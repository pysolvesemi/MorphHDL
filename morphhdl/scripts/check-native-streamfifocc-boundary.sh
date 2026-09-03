#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

stream_source="${MORPHDL_STREAMFIFOCC_STREAM_SOURCE:-lib/src/main/scala/spinal/lib/Stream.scala}"
utils_source="${MORPHDL_STREAMFIFOCC_UTILS_SOURCE:-lib/src/main/scala/spinal/lib/Utils.scala}"
crossclock_source="${MORPHDL_STREAMFIFOCC_CROSSCLOCK_SOURCE:-lib/src/main/scala/spinal/lib/CrossClock.scala}"
phase_source="${MORPHDL_STREAMFIFOCC_PHASE_SOURCE:-core/src/main/scala/spinal/core/internals/Phase.scala}"
memory_source="${MORPHDL_STREAMFIFOCC_MEMORY_SOURCE:-morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala}"
hierarchy_source="${MORPHDL_STREAMFIFOCC_HIERARCHY_SOURCE:-morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala}"
fallback_source="${MORPHDL_STREAMFIFOCC_FALLBACK_SOURCE:-morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala}"
test_source="${MORPHDL_STREAMFIFOCC_TEST_SOURCE:-morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCParameterizedTests.scala}"
reuse_test_source="${MORPHDL_STREAMFIFOCC_REUSE_TEST_SOURCE:-morphhdl/src/test/scala/morphhdl/NativeLibraryReuseTests.scala}"
cdc_test_source="${MORPHDL_STREAMFIFOCC_CDC_TEST_SOURCE:-morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCCdcProofTests.scala}"
formal_test_source="${MORPHDL_STREAMFIFOCC_FORMAL_TEST_SOURCE:-morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCFormalEquivalenceTests.scala}"

fail() {
  local code="$1"
  shift
  printf 'MORPH-NATIVE-STREAMFIFOCC-%s: %s\n' "$code" "$*" >&2
  exit 1
}

require_file() {
  local path="$1"
  local label="$2"
  test -f "$path" || fail SOURCE-MISSING "$label source is missing: $path"
}

