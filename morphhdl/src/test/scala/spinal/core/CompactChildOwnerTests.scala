package spinal.core

import java.nio.file.Files
import morphhdl.{CompactArgumentLeaf, CompactArgumentParent, MorphVerilog}
import org.scalatest.funsuite.AnyFunSuite

class CompactChildOwnerTests extends AnyFunSuite {
  private def generate(): CompactArgumentParent = {
    var top: CompactArgumentParent = null
    MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("compact-owner-").toString)) {
      top = new CompactArgumentParent
      top
    }
    top
  }
  test("compact child ownership requires exact parent declaration and child identities") {
    val parent=generate();val other=generate()
    val expression=parent.timeout.expression
    val root=expression.completedParameterRoots.head;val schema=expression.parameters.head
    def check(p:Component,c:Component,r:ElaborationIntegerParameterRoot,s:ElaborationIntegerParameter):Unit =
      ParameterizedStructure.requireCompactChildOwner(p,c,r,s,"owner test",Some("CompactChildOwnerTests.scala"))
    check(parent,parent.child,root,schema)
    val unrelated=other.timeout.expression
    assert(schema==unrelated.parameters.head)
    assert(schema ne unrelated.parameters.head)
    val foreign=intercept[ParameterizedVerilogException] {
      check(parent,parent.child,unrelated.completedParameterRoots.head,unrelated.parameters.head)
    }
    assert(foreign.code.endsWith("OWNER-MISMATCH"))
    assert(intercept[ParameterizedVerilogException]{check(parent,other.child,root,schema)}.code.endsWith("OWNER-MISSING"))
    assert(intercept[ParameterizedVerilogException]{check(parent,parent.child,root,schema.copy())}.code.endsWith("OWNER-MISMATCH"))
    assert(intercept[ParameterizedVerilogException]{check(null,parent.child,root,schema)}.code.endsWith("OWNER-MISSING"))
  }
  test("forged compound actual certificates remain rejected") {
    val error=intercept[Exception]{
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("compact-forged-").toString))(new Component {
        val timeout=RtlParam.int("TIMEOUT",1024,32,16777216)
        val actual=ElabInt.fromExpression((timeout-1).expression.copy())
        val child=new CompactArgumentLeaf(actual)
        child.source:=0
        val result=out Bool();result:=child.result
      })
    }
    assert(error.toString.contains("SPINAL-ELAB-FORMAL-ACTUAL-EXACT-DOMAIN-REQUIRED"),error.toString)
  }
}
