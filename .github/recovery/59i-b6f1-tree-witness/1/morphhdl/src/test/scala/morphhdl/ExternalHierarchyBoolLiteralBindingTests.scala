package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

/** Regression for native library children whose concrete control inputs carry
  * a literal default alongside their ordinary parent connection.
  */
class ExternalHierarchyBoolLiteralBindingTests extends AnyFunSuite {
  private final class TypedQueueTop(depth: HdlInt) extends Component {
    setDefinitionName("ExternalHierarchyBoolLiteralQueueTop")

    val push = slave(Stream(Bits(8 bits)))
    val pop = master(Stream(Bits(8 bits)))

    pop << push.queue(depth)
  }

  test("typed Stream queue admits the concrete Bool literal on child flush") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param("DEPTH", default = 5, min = 2, max = 8)
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "external_hierarchy_bool_literal.v"

      MorphVerilog(config)(new TypedQueueTop(depth))
      val verilog = new String(
        Files.readAllBytes(directory.resolve(config.netlistFileName)),
        StandardCharsets.UTF_8
      )

      assert(verilog.contains("module ExternalHierarchyBoolLiteralQueueTop #("))
      assert(verilog.contains("parameter integer DEPTH = 5"))
      assert(verilog.contains("module StreamFifo #("))
      assert(verilog.contains(".DEPTH(DEPTH)"))
      assert(verilog.replaceAll("\\s+", "").contains(".io_flush(1'b0)"))
    }
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-bool-literal-binding-")
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
