"""Tool choice alone, by a decision model: Ollama's /v1/systemone returns a
probability for each option and writes nothing. Scored on the test
questions of questions.json against the tool each expects, "none" included.

The options are the agent's tools, each as its name and the start of its
own description (the options share a budget of 176 tokens), and one more for "none". Nothing fills arguments here, so the score compares with
"tool chosen" in the other results, not with "right".

usage: python3 decide.py MODEL [THRESHOLD ...]
       A threshold turns a choice whose probability is below it into "none".
Writes decisions-MODEL.json. Standard library only.
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent
AGENT = json.loads((HERE / "../../agents/mbta.agent.json").read_text())
TOOLS = [tool for tool in AGENT["tools"] if tool["type"] == "cypherTemplate" and tool.get("enabled", True)]
NO_TOOL = "none"
# Option keys are short: the endpoint wants keys, and the names have spaces.
KEYS = {f"tool{position}": tool["name"] for position, tool in enumerate(TOOLS, 1)} | {"none": NO_TOOL}
WORDS_PER_OPTION = 14  # laya allows 48 tokens an option and 176 for all of them


def short(tool):
    """A tool's name and the start of its description, cut to fit an option."""
    return " ".join(f'{tool["name"]}: {tool["description"]}'.split()[:WORDS_PER_OPTION])


CRITERIA = {key: short(next(tool for tool in TOOLS if tool["name"] == name))
            for key, name in KEYS.items() if name != NO_TOOL}
CRITERIA["none"] = "No tool above can answer: times, fares, buses, parking, changes, other subjects."


def decide(model, question):
    request = urllib.request.Request(
        os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434") + "/v1/systemone",
        data=json.dumps({"model": model, "state": question,
                         "questions": {"tool": {"type": "choice",
                                                "instructions": "Which tool answers the question?",
                                                "criteria": CRITERIA}}}).encode(),
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            answer = json.loads(response.read())["answers"]["tool"]
    except urllib.error.HTTPError as refusal:  # the reason is in the body
        raise SystemExit(f"{refusal.code}: {refusal.read().decode()}")
    return KEYS[answer["choice"]], {KEYS[key]: value for key, value in answer["probabilities"].items()}


def main():
    model, thresholds = sys.argv[1], [float(value) for value in sys.argv[2:]] or [0.0]
    rows = json.loads((HERE / "questions.json").read_text())["test"]
    decide(model, "warm up")
    started, decided = time.time(), []
    for row in rows:
        choice, probabilities = decide(model, row["question"])
        decided.append({"question": row["question"], "expected": row["tool"] or NO_TOOL,
                        "chose": choice, "probability": round(probabilities[choice], 3),
                        "probabilities": {name: round(value, 3) for name, value in probabilities.items()}})
    seconds_each = (time.time() - started) / len(rows)
    print(f"{model}: {len(rows)} questions, {seconds_each * 1000:.0f} ms each")
    for threshold in thresholds:
        chosen = [NO_TOOL if row["probability"] < threshold else row["chose"] for row in decided]
        right = sum(choice == row["expected"] for choice, row in zip(chosen, decided))
        no_tool = [(choice, row) for choice, row in zip(chosen, decided) if row["expected"] == NO_TOOL]
        print(f"  threshold {threshold:.2f}: tool chosen {right} of {len(rows)}; "
              f"of {len(no_tool)} with no tool, {sum(choice == NO_TOOL for choice, _ in no_tool)} said none")
    slug = re.sub(r"[^a-z0-9]+", "-", model.lower())
    (HERE / f"decisions-{slug}.json").write_text(
        json.dumps({"model": model, "seconds_each": round(seconds_each, 3), "rows": decided}, indent=1) + "\n")


if __name__ == "__main__":
    main()
