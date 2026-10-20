package spinal.core.internals

import java.nio.file.Files
import morphhdl.MorphVerilog
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class DirectChildResizeLineageTests extends AnyFunSuite {
  private class Source(WIDTH: Param[Int]) extends Component {
    require(WIDTH >= 1 && WIDTH <= 64)
    val dataIn = in UInt(WIDTH bits)
    val dataOut, spare = out UInt(WIDTH bits)
    dataOut := dataIn
    spare := dataIn
  }
  private class Top extends Component {
    val WIDTH: Param[Int] = Param(8)
    require(WIDTH >= 1 && WIDTH <= 64)
    val dataIn = in UInt(WIDTH bits)
    val dataOut = out UInt(64 bits)
    val child = new Source(WIDTH)
    child.dataIn := dataIn
    dataOut := child.dataOut.resize(64)
  }

  test("direct resize rejects stale child ports, carriers, widths, owners and drivers") {
    var inspected = false
    val config = SpinalConfig(targetDirectory = Files.createTempDirectory("child-resize-lineage-").toString)
    config.phasesInserters += { phases =>
      val boundary = phases.indexWhere(_.isInstanceOf[PhasePropagateNames])
      assert(boundary >= 0)
      phases.insert(boundary, new PhaseMisc {
        override def impl(pc: PhaseContext): Unit = {
          val top = pc.topLevel.asInstanceOf[Top]
          val carriers = scala.collection.mutable.ArrayBuffer.empty[UInt]
          top.dslBody.walkDeclarations {
            case value: UInt if value.isDirectionLess && value.hasOnlyOneStatement &&
                value.head.isInstanceOf[DataAssignmentStatement] &&
                (value.head.asInstanceOf[DataAssignmentStatement].source eq top.child.dataOut) =>
              carriers += value
            case _ =>
          }
          assert(carriers.size == 1)
          val carrier = carriers.head
          val driver = carrier.head.asInstanceOf[DataAssignmentStatement]
          def validate(): Unit = ExternalParameterizedNativeResize.withPublicationValidation(top)(())
          def rejected(change: => Unit)(restore: => Unit): Unit = {
            validate()
            try {
              change
              val failure = intercept[ParameterizedVerilogException](validate())
              assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-NATIVE-RESIZE-LINEAGE-MISMATCH",
                failure.toString)
            } finally restore
            validate()
          }
          rejected { driver.source = top.child.spare } { driver.source = top.child.dataOut }
          rejected { carrier.setAsReg() } { carrier.setAsComb() }
          rejected { carrier.setWidth(9) } { carrier.setWidth(8) }
          val scope = driver.parentScope
          rejected { driver.parentScope = new ScopeStatement(null) } { driver.parentScope = scope }
          val childIndex = top.children.indexWhere(_ eq top.child)
          rejected { top.children.remove(childIndex) } { top.children.insert(childIndex, top.child) }
          rejected { top.child.dataOut.setAsDirectionLess() } { top.child.rework { top.child.dataOut.asOutput() } }
          val width = ParameterizedWidth.expressionOf(carrier).get
          val conflict = intercept[ParameterizedVerilogException] {
            ParameterizedWidth.retainNativeWidth(carrier, (top.WIDTH + 1).expression)
          }
          assert(conflict.code == "SPINAL-PARAMETERIZED-VERILOG-WIDTH-PROVENANCE-CONFLICT")
          assert(ParameterizedWidth.expressionOf(carrier).get eq width)
          validate()
          var extra: DataAssignmentStatement = null
          rejected {
            top.rework { carrier := top.child.spare }
            carrier.foreachStatements {
              case edge: DataAssignmentStatement if edge ne driver => extra = edge
              case _ =>
            }
            assert(extra != null)
          } { if (extra != null) extra.removeStatement() }
          // Fresh validation also brackets publication itself.
          val failure = intercept[ParameterizedVerilogException] {
            ExternalParameterizedNativeResize.withPublicationValidation(top) {
              driver.source = top.child.spare
            }
          }
          assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-NATIVE-RESIZE-LINEAGE-MISMATCH")
          driver.source = top.child.dataOut
          validate()
          inspected = true
        }
      })
    }
    MorphVerilog(config)(new Top)
    assert(inspected)
  }
}
