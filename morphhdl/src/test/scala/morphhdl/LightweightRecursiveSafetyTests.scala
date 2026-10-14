package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

@_root_.spinal.core.rtlRecursive
final class LightweightUnsafeRecursion(actual: ElabInt, mode: String) extends Component {
  noIoPrefix()
  val count: ElabInt = RtlParam.int("COUNT", actual, 1, 8)
  val io = new Bundle { val x = in UInt(8 bits); val y = out UInt(8 bits) }
  if (count == 1) io.y := io.x
  else {
    val next: ElabInt = if (mode == "valid") (count + 1) / 2 else count
    val child = new LightweightUnsafeRecursion(next, mode)
    child.io.x := io.x
    io.y := child.io.y
  }
}

/** The geometry mismatch is independent of a direct-constructor rewrite. */
final class LightweightWrongVecGeometry(mode: String) extends Component {
  setDefinitionName("WrongVecGeometry")
  noIoPrefix()
  val count: ElabInt = RtlParam.int("COUNT", 3, 1, 8)
  val terms = in(Vec(UInt(8 bits), count))
  val result = out UInt(8 bits)
  if (count == 1) result := terms(0)
  else {
    val next = (count + 1) / 2
    val child = new BlackBox {
      setBlackBoxName("WrongVecGeometry")
      addGeneric("COUNT", next)
      val terms = in(Vec(UInt((if (mode == "width") 7 else 8) bits),
        if (mode == "depth") next + 1 else next))
      val result = out UInt(8 bits)
    }
    val inputCount: ElabInt = if (mode == "depth") next + 1 else next
    val driven = Vec(UInt((if (mode == "width") 7 else 8) bits), inputCount)
    for (i <- 0 until inputCount) driven(i) := 0
    child.terms := driven
    result := child.result
  }
}

class LightweightRecursiveSafetyTests extends AnyFunSuite {
  private def rejected(body: => Component, code: String): Unit = {
    val error = intercept[Throwable] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(
        targetDirectory = Files.createTempDirectory("lightweight-recursive-negative-").toString),
        preserveConstantVecs = true, preserveConstantLoops = true))(body)
    }
    val messages = Iterator.iterate(error)(_.getCause).takeWhile(_ != null)
      .map(_.toString).mkString("\n")
    assert(messages.contains(code), messages)
  }
  test("direct recursion establishes the ordinary class-derived module name") {
    val directory = Files.createTempDirectory("lightweight-recursive-default-name-")
    MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
      new LightweightUnsafeRecursion(ElabInt.literal(3), "valid")
    }
    val rtl = new String(Files.readAllBytes(directory.resolve("LightweightUnsafeRecursion.v")), "UTF-8")
    assert(rtl.contains("module LightweightUnsafeRecursion") && rtl.contains(") child ("), rtl)
  }
  test("direct recursion rejects a preserved count instead of terminating") {
    rejected(new LightweightUnsafeRecursion(ElabInt.literal(3), "preserved"),
      "RECURSION-METRIC-NONDECREASING")
  }
  for (mode <- Seq("depth", "width")) {
    test(s"recursive Vec port rejects wrong substituted $mode") {
      rejected(new LightweightWrongVecGeometry(mode), "RECURSION-PORT-SCHEMA-MISMATCH")
    }
  }
}
