#!/usr/bin/env python3
"""Exact two-parent PR190/PR189 integration; no compiler or oracle edits.

Current HEAD/index/worktree are checked before any predecessor projection.
Both qualified compiler/test inventories remain exact. Only enumerated review
infrastructure is reconciled, under the unchanged complete outer source seal.
"""
from __future__ import annotations
import argparse
import functools
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
SCHEMA7_SEAL = "300bdf94bea5b0b32f9c8e32aa032c01689ebf5d"
SCHEMA7_SYNC_SHA256 = "914b14ce0e31b430bddb4aadd88a0edf9945c1be162d3d75508195f13f5c5537"
# Audit-only reconciliation. Every other tracked blob, mode, deletion, addition
# and gitlink must be the strict union of the immutable parents.
RECONCILED = frozenset((
    SELF, 'morphhdl/scripts/test-pr190-pr189-source-sync.py',
    '.github/workflows/morphhdl-passes.yml',
    '.github/workflows/increment-60f-equivalence-closure.yml',
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


@functools.lru_cache(maxsize=8)
def _retained_schema7_sync(root: Path) -> dict:
    raw = git(root, 'show', SCHEMA7_SEAL + ':' + SELF)
    require(hashlib.sha256(raw).hexdigest() == SCHEMA7_SYNC_SHA256,
        'immutable schema-7 PR190/PR189 sync reviewer changed')
    with tempfile.TemporaryDirectory(prefix='59i-pr190-pr189-schema7-retained-') as temp:
        retained_root = Path(temp) / 'source'
        git(root, 'worktree', 'add', '--quiet', '--detach', str(retained_root), SCHEMA7_SEAL)
        try:
            result = load(retained_root, SELF).verify(retained_root)
            require(not git(retained_root, 'status', '--porcelain', '--untracked-files=all'),
                'immutable schema-7 PR190/PR189 sync review dirtied its checkout')
        finally:
            git(root, 'worktree', 'remove', '--force', str(retained_root))
    return result


def retained_schema7_sync(root: Path) -> dict:
    # Only an immutable commit result is cached. Current HEAD/index/worktree
    # authentication still runs before and after every compatibility replay.
    return dict(_retained_schema7_sync(root.resolve()))


def verify(root: Path = ROOT, sealed: dict | None = None) -> dict:
    root=root.resolve()
    successor = root / 'morphhdl/scripts/check-increment-59i-production-successor.py'
    certificate = root / 'morphhdl/contracts/increment-59i-production-successor.json'
    if any(p.exists() or p.is_symlink() for p in (successor, certificate)):
        import types
        relative = Path('morphhdl/scripts/check-increment-59i-pr190-integration.py')
        path = root / relative
        require(path.is_file() and not path.stat().st_mode & 0o111 and
            all(not root.joinpath(*relative.parts[:i]).is_symlink()
                for i in range(1, len(relative.parts) + 1)),
            'missing, linked or executable current 59i integration reviewer')
        raw = path.read_bytes()
        require(hashlib.sha256(raw).hexdigest() ==
            'ee11df0bf3b8634b1386459c6a64ee7529dd8a9f68ed5847bdd2048a60b62912',
            'current 59i integration reviewer changed')
        key = 'pr190_integration_' + hashlib.sha256(raw).hexdigest()
        if key not in sys.modules:
            module = types.ModuleType(key)
            module.__file__ = str(path)
            exec(compile(raw, str(path), 'exec'), module.__dict__)
            sys.modules[key] = module
        current = sys.modules[key].verify(root)
        # The current review authenticates the complete merge before this
        # compatibility result exposes the original PR190 obligation sets.
        schema = sys.modules[key].source_review(root).contract(root)['schema_version']
        if schema in (8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20):
            retained = retained_schema7_sync(root)
            current = sys.modules[key].verify(root)
            compatibility = ('base', 'source', 'lane', 'production_files',
                'production_paths', 'implementation_paths', 'review_paths', 'paths',
                'left_implementation_files', 'target_implementation_files',
                'formal_signatures')
            return dict(current, **{name: retained[name] for name in compatibility})
        sequential = load(root, 'morphhdl/scripts/check-sequential-wire-source-review.py')
        implementation = lambda p: '/src/main/' in p or '/src/test/' in p or (
            p.startswith('morphhdl-passes/examples/') and p.endswith('.scala'))
        a = {p for p in changed(root, BASE, LEFT) if implementation(p)}
        b = {p for p in changed(root, BASE, TARGET) if implementation(p)}
        require(not a & b, 'qualified compiler/test deltas unexpectedly overlap')
        for path in sequential.PRODUCTION_PATHS:
            require((root/path).read_bytes() == git(root, 'show', LEFT+':'+path),
                'sequential compiler changed: '+path)
            require(not sequential.safety_failures(path, (root/path).read_text()),
                'sequential safety proof changed')
        verify_layering(root)
        return dict(current, base=BASE, source=LEFT, lane=LEFT,
            production_files=len(a|b), production_paths=sorted(sequential.PRODUCTION_PATHS),
            implementation_paths=sorted(a|b), review_paths=sorted(RECONCILED),
            paths=sorted(changed(root, BASE, 'HEAD')), left_implementation_files=len(a),
            target_implementation_files=len(b), formal_signatures=98)
    require(not git(root, 'rev-list', '--full-history', 'HEAD', '--',
        'morphhdl/scripts/check-increment-59i-production-successor.py',
        'morphhdl/contracts/increment-59i-production-successor.json'),
        '59i integration certificate was removed')
    normalized=re.sub(rb'^CONTRACT_SHA256 = "[^"]+"$', b'CONTRACT_SHA256 = "MANIFEST_HASH"',
                      (root/OUTER).read_bytes(), count=1, flags=re.M)
    require(hashlib.sha256(normalized).hexdigest()=="1593324f64df2351ece9f8c3d181fcf2f6ac72f4d1ebfbb44faf3730d27ec3bc",
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
    verify_ci_gate_successor(root)
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


# Exact CI-only successor to the already qualified combined compiler source.
CI_GATE_BASE = '92d897d68c331b88878d0258c87606e6d5ef215a'
PASS_WORKFLOW = '.github/workflows/morphhdl-passes.yml'
REGRESSION_WORKFLOW = '.github/workflows/increment-60f-equivalence-closure.yml'
TARGETED_WORKFLOW = '.github/workflows/sequential-source-review-targeted.yml'
REGRESSION_BASE = '5374b958f8f94114b1ed46a3069845d580886da9'
STATIC_BASE = '3990185a2701f652c1a47c8b8f244f9bf0d32974'
SEQUENTIAL_REPORTS = {
    'core': {'spinal.core.internals.SequentialWireEmitterTests': 6},
    'morphhdl': {'morphhdl.SequentialWireNativeTests': 18,
                'morphhdl.examples.SequentialWireRetentionTests': 13},
}
CI_STATIC_ROUTE = '''          elif [[ "$SOURCE_HEAD_REF" == agent/wa-sequential-wire-consumers ]]; then
            # Authenticate every current compiler/test byte BEFORE replaying
            # the immutable legacy syntax/scope contracts. All native/Scala/
            # simulation/proof jobs below still check out the current head.
            python3 morphhdl/scripts/check-pr190-pr189-source-sync.py
            python3 morphhdl/scripts/test-pr190-pr189-source-sync.py
            python3 morphhdl/scripts/check-sequential-wire-source-review.py
            python3 morphhdl/scripts/test-sequential-wire-source-review.py
            python3 morphhdl/scripts/check-native-source-preservation.py
            python3 morphhdl/scripts/check-typed-native-source-overlay.py
            python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test
            MORPHDL_PASSES_BASE_SHA=e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d MORPHDL_PASSES_HEAD_REF="$SOURCE_HEAD_REF" bash morphhdl-passes/scripts/check-boundary.sh
            bash morphhdl-passes/scripts/test-boundary-guard.sh
            audit_source=3990185a2701f652c1a47c8b8f244f9bf0d32974
            git merge-base --is-ancestor "$audit_source" HEAD
            audit_root=$(mktemp -d "${RUNNER_TEMP:-/tmp}/pr190-pass-static.XXXXXX")
            git worktree add --detach "$audit_root" "$audit_source"
            audit_head_ref=agent/lane-when-expression-inlining
            audit_base_sha=27af65abbee0d2334d6be7a6e4e2408b8af32fd9
            printf 'Current PR190 source: %s\\nHistorical static-only source: %s\\n' \\
              "$(git rev-parse HEAD)" "$audit_source"
'''
CI_PROJECT_REPORTS = '''          # Authenticate and validate all 37 added tests before removing only
          # their copied reports from the frozen predecessor catalog.
          python3 morphhdl/scripts/check-pr190-pr189-source-sync.py \\
            --project-regressions "$predecessor" "$output/sequential-test-inventory.json"

'''
CI_COMBINE_REPORTS = '''          # Add validated new suites back to the already checked old + Inc61
          # inventory. The original current-head XML is never edited.
          python3 morphhdl/scripts/check-pr190-pr189-source-sync.py \\
            --combine-regressions "$complete_inventory" "$output/sequential-test-inventory.json"
'''
CI_TARGET_JOBS = '''  failed-pass-workflow:
    needs: source-preflight
    uses: ./.github/workflows/morphhdl-passes.yml
  failed-regression-workflow:
    needs: source-preflight
    uses: ./.github/workflows/increment-60f-equivalence-closure.yml
'''


def ci_gate_workflow(path: str, before: str) -> str:
    """Only the named bounded routing/catalog delta is permitted."""
    def replace(text: str, old: str, new: str) -> str:
        require(text.count(old) == 1, 'ambiguous CI predecessor: ' + path)
        return text.replace(old, new, 1)
    if path == PASS_WORKFLOW:
        result = replace(before, '  workflow_dispatch:\n', '  workflow_dispatch:\n  workflow_call:\n')
        boundary = '          elif [[ "$SOURCE_HEAD_REF" == agent/increment-61-one-file-per-component ]]; then\n'
        result = replace(result, boundary, CI_STATIC_ROUTE + boundary)
        # Current source verification and rejection controls take longer than
        # the legacy-only boundary. Keep a finite job budget, not a skip.
        return replace(result, '    timeout-minutes: 15\n', '    timeout-minutes: 25\n')
    if path == REGRESSION_WORKFLOW:
        result = replace(before, '  workflow_dispatch:\n', '  workflow_dispatch:\n  workflow_call:\n')
        core = "'core/testOnly spinal.core.internals.SpinalVerilogPhasePlanTests spinal.core.internals.VerilogEmitterExpressionInliningTests'"
        result = replace(result, core, core[:-1] + " spinal.core.internals.SequentialWireEmitterTests'")
        result = replace(result, '          (\n            cd "$predecessor"\n            python3 morphhdl/scripts/check-increment-60f-artifacts.py regressions', CI_PROJECT_REPORTS + '          (\n            cd "$predecessor"\n            python3 morphhdl/scripts/check-increment-60f-artifacts.py regressions')
        return replace(result, '      - name: Retain inherited test and solver evidence\n', CI_COMBINE_REPORTS + '      - name: Retain inherited test and solver evidence\n')
    require(path == TARGETED_WORKFLOW, 'unexpected CI workflow: ' + path)
    result = replace(before, 'permissions:\n  contents: read\n', 'permissions:\n  contents: read\n  pull-requests: read\n')
    result = replace(result, '  inherited-wa08:\n    needs: source-preflight\n', CI_TARGET_JOBS + '  inherited-wa08:\n    needs: [failed-pass-workflow, failed-regression-workflow]\n')
    return replace(result, '    name: Merged PR189 compact timeout, record consumers and affected regressions\n    needs: source-preflight\n',
                   '    name: Merged PR189 compact timeout, record consumers and affected regressions\n    needs: [failed-pass-workflow, failed-regression-workflow]\n')


def verify_ci_gate_successor(root: Path) -> None:
    git(root, 'merge-base', '--is-ancestor', CI_GATE_BASE, 'HEAD')
    for path in (PASS_WORKFLOW, REGRESSION_WORKFLOW, TARGETED_WORKFLOW):
        original = git(root, 'show', CI_GATE_BASE + ':' + path).decode()
        expected = ci_gate_workflow(path, original)
        require((root / path).read_text() == expected,
                'CI workflow changed outside exact static/catalog repair: ' + path)
    catalog = load(root, 'morphhdl/scripts/check-increment-60f-artifacts.py')
    require(catalog.SEQUENTIAL_WIRE_SUITES == SEQUENTIAL_REPORTS,
            'sequential suite identity/count differs from approved catalog')


def sequential_report_record(path: Path, name: str, count: int) -> dict:
    """Validate actual XML, never infer success from its filename or counts."""
    import xml.etree.ElementTree as ET
    require(path.is_file() and not path.is_symlink(), 'missing/linked sequential report: ' + str(path))
    raw = path.read_bytes()
    suite = ET.fromstring(raw)
    require(suite.tag == 'testsuite' and suite.get('name') == name,
            'wrong sequential suite identity: ' + str(path))
    require(int(suite.get('tests', '0')) == count, 'changed sequential test count: ' + name)
    require(all(int(suite.get(key, '0')) == 0 for key in ('failures', 'errors', 'skipped')),
            'failed/skipped sequential suite: ' + name)
    require(not any(list(suite.iter(tag)) for tag in ('failure', 'error', 'skipped')),
            'unsuccessful sequential testcase: ' + name)
    cases = suite.findall('testcase')
    names = [case.get('name') for case in cases]
    require(len(cases) == count and all(names) and len(set(names)) == count,
            'missing/duplicate sequential testcase identity: ' + name)
    return {'tests': count, 'testcases': sorted(names),
            'sha256': hashlib.sha256(raw).hexdigest()}


def sequential_report_inventory(root: Path, source_head: str) -> dict:
    projects = {}
    for project, suites in SEQUENTIAL_REPORTS.items():
        records = {}
        for name, count in suites.items():
            path = root / project / 'target/test-reports' / ('TEST-' + name + '.xml')
            records[name] = sequential_report_record(path, name, count)
        projects[project] = {'tests': sum(suites.values()), 'suites': sorted(suites),
                             'skipped': 0, 'reports': records}
    return {'schema_version': 1, 'source_head': source_head,
            'predecessor': REGRESSION_BASE, 'projects': projects}


def project_regressions(root: Path, predecessor: Path, output: Path) -> None:
    result = verify(root)
    predecessor = predecessor.resolve()
    require(predecessor != root.resolve() and
            git(predecessor, 'rev-parse', 'HEAD').decode().strip() == REGRESSION_BASE,
            'wrong regression predecessor')
    git(root, 'merge-base', '--is-ancestor', REGRESSION_BASE, 'HEAD')
    output.unlink(missing_ok=True)
    value = sequential_report_inventory(root, result['head'])
    cdc_path = 'morphhdl/scripts/check-cdc-wire-regressions.py'
    cdc = load(root, cdc_path) if (root/cdc_path).exists() else None
    cdc_updates = []
    if cdc is not None:
        # verify(root) authenticated the complete current-source seal above.
        # Validate all additional current/copy XML before any copied deletion.
        specs = cdc.source_suites(root)
        cdc_receipt, cdc_updates = cdc.projection(root, predecessor, result['head'], specs)
        value = {'sequential': value, 'cdc_wire': cdc_receipt}
    # Prove the old catalog/checker has no edits. Only copy-tree XML may differ.
    require(not git(predecessor, 'diff', '--name-only').strip() and
            not git(predecessor, 'diff', '--cached', '--name-only').strip(),
            'frozen regression source changed')
    copied = []
    for project, suites in SEQUENTIAL_REPORTS.items():
        for name in suites:
            relative = Path(project) / 'target/test-reports' / ('TEST-' + name + '.xml')
            path = predecessor / relative
            require(path.is_file() and not path.is_symlink() and
                    path.read_bytes() == (root / relative).read_bytes(),
                    'copied report differs from original: ' + str(relative))
            copied.append(path)
    # No partial deletion: validate ALL original/copy reports before removal.
    for path in copied:
        path.unlink()
    for path, content in cdc_updates:
        if content is None:
            path.unlink()
        else:
            path.write_bytes(content)
    output.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')
    sequential = value['sequential'] if cdc is not None else value
    require(sequential_report_inventory(root, result['head']) == sequential,
            'projection modified original sequential XML')
    if cdc is not None:
        require(cdc.report_inventory(root, result['head'], specs) == value['cdc_wire'],
                'projection modified original CDC-WIRE XML')
    print('PR190_SEQUENTIAL_REPORTS_PASS tests=37 suites=3 projected_copies=3 original_xml_unchanged')


def merge_sequential_inventory(inherited: dict, extension: dict) -> dict:
    result = json.loads(json.dumps(inherited))
    require(extension.get('schema_version') == 1 and extension.get('predecessor') == REGRESSION_BASE and
            set(extension.get('projects', {})) == set(SEQUENTIAL_REPORTS),
            'wrong sequential inventory schema or project set')
    for project, suites in SEQUENTIAL_REPORTS.items():
        extra = extension['projects'][project]
        require(extra.get('tests') == sum(suites.values()) and extra.get('suites') == sorted(suites) and
                extra.get('skipped') == 0 and set(extra.get('reports', {})) == set(suites),
                'partial/changed sequential inventory: ' + project)
        old = result.get(project)
        require(isinstance(old, dict) and old.get('skipped') == 0 and
                isinstance(old.get('tests'), int) and old['tests'] > 0 and
                isinstance(old.get('suites'), list) and old['suites'] and
                len(old['suites']) == len(set(old['suites'])) and
                not set(old['suites']).intersection(suites),
                'invalid/overlapping inherited inventory: ' + project)
        old['tests'] += sum(suites.values())
        old['suites'] = sorted(old['suites'] + list(suites))
    return result



REGRESSION_PROJECTS = ('paramrtl', 'frontend', 'backends/verilog', 'morphhdl',
                       'morphir', 'morphplugin', 'core', 'morphhdl-passes')


def actual_regression_summary(root: Path) -> dict:
    """Independently match the combined catalog to ALL untouched current XML."""
    import xml.etree.ElementTree as ET
    records = {}
    for project in REGRESSION_PROJECTS:
        names = set()
        tests = 0
        reports = sorted((root / project / 'target/test-reports').glob('*.xml'))
        require(bool(reports), 'missing original project reports: ' + project)
        for path in reports:
            suite = ET.fromstring(path.read_bytes())
            name = suite.get('name')
            count = int(suite.get('tests', '0'))
            require(bool(name) and name not in names and count > 0,
                    'duplicate/empty original suite: ' + str(path))
            sequential_report_record(path, name, count)
            names.add(name)
            tests += count
        records[project] = {'tests': tests, 'suites': sorted(names), 'skipped': 0}
    return records


def combine_regressions(root: Path, complete: Path, extension: Path) -> None:
    result = verify(root)
    require(complete.is_file() and not complete.is_symlink() and
            extension.is_file() and not extension.is_symlink(), 'missing/linked regression inventory')
    extra = json.loads(extension.read_bytes())
    cdc_path = 'morphhdl/scripts/check-cdc-wire-regressions.py'
    cdc = load(root, cdc_path) if (root/cdc_path).exists() else None
    cdc_receipt = None
    if cdc is not None:
        require(set(extra) == {'sequential', 'cdc_wire'}, 'missing/changed CDC-WIRE receipt')
        specs = cdc.source_suites(root)
        cdc_receipt = extra['cdc_wire']
        require(cdc_receipt == cdc.report_inventory(root, result['head'], specs),
                'stale/changed CDC-WIRE report receipt')
        extra = extra['sequential']
    # Bind the receipt to the current source and untouched actual reports;
    # rejecting a stale/synthetic/tampered receipt is independent of counts.
    require(extra == sequential_report_inventory(root, result['head']),
            'stale/changed sequential report receipt')
    combined = merge_sequential_inventory(json.loads(complete.read_bytes()), extra)
    if cdc is not None:
        combined = cdc.merge_inventory(combined, cdc_receipt, specs)
    require(combined == actual_regression_summary(root),
            'combined inventory differs from complete untouched current reports')
    complete.write_text(json.dumps(combined, indent=2) + '\n')
    additional = sum(len(spec['added_cases']) for spec in specs.values()) if cdc is not None else 0
    suites = sum(not spec['inherited_tests'] for spec in specs.values()) if cdc is not None else 0
    print('PR190_COMPLETE_REGRESSIONS_PASS tests=' + str(sum(x['tests'] for x in combined.values())) +
          ' added_tests=' + str(37 + additional) + ' added_suites=' + str(3 + suites) +
          ' no_failures_errors_skips')


def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__)
    mode=parser.add_mutually_exclusive_group()
    mode.add_argument('--project-regressions', nargs=2, type=Path, metavar=('PREDECESSOR', 'OUTPUT'))
    mode.add_argument('--combine-regressions', nargs=2, type=Path, metavar=('COMPLETE', 'EXTENSION'))
    args=parser.parse_args()
    if args.project_regressions:
        project_regressions(ROOT, *args.project_regressions)
        return
    if args.combine_regressions:
        combine_regressions(ROOT, *args.combine_regressions)
        return
    result=verify()
    print('PR190_PR189_SOURCE_SYNC_PASS '+json.dumps(result,sort_keys=True))

if __name__=='__main__':main()
