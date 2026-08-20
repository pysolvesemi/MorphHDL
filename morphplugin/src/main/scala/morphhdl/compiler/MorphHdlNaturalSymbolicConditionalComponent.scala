package morphhdl.compiler

import scala.collection.mutable
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

/**
  * Parser bridge for natural Scala `if` syntax with explicit MorphHDL symbolic operands.
  *
  * This phase intentionally runs before typer because a raw Scala `if` requires Boolean and
  * therefore an `HdlBool` condition would otherwise be rejected before the typed MorphHDL
  * bridge can see it. To preserve ordinary Scala control flow, the bridge rewrites only
  * conditions that reference identifiers explicitly declared as HdlBool or HdlInt.
  */
final class MorphHdlNaturalSymbolicConditionalComponent(val global: Global) extends PluginComponent {
  import global._

  override val phaseName: String = "morphhdl-natural-symbolic-conditionals"
  override val runsAfter: List[String] = List("parser")
  override val runsBefore: List[String] = List("namer")

  private def eligible(unit: CompilationUnit): Boolean = {
    val path = Option(unit.source)
      .flatMap(source => Option(source.file))
      .map(_.path.replace('\\', '/'))
      .getOrElse("")
    val content = Option(unit.source).map(_.content.mkString).getOrElse("")
    !path.contains("/frontend/src/main/scala/") &&
      !path.contains("/morphplugin/src/main/scala/") &&
      (content.contains("HdlInt") || content.contains("HdlBool") || content.contains("morphhdl.frontend"))
  }

  private def declaredSymbolicNames(tree: Tree): Set[TermName] = {
    val names = mutable.LinkedHashSet.empty[TermName]
    object Collector extends Traverser {
      private def symbolicType(tpt: Tree): Boolean = {
        val rendered = tpt.toString
        rendered == "HdlBool" || rendered == "HdlInt" ||
        rendered.endsWith(".HdlBool") || rendered.endsWith(".HdlInt")
      }

      override def traverse(current: Tree): Unit = current match {
        case value: ValDef if symbolicType(value.tpt) =>
          names += value.name
          super.traverse(current)
        case _ => super.traverse(current)
      }
    }
    Collector.traverse(tree)
    names.toSet
  }

  private def referencesSymbolic(tree: Tree, symbolicNames: Set[TermName]): Boolean = {
    var found = false
    object Finder extends Traverser {
      override def traverse(current: Tree): Unit = if (!found) current match {
        case Ident(name: TermName) if symbolicNames.contains(name) => found = true
        case _                                                    => super.traverse(current)
      }
    }
    Finder.traverse(tree)
    found
  }

  private def helperSelect: Tree = {
    val root = Ident(termNames.ROOTPKG)
    val morphhdl = Select(root, TermName("morphhdl"))
    val frontend = Select(morphhdl, TermName("frontend"))
    val helper = Select(frontend, TermName("NaturalSymbolicConditional"))
    Select(helper, TermName("selectSymbolic"))
  }

  private final class NaturalIfTransformer(symbolicNames: Set[TermName]) extends Transformer {
    override def transform(tree: Tree): Tree = tree match {
      case original @ If(condition, ifTrue, ifFalse) if referencesSymbolic(condition, symbolicNames) =>
        val rewritten = Apply(
          Apply(
            Apply(helperSelect, List(transform(condition))),
            List(transform(ifTrue))
          ),
          List(transform(ifFalse))
        )
        rewritten.setPos(original.pos)
      case _ => super.transform(tree)
    }
  }

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit =
      if (eligible(unit)) {
        val symbolicNames = declaredSymbolicNames(unit.body)
        if (symbolicNames.nonEmpty) unit.body = new NaturalIfTransformer(symbolicNames).transform(unit.body)
      }
  }
}
