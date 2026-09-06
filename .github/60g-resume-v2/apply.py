#!/usr/bin/env python3
"""Materialize the reviewed 60g integration tree; never merges the target branch."""
import difflib
import hashlib
import json
from pathlib import Path
import subprocess
import sys

payload = Path(sys.argv[1]).resolve()
plan_bytes = (payload / 'plan.json').read_bytes()
assert hashlib.sha256(plan_bytes).hexdigest() == 'd4e7e703b54254f7c17aee1b9a521e1642557e9d4f8c26bc85fab71b1ec50dbf'
plan = json.loads(plan_bytes)
assert plan['base'] == '99b6017d7ac69112a088680457029623620224d3'
assert plan['feature_head'] == 'cefb24d51daaef9a080870b714059b84b6a5a988'
assert plan['tree'] == '53a3e4860b67349867ecd923be3db97e5e3b445a'
parts = sorted(payload.glob('part-*.patch'))
assert [p.name for p in parts] == [f'part-{i}.patch' for i in range(5)]
patch = b''.join(p.read_bytes() for p in parts)
assert hashlib.sha256(patch).hexdigest() == 'e5f7e46da5fa18004fad4ca287fc42e39bc2dbca747cfc88a90979ceab6f9d12'
patch_path = payload / 'reviewed.patch'
patch_path.write_bytes(patch)

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def show(ref, path):
    return subprocess.check_output(['git', 'show', ref + ':' + path], text=True)

def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()

# Only the known prior head or its exact published prototype may be extended.
# Preserve the actual existing branch commit, never reset unreviewed work.
parent = git('rev-parse', 'refs/remotes/origin/agent/increment-60g-default-signed-verilog')
assert parent == plan['feature_head'] or git('rev-parse', parent + '^{tree}') == '7c1eb5f558a36c124d2e0ed8adb65a39d06f228f'
subprocess.run(['git', 'merge-base', '--is-ancestor', plan['feature_head'], parent], check=True)
(payload / 'expected-parent.txt').write_text(parent + '\n')
subprocess.run(['git', 'checkout', '--detach', plan['base']], check=True)
assert len(plan['files']) == 34 and len({e['path'] for e in plan['files']}) == 34
for entry in plan['files']:
    path = entry['path']
    assert not Path(path).is_absolute() and '..' not in Path(path).parts
    assert entry['mode'] in ('100644', '100755')
    assert git('cat-file', '-t', entry['before_sha']) == 'blob'
    git('update-index', '--add', '--cacheinfo', entry['mode'] + ',' + entry['before_sha'] + ',' + path)
    git('checkout-index', '--force', '--', path)
git('update-index', '--refresh')
subprocess.run(['git', 'apply', '--index', '--check', str(patch_path)], check=True)
subprocess.run(['git', 'apply', '--index', str(patch_path)], check=True)

# Derive the reversible ledger from these exact reviewed before/after files.
# Its complete resulting blob and whole repository tree are pinned below.
contract = Path('morphhdl/contracts/increment-60g-publication-edits.json')
data = json.loads(contract.read_text())
paths = [e['path'] for e in data['files']]
for path in ('morphhdl/src/main/scala/morphhdl/MorphVerilog.scala',
             'morphhdl/src/main/scala/morphhdl/MorphSignedCasts.scala',
             'morphhdl/src/main/scala/morphhdl/MorphSignedDeclarations.scala',
             'core/src/main/scala/spinal/core/internals/Phase.scala'):
    if path not in paths:
        paths.append(path)
assert len(paths) == 12
entries = []
for path in paths:
    before, after = show(plan['base'], path), Path(path).read_text()
    a, b = before.splitlines(keepends=True), after.splitlines(keepends=True)
    edits = []
    for group in difflib.SequenceMatcher(a=a, b=b, autojunk=False).get_grouped_opcodes(3):
        x = ''.join(a[group[0][1]:group[-1][2]])
        y = ''.join(b[group[0][3]:group[-1][4]])
        assert x and y and before.count(x) == 1 and after.count(y) == 1
        edits.append(dict(before=x, after=y))
    restored = after
    for edit in reversed(edits):
        restored = restored.replace(edit['after'], edit['before'], 1)
    assert restored == before
    entries.append(dict(path=path, before_sha256=digest(before), after_sha256=digest(after), edits=edits))
data['base'] = plan['base']
data['files'] = entries
contract.write_text(json.dumps(data, indent=2) + '\n')
git('add', '--', str(contract))
for entry in plan['files']:
    assert git('hash-object', entry['path']) == entry['after_sha'], entry['path']
    assert git('ls-files', '--stage', '--', entry['path']).split() == [entry['mode'], entry['after_sha'], '0', entry['path']]
subprocess.run(['git', 'diff', '--cached', '--check'], check=True)
assert git('write-tree') == plan['tree']
commit = subprocess.check_output(['git', 'commit-tree', plan['tree'], '-p', parent,
    '-p', plan['base']], input='fix(morphhdl): integrate 60g with 59d and seal registration at native execution start\n', text=True).strip()
git('reset', '--hard', commit)
assert git('rev-parse', 'HEAD^{tree}') == plan['tree']
assert not git('status', '--porcelain')
print('Exact reviewed 60g integration commit:', commit, 'tree:', plan['tree'])
