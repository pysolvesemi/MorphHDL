"""Exact reversal of reviewed CI enrollment edits for retained contract tests.

Only the explicit policy migration hunks are reversed. Unrelated edits remain
visible to each historical contract test; missing, altered or duplicate hunks
fail rather than normalizing unknown workflow bytes.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / 'morphhdl/contracts/candidate-provenance-workflow-migration.json'


def restore_workflow(path, text):
    contract = json.loads(CONTRACT.read_text())
    assert contract['schema'] == 1
    assert contract['policy'] == 'current-head-authenticated-provenance-2026-10-06'
    record = contract['workflows'][path]
    for patch in reversed(record['patches']):
        assert text.count(patch['after']) == 1, 'missing/duplicate/modified provenance enrollment: ' + path
        text = text.replace(patch['after'], patch['before'], 1)
    return text
