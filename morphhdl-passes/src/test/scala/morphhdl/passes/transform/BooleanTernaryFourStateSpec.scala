package morphhdl.passes.transform

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessLogger}
import morphhdl.ir.v1._
import morphhdl.passes.adapter.CanonicalIrPassAdapter
import morphhdl.passes.api.WireAliasPassConfiguration
import morphhdl.passes.pipeline.WireAliasPassPipeline
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Independent structured rule oracle; the native backend witness is a separate gate. */
final class BooleanTernaryFourStateSpec extends AnyFunSuite with Matchers {
  import BooleanTernaryTestSupport._
  private val root = Paths.get("build", "wa07b-rule-oracle")
  private final case class Case(label: String, expression: RtlExpr, width: Int = 1, signed: Boolean = false) {
    val id: SymbolId = SymbolId.unsafe("symbol.ternary.output." + label)
  }

  private def cases: Vector[Case] = {
    val result = Vector.newBuilder[Case]
    def add(label: String, expression: RtlExpr, width: Int = 1, signed: Boolean = false): Unit =
      result += Case(label, expression, width, signed)
    for (width <- Vector(1, 8, 32); inverse <- Vector(false, true); kind <- Vector("raw", "word", "predicate", "bitwise", "signed")) {
      val label = s"$kind-$inverse-$width"
      val condition = kind match {
        case "raw" => raw(label)
        case "word" => ref(aId, label)
        case "predicate" => predicate(label)
        case "bitwise" => RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, raw(label))
        case "signed" => RtlExpr.Cast(ref(aId, label), Signedness.Signed)
      }
      add(label, mux(condition, inverse), width)
    }
    val relations = Vector(RtlBinaryOperator.Equal, RtlBinaryOperator.NotEqual,
      RtlBinaryOperator.LessThan, RtlBinaryOperator.LessThanOrEqual,
      RtlBinaryOperator.GreaterThan, RtlBinaryOperator.GreaterThanOrEqual)
    relations.zipWithIndex.foreach { case (operator, index) =>
      add(s"relation-$index", mux(RtlExpr.Binary(operator, ref(aId, s"relation-$index"), lit(1, 3))))
    }
    add("logical-condition", mux(RtlExpr.Binary(RtlBinaryOperator.LogicalOr,
      ref(aId, "logical-a"), ref(bId, "logical-b")), inverse = true), 8)
    add("arithmetic-condition", mux(RtlExpr.Binary(RtlBinaryOperator.Add,
      ref(aId, "arithmetic-a"), lit(1, 3))), 8)
    add("shift-condition", mux(RtlExpr.Binary(RtlBinaryOperator.ShiftLeft,
      ref(aId, "shift-a"), lit(1, 2))), 8)
    add("nested-inverse", mux(mux(raw("nested-inverse"), inverse = true), inverse = true), 8)
    add("nested-branches", RtlExpr.Mux(raw("nested-parent"),
      mux(predicate("nested-yes")), mux(ref(aId, "nested-no"), inverse = true)), 8)
    add("under-unary", RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, mux(raw("under-unary"))), 8)
    add("under-arithmetic", RtlExpr.Binary(RtlBinaryOperator.Add,
      mux(raw("under-arithmetic"), inverse = true), lit(1, 32, signed = true)), 32)
    add("under-relation", RtlExpr.Binary(RtlBinaryOperator.Equal,
      mux(raw("under-relation")), lit(255, 8)))
    add("under-concat", RtlExpr.Concat(Vector(lit(0, 7), mux(raw("under-concat"), inverse = true))), 8)
    add("selected-value", RtlExpr.BitSelect(mux(raw("selected-value")), lit(0)))
    add("selected-index", RtlExpr.BitSelect(ref(aId, "selected-array"), mux(raw("selected-index"))))
    add("part-selected", RtlExpr.PartSelect(mux(raw("part-selected")), w(0), w(1)))
    add("under-resize", RtlExpr.Resize(mux(raw("under-resize"), inverse = true), w(16), Signedness.Unsigned), 16)
    add("under-signed-cast", RtlExpr.Cast(mux(raw("under-signed-cast")), Signedness.Signed), 16, signed = true)
    add("signed-output", mux(raw("signed-output"), inverse = true), 16, signed = true)
    add("constant-fences", RtlExpr.Mux(raw("constant-fences"),
      RtlExpr.Resize(lit(1), w(1), Signedness.Unsigned), RtlExpr.Cast(lit(0), Signedness.Unsigned)), 8)
    add("wider-branches-retained", RtlExpr.Mux(raw("wide-branches"), lit(1, 8), lit(0, 8)), 16)
    add("unsized-branches-retained", RtlExpr.Mux(raw("unsized-branches"),
      lit(1, 32, signed = true), lit(0, 32, signed = true)), 32)
    add("signed-branches-retained", RtlExpr.Mux(raw("signed-branches"),
      lit(-1, 1, signed = true), lit(0, 1, signed = true)), 16, signed = true)
    result.result()
  }

  private def design(values: Vector[Case]): Design = {
    val input = fixture(lit(0))
    input.copy(modules = input.modules.map(module => module.copy(
      declarations = module.declarations.filterNot(_.id == sinkId) ++ values.zipWithIndex.map { case (value, index) =>
        declaration(value.id, s"out_$index", w(value.width), PortDirection.Output, value.signed)
      },
      drivers = values.map(value => Driver(DriverId.unsafe("driver.ternary." + value.label), scopeId,
        value.id, DriverKind.Continuous, DriverCoverage.FullObject, value.expression)))))
  }

  private def render(input: Design, name: String): String = {
    val module = input.modules.head
    val names = module.declarations.map(d => d.id -> d.nameOrigin.explicitName.get).toMap
    val types = module.declarations.map(d => d.id -> d.packedType.get).toMap
    val fences = ArrayBuffer.empty[String]
    var fenceIndex = 0
    def width(value: IntExpr): Int = value match {
      case IntExpr.Literal(n) => n.toInt
      case other => throw new IllegalArgumentException(s"nonliteral rule-oracle width: $other")
    }
    def shape(value: RtlExpr): (Int, Boolean) = value match {
      case RtlExpr.Ref(_, target, _, _) => (width(types(target).width), types(target).signedness == Signedness.Signed)
      case RtlExpr.Literal(_, bits, signed) => (bits, signed)
      case RtlExpr.Unary(RtlUnaryOperator.LogicalNot, _) => (1, false)
      case RtlExpr.Unary(_, child) => shape(child)
      case RtlExpr.Binary(operator, left, right) => operator match {
        case RtlBinaryOperator.LogicalAnd | RtlBinaryOperator.LogicalOr |
            RtlBinaryOperator.Equal | RtlBinaryOperator.NotEqual |
            RtlBinaryOperator.LessThan | RtlBinaryOperator.LessThanOrEqual |
            RtlBinaryOperator.GreaterThan | RtlBinaryOperator.GreaterThanOrEqual => (1, false)
        case RtlBinaryOperator.ShiftLeft | RtlBinaryOperator.ShiftRight => shape(left)
        case _ => val a = shape(left); val b = shape(right); (a._1.max(b._1), a._2 && b._2)
      }
      case RtlExpr.Mux(_, yes, no) => val a = shape(yes); val b = shape(no); (a._1.max(b._1), a._2 && b._2)
      case RtlExpr.Concat(values) => (values.map(shape(_)._1).sum, false)
      case _: RtlExpr.BitSelect => (1, false)
      case RtlExpr.PartSelect(_, _, size) => (width(size), false)
      case RtlExpr.Resize(_, size, signedness) => (width(size), signedness == Signedness.Signed)
      case RtlExpr.Cast(child, signedness) => (shape(child)._1, signedness == Signedness.Signed)
    }
    def fence(source: String, bits: Int, signed: Boolean): String = {
      val id = s"fence_$fenceIndex"
      fenceIndex += 1
      fences += s"wire ${if (signed) "signed " else ""}[${bits - 1}:0] $id;\nassign $id = $source;"
      id
    }
    def expression(value: RtlExpr): String = value match {
      case RtlExpr.Ref(_, target, _, _) => names(target)
      case RtlExpr.Literal(n, bits, signed) =>
        val masked = n & ((BigInt(1) << bits) - 1)
        s"${bits}'${if (signed) "s" else ""}h${masked.toString(16)}"
      case RtlExpr.Unary(operator, child) =>
        val token = operator match {
          case RtlUnaryOperator.LogicalNot => "!"
          case RtlUnaryOperator.BitwiseNot => "~"
          case RtlUnaryOperator.Negate => "-"
        }
        s"($token${expression(child)})"
      case RtlExpr.Binary(operator, left, right) =>
        val token = operator match {
          case RtlBinaryOperator.Add => "+"
          case RtlBinaryOperator.Subtract => "-"
          case RtlBinaryOperator.Multiply => "*"
          case RtlBinaryOperator.Divide => "/"
          case RtlBinaryOperator.Modulo => "%"
          case RtlBinaryOperator.BitwiseAnd => "&"
          case RtlBinaryOperator.BitwiseOr => "|"
          case RtlBinaryOperator.BitwiseXor => "^"
          case RtlBinaryOperator.LogicalAnd => "&&"
          case RtlBinaryOperator.LogicalOr => "||"
          case RtlBinaryOperator.Equal => "=="
          case RtlBinaryOperator.NotEqual => "!="
          case RtlBinaryOperator.LessThan => "<"
          case RtlBinaryOperator.LessThanOrEqual => "<="
          case RtlBinaryOperator.GreaterThan => ">"
          case RtlBinaryOperator.GreaterThanOrEqual => ">="
          case RtlBinaryOperator.ShiftLeft => "<<"
          case RtlBinaryOperator.ShiftRight => ">>"
        }
        s"(${expression(left)} $token ${expression(right)})"
      case RtlExpr.Mux(condition, yes, no) => s"(${expression(condition)} ? ${expression(yes)} : ${expression(no)})"
      case RtlExpr.Concat(values) => values.map(expression).mkString("{", ", ", "}")
      case RtlExpr.BitSelect(child, index) =>
        val source = expression(child)
        val selected = child match {
          case _: RtlExpr.Ref => source
          case _ => val t = shape(child); fence(source, t._1, t._2)
        }
        s"$selected[${expression(index)}]"
      case RtlExpr.PartSelect(child, offset, size) =>
        val source = expression(child)
        val selected = child match {
          case _: RtlExpr.Ref => source
          case _ => val t = shape(child); fence(source, t._1, t._2)
        }
        s"$selected[${width(offset) + width(size) - 1}:${width(offset)}]"
      case RtlExpr.Resize(child, size, signedness) =>
        fence(expression(child), width(size), signedness == Signedness.Signed)
      case RtlExpr.Cast(child, signedness) =>
        val token = if (signedness == Signedness.Signed) "$signed" else "$unsigned"
        s"$token(${expression(child)})"
    }
    val ports = module.declarations.map { d =>
      val direction = d.kind match {
        case DeclarationKind.Port(PortDirection.Input) => "input"
        case DeclarationKind.Port(PortDirection.Output) => "output"
        case other => throw new IllegalArgumentException(s"unexpected rule-oracle declaration: $other")
      }
      val t = d.packedType.get
      s"$direction wire ${if (t.signedness == Signedness.Signed) "signed " else ""}[${width(t.width) - 1}:0] ${names(d.id)}"
    }
    val assignments = module.drivers.map(d => s"assign ${names(d.target)} = ${expression(d.value)};")
    s"module $name(\n${ports.mkString(",\n")}\n);\n${fences.mkString("\n")}\n${assignments.mkString("\n")}\nendmodule\n"
  }

  private def miter(values: Vector[Case], simulation: Boolean): String = {
    val signals = values.indices.flatMap(i => Vector(
      s"wire [${values(i).width - 1}:0] before_$i;", s"wire [${values(i).width - 1}:0] after_$i;")).mkString("\n")
    def instance(name: String, prefix: String): String = {
      val ports = Vector(".a(a)", ".b(b)", ".p(p)") ++ values.indices.map(i => s".out_$i(${prefix}_$i)")
      s"$name ${prefix}_dut(${ports.mkString(", ")});"
    }
    val body = signals + "\n" + instance("before_pass", "before") + "\n" + instance("after_pass", "after")
    if (!simulation) {
      val checks = values.indices.map(i => s"(before_$i == after_$i)").mkString(" && ")
      s"module miter(input wire [2:0] a, b, input wire p, output wire ok);\n$body\nassign ok = $checks;\nendmodule\n"
    } else {
      val checks = values.indices.map(i =>
        s"""if (before_$i !== after_$i) begin $$display("WA07B_MISMATCH ${values(i).label} a=%b b=%b p=%b before=%b after=%b", a, b, p, before_$i, after_$i); $$finish; end""").mkString("\n")
      s"""module tb;
reg [2:0] a, b;
reg p;
integer pattern, bit_index;
$body
function four_state;
  input integer digit;
  begin
    case (digit)
      0: four_state = 1'b0;
      1: four_state = 1'b1;
      2: four_state = 1'bx;
      3: four_state = 1'bz;
    endcase
  end
endfunction
initial begin
  for (pattern = 0; pattern < 16384; pattern = pattern + 1) begin
    for (bit_index = 0; bit_index < 3; bit_index = bit_index + 1) begin
      a[bit_index] = four_state((pattern >> (2 * bit_index)) & 3);
      b[bit_index] = four_state((pattern >> (2 * (bit_index + 3))) & 3);
    end
    p = four_state((pattern >> 12) & 3);
    #1;
    $checks
  end
  $$display("WA07B_FOUR_STATE_PASS patterns=16384 cases=${values.size}");
  $$finish;
end
endmodule
"""
    }
  }

  private def write(path: Path, text: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))
  }
  private def command(args: Seq[String], log: Path): (Int, String) = {
    val lines = ArrayBuffer.empty[String]
    val code = Process(args).!(ProcessLogger(line => lines += line, line => lines += line))
    val text = lines.mkString("\n") + "\n"
    write(log, text)
    (code, text)
  }

  private def prepare(stem: String, all: Boolean = false, mutation: Option[String] = None): (Path, Vector[Case]) = {
    val values = cases
    val before = design(values)
    CanonicalIrPassAdapter.bindFixture(before).isRight shouldBe true
    val standalone = BooleanTernarySimplificationPass.run(before)
    withClue(standalone.diagnostics.mkString("; ")) { standalone.isSuccess shouldBe true }
    standalone.rewrites.size should be >= 40
    val pipeline = WireAliasPassPipeline.run(before, WireAliasPassConfiguration(enabled = true))
    withClue(pipeline.diagnostics.mkString("; ")) { pipeline.isSuccess shouldBe true }
    var after = if (all) pipeline.output else standalone.output
    val again = if (all) WireAliasPassPipeline.run(after, WireAliasPassConfiguration(enabled = true)).output
      else BooleanTernarySimplificationPass.run(after).output
    again shouldBe after
    mutation.foreach { kind =>
      val (label, replacement) = kind match {
        case "raw-z" => "raw-false-1" -> raw("mutant-z")
        case "polarity" => "predicate-false-1" -> not(predicate("mutant-polarity"))
        case "vector" => "word-false-8" -> ref(aId, "mutant-vector")
        case "vector-complement" => "word-true-8" -> RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, ref(aId, "mutant-vector-not"))
        case "widened-complement" => "predicate-true-8" -> RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, predicate("mutant-wide-not"))
      }
      val selected = values.find(_.label == label).get.id
      after = after.copy(modules = after.modules.map(module => module.copy(drivers = module.drivers.map(d =>
        if (d.target == selected) d.copy(value = replacement) else d))))
    }
    val rtl = root.resolve(stem + ".v")
    // Both candidates compare directly with this untouched pre-ALL-passes tree.
    val text = render(before, "before_pass") + render(after, "after_pass")
    text shouldBe (render(before, "before_pass") + render(after, "after_pass"))
    write(rtl, text)
    write(root.resolve(stem + "-rewrites.txt"), (if (all) pipeline.simplifiedExpressions else standalone.rewrites).mkString("\n") + "\n")
    (rtl, values)
  }

  test("standalone and complete pipeline preserve all four states against the common pre-pass tree") {
    Vector(false, true).foreach { all =>
      val stem = if (all) "all-five" else "standalone"
      val (rtl, values) = prepare(stem, all)
      val tb = root.resolve(stem + "-tb.v")
      val image = root.resolve(stem + ".vvp")
      write(tb, miter(values, simulation = true))
      val compile = command(Seq("iverilog", "-g2001", "-s", "tb", "-o", image.toString, rtl.toString, tb.toString), root.resolve(stem + "-compile.log"))
      withClue(compile._2) { compile._1 shouldBe 0 }
      val simulation = command(Seq("vvp", image.toString), root.resolve(stem + "-simulation.log"))
      withClue(simulation._2) {
        simulation._1 shouldBe 0
        simulation._2 should include("WA07B_FOUR_STATE_PASS patterns=16384")
        simulation._2 should not include "WA07B_MISMATCH"
      }
    }
  }

  test("four-state oracle detects raw-Z polarity multi-bit and context-widened complement mutations") {
    Vector("raw-z", "polarity", "vector", "vector-complement", "widened-complement").foreach { mutation =>
      val stem = "mutation-" + mutation
      val (rtl, values) = prepare(stem, mutation = Some(mutation))
      val tb = root.resolve(stem + "-tb.v")
      val image = root.resolve(stem + ".vvp")
      write(tb, miter(values, simulation = true))
      val compile = command(Seq("iverilog", "-g2001", "-s", "tb", "-o", image.toString, rtl.toString, tb.toString), root.resolve(stem + "-compile.log"))
      withClue(compile._2) { compile._1 shouldBe 0 }
      val simulation = command(Seq("vvp", image.toString), root.resolve(stem + "-simulation.log"))
      withClue(simulation._2) {
        simulation._2 should include("WA07B_MISMATCH")
        simulation._2 should not include "WA07B_FOUR_STATE_PASS"
      }
    }
  }

  test("two-state rule proofs cover standalone and all-five output and reject functional mutations") {
    for (all <- Vector(false, true); mutation <- Vector(None, Some("polarity"), Some("widened-complement"))) {
      val stem = s"formal-$all-${mutation.getOrElse("normal")}"
      val (rtl, values) = prepare(stem, all, mutation)
      val top = root.resolve(stem + "-miter.v")
      write(top, miter(values, simulation = false))
      val script = s"read_verilog $rtl $top; prep -top miter; flatten; opt; sat -verify -prove ok 1 -show-inputs"
      val formal = command(Seq("yosys", "-Q", "-p", script), root.resolve(stem + ".log"))
      withClue(formal._2) {
        if (mutation.nonEmpty) {
          formal._1 should not be 0
          formal._2 should include("proof did fail")
        } else {
          formal._1 shouldBe 0
          formal._2 should include("SUCCESS")
        }
      }
    }
  }
}
