package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._

import morphhdl.frontend._

object CapturedAssignmentNormalizationSmoke {
  final class UnsizedLiteralRegisters(width: HdlInt) extends Component {
    setDefinitionName("CapturedUnsizedLiteralRegisters")

    val load = in(Bool())
    val dout = out(morphhdl.frontend.UInt(width bits))

    if ((width > 1).named("g_literal_wide", "g_literal_narrow")) {
      val wideState =
        morphhdl.frontend
          .Reg(morphhdl.frontend.UInt(width bits))
          .setName("wide_state")
      wideState.init(0)
      when(load) {
        wideState := 1
      }
      dout := wideState
    } else {
      val narrowState =
        morphhdl.frontend
          .Reg(morphhdl.frontend.UInt(width bits))
          .setName("narrow_state")
      narrowState.init(0)
      when(load) {
        narrowState := 1
      }
      dout := narrowState.resized
    }
  }

  final class InactiveBooleanCasts(width: HdlInt) extends Component {
    setDefinitionName("CapturedInactiveBooleanCasts")

    val flag = in(Bool())
    val directWide = in(morphhdl.frontend.UInt(width bits))
    val invertedWide = in(morphhdl.frontend.UInt(width bits))
    val direct = out(morphhdl.frontend.UInt(width bits))
    val inverted = out(morphhdl.frontend.UInt(width bits))

    if ((width > 1).named("g_cast_wide", "g_cast_one")) {
      direct := directWide
      inverted := invertedWide
    } else {
      direct := U(flag)
      inverted := U(!flag)
    }
  }

  final class ActiveBooleanCastMismatch(width: HdlInt) extends Component {
    setDefinitionName("CapturedActiveBooleanCastMismatch")

    val flag = in(Bool())
    val observed = out(morphhdl.frontend.UInt(width bits))

    if ((width > 1).named("g_active_bare", "g_inactive_resized")) {
      observed := U(flag)
    } else {
      observed := U(flag).resized
    }
  }

  final class ExplicitFixedWidthMismatch(width: HdlInt) extends Component {
    setDefinitionName("CapturedExplicitFixedWidthMismatch")

    val fixedTrue = in(spinal.core.UInt(3 bits))
    val fixedFalse = in(spinal.core.UInt(3 bits))
    val observed = out(morphhdl.frontend.UInt(width bits))

    if ((width > 1).named("g_fixed_wide", "g_fixed_narrow")) {
      observed := fixedTrue
    } else {
      observed := fixedFalse
    }
  }

  final class DefaultThenOneSidedWhen(mode: HdlInt) extends Component {
    setDefinitionName("CapturedDefaultThenOneSidedWhen")

    val selectTrue = in(Bool())
    val selectFalse = in(Bool())
    val trueValue = in(Bool())
    val falseValue = in(Bool())
    val observed = out(Bool())

    if ((mode > 0).named("g_default_true", "g_default_false")) {
      observed := False
      when(selectTrue) {
        observed := trueValue
      }
    } else {
      observed := True
      when(selectFalse) {
        observed := falseValue
      }
    }
  }

  final class CompleteWhenThenRoot(mode: HdlInt) extends Component {
    setDefinitionName("CapturedCompleteWhenThenRoot")

    val select = in(Bool())
    val observed = out(Bool())

    if ((mode > 0).named("g_overlap_true", "g_overlap_false")) {
      when(select) {
        observed := True
      } otherwise {
        observed := False
      }
      observed := False
    } else {
      when(select) {
        observed := False
      } otherwise {
        observed := True
      }
      observed := True
    }
  }

  final class NestedInitializedRegisters(width: HdlInt) extends Component {
    setDefinitionName("CapturedNestedInitializedRegisters")

    val load = in(Bool())
    val din = in(morphhdl.frontend.UInt(width bits))
    val dout = out(morphhdl.frontend.UInt(width bits))

