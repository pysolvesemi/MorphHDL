#!/usr/bin/env python3
"""Static and mutation-tested contract guard for the WA-05 named alias pass."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


@dataclass(frozen=True)
class TextRule:
    code: str
    message: str
    pattern: re.Pattern[str]


GENERIC_RULES: tuple[TextRule, ...] = (
    TextRule(
        "WA05-COMPONENT-SPECIAL-CASE",
        "pass implementation must not recognize a library component or shared witness class",
        re.compile(r"\b(?:StreamFifo(?:CC)?|ParameterizedStreamFifo)\b"),
    ),
    TextRule(
        "WA05-MODULE-NAME-RECOGNITION",
        "pass implementation must not inspect canonical logical module names",
        re.compile(r"\blogicalName\b"),
    ),
    TextRule(
        "WA05-SPINAL-IMPLEMENTATION-COUPLING",
        "pass implementation must consume canonical MorphHDL IR rather than Spinal implementation classes",
        re.compile(r"\bspinal\."),
    ),
    TextRule(
        "WA05-EMITTED-NAME-RECOGNITION",
        "pass implementation must not recognize an emitted temporary identifier",
        re.compile(r"_zz_"),
    ),
    TextRule(
        "WA05-FILE-TEXT-INGRESS",
        "pass implementation must not parse or postprocess generated files",
        re.compile(
            r"\b(?:scala\.io|java\.io|java\.nio\.(?:file|channels))\b|"
            r"\b(?:fromFile|readAllBytes|readString|newInputStream|parseVerilog|generatedVerilog)\b"
        ),
    ),
    TextRule(
        "WA05-REGEX-CANDIDATE-DISCOVERY",
        "pass implementation must not discover candidates with regular expressions",
        re.compile(
            r"\b(?:scala\.util\.matching\.Regex|java\.util\.regex|Pattern\.compile)\b|"
            r"(?:\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\])*\")\s*\.r\b",
            re.DOTALL,
        ),
    ),
)

BRIDGE_RULES: tuple[TextRule, ...] = (
    TextRule(
        "WA05-BRIDGE-COMPONENT-RECOGNITION",
        "native witness bridge must not inspect a component definition or instance name",
        re.compile(r"\.(?:definitionName|getName|getPartialName)\b"),
    ),
    TextRule(
        "WA05-BRIDGE-EMITTED-NAME-RECOGNITION",
        "native witness bridge must not recognize an emitted temporary identifier",
        re.compile(r"_zz_"),
    ),
    TextRule(
        "WA05-BRIDGE-GENERATED-HDL-PARSER",
        "native witness bridge must mutate exact graph identities rather than parse generated HDL",
        re.compile(r"\b(?:parseVerilog|generatedVerilog|emittedVerilog|verilogText)\b", re.IGNORECASE),
    ),
    TextRule(
        "WA05-BRIDGE-FIXTURE-TAG-DEPENDENCY",
        "production candidate discovery must not require the former fixture-only source tag",
        re.compile(r"\bExplicitNamedWireAliasSourceTag\b"),
    ),
    TextRule(
        "WA05-BRIDGE-SOURCE-NAME-RECOGNITION",
        "retained source names are metadata, never candidate-selection predicates",
        re.compile(
            r'\b(?:explicitName|sourceName|nativeName)\s*(?:==|!=|\.\s*(?:equals|matches|startsWith|endsWith|contains)\s*\()\s*"|'
            r'"(?:\\.|[^"\\])*"\s*(?:==|!=)\s*\b(?:explicitName|sourceName|nativeName)\b'
        ),
    ),
)

SOURCE_NAME_LOOKUP = "Option(alias.getName()).filter(_.trim.nonEmpty)"
SOURCE_NAME_GUARD = "if (alias.isNamed && sourceNamed)"
BRIDGE_DISPLAY_NAME_LOOKUP = '.orElse(Option(alias.getName(""))).getOrElse("")'
BRIDGE_TIE_NAME_LOOKUP = 'Option(candidate.source.getName("")).getOrElse("")'
PROVENANCE_NAME_LOOKUP = 'Option(value.getName("")).map(_.trim).filter(_.nonEmpty)'
SOURCE_NAME_PROVENANCE = (
    'readPrivateByte(alias, "namePriority").exists',
    "Nameable.USER_SET | Nameable.USER_WEAK",
    "Nameable.DATAMODEL_STRONG | Nameable.DATAMODEL_WEAK => true",
    "case _ => false",
    SOURCE_NAME_GUARD,
)
SUCCESSOR_PROVENANCE_MARKERS = (
    "private[examples] object NativeWireNameProvenance",
    "if (value == null) None",
    "else if (value.isUnnamed) Some(NameOrigin.Unnamed)",
    PROVENANCE_NAME_LOOKUP,
    'readPrivateByte(value, "namePriority").flatMap',
    "case Nameable.USER_SET | Nameable.USER_WEAK =>",
    "name.map(NameOrigin.Explicit)",
    "case Nameable.DATAMODEL_STRONG | Nameable.DATAMODEL_WEAK =>",
    "name.map(NameOrigin.Reflected)",
    "case Nameable.REMOVABLE => Some(NameOrigin.Generated)",
    "case _                  => None",
    "case NameOrigin.Unnamed | NameOrigin.Unknown => false",
    "case _                                       => true",
    "case _: NoSuchFieldException => current = current.getSuperclass",
    "case _: Throwable            => return None",
)
SUCCESSOR_BRIDGE_MARKERS = (
    "NativeWireNameProvenance.successorExpressionOrigin(alias)",
    "NativeWireNameProvenance.origin(candidate.source)",
    ".getOrElse(NameOrigin.Unknown)",
    "private def selectRewrite(",
    "candidateForValue(candidate.source, sourceOrigin, statements)",
    "wholeRhsUse(_, candidate.alias)",
    "wholeRhsUse(_, sourceCandidate.alias)",
    "PreferredSourceRewrite(candidate, proof, sourceCandidate, sourceProof)",
    "Right(DeferredExpressionRewrite)",
    "private def aliasIsPreferred(",
    "meaningfulName(candidate.nameOrigin)",
    "meaningfulName(sourceOrigin)",
    "implicitly[Ordering[(Int, String, String)]].lt",
    "sourceOrigin == NameOrigin.Unnamed || sourceOrigin == NameOrigin.Generated",
    "new UnnamedWireExpressionNativePhase().isIndependentlyRemovable",
    "new NamedWireExpressionNativePhase().isIndependentlyRemovable",
    BRIDGE_DISPLAY_NAME_LOOKUP,
    BRIDGE_TIE_NAME_LOOKUP,
    "case NameOrigin.Explicit(name)  => AliasNameOrigin.Explicit(name)",
    "case NameOrigin.Reflected(name) => AliasNameOrigin.Reflected(name)",
    "case NameOrigin.Generated       => AliasNameOrigin.Generated",
)
NATIVE_SAFETY_MARKERS = (
    "alias.isEmptyOfTag",
    "!alias.isFrozen()",
    "Option(assignment.locationString).forall(_.isEmpty)",
    '!readPrivateBoolean(alias, "dontSimplify").getOrElse(true)',
    "hasClockDomainUse(pc, alias)",
    "Vector(domain.clock, domain.reset, domain.softReset, domain.clockEnable)",
    "component.pulledDataCache.get(control).exists(value => value eq alias)",
    "statement.foreachClockDomain",
    "pc.components().toVector.flatMap(statementsOf)",
    "hasReferencedMetadata(pc, alias)",
    'Left("WA05-NATIVE-REFERENCED-METADATA")',
    "NativeWireAssignmentMetadata.retains(alias)",
    'Left("WA05-NATIVE-REGISTERED-IDENTITY")',
)
REFERENCED_METADATA_MARKERS = (
    "case value: crossClockFalsePath if value.getClass == classOf[crossClockFalsePath] => value.source.exists(_ eq alias)",
    "case value: ClockSyncTag if value.getClass == classOf[ClockSyncTag] => (value.a eq alias) || (value.b eq alias)",
    "case value: ExternalDriverTag if value.getClass == classOf[ExternalDriverTag] => dataUses(value.driver)",
    "case value: GenericValue if value.getClass == classOf[GenericValue] => expressionUses(value.e)",
    "case value: SimInitTag if value.getClass == classOf[SimInitTag] => expressionUses(value.value)",
    "case value if NativeWireAssignmentMetadata.isReferenceFreeTag(value) => false",
    "case _ => true",
    "var found = tagsUse(component)",
    "statement.walkDrivingExpressions",
    "statement.foreachClockDomain",
    "(mapping eq null) || mapping.getClass != classOf[MemSymbolesMapping]",
    "spinal.core.sim.SimPublic, spinal.core.sim.TracingOff).exists(_ eq value) => false",
) + tuple(
    "case value: " + tag + " if value.getClass == classOf[" + tag + "] =>"
    for tag in (
        "crossClockFalsePath",
        "ClockDomainTag",
        "ClockDomainReportTag",
        "ClockTag",
        "ResetTag",
        "ClockEnableTag",
        "ClockSyncTag",
        "ClockDrivedTag",
        "ClockDriverTag",
        "DefaultTag",
        "ExternalDriverTag",
        "VarAssignementTag",
        "GenericValue",
        "SimInitTag",
        "PhaseNextifyTag",
        "MemReadBufferTag",
        "MemBlackboxOf",
        "IfDefTag",
        "CommentTag",
        "CrossClockBufferDepth",
        "TagAFixTruncated",
        "MemSymbolesTag",
    )
)
CONTINUOUS_RECEIVER_MARKERS = (
    "(assignment.parentScope eq assignment.rootScopeStatement)",
    "(assignment.target eq assignment.finalTarget)",
    "(assignment.finalTarget.component eq component)",
    "assignment.finalTarget.hasOnlyOneStatement",
    "assignment.finalTarget.isComb",
    "!assignment.finalTarget.isAnalog",
    "!assignment.finalTarget.isInputOrInOut",
)
REGISTERED_IDENTITY_HOOKS = {
    "NamedWireAliasNativeBridge.scala": "else if (NativeWireAssignmentMetadata.retains(alias))",
    "UnnamedWireAliasNativeBridge.scala": "!NativeWireAssignmentMetadata.retains(alias) &&",
    # WA-10 routes the unnamed slot through the shared named-expression
    # engine, which must still invoke the independently guarded metadata proof.
    "UnnamedWireExpressionNativeBridge.scala": "new NamedWireExpressionNativePhase(unnamedOnly = true)",
    "NamedWireExpressionNativeBridge.scala": "sharedSafety.expressionRemovalBlocker(",
    "ConstantOperandNativeBridge.scala": "!NativeWireAssignmentMetadata.retains(target) &&",
    "BooleanTernaryNativeBridge.scala": "if bridge.eligible(assignment)",
}
REGISTERED_IDENTITY_MARKERS = (
    "if (alias == null || alias.component == null) return true",
    "new IdentityHashMap[AnyRef, java.lang.Boolean]()",
    "case leaf: BaseType => leaf eq alias",
    "case data: Data => data.flatten.exists(_ eq alias)",
    "value.productIterator.exists(uses)",
    "case value: ParameterizedVecStaticWrite => recordUses(value)",
    "case value: ParameterizedVecPackedAssignment => recordUses(value)",
    "case value: ParameterizedVecWriteInvocation => recordUses(value)",
    "case _ => true",
    "root.walkComponents",
    "ParameterizedVec.retainedVectorsOf(component)",
    "uses(vector)",
    "ParameterizedVec.operationsOf(vector).exists(uses)",
    "ParameterizedVec.writeInvocationsOf(vector).exists(uses)",
)
REFERENCE_FREE_TAG_CASES = (
    "case _: ParameterizedMemoryTag | _: ParameterizedMemoryDepthOverrideTag => true "
    "case _ => false"
)


def registered_identity_failures(sources: dict[str, str], metadata: str) -> list[str]:
    failures: list[str] = []
    for path, marker in REGISTERED_IDENTITY_HOOKS.items():
        failures.extend(require_markers(Path(path), sources[path], (marker,),
                                        "WA05-REGISTERED-IDENTITY-HOOK"))
    failures.extend(scan_text(Path("NativeWireAssignmentMetadata.scala"), metadata, BRIDGE_RULES))
    failures.extend(require_markers(Path("NativeWireAssignmentMetadata.scala"), metadata,
                                    REGISTERED_IDENTITY_MARKERS, "WA05-REGISTERED-IDENTITY-INVENTORY"))
    reference_free = re.search(
        r"def isReferenceFreeTag\(tag: SpinalTag\): Boolean = tag match \{([\s\S]*?)\n  \}",
        metadata,
    )
    if (reference_free is None or
            " ".join(reference_free.group(1).split()) != REFERENCE_FREE_TAG_CASES):
        failures.append("NativeWireAssignmentMetadata.scala: WA05-REFERENCE-FREE-TAGS: "
                        "only the two final integer-only memory metadata tags may be reference-free")
    return failures

REQUIRED_SOURCE_MARKERS: tuple[str, ...] = (
    "PassId.NamedWireAliasElimination",
    "configuration.isEnabled(passId)",
    "PassResult.skipped",
    "WireAliasSafetyGate",
    "NameOrigin.Explicit",
    "NameOrigin.Reflected",
    "NameOrigin.Generated",
    "case NameOrigin.Unknown => false",
    "candidateOrigin(value.nameOrigin).nonEmpty",
    "isDirectPassReportCandidate",
    "aliasIsPreferred",
    "implicitly[Ordering[(Int, String, String)]].lt",
    "isEligibleUnnamedCandidate",
    "isEligibleNamedCandidate",
    "transformToFixedPoint",
    "CanonicalIrPassAdapter.bindFixture(plan.output)",
    "declarations = module.declarations.filterNot(_.id == aliasSymbol)",
    ".filterNot(_.id == aliasDriverId)",
    "target == aliasSymbol",
    "value.copy(target = sourceSymbol)",
    "RtlExpr.Unary",
    "RtlExpr.Binary",
    "RtlExpr.Mux",
    "RtlExpr.Concat",
    "RtlExpr.BitSelect",
    "RtlExpr.PartSelect",
    "RtlExpr.Resize",
    "RtlExpr.Cast",
    "AliasNameOrigin.Explicit",
    "AliasNameOrigin.Reflected",
    "AliasNameOrigin.Generated",
    "without transferring the removed name",
)

REQUIRED_BRIDGE_MARKERS: tuple[str, ...] = (
    "final class NamedWireAliasNativePhase(",
    "deferPreferredExpressionSource: Boolean = false",
    "PhaseRemoveIntermediateUnnameds",
    "alias.isNamed",
    SOURCE_NAME_LOOKUP,
    "alias.isEmptyOfTag",
    "NativeWireNameProvenance.successorExpressionOrigin(alias)",
    "NativeWireNameProvenance.origin(candidate.source)",
    ".getOrElse(NameOrigin.Unknown)",
    "NamedWireAliasEliminationPass.run",
    "WireAliasPassConfiguration.selectedForTesting",
    "statement.walkRemapDrivingExpressions",
    "reference eq alias",
    "aliasAssignment.removeStatement()",
    "alias.removeStatement()",
    "executed_before_name_allocation",
    "eliminated_names",
    "ParameterizedStreamFifoNamedPassWitness",
    "eliminated no named alias",
)

REQUIRED_TEST_MARKERS: tuple[str, ...] = (
    "disabled by default",
    "exact symbol identity",
    "recursive expression rewriting",
    "neighboring symbols remain untouched",
    "source provenance rather than emitted-name spelling selects candidates",
    "shorter meaningful direct alias wins",
    "equal-length meaningful names use a deterministic lexical identity tie-break",
    "meaningful underscore name beats generated provenance regardless of length",
    "meaningful name beats unnamed provenance through a removable direct chain",
    "meaningful name survives independently eligible unnamed expression removal",
    "protected direct source remains the anchor",
    "unsafe explicitly named alias is retained",
    "public hierarchical preservation probe attribute comment and source contracts are retained",
    "complete WIDTH and DEPTH domain",
    "fixed point and the pass is idempotent",
    "surviving names and metadata remain unchanged",
    "without transferring the removed name",
    "invalid canonical input fails closed",
    "deterministic",
    "component names and source paths do not affect",
)

REQUIRED_WORKFLOW_MARKERS: tuple[str, ...] = (
    "check-wa05-pass.py --self-test",
    "check-wa05-pass.py",
    "NamedWireAliasEliminationPassSpec",
    "ParameterizedStreamFifoNamedPassWitness",
    "wire-alias-named.v",
    "wire-alias-named-report.json",
    "eliminated_names",
    "validate_wire_assignment_equivalence.py",
    "cmp -s",
    "executed_before_name_allocation",
)

REQUIRED_README_MARKERS: tuple[str, ...] = (
    "WA-05",
    "NamedWireAliasEliminationPass",
    "explicit source name",
    "without transferring",
    "source location",
    "disabled by default",
    "WIDTH",
    "DEPTH",
    "common pre-pass",
    "512",
    "WA-07",
)

ROADMAP_ENTRY = re.compile(
    r"^- \[(?P<checked>[ xX])\] \*\*(?P<id>WA-[0-9]+)\s+—(?P<body>[\s\S]*?)(?=^- \[[ xX]\] \*\*WA-[0-9]+\s+—|\Z)",
    re.MULTILINE,
)


def scan_text(path: Path, text: str, rules: Sequence[TextRule]) -> list[str]:
    failures: list[str] = []
    for rule in rules:
        match = rule.pattern.search(text)
        if match is not None:
            line = text.count("\n", 0, match.start()) + 1
            failures.append(f"{path}:{line}: {rule.code}: {rule.message}")
    return failures


def require_markers(
    path: Path,
    text: str,
    markers: Sequence[str],
    code: str,
) -> list[str]:
    return [
        f"{path}: {code}: missing required marker {marker!r}"
        for marker in markers
        if marker not in text
    ]


def bridge_phase_failures(path: Path, text: str) -> list[str]:
    """Admit one reviewed Nameable metadata read, retaining all recognition bans."""
    failures: list[str] = []
    helper = re.search(
        r"private def explicitSourceName\(alias: BaseType\): Option\[String\] = \{([\s\S]*?)\n  \}",
        text,
    )
    if (helper is None or text.count(SOURCE_NAME_LOOKUP) != 1 or
            SOURCE_NAME_LOOKUP not in helper.group(1) or
            SOURCE_NAME_GUARD not in helper.group(1)):
        failures.append(
            f"{path}: WA05-BRIDGE-NAMING-METADATA: require exactly one guarded pre-allocation Nameable source-name read"
        )
    if helper is not None:
        failures.extend(require_markers(
            path, helper.group(1), SOURCE_NAME_PROVENANCE, "WA05-BRIDGE-NAMING-METADATA",
        ))
    failures.extend(require_markers(
        path, text, SUCCESSOR_BRIDGE_MARKERS, "WA05-BRIDGE-SUCCESSOR-PROVENANCE",
    ))
    successor_sections = (
        (
            "private def candidateSnapshot(",
            "private def explicitSourceName(",
            (
                "NativeWireNameProvenance.successorExpressionOrigin(alias)",
                BRIDGE_DISPLAY_NAME_LOOKUP,
            ),
        ),
        (
            "private def selectRewrite(",
            "private def aliasIsPreferred(",
            (
                "NativeWireNameProvenance.origin(candidate.source)",
                ".getOrElse(NameOrigin.Unknown)",
                "candidateForValue(candidate.source, sourceOrigin, statements)",
                "proveCandidate(pc, value)",
                "wholeRhsUse(_, candidate.alias)",
                "wholeRhsUse(_, sourceCandidate.alias)",
                "PreferredSourceRewrite(candidate, proof, sourceCandidate, sourceProof)",
                "expressionSourceIsIndependentlyRemovable",
                "Right(DeferredExpressionRewrite)",
                "Right(ForwardRewrite(candidate, proof))",
            ),
        ),
        (
            "private def aliasIsPreferred(",
            "private def meaningfulName(",
            (
                "meaningfulName(candidate.nameOrigin)",
                "meaningfulName(sourceOrigin)",
                "implicitly[Ordering[(Int, String, String)]].lt",
                BRIDGE_TIE_NAME_LOOKUP,
                "sourceOrigin == NameOrigin.Unnamed || sourceOrigin == NameOrigin.Generated",
            ),
        ),
        (
            "private def meaningfulName(",
            "private def expressionSourceIsIndependentlyRemovable(",
            (
                "case NameOrigin.Explicit(name)  => Some(name)",
                "case NameOrigin.Reflected(name) => Some(name)",
                "case _                          => None",
            ),
        ),
        (
            "private def expressionSourceIsIndependentlyRemovable(",
            "private def wholeRhsUse(",
            (
                "case NameOrigin.Unnamed",
                "new UnnamedWireExpressionNativePhase().isIndependentlyRemovable",
                "case NameOrigin.Explicit(_) | NameOrigin.Reflected(_) | NameOrigin.Generated",
                "new NamedWireExpressionNativePhase().isIndependentlyRemovable",
                "case NameOrigin.Unknown => false",
            ),
        ),
        (
            "private def applyCanonicalDecision(",
            "private def sourceKind(",
            (
                "NativeWireNameProvenance.origin(candidate.source)",
                ".getOrElse(NameOrigin.Unknown)",
                "nameOrigin = candidate.nameOrigin",
                "NamedWireAliasEliminationPass.run",
            ),
        ),
        (
            "private def reportOrigin(",
            "private def rewriteNativeIdentity(",
            (
                "case NameOrigin.Explicit(name)  => AliasNameOrigin.Explicit(name)",
                "case NameOrigin.Reflected(name) => AliasNameOrigin.Reflected(name)",
                "case NameOrigin.Generated       => AliasNameOrigin.Generated",
            ),
        ),
    )
    for start, end, markers in successor_sections:
        begin = text.find(start)
        finish = text.find(end, begin + len(start)) if begin >= 0 else -1
        if begin < 0 or finish <= begin:
            failures.append(
                f"{path}: WA05-BRIDGE-SUCCESSOR-PROVENANCE: missing bounded helper {start!r}"
            )
        else:
            failures.extend(require_markers(
                path,
                text[begin:finish],
                markers,
                "WA05-BRIDGE-SUCCESSOR-PROVENANCE",
            ))
    # Remove only the three authenticated metadata/display reads. Every other
    # getName, getPartialName and definitionName access remains forbidden.
    allowed_lookups = (
        (SOURCE_NAME_LOOKUP, "retainedHistoricalSourceNameMetadata"),
        (BRIDGE_DISPLAY_NAME_LOOKUP, "retainedCandidateDisplayNameMetadata"),
        (BRIDGE_TIE_NAME_LOOKUP, "retainedSourceTieNameMetadata"),
    )
    scanned = text
    for lookup, replacement in allowed_lookups:
        if text.count(lookup) != 1:
            failures.append(
                f"{path}: WA05-BRIDGE-NAMING-METADATA: require exactly one authenticated occurrence of {lookup!r}"
            )
        scanned = scanned.replace(lookup, replacement, 1)
    failures.extend(scan_text(path, scanned, BRIDGE_RULES))
    failures.extend(require_markers(
        path, text, NATIVE_SAFETY_MARKERS, "WA05-BRIDGE-PRESERVATION-METADATA",
    ))
    for start, end, markers, code in (
        ("private def hasReferencedMetadata(", "private def allowedUse(",
         REFERENCED_METADATA_MARKERS, "WA05-BRIDGE-REFERENCED-METADATA"),
        ("private def allowedUse(", "private def packedTypeProof(",
         CONTINUOUS_RECEIVER_MARKERS, "WA05-BRIDGE-CONTINUOUS-RECEIVER"),
    ):
        begin = text.find(start)
        finish = text.find(end, begin + len(start)) if begin >= 0 else -1
        if begin < 0 or finish <= begin:
            failures.append(f"{path}: {code}: missing bounded native safety helper")
        else:
            failures.extend(require_markers(path, text[begin:finish], markers, code))
    return failures


def provenance_failures(path: Path, text: str) -> list[str]:
    """Authenticate the shared exact pre-allocation Nameable classifier."""
    failures: list[str] = []
    start = text.find("private[examples] object NativeWireNameProvenance")
    if start < 0:
        return [f"{path}: WA05-NAME-PROVENANCE: shared provenance helper is missing"]
    body = text[start:]
    failures.extend(require_markers(
        path, body, SUCCESSOR_PROVENANCE_MARKERS, "WA05-NAME-PROVENANCE",
    ))
    if body.count(PROVENANCE_NAME_LOOKUP) != 1:
        failures.append(
            f"{path}: WA05-NAME-PROVENANCE: require exactly one authenticated Nameable name read"
        )
    scanned = body.replace(PROVENANCE_NAME_LOOKUP, "retainedProvenanceNameMetadata", 1)
    failures.extend(scan_text(path, scanned, BRIDGE_RULES))
    return failures


def roadmap_entries(text: str) -> dict[str, tuple[bool, str]]:
    values: dict[str, tuple[bool, str]] = {}
    for match in ROADMAP_ENTRY.finditer(text):
        item = match.group("id")
        if item in values:
            raise AssertionError(f"roadmap repeats {item}")
        values[item] = (match.group("checked").lower() == "x", match.group("body"))
    return values


def roadmap_failures(path: Path, text: str) -> list[str]:
    try:
        entries = roadmap_entries(text)
    except AssertionError as error:
        return [f"{path}: WA05-ROADMAP: {error}"]

    failures: list[str] = []
    for item in ("WA-04", "WA-05", "WA-06"):
        if item not in entries:
            failures.append(f"{path}: WA05-ROADMAP: missing {item}")
    if failures:
        return failures

    wa04_checked, wa04_body = entries["WA-04"]
    wa05_checked, wa05_body = entries["WA-05"]
    wa06_checked, wa06_body = entries["WA-06"]
    if not wa04_checked or "**Status:** `COMPLETED`" not in wa04_body:
        failures.append(f"{path}: WA05-DEPENDENCY: WA-04 must remain completed")

    if wa05_checked:
        if "**Status:** `COMPLETED`" not in wa05_body:
            failures.append(f"{path}: WA05-STATUS: checked WA-05 must be COMPLETED")
        if wa06_checked:
            if "**Status:** `COMPLETED`" not in wa06_body:
                failures.append(
                    f"{path}: WA05-NEXT-STATUS: checked WA-06 must be COMPLETED"
                )
        elif "**Status:** `READY`" not in wa06_body:
            failures.append(f"{path}: WA05-NEXT-STATUS: WA-06 must be READY after WA-05")
    else:
        if wa06_checked:
            failures.append(f"{path}: WA05-DEPENDENCY: WA-06 cannot complete while WA-05 is open")
        if "**Status:** `READY`" not in wa05_body:
            failures.append(f"{path}: WA05-STATUS: open WA-05 must be READY")
        if "**Status:** `BLOCKED`" not in wa06_body or "WA-05" not in wa06_body:
            failures.append(f"{path}: WA05-NEXT-STATUS: WA-06 must remain BLOCKED by WA-05")

    required_scope = (
        "same safety contract",
        "explicitly named internal aliases",
        "Do not rename or transfer",
        "Report each removed name and source location deterministically",
        "shared parameterized StreamFifo",
        "common pre-pass reference",
    )
    for marker in required_scope:
        if marker.lower() not in wa05_body.lower():
            failures.append(
                f"{path}: WA05-ROADMAP-SCOPE: WA-05 entry is missing {marker!r}"
            )
    return failures


def manifest_failures(path: Path, value: object) -> list[str]:
    if not isinstance(value, dict):
        return [f"{path}: WA05-MANIFEST: root must be an object"]
    witness = value.get("shared_witness")
    if not isinstance(witness, dict):
        return [f"{path}: WA05-MANIFEST: shared_witness is missing"]
    slots = witness.get("future_pass_outputs")
    if not isinstance(slots, list):
        return [f"{path}: WA05-MANIFEST: future_pass_outputs is missing"]
    matching = [
        slot
        for slot in slots
        if isinstance(slot, dict) and slot.get("activation_item") == "WA-05"
    ]
    if len(matching) != 1:
        return [f"{path}: WA05-MANIFEST: expected exactly one WA-05 slot"]
    expected = {
        "activation_item": "WA-05",
        "candidate": "morphhdl-passes/build/pass-outputs/wire-alias-named.v",
        "pass_id": "wire-alias-named",
    }
    if matching[0] != expected:
        return [
            f"{path}: WA05-MANIFEST: WA-05 slot changed; expected {expected}, observed {matching[0]}"
        ]
    return []


def check_repository(root: Path) -> list[str]:
    pass_root = root / "morphhdl-passes"
    paths = {
        "source": pass_root / "src/main/scala/morphhdl/passes/transform/NamedWireAliasEliminationPass.scala",
        "tests": pass_root / "src/test/scala/morphhdl/passes/transform/NamedWireAliasEliminationPassSpec.scala",
        "bridge": pass_root / "examples/NamedWireAliasNativeBridge.scala",
        "provenance": pass_root / "examples/NativeWireExpressionCodec.scala",
        "metadata": root / "morphhdl/src/main/scala/spinal/core/internals/NativeWireAssignmentMetadata.scala",
        "witness": pass_root / "examples/ParameterizedStreamFifo.scala",
        "roadmap": pass_root / "morphhdl-ir-wire-assignment-passes-todo.md",
        "readme": pass_root / "README.md",
        "workflow": root / ".github/workflows/morphhdl-passes.yml",
        "manifest": pass_root / "tests/formal/wire_assignment_ir/manifest.json",
        "signatures": pass_root / "tests/formal_model/wire_assignment_ir/expected-signatures.json",
    }
    failures: list[str] = []
    for label, path in paths.items():
        if not path.is_file():
            failures.append(f"WA05-{label.upper()}-MISSING: {path.relative_to(root)}")
    if failures:
        return sorted(failures)

    source_text = paths["source"].read_text(encoding="utf-8")
    bridge_text = paths["bridge"].read_text(encoding="utf-8")
    provenance_text = paths["provenance"].read_text(encoding="utf-8")
    test_text = paths["tests"].read_text(encoding="utf-8")
    witness_text = paths["witness"].read_text(encoding="utf-8")
    roadmap_text = paths["roadmap"].read_text(encoding="utf-8")
    readme_text = paths["readme"].read_text(encoding="utf-8")
    workflow_text = paths["workflow"].read_text(encoding="utf-8")

    failures.extend(scan_text(paths["source"].relative_to(root), source_text, GENERIC_RULES))
    failures.extend(require_markers(
        paths["source"].relative_to(root), source_text, REQUIRED_SOURCE_MARKERS,
        "WA05-SOURCE-CONTRACT-MISSING",
    ))
    phase_start = bridge_text.find("final class NamedWireAliasNativePhase(")
    phase_end = bridge_text.find("final case class NamedWireAliasNativeReport")
    if phase_start < 0 or phase_end <= phase_start:
        failures.append(
            f"{paths['bridge'].relative_to(root)}: WA05-BRIDGE-BOUNDARY: unable to isolate native bridge phase"
        )
        bridge_phase_text = bridge_text
    else:
        bridge_phase_text = bridge_text[phase_start:phase_end]
    failures.extend(bridge_phase_failures(paths["bridge"].relative_to(root), bridge_phase_text))
    failures.extend(provenance_failures(
        paths["provenance"].relative_to(root), provenance_text,
    ))
    failures.extend(registered_identity_failures(
        {name: (pass_root / "examples" / name).read_text() for name in REGISTERED_IDENTITY_HOOKS},
        paths["metadata"].read_text(),
    ))
    failures.extend(require_markers(
        paths["bridge"].relative_to(root), bridge_text, REQUIRED_BRIDGE_MARKERS,
        "WA05-BRIDGE-CONTRACT-MISSING",
    ))
    failures.extend(require_markers(
        paths["tests"].relative_to(root), test_text, REQUIRED_TEST_MARKERS,
        "WA05-TEST-COVERAGE-MISSING",
    ))
    witness_markers = (
        "directNamedAlias",
        'setName("popPayloadNamedAlias")',
        "expressionUnnamedAlias(popPayloadSource)",
        "directNamedAlias(directUnnamedAlias(expressionUnnamedAlias(popPayloadSource)))",
    )
    failures.extend(require_markers(
        paths["witness"].relative_to(root), witness_text, witness_markers,
        "WA05-WITNESS-CONTRACT-MISSING",
    ))
    if "ExplicitNamedWireAliasSourceTag" in witness_text:
        failures.append(
            f"{paths['witness'].relative_to(root)}: WA05-WITNESS-FIXTURE-TAG: named witness must use ordinary untagged elaboration naming"
        )
    failures.extend(require_markers(
        paths["workflow"].relative_to(root), workflow_text, REQUIRED_WORKFLOW_MARKERS,
        "WA05-WORKFLOW-GATE-MISSING",
    ))
    failures.extend(require_markers(
        paths["readme"].relative_to(root), readme_text, REQUIRED_README_MARKERS,
        "WA05-README-CONTRACT-MISSING",
    ))
    failures.extend(roadmap_failures(paths["roadmap"].relative_to(root), roadmap_text))

    try:
        manifest = json.loads(paths["manifest"].read_text(encoding="utf-8"))
        failures.extend(manifest_failures(paths["manifest"].relative_to(root), manifest))
    except (OSError, json.JSONDecodeError) as error:
        failures.append(f"{paths['manifest'].relative_to(root)}: WA05-MANIFEST: {error}")

    try:
        registry = json.loads(paths["signatures"].read_text(encoding="utf-8"))
        registered = registry.get("files", {}) if isinstance(registry, dict) else {}
        required_registered = (
            paths["source"], paths["tests"], paths["bridge"], paths["witness"], paths["metadata"],
            Path(__file__).resolve(),
        )
        for registered_path in required_registered:
            key = registered_path.relative_to(root).as_posix()
            if key not in registered:
                failures.append(
                    f"{paths['signatures'].relative_to(root)}: WA05-SIGNATURE-MISSING: {key}"
                )
    except (OSError, json.JSONDecodeError) as error:
        failures.append(f"{paths['signatures'].relative_to(root)}: WA05-SIGNATURES: {error}")

    return sorted(failures)


def run_self_test() -> None:
    allowed = """package morphhdl.passes.transform
