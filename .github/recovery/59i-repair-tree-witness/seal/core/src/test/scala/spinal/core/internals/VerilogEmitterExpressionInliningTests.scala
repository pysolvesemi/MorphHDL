package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class VerilogEmitterExpressionInliningTests extends AnyFunSuite {
  private object PreserveWitness extends SpinalTag
  private final class TaggedUnsignedAdd
      extends Operator.UInt.Add
      with SpinalTagReady

  private def generate(
      name: String,
      enabled: Boolean,
      configureBase: SpinalConfig => Unit = _ => ()
  )(body: => Component): String = {
    val directory = Files.createTempDirectory("verilog-emitter-inline-")
    try {
      val base = SpinalConfig(targetDirectory = directory.toString)
      base.netlistFileName = name + ".v"
      configureBase(base)
      val config = VerilogEmitterExpressionInlining.configure(base, enabled)
      SpinalVerilog(config)(body)
      new String(Files.readAllBytes(directory.resolve(name + ".v")), StandardCharsets.UTF_8)
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  private def fixedTree(componentName: String): Component = new Component {
    setDefinitionName(componentName)
    val a, b, c, d = in UInt (16 bits)
    val total = out UInt (18 bits)
    total := (((a.resize(18) + b.resize(18)) + c.resize(18)) + d.resize(18))
  }

  private def wrapperAssignments(verilog: String): Vector[String] =
    verilog.split("\\n").iterator
      .filter(line => line.trim.startsWith("assign _zz_"))
      .toVector

  test("the opt-in policy inlines an exact fixed unsigned widening-add tree") {
    val verilog = generate("FixedUnsignedInline", enabled = true) {
      fixedTree("FixedUnsignedInline")
    }

    assert(wrapperAssignments(verilog).isEmpty)
    assert(!verilog.contains("morphhdl_resize"))
    assert(verilog.contains("assign total ="))
    assert(verilog.contains("{2'd0, a}"))
    assert(verilog.contains("{2'd0, d}"))
  }

  test("ordinary Verilog emission retains the historical wrappers") {
    val base = SpinalConfig()
    val enabledConfig = VerilogEmitterExpressionInlining.configure(base, enabled = true)
    val disabledAgain = VerilogEmitterExpressionInlining.configure(enabledConfig, enabled = false)
    assert(VerilogEmitterExpressionInlining.isEnabled(enabledConfig))
    assert(!VerilogEmitterExpressionInlining.isEnabled(disabledAgain))

    val verilog = generate("FixedUnsignedLegacy", enabled = false) {
      fixedTree("FixedUnsignedLegacy")
    }

    assert(wrapperAssignments(verilog).size == 6)
    assert(verilog.contains("assign _zz_total_1 ="))
    assert(verilog.contains("assign total = (_zz_total +"))
  }

  test("a truncating root retains its sizing boundary") {
    val verilog = generate("TruncatingUnsigned", enabled = true) {
      new Component {
        setDefinitionName("TruncatingUnsigned")
        val a, b, c = in UInt (18 bits)
        val total = out UInt (16 bits)
        total := ((a + b) + c).resize(16)
      }
    }

    assert(wrapperAssignments(verilog).nonEmpty)
  }

  test("same-width modular overflow keeps the original tree while wrappers inline") {
    val verilog = generate("ModularOverflowInline", enabled = true) {
      new Component {
        setDefinitionName("ModularOverflowInline")
        val a, b, c = in UInt (18 bits)
        val total = out UInt (18 bits)
        total := ((a + b) + c)
      }
    }

    assert(wrapperAssignments(verilog).isEmpty)
    assert(verilog.contains("assign total = ((a + b) + c);"))
  }

  test("signed addition retains emitter-created boundaries") {
    val verilog = generate("SignedBoundary", enabled = true) {
      new Component {
        setDefinitionName("SignedBoundary")
        val a, b, c = in SInt (18 bits)
        val total = out SInt (18 bits)
        total := ((a + b) + c)
      }
    }

    assert(wrapperAssignments(verilog).nonEmpty)
  }

  test("widening concatenation preserves narrower modular arithmetic without a carrier") {
    val verilog = generate("MixedWidthBoundary", enabled = true) {
      new Component {
        setDefinitionName("MixedWidthBoundary")
        val a, b = in UInt (16 bits)
        val c = in UInt (18 bits)
        val total = out UInt (18 bits)
        total := ((a + b).resize(18) + c)
      }
    }

    assert(wrapperAssignments(verilog).isEmpty)
    assert(verilog.contains("assign total = ({2'd0, (a + b)} + c);"))
  }

  test("a retained symbolic resize target cannot be treated as its fixed witness") {
    val verilog = generate("SymbolicResizeBoundary", enabled = true) {
      new Component {
        setDefinitionName("SymbolicResizeBoundary")
        val symbolicWidth = ElabInt.directParameter(
          ElaborationIntegerParameter("SYMBOLIC_WIDTH", 18, 17, 19),
          sourceLocation = None
        )
        val a = in UInt (16 bits)
        val b, c = in UInt (18 bits)
        val total = out UInt (18 bits)
        val resized = a.resize(symbolicWidth)
        val resize = resized.head.asInstanceOf[DataAssignmentStatement].source
          .asInstanceOf[ResizeUInt]
        assert(ParameterizedWidth.resizeExpressionOf(resize).exists(_.parameters.nonEmpty))

        // Model the point after a publisher has consumed its capture marker:
        // the exact Resize identity must still fail closed on retained width.
        resized.removeTag(ParameterizedWidth.TypedResizeCaptureTag)
        total := ((resized + b) + c)
      }
    }

    assert(wrapperAssignments(verilog).nonEmpty)
  }

  test("slice operands retain emitter-created boundaries") {
    val verilog = generate("SliceBoundary", enabled = true) {
      new Component {
        setDefinitionName("SliceBoundary")
        val a = in UInt (20 bits)
        val b, c = in UInt (18 bits)
        val total = out UInt (18 bits)
        total := ((a(17 downto 0) + b) + c)
      }
    }

    assert(wrapperAssignments(verilog).nonEmpty)
  }

  test("annotated leaf declarations remain while redundant expression wrappers inline") {
    val verilog = generate("AnnotatedBoundary", enabled = true) {
      new Component {
        setDefinitionName("AnnotatedBoundary")
        val a, b, c = in UInt (18 bits)
        a.addTag(PreserveWitness)
        val total = out UInt (18 bits)
        total := ((a + b) + c)
      }
    }

    assert(verilog.contains("input  wire [17:0]   a"))
    assert(wrapperAssignments(verilog).isEmpty)
  }

  test("annotated expression wrapper nodes fail the policy closed") {
    val add = new TaggedUnsignedAdd
    add.addTag(PreserveWitness)
    assert(!VerilogEmitterExpressionInlining.isUnannotated(add))
    assert(VerilogEmitterExpressionInlining.isUnannotated(new Operator.UInt.Add))
  }

  test("a shared expression node retains one emitter-created carrier") {
    val verilog = generate("SharedExpressionBoundary", enabled = true) {
      new Component {
        setDefinitionName("SharedExpressionBoundary")
        val a, b, c, d = in UInt (18 bits)
        val first, second = out UInt (18 bits)

        val sharedCarrier = a + b
        val shared = sharedCarrier.head.asInstanceOf[DataAssignmentStatement].source
          .asInstanceOf[Operator.UInt.Add]
        def root(right: UInt): Operator.UInt.Add = {
          val value = new Operator.UInt.Add
          value.left = shared
          value.right = right
          value
        }
        DslScopeStack.get.append(DataAssignmentStatement(first, root(c)))
        DslScopeStack.get.append(DataAssignmentStatement(second, root(d)))
      }
    }

    val wrappers = wrapperAssignments(verilog)
    assert(wrappers.size == 1)
    assert(verilog.contains("assign first = ("))
    assert(verilog.contains("assign second = ("))
  }

  test("an annotated target retains emitter-created boundaries") {
    val verilog = generate("AnnotatedTargetBoundary", enabled = true) {
      new Component {
        setDefinitionName("AnnotatedTargetBoundary")
        val a, b, c = in UInt (18 bits)
        val total = out UInt (18 bits)
        total.addTag(PreserveWitness)
        total := ((a + b) + c)
      }
    }

    assert(wrapperAssignments(verilog).nonEmpty)
  }

  test("partial assignments retain emitter-created boundaries") {
    val verilog = generate("PartialBoundary", enabled = true) {
      new Component {
        setDefinitionName("PartialBoundary")
        val a, b, c = in UInt (18 bits)
        val total = out UInt (20 bits)
        total := 0
        total(17 downto 0) := ((a + b) + c)
      }
    }

    assert(wrapperAssignments(verilog).nonEmpty)
  }

  test("procedural assignments retain emitter-created boundaries") {
    val verilog = generate("ProceduralBoundary", enabled = true) {
      new Component {
        setDefinitionName("ProceduralBoundary")
        val select = in Bool()
        val a, b, c = in UInt (18 bits)
        val total = out UInt (18 bits)
        total := 0
        when(select) {
          total := ((a + b) + c)
        }
      }
    }

    assert(wrapperAssignments(verilog).nonEmpty)
  }

  test("initial assignments retain emitter-created boundaries") {
    val verilog = generate(
      "InitialBoundary",
      enabled = true,
      configureBase = config => config.phasesInserters += { phases =>
        phases --= phases.filter(_.isInstanceOf[PhaseCheck_noLatchNoOverride])
      }
    ) {
      new Component {
        setDefinitionName("InitialBoundary")
        val a, b, c = in UInt (18 bits)
        val total = out UInt (18 bits)
        total.initialFrom((a + b) + c)
      }
    }

    assert(wrapperAssignments(verilog).nonEmpty)
    assert(verilog.contains("initial begin"))
  }

  test("multiply-driven targets retain emitter-created boundaries") {
    val verilog = generate("MultipleDriverBoundary", enabled = true) {
      new Component {
        setDefinitionName("MultipleDriverBoundary")
        val select = in Bool()
        val a, b, c, fallback = in UInt (18 bits)
        val total = out UInt (18 bits)
        total := ((a + b) + c)
        when(select) {
          total := fallback
        }
      }
    }

    assert(wrapperAssignments(verilog).nonEmpty)
  }

  test("literal and widening resize operands inline inside nested comparisons") {
    val verilog = generate("NestedComparisonInline", enabled = true) {
      new Component {
        setDefinitionName("NestedComparisonInline")
        val a = in UInt (16 bits)
        val limit = in UInt (18 bits)
        val legal = out Bool()
        legal := (a >= 1) && (a <= 1920) && (a.resize(18) <= limit)
      }
    }

    assert(wrapperAssignments(verilog).isEmpty, verilog)
    assert(verilog.contains("{2'd0, a}"), verilog)
    assert(verilog.contains("16'h0001"), verilog)
  }

  test("same-width subtraction inlines into equality and ternary arms") {
    val verilog = generate("NestedSubtractionInline", enabled = true) {
      new Component {
        setDefinitionName("NestedSubtractionInline")
        val a, b, lane = in UInt (13 bits)
        val select = in Bool()
        val last = out Bool()
        val chosen = out UInt (13 bits)
        last := lane === (a - 1)
        chosen := Mux(select, a - b, a + b)
      }
    }

    assert(wrapperAssignments(verilog).isEmpty, verilog)
    assert(verilog.contains("assign last = (lane == (a - 13'h0001));"), verilog)
    assert(verilog.contains("assign chosen = (select_1 ? (a - b) : (a + b));"), verilog)
  }

  test("conditional register update preserves its final select carrier and state") {
    val verilog = generate("RegisterExpressionInline", enabled = true) {
      new Component {
        setDefinitionName("RegisterExpressionInline")
        val load = in Bool()
        val a, b, c = in UInt (16 bits)
        val value = out UInt (12 bits)
        val state = Reg(UInt(12 bits)) init(0)
        when(load) {
          state := ((a.resize(18) + b.resize(18)) + c.resize(18)).resize(12)
        }
        value := state
      }
    }

    val wrappers = wrapperAssignments(verilog)
    assert(wrappers.size == 1, verilog)
    assert(wrappers.head.contains("(({2'd0, a} + {2'd0, b}) + {2'd0, c})"), verilog)
    assert(verilog.contains("reg        [11:0]   state;"), verilog)
    assert(verilog.contains("always @(posedge clk or posedge reset)"), verilog)
    assert(verilog.contains("if(load) begin"), verilog)
    assert(verilog.contains("[11:0];"), verilog)
  }

  test("a subtraction used as a procedural condition keeps the original comparison width") {
    val verilog = generate("ConditionalExpressionInline", enabled = true) {
      new Component {
        setDefinitionName("ConditionalExpressionInline")
        val total, lane = in UInt (13 bits)
        val pulse = out Bool()
        val state = Reg(Bool()) init(False)
        when(lane === (total - 1)) {
          state := !state
        }
        pulse := state
      }
    }

    assert(wrapperAssignments(verilog).isEmpty, verilog)
    assert(verilog.contains("(lane == (total - 13'h0001))"), verilog)
    assert(verilog.contains("always @(posedge clk or posedge reset)"), verilog)
  }

  test("an exact expression identity may inline only when its receiver width is proven") {
    var narrow: UInt = null
    var wide: UInt = null
    var result: Bool = null
    val verilog = generate(
      "WidenedReceiverFence",
      enabled = true,
      configureBase = config => config.phasesInserters += { phases =>
        phases.insert(phases.indexWhere(_.isInstanceOf[PhaseVerilog]), new PhaseNetlist {
          override def impl(pc: PhaseContext): Unit = {
            // Model native post-normalization writeback. The equal-width
            // Resize represents the removed 13-bit assignment boundary.
            val subtraction = new Operator.UInt.Sub
            subtraction.left = narrow
            subtraction.right = UIntLiteral(BigInt(1), 13)
            subtraction.inferredWidth = 13
            val fence = new ResizeUInt
            fence.size = 13
            fence.input = subtraction
            val comparison = new Operator.UInt.Equal
            comparison.left = wide
            comparison.right = fence
            val assignment = result.head.asInstanceOf[DataAssignmentStatement]
            assignment.source = comparison

            val enabled = VerilogEmitterExpressionInlining.configure(pc.config, enabled = true)
            val rejected = VerilogEmitterExpressionInlining.redundantWrappers(pc.topLevel, enabled)
            assert(!rejected.containsKey(fence))
            assert(!rejected.containsKey(subtraction))

            comparison.left = narrow
            val admitted = VerilogEmitterExpressionInlining.redundantWrappers(pc.topLevel, enabled)
            assert(admitted.containsKey(fence))
            assert(admitted.containsKey(subtraction))
            comparison.left = wide
          }
        })
      }
    ) {
      new Component {
        setDefinitionName("WidenedReceiverFence")
        narrow = in UInt (13 bits)
        wide = in UInt (18 bits)
        result = out Bool()
        narrow.setName("narrow")
        wide.setName("wide")
        result.setName("result")
        result := narrow.resize(18) === wide
      }
    }

    assert(wrapperAssignments(verilog).size == 2, verilog)
    assert(verilog.contains("- 13'h0001"), verilog)
  }

  test("an unsupported Boolean sibling does not veto independently sized comparison operands") {
    val verilog = generate("IndependentBooleanContexts", enabled = true) {
      new Component {
        setDefinitionName("IndependentBooleanContexts")
        val a, b = in UInt (13 bits)
        val wide = in UInt (18 bits)
        val signed = in SInt (13 bits)
        val first, second = out Bool()
        first := ((signed + 1) < 0) && (a === (b - 1))
        second := ((a === (b + 1)) && ((signed - 1) > 0)) || (a.resize(18) <= wide)
      }
    }

    val wrappers = wrapperAssignments(verilog)
    assert(wrappers.nonEmpty, verilog)
    assert(!wrappers.exists(line => line.contains("b -") || line.contains("b +")), verilog)
    assert(verilog.contains("(a == (b - 13'h0001))"), verilog)
    assert(verilog.contains("(a == (b + 13'h0001))"), verilog)
    assert(verilog.contains("({5'd0, a} <= wide)"), verilog)
  }

  test("static disjoint bit receivers inline pure comparison RHS expressions") {
    val verilog = generate("SelectedComparisonContexts", enabled = true) {
      new Component {
        setDefinitionName("SelectedComparisonContexts")
        val lanes = in Vec(UInt(13 bits), 4)
        val active = in UInt (16 bits)
        val running = in Bool()
        val flags = out Bits (4 bits)
        for (index <- 0 until 4)
          flags(index) := running && (lanes(index).resize(16) === (active - 1))
      }
    }

    assert(wrapperAssignments(verilog).isEmpty, verilog)
    for (index <- 0 until 4)
      assert(verilog.contains(s"flags[$index] = (running && ({3'd0, lanes_$index} == (active - 16'h0001)));"), verilog)
  }

  test("static disjoint ranges use each selected receiver width") {
    val verilog = generate("SelectedArithmeticContexts", enabled = true) {
      new Component {
        setDefinitionName("SelectedArithmeticContexts")
        val a, b, c = in UInt (18 bits)
        val packedValue = out UInt (36 bits)
        packedValue(17 downto 0) := (a + b) + c
        packedValue(35 downto 18) := (a - b) - c
      }
    }

    assert(wrapperAssignments(verilog).isEmpty, verilog)
    assert(verilog.contains("packedValue[17 : 0] = ((a + b) + c);"), verilog)
    assert(verilog.contains("packedValue[35 : 18] = ((a - b) - c);"), verilog)
  }

  test("depth cutting does not recreate eligible leaf literal carriers") {
    def fixture: Component = new Component {
      setDefinitionName("LongBooleanLiteralContexts")
      val a, b, syncA, syncB = in UInt (16 bits)
      val totalA, totalB = in UInt (18 bits)
      val aligned = in Bool()
      val legal = out Bool()
      legal := (a >= 1) && (a <= 1920) && (b >= 1) && (b <= 1080) &&
        (syncA =/= 0) && (syncB =/= 0) && (totalA <= 4096) &&
        (totalB <= 2048) && aligned
    }
    val enabled = generate("LongBooleanLiteralContexts", enabled = true)(fixture)
    val disabled = generate("LongBooleanLiteralContexts", enabled = false)(fixture)

    assert(wrapperAssignments(enabled).isEmpty, enabled)
    assert(wrapperAssignments(disabled).size == 2, disabled)
    assert(disabled.contains("assign _zz_legal = 16'h0001;"), disabled)
    assert(enabled.contains("(16'h0001 <= a)"), enabled)
  }

}
