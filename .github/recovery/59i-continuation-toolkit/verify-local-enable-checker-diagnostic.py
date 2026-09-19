#!/usr/bin/env python3
"""Verify exact 6a858ead checker results over retained cb093b5a RTL, never qualification."""
import argparse
import importlib.util
import json
from pathlib import Path
import stat
import zipfile

HERE = Path(__file__).resolve().parent
INPUT_HEAD = 'cb093b5aa235760f2c3149f4327324976aa0495b'
INPUT_ARTIFACT = 10583436619
INPUT_RUN = 35435278904
INPUT_ZIP_SHA = 'a4589bfd9bf2b604828fe1c5ef6a0350823482278b6163488a5d0377a1f60e16'


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v4 = load('full_v4_inspection', 'verify-local-enable-diagnostic-v4.py')
old = load('original_cb093_inspection', 'verify-local-enable-diagnostic.py')
require = v4.require


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
    require(set(result['phases']) == {'main', 'combined', 'unchanged'}, 'checker phase inventory differs')
    phase_results = {}
    for phase, path in [('main', config['main_checker']), ('combined', config['combined_checker']),
                        ('unchanged', config['results_checker'])]:
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
    for phase, call in [('main', lambda: v4.main_hardware(destination, paths, main, 'main-execution.log')),
                        ('combined', lambda: v4.combined_hardware(destination, paths, combined, 'combined-execution.log'))]:
        try:
            hardware[phase] = call()
        except Exception as error:
            if mode == 'success':
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
    parser.add_argument('--controller', type=Path, default=HERE / 'controller-checker-diagnostic-6a858ead')
    parser.add_argument('--mode', choices=('success', 'failed'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output, destination = args.output.resolve(), args.extract_root.resolve()
    require(not output.exists() and destination not in output.parents, 'summary cannot overwrite/alter evidence')
    result = inspect(args.artifact.resolve(), args.github_sha256, destination, args.repo_root.resolve(),
                     args.controller.resolve(), args.mode)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + '\n')
    compact = dict(result, prior_input={k: v for k, v in result['prior_input'].items() if k != 'missing_publication_files'})
    compact['prior_input']['missing_publication_file_count'] = len(result['prior_input']['missing_publication_files'])
    print(json.dumps(compact, indent=2))


if __name__ == '__main__':
    main()
