#!/usr/bin/env python3
"""Authenticate current source and immutable evidence without historical execution.

The producer runs once for a candidate qualification. Consumers require the
receipt digest supplied by that successful producer (for example a CI job
output), never a digest supplied inside the downloaded receipt itself.
Historical objects establish provenance only; current RTL gates remain required.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
SELF = 'morphhdl/scripts/check-candidate-provenance.py'
INVENTORY = 'morphhdl/contracts/candidate-provenance.json'
POLICY = 'AGENTS.md'
GUARD = 'morphhdl/scripts/check-parameterized-integration-source.py'
SEAL = 'morphhdl/contracts/parameterized-integration-source.json'
CI = 'morphhdl/scripts/candidate_provenance_ci.py'
MIGRATION = 'morphhdl/contracts/candidate-provenance-workflow-migration.json'
POLICY_ID = 'current-head-authenticated-provenance-2026-10-06'
HISTORICAL_ROOT = '9c88f5f75921e28aff329e3cff2d40132315e861'
SUCCESSOR_CONTRACT = 'morphhdl/contracts/increment-59i-production-successor.json'
SUCCESSOR_CHECKER = 'morphhdl/scripts/check-increment-59i-production-successor.py'


def require(ok, message):
    if not ok:
        raise RuntimeError('Candidate provenance: ' + message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def parse(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'duplicate JSON field: ' + key)
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def git(root, *arguments):
    return subprocess.check_output(['git', '--no-replace-objects', '--literal-pathspecs',
                                   *arguments], cwd=root, stderr=subprocess.PIPE, timeout=120)


def regular(root, path):
    require(isinstance(path, str) and path and not Path(path).is_absolute() and
            '..' not in Path(path).parts, 'unsafe evidence path')
    file = root / path
    require(file.is_file() and not file.is_symlink(), 'missing or linked file: ' + path)
    return file.read_bytes()


def identity(root):
    return {'commit': git(root, 'rev-parse', 'HEAD').decode().strip(),
            'tree': git(root, 'rev-parse', 'HEAD^{tree}').decode().strip()}


def current_inputs(root):
    require(not git(root, 'status', '--porcelain', '--untracked-files=all').strip(),
            'dirty candidate')
    result = {}
    for path in (SELF, INVENTORY, POLICY, GUARD, SEAL, CI, MIGRATION):
        raw = regular(root, path)
        require(raw == git(root, 'show', 'HEAD:' + path), 'uncommitted input: ' + path)
        result[path] = digest(raw)
    return result


def authenticate_current(root):
    require(not git(root, 'for-each-ref', 'refs/replace').strip(), 'replacement Git objects are not evidence')
    require(git(root, 'rev-parse', '--is-shallow-repository').strip() == b'false', 'incomplete history')
    spec = importlib.util.spec_from_file_location('candidate_source_guard', root / GUARD)
    require(spec is not None and spec.loader is not None, 'missing current source guard')
    guard = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(guard)
    guard.verify(root)


def validate_inventory(value):
    require(set(value) == {'schema', 'policy', 'roots', 'reviewer_paths', 'certificates', 'successor_chain'},
            'unexpected inventory fields')
    require(value['schema'] == 1 and value['policy'] == POLICY_ID, 'inventory policy differs')
    nodes = value['certificates']
    require(isinstance(nodes, list) and nodes, 'empty certificate inventory')
    by_commit = {}
    for node in nodes:
        require(set(node) == {'commit', 'tree', 'parents', 'references', 'evidence'},
                'unexpected certificate fields')
        for oid in (node['commit'], node['tree'], *node['parents'], *node['references']):
            require(isinstance(oid, str) and re.fullmatch('[0-9a-f]{40}', oid),
                    'mutable or invalid object identity')
        require(node['commit'] not in by_commit, 'duplicate certificate')
        require(node['evidence'], 'missing certificate evidence')
        require(len(node['references']) == len(set(node['references'])), 'duplicate reference')
        for path, evidence in node['evidence'].items():
            require(not Path(path).is_absolute() and '..' not in Path(path).parts,
                    'unsafe certificate evidence path')
            require(set(evidence) == {'mode', 'sha256'} and evidence['mode'] in ('100644', '100755')
                    and re.fullmatch('[0-9a-f]{64}', evidence['sha256']), 'invalid evidence identity')
        by_commit[node['commit']] = node
    require(value['roots'] and len(value['roots']) == len(set(value['roots'])), 'invalid roots')
    visited = set()
    pending = list(value['roots'])
    while pending:
        ref = pending.pop()
        if ref in visited:
            continue
        require(ref in by_commit, 'missing referenced certificate: ' + ref)
        visited.add(ref)
        pending.extend(by_commit[ref]['references'])
    require(visited == set(by_commit), 'unreachable certificate')
    chain = value['successor_chain']
    require(isinstance(chain, list) and len(chain) == len(set(chain)) and
            all(ref in by_commit for ref in chain), 'invalid successor chain inventory')
    return by_commit


def authenticate_successor_chain(nodes, raw_evidence, chain):
    """Interpret seal links as data, never by importing predecessor programs."""
    def helper_hash(raw):
        pattern = rb'^CONTRACT_SHA256 = "([^"\n]+)"$'
        slots = re.findall(pattern, raw, re.M)
        require(len(slots) == 1, 'ambiguous historical checker seal')
        normalized = re.sub(pattern, b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)
        return slots[0].decode(), digest(normalized)
    for index, ref in enumerate(chain):
        raw = raw_evidence[(ref, SUCCESSOR_CONTRACT)]
        value = parse(raw)
        embedded, normalized = helper_hash(raw_evidence[(ref, SUCCESSOR_CHECKER)])
        require(embedded == digest(raw) and normalized == value['helper_normalized_sha256'],
                'historical checker/manifest seal mismatch: ' + ref)
        require(nodes[ref]['parents'] == [value['source_commit']], 'seal/source topology differs: ' + ref)
        for commit, tree in ((value['source_commit'], value['source_tree']),
                             (value['predecessor'], value['predecessor_tree'])):
            require(commit in nodes and nodes[commit]['tree'] == tree,
                    'historical source/predecessor tree differs: ' + ref)
        integration = value.get('target_integration')
        if integration:
            for commit, tree in ((integration['target_commit'], integration['target_tree']),
                                 (integration['common_base'], integration['common_base_tree'])):
                require(commit in nodes and nodes[commit]['tree'] == tree,
                        'historical integration tree differs: ' + ref)
        previous = value.get('previous_seal')
        if previous:
            parent = previous['seal_commit']
            require(index + 1 < len(chain) and chain[index + 1] == parent,
                    'missing or incorrect previous seal link: ' + ref)
            require(nodes[parent]['tree'] == previous['seal_tree'] and
                    nodes[parent]['parents'] == [previous['source_commit']],
                    'previous certificate topology differs: ' + ref)
            require(digest(raw_evidence[(parent, SUCCESSOR_CONTRACT)]) == previous['contract_sha256'] and
                    helper_hash(raw_evidence[(parent, SUCCESSOR_CHECKER)])[1] == previous['helper_normalized_sha256'],
                    'previous certificate evidence differs: ' + ref)
        else:
            require(index == len(chain) - 1, 'unexpected certificates after terminal seal')


def authenticate_history(root, inventory, candidate):
    nodes = validate_inventory(inventory)
    checked = []
    chain = set(inventory['successor_chain'])
    raw_evidence = {}
    # Each distinct certificate is visited exactly once. No checkout, import or
    # execution of any historical program occurs, including retired wire proofs.
    for ref, node in sorted(nodes.items()):
        require(git(root, 'cat-file', '-t', ref).strip() == b'commit', 'not a certificate commit')
        require(git(root, 'rev-parse', ref + '^{tree}').decode().strip() == node['tree'],
                'certificate tree differs: ' + ref)
        require(git(root, 'show', '-s', '--format=%P', ref).decode().split() == node['parents'],
                'certificate parents differ: ' + ref)
        git(root, 'merge-base', '--is-ancestor', ref, candidate['commit'])
        entries = {}
        for row in git(root, 'ls-tree', '-r', '-z', ref).split(b'\0'):
            if not row:
                continue
            meta, path = row.split(b'\t', 1)
            mode, kind, oid = meta.decode().split()
            entries[path.decode()] = (mode, kind, oid)
        for path, expected in node['evidence'].items():
            entry = entries.get(path)
            require(entry is not None and entry[:2] == (expected['mode'], 'blob'),
                    'missing or invalid historical evidence: ' + ref + ':' + path)
            raw = git(root, 'cat-file', 'blob', entry[2])
            if ref in chain and path in (SUCCESSOR_CONTRACT, SUCCESSOR_CHECKER):
                raw_evidence[(ref, path)] = raw
            require(digest(raw) == expected['sha256'],
                    'historical evidence hash differs: ' + ref + ':' + path)
        checked.append({'commit': ref, 'tree': node['tree'],
                        'evidence_sha256': digest(canonical(node)),
                        'evidence_files': len(node['evidence'])})
    authenticate_successor_chain(nodes, raw_evidence, inventory['successor_chain'])
    return checked


def produce(root, destination):
    require(not destination.exists() and not destination.is_symlink(), 'receipt already exists')
    started = time.time_ns()
    before = identity(root)
    inputs = current_inputs(root)
    authenticate_current(root)
    inventory = parse(regular(root, INVENTORY))
    require(inventory['successor_chain'] and inventory['successor_chain'][0] == HISTORICAL_ROOT
            if HISTORICAL_ROOT is not None else not inventory['successor_chain'], 'required successor root missing')
    certificates = authenticate_history(root, inventory, before)
    require(identity(root) == before and current_inputs(root) == inputs, 'candidate moved during audit')
    record = {'schema': 1, 'policy': POLICY_ID, 'status': 'pass', 'candidate': before,
              'inputs': inputs, 'certificates': certificates, 'successor_chain': inventory['successor_chain'],
              'historical_programs_executed': 0, 'rtl_qualification': False,
              'started_ns': started, 'finished_ns': time.time_ns()}
    raw = canonical(record)
    require(not destination.exists() and not destination.is_symlink(), 'receipt already exists')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open('xb') as output:
        output.write(raw)
    print('CANDIDATE_PROVENANCE_PASS receipt_sha256=' + digest(raw))
    return digest(raw)


def consume(root, receipt, expected_digest):
    require(isinstance(expected_digest, str) and re.fullmatch('[0-9a-f]{64}', expected_digest),
            'missing authenticated producer digest')
    require(receipt.is_file() and not receipt.is_symlink(), 'missing or linked receipt')
    raw = receipt.read_bytes()
    require(digest(raw) == expected_digest, 'altered receipt')
    value = parse(raw)
    require(value.get('schema') == 1 and value.get('policy') == POLICY_ID and
            value.get('status') == 'pass', 'missing successful integrity result')
    require(value.get('candidate') == identity(root), 'stale candidate receipt')
    require(value.get('inputs') == current_inputs(root), 'stale policy, checker or manifest')
    inventory = parse(regular(root, INVENTORY))
    nodes = validate_inventory(inventory)
    require(value.get('successor_chain') == inventory['successor_chain'], 'inconsistent successor result')
    expected = [{'commit': ref, 'tree': node['tree'], 'evidence_sha256': digest(canonical(node)),
                 'evidence_files': len(node['evidence'])} for ref, node in sorted(nodes.items())]
    require(value.get('certificates') == expected, 'missing or inconsistent certificate result')
    require(value.get('historical_programs_executed') == 0 and value.get('rtl_qualification') is False,
            'invalid evidence scope')
    print('CANDIDATE_PROVENANCE_CONSUMED ' + value['candidate']['commit'])
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--produce', type=Path)
    mode.add_argument('--consume', type=Path)
    parser.add_argument('--expected-sha256')
    args = parser.parse_args()
    if args.produce:
        require(args.expected_sha256 is None, 'producer cannot take a consumer digest')
        produce(ROOT, args.produce)
    else:
        consume(ROOT, args.consume, args.expected_sha256)


if __name__ == '__main__':
    main()
