#!/usr/bin/env python3
"""Independently inspect proved-state finite diagnostic evidence without HDL execution.

Bind this inspector through a successor wrapper to a separately reviewed exact
source/tree/checker identity. Historical inspectors remain unchanged.

Invoke via verify-local-enable-diagnostic-successor-v2.py with the independently
reviewed --source, --tree and --checker-sha; do not use historical default identity.

--mode failed validates retained evidence and reports observed failure/partial
coverage. It never labels the diagnostic successful, even if retained unit tests
passed before a hardware-generation or hardware-check failure.
"""
from __future__ import annotations

import argparse
from collections import Counter
import copy
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import types
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
HEAD = '6a858ead678f7ec96e9a421f2b4551bcd62d65d9'
TREE = '48ee0b33c8c88ca030603141a6c734648bb54257'
BASE = '90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6'
MAIN_CHECKER = 'morphhdl/scripts/check-increment-59i-composite-local-enable.py'
COMBINED_CHECKER = 'morphhdl/scripts/check-increment-59i-local-enable-combined.py'
PINNED_CHECKERS = {
    MAIN_CHECKER: '58ed81a4eba4fa080618ec4cb7a4a8f082155cc6c583034e68dc3862c471bcfd',
    COMBINED_CHECKER: '0bba1edf5fbf070c55f943dcc28f38b433756d696d0d723a79df73ca3594909a',
}


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def file_digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def git(root, *args):
    result = subprocess.run(['git', '--literal-pathspecs', *args], cwd=root,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    require(result.returncode == 0, 'git inspection failed: ' + result.stderr.decode(errors='replace'))
    return result.stdout


def relative(name):
    require(isinstance(name, str) and name and '\\' not in name and '\0' not in name,
            'invalid artifact path: ' + repr(name))
    p = PurePosixPath(name)
    require(not p.is_absolute() and '..' not in p.parts and p.as_posix() == name and
            all(':' not in part for part in p.parts), 'unsafe artifact path: ' + name)
    return p


def file(root, name):
    p = relative(name)
    target = root.joinpath(*p.parts)
    require(all(not root.joinpath(*p.parts[:i]).is_symlink() for i in range(1, len(p.parts) + 1)),
            'linked artifact path: ' + name)
    require(target.is_file(), 'missing artifact: ' + str(target))
    return target


def read_json(path):
    # Duplicate JSON keys could otherwise conceal a replaced identity/hash.
    def pairs(values):
        result = {}
        for key, value in values:
            require(key not in result, 'duplicate JSON key: ' + str(key))
            result[key] = value
        return result
    return json.loads(path.read_text(), object_pairs_hook=pairs)


def extract(archive, expected_digest, destination):
    supplied = expected_digest.removeprefix('sha256:')
    require(re.fullmatch('[0-9a-f]{64}', supplied), 'GitHub artifact digest must be SHA-256')
    require(archive.is_file() and not archive.is_symlink(), 'missing/linked archive')
    require(file_digest(archive) == supplied, 'GitHub ZIP digest mismatch: ' + str(archive))
    require(not destination.exists(), 'extract destination already exists: ' + str(destination))
    with zipfile.ZipFile(archive) as z:
        infos = z.infolist()
        require(0 < len(infos) <= 20000 and sum(i.file_size for i in infos) <= 4 * 1024 ** 3,
                'empty or oversized archive')
        seen = set()
        for info in infos:
            name = info.filename[:-1] if info.is_dir() else info.filename
            relative(name)
            require(name not in seen, 'duplicate ZIP entry: ' + name)
            seen.add(name)
            mode = info.external_attr >> 16
            kind = stat.S_IFMT(mode)
            require(kind in (0, stat.S_IFDIR if info.is_dir() else stat.S_IFREG),
                    'linked or special ZIP entry: ' + name)
            require(not (info.flag_bits & 1), 'encrypted ZIP entry')
        destination.mkdir(parents=True)
        for info in infos:
            target = destination / info.filename
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(info) as source, target.open('xb') as out:
                    shutil.copyfileobj(source, out)
    return supplied


def evidence_inventory(root, allow_missing=False):
    rows = read_json(file(root, 'evidence-files.json'))
    require(isinstance(rows, list) and rows, 'missing evidence hash inventory')
    indexed, missing = {}, []
    for row in rows:
        require(set(row) == {'path', 'bytes', 'sha256'}, 'wrong evidence hash record')
        name = row['path']
        require(name not in indexed, 'duplicate evidence record: ' + name)
        p = relative(name)
        target = root.joinpath(*p.parts)
        require(all(not root.joinpath(*p.parts[:i]).is_symlink() for i in range(1, len(p.parts) + 1)),
                'linked evidence path: ' + name)
        require(type(row['bytes']) is int and row['bytes'] >= 0 and
                isinstance(row['sha256'], str) and re.fullmatch('[0-9a-f]{64}', row['sha256']),
                'invalid evidence size/hash record: ' + name)
        indexed[name] = row['sha256']
        if not target.exists():
            require(allow_missing, 'missing inventoried evidence: ' + name)
            missing.append(name)
            continue
        target = file(root, name)
        require(type(row['bytes']) is int and target.stat().st_size == row['bytes'],
                'evidence size mismatch: ' + name)
        require(file_digest(target) == row['sha256'], 'evidence hash mismatch: ' + name)
    all_files = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    require(all_files - set(indexed) == {'evidence-files.json', 'retention.log'},
            'evidence inventory coverage differs: ' + repr(sorted(all_files - set(indexed))))
    require(set(indexed) - all_files == set(missing), 'inventory missing-file accounting differs')
    return dict(indexed=indexed, missing=sorted(missing), present_files=len(all_files))


def source_identity(root, repo, payload, source_env='59i-local-enable-source', output_env='59i-local-enable-evidence'):
    require(file(root, 'head.txt').read_text().strip() == HEAD, 'wrong actual source head')
    require(file(root, 'tree.txt').read_text().strip() == TREE, 'wrong actual source tree')
    before = file(root, 'tracked-before.txt').read_bytes()
    require(before == file(root, 'tracked-after.txt').read_bytes(), 'source index changed')
    expected_index = b''
    for entry in git(repo, 'ls-tree', '-r', HEAD).splitlines():
        prefix, name = entry.split(b'\t', 1)
        mode, _, sha = prefix.split()
        expected_index += mode + b' ' + sha + b' 0\t' + name + b'\n'
    require(before == expected_index, 'retained source index differs from immutable source tree')
    identity = read_json(file(root, 'development-identity.json'))
    expected_identity = dict(development_only=True, qualification=False, full_ci=False, source_sealed=False,
        qualified_parent=BASE, development_head=HEAD, source_tree=TREE,
        preserved_development_head=payload['checkpoint'], direct_parent=payload['parent'],
        patch_sha256=payload['patch_sha256'], bundle_sha256=payload['bundle_sha256'],
        continuations=payload['continuations'], changed_paths=payload['paths'],
        audit_inputs_included=True, remote_refs_written=False)
    require(identity == expected_identity, 'development identity/continuation receipt differs')
    require(file_digest(file(root, 'development.bundle')) == payload['bundle_sha256'], 'bundle hash mismatch')
    require(file_digest(file(root, 'development.patch')) == payload['patch_sha256'], 'patch hash mismatch')
    require(file(root, 'development.patch').read_bytes() == git(repo, 'diff', '--binary', '--full-index', BASE, HEAD),
            'retained patch differs from actual committed source')
    git(repo, 'bundle', 'verify', str(file(root, 'development.bundle')))
    require(git(repo, 'bundle', 'list-heads', str(file(root, 'development.bundle'))).decode().split() == [HEAD, 'HEAD'],
            'bundle does not advertise exact source head')
    commits = git(repo, 'rev-list', '--reverse', '--topo-order', HEAD, '^' + BASE).decode().splitlines()
    require(commits == [row['commit'] for row in payload['continuations']], 'preserved source history differs')
    for row in payload['continuations']:
        require(git(repo, 'rev-parse', row['commit'] + '^{tree}').decode().strip() == row['tree'] and
                git(repo, 'show', '-s', '--format=%P', row['commit']).decode().split() == row['parents'],
                'continuation identity differs: ' + row['commit'])
    require('59I_PRODUCTION_SUCCESSOR_PASS files=308' in
            file(root, 'qualified-parent-review.log').read_text(), 'frozen predecessor audit did not pass')
    receipt = read_json(file(root, 'path-receipt.json'))
    require(set(receipt) == {'workspace', 'source', 'output', 'source_env', 'output_env'}, 'wrong path receipt')
    require(receipt['source_env'] == source_env and
            receipt['output_env'] == output_env, 'unexpected controller relative paths')
    workspace = PurePosixPath(receipt['workspace'])
    require(workspace.is_absolute() and '..' not in workspace.parts and '\\' not in str(workspace),
            'invalid original workspace path')
    require(PurePosixPath(receipt['source']) == workspace / receipt['source_env'] and
            PurePosixPath(receipt['output']) == workspace / receipt['output_env'], 'path receipt is inconsistent')
    return receipt


def xml_results(root, scala, expected, successful):
    found, problems, rows = {}, [], []
    for path in sorted((root / 'reports').glob('*.xml')):
        doc = ET.parse(file(root, path.relative_to(root).as_posix())).getroot()
        suite = doc.get('name')
        require(doc.tag == 'testsuite' and isinstance(suite, str) and path.name == 'TEST-' + suite + '.xml',
                'XML suite identity differs: ' + path.name)
        require(suite in expected and suite not in found, 'unexpected/duplicate XML suite: ' + suite)
        cases = doc.findall('testcase')
        names = [case.get('name') for case in cases]
        require(all(case.get('classname') == suite for case in cases), 'XML class differs: ' + suite)
        require(int(doc.get('tests', '-1')) == len(cases), 'XML tests counter differs: ' + suite)
        if Counter(names) != Counter(expected[suite]):
            problems.append('testcase inventory differs: ' + suite)
        counts = {key: len(doc.findall('.//' + tag)) for key, tag in
                  [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]}
        require(all(doc.get(key) is not None and int(doc.get(key)) == count for key, count in counts.items()),
                'XML outcome counters differ from cases: ' + suite)
        if any(counts.values()):
            problems.append('nonpassing cases: ' + suite)
        found[suite] = names
        rows.append(dict(suite=suite, tests=len(cases), **counts))
    if set(found) != set(expected):
        problems.append('missing XML suites: ' + repr(sorted(set(expected) - set(found))))
    actual = read_json(file(root, 'actual-test-results.json'))
    require(actual['development_only'] is True and actual['qualification'] is False and
            actual['full_ci'] is False and actual['source_sealed'] is False and
            actual['development_head'] == HEAD and actual['scala'] == scala,
            'actual test-results source/scope differs')
    require(actual['expected_suites'] == 10 and actual['expected_testcases'] == 138 and
            actual['actual_suites'] == len(rows) and
            actual['actual_testcases'] == sum(row['tests'] for row in rows) and actual['suites'] == rows,
            'actual test-results totals/rows differ from XML')
    if actual['successful']:
        require(not problems and actual['evidence_errors'] == [], 'successful test receipt contradicts retained evidence')
    else:
        require(isinstance(actual['evidence_errors'], list) and actual['evidence_errors'], 'failed receipt lacks error evidence')
    log = file(root, 'tests.log').read_text(errors='replace')
    if successful:
        require(actual['successful'] is True and not problems and len(found) == 10 and
                sum(map(len, found.values())) == 138 and
                'All tests passed' in log and '*** FAILED ***' not in log, 'unit diagnostic did not pass')
    return dict(passed=actual['successful'], suites=len(found), cases=sum(map(len, found.values())),
                problems=problems, receipt_errors=actual['evidence_errors'], identities=found)


def checker(repo, path):
    raw = git(repo, 'show', HEAD + ':' + path)
    require(digest(raw) == PINNED_CHECKERS[path], 'pinned hardware checker bytes changed')
    module = types.ModuleType('pinned_' + Path(path).stem.replace('-', '_'))
    module.__file__ = path
    # Only module definitions are loaded. No subprocess, simulator or checker
    # main is invoked; we use its exact manifest schema and VCD parser.
    exec(compile(raw, path, 'exec'), module.__dict__)
    return module


def mapped_log(root, paths, original):
    p = PurePosixPath(original)
    prefix = PurePosixPath(paths['output'])
    require(p.is_absolute() and '..' not in p.parts and prefix in p.parents,
            'log escapes original evidence directory: ' + str(original))
    return file(root, p.relative_to(prefix).as_posix())


def hashes(root, values):
    require(isinstance(values, dict) and values, 'empty artifact hash map')
    for name, expected in values.items():
        require(re.fullmatch('[0-9a-f]{64}', expected) and file_digest(file(root, name)) == expected,
                'retained artifact hash differs: ' + name)


def simulation(root, paths, rtl_root, row, directory, pass_marker, fail_marker, mutation=False):
    log_path = mapped_log(root, paths, row['log'])
    require(log_path == file(directory, 'simulate.log'), 'simulation log points at another case')
    log = log_path.read_text()
    if mutation:
        require(row['result'] == 'counterexample' and fail_marker in log and pass_marker not in log and
                'candidate=0' in log, 'mutation lacks an actual functional mismatch')
    else:
        require(row['result'] == 'pass' and log.count(pass_marker) == 1 and fail_marker not in log,
                'native comparison log did not pass')
    hashes(rtl_root, row['rtl_sha256'])
    require(file_digest(file(directory, 'tb.v')) == row['testbench_sha256'], 'testbench hash differs')
    file(directory, 'compile.log')
    require(file(directory, 'tb.vvp').stat().st_size > 0, 'empty simulator executable')


FINITE_FILES = ('miter.v', 'state-extraction.ys', 'state-extraction.log',
                'normalized-netlist.json', 'strengthened-netlist.json',
                'state-correspondence.json', 'equivalence.ys', 'formal.log', 'finite-proof.json')
FINITE_PATHS = {'log': 'formal.log', 'script': 'equivalence.ys', 'miter': 'miter.v',
                'normalized_netlist': 'normalized-netlist.json',
                'strengthened_netlist': 'strengthened-netlist.json',
                'state_correspondence': 'state-correspondence.json', 'finite_proof': 'finite-proof.json'}
FINITE_PASS = 'Reached maximum number of time steps -> proved base case for 17 steps: SUCCESS!'


def finite_log_passed(log):
    """The Yosys 0.41 base-only loop checks prefix 1 plus lengths 1..17."""
    lengths = [int(value) for value in re.findall(r'^Base case for induction length (\d+) proven\.$',
                                                  log, re.M)]
    return (lengths == list(range(1, 18)) and log.count(FINITE_PASS) == 1 and
            log.index(FINITE_PASS) > log.rfind('Base case for induction length 17 proven.') and
            not re.search(r'ERROR:|FAIL|TIMEOUT|time(?:d)? out|Skipping', log, re.I))


def assert_additive_strengthening(original, strengthened, pairs):
    """Verify the lemma generator changed no DUT cell, input, or state metadata."""
    require(set(original['modules']) == {'miter'} and set(strengthened['modules']) == {'miter'},
            'finite proof normalized/strengthened top differs')
    before, after = original['modules']['miter'], strengthened['modules']['miter']
    require(set(before) == set(after), 'strengthening changed module structure')
    require(set(before['cells']).issubset(after['cells']), 'strengthening removed an original DUT cell')
    require(all(after['cells'][name] == cell for name, cell in before['cells'].items()),
            'strengthening rewired or changed an original DUT cell')
    registers = {name for name, cell in before['cells'].items()
                 if cell['type'] in ('$_DFF_P_', '$_DFF_N_')}
    paired_names = []
    q_bits = set()
    property_bits = before['ports']['bad']['bits'][:]
    require(len(property_bits) == 1, 'original output-bit property is not scalar')
    for index, pair in enumerate(pairs):
        require(set(pair) == {'left', 'right', 'left_q', 'right_q'}, 'state pairing schema differs')
        left, right = pair['left'], pair['right']
        require(left in registers and right in registers and left != right,
                'state correspondence does not pair independent original flip-flops')
        left_cell, right_cell = before['cells'][left], before['cells'][right]
        require(left_cell['type'] == right_cell['type'] and
                pair['left_q'] == left_cell['connections']['Q'] and
                pair['right_q'] == right_cell['connections']['Q'] and
                len(pair['left_q']) == len(pair['right_q']) == 1,
                'state correspondence Q or clock edge differs from original cells')
        paired_names.extend((left, right))
        q_bits.update(pair['left_q'] + pair['right_q'])
        xor = after['cells'][f'$59i_proved_state${index}$equal']
        combined = after['cells'][f'$59i_proved_state${index}$property']
        for cell, kind, a, b in ((xor, '$_XOR_', pair['left_q'], pair['right_q']),
                                  (combined, '$_OR_', property_bits, xor['connections']['Y'])):
            require(set(cell) == {'hide_name', 'type', 'parameters', 'attributes',
                                 'port_directions', 'connections'} and cell['hide_name'] == 1 and
                    cell['type'] == kind and cell['parameters'] == {} and cell['attributes'] == {} and
                    cell['port_directions'] == {'A': 'input', 'B': 'input', 'Y': 'output'} and
                    set(cell['connections']) == {'A', 'B', 'Y'} and
                    cell['connections']['A'] == a and cell['connections']['B'] == b and
                    len(cell['connections']['Y']) == 1 and type(cell['connections']['Y'][0]) is int,
                    'state property is not the exact original-bad/state-XOR conjunction')
        property_bits = combined['connections']['Y']
    require(len(paired_names) == len(set(paired_names)) and set(paired_names) == registers,
            'state correspondence omits, duplicates, or correlates an original flip-flop')
    initialized = {bit for net in before['netnames'].values() if 'init' in net.get('attributes', {})
                   for bit in net['bits']}
    require(not q_bits.intersection(initialized), 'original flip-flop initial state was constrained')
    require(after['ports']['bad']['bits'] == property_bits and
            after['netnames']['bad']['bits'] == property_bits,
            'strengthened property omits the original output or a state equality')
    expected_added = {f'$59i_proved_state${index}${suffix}'
                      for index in range(len(pairs)) for suffix in ('equal', 'property')}
    require(set(after['cells']) - set(before['cells']) == expected_added,
            'strengthening added unexpected cells')
    require(set(before['ports']) == set(after['ports']) and
            all(after['ports'][name] == port for name, port in before['ports'].items() if name != 'bad'),
            'strengthening changed an input or added a cutpoint')
    require(set(before['netnames']) == set(after['netnames']) and
            all(after['netnames'][name] == net for name, net in before['netnames'].items() if name != 'bad'),
            'strengthening changed signal metadata or state initialization')
    restored = copy.deepcopy(strengthened)
    restored_module = restored['modules']['miter']
    for name in expected_added:
        cell = restored_module['cells'].pop(name)
        require(cell['type'] in ('$_XOR_', '$_OR_') and cell['parameters'] == {} and
                cell['attributes'] == {}, 'strengthening includes state or assumptions')
    restored_module['ports']['bad'] = copy.deepcopy(before['ports']['bad'])
    restored_module['netnames']['bad'] = copy.deepcopy(before['netnames']['bad'])
    require(restored == original, 'strengthening modified other original netlist content')
    # New temporary signals must not alias retained but unconnected netnames.
    occupied = {bit for net in before['netnames'].values() for bit in net['bits'] if isinstance(bit, int)}
    occupied.update(bit for cell in before['cells'].values() for bits in cell['connections'].values()
                    for bit in bits if isinstance(bit, int))
    occupied.update(bit for port in before['ports'].values() for bit in port['bits'] if isinstance(bit, int))
    added_outputs = [bit for name in expected_added for bit in after['cells'][name]['connections']['Y']]
    require(len(set(added_outputs)) == len(added_outputs) and not occupied.intersection(added_outputs),
            'strengthening aliases an existing signal or another lemma output')


def finite_bit_proof(root, paths, main, case, candidate, field, bit, work,
                     partition=None, successful=True):
    """Inspect one complete finite proof without executing HDL tools."""
    original_root = PurePosixPath(paths['output'])
    original_work = original_root / work.relative_to(root).as_posix()
    sources = [original_root / 'hardware-A' / entry['file'] for entry in (case, candidate)] + \
              [original_work / 'miter.v']
    require(file(work, 'miter.v').read_text() == main.miter(case, [candidate], (field, bit)),
            'finite output-bit miter differs from pinned generator')
    extraction = file(work, 'state-extraction.ys')
    require(extraction.read_text() == main.formal_setup(sources) +
            'write_json ' + main.quoted(original_work / 'normalized-netlist.json') + '\n',
            'finite normalization extraction script differs')
    extraction_log = file(work, 'state-extraction.log').read_text()
    require("-- Executing script file `" + str(original_work / 'state-extraction.ys') + "' --" in extraction_log
            and not re.search(r'ERROR:|FAIL!|time(?:d)? out', extraction_log, re.I),
            'normalization log does not authenticate successful extraction')
    for source in sources:
        require("Parsing Verilog input from `" + str(source) + "' to AST representation." in extraction_log,
                'normalization log does not include exact retained source: ' + str(source))
    original_path = file(work, 'normalized-netlist.json')
    strengthened_path = file(work, 'strengthened-netlist.json')
    original, strengthened = read_json(original_path), read_json(strengthened_path)
    expected_strengthened, pairs = main.strengthen_state_invariant(original)
    require(strengthened == expected_strengthened and strengthened_path.read_text() ==
            json.dumps(expected_strengthened, sort_keys=True, indent=2) + '\n',
            'retained strengthening differs from deterministic reviewed construction')
    assert_additive_strengthening(original, strengthened, pairs)
    correspondence_path = file(work, 'state-correspondence.json')
    correspondence = dict(schema=1,
        contract='All state equalities are additional proved obligations, never assumptions.',
        registers=pairs, original_sha256=file_digest(original_path),
        strengthened_sha256=file_digest(strengthened_path),
        original_cells=len(original['modules']['miter']['cells']),
        preserved_original_cells=len(original['modules']['miter']['cells']),
        initial_state='independent and unconstrained', obligation_steps=list(range(2, 19)))
    require(read_json(correspondence_path) == correspondence and correspondence_path.read_text() ==
            json.dumps(correspondence, sort_keys=True, indent=2) + '\n',
            'state correspondence receipt differs from original/preserved cells')
    command = ('sat -seq 1 -tempinduct-baseonly -maxsteps 17 -set-def-inputs '
               f"-set-at 1 reset {int(not case['reset_low'])} "
               f"-set-at 1 enable {int(not case['enable_low'])} "
               '-prove bad 0 -timeout 120 -verify\n')
    require(command == main.finite_base_command(case), 'reviewed finite base conditions differ')
    script = file(work, 'equivalence.ys')
    require(script.read_text() == 'read_json ' + main.quoted(original_work / 'strengthened-netlist.json') +
            '\nhierarchy -check -top miter\ncheck -assert\nstat\n' + command,
            'finite proof uses a different model, bound, constraint, or command')
    log_path = file(work, 'formal.log')
    log = log_path.read_text()
    require("-- Executing script file `" + str(original_work / 'equivalence.ys') + "' --" in log,
            'finite proof log does not identify expected script')
    passed = finite_log_passed(log)
    expected_partition = dict(candidate=candidate['module'], field=field, bit=bit, result='pass-18-steps',
        **{key: str(original_work / filename) for key, filename in FINITE_PATHS.items()},
        state_relations=len(pairs), proved_steps=list(range(2, 19)))
    if successful:
        require(passed, 'finite proof lacks every successful checked step 2 through 18')
        require(main.require_finite_base_proof(log) == list(range(2, 19)),
                'source proof parser differs from independent finite step inspection')
        finite_path = file(work, 'finite-proof.json')
        finite = dict(schema=1, kind='finite-incremental-base-cases', result='pass',
            base_lengths=list(range(1, 18)), proved_steps=list(range(2, 19)), state_relations=len(pairs),
            unbounded_induction='not-run', reset_step=1,
            after_reset='unconstrained input, reset, and global enable',
            initial_state='independent and unconstrained',
            loop_elimination='complete-state repetition implies an already checked shorter counterexample',
            script_sha256=file_digest(script), log_sha256=file_digest(log_path),
            state_correspondence_sha256=file_digest(correspondence_path))
        require(read_json(finite_path) == finite and finite_path.read_text() ==
                json.dumps(finite, sort_keys=True, indent=2) + '\n',
                'finite receipt differs from raw proof log, script, or state obligations')
        if partition is not None:
            require(partition == expected_partition, 'bit partition receipt/path/coverage differs')
        for key, filename in FINITE_PATHS.items():
            require(mapped_log(root, paths, expected_partition[key]) == file(work, filename),
                    'bit partition path escapes its obligation')
    else:
        require(not (work / 'finite-proof.json').exists() or passed,
                'failed finite log carries a false positive proof receipt')
    return dict(passed=passed, original_cells=len(original['modules']['miter']['cells']),
                state_relations=len(pairs), proved_steps=list(range(2, 19)) if passed else [],
                normalized_netlist_sha256=file_digest(original_path),
                strengthened_netlist_sha256=file_digest(strengthened_path), partition=expected_partition)


def strict_and_formal(row, directory, main, case, candidates, root, paths, selected=False, mutation=False):
    require(row['lint'] == 'pass' and row['synthesis'] == 'pass', 'strict native tool checks did not pass')
    file(directory, 'lint.log')
    original_root = PurePosixPath(paths['output'])
    original_directory = original_root / directory.relative_to(root).as_posix()
    source_files = [original_root / 'hardware-A' / entry['file'] for entry in [case] + candidates]
    sources = source_files + [original_directory / 'miter.v']
    require(file(directory, 'miter.v').read_text() == main.miter(case, candidates),
            'complete comparator miter differs from pinned source')
    synthesis = file(directory, 'synthesis.ys').read_text()
    require(synthesis == 'read_verilog ' + ' '.join(main.quoted(path) for path in sources) +
            '\nhierarchy -check -top miter\nsynth -top miter\ncheck -assert\nstat\n',
            'synthesis check script differs')
    file(directory, 'synthesis.log')
    expected = 'counterexample' if mutation else ('pass-18-steps' if selected else 'not-selected')
    require(row['formal'] == expected, 'bounded proof selection/result differs')
    command = (f"sat -seq 18 -set-def-inputs -set-at 1 reset {int(not case['reset_low'])} "
               f"-set-at 1 enable {int(not case['enable_low'])} "
               '-prove bad 0 -prove-skip 1 -timeout 120 ')
    if mutation:
        require('formal_partitions' not in row, 'mutation must retain complete comparator proof')
        script = file(directory, 'equivalence.ys').read_text()
        require(script == main.formal_setup(sources) + command +
                '-show-inputs -show-outputs -dump_vcd ' + main.quoted(original_directory / 'counterexample.vcd') + '\n',
                'complete mutation proof script differs from pinned source')
        log = file(directory, 'formal.log').read_text()
        require(main.SAT_FAIL in log and main.SAT_PASS not in log, 'mutation lacks formal mismatch')
        main.require_counterexample(file(directory, 'counterexample.vcd'))
    elif selected:
        require(not (directory / 'formal.log').exists() and not (directory / 'equivalence.ys').exists(),
                'selected bit-proof case retains stale monolithic proof files')
        partitions = row['formal_partitions']
        expected_identities = [(candidate['module'], field, bit) for candidate in candidates
            for field, width in main.ports(case).items() for bit in range(width)]
        require(len(partitions) == len(expected_identities) and
                Counter((p['candidate'], p['field'], p['bit']) for p in partitions) == Counter(expected_identities),
                'missing, extra, or duplicate candidate/field/bit proof obligation')
        require(partitions == read_json(file(directory, 'formal-partitions.json')),
                'partition receipt differs from case result')
        actual_files = {p.relative_to(directory / 'formal-bits').as_posix()
                        for p in (directory / 'formal-bits').rglob('*') if p.is_file()}
        expected_files = set()
        for candidate_index, candidate in enumerate(candidates):
            for field, width in main.ports(case).items():
                for bit in range(width):
                    match = [p for p in partitions if (p['candidate'], p['field'], p['bit']) ==
                             (candidate['module'], field, bit)]
                    require(len(match) == 1, 'ambiguous bit-proof identity')
                    p = match[0]
                    require(type(p['bit']) is int and p['result'] == 'pass-18-steps',
                            'invalid bit-proof receipt')
                    label = f'c{candidate_index}-{field}-{bit}'
                    work = directory / 'formal-bits' / label
                    expected_files.update(label + '/' + filename for filename in FINITE_FILES)
                    finite_bit_proof(root, paths, main, case, candidate, field, bit, work, p)
        require(actual_files == expected_files, 'bit-proof file set has missing or extra obligations')
    else:
        require('formal_partitions' not in row, 'unselected case unexpectedly claims bit obligations')


def main_hardware(root, paths, main, console_log='hardware-checks.log'):
    rtl = root / 'hardware-A'
    output = root / 'hardware-checks'
    manifest = read_json(file(rtl, 'manifest.json'))
    main.validate(manifest)
    require(all(re.fullmatch('[A-Za-z_][A-Za-z0-9_]*', c['id']) for c in manifest['cases']),
            'unsafe main case directory identity')
    require(file(rtl, 'manifest.json').read_bytes() == file(root / 'hardware-B', 'manifest.json').read_bytes(),
            'main A/B manifests differ')
    for entry in manifest['cases'] + manifest['candidates']:
        file(rtl, entry['file'])
    receipt = read_json(file(output, 'receipt.json'))
    require(set(receipt) == {'schema', 'scope', 'result', 'native_cases', 'candidate_comparisons',
            'cycles_per_case', 'formal', 'results', 'normalized_unmutated', 'mutations'}, 'main receipt schema differs')
    require(receipt['schema'] == 1 and receipt['scope'] == main.SCOPE and receipt['result'] == 'pass' and
            receipt['native_cases'] == 96 and receipt['candidate_comparisons'] == 192 and
            receipt['cycles_per_case'] == 384, 'main coverage receipt differs')
    require(receipt['formal'] == {'kind': 'bounded-active-edge-equivalence', 'steps': 18, 'cases': 32,
        'decomposition': 'all candidate/output-bit obligations plus proved reachable state equalities',
        'method': 'finite incremental base cases for steps 2 through 18; no state merging or cut assumptions',
        'bit_obligations': 1024,
        'initial_state': 'unconstrained; enabled native reset at step 1',
        'after_reset': 'unconstrained input, reset, and global enable',
        'async': 'async2sync edge model; raw RTL asynchronous timing covered by simulation',
        'unbounded_induction': 'not-run'}, 'formal scope receipt differs')
    require(len(receipt['results']) == 96, 'main result count differs')
    for case, row in zip(manifest['cases'], receipt['results']):
        candidates = [c for c in manifest['candidates'] if c['profile'] == case['profile']]
        require(row['id'] == case['id'] and row['candidates'] == [c['module'] for c in candidates] and
                set(row['rtl_sha256']) == {c['file'] for c in [case] + candidates}, 'main native case identity differs')
        directory = output / case['id']
        simulation(root, paths, rtl, row, directory, main.PASS, main.FAIL)
        strict_and_formal(row, directory, main, case, candidates, root, paths,
                          tuple(case[k] for k in ('uw', 'sw', 'bw')) == (5, 7, 3))
    anchor = next(c for c in manifest['cases'] if c['profile'] == 'sync_high_rising' and
                  tuple(c[k] for k in ('uw', 'sw', 'bw', 'count')) == (5, 7, 3, 2))
    candidate = next(c for c in manifest['candidates'] if c['profile'] == anchor['profile'] and not c['split'])
    normalized = receipt['normalized_unmutated']
    require(normalized['id'] == anchor['id'] and normalized['candidates'] == ['LocalEnableActualRtl_unmutated'] and
            set(normalized['rtl_sha256']) == {anchor['file'], 'actual-rtl-mutations/LocalEnableActualRtl_unmutated.v'},
            'normalized baseline identity differs')
    simulation(root, paths, rtl, normalized, output / 'normalized-unmutated', main.PASS, main.FAIL)
    normalized_candidate = dict(module='LocalEnableActualRtl_unmutated',
        file='actual-rtl-mutations/LocalEnableActualRtl_unmutated.v', concrete=True)
    strict_and_formal(normalized, output / 'normalized-unmutated', main, anchor,
                      [normalized_candidate], root, paths, True)
    require(sum(len(row.get('formal_partitions', [])) for row in receipt['results']) == 1024 and
            len(normalized['formal_partitions']) == 16, 'complete proof conjunction total differs')
    original_path = file(rtl, 'actual-rtl-mutations/unmutated.json')
    original = read_json(original_path)
    require(original == read_json(file(rtl, 'actual-rtl-mutations/original.json')), 'baseline netlist changed')
    require(len(receipt['mutations']) == 2, 'missing emitted main mutations')
    for label, row in zip(('enable-identity', 'reset-value'), receipt['mutations']):
        mutant_module = 'LocalEnableActualRtl_' + label.replace('-', '_')
        require(row['id'] == anchor['id'] and row['candidates'] == [mutant_module] and
                set(row['rtl_sha256']) == {anchor['file'], 'actual-rtl-mutations/' + mutant_module + '.v'},
                'main mutation case/module differs')
        directory = output / ('mutation-' + label)
        simulation(root, paths, rtl, row, directory, main.PASS, main.FAIL, True)
        mutant_candidate = dict(module=mutant_module, file='actual-rtl-mutations/' + mutant_module + '.v', concrete=True)
        strict_and_formal(row, directory, main, anchor, [mutant_candidate], root, paths, True, True)
        witness = row['mutation_witness']
        require(set(witness) == {'cell', 'port', 'index', 'before', 'after', 'changed_connections',
                'emitted_source', 'emitted_source_sha256', 'original_netlist_sha256', 'mutated_netlist_sha256'},
                'main mutation witness schema differs')
        require(witness['changed_connections'] == 1 and witness['before'] != witness['after'] and
                witness['emitted_source'] == candidate['file'] and
                file_digest(file(rtl, witness['emitted_source'])) == witness['emitted_source_sha256'] and
                file_digest(original_path) == witness['original_netlist_sha256'], 'main mutation source lineage differs')
        mutant_path = file(rtl, 'actual-rtl-mutations/' + label + '.json')
        require(file_digest(mutant_path) == witness['mutated_netlist_sha256'] != witness['original_netlist_sha256'],
                'mutated netlist hash differs')
        mutated = read_json(mutant_path)
        restored = copy.deepcopy(mutated)
        connection = restored['modules']['mutation_source']['cells'][witness['cell']]['connections'][witness['port']]
        require(connection[witness['index']] == witness['after'], 'mutated connection differs')
        connection[witness['index']] = witness['before']
        require(restored == original and mutated != original, 'mutation changed more than one netlist connection')
    require(main.PASS + ': 192 native comparisons; 2 semantic mutations rejected' in
            file(root, console_log).read_text(), 'missing complete main hardware log')
    return dict(native_cases=96, comparisons=192, cycles=384, bounded_18_step_cases=32,
                candidate_output_bit_obligations=1024, normalized_baseline_bit_obligations=16,
                normalized_baselines=1, emitted_mutations=2, unbounded_induction=False)


def combined_hardware(root, paths, combined, console_log='combined-checks.log'):
    rtl = root / 'hardware-A/combined'
    output = rtl / 'checks'
    manifest = read_json(file(rtl, 'manifest.json'))
    combined.validate(manifest)
    require(file(rtl, 'manifest.json').read_bytes() == file(root / 'hardware-B/combined', 'manifest.json').read_bytes(),
            'combined A/B manifests differ')
    for entry in manifest['cases'] + manifest['candidates']:
        for name in entry['files']:
            file(rtl, name)
    receipt = read_json(file(output, 'receipt.json'))
    require(set(receipt) == {'schema', 'scope', 'result', 'native_cases', 'candidate_comparisons',
            'cycles_per_case', 'formal', 'results', 'mutations', 'normalized_baseline', 'mutation_lineage'},
            'combined receipt schema differs')
    require(receipt['schema'] == 1 and receipt['scope'] == combined.SCOPE and receipt['result'] == 'pass' and
            receipt['native_cases'] == 6 and receipt['candidate_comparisons'] == 24 and
            receipt['cycles_per_case'] == 320 and receipt['formal'] == 'not-run', 'combined coverage differs')
    require(len(receipt['results']) == 6, 'combined case count differs')
    for case, row in zip(manifest['cases'], receipt['results']):
        require(row['case'] == case['id'] and row['comparisons'] == 4 and
                set(row['rtl_sha256']) == {f for c in [case] + manifest['candidates'] for f in c['files']},
                'combined case identity differs')
        simulation(root, paths, rtl, row, output / case['id'], combined.PASS, combined.FAIL)
    anchor = next(c for c in manifest['cases'] if tuple(c[k] for k in combined.KEYS) == (2, 4, 2, 1, 3, 0))
    candidate = next(c for c in manifest['candidates'] if
                     (c['layout'], c['signed_mode'], c['split']) == ('packed', 'legacy', False))
    baseline = receipt['normalized_baseline']
    require(baseline['case'] == anchor['id'] and baseline['comparisons'] == 1 and
            set(baseline['rtl_sha256']) == set(anchor['files']) | {'checks/mutated-emitted-rtl/normalized.v'},
            'combined baseline identity differs')
    simulation(root, paths, rtl, baseline, output / 'normalized-baseline', combined.PASS, combined.FAIL)
    require(len(receipt['mutations']) == 1, 'combined mutation count differs')
    mutant = receipt['mutations'][0]
    require(mutant['case'] == anchor['id'] and mutant['comparisons'] == 1 and
            set(mutant['rtl_sha256']) == set(anchor['files']) | {'checks/mutated-emitted-rtl/mutant.v'},
            'combined mutant identity differs')
    simulation(root, paths, rtl, mutant, output / 'mutation-root-enable-polarity', combined.PASS, combined.FAIL, True)
    lineage = receipt['mutation_lineage']
    require(set(lineage) == {'kind', 'cell', 'cell_type', 'before', 'after', 'source_rtl_sha256',
            'original_json_sha256', 'mutant_json_sha256'} and lineage['kind'] == 'root-enable-polarity' and
            lineage['cell_type'] in ('$dffe', '$sdffe', '$sdffce', '$adffe') and
            int(lineage['before'], 2) + int(lineage['after'], 2) == 1 and
            set(lineage['source_rtl_sha256']) == set(candidate['files']), 'combined mutation lineage differs')
    hashes(rtl, lineage['source_rtl_sha256'])
    directory = output / 'mutated-emitted-rtl'
    original_path, mutant_path = file(directory, 'specialized.json'), file(directory, 'mutant.json')
    require(file_digest(original_path) == lineage['original_json_sha256'] and
            file_digest(mutant_path) == lineage['mutant_json_sha256'] != lineage['original_json_sha256'],
            'combined mutation netlist hashes differ')
    original, changed = read_json(original_path), read_json(mutant_path)
    restored = copy.deepcopy(changed)
    cell = restored['modules'][candidate['module']]['cells'][lineage['cell']]
    require(cell['type'] == lineage['cell_type'] and cell['parameters']['EN_POLARITY'] == lineage['after'],
            'combined mutated cell differs')
    cell['parameters']['EN_POLARITY'] = lineage['before']
    require(restored == original and changed != original, 'combined mutation changed more than one scalar')
    for name in ('specialize.ys', 'specialize.log', 'normalized.ys', 'normalized-write.log',
                 'normalized-lint.log', 'mutant.ys', 'mutant-write.log', 'mutant-lint.log'):
        file(directory, name)
    require(combined.PASS + ': 24 native comparisons; one emitted root-enable mutant rejected' in
            file(root, console_log).read_text(), 'missing complete combined hardware log')
    return dict(native_cases=6, comparisons=24, cycles=320, normalized_baselines=1, emitted_mutations=1,
                formal='not-run')


def deterministic_rtl(root, require_postcheck=True, postcheck_log='unchanged-rtl.log'):
    inventory = read_json(file(root, 'rtl-inventory.json'))
    require(set(inventory) == {'scope', 'files'} and inventory['scope'] == 'local-enable-deterministic-rtl' and
            isinstance(inventory['files'], dict) and inventory['files'], 'missing original RTL inventory')
    for name in inventory['files']:
        require(PurePosixPath(name).suffix in ('.v', '.vhd', '.vhdl'), 'non-HDL file in original RTL inventory')
    for role in ('hardware-A', 'hardware-B'):
        hashes(root / role, inventory['files'])
    original_b = {p.relative_to(root / 'hardware-B').as_posix() for p in (root / 'hardware-B').rglob('*')
                  if p.is_file() and p.suffix in ('.v', '.vhd', '.vhdl')}
    require(original_b == set(inventory['files']), 'original B HDL inventory has missing/extra files')
    require(read_json(file(root, 'determinism.log')) == inventory, 'determinism log differs from inventory')
    if require_postcheck or (root / postcheck_log).exists():
        unchanged = read_json(file(root, postcheck_log))
        require(unchanged == dict(scope='original-local-enable-rtl-unchanged', files=len(inventory['files'])),
                'post-verification original RTL receipt differs')
    return inventory['files']


def failed_hardware_progress(root, main):
    """Observe retained logs only; no missing receipt is replaced by these counts."""
    manifest_path = root / 'hardware-A/manifest.json'
    if not manifest_path.is_file():
        return dict(main_manifest_present=False, complete_hardware_receipt=False)
    manifest = read_json(manifest_path)
    main.validate(manifest)
    observed = dict(main_manifest_present=True, complete_hardware_receipt=(root / 'hardware-checks/receipt.json').is_file(),
        simulation_pass_cases=[], simulation_mismatch_cases=[], formal_pass_cases=[],
        formal_counterexample_cases=[], formal_timeout_cases=[], formal_other_incomplete_cases=[])
    for case in manifest['cases']:
        require(re.fullmatch('[A-Za-z_][A-Za-z0-9_]*', case['id']), 'unsafe progress case identity')
        directory = root / 'hardware-checks' / case['id']
        if (directory / 'simulate.log').is_file():
            log = file(directory, 'simulate.log').read_text()
            if log.count(main.PASS) == 1 and main.FAIL not in log:
                observed['simulation_pass_cases'].append(case['id'])
            elif main.FAIL in log:
                observed['simulation_mismatch_cases'].append(case['id'])
        if (directory / 'formal.log').is_file():
            log = file(directory, 'formal.log').read_text()
            if main.SAT_PASS in log and main.SAT_FAIL not in log:
                key = 'formal_pass_cases'
            elif main.SAT_FAIL in log:
                key = 'formal_counterexample_cases'
            elif re.search(r'timeout|time(?:d)? out', log, re.I):
                key = 'formal_timeout_cases'
            else:
                key = 'formal_other_incomplete_cases'
            observed[key].append(case['id'])
    observed['supplemental_receipt_present'] = (root / 'hardware-A/combined/checks/receipt.json').is_file()
    return observed


def inspect_lane(scala, archive, zip_digest, extract_root, repo, payload, expected, mode, main, combined):
    require(scala in ('2.12.18', '2.13.12'), 'unexpected Scala lane')
    root = extract_root / scala
    actual_digest = extract(archive, zip_digest, root)
    evidence = evidence_inventory(root, allow_missing=(mode == 'failed'))
    paths = source_identity(root, repo, payload)
    tests = xml_results(root, scala, expected, mode == 'success')
    hardware = {}
    rtl = None
    problems = []
    for label, call in (
            ('deterministic_rtl', lambda: deterministic_rtl(root, require_postcheck=(mode == 'success'))),
            ('main', lambda: main_hardware(root, paths, main)),
            ('combined', lambda: combined_hardware(root, paths, combined))):
        try:
            result = call()
            if label == 'deterministic_rtl':
                rtl = result
            else:
                hardware[label] = result
        except Exception as error:
            if mode == 'success':
                raise
            problems.append(label + ': ' + str(error))
    if mode == 'failed' and not (root / 'unchanged-rtl.log').exists():
        problems.append('post-check original RTL receipt absent; available A/B inventory hashes were checked independently')
    return dict(scala=scala, zip_sha256=actual_digest, evidence_files=evidence['present_files'],
                evidence_complete=not evidence['missing'], missing_inventory_files=evidence['missing'],
                present_file_hashes_valid=True,
                extracted=str(root), source_head=HEAD, source_tree=TREE,
                unit_tests=tests, hardware=hardware, incomplete_hardware=problems,
                hardware_progress=failed_hardware_progress(root, main) if mode == 'failed' else None,
                original_rtl=rtl, diagnostic_passed=(mode == 'success'),
                source_sealed=False, qualification=False, full_ci=False)


def main_cli():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--artifact', nargs=3, action='append', metavar=('SCALA', 'ZIP', 'GITHUB_SHA256'), required=True)
    parser.add_argument('--mode', choices=('success', 'failed'), required=True)
    parser.add_argument('--extract-root', type=Path, required=True, help='new extraction directory; never overwrites')
    parser.add_argument('--repo-root', type=Path, default=HERE / '59i-dev')
    parser.add_argument('--controller', type=Path, default=HERE / 'controller-development-v4')
    parser.add_argument('--output', type=Path, help='write independent inspection summary outside extracted evidence')
    args = parser.parse_args()
    require(1 <= len(args.artifact) <= 2 and len({a[0] for a in args.artifact}) == len(args.artifact),
            'supply one or two unique Scala artifacts')
    repo = args.repo_root.resolve()
    require(git(repo, 'rev-parse', HEAD + '^{tree}').decode().strip() == TREE, 'pinned source tree missing/different')
    payload = read_json(args.controller / 'payload.json')
    require(payload['head'] == HEAD and payload['tree'] == TREE and payload['base'] == BASE, 'controller identity differs')
    expected = read_json(args.controller / 'expected-suites.json')
    require(len(expected) == 10 and sum(map(len, expected.values())) == 138, 'controller suite inventory differs')
    committed = json.loads(git(repo, 'show', HEAD + ':morphhdl/contracts/increment-59i-regression-inventory.json'))
    require(expected == {s: committed['projects']['morphhdl'][s] for s in expected}, 'committed case names differ')
    main = checker(repo, MAIN_CHECKER)
    combined = checker(repo, COMBINED_CHECKER)
    lanes = [inspect_lane(scala, Path(archive).resolve(), expected_digest, args.extract_root.resolve(),
                         repo, payload, expected, args.mode, main, combined)
             for scala, archive, expected_digest in args.artifact]
    cross = 'not-checked; only one Scala artifact supplied'
    if len(lanes) == 2:
        identities_equal = all(Counter(lanes[0]['unit_tests']['identities'].get(s, [])) ==
                               Counter(lanes[1]['unit_tests']['identities'].get(s, [])) for s in expected)
        rtl_equal = lanes[0]['original_rtl'] is not None and lanes[0]['original_rtl'] == lanes[1]['original_rtl']
        if args.mode == 'success':
            require(identities_equal and rtl_equal, 'cross-Scala original RTL or test identities differ')
        cross = dict(test_identities_equal=identities_equal, original_rtl_equal=rtl_equal)
    result = dict(scope='independent-local-enable-development-artifact-inspection',
        mode=args.mode, evidence_integrity_valid=all(lane['evidence_complete'] for lane in lanes),
        present_file_hashes_valid=True, diagnostic_passed=(args.mode == 'success'),
        source_sealed=False, qualification=False, full_ci=False, source_head=HEAD, source_tree=TREE,
        cross_scala=cross, lanes=lanes)
    raw = json.dumps(result, indent=2) + '\n'
    if args.output:
        destination = args.output.resolve()
        require(args.extract_root.resolve() not in destination.parents, 'summary must not alter extracted evidence')
        require(not destination.exists(), 'summary output already exists')
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(raw)
    # Keep terminal output compact; full identities and inventories live in the
    # requested independent summary, never inside the downloaded evidence.
    print(json.dumps({k: v for k, v in result.items() if k != 'lanes'} | {'lanes': [
        {k: v for k, v in lane.items() if k not in ('unit_tests', 'original_rtl', 'missing_inventory_files')} |
        {'unit_tests': {k: v for k, v in lane['unit_tests'].items() if k != 'identities'},
         'missing_inventory_files_count': len(lane['missing_inventory_files']),
         'original_rtl_files': len(lane['original_rtl']) if lane['original_rtl'] else 0} for lane in lanes]}, indent=2))


if __name__ == '__main__':
    try:
        main_cli()
    except Exception as error:
        print('ARTIFACT_INSPECTION_FAILED: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
