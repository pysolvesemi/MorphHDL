package spinal.idslplugin.components

import scala.tools.nsc.Global
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.transform.Transform

/** Capture declaration comments before typing; the runtime configuration decides
  * whether embedded automatic notes are published. */
class SourceDocumentationTransformer(val global: Global) extends PluginComponent with Transform {
  import global._
  override val phaseName = "idsl-source-documentation"
  override val runsAfter = List("parser")
  override val runsBefore = List("namer")
  override protected def newTransformer(unit: CompilationUnit): Transformer = new Transformer {
    val source = new String(unit.source.content)
    val comments = SourceDocumentation.scan(source)
    val definitions = scala.collection.mutable.ArrayBuffer.empty[ValDef]
    new Traverser {
      override def traverse(tree: Tree): Unit = {
        tree match { case v: ValDef if v.rhs.nonEmpty && v.pos.isRange => definitions += v; case _ => }
        super.traverse(tree)
      }
    }.traverse(unit.body)
    val ambiguous = definitions.groupBy(v => unit.source.offsetToLine(v.pos.end)).filter(_._2.size > 1).keySet
    def annotation(comment: SourceDocumentation.Comment): Tree = {
      val root = Ident(termNames.ROOTPKG)
      val tpe = Select(Select(Select(root, TermName("spinal")), TermName("idslplugin")), TypeName("RtlSourceDoc"))
      val line = unit.source.offsetToLine(comment.start)
      val column = comment.start - unit.source.lineToOffset(line)
      val location = s"${unit.source.file.path}:${line + 1}:${column + 1}"
      Apply(Select(New(tpe), termNames.CONSTRUCTOR), List(Literal(Constant(comment.text)),
        Literal(Constant(if (comment.scaladoc) "scaladoc" else "scala")), Literal(Constant(location))))
    }
    def notes(tree: Tree, mods: Modifiers, header: Boolean): List[Tree] = {
      if (global.useOffsetPositions && comments.nonEmpty) {
        val marker = SourceDocumentation.Comment(math.max(0, tree.pos.point), math.max(0, tree.pos.point),
          "Automatic RTL comments require Scala compilation with -Yrangepos", false, false)
        val built = annotation(marker)
        return List(built match {
          case Apply(fun, args) => Apply(fun, List(args.head, Literal(Constant("unavailable")), args(2)))
          case _ => built
        })
      }
      if (!tree.pos.isRange) return Nil
      val annotationStarts = scala.collection.mutable.ArrayBuffer.empty[Int]
      mods.annotations.foreach { annotation =>
        new Traverser {
          override def traverse(value: Tree): Unit = {
            if (value.pos.isDefined) annotationStarts += value.pos.start
            super.traverse(value)
          }
        }.traverse(annotation)
      }
      val first = (tree.pos.start +: annotationStarts).min
      val start = if (first > 0 && source(first - 1) == '@') first - 1 else first
      val leading = SourceDocumentation.preceding(source, comments, start)
      val trailing = if (header) {
        val lineEnd = source.indexOf('\n', tree.pos.point) match { case -1 => source.length; case value => value }
        val brace = source.indexOf('{', tree.pos.point)
        if (brace >= 0 && brace < lineEnd) SourceDocumentation.trailing(source, comments, brace + 1) else Vector.empty
      } else if (ambiguous(unit.source.offsetToLine(tree.pos.end))) {
        if (SourceDocumentation.trailing(source, comments, tree.pos.end).nonEmpty)
          reporter.warning(tree.pos, "RTL comment capture: ambiguous same-line declarations; use @doc or .doc")
        Vector.empty
      } else SourceDocumentation.trailing(source, comments, tree.pos.end)
      (leading ++ trailing).distinct.filter(_.text.length > 0).map(annotation _).toList
    }
    override def transform(tree: Tree): Tree = {
      val transformed = super.transform(tree)
      transformed match {
        case value: ValDef if value.rhs.nonEmpty && !value.mods.hasFlag(Flag.PARAMACCESSOR) && !value.mods.hasFlag(Flag.SYNTHETIC) =>
          treeCopy.ValDef(value, value.mods.withAnnotations(notes(value, value.mods, header = false)), value.name, value.tpt, value.rhs)
        case value: ClassDef =>
          treeCopy.ClassDef(value, value.mods.withAnnotations(notes(value, value.mods, header = true)), value.name, value.tparams, value.impl)
        case _ => transformed
      }
    }
  }
}
