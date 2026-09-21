#!/usr/bin/env python3
"""One-shot PR191 baseline count-repair qualification dispatch."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
HELPER = HERE.parent / 'pr191-mill-golden-025ae94b' / 'dispatch.py'
HELPER_SHA256 = 'afa385aff295a4eca54832c9c18e217181b0d7d252a3bf1ee265f039851338bb'
spec = importlib.util.spec_from_file_location('pr191_dispatch_base', HELPER)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

REPOSITORY = base.REPOSITORY
HEAD = 'cb72ea2851b529fbe216c1a56a0de68d68770400'
ALLOWED_IDS = {332279971}
base.HEAD = HEAD
base.ALLOWED_IDS = ALLOWED_IDS
require = base.require
write_journal = base.write_journal
Api = base.Api
dispatch = base.dispatch


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(HERE.joinpath('manifest.json').read_text())
    require(hashlib.sha256(HELPER.read_bytes()).hexdigest() == HELPER_SHA256,
            'Reviewed dispatch helper changed')
    require(manifest['repository'] == REPOSITORY and manifest['head'] == HEAD,
            'Manifest target mismatch')
    require(len(manifest['workflows']) == 1 and
            {row['id'] for row in manifest['workflows']} == ALLOWED_IDS,
            'Unexpected workflow scope')
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'Wrong repository')
    require(os.environ.get('GITHUB_REF') == 'refs/heads/' + manifest['controller_branch'],
            'Wrong controller ref')
    require(os.environ.get('GITHUB_EVENT_NAME') == 'push',
            'Controller supports its reviewed push only')
    require(os.environ.get('GITHUB_RUN_ATTEMPT') == '1',
            'Controller retries prohibited; reconcile the prior journal first')
    git = lambda *a: subprocess.check_output(['git', *a], cwd=args.source).decode().strip()
    require(git('rev-parse', 'HEAD') == HEAD and
            git('rev-parse', 'HEAD^{tree}') == manifest['tree'],
            'Qualification checkout identity mismatch')
    require(not git('status', '--porcelain', '--untracked-files=all'),
            'Qualification checkout is dirty')
    for row in manifest['workflows']:
        require(hashlib.sha256((args.source / row['path']).read_bytes()).hexdigest() == row['sha256'],
                'Workflow bytes changed: ' + row['path'])
    api = Api(manifest)
    run_id = int(os.environ['GITHUB_RUN_ID'])
    current = api.request('/actions/runs/' + str(run_id))
    require(current['head_sha'] == os.environ['GITHUB_SHA'] and
            current['path'].split('@')[0] == manifest['controller_path'],
            'Controller run mismatch')
    previous = api.collection('/actions/runs', 'workflow_runs',
                              branch=manifest['controller_branch'])
    require(not [run for run in previous if run['id'] != run_id and
                 run['path'].split('@')[0] == manifest['controller_path']],
            'A prior controller run exists; reconcile its journal instead of relaunching')
    args.output.mkdir(parents=True, exist_ok=True)
    journal = {'head': HEAD, 'tree': manifest['tree'], 'controller_run_id': run_id,
               'controller_sha': os.environ['GITHUB_SHA'], 'phase': 'preflight', 'records': []}
    output = args.output / 'dispatch-journal.json'
    write_journal(output, journal)
    try:
        dispatch(api, manifest, journal, output)
    except Exception as error:
        journal['error'] = str(error)
        write_journal(output, journal)
        raise


if __name__ == '__main__':
    main()
