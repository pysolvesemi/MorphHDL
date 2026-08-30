package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.annotation.tailrec
import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.{MorphSingleSourceVerilogReport, MorphVerilog, MorphVerilogException}
import morphhdl.frontend.HdlInt

class ParameterizedVerilogTests extends AnyFunSuite {
  private val width =
    ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = 64)

  test("external Verilog retains a direct UInt width parameter and canonical port order") {
    withTemporaryDirectory { directory =>
      val report = generateParameterized(directory, reversePorts = true)
      val verilog = read(directory.resolve("DirectWidthWire.v"))
      val module = verilog.substring(verilog.indexOf("module ")).trim + "\n"

      assert(
        module ==
          """module DirectWidthWire #(
            |  parameter integer WIDTH = 8
            |) (
            |  input  wire [WIDTH-1:0] din,
            |  output wire [WIDTH-1:0] dout
            |);
            |
            |  assign dout = din;
            |
            |endmodule
            |""".stripMargin
      )
      assert(report.parameters.map(_.name) == Vector("WIDTH"))

      val metadataDirectory = directory.resolve("metadata")
      Files.createDirectories(metadataDirectory)
      val metadata = SpinalVerilog(concreteConfig(metadataDirectory)) {
        directWidthComponent(reversePorts = true, width)
      }
      assert(ParameterizedWidth.parametersOf(metadata.toplevel) == Vector(width))
    }
  }

  test("legacy Verilog ignores retained width metadata unless explicitly enabled") {
    withTemporaryDirectory { directory =>
      SpinalVerilog(concreteConfig(directory)) {
        directWidthComponent(reversePorts = false, width)
      }
      val verilog = read(directory.resolve("DirectWidthWire.v"))

      assert(!verilog.contains("parameter integer WIDTH"))
      assert(verilog.contains("input  wire [7:0]    din"))
      assert(verilog.contains("output wire [7:0]    dout"))
    }
  }

  test("parameterized mode rejects a concrete-only bit-count bridge") {
    val failure = interceptParameterized() { () =>
      new Component {
        setDefinitionName("ConcreteOnlyWidthWire")
        private val concrete = ParameterizedBitCount(8, parameter = None)
        val din = in(ParameterizedWidth.UInt(concrete))
        val dout = out(ParameterizedWidth.UInt(concrete))
        dout := din
      }
    }

    assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-UNTAGGED-PORT")
  }

  test("rejects a parameter whose full domain exceeds the configured width limit") {
    val tooWide = width.copy(maximum = 65)
    val failure = interceptParameterized(bitVectorWidthMax = 64) { () =>
      directWidthComponent(reversePorts = false, tooWide)
    }

    assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-DOMAIN-INVALID")
    assert(failure.detail.contains("bitVectorWidthMax=64"))
  }

  test("rejects a same-module parameter and port identifier collision") {
    val collision = width.copy(name = "din")
    val failure = interceptParameterized() { () =>
      directWidthComponent(reversePorts = false, collision)
    }

    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-PORT-NAME-COLLISION"
    )
  }

  test("rejects an IEEE 1364 reserved parameter identifier") {
    val reserved = width.copy(name = "wire")
    val failure = interceptParameterized() { () =>
      directWidthComponent(reversePorts = false, reserved)
    }

    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-NAME-RESERVED"
    )
  }

  test("rejects direct assignments across distinct symbolic width schemas") {
    val failure = interceptParameterized() { () =>
      new Component {
        setDefinitionName("MismatchedWidthWire")
        val din = in(ParameterizedWidth.UInt(ParameterizedBitCount(8, width)))
        val dout = out(
          ParameterizedWidth.UInt(
            ParameterizedBitCount(
              8,
              ElaborationIntegerParameter("OTHER_WIDTH", 8, 1, 64)
            )
          )
        )
        dout := din
      }
    }

    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
    )
  }

  test("rejects direct assignments across independently sourced same-name typed widths") {
    val first = HdlInt.param("WIDTH", default = 8, min = 1, max = 64).asElabInt
    val second = HdlInt.param("WIDTH", default = 8, min = 1, max = 64).asElabInt
    val failure = interceptParameterized() { () =>
      new Component {
        setDefinitionName("IndependentSameNameWidthWire")
        val din = in(Bits(first bits))
        val dout = out(Bits(second bits))
        dout := din
      }
    }

    assert(
      failure.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED"
    )
    assert(failure.detail.contains("WIDTH"))
  }

  test("rejects independently sourced same-name typed widths before assignment analysis") {
    val first = HdlInt.param("WIDTH", default = 8, min = 1, max = 64).asElabInt
    val second = HdlInt.param("WIDTH", default = 8, min = 1, max = 64).asElabInt
    val failure = interceptParameterized() { () =>
      new Component {
        setDefinitionName("IndependentParallelSameNameWidths")
        val usedInput = in(Bits(first bits))
        val unrelatedInput = in(Bits(second bits))
        val output = out(Bits(first bits))
        output := usedInput
      }
    }

    assert(
      failure.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED"
    )
    assert(failure.detail.contains("WIDTH"))
  }

  test("one exact typed width root emits one public parameter") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
      MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        new Component {
          setDefinitionName("SharedTypedWidthWire")
          val din = in(Bits(width.asElabInt bits))
          val dout = out(Bits(width.asElabInt bits))
          dout := din
        }
      }

      val verilog = read(directory.resolve("SharedTypedWidthWire.v"))
      val declaration = "parameter\\s+integer\\s+WIDTH\\s*=\\s*8".r
      assert(declaration.findAllMatchIn(verilog).size == 1)
      assert(verilog.contains("[WIDTH-1:0]"))
    }
  }

  test("root inventory completes rootless declarations and rejects an independent twin") {
    val shared = ElaborationIntegerParameter("WIDTH", 8, 1, 64)
    val first = rootlessDirectExpression(shared)
    val copied = rootlessDirectExpression(shared)
    ElabInt.validateParameterRootInventory(
      "shared inventory test",
      Vector(first, copied)
    )

    val independent = rootlessDirectExpression(
      ElaborationIntegerParameter("WIDTH", 8, 1, 64)
    )
    val error = intercept[ParameterizedVerilogException] {
      ElabInt.validateParameterRootInventory(
        "independent inventory test",
        Vector(first, independent)
      )
    }
    assert(error.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
  }

  test("component inventory rejects independent roots split across width and retained value") {
    val packedWidth =
      ElaborationIntegerParameter("SHARED", 8, 1, 64)
    val retainedValue =
      ElaborationIntegerParameter("SHARED", 8, 1, 64)

    val error = interceptParameterized() { () =>
      new Component {
        setDefinitionName("CrossRegistryIndependentRoots")

        val din = in(
          ParameterizedWidth.UInt(ParameterizedBitCount(8, packedWidth))
        )
        val dout = out(
          ParameterizedWidth.UInt(ParameterizedBitCount(8, packedWidth))
        )
        val retained = out(UInt(8 bits))

        dout := din
        retained := U(8, 8 bits)
        ExternalParameterizedValueRegistry.attach(
          retained,
          directExpression(retainedValue),
          witness = 8,
          sourceLocation = None
        )
      }
    }

    assert(error.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
    assert(error.detail.contains("SHARED"))
    assert(error.detail.contains("complete emitted parameter inventory"))
  }

  test("component inventory rejects independent roots from separate child actuals") {
    val firstActual =
      ElaborationIntegerParameter("ACTUAL", 8, 1, 64)
    val secondActual =
      ElaborationIntegerParameter("ACTUAL", 8, 1, 64)

    val error = interceptParameterized() { () =>
      new Component {
        setDefinitionName("SeparateChildActualIndependentRoots")

        final class Child extends Component {
          setDefinitionName("SeparateChildActualRootLeaf")
        }

        private val formal =
          ElaborationIntegerParameter("WIDTH", 8, 1, 64)
        val left = new Child
        left.setName("left")
        val right = new Child
        right.setName("right")

        private def binding(
            child: Child,
            actual: ElaborationIntegerParameter
        ): ExternalFormalParameterBinding =
          ExternalFormalParameterBinding(
            formal = formal,
            actual = directExpression(actual),
            declarationKey = "SeparateChildActualRootLeaf:WIDTH",
            ownerClassName = child.getClass.getName,
            sourceLocation = None
          )

        ExternalFormalParameterRegistry.retainComponent(
          left,
          binding(left, firstActual)
        )
        ExternalFormalParameterRegistry.retainComponent(
          right,
          binding(right, secondActual)
        )
      }
    }

    assert(error.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
    assert(error.detail.contains("ACTUAL"))
    assert(error.detail.contains("complete emitted parameter inventory"))
  }

  test("component inventory rejects independent sibling actual roots for scalar structural formals") {
    val firstActual =
      ElaborationIntegerParameter("ACTUAL_COUNT", 2, 1, 4)
    val secondActual =
      ElaborationIntegerParameter("ACTUAL_COUNT", 2, 1, 4)
    val formal =
      ElaborationIntegerParameter("COUNT", 2, 1, 4)

    val error = interceptParameterized() { () =>
      final class Marker extends Component {
        setDefinitionName("SiblingScalarFormalMarker")
        val din = in(Bool())
        val dout = out(Bool())
        dout := din
      }

      final class Child extends Component {
        setDefinitionName("SiblingScalarFormalLeaf")
        val din = in(Bool())
        val dout = out(Bool())
        dout := din

        private val body = ParameterizedStructure.captureBlock(this, None) {
          val marker = new Marker
          marker.setName("marker")
          marker.din := din
        }
        ParameterizedStructure.registerFor(
          this,
          "g_count",
          "count_index",
          directExpression(formal),
          body,
          None
        )
      }

      new Component {
        setDefinitionName("SiblingScalarFormalTop")
        val din = in(Bool())
        val dout = out(Bool())
        val left = new Child
        left.setName("left")
        val right = new Child
        right.setName("right")
        left.din := din
        right.din := din
        val leftResult = Bool()
        val rightResult = Bool()
        leftResult := left.dout
        rightResult := right.dout
        dout := leftResult ^ rightResult

        private def retainActual(
            child: Child,
            actual: ElaborationIntegerParameter
        ): Unit =
          ExternalFormalParameterRegistry.retainComponent(
            child,
            ExternalFormalParameterBinding(
              formal = formal,
              actual = directExpression(actual),
              declarationKey = "SiblingScalarFormalLeaf:COUNT",
              ownerClassName = child.getClass.getName,
              sourceLocation = None
            )
          )

        retainActual(left, firstActual)
        retainActual(right, secondActual)
      }
    }

    assert(error.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
    assert(error.detail.contains("ACTUAL_COUNT"))
    assert(error.detail.contains("complete emitted parameter inventory"))
  }

  test("fixed-width parent propagates a scalar child actual used only by structure") {
    withTemporaryDirectory { directory =>
      val actual =
        ElaborationIntegerParameter("ACTUAL_COUNT", 2, 1, 4)
      val formal =
        ElaborationIntegerParameter("COUNT", 2, 1, 4)

      final class Marker extends Component {
        setDefinitionName("ScalarStructuralFormalMarker")
        val din = in(Bool())
        val dout = out(Bool())
        dout := din
      }

      final class Child extends Component {
        setDefinitionName("ScalarStructuralFormalLeaf")
        val din = in(Bool())
        val dout = out(Bool())
        dout := din

        private val body = ParameterizedStructure.captureBlock(this, None) {
          val marker = new Marker
          marker.setName("marker")
          marker.din := din
        }
        ParameterizedStructure.registerFor(
          this,
          "g_count",
          "count_index",
          directExpression(formal),
          body,
          None
        )
      }

      final class Top extends Component {
        setDefinitionName("ScalarStructuralFormalTop")
        val din = in(Bool())
        val dout = out(Bool())
        val child = new Child
        child.setName("child")
        child.din := din
        dout := child.dout

        ExternalFormalParameterRegistry.retainComponent(
          child,
          ExternalFormalParameterBinding(
            formal = formal,
            actual = directExpression(actual),
            declarationKey = "ScalarStructuralFormalLeaf:COUNT",
            ownerClassName = child.getClass.getName,
            sourceLocation = None
          )
        )
      }

      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "scalar_structural_formal.v"
      val report = MorphVerilog(config)(new Top)
      val verilog = read(directory.resolve("scalar_structural_formal.v"))

      assert(report.parameters.map(_.name) == Vector("ACTUAL_COUNT"))
      assert(verilog.contains("module ScalarStructuralFormalLeaf #("))
      assert(verilog.contains("parameter integer COUNT = 2"))
      assert(verilog.contains("module ScalarStructuralFormalTop #("))
      assert(verilog.contains("parameter integer ACTUAL_COUNT = 2"))
      assert(verilog.contains(".COUNT(ACTUAL_COUNT)"))
    }
  }

  test("native memory inventories reject independent same-name geometry roots") {
    withTemporaryDirectory { directory =>
      val depth = directExpression(
        ElaborationIntegerParameter("SIZE", 8, 1, 64)
      )
      val elementWidth = directExpression(
        ElaborationIntegerParameter("SIZE", 8, 1, 64)
      )
      val report = SpinalVerilog(concreteConfig(directory)) {
        new Component {
          setDefinitionName("IndependentMemoryGeometryRoots")
          val address = in(UInt(3 bits))
          val value = out(Bits(8 bits))
          val memory = Mem(Bits(8 bits), wordCount = 8)
          memory.addTag(
            ParameterizedMemoryTag(
              ParameterizedMemoryMetadata(depth, elementWidth, None)
            )
          )
          value := memory.readAsync(address)
        }
      }

      val coreError = intercept[ParameterizedVerilogException] {
        ParameterizedMemory.parametersOf(report.toplevel)
      }
      assert(
        coreError.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED"
      )

      val externalError = intercept[ParameterizedVerilogException] {
        ExternalParameterizedMemoryRegistry.parametersOf(report.toplevel)
      }
      assert(
        externalError.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED"
      )
    }
  }

  test("parameter-only native Counter metadata reuses its declaration root") {
    withTemporaryDirectory { directory =>
      val parameter = ElaborationIntegerParameter("WIDTH", 8, 1, 64)
      var counter: spinal.lib.Counter = null
      SpinalVerilog(concreteConfig(directory)) {
        new Component {
          setDefinitionName("CounterDeclarationRoot")
          counter = spinal.lib.ExternalParameterizedCounterRegistry.create(
            ParameterizedBitCount(8, parameter)
          )
          val observed = out(UInt(8 bits))
          observed := counter.value
        }
      }

      val metadata = spinal.lib.ExternalParameterizedCounterRegistry
        .metadataOf(counter)
        .get
        .width
      val retained = ParameterizedWidth.expressionOf(counter.value).get
      assertSameRoots(metadata, retained)
    }
  }

  test("retained UInt inventory rejects independent same-name roots") {
    withTemporaryDirectory { directory =>
      val first = directExpression(
        ElaborationIntegerParameter("VALUE", 8, 1, 16)
      )
      val second = directExpression(
        ElaborationIntegerParameter("VALUE", 8, 1, 16)
      )
      val report = SpinalVerilog(concreteConfig(directory)) {
        new Component {
          setDefinitionName("IndependentRetainedValues")
          val firstValue = out(UInt(8 bits))
          val secondValue = out(UInt(8 bits))
          firstValue := U(8, 8 bits)
          secondValue := U(8, 8 bits)
          ExternalParameterizedValueRegistry.attach(
            firstValue,
            first,
            witness = 8,
            sourceLocation = None
          )
          ExternalParameterizedValueRegistry.attach(
            secondValue,
            second,
            witness = 8,
            sourceLocation = None
          )
        }
      }

      val error = intercept[ParameterizedVerilogException] {
        ExternalParameterizedValueRegistry.parametersOf(report.toplevel)
      }
      assert(error.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
    }
  }

  test("rejects conflicting declarations for the same retained parameter name") {
    val failure = interceptParameterized() { () =>
      new Component {
        setDefinitionName("ConflictingWidthWire")
        val din = in(
          ParameterizedWidth.UInt(
            ParameterizedBitCount(
              8,
              width,
              sourceLocation = Some("ConflictingWidthWire.scala:12")
            )
          )
        )
        val dout = out(
          ParameterizedWidth.UInt(
            ParameterizedBitCount(
              8,
              width.copy(maximum = 32),
              sourceLocation = Some("ConflictingWidthWire.scala:13")
            )
          )
        )
        dout := din
      }
    }

    assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT")
    assert(failure.sourceLocation.contains("ConflictingWidthWire.scala:12"))
    assert(failure.getMessage.contains("ConflictingWidthWire.scala:12"))
  }

  test("rejects an input-only tagged component outside the direct-wire slice") {
    val failure = interceptParameterized() { () =>
      new Component {
        setDefinitionName("InputOnlyWidth")
        val din = in(ParameterizedWidth.UInt(ParameterizedBitCount(8, width)))
      }
    }

    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-PORT-DIRECTIONS-UNSUPPORTED"
    )
  }

  private def directWidthComponent(
      reversePorts: Boolean,
      parameter: ElaborationIntegerParameter
  ): Component =
    new Component {
      setDefinitionName("DirectWidthWire")
      private val bitCount = ParameterizedBitCount(parameter.default.toInt, parameter)
      private val ports =
        if (reversePorts) {
          val output = out(ParameterizedWidth.UInt(bitCount)).setName("dout")
          val input = in(ParameterizedWidth.UInt(bitCount)).setName("din")
          (input, output)
        } else {
          val input = in(ParameterizedWidth.UInt(bitCount)).setName("din")
          val output = out(ParameterizedWidth.UInt(bitCount)).setName("dout")
          (input, output)
        }
      ports._2 := ports._1
    }

  private def generateParameterized(
      directory: Path,
      reversePorts: Boolean,
      parameter: ElaborationIntegerParameter = width,
      bitVectorWidthMax: Int = 4096
  ): MorphSingleSourceVerilogReport =
    MorphVerilog(
      SpinalConfig(
        targetDirectory = directory.toString,
        bitVectorWidthMax = bitVectorWidthMax
      )
    ) {
      directWidthComponent(reversePorts, parameter)
    }

  private def concreteConfig(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      headerWithRepoHash = false,
      withTimescale = false,
      printFilelist = false
    )

  private def directExpression(
      parameter: ElaborationIntegerParameter
  ): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = parameter.name,
      default = parameter.default,
      minimum = parameter.minimum,
      maximum = parameter.maximum,
      parameters = Vector(parameter),
      parameterRoots = Vector(parameter.declarationRoot)
    )

  private def rootlessDirectExpression(
      parameter: ElaborationIntegerParameter
  ): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = parameter.name,
      default = parameter.default,
      minimum = parameter.minimum,
      maximum = parameter.maximum,
      parameters = Vector(parameter)
    )

  private def assertSameRoots(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Unit = {
    assert(left.parameterRoots.nonEmpty)
    assert(left.parameterRoots.size == right.parameterRoots.size)
    assert(
      left.parameterRoots.forall(root =>
        right.parameterRoots.exists(_ eq root)
      )
    )
  }

  private def interceptParameterized(
      bitVectorWidthMax: Int = 4096
  )(factory: () => Component): ParameterizedVerilogException =
    withTemporaryDirectory { directory =>
      val error = intercept[MorphVerilogException] {
        MorphVerilog(
          SpinalConfig(
            targetDirectory = directory.toString,
            bitVectorWidthMax = bitVectorWidthMax
          )
        ) {
          factory()
        }
      }
      findParameterized(error).getOrElse {
        fail(s"Expected ParameterizedVerilogException, received ${error.failure}")
      }
    }

  @tailrec
  private def findParameterized(error: Throwable): Option[ParameterizedVerilogException] =
    if (error == null) None
    else
      error match {
        case value: ParameterizedVerilogException => Some(value)
        case _                                    => findParameterized(error.getCause)
      }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-parameterized-verilog-test-")
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
