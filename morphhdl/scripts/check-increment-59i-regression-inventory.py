#!/usr/bin/env python3
"""Authenticate all current 59i reports before an exact, copied historical view.

Production/tests are never compiled from the projection. Current positive,
negative and formal cases remain required, with exact identities and statuses.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = 'morphhdl/contracts/increment-59i-regression-inventory.json'
HELPER = 'morphhdl/scripts/check-increment-59i-production-successor.py'
VERIFIER_SHA256 = '2cd3030c3a320ca392527ee38e4186f33db4bc7a6b6320b3bf10946b57fb0f20'
CONTRACT_SHA256 = '338e26e122d657349e254e19ff452fbd94f839b774268bbaecb93ea002e6027f'


def require(ok, message):
    if not ok:
        raise RuntimeError('59i regression inventory: ' + message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def git(root, *args):
    return subprocess.check_output(['git', '--literal-pathspecs', *args], cwd=root,
                                   stderr=subprocess.PIPE, timeout=120)


def regular(root, path):
    p = Path(path)
    require(not p.is_absolute() and '..' not in p.parts and p.as_posix() == path,
            'unsafe report/source path')
    require(not any((root / Path(*p.parts[:i])).is_symlink() for i in range(1, len(p.parts)+1)),
            'linked report/source path: ' + path)
    file = root / path
    require(file.is_file(), 'missing regular file: ' + path)
    return file.read_bytes()


def source_review(root):
    # Pin semantics BEFORE importing executable repository source, then perform
    # fresh whole-HEAD/index/worktree verification on EVERY invocation.
    import re
    raw = regular(root, HELPER)
    normalized = re.sub(rb'^CONTRACT_SHA256 = "[^"]*"$', b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)
    require(digest(normalized) == VERIFIER_SHA256, 'unreviewed source verifier')
    spec = importlib.util.spec_from_file_location('reviewed_59i_inventory_source', root / HELPER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.verify(root)
    return git(root, 'rev-parse', 'HEAD').decode().strip()


def specification(root):
    head = source_review(root)
    raw = regular(root, CONTRACT)
    require(digest(raw) == CONTRACT_SHA256, 'changed reviewed inventory contract')
    value = json.loads(raw)
    require(value['schema'] == 1, 'unknown inventory schema')
    git(root, 'merge-base', '--is-ancestor', value['source_basis'], head)
    git(root, 'merge-base', '--is-ancestor', value['historical_base'], head)
    for path, expected in value['test_source_sha256'].items():
        require(digest(regular(root, path)) == expected, 'test payload differs: ' + path)
    return head, value


def reports(root, projects):
    """Require exact names AND all zero status fields; no total-count shortcut."""
    result = {}
    for project, expected in projects.items():
        directory = root / project / 'target/test-reports'
        require(directory.is_dir() and not directory.is_symlink(), 'missing report directory: '+project)
        observed = {}
        for file in sorted(directory.glob('*.xml')):
            raw = regular(root, file.relative_to(root).as_posix())
            require(b'<!DOCTYPE' not in raw and b'<!ENTITY' not in raw, 'XML declarations are not allowed')
            suite = ET.fromstring(raw)
            name = suite.get('name')
            require(suite.tag == 'testsuite' and name in expected and name not in observed,
                    'foreign/duplicate suite: '+str(name))
            require(file.name == 'TEST-'+name+'.xml', 'report filename differs: '+str(name))
            require(all(suite.get(key) == '0' for key in ('failures','errors','skipped')),
                    'failed/error/skipped suite: '+str(name))
            require(not any(list(suite.iter(tag)) for tag in ('failure','error','skipped')),
                    'unsuccessful testcase: '+str(name))
            cases = suite.findall('testcase')
            names = [case.get('name') for case in cases]
            require(suite.get('tests') == str(len(expected[name])) and len(cases) == len(expected[name]),
                    'incomplete testcase count: '+str(name))
            require(all(names) and len(set(names)) == len(names) and sorted(names) == expected[name],
                    'missing/duplicate/changed testcase identity: '+str(name))
            require(all(case.get('classname') == name for case in cases), 'wrong testcase owner: '+str(name))
            observed[name] = (file, suite)
        require(set(observed) == set(expected), 'missing suite: '+project)
        result[project] = observed
    return result


def summary(observed):
    return {project:dict(tests=sum(len(suite.findall('testcase')) for _,suite in suites.values()),
                         suites=sorted(suites), skipped=0) for project,suites in observed.items()}


def prepare(root, predecessor, output):
    head, value = specification(root)
    observed = reports(root, value['projects'])
    require(git(predecessor,'rev-parse','HEAD').decode().strip() == value['historical_base'],
            'wrong historical report worktree')
    require(digest(regular(predecessor, 'morphhdl/scripts/check-increment-60f-artifacts.py')) ==
            value['historical_checker_sha256'], 'historical checker differs')
    require(root.resolve() != predecessor.resolve(), 'cannot project production checkout')
    # Workflow has independently authenticated and removed precisely the two
    # Increment61 suites from its COPIED reports. Require that exact copy state.
    copied_expected = copy.deepcopy(value['projects'])
    for name,count in value['increment61'].items():
        require(len(copied_expected['morphhdl'].pop(name)) == count, 'changed Increment61 obligations')
    copied = reports(predecessor, copied_expected)
    # Before projecting any report, preserve PR190's original/copy identity
    # requirement for every suite, including all 37 sequential testcases.
    for project, suites in copied.items():
        for name, (path, _) in suites.items():
            require(path.read_bytes() == observed[project][name][0].read_bytes(),
                    'copied report differs from original: ' + project + '/' + name)
    for project, suites in copied.items():
        old = value['historical_counts'][project]
        require(set(old) <= set(suites), 'lost historical suite: '+project)
        for name, (path,suite) in suites.items():
            if name not in old:
                path.unlink()
                continue
            expected_count = old[name]
            if name in value['retained_cases']:
                retained = value['retained_cases'][name]
                require(len(retained) == expected_count and set(retained) <= set(copied_expected[project][name]),
                        'invalid exact historical testcase projection')
                for case in list(suite.findall('testcase')):
                    if case.get('name') not in retained:
                        suite.remove(case)
                suite.set('tests',str(expected_count))
                ET.ElementTree(suite).write(path, encoding='utf-8', xml_declaration=True)
            else:
                require(len(suite.findall('testcase')) == expected_count, 'unreviewed inherited count change: '+name)
    require(source_review(root) == head, 'current source moved during projection')
    output.write_text(json.dumps(dict(head=head,complete=summary(observed)),indent=2,sort_keys=True)+'\n')
    print('59i current exact regression inventory:', sum(r['tests'] for r in summary(observed).values()),
          'cases; immutable historical checker remains mandatory')


def finish(root, output_dir):
    head,value = specification(root)
    current = summary(reports(root,value['projects']))
    receipt = json.loads((output_dir/'increment-59i-test-inventory.json').read_text())
    require(receipt == dict(head=head,complete=current), 'changed current inventory receipt')
    inherited = json.loads((output_dir/'inherited-test-inventory.json').read_text())
    expected = {p:dict(tests=sum(s.values()),suites=sorted(s),skipped=0)
                for p,s in value['historical_counts'].items()}
    require(inherited == expected, 'incomplete or altered historical inventory result')
    # Also require the original combined 60f+61 report, not a shortcut around it.
    combined = copy.deepcopy(expected)
    combined['morphhdl']['tests'] += sum(value['increment61'].values())
    combined['morphhdl']['suites'] = sorted(combined['morphhdl']['suites']+list(value['increment61']))
    require(json.loads((output_dir/'test-inventory.json').read_text()) == combined,
            'original 60f+61 aggregation did not finish')
    (output_dir/'historical-plus-61-inventory.json').write_text(json.dumps(combined,indent=2)+'\n')
    (output_dir/'test-inventory.json').write_text(json.dumps(current,indent=2,sort_keys=True)+'\n')
    print('60f +61 +59i complete regressions:',sum(r['tests'] for r in current.values()),
          'exact non-skipped cases; all historical obligations retained')


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode',choices=('prepare','finish'))
    p.add_argument('--root',type=Path,default=ROOT)
    p.add_argument('--predecessor',type=Path)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    if a.mode=='prepare':
        require(a.predecessor is not None, 'historical worktree is required')
        prepare(a.root.resolve(),a.predecessor.resolve(),a.output)
    else:
        finish(a.root.resolve(),a.output)
