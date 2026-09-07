"""Reconstruct one reviewed integration tree on an isolated staging branch only."""
import gzip,hashlib,json,os,shutil,subprocess,tempfile
from pathlib import Path
BRANCH='agent/60g-59h-stage-20260907'
ROOT=Path('.github/60g-59h-stage')
WORKFLOW='.github/workflows/60g-59h-stage.yml'
meta=json.loads((ROOT/'metadata.json').read_text())
def git(*args,**kwargs):return subprocess.check_output(['git',*args],text=True,**kwargs).strip()
assert os.environ['GITHUB_REPOSITORY']=='pysolvesemi/MorphHDL'
assert os.environ['GITHUB_REF']=='refs/heads/'+BRANCH
assert git('rev-parse','HEAD')==os.environ['GITHUB_SHA']
assert git('rev-parse','HEAD^')==meta['base']
assert git('ls-remote','origin','refs/heads/'+BRANCH).split()[0]==os.environ['GITHUB_SHA']
parts=sorted(ROOT.glob('part-*.gz.part'))
assert [p.name for p in parts]==['part-0.gz.part','part-1.gz.part']
patch=gzip.decompress(b''.join(p.read_bytes() for p in parts))
assert hashlib.sha256(patch).hexdigest()==meta['patch_sha256']
with tempfile.TemporaryDirectory() as td:
 p=Path(td)/'source.patch';p.write_bytes(patch)
 subprocess.run(['git','checkout',meta['prior'],'--',*meta['from_prior']],check=True)
 subprocess.run(['git','apply','--check','--index',str(p)],check=True)
 subprocess.run(['git','apply','--index',str(p)],check=True)
 subprocess.run(['git','diff','--cached','--check'],check=True)
 index=Path(td)/'verify.index';shutil.copyfile(git('rev-parse','--git-path','index'),index)
 env=dict(os.environ,GIT_INDEX_FILE=str(index))
 helpers=git('ls-files','--',str(ROOT),WORKFLOW).splitlines()
 assert WORKFLOW in helpers
 subprocess.run(['git','update-index','--force-remove','--',*helpers],env=env,check=True)
 canonical=git('write-tree',env=env)
 assert canonical==meta['tree'],('complete reviewed tree mismatch',canonical)
 # Store workflow bytes under non-workflow aliases. GitHub's contents-only job
 # token never writes a workflow path; the connected API publishes these blobs.
 for path,sha in meta['workflows'].items():
  assert git('hash-object',path)==sha
  alias=ROOT/(Path(path).name+'.blob')
  alias.write_bytes(Path(path).read_bytes())
  subprocess.run(['git','add','--',str(alias)],check=True)
  if git('ls-tree',meta['base'],'--',path):
   subprocess.run(['git','restore','--source='+meta['base'],'--staged','--worktree','--',path],check=True)
  else:
   subprocess.run(['git','rm','-f','--',path],check=True)
 changes=git('diff','--cached','--name-only').splitlines()
 assert changes and not any(p.startswith('.github/workflows/') for p in changes)
 subprocess.run(['git','diff','--cached','--check'],check=True)
assert git('ls-remote','origin','refs/heads/'+BRANCH).split()[0]==os.environ['GITHUB_SHA']
git('config','user.name','github-actions[bot]')
git('config','user.email','41898282+github-actions[bot]@users.noreply.github.com')
subprocess.run(['git','commit','-m','Stage exact 60g/59h source and workflow blobs for API publication [skip ci]'],check=True)
subprocess.run(['git','push','origin','HEAD:refs/heads/'+BRANCH],check=True)
print('STAGED_COMMIT='+git('rev-parse','HEAD'),flush=True)
print('STAGED_TREE='+git('rev-parse','HEAD^{tree}'),flush=True)
print('REVIEWED_FINAL_TREE='+canonical,flush=True)
print('No PR update, test qualification, completion or integration merge performed.',flush=True)
