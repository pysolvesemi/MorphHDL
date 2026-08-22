#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import subprocess
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument('--pinned-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('logs', type=Path, nargs='+')
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    cp_dir = args.output / 'cp'
    cp_dir.mkdir(parents=True, exist_ok=True)

    entries: list[Path] = []
    for log in args.logs:
        text = log.read_text(errors='replace')
        found = [Path(value) for value in re.findall(r'Attributed\(([^)]*)\)', text)]
        if not found:
            raise SystemExit(f'No classpath entries parsed from {log}')
        entries.extend(found)

    unique: list[Path] = []
    seen: set[str] = set()
    for entry in entries:
        entry = entry.resolve()
        key = str(entry)
        if key in seen:
            continue
        seen.add(key)
        if not entry.exists():
            raise SystemExit(f'Classpath entry is missing: {entry}')
        unique.append(entry)

    manifest: list[str] = []
    for index, entry in enumerate(unique):
        destination = cp_dir / f'cp{index:04d}.jar'
        if entry.is_dir():
            subprocess.run(
                ['jar', '--create', '--file', str(destination), '-C', str(entry), '.'],
                check=True,
            )
        elif entry.is_file():
            shutil.copy2(entry, destination)
        else:
            raise SystemExit(f'Unsupported classpath entry: {entry}')
        manifest.append(f'cp/{destination.name}')

    (args.output / 'classpath.txt').write_text('\n'.join(manifest) + '\n')
    plugins = sorted(
        path for path in (args.pinned_root / 'idslplugin/target').rglob('*.jar')
        if 'sources' not in path.name and 'javadoc' not in path.name
        and 'idsl-plugin' in path.name.lower()
    )
    if len(plugins) != 1:
        raise SystemExit(f'Expected one IDSL plugin package jar, found: {plugins!r}')
    shutil.copy2(plugins[0], args.output / 'idslplugin.jar')

    hashes: list[str] = []
    for path in sorted(cp_dir.glob('*.jar')) + [args.output / 'idslplugin.jar']:
        hashes.append(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(args.output)}')
    (args.output / 'metadata/classpath.sha256').write_text('\n'.join(hashes) + '\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
