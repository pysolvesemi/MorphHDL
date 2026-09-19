package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._
import spinal.core.TypedParameterizedVecHierarchyFixture.{CompoundVecParent, LeafwiseVecParent, VecParent}
import spinal.core.TypedParameterizedVecLineageFixture.{RemovedAutoConnectAssignment, RemovedWholeAssignment}

private object TypedParameterizedVecFixture {
  final case class Pixel() extends Bundle {
    val r = UInt(8 bits)
    val g = UInt(8 bits)
    val b = UInt(8 bits)
  }

  final case class Envelope(width: ElabInt, depth: ElabInt) extends Bundle {
    val tag = Bits(3 bits)
    val samples = Vec(UInt(width bits), depth)
  }

  final case class DuplexWord(width: ElabInt) extends Bundle with IMasterSlave {
    val request = UInt(width bits)
    val response = UInt(width bits)

    override def asMaster(): Unit = {
      out(request)
      in(response)
    }
  }

  final class PackedPorts(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("TypedParameterizedVec")

    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")
    val index = in(UInt(3 bits)).setName("index")
    val elementZero = out(UInt(width bits)).setName("element_zero")
    val selected = out(UInt(width bits)).setName("selected")

    val internalVec = Vec(UInt(width bits), depth)
      .setName("internal_vec")
      .dontSimplifyIt()
    internalVec := vecIn
    vecOut := internalVec
    elementZero := vecIn(0)
    selected := vecIn(index)
  }

  final class WideAddressDynamicRead(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("WideAddressDynamicReadVec")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val wideIndex = in(UInt(6 bits)).setName("wide_index")
    val selected = out(UInt(width bits)).setName("selected")
    selected := vecIn(wideIndex)
  }

  /** A downstream class name must not be allowed to select SInt lowering.
    * The leaf inherits Bits' exact TypeBits object even though its runtime
    * class name ends in `.SInt`.
    */
  final class SIntNamedBitsDynamicRead(depth: ElabInt) extends Component {
    setDefinitionName("SIntNamedBitsDynamicReadVec")
    val vecIn = in(Vec(adversarial.vecfake.SInt(8), depth)).setName("vec_in")
    val index = in(UInt(3 bits)).setName("index")
    val selected = out(Bits(8 bits)).setName("selected")
    selected := vecIn(index)
  }

  /** The explicit switch deliberately uses the same selector and carrier
    * leaves as the authoritative Vec dynamic read.  Coincidental native mux
    * structure must not be mistaken for (or consumed as) Vec lowering.
    */
  final class CoincidentMuxConsumer(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("CoincidentMuxConsumerVec")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val index = in(UInt(3 bits)).setName("index")
    val selected = out(UInt(width bits)).setName("selected")
    val caseSelected = out(UInt(width bits)).setName("case_selected")
    val caseHit = out(Bool()).setName("case_hit")

    selected := vecIn(index)
    caseSelected := vecIn(0)
    caseHit := False
    switch(index) {
      is(0) {
        caseSelected := vecIn(0)
        caseHit := True
      }
      is(1) {
        caseSelected := vecIn(1)
        caseHit := False
      }
      is(2) {
        caseSelected := vecIn(2)
        caseHit := True
      }
      is(3) {
        caseSelected := vecIn(3)
        caseHit := False
      }
      is(4) {
        caseSelected := vecIn(4)
        caseHit := True
      }
      is(5) {
        caseSelected := vecIn(5)
        caseHit := False
      }
      is(6) {
        caseSelected := vecIn(6)
        caseHit := True
      }
      is(7) {
        caseSelected := vecIn(7)
        caseHit := False
      }
    }
  }

  final class SingletonDynamicRead(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("SingletonDynamicReadVec")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val index = in(UInt(1 bits)).setName("index")
    val selected = out(UInt(width bits)).setName("selected")
    selected := vecIn(index)
  }

  final class SingletonUnusedDynamicRead(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("SingletonUnusedDynamicReadVec")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val index = in(UInt(1 bits)).setName("index")
    val plusOne = out(UInt(width bits)).setName("plus_one")
    val ignoredDynamicRead = vecIn(index)
    val one = U(1).resize(width).setName("one").dontSimplifyIt()
    val plusValue = (vecIn(0) + one)
      .setName("plus_value")
      .dontSimplifyIt()
    plusOne := plusValue
  }

  final class CloneHardTypeReg(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("CloneHardTypeRegVec")
    val clk = in(Bool()).setName("clk")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")

    val cloned = cloneOf(vecIn).setName("cloned_vec").dontSimplifyIt()
    val hardClone = HardType(vecIn)().setName("hard_vec").dontSimplifyIt()
    cloned := vecIn
    hardClone := cloned

    val registers = new ClockingArea(ClockDomain(clock = clk)) {
      val registered = Reg(HardType(vecIn))
        .setName("registered_vec")
        .dontSimplifyIt()
      registered := hardClone
    }
    vecOut := registers.registered
  }

  final class RegisteredDynamicWrite(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("RegisteredDynamicWriteVec")
    val clk = in(Bool()).setName("clk")
    val index = in(UInt(3 bits)).setName("index")
    val writeData = in(UInt(width bits)).setName("write_data")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")

    val registers = new ClockingArea(ClockDomain(clock = clk)) {
      val storage = Reg(Vec(UInt(width bits), depth))
        .setName("registered_vec")
        .dontSimplifyIt()
      storage(index) := writeData
    }
    vecOut := registers.storage
  }

  final class ControlledRegisteredDynamicWrite(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("ControlledRegisteredDynamicWriteVec")
    val clk = in(Bool()).setName("clk")
    val enable = in(Bool()).setName("enable")
    val index = in(UInt(3 bits)).setName("index")
    val writeData = in(UInt(width bits)).setName("write_data")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")

    val registers = new ClockingArea(ClockDomain(clock = clk)) {
      val storage = Reg(Vec(UInt(width bits), depth))
        .setName("registered_vec")
        .dontSimplifyIt()
      when(enable) {
        storage(index) := writeData
      }
    }
    vecOut := registers.storage
  }

  final class PackedRoundTrip(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("PackedRoundTripVec")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")
    val packed = vecIn.asBits.setName("packed_vec").dontSimplifyIt()
    val restored = Vec(UInt(width bits), depth)
      .setName("restored_vec")
      .dontSimplifyIt()
    restored.assignFromBits(packed)
    vecOut := restored
  }

  /** A packed Vec read remains an ordinary dataflow value when it feeds a
    * composed expression.  Deliberately omit `dontSimplifyIt()` here: the
    * typed Vec lowering, rather than a test-only retention hint, must preserve
    * the authoritative packed-read identity used by the XOR consumer.
    */
  final class PackedReadConsumer(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("PackedReadConsumerVec")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val mask = in(Bool()).setName("mask")
    val result = out(Bool()).setName("result")
    val packedRead = vecIn.asBits.setName("packed_read")
    result := packedRead(0) ^ mask
  }

  /** Direct single-root packed bridge. DEPTH defaults below its maximum so
    * the native witness wrapper and full-capacity carrier are both exercised.
    * `unrelatedCarrier` deliberately has the same native resize geometry but
    * is not part of either retained Vec packed operation.
    */
  final class DepthBitsBridge(depth: ElabInt) extends Component {
    setDefinitionName("DepthBitsBridgeVec")
    val bitsIn = in(Bits(depth bits)).setName("bits_in")
    val bitsOut = out(Bits(depth bits)).setName("bits_out")
    val unrelatedIn = in(Bits(depth bits)).setName("unrelated_in")
    val unrelatedOut = out(Bits(8 bits)).setName("unrelated_out")

    val values = Vec(Bool(), depth).setName("values").dontSimplifyIt()
    values.assignFromBits(bitsIn)
    val packedValues = values.asBits.setName("packed_values")
    bitsOut := packedValues

    val unrelatedCarrier = unrelatedIn
      .resize(8)
      .setName("unrelated_carrier")
      .dontSimplifyIt()
    unrelatedOut := unrelatedCarrier
  }

  final class PixelBitsBridge(depth: ElabInt) extends Component {
    setDefinitionName("PixelBitsBridgeVec")
    private val packedWidth = depth * 24
    val bitsIn = in(Bits(packedWidth bits)).setName("bits_in")
    val bitsOut = out(Bits(packedWidth bits)).setName("bits_out")
    val pixels = Vec(Pixel(), depth).setName("pixels").dontSimplifyIt()
    pixels.assignFromBits(bitsIn)
    val packedPixels = pixels.asBits.setName("packed_pixels")
    bitsOut := packedPixels
  }

  final class PackedUnrelatedWidth(
      width: ElabInt,
      depth: ElabInt,
      unrelatedPackedWidth: ElabInt
  ) extends Component {
    val packed = Bits(unrelatedPackedWidth bits)
    val target = Vec(UInt(width bits), depth)
    target.assignFromBits(packed)
  }

  /** Two live recorded aggregate operations deliberately compete for the
    * same target. Publication must reject their noncontiguous native priority
    * layout instead of reconstructing one assignment from signal names.
    */
  final class OverriddenWholeAssignment(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("OverriddenWholeAssignmentVec")
    val first = in(Vec(UInt(width bits), depth)).setName("first")
    val second = in(Vec(UInt(width bits), depth)).setName("second")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")
    val target = Vec(UInt(width bits), depth)
      .setName("target")
      .dontSimplifyIt()
    target.allowOverride()
    target := first
    target := second
    vecOut := target
  }

  /** Calls auto-connect from the input receiver so the authoritative native
    * assignment direction is opposite the syntactic operation receiver.
    */
  final class ReverseDirectionAutoConnect(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("ReverseDirectionAutoConnectVec")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")
    vecIn <> vecOut
  }

  final class MixedDirectionAutoConnect(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("MixedDirectionAutoConnectVec")
    val left = Vec(master(DuplexWord(width)), depth).setName("left")
    val right = Vec(slave(DuplexWord(width)), depth).setName("right")
    left <> right
  }

  final class SymbolicWidthConcreteDepth(width: ElabInt) extends Component {
    setDefinitionName("SymbolicWidthConcreteDepthVec")
    val vecIn = in(Vec(UInt(width bits), 4)).setName("vec_in")
    val vecOut = out(Vec(UInt(width bits), 4)).setName("vec_out")
    vecOut := vecIn
  }

