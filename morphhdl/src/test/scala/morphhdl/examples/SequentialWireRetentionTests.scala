package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals._
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlBool

/** Query the very same read-only eligibility proof used by native writeback. */
class SequentialWireRetentionTests extends AnyFunSuite {
  private def inspect(kind: String): (Option[String], String, String, String) = {
    val directory = Files.createTempDirectory("sequential-retention-")
    var predicate: Bool = null
    var inputPort: Bool = null
    var observed = false
    var reason: Option[String] = None
    try {
      val config = MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
        oneFilePerComponent = false, headerWithDate = false))
      config.netlistFileName = "dut.v"
      config.phasesInserters += { phases: ArrayBuffer[Phase] =>
        val index = phases.indexWhere(_.getClass.getName == "morphhdl.examples.ProductionWireAssignmentPhase")
        require(index >= 0)
        val intent = phases.collectFirst { case value: NativeConditionSourceIntent => value }
        phases.insert(index, new Phase {
          override def hasNetlistImpact: Boolean = false
          override def impl(pc: PhaseContext): Unit = {
            reason = new NamedWireExpressionNativePhase(conditionSourceIntent = intent)
              .retentionReasonFor(pc, predicate)
            if (kind == "explicit" || kind == "explicit-declaration") {
              assert(NativeWireNameProvenance.origin(predicate)
                .flatMap(_.explicitName).contains("when_user_l456"))
              // An explicitly named expression result is still a type node.
              // candidateForValue admits type nodes only for generated/unnamed
              // conditions. This is NOT a direct BaseType-to-BaseType alias.
              assert(predicate.isTypeNode == (kind == "explicit"))
              val expression = predicate.head.asInstanceOf[DataAssignmentStatement].source
              assert(expression.isInstanceOf[Operator.Bool.Not], expression)
              assert(expression.asInstanceOf[Operator.Bool.Not].input eq inputPort)
            }
            observed = true
          }
        })
      }
      MorphVerilog(config) {
        val ppc = HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1
        new Component {
          val laneWitness = out Bits(ppc bits)
          laneWitness := 0
          val input = in Bool()
          inputPort = input
          val select = in UInt(2 bits)
          val result = out Bits(8 bits)
          val state = Reg(Bits(8 bits)) init(0)
          @dontName val condition = if (kind == "explicit-declaration") {
            val declaration = Bool()
            declaration := !input
            declaration
          } else !input
          predicate = condition
          kind match {
            case "keep" => condition.addAttribute("keep")
            case "vital" => condition.setAsVital()
            case "explicit" | "explicit-declaration" => condition.setName("when_user_l456")
            case _ =>
          }
          when(condition) {
            if (kind == "partial") state(0) := True else state := 1
          }
          if (kind == "switch") {
            switch(select) { is(0) { when(condition) { state := 2 } } }
          }
          result := state
        }
      }
      assert(observed, "The real production boundary was not observed")
      (reason, predicate.getName(""), inputPort.getName(""),
        new String(Files.readAllBytes(directory.resolve("dut.v")), StandardCharsets.UTF_8))
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }
  test("safe sequential condition has no native retention diagnostic") {
    val (reason, _, inputName, verilog) = inspect("safe")
    assert(reason.isEmpty, reason)
    assert(!verilog.contains("assign when_"), verilog)
    assert(verilog.contains(s"if((! $inputName))"), verilog)
  }
  for ((kind, expected) <- Vector(
      "keep" -> "WA10-CONDITION-PRESERVATION",
      "vital" -> "WA10-CONDITION-SOURCE-INTENT",
      "explicit" -> "WA10-CONDITION-NOT-A-CANDIDATE",
      "explicit-declaration" -> "WA10-CONDITION-USER-NAME",
      "switch" -> "WA10-CONDITION-RECEIVER-CONTEXT",
      "partial" -> "WA10-CONDITION-UNSUPPORTED-CONTROL")) {
    test(s"$kind receiver is retained with its exact native reason") {
      val (reason, name, _, verilog) = inspect(kind)
      assert(reason.contains(expected), reason)
      assert(name.nonEmpty && verilog.contains(s"assign $name = "), verilog)
      assert(verilog.contains(s"if($name)"), verilog)
    }
  }

  /** Observe the normalized graph, including the intermediary that hid vital intent. */
  private def inspectAlias(shape: String, protectedAlias: Boolean): Unit = {
    val directory = Files.createTempDirectory("alias-source-intent-")
    var alias: UInt = null
    var sourcePort: UInt = null
    var observed = false
    try {
      val config = MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
        oneFilePerComponent = false, headerWithDate = false))
      config.netlistFileName = "dut.v"
      config.phasesInserters += { phases: ArrayBuffer[Phase] =>
        val index = phases.indexWhere(_.getClass.getName == "morphhdl.examples.ProductionWireAssignmentPhase")
        require(index >= 0)
        val intent = phases.collectFirst { case value: NativeConditionSourceIntent => value }.get
        phases.insert(index, new Phase {
          override def hasNetlistImpact: Boolean = false
          override def impl(pc: PhaseContext): Unit = {
            assert(alias.isUnnamed && !alias.isTypeNode && alias.hasOnlyOneStatement)
            assert(alias.head.asInstanceOf[DataAssignmentStatement].source eq sourcePort)
            // Both aliases are now live. Only the captured source intent can
            // distinguish the explicit vital alias from the unprotected one.
            assert(alias.isVital)
            assert(intent.permits(alias) == !protectedAlias)
            val consumers = ArrayBuffer.empty[DataAssignmentStatement]
            alias.component.dslBody.walkStatements {
              case assignment: DataAssignmentStatement if assignment.finalTarget ne alias =>
                var referencesAlias = false
                assignment.walkDrivingExpressions {
                  case value: BaseType if value eq alias => referencesAlias = true
                  case _ =>
                }
                if (referencesAlias) consumers += assignment
              case _ =>
            }
            assert(consumers.nonEmpty)
            if (shape == "whole-register") assert(consumers.exists(_.finalTarget.isReg))
            else {
              assert(consumers.forall(_.finalTarget.isComb))
              assert(consumers.exists(_.source.isInstanceOf[Resize]))
            }
            val reason = new UnnamedWireAliasNativePhase(Some(intent)).retentionReasonFor(pc, alias)
            if (protectedAlias) assert(reason.contains("WA04-NATIVE-SOURCE-INTENT"), reason)
            else assert(reason.isEmpty, reason)
            observed = true
          }
        })
      }
      MorphVerilog(config) {
        val ppc = HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1
        new Component {
          val laneWitness = out Bits(ppc bits)
          laneWitness := 0
          val source = in UInt(18 bits)
          sourcePort = source
          @dontName val direct = UInt(18 bits)
          alias = direct
          direct := source
          if (protectedAlias) direct.setAsVital()
          val width = if (shape == "whole-register") 18 else 13
          val result = out UInt(width bits)
          if (shape == "comb-slice") result := direct.resized
          else {
            val state = Reg(UInt(width bits)) init(0)
            state.setName("state")
            if (shape == "whole-register") state := direct else state := direct.resized
            result := state
          }
        }
      }
      assert(observed, "The production-boundary observer did not run")
      val verilog = new String(Files.readAllBytes(directory.resolve("dut.v")), StandardCharsets.UTF_8)
      val name = alias.getName("")
      if (protectedAlias) {
        assert(name.nonEmpty, verilog)
        assert(verilog.contains(s"assign $name = ${sourcePort.getName()};"), verilog)
        val selected = if (shape == "whole-register") name else s"$name[12:0]"
        assert(verilog.contains((if (shape == "comb-slice") "assign result = " else "state <= ") + selected + ";"), verilog)
      } else {
        assert(alias.parentScope == null, "The unprotected alias must really be removed")
        val selected = if (shape == "whole-register") sourcePort.getName() else s"${sourcePort.getName()}[12:0]"
        assert(verilog.contains((if (shape == "comb-slice") "assign result = " else "state <= ") + selected + ";"), verilog)
      }
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  for (shape <- Vector("whole-register", "register-slice", "comb-slice");
       protectedAlias <- Vector(false, true)) {
    test(s"$shape honors captured vital=$protectedAlias independent of immediate consumer kind") {
      inspectAlias(shape, protectedAlias)
    }
  }
}
