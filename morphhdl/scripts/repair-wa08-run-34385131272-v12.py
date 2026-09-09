#!/usr/bin/env python3
from __future__ import annotations
import hashlib, importlib.util, json, re, subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE_REF = "origin/parameterized-verilog"
WA07B = "morphhdl/scripts/check-wa07b-inherited-review.py"
ROLLOUT = "morphhdl/scripts/check-increment-60g-source-scope.py"
HELPER = "morphhdl/scripts/check-wa08-inherited-source-overlay.py"
CONTRACT = "morphhdl/contracts/wa08-inherited-source-overlay.json"
WORKFLOW = ".github/workflows/increment-62-wa08-workflow-closure.yml"
SELF = "morphhdl/scripts/repair-wa08-run-34385131272-v12.py"


def cmd(*args: str, check: bool = True, binary: bool = False):
    r = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE, text=not binary, check=False)
    if check and r.returncode:
        out = r.stdout.decode(errors="replace") if binary else r.stdout
        err = r.stderr.decode(errors="replace") if binary else r.stderr
        raise RuntimeError("command failed: " + " ".join(args) + "\n" + out + err)
    return r


def git(*args: str, binary: bool = False):
    return cmd("git", *args, binary=binary).stdout


def one(text: str, old: str, new: str, label: str) -> str:
    if text.count(old) != 1:
        raise RuntimeError(label + ": reviewed anchor count=" + str(text.count(old)))
    return text.replace(old, new, 1)


def base_file(base: str, path: str) -> str:
    return git("show", base + ":" + path)


def patch_wa07b(base: str) -> None:
    p = ROOT / WA07B
    text = base_file(base, WA07B)
    loader = f'''\n\nWA08_OVERLAY = "{HELPER}"\n\n\ndef wa08_overlay(root: Path):\n    path = root / WA08_OVERLAY\n    if not (path.exists() or path.is_symlink()):\n        return None\n    require(path.is_file() and not path.is_symlink(), "missing regular WA-08 overlay")\n    spec = importlib.util.spec_from_file_location("wa08_overlay", path)\n    require(spec is not None and spec.loader is not None, "cannot load WA-08 overlay")\n    module = importlib.util.module_from_spec(spec)\n    spec.loader.exec_module(module)\n    return module\n\n\ndef restore_wa08(root: Path, path: str, source: bytes) -> bytes:\n    overlay = wa08_overlay(root)\n    return source if overlay is None else overlay.restore_source(root, path, source)\n'''
    text = one(text, "\n\n\ndef require(condition: bool, detail: str) -> None:\n",
               loader + "\n\n\ndef require(condition: bool, detail: str) -> None:\n", "WA07b loader")
    text = one(text,
'''def verify(root: Path) -> bool:
    """Validate real checkout bytes, HEAD and index before choosing a profile."""
    value = load_contract(root)
''',
'''def verify(root: Path) -> bool:
    """Validate real checkout bytes, HEAD and index before choosing a profile."""
    overlay = wa08_overlay(root)
    if overlay is not None:
        overlay.verify(root)
    value = load_contract(root)
''', "WA07b verify")
    text = one(text,
'''    for path in sorted(main(expected)):
        require(digest(regular(root, path)) == expected[path],
                "unreviewed production delta: unreviewed pass main/test bytes: " + path)
''',
'''    for path in sorted(main(expected)):
        inherited = restore_wa08(root, path, regular(root, path))
        require(digest(inherited) == expected[path],
                "unreviewed production delta: unreviewed pass main/test bytes: " + path)
''', "WA07b early bytes")
    text = one(text,
'''    for path, fingerprint in expected.items():
        data = regular(root, path)
        category = ("unreviewed production delta" if path.startswith(ROOTS[0] + "/")
                    else "unreviewed pass test source")
        require(digest(data) == fingerprint, category + ": unreviewed pass main/test bytes: " + path)
        oid = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\\0" + data).hexdigest()
        require(oid == entries[path][1], "uncommitted pass source differs from HEAD: " + path)
''',
'''    for path, fingerprint in expected.items():
        actual = regular(root, path)
        data = restore_wa08(root, path, actual)
        category = ("unreviewed production delta" if path.startswith(ROOTS[0] + "/")
                    else "unreviewed pass test source")
        require(digest(data) == fingerprint, category + ": unreviewed pass main/test bytes: " + path)
        oid = hashlib.sha1(b"blob " + str(len(actual)).encode() + b"\\0" + actual).hexdigest()
        require(oid == entries[path][1], "uncommitted pass source differs from HEAD: " + path)
''', "WA07b final bytes")
    text = one(text,
'''def inherited_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    """Strip only the complete verified B delta; retain every unrelated path."""
    if not verify(root):
        return paths
    delta = set(load_contract(root)["production_delta"])
''',
'''def inherited_inventory(root: Path, paths: set[str], qualification_base: str) -> set[str]:
    """Strip only complete verified successor deltas; retain every unrelated path."""
    if not verify(root):
        return paths
    overlay = wa08_overlay(root)
    if overlay is not None:
        paths = overlay.inherited_inventory(root, paths, qualification_base)
    delta = set(load_contract(root)["production_delta"])
''', "WA07b inventory")
    text = one(text,
'''def restore_pass_source(root: Path, path: str, source: bytes) -> bytes:
    value = load_contract(root)
''',
'''def restore_pass_source(root: Path, path: str, source: bytes) -> bytes:
    source = restore_wa08(root, path, source)
    value = load_contract(root)
''', "WA07b restore")
    p.write_text(text)


