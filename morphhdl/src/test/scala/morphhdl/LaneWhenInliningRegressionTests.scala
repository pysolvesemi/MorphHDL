package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.HdlBool

/** Production API tests. HDL simulation/formal checks accompany these artifacts. */
class LaneWhenInliningRegressionTests extends AnyFunSuite {
  private def emit(mode: String = "default", customize: SpinalConfig => Unit = _ => ())(factory: ElabInt => Component): String = {
    val dir = Files.createTempDirectory("lane-when-regression-")
    try {
      val base = SpinalConfig(targetDirectory = dir.toString, oneFilePerComponent = false,
        headerWithDate = false, headerWithRepoHash = true)
      base.netlistFileName = "design.v"
      customize(base)
      val config = mode match {
        case "default" => base
        case "enabled" => MorphWireAssignmentPasses(base, enabled = true)
        case "disabled" => MorphWireAssignmentPasses(base, enabled = false)
      }
      MorphVerilog(config) {
        factory(HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1)
      }
      new String(Files.readAllBytes(dir.resolve("design.v")), StandardCharsets.UTF_8)
    } finally {
      val paths = Files.walk(dir)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }
  private def assignments(v: String, fragment: String): Vector[String] =
    v.split("\n").filter(line => line.trim.startsWith("assign ") && line.contains(fragment)).toVector
  private lazy val lane = emit()(new LaneExpressionExample(_))
  private lazy val disabledLane = emit("disabled")(new LaneExpressionExample(_))
  private lazy val conditions = emit()(new LaneConditionCoverageExample(_))
  private lazy val receivers = emit()(new LaneReceiverCoverageExample(_))

  test("all four laneDe comparisons inline fixed extensions through the process-separation tag") {
    val namedClass = Class.forName("morphhdl.examples.NamedWireExpressionNativePhase")
    val namedConstructor = namedClass.getConstructor(java.lang.Boolean.TYPE)
    for (unnamedOnly <- Vector(false, true)) {
      val phase = namedConstructor.newInstance(Boolean.box(unnamedOnly))
        .asInstanceOf[spinal.core.internals.Phase]
      assert(phase.hasNetlistImpact)
    }
    for (name <- Vector("UnnamedWireExpressionNativePhase", "ProductionWireAssignmentPhase")) {
      val constructor = Class.forName("morphhdl.examples." + name).getConstructor()
      val first = constructor.newInstance().asInstanceOf[spinal.core.internals.Phase]
      val second = constructor.newInstance().asInstanceOf[spinal.core.internals.Phase]
      assert(first.hasNetlistImpact && second.hasNetlistImpact)
      assert(first ne second)
    }
    assert(!lane.contains("_zz_laneDe"), lane)
    for (n <- 0 until 4) {
      assert(lane.contains(s"laneDe[$n] = ((io_running && ({3'd0, laneX_$n} < io_hActive)) && ({4'd0, laneY_$n} < io_vActive));"), lane)
    }
    assert(lane.contains("reg        [3:0]    laneDe;"), lane)
  }
  test("laneFrameEnd retains 16-bit modular subtraction while inlining both extensions") {
    assert(!lane.contains("_zz_laneFrameEnd"), lane)
    for (n <- 0 until 4) {
      assert(lane.contains(s"laneFrameEnd[$n] = ((io_running && ({3'd0, laneX_$n} == (io_hActive - 16'h0001))) && ({4'd0, laneY_$n} == (io_vActive - 16'h0001)));"), lane)
    }
  }
  test("small generated conditions inline at all split and nested receivers without rematerialization") {
    assert(assignments(lane, "when_").isEmpty, lane)
    assert(!lane.contains("_zz_when"), lane)
    for (n <- 0 until 4) {
      assert(lane.contains(s"if((laneX_$n == (io_hTotal - 13'h0001)))"), lane)
      assert(lane.contains(s"if((laneY_$n == (io_vTotal - 12'h001)))"), lane)
    }
    assert(lane.split("always @", -1).length == disabledLane.split("always @", -1).length)
  }
  test("default and explicit enable are identical and repeated generation is deterministic") {
    assert(lane == emit("enabled")(new LaneExpressionExample(_)))
    assert(lane == emit()(new LaneExpressionExample(_)))
    assert(disabledLane == emit("disabled")(new LaneExpressionExample(_)))
  }
  test("disabled generation retains the legacy carrier forms and symbolic output geometry") {
    assert(disabledLane.contains("assign _zz_laneDe = {3'd0, laneX_0};"))
    assert(disabledLane.contains("assign when_"))
    for (v <- Vector(lane, disabledLane)) {
      assert(v.contains("parameter integer PPC4 = 0"))
      assert(v.contains("[((PPC4 * 3) + 1)-1:0]    io_de"))
      assert(v.contains("[((PPC4 * 3) + 1)-1:0]    io_frameEnd"))
    }
  }
  test("single shared priority and mixed Boolean RHS conditions inline; two protected identities survive") {
    assert(conditions.contains("assign io_booleanRhs = (io_y == (io_vTotal - 12'h001));"), conditions)
    assert(conditions.contains("if((io_x == 13'h0))"), conditions)
    val declarations = conditions.split("\n").filter(line => line.contains("wire") && line.contains("when_") && !line.contains("_zz_"))
    assert(declarations.length == 2, conditions)
    assert(declarations.forall(_.contains("(* keep *)")), conditions)
    assert(conditions.contains("if(when_example_l42)"), conditions)
    assert(conditions.contains("assign io_protectedBoolean = when_example_l42;"), conditions)
  }
  test("whole Bool and disjoint bit receivers agree while overlapping dynamic and protected receivers retain fences") {
    assert(!receivers.contains("_zz_io_whole"), receivers)
    assert(!receivers.contains("_zz_lanes"), receivers)
    assert(receivers.contains("_zz_overlapping"), receivers)
    assert(receivers.contains("_zz_dynamicallySelected"), receivers)
    assert(receivers.contains("(* keep *) reg        [3:0]    keptLane;"), receivers)
    assert(receivers.contains("_zz_keptLane"), receivers)
  }
  test("disjoint fixed ranges retain their own widths with the layout-only tag") {
    val v = emit() { ppc => new Component {
      setDefinitionName("LaneFixedRanges")
      val a, b, c = in UInt(18 bits)
      val packedValue = out UInt(36 bits)
      packedValue.addTag(noBackendCombMerge)
      val witnessIn = in Bits(ppc bits)
      val witnessOut = out Bits(ppc bits)
      witnessOut := witnessIn
      packedValue(17 downto 0) := (a + b) + c
      packedValue(35 downto 18) := (a - b) - c
    }}
    assert(!v.contains("_zz_packedValue"), v)
    assert(v.contains("packedValue[17 : 0] = ((a + b) + c);"), v)
    assert(v.contains("packedValue[35 : 18] = ((a - b) - c);"), v)
  }

  private object UnknownIntent extends SpinalTag
  private final class ProtectedCondition(ppc: ElabInt, protection: Int) extends Component {
    setDefinitionName("LaneProtectedCondition")
    val x, total = in UInt(13 bits)
    val value = out UInt(13 bits)
    val witnessIn = in Bits(ppc bits)
    val witnessOut = out Bits(ppc bits)
    witnessOut := witnessIn
    @dontName val condition = x === total - 1
    protection match {
      case 0 => condition.setAsVital()
      case 1 => condition.dontSimplifyIt()
      case 2 => condition.freeze()
      case 3 => condition.addTag(spinal.core.sim.SimPublic)
      case 4 => condition.setName("when_user_l42")
      case 5 => condition.addTag(UnknownIntent)
      case 6 => // The application transformation phase supplies vital intent.
    }
    value := x + 1
    when(condition) { value := 0 }
  }
  for ((label, protection) <- Vector("explicit vital", "dontSimplify", "frozen", "debug", "explicit lookalike", "unknown metadata").zipWithIndex) {
    test(s"$label condition identity is not erased") {
      var fixture: ProtectedCondition = null
      val v = emit() { ppc => fixture = new ProtectedCondition(ppc, protection); fixture }
      val name = fixture.condition.getName()
      assert(v.contains(s"assign $name = "), v)
      assert(v.contains(s"if($name)"), v)
    }
  }
  test("application transformation-phase vital intent is captured before inferred liveness") {
    var fixture: ProtectedCondition = null
    val v = emit(customize = config => {
      config.transformationPhases += new spinal.core.internals.Phase {
        override def hasNetlistImpact: Boolean = false
        override def impl(pc: spinal.core.internals.PhaseContext): Unit =
          fixture.condition.setAsVital()
      }
    }) { ppc => fixture = new ProtectedCondition(ppc, 6); fixture }
    val name = fixture.condition.getName()
    assert(v.contains(s"assign $name = "), v)
    assert(v.contains(s"if($name)"), v)
  }
  test("source dependency written in a potentially coalesced blocking process stays fenced") {
    var condition: Bool = null
    val v = emit() { ppc => new Component {
      setDefinitionName("LaneBlockingCondition")
      val source, enable = in Bool()
      val result = out Bool()
      val witnessIn = in Bits(ppc bits)
      val witnessOut = out Bits(ppc bits)
      witnessOut := witnessIn
      val stage = Bool()
      @dontName val predicate = !stage
      condition = predicate
      stage := source
      result := False
      when(enable) {
        stage := !source
        when(predicate) { result := True }
      }
    }}
    assert(v.contains(s"assign ${condition.getName()} = "), v)
    assert(v.contains(s"if(${condition.getName()})"), v)
  }
  test("more than 32 generated-condition receivers retain their common predicate") {
    var condition: Bool = null
    val v = emit() { ppc => new Component {
      setDefinitionName("LaneConditionExpansionBudget")
      val x, total = in UInt(13 bits)
      val values = out Vec(UInt(13 bits), 33)
      val witnessIn = in Bits(ppc bits)
      val witnessOut = out Bits(ppc bits)
      witnessOut := witnessIn
      @dontName val predicate = x === total - 1
      condition = predicate
      for (n <- 0 until 33) {
        values(n) := x + U(n, 13 bits)
        when(predicate) { values(n) := 0 }
      }
    }}
    assert(v.contains(s"assign ${condition.getName()} = "), v)
  }
}
