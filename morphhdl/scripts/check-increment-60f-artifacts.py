#!/usr/bin/env python3
"""Fail-closed inventory, regeneration and cross-Scala evidence gates for 60f."""
from __future__ import annotations

import argparse
import contextlib
import functools
import importlib.util
import io
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


COUNTS = {"boundaries": 70, "pure": 29, "compatibility": 114}
REGRESSIONS = {
    "paramrtl": (234, 23),
    "frontend": (257, 22),
    "backends/verilog": (148, 21),
    "morphhdl": (819, 78),
    "morphir": (32, 2),
    "morphplugin": (16, 2),
    "core": (5, 1),
    "morphhdl-passes": (99, 11),
}


# Exact suite identities at the 60f qualification boundary. These are frozen
# data, not runtime discovery or a count-only substitute for inherited suites.
EXPECTED_SUITES = {
    "paramrtl": frozenset("""
        morphhdl.paramrtl.AddressWidthExpressionTests
        morphhdl.paramrtl.AsynchronousEnabledRegisterValidatorTests
        morphhdl.paramrtl.AsynchronousRegisterValidatorTests
        morphhdl.paramrtl.BoolExpressionAnalysisTests
        morphhdl.paramrtl.BooleanLocalParameterTests
        morphhdl.paramrtl.BooleanParameterBindingTests
        morphhdl.paramrtl.CeilLog2ExpressionTests
        morphhdl.paramrtl.CombinationalIfValidatorTests
        morphhdl.paramrtl.GenerateCaseValidatorTests
        morphhdl.paramrtl.GenerateForValidatorTests
        morphhdl.paramrtl.GenerateIfValidatorTests
        morphhdl.paramrtl.HierarchyValidatorTests
        morphhdl.paramrtl.IntExpressionAnalysisTests
        morphhdl.paramrtl.MinMaxExpressionTests
        morphhdl.paramrtl.ParamExpressionValidatorTests
        morphhdl.paramrtl.ParamRtlValidatorTests
        morphhdl.paramrtl.SynchronousCounterValidatorTests
        morphhdl.paramrtl.SynchronousEnabledRegisterValidatorTests
        morphhdl.paramrtl.SynchronousReadFirstSimpleDualPortMemoryValidatorTests
        morphhdl.paramrtl.SynchronousReadFirstSinglePortMemoryValidatorTests
        morphhdl.paramrtl.SynchronousRegisterValidatorTests
        morphhdl.paramrtl.SynchronousStreamFifoValidatorTests
        morphhdl.paramrtl.SynchronousStreamM2sPipeValidatorTests
    """.split()),
    "frontend": frozenset("""
        morphhdl.frontend.AddressWidthFrontendTests
        morphhdl.frontend.AsynchronousEnabledRegisterFrontendTests
        morphhdl.frontend.AsynchronousRegisterFrontendTests
        morphhdl.frontend.BooleanLocalParameterFrontendTests
        morphhdl.frontend.BooleanParameterBindingFrontendTests
        morphhdl.frontend.CeilLog2FrontendTests
        morphhdl.frontend.CombinationalIfFrontendTests
        morphhdl.frontend.FrontendProvenanceTests
        morphhdl.frontend.GenerateCaseFrontendTests
        morphhdl.frontend.GenerateForFrontendTests
        morphhdl.frontend.GenerateIfFrontendTests
        morphhdl.frontend.HdlBoolTests
        morphhdl.frontend.HdlIntTests
        morphhdl.frontend.LocalParameterFrontendTests
        morphhdl.frontend.SynchronousCounterFrontendTests
        morphhdl.frontend.SynchronousEnabledRegisterFrontendTests
        morphhdl.frontend.SynchronousReadFirstSimpleDualPortMemoryFrontendTests
        morphhdl.frontend.SynchronousReadFirstSinglePortMemoryFrontendTests
        morphhdl.frontend.SynchronousRegisterFrontendTests
        morphhdl.frontend.SynchronousStreamFifoFrontendTests
        morphhdl.frontend.SynchronousStreamM2sPipeFrontendTests
        morphhdl.frontend.compatibility.SpinalImportCompatibilityTests
    """.split()),
    "backends/verilog": frozenset("""
        morphhdl.backend.verilog2001.AddressWidthEmitterTests
        morphhdl.backend.verilog2001.AsynchronousEnabledRegisterEmitterTests
        morphhdl.backend.verilog2001.AsynchronousRegisterEmitterTests
        morphhdl.backend.verilog2001.BooleanLocalParameterEmitterTests
        morphhdl.backend.verilog2001.BooleanParameterBindingEmitterTests
        morphhdl.backend.verilog2001.CeilLog2EmitterTests
        morphhdl.backend.verilog2001.CombinationalIfEmitterTests
        morphhdl.backend.verilog2001.DerivedWidthEmitterTests
        morphhdl.backend.verilog2001.GenerateCaseEmitterTests
        morphhdl.backend.verilog2001.GenerateIfEmitterTests
        morphhdl.backend.verilog2001.LaneArrayEmitterTests
        morphhdl.backend.verilog2001.MinMaxEmitterTests
        morphhdl.backend.verilog2001.ParameterForwardingEmitterTests
        morphhdl.backend.verilog2001.SynchronousCounterEmitterTests
        morphhdl.backend.verilog2001.SynchronousEnabledRegisterEmitterTests
        morphhdl.backend.verilog2001.SynchronousReadFirstSimpleDualPortMemoryEmitterTests
        morphhdl.backend.verilog2001.SynchronousReadFirstSinglePortMemoryEmitterTests
        morphhdl.backend.verilog2001.SynchronousRegisterEmitterTests
        morphhdl.backend.verilog2001.SynchronousStreamFifoEmitterTests
        morphhdl.backend.verilog2001.SynchronousStreamM2sPipeEmitterTests
        morphhdl.backend.verilog2001.Verilog2001EmitterTests
    """.split()),
    "morphhdl": frozenset("""
        morphhdl.BackendSyncMergeIsolationTests
        morphhdl.BoundedRecursivePowerTests
        morphhdl.BoundedRecursiveSafetyTests
        morphhdl.CapturedAssignmentNormalizationTests
        morphhdl.CapturedDomainWidthEquivalenceTests
        morphhdl.CounterSingleAuthorityParityTests
        morphhdl.ExternalHierarchyBoolLiteralBindingTests
        morphhdl.FormalParameterClonePropagationTests
        morphhdl.FormalParameterIdentityTests
        morphhdl.GenericExpressionAndStreamTests
        morphhdl.GenericProcessLoweringTests
        morphhdl.HierarchyParameterBindingTests
        morphhdl.MorphCanonicalIrHandoffTests
        morphhdl.MorphSingleSourceVerilogTests
        morphhdl.MorphVerilogTests
        morphhdl.NativeAxi4SlaveFactoryFormalEquivalenceTests
        morphhdl.NativeAxi4SlaveFactoryParameterizedOffsetTests
        morphhdl.NativeLibraryMigrationFormalEquivalenceTests
        morphhdl.NativeLibraryMigrationTests
        morphhdl.NativeLibraryReuseTests
        morphhdl.NativeStreamFifoCCCdcProofTests
        morphhdl.NativeStreamFifoCCFormalEquivalenceTests
        morphhdl.NativeStreamFifoCCParameterizedTests
        morphhdl.NativeStreamFifoFormalEquivalenceTests
        morphhdl.NativeSymbolicMemoryTests
        morphhdl.NativeTypedLibraryCallSurfaceTests
        morphhdl.NaturalSymbolicConditionalTests
        morphhdl.ParameterizedStreamFifoDepthTests
        morphhdl.ParameterizedStreamWidthAdapterTests
        morphhdl.ReduceBalancedTreeNativeContractTests
        morphhdl.SpinalEnumLocalParameterTests
        morphhdl.StreamFifoCompatibilityTests
        morphhdl.StructuralGenerateControlTests
        morphhdl.TypedBlackBoxGenericBindingTests
        morphhdl.TypedCounterAllOnesTests
        morphhdl.TypedElaborationControlTests
        morphhdl.TypedElaborationValueTests
        morphhdl.TypedParameterizedFactoryDirectionTests
        morphhdl.TypedParameterizedVecFormalEquivalenceTests
        morphhdl.TypedParameterizedVecTests
        morphhdl.TypedPrimitiveClosureFormalEquivalenceTests
        morphhdl.TypedStreamWidthAdapterFormalEquivalenceTests
        morphhdl.integration.ExternalSpinalVerilogBaselineTests
        spinal.core.CentralTypedAuthorityAdversarialTests
        spinal.core.FiniteAffineVecReadTests
        spinal.core.FiniteBitsIndexTests
        spinal.core.FiniteFormalBoundaryTests
        spinal.core.FiniteMemIdentityAdversarialTests
        spinal.core.PackedVecIdentityAdversarialTests
        spinal.core.ProceduralIdentityAdversarialTests
        spinal.core.ScalarStructuralIdentityAdversarialTests
        spinal.core.StructuralIdentityAdversarialTests
        spinal.core.TypedElaborationPrimitiveTests
        spinal.core.TypedExactDomainControlTests
        spinal.core.TypedExactDomainSafetyTests
        spinal.core.TypedPrimitiveClosureTests
        spinal.core.TypedProjectionOwnershipTests
        spinal.core.TypedVecShapeTests
        spinal.core.VecEmittedIdentityAdversarialTests
        spinal.core.internals.ParameterizedDataShapeTests
        spinal.core.internals.ParameterizedVerilogStructuralLexicalTests
        spinal.core.internals.ParameterizedVerilogTests
        spinal.core.internals.PureSIntCastTests
        spinal.core.internals.RetainedWidthExpressionEquivalenceTests
        spinal.core.internals.SignedDeclarationPublicationTests
        spinal.core.internals.SignednessBoundaryTests
        spinal.core.internals.SignednessCompatibilityTests
        spinal.core.internals.TypedBalancedReductionCallbackPolicyTests
        spinal.core.internals.TypedBalancedReductionCaptureSafetyTests
        spinal.core.internals.TypedBalancedReductionCaptureTests
        spinal.core.internals.TypedBalancedReductionClosedGraphTests
        spinal.core.internals.TypedBalancedReductionOperatorReplayTests
        spinal.core.internals.TypedBalancedReductionPlanTests
        spinal.core.internals.TypedBalancedReductionPublicationSafetyTests
        spinal.core.internals.TypedBalancedReductionPublicationTests
        spinal.core.internals.TypedBalancedReductionStageReplayTests
        spinal.core.internals.TypedSignednessAuthorityTests
        spinal.core.internals.TypedSignednessResumeTests
    """.split()),
    "morphir": frozenset("""
        morphhdl.ir.v1.CanonicalIrHandoffSpec
        morphhdl.ir.v1.CanonicalIrV1Spec
    """.split()),
    "morphplugin": frozenset("""
        morphhdl.compiler.MorphHdlTypedElaborationControlComponentTests
        morphhdl.compiler.MorphHdlFrontendSymbolicEqualitySafetyComponentTests
    """.split()),
    "core": frozenset("""
        spinal.core.internals.SpinalVerilogPhasePlanTests
    """.split()),
    "morphhdl-passes": frozenset("""
        morphhdl.passes.adapter.CanonicalIrPassAdapterSpec
        morphhdl.passes.api.AllPassConfigurationSpec
        morphhdl.passes.api.PassContractsSpec
        morphhdl.passes.pipeline.WireAliasPassPipelineSpec
        morphhdl.passes.pipeline.WireAssignmentAllPassPipelineSpec
        morphhdl.passes.safety.WireAliasSafetyGateSpec
        morphhdl.passes.transform.NamedWireAliasEliminationPassSpec
        morphhdl.passes.transform.UnnamedWireAliasEliminationPassSpec
        morphhdl.passes.transform.UnnamedWireExpressionAlgebraSpec
        morphhdl.passes.transform.UnnamedWireExpressionEliminationPassSpec
        morphhdl.passes.transform.UnnamedWireExpressionSelectionSafetySpec
    """.split()),
}

# Approved successor inventory. The original 60f boundary above remains frozen;
# this exact union is admitted only after the successor production source audit.
INCREMENT_59D_SUITES = {
    "morphhdl": {
        "spinal.core.ElaborationWidthAuthorityTests": 7,
        "spinal.core.NativeSymbolicWidthProvenanceTests": 5,
        "spinal.core.internals.NativeWidthPublicationSafetyTests": 6,
        "spinal.core.internals.NativePublicationWidthTests": 1,
        "spinal.core.internals.TypedBalancedReductionWidthTransferTests": 11,
        "spinal.core.internals.TypedBalancedReductionWideningPublicationTests": 6,
    },
}

# 59e adds three suites and one test to each of three inherited suites. Keep
# these separate from the immutable 60f boundary and activate them only with its
# complete source profile, which also requires the 59f callback implementation.
INCREMENT_59E_SUITES = {
    "morphhdl": {
        "spinal.core.NativeCloneShapeContractTests": 4,
        "spinal.core.internals.TypedBalancedReductionCompositeTests": 23,
        "spinal.core.internals.TypedBalancedReductionCompositeCallbackPolicyTests": 12,
    },
}
INCREMENT_59E_INHERITED_TESTS = {
    "morphhdl": {
        "spinal.core.PackedVecIdentityAdversarialTests": 8,
        "spinal.core.internals.TypedBalancedReductionPublicationSafetyTests": 8,
        "spinal.core.internals.TypedBalancedReductionOperatorReplayTests": 24,
    },
}

# The width/composite integration adds one replay regression. Standalone E+F
# retains its exact historical 23-case composite suite.
INCREMENT_59D59E_JOINT_TESTS = {
    "morphhdl": {"spinal.core.internals.TypedBalancedReductionCompositeTests": 24},
}

