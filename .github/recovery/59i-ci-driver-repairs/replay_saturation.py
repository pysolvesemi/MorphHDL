#!/usr/bin/env python3
"""Replay saturation hardware gates from exact retained compiler artifacts.

No Scala recompilation, source edit, remote ref update, or final-head claim.
The only proof-mode repair is -falsify for the deliberately broken RTL.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import stat
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

HEAD = 'f43100e4899593eb5c9e78537a4dcf6f53f9c30f'
TREE = '44a48e034fc910506620f0125f2dd417500a89a1'
REPO = 'pysolvesemi/MorphHDL'
INPUT_RUN = 34998796689
INPUT_WORKFLOW_HEAD = '57496fa74867cfc8f725181443c1aba0d4507399'
ARTIFACTS = {
    '2.12.18': (10408844076, 'aebe289ce8e0288876643b6680bc44d65d471edc8868bd1dba59124da1a66ce2', 3704240, '59i-saturation-driver-first-2.12.zip'),
    '2.13.12': (10409985809, 'b21a3772bdd309409b23cc254e5943977294375a323ce73cbf4e6278fd293beb', 3705967, '59i-saturation-driver-first-2.13.zip'),
}
GENERATED = {
    'generated/packed/BalancedSaturatingReduction_packed.v',
    'generated/fields/BalancedSaturatingReduction_fields.v',
    'generated/constant-width/packed/BalancedSaturatingFixedWidth_packed.v',
    'generated/constant-width/fields/BalancedSaturatingFixedWidth_fields.v',
}
SAT_COMMAND = 'sat -prove bad 0 -verify -show-inputs -show-outputs -timeout 120'


def require(ok: bool, message: str) -> None:
    if not ok:
        raise RuntimeError(message)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.PIPE, timeout=120)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def download(scala: str, destination: Path) -> None:
    aid, sha, size, _ = ARTIFACTS[scala]
    token = os.environ.get('GH_TOKEN')
    require(bool(token), 'Artifact download requires the read-only Actions token')
    base = 'https://api.github.com/repos/' + REPO + '/actions/artifacts/' + str(aid)
    headers = {'Authorization': 'Bearer ' + token,
               'Accept': 'application/vnd.github+json', 'User-Agent': 'MorphHDL-saturation-replay'}
    with urllib.request.urlopen(urllib.request.Request(base, headers=headers), timeout=120) as response:
        metadata = json.load(response)
    require(metadata['id'] == aid and metadata['size_in_bytes'] == size and not metadata['expired'],
            'Artifact identity or size changed')
    require(metadata['digest'] == 'sha256:' + sha and metadata['workflow_run']['id'] == INPUT_RUN and
            metadata['workflow_run']['head_sha'] == INPUT_WORKFLOW_HEAD, 'Artifact provenance changed')
    opener = urllib.request.build_opener(NoRedirect())
    try:
        response = opener.open(urllib.request.Request(base + '/zip', headers=headers), timeout=120)
    except urllib.error.HTTPError as error:
        require(error.code in (301, 302, 303, 307), 'Artifact archive request failed: HTTP ' + str(error.code))
        location = error.headers.get('Location', '')
        require(urllib.parse.urlsplit(location).scheme == 'https', 'Invalid artifact download redirect')
        # Explicitly do not forward the GitHub Authorization header to blob storage.
        response = urllib.request.urlopen(urllib.request.Request(location), timeout=120)
    with response, destination.open('wb') as output:
        total = 0
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            require(total <= size, 'Archive exceeds pinned size')
            output.write(chunk)
    require(total == size and digest(destination.read_bytes()) == sha, 'Archive checksum changed')


def validate_archive(scala: str, archive: Path, destination: Path, source: Path) -> dict:
    aid, sha, size, _ = ARTIFACTS[scala]
    raw = archive.read_bytes()
    require(len(raw) == size and digest(raw) == sha, 'Archive checksum or size differs')
    destination.mkdir(parents=True, exist_ok=False)
    members = {}
    with zipfile.ZipFile(archive) as data:
        seen = set()
        for item in data.infolist():
            name = Path(item.filename)
            require(not name.is_absolute() and '..' not in name.parts and '\\' not in item.filename and
                    not stat.S_ISLNK(item.external_attr >> 16) and item.filename not in seen,
                    'Unsafe or duplicate artifact member')
            seen.add(item.filename)
            if item.is_dir() or name.suffix not in ('.v', '.ys', '.log', '.txt', '.xml'):
                continue
            content = data.read(item)
            output = destination / name
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(content)
            members[item.filename] = digest(content)
    require((destination / 'head.txt').read_text().strip() == HEAD and
            (destination / 'tree.txt').read_text().strip() == TREE, 'Wrong compiled source identity')
    before = (destination / 'tracked-before.txt').read_bytes()
    require(before == (destination / 'tracked-after.txt').read_bytes() == git(source, 'ls-files', '--stage'),
            'Compiled source index differs from the exact checkout')
    log = (destination / 'tests.log').read_text()
    totals = re.findall(r'Tests: succeeded (\d+), failed (\d+), canceled (\d+), ignored (\d+), pending (\d+)', log)
    require(totals == [('65', '0', '0', '0', '0')], 'Incomplete or unsuccessful retained Scala tests')
    reports = list((destination / 'test-reports').glob('*.xml'))
    require(len(reports) == 5, 'Retained suite inventory differs')
    count = 0
    for report in reports:
        root = ET.parse(report).getroot()
        cases = list(root.iter('testcase'))
        require(len(cases) == int(root.attrib['tests']), 'XML case count differs')
        require(all(int(root.get(key, '0')) == 0 for key in ('failures', 'errors', 'skipped')) and
                all(not any(n.tag in ('failure', 'error', 'skipped') for n in case) for case in cases),
                'Non-passing retained test case')
        count += len(cases)
    require(count == 65, 'Retained test inventory is incomplete')
    generated = {str(p.relative_to(destination)): digest(p.read_bytes())
                 for p in (destination / 'generated').rglob('*.v')}
    require(set(generated) == GENERATED, 'Generated RTL inventory differs')
    simulation = (destination / 'simulation.log').read_text().splitlines()
    require(simulation == ['59I-SATURATION-CYCLES 259', '59I-SATURATION-SIM-PASS'],
            'Original repaired simulation did not finish all 259 checks')
    fields = destination / 'generated/fields/BalancedSaturatingReduction_fields.v'
    changed, changed_count = re.subn(r'(assign\s+result_value\s*=\s*)([^;]+)(;)',
                                   r"\g<1>{WIDTH{1'b0}}\g<3>", fields.read_text(), count=1)
    require(changed_count == 1 and changed.encode() == (destination / 'mutated.v').read_bytes(),
            'Retained mutation is not the exact actual generated result binding mutation')
    lines = (destination / 'prove.ys').read_text().splitlines()
    require(len(lines) == 5 and lines[0].startswith('read_verilog -noautowire ') and
            lines[1:] == ['prep -top miter -flatten', 'opt -full', 'check -assert', SAT_COMMAND],
            'Retained positive proof script changed')
    return {'scala': scala, 'artifact_id': aid, 'archive_sha256': sha,
            'source': HEAD, 'tree': TREE, 'retained_tests_passed': 65,
            'generated': generated, 'input_members': members}


def execute(command: list[str], directory: Path, label: str, expected: str | None, classifier) -> dict:
    start = time.monotonic()
    try:
        result = subprocess.run(command, cwd=directory, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=240, check=False)
    except subprocess.TimeoutExpired as error:
        output = error.stdout or b''
        if isinstance(output, bytes):
            output = output.decode(errors='replace')
        (directory / (label + '.log')).write_text(output + '\nREPLAY_TIMEOUT\n')
        raise RuntimeError(label + ' timed out; not a proof') from None
    (directory / (label + '.log')).write_text(result.stdout)
    record = {'command': command, 'returncode': result.returncode,
              'seconds': round(time.monotonic() - start, 3), 'log_sha256': digest(result.stdout.encode())}
    (directory / (label + '.json')).write_text(json.dumps(record, indent=2) + '\n')
    if expected is not None:
        classifier(result.returncode, result.stdout, expected)
    else:
        require(result.returncode == 0, label + ' returned a nonzero exit status')
    return record


def quote(path: Path) -> str:
    return '"' + str(path).replace('\\', '\\\\').replace('"', '\\"') + '"'


def hardware(directory: Path, scope: dict) -> dict:
    packed = directory / 'generated/packed/BalancedSaturatingReduction_packed.v'
    fields = directory / 'generated/fields/BalancedSaturatingReduction_fields.v'
    miter = directory / 'miter.v'
    check = scope['classify_solver']
    result = {}
    for role, path in [('packed', packed), ('fields', fields)]:
        result[role + '_parse'] = execute(['iverilog', '-g2001', '-Wall', '-tnull', str(path)],
                                         directory, 'replay-' + role + '-parse', None, check)
        script = 'read_verilog -noautowire ' + quote(path) + '; hierarchy -check -top BalancedSaturatingReduction_' + role + '; proc; opt; check -assert; stat'
        result[role + '_synthesis'] = execute(['yosys', '-q', '-p', script], directory,
                                             'replay-' + role + '-synthesis', None, check)
    result['simulation_build'] = execute(['iverilog', '-g2001', '-Wall', '-s', 'tb', '-o',
        str(directory / 'replay.vvp'), str(fields), str(directory / 'tb.v')], directory, 'replay-simulation-build', None, check)
    result['simulation'] = execute(['vvp', str(directory / 'replay.vvp')], directory,
                                   'replay-simulation', None, check)
    text = (directory / 'replay-simulation.log').read_text().splitlines()
    require(text == ['59I-SATURATION-CYCLES 259', '59I-SATURATION-SIM-PASS'], 'Fresh simulation did not complete all 259 checks')
    positive = 'read_verilog -noautowire ' + ' '.join(quote(p) for p in (packed, fields, miter)) + '\n'
    positive += 'prep -top miter -flatten\nopt -full\ncheck -assert\n' + SAT_COMMAND + '\n'
    (directory / 'replay-positive.ys').write_text(positive)
    result['positive_proof'] = execute(['yosys', '-Q', '-T', '-s', str(directory / 'replay-positive.ys')],
                                       directory, 'replay-positive', scope['SAT_PASS'], check)
    mutated = directory / 'mutated.v'
    result['mutant_parse'] = execute(['iverilog', '-g2001', '-Wall', '-tnull', str(mutated)],
                                     directory, 'replay-mutant-parse', None, check)
    negative = positive.replace(quote(fields), quote(mutated), 1).replace(' -verify ', ' -falsify ', 1)
    negative = negative.rstrip('\n') + ' -dump_vcd ' + quote(directory / 'mutation-counterexample.vcd') + '\n'
    (directory / 'replay-negative.ys').write_text(negative)
    result['negative_proof'] = execute(['yosys', '-Q', '-T', '-s', str(directory / 'replay-negative.ys')],
                                       directory, 'replay-negative', scope['SAT_FAIL'], check)
    model = directory / 'mutation-counterexample.vcd'
    require(model.is_file() and model.stat().st_size > 0, 'Mutation lacks the requested actual counterexample')
    result['counterexample_sha256'] = digest(model.read_bytes())
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--zip-dir', type=Path)
    parser.add_argument('--validate-only', action='store_true')
    args = parser.parse_args()
    source, output = args.source.resolve(), args.output.resolve()
    require(git(source, 'rev-parse', 'HEAD').decode().strip() == HEAD and
            git(source, 'rev-parse', 'HEAD^{tree}').decode().strip() == TREE, 'Wrong published checkout')
    require(not git(source, 'status', '--porcelain'), 'Source is dirty before replay')
    output.mkdir(parents=True, exist_ok=False)
    auth = subprocess.run(['python3', '-B', 'morphhdl/scripts/check-increment-59i-production-successor.py'],
                          cwd=source, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300)
    (output / 'source-authentication.log').write_text(auth.stdout)
    require(auth.returncode == 0, 'Published source authentication failed')
    scope = runpy.run_path(str(source / 'morphhdl/scripts/check-increment-60f-equivalence-closure.py'))
    receipt = {'kind': 'artifact-bound saturation hardware replay', 'source': HEAD,
               'source_tree': TREE, 'compiler_run': INPUT_RUN, 'fresh_scala_compilation': False,
               'validate_only': args.validate_only, 'lanes': [], 'completed': False}
    try:
        for scala, (_, _, _, alias) in ARTIFACTS.items():
            archive = (args.zip_dir / alias).resolve() if args.zip_dir else output / alias
            if args.zip_dir is None:
                download(scala, archive)
            directory = output / scala
            lane = validate_archive(scala, archive, directory, source)
            receipt['lanes'].append(lane)
            scope['classify_solver'](0, (directory / 'formal.log').read_text(), scope['SAT_PASS'])
            if not args.validate_only:
                lane['hardware'] = hardware(directory, scope)
            require(lane['generated'] == {str(p.relative_to(directory)): digest(p.read_bytes())
                for p in (directory / 'generated').rglob('*.v')}, 'Replay modified generated RTL')
        require(receipt['lanes'][0]['generated'] == receipt['lanes'][1]['generated'], 'Cross-Scala emitted RTL differs')
        require(not git(source, 'status', '--porcelain') and git(source, 'rev-parse', 'HEAD').decode().strip() == HEAD,
                'Replay changed tracked source')
        receipt['completed'] = True
        print('SATURATION_INPUT_VALIDATION_PASS' if args.validate_only else 'SATURATION_HARDWARE_REPLAY_PASS')
    finally:
        (output / 'replay-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    main()
