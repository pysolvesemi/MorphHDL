#!/usr/bin/env python3
"""Read-only final-seal CI inventory. Never dispatches, retries, or changes refs.

Execution success and artifact availability are separate from artifact content
review. This tool deliberately never emits a final qualification certificate.
"""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib.util
import itertools
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
DISPATCHER_PATH = Path(os.environ.get('MORPHDL_59I_DISPATCHER',
    str(ROOT / 'full-ci-controller-transport/dispatch.py'))).resolve()
SPEC = importlib.util.spec_from_file_location('reviewed_dispatch', DISPATCHER_PATH)
dispatch = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(dispatch)
REPO = dispatch.REPOSITORY
FEATURE = dispatch.FEATURE
TARGET = dispatch.TARGET


def require(ok, why):
    if not ok:
        raise RuntimeError('59i CI evidence: ' + why)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def expand(text, matrix, seal=None, attempt=None):
    for key, value in matrix.items():
        text = re.sub(r'\$\{\{\s*matrix\.' + re.escape(key) + r'\s*\}\}', str(value), text)
    if seal:
        text = re.sub(r'\$\{\{\s*github\.event\.pull_request\.head\.sha\s*\|\|\s*github\.sha\s*\}\}', seal, text)
        text = re.sub(r'\$\{\{\s*github\.sha\s*\}\}', seal, text)
    if attempt is not None:
        text = re.sub(r'\$\{\{\s*github\.run_attempt\s*\}\}', str(attempt), text)
    return text


def step_expected(condition, matrix):
    if condition is None or condition in ('success()', 'always()'):
        return True
    if condition == 'failure()':
        return False
    match = re.fullmatch(r"matrix\.([a-zA-Z_]+) == '([^']+)'", condition)
    if match:
        require(match[1] in matrix, 'unknown conditional matrix axis')
        return str(matrix[match[1]]) == match[2]
    if condition in ("${{ !cancelled() && steps.generate.outcome == 'success' }}",
            "${{ !cancelled() }}",
            "${{ !cancelled() && steps.core.outcome == 'success' && steps.native.outcome == 'success' }}",
            "${{ !cancelled() && steps.generation.outcome == 'success' }}"):
        # Current-head CDC generation is itself a mandatory successful step.
        return True
    raise RuntimeError('review new step condition explicitly: ' + str(condition))


