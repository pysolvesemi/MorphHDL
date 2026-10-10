#!/usr/bin/env python3
"""Retain and validate real local-enable test reports and deterministic RTL."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

SUITES = tuple('spinal.core.internals.' + name for name in (
    'TypedBalancedReductionCompositeLocalEnableTests',
    'TypedBalancedReductionBridgeReplayTests',
    'TypedBalancedReductionCompositeTests',
    'TypedBalancedReductionCompositeCallbackPolicyTests',
    'TypedBalancedReductionCompositeWideningTests',
    'TypedBalancedReductionCompositeCaptureTests',
    'TypedBalancedReductionCombinedTests',
    'TypedBalancedReductionNestedOwnerTests',
    'TypedBalancedReductionPublicationTests',
    'TypedBalancedReductionWideningPublicationTests'))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def reports(source: Path, output: Path) -> dict:
    inventory = json.loads((source / 'morphhdl/contracts/increment-59i-regression-inventory.json').read_text())
    expected = {suite: inventory['projects']['morphhdl'][suite] for suite in SUITES}
    destination = output / 'test-reports'
    destination.mkdir(parents=True, exist_ok=True)
    found = {}
    errors = []
    for path in sorted((source / 'morphhdl/target/test-reports').glob('*.xml')):
        try:
            require(path.is_file() and not path.is_symlink(), 'linked or nonregular XML: ' + path.name)
            shutil.copyfile(path, destination / path.name)
            root = ET.parse(path).getroot()
            require(root.tag == 'testsuite', 'unexpected XML root: ' + path.name)
            name = root.get('name')
            require(isinstance(name, str) and path.name == 'TEST-' + name + '.xml',
                    'report filename differs from suite identity: ' + path.name)
            require(name not in found, 'duplicate suite: ' + str(name))
            cases = root.findall('testcase')
            found[name] = [case.get('name') for case in cases]
            require(all(case.get('classname') == name for case in cases),
                    'testcase class differs from suite identity: ' + str(name))
            require(int(root.get('tests', '-1')) == len(cases), 'suite testcase total differs: ' + str(name))
            for tag in ('failure', 'error', 'skipped'):
                require(not root.findall('.//' + tag), 'nonpassing case in ' + str(name))
            for key in ('failures', 'errors', 'skipped'):
                require(root.get(key) == '0', 'missing or nonpassing ' + key + ' in suite ' + str(name))
        except Exception as error:
            errors.append(str(error))
    if set(found) != set(expected):
        errors.append('suite inventory differs; missing=' + repr(sorted(set(expected) - set(found))) +
                      '; extra=' + repr(sorted(str(x) for x in set(found) - set(expected))))
    for name in set(expected) & set(found):
        if Counter(found[name]) != Counter(expected[name]):
            errors.append('testcase identities differ: ' + name)
    result = dict(scope='committed-local-enable-tests', successful=not errors,
                  suites=len(found), tests=sum(map(len, found.values())), errors=errors,
                  identities=found)
    (output / 'test-results.json').write_text(json.dumps(result, indent=2) + '\n')
    require(not errors, '; '.join(errors))
    (output / 'test-summary.txt').write_text(
        'tests=' + str(result['tests']) + '\nfailures=0\nerrors=0\nskipped=0\n')
    return result


def rtl_inventory(root: Path) -> dict[str, str]:
    require(root.is_dir() and not root.is_symlink(), 'missing RTL artifact directory')
    result = {}
    for path in sorted(root.rglob('*')):
        require(not path.is_symlink(), 'linked artifact: ' + str(path))
        if path.is_file() and path.suffix in ('.v', '.vhd', '.vhdl'):
            result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    require(bool(result), 'empty RTL inventory: ' + str(root))
    return result


def deterministic(output: Path) -> dict:
    a = rtl_inventory(output / 'hardware-A')
    b = rtl_inventory(output / 'hardware-B')
    require(a == b, 'repeated local-enable RTL differs')
    result = dict(scope='local-enable-deterministic-rtl', files=a)
    (output / 'rtl-inventory.json').write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    return result


def unchanged(output: Path) -> dict:
    result = json.loads((output / 'rtl-inventory.json').read_text())
    require(result.get('scope') == 'local-enable-deterministic-rtl' and bool(result.get('files')),
            'missing deterministic RTL inventory')
    for directory in ('hardware-A', 'hardware-B'):
        for name, expected in result['files'].items():
            relative = Path(name)
            require(not relative.is_absolute() and '..' not in relative.parts, 'unsafe inventory path')
            path = output / directory / relative
            require(path.is_file() and not path.is_symlink() and
                    hashlib.sha256(path.read_bytes()).hexdigest() == expected,
                    'original generated RTL changed during verification: ' + directory + '/' + name)
    return dict(scope='original-local-enable-rtl-unchanged', files=len(result['files']))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('reports', 'determinism', 'unchanged'))
    parser.add_argument('--source', type=Path, default=Path('.'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    result = reports(args.source.resolve(), args.output.resolve()) if args.phase == 'reports' else (
        deterministic(args.output.resolve()) if args.phase == 'determinism' else unchanged(args.output.resolve()))
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
