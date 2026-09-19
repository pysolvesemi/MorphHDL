#!/usr/bin/env python3
"""Authenticate the frozen predecessor and reproduce an exact UNSEALED source.

Audits, contracts and workflows are part of this development snapshot. They are
hashed source inputs, not a current source seal or a qualification result.
"""
import base64
import hashlib
import json
import lzma
import os
from pathlib import Path
import re
import subprocess

# The builder replaces only this assignment with committed identity metadata.
PAYLOAD = None
BASE = PAYLOAD['base']
BASE_TREE = PAYLOAD['base_tree']
PARENT = PAYLOAD['parent']
TREE = PAYLOAD['tree']
HEAD = PAYLOAD['head']
COMPRESSED_SHA256 = PAYLOAD['compressed_sha256']
BUNDLE_SHA256 = PAYLOAD['bundle_sha256']
PATCH_SHA256 = PAYLOAD['patch_sha256']
PATHS = PAYLOAD['paths']
CONTRACT = 'morphhdl/contracts/increment-59i-production-successor.json'
HELPER = 'morphhdl/scripts/check-increment-59i-production-successor.py'
TODO = 'docs/morphhdl/parameterized-verilog-todo.md'


def require(ok, why):
    if not ok:
        raise RuntimeError('59i development probe: ' + why)


def git(root, *args):
    result = subprocess.run(['git', '--literal-pathspecs', *args], cwd=root,
                            capture_output=True, timeout=180)
    require(result.returncode == 0, result.stderr.decode(errors='replace'))
    return result.stdout


def paths():
    repository = Path(os.environ['GITHUB_WORKSPACE']).resolve()
    source_rel, out_rel = Path(os.environ['PROBE_SOURCE']), Path(os.environ['PROBE_OUT'])
    require(not source_rel.is_absolute() and '..' not in source_rel.parts, 'invalid source path')
    require(not out_rel.is_absolute() and '..' not in out_rel.parts, 'invalid output path')
    return repository, repository / source_rel, repository / out_rel


def verify_continuations(root):
    require(git(root, 'rev-parse', PAYLOAD['checkpoint'] + '^{tree}').decode().strip() ==
            PAYLOAD['checkpoint_tree'], 'preserved checkpoint tree differs')
    require(git(root, 'show', '-s', '--format=%P', PAYLOAD['checkpoint']).decode().split() ==
            PAYLOAD['checkpoint_parents'], 'preserved checkpoint parents differ')
    actual = git(root, 'rev-list', '--reverse', '--topo-order', HEAD, '^' + BASE).decode().splitlines()
    require(actual == [row['commit'] for row in PAYLOAD['continuations']],
            'complete development ancestry differs')
    for row in PAYLOAD['continuations']:
        commit = row['commit']
        require(git(root, 'rev-parse', commit + '^{tree}').decode().strip() == row['tree'],
                'continuation tree differs: ' + commit)
        require(git(root, 'show', '-s', '--format=%P', commit).decode().split() == row['parents'],
                'continuation parents differ: ' + commit)
    current = HEAD
    while current != PAYLOAD['checkpoint']:
        parents = git(root, 'show', '-s', '--format=%P', current).decode().split()
        require(len(parents) == 1, 'post-checkpoint source must be linear: ' + current)
        require(git(root, 'show', current + ':' + CONTRACT) == git(root, 'show', BASE + ':' + CONTRACT),
                'intermediate development source changed predecessor manifest: ' + current)
        require(not git(root, 'diff', BASE, current, '--', TODO),
                'unqualified development source changed completion TODO: ' + current)
        current = parents[0]
    require(git(root, 'show', current + ':' + CONTRACT) == git(root, 'show', BASE + ':' + CONTRACT),
            'checkpoint predecessor manifest differs')


