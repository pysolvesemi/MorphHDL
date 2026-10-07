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
}