INCREMENT_59H_SUITES = {
    "morphhdl": {
        "spinal.core.ParameterizedStructuralLexicalOwnerTests": 18,
        "spinal.core.internals.TypedBalancedReductionNestedOwnerTests": 18,
        "spinal.core.internals.TypedBalancedReductionStaticRedirectPolicyTests": 3,
    },
}

# Separately reviewed descendants extend the frozen inherited inventory by exact
# suite identity. Presence of arbitrary XML or a matching count grants nothing.
# A complete, tracked feature source inventory activates the reviewed additions;
# heads without that feature retain the exact inventory of their other reviewed features.
SUITE_EXTENSIONS = {
    "59g": {
        "sources": (
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionBridgePublicationTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionBridgeReplayTests.scala",
        ),
        "projects": {"morphhdl": frozenset((
            "spinal.core.internals.TypedBalancedReductionBridgePublicationTests",
            "spinal.core.internals.TypedBalancedReductionBridgeReplayTests",
        ))},
        "counts": {
            "spinal.core.internals.TypedBalancedReductionBridgePublicationTests": 3,
            "spinal.core.internals.TypedBalancedReductionBridgeReplayTests": 12,
            "spinal.core.internals.TypedBalancedReductionCallbackPolicyTests": 16,
        },
    },
    "59h": {
        "sources": (
            "morphhdl/contracts/increment-59h-source-review.json",
            "morphhdl/scripts/check-increment-59h-source-review.py",
            "morphhdl/src/test/scala/spinal/core/ParameterizedStructuralLexicalOwnerTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionNestedOwnerTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionStaticRedirectPolicyTests.scala",
        ),
        "projects": {project: frozenset(additions) for project, additions in INCREMENT_59H_SUITES.items()},
    },
    "59c": {
        "sources": (
            "morphhdl/src/main/scala/morphhdl/MorphNamedFieldVectors.scala",
            "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogFieldLayout.scala",
            "morphhdl/src/test/scala/morphhdl/NamedFieldVecTests.scala",
            "morphhdl/src/test/scala/morphhdl/NamedFieldVecHierarchyTests.scala",
            "morphhdl/src/test/scala/morphhdl/NamedFieldVecCollisionTests.scala",
            "morphhdl/src/test/scala/morphhdl/NamedFieldVecNestedWriteTests.scala",
            "morphhdl/src/test/scala/spinal/core/NamedFieldPackedAliasTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/ParameterizedVerilogFieldLayoutTests.scala",
        ),
        "projects": {"morphhdl": frozenset((
            "morphhdl.NamedFieldVecTests",
            "morphhdl.NamedFieldVecHierarchyTests",
            "morphhdl.NamedFieldVecCollisionTests",
            "morphhdl.NamedFieldVecNestedWriteTests",
            "spinal.core.NamedFieldPackedAliasTests",
            "spinal.core.internals.ParameterizedVerilogFieldLayoutTests",
        ))},
    },
    "59e": {
        "sources": (
            "core/src/main/scala/spinal/core/ParameterizedVecElementLayout.scala",
            "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeCallbackPolicy.scala",
            "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
            "morphhdl/src/test/scala/spinal/core/NativeCloneShapeContractTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeCallbackPolicyTests.scala",
        ),
        "projects": {project: frozenset(additions) for project, additions in INCREMENT_59E_SUITES.items()},
    },
    "59f": {
        "sources": (
            "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCertifiedCallbackPolicy.scala",
            "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCaptureSchema.scala",
            "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionScalarGraphReplay.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCertifiedCallbackPolicyTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionScalarGraphReplayTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionMuxWidthTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCallbackPublicationTests.scala",
        ),
        "projects": {
            "morphhdl": frozenset("""
                spinal.core.internals.TypedBalancedReductionCertifiedCallbackPolicyTests
                spinal.core.internals.TypedBalancedReductionScalarGraphReplayTests
                spinal.core.internals.TypedBalancedReductionMuxWidthTests
                spinal.core.internals.TypedBalancedReductionCallbackPublicationTests
            """.split()),
        },
    },
}

WA07A_SUITES = frozenset({
    "morphhdl.passes.transform.ConstantOperandFixedPointSpec",
    "morphhdl.passes.transform.ConstantOperandFourStateSpec",
    "morphhdl.passes.transform.ConstantOperandSimplificationPassSpec",
})

# WA-08 adds this exact three-case source-width authority regression. The
# immutable 60f catalog remains unchanged unless the complete WA-08 source
# overlay verifies and includes this newly added source file.
WA08_SUITES = {
    "morphhdl": {"spinal.core.internals.HierarchyResizeSourceWidthTests": 3},
}
WA08_SUITE_SOURCE = "morphhdl/src/test/scala/spinal/core/internals/HierarchyResizeSourceWidthTests.scala"

# Exact inherited suite counts observed in both complete Scala 2.12.18/2.13.12
# 60f report sets on final WA-08 head ddbfa0219fd8671ef0143984d37810eba3167707:
# 1,941 non-skipped tests in 191 suites; Morph contributes 1,105 in 103 suites.
# This WA-09-only baseline does not change historical minimum-count catalogs.
# It is selected only after the complete successor source overlay verifies.
# Canonical sorted JSON count-catalog SHA-256: c661f6902c251c7cebca9c2d124c1955df7a0e7172da3790cee1748831aad042
WA09_INHERITED_FEATURES = frozenset({
    "wa07a", "wa07b", "59c", "59d", "59e", "59f", "59g", "59h", "60g",
})
WA09_INHERITED_SUITE_COUNTS = {
    'paramrtl': {
        'morphhdl.paramrtl.AddressWidthExpressionTests': 11,
        'morphhdl.paramrtl.AsynchronousEnabledRegisterValidatorTests': 11,
        'morphhdl.paramrtl.AsynchronousRegisterValidatorTests': 12,
        'morphhdl.paramrtl.BoolExpressionAnalysisTests': 9,
        'morphhdl.paramrtl.BooleanLocalParameterTests': 14,
        'morphhdl.paramrtl.BooleanParameterBindingTests': 7,
        'morphhdl.paramrtl.CeilLog2ExpressionTests': 6,
        'morphhdl.paramrtl.CombinationalIfValidatorTests': 14,
        'morphhdl.paramrtl.GenerateCaseValidatorTests': 11,
        'morphhdl.paramrtl.GenerateForValidatorTests': 14,
        'morphhdl.paramrtl.GenerateIfValidatorTests': 15,
        'morphhdl.paramrtl.HierarchyValidatorTests': 18,
        'morphhdl.paramrtl.IntExpressionAnalysisTests': 5,
        'morphhdl.paramrtl.MinMaxExpressionTests': 6,
        'morphhdl.paramrtl.ParamExpressionValidatorTests': 12,
        'morphhdl.paramrtl.ParamRtlValidatorTests': 20,
        'morphhdl.paramrtl.SynchronousCounterValidatorTests': 6,
        'morphhdl.paramrtl.SynchronousEnabledRegisterValidatorTests': 11,
        'morphhdl.paramrtl.SynchronousReadFirstSimpleDualPortMemoryValidatorTests': 4,
        'morphhdl.paramrtl.SynchronousReadFirstSinglePortMemoryValidatorTests': 8,
        'morphhdl.paramrtl.SynchronousRegisterValidatorTests': 13,
        'morphhdl.paramrtl.SynchronousStreamFifoValidatorTests': 4,
        'morphhdl.paramrtl.SynchronousStreamM2sPipeValidatorTests': 3,
    },
    'frontend': {
        'morphhdl.frontend.AddressWidthFrontendTests': 10,
        'morphhdl.frontend.AsynchronousEnabledRegisterFrontendTests': 14,
        'morphhdl.frontend.AsynchronousRegisterFrontendTests': 13,
        'morphhdl.frontend.BooleanLocalParameterFrontendTests': 8,
        'morphhdl.frontend.BooleanParameterBindingFrontendTests': 9,
        'morphhdl.frontend.CeilLog2FrontendTests': 5,
        'morphhdl.frontend.CombinationalIfFrontendTests': 16,
        'morphhdl.frontend.FrontendProvenanceTests': 11,
        'morphhdl.frontend.GenerateCaseFrontendTests': 13,
        'morphhdl.frontend.GenerateForFrontendTests': 20,
        'morphhdl.frontend.GenerateIfFrontendTests': 15,
        'morphhdl.frontend.HdlBoolTests': 18,
        'morphhdl.frontend.HdlIntTests': 32,
        'morphhdl.frontend.LocalParameterFrontendTests': 14,
        'morphhdl.frontend.SynchronousCounterFrontendTests': 5,
        'morphhdl.frontend.SynchronousEnabledRegisterFrontendTests': 13,
        'morphhdl.frontend.SynchronousReadFirstSimpleDualPortMemoryFrontendTests': 5,
        'morphhdl.frontend.SynchronousReadFirstSinglePortMemoryFrontendTests': 11,
        'morphhdl.frontend.SynchronousRegisterFrontendTests': 14,
        'morphhdl.frontend.SynchronousStreamFifoFrontendTests': 5,
        'morphhdl.frontend.SynchronousStreamM2sPipeFrontendTests': 4,
        'morphhdl.frontend.compatibility.SpinalImportCompatibilityTests': 2,
    },
    'backends/verilog': {
        'morphhdl.backend.verilog2001.AddressWidthEmitterTests': 7,
        'morphhdl.backend.verilog2001.AsynchronousEnabledRegisterEmitterTests': 7,
        'morphhdl.backend.verilog2001.AsynchronousRegisterEmitterTests': 8,
        'morphhdl.backend.verilog2001.BooleanLocalParameterEmitterTests': 3,
        'morphhdl.backend.verilog2001.BooleanParameterBindingEmitterTests': 4,
        'morphhdl.backend.verilog2001.CeilLog2EmitterTests': 11,
        'morphhdl.backend.verilog2001.CombinationalIfEmitterTests': 6,
        'morphhdl.backend.verilog2001.DerivedWidthEmitterTests': 6,
        'morphhdl.backend.verilog2001.GenerateCaseEmitterTests': 6,
        'morphhdl.backend.verilog2001.GenerateIfEmitterTests': 8,
        'morphhdl.backend.verilog2001.LaneArrayEmitterTests': 10,
        'morphhdl.backend.verilog2001.MinMaxEmitterTests': 8,
        'morphhdl.backend.verilog2001.ParameterForwardingEmitterTests': 7,
        'morphhdl.backend.verilog2001.SynchronousCounterEmitterTests': 5,
        'morphhdl.backend.verilog2001.SynchronousEnabledRegisterEmitterTests': 7,
        'morphhdl.backend.verilog2001.SynchronousReadFirstSimpleDualPortMemoryEmitterTests': 6,
        'morphhdl.backend.verilog2001.SynchronousReadFirstSinglePortMemoryEmitterTests': 6,
        'morphhdl.backend.verilog2001.SynchronousRegisterEmitterTests': 8,
        'morphhdl.backend.verilog2001.SynchronousStreamFifoEmitterTests': 5,
        'morphhdl.backend.verilog2001.SynchronousStreamM2sPipeEmitterTests': 3,
        'morphhdl.backend.verilog2001.Verilog2001EmitterTests': 17,
    },
    'morphhdl': {
        'morphhdl.BackendSyncMergeIsolationTests': 2,
        'morphhdl.BoundedRecursivePowerTests': 4,
        'morphhdl.BoundedRecursiveSafetyTests': 17,
        'morphhdl.CapturedAssignmentNormalizationTests': 19,
        'morphhdl.CapturedDomainWidthEquivalenceTests': 20,
        'morphhdl.CounterSingleAuthorityParityTests': 1,
        'morphhdl.ExternalHierarchyBoolLiteralBindingTests': 1,
        'morphhdl.FormalParameterClonePropagationTests': 6,
        'morphhdl.FormalParameterIdentityTests': 9,
        'morphhdl.GenericExpressionAndStreamTests': 9,
        'morphhdl.GenericProcessLoweringTests': 7,
        'morphhdl.HierarchyParameterBindingTests': 8,
        'morphhdl.MorphCanonicalIrHandoffTests': 14,
        'morphhdl.MorphSingleSourceVerilogTests': 14,
        'morphhdl.MorphVerilogTests': 77,
        'morphhdl.NamedFieldVecCollisionTests': 7,
        'morphhdl.NamedFieldVecHierarchyTests': 6,
        'morphhdl.NamedFieldVecNestedWriteTests': 20,
        'morphhdl.NamedFieldVecTests': 11,
        'morphhdl.NativeAxi4SlaveFactoryFormalEquivalenceTests': 2,
        'morphhdl.NativeAxi4SlaveFactoryParameterizedOffsetTests': 9,
        'morphhdl.NativeLibraryMigrationFormalEquivalenceTests': 2,
        'morphhdl.NativeLibraryMigrationTests': 4,
        'morphhdl.NativeLibraryReuseTests': 3,
        'morphhdl.NativeStreamFifoCCCdcProofTests': 3,
        'morphhdl.NativeStreamFifoCCFormalEquivalenceTests': 6,
        'morphhdl.NativeStreamFifoCCParameterizedTests': 19,
        'morphhdl.NativeStreamFifoFormalEquivalenceTests': 4,
        'morphhdl.NativeSymbolicMemoryTests': 14,
        'morphhdl.NativeTypedLibraryCallSurfaceTests': 5,
        'morphhdl.NaturalSymbolicConditionalTests': 5,
        'morphhdl.ParameterizedStreamFifoDepthTests': 8,
        'morphhdl.ParameterizedStreamWidthAdapterTests': 2,
        'morphhdl.ReduceBalancedTreeNativeContractTests': 8,
        'morphhdl.SpinalEnumLocalParameterTests': 6,
        'morphhdl.StreamFifoCompatibilityTests': 5,
        'morphhdl.StructuralGenerateControlTests': 12,
        'morphhdl.TypedBlackBoxGenericBindingTests': 4,
        'morphhdl.TypedCounterAllOnesTests': 1,
        'morphhdl.TypedElaborationControlTests': 7,
        'morphhdl.TypedElaborationValueTests': 20,
        'morphhdl.TypedParameterizedFactoryDirectionTests': 1,
        'morphhdl.TypedParameterizedVecFormalEquivalenceTests': 2,
        'morphhdl.TypedParameterizedVecTests': 35,
        'morphhdl.TypedPrimitiveClosureFormalEquivalenceTests': 2,
        'morphhdl.TypedStreamWidthAdapterFormalEquivalenceTests': 2,
        'morphhdl.integration.ExternalSpinalVerilogBaselineTests': 4,
        'spinal.core.CentralTypedAuthorityAdversarialTests': 2,
        'spinal.core.ElaborationWidthAuthorityTests': 7,
        'spinal.core.FiniteAffineVecReadTests': 8,
        'spinal.core.FiniteBitsIndexTests': 4,
        'spinal.core.FiniteFormalBoundaryTests': 11,
        'spinal.core.FiniteMemIdentityAdversarialTests': 9,
        'spinal.core.NamedFieldPackedAliasTests': 3,
        'spinal.core.NativeCloneShapeContractTests': 4,
        'spinal.core.NativeSymbolicWidthProvenanceTests': 5,
        'spinal.core.PackedVecIdentityAdversarialTests': 12,
        'spinal.core.ParameterizedStructuralLexicalOwnerTests': 18,
        'spinal.core.ProceduralIdentityAdversarialTests': 3,
        'spinal.core.ScalarStructuralIdentityAdversarialTests': 3,
        'spinal.core.StructuralIdentityAdversarialTests': 52,
        'spinal.core.TypedElaborationPrimitiveTests': 10,
        'spinal.core.TypedExactDomainControlTests': 4,
        'spinal.core.TypedExactDomainSafetyTests': 20,
        'spinal.core.TypedPrimitiveClosureTests': 28,
        'spinal.core.TypedProjectionOwnershipTests': 4,
        'spinal.core.TypedVecShapeTests': 38,
        'spinal.core.VecEmittedIdentityAdversarialTests': 7,
        'spinal.core.internals.HierarchyResizeSourceWidthTests': 3,
        'spinal.core.internals.NativePublicationWidthTests': 1,
        'spinal.core.internals.NativeWidthPublicationSafetyTests': 6,
        'spinal.core.internals.ParameterizedDataShapeTests': 14,
        'spinal.core.internals.ParameterizedVerilogFieldLayoutTests': 5,
        'spinal.core.internals.ParameterizedVerilogStructuralLexicalTests': 2,
        'spinal.core.internals.ParameterizedVerilogTests': 24,
        'spinal.core.internals.PureSIntCastTests': 13,
        'spinal.core.internals.RetainedWidthExpressionEquivalenceTests': 5,
        'spinal.core.internals.SignedDeclarationPublicationTests': 13,
        'spinal.core.internals.SignednessBoundaryTests': 15,
        'spinal.core.internals.SignednessCompatibilityTests': 22,
        'spinal.core.internals.TypedBalancedReductionBridgePublicationTests': 3,
        'spinal.core.internals.TypedBalancedReductionBridgeReplayTests': 12,
        'spinal.core.internals.TypedBalancedReductionCallbackPolicyTests': 16,
        'spinal.core.internals.TypedBalancedReductionCallbackPublicationTests': 1,
        'spinal.core.internals.TypedBalancedReductionCaptureSafetyTests': 12,
        'spinal.core.internals.TypedBalancedReductionCaptureTests': 10,
        'spinal.core.internals.TypedBalancedReductionCertifiedCallbackPolicyTests': 14,
        'spinal.core.internals.TypedBalancedReductionClosedGraphTests': 20,
        'spinal.core.internals.TypedBalancedReductionCompositeCallbackPolicyTests': 12,
        'spinal.core.internals.TypedBalancedReductionCompositeTests': 24,
        'spinal.core.internals.TypedBalancedReductionMuxWidthTests': 4,
        'spinal.core.internals.TypedBalancedReductionNestedOwnerTests': 18,
        'spinal.core.internals.TypedBalancedReductionOperatorReplayTests': 24,
        'spinal.core.internals.TypedBalancedReductionPlanTests': 8,
        'spinal.core.internals.TypedBalancedReductionPublicationSafetyTests': 8,
        'spinal.core.internals.TypedBalancedReductionPublicationTests': 4,
        'spinal.core.internals.TypedBalancedReductionScalarGraphReplayTests': 19,
        'spinal.core.internals.TypedBalancedReductionStageReplayTests': 23,
        'spinal.core.internals.TypedBalancedReductionStaticRedirectPolicyTests': 3,
        'spinal.core.internals.TypedBalancedReductionWideningPublicationTests': 6,
        'spinal.core.internals.TypedBalancedReductionWidthTransferTests': 11,
        'spinal.core.internals.TypedSignednessAuthorityTests': 26,
        'spinal.core.internals.TypedSignednessResumeTests': 5,
    },
    'morphir': {
        'morphhdl.ir.v1.CanonicalIrHandoffSpec': 10,
        'morphhdl.ir.v1.CanonicalIrV1Spec': 22,
    },
    'morphplugin': {
        'morphhdl.compiler.MorphHdlFrontendSymbolicEqualitySafetyComponentTests': 3,
        'morphhdl.compiler.MorphHdlTypedElaborationControlComponentTests': 13,
    },
    'core': {
        'spinal.core.internals.SpinalVerilogPhasePlanTests': 5,
    },
    'morphhdl-passes': {
        'morphhdl.passes.adapter.CanonicalIrPassAdapterSpec': 9,
        'morphhdl.passes.api.AllPassConfigurationSpec': 5,
        'morphhdl.passes.api.NativeRunnerSourceClosureSpec': 3,
        'morphhdl.passes.api.PassContractsSpec': 8,
        'morphhdl.passes.pipeline.WireAliasPassPipelineSpec': 9,
        'morphhdl.passes.pipeline.WireAssignmentAllPassPipelineSpec': 6,
        'morphhdl.passes.safety.WireAliasSafetyGateSpec': 20,
        'morphhdl.passes.transform.BooleanTernaryFourStateSpec': 4,
        'morphhdl.passes.transform.BooleanTernarySimplificationPassSpec': 14,
        'morphhdl.passes.transform.ConstantOperandFixedPointSpec': 2,
        'morphhdl.passes.transform.ConstantOperandFourStateSpec': 3,
        'morphhdl.passes.transform.ConstantOperandSimplificationPassSpec': 14,
        'morphhdl.passes.transform.NamedWireAliasEliminationPassSpec': 13,
        'morphhdl.passes.transform.UnnamedWireAliasEliminationPassSpec': 12,
        'morphhdl.passes.transform.UnnamedWireExpressionAlgebraSpec': 1,
        'morphhdl.passes.transform.UnnamedWireExpressionEliminationPassSpec': 12,
        'morphhdl.passes.transform.UnnamedWireExpressionSelectionSafetySpec': 9,
    },
}

