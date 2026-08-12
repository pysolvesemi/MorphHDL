#!/usr/bin/env python3

import argparse
import csv
import pathlib
import sys


EXPECTED_HEADER = [
    "operator",
    "semantic_node",
    "increment_status",
    "production_behavior",
    "diagnostic",
    "evidence",
    "rationale",
]

REQUIRED_OPERATORS = {
    "literal",
    "public-reference",
    "local-reference",
    "generate-index-reference",
    "add",
    "subtract",
    "multiply",
    "negate",
    "divide",
    "modulo",
}

STATUS_VALUES = {"implemented", "deferred"}
BEHAVIOR_VALUES = {"validate-and-emit", "reject"}


def fail(message):
    print("parameter-operators: " + message, file=sys.stderr)
    return 1


def main():
    parser = argparse.ArgumentParser(
        description="Validate the MorphHDL parameter-expression support inventory"
    )
    parser.add_argument("manifest", type=pathlib.Path)
    args = parser.parse_args()

    if not args.manifest.is_file():
        return fail("manifest does not exist: {}".format(args.manifest))

    with args.manifest.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        if reader.fieldnames != EXPECTED_HEADER:
            return fail(
                "header is {}, expected {}".format(reader.fieldnames, EXPECTED_HEADER)
            )
        rows = list(reader)

    operators = [row["operator"].strip() for row in rows]
    duplicates = sorted(
        {operator for operator in operators if operators.count(operator) > 1}
    )
    if duplicates:
        return fail("duplicate operators: {}".format(", ".join(duplicates)))

    missing = sorted(REQUIRED_OPERATORS.difference(operators))
    if missing:
        return fail("required operators are missing: {}".format(", ".join(missing)))

    unexpected = sorted(set(operators).difference(REQUIRED_OPERATORS))
    if unexpected:
        return fail("unregistered operators: {}".format(", ".join(unexpected)))

    for line_number, row in enumerate(rows, start=2):
        operator = row["operator"].strip()
        for field in EXPECTED_HEADER:
            if not row[field].strip():
                return fail(
                    "line {} ({}) has an empty {}".format(
                        line_number, operator, field
                    )
                )

        status = row["increment_status"].strip()
        behavior = row["production_behavior"].strip()
        diagnostic = row["diagnostic"].strip()
        evidence = row["evidence"].strip()

        if status not in STATUS_VALUES:
            return fail(
                "line {} has invalid increment_status '{}'".format(
                    line_number, status
                )
            )
        if behavior not in BEHAVIOR_VALUES:
            return fail(
                "line {} has invalid production_behavior '{}'".format(
                    line_number, behavior
                )
            )
        if status == "implemented" and behavior != "validate-and-emit":
            return fail(
                "line {} marks an implemented operator without emission".format(
                    line_number
                )
            )
        if status == "implemented" and diagnostic != "-":
            return fail(
                "line {} gives an implemented operator a rejection diagnostic".format(
                    line_number
                )
            )
        if status == "deferred" and (
            behavior != "reject" or diagnostic == "-"
        ):
            return fail(
                "line {} must fail closed with a diagnostic".format(line_number)
            )
        if evidence == "-":
            return fail("line {} has no test evidence".format(line_number))

    print(
        "Parameter-operator manifest passed ({} operators)".format(len(rows))
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
