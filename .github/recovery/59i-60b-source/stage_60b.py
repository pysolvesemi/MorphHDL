#!/usr/bin/env python3
"""Reproduce reviewed 59i/61 and inherited-60b source; never update remote refs."""
from __future__ import annotations
import argparse, base64, copy, hashlib, json, lzma, os, re, subprocess, sys, tempfile, time
import urllib.request, urllib.error
from datetime import datetime, timezone
from pathlib import Path
REPO='pysolvesemi/MorphHDL'
SOURCE='a7d6730f0153ad501c09f856931cacaa2c287967'
SEAL='14dc0d2bb7274d9e91b60f112619a78b237936ab'
SOURCE_TREE='afe020f3c2393cb89fc9cf39806c4df2bec03d72'
SEAL_TREE='93594357906ec1f71485aff354b94bcaaaf3da67'
PARENT='f43100e4899593eb5c9e78537a4dcf6f53f9c30f'
TARGET='27af65abbee0d2334d6be7a6e4e2408b8af32fd9'
BASE='954d9b2763b064dba60af71ad8fa509a9d7cada8'
PAYLOAD_HASH='d33759e68c64eb0d714dd9f979f698d465141f0ff622afa794ed56abfde84b0e'
MANIFEST_HASH='d9f575bfe5b6463c09cf7888f9410196229c69f7bd251023ef9d65fd37ddef0e'
HELPER='morphhdl/scripts/check-increment-59i-production-successor.py'
CONTRACT='morphhdl/contracts/increment-59i-production-successor.json'
CHECKS=['check-increment-59i-production-successor.py','check-increment-61-source-review.py',
 'check-native-source-preservation.py','check-typed-native-source-overlay.py',
 'check-increment-59i-target-integration.py','check-increment-62-wa08-source-overlay.py',
 'check-wa10-source-scope.py','check-increment-59i-widening-source-review.py',
 'check-increment-59i-rollout-composition.py','check-increment-60b-signedness-authority.py',
 'test-increment-60b-inherited-source-scope.py']
def require(ok,why):
 if not ok: raise RuntimeError('59i exact 60b transport: '+why)
