"""One-shot checksum-bound source staging. Never writes a remote Git ref.
The authorized connector, not the Actions token, publishes the final ref.
"""
from pathlib import Path
import base64
import datetime
import gzip
import hashlib
import json
import os
import re
import subprocess
import urllib.request

REPO = 'pysolvesemi/MorphHDL'
BRANCH = 'agent/independent-parameter-domain-composition'
BASE = '91f6c2b347cc480118fc5ea3a2dd577482f54c7e'
EXPECTED_TREE = '86a5fc20deba3b1d5fcbd7b642f70b7e19ad2e78'
TRANSFER = Path('.github/symbolic-transfer')
TRANSIENTS = [
    '.github/independent-root-inventory.patch',
    '.github/pr188-import-reviewed-fix.py',
    '.github/pr188-reviewed-fix.patch.gz',
    '.github/workflows/independent-parameter-cancel-pending.yml',
    '.github/workflows/independent-parameter-root-inventory-repair.yml',
    '.github/workflows/pr188-import-reviewed-fix.yml',
]
PARTS = [
    '88f4e30dd822537fccbe14504994dc63040d1449',
    '2378832c0a1bceb07671b564f417d237ff147b52',
    'e97fbbc78beb5b3c0b392c0809f8ce7aad20e09a',
    'ab8363dede5897d13c2da46dc541d37850f21b4e',
    '45e6793b4aff4d4a1ce2e40320a0550ee61de04c',
    '4f9ec5388101dd917b0ff3bf0e5699bc8a7fdbb0',
    '2745ae12376a8a4fa67d95eba82a385dda303cae',
    '9018ba7f4f941c3afe3397510a6d61450b2f561e',
    'ec80b7c9adc64dfd61a7ee146bb48cc932740d9f',
    'c05e7015656f00b37446b29ed14a62f26dae112b',
]


def git(*args, raw=False):
    data = subprocess.check_output(['git', *args])
    return data if raw else data.decode().strip()


def checked(ok, message):
    if not ok:
        raise RuntimeError(message)


