package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.BalancedTree

class LightweightSymbolicAdapterSafetyTests extends AnyFunSuite {
  private def generate(body: => Component): Unit = {
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("lightweight-adapter-negative-").toString),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray))(body)
    ()
  }
  test("a plain sequence cannot borrow the first element's symbolic geometry") {
    val error=intercept[Exception] { generate { new Component {
      val width: ElabInt=RtlParam.int("WIDTH",8,1,64)
      val data=in(Vec(UInt(width bits),3))
      val result=out UInt(width bits)
      result := BalancedTree.reduceBalancedTree(data.toVector,"SequenceTree")((a:UInt,b:UInt)=>a+b)
    }}}
    assert(error.toString.contains("authenticated native Vec"),error.toString)
  }
  test("a width factory cannot substitute its construction witness through the adapter") {
    val error=intercept[Exception] { generate { new Component {
      val count: ElabInt=RtlParam.int("COUNT",3,1,8)
      val width: ElabInt=RtlParam.int("WIDTH",8,1,64)
      val data=in(Vec(UInt(width bits),count))
      val result=out UInt(width bits)
      val operation=BalancedTree.defineWidth((_:ElabInt)=>UInt(8 bits),8,8,"WrongWidth")((a:UInt,b:UInt)=>a+b)
      result := operation.reduceBalancedTree(data)
    }}}
    assert(error.toString.contains("exact DATA_WIDTH"),error.toString)
  }
}
