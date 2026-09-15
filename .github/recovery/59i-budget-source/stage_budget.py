#!/usr/bin/env python3
"""Reproduce one reviewed budget-only source/seal pair; never update remote refs."""
from __future__ import annotations
import argparse,base64,copy,difflib,gzip,hashlib,importlib.util,json,os,subprocess,sys
from pathlib import Path

SOURCE='b459aa9b94774dac3235c42561237e9c273bed6c'
SEAL='f43100e4899593eb5c9e78537a4dcf6f53f9c30f'
SOURCE_TREE='1c13c39f4309998f36374d400f1e0466de86aa6b'
SEAL_TREE='44a48e034fc910506620f0125f2dd417500a89a1'
OLD_SOURCE='214774fbf9579efc7007cb9ac5674583d8bf370c'
OLD_SEAL='d3a6c453f315104c55025e3f19a7396ab7d4faeb'
PAYLOAD_HASH='2758ab1064e95f00dc3dd762ad11683262d0b893bd4ec956574b7787d765974d'
MANIFEST_HASH='f61e298bd49a1ea7b45565884c398f8a287d24adac8f4bc76067705c094c2486'
OLD_MANIFEST_HASH='d6ec9eb8c5083d44d6e4687900c6a8671d7e3f5336907a6d176c6cdb297561db'
BASE='954d9b2763b064dba60af71ad8fa509a9d7cada8'
TARGET='61d1fe0dcac0b52856620944a2d7426fd1390a48'
HELPER='morphhdl/scripts/check-increment-59i-production-successor.py'
CONTRACT='morphhdl/contracts/increment-59i-production-successor.json'
CHECKS=['check-increment-59i-production-successor.py','check-native-source-preservation.py',
 'check-typed-native-source-overlay.py','check-increment-59i-target-integration.py',
 'check-increment-62-wa08-source-overlay.py','check-wa10-source-scope.py',
 'check-increment-59i-widening-source-review.py','check-increment-59i-rollout-composition.py',
 'test-increment-59i-inherited-audit-budgets.py','test-increment-60f-source-budget.py',
 'test-increment-60f-source-scheduling.py','test-inherited-source-audit-timeouts.py']

def require(ok,detail):
 if not ok:raise RuntimeError('59i budget transport: '+detail)
def digest(raw):return hashlib.sha256(raw).hexdigest()
def load(path,name,oid):
 require(path.is_file() and not path.is_symlink(),'missing regular transport helper')
 raw=path.read_bytes()
 require(hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==oid,'transport helper changed')
 spec=importlib.util.spec_from_file_location(name,path);require(spec is not None and spec.loader is not None,'invalid helper import')
 value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

def spans(path,a,b,prefix):
 aa=a.splitlines(keepends=True);bb=b.splitlines(keepends=True);oa=[0];ob=[0]
 for s in aa:oa.append(oa[-1]+len(s))
 for s in bb:ob.append(ob[-1]+len(s))
 result=[]
 for tag,i,j,k,l in difflib.SequenceMatcher(None,aa,bb,autojunk=False).get_opcodes():
  if tag!='equal':result.append(dict(id=prefix+':'+path+':'+str(len(result)+1),reason='Exact reviewed whole-line budget/reconciliation span; all unchanged gaps retained',before_start=oa[i],before_end=oa[j],after_start=ob[k],after_end=ob[l],before=a[oa[i]:oa[j]].decode(),after=b[ob[k]:ob[l]].decode()))
 restored=[];i=j=0
 for e in result:
  require(a[i:e['before_start']]==b[j:e['after_start']],'changed review gap')
  restored.extend([b[j:e['after_start']],e['before'].encode()]);i=e['before_end'];j=e['after_end']
 restored.append(b[j:]);require(b''.join(restored)==a,'review reversal failed')
 return result

