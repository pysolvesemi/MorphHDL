#!/usr/bin/env python3
"""Exercise the real published-seal / Increment 61 composition in a disposable tree.

The production certificate is never modified. Forged source/seal candidates in
negative tests are local objects only and must be rejected by the real verifier.
No inherited checker or immutable audit is mocked in positive tests.
"""
from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import types
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = 'morphhdl/scripts/check-increment-59i-production-successor.py'
CONTRACT = 'morphhdl/contracts/increment-59i-production-successor.json'
CHECK61 = 'morphhdl/scripts/check-increment-61-source-review.py'
CONTRACT61 = 'morphhdl/contracts/increment-61-source-review.json'


def git(root: Path, *args: str, input: bytes | None = None) -> bytes:
    env = dict(os.environ, GIT_AUTHOR_NAME='59i continuation test',
        GIT_COMMITTER_NAME='59i continuation test',
        GIT_AUTHOR_EMAIL='test@example.invalid', GIT_COMMITTER_EMAIL='test@example.invalid')
    proc = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', *args], cwd=root,
        input=input, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    if proc.returncode:
        raise RuntimeError(proc.stderr.decode(errors='replace'))
    return proc.stdout


def load(path: Path, name: str = 'test_continuation'):
    result = types.ModuleType(name)
    result.__file__ = str(path)
    exec(compile(path.read_bytes(), str(path), 'exec'), result.__dict__)
    return result


class ContinuationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.head = git(ROOT, 'rev-parse', 'HEAD').decode().strip()
        cls.tmp = tempfile.TemporaryDirectory(prefix='59i-real-continuation-')
        cls.root = Path(cls.tmp.name) / 'checkout'
        git(ROOT, 'worktree', 'add', '--detach', str(cls.root), cls.head)
        cls.review = load(cls.root / HELPER)
        cls.value = cls.review.verify(cls.root)
        if cls.value['schema_version'] != 3:
            raise RuntimeError('Run continuation tests only on their sealed schema-3 source')
        cls.source = cls.value['source_commit']
        cls.source_tree = cls.value['source_tree']
        cls.head_tree = git(cls.root, 'rev-parse', 'HEAD^{tree}').decode().strip()
        cls.manifest = (cls.root / CONTRACT).read_bytes()
        cls.helper = (cls.root / HELPER).read_bytes()
        cls.digest = cls.review.CONTRACT_SHA256
        cls.tracked = git(cls.root, 'ls-files', '--stage')

    @classmethod
    def tearDownClass(cls):
        git(ROOT, 'worktree', 'remove', '--force', str(cls.root))
        cls.tmp.cleanup()

    def setUp(self):
        self.restore()

    def tearDown(self):
        self.restore()

    def restore(self):
        git(self.root, 'reset', '--hard', self.head)
        git(self.root, 'clean', '-fdx')
        self.review.CONTRACT_SHA256 = self.digest
        self.assertEqual(git(self.root, 'ls-files', '--stage'), self.tracked)

    def check(self):
        return self.review.verify(self.root)

    def reject(self, pattern, action=None):
        with self.assertRaisesRegex(RuntimeError, pattern):
            (action or self.check)()

    def commit(self, parents=None, message=b'negative continuation fixture\n'):
        git(self.root, 'add', '--all')
        tree = git(self.root, 'write-tree').decode().strip()
        parents = parents if parents is not None else [git(self.root, 'rev-parse', 'HEAD').decode().strip()]
        args = ['commit-tree', tree]
        for parent in parents:
            args += ['-p', parent]
        head = git(self.root, *args, input=message).decode().strip()
        git(self.root, 'reset', '--hard', head)
        return head

    def forged_manifest(self, value, source=None):
        source = source or self.source
        git(self.root, 'reset', '--hard', source)
        raw = (json.dumps(value, indent=2, sort_keys=True) + '\n').encode()
        digest = hashlib.sha256(raw).hexdigest()
        helper = git(self.root, 'show', self.source + ':' + HELPER)
        self.assertEqual(helper.count(b'CONTRACT_SHA256 = "UNSEALED"\n'), 1)
        (self.root / HELPER).write_bytes(helper.replace(b'CONTRACT_SHA256 = "UNSEALED"\n',
            b'CONTRACT_SHA256 = "' + digest.encode() + b'"\n', 1))
        (self.root / CONTRACT).write_bytes(raw)
        self.commit([source])
        self.review.CONTRACT_SHA256 = digest

    def test_projection_cache_is_deeply_immutable(self):
        view = self.review._projection_contract(self.root)
        self.assertIs(view, self.review._projection_contract(self.root))
        with self.assertRaises(TypeError):
            view['source_commit'] = '0' * 40
        with self.assertRaises(TypeError):
            view['files'][0]['path'] = 'unreviewed.scala'
        with self.assertRaises(TypeError):
            view['files'][0] = {}
        self.check()

    def test_public_manifest_cannot_poison_cached_projection(self):
        first = self.review.contract(self.root)
        first['source_commit'] = '0' * 40
        first['files'][0]['path'] = 'poison.scala'
        first['files'].clear()
        self.assertEqual(self.review.contract(self.root), self.value)
        self.assertEqual(self.review._projection_contract(self.root)['source_commit'], self.source)
        self.check()

    def test_projection_cache_reauthenticates_live_manifest(self):
        self.review._projection_contract(self.root)
        (self.root / CONTRACT).write_bytes(self.manifest + b'\n')
        self.reject('sealed successor manifest changed',
            lambda: self.review._projection_contract(self.root))

    def test_projection_cache_reauthenticates_helper_mode(self):
        self.review._projection_contract(self.root)
        (self.root / HELPER).chmod(0o755)
        self.reject('source mode changed',
            lambda: self.review._projection_contract(self.root))

    def test_cached_projection_does_not_authorize_new_source(self):
        self.review._projection_contract(self.root)
        path = self.root / 'morphhdl/src/main/scala/UnreviewedCacheInput.scala'
        path.write_text('object UnreviewedCacheInput {}\n')
        self.reject('untracked|unexpected')

    def test_original_integration_inventory_survives_an_already_integrated_parent(self):
        self.assertEqual(self.review.CONTINUATION_PARENT,
            'f42641880e0645f0c997ecedabd031bf8948bbfa')
        sync = json.loads(git(self.root, 'show',
            self.review.CONTINUATION_PARENT + ':' + CONTRACT))
        self.assertEqual(sync['previous_seal']['seal_commit'],
            '37d1629f9b78c4d9cd6646abee9962fdd046e413')
        sharded = json.loads(git(self.root, 'show',
            sync['previous_seal']['seal_commit'] + ':' + CONTRACT))
        self.assertEqual(sharded['previous_seal']['seal_commit'],
            '969af59a0378fca5b964d04d8f2af49b123dd804')
        immediate = json.loads(git(self.root, 'show',
            sharded['previous_seal']['seal_commit'] + ':' + CONTRACT))
        self.assertEqual(immediate['previous_seal']['seal_commit'],
            'c74b34bb1154d1df20bf85aa63e1276388511a02')
        # Keep the original published-parent assertion on the exact preceding
        # certificate, while this scheduling-only successor pins the next link.
        prior = json.loads(git(self.root, 'show',
            immediate['previous_seal']['seal_commit'] + ':' + CONTRACT))
        self.assertEqual(prior['previous_seal']['seal_commit'],
            'ddf61ef25f927d646027cebbcaca6d724ad8a5fa')
        prior_prior = json.loads(git(self.root, 'show',
            prior['previous_seal']['seal_commit'] + ':' + CONTRACT))
        self.assertEqual(prior_prior['previous_seal']['seal_commit'],
            '14dc0d2bb7274d9e91b60f112619a78b237936ab')
        self.assertEqual(hashlib.sha256(git(self.root, 'show',
            self.review.CONTINUATION_PARENT + ':' + CONTRACT)).hexdigest(),
            self.review.CONTINUATION_PARENT_MANIFEST)
        # The old integrated target remains in the pinned sharding parent;
        # PR189 is incorporated by the new source's second parent instead.
        git(self.root, 'merge-base', '--is-ancestor',
            self.review.CONTINUATION_COMMON, self.review.CONTINUATION_PARENT)
        git(self.root, 'merge-base', '--is-ancestor',
            self.review.CONTINUATION_TARGET, self.value['source_commit'])
        self.assertEqual(set(e['path'] for e in self.value['target_integration']['files']),
            self.review.changed(self.root, self.review.CONTINUATION_COMMON, self.review.CONTINUATION_TARGET))
        self.assertEqual(git(self.root, 'merge-base', '--all',
            self.review.CONTINUATION_INTEGRATION_PARENT, self.review.CONTINUATION_TARGET).decode().strip(),
            self.review.CONTINUATION_COMMON)

    def test_target_projection_cache_preserves_live_authentication(self):
        target = load(self.root / 'morphhdl/scripts/check-increment-59i-target-integration.py')
        first = target._projection_contract(self.root)
        self.assertIs(first, target._projection_contract(self.root))
        with self.assertRaises(TypeError):
            first['files'][0]['path'] = 'poison.scala'
        (self.root / HELPER).write_bytes(self.helper + b'\n# altered after cache\n')
        self.reject('production successor reviewer changed',
            lambda: target._projection_contract(self.root))

    def test_content_cache_rejects_same_size_manifest_edit_with_preserved_mtime(self):
        self.review._projection_contract(self.root)
        file = self.root / CONTRACT
        before = file.stat()
        changed = self.manifest.replace(b'"schema_version": 3', b'"schema_version": 9', 1)
        self.assertNotEqual(changed, self.manifest)
        self.assertEqual(len(changed), len(self.manifest))
        file.write_bytes(changed)
        os.utime(file, ns=(before.st_atime_ns, before.st_mtime_ns))
        self.reject('sealed successor manifest changed')

    def test_content_cache_rejects_new_helper_bytes(self):
        self.review._projection_contract(self.root)
        (self.root / HELPER).write_bytes(self.helper + b'\n# late helper change\n')
        self.reject('sealed successor helper changed')

    def test_warm_lexical_cache_rejects_mode_change_on_other_live_source(self):
        self.check()
        file = self.root / 'morphhdl/scripts/check-increment-59i-target-integration.py'
        file.chmod(0o755)
        self.reject('source mode changed')

    def test_warm_lexical_cache_rejects_parent_directory_link(self):
        self.check()
        original = self.root / 'morphhdl/src/test/scala'
        moved = self.root / 'moved-scala'
        original.rename(moved)
        original.symlink_to(moved, target_is_directory=True)
        try:
            self.reject('linked source')
        finally:
            original.unlink()
            moved.rename(original)

    def test_complete_current_source_and_both_original_certificates(self):
        value = self.check()
        self.assertEqual(value['previous_seal'], self.review.previous_certificate())
        self.assertEqual(git(self.root, 'rev-list', '--parents', '-n', '1', self.source).decode().split(),
            [self.source, self.review.CONTINUATION_PARENT, self.review.CONTINUATION_TARGET])
        self.assertEqual(git(self.root, 'show', self.source + ':' + CONTRACT),
            git(self.root, 'show', self.review.CONTINUATION_PARENT + ':' + CONTRACT))
        load(self.root / CHECK61, 'real_61_continuation').verify(self.root)

    def test_original_increment61_self_tests_execute_on_frozen_target(self):
        self.review.audit_immutable_certificate(self.root, self.review.CONTINUATION_TARGET,
            CHECK61, self.review.CONTINUATION_61_HELPER, self_test=True)

    def test_historical_predecessor_projection_remains_exact(self):
        path = 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala'
        self.assertEqual(self.review.restore_source(self.root, path, (self.root / path).read_bytes()),
            git(self.root, 'show', self.review.BASE + ':' + path))
        self.assertEqual(self.review.target_source(self.root, path, (self.root / path).read_bytes()),
            git(self.root, 'show', self.review.CONTINUATION_TARGET + ':' + path))

    def test_tree_preserving_documentation_descendant_is_supported(self):
        self.commit()
        self.check()

    def test_normal_github_merge_keeps_exact_feature_tree(self):
        self.commit([self.review.CONTINUATION_TARGET, self.head])
        self.check()

    def test_unsealed_source_is_rejected(self):
        git(self.root, 'reset', '--hard', self.source)
        self.reject('sealed successor manifest changed')

    def test_missing_parent_record_is_rejected(self):
        v = copy.deepcopy(self.value); del v['previous_seal']
        self.forged_manifest(v)
        self.reject('invalid manifest schema')

    def test_forged_previous_certificate_is_rejected(self):
        for key in self.value['previous_seal']:
            with self.subTest(key=key):
                v = copy.deepcopy(self.value); v['previous_seal'][key] = '0' * len(v['previous_seal'][key])
                self.forged_manifest(v)
                self.reject('previous source seal identity changed')
                self.restore()

    def test_forged_target_anchor_is_rejected(self):
        v = copy.deepcopy(self.value); v['target_integration']['target_commit'] = self.review.CONTINUATION_COMMON
        self.forged_manifest(v)
        self.reject('target integration anchors changed')

    def test_wrong_source_parent_order_is_rejected(self):
        wrong = git(self.root, 'commit-tree', self.source_tree, '-p', self.review.CONTINUATION_TARGET,
            '-p', self.review.CONTINUATION_PARENT, input=b'wrong parents\n').decode().strip()
        v = copy.deepcopy(self.value); v['source_commit'] = wrong
        self.forged_manifest(v, wrong)
        self.reject('continuation must join the published seal and exact reviewed target in order')

    def test_unsealed_parent_in_place_of_published_seal_is_rejected(self):
        wrong = git(self.root, 'commit-tree', self.source_tree, '-p', self.review.CONTINUATION_PARENT_SOURCE,
            '-p', self.review.CONTINUATION_TARGET, input=b'wrong predecessor\n').decode().strip()
        v = copy.deepcopy(self.value); v['source_commit'] = wrong
        self.forged_manifest(v, wrong)
        self.reject('continuation must join the published seal and exact reviewed target in order')

    def test_prior_manifest_cannot_be_removed_before_resealing(self):
        git(self.root, 'reset', '--hard', self.source)
        (self.root / CONTRACT).unlink()
        wrong = self.commit([self.review.CONTINUATION_PARENT, self.review.CONTINUATION_TARGET])
        v = copy.deepcopy(self.value); v.update(source_commit=wrong, source_tree=git(self.root, 'rev-parse', 'HEAD^{tree}').decode().strip())
        self.forged_manifest(v, wrong)
        self.reject('continuation lost its prior certificate')

    def test_prior_manifest_cannot_be_rewritten_before_resealing(self):
        git(self.root, 'reset', '--hard', self.source)
        (self.root / CONTRACT).write_bytes(b'{}\n')
        wrong = self.commit([self.review.CONTINUATION_PARENT, self.review.CONTINUATION_TARGET])
        v = copy.deepcopy(self.value); v.update(source_commit=wrong, source_tree=git(self.root, 'rev-parse', 'HEAD^{tree}').decode().strip())
        self.forged_manifest(v, wrong)
        self.reject('unsealed continuation must preserve its predecessor certificate bytes')

    def test_missing_current_inventory_entry_is_rejected(self):
        v = copy.deepcopy(self.value)
        v['files'] = [e for e in v['files'] if e['path'] != 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala']
        self.forged_manifest(v)
        self.reject('complete successor delta inventory changed')

    def test_unapproved_target_reconciliation_is_rejected(self):
        v = copy.deepcopy(self.value)
        e = next(e for e in v['target_integration']['files'] if e['path'] not in self.review.CONTINUATION_RECONCILIATIONS)
        e['after_sha256'] = '0' * 64
        self.forged_manifest(v)
        self.reject('unreviewed target-only change')

    def test_forged_source_span_is_rejected(self):
        v = copy.deepcopy(self.value)
        e = next(e for e in v['files'] if e['edits'] and e['edits'][0]['after'])
        span = e['edits'][0]; text = span['after']; span['after'] = ('!' if text[0] != '!' else '?') + text[1:]
        self.forged_manifest(v)
        self.reject('after span differs')

    def test_live_source_mutation_rejected_even_after_cached_pass(self):
        self.check()
        p = self.root / 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala'
        p.write_bytes(p.read_bytes() + b'\n// unreviewed mutation\n')
        self.reject('HEAD/index/worktree identity differs')

    def test_live_helper_mutation_is_rejected(self):
        (self.root / HELPER).write_bytes(self.helper + b'\n# unreviewed\n')
        self.reject('sealed successor helper changed')

    def test_live_manifest_mutation_is_rejected(self):
        (self.root / CONTRACT).write_bytes(self.manifest + b'\n')
        self.reject('sealed successor manifest changed')

    def test_executable_helper_is_rejected(self):
        (self.root / HELPER).chmod(0o755)
        self.reject('source mode changed')

    def test_linked_helper_is_rejected(self):
        path = self.root / HELPER; path.unlink(); path.symlink_to(ROOT / HELPER)
        self.reject('linked source')

    def test_untracked_shadow_source_is_rejected(self):
        (self.root / 'morphhdl/scripts/unreviewed-continuation.py').write_text('raise RuntimeError()\n')
        self.reject('untracked|unexpected')

    def test_removed_seal_files_cannot_enter_legacy_increment61_path(self):
        (self.root / HELPER).unlink(); (self.root / CONTRACT).unlink(); self.commit()
        self.reject('continuation certificate was removed', lambda: load(self.root / CHECK61).verify(self.root))

    def test_existing61_contract_cannot_change(self):
        p = self.root / CONTRACT61; p.write_bytes(p.read_bytes() + b'\n')
        self.reject('HEAD/index/worktree identity differs', lambda: load(self.root / CHECK61).verify(self.root))

    def test_old_wa08_and_wa10_audits_compose_both_certificates(self):
        overlay = load(self.root / 'morphhdl/scripts/check-increment-62-wa08-source-overlay.py')
        overlay.verify(self.root)
        load(self.root / 'morphhdl/scripts/check-wa10-source-scope.py').verify(self.root)
        path = '.github/workflows/increment-60f-equivalence-closure.yml'
        integration = overlay.integration_review(self.root)
        projected = overlay.overlay_target_source(self.root, integration, path, (self.root / path).read_bytes())
        self.assertEqual(projected, git(self.root, 'show', self.review.CONTINUATION_TARGET + ':' + path))
        self.assertEqual(self.review.target_anchor(self.root), self.review.CONTINUATION_TARGET)

    def test_increment61_historical_projection_rejects_foreign_input(self):
        path = 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala'
        self.reject('unreviewed bytes cannot enter Increment 61 predecessor projection', lambda:
            self.review.increment61_predecessor_source(self.root, path, b'unreviewed'))

    def test_increment61_historical_projection_is_exact_and_idempotent(self):
        for path in ['morphhdl/src/main/scala/morphhdl/MorphVerilog.scala', CHECK61]:
            current = git(self.root, 'show', self.review.CONTINUATION_TARGET + ':' + path)
            present = git(self.root, 'ls-tree', '-z', self.review.CONTINUATION_61_BASE, '--', path)
            before = git(self.root, 'show', self.review.CONTINUATION_61_BASE + ':' + path) if present else b''
            self.assertEqual(self.review.increment61_predecessor_source(self.root, path, current), before)
            self.assertEqual(self.review.increment61_predecessor_source(self.root, path, before), before)

    def test_increment61_inventory_projection_preserves_unknown_sentinels(self):
        for base in [self.review.BASE, self.review.CONTINUATION_COMMON, self.review.CONTINUATION_61_BASE]:
            for full in [False, True]:
                with self.subTest(base=base, full=full):
                    domain = lambda paths: paths if full else {p for p in paths if '/src/main/' in p}
                    current = domain(self.review.changed(self.root, base, self.review.CONTINUATION_TARGET))
                    before = domain(self.review.changed(self.root, base, self.review.CONTINUATION_61_BASE))
                    sentinel = 'foreign/src/main/Unreviewed.scala'
                    self.assertEqual(self.review.increment61_predecessor_inventory(self.root, current, base, full), before)
                    self.assertEqual(self.review.increment61_predecessor_inventory(self.root, current | {sentinel}, base, full), before | {sentinel})

    def test_wrong_frozen_checker_pin_is_rejected_before_execution(self):
        self.reject('immutable parent certificate checker changed', lambda:
            self.review.audit_immutable_certificate(self.root, self.review.CONTINUATION_TARGET, CHECK61, '0' * 64))

    def test_competing_seal_in_descendant_history_is_rejected(self):
        (self.root / CONTRACT).write_bytes(b'{}\n'); self.commit()
        (self.root / CONTRACT).write_bytes(self.manifest); self.commit()
        self.reject('successor contract history differs')

    def test_more_than_one_integration_merge_is_rejected(self):
        first = self.commit([self.review.CONTINUATION_TARGET, self.head])
        self.commit([self.review.CONTINUATION_TARGET, first], b'second merge\n')
        self.reject('more than one integration merge')

    def test_future_target_merge_is_not_authorized(self):
        future = git(self.root, 'commit-tree', self.head_tree, '-p', self.review.CONTINUATION_TARGET,
            input=b'future target, not reviewed\n').decode().strip()
        self.commit([future, self.head])
        self.reject('integration target is not an ancestor')

    def test_merged_current_tree_cannot_change(self):
        p = self.root / 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala'
        p.write_bytes(p.read_bytes() + b'\n// bad merge\n')
        self.commit([self.review.CONTINUATION_TARGET, self.head])
        self.reject('integration merge changed the reviewed feature tree')

    def test_index_forgery_is_rejected(self):
        p = 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala'
        (self.root / p).write_bytes((self.root / p).read_bytes() + b'\n// staged\n')
        git(self.root, 'add', '--', p)
        self.reject('HEAD/index identity differs')


def run_schema4_historical_continuation(suite: str = "continuation") -> None:
    """Run every original schema-3 assertion on its exact certified source.

    Schema 4 has a different, separately tested development topology. It cannot
    satisfy schema 3's two-parent-source assertions. Keep that original suite
    byte-for-byte in its published checkout and additionally test the current
    schema-4 verifier; no historical negative is relaxed or skipped.
    """
    helper = ROOT / HELPER
    relative = Path(HELPER)
    if any(ROOT.joinpath(*relative.parts[:index]).is_symlink()
            for index in range(1, len(relative.parts) + 1)) or not helper.is_file() or helper.stat().st_mode & 0o111:
        raise RuntimeError('continuation routing requires a regular current successor verifier')
    raw = helper.read_bytes()
    pattern = rb'^CONTRACT_SHA256 = "[^"\n]+"$'
    if len(re.findall(pattern, raw, re.M)) != 1:
        raise RuntimeError('continuation routing found an ambiguous current verifier seal')
    normalized = re.sub(pattern, b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw, flags=re.M)
    if hashlib.sha256(normalized).hexdigest() != '2cd3030c3a320ca392527ee38e4186f33db4bc7a6b6320b3bf10946b57fb0f20':
        raise RuntimeError('continuation routing refuses an unauthenticated current verifier')
    review = types.ModuleType('reviewed_schema4_continuation_route')
    review.__file__ = str(helper)
    exec(compile(raw, str(helper), 'exec'), review.__dict__)
    value = review.verify(ROOT)
    if value['schema_version'] not in (4, 5, 6, 7):
        raise RuntimeError('continuation route changed schema')
    anchor = '90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6'
    originals = {
        'continuation': ('morphhdl/scripts/test-increment-59i-continuation.py',
            '0c67499379f1e8d514a3cae7e72e329d9c75966e9c63e2f2708c4ea4f93f7e1b'),
        'pr189': ('morphhdl/scripts/test-increment-59i-pr189-sync.py',
            'ad89c3f15ba036652835aff2ac88ddac63a21ac35b3556883a030f6f3d358a3c'),
    }
    if suite not in originals:
        raise RuntimeError('unknown immutable schema-3 test suite')
    test, expected_hash = originals[suite]
    original = review.frozen(ROOT, anchor, test)
    if original is None or hashlib.sha256(original).hexdigest() != expected_hash:
        raise RuntimeError('immutable schema-3 continuation suite changed')
    print('Current schema-' + str(value['schema_version']) + ' source authenticated; replaying unchanged ' + test + ' at ' + anchor,
          flush=True)
    with tempfile.TemporaryDirectory(prefix='59i-schema3-continuation-route-') as directory:
        checkout = Path(directory) / 'source'
        git(ROOT, 'worktree', 'add', '--detach', str(checkout), anchor)
        try:
            if (checkout / test).read_bytes() != original:
                raise RuntimeError('schema-3 continuation checkout differs from its authenticated test bytes')
            subprocess.run([sys.executable, '-B', test, '-v'], cwd=checkout,
                env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'), check=True, timeout=3600)
            review.verify_checkout(checkout, review.tree(checkout, anchor))
        finally:
            git(ROOT, 'worktree', 'remove', '--force', str(checkout))
    print('Original schema-3 ' + suite + ' assertions passed', flush=True)
    if suite == 'continuation':
        print('Exercising current schema-4 lifecycle controls', flush=True)
        subprocess.run([sys.executable, '-B', 'morphhdl/scripts/test-increment-59i-local-enable-successor.py', '-v'],
            cwd=ROOT, env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'), check=True, timeout=3600)
        if value['schema_version'] in (5, 6, 7):
            subprocess.run([sys.executable, '-B', 'morphhdl/scripts/test-increment-59i-pr190-integration.py', '-v'],
                cwd=ROOT, env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'), check=True, timeout=3600)
    review.verify(ROOT)


if __name__ == '__main__':
    if json.loads((ROOT / CONTRACT).read_bytes()).get('schema_version') in (4, 5, 6, 7):
        run_schema4_historical_continuation()
    else:
        unittest.main(verbosity=2)
