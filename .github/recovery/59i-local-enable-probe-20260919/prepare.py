#!/usr/bin/env python3
"""Reproduce the actual preserved development commit; never write remote refs."""
import base64
import hashlib
import json
import lzma
import os
from pathlib import Path
import subprocess

BASE = '90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6'
BASE_TREE = '1b8bf66047a28b836d61f6b168d28dd3b8f95cb2'
PARENT = 'd76fbd5f84869ac56186b36f35dfc3c480a80cbb'
TREE = 'c116c4e73abb439ef3d1ddacd35b7b3f6447ec16'
HEAD = '38e80295d8ec0268f752adf43c4cb22e442ce498'
COMPRESSED_SHA256 = 'a8cd9a15d6630dfae361d47c136833c4fc31c69c86f513ae5b734dc757eda9a5'
BUNDLE_SHA256 = 'a49627d040aa564d47c371a935787235369f5599e7c51cc1098bdffc7c5f322f'
PATCH_SHA256 = 'daa0b048acd9e76470bd8d5c43483a184cb07de95455da713815eab2c28a37a2'
PATHS = [
 'docs/morphhdl/increment-59i-local-enable-development.md',
 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala',
 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala',
 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala',
 'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableTests.scala']


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
    # Environment values remain relative; Actions need not translate custom paths.
    source_rel, out_rel = Path(os.environ['PROBE_SOURCE']), Path(os.environ['PROBE_OUT'])
    require(not source_rel.is_absolute() and '..' not in source_rel.parts, 'invalid source path')
    require(not out_rel.is_absolute() and '..' not in out_rel.parts, 'invalid output path')
    return repository, repository / source_rel, repository / out_rel


def main():
    repository, root, output = paths()
    output.mkdir(parents=True, exist_ok=True)
    require(not root.exists(), 'destination already exists')
    here = Path(__file__).resolve().parent
    parts = [here / ('part-%02d.b64' % i) for i in (1, 2, 3)]
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
        result = subprocess.run(['python3', '-B', 'morphhdl/scripts/check-increment-59i-production-successor.py'],
                                cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=900)
    require(result.returncode == 0, 'qualified parent source review failed')
    git(root, 'bundle', 'verify', str(bundle_path))
    git(root, 'fetch', '--no-tags', str(bundle_path), HEAD)
    require(git(root, 'rev-parse', HEAD + '^{tree}').decode().strip() == TREE,
            'development tree differs')
    require(git(root, 'rev-parse', HEAD + '^').decode().strip() == PARENT,
            'development parent differs')
    git(root, 'merge-base', '--is-ancestor', BASE, HEAD)
    require(git(root, 'diff', '--name-only', BASE, HEAD).decode().splitlines() == PATHS,
            'development scope changed')
    patch = git(root, 'diff', '--binary', '--full-index', BASE, HEAD)
    require(hashlib.sha256(patch).hexdigest() == PATCH_SHA256, 'patch hash differs')
    (output / 'development.patch').write_bytes(patch)
    git(root, 'reset', '--hard', HEAD)
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
        qualified_parent=BASE, development_head=HEAD, source_tree=TREE,
        preserved_development_head=PARENT, patch_sha256=PATCH_SHA256,
        bundle_sha256=BUNDLE_SHA256, remote_refs_written=False), indent=2) + '\n')
    (output / 'path-receipt.json').write_text(json.dumps(dict(
        workspace=str(repository), source=str(root), output=str(output),
        source_env=os.environ['PROBE_SOURCE'], output_env=os.environ['PROBE_OUT']), indent=2) + '\n')
    print('ACTUAL DEVELOPMENT COMMIT PREPARED; NOT SOURCE-SEALED OR QUALIFIED', flush=True)
    print('head=' + HEAD + ' tree=' + TREE + ' output=' + str(output), flush=True)


if __name__ == '__main__':
    main()
