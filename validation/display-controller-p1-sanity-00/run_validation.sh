#!/usr/bin/env bash
set -euo pipefail

readonly morphhdl_base="81abd25518551b8a452ecf038e409331e646f726"
readonly validation_root="validation/display-controller-p1-sanity-00"
readonly mill_bin="/tmp/p1-sanity-mill"
readonly generation_root="/tmp/p1-sanity-generation"
readonly artifact_root="/tmp/p1-sanity-validation-artifact"

log() {
  printf '\n[p1-sanity] %s\n' "$*"
}

log "prove pinned MorphHDL ancestry and exact copied source blobs"
git merge-base --is-ancestor "${morphhdl_base}" HEAD

test "$(git hash-object tester/src/main/scala/displaycontroller/sanity/DisplayControllerSanityShellConfig.scala)" = a3d61a677dc69e37e7caa6b27350e08daa474e8d
test "$(git hash-object tester/src/main/scala/displaycontroller/sanity/DisplayControllerSanityShell.scala)" = 9be9b449170f6ea8946e267710f6b8e4b4b0e02e
test "$(git hash-object tester/src/main/scala/displaycontroller/sanity/DisplayControllerSanityShellVerilog.scala)" = 04e5d1d131200a95335c9857b17ef749332b971c
test "$(git hash-object ${validation_root}/display-controller-verilog/verif/sanity/tb/axi_lite_master_bfm.sv)" = bb29157886a50b198ac7190e95c53def0ae70b4d
test "$(git hash-object ${validation_root}/display-controller-verilog/verif/sanity/tb/axi_lite_passive_monitor.sv)" = d2990e3c76d84e81a68c5323d05f85515142abb0
test "$(git hash-object ${validation_root}/display-controller-verilog/verif/sanity/tb/tb_display_controller_sanity.sv)" = 8a1f68471b92e9cf967b4017c0349acf09254514
test "$(git hash-object ${validation_root}/display-controller-verilog/verif/sanity/compile.f)" = 6feff5226ee8cfae83b5b08bac884c5ac110098d

log "install pinned Mill bootstrap and open-source RTL tools"
curl --fail --location --retry 3 \
  https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.1.0/mill-dist-1.1.0-mill.sh \
  --output "${mill_bin}"
chmod +x "${mill_bin}"

missing=0
for tool in iverilog vvp verilator yosys; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    missing=1
  fi
done
if [[ "${missing}" -ne 0 ]]; then
  apt-get update
  apt-get install -y iverilog verilator yosys
fi

sbt -batch sbtVersion
"${mill_bin}" --version
iverilog -V | head -n 2
verilator --version
yosys -V

log "compile the complete target source closure with SBT and Mill"
sbt -batch "++2.12.18" "tester/compile" 2>&1 | tee /tmp/p1-sanity-sbt-compile.log
"${mill_bin}" tester[2.12.18].compile 2>&1 | tee /tmp/p1-sanity-mill-compile.log

log "reject every declared illegal profile with both builders"
cases=(
  axil-address-width axil-data-width axis-data-width axis-user-width ppc
  axi-address-width axi-data-width axi-id-width dpi-component-width
  native-hdl-parameters module-name output-path
)
for case_name in "${cases[@]}"; do
  set +e
  sbt_out=$(sbt -batch "++2.12.18" \
    "tester/runMain displaycontroller.sanity.DisplayControllerSanityShellConfigProbe ${case_name}" 2>&1)
  sbt_rc=$?
  set -e
  if [[ "${sbt_rc}" -eq 0 ]] || ! grep -q 'DCS_CONFIG_' <<<"${sbt_out}"; then
    printf '%s\n' "${sbt_out}" >&2
    echo "SBT illegal-profile probe failed: ${case_name}" >&2
    exit 20
  fi

  set +e
  mill_out=$("${mill_bin}" tester[2.12.18].runMain \
    displaycontroller.sanity.DisplayControllerSanityShellConfigProbe \
    "${case_name}" 2>&1)
  mill_rc=$?
  set -e
  if [[ "${mill_rc}" -eq 0 ]] || ! grep -q 'DCS_CONFIG_' <<<"${mill_out}"; then
    printf '%s\n' "${mill_out}" >&2
    echo "Mill illegal-profile probe failed: ${case_name}" >&2
    exit 21
  fi
done

log "generate twice with each builder and prove deterministic output"
rm -rf "${generation_root}"
mkdir -p "${generation_root}"

