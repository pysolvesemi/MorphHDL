#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

stream_source="${MORPHDL_BROAD_NATIVE_STREAM_SOURCE:-lib/src/main/scala/spinal/lib/Stream.scala}"
flow_source="${MORPHDL_BROAD_NATIVE_FLOW_SOURCE:-lib/src/main/scala/spinal/lib/Flow.scala}"
mapping_source="${MORPHDL_BROAD_NATIVE_MAPPING_SOURCE:-lib/src/main/scala/spinal/lib/bus/misc/Misc.scala}"
factory_source="${MORPHDL_BROAD_NATIVE_FACTORY_SOURCE:-lib/src/main/scala/spinal/lib/bus/misc/BusSlaveFactory.scala}"
axi_factory_source="${MORPHDL_BROAD_NATIVE_AXI_FACTORY_SOURCE:-lib/src/main/scala/spinal/lib/bus/amba4/axi/Axi4SlaveFactory.scala}"
axi_lite_factory_source="${MORPHDL_BROAD_NATIVE_AXI_LITE_FACTORY_SOURCE:-lib/src/main/scala/spinal/lib/bus/amba4/axilite/AxiLite4SlaveFactory.scala}"
tilelink_factory_source="${MORPHDL_BROAD_NATIVE_TILELINK_FACTORY_SOURCE:-lib/src/main/scala/spinal/lib/bus/tilelink/SlaveFactory.scala}"
wishbone_factory_source="${MORPHDL_BROAD_NATIVE_WISHBONE_FACTORY_SOURCE:-lib/src/main/scala/spinal/lib/bus/wishbone/WishboneSlaveFactory.scala}"
bram_factory_source="${MORPHDL_BROAD_NATIVE_BRAM_FACTORY_SOURCE:-lib/src/main/scala/spinal/lib/bus/bram/BRAMSlaveFactory.scala}"
ahb_factory_source="${MORPHDL_BROAD_NATIVE_AHB_FACTORY_SOURCE:-lib/src/main/scala/spinal/lib/bus/amba3/ahblite/AhbLite3SlaveFactory.scala}"
fixture="${MORPHDL_BROAD_NATIVE_FIXTURE:-morphhdl/src/test/scala/nativeapplication/NativeLibraryMigrationFixture.scala}"
axi_fixture="${MORPHDL_BROAD_NATIVE_AXI_FIXTURE:-morphhdl/src/test/scala/morphhdl/NativeAxi4SlaveFactoryParameterizedOffsetTests.scala}"

fail() {
  local code="$1"
  shift
  printf 'MORPH-BROAD-NATIVE-MIGRATION-%s: %s\n' "$code" "$*" >&2
  exit 1
}

require_file() {
  local path="$1"
  local label="$2"
  test -f "$path" || fail SOURCE-MISSING "$label source is missing: $path"
}

require_text() {
  local path="$1"
  local text="$2"
  local code="$3"
  grep -Fq "$text" "$path" ||
    fail "$code" "$path is missing required source text: $text"
}