def prepare(args):
    import yaml
    manifest = json.loads(args.manifest.read_text())
    require(tuple(sorted(row['workflow'] for row in manifest['applicable'])) == dispatch.APPLICABLE,
            '53-workflow fleet changed')
    require(tuple(sorted(row['workflow'] for row in manifest['excluded'])) == dispatch.EXCLUDED,
            '14 reviewed exclusions changed')
    workflows = []
    for row in manifest['applicable']:
        raw = (args.repo_root / row['path']).read_bytes()
        require(sha(raw) == row['workflow_sha256'], 'workflow map is stale: ' + row['workflow'])
        workflow = yaml.safe_load(raw)
        jobs = []
        for job_id, job in workflow['jobs'].items():
            skipped = job_id in row.get('intentionally_skipped_jobs', {})
            require(skipped or job_id in row['required_jobs'], 'unclassified job: ' + job_id)
            matrix = job.get('strategy', {}).get('matrix', {})
            require(not ({'include', 'exclude'} & matrix.keys()), 'review matrix exceptions explicitly')
            axes = list(matrix)
            for values in itertools.product(*(matrix[axis] for axis in axes)):
                assignment = dict(zip(axes, values))
                name = expand(job.get('name', job_id), assignment)
                if axes and 'name' not in job:
                    name += ' (' + ', '.join(map(str, values)) + ')'
                require('${{' not in name, 'unresolved job name')
                steps, artifacts = [], []
                for number, step in enumerate(job.get('steps', []), 1):
                    expected = step_expected(step.get('if'), assignment) if not skipped else False
                    named = step.get('name')
                    if named:
                        steps.append(dict(name=expand(named, assignment), expected_success=expected,
                            reason=step.get('if', 'implicit success()'), source_step=number,
                            run_sha256=sha(step['run'].encode()) if 'run' in step else None))
                    if str(step.get('uses', '')).startswith('actions/upload-artifact@') and expected:
                        config = step['with']
                        require(config.get('if-no-files-found') == 'error', 'optional upload must be reviewed')
                        artifacts.append(dict(name_template=expand(config['name'], assignment),
                            source_paths=config['path'], step_name=expand(named, assignment) if named else None))
                jobs.append(dict(id=job_id, name=name, matrix=assignment, required=not skipped,
                    allowed_skip_reason=row.get('intentionally_skipped_jobs', {}).get(job_id),
                    steps=steps, artifacts=artifacts,
                    evidence_scope='historical-baseline-dependency-only' if row['workflow'] ==
                        'cdc-independent-parameter-consumers.yml' and job_id == 'baseline-probe' else 'final-seal'))
        workflows.append(dict(workflow=row['workflow'], path=row['path'],
            definition_sha256=row['workflow_sha256'], jobs=jobs,
            content_review=review_requirements(row['workflow'])))
    value = dict(schema=1, repository=REPO, target=TARGET, feature=FEATURE,
        manifest_sha256=sha(args.manifest.read_bytes()),
        dispatcher_sha256=sha(DISPATCHER_PATH.read_bytes()),
        planning_head=dispatch.git(args.repo_root, 'rev-parse', 'HEAD'),
        final_qualification_pass=False, historical_results_count_as_final=False,
        expected_inventory=manifest['expected_inventory'], excluded=manifest['excluded'], workflows=workflows)
    write(args.output, value)
    print(json.dumps(dict(workflows=len(workflows),
        required_jobs=sum(job['required'] for row in workflows for job in row['jobs']),
        allowed_skipped_jobs=sum(not job['required'] for row in workflows for job in row['jobs']),
        expected_artifacts=sum(len(job['artifacts']) for row in workflows for job in row['jobs']),
        plan=str(args.output), network_requests=0)))


