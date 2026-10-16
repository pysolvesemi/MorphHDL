package morphhdl.compiler

import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent

/** Authenticate ordinary-range bounds after symbols exist, without evaluating getters. */
final class MorphHdlConstantLoopBoundsComponent(val global: Global) extends PluginComponent {
  import global._
  val phaseName = "morphhdl-constant-loop-bounds"
  val runsAfter = List("typer")
  override val runsBefore = List("patmat")
  def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    def apply(unit: CompilationUnit): Unit = {
      def stable(symbol: Symbol): Boolean = symbol != null && symbol != NoSymbol &&
        !symbol.isMutable && (symbol.isStable || symbol.isValueParameter)
      def pure(tree: Tree): Boolean = tree match {
        case Literal(Constant(_: Int)) => true
        case This(_) => true
        case Ident(_) => stable(tree.symbol)
        case Select(owner, _) => stable(tree.symbol) && pure(owner)
        case Typed(value, _) => pure(value)
        case Apply(Select(left, op), List(right)) if tree.symbol != null && tree.symbol != NoSymbol &&
            tree.symbol.owner.fullName == "scala.Int" && Set("+", "-", "*", "/", "%")(op.decodedName.toString) =>
          pure(left) && pure(right)
        case _ => false
      }
      object Check extends Traverser {
        override def traverse(tree: Tree): Unit = {
          tree match {
            case Apply(method, List(bound, _)) if method.symbol != null && method.symbol != NoSymbol &&
                method.symbol.fullName == "spinal.core.ElabFiniteRange.foreachConstant" =>
              if (!pure(bound)) reporter.error(bound.pos,
                "MORPHDL-CONSTANT-LOOP-BOUND-UNSUPPORTED: preserved ordinary loop bounds must be literals or immutable fields; mutable fields and effectful getters are unsupported")
            case _ =>
          }
          super.traverse(tree)
        }
      }
      Check.traverse(unit.body)
    }
  }
}
