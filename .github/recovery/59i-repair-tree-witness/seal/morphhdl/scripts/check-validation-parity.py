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
    parser.add_argument(
        "--live-phase-ids",
        action="append",
        default=[],
        type=pathlib.Path,
        help="compare manifest IDs and order with a live shared phase-plan inventory; may be repeated",
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

    if not ids:
        return fail("manifest contains no inherited checks")

    if not args.live_phase_ids:
        return fail("validation parity gate requires at least one --live-phase-ids inventory")

    for live_path in args.live_phase_ids:
        if not live_path.is_file():
            return fail("live phase-ID inventory does not exist: {}".format(live_path))
        live_lines = live_path.read_text(encoding="utf-8").splitlines()
        if not live_lines:
            return fail("live phase-ID inventory is empty: {}".format(live_path))
        if any(not value.strip() for value in live_lines):
            return fail("live phase-ID inventory has an empty ID: {}".format(live_path))
        live_ids = [value.strip() for value in live_lines]
        live_duplicates = sorted(
            {check_id for check_id in live_ids if live_ids.count(check_id) > 1}
        )
        if live_duplicates:
            return fail(
                "live phase-ID inventory {} has duplicates: {}".format(
                    live_path, ", ".join(live_duplicates)
                )
            )
        if live_ids != ids:
            return fail(
                "manifest IDs/order {} do not match live inventory {}".format(
                    ids, live_ids
                )
            )

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
