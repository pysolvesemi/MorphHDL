#!/usr/bin/env python3
"""Mutation controls for the fixed publication ABI and reviewed golden checker."""
from __future__ import annotations

import copy
import importlib.util
import json
import re
from pathlib import Path
import tempfile
import unittest

SOURCE = Path(__file__).with_name("check-increment-59i-publication-golden.py")
SPEC = importlib.util.spec_from_file_location("publication_golden_controls", SOURCE)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("missing publication golden checker")
G = importlib.util.module_from_spec(SPEC)
exec(compile(SOURCE.read_bytes(), str(SOURCE), "exec"), G.__dict__)


def module(name, mode=0, body="  assign result = records;\n", parameters=True, signed=False, width="WIDTH"):
    params = ""
    if parameters:
        values = (5, 3, 7, 5, mode)
        params = " #(\n" + ",\n".join("  parameter integer " + name + " = " + str(value)
            for name, value in zip(G.FORMALS, values)) + "\n)"
    return ("module " + name + params + " (\n"
        "  input wire clk,\n"
        "  input wire [" + width + "-1:0] records,\n"
        "  output wire [" + width + "-1:0] result,\n"
        "  output wire " + ("signed " if signed else "") + "[" + width + "-1:0] signedSelected_real,\n"
        "  output wire " + ("signed " if signed else "") + "[" + width + "-1:0] signedSelected_imag\n);\n" +
        body + "endmodule\n")


def fixture_rtl(metadata):
    kind = metadata["kind"]
    if kind == "concrete":
        header = "// Generator : SpinalHDL v1.13.0    git head : " + "a" * 40 + "\n"
        header += "// Component : PublicationGoldenNative\n// Git hash  : " + "b" * 40 + "\n\n"
        width = "5" if metadata["default_count"] == 1 else "1"
        return (header + "`timescale 1ns/1ps\n" + module(metadata["module"], parameters=False, width=width)).encode()
    signed = metadata["signed_mode"] != "legacy"
    body = "  assign result = records;\n"
    if metadata["layout"] == "fields":
        # Scalar signed operands need native wrappers in legacy/declaration-only
        # modes. Cleanup removes those wrappers but keeps the transport boundary.
        left, right = "signed_left", "signed_right"
        if metadata["signed_mode"] in ("legacy", "declarations"):
            left, right = "$signed(" + left + ")", "$signed(" + right + ")"
        body += "  wire " + ("signed " if signed else "") + "[WIDTH-1:0] signed_left, signed_right;\n"
        body += "  assign signed_left = records;\n  assign signed_right = records;\n"
        body += "  assign signedSelected_real = (" + left + " <= " + right + ") ? signed_left : signed_right;\n"
        body += "  assign signedSelected_imag = $signed(records);\n"
    if kind == "child":
        bindings = ",\n".join("    ." + name + "(" + ("((MODE + 1))" if name == "MODE" else name) + ")"
            for name in G.FORMALS)
        body = "  PublicationGoldenChild #(\n" + bindings + "\n  ) child (.clk(clk), .records(records), .result(result));\n"
        return (module(metadata["module"], body=body) + "\n" + module("PublicationGoldenChild", mode=1)).encode()
    return module(metadata["module"], body=body, signed=signed).encode()


class PublicationGoldenTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="59i-publication-golden-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.a, self.b = self.base / "a", self.base / "b"
        self.contract = self.base / "synthetic-reviewed-contract.json"
        self.metadata = G.expected_metadata()
        self.original = {item["file"]: fixture_rtl(item) for item in self.metadata}
        self.manifest = {"schema": 1, "scope": G.SCOPE, "profiles": self.metadata}
        self.manifest_raw = G.serialized(self.manifest)
        for root in (self.a, self.b):
            root.mkdir()
            (root / "manifest.json").write_bytes(self.manifest_raw)
            for relative, raw in self.original.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
        self.snapshot, _ = G.paired(self.a, self.b)
        self.contract.write_bytes(G.serialized(self.snapshot))

    def reject(self, action=None, detail="59i publication golden"):
        with self.assertRaisesRegex(RuntimeError, detail):
            (action or (lambda: G.check(self.a, self.b, self.contract)))()

    def path(self, profile, root=None):
        metadata = next(item for item in self.metadata if item["profile"] == profile)
        return (root or self.a) / metadata["file"]

    def change_both(self, profile, transform):
        for root in (self.a, self.b):
            path = self.path(profile, root)
            path.write_bytes(transform(path.read_bytes()))

    def test_complete_abi_and_raw_emissions_match_reviewed_contract(self):
        evidence = G.check(self.a, self.b, self.contract)
        self.assertEqual(evidence["profile_count"], 10)
        self.assertEqual(evidence["profiles"], [profile[0] for profile in G.PROFILES])
        self.assertEqual(evidence["runs"]["a"], evidence["runs"]["b"])
        self.assertEqual(len(evidence["runs"]["a"]["raw_sha256"]), 10)
        child = next(item for item in evidence["snapshot"]["profiles"] if item["profile"] == "child-packed")
        self.assertEqual([item["formal"] for item in child["child_bindings"]], list(G.FORMALS))
        self.assertEqual(len(child["modules"]), 2)
        self.assertEqual(child["modules"][0]["ports"][1]["packed_dimensions"], ["[WIDTH-1:0]"])
        output = self.a / "evidence.json"
        G.output_file(output, evidence, (self.a, self.b), self.contract)
        self.assertEqual(G.check(self.a, self.b, self.contract), evidence)

    def test_missing_expectation_fails_closed_and_snapshot_cannot_rewrite_it(self):
        self.contract.unlink()
        self.reject()
        candidate = self.base / "unreviewed-candidate.json"
        snapshot, _ = G.paired(self.a, self.b)
        G.output_file(candidate, snapshot, (self.a, self.b), None, candidate=True)
        self.assertFalse(self.contract.exists())
        self.reject(lambda: G.output_file(candidate, snapshot, (self.a, self.b), None, candidate=True))
        self.reject(lambda: G.output_file(G.DEFAULT_CONTRACT, snapshot, (self.a, self.b), None, candidate=True))
        self.contract.write_bytes(G.serialized(snapshot))
        self.reject(lambda: G.output_file(self.contract, snapshot, (self.a, self.b), self.contract))
        self.reject(lambda: G.output_file(self.a / "manifest.json", snapshot, (self.a, self.b), None))

    def test_fixed_manifest_profile_schema_order_and_metadata_mutations_reject(self):
        for mutation in ("unknown", "missing", "duplicate", "order", "path", "absolute", "layout", "signed", "count-bool", "extra-key"):
            with self.subTest(mutation=mutation):
                value = copy.deepcopy(self.manifest)
                items = value["profiles"]
                if mutation == "unknown": items[0]["profile"] = "unknown"
                elif mutation == "missing": items.pop()
                elif mutation == "duplicate": items.append(copy.deepcopy(items[0]))
                elif mutation == "order": items.reverse()
                elif mutation == "path": items[0]["file"] = "../outside.v"
                elif mutation == "absolute": items[0]["file"] = "/outside.v"
                elif mutation == "layout": items[0]["layout"] = "fields"
                elif mutation == "signed": items[0]["signed_mode"] = "legacy"
                elif mutation == "count-bool": items[-2]["default_count"] = True
                else: items[0]["extra"] = "unreviewed"
                (self.a / "manifest.json").write_bytes(G.serialized(value))
                self.reject()
                (self.a / "manifest.json").write_bytes(self.manifest_raw)
        raw = self.manifest_raw.replace(b'"schema": 1,', b'"schema": 1, "schema": 1,')
        (self.a / "manifest.json").write_bytes(raw)
        self.reject(detail="duplicate JSON key")

    def test_missing_extra_linked_and_executable_artifacts_reject(self):
        path = self.path("fields")
        original = path.read_bytes()
        path.unlink()
        self.reject()
        path.write_bytes(original)
        extra = self.a / "extra.v"
        extra.write_bytes(b"module Extra(); endmodule\n")
        self.reject()
        extra.unlink()
        path.unlink()
        path.symlink_to(self.path("fields", self.b))
        self.reject()
        path.unlink()
        path.write_bytes(original)
        path.chmod(0o755)
        self.reject()

    def test_raw_a_b_identity_is_required_even_for_native_provenance(self):
        path = self.path("concrete-singleton")
        path.write_bytes(path.read_bytes().replace(b"a" * 40, b"c" * 40))
        self.reject(detail="independent emissions differ")
        self.reject(lambda: G.paired(self.a, self.a), "directories must differ")

    def test_only_parsed_leading_native_provenance_can_change_across_reviewed_sources(self):
        before = G.check(self.a, self.b, self.contract)
        self.change_both("concrete-singleton", lambda raw: raw.replace(b"a" * 40, b"c" * 40).replace(b"b" * 40, b"d" * 40))
        after = G.check(self.a, self.b, self.contract)
        self.assertEqual(before["actual_snapshot_sha256"], after["actual_snapshot_sha256"])
        self.assertNotEqual(before["runs"], after["runs"])
        self.change_both("concrete-singleton", lambda raw: raw.replace(b"// Git hash  : " + b"d" * 40 + b"\n", b""))
        self.assertEqual(G.check(self.a, self.b, self.contract)["actual_snapshot_sha256"], before["actual_snapshot_sha256"])

    def test_unknown_nonleading_duplicate_or_malformed_native_headers_reject(self):
        original = self.path("concrete-singleton").read_bytes()
        mutations = (
            original.replace(b"// Generator :", b"// Unknown :", 1),
            original.replace(b"// Component : PublicationGoldenNative", b"// Component : Wrong", 1),
            original.replace(b"\n\n", b"\n// Date      : today\n\n", 1),
            original.replace(b"\n\n", b"\n// Unknown : hidden normalization\n\n", 1),
            original.replace(b"// Git hash  : " + b"b" * 40, b"// Git hash  : unknown", 1),
            original + b"// Component : PublicationGoldenNative\n",
            b"\n" + original,
            original.replace(b"\n", b"\r\n"),
        )
        for raw in mutations:
            with self.subTest(raw=raw[:90]):
                self.path("concrete-singleton").write_bytes(raw)
                self.reject()
        self.path("concrete-singleton").write_bytes(original)

    def test_native_body_and_parameterized_header_bytes_are_never_normalized(self):
        self.change_both("concrete-singleton", lambda raw: raw.replace(b"assign result = records", b"assign result = ~records"))
        self.reject(detail="reviewed publication golden contract differs")
        for root in (self.a, self.b):
            path = self.path("concrete-singleton", root)
            path.write_bytes(self.original[path.relative_to(root).as_posix()])
        self.change_both("fields", lambda raw: b"// Generator : unrelated change\n" + raw)
        self.reject(detail="reviewed publication golden contract differs")

    def test_defaults_ports_and_signedness_mutations_reject(self):
        original = self.path("fields").read_bytes()
        for before, after in ((b"COUNT = 5", b"COUNT = 7"), (b"WIDTH = 5", b"WIDTH = 8"),
                (b"[WIDTH-1:0] records", b"[WIDTH:0] records"),
                (b"input wire [WIDTH-1:0] records", b"input wire signed [WIDTH-1:0] records"),
                (b"input wire clk,", b"output wire clk,")):
            with self.subTest(change=after):
                self.assertIn(before, original)
                for root in (self.a, self.b): self.path("fields", root).write_bytes(original.replace(before, after, 1))
                self.reject()
        self.change_both("default", lambda raw: raw + b"\n// default drift\n")
        self.reject()

    def test_snapshot_rejects_each_mislabeled_signed_mode_without_a_contract(self):
        replacements = (("fields-legacy", "fields-casts"),
            ("fields-declarations", "fields-legacy"), ("fields-casts", "fields-declarations"))
        for target, source in replacements:
            with self.subTest(profile=target):
                original = self.path(target).read_bytes()
                replacement = self.path(source).read_bytes()
                self.change_both(target, lambda raw: replacement)
                self.reject(lambda: G.paired(self.a, self.b),
                    "incorrect signed output declaration|cleanup fixture did not remove")
                self.change_both(target, lambda raw: original)

    def test_snapshot_requires_both_named_signed_outputs_in_each_mode(self):
        for profile in ("fields-legacy", "fields-declarations", "fields-casts"):
            for name in ("signedSelected_real", "signedSelected_imag"):
                with self.subTest(profile=profile, output=name):
                    original = self.path(profile).read_bytes()
                    declaration = ("output wire " + ("" if profile == "fields-legacy" else "signed ") +
                        "[WIDTH-1:0] " + name).encode()
                    changed = declaration.replace(b"output wire ", b"output wire signed ") \
                        if profile == "fields-legacy" else declaration.replace(b" signed ", b" ")
                    self.assertIn(declaration, original)
                    self.change_both(profile, lambda raw: raw.replace(declaration, changed))
                    self.reject(lambda: G.paired(self.a, self.b), "incorrect signed output declaration")
                    self.change_both(profile, lambda raw: original)

    def test_snapshot_rejects_missing_native_casts_and_ignores_comment_string_decoys(self):
        for profile in ("fields-legacy", "fields-declarations"):
            self.change_both(profile, lambda raw: raw.replace(b"$signed(signed_left)", b"signed_left")
                .replace(b"$signed(signed_right)", b"signed_right").replace(b"$signed(records)", b"records")
                .replace(b"endmodule", b'// $signed(fake) $signed(fake)\n  initial $display("$signed(fake)");\nendmodule'))
        self.reject(lambda: G.paired(self.a, self.b), "same positive native signed-cast count")

    def test_snapshot_rejects_cleanup_in_declaration_only_mode(self):
        for profile in ("fields-legacy", "fields-declarations"):
            with self.subTest(profile=profile):
                original = self.path(profile).read_bytes()
                self.change_both(profile, lambda raw: raw.replace(b"$signed(signed_left)", b"signed_left"))
                self.reject(lambda: G.paired(self.a, self.b), "same positive native signed-cast count")
                self.change_both(profile, lambda raw: original)

    def test_actual_formal_order_is_captured_then_frozen_by_the_contract(self):
        def sorted_declarations(match):
            lines = sorted(line.rstrip(",") for line in match.group(1).splitlines())
            return "#(\n" + ",\n".join(lines) + "\n)"
        def sorted_bindings(match):
            lines = sorted(line.rstrip(",") for line in match.group(1).splitlines())
            return "#(\n" + ",\n".join(lines) + "\n  )"
        for metadata in self.metadata:
            if metadata["kind"] != "concrete":
                for root in (self.a, self.b):
                    path = root / metadata["file"]
                    text = path.read_text()
                    text = re.sub(r"#\(\n((?:  parameter integer[^\n]+\n)+)\)", sorted_declarations, text)
                    text = re.sub(r"#\(\n((?:    \.[^\n]+\n)+)  \)", sorted_bindings, text)
                    path.write_text(text)
        snapshot, _ = G.paired(self.a, self.b)
        child = next(item for item in snapshot["profiles"] if item["profile"] == "child-packed")
        self.assertEqual([item["name"] for item in child["modules"][0]["parameters"]], sorted(G.FORMALS))
        self.assertEqual([item["formal"] for item in child["child_bindings"]], sorted(G.FORMALS))
        self.reject(detail="reviewed publication golden contract differs")

    def test_child_actuals_and_module_inventory_mutations_reject(self):
        original = self.path("child-packed").read_bytes()
        for before, after in ((b".MODE(((MODE + 1)))", b".MODE(MODE)"),
                (b".MODE(((MODE + 1)))", b".MODE(1)"),
                (b".WIDTH(WIDTH)", b".WIDTH(TAG_WIDTH)"),
                (b".COUNT(COUNT),", b".COUNT(COUNT), .COUNT(COUNT),"),
                (b".TAG_WIDTH(TAG_WIDTH),", b""),
                (b"module PublicationGoldenChild #", b"module UnexpectedChild #")):
            with self.subTest(change=after):
                self.assertIn(before, original)
                for root in (self.a, self.b): self.path("child-packed", root).write_bytes(original.replace(before, after, 1))
                self.reject()
        for root in (self.a, self.b): self.path("child-packed", root).write_bytes(original + module("Extra").encode())
        self.reject()

    def test_contract_inventory_and_json_types_cannot_be_redefined(self):
        for mutation in ("profile", "missing", "body-hash", "port-order", "schema-bool"):
            with self.subTest(mutation=mutation):
                value = copy.deepcopy(self.snapshot)
                if mutation == "profile": value["profiles"][0]["profile"] = "replacement"
                elif mutation == "missing": value["profiles"].pop()
                elif mutation == "body-hash": value["profiles"][-1]["rtl_sha256"] = "0" * 64
                elif mutation == "port-order": value["profiles"][0]["modules"][0]["ports"].reverse()
                else: value["schema"] = True
                self.contract.write_bytes(G.serialized(value))
                self.reject(detail="reviewed publication golden contract differs")


if __name__ == "__main__":
    unittest.main()
