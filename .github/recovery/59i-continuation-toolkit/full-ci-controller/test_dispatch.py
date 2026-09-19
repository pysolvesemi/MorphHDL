"""Controller failure-injection tests. All REST operations use an in-memory fake."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('dispatcher',HERE/'dispatch.py')
D=importlib.util.module_from_spec(spec);spec.loader.exec_module(D)
ROOT=Path('/workspace/scratch/68d458e0e937/59i-dev')
MANIFEST=json.loads((HERE/'dispatch-manifest.json').read_text())
SOURCE='a'*40;SEAL='b'*40;LOCAL_RUN=999
TARGET_BLOBS={r['path']:D.blob(ROOT,D.TARGET,r['path']) for r in MANIFEST['excluded']}

class Fake:
    def __init__(self):
        self.posts=[];self.fail_post=False;self.rows={};self.runs={};self.workflow={};self.jobs={}
        for index,w in enumerate(MANIFEST['applicable']):
            wid=1000+index;self.workflow[wid]=w;self.rows[wid]=[]
        for requirement,name,rid,head,branch in D.HISTORICAL:
            run=self.make_run(rid,name,head,branch)
            self.runs[rid]=run;self.jobs[rid]=self.success_jobs(['historical executed gate'])
        self.local_id=next(k for k,v in self.workflow.items() if v['workflow']==D.LOCAL)
        self.add_existing(self.local_id,LOCAL_RUN)
        self.jobs[LOCAL_RUN]=self.success_jobs(D.LOCAL_JOBS)
        self.moved=False
    def success_jobs(self,names):
        return [dict(id=i,name=n,conclusion='success',steps=[dict(conclusion='success')]*3) for i,n in enumerate(sorted(names),1)]
    def make_run(self,rid,name,head=SEAL,branch=D.FEATURE,status='completed',conclusion='success'):
        return dict(id=rid,head_sha=head,head_branch=branch,path='.github/workflows/'+name,
            status=status,conclusion=conclusion,event='workflow_dispatch',run_attempt=1,
            repository={'full_name':D.REPOSITORY},html_url='https://github.com/run/'+str(rid),created_at='2026-09-19T12:00:00Z')
    def add_existing(self,wid,rid,**kw):
        w=self.workflow[wid];run=self.make_run(rid,w['workflow'],**kw)
        self.rows[wid].append(run);self.runs[rid]=run
        self.jobs[rid]=self.success_jobs(D.expected_job_names(w))
    def get(self,path,**query):
        if path=='/pulls/177':return dict(state='open',merged=False,draft=True,head=dict(sha=SEAL,ref=D.FEATURE,repo={'full_name':D.REPOSITORY}),base=dict(ref='parameterized-verilog',sha=D.TARGET))
        if path=='/git/ref/heads/'+D.FEATURE:return {'object':{'sha':('c'*40 if self.moved else SEAL)}}
        if path=='/git/ref/heads/parameterized-verilog':return {'object':{'sha':D.TARGET}}
        if path.startswith('/actions/runs/'):return copy.deepcopy(self.runs[int(path.split('/')[3])])
        raise AssertionError(path)
    def collection(self,path,key,**query):
        if path=='/actions/workflows':return [dict(id=k,path=v['path'],state='active') for k,v in self.workflow.items()]
        if path.startswith('/actions/workflows/'):return copy.deepcopy(self.rows[int(path.split('/')[3])])
        if '/attempts/' in path:return copy.deepcopy(self.jobs[int(path.split('/')[3])])
        raise AssertionError(path)
    def post(self,path,payload):
        self.posts.append((path,payload))
        if self.fail_post:raise TimeoutError('accepted-or-not-unknown')

class Controls(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.out=Path(self.temp.name)/'receipts';self.api=Fake()
    def execute(self,mode='dispatch',retry=None):
        args=types.SimpleNamespace(repo_root=ROOT,output=self.out,manifest=HERE/'dispatch-manifest.json',source_sha=SOURCE,seal_sha=SEAL,local_enable_run=LOCAL_RUN,mode=mode,retry_failed_run=retry)
        real_blob=D.blob
        def read_blob(root,revision,path):return (root/path).read_bytes() if revision==SEAL else TARGET_BLOBS[path]
        with patch.dict('os.environ',{'GITHUB_TOKEN':'fake-token'}),patch.object(D,'Api',return_value=self.api),patch.object(D,'clean_source'),patch.object(D,'blob',side_effect=read_blob),patch.object(D.subprocess,'run'),contextlib.redirect_stdout(io.StringIO()):
            D.run_controller(args)
    def test_local_gate_failure_prevents_every_post(self):
        self.api.runs[LOCAL_RUN]['conclusion']='failure'
        with self.assertRaises(RuntimeError):self.execute()
        self.assertEqual(self.api.posts,[])
    def test_missing_cross_scala_job_prevents_every_post(self):
        self.api.jobs[LOCAL_RUN]=self.api.jobs[LOCAL_RUN][:-1]
        with self.assertRaises(RuntimeError):self.execute()
        self.assertEqual(self.api.posts,[])
    def test_historical_recovery_branch_is_pinned(self):
        self.api.runs[D.HISTORICAL[0][2]]['head_branch']=D.FEATURE
        with self.assertRaises(RuntimeError):self.execute()
        self.assertEqual(self.api.posts,[])
    def test_moved_ref_prevents_every_post(self):
        self.api.moved=True
        with self.assertRaises(RuntimeError):self.execute()
        self.assertEqual(self.api.posts,[])
    def test_read_only_plan_never_posts(self):
        self.execute('plan');self.assertEqual(self.api.posts,[])
    def test_successful_local_gate_reused_other51_only(self):
        self.execute();self.assertEqual(len(self.api.posts),51)
        self.assertNotIn('/actions/workflows/'+str(self.api.local_id)+'/dispatches',[p for p,_ in self.api.posts])
        self.assertEqual(self.api.posts[0][1],{'ref':D.FEATURE,'inputs':{'mode':'checks'}})
    def test_active_existing_workflow_is_not_duplicated(self):
        self.api.add_existing(1000,800,status='in_progress',conclusion=None)
        self.execute();self.assertEqual(len(self.api.posts),50)
    def test_existing_failure_anywhere_blocks_fleet_before_first_post(self):
        self.api.add_existing(1051,801,conclusion='failure')
        with self.assertRaises(RuntimeError):self.execute()
        self.assertEqual(self.api.posts,[])
    def test_success_run_with_skipped_required_job_is_rejected(self):
        self.api.add_existing(1000,801);self.api.jobs[801][0]['conclusion']='skipped'
        with self.assertRaises(RuntimeError):self.execute()
        self.assertEqual(self.api.posts,[])
    def test_retry_mode_only_selected_failed_jobs(self):
        self.api.add_existing(1000,801,conclusion='failure')
        self.execute('retry-failed',[801])
        self.assertEqual(self.api.posts,[('/actions/runs/801/rerun-failed-jobs',{})])
    def test_unknown_retry_selector_prevents_posts(self):
        self.api.add_existing(1000,801,conclusion='failure')
        with self.assertRaises(RuntimeError):self.execute('retry-failed',[801,99999])
        self.assertEqual(self.api.posts,[])
    def test_uncertain_dispatch_is_not_repeated_from_journal(self):
        self.api.fail_post=True
        with self.assertRaises(TimeoutError):self.execute()
        self.assertEqual(len(self.api.posts),1)
        self.api.fail_post=False
        with self.assertRaises(RuntimeError):self.execute()
        self.assertEqual(len(self.api.posts),1)

if __name__=='__main__':unittest.main(verbosity=2)
