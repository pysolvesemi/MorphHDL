package spinal.core.internals

import java.nio.file.Files
import scala.sys.process.{Process, ProcessLogger}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

final class BalancedCallbackWidthSmoke(width: HdlInt, count: HdlInt) extends Component {
  setDefinitionName("BalancedCallbackWidthSmoke")
  val dataIn = in(Vec(UInt(width bits), count))
  val part, saturated = out(UInt(width bits))
  locally {
    val w = width.asElabInt
    part := dataIn.reduceBalancedTree((a: UInt, b: UInt) => a(0, 1 bits).resize(w) ^ b)
    saturated := dataIn.reduceBalancedTree((a: UInt, b: UInt) => {
      val sum = a.resize(w + 1) + b.resize(w + 1)
      val maximum = UInt(w.bits)
      maximum := ~U(0).resize(w)
      Mux(sum > maximum.resize(w + 1), maximum, sum.resize(w))
    })
  }
}

class TypedBalancedReductionCallbackPublicationTests extends AnyFunSuite {
  test("published part selection and symbolic saturation constants lint at all scalar width boundaries") {
    val directory = Files.createTempDirectory("reduce-callback-width-publication-")
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = "BalancedCallbackWidthSmoke.v"
    MorphVerilog(config) {
      new BalancedCallbackWidthSmoke(HdlInt.param("WIDTH", 5, 1, 32),
        HdlInt.param("COUNT", 1, 1, 3))
    }
    Vector(1, 5, 8, 32).foreach { width =>
      val output = new StringBuilder
      val status = Process(Seq("verilator", "--lint-only", "--language", "1364-2001",
        "--top-module", "BalancedCallbackWidthSmoke", s"-GWIDTH=$width", "-GCOUNT=3",
        directory.resolve("BalancedCallbackWidthSmoke.v").toString))
        .!(ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n')))
      assert(status == 0, s"WIDTH=$width: $output")
    }
  }
}
