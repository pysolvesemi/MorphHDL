#!/usr/bin/env python3
"""CI-only, pinned composition of the independently qualified PR187/189 sources.

No compiler, ordinary test, oracle or proof-domain changes are permitted. The
source candidate is exported as immutable blobs for explicit publication; this
worker never moves a ref or tries to publish workflow files with Actions auth.
"""
from __future__ import annotations
import argparse, ast, copy, hashlib, importlib.util, json, os, re, subprocess, sys, tempfile, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LEFT = '1fb79c663b3db9e6a162fd48c08632c24f63c73e'
RIGHT = 'f5049ae2abfe5a47cd1fac3574ea08d630bd183f'
COMMON = '27af65abbee0d2334d6be7a6e4e2408b8af32fd9'
BOOTSTRAP = 'b2721b8e8182de277925916874002f5f93d214e2'
SELF = 'repro/cdc-independent-parameters/prepare_pr187_integration.py'
CONTROL = '.github/workflows/pr189-pr187-integration.yml'
PASS = '.github/workflows/morphhdl-passes.yml'
BOUNDARY = 'morphhdl-passes/scripts/test-boundary-guard.sh'
REGISTRY = 'morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json'
NATIVE = 'morphhdl/contracts/native-source-preservation.json'
REVIEW = 'morphhdl/contracts/increment-55-native-change-review.json'
CDC = 'morphhdl/scripts/check-cdc-successor-source.py'
INC61 = 'morphhdl/scripts/check-increment-61-source-review.py'
INHERITED = 'morphhdl/scripts/test-increment-59h-inherited-source-scope.py'
OUTER = 'morphhdl/scripts/check-increment-62-wa08-source-overlay.py'
CONTRACT = 'morphhdl/contracts/increment-62-wa08-source-overlay.json'
ADAPTERS = {PASS, BOUNDARY, REGISTRY, NATIVE, REVIEW, CDC, INC61, INHERITED}
EVIDENCE = Path(os.environ.get('EVIDENCE', '/tmp/pr189-pr187-evidence'))


def require(ok, detail):
    if not ok: raise RuntimeError('PR189/187 integration: ' + detail)


def git(*args, cwd=ROOT):
    return subprocess.check_output(['git', '--literal-pathspecs', *args], cwd=cwd, timeout=120)


def digest(raw): return hashlib.sha256(raw).hexdigest()
def source(ref, path): return git('show', ref + ':' + path)
def text(ref, path): return source(ref, path).decode()
def changed(a, b='HEAD'): return set(git('diff', '--no-renames', '--name-only', a, b).decode().splitlines())


def replace_once(text, before, after):
    require(text.count(before) == 1, 'ambiguous replacement: ' + before[:120])
    return text.replace(before, after, 1)


def put(path, value):
    (ROOT / path).write_bytes(value.encode() if isinstance(value, str) else value)


def commit(message, paths):
    require(set(git('diff', '--name-only').decode().splitlines()) == paths, 'unexpected pending source paths')
    git('add', '--', *sorted(paths))
    git('commit', '-q', '-m', message + ' [skip ci]')
    return git('rev-parse', 'HEAD').decode().strip()


def logic(path):
    return '/src/main/' in path or '/src/test/' in path or (path.startswith('morphhdl-passes/examples/') and path.endswith('.scala'))


def preserve_logic():
    left = {p for p in changed(COMMON, LEFT) if logic(p)}
    right = {p for p in changed(COMMON, RIGHT) if logic(p)}
    require(not left & right, 'qualified implementation/test edits overlap')
    for ref, paths in ((LEFT, left), (RIGHT, right)):
        git('merge-base', '--is-ancestor', ref, 'HEAD')
        for path in paths:
            require(git('ls-tree', 'HEAD', '--', path) == git('ls-tree', ref, '--', path), 'qualified source identity changed: ' + path)
            require((ROOT / path).read_bytes() == source(ref, path), 'qualified worktree bytes changed: ' + path)
    return {'pr189': sorted(left), 'pr187': sorted(right)}


