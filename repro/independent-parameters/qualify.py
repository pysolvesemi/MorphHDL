#!/usr/bin/env python3
"""Clean source/SBT qualification; report-only mode never claims a clean build."""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Mapping

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def verify_reports(directory: Path, expected: Mapping[str, int]) -> dict[str, int]:
    """Reject absent, duplicate, skipped, partial or failing selected suites."""
    found: dict[str, int] = {}
    for path in sorted(directory.rglob("*.xml")):
        document = ET.parse(path).getroot()
        reports = [document] if document.tag == "testsuite" else list(document.iter("testsuite"))
        for report in reports:
            name = report.get("name", "")
            if name not in expected:
                continue
            if name in found:
                raise RuntimeError(f"duplicate report for {name}")
            for field in ("errors", "failures", "skipped", "disabled"):
                if int(report.get(field, "0")) != 0:
                    raise RuntimeError(f"{name}: nonzero {field}")
            cases = list(report.findall("testcase"))
            if any(case.find(tag) is not None for case in cases
                   for tag in ("failure", "error", "skipped")):
                raise RuntimeError(f"{name}: unsuccessful test case")
            count = int(report.get("tests", "-1"))
            if count != expected[name] or len(cases) != count:
                raise RuntimeError(f"{name}: expected {expected[name]} executed tests, got {count}/{len(cases)}")
            found[name] = count
    missing = set(expected) - set(found)
    if missing:
        raise RuntimeError("missing suite reports: " + ", ".join(sorted(missing)))
    return found


def verify_qualification_reports(root: Path, native_expected: Mapping[str, int],
                                 frontend_expected: Mapping[str, int]) -> dict[str, dict[str, int]]:
    """Both module inventories are mandatory; native-only success is insufficient."""
    return {
        "frontend": verify_reports(root / "frontend" / "target" / "test-reports", frontend_expected),
        "morph": verify_reports(root / "morphhdl" / "target" / "test-reports", native_expected),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scala", choices=("2.12.18", "2.13.12"), default="2.12.18")
    parser.add_argument("--verify-reports-only", type=Path,
                        help="only validate existing JUnit files; does not run or claim a clean build")
    args = parser.parse_args()
    expected = json.loads((HERE / "regression-suites.json").read_text(encoding="utf-8"))
    if len(expected) != 33 or sum(expected.values()) != 333:
        raise RuntimeError("the reviewed 33-suite/333-test inventory changed")
    frontend_expected = json.loads((HERE / "frontend-regression-suites.json").read_text(encoding="utf-8"))
    if len(frontend_expected) != 23 or sum(frontend_expected.values()) != 265:
        raise RuntimeError("the reviewed 23-suite/265-test frontend inventory changed")
    if args.verify_reports_only is not None:
        found = verify_reports(args.verify_reports_only, expected)
        print(f"EXISTING_REPORTS_ONLY_PASS suites={len(found)} tests={sum(found.values())}")
        return 0
    if shutil.which("sbt") is None:
        raise RuntimeError("BLOCKED: SBT is required for clean source qualification")
    evidence = ROOT / "target" / ("independent-domains-" + args.scala)
    # 'clean' below may remove target: put the running log in a root directory.
    logs = ROOT / "independent-parameter-evidence" / args.scala
    logs.mkdir(parents=True, exist_ok=True)
    command = ["sbt", "-batch", "++" + args.scala, "clean", "frontend/test",
               "morph/testOnly " + " ".join(expected)]
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    (logs / "command.json").write_text(json.dumps({"head": head, "cwd": str(ROOT), "argv": command}, indent=2) + "\n")
    with (logs / "clean-sbt.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, errors="replace")
        assert process.stdout is not None
        for line in process.stdout:
            print(line, end="", flush=True)
            log.write(line)
        result = process.wait()
    if result != 0:
        raise RuntimeError(f"clean SBT regression command failed with exit {result}")
    reports = verify_qualification_reports(ROOT, expected, frontend_expected)
    found = reports["morph"]
    frontend_found = reports["frontend"]
    if subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() != head:
        raise RuntimeError("source revision changed during qualification")
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / "qualification.json").write_text(json.dumps({
        "status": "clean_selected_regressions_passed_NOT_all_repository_gates",
        "head": head, "scala": args.scala, "suite_tests": found,
        "total_tests": sum(found.values()), "command": command,
        "frontend_suite_tests": frontend_found,
        "frontend_total_tests": sum(frontend_found.values()),
        "combined_total_tests": sum(found.values()) + sum(frontend_found.values()),
    }, indent=2) + "\n")
    print(f"CLEAN_FRONTEND_REGRESSIONS_PASS scala={args.scala} suites={len(frontend_found)} tests={sum(frontend_found.values())} head={head}")
    print(f"CLEAN_SELECTED_REGRESSIONS_PASS scala={args.scala} suites={len(found)} tests={sum(found.values())} head={head}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, ET.ParseError) as error:
        print(f"QUALIFICATION_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
