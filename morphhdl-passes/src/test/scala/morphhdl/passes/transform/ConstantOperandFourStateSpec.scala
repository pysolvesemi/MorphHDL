package morphhdl.passes.transform

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessLogger}

import morphhdl.ir.v1._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
  * Independent four-state rule oracle. A small structured test renderer emits
  * the actual before/after canonical trees; it never parses generated RTL.
  * This supplements, and does not replace, the native shared-witness proof.
  */
final class ConstantOperandFourStateSpec extends AnyFunSuite with Matchers {
  private val moduleId = ModuleId.unsafe("module.constant-oracle")
  private val scopeId = ScopeId.unsafe("scope.constant-oracle")
  private val aId = SymbolId.unsafe("symbol.oracle.a")
  private val sId = SymbolId.unsafe("symbol.oracle.signed-a")
  private val pId = SymbolId.unsafe("symbol.oracle.raw")
  private val root = Paths.get("build", "wa07a-rule-oracle")
  private def w(n: Int): IntExpr = IntExpr.Literal(BigInt(n))
  private def lit(n: Int, width: Int = 1, signed: Boolean = false): RtlExpr =
    RtlExpr.Literal(BigInt(n), width, signed)
  private def ref(id: SymbolId, suffix: String): RtlExpr =
    RtlExpr.Ref(ReferenceId.unsafe("reference.oracle." + suffix), id, scopeId)
  private def predicate(suffix: String): RtlExpr =
    RtlExpr.Binary(RtlBinaryOperator.GreaterThan, ref(aId, suffix), lit(5, 4))

  private final class Case(val label: String, val width: Int, val expr: RtlExpr) {
    val id: SymbolId = SymbolId.unsafe("symbol.oracle.out." + label)
  }

