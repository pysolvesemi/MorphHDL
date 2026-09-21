import fnmatch
import hashlib
import json
from pathlib import Path
import subprocess
import yaml

repo = '/workspace/scratch/022bd487b540/MorphHDL'
root = Path(__file__).resolve().parent
m = json.loads((root / 'manifest.json').read_text())
git = lambda *a: subprocess.check_output(['git', *a], cwd=repo)
paths = git('ls-tree', '-r', '--name-only', m['target'], '.github/workflows').decode().splitlines()
records = []
matches = []
for path in paths:
    raw = git('show', m['target'] + ':' + path)
    doc = yaml.safe_load(raw)
    on = doc.get('on', doc.get(True, {}))
    if isinstance(on, str):
        on = {on: None}
    if isinstance(on, list):
        on = {key: None for key in on}
    push = on.get('push', False)
    match = False
    if push is not False:
        push = push or {}
        branches = push.get('branches')
        ignored = push.get('branches-ignore', [])
        match = (branches is None or any(fnmatch.fnmatchcase(m['controller_branch'], p) for p in branches))
        match = match and not any(fnmatch.fnmatchcase(m['controller_branch'], p) for p in ignored)
        if 'tags' in push and branches is None:
            match = False
    records.append({'path': path, 'sha256': hashlib.sha256(raw).hexdigest(), 'push': push, 'branch_matches': match})
    if match:
        matches.append(path)
assert not matches, matches
for row in m['workflows']:
    assert hashlib.sha256(git('show', m['head'] + ':' + row['path'])).hexdigest() == row['sha256']
new = yaml.safe_load((root / 'workflow.yml').read_text())
trigger = new.get('on', new.get(True))['push']
assert trigger['branches'] == [m['controller_branch']]
assert trigger['paths'] == [m['controller_path'], m['controller_root'] + '/**']
assert new['permissions'] == {'contents': 'read', 'actions': 'write'}
assert git('rev-parse', m['head'] + '^{tree}').decode().strip() == m['tree']
print(json.dumps({'target': m['target'], 'controller_branch': m['controller_branch'], 'existing_push_matches': matches, 'only_new_controller_matches': True, 'workflows': records}, indent=2))