import morphhdl.ir.v1.{NameOrigin, SymbolId}
object Pass { def eligible(origin: NameOrigin, left: SymbolId, right: SymbolId) =
  origin match { case NameOrigin.Explicit(_) => left == right; case _ => false } }
"""
    if scan_text(Path("Allowed.scala"), allowed, GENERIC_RULES):
        raise AssertionError("component-generic explicit-provenance pass source was rejected")

    mutations = (
        ("val selected = StreamFifo", "WA05-COMPONENT-SPECIAL-CASE"),
        ("module.logicalName == \"special\"", "WA05-MODULE-NAME-RECOGNITION"),
        ("import spinal.core._", "WA05-SPINAL-IMPLEMENTATION-COUPLING"),
        ("symbol == \"_zz_1\"", "WA05-EMITTED-NAME-RECOGNITION"),
        ("scala.io.Source.fromFile(path)", "WA05-FILE-TEXT-INGRESS"),
        ("val candidate = \"named.*\".r", "WA05-REGEX-CANDIDATE-DISCOVERY"),
    )
    for text, code in mutations:
        failures = scan_text(Path("Mutant.scala"), text, GENERIC_RULES)
        if not any(code in failure for failure in failures):
            raise AssertionError(f"mutation was not rejected by {code}")

    # Mutate the actual production bridge: guards repeated in other helpers
    # must not hide a missing guard at the receiver or metadata proof boundary.
    bridge_source = (Path(__file__).resolve().parents[1] /
                     "examples/NamedWireAliasNativeBridge.scala").read_text()
    phase_start = bridge_source.index("final class NamedWireAliasNativePhase(")
    phase_end = bridge_source.index("final case class NamedWireAliasNativeReport", phase_start)
    allowed_bridge = bridge_source[phase_start:phase_end]
    if bridge_phase_failures(Path("AllowedBridge.scala"), allowed_bridge):
        raise AssertionError("ordinary guarded Nameable metadata discovery was rejected")
    provenance_source = (Path(__file__).resolve().parents[1] /
                         "examples/NativeWireExpressionCodec.scala").read_text()
    if provenance_failures(Path("AllowedProvenance.scala"), provenance_source):
        raise AssertionError("exact shared Nameable provenance classifier was rejected")
    for marker in SUCCESSOR_PROVENANCE_MARKERS:
        mutant = provenance_source.replace(marker, "removedProvenanceGuard", 1)
        failures = provenance_failures(Path("MutantProvenance.scala"), mutant)
        if not any("WA05-NAME-PROVENANCE" in failure for failure in failures):
            raise AssertionError(
                f"native provenance mutation was not rejected: {marker}"
            )
    bridge_mutations = (
        (allowed_bridge + 'component.definitionName == "ChosenComponent"',
         "WA05-BRIDGE-COMPONENT-RECOGNITION"),
        (allowed_bridge + 'alias.getName() == "chosenSignal"',
         "WA05-BRIDGE-COMPONENT-RECOGNITION"),
        (allowed_bridge + 'source.getPartialName() == "chosenSignal"',
         "WA05-BRIDGE-COMPONENT-RECOGNITION"),
        (allowed_bridge + 'explicitName == "chosenSignal"',
         "WA05-BRIDGE-SOURCE-NAME-RECOGNITION"),
        (allowed_bridge + 'sourceName.startsWith("chosen")',
         "WA05-BRIDGE-SOURCE-NAME-RECOGNITION"),
        (allowed_bridge + 'alias.hasTag(ExplicitNamedWireAliasSourceTag)',
         "WA05-BRIDGE-FIXTURE-TAG-DEPENDENCY"),
        (allowed_bridge.replace(SOURCE_NAME_GUARD, "true"),
         "WA05-BRIDGE-NAMING-METADATA"),
        (allowed_bridge.replace("alias.isNamed && sourceNamed", "alias.isNamed"),
         "WA05-BRIDGE-NAMING-METADATA"),
        (allowed_bridge.replace("case _ => false", "case _ => true"),
         "WA05-BRIDGE-NAMING-METADATA"),
        (allowed_bridge.replace(SOURCE_NAME_LOOKUP, 'Some("chosenSignal")'),
         "WA05-BRIDGE-NAMING-METADATA"),
        (allowed_bridge + "\n" + SOURCE_NAME_LOOKUP,
         "WA05-BRIDGE-NAMING-METADATA"),
        (allowed_bridge.replace("alias.isEmptyOfTag", "true"),
         "WA05-BRIDGE-PRESERVATION-METADATA"),
        (allowed_bridge.replace("case _ => true", "case _ => false"),
         "WA05-BRIDGE-REFERENCED-METADATA"),
        (allowed_bridge.replace("value.source.exists(_ eq alias)", "false"),
         "WA05-BRIDGE-REFERENCED-METADATA"),
    )
    for text, code in bridge_mutations:
        failures = bridge_phase_failures(Path("MutantBridge.scala"), text)
        if not any(code in failure for failure in failures):
            raise AssertionError(f"bridge mutation was not rejected by {code}")
    for marker in SUCCESSOR_BRIDGE_MARKERS:
        mutant = allowed_bridge.replace(marker, "removedSuccessorProvenance")
        failures = bridge_phase_failures(Path("MutantBridge.scala"), mutant)
        if not any("WA05-BRIDGE-SUCCESSOR-PROVENANCE" in failure for failure in failures):
            raise AssertionError(
                f"successor bridge provenance mutation was not rejected: {marker}"
            )
    for marker in NATIVE_SAFETY_MARKERS:
        failures = bridge_phase_failures(
            Path("MutantBridge.scala"), allowed_bridge.replace(marker, "removedSafetyGuard"),
        )
        if not any("WA05-BRIDGE-PRESERVATION-METADATA" in failure for failure in failures):
            raise AssertionError(f"native safety mutation was not rejected: {marker}")
    repository = Path(__file__).resolve().parents[2]
    hooks = {name: (repository / "morphhdl-passes/examples" / name).read_text()
             for name in REGISTERED_IDENTITY_HOOKS}
    metadata = (repository /
                "morphhdl/src/main/scala/spinal/core/internals/NativeWireAssignmentMetadata.scala").read_text()
    if registered_identity_failures(hooks, metadata):
        raise AssertionError("registered identity preservation was rejected")
    for name, marker in REGISTERED_IDENTITY_HOOKS.items():
        mutant = dict(hooks)
        mutant[name] = mutant[name].replace(marker, "removedRegistryGuard")
        if not any("WA05-REGISTERED-IDENTITY-HOOK" in failure
                   for failure in registered_identity_failures(mutant, metadata)):
            raise AssertionError(f"registered identity hook removal survived: {name}")
    for marker in REGISTERED_IDENTITY_MARKERS:
        mutant = metadata.replace(marker, "removedRegistryInventory")
        if not any("WA05-REGISTERED-IDENTITY-INVENTORY" in failure
                   for failure in registered_identity_failures(hooks, mutant)):
            raise AssertionError(f"registered identity inventory removal survived: {marker}")
    for before, after in (
        ("case _ => false", "case _ => true"),
        ("case _: ParameterizedMemoryTag | _: ParameterizedMemoryDepthOverrideTag => true",
         "case _: SpinalTag => true"),
        ("case _: ParameterizedMemoryTag | _: ParameterizedMemoryDepthOverrideTag => true",
         "case _: ParameterizedMemoryTag => true"),
    ):
        mutant = metadata.replace(before, after, 1)
        if not any("WA05-REFERENCE-FREE-TAGS" in failure
                   for failure in registered_identity_failures(hooks, mutant)):
            raise AssertionError("unsafe or incomplete reference-free metadata classifier survived")
    for start, end, markers, code in (
        ("private def hasReferencedMetadata(", "private def allowedUse(",
         REFERENCED_METADATA_MARKERS, "WA05-BRIDGE-REFERENCED-METADATA"),
        ("private def allowedUse(", "private def packedTypeProof(",
         CONTINUOUS_RECEIVER_MARKERS, "WA05-BRIDGE-CONTINUOUS-RECEIVER"),
    ):
        begin, finish = allowed_bridge.index(start), allowed_bridge.index(end)
        body = allowed_bridge[begin:finish]
        for marker in markers:
            mutant = (allowed_bridge[:begin] + body.replace(marker, "removedSafetyGuard") +
                      allowed_bridge[finish:])
            failures = bridge_phase_failures(Path("MutantBridge.scala"), mutant)
            if not any(code in failure for failure in failures):
                raise AssertionError(f"native proof-boundary mutation was not rejected: {marker}")

    open_roadmap = """- [x] **WA-04 — Unnamed**

  **Status:** `COMPLETED`

