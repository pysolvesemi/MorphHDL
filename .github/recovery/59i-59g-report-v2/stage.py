#!/usr/bin/env python3
"""Stage the exact 59g report repair; dispatch only its still-failed workflow.

Reuse the content-addressed stager which previously staged ddf61ef2. Neither
this adapter nor the original stager mutates PR/target refs or dispatches full
CI. The connector must separately publish the exact validation ref.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import importlib.util
from pathlib import Path
import sys

ORIGINAL = '.github/recovery/59i-failed-first-v2/stage.py'
ORIGINAL_BLOB = '9d087c67ccae7a5594c554a9e89637ce81059e50'
PARTS = ('part-01.b64', 'part-02.b64', 'part-03-0.b64', 'part-03-1.b64', 'part-03-2.b64')
BUNDLE_SHA = 'd9b729f92635a25c1a58453a3a5006dc6f0efbdfe1d84e6326eeeccafac0c10a'


def require(ok: bool, message: str) -> None:
    if not ok:
        raise RuntimeError('59g report transport: ' + message)


def checked_file(path: Path) -> bytes:
    require(path.is_file() and not path.is_symlink(), 'expected a regular input: ' + str(path))
    return path.read_bytes()


def bundle_bytes(payload: Path) -> bytes:
    raw = base64.b64decode(b''.join(checked_file(payload / name) for name in PARTS), validate=True)
    require(hashlib.sha256(raw).hexdigest() == BUNDLE_SHA, 'bundle identity differs')
    return raw


def configure(module) -> None:
    module.PARENT = 'ddf61ef25f927d646027cebbcaca6d724ad8a5fa'
    module.TARGET = '27af65abbee0d2334d6be7a6e4e2408b8af32fd9'
    module.SOURCE = '106fa49a30e3b8513a6b6038b929250305360f8a'
    module.SEAL = 'c74b34bb1154d1df20bf85aa63e1276388511a02'
    module.SOURCE_TREE = 'b9319d7bbd2793d38c37a45ceb68d62372700566'
    module.SEAL_TREE = '3e29a314d85c6f708fdd78eaeb68098ff136510c'
    module.BRANCH = 'recovery/increment-59i-59g-report-c74b34bb'
    module.BUNDLE_SHA = BUNDLE_SHA
    module.BUNDLE_REF = 'refs/heads/local/59i-failed-first-report-v2'
    # The original publisher rechecks the failed result, workflow ID, original
    # head and exact new ref, and avoids a duplicate dispatch on an unchanged SHA.
    module.SELECTED = {351947632: 35257977113}
    module.CHECKS = list(module.CHECKS) + ['test-increment-59g-report-inventory.py']
    # Correct only the legacy stager's fixed-count terminal message. Source
    # checks, immutable object staging, receipts and dispatch logic are untouched.
    def report(*args, **kwargs):
        old = 'Exact sealed repair published; only five previously failed workflows dispatched'
        if args == (old,):
            args = ('Exact sealed report repair staged; only the still-failed 59g workflow dispatched',)
        print(*args, **kwargs)
    module.print = report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('prepare', 'publish'))
    for name in ('repository', 'destination', 'output', 'bundle'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    path = args.repository.resolve() / ORIGINAL
    raw = checked_file(path)
    oid = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
    require(oid == ORIGINAL_BLOB, 'original staging implementation differs')
    spec = importlib.util.spec_from_file_location('pinned_failed_first_stager', path)
    require(spec is not None and spec.loader is not None, 'cannot load pinned stager')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    configure(module)
    payload = Path(__file__).resolve().parent
    bundle = args.bundle.resolve()
    expected = bundle_bytes(payload)
    if args.phase == 'prepare':
        require(not bundle.exists(), 'refusing to overwrite bundle')
        bundle.write_bytes(expected)
    else:
        require(checked_file(bundle) == expected, 'prepared bundle changed')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    if args.phase == 'prepare':
        module.prepare(args.repository.resolve(), args.destination.resolve(), out, bundle)
    else:
        module.publish(args.destination.resolve(), out)


if __name__ == '__main__':
    main()
