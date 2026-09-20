#!/usr/bin/env python3
"""CDC-WIRE-01 current-source review, independent of historical CI evidence.

The cumulative source seal authenticates HEAD/index/worktree and immutable
source bytes. This successor admits only the enumerated generic implementation
and review files. Historical PR190/PR189 contracts remain checked at their
unchanged predecessor; they never count as current implementation proof.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BASE = 'bbae646ba43e6189c69feb308f8decb9b677b15f'
BASE_TREE = 'c2f6e2abd588a131c5e6909173659935c62e77b7'
HISTORICAL = 'f4eaa67184f60147b0b2e3bc108276e43dea70c0'
OUTER = 'morphhdl/scripts/check-increment-62-wa08-source-overlay.py'
CONTRACT = 'morphhdl/contracts/increment-62-wa08-source-overlay.json'
SELF = 'morphhdl/scripts/check-cdc-wire-source-review.py'
REGISTRY = 'morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json'
PRODUCTION_PATHS = frozenset((
    'core/src/main/scala/spinal/core/internals/NativePureExpressionCopy.scala',
    'core/src/main/scala/spinal/core/ParameterizedExpressionCarrier.scala',
    'lib/src/main/scala/spinal/lib/Utils.scala',
    'core/src/main/scala/spinal/core/NativeWidthProvenance.scala',
    'core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala',
    'core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala',
    'morphhdl-passes/examples/NativeWireExpressionCodec.scala',
    'morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala',
    'morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala',
    'morphhdl-passes/examples/NamedWireAliasNativeBridge.scala',
    'morphhdl/src/main/scala/spinal/core/internals/NativeWireAssignmentMetadata.scala',
    'morphhdl/src/main/scala/spinal/core/internals/MorphHdlEmitterParameterNames.scala',
    'morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala',
))
TEST_PATHS = frozenset((
    'core/src/test/scala/spinal/core/internals/VerilogEmitterExpressionInliningTests.scala',
    'core/src/test/scala/spinal/core/internals/NativePureExpressionCopyTests.scala',
    'core/src/test/scala/spinal/core/internals/SequentialWireEmitterTests.scala',
    'morphhdl/src/test/scala/morphhdl/examples/CdcWireCleanupArtifactWriter.scala',
    'morphhdl/src/test/scala/morphhdl/CdcWireCleanupRegressionTests.scala',
    'morphhdl/src/test/scala/morphhdl/CdcWireEmitterNamespaceTests.scala',
    'morphhdl/src/test/scala/morphhdl/SequentialWireNativeTests.scala',
    'morphhdl/src/test/scala/spinal/core/CdcWireGeometryControl.scala',
    'morphhdl-passes/scripts/check-cdc-wire-cleanup.py',
))
REVIEW_PATHS = frozenset((
    SELF, OUTER, CONTRACT, REGISTRY,
    'morphhdl/contracts/increment-55-native-change-review.json',
    'morphhdl/contracts/native-source-preservation.json',
    'morphhdl/scripts/check-pr190-pr189-source-sync.py',
    'morphhdl/scripts/check-lane-when-source-scope.py',
    'morphhdl/scripts/check-cdc-wire-regressions.py',
    'morphhdl/scripts/refresh-cdc-wire-source-seal.py',
    'morphhdl-passes/scripts/check-boundary.sh',
    'morphhdl-passes/scripts/test-boundary-guard.sh',
    '.github/workflows/cdc-wire-fixed-point.yml',
    '.github/workflows/morphhdl-passes.yml',
    '.github/workflows/increment-60f-equivalence-closure.yml',
    'docs/morphhdl/cdc-wire-fixed-point-source-review.md',
    'docs/morphhdl/parameterized-verilog-todo.md',
    'morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md',
))


CDC_SAFETY_MARKERS = {
    'morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala': (
        'deferPreferredExpressionSource = true, sourceIntent = Some(sourceIntent))',
        'phases.insert(firstLiveness, sourceIntent)',
    ),
    'morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala': (
        '(!alias.isTypeNode || sourceIntent.exists(_.permits(alias)))',
        'alias.isUnnamed && alias.isComb && alias.isDirectionLess',
        'alias.getTags().forall(ParameterizedExpressionCarrier.isGeometryBoundary)',
        '!readPrivateBoolean(alias, "dontSimplify").getOrElse(true)',
    ),
    'morphhdl-passes/examples/NamedWireAliasNativeBridge.scala': (
        'alias.isTypeNode && !(nameOrigin == NameOrigin.Generated &&',
        'sourceIntent.exists(_.permits(alias))',
        'def this(deferPreferred: Boolean) = this(deferPreferred, None)',
        'source.isInput && (source.parentScope eq candidate.component.dslBody)',
        'alias.getTags().forall(ParameterizedExpressionCarrier.isGeometryBoundary)',
    ),
}


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError('CDC-WIRE-SOURCE: ' + detail)


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(['git', '--literal-pathspecs', *args], cwd=root, timeout=120)


def load(root: Path, path: str):
    require((root/path).is_file() and not (root/path).is_symlink(), 'missing/linked checker: ' + path)
    spec = importlib.util.spec_from_file_location('cdc_wire_' + Path(path).stem, root/path)
    require(spec is not None and spec.loader is not None, 'cannot import checker')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_scope(paths: set[str]) -> None:
    require(paths <= PRODUCTION_PATHS | TEST_PATHS | REVIEW_PATHS,
            'unreviewed successor paths: ' + repr(sorted(paths - PRODUCTION_PATHS - TEST_PATHS - REVIEW_PATHS)))
    require(bool(paths & PRODUCTION_PATHS), 'missing generic compiler implementation')
    require(SELF in paths, 'missing current-source review')


def safety_failures(path: str, source: str) -> list[str]:
    errors = ['missing CDC safety guard: ' + marker for marker in CDC_SAFETY_MARKERS.get(path, ())
              if marker not in source]
    # Scan compiler bodies only; example generators appended to existing bridge
    # files are not candidate recognition and remain byte-sealed as tests.
    for boundary in ('object ParameterizedStreamFifoNamedExpressionPassWitness',
                     'object ParameterizedStreamFifoExpressionPassWitness',
                     'object ParameterizedStreamFifoUnnamedPassWitness',
                     'object ParameterizedStreamFifoNamedPassWitness'):
        source = source.split(boundary, 1)[0]
    source = re.sub(r'/\*.*?\*/|//[^\n]*', '', source, flags=re.S)
    forbidden = (
        r'"(?:_zz_[^"]*|when_[^"]*_l\d+[^"]*)"',
        r'"(?:RemainingWireRepro|DisplayControllerProgressiveTimingGenerator|CdcWireCleanup[^\"]*)"',
        r'\b(?:parseVerilog|Pattern\.compile|readAllBytes|readString|fromFile)\b',
    )
    return errors + ['application/name/text recognition: ' + pattern for pattern in forbidden if re.search(pattern, source)]


def verify(root: Path = ROOT, sealed: dict | None = None) -> dict:
    root = root.resolve()
    outer = load(root, OUTER)
    seal = sealed if sealed is not None else outer.verify(root)
    require(hashlib.sha256(outer.normalized_helper((root/OUTER).read_bytes())).hexdigest() ==
            '14feb8286f32152b7c6881c73e0339e069bbeaaf07cdc1d51d84cc208fc39fab',
            'outer source verifier algorithm changed')
    git(root, 'merge-base', '--is-ancestor', BASE, 'HEAD')
    require(git(root, 'rev-parse', BASE+'^{tree}').decode().strip() == BASE_TREE,
            'predecessor tree changed')
    actual = set(git(root, 'diff', '--no-renames', '--name-only', BASE, 'HEAD').decode().splitlines())
    validate_scope(actual)
    entries = {e['path']: e for e in seal['files']}
    for path in actual - {OUTER, CONTRACT}:
        if outer.governed(path):
            require(path in entries, 'unsealed successor file: ' + path)
        raw = outer.regular(root, path, entries.get(path, {}).get('mode', '100644'))
        require(raw == git(root, 'show', 'HEAD:'+path) == git(root, 'show', ':'+path),
                'uncommitted review/documentation file: ' + path)
    inherited = load(root, 'morphhdl/scripts/check-lane-when-source-scope.py')
    for path in inherited.MARKERS:
        require(not inherited.safety_failures(path, (root/path).read_text()),
                'missing inherited/current safety obligation: ' + path)
    sequential = load(root, 'morphhdl/scripts/check-sequential-wire-source-review.py')
    for path in sequential.SAFETY_MARKERS:
        require(not sequential.safety_failures(path, (root/path).read_text()),
                'missing inherited sequential safety obligation: ' + path)
    for path in actual & PRODUCTION_PATHS:
        require(not safety_failures(path, (root/path).read_text()), 'nongeneric implementation: ' + path)
    old = json.loads(git(root, 'show', BASE+':'+REGISTRY))
    current = json.loads((root/REGISTRY).read_bytes())
    require(current.keys() == old.keys() and current['files'].keys() == old['files'].keys() and
            current['schema_version'] == old['schema_version'] and current['algorithm'] == old['algorithm'],
            'inherited formal signature registry schema/path set changed')
    for path, digest in current['files'].items():
        require(hashlib.sha256((root/path).read_bytes()).hexdigest() == digest,
                'formal fingerprint mismatch: ' + path)
    # Safety boundaries remain explicit current-source obligations. Changes in
    # implementation spelling are handled only by reviewed successor tests,
    # never by removing source-name/protection/reference-count guards.
    named = (root/'morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala').read_text()
    for marker in ('conditionSourceIntent.exists(_.permits(alias))',
                   'WA10-CONDITION-BLOCKING-DEPENDENCY', 'renderedUses.toLong * sourceSize > 256',
                   'replacements != candidate.receiverOccurrenceCount', 'if (remaining != 0)',
                   'candidate.assignment.removeStatement()', 'candidate.alias.removeStatement()'):
        require(marker in named, 'missing rewrite safety guard: ' + marker)
    return {'head': git(root, 'rev-parse', 'HEAD').decode().strip(), 'base': BASE,
            'source': seal['final_source_commit'], 'lane': BASE,
            'paths': sorted(actual), 'production_paths': sorted(actual & PRODUCTION_PATHS),
            'implementation_paths': sorted(actual & (PRODUCTION_PATHS | TEST_PATHS)),
            'review_paths': sorted(actual & REVIEW_PATHS)}


def self_test(root: Path = ROOT) -> None:
    result = verify(root)
    rejected = 0
    for path in ('core/src/main/scala/spinal/core/Unreviewed.scala',
                 'morphhdl/src/main/scala/morphhdl/Unreviewed.scala', '../escape',
                 '.github/workflows/skip-all.yml'):
        try:
            validate_scope(set(result['paths']) | {path})
        except RuntimeError:
            rejected += 1
        else:
            raise RuntimeError('accepted foreign successor path: ' + path)
    for source in ('val x = "_zz_named_special_case"', 'val x = "CdcWireCleanupFixture"',
                   'val x = readAllBytes(file)', 'val x = Pattern.compile("temporary")'):
        require(bool(safety_failures('test', source)), 'accepted fixture/name/text recognition')
        rejected += 1
    for path, markers in CDC_SAFETY_MARKERS.items():
        original = (root/path).read_text()
        for marker in markers:
            require(bool(safety_failures(path, original.replace(marker, 'REMOVED_CDC_GUARD'))),
                    'accepted missing direct-alias guard: ' + marker)
            rejected += 1
    with tempfile.TemporaryDirectory(prefix='cdc-wire-review-') as directory:
        copy = Path(directory)/'current'
        git(root, 'worktree', 'add', '--detach', str(copy), 'HEAD')
        try:
            for relative in result['production_paths']:
                path = copy/relative
                original = path.read_bytes()
                path.write_bytes(original+b'\n// unreviewed CDC-WIRE source mutation\n')
                try:
                    verify(copy)
                except RuntimeError:
                    rejected += 1
                else:
                    raise RuntimeError('accepted worktree source mutation: '+relative)
                finally:
                    path.write_bytes(original)
        finally:
            git(root, 'worktree', 'remove', '--force', str(copy))
    with tempfile.TemporaryDirectory(prefix='cdc-wire-predecessor-') as directory:
        predecessor = Path(directory)/'predecessor'
        git(root, 'merge-base', '--is-ancestor', HISTORICAL, 'HEAD')
        git(root, 'worktree', 'add', '--detach', str(predecessor), HISTORICAL)
        try:
            subprocess.run(['python3', 'morphhdl/scripts/check-pr190-pr189-source-sync.py'],
                           cwd=predecessor, check=True, timeout=120)
        finally:
            git(root, 'worktree', 'remove', '--force', str(predecessor))
    print('CDC_WIRE_SOURCE_CONTROLS_PASS rejected='+str(rejected)+
          ' historical_source_audit=pass current_behavioral_proof=separate')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--print-paths', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        result = verify()
        if args.print_paths:
            print('\n'.join(result['paths']))
        else:
            print('CDC_WIRE_SOURCE_PASS '+json.dumps(result, sort_keys=True))

if __name__ == '__main__':
    main()
