#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

formal_test_source="${MORPHDL_STREAMFIFOCC_WIDTH_FORMAL_TEST_SOURCE:-morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCFormalEquivalenceTests.scala}"

fail() {
  local code="$1"
  shift
  printf 'MORPH-NATIVE-STREAMFIFOCC-WIDTH-FORMAL-%s: %s\n' "$code" "$*" >&2
  exit 1
}

require_file() {
  local path="$1"
  test -f "$path" || fail SOURCE-MISSING "formal proof source is missing: $path"
}

check_boundary() {
  require_file "$formal_test_source"

  python3 - "$formal_test_source" <<'PY'
import re
import sys
from pathlib import Path

formal_path = Path(sys.argv[1])
source = formal_path.read_text(encoding="utf-8")


def fail(code: str, message: str) -> None:
    raise SystemExit(
        f"MORPH-NATIVE-STREAMFIFOCC-WIDTH-FORMAL-{code}: {message}"
    )


def require(pattern: str, text: str, code: str, message: str) -> None:
    if re.search(pattern, text, re.MULTILINE | re.DOTALL) is None:
        fail(code, message)


def reject(pattern: str, text: str, code: str, message: str) -> None:
    if re.search(pattern, text, re.MULTILINE | re.DOTALL) is not None:
        fail(code, message)


def region(start: str, end: str, code: str, label: str) -> str:
    match = re.search(
        rf"{start}(?P<body>.*?){end}", source, re.MULTILINE | re.DOTALL
    )
    if match is None:
        fail(code, f"{label} must remain separately inspectable")
    return match.group("body")


def compact(text: str) -> str:
    return re.sub(r"\s+", "", text)


# This is a separate post-57a proof increment. Keep every generated module,
# preparation artifact, miter and workspace visibly owned by 57b so a stale
# 57a artifact cannot satisfy the new workflow by name.
reject(
    r"57a",
    source,
    "STALE-57A-IDENTIFIER",
    "the width-formal fixture must not reuse Increment 57a identifiers",
)
for pattern, label in (
    (r'"NativeStreamFifoCC57bTypedBuffered"', "typed buffered top"),
    (r'"NativeStreamFifoCC57bTypedDirect"', "typed direct top"),
    (r'NativeStreamFifoCC57bConcreteW\$\{width\}D\$\{depth\}', "concrete wrapper"),
    (r'NativeStreamFifoCC57bConcreteCoreW\$\{width\}D\$\{depth\}', "native concrete core"),
    (r'"native_streamfifocc_57b_parameterized\.v"', "parameterized RTL"),
    (r'streamfifocc_57b_candidate_w\$\{width\}_d\$\{depth\}', "prepared candidate"),
    (r'streamfifocc_57b_reference_w\$\{width\}_d\$\{depth\}', "prepared reference"),
    (r'streamfifocc_57b_miter_', "formal miter"),
    (r'morphhdl-streamfifocc-57b-formal-', "formal workspace"),
):
    require(
        pattern,
        source,
        "57B-IDENTIFIER-MISSING",
        f"the {label} must carry its Increment 57b identity",
    )


# Both dimensions must enter the real native StreamFifoCC through their typed
# data/geometry surfaces. The independent reference must stay on ordinary Int
# construction and ordinary SpinalVerilog elaboration.
fixture = region(
    r"private\s+object\s+NativeStreamFifoCCFormalEquivalenceFixture\s*\{",
    r"\n\}\s*\n\s*/\*\*\s*Relational proof",
    "FIXTURE-MISSING",
    "the typed/concrete proof fixture",
)
require(
    r"final\s+class\s+TypedTop\s*\(\s*width:\s*ElabInt,\s*depth:\s*ElabInt,\s*bufferedPopReset:\s*Boolean\s*\)",
    fixture,
    "TYPED-WIDTH-INGRESS-MISSING",
    "the typed proof top must accept authoritative ElabInt width and depth",
)
for pattern, label in (
    (r"pushPayload\s*=\s*in\s+Bits\s*\(\s*width\s+bits\s*\)", "push payload"),
    (r"popPayload\s*=\s*out\s+Bits\s*\(\s*width\s+bits\s*\)", "pop payload"),
    (r"HardType\s*\(\s*Bits\s*\(\s*width\s+bits\s*\)\s*\)", "native FIFO payload"),
):
    require(
        pattern,
        fixture,
        "TYPED-WIDTH-INGRESS-MISSING",
        f"the typed {label} must retain WIDTH symbolically",
    )

require(
    r"final\s+class\s+ConcreteTop\s*\(\s*width:\s*Int,\s*depth:\s*Int,\s*bufferedPopReset:\s*Boolean\s*\)",
    fixture,
    "INDEPENDENT-REFERENCE-MISSING",
    "the reference top must accept only ordinary Int width and depth",
)
require(
    r"val\s+fifo\s*=\s*new\s+spinal\.lib\.StreamFifoCC\s*\(\s*HardType\s*\(\s*Bits\s*\(\s*width\s+bits\s*\)\s*\)\s*,\s*depth\s*,\s*pushClock\s*,\s*popClock\s*,\s*bufferedPopReset\s*\)",
    fixture,
    "INDEPENDENT-REFERENCE-MISSING",
    "the reference must instantiate the actual native Int StreamFifoCC",
)

# Pin the complete witness domain and exact Cartesian proof construction.
if compact(source).count("privatevalWidths=Vector(1,5,8,32)") != 1:
    fail(
        "WIDTH-WITNESS-VECTOR-MISSING",
        "the exact WIDTH witness vector must be {1, 5, 8, 32}",
    )
if compact(source).count("privatevalDepths=Vector(2,4,8,16)") != 1:
    fail(
        "DEPTH-WITNESS-VECTOR-MISSING",
        "the exact DEPTH witness vector must be {2, 4, 8, 16}",
    )
if compact(source).count("privatevalResetModes=Vector(false,true)") != 1:
    fail(
        "RESET-MODES-MISSING",
        "both direct and buffered reset modes must be present exactly once",
    )
clock_ratios = (
    'privatevalClockRatios=Vector('
    'ClockRatio("push_2x_pop",pushPhaseBit=0,popPhaseBit=1),'
    'ClockRatio("pop_2x_push",pushPhaseBit=1,popPhaseBit=0))'
)
if compact(source).count(clock_ratios) != 1:
    fail(
        "CLOCK-RATIOS-MISSING",
        "the exact push-fast and pop-fast asynchronous clock schedules are required",
    )

matrix = (
    "privatevalConfigurations=for{width<-Widthsdepth<-Depths"
    "buffered<-ResetModesratio<-ClockRatios}yield"
    "Configuration(width,depth,buffered,ratio)"
)
if compact(source).count(matrix) != 1:
    fail(
        "CARTESIAN-MATRIX-MISSING",
        "positive configurations must be the unfiltered WIDTH x DEPTH x reset x ratio Cartesian product",
    )
require(
    r"assert\s*\(\s*Configurations\.size\s*==\s*64\s*\).*?assert\s*\(\s*Configurations\.distinct\.size\s*==\s*Configurations\.size\s*\)",
    source,
    "MATRIX-UNIQUENESS-ASSERTION-MISSING",
    "an ordinary test must pin 64 unique positive configurations",
)
require(
    r"Configurations\.count\s*\(\s*_\.width\s*==\s*8\s*\)\s*==\s*Depths\.size\s*\*\s*ResetModes\.size\s*\*\s*ClockRatios\.size",
    source,
    "WIDTH8-REGRESSION-COVERAGE-MISSING",
    "the prior WIDTH=8 CDC matrix must remain wholly covered",
)

generate = region(
    r"private\s+def\s+generateDuts\s*\(\s*directory:\s*Path\s*\):\s*GeneratedDuts\s*=\s*\{",
    r"\n\s*private\s+def\s+validateGeneratedDuts\s*\(",
    "GENERATION-BUILDER-MISSING",
    "the independent DUT generation builder",
)
require(
    r"for\s*\{\s*width\s*<-\s*Widths\s*depth\s*<-\s*Depths\s*buffered\s*<-\s*ResetModes\s*\}.*?SpinalVerilog\s*\(\s*config\s*\)\s*\{\s*new\s+ConcreteTop\s*\(\s*width\s*,\s*depth\s*,\s*buffered\s*\)\s*\}.*?\(\s*width\s*,\s*depth\s*,\s*buffered\s*\)\s*->",
    generate,
    "INDEPENDENT-REFERENCE-MISSING",
    "every width/depth/reset reference must be independently emitted with SpinalVerilog",
)
if generate.count("SpinalVerilog(config)") != 1:
    fail(
        "INDEPENDENT-REFERENCE-MISSING",
        "the ordinary reference generation site must remain singular and explicit",
    )
if generate.count("MorphVerilog(config)") != 1:
    fail(
        "TYPED-GENERATION-MISSING",
        "the typed parameterized generation site must remain singular and explicit",
    )
if generate.index("SpinalVerilog(config)") >= generate.index("MorphVerilog(config)"):
    fail(
        "REFERENCE-INDEPENDENCE-ORDER-MISSING",
        "ordinary references must be elaborated before typed capture begins",
    )
require(
    r"HdlInt\s*\.param\s*\(\s*\"WIDTH\"\s*,\s*default\s*=\s*BigInt\s*\(\s*8\s*\)\s*,\s*min\s*=\s*BigInt\s*\(\s*1\s*\)\s*,\s*max\s*=\s*BigInt\s*\(\s*32\s*\)\s*\)\s*\.asElabInt",
    generate,
    "WIDTH-DOMAIN-MISSING",
    "the typed WIDTH formal must retain the exact legal domain 1 through 32",
)
require(
    r"HdlInt\s*\.param\s*\(\s*\"DEPTH\"\s*,\s*default\s*=\s*BigInt\s*\(\s*8\s*\)\s*,\s*min\s*=\s*BigInt\s*\(\s*2\s*\)\s*,\s*max\s*=\s*BigInt\s*\(\s*16\s*\)\s*\)\s*\.asElabInt",
    generate,
    "DEPTH-DOMAIN-MISSING",
    "the typed DEPTH formal must retain the exact declared domain 2 through 16",
)
require(
    r"MorphVerilog\s*\(\s*config\s*\)\s*\{\s*new\s+TypedTop\s*\(\s*width\s*,\s*depth\s*,\s*buffered\s*\)\s*\}",
    generate,
    "TYPED-GENERATION-MISSING",
    "WIDTH and DEPTH must both reach the typed proof top",
)

validation = region(
    r"private\s+def\s+validateGeneratedDuts\s*\(\s*generated:\s*GeneratedDuts\s*\):\s*Unit\s*=\s*\{",
    r"\n\s*private\s+def\s+prepareDuts\s*\(",
    "GENERATED-VALIDATION-MISSING",
    "the generated DUT structural validation",
)
require(
    r"generated\.concreteByConfiguration\.toVector\.map\s*\{\s*case\s*\(\s*\(\s*width\s*,\s*depth\s*,\s*buffered\s*\)\s*,\s*path\s*\)\s*=>",
    validation,
    "CONCRETE-GEOMETRY-VALIDATION-MISSING",
    "every concrete WIDTH/DEPTH/reset reference must be structurally inspected",
)
require(
    r"val\s+top\s*=\s*concreteSourceTop\s*\(\s*width\s*,\s*depth\s*,\s*buffered\s*\).*?val\s+core\s*=\s*concreteCoreSourceTop\s*\(\s*width\s*,\s*depth\s*,\s*buffered\s*\).*?source\.contains\s*\(\s*s\"module \$top\"\s*\).*?source\.contains\s*\(\s*s\"module \$core\"\s*\)",
    validation,
    "CONCRETE-GEOMETRY-VALIDATION-MISSING",
    "each independently emitted concrete top and native core must be present",
)
require(
    r"val\s+topHeader\s*=\s*compact\s*\(\s*moduleHeader\s*\(\s*source\s*,\s*top\s*\)\s*\).*?val\s+coreHeader\s*=\s*compact\s*\(\s*moduleHeader\s*\(\s*source\s*,\s*core\s*\)\s*\).*?val\s+payloadRange\s*=\s*s\"\[\$\{width\s*-\s*1\}:0\]\"",
    validation,
    "CONCRETE-GEOMETRY-VALIDATION-MISSING",
    "concrete payload ranges, including WIDTH=1 as [0:0], must derive from each witness",
)
require(
    r"Vector\s*\(\s*s\"inputwire\$\{payloadRange\}io_pushPayload\"\s*,\s*s\"outputwire\$\{payloadRange\}io_popPayload\"\s*\)\.foreach\s*\(\s*token\s*=>\s*assert\s*\(\s*topHeader\.contains\s*\(\s*token\s*\)\s*,\s*source\s*\)\s*\)",
    validation,
    "CONCRETE-GEOMETRY-VALIDATION-MISSING",
    "the concrete wrapper must validate exact push and pop payload ranges",
)
require(
    r"Vector\s*\(\s*s\"inputwire\$\{payloadRange\}io_push_payload\"\s*,\s*s\"outputwire\$\{payloadRange\}io_pop_payload\"\s*\)\.foreach\s*\(\s*token\s*=>\s*assert\s*\(\s*coreHeader\.contains\s*\(\s*token\s*\)\s*,\s*source\s*\)\s*\)",
    validation,
    "CONCRETE-GEOMETRY-VALIDATION-MISSING",
    "the native concrete core must validate exact push and pop payload ranges",
)
require(
    r"compact\s*\(\s*source\s*\)\.contains\s*\(\s*s\"reg\$\{payloadRange\}ram\[0:\$\{depth\s*-\s*1\}\];\"\s*\)",
    validation,
    "CONCRETE-GEOMETRY-VALIDATION-MISSING",
    "each concrete reference must validate exact RAM word width and depth",
)
require(
    r"concreteSources\.toSet\.size\s*==\s*Widths\.size\s*\*\s*Depths\.size\s*\*\s*ResetModes\.size",
    validation,
    "CONCRETE-GEOMETRY-VALIDATION-MISSING",
    "all 32 independent concrete width/depth/reset sources must be unique",
)
require(
    r"private\s+def\s+concreteCoreSourceTop\s*\(\s*width:\s*Int\s*,\s*depth:\s*Int\s*,\s*buffered:\s*Boolean\s*\):\s*String\s*=\s*s\"[^\"]*ConcreteCoreW\$\{width\}D\$\{depth\}\"\s*\+",
    source,
    "CONCRETE-GEOMETRY-VALIDATION-MISSING",
    "native concrete core identities must distinguish both width and depth",
)

formal_test = region(
    r"test\s*\(\s*\n?\s*\"typed StreamFifoCC is formally equivalent[^\"]*\"\s*\)\s*\{",
    r"\n\s*private\s+def\s+generateDuts\s*\(",
    "FORMAL-TEST-MISSING",
    "the opt-in formal matrix test",
)
require(
    r"Configurations\.foreach\s*\{\s*configuration\s*=>.*?positiveSby\s*\(.*?configuration\.width.*?configuration\.depth.*?configuration\.buffered.*?runSby\s*\(\s*directory\s*,\s*config\s*,\s*expectedStatus\s*=\s*\"PASS\"\s*,\s*requireCounterexample\s*=\s*false\s*\)",
    formal_test,
    "POSITIVE-MATRIX-EXECUTION-MISSING",
    "all 64 Cartesian configurations must execute the positive proof",
)
require(
    r"val\s+mutationConfiguration\s*=\s*Configuration\s*\(\s*width\s*=\s*5\s*,\s*depth\s*=\s*2\s*,\s*buffered\s*=\s*false\s*,\s*ratio\s*=\s*ClockRatios\.head\s*\)",
    formal_test,
    "MUTATION-WITNESS-MISSING",
    "the live mutation must use W5 D2 direct reset with the push-fast clock ratio",
)
require(
    r"mutationSby\s*\(.*?mutationConfiguration\.width.*?mutationConfiguration\.depth.*?mutationConfiguration\.buffered.*?runSby\s*\(\s*directory\s*,\s*mutationConfig\s*,\s*expectedStatus\s*=\s*\"FAIL\"\s*,\s*requireCounterexample\s*=\s*true\s*\)",
    formal_test,
    "MUTATION-EXECUTION-MISSING",
    "the width-sensitive mutation must require a real counterexample",
)

candidate = region(
    r"private\s+def\s+candidatePreparationScript\s*\(",
    r"\n\s*private\s+def\s+referencePreparationScript\s*\(",
    "CANDIDATE-PREPARATION-MISSING",
    "the candidate preparation builder",
)
dual_chparam = (
    "|chparam -set WIDTH $width -set DEPTH $depth "
    "${typedSourceTop(buffered)}"
)
if candidate.count(dual_chparam) != 1:
    fail(
        "DUAL-CHPARAM-MISSING",
        "Yosys must specialize WIDTH and DEPTH together exactly once",
    )
require(
    r"candidatePreparationScript\s*\(\s*Paths\.get\s*\(\s*\"typed\.v\"\s*\)\s*,\s*Paths\.get\s*\(\s*\"candidate\.il\"\s*\)\s*,\s*width\s*=\s*5\s*,\s*depth\s*=\s*2\s*,\s*buffered\s*=\s*buffered\s*\).*?chparam -set WIDTH 5 -set DEPTH 2",
    source,
    "DUAL-CHPARAM-TEST-MISSING",
    "an ordinary test must pin simultaneous W5/D2 specialization",
)

miter = region(
    r"private\s+def\s+equivalenceMiter\s*\(",
    r"\n\s*private\s+def\s+positiveSby\s*\(",
    "MITER-BUILDER-MISSING",
    "the width-generic equivalence miter",
)
require(
    r"val\s+payloadRange\s*=\s*s\"\[\$\{configuration\.width\s*-\s*1\}:0\]\"",
    miter,
    "WIDTH-GENERIC-MITER-MISSING",
    "miter payload ranges must derive from each configuration width",
)
if miter.count("$payloadRange") != 4:
    fail(
        "WIDTH-GENERIC-MITER-MISSING",
        "the input and all three compared payload nets must use the generic range",
    )
require(
    r"val\s+mutationMask\s*=\s*BigInt\s*\(\s*1\s*\)\s*<<\s*\(\s*configuration\.width\s*-\s*1\s*\).*?if\s*\(\s*mutatePopPayload\s*\)\s*s\"\(typed_pop_payload \^ \$\{configuration\.width\}'h\$\{mutationMask\.toString\(16\)\}\)\"\s*else\s*\"typed_pop_payload\"",
    miter,
    "WIDTH-GENERIC-MUTATION-MISSING",
    "the payload mutation literal must scale with configuration width",
)
require(
    r"equivalenceMiter\s*\(\s*Configuration\s*\(\s*width\s*=\s*5\s*,\s*depth\s*=\s*2\s*,\s*buffered\s*=\s*false\s*,\s*ratio\s*=\s*ClockRatios\.head\s*\).*?mutatePopPayload\s*=\s*true\s*\).*?typed_pop_payload\^5'h10",
    source,
    "WIDTH-GENERIC-MUTATION-TEST-MISSING",
    "an ordinary test must pin the W5 width-scaled live mutation",
)
for pattern, label in (
    (r"assert\s*\(\s*reference_push_ready\s*==\s*typed_push_ready\s*\);", "push ready"),
    (r"assert\s*\(\s*reference_pop_valid\s*==\s*typed_pop_valid\s*\);", "pop valid"),
    (r"assert\s*\(\s*reference_push_occupancy\s*==\s*typed_push_occupancy\s*\);", "push occupancy"),
    (r"assert\s*\(\s*reference_pop_occupancy\s*==\s*typed_pop_occupancy\s*\);", "pop occupancy"),
    (r"assert\s*\(\s*reference_pop_payload\s*==\s*typed_pop_payload_compared\s*\);", "valid pop payload"),
):
    require(
        pattern,
        miter,
        "FIVE-COMPARISONS-MISSING",
        f"the miter must compare {label}",
    )
if len(re.findall(r"\bassert\s*\(", miter)) != 5:
    fail(
        "FIVE-COMPARISONS-MISSING",
        "the miter must contain exactly five observable equivalence assertions",
    )
require(
    r"if\s*\(\s*reference_pop_valid\s*&&\s*typed_pop_valid\s*\)\s*\|?\s*assert\s*\(\s*reference_pop_payload\s*==",
    miter,
    "PAYLOAD-VALID-GUARD-MISSING",
    "payload equality must remain guarded by both valid outputs",
)

# Width 8 remains a legal default/witness, but no fixed 8-bit data path may
# survive in either proof leg, preparation, miter, or mutation literal.
for pattern, label in (
    (r"(?:push|pop)Payload\s*=\s*(?:in|out)\s+Bits\s*\(\s*8\s+bits\s*\)", "fixture payload declaration"),
    (r"HardType\s*\(\s*Bits\s*\(\s*8\s+bits\s*\)\s*\)", "FIFO payload type"),
    (r"\[7:0\]\s+(?:push_payload|reference_pop_payload|typed_pop_payload|typed_pop_payload_compared)\b", "miter payload net"),
    (r"\b8'h(?:0*1)\b", "mutation literal"),
):
    reject(
        pattern,
        source,
        "HARDCODED-8BIT-PAYLOAD",
        f"a hardcoded 8-bit formal {label} is forbidden",
    )

positive = region(
    r"private\s+def\s+positiveSby\s*\(",
    r"\n\s*private\s+def\s+mutationSby\s*\(",
    "POSITIVE-BUILDER-MISSING",
    "the positive SBY builder",
)
mutation = region(
    r"private\s+def\s+mutationSby\s*\(",
    r"\n\s*private\s+def\s+runSby\s*\(",
    "MUTATION-BUILDER-MISSING",
    "the mutation SBY builder",
)
if len(re.findall(r"^\s*\|abc lcorr; pdr\s*$", positive, re.MULTILINE)) != 1:
    fail(
        "POSITIVE-ENGINE-MISSING",
        "the positive proof must use exactly one abc lcorr; pdr engine",
    )
if "smtbmc" in positive:
    fail(
        "POSITIVE-ENGINE-MIXED",
        "the positive proof must remain on the scalable PDR engine",
    )
if len(re.findall(r"^\s*\|smtbmc yices\s*$", mutation, re.MULTILINE)) != 1:
    fail(
        "MUTATION-ENGINE-MISSING",
        "the live mutation must use exactly one Yices SMT BMC engine",
    )
if "lcorr;" in mutation or re.search(r"\bpdr\b", mutation) is not None:
    fail(
        "MUTATION-ENGINE-MIXED",
        "the live mutation must remain independent from the positive PDR engine",
    )

run_sby = region(
    r"private\s+def\s+runSby\s*\(",
    r"\n\s*private\s+def\s+runYosys\s*\(",
    "RUNSBY-GATE-MISSING",
    "the SymbiYosys result gate",
)
for pattern, message in (
    (
        r"assert\s*\(\s*exitCode\s*==\s*0\s*,",
        "tool or solver process failures must be rejected",
    ),
    (
        r"assert\s*\(\s*Files\.isRegularFile\s*\(\s*statusFile\s*\)\s*,",
        "a missing SBY status file must be rejected",
    ),
    (
        r"assert\s*\(\s*statusLines\.size\s*==\s*1\s*,",
        "ambiguous SBY status must be rejected",
    ),
    (
        r"assert\s*\(\s*statusTokens\.head\s*==\s*expectedStatus\s*,",
        "the exact expected PASS or FAIL status must be required",
    ),
    (
        r"if\s*\(\s*requireCounterexample\s*\).*?traces\.nonEmpty.*?engineLogs\.contains\s*\(\s*\"Assert failed in\"\s*\)",
        "mutation failure must include a non-empty VCD and assertion diagnostic",
    ),
):
    require(
        pattern,
        run_sby,
        "RUNSBY-GATE-MISSING",
        message,
    )
PY

  printf 'Increment 57b typed StreamFifoCC payload-width formal boundary passed.\n'
}

