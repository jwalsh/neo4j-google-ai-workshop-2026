# /// script
# requires-python = ">=3.11"
# dependencies = ["dspy==3.4.0", "optuna"]
# ///
"""The transit agent's two questions to a model, as a DSPy program, scored
plain and after two of DSPy's optimizers have searched for a better prompt.

The program is the shape of workshop.agent: choose a tool, then fill that
tool's arguments. The tools and their descriptions come from
../../agents/mbta.agent.json. What surrounds the model is copied from
workshop.agent too, without a database: an argument must appear in the
question (grounded), and a name must match one stored name (resolve-value,
against the names in questions.json).

usage: uv run optimize.py MODEL [plain|fewshot|mipro ...]
       OLLAMA_URL names the server, default this machine.
Writes results-MODEL.json and, per optimizer, program-MODEL-NAME.json.
"""
import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Literal, Optional

import dspy

HERE = Path(__file__).parent
NO_TOOL = "none"
AGENT = json.loads((HERE / "../../agents/mbta.agent.json").read_text())
TOOLS = [tool for tool in AGENT["tools"] if tool["type"] == "cypherTemplate" and tool.get("enabled", True)]
DATA = json.loads((HERE / "questions.json").read_text())
STORED = {  # which stored names each string parameter is compared with
    "routeName": DATA["names"]["routes"],
    "municipalityName": DATA["names"]["municipalities"],
}


def parameters_of(tool):
    """A tool's parameters without limit: no question here sets one."""
    return [parameter for parameter in tool["config"]["parameters"] if parameter["name"] != "limit"]


def choose_signature():
    instructions = (
        f'You route a question to one tool of the agent "{AGENT["name"]}". '
        f'If no tool can answer the question, the tool is "{NO_TOOL}".\n\nTools:\n'
        + "\n".join(f'- {tool["name"]}: {tool["description"]}' for tool in TOOLS))
    return dspy.Signature(
        {"question": (str, dspy.InputField()),
         "tool": (Literal[tuple([tool["name"] for tool in TOOLS] + [NO_TOOL])], dspy.OutputField())},
        instructions)


def arguments_signature(tool):
    fields = {"question": (str, dspy.InputField())}
    for parameter in parameters_of(tool):
        kind = str if parameter["data_type"] == "string" else Optional[int]
        fields[parameter["name"]] = (kind, dspy.OutputField(desc=parameter["description"]))
    return dspy.Signature(
        fields,
        f'Fill in the arguments of the tool "{tool["name"]}": {tool["description"]} '
        "Copy each value from the question as written. Do not invent one.")


class TransitAgent(dspy.Module):
    def __init__(self):
        super().__init__()
        self.choose = dspy.Predict(choose_signature())
        self.fill = {tool["name"]: dspy.Predict(arguments_signature(tool)) for tool in TOOLS}
        for position, predictor in enumerate(self.fill.values()):
            setattr(self, f"fill_{position}", predictor)  # so optimizers find them

    def forward(self, question):
        tool = self.choose(question=question).tool
        if tool not in self.fill:
            return dspy.Prediction(tool=NO_TOOL, arguments={})
        filled = self.fill[tool](question=question)
        names = [parameter["name"] for parameter in parameters_of(next(t for t in TOOLS if t["name"] == tool))]
        return dspy.Prediction(tool=tool, arguments={name: getattr(filled, name, None) for name in names})


def resolve(parameter_name, written_value):
    """workshop.agent/resolve-value: an exact match without regard to case,
    else every stored name containing it."""
    stored = STORED.get(parameter_name, DATA["names"]["stations"])
    exact = [name for name in stored if name.lower() == written_value.lower()]
    return exact or [name for name in stored if written_value.lower() in name.lower()]


