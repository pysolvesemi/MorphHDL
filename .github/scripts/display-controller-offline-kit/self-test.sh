#!/usr/bin/env bash
set -euo pipefail
kit=${1:?usage: self-test.sh KIT_DIR}
smoke=/tmp/offline-smoke
mkdir -p "$smoke/src/offlinekit" "$smoke/classes" "$smoke/gen"
cat > "$smoke/src/offlinekit/OfflineKitSmoke.scala" <<'SCALA'
package offlinekit
import spinal.core._
case class OfflineKitSmoke() extends Component {
  val io = new Bundle {
    val a = in Bits(8 bits)
    val b = in Bits(8 bits)
    val y = out Bits(8 bits)
  }
  noIoPrefix()
  io.y := io.a ^ io.b
}
object OfflineKitSmokeVerilog extends App {
  SpinalConfig(targetDirectory = "/tmp/offline-smoke/gen").generateVerilog(OfflineKitSmoke())
}
SCALA
"$kit/compile-scala.sh" "$smoke/src" "$smoke/classes"
"$kit/run-main.sh" "$smoke/classes" offlinekit.OfflineKitSmokeVerilog
test -s "$smoke/gen/OfflineKitSmoke.v"
iverilog -g2012 -s OfflineKitSmoke -o "$smoke/smoke.vvp" "$smoke/gen/OfflineKitSmoke.v"
verilator --lint-only --Wall -Wno-DECLFILENAME "$smoke/gen/OfflineKitSmoke.v"
yosys -q -p "read_verilog -sv $smoke/gen/OfflineKitSmoke.v; hierarchy -check -top OfflineKitSmoke; proc; opt; check; synth -top OfflineKitSmoke; check"
sha256sum "$smoke/gen/OfflineKitSmoke.v" > "$kit/metadata/offline-smoke.sha256"
