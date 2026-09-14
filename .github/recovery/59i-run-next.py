#!/usr/bin/env python3
"""Stage the fixed 61d1fe0d reconciliation using the reviewed transport engine.

Only exact constants/transport metadata change in the engine. All source checks,
blob identities, tree identities and raw commit checks remain mandatory. This
workflow never updates a remote ref or claims final-head qualification.
"""
from __future__ import annotations
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

CONSTANTS = {
    "SOURCE": "214774fbf9579efc7007cb9ac5674583d8bf370c",
    "SEAL": "d3a6c453f315104c55025e3f19a7396ab7d4faeb",
    "SOURCE_TREE": "55d288fd83d1837bb2c793cb0eb8b970276f4e12",
    "SEAL_TREE": "0554493565b31f0bdb0ef9112c8ae6f286b7e38d",
    "TARGET": "61d1fe0dcac0b52856620944a2d7426fd1390a48",
    "ARCHIVED_SOURCE": "2375c64c81f5c19d08ad706634cd3484537e77b3",
    "ARCHIVED_SEAL": "1541077ca85082409a6a870884d9ac8b377acdea",
    "PAYLOAD_SHA256": "22944ea9f64f3d854e346f852998c18c4bbe1285e02db41e14d326c05db455fb",
    "MANIFEST_SHA256": "d6ec9eb8c5083d44d6e4687900c6a8671d7e3f5336907a6d176c6cdb297561db",
}
INPUT_BLOBS = {
    "stage_reviewed_source.py": "47fd5cea8f1e4baef225250b4455f349d8959b16",
    "stage_database.py": "d66ab890e97211b38b25bce2a5349dabadcd1c12",
    "test_stage_database.py": "700757aa81b5764363cbc73a233ca35876404ed3",
}
OUTPUT_BLOBS = {
    "stage_reviewed_source.py": "a5c1a3494ebf44f6fd95c20db376ed5c67e6fcbe",
    "stage_database.py": "8b46db4ceb97cf33bda5f65a2baf81ee7c849ccd",
    "test_stage_database.py": "700757aa81b5764363cbc73a233ca35876404ed3",
}


def require(ok: bool, message: str) -> None:
    if not ok:
        raise RuntimeError("59i exact transport configuration: " + message)


def oid(raw: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()


def configured(source_dir: Path) -> dict[str, bytes]:
    files = {}
    for name, expected in INPUT_BLOBS.items():
        path = source_dir / name
        require(path.is_file() and not path.is_symlink(), "missing regular engine: " + name)
        raw = path.read_bytes()
        require(oid(raw) == expected, "original reviewed engine changed: " + name)
        files[name] = raw
    text = files["stage_reviewed_source.py"].decode()
    for key, value in CONSTANTS.items():
        text, count = re.subn(r'^' + key + r' = "[^"\n]+"$', key + ' = "' + value + '"', text, flags=re.M)
        require(count == 1, "ambiguous transport constant: " + key)
    old = '"Exact reviewed " + prefix + " span for " + path'
    require(text.count(old) == 1, "span-reason format differs")
    text = text.replace(old, '"Exact reviewed " + prefix + " reconciliation: " + path', 1)
    old = 'require(len(parts) == 5, "expected all five reviewed payload parts")'
    require(text.count(old) == 1, "part-count guard differs")
    text = text.replace(old, 'require(len(parts) == 13, "expected all 13 reviewed payload parts")', 1)
    files["stage_reviewed_source.py"] = text.encode()
    text, count = re.subn(r'^STAGER_BLOB = "[^"\n]+"$',
        'STAGER_BLOB = "' + OUTPUT_BLOBS["stage_reviewed_source.py"] + '"',
        files["stage_database.py"].decode(), flags=re.M)
    require(count == 1, "ambiguous engine-code pin")
    files["stage_database.py"] = text.encode()
    require(all(oid(files[n]) == h for n, h in OUTPUT_BLOBS.items()), "configured engine bytes differ")
    return files


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("prepare", "commits"))
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repo, work, output = args.repository.resolve(), args.work.resolve(), args.output.resolve()
    files = configured(repo / ".github/recovery/59i-source")
    payload_dir = repo / ".github/recovery/59i-next"
    parts = sorted(payload_dir.glob("reviewed-source.part-*.b64"))
    require([p.name for p in parts] == [f"reviewed-source.part-{i:02}.b64" for i in range(1, 14)],
            "unexpected payload inventory")
    for p in parts:
        require(p.is_file() and not p.is_symlink(), "linked payload")
        files[p.name] = p.read_bytes()
    if args.phase == "prepare":
        require(not work.exists(), "refusing to overwrite transport work directory")
        work.mkdir(parents=True)
        for name, raw in files.items():
            (work / name).write_bytes(raw)
    else:
        require(work.is_dir() and not work.is_symlink(), "missing transport work directory")
        for name, raw in files.items():
            require((work / name).is_file() and not (work / name).is_symlink() and
                    (work / name).read_bytes() == raw, "configured transport changed: " + name)
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    if args.phase == "prepare":
        subprocess.run([sys.executable, "-B", str(work / "test_stage_database.py")], check=True, env=env)
    output.mkdir(parents=True, exist_ok=True)
    subprocess.run([sys.executable, "-B", str(work / "stage_database.py"), args.phase,
        "--repository", str(repo), "--payload-dir", str(work),
        "--destination", str(work.parent / "59i-reviewed-61d1-source"),
        "--output", str(output)], check=True, env=env)


if __name__ == "__main__":
    main()
