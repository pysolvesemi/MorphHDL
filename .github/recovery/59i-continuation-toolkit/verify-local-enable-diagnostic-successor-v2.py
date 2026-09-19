#!/usr/bin/env python3
"""Bind the reviewed full diagnostic inspector to an explicit successor identity.

This wrapper changes no historical inspector and executes no HDL tools. Both
success and failure modes require complete current-upload file-hash coverage,
including hidden publication metadata. Historical incomplete evidence must use
its original historical inspector.
"""
import argparse
import importlib.util
from pathlib import Path
import re
import sys

HERE = Path(__file__).resolve().parent
INPUT_HEAD = 'cb093b5aa235760f2c3149f4327324976aa0495b'
PREVIOUS_CHECKER_HEAD = '6a858ead678f7ec96e9a421f2b4551bcd62d65d9'


def main():
    parser = argparse.ArgumentParser(description=__doc__, add_help=False, allow_abbrev=False)
    parser.add_argument('--source', required=True)
    parser.add_argument('--tree', required=True)
    parser.add_argument('--checker-sha', required=True)
    parser.add_argument('--repo-root', type=Path, default=HERE / '59i-dev')
    parser.add_argument('--controller', type=Path, required=True)
    parser.add_argument('--mode', choices=('success', 'failed'), required=True)
    parser.add_argument('--artifact', nargs=3, action='append', required=True)
    args, remaining = parser.parse_known_args()
    spec = importlib.util.spec_from_file_location('reviewed_full_diagnostic_inspector',
                                                  HERE / 'verify-local-enable-diagnostic-v5.py')
    inspector = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(inspector)
    require, repo = inspector.require, args.repo_root.resolve()
    require(args.mode != 'success' or (len(args.artifact) == 2 and
            {row[0] for row in args.artifact} == {'2.12.18', '2.13.12'}),
            'successful full diagnostic requires both Scala lanes')
    require(re.fullmatch('[0-9a-f]{40}', args.source) and re.fullmatch('[0-9a-f]{40}', args.tree) and
            re.fullmatch('[0-9a-f]{64}', args.checker_sha), 'exact reviewed source/tree/checker identities required')
    require(inspector.git(repo, 'rev-parse', args.source + '^{tree}').decode().strip() == args.tree,
            'explicit source tree differs')
    inspector.git(repo, 'merge-base', '--is-ancestor', PREVIOUS_CHECKER_HEAD, args.source)
    delta = inspector.git(repo, 'diff', '--name-only', INPUT_HEAD, args.source).decode().splitlines()
    require(set(delta) == {inspector.MAIN_CHECKER, '.github/workflows/increment-59i-local-enable-committed-head.yml'},
            'reviewed checker-only successor changed other diagnostic inputs')
    require(inspector.digest(inspector.git(repo, 'show', args.source + ':' + inspector.MAIN_CHECKER)) == args.checker_sha,
            'explicit main checker digest differs')
    require(inspector.git(repo, 'show', args.source + ':' + inspector.COMBINED_CHECKER) ==
            inspector.git(repo, 'show', INPUT_HEAD + ':' + inspector.COMBINED_CHECKER),
            'unchanged supplemental checker differs')
    inspector.HEAD, inspector.TREE = args.source, args.tree
    inspector.PINNED_CHECKERS[inspector.MAIN_CHECKER] = args.checker_sha
    inspector.__doc__ = __doc__ + '\nBound source: ' + args.source + '\nBound tree: ' + args.tree
    original_inventory = inspector.evidence_inventory
    def strict_current_inventory(root, allow_missing=False):
        return original_inventory(root, allow_missing=False)
    inspector.evidence_inventory = strict_current_inventory
    forwarded = [value for row in args.artifact for value in ['--artifact', *row]]
    sys.argv = [sys.argv[0], '--repo-root', str(repo), '--controller', str(args.controller.resolve()),
                '--mode', args.mode, *forwarded, *remaining]
    inspector.main_cli()


if __name__ == '__main__':
    main()