# WA-09 changes two already reviewed pass suites and adds one pass, one core,
# and one public MorphVerilog suite.  Exact per-suite counts make it impossible
# to trade a removed inherited test for a new case elsewhere.
WA09_SUITES = {
    "morphhdl-passes": {
        "morphhdl.passes.pipeline.WireAssignmentAllPassPipelineSpec": 7,
        "morphhdl.passes.transform.NamedWireAliasEliminationPassSpec": 20,
        "morphhdl.passes.transform.NamedWireExpressionEliminationPassSpec": 7,
    },
    "core": {
        "spinal.core.internals.VerilogEmitterExpressionInliningTests": 16,
    },
    "morphhdl": {
        "spinal.core.MorphVerilogExpressionInliningTests": 10,
    },
}
WA09_EXISTING_SUITES = frozenset({
    "morphhdl.passes.pipeline.WireAssignmentAllPassPipelineSpec",
    "morphhdl.passes.transform.NamedWireAliasEliminationPassSpec",
})

# The outer overlay already proves that *every* governed HEAD delta is enrolled.
# Requiring this complete suite-source cluster additionally prevents XML alone,
# or a partial successor source set, from selecting the WA-09 report catalog.
# The Boolean records whether the file is an addition relative to the immutable
# overlay baseline (before_sha256 must be null) rather than a reviewed edit.
WA09_SUITE_SOURCES = {
    "morphhdl-passes/src/test/scala/morphhdl/passes/pipeline/WireAssignmentAllPassPipelineSpec.scala": False,
    "morphhdl-passes/src/test/scala/morphhdl/passes/transform/NamedWireAliasEliminationPassSpec.scala": False,
    "morphhdl-passes/src/test/scala/morphhdl/passes/transform/NamedWireExpressionEliminationPassSpec.scala": True,
    "core/src/test/scala/spinal/core/internals/VerilogEmitterExpressionInliningTests.scala": True,
    "morphhdl/src/test/scala/spinal/core/MorphVerilogExpressionInliningTests.scala": True,
}

# WA-10 extends the existing public MorphVerilog suite by one independently
# reviewed case. Keep WA-09's ten-case record immutable and apply the successor
# count only when the exact WA-10 source inventory is present in the verified
# outer overlay.
WA10_CONTRACT = "morphhdl/contracts/wa10-source-scope.json"
WA10_SUITES = {
    "morphhdl": {
        "morphhdl.examples.NativeWireExpressionCodecTests": 3,
        "spinal.core.MorphVerilogExpressionInliningTests": 11,
    },
}
WA10_EXISTING_SUITES = frozenset({
    "spinal.core.MorphVerilogExpressionInliningTests",
})
WA10_SUITE_SOURCES = frozenset({
    "morphhdl/src/test/scala/morphhdl/examples/NativeWireExpressionCodecTests.scala",
    "morphhdl/src/test/scala/spinal/core/MorphVerilogExpressionInliningTests.scala",
})

def require(ok: bool, message: str) -> None:
    if not ok:
        raise RuntimeError(message)


def exact_names(actual: set[str], expected: set[str], label: str) -> None:
    require(actual == expected,
            f"{label}: missing={sorted(expected - actual)}, unexpected={sorted(actual - expected)}")


def compatibility_names() -> set[str]:
    kinds = ("pure", "declarations", "bundles")
    native_modes = ("before", "declarations", "cleanup", "after")
    morph_modes = ("disabled-before", "declarations", "cleanup", "declarations-after",
                   "disabled-explicit", "disabled-after")
    names = {f"compatibility/{kind}/native-{width}-{mode}.{extension}"
             for kind in kinds for width in (1, 5, 8, 32) for mode in native_modes
             for extension in ("v", "vhd")}
    names |= {f"compatibility/{kind}/morph-{mode}.v" for kind in kinds for mode in morph_modes}
    require(len(names) == COUNTS["compatibility"], "compatibility identity inventory changed")
    return names


@functools.lru_cache(maxsize=1)
def closure_module():
    repository = Path(__file__).resolve().parents[2]
    scripts = str(repository / "morphhdl/scripts")
    if scripts not in sys.path:
        sys.path.insert(0, scripts)
    path = Path(scripts) / "check-increment-60f-equivalence-closure.py"
    spec = importlib.util.spec_from_file_location("signedness_closure_inventory", path)
    require(spec is not None and spec.loader is not None, "cannot load inherited artifact inventory")
    closure = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(closure)
    return closure


def inventory(root: Path) -> dict[str, str]:
    # Reuse the authoritative inherited identities and immutable 60a hash gate.
    # This reads generated bytes only; it does not run HDL tools or alter RTL.
    repository = Path(__file__).resolve().parents[2]
    closure = closure_module()
    inherited = closure.inventory(repository, root)
    require(len(inherited) == COUNTS["boundaries"] + COUNTS["pure"], "inherited identity inventory changed")
    expected = set(inherited) | compatibility_names()
    paths = sorted(p for p in root.rglob("*")
                   if p.is_file() and p.suffix in (".v", ".vhd", ".vhdl"))
    exact_names({p.relative_to(root).as_posix() for p in paths}, expected, "RTL file identities")
    result = {}
    for path in paths:
        require(path.stat().st_size > 0, f"empty artifact: {path}")
        result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    require(all(result[name] == digest for name, digest in inherited.items()),
            "inherited artifacts changed while computing the inventory")
    require(len(result) == sum(COUNTS.values()), "incomplete RTL inventory")
    return result


def snapshot(left: Path, right: Path, output: Path, scala: str) -> None:
    output.unlink(missing_ok=True)
    a, b = inventory(left), inventory(right)
    require(a == b, "fresh-JVM regeneration differs: " +
            str([name for name in sorted(set(a) | set(b)) if a.get(name) != b.get(name)]))
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    output.write_text(json.dumps({"head": head, "scala": scala, "sha256": a}, indent=2) + "\n")
    print(f"60f fresh-JVM byte identity: {len(a)} RTL files, Scala {scala}, {head}")


def compare(left: Path, right: Path) -> None:
    a = json.loads((left / "rtl-manifest.json").read_text())
    b = json.loads((right / "rtl-manifest.json").read_text())
    require({a["scala"], b["scala"]} == {"2.12.18", "2.13.12"}, "both supported Scala lanes are required")
    require(a["head"] == b["head"], "Scala artifacts come from different commits")
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    require(a["head"] == head, "downloaded artifacts do not match this checkout")
    ai, bi = inventory(left / "rtl"), inventory(right / "rtl")
    require(ai == a["sha256"] and bi == b["sha256"], "downloaded RTL does not match its manifest")
    require(ai == bi, "cross-Scala RTL differs: " +
            str([name for name in sorted(set(ai) | set(bi)) if ai.get(name) != bi.get(name)]))
    print(f"60f cross-Scala byte identity: {len(ai)} files at {a['head']}")


