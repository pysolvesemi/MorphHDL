package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.JavaConverters._

import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class EnumConditionInliningRegressionTests extends AnyFunSuite {
  private def emit(enabled: Boolean)(factory: ElabInt => Component): String = {
    val directory = Files.createTempDirectory("enum-condition-inlining-")
    try {
      val base = SpinalConfig(targetDirectory = directory.toString,
        oneFilePerComponent = true, headerWithDate = false, headerWithRepoHash = true)
      val config = MorphWireAssignmentPasses(base, enabled)
      val report = MorphVerilog(config) {
        factory(HdlInt.param("WIDTH", default = 8, min = 1, max = 32).asElabInt)
      }
      new String(Files.readAllBytes(java.nio.file.Paths.get(report.generatedSourcesPaths.head)),
        StandardCharsets.UTF_8)
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  private def carrierAssignments(verilog: String): Vector[String] =
    verilog.split("\n").iterator.map(_.trim).filter(line =>
      line.startsWith("assign when_") || line.startsWith("assign _zz_when")).toVector

  private lazy val exactEnabled = emit(enabled = true)(new EnumConditionRepro(_))
  private lazy val exactDisabled = emit(enabled = false)(new EnumConditionRepro(_))

  test("ordinary StateMachine isActive conditions inline recursively without changing state storage") {
    assert(carrierAssignments(exactEnabled).isEmpty, exactEnabled)
    assert(!exactEnabled.contains("wire                _zz_when;"), exactEnabled)
    assert(exactEnabled.contains("if((fsm_stateReg == FSM_IDLE))"), exactEnabled)
    assert(exactEnabled.split("if\\(\\(fsm_stateReg == FSM_ACTIVE\\)\\)", -1).length - 1 == 2,
      exactEnabled)
    assert(exactEnabled.contains("assign io_active = (fsm_stateReg == FSM_ACTIVE);"), exactEnabled)
    assert(exactEnabled.contains("reg        [0:0]    fsm_stateReg;"), exactEnabled)
    assert(exactEnabled.contains("fsm_stateReg <= FSM_IDLE;"), exactEnabled)
    assert(exactEnabled.contains("fsm_stateReg <= fsm_stateNext;"), exactEnabled)
  }

  test("disabled cleanup retains a meaningful generated-condition baseline") {
    val assignments = carrierAssignments(exactDisabled)
    assert(assignments.count(_.contains("when_EnumConditionRepro")) == 2, exactDisabled)
    assert(exactDisabled.contains("if(when_EnumConditionRepro"), exactDisabled)
    assert(exactDisabled.contains("assign io_active = (fsm_stateReg == FSM_ACTIVE);"), exactDisabled)
  }

  test("the same WIDTH-parameterized artifact remains symbolic in both modes") {
    for (verilog <- Vector(exactEnabled, exactDisabled)) {
      assert(verilog.contains("parameter integer WIDTH = 8"), verilog)
      assert(verilog.contains("input  wire [WIDTH-1:0]    io_data"), verilog)
      assert(verilog.contains("reg        [WIDTH-1:0]    held;"), verilog)
      assert(verilog.contains("held <= {WIDTH{1'b0}};"), verilog)
    }
  }

  for (mode <- EnumConditionEncodingMode.Binary to EnumConditionEncodingMode.CustomWidth4) {
    test(s"${EnumConditionEncodingMode.label(mode)} FSM inlines equality, inequality, nested and shared receivers") {
      val enabled = emit(enabled = true)(new EnumConditionCoverage(_, mode))
      val disabled = emit(enabled = false)(new EnumConditionCoverage(_, mode))
      assert(carrierAssignments(enabled).isEmpty, enabled)
      assert(!enabled.contains("recursiveAlias"), enabled)
      assert(enabled.contains("assign io_active = "), enabled)
      assert(enabled.contains("assign io_nonIdle = "), enabled)
      assert(enabled.contains("assign io_nested = "), enabled)
      assert(enabled.contains("reg        [WIDTH-1:0]    held;"), enabled)
      assert(enabled.contains("fsm_stateReg <= FSM_IDLE;"), enabled)
      assert(carrierAssignments(disabled).nonEmpty, disabled)
      assert(enabled == emit(enabled = true)(new EnumConditionCoverage(_, mode)))
    }
  }

  test("binary, one-hot and nonsequential custom encodings keep authoritative codes and widths") {
    val binary = emit(enabled = true)(new EnumConditionCoverage(_, EnumConditionEncodingMode.Binary))
    val oneHot = emit(enabled = true)(new EnumConditionCoverage(_, EnumConditionEncodingMode.OneHot))
    val custom3 = emit(enabled = true)(new EnumConditionCoverage(_, EnumConditionEncodingMode.CustomWidth3))
    val custom4 = emit(enabled = true)(new EnumConditionCoverage(_, EnumConditionEncodingMode.CustomWidth4))
    assert(binary.contains("localparam FSM_DRAIN = 2'd2;"), binary)
    assert(oneHot.contains("localparam FSM_IDLE = 3'd1;"), oneHot)
    assert(oneHot.contains("localparam FSM_ACTIVE = 3'd2;"), oneHot)
    assert(oneHot.contains("localparam FSM_DRAIN = 3'd4;"), oneHot)
    assert(custom3.contains("localparam FSM_IDLE = 3'd1;"), custom3)
    assert(custom3.contains("localparam FSM_ACTIVE = 3'd5;"), custom3)
    assert(custom3.contains("localparam FSM_DRAIN = 3'd6;"), custom3)
    assert(custom4.contains("localparam FSM_IDLE = 4'd0;"), custom4)
    assert(custom4.contains("localparam FSM_ACTIVE = 4'd2;"), custom4)
    assert(custom4.contains("localparam FSM_DRAIN = 4'd9;"), custom4)
  }

  test("one-hot projections match native bit-test and overlap semantics for invalid values") {
    val enabled = emit(enabled = true)(new EnumObservationRepro(_, EnumConditionEncodingMode.OneHot))
    val disabled = emit(enabled = false)(new EnumObservationRepro(_, EnumConditionEncodingMode.OneHot))
    assert(enabled.contains("assign io_equalIdle = (io_value[STATE_IDLE_OH_ID]);"), enabled)
    assert(enabled.contains("assign io_notActive = (! io_value[STATE_ACTIVE_OH_ID]);"), enabled)
    assert(enabled.contains("assign io_equalPeer = ((io_value & io_peer) != 3'b000);"), enabled)
    assert(carrierAssignments(enabled).isEmpty, enabled)
    assert(disabled.contains("assign io_equalPeer = ((io_value & io_peer) != 3'b000);"), disabled)
  }

  test("distinct enum definitions with overlapping custom codes stay separate") {
    val enabled = emit(enabled = true)(new DistinctEnumIdentityRepro(_))
    assert(enabled.contains("localparam LEFT_STATE_IDLE = 3'd1;"), enabled)
    assert(enabled.contains("localparam RIGHT_STATE_IDLE = 3'd1;"), enabled)
    assert(enabled.contains("assign io_leftIdle = (io_left == LEFT_STATE_IDLE);"), enabled)
    assert(enabled.contains("assign io_rightIdle = (io_right == RIGHT_STATE_IDLE);"), enabled)
    assert(carrierAssignments(enabled).isEmpty, enabled)
  }

  test("explicit, protected, keep, no-merge, unsafe dependency and expansion fences retain native logic") {
    val enabled = emit(enabled = true)(new EnumConditionNegativeControls(_))
    assert(enabled.contains("wire                when_user_generated_l900;"), enabled)
    assert(enabled.contains("assign when_user_generated_l900 = (state == STATE_DEFINITION_ACTIVE);"), enabled)
    assert(enabled.contains("assign io_explicitResult = when_user_generated_l900;"), enabled)
    assert(enabled.contains("(* keep , syn_keep *)"), enabled)
    assert(enabled.contains("_zz_io_protectedResult"), enabled)
    val generatedAssignments = carrierAssignments(enabled)
    assert(generatedAssignments.exists(_.contains("stage == STATE_DEFINITION_ACTIVE")), enabled)
    assert(generatedAssignments.exists(_.contains("state == STATE_DEFINITION_ACTIVE")), enabled)
    assert(!enabled.contains("morphhdl_"), enabled)
  }
}
