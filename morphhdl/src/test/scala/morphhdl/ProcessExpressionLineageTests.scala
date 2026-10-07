package spinal.core

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.internals._

class ProcessExpressionLineageTests extends AnyFunSuite {
  test("captured alias chains survive native pruning and reject replacement drivers") {
    SpinalVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("lineage-alias-").toString)) {
      new Component {
        val x,y=in Bool()
        val result=out Bool();result:=x
        val first=Bool();first:=x
        val second=Bool();second:=first
        val firstDriver=first.head.asInstanceOf[DataAssignmentStatement]
        val secondDriver=second.head.asInstanceOf[DataAssignmentStatement]
        val evidence=ProcessExpressionLineage.capture(second,this)
        assert(evidence.accepts(second) && evidence.accepts(first) && evidence.accepts(x))
        assert(!evidence.accepts(y))
        firstDriver.source=y
        assert(!evidence.accepts(second) && !evidence.accepts(x) && !evidence.accepts(y))
        firstDriver.source=x
        secondDriver.source=x
        firstDriver.removeStatement();first.removeStatement()
        assert(evidence.accepts(second) && evidence.accepts(x))
        secondDriver.removeStatement();second.removeStatement()
        assert(evidence.accepts(x) && !evidence.accepts(second))
      }
    }
  }
  test("captured lineage rejects changed literal values and slice geometry") {
    SpinalVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("lineage-geometry-").toString)) {
      new Component {
        val x=in Bits(8 bits)
        val output=out Bits(4 bits);output:=x(3 downto 0)
        val literal=U(3,8 bits).head.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[UIntLiteral]
        val litEvidence=ProcessExpressionLineage.capture(literal,this)
        assert(litEvidence.accepts(literal))
        literal.value=4;assert(!litEvidence.accepts(literal));literal.value=3
        val selected=x(3 downto 0)
        val slice=selected.head.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[BitVectorRangedAccessFixed]
        val evidence=ProcessExpressionLineage.capture(selected,this)
        slice.hi=4;slice.lo=1
        assert(!evidence.accepts(selected));slice.hi=3;slice.lo=0
        assert(evidence.accepts(selected))
      }
    }
  }
  test("register boundaries and foreign declarations retain distinct lineage") {
    SpinalVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("lineage-owners-").toString)) {
      new Component {
        val input=in Bool();val result=out Bool()
        val state=Reg(Bool()) init(False);state:=input;result:=state
        val evidence=ProcessExpressionLineage.capture(state,this)
        assert(evidence.accepts(state) && !evidence.accepts(input))
        val child=new Component { val data=out Bool();data:=False }
        assert(!evidence.accepts(child.data))
        val alias=Bool();alias:=input
        val aliasEvidence=ProcessExpressionLineage.capture(alias,this)
        val driver=alias.head.asInstanceOf[DataAssignmentStatement]
        driver.source=child.data
        assert(!aliasEvidence.accepts(alias) && !aliasEvidence.accepts(child.data))
        driver.source=input
      }
    }
  }
  for(mode <- Seq("view-source", "view-slice", "result-width", "default-edge", "witness-edge"))
  test(s"scoped output publication rejects mutated $mode") {
    val error=intercept[Exception] {
      morphhdl.MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("scoped-view-mutation-").toString)) {
        new Component {
          val n=morphhdl.frontend.HdlInt.param("COUNT",2,1,4).asElabInt
          val data=in Bits(8 bits);val result=out Bits(8 bits)
          val outputs=ElabProcess.outputsLoop(n,Seq(ElabScopedProcess.Output(ElabInt.literal(8)),ElabScopedProcess.Output(ElabInt.literal(8),3))){p=>
            p.output(0).assign(p.bits(data));p.output(1).assign(p.output(1).current^p.bits(data))
          }
          result:=outputs.head^outputs(1)
          val op=ElabScopedProcess.operations(this).head
          def literal(expression:Expression):BitVectorLiteral=expression match {
            case value:BitVectorLiteral=>value
            case value:BitVector=>literal(value.head.asInstanceOf[DataAssignmentStatement].source)
          }
          mode match {
            case "view-source" => op.views.head.assignment.source=data
            case "view-slice" =>
              val alias=op.views.head.source.asInstanceOf[Bits]
              val range=alias.head.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[BitVectorRangedAccessFixed]
              range.lo+=1;range.hi+=1
            case "result-width" => op.result.setWidth(17)
            case "default-edge" => literal(op.default.source).value+=1
            case "witness-edge" => literal(op.assignment.source).value+=1
          }
        }
      }
    }
    assert(error.toString.contains("SPINAL-") || error.toString.contains("WIDTH MISMATCH") || error.toString.contains("getWidth call result during elaboration differ from inferred width"),error.toString)
  }
}