def prepare_native():
    parents = {ref: json.loads(source(ref, NATIVE)) for ref in (LEFT, RIGHT)}
    reviews = {ref: json.loads(source(ref, REVIEW)) for ref in (LEFT, RIGHT)}
    entries = {ref: {e['path']: e for e in doc['entries']} for ref, doc in parents.items()}
    edit_records = {ref: {e['path']: e for e in doc['files']} for ref, doc in reviews.items()}
    result, review = copy.deepcopy(parents[RIGHT]), copy.deepcopy(reviews[RIGHT])
    for key in result:
        if key not in ('entries', 'source_roots'): require(parents[LEFT][key] == result[key], 'native schema/baseline drift: ' + key)
    require({k:v for k,v in reviews[LEFT].items() if k!='files'} == {k:v for k,v in review.items() if k!='files'}, 'native review baseline differs')
    chosen, records = [], []
    for path in sorted(set(entries[LEFT]) | set(entries[RIGHT])):
        matches = [ref for ref in (LEFT, RIGHT) if path in entries[ref] and digest((ROOT/path).read_bytes()) == entries[ref][path]['approved']['sha256']]
        require(bool(matches), 'no qualified native record matches: ' + path)
        ref = matches[0]
        if len(matches) == 2:
            require(entries[LEFT][path] == entries[RIGHT][path] and edit_records[LEFT][path] == edit_records[RIGHT][path], 'ambiguous native review: '+path)
        chosen.append(entries[ref][path]); records.append(edit_records[ref][path])
    require(len(chosen)==49, 'native union inventory changed')
    result['entries'], review['files'] = chosen, records
    left_roots={e['path']:e for e in parents[LEFT]['source_roots']}
    for item in result['source_roots']:
        require(item['baseline_tree']==left_roots[item['path']]['baseline_tree'], 'native baseline tree changed')
        item['approved_tree']=git('rev-parse', 'HEAD:'+item['path']).decode().strip()
    put(NATIVE, json.dumps(result, indent=2)+'\n'); put(REVIEW, json.dumps(review, indent=2)+'\n')


