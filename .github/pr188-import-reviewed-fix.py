"""One-shot, exact-tree transport of the reviewed PR188 workflow correction."""
from pathlib import Path
import argparse
import gzip
import hashlib
import json
import os
import re
import subprocess
import urllib.parse
import urllib.request
import urllib.error

REPO = 'pysolvesemi/MorphHDL'
BRANCH = 'agent/independent-parameter-domain-composition'
PARENT = '46a2ceb5dc56a6fd039ee3c4e303c6bbe4e03ff6'
SOURCE_TREE = '5a9c2b112f96ef495124a10934e318f16dde9455'
PATCH = '.github/pr188-reviewed-fix.patch.gz'
SCRIPT = '.github/pr188-import-reviewed-fix.py'
WORKFLOW = '.github/workflows/pr188-import-reviewed-fix.yml'
MANIFEST = 'morphhdl/contracts/increment-62-wa08-source-overlay.json'
HELPER = 'morphhdl/scripts/check-increment-62-wa08-source-overlay.py'
REVIEW = {
 '.github/workflows/independent-parameter-domains.yml': '5af52172ca0541c24421e3394f9ae9bf3945285ced39b755177297f4aa4c2010',
 'frontend/src/test/scala/morphhdl/frontend/AnalyzedFrontendBooleanTests.scala': 'e0aab5013dae6af8c52423d6fee1f2c1fd55208a264ac0990b63be66c0254d0e',
 'frontend/src/test/scala/morphhdl/frontend/HdlBoolTests.scala': '5d04d8379a5b58d4a68834885b92734e94f6a841164bb2ed537caebddf6678f1',
 'morphhdl/contracts/increment-54-typed-layering-ir.contract': '7494dc0371f75a24fbf00d09024af132e9d20d440a1eb35bd1a0dc3009478d81',
 'morphhdl/scripts/check-typed-layering-ir.py': 'f7361e79b8f5b5bcfbc4c476e6fe0f27aa96ea7a928ba908ab6728c5b7578aa8',
 'repro/independent-parameters/frontend-regression-suites.json': '149e816083334d57b54d02380f460d8fb2901903c8d4420b5109a3fd267e47d6',
 'repro/independent-parameters/qualify.py': '49224afa94154a27429b39c435fe5d00653df81d5d4a9b76bd4f91893b286887',
 'repro/independent-parameters/test_layering.py': 'a60ad381b634ed12910251a437c68733fc458713f4fcad54d3d73d1936c6323d',
 'repro/independent-parameters/test_qualify.py': '9b64bed3eb1dfba7218069709f580f89be31f7a0308f8e710907e5e58b4f1fed',
}

def require(ok, detail):
    if not ok: raise RuntimeError(detail)

def run(*args):
    subprocess.run(args, check=True)

def git(*args):
    return subprocess.check_output(['git', *args]).decode().strip()

