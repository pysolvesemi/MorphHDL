#!/usr/bin/env python3
"""Build the authenticated simulator required by singleton unpacked-array tests.

Install only into a fresh isolated prefix. The receipt authenticates the complete
installed tree; it is not a claim that compiler/RTL qualification has passed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

COMMIT = 'af348e2a468d3e58bf2aa10a5ae97734b59e48ea'
TREE = '67bc8b44e363d4eaf0babf9e6ec1765ae976ea39'
REPOSITORY = 'https://github.com/steveicarus/iverilog'


def run(arguments, directory):
    print('+', json.dumps(arguments), flush=True)
    subprocess.run(arguments, cwd=directory, check=True, timeout=1200)


def inventory(prefix):
    result = {}
    for path in sorted(prefix.rglob('*')):
        relative = str(path.relative_to(prefix))
        if relative == 'build-receipt.json':
            continue
        if path.is_symlink():
            if prefix not in path.resolve().parents:
                raise RuntimeError('installed symlink escapes prefix: ' + relative)
            result[relative] = {'link': os.readlink(path)}
        elif path.is_file():
            result[relative] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                                'mode': path.stat().st_mode & 0o777}
    return result


def verify(prefix):
    receipt = json.loads((prefix / 'build-receipt.json').read_text())
    if (receipt['commit'], receipt['tree'], receipt['repository']) != (COMMIT, TREE, REPOSITORY):
        raise RuntimeError('simulator source identity changed')
    if receipt['files'] != inventory(prefix):
        raise RuntimeError('installed simulator inventory changed')
    print('Verified pinned simulator source and installed file inventory')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('prefix', type=Path)
    parser.add_argument('--source', default=REPOSITORY,
                        help='optional local Git mirror; exact commit/tree still required')
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    prefix = args.prefix.resolve()
    if args.verify:
        verify(prefix)
        return
    if prefix.exists():
        raise RuntimeError('use a fresh prefix, or --verify to check an existing installation')
    with tempfile.TemporaryDirectory(prefix='morphhdl-iverilog-') as temporary:
        directory = Path(temporary)
        run(['git', 'clone', '--no-checkout', args.source, 'source'], directory)
        source = directory / 'source'
        run(['git', 'checkout', '--detach', COMMIT], source)
        observed = subprocess.check_output(['git', 'rev-parse', 'HEAD', 'HEAD^{tree}'], cwd=source, text=True).splitlines()
        if observed != [COMMIT, TREE] or subprocess.check_output(['git', 'status', '--porcelain'], cwd=source):
            raise RuntimeError('unverified or dirty simulator source')
        run(['sh', 'autoconf.sh'], source)
        run(['./configure', '--prefix=' + str(prefix)], source)
        run(['make', '-j2'], source)
        run(['make', 'install'], source)
        for executable in ('iverilog', 'vvp'):
            run([str(prefix / 'bin' / executable), '-V'], source)
        receipt = {'schema': 1, 'repository': REPOSITORY, 'commit': COMMIT, 'tree': TREE,
                   'files': inventory(prefix)}
        (prefix / 'build-receipt.json').write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
        verify(prefix)


if __name__ == '__main__':
    main()