def prepare_adapters():
    current = text(LEFT, CDC)
    current = replace_once(current, 'TARGET = "'+COMMON+'"', 'TARGET = "'+RIGHT+'"\nPREVIOUS_CDC = "'+LEFT+'"\nCOMMON_BASE = "'+COMMON+'"\nREGRESSION_BASE = "5374b958f8f94114b1ed46a3069845d580886da9"')
    current = replace_once(current, 'SUCCESSOR_PATHS = frozenset("""\n', 'SUCCESSOR_PATHS = frozenset("""\n'+CONTROL+'\n'+SELF+'\n')
    extra_verify = '''    git(root, "merge-base", "--is-ancestor", PREVIOUS_CDC, "HEAD")
    require(git(root, "merge-base", PREVIOUS_CDC, TARGET).decode().strip() == COMMON_BASE,
            "qualified parent ancestry changed")
    def implementation_paths(ref):
        return {p for p in git(root, "diff", "--name-only", COMMON_BASE, ref).decode().splitlines()
                if "/src/main/" in p or "/src/test/" in p or
                (p.startswith("morphhdl-passes/examples/") and p.endswith(".scala"))}
    cdc_paths, lane_paths = implementation_paths(PREVIOUS_CDC), implementation_paths(TARGET)
    require(not cdc_paths.intersection(lane_paths), "qualified implementation edits overlap")
    for ref, paths in ((PREVIOUS_CDC, cdc_paths), (TARGET, lane_paths)):
        for path in sorted(paths):
            require(git(root, "ls-tree", "HEAD", "--", path) == git(root, "ls-tree", ref, "--", path),
                    "qualified implementation mode/blob changed: " + path)
            require((root / path).read_bytes() == git(root, "show", ref + ":" + path),
                    "qualified implementation bytes changed: " + path)
'''
    current=replace_once(current, '    load(root, OVERLAY).verify(root)\n', '    load(root, OVERLAY).verify(root)\n'+extra_verify)
    before=current.split('def verify_predecessor(',1)[1].split('\n\ndef self_test(',1)[0]
    after='''root: Path = ROOT, self_test: bool = False) -> None:
    # Neither parent's original checker is edited or projected onto current code.
    # Both historical reviews remain required, after exact current-source checks.
    for ref in (PREVIOUS_CDC, TARGET):
        with tempfile.TemporaryDirectory(prefix="pr189-qualified-parent-") as temporary:
            checkout = Path(temporary) / "source"
            git(root, "worktree", "add", "--quiet", "--detach", str(checkout), ref)
            try:
                command = [sys.executable, str(checkout / INC61)]
                if self_test:
                    command.append("--self-test")
                subprocess.run(command, cwd=checkout, check=True, timeout=600)
            finally:
                git(root, "worktree", "remove", "--force", str(checkout))
'''
    current=replace_once(current, before, after)
    extra = [p for p in sorted(changed(COMMON, RIGHT)) if logic(p) and ('/src/main/' in p or p.startswith('morphhdl-passes/examples/'))]
    require(len(extra)==8, 'lane implementation inventory changed')
    extra += ['morphhdl/scripts/check-increment-61-publication-artifacts.py','morphhdl/scripts/test-increment-61-publication-artifacts.py',REGISTRY,BOUNDARY,CONTROL,SELF,CDC]
    needle='                "repro/cdc-independent-parameters/check_consumers.py",\n'
    current=replace_once(current, needle, needle+''.join('                '+json.dumps(p)+',\n' for p in extra))
    current=replace_once(current, 'require(controls == 13,', 'require(controls == 28,')
    put(CDC,current)
    cdc_loader='''def _cdc_successor(root: Path):
    import importlib.util
    path = root / "morphhdl/scripts/check-cdc-successor-source.py"
    if not path.exists():
        return None
    if not path.is_file() or path.is_symlink() or sha256(path.read_bytes()) != "CHECKER_DIGEST":
        raise RuntimeError("PR189 successor source: linked or changed integration checker")
    spec = importlib.util.spec_from_file_location("increment61_cdc_successor", path)
    require(spec is not None and spec.loader is not None, "missing CDC successor checker")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


'''.replace('CHECKER_DIGEST',digest((ROOT/CDC).read_bytes()))
    inc=text(RIGHT,INC61)
    inc=replace_once(inc,'def _lane_successor(root: Path):',cdc_loader+'def _lane_successor(root: Path):')
    require(inc.count('successor = _lane_successor(')==3,'Increment61 routing inventory changed')
    inc=inc.replace('successor = _lane_successor(root)', 'successor = _cdc_successor(root) or _lane_successor(root)')
    inc=inc.replace('successor = _lane_successor(ROOT)', 'successor = _cdc_successor(ROOT) or _lane_successor(ROOT)')
    inc=replace_once(inc,'            successor.verify(ROOT)\n            print(successor.LANE)',
        '            import contextlib, sys\n            with contextlib.redirect_stdout(sys.stderr):\n                successor.verify(ROOT)\n            print(successor.REGRESSION_BASE if hasattr(successor, "REGRESSION_BASE") else successor.LANE)')
    put(INC61,inc)
    old, lane = text(LEFT,PASS),text(RIGHT,PASS)
    start='          if [[ "$SOURCE_HEAD_REF" == agent/cdc-independent-parameter-consumers ]]; then\n'
    end='          elif [[ "$SOURCE_HEAD_REF" == agent/increment-61-one-file-per-component ]]; then\n'
    route=start+old.split(start,1)[1].split(end,1)[0]
    route=route.replace('integrated='+COMMON,'integrated='+RIGHT).replace('audit_source=61d1fe0dcac0b52856620944a2d7426fd1390a48','audit_source=3990185a2701f652c1a47c8b8f244f9bf0d32974')
    route=route.replace('audit_head_ref=agent/wa-10-inherited-audit-timeout','audit_head_ref=agent/lane-when-expression-inlining').replace('audit_base_sha=3b547ae5622ae212c17f6e67cc96924127a46a41','audit_base_sha='+COMMON)
    lane=replace_once(lane,end.replace('elif','if',1),route+end)
    put(PASS,lane)
    old, lane = text(LEFT,BOUNDARY),text(RIGHT,BOUNDARY)
    require(old.split('\nPY\n',1)[1]==lane.split('\nPY\n',1)[1], 'original boundary cases diverged')
    inherited=lane.split('def validate_source_routing(text):',1)[1].split('\nPY\n',1)[0]
    inherited='def _validate_increment61_route(text):'+inherited
    # Isolate only the legacy conditional arm for its original single-arm
    # assertions. The sixteen current CDC-route mutations above still inspect
    # the actual workflow. All seven legacy mutations below alter it too.
    wrapper='''def validate_source_routing(text):
    cdc = '          if [[ "$SOURCE_HEAD_REF" == agent/cdc-independent-parameter-consumers ]]; then\\n'
    legacy = '          elif [[ "$SOURCE_HEAD_REF" == agent/increment-61-one-file-per-component ]]; then\\n'
    if text.count(cdc) != 1 or text.count(legacy) != 1:
        raise ValueError("missing authenticated integration or Increment61 route")
    before, rest = text.split(cdc, 1)
    _, after = rest.split(legacy, 1)
    _validate_increment61_route(before + legacy.replace('elif', 'if', 1) + after)

'''
    inherited=replace_once(inherited, 'try:\n    validate_source_routing(workflow)',wrapper+'try:\n    validate_source_routing(workflow)')
    put(BOUNDARY,replace_once(old,'\nPY\n','\n'+inherited+'\nPY\n'))
    inherited=text(RIGHT,INHERITED)
    inherited=replace_once(inherited,'elif mutation == "suffix" and relative in overlay_paths:', 'elif mutation in ("suffix", "inside") and relative in overlay_paths:')
    put(INHERITED,inherited)
    registry=json.loads(source(RIGHT,REGISTRY))
    original=copy.deepcopy(registry)
    require(len(registry['files'])==98,'formal signature inventory changed')
    for path in (PASS,BOUNDARY): registry['files'][path]=digest((ROOT/path).read_bytes())
    require({p for p in registry['files'] if registry['files'][p]!=original['files'][p]}=={PASS,BOUNDARY},'signature refresh exceeded route adapters')
    put(REGISTRY,json.dumps(registry,indent=2,sort_keys=True)+'\n')