check_boundary() {
  require_file "$stream_source" Stream
  require_file "$flow_source" Flow
  require_file "$mapping_source" typed-address-mapping
  require_file "$factory_source" BusSlaveFactory
  require_file "$axi_factory_source" Axi4SlaveFactory
  require_file "$axi_lite_factory_source" AxiLite4SlaveFactory
  require_file "$tilelink_factory_source" TileLink-SlaveFactory
  require_file "$wishbone_factory_source" WishboneSlaveFactory
  require_file "$bram_factory_source" BRAMSlaveFactory
  require_file "$ahb_factory_source" AhbLite3SlaveFactory
  require_file "$fixture" application-fixture
  require_file "$axi_fixture" AXI4-proof-fixture

  python3 - \
    "$stream_source" \
    "$flow_source" \
    "$mapping_source" \
    "$factory_source" \
    "$axi_factory_source" \
    "$axi_lite_factory_source" \
    "$tilelink_factory_source" \
    "$wishbone_factory_source" \
    "$bram_factory_source" \
    "$ahb_factory_source" \
    "$fixture" \
    "$axi_fixture" <<'PY'
import re
import sys
from pathlib import Path

(
    stream_path,
    flow_path,
    mapping_path,
    factory_path,
    axi_factory_path,
    axi_lite_factory_path,
    tilelink_factory_path,
    wishbone_factory_path,
    bram_factory_path,
    ahb_factory_path,
    fixture_path,
    axi_fixture_path,
) = map(Path, sys.argv[1:])

stream = stream_path.read_text(encoding="utf-8")
flow = flow_path.read_text(encoding="utf-8")
mapping = mapping_path.read_text(encoding="utf-8")
factory = factory_path.read_text(encoding="utf-8")
axi_factory = axi_factory_path.read_text(encoding="utf-8")
axi_lite_factory = axi_lite_factory_path.read_text(encoding="utf-8")
tilelink_factory = tilelink_factory_path.read_text(encoding="utf-8")
wishbone_factory = wishbone_factory_path.read_text(encoding="utf-8")
bram_factory = bram_factory_path.read_text(encoding="utf-8")
ahb_factory = ahb_factory_path.read_text(encoding="utf-8")
fixture = fixture_path.read_text(encoding="utf-8")
axi_fixture = axi_fixture_path.read_text(encoding="utf-8")


def fail(code: str, message: str) -> None:
    raise SystemExit(f"MORPH-BROAD-NATIVE-MIGRATION-{code}: {message}")


def require(pattern: str, source: str, code: str, message: str) -> None:
    if re.search(pattern, source, re.MULTILINE | re.DOTALL) is None:
        fail(code, message)


def reject(pattern: str, source: str, code: str, message: str) -> None:
    if re.search(pattern, source, re.MULTILINE | re.DOTALL) is not None:
        fail(code, message)


# Stream typed control must validate the full domain and select only ordinary
# Boolean alternatives. The exact overload has no defaults.
require(
    r"def\s+pipelined\s*\(\s*m2s:\s*ElabBool,\s*s2m:\s*ElabBool,"
    r"\s*halfRate:\s*ElabBool\s*\)\s*:\s*Stream\[T\]",
    stream,
    "STREAM-TYPED-PIPELINE-MISSING",
    "Stream.pipelined must expose the explicit three-ElabBool overload",
)
require(
    r"val\s+legal\s*=\s*!\(halfRate\s*&&\s*\(m2s\s*\|\|\s*s2m\)\)"
    r"\s*ElabControl\.requireCondition\s*\(\s*legal",
    stream,
    "STREAM-LEGALITY-GUARD-MISSING",
    "typed Stream pipeline must prove the half-rate legality predicate",
)
require(
    r"ElabControl\.selectSymbolic\s*\(",
    stream,
    "STREAM-SELECTION-MISSING",
    "typed Stream pipeline must use exact symbolic structural selection",
)
require(
    r"def\s+nativePipeline\s*\(\s*nativeM2s:\s*Boolean,"
    r"\s*nativeS2m:\s*Boolean,\s*nativeHalfRate:\s*Boolean\s*\)"
    r"\s*:\s*Stream\[T\].*?branchSource\.pipelined\s*\("
    r"\s*m2s\s*=\s*nativeM2s,\s*s2m\s*=\s*nativeS2m,"
    r"\s*halfRate\s*=\s*nativeHalfRate\s*\)",
    stream,
    "STREAM-NATIVE-DELEGATION-MISSING",
    "typed Stream pipeline must delegate each selected profile to the ordinary Boolean overload",
)
for flags in (
    ("false", "false", "false"),
    ("true", "false", "false"),
    ("false", "true", "false"),
    ("true", "true", "false"),
    ("false", "false", "true"),
):
    m2s, s2m, half = flags
    require(
        rf"nativePipeline\s*\(\s*{m2s}\s*,\s*{s2m}\s*,\s*{half}\s*\)",
        stream,
        "STREAM-NATIVE-DELEGATION-MISSING",
        f"typed Stream pipeline is missing native Boolean alternative {flags}",
    )

# Only reviewed synchronous queue helpers receive typed depth overloads.
for name in ("queue", "queueWithOccupancy", "queueWithAvailability"):
    require(
        rf"def\s+{name}\s*\(\s*size:\s*ElabInt\s*\)",
        stream,
        "STREAM-TYPED-QUEUE-MISSING",
        f"Stream.{name}(ElabInt) is missing",
    )
require(
    r"StreamFifo\s*\(\s*payloadType,\s*size,",
    stream,
    "STREAM-FIFO-DELEGATION-MISSING",
    "typed Stream queues must instantiate the reviewed native StreamFifo",
)

require(
    r"def\s+m2sPipe\s*\(\s*holdPayload:\s*ElabBool\s*\)\s*:\s*Flow\[T\]",
    flow,
    "FLOW-TYPED-PIPELINE-MISSING",
    "Flow.m2sPipe(ElabBool) is missing",
)
require(
    r"ElabControl\.selectSymbolic\s*\(\s*holdPayload",
    flow,
    "FLOW-SELECTION-MISSING",
    "typed Flow pipeline must use exact symbolic structural selection",
)
require(
    r"m2sPipe\s*\(\s*holdPayload\s*=\s*true,\s*flush\s*=\s*flush,"
    r"\s*crossClockData\s*=\s*crossClockData\s*\)",
    flow,
    "FLOW-NATIVE-DELEGATION-MISSING",
    "typed Flow pipeline must delegate to the ordinary true alternative",
)
require(
    r"m2sPipe\s*\(\s*holdPayload\s*=\s*false,\s*flush\s*=\s*flush,"
    r"\s*crossClockData\s*=\s*crossClockData\s*\)",
    flow,
    "FLOW-NATIVE-DELEGATION-MISSING",
    "typed Flow pipeline must delegate to the ordinary false alternative",
)
for name in ("queueWithOccupancy", "queueWithAvailability"):
    require(
        rf"def\s+{name}\s*\(\s*size:\s*ElabInt\s*\)",
        flow,
        "FLOW-TYPED-QUEUE-MISSING",
        f"Flow.{name}(ElabInt) is missing",
    )

# Freeze the explicit exclusions. Increment 57a successor-refines only the
# StreamFifoCC factory/class and its existing queue/queueWithPushOccupancy
# helpers; that exact surface is owned by check-native-streamfifocc-boundary.sh.
# The historical Increment 57 contract continues to reject every other typed
# CDC protocol and all of its remaining exclusions.
excluded_signatures = (
    (r"def\s+queueOfReg\s*\([^)]*ElabInt", "typed queueOfReg is outside Increment 57"),
    (r"def\s+queueLowLatency\s*\([^)]*ElabInt", "typed low-latency queue is outside Increment 57"),
    (
        r"def\s+(?:ccToggle|ccToggleWithoutBuffer|ccToggleInputWait)\s*\([^)]*(?:ElabInt|ElabBool)",
        "typed toggle CDC policy remains outside Increment 57a",
    ),
    (r"(?:keep|crossClockData)\s*:\s*ElabBool", "metadata-only flags must remain static Boolean values"),
)
for pattern, message in excluded_signatures:
    reject(pattern, stream + "\n" + flow, "EXCLUDED-TYPED-SURFACE", message)

# The address carrier stays generic, exact and fail-closed for concrete-only
# AddressMapping operations.
require(
    r"final\s+case\s+class\s+ElabIntSingleMapping\s*\(\s*address:\s*ElabInt\s*\)"
    r"\s+extends\s+AddressMapping",
    mapping,
    "TYPED-MAPPING-MISSING",
    "generic ElabIntSingleMapping is missing",
)
require(
    r"authoritativeProjectedExpression\s*\(.*?"
    r"requireProjectedExactExtrema\s*=\s*true\s*\)",
    mapping,
    "TYPED-MAPPING-EXACT-DOMAIN-MISSING",
    "typed mapping must retain authoritative complete-domain evidence",
)
require(
    r"busAddress\s*===\s*ElabValue\.uintLike\s*\(\s*projectedAddress,\s*busAddress,\s*\"\"\s*\)",
    mapping,
    "TYPED-MAPPING-HIT-MISSING",
    "typed mapping must retain the exact address expression at bus width",
)
require(
    r"override\s+def\s+lowerBound:\s*BigInt\s*=\s*exactValues\.min",
    mapping,
    "TYPED-MAPPING-BOUNDS-MISSING",
    "typed mapping lower bound must cover the complete admitted domain",
)
require(
    r"override\s+def\s+highestBound:\s*BigInt\s*=\s*exactValues\.max",
    mapping,
    "TYPED-MAPPING-BOUNDS-MISSING",
    "typed mapping upper bound must cover the complete admitted domain",
)
if mapping.count('concreteAddress("') < 3:
    fail(
        "TYPED-MAPPING-CONCRETE-GUARD-MISSING",
        "typed mapping hit(BigInt), randomPick and foreach must all fail closed",
    )
require(
    r"ElabIntSingleMapping\s*\(\s*projectedAddress\s*\+\s*ElabInt\.fromBigInt\(addressOffset\)\s*\)",
    mapping,
    "TYPED-MAPPING-OFFSET-MISSING",
    "mapping offsets must retain typed arithmetic",
)
for code in (
    "BUS-ADDRESS-DOMAIN-NEGATIVE",
    "BUS-ADDRESS-ALIGNMENT-INVALID",
    "BUS-ADDRESS-UNALIGNED",
    "BUS-ADDRESS-WIDTH-INSUFFICIENT",
):
    require(
        re.escape(code),
        mapping,
        "TYPED-MAPPING-BUS-VALIDATION-MISSING",
        f"typed mapping is missing stable validation {code}",
    )

require(
    r"private\s+def\s+requireTypedAddress\s*\(\s*address:\s*ElabInt\s*\)\s*:\s*Unit",
    factory,
    "FACTORY-TYPED-ADDRESS-GUARD-MISSING",
    "BusSlaveFactory typed null-address guard is missing",
)
require(
    r"protected\s+def\s+typedAddressAlignmentBytes:\s*Int\s*=\s*1",
    factory,
    "FACTORY-TYPED-ALIGNMENT-POLICY-MISSING",
    "BusSlaveFactory must default typed mappings to full byte-address decoding",
)
require(
    r"alignmentBytes\s*=\s*typedAddressAlignmentBytes",
    factory,
    "FACTORY-TYPED-ALIGNMENT-POLICY-MISSING",
    "typed mapping validation must use the factory-selected alignment",
)
require(
    r"val\s+mapping\s*=\s*validatedTypedAddress\(address,\s*readAddress\(\)\)"
    r"\s*if\s*\(address\.isConcrete\)\s*read\s*\(\s*that,\s*BigInt\(address\.witness\),"
    r"\s*bitOffset,\s*documentation\s*\)\s*else\s*\{\s*readPrimitive\s*\("
    r".*?address\s*=\s*mapping",
    factory,
    "FACTORY-TYPED-MAPPING-SELECTION-MISSING",
    "typed read must delegate concrete addresses and retain symbolic mappings",
)
require(
    r"val\s+mapping\s*=\s*validatedTypedAddress\(address,\s*writeAddress\(\)\)"
    r"\s*if\s*\(address\.isConcrete\)\s*write\s*\(\s*that,\s*BigInt\(address\.witness\),"
    r"\s*bitOffset,\s*documentation\s*\)\s*else\s*\{\s*writePrimitive\s*\("
    r".*?address\s*=\s*mapping",
    factory,
    "FACTORY-TYPED-MAPPING-SELECTION-MISSING",
    "typed write must delegate concrete addresses and retain symbolic mappings",
)
require(
    r"class\s+BusSlaveFactoryAddressWrapper.*?override\s+def\s+read"
    r".*?address\s*\+\s*ElabInt\.fromBigInt\(addressOffset\)",
    factory,
    "FACTORY-TYPED-WRAPPER-VALIDATION-MISSING",
    "typed wrapper offsets must be applied before the underlying factory validates them",
)
for name in ("read", "write", "onRead", "onWrite", "readAndWrite"):
    require(
        rf"def\s+{name}(?:\s*\[[^\]]+\])?\s*\([^)]*address:\s*ElabInt",
        factory,
        "FACTORY-TYPED-OVERLOAD-MISSING",
        f"BusSlaveFactory.{name} typed overload is missing",
    )

# Address-normalizing bus families declare only their native low-bit policy;
# they must not grow a local typed decoder or MorphHDL shadow.
reject(
    r"ElabInt|ElabBool|ElabIntSingleMapping|NativeIntShadow|Morph(?:Read|Write|Bus)",
    axi_factory,
    "AXI4-LOCAL-TYPED-SHADOW",
    "Axi4SlaveFactory must use the generic mapping and contain no local typed shadow",
)
for source, label in (
    (axi_factory, "Axi4SlaveFactory"),
    (axi_lite_factory, "AxiLite4SlaveFactory"),
    (tilelink_factory, "TileLink SlaveFactory"),
    (wishbone_factory, "WishboneSlaveFactory"),
):
    require(
        r"override\s+protected\s+def\s+typedAddressAlignmentBytes:\s*Int",
        source,
        "NORMALIZED-ADDRESS-ALIGNMENT-MISSING",
        f"{label} must declare the alignment imposed by its native address normalization",
    )
require(
    r"typedAddressAlignmentBytes:\s*Int\s*=\s*bus\.config\.dataWidth\s*/\s*8",
    axi_factory,
    "NORMALIZED-ADDRESS-ALIGNMENT-MISSING",
    "Axi4SlaveFactory alignment must match its native data-word mask",
)
require(
    r"typedAddressAlignmentBytes:\s*Int\s*=\s*bus\.config\.dataWidth\s*/\s*8",
    axi_lite_factory,
    "NORMALIZED-ADDRESS-ALIGNMENT-MISSING",
    "AxiLite4SlaveFactory alignment must match its native data-word mask",
)
require(
    r"typedAddressAlignmentBytes:\s*Int\s*=\s*1\s*<<\s*bus\.p\.dataBytesLog2Up",
    tilelink_factory,
    "NORMALIZED-ADDRESS-ALIGNMENT-MISSING",
    "TileLink alignment must match the exact low-bit shift in its native decoder",
)
require(
    r"typedAddressAlignmentBytes:\s*Int\s*=\s*1\s*<<\s*log2Up\s*\(",
    wishbone_factory,
    "NORMALIZED-ADDRESS-ALIGNMENT-MISSING",
    "Wishbone alignment must match the exact low-bit shift in byteAddress",
)

# Delayed factories must consume optimized concrete mappings plus only the
# exact typed single-address mapping added through BusSlaveFactory calls.
require(
    r"if\s+mapping\.isInstanceOf\[ElabIntSingleMapping\].*?mapping\.hit\(address\)",
    bram_factory,
    "BRAM-GENERIC-MAPPING-MISSING",
    "BRAM reads must decode typed mappings against the registered read address",
)
require(
    r"for\s*\(\(address,\s*jobs\)\s*<-\s*elementsPerAddress\s+if\s+"
    r"address\.isInstanceOf\[SingleMapping\]\s*\|\|\s*"
    r"address\.isInstanceOf\[ElabIntSingleMapping\]\).*?"
    r"address\.hit\(bus\.addr\)",
    bram_factory,
    "BRAM-GENERIC-MAPPING-MISSING",
    "BRAM writes must decode concrete and typed mappings against the live write address",
)
require(
    r"if\s+mapping\.isInstanceOf\[spinal\.lib\.bus\.misc\.ElabIntSingleMapping\].*?"
    r"mapping\.hit\(addressDelay\)",
    ahb_factory,
    "AHB-GENERIC-MAPPING-MISSING",
    "AHB delayed transactions must decode typed mappings",
)

# Application proof sources use ordinary native APIs, not a production wrapper
# or explicit witness extraction.
for source, label in ((fixture, "library fixture"), (axi_fixture, "AXI4 fixture")):
    reject(
        r"Morph(?:Counter|Stream|Flow|Mem|Fifo)|NativeIntShadow|\.witness\b|constantInt\b",
        source,
        "FORBIDDEN-PROOF-SURFACE",
        f"{label} uses a wrapper or concrete witness",
    )
require(r"package\s+nativeapplication", fixture, "FIXTURE-PACKAGE", "application fixture must stay outside MorphHDL packages")
require(r"import\s+spinal\.core\._", fixture, "FIXTURE-IMPORT", "application fixture must use ordinary spinal.core imports")
require(r"import\s+spinal\.lib\._", fixture, "FIXTURE-IMPORT", "application fixture must use ordinary spinal.lib imports")
require(r"\.pipelined\s*\(", fixture, "FIXTURE-PIPELINE", "application fixture must exercise typed Stream pipeline control")
require(r"\.m2sPipe\s*\(", fixture, "FIXTURE-PIPELINE", "application fixture must exercise typed Flow pipeline control")
require(r"\.queueWithOccupancy\s*\(", fixture, "FIXTURE-QUEUE", "application fixture must exercise typed Stream queue depth")
require(r"\.queueWithAvailability\s*\(", fixture, "FIXTURE-QUEUE", "application fixture must exercise typed Flow queue depth")
require(r"Counter\s*\(", fixture, "FIXTURE-COUNTER", "application fixture must exercise native Counter")
require(r"Mem\s*\(", fixture, "FIXTURE-MEMORY", "application fixture must exercise native Mem")
require(r"Axi4SlaveFactory\s*\(", axi_fixture, "AXI4-REAL-FACTORY", "AXI4 proof must use the real native factory")
require(r"Apb3SlaveFactory\s*\(", axi_fixture, "APB3-REAL-FACTORY", "address-policy proof must use the real APB3 factory")
require(r"BRAMSlaveFactory\s*\(", axi_fixture, "BRAM-REAL-FACTORY", "generic-mapping proof must use the real BRAM factory")
require(r"AhbLite3SlaveFactory\s*\(", axi_fixture, "AHB-REAL-FACTORY", "generic-mapping proof must use the real AHB factory")
for call in (
    r"factory\.write\s*\([^,]+,\s*offset\s*\)",
    r"factory\.read\s*\([^,]+,\s*offset\s*\)",
    r"factory\.readAndWrite\s*\([^,]+,\s*offset\s*\+\s*4\s*\)",
    r"factory\.onRead\s*\(\s*eventOffset\s*\)",
    r"factory\.onWrite\s*\(\s*eventOffset\s*\)",
):
    require(call, axi_fixture, "AXI4-TYPED-COVERAGE", "AXI4 proof is missing a required typed register-map call")
PY

  printf 'Increment 57 broad native library migration boundary passed.\n'
}

