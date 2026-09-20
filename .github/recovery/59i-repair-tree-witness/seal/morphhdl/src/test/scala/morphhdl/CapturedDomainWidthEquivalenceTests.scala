package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import spinal.core._

/** Exercise one emitted resize across overrides against Verilog assignment
  * semantics, including the DUT's actual port widths and signed extension.
  */
object NativeResizeCompatibilitySimulation {
  def check(directory: Path, fileName: String, moduleName: String,
            parameterName: String, cases: Vector[(Int, Int, Int)],
            signedSource: Boolean = false): Unit = {
    import scala.sys.process._
    cases.zipWithIndex.foreach { case ((parameter, inputWidth, outputWidth), index) =>
      val bench = directory.resolve(s"resize_compatibility_$index.v")
      val binary = directory.resolve(s"resize_compatibility_$index.out")
      val interpretation = if (signedSource) "$signed(source)" else "source"
      Files.write(bench, s"""module ResizeCompatibilityBench;
  reg [${inputWidth - 1}:0] source;
  wire [${outputWidth - 1}:0] observed;
  reg [${outputWidth - 1}:0] expected;
  integer value;
  $moduleName #(.$parameterName($parameter)) dut (.source(source), .observed(observed));
  initial begin
    if ($$bits(dut.source) != $inputWidth || $$bits(dut.observed) != $outputWidth)
      $$fatal(1, "resize port width mismatch");
    for (value = 0; value < ${1 << inputWidth}; value = value + 1) begin
      source = value;
      #1;
      expected = $interpretation;
      if (observed !== expected) $$fatal(1, "resize value mismatch");
    end
    $$display("RESIZE_PASS");
    $$finish;
  end
endmodule
""".getBytes(StandardCharsets.UTF_8))
      val diagnostics = new StringBuilder
      val logger = ProcessLogger(line => diagnostics.append(line).append('\n'),
        line => diagnostics.append(line).append('\n'))
      // Check the generated DUT independently as strict Verilog-2001. Only
      // the verification bench uses SystemVerilog $bits/$fatal assertions.
      val strict = Process(Seq("iverilog", "-g2001", "-s", moduleName,
        s"-P$moduleName.$parameterName=$parameter", "-tnull",
        directory.resolve(fileName).toString)).!(logger)
      require(strict == 0, diagnostics.toString)
      val compiled = Process(Seq("iverilog", "-g2012", "-s", "ResizeCompatibilityBench",
        "-o", binary.toString, directory.resolve(fileName).toString, bench.toString)).!(logger)
      require(compiled == 0, diagnostics.toString)
      val simulated = Process(Seq("vvp", binary.toString)).!!
      require(simulated.linesIterator.exists(_.trim == "RESIZE_PASS"), simulated)
    }
  }
}

object CapturedDomainWidthEquivalenceSmoke {
  final class ExactSingletonDepth(depth: ElabInt) extends Component {
    setDefinitionName("CapturedDomainExactSingletonDepth")

    val narrow = in UInt (1 bits)
    val matching = in UInt (log2Up(depth + 1) bits)
    val observed = out UInt (log2Up(depth + 1) bits)

    if (depth == 1) {
      observed := narrow
    } else {
      observed := matching
    }
  }

  final class VaryingNarrowDepth(depth: ElabInt) extends Component {
    setDefinitionName("CapturedDomainVaryingNarrowDepth")

    val narrow = in UInt (1 bits)
    val matching = in UInt (log2Up(depth + 1) bits)
    val observed = out UInt (log2Up(depth + 1) bits)

    if (depth <= 2) {
      observed := narrow
    } else {
      observed := matching
    }
  }

  final class IndependentPredicateRoot(
      targetDepth: ElabInt,
      branchDepth: ElabInt
  ) extends Component {
    setDefinitionName("CapturedDomainIndependentPredicateRoot")

    val narrow = in UInt (1 bits)
    val matching = in UInt (log2Up(targetDepth + 1) bits)
    val observed = out UInt (log2Up(targetDepth + 1) bits)

    if (branchDepth == 1) {
      observed := narrow
    } else {
      observed := matching
    }
  }

  final class TypedResizeConsumerMismatch(width: ElabInt) extends Component {
    setDefinitionName("TypedResizeConsumerMismatch")

    val source = in UInt (width bits)
    val observed = out UInt ((width + (width == 4).toElabInt) bits)

