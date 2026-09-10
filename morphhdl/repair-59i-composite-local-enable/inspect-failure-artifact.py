#!/usr/bin/env python3
"""Print exact failed ScalaTest cases from a downloaded Actions artifact.

A failing shell exits before copying JUnit XML into the evidence directory, but
the complete redirected tests.log is uploaded. Prefer JUnit when present and
otherwise extract each concrete ScalaTest failure with surrounding trace lines.
"""
from __future__ import annotations

import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path


def junit_failures(root: Path) -> int:
    failures = 0
    reports = sorted(root.rglob("*.xml"))
    for report in reports:
        try:
            suite = ET.parse(report).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"invalid JUnit XML {report}: {error}")
        for case in suite.findall(".//testcase"):
            children = list(case.findall("failure")) + list(case.findall("error"))
            for child in children:
                failures += 1
                print("FAILED_SOURCE=JUNIT")
                print("FAILED_SUITE=" + str(suite.get("name", report.stem)))
                print("FAILED_TEST=" + str(case.get("name", "<unnamed>")))
                print("FAILED_CLASS=" + str(case.get("classname", "<unknown>")))
                print("FAILED_TYPE=" + str(child.get("type", "<unknown>")))
                print("FAILED_MESSAGE=" + str(child.get("message", "")))
                body = (child.text or "").strip()
                if body:
                    print("FAILED_TRACE_BEGIN")
                    print(body)
                    print("FAILED_TRACE_END")
    print("JUNIT_REPORTS=" + str(len(reports)))
    return failures


def log_failures(root: Path) -> int:
    failures = 0
    logs = sorted(root.rglob("tests.log"))
    if not logs:
        raise SystemExit("artifact contains neither JUnit XML nor tests.log")
    concrete = re.compile(r"^\[info\] - .+ \*\*\* FAILED \*\*\*$")
    for log in logs:
        lines = log.read_text(errors="replace").splitlines()
        indexes = [index for index, line in enumerate(lines) if concrete.match(line)]
        for ordinal, index in enumerate(indexes, 1):
            failures += 1
            print("FAILED_SOURCE=TESTS_LOG")
            print("FAILED_LOG=" + str(log.relative_to(root)))
            print("FAILED_TEST=" + lines[index][len("[info] - "):-len(" *** FAILED ***")])
            print("FAILED_TRACE_BEGIN")
            start = index
            end = min(len(lines), index + 90)
            for line in lines[start:end]:
                if line.startswith("[info] - ") and line != lines[index]:
                    break
                if line.startswith("[info] Run completed"):
                    break
                print(line)
            print("FAILED_TRACE_END")
    return failures


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    if not root.is_dir():
        raise SystemExit("artifact root is missing: " + str(root))

    failures = junit_failures(root)
    if failures == 0:
        failures = log_failures(root)
    print("EXACT_FAILURES=" + str(failures))
    if failures == 0:
        raise SystemExit("downloaded evidence contains no concrete failed testcase")


if __name__ == "__main__":
    main()
