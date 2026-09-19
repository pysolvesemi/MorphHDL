#!/usr/bin/env python3
"""Inspect actual diagnostic artifacts for the fixed 59i/PR190 development merge.

This is artifact inspection, not committed-head source or final qualification.
All prior XML, RTL, finite-proof, mutation and complete-retention checks remain.
"""
import argparse
import importlib.util
from pathlib import Path
import re
import sys

HERE = Path(__file__).resolve().parent
HEAD = 'ccec54986c70077e361f291cd322f0aa547aee15'
TREE = 'daa5f39b8f6f94a89073f0fad5a160b69df3ebc5'
PARENT = '58fb59773a2deebba0251b5b626c19a22453f0a4'
PARENT_TREE = '5ae0ef04c0dc730e49cbb499f2eca6d65b4bf60f'
TARGET = '4b8a86e25f5a1a3f0cb4c37dc537a8dd8aa7b097'
TARGET_TREE = 'ebe59eecbc8f550d265e78c717fb093603329055'
COMMON = 'e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d'
CHECKER_SHA = 'e522d80a10040cc59e9c484e56573b15b85ff8d4041b4fe62d4720cf0b6f3a40'
CONTRACT = 'morphhdl/contracts/increment-59i-production-successor.json'
HELPER = 'morphhdl/scripts/check-increment-59i-production-successor.py'


def main():
    parser = argparse.ArgumentParser(add_help=False, allow_abbrev=False)
    parser.add_argument('--repo-root', type=Path, default=HERE / '59i-dev')
    parser.add_argument('--controller', type=Path, required=True)
    parser.add_argument('--mode', choices=('success', 'failed'), required=True)
    parser.add_argument('--artifact', nargs=3, action='append', required=True)
    args, remaining = parser.parse_known_args()
    spec = importlib.util.spec_from_file_location('reviewed_pr190_diagnostic_inspector',
                                                  HERE / 'verify-local-enable-diagnostic-v5.py')
    inspector = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(inspector)
    require, git = inspector.require, inspector.git
    repo = args.repo_root.resolve()
    require(args.mode != 'success' or (len(args.artifact) == 2 and
            {row[0] for row in args.artifact} == {'2.12.18', '2.13.12'}),
            'successful diagnostic requires both Scala lanes')
    require(git(repo, 'show', '-s', '--format=%P', HEAD).decode().split() == [PARENT, TARGET],
            'fixed development merge parents differ')
    for ref, tree in ((HEAD, TREE), (PARENT, PARENT_TREE), (TARGET, TARGET_TREE)):
        require(git(repo, 'rev-parse', ref + '^{tree}').decode().strip() == tree,
                'immutable source/parent tree differs')
    require(git(repo, 'merge-base', '--all', PARENT, TARGET).decode().splitlines() == [COMMON],
            'fixed target merge base differs')
    require(git(repo, 'show', HEAD + ':' + CONTRACT) == git(repo, 'show', PARENT + ':' + CONTRACT),
            'development changed preserved certificate')
    require(re.findall(rb'^CONTRACT_SHA256 = "([^"\n]+)"$',
            git(repo, 'show', HEAD + ':' + HELPER), re.M) == [b'UNSEALED'],
            'development source is not explicitly unsealed')
    payload = inspector.read_json(args.controller / 'payload.json')
    require(payload['preserved_seal'] == PARENT and payload['preserved_seal_tree'] == PARENT_TREE and
            payload['integrated_target'] == TARGET and payload['integrated_target_tree'] == TARGET_TREE and
            payload['integration_common'] == COMMON, 'controller target bindings differ')
    inspector.HEAD, inspector.TREE = HEAD, TREE
    inspector.PINNED_CHECKERS[inspector.MAIN_CHECKER] = CHECKER_SHA
    original_identity = inspector.source_identity

    def source_identity(root, *rest, **kwargs):
        result = original_identity(root, *rest, **kwargs)
        require(inspector.read_json(inspector.file(root, 'target-integration-identity.json')) ==
                dict(source=HEAD, tree=TREE, parents=[PARENT, TARGET], preserved_seal_tree=PARENT_TREE,
                     target_tree=TARGET_TREE, common=COMMON, current_source_qualified=False),
                'target integration receipt differs')
        require('59I_PRODUCTION_SUCCESSOR_PASS files=322' in
                inspector.file(root, 'preserved-local-enable-seal-review.log').read_text(),
                'preserved schema-4 seal audit did not pass')
        return result

    inspector.source_identity = source_identity
    original_inventory = inspector.evidence_inventory
    inspector.evidence_inventory = lambda root, allow_missing=False: original_inventory(root, allow_missing=False)
    forwarded = [value for row in args.artifact for value in ['--artifact', *row]]
    sys.argv = [sys.argv[0], '--repo-root', str(repo), '--controller', str(args.controller.resolve()),
                '--mode', args.mode, *forwarded, *remaining]
    inspector.main_cli()


if __name__ == '__main__':
    main()