def patch_rollout(base: str) -> None:
    p = ROOT / ROLLOUT
    text = base_file(base, ROLLOUT)
    loader = f'''\n\nWA08_OVERLAY = "{HELPER}"\n\n\ndef wa08_overlay(root: Path):\n    path = root / WA08_OVERLAY\n    if not (path.exists() or path.is_symlink()):\n        return None\n    require(path.is_file() and not path.is_symlink(), "missing regular WA-08 overlay")\n    spec = importlib.util.spec_from_file_location("wa08_rollout_overlay", path)\n    require(spec is not None and spec.loader is not None, "cannot load WA-08 overlay")\n    module = importlib.util.module_from_spec(spec)\n    spec.loader.exec_module(module)\n    return module\n\n\ndef restore_wa08_text(root: Path, path: str, source: str) -> str:\n    overlay = wa08_overlay(root)\n    return source if overlay is None else overlay.restore_text(root, path, source)\n'''
    text = one(text, "\n\n\ndef require(ok: bool, detail: str) -> None:\n",
               loader + "\n\n\ndef require(ok: bool, detail: str) -> None:\n", "60g loader")
    text = one(text,
'''def restore_60g_source(root: Path, path: str, source: str) -> str:
    if path not in PATHS:
        return source
''',
'''def restore_60g_source(root: Path, path: str, source: str) -> str:
    source = restore_wa08_text(root, path, source)
    if path not in PATHS:
        return source
''', "60g restore")
    start = text.index("def without_sibling_delta")
    pos = text.index("    def changed(older: str, *newer: str) -> set[str]:\n", start)
    text = text[:pos] + '''    overlay = wa08_overlay(root)
    if overlay is not None:
        paths = overlay.inherited_inventory(root, paths, revision)

''' + text[pos:]
    start = text.index("def reviewed_blob_scope")
    pos = text.index("    def git(*args: str) -> bytes:\n", start)
    text = text[:pos] + '''    overlay = wa08_overlay(root)
    if overlay is not None:
        overlay.verify(root)

''' + text[pos:]
    text = one(text,
'''        raw = file.read_bytes()
        require(hashlib.sha256(raw).hexdigest() == fingerprint,
                "60g reviewed raw source bytes differ: " + path)
        oid = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\\0" + raw).hexdigest()
''',
'''        actual = file.read_bytes()
        raw = actual if overlay is None else overlay.restore_source(root, path, actual)
        require(hashlib.sha256(raw).hexdigest() == fingerprint,
                "60g reviewed raw source bytes differ: " + path)
        oid = hashlib.sha1(b"blob " + str(len(actual)).encode() + b"\\0" + actual).hexdigest()
''', "60g raw bytes")
    text = one(text,
'''def source_scope(root: Path) -> None:
    def git(*args: str) -> str:
''',
'''def source_scope(root: Path) -> None:
    overlay = wa08_overlay(root)
    if overlay is not None:
        overlay.verify(root)

    def git(*args: str) -> str:
''', "60g scope")
    text = one(text,
'''    if ternary is not None:
        changed = ternary.inherited_inventory(root, changed, BASE)
    require(changed == set(PRODUCTION),
''',
'''    if ternary is not None:
        changed = ternary.inherited_inventory(root, changed, BASE)
    if overlay is not None:
        changed = overlay.inherited_inventory(root, changed, BASE)
    require(changed == set(PRODUCTION),
''', "60g inventory")
    text = one(text,
'''    for path, expected in {**PRODUCTION, **QUALIFICATION}.items():
        file = root / path
        require(file.is_file() and not file.is_symlink(), "missing/linked reviewed 60g source: " + path)
        require(digest(file.read_text()) == expected, "60g reviewed source bytes differ: " + path)
''',
'''    for path, expected in {**PRODUCTION, **QUALIFICATION}.items():
        file = root / path
        require(file.is_file() and not file.is_symlink(), "missing/linked reviewed 60g source: " + path)
        source = file.read_text()
        if overlay is not None:
            source = overlay.restore_text(root, path, source)
        require(digest(source) == expected, "60g reviewed source bytes differ: " + path)
''', "60g hashes")
    text = one(text,
'''    for entry in contract(root)["files"]:
        path = entry["path"]
        source = (root / path).read_text()
        require(digest(source) == entry["after_sha256"], "60g reviewed current source differs: " + path)
''',
'''    for entry in contract(root)["files"]:
        path = entry["path"]
        source = (root / path).read_text()
        if overlay is not None:
            source = overlay.restore_text(root, path, source)
        require(digest(source) == entry["after_sha256"], "60g reviewed current source differs: " + path)
''', "60g contract hashes")
    p.write_text(text)