def seal(anchor):
    values=[json.loads(source(ref,CONTRACT)) for ref in (LEFT,RIGHT)]
    records={}
    for value in values:
        require(value['base']==values[0]['base'] and value['helper_normalized_sha256']==values[0]['helper_normalized_sha256'],'outer baseline/algorithm differs')
        for entry in value['files']:
            previous=records.get(entry['path'])
            if previous: require((previous['mode'],previous['before_sha256'])==(entry['mode'],entry['before_sha256']),'reviewed baseline/mode differs')
            records[entry['path']]=copy.deepcopy(entry)
    require(len(records)==381, 'original outer union inventory changed')
    for path in (SELF,CONTROL):
        require(path not in records and not git('ls-tree',values[0]['base'],'--',path),'new integration path already exists in baseline')
        records[path]={'path':path,'mode':'100644','before_sha256':None,'after_sha256':digest((ROOT/path).read_bytes())}
    for path,record in records.items():
        raw=(ROOT/path).read_bytes()
        expected_mode='100755' if (ROOT/path).stat().st_mode & 0o111 else '100644'
        require(expected_mode==record['mode'],'reviewed mode changed: '+path)
        if path not in ADAPTERS|{SELF,CONTROL}:
            require(any(raw==source(ref,path) for ref in (LEFT,RIGHT) if git('ls-tree',ref,'--',path)), 'unreviewed successor source: '+path)
        record['after_sha256']=digest(raw)
    value=copy.deepcopy(values[0]); value['files']=[records[p] for p in sorted(records)];value['final_source_commit']=anchor
    put(CONTRACT,json.dumps(value,indent=2)+'\n')
    helper=source(LEFT,OUTER)
    normalized=lambda raw:re.sub(rb'^CONTRACT_SHA256 = "[^"]+"$',b'CONTRACT_SHA256 = "MANIFEST_HASH"',raw,count=1,flags=re.M)
    require(normalized(helper)==normalized(source(RIGHT,OUTER)) and digest(normalized(helper))==value['helper_normalized_sha256'],'outer verifier algorithm changed')
    helper,n=re.subn(rb'^CONTRACT_SHA256 = "[^"]+"$',('CONTRACT_SHA256 = "'+digest((ROOT/CONTRACT).read_bytes())+'"').encode(),helper,count=1,flags=re.M)
    require(n==1,'missing outer manifest hash');put(OUTER,helper)
    return commit('PR189: seal exact PR187 integration source', {OUTER,CONTRACT})


