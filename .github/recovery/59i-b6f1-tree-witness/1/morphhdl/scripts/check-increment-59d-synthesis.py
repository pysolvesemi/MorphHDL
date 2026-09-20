#!/usr/bin/env python3
"""Bound ABC working sets without omitting any native logic or synthesis pass."""
from __future__ import annotations

import json
import re
import time
from pathlib import Path


ARTIFACT_SUFFIXES = (
    'ports.json', 'flat.json', 'grouped.json', 'prepared-flat.json',
    'frontend.ys', 'frontend.log', 'frontend.timing.json',
    'preparation.ys', 'preparation.log', 'preparation.timing.json',
    'primitive-cells.log', 'primitive-cells.timing.json',
    'synthesis.ys', 'synthesis.log', 'synthesis.timing.json', 'final-stat.json',
)


def known_init(module: dict) -> dict:
    """An init constraint belongs to a signal bit, including all its aliases."""
    known = {}
    for name, wire in module['netnames'].items():
        initial = wire.get('attributes', {}).get('init')
        if initial is None:
            continue
        if not isinstance(initial, str) or len(initial) != len(wire['bits']):
            raise RuntimeError('invalid native init width/encoding: ' + name)
        for bit, value in zip(wire['bits'], reversed(initial.lower())):
            if value not in '01xz':
                raise RuntimeError('invalid native init bit: ' + name)
            if value in '01':
                if bit in known and known[bit] != value:
                    raise RuntimeError('conflicting alias init constraints: ' + name)
                known[bit] = value
    return known


def check_initial_state(original: dict, prepared: dict) -> int:
    # submod transfers init to the new driver wire and clears the former alias.
    # Equivalence alone can accept a dropped/added initial constraint, so compare
    # the complete known-bit sets independently before running full synthesis.
    old, new = known_init(original), known_init(prepared)
    mapping = {}
    for name, wire in original['netnames'].items():
        peer = prepared['netnames'].get(name)
        if peer is None:
            continue  # submod may clean unused, uninitialized temporary wires.
        if len(wire['bits']) != len(peer['bits']):
            raise RuntimeError('native wire width changed during preparation: ' + name)
        for before, after in zip(wire['bits'], peer['bits']):
            if before in mapping and mapping[before] != after:
                raise RuntimeError('native alias mapping changed during preparation: ' + name)
            mapping[before] = after
    expected = {}
    for before, value in old.items():
        if before not in mapping:
            raise RuntimeError('initialized native bit lost every surviving alias')
        after = mapping[before]
        if after in expected and expected[after] != value:
            raise RuntimeError('inconsistent native initial bits collapsed')
        expected[after] = value
    if expected != new:
        raise RuntimeError('native initial-state constraints changed during preparation')
    return len(old)


def check_interface(original: dict, prepared: dict) -> None:
    def geometry(module: dict) -> dict:
        return {name: (port['direction'], len(port['bits']))
                for name, port in module['ports'].items()}

    if geometry(original) != geometry(prepared):
        raise RuntimeError('native port set, directions or widths changed during preparation')


def partition_cells(design: dict, top: str) -> int:
    if set(design['modules']) != {top}:
        raise RuntimeError('synthesis preparation requires a completely flattened native design')
    module = design['modules'][top]
    if module.get('processes') or module.get('memories'):
        raise RuntimeError('native process or memory remains before synthesis preparation')
    for name in ('blackbox', 'whitebox'):
        if name in module.get('attributes', {}):
            raise RuntimeError('boxed native module cannot be synthesis preparation input')
    for index, (name, cell) in enumerate(sorted(module['cells'].items())):
        if cell['type'].startswith('$mem'):
            raise RuntimeError('native memory ownership is outside this synthesis preparation')
        attributes = cell.setdefault('attributes', {})
        if 'submod' in attributes:
            raise RuntimeError('native cell already carries a submod directive: ' + name)
        group = f'__synthesis_cell_{index:04d}'
        while group in module['cells'] or top + '_' + group in design['modules']:
            group += '_'
        # Only attributes change. Every native cell, including state and control
        # cells, is included; no operation, width, count or fixture is selected.
        attributes['submod'] = group
    return len(module['cells'])


