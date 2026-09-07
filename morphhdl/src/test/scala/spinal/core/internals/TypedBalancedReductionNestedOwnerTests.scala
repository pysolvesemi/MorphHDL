package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.sys.process.{Process, ProcessLogger}
import morphhdl.MorphVerilog
import morphhdl.frontend.{HdlInt, HdlIntRangeStart, StructuralGenerateIfOps, StructuralGenerateCaseOps}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

private[internals] final class BalancedNestedEscapingResult(count: HdlInt, mode: HdlInt,
    sibling: Boolean) extends Component {
  val words = in(Vec(UInt(5 bits), count))
  val result = out(UInt(5 bits))
  var escaped: UInt = null
  (mode > HdlInt.literal(0)).generateIf("g_producer", "g_sibling") {
    escaped = words.reduceBalancedTree((a: UInt, b: UInt) => a + b)
    if (sibling) result := escaped
    else {
      val consumed = UInt(5 bits).setName("local_consumed").dontSimplifyIt()
      consumed := escaped
    }
  }.otherwise {
    if (sibling) result := escaped
    else {
      val marker = UInt(5 bits).setName("sibling_marker").dontSimplifyIt()
      marker := words(0)
    }
  }
  if (!sibling) result := escaped
}

private[internals] final class BalancedNestedConflictingDriver(count: HdlInt, mode: HdlInt)
    extends Component {
  val words = in(Vec(UInt(5 bits), count))
  val result = out(UInt(5 bits))
  result := words(0)
  (mode > HdlInt.literal(0)).generateIf("g_enabled", "g_disabled") {
    result := words.reduceBalancedTree((a: UInt, b: UInt) => a + b)
  }.otherwise {
    result := words(0)
  }
}

private[internals] final class BalancedNestedCallbackChild extends Component {
  val a, b = in(UInt(5 bits))
  val result = out(UInt(5 bits))
  result := a + b
}

private[internals] final class BalancedNestedUncertifiedChild(count: HdlInt, mode: HdlInt)
    extends Component {
  val words = in(Vec(UInt(5 bits), count))
  val result = out(UInt(5 bits))
  (mode > HdlInt.literal(0)).generateIf("g_child_callback", "g_singleton") {
    result := words.reduceBalancedTree((a: UInt, b: UInt) => {
      val child = new BalancedNestedCallbackChild
      child.a := a
      child.b := b
      child.result
    })
  }.otherwise {
    result := words(0)
  }
}

private[internals] final class BalancedNestedStageLabelCollision(count: HdlInt, mode: HdlInt)
    extends Component {
  setDefinitionName("BalancedNestedStageLabelCollision")
  val words = in(Vec(UInt(5 bits), count))
  val result = out(UInt(5 bits))
  (mode > HdlInt.literal(0)).generateIf("g_outer", "g_other") {
    result := words.reduceBalancedTree((a: UInt, b: UInt) => a + b)
    (count > HdlInt.literal(1))
      .generateIf("morphhdl_balanced_1_active_0", "g_user_single") {
        val marker = UInt(5 bits).setName("user_many").dontSimplifyIt()
        marker := words(0)
      }.otherwise {
        val marker = UInt(5 bits).setName("user_single").dontSimplifyIt()
        marker := words(0)
      }
  }.otherwise {
    result := words(0)
  }
}

/** Packed nested aggregate aliases remain part of the 59i cross-feature join. */
private[internals] final class BalancedNestedAggregateAliasDeferred(count: HdlInt, rows: HdlInt)
    extends Component {
  val words = in(Vec(Vec(UInt(5 bits), count), rows))
  val result = out(Vec(UInt(5 bits), rows))
  ElabFiniteRange.foreach(rows.asElabInt, "aggregate_row") { row =>
    row(result) := row(words).reduceBalancedTree((a: UInt, b: UInt) => a + b)
  }
}

/** The outer Vec keeps three native lanes while the COUNT=2 case admits two. */
private[internals] final class BalancedNestedCountCase(count: HdlInt) extends Component {
  setDefinitionName("BalancedNestedCountCase")
  val words = in(Vec(UInt(5 bits), count)).setName("words")
  val result = out(UInt(5 bits)).setName("result")
  count.generateCase
    .choice(BigInt(1), "g_count_one") { result := words(0) }
    .choice(BigInt(2), "g_count_two") {
      result := words.reduceBalancedTree((a: UInt, b: UInt) => a + b)
    }
    .default("g_count_three") {
      result := words.reduceBalancedTree((a: UInt, b: UInt) => a ^ b)
    }
}

