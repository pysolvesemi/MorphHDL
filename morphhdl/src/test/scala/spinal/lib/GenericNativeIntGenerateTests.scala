package spinal.lib

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._

import morphhdl.MorphVerilog

final class DisjointNativeGenerateLeaf(choice: Int) extends Component {
  setDefinitionName("DisjointNativeGenerateLeaf")
  val observed = out(Bool())

  (choice == 1).generate {
    observed := False
  }
  (choice > 1).generate {
    observed := True
  }
}

object DisjointNativeGenerateLeaf {
  def apply(choice: ParameterizedMemoryDepth): DisjointNativeGenerateLeaf =
    ExternalNativeIntFormalComponent.parameter(
      actual = choice,
      name = "CHOICE",
      minimum = BigInt(1),
      maximum = BigInt(Int.MaxValue) - 1
    )(witness => new DisjointNativeGenerateLeaf(witness))
}

final class OverlappingNativeGenerateLeaf(choice: Int) extends Component {
  setDefinitionName("OverlappingNativeGenerateLeaf")
  val observed = out(Bool())

  (choice >= 1).generate {
    observed := False
  }
  (choice > 1).generate {
    observed := True
  }
}

object OverlappingNativeGenerateLeaf {
  def apply(choice: ParameterizedMemoryDepth): OverlappingNativeGenerateLeaf =
    ExternalNativeIntFormalComponent.parameter(
      actual = choice,
      name = "CHOICE",
      minimum = BigInt(1),
      maximum = BigInt(Int.MaxValue) - 1
    )(witness => new OverlappingNativeGenerateLeaf(witness))
}

final class ReachableNativeGenerateLeaf(choice: Int) extends Component {
  setDefinitionName("ReachableNativeGenerateLeaf")
  val observed = out(UInt(3 bits))

  // This body is deliberately invalid for the concrete graph, but its exact
  // truth domain is empty over the canonical positive constructor domain.
  (choice == 0).generate {
    observed := 0
  }
  // The witness is five, so this body proves that reachable false alternatives
  // are still captured rather than specialized away.
  (choice == 1).generate {
    observed := U(1, 3 bits)
  }
  (choice > 1).generate {
    observed := U(2, 3 bits)
  }
}

object ReachableNativeGenerateLeaf {
  def apply(choice: ParameterizedMemoryDepth): ReachableNativeGenerateLeaf =
    ExternalNativeIntFormalComponent.parameter(
      actual = choice,
      name = "CHOICE",
      minimum = BigInt(1),
      maximum = BigInt(Int.MaxValue) - 1
    )(witness => new ReachableNativeGenerateLeaf(witness))
}

private object GenericNativeGenerateActual {
  def apply(): ParameterizedMemoryDepth = {
    val parameter = ElaborationIntegerParameter("SELECT", 5, 1, 8)
    ParameterizedMemoryDepth(
      value = 5,
      expression = ElaborationIntegerExpression(
        verilog = parameter.name,
        default = parameter.default,
        minimum = parameter.minimum,
        maximum = parameter.maximum,
        parameters = Vector(parameter),
        sourceLocation = Some("GenericNativeIntGenerateTests.scala:SELECT")
      )
    )
  }
}

final class DisjointNativeGenerateHarness extends Component {
  setDefinitionName("DisjointNativeGenerateHarness")
  val observed = out(Bool())
  val leaf = DisjointNativeGenerateLeaf(GenericNativeGenerateActual())
  observed := leaf.observed
}

final class OverlappingNativeGenerateHarness extends Component {
  setDefinitionName("OverlappingNativeGenerateHarness")
  val observed = out(Bool())
  val leaf = OverlappingNativeGenerateLeaf(GenericNativeGenerateActual())
  observed := leaf.observed
}

final class ReachableNativeGenerateHarness extends Component {
  setDefinitionName("ReachableNativeGenerateHarness")
  val observed = out(UInt(3 bits))
  val leaf = ReachableNativeGenerateLeaf(GenericNativeGenerateActual())
  observed := leaf.observed
}

class GenericNativeIntGenerateTests extends AnyFunSuite {
  test("independent generically instrumented generate regions accept exact disjoint domains") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        mode = Verilog,
        targetDirectory = directory.toString
      )
      config.netlistFileName = "generic_native_generate.v"
      MorphVerilog(config)(new DisjointNativeGenerateHarness)

      val verilog = new String(
        Files.readAllBytes(directory.resolve("generic_native_generate.v")),
        StandardCharsets.UTF_8
      ).replaceAll("\\s+", "")
      assert(verilog.contains("moduleDisjointNativeGenerateLeaf#("))
      assert(verilog.contains("CHOICE==1") || verilog.contains("(CHOICE)==(1)"))
      assert(verilog.contains("CHOICE>1") || verilog.contains("(CHOICE)>(1)"))
    }
  }

  test("independent generically instrumented generate regions reject overlapping domains") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        mode = Verilog,
        targetDirectory = directory.toString
      )
      config.netlistFileName = "generic_native_generate_overlap.v"
      MorphVerilog.tryGenerate(config)(new OverlappingNativeGenerateHarness) match {
        case Left(failure) =>
          assert(failure.detail.contains("ASSIGNMENT OVERLAP"))
        case Right(report) =>
          fail(s"Expected inherited overlap failure, received $report")
      }
    }
  }

  test("empty-domain generate is elided while a reachable witness-false body is captured") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        mode = Verilog,
        targetDirectory = directory.toString
      )
      config.netlistFileName = "generic_native_generate_reachable.v"
      MorphVerilog(config)(new ReachableNativeGenerateHarness)

      val verilog = new String(
        Files.readAllBytes(directory.resolve("generic_native_generate_reachable.v")),
        StandardCharsets.UTF_8
      ).replaceAll("\\s+", "")
      assert(verilog.contains("CHOICE==1") || verilog.contains("(CHOICE)==(1)"))
      assert(!verilog.contains("CHOICE==0") && !verilog.contains("(CHOICE)==(0)"))
    }
  }

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("generic-native-int-generate-test-")
    try body(directory)
    finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }
}
