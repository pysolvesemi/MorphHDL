#!/usr/bin/env python3
"""Restore the exact reviewed PR189 sync; stage objects, never remote refs or CI."""
from __future__ import annotations
import argparse,base64,copy,difflib,hashlib,importlib.util,json,lzma,os,re,subprocess,sys,types
from pathlib import Path
PARENT='37d1629f9b78c4d9cd6646abee9962fdd046e413'
TARGET='e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d'
SOURCE='a68830a058da93dcc1fb4662d777a309e4986ebd'
SEAL='ba167f35742e94ed6b1c287fdf0ca8a3e02ee101'
SOURCE_TREE='c62d92e2efa2dd2fb893d6a4ad2dfd76a7139f74'
SEAL_TREE='c0ec9b7309dad8721dbfc73e12886c153d9b0594'
PAYLOAD_SHA='7400e297de5d383a6d828ebd12f0d8e0f95e56853c71bf199a79bd3e3b10dfde'
MANIFEST_SHA='a0be87501950676e6fa4b86cfca956cb24903161046624086bdd75c3a0d27909'
OLD_MANIFEST='c64f923ddd7778115a13374505fd90a3a84f04700c8caf77884477f0f2f37ccb'
BASE='954d9b2763b064dba60af71ad8fa509a9d7cada8'
COMMON='27af65abbee0d2334d6be7a6e4e2408b8af32fd9'
HELPER='morphhdl/scripts/check-increment-59i-production-successor.py'
CONTRACT='morphhdl/contracts/increment-59i-production-successor.json'
ORIGINAL='.github/recovery/59i-failed-first-v2/stage.py'
ORIGINAL_BLOB='9d087c67ccae7a5594c554a9e89637ce81059e50'
CHECKS=['check-increment-59i-production-successor.py','check-increment-61-source-review.py',
 'check-native-source-preservation.py','check-typed-native-source-overlay.py',
 'check-increment-59i-target-integration.py','check-increment-62-wa08-source-overlay.py',
 'check-wa10-source-scope.py','check-increment-59i-widening-source-review.py',
 'check-increment-59i-rollout-composition.py','check-increment-60b-signedness-authority.py',
 'check-cdc-successor-source.py','check-lane-when-source-scope.py',
 'test-increment-59i-pr189-sync.py','test-increment-59i-continuation.py',
 'test-increment-59i-audit-shards.py','test-increment-59i-source-io.py',
 'test-increment-59g-report-inventory.py','test-increment-59i-production-successor.py']
def require(ok,msg):
 if not ok:raise RuntimeError('59i exact PR189 staging: '+msg)
def digest(raw):return hashlib.sha256(raw).hexdigest()
def git(r,*args,input=None):
 p=subprocess.run(['git','--literal-pathspecs',*args],cwd=r,input=input,capture_output=True,timeout=180)
 require(p.returncode==0,'git '+str(args)+': '+p.stderr.decode(errors='replace'));return p.stdout
def tree(r,ref):
 result={}
 for row in git(r,'ls-tree','-rz',ref).split(b'\0'):
  if row:
   meta,path=row.split(b'\t');result[path.decode()]=meta.decode().split()
 return result
def blob(r,t,path):return None if path not in t else git(r,'cat-file','blob',t[path][2])
def load_payload(here):
 raw=base64.b64decode((here/'payload.xz.b64').read_bytes(),validate=True)
 require(digest(raw)==PAYLOAD_SHA,'payload checksum differs')
 value=json.loads(lzma.decompress(raw))
 require(value['parents']==[PARENT,TARGET] and value['source']==SOURCE and value['seal']==SEAL and value['source_tree']==SOURCE_TREE and value['seal_tree']==SEAL_TREE,'payload identity differs')
 return value