def outcome(question, prediction):
    """What would run: (tool, parameters), or (None, reason) when nothing would."""
    if prediction.tool == NO_TOOL:
        return None, "no tool"
    tool = next(t for t in TOOLS if t["name"] == prediction.tool)
    numbers = set(re.findall(r"\d+(?:\.\d+)?", question))
    parameters = {}
    for parameter in parameters_of(tool):
        given = prediction.arguments.get(parameter["name"])
        if isinstance(given, str):
            if not given.strip() or given.lower() not in question.lower():
                given = None  # grounded
            else:
                candidates = resolve(parameter["name"], given)
                if len(candidates) != 1:
                    return None, f"{len(candidates)} stored values match {given!r}"
                given = candidates[0]
        elif given is not None and str(given) not in numbers:
            given = None
        if given is None and not parameter.get("is_optional"):
            return None, f"missing {parameter['name']}"
        if given is not None:
            parameters[parameter["name"]] = given
    return prediction.tool, parameters


def grade(example, prediction):
    """right, refused or wrong, as experiments 009 and 013 grade."""
    ran_tool, ran = outcome(example.question, prediction)
    if example.tool is None:
        return "right" if ran_tool is None else "wrong"
    if ran_tool is None:
        return "refused"
    wanted = example.parameters
    return "right" if ran_tool == example.tool and {name: ran.get(name) for name in wanted} == wanted else "wrong"


def metric(example, prediction, trace=None):
    return grade(example, prediction) == "right"


def examples(part):
    return [dspy.Example(question=row["question"], tool=row["tool"], parameters=row["parameters"]).with_inputs("question")
            for row in DATA[part]]


def score(program, rows):
    started, graded = time.time(), []
    for example in rows:
        try:
            prediction = program(question=example.question)
        except Exception as failure:  # an answer the adapter cannot read is a refusal
            prediction = dspy.Prediction(tool=NO_TOOL, arguments={"error": str(failure)[:120]})
        graded.append({"question": example.question, "expected": example.tool, "grade": grade(example, prediction),
                       "chose": prediction.tool, "arguments": prediction.arguments,
                       "ran": outcome(example.question, prediction)[1]})
    counts = {name: sum(row["grade"] == name for row in graded) for name in ("right", "refused", "wrong")}
    return {**counts, "of": len(graded), "seconds_each": round((time.time() - started) / len(graded), 2), "rows": graded}


def optimized(name, lm, train, validation):
    if name == "fewshot":
        optimizer = dspy.BootstrapFewShotWithRandomSearch(
            metric=metric, max_bootstrapped_demos=3, max_labeled_demos=3, num_candidate_programs=8, num_threads=1)
        return optimizer.compile(TransitAgent(), trainset=train, valset=validation)
    if name == "mipro":
        # The model being tuned also proposes the instructions: nothing larger is here.
        optimizer = dspy.MIPROv2(metric=metric, auto="light", prompt_model=lm, task_model=lm, num_threads=1)
        return optimizer.compile(TransitAgent(), trainset=train, valset=validation)
    raise SystemExit(f"no optimizer named {name}")


def main():
    model, variants = sys.argv[1], sys.argv[2:] or ["plain", "fewshot", "mipro"]
    lm = dspy.LM(f"openai/{model}", api_base=os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434") + "/v1",
                 api_key="ollama", temperature=0, max_tokens=400)
    dspy.configure(lm=lm)
    train, validation, test = examples("train"), examples("validation"), examples("test")
    slug = re.sub(r"[^a-z0-9]+", "-", model.lower())
    results_path = HERE / f"results-{slug}.json"
    results = json.loads(results_path.read_text()) if results_path.exists() else {}
    for variant in variants:
        started = time.time()
        program = TransitAgent() if variant == "plain" else optimized(variant, lm, train, validation)
        searching = round(time.time() - started)
        if variant != "plain":
            program.save(str(HERE / f"program-{slug}-{variant}.json"))
        results[variant] = {"model": model, "search_seconds": searching, **score(program, test)}
        results_path.write_text(json.dumps(results, indent=1) + "\n")
        print(variant, {key: value for key, value in results[variant].items() if key != "rows"}, flush=True)


if __name__ == "__main__":
    main()