for run_id in 1 2; do
  rm -rf hw/gen/sanity
  sbt -batch "++2.12.18" \
    "tester/runMain displaycontroller.sanity.DisplayControllerSanityShellVerilog" \
    2>&1 | tee "/tmp/p1-sanity-sbt-generate-${run_id}.log"
  test -f hw/gen/sanity/DisplayControllerSanityShell.v
  cp -a hw/gen/sanity "${generation_root}/sbt-${run_id}"
done

for run_id in 1 2; do
  rm -rf hw/gen/sanity
  "${mill_bin}" tester[2.12.18].runMain \
    displaycontroller.sanity.DisplayControllerSanityShellVerilog \
    2>&1 | tee "/tmp/p1-sanity-mill-generate-${run_id}.log"
  test -f hw/gen/sanity/DisplayControllerSanityShell.v
  cp -a hw/gen/sanity "${generation_root}/mill-${run_id}"
done

diff -ru "${generation_root}/sbt-1" "${generation_root}/sbt-2"
diff -ru "${generation_root}/sbt-1" "${generation_root}/mill-1"
diff -ru "${generation_root}/sbt-1" "${generation_root}/mill-2"

rtl_dir="${validation_root}/display-controller-verilog/rtl/display_controller"
mkdir -p "${rtl_dir}"
cp "${generation_root}/sbt-1/DisplayControllerSanityShell.v" \
  "${rtl_dir}/DisplayControllerSanityShell.v"
cmp "${generation_root}/sbt-1/DisplayControllerSanityShell.v" \
  "${rtl_dir}/DisplayControllerSanityShell.v"

log "compile and run deterministic SystemVerilog smoke test"
pushd "${validation_root}/display-controller-verilog/verif/sanity" >/dev/null
iverilog -g2012 -Wall -s tb_display_controller_sanity \
  -o /tmp/p1-sanity.vvp -f compile.f \
  2>&1 | tee /tmp/p1-sanity-iverilog-compile.log
vvp /tmp/p1-sanity.vvp 2>&1 | tee /tmp/p1-sanity-smoke.log
grep -q '^\[PASS\] DisplayControllerSanityShell AXI4-Lite smoke completed: writes=7 reads=18$' \
  /tmp/p1-sanity-smoke.log

set +e
vvp /tmp/p1-sanity.vvp +EXPECT_TIMEOUT_FATAL \
  > /tmp/p1-sanity-timeout.log 2>&1
timeout_rc=$?
set -e
if [[ "${timeout_rc}" -eq 0 ]]; then
  echo "controlled timeout run unexpectedly passed" >&2
  exit 30
fi
grep -q 'EXPECTED_TIMEOUT_FATAL BFM_AR_TIMEOUT' /tmp/p1-sanity-timeout.log

log "lint and synthesize generated DUT"
verilator --lint-only --Wall --Wno-fatal -sv \
  ../../rtl/display_controller/DisplayControllerSanityShell.v \
  --top-module DisplayControllerSanityShell \
  2>&1 | tee /tmp/p1-sanity-verilator.log

yosys -q -p '
  read_verilog -sv ../../rtl/display_controller/DisplayControllerSanityShell.v;
  hierarchy -check -top DisplayControllerSanityShell;
  proc; opt; memory; opt; check;
  write_json /tmp/p1-sanity-yosys.json
'
popd >/dev/null

log "verify exact module/port inventory and absence of latches/memories/parameters"
python3 - <<'PY'
import json
from pathlib import Path

