#!/usr/bin/env python3
"""Synthetic native-report contract mutations; not RTL qualification evidence."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--runner', type=Path,
                    default=Path(__file__).resolve().with_name('run-wa07a-regression.sh'))
args = parser.parse_args()
source = args.runner.read_text()
begin = 'python3 - "${out}" "${native}" <<\'PY\'\n'
assert source.count(begin) == 1, 'historical native report validator must be unique'
validator = source.split(begin, 1)[1].split('\nPY\n', 1)[0]
constant = 'constant-operand-simplification'
historical = ['wire-alias-unnamed', 'wire-alias-named', 'wire-expression-unnamed', constant]

def report(ids):
    return {
        'pass_id': '+'.join(ids), 'executed_passes': ids,
        'executed_rounds': [ids, ids], 'rounds': 2,
        'common_flag_enabled': False,
        'historical_regression_selection': ids == historical,
        'actual_rhs_capture_writeback': True,
        'executed_before_name_allocation': True,
        'procedural_receiver_rewrites': 0,
        'simplified_assignment_count': 6, 'rules': {'test-rule': 6},
        'unnamed_alias_eliminated_count': 1, 'named_alias_eliminated_count': 1,
        'unnamed_expression_eliminated_count': 1,
    }

with tempfile.TemporaryDirectory(prefix='wa07b-report-contract-') as directory:
    root = Path(directory)
    out, native = root / 'out', root / 'native'
    out.mkdir()
    native.mkdir()
    nominal = {
        out / (constant + '-report.json'): report([constant]),
        out / 'wire-assignment-four-pass-report.json': report(historical),
        native / 'candidate-report.json': report([constant]),
    }

    def run(records):
        for path, value in records.items():
            path.write_text(json.dumps(value))
        return subprocess.run([sys.executable, '-c', validator, str(out), str(native)],
                              capture_output=True, text=True, timeout=15)

    result = run(nominal)
    assert result.returncode == 0, 'valid historical reports rejected: ' + result.stderr
    mutations = 0
    for path, value in nominal.items():
        changes = [
            ('common_flag_enabled', True),
            ('historical_regression_selection', not value['historical_regression_selection']),
            ('executed_passes', []),
            ('executed_rounds', []),
            ('executed_rounds', [historical + ['boolean-ternary-simplification']]),
            ('executed_rounds', [value['executed_passes'], []]),
            ('rounds', 3),
            ('pass_id', 'incorrect-stage'),
        ]
        for key, replacement in changes:
            records = copy.deepcopy(nominal)
            records[path][key] = replacement
            result = run(records)
            assert result.returncode != 0, 'accepted report mutation: ' + str(path) + ' ' + key
            mutations += 1
        for key in ('common_flag_enabled', 'historical_regression_selection', 'executed_rounds'):
            records = copy.deepcopy(nominal)
            del records[path][key]
            result = run(records)
            assert result.returncode != 0, 'accepted missing evidence: ' + str(path) + ' ' + key
            mutations += 1
    print('WA07B_HISTORICAL_REPORT_PASS reports=3 rejected_mutations=' + str(mutations))
