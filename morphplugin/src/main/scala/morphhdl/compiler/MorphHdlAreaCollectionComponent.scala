package morphhdl.compiler

import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent

/** Narrow source bridge for zero-based Area collections containing index conditions.
  * Ordinary scalar loops remain Scala loops. Runtime capture is opt-in through
  * preserveConstantLoops, just like the explicit finite-range API.
  */
final class MorphHdlAreaCollectionComponent(val global: Global) extends PluginComponent {
  import global._
  val phaseName = "morphhdl-area-collections"
  val runsAfter = List("parser")
  override val runsBefore = List("morphhdl-typed-elaboration-control", "namer")

  private def core(name: String): Tree =
    Select(Select(Select(Ident(termNames.ROOTPKG), TermName("spinal")), TermName("core")), TermName(name))

  private def qualifiedPath(tree: Tree): Vector[String] = tree match {
    case Ident(name) => Vector(name.toString)
    case Select(owner, name) => qualifiedPath(owner) :+ name.toString
    case _ => Vector.empty
  }
  private def areaBody(tree: Tree, ordinaryImport: Boolean): Boolean = tree match {
    case Block(stats, _) => stats.exists {
      case definition: ClassDef => definition.impl.parents.exists {
        case Ident(TypeName("Area")) => ordinaryImport
        case selected: Select => Set(Vector("spinal", "core", "Area"),
          Vector("_root_", "spinal", "core", "Area"))(qualifiedPath(selected))
        case _ => false
      }
      case _ => false
    }
    case _ => false
  }

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    def apply(unit: CompilationUnit): Unit = {
      val path = unit.source.file.path.replace('\\', '/')
      if (path.contains("/morphruntime/src/main/") || path.contains("/morphplugin/src/main/")) return
      object Rewrite extends Transformer {
        var collections = Set.empty[TermName]
        private var importedArea = false
        private var shadowedArea = false
        private def localArea(trees: List[Tree]): Boolean = trees.exists {
          case d: ClassDef => d.name.toString == "Area"
          case d: TypeDef => d.name.toString == "Area"
          case _ => false
        }
        private def scoped[A](shadow: Set[TermName])(body: => A): A = {
          val old = collections
          val oldImport = importedArea
          val oldShadow = shadowedArea
          collections --= shadow
          try body finally { collections = old; importedArea = oldImport; shadowedArea = oldShadow }
        }
        private def documentationOnly(tree: Tree): Boolean = tree match {
          case Function(_, body) => documentationOnly(body)
          case Match(_, cases) => cases.forall(value => documentationOnly(value.body))
          case Block(statements, result) => (statements :+ result).forall(documentationOnly)
          case Apply(Select(_, TermName("doc")), List(_)) => true
          case Literal(Constant(())) => true
          case _ => false
        }
        override def transform(tree: Tree): Tree = tree match {
          case packageTree: PackageDef => scoped(Set.empty) {
            if (qualifiedPath(packageTree.pid) == Vector("spinal", "core")) importedArea = true
            else shadowedArea ||= localArea(packageTree.stats)
            super.transform(packageTree)
          }
          case imported: Import =>
            val fromCore = Set(Vector("spinal", "core"), Vector("_root_", "spinal", "core"))(qualifiedPath(imported.expr))
            imported.selectors.foreach { selector =>
              if (fromCore && selector.name == termNames.WILDCARD) importedArea = true
              else if (Option(selector.rename).getOrElse(selector.name).toString == "Area")
                importedArea = fromCore && selector.name.toString == "Area"
            }
            super.transform(imported)
          case template: Template => scoped(Set.empty) {
            shadowedArea ||= localArea(template.body)
            super.transform(template)
          }
          case block: Block => scoped(Set.empty) {
            shadowedArea ||= localArea(block.stats)
            super.transform(block)
          }
          case method: DefDef => scoped(method.vparamss.flatten.map(_.name).toSet) { super.transform(method) }
          case function: Function => scoped(function.vparams.map(_.name).toSet) { super.transform(function) }
          case value @ ValDef(mods, name, tpt,
              Apply(Select(Apply(Select(Literal(Constant(0)), TermName("until")), List(end)), TermName("map")),
                List(Function(List(parameter), body)))) if areaBody(body, importedArea && !shadowedArea) =>
            var conditions = 0
            val unsupported = scala.collection.mutable.ArrayBuffer.empty[Position]
            object Body extends Transformer {
              private var activeIndex = true
              private def scoped[A](shadow: Boolean)(body: => A): A = {
                val previous = activeIndex
                if (shadow) activeIndex = false
                try body finally activeIndex = previous
              }
              private def bindsIndex(trees: List[Tree]): Boolean = trees.exists {
                case definition: ValDef => definition.name == parameter.name
                case definition: DefDef => definition.name == parameter.name
                case _ => false
              }
              private def indexCondition(tree: Tree): Option[Tree] = tree match {
                case Apply(Select(Ident(index), TermName("$eq$eq")), List(literal @ Literal(Constant(_: Int))))
                    if activeIndex && index == parameter.name => Some(literal)
                case _ => None
              }
              private def nativeWhen(tree: Tree): Boolean = tree match {
                case Ident(TermName("when")) => true
                case Apply(fun, _) => nativeWhen(fun)
                case Select(owner, TermName("otherwise")) => nativeWhen(owner)
                case Select(owner, TermName("elsewhen")) => nativeWhen(owner)
                case _ => false
              }
              private def firstCondition(tree: Tree): Option[If] = {
                var found = Option.empty[If]
                object Find extends Traverser {
                  override def traverse(node: Tree): Unit = if (found.isEmpty) node match {
                    case f: Function if f.vparams.exists(_.name == parameter.name) =>
                    case d: DefDef if d.vparamss.flatten.exists(_.name == parameter.name) =>
                    case b: Block if bindsIndex(b.stats) =>
                    case t: Template if bindsIndex(t.body) =>
                    case branch: If if indexCondition(branch.cond).nonEmpty => found = Some(branch)
                    case _ => super.traverse(node)
                  }
                }
                Find.traverse(tree)
                found
              }
              private def alternative(tree: Tree, branch: If, body: Tree): Tree = {
                object Replace extends Transformer {
                  override def transform(node: Tree): Tree =
                    if (node eq branch) body.duplicate else super.transform(node)
                }
                Replace.transform(tree)
              }
              private def choice(literal: Tree, yes: Tree, no: Tree, position: Position): Tree = {
                conditions += 1
                val role = Literal(Constant(name.toString + "_if_" + conditions))
                if (no.equalsStructure(Literal(Constant(()))))
                  Apply(Apply(Select(Ident(parameter.name), TermName("onlyEqual")), List(literal, role)),
                    List(transform(yes))).setPos(position)
                else Apply(Apply(Apply(Select(Ident(parameter.name), TermName("choose")), List(literal, role)),
                  List(transform(yes))), List(transform(no))).setPos(position)
              }
              private def assignmentTarget(tree: Tree): Option[Tree] = tree match {
                case Apply(Select(target, TermName("$colon$eq")), List(_)) => Some(target)
                case _ => None
              }
              private def writes(tree: Tree, target: Tree): Boolean = {
                var found = false
                object Find extends Traverser {
                  override def traverse(node: Tree): Unit = if (!found) {
                    if (assignmentTarget(node).exists(_.equalsStructure(target))) found = true
                    else super.traverse(node)
                  }
                }
                Find.traverse(tree)
                found
              }
              private def statements(trees: List[Tree]): List[Tree] = trees match {
                case default :: call :: tail if assignmentTarget(default).nonEmpty && nativeWhen(call) &&
                    firstCondition(call).nonEmpty && writes(call, assignmentTarget(default).get) =>
                  val branch = firstCondition(call).get
                  val joined = Block(List(default, call), Literal(Constant(())))
                  choice(indexCondition(branch.cond).get, alternative(joined, branch, branch.thenp),
                    alternative(joined, branch, branch.elsep), call.pos) :: statements(tail)
                case head :: tail => transform(head) :: statements(tail)
                case Nil => Nil
              }
              override def transform(node: Tree): Tree = node match {
                case function: Function => scoped(function.vparams.exists(_.name == parameter.name)) {
                  super.transform(function)
                }
                case method: DefDef => scoped(method.vparamss.flatten.exists(_.name == parameter.name)) {
                  super.transform(method)
                }
                case template: Template => scoped(bindsIndex(template.body)) {
                  treeCopy.Template(template, template.parents.map(transform), template.self, statements(template.body))
                }
                case block: Block => scoped(bindsIndex(block.stats)) {
                  treeCopy.Block(block, statements(block.stats), transform(block.expr))
                }
                case call: Apply if nativeWhen(call) && firstCondition(call).nonEmpty =>
                  val branch = firstCondition(call).get
                  choice(indexCondition(branch.cond).get,
                    Block(List(alternative(call, branch, branch.thenp)), Literal(Constant(()))),
                    Block(List(alternative(call, branch, branch.elsep)), Literal(Constant(()))), call.pos)
                case branch @ If(Apply(Select(Ident(index), TermName("$eq$eq")), List(literal @ Literal(Constant(_: Int)))), yes, no)
                    if activeIndex && index == parameter.name =>
                  choice(literal, yes, no, branch.pos)
                case use @ Apply(Select(source, TermName("$eq$eq$eq")), List(Ident(index))) if activeIndex && index == parameter.name =>
                  Apply(Select(Ident(parameter.name), TermName("equal")), List(transform(source))).setPos(use.pos)
                case use @ Apply(source, List(Apply(Select(Ident(index), TermName("$times")), List(stride)), width))
                    if activeIndex && index == parameter.name =>
                  Apply(Select(Ident(parameter.name), TermName("packed")), List(transform(source), transform(stride), transform(width))).setPos(use.pos)
                case use @ Apply(source, List(Ident(index))) if activeIndex && index == parameter.name =>
                  Apply(Select(Ident(parameter.name), TermName("at")), List(transform(source))).setPos(use.pos)
                case use @ Apply(Ident(TermName("B")), List(Ident(index), width)) if activeIndex && index == parameter.name =>
                  Apply(Select(Ident(parameter.name), TermName("bits")), List(transform(width))).setPos(use.pos)
                case use @ Apply(Ident(TermName("U")), List(Ident(index), width)) if activeIndex && index == parameter.name =>
                  Apply(Select(Ident(parameter.name), TermName("uint")), List(transform(width))).setPos(use.pos)
                case use @ Ident(index) if activeIndex && index == parameter.name =>
                  unsupported += use.pos
                  use
                case _ => super.transform(node)
              }
            }
            val rewrittenBody = Body.transform(body.duplicate)
            if (conditions == 0) { collections -= name; super.transform(value) }
            else {
              unsupported.foreach(position => reporter.error(position,
                "[SPINAL-ELAB-AREA-INDEX-USE-UNSUPPORTED] retained Area index must use a supported structural condition, selection, comparison or sized source tag"))
              collections += name
              val count = Apply(Select(core("ElabInt"), TermName("literal")), List(transform(end)))
              val call = Apply(Apply(Select(core("ElabAreaCollection"), TermName("tabulate")),
                List(count, Literal(Constant(name.toString)))),
                List(Function(List(parameter), rewrittenBody)))
              treeCopy.ValDef(value, mods, name, tpt, call).setPos(value.pos)
            }
          case value: ValDef => collections -= value.name; super.transform(value)
          case use @ Apply(Select(Select(Ident(name: TermName), TermName("zipWithIndex")), TermName("foreach")), List(body))
              if collections(name) =>
            if (!documentationOnly(body)) reporter.error(use.pos,
              "[SPINAL-ELAB-AREA-COLLECTION-ITERATION-UNSUPPORTED] retained Area iteration supports documentation callbacks only; export hardware signals with static member access")
            Apply(Select(Select(Ident(name), TermName("documentation")), TermName("foreach")),
              List(transform(body))).setPos(use.pos)
          case use @ Select(Apply(Ident(name: TermName), List(position)), field) if collections(name) =>
            val item = TermName(unit.fresh.newName("areaMember"))
            Apply(Apply(Select(Ident(name), TermName("member")), List(transform(position))),
              List(Function(List(ValDef(Modifiers(Flag.PARAM), item, TypeTree(), EmptyTree)),
                Select(Ident(item), field)))).setPos(use.pos)
          case _ => super.transform(tree)
        }
      }
      unit.body = Rewrite.transform(unit.body)
    }
  }
}
