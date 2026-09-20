#!/usr/bin/env python3
"""Probe a proposed repair; NEVER grant source, hardware or merge qualification."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

HEAD = '883c5d8f088a0e2eab35592cf171d87792d30bf4'
BASE = '7f355a859e7e88ca343e1ff82f261fb47b3311d0'
PATCH_SHA256 = 'e664c4b0f35764cdc5e037925b3a3a171213536de62a3cf245e4b830f93ae258'
SCALA_FILES = (
    'ParameterizedVerilogStructural', 'TypedBalancedReductionBackend',
    'TypedBalancedReductionBridgeReplay', 'TypedBalancedReductionCapture',
    'TypedBalancedReductionClosedGraph', 'TypedBalancedReductionCompositeReplay',
    'TypedBalancedReductionStageReplay',
)
PATHS = {'morphhdl/src/main/scala/spinal/core/internals/' + x + '.scala' for x in SCALA_FILES}
PATHS.add('morphhdl/scripts/test-increment-59g-report-inventory.py')
SUITES = (
    'TypedBalancedReductionCaptureTests', 'TypedBalancedReductionCaptureSafetyTests',
    'TypedBalancedReductionStageReplayTests', 'TypedBalancedReductionClosedGraphTests',
    'TypedBalancedReductionBridgeReplayTests', 'TypedBalancedReductionCompositeLocalEnableTests',
    'TypedBalancedReductionCompositeWideningTests',
    'TypedBalancedReductionCompositeWideningCaptureTests',
    'TypedBalancedReductionCompositeWideningIdentityTests',
)


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--root', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--scala', choices=['2.12.18', '2.13.12'], required=True)
    a = p.parse_args()
    root, out = a.root.resolve(), a.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    patch = Path(__file__).with_name('repair.patch')
    assert git(root, 'rev-parse', 'HEAD') == HEAD
    assert not git(root, 'status', '--porcelain', '--untracked-files=all')
    assert digest(patch) == PATCH_SHA256
    assert git(root, 'rev-parse', BASE + '^{commit}') == BASE
    receipt = {'schema': 1, 'kind': 'unsealed-repair-diagnostic', 'head': HEAD,
               'baseline': BASE, 'scala': a.scala, 'patch_sha256': PATCH_SHA256,
               'source_qualification': False, 'hardware_qualification': False,
               'merge_qualification': False, 'completed': False, 'commands': []}
    def save():
        (out / 'receipt.json').write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    def run(name, command, cwd=root):
        save()
        log = out / (name + '.log')
        with log.open('w') as stream:
            result = subprocess.run(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT)
        receipt['commands'].append({'name': name, 'command': command,
            'cwd': str(cwd), 'returncode': result.returncode, 'log_sha256': digest(log)})
        save()
        print(log.read_text()[-24000:], flush=True)
        result.check_returncode()
    save()
    run('patch-check', ['git', 'apply', '--check', str(patch)])
    run('patch-apply', ['git', 'apply', str(patch)])
    assert set(git(root, 'diff', '--name-only').splitlines()) == PATHS
    run('diff-check', ['git', 'diff', '--check'])
    diff = subprocess.check_output(['git', 'diff', '--binary'], cwd=root)
    (out / 'applied.patch').write_bytes(diff)
    receipt['applied_diff_sha256'] = hashlib.sha256(diff).hexdigest()
    receipt['modified_files'] = {path: digest(root / path) for path in sorted(PATHS)}
    # The ABI checker, tests, workflows, certificates and build configuration
    # are not part of the applied patch. Their original checks stay untouched.
    run('59g-report-inventory', ['python3', '-B', 'morphhdl/scripts/test-increment-59g-report-inventory.py'])
    baseline = out / 'baseline'
    run('baseline-worktree', ['git', 'worktree', 'add', '--detach', str(baseline), BASE])
    run('baseline-package', ['sbt', '-batch', '++' + a.scala, 'morph / Compile / packageBin'], baseline)
    run('patched-package', ['sbt', '-batch', '++' + a.scala, 'morph / Compile / packageBin'])
    binary = a.scala.rsplit('.', 1)[0]
    def jar(checkout):
        jars = [path for path in (checkout / 'morphhdl/target' / ('scala-' + binary)).glob('*.jar')
                if not path.name.endswith(('-tests.jar', '-sources.jar', '-javadoc.jar'))]
        assert len(jars) == 1, jars
        return jars[0]
    before, after = jar(baseline), jar(root)
    receipt['baseline_jar_sha256'] = digest(before)
    receipt['patched_jar_sha256'] = digest(after)
    run('exact-baseline-abi', ['python3', '-B', 'morphhdl/scripts/check-jvm-binary-compatibility.py',
        '--label', 'morph-report-' + a.scala, '--baseline', str(before), '--current', str(after)])
    assert 'JVMABI_OK:' in (out / 'exact-baseline-abi.log').read_text()
    tests = 'morph/Test/testOnly ' + ' '.join('spinal.core.internals.' + name for name in SUITES)
    run('affected-replay-tests', ['sbt', '-batch', '++' + a.scala, tests])
    # Use the reviewed runtime inventory. Several suites intentionally create
    # cases from bounded loops, so counting only literal test("...") calls in
    # source undercounts their real XML inventory.
    expected = {
        'TypedBalancedReductionCaptureTests': 10,
        'TypedBalancedReductionCaptureSafetyTests': 12,
        'TypedBalancedReductionStageReplayTests': 23,
        'TypedBalancedReductionClosedGraphTests': 27,
        'TypedBalancedReductionBridgeReplayTests': 12,
        'TypedBalancedReductionCompositeLocalEnableTests': 31,
        'TypedBalancedReductionCompositeWideningTests': 4,
        'TypedBalancedReductionCompositeWideningCaptureTests': 4,
        'TypedBalancedReductionCompositeWideningIdentityTests': 3,
    }
    assert set(expected) == set(SUITES) and sum(expected.values()) == 126
    seen = {}
    reports = out / 'test-reports'
    reports.mkdir()
    for report in (root / 'morphhdl/target/test-reports').glob('TEST-*.xml'):
        node = ET.parse(report).getroot()
        name = node.get('name', '').split('.')[-1]
        if name not in expected:
            continue
        assert name not in seen
        assert int(node.get('tests', '0')) == expected[name]
        assert len(node.findall('testcase')) == expected[name]
        assert all(int(node.get(k, '0')) == 0 for k in ('failures', 'errors', 'skipped'))
        assert not node.findall('.//skipped') and not node.findall('.//failure') and not node.findall('.//error')
        seen[name] = expected[name]
        (reports / report.name).write_bytes(report.read_bytes())
    assert seen == expected, (seen, expected)
    assert git(root, 'rev-parse', 'HEAD') == HEAD
    assert {path: digest(root / path) for path in PATHS} == receipt['modified_files']
    receipt['tests'] = seen
    receipt['completed'] = True
    save()
    print('DIAGNOSTIC PASS ONLY: source sealing and all final-head CI remain required.')


if __name__ == '__main__':
    main()