  final class ConcreteWidthSymbolicDepth(depth: ElabInt) extends Component {
    setDefinitionName("ConcreteWidthSymbolicDepthVec")
    val vecIn = in(Vec(UInt(8 bits), depth)).setName("vec_in")
    val vecOut = out(Vec(UInt(8 bits), depth)).setName("vec_out")
    vecOut := vecIn
  }

  final class ExpressionShape(width: ElabInt, baseDepth: ElabInt) extends Component {
    setDefinitionName("ExpressionShapeVec")
    val vecIn = in(Vec(Bits((width + 1) bits), baseDepth + 1)).setName("vec_in")
    val vecOut = out(Vec(Bits((width + 1) bits), baseDepth + 1)).setName("vec_out")
    vecOut := vecIn
  }

  final class ConstantIndex(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("ConstantIndexVec")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val first = out(UInt(width bits)).setName("first")
    val third = out(UInt(width bits)).setName("third")
    first := vecIn(0)
    third := vecIn(2)
  }

  final class DynamicWrite(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("DynamicWriteVec")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")
    val index = in(UInt(3 bits)).setName("index")
    val writeData = in(UInt(width bits)).setName("write_data")

    val internalVec = Vec(UInt(width bits), depth)
      .setName("internal_vec")
      .dontSimplifyIt()
    internalVec := vecIn
    internalVec(index) := writeData
    vecOut := internalVec
  }

  final class VecInsideBundle(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("VecInsideBundle")
    val bundleIn = in(Envelope(width, depth)).setName("bundle_in")
    val bundleOut = out(Envelope(width, depth)).setName("bundle_out")
    bundleOut := bundleIn
  }

  final class VecOfBundle(depth: ElabInt) extends Component {
    setDefinitionName("VecOfBundle")
    val pixelsIn = in(Vec(Pixel(), depth)).setName("pixels_in")
    val pixelsOut = out(Vec(Pixel(), depth)).setName("pixels_out")
    val firstRed = out(UInt(8 bits)).setName("first_red")
    val firstGreen = out(UInt(8 bits)).setName("first_green")
    val firstBlue = out(UInt(8 bits)).setName("first_blue")
    pixelsOut := pixelsIn
    firstRed := pixelsIn(0).r
    firstGreen := pixelsIn(0).g
    firstBlue := pixelsIn(0).b
  }

  final class VecOfBundleOrdering(depth: ElabInt) extends Component {
    setDefinitionName("VecOfBundleOrdering")
    val pixelsIn = in(Vec(Pixel(), depth)).setName("pixels_in")
    val firstRed = out(UInt(8 bits)).setName("first_red")
    val firstGreen = out(UInt(8 bits)).setName("first_green")
    val firstBlue = out(UInt(8 bits)).setName("first_blue")
    val thirdBlue = out(UInt(8 bits)).setName("third_blue")
    firstRed := pixelsIn(0).r
    firstGreen := pixelsIn(0).g
    firstBlue := pixelsIn(0).b
    thirdBlue := pixelsIn(2).b
  }

  final class VecAndMem(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("TypedVecAndMem")
    val clk = in(Bool()).setName("clk")
    val address = in(UInt(3 bits)).setName("address")
    val writeEnable = in(Bool()).setName("write_enable")
    val readEnable = in(Bool()).setName("read_enable")
    val writeData = in(UInt(width bits)).setName("write_data")
    val readData = out(UInt(width bits)).setName("read_data")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")

    val vecStorage = Vec(UInt(width bits), depth)
      .setName("vec_storage")
      .dontSimplifyIt()
    vecStorage := vecIn
    vecOut := vecStorage

    private val memoryClock = ClockDomain(clock = clk)
    val memoryArea = new ClockingArea(memoryClock) {
      val memStorage = Mem(UInt(width bits), depth).setName("mem_storage")
      memStorage.write(
        address = address,
        data = writeData,
        enable = writeEnable
      )
      readData := memStorage.readSync(
        address = address,
        enable = readEnable,
        readUnderWrite = readFirst
      )
    }
  }

  final class ConcreteParity(useTypedLiteral: Boolean) extends Component {
    setDefinitionName("ConcreteVecParity")
    val vecIn = in(
      if (useTypedLiteral) Vec(UInt(8 bits), ElabInt.literal(4))
      else Vec(UInt(8 bits), 4)
    ).setName("vec_in")
    val vecOut = out(
      if (useTypedLiteral) Vec(UInt(8 bits), ElabInt.literal(4))
      else Vec(UInt(8 bits), 4)
    ).setName("vec_out")
    vecOut := vecIn
  }

  final class IncompatibleDepth(leftDepth: ElabInt, rightDepth: ElabInt) extends Component {
    val left = Vec(UInt(8 bits), leftDepth)
    val right = Vec(UInt(8 bits), rightDepth)
    left := right
  }

  final class IncompatibleElementWidth(width: ElabInt, depth: ElabInt) extends Component {
    val left = Vec(UInt(width bits), depth)
    val right = Vec(UInt((width + 1) bits), depth)
    left := right
  }
}

/** End-to-end typed Vec publication contracts.
  *
  * The strict-tool test generates one parameterized module once and then
  * specializes that same file at every WIDTH/DEPTH tuple. It must never be
  * replaced by one generated module per witness.
  */
class TypedParameterizedVecTests extends AnyFunSuite {
  import TypedParameterizedVecFixture._

  private final case class PortExpectation(
      name: String,
      direction: String,
      width: Int
  )

  private val PortDeclaration =
    """(?m)^\s*(input|output)\s+wire\s+\[([^\]]+)\]\s+([A-Za-z_][A-Za-z0-9_$]*)\s*(?:[,;]|$)""".r

  test("symbolic width with concrete depth emits one packed Vec port") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val verilog = generate(
        directory,
        "symbolic_width_concrete_depth.v",
        new SymbolicWidthConcreteDepth(width)
      )

