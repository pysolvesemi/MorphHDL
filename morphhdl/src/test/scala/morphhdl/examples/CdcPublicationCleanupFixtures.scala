package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ArrayBuffer
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.core.internals._

/** Standalone occupancy and naming fixtures; no application/private IP inputs. */
object CdcPublicationCleanupFixtures {
  final class Fill extends Component {
    setDefinitionName("RecursiveFillCleanupRepro")
    val depth: ElabInt = HdlInt.param("FIFO_LOG_DEPTH", 3, 2, 16).asElabInt
    val countBits: ElabInt = depth + 2
    val capacity: ElabInt = depth.pow2 + 1
    val bypass = in Bool()
    val upper, lower = in UInt(countBits bits)
    val writeFill, readFill = out UInt(countBits bits)
    val capacityValue = ElabValue.uintLike(capacity, U(0, 18 bits), "fifo_capacity")
    val upperWide = UInt(18 bits)
    upperWide := upper.resize(18)
    val selectedUpper = UInt(18 bits)
    selectedUpper := Mux(upper > capacityValue, capacityValue, upperWide)
    val clampedUpper = UInt(countBits bits)
    clampedUpper := selectedUpper.resize(countBits)
    val zeroFill = UInt(countBits bits)
    zeroFill := 0
    writeFill := Mux(bypass, zeroFill, clampedUpper)
    readFill := Mux(bypass || lower > capacityValue, zeroFill, lower)
  }

  final class Naming(width: ElabInt) extends Component {
    setDefinitionName("ResizeTemporaryNamingRepro")
    val a, b = in UInt(width bits)
    val wide, namedWide, zzNamedWide, userPrefixWide, collisionWide = out UInt(18 bits)
    val roundTrip = out UInt(width bits)
    @dontName val difference = a - b
    wide := difference.resize(18)
    roundTrip := wide.resize(width)
    val kept = UInt(width bits).setName("kept_difference").dontSimplifyIt()
    kept := a - b
    namedWide := kept.resize(18)
    val zzKept = UInt(width bits).setName("_zz_user_kept").dontSimplifyIt()
    zzKept := a - b
    zzNamedWide := zzKept.resize(18)
    val userPrefix = UInt(width bits).setName("morphhdl_resize_source").dontSimplifyIt()
    userPrefix := a - b
    userPrefixWide := userPrefix.resize(18)
    val collision = UInt(width bits).setName("_zz_wide").dontSimplifyIt()
    collision := a - b
    collisionWide := collision.resize(18)
  }

  final class NamingHierarchy extends Component {
    setDefinitionName("ResizeNamingHierarchy")
    val width: ElabInt = HdlInt.param("COUNT_BITS", 5, 4, 18).asElabInt
    val a, b = in UInt(width bits)
    val wide, namedWide, zzNamedWide, userPrefixWide, collisionWide = out UInt(18 bits)
    val roundTrip = out UInt(width bits)
    val first = CdcPublicationFormalControl.bind(width)(formalWidth => new Naming(formalWidth))
    val second = CdcPublicationFormalControl.bind(width)(formalWidth => new Naming(formalWidth))
    first.a := a; first.b := b
    second.a := a; second.b := b
    wide := first.wide
    namedWide := second.namedWide
    zzNamedWide := first.zzNamedWide
    userPrefixWide := second.userPrefixWide
    collisionWide := first.collisionWide
    roundTrip := second.roundTrip
  }

  final class ZeroWidthControl extends Component {
    setDefinitionName("CdcZeroWidthControl")
    val width: ElabInt = HdlInt.param("WIDTH", 5, 4, 18).asElabInt
    val value = in UInt(width bits)
    val equalsComplement = out Bool()
    val zero = UInt(width bits)
    zero := 0
    equalsComplement := (~zero) === value
  }