    if ((width > 2).named("g_outer_wide", "g_outer_narrow")) {
      if ((width > 5).named("g_inner_wide", "g_inner_middle")) {
        val highState =
          morphhdl.frontend
            .Reg(morphhdl.frontend.UInt(width bits))
            .setName("high_state")
        highState.init(0)
        when(load) {
          highState := din.resized
        }
        dout := highState.resized
      } else {
        val middleState =
          morphhdl.frontend
            .Reg(morphhdl.frontend.UInt(width bits))
            .setName("middle_state")
        middleState.init(0)
        when(load) {
          middleState := din.resized
        }
        dout := middleState.resized
      }
    } else {
      val lowState =
        morphhdl.frontend
          .Reg(morphhdl.frontend.UInt(width bits))
          .setName("low_state")
      lowState.init(0)
      when(load) {
        lowState := din.resized
      }
      dout := lowState.resized
    }
  }

  final class BranchLocalCombinationalDefaults(mode: HdlInt)
      extends Component {
    setDefinitionName("CapturedBranchLocalCombinationalDefaults")

    val selectTrue = in(Bool())
    val selectFalse = in(Bool())
    val trueValue = in(Bool())
    val falseValue = in(Bool())
    val observed = out(Bool())

    if ((mode > 0).named("g_comb_true", "g_comb_false")) {
      val trueComb = Bool().setName("true_comb")
      trueComb := False
      when(selectTrue) {
        trueComb := trueValue
      }
      observed := trueComb
    } else {
      val falseComb = Bool().setName("false_comb")
      falseComb := True
      when(selectFalse) {
        falseComb := falseValue
      }
      observed := falseComb
    }
  }

  final class AncestorDeclaration(width: HdlInt) extends Component {
    setDefinitionName("CapturedAncestorDeclaration")

    val din = in(morphhdl.frontend.Bits(width bits))
    val dout = out(morphhdl.frontend.Bits(width bits))

    if ((width > 1).named("g_ancestor_present", "g_ancestor_absent")) {
      val ancestorValue =
        morphhdl.frontend.Bits(width bits).setName("ancestor_value")
      ancestorValue := din

      if ((width > 4).named("g_ancestor_direct", "g_ancestor_inverted")) {
        dout := ancestorValue
      } else {
        val invertedValue =
          morphhdl.frontend.Bits(width bits).setName("inverted_value")
        invertedValue := ~ancestorValue
        dout := invertedValue
      }
    } else {
      dout := din
    }
  }

  final class StructuralForSink extends Component {
    setDefinitionName("CapturedStructuralForSink")

    val din = in(morphhdl.frontend.Bits(8 bits))
    val observed = out(Bool())

    observed := din.orR
  }

  final class AncestorDeclarationThroughFor(lanes: HdlInt) extends Component {
    setDefinitionName("CapturedAncestorDeclarationThroughFor")

    val din = in(morphhdl.frontend.Bits(8 bits))
    val alive = out(Bool())

    alive := din.orR

    if ((lanes > 1).named("g_for_present", "g_for_absent")) {
      val ancestorValue =
        morphhdl.frontend.Bits(8 bits).setName("for_ancestor_value")
      ancestorValue := din

      (0 until lanes).named("g_ancestor_lane", "ancestor_lane").foreach {
        _ =>
          val laneValue =
            morphhdl.frontend.Bits(8 bits).setName("for_lane_value")
          laneValue := ancestorValue

          val sink = new StructuralForSink
          sink.din := laneValue
      }
    } else {
      val fallback = new StructuralForSink
      fallback.setName("for_fallback")
      fallback.din := din
    }
  }

  final class CachedDriverSink extends Component {
    setDefinitionName("CapturedCachedDriverSink")

    val din = in(Bool())
    val observed = out(Bool())
    observed := din
  }

  final class ChildFirstCachedDriver(mode: HdlInt) extends Component {
    setDefinitionName("CapturedChildFirstCachedDriver")

    val din = in(Bool())
    val observed = out(Bool())

    def cachedDriver: Bool =
      signalCache(this, "child_first_cached_driver") {
        val value = Bool().setName("cached_driver")
        value := din
        value
      }