def helper_text(base: str) -> str:
    return f'''#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, importlib.util, json, os, stat, subprocess
from pathlib import Path
BASE = "{base}"
CONTRACT = "{CONTRACT}"
WORKFLOW = "{WORKFLOW}"

def require(ok, detail):
    if not ok: raise RuntimeError("WA-08 inherited source overlay: " + detail)

def run(root, *args, text=False):
    r=subprocess.run(["git",*args],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=text,check=False,timeout=120)
    require(r.returncode==0,"git failed: "+" ".join(args)+"\\n"+(r.stderr if text else r.stderr.decode(errors="replace")))
    return r.stdout

def digest(data): return hashlib.sha256(data).hexdigest()
def oid(data): return hashlib.sha1(b"blob "+str(len(data)).encode()+b"\\0"+data).hexdigest()

def regular(root,path):
    p=root/path
    require(p.is_file() and not p.is_symlink() and stat.S_ISREG(p.stat().st_mode) and not p.stat().st_mode&0o111,"invalid governed file: "+path)
    q=root
    for part in Path(path).parts[:-1]:
        q=q/part; require(not q.is_symlink(),"linked governed ancestor: "+path)
    return p.read_bytes()

def contract(root):
    value=json.loads(regular(root,CONTRACT))
    require(set(value)=={{"schema_version","base","files"}} and value["schema_version"]==1 and value["base"]==BASE,"invalid contract")
    require([e.get("path") for e in value["files"]]==sorted(e.get("path") for e in value["files"]),"unordered contract")
    return value

def records(root): return {{e["path"]:e for e in contract(root)["files"]}}

def before(root,e):
    r=subprocess.run(["git","show",BASE+":"+e["path"]],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=False)
    if e["change"]=="added": require(r.returncode!=0 and e["before_sha256"] is None,"added path exists in base: "+e["path"]); return b""
    require(r.returncode==0 and digest(r.stdout)==e["before_sha256"],"baseline changed: "+e["path"]); return r.stdout

def verify(root):
    root=root.resolve(); run(root,"merge-base","--is-ancestor",BASE,"HEAD")
    rec=records(root)
    for path,e in rec.items():
        raw=regular(root,path); require(digest(raw)==e["after_sha256"],"final bytes differ: "+path); before(root,e)
        stage=run(root,"ls-files","--stage","--",path,text=True).split(); require(len(stage)==4 and stage[0]=="100644" and stage[2]=="0" and stage[3]==path,"invalid index entry: "+path)
        committed=run(root,"rev-parse","HEAD:"+path,text=True).strip(); require(stage[1]==committed==oid(raw),"HEAD/index/worktree differ: "+path)
    changed={{p.decode() for p in run(root,"diff","--no-renames","--name-only","-z",BASE,"HEAD").split(b"\\0") if p}}
    expected=set(rec)|{{CONTRACT,WORKFLOW}}
    require(changed==expected,"changed inventory differs; missing="+repr(sorted(expected-changed))+"; extra="+repr(sorted(changed-expected)))
    require(not run(root,"diff","--cached","--name-only","-z","HEAD"),"staged files present")
    require(not run(root,"diff","--name-only","-z"),"unstaged files present")
    require(not run(root,"ls-files","--others","--exclude-standard","-z"),"untracked files present")
    handoff=regular(root,"morphir/src/main/scala/morphhdl/ir/v1/Handoff.scala").decode(); adapter=regular(root,"morphhdl-passes/src/main/scala/morphhdl/passes/adapter/CanonicalIrPassAdapter.scala").decode(); test=regular(root,"morphhdl/src/test/scala/morphhdl/MorphCanonicalIrHandoffTests.scala").decode()
    require("PureWireExpressionsV1" in handoff and "PureExpressions" in handoff,"pure profile/facet absent")
    require("CanonicalIrHandoff.productionProfile" in adapter,"adapter profile absent")
    require("PureWireExpressionsV1" in test and "PureExpressions" in test,"profile regression absent")
    return contract(root)

def restore_source(root,path,source):
    e=records(root).get(path)
    if e is None: return source
    old=before(root,e); current=digest(source)
    if e["change"]=="added": require(source==b"" or current==e["after_sha256"],"changed added path: "+path); return b""
    require(current in (e["before_sha256"],e["after_sha256"]),"unreviewed bytes: "+path); return old

def restore_text(root,path,source): return restore_source(root,path,source.encode()).decode()
def restore_pass_source(root,path,source): return restore_source(root,path,source)

def inherited_inventory(root,paths,revision):
    verify(root); result=set(paths)
    for path in records(root):
        result.discard(path)
        r=subprocess.run(["git","diff","--quiet","--no-renames",revision,BASE,"--",path],cwd=root,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,check=False)
        require(r.returncode in (0,1),"historical comparison failed: "+path)
        if r.returncode==1: result.add(path)
    return result

def self_test(root):
    value=verify(root); n=0
    for e in value["files"]:
        raw=regular(root,e["path"]); changed=(bytes([raw[0]^1])+raw[1:]) if raw else b"x"; require(digest(changed)!=e["after_sha256"],"mutation escaped: "+e["path"]); require(restore_source(root,e["path"],raw)==before(root,e),"restore failed: "+e["path"]); n+=1
    print("WA08_OVERLAY_SELF_TEST_PASS governed="+str(n))
def main():
    p=argparse.ArgumentParser(); p.add_argument("--repo-root",type=Path,default=Path(__file__).resolve().parents[2]); p.add_argument("--self-test",action="store_true"); a=p.parse_args()
    self_test(a.repo_root) if a.self_test else print("WA08_INHERITED_SOURCE_OVERLAY_PASS governed="+str(len(verify(a.repo_root)["files"])))
if __name__=="__main__": main()
'''


