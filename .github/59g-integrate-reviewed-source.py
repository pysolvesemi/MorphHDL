from pathlib import Path
import shutil,json,hashlib,difflib,subprocess,importlib.util,re,os
G=Path(os.environ['REPAIRED_SOURCE']);H=Path(os.environ['OWNER_SOURCE']);W=Path(os.environ['JOINT_WORK']);R=W/'candidate'
(W/'merge').mkdir(parents=True,exist_ok=True)
ge={e['path']:e for e in json.loads((G/'morphhdl/contracts/increment-59g-source-review.json').read_text())['files']}
he={e['path']:e for e in json.loads((H/'morphhdl/contracts/increment-59h-source-review.json').read_text())['files']}
def reverse(entry,source):
    parts=[];position=0
    for edit in entry['edits']:
        assert source[edit['after_start']:edit['after_end']]==edit['after'].encode(),entry['path']
        parts.extend((source[position:edit['after_start']],edit['before'].encode()));position=edit['after_end']
    parts.append(source[position:]);baseline=b''.join(parts)
    assert hashlib.sha256(baseline).hexdigest()==entry['baseline_sha256'],entry['path']
    return baseline
for path in sorted(ge.keys()&he.keys()):
    baseline=reverse(ge[path],(G/path).read_bytes())
    assert baseline==reverse(he[path],(H/path).read_bytes()),path
    stem=Path(path).name
    for suffix,content in [('base',baseline),('g',(G/path).read_bytes()),('h',(H/path).read_bytes())]:
        (W/'merge'/(stem+'.'+suffix)).write_bytes(content)
    result=subprocess.run(['git','merge-file','-p','-L','59g','-L','common','-L','59h',str(W/'merge'/(stem+'.g')),str(W/'merge'/(stem+'.base')),str(W/'merge'/(stem+'.h'))],capture_output=True)
    assert result.returncode>=0 and result.returncode<=4,(path,result.stderr)
    if path.endswith('.scala'):assert result.returncode==0,path
    (W/'merge'/(stem+'.merged')).write_bytes(result.stdout)
assert not R.exists()
shutil.copytree(H,R)
base='cba4717abc9192917d819e1f84cb246162488286'
g_paths=['.github/workflows/'+n+'.yml' for n in ['increment-59b-closed-graph','increment-59b-operator-replay','increment-59b-publication','increment-59b-stage-replay','increment-59d-widening','increment-59e-composite-reduction','increment-59f-callback-graphs','increment-59g-register-bridges']]
g_paths += ['docs/morphhdl/increment-59g-register-bridges.md','docs/morphhdl/parameterized-verilog-todo.md','morphhdl/contracts/increment-59g-source-review.json']
g_paths += ['morphhdl/scripts/'+n for n in ['check-increment-59c-source-review.py','check-increment-59g-register-bridges.py','check-increment-59g-source-review.py','check-increment-60f-artifacts.py','check-increment-60f-equivalence-closure.py','test-increment-59c-inherited-source-scope.py','test-increment-59g-source-review.py','check-native-streamfifocc-boundary.sh']]
g_paths += ['morphhdl/src/main/scala/spinal/core/internals/'+n+'.scala' for n in ['ExternalParameterizedVerilogNativeFallback','TypedBalancedReductionBridgeReplay','TypedBalancedReductionCallbackPolicy','TypedBalancedReductionClosedGraph','TypedBalancedReductionStageReplay']]
g_paths += ['morphhdl/src/test/scala/nativeapplication/BalancedBridgeNativeOracle.scala']
g_paths += ['morphhdl/src/test/scala/spinal/core/internals/'+n+'.scala' for n in ['TypedBalancedReductionBridgeArtifactWriter','TypedBalancedReductionBridgePublicationTests','TypedBalancedReductionBridgeReplayTests','TypedBalancedReductionCallbackPolicyTests','TypedBalancedReductionStageReplayTests']]
for p in g_paths:
    (R/p).parent.mkdir(parents=True,exist_ok=True);shutil.copy2(G/p,R/p)