def catalog_for_profile(profile: str, packing: bool = False, wa08: bool = False,
                        wa09: bool = False, wa10: bool = False) -> tuple[dict, dict, dict]:
    features = closure_module().profile_features(profile)
    require(not packing or {"59d", "59e", "59f"}.issubset(features),
            "reviewed packing inventory requires the complete width/composite/callback profile")
    counts, suites = dict(REGRESSIONS), dict(EXPECTED_SUITES)
    extension = {}
    if "wa07a" in features:
        counts["morphhdl-passes"] = (123, 14)
        suites["morphhdl-passes"] |= WA07A_SUITES
    if "59d" in features:
        extension = {project: dict(additions) for project, additions in INCREMENT_59D_SUITES.items()}
        for project, additions in extension.items():
            require(not suites[project].intersection(additions), "59d inventory replaced an inherited suite")
            suites[project] |= frozenset(additions)
            old_tests, old_suites = counts[project]
            counts[project] = (old_tests + sum(additions.values()), old_suites + len(additions))
    for name in SUITE_EXTENSIONS:
        if name in features:
            for project, additions in SUITE_EXTENSIONS[name]["projects"].items():
                require(not suites[project].intersection(additions),
                        "reviewed suite addition duplicates inherited identity: " + name)
                suites[project] |= additions
                minimum, old_suites = counts[project]
                if name == "59g":
                    exact = SUITE_EXTENSIONS[name]["counts"]
                    require(set(exact) == set(additions) |
                            {"spinal.core.internals.TypedBalancedReductionCallbackPolicyTests"},
                            "59g exact test counts escaped register-bridge scope")
                    reviewed_counts = extension.setdefault(project, {})
                    require(not set(reviewed_counts).intersection(exact),
                            "59g exact test counts replaced another reviewed feature")
                    reviewed_counts.update(exact)
                    minimum += sum(exact[name] for name in additions) + 2
                if name == "59h":
                    exact_new = INCREMENT_59H_SUITES[project]
                    reviewed_counts = extension.setdefault(project, {})
                    require(not set(reviewed_counts).intersection(exact_new),
                            "59h exact test counts replaced another reviewed feature")
                    reviewed_counts.update(exact_new)
                    minimum += sum(exact_new.values())
                if name == "59e":
                    exact_new = INCREMENT_59E_SUITES[project]
                    exact_inherited = INCREMENT_59E_INHERITED_TESTS[project]
                    require(set(exact_inherited) <= EXPECTED_SUITES[project],
                            "59e inherited test counts escaped the frozen suite inventory")
                    reviewed_counts = extension.setdefault(project, {})
                    require(not set(reviewed_counts).intersection((*exact_new, *exact_inherited)),
                            "59e exact test counts replaced another reviewed feature")
                    reviewed_counts.update(exact_new)
                    reviewed_counts.update(exact_inherited)
                    # The three inherited suites each gain one adversarial test.
                    minimum += sum(exact_new.values()) + len(exact_inherited)
                    if {"59d", "59e", "59f"}.issubset(features):
                        for suite, count in INCREMENT_59D59E_JOINT_TESTS[project].items():
                            require(suite in exact_new and count > exact_new[suite],
                                    "joint width/composite count escaped its reviewed suite")
                            minimum += count - exact_new[suite]
                            reviewed_counts[suite] = count
                        if packing:
                            suite = "spinal.core.PackedVecIdentityAdversarialTests"
                            require(reviewed_counts[suite] == 8, "historical packed Vec inventory changed")
                            reviewed_counts[suite] = 12
                            minimum += 4
                counts[project] = (minimum, old_suites + len(additions))
    if "60g" in features:
        # The production profile is validated before reports are inspected.
        # Require the entire extended existing suite, not just a global count.
        tests, total_suites = counts["morphhdl"]
        counts["morphhdl"] = (tests + 18, total_suites)
        extension.setdefault("morphhdl", {})[
            "spinal.core.internals.SignednessCompatibilityTests"] = 22
        extension.setdefault("morphhdl", {})[
            "spinal.core.internals.ParameterizedVerilogStructuralLexicalTests"] = 2
    if "wa07b" in features:
        require("wa07a" in features, "WA-07b suite obligations require WA-07a")
        reviewed = {'morphhdl.passes.adapter.CanonicalIrPassAdapterSpec': 9, 'morphhdl.passes.api.AllPassConfigurationSpec': 5, 'morphhdl.passes.api.NativeRunnerSourceClosureSpec': 3, 'morphhdl.passes.api.PassContractsSpec': 8, 'morphhdl.passes.pipeline.WireAliasPassPipelineSpec': 9, 'morphhdl.passes.pipeline.WireAssignmentAllPassPipelineSpec': 6, 'morphhdl.passes.safety.WireAliasSafetyGateSpec': 20, 'morphhdl.passes.transform.BooleanTernaryFourStateSpec': 4, 'morphhdl.passes.transform.BooleanTernarySimplificationPassSpec': 14, 'morphhdl.passes.transform.ConstantOperandFixedPointSpec': 2, 'morphhdl.passes.transform.ConstantOperandFourStateSpec': 3, 'morphhdl.passes.transform.ConstantOperandSimplificationPassSpec': 14, 'morphhdl.passes.transform.NamedWireAliasEliminationPassSpec': 13, 'morphhdl.passes.transform.UnnamedWireAliasEliminationPassSpec': 12, 'morphhdl.passes.transform.UnnamedWireExpressionAlgebraSpec': 1, 'morphhdl.passes.transform.UnnamedWireExpressionEliminationPassSpec': 12, 'morphhdl.passes.transform.UnnamedWireExpressionSelectionSafetySpec': 9}
        require(suites["morphhdl-passes"] < set(reviewed),
                "WA-07b must preserve every historical pass suite")
        suites["morphhdl-passes"] = frozenset(reviewed)
        counts["morphhdl-passes"] = (sum(reviewed.values()), len(reviewed))
        extension["morphhdl-passes"] = reviewed
    if wa08:
        require("wa07b" in features, "WA-08 suite obligations require the reviewed WA-07b profile")
        for project, additions in WA08_SUITES.items():
            require(not suites[project].intersection(additions),
                    "WA-08 inventory replaced an inherited suite")
            suites[project] |= frozenset(additions)
            tests, total_suites = counts[project]
            counts[project] = (tests + sum(additions.values()), total_suites + len(additions))
            extension.setdefault(project, {}).update(additions)
    if wa09:
        require(wa08, "WA-09 suite obligations require the reviewed WA-08 profile")
        require(features == WA09_INHERITED_FEATURES and packing,
                "WA-09 exact catalog requires its complete inherited source and packing profile")
        require(set(WA09_INHERITED_SUITE_COUNTS) == set(counts),
                "WA-09 exact catalog changed the inherited project inventory")
        for project, exact in WA09_INHERITED_SUITE_COUNTS.items():
            require(set(exact) == suites[project],
                    "WA-09 exact catalog changed inherited suite identities: " + project)
            require(all(exact[name] == count
                        for name, count in extension.get(project, {}).items()),
                    "WA-09 exact catalog changed an inherited exact suite count: " + project)
            require(sum(exact.values()) >= counts[project][0],
                    "WA-09 exact catalog weakened an inherited minimum: " + project)
            counts[project] = (sum(exact.values()), len(exact))
            extension[project] = dict(exact)
        for project, exact in WA09_SUITES.items():
            tests, total_suites = counts[project]
            reviewed_counts = extension.setdefault(project, {})
            for name, expected_count in exact.items():
                inherited = name in suites[project]
                expected_inherited = name in WA09_EXISTING_SUITES
                require(inherited == expected_inherited,
                        "WA-09 suite identity changed inherited/new classification: " + name)
                if inherited:
                    require(name in reviewed_counts,
                            "WA-09 changed suite lacks an inherited exact count: " + name)
                    previous_count = reviewed_counts[name]
                    require(expected_count >= previous_count,
                            "WA-09 exact suite count removed inherited tests: " + name)
                    tests += expected_count - previous_count
                else:
                    suites[project] |= frozenset((name,))
                    tests += expected_count
                    total_suites += 1
                reviewed_counts[name] = expected_count
            counts[project] = (tests, total_suites)
    if wa10:
        require(wa09, "WA-10 suite obligations require the reviewed WA-09 profile")
        for project, exact in WA10_SUITES.items():
            tests, total_suites = counts[project]
            reviewed_counts = extension.setdefault(project, {})
            for name, expected_count in exact.items():
                inherited = name in suites[project]
                expected_inherited = name in WA10_EXISTING_SUITES
                require(inherited == expected_inherited,
                        "WA-10 suite identity changed inherited/new classification: " + name)
                if inherited:
                    require(name in reviewed_counts,
                            "WA-10 changed suite lacks an inherited exact count: " + name)
                    previous_count = reviewed_counts[name]
                    require(expected_count > previous_count,
                            "WA-10 exact suite count did not extend its predecessor: " + name)
                    tests += expected_count - previous_count
                else:
                    suites[project] |= frozenset((name,))
                    tests += expected_count
                    total_suites += 1
                reviewed_counts[name] = expected_count
            counts[project] = (tests, total_suites)
    return counts, suites, extension


def successor_suite_flags(entries: dict[str, dict]) -> tuple[bool, bool, bool]:
    wa08 = entries.get(WA08_SUITE_SOURCE)
    require(wa08 is not None and wa08["before_sha256"] is None,
            "WA-08 hierarchy suite is absent from the verified added-source inventory")
    selected = set(entries).intersection(WA09_SUITE_SOURCES)
    if not selected:
        return True, False, False
    require(selected == set(WA09_SUITE_SOURCES),
            "partial WA-09 suite-source enrollment: " +
            repr(sorted(set(WA09_SUITE_SOURCES) - selected)))
    for path, added in WA09_SUITE_SOURCES.items():
        before = entries[path]["before_sha256"]
        require((before is None) == added,
                "WA-09 suite source has changed baseline identity: " + path)
    if WA10_CONTRACT not in entries:
        return True, True, False
    require(entries[WA10_CONTRACT]["before_sha256"] is None,
            "WA-10 successor contract has changed baseline identity")
    require(WA10_SUITE_SOURCES <= set(entries),
            "WA-10 suite source is absent from the reviewed overlay")
    require(all(entries[path]["before_sha256"] is None for path in WA10_SUITE_SOURCES),
            "WA-10 suite source has changed baseline identity")
    return True, True, True


def reviewed_successor_suites(root: Path) -> tuple[bool, bool, bool]:
    ternary = closure_module().boolean_ternary_review(root)
    adapter = getattr(ternary, "wa08_overlay", None)
    overlay = adapter(root) if adapter is not None else None
    if overlay is None:
        return False, False, False
    # Presence or XML cannot authorize a new suite: verify immutable source
    # bytes, full governed inventory, Git index/worktree and source ancestry.
    entries = {entry["path"]: entry for entry in overlay.verify(root)["files"]}
    return successor_suite_flags(entries)


def reviewed_wa08_suites(root: Path) -> bool:
    """Compatibility helper retained for callers that need only WA-08."""
    return reviewed_successor_suites(root)[0]


