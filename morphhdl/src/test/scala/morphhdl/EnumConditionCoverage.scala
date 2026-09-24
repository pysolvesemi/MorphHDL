package morphhdl

import java.nio.file.{Files, Path, Paths}

import spinal.core._
import spinal.lib.fsm._
import spinal.lib.KeepAttribute
import morphhdl.frontend.HdlInt

/** Encoding modes use only StateMachine APIs present in this checkout. */
object EnumConditionEncodingMode {
  final val Binary = 0
  final val OneHot = 1
  final val CustomWidth3 = 2
  final val CustomWidth4 = 3

  def label(mode: Int): String = mode match {
    case Binary => "Binary"
    case OneHot => "OneHot"
    case CustomWidth3 => "CustomWidth3"
    case CustomWidth4 => "CustomWidth4"
    case other => throw new IllegalArgumentException(s"unsupported enum encoding mode $other")
  }
}

/** Multi-receiver, inequality, priority and recursive-alias production fixture. */
class EnumConditionCoverage(width: ElabInt, mode: Int) extends Component {
  import EnumConditionEncodingMode._

  setDefinitionName("EnumCondition" + label(mode) + "Coverage")
  val io = new Bundle {
    val enable = in Bool()
    val stall = in Bool()
    val accept = in Bool()
    val data = in Bits(width bits)
    val active = out Bool()
    val nonIdle = out Bool()
    val nested = out Bool()
    val result = out Bits(width bits)
    val transitions = out UInt(4 bits)
  }

  val fsm = new StateMachine {
    val IDLE = makeInstantEntry()
    val ACTIVE = new State
    val DRAIN = new State
    mode match {
      case Binary => setEncoding(binarySequential)
      case OneHot => setEncoding(binaryOneHot)
      case CustomWidth3 => setEncoding(IDLE -> BigInt(1), ACTIVE -> BigInt(5), DRAIN -> BigInt(6))
      case CustomWidth4 => setEncoding(IDLE -> BigInt(0), ACTIVE -> BigInt(2), DRAIN -> BigInt(9))
      case other => throw new IllegalArgumentException(s"unsupported enum encoding mode $other")
    }
  }

  val held = Reg(Bits(width bits)) init(0)
  val transitionCount = Reg(UInt(4 bits)) init(0)
  @dontName val sharedActive = Bool()
  @dontName val notIdle = Bool()
  @dontName val recursiveAlias0 = Bool()
  @dontName val recursiveAlias1 = Bool()

  fsm.always {
    sharedActive := fsm.isActive(fsm.ACTIVE)
    notIdle := fsm.stateReg =/= fsm.enumOf(fsm.IDLE)
    recursiveAlias0 := sharedActive
    recursiveAlias1 := recursiveAlias0 && io.enable && !io.stall

    when(io.enable) {
      when(fsm.isActive(fsm.IDLE)) {
        fsm.goto(fsm.ACTIVE)
        transitionCount := transitionCount + 1
      } elsewhen(fsm.isActive(fsm.ACTIVE) && io.accept && !io.stall) {
        fsm.goto(fsm.DRAIN)
        transitionCount := transitionCount + 1
      } elsewhen(fsm.isActive(fsm.DRAIN)) {
        fsm.goto(fsm.IDLE)
        transitionCount := transitionCount + 1
      }
    }
    when(recursiveAlias1) {
      held := io.data
    }
  }

  io.active := sharedActive
  io.nonIdle := notIdle
  io.nested := recursiveAlias1 || (notIdle && io.accept)
  io.result := held
  io.transitions := transitionCount
}

/** Direct encoded observations make invalid and four-state inputs controllable. */
class EnumObservationRepro(width: ElabInt, mode: Int) extends Component {
  import EnumConditionEncodingMode._

  setDefinitionName("EnumObservation" + label(mode) + "Repro")
  val encoding = mode match {
    case Binary => binarySequential
    case OneHot => binaryOneHot
    case CustomWidth3 => SpinalEnumEncoding("enumObservationCustom3", index =>
      if (index == 0) BigInt(1) else if (index == 1) BigInt(5) else BigInt(6))
    case CustomWidth4 => SpinalEnumEncoding("enumObservationCustom4", index =>
      if (index == 0) BigInt(0) else if (index == 1) BigInt(2) else BigInt(9))
    case other => throw new IllegalArgumentException(s"unsupported enum encoding mode $other")
  }
  val state = new SpinalEnum(encoding)
  val IDLE = state.newElement("IDLE")
  val ACTIVE = state.newElement("ACTIVE")
  state.newElement("DRAIN")

