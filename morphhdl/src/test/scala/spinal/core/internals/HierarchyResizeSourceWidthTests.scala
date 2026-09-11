package spinal.core.internals

import java.nio.file.Files
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

private[internals] abstract class ResizeWidthChild(count: ElabInt) extends Component {
  val enable = in Bool()
  val payload = out UInt((count + 1).addressWidth bits)
  val sparePayload = out(cloneOf(payload))
  payload := 0
  when(enable) { payload := 1 }
  sparePayload := payload

  def lateOutput(): UInt = rework {
    val result = cloneOf(payload)
    result := payload
    result
  }.pull()
}

private[internals] final class CedarWidthUnit(count: ElabInt) extends ResizeWidthChild(count)
private[internals] final class QuartzWidthUnit(count: ElabInt) extends ResizeWidthChild(count)

private[internals] final class HierarchyResizeWidthParent(actual: ElabInt, renamed: Boolean, late: Boolean)
    extends Component {
  val enable = in Bool()
  val observed = out UInt(4 bits)
  val spare = out UInt(4 bits)
  val child = ElabFormalComponent.parameter(actual, "LOCAL_SIZE", 1, 8) { count =>
    if (renamed) new QuartzWidthUnit(count) else new CedarWidthUnit(count)
  }
  child.enable := enable
  observed := (if (late) child.lateOutput() else child.payload).resized
  spare := 0
}

class HierarchyResizeSourceWidthTests extends AnyFunSuite {
  private def generate(
      renamed: Boolean = false,
      enabled: Boolean = true,
      late: Boolean = false
  )(inspect: (HierarchyResizeWidthParent, ElabInt) => Unit): Unit = {
    val root = HdlInt.param("SIZE", 4, 1, 7).asElabInt
    val actual = root + 1
    val config = SpinalConfig(
      targetDirectory = Files.createTempDirectory("hierarchy-resize-width-").toString)
    if (enabled) {
      config.phasesInserters += { phases =>
        val boundary = phases.indexWhere(_.isInstanceOf[PhasePropagateNames])
        assert(boundary >= 0)
        phases.insert(boundary, new PhaseMisc {
          override def impl(pc: PhaseContext): Unit =
            inspect(pc.topLevel.asInstanceOf[HierarchyResizeWidthParent], root)
        })
      }
    }
    val report = MorphVerilog(MorphWireAssignmentPasses(config, enabled = enabled)) {
      new HierarchyResizeWidthParent(actual, renamed, late)
    }
    assert(report.parameters.map(_.name) == Vector("SIZE"))
  }

  private def carrier(fixture: HierarchyResizeWidthParent): UInt = {
    val values = scala.collection.mutable.ArrayBuffer.empty[UInt]
    fixture.dslBody.walkDeclarations {
      case value: UInt if ExternalParameterizedAutoResize
          .directChildOutputOfResizeSource(fixture, value).nonEmpty => values += value
      case _ =>
    }
    assert(values.size == 1)
    values.head
  }

  test("ordinary child auto-resize retains the parent root and exact complete width domain") {
    for (renamed <- Vector(false, true); late <- Vector(false, true)) generate(renamed, late = late) { (fixture, root) =>
      val value = carrier(fixture)
      val original = ParameterizedWidth.expressionOf(value).get
      val width = ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).get
      assert(width.completedParameterRoots.head eq root.expression.completedParameterRoots.head)
      assert(width.parameters.head eq root.expression.parameters.head)
      assert(width.exactDomain.get.hasCompleteCoverage)
      for (size <- 1 to 7) {
        val expected = BigInt((32 - Integer.numberOfLeadingZeros(size + 1)).max(1))
        assert(width.exactDomain.get.evaluate(BigInt(size)).contains(expected))
      }
      assert(ParameterizedWidth.expressionOf(value).get eq original)
      assert(original.completedParameterRoots.head ne width.completedParameterRoots.head)
    }
    generate(enabled = false)((_, _) => ())
  }

  test("auto-resize source-width authority rejects stale drivers, kind, direction, use and owner") {
    generate() { (fixture, _) =>
      val value = carrier(fixture)
      val driver = value.head.asInstanceOf[DataAssignmentStatement]
      val original = driver.source
      assert(ParameterizedWidth.expressionOf(fixture.child.sparePayload).get eq
        ParameterizedWidth.expressionOf(original.asInstanceOf[BaseType]).get)
      driver.source = fixture.child.sparePayload
      assert(ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).isEmpty)
      driver.source = original

      fixture.rework { value := fixture.child.sparePayload }
      val extraDrivers = scala.collection.mutable.ArrayBuffer.empty[DataAssignmentStatement]
      value.foreachStatements {
        case assignment: DataAssignmentStatement if assignment ne driver => extraDrivers += assignment
        case _ =>
      }
      assert(extraDrivers.size == 1)
      assert(!value.hasOnlyOneStatement)
      assert(ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).isEmpty)
      extraDrivers.head.removeStatement()
      assert(value.hasOnlyOneStatement)
      assert(ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).nonEmpty)

      value.setAsReg()
      assert(ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).isEmpty)
      value.setAsComb()
      assert(ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).nonEmpty)

      fixture.rework { value.asOutput() }
      assert(!value.isDirectionLess)
      assert(ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).isEmpty)
      value.setAsDirectionLess()
      assert(ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).nonEmpty)

      val spareDriver = fixture.spare.head.asInstanceOf[DataAssignmentStatement]
      val spareSource = spareDriver.source
      spareDriver.source = value
      assert(ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).isEmpty)
      spareDriver.source = spareSource

      val originalScope = driver.parentScope
      val borrowed = new ScopeStatement(null)
      borrowed.component = fixture
      driver.parentScope = borrowed
      assert(ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).isEmpty)
      driver.parentScope = originalScope
      assert(ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).nonEmpty)

      val childIndex = fixture.children.indexWhere(_ eq fixture.child)
      fixture.children.remove(childIndex)
      assert(ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).isEmpty)
      fixture.children.insert(childIndex, fixture.child)
      assert(ExternalParameterizedHierarchyResizeWidth.sourceWidthOf(fixture, value).nonEmpty)
    }
  }

  test("same-name independent roots retain the complete-inventory rejection") {
    val left = HdlInt.param("WIDTH", 4, 1, 8)
    val right = HdlInt.param("WIDTH", 4, 1, 8)
    val config = SpinalConfig(
      targetDirectory = Files.createTempDirectory("independent-width-roots-").toString)
    var inventoryRejected = false
    config.phasesInserters += { phases =>
      val boundary = phases.indexWhere(_.isInstanceOf[PhasePropagateNames])
      phases.insert(boundary, new PhaseMisc {
        override def impl(pc: PhaseContext): Unit = {
          val error = intercept[ParameterizedVerilogException] {
            ExternalParameterizedHierarchyResizeWidth.parametersOf(pc.topLevel)
          }
          assert(error.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
          inventoryRejected = true
        }
      })
    }
    val failure = MorphVerilog.tryGenerate(config) {
      new Component {
        val input = in UInt(left bits)
        val output = out UInt(right bits)
        output := input
      }
    }
    assert(failure.isLeft)
    assert(inventoryRejected)
    assert(failure.left.get.message.contains("INDEPENDENT-ROOTS-UNSUPPORTED"))
  }
}
