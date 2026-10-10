package spinal.core.internals

import java.util.regex.Pattern
import scala.collection.mutable

/** Publish only identity-certified, owner-local Vec storage as Verilog-2001
  * unpacked arrays. Packed ports and explicit bit packing keep their ABI.
  * No synthesis storage attributes are introduced.
  */
private[internals] object ParameterizedVerilogUnpacked {
  final case class ArrayShape(name: String, width: String, dimensions: Vector[String]) {
    val total: String = (width +: dimensions).map(x => s"($x)").mkString(" * ")
    def element(bit: String): String = {
      val ordinal = wordIndex(bit, width).getOrElse(s"(($bit) / ($width))")
      val indices = dimensions.indices.map { axis =>
        val stride = dimensions.drop(axis + 1).map(x => s"($x)").mkString(" * ")
        val index = if (stride.isEmpty) ordinal else s"($ordinal / ($stride))"
        // Do not wrap the outer index: an invalid address must stay invalid.
        if (axis == 0) s"[$index]" else s"[($index) % (${dimensions(axis)})]"
      }.mkString
      name + indices
    }
    def bit(index: String): String = element(index) + s"[($index) % ($width)]"
  }

  private final case class Selection(offset: String, width: String, end: Int)

  def rewrite(source: String, shapes: Vector[ArrayShape]): String = {
    if (shapes.isEmpty) return source
    val used = mutable.HashSet.empty[String] ++ "[A-Za-z_][A-Za-z0-9_$]*".r.findAllIn(source)
    def allocate(stem: String): String = {
      var value = stem
      var ordinal = 0
      while (used(value)) { ordinal += 1; value = stem + "_" + ordinal }
      used += value
      value
    }
    val byName = shapes.map(x => x.name -> x).toMap
    require(byName.size == shapes.size, "unpacked Vec names must be unique")
    val views = shapes.map(x => x.name -> allocate(x.name + "_packed_view")).toMap
    val usedViews = mutable.HashSet.empty[String]
    val functions = mutable.LinkedHashMap.empty[(String, String), String]
    val wiring = mutable.LinkedHashMap.empty[String, Vector[String]]
    val seen = mutable.HashSet.empty[String]
    def function(shape: ArrayShape, width: String): String =
      functions.getOrElseUpdate(shape.name -> width, allocate(shape.name + "_read"))

    def readCode(code: String, continuous: Boolean): String = {
      val out = new StringBuilder
      var copied = 0
      val identifiers = "[A-Za-z_][A-Za-z0-9_$]*".r.findAllMatchIn(code).toVector
      identifiers.foreach { m =>
        if (m.start >= copied && byName.contains(m.matched) &&
            ParameterizedVerilogVecs.isSignalReference(code, m.start, m.end)) {
          val shape = byName(m.matched)
          val selection = select(code, m.end, shape.total)
          // Prefer direct array reads so blocking writes remain immediately
          // visible. Symbolic procedural reads still need the native-memory
          // helper and its Verilog-2001 sensitivity dependency.
          out.append(code.substring(copied, m.start))
          val offset = readCode(selection.offset, continuous)
          if (normalized(selection.width) == normalized(shape.width) && wordIndex(selection.offset, shape.width).nonEmpty)
            out.append(shape.element(offset))
          else if (normalized(selection.width) == "1") out.append(shape.bit(offset))
          else {
            val direct = for {
              width <- constantProduct(selection.width) if width > 0 && width <= 4096
            } yield {
              val elements = for {
                scalar <- constantProduct(shape.width) if scalar > 0 && width % scalar == 0
                _ <- wordIndex(selection.offset, shape.width)
              } yield (0 until (width / scalar).toInt).reverse.map { i =>
                shape.element(if (i == 0) offset else s"($offset) + (${i * scalar})")
              }
              val parts = elements.getOrElse((0 until width.toInt).reverse.map { i =>
                shape.bit(if (i == 0) offset else s"($offset) + $i")
              })
              if (parts.size == 1) parts.head else parts.mkString("{", ", ", "}")
            }
            direct match {
              case Some(value) => out.append(value)
              case None if continuous =>
                usedViews += shape.name
                out.append(s"${views(shape.name)}[($offset) +: ${selection.width}]")
              case None =>
                usedViews += shape.name
                val name = function(shape, selection.width)
                out.append(s"$name($offset, ${views(shape.name)})")
            }
          }
          copied = selection.end
        }
      }
      out.append(code.substring(copied)).result()
    }
    def reads(text: String, continuous: Boolean = false): String =
      ParameterizedVerilogVecs.mapReferenceCode(text.split("\n", -1).toVector)(code => readCode(code, continuous)).mkString("\n")

    // A field-major whole assignment has a concatenation on its left side.
    // Capture its RHS once, then split using exact retained field widths.
    // Procedural addresses are also sampled before any blocking write.
    val expanded = source.split("\n", -1).toVector.flatMap { line =>
      val concat = "^(\\s*)(assign\\s+)?\\{(.*)\\}\\s*(<=|=)\\s*(.*?)\\s*;\\s*$".r
      line match {
        case concat(indent, continuous, targets, op, rhs) =>
          val parts = splitConcatenation(targets)
          val hasArray = parts.exists(p => byName.keys.exists(n =>
            ("^" + Pattern.quote(n) + "(?:\\s*\\[|\\s*$)").r.findFirstIn(p).nonEmpty))
          if (!hasArray) Vector(line)
          else {
            val selected = parts.map { part =>
              val name = "^[A-Za-z_][A-Za-z0-9_$]*".r.findFirstIn(part).getOrElse("")
              require(byName.contains(name), "unpacked concatenated writes require retained Vec targets")
              val selection = select(part, name.length, byName(name).total)
              require(part.substring(selection.end).trim.isEmpty, "unsupported unpacked concatenated target")
              (name, selection)
            }
            val width = selected.map(x => s"(${x._2.width})").mkString(" + ")
            val value = allocate("vec_concat_value")
            val label = allocate("vec_concat_write")
            val bases = selected.map(_ => allocate("vec_concat_base"))
            val writes = selected.indices.map { i =>
              val (name, selection) = selected(i)
              val low = selected.drop(i + 1).map(x => s"(${x._2.width})").mkString(" + ") match {
                case "" => "0"
                case expression => expression
              }
              val base = if (continuous != null) selection.offset else bases(i)
              s"$indent${if (continuous != null) "assign " else "  "}$name[($base) +: ${selection.width}] $op $value[($low) +: ${selection.width}];"
            }
            if (continuous != null) {
              require(op == "=", "continuous concatenated writes must use =")
              Vector(s"${indent}wire [($width)-1:0] $value;", s"${indent}assign $value = $rhs;") ++ writes
            } else {
              Vector(s"${indent}begin : $label", s"$indent  reg [($width)-1:0] $value;") ++
                bases.map(b => s"$indent  integer $b;") ++ Vector(s"$indent  $value = $rhs;") ++
                selected.indices.map(i => s"$indent  ${bases(i)} = ${selected(i)._2.offset};") ++
                writes ++ Vector(s"${indent}end")
            }
          }
        case _ => Vector(line)
      }
    }
    var generateDepth = 0
    val output = expanded.flatMap { line =>
      if (line.trim == "generate") generateDepth += 1
      if (line.trim == "endgenerate") generateDepth -= 1
      val declaration = shapes.iterator.flatMap { shape =>
        val pattern = ("^(\\s*(?:\\(\\*.*?\\*\\)\\s*)?)(wire|reg)(\\s+signed)?\\s+\\[.*\\]\\s+" +
          Pattern.quote(shape.name) + "\\s*;\\s*$").r
        pattern.findFirstMatchIn(line).map(m => (shape, m))
      }.toVector
      if (declaration.nonEmpty) {
        require(declaration.size == 1, "ambiguous unpacked Vec declaration")
        val (shape, m) = declaration.head
        seen += shape.name
        val axes = shape.dimensions.map(d => s"[0:($d)-1]").mkString
        val signed = Option(m.group(3)).getOrElse("")
        val index = allocate(shape.name + "_pack_index")
        val label = allocate(shape.name + "_pack")
        wiring(shape.name) = Vector(s"  genvar $index;", "  generate",
          s"    for ($index = 0; $index < (${shape.total}); $index = $index + 1) begin : $label",
          s"      assign ${views(shape.name)}[$index] = ${shape.bit(index)};",
          "    end", "  endgenerate")
        Vector(s"${m.group(1)}${m.group(2)}$signed [(${shape.width})-1:0] ${shape.name} $axes;",
          s"  wire [(${shape.total})-1:0] ${views(shape.name)};")
      } else {
        val start = "^(\\s*)(assign\\s+)?([A-Za-z_][A-Za-z0-9_$]*)".r.findFirstMatchIn(line)
        val write = start.filter(m => byName.contains(m.group(3))).flatMap { m =>
          val shape = byName(m.group(3))
          val selection = select(line, m.end, shape.total)
          val tail = line.substring(selection.end)
          "^\\s*(<=|=)\\s*(.*?)\\s*;\\s*$".r.findFirstMatchIn(tail).map(a => (m, shape, selection, a))
        }
        write match {
          case Some((m, shape, selection, assignment)) =>
            val rhs = reads(assignment.group(2), m.group(2) != null)
            val offset = reads(selection.offset)
            val width = selection.width
            val index = allocate(shape.name + "_write_index")
            val label = allocate(shape.name + "_write")
            val value = allocate(shape.name + "_write_value")
            val base = allocate(shape.name + "_write_base")
            val indent = m.group(1)
            val op = assignment.group(1)
            if (normalized(width) == normalized(shape.width) && wordIndex(selection.offset, shape.width).nonEmpty) {
              Vector(indent + (if (m.group(2) != null) "assign " else "") + s"${shape.element(offset)} $op $rhs;")
            } else if (m.group(2) != null) {
              require(op == "=", "continuous Vec writes must use =")
              val body = Vector(s"$indent  for ($index = 0; $index < ($width); $index = $index + 1) begin : $label",
                s"$indent    assign ${shape.bit(s"($offset) + $index")} = $value[$index];",
                s"$indent  end")
              Vector(s"${indent}wire [($width)-1:0] $value;", s"${indent}assign $value = $rhs;",
                s"${indent}genvar $index;") ++
                (if (generateDepth == 0) Vector(s"${indent}generate") ++ body ++ Vector(s"${indent}endgenerate") else body)
            } else {
              // Sample RHS and address once, before any blocking element write.
              // A named procedural block permits local variables in Verilog-2001.
              Vector(s"${indent}begin : $label", s"$indent  integer $index;",
                s"$indent  integer $base;", s"$indent  reg [($width)-1:0] $value;",
                s"$indent  $value = $rhs;", s"$indent  $base = $offset;",
                s"$indent  for ($index = 0; $index < ($width); $index = $index + 1) begin",
                s"$indent    ${shape.bit(s"$base + $index")} $op $value[$index];",
                s"$indent  end", s"${indent}end")
            }
          case None => Vector(reads(line, line.trim.startsWith("assign ")))
        }
      }
    }
    require(seen == byName.keySet, "unpacked Vec publication lost an exact internal declaration")
    val helpers = functions.toVector.flatMap { case ((name, width), functionName) =>
      val shape = byName(name)
      val offset = allocate(functionName + "_offset")
      val dependency = allocate(functionName + "_dependency")
      val index = allocate(functionName + "_index")
      Vector(s"  function [($width)-1:0] $functionName;", s"    input integer $offset;",
        s"    input [(${shape.total})-1:0] $dependency;", s"    integer $index;",
        "    begin", s"      for ($index = 0; $index < ($width); $index = $index + 1)",
        s"        $functionName[$index] = ${shape.bit(s"$offset + $index")};",
        "    end", "  endfunction")
    }
    // Direct reads need neither a packed sensitivity witness nor its wiring.
    val unusedViews = shapes.filterNot(x => usedViews(x.name)).map(x => views(x.name)).toSet
    val keptOutput = output.filterNot(line => unusedViews.exists(name => line.trim.endsWith(s"] $name;")))
    val keptWiring = wiring.iterator.filter { case (name, _) => usedViews(name) }
      .flatMap(_._2).toVector
    val end = keptOutput.indexWhere(_.trim == "endmodule")
    require(end >= 0, "unpacked Vec publication requires a module boundary")
    keptOutput.patch(end, helpers ++ keptWiring, 0).mkString("\n")
  }

  private def splitConcatenation(text: String): Vector[String] = {
    var depth = 0
    var start = 0
    val parts = mutable.ArrayBuffer.empty[String]
    text.indices.foreach { i =>
      if ("([{".contains(text(i))) depth += 1
      if (")]}".contains(text(i))) depth -= 1
      if (text(i) == ',' && depth == 0) { parts += text.substring(start, i).trim; start = i + 1 }
    }
    parts += text.substring(start).trim
    parts.toVector.flatMap { part =>
      if (part.startsWith("{") && part.endsWith("}")) splitConcatenation(part.substring(1, part.length - 1))
      else Vector(part)
    }
  }

  private def strip(value: String): String = {
    val text = value.trim
    if (!text.startsWith("(") || !text.endsWith(")")) return text
    var depth = 0
    val closesEarly = text.indices.dropRight(1).exists { i =>
      if (text(i) == '(') depth += 1
      if (text(i) == ')') depth -= 1
      depth == 0
    }
    if (closesEarly) text else strip(text.substring(1, text.length - 1))
  }
  private def normalized(value: String): String = strip(value.replaceAll("\\s", ""))

  // Only literal products are folded; symbolic widths retain their expressions.
  private def constantProduct(value: String): Option[BigInt] = {
    val text = value.replaceAll("[\\s()]", "")
    if (text.matches("[0-9]+(?:\\*[0-9]+)*")) Some(text.split("\\*").map(BigInt(_)).product)
    else None
  }

  /** Cancel only a proven outer multiplication by the exact scalar width. */
  private def wordIndex(offset: String, width: String): Option[String] = {
    val text = strip(offset)
    if (text == "0") return Some("0")
    val scalar = normalized(width)
    if (scalar == "1") return Some(text)
    if (text.matches("[0-9]+") && scalar.matches("[0-9]+") && BigInt(scalar) > 0 &&
        BigInt(text) % BigInt(scalar) == 0) return Some((BigInt(text) / BigInt(scalar)).toString)
    var depth = 0
    val operators = text.indices.filter { i =>
      if (text(i) == '(' || text(i) == '[') depth += 1
      if (text(i) == ')' || text(i) == ']') depth -= 1
      depth == 0 && "+-*/%?:".contains(text(i))
    }
    if (operators.size != 1 || text(operators.head) != '*') None
    else {
      val at = operators.head
      val left = strip(text.substring(0, at))
      val right = strip(text.substring(at + 1))
      if (normalized(left) == normalized(width)) Some(right)
      else if (normalized(right) == normalized(width)) Some(left)
      else None
    }
  }

  private def select(code: String, from: Int, wholeWidth: String): Selection = {
    var start = from
    while (start < code.length && code(start).isWhitespace) start += 1
    if (start == code.length || code(start) != '[') return Selection("0", wholeWidth, from)
    var end = start + 1
    var depth = 1
    while (end < code.length && depth != 0) {
      if (code(end) == '[') depth += 1
      if (code(end) == ']') depth -= 1
      end += 1
    }
    require(depth == 0, "unterminated packed Vec selection")
    val value = code.substring(start + 1, end - 1)
    var level = 0
    var split = -1
    value.indices.foreach { i =>
      if (value(i) == '(' || value(i) == '[') level += 1
      if (value(i) == ')' || value(i) == ']') level -= 1
      if (value(i) == ':' && level == 0) split = i
    }
    if (split < 0) Selection(value, "1", end)
    else if (split > 0 && value(split - 1) == '+')
      Selection(value.substring(0, split - 1).trim, value.substring(split + 1).trim, end)
    else if (split > 0 && value(split - 1) == '-') {
      val high = value.substring(0, split - 1).trim
      val width = value.substring(split + 1).trim
      Selection(s"($high) - ($width) + 1", width, end)
    } else {
      val high = value.substring(0, split).trim
      val low = value.substring(split + 1).trim
      Selection(low, s"($high) - ($low) + 1", end)
    }
  }
}
