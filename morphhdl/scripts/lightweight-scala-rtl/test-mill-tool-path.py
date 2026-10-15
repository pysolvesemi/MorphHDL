#!/usr/bin/env python3
"""Exercise tool-PATH changes against the same cached Mill module graph."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mill', required=True)
    parser.add_argument('--scala', choices=['2.12.18', '2.13.12'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    receipts = []
    with tempfile.TemporaryDirectory(prefix='mill-tool-path-') as temporary:
        markers = [str(Path(temporary) / name) for name in ('first', 'second')]
        for marker in markers:
            Path(marker).mkdir()
        for index, marker in enumerate((markers[0], markers[1], markers[0])):
            supplied = marker + os.pathsep + os.environ['PATH']
            for module in ('morph', 'morph.test'):
                selector = 'morph[' + args.scala + ']' + ('.test' if module.endswith('.test') else '')
                command = [args.mill, '--no-server', 'show', selector + '.allForkEnv']
                result = subprocess.run(command, cwd=ROOT, env={**os.environ, 'PATH': supplied},
                                        capture_output=True, timeout=180)
                stem = str(index) + '-' + module
                (args.output / (stem + '.stdout')).write_bytes(result.stdout)
                (args.output / (stem + '.stderr')).write_bytes(result.stderr)
                if result.returncode:
                    raise RuntimeError('Mill environment query failed: ' + stem)
                actual = json.loads(result.stdout)['PATH']
                # JDK bin remains first; the caller's complete PATH follows it.
                java_bin, inherited = actual.split(os.pathsep, 1)
                if not Path(java_bin, 'java').is_file() or inherited != supplied:
                    raise RuntimeError('cached or malformed fork PATH: ' + stem)
                receipts.append({'command': command, 'supplied_path': supplied,
                                 'actual_path': actual,
                                 'stdout_sha256': hashlib.sha256(result.stdout).hexdigest()})
    (args.output / 'results.json').write_text(json.dumps(
        {'status': 'pass', 'scala': args.scala, 'checks': receipts}, indent=2) + '\n')
    print('Passed A/B/A tool-path cache regression for outer and test modules')


if __name__ == '__main__':
    main()