def check_mapped_hierarchy(report: dict, top: str, primitives: set[str]) -> tuple[int, int]:
    def identifier(name: str) -> str:
        return name[1:] if name.startswith('\\') else name

    modules = {identifier(name): value for name, value in report['modules'].items()}
    if top not in modules:
        raise RuntimeError('full synthesis statistics lost the native top')
    totals, reached = {}, set()

    def visit(name: str, active: set[str]) -> dict[str, int]:
        if name in active:
            raise RuntimeError('cyclic module hierarchy after full synthesis')
        reached.add(name)
        if name in totals:
            return totals[name]
        module = modules[name]
        if any(module[key] for key in ('num_processes', 'num_memories', 'num_memory_bits')):
            raise RuntimeError('unmapped process or memory remains after full synthesis')
        cells = module['num_cells_by_type']
        if any(not isinstance(n, int) or n < 0 for n in cells.values()) or sum(cells.values()) != module['num_cells']:
            raise RuntimeError('inconsistent full synthesis cell inventory')
        result = {}
        for cell_type, count in cells.items():
            cell_type = identifier(cell_type)
            if cell_type in modules:
                contribution = visit(cell_type, active | {name})
            elif cell_type in primitives:
                contribution = {cell_type: 1}
            else:
                raise RuntimeError('unknown or unmapped cell after full synthesis: ' + cell_type)
            for leaf, instances in contribution.items():
                result[leaf] = result.get(leaf, 0) + count * instances
        totals[name] = result
        return result

    leaves = visit(top, set())
    if reached != set(modules):
        raise RuntimeError('unreachable modules remain outside the checked synthesis hierarchy')
    aggregate = report['design']
    if leaves != aggregate['num_cells_by_type'] or sum(leaves.values()) != aggregate['num_cells']:
        raise RuntimeError('full synthesis hierarchy and leaf inventories disagree')
    return sum(leaves.values()), len(reached)


def qualify(module: str, paths: list[Path], work: Path, role: str, run, quoted) -> dict:
    started = time.monotonic()

    def path(suffix: str) -> Path:
        return work / (role + '-' + suffix)

    # Capture actual specialized native ports before flattening the wrapper.
    path('frontend.ys').write_text('read_verilog ' + ' '.join(quoted(p) for p in paths) +
        f'\nhierarchy -check -top {module}\nproc\nwrite_json {quoted(path("ports.json"))}\n' +
        f'flatten\nhierarchy -check -top {module}\nwrite_json {quoted(path("flat.json"))}\n')
    run(['yosys', '-Q', '-T', '-s', str(path('frontend.ys'))], path('frontend.log'))
    original = json.loads(path('flat.json').read_text())
    native = json.loads(path('ports.json').read_text())
    check_interface(native['modules'][module], original['modules'][module])
    grouped = json.loads(path('flat.json').read_text())
    cell_count = partition_cells(grouped, module)
    path('grouped.json').write_text(json.dumps(grouped, indent=2) + '\n')

    path('preparation.ys').write_text(
        f'read_json {quoted(path("flat.json"))}\nrename {module} gold\ndesign -stash original\n' +
        f'read_json {quoted(path("grouped.json"))}\nsubmod -hidden\nselect -clear\n' +
        f'hierarchy -check -top {module}\nflatten\nwrite_json {quoted(path("prepared-flat.json"))}\n' +
        f'rename {module} gate\ndesign -copy-from original gold\n' +
        'equiv_make gold gate equiv\nhierarchy -top equiv\nequiv_struct -fwd -icells\n' +
        'opt_clean\nopt_expr\nequiv_simple\nequiv_induct -seq 8\nequiv_status -assert\n')
    proof = run(['yosys', '-Q', '-T', '-s', str(path('preparation.ys'))], path('preparation.log'))
    if 'Executing EQUIV_STATUS pass' not in proof:
        raise RuntimeError('synthesis preparation lacks its asserted equivalence result')
    prepared = json.loads(path('prepared-flat.json').read_text())
    check_interface(original['modules'][module], prepared['modules'][module])
    initial_bits = check_initial_state(original['modules'][module], prepared['modules'][module])

    inventory = run(['yosys', '-Q', '-T', '-p', 'help -cells'], path('primitive-cells.log'))
    primitives = set(re.findall(r'(?m)^\s+(\$_[A-Za-z0-9_]+_)\s+\(', inventory))
    if not primitives:
        raise RuntimeError('Yosys did not identify its recognized mapped primitive cells')
    # Full synth still performs every default pass, including ABC for every
    # module. Native cell boundaries limit each ABC working set; no logic is
    # removed from the flow. A relative tee path avoids Yosys's literal quotes.
    path('synthesis.ys').write_text(
        f'read_json {quoted(path("grouped.json"))}\nsubmod -hidden\nselect -clear\n' +
        f'synth -top {module}\ncheck -assert\n' +
        'select -assert-none p:* m:*\nselect -assert-none =A:blackbox =A:whitebox\nstat\n' +
        f'tee -o {role}-final-stat.json stat -json\n')
    run(['yosys', '-Q', '-T', '-s', str(path('synthesis.ys'))], path('synthesis.log'),
        timeout=1200, cwd=work)
    cells, modules = check_mapped_hierarchy(json.loads(path('final-stat.json').read_text()), module, primitives)
    return dict(cells=cells, elapsed_seconds=time.monotonic() - started,
        full_synthesis_elapsed_seconds=json.loads(path('synthesis.timing.json').read_text())['elapsed_seconds'],
        partitioned_native_cells=cell_count, mapped_modules=modules,
        preparation_equivalence='PASS', preserved_known_init_bits=initial_bits)
