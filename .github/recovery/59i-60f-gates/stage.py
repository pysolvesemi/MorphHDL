#!/usr/bin/env python3
"""Reproduce exact 60f gate repair; dispatch only the original failed workflow."""
from __future__ import annotations
import argparse,base64,copy,difflib,hashlib,io,json,lzma,os,re,subprocess,sys,time,types,zipfile
import urllib.request,urllib.error
import xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime,timezone
REPO='pysolvesemi/MorphHDL'
PARENT='f42641880e0645f0c997ecedabd031bf8948bbfa'
TARGET='e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d'
SOURCE='1ed8930326497aa43a98c2491468d4cb75e59f3a'
SEAL='90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6'
SOURCE_TREE='1597feb63688159ece99a14eca228cc56350b67e'
SEAL_TREE='1b8bf66047a28b836d61f6b168d28dd3b8f95cb2'
BASE='954d9b2763b064dba60af71ad8fa509a9d7cada8'
HISTORICAL='5374b958f8f94114b1ed46a3069845d580886da9'
HELPER='morphhdl/scripts/check-increment-59i-production-successor.py'
CONTRACT='morphhdl/contracts/increment-59i-production-successor.json'
INVENTORY='morphhdl/contracts/increment-59i-regression-inventory.json'
PAYLOAD='d861c0c4e863cc8344d214a85cc19fc09970a2059c63c1967bdaa729d1ff983a'
ARTIFACT=10557586706
ARTIFACT_SHA='dfd1b37f5e5db1be03a6b20425f0d77078b44a9bd51f332afa44563e25ebac01'
BRANCH='recovery/increment-59i-60f-gates-90b7fc8f'
FEATURE='agent/increment-59i-combined-reduction-closure'
WORKFLOW=351327055
FAILED=35363157513
CHECKS=['check-increment-59i-production-successor.py','check-increment-61-source-review.py',
 'check-native-source-preservation.py','check-typed-native-source-overlay.py',
 'check-increment-59i-target-integration.py','check-increment-62-wa08-source-overlay.py',
 'check-wa10-source-scope.py','check-increment-59i-widening-source-review.py',
 'check-increment-59i-rollout-composition.py','check-increment-60b-signedness-authority.py',
 'check-cdc-successor-source.py','check-lane-when-source-scope.py',
 'test-increment-59i-inherited-audit-budgets.py','test-increment-59i-regression-inventory.py',
 'test-increment-59i-production-successor.py']
RUNS='/actions/workflows/%d/runs?head_sha=%s&event=workflow_dispatch&per_page=100'%(WORKFLOW,SEAL)
DISPATCH='/actions/workflows/%d/dispatches'%WORKFLOW
READS={'/git/trees/'+SOURCE_TREE,'/git/trees/'+SEAL_TREE,'/git/ref/heads/'+BRANCH,
 '/git/ref/heads/'+FEATURE,'/git/ref/heads/parameterized-verilog','/actions/runs/'+str(FAILED),RUNS}
def require(ok,msg):
 if not ok:raise RuntimeError('59i exact60f staging: '+msg)
def digest(raw):return hashlib.sha256(raw).hexdigest()
def git(r,*args,input=None):
 p=subprocess.run(['git','--literal-pathspecs',*args],cwd=r,input=input,capture_output=True,timeout=180)
 require(p.returncode==0,'git '+str(args)+': '+p.stderr.decode(errors='replace'));return p.stdout
def allowed(method,path,body=None):
 return (method=='GET' and path in READS and body is None) or (method=='POST' and
  (path in ('/git/blobs','/git/commits') or (path==DISPATCH and body=={'ref':BRANCH})))
def api(method,path,body=None):
 require(allowed(method,path,body),'operation outside exact allowlist')
 require(os.environ.get('GITHUB_REPOSITORY')==REPO,'wrong authorized repository')
 req=urllib.request.Request('https://api.github.com/repos/'+REPO+path,method=method,
  data=None if body is None else json.dumps(body).encode(),headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],
  'Accept':'application/vnd.github+json','Content-Type':'application/json','X-GitHub-Api-Version':'2022-11-28'})
 with urllib.request.urlopen(req,timeout=120) as response:
  raw=response.read();return json.loads(raw) if raw else None
def identity():
 require(api('GET','/git/ref/heads/'+FEATURE)['object']['sha']==PARENT,'feature changed')
 require(api('GET','/git/ref/heads/parameterized-verilog')['object']['sha']==TARGET,'target changed')
 v=api('GET','/actions/runs/'+str(FAILED))
 require(v['id']==FAILED and v['head_sha']==PARENT and v['workflow_id']==WORKFLOW and v['status']=='completed' and v['conclusion']=='failure','original failed run differs')