    if ((mode > 0).named("g_cached_outer", "g_cached_absent")) {
      if ((mode > 1).named("g_cached_inner", "g_cached_shallow")) {
        val innerSink = new CachedDriverSink
        innerSink.setName("cached_inner_sink")
        val innerUse = Bool().setName("inner_use")
        innerUse := cachedDriver
        innerSink.din := innerUse
      } else {
        val shallowSink = new CachedDriverSink
        shallowSink.setName("cached_shallow_sink")
        shallowSink.din := din
      }

      val outerUse = Bool().setName("outer_use")
      outerUse := cachedDriver
      observed := outerUse
    } else {
      observed := din
    }
  }

  final class RawControlOnlyCachedDriver(mode: HdlInt) extends Component {
    setDefinitionName("CapturedRawControlOnlyCachedDriver")

    val din = in(Bool())
    val observed = out(Bool())

    def cachedDriver: Bool =
      signalCache(this, "raw_control_cached_driver") {
        val value = Bool().setName("raw_control_driver")
        value := din
        value
      }

    if ((mode > 0).named("g_raw_outer", "g_raw_absent")) {
      if ((mode > 1).named("g_raw_inner", "g_raw_shallow")) {
        val innerUse = Bool().setName("raw_inner_use")
        innerUse := cachedDriver
      } else {
        val shallowUse = Bool().setName("raw_shallow_use")
        shallowUse := din
      }

      val controlled = Bool().setName("raw_controlled")
      controlled := False
      when(cachedDriver) {
        controlled := True
      }
      observed := controlled
    } else {
      observed := din
    }
  }

  final class VecDuplicatedRawControlCachedDriver(
      mode: HdlInt,
      lanes: HdlInt
  ) extends Component {
    setDefinitionName("CapturedVecDuplicatedRawControlCachedDriver")

    val din = in(Bool())
    val laneIn =
      in(morphhdl.frontend.Vec(morphhdl.frontend.Bits(1 bits), 2))
    val observed = out(Bool())

    def cachedDriver: Bool =
      signalCache(this, "vec_raw_control_cached_driver") {
        val value = Bool().setName("vec_raw_control_driver")
        value := din
        value
      }

    if ((mode > 0).named("g_vec_raw_outer", "g_vec_raw_absent")) {
      (0 until lanes).named("g_vec_raw_lane", "vec_raw_lane").foreach {
        index =>
          val selected = Bool().setName("vec_raw_selected")
          selected := laneIn(index).orR
          val selectedSink = new CachedDriverSink
          selectedSink.setName("vec_raw_selected_sink")
          selectedSink.din := selected

          val innerUse = Bool().setName("vec_raw_inner_use")
          innerUse := cachedDriver
          val driverSink = new CachedDriverSink
          driverSink.setName("vec_raw_driver_sink")
          driverSink.din := innerUse
      }

      val controlled = Bool().setName("vec_raw_controlled")
      controlled := False
      when(cachedDriver) {
        controlled := True
      }
      observed := controlled
    } else {
      observed := din
    }
  }

  final class VecScopedNestedConsumer(mode: HdlInt, lanes: HdlInt)
      extends Component {
    setDefinitionName("CapturedVecScopedNestedConsumer")

    val din = in(Bool())
    val laneIn =
      in(morphhdl.frontend.Vec(morphhdl.frontend.Bits(1 bits), 2))
    val observed = out(Bool())

    observed := din
    (0 until lanes).named("g_vec_scoped_lane", "vec_scoped_lane").foreach {
      index =>
        val selected = Bool().setName("vec_scoped_selected")
        selected := laneIn(index).orR
        val selectedSink = new CachedDriverSink
        selectedSink.setName("vec_scoped_selected_sink")
        selectedSink.din := selected

        val scopedDriver = Bool().setName("vec_scoped_driver")
        scopedDriver := din
        if ((mode > 0).named("g_vec_scoped_inner", "g_vec_scoped_shallow")) {
          val innerUse = Bool().setName("vec_scoped_inner_use")
          innerUse := scopedDriver
          val innerSink = new CachedDriverSink
          innerSink.setName("vec_scoped_inner_sink")
          innerSink.din := innerUse
        } else {
          val shallowSink = new CachedDriverSink
          shallowSink.setName("vec_scoped_shallow_sink")
          shallowSink.din := din
        }
    }
  }

