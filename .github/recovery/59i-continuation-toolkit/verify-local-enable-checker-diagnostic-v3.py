#!/usr/bin/env python3
"""Verify a reviewed successor checker over retained cb093 RTL, never qualification."""
import argparse
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile

HERE = Path(__file__).resolve().parent
INPUT_HEAD = 'cb093b5aa235760f2c3149f4327324976aa0495b'
INPUT_ARTIFACT = 10583436619
INPUT_RUN = 35435278904
INPUT_ZIP_SHA = 'a4589bfd9bf2b604828fe1c5ef6a0350823482278b6163488a5d0377a1f60e16'
PREVIOUS_CHECKER_HEAD = '6a858ead678f7ec96e9a421f2b4551bcd62d65d9'


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v4 = load('full_v5_inspection', 'verify-local-enable-diagnostic-v5.py')
old = load('original_cb093_inspection', 'verify-local-enable-diagnostic.py')
require = v4.require


def configure(repo, controller, source, checker_sha):
    """Bind the generic inspector to separately reviewed immutable source."""
    require(re.fullmatch('[0-9a-f]{40}', source) and re.fullmatch('[0-9a-f]{64}', checker_sha),
            'exact reviewed source and checker SHA-256 required')
    config = v4.read_json(controller / 'checker-config.json')
    tree = v4.git(repo, 'rev-parse', source + '^{tree}').decode().strip()
    require(config['source'] == source and config['tree'] == tree and
            config['checker_hashes'][v4.MAIN_CHECKER] == checker_sha, 'reviewed controller identity differs')
    v4.git(repo, 'merge-base', '--is-ancestor', PREVIOUS_CHECKER_HEAD, source)
    delta = v4.git(repo, 'diff', '--name-only', INPUT_HEAD, source).decode().splitlines()
    require(delta == config['source_delta'] and set(delta) == {
        v4.MAIN_CHECKER, '.github/workflows/increment-59i-local-enable-committed-head.yml'},
        'retained RTL source includes a generator/build/other change')
    for path, expected in config['checker_hashes'].items():
        raw = v4.git(repo, 'show', source + ':' + path)
        require(v4.digest(raw) == expected, 'controller checker hash differs from committed bytes')
        if path != v4.MAIN_CHECKER:
            require(raw == v4.git(repo, 'show', INPUT_HEAD + ':' + path), 'supplemental/results checker changed')
    v4.HEAD, v4.TREE = source, tree
    v4.PINNED_CHECKERS = {path: config['checker_hashes'][path]
                          for path in (v4.MAIN_CHECKER, v4.COMBINED_CHECKER)}


def priority_obligation(root, paths, main, config, successful):
    rtl = root / 'hardware-A'
    manifest = v4.read_json(v4.file(rtl, 'manifest.json'))
    main.validate(manifest)
    cases = [c for c in manifest['cases'] if c['id'] == 'sync_high_rising_u5_s7_b3_n5']
    require(len(cases) == 1, 'priority case identity differs')
    case = cases[0]
    candidates = [c for c in manifest['candidates'] if c['profile'] == case['profile']]
    require(len(candidates) == 2, 'priority candidate inventory differs')
    candidate = candidates[0]
    work = root / 'priority-obligation'
    inspected = v4.finite_bit_proof(root, paths, main, case, candidate, 'unsigned', 0, work,
                                    successful=successful)
    if successful:
        receipt = v4.read_json(v4.file(work, 'receipt.json'))
        require(receipt == dict(scope='previous-timeout-only', checker_source=v4.HEAD,
            checker_sha256=config['checker_hashes'][config['main_checker']], emitted_rtl_source=INPUT_HEAD,
            case=case['id'], candidate_index=0, candidate=candidate['module'], field='unsigned', bit=0,
            result='pass-18-steps', rtl_sha256={entry['file']: v4.file_digest(v4.file(rtl, entry['file']))
                for entry in (case, candidate)}, miter_sha256=v4.file_digest(v4.file(work, 'miter.v')),
            script_sha256=v4.file_digest(v4.file(work, 'equivalence.ys')),
            log_sha256=v4.file_digest(v4.file(work, 'formal.log')),
            normalized_netlist_sha256=v4.file_digest(v4.file(work, 'normalized-netlist.json')),
            proof=inspected['partition'],
            proof_files={name: v4.file_digest(v4.file(work, name)) for name in v4.FINITE_FILES},
            diagnostic_only=True, qualification=False), 'priority obligation receipt differs')
        require({p.name for p in work.iterdir() if p.is_file()} == set(v4.FINITE_FILES) | {'receipt.json'},
                'priority proof evidence has missing/extra files')
        require('PRIORITY_OBLIGATION_PASS: COUNT5 candidate 0 unsigned bit 0; full checker still required' in
                v4.file(root, 'priority-execution.log').read_text(), 'priority completion log absent')
    return dict(case=case['id'], candidate=candidate['module'], field='unsigned', bit=0,
                **{key: value for key, value in inspected.items() if key != 'partition'})


