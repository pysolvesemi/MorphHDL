package morphhdl.compiler

import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

/**
  * Parser bridge for natural Scala if syntax in explicitly MorphHDL-aware source units.
  * The overloaded typed frontend bridge is authoritative for Boolean versus HdlBool.
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

  private def helperSelect: Tree = {
    val root = Ident(termNames.ROOTPKG)
    val morphhdl = Select(root, TermName("morphhdl"))
    val frontend = Select(morphhdl, TermName("frontend"))
    val helper = Select(frontend, TermName("NaturalSymbolicConditional"))
    Select(helper, TermName("select"))
  }

  private final class NaturalIfTransformer extends Transformer {
    override def transform(tree: Tree): Tree = tree match {
      case original @ If(condition, ifTrue, ifFalse) =>
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
      if (eligible(unit)) unit.body = new NaturalIfTransformer().transform(unit.body)
  }
}