for n in ['check-increment-59c-source-review.py','test-increment-59c-inherited-source-scope.py']:
    shutil.copy2(H/'morphhdl/scripts'/n,R/'morphhdl/scripts'/n)
(R/'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala').write_bytes((W/'merge/ExternalParameterizedVerilogNativeFallback.scala.merged').read_bytes())
def replace(p,old,new):
    t=R/p;s=t.read_text();assert s.count(old)==1,(p,old[:120],s.count(old));t.write_text(s.replace(old,new,1))
p='morphhdl/scripts/check-increment-60f-artifacts.py'
s=(W/'merge/check-increment-60f-artifacts.py.merged').read_text()
pat=re.compile(r'^<<<<<<< 59g\n(.*?)^=======\n(.*?)^>>>>>>> 59h\n',re.M|re.S)
blocks=list(pat.finditer(s));assert len(blocks)==3
out=[];pos=0
for i,m in enumerate(blocks):
    out.append(s[pos:m.start()]);a,b=m.group(1),m.group(2)
    if i==0:out.append(a+'    },\n'+b)
    elif i==1:out.append(a+b)
    else:
        a=a[:a.rfind('    print(')]
        b=b[:b.rfind('    print(')]
        out.append(a+b+'''    joint = catalog_for_profile("60f-with-59d-and-59e-and-59f-and-59c-and-59g-and-59h", True)
    require(joint[1]["morphhdl"] == bridges[1]["morphhdl"] | set(nested_additions),
            "joint 59g/59h inventory lost an exact suite identity")
    require(joint[2]["morphhdl"] == {**bridges[2]["morphhdl"], **nested_additions},
            "joint 59g/59h inventory replaced an inherited exact count")
    for missing in bridge_additions | set(nested_additions):
        rejected(lambda missing=missing: exact_names(set(joint[1]["morphhdl"]) - {missing},
                 set(joint[1]["morphhdl"]), "missing joint register/nested suite"),
                 "missing exact joint 59g/59h suite")
    print(f"60f inventory self-test: inherited exact source profiles, named/register/nested suite extensions and {rejections} rejection controls PASS")
''')
    pos=m.end()
