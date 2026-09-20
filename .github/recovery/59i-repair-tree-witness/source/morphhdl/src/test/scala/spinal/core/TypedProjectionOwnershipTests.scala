package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.annotation.tailrec
import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.{MorphVerilog, MorphVerilogException}
import morphhdl.frontend.HdlInt

object TypedProjectionOwnershipFixture {
  final class Sink extends Component {
    setDefinitionName("TypedProjectionOwnershipSink")

    val din = in Bits (8 bits)
    val observed = out Bool()
    observed := din.orR
  }

  final class FreshNested(depth: ElabInt) extends Component {
    setDefinitionName("TypedProjectionFreshNested")

    val din = in Bits (8 bits)
    val alive = out Bool()
    alive := din.orR
    val aboveOne = depth > 1
    val aboveFour = depth > 4

    if (aboveOne) {
      if (aboveFour) {
        // Projection occurs at the final declaration owner. With DEPTH's
        // default at one, this exact nested owner is constructed at five.
        val local = UInt(depth.bits)
        local := 0
        retain(local, din)
      } else {
        marker(din)
      }
    } else {
      marker(din)
    }

    private def marker(input: Bits): Unit = {
      val value = Bits(8 bits)
      value := input
      val sink = new Sink
      sink.din := value
    }

    private def retain(value: UInt, input: Bits): Unit = {
      val branchWire = Bits(8 bits)
      branchWire := input ^ value.resize(8).asBits
      val sink = new Sink
      sink.din := branchWire
    }
  }

  final class PrecomputedNested(depth: ElabInt) extends Component {
    setDefinitionName("TypedProjectionPrecomputedNested")

    val din = in Bits (8 bits)
    val alive = out Bool()
    alive := din.orR
    val aboveOne = depth > 1
    val aboveFour = depth > 4

    if (aboveOne) {
      // This carrier is constructed for 2...8 (representative two), then used
      // to construct a declaration owned only by 5...8 (representative five).
      val precomputed = depth.bits
      if (aboveFour) {
        val local = UInt(precomputed)
        local := 0
        retain(local, din)
      } else {
        marker(din)
      }
    } else {
      marker(din)
    }

    private def marker(input: Bits): Unit = {
      val value = Bits(8 bits)
      value := input
      val sink = new Sink
      sink.din := value
    }

    private def retain(value: UInt, input: Bits): Unit = {
      val branchWire = Bits(8 bits)
      branchWire := input ^ value.resize(8).asBits
      val sink = new Sink
      sink.din := branchWire
    }
  }

  final class ModuleEscape(depth: ElabInt) extends Component {
    setDefinitionName("TypedProjectionModuleEscape")

    val din = in Bits (8 bits)
    val alive = out Bool()
    alive := din.orR
    val aboveOne = depth > 1

    var escaped: ParameterizedBitCount = null
    if (aboveOne) {
      marker(din)
    } else {
      marker(din)
      escaped = depth.bits
    }
    val local = UInt(escaped)
    local := 0

    private def marker(input: Bits): Unit = {
      val value = Bits(8 bits)
      value := input
      val sink = new Sink
      sink.din := value
    }
  }

  final class CopiedExpression(depth: ElabInt) extends Component {
    setDefinitionName("TypedProjectionCopiedExpression")

    val din = in Bits (8 bits)
    val alive = out Bool()
    alive := din.orR
    val aboveOne = depth > 1

    if (aboveOne) {
      val projected = depth.bits
      val copied = projected.copy(
        expression = projected.expression.map(_.copy())
      )
      val local = UInt(copied)
      local := 0
      retain(local, din)
    } else {
      marker(din)
    }

    private def marker(input: Bits): Unit = {
      val value = Bits(8 bits)
      value := input
      val sink = new Sink
      sink.din := value
    }

    private def retain(value: UInt, input: Bits): Unit = {
      val branchWire = Bits(8 bits)
      branchWire := input ^ value.resize(8).asBits
      val sink = new Sink
      sink.din := branchWire
    }
  }
}

class TypedProjectionOwnershipTests extends AnyFunSuite {
  import TypedProjectionOwnershipFixture._

  test("fresh nested projection is owned by its exact final declaration path") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "typed_projection_fresh_nested.v"
      MorphVerilog(config) {
        new FreshNested(depth(default = 1))
      }
      val verilog = read(directory.resolve(config.netlistFileName))
      assert(verilog.contains("parameter integer DEPTH = 1"))
      assert(verilog.contains("DEPTH) > (4"))
      assert(verilog.contains("DEPTH"))
    }
  }

  test("precomputed outer projection cannot construct a differently represented nested owner") {
    val error = interceptParameterized(() => new PrecomputedNested(depth(default = 1)))
    assert(
      error.code ==
        "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-REPRESENTATIVE-MISMATCH"
    )
  }

  test("branch-projected metadata cannot escape to a module-scope declaration") {
    val error = interceptParameterized(() => new ModuleEscape(depth(default = 1)))
    assert(
      error.code == "SPINAL-ELAB-DOMAIN-PROJECTION-SCOPE-EXPANSION"
    )
  }

  test("case-class expression copy loses exact projection authority") {
    val error = interceptParameterized(() => new CopiedExpression(depth(default = 1)))
    assert(error.code == "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING")
  }

  private def depth(default: Int): ElabInt =
    HdlInt
      .param(
        "DEPTH",
        default = BigInt(default),
        min = BigInt(1),
        max = BigInt(8)
      )
      .asElabInt

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def interceptParameterized(
      factory: () => Component
  ): ParameterizedVerilogException =
    withTemporaryDirectory { directory =>
      val error = intercept[MorphVerilogException] {
        MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
          factory()
        }
      }
      findParameterized(error).getOrElse {
        fail(s"Expected ParameterizedVerilogException, received ${error.failure}")
      }
    }

  @tailrec
  private def findParameterized(
      error: Throwable
  ): Option[ParameterizedVerilogException] =
    if (error == null) None
    else
      error match {
        case value: ParameterizedVerilogException => Some(value)
        case _                                    => findParameterized(error.getCause)
      }

  private def withTemporaryDirectory[T](body: Path => T): T = {
    val directory = Files.createTempDirectory("morphhdl-projection-owner-")
    try body(directory)
    finally {
      Files
        .walk(directory)
        .iterator()
        .asScala
        .toVector
        .sortBy(_.getNameCount)
        .reverse
        .foreach(path => Files.deleteIfExists(path))
    }
  }
}