def review_requirements(workflow):
    common = ['Verify every expected artifact ZIP against its API SHA-256; retain safe member-name/size/hash inventory.',
        'For current-source receipts, bind head to the exact seal and tree to its tree; preserve historical source identifiers separately.',
        'Require named proof/test/source steps to have run successfully. A skipped required step is not qualification.',
        'Record concrete test and proof limits from receipts; do not infer unbounded proof from bounded checks.']
    special = {
      'sequential-wire-consumers.yml': [
        'Require all37 target native/emitter/retention cases, generated public API consumer matrix, deterministic original RTL, shape checks, actual simulation comparisons and synthesis plus every inherited suite named by the exact workflow.',
        'Bind remaining-wires-evidence/source.txt and every generated/check receipt to the actual sealed source. Preserve real nonempty outputs; never infer runtime success from source checks or target historical runs.'],
      'increment-59i-local-enable-committed-head.yml': [
        'Require source plus both Scala archives, 138 exact cases across10 suites per Scala with XML name/class/count and explicit zero failure/error/skip fields; compare against sealed regression contract.',
        'Require head/tree and tracked-before/after equality; test-results.json and rtl-inventory.json identical across Scala.',
        'Re-hash every original inventory file in both hardware-A/B after mutation checking; mutation RTL is additional evidence, not part of original deterministic inventory.',
        'Main receipt:96 native cases,192 comparisons,384 cycles;32 bounded18-step proofs; strict Icarus/Verilator/Yosys evidence;2 emitted-netlist mutants each mismatch plus solver counterexample; normalized unmutated baseline passes.',
        'Supplement:6 native cases/24 candidate comparisons,320 cycles,1 emitted root-enable mutant rejected;4 publication profiles including actual split child modules. Its formal field must remain not-run.',
        'Inspect mutation lineage hashes and retained SAT witness/log artifacts. Do not treat normalized-mutant counterexamples alone as a hardware pass.'],
      'increment-60f-equivalence-closure.yml': [
        'Both regression archives must contain head.txt=seal, increment-59i-test-inventory.json with head=seal, test-inventory.json, historical-plus-61-inventory.json, raw XML for all8 projects, and actual inherited formal workspaces.',
        'Match every suite/case identity against sealed increment-59i-regression-inventory.json:2307 cases/230 suites per Scala, explicit zero failures/errors/skips. Compare final inventories across Scala; older2270/227 and2239/226 artifacts are insufficient.',
        'Keep immutable historical projection and original Increment61 20-case/2-suite receipt; these are subordinate provenance, never replacements for the complete current reports.',
        'Both RTL archives: verify rtl-manifest.json exact source/profile/file hashes, nonempty actual emitted files, A/B and cross-Scala bytes, solver-equivalence receipts and actual negative-control witnesses. Derive HDL count from actual manifest; do not hardcode historical213.'],
      'increment-59i-inherited-source-qualification.yml': [
        'Require5 distinct shard archives and aggregate source archive. Re-run sealed check-increment-59i-audit-shards.py aggregate against their exact receipts/head or independently verify the same27-command catalogue, returncodes and log hashes; retain required preflight/aggregate checks as well.',
        'Receipt origin attempts may differ only for successful reused jobs at the same exact seal and workflow definition; preserve each origin. Aggregate must authenticate all five.'],
      'increment-59i-combined-closure.yml': [
        'Require both combined archives including morphhdl/frontend raw XML, exact head/source status, A/B generated corpora and all evidence.json files.',
        'Review the full pinned cross-Scala Python comparison, including96 nested/96 capture/20 widening/56 saturation/96 child cases,16 mechanisms and16 saturation-widening cases; two independent native publication layouts;10 publication ABI/golden profiles.',
        'Preserve original RTL inventories and all emitted mutation counterexamples; complete_59i flags deliberately remain false on focused constituent receipts. Final join is an assessment across the complete fleet.'],
      'increment-59i-generated-child-combination.yml': [
        'Keep this workflow separate from v3 even though both use identical artifact names; index by run ID. Require each own child hierarchy/mode, test inventory, strict tools, proof and cross-Scala receipts.'],
      'increment-59i-generated-child-combination-v3.yml': [
        'Keep v3 separate from original even though artifact names match. Verify its own focused/inherited test inventory, split hierarchy, native comparisons and byte-identity receipts.'],
      'increment-59i-composite-saturation.yml': [
        'Verify case/layout matrix, saturation arithmetic references, A/B and cross-Scala bytes, strict tools, SAT and actual generated-RTL mutation counterexamples.'],
      'increment-61-one-file-per-component.yml': [
        'Require source archive, both diagnostics archives and both qualified archives. Qualified publication-proof.json must bind exact head and all split/single ABI/tool/equivalence profiles; check generated-a/b byte inventories and cross-Scala comparison.'],
      'increment-61-compatibility-matrix.yml': [
        'Require complete emitted compatibility corpus and strict Verilog-2001 compile/lint/synthesis receipts for every roadmap family, with cross-Scala actual bytes.'],
      'cdc-independent-parameter-consumers.yml': [
        'The448dfdf baseline-probe artifact is historical dependency evidence only; never label its compilation as final-source runtime success.',
        'Current source-preflight and current record-consumers must separately bind seal, succeed in generation, override simulation/synthesis and exact construction-negative diagnostics.'],
      'lane-when-expression-diagnostic.yml': [
        'Retain historical compiler reproducer separately; require actual current-production qualification, both ABI compatibility lanes, both SBT full-suite lanes and both Mill affected-suite lanes, plus complete exact-head aggregate job.'],
      'morphhdl-baseline.yml': [
        'Require both Scala live phase-inventory artifacts plus the2.12 contract-RTL artifact. The2.13 job intentionally skips2.12-only artifact/smoke steps; architecture contracts must consume both actual phase inventories.'],
    }
    if workflow in special:
        return common + special[workflow]
    return common + ['Review workflow-defined manifests, native comparisons, formal workspace/solver statuses and negative controls from retained logs and archives. Workflows without uploads require retained completed-job logs.']


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


