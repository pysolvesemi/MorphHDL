package morphhdl

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals.{
  AssignmentStatement,
  DataAssignmentStatement,
  Expression,
  ExternalParameterizedStructuralWitnessSizing,
  InitAssignmentStatement,
  InitialAssignmentStatement,
  Phase,
  PhaseContext,
  PhaseInferWidth,
  PhaseNetlist,
  PhaseNormalizeNodeInputs,
  PhaseVerilog,
  ResizeUInt,
  UIntLiteral,
  WidthProvider
}

import morphhdl.frontend.HdlInt

final class StructuralFixedWitnessHarness(width: HdlInt) extends Component {
  setDefinitionName("StructuralFixedWitnessHarness")
  val stimulus = in(Bool())
  val observed = out(morphhdl.frontend.UInt(width bits))

  if (width > 2) {
    val state = morphhdl.frontend.Reg(morphhdl.frontend.UInt(width bits))
    state.init(U(1, 1 bits))
    state := U(stimulus)
    observed := U(stimulus)
  } else {
    val state = morphhdl.frontend.Reg(morphhdl.frontend.UInt(width bits))
    state.init(U(0))
    state := U(0)
    observed := state
  }
}

final class StructuralTooWideWitnessHarness(width: HdlInt) extends Component {
  setDefinitionName("StructuralTooWideWitnessHarness")
  val stimulus = in(UInt(3 bits))
  val observed = out(morphhdl.frontend.UInt(width bits))

  if (width < 3) observed := stimulus
  else observed := U(0, 1 bits)
}

final class StructuralRegisterWitnessHarness(width: HdlInt) extends Component {
  setDefinitionName("StructuralRegisterWitnessHarness")
  val stimulus = in(Bool())
  val observed = out(morphhdl.frontend.UInt(width bits))
  val fixedState = Reg(UInt(1 bits)).setName("fixed_state_keep")
  fixedState.init(U(0, 1 bits))
  fixedState := U(stimulus)

  if (width > 2) {
    observed := fixedState
  } else {
    observed := U(0)
  }
}

final class StructuralSameWidthWitnessHarness(width: HdlInt) extends Component {
  setDefinitionName("StructuralSameWidthWitnessHarness")
  val stimulus = in(UInt(3 bits))
  val observed = out(morphhdl.frontend.UInt(width bits))

  if (width < 4) observed := stimulus
  else observed := U(0, 3 bits)
}

private final class StructuralWitnessSourceCorruptionPhase(
    initializer: Boolean
) extends PhaseNetlist {
  var mutationCount = 0

  override def impl(pc: PhaseContext): Unit = {
    if (!pc.config.parameterizedVerilog) return
    var mutatedThisRun = false

    def mutateInitializer(
        assignment: AssignmentStatement,
        target: UInt
    ): Unit = {
      if (!mutatedThisRun) {
        assignment.source match {
          case literal: UIntLiteral
              if literal.getWidth == target.getBitsWidth =>
            assignment.source = UIntLiteral(
              literal.value,
              literal.poisonMask,
              literal.getWidth
            )
            mutationCount += 1
            mutatedThisRun = true
          case _ =>
        }
      }
    }

    pc.walkComponents { component =>
      component.dslBody.walkStatements {
        case assignment: DataAssignmentStatement if !mutatedThisRun =>
          assignment.finalTarget match {
            case target: UInt if initializer =>
              target.foreachStatements {
                case value: InitAssignmentStatement =>
                  mutateInitializer(value, target)
                case value: InitialAssignmentStatement =>
                  mutateInitializer(value, target)
                case _ =>
              }
            case _: UInt =>
              assignment.source match {
                case resize: ResizeUInt =>
                  assignment.source = resize.input
                  mutationCount += 1
                  mutatedThisRun = true
                case _ =>
              }
            case _ =>
          }
        case _ =>
      }
    }
  }
}

