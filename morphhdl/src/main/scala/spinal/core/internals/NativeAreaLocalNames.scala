package spinal.core.internals

import spinal.core._
import scala.collection.mutable
import java.util.regex.{Matcher, Pattern}

/** Delay cosmetic names until symbolic widths, arrays and documentation have
  * finished using the native declaration identities and their original names.
  */
private[internals] object NativeAreaLocalNames {
  private object Key
  private case class Scope(id: Int, requests: Vector[(BaseType, String, String)]) {
    def begin = s"// native_area_scope_${id}_begin"
    def end = s"// native_area_scope_${id}_end"
  }
  private def scopes(component: Component) = component.userCache.getOrElseUpdate(Key,
    mutable.ArrayBuffer.empty[Scope]).asInstanceOf[mutable.ArrayBuffer[Scope]]

  def retain(body: String, requests: Vector[(BaseType, String)]): String = {
    if (requests.isEmpty) return body
    val component = requests.head._1.component
    require(requests.forall(_._1.component eq component), "one generated scope has one declaring component")
    val entries = scopes(component)
    val scope = Scope(entries.size, requests.map { case (leaf, short) => (leaf, leaf.getName(), short) })
    require(!body.contains(scope.begin) && !body.contains(scope.end), "Area naming receipt collision")
    entries += scope
    scope.begin + "\n" + body + "\n" + scope.end
  }

  def publish(component: Component, verilog: String, pc: PhaseContext): String = {
    val identifier = "[A-Za-z_][A-Za-z0-9_$]*".r
    val moduleNames = mutable.HashSet.empty[String] ++ pc.verilogKeywords
    ParameterizedVerilogVecs.mapReferenceCode(verilog.split("\n", -1).toVector) { code =>
      moduleNames ++= identifier.findAllIn(code); code
    }
    scopes(component).foldLeft(verilog) { (text, scope) =>
      val start = text.indexOf(scope.begin)
      val stop = text.indexOf(scope.end)
      require(start >= 0 && stop > start && text.lastIndexOf(scope.begin) == start &&
        text.lastIndexOf(scope.end) == stop, "Area naming lost its exact finalized scope receipt")
      val body = text.substring(start + scope.begin.length, stop)
      val reserved = moduleNames.clone()
      ParameterizedVerilogVecs.mapReferenceCode(body.split("\n", -1).toVector) { code =>
        reserved ++= identifier.findAllIn(code); code
      }
      val names = mutable.LinkedHashMap.empty[String, String]
      scope.requests.foreach { case (leaf, original, requested) =>
        require((leaf.component eq component) && leaf.getName() == original && !original.startsWith("_zz"),
          "Area naming lost its native declaration identity")
        val declaration = ("(?m)^\\s*(?:wire|reg)\\s+(?:signed\\s+)?(?:\\[[^\\n]+?\\]\\s*)?" +
          Pattern.quote(original) + "\\s*(?:;|\\[)").r
        val count = declaration.findAllIn(body).size
        // A native Vec leaf may have been consumed by final array publication.
        // Its array name is allocated by that publisher, outside this policy.
        if (count == 1) {
          var candidate = requested
          var suffix = 2
          while (reserved(candidate)) { candidate = requested + "_" + suffix; suffix += 1 }
          reserved += candidate
          names(original) = candidate
        } else {
          require(count == 0, "Area naming requires exactly one local declaration")
          var live = false
          ParameterizedVerilogVecs.mapReferenceCode(body.split("\n", -1).toVector) { code =>
            live ||= identifier.findAllMatchIn(code).exists(t => t.matched == original &&
              ParameterizedVerilogVecs.isSignalReference(code, t.start, t.end))
            code
          }
          require(!live, "Area naming lost a live local declaration during final publication")
        }
      }
      val renamed = ParameterizedVerilogVecs.mapReferenceCode(body.split("\n", -1).toVector) { code =>
        identifier.replaceAllIn(code, token => Matcher.quoteReplacement(
          if (ParameterizedVerilogVecs.isSignalReference(code, token.start, token.end))
            names.getOrElse(token.matched, token.matched) else token.matched))
      }.mkString("\n")
      text.substring(0, start) + renamed + text.substring(stop + scope.end.length)
    }
  }
}
