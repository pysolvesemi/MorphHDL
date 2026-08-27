package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._

import morphhdl.frontend.{formalComponent, HdlInt, NativeIntShadow}

object StructureOnlyRetainedIntegerHelperSmoke {
  private def addressWidth(value: Int): Int =
    math.max(1, (BigInt(value) - 1).bitLength)

  final class FixedSink extends Component {
    setDefinitionName("StructureOnlyRetainedIntegerHelperSink")

    val din = in(Bits(8 bits))
    val observed = out(Bool())
    observed := din.orR
  }

  final class Leaf(depth: Int) extends Component {
    setDefinitionName("StructureOnlyRetainedIntegerHelperLeaf")

    @dontName val root = NativeIntShadow.captureArgument(depth, "depth")
    @dontName val pointerWidth = addressWidth(root)

    val din = in(Bits(8 bits))
    val observed = out(Bool())
    observed := din.orR

    if (pointerWidth > 2) {
      val branchValue = Bits(8 bits)
      branchValue := din
      val sink = new FixedSink
      sink.din := branchValue
    } else {
      val branchValue = Bits(8 bits)
      branchValue := ~din
      val sink = new FixedSink
      sink.din := branchValue
    }
  }

  final class Top(depth: HdlInt) extends Component {
    setDefinitionName("StructureOnlyRetainedIntegerHelperTop")

    val din = in(Bits(8 bits))
    val observed = out(Bool())
    val leaf = formalComponent.parameter(
      depth,
      "DEPTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )(value => new Leaf(value))

    leaf.din := din
    observed := leaf.observed
  }
}

class StructureOnlyRetainedIntegerHelperTests extends AnyFunSuite {
  import StructureOnlyRetainedIntegerHelperSmoke._

  test("structure-only native helper parameters lower in child and parent definitions") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "structure_only_retained_helper.v"
      val report = MorphVerilog(config) {
        val depth = HdlInt.param(
          "TOP_DEPTH",
          default = BigInt(5),
          min = BigInt(1),
          max = BigInt(16)
        )
        new Top(depth)
      }
      val verilog = new String(
        Files.readAllBytes(directory.resolve("structure_only_retained_helper.v")),
        StandardCharsets.UTF_8
      )
      val compact = verilog.replaceAll("\\s+", "")

      assert(report.parameters.map(_.name) == Vector("TOP_DEPTH"))
      assert(verilog.contains("module StructureOnlyRetainedIntegerHelperTop #("))
      assert(verilog.contains("parameter integer TOP_DEPTH = 5"))
      assert(verilog.contains("module StructureOnlyRetainedIntegerHelperLeaf #("))
      assert(verilog.contains("parameter integer DEPTH = 5"))
      assert(compact.contains(".DEPTH(TOP_DEPTH)"))
      assert(verilog.contains("function integer clog2;"))
      assert(compact.contains("clog2(DEPTH,1)"))
      assert(!verilog.contains("morphhdl_address_width"))
      assert(!verilog.contains("morphhdl_ceil_log2"))
      assert(verilog.contains("generate"))
      assert(verilog.contains("end else begin"))
    }
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-structure-helper-test-")
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