    observed := source.resize(width)
  }

  final class TypedResizeFixedConsumer(width: ElabInt) extends Component {
    setDefinitionName("TypedResizeFixedConsumer")

    val source = in UInt ((width + (width == 4).toElabInt) bits)
    val observed = out UInt (3 bits)

    observed := source.resize(width)
  }

  final class TypedResizeMixedSourceConsumer(width: ElabInt) extends Component {
    setDefinitionName("TypedResizeMixedSourceConsumer")

    val source = in UInt ((ElabInt.literal(6) - width) bits)
    val observed = out UInt (3 bits)

    observed := source.resize(width)
  }

  final class TypedResizeFixedSourceConsumer(width: ElabInt) extends Component {
    setDefinitionName("TypedResizeFixedSourceConsumer")

    val source = in UInt (3 bits)
    val observed = out UInt (3 bits)

    observed := source.resize(width)
  }

  final class TypedResizeUnprovenInternalSource(width: ElabInt) extends Component {
    setDefinitionName("TypedResizeUnprovenInternalSource")

    val incoming = in UInt (3 bits)
    val observed = out UInt (3 bits)
    val source = UInt(3 bits)

    source := incoming
    observed := source.resize(width)
  }

  final class TypedResizeProjectedEscape(width: ElabInt) extends Component {
    setDefinitionName("TypedResizeProjectedEscape")

    val source = in UInt (3 bits)
    val observed = out UInt (1 bits)

    var escaped: UInt = null
    if (width == 1) {
      val branchMarker = new TypedResizeChild
      branchMarker.incoming := source
      escaped = source.resize(width)
    } else {
      val branchMarker = new TypedResizeChild
      branchMarker.incoming := source
    }
    observed := escaped
  }

  final class TypedResizeChild extends Component {
    val incoming = in UInt (3 bits)
    val outgoing = out UInt (3 bits)
    outgoing := incoming
  }

  final class TypedResizeForeignSourceConsumer(width: ElabInt) extends Component {
    setDefinitionName("TypedResizeForeignSourceConsumer")

    val source = in UInt (3 bits)
    val observed = out UInt (3 bits)
    val child = new TypedResizeChild

    child.incoming := source
    observed := child.outgoing.resize(width)
  }

  final class TypedResizeNarrowAssignmentOwner(width: ElabInt) extends Component {
    setDefinitionName("TypedResizeNarrowAssignmentOwner")

    val source = in UInt (width bits)
    val observed = out Bool ()
    observed := source.orR

    if (width == 2) {
      val local = UInt(1 bits)
      local.setName("narrow_local_true")
      local := source.resize(ElabInt.literal(3) - width)
      val widened = UInt(3 bits)
      widened := local.resize(3)
      val sink = new TypedResizeChild
      sink.incoming := widened
    } else {
      val local = UInt(1 bits)
      local := source.resize(1)
      val widened = UInt(3 bits)
      widened := local.resize(3)
      val sink = new TypedResizeChild
      sink.incoming := widened
    }
  }