def retained_input(root, repo, input_controller, expected_missing):
    archive = v4.file(root, 'retained-cb093b5a-scala-2.13.zip')
    require(v4.file_digest(archive) == INPUT_ZIP_SHA, 'retained input ZIP changed')
    prior = root / 'retained-input/2.13.12'
    with zipfile.ZipFile(archive) as z:
        names = set()
        for info in z.infolist():
            if info.is_dir():
                continue
            v4.relative(info.filename)
            require(info.filename not in names and stat.S_IFMT(info.external_attr >> 16) in (0, stat.S_IFREG),
                    'duplicate or linked retained ZIP entry')
            names.add(info.filename)
            raw = z.read(info)
            actual = v4.file(prior, info.filename)
            require(actual.stat().st_size == info.file_size and v4.digest(raw) == v4.file_digest(actual),
                    'nested retained input differs from downloaded ZIP: ' + info.filename)
        require(names == {p.relative_to(prior).as_posix() for p in prior.rglob('*') if p.is_file()},
                'nested retained input has missing/extra files')
    indexed = old.evidence_inventory(prior, allow_missing=True)
    require(indexed['missing'] == expected_missing and len(indexed['missing']) == 64,
            'upstream missing publication files differ')
    payload = old.read_json(input_controller / 'payload.json')
    expected = old.read_json(input_controller / 'expected-suites.json')
    old.source_identity(prior, repo, payload)
    tests = old.xml_results(prior, '2.13.12', expected, True)
    rtl = old.deterministic_rtl(prior, require_postcheck=False)
    require(len(rtl) == 124, 'retained original HDL inventory differs')
    metadata = v4.read_json(v4.file(root, 'input-artifact-metadata.json'))
    require(metadata['id'] == INPUT_ARTIFACT and metadata['workflow_run']['id'] == INPUT_RUN and
            metadata['name'] == 'increment-59i-local-enable-development-cb093b5a-2.13.12-1' and
            metadata['digest'] == 'sha256:' + INPUT_ZIP_SHA and metadata['expired'] is False,
            'upstream artifact API receipt differs')
    verification = v4.read_json(v4.file(root, 'retained-input-verification.json'))
    require(verification['source_head'] == INPUT_HEAD and verification['evidence_integrity_valid'] is False and
            verification['present_file_hashes_valid'] is True and verification['diagnostic_passed'] is False and
            verification['qualification'] is False and len(verification['lanes']) == 1,
            'retained input verification scope differs')
    lane = verification['lanes'][0]
    require(lane['unit_tests'] == tests and lane['original_rtl'] == rtl and
            lane['missing_inventory_files'] == expected_missing, 'retained input verification contradicts raw evidence')
    return dict(source_head=INPUT_HEAD, scala='2.13.12', tests=tests['cases'], suites=tests['suites'],
                original_rtl_files=124, archive_files=len(names), missing_publication_files=expected_missing,
                publication_evidence_complete=False), rtl


