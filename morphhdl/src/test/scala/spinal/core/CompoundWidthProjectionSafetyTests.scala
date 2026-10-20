package spinal.core

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

class CompoundWidthProjectionSafetyTests extends AnyFunSuite {
  test("compound substitution requires exact complete declarations and authenticated bounded actuals") {
    SpinalConfig(targetDirectory=Files.createTempDirectory("compound-projection-safety-").toString).generateVerilog(new Component {
      val keep=out Bool();keep:=False
      val a=RtlParam.int("A",3,1,8)
      val b=RtlParam.int("B",2,1,8)
      val width=(a+b).expression
      val af=a.expression.parameters.head;val bf=b.expression.parameters.head
      val arguments=Vector(af->ElabInt.literal(1).expression,bf->ElabInt.literal(8).expression)
      val actual=ElaborationWidthAuthority.substituteFormals(width,arguments)
      assert(actual.default==9 && actual.parameters.isEmpty)
      def reject(w:ElaborationIntegerExpression, bindings:Vector[(ElaborationIntegerParameter,ElaborationIntegerExpression)]):Unit = {
        intercept[ParameterizedVerilogException] { ElaborationWidthAuthority.substituteFormals(w,bindings) }
      }
      reject(width.copy(),arguments)
      reject(width,arguments.take(1))
      reject(width,arguments:+arguments.head)
      reject(width,Vector(af.copy()->arguments.head._2,arguments.last))
      reject(width,Vector(af->ElabInt.literal(0).expression,arguments.last))
      reject(width,Vector(af->ElabInt.literal(9).expression,arguments.last))
      reject(width,Vector(af->(a+1).expression.copy(),arguments.last))
      intercept[ParameterizedVerilogException] { RtlParam.int("A",3,1,8) }
    })
  }
}
