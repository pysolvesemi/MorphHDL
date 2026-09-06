#!/usr/bin/env bash
set -euo pipefail

mode=${1:-}
scala_version=${2:-}

require_tools() {
  command -v iverilog
  command -v vvp
  command -v verilator
  command -v yosys
  command -v sby
  command -v yices-smt2
  yosys_version="$(yosys -V)"
  grep -Eq '^Yosys 0\.41([ +]|$)' <<<"$yosys_version"
  sby -h 2>&1 | grep -q 'usage: sby'
}

source_gate() {
  base=${INC59_BASE_SHA:-}
  if [[ -z "$base" ]]; then
    git fetch --no-tags origin parameterized-verilog
    base="$(git rev-parse origin/parameterized-verilog)"
  fi
  test "$(git rev-parse --verify "$base^{commit}")" = "$base"
  git merge-base --is-ancestor "$base" HEAD

  python3 -m py_compile \
    morphhdl/scripts/check-increment-59-blackbox-generics.py \
    morphhdl/scripts/generate-increment-59-blackbox-stubs.py \
    morphhdl/scripts/check-native-source-preservation.py \
    morphhdl/scripts/check-production-retirement.py \
    morphhdl/scripts/check-typed-layering-ir.py
  python3 morphhdl/scripts/check-increment-59-blackbox-generics.py --self-test
  python3 morphhdl/scripts/check-increment-59-blackbox-generics.py --repo-root .
  python3 morphhdl/scripts/check-native-source-preservation.py --self-test
  python3 morphhdl/scripts/check-native-source-preservation.py
  python3 morphhdl/scripts/check-production-retirement.py
  python3 morphhdl/scripts/check-typed-layering-ir.py

  scope=morphhdl/contracts/increment-59-source-scope.txt
  todo=docs/morphhdl/parameterized-verilog-todo.md
  test -s "$scope"
  test "$(cat "$scope")" = "$(LC_ALL=C sort -u "$scope")"
  if grep -Eq '^[[:space:]]*$|^#|^/' "$scope"; then
    printf '%s\n' 'Increment 59 scope must contain only sorted repository-relative paths' >&2
    exit 1
  fi
  while IFS= read -r path; do test -e "$path"; done < "$scope"
  grep -Fqx -- '- [x] **Increment 59 — Typed BlackBox parameter and generic binding**' "$todo"
  grep -Fqx '  **Dependencies:** Increment 58 implemented and merged.' "$todo"

  test ! -e .github/increment59-source-validation.txt
  test ! -e .github/workflows/increment-59-export.yml
  test ! -e .github/workflows/increment-59-gates-v2.yml
  test ! -e .github/workflows/increment-59-bootstrap-v5.yml
  test ! -e .github/workflows/increment-59-bootstrap-v6.yml
  test ! -e .github/workflows/increment-59-finalize.yml
  test ! -e .github/workflows/increment-59-finalize-v2.yml
  test ! -e .github/scripts/inc59-complete-v6.py
  test ! -e .github/scripts/inc59-complete-v6.sh

  actual="$(git diff --no-renames --name-only --diff-filter=ACMRTD \
    "$base"...HEAD \
    | awk '$0 != "docs/morphhdl/parameterized-verilog-todo.md"' \
    | LC_ALL=C sort -u)"
  expected="$(cat "$scope")"
  if [[ "$actual" != "$expected" ]]; then
    printf '%s\n' 'Unexpected Increment 59 file inventory' >&2
    printf '%s\n' '--- expected ---' "$expected" >&2
    printf '%s\n' '--- actual ---' "$actual" >&2
    exit 1
  fi
  git diff --check "$base"...HEAD
}

scala_gate() {
  test -n "$scala_version"
  sbt -batch "++$scala_version" compile Test/compile
  sbt -batch "++$scala_version" \
    "morph/testOnly morphhdl.TypedBlackBoxGenericBindingTests morphhdl.NativeTypedLibraryCallSurfaceTests morphhdl.MorphSingleSourceVerilogTests"
  sbt -batch "++$scala_version" frontend/test
  sbt -batch "++$scala_version" morphir/test
  sbt -batch "++$scala_version" morph/test
  sbt -batch "++$scala_version" \
    "tester/testOnly spinal.lib.CounterTester spinal.lib.SpinalSimStreamFifoTester spinal.lib.SpinalSimStreamFifoCCTester spinal.lib.SpinalSimStreamWidthAdapterTester"
}