def rebuild(repository,r,value):
 require(not r.exists(),'refuse to overwrite destination')
 git(repository,'worktree','add','--detach',str(r),PARENT)
 tables={ref:tree(r,ref) for ref in (PARENT,TARGET,BASE)}
 paths=[e['path'] for e in value['files']]
 require(len(paths)==102 and paths==sorted(set(paths)),'invalid changed-file inventory')
 for e in value['files']:
  p=e['path'];ref=e['ref'];want=e['entry']
  require(ref in (PARENT,TARGET) and p and not Path(p).is_absolute() and not ({'..','.git'} & set(Path(p).parts)),'unsafe source entry')
  f=r/p;require(not any((r/Path(*Path(p).parts[:i])).is_symlink() for i in range(1,len(Path(p).parts)+1)),'linked write path')
  before=blob(r,tables[ref],p);after=before
  if 'edits' in e:
   before=before or b'';parts=[];last=0
   for start,stop,text in e['edits']:
    require(type(start)is int and type(stop)is int and last<=start<=stop<=len(before),'invalid transport edit')
    parts.extend([before[last:start],text.encode()]);last=stop
   after=b''.join(parts)+before[last:]
  if want is None:f.unlink(missing_ok=True);continue
  require(want[0] in ('100644','100755') and want[1]=='blob' and after is not None,'invalid output entry')
  require(git(r,'hash-object','--stdin',input=after).decode().strip()==want[2],'wrong output blob: '+p)
  f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(after);f.chmod(0o755 if want[0]=='100755' else 0o644)
 git(r,'add','-f','--all','--',*paths)
 require(git(r,'write-tree').decode().strip()==SOURCE_TREE,'source tree differs')
 require(git(r,'hash-object','-t','commit','-w','--stdin',input=value['source_raw'].encode()).decode().strip()==SOURCE,'source raw commit differs')
 git(r,'reset','--hard',SOURCE)
 require(git(r,'rev-list','--parents','-n','1',SOURCE).decode().split()==[SOURCE,PARENT,TARGET],'source topology differs')
 oldraw=blob(r,tables[PARENT],CONTRACT);require(digest(oldraw)==OLD_MANIFEST,'previous certificate changed')
 old=json.loads(oldraw);prior={x['path']:x for x in old['files']};current=tree(r,SOURCE)
 h=(r/HELPER).read_bytes();m=types.ModuleType('exact_reviewed_sync');m.__file__=str(r/HELPER);exec(compile(h,m.__file__,'exec'),m.__dict__)
 def edits(p,x,y,prefix):
  aa=x.splitlines(keepends=True);bb=y.splitlines(keepends=True);oa=[0];ob=[0]
  for v in aa:oa.append(oa[-1]+len(v))
  for v in bb:ob.append(ob[-1]+len(v))
  out=[]
  for tag,i,j,k,l in difflib.SequenceMatcher(None,aa,bb,autojunk=False).get_opcodes():
   if tag!='equal':out.append(dict(id=prefix+':'+p+':'+str(len(out)+1),reason='Exact reviewed synchronization span; both parent sources and every unchanged gap retained.',before_start=oa[i],before_end=oa[j],after_start=ob[k],after_end=ob[l],before=x[oa[i]:oa[j]].decode(),after=y[ob[k]:ob[l]].decode()))
  return out
 def record(p,baseline,table,prefix,previous=None):
  x=blob(r,table,p);y=blob(r,current,p)
  ids=dict(path=p,before_mode=table.get(p,(None,))[0],after_mode=current.get(p,(None,))[0],before_sha256=None if x is None else digest(x),after_sha256=None if y is None else digest(y))
  if previous and all(previous[k]==v for k,v in ids.items()):return copy.deepcopy(previous)
  e=dict(**ids,reason='Preserve merged PR189/PR187 and sealed59i source; exact conflict resolution and authenticated source-review routing.',edits=edits(p,x or b'',y or b'',prefix))
  require(m.restore_reviewed(e,x or b'',y or b'')==(x or b''),'review reversal differs');return e
 catalogue=copy.deepcopy(old)
 catalogue.update(source_commit=SOURCE,source_tree=SOURCE_TREE,helper_normalized_sha256=digest(m.normalized_helper(h)),previous_seal=m.previous_certificate())
 changed=set(git(r,'diff','--name-only',BASE,SOURCE).decode().splitlines())-{CONTRACT}
 catalogue['files']=[record(p,BASE,tables[BASE],'sync189',prior.get(p)) for p in sorted(changed)]
 catalogue['target_integration']=dict(target_commit=TARGET,target_tree=git(r,'rev-parse',TARGET+'^{tree}').decode().strip(),common_base=COMMON,common_base_tree=git(r,'rev-parse',COMMON+'^{tree}').decode().strip(),files=[record(p,TARGET,tables[TARGET],'sync189-target') for p in sorted(git(r,'diff','--name-only',COMMON,TARGET).decode().splitlines())])
 m.validate_contract(catalogue)
 raw=(json.dumps(catalogue,indent=2,sort_keys=True)+'\n').encode();require(digest(raw)==MANIFEST_SHA,'regenerated manifest differs')
 (r/CONTRACT).write_bytes(raw)
 slot=b'CONTRACT_SHA256 = "UNSEALED"\n';require(h.count(slot)==1,'ambiguous source seal slot')
 (r/HELPER).write_bytes(h.replace(slot,b'CONTRACT_SHA256 = "'+MANIFEST_SHA.encode()+b'"\n',1))
 git(r,'add','--',HELPER,CONTRACT)
 require(set(git(r,'diff','--cached','--name-only',SOURCE).decode().splitlines())=={HELPER,CONTRACT},'seal changes more than two files')
 require(git(r,'write-tree').decode().strip()==SEAL_TREE,'seal tree differs')
 require(git(r,'hash-object','-t','commit','-w','--stdin',input=value['seal_raw'].encode()).decode().strip()==SEAL,'seal raw commit differs')
 git(r,'reset','--hard',SEAL)
 require(not git(r,'status','--porcelain','--untracked-files=all'),'dirty restored source')

