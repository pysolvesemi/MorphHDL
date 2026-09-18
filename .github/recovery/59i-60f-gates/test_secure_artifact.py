import importlib.util, tempfile, unittest, urllib.request
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('wrapper',Path(__file__).with_name('secure_artifact.py'))
S=importlib.util.module_from_spec(spec);spec.loader.exec_module(S)
class Tests(unittest.TestCase):
 def test_https_redirect_removes_credentials(self):
  request=urllib.request.Request('https://api.github.com/repos/pysolvesemi/MorphHDL/actions/artifacts/10557586706/zip',headers={'Authorization':'Bearer test','Cookie':'a=b','Proxy-Authorization':'other','Accept':'application/zip'})
  for target in ('https://example.blob.core.windows.net/file?sig=opaque','https://api.github.com/redirect'):
   value=S.ArtifactRedirect().redirect_request(request,None,302,'Found',{},target)
   self.assertEqual(value.full_url,target)
   self.assertEqual({k.lower() for k in value.headers},{'accept'})
   self.assertEqual(request.headers['Authorization'],'Bearer test')
 def test_non_https_and_userinfo_rejected(self):
  request=urllib.request.Request('https://api.github.com/')
  for target in ('http://example.org/a','file:///etc/passwd','https://a:b@example.org/a','https:///missing'):
   with self.assertRaisesRegex(RuntimeError,'Unsafe'):S.ArtifactRedirect().redirect_request(request,None,302,'Found',{},target)
 def test_unchanged_source_identity_and_exact_workflow(self):
  m=S.configure()
  self.assertEqual(m.SEAL,'90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6')
  self.assertEqual(m.WORKFLOW,351327055);self.assertEqual(m.FAILED,35363157513)
  self.assertEqual(len(m.CHECKS),15)
 def test_no_ref_write_or_other_workflow(self):
  m=S.configure()
  for method in ('POST','PATCH','PUT','DELETE'):
   self.assertFalse(m.allowed(method,'/git/refs/heads/'+m.FEATURE,{'sha':m.SEAL}))
  self.assertFalse(m.allowed('POST','/actions/workflows/358545231/dispatches',{'ref':m.BRANCH}))
  self.assertTrue(m.allowed('POST',m.DISPATCH,{'ref':m.BRANCH}))
  self.assertFalse(m.allowed('POST',m.DISPATCH,{'ref':m.FEATURE}))
 def test_corrupt_cached_artifact_rejected(self):
  m=S.configure()
  with tempfile.TemporaryDirectory() as d:
   out=Path(d);(out/'original-regressions.zip').write_bytes(b'not the archive')
   with self.assertRaisesRegex(RuntimeError,'checksum'):m.artifact(out)
 def test_wrong_repository_rejected_before_network(self):
  m=S.configure()
  with tempfile.TemporaryDirectory() as d,patch.dict('os.environ',{'GITHUB_REPOSITORY':'foreign/repo'}):
   with self.assertRaisesRegex(RuntimeError,'wrong artifact'):m.artifact(Path(d))
if __name__=='__main__':unittest.main(verbosity=2)