def inspect(archive, digest, destination, repo, controller, mode):
    archive_sha = v4.extract(archive, digest, destination)
    inventory = v4.evidence_inventory(destination)  # Success and failure both require this upload's full coverage.
    payload = v4.read_json(controller / 'payload.json')
    config = v4.read_json(controller / 'checker-config.json')
    require(payload['head'] == v4.HEAD and payload['tree'] == v4.TREE and config['source'] == v4.HEAD and
            config['tree'] == v4.TREE, 'narrow controller source identity differs')
    paths = v4.source_identity(destination, repo, payload,
        source_env='59i-local-enable-checker-source', output_env='59i-local-enable-checker-evidence')
    prior, original_rtl = retained_input(destination, repo, controller / 'input-controller', config['missing_input_files'])
    identity = v4.read_json(v4.file(destination, 'checker-diagnostic-identity.json'))
    require(identity == dict(checker_source=v4.HEAD, checker_tree=v4.TREE,
        checker_hashes=config['checker_hashes'], emitted_rtl_source=INPUT_HEAD, emitted_rtl_scala='2.13.12',
        artifact_id=INPUT_ARTIFACT, artifact_run=INPUT_RUN, artifact_zip_sha256=INPUT_ZIP_SHA,
        original_rtl_files=124, missing_input_publication_files=config['missing_input_files'],
        diagnostic_only=True, current_source_compiled=False, current_source_rtl_generated=False,
        source_sealed=False, qualification=False, full_ci=False, remote_refs_written=False),
        'checker diagnostic identity differs')
    result = v4.read_json(v4.file(destination, 'checker-diagnostic-result.json'))
    for key, value in dict(scope='retained-rtl-checker-diagnostic', checker_source=v4.HEAD, checker_tree=v4.TREE,
        emitted_rtl_source=INPUT_HEAD, source_evidence_errors=[], upstream_publication_evidence_complete=False,
        missing_input_publication_files=config['missing_input_files'], current_source_compiled=False,
        current_source_rtl_generated=False, diagnostic_only=True, source_sealed=False, qualification=False,
        full_ci=False, remote_refs_written=False).items():
        require(result.get(key) == value, 'checker result scope differs: ' + key)
    require(set(result['phases']) == {'priority', 'main', 'combined', 'unchanged'}, 'checker phase inventory differs')
    phase_results = {}
    for phase, path in [('priority', config['main_checker']), ('main', config['main_checker']), ('combined', config['combined_checker']),
                        ('unchanged', config['results_checker'])]:
        if phase == 'main' and result['phases'][phase] is None:
            require(mode == 'failed' and phase_results['priority'] != 0 and
                    not (destination / 'phase-main.json').exists(), 'main may be skipped only after failed priority')
            phase_results[phase] = None
            continue
        receipt = v4.read_json(v4.file(destination, 'phase-' + phase + '.json'))
        require(receipt == result['phases'][phase] and receipt['phase'] == phase and
                receipt['checker_source'] == v4.HEAD and receipt['checker_sha256'] == config['checker_hashes'][path] and
                receipt['emitted_rtl_source'] == INPUT_HEAD and receipt['diagnostic_only'] is True and
                receipt['qualification'] is False and type(receipt['exit_code']) is int,
                'checker phase source/exit receipt differs: ' + phase)
        if mode == 'success':
            require(receipt['exit_code'] == 0, 'checker phase did not pass: ' + phase)
        phase_results[phase] = receipt['exit_code']
    require(result['checker_diagnostic_passed'] == all(code == 0 for code in phase_results.values()),
            'checker completion receipt contradicts phase exits')
    current_rtl = v4.deterministic_rtl(destination, postcheck_log='unchanged-execution.log')
    require(current_rtl == original_rtl, 'reused RTL does not match the original cb093 artifact')
    main = v4.checker(repo, v4.MAIN_CHECKER)
    combined = v4.checker(repo, v4.COMBINED_CHECKER)
    hardware, incomplete = {}, []
    try:
        hardware['priority'] = priority_obligation(destination, paths, main, config, phase_results['priority'] == 0)
    except Exception as error:
        if mode == 'success' or phase_results['priority'] == 0:
            raise
        incomplete.append('priority: ' + str(error))
    for phase, call in [('main', lambda: v4.main_hardware(destination, paths, main, 'main-execution.log')),
                        ('combined', lambda: v4.combined_hardware(destination, paths, combined, 'combined-execution.log'))]:
        try:
            hardware[phase] = call()
        except Exception as error:
            if mode == 'success' or phase_results[phase] == 0:
                raise
            incomplete.append(phase + ': ' + str(error))
    return dict(scope='independent-retained-rtl-checker-inspection', mode=mode,
        archive_sha256=archive_sha, evidence_files=inventory['present_files'], evidence_integrity_valid=True,
        checker_source=v4.HEAD, checker_tree=v4.TREE, emitted_rtl_source=INPUT_HEAD,
        checker_diagnostic_passed=(mode == 'success'), phases=phase_results, prior_input=prior,
        original_rtl_unchanged=True, hardware=hardware, incomplete_hardware=incomplete,
        current_source_compiled=False, current_source_rtl_generated=False, source_sealed=False,
        qualification=False, full_ci=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--github-sha256', required=True)
    parser.add_argument('--extract-root', type=Path, required=True)
    parser.add_argument('--repo-root', type=Path, default=HERE / '59i-dev')
    parser.add_argument('--controller', type=Path, required=True)
    parser.add_argument('--source', required=True, help='independently reviewed exact checker source SHA')
    parser.add_argument('--checker-sha', required=True, help='independently reviewed exact main checker SHA-256')
    parser.add_argument('--mode', choices=('success', 'failed'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output, destination = args.output.resolve(), args.extract_root.resolve()
    require(not output.exists() and destination not in output.parents, 'summary cannot overwrite/alter evidence')
    configure(args.repo_root.resolve(), args.controller.resolve(), args.source, args.checker_sha)
    result = inspect(args.artifact.resolve(), args.github_sha256, destination, args.repo_root.resolve(),
                     args.controller.resolve(), args.mode)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + '\n')
    compact = dict(result, prior_input={k: v for k, v in result['prior_input'].items() if k != 'missing_publication_files'})
    compact['prior_input']['missing_publication_file_count'] = len(result['prior_input']['missing_publication_files'])
    print(json.dumps(compact, indent=2))


if __name__ == '__main__':
    main()