private final class StructuralWitnessResizeInputCorruptionPhase
    extends PhaseNetlist {
  var mutationCount = 0

  override def impl(pc: PhaseContext): Unit = {
    if (!pc.config.parameterizedVerilog) return
    pc.walkComponents { component =>
      component.dslBody.walkStatements {
        case assignment: DataAssignmentStatement =>
          assignment.finalTarget match {
            case _: UInt =>
              assignment.source match {
                case resize: ResizeUInt =>
                  resize.input match {
                    case input: Expression with WidthProvider
                        if input.getWidth > 0 =>
                      resize.input = UIntLiteral(
                        BigInt(0),
                        BigInt(0),
                        input.getWidth
                      )
                      mutationCount += 1
                    case _ =>
                  }
                case _ =>
              }
            case _ =>
          }
        case _ =>
      }
    }
  }
}

private final class StructuralWitnessSameWidthCorruptionPhase
    extends PhaseNetlist {
  var mutationCount = 0

  override def impl(pc: PhaseContext): Unit = {
    if (!pc.config.parameterizedVerilog) return
    var mutatedThisRun = false
    pc.walkComponents { component =>
      component.dslBody.walkStatements {
        case assignment: DataAssignmentStatement if !mutatedThisRun =>
          assignment.finalTarget match {
            case target: UInt =>
              assignment.source match {
                case source: Expression with WidthProvider
                    if !source.isInstanceOf[ResizeUInt] &&
                      !source.isInstanceOf[UIntLiteral] &&
                      ParameterizedWidth
                        .expressionOf(target)
                        .exists(_.parameters.nonEmpty) &&
                      source.getWidth == target.getBitsWidth =>
                  assignment.source = UIntLiteral(
                    BigInt(0),
                    BigInt(0),
                    source.getWidth
                  )
                  mutationCount += 1
                  mutatedThisRun = true
                case _ =>
              }
            case _ =>
          }
        case _ =>
      }
    }
  }
}

