#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

region_adapter=frontend/src/main/scala/morphhdl/frontend/formalRegion.scala
component_adapter=frontend/src/main/scala/morphhdl/frontend/formalComponent.scala
publisher=frontend/src/main/scala/spinal/core/ExternalAnalyzedNativeIntFormalizationPublisher.scala
registry=morphruntime/src/main/scala/spinal/core/ExternalNativeIntFormalizationRegistry.scala
formal_registry=morphruntime/src/main/scala/spinal/core/ExternalFormalParameterRegistry.scala
hdl_int=frontend/src/main/scala/morphhdl/frontend/HdlInt.scala
hierarchy=morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala
test_file=morphhdl/src/test/scala/morphhdl/ExternalNativeIntFormalizationTests.scala
document=docs/morphhdl/increment-47-external-formalization-boundary.md

for path in \
  "$region_adapter" \
  "$component_adapter" \
  "$publisher" \
  "$registry" \
  "$formal_registry" \
  "$hdl_int" \
  "$hierarchy" \
  "$test_file" \
  "$document"; do
  test -f "$path"
done

grep -Fq 'object formalRegion' "$region_adapter"
grep -Fq 'constructor: Int => T' "$region_adapter"
grep -Fq 'ExternalAnalyzedNativeIntFormalizationPublisher.captureRegion' "$region_adapter"
grep -Fq 'ExternalAnalyzedNativeIntFormalizationPublisher.publishRegion' "$region_adapter"
grep -Fq 'object formalComponent' "$component_adapter"
grep -Fq 'constructor: Int => C' "$component_adapter"
grep -Fq 'geometry: C => Iterable[Data]' "$component_adapter"
grep -Fq 'ExternalAnalyzedNativeIntFormalizationPublisher.captureComponent' "$component_adapter"
grep -Fq 'ExternalAnalyzedNativeIntFormalizationPublisher.publishComponent' "$component_adapter"
grep -Fq 'ExternalNativeIntFormalizationRegistry.attachRegionAtomically' "$publisher"
grep -Fq 'ExternalNativeIntFormalizationRegistry.attachComponentAtomically' "$publisher"
grep -Fq 'ExternalFormalParameterRegistry.preflightAttachAll' "$registry"
grep -Fq 'ExternalFormalParameterRegistry.commitAttachAll' "$registry"
grep -Fq 'ExternalFormalParameterRegistry.preflightRetainComponent' "$registry"
grep -Fq 'ExternalFormalParameterRegistry.commitRetainComponent' "$registry"
grep -Fq 'System.identityHashCode' "$registry"
grep -Fq 'ReferenceQueue[Data]' "$registry"
grep -Fq 'ReferenceQueue[Component]' "$registry"
grep -Fq 'left eq right' "$registry"
grep -Fq 'private[frontend] def nativeIntExpression' "$hdl_int"
grep -Fq 'private[frontend] def formalBindingForOwner' "$hdl_int"
grep -Fq 'plainStartPattern' "$hierarchy"
grep -Fq 'parameterizedStartPattern' "$hierarchy"
grep -Fq 'bindingsOf(component)' "$hierarchy"
grep -Fq 'private def retainedFormals' "$hierarchy"
grep -Fq 'class ExternalNativeIntFormalizationTests' "$test_file"
grep -Fq 'MORPH-FRONTEND-FORMAL-REGION-CONFLICT' "$test_file"
grep -Fq 'one exact component rejects conflicting actuals' "$test_file"

python3 - <<'PY'
from pathlib import Path

registry = Path(
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntFormalizationRegistry.scala"
).read_text()
component = Path(
    "frontend/src/main/scala/morphhdl/frontend/formalComponent.scala"
).read_text()
region = Path(
    "frontend/src/main/scala/morphhdl/frontend/formalRegion.scala"
).read_text()
publisher = Path(
    "frontend/src/main/scala/spinal/core/ExternalAnalyzedNativeIntFormalizationPublisher.scala"
).read_text()
production = "\n".join((registry, component, region, publisher))

for forbidden in (
    "HashMap.empty[Int",
    "HashMap.empty[BigInt",
    "Map[Int,",
    "Map[BigInt,",
    "groupBy(_.value)",
    "groupBy(_.default)",
    "find(_.getBitsWidth",
    "find(_.value",
    "find(_._2.value",
):
    if forbidden in production:
        raise SystemExit(
            f"native-Int formalization contains concrete-value discovery: {forbidden}"
        )

# Names may appear in diagnostics and documentation, but production must not
# ask the native graph for emitted names to discover geometry or ownership.
for forbidden in (
    ".getName()",
    ".getName(",
    ".getDisplayName",
    ".getPartialName",
    ".getScalaName",
    "definitionName ==",
    "instanceName ==",
):
    if forbidden in production:
        raise SystemExit(
            f"native-Int formalization contains emitted/source-name discovery: {forbidden}"
        )

required_phrases = (
    "No concrete integer",
    "Weak keys bound metadata lifetime",
    "constructor receives only the checked concrete witness",
)
combined = registry + "\n" + component + "\n" + region + "\n" + publisher
for phrase in required_phrases:
    if phrase not in combined:
        raise SystemExit(f"native-Int boundary rationale is missing: {phrase}")
PY

python3 morphhdl/scripts/check-native-source-preservation.py

echo "External native-Int formalization boundary is valid"
