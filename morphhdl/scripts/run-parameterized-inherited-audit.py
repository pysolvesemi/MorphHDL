#!/usr/bin/env python3
"""Consume current-candidate provenance for an explicitly migrated source gate.

Policy 2026-10-06 replaces recursive historical execution with the producer's
once-per-candidate authenticated certificate traversal. The legacy command is
an allowlisted scope identifier, NOT a program to execute. Current-source tests,
proofs and mutation controls run independently in their original workflow lanes.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import sys
import tempfile
from candidate_provenance_ci import P, consume

ROOT = Path(__file__).resolve().parents[2]
INTEGRATION_PARENT = "6bb250972f06ca55c9cfbd7100126adad9e81569"

PREDECESSOR = "9c88f5f75921e28aff329e3cff2d40132315e861"

REVIEWERS = (
    "check-increment-59c-source-review.py",
    "check-increment-59g-source-review.py",
    "check-increment-59h-source-review.py",
    "check-increment-59i-source-review.py",
    "check-increment-61-source-review.py",
)

CONTROLS = (
    "test-increment-59i-review-composition.py",
    "test-increment-59i-capture-review.py",
    "test-increment-59d-inherited-60f-scope.py",
    "check-increment-59f-source-scope.py",
    "test-increment-59f-source-scope.py",
    "test-increment-59g-source-review.py",
    "test-increment-59b-inherited-source-scope.py",
    "test-increment-59h-inherited-source-scope.py",
    "test-increment-60f-inherited-source-scope.py",
    "test-wa07b-inherited-review.py",
    "test-increment-59i-regression-inventory.py",
)

COMMANDS = frozenset(
    [(name, *args) for name in REVIEWERS for args in ((), ("--self-test",))] +
    [(name,) for name in CONTROLS] +
    [("test-increment-59i-review-composition.py", "-v"),
     ("check-increment-59i-production-successor.py",),
     ("check-increment-60f-equivalence-closure.py", "--source-only")])


def command(arguments):
    P.require(bool(arguments), 'missing migrated source scope')
    path = Path(arguments[0])
    P.require(path.as_posix() == 'morphhdl/scripts/' + path.name and
              (path.name, *arguments[1:]) in COMMANDS, 'unreviewed source scope')
    return [sys.executable, '-B', *arguments]


def run(root, arguments):
    command(arguments)
    receipt = consume(root)
    inventory = P.parse(P.regular(root, P.INVENTORY))
    anchor = next(node for node in inventory['certificates'] if node['commit'] == PREDECESSOR)
    P.require(arguments[0] in anchor['evidence'], 'scope missing from authenticated evidence')
    output = root / 'target/parameterized-inherited-audits'
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode='wb', prefix='scope-', suffix='.json', dir=output, delete=False) as file:
        file.write(P.canonical({'policy': P.POLICY_ID, 'candidate': receipt['candidate'],
            'scope': arguments, 'historical_commit': PREDECESSOR,
            'historical_evidence': anchor['evidence'][arguments[0]],
            'historical_program_executed': False, 'rtl_qualification': False}))
    print('MIGRATED_SOURCE_SCOPE_AUTHENTICATED ' + ' '.join(arguments))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    run(ROOT, parser.parse_args().command)


if __name__ == '__main__':
    main()
