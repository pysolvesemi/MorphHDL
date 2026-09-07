#!/usr/bin/env python3
"""Mutation-tested WA-07b source/evidence contracts; never a substitute for proofs."""
from __future__ import annotations

import argparse
import copy
import importlib.util
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
BASE = 'morphhdl-passes/'
PASS = BASE + 'src/main/scala/morphhdl/passes/transform/BooleanTernarySimplificationPass.scala'
API = BASE + 'src/main/scala/morphhdl/passes/api/PassContracts.scala'
PIPELINE = BASE + 'src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala'
TEST = BASE + 'src/test/scala/morphhdl/passes/transform/BooleanTernarySimplificationPassSpec.scala'
ORACLE = BASE + 'src/test/scala/morphhdl/passes/transform/BooleanTernaryFourStateSpec.scala'
NATIVE = BASE + 'examples/BooleanTernaryNativeBridge.scala'
CODEC = BASE + 'examples/ConstantOperandNativeBridge.scala'
RUN = BASE + 'scripts/run-wa07b-regression.sh'
TB = BASE + 'tests/formal/wire_assignment_ir/boolean_ternary_native_tb.v'
WORKFLOW = '.github/workflows/morphhdl-passes.yml'
MANIFEST = BASE + 'tests/formal/wire_assignment_ir/manifest.json'
REGISTRY = BASE + 'tests/formal_model/wire_assignment_ir/expected-signatures.json'
ROADMAP = BASE + 'morphhdl-ir-wire-assignment-passes-todo.md'
ID = 'boolean-ternary-simplification'
ALL = 'wire-alias-unnamed+wire-alias-named+wire-expression-unnamed+constant-operand-simplification+' + ID
SLOTS = [
    {'activation_item': 'WA-07b', 'candidate': BASE + 'build/pass-outputs/' + ID + '.v', 'pass_id': ID},
    {'activation_item': 'WA-07b', 'candidate': BASE + 'build/pass-outputs/wire-assignment-five-pass.v', 'pass_id': ALL},
]
MARKERS = {
    PASS: ('object BooleanTernarySimplificationPass', 'CanonicalIrPassAdapter.bindFixture(design)',
           'CanonicalIrPassAdapter.bindFixture(output)', 'if (rewrites.isEmpty) design else output',
           'DriverKind.Continuous', 'DriverCoverage.FullObject', '!observable.keep',
           '!observable.dontTouch', '!observable.probe', '!observable.preserve',
           '!observable.publicExport', '!observable.blackBoxBoundary', '!observable.hierarchyBoundary',
           'target.attributes.isEmpty && driver.attributes.isEmpty',
           'case RtlExpr.Literal(n, 1, false)', 'isTruthProducer', 'not(not(value))',
           'RtlExpr.Unary(RtlUnaryOperator.LogicalNot, value)',
           'boolean-ternary-positive', 'boolean-ternary-inverse',
           'child(condition, "condition")', 'child(yes, "yes")', 'child(no, "no")',
           'child(left, "left")', 'child(right, "right")', 'child(index, "index")',
           'case RtlExpr.Concat(values)', 'case RtlExpr.PartSelect(value, offset, width)',
           'case RtlExpr.Resize(value, width, signedness)', 'case RtlExpr.Cast(value, signedness)',
           'WA07B-INVALID-IR'),
    API: ('historicalWireAssignmentPasses :+ ConstantOperandSimplification',
          'historicalConstantOperandPasses :+ BooleanTernarySimplification',
          'if (enabled) PassId.allWireAssignmentPasses else Vector.empty'),
    PIPELINE: ('case PassId.BooleanTernarySimplification =>', 'BooleanTernarySimplificationPass.run(design)',
               'SimplifiedExpression', 'historicalConstantPassId', 'progressMeasure',
               'enabled.contains(PassId.BooleanTernarySimplification)', 'WA07B-PIPELINE-NONDECREASING',
               'WireAliasPipelineResult(design, PassExecutionStatus.Failed'),
    TEST: ('both polarities remove', 'raw Bool Z and multi-bit conditions',
           'widened signed and symbolic contexts', 'every supported expression child',
           'nonmatching parents', '128', 'exposed by recursive branch rewrites',
           'wider signed or parameter branches are retained',
           'preservation metadata attributes and procedural drivers',
           'renamed unrelated components', 'invalid inputs roll back',
           'historical four-stage selection excludes'),
    ORACLE: ('BooleanTernarySimplificationPass.run(before)',
             'WireAliasPassPipeline.run(before, WireAliasPassConfiguration(enabled = true))',
             'before_$i !== after_$i', 'pattern < 16384',
             'raw-z', 'polarity', 'vector-complement', 'widened-complement',
             'iverilog', '-g2001', 'yosys', 'sat -verify -prove ok 1',
             'proof did fail', 'WA07B_FOUR_STATE_PASS'),
    NATIVE: ('bridge.eligible(assignment)', 'new bridge.BooleanCodec',
             'codec.capture(assignment.source, "rhs")', 'codec.design(input)',
             'BooleanTernarySimplificationPass.run(snapshot)',
             'codec.decode(result.output.modules.head.drivers.head.value)',
             'assignment.source = rewritten', 'executionRounds :+= executed',
             'WireAliasPassConfiguration(enabled = true).enabledPasses',
             'ternary_no_op', 'actual_rhs_capture_writeback', 'procedural_receiver_rewrites',
             'ParameterizedStreamFifoBooleanTernaryWitness', 'BooleanTernaryGenericNativeWitness'),
    CODEC: ('private[examples] def eligible', 'private[examples] final class BooleanCodec',
            'target.isEmptyOfTag', '!preserved(target)', 'target.hasOnlyOneStatement',
            'case _ => None', 'PassId.historicalConstantOperandPasses: _*'),
    RUN: ('bash morphhdl-passes/scripts/run-wa07a-regression.sh',
          'ParameterizedStreamFifoBooleanTernaryWitness reference',
          'ParameterizedStreamFifoBooleanTernaryWitness ternary',
          'ParameterizedStreamFifoBooleanTernaryWitness all',
          'cmp -s "${reference}" "${new_reference}/parameterized_stream_fifo.v"',
          'cmp -s "${out}/${stem}.v" "${repeat}/${stem}.v"',
          'cmp -s "${out}/${stem}-report.json" "${repeat}/${stem}-report.json"',
          "assert report['ternary_simplified_assignment_count'] > 0",
          "'boolean-ternary-positive', 'boolean-ternary-inverse'",
          'actual_rhs_capture_writeback', 'iverilog -g2001',
          'verilator --lint-only --language 1364-2001', 'sat -verify -prove ok 1',
          'synth -top ParameterizedStreamFifo; check -assert', 'WA03_SIM_PASS',
          '1:1', '64:8', 'WA07B_NATIVE_PASS', 'WA07B_ACTUAL_NATIVE_'),
    TB: ('reference_value !== ternary_value', 'reference_value !== all_value',
         "four_state = 1'bx", "four_state = 1'bz", 'pattern < 16',
         'BooleanTernaryNativeMiter', 'BooleanTernaryNativeTb', 'WA07B_NATIVE_FAIL', 'WA07B_NATIVE_PASS'),
    WORKFLOW: ('check-wa07b-ternary-pass.py --self-test', 'check-wa07b-ternary-pass.py',
               'run-wa07b-regression.sh --after-wa07a', '--prove-pending WA-07b',
               '--check-determinism', '--formal-shard-count 16', '--shard-count 16',
               'wa07b-rule-oracle', 'needs: [boundary, contracts]',
               'needs: [native_generation, formal_shards]'),
}


