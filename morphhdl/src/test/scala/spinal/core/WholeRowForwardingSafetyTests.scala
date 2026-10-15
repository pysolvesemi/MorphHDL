package spinal.core

import java.nio.file.Files
import morphhdl.{MorphAggregateOptions, MorphVerilog}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.internals.DataAssignmentStatement

class WholeRowForwardingSafetyTests extends AnyFunSuite {
  for (mutation <- Seq("source", "missing-forwarding-record", "empty-forwarding", "repeated-forwarding"))
  test(s"native whole-row forwarding rejects $mutation") {
    val error = intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("whole-row-negative-").toString),
        preserveConstantVecs=true,preserveConstantLoops=true)) {
        new Component {
          val rows=RtlParam.int("ROWS",3,1,5)
          val columns=RtlParam.int("COLS",3,1,4)
          val width=RtlParam.int("WIDTH",8,1,64)
          val source=in(Vec(Vec(SInt(width bits),columns),rows))
          val replacement=in(Vec(SInt(width bits),columns))
          val row=in UInt(3 bits)
          val other=in SInt(width bits)
          val enable=in Bool()
          val result=out(Vec(Vec(SInt(width bits),columns),rows))
          result := source
          val selected=result(row)
          when(enable) { selected := replacement }
          val whole=ParameterizedVec.wholeForwardInvocationsOf(selected).head
          mutation match {
            case "source" =>
              whole.forwardedWrites.head.operation.asInstanceOf[ParameterizedVecDynamicWrite].assignments.head.source=other
            case "missing-forwarding-record" =>
              val field=ParameterizedVec.getClass.getDeclaredFields.find(_.getName=="retained").get
              field.setAccessible(true)
              val entries=field.get(ParameterizedVec).asInstanceOf[scala.collection.mutable.Map[AnyRef,AnyRef]].values.toVector
              val inventories=entries.map { entry =>
                val operations=entry.getClass.getDeclaredFields.find(_.getName=="operations").get
                operations.setAccessible(true)
                operations.get(entry).asInstanceOf[scala.collection.mutable.ArrayBuffer[ParameterizedVecOperation]]
              }
              val inventory=inventories.find(_.exists(_ eq whole)).get
              inventory.remove(inventory.indexWhere(_ eq whole))
            case other =>
              val field=classOf[ParameterizedVecWholeAssignment].getDeclaredField("forwardedWrites")
              field.setAccessible(true)
              field.set(whole,if(other=="empty-forwarding") Vector.empty[ParameterizedVecWriteInvocation]
                else Vector.fill(whole.forwardedWrites.size)(whole.forwardedWrites.head))
          }
        }
      }
    }
    assert(error.toString.contains("SPINAL-PARAMETERIZED-VERILOG-VEC-WHOLE-FORWARDING-MISMATCH"),error.toString)
  }
}
