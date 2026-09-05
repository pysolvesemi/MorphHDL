#!/usr/bin/env python3
"""Fail-closed static contracts for WA-07a; static checks are not RTL proofs."""
from __future__ import annotations

import argparse
import copy
import importlib.util
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
PASS = 'morphhdl-passes/src/main/scala/morphhdl/passes/transform/ConstantOperandSimplificationPass.scala'
PIPELINE = 'morphhdl-passes/src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala'
API = 'morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala'
NATIVE = 'morphhdl-passes/examples/ConstantOperandNativeBridge.scala'
RUN = 'morphhdl-passes/scripts/run-wa07a-regression.sh'
WORKFLOW = '.github/workflows/morphhdl-passes.yml'
VALIDATOR = 'morphhdl-passes/scripts/validate_wire_assignment_equivalence.py'
CLOCK_TEST = 'morphhdl-passes/scripts/test_wire_assignment_clock_model.py'
SCHEDULER_TEST = 'morphhdl-passes/scripts/test_wire_assignment_equivalence.py'
TESTS = 'morphhdl-passes/src/test/scala/morphhdl/passes/transform/'
CONST = 'constant-operand-simplification'
ALL = 'wire-alias-unnamed+wire-alias-named+wire-expression-unnamed+' + CONST
SLOTS = [
    {'activation_item': 'WA-07a', 'candidate': 'morphhdl-passes/build/pass-outputs/' + CONST + '.v', 'pass_id': CONST},
    {'activation_item': 'WA-07a', 'candidate': 'morphhdl-passes/build/pass-outputs/wire-assignment-four-pass.v', 'pass_id': ALL},
]
MARKERS = {
    VALIDATOR: ('multiclock on', 'def prove_comparison_reachable',
                'prove_comparison_reachable(directory, miter_top)',
                'cover(!{reset});', 'reachability-evidence.json',
                'comparison reachability passed without a retained cover trace',
                'proof.get("comparison_reachable") is not True',
                'mutation = run_mutation_control(mutation_case, pass_directory)',
                'sequential_mutation = run_mutation_control'),
    CLOCK_TEST: ('correct-clock-model', 'functional_mutation', 'incorrect-clock-model',
                 'value.replace("multiclock on", "multiclock off")',
                 '"REJECTED_UNREACHABLE"', 'WA07A_CLOCK_MODEL_PASS'),
    SCHEDULER_TEST: ('test_native_artifact_paths_ignore_forked_subproject_working_directory',
                     'test_relative_native_artifact_path_mutation_is_rejected',
                     'test_pass_status_without_reachability_cannot_publish_success',
                     'test_unreachable_comparison_stops_before_equivalence_proof',
                     'test_reachability_status_without_cover_trace_is_rejected'),
    PASS: ('ConstantOperandSimplificationPass', 'CanonicalIrPassAdapter.bindFixture(design)',
           'CanonicalIrPassAdapter.bindFixture(output)', 'cannotProduceZ', 'effectiveWidth',
           'allOnesAt', 'sameShape', 'isTruthProducer', 'DriverKind.Continuous',
           '!observable.keep', '!observable.dontTouch', 'rewrite(value, contextWidth, path, record)',
           'resultShape.width', 'if (rewrites.isEmpty) design else output'),
    API: ('ConstantOperandSimplification', 'historicalWireAssignmentPasses :+ ConstantOperandSimplification',
          'simplifiedExpressions', 'def changedCount', 'val enabled: Boolean'),
    PIPELINE: ('ConstantOperandSimplificationPass.run', 'progressMeasure', 'accumulate',
               'SimplifiedExpression', 'WireAliasPipelineResult(design, PassExecutionStatus.Failed'),
    NATIVE: ('codec.capture(assignment.source', 'codec.design(input)',
             'ConstantOperandSimplificationPass.run(snapshot)',
             'codec.decode(result.output.modules.head.drivers.head.value)',
             'assignment.source = rewritten', 'case _ => None',
             'isEmptyOfTag', 'preserved(target)', 'target.hasOnlyOneStatement',
             'assignment.parentScope eq target.rootScopeStatement',
             'actual_rhs_capture_writeback', 'executionRounds :+= executed',
             'WireAliasPassConfiguration(enabled = true)', 'progress = aliases.sum + constant.changedCount > 0'),
    RUN: ('root="${repo_root}/morphhdl-passes/build"',
          'test -s "${new_reference}/parameterized_stream_fifo.v"',
          'python3 morphhdl-passes/scripts/test_wire_assignment_clock_model.py',
          'bash morphhdl-passes/scripts/run-wa07-regression.sh',
          'ParameterizedStreamFifoConstantPassWitness reference',
          'ParameterizedStreamFifoConstantPassWitness constant',
          'ParameterizedStreamFifoConstantPassWitness all',
          'cmp -s "${reference}"', 'cmp -s "${out}/${stem}.v" "${repeat}/${stem}.v"',
          'actual_rhs_capture_writeback', 'iverilog -g2001', 'verilator --lint-only --language 1364-2001',
          'synth -top ParameterizedStreamFifo; check -assert', 'WA07A_NATIVE_PASS',
          '1:1', '64:8'),
    WORKFLOW: ('check-wa07a-constant-pass.py --self-test', 'check-wa07a-constant-pass.py',
               'run-wa07a-regression.sh', '--prove-pending WA-07a', '--check-determinism',
               'needs: [boundary, contracts]', 'wa07a-rule-oracle', 'actions/upload-artifact@v4'),
    TESTS+'ConstantOperandSimplificationPassSpec.scala': (
        'comparison AND OR and XOR constant operands simplify in both orders',
        'Boolean metadata does not authorize Z-changing raw-reference identities',
        'numeric one is not an all-ones mask', 'effective enclosing evaluation width',
        'parameterized zero masks preserve WIDTH', 'procedural drivers are unchanged',
        'invalid canonical input fails closed'),
    TESTS+'ConstantOperandFourStateSpec.scala': (
        'actual transformed trees preserve all four states', 'raw Z identity mutation',
        'two-state formal rule proof passes', 'patterns=1024', 'before_$i !== after_$i',
        'literal', 'yosys', 'proof did fail'),
    TESTS+'ConstantOperandFixedPointSpec.scala': ('XOR-with-ones closes newly exposed double inversion',
                                                'preserves input declaration order'),
}
# These are source-policy checks, never generated-HDL candidate discovery.
FORBIDDEN = re.compile(r'\b(?:StreamFifo(?:CC)?|ParameterizedStreamFifo|logicalName|spinal\.|scala\.io|java\.io|java\.nio\.(?:file|channels)|parseVerilog|verilogText|generatedVerilog)\b|_zz_|\.sourceLocation[^\n]*\.path')
NATIVE_FORBIDDEN = re.compile(r'\.(?:definitionName|getName|getPartialName)\b|_zz_|\b(?:parseVerilog|verilogText|generatedVerilog)\b')


