package spinal.idslplugin.components

import scala.tools.nsc.Global
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.transform.{Transform, TypingTransformers}

/** Convert declaration annotations to typed identity-preserving runtime calls. */
class DocumentationTransformer(val global: Global) extends PluginComponent with Transform with TypingTransformers {
  import global._
  override val phaseName = "idsl-documentation"
  override val runsAfter = List("typer")
  override val runsBefore = List("patmat")
  override protected def newTransformer(unit: CompilationUnit): Transformer = new TypingTransformer(unit) {
    private def supported(tpe: Type): Boolean = tpe != null && tpe != NoType &&
      tpe.baseClasses.exists(s => Set("spinal.core.Data", "spinal.core.Component").contains(s.fullName))
    private def docs(symbol: Symbol): List[(String, String, String)] = {
      if (symbol == null || symbol == NoSymbol) Nil
      else symbol.annotations.flatMap { annotation =>
        annotation.atp.typeSymbol.fullName match {
          case "spinal.core.doc" => annotation.scalaArgs match {
            case List(Literal(Constant(value: String))) => List((value, "annotation", if (symbol.pos.isDefined) s"${symbol.pos.source.file.path}:${symbol.pos.line}:${symbol.pos.column + 1}" else ""))
            case _ => global.reporter.error(symbol.pos, "@doc requires a literal String"); Nil
          }
          case "spinal.idslplugin.RtlSourceDoc" => annotation.scalaArgs match {
            case List(Literal(Constant(value: String)), Literal(Constant(kind: String)), Literal(Constant(location: String))) =>
              List((value, kind, location))
            case _ => Nil
          }
          case _ => Nil
        }
      }
    }
    private def attach(value: Tree, notes: List[(String, String, String)], definition: Boolean): Tree =
      notes.foldLeft(value) { case (result, (message, origin, location)) =>
        val module = rootMirror.staticModule("spinal.core.RtlDocumentation")
        val select = Select(gen.mkAttributedRef(module), TermName("attach"))
        val call = Apply(TypeApply(select, List(TypeTree(result.tpe))),
          List(result, Literal(Constant(message)), Literal(Constant(origin)), Literal(Constant(location)), Literal(Constant(definition))))
        localTyper.typed(atPos(value.pos)(call))
      }
    private val source = new String(unit.source.content)
    private val sourceComments = SourceDocumentation.scan(source)
    private val consumed = scala.collection.mutable.HashSet.empty[Int]
    private val regionMethods = Set(
      "spinal.core.when.apply", "spinal.core.WhenContext.elsewhen", "spinal.core.WhenContext.otherwise",
      "spinal.core.switch.apply", "spinal.core.is.apply", "spinal.core.default.apply",
      "morphhdl.frontend.HdlRange.foreach",
      "morphhdl.frontend.StructuralGenerateIfOps.generateIf",
      "morphhdl.frontend.GenerateIfBuilder.otherwise",
      "morphhdl.frontend.GenerateCaseBuilder.choice", "morphhdl.frontend.GenerateCaseBuilder.default",
      "morphhdl.frontend.ParamRtlFrontend.generateIf"
    )
    private def take(notes: Seq[SourceDocumentation.Comment]): List[SourceDocumentation.Comment] =
      notes.filter(n => n.text.length > 0 && consumed.add(n.start)).toList
    private def wrapRegion(value: Tree, notes: List[SourceDocumentation.Comment]): Tree =
      notes.foldRight(value) { (note, result) =>
        val module = rootMirror.staticModule("spinal.core.RtlDocumentation")
        val select = Select(gen.mkAttributedRef(module), TermName("automaticRegion"))
        val call = Apply(Apply(TypeApply(select, List(TypeTree(result.tpe))), List(
          Literal(Constant(note.text)), Literal(Constant(note.scaladoc)))), List(result))
        localTyper.typed(atPos(value.pos)(call))
      }
    private def hardwareCall(tree: Tree): Boolean = tree match {
      case value: Apply => value.fun.symbol != null && value.fun.symbol != NoSymbol && regionMethods(value.fun.symbol.fullName)
      case _ => false
    }
    private def bodyNotes(body: Tree, call: Tree, lambda: Boolean = false): List[SourceDocumentation.Comment] = {
      if (!body.pos.isRange || !call.pos.isRange) return Nil
      val brace = source.lastIndexOf('{', body.pos.start)
      if (brace < call.pos.start) Nil
      else {
        val arrow = if (lambda) source.lastIndexOf("=>", body.pos.start) else -1
        val end = if (arrow > brace) arrow + 2 else brace + 1
        val found = take(SourceDocumentation.trailing(source, sourceComments, end))
        found
      }
    }
    override def transform(tree: Tree): Tree = {
      // Reserve outer chain comments before traversing curried calls and bodies.
      val header = if (hardwareCall(tree) && tree.pos.isRange && !tree.tpe.isInstanceOf[MethodType])
        take(SourceDocumentation.preceding(source, sourceComments, tree.pos.start)) else Nil
      val prepared = tree match {
        case value: Apply if hardwareCall(value) =>
          val parameters = value.fun.tpe.params
          val args = value.args.zipWithIndex.map { case (argument, index) =>
            argument match {
              case function: Function =>
                val notes = bodyNotes(function.body, value, lambda = true)
                if (notes.isEmpty) argument else treeCopy.Function(function, function.vparams, wrapRegion(function.body, notes))
              case _ if index < parameters.size && parameters(index).info.typeSymbol == definitions.ByNameParamClass =>
                val notes = bodyNotes(argument, value)
                if (notes.isEmpty) argument else wrapRegion(argument, notes)
              case _ => argument
            }
          }
          treeCopy.Apply(value, value.fun, args)
        case _ => tree
      }
      val transformed = super.transform(prepared)
      val result = transformed match {
        case value: ValDef if value.rhs != EmptyTree =>
          val notes = docs(value.symbol)
          if (notes.isEmpty) value
          else if (supported(value.rhs.tpe))
            treeCopy.ValDef(value, value.mods, value.name, value.tpt, attach(value.rhs, notes, definition = false))
          else {
            if (notes.exists(_._2 == "annotation")) reporter.error(value.pos, "@doc requires a hardware-valued declaration")
            value
          }
        case value: Apply if value.fun.symbol != null && value.fun.symbol != NoSymbol && value.fun.symbol.isConstructor &&
            value.fun.isInstanceOf[Select] && value.fun.asInstanceOf[Select].qualifier.isInstanceOf[New] =>
          val notes = docs(value.fun.symbol.owner)
          if (notes.nonEmpty && value.tpe != null && value.tpe.baseClasses.exists(_.fullName == "spinal.core.Component")) attach(value, notes, definition = true) else value
        case value: ClassDef =>
          if (docs(value.symbol).exists(_._2 == "annotation") &&
              !value.symbol.info.baseClasses.exists(_.fullName == "spinal.core.Component"))
            reporter.error(value.pos, "class @doc requires a Component definition")
          value
        case value: ModuleDef =>
          if (docs(value.symbol).exists(_._2 == "annotation")) reporter.error(value.pos, "@doc on objects is unsupported; annotate a Component class or a hardware val")
          value
        case value: DefDef =>
          if (!value.symbol.isAccessor && docs(value.symbol).exists(_._2 == "annotation")) reporter.error(value.pos, "@doc on methods is unsupported; use rtlDoc for a hardware region")
          value
        case _ => transformed
      }
      if (header.isEmpty) result else wrapRegion(result, header)
    }
  }
}
