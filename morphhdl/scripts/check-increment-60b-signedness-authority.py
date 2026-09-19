#!/usr/bin/env python3
"""Check 60b's original analysis-only boundary and authenticated later joins.

The original 60b checker still executes on its immutable qualified merge. Later
59i source is accepted only by its pinned, complete source certificate; its
actual signedness code is linted below, never replaced for compilation or tests.
"""
from __future__ import annotations

import argparse
import functools
import hashlib
import os
import re
import stat
import subprocess
import sys
import tempfile
import types
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


QUALIFIED_60B = 'd0c2d65ed301a7895218a2fe225b2faf4a4bbfe0'
QUALIFIED_60B_TREE = 'bfbefaa9ae4ebd3d52a7e63473bfc615b7cf5d01'
CHECKER = 'morphhdl/scripts/check-increment-60b-signedness-authority.py'
SCOPE = 'morphhdl/contracts/increment-60b-source-scope.txt'
ORIGINAL_CHECKER_SHA256 = 'aec45d3a935b0f228ed8857345a38a22001ac1ea87315bc0a9dc8ba378ee15ab'
ORIGINAL_SCOPE_SHA256 = 'faf9c3264942b03cedb6af215f697c9e8d26310de70f0d7d8dee18e4c46d2566'
SUCCESSOR = 'morphhdl/scripts/check-increment-59i-production-successor.py'
# Pin executable verifier semantics, not a moving branch or an unchecked file.
# The only normalized field is its separately authenticated manifest hash slot.
SUCCESSOR_SHA256 = frozenset((
    'd18d47a49cbb363077b266e47f10ea8b8604542dec8ff1955b3f4d29de7634c4',
    'd719d46132368d732d508a5d92434408eef3d0a2634751b3dfcc2c082525726d',
    'c9be9212f628384ac6c30d59c2382009ce681da40c514f4592e1b1a60ae790a7',
    'f952c57a2b927a73afefd2418db562be8e917ece1763be54449f67095f8918b1',
    'ee3833309ea2306dd34ac07cab18f99384d9e76fb8f3a6da070f2caadfe4e3fa',
    'bf05c8442278b46cbb68a45a72ba051c7501c4eb6a3c1479124249ddeaa99f8f',
))


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def regular(root: Path, name: str, executable: bool = False) -> bytes:
    path = root / name
    require(not any((root / Path(*Path(name).parts[:i])).is_symlink()
                    for i in range(1, len(Path(name).parts) + 1)),
            'linked inherited 60b source: ' + name)
    require(path.is_file() and stat.S_ISREG(path.stat().st_mode),
            'missing inherited 60b source: ' + name)
    require(bool(path.stat().st_mode & 0o111) == executable, 'inherited 60b source mode changed: ' + name)
    return path.read_bytes()


@functools.lru_cache(maxsize=8)
def successor_module(raw: bytes, filename: str):
    # Cache only an immutable module keyed by its complete source bytes. The
    # live verifier below is called every time, including on repeated checks.
    normalized, count = re.subn(rb'^CONTRACT_SHA256 = "[^"\n]+"$',
        b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)
    require(count == 1 and digest(normalized) in SUCCESSOR_SHA256,
            'unreviewed inherited 60b source authenticator')
    result = types.ModuleType('authenticated_60b_successor')
    result.__file__ = filename
    exec(compile(raw, filename, 'exec'), result.__dict__)
    require(callable(getattr(result, 'verify', None)), 'missing source authenticator API')
    return result


def inherited_source_scope(root: Path) -> None:
    root = root.resolve()
    head = git(root, 'rev-parse', 'HEAD').strip()
    git(root, 'merge-base', '--is-ancestor', QUALIFIED_60B, head)
    require(git(root, 'rev-parse', QUALIFIED_60B + '^{tree}').strip() == QUALIFIED_60B_TREE,
            'qualified 60b source tree changed')
    raw = regular(root, SUCCESSOR)
    successor = successor_module(raw, str(root / SUCCESSOR))
    successor.verify(root)  # Never cache a live checkout's authorization.
    original = subprocess.check_output(['git', 'show', QUALIFIED_60B + ':' + CHECKER], cwd=root)
    scope = subprocess.check_output(['git', 'show', QUALIFIED_60B + ':' + SCOPE], cwd=root)
    require(digest(original) == ORIGINAL_CHECKER_SHA256, 'original 60b checker changed')
    require(digest(scope) == ORIGINAL_SCOPE_SHA256 and regular(root, SCOPE) == scope,
            'original 60b analysis-only inventory changed')
    # The complete original predicate runs unchanged against the real historical
    # tree and real Git history. No projected or synthetic files are installed.
    with tempfile.TemporaryDirectory(prefix='60b-qualified-source-') as directory:
        checkout = Path(directory) / 'source'
        git(root, 'worktree', 'add', '--detach', str(checkout), QUALIFIED_60B)
        try:
            require(regular(checkout, CHECKER, executable=True) == original and regular(checkout, SCOPE) == scope,
                    'historical 60b checkout identity differs')
            result = subprocess.run([sys.executable, '-B', CHECKER], cwd=checkout,
                env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'),
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
            require(result.returncode == 0 and
                    b'Increment 60b: merged dependency, exact source scope and analysis-only boundary PASS' in result.stdout,
                    'original 60b analysis-only check failed: ' + result.stdout.decode(errors='replace'))
            require(git(checkout, 'rev-parse', 'HEAD').strip() == QUALIFIED_60B and
                    not git(checkout, 'status', '--porcelain', '--untracked-files=all').strip(),
                    'historical 60b checkout changed during audit')
        finally:
            git(root, 'worktree', 'remove', '--force', str(checkout))
    require(git(root, 'rev-parse', 'HEAD').strip() == head,
            'HEAD changed during inherited 60b authentication')
    print('60B_INHERITED_SOURCE_PASS historical=' + QUALIFIED_60B + ' current=' + head)


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
        if not changed <= allowed:
            inherited_source_scope(root)
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
    print('Increment 60b: merged dependency, authenticated source scope and analysis-only boundary PASS')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--without-git-scope', action='store_true',
                        help='source lint only for an extracted archive; CI never uses this option')
    args = parser.parse_args()
    check(Path(__file__).resolve().parents[2], not args.without_git_scope)


if __name__ == '__main__':
    main()