      assertPackedPort(verilog, "input", "vec_in", Vector("WIDTH", "4", "*"))
      assertPackedPort(verilog, "output", "vec_out", Vector("WIDTH", "4", "*"))
      assertNoExplodedVecPorts(verilog, Vector("vec_in", "vec_out"))
    }
  }

  test("concrete width with symbolic depth emits one packed Vec port") {
    withTemporaryDirectory { directory =>
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "concrete_width_symbolic_depth.v",
        new ConcreteWidthSymbolicDepth(depth)
      )

      assertPackedPort(verilog, "input", "vec_in", Vector("8", "DEPTH", "*"))
      assertPackedPort(verilog, "output", "vec_out", Vector("8", "DEPTH", "*"))
      assertNoExplodedVecPorts(verilog, Vector("vec_in", "vec_out"))
    }
  }

  test("independent symbolic width and depth remain in the total packed width") {
    withTemporaryDirectory { directory =>
      val reportAndText = generatePackedPorts(directory)
      val report = reportAndText._1
      val verilog = reportAndText._2

      assert(report.parameters.map(_.name).toSet == Set("WIDTH", "DEPTH"))
      assertPackedPort(verilog, "input", "vec_in", Vector("WIDTH", "DEPTH", "*"))
      assertPackedPort(verilog, "output", "vec_out", Vector("WIDTH", "DEPTH", "*"))
      assertInternalPackedVector(verilog, "internal_vec", Vector("WIDTH", "DEPTH", "*"))
      assertPackedRangeAlgebra(verilog, "input", "vec_in", "WIDTH * DEPTH - 1:0")
      assertPackedRangeAlgebra(verilog, "output", "vec_out", "WIDTH * DEPTH - 1:0")
      assertInternalRangeAlgebra(verilog, "internal_vec", "WIDTH * DEPTH - 1:0")
      assertNoExplodedVecPorts(verilog, Vector("vec_in", "vec_out", "internal_vec"))
      assert(verilog.contains("assign vec_out = internal_vec"))
      assert(hasCanonicalSlice(compact(verilog), "element_zero", element = 0), verilog)
      assert(!compact(verilog).contains("vec_in_0"), verilog)
      assertSymbolicElementDriver(verilog, "selected", "WIDTH")
    }
  }

  test("symbolic element and depth expressions are not collapsed to defaults") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 12)
      val baseDepth = parameter("DEPTH", default = 4, minimum = 1, maximum = 7)
      val verilog = generate(
        directory,
        "expression_shape_vec.v",
        new ExpressionShape(width, baseDepth)
      )
      val expected = "(WIDTH + 1) * (DEPTH + 1) - 1:0"
      assertPackedRangeAlgebra(verilog, "input", "vec_in", expected)
      assertPackedRangeAlgebra(verilog, "output", "vec_out", expected)
      assert(!packedRange(verilog, "input", "vec_in").contains("30"))
      assertNoExplodedVecPorts(verilog, Vector("vec_in", "vec_out"))
    }
  }

  test("constant indices publish the canonical zero-based packed slices") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 3, maximum = 8)
      val verilog = generate(
        directory,
        "constant_index_vec.v",
        new ConstantIndex(width, depth)
      )
      val normalized = compact(verilog)

      assertPackedPort(verilog, "input", "vec_in", Vector("WIDTH", "DEPTH", "*"))
      assert(hasCanonicalSlice(normalized, "first", element = 0), verilog)
      assert(hasCanonicalSlice(normalized, "third", element = 2), verilog)
      assert(!normalized.contains("vec_in_0"))
      assert(!normalized.contains("vec_in_2"))
    }
  }

  test("dynamic Vec reads retain the original wide address and singleton carrier") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "wide_address_dynamic_read.v",
        new WideAddressDynamicRead(width, depth)
      )

      assertPackedRangeAlgebra(verilog, "input", "vec_in", "WIDTH * DEPTH - 1:0")
      assertIndexedSliceUsesAddress(verilog, "vec_in", "wide_index", "WIDTH")
      assertSymbolicElementDriver(verilog, "selected", "WIDTH")
    }

    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 1, minimum = 1, maximum = 1)
      val verilog = generate(
        directory,
        "singleton_dynamic_read.v",
        new SingletonDynamicRead(width, depth)
      )

      assertPackedRangeAlgebra(verilog, "input", "vec_in", "WIDTH * DEPTH - 1:0")
      assertIndexedSliceUsesAddress(verilog, "vec_in", "index", "WIDTH")
      assertSymbolicElementDriver(verilog, "selected", "WIDTH")
      assertNoExplodedVecPorts(verilog, Vector("vec_in"))
    }
  }

  test("a downstream Bits subclass named SInt cannot acquire signed Vec lowering") {
    withTemporaryDirectory { directory =>
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "sint_named_bits_dynamic_read.v",
        new SIntNamedBitsDynamicRead(depth)
      )

      assertPackedRangeAlgebra(verilog, "input", "vec_in", "8 * DEPTH - 1:0")
      assertIndexedSliceUsesAddress(verilog, "vec_in", "index", "8")
      assert(!verilog.contains("$signed("), verilog)
    }
  }

  test("coincident same-selector mux cannot be consumed as a Vec dynamic read") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 8, minimum = 8, maximum = 8)
      val rtl = directory.resolve("coincident_mux_consumer_vec.v")
      MorphVerilog.tryGenerate(config(directory, rtl.getFileName.toString)) {
        new CoincidentMuxConsumer(width, depth)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-CONTROL-UNSUPPORTED"
            ),
            failure.detail
          )
          assert(!Files.exists(rtl), "coincident mux rejection published partial RTL")

        case Right(_) =>
          val verilog = read(rtl)
          assertPackedRangeAlgebra(
            verilog,
            "input",
            "vec_in",
            "WIDTH * DEPTH - 1:0"
          )
          assertIndexedSliceUsesAddress(verilog, "vec_in", "index", "WIDTH")
          assertSymbolicElementDriver(verilog, "selected", "WIDTH")

          val normalized = compact(verilog)
          assert(normalized.contains("case(index)"), verilog)
          val caseAssignments = proceduralAssignmentsTo(verilog, "case_selected")
          assert(caseAssignments.size >= 9, verilog)
          assert(caseAssignments.forall(_.contains("vec_in[")), verilog)
          assert(caseAssignments.forall(_.contains("WIDTH")), verilog)
          assert(caseAssignments.distinct.size >= 8, verilog)
          val hitAssignments = proceduralAssignmentsTo(verilog, "case_hit")
          assert(hitAssignments.exists(_.contains("1'b0")), verilog)
          assert(hitAssignments.exists(_.contains("1'b1")), verilog)
          assertNoExplodedVecPorts(
            verilog,
            Vector("vec_in", "selected", "case_selected")
          )
      }
    }
  }

  test("unused singleton dynamic read cannot consume an unrelated static expression") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 1, minimum = 1, maximum = 1)
      val verilog = generate(
        directory,
        "singleton_unused_dynamic_read.v",
        new SingletonUnusedDynamicRead(width, depth)
      )

      assertPackedRangeAlgebra(verilog, "input", "vec_in", "WIDTH * DEPTH - 1:0")
      val plusDriver = "(?m)^\\s*assign\\s+plus_value\\s*=\\s*([^;]+);".r
        .findFirstMatchIn(verilog)
        .getOrElse(fail(s"missing retained plus_value driver\n$verilog"))
        .group(1)
      assert(plusDriver.contains("+"), verilog)
      assert(plusDriver.contains("vec_in["), verilog)
      assert(verilog.contains("assign plus_one = plus_value"), verilog)
      assert(!verilog.contains("vec_in_0"), verilog)
      assertNoExplodedVecPorts(verilog, Vector("vec_in"))
    }
  }

  test("dynamic UInt Vec write retains symbolic element width") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "dynamic_write_vec.v",
        new DynamicWrite(width, depth)
      )

      assertPackedPort(verilog, "input", "vec_in", Vector("WIDTH", "DEPTH", "*"))
      assertPackedPort(verilog, "output", "vec_out", Vector("WIDTH", "DEPTH", "*"))
      assertInternalPackedVector(verilog, "internal_vec", Vector("WIDTH", "DEPTH", "*"))
      assertNoExplodedVecPorts(verilog, Vector("vec_in", "vec_out", "internal_vec"))
      assertNoWitnessElementDeclarations(verilog, witnessWidth = 5)
      val writeLines = verilog
        .split("\\r?\\n")
        .filter(_.contains("write_data"))
        .map(compact)
        .filter(line => line.contains("internal_vec") || line.contains("_zz"))
      assert(writeLines.nonEmpty, verilog)
      assert(writeLines.exists(line => line.contains("WIDTH") && line.contains("index")), verilog)
    }
  }

  test("clone HardType and Reg propagation reaches packed generated RTL") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "clone_hardtype_reg_vec.v",
        new CloneHardTypeReg(width, depth)
      )
      val expected = "WIDTH * DEPTH - 1:0"

      assertPackedRangeAlgebra(verilog, "input", "vec_in", expected)
      assertPackedRangeAlgebra(verilog, "output", "vec_out", expected)
      Vector("cloned_vec", "hard_vec", "registered_vec").foreach { signal =>
        assertInternalRangeAlgebra(verilog, signal, expected)
      }
      assert(compact(declarationContaining(verilog, "registered_vec")).startsWith("reg["), verilog)
      assert(compact(verilog).contains("always@(posedgeclk)"), verilog)
      assertNoExplodedVecPorts(
        verilog,
        Vector("vec_in", "vec_out", "cloned_vec", "hard_vec", "registered_vec")
      )
    }
  }

  test("Reg Vec dynamic write has one packed procedural driver") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val rtl = directory.resolve("registered_dynamic_write_vec.v")
      val verilog = generate(
        directory,
        rtl.getFileName.toString,
        new RegisteredDynamicWrite(width, depth)
      )
      val expected = "WIDTH * DEPTH - 1:0"
      assertPackedRangeAlgebra(verilog, "output", "vec_out", expected)
      assertInternalRangeAlgebra(verilog, "registered_vec", expected)
      val targets = proceduralAssignmentsTo(verilog, "registered_vec")
      assert(
        targets.size == 1,
        s"expected one packed register driver, found ${targets.size}\n$verilog"
      )
      assert(targets.head.contains("index") && targets.head.contains("WIDTH"), verilog)
      assert(
        !verilog.matches("(?s).*assign\\s+registered_vec(?:\\s*\\[|\\s*=).*"),
        verilog
      )
      assertNoExplodedVecPorts(verilog, Vector("vec_out", "registered_vec"))
    }
  }

  test("Reg Vec dynamic write never drops unrelated user control") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val rtl = directory.resolve("controlled_registered_dynamic_write_vec.v")
      val failure = MorphVerilog.tryGenerate(config(directory, rtl.getFileName.toString)) {
        new ControlledRegisteredDynamicWrite(width, depth)
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected controlled Reg(Vec) rejection, received $value")
      }

      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-CONTROL-UNSUPPORTED"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "controlled Reg(Vec) write published partial RTL")
    }
  }

  test("Vec asBits round-trip preserves packed shape and rejects unrelated roots") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "packed_round_trip_vec.v",
        new PackedRoundTrip(width, depth)
      )
      val expected = "WIDTH * DEPTH - 1:0"

      assertPackedRangeAlgebra(verilog, "input", "vec_in", expected)
      assertPackedRangeAlgebra(verilog, "output", "vec_out", expected)
      assertInternalRangeAlgebra(verilog, "packed_vec", expected)
      assertInternalRangeAlgebra(verilog, "restored_vec", expected)
      assertNoExplodedVecPorts(
        verilog,
        Vector("vec_in", "vec_out", "packed_vec", "restored_vec")
      )
    }

    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val unrelated = parameter(
        "OTHER_PACKED_WIDTH",
        default = 25,
        minimum = 1,
        maximum = 128
      )
      val failure = intercept[ParameterizedVerilogException] {
        SpinalVerilog(config(directory, "packed_unrelated_width.v")) {
          new PackedUnrelatedWidth(width, depth, unrelated)
        }
      }
      assert(failure.code == "SPINAL-ELAB-VEC-PACKED-SOURCE-PROVENANCE-MISSING")
      assert(!Files.exists(directory.resolve("packed_unrelated_width.v")))
    }
  }

  test("packed Vec read remains distinct when consumed by a composed expression") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "packed_read_consumer_vec.v",
        new PackedReadConsumer(width, depth)
      )
      val expected = "WIDTH * DEPTH - 1:0"

      assertPackedRangeAlgebra(verilog, "input", "vec_in", expected)
      assertInternalRangeAlgebra(verilog, "packed_read", expected)

      val packedReadDriver = continuousAssignmentRhs(verilog, "packed_read")
      assert(packedReadDriver == "vec_in", verilog)
      val resultDriver = continuousAssignmentRhs(verilog, "result")
      assert(resultDriver.contains("packed_read[0]"), verilog)
      assert(resultDriver.contains("mask"), verilog)
      assert(resultDriver.contains("^"), verilog)
      assertNoExplodedVecPorts(verilog, Vector("vec_in", "packed_read"))
    }
  }

  test("typed Bits bridge preserves Bool Vec depth and unrelated resize dataflow") {
    withTemporaryDirectory { directory =>
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "depth_bits_bridge_vec.v",
        new DepthBitsBridge(depth)
      )

      Vector(("input", "bits_in"), ("input", "unrelated_in"), ("output", "bits_out"))
        .foreach { case (direction, signal) =>
          assertPackedRangeAlgebra(verilog, direction, signal, "DEPTH - 1:0")
        }
      assertInternalRangeAlgebra(verilog, "values", "DEPTH - 1:0")
      assertInternalRangeAlgebra(verilog, "packed_values", "DEPTH - 1:0")
      assert(continuousAssignmentRhs(verilog, "bits_out").contains("packed_values"), verilog)

      val unrelatedDriver = continuousAssignmentRhs(verilog, "unrelated_carrier")
      assert(unrelatedDriver.contains("unrelated_in"), verilog)
      assert(!unrelatedDriver.contains("bits_in"), verilog)
      assert(continuousAssignmentRhs(verilog, "unrelated_out") == "unrelated_carrier", verilog)
      assertNoExplodedVecPorts(
        verilog,
        Vector("values", "packed_values", "bits_in", "bits_out")
      )
    }
  }

  test("typed packed bridge preserves Vec of Bundle element width") {
    withTemporaryDirectory { directory =>
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "pixel_bits_bridge_vec.v",
        new PixelBitsBridge(depth)
      )
      val expected = "24 * DEPTH - 1:0"

      assertPackedRangeAlgebra(verilog, "input", "bits_in", expected)
      assertPackedRangeAlgebra(verilog, "output", "bits_out", expected)
      assertInternalRangeAlgebra(verilog, "pixels", expected)
      assertInternalRangeAlgebra(verilog, "packed_pixels", expected)
      assert(continuousAssignmentRhs(verilog, "bits_out").contains("packed_pixels"), verilog)
      assertNoExplodedVecPorts(
        verilog,
        Vector("pixels", "packed_pixels", "bits_in", "bits_out")
      )
    }
  }

  test("parent and child retain packed typed Vec ports and parameter forwarding") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "parent_child_vec.v",
        new VecParent(width, depth)
      )

      Vector(
        ("input", "parent_vec_in"),
        ("output", "parent_vec_out"),
        ("input", "child_vec_in"),
        ("output", "child_vec_out")
      ).foreach { case (direction, signal) =>
        assertPackedPort(verilog, direction, signal, Vector("WIDTH", "DEPTH", "*"))
      }
      assert(verilog.contains("module TypedVecParent"), verilog)
      assert(verilog.contains("module TypedVecChild"), verilog)
      val normalized = compact(verilog)
      assert(normalized.contains(".WIDTH(WIDTH)"), verilog)
      assert(normalized.contains(".DEPTH(DEPTH)"), verilog)
      assertNoExplodedVecPorts(
        verilog,
        Vector("parent_vec_in", "parent_vec_out", "child_vec_in", "child_vec_out")
      )
    }
  }

  test("compound Vec depth crosses a typed child-formal boundary exactly") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val baseDepth = parameter("DEPTH", default = 4, minimum = 1, maximum = 7)
      val verilog = generate(
        directory,
        "compound_parent_child_vec.v",
        new CompoundVecParent(width, baseDepth)
      )

      val parentRange = "WIDTH * (DEPTH + 1) - 1:0"
      assertPackedRangeAlgebra(verilog, "input", "parent_vec_in", parentRange)
      assertPackedRangeAlgebra(verilog, "output", "parent_vec_out", parentRange)
      assertPackedRangeAlgebra(verilog, "input", "child_vec_in", "WIDTH * DEPTH - 1:0")
      assertPackedRangeAlgebra(verilog, "output", "child_vec_out", "WIDTH * DEPTH - 1:0")
      val normalized = compact(verilog)
      assert(
        normalized.contains(".DEPTH((DEPTH+1))") ||
          normalized.contains(".DEPTH(DEPTH+1)"),
        verilog
      )
      assertNoExplodedVecPorts(
        verilog,
        Vector("parent_vec_in", "parent_vec_out", "child_vec_in", "child_vec_out")
      )
    }
  }

  test("leafwise child Vec wiring without aggregate identity fails closed") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val rtl = directory.resolve("leafwise_parent_child_vec.v")
      val failure = MorphVerilog.tryGenerate(config(directory, rtl.getFileName.toString)) {
        new LeafwiseVecParent(width, depth)
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected leafwise hierarchy rejection, received $value")
      }

      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-CONNECTION-UNSUPPORTED"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "leafwise hierarchy wiring published partial RTL")
    }
  }

  test("Vec inside Bundle retains one packed Vec subtree") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 12)
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "vec_inside_bundle.v",
        new VecInsideBundle(width, depth)
      )

      assert(verilog.contains("bundle_in_tag"))
      assert(verilog.contains("bundle_out_tag"))
      assertPackedPort(
        verilog,
        "input",
        "bundle_in_samples",
        Vector("WIDTH", "DEPTH", "*")
      )
      assertPackedPort(
        verilog,
        "output",
        "bundle_out_samples",
        Vector("WIDTH", "DEPTH", "*")
      )
      assertNoExplodedVecPorts(
        verilog,
        Vector("bundle_in_samples", "bundle_out_samples")
      )
    }
  }

  test("Vec of Bundle uses DEPTH times the logical twenty-four-bit element") {
    withTemporaryDirectory { directory =>
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "vec_of_bundle.v",
        new VecOfBundle(depth)
      )

      assertPackedPort(verilog, "input", "pixels_in", Vector("24", "DEPTH", "*"))
      assertPackedPort(verilog, "output", "pixels_out", Vector("24", "DEPTH", "*"))
      assertPackedRangeAlgebra(verilog, "input", "pixels_in", "24 * DEPTH - 1:0")
      assertPackedRangeAlgebra(verilog, "output", "pixels_out", "24 * DEPTH - 1:0")
      assertNoExplodedVecPorts(verilog, Vector("pixels_in", "pixels_out"))
      assert(!verilog.contains("pixels_in_0_r"))
      assert(!verilog.contains("pixels_out_0_b"))
      assert(hasFixedPackedSlice(verilog, "first_red", base = 0, width = 8), verilog)
      assert(hasFixedPackedSlice(verilog, "first_green", base = 8, width = 8), verilog)
      assert(hasFixedPackedSlice(verilog, "first_blue", base = 16, width = 8), verilog)
    }
  }

  test("Vec of Bundle preserves leaf and later-element packed ordering") {
    withTemporaryDirectory { directory =>
      val depth = parameter("DEPTH", default = 5, minimum = 3, maximum = 8)
      val verilog = generate(
        directory,
        "vec_of_bundle_ordering.v",
        new VecOfBundleOrdering(depth)
      )

      assertPackedRangeAlgebra(verilog, "input", "pixels_in", "24 * DEPTH - 1:0")
      assert(hasFixedPackedSlice(verilog, "first_red", base = 0, width = 8), verilog)
      assert(hasFixedPackedSlice(verilog, "first_green", base = 8, width = 8), verilog)
      assert(hasFixedPackedSlice(verilog, "first_blue", base = 16, width = 8), verilog)
      assert(hasFixedPackedSlice(verilog, "third_blue", base = 64, width = 8), verilog)
      assertNoExplodedVecPorts(verilog, Vector("pixels_in"))
    }
  }

  test("two live overridden whole-Vec assignments fail closed") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val rtl = directory.resolve("overridden_whole_assignment_vec.v")
      val failure = MorphVerilog.tryGenerate(config(directory, rtl.getFileName.toString)) {
        new OverriddenWholeAssignment(width, depth)
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected noncontiguous Vec assignment rejection, received $value")
      }

      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-NONCONTIGUOUS"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "noncontiguous Vec assignments published partial RTL")
    }
  }

  test("removed whole-Vec assignment evidence fails closed as stale") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val rtl = directory.resolve("removed_whole_assignment_vec.v")
      val failure = MorphVerilog.tryGenerate(config(directory, rtl.getFileName.toString)) {
        new RemovedWholeAssignment(width, depth)
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected stale Vec assignment rejection, received $value")
      }

      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-STALE"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "stale Vec assignment evidence published partial RTL")
    }
  }

  test("removed auto-connect evidence cannot launder same-name replacement assignments") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val rtl = directory.resolve("removed_autoconnect_assignment_vec.v")
      val failure = MorphVerilog.tryGenerate(config(directory, rtl.getFileName.toString)) {
        new RemovedAutoConnectAssignment(width, depth)
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected stale Vec auto-connect rejection, received $value")
      }

      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-STALE"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "stale Vec auto-connect evidence published partial RTL")
    }
  }

  test("reverse-direction Vec auto-connect follows exact assignment lineage") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val verilog = generate(
        directory,
        "reverse_direction_autoconnect_vec.v",
        new ReverseDirectionAutoConnect(width, depth)
      )
      val expected = "WIDTH * DEPTH - 1:0"

      assertPackedRangeAlgebra(verilog, "input", "vec_in", expected)
      assertPackedRangeAlgebra(verilog, "output", "vec_out", expected)
      assert(continuousAssignmentRhs(verilog, "vec_out") == "vec_in", verilog)
      assert(!verilog.matches("(?s).*assign\\s+vec_in\\s*=.*"), verilog)
      assertNoExplodedVecPorts(verilog, Vector("vec_in", "vec_out"))
    }
  }

  test("mixed-direction Vec auto-connect fails closed") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val rtl = directory.resolve("mixed_direction_autoconnect_vec.v")
      val failure = MorphVerilog.tryGenerate(config(directory, rtl.getFileName.toString)) {
        new MixedDirectionAutoConnect(width, depth)
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected mixed-direction Vec rejection, received $value")
      }

      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINEAGE-UNSUPPORTED"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "mixed-direction Vec auto-connect published partial RTL")
    }
  }

  test("Vec assignment rejects incompatible symbolic depth and element geometry") {
    withTemporaryDirectory { directory =>
      val leftDepth = parameter("LEFT_DEPTH", default = 5, minimum = 1, maximum = 8)
      val rightBase = parameter("RIGHT_BASE", default = 4, minimum = 0, maximum = 7)
      val depthFailure = intercept[ParameterizedVerilogException] {
        SpinalVerilog(config(directory, "bad_depth.v")) {
          new IncompatibleDepth(leftDepth, rightBase + 1)
        }
      }
      assert(depthFailure.code == "SPINAL-ELAB-VEC-ASSIGNMENT-SHAPE-MISMATCH")
    }

    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 8)
      val depth = parameter("DEPTH", default = 4, minimum = 1, maximum = 8)
      val widthFailure = intercept[ParameterizedVerilogException] {
        SpinalVerilog(config(directory, "bad_element_width.v")) {
          new IncompatibleElementWidth(width, depth)
        }
      }
      assert(widthFailure.code == "SPINAL-ELAB-VEC-ASSIGNMENT-SHAPE-MISMATCH")
    }
  }

  test("symbolic zero and negative depth domains fail before RTL publication") {
    Vector(
      ("ZERO_DEPTH", 1, 0, 8),
      ("NEGATIVE_DEPTH", 1, -2, 8)
    ).foreach { case (name, default, minimum, maximum) =>
      withTemporaryDirectory { directory =>
        val depth = parameter(name, default, minimum, maximum)
        val failure = MorphVerilog.tryGenerate(config(directory, s"$name.v")) {
          new ConcreteWidthSymbolicDepth(depth)
        } match {
          case Left(value)  => value
          case Right(value) => fail(s"Expected illegal Vec depth failure, received $value")
        }
        assert(failure.detail.contains("SPINAL-ELAB-INT-VEC-DEPTH-DOMAIN-INVALID"))
        assert(!Files.exists(directory.resolve(s"$name.v")))
      }
    }

    withTemporaryDirectory { directory =>
      val base = parameter("BASE_DEPTH", default = 4, minimum = 1, maximum = 8)
      val failure = MorphVerilog.tryGenerate(config(directory, "zero_expression.v")) {
        new ConcreteWidthSymbolicDepth(base - 1)
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"Expected illegal Vec depth expression failure, received $value")
      }
      assert(failure.detail.contains("SPINAL-ELAB-INT-VEC-DEPTH-DOMAIN-INVALID"))
      assert(!Files.exists(directory.resolve("zero_expression.v")))
    }
  }

  test("ordinary Int and ElabInt literal Vec construction have byte-identical RTL") {
    withTemporaryDirectory { directory =>
      val ordinaryDirectory = directory.resolve("ordinary")
      val typedDirectory = directory.resolve("typed-literal")
      Files.createDirectories(ordinaryDirectory)
      Files.createDirectories(typedDirectory)

      SpinalVerilog(config(ordinaryDirectory, "ConcreteVecParity.v")) {
        new ConcreteParity(useTypedLiteral = false)
      }
      SpinalVerilog(config(typedDirectory, "ConcreteVecParity.v")) {
        new ConcreteParity(useTypedLiteral = true)
      }

      val ordinary = Files.readAllBytes(ordinaryDirectory.resolve("ConcreteVecParity.v"))
      val typed = Files.readAllBytes(typedDirectory.resolve("ConcreteVecParity.v"))
      assert(java.util.Arrays.equals(ordinary, typed))
      val concreteText = new String(ordinary, StandardCharsets.UTF_8)
      assert(!concreteText.contains("parameter integer"))
      assert(concreteText.contains("vec_in_0"))
      assert(concreteText.contains("vec_out_3"))
    }
  }

  test("one parameterized Vec module passes strict V2001 at depths 1 3 5 and 8") {
    withTemporaryDirectory { directory =>
      val generated = generatePackedPorts(directory)
      val rtl = directory.resolve("typed_parameterized_vec.v")
      val originalBytes = Files.readAllBytes(rtl)
      val verilog = generated._2

      assert(!verilog.matches("(?s).*\\blogic\\b.*"))
      assert(!verilog.contains("always_comb"))
      assert(!verilog.contains("]["))
      assertNoExplodedVecPorts(verilog, Vector("vec_in", "vec_out", "internal_vec"))
      assert(hasCanonicalSlice(compact(verilog), "element_zero", element = 0), verilog)
      assert(!compact(verilog).contains("vec_in_0"), verilog)

      val specializations = Vector(
        3 -> 1,
        5 -> 3,
        7 -> 5,
        9 -> 8
      )
      specializations.foreach { case (selectedWidth, selectedDepth) =>
        lint(
          directory,
          rtl,
          "TypedParameterizedVec",
          selectedWidth,
          selectedDepth
        )
        simulatePackedVec(directory, rtl, selectedWidth, selectedDepth)
        synthesize(
          directory,
          rtl,
          "TypedParameterizedVec",
          selectedWidth,
          selectedDepth,
          Vector(
            PortExpectation("vec_in", "input", selectedWidth * selectedDepth),
            PortExpectation("vec_out", "output", selectedWidth * selectedDepth),
            PortExpectation("index", "input", 3),
            PortExpectation("element_zero", "output", selectedWidth),
            PortExpectation("selected", "output", selectedWidth)
          )
        )
        assert(
          java.util.Arrays.equals(originalBytes, Files.readAllBytes(rtl)),
          "a concrete specialization rewrote the one parameterized Vec module"
        )
      }
    }
  }

  test("one typed packed bridge module passes depths 1 3 5 and 8") {
    withTemporaryDirectory { directory =>
      val boolDirectory = directory.resolve("bool")
      Files.createDirectories(boolDirectory)
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val boolRtl = boolDirectory.resolve("depth_bits_bridge_vec.v")
      generate(
        boolDirectory,
        boolRtl.getFileName.toString,
        new DepthBitsBridge(depth)
      )
      val originalBytes = Files.readAllBytes(boolRtl)

      Vector(1, 3, 5, 8).foreach { selectedDepth =>
        val parameters = Vector("DEPTH" -> selectedDepth)
        lintWithParameters(
          boolDirectory,
          boolRtl,
          "DepthBitsBridgeVec",
          parameters
        )
        simulateDepthBitsBridge(boolDirectory, boolRtl, selectedDepth)
        synthesizeWithParameters(
          boolDirectory,
          boolRtl,
          "DepthBitsBridgeVec",
          parameters,
          Vector(
            PortExpectation("bits_in", "input", selectedDepth),
            PortExpectation("bits_out", "output", selectedDepth),
            PortExpectation("unrelated_in", "input", selectedDepth),
            PortExpectation("unrelated_out", "output", 8)
          )
        )
        assert(
          java.util.Arrays.equals(originalBytes, Files.readAllBytes(boolRtl)),
          "a concrete specialization rewrote the one typed packed bridge module"
        )
      }

      val pixelDirectory = directory.resolve("pixel")
      Files.createDirectories(pixelDirectory)
      val pixelDepth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val pixelRtl = pixelDirectory.resolve("pixel_bits_bridge_vec.v")
      generate(
        pixelDirectory,
        pixelRtl.getFileName.toString,
        new PixelBitsBridge(pixelDepth)
      )
      val selectedDepth = 5
      val parameters = Vector("DEPTH" -> selectedDepth)
      lintWithParameters(
        pixelDirectory,
        pixelRtl,
        "PixelBitsBridgeVec",
        parameters
      )
      simulatePixelBitsBridge(pixelDirectory, pixelRtl, selectedDepth)
      synthesizeWithParameters(
        pixelDirectory,
        pixelRtl,
        "PixelBitsBridgeVec",
        parameters,
        Vector(
          PortExpectation("bits_in", "input", 24 * selectedDepth),
          PortExpectation("bits_out", "output", 24 * selectedDepth)
        )
      )
    }
  }

  test("expression write hierarchy and composite Vec fixtures pass strict V2001") {
    withTemporaryDirectory { directory =>
      val expressionDirectory = directory.resolve("expression")
      Files.createDirectories(expressionDirectory)
      val expressionWidth = parameter("WIDTH", default = 5, minimum = 1, maximum = 12)
      val expressionDepth = parameter("DEPTH", default = 4, minimum = 1, maximum = 7)
      val expressionRtl = expressionDirectory.resolve("expression_shape_vec.v")
      generate(
        expressionDirectory,
        expressionRtl.getFileName.toString,
        new ExpressionShape(expressionWidth, expressionDepth)
      )
      val expressionParameters = Vector("WIDTH" -> 4, "DEPTH" -> 2)
      lintWithParameters(
        expressionDirectory,
        expressionRtl,
        "ExpressionShapeVec",
        expressionParameters
      )
      synthesizeWithParameters(
        expressionDirectory,
        expressionRtl,
        "ExpressionShapeVec",
        expressionParameters,
        Vector(
          PortExpectation("vec_in", "input", 15),
          PortExpectation("vec_out", "output", 15)
        )
      )
      simulateExpressionVec(expressionDirectory, expressionRtl, width = 4, depth = 2)

      val writeDirectory = directory.resolve("dynamic-write")
      Files.createDirectories(writeDirectory)
      val writeWidth = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val writeDepth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val writeRtl = writeDirectory.resolve("dynamic_write_vec.v")
      generate(
        writeDirectory,
        writeRtl.getFileName.toString,
        new DynamicWrite(writeWidth, writeDepth)
      )
      val writeParameters = Vector("WIDTH" -> 5, "DEPTH" -> 3)
      lintWithParameters(writeDirectory, writeRtl, "DynamicWriteVec", writeParameters)
      synthesizeWithParameters(
        writeDirectory,
        writeRtl,
        "DynamicWriteVec",
        writeParameters,
        Vector(
          PortExpectation("vec_in", "input", 15),
          PortExpectation("vec_out", "output", 15),
          PortExpectation("index", "input", 3),
          PortExpectation("write_data", "input", 5)
        )
      )
      simulateDynamicWriteVec(writeDirectory, writeRtl, width = 5, depth = 3)

      val hierarchyDirectory = directory.resolve("hierarchy")
      Files.createDirectories(hierarchyDirectory)
      val hierarchyWidth = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
      val hierarchyDepth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val hierarchyRtl = hierarchyDirectory.resolve("parent_child_vec.v")
      generate(
        hierarchyDirectory,
        hierarchyRtl.getFileName.toString,
        new VecParent(hierarchyWidth, hierarchyDepth)
      )
      val hierarchyParameters = Vector("WIDTH" -> 5, "DEPTH" -> 3)
      lintWithParameters(
        hierarchyDirectory,
        hierarchyRtl,
        "TypedVecParent",
        hierarchyParameters
      )
      synthesizeWithParameters(
        hierarchyDirectory,
        hierarchyRtl,
        "TypedVecParent",
        hierarchyParameters,
        Vector(
          PortExpectation("parent_vec_in", "input", 15),
          PortExpectation("parent_vec_out", "output", 15)
        )
      )
      simulateHierarchyVec(hierarchyDirectory, hierarchyRtl, width = 5, depth = 3)

      val bundleDirectory = directory.resolve("vec-of-bundle")
      Files.createDirectories(bundleDirectory)
      val bundleDepth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val bundleRtl = bundleDirectory.resolve("vec_of_bundle.v")
      generate(
        bundleDirectory,
        bundleRtl.getFileName.toString,
        new VecOfBundle(bundleDepth)
      )
      val bundleParameters = Vector("DEPTH" -> 3)
      lintWithParameters(bundleDirectory, bundleRtl, "VecOfBundle", bundleParameters)
      synthesizeWithParameters(
        bundleDirectory,
        bundleRtl,
        "VecOfBundle",
        bundleParameters,
        Vector(
          PortExpectation("pixels_in", "input", 72),
          PortExpectation("pixels_out", "output", 72),
          PortExpectation("first_red", "output", 8),
          PortExpectation("first_green", "output", 8),
          PortExpectation("first_blue", "output", 8)
        )
      )
      simulateVecOfBundle(bundleDirectory, bundleRtl, depth = 3)
    }
  }

  test("Vec is packed while Mem remains an unpacked Verilog memory") {
    withTemporaryDirectory { directory =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 12)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val rtl = directory.resolve("typed_vec_and_mem.v")
      val verilog = generate(
        directory,
        rtl.getFileName.toString,
        new VecAndMem(width, depth)
      )

      assertPackedPort(verilog, "input", "vec_in", Vector("WIDTH", "DEPTH", "*"))
      assertPackedPort(verilog, "output", "vec_out", Vector("WIDTH", "DEPTH", "*"))
      assertInternalPackedVector(verilog, "vec_storage", Vector("WIDTH", "DEPTH", "*"))
      val memoryDeclaration = declarationContaining(verilog, "mem_storage")
      val normalizedMemory = compact(memoryDeclaration)
      assert(normalizedMemory.startsWith("reg["), memoryDeclaration)
      assert(normalizedMemory.contains("WIDTH"), memoryDeclaration)
      assert(normalizedMemory.contains("mem_storage[0:"), memoryDeclaration)
      assert(normalizedMemory.contains("DEPTH"), memoryDeclaration)
      assert(!normalizedMemory.contains("WIDTH*DEPTH"), memoryDeclaration)

      Vector(1, 3, 5, 8).foreach { selectedDepth =>
        lint(directory, rtl, "TypedVecAndMem", 5, selectedDepth)
        synthesize(directory, rtl, "TypedVecAndMem", 5, selectedDepth)
      }
    }
  }

  private def generatePackedPorts(
      directory: Path
  ): (MorphSingleSourceVerilogReport, String) = {
    val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 16)
    val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
    val useConfig = config(directory, "typed_parameterized_vec.v")
    val report = MorphVerilog(useConfig)(new PackedPorts(width, depth))
    report -> read(directory.resolve("typed_parameterized_vec.v"))
  }

  private def generate(
      directory: Path,
      fileName: String,
      component: => Component
  ): String = {
    MorphVerilog(config(directory, fileName))(component)
    read(directory.resolve(fileName))
  }

  private def config(directory: Path, fileName: String): SpinalConfig = {
    val value = SpinalConfig(targetDirectory = directory.toString)
    value.netlistFileName = fileName
    value
  }

  private def parameter(
      name: String,
      default: Int,
      minimum: Int,
      maximum: Int
  ): ElabInt =
    HdlInt
      .param(
        name,
        default = BigInt(default),
        min = BigInt(minimum),
        max = BigInt(maximum)
      )
      .asElabInt

  private def assertPackedPort(
      verilog: String,
      direction: String,
      signal: String,
      requiredTokens: Vector[String]
  ): Unit = {
    val range = packedRange(verilog, direction, signal)
    val normalized = compact(range)
    requiredTokens.foreach { token =>
      assert(normalized.contains(token), s"$signal range '$range' lacks '$token'")
    }
    assert(normalized.endsWith(":0"), s"$signal range is not zero-based: $range")
  }

  private def packedRange(
      verilog: String,
      direction: String,
      signal: String
  ): String = {
    val matches = PortDeclaration
      .findAllMatchIn(verilog)
      .filter(value => value.group(1) == direction && value.group(3) == signal)
      .toVector
    assert(matches.size == 1, s"expected one $direction packed port $signal, found ${matches.size}\n$verilog")
    matches.head.group(2)
  }

  private sealed trait Algebra
  private final case class AlgebraAtom(value: String) extends Algebra
  private final case class AlgebraSum(terms: Vector[Algebra]) extends Algebra
  private final case class AlgebraProduct(factors: Vector[Algebra]) extends Algebra
  private final case class AlgebraDifference(left: Algebra, right: Algebra) extends Algebra

  /** Compares range algebra, not printer parentheses. Addition and
    * multiplication are flattened and sorted so harmless factor ordering is
    * ignored, while constants, operators, and symbolic roots remain exact.
    */
  private def assertRangeAlgebra(actual: String, expected: String): Unit = {
    def splitRange(value: String): (String, String) = {
      val colon = value.lastIndexOf(':')
      assert(colon > 0 && colon < value.length - 1, s"invalid packed range '$value'")
      value.substring(0, colon) -> value.substring(colon + 1)
    }
    val actualParts = splitRange(actual)
    val expectedParts = splitRange(expected)
    val actualAlgebra = parseAlgebra(actualParts._1) -> parseAlgebra(actualParts._2)
    val expectedAlgebra = parseAlgebra(expectedParts._1) -> parseAlgebra(expectedParts._2)
    assert(
      actualAlgebra == expectedAlgebra,
      s"packed range algebra '$actual' did not equal '$expected': $actualAlgebra != $expectedAlgebra"
    )
  }

  private def assertPackedRangeAlgebra(
      verilog: String,
      direction: String,
      signal: String,
      expected: String
  ): Unit =
    assertRangeAlgebra(packedRange(verilog, direction, signal), expected)

  private def assertInternalRangeAlgebra(
      verilog: String,
      signal: String,
      expected: String
  ): Unit = {
    val declaration = declarationContaining(verilog, signal)
    val open = declaration.indexOf('[')
    val close = declaration.indexOf(']', open + 1)
    assert(open >= 0 && close > open, s"missing packed range for $signal: $declaration")
    assertRangeAlgebra(declaration.substring(open + 1, close), expected)
  }

  private def parseAlgebra(source: String): Algebra = {
    final class Parser {
      private var offset = 0

      def parse(): Algebra = {
        val value = parseSum()
        skipSpace()
        assert(offset == source.length, s"unsupported algebra at '${source.substring(offset)}' in '$source'")
        value
      }

      private def parseSum(): Algebra = {
        var value = parseProduct()
        var continue = true
        while (continue) {
          skipSpace()
          peek() match {
            case Some('+') =>
              offset += 1
              value = add(value, parseProduct())
            case Some('-') =>
              offset += 1
              value = AlgebraDifference(value, parseProduct())
            case _ => continue = false
          }
        }
        value
      }

      private def parseProduct(): Algebra = {
        var value = parseAtom()
        var continue = true
        while (continue) {
          skipSpace()
          peek() match {
            case Some('*') =>
              offset += 1
              value = multiply(value, parseAtom())
            case _ => continue = false
          }
        }
        value
      }

      private def parseAtom(): Algebra = {
        skipSpace()
        peek() match {
          case Some('(') =>
            offset += 1
            val value = parseSum()
            skipSpace()
            assert(peek().contains(')'), s"unclosed parenthesis in '$source'")
            offset += 1
            value
          case Some(value) if value.isDigit =>
            AlgebraAtom(readWhile(_.isDigit))
          case Some(value) if value.isLetter || value == '_' || value == '$' =>
            AlgebraAtom(readWhile(character => character.isLetterOrDigit || character == '_' || character == '$'))
          case _ => fail(s"unsupported packed-range algebra '$source' at offset $offset")
        }
      }

      private def readWhile(predicate: Char => Boolean): String = {
        val start = offset
        while (offset < source.length && predicate(source.charAt(offset))) offset += 1
        source.substring(start, offset)
      }

      private def skipSpace(): Unit =
        while (offset < source.length && source.charAt(offset).isWhitespace) offset += 1

      private def peek(): Option[Char] =
        if (offset < source.length) Some(source.charAt(offset)) else None
    }

    new Parser().parse()
  }

  private def add(left: Algebra, right: Algebra): Algebra = {
    (left, right) match {
      case (AlgebraAtom(a), AlgebraAtom(b)) if a.forall(_.isDigit) && b.forall(_.isDigit) =>
        return AlgebraAtom((BigInt(a) + BigInt(b)).toString)
      case _ =>
    }
    val terms = (left match {
      case AlgebraSum(values) => values
      case value              => Vector(value)
    }) ++ (right match {
      case AlgebraSum(values) => values
      case value              => Vector(value)
    })
    AlgebraSum(terms.sortBy(_.toString))
  }

  private def multiply(left: Algebra, right: Algebra): Algebra = {
    (left, right) match {
      case (AlgebraAtom(a), AlgebraAtom(b)) if a.forall(_.isDigit) && b.forall(_.isDigit) =>
        return AlgebraAtom((BigInt(a) * BigInt(b)).toString)
      case _ =>
    }
    val factors = (left match {
      case AlgebraProduct(values) => values
      case value                  => Vector(value)
    }) ++ (right match {
      case AlgebraProduct(values) => values
      case value                  => Vector(value)
    })
    AlgebraProduct(factors.sortBy(_.toString))
  }

  private def assertIndexedSliceUsesAddress(
      verilog: String,
      vector: String,
      address: String,
      width: String
  ): Unit = {
    val candidates = verilog
      .split("\\r?\\n")
      .map(compact)
      .filter(line => line.contains(s"$vector[") && line.contains("+:"))
      .toVector
    assert(candidates.nonEmpty, s"missing indexed slice of $vector\n$verilog")
    val slices = candidates.map { line =>
      val start = line.indexOf(s"$vector[") + vector.length + 1
      val end = line.indexOf("+:", start)
      assert(end > start, line)
      line.substring(start, end)
    }
    assert(
      slices.exists(slice => slice.contains(address) && slice.contains(width)),
      s"no $vector slice retained address $address and width $width: ${slices.mkString(", ")}\n$verilog"
    )
  }

  private def proceduralAssignmentsTo(
      verilog: String,
      signal: String
  ): Vector[String] = {
    val pattern =
      (s"\\b${java.util.regex.Pattern.quote(signal)}(?:\\[[^;]+\\])?" +
        "(?:<=|=)").r
    verilog
      .split("\\r?\\n")
      .map(compact)
      .collect { case line if pattern.findFirstIn(line).nonEmpty => line }
      .toVector
  }

  private def continuousAssignmentRhs(verilog: String, signal: String): String = {
    val assignment =
      (s"(?m)^\\s*assign\\s+${java.util.regex.Pattern.quote(signal)}" +
        "\\s*=\\s*([^;]+)\\s*;").r
        .findFirstMatchIn(verilog)
        .getOrElse(fail(s"missing continuous assignment for $signal\n$verilog"))
    assignment.group(1).trim
  }

  private def assertInternalPackedVector(
      verilog: String,
      signal: String,
      requiredTokens: Vector[String]
  ): Unit = {
    val declaration = declarationContaining(verilog, signal)
    val normalized = compact(declaration)
    assert(
      normalized.startsWith("wire[") || normalized.startsWith("reg["),
      s"$signal is not a packed internal declaration: $declaration"
    )
    requiredTokens.foreach(token => assert(normalized.contains(token), declaration))
    assert(!normalized.contains(s"$signal[0:"), declaration)
  }

  private def assertSymbolicElementDriver(
      verilog: String,
      output: String,
      widthToken: String
  ): Unit = {
    val assignment = (s"(?m)^\\s*assign\\s+${java.util.regex.Pattern.quote(output)}" +
      "\\s*=\\s*([^;]+)\\s*;").r
      .findFirstMatchIn(verilog)
      .getOrElse(fail(s"missing continuous assignment for $output\n$verilog"))
    val rightHandSide = assignment.group(1).trim
    val symbolicSource =
      if (rightHandSide.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
        compact(declarationContaining(verilog, rightHandSide))
      } else compact(rightHandSide)
    assert(
      symbolicSource.contains(widthToken),
      s"$output is driven through witness-width storage '$symbolicSource'\n$verilog"
    )
  }

  private def assertNoWitnessElementDeclarations(
      verilog: String,
      witnessWidth: Int
  ): Unit = {
    val witnessRange = s"[${witnessWidth - 1}:0]"
    val offending = verilog
      .split("\\r?\\n")
      .map(_.trim)
      .filter(line =>
        (line.startsWith("wire") || line.startsWith("reg")) &&
          compact(line).contains(witnessRange)
      )
      .toVector
    assert(
      offending.isEmpty,
      s"dynamic Vec lowering retained witness-width declarations: ${offending.mkString(", ")}\n$verilog"
    )
  }

  private def declarationContaining(verilog: String, signal: String): String = {
    val declaration = verilog
      .split("\\r?\\n")
      .filter(line => line.matches(s".*\\b${java.util.regex.Pattern.quote(signal)}\\b.*"))
      .find(line => line.trim.startsWith("wire") || line.trim.startsWith("reg"))
    declaration.getOrElse(fail(s"missing declaration for $signal\n$verilog"))
  }

  private def assertNoExplodedVecPorts(
      verilog: String,
      vectorNames: Vector[String]
  ): Unit = {
    vectorNames.foreach { name =>
      assert(!verilog.matches(s"(?s).*\\b${java.util.regex.Pattern.quote(name)}_[0-9]+(?:_|\\b).*"), verilog)
    }
  }

  private def hasCanonicalSlice(
      compactVerilog: String,
      output: String,
      element: Int
  ): Boolean = {
    val assignPrefix = s"assign$output="
    val line = compactVerilog
      .split(";")
      .find(_.startsWith(assignPrefix))
      .getOrElse("")
    val low = if (element == 0) "0" else s"($element*WIDTH)"
    val highFactor = element + 1
    val accepted = Vector(
      s"vec_in[(($highFactor*WIDTH)-1):$low]",
      s"vec_in[(($highFactor*WIDTH)-1):((${element}*WIDTH))]",
      s"vec_in[($element*WIDTH)+:WIDTH]",
      if (element == 0) "vec_in[(0)+:WIDTH]" else "",
      if (element == 0) "vec_in[WIDTH-1:0]" else ""
    ).filter(_.nonEmpty)
    accepted.exists(line.contains)
  }

  private def hasFixedPackedSlice(
      verilog: String,
      output: String,
      base: Int,
      width: Int
  ): Boolean = {
    val normalized = compact(verilog)
    normalized
      .split(";")
      .find(_.startsWith(s"assign$output="))
      .exists { line =>
        val start = line.indexOf("pixels_in[")
        val indexed = if (start < 0) "" else line.substring(start + "pixels_in[".length)
        val partSelect = indexed.indexOf("+:")
        val close = indexed.indexOf(']', partSelect + 2)
        if (partSelect <= 0 || close <= partSelect) false
        else {
          val actualBase = parseAlgebra(indexed.substring(0, partSelect))
          val actualWidth = parseAlgebra(indexed.substring(partSelect + 2, close))
          actualBase == parseAlgebra(base.toString) &&
          actualWidth == parseAlgebra(width.toString)
        }
      }
  }

  private def lint(
      directory: Path,
      rtl: Path,
      top: String,
      width: Int,
      depth: Int
  ): Unit =
    lintWithParameters(
      directory,
      rtl,
      top,
      Vector("WIDTH" -> width, "DEPTH" -> depth)
    )

  private def lintWithParameters(
      directory: Path,
      rtl: Path,
      top: String,
      parameters: Vector[(String, Int)]
  ): Unit = {
    if (!commandAvailable("verilator")) return
    val result = run(
      directory,
      Seq(
        "verilator",
        "--lint-only",
        "--language",
        "1364-2001",
        "-Wall",
        "-Wno-DECLFILENAME",
        "-Wno-WIDTH",
        "-Wno-UNUSED",
        "--top-module",
        top
      ) ++ parameters.map { case (name, value) => s"-G$name=$value" } ++
        Seq(rtl.toString)
    )
    val specialization = parameters.map { case (name, value) => s"$name=$value" }.mkString(" ")
    assert(result._1 == 0, s"V2001 lint failed for $top $specialization:\n${result._2}")
  }

  private def simulatePackedVec(
      directory: Path,
      rtl: Path,
      width: Int,
      depth: Int
  ): Unit = {
    val top = s"TypedParameterizedVecW${width}D${depth}Tb"
    val testbench = directory.resolve(s"$top.v")
    val executable = directory.resolve(s"$top.out")
    val source =
      s"""module $top;
         |  localparam integer WIDTH = $width;
         |  localparam integer DEPTH = $depth;
         |  localparam integer TOTAL_WIDTH = WIDTH * DEPTH;
         |  reg [TOTAL_WIDTH-1:0] vec_in;
         |  wire [TOTAL_WIDTH-1:0] vec_out;
         |  reg [2:0] index;
         |  wire [WIDTH-1:0] element_zero;
         |  wire [WIDTH-1:0] selected;
         |  integer i;
         |
         |  TypedParameterizedVec #(
         |    .WIDTH(WIDTH),
         |    .DEPTH(DEPTH)
         |  ) dut (
         |    .vec_in(vec_in),
         |    .vec_out(vec_out),
         |    .index(index),
         |    .element_zero(element_zero),
         |    .selected(selected)
         |  );
         |
         |  initial begin
         |    vec_in = {TOTAL_WIDTH{1'b0}};
         |    index = 3'b000;
         |    for (i = 0; i < DEPTH; i = i + 1)
         |      vec_in[(i * WIDTH) +: WIDTH] = i + 1;
         |    #1;
         |    if (vec_out !== vec_in) begin
         |      $$display("FAIL packed assignment WIDTH=%0d DEPTH=%0d", WIDTH, DEPTH);
         |      $$finish(2);
         |    end
         |    if (element_zero !== vec_in[0 +: WIDTH]) begin
         |      $$display("FAIL constant index WIDTH=%0d DEPTH=%0d", WIDTH, DEPTH);
         |      $$finish(2);
         |    end
         |    for (i = 0; i < DEPTH; i = i + 1) begin
         |      index = i;
         |      #1;
         |      if (selected !== vec_in[(i * WIDTH) +: WIDTH]) begin
         |        $$display("FAIL dynamic index %0d WIDTH=%0d DEPTH=%0d", i, WIDTH, DEPTH);
         |        $$finish(2);
         |      end
         |    end
         |    $$display("PASS typed Vec WIDTH=%0d DEPTH=%0d", WIDTH, DEPTH);
         |    $$finish;
         |  end
         |endmodule
         |""".stripMargin
    Files.write(testbench, source.getBytes(StandardCharsets.UTF_8))

    val compileResult = run(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-Wall",
        "-s",
        top,
        "-o",
        executable.toString,
        rtl.toString,
        testbench.toString
      )
    )
    assert(compileResult._1 == 0, compileResult._2)
    val simulationResult = run(directory, Seq("vvp", executable.toString))
    assert(simulationResult._1 == 0, simulationResult._2)
    assert(
      simulationResult._2.contains(s"PASS typed Vec WIDTH=$width DEPTH=$depth"),
      simulationResult._2
    )
  }

  private def simulateDepthBitsBridge(
      directory: Path,
      rtl: Path,
      depth: Int
  ): Unit = {
    val top = s"DepthBitsBridgeD${depth}Tb"
    val source =
      s"""module $top;
         |  localparam integer DEPTH = $depth;
         |  reg [DEPTH-1:0] bits_in;
         |  wire [DEPTH-1:0] bits_out;
         |  reg [DEPTH-1:0] unrelated_in;
         |  wire [7:0] unrelated_out;
         |  reg [7:0] unrelated_expected;
         |  DepthBitsBridgeVec #(.DEPTH(DEPTH)) dut (
         |    .bits_in(bits_in),
         |    .bits_out(bits_out),
         |    .unrelated_in(unrelated_in),
         |    .unrelated_out(unrelated_out)
         |  );
         |  initial begin
         |    bits_in = {DEPTH{1'b1}};
         |    unrelated_in = {DEPTH{1'b0}};
         |    unrelated_in[0] = 1'b1;
         |    unrelated_expected = 8'b0;
         |    unrelated_expected[DEPTH-1:0] = unrelated_in;
         |    #1;
         |    if (bits_out !== bits_in || unrelated_out !== unrelated_expected) begin
         |      $$display("FAIL depth Bits bridge DEPTH=%0d", DEPTH);
         |      $$finish(2);
         |    end
         |    $$display("PASS depth Bits bridge DEPTH=%0d", DEPTH);
         |    $$finish;
         |  end
         |endmodule
         |""".stripMargin
    compileAndSimulate(
      directory,
      rtl,
      top,
      source,
      s"PASS depth Bits bridge DEPTH=$depth"
    )
  }

  private def simulatePixelBitsBridge(
      directory: Path,
      rtl: Path,
      depth: Int
  ): Unit = {
    val top = s"PixelBitsBridgeD${depth}Tb"
    val source =
      s"""module $top;
         |  localparam integer DEPTH = $depth;
         |  localparam integer TOTAL_WIDTH = DEPTH * 24;
         |  reg [TOTAL_WIDTH-1:0] bits_in;
         |  wire [TOTAL_WIDTH-1:0] bits_out;
         |  integer i;
         |  PixelBitsBridgeVec #(.DEPTH(DEPTH)) dut (
         |    .bits_in(bits_in),
         |    .bits_out(bits_out)
         |  );
         |  initial begin
         |    bits_in = {TOTAL_WIDTH{1'b0}};
         |    for (i = 0; i < DEPTH * 3; i = i + 1)
         |      bits_in[(i * 8) +: 8] = i + 1;
         |    #1;
         |    if (bits_out !== bits_in) begin
         |      $$display("FAIL Pixel Bits bridge DEPTH=%0d", DEPTH);
         |      $$finish(2);
         |    end
         |    $$display("PASS Pixel Bits bridge DEPTH=%0d", DEPTH);
         |    $$finish;
         |  end
         |endmodule
         |""".stripMargin
    compileAndSimulate(
      directory,
      rtl,
      top,
      source,
      s"PASS Pixel Bits bridge DEPTH=$depth"
    )
  }

  private def simulateExpressionVec(
      directory: Path,
      rtl: Path,
      width: Int,
      depth: Int
  ): Unit = {
    val top = s"ExpressionShapeVecW${width}D${depth}Tb"
    val source =
      s"""module $top;
         |  localparam integer WIDTH = $width;
         |  localparam integer DEPTH = $depth;
         |  localparam integer TOTAL_WIDTH = (WIDTH + 1) * (DEPTH + 1);
         |  reg [TOTAL_WIDTH-1:0] vec_in;
         |  wire [TOTAL_WIDTH-1:0] vec_out;
         |  ExpressionShapeVec #(.WIDTH(WIDTH), .DEPTH(DEPTH)) dut (
         |    .vec_in(vec_in),
         |    .vec_out(vec_out)
         |  );
         |  initial begin
         |    vec_in = {TOTAL_WIDTH{1'b1}};
         |    #1;
         |    if (vec_out !== vec_in) begin
         |      $$display("FAIL expression Vec");
         |      $$finish(2);
         |    end
         |    $$display("PASS expression Vec");
         |    $$finish;
         |  end
         |endmodule
         |""".stripMargin
    compileAndSimulate(directory, rtl, top, source, "PASS expression Vec")
  }

  private def simulateDynamicWriteVec(
      directory: Path,
      rtl: Path,
      width: Int,
      depth: Int
  ): Unit = {
    val top = s"DynamicWriteVecW${width}D${depth}Tb"
    val source =
      s"""module $top;
         |  localparam integer WIDTH = $width;
         |  localparam integer DEPTH = $depth;
         |  localparam integer TOTAL_WIDTH = WIDTH * DEPTH;
         |  reg [TOTAL_WIDTH-1:0] vec_in;
         |  wire [TOTAL_WIDTH-1:0] vec_out;
         |  reg [2:0] index;
         |  reg [WIDTH-1:0] write_data;
         |  reg [TOTAL_WIDTH-1:0] expected;
         |  integer i;
         |  DynamicWriteVec #(.WIDTH(WIDTH), .DEPTH(DEPTH)) dut (
         |    .vec_in(vec_in),
         |    .vec_out(vec_out),
         |    .index(index),
         |    .write_data(write_data)
         |  );
         |  initial begin
         |    vec_in = {TOTAL_WIDTH{1'b0}};
         |    for (i = 0; i < DEPTH; i = i + 1)
         |      vec_in[(i * WIDTH) +: WIDTH] = i + 1;
         |    index = 2;
         |    write_data = {WIDTH{1'b1}};
         |    expected = vec_in;
         |    expected[(index * WIDTH) +: WIDTH] = write_data;
         |    #1;
         |    if (vec_out !== expected) begin
         |      $$display("FAIL dynamic Vec write");
         |      $$finish(2);
         |    end
         |    index = 7;
         |    #1;
         |    if (vec_out !== vec_in) begin
         |      $$display("FAIL guarded dynamic Vec write");
         |      $$finish(2);
         |    end
         |    $$display("PASS dynamic Vec write");
         |    $$finish;
         |  end
         |endmodule
         |""".stripMargin
    compileAndSimulate(directory, rtl, top, source, "PASS dynamic Vec write")
  }

  private def simulateHierarchyVec(
      directory: Path,
      rtl: Path,
      width: Int,
      depth: Int
  ): Unit = {
    val top = s"TypedVecParentW${width}D${depth}Tb"
    val source =
      s"""module $top;
         |  localparam integer WIDTH = $width;
         |  localparam integer DEPTH = $depth;
         |  localparam integer TOTAL_WIDTH = WIDTH * DEPTH;
         |  reg [TOTAL_WIDTH-1:0] parent_vec_in;
         |  wire [TOTAL_WIDTH-1:0] parent_vec_out;
         |  TypedVecParent #(.WIDTH(WIDTH), .DEPTH(DEPTH)) dut (
         |    .parent_vec_in(parent_vec_in),
         |    .parent_vec_out(parent_vec_out)
         |  );
         |  initial begin
         |    parent_vec_in = {TOTAL_WIDTH{1'b1}};
         |    #1;
         |    if (parent_vec_out !== parent_vec_in) begin
         |      $$display("FAIL hierarchy Vec");
         |      $$finish(2);
         |    end
         |    $$display("PASS hierarchy Vec");
         |    $$finish;
         |  end
         |endmodule
         |""".stripMargin
    compileAndSimulate(directory, rtl, top, source, "PASS hierarchy Vec")
  }

  private def simulateVecOfBundle(
      directory: Path,
      rtl: Path,
      depth: Int
  ): Unit = {
    val top = s"VecOfBundleD${depth}Tb"
    val source =
      s"""module $top;
         |  localparam integer DEPTH = $depth;
         |  localparam integer TOTAL_WIDTH = 24 * DEPTH;
         |  reg [TOTAL_WIDTH-1:0] pixels_in;
         |  wire [TOTAL_WIDTH-1:0] pixels_out;
         |  wire [7:0] first_red;
         |  wire [7:0] first_green;
         |  wire [7:0] first_blue;
         |  integer i;
         |  VecOfBundle #(.DEPTH(DEPTH)) dut (
         |    .pixels_in(pixels_in),
         |    .pixels_out(pixels_out),
         |    .first_red(first_red),
         |    .first_green(first_green),
         |    .first_blue(first_blue)
         |  );
         |  initial begin
         |    pixels_in = {TOTAL_WIDTH{1'b0}};
         |    for (i = 0; i < DEPTH * 3; i = i + 1)
         |      pixels_in[(i * 8) +: 8] = i + 1;
         |    #1;
         |    if (pixels_out !== pixels_in ||
         |        first_red !== pixels_in[7:0] ||
         |        first_green !== pixels_in[15:8] ||
         |        first_blue !== pixels_in[23:16]) begin
         |      $$display("FAIL Vec of Bundle");
         |      $$finish(2);
         |    end
         |    $$display("PASS Vec of Bundle");
         |    $$finish;
         |  end
         |endmodule
         |""".stripMargin
    compileAndSimulate(directory, rtl, top, source, "PASS Vec of Bundle")
  }

  private def compileAndSimulate(
      directory: Path,
      rtl: Path,
      top: String,
      source: String,
      passMarker: String
  ): Unit = {
    if (!commandAvailable("iverilog") || !commandAvailable("vvp")) return
    val testbench = directory.resolve(s"$top.v")
    val executable = directory.resolve(s"$top.out")
    Files.write(testbench, source.getBytes(StandardCharsets.UTF_8))
    val compileResult = run(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-Wall",
        "-s",
        top,
        "-o",
        executable.toString,
        rtl.toString,
        testbench.toString
      )
    )
    assert(compileResult._1 == 0, compileResult._2)
    val simulationResult = run(directory, Seq("vvp", executable.toString))
    assert(simulationResult._1 == 0, simulationResult._2)
    assert(simulationResult._2.contains(passMarker), simulationResult._2)
  }

  private def synthesize(
      directory: Path,
      rtl: Path,
      top: String,
      width: Int,
      depth: Int,
      expectedPorts: Vector[PortExpectation] = Vector.empty
  ): Unit =
    synthesizeWithParameters(
      directory,
      rtl,
      top,
      Vector("WIDTH" -> width, "DEPTH" -> depth),
      expectedPorts
    )

  private def synthesizeWithParameters(
      directory: Path,
      rtl: Path,
      top: String,
      parameters: Vector[(String, Int)],
      expectedPorts: Vector[PortExpectation]
  ): Unit = {
    if (!commandAvailable("yosys")) return
    val suffix = parameters.map { case (name, value) => s"${name.toLowerCase}$value" }.mkString("_")
    val script = directory.resolve(s"${top}_$suffix.ys")
    val json = directory.resolve(s"${top}_$suffix.json")
    val parameterCommand = parameters
      .flatMap { case (name, value) => Vector("-set", name, value.toString) }
      .mkString(" ")
    val source =
      s"""read_verilog -defer -noautowire ${rtl.toString}
         |chparam $parameterCommand $top
         |hierarchy -check -top $top
         |proc
         |check -assert
         |synth -top $top
         |check -assert
         |write_json ${json.toString}
         |""".stripMargin
    Files.write(script, source.getBytes(StandardCharsets.UTF_8))
    val result = run(directory, Seq("yosys", "-q", "-s", script.toString))
    val specialization = parameters.map { case (name, value) => s"$name=$value" }.mkString(" ")
    assert(result._1 == 0, s"V2001 synthesis failed for $top $specialization:\n${result._2}")

    if (expectedPorts.nonEmpty && commandAvailable("python3")) {
      val checker = findRepositoryFile("morphhdl/scripts/check-yosys-port-widths.py")
      val checkerResult = run(
        directory,
        Seq("python3", checker.toString, json.toString, top) ++
          expectedPorts.flatMap(port => Seq("--port", s"${port.name}:${port.direction}:${port.width}"))
      )
      assert(
        checkerResult._1 == 0,
        s"exact Yosys port check failed for $top $specialization:\n${checkerResult._2}"
      )
    }
  }

  private def findRepositoryFile(relative: String): Path = {
    var directory = Paths.get("").toAbsolutePath.normalize()
    while (directory != null) {
      val candidate = directory.resolve(relative)
      if (Files.isRegularFile(candidate)) return candidate
      directory = directory.getParent
    }
    fail(s"could not locate repository file '$relative' from ${Paths.get("").toAbsolutePath}")
  }

  private def commandAvailable(name: String): Boolean =
    Process(Seq("sh", "-c", s"command -v $name >/dev/null 2>&1")).! == 0

  private def run(directory: Path, command: Seq[String]): (Int, String) = {
    val log = new StringBuilder
    val status = Process(command, directory.toFile).!(
      ProcessLogger(
        line => log.append(line).append('\n'),
        line => log.append(line).append('\n')
      )
    )
    status -> log.toString
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def compact(value: String): String = value.replaceAll("\\s+", "")

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val retained = Option(System.getenv("MORPHDL_TYPED_VEC_KEEP_DIR"))
    val directory = retained match {
      case Some(value) =>
        val path = java.nio.file.Paths.get(value)
        Files.createDirectories(path)
        path
      case None => Files.createTempDirectory("morphhdl-typed-vec-")
    }
    try body(directory)
    finally {
      if (retained.isEmpty) {
        val stream = Files.walk(directory)
        try {
          stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
            Files.deleteIfExists(path)
          }
        } finally stream.close()
      }
    }
  }
}
