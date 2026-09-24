package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.collection.JavaConverters._
import morphhdl.{EnumConditionNegativeControls, EnumConditionRepro, MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import morphhdl.ir.v1.NameOrigin
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals._

final case class EnumConditionIdentityObservation(
    native: BaseType,
    origin: Option[NameOrigin],
    sourceIntent: Boolean,
    sourceClass: String,
    beforeReason: Option[String],
    survived: Boolean
)

final case class EnumConditionDiagnosticResult(
    verilog: String,
    observations: Vector[EnumConditionIdentityObservation],
    remainingEligible: Int
)

/** Identity/provenance observer installed only after the production installer. */
final class EnumConditionNativeIdentityTrace {
  private var before = Vector.empty[(BaseType, Option[NameOrigin], Boolean, String, Option[String])]
  private var after = Vector.empty[EnumConditionIdentityObservation]
  private var eligibleAfterCleanup = -1

  def observations: Vector[EnumConditionIdentityObservation] = after
  def remainingEligible: Int = eligibleAfterCleanup

  def install(config: SpinalConfig): Unit = config.phasesInserters += { phases =>
    val production = phases.indexWhere(_.getClass.getSimpleName == "ProductionWireAssignmentPhase")
    require(production >= 0, "production cleanup must be installed before the enum trace")
    val intent = phases.collectFirst { case value: NativeConditionSourceIntent => value }
      .getOrElse(throw new IllegalStateException("missing condition source-intent capture"))

    def enumSource(value: BaseType): Option[Expression] =
      if (!value.hasOnlyOneStatement) None
      else value.head match {
        case assignment: DataAssignmentStatement if assignment.finalTarget eq value =>
          assignment.source match {
            case _: Operator.Enum.Equal | _: Operator.Enum.NotEqual => Some(assignment.source)
            case _ => None
          }
        case _ => None
      }

    def present(pc: PhaseContext, identity: BaseType): Boolean = {
      var found = false
      pc.components().foreach(_.dslBody.walkDeclarations {
        case value: BaseType if value eq identity => found = true
        case _ =>
      })
      found
    }

    val beforePhase = new PhaseMisc {
      override def impl(pc: PhaseContext): Unit = {
        val entries = Vector.newBuilder[(BaseType, Option[NameOrigin], Boolean, String, Option[String])]
        pc.components().foreach(_.dslBody.walkDeclarations {
          case value: BaseType => enumSource(value).foreach { source =>
            val origin = NativeWireNameProvenance.origin(value)
            val proof = new NamedWireExpressionNativePhase(
              unnamedOnly = origin.contains(NameOrigin.Unnamed),
              conditionSourceIntent = Some(intent)
            )
            entries += ((value, origin, intent.permits(value), source.getClass.getName,
              proof.retentionReasonFor(pc, value)))
          }
          case _ =>
        })
        before = entries.result()
      }
    }
    val afterPhase = new PhaseMisc {
      override def impl(pc: PhaseContext): Unit = {
        after = before.map { case (native, origin, sourceIntent, sourceClass, reason) =>
          EnumConditionIdentityObservation(native, origin, sourceIntent, sourceClass,
            reason, present(pc, native))
        }
        var count = 0
        pc.components().foreach(_.dslBody.walkDeclarations {
          case value: BaseType => enumSource(value).foreach { _ =>
            val origin = NativeWireNameProvenance.origin(value)
            val proof = new NamedWireExpressionNativePhase(
              unnamedOnly = origin.contains(NameOrigin.Unnamed),
              conditionSourceIntent = Some(intent)
            )
            if (proof.retentionReasonFor(pc, value).isEmpty) count += 1
          }
          case _ =>
        })
        eligibleAfterCleanup = count
      }
    }
    phases.insert(production, beforePhase)
    phases.insert(production + 2, afterPhase)
  }
}

object EnumConditionNativeDiagnostics {
  def emit(
      traced: Boolean,
      negative: Boolean = false
  ): EnumConditionDiagnosticResult = {
    val directory = Files.createTempDirectory("enum-condition-native-identity-")
    val trace = new EnumConditionNativeIdentityTrace
    try {
      val base = SpinalConfig(targetDirectory = directory.toString, oneFilePerComponent = true,
        headerWithDate = false, headerWithRepoHash = true)
      val config = MorphWireAssignmentPasses(base, enabled = true)
      if (traced) trace.install(config)
      val report = MorphVerilog(config) {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32).asElabInt
        if (negative) new EnumConditionNegativeControls(width) else new EnumConditionRepro(width)
      }
      val verilog = new String(Files.readAllBytes(java.nio.file.Paths.get(
        report.generatedSourcesPaths.head)), StandardCharsets.UTF_8)
      EnumConditionDiagnosticResult(verilog, trace.observations, trace.remainingEligible)
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }
}

object EnumConditionNativeDiagnosticsWriter extends App {
  require(args.length == 1, "Usage: EnumConditionNativeDiagnosticsWriter <output-file>")
  val output = Paths.get(args(0)).toAbsolutePath.normalize
  Option(output.getParent).foreach(Files.createDirectories(_))
  val rows = Vector("exact" -> false, "negative" -> true).flatMap { case (fixture, negative) =>
    val result = EnumConditionNativeDiagnostics.emit(traced = true, negative = negative)
    val observations = result.observations.zipWithIndex.map { case (entry, index) =>
      Vector(fixture, index.toString, entry.origin.map(_.toString).getOrElse("None"),
        entry.sourceIntent.toString, entry.sourceClass,
        entry.beforeReason.getOrElse("ELIGIBLE"), entry.survived.toString).mkString("\t")
    }
    observations :+ Vector(fixture, "remaining-eligible", result.remainingEligible.toString,
      "-", "-", "-", "-").mkString("\t")
  }
  Files.write(output, (("fixture\tindex\torigin\tsourceIntent\tsourceClass\tbeforeReason\tsurvived" +:
    rows).mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))
}

class EnumConditionNativeIdentityTests extends AnyFunSuite {
  private def emit(traced: Boolean, negative: Boolean = false): EnumConditionDiagnosticResult =
    EnumConditionNativeDiagnostics.emit(traced, negative)

  test("generated and unnamed enum observations are eligible by identity and disappear") {
    val result = emit(traced = true)
    val (verilog, observations, remainingEligible) =
      (result.verilog, result.observations, result.remainingEligible)
    assert(observations.size >= 3, observations)
    assert(observations.exists(_.origin.contains(NameOrigin.Generated)), observations)
    assert(observations.exists(_.origin.contains(NameOrigin.Unnamed)), observations)
    assert(observations.forall(_.sourceIntent), observations)
    assert(observations.forall(_.beforeReason.isEmpty), observations)
    assert(observations.forall(!_.survived), observations)
    assert(remainingEligible == 0, remainingEligible)
    assert(observations.forall(entry =>
      entry.sourceClass.endsWith("Operator$Enum$Equal") ||
        entry.sourceClass.endsWith("Operator$Enum$NotEqual")), observations)
    assert(!verilog.contains("assign when_EnumConditionRepro"), verilog)
  }

  test("the read-only observer does not change allocation or emitted bytes") {
    val plain = emit(traced = false).verilog
    val traced = emit(traced = true).verilog
    assert(plain == traced)
  }

  test("protected enum carriers retain precise native rejection evidence") {
    val result = emit(traced = true, negative = true)
    val (verilog, observations, remainingEligible) =
      (result.verilog, result.observations, result.remainingEligible)
    val retained = observations.filter(_.survived)
    assert(retained.nonEmpty, observations)
    assert(retained.forall(_.beforeReason.nonEmpty), retained)
    assert(retained.exists(_.origin.exists(_.explicitName.contains("when_user_generated_l900"))), retained)
    assert(retained.exists(_.beforeReason.exists(_.contains("PRESERVATION"))), retained)
    assert(remainingEligible == 0, remainingEligible)
    assert(verilog.contains("when_user_generated_l900"), verilog)
  }
}
