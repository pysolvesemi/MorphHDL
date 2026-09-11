#!/usr/bin/env python3
"""Mutation-tested WA-09 source contract; this does not replace executable proofs."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]
BASE = "morphhdl-passes/"
API = BASE + "src/main/scala/morphhdl/passes/api/PassContracts.scala"
PIPELINE = BASE + "src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala"
EXPRESSION = BASE + "src/main/scala/morphhdl/passes/transform/UnnamedWireExpressionEliminationPass.scala"
ALIAS = BASE + "src/main/scala/morphhdl/passes/transform/NamedWireAliasEliminationPass.scala"
NAMED_ALIAS_NATIVE = BASE + "examples/NamedWireAliasNativeBridge.scala"
UNNAMED_EXPRESSION_NATIVE = BASE + "examples/UnnamedWireExpressionNativeBridge.scala"
EXPRESSION_TEST = BASE + "src/test/scala/morphhdl/passes/transform/NamedWireExpressionEliminationPassSpec.scala"
ALIAS_TEST = BASE + "src/test/scala/morphhdl/passes/transform/NamedWireAliasEliminationPassSpec.scala"
CODEC = BASE + "examples/NativeWireExpressionCodec.scala"
NATIVE = BASE + "examples/NamedWireExpressionNativeBridge.scala"
PRODUCTION = "morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala"
MORPH_CONFIG = "morphhdl/src/main/scala/morphhdl/MorphWireAssignmentPasses.scala"
BUILD = "build.sbt"
MILL = "build.mill"
EMITTER_FLAG = "core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala"
EMITTER = "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala"
EMITTER_TEST = "core/src/test/scala/spinal/core/internals/VerilogEmitterExpressionInliningTests.scala"
PUBLIC_EMITTER_TEST = "morphhdl/src/test/scala/spinal/core/MorphVerilogExpressionInliningTests.scala"
PRODUCTION_WRITER = "morphhdl/src/test/scala/nativeapplication/NestedUnsignedExtendedSumProductionArtifactWriter.scala"
PRODUCTION_AGGREGATE_WRITER = "morphhdl/src/test/scala/nativeapplication/WireAssignmentProductionArtifactWriter.scala"
PRODUCTION_ARTIFACT_CHECKER = "morphhdl/scripts/check-wa08-production-artifacts.py"
INVENTORY_CHECKER = "morphhdl/scripts/check-increment-60f-artifacts.py"
RUNNER = BASE + "scripts/run-wa09-regression.sh"
NATIVE_TB = BASE + "tests/formal/wire_assignment_ir/named_expression_native_tb.v"
PREFERENCE_TB = BASE + "tests/formal/wire_assignment_ir/named_preference_native_tb.v"
MANIFEST = BASE + "tests/formal/wire_assignment_ir/manifest.json"
REGISTRY = BASE + "tests/formal_model/wire_assignment_ir/expected-signatures.json"
ROADMAP = BASE + "morphhdl-ir-wire-assignment-passes-todo.md"
NOTES = BASE + "wa09-named-expression-and-name-preference.md"
README = BASE + "README.md"
PV_ROADMAP = "docs/morphhdl/parameterized-verilog-todo.md"
WORKFLOW = ".github/workflows/morphhdl-passes.yml"
CHECKER = BASE + "scripts/check-wa09-named-expression.py"


@dataclass(frozen=True)
class TextRule:
    code: str
    pattern: re.Pattern[str]


GENERIC_RULES = (
    TextRule("COMPONENT-SPECIAL-CASE", re.compile(r"\b(?:StreamFifo(?:CC)?|ProductionNamedAliases)\b")),
    TextRule("MODULE-NAME-RECOGNITION", re.compile(r"\blogicalName\b")),
    TextRule("EMITTED-TEMPORARY-RECOGNITION", re.compile(r"_zz_[A-Za-z0-9]*")),
    TextRule(
        "FILE-TEXT-INGRESS",
        re.compile(
            r"\b(?:scala\.io|java\.io|java\.nio\.(?:file|channels))\b|"
            r"\b(?:fromFile|readAllBytes|readString|parseVerilog|generatedVerilog)\b"
        ),
    ),
    TextRule(
        "REGEX-CANDIDATE-DISCOVERY",
        re.compile(r"\b(?:scala\.util\.matching\.Regex|java\.util\.regex|Pattern\.compile)\b"),
    ),
)


MARKERS = {
    API: (
        'unsafe("wire-expression-named")',
        "val historicalBooleanTernaryPasses: Vector[PassId]",
        "val allWireAssignmentPasses: Vector[PassId] = Vector(",
    ),
    PIPELINE: (
        "PassId.historicalBooleanTernaryPasses.map(_.value).mkString",
        "case PassId.NamedWireExpressionElimination =>",
        "NamedWireExpressionEliminationPass.run(design, stageConfiguration(passId))",
    ),
    EXPRESSION: (
        "object NamedWireExpressionEliminationPass",
        "WireExpressionEliminationMode.Named",
        "case NameOrigin.Explicit(name)",
        "case NameOrigin.Reflected(name)",
        "case NameOrigin.Generated",
        "DriverKind.Continuous",
        "DriverCoverage.FullObject",
        "RtlExpr.Resize",
        'inlineMarker: String = "wa09-inline"',
        "isEligibleNamedCandidate",
        "CanonicalIrPassAdapter.bindFixture",
    ),
    ALIAS: (
        "candidateOrigin",
        "meaningfulName",
        "aliasIsPreferred",
        "isDirectPassReportCandidate",
        "expressionSourceIsIndependentlyEligible",
        "EliminatePreferredSource",
        "NameOrigin.Reflected",
        "NameOrigin.Generated",
        "sourceOrigin == NameOrigin.Unnamed || sourceOrigin == NameOrigin.Generated",
        "(aliasName.length, aliasName, alias.aliasSymbol.value)",
        ".isEligibleUnnamedCandidate(design, source)",
        ".isEligibleNamedCandidate(design, source)",
        "case NameOrigin.Unknown => false",
        "WireAliasSafetyGate",
    ),
    NAMED_ALIAS_NATIVE: (
        "deferPreferredExpressionSource: Boolean = false",
        "deferPreferredExpressionSource && expressionSourceIsIndependentlyRemovable(",
        "private final case class PreferredSourceRewrite",
        "private case object DeferredExpressionRewrite",
        "applyCanonicalPreferenceDecision(plan)",
        "plan.removed.alias,",
        "plan.removed.source,",
        "val wholeRelation = candidate.useStatements.forall(",
        "wholeRhsUse(_, candidate.alias)",
        "new UnnamedWireExpressionNativePhase().isIndependentlyRemovable(pc, source)",
        "new NamedWireExpressionNativePhase().isIndependentlyRemovable(pc, source)",
        "Validate reverse name-preference orientation with the actual two direct",
        "value.aliasSymbol == IrSymbolId.unsafe(sourceId.value)",
        "value.sourceSymbol == IrSymbolId.unsafe(terminalId.value)",
    ),
    UNNAMED_EXPRESSION_NATIVE: (
        "private[examples] final class UnnamedWireExpressionNativePhase extends Phase",
        "new NamedWireExpressionNativePhase().isIndependentlyRemovableWithOrigin(",
        "NameOrigin.Unnamed",
        "val sharedSafety = new NamedWireAliasNativePhase",
        "sharedSafety.expressionRemovalBlocker(",
        "case assignment: DataAssignmentStatement => assignment.source eq alias",
        "case _ if expression.getTypeObject == TypeBool => 1",
        "val codec = new NativeWireExpressionCodec(",
        "val sourceExpression = codec.capture(candidate.sourceExpression).getOrElse(",
        "codec.capture(assignment.source).getOrElse(",
        "DriverCoverage.FullObject",
        "UnnamedWireExpressionEliminationPass.run(",
        "candidate.sourceExpression",
    ),
    EXPRESSION_TEST: (
        "explicit reflected and generated expressions share the proven inlining engine",
        "classification uses origin metadata rather than generated-looking name text",
        "actual unnamed stays historical and unknown provenance fails closed",
        "observed or procedural named expressions fail closed",
        "direct aliases remain owned by the direct alias passes",
        "execution is deterministic and idempotent",
        'NameOrigin.Explicit("_zz")',
    ),
    ALIAS_TEST: (
        "shorter meaningful direct alias wins by eliminating its removable source first",
        "shorter meaningful source wins and the longer alias is removed",
        "meaningful underscore name beats generated provenance regardless of length",
        "protected direct source remains the anchor even when its name is generated",
        "meaningful name beats unnamed provenance through a removable direct chain",
        "meaningful name survives independently eligible unnamed expression removal",
        'NameOrigin.Explicit("_zz")',
    ),
    CODEC: (
        "final class NativeWireExpressionCodec",
        "object NativeWireNameProvenance",
        "Nameable.USER_SET | Nameable.USER_WEAK",
        "Nameable.DATAMODEL_STRONG | Nameable.DATAMODEL_WEAK",
        "Nameable.REMOVABLE",
        "def successorExpressionOrigin",
        "case _: Resize => None",
        "case _         => None",
    ),
    NATIVE: (
        "final class NamedWireExpressionNativePhase extends Phase",
        "final class NamedWireExpressionPipelineNativePhase(all: Boolean)",
        "new NamedWireAliasNativePhase(deferPreferredExpressionSource = true)",
        "object NamedWireExpressionWitnessPhasePlan",
        "NativeWireNameProvenance.successorExpressionOrigin(alias)",
        "new NativeWireExpressionCodec",
        "NamedWireExpressionEliminationPass.run",
        "rewriteNativeIdentity",
        "WA09-NATIVE-NO-RECEIVER",
        "expressionRemovalBlocker",
        "WA09-NATIVE-EXPRESSION-UNREPRESENTED",
        "samePackedBoundary(assignment.finalTarget, alias)",
        "WA09-NATIVE-RECEIVER-PACKED-BOUNDARY",
        "left.parameterRoots.zip(right.parameterRoots).forall { case (l, r) => l eq r }",
        "ElabInt.fromExpression(left).elabEq(ElabInt.fromExpression(right)).isAlwaysTrue",
        "snapshotParameters += IntegerParameter(",
        "case Some(existing) if !sameRetainedWidth(existing, width) => return None",
        "actual_rhs_capture_writeback",
        "whole-rhs-only",
        "wire-expression-named.v",
        "wire-assignment-six-pass.v",
        "private[examples] final class NamedWirePreferenceNativeTopology",
        "object NamedWirePreferenceNativeWitness",
        "expectedEliminatedNames",
        '"substantiallyLongerMeaningfulSource"',
        '"substantiallyLongerMeaningfulAlias"',
        '"bbb"',
        '"p"',
        'Vector("generated", "generated", "unnamed")',
        '"native-preference-reference.v"',
        '"native-preference-candidate.v"',
        '"NamedWirePreferenceNativeReference"',
        '"NamedWirePreferenceNativeCandidate"',
        "actual_native_identity_writeback",
        '"deferred_source_origins"',
    ),
    PRODUCTION: (
        "PassId.UnnamedWireExpressionElimination,",
        "PassId.NamedWireExpressionElimination,",
        "PassId.ConstantOperandSimplification,",
        "new NamedWireAliasNativePhase(deferPreferredExpressionSource = true)",
        "val namedExpression = new NamedWireExpressionNativePhase",
        "namedExpression.report.eliminatedCount",
        "VerilogEmitterExpressionInlining.configure(configured, enabled)",
    ),
    MORPH_CONFIG: (
        "if (enabled) WireAssignmentProductionBridge.enable(config)",
        "else WireAssignmentProductionBridge.disable(config)",
        "WireAssignmentProductionBridge.forPublication(config)",
    ),
    BUILD: (
        '"NativeWireExpressionCodec", "NamedWireExpressionNativeBridge"',
    ),
    MILL: (
        '"morphhdl-passes" / "examples" / "NativeWireExpressionCodec.scala"',
        '"morphhdl-passes" / "examples" / "NamedWireExpressionNativeBridge.scala"',
    ),
    EMITTER_FLAG: (
        "object VerilogEmitterExpressionInlining",
        "private object EnabledProperty extends ScopeProperty[Boolean]",
        "val properties = config.scopeProperties.clone()",
        "if (enabled) properties.update(EnabledProperty, true)",
        "else properties.remove(EnabledProperty)",
        "config.copy(scopeProperties = properties)",
        "private[internals] def isEnabled",
        "private[internals] def isUnannotated",
        "case tagged: SpinalTagReady => tagged.isEmptyOfTag",
    ),
    EMITTER: (
        "def redundantUnsignedAddWrappers()",
        "VerilogEmitterExpressionInlining.isEnabled(spinalConfig)",
        "new java.util.IdentityHashMap[Expression, java.lang.Integer]()",
        "case assignment: DataAssignmentStatement =>",
        "case add: Operator.UInt.Add",
        "add.getWidth == width",
        "VerilogEmitterExpressionInlining.isUnannotated(add)",
        "case resize: ResizeUInt",
        "resize.size == width",
        "ParameterizedWidth.resizeExpressionOf(resize).isEmpty",
        "VerilogEmitterExpressionInlining.isUnannotated(resize)",
        "source.getWidth > 0 && source.getWidth < width",
        "ParameterizedWidth.expressionOf(value).isEmpty",
        "assignment.parentScope == component.dslBody",
        "target.isComb",
        "target.hasOnlyOneStatement",
        "target.head eq assignment",
        "target.isEmptyOfTag",
        "ParameterizedWidth.expressionOf(target).isEmpty",
        "count != null && count.intValue == 1",
        "wrappersProvenRedundant.containsKey(that)",
    ),
    EMITTER_TEST: (
        "the opt-in policy inlines an exact fixed unsigned widening-add tree",
        "ordinary Verilog emission retains the historical wrappers",
        "a truncating root retains its sizing boundary",
        "same-width modular overflow keeps the original tree while wrappers inline",
        "signed addition retains emitter-created boundaries",
        "mixed-width intermediate addition retains its explicit resize boundary",
        "a retained symbolic resize target cannot be treated as its fixed witness",
        "slice operands retain emitter-created boundaries",
        "annotated leaf declarations remain while redundant expression wrappers inline",
        "annotated expression wrapper nodes fail the policy closed",
        "a shared expression node retains one emitter-created carrier",
        "assert(wrappers.size == 1)",
        "an annotated target retains emitter-created boundaries",
        "partial assignments retain emitter-created boundaries",
        "procedural assignments retain emitter-created boundaries",
        "initial assignments retain emitter-created boundaries",
        "multiply-driven targets retain emitter-created boundaries",
        "a.resize(18) + b.resize(18)",
        "wrapperAssignments(verilog).isEmpty",
    ),
    PUBLIC_EMITTER_TEST: (
        "public MorphVerilog inlines the fixed unsigned extended addition tree",
        "assert(default == explicit)",
        "explicitly disabled optimization preserves the legacy wrapper structure",
        "assert(wrapperAssignments(disabled).size == 6)",
        "ordinary SpinalVerilog remains unchanged",
        "parameter-dependent resize carriers remain while redundant arithmetic wrappers inline",
        "assert(wrapperAssignments(generated).isEmpty)",
        "morphhdl_resize_3",
        "disabled parameter-dependent generation preserves both resize and add wrappers",
        "assert(wrapperAssignments(generated).size == 2)",
        "repeated public generation is byte deterministic",
        "the private emitter marker does not admit unrelated generation flags",
        "assign hTotal = ((({2'd0, hActive} + {2'd0, hFrontPorch})",
    ),
    PRODUCTION_WRITER: (
        "object NestedUnsignedExtendedSumProductionArtifactWriter",
        "new FixedNestedUnsignedExtendedSum(witnessWidth)",
        "new ParameterizedNestedUnsignedExtendedSum(width)",
        "new FixedNestedUnsignedOverflowSum(witnessWidth)",
        'for (mode <- Vector("default", "enabled", "disabled"))',
        'for (round <- Vector("first", "repeat"))',
        "hActive.resize(18) + hFrontPorch.resize(18)",
        'generateOverflow(output.resolve("nested-overflow-" + mode), mode)',
        'require(args.length == 1, "usage: OUTPUT_DIRECTORY")',
    ),
    RUNNER: (
        "run-wa07b-regression.sh",
        "--after-wa07b",
        "ParameterizedStreamFifoNamedExpressionPassWitness",
        "NamedWireExpressionGenericNativeWitness",
        "NamedWirePreferenceNativeWitness",
        "wire-expression-named.v",
        "wire-assignment-six-pass.v",
        "may therefore differ textually from the frozen common reference",
        'assert "assign result = (a ^ b);" in lines',
        'assert "assign flagResult = (c ^ d);" in lines',
        "named_expression_native_tb.v",
        "named_preference_native_tb.v",
        "NamedWirePreferenceNativeTb",
        "NamedWirePreferenceNativeMiter",
        "WA09_PREFERENCE_NATIVE_PASS patterns=16 outputs=5 candidate=1",
        'preference_report["eliminated_names"] == [',
        '"substantiallyLongerMeaningfulSource",',
        '"substantiallyLongerMeaningfulAlias",',
        'preference_report["deferred_source_origins"] == [',
        '"generated",',
        '"unnamed",',
        '"assign reverseResult = q;"',
        '"assign forwardResult = abc;"',
        '"assign lexicalResult = aaa;"',
        '"assign protectedResult = extraordinarilyLongProtectedName;"',
        "iverilog -g2001",
        "verilator --lint-only --language 1364-2001",
        "sat -verify -prove ok 1",
        "complete 512-binding domain",
    ),
    NATIVE_TB: (
        "module NamedWireExpressionNativeMiter",
        "module NamedWireExpressionNativeTb",
        "NamedWireExpressionNativeReference",
        "NamedWireExpressionNativeCandidate",
        "function four_state",
        "reference_result !== candidate_result",
        "reference_flag !== candidate_flag",
        "WA09_NATIVE_PASS patterns=256 outputs=9 candidate=1",
    ),
    PREFERENCE_TB: (
        "module NamedWirePreferenceNativeMiter",
        "module NamedWirePreferenceNativeTb",
        "NamedWirePreferenceNativeReference",
        "NamedWirePreferenceNativeCandidate",
        "function four_state",
        "reference_value !== candidate_value",
        "WA09_PREFERENCE_NATIVE_PASS patterns=16 outputs=5 candidate=1",
    ),
    ROADMAP: (
        "six optional, behavior-preserving",
        "named continuous wire-expression inlining (WA-09)",
        "provenance-first alias preference",
        "assign clonedResult = (a ^ b);",
        "historical three-, four- and five-stage pass vectors",
        "Bounded post-pass wrapper elision",
        "fillExpressionToWrap",
        "0..262140",
        "actual whole RHS",
        "no representative XOR",
        "UnnamedWireExpressionNativePhase",
        "resize `BaseType` carriers remain declared",
        "direct symbolic-width",
        "expression nodes, incomplete facts",
        "morph/Test/runMain morphhdl.examples.NestedUnsignedExtendedSumProductionArtifactWriter",
    ),
    NOTES: (
        "Six-stage production order",
        "Provenance before length",
        "Named expression inlining",
        "assign clonedResult = (a ^ b);",
        "an explicitly user-named `_zz` is meaningful",
        "An unsupported native RHS is retained",
        "Native handoff ownership",
        "No representative XOR",
        "substitute expression is",
        "`TypeBool` is represented at width one",
        "true `Unnamed` provenance dispatches",
        "`UnnamedWireExpressionNativePhase`",
        "Post-pass emitter-created wrappers",
        "Mixed operand or intermediate widths, signed operands/results, narrowing",
        "tagged parameterized resize carriers remain declared",
        "Direct symbolic-width",
        "expression nodes still retain their wrappers",
        "`DataAssignmentStatement`",
        "`ParameterizedWidth.resizeExpressionOf`",
        "initial assignments",
        "morph/Test/runMain morphhdl.examples.NestedUnsignedExtendedSumProductionArtifactWriter",
    ),
    README: (
        "WA-09 — named expressions and alias survivor preference",
        "The common flag runs six stages",
        "historicalBooleanTernaryPasses",
        "assign clonedResult = (a ^ b);",
        "actual source and receiver RHS",
        "UnnamedWireExpressionNativePhase",
        "Tagged parameterized resize carriers",
        "morph/Test/runMain morphhdl.examples.NestedUnsignedExtendedSumProductionArtifactWriter",
        "generated-text rewrite",
    ),
    PV_ROADMAP: (
        "Increment 63 — Named expression-wire elimination and provenance-first alias preference",
        "successor is independent of unfinished Increments 59i and 61",
        "assign clonedResult = (a ^ b);",
        "actual source and receiver RHS",
        "UnnamedWireExpressionNativePhase",
        "graph/type-proven emitter policy",
        "not a seventh",
        "emitted-text",
        "16-bit inputs in an 18-bit domain (`0..262140`)",
        "parameterized resize carriers remain declared",
        "morph/Test/runMain morphhdl.examples.NestedUnsignedExtendedSumProductionArtifactWriter",
    ),
    WORKFLOW: (
        "check-wa09-named-expression.py --self-test",
        "check-wa09-named-expression.py",
        "run-wa09-regression.sh --after-wa07b",
        "--prove-pending WA-09",
    ),
}


SIGNATURE_SOURCES = frozenset(
    (set(MARKERS) - {ROADMAP, NOTES, README, PV_ROADMAP})
    | {
        MANIFEST,
        CHECKER,
        BASE + "src/test/scala/morphhdl/passes/api/AllPassConfigurationSpec.scala",
        BASE + "src/test/scala/morphhdl/passes/api/NativeRunnerSourceClosureSpec.scala",
        BASE + "src/test/scala/morphhdl/passes/pipeline/WireAliasPassPipelineSpec.scala",
        BASE + "src/test/scala/morphhdl/passes/pipeline/WireAssignmentAllPassPipelineSpec.scala",
        PRODUCTION_AGGREGATE_WRITER,
        PRODUCTION_ARTIFACT_CHECKER,
        INVENTORY_CHECKER,
    }
)


HISTORICAL_VECTORS = {
    "historicalWireAssignmentPasses": (
        "UnnamedWireAliasElimination",
        "NamedWireAliasElimination",
        "UnnamedWireExpressionElimination",
    ),
    "historicalConstantOperandPasses": (
        "UnnamedWireAliasElimination",
        "NamedWireAliasElimination",
        "UnnamedWireExpressionElimination",
        "ConstantOperandSimplification",
    ),
    "historicalBooleanTernaryPasses": (
        "UnnamedWireAliasElimination",
        "NamedWireAliasElimination",
        "UnnamedWireExpressionElimination",
        "ConstantOperandSimplification",
        "BooleanTernarySimplification",
    ),
    "allWireAssignmentPasses": (
        "UnnamedWireAliasElimination",
        "NamedWireAliasElimination",
        "UnnamedWireExpressionElimination",
        "NamedWireExpressionElimination",
        "ConstantOperandSimplification",
        "BooleanTernarySimplification",
    ),
}


def marker_failures(path: str, text: str) -> list[str]:
    return [
        f"WA09-CONTRACT: {path}: missing {marker!r}"
        for marker in MARKERS[path]
        if marker not in text
    ]


def genericity_failures(path: str, text: str) -> list[str]:
    inspected = text
    if path == NATIVE:
        # The witness below the phase writes generated artifacts for proof. It
        # must not affect phase candidate discovery, and is outside this scan.
        inspected = inspected.split(
            "object ParameterizedStreamFifoNamedExpressionPassWitness", 1
        )[0]
        inspected = "\n".join(
            line for line in inspected.splitlines()
            if not line.startswith("import java.nio.")
        )
    elif path == EMITTER:
        match = re.search(
            r"def redundantUnsignedAddWrappers\(\).*?"
            r"val wrappersProvenRedundant = redundantUnsignedAddWrappers\(\)",
            inspected,
            re.S,
        )
        inspected = match.group(0) if match else inspected
    errors = [
        f"WA09-GENERICITY-{rule.code}: {path}"
        for rule in GENERIC_RULES
        if rule.pattern.search(inspected)
    ]
    if path == EMITTER and re.search(
        r"\.(?:getName|getPartialName|definitionName)\b", inspected
    ):
        errors.append(f"WA09-GENERICITY-EMITTER-NAME-RECOGNITION: {path}")
    return errors


def vector_body(text: str, name: str) -> str | None:
    if name == "historicalConstantOperandPasses":
        match = re.search(
            r"val historicalConstantOperandPasses: Vector\[PassId\]\s*=\s*"
            r"historicalWireAssignmentPasses\s*:\+\s*ConstantOperandSimplification",
            text,
        )
        return "derived" if match else None
    if name == "historicalBooleanTernaryPasses":
        match = re.search(
            r"val historicalBooleanTernaryPasses: Vector\[PassId\]\s*=\s*"
            r"historicalConstantOperandPasses\s*:\+\s*BooleanTernarySimplification",
            text,
        )
        return "derived" if match else None
    match = re.search(
        rf"val {re.escape(name)}: Vector\[PassId\]\s*=\s*Vector\((.*?)\n\s*\)",
        text,
        re.S,
    )
    return match.group(1) if match else None


def vector_failures(text: str) -> list[str]:
    errors: list[str] = []
    resolved: dict[str, tuple[str, ...]] = {}
    for name in HISTORICAL_VECTORS:
        body = vector_body(text, name)
        if body is None:
            errors.append(f"WA09-HISTORICAL-VECTOR: unable to parse {name}")
            continue
        if body == "derived":
            if name == "historicalConstantOperandPasses":
                resolved[name] = resolved.get("historicalWireAssignmentPasses", ()) + (
                    "ConstantOperandSimplification",
                )
            else:
                resolved[name] = resolved.get("historicalConstantOperandPasses", ()) + (
                    "BooleanTernarySimplification",
                )
        else:
            resolved[name] = tuple(re.findall(r"\b([A-Z][A-Za-z]+)\b", body))
        if resolved.get(name) != HISTORICAL_VECTORS[name]:
            errors.append(
                f"WA09-HISTORICAL-VECTOR: {name} changed: {resolved.get(name)!r}"
            )
    return errors


def status_failures(roadmap: str, pv_roadmap: str) -> list[str]:
    errors: list[str] = []
    wa09 = re.search(
        r"^- \[([ xX])\] \*\*WA-09\s+—(.*?)(?=^- \[[ xX]\] \*\*WA-|\Z)",
        roadmap,
        re.M | re.S,
    )
    wa_complete: bool | None = None
    if wa09 is None:
        errors.append("WA09-STATUS: missing WA-09 roadmap entry")
    else:
        wa_complete = wa09.group(1).lower() == "x"
        expected = "COMPLETED" if wa_complete else "IN PROGRESS"
        if f"**Status:** `{expected}`." not in wa09.group(2):
            errors.append(
                f"WA09-STATUS: {'checked' if wa_complete else 'unchecked'} WA-09 must be {expected}"
            )
    increment = re.search(
        r"^- \[([ xX])\] \*\*Increment 63\s+—",
        pv_roadmap,
        re.M,
    )
    if increment is None:
        errors.append("WA09-PV-STATUS: missing Increment 63 roadmap entry")
    elif wa_complete is not None and (increment.group(1).lower() == "x") != wa_complete:
        errors.append("WA09-PV-STATUS: WA-09 and Increment 63 completion states differ")
    return errors


def workflow_failures(text: str) -> list[str]:
    errors: list[str] = []
    if text.count("--prove-pending WA-09") != 4:
        errors.append("WA09-WORKFLOW: expected exactly four final-head WA-09 proof bindings")
    generation = text.find("run-wa09-regression.sh --after-wa07b")
    capture = text.find("--capture-native-inputs")
    if generation < 0 or capture < 0 or generation > capture:
        errors.append("WA09-WORKFLOW: WA-09 generation must precede native-input capture")
    return errors


def native_expression_architecture_failures(text: str) -> list[str]:
    """Reject a return to the former representative-expression witness."""
    errors: list[str] = []
    if "RtlBinaryOperator.Xor" in text:
        errors.append("WA09-NATIVE-ACTUAL-RHS: fabricated XOR witness detected")
    if re.search(
        r"RtlExpr\.(?:Unary|Binary|Mux|Concat|Slice|BitSelect|Resize|Cast)\s*\(",
        text,
    ):
        errors.append("WA09-NATIVE-ACTUAL-RHS: fabricated expression tree detected")
    return errors


def preference_witness_failures(text: str) -> list[str]:
    """Freeze the durable native witness's semantic order, not just its tokens."""
    errors: list[str] = []
    eliminated = re.search(
        r"private val expectedEliminatedNames\s*=\s*Vector\((.*?)\n\s*\)",
        text,
        re.S,
    )
    expected_eliminated = (
        "substantiallyLongerMeaningfulSource",
        "substantiallyLongerMeaningfulAlias",
        "bbb",
        "p",
    )
    actual_eliminated = (
        tuple(re.findall(r'"([^"]+)"', eliminated.group(1)))
        if eliminated
        else None
    )
    if actual_eliminated != expected_eliminated:
        errors.append(
            "WA09-PREFERENCE-WITNESS: eliminated-name sequence changed: "
            f"{actual_eliminated!r}"
        )

    deferred = re.search(
        r"private val expectedDeferredOrigins\s*=\s*Vector\(([^)]*)\)",
        text,
    )
    expected_deferred = ("generated", "generated", "unnamed")
    actual_deferred = (
        tuple(re.findall(r'"([^"]+)"', deferred.group(1))) if deferred else None
    )
    if actual_deferred != expected_deferred:
        errors.append(
            "WA09-PREFERENCE-WITNESS: deferred-origin sequence changed: "
            f"{actual_deferred!r}"
        )
    return errors