  final class TypedResizeNestedOperand(
      sourceWidth: ElabInt,
      targetWidth: ElabInt
  ) extends Component {
    setDefinitionName("TypedResizeNestedOperand")

    val source = in UInt (sourceWidth bits)
    val observed = out Bits ((sourceWidth + 1) bits)

    observed := (source.resize(targetWidth).asBits ## False)
  }

  final class TypedBitsResizeWholeAssignment(targetWidth: ElabInt) extends Component {
    setDefinitionName("TypedBitsResizeWholeAssignment")

    val source = in Bits (16 bits)
    val observed = out Bits (targetWidth bits)

    observed := source.resize(targetWidth)
  }

  final class TypedBitsResizeCrossingInputWidth(targetWidth: ElabInt) extends Component {
    setDefinitionName("TypedBitsResizeCrossingInputWidth")

    val source = in Bits (4 bits)
    val observed = out Bits (targetWidth bits)

    observed := source.resize(targetWidth)
  }

  final class TypedBitsResizeNamedCrossingCarrier(targetWidth: ElabInt) extends Component {
    setDefinitionName("TypedBitsResizeNamedCrossingCarrier")

    val source = in Bits (4 bits)
    val observed = out Bits (targetWidth bits)
    val retained = source
      .resize(targetWidth)
      .setName("retained_crossing_resize")
      .dontSimplifyIt()

    observed := retained
  }

  final class TypedBitsResizeNamedGrowCarrier(targetWidth: ElabInt) extends Component {
    setDefinitionName("TypedBitsResizeNamedGrowCarrier")

    val source = in Bits (4 bits)
    val observed = out Bits (targetWidth bits)
    val retained = source
      .resize(targetWidth)
      .setName("retained_grow_resize")
      .dontSimplifyIt()

    observed := retained
  }

  final class TypedSIntResizeNamedGrowCarrier(width: ElabInt) extends Component {
    setDefinitionName("TypedSIntResizeNamedGrowCarrier")

    val source = in SInt (width bits)
    val observed = out SInt ((width + 1) bits)
    val retained = source
      .resize(width + 1)
      .setName("retained_signed_grow")
      .dontSimplifyIt()

    observed := retained
  }

  final class TypedBitsResizeNamedFixedCarrier(
      sourceWidth: ElabInt,
      targetWidth: ElabInt
  ) extends Component {
    setDefinitionName("TypedBitsResizeNamedFixedCarrier")

    val source = in Bits (sourceWidth bits)
    val observed = out Bits (targetWidth bits)
    val carrier = Bits(4 bits)
    carrier.setName("named_fixed_carrier")

    carrier := source
    observed := carrier.resize(targetWidth)
  }

  final class TypedBitsResizeTransientCast(targetWidth: ElabInt) extends Component {
    setDefinitionName("TypedBitsResizeTransientCast")

    val source = in SInt (3 bits)
    val observed = out Bits (targetWidth bits)

    observed := source.asBits.resize(targetWidth)
  }

  final class TypedBitsResizeNestedOperand(
      sourceWidth: ElabInt,
      targetWidth: ElabInt
  ) extends Component {
    setDefinitionName("TypedBitsResizeNestedOperand")

    val source = in Bits (sourceWidth bits)
    val observed = out Bits ((sourceWidth + 1) bits)

    observed := (source.resize(targetWidth) ## False)
  }

}

class CapturedDomainWidthEquivalenceTests extends AnyFunSuite {
  import CapturedDomainWidthEquivalenceSmoke._

  test("a captured singleton domain proves symbolic target and concrete source widths equal") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "captured_domain_exact_singleton_depth.v"
      config.netlistFileName = fileName
      val depth =
        HdlInt.param("DEPTH", default = 1, min = 1, max = 8).asElabInt

      MorphVerilog(config)(new ExactSingletonDepth(depth))

      val verilog = new String(
        Files.readAllBytes(directory.resolve(fileName)),
        StandardCharsets.UTF_8
      )
      assert(verilog.contains("parameter integer DEPTH = 1"))
      assert(verilog.replaceAll("\\s+", "").contains("DEPTH)==(1"))
    }
  }

  test("a captured domain rejects a concrete source when symbolic target width varies") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "captured_domain_varying_narrow_depth.v"
      config.netlistFileName = fileName
      val depth =
        HdlInt.param("DEPTH", default = 1, min = 1, max = 8).asElabInt

