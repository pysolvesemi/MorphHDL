package spinal.core.internals

import scala.collection.mutable

/** Final whitespace-only layout of already validated native structural output.
  * No declaration, expression, name, line ending or intra-line byte is rewritten.
  * In particular this is not an HDL reconstruction or simplification pass.
  */
private[internals] object NativeStructuralIndentation {
  def publish(text: String): String = {
    val blocks = mutable.ArrayBuffer.empty[String]
    val continuations = mutable.ArrayBuffer.empty[Int]
    val open = Map("module" -> "endmodule", "generate" -> "endgenerate",
      "begin" -> "end", "case" -> "endcase", "casex" -> "endcase",
      "casez" -> "endcase", "function" -> "endfunction", "task" -> "endtask",
      "fork" -> "join")
    val close = open.values.toSet
    var blockComment = false
    var attribute = false
    var quoted = false
    var escape = false
    var directiveContinuation = false
    // Preprocessor alternatives must start at the same structural position.
    val branches = mutable.ArrayBuffer.empty[(Vector[String], Vector[Int])]
    val exits = mutable.ArrayBuffer.empty[Option[(Vector[String], Vector[Int])]]
    def state = blocks.toVector -> continuations.toVector
    def restore(s: (Vector[String], Vector[Int])): Unit = {
      blocks.clear(); blocks ++= s._1
      continuations.clear(); continuations ++= s._2
    }
    text.split("\n", -1).map { line =>
      val body = line.dropWhile(c => c == ' ' || c == '\t')
      val protectedStart = blockComment || attribute || quoted
      if (!protectedStart && (body.startsWith("`") || directiveContinuation)) {
        val command = body.takeWhile(c => !c.isWhitespace)
        if (!directiveContinuation) command match {
          case "`ifdef" | "`ifndef" => branches += state; exits += None
          case "`else" | "`elsif" if branches.nonEmpty =>
            exits(exits.size - 1).foreach(s => require(s == state, "inconsistent native directive branches"))
            exits(exits.size - 1) = Some(state)
            restore(branches.last)
          case "`endif" if branches.nonEmpty =>
            exits.remove(exits.size - 1).foreach(s => require(s == state, "inconsistent native directive branches"))
            branches.remove(branches.size - 1)
          case _ =>
        }
        directiveContinuation = line.stripSuffix("\r").endsWith("\\")
        line
      } else {
        val tokens = mutable.ArrayBuffer.empty[String]
        var i = 0
        while (i < line.length) {
          if (quoted) {
            val c = line(i); i += 1
            if (escape) escape = false
            else if (c == '\\') escape = true
            else if (c == '"') quoted = false
          } else if (blockComment) {
            if (line.startsWith("*/", i)) { blockComment = false; i += 2 } else i += 1
          } else if (attribute) {
            if (line.startsWith("*)", i)) { attribute = false; i += 2 }
            else if (line(i) == '"') { quoted = true; i += 1 } else i += 1
          } else if (line.startsWith("//", i)) i = line.length
          else if (line.startsWith("/*", i)) { blockComment = true; i += 2 }
          else if (line.startsWith("(*", i) && line.take(i).reverse.dropWhile(_.isWhitespace).headOption != Some('@')) {
            attribute = true; i += 2
          } else if (line(i) == '"') { quoted = true; i += 1 }
          else if (line(i) == '\\') {
            i += 1
            while (i < line.length && !line(i).isWhitespace) i += 1
          } else if (line(i).isLetter || line(i) == '_' || line(i) == '$') {
            val start = i; i += 1
            while (i < line.length && (line(i).isLetterOrDigit || line(i) == '_' || line(i) == '$')) i += 1
            tokens += line.substring(start, i)
          } else { if (!line(i).isWhitespace) tokens += line(i).toString; i += 1 }
        }
        // An escaped physical newline belongs to the string, not to the
        // first character on its following line.
        if (quoted && escape) escape = false
        // Dedent only closers at the start of this physical line. Inline
        // end/else/begin and parameter/port continuation transitions still update
        // the same stacks below, without changing any of their token bytes.
        val leading = tokens.takeWhile(t => close(t) || t == ")" || t == "]" || t == "}")
        val depth = blocks.size - leading.count(close)
        val remaining = continuations.dropRight(leading.count(t => t == ")" || t == "]" || t == "}"))
        val headerClose = leading.exists(t => t == ")") && blocks.toVector == Vector("endmodule") &&
          continuations.headOption.contains(0) && remaining.isEmpty
        val level = if (headerClose) 0 else math.max(depth, remaining.lastOption.map(_ + 1).getOrElse(0))
        tokens.foreach { token =>
          if (close(token)) {
            require(blocks.nonEmpty && blocks.last == token, "unbalanced native structural output: " + token)
            blocks.remove(blocks.size - 1)
          } else if (open.contains(token)) blocks += open(token)
          else if (token == "(" || token == "[" || token == "{") continuations += level
          else if (token == ")" || token == "]" || token == "}") {
            require(continuations.nonEmpty, "unbalanced native continuation")
            continuations.remove(continuations.size - 1)
          }
        }
        if (protectedStart || tokens.isEmpty) line else "  " * level + body
      }
    }.mkString("\n")
  }
}