def signature_failures(value: object) -> list[str]:
    if not isinstance(value, dict) or value.get("schema_version") != 1 or \
            value.get("algorithm") != "SHA-256":
        return ["WA09-SIGNATURES: invalid signature registry schema or algorithm"]
    files = value.get("files")
    if not isinstance(files, dict):
        return ["WA09-SIGNATURES: missing signature file inventory"]
    return [
        "WA09-SIGNATURE-MISSING: " + path
        for path in sorted(SIGNATURE_SOURCES - set(files))
    ]


def record_source_signatures(root: Path) -> None:
    """Print reviewed current bytes; never rewrite or weaken the registry."""
    value = json.loads((root / REGISTRY).read_text(encoding="utf-8"))
    paths = set(value.get("files", {})) | set(SIGNATURE_SOURCES)
    value["files"] = {
        path: hashlib.sha256((root / path).read_bytes()).hexdigest()
        for path in sorted(paths)
    }
    print("WA09_SOURCE_SIGNATURES_BEGIN")
    print(json.dumps(value, indent=2, sort_keys=True))
    print("WA09_SOURCE_SIGNATURES_END")


def check(root: Path) -> list[str]:
    errors: list[str] = []
    sources: dict[str, str] = {}
    for path in MARKERS:
        try:
            sources[path] = (root / path).read_text(encoding="utf-8")
        except OSError as error:
            errors.append(f"WA09-MISSING: {path}: {error}")
            continue
        errors.extend(marker_failures(path, sources[path]))
    for path in (EXPRESSION, ALIAS, CODEC, NATIVE, EMITTER):
        if path in sources:
            errors.extend(genericity_failures(path, sources[path]))
    if API in sources:
        errors.extend(vector_failures(sources[API]))
    if ROADMAP in sources and PV_ROADMAP in sources:
        errors.extend(status_failures(sources[ROADMAP], sources[PV_ROADMAP]))
    if WORKFLOW in sources:
        errors.extend(workflow_failures(sources[WORKFLOW]))
    if UNNAMED_EXPRESSION_NATIVE in sources:
        errors.extend(
            native_expression_architecture_failures(
                sources[UNNAMED_EXPRESSION_NATIVE]
            )
        )
    if NATIVE in sources:
        errors.extend(preference_witness_failures(sources[NATIVE]))
    try:
        errors.extend(signature_failures(json.loads((root / REGISTRY).read_text(encoding="utf-8"))))
    except (OSError, json.JSONDecodeError) as error:
        errors.append(f"WA09-SIGNATURES: {error}")
    return errors