  def zeroWidthControl(directory: Path): Unit = {
    val config = MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
      oneFilePerComponent = true, headerWithDate = false, headerWithRepoHash = true))
    var inspected = false
    config.phasesInserters += { phases =>
      val index = phases.indexWhere(_.getClass.getSimpleName == "ProductionWireAssignmentPhase")
      phases.insert(index + 1, new Phase {
        override def hasNetlistImpact: Boolean = false
        override def impl(pc: PhaseContext): Unit = {
          val f = pc.topLevel.asInstanceOf[ZeroWidthControl]
          val reason = new NamedWireExpressionNativePhase().retentionReasonFor(pc, f.zero)
          assert(reason.contains("WA10-NATIVE-ZERO-RECEIVER-WIDTH-AUTHORITY"), reason.toString)
          inspected = true
        }
      })
    }
    MorphVerilog(config) { new ZeroWidthControl }
    assert(inspected)
  }

  def generate(directory: Path, naming: Boolean, enabled: Boolean, observe: Boolean,
      hierarchy: Boolean = false): String = {
    Files.createDirectories(directory)
    val config = MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
      oneFilePerComponent = true, headerWithDate = false, headerWithRepoHash = true), enabled)
    var inspected = false
    if (observe) CdcWireCleanupFixedPointObserver.install(config, directory.resolve("fixed-point.json"))
    if (observe) config.phasesInserters += { phases =>
      // The fixed-point observer wraps the production phase at this index.
      val index = phases.indexWhere(_.getClass.getName.contains("CdcWireCleanupFixedPointObserver"))
      require(index >= 0, "identity observation requires the enabled pipeline")
      phases.insert(index + 1, new Phase {
        override def hasNetlistImpact: Boolean = false
        override def impl(pc: PhaseContext): Unit = {
          val values = ArrayBuffer.empty[BaseType]
          pc.walkDeclarations { case b: BaseType => values += b; case _ => }
          pc.components().foreach {
            case f: Fill =>
              assert(!values.exists(_ eq f.clampedUpper), "same-width forwarding identity survived")
              assert(!values.exists(_ eq f.zeroFill), "shared symbolic zero identity survived")
              assert(f.readFill.head.source.isInstanceOf[BinaryMultiplexer], "read mux carrier survived")
              assert(f.selectedUpper.head.source.isInstanceOf[BinaryMultiplexer], "selected mux carrier survived")
              assert(values.exists(_ eq f.selectedUpper), "preserved resize source was erased")
              assert(f.selectedUpper.hasTag(noBackendCombMerge))
            case f: Naming =>
              assert(NativeWireNameProvenance.origin(f.difference).contains(morphhdl.ir.v1.NameOrigin.Unnamed),
                "resize capture changed unnamed source provenance")
              assert(values.exists(_ eq f.difference), "modular subtraction boundary was erased")
              assert(f.difference.hasTag(noBackendCombMerge))
              for ((node, name) <- Vector(f.kept -> "kept_difference", f.zzKept -> "_zz_user_kept",
                  f.userPrefix -> "morphhdl_resize_source", f.collision -> "_zz_wide")) {
                assert(values.exists(_ eq node) && node.getName() == name,
                  "explicit protected identity/name changed: " + name)
              }
            case _: NamingHierarchy =>
            case _ => sys.error("unexpected publication fixture")
          }
          inspected = true
        }
      })
    }
    MorphVerilog(config) { if (hierarchy) new NamingHierarchy
      else if (naming) new Naming(HdlInt.param("FIFO_LOG_DEPTH", 3, 2, 16).asElabInt + 2) else new Fill }
    assert(!observe || inspected, "native identity observer did not execute")
    val name = if (hierarchy) "ResizeNamingHierarchy" else if (naming) "ResizeTemporaryNamingRepro" else "RecursiveFillCleanupRepro"
    new String(Files.readAllBytes(directory.resolve(name + ".v")), StandardCharsets.UTF_8)
  }
}

object CdcPublicationCleanupArtifactWriter extends App {
  require(args.length == 1)
  val root = Paths.get(args(0))
  for ((naming, prefix) <- Vector(false -> "fill", true -> "naming")) {
    val first = CdcPublicationCleanupFixtures.generate(root.resolve(prefix + "-on"), naming, true, true)
    val repeat = CdcPublicationCleanupFixtures.generate(root.resolve(prefix + "-repeat"), naming, true, false)
    require(first == repeat, "observation/repeated generation changed the emitted artifact")
    CdcPublicationCleanupFixtures.generate(root.resolve(prefix + "-off"), naming, false, false)
  }
  val first = CdcPublicationCleanupFixtures.generate(root.resolve("hierarchy-on"), true, true, true, true)
  val repeat = CdcPublicationCleanupFixtures.generate(root.resolve("hierarchy-repeat"), true, true, false, true)
  require(first == repeat)
  CdcPublicationCleanupFixtures.generate(root.resolve("hierarchy-off"), true, false, false, true)
}
