package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

final class NativeParameterizedStreamFifoHarness(depth: HdlInt) extends Component {
  setDefinitionName("NativeParameterizedStreamFifoHarness")

  val io = new Bundle {
    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
    val flush = in Bool ()
    val occupancy = out UInt (4 bits)
    val availability = out UInt (4 bits)
  }

  val fifo = spinal.lib.StreamFifo(
    HardType(Bits(8 bits)),
    depth.asElabInt
  )
  fifo.setName("fifo")
  fifo.io.push << io.push
  io.pop << fifo.io.pop
  fifo.io.flush := io.flush
  io.occupancy := fifo.io.occupancy.resized
  io.availability := fifo.io.availability.resized
}

final class UnsafeStructuralAssignmentHarness(depth: HdlInt) extends Component {
  setDefinitionName("UnsafeStructuralAssignmentHarness")
  val observed = out(Bool())
  observed := False
  if (depth > 1) observed := True
  else observed := False
}

final class SymbolicStreamFifoFormalHelperHarness(
    depth: HdlInt,
    directDepth: Boolean = false,
    useVecStorage: Boolean = false
) extends Component {
  setDefinitionName("SymbolicStreamFifoFormalHelperHarness")
  private val typedDepth = depth.asElabInt

  val io = new Bundle {
    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
    val flush = in Bool ()
    val needle = in Bits (8 bits)
    val lastPush = out Bool ()
    val ramChecks = out Bits (typedDepth bits)
    val contains = out Bool ()
    val count = out UInt ((typedDepth + 1).addressWidth + 1 bits)
    val wordContains = out Bool ()
    val wordCount = out UInt ((typedDepth + 1).addressWidth + 1 bits)
  }

  val fifo =
    if (directDepth)
      StreamFifo(
        HardType(Bits(8 bits)),
        typedDepth,
        withAsyncRead = useVecStorage,
        withBypass = false,
        allowExtraMsb = true,
        forFMax = false,
        useVec = useVecStorage,
        initPayload = None
      )
    else spinal.lib.StreamFifo(HardType(Bits(8 bits)), typedDepth)

  fifo.io.push << io.push
  io.pop << fifo.io.pop
  fifo.io.flush := io.flush

  io.lastPush := fifo.formalCheckLastPush(_.orR)
  io.ramChecks := fifo.formalCheckRam(_.orR).asBits
  io.contains := fifo.formalContains(_.orR)
  io.count := fifo.formalCount(_.orR).resized
  io.wordContains := fifo.formalContains(io.needle)
  io.wordCount := fifo.formalCount(io.needle).resized
  fifo.formalFullToEmpty()
}

final class ConcreteStreamFifoFormalHelperHarness(
    useTypedLiteral: Boolean,
    payloadGeneratorHit: () => Unit = () => ()
) extends Component {
  setDefinitionName("ConcreteStreamFifoFormalHelperHarness")
  val io = new Bundle {
    val push = slave Stream (Bits(8 bits))
    val pop = master Stream (Bits(8 bits))
    val flush = in Bool ()
    val needle = in Bits (8 bits)
    val lastPush = out Bool ()
    val ramChecks = out Bits (5 bits)
    val contains = out Bool ()
    val count = out UInt (4 bits)
    val wordContains = out Bool ()
    val wordCount = out UInt (4 bits)
  }
  private val payloadType = HardType {
    payloadGeneratorHit()
    Bits(8 bits)
  }
  val fifo = spinal.core.formal.FormalDut {
    if (useTypedLiteral)
      spinal.lib.StreamFifo(payloadType, ElabInt.literal(5))
    else spinal.lib.StreamFifo(payloadType, 5)
  }
  fifo.io.push << io.push
  io.pop << fifo.io.pop
  fifo.io.flush := io.flush
  io.lastPush := fifo.formalCheckLastPush(_.orR)
  io.ramChecks := fifo.formalCheckRam(_.orR).asBits
  io.contains := fifo.formalContains(_.orR)
  io.count := fifo.formalCount(_.orR).resized
  io.wordContains := fifo.formalContains(io.needle)
  io.wordCount := fifo.formalCount(io.needle).resized
  fifo.formalFullToEmpty()
}

final class NonpositiveStreamFifoFormalHelperHarness(depth: HdlInt) extends Component {
  val fifo = new StreamFifo(
    HardType(Bits(8 bits)),
    depth.asElabInt,
    withAsyncRead = false,
    withBypass = false,
    allowExtraMsb = true,
    forFMax = false,
    useVec = false,
    initPayload = None
  )
  val observed = out(Bool())
  observed := fifo.formalCheckLastPush(_.orR)
}

class ParameterizedStreamFifoDepthTests extends AnyFunSuite {
  private val ExpectedStreamFifoModuleInventory =
    Vector("NativeParameterizedStreamFifoHarness", "StreamFifo").sorted

