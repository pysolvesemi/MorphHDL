#!/usr/bin/env python3
"""Attack exact 59f restoration in isolated worktrees, retaining signed gates."""
from __future__ import annotations

import hashlib
import json
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = "morphhdl/scripts/check-increment-59f-source-scope.py"
CONTRACT = "morphhdl/contracts/increment-59f-publisher-edits.json"
FALLBACK = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
BOUNDARY = "morphhdl/scripts/check-increment-60e-signedness-boundaries.py"
CLOSURE = "morphhdl/scripts/check-increment-60f-equivalence-closure.py"
WIDTH_59D_CONTRACT = "morphhdl/contracts/increment-59d-width-publication-edits.json"
REVIEW_59D = "morphhdl/contracts/increment-59d-production-review.json"
ZERO_59D_59F_CONTRACT = "morphhdl/contracts/increment-59d-59f-zero-edits.json"
PADDING_59D_59F_CONTRACT = "morphhdl/contracts/increment-59d-59f-padding-edits.json"
COMPOSITE_59D_59E_CONTRACT = "morphhdl/contracts/increment-59d-59e-publisher-edits.json"
COMPOSITE_59E_59F_CONTRACT = "morphhdl/contracts/increment-59e-59f-publisher-edits.json"
PRODUCTION_59D_59E_CONTRACT = "morphhdl/contracts/increment-59d-59e-production-edits.json"
PACKING_59D_59E_CONTRACT = "morphhdl/contracts/increment-59d-59e-packing-edits.json"
QUALIFIED_59E = "b25e367d99604e61b8f2c895b2c51ca1ab90d423"
PRE_PACKING_59DE = "538dd4e9a2f0074a27d2fa98dc3e18083725cb87"
DRIVER = """import importlib.util, sys
from pathlib import Path
root = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location('reviewed_scope', root / sys.argv[2])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
expected_sizes = {'reviewed_59d59e_production': 1, 'reviewed_59d59e_packing': 2}
if sys.argv[3] in expected_sizes:
    result = getattr(module, sys.argv[3])(root)
    assert len(result) == expected_sizes[sys.argv[3]], result
    print('PASS: exact reviewed adapter', sys.argv[3])
else:
    assert sys.argv[3] == 'source_scope', sys.argv[3]
    module.source_scope(root)
"""


def git(root: Path, *arguments: str) -> str:
    return subprocess.check_output(["git", "-C", str(root), *arguments], text=True,
                                   stderr=subprocess.STDOUT, timeout=120).strip()


def check(root: Path, checker: str, label: str, rejection: str | None = None,
          entrypoint: str = "source_scope") -> dict:
    result = subprocess.run([sys.executable, "-c", DRIVER, str(root), checker, entrypoint],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=120, check=False)
    if rejection is None:
        if result.returncode or "PASS" not in result.stdout:
            raise RuntimeError(label + " did not pass:\n" + result.stdout)
    elif not result.returncode or rejection not in result.stdout:
        raise RuntimeError(label + " did not reject for " + rejection + ":\n" + result.stdout)
    print("PASS:", label, "[" + (rejection or "accepted") + "]", flush=True)
    return {"case": label, "checker": checker, "entrypoint": entrypoint, "expected_rejection": rejection,
            "exit_code": result.returncode}


