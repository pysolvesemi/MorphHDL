#!/usr/bin/env python3
"""Dispatch only five original failed inherited workflows on the reviewed PR189 sync.

No Git ref writes, arbitrary endpoint access, old-head reruns, or full CI.
Successful replacements and incomplete local-enable are not in this allowlist.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import urllib.request

REPO = 'pysolvesemi/MorphHDL'
HEAD = 'f42641880e0645f0c997ecedabd031bf8948bbfa'
TARGET = 'e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d'
FAILED_HEAD = '14dc0d2bb7274d9e91b60f112619a78b237936ab'
BRANCH = 'recovery/increment-59i-pr189-sync-f4264188'
FEATURE = 'agent/increment-59i-combined-reduction-closure'
SELECTED = {350978616: 35138263862, 351370311: 35138263964,
            351397918: 35138263633, 351996892: 35138263581,
            351327055: 35138263797}
READS = {'/pulls/177', '/git/ref/heads/' + FEATURE,
         '/git/ref/heads/' + BRANCH, '/git/ref/heads/parameterized-verilog'}


def require(ok, message):
    if not ok:
        raise RuntimeError('59i failed-only PR189 dispatch: ' + message)


def run_path(workflow):
    return '/actions/workflows/%d/runs?head_sha=%s&event=workflow_dispatch&per_page=100' % (workflow, HEAD)


def allowed(method, path, body):
    if method == 'GET' and body is None:
        return path in (READS | {'/actions/runs/' + str(r) for r in SELECTED.values()} |
                        {run_path(w) for w in SELECTED})
    return method == 'POST' and body == {'ref': BRANCH} and path in {
        '/actions/workflows/%d/dispatches' % w for w in SELECTED}


def api(method, path, body=None):
    require(allowed(method, path, body), 'endpoint or payload outside exact allowlist')
    require(os.environ.get('GITHUB_REPOSITORY') == REPO, 'wrong authorized repository')
    token = os.environ.get('GH_TOKEN')
    require(bool(token), 'missing authorized job token')
    req = urllib.request.Request('https://api.github.com/repos/' + REPO + path,
        method=method, data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
                 'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28'})
    with urllib.request.urlopen(req, timeout=120) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def live_identity(request):
    p = request('GET', '/pulls/177')
    require(p['state'] == 'open' and p['merged'] is False and p['draft'] is True and
            p['head']['sha'] == HEAD and p['head']['ref'] == FEATURE and
            p['head']['repo']['full_name'] == REPO and p['base']['ref'] == 'parameterized-verilog',
            'PR identity or completion state changed')
    for branch, expected in ((FEATURE, HEAD), (BRANCH, HEAD), ('parameterized-verilog', TARGET)):
        require(request('GET', '/git/ref/heads/' + branch)['object']['sha'] == expected,
                'source or target ref changed: ' + branch)


def validate_failure(request, workflow, run):
    v = request('GET', '/actions/runs/' + str(run))
    require(v['id'] == run and v['workflow_id'] == workflow and v['head_sha'] == FAILED_HEAD and
            v['status'] == 'completed' and v['conclusion'] == 'failure',
            'original failed-workflow evidence differs: ' + str(run))


def inventory(request, workflow):
    data = request('GET', run_path(workflow))
    runs = data['workflow_runs']
    require(data['total_count'] == len(runs), 'truncated workflow inventory')
    require(len({r['id'] for r in runs}) == len(runs), 'duplicate run identity')
    require(all(r['head_sha'] == HEAD and r['workflow_id'] == workflow and
                r['event'] == 'workflow_dispatch' for r in runs), 'foreign run in inventory')
    return runs


def dispatch(out: Path, request=api):
    live_identity(request)
    # Validate every selected failure BEFORE any dispatch; no partial inventory shortcut.
    for workflow, run in SELECTED.items():
        validate_failure(request, workflow, run)
        inventory(request, workflow)
    result = dict(head=HEAD, target=TARGET, branch=BRANCH, full_ci=False,
                  refs_updated=False, selected=SELECTED, dispatches=[])
    out.mkdir(parents=True, exist_ok=True)
    for workflow, run in SELECTED.items():
        live_identity(request)
        validate_failure(request, workflow, run)
        runs = inventory(request, workflow)
        # An existing queued, running, failed OR successful same-head execution is
        # retained. A future specific retry requires a separate deliberate action.
        if not runs:
            request('POST', '/actions/workflows/%d/dispatches' % workflow, {'ref': BRANCH})
        result['dispatches'].append(dict(workflow_id=workflow, original_failure=run,
                                        dispatched=not runs, existing_runs=[r['id'] for r in runs]))
        tmp = out/'dispatch.tmp'
        tmp.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
        tmp.replace(out/'dispatch.json')
    require(len(result['dispatches']) == len(SELECTED), 'incomplete dispatch ledger')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(dispatch(args.output), indent=2))