strict_gate() {
  test -n "$scala_version"
  require_tools
  work="target/morphhdl/typed-blackbox-$scala_version"
  mkdir -p "$work"
  sbt -batch "++$scala_version" \
    "morph/Test/runMain morphhdl.TypedBlackBoxGenericArtifactGenerator parameterized ${PWD}/$work/typed_blackbox.v"
  python3 morphhdl/scripts/check-increment-59-blackbox-generics.py \
    --artifact "$work/typed_blackbox.v"
  python3 morphhdl/scripts/generate-increment-59-blackbox-stubs.py \
    "$work/typed_blackbox.v" "$work/external_stubs.v"

  iverilog -g2001 -Wall -Wimplicit \
    -s TypedBlackBoxGenericTop \
    -o "$work/default.vvp" \
    "$work/external_stubs.v" "$work/typed_blackbox.v"
  iverilog -g2001 -Wall -Wimplicit \
    -P TypedBlackBoxGenericTop.WIDTH=11 \
    -P TypedBlackBoxGenericTop.LATENCY=3 \
    -P TypedBlackBoxGenericTop.ENABLE=0 \
    -s TypedBlackBoxGenericTop \
    -o "$work/override.vvp" \
    "$work/external_stubs.v" "$work/typed_blackbox.v"
  verilator --lint-only --language 1364-2001 -Wall -Wno-fatal \
    --top-module TypedBlackBoxGenericTop \
    "$work/external_stubs.v" "$work/typed_blackbox.v"
  yosys -q -p "read_verilog -noautowire $work/external_stubs.v $work/typed_blackbox.v; hierarchy -check -top TypedBlackBoxGenericTop; proc; opt; check -assert; synth -top TypedBlackBoxGenericTop; check -assert"
  yosys -q -p "read_verilog -noautowire $work/external_stubs.v $work/typed_blackbox.v; chparam -set WIDTH 11 -set LATENCY 3 -set ENABLE 0 TypedBlackBoxGenericTop; hierarchy -check -top TypedBlackBoxGenericTop; proc; opt; check -assert; synth -top TypedBlackBoxGenericTop; check -assert"
}

formal_gate() {
  test -n "$scala_version"
  require_tools
  root="${PWD}/formal-artifacts/scala-$scala_version"
  export MORPHDL_RUN_TYPED_BLACKBOX_FORMAL_EQUIVALENCE=1
  export MORPHDL_TYPED_BLACKBOX_FORMAL_WORKSPACE="$root/typed-blackbox"
  export MORPHDL_RUN_STREAMFIFOCC_CDC_PROOF=1
  export MORPHDL_STREAMFIFOCC_CDC_WORKSPACE="$root/streamfifocc-cdc"
  export MORPHDL_RUN_TYPED_VEC_FORMAL_EQUIVALENCE=1
  export MORPHDL_TYPED_VEC_FORMAL_WORKSPACE="$root/vec"
  export MORPHDL_RUN_TYPED_PRIMITIVE_CLOSURE_FORMAL_EQUIVALENCE=1
  export MORPHDL_TYPED_PRIMITIVE_FORMAL_WORKSPACE="$root/closure"
  export MORPHDL_RUN_STREAMFIFO_FORMAL_EQUIVALENCE=1
  export MORPHDL_STREAMFIFO_FORMAL_WORKSPACE="$root/streamfifo"
  export MORPHDL_RUN_NATIVE_LIBRARY_MIGRATION_FORMAL_EQUIVALENCE=1
  export MORPHDL_NATIVE_LIBRARY_MIGRATION_FORMAL_WORKSPACE="$root/library-migration"
  export MORPHDL_RUN_NATIVE_AXI4_FORMAL_EQUIVALENCE=1
  export MORPHDL_NATIVE_AXI4_FORMAL_WORKSPACE="$root/axi4"
  export MORPHDL_RUN_STREAMFIFOCC_FORMAL_EQUIVALENCE=1
  export MORPHDL_STREAMFIFOCC_FORMAL_WORKSPACE="$root/streamfifocc-depth-width"
  mkdir -p "$MORPHDL_TYPED_BLACKBOX_FORMAL_WORKSPACE"

  sbt -batch "++$scala_version" \
    "morph/testOnly morphhdl.TypedBlackBoxGenericBindingTests"
  sbt -batch "++$scala_version" \
    "morph/testOnly morphhdl.NativeLibraryMigrationFormalEquivalenceTests morphhdl.NativeAxi4SlaveFactoryFormalEquivalenceTests morphhdl.TypedStreamWidthAdapterFormalEquivalenceTests morphhdl.TypedParameterizedVecFormalEquivalenceTests morphhdl.TypedPrimitiveClosureFormalEquivalenceTests morphhdl.NativeStreamFifoFormalEquivalenceTests morphhdl.NativeStreamFifoCCFormalEquivalenceTests"
  sbt -batch "++$scala_version" \
    "tester/testOnly spinal.lib.CounterFormalTester spinal.tester.scalatest.FormalFifoCCTester"
  find "$root" -type f -print -quit | grep -q .
}

determinism_gate() {
  test -n "$scala_version"
  sbt -batch "++$scala_version" \
    'morph/testOnly morphhdl.TypedBlackBoxGenericBindingTests'
  sbt -batch "++$scala_version" \
    'morph/testOnly morphhdl.MorphVerilogTests -- -z "repeated successful runs are byte-identical"'
  sbt -batch "++$scala_version" \
    'morph/testOnly morphhdl.MorphSingleSourceVerilogTests morphhdl.StreamFifoCompatibilityTests'
  git diff --exit-code -- .
}

case "$mode" in
  source) source_gate ;;
  scala) scala_gate ;;
  strict) strict_gate ;;
  formal) formal_gate ;;
  determinism) determinism_gate ;;
  *)
    printf 'usage: %s {source|scala|strict|formal|determinism} [scala-version]\n' "$0" >&2
    exit 2
    ;;
esac
