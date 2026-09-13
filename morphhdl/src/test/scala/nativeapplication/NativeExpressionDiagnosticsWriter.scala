import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable.ArrayBuffer
import spinal.core._
import spinal.core.internals._
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlBool

package morphhdl.examples {
  /** Test-only observation at the exact public production phase boundary.
    * Executes the same six native slots in the same order and records their
    * actual reports. Names are displayed only as diagnostics; classification
    * uses pre-allocation Nameable provenance and expression identity.
    */
  object NativeExpressionDiagnostics {
    def install(config: SpinalConfig, output: java.nio.file.Path): Unit = {
      config.phasesInserters += { phases: ArrayBuffer[Phase] =>
        val boundary = phases.indexWhere(_.getClass.getName ==
          "morphhdl.examples.ProductionWireAssignmentPhase")
        require(boundary >= 0, "production wire-assignment phase missing")
        phases.update(boundary, new Phase {
          override def hasNetlistImpact: Boolean = true
          override def impl(pc: PhaseContext): Unit = {
            val lines = ArrayBuffer.empty[String]
            def snapshot(label: String): Unit = pc.components().foreach { component =>
              component.dslBody.walkDeclarations {
                case value: BaseType if value.isComb && value.isDirectionLess &&
                    value.hasOnlyOneStatement => value.head match {
                  case assignment: DataAssignmentStatement =>
                    val uses = ArrayBuffer.empty[String]
                    component.dslBody.walkStatements { statement =>
                      var count = 0
                      statement.walkDrivingExpressions {
                        case reference: BaseType if reference eq value => count += 1
                        case _ =>
                      }
                      if (count > 0) uses += (statement match {
                        case receiver: DataAssignmentStatement =>
                          s"${receiver.finalTarget.getName("")}:${receiver.source.opName}:" +
                            s"register=${receiver.finalTarget.isReg}:occurrences=$count"
                        case other => s"${other.getClass.getSimpleName}:occurrences=$count"
                      })
                    }
                    lines += s"$label\t${value.getName("")}\t" +
                      s"${NativeWireNameProvenance.origin(value)}\twidth=${value.getBitsWidth}\t" +
                      s"typeNode=${value.isTypeNode},nativeNamed=${value.isNamed}," +
                      s"rootDeclaration=${value.parentScope eq value.rootScopeStatement}," +
                      s"rootDriver=${assignment.parentScope eq value.rootScopeStatement}\t" +
                      s"${assignment.source.opName}\t${uses.mkString(";")}"
                  case _ =>
                }
                case _ =>
              }
            }
            var progress = true
            var round = 0
            while (progress) {
              round += 1
              require(round <= 1024, "diagnostic native pipeline failed to converge")
              snapshot(s"round-$round-before")
              val unnamedAlias = new UnnamedWireAliasNativePhase
              unnamedAlias.impl(pc)
              val namedAlias = new NamedWireAliasNativePhase(deferPreferredExpressionSource = true)
              namedAlias.impl(pc)
              val unnamed = new UnnamedWireExpressionNativePhase
              unnamed.impl(pc)
              val named = new NamedWireExpressionNativePhase
              named.impl(pc)
              val constant = new ConstantOperandNativePhase
              constant.impl(pc)
              val ternary = new BooleanTernaryNativePhase
              ternary.impl(pc)
              lines += s"round-$round-unnamed-expression\t${unnamed.report}"
              lines += s"round-$round-named-expression\t${named.report}"
              lines += s"round-$round-constant\t${constant.changedCount}"
              lines += s"round-$round-ternary\t${ternary.changedCount}"
              progress = unnamedAlias.report.eliminatedCount + namedAlias.report.eliminatedCount +
                unnamed.report.eliminatedCount + named.report.eliminatedCount +
                constant.changedCount + ternary.changedCount > 0
            }
            snapshot("pre-emission")
            Files.createDirectories(output.getParent)
            Files.write(output, (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))
          }
        })
      }
    }
  }
}

object NativeExpressionDiagnosticsWriter extends App {
  require(args.length == 2, "usage: timing|general OUTPUT_DIRECTORY")
  val output = Paths.get(args(1)).toAbsolutePath
  Files.createDirectories(output)
  val config = MorphWireAssignmentPasses(SpinalConfig(
    targetDirectory = output.toString, headerWithDate = false, headerWithRepoHash = true,
    defaultConfigForClockDomains = if (args(0) == "timing") ClockDomainConfig()
    else ClockDomainConfig(clockEdge = RISING, resetKind = SYNC, resetActiveLevel = HIGH)))
  config.netlistFileName = "diagnostic.v"
  morphhdl.examples.NativeExpressionDiagnostics.install(config, output.resolve("native.tsv"))
  MorphVerilog(config) {
    val ppc = HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1
    args(0) match {
      case "timing" => new TimingExpressionExample(ppc)
      case "general" => new morphhdl.examples.GeneralExpressionInlining(ppc)
      case other => throw new IllegalArgumentException(s"unsupported topology '$other'")
    }
  }
}