def self_test(root: Path) -> None:
    sources = {path: (root / path).read_text(encoding="utf-8") for path in MARKERS}
    baseline = check(root)
    assert not baseline, "\n".join(baseline)

    for path, markers in MARKERS.items():
        for marker in markers:
            mutated = sources[path].replace(marker, "WA09_MUTATED")
            assert marker_failures(path, mutated), (path, marker)

    for path in (EXPRESSION, ALIAS, CODEC, NATIVE, EMITTER):
        for mutation in (
            "StreamFifo",
            "logicalName",
            'if (nativeName == "_zz_7") true',
            "java.nio.file.Files.readString(path)",
            "java.util.regex.Pattern.compile(name)",
        ):
            if path == NATIVE:
                mutated = sources[path].replace(
                    "object ParameterizedStreamFifoNamedExpressionPassWitness",
                    mutation + "\nobject ParameterizedStreamFifoNamedExpressionPassWitness",
                    1,
                )
            elif path == EMITTER:
                mutated = sources[path].replace(
                    "val wrappersProvenRedundant = redundantUnsignedAddWrappers()",
                    mutation + "\n    val wrappersProvenRedundant = redundantUnsignedAddWrappers()",
                    1,
                )
            else:
                mutated = sources[path] + "\n" + mutation
            assert genericity_failures(path, mutated), (path, mutation)
    emitter_name_mutation = sources[EMITTER].replace(
        "val wrappersProvenRedundant = redundantUnsignedAddWrappers()",
        "target.getName()\n    val wrappersProvenRedundant = redundantUnsignedAddWrappers()",
        1,
    )
    assert genericity_failures(EMITTER, emitter_name_mutation)

    workflow_mutation = sources[WORKFLOW].replace("--prove-pending WA-09", "", 1)
    assert workflow_failures(workflow_mutation), "missing proof binding escaped"

    fabricated_rhs_mutation = (
        sources[UNNAMED_EXPRESSION_NATIVE] + "\nRtlBinaryOperator.Xor\n"
    )
    assert native_expression_architecture_failures(fabricated_rhs_mutation), (
        "fabricated native expression escaped"
    )

    preference_order_mutation = sources[NATIVE].replace(
        '    "substantiallyLongerMeaningfulSource",\n'
        '    "substantiallyLongerMeaningfulAlias",',
        '    "substantiallyLongerMeaningfulAlias",\n'
        '    "substantiallyLongerMeaningfulSource",',
        1,
    )
    assert preference_witness_failures(preference_order_mutation), (
        "preference eliminated-name order mutation escaped"
    )
    deferred_origin_mutation = sources[NATIVE].replace(
        'Vector("generated", "generated", "unnamed")',
        'Vector("generated", "unnamed", "generated")',
        1,
    )
    assert preference_witness_failures(deferred_origin_mutation), (
        "preference deferred-origin order mutation escaped"
    )

    registry = json.loads((root / REGISTRY).read_text(encoding="utf-8"))
    assert not signature_failures(registry), signature_failures(registry)
    for path in SIGNATURE_SOURCES:
        mutated = dict(registry)
        mutated["files"] = dict(registry["files"])
        mutated["files"].pop(path)
        assert signature_failures(mutated), "missing signature escaped: " + path

    api = sources[API]
    for mutation in (
        api.replace(
            "historicalWireAssignmentPasses :+ ConstantOperandSimplification",
            "allWireAssignmentPasses :+ ConstantOperandSimplification",
        ),
        api.replace(
            "historicalConstantOperandPasses :+ BooleanTernarySimplification",
            "allWireAssignmentPasses :+ BooleanTernarySimplification",
        ),
        api.replace(
            "UnnamedWireExpressionElimination,\n    NamedWireExpressionElimination,",
            "NamedWireExpressionElimination,\n    UnnamedWireExpressionElimination,",
        ),
    ):
        assert vector_failures(mutation), "historical/order mutation escaped"

    assert status_failures(
        sources[ROADMAP].replace("- [ ] **WA-09", "- [x] **WA-09"),
        sources[PV_ROADMAP],
    )
    assert status_failures(
        sources[ROADMAP],
        sources[PV_ROADMAP].replace("- [ ] **Increment 63", "- [x] **Increment 63"),
    )
    completed_wa = sources[ROADMAP].replace(
        "- [ ] **WA-09", "- [x] **WA-09"
    ).replace("**Status:** `IN PROGRESS`.", "**Status:** `COMPLETED`.", 1)
    completed_pv = sources[PV_ROADMAP].replace(
        "- [ ] **Increment 63", "- [x] **Increment 63"
    )
    assert not status_failures(completed_wa, completed_pv)
    print("WA-09 named-expression and preference contract self-tests passed.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=ROOT)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--record-source-signatures", action="store_true")
    args = parser.parse_args()
    if args.record_source_signatures:
        record_source_signatures(args.repo_root)
        return 0
    if args.self_test:
        self_test(args.repo_root)
        return 0
    errors = check(args.repo_root)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("WA-09 named-expression and preference contract passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
