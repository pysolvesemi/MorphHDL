#!/usr/bin/env python3
"""Authenticate the 59i + remaining-increment integration and wire-pass retirement.

Historical wire certificates describe deleted implementations. This successor
records every current delta against the ancestry-preserving integration commit,
including deletions, and checks exact HEAD/index/worktree identity. Native-source
preservation, retained regression inventories and behavioral proofs stay separate
mandatory gates; this source seal is never behavioral qualification evidence.
"""
import argparse,hashlib,json,re,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BASIS='bfed00f992281d83dde20dfabf319d8f1ea0bc57'
PARENTS=('23dc9248a09157c25030f6603fbdbd0d01f7ab6d','6bb250972f06ca55c9cfbd7100126adad9e81569')
SELF='morphhdl/scripts/check-parameterized-integration-source.py'
CONTRACT='morphhdl/contracts/parameterized-integration-source.json'
CONTRACT_SHA256='05a3f5aaca3f3d6a6053ebe11f2ad28b7016860d93558173be78f29c56d5ef8e'
RETIRED=('morphhdl-passes/src','morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md','morphhdl/src/main/scala/morphhdl/MorphWireAssignmentPasses.scala','morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala','core/src/main/scala/spinal/core/internals/NativePureExpressionCopy.scala','core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala','morphhdl/src/main/scala/spinal/core/internals/NativeWireAssignmentMetadata.scala')
FORBIDDEN=('MorphWireAssignmentPasses','WireAssignmentProductionBridge','VerilogEmitterExpressionInlining','NativePureExpressionCopy','NativeWireAssignmentMetadata','morphhdl.passes.')
def require(ok,detail):
 if not ok:raise RuntimeError('Parameterized integration source: '+detail)
def git(root,*args):return subprocess.check_output(['git','--literal-pathspecs',*args],cwd=root,stderr=subprocess.PIPE,timeout=120)
def digest(raw):return hashlib.sha256(raw).hexdigest()
def normalized(raw):return re.sub(rb"^CONTRACT_SHA256='[^']*'$",b"CONTRACT_SHA256='MANIFEST_HASH'",raw,flags=re.M)
def record(root,ref,path):
 raw=git(root,'ls-tree',ref,'--',path).decode().strip()
 if not raw:return None
 metadata,found=raw.split('\t');mode,kind,oid=metadata.split();require(found==path and kind=='blob' and mode in ('100644','100755'),'unsupported tree entry: '+path)
 return {'mode':mode,'sha256':digest(git(root,'cat-file','blob',oid))}
def check_records(root,ref,files):
 for path,expected in files.items():
  require(record(root,ref,path)==expected,'committed source differs: '+path)
  p=root/path
  if expected is None:require(not p.exists() and not p.is_symlink(),'retired path reintroduced: '+path)
  else:
   require(p.is_file() and not p.is_symlink() and digest(p.read_bytes())==expected['sha256'],'working source differs: '+path)
def retirement(root):
 for path in RETIRED:require(not (root/path).exists(),'wire implementation still exists: '+path)
 for file in ('build.sbt','build.mill'):
  s=(root/file).read_text();require('morphhdl-passes' not in s and 'wireAssignmentSources' not in s,'retired build input: '+file)
 for base in ('core/src/main','morphhdl/src/main','morphruntime/src/main'):
  for p in (root/base).rglob('*.scala'):
   require(not any(token in p.read_text() for token in FORBIDDEN),'retired production entry point: '+str(p))
 for path in ('morphhdl/scripts/check-wa11-boolean-width.py','docs/morphhdl/wa11-boolean-width-normalization.md','morphhdl/src/test/scala/spinal/core/BooleanWidthNormalizationTests.scala'):
  require((root/path).is_file(),'lost parameter normalization obligation: '+path)
def verify(root=ROOT):
 root=Path(root);raw=(root/CONTRACT).read_bytes();require(digest(raw)==CONTRACT_SHA256,'unreviewed source manifest')
 c=json.loads(raw);require(c['schema']==1 and c['basis']==BASIS and tuple(c['parents'])==PARENTS,'source identity differs')
 require(digest(normalized((root/SELF).read_bytes()))==c['verifier_sha256'],'source verifier differs')
 require(git(root,'show','HEAD:'+SELF)==(root/SELF).read_bytes(),'uncommitted source verifier')
 require(git(root,'show','HEAD:'+CONTRACT)==raw,'uncommitted source manifest')
 require(not git(root,'diff','--name-only') and not git(root,'diff','--cached','--name-only'),'dirty candidate')
 for ref in (BASIS,*PARENTS):git(root,'merge-base','--is-ancestor',ref,'HEAD')
 require(git(root,'rev-parse',BASIS+'^{tree}').decode().strip()==c['basis_tree'],'integration basis changed')
 changed=set(git(root,'diff','--no-renames','--name-only',BASIS,'HEAD').decode().splitlines())-{SELF,CONTRACT}
 require(changed==set(c['files']),'unreviewed added/removed source paths')
 check_records(root,'HEAD',c['files']);retirement(root)
 require(not git(root,'ls-files','--others','--exclude-standard').strip(),'untracked candidate files')
 print('Parameterized integration source: exact union and retirement verified;',len(c['files']),'reviewed paths')
 return c
