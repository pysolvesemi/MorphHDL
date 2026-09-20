#!/usr/bin/env python3
"""Add CDC-WIRE reports to the unchanged inherited regression inventory.

The caller authenticates current source before using this adapter. Every
current testcase is checked against that source; only copied additional cases
are projected out for the frozen predecessor catalog. Original XML is retained
and independently compared with the final complete inventory by the caller.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BASE = 'bbae646ba43e6189c69feb308f8decb9b677b15f'
EMITTER = 'spinal.core.internals.VerilogEmitterExpressionInliningTests'
COPY = 'spinal.core.internals.NativePureExpressionCopyTests'
CDC = 'morphhdl.CdcWireCleanupRegressionTests'
NAMESPACE = 'morphhdl.CdcWireEmitterNamespaceTests'
SEQUENTIAL = 'morphhdl.SequentialWireNativeTests'
EMITTER_SOURCE = 'core/src/test/scala/spinal/core/internals/VerilogEmitterExpressionInliningTests.scala'
COPY_SOURCE = 'core/src/test/scala/spinal/core/internals/NativePureExpressionCopyTests.scala'
CDC_SOURCE = 'morphhdl/src/test/scala/morphhdl/CdcWireCleanupRegressionTests.scala'
NAMESPACE_SOURCE = 'morphhdl/src/test/scala/morphhdl/CdcWireEmitterNamespaceTests.scala'
SEQUENTIAL_SOURCE = 'morphhdl/src/test/scala/morphhdl/SequentialWireNativeTests.scala'
WRITER_SOURCE = 'morphhdl/src/test/scala/morphhdl/examples/CdcWireCleanupArtifactWriter.scala'
EMITTER_RENAMES = {
    'a truncating root retains its sizing boundary':
        'a truncating root uses a function with the exact source evaluation width',
    'slice operands retain emitter-created boundaries':
        'fixed slice operands inline while retaining exact select syntax',
    'a shared expression node retains one emitter-created carrier':
        'a shared expression node inlines when every receiving width is proven',
    'conditional register update preserves its final select carrier and state':
        'conditional register update preserves exact truncation and state without a select carrier',
}
EMITTER_ADDITIONS = frozenset((
    'shift then truncate remains an exact function result inside a comparison',
    'a shared node with an unproven receiving context retains its carrier everywhere',
    'aggregate shared expression growth retains a carrier even below each root budget',
))
COPY_ADDITIONS = frozenset((
    'fixed-width shift copies preserve logical operator class, amount and source geometry',
))
CDC_LITERAL_CASES = frozenset((
    'generated-looking explicit names and keep/debug/CDC barriers survive recursive cleanup',
    'shared unnamed expressions above the duplication budget retain their actual identity',
    'shared compiler expression nodes inline while independently protected expression nodes remain',
    'simplified compiler alias nodes reach every receiver while explicit and protected aliases remain',
))
CDC_FIXTURES = ('CdcSliceCompareRepro', 'CdcGrayChainRepro',
                'CdcWhenPredicateRepro', 'CdcWireControls')
SEQUENTIAL_RENAMES = {
    'named source and arithmetic slice bases survive while direct register slices simplify':
        'named sources survive and arithmetic register slices preserve their exact boundary',
}


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError('CDC-WIRE-REGRESSIONS: ' + detail)


def literal_cases(source: str) -> set[str]:
    values = re.findall(r'\btest\("([^"\n]+)"\)', source)
    require(values and len(values) == len(set(values)), 'missing/duplicate source test declarations')
    return set(values)


def source_suites(root: Path) -> dict:
    def inherited(path):
        return subprocess.check_output(['git', 'show', BASE + ':' + path], cwd=root, text=True)
    emitter_before = literal_cases(inherited(EMITTER_SOURCE))
    require(len(emitter_before) == 25 and set(EMITTER_RENAMES) <= emitter_before,
            'emitter predecessor or reviewed rename identities changed')
    emitter = {EMITTER_RENAMES.get(name, name) for name in emitter_before} | EMITTER_ADDITIONS
    require(literal_cases((root/EMITTER_SOURCE).read_text()) == emitter and
            len(emitter) == 25 + len(EMITTER_ADDITIONS),
            'missing/unreviewed current emitter testcase')
    copy_before = literal_cases(inherited(COPY_SOURCE))
    copied = copy_before | COPY_ADDITIONS
    require(len(copy_before) == 2 and len(copied) == 3 and
            literal_cases((root/COPY_SOURCE).read_text()) == copied,
            'missing/unreviewed native-copy testcase')
    cdc_source = (root/CDC_SOURCE).read_text()
    require(literal_cases(cdc_source) == CDC_LITERAL_CASES,
            'missing/unreviewed CDC literal testcase')
    template = 'test(s"$name converges in one production invocation with deterministic emission")'
    require(cdc_source.count(template) == 1, 'changed CDC parameterized testcase template')
    writer = (root/WRITER_SOURCE).read_text()
    names = re.search(r'val names: Vector\[String\] = Vector\((.*?)\)', writer, re.S)
    require(names is not None and tuple(re.findall(r'"([^"\n]+)"', names.group(1))) == CDC_FIXTURES,
            'changed CDC fixed-point fixture inventory')
    cdc = CDC_LITERAL_CASES | {
        name + ' converges in one production invocation with deterministic emission'
        for name in CDC_FIXTURES}
    namespace = {'slice helpers and arguments avoid every retained module parameter name'}
    require(literal_cases((root/NAMESPACE_SOURCE).read_text()) == namespace,
            'missing/unreviewed emitter namespace testcase')
    sequential_before = inherited(SEQUENTIAL_SOURCE)
    sequential_current = (root/SEQUENTIAL_SOURCE).read_text()
    sequential_cases = literal_cases(sequential_before)
    require(len(sequential_cases) == 4 and set(SEQUENTIAL_RENAMES) <= sequential_cases,
            'changed sequential predecessor testcase identities')
    sequential_cases = {SEQUENTIAL_RENAMES.get(name, name) for name in sequential_cases}
    require(literal_cases(sequential_current) == sequential_cases,
            'missing/unreviewed sequential testcase rename')
    labels = ('keep', 'dontSimplify', 'vital', 'frozen', 'unknown tag', 'explicit lookalike', 'debug')
    template = 'test(s"$label ${if (aliasCase) "direct alias" else "condition"} remains a named identity for register consumers")'
    loop = 'Vector("keep", "dontSimplify", "vital", "frozen", "unknown tag", "explicit lookalike", "debug").zipWithIndex'
    require(all(source.count(template) == 1 and source.count(loop) == 1 and
                source.count('aliasCase <- Vector(false, true)') == 1
                for source in (sequential_before, sequential_current)),
            'changed sequential parameterized testcase inventory')
    sequential_cases |= {label + ' ' + kind + ' remains a named identity for register consumers'
                         for label in labels for kind in ('direct alias', 'condition')}
    return {
        EMITTER: {'project': 'core', 'cases': sorted(emitter), 'inherited_tests': 25,
                  'added_cases': sorted(EMITTER_ADDITIONS)},
        COPY: {'project': 'core', 'cases': sorted(copied), 'inherited_tests': 0,
               'added_cases': sorted(copied)},
        CDC: {'project': 'morphhdl', 'cases': sorted(cdc), 'inherited_tests': 0,
              'added_cases': sorted(cdc)},
        NAMESPACE: {'project': 'morphhdl', 'cases': sorted(namespace), 'inherited_tests': 0,
                    'added_cases': sorted(namespace)},
        SEQUENTIAL: {'project': 'morphhdl', 'cases': sorted(sequential_cases), 'inherited_tests': 18,
                     'added_cases': [], 'projected_by_sequential_catalog': True},
    }


def report_path(root: Path, name: str, spec: dict) -> Path:
    return root/spec['project']/'target/test-reports'/('TEST-' + name + '.xml')


def report_record(path: Path, name: str, expected: list[str]) -> dict:
    require(path.is_file() and not path.is_symlink(), 'missing/linked report: ' + str(path))
    raw = path.read_bytes()
    suite = ET.fromstring(raw)
    require(suite.tag == 'testsuite' and suite.get('name') == name,
            'wrong suite identity: ' + str(path))
    require(int(suite.get('tests', '0')) == len(expected), 'changed suite count: ' + name)
    require(all(int(suite.get(key, '0')) == 0 for key in ('failures', 'errors', 'skipped')) and
            not any(list(suite.iter(tag)) for tag in ('failure', 'error', 'skipped')),
            'failed/errored/skipped testcase: ' + name)
    names = [case.get('name') for case in suite.findall('testcase')]
    require(len(names) == len(expected) and all(names) and len(set(names)) == len(names) and
            sorted(names) == sorted(expected), 'missing/duplicate/unreviewed testcase: ' + name)
    return {'tests': len(names), 'testcases': sorted(names), 'sha256': hashlib.sha256(raw).hexdigest()}


def report_inventory(root: Path, source_head: str, specs: dict) -> dict:
    return {'schema_version': 1, 'source_head': source_head, 'predecessor': BASE,
            'reports': {name: report_record(report_path(root, name, spec), name, spec['cases'])
                        for name, spec in specs.items()}}


def projection(root: Path, predecessor: Path, source_head: str, specs: dict) -> tuple[dict, list]:
    receipt = report_inventory(root, source_head, specs)
    updates = []
    for name, spec in specs.items():
        original, copied = report_path(root, name, spec), report_path(predecessor, name, spec)
        require(copied.is_file() and not copied.is_symlink() and
                copied.read_bytes() == original.read_bytes(), 'copied report differs: ' + name)
        if spec.get('projected_by_sequential_catalog'):
            # The unchanged sequential adapter removes this entire copied
            # suite; retain its exact renamed-case receipt without double credit.
            continue
        if spec['inherited_tests']:
            suite = ET.fromstring(copied.read_bytes())
            for case in list(suite.findall('testcase')):
                if case.get('name') in spec['added_cases']:
                    suite.remove(case)
            require(len(suite.findall('testcase')) == spec['inherited_tests'],
                    'projection removed an inherited testcase: ' + name)
            suite.set('tests', str(spec['inherited_tests']))
            updates.append((copied, ET.tostring(suite, encoding='utf-8', xml_declaration=True)))
        else:
            updates.append((copied, None))
    # Return a plan only after every source/copy pair has passed validation.
    return receipt, updates


def merge_inventory(inherited: dict, receipt: dict, specs: dict) -> dict:
    require(receipt.get('schema_version') == 1 and receipt.get('predecessor') == BASE and
            set(receipt.get('reports', {})) == set(specs), 'wrong additive inventory schema')
    result = copy.deepcopy(inherited)
    for name, spec in specs.items():
        record = receipt['reports'][name]
        require(record.get('tests') == len(spec['cases']) and
                record.get('testcases') == spec['cases'], 'changed additive report receipt: ' + name)
        project = result.get(spec['project'])
        require(isinstance(project, dict) and isinstance(project.get('tests'), int) and
                project['tests'] > 0 and project.get('skipped') == 0 and
                isinstance(project.get('suites'), list) and
                len(project['suites']) == len(set(project['suites'])),
                'invalid inherited project inventory: ' + spec['project'])
        require((name in project['suites']) == bool(spec['inherited_tests']),
                'changed inherited/additional suite identity: ' + name)
        project['tests'] += len(spec['added_cases'])
        if not spec['inherited_tests']:
            project['suites'] = sorted(project['suites'] + [name])
    return result


def self_test(root: Path) -> None:
    specs = source_suites(root)
    rejected = 0
    def reject(action, label):
        nonlocal rejected
        try:
            action()
        except (RuntimeError, ValueError, OSError, ET.ParseError):
            rejected += 1
        else:
            raise RuntimeError('accepted report mutation: ' + label)
    with tempfile.TemporaryDirectory(prefix='cdc-wire-report-controls-') as directory:
        current, predecessor = Path(directory)/'current', Path(directory)/'predecessor'
        for name, spec in specs.items():
            suite = ET.Element('testsuite', name=name, tests=str(len(spec['cases'])),
                               failures='0', errors='0', skipped='0')
            for case in spec['cases']:
                ET.SubElement(suite, 'testcase', name=case)
            for target in (current, predecessor):
                path = report_path(target, name, spec)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(ET.tostring(suite))
        receipt, updates = projection(current, predecessor, 'fixture-not-qualified', specs)
        initial = {'core': {'tests': 30, 'suites': [EMITTER, 'original.core'], 'skipped': 0},
                   'morphhdl': {'tests': 20, 'suites': ['original.morph', SEQUENTIAL], 'skipped': 0}}
        merged = merge_inventory(initial, receipt, specs)
        added = sum(len(spec['added_cases']) for spec in specs.values())
        require(sum(value['tests'] for value in merged.values()) == 50 + added,
                'additive receipt removed inherited tests')
        require(initial['core']['tests'] == 30, 'merge mutated inherited receipt')
        for name, spec in specs.items():
            path = report_path(current, name, spec)
            raw = path.read_bytes()
            for key in ('failures', 'errors', 'skipped', 'tests'):
                suite = ET.fromstring(raw)
                suite.set(key, str(int(suite.get(key)) + 1))
                path.write_bytes(ET.tostring(suite))
                reject(lambda: report_inventory(current, 'fixture-not-qualified', specs), key)
            for tag in ('failure', 'error', 'skipped'):
                suite = ET.fromstring(raw); ET.SubElement(suite[0], tag)
                path.write_bytes(ET.tostring(suite))
                reject(lambda: report_inventory(current, 'fixture-not-qualified', specs), tag)
            for kind in ('missing', 'duplicate', 'unreviewed'):
                suite = ET.fromstring(raw)
                if kind == 'missing':
                    suite.remove(suite[0])
                elif kind == 'duplicate':
                    if len(suite) > 1:
                        suite[0].set('name', suite[1].get('name'))
                    else:
                        suite.append(copy.deepcopy(suite[0]))
                else:
                    suite[0].set('name', 'unreviewed-compensating-case')
                path.write_bytes(ET.tostring(suite))
                reject(lambda: report_inventory(current, 'fixture-not-qualified', specs), kind)
            path.unlink()
            reject(lambda: report_inventory(current, 'fixture-not-qualified', specs), 'missing report')
            path.write_bytes(raw)
            copied = report_path(predecessor, name, spec)
            copied.write_bytes(raw+b'\n')
            reject(lambda: projection(current, predecessor, 'fixture-not-qualified', specs), 'copy tamper')
            copied.write_bytes(raw)
            altered = copy.deepcopy(receipt); del altered['reports'][name]
            reject(lambda: merge_inventory(initial, altered, specs), 'missing receipt suite')
        for copied, content in updates:
            if content is None:
                copied.unlink()
            else:
                copied.write_bytes(content)
        require(report_inventory(current, 'fixture-not-qualified', specs) == receipt,
                'projection changed original reports')
        emitter_copy = report_path(predecessor, EMITTER, specs[EMITTER])
        expected = sorted(set(specs[EMITTER]['cases']) - EMITTER_ADDITIONS)
        report_record(emitter_copy, EMITTER, expected)
    print('CDC_WIRE_REGRESSION_CONTROLS_PASS added_tests=' + str(added) +
          ' added_suites=3 rejected=' + str(rejected) + ' original_xml_unchanged')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true', required=True)
    parser.parse_args()
    self_test(ROOT)