def reconstruct(m,repository,root,value):
 require((value['source'],value['seal'],value['source_tree'],value['seal_tree'])==(SOURCE,SEAL,SOURCE_TREE,SEAL_TREE),'payload anchors changed')
 require(value['old_source']==OLD_SOURCE and value['old_seal']==OLD_SEAL and value['parents']==[BASE,TARGET],'payload parent anchors changed')
 require(not root.exists(),'destination already exists')
 m.git(repository,'worktree','add','--detach',str(root),OLD_SOURCE)
 paths=[e['path'] for e in value['changes']]
 require(len(paths)==10 and paths==sorted(set(paths)),'transport inventory changed')
 oldtree=m.tree(root,OLD_SOURCE)
 for entry in value['changes']:
  p=entry['path'];require(m.valid_path(p),'unsafe source path')
  before=m.blob(root,oldtree,p) or b''
  require(digest(before)==entry['before_sha256'],'transport baseline differs: '+p)
  parts=[];end=0
  for start,stop,text in entry['edits']:
   require(type(start)is int and type(stop)is int and end<=start<=stop<=len(before),'invalid transport offsets')
   parts.extend([before[end:start],text.encode()]);end=stop
  parts.append(before[end:]);after=b''.join(parts)
  require(digest(after)==entry['after_sha256'],'transport output differs: '+p)
  m.write(root,p,after,'100644')
 m.git(root,'add','--',*paths)
 require(m.git(root,'write-tree').decode().strip()==SOURCE_TREE,'source tree differs')
 require(m.git(root,'hash-object','-t','commit','-w','--stdin',input=value['source_raw'].encode()).decode().strip()==SOURCE,'source raw commit differs')
 m.git(root,'reset','--hard',SOURCE)
 require(m.git(root,'rev-list','--parents','-n','1',SOURCE).decode().split()==[SOURCE,BASE,TARGET],'source ordered parents differ')
 raw=m.git(root,'show',OLD_SEAL+':'+CONTRACT);require(digest(raw)==OLD_MANIFEST_HASH,'prior catalogue changed')
 old=json.loads(raw);new=copy.deepcopy(old);new.update(source_commit=SOURCE,source_tree=SOURCE_TREE)
 before=m.tree(root,BASE);target=m.tree(root,TARGET);after=m.tree(root,SOURCE)
 records={e['path']:e for e in old['files']}
 def record(p,bt,prior,prefix):
  a=m.blob(root,bt,p);b=m.blob(root,after,p)
  ids=dict(path=p,before_mode=bt.get(p,(None,))[0],after_mode=after.get(p,(None,))[0],before_sha256=None if a is None else digest(a),after_sha256=None if b is None else digest(b))
  if prior is not None and all(prior[k]==v for k,v in ids.items()):return copy.deepcopy(prior)
  require(p in paths,'unexpected catalogue edit')
  reason=(prior['reason']+' ' if prior else '')+'Audited scheduling-only follow-on: preserve all source/proof controls and provide bounded current-positive runtime headroom.'
  return dict(**ids,reason=reason,edits=spans(p,a or b'',b or b'',prefix))
 changed=m.git(root,'diff','--name-only',BASE,SOURCE).decode().splitlines()
 new['files']=[record(p,before,records.get(p),'current-budget') for p in sorted(changed)]
 new['target_integration']['files']=[record(e['path'],target,e,'current-budget-target') for e in old['target_integration']['files']]
 raw=(json.dumps(new,indent=2,sort_keys=True)+'\n').encode();require(digest(raw)==MANIFEST_HASH,'rebuilt catalogue differs')
 helper=(root/HELPER).read_bytes();slot=b'CONTRACT_SHA256 = "UNSEALED"\n';require(helper.count(slot)==1,'ambiguous source hash slot')
 m.write(root,CONTRACT,raw,'100644');m.write(root,HELPER,helper.replace(slot,b'CONTRACT_SHA256 = "'+MANIFEST_HASH.encode()+b'"\n',1),'100644')
 m.git(root,'add','-f','--',HELPER,CONTRACT)
 require(set(m.git(root,'diff','--cached','--name-only',SOURCE).decode().splitlines())=={HELPER,CONTRACT},'seal inventory differs')
 require(m.git(root,'write-tree').decode().strip()==SEAL_TREE,'seal tree differs')
 require(m.git(root,'hash-object','-t','commit','-w','--stdin',input=value['seal_raw'].encode()).decode().strip()==SEAL,'seal raw commit differs')
 m.git(root,'reset','--hard',SEAL)
 require(not m.git(root,'diff',OLD_SEAL,SEAL,'--','**/src/**','morphhdl/contracts/native-source-preservation.json','morphhdl/contracts/increment-55-native-change-review.json'),'runtime/native source changed')
 require(not m.git(root,'status','--porcelain','--untracked-files=all'),'reconstructed source is dirty')


