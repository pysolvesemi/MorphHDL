#!/usr/bin/env python3
"""Build a preserved-history UNSEALED diagnostic controller; never publish it."""
import argparse
import ast
import base64
import hashlib
import json
import lzma
from pathlib import Path
import re
import subprocess

BASE = '90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6'
BASE_TREE = '1b8bf66047a28b836d61f6b168d28dd3b8f95cb2'
CHECKPOINT = 'd76fbd5f84869ac56186b36f35dfc3c480a80cbb'
CHECKPOINT_TREE = '5df50314aa7ae3bad916b157b69a87a278c391ba'
PROTOTYPE = '1931c0aa82860d81a9a651ffa06b920840ddea1e'
AUDIT_SOURCE = 'a3ffe64779a4947777782ae1efbbea17d5016d17'
CONTRACT = 'morphhdl/contracts/increment-59i-production-successor.json'
HELPER = 'morphhdl/scripts/check-increment-59i-production-successor.py'


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def git(root, *args):
    p = subprocess.run(['git', '--literal-pathspecs', *args], cwd=root,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    require(p.returncode == 0, p.stderr.decode(errors='replace'))
    return p.stdout


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def build(root, source, output, templates):
    require(re.fullmatch('[0-9a-f]{40}', source), 'source must be an exact full SHA')
    require(git(root, 'rev-parse', 'HEAD').decode().strip() == source, 'source must equal current HEAD')
    require(not git(root, 'status', '--porcelain', '--untracked-files=all'), 'source checkout is dirty')
    git(root, 'merge-base', '--is-ancestor', AUDIT_SOURCE, source)
    require(git(root, 'rev-parse', BASE + '^{tree}').decode().strip() == BASE_TREE, 'base tree changed')
    require(git(root, 'rev-parse', CHECKPOINT + '^{tree}').decode().strip() == CHECKPOINT_TREE,
            'checkpoint tree changed')
    require(git(root, 'show', '-s', '--format=%P', CHECKPOINT).decode().split() == [BASE, PROTOTYPE],
            'checkpoint parents changed')
    raw_helper = git(root, 'show', source + ':' + HELPER)
    require(re.findall(rb'^CONTRACT_SHA256 = "([^"\n]+)"$', raw_helper, re.M) == [b'UNSEALED'],
            'diagnostic requires an explicitly UNSEALED source')
    previous = git(root, 'show', BASE + ':' + CONTRACT)
    current = source
    while current != CHECKPOINT:
        parents = git(root, 'show', '-s', '--format=%P', current).decode().split()
        require(len(parents) == 1, 'post-checkpoint source is not linear: ' + current)
        require(git(root, 'show', current + ':' + CONTRACT) == previous,
                'intermediate source changed previous manifest: ' + current)
        require(not git(root, 'diff', BASE, current, '--', 'docs/morphhdl/parameterized-verilog-todo.md'),
                'intermediate source changed completion TODO: ' + current)
        current = parents[0]
    require(git(root, 'show', CHECKPOINT + ':' + CONTRACT) == previous, 'checkpoint manifest changed')
    expected = json.loads((templates / 'expected-suites.json').read_text())
    require(len(expected) == 10 and sum(map(len, expected.values())) == 138,
            'diagnostic scope must remain exactly 138 cases in 10 suites')
    inventory = json.loads(git(root, 'show', source + ':morphhdl/contracts/increment-59i-regression-inventory.json'))
    require({suite: inventory['projects']['morphhdl'][suite] for suite in expected} == expected,
            'controller case identities differ from committed source inventory')
    rows = []
    for commit in git(root, 'rev-list', '--reverse', '--topo-order', source, '^' + BASE).decode().splitlines():
        rows.append(dict(commit=commit, tree=git(root, 'rev-parse', commit + '^{tree}').decode().strip(),
                         parents=git(root, 'show', '-s', '--format=%P', commit).decode().split()))
    output.mkdir(parents=True, exist_ok=True)
    bundle_path = output / 'development.bundle'
    git(root, 'bundle', 'create', str(bundle_path), 'HEAD', '^' + BASE)
    bundle = bundle_path.read_bytes()
    compressed = lzma.compress(bundle, preset=9)
    b64 = base64.b64encode(compressed)
    for old in output.glob('part-*.b64'):
        old.unlink()
    part_names = []
    # Each part is independently bounded for the GitHub connector's text input.
    for start in range(0, len(b64), 48000):
        name = 'part-%02d.b64' % (len(part_names) + 1)
        (output / name).write_bytes(b64[start:start + 48000])
        part_names.append(name)
    patch = git(root, 'diff', '--binary', '--full-index', BASE, source)
    (output / 'development.patch').write_bytes(patch)
    payload = dict(base=BASE, base_tree=BASE_TREE, head=source,
        tree=git(root, 'rev-parse', source + '^{tree}').decode().strip(),
        parent=git(root, 'rev-parse', source + '^').decode().strip(),
        checkpoint=CHECKPOINT, checkpoint_tree=CHECKPOINT_TREE,
        checkpoint_parents=[BASE, PROTOTYPE], reviewed_audit_source=AUDIT_SOURCE,
        bundle_sha256=digest(bundle), compressed_sha256=digest(compressed),
        patch_sha256=digest(patch), parts=part_names, continuations=rows,
        paths=git(root, 'diff', '--name-only', BASE, source).decode().splitlines())
    (output / 'payload.json').write_text(json.dumps(payload, indent=2) + '\n')
    prepare = (templates / 'prepare.py').read_text()
    require(prepare.count('PAYLOAD = None\n') == 1, 'prepare template marker differs')
    prepare = prepare.replace('PAYLOAD = None\n', 'PAYLOAD = ' + repr(payload) + '\n')
    ast.parse(prepare)
    (output / 'prepare.py').write_text(prepare)
    (output / 'retain.py').write_bytes((templates / 'retain.py').read_bytes())
    (output / 'expected-suites.json').write_bytes((templates / 'expected-suites.json').read_bytes())
    workflow = (templates / 'probe.yml').read_text()
    workflow = workflow.replace('Increment 59i local-enable development compiler probe',
        'Increment 59i local-enable unsealed source diagnostic ' + source[:8])
    workflow = workflow.replace('increment-59i-local-enable-development-20260919-${{ matrix.scala }}',
        'increment-59i-local-enable-development-' + source[:8] + '-${{ matrix.scala }}')
    (output / 'probe.yml').write_text(workflow)
    publish = ['probe.yml', 'prepare.py', 'retain.py', 'expected-suites.json'] + part_names
    summary = dict(head=source, tree=payload['tree'], parent=payload['parent'],
        source_sealed=False, qualification=False, full_ci=False,
        cases=138, suites=10, scala=['2.12.18', '2.13.12'],
        preserved_commits=len(rows), changed_paths=len(payload['paths']),
        bundle_bytes=len(bundle), payload_bytes=len(b64), publish_files=publish,
        bundle_sha256=payload['bundle_sha256'], compressed_sha256=payload['compressed_sha256'])
    (output / 'build-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo-root', type=Path, required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--templates', type=Path, default=Path(__file__).parent / 'controller-development-v3/templates')
    args = parser.parse_args()
    build(args.repo_root.resolve(), args.source, args.output.resolve(), args.templates.resolve())