- [ ] **WA-05 — Named**

  **Status:** `READY`
  same safety contract; explicitly named internal aliases; Do not rename or transfer;
  Report each removed name and source location deterministically;
  shared parameterized StreamFifo; common pre-pass reference.

- [ ] **WA-06 — Combined**

  **Status:** `BLOCKED` by WA-05.
"""
    wa05_completed = open_roadmap.replace(
        "- [ ] **WA-05", "- [x] **WA-05"
    ).replace(
        "**Status:** `READY`\n  same safety contract",
        "**Status:** `COMPLETED`\n  same safety contract",
    ).replace("**Status:** `BLOCKED` by WA-05.", "**Status:** `READY`.")
    wa06_completed = wa05_completed.replace(
        "- [ ] **WA-06", "- [x] **WA-06"
    ).replace("**Status:** `READY`.", "**Status:** `COMPLETED`.")
    for value in (open_roadmap, wa05_completed, wa06_completed):
        if roadmap_failures(Path("roadmap.md"), value):
            raise AssertionError("valid WA-05 roadmap transition state was rejected")

    invalid_transition = open_roadmap.replace(
        "- [ ] **WA-06", "- [x] **WA-06"
    ).replace("**Status:** `BLOCKED` by WA-05.", "**Status:** `COMPLETED`.")
    if not roadmap_failures(Path("roadmap.md"), invalid_transition):
        raise AssertionError("WA-06 completion without WA-05 was not rejected")

    manifest = {
        "shared_witness": {
            "future_pass_outputs": [
                {
                    "activation_item": "WA-05",
                    "candidate": "morphhdl-passes/build/pass-outputs/wire-alias-named.v",
                    "pass_id": "wire-alias-named",
                }
            ]
        }
    }
    if manifest_failures(Path("manifest.json"), manifest):
        raise AssertionError("valid WA-05 manifest slot was rejected")
    manifest["shared_witness"]["future_pass_outputs"][0]["candidate"] = "wrong.v"
    if not manifest_failures(Path("manifest.json"), manifest):
        raise AssertionError("mutated WA-05 manifest slot was not rejected")

    print("WA-05 pass contract self-tests passed.")


def parse_args(argv: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(list(argv))


def main(argv: Iterable[str] = sys.argv[1:]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        return 0
    root = (args.repo_root or Path(__file__).resolve().parents[2]).resolve()
    failures = check_repository(root)
    if failures:
        print("WA-05 pass contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("WA-05 pass contract passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
