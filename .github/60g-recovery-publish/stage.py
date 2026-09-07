"""Reconstruct one pre-reviewed source tree; never qualify or merge a PR."""
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

BRANCH = 'agent/60g-resume-publish-20260907'
ROOT = Path('.github/60g-recovery-publish')
WORKFLOW = '.github/workflows/60g-recovery-publish.yml'
meta = json.loads((ROOT / 'metadata.json').read_text())

def git(*args, **kwargs):
    return subprocess.check_output(['git', *args], text=True, **kwargs).strip()

assert os.environ['GITHUB_REF'] == 'refs/heads/' + BRANCH
assert git('rev-parse', 'HEAD') == os.environ['GITHUB_SHA']
assert git('ls-remote', 'origin', 'refs/heads/' + BRANCH).split()[0] == os.environ['GITHUB_SHA']
subprocess.run(['git', 'merge-base', '--is-ancestor', meta['integration'], 'HEAD'], check=True)
assert git('rev-parse', 'HEAD^') == meta['integration'], 'unexpected staging base'
parts = sorted(ROOT.glob('part-*.gz.part'))
assert [p.name for p in parts] == [f'part-{i}.gz.part' for i in range(4)]
patch = gzip.decompress(b''.join(p.read_bytes() for p in parts))
assert hashlib.sha256(patch).hexdigest() == meta['patch_sha256'], 'patch integrity failure'
assert hashlib.sha256((ROOT / 'generate-ledger.py').read_bytes()).hexdigest() == '4b634e3d981626648d1cfdc15e86fd99a6a869b199469d0a8632508549fab08e'
with tempfile.TemporaryDirectory() as td:
    patchfile = Path(td) / 'changes.patch'
    patchfile.write_bytes(patch)
    subprocess.run(['git', 'checkout', meta['prior_pr'], '--', *meta['files_from_pr']], check=True)
    subprocess.run(['git', 'apply', '--check', '--index', str(patchfile)], check=True)
    subprocess.run(['git', 'apply', '--index', str(patchfile)], check=True)
    subprocess.run(['python3', str(ROOT / 'generate-ledger.py')], check=True)
    subprocess.run(['git', 'add', '-f', 'morphhdl/contracts/increment-60g-publication-edits.json'], check=True)
    subprocess.run(['git', 'diff', '--cached', '--check'], check=True)
    index = Path(td) / 'verify.index'
    shutil.copyfile(git('rev-parse', '--git-path', 'index'), index)
    env = dict(os.environ, GIT_INDEX_FILE=str(index))
    helpers = git('ls-files', '--', str(ROOT), WORKFLOW).splitlines()
    assert helpers and WORKFLOW in helpers
    subprocess.run(['git', 'update-index', '--force-remove', '--', *helpers], env=env, check=True)
    canonical = git('write-tree', env=env)
    assert canonical == meta['staging_source_tree'], ('complete source tree mismatch', canonical)
assert git('ls-remote', 'origin', 'refs/heads/' + BRANCH).split()[0] == os.environ['GITHUB_SHA']
git('config', 'user.name', 'github-actions[bot]')
git('config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
subprocess.run(['git', 'commit', '-m', 'Stage exact verified 60g recovery source for API publication [skip ci]'], check=True)
subprocess.run(['git', 'push', 'origin', 'HEAD:refs/heads/' + BRANCH], check=True)
print('STAGED_COMMIT=' + git('rev-parse', 'HEAD'), flush=True)
print('STAGED_TREE=' + git('rev-parse', 'HEAD^{tree}'), flush=True)
print('CANONICAL_SOURCE_TREE=' + canonical, flush=True)
print('No compiler, HDL proof, increment completion or merge is claimed.', flush=True)
