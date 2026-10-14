#!/usr/bin/env python3
"""SPEC-search-semantic T23: calibrate semantic.min_score / semantic.top_k with a golden query set.

Input (TSV, UTF-8, header optional): <question>\t<expected segmentId>[,<expected segmentId>...]
(empty second column = a question that must return NOTHING, i.e. "không đủ căn cứ").

For each candidate min_score it replays every question against POST /search/semantic, reads the
returned passages (the server applies its CURRENT semantic.min_score, so set it first in System Config
or pass --sweep to evaluate offline from the returned cosineScore: the endpoint returns every passage
that passed the server threshold, so run this with the server threshold set LOW, e.g. 0.30).

Reports recall@10, precision@5 and the empty-answer rate per candidate threshold.
Usage:
  TOKEN=<bearer> python3 semantic-calibrate.py golden.tsv --base http://localhost:3001 --sweep 0.35,0.40,0.45,0.50,0.55,0.60
Only the standard library is used.
"""
import argparse
import csv
import json
import os
import sys
import urllib.request


def ask(base, token, question):
    req = urllib.request.Request(
        base.rstrip("/") + "/search/semantic",
        data=json.dumps({"query": question, "size": 20}).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("golden")
    parser.add_argument("--base", default="http://localhost:3001")
    parser.add_argument("--sweep", default="0.40,0.45,0.50,0.55,0.60")
    args = parser.parse_args()
    token = os.environ.get("TOKEN")
    if not token:
        sys.exit("Set TOKEN to a bearer token of an R1/R2 user whose scope contains the golden documents")

    rows = []
    with open(args.golden, encoding="utf-8", newline="") as handle:
        for record in csv.reader(handle, delimiter="\t"):
            if not record or record[0].strip().lower() in ("question", "câu hỏi"):
                continue
            expected = {s.strip() for s in (record[1] if len(record) > 1 else "").split(",") if s.strip()}
            rows.append((record[0].strip(), expected))

    answers = [(q, exp, ask(args.base, token, q)) for q, exp in rows]
    print("questions: %d" % len(answers))
    print("%-10s %-12s %-14s %-12s" % ("min_score", "recall@10", "precision@5", "empty-rate"))
    for threshold in [float(x) for x in args.sweep.split(",")]:
        recalls, precisions, empties = [], [], 0
        for _, expected, response in answers:
            passed = [r for r in response["results"] if r["matchedLexical"] or (r["cosineScore"] or 0) >= threshold]
            ids = [r["segmentId"] for r in passed]
            if not ids:
                empties += 1
            if expected:
                recalls.append(len(expected & set(ids[:10])) / len(expected))
                precisions.append(len(expected & set(ids[:5])) / max(1, min(5, len(ids))))
        recall = sum(recalls) / len(recalls) if recalls else float("nan")
        precision = sum(precisions) / len(precisions) if precisions else float("nan")
        print("%-10.2f %-12.3f %-14.3f %-12.3f" % (threshold, recall, precision, empties / len(answers)))


if __name__ == "__main__":
    main()