out.append(s[pos:]);(R/p).write_text(''.join(out))
p='morphhdl/scripts/check-increment-59g-source-review.py'
replace(p,'BASE = "0018da2740645e0ac0c419ded7b67c01622d2bb7"',f'BASE = "{base}"')
replace(p,'    "morphhdl/scripts/check-increment-59c-source-review.py",\n','    "morphhdl/scripts/check-increment-59h-source-review.py",\n')
replace(p,'    "morphhdl/scripts/test-increment-59c-inherited-source-scope.py",\n','    "morphhdl/scripts/test-increment-59h-inherited-source-scope.py",\n')
replace(p,'"59g production delta differs from the complete reviewed inventory; missing=" +','"59g production delta differs from the complete reviewed inventory; unreviewed production delta; missing=" +')
replace(p,'Each unchanged interval must equal the merged 59c baseline.','Each unchanged interval must equal the merged 59h baseline.')
p='morphhdl/scripts/check-increment-59h-source-review.py'
replace(p,'import hashlib\n','import hashlib\nimport importlib.util\n')
helper='''def register_source_review(root: Path):
    """Restore a separately pinned register successor before the frozen owner audit."""
    checker = root / "morphhdl/scripts/check-increment-59g-source-review.py"
    contract = root / "morphhdl/contracts/increment-59g-source-review.json"
    if not (checker.exists() or checker.is_symlink() or contract.exists() or contract.is_symlink()):
        return None
    require(checker.is_file() and not checker.is_symlink() and
            contract.is_file() and not contract.is_symlink(),
            "59g source-review checker or contract is missing")
    spec = importlib.util.spec_from_file_location("register_59g_review", checker)
    require(spec is not None and spec.loader is not None, "cannot load exact 59g source review")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def register_inherited_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    register = register_source_review(root)
    if register is None:
        return paths
    register.verify(root)
    historical = subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", qualification_base, register.BASE],
        cwd=root, text=True).splitlines()
    inherited = {path for path in historical if re.search(r"(?:^|/)src/main/", path)}
    return (paths - register.PRODUCTION_PATHS) | (inherited & register.PRODUCTION_PATHS)


'''
replace(p,'def restore_source(root: Path, path: str, source: str) -> str:\n',helper+'def restore_source(root: Path, path: str, source: str) -> str:\n')
replace(p,'    """Leave unrelated historical hooks to their own exact source contracts."""\n    entries = load_contract(root)', '    """Leave unrelated historical hooks to their own exact source contracts."""\n    register = register_source_review(root)\n    if register is not None:\n        source = register.restore_source(root, path, source)\n    entries = load_contract(root)')
replace(p,'    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)\n    entries = load_contract(root)', '    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)\n    register = register_source_review(root)\n    if register is not None:\n        register.verify_spans(root)\n    entries = load_contract(root)')
replace(p,'        restore_reviewed(entry, baseline, source.read_bytes())','        current = source.read_bytes()\n        if register is not None:\n            current = register.restore_source(root, path, current.decode()).encode()\n        restore_reviewed(entry, baseline, current)')
replace(p,'    verify(root)\n    historical = subprocess.check_output(', '    verify(root)\n    paths = register_inherited_inventory(root, paths, qualification_base)\n    historical = subprocess.check_output(')
replace(p,'    require_production_inventory(production_changes(root, qualification_base))','    paths = register_inherited_inventory(root, production_changes(root, qualification_base), qualification_base)\n    require_production_inventory(paths)')
p='morphhdl/scripts/test-increment-59h-inherited-source-scope.py'
replace(p,'    cases = [("unreviewed suffix " + path, path, "suffix", outside) for path in review.PATHS]', '''    register = getattr(review, "register_source_review", lambda root: None)(ROOT)
    cases = [("unreviewed suffix " + path, path, "suffix",
              "unreviewed source change outside reviewed 59g spans"
              if register is not None and path in register.PATHS else outside)
             for path in review.PATHS]''')
replace(p,'         "59h production delta differs from the complete reviewed inventory"),','         ("59g" if register is not None else "59h") + " production delta differs from the complete reviewed inventory"),')
p='morphhdl/scripts/check-increment-60f-equivalence-closure.py'
shutil.copy2(H/p,R/p)
replace(p,'    names = ("wa07a", "59d", "59e", "59f", "59c", "59h")','    names = ("wa07a", "59d", "59e", "59f", "59c", "59g", "59h")')
replace(p,'    for mask in range(1, 64):','    for mask in range(1, 1 << len(names)):')
replace(p,'        if "59h" in selected and not {"59d", "59e", "59f", "59c"}.issubset(selected):','        if {"59g", "59h"}.intersection(selected) and not {"59d", "59e", "59f", "59c"}.issubset(selected):')
replace(p,'''        if nested is not None and path in nested.PRODUCTION_PATHS and path not in named.PRODUCTION_PATHS:
            source_bytes = nested.restore_source(root, path, source_bytes.decode()).encode()
        if named is not None and path in named.PRODUCTION_PATHS:
            source_bytes = named.restore_source(root, path, source_bytes.decode()).encode()''','''        if named is not None:
            source_bytes = named.restore_source(root, path, source_bytes.decode()).encode()''')
replace(p,'''    if nested is not None:
        profile += "-and-59h"
    return profile''','''    if nested is not None:
        if getattr(nested, "register_source_review", lambda root: None)(root) is not None:
            profile += "-and-59g"
        profile += "-and-59h"
    return profile''')
