package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ArrayBuffer
import spinal.core._
import spinal.core.internals._
import morphhdl.{LaneExpressionExample, MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlBool

/** Observes the real production phase; does not replace its implementation. */
private object LaneWhenNativeTrace {
  def install(config: SpinalConfig, output: Path): Unit = {
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val index = phases.indexWhere(_.getClass.getName ==
        "morphhdl.examples.ProductionWireAssignmentPhase")
      require(index >= 0, "production wire-assignment phase missing")
      val production = phases(index)
      val intent = phases.collectFirst { case value: NativeConditionSourceIntent => value }
      val inspector = new NamedWireExpressionNativePhase(conditionSourceIntent = intent)
      phases.update(index, new Phase {
        override def hasNetlistImpact: Boolean = production.hasNetlistImpact
        override def impl(pc: PhaseContext): Unit = {
          val lines = ArrayBuffer.empty[String]
          def width(expression: Expression): Int = expression match {
            case _ if expression.getTypeObject == TypeBool => 1
            case value: WidthProvider => value.getWidth
            case _ => -1
          }
          def snapshot(label: String): Unit = pc.components().foreach {
            case component: LaneExpressionExample =>
              for ((name, target) <- Vector("laneDe" -> component.laneDe,
                  "laneFrameEnd" -> component.laneFrameEnd)) {
                val tags = target.getTags().map(_.getClass.getName).mkString(",")
                lines += s"$label\ttarget=$name\twidth=${target.getBitsWidth}\ttags=$tags\t" +
                  s"symbolic=${ParameterizedWidth.expressionOf(target).map(_.verilog)}"
                target.foreachStatements {
                  case assignment: DataAssignmentStatement =>
                    lines += s"$label\ttarget=$name\treceiver=${assignment.target.getClass.getName}" +
                      s"\treceiverWidth=${width(assignment.target)}\trhs=${assignment.source.opName}"
                    assignment.walkDrivingExpressions {
                      case resize: Resize =>
                        lines += s"$label\tresize=${resize.getClass.getName}\twidth=${width(resize)}" +
                          s"\tinputWidth=${width(resize.input)}\tinputType=${resize.input.getTypeObject}" +
                          s"\tsymbolic=${ParameterizedWidth.resizeExpressionOf(resize).map(_.verilog)}"
                      case _ =>
                    }
                  case _ =>
                }
              }
              component.dslBody.walkStatements {
                case condition: WhenStatement =>
                  lines += s"$label\twhen=${condition.getClass.getName}\tcondition=${condition.cond.opName}" +
                    s"\tconditionType=${condition.cond.getClass.getName}\twidth=${width(condition.cond)}"
                  condition.cond match {
                    case value: BaseType =>
                      value.head.source.walkDrivingExpressions {
                        case source: BaseType =>
                          lines += s"$label\tcondition-source=${source.getName("")}\twidth=${source.getBitsWidth}\t" +
                            s"type=${source.getTypeObject}\tsame-component=${source.component eq component}\t" +
                            s"root=${source.parentScope eq source.rootScopeStatement}\t" +
                            s"null-scope=${source.parentScope == null}\tanalog=${source.isAnalog}\tinout=${source.isInOut}\t" +
                            s"symbolic=${ParameterizedWidth.expressionOf(source)}"
                        case _ =>
                      }
                      var occurrences = 0
                      pc.components().foreach(_.dslBody.walkStatements { statement =>
                        statement.walkDrivingExpressions {
                          case reference: BaseType if reference eq value => occurrences += 1
                          case _ =>
                        }
                      })
                      lines += s"$label\tconditionCarrier=${value.getName("")}\t" +
                        s"origin=${NativeWireNameProvenance.origin(value)}\t" +
                        s"typeNode=${value.isTypeNode}\tnamed=${value.isNamed}\t" +
                        s"root=${value.parentScope eq value.rootScopeStatement}\tcomponent-root=${value.parentScope eq component.dslBody}\t" +
                        s"singleDriver=${value.hasOnlyOneStatement}\toccurrences=$occurrences\t" +
                        s"intent=${intent.exists(_.permits(value))}\tretained=${inspector.retentionReasonFor(pc, value)}\t" +
                        s"frozen=${value.isFrozen()}\ttags=${value.getTags().map(_.getClass.getName).mkString(",")}"
                    case _ =>
                  }
                case _ =>
              }
            case _ =>
          }
          snapshot("before-production")
          production.impl(pc)
          snapshot("after-production")
          Files.createDirectories(output.getParent)
          Files.write(output, (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))
        }
      })
    }
  }
}

/** Three executions of the public API: enabled, repeated, and explicitly disabled. */
object LaneWhenInliningArtifactWriter extends App {
  require(args.length == 1, "usage: LaneWhenInliningArtifactWriter OUTPUT_DIRECTORY")
  val output = Paths.get(args(0)).toAbsolutePath.normalize
  def generate(name: String, enabled: Boolean, trace: Boolean): Unit = {
    val directory = output.resolve(name)
    Files.createDirectories(directory)
    val config = MorphWireAssignmentPasses(SpinalConfig(
      targetDirectory = directory.toString,
      oneFilePerComponent = false,
      headerWithDate = false,
      headerWithRepoHash = true
    ), enabled)
    config.netlistFileName = "LaneExpressionExample.v"
    if (trace) LaneWhenNativeTrace.install(config, directory.resolve("native.tsv"))
    MorphVerilog(config) {
      new LaneExpressionExample(
        HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1)
    }
  }
  generate("enabled", enabled = true, trace = false)
  generate("repeat", enabled = true, trace = false)
  generate("disabled", enabled = false, trace = false)
  generate("trace", enabled = true, trace = true)
  def bytes(name: String): Array[Byte] =
    Files.readAllBytes(output.resolve(name).resolve("LaneExpressionExample.v"))
  require(java.util.Arrays.equals(bytes("enabled"), bytes("repeat")),
    "repeated generation changed the final Verilog artifact")
  require(java.util.Arrays.equals(bytes("enabled"), bytes("trace")),
    "read-only native observation changed the final Verilog artifact")
  println("Lane artifact generation: deterministic and observation-neutral")
}
