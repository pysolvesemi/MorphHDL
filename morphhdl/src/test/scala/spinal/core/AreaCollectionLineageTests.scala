package spinal.core

import java.nio.file.Files
import morphhdl.{MorphAggregateOptions, MorphVerilog}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.internals._

/** A captured witness edge is not authority for a subsequently modified edge. */
class AreaCollectionLineageTests extends AnyFunSuite {
  for (mutation <- Seq("source", "target", "width"))
  test(s"finite index value rejects mutated $mutation lineage") {
    val error = intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = Files.createTempDirectory("area-lineage-").toString),
        preserveConstantLoops = true, preserveConstantVecs = true)) {
        new Component {
          val output = out(Vec(UInt(2 bits), 3))
          ElabFiniteRange.foreach(ElabInt.literal(3), "lanes") { index => index(output) := index.uint(2 bits) }
          val edge = TypedFiniteIndexValue.entries(this).head
          mutation match {
            case "source" => edge.assignment.source = U(1, 2 bits).head.source
            case "target" => edge.assignment.target = UInt(2 bits)
            case "width" => edge.result.setWidth(3)
          }
          // Exercise the authenticated emitter seam before a native phase can repair or reject it.
          TypedFiniteIndexValue.source(this, edge.assignment)
        }
      }
    }
    assert(error.toString.contains("finite index value lost its exact constant edge, owner or width"), error.toString)
  }

  test("Area documentation iteration rejects changes to a child driver") {
    val error = intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = Files.createTempDirectory("area-child-doc-").toString),
        preserveConstantLoops = true, preserveConstantVecs = true)) {
        new Component {
          val input = in Bool()
          val areas = ElabAreaCollection.tabulate(ElabInt.literal(3), "lanes") { index => new Area {
            val child = new Component {
              val a = in Bool()
              val b = out Bool()
              b := a
            }
            child.a := input
          }}
          areas.documentation.foreach { case (area, _) =>
            val assignment = area.child.b.head.asInstanceOf[DataAssignmentStatement]
            assignment.source = True.head.source
          }
        }
      }
    }
    assert(error.toString.contains("SPINAL-ELAB-AREA-DOCUMENTATION-HARDWARE-EFFECT"), error.toString)
  }
}