case "${1:-}" in
  ''|--check)
    check_boundary
    ;;
  --self-test)
    temporary="$(mktemp -d)"
    trap 'rm -rf -- "$temporary"' EXIT

    cp "$stream_source" "$temporary/good-stream.scala"
    MORPHDL_BROAD_NATIVE_STREAM_SOURCE="$temporary/good-stream.scala" \
      "$0" --check >/dev/null

    sed '0,/ElabControl.requireCondition(/s//ElabControl.acceptCondition(/' \
      "$stream_source" > "$temporary/missing-legality.scala"
    if MORPHDL_BROAD_NATIVE_STREAM_SOURCE="$temporary/missing-legality.scala" \
      "$0" --check >"$temporary/legality.stdout" 2>"$temporary/legality.stderr"; then
      fail SELF-TEST-ACCEPTED 'missing Stream domain legality proof passed'
    fi
    grep -Fq 'MORPH-BROAD-NATIVE-MIGRATION-STREAM-LEGALITY-GUARD-MISSING' \
      "$temporary/legality.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'legality mutation did not report its stable diagnostic'

    cp "$stream_source" "$temporary/typed-cdc.scala"
    printf '\ntrait ForbiddenTypedCdc { def ccToggle(pushClock: ClockDomain, popClock: ClockDomain, stages: ElabInt): Unit }\n' \
      >> "$temporary/typed-cdc.scala"
    if MORPHDL_BROAD_NATIVE_STREAM_SOURCE="$temporary/typed-cdc.scala" \
      "$0" --check >"$temporary/cdc.stdout" 2>"$temporary/cdc.stderr"; then
      fail SELF-TEST-ACCEPTED 'excluded typed toggle CDC mutation passed'
    fi
    grep -Fq 'MORPH-BROAD-NATIVE-MIGRATION-EXCLUDED-TYPED-SURFACE' \
      "$temporary/cdc.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'typed toggle CDC mutation did not report its stable diagnostic'

    sed '0,/when(mapping.hit(address))/s//when(False)/' \
      "$bram_factory_source" > "$temporary/missing-bram-generic.scala"
    if MORPHDL_BROAD_NATIVE_BRAM_FACTORY_SOURCE="$temporary/missing-bram-generic.scala" \
      "$0" --check >"$temporary/bram.stdout" 2>"$temporary/bram.stderr"; then
      fail SELF-TEST-ACCEPTED 'missing BRAM generic read decoder mutation passed'
    fi
    grep -Fq 'MORPH-BROAD-NATIVE-MIGRATION-BRAM-GENERIC-MAPPING-MISSING' \
      "$temporary/bram.stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'BRAM decoder mutation did not report its stable diagnostic'

    printf 'Increment 57 broad native library migration self-test passed.\n'
    ;;
  *)
    fail ARGUMENT "unknown argument: $1"
    ;;
esac