def check_sources():
    checks=[['git','diff','--check']]
    for path in (OUTER,INC61,'morphhdl/scripts/check-native-source-preservation.py','morphhdl/scripts/check-typed-layering-ir.py'):
        checks += [['python3',path],['python3',path,'--self-test']]
    checks += [['python3','morphhdl/scripts/check-typed-native-source-overlay.py'],
               ['python3','morphhdl/scripts/check-increment-58-retirement.py'],
               ['python3','morphhdl/scripts/check-production-retirement.py'],
               ['bash','morphhdl/scripts/check-typed-vec-boundary.sh'],
               ['python3','repro/cdc-independent-parameters/test_layering_root.py'],
               ['python3','morphhdl/scripts/check-increment-60g-source-scope.py'],
               ['python3',INHERITED],
               ['python3','morphhdl/scripts/test-increment-59c-inherited-source-scope.py'],
               ['python3','morphhdl/scripts/check-increment-60f-equivalence-closure.py','--source-only'],
               ['python3','morphhdl/scripts/check-lane-when-source-scope.py'],
               ['python3','morphhdl/scripts/test-lane-when-source-scope.py'],
               ['bash',BOUNDARY],
               ['python3','morphhdl-passes/scripts/validate_wire_assignment_equivalence.py','--self-test'],
               ['python3','morphhdl/scripts/test-increment-61-publication-artifacts.py']]
    rows=[]
    for n,command in enumerate(checks,1):
        log=EVIDENCE/('source-check-'+str(n)+'.log')
        with log.open('w') as output:
            result=subprocess.run(command,cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,timeout=900)
        print('INTEGRATION_SOURCE_CHECK',n,result.returncode,' '.join(command),flush=True)
        rows.append({'command':command,'exit_code':result.returncode,'log':log.name})
        (EVIDENCE/'source-checks.json').write_text(json.dumps(rows,indent=2)+'\n')
        if result.returncode: print(log.read_text()[-12000:],flush=True)
        require(result.returncode==0,'source check failed: '+' '.join(command))
    require(len(checks)==23,'source check inventory changed')
    require(not git('status','--porcelain','--untracked-files=all'),'source checks changed checkout')
    return rows


