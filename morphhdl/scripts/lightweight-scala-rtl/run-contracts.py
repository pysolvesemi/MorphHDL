#!/usr/bin/env python3
"""Run the closed lightweight suite set and reject missing/empty/skipped reports."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
MANIFEST = Path(__file__).with_name('suites.json')


def reports(paths, expected):
    found = {}
    for path in paths:
        if not path.is_file() or path.is_symlink():
            raise RuntimeError('missing or linked report: ' + str(path))
        raw = path.read_bytes()
        if b'<!DOCTYPE' in raw or b'<!ENTITY' in raw:
            raise RuntimeError('report contains XML declarations')
        tree = ET.fromstring(raw)
        for suite in ([tree] if tree.tag == 'testsuite' else tree.findall('testsuite')):
            name = suite.attrib['name']
            if name not in expected or name in found:
                raise RuntimeError('unexpected or duplicate suite: ' + name)
            cases = suite.findall('testcase')
            if not cases or int(suite.attrib['tests']) != len(cases):
                raise RuntimeError('missing test cases: ' + name)
            if any(int(suite.attrib.get(key, '0')) for key in ('failures', 'errors', 'skipped')) or any(
                    case.find(tag) is not None for case in cases for tag in ('failure', 'error', 'skipped')):
                raise RuntimeError('nonpassing suite: ' + name)
            if any(case.get('classname') != name for case in cases):
                raise RuntimeError('test case belongs to another suite: ' + name)
            identities = [case.attrib['name'] for case in cases]
            if len(set(identities)) != len(identities):
                raise RuntimeError('duplicate test case: ' + name)
            if sorted(identities) != sorted(expected[name]):
                raise RuntimeError('test case inventory differs: ' + name)
            found[name] = identities
    if set(found) != set(expected):
        raise RuntimeError('missing suites: ' + repr(sorted(set(expected) - set(found))))
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', choices=['sbt', 'mill'], required=True)
    parser.add_argument('--scala', choices=['2.12.18', '2.13.12'], required=True)
    parser.add_argument('--executable', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT):
        raise RuntimeError('qualification requires a clean committed candidate')
    guard_path = ROOT / 'morphhdl/scripts/check-retained-parameterized-regressions.py'
    spec = importlib.util.spec_from_file_location('retained_catalog', guard_path)
    guard = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(guard)
    catalog = guard.catalog(ROOT)
    manifest = json.loads(MANIFEST.read_text())
    sources = [entry['source'] for entry in manifest['entries']]
    if len(set(sources)) != len(sources) or not all((ROOT / path).is_file() for path in sources):
        raise RuntimeError('invalid source enrollment')
    required = {str(p.relative_to(ROOT)) for p in (ROOT / 'morphhdl/src/test/scala').rglob('Lightweight*Tests.scala')}
    required |= {str(p.relative_to(ROOT)) for p in (ROOT / 'morphplugin/src/test/scala').rglob('*Tests.scala')}
    if not required.issubset(sources):
        raise RuntimeError('unenrolled lightweight/plugin test sources: ' + repr(sorted(required - set(sources))))
    args.output.mkdir(parents=True, exist_ok=False)
    receipt = {'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
               'tree': subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], cwd=ROOT, text=True).strip(),
               'manifest_sha256': hashlib.sha256(MANIFEST.read_bytes()).hexdigest(),
               'catalog_sha256': guard.CONTRACT_SHA256,
               'build': args.build, 'scala': args.scala, 'modules': {}}
    receipt['tools'] = {}
    for name, flag in (('java', '-version'), ('iverilog', '-V'), ('vvp', '-V'),
                       ('verilator', '--version'), ('yosys', '-V')):
        executable = shutil.which(name)
        if executable is None:
            raise RuntimeError('required tool unavailable: ' + name)
        version = subprocess.run([executable, flag], stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, timeout=30, check=True).stdout
        (args.output / (name + '-version.log')).write_bytes(version)
        receipt['tools'][name] = {'path': executable,
            'executable_sha256': hashlib.sha256(Path(executable).read_bytes()).hexdigest(),
            'version_sha256': hashlib.sha256(version).hexdigest()}
    for module in ('morphplugin', 'morph'):
        suites = sorted(suite for entry in manifest['entries'] if entry['module'] == module for suite in entry['suites'])
        if len(suites) != len(set(suites)) or not suites:
            raise RuntimeError('invalid suite enrollment for ' + module)
        if args.build == 'sbt':
            directory = ROOT / ('morphhdl' if module == 'morph' else module) / 'target/test-reports'
            paths = [directory / ('TEST-' + suite + '.xml') for suite in suites]
            command = [args.executable, '-batch', '++' + args.scala, module + '/testOnly ' + ' '.join(suites)]
        else:
            paths = [ROOT / 'out' / module / args.scala / 'test/testOnly.dest/test-report.xml']
            command = [args.executable, '--no-server', '-j', '2', module + '[' + args.scala + '].testOnly', *suites]
        for path in paths:
            path.unlink(missing_ok=True)
        log = args.output / (module + '.log')
        with log.open('w') as stream:
            result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=3300)
        if result.returncode:
            raise RuntimeError('test command failed; inspect ' + str(log))
        project = 'morphhdl' if module == 'morph' else module
        expected = {suite: catalog['projects'][project][suite] for suite in suites}
        found = reports(paths, expected)
        receipt['modules'][module] = {'command': command, 'suites': found,
                                     'log_sha256': hashlib.sha256(log.read_bytes()).hexdigest()}
        for index, path in enumerate(paths):
            (args.output / (module + '-' + str(index) + '.xml')).write_bytes(path.read_bytes())
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT) or any(
            subprocess.check_output(['git', 'rev-parse', ref], cwd=ROOT, text=True).strip() != receipt[key]
            for key, ref in (('commit', 'HEAD'), ('tree', 'HEAD^{tree}'))):
        raise RuntimeError('candidate changed during qualification')
    (args.output / 'results.json').write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    print('All enrolled suites ran without missing, empty or skipped cases')


if __name__ == '__main__':
    main()
