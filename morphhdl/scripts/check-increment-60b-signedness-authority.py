#!/usr/bin/env python3
"""Check the analysis-only Increment 60b source and merged dependency boundary."""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

BASE = '7087302067fc3b7ffdf4ead2d2b39c722196828c'
# Immutable introduction snapshot: later increments have their own source scopes.
QUALIFIED_MERGE = 'd0c2d65ed301a7895218a2fe225b2faf4a4bbfe0'
SCOPE = 'morphhdl/contracts/increment-60b-source-scope.txt'
ROADMAP = 'docs/morphhdl/increment-60-sint-signed-verilog-roadmap.md'
PRODUCTION = (
    'morphhdl/src/main/scala/morphhdl/analysis/SignednessFacts.scala',
    'morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala',
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(['git', *args], cwd=root, text=True)


def contains_commit(root: Path, revision: str) -> bool:
    result = subprocess.run(
        ['git', 'rev-parse', '--verify', '--quiet', revision + '^{commit}'],
        cwd=root, text=True, capture_output=True,
    )
    if result.returncode == 1:
        return False
    result.check_returncode()
    return True


def is_ancestor(root: Path, ancestor: str, descendant: str) -> bool:
    result = subprocess.run(
        ['git', 'merge-base', '--is-ancestor', ancestor, descendant],
        cwd=root, text=True, capture_output=True,
    )
    if result.returncode == 1:
        return False
    result.check_returncode()
    return True


def check_source_scope(root: Path, prerequisite: str) -> str:
    require(is_ancestor(root, BASE, 'HEAD'), 'merged 60a base is not an ancestor')
    require(prerequisite in git(root, 'show', BASE + ':' + ROADMAP),
            'dependency was not complete on merged base')

    # Before the original 60b merge, audit the entire candidate as before.
    # Afterwards, audit the immutable introduction delta against its own sealed
    # inventory. Do not attribute independently reviewed later increments to 60b.
    # Missing history cannot produce a bypass: HEAD then faces the original full
    # delta check. Current source boundaries and all Scala/tool proofs still run.
    revision = 'HEAD'
    if contains_commit(root, QUALIFIED_MERGE) and is_ancestor(root, QUALIFIED_MERGE, 'HEAD'):
        revision = QUALIFIED_MERGE
    allowed = set(git(root, 'show', revision + ':' + SCOPE).splitlines())
    changed = set(git(root, 'diff', '--no-renames', '--name-only', BASE, revision).splitlines())
    require(changed <= allowed,
            'outside the reviewed analysis-only source scope: ' + repr(sorted(changed - allowed)))
    require(set(PRODUCTION) <= changed,
            'both exact graph binding and target-neutral facts are required')
    print('Increment 60b: introduction source scope PASS at ' + revision)
    return revision


def check(root: Path, source_scope: bool) -> None:
    prerequisite = '- [x] **Increment 60a — Baseline, semantic contract and independent oracle**'
    roadmap = (root / ROADMAP).read_text()
    require(prerequisite in roadmap, '60a must be complete before 60b')
    require('**Increment 60b — Typed declaration and expression signedness authority**' in roadmap,
            '60b must be the existing named roadmap increment')
    if source_scope:
        check_source_scope(root, prerequisite)
    source = '\n'.join((root / name).read_text() for name in PRODUCTION)
    for forbidden in ('getName(', 'getNameElseThrow', 'getScalaLocation', '.verilog',
                      '.opName', 'ThreadLocal', 'Class.forName', 'scala.io',
                      'java.nio.file', 'NativeIntShadow', 'SIntCastHeavyBaseline',
                      'SIntSignedVerilogBaselineFixture', 'replaceAll', 'replaceFirst'):
        require(forbidden not in source, 'production inference contains forbidden dependency: ' + forbidden)
    neutral = (root / PRODUCTION[0]).read_text()
    require('import spinal.' not in neutral, 'neutral facts must not import the native graph')
    for kind in ('SignedScalar', 'UnsignedScalar', 'UnsignedAggregate', 'BooleanValue', 'Unknown'):
        require('case object ' + kind in neutral, 'missing signedness kind: ' + kind)
    native = (root / PRODUCTION[1]).read_text()
    for marker in ('IdentityHashMap', 'private[MorphHdlSignednessAnalysis]', 'STALE-EVIDENCE',
                   'FOREIGN-EVIDENCE', 'USE-IDENTITY', 'OPERAND-IDENTITY', 'WIDTH-USE-IDENTITY',
                   'UNKNOWN-FACT', 'EXPRESSION-CYCLE', 'requireAuthoritativeIntegerDomain',
                   'wordTypeLeaves', 'ParameterizedVec.packedShapeOf', 'PhaseVerilog'):
        require(marker in native, 'missing exact authority boundary: ' + marker)
    require('wordType()' not in native, 'memory analysis must not reevaluate HardType')
    print('Increment 60b: current dependency and analysis-only boundary PASS')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--without-git-scope', action='store_true',
                        help='source lint only for an extracted archive; CI never uses this option')
    args = parser.parse_args()
    check(Path(__file__).resolve().parents[2], not args.without_git_scope)


if __name__ == '__main__':
    main()
