#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

stream_source="${MORPHDL_STREAMFIFOCC_STREAM_SOURCE:-lib/src/main/scala/spinal/lib/Stream.scala}"
utils_source="${MORPHDL_STREAMFIFOCC_UTILS_SOURCE:-lib/src/main/scala/spinal/lib/Utils.scala}"
crossclock_source="${MORPHDL_STREAMFIFOCC_CROSSCLOCK_SOURCE:-lib/src/main/scala/spinal/lib/CrossClock.scala}"
memory_source="${MORPHDL_STREAMFIFOCC_MEMORY_SOURCE:-morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala}"
hierarchy_source="${MORPHDL_STREAMFIFOCC_HIERARCHY_SOURCE:-morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala}"
test_source="${MORPHDL_STREAMFIFOCC_TEST_SOURCE:-morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCParameterizedTests.scala}"
reuse_test_source="${MORPHDL_STREAMFIFOCC_REUSE_TEST_SOURCE:-morphhdl/src/test/scala/morphhdl/NativeLibraryReuseTests.scala}"

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
  require_file "$memory_source" parameterized-memory
  require_file "$hierarchy_source" parameterized-hierarchy
  require_file "$test_source" focused-test
  require_file "$reuse_test_source" shared-helper-test

  python3 - \
    "$stream_source" \
    "$utils_source" \
    "$crossclock_source" \
    "$memory_source" \
    "$hierarchy_source" \
    "$test_source" \
    "$reuse_test_source" <<'PY'
import re
import sys
from pathlib import Path

(
    stream_path,
    utils_path,
    crossclock_path,
    memory_path,
    hierarchy_path,
    test_path,
    reuse_test_path,
) = map(Path, sys.argv[1:])

stream = stream_path.read_text(encoding="utf-8")
utils = utils_path.read_text(encoding="utf-8")
crossclock = crossclock_path.read_text(encoding="utf-8")
memory = memory_path.read_text(encoding="utf-8")
hierarchy = hierarchy_path.read_text(encoding="utf-8")
tests = test_path.read_text(encoding="utf-8")
reuse_tests = reuse_test_path.read_text(encoding="utf-8")


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
    rf"val\s+{re.escape(invalid_owner)}\s*=\s*\(!depthIsLegal\)\s+generate\s+new\s+Area\s*\{{(?P<body>.*?)^[ \t]*\}}",
    stream[invalid_owner_start:],
    re.MULTILINE | re.DOTALL,
)
if invalid_block_match is None:
    fail(
        "INVALID-ALTERNATIVE-MISSING",
        "the inert depth alternative must have one inspectable generated body",
    )
invalid_region = invalid_block_match.group("body")
for pattern, code, message in (
    (r"Mem\s*\(\s*dataType,\s*elabDepth\s*\)", "TYPED-RAM-DEPTH-MISSING", "the legal FIFO owner must contain the typed native RAM"),
    (r"new\s+ClockingArea\s*\(\s*pushClock\s*\)\s+with\s+PushCCMembers", "PUSH-AREA-MISSING", "the legal FIFO owner must contain the native push ClockingArea"),
    (r"new\s+ClockingArea\s*\(\s*finalPopCd\s*\)\s+with\s+PopCCMembers", "POP-AREA-MISSING", "the legal FIFO owner must contain the native pop ClockingArea"),
    (r'"StreamFifoCCPopToPushBufferCC"', "BUFFERCC-DEFINITION-MISSING", "the legal FIFO owner must retain the pop-to-push BufferCC definition"),
    (r'"StreamFifoCCPushToPopBufferCC"', "BUFFERCC-DEFINITION-MISSING", "the legal FIFO owner must retain the push-to-pop BufferCC definition"),
):
    require(pattern, legal_region, code, message)

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
        rf"^[ \t]*{re.escape(alias_source)}\s*=\s*[A-Za-z_]\w*\s*$",
        stream[:legal_owner_match.start()],
        "LEGACY-INSPECTION-MEMBER-MISSING",
        f"native inspection member {member} must also resolve to the concrete FIFO leg",
    )
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
    r"io\.push\.ready\s*:=\s*False.*?io\.pushOccupancy\s*:=\s*0.*?io\.pop\.valid\s*:=\s*False.*?io\.pop\.payload\s*:=\s*io\.pop\.payload\.getZero.*?io\.popOccupancy\s*:=\s*0",
    invalid_region,
    "INVALID-ALTERNATIVE-MISSING",
    "illegal depth specializations must drive the complete public FIFO interface inert",
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
    r"ElabFormalComponent\.parameter\s*\(\s*actual\s*=\s*elabPtrWidth,\s*name\s*=\s*\"WIDTH\"",
    stream,
    "BUFFERCC-WIDTH-FORMAL-MISSING",
    "each kept BufferCC child must bind the symbolic pointer width",
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

    sed '0,/result := fromGray(value)/s//result := value.asUInt |>> 1/' \
      "$stream_source" > "$temporary/local-gray-codec.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/local-gray-codec.scala" \
      "$0" --check >"$temporary/codec.stdout" 2>"$temporary/codec.stderr"; then
      fail SELF-TEST-ACCEPTED 'FIFO-local Gray codec mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-LOCAL-GRAY-CODEC' \
      "$temporary/codec.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'local Gray codec mutation did not report its stable diagnostic'

    sed '0,/io\.push\.ready := False/s//io.push.ready := True/' \
      "$stream_source" > "$temporary/unsafe-invalid-depth.scala"
    if MORPHDL_STREAMFIFOCC_STREAM_SOURCE="$temporary/unsafe-invalid-depth.scala" \
      "$0" --check >"$temporary/inert.stdout" 2>"$temporary/inert.stderr"; then
      fail SELF-TEST-ACCEPTED 'unsafe invalid-depth fallback passed'
    fi
    grep -Fq 'MORPH-NATIVE-STREAMFIFOCC-INVALID-ALTERNATIVE-MISSING' \
      "$temporary/inert.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'invalid-depth mutation did not report its stable diagnostic'

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

    printf 'Increment 57a typed native StreamFifoCC boundary self-test passed.\n'
    ;;
  *)
    fail ARGUMENT "unknown argument: $1"
    ;;
esac
