package spinal.core.internals

import spinal.core._
import scala.collection.mutable
import java.util.regex.{Matcher, Pattern}

/** Delay cosmetic names until symbolic widths, arrays and documentation have
  * finished using the native declaration identities and their original names.
  */
private[internals] object NativeAreaLocalNames {
  private object Key
  private object ConnectionsKey
  private object InstancesKey
  private case class Instance(child: Component, original: String, short: String, definition: String)
  private case class Scope(id: Int, requests: Vector[(BaseType, String, String)], instances: Vector[Instance]) {
    def begin = s"// native_area_scope_${id}_begin"
    def end = s"// native_area_scope_${id}_end"
  }
  private def scopes(component: Component) = component.userCache.getOrElseUpdate(Key,
    mutable.ArrayBuffer.empty[Scope]).asInstanceOf[mutable.ArrayBuffer[Scope]]

  // String overrides are native interface wrappers. Direct aliases retain a
  // declaration/literal override and must never acquire the child's name.
  def captureConnections(printer: ComponentEmitterVerilog): Unit = {
    val refs = new java.util.IdentityHashMap[BaseType, String]()
    printer.component.children.foreach(_.getOrdredNodeIo.foreach { port =>
      printer.referencesOverrides.get(port).foreach {
        case name: String if name.matches("[A-Za-z_][A-Za-z0-9_$]*") => refs.put(port, name)
        case _ =>
      }
    })
    printer.component.userCache(ConnectionsKey) = refs
  }
  def captureInstance(component: Component, child: Component, definition: String): Unit = {
    val instances = component.userCache.getOrElseUpdate(InstancesKey,
      new java.util.IdentityHashMap[Component, String]()).asInstanceOf[java.util.IdentityHashMap[Component, String]]
    require(child.parent eq component, "Area child lost its native parent")
    instances.put(child, definition)
  }

  def retain(body: String, requests: Vector[(BaseType, String)], children: Vector[(Component, String)]): String = {
    if (requests.isEmpty && children.isEmpty) return body
    val component = requests.headOption.map(_._1.component).getOrElse(children.head._1.parent)
    require(requests.forall(_._1.component eq component), "one generated scope has one declaring component")
    val entries = scopes(component)
    val instances = children.map { case (child, short) =>
      require(child.parent eq component, "one generated scope has one instance parent")
      val definitions = component.userCache(InstancesKey).asInstanceOf[java.util.IdentityHashMap[Component, String]]
      val definition = Option(definitions.get(child)).getOrElse(
        throw new IllegalArgumentException("Area child lacks authenticated native instance extraction"))
      Instance(child, child.getName(), short, definition)
    }
    val scope = Scope(entries.size, requests.map { case (leaf, short) => (leaf, leaf.getName(), short) }, instances)
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
      def allocate(requested: String): String = {
        var candidate = requested
        var suffix = 2
        while (reserved(candidate)) { candidate = requested + "_" + suffix; suffix += 1 }
        reserved += candidate
        candidate
      }
      def declarationCount(name: String): Int = {
        val declaration = ("(?m)^\\s*(?:wire|reg)\\s+(?:signed\\s+)?(?:\\[[^\\n]+?\\]\\s*)?" +
          Pattern.quote(name) + "\\s*(?:;|\\[)").r
        declaration.findAllIn(body).size
      }
      val instanceNames = mutable.LinkedHashMap.empty[String, (String, String)]
      scope.instances.foreach { instance =>
        val child = instance.child
        require((child.parent eq component) && child.getName() == instance.original,
          "Area naming lost its native child identity")
        val short = allocate(instance.short)
        instanceNames(instance.original) = short -> instance.definition
        val refs = component.userCache.get(ConnectionsKey).map(
          _.asInstanceOf[java.util.IdentityHashMap[BaseType, String]])
        child.getOrdredNodeIo.foreach { port =>
          refs.flatMap(r => Option(r.get(port))).foreach { original =>
            val prefix = instance.original + "_"
            // Prefix removal is authorized by the exact native port override;
            // spelling alone never enrolls a parent signal or user alias.
            if (original.startsWith(prefix) && !original.startsWith("_zz") && declarationCount(original) == 1) {
              names(original) = allocate(short + "_" + original.substring(prefix.length))
            }
          }
        }
      }
      scope.requests.foreach { case (leaf, original, requested) =>
        require((leaf.component eq component) && leaf.getName() == original && !original.startsWith("_zz"),
          "Area naming lost its native declaration identity")
        val declaration = ("(?m)^\\s*(?:wire|reg)\\s+(?:signed\\s+)?(?:\\[[^\\n]+?\\]\\s*)?" +
          Pattern.quote(original) + "\\s*(?:;|\\[)").r
        val count = declaration.findAllIn(body).size
        // A native Vec leaf may have been consumed by final array publication.
        // Its array name is allocated by that publisher, outside this policy.
        if (count == 1) {
          names(original) = allocate(requested)
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
      val instanceCounts = mutable.HashMap.empty[String, Int].withDefaultValue(0)
      val renamed = ParameterizedVerilogVecs.mapReferenceCode(body.split("\n", -1).toVector) { code =>
        identifier.replaceAllIn(code, token => Matcher.quoteReplacement {
          instanceNames.get(token.matched) match {
            case Some((short, definition)) if code.substring(token.end).matches("\\s*\\(.*") &&
                (code.substring(0, token.start).trim == ")" ||
                 code.substring(0, token.start).replaceAll("\\(\\*.*?\\*\\)", "").trim == definition) =>
              instanceCounts(token.matched) += 1
              short
            case _ if ParameterizedVerilogVecs.isSignalReference(code, token.start, token.end) =>
              names.getOrElse(token.matched, token.matched)
            case _ => token.matched
          }
        })
      }.mkString("\n")
      require(instanceNames.keys.forall(instanceCounts(_) == 1),
        "Area naming requires exactly one authenticated local child instance")
      text.substring(0, start) + renamed + text.substring(stop + scope.end.length)
    }
  }
}