def category(path: str) -> str:
    if path.startswith("morphhdl-passes/src/main/"): return "pass-production"
    if "/src/main/" in "/"+path: return "production"
    if "/src/test/" in "/"+path: return "test"
    if path.endswith("expected-signatures.json"): return "signature"
    if "/scripts/" in "/"+path or path.endswith("check-boundary.sh"): return "source-audit"
    if path.startswith("docs/"): return "qualification"
    return "support"


def make_contract(base: str) -> dict:
    changed=set(git("diff","--no-renames","--name-only",base).splitlines())
    changed|=set(git("ls-files","--others","--exclude-standard").splitlines())
    changed-={CONTRACT,WORKFLOW,SELF}
    changed.add(HELPER)
    files=[]
    for path in sorted(changed):
        p=ROOT/path
        if not p.is_file() or p.is_symlink() or p.stat().st_mode&0o111: raise RuntimeError("invalid governed path: "+path)
        raw=p.read_bytes(); old=cmd("git","show",base+":"+path,check=False,binary=True)
        if old.returncode==0:
            if old.stdout==raw: raise RuntimeError("unchanged governed path: "+path)
            change="modified"; before=hashlib.sha256(old.stdout).hexdigest()
        else: change="added"; before=None
        files.append({"path":path,"category":category(path),"change":change,"mode":"100644","before_sha256":before,"after_sha256":hashlib.sha256(raw).hexdigest()})
    return {"schema_version":1,"base":base,"files":files}


