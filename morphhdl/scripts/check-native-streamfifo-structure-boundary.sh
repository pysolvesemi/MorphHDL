#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

[[ ! -e lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala ]]
[[ ! -e frontend/src/main/scala/spinal/lib/ExternalParameterizedStreamFifoDepthRegistry.scala ]]
! grep -R --line-number --fixed-strings 'rewriteParameterizedStreamFifoDepth'   morphhdl/src/main/scala frontend/src/main/scala morphruntime/src/main/scala lib/src/main/scala
! grep -R --line-number --fixed-strings 'fromParameterizedMemoryDepth'   frontend/src/main/scala
! grep -R --line-number -E   'io_push_(valid|ready|payload)|io_pop_(valid|ready|payload)|io_occupancy|io_availability'   morphhdl/src/main/scala/spinal/core/internals
! grep -q -E 'NativeHardwareNames|looksNativeHardware' \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala
! grep -q -E '"(push|pop|popOnIo|occupancy|availability|full|empty|addressGen|readArbitration|readPort|io)"' \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala
! grep -q -E 'StreamFifo|Stream\.scala|nativeStreamFifo|inNativeStreamFifo|NativeStreamFifo' \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala
! grep -q -E '"(depth|dataType|withAsyncRead|withBypass|allowExtraMsb|forFMax|useVec)"' \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala
grep -q 'discoverNativeConstructors' \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala
grep -q 'ExternalNativeIntFormalComponent.parameter' \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala
grep -q 'selectedNativeIntParameter' \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala
grep -q 'ValDef roots enter rewriteExpression directly' \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala

constructor_selection_tests=morphplugin/src/test/scala/morphhdl/compiler/NativeIntConstructorSelectionTests.scala
grep -q 'exact named constructor slot without a source-text gate' "$constructor_selection_tests"
grep -q 'boundary witness used by more than one constructor slot' "$constructor_selection_tests"
grep -q 'rejects an indirect constructor boundary' "$constructor_selection_tests"
grep -q 'rejects auxiliary constructors' "$constructor_selection_tests"
grep -q 'simple-name collision' "$constructor_selection_tests"
grep -q 'simple declaration name collides' "$constructor_selection_tests"
grep -q 'shadow the selected slot' "$constructor_selection_tests"
grep -q 'anonymous-record field read' "$constructor_selection_tests"
grep -q 'applied Scala flush call' "$constructor_selection_tests"

predicate_domain_tests=morphhdl/src/test/scala/spinal/core/StructuralPredicateDomainTests.scala
grep -q 'large canonical comparison domains prove independent disjoint alternatives' "$predicate_domain_tests"
grep -q 'large canonical comparison domains do not authorize overlapping alternatives' "$predicate_domain_tests"
grep -q 'predicate evidence is bound to one exact capture root identity' "$predicate_domain_tests"
grep -q 'large canonical power-of-two and complement domains are exactly disjoint' "$predicate_domain_tests"
grep -q 'unsupported large-domain predicates retain no exclusivity evidence' "$predicate_domain_tests"

generic_generate_tests=morphhdl/src/test/scala/spinal/lib/GenericNativeIntGenerateTests.scala
grep -q 'generically instrumented generate regions accept exact disjoint domains' "$generic_generate_tests"
grep -q 'generically instrumented generate regions reject overlapping domains' "$generic_generate_tests"
grep -q 'empty-domain generate is elided while a reachable witness-false body is captured' "$generic_generate_tests"
grep -q 'maximum = BigInt(Int.MaxValue) - 1' "$generic_generate_tests"

increment_workflow=.github/workflows/morphhdl-native-streamfifo-structure.yml
grep -q 'morphhdl.compiler.NativeIntConstructorSelectionTests' "$increment_workflow"
grep -q 'spinal.core.StructuralPredicateDomainTests' "$increment_workflow"
grep -q 'spinal.lib.GenericNativeIntGenerateTests' "$increment_workflow"

grep -q 'depth: ParameterizedMemoryDepth'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'ExternalNativeIntFormalComponent.parameter'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'ParameterizedDepthMinimum = BigInt(1)'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'ParameterizedDepthMaximum = BigInt(Int.MaxValue) - 1'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'minimum = ParameterizedDepthMinimum'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'maximum = ParameterizedDepthMaximum'   lib/src/main/scala/spinal/lib/Stream.scala
! grep -q 'maximum = BigInt(4096)'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'push := (push + 1).resized'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'pop := (pop + 1).resized'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'push := U(0).resized'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'pop := U(0).resized'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'object ExternalNativeIntFormalComponent'   morphruntime/src/main/scala/spinal/core/ExternalNativeIntFormalComponent.scala
grep -q 'val fifo = spinal.lib.StreamFifo'   morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala
! grep -q 'MorphStreamFifo'   morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala

process_tree_tests=morphhdl/src/test/scala/morphhdl/NativeProcessTreeCaptureTests.scala
grep -q 'native when and switch process trees relocate wholly into parameter alternatives' "$process_tree_tests"
grep -q 'unsupported statements nested in native process trees fail closed' "$process_tree_tests"
grep -q 'nested process throws restore the complete entry scope context' "$process_tree_tests"
grep -q 'captured process trees cannot adopt statements owned before capture' "$process_tree_tests"
grep -q 'captured initializers reject targets declared outside their structural block' "$process_tree_tests"
grep -q 'insertion into an existing process tree rolls back before graph reuse' "$process_tree_tests"
grep -q 'foreign memory ports roll back both scope and memory DLC ownership' "$process_tree_tests"
grep -q 'detached new assignments and ports restore exact pre-capture DLC state' "$process_tree_tests"
grep -q 'foreign component statements restore their original owner and order' "$process_tree_tests"
grep -q 'pre-existing statement order is validated and restored transactionally' "$process_tree_tests"
grep -q 'cross-component capture entry fails before executing or mutating the body' "$process_tree_tests"
grep -q 'rollback restores children and IO on every pre-existing component' "$process_tree_tests"
grep -q 'failed nested registrations restore labels pending continuations and graph state' "$process_tree_tests"
grep -q 'morphhdl.NativeProcessTreeCaptureTests' \
  .github/workflows/morphhdl-native-streamfifo-structure.yml

python3 morphhdl/scripts/check-native-source-preservation.py   --manifest morphhdl/contracts/native-source-preservation.json

printf 'Increment 53 native StreamFifo source boundary passed.\n'
