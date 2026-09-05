#!/usr/bin/env python3
"""Materialize only the reviewed 59b capture changes; never push or merge."""
from pathlib import Path
import json
import subprocess

ROOT = Path(__file__).resolve().parents[2]
STAGING = ROOT / 'morphhdl/implementation/increment-59b'
MANIFEST = ROOT / 'morphhdl/contracts/native-source-preservation.json'
POLICY = ROOT / 'morphhdl/contracts/increment-59b-native-capture-review.json'
NATIVE = 'core/src/main/scala/spinal/core/ElabBalancedReduction.scala'
UTILS = 'lib/src/main/scala/spinal/lib/Utils.scala'
LIB = 'lib/src/main/scala/spinal/lib.scala'
UTILS_OLD_BLOB = '8bca54bbf29f9fed58c10ed2e32e4401c08862c7'
UTILS_OLD_EDITS = ['gray-typed-width-01', 'gray-typed-width-02', 'gray-typed-width-03',
                   'clockdomain-buffered-reset-uncached-01', 'clockdomain-optional-buffered-reset-uncached-01']


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()


def replace_once(path, old, new):
    text = path.read_text()
    if text.count(old) != 1:
        raise RuntimeError(f'exact reviewed source anchor is missing or ambiguous: {path}')
    offset = text.index(old)
    path.write_text(text.replace(old, new, 1))
    return offset