def manifest_failures(value):
    try:
        shared = value['shared_witness']
        slots = [x for x in shared['future_pass_outputs'] if x['activation_item'] == 'WA-07a']
        if slots != SLOTS:
            return ['WA07A-SLOTS: both exact new proof legs are mandatory']
        if shared['parameter_domains'] != {'WIDTH': list(range(1, 65)), 'DEPTH': list(range(1, 9))}:
            return ['WA07A-DOMAIN: all 512 WIDTH/DEPTH bindings are mandatory']
        if shared['common_reference_capture'] != 'common-pre-pass/reference.v':
            return ['WA07A-BASELINE: reference must precede the entire passes phase']
    except (KeyError, TypeError):
        return ['WA07A-MANIFEST: incomplete proof contract']
    return []


def text_failures(path, text):
    errors = [f'WA07A-CONTRACT: {path}: missing {marker!r}' for marker in MARKERS[path] if marker not in text]
    if path in (PASS, PIPELINE) and FORBIDDEN.search(text):
        errors.append(f'WA07A-GENERICITY: {path}')
    if path == RUN:
        cover = text.find('python3 morphhdl-passes/scripts/test_wire_assignment_clock_model.py')
        historical = text.find('bash morphhdl-passes/scripts/run-wa07-regression.sh')
        if cover < 0 or historical <= cover:
            errors.append('WA07A-CLOCK-GATE: clock mutation controls must precede native regression')
    if path == NATIVE:
        begin = text.find('final class ConstantOperandNativePhase')
        end = text.find('object ParameterizedStreamFifoConstantPassWitness')
        if begin < 0 or end <= begin or NATIVE_FORBIDDEN.search(text[begin:end]):
            errors.append('WA07A-NATIVE-GENERICITY: native decision boundary is unproven')
    return errors