def main():
    repository, root, output = paths()
    output.mkdir(parents=True, exist_ok=True)
    require(not root.exists(), 'destination already exists')
    here = Path(__file__).resolve().parent
    parts = [here / name for name in PAYLOAD['parts']]
    require(all(p.is_file() and not p.is_symlink() for p in parts), 'invalid payload')
    compressed = base64.b64decode(b''.join(p.read_bytes() for p in parts), validate=True)
    require(hashlib.sha256(compressed).hexdigest() == COMPRESSED_SHA256, 'compressed hash differs')
    bundle = lzma.decompress(compressed)
    require(hashlib.sha256(bundle).hexdigest() == BUNDLE_SHA256, 'bundle hash differs')
    bundle_path = output / 'development.bundle'
    bundle_path.write_bytes(bundle)
    git(repository, 'worktree', 'add', '--detach', str(root), BASE)
    require(git(root, 'rev-parse', 'HEAD^{tree}').decode().strip() == BASE_TREE,
            'qualified base tree differs')
    with (output / 'qualified-parent-review.log').open('wb') as log:
        result = subprocess.run(['python3', '-B', HELPER], cwd=root,
                                stdout=log, stderr=subprocess.STDOUT, timeout=900)
    require(result.returncode == 0, 'qualified parent source review failed')
    git(root, 'bundle', 'verify', str(bundle_path))
    git(root, 'fetch', '--no-tags', str(bundle_path), HEAD)
    require(git(root, 'rev-parse', HEAD + '^{tree}').decode().strip() == TREE,
            'development tree differs')
    require(git(root, 'rev-parse', HEAD + '^').decode().strip() == PARENT,
            'development parent differs')
    git(root, 'merge-base', '--is-ancestor', BASE, HEAD)
    git(root, 'merge-base', '--is-ancestor', PAYLOAD['reviewed_audit_source'], HEAD)
    verify_continuations(root)
    require(git(root, 'diff', '--name-only', BASE, HEAD).decode().splitlines() == PATHS,
            'development scope changed')
    patch = git(root, 'diff', '--binary', '--full-index', BASE, HEAD)
    require(hashlib.sha256(patch).hexdigest() == PATCH_SHA256, 'patch hash differs')
    (output / 'development.patch').write_bytes(patch)
    git(root, 'reset', '--hard', HEAD)
    helper = (root / HELPER).read_bytes()
    require(re.findall(rb'^CONTRACT_SHA256 = "([^"\n]+)"$', helper, re.M) == [b'UNSEALED'],
            'development source must carry its explicit UNSEALED hash slot')
    require((root / CONTRACT).read_bytes() == git(root, 'show', BASE + ':' + CONTRACT),
            'development source did not retain predecessor manifest')
    require(not (root / 'docs/morphhdl/increment-59i-final-qualification.md').exists(),
            'unqualified source unexpectedly contains final qualification record')
    require(not git(root, 'status', '--porcelain', '--untracked-files=all'), 'dirty source')
    (output / 'head.txt').write_text(HEAD + '\n')
    (output / 'tree.txt').write_text(TREE + '\n')
    (output / 'tracked-before.txt').write_bytes(git(root, 'ls-files', '--stage'))
    (output / 'development-identity.json').write_text(json.dumps(dict(
        development_only=True, qualification=False, full_ci=False, source_sealed=False,
        qualified_parent=BASE, development_head=HEAD, source_tree=TREE,
        preserved_development_head=PAYLOAD['checkpoint'], direct_parent=PARENT,
        patch_sha256=PATCH_SHA256, bundle_sha256=BUNDLE_SHA256,
        continuations=PAYLOAD['continuations'], changed_paths=PATHS,
        audit_inputs_included=True, remote_refs_written=False), indent=2) + '\n')
    (output / 'path-receipt.json').write_text(json.dumps(dict(
        workspace=str(repository), source=str(root), output=str(output),
        source_env=os.environ['PROBE_SOURCE'], output_env=os.environ['PROBE_OUT']), indent=2) + '\n')
    print('ACTUAL UNSEALED DEVELOPMENT COMMIT PREPARED; NOT CURRENT SOURCE QUALIFICATION', flush=True)
    print('head=' + HEAD + ' tree=' + TREE + ' output=' + str(output), flush=True)


if __name__ == '__main__':
    main()
