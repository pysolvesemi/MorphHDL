#!/usr/bin/env python3
"""Publish or fetch the one exact-candidate integrity receipt for CI consumers."""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import zipfile

SPEC = importlib.util.spec_from_file_location('candidate_provenance', Path(__file__).with_name('check-candidate-provenance.py'))
P = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(P)
REPOSITORY = 'pysolvesemi/MorphHDL'
WORKFLOW = 336665858
JOB = 'Native source preservation and typed overlay'
OUTPUT = 'target/parameterized-inherited-audits/candidate-provenance/receipt.json'
MAX_BYTES = 16 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def limited(response):
    raw = response.read(MAX_BYTES + 1)
    P.require(len(raw) <= MAX_BYTES, 'oversized API response or artifact')
    return raw


def api(path, archive=False):
    P.require(path.startswith('/') and '..' not in path, 'unsafe API path')
    token = os.environ.get('GH_TOKEN')
    P.require(token, 'missing automatic Actions token')
    request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + path,
        headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
                 'X-GitHub-Api-Version': '2022-11-28'})
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=120) as response:
            P.require(not archive, 'artifact endpoint did not redirect')
            return P.parse(limited(response))
    except urllib.error.HTTPError as error:
        if not archive or error.code != 302:
            raise
        location = error.headers['Location']
        P.require(urllib.parse.urlparse(location).scheme == 'https', 'unsafe artifact redirect')
        # Never forward the job token to the signed artifact storage URL.
        with urllib.request.urlopen(location, timeout=120) as response:
            return limited(response)


def pages(path, key):
    result = []
    for page in range(1, 101):
        data = api(path + ('&' if '?' in path else '?') + 'per_page=100&page=' + str(page))[key]
        result.extend(data)
        if len(data) < 100:
            return result
    raise RuntimeError('Candidate provenance: pagination bound exceeded')


def select_run(runs, head, branch):
    matches = [run for run in runs if run['workflow_id'] == WORKFLOW and
               run['head_sha'] == head and run['head_branch'] == branch and run['status'] == 'completed' and
               run['conclusion'] == 'success' and run['event'] == 'workflow_dispatch']
    P.require(len(matches) == 1, 'missing or ambiguous successful exact-head integrity producer')
    run = matches[0]
    P.require(run['repository']['full_name'] == REPOSITORY and
              run['head_repository']['full_name'] == REPOSITORY,
              'foreign integrity producer')
    return run


def extract_receipt(raw, artifact):
    P.require(artifact.get('digest') == 'sha256:' + hashlib.sha256(raw).hexdigest(),
              'artifact digest mismatch')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        P.require(archive.namelist() == ['receipt.json'], 'unexpected artifact members')
        info = archive.getinfo('receipt.json')
        P.require(info.file_size <= MAX_BYTES, 'oversized receipt')
        return archive.read(info)


def fetch(root):
    head = P.identity(root)['commit']
    branch = os.environ.get('GITHUB_HEAD_REF') or os.environ.get('GITHUB_REF_NAME')
    P.require(branch, 'missing candidate branch')
    run = select_run(pages('/actions/workflows/' + str(WORKFLOW) + '/runs?head_sha=' + head,
                          'workflow_runs'), head, branch)
    jobs = pages('/actions/runs/' + str(run['id']) + '/attempts/' + str(run['run_attempt']) + '/jobs', 'jobs')
    P.require(len(jobs) == 1 and jobs[0]['name'] == JOB and jobs[0]['status'] == 'completed'
              and jobs[0]['conclusion'] == 'success' and jobs[0]['head_sha'] == head
              and jobs[0]['head_branch'] == branch and jobs[0]['run_attempt'] == run['run_attempt'],
              'incomplete or mismatched producer jobs')
    artifacts = pages('/actions/runs/' + str(run['id']) + '/artifacts', 'artifacts')
    name = 'candidate-provenance-' + head + '-' + str(run['run_attempt'])
    matches = [item for item in artifacts if item['name'] == name and not item['expired']]
    P.require(len(matches) == 1, 'missing or ambiguous integrity artifact')
    artifact = matches[0]
    P.require(artifact['workflow_run']['id'] == run['id'] and
              artifact['workflow_run']['head_sha'] == head, 'artifact producer differs')
    receipt = extract_receipt(api('/actions/artifacts/' + str(artifact['id']) + '/zip', archive=True), artifact)
    destination = root / OUTPUT
    destination.parent.mkdir(parents=True, exist_ok=True)
    P.require(not destination.exists() and not destination.is_symlink(), 'receipt already exists')
    with destination.open('xb') as output:
        output.write(receipt)
    sha = P.digest(receipt)
    P.consume(root, destination, sha)
    # Record the external authentication chain separately from the receipt.
    destination.with_name('transport.json').write_bytes(P.canonical({
        'workflow_id': WORKFLOW, 'run_id': run['id'], 'run_attempt': run['run_attempt'],
        'artifact_id': artifact['id'], 'artifact_digest': artifact['digest'], 'receipt_sha256': sha}))
    return sha


def consume(root):
    return P.consume(root, root / OUTPUT, os.environ.get('MORPHHDL_PROVENANCE_SHA256'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--produce', action='store_true')
    args = parser.parse_args()
    P.require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'unexpected CI repository')
    env = os.environ.get('GITHUB_ENV')
    P.require(env, 'missing CI environment output')
    sha = P.produce(P.ROOT, P.ROOT / OUTPUT) if args.produce else fetch(P.ROOT)
    with open(env, 'a') as output:
        output.write('MORPHHDL_PROVENANCE_SHA256=' + sha + '\n')
        output.write('MORPHHDL_PROVENANCE_HEAD=' + P.identity(P.ROOT)['commit'] + '\n')


if __name__ == '__main__':
    main()