def post(path,value):
    request=urllib.request.Request('https://api.github.com/repos/pysolvesemi/MorphHDL/'+path,
        data=json.dumps(value).encode(),headers={'Authorization':'Bearer '+os.environ['GH_OBJECT_TOKEN'],
        'Accept':'application/vnd.github+json','Content-Type':'application/json'},method='POST')
    with urllib.request.urlopen(request,timeout=60) as response:return json.load(response)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--mode',choices=['prepare','seal'],default='prepare');args=parser.parse_args()
    EVIDENCE.mkdir(parents=True,exist_ok=True)
    head=git('rev-parse','HEAD').decode().strip()
    require(head==os.environ['EXPECTED_INPUT'],'wrong exact source input')
    require(not git('status','--porcelain','--untracked-files=all'),'dirty input')
    git('merge-base','--is-ancestor',BOOTSTRAP,head)
    require(git('merge-base',LEFT,RIGHT).decode().strip()==COMMON,'qualified common base moved')
    logic_receipt=preserve_logic()
    if args.mode=='prepare':
        require(changed(BOOTSTRAP)=={SELF,CONTROL},'unreviewed bootstrap delta')
        prepare_native();prepare_adapters()
        anchor=commit('PR189: compose qualified CDC and lane source reviews',ADAPTERS)
    else:
        # Seal only an externally published source tree identical to the
        # successful preparation artifact; the workflow authenticates receipt.
        require(os.environ.get('CHECKED_SOURCE_TREE')==git('rev-parse','HEAD^{tree}').decode().strip(),'unverified published source tree')
        anchor=head
    candidate=seal(anchor)
    rows=check_sources();preserve_logic()
    (EVIDENCE/'candidate.patch').write_bytes(git('diff','--binary',head,'HEAD'))
    git('bundle','create',str(EVIDENCE/'candidate.bundle'),'HEAD','^'+head)
    identity={'input':head,'source_anchor':anchor,'candidate':candidate,'tree':git('rev-parse','HEAD^{tree}').decode().strip(),
              'source_tree':git('rev-parse',anchor+'^{tree}').decode().strip(),'left':LEFT,'target':RIGHT,
              'logic_sources':logic_receipt,'source_groups':len(rows),'compiler_sources_unchanged':True,'remote_ref_written':False}
    (EVIDENCE/'source-pass.json').write_text(json.dumps(identity,indent=2)+'\n')
    if args.mode=='prepare':
        entries=[]
        require(changed(head,anchor)==ADAPTERS,'unexpected complete source adapter delta')
        for path in sorted(ADAPTERS):
            raw=source(anchor,path);mode,kind,sha=git('ls-tree',anchor,'--',path).decode().split('\t')[0].split()
            require(kind=='blob','unsupported source object')
            posted=post('git/blobs',{'content':raw.decode(),'encoding':'utf-8'})
            require(posted['sha']==sha,'exported source blob identity differs')
            entries.append({'path':path,'mode':mode,'type':'blob','sha':sha})
        receipt={'input':head,'base_tree':git('rev-parse',head+'^{tree}').decode().strip(),'source_tree':identity['source_tree'],'entries':entries}
        (EVIDENCE/'source-handoff.json').write_text(json.dumps(receipt,indent=2)+'\n')
        print('PR189_PR187_CHECKED_SOURCE_HANDOFF '+json.dumps(receipt),flush=True)
    else:
        require(changed(head)=={OUTER,CONTRACT},'metadata-only export changed source')
        entries=[]
        for path in sorted((OUTER,CONTRACT)):
            raw=source(candidate,path);blob=post('git/blobs',{'content':raw.decode(),'encoding':'utf-8'})
            entries.append({'path':path,'mode':'100644','type':'blob','sha':blob['sha']})
        tree=post('git/trees',{'base_tree':git('rev-parse',head+'^{tree}').decode().strip(),'tree':entries})
        require(tree['sha']==identity['tree'],'metadata tree mismatch')
        fields=git('show','-s','--format=%an%x00%ae%x00%aI%x00%cn%x00%ce%x00%cI%x00%B',candidate).decode().split('\0',6)
        obj=post('git/commits',{'message':fields[6].rstrip('\n')+'\n','tree':tree['sha'],'parents':[head],
            'author':dict(zip(('name','email','date'),fields[:3])), 'committer':dict(zip(('name','email','date'),fields[3:6]))})
        require(obj['sha']==candidate,'metadata commit mismatch')
        print('PR189_PR187_SEALED_CANDIDATE '+json.dumps(identity),flush=True)
    print('Source checks passed. No branch or target ref was updated.',flush=True)

if __name__=='__main__':main()
