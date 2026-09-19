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
PAYLOAD = {'base': '90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6', 'base_tree': '1b8bf66047a28b836d61f6b168d28dd3b8f95cb2', 'head': '545134a42dd200c5cee679db5dde0fea0acb15c9', 'tree': 'cab84063b2933dd146f2b5ff37aab42f7ea0b191', 'parent': '86f6e3d9c7c4dfbd629533668a6f5dfe296bf602', 'checkpoint': 'd76fbd5f84869ac56186b36f35dfc3c480a80cbb', 'checkpoint_tree': '5df50314aa7ae3bad916b157b69a87a278c391ba', 'checkpoint_parents': ['90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6', '1931c0aa82860d81a9a651ffa06b920840ddea1e'], 'reviewed_audit_source': 'a3ffe64779a4947777782ae1efbbea17d5016d17', 'bundle_sha256': '65c033ddfbcad4583c664c38aae3f14e2215ea25d08818a1029752ad76e25105', 'compressed_sha256': '2bcac920ba36d9f7698c2fe5d5f6cee4186fd512b2eb4d97949db9ec71ddd3cf', 'patch_sha256': '480ca6ccd51aad84d4beeff7ccac052a6e674998a3adf27853853f7c782e9f63', 'parts': ['part-01.b64', 'part-02.b64', 'part-03.b64', 'part-04.b64'], 'continuations': [{'commit': '34a93ec7f9cde83e10fd5c47737b324167f54b5e', 'tree': 'b96545aea836689ba430b0e419d8352409204710', 'parents': ['37d1629f9b78c4d9cd6646abee9962fdd046e413']}, {'commit': '1931c0aa82860d81a9a651ffa06b920840ddea1e', 'tree': 'd9272a2c2c48394c5a6964773853adbfd3ed9fd4', 'parents': ['f42641880e0645f0c997ecedabd031bf8948bbfa', '34a93ec7f9cde83e10fd5c47737b324167f54b5e']}, {'commit': 'd76fbd5f84869ac56186b36f35dfc3c480a80cbb', 'tree': '5df50314aa7ae3bad916b157b69a87a278c391ba', 'parents': ['90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6', '1931c0aa82860d81a9a651ffa06b920840ddea1e']}, {'commit': '38e80295d8ec0268f752adf43c4cb22e442ce498', 'tree': 'c116c4e73abb439ef3d1ddacd35b7b3f6447ec16', 'parents': ['d76fbd5f84869ac56186b36f35dfc3c480a80cbb']}, {'commit': 'c5ecddbf5db0ef56c229b0065d9eef85f8c545cb', 'tree': '19760753d514a2cdf4cf311b82bfe639d4afc9c2', 'parents': ['38e80295d8ec0268f752adf43c4cb22e442ce498']}, {'commit': 'ce8822f5e7038aba5ce33aff69f849d2271e1f2f', 'tree': '7f93be41567c7c4750691e6d9281c561918bb849', 'parents': ['c5ecddbf5db0ef56c229b0065d9eef85f8c545cb']}, {'commit': 'a3ffe64779a4947777782ae1efbbea17d5016d17', 'tree': 'b5aed915338b0bcd3b69744aa37a98f250012daa', 'parents': ['ce8822f5e7038aba5ce33aff69f849d2271e1f2f']}, {'commit': 'cb093b5aa235760f2c3149f4327324976aa0495b', 'tree': 'd207a118d2ef0260efe2388c7df0b8d5954c2d04', 'parents': ['a3ffe64779a4947777782ae1efbbea17d5016d17']}, {'commit': '6a858ead678f7ec96e9a421f2b4551bcd62d65d9', 'tree': '48ee0b33c8c88ca030603141a6c734648bb54257', 'parents': ['cb093b5aa235760f2c3149f4327324976aa0495b']}, {'commit': '86f6e3d9c7c4dfbd629533668a6f5dfe296bf602', 'tree': '4ade045ae6d8debdf933a1d965a934a686276e6e', 'parents': ['6a858ead678f7ec96e9a421f2b4551bcd62d65d9']}, {'commit': '545134a42dd200c5cee679db5dde0fea0acb15c9', 'tree': 'cab84063b2933dd146f2b5ff37aab42f7ea0b191', 'parents': ['86f6e3d9c7c4dfbd629533668a6f5dfe296bf602']}], 'paths': ['.github/workflows/increment-59i-combined-closure.yml', '.github/workflows/increment-59i-local-enable-committed-head.yml', 'docs/morphhdl/increment-59i-local-enable-development.md', 'morphhdl/contracts/increment-59i-local-enable-review.json', 'morphhdl/contracts/increment-59i-regression-inventory.json', 'morphhdl/scripts/check-cdc-successor-source.py', 'morphhdl/scripts/check-increment-59i-composite-local-enable.py', 'morphhdl/scripts/check-increment-59i-local-enable-combined.py', 'morphhdl/scripts/check-increment-59i-local-enable-results.py', 'morphhdl/scripts/check-increment-59i-local-enable-source-review.py', 'morphhdl/scripts/check-increment-59i-production-successor.py', 'morphhdl/scripts/check-increment-59i-regression-inventory.py', 'morphhdl/scripts/check-increment-59i-rollout-composition.py', 'morphhdl/scripts/check-increment-59i-target-integration.py', 'morphhdl/scripts/check-increment-59i-widening-source-review.py', 'morphhdl/scripts/check-increment-60b-signedness-authority.py', 'morphhdl/scripts/check-increment-61-source-review.py', 'morphhdl/scripts/check-increment-62-wa08-source-overlay.py', 'morphhdl/scripts/test-increment-59i-continuation.py', 'morphhdl/scripts/test-increment-59i-local-enable-results.py', 'morphhdl/scripts/test-increment-59i-local-enable-source-review.py', 'morphhdl/scripts/test-increment-59i-local-enable-successor.py', 'morphhdl/scripts/test-increment-59i-pr189-sync.py', 'morphhdl/scripts/test-increment-59i-regression-inventory.py', 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala', 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala', 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionClosedGraph.scala', 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala', 'morphhdl/src/test/scala/nativeapplication/BalancedCompositeLocalEnableNativeOracle.scala', 'morphhdl/src/test/scala/nativeapplication/BalancedLocalEnableCombinedNativeOracle.scala', 'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableArtifactWriter.scala', 'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableTests.scala', 'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionLocalEnableCombinedArtifactWriter.scala']}
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
