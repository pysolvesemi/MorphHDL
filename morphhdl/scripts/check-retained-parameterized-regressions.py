#!/usr/bin/env python3
"""Exact current regression inventory after user-authorized wire-pass retirement.

The fixed catalog composes the 59i and 64/65/66 inventories, subtracting only
retired wire suites and the two optimizer cases in the canonical handoff suite,
and adds the user-requested constant aggregate/loop, unpacked-array and
comments API compiler/output cases.
No missing, extra, duplicate, failed, skipped or old-source result is accepted.
"""
import argparse,hashlib,json,subprocess,tempfile,time
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
CONTRACT='morphhdl/contracts/retained-parameterized-regressions.json'
CONTRACT_SHA256='d2a6d30bb2366e93642bf6d551a0ec229445414c1209b2a6cab605a6455edf41'
def require(ok,detail):
 if not ok:raise RuntimeError('Retained parameterized regressions: '+detail)
def project_reports(directory,expected,started_ns=0):
 actual={}
 for p in directory.glob('TEST-*.xml'):
  require(p.is_file() and not p.is_symlink(),'linked report')
  require(p.stat().st_mtime_ns >= started_ns,'report predates this run: '+str(p))
  raw=p.read_bytes();require(b'<!DOCTYPE' not in raw and b'<!ENTITY' not in raw,'XML declarations')
  r=ET.fromstring(raw);name=r.get('name')
  require(r.tag=='testsuite' and name in expected and name not in actual,'unexpected/duplicate suite: '+str(name))
  require(p.name=='TEST-'+name+'.xml','report filename mismatch')
  cases=r.findall('testcase');names=[c.get('name') for c in cases]
  require(sorted(names)==sorted(expected[name]) and len(names)==len(set(names)),'case inventory differs: '+name)
  require(int(r.get('tests','-1'))==len(names),'test count differs: '+name)
  require(all(r.get(k)=='0' for k in ['failures','errors','skipped']),'unsuccessful suite: '+name)
  require(not any(list(r.iter(k)) for k in ['failure','error','skipped']),'unsuccessful testcase: '+name)
  require(all(c.get('classname')==name for c in cases),'case owner differs: '+name)
  actual[name]={'tests':len(names),'sha256':hashlib.sha256(raw).hexdigest()}
 require(set(actual)==set(expected),'missing suite reports: '+str(sorted(set(expected)-set(actual))))
 return actual

def self_test():
 with tempfile.TemporaryDirectory() as d:
  p=Path(d)/'TEST-example.Suite.xml'
  good='<testsuite name="example.Suite" tests="1" failures="0" errors="0" skipped="0"><testcase classname="example.Suite" name="kept"/></testsuite>'
  p.write_text(good);project_reports(Path(d),{'example.Suite':['kept']})
  try:project_reports(Path(d),{'example.Suite':['kept']},p.stat().st_mtime_ns+1)
  except RuntimeError:pass
  else:raise RuntimeError('stale report accepted')
  for bad in [good.replace('skipped="0"','skipped="1"'),good.replace('name="kept"','name="lost"'),good.replace('/></testsuite>','><failure/></testcase></testsuite>'),good.replace('tests="1"','tests="2"'),good.replace('classname="example.Suite"','classname="other"')]:
   p.write_text(bad)
   try:project_reports(Path(d),{'example.Suite':['kept']})
   except (RuntimeError,ET.ParseError):pass
   else:raise RuntimeError('negative control accepted')
  p.unlink()
  try:project_reports(Path(d),{'example.Suite':['kept']})
  except RuntimeError:pass
  else:raise RuntimeError('missing report accepted')
 print('Retained regression inventory: positive control and 7 rejection controls passed')
def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--self-test',action='store_true');parser.add_argument('--output',type=Path);parser.add_argument('--begin',action='store_true');parser.add_argument('--session',type=Path);a=parser.parse_args()
 if a.self_test:self_test();return
 raw=(ROOT/CONTRACT).read_bytes();require(hashlib.sha256(raw).hexdigest()==CONTRACT_SHA256,'catalog digest differs');c=json.loads(raw)
 require(c['schema']==1,'schema differs')
 head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
 tree=subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT,text=True).strip()
 require(not subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],cwd=ROOT),'dirty candidate')
 for ref in ['basis_59i','basis_batch']:subprocess.run(['git','merge-base','--is-ancestor',c[ref],head],cwd=ROOT,check=True)
 for path,sha in c['test_source_sha256'].items():
  p=ROOT/path;require(p.is_file() and not p.is_symlink() and hashlib.sha256(p.read_bytes()).hexdigest()==sha,'test source differs: '+path)
 require(a.session is not None,'--session is required to bind reports to a fresh run')
 if a.begin:
  require(not a.session.exists(),'session already exists')
  for project in c['projects']:
   require(not list((ROOT/project/'target/test-reports').glob('TEST-*.xml')),'remove stale reports before starting: '+project)
  a.session.parent.mkdir(parents=True,exist_ok=True)
  a.session.write_text(json.dumps({'head':head,'tree':tree,'catalog_sha256':CONTRACT_SHA256,'started_ns':time.time_ns()},indent=2)+'\n')
  print('Started retained regression session for',head)
  return
 session=json.loads(a.session.read_text())
 require(session['head']==head and session['tree']==tree and session['catalog_sha256']==CONTRACT_SHA256,'session belongs to another candidate')
 require(0 < session['started_ns'] <= time.time_ns(),'invalid session start')
 result={project:project_reports(ROOT/project/'target/test-reports',suites,session['started_ns']) for project,suites in c['projects'].items()}
 require(subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()==head,'source moved')
 receipt={'head':head,'tree':tree,'session':session,'catalog_sha256':CONTRACT_SHA256,'projects':result,'retired_suites':c['retired_suites']}
 if a.output:a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(receipt,indent=2)+'\n')
 print('Retained parameterized regressions:',sum(x['tests'] for p in result.values() for x in p.values()),'tests passed')
if __name__=='__main__':main()