def packing_controls(fixture: Path, records: list[dict]) -> None:
    manifest = (fixture / PACKING_59D_59E_CONTRACT).read_text()
    data = json.loads(manifest)
    paths = [PACKING_59D_59E_CONTRACT] + [entry["path"] for entry in data["files"]]
    originals = {path: (fixture / path).read_bytes() for path in paths}
    modes = {path: stat.S_IMODE((fixture / path).stat().st_mode) for path in paths}
    indexes = {path: git(fixture, "ls-files", "--stage", "--", path).split() for path in paths}
    if any(len(value) != 4 or value[2] != "0" for value in indexes.values()):
        raise RuntimeError("packing fixture does not start with uniquely tracked source and review")
    api = "reviewed_59d59e_packing"
    records.append(check(fixture, HELPER, "current composite packing adapter", entrypoint=api))

    def verify(label: str, change, rejection: str, checker: str = HELPER) -> None:
        try:
            change()
            records.append(check(fixture, checker, label, rejection,
                                 entrypoint=api if checker == HELPER else "source_scope"))
        finally:
            for path, original in originals.items():
                target = fixture / path
                if target.is_symlink():
                    target.unlink()
                target.write_bytes(original)
                target.chmod(modes[path])
                mode, blob, _, _ = indexes[path]
                git(fixture, "update-index", "--add", "--cacheinfo", mode + "," + blob + "," + path)

    def write_manifest(value: dict) -> None:
        (fixture / PACKING_59D_59E_CONTRACT).write_text(json.dumps(value, indent=2) + "\n")

    def manifest_mutation(label: str, change) -> None:
        changed = json.loads(manifest)
        change(changed)
        if changed == data:
            raise RuntimeError("packing manifest mutation made no change: " + label)
        verify(label, lambda: write_manifest(changed), "59d/59e reviewed packing manifest changed")

    source_rejection = "59d/59e reviewed packing source changed"
    regular_source = "missing regular non-executable 59d/59e packing source"
    regular_review = "missing regular non-executable 59d/59e packing review"
    with tempfile.TemporaryDirectory(prefix="packing-scope-symlink-") as directory:
        alias = Path(directory) / "original"
        for entry in data["files"]:
            relative = entry["path"]
            target = fixture / relative
            source = originals[relative].decode()
            edit = entry["edits"][0]
            label = "packing " + Path(relative).stem
            token, replacement = (("cat.left = high", "cat.left = low")
                                  if relative.endswith("/ParameterizedVec.scala") else
                                  ("shape.elementLeaves.size > 1", "shape.elementLeaves.size > 0"))
            if edit["after"].count(token) != 1 or source.count(edit["after"]) != 1:
                raise RuntimeError("packing source mutation target differs: " + relative)
            changed_span = edit["after"].replace(token, replacement, 1)
            changed_source = source.replace(edit["after"], changed_span, 1)
            verify(label + " inside span changed", lambda: target.write_text(changed_source), source_rejection)
            verify(label + " outside span changed", lambda: target.write_text(source + "\n// unrelated packing edit\n"),
                   source_rejection)
            verify(label + " span duplicated", lambda: target.write_text(source + edit["after"]), source_rejection)
            verify(label + " span removed", lambda: target.write_text(source.replace(edit["after"], edit["before"], 1)),
                   source_rejection)
            verify(label + " source removed", target.unlink, regular_source)
            alias.write_bytes(originals[relative])

            def link_source() -> None:
                target.unlink()
                target.symlink_to(alias)

            verify(label + " source symlink", link_source, regular_source)
            verify(label + " source executable", lambda: target.chmod(modes[relative] | 0o111), regular_source)
            verify(label + " source untracked", lambda: git(fixture, "update-index", "--force-remove", "--", relative),
                   "59d/59e packing source is not uniquely tracked")
            verify(label + " source executable index", lambda: git(fixture, "update-index", "--chmod=+x", "--", relative),
                   "59d/59e packing source is not uniquely tracked")
            paired = json.loads(manifest)
            paired_entry = next(item for item in paired["files"] if item["path"] == relative)
            paired_entry["edits"][0]["after"] = changed_span
            paired_entry["after_sha256"] = hashlib.sha256(changed_source.encode()).hexdigest()

            def paired_forgery() -> None:
                target.write_text(changed_source)
                write_manifest(paired)

            verify(label + " source and manifest changed together", paired_forgery,
                   "59d/59e reviewed packing manifest changed")
        manifest_mutation("packing manifest baseline changed", lambda value: value.update(base="0" * 40))
        manifest_mutation("packing manifest path removed", lambda value: value["files"].pop())
        manifest_mutation("packing manifest path duplicated", lambda value: value["files"].append(value["files"][0]))
        for index, entry in enumerate(data["files"]):
            label = "packing manifest " + Path(entry["path"]).stem
            for field in ("path", "before_sha256", "after_sha256"):
                replacement = "other/src/main/Unreviewed.scala" if field == "path" else "0" * 64
                manifest_mutation(label + " " + field + " changed",
                                  lambda value: value["files"][index].update({field: replacement}))
            for field in ("id", "before", "after"):
                manifest_mutation(label + " span " + field + " changed", lambda value:
                                  value["files"][index]["edits"][0].update({field: "unreviewed packing span"}))
        target = fixture / PACKING_59D_59E_CONTRACT
        verify("packing manifest removed", target.unlink, regular_review)
        verify("packing manifest removed from current profile", target.unlink,
               "reviewed production source hash differs", CLOSURE)
        alias.write_bytes(originals[PACKING_59D_59E_CONTRACT])

        def link_review(broken: bool = False) -> None:
            target.unlink()
            target.symlink_to(Path(directory) / "missing" if broken else alias)

        verify("packing manifest symlink", link_review, regular_review)
        verify("packing manifest broken symlink", lambda: link_review(True), regular_review)
        verify("packing manifest broken symlink in current profile", lambda: link_review(True), regular_review, CLOSURE)
        verify("packing manifest executable", lambda: target.chmod(modes[PACKING_59D_59E_CONTRACT] | 0o111), regular_review)
        verify("packing manifest untracked", lambda:
               git(fixture, "update-index", "--force-remove", "--", PACKING_59D_59E_CONTRACT),
               "59d/59e packing review is not uniquely tracked")
        verify("packing manifest executable index", lambda:
               git(fixture, "update-index", "--chmod=+x", "--", PACKING_59D_59E_CONTRACT),
               "59d/59e packing review is not uniquely tracked")


