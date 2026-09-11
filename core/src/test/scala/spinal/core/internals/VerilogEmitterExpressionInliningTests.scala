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

  test("mixed-width intermediate addition retains its explicit resize boundary") {
    val verilog = generate("MixedWidthBoundary", enabled = true) {
      new Component {
        setDefinitionName("MixedWidthBoundary")
        val a, b = in UInt (16 bits)
        val c = in UInt (18 bits)
        val total = out UInt (18 bits)
        total := ((a + b).resize(18) + c)
      }
    }

    assert(wrapperAssignments(verilog).nonEmpty)
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

}