private[internals] final class BalancedNestedCountCaseReference(count: Int) extends Component {
  setDefinitionName(s"BalancedNestedCountCaseReference_n$count")
  val words = in(Bits(5 * count bits)).setName("words")
  val result = out(UInt(5 bits)).setName("result")
  val values = Vector.tabulate(count)(lane => words(lane * 5, 5 bits).asUInt)
  count match {
    case 1 => result := values.head
    case 2 => result := values.reduceBalancedTree((a: UInt, b: UInt) => a + b)
    case _ => result := values.reduceBalancedTree((a: UInt, b: UInt) => a ^ b)
  }
}

private[internals] final class BalancedNestedMirrorChild(count: ElabInt) extends Component {
  val words = in(Vec(UInt(5 bits), count))
  val result = out(UInt(5 bits))
  ElabControl.selectSymbolic(count > 1, "nested-mirror-count", 1) {
    result := words.reduceBalancedTree((a: UInt, b: UInt) => a + b)
  } {
    result := words(0)
  }
}

private[internals] final class BalancedNestedCorruptMirror(count: HdlInt, mutation: String)
    extends Component {
  val words = in(Vec(UInt(5 bits), count))
  val unrelated = in(UInt(5 bits))
  val result = out(UInt(5 bits))
  val child = ElabFormalComponent.parameter(actual = count.asElabInt, name = "COUNT",
    minimum = BigInt(1), maximum = BigInt(3))(childCount => new BalancedNestedMirrorChild(childCount))
  child.words := words
  result := child.result
  val mirror = ParameterizedVec.operationsOf(child.words).collect {
    case write: ParameterizedVecStaticWrite => write
  }.headOption.getOrElse(throw new IllegalStateException("fixture captured no parent-owned static mirror"))
  def changeSnapshot(fieldName: String, value: AnyRef): Unit = {
    // Mutate retained evidence alone. The live native input connection remains
    // unchanged, so simply trusting the complete boundary would miss this.
    val field = classOf[ParameterizedVecStaticWrite].getDeclaredField(fieldName)
    field.setAccessible(true)
    field.set(mirror, value)
  }
  mutation match {
    case "source" => changeSnapshot("source", unrelated)
    case "selected" => changeSnapshot("selected", child.words.vec(1))
    case "removed" =>
      mirror.assignment.removeStatement()
      // Restore the same scalar native wiring without replacing its retained
      // whole-Vec boundary. The removed statement must still be rejected.
      mirror.selected.allowOverride()
      mirror.selected.assignFrom(words.vec(mirror.elementIndex))
    case other => throw new IllegalArgumentException(other)
  }
}

