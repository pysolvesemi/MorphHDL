#!/usr/bin/env python3
"""One-shot PR191 baseline/lane/60f targeted dispatch; never writes refs.

Uses only the runner's ordinary repository token against api.github.com. An
fsynced intent precedes every POST. Requests are never retried: ambiguous or
partial launches are retained for the hourly monitor to reconcile.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.parse
import urllib.request

REPOSITORY = 'pysolvesemi/MorphHDL'
HEAD = '025ae94b61a8620c5b75af967425898e579f4829'
ALLOWED_IDS = {332279971, 357516223, 351327055}
ACTIVE = {'queued', 'in_progress', 'requested', 'waiting', 'pending'}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def write_journal(path, value):
    temporary = path.with_suffix('.tmp')
    with temporary.open('w') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise RuntimeError('Unexpected GitHub API redirect; request not retried')


class Api:
    def __init__(self, manifest):
        self.manifest = manifest
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, path, method='GET', payload=None):
        require(path.startswith('/') and not path.startswith('//'), 'Invalid API path')
        if method == 'POST':
            match = re.fullmatch(r'/actions/workflows/(\d+)/dispatches', path)
            require(match and int(match[1]) in ALLOWED_IDS, 'Non-allowlisted write')
            require(payload == {'ref': self.manifest['branch']}, 'Unexpected dispatch payload')
        else:
            require(method == 'GET' and payload is None, 'Only reads and exact dispatches allowed')
        data = None if payload is None else json.dumps(payload).encode()
        request = urllib.request.Request(
            'https://api.github.com/repos/' + REPOSITORY + path,
            data=data,
            method=method,
            headers={
                'Authorization': 'Bearer ' + os.environ['GITHUB_TOKEN'],
                'Accept': 'application/vnd.github+json',
                'X-GitHub-Api-Version': '2022-11-28',
                'Content-Type': 'application/json',
                'User-Agent': 'pr191-targeted-ci',
            },
        )
        with self.opener.open(request, timeout=60) as response:
            if method == 'POST':
                require(response.status == 204,
                        'Unexpected dispatch response; reconcile before retry')
                return None
            return json.load(response)

    def collection(self, path, key, **query):
        rows = []
        page = 1
        while True:
            suffix = urllib.parse.urlencode(dict(query, per_page=100, page=page))
            value = self.request(path + '?' + suffix)
            part = value[key]
            rows.extend(part)
            if len(part) < 100:
                require(len({row['id'] for row in rows}) == len(rows),
                        'Pagination shifted; retry read later')
                return rows
            page += 1
            require(page <= 100, 'Unexpected pagination size')


def refs_guard(api, manifest):
    pr = api.request('/pulls/191')
    require(pr['state'] == 'open' and not pr['merged'], 'PR is not open')
    require(pr['head']['repo']['full_name'] == REPOSITORY, 'Foreign PR repository')
    require(pr['head']['ref'] == manifest['branch'] and pr['head']['sha'] == HEAD,
            'Feature branch changed; stop and reconcile')
    require(pr['base']['ref'] == manifest['target_branch'] and
            pr['base']['sha'] == manifest['target'], 'Target branch changed')
    for branch, expected in ((manifest['branch'], HEAD),
                             (manifest['target_branch'], manifest['target'])):
        value = api.request('/git/ref/heads/' + urllib.parse.quote(branch, safe='/'))
        require(value['object']['sha'] == expected, 'Live ref changed: ' + branch)


def choose_existing(runs, row):
    matches = [run for run in runs if run['workflow_id'] == row['id']]
    require(len(matches) <= 1, 'Multiple exact-head runs; reconcile before dispatch')
    if not matches:
        return None
    run = matches[0]
    require(run['head_sha'] == HEAD and run['path'].split('@')[0] == row['path'],
            'Run source or workflow identity mismatch')
    require(run['status'] in ACTIVE or
            (run['status'] == 'completed' and run['conclusion'] == 'success'),
            'Exact-head terminal failure or skip needs diagnosis, not a new dispatch')
    return run


def authenticate_failures(api, manifest):
    for row in manifest['workflows']:
        run = api.request('/actions/runs/' + str(row['failed_run']))
        require(run['head_sha'] == row['failed_head'] and
                run['workflow_id'] == row['id'] and
                run['path'].split('@')[0] == row['path'] and
                run['event'] == 'workflow_dispatch' and
                run['head_branch'] == manifest['branch'] and
                run['status'] == 'completed' and run['conclusion'] == 'failure',
                'Historical failed-run identity or conclusion changed')
        jobs = api.collection('/actions/runs/' + str(run['id']) + '/jobs',
                              'jobs', filter='latest')
        require(jobs and any(job['conclusion'] == 'failure' for job in jobs),
                'No substantive failed job supports this target')
        require(all(job['run_id'] == run['id'] and
                    job['head_sha'] == row['failed_head'] for job in jobs),
                'Historical job source mismatch')


def dispatch(api, manifest, journal, output):
    refs_guard(api, manifest)
    authenticate_failures(api, manifest)
    rows = manifest['workflows']
    # Plan the whole set before the first write so an existing failure stops all
    # dispatches and a later row cannot turn the operation into a partial retry.
    runs = api.collection('/actions/runs', 'workflow_runs', head_sha=HEAD)
    for row in rows:
        choose_existing(runs, row)
    journal['phase'] = 'dispatching'
    write_journal(output, journal)
    for row in rows:
        refs_guard(api, manifest)
        runs = api.collection('/actions/runs', 'workflow_runs', head_sha=HEAD)
        current = choose_existing(runs, row)
        record = {'workflow_id': row['id'], 'workflow_path': row['path']}
        journal['records'].append(record)
        if current:
            record.update(action='reuse', run_id=current['id'], url=current['html_url'])
            write_journal(output, journal)
            print(json.dumps(record), flush=True)
            continue
        record.update(action='dispatch-intent', before_run_ids=[r['id'] for r in runs])
        write_journal(output, journal)
        print(json.dumps(record), flush=True)
        try:
            api.request('/actions/workflows/' + str(row['id']) + '/dispatches',
                        'POST', {'ref': manifest['branch']})
        except Exception:
            record['action'] = 'dispatch-uncertain'
            write_journal(output, journal)
            raise
        record['action'] = 'dispatch-requested'
        write_journal(output, journal)
        print(json.dumps(record), flush=True)
    refs_guard(api, manifest)
    # One reconciliation read only. The hourly monitor handles delayed runs.
    runs = api.collection('/actions/runs', 'workflow_runs', head_sha=HEAD)
    for record, row in zip(journal['records'], rows):
        current = choose_existing(runs, row)
        if current:
            record.update(run_id=current['id'], url=current['html_url'])
    journal['phase'] = 'requests-complete'
    write_journal(output, journal)
    print(json.dumps(journal), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(Path(__file__).with_name('manifest.json').read_text())
    require(manifest['repository'] == REPOSITORY and manifest['head'] == HEAD,
            'Manifest target mismatch')
    require(len(manifest['workflows']) == 3 and
            {row['id'] for row in manifest['workflows']} == ALLOWED_IDS,
            'Unexpected workflow scope')
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'Wrong repository')
    require(os.environ.get('GITHUB_REF') ==
            'refs/heads/' + manifest['controller_branch'], 'Wrong controller ref')
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
        require(hashlib.sha256((args.source / row['path']).read_bytes()).hexdigest() ==
                row['sha256'], 'Workflow bytes changed: ' + row['path'])
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
    journal = {
        'head': HEAD,
        'tree': manifest['tree'],
        'controller_run_id': run_id,
        'controller_sha': os.environ['GITHUB_SHA'],
        'phase': 'preflight',
        'records': [],
    }
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
