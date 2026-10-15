package spinal.idslplugin.components

import scala.collection.mutable

/** Source lexer: comments inside quoted literals are never documentation. */
private[idslplugin] object SourceDocumentation {
  final case class Comment(start: Int, end: Int, text: String, scaladoc: Boolean, standalone: Boolean)
  def scan(source: String): Vector[Comment] = {
    val result = mutable.ArrayBuffer.empty[Comment]
    var i = 0
    def standalone(at: Int): Boolean = source.substring(source.lastIndexOf('\n', at - 1) + 1, at).trim.isEmpty
    while (i < source.length) {
      if (source.startsWith("//", i)) {
        val start = i
        while (i < source.length && source(i) != '\n' && source(i) != '\r') i += 1
        result += Comment(start, i, source.substring(start + 2, i).stripPrefix(" "), false, standalone(start))
      } else if (source.startsWith("/*", i)) {
        val start = i
        val doc = source.startsWith("/**", i)
        var depth = 1
        i += 2
        while (i < source.length && depth > 0) {
          if (source.startsWith("/*", i)) { depth += 1; i += 2 }
          else if (source.startsWith("*/", i)) { depth -= 1; i += 2 }
          else i += 1
        }
        if (depth == 0) {
          val raw = source.substring(start + (if (doc) 3 else 2), math.max(start + (if (doc) 3 else 2), i - 2))
          val text = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)
            .map(_.replaceFirst("^\\s*\\* ?", "")).mkString("\n").trim
          result += Comment(start, i, text, doc, standalone(start))
        }
      } else if (source.startsWith("\"\"\"", i)) {
        val end = source.indexOf("\"\"\"", i + 3)
        i = if (end < 0) source.length else end + 3
      } else if (source(i) == '"') {
        i += 1
        var done = false
        while (i < source.length && !done) {
          if (source(i) == '\\') i = math.min(source.length, i + 2)
          else if (source(i) == '"') { i += 1; done = true }
          else i += 1
        }
      } else if (source(i) == '\'') {
        // Character literals have a closing quote; Scala 2 symbol literals do not.
        val end = if (i + 1 < source.length && source(i + 1) == '\\') {
          if (source.startsWith("\\u", i + 1)) i + 7 else i + 3
        } else i + 2
        if (end < source.length && source(end) == '\'') i = end + 1 else i += 1
      } else i += 1
    }
    result.toVector
  }
  def preceding(source: String, comments: Vector[Comment], start: Int): Vector[Comment] = {
    val result = mutable.ArrayBuffer.empty[Comment]
    var cursor = start
    var searching = true
    comments.filter(c => c.end <= start && c.standalone).reverseIterator.foreach { comment =>
      if (searching) {
        val gap = source.substring(comment.end, cursor)
        if (gap.forall(_.isWhitespace) && gap.count(_ == '\n') <= 1) {
          result.prepend(comment); cursor = comment.start
        } else searching = false
      }
    }
    result.toVector
  }
  def trailing(source: String, comments: Vector[Comment], end: Int): Vector[Comment] =
    comments.find { comment =>
      comment.start >= end && source.substring(end, comment.start).forall(c => c == ' ' || c == '\t' || c == '\r' || c == ';')
    }.toVector
}
