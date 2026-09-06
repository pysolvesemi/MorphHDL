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

  test("retained integer helper audit distinguishes named associations from expression calls") {
    val association =
      "    .morphhdl_synthetic_bus (morphhdl_synthetic_actual),"
    assert(
      ExternalParameterizedVerilogNativeFallback.lowerRetainedIntegerHelpers(
        association,
        "AssociationGrammar"
      ) == association
    )
    Vector(
      "    .morphhdl_address_width(morphhdl_synthetic_actual),",
      "    .morphhdl_ceil_log2(morphhdl_synthetic_actual),",
      "    . morphhdl_address_width(morphhdl_synthetic_actual),",
      "    . morphhdl_ceil_log2(morphhdl_synthetic_actual),"
    ).foreach { reviewedHelperAssociation =>
      assert(
        ExternalParameterizedVerilogNativeFallback.lowerRetainedIntegerHelpers(
          reviewedHelperAssociation,
          "AssociationGrammar"
        ) == reviewedHelperAssociation
      )
    }

    Vector(
      "assign observed = morphhdl_unsupported_integer_helper(source);" ->
        "morphhdl_unsupported_integer_helper(",
      "assign observed = child.morphhdl_unsupported_integer_helper(source);" ->
        "morphhdl_unsupported_integer_helper(",
      "assign observed = child\n  .morphhdl_unsupported_integer_helper(source);" ->
        "morphhdl_unsupported_integer_helper(",
      "assign observed = child\n  .morphhdl_address_width(source);" ->
        "morphhdl_address_width(",
      "assign observed = child\n  .morphhdl_ceil_log2(source);" ->
        "morphhdl_ceil_log2(",
      "assign observed = child.\n  morphhdl_address_width(source);" ->
        "morphhdl_address_width(",
      "assign observed = child.\n  morphhdl_ceil_log2(source);" ->
        "morphhdl_ceil_log2(",
      "assign observed = child.\n  morphhdl_unsupported_integer_helper(source);" ->
        "morphhdl_unsupported_integer_helper(",
      "assign observed = child // ,\n  .morphhdl_unsupported_integer_helper(source);" ->
        "morphhdl_unsupported_integer_helper(",
      "    .ordinary_port (morphhdl_unsupported_integer_helper(source))," ->
        "morphhdl_unsupported_integer_helper("
    ).foreach { case (body, helper) =>
      val error = intercept[ParameterizedVerilogException] {
        ExternalParameterizedVerilogNativeFallback.lowerRetainedIntegerHelpers(
          association + "\n  " + body,
          "AssociationGrammar"
        )
      }
      assert(
        error.code ==
          "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-UNSUPPORTED"
      )
      assert(error.detail.contains(helper))
    }

    Vector(
      "// morphhdl_unsupported_integer_helper(source)",
      "/* morphhdl_unsupported_integer_helper(source) */",
      "initial $display(\"morphhdl_unsupported_integer_helper(source)\");",
      "wire \\morphhdl_unsupported_integer_helper(source) ;"
    ).foreach { nonCodeOccurrence =>
      assert(
        ExternalParameterizedVerilogNativeFallback.lowerRetainedIntegerHelpers(
          nonCodeOccurrence,
          "AssociationGrammar"
        ) == nonCodeOccurrence
      )
    }
  }

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
        retained.assignFrom(UIntLiteral(BigInt(8), null, 8))
        ExternalParameterizedValueRegistry.attach(
          retained,
          exactDirectExpression(retainedValue),
          witness = 8,
          sourceLocation = None
        )
      }
    }

    assert(error.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
    assert(error.detail.contains("SHARED"))
    assert(error.detail.contains("complete emitted parameter inventory"))
  }

  test("retained value rewrite rejects a stale same-name emitted right-hand side") {
    withTemporaryDirectory { directory =>
      val binaryWidth = ElaborationIntegerParameter("BINARY_WIDTH", 4, 2, 8)
      val hexWidth = ElaborationIntegerParameter("HEX_WIDTH", 8, 6, 8)
      val report = SpinalVerilog(concreteConfig(directory)) {
        new Component {
          setDefinitionName("RetainedValueEmittedLineage")
          val observedBinary = out(UInt(4 bits)).setName("observed_binary")
          val observedHex = out(UInt(8 bits)).setName("observed_hex")
          val retainedBinary = ParameterizedWidth
            .UInt(ParameterizedBitCount(4, binaryWidth))
            .setName("retained_binary")
          val retainedHex = ParameterizedWidth
            .UInt(ParameterizedBitCount(8, hexWidth))
            .setName("retained_hex")
          retainedBinary.assignFrom(UIntLiteral(BigInt(3), null, 4))
          retainedHex.assignFrom(UIntLiteral(BigInt(42), null, 8))
          ExternalParameterizedValueRegistry.attach(
            retainedBinary,
            ElabInt.literal(3).expression,
            witness = 3,
            sourceLocation = None
          )
          ExternalParameterizedValueRegistry.attach(
            retainedHex,
            ElabInt.literal(42).expression,
            witness = 42,
            sourceLocation = None
          )
          observedBinary := retainedBinary
          observedHex := retainedHex
        }
      }
      val native = read(directory.resolve("RetainedValueEmittedLineage.v"))
      def emittedAssignment(name: String): scala.util.matching.Regex.Match = {
        val assignment =
          ("(?m)^(\\s*assign\\s+" + java.util.regex.Pattern.quote(name) +
            "\\s*=\\s*)(.*?)(;\\s*)$").r
        assignment.findFirstMatchIn(native).getOrElse {
          fail(s"retained value '$name' has no native witness assignment:\n$native")
        }
      }
      val emittedBinary = emittedAssignment("retained_binary")
      val emittedHex = emittedAssignment("retained_hex")
      assert(emittedBinary.group(2).trim == "4'b0011", native)
      assert(emittedHex.group(2).trim == "8'h2a", native)

      val rewritten = ExternalParameterizedVerilogNativeFallback
        .rewriteRetainedValueAssignments(report.toplevel, native)
      assert(rewritten.contains("assign retained_binary = (3);"), rewritten)
      assert(rewritten.contains("assign retained_hex = (42);"), rewritten)

      Vector(
        (emittedBinary, "4'b0100"),
        (emittedHex, "8'h2b")
      ).foreach { case (emitted, replacement) =>
        val stale =
          native.substring(0, emitted.start(2)) + replacement +
            native.substring(emitted.end(2))
        val error = intercept[ParameterizedVerilogException] {
          ExternalParameterizedVerilogNativeFallback
            .rewriteRetainedValueAssignments(report.toplevel, stale)
        }
        assert(
          error.code ==
            "SPINAL-PARAMETERIZED-VERILOG-VALUE-EMITTED-LINEAGE-MISMATCH"
        )
        assert(error.detail.contains("0 exact native witness edges"))
      }
    }
  }

  test("retained symbolic values preserve zero default witnesses until value publication") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "TypedZeroWitnessValue.v"
      MorphVerilog(config) {
        new Component {
          setDefinitionName("TypedZeroWitnessValue")
          val width = HdlInt.param("WIDTH", 1, 1, 8).asElabInt
          val input = in Bool()
          val echo = out Bool()
          echo := input
          val output, zero = out UInt(width.bits)
          val value = ElabValue.uintLike(width - 1, UInt(width.bits), "varying_value")
          output := value
          zero := 0
        }
      }
      val verilog = read(directory.resolve("TypedZeroWitnessValue.v"))
      val compact = verilog.replaceAll("\\s+", "")
      val valueAssignment = verilog.split("\n").find(_.matches("\\s*assign\\s+varying_value\\s*=.*"))
        .getOrElse(fail("retained typed value assignment was not published:\n" + verilog))
      assert(valueAssignment.replaceAll("\\s+", "").contains("WIDTH-1"), valueAssignment)
      assert(!valueAssignment.contains("1'b0"), valueAssignment)
      assert(compact.contains("assignzero={WIDTH{1'b0}};"), verilog)
    }
  }

  test("retained finite folds preserve zero anchors until fold publication") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "TypedZeroWitnessFold.v"
      MorphVerilog(config) {
        new Component {
          setDefinitionName("TypedZeroWitnessFold")
          val width = HdlInt.param("WIDTH", 1, 1, 8).asElabInt
          val source = in Bits(width.bits)
          val count = out UInt((width + 1).addressWidth.bits)
          val zero = out Bits(width.bits)
          count := ElabFiniteRange.countOne(source, width)(spinal.lib.CountOne(source))
          zero := 0
        }
      }
      val verilog = read(directory.resolve("TypedZeroWitnessFold.v"))
      val compact = verilog.replaceAll("\\s+", "")
      assert(compact.contains("for(morphhdl_finite_fold_index_1=0;"), verilog)
      assert(compact.contains("morphhdl_finite_fold_index_1<WIDTH;"), verilog)
      assert(compact.contains("assignzero={WIDTH{1'b0}};"), verilog)
      assert(!compact.contains("assignmorphhdl_finite_count_one_1="), verilog)
    }
  }

  test("retained zero widths are validated within their exact structural owner") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "TypedScopedZero.v"
      MorphVerilog(config) {
        new Component {
          setDefinitionName("TypedScopedZero")
          val width: ElabInt = HdlInt.param("WIDTH", 1, 1, 8).asElabInt
          val source = in Bool()
          val observed = out Bool()
          if (width > 1) {
            val scoped = UInt((width - 1).bits).setName("scoped_zero").dontSimplifyIt()
            scoped := 0
            observed := scoped.orR
          } else {
            observed := source
          }
        }
      }
      val verilog = read(directory.resolve("TypedScopedZero.v"))
      val compact = verilog.replaceAll("\\s+", "")
      assert(compact.contains("if(((WIDTH)>(1)))begin"), verilog)
      assert(compact.contains("assignscoped_zero={(WIDTH-1){1'b0}};"), verilog)
    }
  }

  test("retained zero publication rejects a projected width on a broader native owner") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", 5, 1, 8).asElabInt
      var zero: UInt = null
      val report = SpinalVerilog(concreteConfig(directory)) {
        new Component {
          setDefinitionName("TypedEscapedZeroWidth")
          val observed = out UInt(5 bits)
          zero = UInt(5 bits).setName("escaped_zero").dontSimplifyIt()
          zero := 0
          observed := zero
        }
      }
      val domain = width.expression.exactDomain.get
      ElaborationDomainContext.withAdmitted(
        domain.root, Set[BigInt](5, 6, 7, 8), width.sourceLocation
      ) {
        ParameterizedWidth.attach(zero, width.toParameterizedBitCount("escaped zero width"))
      }
      val native = read(directory.resolve("TypedEscapedZeroWidth.v"))
      val error = intercept[ParameterizedVerilogException] {
        ExternalParameterizedVerilogNativeFallback.rewriteRetainedZeroAssignments(report.toplevel, native)
      }
      assert(error.code == "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-SCOPE-MISMATCH")
    }
  }

  test("retained resize rewrite rejects an additional same-target assignment") {
    withTemporaryDirectory { directory =>
      val targetWidth =
        HdlInt.param("TARGET", default = 5, min = 5, max = 7).asElabInt
      val report = SpinalVerilog(concreteConfig(directory)) {
        new Component {
          setDefinitionName("RetainedResizeEmittedLineage")
          val source = in(Bits(4 bits)).setName("source")
          val observed = out(Bits(targetWidth bits)).setName("observed")
          val retained = source
            .resize(targetWidth)
            .setName("retained_grow_resize")
            .dontSimplifyIt()
          observed := retained
        }
      }
      val native = read(directory.resolve("RetainedResizeEmittedLineage.v"))
      val assignment =
        "(?m)^(\\s*assign\\s+retained_grow_resize\\s*=\\s*)(.*?)(;\\s*)$".r
          .findFirstMatchIn(native)
          .getOrElse {
            fail(s"retained resize has no native witness assignment:\n$native")
          }
      assert(
        assignment.group(2).replaceAll("\\s+", "") == "{1'd0,source}",
        native
      )

      val rewritten = ExternalParameterizedVerilogNativeFallback
        .rewriteRetainedResizeAssignments(report.toplevel, native)
      assert(
        rewritten
          .replaceAll("\\s+", "")
          .contains(
            "assignretained_grow_resize={{(TARGET-4){1'b0}},source};"
          ),
        rewritten
      )

      Vector(
        assignment.group(0) -> 2,
        "assign retained_grow_resize = source;" -> 1
      ).foreach { case (duplicate, exactRewriteCount) =>
        val error = intercept[ParameterizedVerilogException] {
          ExternalParameterizedVerilogNativeFallback
            .rewriteRetainedResizeAssignments(
              report.toplevel,
              native + "\n" + duplicate + "\n"
            )
        }
        assert(
          error.code ==
            "SPINAL-PARAMETERIZED-VERILOG-RESIZE-ASSIGNMENT-NOT-UNIQUE"
        )
        assert(error.detail.contains("2 module-scope target assignments"))
        assert(
          error.detail.contains(
            s"$exactRewriteCount exact native Resize rewrites"
          )
        )
      }
    }
  }

  test("retained zero rewrite requires one exact native combinational literal edge") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 5, min = 1, max = 32).asElabInt
      val report = SpinalVerilog(concreteConfig(directory)) {
        new Component {
          setDefinitionName("RetainedZeroEmittedLineage")
          val observed = out(UInt(width bits)).setName("observed")
          val retained = UInt(width bits).setName("retained_zero").dontSimplifyIt()
          retained := 0
          observed := retained
        }
      }
      val native = read(directory.resolve("RetainedZeroEmittedLineage.v"))
      val assignment = "(?m)^(\\s*assign\\s+retained_zero\\s*=\\s*)(.*?)(;\\s*)$".r
        .findFirstMatchIn(native).getOrElse(fail("native zero assignment missing:\n" + native))
      val rewritten = ExternalParameterizedVerilogNativeFallback
        .rewriteRetainedZeroAssignments(report.toplevel, native)
      assert(rewritten.replaceAll("\\s+", "").contains("assignretained_zero={WIDTH{1'b0}};"), rewritten)
      val stale = native.substring(0, assignment.start(2)) + "5'h1" + native.substring(assignment.end(2))
      Vector(stale, native + "\n" + assignment.group(0) + "\n").foreach { altered =>
        val error = intercept[ParameterizedVerilogException] {
          ExternalParameterizedVerilogNativeFallback.rewriteRetainedZeroAssignments(report.toplevel, altered)
        }
        assert(error.code == "SPINAL-PARAMETERIZED-VERILOG-ZERO-EMITTED-LINEAGE-MISMATCH")
      }
    }
  }

  test("canonical memory inventory rejects independent same-name geometry roots") {
    withTemporaryDirectory { directory =>
      val depth = exactDirectExpression(
        ElaborationIntegerParameter("SIZE", 8, 1, 64)
      )
      val elementWidth = exactDirectExpression(
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
    }
  }

  test("retained UInt inventory rejects independent same-name roots") {
    withTemporaryDirectory { directory =>
      val first = exactDirectExpression(
        ElaborationIntegerParameter("VALUE", 8, 1, 16)
      )
      val second = exactDirectExpression(
        ElaborationIntegerParameter("VALUE", 8, 1, 16)
      )
      val report = SpinalVerilog(concreteConfig(directory)) {
        new Component {
          setDefinitionName("IndependentRetainedValues")
          val firstValue = out(UInt(8 bits))
          val secondValue = out(UInt(8 bits))
          firstValue.assignFrom(UIntLiteral(BigInt(8), null, 8))
          secondValue.assignFrom(UIntLiteral(BigInt(8), null, 8))
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

  private def exactDirectExpression(
      parameter: ElaborationIntegerParameter
  ): ElaborationIntegerExpression =
    ElabInt.directParameter(parameter, sourceLocation = None).expression

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
      left.parameterRoots.forall(root => right.parameterRoots.exists(_ eq root))
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