  private def cases: Vector[Case] = {
    val result = Vector.newBuilder[Case]
    def add(label: String, width: Int, expr: RtlExpr): Unit = result += new Case(label, width, expr)
    for {
      op <- Vector(RtlBinaryOperator.BitwiseAnd, RtlBinaryOperator.BitwiseOr, RtlBinaryOperator.BitwiseXor)
      bit <- Vector(0, 1)
      swapped <- Vector(false, true)
      constantWidth <- Vector(1, 32)
    } {
      val label = s"${op.label}-$bit-$swapped-$constantWidth"
      val p = predicate(label)
      val k = lit(bit, constantWidth, signed = constantWidth == 32)
      val expr = if (swapped) RtlExpr.Binary(op, k, p) else RtlExpr.Binary(op, p, k)
      add(label, constantWidth, expr)
    }
    for (op <- Vector(RtlBinaryOperator.BitwiseAnd, RtlBinaryOperator.BitwiseOr, RtlBinaryOperator.BitwiseXor)) {
      val id = op.label
      add(s"raw-$id", 1, RtlExpr.Binary(op, ref(pId, "raw-" + id),
        lit(if (op == RtlBinaryOperator.BitwiseAnd) 1 else 0)))
      add(s"wide-$id", 16, RtlExpr.Binary(op,
        RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, ref(aId, "wide-" + id)), lit(15, 4)))
      add(s"numeric-one-$id", 4, RtlExpr.Binary(op,
        RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, ref(aId, "numeric-one-" + id)), lit(1, 4)))
      add(s"signed-$id", 16, RtlExpr.Binary(op,
        RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, ref(sId, "signed-" + id)), lit(-1, 4, signed = true)))
    }
    add("raw-xor-ones", 1, RtlExpr.Binary(RtlBinaryOperator.BitwiseXor, ref(pId, "raw-xor-ones"), lit(1)))
    add("raw-and-zero", 1, RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, ref(pId, "raw-and-zero"), lit(0)))
    add("raw-or-one", 1, RtlExpr.Binary(RtlBinaryOperator.BitwiseOr, ref(pId, "raw-or-one"), lit(1)))
    add("vector-logical-and", 1, RtlExpr.Binary(RtlBinaryOperator.LogicalAnd, ref(aId, "logical-and"), lit(1)))
    add("vector-logical-or", 1, RtlExpr.Binary(RtlBinaryOperator.LogicalOr, ref(aId, "logical-or"), lit(0)))
    add("double-not-raw", 1, RtlExpr.Unary(RtlUnaryOperator.BitwiseNot,
      RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, ref(pId, "double-not"))))
    add("logical-inversion-wide", 8, RtlExpr.Binary(RtlBinaryOperator.LogicalAnd,
      RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, ref(pId, "logical-inversion")), lit(1)))
    add("double-logical-inversion-wide", 8, RtlExpr.Unary(RtlUnaryOperator.LogicalNot,
      RtlExpr.Unary(RtlUnaryOperator.LogicalNot,
        RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, ref(pId, "double-logical-inversion")))))
    add("logical-constant-vector", 8, RtlExpr.Binary(RtlBinaryOperator.LogicalAnd, lit(2, 4), lit(1)))
    add("shift-zero", 16, RtlExpr.Binary(RtlBinaryOperator.ShiftLeft, ref(sId, "shift"), lit(0, 32)))
    add("mux-true", 16, RtlExpr.Mux(lit(1), ref(aId, "mux-true"), lit(0, 4)))
    add("mux-width-mismatch", 16, RtlExpr.Mux(lit(1), ref(aId, "mux-wide"), lit(0, 16)))
    add("comparison-fence", 8, RtlExpr.Resize(
      RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, predicate("fence"), lit(1)), w(8), Signedness.Unsigned))
    add("inversion-fence", 8, RtlExpr.Resize(
      RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd,
        RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, ref(pId, "inversion-fence")), lit(1)), w(8), Signedness.Unsigned))
    add("nested-widening", 64, RtlExpr.Binary(RtlBinaryOperator.Add,
      RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd,
        RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, ref(aId, "nested")), lit(15, 4)), lit(1, 32)))
    result.result()
  }

  private def design(values: Vector[Case]): Design = {
    def declaration(id: SymbolId, name: String, width: Int, signed: Boolean, direction: PortDirection): Declaration =
      Declaration(id, scopeId, DeclarationKind.Port(direction),
        Some(PackedType(w(width), if (signed) Signedness.Signed else Signedness.Unsigned,
          if (signed) PackedValueSemantics.SignedInteger else PackedValueSemantics.BitVector)),
        NameOrigin.Explicit(name), None, Observability(complete = true, externallyVisible = true))
    val ports = Vector(
      declaration(aId, "a", 4, signed = false, direction = PortDirection.Input),
      declaration(sId, "sa", 4, signed = true, direction = PortDirection.Input),
      declaration(pId, "p", 1, signed = false, direction = PortDirection.Input)
    ) ++ values.zipWithIndex.map { case (value, index) =>
      declaration(value.id, s"out_$index", value.width, signed = false, direction = PortDirection.Output)
    }
    Design(CanonicalIrSchema.schemaVersion, CanonicalIrSchema.stage, moduleId,
      Vector(Module(moduleId, "ConstantRuleOracle", Vector.empty,
        Vector(Scope(scopeId, None, ScopeKind.Module)), Vector.empty, ports,
        values.map(value => Driver(DriverId.unsafe("driver." + value.label), scopeId,
          value.id, DriverKind.Continuous, DriverCoverage.FullObject, value.expr)))))
  }

  private def render(design: Design, name: String): String = {
    val module = design.modules.head
    val names = module.declarations.map(d => d.id -> d.nameOrigin.explicitName.get).toMap
    val fences = ArrayBuffer.empty[String]
    var fenceIndex = 0
    def width(expr: IntExpr): Int = expr match {
      case IntExpr.Literal(n) => n.toInt
      case other => throw new IllegalArgumentException(s"nonliteral oracle width: $other")
    }
    def unsignedLiteral(n: BigInt, bits: Int, signed: Boolean): String = {
      val masked = n & ((BigInt(1) << bits) - 1)
      s"${bits}'${if (signed) "s" else ""}h${masked.toString(16)}"
    }
    def expression(expr: RtlExpr): String = expr match {
      case RtlExpr.Ref(_, target, _, _) => names(target)
      case RtlExpr.Literal(n, bits, signed) => unsignedLiteral(n, bits, signed)
      case RtlExpr.Unary(op, value) =>
        val token = op match {
          case RtlUnaryOperator.BitwiseNot => "~"
          case RtlUnaryOperator.LogicalNot => "!"
          case RtlUnaryOperator.Negate => "-"
        }
        s"($token${expression(value)})"
      case RtlExpr.Binary(op, left, right) =>
        val token = op match {
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
      case RtlExpr.BitSelect(value, index) => s"${expression(value)}[${expression(index)}]"
      case RtlExpr.PartSelect(value, offset, size) =>
        s"${expression(value)}[${width(offset) + width(size) - 1}:${width(offset)}]"
      case RtlExpr.Cast(value, signedness) =>
        val cast = if (signedness == Signedness.Signed) "$signed" else "$unsigned"
        s"$cast(${expression(value)})"
      case RtlExpr.Resize(value, size, signedness) =>
        val source = expression(value)
        val id = s"fence_$fenceIndex"
        fenceIndex += 1
        val signed = if (signedness == Signedness.Signed) "signed " else ""
        fences += s"wire $signed[${width(size) - 1}:0] $id;\nassign $id = $source;"
        id
    }
    val ports = module.declarations.map { declaration =>
      val direction = declaration.kind match {
        case DeclarationKind.Port(PortDirection.Input) => "input"
        case DeclarationKind.Port(PortDirection.Output) => "output"
        case other => throw new IllegalArgumentException(s"unexpected oracle declaration $other")
      }
      val packed = declaration.packedType.get
      val signed = if (packed.signedness == Signedness.Signed) "signed " else ""
      s"$direction wire $signed[${width(packed.width) - 1}:0] ${names(declaration.id)}"
    }
    val assignments = module.drivers.map(d => s"assign ${names(d.target)} = ${expression(d.value)};")
    s"module $name(\n${ports.mkString(",\n")}\n);\n${fences.mkString("\n")}\n${assignments.mkString("\n")}\nendmodule\n"
  }

  private def miter(values: Vector[Case], simulation: Boolean): String = {
    val declarations = values.indices.flatMap { i =>
      Vector(s"wire [${values(i).width - 1}:0] before_$i;", s"wire [${values(i).width - 1}:0] after_$i;")
    }.mkString("\n")
    def instance(module: String, prefix: String): String = {
      val ports = Vector(".a(a)", ".sa(a)", ".p(p)") ++ values.indices.map(i => s".out_$i(${prefix}_$i)")
      s"$module ${prefix}_dut(${ports.mkString(", ")});"
    }
    val body = declarations + "\n" + instance("before_pass", "before") + "\n" + instance("after_pass", "after")
    if (!simulation) {
      val checks = values.indices.map(i => s"(before_$i == after_$i)").mkString(" && ")
      s"module miter(input wire [3:0] a, input wire p, output wire ok);\n$body\nassign ok = $checks;\nendmodule\n"
    } else {
      val checks = values.indices.map { i =>
        s"if (before_$i !== after_$i) begin $$display(\"WA07A_MISMATCH ${values(i).label} a=%b p=%b before=%b after=%b\", a, p, before_$i, after_$i); $$finish; end"
      }.mkString("\n")
      s"""module tb;
reg [3:0] a;
reg p;
integer pattern;
integer bit_index;
integer raw;
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
  for (pattern = 0; pattern < 256; pattern = pattern + 1) begin
    for (bit_index = 0; bit_index < 4; bit_index = bit_index + 1)
      a[bit_index] = four_state((pattern >> (2 * bit_index)) & 3);
    for (raw = 0; raw < 4; raw = raw + 1) begin
      p = four_state(raw);
      #1;
      $checks
    end
  end
  $$display("WA07A_FOUR_STATE_PASS patterns=1024 cases=${values.size}");
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

  private def prepare(stem: String, mutation: Option[String] = None): (Path, Vector[Case]) = {
    val values = cases
    val before = design(values)
    val result = ConstantOperandSimplificationPass.run(before)
    withClue(result.diagnostics.mkString("; ")) { result.isSuccess shouldBe true }
    result.rewrites.size should be >= 30
    val again = ConstantOperandSimplificationPass.run(result.output)
    withClue(again.diagnostics.mkString("; ")) { again.isSuccess shouldBe true }
    again.output shouldBe result.output
    again.rewrites shouldBe empty
    var after = result.output
    mutation.foreach { kind =>
      val selected = values.find(_.label == "raw-bitwise-and").get
      after = after.copy(modules = after.modules.map(module => module.copy(drivers = module.drivers.map { driver =>
        if (driver.target != selected.id) driver
        else driver.copy(value = if (kind == "z-identity") ref(pId, "mutant-z")
          else RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, ref(pId, "mutant-functional")))
      })))
    }
    val path = root.resolve(stem + ".v")
    write(path, render(before, "before_pass") + render(after, "after_pass"))
    write(root.resolve(stem + "-rewrites.txt"), result.rewrites.mkString("\n") + "\n")
    (path, values)
  }

  test("actual transformed trees preserve all four states including wide and signed contexts") {
    val (rtl, values) = prepare("four-state")
    val tb = root.resolve("four-state-tb.v")
    val image = root.resolve("four-state.vvp")
    write(tb, miter(values, simulation = true))
    val compile = command(Seq("iverilog", "-g2001", "-s", "tb", "-o", image.toString, rtl.toString, tb.toString),
      root.resolve("four-state-compile.log"))
    withClue(compile._2) { compile._1 shouldBe 0 }
    val simulation = command(Seq("vvp", image.toString), root.resolve("four-state-simulation.log"))
    withClue(simulation._2) {
      simulation._1 shouldBe 0
      simulation._2 should include("WA07A_FOUR_STATE_PASS")
      simulation._2 should not include "WA07A_MISMATCH"
    }
  }

  test("four-state oracle rejects an intentional raw Z identity mutation") {
    val (rtl, values) = prepare("z-mutation", Some("z-identity"))
    val tb = root.resolve("z-mutation-tb.v")
    val image = root.resolve("z-mutation.vvp")
    write(tb, miter(values, simulation = true))
    val compile = command(Seq("iverilog", "-g2001", "-s", "tb", "-o", image.toString, rtl.toString, tb.toString),
      root.resolve("z-mutation-compile.log"))
    withClue(compile._2) { compile._1 shouldBe 0 }
    val simulation = command(Seq("vvp", image.toString), root.resolve("z-mutation-simulation.log"))
    withClue(simulation._2) {
      simulation._2 should include("WA07A_MISMATCH raw-bitwise-and")
      simulation._2 should not include "WA07A_FOUR_STATE_PASS"
    }
  }

  test("two-state formal rule proof passes and detects a functional mutation") {
    Vector(false, true).foreach { mutate =>
      val stem = if (mutate) "formal-mutation" else "formal"
      val (rtl, values) = prepare(stem, if (mutate) Some("functional") else None)
      val top = root.resolve(stem + "-miter.v")
      write(top, miter(values, simulation = false))
      val script = s"read_verilog ${rtl.toString} ${top.toString}; prep -top miter; flatten; opt; sat -verify -prove ok 1 -show-inputs"
      val formal = command(Seq("yosys", "-Q", "-p", script), root.resolve(stem + ".log"))
      withClue(formal._2) {
        if (mutate) {
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