def generate(root=ROOT):
 require(not git(root,'diff','--name-only') and not git(root,'diff','--cached','--name-only'),'commit source before sealing')
 retirement(root)
 files=sorted(set(git(root,'diff','--no-renames','--name-only',BASIS,'HEAD').decode().splitlines())-{SELF,CONTRACT})
 c={'schema':1,'basis':BASIS,'basis_tree':git(root,'rev-parse',BASIS+'^{tree}').decode().strip(),'parents':list(PARENTS),'verifier_sha256':digest(normalized((root/SELF).read_bytes())),'files':{p:record(root,'HEAD',p) for p in files},'authorization':'User requested merging 59i into the work branch and deleting deprecated hardware wire passes and roadmap; WA-11 remains parameterized-Verilog work. User additionally requested opt-in constant Vec and typed-loop retention, internal unpacked arrays with packed ports, preserved dimension factors and synthesis-owned storage inference. User additionally requested the comments API roadmap: explicit annotation/fluent/region documentation, opt-in source-comment capture and generated-comment-only local validation, with CI and monitoring kept paused. User then stopped whole-branch qualification to repair the reported compiler issues: contextual Vec literals, static switch scopes, native literal clone lineage, synchronizer array attributes, formal/actual width publication, implicit clock aliases and concrete-parent aggregate admission. The user explicitly resumed whole-branch qualification on 2 October 2026, superseding that pause. Qualification repairs admit exact fresh-hardware documentation attachments, preserve native post-retirement fixture carriers and reconcile the complete retained regression inventory. On 3 October the user stopped qualification again and requested direct unpacked-array reads, a lightweight lint gate, and CA-005 ANSI-port width expansion with body aliases preserved. The user requested vendor-neutral repair documentation. On 4 October the user added CA-006 through CA-010 and requested all current compiler issues be repaired before restarting qualification. The reviewed batch repairs typed-loop carrier/reset/enable ownership and packed coverage, nested combinational Vec identities and conditional scope, concise genvar publication, scalar/width formal definition sharing, standalone formal defaults, exact constant shifts, symbolic signed operand sizing and combinational event dependencies. Regression inventory and affected targeted workflows include the new cases. The user then requested CA-011 literal-actual internal carrier repairs and resumed qualification on 4 October 2026. The reviewed continuation preserves exact child actual authority, stops fixed child-input inference at ownership boundaries, and sizes retained unsized UInt values without borrowing witness widths. Local qualification additionally repairs signed Int-domain arithmetic through packed named locals, exact child-scope width publication, split-process event dependencies and native geometry registry ownership. The publication contract intentionally adopts canonical child width defaults while retaining parent actual bindings and strict full-RTL hashes. The user subsequently requested CA-012 through CA-016 repairs: authenticated affine Vec subranges and driver coverage, logarithmic Gray stage arrays, symbolic register definition sharing, and transitive scalar binding liveness. Focused regressions and workflow enrollment are retained. The user revised qualification policy on 6 October 2026: authenticate the current exact integration seal and required historical certificate commit/tree/manifest identities and ancestry once per candidate, without recursively executing historical programs. Frozen evidence remains immutable and current regression, proof, native-source and rejection gates remain mandatory; historical results do not qualify current RTL. On 4 October the user resumed qualification through completion. Qualification proceeds through targeted CI before full CI; no old-head result qualifies repaired source. On 10 October the user requested CA-028: retain ordinary Area collection loops with index-dependent branches, exact exports and child wiring. The current retirement and layering contracts explicitly enroll this frontend phase while retaining all rejection rules; native publication hooks and new regression enrollment are reviewed in this seal. On 11 October the user requested guarded previous-iteration Area Vec reads and scope-aware local declaration names while explicitly preserving every native _zz_ helper. The extension retains exact index/guard/width provenance, read-only backwards access and combinational-cycle checks, and publishes local names only after symbolic geometry is finalized. The user further requested scope-local native child instances, exact interface wrapper names and library-derived Counter composites, preserving explicit user naming and native _zz_ helpers without application Scala edits. Native naming priority provenance, exact instance/port receipts and collision allocation are reviewed with cross-version and read-only application regressions. The user then requested an explicit reusable recursive unsigned reduction module: positive COUNT, parameter-dependent packed geometry, two independently terminating children, exact native partition and connection lineage, portable domain guards, and deterministic native definition sharing. Existing reduceBalancedTree contracts remain unchanged. Compiler/library fixtures and cross-version simulation, lint, synthesis and formal gates are enrolled; application source, delivery RTL, cleanup and pins remain read-only. CA-029 through CA-031 extend authenticated constant multidimensional word access, symbolic child-input bindings, and exact tabulation construction aliases. Native review metadata and cross-version word/alias/parameter-override regression enrollment are retained; no application source is changed. CA-032 through CA-035 retain exact branch assignment publication, complete guarded Vec promotion, whole-scalar signed writes, scalar generic definition identity, and explicit authenticated sibling module references. Compiler-only fixtures retain negative ownership/domain/geometry cases and both-Scala hardware checks; application source remains unchanged. CA-036 through CA-038 retain exact signed guarded write lineage and weak branch-local Vec owner names, preserve native declaration attributes during authenticated constant-branch conversion, and keep native scalar driver cells behind read-only aggregates instead of introducing constant-driver initial blocks. Fresh cross-version manual-packing, signed, reset, pipeline, X/Z, lint and synthesis regressions preserve ownership and competing-driver rejection; application sources, RTL, cleanup, pins and checklist remain read-only. CA-039 through CA-041 authenticate shrinking recursive ports in their exact child branch, retain scoped logical Vec packing and complete unpacking lineage, and preserve module-owned finite-write arrays and scoped packing helpers. Explicit-formal definition comparison uses logical derived widths and witness-invariant packing wrappers while legacy authoring retains its original validation. Native declaration recognition admits comparisons inside ranges but still excludes initialized declarations. New cross-version recursive packed/unpacked, signed/composite, shared-default, per-component, negative identity, simulation, lint, synthesis and SAT fixtures are enrolled without modifying application sources or frozen historical evidence. CA-042 and CA-043 retain authenticated finite word-slice correlation and complete packed-write ownership, and publish mixed combinational symbolic Vec cells through guarded read-only aggregates. Exact lexical owners, native assignment identities and full-domain coverage remain checked. Both-Scala positive and negative fixtures, simulation, lint, synthesis and SAT are enrolled; application source, pins, RTL and checklist remain read-only. CA-044 authenticates repeated native read-alias occurrences for complete finite Vec writers and signed affine consumers. CA-045 permits exact direct-child packed output reads with separate write-direction validation; child internals, grandchildren, parent inputs and child outputs retain the corresponding illegal access checks. Both-Scala signed and composite recursive reference simulations, SAT, lint, synthesis and direct-child positive/negative tests extend the existing enrolled suite. The current publication golden succeeds both retained historical expectations and changes only the two reviewed child COUNT defaults from minimum 1 to authored 5 and their RTL hashes, matching b4bffb665f scalar-default semantics. Exact ABI/body checks and mutation controls remain enabled. CA-046 adds an explicit native typed recursive interface boundary, derives complete reference ports and actual bindings, authenticates exact owner/formal/interface identities and migrates the unsigned sum without changing its algorithm. CA-047 adds correlated finite affine guards, retained guard/loop/read lineage and guarded cross-loop aggregate consumption. Full-domain bounds, arithmetic limits, missing-tail and competing-write rejection remain required. Both-Scala generation, recursion safety, clocked signed/composite/bridge comparisons, Verilog-2001 lint, synthesis and SAT are enrolled in the structural/process workflow. Application sources, pins, delivered RTL, cleanup and issue checklist remain read-only.'}
 raw=(json.dumps(c,indent=2,sort_keys=True)+'\n').encode();(root/CONTRACT).write_bytes(raw)
 p=root/SELF;p.write_bytes(re.sub(rb"^CONTRACT_SHA256='[^']*'$",("CONTRACT_SHA256='"+digest(raw)+"'").encode(),p.read_bytes(),flags=re.M))
 print('Generated exact integration source seal; commit both outputs before verification')
