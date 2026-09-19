#!/usr/bin/env python3
"""Authenticate PR177 failed-first completion; plan/dispatch missing final-head CI.

This controller never edits source, refs, pull requests, or workflow definitions.
A dispatch receipt is not a qualification result. POST requests are never retried
implicitly: an interrupted/ambiguous dispatch requires reconciliation first.
"""
from __future__ import annotations
import argparse
import hashlib
import itertools
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPOSITORY = 'pysolvesemi/MorphHDL'
FEATURE = 'agent/increment-59i-combined-reduction-closure'
TARGET = 'e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d'
LOCAL = 'increment-59i-local-enable-committed-head.yml'
LOCAL_JOBS = {'Exact committed source and review',
    'Committed local-enable Scala 2.12.18', 'Committed local-enable Scala 2.13.12',
    'Committed local-enable cross-Scala identity'}
HISTORICAL = [('baseline', 'morphhdl-baseline.yml', 35257965646, 'ddf61ef25f927d646027cebbcaca6d724ad8a5fa', 'recovery/increment-59i-failed-first-ddf61ef2'), ('Mill', 'morphhdl-mill.yml', 35257969307, 'ddf61ef25f927d646027cebbcaca6d724ad8a5fa', 'recovery/increment-59i-failed-first-ddf61ef2'), ('Increment61 publication', 'increment-61-one-file-per-component.yml', 35257971746, 'ddf61ef25f927d646027cebbcaca6d724ad8a5fa', 'recovery/increment-59i-failed-first-ddf61ef2'), ('independent parameters', 'independent-parameter-domains.yml', 35257974512, 'ddf61ef25f927d646027cebbcaca6d724ad8a5fa', 'recovery/increment-59i-failed-first-ddf61ef2'), ('59g register bridges', 'increment-59g-register-bridges.yml', 35275906758, 'c74b34bb1154d1df20bf85aa63e1276388511a02', 'recovery/increment-59i-59g-report-c74b34bb'), ('sharded inherited source', 'increment-59i-inherited-source-qualification.yml', 35331945529, '37d1629f9b78c4d9cd6646abee9962fdd046e413', 'recovery/increment-59i-audit-shards-37d1629f'), ('59b inherited scope', 'increment-59b-inherited-source-scope.yml', 35363134440, 'f42641880e0645f0c997ecedabd031bf8948bbfa', 'recovery/increment-59i-pr189-sync-f4264188'), ('59d widening', 'increment-59d-widening.yml', 35363139931, 'f42641880e0645f0c997ecedabd031bf8948bbfa', 'recovery/increment-59i-pr189-sync-f4264188'), ('59f callbacks', 'increment-59f-callback-graphs.yml', 35363145880, 'f42641880e0645f0c997ecedabd031bf8948bbfa', 'recovery/increment-59i-pr189-sync-f4264188'), ('59h nested owners', 'increment-59h-nested-owners.yml', 35363151766, 'f42641880e0645f0c997ecedabd031bf8948bbfa', 'recovery/increment-59i-pr189-sync-f4264188'), ('60f closure', 'increment-60f-equivalence-closure.yml', 35377581763, '90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6', 'recovery/increment-59i-60f-gates-90b7fc8f')]
# Exact reviewed fleet; workflow_dispatch presence alone never expands it.
APPLICABLE = ('cdc-independent-parameter-consumers.yml', 'increment-59b-closed-graph.yml', 'increment-59b-inherited-source-scope.yml', 'increment-59b-operator-replay.yml', 'increment-59b-publication.yml', 'increment-59b-reduce-balanced-tree.yml', 'increment-59b-stage-replay.yml', 'increment-59c-named-field-vectors.yml', 'increment-59d-widening.yml', 'increment-59e-composite-reduction.yml', 'increment-59f-callback-graphs.yml', 'increment-59g-register-bridges.yml', 'increment-59h-nested-owners.yml', 'increment-59i-combined-closure.yml', 'increment-59i-composite-saturation.yml', 'increment-59i-generated-child-combination-v3.yml', 'increment-59i-generated-child-combination.yml', 'increment-59i-inherited-source-qualification.yml', 'increment-59i-local-enable-committed-head.yml', 'increment-60a-sint-baseline.yml', 'increment-60b-signedness-authority.yml', 'increment-60c-signed-declarations.yml', 'increment-60d-pure-sint-casts.yml', 'increment-60e-signedness-boundaries.yml', 'increment-60f-equivalence-closure.yml', 'increment-60g-default-signed-verilog.yml', 'increment-61-compatibility-matrix.yml', 'increment-61-one-file-per-component.yml', 'increment-62-wa08-source-overlay.yml', 'independent-parameter-domains.yml', 'lane-when-expression-diagnostic.yml', 'morphhdl-baseline.yml', 'morphhdl-enum-localparams.yml', 'morphhdl-external-boundary.yml', 'morphhdl-external-formalization.yml', 'morphhdl-external-memory.yml', 'morphhdl-external-structural-process.yml', 'morphhdl-external-symbolic-width.yml', 'morphhdl-mill.yml', 'morphhdl-native-axi4-slave-factory-formal-equivalence.yml', 'morphhdl-native-axi4-slave-factory-offsets.yml', 'morphhdl-native-int-nested-control-flow.yml', 'morphhdl-native-int-shadow-expressions.yml', 'morphhdl-native-int-shadow-provenance.yml', 'morphhdl-native-int-symbolic-conditionals.yml', 'morphhdl-native-source-guard.yml', 'morphhdl-native-stream-width-adapter.yml', 'morphhdl-native-streamfifo-formal-equivalence.yml', 'morphhdl-native-streamfifo-structure.yml', 'morphhdl-streamfifocc-proof.yml', 'morphhdl-typed-elaboration-values.yml', 'wa11-symbolic-boolean-width.yml')
EXCLUDED = ('increment-53d-typed-streamwidthadapter.yml', 'increment-53e-typed-streamfifo.yml', 'increment-53f-typed-primitives.yml', 'increment-53g-production-retirement.yml', 'increment-54-typed-layering-ir.yml', 'increment-55-concrete-compatibility-audit.yml', 'increment-56-native-typed-library-surface.yml', 'increment-57-broad-native-library-migration.yml', 'increment-57a-typed-streamfifocc.yml', 'increment-57b-streamfifocc-payload-width-formal.yml', 'increment-58-legacy-retirement.yml', 'increment-59-typed-blackbox-generics.yml', 'increment-59a-recursive-verilog-module.yml', 'morphhdl-passes.yml')


