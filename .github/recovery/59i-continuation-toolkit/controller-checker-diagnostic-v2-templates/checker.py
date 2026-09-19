#!/usr/bin/env python3
"""Retained-RTL checker experiment: never compiles Scala or qualifies source."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

from prepare import HEAD, TREE, git, paths, require

CONFIG = None
HERE = Path(__file__).resolve().parent
INPUT_HEAD = 'cb093b5aa235760f2c3149f4327324976aa0495b'
ARTIFACT_ID = 10583436619
ARTIFACT_RUN = 35435278904
ZIP_SHA256 = 'a4589bfd9bf2b604828fe1c5ef6a0350823482278b6163488a5d0377a1f60e16'
API = 'https://api.github.com/repos/pysolvesemi/MorphHDL/actions/artifacts/' + str(ARTIFACT_ID)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def source(root):
    require(HEAD == CONFIG['source'] and TREE == CONFIG['tree'], 'checker source/controller differs')
    require(git(root, 'rev-parse', 'HEAD').decode().strip() == HEAD, 'checker checkout head differs')
    require(git(root, 'rev-parse', 'HEAD^{tree}').decode().strip() == TREE, 'checker checkout tree differs')
    for path, expected in CONFIG['checker_hashes'].items():
        file = root / path
        require(file.is_file() and not file.is_symlink() and digest(file) == expected,
                'exact checker source hash differs: ' + path)
    actual_delta = git(root, 'diff', '--name-only', INPUT_HEAD, HEAD).decode().splitlines()
    require(actual_delta == CONFIG['source_delta'], 'checker source delta differs')
    require(not any(path.endswith(('.scala', '.sbt', '.sc', '.java', '.v', '.vhd', '.vhdl')) or
                    path.startswith(('project/', 'morphhdl/src/', 'core/src/', 'frontend/src/', 'lib/src/'))
                    for path in actual_delta), 'retained-RTL experiment includes generator/build changes')
    git(root, 'diff', '--exit-code')
    git(root, 'diff', '--cached', '--exit-code')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def download(out):
    token = os.environ['GH_TOKEN']
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
               'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': '59i-retained-rtl-diagnostic'}
    with urllib.request.urlopen(urllib.request.Request(API, headers=headers), timeout=120) as response:
        metadata = json.load(response)
    require(metadata['id'] == ARTIFACT_ID and metadata['workflow_run']['id'] == ARTIFACT_RUN and
            metadata['name'] == 'increment-59i-local-enable-development-cb093b5a-2.13.12-1' and
            metadata['digest'] == 'sha256:' + ZIP_SHA256 and metadata['expired'] is False,
            'retained artifact API identity differs')
    write(out / 'input-artifact-metadata.json', metadata)
    opener = urllib.request.build_opener(NoRedirect())
    try:
        response = opener.open(urllib.request.Request(API + '/zip', headers=headers), timeout=120)
    except urllib.error.HTTPError as error:
        require(error.code in (301, 302, 303, 307, 308), 'artifact download did not return an archive/redirect')
        location = error.headers['Location']
        uri = urllib.parse.urlsplit(location)
        require(uri.scheme == 'https' and uri.hostname and not uri.username and not uri.password and
                any(uri.hostname.endswith(suffix) for suffix in
                    ('.blob.core.windows.net', '.amazonaws.com', '.githubusercontent.com')),
                'artifact redirect is not trusted HTTPS storage')
        # The GitHub token is never forwarded to the signed storage URL.
        response = urllib.request.urlopen(urllib.request.Request(location), timeout=180)
    archive = out / 'retained-cb093b5a-scala-2.13.zip'
    with response, archive.open('xb') as stream:
        shutil.copyfileobj(response, stream)
    require(digest(archive) == ZIP_SHA256, 'retained ZIP digest mismatch')
    return archive


def command(args, root, log):
    with log.open('w') as stream:
        result = subprocess.run(args, cwd=root, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        print('\n'.join(log.read_text(errors='replace').splitlines()[-160:]), flush=True)
    return result.returncode


def inputs(root, out):
    source(root)
    archive = download(out)
    summary_path = out / 'retained-input-verification.json'
    code = command(['python3', '-B', str(HERE / 'verify-local-enable-diagnostic.py'),
        '--mode', 'failed', '--artifact', '2.13.12', str(archive), ZIP_SHA256,
        '--extract-root', str(out / 'retained-input'), '--repo-root', str(root),
        '--controller', str(HERE / 'input-controller'), '--output', str(summary_path)],
        root, out / 'retained-input-verification.log')
    require(code == 0, 'retained input evidence verification failed')
    verified = json.loads(summary_path.read_text())
    lane = verified['lanes'][0]
    require(verified['diagnostic_passed'] is False and verified['qualification'] is False and
            verified['present_file_hashes_valid'] is True and lane['source_head'] == INPUT_HEAD and
            lane['unit_tests']['passed'] is True and lane['unit_tests']['cases'] == 138 and
            lane['unit_tests']['suites'] == 10 and len(lane['original_rtl']) == 124,
            'retained input source/test/RTL evidence differs')
    require(lane['missing_inventory_files'] == CONFIG['missing_input_files'],
            'missing upstream publication metadata differs from the inspected failed artifact')
    prior = out / 'retained-input/2.13.12'
    for name in ('hardware-A', 'hardware-B'):
        shutil.copytree(prior / name, out / name)
    shutil.copyfile(prior / 'rtl-inventory.json', out / 'rtl-inventory.json')
    require(command(['python3', '-B', str(root / CONFIG['results_checker']), 'determinism',
                     '--output', str(out)], root, out / 'determinism.log') == 0,
            'retained original A/B RTL no longer matches')
    write(out / 'checker-diagnostic-identity.json', dict(
        checker_source=HEAD, checker_tree=TREE, checker_hashes=CONFIG['checker_hashes'],
        emitted_rtl_source=INPUT_HEAD, emitted_rtl_scala='2.13.12',
        artifact_id=ARTIFACT_ID, artifact_run=ARTIFACT_RUN, artifact_zip_sha256=ZIP_SHA256,
        original_rtl_files=124, missing_input_publication_files=CONFIG['missing_input_files'],
        diagnostic_only=True, current_source_compiled=False, current_source_rtl_generated=False,
        source_sealed=False, qualification=False, full_ci=False, remote_refs_written=False))
    print('RETAINED RTL AUTHENTICATED: checker experiment only; upstream publication metadata remains incomplete')


def priority_worker(root, out):
    """Retry the exact previous timeout before spending time on the full suite."""
    source(root)
    path = root / CONFIG['main_checker']
    spec = importlib.util.spec_from_file_location('exact_main_checker', path)
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    rtl = out / 'hardware-A'
    manifest = json.loads((rtl / 'manifest.json').read_text())
    checker.validate(manifest)
    cases = [c for c in manifest['cases'] if c['id'] == 'sync_high_rising_u5_s7_b3_n5']
    require(len(cases) == 1, 'previously timed-out case identity differs')
    case = cases[0]
    candidates = [c for c in manifest['candidates'] if c['profile'] == case['profile']]
    require(len(candidates) == 2, 'previously timed-out candidate inventory differs')
    candidate = candidates[0]
    work = out / 'priority-obligation'
    work.mkdir(parents=True, exist_ok=True)
    receipt = work / 'receipt.json'
    receipt.unlink(missing_ok=True)
    top = work / 'miter.v'
    top.write_text(checker.miter(case, [candidate], ('unsigned', 0)))
    sources = [checker.checked_file(rtl, entry['file']) for entry in (case, candidate)] + [top]
    proof = work / 'equivalence.ys'
    netlist = work / 'normalized-netlist.json'
    command = ('sat -seq 18 -set-def-inputs -set-at 1 reset 1 '
               '-set-at 1 enable 1 -prove bad 0 -prove-skip 1 -timeout 120 -verify\n')
    proof.write_text(checker.formal_setup(sources) + 'write_json ' + checker.quoted(netlist) + '\n' + command)
    log = checker.run(['yosys', '-Q', '-T', '-s', str(proof)], work / 'formal.log')
    require(log.count(checker.SAT_PASS) == 1 and checker.SAT_FAIL not in log,
            'previously timed-out output-bit obligation did not pass')
    write(receipt, dict(scope='previous-timeout-only', checker_source=HEAD,
        checker_sha256=CONFIG['checker_hashes'][CONFIG['main_checker']],
        emitted_rtl_source=INPUT_HEAD, case=case['id'], candidate_index=0,
        candidate=candidate['module'], field='unsigned', bit=0, result='pass-18-steps',
        rtl_sha256={entry['file']: digest(rtl / entry['file']) for entry in (case, candidate)},
        miter_sha256=digest(top), script_sha256=digest(proof), log_sha256=digest(work / 'formal.log'),
        normalized_netlist_sha256=digest(netlist),
        diagnostic_only=True, qualification=False))
    print('PRIORITY_OBLIGATION_PASS: COUNT5 candidate 0 unsigned bit 0; full checker still required')


def execute(phase, root, out):
    source(root)
    require((out / 'checker-diagnostic-identity.json').is_file(), 'input validation did not complete')
    if phase == 'priority':
        path = CONFIG['main_checker']
        command_args = ['python3', '-B', str(HERE / 'checker.py'), 'priority-worker']
    elif phase == 'main':
        path = CONFIG['main_checker']
        args = ['--artifacts', str(out / 'hardware-A'), '--output', str(out / 'hardware-checks')]
    elif phase == 'combined':
        path = CONFIG['combined_checker']
        args = ['--artifacts', str(out / 'hardware-A/combined'), '--output', str(out / 'hardware-A/combined/checks')]
    else:
        path = CONFIG['results_checker']
        args = ['unchanged', '--output', str(out)]
    if phase != 'priority':
        command_args = ['python3', '-B', str(root / path), *args]
    code = command(command_args, root, out / (phase + '-execution.log'))
    write(out / ('phase-' + phase + '.json'), dict(phase=phase, checker_source=HEAD,
        checker_sha256=CONFIG['checker_hashes'][path], emitted_rtl_source=INPUT_HEAD,
        exit_code=code, diagnostic_only=True, qualification=False))
    source(root)
    print('checker phase=' + phase + ' exit_code=' + str(code), flush=True)
    return code


def finish(root, out):
    problems = []
    try:
        source(root)
        after = git(root, 'ls-files', '--stage')
        (out / 'tracked-after.txt').write_bytes(after)
        require(after == (out / 'tracked-before.txt').read_bytes(), 'checker source index changed')
    except Exception as error:
        problems.append(str(error))
    phases = {}
    for name in ('priority', 'main', 'combined', 'unchanged'):
        path = out / ('phase-' + name + '.json')
        phases[name] = json.loads(path.read_text()) if path.is_file() else None
    passed = not problems and all(value and value['exit_code'] == 0 for value in phases.values())
    result = dict(scope='retained-rtl-checker-diagnostic', checker_source=HEAD, checker_tree=TREE,
        emitted_rtl_source=INPUT_HEAD, checker_diagnostic_passed=passed, phases=phases,
        source_evidence_errors=problems, upstream_publication_evidence_complete=False,
        missing_input_publication_files=CONFIG['missing_input_files'],
        current_source_compiled=False, current_source_rtl_generated=False, diagnostic_only=True,
        source_sealed=False, qualification=False, full_ci=False, remote_refs_written=False)
    write(out / 'checker-diagnostic-result.json', result)
    write(out / 'evidence-files.json', [dict(path=p.relative_to(out).as_posix(), bytes=p.stat().st_size,
        sha256=digest(p)) for p in sorted(out.rglob('*')) if p.is_file() and not p.is_symlink() and
        p not in (out / 'evidence-files.json', out / 'retention.log')])
    print(json.dumps({k: v for k, v in result.items() if k != 'missing_input_publication_files'}, indent=2))
    return 1 if problems else 0


if __name__ == '__main__':
    require(len(sys.argv) == 2 and sys.argv[1] in ('input', 'priority', 'priority-worker', 'main', 'combined', 'unchanged', 'finish'),
            'usage: checker.py input|priority|priority-worker|main|combined|unchanged|finish')
    _, root, out = paths()
    out.mkdir(parents=True, exist_ok=True)
    phase = sys.argv[1]
    if phase == 'input':
        inputs(root, out)
    elif phase == 'priority-worker':
        priority_worker(root, out)
    else:
        raise SystemExit(finish(root, out) if phase == 'finish' else execute(phase, root, out))
