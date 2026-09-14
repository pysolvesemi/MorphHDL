import base64, hashlib, importlib.util, unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('transport',Path(__file__).with_name('stage_database.py'))
M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)
def oid(raw):return hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
class Tests(unittest.TestCase):
 def fixture(self):
  data={'.github/workflows/a.yml':b'name: qualified\n','script.py':b'print(1)\n'}
  before={'unchanged':('100644','blob',oid(b'x')),'removed':('100644','blob',oid(b'y'))}
  after={k:('100644','blob',oid(v)) for k,v in data.items()};after['unchanged']=before['unchanged']
  calls=[]
  def api(method,path,value):
   calls.append((method,path,value));return {'sha':oid(base64.b64decode(value['content'],validate=True))}
  mod=SimpleNamespace(tree=lambda r,v:before if v=='before' else after,
   blob=lambda r,t,p:data[p],api=api,git=lambda *a:b'f'*40+b'\n')
  return mod,calls,after
 def test_exact_blob_upload_and_tree_request(self):
  m,calls,_=self.fixture();result=M.request_for(m,Path('.'),'before','after','e'*40,set())
  self.assertEqual([x['path'] for x in result['tree']],['.github/workflows/a.yml','removed','script.py'])
  self.assertIsNone(result['tree'][1]['sha']);self.assertEqual(len(calls),2)
  self.assertTrue(all((a,b)==('POST','/git/blobs') for a,b,_ in calls))
 def test_duplicate_blob_not_uploaded_again(self):
  m,calls,new=self.fixture();M.request_for(m,Path('.'),'before','after','e'*40,{new['script.py'][2]});self.assertEqual(len(calls),1)
 def test_wrong_uploaded_identity_rejected(self):
  m,_,_=self.fixture();m.api=lambda *a:{'sha':'0'*40}
  with self.assertRaisesRegex(RuntimeError,'Uploaded blob identity'):M.request_for(m,Path('.'),'before','after','e'*40,set())
 def test_non_blob_entry_rejected(self):
  m,_,after=self.fixture();after['script.py']=('160000','commit','0'*40)
  with self.assertRaisesRegex(RuntimeError,'Unsupported changed object'):M.request_for(m,Path('.'),'before','after','e'*40,set())
 def test_existing_tree_accepted(self):M.wait_for_tree(SimpleNamespace(api=lambda *a:{'sha':'e'*40,'truncated':False}),'e'*40)
 def test_permission_denial_is_not_retried(self):
  count=[]
  def api(*a):count.append(1);raise RuntimeError('GitHub object staging HTTP 403: denied')
  with self.assertRaisesRegex(RuntimeError,'403'):M.wait_for_tree(SimpleNamespace(api=api),'e'*40)
  self.assertEqual(count,[1])
 def test_missing_tree_times_out_as_failure(self):
  def api(*a):raise RuntimeError('GitHub object staging HTTP 404: missing')
  with self.assertRaisesRegex(RuntimeError,'not available'):M.wait_for_tree(SimpleNamespace(api=api),'e'*40,seconds=0)
 def test_missing_then_created_tree_accepted(self):
  calls=[]
  def api(*a):
   calls.append(1)
   if len(calls)==1:raise RuntimeError('GitHub object staging HTTP 404: missing')
   return {'sha':'e'*40}
  with patch.object(M.time,'sleep',lambda s:None):M.wait_for_tree(SimpleNamespace(api=api),'e'*40)
  self.assertEqual(len(calls),2)
 def test_wrong_tree_rejected(self):
  with self.assertRaisesRegex(RuntimeError,'Wrong'):M.wait_for_tree(SimpleNamespace(api=lambda *a:{'sha':'0'*40}),'e'*40)
 def test_truncated_tree_rejected(self):
  with self.assertRaisesRegex(RuntimeError,'truncated'):M.wait_for_tree(SimpleNamespace(api=lambda *a:{'sha':'e'*40,'truncated':True}),'e'*40)
if __name__=='__main__':unittest.main(verbosity=2)
