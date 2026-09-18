#!/usr/bin/env python3
"""Additive live source/projection controls for the four-file PR190 repair."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SELF = 'morphhdl/scripts/check-sequential-wire-source-review.py'
spec = importlib.util.spec_from_file_location('sequential_review_controls', ROOT / SELF)
assert spec and spec.loader
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


def git(root, *args):
    return subprocess.check_output(['git', '-c', 'user.name=Sequential review controls',
        '-c', 'user.email=source-control@example.invalid', *args], cwd=root, stderr=subprocess.PIPE)


def catalog_controls():
    source = ROOT / "morphhdl/scripts/check-increment-60f-artifacts.py"
    spec = importlib.util.spec_from_file_location("sequential_catalog", source)
    catalog = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(catalog)
    profile = "60f-with-wa07a-and-59d-and-59e-and-59f-and-59c-and-59g-and-59h-and-wa07b-and-60g"
    common = dict(packing=True, wa08=True, wa09=True, wa10=True, wa11=True, lane_when=True)
    old = catalog.catalog_for_profile(profile, **common)
    current = catalog.catalog_for_profile(profile, **common, sequential_wire=True)
    assert tuple(map(sum, zip(*old[0].values()))) == (2029, 198)
    assert tuple(map(sum, zip(*current[0].values()))) == (2066, 201)
    for project in old[0]:
        assert old[1][project] <= current[1][project]
        for name, count in old[2].get(project, {}).items():
            assert current[2][project][name] == count
        assert current[1][project] - old[1][project] == set(catalog.SEQUENTIAL_WIRE_SUITES.get(project, {}))
    combined = catalog.catalog_for_profile(profile, **common, pr188=True, sequential_wire=True)
    assert tuple(map(sum, zip(*combined[0].values()))) == (2101, 204)
    entries = {path: {"before_sha256": None} for path in catalog.SEQUENTIAL_WIRE_SUITE_SOURCES}
    assert not catalog.sequential_wire_suite_flag({}, True)
    assert catalog.sequential_wire_suite_flag(entries, True)
    rejected = 0
    def reject(action):
        nonlocal rejected
        try:
            action()
        except RuntimeError:
            rejected += 1
        else:
            raise AssertionError("accepted partial/misclassified sequential catalog")
    for path in entries:
        reject(lambda p=path: catalog.sequential_wire_suite_flag({k:v for k,v in entries.items() if k != p}, True))
        changed = {k:dict(v) for k,v in entries.items()}
        changed[path]["before_sha256"] = "0" * 64
        reject(lambda e=changed: catalog.sequential_wire_suite_flag(e, True))
    reject(lambda: catalog.sequential_wire_suite_flag(entries, False))
    without_lane = dict(common); without_lane["lane_when"] = False
    reject(lambda: catalog.catalog_for_profile(profile, **without_lane, sequential_wire=True))
    assert rejected == 14
    print("SEQUENTIAL_WIRE_CATALOG_CONTROLS_PASS additive_tests=37 additive_suites=3 rejected=14")


def main():
    catalog_controls()
    current = review.verify(ROOT)
    safety_count = 0
    for path, markers in review.SAFETY_MARKERS.items():
        text = (ROOT / path).read_text()
        assert not review.safety_failures(path, text)
        for marker in markers:
            assert review.safety_failures(path, text.replace(marker, 'REMOVED_SAFETY_OBLIGATION'))
            safety_count += 1
        # Apply forbidden recognizers to the compiler region, not fixture text.
        for boundary in ('object ParameterizedStreamFifoNamedExpressionPassWitness',
                         'object ParameterizedStreamFifoExpressionPassWitness',
                         'object ParameterizedStreamFifoUnnamedPassWitness'):
            text = text.split(boundary, 1)[0]
        for addition in ('val bad = "_zz_timing_hTotal"', 'val bad = "when_test_l123"',
                         'val bad = "RemainingWireRepro"', 'val bad = parseVerilog(text)'):
            assert review.safety_failures(path, text + '\n' + addition)
            safety_count += 1
    cases = []
    with tempfile.TemporaryDirectory(prefix='sequential-source-controls-') as temporary:
        fixture = Path(temporary) / 'current'
        git(ROOT, 'worktree', 'add', '--quiet', '--detach', str(fixture), current['head'])
        try:
            def reject(label, mutate, commit=False):
                git(fixture, 'reset', '--hard', current['head'])
                git(fixture, 'clean', '-fdx')
                review.verify(fixture)
                mutate()
                if commit:
                    git(fixture, 'add', '-A')
                    git(fixture, 'commit', '-qm', 'deliberately unreviewed source')
                try:
                    review.verify(fixture)
                except RuntimeError as error:
                    assert str(error).startswith(('WA-08 source overlay:', 'SEQUENTIAL-WIRE-SOURCE:'))
                    cases.append({'case': label, 'rejection': str(error).splitlines()[0]})
                else:
                    raise AssertionError('Accepted live source mutation: ' + label)

            def append(path):
                file = fixture / path
                file.write_bytes(file.read_bytes() + b'\n// unreviewed sequential mutation\n')

            for path in sorted(review.PRODUCTION_PATHS):
                reject('worktree compiler bytes ' + path, lambda p=path: append(p))
                reject('committed compiler bytes ' + path, lambda p=path: append(p), True)
                reject('partial rollback ' + path, lambda p=path:
                    (fixture / p).write_bytes(git(fixture, 'show', review.BASE + ':' + p)), True)
            for path in (SELF, 'morphhdl/scripts/check-lane-when-source-scope.py',
                         'morphhdl/scripts/check-lane-when-increment61-source.py',
                         'morphhdl-passes/scripts/check-boundary.sh',
                         'core/src/test/scala/spinal/core/internals/SequentialWireEmitterTests.scala',
                         'morphhdl/src/test/scala/morphhdl/SequentialWireNativeTests.scala',
                         'morphhdl/src/test/scala/morphhdl/examples/SequentialWireRetentionTests.scala'):
                reject('review/test source ' + path, lambda p=path: append(p))
            victim = sorted(review.PRODUCTION_PATHS)[0]
            def hidden_index():
                raw = (fixture / victim).read_bytes()
                append(victim)
                git(fixture, 'add', victim)
                (fixture / victim).write_bytes(raw)
            reject('hidden staged compiler delta', hidden_index)
            reject('compiler mode', lambda: (fixture / victim).chmod(0o755))
            def extra_source():
                path = fixture / 'repro/unreviewed/src/main/Unknown.scala'
                path.parent.mkdir(parents=True)
                path.write_text('// unknown production root\n')
            reject('unknown source root', extra_source, True)
            git(fixture, 'reset', '--hard', current['head'])
            git(fixture, 'clean', '-fdx')
            review.verify(fixture)
            outer = review.load_outer(fixture)
            for path in sorted(review.PRODUCTION_PATHS):
                raw = (fixture / path).read_bytes()
                before = git(fixture, 'show', outer.BASE + ':' + path) if outer.frozen(fixture, outer.BASE, path) is not None else b''
                assert outer.restore_source(fixture, path, raw) == before
                try:
                    outer.restore_source(fixture, path, raw + b'\nmutation\n')
                except RuntimeError:
                    cases.append({'case':'changed projection ' + path, 'rejection':'WA-08 rejects unknown current bytes'})
                else:
                    raise AssertionError('Accepted altered projection')
        finally:
            git(ROOT, 'worktree', 'remove', '--force', str(fixture))
        # Independently execute the predecessor's actual checker at its own
        # immutable tree. This supplements, never replaces, the current proof.
        predecessor = Path(temporary) / 'predecessor'
        git(ROOT, 'worktree', 'add', '--quiet', '--detach', str(predecessor), review.BASE)
        try:
            result = subprocess.run([sys.executable, 'morphhdl/scripts/check-increment-61-source-review.py'],
                cwd=predecessor, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
            assert result.returncode == 0, result.stdout
            assert 'LANE_INCREMENT61_CURRENT_SOURCE_PASS compiler_files=14 signatures=98' in result.stdout
        finally:
            git(ROOT, 'worktree', 'remove', '--force', str(predecessor))
    assert len(cases) == 26, cases
    assert safety_count >= 55
    directory = ROOT / 'target/sequential-source-review'
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'rejection-controls.json').write_text(json.dumps(
        {'head':current['head'], 'cases':cases, 'safety_mutations':safety_count}, indent=2)+'\n')
    (directory / 'historical-predecessor.log').write_text(result.stdout)
    print('SEQUENTIAL_WIRE_SOURCE_MUTATIONS_PASS live=26 safety=' + str(safety_count))


if __name__ == '__main__':
    main()
