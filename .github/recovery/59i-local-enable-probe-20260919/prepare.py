#!/usr/bin/env python3
"""Prepare an exact development tree; never issue a source seal or write remote refs."""
import base64
import hashlib
import json
import lzma
import os
from pathlib import Path
import subprocess

BASE = '90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6'
TREE = '5df50314aa7ae3bad916b157b69a87a278c391ba'
HEAD = 'bd6a2ab868925c0cfe7dcf020d07d73df1d1d05c'
COMPRESSED = 'a16b3a6ba83cb1c8a79c5b147a9ba0aec1a780fa6ee6eeabd38eb496ebd26bb7'
PATCH = 'ab565f680f803472a300d23805ba5ec37a0e49df2d5075b9eefcb1846404297e'
PATHS = [
 'docs/morphhdl/increment-59i-local-enable-development.md',
 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala',
 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala',
 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala',
 'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableTests.scala']


def require(ok, why):
    if not ok:
        raise RuntimeError('59i development probe: ' + why)


def git(root, *args, data=None, env=None):
    p = subprocess.run(['git', '--literal-pathspecs', *args], cwd=root,
                       input=data, capture_output=True, env=env, timeout=180)
    require(p.returncode == 0, p.stderr.decode(errors='replace'))
    return p.stdout


def main():
    repository = Path(os.environ['GITHUB_WORKSPACE']).resolve()
    output = Path(os.environ['PROBE_OUT']).resolve()
    root = Path(os.environ['PROBE_SOURCE']).resolve()
    output.mkdir(parents=True, exist_ok=True)
    require(not root.exists(), 'destination already exists')
    here = Path(__file__).resolve().parent
    parts = [here / ('part-%02d.b64' % i) for i in (1, 2, 3)]
    require(all(p.is_file() and not p.is_symlink() for p in parts), 'invalid payload')
    compressed = base64.b64decode(b''.join(p.read_bytes() for p in parts), validate=True)
    require(hashlib.sha256(compressed).hexdigest() == COMPRESSED, 'compressed hash differs')
    patch = lzma.decompress(compressed)
    require(hashlib.sha256(patch).hexdigest() == PATCH, 'patch hash differs')
    (output / 'development.patch').write_bytes(patch)
    git(repository, 'worktree', 'add', '--detach', str(root), BASE)
    require(git(root, 'rev-parse', 'HEAD^{tree}').decode().strip() ==
            '1b8bf66047a28b836d61f6b168d28dd3b8f95cb2', 'qualified base tree differs')
    # Authenticate the qualified parent BEFORE introducing development source.
    with (output / 'qualified-parent-review.log').open('wb') as log:
        result = subprocess.run(['python3', '-B', 'morphhdl/scripts/check-increment-59i-production-successor.py'],
                                cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=900)
    require(result.returncode == 0, 'qualified parent source review failed')
    git(root, 'apply', '--check', '--index', '-', data=patch)
    git(root, 'apply', '--index', '-', data=patch)
    require(git(root, 'diff', '--cached', '--name-only', BASE).decode().splitlines() == PATHS,
            'development scope changed')
    require(git(root, 'write-tree').decode().strip() == TREE, 'prototype tree differs')
    env = dict(os.environ, GIT_AUTHOR_NAME='MorphHDL continuation',
               GIT_AUTHOR_EMAIL='morphhdl-continuation@example.invalid',
               GIT_COMMITTER_NAME='MorphHDL continuation',
               GIT_COMMITTER_EMAIL='morphhdl-continuation@example.invalid',
               GIT_AUTHOR_DATE='2026-09-19T04:30:00+0000',
               GIT_COMMITTER_DATE='2026-09-19T04:30:00+0000')
    message = ('Development-only local-enable compiler probe [skip ci]\n\n'
               'Exact prototype tree from d76fbd5f; this is not a source seal or qualification.\n')
    head = git(root, 'commit-tree', TREE, '-p', BASE, data=message.encode(), env=env).decode().strip()
    require(head == HEAD, 'diagnostic commit identity differs')
    git(root, 'reset', '--hard', HEAD)
    # The existing mandatory qualification gate must remain unchanged and incomplete.
    require(not git(root, 'diff', BASE, HEAD, '--', '.github/workflows',
                    'morphhdl/contracts', 'docs/morphhdl/parameterized-verilog-todo.md'),
            'qualification controls changed')
    require(not (root / 'morphhdl/contracts/increment-59i-local-enable-review.json').exists(),
            'this development snapshot unexpectedly claims a local-enable certificate')
    require(not git(root, 'status', '--porcelain', '--untracked-files=all'), 'dirty source')
    (output / 'head.txt').write_text(HEAD + '\n')
    (output / 'tree.txt').write_text(TREE + '\n')
    (output / 'tracked-before.txt').write_bytes(git(root, 'ls-files', '--stage'))
    (output / 'development-identity.json').write_text(json.dumps(dict(
        development_only=True, qualification=False, full_ci=False,
        qualified_parent=BASE, diagnostic_head=HEAD, source_tree=TREE,
        preserved_development_head='d76fbd5f84869ac56186b36f35dfc3c480a80cbb',
        patch_sha256=PATCH, remote_refs_written=False), indent=2) + '\n')
    print('EXACT DEVELOPMENT TREE PREPARED; NOT SOURCE-SEALED OR QUALIFIED', flush=True)


if __name__ == '__main__':
    main()
