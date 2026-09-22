package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ArrayBuffer
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.core.internals._
import spinal.lib._

/** Product-independent copies of the three CDC-WIRE-01 source reproductions. */
object CdcWireCleanupFixtures {
  final class SliceCompare extends Component {
    setDefinitionName("CdcSliceCompareRepro")
    val dataBits: ElabInt = HdlInt.param("DATA_BITS", 32, 1, 2048).asElabInt
    val payload = in UInt((dataBits + 57) bits)
    val activeGeneration = in UInt(32 bits)
    val enabled, ready = in Bool()
    val valid, stale, consume = out Bool()
    val offset = ElabValue.uintLike(dataBits + 7, U(0, 12 bits), "generation_offset")
    def mismatch: Bool = (payload >> offset).resize(32) =/= activeGeneration
    valid := enabled && !mismatch
    stale := enabled && mismatch
    consume := enabled && (mismatch || ready)
  }

  final class GrayChain extends Component {
    setDefinitionName("CdcGrayChainRepro")
    val depth: ElabInt = HdlInt.param("FIFO_LOG_DEPTH", 3, 2, 16).asElabInt
    val gray = in Bits((depth + 2) bits)
    val readCount = in UInt((depth + 2) bits)
    val decoded, fill = out UInt((depth + 2) bits)
    decoded := fromGray(gray)
    fill := fromGray(gray) - readCount
  }

  final class WhenPredicate extends Component {
    setDefinitionName("CdcWhenPredicateRepro")
    val bypass, sourceValid, sourceUp, full = in Bool()
    val destinationUp, empty, ready = in Bool()
    val generationBits: ElabInt = HdlInt.param("GENERATION_BITS", 32, 2, 64).asElabInt
    val recordGeneration, activeGeneration = in UInt(generationBits bits)
    val writeCount, readCount = out(Reg(UInt(8 bits)) init(0))
    val writeGray, readGray = out(Reg(Bits(8 bits)) init(0))
    when(!bypass && sourceValid && sourceUp && !full) {
      writeCount := writeCount + 1
      writeGray := toGray(writeCount + 1).asBits
    }
    when(!bypass && destinationUp && !empty &&
        ((recordGeneration =/= activeGeneration) || ready)) {
      readCount := readCount + 1
      readGray := toGray(readCount + 1).asBits
    }
  }

  /** Unrelated names/operators, real unnamed fanout, and explicit barriers. */
  final class Controls extends Component {
    setDefinitionName("CdcWireControls")
    val width: ElabInt = HdlInt.param("CONTROL_BITS", 8, 4, 16).asElabInt
    val a, b = in Bits(width bits)
    val signedValue = in SInt(12 bits)
    val wideValue = in UInt(48 bits)
    val wideReference = in UInt((width + 1) bits)
    val shift = in UInt(4 bits)
    val choose, advance = in Bool()
    val left, right, kept, guardedValue, debugged, synchronized, geometryGuarded = out Bits(width bits)
    val arithmetic = out SInt(12 bits)
    val logical = out UInt(12 bits)
    val widenedSlice = out UInt(12 bits)
    val widenedSum = out UInt((width + 1) bits)
    val sumEqualsWide = out Bool()
    val namedPredicate = out Bool()
    val state = out(Reg(UInt(8 bits)) init(0))
    @dontName val first = Bits(width bits)
    @dontName val second = Bits(width bits)
    @dontName val third = Bits(width bits)
    first := a ^ b
    second := ~first
    third := Mux(choose, second, a)
    left := third
    right := third ^ b
    arithmetic := (signedValue >> shift).resize(12)
    logical := (signedValue.asUInt >> shift).resize(12)
    // The extra zero bit makes the inner 11-bit truncation observable even
    // when the final receiver is wider. Low Z bits must remain Z, not X.
    widenedSlice := (wideValue >> shift).resize(11).resize(12)
    @dontName val modularSum = UInt(width bits)
    modularSum := a.asUInt + b.asUInt
    widenedSum := modularSum.resize(width + 1)
    sumEqualsWide := modularSum.resize(width + 1) === wideReference
    val protectedKeep = Bits(width bits)
    protectedKeep.addAttribute("keep")
    protectedKeep := first
    kept := protectedKeep
    val protectedGuard = Bits(width bits)
    protectedGuard.dontSimplifyIt()
    protectedGuard := second
    guardedValue := protectedGuard
    val protectedDebug = Bits(width bits)
    protectedDebug.addTag(spinal.core.sim.SimPublic)
    protectedDebug := first
    debugged := protectedDebug
    val protectedCdc = Bits(width bits)
    protectedCdc.addAttribute("async_reg", "true")
    protectedCdc := second
    synchronized := protectedCdc
    val protectedGeometry = CdcWireGeometryControl.retainedAndProtected(Bits(width bits))
    protectedGeometry := first
    geometryGuarded := protectedGeometry
    @dontName val userPredicate = a === b
    userPredicate.setName("when_user_l42")
    namedPredicate := userPredicate
    when(advance) {
      when(userPredicate) { state := state + 1 }
    }
  }

