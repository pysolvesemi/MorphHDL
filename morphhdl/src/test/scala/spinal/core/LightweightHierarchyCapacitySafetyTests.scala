package spinal.core

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.internals._
import morphhdl.{MorphAggregateOptions,MorphVerilog}

class LightweightHierarchyCapacitySafetyTests extends AnyFunSuite {
  class Child(actual: ElabInt) extends Component {
    val count: ElabInt=RtlParam.int("COUNT",actual,1,8)
    val source=in(Vec(Bits(8 bits),count))
    val result=out(Vec(Bits(8 bits),count))
    ElabFiniteRange.foreach(count,"copy") { i => i(result) := i(source) }
  }
  for(mode<-Seq("value","width","poison","missing","competing","consumer"))
    test(s"native capacity adaptation rejects changed evidence $mode") {
      val dir=Files.createTempDirectory("lightweight-capacity-negative-")
      var revoked=false
      val error=intercept[Exception] {
        MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString),
          preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
          new Component {
            val count: ElabInt=RtlParam.int("COUNT",3,1,4)
            val source=in(Vec(Bits(8 bits),count))
            val result=out(Vec(Bits(8 bits),count))
            val child=new Child(count)
            child.source := source
            result := child.result
            val receipt=ParameterizedVecHierarchy.between(child.source,source).get
            val output=ParameterizedVecHierarchy.between(result,child.result).get
            mode match {
              case "value" => receipt.padding.head._2.asInstanceOf[BitVectorLiteral].value=1
              case "poison" => receipt.padding.head._2.asInstanceOf[BitVectorLiteral].poisonMask=1
              case "width" => receipt.padding.head._2.asInstanceOf[BitVectorLiteral].bitCount=9
              case "missing" => receipt.operation.assignments.head.removeStatement()
              case "competing" =>
                dslBody.append(DataAssignmentStatement(receipt.paired.head._1,receipt.paired.head._2))
              case "consumer" =>
                val extra=out Bits(8 bits)
                extra := child.result.vec(source.vec.size)
            }
            revoked=if(mode=="consumer") ParameterizedVecHierarchy.between(result,child.result).isEmpty
              else ParameterizedVecHierarchy.between(child.source,source).isEmpty
            assert(revoked,"modified native evidence retained authority")
          }
        }
      }
      assert(revoked,s"did not reach evidence mutation: $error")
      assert(!Files.exists(dir.resolve("Child.v")),"invalid child boundary must not publish a final artifact")
    }
  test("a different literal count is not a capacity adaptation") {
    val error=intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("lightweight-capacity-count-negative-").toString),
        preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
        new Component {
          val source=in(Vec(Bits(8 bits),ElabInt.literal(3)))
          val child=new Child(ElabInt.literal(4))
          child.source := source
          val result=out Bits(8 bits)
          result := child.result(0)
        }
      }
    }
    assert(error.toString.contains("SHAPE-MISMATCH"),error.toString)
  }
  test("equal witness sizes do not authorize a signedness change") {
    val error=intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("lightweight-capacity-type-negative-").toString),
        preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
        new Component {
          val source=in(Vec(SInt(8 bits),ElabInt.literal(3)))
          val child=new Child(ElabInt.literal(3))
          // Bypass the Scala generic check to exercise native type identity rejection.
          child.source := source.asInstanceOf[Vec[Bits]]
        }
      }
    }
    assert(error.toString.contains("SHAPE-MISMATCH"),error.toString)
  }

}