def inherited_guard(root):
    path = root / BASE / 'scripts/check-wa07a-constant-pass.py'
    spec = importlib.util.spec_from_file_location('wa07b_historical_contract', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def text_failures(path, text, inherited):
    errors = [f'WA07B-CONTRACT: {path}: missing {marker!r}' for marker in MARKERS[path] if marker not in text]
    if path in (PASS, PIPELINE) and inherited.FORBIDDEN.search(text):
        errors.append(f'WA07B-GENERICITY: {path}')
    if path == NATIVE:
        boundary = text.find('object ParameterizedStreamFifoBooleanTernaryWitness')
        if boundary < 0 or inherited.NATIVE_FORBIDDEN.search(text[:boundary]):
            errors.append('WA07B-NATIVE-GENERICITY: native phase inspects names or emitted text')
    if path == WORKFLOW and text.count('--prove-pending WA-07b') < 4:
        errors.append('WA07B-PROOF-WIRING: capture, restore verification, shard proof and aggregate must all include WA-07b')
    return errors


def manifest_failures(value):
    try:
        shared = value['shared_witness']
        if [x for x in shared['future_pass_outputs'] if x['activation_item'] == 'WA-07b'] != SLOTS:
            return ['WA07B-SLOTS: require standalone and five-stage legs']
        if shared['parameter_domains'] != {'WIDTH': list(range(1, 65)), 'DEPTH': list(range(1, 9))}:
            return ['WA07B-DOMAIN: require all 512 admitted bindings']
        if shared['common_reference_capture'] != 'common-pre-pass/reference.v':
            return ['WA07B-BASELINE: require the common pre-ALL-passes reference']
    except (KeyError, TypeError):
        return ['WA07B-MANIFEST: incomplete proof contract']
    return []


def check(root, inherited):
    errors = []
    for path in MARKERS:
        try:
            errors += text_failures(path, (root / path).read_text(), inherited)
        except OSError as error:
            errors.append(f'WA07B-MISSING: {path}: {error}')
    errors += manifest_failures(json.loads((root / MANIFEST).read_text()))
    roadmap = (root / ROADMAP).read_text()
    errors += inherited.roadmap_failures(roadmap)
    entry = re.search(r'^- \[([ xX])\] \*\*WA-07b\s+—(.*?)(?=^- \[[ xX]\] \*\*WA-|\Z)', roadmap, re.M | re.S)
    if entry is None or f'**Status:** `{"COMPLETED" if entry[1].lower() == "x" else "IN PROGRESS"}`' not in entry[2]:
        errors.append('WA07B-STATUS: implemented branch must remain IN PROGRESS until qualification completes')
    registry = json.loads((root / REGISTRY).read_text())['files']
    for path in list(MARKERS) + [BASE + 'scripts/check-wa07b-ternary-pass.py']:
        if path not in registry:
            errors.append(f'WA07B-SIGNATURE: {path}')
    return errors


def self_test(root, inherited):
    for path, markers in MARKERS.items():
        text = (root / path).read_text()
        assert not text_failures(path, text, inherited), text_failures(path, text, inherited)
        for marker in markers:
            assert text_failures(path, text.replace(marker, 'MUTATED'), inherited), (path, marker)
    text = (root / PASS).read_text()
    for mutation in ('StreamFifo', 'module.logicalName', 'spinal.core.X', 'java.nio.file.Files', 'parseVerilog', '_zz_1'):
        assert any('GENERICITY' in x for x in text_failures(PASS, text + '\n' + mutation, inherited)), mutation
    native = (root / NATIVE).read_text()
    assert text_failures(NATIVE, native.replace('codec.design(input)', 'component.getName'), inherited)
    valid = json.loads((root / MANIFEST).read_text())
    assert not manifest_failures(valid)
    for key, value in (('parameter_domains', {'WIDTH': [8], 'DEPTH': [5]}),
                       ('common_reference_capture', 'previous-pass/reference.v'),
                       ('future_pass_outputs', SLOTS[:1])):
        mutant = copy.deepcopy(valid)
        mutant['shared_witness'][key] = value
        assert manifest_failures(mutant), key
    print('WA-07b ternary-pass contract self-tests passed.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo-root', type=Path, default=ROOT)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    inherited = inherited_guard(args.repo_root)
    if args.self_test:
        self_test(args.repo_root, inherited)
        return 0
    errors = check(args.repo_root, inherited)
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print('WA-07b ternary-pass contract passed.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