class ReadOnlyApi:
    def __init__(self, token):
        self.token = token

    def bytes(self, suffix, **query):
        require(suffix.startswith(('/actions/', '/git/ref/', '/pulls/')), 'unexpected read-only resource')
        url = 'https://api.github.com/repos/' + REPO + suffix
        if query:
            url += '?' + urllib.parse.urlencode(query)
        request = urllib.request.Request(url, method='GET', headers={
            'Authorization': 'Bearer ' + self.token, 'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'MorphHDL-59i-read-only-evidence'})
        # Never forward the GitHub token to an artifact/log storage redirect.
        try:
            with urllib.request.build_opener(NoRedirect).open(request, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code not in (301, 302, 303, 307, 308):
                raise
            target = error.headers['Location']
            require(urllib.parse.urlparse(target).scheme == 'https', 'insecure download redirect')
            with urllib.request.urlopen(urllib.request.Request(target, method='GET'), timeout=120) as response:
                return response.read()

    def get(self, suffix, **query):
        return json.loads(self.bytes(suffix, **query))

    def collection(self, suffix, key, **query):
        rows = []
        for page in range(1, 51):
            value = self.get(suffix, per_page=100, page=page, **query)
            require(isinstance(value.get(key), list), 'bad collection envelope')
            rows.extend(value[key])
            if len(value[key]) < 100 or len(rows) >= value['total_count']:
                require(len(rows) == value['total_count'], 'truncated or duplicate collection')
                require(len({row['id'] for row in rows}) == len(rows), 'duplicate API objects')
                return rows
        raise RuntimeError('pagination limit exceeded')


def check_job(job, expected, run, seal):
    issues = []
    if job.get('head_sha') != seal or job.get('run_id') != run['id']:
        issues.append('job source or run identity differs')
    if not expected['required']:
        if job.get('conclusion') != 'skipped':
            issues.append('out-of-scope publisher/repair job did not remain skipped')
        return issues
    if job.get('status') != 'completed' or job.get('conclusion') != 'success':
        issues.append('required job has not completed successfully')
    steps = {}
    for step in job.get('steps', []):
        steps.setdefault(step['name'], []).append(step)
        if step.get('conclusion') not in ('success', 'skipped'):
            issues.append('nonpassing job step: ' + step['name'])
    for step in expected['steps']:
        actual = steps.get(step['name'], [])
        if len(actual) != 1:
            issues.append('missing/duplicate named step: ' + step['name'])
        elif step['expected_success'] and actual[0].get('conclusion') != 'success':
            issues.append('required step did not succeed: ' + step['name'])
        elif not step['expected_success'] and actual[0].get('conclusion') != 'skipped':
            issues.append('expected skipped step unexpectedly ran: ' + step['name'])
    return issues


def zip_inventory(path, digest):
    raw = path.read_bytes()
    require(sha(raw) == digest.removeprefix('sha256:'), 'downloaded artifact digest differs')
    inventory = []
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'duplicate ZIP members')
        for entry in archive.infolist():
            name = PurePosixPath(entry.filename)
            require(not name.is_absolute() and '..' not in name.parts and '\\' not in entry.filename,
                    'unsafe ZIP member')
            mode = entry.external_attr >> 16
            require(not stat.S_ISLNK(mode), 'linked ZIP member')
            if not entry.is_dir():
                content = archive.read(entry)
                require(len(content) == entry.file_size, 'wrong ZIP member size')
                inventory.append(dict(path=entry.filename, bytes=len(content), sha256=sha(content)))
    require(inventory, 'empty artifact archive')
    return inventory


def collect(args):
    plan = json.loads(args.plan.read_text())
    require(plan['schema'] == 1 and plan['repository'] == REPO and plan['target'] == TARGET
            and plan['feature'] == FEATURE and len(plan['workflows']) == 53, 'wrong evidence plan')
    require(sha(DISPATCHER_PATH.read_bytes()) == plan['dispatcher_sha256'],
            'reviewed dispatcher helpers changed')
    dispatch.clean_source(args.repo_root, args.source_sha, args.seal_sha)
    for row in plan['workflows']:
        require(sha((args.repo_root / row['path']).read_bytes()) == row['definition_sha256'],
                'workflow definition changed after evidence planning')
    require(args.output.resolve() != args.repo_root.resolve() and args.repo_root.resolve() not in args.output.resolve().parents,
            'evidence must be outside source checkout')
    args.output.mkdir(parents=True, exist_ok=True)
    require(os.getenv('GITHUB_TOKEN'), 'GITHUB_TOKEN required for authenticated read-only collection')
    api = ReadOnlyApi(os.environ['GITHUB_TOKEN'])
    initial = dispatch.guard(api, args.seal_sha)
    runs = api.collection('/actions/runs', 'workflow_runs', head_sha=args.seal_sha)
    require(all(run['head_sha'] == args.seal_sha for run in runs), 'API ignored sealed-head filter')
    write(args.output / 'exact-head-runs.json', runs)
    summaries = []
    for expected in plan['workflows']:
        candidates = [run for run in runs if run['path'] == expected['path']]
        require(all(run['head_branch'] == FEATURE and run['event'] in ('push', 'pull_request', 'workflow_dispatch')
                    and run['repository']['full_name'] == REPO for run in candidates), 'foreign same-head run origin')
        candidates.sort(key=lambda run: (run['created_at'], run['id']), reverse=True)
        active = [run for run in candidates if run['status'] != 'completed']
        summary = dict(workflow=expected['workflow'], execution_pass=False, artifact_index_complete=False,
            content_review_pass=False, candidate_runs=[dict(id=run['id'], attempt=run['run_attempt'],
                status=run['status'], conclusion=run['conclusion']) for run in candidates], issues=[])
        summaries.append(summary)
        if len(active) > 1:
            summary['issues'].append('multiple active duplicate runs; reconcile')
            continue
        run = active[0] if active else (candidates[0] if candidates else None)
        if not run:
            summary['issues'].append('workflow not started at exact seal')
            continue
        folder = args.output / ('run-' + str(run['id']))
        write(folder / 'run.json', run)
        summary.update(run_id=run['id'], run_attempt=run['run_attempt'], url=run['html_url'])
        if run['status'] != 'completed' or run['conclusion'] != 'success':
            summary['issues'].append('workflow not terminal success')
        latest = api.collection('/actions/runs/' + str(run['id']) + '/jobs', 'jobs', filter='latest')
        write(folder / 'effective-jobs.json', latest)
        origins = {}
        require(run['run_attempt'] <= 30, 'excessive attempts need separate reconciliation')
        for attempt in range(1, run['run_attempt'] + 1):
            jobs = api.collection('/actions/runs/' + str(run['id']) + '/attempts/' + str(attempt) + '/jobs', 'jobs')
            write(folder / ('attempt-%d-jobs.json' % attempt), jobs)
            for job in jobs:
                origins.setdefault(job['id'], []).append(attempt)
        names = [job['name'] for job in latest]
        if len(names) != len(set(names)):
            summary['issues'].append('duplicate effective job name')
        actual = {job['name']: job for job in latest}
        expected_names = {job['name'] for job in expected['jobs']}
        if set(actual) - expected_names:
            summary['issues'].append('unexpected jobs: ' + repr(sorted(set(actual) - expected_names)))
        job_records = []
        expected_artifacts = []
        for specification in expected['jobs']:
            job = actual.get(specification['name'])
            if job is None:
                if specification['required']:
                    summary['issues'].append('missing required job: ' + specification['name'])
                continue
            issues = check_job(job, specification, run, args.seal_sha)
            summary['issues'].extend(specification['name'] + ': ' + issue for issue in issues)
            origin = min(origins.get(job['id'], [job.get('run_attempt', run['run_attempt'])]))
            record = dict(name=job['name'], id=job['id'], origin_attempt=origin,
                observed_in_attempts=origins.get(job['id'], []), conclusion=job['conclusion'],
                evidence_scope=specification['evidence_scope'], issues=issues)
            job_records.append(record)
            if args.logs and job['status'] == 'completed' and specification['required']:
                log = api.bytes('/actions/jobs/' + str(job['id']) + '/logs')
                require(log, 'empty required job log')
                log_path = folder / ('job-%d.log' % job['id'])
                log_path.write_bytes(log)
                record.update(log=str(log_path.relative_to(args.output)), log_sha256=sha(log))
            for artifact in specification['artifacts']:
                name = expand(artifact['name_template'], {}, args.seal_sha, origin)
                require('${{' not in name, 'unresolved artifact name')
                expected_artifacts.append(dict(name=name, job_id=job['id'], origin_attempt=origin,
                    evidence_scope=specification['evidence_scope'], source_paths=artifact['source_paths']))
        summary['jobs'] = job_records
        summary['execution_pass'] = not summary['issues']
        artifacts = api.collection('/actions/runs/' + str(run['id']) + '/artifacts', 'artifacts')
        write(folder / 'artifacts.json', artifacts)
        artifact_issues, artifact_records = [], []
        for wanted in expected_artifacts:
            matches = [artifact for artifact in artifacts if artifact['name'] == wanted['name']]
            if len(matches) != 1:
                artifact_issues.append('missing/ambiguous artifact: ' + wanted['name'])
                continue
            item = matches[0]
            issues = []
            if item.get('expired') or item.get('size_in_bytes', 0) <= 0:
                issues.append('expired or empty artifact')
            if not re.fullmatch('sha256:[0-9a-f]{64}', item.get('digest') or ''):
                issues.append('API digest absent or malformed')
            origin = item.get('workflow_run', {})
            if origin.get('id') != run['id'] or origin.get('head_sha') != args.seal_sha:
                issues.append('artifact run/seal identity not proven')
            entry = dict(wanted, artifact_id=item['id'], bytes=item['size_in_bytes'],
                digest=item.get('digest'), issues=issues, content_review_pass=False)
            artifact_records.append(entry)
            artifact_issues.extend(wanted['name'] + ': ' + issue for issue in issues)
            if not issues and expected['workflow'] in args.download_workflow:
                archive = folder / ('artifact-%d.zip' % item['id'])
                if not archive.exists():
                    archive.write_bytes(api.bytes('/actions/artifacts/' + str(item['id']) + '/zip'))
                members = zip_inventory(archive, item['digest'])
                write(folder / ('artifact-%d-members.json' % item['id']), members)
                entry.update(archive=str(archive.relative_to(args.output)),
                    zip_sha256=sha(archive.read_bytes()), files=len(members), transport_verified=True)
        summary['artifacts'] = artifact_records
        summary['artifact_issues'] = artifact_issues
        summary['artifact_index_complete'] = not artifact_issues and len(artifact_records) == len(expected_artifacts)
        summary['separate_review_required'] = expected['content_review']
        write(folder / 'review-index.json', summary)
        write(args.output / 'progress.json', summaries)
    final = dispatch.guard(api, args.seal_sha)
    receipt = dict(schema=1, repository=REPO, source=args.source_sha, seal=args.seal_sha,
        tree=dispatch.git(args.repo_root, 'rev-parse', 'HEAD^{tree}'), target=TARGET,
        collected_at=datetime.now(timezone.utc).isoformat(), plan_sha256=sha(args.plan.read_bytes()),
        initial_identity=initial, final_identity=final, workflows=summaries,
        ci_execution_pass=all(row['execution_pass'] for row in summaries),
        artifact_index_complete=all(row['artifact_index_complete'] for row in summaries),
        final_qualification_pass=False, separate_artifact_content_review_required=True,
        readonly=True, dispatched=False, historical_results_count_as_final=False)
    write(args.output / 'qualification-review-index.json', receipt)
    print(json.dumps(dict(seal=args.seal_sha, execution_passed=sum(row['execution_pass'] for row in summaries),
        workflows=53, artifact_indexes_complete=sum(row['artifact_index_complete'] for row in summaries),
        final_qualification_pass=False, output=str(args.output))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('prepare')
    p.add_argument('--repo-root', type=Path, required=True)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p = sub.add_parser('collect')
    p.add_argument('--repo-root', type=Path, required=True)
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--source-sha', required=True)
    p.add_argument('--seal-sha', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--logs', action='store_true')
    p.add_argument('--download-workflow', action='append', default=[])
    args = parser.parse_args()
    if args.command == 'prepare':
        prepare(args)
    else:
        collect(args)


if __name__ == '__main__':
    main()
