import importlib.util
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('dispatcher',Path(__file__).with_name('dispatch.py'))
D=importlib.util.module_from_spec(spec);spec.loader.exec_module(D)
class Tests(unittest.TestCase):
 def fixture(self):
  state={'/pulls/177':dict(state='open',merged=False,draft=True,head=dict(sha=D.HEAD,ref=D.FEATURE,repo=dict(full_name=D.REPO)),base=dict(ref='parameterized-verilog'))}
  for branch,sha in ((D.FEATURE,D.HEAD),(D.BRANCH,D.HEAD),('parameterized-verilog',D.TARGET)):
   state['/git/ref/heads/'+branch]={'object':{'sha':sha}}
  for w,r in D.SELECTED.items():
   state['/actions/runs/'+str(r)]=dict(id=r,workflow_id=w,head_sha=D.FAILED_HEAD,status='completed',conclusion='failure')
   state[D.run_path(w)]=dict(total_count=0,workflow_runs=[])
  calls=[]
  def request(method,path,body=None):
   self.assertTrue(D.allowed(method,path,body));calls.append((method,path,body))
   return state[path] if method=='GET' else None
  return state,calls,request
 def run_fixture(self,request):
  with tempfile.TemporaryDirectory() as d:return D.dispatch(Path(d),request)
 def test_exact_five_original_requirements(self):
  self.assertEqual(D.SELECTED,{350978616:35138263862,351370311:35138263964,351397918:35138263633,351996892:35138263581,351327055:35138263797})
 def test_only_five_dispatches(self):
  _,calls,request=self.fixture();result=self.run_fixture(request)
  self.assertEqual(len([c for c in calls if c[0]=='POST']),5)
  self.assertFalse(result['full_ci']);self.assertFalse(result['refs_updated'])
 def test_existing_successes_and_pending_runs_are_retained(self):
  for status,conclusion in [('queued',None),('in_progress',None),('completed','success'),('completed','failure')]:
   state,calls,request=self.fixture()
   for w in D.SELECTED:state[D.run_path(w)]=dict(total_count=1,workflow_runs=[dict(id=w,workflow_id=w,head_sha=D.HEAD,event='workflow_dispatch',status=status,conclusion=conclusion)])
   self.run_fixture(request);self.assertFalse(any(c[0]=='POST' for c in calls))
 def test_successful_original_refuses_all_dispatches(self):
  state,calls,request=self.fixture();state['/actions/runs/'+str(list(D.SELECTED.values())[-1])]['conclusion']='success'
  with self.assertRaisesRegex(RuntimeError,'evidence differs'):self.run_fixture(request)
  self.assertFalse(any(c[0]=='POST' for c in calls))
 def test_target_movement_rejected(self):
  state,calls,request=self.fixture();state['/git/ref/heads/parameterized-verilog']['object']['sha']='0'*40
  with self.assertRaisesRegex(RuntimeError,'ref changed'):self.run_fixture(request)
  self.assertFalse(any(c[0]=='POST' for c in calls))
 def test_feature_movement_rejected(self):
  state,calls,request=self.fixture();state['/git/ref/heads/'+D.FEATURE]['object']['sha']='0'*40
  with self.assertRaisesRegex(RuntimeError,'ref changed'):self.run_fixture(request)
 def test_wrong_validation_ref_rejected(self):
  state,calls,request=self.fixture();state['/git/ref/heads/'+D.BRANCH]['object']['sha']='0'*40
  with self.assertRaisesRegex(RuntimeError,'ref changed'):self.run_fixture(request)
 def test_foreign_original_workflow_or_head_rejected(self):
  for field,val in [('head_sha',D.HEAD),('workflow_id',99),('id',99),('status','queued'),('conclusion','cancelled')]:
   state,calls,request=self.fixture();state['/actions/runs/'+str(next(iter(D.SELECTED.values())))][field]=val
   with self.assertRaises(RuntimeError):self.run_fixture(request)
   self.assertFalse(any(c[0]=='POST' for c in calls))
 def test_truncated_inventory_rejected(self):
  state,calls,request=self.fixture();state[D.run_path(next(iter(D.SELECTED)))]['total_count']=1
  with self.assertRaisesRegex(RuntimeError,'truncated'):self.run_fixture(request)
  self.assertFalse(any(c[0]=='POST' for c in calls))
 def test_foreign_inventory_rejected(self):
  state,calls,request=self.fixture();w=next(iter(D.SELECTED));state[D.run_path(w)]=dict(total_count=1,workflow_runs=[dict(id=1,workflow_id=w,head_sha=D.FAILED_HEAD,event='workflow_dispatch')])
  with self.assertRaisesRegex(RuntimeError,'foreign'):self.run_fixture(request)
 def test_ref_mutations_never_allowed(self):
  for method in ('POST','PUT','PATCH','DELETE'):
   self.assertFalse(D.allowed(method,'/git/refs/heads/'+D.FEATURE,{'sha':D.HEAD}))
 def test_successful_workflows_and_local_enable_disallowed(self):
  for w in (332279971,332476711,353947848,357674356,351947632,358545231,358545233):
   self.assertFalse(D.allowed('POST','/actions/workflows/%d/dispatches'%w,{'ref':D.BRANCH}))
 def test_other_refs_inputs_and_reruns_disallowed(self):
  w=next(iter(D.SELECTED))
  for body in ({'ref':'parameterized-verilog'},{'ref':D.FEATURE},{'ref':D.BRANCH,'inputs':{}},{}):
   self.assertFalse(D.allowed('POST','/actions/workflows/%d/dispatches'%w,body))
  self.assertFalse(D.allowed('POST','/actions/runs/35138263862/rerun',{}))
 def test_merged_closed_or_ready_pr_rejected(self):
  for field,val in [('merged',True),('draft',False),('state','closed')]:
   state,calls,request=self.fixture();state['/pulls/177'][field]=val
   with self.assertRaisesRegex(RuntimeError,'PR identity'):self.run_fixture(request)
 def test_identity_rechecked_after_first_dispatch(self):
  state,calls,request=self.fixture()
  def moved(method,path,body=None):
   value=request(method,path,body)
   if method=='POST':state['/git/ref/heads/'+D.FEATURE]['object']['sha']='0'*40
   return value
  with self.assertRaisesRegex(RuntimeError,'ref changed'):self.run_fixture(moved)
  self.assertEqual(len([c for c in calls if c[0]=='POST']),1)
if __name__=='__main__':unittest.main(verbosity=2)
