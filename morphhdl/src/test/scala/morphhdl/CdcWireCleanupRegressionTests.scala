package morphhdl

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._
import morphhdl.examples.{CdcWireCleanupArtifactWriter, CdcWireCleanupFixtures}
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.core.internals.{Phase, PhaseContext}
import scala.collection.mutable.ArrayBuffer

/** HDL semantics and the independent oracles run in check-cdc-wire-cleanup.py. */
class CdcWireCleanupRegressionTests extends AnyFunSuite {
  private def withDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("cdc-wire-cleanup-")
    try body(directory)
    finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse
        .foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  for (name <- CdcWireCleanupFixtures.names) {
    test(s"$name converges in one production invocation with deterministic emission") {
      withDirectory { directory =>
        val first = CdcWireCleanupArtifactWriter.generate(directory.resolve("first"), name,
          enabled = true, observe = true)
        val repeat = CdcWireCleanupArtifactWriter.generate(directory.resolve("repeat"), name,
          enabled = true)
        assert(first == repeat, s"$name observation or repeat changed emitted RTL")
        assert(Files.exists(directory.resolve("first").resolve(name + ".fixed-point.json")))
      }
    }
  }

  test("generated-looking explicit names and keep/debug/CDC barriers survive recursive cleanup") {
    withDirectory { directory =>
      val source = CdcWireCleanupArtifactWriter.generate(directory, "CdcWireControls", enabled = true)
      for (name <- Vector("protectedKeep", "protectedGuard", "protectedDebug", "protectedCdc",
          "protectedGeometry", "when_user_l42"))
        assert(("(?m)^\\s*assign\\s+" + name + "\\s*=").r.findFirstIn(source).nonEmpty,
          s"protected identity $name disappeared:\n$source")
      assert(source.contains("async_reg"), source)
      assert(source.contains("state <="), source)
    }
  }

  test("shared unnamed expressions above the duplication budget retain their actual identity") {
    withDirectory { directory =>
      var carrier: Bits = null
      MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
        oneFilePerComponent = true, headerWithDate = false))) {
        new Component {
          setDefinitionName("CdcWireBudgetControl")
          val width: ElabInt = HdlInt.param("WIDTH", 8, 6, 16).asElabInt
          val a, b = in Bits(width bits)
          val receivers = out Vec(Bits(width bits), 33)
          @dontName val shared = Bits(width bits)
          shared := a ^ b
          carrier = shared
          for (index <- 0 until 33) receivers(index) := shared ^ B(index, 6 bits).resize(width)
        }
      }
      val source = new String(Files.readAllBytes(directory.resolve("CdcWireBudgetControl.v")),
        StandardCharsets.UTF_8)
      val name = java.util.regex.Pattern.quote(carrier.getName())
      assert(("(?m)^\\s*assign\\s+" + name + "\\s*=").r.findFirstIn(source).nonEmpty,
        "shared carrier above the aggregate duplication budget disappeared:\n" + source)
    }
  }

  test("shared compiler expression nodes inline while independently protected expression nodes remain") {
    withDirectory { directory =>
      var protectedNode: UInt = null
      MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
        oneFilePerComponent = true, headerWithDate = false))) {
        new Component {
          setDefinitionName("CdcSharedExpressionNodeControl")
          val width: ElabInt = HdlInt.param("WIDTH", 8, 4, 16).asElabInt
          val a, b = in UInt(width bits)
          val advance = in Bool()
          val first, second, protectedValue = out UInt(width bits)
          val sampled = out(Reg(UInt(width bits)) init(0))
          @dontName val shared = a + b
          first := shared
          second := shared ^ b
          when(advance) { sampled := shared }
          @dontName val protectedExpression = a ^ b
          protectedExpression.dontSimplifyIt()
          protectedNode = protectedExpression
          protectedValue := protectedExpression
        }
      }
      val source = new String(Files.readAllBytes(directory.resolve("CdcSharedExpressionNodeControl.v")),
        StandardCharsets.UTF_8)
      assert(source.contains("assign first = (a + b);"), source)
      assert(source.contains("assign second = ((a + b) ^ b);"), source)
      assert(source.contains("sampled <= (a + b);"), source)
      val name = java.util.regex.Pattern.quote(protectedNode.getName())
      assert(("(?m)^\\s*assign\\s+" + name + "\\s*=").r.findFirstIn(source).nonEmpty, source)
    }
  }

  test("simplified compiler alias nodes reach every receiver while explicit and protected aliases remain") {
    for (generatedName <- Vector(false, true)) withDirectory { directory =>
      var sharedNode, protectedNode, explicitlyNamedNode: UInt = null
      var inspected = false
      val config = MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
        oneFilePerComponent = true, headerWithDate = false))
      config.phasesInserters += { phases: ArrayBuffer[Phase] =>
        val index = phases.indexWhere(_.getClass.getName ==
          "morphhdl.examples.ProductionWireAssignmentPhase")
        require(index >= 0)
        phases.insert(index + 1, new Phase {
          override def hasNetlistImpact: Boolean = false
          override def impl(pc: PhaseContext): Unit = {
            val declarations = ArrayBuffer.empty[BaseType]
            pc.walkDeclarations {
              case value: BaseType => declarations += value
              case _ =>
            }
            assert(!declarations.exists(_ eq sharedNode),
              "the first production invocation retained the simplified shared alias identity")
            assert(declarations.exists(_ eq protectedNode), "dontSimplify alias identity disappeared")
            assert(declarations.exists(_ eq explicitlyNamedNode), "explicit alias identity disappeared")
            inspected = true
          }
        })
      }
      MorphVerilog(config) {
        new Component {
          setDefinitionName("CdcSharedZeroShiftAliasControl")
          val width: ElabInt = HdlInt.param("WIDTH", 8, 4, 16).asElabInt
          val payload, other = in UInt(width bits)
          val advance = in Bool()
          val first, second, protectedValue, explicitValue = out UInt(width bits)
          val sampled = out(Reg(UInt(width bits)) init(0))
          // This starts as a real compiler expression type node. Ordinary
          // simplification exposes a direct alias before the wire pipeline.
          @dontName val shared = payload >> 0
          if (generatedName) shared.setCompositeName(payload, "folded", Nameable.REMOVABLE)
          sharedNode = shared
          first := shared
          second := shared ^ other
          // The generated-named alias profile remains continuous-only;
          // the unnamed profile also authorizes a nonblocking receiver.
          when(advance) { sampled := (if (generatedName) payload else shared) }
          @dontName val protectedAlias = payload >> 0
          if (generatedName) protectedAlias.setCompositeName(payload, "protected", Nameable.REMOVABLE)
          protectedAlias.dontSimplifyIt()
          protectedNode = protectedAlias
          protectedValue := protectedAlias
          @dontName val explicitAlias = payload >> 0
          explicitAlias.setName("when_user_alias")
          explicitlyNamedNode = explicitAlias
          explicitValue := explicitAlias
        }
      }
      assert(inspected, "native identity observer did not run")
      val source = new String(Files.readAllBytes(directory.resolve("CdcSharedZeroShiftAliasControl.v")),
        StandardCharsets.UTF_8)
      assert(source.contains("assign first = payload;"), source)
      assert(source.contains("assign second = (payload ^ other);"), source)
      assert(source.contains("sampled <= payload;"), source)
      for (node <- Vector(protectedNode, explicitlyNamedNode)) {
        val name = java.util.regex.Pattern.quote(node.getName())
        assert(("(?m)^\\s*assign\\s+" + name + "\\s*=").r.findFirstIn(source).nonEmpty, source)
      }
    }
  }
}