def original(repository):
 path=repository/ORIGINAL;raw=path.read_bytes()
 require(hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==ORIGINAL_BLOB,'original stager differs')
 spec=importlib.util.spec_from_file_location('pinned_stager',path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 for key in ('PARENT','TARGET','SOURCE','SEAL','SOURCE_TREE','SEAL_TREE'):setattr(m,key,globals()[key])
 m.SELECTED={} # This program does not dispatch any workflow or write any ref.
 m.BRANCH='recovery/increment-59i-pr189-sync-ba167f35'
 old=m.api
 def api(method,path,body=None):
  require((method=='POST' and path in ('/git/blobs','/git/commits')) or (method=='GET' and path in ('/git/trees/'+SOURCE_TREE,'/git/trees/'+SEAL_TREE)),'only immutable exact Git objects allowed')
  return old(method,path,body)
 m.api=api;return m

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('phase',choices=('reconstruct','prepare','publish'))
 for n in ('repository','destination','output'):p.add_argument('--'+n,type=Path,required=True)
 a=p.parse_args();repo=a.repository.resolve();r=a.destination.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
 value=load_payload(Path(__file__).resolve().parent)
 if a.phase in ('prepare','reconstruct'):
  rebuild(repo,r,value)
  if a.phase=='reconstruct':print('EXACT_PR189_SYNC_RESTORED');return
  receipt=dict(source=SOURCE,seal=SEAL,checks=[],refs_updated=False,ci_dispatched=False)
  for name in CHECKS:
   with (out/(name+'.log')).open('wb') as log:
    proc=subprocess.run([sys.executable,'-B','morphhdl/scripts/'+name],cwd=r,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1'),stdout=log,stderr=subprocess.STDOUT,timeout=1200)
   receipt['checks'].append(dict(script=name,returncode=proc.returncode,sha256=digest((out/(name+'.log')).read_bytes())))
   (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');require(proc.returncode==0,'failed source check: '+name)
  m=original(repo);m.check_clean(r);uploaded=set()
  requests=dict(source=m.request_tree(r,PARENT,SOURCE,SOURCE_TREE,uploaded),seal=m.request_tree(r,SOURCE,SEAL,SEAL_TREE,uploaded))
  (out/'connector-tree-requests.json').write_text(json.dumps(requests,indent=2)+'\n')
  for label,ref in (('source',SOURCE),('seal',SEAL)):(out/(label+'-commit.txt')).write_bytes(git(r,'cat-file','commit',ref))
 else:
  m=original(repo);m.check_clean(r);v=json.loads((out/'receipt.json').read_text())
  require(v['source']==SOURCE and v['seal']==SEAL and [c['script'] for c in v['checks']]==CHECKS,'receipt identity changed')
  for c in v['checks']:require(c['returncode']==0 and digest((out/(c['script']+'.log')).read_bytes())==c['sha256'],'source check/log changed')
  import time,urllib.error
  for sha in (SOURCE_TREE,SEAL_TREE):
   deadline=time.monotonic()+1200
   while True:
    try:got=m.api('GET','/git/trees/'+sha)
    except urllib.error.HTTPError as e:
     if e.code!=404:raise
     require(time.monotonic()<deadline,'connector tree not available');time.sleep(10)
    else:
     require(got['sha']==sha and not got.get('truncated',False),'wrong tree');break
  v['objects']=[m.stage_commit(r,ref) for ref in (SOURCE,SEAL)]
  (out/'published-objects.json').write_text(json.dumps(v,indent=2)+'\n');m.check_clean(r)
 print('EXACT_PR189_SYNC_'+a.phase.upper()+'_PASS; refs and workflows untouched')
if __name__=='__main__':main()