      expectWidthMismatch(
        directory,
        fileName,
        MorphVerilog.tryGenerate(config)(new VaryingNarrowDepth(depth))
      )
    }
  }

  test("same witnesses never correlate an independent predicate root with target width") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "captured_domain_independent_predicate_root.v"
      config.netlistFileName = fileName
      val targetDepth =
        HdlInt.param("TARGET_DEPTH", default = 1, min = 1, max = 8).asElabInt
      val branchDepth =
        HdlInt.param("BRANCH_DEPTH", default = 1, min = 1, max = 8).asElabInt

      expectWidthMismatch(
        directory,
        fileName,
        MorphVerilog.tryGenerate(config) {
          new IndependentPredicateRoot(targetDepth, branchDepth)
        }
      )
    }
  }

  test("a typed resize cannot lend its witness to a different consumer width") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_resize_consumer_mismatch.v"
      config.netlistFileName = fileName
      val width =
        HdlInt.param("WIDTH", default = 3, min = 2, max = 4).asElabInt

      expectWidthMismatch(
        directory,
        fileName,
        MorphVerilog.tryGenerate(config) {
          new TypedResizeConsumerMismatch(width)
        }
      )
    }
  }

  test("a normalized typed resize preserves its exact fixed consumer boundary") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_resize_fixed_consumer.v"
      config.netlistFileName = fileName
      val width =
        HdlInt.param("WIDTH", default = 3, min = 2, max = 4).asElabInt

      MorphVerilog(config) {
        new TypedResizeFixedConsumer(width)
      }

      val verilog = new String(
        Files.readAllBytes(directory.resolve(fileName)),
        StandardCharsets.UTF_8
      )
      assert(verilog.contains("parameter integer WIDTH = 3"))
      assert(
        """(?m)^[ \t]*output[ \t]+wire[ \t]+\[2:0\][ \t]+observed[ \t]*[,;]?[ \t]*$""".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      assertNormalizedTypedUIntResize(
        verilog,
        expectedSourceWidth = "WIDTH+WIDTH==4?1:0"
      )
    }
  }

  test("a normalized typed resize preserves mixed source and target domains") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_resize_mixed_source_consumer.v"
      config.netlistFileName = fileName
      val width =
        HdlInt.param("WIDTH", default = 3, min = 2, max = 4).asElabInt

      MorphVerilog(config) {
        new TypedResizeMixedSourceConsumer(width)
      }

      val verilog = new String(
        Files.readAllBytes(directory.resolve(fileName)),
        StandardCharsets.UTF_8
      )
      assert(verilog.contains("parameter integer WIDTH = 3"))
      assert(
        """(?m)^[ \t]*output[ \t]+wire[ \t]+\[2:0\][ \t]+observed[ \t]*[,;]?[ \t]*$""".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      assertNormalizedTypedUIntResize(
        verilog,
        expectedSourceWidth = "6-WIDTH"
      )
    }
  }

  test("a normalized typed resize preserves a symbolic target between fixed boundaries") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_resize_fixed_source_consumer.v"
      config.netlistFileName = fileName
      val width =
        HdlInt.param("WIDTH", default = 3, min = 1, max = 3).asElabInt

      MorphVerilog(config) {
        new TypedResizeFixedSourceConsumer(width)
      }

      val verilog = new String(
        Files.readAllBytes(directory.resolve(fileName)),
        StandardCharsets.UTF_8
      )
      assert(verilog.contains("parameter integer WIDTH = 3"))
      assert(
        """(?m)^[ \t]*output[ \t]+wire[ \t]+\[2:0\][ \t]+observed[ \t]*[,;]?[ \t]*$""".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      assertNormalizedTypedUIntResize(
        verilog,
        expectedSourceWidth = "3"
      )
    }
  }

  test("a normalized typed resize rejects an internal source without width provenance") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_resize_unproven_internal_source.v"
      config.netlistFileName = fileName
      val width =
        HdlInt.param("WIDTH", default = 3, min = 1, max = 3).asElabInt

      MorphVerilog.tryGenerate(config) {
        new TypedResizeUnprovenInternalSource(width)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-SOURCE-WIDTH-UNPROVEN"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected unproven normalized typed resize source, received $report")
      }
      assert(!Files.exists(directory.resolve(fileName)))
    }
  }

  test("a projected typed resize cannot escape to a wider assignment owner") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_resize_projected_escape.v"
      config.netlistFileName = fileName
      val width =
        HdlInt.param("WIDTH", default = 1, min = 1, max = 3).asElabInt

      MorphVerilog.tryGenerate(config) {
        new TypedResizeProjectedEscape(width)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-SCOPE-MISMATCH"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected escaped projected typed resize, received $report")
      }
      assert(!Files.exists(directory.resolve(fileName)))
    }
  }

  test("a normalized typed resize rejects a foreign child-owned source") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_resize_foreign_source_consumer.v"
      config.netlistFileName = fileName
      val width =
        HdlInt.param("WIDTH", default = 3, min = 1, max = 3).asElabInt

      MorphVerilog.tryGenerate(config) {
        new TypedResizeForeignSourceConsumer(width)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-SOURCE-OWNER-UNSUPPORTED"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected foreign normalized typed resize source, received $report")
      }
      assert(!Files.exists(directory.resolve(fileName)))
    }
  }

  test("a normalized typed resize accepts a narrower exact assignment owner") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_resize_narrow_assignment_owner.v"
      config.netlistFileName = fileName
      val width =
        HdlInt.param("WIDTH", default = 1, min = 1, max = 2).asElabInt

      MorphVerilog(config) {
        new TypedResizeNarrowAssignmentOwner(width)
      }

      val verilog = new String(
        Files.readAllBytes(directory.resolve(fileName)),
        StandardCharsets.UTF_8
      )
      assert(verilog.contains("parameter integer WIDTH = 1"))
      assert(verilog.contains("{WIDTH{1'b1}}"), verilog)
      assert(verilog.replaceAll("\\s+", "").contains("3-WIDTH"), verilog)
    }
  }

  test("a nested typed resize rejects a different independent consumer width") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_resize_nested_operand.v"
      config.netlistFileName = fileName
      val sourceWidth =
        HdlInt.param("SOURCE_WIDTH", default = 3, min = 2, max = 4).asElabInt
      val targetWidth =
        HdlInt.param("TARGET_WIDTH", default = 3, min = 2, max = 4).asElabInt

      MorphVerilog.tryGenerate(config) {
        new TypedResizeNestedOperand(sourceWidth, targetWidth)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected independent nested resize width mismatch, received $report")
      }
      assert(!Files.exists(directory.resolve(fileName)))
    }
  }

  test("a typed Bits resize survives as an exact whole assignment") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_bits_resize_whole_assignment.v"
      config.netlistFileName = fileName
      val targetWidth =
        HdlInt.param("TARGET_WIDTH", default = 3, min = 2, max = 4).asElabInt

      MorphVerilog(config) {
        new TypedBitsResizeWholeAssignment(targetWidth)
      }

      val verilog = new String(
        Files.readAllBytes(directory.resolve(fileName)),
        StandardCharsets.UTF_8
      )
      val compact = verilog.replaceAll("\\s+", "")
      assert(verilog.contains("parameter integer TARGET_WIDTH = 3"), verilog)
      assert(compact.contains("outputwire[TARGET_WIDTH-1:0]observed"), verilog)
      assert(compact.replace("(TARGET_WIDTH)", "TARGET_WIDTH")
        .contains("source[TARGET_WIDTH-1:0]"), verilog)
    }
  }

  test("a typed whole-assignment resize supports exact narrowing and widening") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_bits_resize_crossing_input_width.v"
      config.netlistFileName = fileName
      val targetWidth =
        HdlInt.param("TARGET_WIDTH", default = 3, min = 3, max = 5).asElabInt

      MorphVerilog(config) {
        new TypedBitsResizeCrossingInputWidth(targetWidth)
      }
      NativeResizeCompatibilitySimulation.check(directory, fileName,
        "TypedBitsResizeCrossingInputWidth", "TARGET_WIDTH",
        Vector(3, 4, 5).map(value => (value, 4, value)))
    }
  }

  test("a named retained resize preserves its complete crossing-domain behavior") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_bits_resize_named_crossing_carrier.v"
      config.netlistFileName = fileName
      val targetWidth =
        HdlInt.param("TARGET", default = 3, min = 3, max = 5).asElabInt

      MorphVerilog(config) {
        new TypedBitsResizeNamedCrossingCarrier(targetWidth)
      }
      NativeResizeCompatibilitySimulation.check(directory, fileName,
        "TypedBitsResizeNamedCrossingCarrier", "TARGET",
        Vector(3, 4, 5).map(value => (value, 4, value)))
    }
  }

  test("a named unsigned grow preserves exact widths and zero extension") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_bits_resize_named_grow_carrier.v"
      config.netlistFileName = fileName
      val targetWidth =
        HdlInt.param("TARGET", default = 5, min = 5, max = 7).asElabInt

      MorphVerilog(config) {
        new TypedBitsResizeNamedGrowCarrier(targetWidth)
      }

      val verilog = new String(
        Files.readAllBytes(directory.resolve(fileName)),
        StandardCharsets.UTF_8
      )
      val compact = verilog.replaceAll("\\s+", "")
      assert(verilog.contains("parameter integer TARGET = 5"), verilog)
      assert(compact.contains("[TARGET-1:0]retained_grow_resize"), verilog)
      NativeResizeCompatibilitySimulation.check(directory, fileName,
        "TypedBitsResizeNamedGrowCarrier", "TARGET",
        Vector(5, 6, 7).map(value => (value, 4, value)))
    }
  }

  test("a symbolic signed grow preserves its dynamic sign index") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_sint_resize_named_grow_carrier.v"
      config.netlistFileName = fileName
      val width =
        HdlInt.param("WIDTH", default = 8, min = 4, max = 12).asElabInt

      MorphVerilog(config) {
        new TypedSIntResizeNamedGrowCarrier(width)
      }
      NativeResizeCompatibilitySimulation.check(directory, fileName,
        "TypedSIntResizeNamedGrowCarrier", "WIDTH",
        Vector(4, 8, 12).map(value => (value, value, value + 1)), signedSource = true)
    }
  }

  test("a named fixed carrier cannot launder a symbolic resize input width") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_bits_resize_named_fixed_carrier.v"
      config.netlistFileName = fileName
      val sourceWidth =
        HdlInt.param("SOURCE_WIDTH", default = 4, min = 2, max = 4).asElabInt
      val targetWidth =
        HdlInt.param("TARGET_WIDTH", default = 3, min = 3, max = 4).asElabInt

      MorphVerilog.tryGenerate(config) {
        new TypedBitsResizeNamedFixedCarrier(sourceWidth, targetWidth)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-NATIVE-RESIZE-LINEAGE-MISMATCH"
            ),
            failure.detail
          )
          assert(failure.detail.contains("source publication differs from its captured exact width"),
            failure.detail)
        case Right(report) =>
          fail(s"Expected named-carrier typed resize rejection, received $report")
      }
      assert(!Files.exists(directory.resolve(fileName)))
    }
  }

  test("an explicit SInt to Bits cast resizes with unsigned bit semantics") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_bits_resize_transient_cast.v"
      config.netlistFileName = fileName
      val targetWidth =
        HdlInt.param("TARGET_WIDTH", default = 3, min = 3, max = 4).asElabInt

      MorphVerilog(config) {
        new TypedBitsResizeTransientCast(targetWidth)
      }
      NativeResizeCompatibilitySimulation.check(directory, fileName,
        "TypedBitsResizeTransientCast", "TARGET_WIDTH",
        Vector(3, 4).map(value => (value, 3, value)))
    }
  }

  test("a nested typed Bits resize rejects a different independent consumer width") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val fileName = "typed_bits_resize_nested_operand.v"
      config.netlistFileName = fileName
      val sourceWidth =
        HdlInt.param("SOURCE_WIDTH", default = 3, min = 2, max = 4).asElabInt
      val targetWidth =
        HdlInt.param("TARGET_WIDTH", default = 3, min = 2, max = 4).asElabInt

      MorphVerilog.tryGenerate(config) {
        new TypedBitsResizeNestedOperand(sourceWidth, targetWidth)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected independent nested Bits resize width mismatch, received $report")
      }
      assert(!Files.exists(directory.resolve(fileName)))
    }
  }

  private def assertNormalizedTypedUIntResize(
      verilog: String,
      expectedSourceWidth: String
  ): Unit = {
    val assignmentPattern =
      """(?m)^\s*assign\s+observed\s*=\s*(.*?)\s*;\s*$""".r
    val assignments = assignmentPattern.findAllMatchIn(verilog).toVector
    assert(assignments.size == 1, verilog)

    val compactRhs = assignments.head.group(1).replaceAll("\\s+", "")
    assert(compactRhs != "source", verilog)

    // Parentheses are renderer punctuation here. Removing only those leaves an
    // exact token-level contract for source & ~({S{1'b1}} << T): S is the
    // retained source width, while T is the explicit typed resize WIDTH.
    val normalizedRhs = compactRhs.filterNot(character => character == '(' || character == ')')
    val expectedRhs =
      s"source&~{$expectedSourceWidth{1'b1}}<<WIDTH"
    assert(normalizedRhs == expectedRhs, verilog)
    assert(
      !"""(?m)^\s*assign\s+observed\s*=\s*source\s*;\s*$""".r
        .findFirstIn(verilog)
        .nonEmpty,
      verilog
    )
  }

  private def expectWidthMismatch(
      directory: Path,
      fileName: String,
      result: Either[MorphVerilogFailure, MorphSingleSourceVerilogReport]
  ): Unit = {
    result match {
      case Left(failure) =>
        assert(
          failure.detail.contains(
            "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
          ),
          failure.detail
        )
      case Right(report) =>
        fail(s"Expected captured-domain width mismatch, received $report")
    }
    assert(!Files.exists(directory.resolve(fileName)))
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-captured-domain-width-")
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