  final class PartialProducerRhsPromotion(mode: HdlInt) extends Component {
    setDefinitionName("CapturedPartialProducerRhsPromotion")

    val din = in(Bool())
    val observed = out(Bool())

    if ((mode > 0).named("g_partial_outer", "g_partial_absent")) {
      val partialSource = Bool().setName("partial_source")
      def cachedDriver: Bool =
        signalCache(this, "partial_producer_cached_driver") {
          val value = Bool().setName("partial_cached_driver")
          value := partialSource
          value
        }

      if ((mode > 1).named("g_partial_inner", "g_partial_shallow")) {
        partialSource := din
        val innerUse = Bool().setName("partial_inner_use")
        innerUse := cachedDriver
        val innerSink = new CachedDriverSink
        innerSink.setName("partial_inner_sink")
        innerSink.din := innerUse
      } else {
        val shallowSink = new CachedDriverSink
        shallowSink.setName("partial_shallow_sink")
        shallowSink.din := din
      }

      val outerUse = Bool().setName("partial_outer_use")
      outerUse := cachedDriver
      observed := outerUse
    } else {
      observed := din
    }
  }

  final class AnonymousCastHelperOwnership(mode: HdlInt) extends Component {
    setDefinitionName("CapturedAnonymousCastHelperOwnership")

    val trueInput = in(Bool())
    val falseInput = in(Bool())
    val observed = out(spinal.core.UInt(3 bits))

    if ((mode > 0).named("g_helper_true", "g_helper_false")) {
      val trueLocal = Bool().setName("true_local")
      trueLocal := trueInput
      observed := U(trueLocal).resized
    } else {
      val falseLocal = Bool().setName("false_local")
      falseLocal := falseInput
      observed := U(falseLocal).resized
    }
  }

  final class RootScopeAlgebraicWidthMismatch(depth: HdlInt) extends Component {
    setDefinitionName("CapturedRootScopeAlgebraicWidthMismatch")

    val candidate = in(morphhdl.frontend.UInt((depth * 2) bits))
    val observed = out(morphhdl.frontend.UInt((depth + 2) bits))
    observed := candidate
  }

  final class CapturedDomainAlgebraicWidthMismatch(depth: HdlInt)
      extends Component {
    setDefinitionName("CapturedDomainAlgebraicWidthMismatch")

    val candidate = in(morphhdl.frontend.UInt((depth * 2) bits))
    val matching = in(morphhdl.frontend.UInt((depth + 2) bits))
    val observed = out(morphhdl.frontend.UInt((depth + 2) bits))

    if ((depth <= 2).named("g_domain_low", "g_domain_high")) {
      observed := candidate
    } else {
      observed := matching
    }
  }

  final class UnsafeInactiveWideningCarrier(width: HdlInt)
      extends Component {
    setDefinitionName("CapturedUnsafeInactiveWideningCarrier")

    val din = in(morphhdl.frontend.UInt(width bits))
    val observed = out(Bool())

    if ((width <= 3).named("g_carrier_safe", "g_carrier_unsafe")) {
      observed := din.orR
    } else {
      val widened = morphhdl.frontend
        .UInt(width bits)
        .setName("unsafe_inactive_widening")
      widened := din.resized
      observed := widened.orR
    }
  }
}

class CapturedAssignmentNormalizationTests extends AnyFunSuite {
  import CapturedAssignmentNormalizationSmoke._

  test("captured symbolic registers retain unsized init and data literals") {
    withTemporaryDirectory { directory =>
      val verilog = emitMorph(
        directory,
        "captured_unsized_literals.v",
        new UnsizedLiteralRegisters(symbolicWidth()),
        synchronousResetConfig(directory)
      )

      assert(verilog.contains("parameter integer WIDTH = 3"))
      assert(verilog.contains("begin : g_literal_wide"))
      assert(verilog.contains("begin : g_literal_narrow"))
      assert(verilog.contains("wide_state"))
      assert(verilog.contains("narrow_state"))
      assert(occurrences(verilog, "wide_state <=") >= 2)
      assert(occurrences(verilog, "narrow_state <=") >= 2)
      assert(verilog.contains("{1'b0, _zz_dout}"))
      assert(!verilog.contains("{2'd0, _zz_dout}"))
    }
  }

