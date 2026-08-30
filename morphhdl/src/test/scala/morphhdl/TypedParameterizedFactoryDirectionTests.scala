package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._
import morphhdl.frontend.HdlInt

object TypedParameterizedFactoryDirectionSmoke {
  final class Top(width: ElabInt) extends Component {
    setDefinitionName("TypedParameterizedFactoryDirectionTop")

    val bitsIn = in Bits (width bits)
    val bitsOut = out Bits (width bits)
    val uintIn = in UInt (width bits)
    val uintOut = out UInt (width bits)
    val sintIn = in SInt (width bits)
    val sintOut = out SInt (width bits)

    require(bitsIn.isInput, "typed Bits factory lost input direction")
    require(bitsOut.isOutput, "typed Bits factory lost output direction")
    require(uintIn.isInput, "typed UInt factory lost input direction")
    require(uintOut.isOutput, "typed UInt factory lost output direction")
    require(sintIn.isInput, "typed SInt factory lost input direction")
    require(sintOut.isOutput, "typed SInt factory lost output direction")

    bitsOut := bitsIn
    uintOut := uintIn
    sintOut := sintIn
  }

  def component(): Top =
    new Top(
      HdlInt
        .param("WIDTH", default = 8, min = 1, max = 16)
        .asElabInt
    )
}

class TypedParameterizedFactoryDirectionTests extends AnyFunSuite {
  import TypedParameterizedFactoryDirectionSmoke._

  test("typed packed factories retain short-form input and output dispatch") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "typed_parameterized_factory_direction.v"
      val report = MorphVerilog(config)(component())
      val verilog = new String(
        Files.readAllBytes(
          directory.resolve("typed_parameterized_factory_direction.v")
        ),
        StandardCharsets.UTF_8
      )

      assert(report.parameters.map(_.name) == Vector("WIDTH"))
      assert(verilog.contains("parameter integer WIDTH = 8"))
      Vector("bitsIn", "bitsOut", "uintIn", "uintOut", "sintIn", "sintOut")
        .foreach(name => assert(hasWidth(verilog, name), s"missing typed WIDTH on $name"))
    }
  }

  private def hasWidth(verilog: String, signal: String): Boolean = {
    val compact = verilog.replaceAll("\\s+", "")
    compact.contains(s"[WIDTH-1:0]$signal") ||
    compact.contains(s"[(WIDTH-1):0]$signal")
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-typed-factory-direction-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach {
          path => Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
