#!/usr/bin/env python3
"""Run the separate frozen saturation+widening+capture+register/nested matrix."""
import importlib.util
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('nested_saturation_tools',
    Path(__file__).with_name('check-increment-59i-nested-mechanisms.py'))
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)

if __name__ == '__main__':
    CHECKER.main(saturation=True)
