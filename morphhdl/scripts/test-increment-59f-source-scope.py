#!/usr/bin/env python3
"""Attack exact 59f restoration in isolated worktrees, retaining signed gates."""
from __future__ import annotations

import hashlib
import json
import shutil
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
DRIVER = """import importlib.util, sys
from pathlib import Path
root = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location('reviewed_scope', root / sys.argv[2])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.source_scope(root)
"""


def git(root: Path, *arguments: str) -> str:
    return subprocess.check_output(["git", "-C", str(root), *arguments], text=True,
                                   stderr=subprocess.STDOUT, timeout=120).strip()


def check(root: Path, checker: str, label: str, rejection: str | None = None) -> dict:
    result = subprocess.run([sys.executable, "-c", DRIVER, str(root), checker],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=120, check=False)
    if rejection is None:
        if result.returncode or "PASS" not in result.stdout:
            raise RuntimeError(label + " did not pass:\n" + result.stdout)
    elif not result.returncode or rejection not in result.stdout:
        raise RuntimeError(label + " did not reject for " + rejection + ":\n" + result.stdout)
    print("PASS:", label, "[" + (rejection or "accepted") + "]", flush=True)
    return {"case": label, "checker": checker, "expected_rejection": rejection,
            "exit_code": result.returncode}


def main() -> None:
    head = git(ROOT, "rev-parse", "HEAD")
    has_59d = (ROOT / REVIEW_59D).is_file()
    has_joint_zero = (ROOT / ZERO_59D_59F_CONTRACT).is_file()
    has_joint_padding = (ROOT / PADDING_59D_59F_CONTRACT).is_file()
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
            fallback = (fixture / FALLBACK).read_text()
            boundary = (fixture / BOUNDARY).read_text()
            manifest = (fixture / CONTRACT).read_text()
            data = json.loads(manifest)
            span = next(entry for entry in data["files"] if entry["path"] == FALLBACK)["edits"][0]["after"]
            fallback_rejection = ("unreviewed source change outside 59d/59f padding-owner spans"
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
                 "MORPH-NATIVE-AUDIT-DIRTY-WORKTREE"),
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
                     HELPER, zero_manifest_rejection),
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
                     HELPER, "unreviewed source change outside 59d/59f zero-owner span"),
                    ("joint padding source and manifest changed together", [
                      (FALLBACK, changed_padding_source),
                      (PADDING_59D_59F_CONTRACT, json.dumps(paired_padding, indent=2) + "\n")],
                     HELPER, padding_manifest_rejection),
                ]
            for label, replacements, checker, rejection in mutations:
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
                    records.append(check(fixture, checker, label, rejection))
                finally:
                    for path, original in originals.items():
                        (fixture / path).write_bytes(original)
            records.append(check(fixture, CLOSURE, "restored fixture preserves inherited gates"))
            if has_joint_zero:
                # Rebuild both complete frozen 59f blobs from their historical
                # baseline and reviewed edits. No 59d or joint-zero contract is
                # needed to accept the original 59f publisher profile.
                original_paths = [FALLBACK, BOUNDARY, REVIEW_59D, ZERO_59D_59F_CONTRACT]
                if has_joint_padding:
                    original_paths.append(PADDING_59D_59F_CONTRACT)
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
                    records.append(check(fixture, HELPER, "historical frozen 59f publisher alone"))
                finally:
                    for path, original in originals.items():
                        (fixture / path).write_bytes(original)
        finally:
            git(ROOT, "worktree", "remove", "--force", str(fixture))
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