def main():
    manifest = json.loads(MANIFEST.read_text())
    entries = {entry['path']: entry for entry in manifest['entries']}
    if NATIVE in entries or LIB in entries:
        raise RuntimeError('new native capture paths already have audit entries; explicit reconciliation required')
    old_utils_entry = entries[UTILS]
    if old_utils_entry['approved']['blob'] != UTILS_OLD_BLOB or git('hash-object', UTILS) != UTILS_OLD_BLOB:
        raise RuntimeError('Utils.scala differs from the reviewed merged 57a source')
    if [e['id'] for e in old_utils_entry['edits']] != UTILS_OLD_EDITS:
        raise RuntimeError('Utils.scala existing Gray-code/reset audit changed; refusing to overwrite it')
    if (ROOT / NATIVE).exists():
        raise RuntimeError('native capture SPI already exists; refusing to overwrite')
    if git('status', '--porcelain'):
        raise RuntimeError('materialization requires a clean checkout')

    old_utils = (ROOT / UTILS).read_text()
    start = old_utils.index('  def reduceBalancedTree(op: (T, T) => T): T = {')
    end = old_utils.index('  def distinctLinked', start)
    authoritative = old_utils[start:end]
    insertion = replace_once(ROOT / UTILS,
        '  def reduceBalancedTree(op: (T, T) => T): T =  new TraversableOnceAnyPimped[T](pimped).reduceBalancedTree(op)\n'
        '  def reduceBalancedTree(op: (T, T) => T, levelBridge: (T, Int) => T): T =  new TraversableOnceAnyPimped[T](pimped).reduceBalancedTree(op, levelBridge)\n',
        '  def reduceBalancedTree(op: (T, T) => T): T = reduceBalancedTree(op, (value: T, _: Int) => value)\n'
        '  def reduceBalancedTree(op: (T, T) => T, levelBridge: (T, Int) => T): T =\n'
        '    ElabBalancedReduction.reduce[T](pimped, op, levelBridge) {\n'
        '      (elements: Seq[T], operation: (T, T) => T, bridge: (T, Int) => T) =>\n'
        '        new TraversableOnceAnyPimped[T](elements).reduceBalancedTree(operation, bridge)\n'
        '    }\n')
    if authoritative not in (ROOT / UTILS).read_text():
        raise RuntimeError('the authoritative native reduction algorithm was changed')
    insertion_byte = len(old_utils[:insertion].encode())
    insertion_index = sum(e['approved_span']['start'] < insertion_byte for e in old_utils_entry['edits'])
    if any(e['approved_span']['start'] <= insertion_byte < e['approved_span']['end'] for e in old_utils_entry['edits']):
        raise RuntimeError('new native edit overlaps an existing reviewed span')
    replace_once(ROOT / LIB, 'new TraversableOncePimped[T](that.toSeq)',
                 'new TraversableOncePimped[T](ElabBalancedReduction.sourceSeq(that))')

    destinations = {
        'ElabBalancedReduction.scala.in': NATIVE,
        'TypedBalancedReductionCapture.scala.in': 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCapture.scala',
        'TypedBalancedReductionCaptureTests.scala.in': 'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCaptureTests.scala',
    }
    for template, destination in destinations.items():
        target = ROOT / destination
        if target.exists():
            raise RuntimeError(f'refusing to overwrite existing implementation: {destination}')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((STAGING / template).read_bytes())
    # Exact corrective patch was compiled locally from current source; it adds
    # target/initializer mutation checks and the independent safety suite.
    patch = STAGING / 'capture-fixes.patch'
    git('apply', '--check', str(patch))
    git('apply', str(patch))
    safety = 'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCaptureSafetyTests.scala'

    fields = ('id', 'kind', 'owner', 'reason', 'required_exact_text')
    files = []
    for entry in manifest['entries']:
        files.append(dict(path=entry['path'], baseline_path=entry['baseline']['path'] if entry['baseline'] else None,
                          change=entry['change'], classification=entry['classification'],
                          introduced_by=list(entry['introduced_by']), reason=entry['reason'],
                          edits=[{key: edit[key] for key in fields} for edit in entry['edits']]))
    files.append(dict(path=NATIVE, baseline_path=None, change='added', classification='typed-support-file',
                      introduced_by=['Increment 59b: native balanced-reduction capture boundary'],
                      reason='Neutral scoped callback dispatch with exception-safe restoration and exact symbolic Vec receiver retention; no reduction algorithm or RTL implementation.', edits=[]))
    for path, identifier, owner, anchor, reason in (
        (UTILS, 'reduce-balanced-dispatch-59b', 'spinal.lib.TraversableOncePimped.reduceBalancedTree',
         'ElabBalancedReduction.reduce[T]', 'Route typed Vec counts through the neutral callback boundary while retaining the unchanged generic helper and concrete descriptors.'),
        (LIB, 'reduce-balanced-receiver-59b', 'spinal.lib.traversableOncePimped',
         'ElabBalancedReduction.sourceSeq(that)', 'Preserve the exact typed Vec receiver before collection conversion; retain historical conversion for every concrete input.')
    ):
        edit = dict(id=identifier, kind='mechanical-propagation', owner=owner, reason=reason,
                    required_exact_text=[dict(side='approved', text=anchor, count=1)])
        if path == UTILS:
            policy_file = next(value for value in files if value['path'] == path)
            policy_file['edits'].insert(insertion_index, edit)
            policy_file['introduced_by'].append('Increment 59b: native balanced-reduction capture boundary')
            policy_file['reason'] += ' ' + reason
        else:
            files.append(dict(path=path, baseline_path=path, change='modified', classification='mechanical-propagation',
                              introduced_by=['Increment 59b: native balanced-reduction capture boundary'], reason=reason, edits=[edit]))
    policy = dict(schema_version=1, repository=manifest['repository'], baseline_commit=manifest['baseline']['commit'],
                  files=sorted(files, key=lambda item: item['path']))
    POLICY.write_text(json.dumps(policy, indent=2) + '\n')
    git('add', NATIVE, UTILS, LIB, safety, *[v for v in destinations.values() if v != NATIVE], str(POLICY.relative_to(ROOT)))
    git('commit', '-m', '59b: retain typed Vec receiver and capture authoritative native callback graphs')
    subprocess.run(['python3', 'morphhdl/scripts/check-native-source-preservation.py',
                    '--generate-template', str(POLICY.relative_to(ROOT)), '--output', str(MANIFEST.relative_to(ROOT)), '--force'],
                   cwd=ROOT, check=True)
    git('add', str(MANIFEST.relative_to(ROOT)))
    git('commit', '--amend', '--no-edit')
    print('CAPTURE_IMPLEMENTATION_HEAD=' + git('rev-parse', 'HEAD'))


if __name__ == '__main__':
    main()
