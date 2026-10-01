package spinal.core

import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.{HdlInt, formalParam}
import org.scalatest.funsuite.AnyFunSuite
import scala.sys.process.{Process, ProcessLogger}

class NativeFormalOwnershipTests extends AnyFunSuite {
  class Child(actual: ElabInt) extends Component {
    @dontName val width = formalParam(actual, "WIDTH", 1, 32)
    val din = in Bits(width bits)
    val dout = out Bits(width bits)
    dout := din
  }
  class Siblings extends Component {
    @dontName val a = HdlInt.param("WIDTH_A", 8, 1, 32).asElabInt
    @dontName val b = HdlInt.param("WIDTH_B", 8, 1, 32).asElabInt
    val inA = in Bits(a bits)
    val inB = in Bits(b bits)
    val outA = out Bits(a bits)
    val outB = out Bits(b bits)
    val first = new Child(a)
    val second = new Child(b)
    first.din := inA
    second.din := inB
    outA := first.dout
    outB := second.dout
  }
  test("equal-default sibling formals retain independent actual identities") {
    val dir = Files.createTempDirectory("native-formal-siblings-")
    MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory = dir.toString,
      headerWithDate = false), enabled = false))(new Siblings)
    val rtl = new String(Files.readAllBytes(dir.resolve("Siblings.v")), UTF_8)
    assert(rtl.contains(".WIDTH(WIDTH_A)") && rtl.contains(".WIDTH(WIDTH_B)"), rtl)
    for ((a,b) <- Seq((1,32),(32,1),(7,9),(8,8))) {
      val log = new StringBuilder
      val result = morphhdl.Increment66ToolEvidence.run(dir, Seq("yosys", "-p", s"read_verilog -DSYNTHESIS Siblings.v; chparam -set WIDTH_A $a -set WIDTH_B $b Siblings; prep -top Siblings -flatten; check -assert; sat -verify -prove outA inA -prove outB inB"))
      log.append(result._2)
      val status = result._1
      assert(status == 0, log.toString)
    }
  }
  class ScalarStandalone(zero: Boolean, legacy: Boolean) extends Component {
    @dontName private val scalar = if (legacy) {
      if (zero) formalParam(HdlInt.literal(0), "PPC4", 0, 1).asElabInt
      else formalParam(HdlInt.literal(2), "BUS_LOG2_BYTES", 2, 5).asElabInt
    } else {
      if (zero) formalParam(ElabInt.literal(0), "PPC4", 0, 1)
      else formalParam(ElabInt.literal(2), "BUS_LOG2_BYTES", 2, 5)
    }
    private val width = if (zero) scalar * 3 + 1 else scalar.pow2 * 8
    val din = in Bits(width bits)
    val dout = out Bits(width bits)
    dout := din
  }
  for (zero <- Seq(false, true); legacy <- Seq(false, true))
  test(s"standalone scalar formals retain native and HdlInt literal declarations, zero=$zero legacy=$legacy") {
    val dir = Files.createTempDirectory("native-formal-standalone-")
    MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory = dir.toString,
      headerWithDate = false), enabled = false))(new ScalarStandalone(zero, legacy))
    val parameter = if (zero) "PPC4" else "BUS_LOG2_BYTES"
    for (value <- (if (zero) 0 to 1 else 2 to 5)) {
      val log = new StringBuilder
      val result = morphhdl.Increment66ToolEvidence.run(dir, Seq("yosys", "-p", s"read_verilog -DSYNTHESIS ScalarStandalone.v; chparam -set $parameter $value ScalarStandalone; prep -top ScalarStandalone -flatten; check -assert; sat -verify -prove dout din"))
      log.append(result._2)
      val status = result._1
      assert(status == 0, log.toString)
    }
  }
  for ((mode, code) <- Seq(
      0 -> "SPINAL-ELAB-FORMAL-DOMAIN-INVALID",
      1 -> "SPINAL-ELAB-FORMAL-DECLARATION-INVALID",
      2 -> "SPINAL-ELAB-FORMAL-NAME-DUPLICATE"))
  test(s"native formal rejects invalid declarations without guessing authority, mode=$mode") {
    val dir = Files.createTempDirectory("native-formal-invalid-")
    val error = intercept[Exception] {
      MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory = dir.toString), enabled = false)) {
        new Component {
          @dontName val first = formalParam(ElabInt.literal(if (mode == 0) 3 else 1),
            if (mode == 1) "bad-name" else "WIDTH", 1, 2)
          if (mode == 2) { formalParam(ElabInt.literal(1), "WIDTH", 1, 2) }
          val din = in Bits(first bits)
          val dout = out Bits(first bits)
          dout := din
        }
      }
    }
    val messages = Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null)
      .map(x => String.valueOf(x.getMessage)).mkString("\n")
    assert(messages.contains(code), messages)
  }
}