def digest(raw): return hashlib.sha256(raw).hexdigest()
def git(r,*args,input=None):
 p=subprocess.run(['git','--literal-pathspecs',*args],cwd=r,input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
 require(p.returncode==0,'git '+str(args)+': '+p.stderr.decode(errors='replace'));return p.stdout
def valid(p): return isinstance(p,str) and p and Path(p).as_posix()==p and not Path(p).is_absolute() and not ({'..','.git'} & set(Path(p).parts)) and '\0' not in p
def tree(r,ref):
 result={}
 for row in git(r,'ls-tree','-rz',ref).split(b'\0'):
  if row:
   meta,p=row.split(b'\t',1);mode,kind,oid=meta.decode().split();p=p.decode()
   require(valid(p) and p not in result,'invalid tree inventory');result[p]=[mode,kind,oid]
 return result
def blob(r,t,p):return None if p not in t else git(r,'cat-file','blob',t[p][2])
def write(r,p,raw,mode):
 require(valid(p),'invalid write path')
 require(not any((r/Path(*Path(p).parts[:i])).is_symlink() for i in range(1,len(Path(p).parts)+1)),'linked destination')
 f=r/p
 if raw is None:f.unlink(missing_ok=True)
 else:
  require(mode in ('100644','100755'),'invalid file mode');f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(raw);f.chmod(0o755 if mode=='100755' else 0o644)
def rebuild(repository,r,value):
 require(not r.exists(),'destination exists')
 require((value['schema'],value['repository'],value['base'],value['parent'],value['target'])==(1,REPO,BASE,PARENT,TARGET),'wrong input anchors')
 require((value['source'],value['seal'],value['source_tree'],value['seal_tree'],value['manifest_sha256'])==(SOURCE,SEAL,SOURCE_TREE,SEAL_TREE,MANIFEST_HASH),'wrong output anchors')
 git(repository,'worktree','add','--detach',str(r),PARENT)
 tables={ref:tree(r,ref) for ref in (PARENT,TARGET,BASE)}
 entries=value['files'];paths=[e['path'] for e in entries]
 require(paths==sorted(set(paths)) and len(paths)==96,'unexpected file inventory')
 for e in entries:
  p,ref,want=e['path'],e['ref'],e['entry'];require(ref in (PARENT,TARGET),'unsupported input tree')
  a=blob(r,tables[ref],p)
  if 'edits' in e:
   a=a or b'';parts=[];end=0
   for start,stop,text in e['edits']:
    require(type(start)is int and type(stop)is int and end<=start<=stop<=len(a),'invalid edit span')
    parts.extend([a[end:start],text.encode()]);end=stop
   parts.append(a[end:]);a=b''.join(parts)
  if want is None:a=None
  else:
   require(want[1]=='blob' and a is not None and git(r,'hash-object','--stdin',input=a).decode().strip()==want[2],'changed blob identity: '+p)
  write(r,p,a,None if want is None else want[0])
 git(r,'add','-f','--all','--',*paths);require(git(r,'write-tree').decode().strip()==SOURCE_TREE,'source tree differs')
 require(git(r,'hash-object','-t','commit','-w','--stdin',input=value['source_raw'].encode()).decode().strip()==SOURCE,'raw source commit differs')
 git(r,'reset','--hard',SOURCE);require(git(r,'rev-list','--parents','-n','1',SOURCE).decode().split()==[SOURCE,PARENT,TARGET],'ordered source parents differ')
 oldraw=blob(r,tables[PARENT],CONTRACT);require(digest(oldraw)==value['old_manifest_sha256'],'prior certificate differs')
 old={e['path']:e for e in json.loads(oldraw)['files']};current=tree(r,SOURCE)
 def record(e,baseline):
  p=e['path'];require(valid(p),'invalid review path');a=blob(r,baseline,p);b=blob(r,current,p)
  out=dict(path=p,reason=e['reason'],before_mode=baseline.get(p,(None,))[0],after_mode=current.get(p,(None,))[0],before_sha256=None if a is None else digest(a),after_sha256=None if b is None else digest(b),edits=[])
  aa=a or b'';bb=b or b'';lastA=lastB=0;reverse=[]
  for identity,reason,i,j,k,l in e['edits']:
   require(all(type(v)is int for v in (i,j,k,l)) and lastA<=i<=j<=len(aa) and lastB<=k<=l<=len(bb),'invalid reviewed offsets')
   require(aa[lastA:i]==bb[lastB:k],'unreviewed byte gap')
   out['edits'].append(dict(id=identity,reason=reason,before_start=i,before_end=j,after_start=k,after_end=l,before=aa[i:j].decode(),after=bb[k:l].decode()))
   reverse.extend([bb[lastB:k],aa[i:j]]);lastA=j;lastB=l
  reverse.append(bb[lastB:]);require(b''.join(reverse)==aa,'review reversal differs: '+p)
  return out
 reused=value['reused'];require(reused==sorted(set(reused)) and all(p in old for p in reused),'invalid reused record set')
 m=copy.deepcopy(value['header']);m['files']=[copy.deepcopy(old[p]) for p in reused]+[record(e,tables[BASE]) for e in value['records']];m['files'].sort(key=lambda e:e['path'])
 m['target_integration']['files']=[record(e,tables[TARGET]) for e in value['target_records']]
 manifest=(json.dumps(m,indent=2,sort_keys=True)+'\n').encode();require(digest(manifest)==MANIFEST_HASH,'review contract does not match exact reviewed bytes')
 h=(r/HELPER).read_bytes();slot=b'CONTRACT_SHA256 = "UNSEALED"\n';require(h.count(slot)==1,'ambiguous source seal slot')
 write(r,CONTRACT,manifest,'100644');write(r,HELPER,h.replace(slot,b'CONTRACT_SHA256 = "'+MANIFEST_HASH.encode()+b'"\n',1),'100644')
 git(r,'add','--',HELPER,CONTRACT);require(set(git(r,'diff','--cached','--name-only',SOURCE).decode().splitlines())=={HELPER,CONTRACT},'seal touched other files')
 require(git(r,'write-tree').decode().strip()==SEAL_TREE,'seal tree differs')
 require(git(r,'hash-object','-t','commit','-w','--stdin',input=value['seal_raw'].encode()).decode().strip()==SEAL,'raw seal differs')
 git(r,'reset','--hard',SEAL);require(not git(r,'status','--porcelain','--untracked-files=all'),'dirty reconstruction')
def api(method,path,body=None):
 require(os.environ.get('GITHUB_REPOSITORY')==REPO,'wrong authorized repository')
 require((method=='POST' and path in ('/git/blobs','/git/commits')) or (method=='GET' and re.fullmatch('/git/trees/[0-9a-f]{40}',path)),'only exact immutable Git objects permitted')
 token=os.environ.get('GH_TOKEN');require(bool(token),'missing authorized job token')
 req=urllib.request.Request('https://api.github.com/repos/'+REPO+path,method=method,data=None if body is None else json.dumps(body).encode(),headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','Content-Type':'application/json','X-GitHub-Api-Version':'2022-11-28','User-Agent':'MorphHDL-reviewed-60b-transport'})
 try:
  with urllib.request.urlopen(req,timeout=120) as response:return json.load(response)
 except urllib.error.HTTPError as e:raise RuntimeError('Git object HTTP '+str(e.code)+': '+e.read().decode(errors='replace')[:2000]) from None

def request(r,before,after,expected,uploaded):
 a,b=tree(r,before),tree(r,after);changes=[]
 for p in sorted(set(a)|set(b)):
  if a.get(p)==b.get(p):continue
  v=b.get(p)
  if v is None:changes.append(dict(path=p,mode=a[p][0],type=a[p][1],sha=None));continue
  mode,kind,sha=v;require(kind=='blob' and mode in ('100644','100755'),'unexpected changed object type')
  if sha not in uploaded:
   result=api('POST','/git/blobs',dict(content=base64.b64encode(blob(r,b,p)).decode(),encoding='base64'))
   require(result['sha']==sha,'remote blob differs');uploaded.add(sha)
  changes.append(dict(path=p,mode=mode,type=kind,sha=sha))
 return dict(base_tree=git(r,'rev-parse',before+'^{tree}').decode().strip(),expected_tree=expected,tree=changes)
def stage_commit(raw,expected):
 header,message=raw.split('\n\n',1);body=dict(message=message,parents=[])
 for line in header.splitlines():
  key,rest=line.split(' ',1)
  if key=='tree':body['tree']=rest
  elif key=='parent':body['parents'].append(rest)
  else:
   require(key in ('author','committer'),'unsupported commit metadata');m=re.fullmatch(r'(.*) <([^<>]+)> ([0-9]+) \+0000',rest);require(m is not None,'unsupported author timezone')
   body[key]=dict(name=m[1],email=m[2],date=datetime.fromtimestamp(int(m[3]),timezone.utc).isoformat().replace('+00:00','Z'))
 result=api('POST','/git/commits',body);require(result['sha']==expected,'remote raw commit differs')
 return dict(sha=result['sha'],tree=result['tree']['sha'],parents=[v['sha'] for v in result['parents']])
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('phase',choices=('reconstruct','prepare','commits'))
 for name in ('repository','destination','payload-dir','output'):p.add_argument('--'+name,type=Path,required=True)
 a=p.parse_args();repository=a.repository.resolve();r=a.destination.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
 parts=sorted(a.payload_dir.glob('part-*.b64'));require(len(parts)==4,'four payload parts required')
 compressed=base64.b64decode(b''.join(f.read_bytes() for f in parts),validate=True);require(digest(compressed)==PAYLOAD_HASH,'transport checksum differs');value=json.loads(lzma.decompress(compressed))
 if a.phase in ('reconstruct','prepare'):
  rebuild(repository,r,value)
  if a.phase=='reconstruct':print('EXACT_60B_TRANSPORT_RECONSTRUCTION_PASS');return
  receipt=dict(source=SOURCE,seal=SEAL,source_tree=SOURCE_TREE,seal_tree=SEAL_TREE,manifest_sha256=MANIFEST_HASH,checks=[],refs_updated=False)
  for name in CHECKS:
   log=out/(name+'.log');start=time.monotonic()
   with log.open('wb') as stream:
    result=subprocess.run([sys.executable,'-B','morphhdl/scripts/'+name],cwd=r,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1'),stdout=stream,stderr=subprocess.STDOUT,timeout=900)
   receipt['checks'].append(dict(script=name,returncode=result.returncode,seconds=round(time.monotonic()-start,3),log_sha256=digest(log.read_bytes())))
   (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');require(result.returncode==0,'preflight failed: '+name)
  uploaded=set();requests=dict(source=request(r,PARENT,SOURCE,SOURCE_TREE,uploaded),seal=request(r,SOURCE,SEAL,SEAL_TREE,uploaded))
  (out/'connector-tree-requests.json').write_text(json.dumps(requests,indent=2)+'\n')
  for label,ref in [('source',SOURCE),('seal',SEAL)]:
   git(r,'update-ref','refs/heads/60b-transport-'+label,ref);(out/(label+'-commit.txt')).write_bytes(git(r,'cat-file','commit',ref))
  bundle=out/'60b-reviewed.bundle';git(r,'bundle','create',str(bundle),'refs/heads/60b-transport-source','refs/heads/60b-transport-seal','^'+PARENT,'^'+TARGET)
  (out/'bundle.sha256').write_text(digest(bundle.read_bytes())+'  60b-reviewed.bundle\n')
 else:
  receipt=json.loads((out/'receipt.json').read_text());require(receipt['source']==SOURCE and receipt['seal']==SEAL and [c['script'] for c in receipt['checks']]==CHECKS,'preflight receipt changed')
  for c in receipt['checks']:require(c['returncode']==0 and digest((out/(c['script']+'.log')).read_bytes())==c['log_sha256'],'missing/altered source evidence')
  require(git(r,'rev-parse','HEAD').decode().strip()==SEAL,'source HEAD changed')
  for expected in (SOURCE_TREE,SEAL_TREE):
   deadline=time.monotonic()+600
   while True:
    try: result=api('GET','/git/trees/'+expected)
    except RuntimeError as e:
     if not str(e).startswith('Git object HTTP 404:'):raise
     require(time.monotonic()<deadline,'connector tree not available');time.sleep(10)
    else:
     require(result.get('sha')==expected and not result.get('truncated',False),'wrong connector tree');break
  objects=[stage_commit(value[label+'_raw'],ref) for label,ref in [('source',SOURCE),('seal',SEAL)]]
  (out/'commit-receipt.json').write_text(json.dumps(dict(objects=objects,refs_updated=False),indent=2)+'\n')
 require(not git(r,'status','--porcelain','--untracked-files=all'),'source changed during staging')
 print('EXACT_60B_TRANSPORT_'+a.phase.upper()+'_PASS; no remote refs updated')
if __name__=='__main__':main()
