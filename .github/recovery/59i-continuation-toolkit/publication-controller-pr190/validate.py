#!/usr/bin/env python3
"""Check the exact-object transport before authorizing any remote operation."""
import ast
from datetime import datetime
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import yaml

here = Path(__file__).resolve().parent
for path in sorted(here.glob('*.py')):
    ast.parse(path.read_text(), filename=str(path))
spec = importlib.util.spec_from_file_location('stage', here / 'stage.py')
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)
workflow = yaml.safe_load((here / 'stage.yml').read_text())
assert workflow['permissions'] == {'contents': 'write', 'actions': 'write'}
for job in workflow['jobs'].values():
    for step in job['steps']:
        if 'run' in step:
            subprocess.run(['bash', '-n'], input=step['run'].encode(), check=True)
repo = here.parent / '59i-dev'
assert stage.git(repo, 'rev-parse', stage.TARGET + '^{tree}').decode().strip() == stage.TARGET_TREE
assert len(stage.CHECKS) == 25
shas = stage.git(repo, 'rev-list', '--reverse', '--topo-order', 'HEAD', '^' + stage.BASE, '^' + stage.TARGET).decode().splitlines()
for sha in shas:
    raw = stage.git(repo, 'cat-file', 'commit', sha)
    body = stage.commit_body(raw)
    rows = ['tree ' + body['tree']] + ['parent ' + parent for parent in body['parents']]
    for name in ['author', 'committer']:
        person = body[name]
        date = datetime.fromisoformat(person['date'])
        rows.append('%s %s <%s> %d %s' % (name, person['name'], person['email'], date.timestamp(), date.strftime('%z')))
    rebuilt = ('\n'.join(rows) + '\n\n' + body['message']).encode()
    assert rebuilt == raw, sha
    assert stage.git(repo, 'hash-object', '-t', 'commit', '--stdin', input=rebuilt).decode().strip() == sha
# A timezone conversion would silently change source identity; actual +0200
# history above exercises it. Unknown headers must instead fail closed.
try:
    stage.commit_body(raw.replace(b'\ncommitter ', b'\ngpgsig rejected\ncommitter ', 1))
except RuntimeError:
    pass
else:
    raise AssertionError('unsupported raw metadata admitted')
print('PASS: Python AST, workflow YAML, shell syntax, %d exact raw commit/API-body round trips, unknown-metadata rejection' % len(shas))
