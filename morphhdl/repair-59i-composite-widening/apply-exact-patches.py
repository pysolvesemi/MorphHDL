#!/usr/bin/env python3
"""Apply the 59i development patches by exact source text, not fuzzy offsets.

Each patch in this directory changes one file. Existing-file hunks must have one
and only one exact old-text occurrence in the current source. New-file hunks
must contain no old text and may not replace an existing path. This preserves
the source-bound review property while avoiding unified-diff line-count drift.
"""
from __future__ import annotations

import argparse
from pathlib import Path


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def repository_path(header: str) -> str | None:
    value = header.split(None, 1)[1].strip().split('\t', 1)[0]
    if value == '/dev/null':
        return None
    require(value.startswith(('a/', 'b/')), 'invalid patch path: ' + value)
    result = value[2:]
    require(result and not result.startswith('/') and '..' not in Path(result).parts,
            'unsafe patch path: ' + result)
    return result


def parse(patch: Path) -> tuple[str | None, str | None, list[tuple[str, str]]]:
    lines = patch.read_text().splitlines(keepends=True)
    old_header = next((line for line in lines if line.startswith('--- ')), None)
    new_header = next((line for line in lines if line.startswith('+++ ')), None)
    require(old_header is not None and new_header is not None,
            f'{patch}: missing file headers')
    old_path, new_path = repository_path(old_header), repository_path(new_header)
    require(old_path is not None or new_path is not None, f'{patch}: deletes nothing')
    require(old_path is None or new_path is None or old_path == new_path,
            f'{patch}: renames are not admitted')

    hunks: list[tuple[str, str]] = []
    index = 0
    while index < len(lines):
        if not lines[index].startswith('@@ '):
            index += 1
            continue
        index += 1
        old: list[str] = []
        new: list[str] = []
        while index < len(lines) and not lines[index].startswith('@@ '):
            line = lines[index]
            if line.startswith('diff --git '):
                break
            if line.startswith('\\ No newline at end of file'):
                index += 1
                continue
            require(line and line[0] in ' +-',
                    f'{patch}: invalid hunk line: {line!r}')
            if line[0] in ' -':
                old.append(line[1:])
            if line[0] in ' +':
                new.append(line[1:])
            index += 1
        hunks.append((''.join(old), ''.join(new)))
    require(hunks, f'{patch}: no hunks')
    return old_path, new_path, hunks


def apply(root: Path, patch: Path) -> str:
    old_path, new_path, hunks = parse(patch)
    relative = new_path or old_path
    require(relative is not None, f'{patch}: missing destination')
    destination = root / relative
    destination.resolve().relative_to(root.resolve())

    if old_path is None:
        require(not destination.exists(), f'{patch}: new path already exists: {relative}')
        require(all(not old for old, _ in hunks),
                f'{patch}: new-file hunk unexpectedly removes source')
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(''.join(new for _, new in hunks))
        return relative

    require(destination.is_file(), f'{patch}: source path is missing: {relative}')
    text = destination.read_text()
    for ordinal, (old, new) in enumerate(hunks, 1):
        require(old, f'{patch}: existing-file hunk {ordinal} has no source anchor')
        occurrences = text.count(old)
        require(occurrences == 1,
                f'{patch}: hunk {ordinal} expected one exact source occurrence, found {occurrences}')
        text = text.replace(old, new, 1)
    destination.write_text(text)
    return relative


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('patches', nargs='+', type=Path)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    args = parser.parse_args()
    root = args.root.resolve()
    changed: set[str] = set()
    for patch in args.patches:
        relative = apply(root, patch.resolve())
        require(relative not in changed, 'multiple prototype patches target ' + relative)
        changed.add(relative)
        print('applied exact source patch:', relative)
    require(len(changed) == len(args.patches), 'patch/file cardinality changed')


if __name__ == '__main__':
    main()
