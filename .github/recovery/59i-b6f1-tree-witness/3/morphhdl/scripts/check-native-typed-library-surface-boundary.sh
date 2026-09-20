#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

fixture="${MORPHDL_NATIVE_TYPED_SURFACE_FIXTURE:-morphhdl/src/test/scala/nativeapplication/NativeTypedLibraryCallSurfaceFixture.scala}"

fail() {
  local code="$1"
  shift
  printf 'MORPH-NATIVE-TYPED-SURFACE-%s: %s\n' "$code" "$*" >&2
  exit 1
}

require_text() {
  local path="$1"
  local text="$2"
  local code="$3"
  grep -Fq "$text" "$path" ||
    fail "$code" "$path is missing required source text: $text"
}

check_surface() {
  test -f "$fixture" || fail FIXTURE-MISSING "application fixture is missing: $fixture"

  require_text "$fixture" 'package nativeapplication' FIXTURE-PACKAGE
  require_text "$fixture" 'import spinal.core._' CORE-IMPORT-MISSING
  require_text "$fixture" 'import spinal.lib._' LIB-IMPORT-MISSING
  require_text "$fixture" \
    'import morphhdl.frontend.{formalParam, HdlBool, HdlInt}' \
    PARAMETER-IMPORT-MISSING

  local forbidden
  forbidden='import[[:space:]]+morphhdl\.frontend\._|morphhdl\.frontend\.(Bits|UInt|SInt|HardType|Reg|Vec|Mem|Stream|Flow|StreamFifo|cloneOf)|Morph(Counter|Stream|Flow)|\.(asElabInt|asElabBool|witness|constantInt)\b|NativeIntShadow|ExternalNativeInt|compilerTrackArgument|componentClassName|definitionName.*match|emittedName.*match|source(File|Path).*match'
  if grep -En "$forbidden" "$fixture" >&2; then
    fail FORBIDDEN-SURFACE \
      "application fixture uses a wrapper, witness, or runtime provenance reconstruction"
  fi

  require_text "$fixture" 'val counter: Counter = Counter(depth, increment)' COUNTER-CALL-MISSING
  require_text "$fixture" 'val stream: Stream[Bits] = Stream(Bits(width bits))' STREAM-CALL-MISSING
  require_text "$fixture" 'val flow: Flow[Bits] = Flow(Bits(width bits))' FLOW-CALL-MISSING
  require_text "$fixture" \
    'val memory: Mem[Bits] = Mem(HardType(Bits(width bits)), depth)' \
    MEM-CALL-MISSING
  require_text "$fixture" 'val vector: Vec[Bits] = Vec(Bits(width bits), depth)' VEC-CALL-MISSING
  require_text "$fixture" 'val child: Child = new Child(width)' HIERARCHY-CALL-MISSING
  require_text "$fixture" 'private val width = formalParam(' FORMAL-BINDING-MISSING
  require_text "$fixture" 'def integer(value: Int)' INTEGER-LITERAL-LANE-MISSING
  require_text "$fixture" 'def integer(value: ElabInt)' INTEGER-TYPED-LANE-MISSING
  require_text "$fixture" 'def boolean(value: Boolean)' BOOLEAN-LITERAL-LANE-MISSING
  require_text "$fixture" 'def boolean(value: ElabBool)' BOOLEAN-TYPED-LANE-MISSING

  local hdl_int=frontend/src/main/scala/morphhdl/frontend/HdlInt.scala
  local hdl_bool=frontend/src/main/scala/morphhdl/frontend/HdlBool.scala
  local bridge=frontend/src/main/scala/morphhdl/frontend/StructuralExpressionBridge.scala
  require_text "$hdl_int" 'implicit def hdlIntToElabInt[A]' INTEGER-BRIDGE-MISSING
  require_text "$hdl_int" 'target: spinal.core.ElabInt =:= A' INTEGER-TARGET-GUARD-MISSING
  require_text "$hdl_bool" 'def asElabBool: spinal.core.ElabBool' BOOLEAN-BRIDGE-MISSING
  require_text "$hdl_bool" 'implicit def hdlBoolToElabBool[A]' BOOLEAN-IMPLICIT-MISSING
  require_text "$hdl_bool" 'target: spinal.core.ElabBool =:= A' BOOLEAN-TARGET-GUARD-MISSING
  require_text "$bridge" \
    'value.parameters.size + value.booleanParameters.size != 1' \
    BOOLEAN-ROOT-EVIDENCE-MISSING

  python3 - "$hdl_int" "$hdl_bool" <<'PY'
import re
import sys
from pathlib import Path

source = "\n".join(Path(path).read_text(encoding="utf-8") for path in sys.argv[1:])
reverse = re.compile(
    r"implicit\s+def\s+\w+[^=]{0,600}?\([^)]*Hdl(?:Int|Bool)[^)]*\)"
    r"[^=]{0,300}?:\s*(?:scala\.)?(?:Int|Boolean)\b",
    re.DOTALL,
)
if reverse.search(source):
    raise SystemExit(
        "MORPH-NATIVE-TYPED-SURFACE-REVERSE-CONVERSION: "
        "symbolic Hdl values must not convert implicitly to Int or Boolean"
    )
PY

  printf 'Increment 56 native typed library-call surface boundary passed.\n'
}

case "${1:-}" in
  '')
    check_surface
    ;;
  --check)
    check_surface
    ;;
  --self-test)
    temporary="$(mktemp -d)"
    trap 'rm -rf -- "$temporary"' EXIT
    cp "$fixture" "$temporary/good.scala"
    MORPHDL_NATIVE_TYPED_SURFACE_FIXTURE="$temporary/good.scala" \
      "$0" --check >/dev/null
    cp "$fixture" "$temporary/rejected.scala"
    printf '\nobject ForbiddenFactory { val value = morphhdl.frontend.Stream }\n' \
      >> "$temporary/rejected.scala"
    if MORPHDL_NATIVE_TYPED_SURFACE_FIXTURE="$temporary/rejected.scala" \
      "$0" --check >"$temporary/stdout" 2>"$temporary/stderr"; then
      fail SELF-TEST-ACCEPTED 'forbidden frontend Stream factory mutation passed'
    fi
    grep -Fq 'MORPH-NATIVE-TYPED-SURFACE-FORBIDDEN-SURFACE' \
      "$temporary/stderr" ||
      fail SELF-TEST-DIAGNOSTIC 'forbidden fixture did not report the stable diagnostic'
    printf 'Increment 56 native typed library-call surface self-test passed.\n'
    ;;
  *)
    fail ARGUMENT "unknown argument: $1"
    ;;
esac
