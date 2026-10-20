#!/usr/bin/env python3
"""Qualify actual generated-child bindings over the inherited combined matrix.

Reuse the independent native oracle, scalar software model, wire-only layout
adapter and proof obligations. Both logical MODE values reach the child through
its actual MODE+1 binding; mutants must fail simulation and formal equivalence.
This is one slice, not a certificate for the complete Increment 59i.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import importlib.util
import json
import re
import shutil
from pathlib import Path


COMBINED = Path(__file__).with_name('check-increment-59i-combined.py')
spec = importlib.util.spec_from_file_location('generated_child_combined', COMBINED)
if spec is None or spec.loader is None:
    raise RuntimeError('inherited combined checker is missing')
C = importlib.util.module_from_spec(spec)
spec.loader.exec_module(C)
H, require = C.H, C.require
SCOPE = '59i-generated-child-record-join'
MODE_BINDING = re.compile(r'\.MODE\s*\(\s*\(\s*MODE\s*\+\s*1\s*\)\s*\)')
CONTROLS = tuple('wrong-mode-actual' if c == 'wrong-branch' else c for c in C.MUTATIONS) + (
    'fixed-mode-actual',)


def validate_manifest(manifest: dict) -> None:
    require(manifest.get('scope') == SCOPE, 'wrong generated-child qualification scope')
    C.validate_manifest(dict(manifest, scope=C.SCOPE))


def validate_child(rtl: str, candidate: dict) -> None:
    top = candidate['module']
    require(top.startswith('BalancedCombinedGeneratedChildTop_'), 'unexpected generated-child top')
    child = top.replace('GeneratedChildTop_', 'GeneratedChild_', 1)
    modules = re.findall(r'(?m)^module\s+([A-Za-z_][A-Za-z0-9_]*)\b', rtl)
    require(len(modules) == 2 and set(modules) == {top, child},
            'missing, duplicated or unexpected generated module')
    require(len(re.findall(r'\b' + re.escape(child) + r'\s*#\s*\(', rtl)) == 2,
            'generated child must have one definition and one parameterized instance')
    require(len(MODE_BINDING.findall(rtl)) == 1, 'actual MODE+1 binding is missing or duplicated')
    compact = re.sub(r'\s+', '', rtl)
    for parameter in ('WIDTH', 'TAG_WIDTH', 'COORD_WIDTH', 'COUNT'):
        require(compact.count('.' + parameter + '(' + parameter + ')') == 1,
                'missing exact generated-child actual: ' + parameter)


def mutate(rtl: str, control: str) -> str:
    if control in ('wrong-mode-actual', 'fixed-mode-actual'):
        replacement = '.MODE(MODE)' if control == 'wrong-mode-actual' else '.MODE(1)'
        result, count = MODE_BINDING.subn(replacement, rtl)
        require(count == 1, 'missing or ambiguous actual MODE mutation anchor')
        return result
    return C.mutate(rtl, control)


def qualify_case(root: Path, candidates: list[dict], case: dict) -> dict:
    work = root / 'checks' / case['id']
    work.mkdir(parents=True, exist_ok=True)
    sources = [H.checked_rtl(root, case['file'])] + [H.checked_rtl(root, c['file']) for c in candidates]
    top = work / 'miter.v'
    top.write_text(C.miter(case, candidates, True))
    H.command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', 'miter',
               *map(str, sources + [top])], work / 'lint.log')
    script = work / 'synthesis.ys'
    script.write_text(C.setup(sources + [top]) + 'synth -top miter\ncheck -assert\nstat\n')
    H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'synthesis.log')
    bench = work / 'tb.v'
    text, cycles = C.testbench(case, candidates)
    bench.write_text(text)
    executable = work / 'tb.vvp'
    H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable),
               *map(str, sources + [bench])], work / 'compile.log')
    result = H.command(['vvp', str(executable)], work / 'simulation.log')
    require(C.SIM_PASS in result and C.SIM_FAIL not in result,
            'native/software simulation did not pass: ' + case['id'])
    combinational = work / 'combinational.v'
    combinational.write_text(C.miter(case, candidates, False))
    obligations = (
        ('combinational', C.setup(sources + [combinational]) +
         'sat -prove bad 0 -verify -timeout 90\n', H.PASS),
        ('reset-entry', C.setup(sources + [top]) +
         'sat -seq 2 -set-at 1 reset 1 -set-at 1 enable 1 -prove bad 0 -prove-skip 1 -verify -timeout 90\n', H.PASS),
        ('induction', C.setup(sources + [top], True) +
         'sat -seq 1 -tempinduct -set-init-zero -prove bad 0 -verify -maxsteps 24 -timeout 90\n', C.INDUCTION_PASS))
    for name, commands, marker in obligations:
        script = work / (name + '.ys')
        script.write_text(commands)
        result = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / (name + '.log'))
        require(marker in result and (name == 'induction' or H.COUNTEREXAMPLE not in result),
                name + ' proof did not pass: ' + case['id'])
    print('PASS generated child:', case['id'], flush=True)
    return {'case': case['id'], 'mode': case['mode'], 'profiles': len(candidates), 'cycles': cycles,
            'simulation': 'PASS', 'combinational': 'PASS', 'reset_entry': 'PASS', 'induction': 'PASS'}


def reject_mutation(root: Path, candidates: list[dict], cases: list[dict], control: str) -> dict:
    # A fixed actual is exposed by logical MODE=1; the missing +1 is exposed by
    # MODE=0. Both are tested against independently emitted native hardware.
    mode = 1 if control == 'fixed-mode-actual' else 0
    case = next(c for c in cases if C.shape(c) == (5, 5, 5, 5, mode))
    selected = next(i for i, c in enumerate(candidates) if C.profile(c) == ('fields', 'legacy', 1))
    work = root / 'checks' / control
    work.mkdir(parents=True, exist_ok=True)
    candidate_files = [H.checked_rtl(root, c['file']) for c in candidates]
    mutated = work / 'mutated.v'
    mutated.write_text(mutate(candidate_files[selected].read_text(), control))
    sources = [H.checked_rtl(root, case['file'])] + [
        mutated if i == selected else path for i, path in enumerate(candidate_files)]
    sequential = control in C.SEQUENTIAL_MUTATIONS
    top = work / 'miter.v'
    top.write_text(C.miter(case, candidates, sequential))
    H.command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', 'miter',
               *map(str, sources + [top])], work / 'lint.log')
    bench = work / 'tb.v'
    text, _ = C.testbench(case, candidates)
    bench.write_text(text)
    executable = work / 'tb.vvp'
    H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable),
               *map(str, sources + [bench])], work / 'compile.log')
    output = H.command(['vvp', str(executable)], work / 'simulation.log')
    require(C.SIM_FAIL in output and C.SIM_PASS not in output,
            'mutant did not produce a real simulation mismatch: ' + control)
    trace = work / 'counterexample.vcd'
    script = work / 'mutation.ys'
    script.write_text(C.setup(sources + [top], sequential) + 'sat ' +
        ('-seq 6 -set-init-zero ' if sequential else '') +
        '-prove bad 0 -show-inputs -show-outputs -timeout 90 -dump_vcd ' + H.quoted(trace) + '\n')
    result = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
    require(H.COUNTEREXAMPLE in result and H.PASS not in result,
            'mutant did not yield a real counterexample: ' + control)
    H.require_counterexample_vcd(trace)
    return {'control': control, 'mode': mode, 'simulation': 'MISMATCH', 'formal': 'COUNTEREXAMPLE'}


def qualify(root: Path, duplicate: Path, focus: str | None, jobs: int) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    (root / 'evidence.json').unlink(missing_ok=True)
    require(root != duplicate, 'independent A/B generation directories are required')
    manifest = json.loads((root / 'manifest.json').read_text())
    validate_manifest(manifest)
    require((root / 'manifest.json').read_bytes() == (duplicate / 'manifest.json').read_bytes(),
            'nondeterministic generated-child manifest')
    candidates, cases = manifest['candidates'], manifest['cases']
    seen = set()
    for item in candidates + cases:
        first, second = H.checked_rtl(root, item['file']), H.checked_rtl(duplicate, item['file'])
        require(not first.samefile(second) and first not in seen and second not in seen,
                'A/B or matrix entries reused a generated artifact')
        seen.update((first, second))
        require(first.read_bytes() == second.read_bytes(), 'nondeterministic RTL: ' + item['file'])
    for candidate in candidates:
        validate_child(H.checked_rtl(root, candidate['file']).read_text(), candidate)
    for tool in ('iverilog', 'vvp', 'verilator', 'yosys'):
        require(shutil.which(tool) is not None, 'required HDL tool is missing: ' + tool)
    selected = [c for c in cases if focus is None or c['id'] == focus]
    require(selected, 'unknown focused case')
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        reports = list(pool.map(lambda c: qualify_case(root, candidates, c), selected))
    if focus is not None:
        print('Focused development case only; no complete matrix evidence produced.')
        return
    controls = [reject_mutation(root, candidates, cases, control) for control in CONTROLS]
    report = {'scope': SCOPE, 'complete_59i_join': False, 'cases': reports, 'mutations': controls,
              'checker_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'combined_checker_sha256': hashlib.sha256(COMBINED.read_bytes()).hexdigest(),
              'tool_helper_sha256': hashlib.sha256(Path(H.__file__).read_bytes()).hexdigest(),
              'manifest_sha256': hashlib.sha256((root / 'manifest.json').read_bytes()).hexdigest(),
              'sha256': {i['file']: hashlib.sha256(H.checked_rtl(root, i['file']).read_bytes()).hexdigest()
                         for i in candidates + cases}}
    (root / 'evidence.json').write_text(json.dumps(report, indent=2) + '\n')


def self_test() -> None:
    C.self_test()
    candidate = {'module': 'BalancedCombinedGeneratedChildTop_fixture'}
    valid = '''module BalancedCombinedGeneratedChildTop_fixture #(parameter MODE = 0) ();
    BalancedCombinedGeneratedChild_fixture #(.WIDTH(WIDTH), .TAG_WIDTH(TAG_WIDTH),
    .COORD_WIDTH(COORD_WIDTH), .COUNT(COUNT), .MODE((MODE + 1))) child();
    endmodule
module BalancedCombinedGeneratedChild_fixture #(parameter MODE = 1) (); endmodule
'''
    validate_child(valid, candidate)
    for invalid in (valid.replace('.MODE((MODE + 1))', '.MODE(MODE)'),
                    valid.replace('.COUNT(COUNT)', '.COUNT(1)'),
                    valid + '\nmodule Unexpected(); endmodule'):
        try:
            validate_child(invalid, candidate)
        except RuntimeError:
            pass
        else:
            raise AssertionError('invalid generated-child structure accepted')
    for control in ('wrong-mode-actual', 'fixed-mode-actual'):
        require(mutate(valid, control) != valid, 'MODE mutation changed nothing')
        try:
            mutate('module absent; endmodule', control)
        except RuntimeError:
            pass
        else:
            raise AssertionError('missing actual MODE anchor accepted')
    print('generated-child checker self-test PASS; hardware not run')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', nargs='?', type=Path)
    parser.add_argument('duplicate', nargs='?', type=Path)
    parser.add_argument('--case')
    parser.add_argument('--jobs', type=int, default=1)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    require(args.jobs >= 1, 'jobs must be positive')
    if args.self_test:
        self_test()
    else:
        require(args.root is not None and args.duplicate is not None, 'two artifact roots are required')
        qualify(args.root, args.duplicate, args.case, args.jobs)


if __name__ == '__main__':
    main()