def descendant_extensions(root: Path) -> tuple[str, ...]:
    selected = []
    for name, extension in SUITE_EXTENSIONS.items():
        sources = extension["sources"]
        result = subprocess.run(["git", "ls-tree", "-r", "--name-only", "HEAD", "--", *sources],
                                cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        require(result.returncode == 0,
                "cannot inspect reviewed suite source inventory for " + name + ": " + result.stderr)
        tracked = set(result.stdout.splitlines())
        present = [(root / path).is_file() for path in sources]
        if tracked or any(present):
            require(tracked == set(sources) and all(present),
                    "incomplete or uncommitted reviewed suite source inventory for " + name)
            selected.append(name)
    return tuple(selected)


def regressions(root: Path, output: Path) -> None:
    output.unlink(missing_ok=True)
    # Source validation precedes report discovery. XML and feature-file presence
    # cannot select a successor contract or bypass inherited 59d authority audits.
    closure = closure_module()
    profile = closure.regression_profile(root)
    if "59d" in closure.profile_features(profile):
        closure.source_scope(root)
    _regression_inventory(root, output, profile)


def _regression_inventory(root: Path, output: Path, profile: str) -> None:
    """Validate reports only after the caller has validated the source profile."""
    closure = closure_module()
    features = closure.profile_features(profile)
    extensions = descendant_extensions(root)
    require(extensions == tuple(name for name in SUITE_EXTENSIONS if name in features),
            "committed suite sources differ from the validated production profile")
    packing_review = root / closure.PACKING_59D59E_CONTRACT
    packing = packing_review.exists() or packing_review.is_symlink()
    if packing:
        require({"59d", "59e", "59f"}.issubset(features),
                "reviewed packing inventory requires the complete width/composite/callback profile")
        publisher = closure.load(root, "59f-source-scope")
        reviewed = publisher.reviewed_59d59e_packing(root)
        require(set(reviewed) == closure.PACKING_59D59E_PATHS,
                "reviewed packing inventory escaped its exact source paths")
    wa08, wa09, wa10 = reviewed_successor_suites(root)
    counts, suite_inventory, extension = catalog_for_profile(
        profile, packing, wa08, wa09, wa10)
    records = {}
    for project, (minimum_tests, minimum_suites) in counts.items():
        reports = sorted((root / project / "target/test-reports").glob("*.xml"))
        names = set()
        tests = 0
        for path in reports:
            suite = ET.parse(path).getroot()
            require(suite.tag == "testsuite", f"unexpected test report: {path}")
            name = suite.get("name")
            require(bool(name) and name not in names, f"missing or duplicate suite: {path}")
            names.add(name)
            require(all(int(suite.get(key, "0")) == 0 for key in ("failures", "errors", "skipped")),
                    f"failed or skipped regression: {path}: {suite.attrib}")
            require(not list(suite.iter("skipped")) and not list(suite.iter("failure"))
                    and not list(suite.iter("error")), f"unsuccessful test result: {path}")
            count = int(suite.get("tests", "0"))
            added_count = extension.get(project, {}).get(name)
            if added_count is not None:
                require(count == added_count, f"changed exact reviewed test inventory: {name}: {count}")
            cases = suite.findall("testcase")
            require(count > 0 and len(cases) == count, f"empty/inconsistent suite: {path}")
            case_names = [case.get("name") for case in cases]
            require(all(case_names) and len(set(case_names)) == count,
                    f"missing or duplicated testcase identities: {path}")
            tests += count
        complete_tests = tests == minimum_tests if wa09 else tests >= minimum_tests
        require(complete_tests and len(names) >= minimum_suites,
                f"missing/changed {project} regressions: tests={tests}, "
                f"expected={'exactly ' if wa09 else 'at least '}{minimum_tests}, suites={len(names)}")
        expected = suite_inventory[project]
        require(len(expected) == minimum_suites, f"inconsistent frozen suite inventory: {project}")
        exact_names(names, expected, project + " suite identities")
        records[project] = {"tests": tests, "suites": sorted(names), "skipped": 0}
        print(f"{project}: {tests} tests / {len(names)} suites, zero failures/errors/skips")
    output.write_text(json.dumps(records, indent=2) + "\n")
    print("60f all inherited regressions:", sum(x["tests"] for x in records.values()),
          "non-skipped tests; validated source profile:", profile)


def self_test() -> None:
    """Synthetic Git/source/report controls only; never HDL qualification evidence."""
    from unittest import mock

    rejections = 0

    def rejected(action, label: str) -> None:
        nonlocal rejections
        try:
            action()
        except (RuntimeError, FileNotFoundError, subprocess.CalledProcessError):
            rejections += 1
            return
        raise RuntimeError("inventory self-test accepted " + label)

    expected = compatibility_names()
    name = min(expected)
    exact_names(expected, expected, "positive inventory control")
    rejected(lambda: exact_names(expected - {name}, expected, "missing"), "missing RTL")
    rejected(lambda: exact_names((expected - {name}) | {"compatibility/wrong.v"}, expected, "renamed"),
             "same-count renamed RTL")
    rejected(lambda: exact_names(expected | {"unexpected.v"}, expected, "extra"), "extra RTL")
    with tempfile.TemporaryDirectory(prefix="increment-60f-inventory-self-test-") as temporary:
        root = Path(temporary)
        closure = closure_module()

        def git(*args: str) -> str:
            return subprocess.check_output(["git", *args], cwd=root, text=True,
                                           stderr=subprocess.PIPE).strip()

        def write(path: str, data: bytes) -> None:
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)

        def commit() -> str:
            git("add", ".")
            git("-c", "user.name=Synthetic Test", "-c", "user.email=synthetic@example.invalid",
                "commit", "-qm", "synthetic source contract fixture")
            return git("rev-parse", "HEAD")

        git("init", "-q")
        wa_paths = tuple(closure.WA07A_PRODUCTION_SHA256)
        callback_paths = tuple(closure.CALLBACK_59F_PRODUCTION_SHA256)
        feature_sources = SUITE_EXTENSIONS["59f"]["sources"]
        composite_feature_sources = SUITE_EXTENSIONS["59e"]["sources"]
        composite_real_paths = {path for path in composite_feature_sources if "/src/main/" in path} | \
            set(closure.PACKING_59D59E_PATHS)
        composite_paths = tuple(sorted(composite_real_paths)) + tuple(
            f"morphhdl/src/main/scala/Synthetic59e{index}.scala" for index in range(14 - len(composite_real_paths)))
        require(len(composite_paths) == 14, "synthetic 59e fixture changed its exact source inventory")
        callback_new = {path for path in feature_sources if "/src/main/" in path}
        write(".gitignore", b"**/target/\n/ignored/\n/result.json\n")
        for path in (*wa_paths[:2], *(path for path in callback_paths if path not in callback_new)):
            write(path, b"synthetic inherited source\n")
        other_source = "core/src/main/scala/Synthetic.scala"
        write(other_source, b"synthetic sealed production\n")
        base = commit()
        write("qualification.txt", b"synthetic qualification-only commit\n")
        qualified = commit()
        source_bytes = {path: ("synthetic reviewed follow-on: " + path + "\n").encode()
                        for path in (*wa_paths, *callback_paths, *composite_paths)}
        composite_hashes = {path: hashlib.sha256(source_bytes[path]).hexdigest() for path in composite_paths}
        wa_hashes = {path: hashlib.sha256(source_bytes[path]).hexdigest() for path in wa_paths}
        callback_hashes = {path: hashlib.sha256(source_bytes[path]).hexdigest() for path in callback_paths}
        # A real immutable Git blob anchors each joint edit's full restoration.
        # It need not be an ancestor of the independent profile fixture heads.
        for path in callback_paths:
            write(path, source_bytes[path])
        integration_base = commit()
        git("branch", "synthetic-callback-baseline", integration_base)
        git("reset", "--hard", qualified)
        integration_path = closure.INTEGRATION_59D59F_CONTRACT
        integration_bytes = {
            path: source_bytes[path] + b"synthetic reviewed joint widthAt integration\n"
            for path in closure.INTEGRATION_59D59F_PATHS}
        integration_review = {"base": integration_base, "files": [
            {"path": path,
             "before_sha256": callback_hashes[path],
             "after_sha256": hashlib.sha256(data).hexdigest(),
             "edits": [{"id": "restore-exact-joint-widthAt",
                        "before": source_bytes[path].decode(), "after": data.decode()}]}
            for path, data in sorted(integration_bytes.items())]}
        integration_manifest = (json.dumps(integration_review, indent=2) + "\n").encode()
        integration_sha256 = hashlib.sha256(integration_manifest).hexdigest()
        publisher_path = "morphhdl/scripts/check-increment-59f-source-scope.py"
        repository = Path(__file__).resolve().parents[2]
        publisher = closure.load(repository, "59f-source-scope")
        composite_integration_path = publisher.COMPOSITE_59DE_PRODUCTION_CONTRACT
        composite_policy = publisher.COMPOSITE_59DE_POLICY
        # The copied real validator remains intact. Only the fixture's immutable
        # baseline and pinned manifest identities differ from production.
        for path in (*callback_paths, *composite_paths):
            write(path, source_bytes[path])
        composite_integration_base = commit()
        git("branch", "synthetic-composite-baseline", composite_integration_base)
        git("reset", "--hard", qualified)
        composite_integrated_bytes = source_bytes[composite_policy] + b"synthetic reviewed scalar auto-resize rejection\n"
        composite_integration_review = {"base": composite_integration_base, "files": [{
            "path": composite_policy,
            "before_sha256": composite_hashes[composite_policy],
            "after_sha256": hashlib.sha256(composite_integrated_bytes).hexdigest(),
            "edits": [{"id": "reject-scalar-auto-resize",
                       "before": source_bytes[composite_policy].decode(),
                       "after": composite_integrated_bytes.decode()}]}]}
        composite_integration_manifest = (json.dumps(composite_integration_review, indent=2) + "\n").encode()
        composite_integration_sha256 = hashlib.sha256(composite_integration_manifest).hexdigest()
        packing_path = closure.PACKING_59D59E_CONTRACT
        packing_bytes = {path: source_bytes[path] + b"synthetic reviewed exact composite packing\n"
                         for path in closure.PACKING_59D59E_PATHS}
        packing_review = {"base": composite_integration_base, "files": [{
            "path": path,
            "before_sha256": composite_hashes[path],
            "after_sha256": hashlib.sha256(data).hexdigest(),
            "edits": [{"id": publisher.PACKING_59DE_IDS[path][0],
                       "before": source_bytes[path].decode(), "after": data.decode()}]}
            for path, data in sorted(packing_bytes.items())]}
        require(all(len(ids) == 1 for ids in publisher.PACKING_59DE_IDS.values()),
                "synthetic packing fixture requires each exact reviewed single-span edit")
        packing_manifest = (json.dumps(packing_review, indent=2) + "\n").encode()
        packing_sha256 = hashlib.sha256(packing_manifest).hexdigest()
        publisher_source = (repository / publisher_path).read_text()

        def fixture_publisher(manifest_sha256: str = composite_integration_sha256,
                              packing_sha: str = packing_sha256) -> bytes:
            source = publisher_source
            for name, value in (("COMPOSITE_59EF_BASE", composite_integration_base),
                                ("COMPOSITE_59DE_PRODUCTION_SHA256", manifest_sha256),
                                ("PACKING_59DE_SHA256", packing_sha)):
                before = name + ' = "' + getattr(publisher, name) + '"'
                after = name + ' = "' + value + '"'
                require(source.count(before) == 1, "missing unique real publisher fixture identity: " + name)
                source = source.replace(before, after, 1)
            return source.encode()

        descendant_paths = ("core/src/main/scala/Synthetic59d.scala",
                            "morphhdl/src/main/scala/Synthetic59d.scala")
        descendant_bytes = {path: ("synthetic reviewed 59d: " + path + "\n").encode()
                            for path in descendant_paths}
        review_path = "morphhdl/contracts/increment-59d-production-review.json"
        review = {"base": qualified, "files": [
            {"path": path, "sha256": hashlib.sha256(data).hexdigest()}
            for path, data in sorted(descendant_bytes.items())], "checker_edits": [
            {"path": "morphhdl/scripts/check-increment-60e-signedness-boundaries.py",
             "id": "restore-exact-59d-width-seams", "before": "fixture before", "after": "fixture after"}]}
        profiles = tuple((wa, descendant, composite, callbacks)
                         for wa in (False, True) for descendant in (False, True)
                         for composite in (False, True) for callbacks in (False, True)
                         if not composite or callbacks)
        incomplete_profiles = tuple((wa, descendant, True, False)
                                    for wa in (False, True) for descendant in (False, True))

        def profile_name(wa: bool, descendant: bool, composite: bool, callbacks: bool) -> str:
            selected = [name for name, present in (("wa07a", wa), ("59d", descendant),
                                                   ("59e", composite), ("59f", callbacks)) if present]
            return "60f-with-" + "-and-".join(selected) if selected else "60f-baseline"

        def reports(wa: bool, descendant: bool, composite: bool, callbacks: bool,
                    packing: bool = False) -> None:
            counts, inventories, extension = catalog_for_profile(
                profile_name(wa, descendant, composite, callbacks), packing)
            for project, (minimum, suites) in counts.items():
                names = inventories[project]
                require(len(names) == suites, "frozen suite count differs: " + project)
                exact_counts = extension.get(project, {})
                inherited = sorted(names - exact_counts.keys())
                counts_by_suite = {name: exact_counts.get(name, 1) for name in names}
                counts_by_suite[inherited[0]] += minimum - sum(counts_by_suite.values())
                directory = root / project / "target/test-reports"
                directory.mkdir(parents=True, exist_ok=True)
                for old in directory.glob("*.xml"):
                    old.unlink()
                for index, suite_name in enumerate(sorted(names)):
                    count = counts_by_suite[suite_name]
                    suite = ET.Element("testsuite", name=suite_name, tests=str(count),
                                       failures="0", errors="0", skipped="0")
                    for case in range(count):
                        ET.SubElement(suite, "testcase", name=f"synthetic-case-{case}")
                    ET.ElementTree(suite).write(directory / f"suite-{index}.xml")

        def fixture_regressions() -> None:
            # Real Git/profile and XML gates run on synthetic bytes. Historical
            # restoration and overlapping real source unions are audited by
            # test-increment-59d-inherited-60f-scope.py and the 59f scope fixtures.
            output.unlink(missing_ok=True)
            profile = closure.regression_profile(root)
            _regression_inventory(root, output, profile)

        output = root / "result.json"

        def reject_reports(label: str) -> None:
            output.write_text('{"stale_success": true}\n')
            with contextlib.redirect_stdout(io.StringIO()):
                rejected(fixture_regressions, label)
            require(not output.exists(), "failed validation retained stale success: " + label)

        # Only fixture identities are patched. All twelve selections execute real
        # Git ancestry/delta, exact bytes, committed feature-source and XML gates.
        with mock.patch.multiple(closure, BASE=base, QUALIFIED_60F=qualified,
                                 INCREMENT_59D_BASE=qualified,
                                 INCREMENT_59D_PRODUCTION_PATHS=frozenset(descendant_paths),
                                 WA07A_PRODUCTION_SHA256=wa_hashes,
                                 CALLBACK_59F_PRODUCTION_SHA256=callback_hashes,
                                 COMPOSITE_59E_PRODUCTION_SHA256=composite_hashes,
                                 INCREMENT_59E_BASE=qualified,
                                 COMPLETED_59F=integration_base,
                                 COMPLETED_59E=integration_base,
                                 INTEGRATION_59D59F_BASE=integration_base,
                                 INTEGRATION_59D59F_SHA256=integration_sha256):
            for wa, descendant, composite, callbacks in incomplete_profiles:
                incomplete_name = profile_name(wa, descendant, composite, callbacks)
                rejected(lambda: catalog_for_profile(incomplete_name),
                         "59e profile name without required 59f callbacks")
                git("reset", "--hard", qualified)
                git("clean", "-fd")
                for path in composite_paths:
                    write(path, source_bytes[path])
                for path in composite_feature_sources:
                    if "/src/test/" in path:
                        write(path, b"// synthetic composite test fixture\n")
                if wa:
                    for path in wa_paths:
                        write(path, source_bytes[path])
                if descendant:
                    for path, data in descendant_bytes.items():
                        write(path, data)
                    write(review_path, (json.dumps(review, indent=2) + "\n").encode())
                commit()
                reports(False, False, False, False)
                reject_reports("committed 59e production without required 59f callbacks " + incomplete_name)

            for wa, descendant, composite, callbacks in profiles:
                # Destructive reset/clean calls are confined to this temporary Git fixture.
                git("reset", "--hard", qualified)
                git("clean", "-fd")
                selected_bytes = {}
                if wa:
                    selected_bytes.update((path, source_bytes[path]) for path in wa_paths)
                if descendant:
                    selected_bytes.update(descendant_bytes)
                    write(review_path, (json.dumps(review, indent=2) + "\n").encode())
                if callbacks:
                    selected_bytes.update((path, source_bytes[path]) for path in callback_paths)
                if composite:
                    selected_bytes.update((path, source_bytes[path]) for path in composite_paths)
                if descendant and callbacks:
                    selected_bytes.update(integration_bytes)
                    write(integration_path, integration_manifest)
                if descendant and composite:
                    selected_bytes[composite_policy] = composite_integrated_bytes
                    write(composite_integration_path, composite_integration_manifest)
                    write(publisher_path, fixture_publisher())
                for path, data in selected_bytes.items():
                    write(path, data)
                reports(wa, descendant, composite, callbacks)
                if callbacks:
                    # Reviewed production bytes cannot authorize either an
                    # incomplete or a complete-but-uncommitted callback feature.
                    git("add", *selected_bytes)
                    reject_reports("uncommitted callback source inventory")
                    for path in feature_sources:
                        if path not in callback_paths:
                            write(path, b"// synthetic callback test fixture\n")
                    git("add", *feature_sources)
                    reject_reports("complete but uncommitted callback source inventory")
                if composite:
                    reject_reports("incomplete uncommitted composite source inventory")
                    for path in composite_feature_sources:
                        if path not in composite_paths:
                            write(path, b"// synthetic composite test fixture\n")
                    git("add", *composite_feature_sources)
                    reject_reports("complete but uncommitted composite source inventory")
                if selected_bytes:
                    commit()
                expected_profile = profile_name(wa, descendant, composite, callbacks)
                require(closure.production_profile(root) == expected_profile, "source profile mismatch")
                expected_extensions = (*(("59e",) if composite else ()), *(("59f",) if callbacks else ()))
                require(descendant_extensions(root) == expected_extensions,
                        "committed descendant source selection mismatch")
                with contextlib.redirect_stdout(io.StringIO()):
                    fixture_regressions()
                results = json.loads(output.read_text())
                require(len(results["morphhdl"]["suites"]) == 78 + 6 * descendant + 3 * composite + 4 * callbacks,
                        "reviewed descendant inventory omitted or replaced a suite")
                require(results["morphhdl"]["tests"] == 819 + 36 * descendant + 42 * composite +
                        int(descendant and composite),
                        "reviewed descendant minimum test count changed")
                require(results["morphhdl-passes"]["tests"] == (123 if wa else 99),
                        "reviewed WA minimum test count changed")
                require(len(results["morphhdl-passes"]["suites"]) == (14 if wa else 11),
                        "reviewed WA inventory omitted or replaced a suite")
                for project in ("morphhdl", "morphhdl-passes"):
                    directory = root / project / "target/test-reports"
                    # Every inherited/new suite is independently mandatory.
                    for report in sorted(directory.glob("*.xml")):
                        original = report.read_bytes()
                        report.unlink()
                        reject_reports("missing suite " + project + "/" + report.name)
                        report.write_bytes(original)
                    report = directory / "suite-1.xml"
                    original = report.read_bytes()
                    tree = ET.parse(report)
                    tree.getroot().set("name", "synthetic.SubstituteSuite")
                    tree.write(report)
                    reject_reports("same-count substitute suite " + project)
                    report.write_bytes(original)
                    extra = directory / "extra.xml"
                    tree.write(extra)
                    reject_reports("extra suite " + project)
                    extra.unlink()
                    tree = ET.parse(report)
                    ET.SubElement(tree.getroot().find("testcase"), "skipped")
                    tree.write(report)
                    reject_reports("skipped testcase " + project)
                    report.write_bytes(original)
                    largest = directory / "suite-0.xml"
                    original = largest.read_bytes()
                    tree = ET.parse(largest)
                    suite = tree.getroot()
                    suite.remove(suite.find("testcase"))
                    suite.set("tests", str(int(suite.get("tests")) - 1))
                    tree.write(largest)
                    reject_reports("test count below profile minimum " + project)
                    largest.write_bytes(original)

                for path in (other_source, "backend/src/main/scala/New.scala",
                             "ignored/src/main/scala/Hidden.scala"):
                    target = root / path
                    original = target.read_bytes() if target.exists() else None
                    write(path, b"unreviewed source\n")
                    reject_reports("unauthorized tracked/untracked/ignored production " + path)
                    if original is None:
                        target.unlink()
                    else:
                        target.write_bytes(original)
                git("mv", "--", other_source, "moved-outside-production.scala")
                reject_reports("tracked production source renamed outside src/main")
                git("mv", "--", "moved-outside-production.scala", other_source)
                for other in profiles:
                    if other != (wa, descendant, composite, callbacks):
                        reports(*other)
                        reject_reports("report inventory cannot select source profile " + profile_name(*other))
                reports(wa, descendant, composite, callbacks)
                if descendant or composite:
                    directory = root / "morphhdl/target/test-reports"
                    exact_counts = catalog_for_profile(expected_profile)[2]["morphhdl"]
                    for report in directory.glob("*.xml"):
                        if ET.parse(report).getroot().get("name") not in exact_counts:
                            continue
                        original = report.read_bytes()
                        tree = ET.parse(report)
                        suite = tree.getroot()
                        suite.set("tests", str(int(suite.get("tests")) + 1))
                        ET.SubElement(suite, "testcase", name="unreviewed-extra-case")
                        tree.write(report)
                        reject_reports("exact reviewed suite test count increased " + suite.get("name"))
                        report.write_bytes(original)
                        if composite and (suite.get("name") in INCREMENT_59E_INHERITED_TESTS["morphhdl"] or
                                          (descendant and suite.get("name") in INCREMENT_59D59E_JOINT_TESTS["morphhdl"])):
                            tree = ET.parse(report)
                            suite = tree.getroot()
                            suite.remove(suite.find("testcase"))
                            suite.set("tests", str(int(suite.get("tests")) - 1))
                            tree.write(report)
                            # Add a compensating case elsewhere to demonstrate
                            # that an inherited E or joint integration test
                            # cannot be traded away.
                            compensating = directory / "suite-0.xml"
                            compensating_original = compensating.read_bytes()
                            other_tree = ET.parse(compensating)
                            other_suite = other_tree.getroot()
                            other_suite.set("tests", str(int(other_suite.get("tests")) + 1))
                            ET.SubElement(other_suite, "testcase", name="compensating-unreviewed-case")
                            other_tree.write(compensating)
                            reject_reports("missing inherited or joint 59e case with unchanged total " + suite.get("name"))
                            compensating.write_bytes(compensating_original)
                            report.write_bytes(original)
                if not descendant:
                    write(review_path, (json.dumps(review) + "\n").encode())
                    require(closure.production_profile(root) == expected_profile,
                            "59d contract presence selected an unaudited source profile")
                    (root / review_path).unlink()
                if descendant and callbacks:
                    contract = root / integration_path
                    contract.unlink()
                    reject_reports("missing mandatory 59d/59f joint integration review")
                    contract.write_bytes(integration_manifest + b" ")
                    reject_reports("changed pinned 59d/59f integration manifest bytes")
                    forged = json.loads(integration_manifest)
                    extra_path = next(path for path in callback_paths if path not in integration_bytes)
                    forged["files"].append({
                        "path": extra_path,
                        "before_sha256": callback_hashes[extra_path],
                        "after_sha256": callback_hashes[extra_path],
                        "edits": [{"id": "unreviewed-extra-path",
                                   "before": source_bytes[extra_path].decode(),
                                   "after": source_bytes[extra_path].decode()}]})
                    forged["files"].sort(key=lambda entry: entry["path"])
                    forged_bytes = (json.dumps(forged, indent=2) + "\n").encode()
                    contract.write_bytes(forged_bytes)
                    # Re-pin only the synthetic identity so the exact two-path
                    # inventory must independently reject this scope expansion.
                    with mock.patch.object(closure, "INTEGRATION_59D59F_SHA256",
                                           hashlib.sha256(forged_bytes).hexdigest()):
                        reject_reports("joint integration review cannot absorb another callback path")
                    forged = json.loads(integration_manifest)
                    forged["files"][0]["edits"][0]["before"] += "unreviewed restored source\n"
                    forged_bytes = (json.dumps(forged, indent=2) + "\n").encode()
                    contract.write_bytes(forged_bytes)
                    with mock.patch.object(closure, "INTEGRATION_59D59F_SHA256",
                                           hashlib.sha256(forged_bytes).hexdigest()):
                        reject_reports("joint source edit must restore the complete frozen callback blob")
                    contract.write_bytes(integration_manifest)
                if descendant and composite:
                    contract = root / composite_integration_path
                    contract.unlink()
                    reject_reports("missing mandatory width/composite policy integration review")
                    contract.write_bytes(composite_integration_manifest + b" ")
                    reject_reports("changed pinned width/composite policy integration manifest")
                    forged = json.loads(composite_integration_manifest)
                    forged["files"].append(dict(forged["files"][0], path=composite_paths[-1]))
                    forged_bytes = (json.dumps(forged, indent=2) + "\n").encode()
                    contract.write_bytes(forged_bytes)
                    write(publisher_path, fixture_publisher(hashlib.sha256(forged_bytes).hexdigest()))
                    reject_reports("policy integration review cannot absorb another composite path")
                    forged = json.loads(composite_integration_manifest)
                    forged["files"][0]["edits"][0]["before"] += "unreviewed restored source\n"
                    forged_bytes = (json.dumps(forged, indent=2) + "\n").encode()
                    contract.write_bytes(forged_bytes)
                    write(publisher_path, fixture_publisher(hashlib.sha256(forged_bytes).hexdigest()))
                    reject_reports("policy integration must restore the complete frozen composite blob")
                    contract.write_bytes(composite_integration_manifest)
                    write(publisher_path, fixture_publisher())
                for path, data in selected_bytes.items():
                    target = root / path
                    target.write_bytes(data + b"unreviewed mutation\n")
                    reject_reports("reviewed source hash mutation " + path)
                    target.unlink()
                    reject_reports("missing reviewed source " + path)
                    target.write_bytes(data)
                    # Restoring only the worktree must not hide different bytes
                    # still staged for the next committed production tree.
                    target.write_bytes(data + b"unreviewed staged mutation\n")
                    git("add", "--", path)
                    target.write_bytes(data)
                    reject_reports("staged production mutation hidden by worktree restoration " + path)
                    git("add", "--", path)
                    git("rm", "--cached", "--", path)
                    reject_reports("exact reviewed bytes at untracked allowed path " + path)
                    git("add", "--", path)
                    target.chmod(0o755)
                    reject_reports("executable reviewed source " + path)
                    target.chmod(0o644)
                    git("update-index", "--chmod=+x", "--", path)
                    reject_reports("executable index mode with regular worktree source " + path)
                    git("update-index", "--chmod=-x", "--", path)
                    link_target = root / "symlink-source.txt"
                    link_target.write_bytes(data)
                    target.unlink()
                    target.symlink_to(link_target)
                    reject_reports("symlink at reviewed source path " + path)
                    target.unlink()
                    target.write_bytes(data)
                    link_target.unlink()
                if descendant:
                    path = descendant_paths[0]
                    original_source = (root / path).read_bytes()
                    original_review = (root / review_path).read_bytes()
                    uncommitted_source = original_source + b"uncommitted reviewed width change\n"
                    forged = json.loads(original_review)
                    next(entry for entry in forged["files"] if entry["path"] == path)["sha256"] = \
                        hashlib.sha256(uncommitted_source).hexdigest()
                    write(path, uncommitted_source)
                    write(review_path, (json.dumps(forged, indent=2) + "\n").encode())
                    reject_reports("dirty width source paired with matching uncommitted review hash")
                    write(path, original_source)
                    write(review_path, original_review)
                if callbacks:
                    for path in feature_sources:
                        target = root / path
                        original = target.read_bytes()
                        target.unlink()
                        reject_reports("deleted tracked callback source " + path)
                        target.write_bytes(original)
                else:
                    path = next(path for path in feature_sources if "/src/test/" in path)
                    write(path, b"// uncommitted callback source fixture\n")
                    reject_reports("callback suite source cannot select a production profile")
                    (root / path).unlink()
                if composite:
                    for path in composite_feature_sources:
                        target = root / path
                        original = target.read_bytes()
                        target.unlink()
                        reject_reports("deleted tracked composite source " + path)
                        target.write_bytes(original)
                else:
                    path = next(path for path in composite_feature_sources if "/src/test/" in path)
                    write(path, b"// uncommitted composite source fixture\n")
                    reject_reports("composite suite source cannot select a production profile")
                    (root / path).unlink()
                if selected_bytes:
                    with mock.patch.object(closure, "QUALIFIED_60F", git("rev-parse", "HEAD")):
                        reject_reports("historical 60f production change")
                else:
                    git("mv", "--", other_source, "moved-outside-production.scala")
                    renamed_qualification = commit()
                    with mock.patch.object(closure, "QUALIFIED_60F", renamed_qualification):
                        reject_reports("historical production source renamed outside src/main")
                    git("reset", "--hard", qualified)
                if callbacks:
                    callback_completion = git("rev-parse", "HEAD")
                    # Completion remains an ancestor when a descendant commit
                    # restores every feature source and suite to baseline.
                    git("read-tree", "--reset", "-u", qualified)
                    commit()
                    reports(False, False, False, False)
                    with mock.patch.object(closure, "COMPLETED_59F", callback_completion):
                        require(closure.production_profile(root) == "60f-baseline",
                                "complete callback reversion did not restore exact baseline bytes")
                        reject_reports("complete committed callback reversion cannot drop suite obligations")
                    git("reset", "--hard", callback_completion)
                    reports(wa, descendant, composite, callbacks)
                if composite:
                    composite_completion = git("rev-parse", "HEAD")
                    # Retain the complete F (and any D/WA) implementation while
                    # reverting all E production and suite sources in a child.
                    git("read-tree", "--reset", "-u", qualified)
                    for path, data in selected_bytes.items():
                        if path not in composite_paths:
                            write(path, data)
                    for path in feature_sources:
                        if "/src/test/" in path:
                            write(path, b"// synthetic callback test fixture\n")
                    if descendant:
                        write(review_path, (json.dumps(review, indent=2) + "\n").encode())
                        write(integration_path, integration_manifest)
                    commit()
                    reports(wa, descendant, False, callbacks)
                    with mock.patch.object(closure, "COMPLETED_59E", composite_completion):
                        require(closure.production_profile(root) == profile_name(wa, descendant, False, callbacks),
                                "complete composite reversion did not retain exact inherited feature bytes")
                        reject_reports("complete committed composite reversion cannot drop suite obligations")
                    git("reset", "--hard", composite_completion)
                    reports(wa, descendant, composite, callbacks)
                if not (descendant and composite):
                    rejected(lambda: catalog_for_profile(expected_profile, packing=True),
                             "packing inventory without the complete width/composite/callback profile")
                    write(packing_path, packing_manifest)
                    reject_reports("packing manifest cannot activate outside the complete integration profile")
                    (root / packing_path).unlink()
                else:
                    historical_head = git("rev-parse", "HEAD")
                    for path, data in packing_bytes.items():
                        write(path, data)
                    write(packing_path, packing_manifest)
                    write(publisher_path, fixture_publisher())
                    packing_head = commit()
                    reports(wa, descendant, composite, callbacks, packing=True)
                    with contextlib.redirect_stdout(io.StringIO()):
                        fixture_regressions()
                    packing_results = json.loads(output.read_text())
                    require(packing_results["morphhdl"]["tests"] == 902 and
                            len(packing_results["morphhdl"]["suites"]) == 91,
                            "reviewed packing inventory changed its exact minimum or suite count")
                    directory = root / "morphhdl/target/test-reports"
                    packed_report = next(report for report in directory.glob("*.xml")
                                         if ET.parse(report).getroot().get("name") ==
                                         "spinal.core.PackedVecIdentityAdversarialTests")
                    require(ET.parse(packed_report).getroot().get("tests") == "12",
                            "reviewed packing inventory omitted its four integration cases")
                    reports(wa, descendant, composite, callbacks)
                    reject_reports("packing source cannot use the historical eight-case packed Vec inventory")
                    reports(wa, descendant, composite, callbacks, packing=True)
                    contract = root / packing_path
                    contract.unlink()
                    commit()
                    reject_reports("committed packing sources cannot lose their required adapter")
                    git("reset", "--hard", packing_head)
                    contract.write_bytes(packing_manifest + b" ")
                    reject_reports("changed pinned packing manifest")
                    forged = json.loads(packing_manifest)
                    forged["files"].append(dict(forged["files"][0], path=composite_paths[-1]))
                    forged_bytes = (json.dumps(forged, indent=2) + "\n").encode()
                    contract.write_bytes(forged_bytes)
                    write(publisher_path, fixture_publisher(packing_sha=hashlib.sha256(forged_bytes).hexdigest()))
                    reject_reports("packing adapter cannot absorb another composite source")
                    forged = json.loads(packing_manifest)
                    forged["files"][0]["edits"][0]["before"] += "unreviewed restored source\n"
                    forged_bytes = (json.dumps(forged, indent=2) + "\n").encode()
                    contract.write_bytes(forged_bytes)
                    write(publisher_path, fixture_publisher(packing_sha=hashlib.sha256(forged_bytes).hexdigest()))
                    reject_reports("packing adapter must restore the complete frozen composite blob")
                    contract.write_bytes(packing_manifest)
                    write(publisher_path, fixture_publisher())
                    for path, data in packing_bytes.items():
                        target = root / path
                        target.write_bytes(data + b"unreviewed packing mutation\n")
                        reject_reports("reviewed packing source mutation " + path)
                        target.write_bytes(data)
                        target.write_bytes(data + b"unreviewed staged packing mutation\n")
                        git("add", "--", path)
                        target.write_bytes(data)
                        reject_reports("staged packing mutation hidden by restored worktree " + path)
                        git("add", "--", path)
                        target.unlink()
                        reject_reports("missing reviewed packing source " + path)
                        target.write_bytes(data)
                    for relative in (packing_path, *packing_bytes):
                        target = root / relative
                        data = target.read_bytes()
                        target.chmod(0o755)
                        reject_reports("executable packing source or manifest " + relative)
                        target.chmod(0o644)
                        git("rm", "--cached", "--", relative)
                        reject_reports("untracked packing source or manifest " + relative)
                        git("add", "--", relative)
                        git("update-index", "--chmod=+x", "--", relative)
                        reject_reports("executable index mode on packing source or manifest " + relative)
                        git("update-index", "--chmod=-x", "--", relative)
                        link_target = root / "packing-symlink-target.txt"
                        link_target.write_bytes(data)
                        target.unlink()
                        target.symlink_to(link_target)
                        reject_reports("symlink packing source or manifest " + relative)
                        target.unlink()
                        target.write_bytes(data)
                        link_target.unlink()
                    git("reset", "--hard", historical_head)
                    # A historical source head must not acquire the extra test
                    # obligation merely because XML includes the new cases.
                    reports(wa, descendant, composite, callbacks, packing=True)
                    reject_reports("packing reports cannot activate an absent source adapter")
                    reports(wa, descendant, composite, callbacks)
                require(closure.production_profile(root) == expected_profile, "fixture restoration failed")

            original_review = (root / review_path).read_bytes()
            forged = json.loads(original_review)
            forged["files"].extend({"path": path, "sha256": digest} for path, digest in wa_hashes.items())
            forged["files"].sort(key=lambda entry: entry["path"])
            write(review_path, (json.dumps(forged) + "\n").encode())
            reject_reports("59d production review cannot absorb WA-07a paths")
            write(review_path, original_review)
            for incomplete in (wa_paths[:1], wa_paths[:2]):
                for path in incomplete:
                    git("checkout", qualified, "--", path)
                reject_reports("partial WA-07a source set in the 59d/59f union")
                for path in incomplete:
                    write(path, source_bytes[path])
                    git("add", "--", path)
            require(closure.production_profile(root) == "60f-with-wa07a-and-59d-and-59e-and-59f",
                    "fixture restoration failed")
    # The named-field extension preserves the entire existing feature union.
    # Missing any one admitted suite must still fail exact inventory matching.
    inherited = catalog_for_profile("60f-with-59d-and-59e-and-59f", True)
    named = catalog_for_profile("60f-with-59d-and-59e-and-59f-and-59c", True)
    additions = SUITE_EXTENSIONS["59c"]["projects"]["morphhdl"]
    require(named[1]["morphhdl"] == inherited[1]["morphhdl"] | additions and
            named[2] == inherited[2], "59c changed an inherited exact suite/test obligation")
    exact_names(set(named[1]["morphhdl"]), set(named[1]["morphhdl"]), "complete named suite inventory")
    bridges = catalog_for_profile("60f-with-59d-and-59e-and-59f-and-59c-and-59g", True)
    bridge_additions = SUITE_EXTENSIONS["59g"]["projects"]["morphhdl"]
    require(bridges[1]["morphhdl"] == named[1]["morphhdl"] | bridge_additions,
            "59g changed an inherited exact suite identity")
    require(bridges[2]["morphhdl"] == dict(named[2]["morphhdl"],
            **SUITE_EXTENSIONS["59g"]["counts"]), "59g changed an inherited exact test obligation")
    for missing in bridge_additions:
        rejected(lambda missing=missing: exact_names(set(bridges[1]["morphhdl"]) - {missing},
                 set(bridges[1]["morphhdl"]), "missing reviewed register-bridge suite"),
                 "missing exact 59g suite")
    for missing in additions:
        try:
            exact_names(set(named[1]["morphhdl"]) - {missing}, set(named[1]["morphhdl"]),
                        "missing reviewed named-field suite")
        except RuntimeError:
            rejections += 1
        else:
            raise RuntimeError("missing named-field suite was accepted: " + missing)
    nested = catalog_for_profile("60f-with-59d-and-59e-and-59f-and-59c-and-59h", True)
    nested_additions = INCREMENT_59H_SUITES["morphhdl"]
    require(nested[1]["morphhdl"] == named[1]["morphhdl"] | set(nested_additions) and
            nested[0]["morphhdl"] == (named[0]["morphhdl"][0] + sum(nested_additions.values()),
                                     named[0]["morphhdl"][1] + len(nested_additions)) and
            nested[2]["morphhdl"] == {**named[2]["morphhdl"], **nested_additions},
            "59h changed an inherited exact suite/test obligation")
    for missing in nested_additions:
        try:
            exact_names(set(nested[1]["morphhdl"]) - {missing}, set(nested[1]["morphhdl"]),
                        "missing reviewed nested-owner suite")
        except RuntimeError:
            rejections += 1
        else:
            raise RuntimeError("missing nested-owner suite was accepted: " + missing)
    # The rollout extends existing suites, not the nested-owner suite set.
    # Its union must retain every pre-rollout exact obligation and require the
    # two reviewed new counts, independent of arbitrary report contents.
    joined = catalog_for_profile("60f-with-59d-and-59e-and-59f-and-59c-and-59h-and-60g", True)
    expected_joined = {**nested[2]["morphhdl"],
        "spinal.core.internals.SignednessCompatibilityTests": 22,
        "spinal.core.internals.ParameterizedVerilogStructuralLexicalTests": 2}
    require(joined[1] == nested[1] and joined[2]["morphhdl"] == expected_joined and
            joined[0]["morphhdl"] == (nested[0]["morphhdl"][0] + 18, nested[0]["morphhdl"][1]),
            "60g/59h integration lost an exact suite or test obligation")
    print("60g/59h combined inventory retains nested owners and exact rollout test counts PASS")
    joint = catalog_for_profile("60f-with-59d-and-59e-and-59f-and-59c-and-59g-and-59h", True)
    require(joint[1]["morphhdl"] == bridges[1]["morphhdl"] | set(nested_additions),
            "joint 59g/59h inventory lost an exact suite identity")
    require(joint[2]["morphhdl"] == {**bridges[2]["morphhdl"], **nested_additions},
            "joint 59g/59h inventory replaced an inherited exact count")
    for missing in bridge_additions | set(nested_additions):
        rejected(lambda missing=missing: exact_names(set(joint[1]["morphhdl"]) - {missing},
                 set(joint[1]["morphhdl"]), "missing joint register/nested suite"),
                 "missing exact joint 59g/59h suite")
    rollout_joint = catalog_for_profile("60f-with-59d-and-59e-and-59f-and-59c-and-59g-and-59h-and-60g", True)
    require(rollout_joint[1] == joint[1] and
            rollout_joint[2]["morphhdl"] == {**joint[2]["morphhdl"],
                "spinal.core.internals.SignednessCompatibilityTests": 22,
                "spinal.core.internals.ParameterizedVerilogStructuralLexicalTests": 2} and
            rollout_joint[0]["morphhdl"] == (joint[0]["morphhdl"][0] + 18, joint[0]["morphhdl"][1]),
            "60g/register/nested composition lost exact inherited tests or suites")
    print("60g/59g/59h combined inventory preserves every register, nested and rollout obligation PASS")
    wa08_profile = "60f-with-wa07a-and-wa07b"
    historical = catalog_for_profile(wa08_profile)
    current = catalog_for_profile(wa08_profile, wa08=True)
    additions = WA08_SUITES["morphhdl"]
    require(current[1]["morphhdl"] == historical[1]["morphhdl"] | set(additions) and
            current[0]["morphhdl"] == (historical[0]["morphhdl"][0] + 3,
                                      historical[0]["morphhdl"][1] + 1) and
            current[2]["morphhdl"] == {**historical[2].get("morphhdl", {}), **additions} and
            all(current[index][project] == values for index in range(3)
                for project, values in historical[index].items() if project != "morphhdl"),
            "WA-08 changed an inherited suite or exact test obligation")
    rejected(lambda: catalog_for_profile("60f-baseline", wa08=True),
             "WA-08 inventory without its inherited source profile")
    wa09_profile = "60f-with-wa07a-and-59d-and-59e-and-59f-and-59c-and-59g-and-59h-and-wa07b-and-60g"
    wa09_previous = catalog_for_profile(wa09_profile, packing=True, wa08=True)
    successor = catalog_for_profile(wa09_profile, packing=True, wa08=True, wa09=True)
    wa10_successor = catalog_for_profile(
        wa09_profile, packing=True, wa08=True, wa09=True, wa10=True)
    expected_totals = {
        "paramrtl": (234, 23), "frontend": (257, 22), "backends/verilog": (148, 21),
        "morphhdl": (1115, 104), "morphir": (32, 2), "morphplugin": (16, 2),
        "core": (21, 2), "morphhdl-passes": (159, 18),
    }
    require(successor[0] == expected_totals and
            sum(tests for tests, _ in successor[0].values()) == 1982 and
            sum(sum(exact.values()) for exact in WA09_INHERITED_SUITE_COUNTS.values()) == 1941 and
            sum(len(exact) for exact in WA09_INHERITED_SUITE_COUNTS.values()) == 191,
            "WA-09 exact per-project inherited or successor totals changed")
    require(wa10_successor[0] == {
                **expected_totals, "morphhdl": (1119, 105)
            } and
            wa10_successor[1]["morphhdl"] == successor[1]["morphhdl"] |
                {"morphhdl.examples.NativeWireExpressionCodecTests"} and
            all(wa10_successor[1][project] == successor[1][project]
                for project in successor[1] if project != "morphhdl") and
            wa10_successor[2]["morphhdl"] == {
                **successor[2]["morphhdl"],
                "morphhdl.examples.NativeWireExpressionCodecTests": 3,
                "spinal.core.MorphVerilogExpressionInliningTests": 11,
            } and
            sum(tests for tests, _ in wa10_successor[0].values()) == 1986,
            "WA-10 exact public suite successor count changed")
    for project, inherited in WA09_INHERITED_SUITE_COUNTS.items():
        exact = WA09_SUITES.get(project, {})
        expected_names = wa09_previous[1][project] | (set(exact) - WA09_EXISTING_SUITES)
        expected_counts = {**inherited, **exact}
        require(successor[1][project] == expected_names and
                successor[2][project] == expected_counts,
                "WA-09 changed an inherited exact suite obligation: " + project)
    rejected(lambda: catalog_for_profile(wa09_profile, packing=True, wa09=True),
             "WA-09 inventory without its reviewed WA-08 predecessor")
    rejected(lambda: catalog_for_profile(wa09_profile, wa08=True, wa09=True),
             "WA-09 exact catalog without the verified packing profile")
    rejected(lambda: catalog_for_profile(wa08_profile, wa08=True, wa09=True),
             "WA-09 exact catalog without the complete inherited source profile")
    rejected(lambda: catalog_for_profile(
        wa09_profile, packing=True, wa08=True, wa10=True),
        "WA-10 inventory without its reviewed WA-09 predecessor")
    with tempfile.TemporaryDirectory(prefix="increment-60f-wa08-inventory-") as temporary:
        root = Path(temporary)
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        subprocess.run(["git", "-c", "user.name=Synthetic Test", "-c",
                        "user.email=synthetic@example.invalid", "commit", "--allow-empty",
                        "-qm", "synthetic inventory fixture"], cwd=root, check=True)
        def write_reports(catalog: tuple[dict, dict, dict]) -> None:
            for project, (minimum, _) in catalog[0].items():
                exact = catalog[2].get(project, {})
                counts = {name: exact.get(name, 1) for name in catalog[1][project]}
                inherited = catalog[1][project] - exact.keys()
                if inherited:
                    counts[min(inherited)] += minimum - sum(counts.values())
                directory = root / project / "target/test-reports"
                directory.mkdir(parents=True, exist_ok=True)
                for old in directory.glob("*.xml"):
                    old.unlink()
                for name, count in counts.items():
                    suite = ET.Element("testsuite", name=name, tests=str(count),
                                       failures="0", errors="0", skipped="0")
                    for case in range(count):
                        ET.SubElement(suite, "testcase", name=f"synthetic-case-{case}")
                    ET.ElementTree(suite).write(directory / (name + ".xml"))

        write_reports(current)
        # Only the source verifier's result is synthetic here. Its real Git and
        # byte-integrity attacks run in the separate WA-08 overlay self-test;
        # these controls exercise the unchanged report parser and exact catalog.
        overlay = mock.Mock()
        reviewed = {"files": [{"path": WA08_SUITE_SOURCE, "before_sha256": None}]}
        overlay.verify.return_value = reviewed
        ternary = mock.Mock()
        ternary.wa08_overlay.return_value = overlay
        output = root / "result.json"

        def validate_wa08_reports() -> None:
            output.unlink(missing_ok=True)
            with contextlib.redirect_stdout(io.StringIO()):
                _regression_inventory(root, output, wa08_profile)

        with mock.patch.object(closure_module(), "boolean_ternary_review", return_value=ternary):
            validate_wa08_reports()
            overlay.verify.assert_called_once_with(root)
            name = next(iter(additions))
            path = root / "morphhdl/target/test-reports" / (name + ".xml")
            original = path.read_bytes()
            for attribute, value in (("name", "synthetic.SubstituteSuite"), ("skipped", "1")):
                tree = ET.parse(path)
                tree.getroot().set(attribute, value)
                tree.write(path)
                rejected(validate_wa08_reports, "WA-08 changed exact suite " + attribute)
                require(not output.exists(), "failed WA-08 inventory retained stale success")
                path.write_bytes(original)
            for count in (2, 4):
                tree = ET.parse(path)
                suite = tree.getroot()
                suite.set("tests", str(count))
                if count == 2:
                    suite.remove(suite.find("testcase"))
                else:
                    ET.SubElement(suite, "testcase", name="synthetic-extra-case")
                tree.write(path)
                rejected(validate_wa08_reports, "WA-08 changed exact test count")
                path.write_bytes(original)
            path.unlink()
            rejected(validate_wa08_reports, "missing WA-08 suite")
            path.write_bytes(original)
            extra = path.with_name("unknown.xml")
            tree = ET.parse(path)
            tree.getroot().set("name", "synthetic.UnknownSuite")
            tree.write(extra)
            rejected(validate_wa08_reports, "unexpected suite alongside WA-08")
            extra.unlink()
            ternary.wa08_overlay.return_value = None
            rejected(validate_wa08_reports, "WA-08 reports without source overlay")
            ternary.wa08_overlay.return_value = overlay
            for entries in ([], [{"path": WA08_SUITE_SOURCE, "before_sha256": "0" * 64}]):
                overlay.verify.return_value = {"files": entries}
                rejected(validate_wa08_reports, "WA-08 suite outside reviewed added-source inventory")
            overlay.verify.side_effect = RuntimeError("synthetic source-integrity rejection")
            rejected(validate_wa08_reports, "WA-08 verifier rejection must propagate")
            overlay.verify.side_effect = None
            overlay.verify.return_value = reviewed
            validate_wa08_reports()
            # Exact successor reports require the complete inherited source
            # profile. These placeholder sources only exercise real Git/source
            # inventory; production byte-integrity is independently sealed.
            for descendant in SUITE_EXTENSIONS.values():
                for source in descendant["sources"]:
                    source_path = root / source
                    source_path.parent.mkdir(parents=True, exist_ok=True)
                    source_path.write_text("synthetic reviewed suite source\n")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Synthetic Test", "-c",
                            "user.email=synthetic@example.invalid", "commit", "-qm",
                            "synthetic complete inherited sources"], cwd=root, check=True)
            packing_path = root / closure_module().PACKING_59D59E_CONTRACT
            packing_path.parent.mkdir(parents=True, exist_ok=True)
            packing_path.write_text("synthetic packing review\n")
            publisher = mock.Mock()
            publisher.reviewed_59d59e_packing.return_value = {
                path: "synthetic digest" for path in closure_module().PACKING_59D59E_PATHS}

            def validate_wa09_reports() -> None:
                output.unlink(missing_ok=True)
                with mock.patch.object(closure_module(), "load", return_value=publisher), \
                        contextlib.redirect_stdout(io.StringIO()):
                    _regression_inventory(root, output, wa09_profile)

            wa09_entries = [
                {"path": path, "before_sha256": None if added else "a" * 64}
                for path, added in WA09_SUITE_SOURCES.items()
            ]
            reviewed_wa09 = {"files": [*reviewed["files"], *wa09_entries]}
            overlay.verify.return_value = reviewed_wa09
            write_reports(successor)
            validate_wa09_reports()
            for project, exact in successor[2].items():
                for name, count in exact.items():
                    path = root / project / "target/test-reports" / (name + ".xml")
                    original = path.read_bytes()
                    for delta in (-1, 1):
                        tree = ET.parse(path)
                        suite = tree.getroot()
                        suite.set("tests", str(count + delta))
                        if delta == -1:
                            suite.remove(suite.find("testcase"))
                        else:
                            ET.SubElement(suite, "testcase", name="synthetic-unreviewed-extra-case")
                        tree.write(path)
                        rejected(validate_wa09_reports, "WA-09 changed exact test count " + name)
                        require(not output.exists(), "failed WA-09 inventory retained stale success")
                        path.write_bytes(original)
                    for attribute, value in (("name", "synthetic.SubstituteSuite"), ("skipped", "1")):
                        tree = ET.parse(path)
                        tree.getroot().set(attribute, value)
                        tree.write(path)
                        rejected(validate_wa09_reports, "WA-09 changed exact suite " + attribute + " " + name)
                        path.write_bytes(original)
                    path.unlink()
                    rejected(validate_wa09_reports, "missing WA-09 exact suite " + name)
                    path.write_bytes(original)
            inherited_path = next((root / "paramrtl/target/test-reports").glob("*.xml"))
            inherited_original = inherited_path.read_bytes()
            tree = ET.parse(inherited_path)
            suite = tree.getroot()
            suite.set("tests", str(int(suite.get("tests")) + 1))
            ET.SubElement(suite, "testcase", name="synthetic-unreviewed-inherited-case")
            tree.write(inherited_path)
            rejected(validate_wa09_reports,
                     "WA-09 exact complete inventory cannot absorb an inherited extra case")
            inherited_path.write_bytes(inherited_original)
            # A count-only project total would accept this loss masked by an
            # extra testcase in another suite. Every suite count stays exact.
            other_path = next(path for path in
                              (root / "paramrtl/target/test-reports").glob("*.xml")
                              if path != inherited_path)
            other_original = other_path.read_bytes()
            for path, delta in ((inherited_path, -1), (other_path, 1)):
                tree = ET.parse(path)
                suite = tree.getroot()
                suite.set("tests", str(int(suite.get("tests")) + delta))
                if delta == -1:
                    suite.remove(suite.find("testcase"))
                else:
                    ET.SubElement(suite, "testcase", name="synthetic-compensating-extra-case")
                tree.write(path)
            rejected(validate_wa09_reports, "WA-09 removed inherited case compensated in another suite")
            inherited_path.write_bytes(inherited_original)
            other_path.write_bytes(other_original)
            extra_path = inherited_path.with_name("unknown.xml")
            tree = ET.parse(inherited_path)
            tree.getroot().set("name", "synthetic.UnknownSuite")
            tree.write(extra_path)
            rejected(validate_wa09_reports, "WA-09 unexpected suite alongside all inherited suites")
            extra_path.unlink()
            overlay.verify.return_value = reviewed
            rejected(validate_wa09_reports, "WA-09 reports without WA-09 source enrollment")
            for omitted in WA09_SUITE_SOURCES:
                overlay.verify.return_value = {
                    "files": [entry for entry in reviewed_wa09["files"]
                              if entry["path"] != omitted]
                }
                rejected(validate_wa09_reports, "partial WA-09 suite-source enrollment " + omitted)
            for path, added in WA09_SUITE_SOURCES.items():
                mutated = [dict(entry) for entry in reviewed_wa09["files"]]
                entry = next(entry for entry in mutated if entry["path"] == path)
                entry["before_sha256"] = "b" * 64 if added else None
                overlay.verify.return_value = {"files": mutated}
                rejected(validate_wa09_reports, "changed WA-09 source baseline identity " + path)
            overlay.verify.return_value = reviewed_wa09
            validate_wa09_reports()
            wa10_source_entries = [
                {"path": path, "before_sha256": None}
                for path in WA10_SUITE_SOURCES if path not in WA09_SUITE_SOURCES
            ]
            reviewed_wa10 = {"files": [*reviewed_wa09["files"],
                *wa10_source_entries,
                {"path": WA10_CONTRACT, "before_sha256": None}]}
            overlay.verify.return_value = reviewed_wa10
            write_reports(wa10_successor)
            validate_wa09_reports()
            wa10_path = root / "morphhdl/target/test-reports" / \
                "spinal.core.MorphVerilogExpressionInliningTests.xml"
            wa10_original = wa10_path.read_bytes()
            tree = ET.parse(wa10_path)
            suite = tree.getroot()
            suite.set("tests", "10")
            suite.remove(suite.find("testcase"))
            tree.write(wa10_path)
            rejected(validate_wa09_reports, "WA-10 removed its reviewed public test")
            wa10_path.write_bytes(wa10_original)
            overlay.verify.return_value = reviewed_wa09
            rejected(validate_wa09_reports,
                     "WA-10 report count without WA-10 source enrollment")
            changed_contract = [dict(entry) for entry in reviewed_wa10["files"]]
            next(entry for entry in changed_contract
                 if entry["path"] == WA10_CONTRACT)["before_sha256"] = "b" * 64
            overlay.verify.return_value = {"files": changed_contract}
            rejected(validate_wa09_reports, "changed WA-10 contract baseline identity")
            overlay.verify.return_value = reviewed_wa10
            validate_wa09_reports()
    print("WA-08 inventory retains every inherited suite and requires its exact three-case verified addition PASS")
    print("WA-09 inventory requires exactly 1982 cases / 194 suites: 1115 Morph, 159 pass, 21 core cases PASS")
    print("WA-10 successor inventory requires exactly 1986 cases / 195 suites: 1119 Morph cases PASS")
    print(f"60f inventory self-test: inherited exact source profiles, named/register/nested suite extensions and {rejections} rejection controls PASS")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("self-test", help="synthetic inventory controls; no HDL qualification")
    fresh = commands.add_parser("snapshot")
    fresh.add_argument("left", type=Path)
    fresh.add_argument("right", type=Path)
    fresh.add_argument("output", type=Path)
    fresh.add_argument("--scala", choices=("2.12.18", "2.13.12"), required=True)
    cross = commands.add_parser("compare")
    cross.add_argument("left", type=Path)
    cross.add_argument("right", type=Path)
    tests = commands.add_parser("regressions")
    tests.add_argument("root", type=Path)
    tests.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.command == "self-test":
        self_test()
    elif args.command == "snapshot":
        snapshot(args.left, args.right, args.output, args.scala)
    elif args.command == "compare":
        compare(args.left, args.right)
    else:
        regressions(args.root, args.output)


if __name__ == "__main__":
    main()
