#!/usr/bin/env python3
"""Validate historical reproduction only; never use this as a feature-success gate."""
import re
import sys
from pathlib import Path

EXPECTED = [
    'PROBE ports: GENERATION_SUCCEEDED',
    'PROBE blackbox: REPRODUCED SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-INTEGER-GENERIC-DOMAIN-INVALID',
    'PROBE value: REPRODUCED SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED',
    'PROBE timeout: REPRODUCED SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING',
]

def check(text: str) -> None:
    plain = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', text)
    actual = []
    for line in plain.splitlines():
        line = re.sub(r'^\[info\]\s*', '', line.strip())
        if line.startswith('PROBE '):
            actual.append(line)
    if actual != EXPECTED:
        raise RuntimeError(f'Baseline outcomes differ: {actual!r}; expected {EXPECTED!r}')

if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: check_baseline.py LOG')
    check(Path(sys.argv[1]).read_text())
    print('BASELINE: four exact outcomes reproduced; this does NOT qualify the repair')