design = json.loads(Path('/tmp/p1-sanity-yosys.json').read_text())
expected = {
    'aclk': ('input', 1), 'aresetn': ('input', 1),
    's_axil_awaddr': ('input', 12), 's_axil_awprot': ('input', 3),
    's_axil_awvalid': ('input', 1), 's_axil_awready': ('output', 1),
    's_axil_wdata': ('input', 32), 's_axil_wstrb': ('input', 4),
    's_axil_wvalid': ('input', 1), 's_axil_wready': ('output', 1),
    's_axil_bresp': ('output', 2), 's_axil_bvalid': ('output', 1),
    's_axil_bready': ('input', 1), 's_axil_araddr': ('input', 12),
    's_axil_arprot': ('input', 3), 's_axil_arvalid': ('input', 1),
    's_axil_arready': ('output', 1), 's_axil_rdata': ('output', 32),
    's_axil_rresp': ('output', 2), 's_axil_rvalid': ('output', 1),
    's_axil_rready': ('input', 1), 's_axis_tdata': ('input', 32),
    's_axis_tvalid': ('input', 1), 's_axis_tready': ('output', 1),
    's_axis_tlast': ('input', 1), 's_axis_tuser': ('input', 1),
    'm_axi_arid': ('output', 4), 'm_axi_araddr': ('output', 32),
    'm_axi_arlen': ('output', 8), 'm_axi_arsize': ('output', 3),
    'm_axi_arburst': ('output', 2), 'm_axi_arlock': ('output', 1),
    'm_axi_arcache': ('output', 4), 'm_axi_arprot': ('output', 3),
    'm_axi_arqos': ('output', 4), 'm_axi_arvalid': ('output', 1),
    'm_axi_arready': ('input', 1), 'm_axi_rid': ('input', 4),
    'm_axi_rdata': ('input', 64), 'm_axi_rresp': ('input', 2),
    'm_axi_rlast': ('input', 1), 'm_axi_rvalid': ('input', 1),
    'm_axi_rready': ('output', 1), 'dpi_pclk': ('output', 1),
    'dpi_r': ('output', 8), 'dpi_g': ('output', 8),
    'dpi_b': ('output', 8), 'dpi_hsync': ('output', 1),
    'dpi_vsync': ('output', 1), 'dpi_de': ('output', 1),
    'irq': ('output', 1),
}

if set(design['modules']) != {'DisplayControllerSanityShell'}:
    raise SystemExit(f"unexpected modules: {sorted(design['modules'])}")
module = design['modules']['DisplayControllerSanityShell']
observed = {
    name: (port['direction'], len(port['bits']))
    for name, port in module['ports'].items()
}
if observed != expected:
    missing = sorted(set(expected) - set(observed))
    extra = sorted(set(observed) - set(expected))
    wrong = sorted(
        name for name in set(expected) & set(observed)
        if expected[name] != observed[name]
    )
    raise SystemExit(
        f"port inventory mismatch missing={missing} extra={extra} wrong={wrong}"
    )
if module.get('memories'):
    raise SystemExit(f"unexpected inferred memories: {module['memories']}")
cell_types = {cell['type'] for cell in module.get('cells', {}).values()}
if '$dlatch' in cell_types:
    raise SystemExit('unexpected inferred latch')

verilog = Path(
    'validation/display-controller-p1-sanity-00/'
    'display-controller-verilog/rtl/display_controller/'
    'DisplayControllerSanityShell.v'
).read_text()
if 'module DisplayControllerSanityShell #(' in verilog:
    raise SystemExit('native Verilog parameters are not approved')
PY

log "assemble generated RTL and target evidence artifact"
rm -rf "${artifact_root}"
mkdir -p "${artifact_root}/logs"
cp "${rtl_dir}/DisplayControllerSanityShell.v" \
  "${artifact_root}/DisplayControllerSanityShell.v"
for path in \
  /tmp/p1-sanity-sbt-compile.log \
  /tmp/p1-sanity-mill-compile.log \
  /tmp/p1-sanity-sbt-generate-1.log \
  /tmp/p1-sanity-sbt-generate-2.log \
  /tmp/p1-sanity-mill-generate-1.log \
  /tmp/p1-sanity-mill-generate-2.log \
  /tmp/p1-sanity-iverilog-compile.log \
  /tmp/p1-sanity-smoke.log \
  /tmp/p1-sanity-timeout.log \
  /tmp/p1-sanity-verilator.log; do
  cp "${path}" "${artifact_root}/logs/"
done
cp /tmp/p1-sanity-yosys.json "${artifact_root}/"
sha256sum "${artifact_root}/DisplayControllerSanityShell.v" \
  | tee "${artifact_root}/DisplayControllerSanityShell.v.sha256"
{
  echo "morphhdl_base=${morphhdl_base}"
  echo "validation_commit=$(git rev-parse HEAD)"
  echo "rtl_gate_sha256=1c637be71c7f686943aa8ee41bb439071b7e60bf676ac47beac08ea96890ea90"
  echo "dv_gate_sha256=461468459b48b6bc1a5d4a12f606933551df1381dd776656a699051ee43c0db2"
  echo "status=open_source_validation_passed_questa_pending"
} > "${artifact_root}/validation-summary.txt"

log "validation passed"