class StructuralWitnessSizingTests extends AnyFunSuite {
  test("captured fixed UInt data and literal init retain a legal witness size") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        defaultConfigForClockDomains = ClockDomainConfig(resetKind = BOOT)
      )
      config.netlistFileName = "structural_fixed_witness.v"
      val report = MorphVerilog(config) {
        val width = HdlInt.param("WIDTH", default = 3, min = 1, max = 4)
        new StructuralFixedWitnessHarness(width)
      }

      assert(report.parameters.map(_.name) == Vector("WIDTH"))
      val verilog = new String(
        Files.readAllBytes(directory.resolve("structural_fixed_witness.v")),
        java.nio.charset.StandardCharsets.UTF_8
      )
      assert(verilog.contains("parameter integer WIDTH"))
      assert(verilog.contains("generate"))
      assert(!verilog.contains("{2'd0, stimulus}"))
      assert(!verilog.contains("3'b000"))
    }
  }

  test("captured fixed UInt wider than the legal target minimum fails closed") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "structural_too_wide_witness.v"
      MorphVerilog.tryGenerate(config) {
        val width = HdlInt.param("WIDTH", default = 4, min = 2, max = 4)
        new StructuralTooWideWitnessHarness(width)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
            ) || failure.detail.contains("WIDTH MISMATCH")
          )
        case Right(report) =>
          fail(s"Expected unsafe fixed witness-width failure, received $report")
      }
    }
  }

  test("fixed register sources remain sequential after witness sizing") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        defaultConfigForClockDomains = ClockDomainConfig(resetKind = BOOT),
        parameterizedVerilog = true
      )
      config.netlistFileName = "structural_register_witness.v"
      config.phasesInserters +=
        ExternalParameterizedStructuralWitnessSizing.install _
      config.generateVerilog {
        val width = HdlInt.param("WIDTH", default = 3, min = 1, max = 4)
        new StructuralRegisterWitnessHarness(width)
      }

      val verilog = new String(
        Files.readAllBytes(directory.resolve("structural_register_witness.v")),
        java.nio.charset.StandardCharsets.UTF_8
      )
      assert(verilog.contains("fixed_state_keep"))
      assert(verilog.contains("fixed_state_keep <="))
      assert(verilog.contains("observed = fixed_state_keep;"))
      assert(!verilog.contains("observed = {2'd0, fixed_state_keep};"))
    }
  }

  test("structural witness sizing brackets validation before Verilog emission") {
    val pc = new PhaseContext(SpinalConfig())
    val infer = new PhaseInferWidth(pc)
    val normalize = new PhaseNormalizeNodeInputs(pc)
    val emit = new PhaseVerilog(pc, new SpinalReport[Component]())
    val phases = ArrayBuffer[Phase](infer, normalize, emit)

    ExternalParameterizedStructuralWitnessSizing.install(phases)

    assert(phases.size == 5)
    assert(phases.head eq infer)
    assert(phases(2) eq normalize)
    assert(phases.last eq emit)
    assert(phases(1).getClass.getSimpleName.contains("PreparePhase"))
    assert(phases(3).getClass.getSimpleName.contains("RestorePhase"))
  }

  test("restore fails closed when a prepared data source is replaced") {
    val corruption = new StructuralWitnessSourceCorruptionPhase(
      initializer = false
    )
    val result = generateWithCorruption(corruption)

    assert(corruption.mutationCount > 0)
    result match {
      case Left(failure) =>
        assert(
          failure.detail.contains(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-WITNESS-SIZING-RESTORE-FAILED"
          )
        )
        assert(failure.detail.contains("DataAssignmentStatement"))
      case Right(report) =>
        fail(s"Expected data witness-restore failure, received $report")
    }
  }

  test("restore fails closed when a prepared initializer is replaced") {
    val corruption = new StructuralWitnessSourceCorruptionPhase(
      initializer = true
    )
    val result = generateWithCorruption(corruption)

    assert(corruption.mutationCount > 0)
    result match {
      case Left(failure) =>
        assert(
          failure.detail.contains(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-WITNESS-SIZING-RESTORE-FAILED"
          )
        )
        assert(
          failure.detail.contains("InitAssignmentStatement") ||
            failure.detail.contains("InitialAssignmentStatement")
        )
      case Right(report) =>
        fail(s"Expected initializer witness-restore failure, received $report")
    }
  }

  test("restore fails closed when a prepared resize input is replaced") {
    val corruption = new StructuralWitnessResizeInputCorruptionPhase
    val result = generateWithInsertedPhase(corruption) {
      val width = HdlInt.param("WIDTH", default = 3, min = 1, max = 4)
      new StructuralFixedWitnessHarness(width)
    }

    assert(corruption.mutationCount > 0)
    result match {
      case Left(failure) =>
        assert(
          failure.detail.contains(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-WITNESS-SIZING-RESTORE-FAILED"
          )
        )
      case Right(report) =>
        fail(s"Expected resize-input witness-restore failure, received $report")
    }
  }

  test("restore fails closed when a prepared same-width source is replaced") {
    val corruption = new StructuralWitnessSameWidthCorruptionPhase
    val result = generateWithInsertedPhase(corruption) {
      val width = HdlInt.param("WIDTH", default = 3, min = 3, max = 4)
      new StructuralSameWidthWitnessHarness(width)
    }

    assert(corruption.mutationCount > 0)
    result match {
      case Left(failure) =>
        assert(
          failure.detail.contains(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-WITNESS-SIZING-RESTORE-FAILED"
          ),
          failure.detail
        )
      case Right(report) =>
        fail(s"Expected same-width witness-restore failure, received $report")
    }
  }

  private def generateWithCorruption(
      corruption: StructuralWitnessSourceCorruptionPhase
  ) = generateWithInsertedPhase(corruption) {
    val width = HdlInt.param("WIDTH", default = 3, min = 1, max = 4)
    new StructuralFixedWitnessHarness(width)
  }

  private def generateWithInsertedPhase(
      corruption: PhaseNetlist
  )(component: => Component) = {
    val directory = Files.createTempDirectory(
      "morphhdl-structural-witness-corruption-"
    )
    try {
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        defaultConfigForClockDomains = ClockDomainConfig(resetKind = BOOT)
      )
      config.phasesInserters += { phases =>
        val emission = phases.indexWhere(_.isInstanceOf[PhaseVerilog])
        assert(emission >= 0)
        phases.insert(emission, corruption)
      }
      MorphVerilog.tryGenerate(config)(component)
    } finally deleteDirectory(directory)
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-structural-witness-")
    try body(directory)
    finally deleteDirectory(directory)
  }

  private def deleteDirectory(directory: Path): Unit = {
    val stream = Files.walk(directory)
    try {
      stream.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
    } finally stream.close()
  }
}