def require(ok, detail):
    if not ok: raise RuntimeError('59i full CI: '+detail)


def sha(raw): return hashlib.sha256(raw).hexdigest()


def git(root, *args):
    return subprocess.check_output(['git','--literal-pathspecs',*args],cwd=root,
        stderr=subprocess.PIPE,timeout=120).decode().strip()


def blob(root, revision, path):
    return subprocess.check_output(['git','--literal-pathspecs','show',revision+':'+path],
        cwd=root,stderr=subprocess.PIPE,timeout=120)


def write(path, value):
    path.parent.mkdir(parents=True,exist_ok=True)
    temporary=path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(value,indent=2,sort_keys=True)+'\n')
    temporary.replace(path)


def validate_fleet(value):
    require(value['schema']==1 and value['repository']==REPOSITORY and value['pr']==177 and
        value['feature_branch']==FEATURE and value['target_branch']=='parameterized-verilog' and
        value['target_snapshot']==TARGET,'wrong workflow-map identity')
    rows=value['applicable_workflows']; excluded=value['excluded_pr_workflows']
    require(len(rows)==52 and sorted(r['workflow'] for r in rows)==list(APPLICABLE),
        'applicable fleet differs from reviewed 52 workflows')
    require(len(excluded)==14 and sorted(r['workflow'] for r in excluded)==list(EXCLUDED),
        'exclusions differ from reviewed 14 workflows')
    for row in rows:
        require(row['path']=='.github/workflows/'+row['workflow'] and
            row['dispatch_supported_by_source'] is True,'invalid dispatch path/trigger')
        expected={'mode':'checks'} if row['workflow']=='cdc-independent-parameter-consumers.yml' else {}
        require(row['dispatch_inputs']==expected,'unreviewed workflow inputs')
    require(value['expected_inventory']['test_cases']==2270 and
        value['expected_inventory']['suites']==227 and value['expected_inventory']['local_enable_cases']==31,
        'expected regression inventory changed')