  test("witness-inactive direct and negated Bool-to-UInt casts normalize exactly") {
    withTemporaryDirectory { directory =>
      val verilog = emitMorph(
        directory,
        "captured_inactive_boolean_casts.v",
        new InactiveBooleanCasts(symbolicWidth())
      )

      assert(verilog.contains("parameter integer WIDTH = 3"))
      assert(verilog.contains("begin : g_cast_wide"))
      assert(verilog.contains("begin : g_cast_one"))
      assert(verilog.contains("direct"))
      assert(verilog.contains("inverted"))
      assert(verilog.contains("directWide"))
      assert(verilog.contains("invertedWide"))
      assert(verilog.contains("flag"))
      assert(verilog.contains("!"))
    }
  }

  test("an active captured bare Bool-to-UInt mismatch remains rejected") {
    withTemporaryDirectory { directory =>
      val fileName = "captured_active_boolean_mismatch.v"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = fileName
      val result = MorphVerilog.tryGenerate(config) {
        new ActiveBooleanCastMismatch(symbolicWidth())
      }

      result match {
        case Left(failure) =>
          assert(
            failure.detail.contains("WIDTH MISMATCH") ||
              failure.detail.contains(
                "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
              ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected active Bool width failure, received $report")
      }
      assert(!Files.exists(directory.resolve(fileName)))
    }
  }

  test("captured explicit fixed-width values remain rejected") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "captured_fixed_width_mismatch.v"
      val result = MorphVerilog.tryGenerate(config) {
        new ExplicitFixedWidthMismatch(symbolicWidth())
      }

      result match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected explicit fixed-width failure, received $report")
      }
      assert(
        !Files.exists(directory.resolve("captured_fixed_width_mismatch.v"))
      )
    }
  }

  test("exclusive alternatives preserve a default plus one-sided when override") {
    withTemporaryDirectory { directory =>
      val mode = HdlInt.param("MODE", default = 1, min = 0, max = 1)
      val verilog = emitMorph(
        directory,
        "captured_default_then_when.v",
        new DefaultThenOneSidedWhen(mode)
      )

      val trueBody = branchBody(verilog, "g_default_true", "g_default_false")
      val falseBody = verilog.substring(verilog.indexOf("begin : g_default_false"))
      assert(trueBody.contains("trueValue"))
      assert(!trueBody.contains("falseValue"))
      assert(falseBody.contains("falseValue"))
      assert(verilog.contains("always @(*)"))
    }
  }

  test("a complete when otherwise followed by a root assignment remains an overlap") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "captured_complete_when_overlap.v"
      val result = MorphVerilog.tryGenerate(config) {
        val mode = HdlInt.param("MODE", default = 1, min = 0, max = 1)
        new CompleteWhenThenRoot(mode)
      }

      result match {
        case Left(failure) =>
          assert(failure.detail.contains("ASSIGNMENT OVERLAP"), failure.detail)
        case Right(report) =>
          fail(s"Expected inherited overlap failure, received $report")
      }
      assert(
        !Files.exists(directory.resolve("captured_complete_when_overlap.v"))
      )
    }
  }

  test("nested captured initialized registers remain in separate native processes") {
    withTemporaryDirectory { directory =>
      val verilog = emitMorph(
        directory,
        "captured_nested_initialized_registers.v",
        new NestedInitializedRegisters(symbolicWidth(default = 6)),
        synchronousResetConfig(directory)
      )

      Vector(
        "g_outer_wide",
        "g_inner_wide",
        "g_inner_middle",
        "g_outer_narrow",
        "high_state",
        "middle_state",
        "low_state"
      ).foreach(token => assert(verilog.contains(token), s"missing $token"))
      assert(occurrences(verilog, "always @(posedge clk)") == 3)
      assert(verilog.contains("{1'b0, _zz_dout_1}"), verilog)
      assert(verilog.contains("{1'b0, _zz_dout_2}"), verilog)
      assert(!verilog.contains("{3'd0, _zz_dout_1}"), verilog)
      assert(!verilog.contains("{5'd0, _zz_dout_2}"), verilog)
    }
  }

  test("witness-inactive auto-resize carriers cannot truncate a wider owner domain") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "captured_unsafe_inactive_widening.v"
      MorphVerilog.tryGenerate(config) {
        new UnsafeInactiveWideningCarrier(symbolicWidth(default = 3))
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-REPRESENTATIVE-MISMATCH"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected unsafe inactive widening rejection, received $report")
      }
      assert(!Files.exists(directory.resolve(config.netlistFileName)))
    }
  }

  test("branch-local combinational defaults retain no-else overrides") {
    withTemporaryDirectory { directory =>
      val mode = HdlInt.param("MODE", default = 1, min = 0, max = 1)
      val verilog = emitMorph(
        directory,
        "captured_branch_local_comb.v",
        new BranchLocalCombinationalDefaults(mode)
      )

      val trueBody = branchBody(verilog, "g_comb_true", "g_comb_false")
      val falseBody = verilog.substring(verilog.indexOf("begin : g_comb_false"))
      assert(trueBody.contains("true_comb"))
      assert(trueBody.contains("selectTrue"))
      assert(!trueBody.contains("false_comb"))
      assert(falseBody.contains("false_comb"))
      assert(falseBody.contains("selectFalse"))
      assert(occurrences(verilog, "always @(*)") >= 2)
    }
  }

  test("an ancestor declaration and continuous assignment stay with nested descendants") {
    withTemporaryDirectory { directory =>
      val verilog = emitMorph(
        directory,
        "captured_ancestor_declaration.v",
        new AncestorDeclaration(symbolicWidth(default = 3))
      )

      val outerStart = verilog.indexOf("begin : g_ancestor_present")
      val outerFalse = verilog.indexOf("begin : g_ancestor_absent")
      assert(outerStart >= 0 && outerFalse > outerStart)
      assert(!verilog.substring(0, outerStart).contains("ancestor_value"))
      val outerBody = verilog.substring(outerStart, outerFalse)
      assert(outerBody.contains("ancestor_value"))
      assert(outerBody.contains("inverted_value"))
      assert(
        outerBody.replaceAll("\\s+", "").contains(
          "assignancestor_value=din;"
        )
      )
      assert(occurrences(outerBody, "ancestor_value") >= 4)
      assert(outerBody.contains("begin : g_ancestor_direct"))
      assert(outerBody.contains("begin : g_ancestor_inverted"))
    }
  }

  test("an ancestor declaration and driver stay outside a nested structural for") {
    withTemporaryDirectory { directory =>
      val lanes = HdlInt.param("LANES", default = 3, min = 1, max = 4)
      val verilog = emitMorph(
        directory,
        "captured_ancestor_structural_for.v",
        new AncestorDeclarationThroughFor(lanes)
      )

      val outerStart = verilog.indexOf("begin : g_for_present")
      val loopStart = verilog.indexOf("begin : g_ancestor_lane")
      val outerFalse = verilog.indexOf("begin : g_for_absent")
      assert(outerStart >= 0 && loopStart > outerStart && outerFalse > loopStart)
      assert(!verilog.substring(0, outerStart).contains("for_ancestor_value"))
      val beforeLoop = verilog.substring(outerStart, loopStart)
      assert(beforeLoop.contains("for_ancestor_value"))
      assert(
        beforeLoop.replaceAll("\\s+", "").contains(
          "assignfor_ancestor_value=din;"
        )
      )
      val loopBody = verilog.substring(loopStart, outerFalse)
      assert(loopBody.contains("for_lane_value"))
      assert(loopBody.contains("for_ancestor_value"))
      assert(
        !loopBody.replaceAll("\\s+", "").contains(
          "wire[7:0]for_ancestor_value"
        )
      )
    }
  }

  test("a child-first cached driver is promoted to its consuming ancestor") {
    withTemporaryDirectory { directory =>
      val mode = HdlInt.param("MODE", default = 2, min = 0, max = 2)
      val verilog = emitMorph(
        directory,
        "captured_child_first_cached_driver.v",
        new ChildFirstCachedDriver(mode)
      )

      val outerStart = verilog.indexOf("begin : g_cached_outer")
      val innerStart = verilog.indexOf("begin : g_cached_inner")
      val outerFalse = verilog.indexOf("begin : g_cached_absent")
      assert(
        outerStart >= 0 && innerStart > outerStart && outerFalse > innerStart
      )

      val outerPrefix = verilog.substring(outerStart, innerStart)
      val nestedBody = verilog.substring(innerStart, outerFalse)
      val compactVerilog = verilog.replaceAll("\\s+", "")
      val compactOuterPrefix = outerPrefix.replaceAll("\\s+", "")
      val compactNestedBody = nestedBody.replaceAll("\\s+", "")

      assert(compactOuterPrefix.contains("wirecached_driver;"))
      assert(compactOuterPrefix.contains("assigncached_driver=din;"))
      assert(compactOuterPrefix.contains("assignouter_use=cached_driver;"))
      assert(occurrences(compactVerilog, "wirecached_driver;") == 1)
      assert(
        occurrences(compactVerilog, "assigncached_driver=din;") == 1
      )
      assert(!compactNestedBody.contains("wirecached_driver;"))
      assert(!compactNestedBody.contains("assigncached_driver=din;"))
      assert(compactNestedBody.contains("assigninner_use=cached_driver;"))
    }
  }

  test("a raw-control-only ancestor consumer fails closed") {
    withTemporaryDirectory { directory =>
      val mode = HdlInt.param("MODE", default = 2, min = 0, max = 2)
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "captured_raw_control_only_cached_driver.v"
      config.netlistFileName = fileName

      MorphVerilog.tryGenerate(config) {
        new RawControlOnlyCachedDriver(mode)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected raw-control dominance failure, received $report")
      }
      assert(!Files.exists(directory.resolve(fileName)))
    }
  }

  test("Vec-cloned drivers retain one owner for raw-control dominance") {
    withTemporaryDirectory { directory =>
      val mode = HdlInt.param("MODE", default = 1, min = 0, max = 1)
      val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 2)
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "captured_vec_duplicated_raw_control_driver.v"
      config.netlistFileName = fileName

      MorphVerilog.tryGenerate(config) {
        new VecDuplicatedRawControlCachedDriver(mode, lanes)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN"
            ),
            failure.detail
          )
          assert(failure.detail.contains("vec_raw_control_driver"), failure.detail)
        case Right(report) =>
          fail(s"Expected Vec-cloned raw-control dominance failure, received $report")
      }
      assert(!Files.exists(directory.resolve(fileName)))
    }
  }

  test("single-value Vec-scoped direct driver fails closed") {
    withTemporaryDirectory { directory =>
      val mode = HdlInt.param("MODE", default = 1, min = 0, max = 1)
      val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 1)
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "captured_vec_scoped_promoted_driver.v"
      config.netlistFileName = fileName

      MorphVerilog.tryGenerate(config) {
        new VecScopedNestedConsumer(mode, lanes)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN"
            ),
            failure.detail
          )
          assert(failure.detail.contains("vec_scoped_driver"), failure.detail)
        case Right(report) =>
          fail(s"Expected Vec-scoped dominance failure, received $report")
      }
      assert(!Files.exists(directory.resolve(fileName)))
    }
  }

  test("promotion rejects an internal RHS with only a deeper producer") {
    withTemporaryDirectory { directory =>
      val mode = HdlInt.param("MODE", default = 2, min = 0, max = 2)
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "captured_partial_producer_rhs_promotion.v"
      config.netlistFileName = fileName

      MorphVerilog.tryGenerate(config) {
        new PartialProducerRhsPromotion(mode)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN"
            ),
            failure.detail
          )
          assert(failure.detail.contains("partial_cached_driver"), failure.detail)
          assert(failure.detail.contains("rhsSourcesAvailable=false"), failure.detail)
        case Right(report) =>
          fail(s"Expected partial-producer promotion failure, received $report")
      }
      assert(!Files.exists(directory.resolve(fileName)))
    }
  }

  test("anonymous Bool-cast helper assignments remain inside their alternatives") {
    withTemporaryDirectory { directory =>
      val mode = HdlInt.param("MODE", default = 1, min = 0, max = 1)
      val verilog = emitMorph(
        directory,
        "captured_anonymous_cast_helper.v",
        new AnonymousCastHelperOwnership(mode)
      )

      val trueStart = verilog.indexOf("begin : g_helper_true")
      val falseStart = verilog.indexOf("begin : g_helper_false")
      assert(trueStart >= 0 && falseStart > trueStart)
      val modulePrefix = verilog.substring(0, trueStart)
      val trueBody = verilog.substring(trueStart, falseStart)
      val falseBody = verilog.substring(falseStart)
      assert(!modulePrefix.contains("true_local"))
      assert(!modulePrefix.contains("false_local"))
      assert(hasContinuousAssignmentFrom(trueBody, "true_local"))
      assert(hasContinuousAssignmentFrom(falseBody, "false_local"))
      assert(!trueBody.contains("false_local"))
    }
  }

  test("algebraically equal witness widths remain rejected at root scope") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param("DEPTH", default = 2, min = 1, max = 4)
      expectWidthMismatch(
        directory,
        "captured_root_scope_algebraic_width_mismatch.v",
        new RootScopeAlgebraicWidthMismatch(depth)
      )
    }
  }

  test("captured width equivalence is rejected when any admitted value diverges") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param("DEPTH", default = 2, min = 1, max = 4)
      expectWidthMismatch(
        directory,
        "captured_domain_algebraic_width_mismatch.v",
        new CapturedDomainAlgebraicWidthMismatch(depth)
      )
    }
  }

  private def symbolicWidth(default: Int = 3): HdlInt =
    HdlInt.param("WIDTH", default = default, min = 1, max = 8)

  private def expectWidthMismatch(
      directory: Path,
      fileName: String,
      component: => Component
  ): Unit = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = fileName
    MorphVerilog.tryGenerate(config)(component) match {
      case Left(failure) =>
        assert(
          failure.detail.contains(
            "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
          ),
          failure.detail
        )
      case Right(report) =>
        fail(s"Expected captured symbolic width mismatch, received $report")
    }
    assert(!Files.exists(directory.resolve(fileName)))
  }

  private def emitMorph(
      directory: Path,
      fileName: String,
      component: => Component,
      config: SpinalConfig = null
  ): String = {
    Files.createDirectories(directory)
    val selected =
      if (config == null) SpinalConfig(targetDirectory = directory.toString)
      else config
    selected.netlistFileName = fileName
    MorphVerilog(selected)(component)
    read(directory.resolve(fileName))
  }

  private def synchronousResetConfig(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )

  private def branchBody(
      verilog: String,
      firstLabel: String,
      secondLabel: String
  ): String = {
    val first = verilog.indexOf(s"begin : $firstLabel")
    val second = verilog.indexOf(s"begin : $secondLabel")
    assert(first >= 0 && second > first)
    verilog.substring(first, second)
  }

  private def occurrences(value: String, needle: String): Int = {
    var count = 0
    var from = 0
    var found = value.indexOf(needle, from)
    while (found >= 0) {
      count += 1
      from = found + needle.length
      found = value.indexOf(needle, from)
    }
    count
  }

  private def hasContinuousAssignmentFrom(
      body: String,
      sourceName: String
  ): Boolean =
    body.split("\\n", -1).exists { line =>
      val compact = line.replaceAll("\\s+", "")
      compact.startsWith("assign") && compact.endsWith(s"=$sourceName;")
    }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-captured-assignment-test-")
    try body(directory)
    finally deleteTree(directory)
  }

  private def deleteTree(root: Path): Unit =
    if (Files.exists(root)) {
      val stream = Files.walk(root)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach {
          path => Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
}
