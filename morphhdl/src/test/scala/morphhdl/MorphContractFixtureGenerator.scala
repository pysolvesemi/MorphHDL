package morphhdl

import java.nio.file.{Files, Paths}

import spinal.core._

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.ParameterRef
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._

/** Public-entry-point source for the first reviewed MorphVerilog artifact. */
object MorphContractFixtureGenerator {
  def main(args: Array[String]): Unit = {
    val outputDirectory = args.toVector match {
      case Vector("--output-dir", path) => Paths.get(path)
      case _ =>
        throw new IllegalArgumentException(
          "Usage: MorphContractFixtureGenerator --output-dir <directory>"
        )
    }
    Files.createDirectories(outputDirectory)
    val config = SpinalConfig(targetDirectory = outputDirectory.toString)
    config.netlistFileName = "parameterized_wire.v"

    MorphVerilog(config) {
      MorphProgram(
        concreteWitness = new Component {
          setDefinitionName("ParameterizedWire")
          val din = in(Bits(8 bits))
          val dout = out(Bits(8 bits))
          dout := din
        },
        parameterizedDesign = parameterizedWireDesign()
      )
    }
  }

  private def parameterizedWireDesign(): Design = {
    val packed = PackedBits(ParameterRef("WIDTH"), Unsigned)
    Design(
      top = "ParameterizedWire",
      modules = Vector(
        ModuleDef(
          name = "ParameterizedWire",
          parameters = Vector(
            IntegerParameter(
              "WIDTH",
              default = 8,
              constraints = Vector(MinInclusive(1), MaxInclusive(Int.MaxValue))
            )
          ),
          ports = Vector(
            Port("din", Input, packed),
            Port("dout", Output, packed)
          ),
          items = Vector(ContinuousAssign(Ref("dout"), Ref("din")))
        )
      )
    )
  }
}