  val names: Vector[String] = Vector("CdcSliceCompareRepro", "CdcGrayChainRepro",
    "CdcWhenPredicateRepro", "CdcWireControls")

  def component(name: String): Component = name match {
    case "CdcSliceCompareRepro" => new SliceCompare
    case "CdcGrayChainRepro" => new GrayChain
    case "CdcWhenPredicateRepro" => new WhenPredicate
    case "CdcWireControls" => new Controls
    case other => throw new IllegalArgumentException(other)
  }
}

/** Verifies the first production invocation already reached a native fixed point.
  * Identity comparison is deliberately stronger than equivalent emitted text:
  * a second invocation cannot replace a node or driver, even with an equal copy.
  */
private[examples] object CdcWireCleanupFixedPointObserver {
  def install(config: SpinalConfig, output: Path): Unit = {
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val index = phases.indexWhere(_.getClass.getName ==
        "morphhdl.examples.ProductionWireAssignmentPhase")
      require(index >= 0, "missing real production wire-assignment phase")
      val production = phases(index)
      val intent = phases.collectFirst { case value: NativeConditionSourceIntent => value }.get
      phases.update(index, new Phase {
        override def hasNetlistImpact: Boolean = true
        override def impl(pc: PhaseContext): Unit = {
          def snapshot(): Vector[AnyRef] = {
            val values = ArrayBuffer.empty[AnyRef]
            pc.components().foreach { component =>
              values += component
              component.dslBody.walkStatements { statement =>
                values += statement
                statement.walkExpression(expression => values += expression)
              }
            }
            values.toVector
          }
          val before = snapshot().size
          production.impl(pc)
          val once = snapshot()
          new ProductionWireAssignmentPhase(intent).impl(pc)
          val twice = snapshot()
          require(once.size == twice.size && once.zip(twice).forall { case (a, b) => a eq b },
            "CDC-WIRE-01: second invocation changed the first invocation's native graph")
          Files.createDirectories(output.getParent)
          val report = s"""{"before_nodes":$before,"after_nodes":${once.size},"second_invocation_changes":0}"""
          Files.write(output, (report + "\n").getBytes(StandardCharsets.UTF_8))
        }
      })
    }
  }
}

object CdcWireCleanupArtifactWriter {
  def generate(directory: Path, name: String, enabled: Boolean, observe: Boolean = false): String = {
    Files.createDirectories(directory)
    val config = MorphWireAssignmentPasses(SpinalConfig(
      targetDirectory = directory.toString, oneFilePerComponent = true,
      headerWithDate = false, headerWithRepoHash = true), enabled)
    if (observe) CdcWireCleanupFixedPointObserver.install(config,
      directory.resolve(name + ".fixed-point.json"))
    MorphVerilog(config) { CdcWireCleanupFixtures.component(name) }
    new String(Files.readAllBytes(directory.resolve(name + ".v")), StandardCharsets.UTF_8)
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: CdcWireCleanupArtifactWriter <output-directory>")
    val root = Paths.get(args(0))
    CdcWireCleanupFixtures.names.foreach { name =>
      val enabled = generate(root.resolve("enabled"), name, enabled = true, observe = true)
      val repeat = generate(root.resolve("repeat"), name, enabled = true)
      require(enabled == repeat, s"$name emission is not deterministic or observation-neutral")
      generate(root.resolve("disabled"), name, enabled = false)
    }
    println("CDC_WIRE_ARTIFACTS_PASS deterministic=true one_invocation_fixed_point=true")
  }
}
