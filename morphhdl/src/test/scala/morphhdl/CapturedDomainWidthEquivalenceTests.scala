package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import spinal.core._

object CapturedDomainWidthEquivalenceSmoke {
  final class ExactSingletonDepth(depth: ElabInt) extends Component {
    setDefinitionName("CapturedDomainExactSingletonDepth")

    val narrow = in UInt (1 bits)
    val matching = in UInt (log2Up(depth + 1) bits)
    val observed = out UInt (log2Up(depth + 1) bits)

    if (depth == 1) {
      observed := narrow
    } else {
      observed := matching
    }
  }

  final class VaryingNarrowDepth(depth: ElabInt) extends Component {
    setDefinitionName("CapturedDomainVaryingNarrowDepth")

    val narrow = in UInt (1 bits)
    val matching = in UInt (log2Up(depth + 1) bits)
    val observed = out UInt (log2Up(depth + 1) bits)

    if (depth <= 2) {
      observed := narrow
    } else {
      observed := matching
    }
  }

  final class IndependentPredicateRoot(
      targetDepth: ElabInt,
      branchDepth: ElabInt
  ) extends Component {
    setDefinitionName("CapturedDomainIndependentPredicateRoot")

    val narrow = in UInt (1 bits)
    val matching = in UInt (log2Up(targetDepth + 1) bits)
    val observed = out UInt (log2Up(targetDepth + 1) bits)

    if (branchDepth == 1) {
      observed := narrow
    } else {
      observed := matching
    }
  }

  final class TypedResizeConsumerMismatch(width: ElabInt) extends Component {
    setDefinitionName("TypedResizeConsumerMismatch")

    val source = in UInt (width bits)
    val observed = out UInt ((width + (width == 4).toElabInt) bits)

    observed := source.resize(width)
  }
}

class CapturedDomainWidthEquivalenceTests extends AnyFunSuite {
  import CapturedDomainWidthEquivalenceSmoke._

  test("a captured singleton domain proves symbolic target and concrete source widths equal") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "captured_domain_exact_singleton_depth.v"
      config.netlistFileName = fileName
      val depth =
        HdlInt.param("DEPTH", default = 1, min = 1, max = 8).asElabInt

      MorphVerilog(config)(new ExactSingletonDepth(depth))

      val verilog = new String(
        Files.readAllBytes(directory.resolve(fileName)),
        StandardCharsets.UTF_8
      )
      assert(verilog.contains("parameter integer DEPTH = 1"))
      assert(verilog.replaceAll("\\s+", "").contains("DEPTH)==(1"))
    }
  }

  test("a captured domain rejects a concrete source when symbolic target width varies") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "captured_domain_varying_narrow_depth.v"
      config.netlistFileName = fileName
      val depth =
        HdlInt.param("DEPTH", default = 1, min = 1, max = 8).asElabInt

      expectWidthMismatch(
        directory,
        fileName,
        MorphVerilog.tryGenerate(config)(new VaryingNarrowDepth(depth))
      )
    }
  }

  test("same witnesses never correlate an independent predicate root with target width") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "captured_domain_independent_predicate_root.v"
      config.netlistFileName = fileName
      val targetDepth =
        HdlInt.param("TARGET_DEPTH", default = 1, min = 1, max = 8).asElabInt
      val branchDepth =
        HdlInt.param("BRANCH_DEPTH", default = 1, min = 1, max = 8).asElabInt

      expectWidthMismatch(
        directory,
        fileName,
        MorphVerilog.tryGenerate(config) {
          new IndependentPredicateRoot(targetDepth, branchDepth)
        }
      )
    }
  }

  test("a typed resize cannot lend its witness to a different consumer width") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_resize_consumer_mismatch.v"
      config.netlistFileName = fileName
      val width =
        HdlInt.param("WIDTH", default = 3, min = 2, max = 4).asElabInt

      expectWidthMismatch(
        directory,
        fileName,
        MorphVerilog.tryGenerate(config) {
          new TypedResizeConsumerMismatch(width)
        }
      )
    }
  }

  private def expectWidthMismatch(
      directory: Path,
      fileName: String,
      result: Either[MorphVerilogFailure, MorphSingleSourceVerilogReport]
  ): Unit = {
    result match {
      case Left(failure) =>
        assert(
          failure.detail.contains(
            "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
          ),
          failure.detail
        )
      case Right(report) =>
        fail(s"Expected captured-domain width mismatch, received $report")
    }
    assert(!Files.exists(directory.resolve(fileName)))
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-captured-domain-width-")
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