  private val ModuleDeclaration =
    """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r

  private val FormalRamCheckAggregate =
    """(?<![A-Za-z0-9_$])(formal_ram_check(?:_[0-9]+_morphhdl_vec)?)(?![A-Za-z0-9_$])""".r

  private def streamFifoModuleInventory(verilog: String): Vector[String] =
    ModuleDeclaration
      .findAllMatchIn(verilog)
      .map(_.group(1))
      .filter(_.contains("StreamFifo"))
      .toVector
      .sorted

  private def assertTypedStoragePopIndex(verilog: String): Unit = {
    val declarations = verilog
      .split("\\r?\\n")
      .map(_.replaceAll("\\s+", ""))
      .filter(_.contains("typed_storage_pop_index"))
    val exactLog2Width = """clog2\(\(*DEPTH\)*,0\)""".r
    assert(
      declarations.exists(line => exactLog2Width.findFirstIn(line).nonEmpty),
      s"missing exact symbolic storage pop-index declaration:\n${declarations.mkString("\n")}"
    )
  }

  private def nativeStreamFifoDefinition(verilog: String): String =
    "(?ms)^\\s*module\\s+StreamFifo\\b.*?^\\s*endmodule\\b".r
      .findFirstIn(verilog)
      .getOrElse(fail("Native StreamFifo module definition is missing"))

  private def assertFormalRamCheckAggregateDominance(
      verilog: String
  ): Unit = {
    val names = FormalRamCheckAggregate
      .findAllMatchIn(verilog)
      .map(_.group(1))
      .toSet
    assert(
      names.size == 5,
      s"expected five formal RAM-check aggregates, found ${names.toVector.sorted.mkString(", ")}:\n$verilog"
    )
    val generateStart = "(?m)^\\s*generate\\s*$".r
      .findFirstMatchIn(verilog)
      .map(_.start)
      .getOrElse(fail(s"formal StreamFifo generate region is missing:\n$verilog"))

    names.foreach { name =>
      val quoted = java.util.regex.Pattern.quote(name)
      val declarations =
        ("(?m)^\\s*wire\\s+\\[[^\\]]*DEPTH[^\\]]*\\]\\s+" +
          quoted + "\\s*;\\s*$").r.findAllMatchIn(verilog).toVector
      val allDeclarations =
        ("(?m)^\\s*(?:wire|reg)\\b[^;]*\\b" + quoted +
          "\\s*;\\s*$").r.findAllMatchIn(verilog).toVector
      assert(
        declarations.size == 1 &&
          allDeclarations.size == 1 &&
          declarations.head.start == allDeclarations.head.start &&
          declarations.head.start < generateStart,
        s"formal RAM-check aggregate '$name' is not declared exactly once at module scope:\n$verilog"
      )

      val slices =
        ("(?m)^\\s*assign\\s+" + quoted +
          "\\s*\\[([^\\]]+)\\]\\s*=").r
          .findAllMatchIn(verilog)
          .map(_.group(1).replaceAll("\\s+", ""))
          .toVector
      assert(
        slices.size == 2 &&
          slices.count(_ == "(0)+:1") == 1 &&
          slices.count(value =>
            value.contains("stream_fifo_formal_ram_mask_index") &&
              value.endsWith("+:1")
          ) == 1,
        s"formal RAM-check aggregate '$name' does not retain one depth-one and one storage slice driver: ${slices
            .mkString(", ")}\n$verilog"
      )
    }
  }

  private def assertFormalHelperSymbolicGeometry(verilog: String): Unit = {
    val depthWideDeclarations =
      """(?m)^\s*(?:wire|reg)\s+\[[^\]]*DEPTH[^\]]*\]\s+([A-Za-z_][A-Za-z0-9_$]*)\s*;\s*$""".r
        .findAllMatchIn(verilog)
        .map(_.group(1))
        .toSet
    val allOnes = depthWideDeclarations.filter(
      _.matches("formal_ram_mask_all_ones_[0-9]+")
    )
    val allOnesZero = depthWideDeclarations.filter(
      _.matches("formal_ram_mask_all_ones_[0-9]+_zero")
    )
    val shiftedOne = depthWideDeclarations.filter(
      _.matches(
        "typed_formal_ram_(?:pop|push)_low_mask_shifted_one(?:_[0-9]+)?"
      )
    )
    assert(
      allOnes.size == 5 && allOnesZero.size == 5,
      s"formal RAM defaults do not retain five DEPTH-wide complement carriers: ones=${allOnes.toVector.sorted.mkString(", ")} zero=${allOnesZero.toVector.sorted.mkString(", ")}\n$verilog"
    )
    assert(
      shiftedOne.count(_.contains("_pop_")) == 5 &&
        shiftedOne.count(_.contains("_push_")) == 5,
      s"formal RAM low masks do not retain ten DEPTH-wide shift carriers: ${shiftedOne.toVector.sorted.mkString(", ")}\n$verilog"
    )

    val complementOrdinals =
      """(?m)^\s*assign\s+formal_ram_mask_all_ones_([0-9]+)\s*=\s*\(\s*~\s+formal_ram_mask_all_ones_([0-9]+)_zero\s*\)\s*;\s*$""".r
        .findAllMatchIn(verilog)
        .map(value => value.group(1) -> value.group(2))
        .toVector
    assert(
      complementOrdinals.size == 5 &&
        complementOrdinals.forall { case (result, zero) => result == zero },
      s"formal RAM defaults are not driven by their DEPTH-wide complemented zero carriers: ${complementOrdinals.mkString(", ")}\n$verilog"
    )
    val defaultMaskAssignments =
      """(?m)^\s*(formal_ram_mask(?:_[0-9]+)?)\s*=\s*formal_ram_mask_all_ones_([0-9]+)\s*;\s*$""".r
        .findAllMatchIn(verilog)
        .map(value => value.group(1) -> value.group(2))
        .toVector
    assert(
      defaultMaskAssignments.map(_._1).distinct.size == 5 &&
        defaultMaskAssignments.map(_._2).distinct.size == 5,
      s"formal RAM masks do not each default from one typed all-ones carrier: ${defaultMaskAssignments.mkString(", ")}\n$verilog"
    )
    assert(
      """(?m)^\s*formal_ram_mask(?:_[0-9]+)?\s*=\s*[0-9]+'b1+\s*;\s*$""".r
        .findFirstIn(verilog)
        .isEmpty,
      s"formal RAM default retained a construction-width all-ones literal:\n$verilog"
    )
    assert(
      """(?m)^\s*(?:wire|reg)\b[^;]*\b_zz_+typed_formal_ram_(?:pop|push)_low_mask(?:_uint)?\b[^;]*;\s*$""".r
        .findFirstIn(verilog)
        .isEmpty,
      s"formal RAM low mask retained an anonymous construction-width shift carrier:\n$verilog"
    )

    val compactLines = verilog
      .split("\\r?\\n")
      .map(_.replaceAll("\\s+", ""))
    Vector(
      "typed_formal_last_push_previous_index_pointer",
      "typed_formal_last_push_previous_index_last_index",
      "typed_formal_last_push_previous_index_one",
      "typed_formal_last_push_previous_index_decremented",
      "typed_formal_last_push_previous_index",
      "typed_formal_last_push_index"
    ).foreach { name =>
      assert(
        compactLines.exists(
          _.contains(s"[clog2(DEPTH,1)-1:0]$name;")
        ),
        s"last-push predecessor '$name' does not retain exact DEPTH address width:\n$verilog"
      )
    }
    val compact = compactLines.mkString
    assert(
      compact.contains(
        "assigntyped_formal_last_push_previous_index_last_index=((DEPTH-1));"
      ) &&
        compact.contains(
          "typed_formal_last_push_previous_index=typed_formal_last_push_previous_index_decremented;"
        ) &&
        !compact.contains("typed_formal_last_push_depth"),
      s"last-push did not retain the exact-width wrapped-predecessor algorithm:\n$verilog"
    )
  }

  private def concreteFifoGraph(
      fifo: StreamFifo[_ <: Data]
  ): Vector[String] =
    Vector(
      s"depth=${fifo.depth}",
      s"withAsyncRead=${fifo.withAsyncRead}",
      s"withBypass=${fifo.withBypass}",
      s"allowExtraMsb=${fifo.allowExtraMsb}",
      s"withExtraMsb=${fifo.withExtraMsb}",
      s"forFMax=${fifo.forFMax}",
      s"useVec=${fifo.useVec}",
      s"payloadWidth=${fifo.io.push.payload.getBitsWidth}",
      s"occupancyWidth=${fifo.io.occupancy.getBitsWidth}",
      s"availabilityWidth=${fifo.io.availability.getBitsWidth}",
      s"bypass=${fifo.bypass != null}",
      s"oneStage=${fifo.oneStage != null}",
      s"storage=${fifo.logic != null}"
    )

  private def component(default: Int = 5): NativeParameterizedStreamFifoHarness = {
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(default),
      min = BigInt(1),
      max = BigInt(8)
    )
    new NativeParameterizedStreamFifoHarness(depth)
  }

  test("default depth one still captures the native storage alternative") {
    withTemporaryDirectory { directory =>
      val config = synchronousResetConfig(directory)
      config.netlistFileName = "stream_fifo_default_one.v"
      val report = MorphVerilog(config)(component(default = 1))
      val verilog = read(directory.resolve(config.netlistFileName))
      val nativeStreamFifo = nativeStreamFifoDefinition(verilog)

      assert(report.parameters.map(parameter => parameter.name -> parameter.default) == Vector("DEPTH" -> BigInt(1)))
      assert(verilog.contains("parameter integer DEPTH = 1"))
      assert(verilog.contains(".DEPTH(DEPTH)"))
      assert(
        verilog.contains("[0:DEPTH-1]") ||
          verilog.contains("[0:(DEPTH - 1)]")
      )
      assert("""DEPTH\s*\)?\s*>\s*\(?\s*1""".r.findFirstIn(nativeStreamFifo).nonEmpty)
      assert(
        streamFifoModuleInventory(verilog) == ExpectedStreamFifoModuleInventory
      )
      assertTypedStoragePopIndex(nativeStreamFifo)
    }
  }

  test("one native StreamFifo definition preserves depths 1, 3, 5 and 8") {
    withTemporaryDirectory { directory =>
      val parameterizedDirectory = directory.resolve("parameterized")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(parameterizedDirectory)
      Files.createDirectories(concreteDirectory)

      val parameterizedConfig = synchronousResetConfig(parameterizedDirectory)
      parameterizedConfig.netlistFileName = "stream_fifo_parameterized_depth.v"
      val parameterizedReport = MorphVerilog(parameterizedConfig)(component())
      val parameterizedRtl =
        parameterizedDirectory.resolve("stream_fifo_parameterized_depth.v")
      val parameterized = read(parameterizedRtl)

      val replayDirectory = directory.resolve("parameterized-replay")
      Files.createDirectories(replayDirectory)
      val replayConfig = synchronousResetConfig(replayDirectory)
      replayConfig.netlistFileName = "stream_fifo_parameterized_depth.v"
      MorphVerilog(replayConfig)(component())
      val replayRtl =
        replayDirectory.resolve("stream_fifo_parameterized_depth.v")
      assert(
        java.util.Arrays.equals(
          Files.readAllBytes(parameterizedRtl),
          Files.readAllBytes(replayRtl)
        ),
        "identical native StreamFifo generation was not byte deterministic"
      )

      val concreteConfig = synchronousResetConfig(concreteDirectory)
      concreteConfig.netlistFileName = "stream_fifo_parameterized_depth.v"
      SpinalVerilog(concreteConfig)(component())
      val concrete =
        read(concreteDirectory.resolve("stream_fifo_parameterized_depth.v"))

      val depthParameter = parameterizedReport.parameters.find(_.name == "DEPTH")
      assert(depthParameter.nonEmpty)
      assert(depthParameter.get.default == BigInt(5))
      assert(
        depthParameter.get.constraints == Vector(
          paramrtl.IntConstraint.MinInclusive(BigInt(1)),
          paramrtl.IntConstraint.MaxInclusive(BigInt(8))
        )
      )
      val streamFifoModules = streamFifoModuleInventory(parameterized)
      assert(
        streamFifoModules == ExpectedStreamFifoModuleInventory,
        s"Unexpected StreamFifo module inventory: ${streamFifoModules.mkString(", ")}"
      )
      assert(parameterized.contains("module NativeParameterizedStreamFifoHarness #("))
      assert(parameterized.contains("parameter integer DEPTH = 5"))
      assert(parameterized.contains(".DEPTH(DEPTH)"))
      assert(
        parameterized.contains("[0:DEPTH-1]") ||
          parameterized.contains("[0:(DEPTH - 1)]")
      )
      // Typed log2Up preserves log2Up(1) == 0.  Its use as a packed width is
      // valid here because the declaration is owned by the DEPTH > 1 branch.
      assert(parameterized.contains("clog2(DEPTH, 0)"))
      assert(parameterized.contains("function integer clog2;"))
      assert(!parameterized.contains("morphhdl_address_width"))
      assert(!parameterized.contains("morphhdl_ceil_log2"))
      val nativeStreamFifo = nativeStreamFifoDefinition(parameterized)
      val depthOneCondition =
        """DEPTH\s*\)?\s*==\s*\(?\s*1""".r.findFirstMatchIn(nativeStreamFifo)
      val storageCondition =
        """DEPTH\s*\)?\s*>\s*\(?\s*1""".r.findFirstMatchIn(nativeStreamFifo)
      assert(nativeStreamFifo.contains("generate"))
      assert(depthOneCondition.nonEmpty)
      assert(storageCondition.nonEmpty)
      assert(
        nativeStreamFifo.contains("DEPTH & (DEPTH - 1)") ||
          nativeStreamFifo.contains("DEPTH & (DEPTH-1)")
      )
      val logicAlternative = storageCondition.get.start
      val powerOfTwoAlternative = nativeStreamFifo.indexOf("DEPTH &")
      assert(logicAlternative >= 0 && powerOfTwoAlternative > logicAlternative)
      assert(parameterized.contains("io_push_ready"))
      assert(parameterized.contains("io_pop_valid"))
      assert(parameterized.contains("io_occupancy"))
      assert(parameterized.contains("io_availability"))
      assert(!parameterized.contains("ParamRTL"))
      assert(!parameterized.contains("rewriteParameterizedStreamFifoDepth"))
      assertTypedStoragePopIndex(nativeStreamFifo)

      assert(!concrete.contains("parameter integer DEPTH"))
      assert(concrete.contains("[0:4]"))
      assert(concrete.contains("[7:0]"))

      val rtl = parameterizedDirectory.resolve("stream_fifo_parameterized_depth.v")
      Vector(1, 3, 5, 8).foreach { selectedDepth =>
        if (commandAvailable("verilator"))
          lintDepth(parameterizedDirectory, rtl, selectedDepth)
        if (commandAvailable("iverilog") && commandAvailable("vvp"))
          simulateDepth(parameterizedDirectory, rtl, selectedDepth)
        if (commandAvailable("yosys"))
          synthesizeDepth(parameterizedDirectory, rtl, selectedDepth)
      }
    }
  }

  test("mutually-exclusive capture never hides an outside assignment") {
    withTemporaryDirectory { directory =>
      val config = synchronousResetConfig(directory)
      config.netlistFileName = "unsafe_structural_assignment.v"
      MorphVerilog.tryGenerate(config) {
        val depth = HdlInt.param(
          "DEPTH",
          default = BigInt(5),
          min = BigInt(1),
          max = BigInt(8)
        )
        new UnsafeStructuralAssignmentHarness(depth)
      } match {
        case Left(failure) =>
          assert(failure.detail.contains("ASSIGNMENT OVERLAP"))
        case Right(report) =>
          fail(s"Expected inherited overlap failure, received $report")
      }
    }
  }

  test("compound bounded depth retains its exact formal actual") {
    withTemporaryDirectory { directory =>
      val config = synchronousResetConfig(directory)
      config.netlistFileName = "stream_fifo_compound_depth.v"
      val report = MorphVerilog(config) {
        val base = HdlInt.param(
          "BASE",
          default = BigInt(4),
          min = BigInt(1),
          max = BigInt(7)
        )
        new NativeParameterizedStreamFifoHarness(
          base + HdlInt.literal(BigInt(1))
        )
      }
      val verilog = read(directory.resolve("stream_fifo_compound_depth.v"))
      val compact = verilog.replaceAll("\\s+", "")
      assert(report.parameters.map(_.name) == Vector("BASE"))
      val baseParameter = report.parameters.head
      assert(baseParameter.default == BigInt(4))
      assert(
        baseParameter.constraints == Vector(
          paramrtl.IntConstraint.MinInclusive(BigInt(1)),
          paramrtl.IntConstraint.MaxInclusive(BigInt(7))
        )
      )
      assert(compact.contains(".DEPTH((BASE+1))"))
      val streamFifoModules = streamFifoModuleInventory(verilog)
      assert(
        streamFifoModules == ExpectedStreamFifoModuleInventory,
        s"Unexpected StreamFifo module inventory: ${streamFifoModules.mkString(", ")}"
      )
    }
  }

  test("symbolic formal helpers remain live at depths 1, 3, 5 and 8") {
    withTemporaryDirectory { directory =>
      val config = synchronousResetConfig(directory)
      config.includeFormal
      config.netlistFileName = "symbolic_stream_fifo_formal_helpers.v"
      val depth = HdlInt.param(
        "DEPTH",
        default = BigInt(1),
        min = BigInt(1),
        max = BigInt(8)
      )
      val report = MorphVerilog(config) {
        new SymbolicStreamFifoFormalHelperHarness(depth)
      }
      val rtl = directory.resolve(config.netlistFileName)
      val verilog = read(rtl)
      val compact = verilog.replaceAll("\\s+", "")

      assert(
        report.parameters.map(parameter => parameter.name -> parameter.default) ==
          Vector("DEPTH" -> BigInt(1))
      )
      assert(verilog.contains("module SymbolicStreamFifoFormalHelperHarness #("))
      assert(compact.contains("[DEPTH-1:0]io_ramChecks"), verilog)
      assert(
        compact.contains("[DEPTH-1:0]typed_formal_ram_mask_one"),
        verilog
      )
      assert(
        compact.contains("[(clog2((DEPTH+1),1)+1)-1:0]io_count"),
        verilog
      )
      assert(
        compact.contains("[(clog2((DEPTH+1),1)+1)-1:0]io_wordCount"),
        verilog
      )
      assert(verilog.contains("morphhdl_finite_fold_index_"), verilog)
      assert(
        "for\\s*\\([^;]+;\\s*[^;]+<\\s*DEPTH\\s*;".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      assert(verilog.contains("formal_last_push"), verilog)
      assert(verilog.contains("formal_ram_check"), verilog)
      assert(verilog.contains("formal_ram_condition"), verilog)
      assert(verilog.contains("formal_ram_mask"), verilog)
      assert(
        "(?m)^\\s*cover\\s*\\(".r.findAllMatchIn(verilog).size == 1,
        verilog
      )
      val formalFifo = nativeStreamFifoDefinition(verilog)
      assertFormalRamCheckAggregateDominance(formalFifo)
      assertFormalHelperSymbolicGeometry(formalFifo)
      assert(
        """DEPTH\s*\)?\s*==\s*\(?\s*1""".r
          .findFirstIn(formalFifo)
          .nonEmpty,
        formalFifo
      )
      assert(
        """DEPTH\s*\)?\s*>\s*\(?\s*1""".r
          .findFirstIn(formalFifo)
          .nonEmpty,
        formalFifo
      )
      assert(
        """(?m)^\s*(?:assign\s+)?formal_full_to_empty\s*=""".r
          .findAllMatchIn(formalFifo)
          .size == 2,
        formalFifo
      )

      Vector(1, 3, 5, 8).foreach { selectedDepth =>
        if (commandAvailable("verilator"))
          lintFormalHelpers(directory, rtl, selectedDepth)
        if (commandAvailable("iverilog") && commandAvailable("vvp"))
          simulateFormalHelpers(directory, rtl, selectedDepth)
        if (commandAvailable("yosys"))
          synthesizeFormalHelpers(directory, rtl, selectedDepth)
      }
    }
  }

  private def lintFormalHelpers(
      directory: Path,
      rtl: Path,
      selectedDepth: Int
  ): Unit = {
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
        "SymbolicStreamFifoFormalHelperHarness",
        s"-GDEPTH=$selectedDepth",
        rtl.toString
      )
    )
    assert(
      result._1 == 0,
      s"formal-helper Verilator lint failed for DEPTH=$selectedDepth:\n${result._2}"
    )
  }

  private def simulateFormalHelpers(
      directory: Path,
      rtl: Path,
      selectedDepth: Int,
      outputStageEnabled: Boolean = true
  ): Unit = {
    val top = s"SymbolicStreamFifoFormalHelpersDepth${selectedDepth}Tb"
    val testbench = directory.resolve(s"$top.v")
    val output = directory.resolve(s"formal_helpers_depth_$selectedDepth.out")
    val depthOneExpectedCount = if (outputStageEnabled) 2 else 1
    val source =
      s"""`timescale 1ns/1ps
         |module $top;
         |  parameter integer DEPTH = $selectedDepth;
         |  function integer tb_clog2;
         |    input integer value;
         |    integer shifted;
         |    begin
         |      shifted = value - 1;
         |      tb_clog2 = 0;
         |      while (shifted > 0) begin
         |        shifted = shifted >> 1;
         |        tb_clog2 = tb_clog2 + 1;
         |      end
         |    end
         |  endfunction
         |  localparam integer COUNT_WIDTH = tb_clog2(DEPTH + 1) + 1;
         |  // Preserve the inherited concrete helper behavior. A synchronous
         |  // depth-one buffer is visible in both helpers and reports two;
         |  // async Vec storage has no formal output stage and reports one.
         |  localparam integer EXPECTED_MISMATCH_PREDICATE_COUNT =
         |    (DEPTH == 1) ? $depthOneExpectedCount : 1;
         |  localparam integer EXPECTED_MATCHING_PREDICATE_COUNT =
         |    (DEPTH == 1) ? $depthOneExpectedCount : 1;
         |  localparam integer EXPECTED_MATCHING_WORD_COUNT =
         |    (DEPTH == 1) ? $depthOneExpectedCount : 1;
         |  localparam integer MATCHING_PUSH_COUNT = (DEPTH == 1) ? 1 : 3;
         |  reg clk = 1'b0;
         |  reg reset = 1'b1;
         |  reg io_push_valid = 1'b0;
         |  wire io_push_ready;
         |  reg [7:0] io_push_payload = 8'h00;
         |  wire io_pop_valid;
         |  reg io_pop_ready = 1'b0;
         |  wire [7:0] io_pop_payload;
         |  reg io_flush = 1'b0;
         |  reg [7:0] io_needle = 8'h02;
         |  wire io_lastPush;
         |  wire [DEPTH-1:0] io_ramChecks;
         |  wire io_contains;
         |  wire [COUNT_WIDTH-1:0] io_count;
         |  wire io_wordContains;
         |  wire [COUNT_WIDTH-1:0] io_wordCount;
         |  integer timeout;
         |  integer drained;
         |
         |  always #5 clk = ~clk;
         |
         |  SymbolicStreamFifoFormalHelperHarness #(.DEPTH(DEPTH)) dut (
         |    .io_push_valid(io_push_valid),
         |    .io_push_ready(io_push_ready),
         |    .io_push_payload(io_push_payload),
         |    .io_pop_valid(io_pop_valid),
         |    .io_pop_ready(io_pop_ready),
         |    .io_pop_payload(io_pop_payload),
         |    .io_flush(io_flush),
         |    .io_needle(io_needle),
         |    .io_lastPush(io_lastPush),
         |    .io_ramChecks(io_ramChecks),
         |    .io_contains(io_contains),
         |    .io_count(io_count),
         |    .io_wordContains(io_wordContains),
         |    .io_wordCount(io_wordCount),
         |    .clk(clk),
         |    .reset(reset)
         |  );
         |
         |  task tick;
         |    begin
         |      @(posedge clk);
         |      #1;
         |    end
         |  endtask
         |
         |  task fail;
         |    input [511:0] reason;
         |    begin
         |      $$display("FAIL formal helpers DEPTH=%0d: %0s", DEPTH, reason);
         |      $$display("STATE ready=%b valid=%b last=%b checks=%b contains=%b count=%0d wordContains=%b wordCount=%0d",
         |        io_push_ready, io_pop_valid, io_lastPush, io_ramChecks,
         |        io_contains, io_count, io_wordContains, io_wordCount);
         |      $$finish(2);
         |    end
         |  endtask
         |
         |  task push_word;
         |    input [7:0] value;
         |    begin
         |      io_push_payload = value;
         |      io_push_valid = 1'b1;
         |      timeout = 0;
         |      while (!io_push_ready && timeout < 30) begin
         |        tick;
         |        timeout = timeout + 1;
         |      end
         |      if (!io_push_ready) fail("push timeout");
         |      tick;
         |      io_push_valid = 1'b0;
         |    end
         |  endtask
         |
         |  task wait_for_pop;
         |    begin
         |      timeout = 0;
         |      while (!io_pop_valid && timeout < 30) begin
         |        tick;
         |        timeout = timeout + 1;
         |      end
         |      if (!io_pop_valid) fail("pop-valid timeout");
         |    end
         |  endtask
         |
         |  task drain_words;
         |    input integer amount;
         |    begin
         |      io_pop_ready = 1'b1;
         |      drained = 0;
         |      timeout = 0;
         |      while (drained < amount && timeout < 100) begin
         |        if (io_pop_valid) drained = drained + 1;
         |        tick;
         |        timeout = timeout + 1;
         |      end
         |      io_pop_ready = 1'b0;
         |      if (drained != amount) fail("drain timeout");
         |    end
         |  endtask
         |
         |  task check_empty;
         |    begin
         |      timeout = 0;
         |      while ((io_contains !== 1'b0 || io_count !== 0 ||
         |              io_wordContains !== 1'b0 || io_wordCount !== 0) && timeout < 30) begin
         |        tick;
         |        timeout = timeout + 1;
         |      end
         |      if (io_contains !== 1'b0) fail("contains remained set after drain");
         |      if (io_count !== 0) fail("count remained set after drain");
         |      if (io_wordContains !== 1'b0) fail("word contains remained set after drain");
         |      if (io_wordCount !== 0) fail("word count remained set after drain");
         |    end
         |  endtask
         |
         |  initial begin
         |    repeat (3) tick;
         |    reset = 1'b0;
         |    tick;
         |    if (io_ramChecks !== {DEPTH{1'b0}}) fail("RAM checks nonzero after reset");
         |    if (io_contains !== 1'b0) fail("contains nonzero after reset");
         |    if (io_count !== 0) fail("count nonzero after reset");
         |    if (io_wordContains !== 1'b0) fail("word contains nonzero after reset");
         |    if (io_wordCount !== 0) fail("word count nonzero after reset");
         |
         |    // A nonzero word distinct from the needle discriminates the
         |    // predicate and word overloads.
         |    push_word(8'h01);
         |    repeat (3) tick;
         |    wait_for_pop;
         |    if (io_contains !== 1'b1) fail("predicate contains missed mismatching word");
         |    if (io_count !== EXPECTED_MISMATCH_PREDICATE_COUNT)
         |      fail("predicate count missed mismatching word");
         |    if (io_wordContains !== 1'b0) fail("word contains aliased predicate");
         |    if (io_wordCount !== 0) fail("word count aliased predicate");
         |    if (io_lastPush !== 1'b1) fail("last-push missed mismatching word");
         |    drain_words(1);
         |    check_empty;
         |
         |    // At storage depths, hold three distinguishable items while pop
         |    // is stalled. The middle word matches and the final word is
         |    // zero, making a previous-item/off-by-one lastPush observable
         |    // while live RAM checks remain visible.
         |    if (DEPTH == 1) begin
         |      push_word(8'h02);
         |    end else begin
         |      push_word(8'h00);
         |      push_word(8'h02);
         |      push_word(8'h00);
         |    end
         |    repeat (3) tick;
         |    wait_for_pop;
         |    if (DEPTH > 1 && io_pop_payload !== 8'h00)
         |      fail("FIFO ordering did not retain first stalled word");
         |    if (DEPTH > 1 && io_ramChecks === {DEPTH{1'b0}})
         |      fail("RAM helper mask/fold remained unexercised");
         |    if (io_contains !== 1'b1) fail("predicate contains missed matching phase");
         |    if (io_count !== EXPECTED_MATCHING_PREDICATE_COUNT)
         |      fail("predicate count mismatch in matching phase");
         |    if (io_wordContains !== 1'b1) fail("word contains missed needle");
         |    if (io_wordCount !== EXPECTED_MATCHING_WORD_COUNT)
         |      fail("word count mismatch in matching phase");
         |    if (DEPTH == 1 && io_lastPush !== 1'b1)
         |      fail("depth-one last-push missed matching word");
         |    if (DEPTH > 1 && io_lastPush !== 1'b0)
         |      fail("last-push selected the previous rather than final word");
         |    drain_words(MATCHING_PUSH_COUNT);
         |    check_empty;
         |    $$display("PASS formal helpers DEPTH=%0d", DEPTH);
         |    $$finish;
         |  end
         |endmodule
         |""".stripMargin
    Files.write(testbench, source.getBytes(StandardCharsets.UTF_8))
    val compile = run(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-s",
        top,
        "-o",
        output.toString,
        rtl.toString,
        testbench.toString
      )
    )
    assert(
      compile._1 == 0,
      s"formal-helper Icarus compile failed for DEPTH=$selectedDepth:\n${compile._2}"
    )
    val simulation = run(directory, Seq("vvp", output.toString))
    assert(
      simulation._1 == 0 &&
        simulation._2.contains(s"PASS formal helpers DEPTH=$selectedDepth"),
      s"formal-helper simulation failed for DEPTH=$selectedDepth:\n${simulation._2}"
    )
  }

  private def synthesizeFormalHelpers(
      directory: Path,
      rtl: Path,
      selectedDepth: Int
  ): Unit = {
    val script = directory.resolve(s"formal_helpers_depth_$selectedDepth.ys")
    Files.write(
      script,
      s"""read_verilog -formal -defer ${rtl.toString}
         |chparam -set DEPTH $selectedDepth SymbolicStreamFifoFormalHelperHarness
         |hierarchy -check -top SymbolicStreamFifoFormalHelperHarness
         |prep -top SymbolicStreamFifoFormalHelperHarness
         |check -assert
         |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )
    val result = run(directory, Seq("yosys", "-q", "-s", script.toString))
    assert(
      result._1 == 0,
      s"formal-helper Yosys synthesis failed for DEPTH=$selectedDepth:\n${result._2}"
    )
  }

  test("symbolic formal helpers support one-stage storage and Vec-storage domains") {
    withTemporaryDirectory { directory =>
      val domains = Vector(
        ("one", 1, 1, 1, Vector(1), false),
        ("storage", 3, 2, 8, Vector(3, 5, 8), false),
        ("vec_storage", 1, 1, 8, Vector(1, 3, 4, 5, 8), true)
      )
      domains.foreach { case (label, default, minimum, maximum, depths, useVecStorage) =>
        val target = directory.resolve(label)
        Files.createDirectories(target)
        val config = synchronousResetConfig(target)
        config.includeFormal
        config.netlistFileName = s"symbolic_stream_fifo_formal_$label.v"
        val depth = HdlInt.param(
          "DEPTH",
          default = BigInt(default),
          min = BigInt(minimum),
          max = BigInt(maximum)
        )
        MorphVerilog(config) {
          new SymbolicStreamFifoFormalHelperHarness(
            depth,
            directDepth = true,
            useVecStorage = useVecStorage
          )
        }
        val rtl = target.resolve(config.netlistFileName)
        val verilog = read(rtl)
        assert(verilog.contains("formal_last_push"), verilog)
        assert(verilog.contains("formal_ram_check"), verilog)
        assert(verilog.contains("morphhdl_finite_fold_index_"), verilog)
        assert(
          "(?m)^\\s*cover\\s*\\(".r.findAllMatchIn(verilog).size == 1,
          verilog
        )
        if (maximum > 1) {
          val formalFifo = nativeStreamFifoDefinition(verilog)
          assertTypedStoragePopIndex(formalFifo)
          assertFormalHelperSymbolicGeometry(formalFifo)
          val compactLines = verilog.split("\\r?\\n").map(_.replaceAll("\\s+", ""))
          val exactAddressWidth =
            """.*\[\(?clog2\(\(*DEPTH\)*,1\)\)?-1:0\].*""".r
          Vector(
            "typed_formal_last_push_index",
            "typed_formal_ram_push_index",
            "typed_formal_ram_pop_index"
          ).foreach { name =>
            val matchingDeclarations = compactLines.filter(_.contains(name))
            assert(
              matchingDeclarations.exists(line =>
                exactAddressWidth.pattern.matcher(line).matches()
              ),
              s"missing exact DEPTH address-width declaration for $name:\n${matchingDeclarations.mkString("\n")}"
            )
          }
        }
        if (useVecStorage) {
          Vector(
            "typed_vec_write_index",
            "typed_vec_write_target",
            "typed_vec_write_data"
          ).foreach(name => assert(verilog.contains(name), verilog))
        }
        depths.foreach { selectedDepth =>
          if (commandAvailable("verilator"))
            lintFormalHelpers(target, rtl, selectedDepth)
          if (commandAvailable("iverilog") && commandAvailable("vvp"))
            simulateFormalHelpers(
              target,
              rtl,
              selectedDepth,
              outputStageEnabled = !useVecStorage
            )
          if (commandAvailable("yosys"))
            synthesizeFormalHelpers(target, rtl, selectedDepth)
        }
      }
    }
  }

  test("typed literal formal helpers preserve ordinary concrete RTL") {
    withTemporaryDirectory { directory =>
      val ordinaryDirectory = directory.resolve("ordinary")
      val typedDirectory = directory.resolve("typed")
      Files.createDirectories(ordinaryDirectory)
      Files.createDirectories(typedDirectory)
      val ordinaryConfig = synchronousResetConfig(ordinaryDirectory)
      ordinaryConfig.includeFormal
      ordinaryConfig.netlistFileName = "concrete_formal_helpers.v"
      var ordinaryGeneratorCalls = 0
      var ordinaryGraph = Vector.empty[String]
      SpinalVerilog(ordinaryConfig) {
        val dut = new ConcreteStreamFifoFormalHelperHarness(
          useTypedLiteral = false,
          payloadGeneratorHit = () => ordinaryGeneratorCalls += 1
        )
        ordinaryGraph = concreteFifoGraph(dut.fifo)
        dut
      }
      val typedConfig = synchronousResetConfig(typedDirectory)
      typedConfig.includeFormal
      typedConfig.netlistFileName = "concrete_formal_helpers.v"
      var typedGeneratorCalls = 0
      var typedGraph = Vector.empty[String]
      SpinalVerilog(typedConfig) {
        val dut = new ConcreteStreamFifoFormalHelperHarness(
          useTypedLiteral = true,
          payloadGeneratorHit = () => typedGeneratorCalls += 1
        )
        typedGraph = concreteFifoGraph(dut.fifo)
        dut
      }
      assert(
        java.util.Arrays.equals(
          Files.readAllBytes(ordinaryDirectory.resolve("concrete_formal_helpers.v")),
          Files.readAllBytes(typedDirectory.resolve("concrete_formal_helpers.v"))
        )
      )
      assert(typedGeneratorCalls == ordinaryGeneratorCalls)
      assert(typedGeneratorCalls > 0)
      assert(typedGraph == ordinaryGraph)
    }
  }

  test("symbolic formal helpers reject a nonpositive depth domain") {
    withTemporaryDirectory { directory =>
      val config = synchronousResetConfig(directory)
      config.netlistFileName = "nonpositive_formal_helpers.v"
      MorphVerilog.tryGenerate(config) {
        val depth = HdlInt.param(
          "DEPTH",
          default = BigInt(1),
          min = BigInt(0),
          max = BigInt(8)
        )
        new NonpositiveStreamFifoFormalHelperHarness(depth)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-ELAB-STREAMFIFO-FORMAL-DEPTH-DOMAIN-NONPOSITIVE"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"expected nonpositive formal-depth failure, received $report")
      }
    }
  }

  private def lintDepth(
      directory: Path,
      rtl: Path,
      selectedDepth: Int
  ): Unit = {
    val depthSpecificWarnings =
      if (selectedDepth == 1) Seq("-Wno-CMPCONST")
      else {
        // Native StreamFifo keeps inherited dead helper nets in ordinary
        // concrete output. Legacy Verilator reports that family as UNUSED,
        // while current Verilator splits it into finer unused categories.
        // This leaves UNDRIVEN and the existing non-unused warning policy unchanged.
        Seq("-Wno-UNUSED")
      }
    val command = Seq(
      "verilator",
      "--lint-only",
      "--language",
      "1364-2001",
      "-Wall",
      "-Wno-DECLFILENAME",
      "-Wno-WIDTH"
    ) ++ depthSpecificWarnings ++ Seq(
      "--top-module",
      "NativeParameterizedStreamFifoHarness",
      s"-GDEPTH=$selectedDepth",
      rtl.toString
    )
    val result = run(directory, command)
    assert(
      result._1 == 0,
      s"Verilator lint failed for DEPTH=$selectedDepth:\n${result._2}"
    )
  }

  private def simulateDepth(
      directory: Path,
      rtl: Path,
      selectedDepth: Int
  ): Unit = {
    val testbench = directory.resolve(s"StreamFifoDepth${selectedDepth}Tb.v")
    val executable = directory.resolve(s"StreamFifoDepth${selectedDepth}Tb.out")
    val source =
      s"""`timescale 1ns/1ps
         |module StreamFifoDepth${selectedDepth}Tb;
         |  localparam integer DEPTH = $selectedDepth;
         |  reg clk = 1'b0;
         |  reg reset = 1'b1;
         |  reg io_push_valid = 1'b0;
         |  wire io_push_ready;
         |  reg [7:0] io_push_payload = 8'h00;
         |  wire io_pop_valid;
         |  reg io_pop_ready = 1'b0;
         |  wire [7:0] io_pop_payload;
         |  reg io_flush = 1'b0;
         |  wire [3:0] io_occupancy;
         |  wire [3:0] io_availability;
         |  integer capacity;
         |  integer sent;
         |  integer received;
         |  integer timeout;
         |
         |  always #5 clk = ~clk;
         |
         |  StreamFifo #(
         |    .DEPTH(DEPTH)
         |  ) dut (
         |    .io_push_valid(io_push_valid),
         |    .io_push_ready(io_push_ready),
         |    .io_push_payload(io_push_payload),
         |    .io_pop_valid(io_pop_valid),
         |    .io_pop_ready(io_pop_ready),
         |    .io_pop_payload(io_pop_payload),
         |    .io_flush(io_flush),
         |    .io_occupancy(io_occupancy),
         |    .io_availability(io_availability),
         |    .clk(clk),
         |    .reset(reset)
         |  );
         |
         |  task tick;
         |    begin
         |      @(posedge clk);
         |      #1;
         |    end
         |  endtask
         |
         |  task fail;
         |    input [255:0] reason;
         |    begin
         |      $$display("FAIL depth=%0d: %0s", DEPTH, reason);
         |      $$display("STATE sent=%0d received=%0d ready=%b valid=%b occupancy=%0d availability=%0d",
         |        sent, received, io_push_ready, io_pop_valid, io_occupancy, io_availability);
         |      $$finish(2);
         |    end
         |  endtask
         |
         |  initial begin
         |    repeat (3) tick;
         |    reset = 1'b0;
         |    tick;
         |    if (io_occupancy !== 0) fail("reset occupancy mismatch");
         |    if (io_availability !== DEPTH) fail("reset availability mismatch");
         |
         |    capacity = DEPTH;
         |    for (sent = 0; sent < capacity; sent = sent + 1) begin
         |      io_push_payload = 8'h40 + sent;
         |      io_push_valid = 1'b1;
         |      timeout = 0;
         |      while (!io_push_ready && timeout < 50) begin
         |        tick;
         |        timeout = timeout + 1;
         |      end
         |      if (!io_push_ready) fail("push timeout");
         |      tick;
         |      if (io_occupancy !== (sent + 1)) fail("occupancy mismatch after push");
         |      if (io_availability !== (DEPTH - sent - 1)) fail("availability mismatch after push");
         |    end
         |    io_push_valid = 1'b0;
         |    tick;
         |    if (io_push_ready !== 1'b0) fail("fifo did not report full");
         |    if (io_occupancy !== capacity) fail("full occupancy mismatch");
         |    if (io_availability !== 0) fail("full availability mismatch");
         |
         |    io_pop_ready = 1'b1;
         |    received = 0;
         |    timeout = 0;
         |    while (received < capacity && timeout < 200) begin
         |      if (io_pop_valid) begin
         |        if (io_pop_payload !== (8'h40 + received))
         |          fail("payload ordering mismatch");
         |        received = received + 1;
         |      end
         |      tick;
         |      if (io_occupancy !== (capacity - received)) fail("occupancy mismatch after pop");
         |      if (io_availability !== received) fail("availability mismatch after pop");
         |      timeout = timeout + 1;
         |    end
         |    if (received != capacity) fail("pop timeout");
         |    io_pop_ready = 1'b0;
         |    tick;
         |    if (io_pop_valid !== 1'b0) fail("fifo did not become empty");
         |    if (io_occupancy !== 0) fail("empty occupancy mismatch");
         |    if (io_availability !== DEPTH) fail("empty availability mismatch");
         |
         |    io_push_payload = 8'hA5;
         |    io_push_valid = 1'b1;
         |    timeout = 0;
         |    while (!io_push_ready && timeout < 50) begin
         |      tick;
         |      timeout = timeout + 1;
         |    end
         |    if (!io_push_ready) fail("post-drain push timeout");
         |    tick;
         |    if (io_occupancy !== 1) fail("post-drain occupancy mismatch");
         |    if (io_availability !== (DEPTH - 1)) fail("post-drain availability mismatch");
         |    io_push_valid = 1'b0;
         |    io_pop_ready = 1'b1;
         |    timeout = 0;
         |    while (!io_pop_valid && timeout < 50) begin
         |      tick;
         |      timeout = timeout + 1;
         |    end
         |    if (!io_pop_valid) fail("post-wrap pop timeout");
         |    if (io_pop_payload !== 8'hA5) fail("post-wrap payload mismatch");
         |    tick;
         |    io_pop_ready = 1'b0;
         |    if (io_occupancy !== 0) fail("post-wrap occupancy mismatch");
         |
         |    io_push_payload = 8'hA6;
         |    io_push_valid = 1'b1;
         |    timeout = 0;
         |    while (!io_push_ready && timeout < 50) begin
         |      tick;
         |      timeout = timeout + 1;
         |    end
         |    if (!io_push_ready) fail("pre-flush push timeout");
         |    tick;
         |    io_push_valid = 1'b0;
         |    io_flush = 1'b1;
         |    tick;
         |    io_flush = 1'b0;
         |    tick;
         |    if (io_pop_valid !== 1'b0) fail("flush did not discard queued data");
         |    if (io_occupancy !== 0) fail("flush occupancy mismatch");
         |    if (io_availability !== DEPTH) fail("flush availability mismatch");
         |
         |    $$display("PASS depth=%0d", DEPTH);
         |    $$finish;
         |  end
         |endmodule
         |""".stripMargin
    Files.write(testbench, source.getBytes(StandardCharsets.UTF_8))

    val compileLog = run(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-s",
        s"StreamFifoDepth${selectedDepth}Tb",
        "-o",
        executable.toString,
        rtl.toString,
        testbench.toString
      )
    )
    assert(compileLog._1 == 0, compileLog._2)
    val simulationLog = run(directory, Seq("vvp", executable.toString))
    if (
      simulationLog._1 != 0 ||
      !simulationLog._2.contains(s"PASS depth=$selectedDepth")
    ) {
      println(s"--- BEGIN PARAMETERIZED FIFO RTL depth=$selectedDepth ---")
      println(read(rtl))
      println(s"--- END PARAMETERIZED FIFO RTL depth=$selectedDepth ---")
    }
    assert(simulationLog._1 == 0, simulationLog._2)
    assert(
      simulationLog._2.contains(s"PASS depth=$selectedDepth"),
      simulationLog._2
    )
  }

  private def synthesizeDepth(
      directory: Path,
      rtl: Path,
      selectedDepth: Int
  ): Unit = {
    val script = directory.resolve(s"stream_fifo_depth_$selectedDepth.ys")
    Files.write(
      script,
      s"""read_verilog -defer ${rtl.toString}
         |chparam -set DEPTH $selectedDepth StreamFifo
         |hierarchy -check -top StreamFifo
         |synth -top StreamFifo
         |check -assert
         |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )
    val result = run(directory, Seq("yosys", "-q", "-s", script.toString))
    assert(result._1 == 0, result._2)
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

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-streamfifo-depth-test-")
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