def main() -> None:
    head = git(ROOT, "rev-parse", "HEAD")
    rollout_helper = "morphhdl/scripts/check-increment-60g-source-scope.py"
    has_rollout = (ROOT / rollout_helper).is_file()
    has_59d = (ROOT / REVIEW_59D).is_file()
    has_joint_zero = (ROOT / ZERO_59D_59F_CONTRACT).is_file()
    has_joint_padding = (ROOT / PADDING_59D_59F_CONTRACT).is_file()
    has_composite = (ROOT / COMPOSITE_59D_59E_CONTRACT).is_file()
    has_production_adapter = (ROOT / PRODUCTION_59D_59E_CONTRACT).is_file()
    has_packing_adapter = (ROOT / PACKING_59D_59E_CONTRACT).is_file()
    records = [check(ROOT, HELPER, "current exact publisher delta"),
               check(ROOT, CLOSURE, "current full inherited source gates")]
    with tempfile.TemporaryDirectory(prefix="morphhdl-59f-source-scope-") as temporary:
        fixture = Path(temporary) / "fixture"
        git(ROOT, "worktree", "add", "--detach", str(fixture), head)
        try:
            # Copy only this review's files so the test also checks uncommitted
            # source-gate work. No mutation is ever applied to the real checkout.
            for path in (HELPER, CONTRACT, FALLBACK, BOUNDARY, CLOSURE):
                shutil.copyfile(ROOT / path, fixture / path)
            if has_59d:
                for path in (WIDTH_59D_CONTRACT, REVIEW_59D):
                    shutil.copyfile(ROOT / path, fixture / path)
                reviewed = json.loads((ROOT / REVIEW_59D).read_text())
                for entry in reviewed["files"]:
                    target = fixture / entry["path"]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT / entry["path"], target)
            if has_joint_zero:
                shutil.copyfile(ROOT / ZERO_59D_59F_CONTRACT, fixture / ZERO_59D_59F_CONTRACT)
            if has_joint_padding:
                shutil.copyfile(ROOT / PADDING_59D_59F_CONTRACT, fixture / PADDING_59D_59F_CONTRACT)
            if has_composite:
                for path in (COMPOSITE_59D_59E_CONTRACT, COMPOSITE_59E_59F_CONTRACT):
                    shutil.copyfile(ROOT / path, fixture / path)
            if has_production_adapter:
                shutil.copyfile(ROOT / PRODUCTION_59D_59E_CONTRACT, fixture / PRODUCTION_59D_59E_CONTRACT)
                production_review = json.loads((ROOT / PRODUCTION_59D_59E_CONTRACT).read_text())
                for entry in production_review["files"]:
                    target = fixture / entry["path"]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT / entry["path"], target)
                records.append(check(fixture, HELPER, "current composite production adapter",
                                     entrypoint="reviewed_59d59e_production"))
            if has_packing_adapter:
                shutil.copyfile(ROOT / PACKING_59D_59E_CONTRACT, fixture / PACKING_59D_59E_CONTRACT)
                packing_review = json.loads((ROOT / PACKING_59D_59E_CONTRACT).read_text())
                for entry in packing_review["files"]:
                    shutil.copyfile(ROOT / entry["path"], fixture / entry["path"])
                git(fixture, "add", "-f", "--", PACKING_59D_59E_CONTRACT)
            fallback = (fixture / FALLBACK).read_text()
            boundary = (fixture / BOUNDARY).read_text()
            manifest = (fixture / CONTRACT).read_text()
            data = json.loads(manifest)
            span = next(entry for entry in data["files"] if entry["path"] == FALLBACK)["edits"][0]["after"]
            fallback_rejection = ("unreviewed source change outside 59d/59e publisher spans"
                                  if has_composite else
                                  "unreviewed source change outside 59d/59f padding-owner spans"
                                  if has_joint_padding else
                                  "unreviewed source change outside 59d/59f zero-owner span"
                                  if has_joint_zero else "unreviewed source change outside 59f spans")
            cases = (
                ("unrelated fallback addition", FALLBACK, fallback + "\n// unrelated mutation\n",
                 CLOSURE, fallback_rejection),
                ("reviewed zero proof changed", FALLBACK,
                 fallback.replace("literal.getValue() == 0", "literal.getValue() == 1", 1),
                 CLOSURE, fallback_rejection),
                ("duplicate reviewed span", FALLBACK, fallback + span,
                 HELPER, fallback_rejection),
                ("unrelated sealed checker addition", BOUNDARY, boundary + "\n# unrelated mutation\n",
                 CLOSURE, "unreviewed source change outside 59f spans"),
                ("restoration hook removed", BOUNDARY,
                 boundary.replace("source = module.restore_59f_source(root, path, source)", "source = source", 1),
                 CLOSURE, ("missing/duplicate reviewed 59d checker restoration span" if has_59d else
                           "unreviewed source change outside 59f spans")),
                ("manifest baseline changed", CONTRACT,
                 manifest.replace(data["base"], "0" * 40, 1),
                 HELPER, "59f reviewed publisher manifest changed"),
                ("manifest path broadened", CONTRACT, manifest.replace(FALLBACK, "other/src/main/Unreviewed.scala", 1),
                 HELPER, "59f reviewed publisher manifest changed"),
                ("manifest span changed", CONTRACT, manifest.replace("literal.getValue() == 0", "literal.getValue() == 1", 1),
                 HELPER, "59f reviewed publisher manifest changed"),
                ("missing manifest", CONTRACT, None, CLOSURE, "FileNotFoundError"),
                ("missing restoration helper", HELPER, None, CLOSURE, "sealed writer/checker changed"),
                ("changed independent oracle", "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala",
                 "// independent oracle was replaced\n", CLOSURE, "sealed writer/checker changed"),
                ("dirty native signed hook", "core/src/main/scala/spinal/core/internals/VerilogBase.scala",
                 "// native signed hook was replaced\n", CLOSURE,
                 "native signed declaration/cast hooks changed after their frozen qualification"),
            )
            mutations = [(label, [(path, replacement)], checker, rejection)
                         for label, path, replacement, checker, rejection in cases]
            if has_59d:
                width_manifest = (fixture / WIDTH_59D_CONTRACT).read_text()
                width_data = json.loads(width_manifest)
                width_span = next(edit for edit in width_data["edits"]
                                  if edit["id"] == "native-resize-published-width-validation")["after"]
                changed_width_span = width_span.replace("validatePublishedWidths", "changedPublishedWidths", 1)
                changed_width_data = json.loads(width_manifest)
                next(edit for edit in changed_width_data["edits"]
                     if edit["id"] == "native-resize-published-width-validation")["after"] = changed_width_span
                review_text = (fixture / REVIEW_59D).read_text()
                review_data = json.loads(review_text)
                checker_span = review_data["checker_edits"][0]["after"]
                changed_checker_span = checker_span.replace("if width_contract.is_file()", "if False", 1)
                changed_review = json.loads(review_text)
                changed_review["checker_edits"][0]["after"] = changed_checker_span
                mutations += [
                    ("59d width publication seam changed", [(FALLBACK,
                      fallback.replace(width_span, changed_width_span, 1))],
                     HELPER, "missing/duplicate reviewed 59d restoration span"),
                    ("59d width publication seam duplicated", [(FALLBACK, fallback + width_span)],
                     HELPER, "missing/duplicate reviewed 59d restoration span"),
                    ("59d restoration contract changed", [(WIDTH_59D_CONTRACT,
                      width_manifest.replace(width_data["base"], "0" * 40, 1))],
                     HELPER, "59d reviewed width publication restoration changed"),
                    ("59d source and width contract changed together", [
                      (FALLBACK, fallback.replace(width_span, changed_width_span, 1)),
                      (WIDTH_59D_CONTRACT, json.dumps(changed_width_data, indent=2) + "\n")],
                     HELPER, "59d reviewed width publication restoration changed"),
                    ("59d width restoration contract removed", [(WIDTH_59D_CONTRACT, None)],
                     HELPER, "missing regular 59d width publication restoration contract"),
                    ("59d checker restoration seam changed", [(BOUNDARY,
                      boundary.replace(checker_span, changed_checker_span, 1))],
                     HELPER, "missing/duplicate reviewed 59d restoration span"),
                    ("59d checker restoration contract changed", [(REVIEW_59D,
                      review_text.replace("restore-exact-59d-width-seams", "unreviewed-width-seams", 1))],
                     HELPER, "59d reviewed checker restoration changed"),
                    ("59d source and checker contract changed together", [
                      (BOUNDARY, boundary.replace(checker_span, changed_checker_span, 1)),
                      (REVIEW_59D, json.dumps(changed_review, indent=2) + "\n")],
                     HELPER, "59d reviewed checker restoration changed"),
                    ("59d source profile hidden", [(REVIEW_59D, None)],
                     HELPER, "unreviewed source change outside 59f spans"),
                ]
            if has_joint_zero:
                zero_manifest = (fixture / ZERO_59D_59F_CONTRACT).read_text()
                zero_data = json.loads(zero_manifest)
                zero_entry = zero_data["files"][0]
                zero_span = zero_entry["edits"][0]["after"]
                changed_zero_span = zero_span.replace(
                    "NativePublicationWidth.validate(width, component, target,",
                    "NativePublicationWidth.validate(width, component, literal,", 1)
                changed_zero_source = fallback.replace(zero_span, changed_zero_span, 1)
                paired_zero = json.loads(zero_manifest)
                paired_zero["files"][0]["edits"][0]["after"] = changed_zero_span
                # Make the forged manifest internally consistent with the
                # forged source after removing exactly the unchanged 59d spans.
                # Its raw manifest seal must still reject the coordinated edit.
                projected_zero_source = changed_zero_source
                for edit in reversed(width_data["edits"]):
                    if projected_zero_source.count(edit["after"]) != 1:
                        raise RuntimeError("joint zero fixture cannot restore exact 59d width span")
                    projected_zero_source = projected_zero_source.replace(edit["after"], edit["before"], 1)
                paired_zero["files"][0]["after_sha256"] = hashlib.sha256(
                    projected_zero_source.encode()).hexdigest()
                zero_manifest_rejection = "59d/59f reviewed zero-owner manifest changed"
                mutations += [
                    ("joint zero declaration owner changed", [(FALLBACK, changed_zero_source)],
                     HELPER, fallback_rejection),
                    ("joint zero owner span duplicated", [(FALLBACK, fallback + zero_span)],
                     HELPER, fallback_rejection),
                    ("joint zero manifest baseline changed", [(ZERO_59D_59F_CONTRACT,
                      zero_manifest.replace(zero_data["base"], "0" * 40, 1))],
                     HELPER, zero_manifest_rejection),
                    ("joint zero manifest before hash changed", [(ZERO_59D_59F_CONTRACT,
                      zero_manifest.replace(zero_entry["before_sha256"], "0" * 64, 1))],
                     HELPER, zero_manifest_rejection),
                    ("joint zero manifest after hash changed", [(ZERO_59D_59F_CONTRACT,
                      zero_manifest.replace(zero_entry["after_sha256"], "0" * 64, 1))],
                     HELPER, zero_manifest_rejection),
                    ("joint zero manifest path broadened", [(ZERO_59D_59F_CONTRACT,
                      zero_manifest.replace(FALLBACK, "other/src/main/Unreviewed.scala", 1))],
                     HELPER, zero_manifest_rejection),
                    ("joint zero manifest before span changed", [(ZERO_59D_59F_CONTRACT,
                      zero_manifest.replace("validateWidthDomain", "bypassWidthDomain", 1))],
                     HELPER, zero_manifest_rejection),
                    ("joint zero manifest after span changed", [(ZERO_59D_59F_CONTRACT,
                      zero_manifest.replace("NativePublicationWidth.validate", "NativePublicationWidth.bypass", 1))],
                     HELPER, zero_manifest_rejection),
                    ("joint zero manifest span identity changed", [(ZERO_59D_59F_CONTRACT,
                      zero_manifest.replace("native-zero-width-owner", "unreviewed-zero-owner", 1))],
                     HELPER, zero_manifest_rejection),
                    ("joint zero manifest removed", [(ZERO_59D_59F_CONTRACT, None)],
                     HELPER, "unreviewed source change outside 59f spans"),
                    ("joint zero source and manifest changed together", [
                      (FALLBACK, changed_zero_source),
                      (ZERO_59D_59F_CONTRACT, json.dumps(paired_zero, indent=2) + "\n")],
                     HELPER, fallback_rejection if has_composite else zero_manifest_rejection),
                ]
            if has_joint_padding:
                padding_manifest = (fixture / PADDING_59D_59F_CONTRACT).read_text()
                padding_data = json.loads(padding_manifest)
                padding_entry = padding_data["files"][0]
                padding_edits = padding_entry["edits"]
                signature_edit = next(edit for edit in padding_edits
                                      if "private def exactUnsignedResizePadding(" in edit["after"])
                owner_edit = next(edit for edit in padding_edits
                                  if "nonNegativeDifferenceAtOwners" in edit["after"])
                call_edit = next(edit for edit in padding_edits
                                 if "val symbolicPadding = exactUnsignedResizePadding(" in edit["after"])
                changed_owner_span = owner_edit["after"].replace(
                    "width, sourceDeclaration, component)", "width, targetDeclaration, component)", 1)
                changed_padding_source = fallback.replace(owner_edit["after"], changed_owner_span, 1)
                paired_padding = json.loads(padding_manifest)
                next(edit for edit in paired_padding["files"][0]["edits"]
                     if edit["id"] == owner_edit["id"])["after"] = changed_owner_span
                projected_padding_source = changed_padding_source
                for edit in reversed(width_data["edits"]):
                    if projected_padding_source.count(edit["after"]) != 1:
                        raise RuntimeError("padding fixture cannot restore exact 59d width span")
                    projected_padding_source = projected_padding_source.replace(edit["after"], edit["before"], 1)
                paired_padding["files"][0]["after_sha256"] = hashlib.sha256(
                    projected_padding_source.encode()).hexdigest()
                padding_manifest_rejection = "59d/59f reviewed padding-owner manifest changed"
                mutations += [
                    ("joint padding owner signature changed", [(FALLBACK, fallback.replace(
                      signature_edit["after"], signature_edit["after"].replace(
                          "targetDeclaration: BitVector", "ignoredTargetDeclaration: BitVector", 1), 1))],
                     HELPER, fallback_rejection),
                    ("joint padding source owner changed", [(FALLBACK, changed_padding_source)],
                     HELPER, fallback_rejection),
                    ("joint padding authority method changed", [(FALLBACK, fallback.replace(
                      "NativePublicationWidth.nonNegativeDifferenceAtOwners",
                      "NativePublicationWidth.unreviewedDifferenceAtOwners", 1))],
                     HELPER, fallback_rejection),
                    ("joint padding call owner removed", [(FALLBACK, fallback.replace(
                      call_edit["after"], call_edit["after"].replace("record.target", "null", 1), 1))],
                     HELPER, fallback_rejection),
                    ("joint padding owner span duplicated", [(FALLBACK, fallback + owner_edit["after"])],
                     HELPER, fallback_rejection),
                    ("joint padding manifest baseline changed", [(PADDING_59D_59F_CONTRACT,
                      padding_manifest.replace(padding_data["base"], "0" * 40, 1))],
                     HELPER, padding_manifest_rejection),
                    ("joint padding prior review changed", [(PADDING_59D_59F_CONTRACT,
                      padding_manifest.replace(padding_data["prior_review_sha256"], "0" * 64, 1))],
                     HELPER, padding_manifest_rejection),
                    ("joint padding manifest before hash changed", [(PADDING_59D_59F_CONTRACT,
                      padding_manifest.replace(padding_entry["before_sha256"], "0" * 64, 1))],
                     HELPER, padding_manifest_rejection),
                    ("joint padding manifest after hash changed", [(PADDING_59D_59F_CONTRACT,
                      padding_manifest.replace(padding_entry["after_sha256"], "0" * 64, 1))],
                     HELPER, padding_manifest_rejection),
                    ("joint padding manifest path broadened", [(PADDING_59D_59F_CONTRACT,
                      padding_manifest.replace(FALLBACK, "other/src/main/Unreviewed.scala", 1))],
                     HELPER, padding_manifest_rejection),
                    ("joint padding manifest before span changed", [(PADDING_59D_59F_CONTRACT,
                      padding_manifest.replace("ElabInt.requireAuthoritativeIntegerDomain",
                                               "ElabInt.bypassAuthoritativeIntegerDomain", 1))],
                     HELPER, padding_manifest_rejection),
                    ("joint padding manifest after span changed", [(PADDING_59D_59F_CONTRACT,
                      padding_manifest.replace("nonNegativeDifferenceAtOwners", "unreviewedDifferenceAtOwners", 1))],
                     HELPER, padding_manifest_rejection),
                    ("joint padding manifest span identity changed", [(PADDING_59D_59F_CONTRACT,
                      padding_manifest.replace(owner_edit["id"], "unreviewed-padding-owner", 1))],
                     HELPER, padding_manifest_rejection),
                    ("joint padding manifest removed", [(PADDING_59D_59F_CONTRACT, None)],
                     HELPER, ("missing regular 59d/59f padding-owner review" if has_composite else
                              "unreviewed source change outside 59d/59f zero-owner span")),
                    ("joint padding source and manifest changed together", [
                      (FALLBACK, changed_padding_source),
                      (PADDING_59D_59F_CONTRACT, json.dumps(paired_padding, indent=2) + "\n")],
                     HELPER, padding_manifest_rejection),
                ]
            if has_composite:
                composite_manifest = (fixture / COMPOSITE_59D_59E_CONTRACT).read_text()
                composite_data = json.loads(composite_manifest)
                composite_entry = composite_data["files"][0]
                composite_edits = composite_entry["edits"]
                changes = {
                    "packed-read-support-identities":
                        ("retained.put(assignment.finalTarget,", "retained.put(null,"),
                    "packed-read-support-assignment-evidence":
                        ("retained.put(assignment,", "retained.put(null,"),
                    "packed-read-independent-owner-roots":
                        ("exactPackedReadSupportTargets.containsKey(declaration)", "true"),
                    "recursive-packed-element-geometry":
                        ("widthMultiply(geometry(left), geometry(right))", "widthAdd(geometry(left), geometry(right))"),
                }
                for edit in composite_edits:
                    before, after = changes[edit["id"]]
                    if edit["after"].count(before) != 1:
                        raise RuntimeError("composite publisher mutation target differs: " + edit["id"])
                    changed_span = edit["after"].replace(before, after, 1)
                    mutations.append(("composite " + edit["id"] + " changed",
                                      [(FALLBACK, fallback.replace(edit["after"], changed_span, 1))],
                                      HELPER, fallback_rejection))
                owner_edit = next(edit for edit in composite_edits
                                  if edit["id"] == "packed-read-independent-owner-roots")
                changed_owner_span = owner_edit["after"].replace(*changes[owner_edit["id"]], 1)
                changed_composite_source = fallback.replace(owner_edit["after"], changed_owner_span, 1)
                paired_composite = json.loads(composite_manifest)
                next(edit for edit in paired_composite["files"][0]["edits"]
                     if edit["id"] == owner_edit["id"])["after"] = changed_owner_span
                projected_composite_source = changed_composite_source
                for edit in reversed(width_data["edits"]):
                    if projected_composite_source.count(edit["after"]) != 1:
                        raise RuntimeError("composite fixture cannot restore exact 59d width span")
                    projected_composite_source = projected_composite_source.replace(edit["after"], edit["before"], 1)
                paired_composite["files"][0]["after_sha256"] = hashlib.sha256(
                    projected_composite_source.encode()).hexdigest()
                changed_before = json.loads(composite_manifest)
                changed_before["files"][0]["edits"][0]["before"] += "\n// unreviewed baseline span\n"
                changed_after = json.loads(composite_manifest)
                changed_after["files"][0]["edits"][0]["after"] += "\n// unreviewed successor span\n"
                historical_manifest = (fixture / COMPOSITE_59E_59F_CONTRACT).read_text()
                manifest_rejection = "59d/59e reviewed publisher manifest changed"
                mutations += [
                    ("composite publisher span duplicated", [(FALLBACK, fallback + owner_edit["after"])],
                     HELPER, fallback_rejection),
                    ("composite manifest baseline changed", [(COMPOSITE_59D_59E_CONTRACT,
                      composite_manifest.replace(composite_data["base"], "0" * 40, 1))], HELPER, manifest_rejection),
                    ("composite qualified history changed", [(COMPOSITE_59D_59E_CONTRACT,
                      composite_manifest.replace(composite_data["qualified_composite"], "0" * 40, 1))],
                     HELPER, manifest_rejection),
                    ("composite historical publisher pin changed", [(COMPOSITE_59D_59E_CONTRACT,
                      composite_manifest.replace(composite_data["historical_publisher_sha256"], "0" * 64, 1))],
                     HELPER, manifest_rejection),
                    ("composite manifest before hash changed", [(COMPOSITE_59D_59E_CONTRACT,
                      composite_manifest.replace(composite_entry["before_sha256"], "0" * 64, 1))],
                     HELPER, manifest_rejection),
                    ("composite manifest after hash changed", [(COMPOSITE_59D_59E_CONTRACT,
                      composite_manifest.replace(composite_entry["after_sha256"], "0" * 64, 1))],
                     HELPER, manifest_rejection),
                    ("composite manifest path broadened", [(COMPOSITE_59D_59E_CONTRACT,
                      composite_manifest.replace(FALLBACK, "other/src/main/Unreviewed.scala", 1))],
                     HELPER, manifest_rejection),
                    ("composite manifest span identity changed", [(COMPOSITE_59D_59E_CONTRACT,
                      composite_manifest.replace(owner_edit["id"], "unreviewed-composite-owner", 1))],
                     HELPER, manifest_rejection),
                    ("composite manifest before span changed", [(COMPOSITE_59D_59E_CONTRACT,
                      json.dumps(changed_before, indent=2) + "\n")], HELPER, manifest_rejection),
                    ("composite manifest after span changed", [(COMPOSITE_59D_59E_CONTRACT,
                      json.dumps(changed_after, indent=2) + "\n")], HELPER, manifest_rejection),
                    ("frozen composite publisher manifest changed", [(COMPOSITE_59E_59F_CONTRACT,
                      historical_manifest + "\n")], HELPER, "frozen 59e/59f publisher manifest changed"),
                    ("frozen composite publisher manifest removed", [(COMPOSITE_59E_59F_CONTRACT, None)],
                     HELPER, "missing regular frozen 59e/59f publisher review"),
                    ("composite publisher adapter removed", [(COMPOSITE_59D_59E_CONTRACT, None)],
                     HELPER, "unreviewed source change outside 59d/59f padding-owner spans"),
                    ("composite publisher source and manifest changed together", [
                      (FALLBACK, changed_composite_source),
                      (COMPOSITE_59D_59E_CONTRACT, json.dumps(paired_composite, indent=2) + "\n")],
                     HELPER, manifest_rejection),
                ]
            if has_production_adapter:
                production_manifest = (fixture / PRODUCTION_59D_59E_CONTRACT).read_text()
                production_data = json.loads(production_manifest)
                production_entry = production_data["files"][0]
                production_path = production_entry["path"]
                production_source = (fixture / production_path).read_text()
                production_edit = production_entry["edits"][0]
                if production_source.count(production_edit["after"]) != 1:
                    raise RuntimeError("composite production fixture cannot locate exact policy span")
                resized_source = production_source.replace(production_edit["after"], production_edit["before"], 1)
                paired_production = json.loads(production_manifest)
                paired_production["files"][0]["edits"][0]["after"] = production_edit["before"]
                paired_production["files"][0]["after_sha256"] = hashlib.sha256(resized_source.encode()).hexdigest()
                changed_before = json.loads(production_manifest)
                changed_before["files"][0]["edits"][0]["before"] += "\n// unreviewed original policy\n"
                changed_after = json.loads(production_manifest)
                changed_after["files"][0]["edits"][0]["after"] += "\n// unreviewed successor policy\n"
                source_rejection = "59d/59e reviewed production source changed"
                manifest_rejection = "59d/59e reviewed production manifest changed"
                production_cases = [
                    ("composite production scalar resized reenabled", [(production_path, resized_source)], source_rejection),
                    ("composite production unrelated source addition", [(production_path,
                      production_source + "\n// unreviewed policy addition\n")], source_rejection),
                    ("composite production manifest baseline changed", [(PRODUCTION_59D_59E_CONTRACT,
                      production_manifest.replace(production_data["base"], "0" * 40, 1))], manifest_rejection),
                    ("composite production manifest path broadened", [(PRODUCTION_59D_59E_CONTRACT,
                      production_manifest.replace(production_path, "other/src/main/Unreviewed.scala", 1))], manifest_rejection),
                    ("composite production manifest before hash changed", [(PRODUCTION_59D_59E_CONTRACT,
                      production_manifest.replace(production_entry["before_sha256"], "0" * 64, 1))], manifest_rejection),
                    ("composite production manifest after hash changed", [(PRODUCTION_59D_59E_CONTRACT,
                      production_manifest.replace(production_entry["after_sha256"], "0" * 64, 1))], manifest_rejection),
                    ("composite production manifest before span changed", [(PRODUCTION_59D_59E_CONTRACT,
                      json.dumps(changed_before, indent=2) + "\n")], manifest_rejection),
                    ("composite production manifest after span changed", [(PRODUCTION_59D_59E_CONTRACT,
                      json.dumps(changed_after, indent=2) + "\n")], manifest_rejection),
                    ("composite production manifest span identity changed", [(PRODUCTION_59D_59E_CONTRACT,
                      production_manifest.replace(production_edit["id"], "unreviewed-policy-span", 1))], manifest_rejection),
                    ("composite production source and manifest changed together", [
                      (production_path, resized_source),
                      (PRODUCTION_59D_59E_CONTRACT, json.dumps(paired_production, indent=2) + "\n")], manifest_rejection),
                    ("composite production manifest removed", [(PRODUCTION_59D_59E_CONTRACT, None)],
                     "missing regular 59d/59e production review"),
                    ("composite production source removed", [(production_path, None)],
                     "missing regular 59d/59e production source"),
                ]
                mutations += [(label, replacements, HELPER, rejection, "reviewed_59d59e_production")
                              for label, replacements, rejection in production_cases]
            for label, replacements, checker, rejection, *entrypoints in mutations:
                # 60g's reviewed whole-checker seal is intentionally outside
                # the inherited 59d seam guard. These exact mutations must now
                # fail there first; do not weaken them to any failed command.
                if has_rollout and label in ("restoration hook removed",
                        "59d checker restoration seam changed",
                        "59d source and checker contract changed together"):
                    rejection = "or 60g publication spans: " + BOUNDARY
                originals = {path: (fixture / path).read_bytes() for path, _ in replacements}
                try:
                    for path, replacement in replacements:
                        target = fixture / path
                        if replacement is not None and replacement.encode() == originals[path]:
                            raise RuntimeError("mutation did not change source: " + label)
                        if replacement is None:
                            target.unlink()
                        else:
                            target.write_text(replacement)
                    records.append(check(fixture, checker, label, rejection,
                                         entrypoint=entrypoints[0] if entrypoints else "source_scope"))
                finally:
                    for path, original in originals.items():
                        (fixture / path).write_bytes(original)
            if has_packing_adapter:
                packing_controls(fixture, records)
            # Commit the same outside-span mutation so a dirty-worktree check
            # cannot substitute for the exact current publisher restoration.
            (fixture / FALLBACK).write_text(fallback + "\n// committed unrelated mutation\n")
            try:
                git(fixture, "add", "--", FALLBACK)
                git(fixture, "-c", "user.name=Scope guard fixture", "-c",
                    "user.email=scope-fixture@example.invalid", "commit", "--no-verify",
                    "-m", "isolated committed publisher mutation")
                records.append(check(fixture, CLOSURE, "committed unrelated publisher mutation",
                                     fallback_rejection))
            finally:
                git(fixture, "reset", "--mixed", head)
                (fixture / FALLBACK).write_text(fallback)
                if has_packing_adapter:
                    git(fixture, "add", "-f", "--", PACKING_59D_59E_CONTRACT)
            records.append(check(fixture, CLOSURE, "restored fixture preserves inherited gates"))
            if has_joint_zero:
                # Rebuild both complete frozen 59f blobs from their historical
                # baseline and reviewed edits. No 59d or joint-zero contract is
                # needed to accept the original 59f publisher profile.
                original_paths = [FALLBACK, BOUNDARY, REVIEW_59D, ZERO_59D_59F_CONTRACT]
                if has_joint_padding:
                    original_paths.append(PADDING_59D_59F_CONTRACT)
                if has_rollout:
                    # This positive control explicitly reconstructs pre-59d
                    # 59f alone. Its historical source is not a 60g profile;
                    # restore the current helper in the finally block below.
                    original_paths.append(rollout_helper)
                originals = {path: (fixture / path).read_bytes() for path in original_paths}
                try:
                    for entry in data["files"]:
                        source = subprocess.check_output(
                            ["git", "show", data["base"] + ":" + entry["path"]],
                            cwd=fixture, text=True)
                        if hashlib.sha256(source.encode()).hexdigest() != entry["before_sha256"]:
                            raise RuntimeError("historical 59f source fixture baseline differs")
                        for edit in entry["edits"]:
                            if source.count(edit["before"]) != 1:
                                raise RuntimeError("historical 59f source fixture span differs")
                            source = source.replace(edit["before"], edit["after"], 1)
                        if hashlib.sha256(source.encode()).hexdigest() != entry["after_sha256"]:
                            raise RuntimeError("historical 59f source fixture publication differs")
                        (fixture / entry["path"]).write_text(source)
                    (fixture / REVIEW_59D).unlink()
                    (fixture / ZERO_59D_59F_CONTRACT).unlink()
                    if has_joint_padding:
                        (fixture / PADDING_59D_59F_CONTRACT).unlink()
                    if has_rollout:
                        (fixture / rollout_helper).unlink()
                    records.append(check(fixture, HELPER, "historical frozen 59f publisher alone"))
                finally:
                    for path, original in originals.items():
                        (fixture / path).write_bytes(original)
        finally:
            git(ROOT, "worktree", "remove", "--force", str(fixture))
        historical = Path(temporary) / "historical-59e"
        git(ROOT, "worktree", "add", "--detach", str(historical), QUALIFIED_59E)
        try:
            records.append(check(historical, HELPER, "historical exact 59e publisher"))
            source = (historical / FALLBACK).read_text()
            before = "val owner = ParameterizedStructure.exactAssignmentDomainOf("
            if source.count(before) != 1:
                raise RuntimeError("historical 59e assignment-owner padding proof is missing")
            (historical / FALLBACK).write_text(source.replace(
                before, "val owner = ParameterizedStructure.unreviewedAssignmentDomainOf(", 1))
            records.append(check(historical, HELPER, "assignment-owner padding proof changed",
                                 "unreviewed source change outside 59f spans"))
        finally:
            git(ROOT, "worktree", "remove", "--force", str(historical))
        if has_packing_adapter:
            historical = Path(temporary) / "historical-pre-packing"
            git(ROOT, "worktree", "add", "--detach", str(historical), PRE_PACKING_59DE)
            try:
                records.append(check(historical, str(ROOT / CLOSURE),
                                     "historical pre-packing source through current gate"))
            finally:
                git(ROOT, "worktree", "remove", "--force", str(historical))
    if git(ROOT, "rev-parse", "HEAD") != head:
        raise RuntimeError("source-scope fixtures changed the real checkout HEAD")
    output = ROOT / "target/increment-59f/source-scope"
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").write_text(json.dumps({"head": head, "cases": records}, indent=2) + "\n")
    positives = sum(record["expected_rejection"] is None for record in records)
    print("PASS: " + str(positives) + " positive and " + str(len(records) - positives) +
          " exact negative 59f source-scope controls", flush=True)


if __name__ == "__main__":
    main()
