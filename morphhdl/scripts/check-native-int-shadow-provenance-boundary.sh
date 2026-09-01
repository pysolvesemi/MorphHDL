#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

registry=morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala
api=frontend/src/main/scala/morphhdl/frontend/NativeIntShadow.scala
region=frontend/src/main/scala/morphhdl/frontend/formalRegion.scala
component=frontend/src/main/scala/morphhdl/frontend/formalComponent.scala
publisher=frontend/src/main/scala/spinal/core/ExternalAnalyzedNativeIntFormalizationPublisher.scala
test_file=morphhdl/src/test/scala/morphhdl/ExternalNativeIntShadowProvenanceTests.scala
document=docs/morphhdl/increment-49-native-int-symbolic-provenance.md

for path in "$registry" "$api" "$region" "$component" "$publisher" "$test_file" "$document"; do
  test -f "$path"
done

grep -Fq 'object ExternalNativeIntShadowRegistry' "$registry"
grep -Fq 'ThreadLocal[List[ActiveBoundary]]' "$registry"
grep -Fq 'ReferenceQueue[Component]' "$registry"
grep -Fq 'ReferenceQueue[Data]' "$registry"
grep -Fq 'System.identityHashCode' "$registry"
grep -Fq 'left eq right' "$registry"
grep -Fq 'parentBoundaryToken' "$registry"
grep -Fq 'MORPH-FRONTEND-NATIVE-INT-SHADOW-EXPRESSION-DEFERRED' "$registry"
grep -Fq 'object NativeIntShadow' "$api"
grep -Fq 'object shadowInt' "$api"
grep -Fq 'ExternalAnalyzedNativeIntFormalizationPublisher.captureRegion' "$region"
grep -Fq 'ExternalAnalyzedNativeIntFormalizationPublisher.publishRegion' "$region"
grep -Fq 'ExternalAnalyzedNativeIntFormalizationPublisher.captureComponent' "$component"
grep -Fq 'ExternalAnalyzedNativeIntFormalizationPublisher.publishComponent' "$component"
grep -Fq 'ExternalNativeIntShadowRegistry.capture(' "$publisher"
grep -Fq 'ExternalNativeIntShadowRegistry.captureWithDefinition(' "$publisher"
grep -Fq 'ExternalNativeIntFormalizationRegistry.attachRegionAtomically(' "$publisher"
grep -Fq 'ExternalNativeIntFormalizationRegistry.attachComponentAtomically(' "$publisher"
grep -Fq 'class ExternalNativeIntShadowProvenanceTests' "$test_file"
grep -Fq 'nested boundaries preserve exact stack ownership' "$test_file"
grep -Fq 'shadow provenance replay is deterministic' "$test_file"
grep -Fq 'selected derived native Int remains fail closed until Increment 50' "$test_file"

python3 - <<'PY'
from pathlib import Path

paths = (
    Path("morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"),
    Path("frontend/src/main/scala/morphhdl/frontend/NativeIntShadow.scala"),
    Path("frontend/src/main/scala/morphhdl/frontend/formalRegion.scala"),
    Path("frontend/src/main/scala/morphhdl/frontend/formalComponent.scala"),
    Path("frontend/src/main/scala/spinal/core/ExternalAnalyzedNativeIntFormalizationPublisher.scala"),
)
production = "\n".join(path.read_text() for path in paths)

for forbidden in (
    "HashMap.empty[Int",
    "HashMap.empty[BigInt",
    "Map[Int,",
    "Map[BigInt,",
    "groupBy(_.value)",
    "groupBy(_.default)",
    "find(_.getBitsWidth",
    ".getName()",
    ".getDisplayName",
    ".getPartialName",
    ".getScalaName",
):
    if forbidden in production:
        raise SystemExit(f"native Int shadow provenance contains forbidden discovery: {forbidden}")

required = (
    "The ordinary `Int` value is never boxed, replaced or used as a",
    "Increment 49 deliberately accepts only direct aliases",
    "ReferenceQueue[Component]",
    "ReferenceQueue[Data]",
)
for phrase in required:
    if phrase not in production:
        raise SystemExit(f"native Int shadow rationale is missing: {phrase}")
PY

python3 morphhdl/scripts/check-native-source-preservation.py

echo "Native Int shadow provenance boundary is valid"