check_boundary() {
  require_file "$stream_source" Stream
  require_file "$utils_source" Gray-code-helper
  require_file "$crossclock_source" BufferCC
  require_file "$phase_source" SpinalVerilog-boot
  require_file "$memory_source" parameterized-memory
  require_file "$hierarchy_source" parameterized-hierarchy
  require_file "$fallback_source" parameterized-native-fallback
  require_file "$test_source" focused-test
  require_file "$reuse_test_source" shared-helper-test
  require_file "$cdc_test_source" CDC-proof-test
  require_file "$formal_test_source" formal-proof-test

  python3 - \
    "$stream_source" \
    "$utils_source" \
    "$crossclock_source" \
    "$phase_source" \
    "$memory_source" \
    "$hierarchy_source" \
    "$fallback_source" \
    "$test_source" \
    "$reuse_test_source" \
    "$cdc_test_source" \
    "$formal_test_source" <<'PY'
import re
import sys
from pathlib import Path

(
    stream_path,
    utils_path,
    crossclock_path,
    phase_path,
    memory_path,
    hierarchy_path,
    fallback_path,
    test_path,
    reuse_test_path,
    cdc_test_path,
    formal_test_path,
) = map(Path, sys.argv[1:])

stream = stream_path.read_text(encoding="utf-8")
utils = utils_path.read_text(encoding="utf-8")
crossclock = crossclock_path.read_text(encoding="utf-8")
phase = phase_path.read_text(encoding="utf-8")
memory = memory_path.read_text(encoding="utf-8")
hierarchy = hierarchy_path.read_text(encoding="utf-8")
fallback = fallback_path.read_text(encoding="utf-8")
tests = test_path.read_text(encoding="utf-8")
reuse_tests = reuse_test_path.read_text(encoding="utf-8")
cdc_tests = cdc_test_path.read_text(encoding="utf-8")
formal_tests = formal_test_path.read_text(encoding="utf-8")


def fail(code: str, message: str) -> None:
    raise SystemExit(f"MORPH-NATIVE-STREAMFIFOCC-{code}: {message}")


def require(pattern: str, source: str, code: str, message: str) -> None:
    if re.search(pattern, source, re.MULTILINE | re.DOTALL) is None:
        fail(code, message)


def reject(pattern: str, source: str, code: str, message: str) -> None:
    if re.search(pattern, source, re.MULTILINE | re.DOTALL) is not None:
        fail(code, message)


# Exactly the reviewed native-looking ingress is widened. Existing Int entry
# points stay present and authoritative for literals.
for name in ("queue", "queueWithPushOccupancy"):
    require(
        rf"def\s+{name}\s*\(\s*size:\s*ElabInt,\s*pushClock:\s*ClockDomain,\s*popClock:\s*ClockDomain\s*\)",
        stream,
        "TYPED-STREAM-HELPER-MISSING",
        f"Stream.{name} must expose the reviewed ElabInt cross-clock overload",
    )
    require(
        rf"def\s+{name}\s*\(\s*size:\s*Int,\s*pushClock:\s*ClockDomain,\s*popClock:\s*ClockDomain\s*\)",
        stream,
        "CONCRETE-STREAM-HELPER-MISSING",
        f"Stream.{name} must retain its ordinary Int overload",
    )

require(
    r"def\s+apply\s*\[T\s*<:\s*Data\]\s*\(\s*dataType:\s*HardType\[T\],\s*depth:\s*ElabInt,\s*pushClock:\s*ClockDomain,\s*popClock:\s*ClockDomain",
    stream,
    "TYPED-COMPANION-MISSING",
    "StreamFifoCC data-type companion entry must accept ElabInt depth",
)
require(
    r"def\s+apply\s*\[T\s*<:\s*Data\]\s*\(\s*push\s*:\s*Stream\[T\],\s*pop\s*:\s*Stream\[T\],\s*depth:\s*ElabInt,\s*pushClock:\s*ClockDomain,\s*popClock:\s*ClockDomain",
    stream,
    "TYPED-CONNECTED-COMPANION-MISSING",
    "StreamFifoCC connected-Stream companion entry must accept ElabInt depth",
)
require(
    r"class\s+StreamFifoCC\[T\s*<:\s*Data\]\s+private\[lib\]\s*\(\s*val\s+dataType:\s*HardType\[T\],\s*private\[lib\]\s+val\s+elabDepth:\s*ElabInt",
    stream,
    "TYPED-NATIVE-CLASS-MISSING",
    "the native StreamFifoCC definition-only constructor must privately retain authoritative ElabInt depth",
)
reject(
    r"def\s+this\s*\(\s*dataType:\s*HardType\[T\],\s*depth:\s*ElabInt",
    stream,
    "PUBLIC-TYPED-CONSTRUCTOR",
    "symbolic StreamFifoCC ingress must remain companion/helper-owned rather than a public auxiliary constructor",
)
require(
    r"def\s+this\s*\(\s*dataType:\s*HardType\[T\],\s*depth:\s*Int,\s*pushClock:\s*ClockDomain,\s*popClock:\s*ClockDomain,\s*withPopBufferedReset:\s*Boolean\s*=",
    stream,
    "LEGACY-CONSTRUCTOR-MISSING",
    "the legacy Int constructor and reset default must remain source/JVM compatible",
)
require(
    r"val\s+depth:\s*Int\s*=\s*elabDepth\.witness",
    stream,
    "LEGACY-DEPTH-ACCESSOR-MISSING",
    "the compatibility depth accessor must expose only the construction witness",
)
require(
    r"val\s+ptrWidth:\s*Int\s*=\s*elabPtrWidth\.witness",
    stream,
    "LEGACY-POINTER-WIDTH-ACCESSOR-MISSING",
    "the compatibility pointer-width accessor must expose only the construction witness",
)
legal_owner_match = re.search(
    r"val\s+([A-Za-z_]\w*)\s*=\s*depthIsLegal\s+generate\s+new\s+Area",
    stream,
    re.MULTILINE | re.DOTALL,
)
if legal_owner_match is None:
    fail(
        "LEGAL-ALTERNATIVE-MISSING",
        "the native FIFO algorithm must be confined to one legal generated alternative",
    )
legal_owner = legal_owner_match.group(1)
legal_owner_matches = re.findall(
    r"val\s+[A-Za-z_]\w*\s*=\s*depthIsLegal\s+generate\s+new\s+Area",
    stream,
    re.MULTILINE | re.DOTALL,
)
if len(legal_owner_matches) != 1:
    fail(
        "LEGAL-ALTERNATIVE-COUNT",
        f"expected one legal generated FIFO owner, found {len(legal_owner_matches)}",
    )
invalid_owner_match = re.search(
    r"val\s+([A-Za-z_]\w*)\s*=\s*\(!depthIsLegal\)\s+generate\s+new\s+Area",
    stream[legal_owner_match.end():],
    re.MULTILINE | re.DOTALL,
)
if invalid_owner_match is None:
    fail(
        "INVALID-ALTERNATIVE-MISSING",
        "illegal depth specializations must have one separate inert alternative",
    )
invalid_owner_start = legal_owner_match.end() + invalid_owner_match.start()
legal_region = stream[legal_owner_match.end():invalid_owner_start]
invalid_owner = invalid_owner_match.group(1)
invalid_block_match = re.search(
    rf"val\s+{re.escape(invalid_owner)}\s*=\s*\(!depthIsLegal\)\s+generate\s+new\s+Area\s*\{{(?P<body>.*?)^    \}}\s*\n\s*algorithm\.popToPushGray\.setName",
    stream[invalid_owner_start:],
    re.MULTILINE | re.DOTALL,
)
if invalid_block_match is None:
    fail(
        "INVALID-ALTERNATIVE-MISSING",
        "the inert depth alternative must have one inspectable generated body",
    )
invalid_region = invalid_block_match.group("body")

active_classes = re.findall(r"(?m)^[ \t]*class\s+StreamFifoCC\[", stream)
if len(active_classes) != 1:
    fail(
        "NATIVE-ALGORITHM-COUNT",
        f"expected one active native StreamFifoCC class, found {len(active_classes)}",
    )
streamfifocc_class_match = re.search(
    r"(?m)^[ \t]*class\s+StreamFifoCC\[",
    stream,
)
next_class_match = re.search(
    r"(?m)^[ \t]*class\s+StreamCCByToggle\b",
    stream[streamfifocc_class_match.end():],
)
streamfifocc_class_end = (
    streamfifocc_class_match.end() + next_class_match.start()
    if next_class_match is not None
    else len(stream)
)
streamfifocc_class = stream[
    streamfifocc_class_match.start():streamfifocc_class_end
]

builder_start = stream.find("private def buildNativeAlgorithm(): BuiltAlgorithm")
owner_selection_start = stream.find(
    "if (elabDepth.isConcrete) {\n    // Invoke the one body directly",
    builder_start,
)
if builder_start < 0 or owner_selection_start < 0:
    fail(
        "SHARED-ALGORITHM-BUILDER-MISSING",
        "the concrete and typed lanes must select one shared native algorithm builder",
    )
builder_region = stream[builder_start:owner_selection_start]

require(
    r"private\s+final\s+class\s+BuiltAlgorithm\s*\(",
    stream,
    "SHARED-ALGORITHM-CARRIER-MISSING",
    "the shared FIFO body must publish references through a plain Scala carrier",
)
reject(
    r"class\s+BuiltAlgorithm\b[^\n]*(?:extends|with)\s+Area\b",
    stream,
    "SHARED-ALGORITHM-CARRIER-IS-AREA",
    "the shared FIFO reference carrier must not introduce hardware hierarchy",
)
if len(re.findall(r"buildNativeAlgorithm\s*\(\s*\)", streamfifocc_class)) != 3:
    fail(
        "SHARED-ALGORITHM-BUILDER-COUNT",
        "expected one shared builder definition and exactly two owner-selection calls",
    )

for pattern, code, message in (
    (r"Mem\s*\(\s*dataType,\s*elabDepth\s*\)", "TYPED-RAM-DEPTH-MISSING", "the shared FIFO body must contain the typed native RAM geometry"),
    (r"val\s+finalPopCd\s*=\s*if\s*\(\s*elabDepth\.isConcrete\s*\).*?popClock\.withOptionalBufferedResetFrom\s*\(\s*withPopBufferedReset\s*\)\s*\(\s*pushClock\s*\).*?else.*?popClock\.withOptionalBufferedResetFromUncached\s*\(\s*withPopBufferedReset\s*\)\s*\(\s*pushClock\s*\)", "BUFFERED-RESET-OWNER-MISSING", "the concrete lane must retain the cached reset path while the generated lane creates its reset synchronizer under the legal owner"),
    (r"new\s+ClockingArea\s*\(\s*pushClock\s*\)\s+with\s+PushCCMembers", "PUSH-AREA-MISSING", "the shared FIFO body must contain the native push ClockingArea"),
    (r"new\s+ClockingArea\s*\(\s*finalPopCd\s*\)\s+with\s+PopCCMembers", "POP-AREA-MISSING", "the shared FIFO body must contain the native pop ClockingArea"),
    (r'"StreamFifoCCPopToPushBufferCC"', "BUFFERCC-DEFINITION-MISSING", "the shared FIFO body must retain the pop-to-push BufferCC definition"),
    (r'"StreamFifoCCPushToPopBufferCC"', "BUFFERCC-DEFINITION-MISSING", "the shared FIFO body must retain the push-to-pop BufferCC definition"),
):
    require(pattern, builder_region, code, message)

require(
    r"private\[lib\]\s+def\s+withBufferedResetFromUncached.*?resetCd\.config\.resetKind\s*==\s*BOOT.*?cd\.config\.resetKind\s*==\s*BOOT.*?ResetCtrl\.asyncAssertSyncDeassertCreateCd\s*\(\s*resetCd,\s*cd,\s*bufferDepth\s*\)",
    utils,
    "BUFFERED-RESET-UNCACHED-HELPER-MISSING",
    "the owner-local reset path must preserve the native BOOT and synchronized-reset semantics without the global cache",
)
require(
    r"setDefinitionName\s*\(\s*definitionName,\s*noMerge\s*=\s*false\s*\)",
    streamfifocc_class,
    "BUFFERCC-MULTI-INSTANCE-DEFINITION-POLICY-MISSING",
    "typed pointer synchronizers with unequal depth domains must receive distinct mergeable definitions",
)
require(
    r"val\s+payloadWidth\s*=\s*widthOfExpr\s*\(\s*io\.push\.payload\s*\).*?val\s+parameterizedMemoryRoles\s*=\s*!elabDepth\.isConcrete\s*\|\|\s*!payloadWidth\.isConcrete.*?val\s+writeData\s*=\s*if\s*\(\s*!parameterizedMemoryRoles\s*\)\s*null\s*else\s*Bits\s*\(\s*payloadWidth\s+bits\s*\)\s*\.setName\s*\(\s*\"stream_fifocc_write_data\"\s*,\s*weak\s*=\s*true\s*\)\s*\.dontSimplifyIt\s*\(\s*\).*?if\s*\(\s*parameterizedMemoryRoles\s*\)\s*writeData\s*:=\s*io\.push\.payload\.asBits.*?when\s*\(\s*io\.push\.fire\s*\)\s*\{\s*if\s*\(\s*!parameterizedMemoryRoles\s*\)\s*\{\s*ram\s*\(\s*pushPtr\.resized\s*\)\s*:=\s*io\.push\.payload\s*\}\s*else\s*\{.*?ram\.writeImpl\s*\(.*?writeData,.*?allowMixedWidth\s*=\s*false",
    builder_region,
    "AGGREGATE-WRITE-CARRIER-MISSING",
    "any symbolic RAM dimension must use one named packed write carrier while the fully concrete lane retains its native shorthand",
)

require(
    r"val\s+built\s*=\s*buildNativeAlgorithm\s*\(\s*\).*?val\s+ram\s*=\s*built\.ram.*?val\s+pushCC\s*=\s*built\.pushCC.*?val\s+popCC\s*=\s*built\.popCC",
    legal_region,
    "LEGAL-ALGORITHM-SELECTION-MISSING",
    "the legal generated alternative must select and expose the shared native body",
)
for unique_pattern, role in (
    (r"ram\.readSyncPort\s*\(\s*clockCrossing\s*=\s*true\s*\)", "dual-clock RAM read"),
    (r"io\.pop\s*<<\s*readArbitration\.translateWith\s*\(\s*readPort\.rsp\s*\)", "native read-response pipeline"),
    (r"pushToPopGray\s*:=\s*pushCC\.pushPtrGray", "push-to-pop Gray crossing"),
    (r"popToPushGray\s*:=\s*popCC\.ptrToPush", "pop-to-push Gray crossing"),
):
    whole_count = len(re.findall(unique_pattern, streamfifocc_class, re.MULTILINE | re.DOTALL))
    body_count = len(re.findall(unique_pattern, builder_region, re.MULTILINE | re.DOTALL))
    if whole_count != 1 or body_count != 1:
        fail(
            "NATIVE-ALGORITHM-DUPLICATED",
            f"{role} must occur exactly once inside the shared builder; found body={body_count}, class={whole_count}",
        )

# Follow the identity-alias chain instead of freezing implementation-local
# variable names. Each public inspection member must resolve to the matching
# member of the single legal generated owner.
for member, member_type in (
    ("ram", r"Mem\s*\[\s*T\s*\]"),
    ("pushCC", r"PushCCArea"),
    ("popCC", r"PopCCArea"),
):
    public_alias = re.search(
        rf"val\s+{member}\s*:\s*{member_type}\s*=\s*([A-Za-z_]\w*)",
        stream,
        re.MULTILINE | re.DOTALL,
    )
    if public_alias is None:
        fail(
            "LEGACY-INSPECTION-MEMBER-MISSING",
            f"native inspection member {member} must remain a typed identity alias",
        )
    alias_source = public_alias.group(1)
    require(
        rf"^[ \t]*{re.escape(alias_source)}\s*=\s*{re.escape(legal_owner)}\.{member}\s*$",
        stream,
        "LEGACY-INSPECTION-MEMBER-MISSING",
        f"native inspection member {member} must resolve to the one legal FIFO owner",
    )
    require(
        rf"^[ \t]*{re.escape(alias_source)}\s*=\s*[A-Za-z_]\w*\.{member}\s*$",
        stream[:legal_owner_match.start()],
        "LEGACY-INSPECTION-MEMBER-MISSING",
        f"native inspection member {member} must also resolve to the concrete FIFO leg",
    )
# StreamFifoCC may retain named typed-width result carriers, but the shared
# Utils helpers are the sole Gray-code algorithms. No local shift topology or
# copied prefix loop is permitted.
reject(
    r"\|?>>|for\s*\(\s*shift\b|while\s*\(\s*shift\b|Seq\s*\(\s*1\s*,\s*2\s*,\s*4",
    streamfifocc_class,
    "LOCAL-GRAY-CODEC",
    "StreamFifoCC must not contain a FIFO-local Gray shift or prefix algorithm",
)
gray_helpers = re.findall(
    r"private\s+def\s+([A-Za-z_]\w*(?:ToGray|FromGray))\b",
    streamfifocc_class,
)
if sorted(gray_helpers) != ["retainedFromGray", "retainedToGray"]:
    fail(
        "LOCAL-GRAY-HELPER-SURFACE",
        f"expected only the two reviewed shared-helper carriers, found {sorted(gray_helpers)}",
    )
to_gray_start = streamfifocc_class.find("private def retainedToGray")
from_gray_start = streamfifocc_class.find("private def retainedFromGray")
pointer_zero_start = streamfifocc_class.find("private def pointerZeroBits")
if not (0 <= to_gray_start < from_gray_start < pointer_zero_start):
    fail(
        "SHARED-GRAY-DELEGATION-MISSING",
        "reviewed StreamFifoCC Gray carrier boundaries are missing or reordered",
    )
to_gray_carrier = streamfifocc_class[to_gray_start:from_gray_start]
from_gray_carrier = streamfifocc_class[from_gray_start:pointer_zero_start]
require(
    r"if\s*\(elabDepth\.isConcrete\)\s*toGray\(value\).*?Bits\(elabPtrWidth\s+bits\).*?result\s*:=\s*toGray\(value\)",
    to_gray_carrier,
    "SHARED-TO-GRAY-DELEGATION-MISSING",
    "the typed-width toGray carrier must delegate both paths to the shared helper",
)
require(
    r"if\s*\(elabDepth\.isConcrete\)\s*fromGray\(value\).*?UInt\(elabPtrWidth\s+bits\).*?result\s*:=\s*fromGray\(value\)",
    from_gray_carrier,
    "SHARED-FROM-GRAY-DELEGATION-MISSING",
    "the typed-width fromGray carrier must delegate both paths to the shared helper",
)

# The complete typed domain supplies the legality predicate. Invalid public
# specializations are isolated in a safe generated alternative.
require(
    r"depth\.requireAuthoritativeIntegerDomain\s*\(.*?SPINAL-STREAM-FIFO-CC-DEPTH-EXACT-DOMAIN-REQUIRED",
    stream,
    "EXACT-DOMAIN-GUARD-MISSING",
    "typed depth must require authoritative exact-domain evidence",
)
require(
    r"val\s+legal\s*=\s*\(depth\s*>=\s*2\)\s*&&\s*depth\.isPow2",
    stream,
    "LEGALITY-PREDICATE-MISSING",
    "typed depth must retain the complete minimum-and-power-of-two predicate",
)
for code in (
    "SPINAL-STREAM-FIFO-CC-DEPTH-DOMAIN-INVALID",
    "SPINAL-STREAM-FIFO-CC-DEPTH-DEFAULT-INVALID",
    "SPINAL-STREAM-FIFO-CC-DEPTH-NO-LEGAL-VALUE",
):
    require(
        re.escape(code),
        stream,
        "STABLE-DEPTH-DIAGNOSTIC-MISSING",
        f"typed depth validation is missing stable diagnostic {code}",
    )
require(
    r"if\s*\(depth\.minimum\s*<\s*2\s*\|\|\s*depth\.maximum\s*>\s*BigInt\(Int\.MaxValue\)\).*?SPINAL-STREAM-FIFO-CC-DEPTH-DOMAIN-INVALID",
    stream,
    "DEFENSIVE-INT-DOMAIN-GUARD-MISSING",
    "typed depth must retain the defensive Int-sized domain guard",
)
require(
    r"ElabFormalComponent\.parameter\s*\(\s*actual\s*=\s*depth,\s*name\s*=\s*\"DEPTH\"",
    stream,
    "DEPTH-FORMAL-MISSING",
    "symbolic construction must create the exact native DEPTH child formal",
)
require(
    r"private\s+def\s+typedDepthFormalMaximum\s*\(\s*depth:\s*ElabInt\s*\)\s*:\s*BigInt\s*=\s*\{.*?maximum\s*==\s*2.*?BigInt\s*\(\s*1\s*\)\s*<<\s*maximum\.bitLength.*?ElaborationExactDomain\.MaximumDomainSize\s*\+\s*1",
    stream,
    "DEPTH-FORMAL-CANONICAL-DOMAIN-MISSING",
    "typed parent definitions must canonicalize only the invalid tail below the next legal power of two and retain the exact-domain cap",
)
require(
    r"ElabFormalComponent\.parameter\s*\(\s*actual\s*=\s*depth,\s*name\s*=\s*\"DEPTH\",\s*minimum\s*=\s*BigInt\s*\(\s*2\s*\),\s*maximum\s*=\s*typedDepthFormalMaximum\s*\(\s*depth\s*\)",
    stream,
    "DEPTH-FORMAL-CANONICAL-DOMAIN-MISSING",
    "typed StreamFifoCC construction must use the canonical legal-depth bucket schema",
)
require(
    r"popToPushGray\.addAttribute\s*\(\s*\"spinal_stream_fifocc_legal_depth_ceiling\"\s*,\s*elabDepth\.maximum\.toInt\s*\)",
    builder_region,
    "DEPTH-FORMAL-GEOMETRY-IDENTITY-MISSING",
    "typed FIFO traces must retain their exact projected legal-depth ceiling without changing synthesis behavior",
)
require(
    r"val\s+inert\s*=\s*\(\s*io\.push\.valid\s*&\s*False\s*\).*?\.setName\s*\(\s*\"stream_fifocc_invalid_inert\"\s*\).*?\.dontSimplifyIt\s*\(\s*\)",
    invalid_region,
    "INVALID-ALTERNATIVE-SENSITIVITY-MISSING",
    "the inert alternative must retain a masked input carrier for Verilog-2001 combinational sensitivity",
)
require(
    r"val\s+popPayloadWidth\s*=\s*widthOfExpr\s*\(\s*io\.pop\.payload\s*\).*?val\s+retainedPayloadZero\s*=\s*if\s*\(\s*popPayloadWidth\.isConcrete\s*\)\s*null\s*else\s*Bits\s*\(\s*popPayloadWidth\s+bits\s*\)\s*\.setName\s*\(\s*\"stream_fifocc_invalid_payload_zero\"\s*,\s*weak\s*=\s*true\s*\)\s*\.dontSimplifyIt\s*\(\s*\).*?if\s*\(\s*retainedPayloadZero\s*!=\s*null\s*\)\s*retainedPayloadZero\s*:=\s*0.*?io\.push\.ready\s*:=\s*inert.*?io\.pushOccupancy\s*:=\s*0.*?io\.pop\.valid\s*:=\s*inert.*?if\s*\(\s*retainedPayloadZero\s*==\s*null\s*\)\s*io\.pop\.payload\.assignFromBits\s*\(\s*B\s*\(\s*0\s*\)\.resize\s*\(\s*popPayloadWidth\s*\)\s*\)\s*else\s*io\.pop\.payload\.assignFromBits\s*\(\s*retainedPayloadZero\s*\).*?io\.popOccupancy\s*:=\s*0.*?when\s*\(\s*inert\s*\)\s*\{\s*io\.pushOccupancy\s*:=\s*0\s*if\s*\(\s*retainedPayloadZero\s*==\s*null\s*\)\s*io\.pop\.payload\.assignFromBits\s*\(\s*B\s*\(\s*0\s*\)\.resize\s*\(\s*popPayloadWidth\s*\)\s*\)\s*else\s*io\.pop\.payload\.assignFromBits\s*\(\s*retainedPayloadZero\s*\)\s*io\.popOccupancy\s*:=\s*0",
    invalid_region,
    "INVALID-ALTERNATIVE-MISSING",
    "illegal depth specializations must retain a width-generic zero carrier and drive the complete public FIFO interface inert",
)
reject(
    r"io\.pop\.payload\.getZero",
    invalid_region,
    "INVALID-ALTERNATIVE-PAYLOAD-NONGENERIC",
    "the inert payload assignment must support aggregate Data values",
)
reject(
    r"\b(?:pushToPopGray|popToPushGray)\b",
    invalid_region,
    "INVALID-ALTERNATIVE-CROSS-SCOPE-COUPLING",
    "the inert sibling must not reference Gray carriers owned by the legal FIFO alternative",
)
require(
    r"def\s+formalAsserts\s*\(\s*gclk:\s*ClockDomain\s*\)\s*:\s*Composite\s*\[\s*StreamFifoCC\s*\[\s*T\s*\]\s*\]",
    stream,
    "FORMAL-RETURN-TYPE-MISSING",
    "native formalAsserts must retain its StreamFifoCC Composite return type",
)

# Every width-sensitive part of the authoritative algorithm consumes the typed
# depth. These checks guard the most dangerous default-witness leak points.
for pattern, code, message in (
    (r"val\s+pushOccupancy\s*=.*?log2Up\(elabDepth\s*\+\s*1\)\s+bits", "TYPED-OCCUPANCY-MISSING", "push occupancy width must retain typed depth"),
    (r"val\s+popOccupancy\s*=.*?log2Up\(elabDepth\s*\+\s*1\)\s+bits", "TYPED-OCCUPANCY-MISSING", "pop occupancy width must retain typed depth"),
    (r"elabPtrWidth\s*=\s*log2Up\(elabDepth\)\s*\+\s*1", "TYPED-POINTER-WIDTH-MISSING", "pointer width must retain typed depth"),
    (r"Mem\s*\(\s*dataType,\s*elabDepth\s*\)", "TYPED-RAM-DEPTH-MISSING", "the native RAM must consume typed depth"),
    (r"UInt\s*\(\s*elabDepth\.addressWidth\s+bits\s*\)", "TYPED-ADDRESS-WIDTH-MISSING", "RAM addresses must consume typed depth"),
    (r"elabDepth\s*\+\s*\(elabDepth\s*/\s*2\)", "TYPED-FULL-MASK-MISSING", "the symbolic Gray full comparison must not freeze witness-sized slices"),
):
    require(pattern, stream, code, message)

require(
    r"private\s+val\s+TypedPointerWidthMinimum\s*=\s*BigInt\s*\(\s*2\s*\).*?private\s+val\s+TypedPointerWidthMaximum\s*=\s*BigInt\s*\(\s*log2Up\s*\(\s*Int\.MaxValue\s*\)\s*\)",
    stream,
    "BUFFERCC-CANONICAL-WIDTH-DOMAIN-MISSING",
    "mergeable typed BufferCC children must share the complete Int-depth pointer-width capability",
)
require(
    r"ElabFormalComponent\.parameter\s*\(\s*actual\s*=\s*elabPtrWidth,\s*name\s*=\s*\"WIDTH\",\s*minimum\s*=\s*StreamFifoCC\.TypedPointerWidthMinimum,\s*maximum\s*=\s*StreamFifoCC\.TypedPointerWidthMaximum",
    stream,
    "BUFFERCC-WIDTH-FORMAL-MISSING",
    "each kept BufferCC child must bind the symbolic pointer width through the canonical mergeable schema",
)
if stream.count("crossClockMaxDelay(1, useTargetClock = false)") < 1:
    fail(
        "CDC-ATTRIBUTE-MISSING",
        "the native Gray synchronizers must retain crossClockMaxDelay metadata",
    )
reject(
    r"withPopBufferedReset\s*:\s*ElabBool|(?:pushClock|popClock)\s*:\s*Elab",
    stream,
    "STATIC-CDC-POLICY-WIDENED",
    "clock domains and reset topology must remain static construction policy",
)
reject(
    r"NativeIntShadow|MorphStreamFifoCC|ParameterizedStreamFifoCC|sourceFile.*StreamFifoCC|componentName.*StreamFifoCC",
    stream,
    "FORBIDDEN-RECONSTRUCTION",
    "StreamFifoCC must not use a wrapper, shadow path or component/source recognizer",
)

# Shared Gray helpers retain typed packed width without replacing their
# established concrete implementation.
require(
    r"object\s+toGray.*?val\s+width\s*=\s*widthOfExpr\(uint\).*?if\s*\(width\.isConcrete\)\s*\{\s*B\s*\(\s*\(uint\s*>>\s*U\(1\)\)\s*\^\s*uint\s*\).*?val\s+shifted\s*=\s*UInt\(width\s+bits\).*?shifted\s*:=\s*uint\s*\|>>\s*1.*?val\s+result\s*=\s*Bits\(width\s+bits\).*?result\s*:=\s*shifted\.asBits\s*\^\s*uint\.asBits",
    utils,
    "TO-GRAY-WIDTH-RETENTION-MISSING",
    "toGray must retain its concrete algorithm and materialize typed shift/XOR carriers",
)
require(
    r"object\s+fromGray.*?val\s+width\s*=\s*widthOfExpr\(gray\).*?if\s*\(width\.isConcrete\).*?List\.fill\(widthOf\(gray\)\).*?requireAuthoritativeIntegerDomain.*?val\s+maximumWidth\s*=\s*width\.maximum.*?var\s+shift\s*=\s*BigInt\(1\).*?while\s*\(shift\s*<\s*maximumWidth\).*?val\s+shiftAmount\s*=\s*shift\.toInt.*?shift\s*=\s*shift\s*<<\s*1",
    utils,
    "FROM-GRAY-TYPED-PATH-MISSING",
    "fromGray must retain the concrete algorithm and derive every typed prefix stage from the authoritative maximum",
)
require(
    r"var\s+decoded\s*=\s*UInt\(width\s+bits\).*?val\s+shifted\s*=\s*UInt\(width\s+bits\).*?val\s+next\s*=\s*UInt\(width\s+bits\)",
    utils,
    "FROM-GRAY-WIDTH-RETENTION-MISSING",
    "each typed Gray decode stage must retain the input packed width",
)

# BufferCC remains the native synchronizer; only mechanical width propagation
# through its boundary is admitted.
require(
    r"val\s+typedInput\s*=\s*!widthOfExpr\(input\)\.isConcrete.*?ParameterizedWidth\.cloneOf\(c\.io\.dataOut\)",
    crossclock,
    "BUFFERCC-RETURN-WIDTH-MISSING",
    "BufferCC must preserve a symbolic returned clone",
)
require(
    r"typedData\s*=\s*!widthOfExpr\(dataType\)\.isConcrete.*?ParameterizedWidth\.cloneOf\(dataType\).*?ParameterizedWidth\.HardType\(dataType\)",
    crossclock,
    "BUFFERCC-INTERNAL-WIDTH-MISSING",
    "BufferCC ports and synchronizer registers must preserve typed input width",
)
require(
    r"Vector\.fill\(finalBufferDepth\).*?val\s+registerInit\s*=\s*init.*?if\s*\(registerInit\s*!=\s*null\)\s*register\.init\(registerInit\)",
    crossclock,
    "BUFFERCC-INIT-EVALUATION-MISSING",
    "each retained BufferCC stage must evaluate its by-name initializer exactly once",
)
require(
    r"class\s+PhaseBufferCCBB\s+extends\s+PhaseNetlist.*?val\s+width\s*=\s*widthOfExpr\s*\(\s*c\.io\.dataIn\s*\).*?if\s*\(\s*!width\.isConcrete\s*\).*?SPINAL-BUFFER-CC-BLACKBOX-TYPED-WIDTH-UNSUPPORTED.*?c\.io\.dataIn\.getBitsWidth",
    crossclock,
    "BUFFERCC-BLACKBOX-TYPED-WIDTH-GUARD-MISSING",
    "the Int-only BufferCC blackbox phase must fail closed before freezing a retained width",
)
require(
    r"object\s+SpinalVerilogBoot\s*\{.*?catch\s*\{.*?case\s+e:\s*ParameterizedVerilogException\s*=>\s*throw\s+e.*?case\s+e:\s*Throwable",
    phase,
    "PARAMETERIZED-DIAGNOSTIC-RETRY-BYPASS-MISSING",
    "SpinalVerilog must preserve deterministic typed diagnostics instead of masking them through its Scala-trace retry",
)

# The memory extension is limited to an authenticated native crossing with its
# established independent-address/dontCare collision policy.
require(
    r"clocksDiffer.*?\(clocksDiffer\s*&&\s*!read\.hasTag\(crossClockDomain\)\)",
    memory,
    "CROSS-CLOCK-MEMORY-AUTHORITY-MISSING",
    "distinct memory clocks must require the native clockCrossing tag",
)
require(
    r"clocksDiffer\s*&&\s*!independentDontCare.*?SPINAL-PARAMETERIZED-VERILOG-MEMORY-CROSS-CLOCK-COLLISION-POLICY-UNSUPPORTED",
    memory,
    "CROSS-CLOCK-COLLISION-GUARD-MISSING",
    "cross-clock memory must retain independent-address dontCare collision policy",
)
require(
    r"always @\(posedge \$\{plan\.readClock\}\).*?always @\(posedge \$\{plan\.writeClock\}\)",
    memory,
    "DUAL-CLOCK-EMISSION-MISSING",
    "parameterized memory emission must keep separate read and write processes",
)

# Kept hierarchy attributes must survive insertion of child parameter bindings.
require(
    r"attributePrefix.*?plainStartPattern.*?parameterizedStartPattern.*?group\(2\).*?\$indent\$attributes\$\{instance\.definitionName\}\s+#\(",
    hierarchy,
    "HIERARCHY-ATTRIBUTE-PRESERVATION-MISSING",
    "parameter insertion must recognize and preserve leading native Verilog attributes",
)

# The external proof remains application-shaped and uses the ordinary native
# package surface.
require(
    r"class\s+NativeStreamFifoCCParameterizedTests\s+extends\s+AnyFunSuite",
    tests,
    "FOCUSED-SUITE-MISSING",
    "the focused parameterized StreamFifoCC suite is missing",
)
require(
    r"class\s+NativeStreamFifoCCParameterizedHarness\s*\(\s*depth:\s*ElabInt.*?StreamFifoCC\s*\(\s*HardType\(Bits\(8\s+bits\)\),\s*depth,\s*pushCd,\s*popCd",
    tests,
    "NATIVE-APPLICATION-CALL-MISSING",
    "the focused fixture must call the ordinary native StreamFifoCC surface",
)
require(
    r"test\s*\(\s*\"exact legal domain uses the public typed companion without an invalid formal owner\"\s*\)",
    tests,
    "PUBLIC-TYPED-COMPANION-COVERAGE-MISSING",
    "focused proof must retain exact-domain public companion construction",
)
require(
    r"test\s*\(\s*\"typed BufferCC evaluates by-name init exactly once per retained stage\"\s*\).*?evaluations\s*==\s*stageCount",
    tests,
    "BUFFERCC-INIT-COVERAGE-MISSING",
    "focused proof must retain the typed BufferCC by-name initializer contract",
)
phase_buffer_test_match = re.search(
    r"test\s*\(\s*\"BufferCC blackbox phase rejects retained width before witness freezing\"\s*\)(?P<body>.*?)(?=\n\s*test\s*\()",
    tests,
    re.MULTILINE | re.DOTALL,
)
if phase_buffer_test_match is None:
    fail(
        "BUFFERCC-BLACKBOX-TYPED-WIDTH-COVERAGE-MISSING",
        "the retained-width BufferCC blackbox regression must remain inspectable",
    )
phase_buffer_test = phase_buffer_test_match.group("body")
require(
    r"typedCode\s*=.*?SPINAL-BUFFER-CC-BLACKBOX-TYPED-WIDTH-UNSUPPORTED.*?MorphVerilog\.tryGenerate.*?morphFailure\.cause\.collect.*?ParameterizedVerilogException.*?morphCause\.exists\s*\(\s*_\.code\s*==\s*typedCode\s*\)",
    phase_buffer_test,
    "BUFFERCC-BLACKBOX-MORPH-DIAGNOSTIC-COVERAGE-MISSING",
    "focused proof must preserve the retained-width diagnostic and typed cause through MorphVerilog",
)
require(
    r"intercept\s*\[\s*ParameterizedVerilogException\s*\].*?rawFailure\.code\s*==\s*typedCode",
    phase_buffer_test,
    "BUFFERCC-BLACKBOX-RAW-DIAGNOSTIC-COVERAGE-MISSING",
    "focused proof must expose the same retained-width code through default raw SpinalVerilog generation",
)
require(
    r"retryPhaseRuns\s*=\s*0.*?IllegalStateException\s*\(\s*\"ordinary retry control\"\s*\).*?retryPhaseRuns\s*==\s*2",
    phase_buffer_test,
    "SPINAL-ORDINARY-RETRY-CONTROL-MISSING",
    "focused proof must show that non-parameterized failures retain the ordinary Scala-trace retry",
)
require(
    r"BufferCCBlackBox.*?\.WIDTH\(4\)",
    phase_buffer_test,
    "BUFFERCC-BLACKBOX-CONCRETE-COVERAGE-MISSING",
    "focused proof must preserve concrete-width BufferCC blackbox replacement",
)
reject(
    r"debugComponents",
    phase_buffer_test,
    "BUFFERCC-BLACKBOX-DEBUG-WORKAROUND",
    "the retained-width regression must exercise default retry-enabled generation",
)
require(
    r"test\s*\(\s*\"aggregate FIFOs sharing clocks retain independent legal owners and reset buffers\"\s*\).*?DEPTH_A.*?DEPTH_B.*?finalPopCd\.reset\.getComponent\(\)\s+eq\s+top\.fifoA.*?finalPopCd\.reset\.getComponent\(\)\s+eq\s+top\.fifoB.*?resetA\.parent\s+eq\s+top\.fifoA.*?resetB\.parent\s+eq\s+top\.fifoB",
    tests,
    "MULTI-FIFO-OWNER-COVERAGE-MISSING",
    "focused proof must retain two unequal typed FIFO domains with independently owned buffered resets",
)
aggregate_test_match = re.search(
    r"test\s*\(\s*\"aggregate FIFOs sharing clocks retain independent legal owners and reset buffers\"\s*\)(?P<body>.*?)(?=\n\s*test\s*\()",
    tests,
    re.MULTILINE | re.DOTALL,
)
if aggregate_test_match is None:
    fail(
        "BUFFERCC-CANONICAL-WIDTH-COVERAGE-MISSING",
        "the aggregate multi-FIFO regression must remain inspectable",
    )
aggregate_test = aggregate_test_match.group("body")
if len(re.findall(r"default\s*=\s*BigInt\s*\(\s*4\s*\)", aggregate_test)) != 2:
    fail(
        "BUFFERCC-CANONICAL-WIDTH-COVERAGE-MISSING",
        "both unequal-domain FIFOs must share the same default pointer-width witness",
    )
for maximum in (5, 16):
    require(
        rf"max\s*=\s*BigInt\s*\(\s*{maximum}\s*\)",
        aggregate_test,
        "BUFFERCC-CANONICAL-WIDTH-COVERAGE-MISSING",
        f"the aggregate regression must retain DEPTH maximum {maximum}",
    )
require(
    r"StreamFifoCCPopToPushBufferCC.*?StreamFifoCCPushToPopBufferCC",
    aggregate_test,
    "BUFFERCC-CANONICAL-WIDTH-COVERAGE-MISSING",
    "the equal-witness regression must observe both canonical merged pointer synchronizers",
)
require(
    r"test\s*\(\s*\"same-topology unnamed FIFO parents canonicalize by legal-depth bucket\"\s*\).*?defaultDepth\s*=\s*BigInt\s*\(\s*8\s*\).*?maximumA\s*=\s*BigInt\s*\(\s*8\s*\).*?maximumB\s*=\s*BigInt\s*\(\s*15\s*\).*?Vector\s*\(\s*\"StreamFifoCC\"\s*\).*?spinal_stream_fifocc_legal_depth_ceiling=8.*?defaultDepth\s*=\s*BigInt\s*\(\s*16\s*\).*?maximumA\s*=\s*BigInt\s*\(\s*16\s*\).*?maximumB\s*=\s*BigInt\s*\(\s*32\s*\).*?Set\s*\(\s*\"StreamFifoCC\"\s*,\s*\"StreamFifoCC_1\"\s*\).*?spinal_stream_fifocc_legal_depth_ceiling=16.*?spinal_stream_fifocc_legal_depth_ceiling=32",
    tests,
    "PARENT-FORMAL-CANONICALIZATION-COVERAGE-MISSING",
    "focused proof must merge equal legal-depth buckets and separate different legal geometries even when their witness traces match",
)
require(
    r"test\s*\(\s*\"typed owner-local reset path preserves native BOOT semantics\"\s*\).*?resetKind\s*=\s*BOOT.*?finalPopCd\.reset\s*==\s*null.*?buffers\.size\s*==\s*2",
    tests,
    "BUFFERED-RESET-BOOT-COVERAGE-MISSING",
    "focused proof must retain the native BOOT reset policy on the uncached generated path",
)
reject(
    r"(?:MorphStreamFifoCC|ParameterizedStreamFifoCC)\s*\(|NativeIntShadow\s*[.(]|\.witness\b",
    tests,
    "FORBIDDEN-PROOF-SURFACE",
    "focused tests must not use a wrapper, shadow or witness extraction",
)
require(
    r"LegalDepths\s*=\s*Vector\s*\(\s*2\s*,\s*4\s*,\s*8\s*,\s*16\s*\)",
    tests,
    "LEGAL-DEPTH-COVERAGE-MISSING",
    "focused proof must retain the exact legal-depth matrix 2, 4, 8 and 16",
)
require(
    r"countOccurrences\s*\(\s*bufferRtl,\s*\"<=\{WIDTH\{1'b0\}\};\"\s*\)\s*==\s*2.*?assert\s*\(\s*!bufferRtl\.contains\s*\(\s*\"4'b0000\"\s*\)",
    tests,
    "BUFFERCC-ZERO-INIT-COVERAGE-MISSING",
    "focused proof must reject witness-sized BufferCC reset literals",
)
require(
    r"def\s+assertGrayShiftGeometry\s*\(.*?Vector\s*\(\s*1\s*,\s*2\s*,\s*4\s*\).*?Vector\s*\(\s*8\s*,\s*16\s*\).*?!shifts\.exists",
    tests,
    "FROM-GRAY-POINTER-STAGE-COVERAGE-MISSING",
    "focused proof must require shifts 1/2/4 and reject stages above the five-bit pointer maximum",
)
for code in (
    "SPINAL-STREAM-FIFO-CC-DEPTH-EXACT-DOMAIN-REQUIRED",
    "SPINAL-STREAM-FIFO-CC-DEPTH-DOMAIN-INVALID",
    "SPINAL-STREAM-FIFO-CC-DEPTH-DEFAULT-INVALID",
):
    require(
        re.escape(code),
        tests,
        "NEGATIVE-DOMAIN-COVERAGE-MISSING",
        f"focused proof must assert stable negative-domain diagnostic {code}",
    )
require(
    r"Int\.MaxValue.*?(?:SPINAL-STREAM-FIFO-CC-DEPTH-DOMAIN-INVALID|SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUT-OF-RANGE|SPINAL-ELAB-INT-DOMAIN-INVALID|MORPH-FRONTEND-SPINAL-WIDTH-DOMAIN-TOO-LARGE)",
    tests,
    "OVERSIZED-DOMAIN-COVERAGE-MISSING",
    "focused proof must retain stable public-ingress rejection of an oversized domain",
)

# Retained-width zero lowering may use an emitted name and witness only after
# the graph proves every matching edge is a direct, full-target invariant zero.
require(
    r"private\s+def\s+isInvariantZero\s*\(\s*expression:\s*Expression\s*\).*?case\s+literal:\s*BitVectorLiteral\s*=>\s*!literal\.hasPoison\(\)\s*&&\s*literal\.getValue\(\)\s*==\s*0.*?case\s+resize:\s*Resize\s*=>\s*isInvariantZero\s*\(\s*resize\.input\s*\).*?case\s+cast:\s*CastBitVectorToBitVector\s*=>\s*isInvariantZero\s*\(\s*cast\.input\s*\)",
    fallback,
    "RETAINED-ZERO-CARDINALITY-AUTHORITY-MISSING",
    "retained-zero authorization must remain limited to poison-free literal zero through invariant resize/cast nodes",
)
require(
    r"private\s+def\s+isAuthorizedZeroAssignment\s*\(\s*statement:\s*AssignmentStatement\s*,\s*target:\s*BitVector\s*\).*?\(\s*statement\.target\s+eq\s+target\s*\)\s*&&\s*\(\s*statement\.finalTarget\s+eq\s+target\s*\)\s*&&.*?case\s+sourceWidth:\s*WidthProvider\s*=>\s*sourceWidth\.getWidth\s*==\s*target\.getBitsWidth\s*&&\s*isInvariantZero\s*\(\s*statement\.source\s*\)",
    fallback,
    "RETAINED-ZERO-CARDINALITY-AUTHORITY-MISSING",
    "retained-zero authorization must require exact target identity and an exact-width invariant-zero source",
)
require(
    r"final\s+case\s+class\s+RetainedZeroInitializer\s*\(\s*target:\s*BitVector\s*,.*?var\s+authorizedEdges\s*=\s*0.*?component\.dslBody\.walkLeafStatements\s*\{.*?isAuthorizedZeroAssignment\s*\(\s*statement\s*,\s*initializer\.target\s*\)\s*=>\s*authorizedEdges\s*\+=\s*1.*?if\s*\(\s*authorizedEdges\s*==\s*0\s*\|\|\s*exactEdges\s*!=\s*authorizedEdges\s*\)",
    fallback,
    "RETAINED-ZERO-CARDINALITY-AUTHORITY-MISSING",
    "retained-zero rewriting must carry exact target identity and require emitted/authorized edge cardinality equality",
)
require(
    r"class\s+NativeRetainedZeroCardinalityHarness\s*\(\s*width:\s*HdlInt\s*\).*?Reg\s*\(\s*UInt\s*\(\s*elabWidth\s+bits\s*\)\s*\)\s*init\s*\(\s*0\s*\).*?when\s*\(\s*io\.clear\s*\)\s*\{\s*state\s*:=\s*0",
    tests,
    "RETAINED-ZERO-CARDINALITY-FIXTURE-MISSING",
    "focused proof must retain a symbolic register with both init and ordinary clear-to-zero assignments",
)
require(
    r"test\s*\(\s*\"retained zero rewriting preserves exact graph-to-emission cardinality\"\s*\).*?countOccurrences\s*\(\s*module\s*,\s*s\"\$stateName<=\{WIDTH\{1'b0\}\};\"\s*\)\s*==\s*2.*?!module\.contains\s*\(\s*s\"\$stateName<=8'h00;\"\s*\)",
    tests,
    "RETAINED-ZERO-CARDINALITY-COVERAGE-MISSING",
    "focused proof must require one emitted symbolic zero for every authorized graph edge and reject witness literals",
)

# Asynchronous ratios and clock interruptions remain stress coverage. Accepted
# simultaneous traffic has a separate deterministic shared-edge witness for
# every legal depth and both static reset topologies.
require(
    r"class\s+NativeStreamFifoCCCdcProofTests\s+extends\s+AnyFunSuite",
    cdc_tests,
    "CDC-PROOF-SUITE-MISSING",
    "the dedicated StreamFifoCC CDC proof suite is missing",
)
require(
    r"private\s+val\s+Depths\s*=\s*Vector\s*\(\s*2\s*,\s*4\s*,\s*8\s*,\s*16\s*\).*?private\s+val\s+ResetModes\s*=\s*Vector\s*\(\s*false\s*,\s*true\s*\).*?ClockSchedule\s*\(\s*\"push_faster\"\s*,\s*pushHalfPeriod\s*=\s*3\s*,\s*popHalfPeriod\s*=\s*7\s*\).*?ClockSchedule\s*\(\s*\"pop_faster\"\s*,\s*pushHalfPeriod\s*=\s*7\s*,\s*popHalfPeriod\s*=\s*3\s*\)",
    cdc_tests,
    "CDC-STRESS-MATRIX-MISSING",
    "CDC proof must retain both asynchronous ratios across depths 2, 4, 8 and 16 and both reset modes",
)
proof_test_match = re.search(
    r"test\s*\(\s*\"typed CDC specializations pass lint synthesis asynchronous stress and a simultaneous witness\"\s*\)(?P<body>.*?)(?=\n\s*private\s+def\s+generate)",
    cdc_tests,
    re.MULTILINE | re.DOTALL,
)
if proof_test_match is None:
    fail(
        "CDC-PROOF-COVERAGE-MISSING",
        "the tool-backed CDC proof test must remain inspectable",
    )
proof_test = proof_test_match.group("body")
require(
    r"ResetModes\.foreach\s*\{\s*buffered\s*=>.*?Depths\.foreach\s*\{\s*depth\s*=>.*?Schedules\.foreach\s*\{\s*schedule\s*=>\s*simulate\s*\(\s*directory\s*,\s*rtl\s*,\s*depth\s*,\s*buffered\s*,\s*schedule\s*\)\s*\}.*?simulateSimultaneousTransfer\s*\(\s*directory\s*,\s*rtl\s*,\s*depth\s*,\s*buffered\s*\)",
    proof_test,
    "CDC-SIMULTANEOUS-MATRIX-MISSING",
    "each depth/reset specialization must run both asynchronous stress ratios and one deterministic simultaneous-transfer witness",
)

async_start = cdc_tests.find("private def simulationTestbench(")
simultaneous_start = cdc_tests.find("private def simultaneousTransferTestbench(")
invalid_start = cdc_tests.find("private def invalidDepthTestbench(")
if not (0 <= async_start < simultaneous_start < invalid_start):
    fail(
        "CDC-TESTBENCH-SURFACE-MISSING",
        "the asynchronous, simultaneous-transfer and invalid-depth testbench builders must remain separate and inspectable",
    )
async_testbench = cdc_tests[async_start:simultaneous_start]
simultaneous_testbench = cdc_tests[simultaneous_start:invalid_start]

require(
    r"#270\s+popRun\s*=\s*1'b0.*?#91\s+popRun\s*=\s*1'b1.*?#233\s+pushRun\s*=\s*1'b0.*?#79\s+pushRun\s*=\s*1'b1",
    async_testbench,
    "CDC-CLOCK-INTERRUPTION-COVERAGE-MISSING",
    "asynchronous stress must retain independent push/pop clock interruptions",
)
require(
    r"io_pushValid\s*&&\s*!io_pushReady.*?sawFull\s*=\s*1.*?io_popPayload\s*!==\s*received\[7:0\].*?sent\s*!=\s*TOTAL\s*\|\|\s*!sawFull\s*\|\|\s*!sawPushPause\s*\|\|\s*!sawPopPause.*?io_popValid\s*!==\s*1'b0.*?io_pushOccupancy\s*!==\s*5'b0.*?io_popOccupancy\s*!==\s*5'b0",
    async_testbench,
    "CDC-ASYNC-STRESS-COVERAGE-MISSING",
    "asynchronous stress must retain full, ordered payload, clock-pause and settled-drain checks",
)
reject(
    r"\|\|\s*!sawSimultaneous\s*\)\s*begin",
    async_testbench,
    "CDC-ASYNC-ACCIDENTAL-COINCIDENCE-REQUIRED",
    "asynchronous ratio stress must not depend on accidental same-timestamp clock edges",
)

if len(re.findall(r"\breg\s+io_clock\b", simultaneous_testbench)) != 1:
    fail(
        "CDC-COINCIDENT-SHARED-CLOCK-COVERAGE-MISSING",
        "the deterministic simultaneous-transfer testbench must declare exactly one shared clock",
    )
reject(
    r"\breg\s+io_(?:push|pop)Clock\b",
    simultaneous_testbench,
    "CDC-COINCIDENT-SHARED-CLOCK-COVERAGE-MISSING",
    "the deterministic witness must not recreate independently scheduled FIFO clocks",
)
require(
    r"always\s+#5\s+io_clock\s*=\s*~io_clock.*?\.io_pushClock\s*\(\s*io_clock\s*\).*?\.io_popClock\s*\(\s*io_clock\s*\)",
    simultaneous_testbench,
    "CDC-COINCIDENT-SHARED-CLOCK-COVERAGE-MISSING",
    "both FIFO domains must use the same clock object for the deterministic simultaneous-transfer edge",
)
require(
    r"repeat\s*\(\s*4\s*\)\s*@\(negedge\s+io_clock\).*?io_pushReset\s*=\s*1'b0.*?io_popReset\s*=\s*1'b0.*?repeat\s*\(\s*4\s*\)\s*@\(negedge\s+io_clock\).*?io_pushPayload\s*=\s*8'h00.*?io_pushValid\s*=\s*1'b1.*?io_popReady\s*=\s*1'b0.*?sent\s*!=\s*1",
    simultaneous_testbench,
    "CDC-SIMULTANEOUS-BACKLOG-MISSING",
    "the shared-clock witness must settle reset and create exactly one queued item before arming both interfaces",
)
require(
    r"while\s*\(\s*\(\s*io_popValid\s*!==\s*1'b1\s*\|\|\s*io_pushReady\s*!==\s*1'b1\s*\).*?preconditionCycles\s*<\s*64.*?io_pushPayload\s*=\s*8'h01.*?io_pushValid\s*=\s*1'b1.*?io_popReady\s*=\s*1'b1.*?@\(posedge\s+io_clock\).*?@\(negedge\s+io_clock\).*?simultaneousTransfers\s*!=\s*1",
    simultaneous_testbench,
    "CDC-SIMULTANEOUS-STIMULUS-MISSING",
    "the witness must await live pop-valid/push-ready and arm push and pop for one shared rising edge",
)
require(
    r"always\s*@\(posedge\s+io_clock\).*?io_popPayload\s*!==\s*received\[7:0\].*?if\s*\(\s*io_pushValid\s*&&\s*io_pushReady\s*&&\s*io_popValid\s*&&\s*io_popReady\s*\).*?simultaneousTransfers\s*=\s*simultaneousTransfers\s*\+\s*1.*?sent\s*!=\s*2\s*\|\|\s*received\s*!=\s*2\s*\|\|\s*simultaneousTransfers\s*!=\s*1.*?io_popValid\s*!==\s*1'b0.*?io_pushOccupancy\s*!==\s*5'b0.*?io_popOccupancy\s*!==\s*5'b0.*?STREAMFIFOCC_57A_SIMULTANEOUS_PASS",
    simultaneous_testbench,
    "CDC-SIMULTANEOUS-WITNESS-MISSING",
    "the shared edge must count exactly one accepted push/pop, preserve payload order and settle completely empty",
)
require(
    r"test\s*\(\s*\"dedicated shared-clock harness forces one simultaneous transfer\"\s*\).*?ResetModes\.foreach.*?Depths\.foreach.*?simultaneousTransferTestbench.*?\.io_pushClock\(io_clock\).*?\.io_popClock\(io_clock\).*?simultaneousTransfers != 1.*?STREAMFIFOCC_57A_SIMULTANEOUS_PASS",
    cdc_tests,
    "CDC-SIMULTANEOUS-SHAPE-TEST-MISSING",
    "an ordinary non-opt-in test must pin the deterministic shared-clock harness shape across the full matrix",
)

# Buffered StreamFifoCC deliberately derives its pop reset from push_reset, so
# both independently emitted buffered tops omit the unused external pop_reset
# port. Keep the miter's instance tails topology-aware without weakening either
# leg's independently checked interface.
miter_match = re.search(
    r"private\s+def\s+equivalenceMiter\s*\((?P<body>.*?)\n\s*private\s+def\s+positiveSby\s*\(",
    formal_tests,
    re.MULTILINE | re.DOTALL,
)
if miter_match is None:
    fail(
        "FORMAL-MITER-RESET-TOPOLOGY-MISSING",
        "the formal equivalence miter must remain separately inspectable",
    )
miter_body = miter_match.group("body")
optional_pop_reset = re.findall(
    r'val\s+popResetConnection\s*=\s*if\s*\(\s*configuration\.buffered\s*\)\s*""\s*else\s*",\\n\s+\.pop_reset\(pop_reset\)"',
    miter_body,
    re.MULTILINE | re.DOTALL,
)
topology_sites = re.findall(
    r"\|\s*\.pop_clk\(pop_clk\)\$popResetConnection",
    miter_body,
    re.MULTILINE,
)
literal_pop_reset_ports = re.findall(
    r"\.pop_reset\(pop_reset\)",
    miter_body,
    re.MULTILINE,
)
if (
    len(optional_pop_reset) != 1
    or len(topology_sites) != 2
    or len(literal_pop_reset_ports) != 1
):
    fail(
        "FORMAL-MITER-RESET-TOPOLOGY-MISSING",
        "the miter must omit buffered pop_reset and append the direct-only port to both DUTs exactly once",
    )

miter_test_match = re.search(
    r"test\s*\(\s*\"formal miter connects each reference and typed DUT port exactly once\"\s*\)(?P<body>.*?)\n\s*test\s*\(\s*\"formal DUT preparation releases BufferCC hierarchy before flattening\"",
    formal_tests,
    re.MULTILINE | re.DOTALL,
)
if miter_test_match is None:
    fail(
        "FORMAL-MITER-RESET-TOPOLOGY-MISSING",
        "the ordinary miter port-topology test must remain separately inspectable",
    )
miter_test = miter_test_match.group("body")
require(
    r"val\s+clockResetConnections\s*=\s*Vector\s*\(.*?\"push_clk\"\s*->\s*\"push_clk\".*?\"push_reset\"\s*->\s*\"push_reset\".*?\"pop_clk\"\s*->\s*\"pop_clk\".*?\)\s*\+\+\s*\(\s*if\s*\(\s*configuration\.buffered\s*\)\s*Vector\.empty\s*else\s*Vector\s*\(\s*\"pop_reset\"\s*->\s*\"pop_reset\"\s*\)\s*\)",
    miter_test,
    "FORMAL-MITER-RESET-TOPOLOGY-MISSING",
    "the ordinary test must expect pop_reset only for direct reset topology",
)
for instance, prefix in (
    ("reference_dut", "referenceDataConnections"),
    ("typed_dut", "typedDataConnections"),
):
    require(
        rf"connectionsOf\s*\(\s*miter\s*,\s*\"{instance}\"\s*\)\s*==\s*{prefix}\s*\+\+\s*clockResetConnections",
        miter_test,
        "FORMAL-MITER-RESET-TOPOLOGY-MISSING",
        f"the ordinary topology test must validate {instance} independently",
    )

generated_validation_match = re.search(
    r"private\s+def\s+validateGeneratedDuts\s*\((?P<body>.*?)\n\s*private\s+def\s+prepareDuts\s*\(",
    formal_tests,
    re.MULTILINE | re.DOTALL,
)
if generated_validation_match is None:
    fail(
        "FORMAL-MITER-RESET-TOPOLOGY-MISSING",
        "generated DUT validation must remain separately inspectable",
    )
generated_validation = generated_validation_match.group("body")
require(
    r"moduleHeader\s*\(\s*source\s*,\s*typedSourceTop\s*\(\s*buffered\s*\)\s*\)\s*\.contains\s*\(\s*\"pop_reset\"\s*\)\s*==\s*!buffered",
    generated_validation,
    "FORMAL-MITER-RESET-TOPOLOGY-MISSING",
    "typed generated tops must independently pin their reset-port topology",
)
require(
    r"val\s+top\s*=\s*concreteSourceTop\s*\(\s*width\s*,\s*depth\s*,\s*buffered\s*\).*?moduleHeader\s*\(\s*source\s*,\s*top\s*\)\.contains\s*\(\s*\"pop_reset\"\s*\)\s*==\s*!buffered",
    generated_validation,
    "FORMAL-MITER-RESET-TOPOLOGY-MISSING",
    "concrete generated tops must independently pin their reset-port topology",
)

# Candidate and reference RTLIL are loaded into one proof design. Flatten each
# leg after releasing BufferCC's synthesis-only hierarchy attribute so their
# independently emitted helper module names cannot collide.
for builder, next_builder in (
    ("candidatePreparationScript", "referencePreparationScript"),
    ("referencePreparationScript", "equivalenceMiter"),
):
    builder_match = re.search(
        rf"private\s+def\s+{builder}\s*\((?P<body>.*?)\n\s*private\s+def\s+{next_builder}\s*\(",
        formal_tests,
        re.MULTILINE | re.DOTALL,
    )
    if builder_match is None:
        fail(
            "FORMAL-PREPARATION-HIERARCHY-RELEASE-MISSING",
            f"formal preparation builder {builder} must remain separately inspectable",
        )
    builder_body = builder_match.group("body")
    hierarchy_release = re.findall(
        r"\|hierarchy -check -top [^\n]+\s*\n\s*\|setattr -unset keep_hierarchy\s*\n\s*\|flatten",
        builder_body,
        re.MULTILINE,
    )
    release_commands = re.findall(
        r"^\s*\|setattr -unset keep_hierarchy\s*$",
        builder_body,
        re.MULTILINE,
    )
    if len(hierarchy_release) != 1 or len(release_commands) != 1:
        fail(
            "FORMAL-PREPARATION-HIERARCHY-RELEASE-MISSING",
            f"formal preparation builder {builder} must release hierarchy exactly once immediately before flatten",
        )
preparation_test_match = re.search(
    r"test\s*\(\s*\"formal DUT preparation releases BufferCC hierarchy before flattening\"\s*\)(?P<body>.*?)\n\s*test\s*\(\s*\"formal positive proof uses reachability PDR for the multiclock model\"",
    formal_tests,
    re.MULTILINE | re.DOTALL,
)
if preparation_test_match is None:
    fail(
        "FORMAL-PREPARATION-HIERARCHY-RELEASE-TEST-MISSING",
        "the ordinary formal-preparation test must remain separately inspectable",
    )
preparation_test = preparation_test_match.group("body")
require(
    r"def\s+assertSingleOrderedRelease.*?hierarchy -check -top \$top.*?setattr -unset keep_hierarchy.*?flatten.*?count\(_ == \"setattr -unset keep_hierarchy\"\) == 1",
    preparation_test,
    "FORMAL-PREPARATION-HIERARCHY-RELEASE-TEST-MISSING",
    "the ordinary unit test must pin exact hierarchy-release ordering and uniqueness",
)
require(
    r"ResetModes\.foreach\s*\{\s*buffered\s*=>.*?val\s+candidate\s*=\s*candidatePreparationScript\s*\(.*?depth\s*=\s*2\s*,\s*buffered\s*=\s*buffered\s*\).*?assertSingleOrderedRelease\s*\(\s*candidate\s*,\s*typedSourceTop\s*\(\s*buffered\s*\)\s*\)",
    preparation_test,
    "FORMAL-PREPARATION-HIERARCHY-RELEASE-TEST-MISSING",
    "the ordinary unit test must pin the candidate preparation leg",
)
require(
    r"ResetModes\.foreach\s*\{\s*buffered\s*=>.*?val\s+reference\s*=\s*referencePreparationScript\s*\(.*?width\s*=\s*5\s*,\s*depth\s*=\s*2\s*,\s*buffered\s*=\s*buffered\s*\).*?assertSingleOrderedRelease\s*\(\s*reference\s*,\s*concreteSourceTop\s*\(\s*width\s*=\s*5\s*,\s*depth\s*=\s*2\s*,\s*buffered\s*=\s*buffered\s*\)\s*\)",
    preparation_test,
    "FORMAL-PREPARATION-HIERARCHY-RELEASE-TEST-MISSING",
    "the ordinary unit test must pin the reference preparation leg",
)

# PDR consumes a fully flattened binary AIG. Release the synthesis-only
# BufferCC hierarchy boundary before prep so the engine cannot prune its CDC
# outputs, then preserve ordinary and undriven unknowns as nondeterministic
# inputs before normalizing only residual FF/RAM init state.
for builder, next_builder in (
    ("positiveSby", "mutationSby"),
    ("mutationSby", "runSby"),
):
    builder_match = re.search(
        rf"private\s+def\s+{builder}\s*\((?P<body>.*?)\n\s*private\s+def\s+{next_builder}\s*\(",
        formal_tests,
        re.MULTILINE | re.DOTALL,
    )
    if builder_match is None:
        fail(
            "FORMAL-UNDEFINED-NORMALIZATION-MISSING",
            f"formal builder {builder} must remain separately inspectable",
        )
    normalization = re.findall(
        r"\|hierarchy -check -top \$top\s*\n\s*\|setattr -unset keep_hierarchy\s*\n\s*\|prep -top \$top\s*\n\s*\|memory_map\s*\n\s*\|setundef -undriven -anyseq\s*\n\s*\|setundef -init -zero\s*\n\s*\|opt_clean\s*\n\s*\|check -assert",
        builder_match.group("body"),
        re.MULTILINE,
    )
    if len(normalization) != 1:
        fail(
            "FORMAL-UNDEFINED-NORMALIZATION-MISSING",
            f"formal builder {builder} must preserve exactly one ordered hierarchy-release and undefined-state normalization sequence",
        )
require(
    r"val\s+proofPreparation\s*=.*?hierarchy -check -top miter.*?setattr -unset keep_hierarchy.*?prep -top miter.*?memory_map.*?setundef -undriven -anyseq.*?setundef -init -zero.*?Vector\s*\(\s*config\s*,\s*mutationConfig\s*\)\.foreach.*?indexOf\s*\(\s*proofPreparation\s*\).*?indexOf\s*\(\s*proofPreparation\s*,\s*first\s*\+\s*1\s*\)\s*<\s*0",
    formal_tests,
    "FORMAL-UNDEFINED-NORMALIZATION-TEST-MISSING",
    "an ordinary unit test must pin hierarchy release, normalization ordering and uniqueness in both formal configs",
)

# The candidate and reference RAMs are intentionally independent until the
# proof model is built. ABC latch correlation may merge only state proven
# equivalent by SAT/induction, preserving every property output and arbitrary
# input before PDR explores reachability. Keep that prepass exact and confined
# to the positive engine; the live mutation retains its independent SMT BMC.
positive_sby_match = re.search(
    r"private\s+def\s+positiveSby\s*\((?P<body>.*?)\n\s*private\s+def\s+mutationSby\s*\(",
    formal_tests,
    re.MULTILINE | re.DOTALL,
)
if positive_sby_match is None:
    fail(
        "FORMAL-PDR-LATCH-CORRELATION-MISSING",
        "the positive formal builder must remain separately inspectable",
    )
positive_sby = positive_sby_match.group("body")
positive_engine_sections = re.findall(
    r"\|\[engines\]\s*\n\s*\|abc lcorr; pdr\s*\n\s*\|\s*\n\s*\|\[script\]",
    positive_sby,
    re.MULTILINE,
)
positive_engine_lines = re.findall(
    r"^\s*\|abc lcorr; pdr\s*$",
    positive_sby,
    re.MULTILINE,
)
if len(positive_engine_sections) != 1 or len(positive_engine_lines) != 1:
    fail(
        "FORMAL-PDR-LATCH-CORRELATION-MISSING",
        "the positive proof must apply exactly one ABC lcorr prepass immediately before PDR",
    )

positive_config_test_match = re.search(
    r"test\s*\(\s*\"formal positive proof uses reachability PDR for the multiclock model\"\s*\)(?P<body>.*?)\n\s*test\s*\(\s*\n\s*\"typed StreamFifoCC is formally equivalent",
    formal_tests,
    re.MULTILINE | re.DOTALL,
)
if positive_config_test_match is None:
    fail(
        "FORMAL-PDR-LATCH-CORRELATION-MISSING",
        "the ordinary positive-proof configuration test must remain separately inspectable",
    )
positive_config_test = positive_config_test_match.group("body")
require(
    r'"\(\?m\)\^abc lcorr; pdr\$"\.r\.findAllMatchIn\s*\(\s*config\s*\)\.length\s*==\s*1',
    positive_config_test,
    "FORMAL-PDR-LATCH-CORRELATION-MISSING",
    "the ordinary test must require the exact positive correlation-plus-PDR engine once",
)
require(
    r'"\(\?m\)\^smtbmc yices\$"\.r.*?findAllMatchIn\s*\(\s*mutationConfig\s*\).*?length\s*==\s*1.*?!mutationConfig\.contains\s*\(\s*"lcorr;"\s*\)',
    positive_config_test,
    "FORMAL-PDR-LATCH-CORRELATION-MISSING",
    "the ordinary test must keep the mutation on exactly one unchanged SMT BMC engine",
)

# The shared Gray helper is wider than StreamFifoCC's current pointer matrix.
# Retain an explicit above-32-bit regression so its maximum-derived prefix
# topology cannot silently fall back to the construction witness.
require(
    r"class\s+NativeFromGray\s*\(\s*width:\s*HdlInt\s*\).*?spinal\.lib\.fromGray\s*\(\s*gray\s*\)",
    reuse_tests,
    "FROM-GRAY-WIDE-FIXTURE-MISSING",
    "shared-library proof must exercise the ordinary typed fromGray surface",
)
require(
    r"test\s*\(\s*\"native typed fromGray retains every prefix stage through WIDTH 65\"\s*\).*?HdlInt\.param\s*\(\s*\"WIDTH\"\s*,\s*default\s*=\s*33\s*,\s*min\s*=\s*1\s*,\s*max\s*=\s*65\s*\).*?!parameterized\.contains\s*\(\s*\"\[32:0\]\"\s*\).*?Vector\s*\(\s*1\s*,\s*2\s*,\s*4\s*,\s*8\s*,\s*16\s*,\s*32\s*,\s*64\s*\).*?\"\[64:0\]\"",
    reuse_tests,
    "FROM-GRAY-WIDE-COVERAGE-MISSING",
    "shared-library proof must retain WIDTH 65 typed and concrete Gray-decode evidence",
)
PY

  printf 'Increment 57a typed native StreamFifoCC boundary passed.\n'
}