def prepare(args):
    root=args.repo_root.resolve(); value=json.loads(args.workflow_map.read_text())
    validate_fleet(value)
    source_head=git(root,'rev-parse','HEAD')
    for row in value['applicable_workflows']:
        raw=(root/row['path']).read_bytes()
        require(sha(raw)==row['workflow_sha256'],'mapping needs refresh: '+row['workflow'])
        require(re.search(rb'^  workflow_dispatch\s*:',raw,re.M),'dispatch trigger missing')
    excluded=[]
    for row in value['excluded_pr_workflows']:
        path='.github/workflows/'+row['workflow']
        current=(root/path).read_bytes(); target=blob(root,TARGET,path)
        entry=dict(row,path=path,source_sha256=sha(current),target_sha256=sha(target),
            source_blob=git(root,'hash-object',path),target_blob=git(root,'rev-parse',TARGET+':'+path),
            qualification_pass=False)
        if 'Immutable-base route' in row['reason']:
            require(b'git cat-file -e "$PR_BASE:$workflow"' in current and
                b'run=false' in current,'excluded immutable route changed: '+path)
        else:
            require('root_job_conditions' in row,'missing explicit branch exclusions: '+path)
            for condition in row['root_job_conditions'].values():
                require(condition.encode() in current,'excluded branch predicate changed: '+path)
        excluded.append(entry)
    manifest=dict(schema=1,repository=REPOSITORY,pr=177,feature=FEATURE,target=TARGET,
        planning_source=source_head,workflow_map_sha256=sha(args.workflow_map.read_bytes()),
        applicable=value['applicable_workflows'],excluded=excluded,
        historical_replacements=[dict(requirement=a,workflow=b,run_id=c,head=d,branch=e) for a,b,c,d,e in HISTORICAL],
        expected_inventory=value['expected_inventory'],
        full_ci_started=False,qualification_pass=False)
    write(args.output,manifest)
    print(json.dumps({'manifest':str(args.output),'sha256':sha(args.output.read_bytes()),
        'applicable':52,'excluded':14,'network_requests':0}))


class Api:
    def __init__(self, token): self.token=token
    def call(self, method, path, payload=None, **query):
        require(path.startswith('/repos/'+REPOSITORY+'/'),'API escaped repository')
        url='https://api.github.com'+path
        if query:url+='?'+urllib.parse.urlencode(query)
        request=urllib.request.Request(url,method=method,
            data=None if payload is None else json.dumps(payload).encode(),
            headers={'Authorization':'Bearer '+self.token,'Accept':'application/vnd.github+json',
                'X-GitHub-Api-Version':'2022-11-28','Content-Type':'application/json',
                'User-Agent':'MorphHDL-59i-full-ci-controller'})
        # No automatic POST retry: a timeout may follow an accepted dispatch.
        with urllib.request.urlopen(request,timeout=60) as response:
            raw=response.read()
            return json.loads(raw) if raw else None
    def get(self, suffix, **query):return self.call('GET','/repos/'+REPOSITORY+suffix,**query)
    def post(self, suffix, payload):return self.call('POST','/repos/'+REPOSITORY+suffix,payload)
    def collection(self, suffix, key, **query):
        result=[]
        for page in range(1,51):
            value=self.get(suffix,per_page=100,page=page,**query)
            require(isinstance(value.get(key),list),'unexpected API envelope: '+key)
            result.extend(value[key])
            if len(value[key])<100 or len(result)>=value.get('total_count',10**9):return result
        raise RuntimeError('API pagination bound exceeded: '+suffix)


def clean_source(root, source, seal):
    require(all(re.fullmatch('[0-9a-f]{40}',v) for v in (source,seal)),'source/seal must be full immutable SHAs')
    require(git(root,'rev-parse','HEAD')==seal,'checkout is not requested seal')
    require(git(root,'show','-s','--format=%P',seal)==source,'seal must have exactly the requested source parent')
    require(not git(root,'status','--porcelain','--untracked-files=normal'),'qualification checkout is dirty')
    certificate=json.loads((root/'morphhdl/contracts/increment-59i-production-successor.json').read_text())
    require(certificate['schema_version']==4 and certificate['source_commit']==source and
        certificate['source_tree']==git(root,'rev-parse',source+'^{tree}'),'production seal does not bind requested source')
    git(root,'merge-base','--is-ancestor',TARGET,seal)
    return certificate


def guard(api, seal):
    pr=api.get('/pulls/177')
    feature=api.get('/git/ref/heads/'+urllib.parse.quote(FEATURE,safe='/'))
    target=api.get('/git/ref/heads/parameterized-verilog')
    require(pr['state']=='open' and not pr['merged'] and pr['draft'] and
        pr['head']['sha']==seal and pr['head']['ref']==FEATURE and
        pr['head']['repo']['full_name']==REPOSITORY and feature['object']['sha']==seal and
        pr['base']['ref']=='parameterized-verilog' and pr['base']['sha']==TARGET and
        target['object']['sha']==TARGET,'PR feature/target identity or draft state changed')
    return dict(head=seal,target=TARGET,pr=177,draft=True)


