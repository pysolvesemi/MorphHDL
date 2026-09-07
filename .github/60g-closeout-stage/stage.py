"""Stage an exact documentation-only closeout. Never merge or qualify a PR."""
import gzip
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

BRANCH = 'agent/60g-closeout-stage-20260907'
BASE = 'f496ae8251dc4ce26e3e3a33894e7f1e652c8f92'
TREE = '31a008ea54b83abda129998ad64a4420b6eac877'
ROOT = Path('.github/60g-closeout-stage')
WORKFLOW = '.github/workflows/60g-closeout-stage.yml'
FILES = {
    'docs/morphhdl/increment-60-sint-signed-verilog-roadmap.md': '8691501de4d7c9015bd790a7d5945ac0c2401eeb',
    'docs/morphhdl/increment-60g-default-rollout.md': '2f0df1f90ee2c6f2f8c594959c0e4ed5c5fe111b',
    'docs/morphhdl/parameterized-verilog-todo.md': '7108b35b050de5df24a00b9f2e42fc1a12122d3c',
}

def git(*args, **kwargs):
    return subprocess.check_output(['git', *args], text=True, **kwargs).strip()

assert os.environ['GITHUB_REF'] == 'refs/heads/' + BRANCH
assert git('rev-parse', 'HEAD') == os.environ['GITHUB_SHA']
assert git('rev-parse', 'HEAD^') == BASE
assert git('ls-remote', 'origin', 'refs/heads/' + BRANCH).split()[0] == os.environ['GITHUB_SHA']
parts = sorted(ROOT.glob('part-*.gz.part'))
assert [p.name for p in parts] == [f'part-{i}.gz.part' for i in range(4)]
compressed = b''.join(p.read_bytes() for p in parts)
assert hashlib.sha256(compressed).hexdigest() == '3a3ebc4adcb2bef62673e8ee9c04ee698ba59bf513b7e9cb281f60f7150032b9'
patch = gzip.decompress(compressed)
assert hashlib.sha256(patch).hexdigest() == 'fc1778d9ce9e8845fd3f8458777c1233cd7113ab849a0ef441ae100b49d4c4f7'
with tempfile.TemporaryDirectory() as td:
    patchfile = Path(td) / 'closeout.patch'
    patchfile.write_bytes(patch)
    subprocess.run(['git', 'apply', '--check', '--index', str(patchfile)], check=True)
    subprocess.run(['git', 'apply', '--index', str(patchfile)], check=True)
    assert set(git('diff', '--cached', '--name-only').splitlines()) == set(FILES)
    for path, expected in FILES.items():
        assert git('hash-object', path) == expected, path
    subprocess.run(['git', 'diff', '--cached', '--check'], check=True)
    index = Path(td) / 'verify.index'
    shutil.copyfile(git('rev-parse', '--git-path', 'index'), index)
    env = dict(os.environ, GIT_INDEX_FILE=str(index))
    helpers = git('ls-files', '--', str(ROOT), WORKFLOW).splitlines()
    assert len(helpers) == 6 and WORKFLOW in helpers
    subprocess.run(['git', 'update-index', '--force-remove', '--', *helpers], env=env, check=True)
    canonical = git('write-tree', env=env)
    assert canonical == TREE, ('complete closeout tree differs', canonical)
assert git('ls-remote', 'origin', 'refs/heads/' + BRANCH).split()[0] == os.environ['GITHUB_SHA']
git('config', 'user.name', 'github-actions[bot]')
git('config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
subprocess.run(['git', 'commit', '-m', 'docs(morphhdl): stage verified three-file 60g closeout'], check=True)
subprocess.run(['git', 'push', 'origin', 'HEAD:refs/heads/' + BRANCH], check=True)
print('STAGED_COMMIT=' + git('rev-parse', 'HEAD'), flush=True)
print('STAGED_TREE=' + git('rev-parse', 'HEAD^{tree}'), flush=True)
print('CANONICAL_CLOSEOUT_TREE=' + canonical, flush=True)
print('No production change, qualification bypass or PR merge.', flush=True)