case "${1:-}" in
  ''|--check)
    check_boundary
    ;;
  --self-test)
    temporary="$(mktemp -d)"
    trap 'rm -rf -- "$temporary"' EXIT

    cp "$stream_source" "$temporary/good-stream.scala"
    MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/good-stream.scala" \
      "$0" --check >/dev/null

    sed '0,/depth\.isPow2/s//ElabBool.literal(true)/' \
      "$stream_source" > "$temporary/missing-power-of-two.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/missing-power-of-two.scala" \
      "$0" --check >"$temporary/legality.stdout" 2>"$temporary/legality.stderr"; then
      fail SELF-TEST-ACCEPTED 'missing power-of-two depth predicate passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-LEGALITY-PREDICATE-MISSING' \
      "$temporary/legality.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'legality mutation did not report its stable diagnostic'

    sed '0,/maximum = typedDepthFormalMaximum(depth)/s//maximum = depth.maximum/' \
      "$stream_source" > "$temporary/owner-specific-parent-domain.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/owner-specific-parent-domain.scala" \
      "$0" --check >"$temporary/parent-domain.stdout" 2>"$temporary/parent-domain.stderr"; then
      fail SELF-TEST-ACCEPTED 'owner-specific typed FIFO parent formal domain passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-DEPTH-FORMAL-CANONICAL-DOMAIN-MISSING' \
      "$temporary/parent-domain.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'typed FIFO parent-domain mutation did not report its stable diagnostic'

    sed '0,/spinal_stream_fifocc_legal_depth_ceiling/s//spinal_stream_fifocc_legal_depth_marker_disabled/' \
      "$stream_source" > "$temporary/missing-legal-geometry-identity.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/missing-legal-geometry-identity.scala" \
      "$0" --check >"$temporary/geometry.stdout" 2>"$temporary/geometry.stderr"; then
      fail SELF-TEST-ACCEPTED 'missing typed FIFO legal-geometry identity passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-DEPTH-FORMAL-GEOMETRY-IDENTITY-MISSING' \
      "$temporary/geometry.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'typed FIFO geometry-identity mutation did not report its stable diagnostic'

    sed '0,/result := fromGray(value)/s//result := value.asUInt |>> 1/' \
      "$stream_source" > "$temporary/local-gray-codec.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/local-gray-codec.scala" \
      "$0" --check >"$temporary/codec.stdout" 2>"$temporary/codec.stderr"; then
      fail SELF-TEST-ACCEPTED 'FIFO-local Gray codec mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-LOCAL-GRAY-CODEC' \
      "$temporary/codec.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'local Gray codec mutation did not report its stable diagnostic'

    sed '0,/^    pushToPopGray := pushCC\.pushPtrGray/s//    pushToPopGray := pushCC.pushPtrGray\n    pushToPopGray := pushCC.pushPtrGray/' \
      "$stream_source" > "$temporary/copied-fifo-algorithm.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/copied-fifo-algorithm.scala" \
      "$0" --check >"$temporary/copied.stdout" 2>"$temporary/copied.stderr"; then
      fail SELF-TEST-ACCEPTED 'copied FIFO algorithm mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-NATIVE-ALGORITHM-DUPLICATED' \
      "$temporary/copied.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'copied FIFO algorithm mutation did not report its stable diagnostic'

    sed '0,/withOptionalBufferedResetFromUncached/s//withOptionalBufferedResetFrom/' \
      "$stream_source" > "$temporary/cached-generated-reset.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/cached-generated-reset.scala" \
      "$0" --check >"$temporary/reset.stdout" 2>"$temporary/reset.stderr"; then
      fail SELF-TEST-ACCEPTED 'generated reset-cache mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-BUFFERED-RESET-OWNER-MISSING' \
      "$temporary/reset.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'generated reset-cache mutation did not report its stable diagnostic'

    sed '0,/noMerge = false/s//noMerge = true/' \
      "$stream_source" > "$temporary/nonmergeable-buffercc.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/nonmergeable-buffercc.scala" \
      "$0" --check >"$temporary/merge.stdout" 2>"$temporary/merge.stderr"; then
      fail SELF-TEST-ACCEPTED 'nonmergeable typed BufferCC definition mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-BUFFERCC-MULTI-INSTANCE-DEFINITION-POLICY-MISSING' \
      "$temporary/merge.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'typed BufferCC definition mutation did not report its stable diagnostic'

    sed '0,/maximum = StreamFifoCC\.TypedPointerWidthMaximum/s//maximum = elabPtrWidth.maximum/' \
      "$stream_source" > "$temporary/owner-derived-buffercc-domain.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/owner-derived-buffercc-domain.scala" \
      "$0" --check >"$temporary/domain.stdout" 2>"$temporary/domain.stderr"; then
      fail SELF-TEST-ACCEPTED 'owner-derived typed BufferCC formal domain passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-BUFFERCC-WIDTH-FORMAL-MISSING' \
      "$temporary/domain.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'typed BufferCC formal-domain mutation did not report its stable diagnostic'

    sed '0,/!elabDepth\.isConcrete || !payloadWidth\.isConcrete/s//!elabDepth.isConcrete/' \
      "$stream_source" > "$temporary/depth-only-memory-roles.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/depth-only-memory-roles.scala" \
      "$0" --check >"$temporary/memory-roles.stdout" 2>"$temporary/memory-roles.stderr"; then
      fail SELF-TEST-ACCEPTED 'depth-only parameterized memory-role predicate passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-AGGREGATE-WRITE-CARRIER-MISSING' \
      "$temporary/memory-roles.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'memory-role predicate mutation did not report its stable diagnostic'

    sed '0,/allowMixedWidth = false/s//allowMixedWidth = true/' \
      "$stream_source" > "$temporary/mixed-width-aggregate-write.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/mixed-width-aggregate-write.scala" \
      "$0" --check >"$temporary/write.stdout" 2>"$temporary/write.stderr"; then
      fail SELF-TEST-ACCEPTED 'mixed-width aggregate memory mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-AGGREGATE-WRITE-CARRIER-MISSING' \
      "$temporary/write.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'aggregate memory mutation did not report its stable diagnostic'

    sed '0,/if (!width\.isConcrete)/s//if (false)/' \
      "$crossclock_source" > "$temporary/witness-width-blackbox.scala"
    if MORPHDL_STREAMFIFOCC_CROSSCLOCK_SOURCE="$temporary/witness-width-blackbox.scala" \
      "$0" --check >"$temporary/blackbox.stdout" 2>"$temporary/blackbox.stderr"; then
      fail SELF-TEST-ACCEPTED 'witness-width BufferCC blackbox mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-BUFFERCC-BLACKBOX-TYPED-WIDTH-GUARD-MISSING' \
      "$temporary/blackbox.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'BufferCC blackbox mutation did not report its stable diagnostic'

    sed '0,/case e: ParameterizedVerilogException/s//case e: RuntimeException/' \
      "$phase_source" > "$temporary/retried-parameterized-diagnostic.scala"
    if MORPHDL_STREAMFIFOCC_PHASE_SOURCE="$temporary/retried-parameterized-diagnostic.scala" \
      "$0" --check >"$temporary/retry-bypass.stdout" 2>"$temporary/retry-bypass.stderr"; then
      fail SELF-TEST-ACCEPTED 'parameterized diagnostic retry mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-PARAMETERIZED-DIAGNOSTIC-RETRY-BYPASS-MISSING' \
      "$temporary/retry-bypass.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'parameterized diagnostic retry mutation did not report its stable diagnostic'

    sed '0,/io\.push\.ready := inert/s//io.push.ready := True/' \
      "$stream_source" > "$temporary/unsafe-invalid-depth.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/unsafe-invalid-depth.scala" \
      "$0" --check >"$temporary/inert.stdout" 2>"$temporary/inert.stderr"; then
      fail SELF-TEST-ACCEPTED 'unsafe invalid-depth fallback passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-INVALID-ALTERNATIVE-MISSING' \
      "$temporary/inert.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'invalid-depth mutation did not report its stable diagnostic'

    sed '0,/stream_fifocc_invalid_payload_zero/s//stream_fifocc_invalid_payload_frozen/' \
      "$stream_source" > "$temporary/unnamed-invalid-payload-zero.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/unnamed-invalid-payload-zero.scala" \
      "$0" --check >"$temporary/payload-zero.stdout" 2>"$temporary/payload-zero.stderr"; then
      fail SELF-TEST-ACCEPTED 'invalid payload-zero carrier identity mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-INVALID-ALTERNATIVE-MISSING' \
      "$temporary/payload-zero.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'invalid payload-zero mutation did not report its stable diagnostic'

    sed '0,/io\.popOccupancy := 0/s//pushToPopGray := 0\n      io.popOccupancy := 0/' \
      "$stream_source" > "$temporary/cross-sibling-gray.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/cross-sibling-gray.scala" \
      "$0" --check >"$temporary/gray.stdout" 2>"$temporary/gray.stderr"; then
      fail SELF-TEST-ACCEPTED 'invalid sibling Gray-carrier coupling passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-INVALID-ALTERNATIVE-CROSS-SCOPE-COUPLING' \
      "$temporary/gray.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'cross-sibling Gray mutation did not report its stable diagnostic'

    sed '0,/read\.hasTag(crossClockDomain)/s//true/' \
      "$memory_source" > "$temporary/untagged-cross-clock-memory.scala"
    if MORPHDL_STREAMFIFOCC_MEMORY_SOURCE="$temporary/untagged-cross-clock-memory.scala" \
      "$0" --check >"$temporary/memory.stdout" 2>"$temporary/memory.stderr"; then
      fail SELF-TEST-ACCEPTED 'untagged cross-clock memory mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-CROSS-CLOCK-MEMORY-AUTHORITY-MISSING' \
      "$temporary/memory.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'memory mutation did not report its stable diagnostic'

    sed '0,/exactEdges != authorizedEdges/s//exactEdges != 1/' \
      "$fallback_source" > "$temporary/noncardinal-retained-zero.scala"
    if MORPHDL_STREAMFIFOCC_FALLBACK_SOURCE="$temporary/noncardinal-retained-zero.scala" \
      "$0" --check >"$temporary/cardinality.stdout" 2>"$temporary/cardinality.stderr"; then
      fail SELF-TEST-ACCEPTED 'non-cardinal retained-zero rewrite mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-RETAINED-ZERO-CARDINALITY-AUTHORITY-MISSING' \
      "$temporary/cardinality.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'retained-zero cardinality mutation did not report its stable diagnostic'

    sed '0,/|    \.io_popClock(io_clock), \.io_popReset/s//|    .io_popClock(io_popClock), .io_popReset/' \
      "$cdc_test_source" > "$temporary/split-simultaneous-clock.scala"
    if MORPHDL_STREAMFIFOCC_CDC_TEST_SOURCE="$temporary/split-simultaneous-clock.scala" \
      "$0" --check >"$temporary/coincident.stdout" 2>"$temporary/coincident.stderr"; then
      fail SELF-TEST-ACCEPTED 'split deterministic simultaneous-transfer clock mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-CDC-COINCIDENT-SHARED-CLOCK-COVERAGE-MISSING' \
      "$temporary/coincident.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'shared-clock CDC mutation did not report its stable diagnostic'

    sed 's/|setundef -undriven -anyseq/|setundef -zero/g' \
      "$formal_test_source" > "$temporary/unsound-formal-normalization.scala"
    if MORPHDL_STREAMFIFOCC_FORMAL_TEST_SOURCE="$temporary/unsound-formal-normalization.scala" \
      "$0" --check >"$temporary/formal.stdout" 2>"$temporary/formal.stderr"; then
      fail SELF-TEST-ACCEPTED 'unsound formal undefined-state normalization passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-FORMAL-UNDEFINED-NORMALIZATION-MISSING' \
      "$temporary/formal.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'formal normalization mutation did not report its stable diagnostic'

    sed '/private def positiveSby/,/private def runSby/ s/|setattr -unset keep_hierarchy/|setattr -set keep_hierarchy 1/g' \
      "$formal_test_source" > "$temporary/retained-formal-hierarchy.scala"
    if MORPHDL_STREAMFIFOCC_FORMAL_TEST_SOURCE="$temporary/retained-formal-hierarchy.scala" \
      "$0" --check >"$temporary/hierarchy.stdout" 2>"$temporary/hierarchy.stderr"; then
      fail SELF-TEST-ACCEPTED 'retained formal BufferCC hierarchy mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-FORMAL-UNDEFINED-NORMALIZATION-MISSING' \
      "$temporary/hierarchy.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'formal hierarchy mutation did not report its stable diagnostic'

    sed '/private def positiveSby/,/private def mutationSby/ s/|abc lcorr; pdr/|abc pdr/' \
      "$formal_test_source" > "$temporary/missing-pdr-latch-correlation.scala"
    if MORPHDL_STREAMFIFOCC_FORMAL_TEST_SOURCE="$temporary/missing-pdr-latch-correlation.scala" \
      "$0" --check >"$temporary/lcorr.stdout" 2>"$temporary/lcorr.stderr"; then
      fail SELF-TEST-ACCEPTED 'positive PDR without latch correlation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-FORMAL-PDR-LATCH-CORRELATION-MISSING' \
      "$temporary/lcorr.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'PDR latch-correlation mutation did not report its stable diagnostic'

    sed '/private def candidatePreparationScript/,/private def equivalenceMiter/ s/|setattr -unset keep_hierarchy/|setattr -set keep_hierarchy 1/g' \
      "$formal_test_source" > "$temporary/retained-preparation-hierarchy.scala"
    if MORPHDL_STREAMFIFOCC_FORMAL_TEST_SOURCE="$temporary/retained-preparation-hierarchy.scala" \
      "$0" --check >"$temporary/preparation.stdout" 2>"$temporary/preparation.stderr"; then
      fail SELF-TEST-ACCEPTED 'retained prepared-DUT BufferCC hierarchy mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-FORMAL-PREPARATION-HIERARCHY-RELEASE-MISSING' \
      "$temporary/preparation.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'prepared-DUT hierarchy mutation did not report its stable diagnostic'

    sed '/test("formal DUT preparation releases BufferCC hierarchy before flattening")/,/test("formal positive proof uses reachability PDR for the multiclock model")/ s/assertSingleOrderedRelease(candidate, typedSourceTop(buffered))/assert(candidate.nonEmpty)/' \
      "$formal_test_source" > "$temporary/bypassed-candidate-preparation-test.scala"
    if MORPHDL_STREAMFIFOCC_FORMAL_TEST_SOURCE="$temporary/bypassed-candidate-preparation-test.scala" \
      "$0" --check >"$temporary/candidate-test.stdout" 2>"$temporary/candidate-test.stderr"; then
      fail SELF-TEST-ACCEPTED 'bypassed candidate preparation unit assertion passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-FORMAL-PREPARATION-HIERARCHY-RELEASE-TEST-MISSING' \
      "$temporary/candidate-test.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'candidate preparation unit-test mutation did not report its stable diagnostic'

    sed '/test("formal DUT preparation releases BufferCC hierarchy before flattening")/,/test("formal positive proof uses reachability PDR for the multiclock model")/ s/^        reference,$/        candidate,/' \
      "$formal_test_source" > "$temporary/bypassed-reference-preparation-test.scala"
    if MORPHDL_STREAMFIFOCC_FORMAL_TEST_SOURCE="$temporary/bypassed-reference-preparation-test.scala" \
      "$0" --check >"$temporary/reference-test.stdout" 2>"$temporary/reference-test.stderr"; then
      fail SELF-TEST-ACCEPTED 'bypassed reference preparation unit assertion passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-FORMAL-PREPARATION-HIERARCHY-RELEASE-TEST-MISSING' \
      "$temporary/reference-test.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'reference preparation unit-test mutation did not report its stable diagnostic'

    sed '/private def equivalenceMiter/,/private def positiveSby/ s/if (configuration.buffered) ""/if (!configuration.buffered) ""/' \
      "$formal_test_source" > "$temporary/inverted-miter-reset-topology.scala"
    if MORPHDL_STREAMFIFOCC_FORMAL_TEST_SOURCE="$temporary/inverted-miter-reset-topology.scala" \
      "$0" --check >"$temporary/miter-topology.stdout" 2>"$temporary/miter-topology.stderr"; then
      fail SELF-TEST-ACCEPTED 'inverted formal miter reset topology passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-FORMAL-MITER-RESET-TOPOLOGY-MISSING' \
      "$temporary/miter-topology.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'miter reset-topology mutation did not report its stable diagnostic'

    printf 'Increment 57a typed native StreamFifoCC boundary self-test passed.\n'
    ;;
  *)
    fail ARGUMENT "unknown argument: $1"
    ;;
esac