def require_pass(api, requirement, workflow, run_id, head, branch=FEATURE, local=False):
    run=api.get('/actions/runs/'+str(run_id))
    require(run['status']=='completed' and run['conclusion']=='success' and
        run['head_sha']==head and run['head_branch']==branch and
        (run['event']=='workflow_dispatch' or (local and run['event'] in ('push','pull_request'))) and
        run['path']=='.github/workflows/'+workflow and run['repository']['full_name']==REPOSITORY,
        'failed-first replacement is not currently passed: '+requirement)
    jobs=api.collection('/actions/runs/'+str(run_id)+'/attempts/'+str(run['run_attempt'])+'/jobs','jobs')
    require(any(j['conclusion']=='success' and len(j.get('steps',[]))>2 for j in jobs),
        'no real executed job: '+requirement)
    require(not any(j['conclusion'] not in ('success','skipped') for j in jobs),
        'unsuccessful job hidden in workflow success: '+requirement)
    if local:
        require(len(jobs)==4 and {j['name'] for j in jobs}==LOCAL_JOBS and
            all(j['conclusion']=='success' and len(j.get('steps',[]))>2 for j in jobs),
            'local-enable source, both Scala lanes and cross-Scala jobs are mandatory')
    return dict(requirement=requirement,workflow=workflow,run_id=run_id,head=head,branch=branch,
        run_attempt=run['run_attempt'],url=run['html_url'],conclusion='success',
        qualification_scope='final-head' if local else 'historical-source-only',
        jobs=[dict(id=j['id'],name=j['name'],conclusion=j['conclusion']) for j in jobs])


def same_head_runs(api, workflow_id, seal):
    rows=api.collection('/actions/workflows/'+str(workflow_id)+'/runs','workflow_runs',head_sha=seal)
    require(all(r['head_sha']==seal for r in rows),'API ignored exact-head run filter')
    for row in rows:
        require(row['head_branch']==FEATURE and row['event'] in ('push','pull_request','workflow_dispatch'),
            'unexpected same-head run origin; reconcile before dispatch')
    return sorted(rows,key=lambda r:(r['created_at'],r['id']),reverse=True)


def select_existing(rows):
    if not rows:return None
    active=[r for r in rows if r['status']!='completed']
    require(len(active)<=1,'multiple active same-head runs need reconciliation')
    if active:return active[0]
    return rows[0]


def expected_job_names(workflow):
    names=[]
    for job in workflow['jobs']:
        if job['id'] not in workflow['required_jobs']:continue
        matrix=job['matrix']
        require(isinstance(matrix,dict) and not {'include','exclude'} & set(matrix),
            'review explicit matrix shape before reuse')
        axes=list(matrix)
        for values in itertools.product(*(matrix[axis] for axis in axes)):
            name=job['name']
            for axis,value in zip(axes,values):
                name=re.sub(r'\$\{\{\s*matrix\.'+re.escape(axis)+r'\s*\}\}',str(value),name)
            if axes and job['name']==job['id']:
                name+=' ('+', '.join(map(str,values))+')'
            require('${{' not in name,'unexpanded required job name')
            names.append(name)
    require(len(names)==len(set(names)),'ambiguous required job names')
    return set(names)


def executed_success(api, run, workflow):
    jobs=api.collection('/actions/runs/'+str(run['id'])+'/attempts/'+str(run['run_attempt'])+'/jobs','jobs')
    expected=expected_job_names(workflow)
    actual={job['name']:job for job in jobs}
    require(len(actual)==len(jobs) and expected<=set(actual),'missing/duplicate required final-head jobs: '+workflow['workflow'])
    require(all(actual[name]['conclusion']=='success' and
        any(step['conclusion']=='success' for step in actual[name].get('steps',[])) for name in expected),
        'successful workflow did not execute all required jobs: '+workflow['workflow'])
    return [dict(id=actual[name]['id'],name=name,conclusion='success') for name in sorted(expected)]


def bind_run(row, run, action):
    row.update(action=action,run_id=run['id'],run_attempt=run['run_attempt'],
        status=run['status'],conclusion=run['conclusion'],url=run['html_url'])


