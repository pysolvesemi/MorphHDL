package morphhdl.frontend

import scala.language.experimental.macros
import scala.reflect.macros.whitebox

/** Typed bridge introduced by the MorphHDL compiler plugin for natural Scala `if` syntax. */
object NaturalSymbolicConditional {
  /** Ordinary Scala Boolean remains ordinary witness-selected Scala control flow. */
  def select[T](condition: Boolean)(ifTrue: => T)(ifFalse: => T): T =
    if (condition) ifTrue else ifFalse

  /** Explicit HdlBool is proven by overload resolution and captures both hardware alternatives. */
  def select[T](condition: HdlBool)(ifTrue: => T)(ifFalse: => T): T =
    macro NaturalSymbolicConditionalMacro.selectSymbolicImpl[T]

  private[frontend] def runtime[T](
      condition: HdlBool,
      sourceFile: String,
      sourceLine: Int
  )(ifTrue: => T)(ifFalse: => T): T = {
    val origin = SourceOrigin(sourceFile, sourceLine)
    if (spinal.core.ParameterizedStructure.captureEnabled) {
      var trueValue: Option[T] = None
      var falseValue: Option[T] = None
      NativeStructuralFrontend
        .startGenerateIf(condition, None, { trueValue = Some(ifTrue); () }, origin)
        .otherwise({ falseValue = Some(ifFalse); () }, origin)
      if (condition.witness) trueValue.get
      else falseValue.get
    } else if (condition.witness) ifTrue
    else ifFalse
  }
}

object NaturalSymbolicConditionalMacro {
  def selectSymbolicImpl[T: c.WeakTypeTag](
      c: whitebox.Context
  )(
      condition: c.Expr[HdlBool]
  )(
      ifTrue: c.Expr[T]
  )(
      ifFalse: c.Expr[T]
  ): c.Expr[T] = {
    import c.universe._

    object UnsafeAlternativeEffect extends Traverser {
      var finding: Option[(Position, String)] = None
      override def traverse(tree: Tree): Unit = if (finding.isEmpty) tree match {
        case Return(_) => finding = Some(tree.pos -> "return")
        case Throw(_)  => finding = Some(tree.pos -> "throw")
        case _         => super.traverse(tree)
      }
    }
    UnsafeAlternativeEffect.traverse(ifTrue.tree)
    UnsafeAlternativeEffect.traverse(ifFalse.tree)
    UnsafeAlternativeEffect.finding.foreach { case (position, effect) =>
      c.abort(
        position,
        s"MORPHDL-SYMBOLIC-CONDITIONAL-UNSAFE-EFFECT: '$effect' is not supported inside an explicit symbolic alternative"
      )
    }

    val pos = c.enclosingPosition
    val sourceFile = Option(pos.source).map(_.path).filter(_.nonEmpty).getOrElse("<symbolic-if>")
    val sourceLine = math.max(1, pos.line)
    c.Expr[T](
      q"_root_.morphhdl.frontend.NaturalSymbolicConditional.runtime[${weakTypeOf[T]}](${condition.tree}, $sourceFile, $sourceLine)(${ifTrue.tree})(${ifFalse.tree})"
    )
  }
}
