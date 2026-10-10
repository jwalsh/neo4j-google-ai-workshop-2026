# /// script
# requires-python = ">=3.11"
# dependencies = ["neo4j"]
# ///
"""Writes questions.json: transit questions with the tool and parameters a
right answer runs, generated from the names in the transit graph.

Phrasings are fixed lists below; stations, routes and municipalities are
drawn from the graph with a fixed seed, so the file is reproducible from the
same feed. A station is sometimes written short ("Kendall" for
"Kendall/MIT") when the short form still matches one stored name. The split
into train, validation and test is by row, after shuffling; the same
phrasing appears in all three, the same pair of stations does not.

usage: uv run questions.py   (needs bb local-mbta; reads ../../mbta-graph.env)
"""
import json
import random
from pathlib import Path

from neo4j import GraphDatabase

HERE = Path(__file__).parent
SEED = 20261009

JOURNEY = "Subway Journey Between Two Stations"
ROUTES = "Which Routes Serve a Station"
ORDER = "Stations on a Route in Order"
NEAR = "Stations Near a Station"
PLATFORMS = "Platforms in a Station"
MUNICIPALITY = "Stations in a Municipality"

# {a} is where the journey starts and {b} where it ends, in every phrasing.
JOURNEY_PHRASINGS = [
    "How do I get from {a} to {b}?",
    "Get me from {a} to {b}.",
    "I'm at {a} and need to reach {b}.",
    "To {b} from {a}, please.",
    "What's the way to {b} if I start at {a}?",
    "Going to {b}, leaving from {a}.",
    "{a} to {b}?",
    "Directions from {a} to {b}.",
    "I need to be at {b}. I'm at {a} now.",
    "Starting at {a}, how do I reach {b}?",
]
ONE_STATION_PHRASINGS = {
    ROUTES: ("stationName", ["Which routes serve {a}?", "What lines stop at {a}?", "What runs through {a}?"]),
    NEAR: ("nearStation", ["What is near {a}?", "What can I walk to from {a}?", "Which stations are close to {a}?"]),
    PLATFORMS: ("platformsOf", ["What platforms does {a} have?", "Which platforms at {a} are wheelchair accessible?",
                                "List the stops inside {a}."]),
}
ORDER_PHRASINGS = ["List the stops on the {r} in order.", "What stations are on the {r}?", "Where does the {r} go?"]
MUNICIPALITY_PHRASINGS = ["Which stations are in {m}?", "What stations are there in {m}?", "Stations in {m}?"]
WITHIN_PHRASINGS = ["Which stations are within {n} metres of {a}?", "What is within {n} metres of {a}?"]
# No tool answers these: no timetable, no fares, no buses, nothing written,
# and nothing about other graphs.
NO_TOOL_PHRASINGS = [
    "When is the next train from {a}?",
    "What time does the last train leave {a}?",
    "How long does it take from {a} to {b}?",
    "How much is a ticket from {a} to {b}?",
    "Which bus goes to {a}?",
    "Is the {r} delayed right now?",
    "Delete the {r}.",
    "Rename {a} to {b}.",
    "Is there parking at {a}?",
    "Who built {a}?",
]
NO_TOOL_FIXED = [
    "What is the weather in Boston?", "Which managers hold Allstate?", "Who cites SWE-bench?",
    "Which books mention telemetry?", "Write a haiku about the subway.", "What is 17 times 23?",
]


def read_env(path):
    return dict(line.strip().split("=", 1) for line in path.read_text().splitlines()
                if "=" in line and not line.startswith("#"))


def graph_names():
    env = read_env(HERE / "../../mbta-graph.env")
    with GraphDatabase.driver(env["NEO4J_URI"], auth=(env["NEO4J_USERNAME"], env["NEO4J_PASSWORD"])) as driver:
        column = lambda statement: [record["value"] for record in driver.execute_query(statement).records]
        return {
            "stations": column("MATCH (s:Station) RETURN DISTINCT s.name AS value ORDER BY value"),
            "subway_stations": column(
                "MATCH (s:Station)-[c:CONNECTS]->() WHERE c.kind <> 'commuter rail' "
                "RETURN DISTINCT s.name AS value ORDER BY value"),
            "routes": column("MATCH (r:Route) RETURN r.name AS value ORDER BY value"),
            "municipalities": column(
                "MATCH (s:Station) WHERE s.municipality IS NOT NULL RETURN DISTINCT s.municipality AS value ORDER BY value"),
        }


def main():
    names = graph_names()
    chooser = random.Random(SEED)

    def written(station):
        """The station as a person might write it, and still one stored name."""
        short = station.split("/")[0]
        matches = [name for name in names["stations"] if short.lower() in name.lower()]
        return short if short != station and len(matches) == 1 and chooser.random() < 0.6 else station

    questions = []
    for _ in range(140):
        start, end = chooser.sample(names["subway_stations"], 2)
        questions.append({"question": chooser.choice(JOURNEY_PHRASINGS).format(a=written(start), b=written(end)),
                          "tool": JOURNEY, "parameters": {"fromStation": start, "toStation": end}})
    for tool, (parameter, phrasings) in ONE_STATION_PHRASINGS.items():
        for _ in range(30):
            station = chooser.choice(names["stations"])
            questions.append({"question": chooser.choice(phrasings).format(a=written(station)),
                              "tool": tool, "parameters": {parameter: station}})
    for _ in range(15):
        station, metres = chooser.choice(names["stations"]), chooser.choice([300, 500, 800, 1000, 2000])
        questions.append({"question": chooser.choice(WITHIN_PHRASINGS).format(a=written(station), n=metres),
                          "tool": NEAR, "parameters": {"nearStation": station, "withinMetres": metres}})
    for _ in range(30):
        route = chooser.choice(names["routes"])
        questions.append({"question": chooser.choice(ORDER_PHRASINGS).format(r=route),
                          "tool": ORDER, "parameters": {"routeName": route}})
    for _ in range(30):
        municipality = chooser.choice(names["municipalities"])
        questions.append({"question": chooser.choice(MUNICIPALITY_PHRASINGS).format(m=municipality),
                          "tool": MUNICIPALITY, "parameters": {"municipalityName": municipality}})
    for _ in range(70):
        start, end = chooser.sample(names["subway_stations"], 2)
        questions.append({"question": chooser.choice(NO_TOOL_PHRASINGS).format(
                              a=written(start), b=written(end), r=chooser.choice(names["routes"])),
                          "tool": None, "parameters": {}})
    questions += [{"question": question, "tool": None, "parameters": {}} for question in NO_TOOL_FIXED]

    unique = list({row["question"]: row for row in questions}.values())
    chooser.shuffle(unique)
    split = {"train": unique[:80], "validation": unique[80:160], "test": unique[160:]}
    (HERE / "questions.json").write_text(json.dumps({"seed": SEED, "names": names, **split}, indent=1) + "\n")
    for part, rows in split.items():
        print(part, len(rows), "of which no tool:", sum(row["tool"] is None for row in rows))


if __name__ == "__main__":
    main()