def run_controller(args):
    root=args.repo_root.resolve(); output=args.output.resolve()
    require(root not in output.parents and output!=root,'evidence must be outside authenticated checkout')
    output.mkdir(parents=True,exist_ok=True)
    journal=output/'dispatch-journal.json'
    source=args.source_sha; seal=args.seal_sha
    receipt=dict(schema=1,mode=args.mode,repository=REPOSITORY,source=source,seal=seal,target=TARGET,
        controller_run_id=os.getenv('GITHUB_RUN_ID'),controller_attempt=os.getenv('GITHUB_RUN_ATTEMPT'),
        qualification_pass=False,post_merge_ci=False,workflows=[])
    try:
        token=os.getenv('GITHUB_TOKEN')
        require(bool(token),'GITHUB_TOKEN is required')
        require(os.getenv('GITHUB_REPOSITORY',REPOSITORY)==REPOSITORY,'wrong controller repository')
        manifest=json.loads(args.manifest.read_text())
        require(manifest['schema']==1 and manifest['repository']==REPOSITORY and manifest['pr']==177 and
            manifest['feature']==FEATURE and manifest['target']==TARGET,'wrong dispatcher manifest')
        require(len(manifest['applicable'])==52 and
            sorted(r['workflow'] for r in manifest['applicable'])==list(APPLICABLE),'52-workflow inventory changed')
        require(len(manifest['excluded'])==14 and
            sorted(r['workflow'] for r in manifest['excluded'])==list(EXCLUDED),'14 exclusions changed')
        require(manifest['historical_replacements']==[dict(requirement=a,workflow=b,run_id=c,head=d,branch=e)
            for a,b,c,d,e in HISTORICAL],'historical replacement identities changed')
        clean_source(root,source,seal)
        for row in manifest['applicable']:
            require(sha(blob(root,seal,row['path']))==row['workflow_sha256'],
                'sealed workflow differs from reviewed map: '+row['workflow'])
            expected={'mode':'checks'} if row['workflow']=='cdc-independent-parameter-consumers.yml' else {}
            require(row['dispatch_inputs']==expected,'workflow input changed')
        for row in manifest['excluded']:
            require(row['qualification_pass'] is False and
                sha(blob(root,seal,row['path']))==row['source_sha256'] and
                sha(blob(root,TARGET,row['path']))==row['target_sha256'],'exclusion source changed')
        receipt['manifest_sha256']=sha(args.manifest.read_bytes())
        write(output/'start.json',receipt)
        api=Api(token); guard(api,seal)
        replacements=[require_pass(api,*row) for row in HISTORICAL]
        replacements.append(require_pass(api,'local-enable committed head',LOCAL,args.local_enable_run,seal,local=True))
        require(len(replacements)==12,'failed-first gate incomplete')
        write(output/'failed-first-pass.json',dict(source=source,seal=seal,target=TARGET,replacements=replacements))
        # The successful same-head workflow already ran this review. Repeat its
        # exact committed-source authentication before external CI writes.
        with (output/'source-review.log').open('w') as log:
            subprocess.run([sys.executable,'-B','morphhdl/scripts/check-increment-59i-local-enable-source-review.py'],
                cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=2400)
        clean_source(root,source,seal); guard(api,seal)
        definitions=api.collection('/actions/workflows','workflows')
        selected=[]
        for row in manifest['applicable']:
            found=[d for d in definitions if d['path']==row['path'] and d['state']=='active']
            require(len(found)==1,'missing/ambiguous active workflow definition: '+row['path'])
            selected.append(dict(path=row['path'],workflow=row['workflow'],workflow_id=found[0]['id'],
                inputs=row['dispatch_inputs'],workflow_sha256=row['workflow_sha256']))
        old=None
        if journal.exists():
            old=json.loads(journal.read_text())
            require(old['source']==source and old['seal']==seal and old['target']==TARGET,
                'journal belongs to another source; use a different output directory')
        previous={r['path']:r for r in old['workflows']} if old else {}
        by_path={r['path']:r for r in manifest['applicable']}
        retry_ids=set(args.retry_failed_run or [])
        require((args.mode=='retry-failed')==bool(retry_ids),'retry-failed requires exact run selectors; other modes prohibit them')
        retry_seen=set()
        # Inspect the whole fleet before writing so an existing failed result
        # cannot trigger unrelated missing workflows ahead of its diagnosis.
        if args.mode=='dispatch':
            for row in selected:
                prior=select_existing(same_head_runs(api,row['workflow_id'],seal))
                require(not prior or prior['status']!='completed' or prior['conclusion']=='success',
                    'same-head failure/cancellation requires diagnosis before full dispatch: '+row['workflow'])
        if args.mode=='retry-failed':
            eligible=set()
            for row in selected:
                prior=select_existing(same_head_runs(api,row['workflow_id'],seal))
                if prior and prior['status']=='completed' and prior['conclusion']=='failure':eligible.add(prior['id'])
            require(retry_ids<=eligible,'retry selector is not a latest applicable exact-head failed run')
        for row in selected:
            guard(api,seal)
            rows=same_head_runs(api,row['workflow_id'],seal)
            row['all_existing_run_ids']=[r['id'] for r in rows]
            prior=select_existing(rows)
            previous_row=previous.get(row['path'],{})
            if prior:
                bind_run(row,prior,'reuse-existing')
                if prior['status']=='completed' and prior['conclusion']=='success':
                    row['executed_required_jobs']=executed_success(api,prior,by_path[row['path']])
                if args.mode=='retry-failed' and prior['id'] in retry_ids:
                    require(prior['status']=='completed' and prior['conclusion']=='failure',
                        'explicit failed-jobs retry is not a latest failed run')
                    require(previous_row.get('action') not in ('retry-failed-intent','retry-failed-requested') or
                        prior['run_attempt']>=previous_row.get('minimum_attempt',0),
                        'previous failed-jobs request is not visible yet; do not repeat it')
                    require_pass(api,'local-enable committed head',LOCAL,args.local_enable_run,seal,local=True)
                    guard(api,seal)
                    row.update(action='retry-failed-intent',minimum_attempt=prior['run_attempt']+1)
                    receipt['workflows'].append(row);write(journal,receipt)
                    api.post('/actions/runs/'+str(prior['id'])+'/rerun-failed-jobs',{})
                    row['action']='retry-failed-requested';retry_seen.add(prior['id'])
                else:
                    require(prior['status']!='completed' or prior['conclusion']=='success' or args.mode in ('plan','retry-failed'),
                        'same-head unsuccessful run exists; use explicit retry-failed after diagnosis: '+str(prior['id']))
                    receipt['workflows'].append(row)
            else:
                require(previous_row.get('action') not in ('dispatch-intent','dispatch-requested','dispatch-uncertain'),
                    'prior dispatch has no visible run yet; reconcile journal before repeating: '+row['workflow'])
                if args.mode=='retry-failed':
                    row['action']='missing-not-dispatched-in-retry-mode';receipt['workflows'].append(row)
                elif args.mode=='plan':
                    row['action']='would-dispatch';receipt['workflows'].append(row)
                else:
                    require_pass(api,'local-enable committed head',LOCAL,args.local_enable_run,seal,local=True)
                    guard(api,seal)
                    row.update(action='dispatch-intent',requested_at=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()))
                    receipt['workflows'].append(row);write(journal,receipt)
                    try:
                        api.post('/actions/workflows/'+str(row['workflow_id'])+'/dispatches',
                            dict(ref=FEATURE,**({'inputs':row['inputs']} if row['inputs'] else {})))
                    except Exception:
                        row['action']='dispatch-uncertain';write(journal,receipt);raise
                    row['action']='dispatch-requested'
            write(journal,receipt)
            print(json.dumps({k:row[k] for k in ('workflow','action','run_id','conclusion') if k in row}),flush=True)
        require(retry_seen==retry_ids,'some requested retry IDs were not latest applicable same-head failures')
        guard(api,seal);clean_source(root,source,seal)
        receipt['completed_controller']=True;receipt['applicable_count']=52;receipt['exclusion_count']=14
        receipt['excluded']=manifest['excluded'];write(journal,receipt)
        print('Controller completed; dispatch/reuse receipts are not final passing CI results.',flush=True)
    except Exception as error:
        receipt['error']=str(error);write(output/'controller-error.json',receipt)
        raise


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('prepare');p.add_argument('--repo-root',type=Path,required=True)
    p.add_argument('--workflow-map',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p=sub.add_parser('run');p.add_argument('--repo-root',type=Path,required=True)
    p.add_argument('--manifest',type=Path,required=True);p.add_argument('--source-sha',required=True)
    p.add_argument('--seal-sha',required=True);p.add_argument('--local-enable-run',type=int,required=True)
    p.add_argument('--mode',choices=('plan','dispatch','retry-failed'),default='plan')
    p.add_argument('--retry-failed-run',type=int,action='append');p.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    prepare(args) if args.command=='prepare' else run_controller(args)

if __name__=='__main__':main()
