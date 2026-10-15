package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.BalancedTree

class LightweightBalancedSafetyTests extends AnyFunSuite {
  private def generate(body: => Component): Unit = {
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("lightweight-library-negative-").toString),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray))(body)
    ()
  }
  test("a symbolic payload factory cannot substitute a fixed witness width") {
    val error=intercept[Exception] { generate {
      BalancedTree.defineWidth((_:ElabInt)=>UInt(8 bits),8,8,"WrongWidth")(
        (a:UInt,b:UInt)=>a+b).component(ElabInt.literal(3))
    }}
    assert(error.toString.contains("exact DATA_WIDTH"),error.toString)
  }
  test("a widening callback cannot silently change the reduction payload") {
    val error=intercept[Exception] { generate {
      BalancedTree.define(HardType(UInt(8 bits)),8,"Widened")(
        (a:UInt,b:UInt)=>a+^b).component(ElabInt.literal(3))
    }}
    assert(error.toString.toLowerCase.contains("width"),error.toString)
  }
  test("a callback cannot capture a foreign parent signal") {
    val error=intercept[Exception] { generate {
      new Component {
        val count: ElabInt=RtlParam.int("COUNT",3,1,8)
        val terms=in(Vec(UInt(8 bits),count))
        val bias=in UInt(8 bits)
        val tree=BalancedTree.define(HardType(UInt(8 bits)),8,"ForeignCapture")(
          (a:UInt,b:UInt)=>a+b+bias).component(count)
        tree.io.clk := False
        tree.io.reset := False
        tree.io.softReset := False
        tree.io.enable := True
        tree.io.terms := terms
        val result=out UInt(8 bits)
        result := tree.io.result
      }
    }}
    assert(Seq("hierarchy","owner").exists(error.toString.toLowerCase.contains),error.toString)
  }
}