def artifact(out):
 file=out/'original-regressions.zip'
 if not file.exists():
  require(os.environ.get('GITHUB_REPOSITORY')==REPO,'wrong artifact repository')
  request=urllib.request.Request('https://api.github.com/repos/'+REPO+'/actions/artifacts/'+str(ARTIFACT)+'/zip',
   headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json'})
  with urllib.request.urlopen(request,timeout=120) as response:file.write_bytes(response.read())
 require(digest(file.read_bytes())==ARTIFACT_SHA,'original artifact checksum mismatch');return file

def inventory(r,archive):
 projects={}
 with zipfile.ZipFile(archive) as z:
  for name in z.namelist():
   if not name.startswith('__w/MorphHDL/MorphHDL/') or '/target/test-reports/' not in name or not name.endswith('.xml'):continue
   project=name.removeprefix('__w/MorphHDL/MorphHDL/').split('/target/test-reports/')[0]
   suite=ET.fromstring(z.read(name));n=suite.attrib['name'];d=projects.setdefault(project,{})
   require(n not in d,'duplicate reference suite');d[n]=sorted(case.attrib['name'] for case in suite.findall('testcase'))
 require(sum(len(d) for d in projects.values())==226,'reference report set differs')
 lexical='spinal.core.internals.ParameterizedVerilogStructuralLexicalTests'
 closed='spinal.core.internals.TypedBalancedReductionClosedGraphTests'
 projects['morphhdl'][lexical].append('signed declaration qualifiers never connect independent dependency sets')
 projects['morphhdl'][lexical].sort()
 original=git(r,'show',HISTORICAL+':morphhdl/scripts/check-increment-60f-artifacts.py')
 m=types.ModuleType('immutable_catalog');m.__file__=str(r/'morphhdl/scripts/check-increment-60f-artifacts.py');exec(compile(original,m.__file__,'exec'),m.__dict__)
 m.closure_module=lambda:types.SimpleNamespace(profile_features=lambda p:m.WA09_INHERITED_FEATURES)
 _,_,counts=m.catalog_for_profile('known',True,True,True,True,True,lane_when=True,pr188=True)
 retain={}
 for name in (lexical,closed):
  text=git(r,'show',HISTORICAL+':morphhdl/src/test/scala/'+name.replace('.','/')+'.scala').decode()
  retain[name]=sorted(re.findall(r'\btest\("([^"\n]+)"\)',text))
 sources={p:digest((r/p).read_bytes()) for p in git(r,'ls-tree','-r','--name-only',PARENT).decode().splitlines() if '/src/test/' in p and p.endswith('.scala')}
 return (json.dumps(dict(schema=1,source_basis=PARENT,historical_base=HISTORICAL,historical_checker_sha256=digest(original),projects=projects,historical_counts=counts,retained_cases=retain,test_source_sha256=sources,increment61={'morphhdl.Increment61CompatibilityMatrixTests':4,'morphhdl.Increment61PerComponentPublicationTests':16}),indent=2,sort_keys=True)+'\n').encode()
def load():
 file=Path(__file__).with_name('payload.b64');require(file.is_file() and not file.is_symlink(),'regular payload required')
 raw=base64.b64decode(file.read_bytes(),validate=True);require(digest(raw)==PAYLOAD,'payload checksum mismatch');v=json.loads(lzma.decompress(raw))
 for name in ('source','seal','source_tree','seal_tree','parent','target'):require(v[name]==globals()[name.upper()],'changed exact '+name)
 return v
def rebuild(repo,r,out,v):
 require(not r.exists(),'destination exists');git(repo,'worktree','add','--detach',str(r),PARENT)
 paths=[e['path'] for e in v['files']];require(len(paths)==16 and paths==sorted(set(paths)),'source inventory mismatch')
 deferred=None
 for e in v['files']:
  p=e['path'];require(p and not Path(p).is_absolute() and not ({'..','.git'}&set(Path(p).parts)),'unsafe source path')
  f=r/p;require(not any((r/Path(*Path(p).parts[:i])).is_symlink() for i in range(1,len(Path(p).parts)+1)),'linked source')
  before=f.read_bytes() if f.exists() else b'';require(digest(before)==e['before_sha256'],'wrong baseline: '+p)
  if 'from_failure_artifact' in e:
   require(p==INVENTORY and e['from_failure_artifact']==ARTIFACT,'unexpected deferred source');deferred=e;continue
  parts=[];end=0
  for start,stop,text in e['edits']:
   require(type(start)is int and type(stop)is int and end<=start<=stop<=len(before),'invalid exact edit')
   parts.extend([before[end:start],text.encode()]);end=stop
  after=b''.join(parts)+before[end:];require(digest(after)==e['after_sha256'],'wrong patched source: '+p)
  f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(after)
 require(deferred is not None,'missing inventory reconstruction')
 raw=inventory(r,artifact(out));require(digest(raw)==deferred['after_sha256'],'reviewed inventory bytes mismatch');(r/INVENTORY).write_bytes(raw)
 git(r,'add','-f','--',*paths);require(git(r,'write-tree').decode().strip()==SOURCE_TREE,'source tree mismatch')
 require(git(r,'hash-object','-t','commit','-w','--stdin',input=v['source_raw'].encode()).decode().strip()==SOURCE,'raw source mismatch');git(r,'reset','--hard',SOURCE)
 h=(r/HELPER).read_bytes();m=types.ModuleType('reviewed_sealer');m.__file__=str(r/HELPER);exec(compile(h,m.__file__,'exec'),m.__dict__)
 old=json.loads(git(r,'show',PARENT+':'+CONTRACT));prior={e['path']:e for e in old['files']};current=m.tree(r,SOURCE)
 def spans(p,a,b):
  aa=a.splitlines(keepends=True);bb=b.splitlines(keepends=True);oa=[0];ob=[0]
  for s in aa:oa.append(oa[-1]+len(s))
  for s in bb:ob.append(ob[-1]+len(s))
  result=[]
  for tag,i,j,k,l in difflib.SequenceMatcher(None,aa,bb,autojunk=False).get_opcodes():
   if tag!='equal':result.append(dict(id='60f-repair:'+p+':'+str(len(result)+1),reason='Exact reviewed 60f gate repair; original testcase and source obligations retained.',before_start=oa[i],before_end=oa[j],after_start=ob[k],after_end=ob[l],before=a[oa[i]:oa[j]].decode(),after=b[ob[k]:ob[l]].decode()))
  return result
 def record(p,ref,bt,previous=None):
  a,b=m.frozen(r,ref,p),m.frozen(r,SOURCE,p)
  ids=dict(path=p,before_mode=bt.get(p,(None,))[0],after_mode=current.get(p,(None,))[0],before_sha256=None if a is None else digest(a),after_sha256=None if b is None else digest(b))
  if previous and all(previous[k]==val for k,val in ids.items()):return copy.deepcopy(previous)
  e=dict(**ids,reason='60f failed-first source-bound diagnostic and inventory repair; original native/runtime source retained.',edits=spans(p,a or b'',b or b''))
  require(m.restore_reviewed(e,a or b'',b or b'')==(a or b''),'review reversal mismatch');return e
 value=copy.deepcopy(old);value.update(source_commit=SOURCE,source_tree=SOURCE_TREE,helper_normalized_sha256=digest(m.normalized_helper(h)),previous_seal=m.previous_certificate())
 value['files']=[record(p,BASE,m.tree(r,BASE),prior.get(p)) for p in sorted(m.changed(r,BASE,SOURCE)-{CONTRACT})]
 value['target_integration']['files']=[record(e['path'],TARGET,m.tree(r,TARGET),e) for e in old['target_integration']['files']]
 m.validate_contract(value);raw=(json.dumps(value,indent=2,sort_keys=True)+'\n').encode();require(digest(raw)==v['manifest_sha256'],'catalogue mismatch')
 (r/CONTRACT).write_bytes(raw);(r/HELPER).write_bytes(h.replace(b'CONTRACT_SHA256 = "UNSEALED"',b'CONTRACT_SHA256 = "'+digest(raw).encode()+b'"',1))
 git(r,'add','--',HELPER,CONTRACT);require(git(r,'write-tree').decode().strip()==SEAL_TREE,'seal tree mismatch')
 require(git(r,'hash-object','-t','commit','-w','--stdin',input=v['seal_raw'].encode()).decode().strip()==SEAL,'raw seal mismatch');git(r,'reset','--hard',SEAL)
 require(not git(r,'status','--porcelain','--untracked-files=all'),'dirty reconstruction')

def request_tree(r,before,after):
 def tree(ref):
  return {row.split(b'\t',1)[1].decode():row.split(b'\t',1)[0].decode().split() for row in git(r,'ls-tree','-rz',ref).split(b'\0') if row}
 a,b=tree(before),tree(after);entries=[]
 for p in sorted(set(a)|set(b)):
  if a.get(p)==b.get(p):continue
  require(p in b,'unexpected deletion');mode,kind,sha=b[p];require(kind=='blob','unexpected nonblob')
  uploaded=api('POST','/git/blobs',dict(content=base64.b64encode(git(r,'cat-file','blob',sha)).decode(),encoding='base64'));require(uploaded['sha']==sha,'remote blob mismatch')
  entries.append(dict(path=p,mode=mode,type=kind,sha=sha))
 return dict(base_tree=git(r,'rev-parse',before+'^{tree}').decode().strip(),tree=entries)
def commit(raw,expected):
 header,message=raw.split('\n\n',1);body=dict(message=message,parents=[])
 for row in header.splitlines():
  key,value=row.split(' ',1)
  if key=='tree':body[key]=value
  elif key=='parent':body['parents'].append(value)
  else:
   require(key in ('author','committer'),'unexpected metadata');m=re.fullmatch(r'(.*) <([^<>]+)> ([0-9]+) \+0000',value);require(m is not None,'unexpected identity')
   body[key]=dict(name=m[1],email=m[2],date=datetime.fromtimestamp(int(m[3]),timezone.utc).isoformat().replace('+00:00','Z'))
 actual=api('POST','/git/commits',body);require(actual['sha']==expected,'exact remote commit differs');return dict(sha=actual['sha'],tree=actual['tree']['sha'],parents=[p['sha'] for p in actual['parents']])
def wait(path,sha):
 end=time.monotonic()+1200
 while True:
  try:v=api('GET',path)
  except urllib.error.HTTPError as e:
   if e.code!=404:raise
   require(time.monotonic()<end,'connector object/ref unavailable');time.sleep(10)
  else:
   require((v['object']['sha'] if 'object' in v else v['sha'])==sha,'wrong object/ref');return

def publish(r,out,v):
 receipt=json.loads((out/'receipt.json').read_text());require(receipt['seal']==SEAL and [c['script'] for c in receipt['checks']]==CHECKS,'wrong source receipt')
 for c in receipt['checks']:require(c['rc']==0 and digest((out/(c['script']+'.log')).read_bytes())==c['sha256'],'source log mismatch')
 identity();wait('/git/trees/'+SOURCE_TREE,SOURCE_TREE);wait('/git/trees/'+SEAL_TREE,SEAL_TREE)
 receipt['objects']=[commit(v['source_raw'],SOURCE),commit(v['seal_raw'],SEAL)]
 (out/'commit-staging.json').write_text(json.dumps(receipt,indent=2)+'\n')
 print('Exact objects staged; connector must create '+BRANCH,flush=True)
 wait('/git/ref/heads/'+BRANCH,SEAL);identity()
 runs=api('GET',RUNS);require(runs['total_count']==len(runs['workflow_runs']),'truncated run inventory')
 require(all(x['head_sha']==SEAL and x['workflow_id']==WORKFLOW and x['event']=='workflow_dispatch' for x in runs['workflow_runs']),'foreign run inventory')
 if not runs['workflow_runs']:api('POST',DISPATCH,{'ref':BRANCH})
 receipt.update(dispatched=not runs['workflow_runs'],existing_runs=[x['id'] for x in runs['workflow_runs']],workflow=WORKFLOW,original_failure=FAILED,branch=BRANCH)
 (out/'publication.json').write_text(json.dumps(receipt,indent=2)+'\n')
 print('Only60f dispatch complete; no full CI or feature/target updates',flush=True)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('phase',choices=('reconstruct','prepare','publish'))
 for n in ('repository','destination','output'):p.add_argument('--'+n,type=Path,required=True)
 a=p.parse_args();repo=a.repository.resolve();r=a.destination.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True);v=load()
 if a.phase in ('prepare','reconstruct'):
  rebuild(repo,r,out,v)
  if a.phase=='reconstruct':print('EXACT60F_RECONSTRUCTION_PASS');return
  identity();receipt=dict(source=SOURCE,seal=SEAL,checks=[],refs_updated=False,full_ci=False)
  for script in CHECKS:
   start=time.monotonic()
   with (out/(script+'.log')).open('wb') as log:
    result=subprocess.run([sys.executable,'-B','morphhdl/scripts/'+script],cwd=r,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1'),stdout=log,stderr=subprocess.STDOUT,timeout=1200)
   receipt['checks'].append(dict(script=script,rc=result.returncode,seconds=round(time.monotonic()-start,3),sha256=digest((out/(script+'.log')).read_bytes())))
   (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');require(result.returncode==0,'source gate failed: '+script)
  (out/'connector-tree-requests.json').write_text(json.dumps(dict(source=request_tree(r,PARENT,SOURCE),seal=request_tree(r,SOURCE,SEAL)),indent=2)+'\n')
  for label,ref in (('source',SOURCE),('seal',SEAL)):(out/(label+'-commit.txt')).write_bytes(git(r,'cat-file','commit',ref))
 else:publish(r,out,v)
 require(git(r,'rev-parse','HEAD').decode().strip()==SEAL and not git(r,'status','--porcelain','--untracked-files=all'),'source changed during staging')
if __name__=='__main__':main()