expect_rejection() {
  local source_path="$1"
  local diagnostic="$2"
  local label="$3"
  local temporary="$4"

  if MORPHDL_STREAMFIFOCC_WIDTH_FORMAL_TEST_SOURCE="$source_path" \
      "$0" --check >"$temporary/$label.stdout" 2>"$temporary/$label.stderr"; then
    fail SELF-TEST-ACCEPTED "$label mutation passed"
  fi
  if ! grep -Fq \
      "MORPH-NATIVE-STREAMFIFOCC-WIDTH-FORMAL-$diagnostic" \
      "$temporary/$label.stderr"; then
    fail SELF-TEST-DIAGNOSTIC \
      "$label mutation did not report its stable $diagnostic diagnostic"
  fi
}

case "${1:-}" in
  ''|--check)
    check_boundary
    ;;
  --self-test)
    temporary="$(mktemp -d)"
    trap 'rm -rf -- "$temporary"' EXIT

    cp "$formal_test_source" "$temporary/good.scala"
    MORPHDL_STREAMFIFOCC_WIDTH_FORMAL_TEST_SOURCE="$temporary/good.scala" \
      "$0" --check >/dev/null

    sed '0,/Widths = Vector(1, 5, 8, 32)/s//Widths = Vector(1, 8, 32)/' \
      "$formal_test_source" > "$temporary/missing-width-corner.scala"
    expect_rejection \
      "$temporary/missing-width-corner.scala" \
      WIDTH-WITNESS-VECTOR-MISSING \
      missing-width-corner \
      "$temporary"

    sed '0,/Depths = Vector(2, 4, 8, 16)/s//Depths = Vector(2, 4, 8)/' \
      "$formal_test_source" > "$temporary/missing-depth-corner.scala"
    expect_rejection \
      "$temporary/missing-depth-corner.scala" \
      DEPTH-WITNESS-VECTOR-MISSING \
      missing-depth-corner \
      "$temporary"

    sed '0,/ResetModes = Vector(false, true)/s//ResetModes = Vector(false)/' \
      "$formal_test_source" > "$temporary/missing-buffered-reset.scala"
    expect_rejection \
      "$temporary/missing-buffered-reset.scala" \
      RESET-MODES-MISSING \
      missing-buffered-reset \
      "$temporary"

    sed '0,/ClockRatio("pop_2x_push", pushPhaseBit = 1, popPhaseBit = 0)/s//ClockRatio("pop_2x_push", pushPhaseBit = 0, popPhaseBit = 1)/' \
      "$formal_test_source" > "$temporary/duplicate-clock-ratio.scala"
    expect_rejection \
      "$temporary/duplicate-clock-ratio.scala" \
      CLOCK-RATIOS-MISSING \
      duplicate-clock-ratio \
      "$temporary"

    sed '0,/max = BigInt(32)/s//max = BigInt(31)/' \
      "$formal_test_source" > "$temporary/narrow-width-domain.scala"
    expect_rejection \
      "$temporary/narrow-width-domain.scala" \
      WIDTH-DOMAIN-MISSING \
      narrow-width-domain \
      "$temporary"

    sed '0,/width <- Widths/s//width <- Widths.take(3)/' \
      "$formal_test_source" > "$temporary/incomplete-cartesian.scala"
    expect_rejection \
      "$temporary/incomplete-cartesian.scala" \
      CARTESIAN-MATRIX-MISSING \
      incomplete-cartesian \
      "$temporary"

    sed '0,/chparam -set WIDTH \$width -set DEPTH \$depth/s//chparam -set DEPTH $depth/' \
      "$formal_test_source" > "$temporary/depth-only-specialization.scala"
    expect_rejection \
      "$temporary/depth-only-specialization.scala" \
      DUAL-CHPARAM-MISSING \
      depth-only-specialization \
      "$temporary"

    sed '/private def equivalenceMiter/,/private def positiveSby/ s/val payloadRange = s"\[\${configuration.width - 1}:0\]"/val payloadRange = "[7:0]"/' \
      "$formal_test_source" > "$temporary/hardcoded-miter-width.scala"
    expect_rejection \
      "$temporary/hardcoded-miter-width.scala" \
      WIDTH-GENERIC-MITER-MISSING \
      hardcoded-miter-width \
      "$temporary"

    sed '/private def validateGeneratedDuts/,/private def prepareDuts/ s/val payloadRange = s"\[\${width - 1}:0\]"/val payloadRange = "[7:0]"/' \
      "$formal_test_source" > "$temporary/frozen-concrete-width-validation.scala"
    expect_rejection \
      "$temporary/frozen-concrete-width-validation.scala" \
      CONCRETE-GEOMETRY-VALIDATION-MISSING \
      frozen-concrete-width-validation \
      "$temporary"

    sed '/private def equivalenceMiter/,/private def positiveSby/ s/assert(reference_pop_payload == typed_pop_payload_compared);/assume(reference_pop_payload == typed_pop_payload_compared);/' \
      "$formal_test_source" > "$temporary/missing-payload-comparison.scala"
    expect_rejection \
      "$temporary/missing-payload-comparison.scala" \
      FIVE-COMPARISONS-MISSING \
      missing-payload-comparison \
      "$temporary"

    sed '/val mutationConfiguration = Configuration(/,/ratio = ClockRatios.head/ s/width = 5/width = 8/' \
      "$formal_test_source" > "$temporary/wrong-mutation-width.scala"
    expect_rejection \
      "$temporary/wrong-mutation-width.scala" \
      MUTATION-WITNESS-MISSING \
      wrong-mutation-width \
      "$temporary"

    sed '/private def positiveSby/,/private def mutationSby/ s/abc lcorr; pdr/abc pdr/' \
      "$formal_test_source" > "$temporary/missing-lcorr.scala"
    expect_rejection \
      "$temporary/missing-lcorr.scala" \
      POSITIVE-ENGINE-MISSING \
      missing-lcorr \
      "$temporary"

    sed '/private def mutationSby/,/private def runSby/ s/smtbmc yices/abc pdr/' \
      "$formal_test_source" > "$temporary/non-yices-mutation.scala"
    expect_rejection \
      "$temporary/non-yices-mutation.scala" \
      MUTATION-ENGINE-MISSING \
      non-yices-mutation \
      "$temporary"

    sed '0,/SpinalVerilog(config)/s//MorphVerilog(config)/' \
      "$formal_test_source" > "$temporary/shared-reference-capture.scala"
    expect_rejection \
      "$temporary/shared-reference-capture.scala" \
      INDEPENDENT-REFERENCE-MISSING \
      shared-reference-capture \
      "$temporary"

    sed '/private def runSby/,/private def runYosys/ s/exitCode == 0/exitCode >= 0/' \
      "$formal_test_source" > "$temporary/accepted-tool-failure.scala"
    expect_rejection \
      "$temporary/accepted-tool-failure.scala" \
      RUNSBY-GATE-MISSING \
      accepted-tool-failure \
      "$temporary"

    sed '0,/57b/s//57a/' \
      "$formal_test_source" > "$temporary/stale-increment-identity.scala"
    expect_rejection \
      "$temporary/stale-increment-identity.scala" \
      STALE-57A-IDENTIFIER \
      stale-increment-identity \
      "$temporary"

    printf 'Increment 57b typed StreamFifoCC payload-width formal boundary self-test passed.\n'
    ;;
  *)
    fail USAGE "usage: $0 [--check|--self-test]"
    ;;
esac
