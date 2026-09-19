#!/usr/bin/env python3
"""Build a reviewed exact-source staging payload without remote writes."""
from __future__ import annotations
import argparse
import base64
import hashlib
import importlib.util
import json
import lzma
from pathlib import Path
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('stage', HERE / 'stage.py')
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


def tree(repo, ref):
    values = {}
    for row in stage.git(repo, 'ls-tree', '-rz', ref).split(b'\0'):
        if row:
            meta, path = row.split(b'\t', 1)
            mode, kind, sha = meta.decode().split()
            values[path.decode()] = (mode, kind, sha)
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo-root', type=Path, required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--seal', required=True)
    parser.add_argument('--diagnostic-source', required=True)
    parser.add_argument('--diagnostic-run', type=int, required=True)
    parser.add_argument('--diagnostic-controller', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    repo, out = args.repo_root.resolve(), args.output.resolve()
    stage.require(not out.exists(), 'output already exists')
    source = stage.git(repo, 'rev-parse', args.source + '^{commit}').decode().strip()
    seal = stage.git(repo, 'rev-parse', args.seal + '^{commit}').decode().strip()
    diagnostic = stage.git(repo, 'rev-parse', args.diagnostic_source + '^{commit}').decode().strip()
    stage.require_clean(repo, seal)
    stage.require(stage.git(repo, 'rev-parse', seal + '^').decode().strip() == source,
                  'seal is not direct child of source')
    stage.require(stage.git(repo, 'diff', '--name-only', source, seal).decode().splitlines()
                  == sorted([stage.HELPER, stage.CONTRACT]), 'seal delta is not exactly two files')
    stage.git(repo, 'merge-base', '--is-ancestor', stage.BASE, seal)
    stage.git(repo, 'merge-base', '--is-ancestor', diagnostic, source)
    stage.git(repo, 'merge-base', '--is-ancestor', 'd76fbd5f84869ac56186b36f35dfc3c480a80cbb', source)
    shas = stage.git(repo, 'rev-list', '--reverse', '--topo-order', seal, '^' + stage.BASE).decode().splitlines()
    commits, requests, blobs = [], [], {}
    for sha in shas:
        raw = stage.git(repo, 'cat-file', 'commit', sha)
        body = stage.commit_body(raw)
        commits.append(dict(sha=sha, raw=raw.decode(), tree=body['tree'], parents=body['parents']))
        before, after = tree(repo, body['parents'][0]), tree(repo, sha)
        entries = []
        for path in sorted(set(before) | set(after)):
            if before.get(path) == after.get(path):
                continue
            mode, kind, object_sha = after.get(path, before.get(path))
            stage.require(kind == 'blob' and mode in ('100644', '100755'),
                          'unexpected changed non-file: ' + path)
            entry = dict(path=path, mode=mode, type=kind, sha=object_sha if path in after else None)
            entries.append(entry)
            if path in after:
                content = stage.git(repo, 'cat-file', 'blob', object_sha)
                blobs[object_sha] = dict(sha=object_sha, sha256=stage.digest(content), size=len(content))
        requests.append(dict(commit=sha, expected_tree_sha=body['tree'], repository_full_name=stage.REPO,
            base_tree_sha=stage.git(repo, 'rev-parse', body['parents'][0] + '^{tree}').decode().strip(),
            tree_elements=entries))
    # Bind all Scala/build source and exact hardware verification programs to the
    # diagnostic commit. Audit-only Python/contracts may evolve before the seal.
    runtime_paths = stage.runtime_paths(repo, seal)
    stage.require(runtime_paths == stage.runtime_paths(repo, diagnostic),
                  'runtime file path set changed after diagnostic')
    gitlinks = stage.runtime_gitlinks(repo, seal)
    stage.require(gitlinks == stage.runtime_gitlinks(repo, diagnostic),
                  'runtime gitlinks changed after diagnostic')
    runtime = {}
    for path in sorted(runtime_paths):
        current = stage.git(repo, 'show', seal + ':' + path)
        stage.require(current == stage.git(repo, 'show', diagnostic + ':' + path),
                      'runtime changed after diagnostic: ' + path)
        runtime[path] = stage.digest(current)
    # Source checking remains mandatory in Actions as well; this inexpensive
    # local authentication rejects an accidentally stale or non-final seal now.
    result = subprocess.run(['python3', '-B', stage.HELPER], cwd=repo, capture_output=True, timeout=1200)
    stage.require(result.returncode == 0, 'local seal check failed: ' + result.stdout.decode(errors='replace')
                  + result.stderr.decode(errors='replace'))
    out.mkdir(parents=True)
    archive = out / 'exact-source.bundle'
    stage.git(repo, 'bundle', 'create', str(archive), seal, '^' + stage.BASE)
    stage.git(repo, 'bundle', 'verify', str(archive))
    raw = archive.read_bytes()
    compressed = lzma.compress(raw, preset=9)
    encoded = base64.b64encode(compressed)
    parts = []
    for index, offset in enumerate(range(0, len(encoded), 64000), 1):
        name = 'part-%02d.b64' % index
        (out / name).write_bytes(encoded[offset:offset + 64000])
        parts.append(name)
    value = dict(schema=1, repository=stage.REPO, base=stage.BASE, target=stage.TARGET,
        feature=stage.FEATURE, workflow=stage.WORKFLOW, source=source, seal=seal,
        commits=commits, blobs=[blobs[key] for key in sorted(blobs)], tree_requests=requests,
        parts=parts, bundle_sha256=stage.digest(raw), compressed_sha256=stage.digest(compressed),
        checks=stage.CHECKS, diagnostic=dict(run_id=args.diagnostic_run,
            controller_sha=args.diagnostic_controller, source_sha=diagnostic,
            runtime_files=runtime, runtime_gitlinks=gitlinks))
    stage.write_json(out / 'payload.json', value)
    shutil.copy2(HERE / 'stage.py', out / 'stage.py')
    shutil.copy2(HERE / 'stage.yml', out / 'stage.yml')
    (out / 'local-seal-check.log').write_bytes(result.stdout + result.stderr)
    stage.load(out)
    summary = dict(source=source, seal=seal, commits=[item['sha'] for item in commits],
        payload_sha256=stage.digest((out / 'payload.json').read_bytes()),
        bundle_sha256=value['bundle_sha256'], controller_files=['stage.py', 'payload.json', *parts],
        workflow='.github/workflows/increment-59i-local-enable-publication-stage.yml',
        recovery_directory='.github/recovery/59i-local-enable-publication',
        runtime_files=len(runtime), changed_blob_objects=len(blobs), refs_updated=False, remote_calls=False)
    stage.write_json(out / 'build-receipt.json', summary)
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
