package spinal.core

import java.nio.file.Files
import morphhdl.{MorphAggregateOptions,MorphVerilog,LightweightNestedCapacityChild}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.internals._

class LightweightNestedCapacitySafetyTests extends AnyFunSuite {
  for(mode<-Seq("coordinate","interleaved-padding","missing-row","duplicate-row","interleaved-consumer"))
    test(s"nested capacity receipt rejects $mode") {
      var revoked=false
      val failure=intercept[Exception] {
        MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("nested-capacity-negative-").toString),
          preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
          new Component {
            val width=RtlParam.int("WIDTH",8,1,16)
            val source=in(Vec(Vec(SInt(width bits),ElabInt.literal(2)),ElabInt.literal(2)))
            val result=out(Vec(Vec(SInt(width bits),ElabInt.literal(2)),ElabInt.literal(2)))
            val child=new LightweightNestedCapacityChild(ElabInt.literal(2),ElabInt.literal(2),width)
            child.source := source
            result := child.result
            val receipt=ParameterizedVecHierarchy.between(child.source,source).get
            val row=child.source.vec(1).vec(0)
            assert(receipt.paired.exists { case(to,from) => (to eq row) && (from eq source.vec(1).vec(0)) })
            val padding=child.source.vec(0).vec(2)
            assert(receipt.auditLeaves.exists(_ eq padding))
            val assignment=receipt.operation.assignments.find(_.finalTarget eq row).get
            mode match {
              case "coordinate" => assignment.source=source.vec(0).vec(1)
              case "interleaved-padding" => receipt.padding.find(_._1 eq padding).get._2.asInstanceOf[BitVectorLiteral].value=1
              case "missing-row" => assignment.removeStatement()
              case "duplicate-row" => dslBody.append(DataAssignmentStatement(row,source.vec(1).vec(0)))
              case "interleaved-consumer" =>
                val extra=out SInt(width bits)
                extra := child.result.vec(0).vec(2)
            }
            revoked=if(mode == "interleaved-consumer") ParameterizedVecHierarchy.between(result,child.result).isEmpty
              else ParameterizedVecHierarchy.between(child.source,source).isEmpty
            assert(revoked,"altered nested boundary retained authority")
          }
        }
      }
      assert(revoked,s"did not reach nested mutation: $failure")
    }

  for(mode<-Seq("literal-mismatch","independent-root")) test(s"nested dimension identity rejects $mode") {
    val failure=intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("nested-domain-negative-").toString),
        preserveConstantVecs=true,preserveConstantLoops=true)) {
        new Component {
          val width=RtlParam.int("WIDTH",8,1,16)
          val cols:ElabInt=if(mode == "literal-mismatch") ElabInt.literal(2) else RtlParam.int("COLS",2,1,3)
          val other:ElabInt=if(mode == "literal-mismatch") ElabInt.literal(3) else RtlParam.int("OTHER",2,1,3)
          val source=in(Vec(Vec(SInt(width bits),cols),ElabInt.literal(2)))
          val child=new LightweightNestedCapacityChild(ElabInt.literal(2),other,width)
          child.source := source
        }
      }
    }
    assert(failure.toString.contains("SHAPE-MISMATCH"),failure.toString)
  }
}
