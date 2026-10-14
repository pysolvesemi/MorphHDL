#!/usr/bin/env python3
"""Read-only roadmap/call-site capture; does not qualify RTL behavior."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[3]
PATTERNS = {
    'parameters': r'ElabFormalParameter|formalParam|HdlInt\.param',
    'control': r'ElabControl\.|generateSymbolic|selectSymbolic',
    'loops': r'ElabFiniteRange|HdlRange',
    'affine': r'\.affine\(|whenAffineLessThan',
    'packing': r'\.packed\(|asBits|assignFromBits',
    'recursion': r'ElabRecursiveComponent',
    'hierarchy': r'ModulePortWires',
    'clock': r'ClockingArea|ClockDomain',
    'callback': r'levelBridge|pipelineMask',
    'names': r'setName\(|setDefinitionName',
}


def capture(application):
    roadmap = application / 'docs/plans/morphhdl-lightweight-scala-rtl-roadmap.md'
    items, entries = [], []
    phase = None
    for line in roadmap.read_text().splitlines():
        if line.startswith('## LWR-'):
            phase = line.split(' —')[0][3:]
        if line.startswith('- [ ] '):
            assert phase, 'requirement lacks a phase'
            ordinal = 1 + sum(item['phase'] == phase for item in items)
            items.append(dict(id=f'{phase}.{ordinal:02}', phase=phase,
                              requirement=line[6:], status='pending', evidence=[]))
    for label, base in [('compiler', ROOT),
                        ('application', application / 'display-controller-morphhdl/hw')]:
        for path in sorted(base.rglob('*.scala')):
            if any(part in path.parts for part in ('target', '.git')):
                continue
            if label == 'compiler' and not any(part in path.relative_to(base).parts
                                               for part in ('lib', 'morphruntime', 'frontend')):
                continue
            raw = path.read_bytes()
            lines = raw.decode().splitlines()
            matches = {key: [n for n, line in enumerate(lines, 1) if re.search(pattern, line)]
                       for key, pattern in PATTERNS.items()}
            matches = {key: value for key, value in matches.items() if value}
            if matches:
                entries.append(dict(repository=label, path=str(path.relative_to(base)),
                                    sha256=hashlib.sha256(raw).hexdigest(), lines=matches))
    def git(ref):
        return subprocess.check_output(['git', 'rev-parse', ref], cwd=ROOT, text=True).strip()
    return dict(schema=1, compiler_commit=git('HEAD'), compiler_tree=git('HEAD^{tree}'),
                roadmap_sha256=hashlib.sha256(roadmap.read_bytes()).hexdigest(),
                items=items, call_sites=entries)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--application', required=True, type=pathlib.Path)
    args = parser.parse_args()
    print(json.dumps(capture(args.application.resolve()), indent=2))