p='morphhdl/scripts/check-increment-59g-source-review.py'
s=(R/p).read_text();paths=re.findall(r'    "([^"]+)",',s[s.index('PATHS = ('):s.index('ADDED_PATHS')]);assert len(paths)==9,paths
original=json.loads((G/'morphhdl/contracts/increment-59g-source-review.json').read_text())
oldmap={e['path']:e for e in original['files']}
review={'schema_version':2,'base':base,'offset_format':'utf8-bytes','files':[]}
for path in paths:
    a0=(H/path).read_bytes();b0=(R/path).read_bytes();assert a0!=b0,path
    a=a0.splitlines(keepends=True);b=b0.splitlines(keepends=True);ao=[0];bo=[0]
    for x in a:ao.append(ao[-1]+len(x))
    for x in b:bo.append(bo[-1]+len(x))
    reason=(oldmap[path]['reason'] if path in oldmap else 'Compose exact register and nested-owner source checks without changing the completed 59h contract.')+' Integration after merged 59h: retain native lexical owners and all original hardware/mutation obligations while adding reviewed register semantics and legacy direct-zero compatibility.'
    ed=[]
    for tag,i,j,k,l in difflib.SequenceMatcher(None,a,b,autojunk=False).get_opcodes():
        if tag=='equal':continue
        ed.append(dict(id=Path(path).stem+'-59g59h-'+str(len(ed)+1),reason=reason,before_start=ao[i],before_end=ao[j],after_start=bo[k],after_end=bo[l],before=b''.join(a[i:j]).decode(),after=b''.join(b[k:l]).decode()))
    review['files'].append(dict(path=path,change='modified',reason=reason,baseline_sha256=hashlib.sha256(a0).hexdigest(),edits=ed))
contract=R/'morphhdl/contracts/increment-59g-source-review.json';contract.write_text(json.dumps(review,indent=2)+'\n');digest=hashlib.sha256(contract.read_bytes()).hexdigest()
replace(p,'CONTRACT_SHA256 = "2af2f16dc0bf5682da63346cb7b204bbd3ada90642bb0c965b60659f22aa9b4c"',f'CONTRACT_SHA256 = "{digest}"')
assert (R/'morphhdl/contracts/increment-59h-source-review.json').read_bytes()==(H/'morphhdl/contracts/increment-59h-source-review.json').read_bytes()
expected={'morphhdl/contracts/increment-59g-source-review.json': '6afc16b035e7591d781370b81d554911320d3d686f8f892e3af609531d4c61aa', 'morphhdl/scripts/check-increment-59c-source-review.py': 'bb0e3c55244a57b13543b7fc6dcd3045183a8abe1ced465cddd840723c7b89aa', 'morphhdl/scripts/check-increment-59g-source-review.py': '997156ff5bbcd85cbe61df64b639677f51df896685e305384b7d001dedff2bea', 'morphhdl/scripts/check-increment-59h-source-review.py': 'a16e4d9f473848d569f11850df69b01da5063d721cf9baaaa60c0aa7ad179ed1', 'morphhdl/scripts/check-increment-60f-artifacts.py': 'decc59c1c7c3570f5973f25f53965ca17c66620230b51d17dc58c7235d40cb20', 'morphhdl/scripts/check-increment-60f-equivalence-closure.py': '9d6cda8778401908798b94c3334f315b0a95fe42e419dbc425f892ea31eb1b18', 'morphhdl/scripts/test-increment-59c-inherited-source-scope.py': '428b80f014dde35bc1c55d938ef4dec586d8b180b0b460e888433c66b7b72110', 'morphhdl/scripts/test-increment-59h-inherited-source-scope.py': '345544362f335e2c1268cd398a5ec520f04ea567f41507c3b6cac91e68f522de', 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala': '2f5617afcf9f97c5ace1afd71e7c37fde0efd12f1573990bdff78882679476f9'}
for path,digest in expected.items():
    assert hashlib.sha256((R/path).read_bytes()).hexdigest()==digest,path
    shutil.copy2(R/path,Path.cwd()/path)
subprocess.run(['git','add','--']+list(expected),check=True)
print('All nine reviewed integration resolutions match their exact expected SHA-256 values')