  val io = new Bundle {
    val value = in(state())
    val peer = in(state())
    val gate = in Bool()
    val witnessIn = in Bits(width bits)
    val equalIdle = out Bool()
    val notActive = out Bool()
    val equalPeer = out Bool()
    val nested = out Bool()
    val witnessOut = out Bits(width bits)
  }
  io.equalIdle := io.value === IDLE
  io.notActive := io.value =/= ACTIVE
  io.equalPeer := io.value === io.peer
  io.nested := ((io.value === ACTIVE) && io.gate) || (io.value =/= IDLE)
  io.witnessOut := io.witnessIn
}

/** Same codes and widths, deliberately distinct definition and encoding identities. */
class DistinctEnumIdentityRepro(width: ElabInt) extends Component {
  setDefinitionName("DistinctEnumIdentityRepro")
  val leftEncoding = SpinalEnumEncoding("overlapLeft", index => if (index == 0) 1 else 5)
  val rightEncoding = SpinalEnumEncoding("overlapRight", index => if (index == 0) 1 else 5)
  val leftState = new SpinalEnum(leftEncoding)
  val leftIdle = leftState.newElement("IDLE")
  leftState.newElement("ACTIVE")
  val rightState = new SpinalEnum(rightEncoding)
  val rightIdle = rightState.newElement("IDLE")
  rightState.newElement("ACTIVE")
  val io = new Bundle {
    val left = in(leftState())
    val right = in(rightState())
    val witnessIn = in Bits(width bits)
    val leftIdle = out Bool()
    val rightIdle = out Bool()
    val witnessOut = out Bits(width bits)
  }
  io.leftIdle := io.left === leftIdle
  io.rightIdle := io.right === rightIdle
  io.witnessOut := io.witnessIn
}

/** Deliberate fences for name, metadata, process-dependency and budget checks. */
class EnumConditionNegativeControls(width: ElabInt) extends Component {
  setDefinitionName("EnumConditionNegativeControls")
  val stateDefinition = new SpinalEnum(binarySequential)
  val IDLE = stateDefinition.newElement("IDLE")
  val ACTIVE = stateDefinition.newElement("ACTIVE")
  val state = Reg(stateDefinition()) init(IDLE)

  val io = new Bundle {
    val enable = in Bool()
    val overrideStage = in Bool()
    val witnessIn = in Bits(width bits)
    val explicitResult = out Bool()
    val protectedResult = out Bool()
    val keptResult = out Bool()
    val noMergeResult = out Bool()
    val unsafeResult = out Bool()
    val fanout = out Bits(33 bits)
    val witnessOut = out Bits(width bits)
  }

  when(io.enable) {
    state := Mux(state === IDLE, ACTIVE, IDLE)
  }

  val explicitLookalike = state === ACTIVE
  explicitLookalike.setName("when_user_generated_l900")
  @dontName val protectedCondition = state === ACTIVE
  protectedCondition.dontSimplifyIt()
  @dontName val keptCondition = KeepAttribute(state === ACTIVE)
  @dontName val noMergeCondition = state === ACTIVE
  noMergeCondition.noBackendCombMerge()

  val stage = stateDefinition()
  stage := state
  @dontName val unsafeCondition = stage === ACTIVE
  io.unsafeResult := False
  when(io.overrideStage) {
    stage := IDLE
    when(unsafeCondition) {
      io.unsafeResult := True
    }
  }

  @dontName val overBudget = state === ACTIVE
  for (index <- 0 until 33) {
    io.fanout(index) := False
    when(overBudget) {
      io.fanout(index) := True
    }
  }

  io.explicitResult := explicitLookalike
  io.protectedResult := protectedCondition
  io.keptResult := keptCondition
  io.noMergeResult := noMergeCondition
  io.witnessOut := io.witnessIn
}

object EnumConditionCleanupArtifactWriter extends App {
  import EnumConditionEncodingMode._

  require(args.length == 1, "Usage: EnumConditionCleanupArtifactWriter <output-root>")
  val root = Paths.get(args(0)).toAbsolutePath.normalize
  val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32).asElabInt

  def emit(modeDirectory: Path, enabled: Boolean)(component: => Component): Unit = {
    Files.createDirectories(modeDirectory)
    val config = MorphWireAssignmentPasses(
      SpinalConfig(targetDirectory = modeDirectory.toString, oneFilePerComponent = true,
        headerWithDate = false, headerWithRepoHash = true), enabled)
    MorphVerilog(config)(component)
  }

  for ((directory, enabled) <- Vector("enabled" -> true, "disabled" -> false, "repeat" -> true)) {
    val target = root.resolve(directory)
    emit(target, enabled)(new EnumConditionRepro(width))
    for (mode <- Binary to CustomWidth4) {
      emit(target, enabled)(new EnumConditionCoverage(width, mode))
      emit(target, enabled)(new EnumObservationRepro(width, mode))
    }
    emit(target, enabled)(new DistinctEnumIdentityRepro(width))
    emit(target, enabled)(new EnumConditionNegativeControls(width))
  }
}