def raw_git(*args):
    return subprocess.check_output(['git', *args])

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def normalized(raw):
    return re.sub(rb'^CONTRACT_SHA256 = "[^"]+"$', b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, count=1, flags=re.M)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--local-only', action='store_true', help='exercise import/checks without API calls or push')
    args = parser.parse_args()
    require(os.environ['GITHUB_REPOSITORY'] == REPO and os.environ['GITHUB_REF'] == 'refs/heads/' + BRANCH, 'wrong repository/ref')
    start = git('rev-parse', 'HEAD')
    require(start == os.environ['GITHUB_SHA'] and git('rev-parse', 'HEAD^') == PARENT, 'source moved')
    require(not git('status', '--porcelain'), 'dirty source checkout')
    evidence = Path(os.environ['RUNNER_TEMP']) / 'pr188-reviewed-fix-evidence'
    evidence.mkdir()
    data = Path(PATCH).read_bytes()
    require(hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == 'ec13beb2fc1c051cb5858841dc471d6eebaabc0c', 'transfer blob differs')
    patch = gzip.decompress(data)
    require(len(patch) == 20802 and digest(patch) == 'b3c2ac838c1f1a71987e7fc6b6a2fa657294a07e766459d38792c7052e8ece98', 'source patch differs')
    patch_path = evidence / 'reviewed.patch'
    patch_path.write_bytes(patch)
    run('git', 'apply', '--index', '--check', str(patch_path))
    run('git', 'apply', '--index', str(patch_path))
    run('git', 'rm', PATCH, SCRIPT, WORKFLOW,
        '.github/independent-root-inventory.patch',
        '.github/workflows/independent-parameter-root-inventory-repair.yml',
        '.github/workflows/independent-parameter-cancel-pending.yml')
    require(git('write-tree') == SOURCE_TREE, 'reviewed source tree differs')
    run('git', 'diff', '--cached', '--check')
    run('git', 'config', 'user.name', 'github-actions[bot]')
    run('git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
    run('git', 'commit', '-m', 'fix(ci): close standalone audit coverage and qualify frontend ingress',
        '-m', 'Apply the exact reviewed workflow/root-inventory and frontend diagnostic correction. Add positive native composition tests and complete frontend JUnit coverage without weakening compiler safety. Remove temporary transport and cancellation facilities. Audit resealing and final-head CI remain required.')
    source = git('rev-parse', 'HEAD')
    old_raw = Path(MANIFEST).read_bytes()
    require(digest(old_raw) == 'e2388d03c0d57124fb74bb0b65c9a51f08b504a8b0f8e14d13f7ec41c35bb0d1', 'previous manifest differs')
    old = json.loads(old_raw)
    require(old['base'] == '2ebaa2ef5561eab35aa0ba9caced5c5a314d59f6' and len(old['files']) == 132, 'previous scope differs')
    previous_helper = Path(HELPER).read_bytes()
    require(digest(normalized(previous_helper)) == old['helper_normalized_sha256'] == '58c4b49f7475c7791c3dd52470c94d1f9d044b0a9434583e4449913f1f2b9f3b', 'checker logic differs')
    updated = json.loads(json.dumps(old))
    entries = {entry['path']: entry for entry in updated['files']}
    for path, expected in REVIEW.items():
        raw = raw_git('show', source + ':' + path)
        require(digest(raw) == expected, 'unreviewed successor bytes: ' + path)
        before = digest(raw_git('show', old['base'] + ':' + path)) if git('ls-tree', old['base'], '--', path) else None
        if path in entries:
            require(entries[path]['before_sha256'] == before, 'baseline identity changed')
        else:
            entries[path] = {'path': path, 'mode': '100644', 'before_sha256': before}
        entries[path]['after_sha256'] = expected
    for entry in old['files']:
        if entry['path'] not in REVIEW: require(entries[entry['path']] == entry, 'predecessor record changed')
    require(len(entries) == 139, 'reviewed inventory differs')
    updated['files'] = [entries[path] for path in sorted(entries)]
    updated['final_source_commit'] = source
    new_raw = (json.dumps(updated, indent=2) + '\n').encode()
    Path(MANIFEST).write_bytes(new_raw)
    new_helper = re.sub(rb'^CONTRACT_SHA256 = "[^"]+"$', ('CONTRACT_SHA256 = "' + digest(new_raw) + '"').encode(), previous_helper, count=1, flags=re.M)
    require(normalized(new_helper) == normalized(previous_helper), 'checker logic must remain unchanged')
    Path(HELPER).write_bytes(new_helper)
    run('git', 'add', MANIFEST, HELPER)
    run('git', 'diff', '--cached', '--check')
    run('git', 'commit', '-m', 'chore(ci): seal reviewed PR188 workflow and ingress corrections',
        '-m', 'Preserve all 132 predecessor records and all immutable baseline identities. Bind the nine reviewed successor files to the actual committed source anchor; retain 139 records and unchanged verifier logic. Final-head build, simulation and inherited CI remain required.')
    checks = [
        ['python3', 'morphhdl/scripts/check-native-source-preservation.py'],
        ['python3', 'morphhdl/scripts/check-production-retirement.py'],
        ['python3', 'morphhdl/scripts/check-typed-layering-ir.py'],
        ['python3', 'morphhdl/scripts/check-typed-layering-ir.py', '--self-test'],
        ['python3', HELPER],
        ['python3', HELPER, '--self-test'],
        ['python3', '-m', 'unittest', 'discover', '-s', 'repro/independent-parameters', '-p', 'test_*.py', '-v'],
    ]
    with (evidence / 'verification.log').open('w') as log:
        for command in checks:
            log.write(repr(command) + '\n'); log.flush()
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    require(not git('status', '--porcelain'), 'post-verification source changed')
    (evidence / 'publication.json').write_text(json.dumps({'source_anchor': source, 'source_tree': SOURCE_TREE, 'head': git('rev-parse', 'HEAD'), 'tree': git('rev-parse', 'HEAD^{tree}'), 'reviewed_successors': REVIEW, 'local_only': args.local_only}, indent=2) + '\n')
    run('git', 'bundle', 'create', str(evidence / 'repair.bundle'), 'HEAD', '^' + PARENT)
    if args.local_only:
        print('LOCAL_IMPORT_AND_SOURCE_CHECKS_PASS ' + git('rev-parse', 'HEAD'))
        return
    # Do not leave CI queued on this obsolete transport-only commit. The user
    # explicitly requested pending-run cancellation; final-head checks are NOT
    # canceled and will be triggered separately after publication.
    api_base = 'https://api.github.com/repos/' + REPO
    def api(path, method='GET'):
        request = urllib.request.Request(api_base + path, method=method, headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'})
        with urllib.request.urlopen(request, timeout=60) as response: content = response.read()
        return json.loads(content) if content else {}
    own = int(os.environ['GITHUB_RUN_ID'])
    runs = []
    for page in range(1, 11):
        batch = api('/actions/runs?' + urllib.parse.urlencode({'head_sha':start,'per_page':100,'page':page}))['workflow_runs']
        runs.extend(batch)
        if len(batch) < 100: break
    else: raise RuntimeError('unexpected pagination overflow')
    cancelled = []
    for job in runs:
        if job['id'] == own or job['head_sha'] != start or job['head_branch'] != BRANCH or job['status'] == 'completed': continue
        try: api('/actions/runs/' + str(job['id']) + '/cancel', 'POST')
        except urllib.error.HTTPError as error:
            if error.code != 409: raise
        cancelled.append(job['id'])
    (evidence / 'obsolete-transport-cancellation.json').write_text(json.dumps(cancelled) + '\n')
    remote = git('ls-remote', '--exit-code', 'origin', 'refs/heads/' + BRANCH).split()[0]
    require(remote == start, 'branch moved; refusing to overwrite concurrent work')
    run('git', 'push', 'origin', 'HEAD:refs/heads/' + BRANCH)
    print('PUBLISHED_REVIEWED_SOURCE_AND_SEAL ' + git('rev-parse', 'HEAD'))

if __name__ == '__main__': main()
