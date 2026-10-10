"""Narrow increment controller. Render CONFIG_SHA256 before publication."""
import base64,hashlib,json,os,pathlib,time,urllib.request,urllib.error
CONFIG_SHA256='6767043d3754ff3233d53c4cdce9ef40a10bbeddeacb73f771b3146f307ec5b6'
class RejectRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs): raise RuntimeError('API redirect rejected')
class Controller:
 def __init__(self,cfg,request,ledger):self.c=cfg;self.request=request;self.ledger=ledger
 def log(self,event,**data):
  with self.ledger.open('a') as f:f.write(json.dumps({'time':time.time(),'event':event,**data})+'\n');f.flush();os.fsync(f.fileno())
 def api(self,method,path,body=None):
  prefix='/repos/'+self.c['repository']
  assert path.startswith(prefix+'/') and '..' not in path.split('/')
  if method=='POST':
   assert path in {prefix+'/actions/workflows/'+str(w['id'])+'/dispatches' for w in self.c['workflows']}
   assert body=={'ref':self.c['candidate_ref']}
  else:assert method=='GET' and body is None
  return self.request(method,path,body)
 def get(self,suffix):return self.api('GET','/repos/'+self.c['repository']+suffix)
 def identities(self):
  c=self.c;p=self.get('/pulls/'+str(c['pr']))
  assert p['state']=='open' and not p['merged']
  for side,ref,sha in [('head',c['candidate_ref'],c['candidate_sha']),('base',c['base_ref'],c['base_sha'])]:
   assert p[side]['repo']['full_name']==c['repository'] and p[side]['ref']==ref and p[side]['sha']==sha
   assert self.get('/git/ref/heads/'+ref)['object']['sha']==sha
  assert self.get('/git/commits/'+c['candidate_sha'])['tree']['sha']==c['candidate_tree']
 def workflow(self,w):
  meta=self.get('/actions/workflows/'+str(w['id']))
  assert meta['id']==w['id'] and meta['path']==w['path'] and meta['state']=='active'
  file=self.get('/contents/'+w['path']+'?ref='+self.c['candidate_sha'])
  assert file['type']=='file' and file['encoding']=='base64'
  assert hashlib.sha256(base64.b64decode(file['content'])).hexdigest()==w['sha256']
  assert w['payload']=={'ref':self.c['candidate_ref']}
 def runs(self,w):
  result=[];total=None
  for page in range(1,11):
   data=self.get('/actions/workflows/'+str(w['id'])+'/runs?event=workflow_dispatch&head_sha='+self.c['candidate_sha']+'&per_page=100&page='+str(page))
   if total is None:total=data['total_count'];assert total<=1000
   assert total==data['total_count'];items=data['workflow_runs'];result+=items
   if len(items)<100:break
  assert len(result)==total and len({x['id'] for x in result})==total
  for run in result:
   assert run['workflow_id']==w['id'] and run['event']=='workflow_dispatch'
   assert run['head_sha']==self.c['candidate_sha'] and run['head_branch']==self.c['candidate_ref']
  return result
 def run(self):
  c=self.c;assert c['workflows'] and len({w['id'] for w in c['workflows']})==len(c['workflows'])
  self.identities()
  ancestor=self.get('/compare/'+c['base_sha']+'...'+c['candidate_sha'])
  assert ancestor['merge_base_commit']['sha']==c['base_sha']
  for e in c.get('original_failures',[]):
   r=self.get('/actions/runs/'+str(e['run_id']))
   assert (r['workflow_id'],r['head_sha'],r['conclusion'])==(e['workflow_id'],e['head_sha'],'failure')
  for w in c['workflows']:self.workflow(w)
  for w in c['workflows']:
   self.identities();self.workflow(w);runs=self.runs(w)
   active=[r for r in runs if r['status']!='completed' or r['conclusion']=='success']
   if active:self.log('retained',workflow_id=w['id'],runs=[r['id'] for r in active]);continue
   assert not runs,'Previous unsuccessful same-head run requires explicit review'
   self.identities();self.log('dispatch-attempt',workflow_id=w['id'],payload=w['payload'])
   self.api('POST','/repos/'+c['repository']+'/actions/workflows/'+str(w['id'])+'/dispatches',w['payload'])
   self.log('dispatch-accepted',workflow_id=w['id']);self.identities()
def main():
 raw=pathlib.Path(__file__).with_name('dispatch.json').read_bytes();assert hashlib.sha256(raw).hexdigest()==CONFIG_SHA256
 c=json.loads(raw)
 assert os.environ['GITHUB_EVENT_NAME']=='push' and os.environ['GITHUB_REPOSITORY']==c['repository']
 assert os.environ['GITHUB_REF']=='refs/heads/'+c['control_ref']
 opener=urllib.request.build_opener(RejectRedirect())
 def request(method,path,body):
  req=urllib.request.Request('https://api.github.com'+path,data=json.dumps(body).encode() if body is not None else None,method=method,headers={'Authorization':'Bearer '+os.environ['GITHUB_TOKEN'],'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','Content-Type':'application/json'})
  with opener.open(req,timeout=30) as r:
   raw=r.read();return json.loads(raw) if raw else None
 controller=Controller(c,request,pathlib.Path('dispatch-ledger.jsonl'))
 controller.log('controller-start',head=os.environ['GITHUB_SHA'])
 sha=os.environ['GITHUB_SHA'];commit=controller.get('/git/commits/'+sha)
 assert [p['sha'] for p in commit['parents']]==[c['base_sha']]
 assert controller.get('/git/ref/heads/'+c['control_ref'])['object']['sha']==sha
 compare=controller.get('/compare/'+c['base_sha']+'...'+sha)
 assert {f['filename'] for f in compare['files']}==set(c['control_files'])
 assert all(f['status']=='added' for f in compare['files'])
 try:controller.run()
 except Exception as e:controller.log('stopped',reason=str(e));raise
if __name__=='__main__':main()
