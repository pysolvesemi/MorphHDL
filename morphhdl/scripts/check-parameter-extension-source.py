#!/usr/bin/env python3
"""Authenticate the CDC-LEG-01/64/65/66 successor without changing old proofs.

The cumulative seal checks exact committed/index/worktree bytes, including the
reviewed relocation deletions. This contract bounds the admitted source delta;
all historical safety markers and formal-registry identities remain current
obligations. Historical source reviews are replayed separately on their baseline.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASE = 'db54d01e5b21c7664f7a0de3795f061d77a3d259'
CONTRACT = 'morphhdl/contracts/parameter-extension-source-review.json'
CONTRACT_SHA256 = 'aa1539fcd4b3edc7c94ff4277fd1ce1cf0cbfe2fe5a3e815115e63b806786677'
OUTER = 'morphhdl/scripts/check-increment-62-wa08-source-overlay.py'
REGISTRY = 'morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json'


def require(ok, detail):
    if not ok:
        raise RuntimeError('CDC-WIRE-SOURCE: parameter extensions: ' + detail)


def git(root, *args):
    return subprocess.check_output(['git', '--literal-pathspecs', *args], cwd=root, timeout=120)


def load(root, path):
    file = root / path
    require(file.is_file() and not file.is_symlink(), 'missing/linked reviewer: ' + path)
    spec = importlib.util.spec_from_file_location('parameter_extension_' + file.stem, file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def contract(root):
    raw = (root / CONTRACT).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == CONTRACT_SHA256, 'review contract digest differs')
    value = json.loads(raw)
    require(value['base'] == BASE and value['schema_version'] == 1, 'review identity differs')
    paths = [entry['path'] for entry in value['files']]
    require(paths == sorted(set(paths)) and all(entry['reason'] for entry in value['files']),
            'empty, duplicate or unordered review records')
    return value


def validate_scope(paths, root=ROOT):
    approved = {entry['path'] for entry in contract(root)['files']}
    outer = load(root, OUTER)
    require({p for p in paths if outer.governed(p)} <= approved,
            'unreviewed implementation paths: ' + repr(sorted(p for p in set(paths) - approved if outer.governed(p))))
    require(all(not Path(p).is_absolute() and '..' not in Path(p).parts and
                (p in approved or p.startswith('docs/morphhdl/')) for p in paths),
            'unreviewed path outside source/documentation scope')


def verify(root=ROOT, sealed=None):
    root = root.resolve()
    outer = load(root, OUTER)
    seal = outer.verify(root) if sealed is None else sealed
    value = contract(root)
    git(root, 'merge-base', '--is-ancestor', BASE, 'HEAD')
    require(git(root, 'rev-parse', BASE + '^{tree}').decode().strip() == value['base_tree'],
            'immutable predecessor tree differs')
    changed = set(git(root, 'diff', '--no-renames', '--name-only', BASE, 'HEAD').decode().splitlines())
    validate_scope(changed, root)
    governed = {p for p in changed if outer.governed(p)}
    require(governed == {entry['path'] for entry in value['files']}, 'reviewed inventory differs')
    records = {entry['path']: entry for entry in seal['files']}
    require(governed - {OUTER, 'morphhdl/contracts/increment-62-wa08-source-overlay.json'} <= records.keys(),
            'reviewed source missing from complete cumulative seal')
    # Load the frozen safety predicates, never a relaxed successor's spelling.
    for helper, marker_name, checker_name in (
        ('morphhdl/scripts/check-lane-when-source-scope.py', 'MARKERS', 'safety_failures'),
        ('morphhdl/scripts/check-sequential-wire-source-review.py', 'SAFETY_MARKERS', 'safety_failures'),
        ('morphhdl/scripts/check-cdc-wire-source-review.py', 'CDC_SAFETY_MARKERS', 'safety_failures'),
        ('morphhdl/scripts/check-cdc-wire-source-review.py', 'BUILD_REVIEW_MARKERS', 'review_failures'),
    ):
        scope = {'__file__': str(root / helper), '__name__': 'frozen_parameter_predecessor'}
        exec(compile(git(root, 'show', BASE + ':' + helper), helper, 'exec'), scope)
        for path in scope[marker_name]:
            require(not scope[checker_name](path, (root / path).read_text()),
                    'inherited safety obligation changed: ' + path)
    before = json.loads(git(root, 'show', BASE + ':' + REGISTRY))
    current = json.loads((root / REGISTRY).read_bytes())
    require(current.keys() == before.keys() and current['files'].keys() == before['files'].keys() and
            all(current[k] == before[k] for k in current if k != 'files'),
            'inherited formal registry schema/identities changed')
    for path, digest in current['files'].items():
        require(hashlib.sha256((root / path).read_bytes()).hexdigest() == digest,
                'current formal fingerprint differs: ' + path)
    subprocess.run(['python3', 'morphhdl/scripts/check-native-source-preservation.py'],
                   cwd=root, check=True, timeout=120, stdout=subprocess.DEVNULL)
    production = {p for p in governed if '/src/main/' in p and (root / p).is_file()}
    implementation = {p for p in governed if '/src/main/' in p or '/src/test/' in p}
    return {'head': git(root, 'rev-parse', 'HEAD').decode().strip(), 'base': BASE,
            'source': seal['final_source_commit'], 'lane': BASE, 'paths': sorted(changed),
            'production_paths': sorted(production), 'implementation_paths': sorted(implementation),
            'review_paths': sorted(governed - implementation)}


if __name__ == '__main__':
    argparse.ArgumentParser(description=__doc__).parse_args()
    print('PARAMETER_EXTENSION_SOURCE_PASS ' + json.dumps(verify(), sort_keys=True))
