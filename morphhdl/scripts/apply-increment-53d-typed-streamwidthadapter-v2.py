#!/usr/bin/env python3
import runpy
import subprocess

runpy.run_path(
    "morphhdl/scripts/apply-increment-53d-typed-streamwidthadapter.py",
    run_name="__main__",
)
subprocess.check_call([
    "python3",
    "morphhdl/scripts/check-typed-native-change-manifest.py",
    "--write",
])
