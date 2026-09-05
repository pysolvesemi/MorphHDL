#!/usr/bin/env python3
"""Check the analysis-only Increment 60b source and merged dependency boundary."""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

BASE = '7087302067fc3b7ffdf4ead2d2b39c722196828c'
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


def check(root: Path, source_scope: bool) -> None:
    prerequisite = '- [x] **Increment 60a — Baseline, semantic contract and independent oracle**'
    roadmap = (root / ROADMAP).read_text()
    require(prerequisite in roadmap, '60a must be complete before 60b')
    require('**Increment 60b — Typed declaration and expression signedness authority**' in roadmap,
            '60b must be the existing named roadmap increment')
    if source_scope:
        git(root, 'merge-base', '--is-ancestor', BASE, 'HEAD')
        require(prerequisite in git(root, 'show', BASE + ':' + ROADMAP), 'dependency was not complete on merged base')
        allowed = set((root / 'morphhdl/contracts/increment-60b-source-scope.txt').read_text().splitlines())
        changed = set(git(root, 'diff', '--name-only', BASE, 'HEAD').splitlines())
        require(changed <= allowed, 'outside the reviewed analysis-only source scope: ' + repr(sorted(changed - allowed)))
        require(set(PRODUCTION) <= changed, 'both exact graph binding and target-neutral facts are required')
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
    print('Increment 60b: merged dependency, exact source scope and analysis-only boundary PASS')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--without-git-scope', action='store_true',
                        help='source lint only for an extracted archive; CI never uses this option')
    args = parser.parse_args()
    check(Path(__file__).resolve().parents[2], not args.without_git_scope)


if __name__ == '__main__':
    main()
