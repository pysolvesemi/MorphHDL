#!/usr/bin/env python3
"""Inspect actual committed-head local-enable evidence; never reclassify a diagnostic.

Requires independently fetched GitHub run/jobs metadata and three original ZIPs
with API digests. Hardware inspection reuses the authenticated finite-proof
inspector, without editing any extracted evidence or running HDL tools.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import types
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
HEAD = '268cdc63ca5b12177e01b8715688a6e001dff06f'
TREE = '9c78547d73fdf08a8c9fbb7f1d836ba4af2c1ad4'
SOURCE = '8c634b150c778607b280dd6831095dd6e474a4c1'
BRANCH = 'agent/increment-59i-combined-reduction-closure'
WORKFLOW = '.github/workflows/increment-59i-local-enable-committed-head.yml'
INSPECTOR_SHA256 = '2176aa1200a37ce68d344caa39e6a510959f0181f77460707afbc11fc650e4f5'
MAIN_SHA256 = 'e522d80a10040cc59e9c484e56573b15b85ff8d4041b4fe62d4720cf0b6f3a40'


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def load_inspector():
    p = HERE / 'verify-local-enable-diagnostic-v5.py'
    raw = p.read_bytes()
    require(not p.is_symlink() and hashlib.sha256(raw).hexdigest() == INSPECTOR_SHA256,
            'unreviewed evidence inspector')
    module = types.ModuleType('committed_hardware_inspector')
    module.__file__ = str(p)
    exec(compile(raw, str(p), 'exec'), module.__dict__)
    # Only hardware functions are reused. Diagnostic identity, XML/scope,
    # inspection and CLI routines are never invoked for committed evidence.
    module.HEAD, module.TREE = HEAD, TREE
    module.PINNED_CHECKERS[module.MAIN_CHECKER] = MAIN_SHA256
    return module


def metadata(run, jobs):
    require(run['head_sha'] == HEAD and run['head_branch'] == BRANCH and
        run['path'] == WORKFLOW and run['event'] == 'workflow_dispatch' and
        run['status'] == 'completed' and run['conclusion'] == 'success',
        'actual committed-head run has not passed')
    expected = {'Exact committed source and review',
        'Committed local-enable Scala 2.12.18', 'Committed local-enable Scala 2.13.12',
        'Committed local-enable cross-Scala identity'}
    require(jobs['total_count'] == len(jobs['jobs']) == 4 and
        {j['name'] for j in jobs['jobs']} == expected, 'committed job inventory differs')
    require(all(j['run_id'] == run['id'] and j['head_sha'] == HEAD and
        j['run_attempt'] == run['run_attempt'] and j['status'] == 'completed' and
        j['conclusion'] == 'success' for j in jobs['jobs']), 'required job/attempt did not pass')


def exact_index(v, repo):
    rows = []
    for row in v.git(repo, 'ls-tree', '-r', '--full-tree', HEAD).splitlines():
        meta, path = row.split(b'\t', 1)
        mode, kind, sha = meta.split()
        require(kind in (b'blob', b'commit'), 'unexpected tree entry')
        rows.append(mode + b' ' + sha + b' 0\t' + path + b'\n')
    return b''.join(rows)


def identity(v, root):
    require(v.file(root, 'head.txt').read_text().strip() == HEAD and
        v.file(root, 'tree.txt').read_text().strip() == TREE, 'artifact source identity differs')


def tests(v, root, expected):
    found = {}
    for path in sorted((root / 'test-reports').glob('*.xml')):
        doc = ET.parse(v.file(root, path.relative_to(root).as_posix())).getroot()
        suite = doc.get('name')
        require(doc.tag == 'testsuite' and suite in expected and suite not in found and
            path.name == 'TEST-' + suite + '.xml', 'unexpected or duplicate XML suite')
        cases = doc.findall('testcase')
        names = [c.get('name') for c in cases]
        require(all(c.get('classname') == suite for c in cases) and
            Counter(names) == Counter(expected[suite]) and
            int(doc.get('tests', '-1')) == len(cases), 'testcase identities/counters differ')
        require(all(doc.get(k) == '0' for k in ('failures', 'errors', 'skipped')) and
            not any(doc.findall('.//' + k) for k in ('failure', 'error', 'skipped')),
            'failed, errored or skipped XML testcase')
        found[suite] = names
    require(set(found) == set(expected) and sum(map(len, found.values())) == 138,
            'missing required tests')
    result = v.read_json(v.file(root, 'test-results.json'))
    require(result == dict(scope='committed-local-enable-tests', successful=True,
        suites=10, tests=138, errors=[], identities=found), 'test receipt differs from actual XML')
    require(v.read_json(v.file(root, 'report-inventory.log')) == result,
            'report inventory log differs')
    require(v.file(root, 'test-summary.txt').read_text() ==
        'tests=138\nfailures=0\nerrors=0\nskipped=0\n', 'test summary differs')
    log = v.file(root, 'tests.log').read_text(errors='replace')
    require('All tests passed' in log and '*** FAILED ***' not in log,
            'test execution did not pass')
    return found


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo-root', type=Path, required=True)
    p.add_argument('--run-json', type=Path, required=True)
    p.add_argument('--jobs-json', type=Path, required=True)
    p.add_argument('--source-artifact', nargs=2, metavar=('ZIP', 'GITHUB_SHA256'), required=True)
    p.add_argument('--artifact', nargs=3, action='append', metavar=('SCALA', 'ZIP', 'GITHUB_SHA256'), required=True)
    p.add_argument('--extract-root', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    v = load_inspector()
    run, jobs = v.read_json(args.run_json), v.read_json(args.jobs_json)
    metadata(run, jobs)
    require(len(args.artifact) == 2 and {a[0] for a in args.artifact} == {'2.12.18', '2.13.12'},
            'both distinct Scala artifacts are mandatory')
    repo, output, extraction = args.repo_root.resolve(), args.output.resolve(), args.extract_root.resolve()
    require(not output.exists() and extraction not in output.parents, 'unsafe/existing result destination')
    require(v.git(repo, 'rev-parse', HEAD + '^{tree}').decode().strip() == TREE and
        v.git(repo, 'rev-parse', HEAD + '^').decode().strip() == SOURCE, 'unexpected committed seal')
    raw = v.git(repo, 'show', HEAD + ':morphhdl/contracts/increment-59i-regression-inventory.json')
    catalog = json.loads(raw)['projects']['morphhdl']
    suite_module = types.ModuleType('committed_results')
    suite_raw = v.git(repo, 'show', HEAD + ':morphhdl/scripts/check-increment-59i-local-enable-results.py')
    exec(compile(suite_raw, 'committed-results', 'exec'), suite_module.__dict__)
    expected = {s: catalog[s] for s in suite_module.SUITES}
    require(len(expected) == 10 and sum(map(len, expected.values())) == 138, 'source suite inventory differs')
    source = extraction / 'source'
    source_hash = v.extract(Path(args.source_artifact[0]), args.source_artifact[1], source)
    identity(v, source)
    require(v.file(source, 'status.txt').read_bytes() == b'', 'source review checkout was dirty')
    source_hashes = {}
    for line in v.file(source, 'source-sha256.txt').read_text().splitlines():
        sha, name = line.split('  ', 1)
        v.relative(name)
        require(name not in source_hashes and re.fullmatch('[0-9a-f]{64}', sha),
                'invalid or duplicate source hash')
        source_hashes[name] = sha
        require(hashlib.sha256(v.git(repo, 'show', HEAD + ':' + name)).hexdigest() == sha,
                'reviewed source digest differs: ' + name)
    require(set(source_hashes) == {
        'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala',
        'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionClosedGraph.scala',
        'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala',
        'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala',
        'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableTests.scala',
        'morphhdl/contracts/increment-59i-local-enable-review.json',
        'morphhdl/scripts/check-increment-59i-local-enable-source-review.py',
    }, 'source hash inventory differs')
    review = v.file(source, 'pr190-source.log').read_text()
    require(review.startswith('59I_PR190_CURRENT_SOURCE_PASS ') and
        json.loads(review.split(' ', 1)[1])['head'] == HEAD, 'missing current source review')
    for name in ('local-enable-review.log', 'local-enable-review-tests.log', 'successor-tests.log',
        'pr190-integration-tests.log', 'pr190-sync-tests.log', 'sequential-review-tests.log',
        'results-checker-tests.log', 'native-source.log', 'native-overlay.log'):
        v.file(source, name)  # unittest writes to retained GitHub job stderr.
    index = exact_index(v, repo)
    main_checker, combined_checker = v.checker(repo, v.MAIN_CHECKER), v.checker(repo, v.COMBINED_CHECKER)
    lanes = []
    for scala, archive, sha in sorted(args.artifact):
        root = extraction / scala
        zip_sha = v.extract(Path(archive), sha, root)
        identity(v, root)
        require(v.file(root, 'tracked-before.txt').read_bytes() == index ==
            v.file(root, 'tracked-after.txt').read_bytes(), 'compiled index differs from committed source')
        require(v.file(root, 'tmp-path.txt').read_text() == '/tmp/59i-' + scala + '\n',
                'short SBT path receipt differs')
        found = tests(v, root, expected)
        rtl = v.deterministic_rtl(root)
        require(len(rtl) == 124, 'original RTL inventory differs')
        paths = {'output': '/__w/MorphHDL/MorphHDL/target/increment-59i-local-enable-committed-' + scala}
        hardware = v.main_hardware(root, paths, main_checker)
        combined = v.combined_hardware(root, paths, combined_checker)
        hashes = {f.relative_to(root).as_posix(): v.file_digest(f) for f in sorted(root.rglob('*')) if f.is_file()}
        lanes.append(dict(scala=scala, zip_sha256=zip_sha, tests=found, original_rtl=rtl,
            hardware=hardware, combined=combined, retained_files=hashes))
    require(lanes[0]['tests'] == lanes[1]['tests'] and
        lanes[0]['original_rtl'] == lanes[1]['original_rtl'], 'cross-Scala identities differ')
    result = dict(scope='independent-committed-local-enable-inspection', head=HEAD, tree=TREE,
        source=SOURCE, run_id=run['id'], attempt=run['run_attempt'], source_zip_sha256=source_hash,
        local_enable_qualification=True, full_ci=False, unbounded_induction=False, lanes=lanes)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: val for k, val in result.items() if k != 'lanes'} | {
        'lanes': [dict(scala=l['scala'], tests=138, suites=10, original_rtl=124,
            retained_files=len(l['retained_files']), hardware=l['hardware'], combined=l['combined']) for l in lanes]}, indent=2))


if __name__ == '__main__':
    main()
