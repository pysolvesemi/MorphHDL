package spinal.core.internals

import java.util.regex.Pattern
import org.scalatest.funsuite.AnyFunSuite
import scala.util.Random

/** Differential source tests: output must match the frozen pre-59i parser.
  * The substring check authorizes nothing; both exact patterns still decide.
  */
class TypedBalancedReductionDeclarationFilterTests extends AnyFunSuite {
  private val target = ExternalParameterizedVerilogNativeFallback
  private val method = target.getClass.getDeclaredMethods.filter(_.getName == "rewriteDeclarationLine").toVector match {
    case Vector(found) => found.setAccessible(true); found
    case other => throw new IllegalStateException("missing/ambiguous declaration parser: " + other)
  }
  private def rewrite(line: String, widths: Vector[(String, String)]): String =
    method.invoke(target, line, widths).asInstanceOf[String]
  private def parity(line: String, widths: Vector[(String, String)]): Unit =
    assert(rewrite(line, widths) == reference(line, widths), line + " / " + widths)

  test("packed and scalar declarations retain qualifiers attributes comments and terminators") {
    for (kind <- Vector("input", "output", "inout", "wire", "reg", "logic");
         qualifier <- Vector("", "wire ", "reg signed ", "signed ");
         packed <- Vector("", "[4:0] ");
         prefix <- Vector("  ", "  (* keep = \"true\" *) ", "(* x = 1 *) (* y = 2 *) ");
         suffix <- Vector(";", ",", "", " /* retained */ ;")) {
      val line = prefix + kind + " " + qualifier + packed + "leaf" + suffix
      parity(line, Vector("leaf" -> "[WIDTH-1:0]", "unrelated" -> "[OTHER-1:0]"))
    }
  }

  test("identifier substrings and regex metacharacters remain exact matches") {
    val names = Vector("a", "aa", "a$1", "a.b", "[a]", "\\escaped", "signal", "signal_next")
    for (name <- names; other <- names) {
      val line = "wire [2:0] " + name + ";"
      parity(line, Vector(other -> "[WIDTH-1:0]"))
    }
  }

  test("non-declarations and complex declarations retain the existing acceptance boundary") {
    val lines = Vector("assign leaf = rhs;", "// wire leaf;", "parameter leaf = 3;",
      "localparam leaf = 1;", "always @(posedge clk)", "wire leaf [0:3];",
      "wire leaf = rhs;", "wire [3:0] leaf, other;", "wire \\escaped name ;",
      "(* note = \"leaf\" *) wire other;", "wire [leaf-1:0] other;", "")
    lines.foreach(parity(_, Vector("leaf" -> "[WIDTH-1:0]", "other" -> "[DEPTH-1:0]")))
  }

  test("repeated entries and text introduced by prior replacements keep sequential parser semantics") {
    val cases = Vector(
      Vector("leaf" -> "[A-1:0]", "leaf" -> "[B-1:0]"),
      Vector("leaf" -> "[WIDTH-1:0]", "WIDTH" -> "[Z-1:0]"),
      Vector("leaf" -> "[Q-1:0] alias; wire", "alias" -> "[R-1:0]"),
      Vector("absent" -> "[A-1:0]", "leaf" -> "[B-1:0]", "absent" -> "[C-1:0]"))
    cases.foreach(parity("wire leaf;", _))
  }

  test("randomized declaration inventories match the unfiltered parser byte for byte") {
    val random = new Random(5909L)
    val names = Vector.tabulate(64)(i => "leaf_" + i) ++ Vector("a", "aa", "x$1", "xx$1")
    for (_ <- 0 until 2048) {
      val widths = random.shuffle(names).take(random.nextInt(names.size)).map { name =>
        name -> ("[W_" + random.nextInt(8) + "-1:0]")
      }
      val line = (if (random.nextBoolean()) "(* keep = 1 *) " else "") +
        (if (random.nextBoolean()) "wire " else "output reg signed ") +
        (if (random.nextBoolean()) "[5:0] " else "") + names(random.nextInt(names.size)) +
        (if (random.nextBoolean()) ";" else " /* comment */ ,")
      parity(line, widths)
    }
  }

  test("large unrelated leaf inventories retain the exact one relevant declaration") {
    val widths = Vector.tabulate(4096)(i => ("other_" + i) -> "[OTHER-1:0]") :+
      ("selected_tag" -> "[TAG_WIDTH-1:0]")
    val input = "  output wire [2:0] selected_tag;"
    assert(rewrite(input, widths) == "  output wire [TAG_WIDTH-1:0] selected_tag;")
    parity(input, widths)
  }

  private def reference(
      line: String,
      widthsByName: Vector[(String, String)]
  ): String = {
    val trimmed = line.trim
    // Native Verilog may prefix a declaration with one or more synthesis
    // attributes. Those attributes are syntax attached to the declaration,
    // not a reason to hide its retained packed-width identity from this pass.
    val declarationLine =
      "^(?:\\(\\*.*?\\*\\)\\s*)*(?:input|output|inout|wire|reg|logic)\\b".r
        .findPrefixOf(trimmed)
        .nonEmpty
    if (!declarationLine) return line

    widthsByName.foldLeft(line) { case (current, (name, range)) =>
      val quotedName = Pattern.quote(name)
      val declarationEnd = "(?=\\s*(?:/\\*.*?\\*/\\s*)*(?:[,;]|$))"
      val packedPattern =
        ("(\\[[^\\]]+\\])(\\s+)(" + quotedName + ")" + declarationEnd).r
      var replaced = false
      val withRange = packedPattern.replaceAllIn(
        current,
        matched => {
          if (replaced) matched.matched
          else {
            replaced = true
            range + matched.group(2) + matched.group(3)
          }
        }
      )
      if (replaced) withRange
      else {
        val scalarPattern =
          ("(\\s+)(" + quotedName + ")" + declarationEnd).r
        var inserted = false
        scalarPattern.replaceAllIn(
          withRange,
          matched => {
            if (inserted) matched.matched
            else {
              inserted = true
              matched.group(1) + range + " " + matched.group(2)
            }
          }
        )
      }
    }
  }

}
