#!/usr/bin/env python3
"""Run exact 59i/60g bidirectional composition controls."""
from __future__ import annotations
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "morphhdl/scripts/check-increment-59i-rollout-composition.py"
spec = importlib.util.spec_from_file_location("increment_59i_rollout_composition_test", SOURCE)
if spec is None or spec.loader is None:
    raise RuntimeError("cannot load 59i rollout-composition reviewer")
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)
review.verify(ROOT)
review.self_test(ROOT)
