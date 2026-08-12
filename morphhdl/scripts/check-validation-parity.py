#!/usr/bin/env python3

import argparse
import csv
import pathlib
import sys


EXPECTED_HEADER = [
    "check_id",
    "legacy_guard",
    "concrete_witness",
    "symbolic_disposition",
    "symbolic_guard",
    "implementation_status",
    "legacy_evidence",
    "symbolic_evidence",
    "rationale",
]

REQUIRED_CHECKS = {
    "PhaseCheckIoBundle",
    "PhaseCheckHierarchy",
    "PhaseInferWidth",
    "PhaseCheck_noLatchNoOverride",
    "PhaseCheck_noRegisterAsLatch",
    "PhaseCheckCombinationalLoops",
    "PhaseCheckCrossClock",
    "PhaseContext.checkGlobalData",
}

WITNESS_VALUES = {"required", "not-applicable"}
DISPOSITION_VALUES = {"reuse", "adapt", "equivalent", "not-applicable"}
STATUS_VALUES = {"planned", "partial", "implemented"}


def fail(message):
    print("validation-parity: " + message, file=sys.stderr)
    return 1


def main():
    parser = argparse.ArgumentParser(
        description="Validate MorphHDL inherited-check parity metadata"
    )
    parser.add_argument("manifest", type=pathlib.Path)
    parser.add_argument(
        "--release",
        action="store_true",
        help="require every inherited check to be fully implemented",
    )
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

    ids = [row["check_id"].strip() for row in rows]
    duplicates = sorted({check_id for check_id in ids if ids.count(check_id) > 1})
    if duplicates:
        return fail("duplicate check IDs: {}".format(", ".join(duplicates)))

    missing = sorted(REQUIRED_CHECKS.difference(ids))
    if missing:
        return fail("required inherited checks are missing: {}".format(", ".join(missing)))

    unexpected = sorted(set(ids).difference(REQUIRED_CHECKS))
    if unexpected:
        return fail("unregistered inherited checks: {}".format(", ".join(unexpected)))

    for line_number, row in enumerate(rows, start=2):
        check_id = row["check_id"].strip()
        for field in EXPECTED_HEADER:
            if not row[field].strip():
                return fail("line {} ({}) has an empty {}".format(line_number, check_id, field))

        witness = row["concrete_witness"].strip()
        disposition = row["symbolic_disposition"].strip()
        status = row["implementation_status"].strip()

        if witness not in WITNESS_VALUES:
            return fail("line {} has invalid concrete_witness '{}'".format(line_number, witness))
        if disposition not in DISPOSITION_VALUES:
            return fail("line {} has invalid symbolic_disposition '{}'".format(line_number, disposition))
        if status not in STATUS_VALUES:
            return fail("line {} has invalid implementation_status '{}'".format(line_number, status))
        if (witness == "not-applicable" or disposition == "not-applicable") and not row[
            "rationale"
        ].strip():
            return fail("line {} uses not-applicable without a rationale".format(line_number))
        legacy_evidence = row["legacy_evidence"].strip()
        symbolic_evidence = row["symbolic_evidence"].strip()
        if legacy_evidence == "-":
            return fail("line {} has no legacy test evidence".format(line_number))
        if status in {"partial", "implemented"} and symbolic_evidence == "-":
            return fail("line {} marks {} without symbolic test evidence".format(line_number, status))
        if args.release and status != "implemented":
            return fail(
                "release gate requires implemented status for {}, found {}".format(
                    check_id, status
                )
            )
        if args.release and (legacy_evidence == "-" or symbolic_evidence == "-"):
            return fail(
                "release gate requires legacy and symbolic evidence for {}".format(check_id)
            )

    mode = "release" if args.release else "development"
    print("Validation-parity manifest passed ({}, {} inherited checks)".format(mode, len(rows)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