def blob_sha(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()


def api(path, data=None):
    url = 'https://api.github.com/repos/' + REPO + '/' + path
    request = urllib.request.Request(url, data=None if data is None else json.dumps(data).encode(),
        method='GET' if data is None else 'POST', headers={
            'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
            'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28',
            'Content-Type': 'application/json',
        })
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def commit_identity(line):
    match = re.fullmatch(r'(.+) <([^>]+)> ([0-9]+) \+0000', line)
    checked(match is not None, 'non-UTC or unsupported Git identity')
    name, email, timestamp = match.groups()
    date = datetime.datetime.fromtimestamp(int(timestamp), datetime.timezone.utc).isoformat()
    return dict(name=name, email=email, date=date)


def upload_commit(commit):
    """Upload exact Git objects only; no push, branch, ref or workflow dispatch API."""
    parent = git('rev-parse', commit + '^')
    changes = git('diff', '--no-renames', '--name-only', '-z', parent, commit, raw=True)
    entries = []
    for path in sorted(p.decode() for p in changes.split(b'\0') if p):
        metadata = git('ls-tree', commit, '--', path).split()
        if not metadata:
            entries.append(dict(path=path, mode='100644', type='blob', sha=None))
            continue
        mode, kind, sha = metadata[:3]
        checked(kind == 'blob', 'unexpected changed gitlink/tree: ' + path)
        raw = git('cat-file', 'blob', sha, raw=True)
        reply = api('git/blobs', dict(content=base64.b64encode(raw).decode(), encoding='base64'))
        checked(reply['sha'] == sha == blob_sha(raw), 'uploaded blob differs: ' + path)
        entries.append(dict(path=path, mode=mode, type='blob', sha=sha))
    tree = api('git/trees', dict(base_tree=git('rev-parse', parent + '^{tree}'), tree=entries))
    checked(tree['sha'] == git('rev-parse', commit + '^{tree}'), 'uploaded tree differs')
    headers, message = git('cat-file', 'commit', commit, raw=True).decode().split('\n\n', 1)
    lines = headers.splitlines()
    checked(len([x for x in lines if x.startswith('parent ')]) == 1, 'unexpected merge commit')
    author = commit_identity(next(x[7:] for x in lines if x.startswith('author ')))
    committer = commit_identity(next(x[10:] for x in lines if x.startswith('committer ')))
    reply = api('git/commits', dict(tree=tree['sha'], parents=[parent], message=message,
                                  author=author, committer=committer))
    checked(reply['sha'] == commit, 'API/local commit identity differs: ' + reply['sha'] + ' / ' + commit)
    print('STAGED_IMMUTABLE_OBJECT ' + commit, flush=True)
    return commit


def seal_reviewed_source():
    helper = Path('morphhdl/scripts/check-increment-62-wa08-source-overlay.py')
    manifest = Path('morphhdl/contracts/increment-62-wa08-source-overlay.json')
    old = json.loads(manifest.read_text())
    ns = {'__name__': 'reviewed_overlay'}
    exec(compile(helper.read_text(), str(helper), 'exec'), ns)
    head = git('rev-parse', 'HEAD')
    paths = sorted(p for p in git('diff', '--name-only', '--no-renames', old['base'], head).splitlines()
                   if ns['governed'](p) and p not in [str(helper), str(manifest)])
    records = []
    digest = lambda b: hashlib.sha256(b).hexdigest()
    for path in paths:
        mode, kind, sha = git('ls-tree', head, '--', path).split()[:3]
        checked(kind == 'blob', 'unreviewed non-file source')
        before = git('ls-tree', old['base'], '--', path)
        records.append(dict(path=path, mode=mode,
            before_sha256=digest(git('show', old['base'] + ':' + path, raw=True)) if before else None,
            after_sha256=digest(git('show', head + ':' + path, raw=True))))
    index = {entry['path']: entry for entry in records}
    checked(len(records) == 156, 'reviewed source inventory differs')
    for entry in old['files']:
        checked(entry['path'] in index and index[entry['path']]['before_sha256'] == entry['before_sha256'],
                'predecessor baseline record removed/modified: ' + entry['path'])
    content = helper.read_bytes()
    normalized = ns['normalized_helper'](content)
    value = dict(old, files=records, final_source_commit=head, helper_normalized_sha256=digest(normalized))
    raw = (json.dumps(value, indent=2) + '\n').encode()
    manifest.write_bytes(raw)
    updated = re.sub(rb'^CONTRACT_SHA256 = "[^"]+"$',
                     ('CONTRACT_SHA256 = "' + digest(raw) + '"').encode(), content, count=1, flags=re.M)
    checked(ns['normalized_helper'](updated) == normalized, 'source audit implementation changed')
    helper.write_bytes(updated)
    subprocess.run(['git', 'add', str(helper), str(manifest)], check=True)
    checked(set(git('diff', '--cached', '--name-only').splitlines()) == {str(helper), str(manifest)},
            'unexpected seal source changes')


def main():
    checked(os.environ['GITHUB_REPOSITORY'] == REPO and os.environ['GITHUB_REF'] == 'refs/heads/' + BRANCH,
            'unexpected repository/branch')
    start = git('rev-parse', 'HEAD')
    checked(start == os.environ['GITHUB_SHA'] and not git('status', '--porcelain'), 'wrong or dirty checkout')
    subprocess.run(['git', 'merge-base', '--is-ancestor', BASE, start], check=True)
    for path in git('diff', '--name-only', BASE, start).splitlines():
        checked(path in TRANSIENTS or path.startswith(str(TRANSFER) + '/'), 'unreviewed source moved: ' + path)
    evidence = Path(os.environ['RUNNER_TEMP']) / 'pr188-reviewed-fix-evidence'
    evidence.mkdir(exist_ok=True)
    parts = []
    for number, expected in enumerate(PARTS):
        raw = (TRANSFER / ('part-' + str(number) + '.gz.part')).read_bytes()
        # Correct the one independently identified transport-bit error, not source.
        # Both original and corrected blob hashes, then whole payload hash, are checked.
        if number == 6 and blob_sha(raw) == '8b5da3b81d8f2aafb7ba7682207b432b746e548f':
            checked(len(raw) == 5000 and raw[4916] == 205, 'transport correction preimage differs')
            raw = raw[:4916] + bytes([197]) + raw[4917:]
        checked(blob_sha(raw) == expected, 'transport checksum differs: ' + str(number))
        parts.append(raw)
    compressed = b''.join(parts)
    checked(hashlib.sha256(compressed).hexdigest() == 'eaf608b4903cdad5d0748f6111711d50388290e276517333fd8a8dcf2cc2b8b8',
            'compressed payload checksum differs')
    patch = gzip.decompress(compressed)
    checked(len(patch) == 177158 and hashlib.sha256(patch).hexdigest() ==
            'c763f641d4530d7263ebb772ae7caec4dd2229a06b86e7b320f603fb51d50b7e', 'source patch checksum differs')
    patch_path = evidence / 'reviewed-source.patch'
    patch_path.write_bytes(patch)
    subprocess.run(['git', 'apply', '--index', '--check', str(patch_path)], check=True)
    subprocess.run(['git', 'apply', '--index', str(patch_path)], check=True)
    Path('.github/workflows/independent-parameter-domains.yml').write_bytes(
        (TRANSFER / 'production-workflow.yml').read_bytes())
    subprocess.run(['git', 'add', '.github/workflows/independent-parameter-domains.yml'], check=True)
    subprocess.run(['git', 'rm', '-r', '--ignore-unmatch', *TRANSIENTS, str(TRANSFER)], check=True)
    checked(git('write-tree') == EXPECTED_TREE, 'full reviewed source tree differs')
    subprocess.run(['git', 'diff', '--cached', '--check'], check=True)
    subprocess.run(['git', 'config', 'user.name', 'github-actions[bot]'], check=True)
    subprocess.run(['git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], check=True)
    os.environ['TZ'] = 'UTC'
    subprocess.run(['git', 'commit', '-m', 'fix: separate symbolic HDL publication from structural domain proof',
        '-m', 'Apply the exact reviewed 86a5fc20 source tree. Retain authenticated symbolic identities and branch restrictions without eager Cartesian publication proof. Add guarded legality, signed safe widths, scalar child actuals and the three-tool regression matrix. Remove completed transport files. This source commit is not final-head qualification.'], check=True)
    source = git('rev-parse', 'HEAD')
    upload_commit(source)
    seal_reviewed_source()
    subprocess.run(['git', 'commit', '-m', 'audit: seal the published symbolic-provenance source checkpoint',
        '-m', 'Retain all predecessor baselines and verify 156 exact source, workflow and reproducer paths. Only the reviewed audit manifest and its hash change. Clean exact-final-head CI remains required.'], check=True)
    final = git('rev-parse', 'HEAD')
    with (evidence / 'source-verification.log').open('w') as output:
        commands = [
            ['python3', 'morphhdl/scripts/check-native-source-preservation.py'],
            ['python3', 'morphhdl/scripts/check-native-source-preservation.py', '--self-test'],
            ['python3', 'morphhdl/scripts/check-production-retirement.py'],
            ['python3', 'morphhdl/scripts/check-production-retirement.py', '--self-test'],
            ['python3', 'morphhdl/scripts/check-typed-layering-ir.py'],
            ['python3', 'morphhdl/scripts/check-typed-layering-ir.py', '--self-test'],
            ['python3', 'morphhdl/scripts/check-increment-62-wa08-source-overlay.py'],
            ['python3', 'morphhdl/scripts/test-increment-62-wa08-source-overlay.py'],
            ['python3', '-m', 'unittest', 'discover', '-s', 'repro/independent-parameters', '-p', 'test_*.py', '-v'],
        ]
        for command in commands:
            output.write(repr(command) + '\n'); output.flush()
            subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, check=True)
    checked(not git('status', '--porcelain'), 'source checks modified checkout')
    upload_commit(final)
    remote = api('git/ref/heads/' + BRANCH)['object']['sha']
    checked(remote == start, 'branch moved during staging; refuse publication claim')
    value = dict(status='objects_staged_NOT_branch_published_NOT_final_CI', repository=REPO, branch=BRANCH,
                 starting_head=start, source_head=source, source_tree=EXPECTED_TREE,
                 final_head=final, final_tree=git('rev-parse', 'HEAD^{tree}'),
                 reviewed_paths=156, expected_overlay_mutation_controls=172)
    (evidence / 'publication.json').write_text(json.dumps(value, indent=2) + '\n')
    subprocess.run(['git', 'bundle', 'create', str(evidence / 'source.bundle'), 'HEAD', '^' + BASE], check=True)
    print(json.dumps(value), flush=True)


if __name__ == '__main__':
    main()