def self_test():
 with tempfile.TemporaryDirectory() as d:
  root=Path(d);subprocess.run(['git','init','-q',d],check=True)
  for a in [('user.email','test@example.invalid'),('user.name','Source guard test')]:git(root,'config',*a)
  (root/'kept').write_text('valid\n');git(root,'add','kept');git(root,'commit','-qm','fixture')
  expected={'kept':record(root,'HEAD','kept'),'deleted':None};check_records(root,'HEAD',expected)
  for action in ('edit','reintroduce','symlink'):
   if action=='edit':(root/'kept').write_text('invalid\n')
   elif action=='reintroduce':(root/'deleted').write_text('invalid\n')
   else:(root/'kept').unlink();(root/'kept').symlink_to('missing')
   try:check_records(root,'HEAD',expected)
   except RuntimeError:pass
   else:raise RuntimeError('mutation accepted: '+action)
   for p in ('kept','deleted'):
    f=root/p
    if f.exists() or f.is_symlink():f.unlink()
   (root/'kept').write_text('valid\n')
 print('Integration source guard: positive and edit/reintroduction/symlink controls passed')
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--generate',action='store_true');p.add_argument('--self-test',action='store_true');a=p.parse_args()
 if a.self_test:self_test()
 elif a.generate:generate()
 else:verify()