def workflow(contract_sha: str) -> str:
    return f'''name: Increment 62 WA-08 inherited workflow closure
on:
  pull_request:
    branches: [parameterized-verilog]
  push:
    branches: [agent/wa-08-production-handoff]
  workflow_dispatch:
jobs:
  source-closure:
    runs-on: ubuntu-24.04
    strategy:
      fail-fast: false
      matrix:
        scala: ["2.12.18", "2.13.12"]
    container:
      image: ghcr.io/spinalhdl/docker:v1.2.0
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
          submodules: recursive
      - shell: bash
        run: |
          set -euo pipefail
          test "$(sha256sum {CONTRACT} | awk '{{print $1}}')" = "{contract_sha}"
          python3 {HELPER} --self-test
          python3 {HELPER}
          python3 {WA07B} --self-test
          python3 {WA07B}
          python3 {ROLLOUT} --self-test
          python3 {ROLLOUT}
          python3 morphhdl/scripts/check-increment-60f-equivalence-closure.py --source-only
          python3 morphhdl/scripts/check-increment-59c-source-review.py --self-test
          python3 morphhdl/scripts/check-increment-59c-source-review.py
          python3 morphhdl/scripts/check-increment-59g-source-review.py --self-test
          python3 morphhdl/scripts/check-increment-59g-source-review.py
          bash morphhdl-passes/scripts/check-boundary.sh
          python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test
'''


def main() -> None:
    cmd("git","fetch","origin","parameterized-verilog")
    base=git("rev-parse",BASE_REF).strip(); cmd("git","merge-base","--is-ancestor",base,"HEAD")
    patch_wa07b(base); patch_rollout(base)
    hp=ROOT/HELPER; hp.parent.mkdir(parents=True,exist_ok=True); hp.write_text(helper_text(base)); hp.chmod(0o644)
    for old in ("morphhdl/contracts/wa08-inherited-overlay.json","morphhdl/scripts/check-wa08-inherited-overlay.py","morphhdl/scripts/test-wa08-inherited-overlay.py"):
        p=ROOT/old
        if p.exists() or p.is_symlink(): p.unlink()
    value=make_contract(base); cp=ROOT/CONTRACT; cp.parent.mkdir(parents=True,exist_ok=True); cp.write_text(json.dumps(value,indent=2)+"\n")
    wp=ROOT/WORKFLOW; wp.parent.mkdir(parents=True,exist_ok=True); wp.write_text(workflow(hashlib.sha256(cp.read_bytes()).hexdigest()))
    print("WA08_REPAIR_V12_STAGED files="+str(len(value["files"])))
if __name__=="__main__": main()