def check(root):
    errors = []
    for path in MARKERS:
        try:
            errors += text_failures(path, (root/path).read_text())
        except OSError as error:
            errors.append(f'WA07A-MISSING: {path}: {error}')
    manifest = root/'morphhdl-passes/tests/formal/wire_assignment_ir/manifest.json'
    errors += manifest_failures(json.loads(manifest.read_text()))
    roadmap = (root/'morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md').read_text()
    entries = re.findall(r'^- \[([ xX])\] \*\*(WA-[0-9]+[a-z]?)\s+—(.*?)(?=^- \[[ xX]\] \*\*WA-|\Z)', roadmap, re.M | re.S)
    items = {name: (flag.lower() == 'x', body) for flag, name, body in entries}
    if len(items) != len(entries):
        errors.append('WA07A-ROADMAP: duplicate increment')
    if 'WA-07a' not in items or not items.get('WA-07', (False,))[0]:
        errors.append('WA07A-DEPENDENCY: completed WA-07 and separate WA-07a are required')
    else:
        done, body = items['WA-07a']
        status = 'COMPLETED' if done else 'IN PROGRESS'
        successor = 'READY' if done else 'BLOCKED'
        if f'**Status:** `{status}`' not in body or f'**Status:** `{successor}`' not in items.get('WA-08', (False, ''))[1]:
            errors.append('WA07A-STATUS: checkbox/status/WA-08 dependency disagree')
        if items.get('WA-08', (False,))[0]:
            errors.append('WA07A-SCOPE: this increment must not complete WA-08')
    registry = json.loads((root/'morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json').read_text())['files']
    for path in list(MARKERS) + ['morphhdl-passes/scripts/check-wa07a-constant-pass.py',
                                 'morphhdl-passes/tests/formal/wire_assignment_ir/constant_native_tb.v']:
        if path not in registry:
            errors.append(f'WA07A-SIGNATURE: {path}')
    return errors


def self_test(root):
    for path in MARKERS:
        text = (root/path).read_text()
        assert not text_failures(path, text), text_failures(path, text)
        for marker in MARKERS[path]:
            assert text_failures(path, text.replace(marker, 'MUTATED')), (path, marker)
    text = (root/PASS).read_text()
    for mutation in ('StreamFifo', 'module.logicalName', 'spinal.core.X', 'java.nio.file.Files', 'parseVerilog', '_zz_1'):
        assert any('GENERICITY' in failure for failure in text_failures(PASS, text+'\n'+mutation)), mutation
    native = (root/NATIVE).read_text()
    assert text_failures(NATIVE, native.replace('codec.design(input)', 'component.getName'))
    valid = json.loads((root/'morphhdl-passes/tests/formal/wire_assignment_ir/manifest.json').read_text())
    assert not manifest_failures(valid)
    for key, val in (('parameter_domains', {'WIDTH': [8], 'DEPTH': [5]}),
                     ('common_reference_capture', 'previous-pass/reference.v'),
                     ('future_pass_outputs', SLOTS[:1])):
        mutant = copy.deepcopy(valid)
        mutant['shared_witness'][key] = val
        assert manifest_failures(mutant), key
    print('WA-07a constant-pass contract self-tests passed.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--repo-root', type=Path, default=ROOT)
    args = parser.parse_args()
    if args.self_test:
        self_test(args.repo_root)
        return 0
    errors = check(args.repo_root)
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print('WA-07a constant-pass contract passed.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