class TypedBalancedReductionNestedOwnerTests extends AnyFunSuite {
  private def text(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def generate(profile: String): String =
    text(TypedBalancedReductionNestedOwnerArtifactWriter.candidate(
      Files.createTempDirectory("balanced-nested-"), profile))

  private def messages(error: Throwable): String = {
    val values = scala.collection.mutable.ArrayBuffer.empty[String]
    var current = error
    while (current != null) {
      values += Option(current.getMessage).getOrElse("")
      current = current.getCause
    }
    values.mkString("\n")
  }

  private def rejected(component: => Component): String = {
    val directory = Files.createTempDirectory("balanced-nested-rejected-")
    val config = TypedBalancedReductionNestedOwnerArtifactWriter.config(directory, "rejected.v")
    val failure = intercept[Exception](MorphVerilog(config)(component))
    assert(!Files.exists(directory.resolve("rejected.v")), "unsafe candidate reached publication")
    messages(failure)
  }

  test("nested case and narrowed if branches retain a tree absent at the COUNT one default") {
    val rtl = generate("conditional")
    Vector("WIDTH = 5", "COUNT = 1", "MODE = 0", "case (MODE)",
      "begin : g_add", "begin : g_xor", "begin : g_or").foreach { expected =>
      assert(rtl.contains(expected), rtl)
    }
    assert("\\bCOUNT\\b\\s*\\)*\\s*>\\s*\\(*\\s*1\\b".r.findFirstIn(rtl).nonEmpty, rtl)
    assert(rtl.contains("morphhdl_balanced_1_active_3"), rtl)
    assert(rtl.contains("morphhdl_balanced_2_active_3"), rtl)
    assert(rtl.contains("morphhdl_balanced_3_active_3"), rtl)
    assert("(?m)^module\\s+BalancedNestedConditional\\b".r.findAllIn(rtl).size == 1, rtl)
  }

  test("finite outer loops retain row indices inside nested reduction branches") {
    val rtl = generate("loop")
    Vector("ROWS = 1", "row_index_1_1 < ROWS", "begin : g_row", "begin : g_row_xor",
      "begin : g_row_add", "morphhdl_balanced_1", "morphhdl_balanced_2").foreach { expected =>
      assert(rtl.contains(expected), rtl)
    }
    val rowReads = "(?m)^.*biases\\[[^;\\n]*\\brow_index_[0-9_]+\\b[^;\\n]*;".r.findAllIn(rtl).toVector
    assert(rowReads.nonEmpty, "row binding disappeared from packed reduction inputs:\n" + rtl)
    assert(rowReads.exists(_.contains("WIDTH")), rtl)
    assert("(?m)^.*words\\[[^;\\n]*\\bword_index_[0-9_]+\\b[^;\\n]*;".r.findFirstIn(rtl).nonEmpty, rtl)
  }

  test("COUNT case narrowing captures only the admitted native carrier prefix and preserves singleton fallback") {
    val directory = Paths.get("target", "increment-59h-count-case").toAbsolutePath.normalize()
    val config = TypedBalancedReductionNestedOwnerArtifactWriter.config(directory, "count_case.v")
    MorphVerilog(config)(new BalancedNestedCountCase(HdlInt.param("COUNT", 1, 1, 3)))
    val rtl = text(directory.resolve("count_case.v"))
    Vector("COUNT = 1", "case (COUNT)", "begin : g_count_one", "begin : g_count_two",
      "begin : g_count_three").foreach(expected => assert(rtl.contains(expected), rtl))
    assert(rtl.contains("morphhdl_balanced_1_active_0"), rtl)
    assert(!rtl.contains("morphhdl_balanced_1_active_1"), rtl)
    assert(rtl.contains("morphhdl_balanced_2_active_1"), rtl)
    def available(command: String): Boolean =
      Process(Seq("sh", "-c", s"command -v $command")).!(ProcessLogger(_ => (), _ => ())) == 0
    val runSimulation = available("iverilog") && available("vvp")
    val runFormal = available("yosys")
    if (runSimulation || runFormal) {
      for (count <- 1 to 3) {
        val referenceModule = s"BalancedNestedCountCaseReference_n$count"
        val referenceDirectory = directory.resolve(s"reference_n$count")
        Files.createDirectories(referenceDirectory)
        SpinalConfig(targetDirectory = referenceDirectory.toString, headerWithDate = false,
          headerWithRepoHash = false).generateVerilog(new BalancedNestedCountCaseReference(count))
        if (runSimulation) {
          val tb = directory.resolve(s"tb_n$count.v")
          val executable = directory.resolve(s"tb_n$count.out")
          val source = s"""module tb;
          |reg [${count * 5 - 1}:0] words;
          |wire [4:0] candidate, reference;
          |integer sample, lane;
          |BalancedNestedCountCase #(.COUNT($count)) dut(.words(words), .result(candidate));
          |$referenceModule ref_dut(.words(words), .result(reference));
          |initial begin
          |  for (sample = 0; sample < 64; sample = sample + 1) begin
          |    for (lane = 0; lane < $count; lane = lane + 1)
          |      words[lane*5 +: 5] = sample*(lane+1) + 7*lane + 3;
          |    #1;
          |    if (candidate !== reference) begin
          |      $$display("FAIL COUNT=$count sample=%0d actual=%0d expected=%0d", sample, candidate, reference);
          |      $$finish;
          |    end
          |  end
          |  $$display("PASS narrowed COUNT=$count");
          |  $$finish;
          |end
          |endmodule
          |""".stripMargin
          Files.write(tb, source.getBytes(StandardCharsets.UTF_8))
          assert(Process(Seq("iverilog", "-g2001", "-s", "tb", "-o", executable.toString,
            directory.resolve("count_case.v").toString,
            referenceDirectory.resolve(referenceModule + ".v").toString, tb.toString)).! == 0)
          val output = Process(Seq("vvp", executable.toString)).!!
          Files.write(directory.resolve(s"simulation_n$count.log"), output.getBytes(StandardCharsets.UTF_8))
          assert(output.contains(s"PASS narrowed COUNT=$count") && !output.contains("FAIL"), output)
        }
        if (runFormal) {
          val miter = directory.resolve(s"miter_n$count.v")
          val source = s"""module CountCaseMiter(input wire [${count * 5 - 1}:0] words, output wire bad);
            |wire [4:0] candidate, reference;
            |BalancedNestedCountCase #(.COUNT($count)) dut(.words(words), .result(candidate));
            |$referenceModule ref_dut(.words(words), .result(reference));
            |assign bad = |(candidate ^ reference);
            |endmodule
            |""".stripMargin
          Files.write(miter, source.getBytes(StandardCharsets.UTF_8))
          val script = directory.resolve(s"formal_n$count.ys")
          val commands = s"""read_verilog ${directory.resolve("count_case.v")} ${referenceDirectory.resolve(referenceModule + ".v")} $miter
            |hierarchy -check -top CountCaseMiter
            |prep -top CountCaseMiter -flatten
            |check -assert
            |sat -verify -prove bad 0 -show-inputs
            |""".stripMargin
          Files.write(script, commands.getBytes(StandardCharsets.UTF_8))
          val output = new StringBuilder
          val logger = ProcessLogger(line => output.append(line).append('\n'),
            line => output.append(line).append('\n'))
          val exitCode = Process(Seq("yosys", "-Q", "-T", "-s", script.toString)).!(logger)
          Files.write(directory.resolve(s"formal_n$count.log"), output.toString.getBytes(StandardCharsets.UTF_8))
          assert(exitCode == 0 && output.toString.contains("SUCCESS"), output.toString)
        }
      }
    }
  }

  test("ordinary child reductions retain canonical parameter bindings and independent result wires") {
    val rtl = generate("hierarchy")
    assert("(?m)^module\\s+BalancedNestedHierarchy\\b".r.findAllIn(rtl).size == 1, rtl)
    assert("(?m)^module\\s+BalancedNestedFormalChild\\b".r.findAllIn(rtl).size == 1, rtl)
    assert("(?m)^\\s+BalancedNestedFormalChild\\s*#".r.findAllIn(rtl).size == 2, rtl)
    Vector("WIDTH", "COUNT").foreach { parameter =>
      val binding = ("\\." + parameter + "\\s*\\(\\s*" + parameter + "\\s*\\)").r
      assert(binding.findAllIn(rtl).size == 2, rtl)
    }
    val modeBinding = "\\.MODE\\s*\\(\\s*\\(*\\s*MODE\\b\\s*\\)*\\s*\\+\\s*\\(*\\s*1\\b\\s*\\)*\\s*\\)".r
    assert(modeBinding.findAllIn(rtl).size == 2, rtl)
    assert(rtl.contains("leftWords") && rtl.contains("rightWords"), rtl)
    assert(rtl.contains("leftResult") && rtl.contains("rightResult"), rtl)
  }

  test("initialized register bridge processes retain their outer loop and branch owners") {
    val rtl = generate("registered-loop")
    Vector("begin : g_registered_row", "begin : g_row_xor", "begin : g_row_add",
      "posedge clk", "reset", "enable", "morphhdl_balanced_1", "morphhdl_balanced_2").foreach { expected =>
      assert(rtl.contains(expected), rtl)
    }
    assert(rtl.contains("COUNT = 1"), rtl)
  }

  test("child component publication suspends and restores an active parent loop owner") {
    val rtl = generate("hierarchy-loop")
    val parentHeader = "(?s)module\\s+BalancedNestedHierarchyLoop\\s*#\\((.*?)\\)\\s*\\(".r
      .findFirstMatchIn(rtl).getOrElse(fail("parent parameter header is missing:\n" + rtl)).group(1)
    Vector("COUNT = 1", "MODE = 0", "ROWS = 1", "WIDTH = 5").foreach { declaration =>
      assert(parentHeader.contains(declaration), parentHeader)
    }
    assert(rtl.contains("begin : g_child_row"), rtl)
    assert("(?m)^module\\s+BalancedNestedFormalChild\\b".r.findAllIn(rtl).size == 1, rtl)
    assert("(?m)^\\s+BalancedNestedFormalChild\\s*#".r.findAllIn(rtl).size == 1, rtl)
    assert("(?s)biases\\[[^;]*\\bchild_row_index_[0-9_]+\\b[^;]*WIDTH".r.findFirstIn(rtl).nonEmpty, rtl)
    Vector("WIDTH", "COUNT").foreach { parameter =>
      assert(("\\." + parameter + "\\s*\\(\\s*" + parameter + "\\s*\\)").r
        .findAllIn(rtl).size == 1, rtl)
    }
    val modeBinding = "\\.MODE\\s*\\(\\s*\\(*\\s*MODE\\b\\s*\\)*\\s*\\+\\s*\\(*\\s*1\\b\\s*\\)*\\s*\\)".r
    assert(modeBinding.findAllIn(rtl).size == 1, rtl)
  }

  test("fresh nested publication has deterministic names and no duplicate component definitions") {
    assert(generate("conditional") == generate("conditional"))
    assert(generate("loop") == generate("loop"))
    assert(generate("hierarchy") == generate("hierarchy"))
  }

  test("a nested user generate label cannot collide with a balanced stage label in its outer owner") {
    val directory = Files.createTempDirectory("balanced-nested-stage-label-")
    MorphVerilog(TypedBalancedReductionNestedOwnerArtifactWriter.config(directory, "collision.v")) {
      new BalancedNestedStageLabelCollision(
        HdlInt.param("COUNT", 1, 1, 5), HdlInt.param("MODE", 0, 0, 1))
    }
    val rtl = text(directory.resolve("collision.v"))
    assert(rtl.contains("begin : morphhdl_balanced_1_active_0"), rtl)
    assert(rtl.contains("morphhdl_balanced_1_1_active_0"), rtl)
    assert("\\bbegin\\s*:\\s*morphhdl_balanced_1_active_0\\b".r.findAllIn(rtl).size == 1, rtl)
  }

  test("a reduction result cannot escape to its outer component scope") {
    val detail = rejected(new BalancedNestedEscapingResult(
      HdlInt.param("COUNT", 1, 1, 5), HdlInt.param("MODE", 0, 0, 1), sibling = false))
    assert(Vector("OWNER", "SCOPE", "ESCAP", "REFERENCE").exists(detail.contains), detail)
  }

  test("sibling structural branches cannot consume each other's reduction results") {
    val detail = rejected(new BalancedNestedEscapingResult(
      HdlInt.param("COUNT", 1, 1, 5), HdlInt.param("MODE", 0, 0, 1), sibling = true))
    assert(Vector("OWNER", "SCOPE", "ESCAP", "REFERENCE").exists(detail.contains), detail)
  }

  test("outer and nested reduction drivers cannot overlap") {
    val detail = rejected(new BalancedNestedConflictingDriver(
      HdlInt.param("COUNT", 1, 1, 5), HdlInt.param("MODE", 0, 0, 1)))
    assert(Vector("DRIVER", "OVERLAP", "ASSIGNMENT").exists(detail.contains), detail)
  }

  test("nested callbacks cannot instantiate uncertified child hardware") {
    val detail = rejected(new BalancedNestedUncertifiedChild(
      HdlInt.param("COUNT", 1, 1, 5), HdlInt.param("MODE", 0, 0, 1)))
    assert(detail.contains("CALLBACK-UNSUPPORTED"), detail)
  }

  test("nested packed aggregate aliases remain fail-closed until the 59i layout integration") {
    val detail = rejected(new BalancedNestedAggregateAliasDeferred(
      HdlInt.param("COUNT", 1, 1, 5), HdlInt.param("ROWS", 1, 1, 3)))
    assert(detail.contains("SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-ALIAS-AGGREGATE-UNSUPPORTED"), detail)
  }

  Vector("source", "selected", "removed").foreach { mutation =>
    test(s"parent-owned child Vec static mirrors reject $mutation mutation") {
      val detail = rejected(new BalancedNestedCorruptMirror(HdlInt.param("COUNT", 1, 1, 3), mutation))
      assert(detail.contains("VEC") && Vector("EVIDENCE", "MISMATCH", "STALE").exists(detail.contains), detail)
    }
  }

  test("invalid finite outer loop bounds fail before reduction publication") {
    val detail = rejected {
      new Component {
        val words = in(Vec(UInt(5 bits), HdlInt.param("COUNT", 1, 1, 5)))
        val result = out(UInt(5 bits))
        result := words(0)
        @dontName val rows = HdlInt.param("ROWS", 1, -1, 3)
        (0 until rows).named("g_invalid", "row").foreach { _ =>
          val observed = UInt(5 bits).dontSimplifyIt()
          observed := words.reduceBalancedTree((a: UInt, b: UInt) => a + b)
        }
      }
    }
    assert(detail.contains("SPINAL-PARAMETERIZED-VERILOG-PROCESS-COUNT-DOMAIN-UNSUPPORTED"), detail)
  }
}
