#!/usr/bin/env python3
"""Validate downloaded source/commit handoff receipts before connector writes."""
import argparse
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--artifact', type=Path, required=True)
    p.add_argument('--sha256', required=True)
    p.add_argument('--extract-root', type=Path, required=True)
    p.add_argument('--controller', type=Path, required=True)
    p.add_argument('--phase', choices=['source', 'commits', 'dispatch'], required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    outer = load(HERE / 'verify-local-enable-committed.py', 'committed_scope')
    v = outer.load_inspector()
    stage = load(a.controller / 'stage.py', 'reviewed_stage')
    payload = stage.load(a.controller)
    require = v.require
    require(payload['seal'] == outer.HEAD and payload['source'] == outer.SOURCE,
            'wrong staged source')
    require(not a.output.exists() and a.extract_root.resolve() not in a.output.resolve().parents,
            'unsafe/existing output')
    sha = v.extract(a.artifact, a.sha256, a.extract_root)
    root = a.extract_root
    reconstruction = v.read_json(v.file(root, 'reconstruction.json'))
    require(reconstruction == dict(source=payload['source'], seal=payload['seal'],
        seal_tree=outer.TREE, preserved_commits=[c['sha'] for c in payload['commits']],
        target=stage.TARGET, target_tree=stage.TARGET_TREE,
        preserved_target_history='Existing immutable target commits are prerequisites, never recreated.',
        refs_updated=False, full_ci=False), 'reconstruction receipt differs')
    require(v.file_digest(v.file(root, 'exact-source.bundle')) == payload['bundle_sha256'],
            'preserved Git bundle changed')
    require(v.file(root, 'qualified-parent-review.log').read_text().strip() ==
        '59I_PRODUCTION_SUCCESSOR_PASS files=345', 'previous certificate did not pass')
    checks = v.read_json(v.file(root, 'source-checks.json'))
    require(checks['source'] == payload['source'] and checks['seal'] == payload['seal'] and
        checks['full_ci'] is False and checks['refs_updated'] is False and
        [c['command'] for c in checks['checks']] == stage.CHECKS and len(stage.CHECKS) == 25,
            'source command identities/scope differ')
    for i, row in enumerate(checks['checks'], 1):
        require(row['returncode'] == 0 and row['log'] == '%02d-%s.log' % (i, row['command'][0]) and
            v.file_digest(v.file(root, row['log'])) == row['sha256'], 'failed/changed source command')
    diag = checks['diagnostic']
    require(diag['run'] == 35458181915 and diag['attempt'] == 1 and
        diag['controller_sha'] == '5f2d416138ace882dd8465a41b4187211f368ccc' and
        diag['source_sha'] == 'ccec54986c70077e361f291cd322f0aa547aee15' and
        set(diag['jobs']) == {105937100299, 105937100112}, 'diagnostic prerequisite identity differs')
    requests = v.read_json(v.file(root, 'connector-tree-requests.json'))
    require(requests == payload['tree_requests'], 'connector tree requests differ from reviewed payload')
    if a.phase in ('commits', 'dispatch'):
        staged = v.read_json(v.file(root, 'commit-staging.json'))
        require(staged == dict(source=payload['source'], seal=payload['seal'],
            commits=[dict(sha=c['sha'], tree=c['tree'], parents=c['parents']) for c in payload['commits']],
            refs_updated=False, full_ci=False,
            next_action=dict(branch_name=stage.FEATURE, expected_old_sha=stage.BASE,
                sha=payload['seal'], force=False)), 'exact-commit handoff differs')
    if a.phase == 'dispatch':
        dispatched = v.read_json(v.file(root, 'failed-first-dispatch.json'))
        require(dispatched['source'] == payload['source'] and dispatched['seal'] == payload['seal'] and
            dispatched['workflow'] == stage.WORKFLOW and dispatched['branch'] == stage.FEATURE and
            dispatched['full_ci'] is False and dispatched['refs_updated_by_controller'] is False and
            (dispatched['dispatched'] is True or bool(dispatched['existing_runs'])),
            'targeted dispatch receipt differs')
    receipt = dict(phase=a.phase, zip_sha256=sha, source=payload['source'], seal=payload['seal'],
        source_commands_passed=len(checks['checks']), exact_commits=len(payload['commits']),
        tree_requests=len(requests), evidence_files=len([f for f in root.rglob('*') if f.is_file()]),
        qualification=False, full_ci=False, remote_mutations=False)
    a.output.write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
