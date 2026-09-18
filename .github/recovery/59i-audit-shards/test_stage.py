import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('stage',Path(__file__).with_name('stage.py'))
M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)

class Routes(unittest.TestCase):
 def test_only_one_workflow_and_exact_ref(self):
  self.assertTrue(M.route_allowed('POST',M.DISPATCH,{'ref':M.BRANCH}))
  for body in ({'ref':'parameterized-verilog'},{'ref':M.BRANCH,'inputs':{}},{'ref':'agent/increment-59i-combined-reduction-closure'}):
   self.assertFalse(M.route_allowed('POST',M.DISPATCH,body))
 def test_other_workflows_and_reruns_rejected(self):
  for path in ('/actions/workflows/332279971/dispatches','/actions/runs/35306242993/rerun','/actions/workflows/351947632/dispatches'):
   self.assertFalse(M.route_allowed('POST',path,{'ref':M.BRANCH}))
 def test_ref_writes_rejected(self):
  for method in ('POST','PATCH','PUT','DELETE'):
   self.assertFalse(M.route_allowed(method,'/git/refs',{'ref':M.BRANCH,'sha':M.SEAL}))
 def test_reads_exactly_bounded(self):
  for p in M.EXTRA_READS | {M.RUNS,'/git/trees/'+M.SEAL_TREE,'/actions/runs/'+str(M.FAILED_RUN)}:
   self.assertTrue(M.route_allowed('GET',p))
   self.assertFalse(M.route_allowed('GET',p,{}))
  self.assertFalse(M.route_allowed('GET','/git/trees/'+'0'*40))
 def test_configure_preserves_checks_and_exact_topology(self):
  m=SimpleNamespace(CHECKS=['original-a','original-b'],api=lambda *a:None)
  M.configure(m)
  self.assertEqual(m.CHECKS[:2],['original-a','original-b'])
  self.assertEqual(m.SELECTED,{M.WORKFLOW:M.FAILED_RUN})
  self.assertEqual(m.TARGET,M.INTEGRATED_TARGET)
  self.assertNotEqual(m.TARGET,M.OBSERVED_TARGET)
 def fixture(self):
  state={
   '/pulls/177':{'state':'open','merged':False,'head':{'sha':M.FEATURE,'ref':'agent/increment-59i-combined-reduction-closure','repo':{'full_name':M.REPO}},'base':{'ref':'parameterized-verilog'}},
   '/git/ref/heads/agent/increment-59i-combined-reduction-closure':{'object':{'sha':M.FEATURE}},
   '/git/ref/heads/parameterized-verilog':{'object':{'sha':M.OBSERVED_TARGET}},
   '/git/ref/heads/'+M.BRANCH:{'object':{'sha':M.SEAL}},
   '/actions/runs/'+str(M.FAILED_RUN):{'workflow_id':M.WORKFLOW,'head_sha':M.PARENT,'status':'completed','conclusion':'cancelled'},
   M.RUNS:{'total_count':0,'workflow_runs':[]}}
  calls=[]
  def api(method,path,body=None):
   self.assertTrue(M.route_allowed(method,path,body));calls.append((method,path,body))
   return state[path] if method=='GET' else None
  return SimpleNamespace(api=api,git=lambda *a:(M.INTEGRATED_TARGET+'\n').encode()),state,calls
 def test_observed_target_not_falsely_integrated(self):
  m,_,_=self.fixture();v=M.remote_preflight(m,Path('.'))
  self.assertTrue(v['target_not_integrated']);self.assertFalse(v['final_head_qualification'])
 def test_target_movement_rejected(self):
  m,s,_=self.fixture();s['/git/ref/heads/parameterized-verilog']['object']['sha']='0'*40
  with self.assertRaisesRegex(RuntimeError,'target moved'):M.remote_preflight(m,Path('.'))
 def test_feature_movement_rejected(self):
  m,s,_=self.fixture();s['/pulls/177']['head']['sha']='0'*40
  with self.assertRaisesRegex(RuntimeError,'PR identity'):M.remote_preflight(m,Path('.'))
 def test_wrong_failure_or_new_success_rejected(self):
  for field,value in (('head_sha',M.SEAL),('conclusion','success'),('workflow_id',99),('status','in_progress')):
   m,s,_=self.fixture();s['/actions/runs/'+str(M.FAILED_RUN)][field]=value
   with self.assertRaisesRegex(RuntimeError,'timeout evidence'):M.remote_preflight(m,Path('.'))
 def test_unknown_target_ancestry_rejected(self):
  m,_,_=self.fixture();m.git=lambda *a:b'0'*40+b'\n'
  with self.assertRaisesRegex(RuntimeError,'direct successor'):M.remote_preflight(m,Path('.'))
 def test_single_dispatch(self):
  m,_,calls=self.fixture()
  with tempfile.TemporaryDirectory() as d:
   r=M.dispatch_once(m,Path('.'),Path(d),{})
   self.assertTrue(r['dispatches'][0]['dispatched'])
  self.assertEqual([c for c in calls if c[0]=='POST'],[('POST',M.DISPATCH,{'ref':M.BRANCH})])
 def test_duplicate_run_not_dispatched(self):
  for status in ('in_progress','completed'):
   m,s,calls=self.fixture();s[M.RUNS]={'total_count':1,'workflow_runs':[{'id':123,'head_sha':M.SEAL,'workflow_id':M.WORKFLOW,'event':'workflow_dispatch','status':status}]}
   with tempfile.TemporaryDirectory() as d:r=M.dispatch_once(m,Path('.'),Path(d),{})
   self.assertFalse(r['dispatches'][0]['dispatched']);self.assertFalse(any(c[0]=='POST' for c in calls))
 def test_truncated_or_foreign_inventory_rejected(self):
  for inventory in ({'total_count':1,'workflow_runs':[]},{'total_count':1,'workflow_runs':[{'head_sha':M.PARENT}]}):
   m,s,calls=self.fixture();s[M.RUNS]=inventory
   with tempfile.TemporaryDirectory() as d:
    with self.assertRaises(RuntimeError):M.dispatch_once(m,Path('.'),Path(d),{})
   self.assertFalse(any(c[0]=='POST' for c in calls))
 def test_wrong_validation_ref_rejected(self):
  m,s,calls=self.fixture();s['/git/ref/heads/'+M.BRANCH]['object']['sha']=M.PARENT
  with tempfile.TemporaryDirectory() as d:
   with self.assertRaisesRegex(RuntimeError,'validation ref'):M.dispatch_once(m,Path('.'),Path(d),{})
  self.assertFalse(any(c[0]=='POST' for c in calls))
 def test_real_bundle_checksum(self):
  raw=M.bundle_bytes(Path(__file__).parent)
  self.assertTrue(raw.startswith(b'# v2 git bundle\n'))
  self.assertIn(M.SEAL.encode()+b' refs/heads/local/59i-audit-shards-sealed',raw[:400])
 def test_corrupt_bundle_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   for n in M.PARTS:(Path(d)/n).write_bytes((Path(__file__).parent/n).read_bytes())
   f=Path(d)/M.PARTS[-1];b=bytearray(f.read_bytes());b[-5]=ord('A') if b[-5]!=ord('A') else ord('B');f.write_bytes(b)
   with self.assertRaisesRegex(RuntimeError,'checksum'):M.bundle_bytes(Path(d))

if __name__=='__main__':unittest.main(verbosity=2)
