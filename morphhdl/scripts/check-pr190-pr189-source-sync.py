#!/usr/bin/env python3
"""Exact two-parent PR190/PR189 integration; no compiler or oracle edits.

Current HEAD/index/worktree are checked before any predecessor projection.
Both qualified compiler/test inventories remain exact. Only enumerated review
infrastructure is reconciled, under the unchanged complete outer source seal.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import re
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
LEFT = "28b5a04621ed6207b374f37b88fa4f5b4030c0d4"
TARGET = "e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d"
CDC = "a754a1f2f84b31544a619f8c0456dc23a27e7b88"
BASE = "f5049ae2abfe5a47cd1fac3574ea08d630bd183f"
OUTER = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
CONTRACT = "morphhdl/contracts/increment-62-wa08-source-overlay.json"
SELF = "morphhdl/scripts/check-pr190-pr189-source-sync.py"
REGISTRY = "morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json"
# Audit-only reconciliation. Every other tracked blob, mode, deletion, addition
# and gitlink must be the strict union of the immutable parents.
RECONCILED = frozenset((
    SELF, 'morphhdl/scripts/test-pr190-pr189-source-sync.py',
    'docs/morphhdl/pr190-pr189-source-sync.md',
    '.github/workflows/sequential-source-review-targeted.yml',
    'morphhdl/contracts/increment-54-typed-layering-ir.contract',
    'morphhdl/scripts/check-typed-layering-ir.py',
    'morphhdl/scripts/check-sequential-wire-source-review.py',
    'morphhdl/scripts/check-cdc-successor-source.py',
    'morphhdl/scripts/check-lane-when-increment61-source.py',
    'morphhdl/scripts/check-lane-when-source-scope.py',
    'morphhdl/scripts/check-increment-61-source-review.py',
    CONTRACT, OUTER, REGISTRY,
    'morphhdl/contracts/native-source-preservation.json',
))


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError('SEQUENTIAL-WIRE-SOURCE: PR189 sync: ' + detail)


def git(root: Path, *args: str) -> bytes:
    p = subprocess.run(['git', '--literal-pathspecs', *args], cwd=root,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    require(p.returncode == 0, 'Git failed: ' + ' '.join(args) + '\n' + p.stderr.decode(errors='replace'))
    return p.stdout


def load(root: Path, path: str):
    require((root/path).is_file() and not (root/path).is_symlink(), 'missing/linked reviewer: ' + path)
    spec=importlib.util.spec_from_file_location('integrated_' + Path(path).stem, root/path)
    require(spec is not None and spec.loader is not None, 'cannot load reviewer')
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def trees(root: Path, ref: str) -> dict:
    result = {}
    for record in git(root, 'ls-tree', '-r', '-z', ref).split(b'\0'):
        if record:
            meta, path = record.split(b'\t', 1)
            mode, kind, sha = meta.decode().split()
            result[path.decode()] = (mode, kind, sha)
    return result


def changed(root: Path, a: str, b: str) -> set[str]:
    return set(git(root, 'diff', '--no-renames', '--name-only', '-z', a, b).decode().split('\0')) - {''}


def merged_bytes(root: Path, path: str) -> bytes:
    """Reproduce Git's conflict-free textual merge; never select one side."""
    with tempfile.TemporaryDirectory(prefix='pr190-merge-body-') as td:
        files = []
        for ref, name in ((LEFT,'left'), (BASE,'base'), (TARGET,'target')):
            file=Path(td)/name; file.write_bytes(git(root, 'show', ref+':'+path));files.append(str(file))
        p=subprocess.run(['git','merge-file','-p',*files], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        require(p.returncode == 0, 'unreviewed textual conflict: ' + path)
        return p.stdout


def verify_layering(root: Path) -> None:
    path='morphhdl/contracts/increment-54-typed-layering-ir.contract'
    before=json.loads(git(root,'show',TARGET+':'+path))
    expected=json.loads(json.dumps(before)); extra='repro/remaining-wires/src/main'
    require(extra not in expected['production_source_roots'], 'unexpected target layering root')
    expected['production_source_roots']=sorted(expected['production_source_roots']+[extra])
    rule=next(x for x in expected['forbidden_source_rules'] if x['id']=='obsolete-parameterized-sidecar-symbol')
    rule['path_prefixes']=sorted(rule['path_prefixes']+[extra+'/'])
    actual=json.loads((root/path).read_bytes())
    require(actual == expected, 'layering policy must be the exact two-root union')
    for entry in (extra,'repro/cdc-independent-parameters/src/main'):
        require(entry in actual['production_source_roots'] and entry not in actual['low_level_source_roots'],
                'reproducer must be scanned, never exempted: '+entry)
    canonical=lambda v:hashlib.sha256(json.dumps(v,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()
    helper='morphhdl/scripts/check-typed-layering-ir.py'
    old=git(root,'show',TARGET+':'+helper).decode()
    pin='EXPECTED_CONTRACT_SHA256 = "'+canonical(before)+'"'
    require(old.count(pin)==1, 'missing qualified layering pin')
    require((root/helper).read_text()==old.replace(pin,'EXPECTED_CONTRACT_SHA256 = "'+canonical(actual)+'"'),
            'layering checker algorithm changed')


def verify(root: Path = ROOT, sealed: dict | None = None) -> dict:
    root=root.resolve()
    normalized=re.sub(rb'^CONTRACT_SHA256 = "[^"]+"$', b'CONTRACT_SHA256 = "MANIFEST_HASH"',
                      (root/OUTER).read_bytes(), count=1, flags=re.M)
    require(hashlib.sha256(normalized).hexdigest()=="14feb8286f32152b7c6881c73e0339e069bbeaaf07cdc1d51d84cc208fc39fab",
            "outer verifier algorithm changed")
    outer=load(root,OUTER)
    seal=sealed if sealed is not None else outer.verify(root)
    for ref, tree in ((LEFT,'d6f1cfe43445ffa198cf35528e1b41b329325bad'),
                      (TARGET,'18747a4995e2bb766ff7a87086a2fb30eae121b9')):
        git(root,'merge-base','--is-ancestor',ref,'HEAD')
        require(git(root,'rev-parse',ref+'^{tree}').decode().strip()==tree, 'qualified parent tree changed')
    require(git(root,'show','-s','--format=%P',TARGET).decode().strip().split()==[BASE,CDC],
            'target no longer represents the normal PR189 merge')
    require(git(root,'merge-base',LEFT,TARGET).decode().strip()==BASE, 'common ancestor changed')
    base,left,right,current = (trees(root,ref) for ref in (BASE,LEFT,TARGET,'HEAD'))
    expected_paths=set(left)|set(right)|RECONCILED
    require(set(current)==expected_paths, 'complete tracked inventory differs')
    require(not any('/src/main/' in p or '/src/test/' in p or p.endswith('.scala') for p in RECONCILED),
            'review reconciliation cannot authorize compiler/test changes')
    for path in sorted(expected_paths-RECONCILED):
        a,b,c=left.get(path),base.get(path),right.get(path)
        expected=c if a==b else a if c==b or a==c else None
        if expected is None:
            require(a and b and c and a[:2]==b[:2]==c[:2] and a[1]=='blob', 'unsupported merged identity: '+path)
            raw=merged_bytes(root,path)
            expected=(a[0],a[1],hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest())
        require(current.get(path)==expected, 'qualified parent content/mode lost: '+path)
    entries={e['path']:e for e in seal['files']}
    for path in RECONCILED-{OUTER,CONTRACT}:
        require(path in entries, 'review reconciliation missing from exact seal: '+path)
    # All 98 inherited formal identities remain, and every digest is a digest
    # of the actual merged source. No renamed, deleted or synthetic entries.
    old=json.loads(git(root,'show',TARGET+':'+REGISTRY)); now=json.loads((root/REGISTRY).read_bytes())
    require(now.keys()==old.keys() and now['schema_version']==old['schema_version'] and
            now['algorithm']==old['algorithm'] and now['files'].keys()==old['files'].keys() and
            len(now['files'])==98, 'formal registry identity changed')
    for path,digest in now['files'].items():
        require(hashlib.sha256((root/path).read_bytes()).hexdigest()==digest,'formal fingerprint mismatch: '+path)
    verify_layering(root)
    sequential=load(root,'morphhdl/scripts/check-sequential-wire-source-review.py')
    for path in sequential.PRODUCTION_PATHS:
        require((root/path).read_bytes()==git(root,'show',LEFT+':'+path),'sequential compiler changed: '+path)
        require(not sequential.safety_failures(path,(root/path).read_text()),'sequential safety proof changed')
    # Both compiler introduction sets are disjoint and preserved in full, not
    # merely their currently observed emitted examples.
    implementation=lambda p: '/src/main/' in p or '/src/test/' in p or (p.startswith('morphhdl-passes/examples/') and p.endswith('.scala'))
    a={p for p in changed(root,BASE,LEFT) if implementation(p)}
    b={p for p in changed(root,BASE,TARGET) if implementation(p)}
    require(not a & b, 'qualified compiler/test deltas unexpectedly overlap')
    require({p for p in changed(root,BASE,'HEAD') if implementation(p)} == a|b,
            'combined implementation inventory changed')
    output={'head':git(root,'rev-parse','HEAD').decode().strip(),'base':BASE,'source':LEFT,
            'target':TARGET,'lane':LEFT,'production_files':len(a|b),
            'production_paths':sorted(sequential.PRODUCTION_PATHS),'implementation_paths':sorted(a|b),
            'review_paths':sorted(RECONCILED),'paths':sorted(changed(root,BASE,'HEAD')),
            'left_implementation_files':len(a),'target_implementation_files':len(b),'formal_signatures':98}
    return output


def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__);parser.parse_args()
    result=verify()
    print('PR190_PR189_SOURCE_SYNC_PASS '+json.dumps(result,sort_keys=True))

if __name__=='__main__':main()
