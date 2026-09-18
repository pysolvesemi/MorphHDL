#!/usr/bin/env python3
"""Archive an allowlist of public build artifacts for offline reproduction.

No home directories, environment dumps, authentication files or Git config are
included. The source bundle contains Git objects for the exact checked-out
revision and its ancestry, not repository configuration or credentials.
"""
import hashlib
import json
import re
import shutil
import subprocess
import tarfile
from pathlib import Path

root = Path.cwd()
out = root / 'cdc-independent-evidence' / 'tools'
out.mkdir(parents=True, exist_ok=True)
allowed = {'.jar', '.pom', '.xml', '.sha1', '.md5', '.sha256', '.properties'}
cache_roots = [Path('/sbt/.sbt/boot'), Path('/sbt/.ivy2/cache'), Path('/sbt/.cache/coursier/v1')]
files = set()
for directory in cache_roots:
    if directory.is_dir():
        files.update(p for p in directory.rglob('*') if p.is_file() and not p.is_symlink()
                     and p.suffix in allowed)
# Include only launcher JARs, never shell startup or authentication files.
for directory in (Path('/opt'), Path('/usr/local'), Path('/sbt'),
                  Path('/github/home/.sbt/launchers'), Path('/root/.sbt/launchers'),
                  Path('/github/home/.cache/sbt'), Path('/usr/share/sbt')):
    if directory.is_dir():
        files.update(p for p in directory.rglob('*sbt-launch*.jar') if p.is_file())
if not any(p.name.startswith('scala-compiler-2.12.18') for p in files):
    raise RuntimeError('missing Scala 2.12.18 compiler in public cache')
if not any('sbt-launch' in p.name for p in files):
    raise RuntimeError('missing sbt launcher')
with tarfile.open(out / 'public-build-cache.tar.gz', 'w:gz') as archive:
    for p in sorted(files):
        archive.add(p, arcname=str(p).lstrip('/'), recursive=False)

# Binary tools and their non-libc dynamic dependencies remain in an isolated
# archive. Consumers choose an explicit prefix; no system files are overwritten.
tools = set()
for name in ('iverilog', 'vvp', 'yosys', 'yosys-abc', 'verilator_bin'):
    found = shutil.which(name)
    if found:
        tools.add(Path(found).resolve())
for directory in (Path('/usr/lib/x86_64-linux-gnu/ivl'), Path('/usr/lib/ivl'),
                  Path('/usr/local/lib/ivl'), Path('/opt/lib/ivl'),
                  Path('/usr/share/yosys'), Path('/usr/local/share/yosys'), Path('/opt/share/yosys')):
    if directory.is_dir():
        tools.update(p.resolve() for p in directory.rglob('*') if p.is_file())
for p in list(tools):
    if p.read_bytes()[:4] != b'\x7fELF':
        continue
    result = subprocess.run(['ldd', str(p)], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    for dep in re.findall(r'(?:=>\s+|^\s*)(/\S+)', result.stdout, re.M):
        dep = Path(dep)
        if dep.is_file() and not dep.name.startswith(('ld-linux', 'libc.so', 'libpthread.so', 'libdl.so', 'librt.so', 'libm.so')):
            tools.add(dep.resolve())
with tarfile.open(out / 'hdl-tools.tar.gz', 'w:gz') as archive:
    for p in sorted(tools):
        archive.add(p, arcname=str(p).lstrip('/'), recursive=False)
subprocess.run(['git', 'bundle', 'create', str(out / 'source.bundle'), 'HEAD'], check=True)
(out / 'manifest.json').write_text(json.dumps({
    'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
    'public_cache_files': [str(p) for p in sorted(files)],
    'tool_files': [str(p) for p in sorted(tools)],
    'sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
               for p in out.iterdir() if p.is_file()},
}, indent=2) + '\n')
print('Exported allowlisted public build artifacts and exact source ancestry')
