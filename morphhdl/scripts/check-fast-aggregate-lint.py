#!/usr/bin/env python3
"""Fast aggregate RTL lint; fresh elaboration by default, no simulation or synthesis.

Run before broad qualification. Nonzero Verilator results (including warnings)
fail the gate. --rtl permits diagnostic lint of an existing fixture corpus;
its provenance must be assessed separately from fresh source qualification.
"""
import argparse
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--scala', choices=['2.12.18', '2.13.12'], default='2.12.18')
    parser.add_argument('--rtl', type=Path, help='Diagnostic mode: already generated corpus')
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    start = time.monotonic()
    git = lambda *a: subprocess.check_output(['git', *a], cwd=ROOT, text=True).strip()
    def changed_sources():
        paths = set(git('diff', '--name-only', 'HEAD').splitlines()) | set(git('ls-files', '--others', '--exclude-standard').splitlines())
        return {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() if (ROOT / name).is_file() else None
                for name in sorted(paths)}
    source_hashes = changed_sources()
    record = {'changed_source_sha256': source_hashes, 'head': git('rev-parse', 'HEAD'), 'tree': git('rev-parse', 'HEAD^{tree}'),
              'dirty': git('status', '--porcelain'), 'scala': args.scala,
              'mode': 'existing-RTL-diagnostic' if args.rtl else 'fresh-elaboration',
              'verilator': subprocess.check_output(['verilator', '--version'], text=True).strip(),
              'results': []}
    for relative in ['morphhdl/src/test/scala/morphhdl/FastAggregateLintFixture.scala',
                     'morphhdl/scripts/check-fast-aggregate-lint.py']:
        record.setdefault('gate_source_sha256', {})[relative] = hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()
    rtl = args.rtl.resolve() if args.rtl else out / 'rtl'
    if not args.rtl:
        command = ['sbt', '-batch', '++' + args.scala,
                   'morph/Test/runMain morphhdl.FastAggregateLintFixture ' + str(rtl)]
        record['generation_command'] = command
        with (out / 'generation.log').open('w') as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        record['generation_exit'] = result.returncode
        if result.returncode:
            (out / 'receipt.json').write_text(json.dumps(record, indent=2) + '\n')
            return result.returncode
    expected = {f'depth-{d}-nested-{n}-generate-{g}-unpacked-{u}' for d in (1, 3)
                for n in ('false', 'true') for g in (('false',) if n == 'true' else ('false', 'true')) for u in ('false', 'true')}
    expected |= {'derived-ports-preserve-false', 'derived-ports-preserve-true'}
    expected |= {'native-mask-width-' + str(w) for w in (4, 8, 32)}
    cases = (rtl / 'cases.txt').read_text().splitlines()
    if len(cases) != len(expected) or set(cases) != expected:
        raise RuntimeError('Missing, extra or duplicate lint fixture cases')
    for name in cases:
        source = rtl / name / 'Top.v'
        dest = out / name
        dest.mkdir()
        raw = source.read_bytes()
        (dest / 'Top.v').write_bytes(raw)
        command = ['verilator', '--lint-only', '-Wall', '--language', '1364-2001',
                   '--top-module', 'Top', 'Top.v']
        if name.startswith('native-mask-width-'):
            command.append('-GBUS_BYTES=' + name.rsplit('-', 1)[1])
        result = subprocess.run(command, cwd=dest, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=60)
        (dest / 'lint.log').write_text(result.stdout)
        record['results'].append({'case': name, 'command': command, 'exit': result.returncode,
                                  'rtl_sha256': hashlib.sha256(raw).hexdigest(),
                                  'warnings': sorted(set(re.findall(r'%Warning-([A-Z0-9_]+)', result.stdout)))})
    record['seconds'] = round(time.monotonic() - start, 2)
    record['passed'] = all(r['exit'] == 0 for r in record['results'])
    record['source_unchanged'] = git('rev-parse', 'HEAD') == record['head'] and git('status', '--porcelain') == record['dirty'] and changed_sources() == source_hashes
    (out / 'receipt.json').write_text(json.dumps(record, indent=2) + '\n')
    print(f"{sum(r['exit'] == 0 for r in record['results'])}/{len(cases)} clean; receipt: {out / 'receipt.json'}")
    return 0 if record['passed'] and record['source_unchanged'] else 1

if __name__ == '__main__':
    raise SystemExit(main())
