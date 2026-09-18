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
            if (kind == "explicit") {
              // This source is already a direct named alias at this boundary,
              // not an expression candidate. Check its actual provenance too.
              assert(NativeWireNameProvenance.origin(predicate)
                .flatMap(_.explicitName).contains("when_user_l456"))
              assert(predicate.head.asInstanceOf[DataAssignmentStatement].source
                .isInstanceOf[BaseType])
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
          @dontName val condition = !input
          predicate = condition
          kind match {
            case "keep" => condition.addAttribute("keep")
            case "vital" => condition.setAsVital()
            case "explicit" => condition.setName("when_user_l456")
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
      "switch" -> "WA10-CONDITION-RECEIVER-CONTEXT",
      "partial" -> "WA10-CONDITION-UNSUPPORTED-CONTROL")) {
    test(s"$kind receiver is retained with its exact native reason") {
      val (reason, name, _, verilog) = inspect(kind)
      assert(reason.contains(expected), reason)
      assert(name.nonEmpty && verilog.contains(s"assign $name = "), verilog)
      assert(verilog.contains(s"if($name)"), verilog)
    }
  }
}
