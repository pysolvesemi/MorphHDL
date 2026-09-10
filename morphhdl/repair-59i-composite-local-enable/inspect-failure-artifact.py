#!/usr/bin/env python3
"""Print exact failed ScalaTest cases from a downloaded Actions artifact."""
from __future__ import annotations

import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    if not root.is_dir():
        raise SystemExit("artifact root is missing: " + str(root))

    reports = sorted(root.rglob("*.xml"))
    if not reports:
        raise SystemExit("artifact contains no JUnit XML")
    failures = 0
    for report in reports:
        try:
            suite = ET.parse(report).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"invalid JUnit XML {report}: {error}")
        for case in suite.findall(".//testcase"):
            children = list(case.findall("failure")) + list(case.findall("error"))
            for child in children:
                failures += 1
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
    print("JUNIT_FAILURES=" + str(failures))
    if failures == 0:
        # Preserve useful context if the runner copied a different report root.
        logs = sorted(root.rglob("tests.log"))
        for log in logs:
            text = log.read_text(errors="replace")
            matches = list(re.finditer(r"(?m)^.*(?:FAILED|Exception|error).*$", text))
            for match in matches[-100:]:
                print("LOG_DIAGNOSTIC=" + match.group(0))
        raise SystemExit("downloaded reports contain no failed testcase")


if __name__ == "__main__":
    main()
