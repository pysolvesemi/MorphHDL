#!/usr/bin/env python3
"""Build a readonly retained-RTL checker controller; never publish or dispatch."""
import argparse
import ast
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil

HERE = Path(__file__).resolve().parent
INPUT_HEAD = 'cb093b5aa235760f2c3149f4327324976aa0495b'
MAIN = 'morphhdl/scripts/check-increment-59i-composite-local-enable.py'
COMBINED = 'morphhdl/scripts/check-increment-59i-local-enable-combined.py'
RESULTS = 'morphhdl/scripts/check-increment-59i-local-enable-results.py'
WORKFLOW = '.github/workflows/increment-59i-local-enable-committed-head.yml'
FROZEN_COMBINED_SHA = '0bba1edf5fbf070c55f943dcc28f38b433756d696d0d723a79df73ca3594909a'


def load_builder():
    spec = importlib.util.spec_from_file_location('development_builder', HERE / 'build-local-enable-diagnostic.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo-root', type=Path, required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--checker-sha', required=True, help='independently reviewed expected main-checker SHA-256')
    parser.add_argument('--resume-notes', type=Path, required=True, help='reviewed continuation instructions for hourly monitoring')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    base = load_builder()
    root, output = args.repo_root.resolve(), args.output.resolve()
    require, git = base.require, base.git
    require(not output.exists(), 'narrow controller output already exists')
    require(args.resume_notes.is_file() and args.resume_notes.read_text().strip(), 'missing reviewed continuation instructions')
    delta = git(root, 'diff', '--name-only', INPUT_HEAD, args.source).decode().splitlines()
    require(set(delta) == {MAIN, WORKFLOW}, 'narrow checker source must change exactly main checker and upload workflow')
    checker_hashes = {p: hashlib.sha256(git(root, 'show', args.source + ':' + p)).hexdigest()
                      for p in (MAIN, COMBINED, RESULTS)}
    require(checker_hashes[COMBINED] == FROZEN_COMBINED_SHA, 'supplemental checker changed')
    for path in (COMBINED, RESULTS):
        require(git(root, 'show', args.source + ':' + path) == git(root, 'show', INPUT_HEAD + ':' + path),
                'retained checker unexpectedly changed: ' + path)
    if args.checker_sha:
        require(re.fullmatch('[0-9a-f]{64}', args.checker_sha) and checker_hashes[MAIN] == args.checker_sha,
                'supplied main checker digest differs')
    base.build(root, args.source, output, HERE / 'controller-development-v3/templates')
    summary = json.loads((output / 'build-summary.json').read_text())
    metadata = json.loads((HERE / 'evidence/cb093-terminal-failed-verification.json').read_text())
    lane = next(row for row in metadata['lanes'] if row['scala'] == '2.13.12')
    require(lane['zip_sha256'] == 'a4589bfd9bf2b604828fe1c5ef6a0350823482278b6163488a5d0377a1f60e16' and
            lane['present_file_hashes_valid'] is True and len(lane['missing_inventory_files']) == 64,
            'prior artifact inspection identity differs')
    config = dict(source=args.source, tree=summary['tree'], source_delta=delta,
                  main_checker=MAIN, combined_checker=COMBINED, results_checker=RESULTS,
                  checker_hashes=checker_hashes, missing_input_files=lane['missing_inventory_files'])
    templates = HERE / 'controller-checker-diagnostic-v3-templates'
    checker = (templates / 'checker.py').read_text()
    require(checker.count('CONFIG = None\n') == 1, 'checker config marker differs')
    checker = checker.replace('CONFIG = None\n', 'CONFIG = ' + repr(config) + '\n')
    ast.parse(checker)
    (output / 'checker.py').write_text(checker)
    shutil.copyfile(HERE / 'verify-local-enable-diagnostic.py', output / 'verify-local-enable-diagnostic.py')
    (output / 'input-controller').mkdir()
    for name in ('payload.json', 'expected-suites.json'):
        shutil.copyfile(HERE / 'controller-development-v3' / name, output / 'input-controller' / name)
    workflow = (templates / 'probe.yml').read_text().replace('NEW_SOURCE', args.source[:8])
    (output / 'probe.yml').write_text(workflow)
    parts = json.loads((output / 'payload.json').read_text())['parts']
    payload = json.loads((output / 'payload.json').read_text())
    resume = f'''# Increment 59i targeted checker diagnostic continuation

Repository: `pysolvesemi/MorphHDL`; PR: #177.
Recovery controller branch: `recovery/increment-59i-history-20260914`.
Workflow: `.github/workflows/increment-59i-local-enable-checker-diagnostic.yml`.
Use the latest workflow run matching the controller commit that contains this file; the run ID is intentionally not written by another triggering push.

Exact checker source: `{args.source}`.
Exact checker source tree: `{summary['tree']}`.
Exact main checker SHA-256: `{checker_hashes[MAIN]}`.
Preserved development bundle SHA-256: `{payload['bundle_sha256']}`.
Compressed development bundle SHA-256: `{payload['compressed_sha256']}`.
Preserved development patch SHA-256: `{payload['patch_sha256']}`.
Source history contains {len(payload['continuations'])} preserved commits beyond qualified predecessor `{payload['base']}`.

This is an unsealed, retained-RTL diagnostic. It executes checkers from the exact source above against original Scala 2.13 RTL from `{INPUT_HEAD}`.
It does not compile the current source, regenerate RTL, qualify a committed head, complete the TODO, or qualify full CI.
Input artifact: 10583436619 from run 35435278904, ZIP SHA-256 `{lane['zip_sha256']}`.
All 124 original HDL files and the earlier 138 test identities were verified; 64 upstream publication metadata files were missing, so that earlier artifact remains incomplete.
The prior checker run 35443564906 timed out on COUNT5, candidate 0, unsigned bit 0 after bit-level normalization. Its supplemental checker and unchanged-RTL check passed; the complete main phase was skipped after the priority failure.

Execution order: authenticate source and retained input; retry precisely that failed 18-step bit obligation; run the complete main checker only after it passes; always attempt supplemental and unchanged checks after valid input, including when priority or main fails.
A successful diagnostic requires all four phases, all 96 main cases/192 comparisons, all 1,024 original formal bit obligations plus 16 normalized-baseline obligations, both main emitted-RTL mutation counterexamples, and all six supplemental cases/24 comparisons plus its emitted-RTL mutation.
The proof keeps independent arbitrary initial state, native enabled reset at step 1, and unconstrained data/reset/global enable thereafter. The 18-step bound and 120-second SAT timeout are unchanged.
The priority phase uses exactly the same positive-proof helper as the complete main checker. It retains the original normalized netlist, the strengthened netlist, the actual RTL state correspondence, extraction/proof scripts and logs, and a finite-proof receipt. State equalities are additional proof obligations: original cells, input constraints and arbitrary initial state remain intact. The single priority result never substitutes for the complete main checker.

The requested monitoring cadence is once per hour after targeted CI starts. Inspect run and job outcomes read-only; while active, do not dispatch a duplicate or modify this controller merely to record a run ID. On terminal status, download and verify the ZIP digest, all retained file hashes, exact source identity, every required proof/script/log, mutations, and unchanged original RTL before taking the next authorized step.

## Authorized continuation

{args.resume_notes.read_text().strip()}
'''
    (output / 'RESUME.md').write_text(resume)
    publish = ['probe.yml', 'prepare.py', 'checker.py', 'verify-local-enable-diagnostic.py', 'RESUME.md',
               'payload.json', 'checker-config.json', 'build-summary.json',
               'input-controller/payload.json', 'input-controller/expected-suites.json'] + parts
    summary.update(scope='retained-rtl-checker-diagnostic', checker_hashes=checker_hashes,
        proof_helper='prove_bit_equivalence',
        priority_evidence=['miter.v', 'state-extraction.ys', 'state-extraction.log',
                          'normalized-netlist.json', 'strengthened-netlist.json',
                          'state-correspondence.json', 'equivalence.ys', 'formal.log', 'finite-proof.json'],
        emitted_rtl_source=INPUT_HEAD, emitted_rtl_scala='2.13.12',
        input_artifact_id=10583436619, input_artifact_run=35435278904,
        input_artifact_sha256=lane['zip_sha256'], missing_input_publication_files=64,
        current_source_compiled=False, current_source_rtl_generated=False,
        qualification=False, full_ci=False, source_sealed=False,
        publish_files=publish, phases=['priority', 'main', 'combined', 'unchanged'],
        priority_obligation=dict(case='sync_high_rising_u5_s7_b3_n5', candidate_index=0,
                                 field='unsigned', bit=0, previous_run=35443564906),
        remote_workflow='.github/workflows/increment-59i-local-enable-checker-diagnostic.yml',
        remote_directory='.github/recovery/59i-local-enable-checker-diagnostic')
    (output / 'build-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    (output / 'checker-config.json').write_text(json.dumps(config, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
