package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

private object ParameterizedDataShapeTestFixture {
  final case class Payload(shapeWidth: ParameterizedBitCount) extends Bundle {
    val bits = Bits(shapeWidth)
    val uint = UInt(shapeWidth)
    val sint = SInt(shapeWidth)
  }
}

class ParameterizedDataShapeTests extends AnyFunSuite {
  import ParameterizedDataShapeTestFixture.Payload

  private val width =
    ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = 64)
  private val sourceLocation = Some("ParameterizedDataShapeTests.scala:WIDTH")
  private val bitCount = ParameterizedBitCount(8, width, sourceLocation)

  private final class MetadataComponent extends Component {
    setDefinitionName("ParameterizedShapeMetadata")

    val clk = in(Bool())
    val din = in(Bits(bitCount))
    val dout = out(Bits(bitCount))

    val directBits = Bits(bitCount)
    val directUInt = UInt(bitCount)
    val directSInt = SInt(bitCount)
    val clonedBits = cloneOf(directBits)

    private val uintTemplate = UInt(bitCount)
    private val uintHardType = HardType(uintTemplate)
    val hardUIntA = uintHardType()
    val hardUIntB = uintHardType()

    val payload = Payload(bitCount)
    val payloadClone = cloneOf(payload)
    val payloadVec = Vec(Payload(bitCount), 2)

    val ordinary = Bits(8 bits)
    val ordinaryClone = cloneOf(ordinary)

    private val registerClock = ClockDomain(clock = clk)
    val registerArea = new ClockingArea(registerClock) {
      val symbolicRegister = Reg(Bits(bitCount))
      symbolicRegister := din
    }
    dout := registerArea.symbolicRegister
  }

  test("Bits UInt and SInt retain one shared symbolic schema") {
    withTemporaryDirectory { directory =>
      val report = generateMetadata(directory)
      val leaves = Vector(
        report.toplevel.directBits,
        report.toplevel.directUInt,
        report.toplevel.directSInt
      )

      assert(leaves.map(_.getClass) == Vector(classOf[Bits], classOf[UInt], classOf[SInt]))
      assert(leaves.forall(ParameterizedWidth.parameterOf(_).contains(width)))
      assert(leaves.forall(ParameterizedWidth.sourceLocationOf(_) == sourceLocation))
      assert(leaves.forall(_.asInstanceOf[BitVector].getWidth == 8))
    }
  }

  test("cloneOf and repeated HardType construction preserve independent metadata") {
    withTemporaryDirectory { directory =>
      val top = generateMetadata(directory).toplevel

      assert(top.clonedBits ne top.directBits)
      assert(top.hardUIntA ne top.hardUIntB)
      assert(ParameterizedWidth.parameterOf(top.clonedBits).contains(width))
      assert(ParameterizedWidth.parameterOf(top.hardUIntA).contains(width))
      assert(ParameterizedWidth.parameterOf(top.hardUIntB).contains(width))
      assert(ParameterizedWidth.sourceLocationOf(top.clonedBits) == sourceLocation)
      assert(ParameterizedWidth.sourceLocationOf(top.hardUIntA) == sourceLocation)
      assert(ParameterizedWidth.sourceLocationOf(top.hardUIntB) == sourceLocation)

      val directTag = top.directBits.getTag(classOf[ParameterizedWidthTag]).get
      val cloneTag = top.clonedBits.getTag(classOf[ParameterizedWidthTag]).get
      val hardTagA = top.hardUIntA.getTag(classOf[ParameterizedWidthTag]).get
      val hardTagB = top.hardUIntB.getTag(classOf[ParameterizedWidthTag]).get
      assert(directTag ne cloneTag)
      assert(hardTagA ne hardTagB)
    }
  }

  test("Bundle Vec and Reg cloning preserve symbolic leaf shapes") {
    withTemporaryDirectory { directory =>
      val top = generateMetadata(directory).toplevel

      assert(ParameterizedWidth.leavesOf(top.payload).size == 3)
      assert(ParameterizedWidth.leavesOf(top.payloadClone).size == 3)
      assert(ParameterizedWidth.leavesOf(top.payloadVec).size == 6)
      assert(
        ParameterizedWidth.leavesOf(top.registerArea.symbolicRegister).map(_.getClass) ==
          Vector(classOf[Bits])
      )
      assert(
        (
          ParameterizedWidth.leavesOf(top.payload) ++
            ParameterizedWidth.leavesOf(top.payloadClone) ++
            ParameterizedWidth.leavesOf(top.payloadVec) ++
            ParameterizedWidth.leavesOf(top.registerArea.symbolicRegister)
        ).forall(ParameterizedWidth.parameterOf(_).contains(width))
      )
    }
  }

  test("ordinary concrete factories and clones remain untagged") {
    withTemporaryDirectory { directory =>
      val top = generateMetadata(directory).toplevel

      assert(ParameterizedWidth.parameterOf(top.ordinary).isEmpty)
      assert(ParameterizedWidth.parameterOf(top.ordinaryClone).isEmpty)
      assert(ParameterizedWidth.sourceLocationOf(top.ordinary).isEmpty)
      assert(ParameterizedWidth.sourceLocationOf(top.ordinaryClone).isEmpty)
    }
  }

  test("parametersOf discovers tagged internal signals and registers") {
    withTemporaryDirectory { directory =>
      val internalWidth =
        ElaborationIntegerParameter("INTERNAL_WIDTH", 5, minimum = 1, maximum = 16)
      var discovered = Vector.empty[ElaborationIntegerParameter]

      SpinalVerilog(concreteConfig(directory)) {
        new Component {
          setDefinitionName("InternalShapeDiscovery")
          val clk = in(Bool())
          val pass = in(Bool())
          val passed = out(Bool())
          passed := pass

          val internal = Bits(ParameterizedBitCount(5, internalWidth)).dontSimplifyIt()
          private val internalClockDomain = ClockDomain(clock = clk)
          val area = new ClockingArea(internalClockDomain) {
            val state = Reg(Bits(ParameterizedBitCount(5, internalWidth))).dontSimplifyIt()
            state := internal
          }
          discovered = ParameterizedWidth.parametersOf(this)
        }
      }

      assert(discovered == Vector(internalWidth))
    }
  }

  test("parametersOf rejects a conflicting schema found only on internals") {
    withTemporaryDirectory { directory =>
      val failure = intercept[ParameterizedVerilogException] {
        SpinalVerilog(concreteConfig(directory)) {
          new Component {
            setDefinitionName("InternalShapeConflict")
            val pass = in(Bool())
            val passed = out(Bool())
            passed := pass

            val first = Bits(
              ParameterizedBitCount(8, width, Some("InternalShapeConflict.scala:10"))
            )
            val second = Bits(
              ParameterizedBitCount(
                8,
                width.copy(maximum = 32),
                Some("InternalShapeConflict.scala:11")
              )
            )
            ParameterizedWidth.parametersOf(this)
          }
        }
      }

      assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT")
      assert(failure.detail.contains("INTERNAL".toLowerCase) || failure.detail.contains("WIDTH"))
    }
  }

  test("parameterized emission accepts concrete internals beside symbolic shapes") {
    withTemporaryDirectory { directory =>
      SpinalVerilog(parameterizedConfig(directory)) {
        new Component {
          setDefinitionName("ConcreteInternalShape")
          val din = in(Bits(bitCount))
          val dout = out(Bits(bitCount))
          val concreteInternal = Bits(8 bits).setName("concrete_internal").dontSimplifyIt()
          concreteInternal := B(0, 8 bits)
          dout := din
        }
      }

      val verilog = read(directory.resolve("ConcreteInternalShape.v"))
      assert(verilog.contains("parameter integer WIDTH = 8"))
      assert(hasDeclarationWidth(verilog, "din", "[WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "dout", "[WIDTH-1:0]"))
      assert(verilog.contains("[7:0]"))
      assert(verilog.contains("concrete_internal"))
    }
  }

  test("parameterized emission rejects a concrete witness mismatch") {
    val failure = interceptParameterized("MismatchedShapeWitness") { () =>
      new Component {
        setDefinitionName("MismatchedShapeWitness")
        private val wrongWitness = ParameterizedBitCount(7, width, sourceLocation)
        val din = in(Bits(wrongWitness))
        val dout = out(Bits(wrongWitness))
        dout := din
      }
    }

    assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-WITNESS-MISMATCH")
  }

  test("parameter names cannot collide with internal flattened declarations") {
    val failure = interceptParameterized("InternalParameterCollision") { () =>
      new Component {
        setDefinitionName("InternalParameterCollision")
        val din = in(Bits(bitCount))
        val dout = out(Bits(bitCount))
        val internal = Bits(bitCount).setName("WIDTH").dontSimplifyIt()
        internal := din
        dout := internal
      }
    }

    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-SIGNAL-NAME-COLLISION"
    )
  }

  test("parameterized emission rejects initialized or reset register paths") {
    val failure = interceptParameterized("ResetSymbolicRegister") { () =>
      new Component {
        setDefinitionName("ResetSymbolicRegister")
        val clk = in(Bool())
        val reset = in(Bool())
        val din = in(Bits(bitCount))
        val dout = out(Bits(bitCount))
        private val resetClockDomain = ClockDomain(clock = clk, reset = reset)
        val area = new ClockingArea(resetClockDomain) {
          val state = Reg(Bits(bitCount))
          state.init(B(0, 8 bits))
          state := din
          dout := state
        }
      }
    }

    assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-REGISTER-INIT-UNSUPPORTED")
  }

  test("parameterized emission rejects conditional assignments") {
    val failure = interceptParameterized("ConditionalShapeAssignment") { () =>
      new Component {
        setDefinitionName("ConditionalShapeAssignment")
        val clk = in(Bool())
        val select = in(Bool())
        val din = in(Bits(bitCount))
        val dout = out(Bits(bitCount))
        private val registerClockDomain = ClockDomain(clock = clk)
        val area = new ClockingArea(registerClockDomain) {
          val state = Reg(Bits(bitCount))
          when(select) {
            state := din
          }
          dout := state
        }
      }
    }

    assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-STATEMENT-UNSUPPORTED")
  }

  test("parameterized emission lowers partial assignments and expressions") {
    withTemporaryDirectory { directory =>
      SpinalVerilog(parameterizedConfig(directory)) {
        new Component {
          setDefinitionName("PartialShapeAssignment")
          val din = in(Bits(bitCount))
          val alternate = in(Bits(bitCount))
          val dout = out(Bits(bitCount))
          dout := din
          dout(0) := alternate(0)
        }
      }
      val partial = read(directory.resolve("PartialShapeAssignment.v"))
      assert(partial.contains("parameter integer WIDTH = 8"))
      assert(partial.contains("dout[0]"))
      assert(partial.contains("alternate[0]"))
    }

    withTemporaryDirectory { directory =>
      SpinalVerilog(parameterizedConfig(directory)) {
        new Component {
          setDefinitionName("ExpressionShapeAssignment")
          val din = in(Bits(bitCount))
          val alternate = in(Bits(bitCount))
          val dout = out(Bits(bitCount))
          dout := din ^ alternate
        }
      }
      val expression = read(directory.resolve("ExpressionShapeAssignment.v"))
      assert(expression.contains("parameter integer WIDTH = 8"))
      assert(hasDeclarationWidth(expression, "dout", "[WIDTH-1:0]"))
      assert(expression.contains("din ^ alternate"))
    }
  }

  test("inherited validation rejects missing and overlapping output drivers") {
    val missing = withTemporaryDirectory { directory =>
      intercept[SpinalExit] {
        SpinalVerilog(concreteConfig(directory).copy(parameterizedVerilog = true)) {
          new Component {
            setDefinitionName("MissingShapeDriver")
            val din = in(Bits(bitCount))
            val dout = out(Bits(bitCount))
            val internal = Bits(bitCount).dontSimplifyIt()
            internal := din
          }
        }
      }
    }
    assert(missing.getMessage.contains("NO DRIVER ON"))

    val multiple = withTemporaryDirectory { directory =>
      intercept[SpinalExit] {
        SpinalVerilog(concreteConfig(directory).copy(parameterizedVerilog = true)) {
          new Component {
            setDefinitionName("MultipleShapeDrivers")
            val first = in(Bits(bitCount))
            val second = in(Bits(bitCount))
            val dout = out(Bits(bitCount))
            dout := first
            dout := second
          }
        }
      }
    }
    assert(multiple.getMessage.contains("ASSIGNMENT OVERLAP"))
  }

  test("parameterized emission rejects reset and falling-edge register domains") {
    val resetDomain = interceptParameterized("ResetDomainSymbolicRegister") { () =>
      new Component {
        setDefinitionName("ResetDomainSymbolicRegister")
        val clk = in(Bool())
        val reset = in(Bool())
        val din = in(Bits(bitCount))
        val dout = out(Bits(bitCount))
        private val registerClockDomain = ClockDomain(clock = clk, reset = reset)
        val area = new ClockingArea(registerClockDomain) {
          val state = Reg(Bits(bitCount))
          state := din
          dout := state
        }
      }
    }
    assert(resetDomain.code == "SPINAL-PARAMETERIZED-VERILOG-REGISTER-UNSUPPORTED")

    val fallingEdge = interceptParameterized("FallingEdgeSymbolicRegister") { () =>
      new Component {
        setDefinitionName("FallingEdgeSymbolicRegister")
        val clk = in(Bool())
        val din = in(Bits(bitCount))
        val dout = out(Bits(bitCount))
        private val registerClockDomain = ClockDomain(
          clock = clk,
          config = ClockDomainConfig(clockEdge = FALLING)
        )
        val area = new ClockingArea(registerClockDomain) {
          val state = Reg(Bits(bitCount))
          state := din
          dout := state
        }
      }
    }
    assert(fallingEdge.code == "SPINAL-PARAMETERIZED-VERILOG-REGISTER-UNSUPPORTED")
  }

  private def hasDeclarationWidth(
      verilog: String,
      name: String,
      range: String
  ): Boolean = {
    val pattern =
      (java.util.regex.Pattern.quote(range) + "\\s+" +
        java.util.regex.Pattern.quote(name) + "(?=\\s*(?:[,;]|\\)))").r
    pattern.findFirstIn(verilog).nonEmpty
  }

  private def generateMetadata(directory: Path): SpinalReport[MetadataComponent] =
    SpinalVerilog(concreteConfig(directory)) {
      new MetadataComponent
    }

  private def concreteConfig(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      headerWithRepoHash = false,
      withTimescale = false,
      printFilelist = false
    )

  private def parameterizedConfig(directory: Path): SpinalConfig =
    concreteConfig(directory).copy(parameterizedVerilog = true)

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def interceptParameterized(
      name: String
  )(factory: () => Component): ParameterizedVerilogException =
    withTemporaryDirectory { directory =>
      intercept[ParameterizedVerilogException] {
        SpinalVerilog(
          concreteConfig(directory).copy(parameterizedVerilog = true)
        ) {
          factory()
        }
      }
    }

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-parameterized-shape-test-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