def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('phase',choices=['prepare','commits','reconstruct'])
 for name in ['repository','destination','output']:p.add_argument('--'+name,type=Path,required=True)
 args=p.parse_args();repository=args.repository.resolve();root=args.destination.resolve();out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
 here=Path(__file__).resolve().parent
 m=load(repository/'.github/recovery/59i-source/stage_reviewed_source.py','old_stager','47fd5cea8f1e4baef225250b4455f349d8959b16')
 dbpath=repository/'.github/recovery/59i-source/stage_database.py'
 db=load(dbpath,'old_database',DATABASE_BLOB)
 raw=base64.b64decode((here/'budget-payload.b64').read_bytes(),validate=True);require(digest(raw)==PAYLOAD_HASH,'payload checksum changed');value=json.loads(gzip.decompress(raw))
 if args.phase in ('prepare','reconstruct'):
  reconstruct(m,repository,root,value)
  if args.phase=='reconstruct':print('EXACT_BUDGET_TRANSPORT_RECONSTRUCTION_PASS');return
  receipt=dict(source=SOURCE,source_tree=SOURCE_TREE,seal=SEAL,seal_tree=SEAL_TREE,manifest_sha256=MANIFEST_HASH,checks=[],refs_updated=False)
  for name in CHECKS:
   log=out/(name+'.log')
   with log.open('wb') as stream:
    proc=subprocess.run([sys.executable,'-B','morphhdl/scripts/'+name],cwd=root,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1'),stdout=stream,stderr=subprocess.STDOUT,timeout=900,check=False)
   receipt['checks'].append(dict(script=name,returncode=proc.returncode,log_sha256=digest(log.read_bytes())))
   (out/'stage-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');require(proc.returncode==0,'source preflight failed: '+name)
  require(os.environ.get('GITHUB_REPOSITORY')==m.REPOSITORY,'wrong authorized repository')
  uploaded=set();requests=dict(source=db.request_for(m,root,OLD_SOURCE,SOURCE,SOURCE_TREE,uploaded),seal=db.request_for(m,root,SOURCE,SEAL,SEAL_TREE,uploaded))
  (out/'connector-tree-requests.json').write_text(json.dumps(requests,indent=2)+'\n')
  for label,sha in [('source',SOURCE),('seal',SEAL)]:
   (out/(label+'-commit.txt')).write_bytes(m.git(root,'cat-file','commit',sha));m.git(root,'update-ref','refs/heads/budget-transport-'+label,sha)
  bundle=out/'reviewed-budget.bundle';m.git(root,'bundle','create',str(bundle),'refs/heads/budget-transport-source','refs/heads/budget-transport-seal','^'+BASE,'^'+TARGET)
  (out/'bundle.sha256').write_text(digest(bundle.read_bytes())+'  reviewed-budget.bundle\n')
  receipt['status']='exact-objects-prepared';(out/'stage-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
 else:
  receipt=json.loads((out/'stage-receipt.json').read_text())
  require(receipt['source']==SOURCE and receipt['seal']==SEAL and [e['script'] for e in receipt['checks']]==CHECKS,'preflight receipt changed')
  for e in receipt['checks']:require(e['returncode']==0 and digest((out/(e['script']+'.log')).read_bytes())==e['log_sha256'],'missing or altered source check')
  require(m.git(root,'rev-parse','HEAD').decode().strip()==SEAL,'head changed')
  for tree in [SOURCE_TREE,SEAL_TREE]:db.wait_for_tree(m,tree)
  staged=[m.stage_commit(value[label+'_raw'],sha) for label,sha in [('source',SOURCE),('seal',SEAL)]]
  (out/'commit-staging-receipt.json').write_text(json.dumps(dict(objects=staged,refs_updated=False),indent=2)+'\n')
 require(not m.git(root,'status','--porcelain','--untracked-files=all'),'source changed during staging')
 print('Reviewed budget '+args.phase+' PASS; no remote refs updated')

# Existing transport helper is immutable, independently pinned before import.
DATABASE_BLOB='d66ab890e97211b38b25bce2a5349dabadcd1c12'
if __name__=='__main__':main()
