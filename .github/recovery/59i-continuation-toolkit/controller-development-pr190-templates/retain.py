#!/usr/bin/env python3
"""Retain real reports first, then fail closed on missing or inconsistent evidence."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

from prepare import BASE, HEAD, TREE, PATCH_SHA256, BUNDLE_SHA256, PAYLOAD, git, paths, require


def retain():
    _, root, out = paths()
    out.mkdir(parents=True, exist_ok=True)
    errors, rows = [], []
    result = dict(development_only=True, qualification=False, full_ci=False,
                  source_sealed=False, scala=os.environ['SCALA_VERSION'], development_head=HEAD,
                  suites=rows, evidence_errors=errors, successful=False)
    expected = json.loads((Path(__file__).parent / 'expected-suites.json').read_text())
    # Copy raw XML before any identity check can fail; diagnostic failures must retain it.
    sources = sorted((root / 'morphhdl/target/test-reports').glob('*.xml'))
    (out / 'reports').mkdir(exist_ok=True)
    found = {}
    for source in sources:
        try:
            require(source.is_file() and not source.is_symlink(), 'linked or nonregular report: ' + source.name)
            shutil.copyfile(source, out / 'reports' / source.name)
            document = ET.parse(source).getroot()
            require(document.tag == 'testsuite', 'unexpected XML root: ' + source.name)
            suite = document.get('name')
            require(isinstance(suite, str) and source.name == 'TEST-' + suite + '.xml',
                    'report filename differs from suite identity: ' + source.name)
            require(suite not in found, 'duplicate report suite: ' + str(suite))
            cases = document.findall('testcase')
            found[suite] = [case.get('name') for case in cases]
            rows.append(dict(suite=suite, tests=len(cases),
                             failures=len(document.findall('.//failure')),
                             errors=len(document.findall('.//error')),
                             skipped=len(document.findall('.//skipped'))))
            require(all(case.get('classname') == suite for case in cases),
                    'testcase class differs from suite identity: ' + str(suite))
            require(int(document.get('tests', '-1')) == len(cases),
                    'suite testcase total differs: ' + str(suite))
            require(all(document.get(key) == '0' for key in ('failures', 'errors', 'skipped')),
                    'missing or nonpassing XML outcome counters: ' + str(suite))
        except Exception as error:
            errors.append(str(error))
    try:
        require(set(found) == set(expected), 'missing or unexpected XML suites')
        for suite, cases in expected.items():
            require(Counter(found[suite]) == Counter(cases), 'testcase inventory differs: ' + suite)
        require(all(not row['failures'] and not row['errors'] and not row['skipped'] for row in rows),
                'XML reports include failed, errored or skipped cases')
    except Exception as error:
        errors.append(str(error))
    try:
        for name in ('prepare.log', 'qualified-parent-review.log', 'tests.log', 'head.txt',
                     'tree.txt', 'tracked-before.txt', 'development-identity.json',
                     'development.patch', 'development.bundle', 'path-receipt.json',
                     'preserved-local-enable-seal-review.log', 'target-integration-identity.json'):
            path = out / name
            require(path.is_file() and not path.is_symlink() and path.stat().st_size > 0,
                    'missing evidence: ' + name)
        require((out / 'head.txt').read_text().strip() == HEAD, 'missing or incorrect head receipt')
        require((out / 'tree.txt').read_text().strip() == TREE, 'incorrect tree receipt')
        expected_merge = dict(source=HEAD, tree=TREE,
            parents=[PAYLOAD['preserved_seal'], PAYLOAD['integrated_target']],
            preserved_seal_tree=PAYLOAD['preserved_seal_tree'], target_tree=PAYLOAD['integrated_target_tree'],
            common=PAYLOAD['integration_common'], current_source_qualified=False)
        require(json.loads((out / 'target-integration-identity.json').read_text()) == expected_merge,
                'target integration receipt differs')
        require('59I_PRODUCTION_SUCCESSOR_PASS files=322' in
                (out / 'preserved-local-enable-seal-review.log').read_text(), 'preserved seal audit did not pass')
        identity = json.loads((out / 'development-identity.json').read_text())
        require(identity['development_only'] is True and identity['qualification'] is False and
                identity['full_ci'] is False and identity['source_sealed'] is False and
                identity['development_head'] == HEAD and
                identity['source_tree'] == TREE and identity['qualified_parent'] == BASE and
                identity['patch_sha256'] == PATCH_SHA256 and identity['bundle_sha256'] == BUNDLE_SHA256,
                'development identity receipt differs')
        require(identity['continuations'] == PAYLOAD['continuations'] and
                identity['changed_paths'] == PAYLOAD['paths'] and identity['audit_inputs_included'] is True,
                'development continuation receipt differs')
        require(hashlib.sha256((out / 'development.patch').read_bytes()).hexdigest() == PATCH_SHA256,
                'retained patch differs')
        require(hashlib.sha256((out / 'development.bundle').read_bytes()).hexdigest() == BUNDLE_SHA256,
                'retained bundle differs')
        require(git(root, 'rev-parse', 'HEAD').decode().strip() == HEAD, 'tested head changed')
        require(git(root, 'rev-parse', 'HEAD^{tree}').decode().strip() == TREE, 'tested tree changed')
        (out / 'tracked-after.txt').write_bytes(git(root, 'ls-files', '--stage'))
        require((out / 'tracked-before.txt').read_bytes() == (out / 'tracked-after.txt').read_bytes(),
                'tracked index changed during probe')
        git(root, 'diff', '--exit-code')
        git(root, 'diff', '--cached', '--exit-code')
        log = (out / 'tests.log').read_text(errors='replace')
        require('All tests passed' in log and '*** FAILED ***' not in log,
                'console does not confirm successful tests')
    except Exception as error:
        errors.append(str(error))
    result.update(successful=not errors, expected_suites=len(expected),
                  expected_testcases=sum(map(len, expected.values())),
                  actual_suites=len(rows), actual_testcases=sum(row['tests'] for row in rows))
    (out / 'actual-test-results.json').write_text(json.dumps(result, indent=2) + '\n')
    (out / 'evidence-files.json').write_text(json.dumps([
        dict(path=str(path.relative_to(out)), bytes=path.stat().st_size,
             sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        for path in sorted(out.rglob('*')) if path.is_file() and not path.is_symlink()
        and path.name not in ('evidence-files.json', 'retention.log')], indent=2) + '\n')
    print(json.dumps(result, indent=2))
    return 0 if result['successful'] else 1


if __name__ == '__main__':
    raise SystemExit(retain())
