"""The core of the Dataproc similarity notebooks, with no database and no GDS.

Natural log of four census figures, min-max scaling, the 15 nearest
neighbours of each area by Euclidean distance, similarity 1 / (1 + distance),
and a cutoff at the first percentile of similarity: the notebook's steps up
to its IS_SIMILAR relationships. Louvain is left out.

usage: similarity.py <census csv>   prints source, target, similarity as TSV
"""
import csv
import math
import sys

FEATURES = ["population", "medianHouseholdIncome", "medianHomePrice", "percentOver25WithBachelors"]
AREA_FILTER = "Metro"  # the notebook's WHERE row.name CONTAINS 'Metro': micro areas are dropped
TOP_K = 15
CUTOFF_PERCENTILE = 0.01


def read_areas(csv_path):
    with open(csv_path, newline="") as csv_file:
        return [{"name": row["name"], "logs": [math.log(float(row[feature])) for feature in FEATURES]}
                for row in csv.DictReader(csv_file) if AREA_FILTER in row["name"]]


def min_max_scale(areas):
    lows = [min(area["logs"][index] for area in areas) for index in range(len(FEATURES))]
    highs = [max(area["logs"][index] for area in areas) for index in range(len(FEATURES))]
    for area in areas:
        area["scaled"] = [(value - low) / (high - low)
                          for value, low, high in zip(area["logs"], lows, highs)]
    return areas


def nearest_neighbours(areas):
    edges = []
    for source in areas:
        candidates = []
        for target in areas:
            if target is source:
                continue
            distance = math.sqrt(sum((a - b) ** 2 for a, b in zip(source["scaled"], target["scaled"])))
            candidates.append((round(1.0 / (1.0 + distance), 9), target["name"]))
        candidates.sort(key=lambda candidate: (-candidate[0], candidate[1]))
        edges.extend((source["name"], target_name, similarity)
                     for similarity, target_name in candidates[:TOP_K])
    return edges


def apply_cutoff(edges):
    similarities = sorted(similarity for _, _, similarity in edges)
    cutoff = similarities[int(len(similarities) * CUTOFF_PERCENTILE)]
    return [edge for edge in edges if edge[2] >= cutoff]


if __name__ == "__main__":
    kept_edges = apply_cutoff(nearest_neighbours(min_max_scale(read_areas(sys.argv[1]))))
    for source_name, target_name, similarity in sorted(kept_edges):
        print(f"{source_name}\t{target_name}\t{similarity:.9f}")
