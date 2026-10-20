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
            case Apply(method, List(first, second, _)) if method.symbol != null && method.symbol != NoSymbol &&
                Set("spinal.core.ElabFiniteRange.foreachConstantFrom", "spinal.core.ElabFiniteRange.foreachVecLength")(method.symbol.fullName) =>
              if (!pure(first) || !pure(second)) reporter.error(tree.pos,
                "MORPHDL-CONSTANT-LOOP-BOUND-UNSUPPORTED: preserved range geometry requires immutable owners and bounds")
            case _ =>
          }
          super.traverse(tree)
        }
      }
      Check.traverse(unit.body)
      // Resolve native Vec identity after typer, rather than guessing from an
      // indexed source spelling (Bits and Vec have the same surface syntax).
      // Only ordinary compiler bridges opt into conservative runtime-scope
      // expansion; explicitly typed loops keep their original capture contract.
      def usesVec(tree: Tree): Boolean = tree.exists(t => t.tpe != null &&
        t.tpe != NoType && t.tpe.baseClasses.exists(_.fullName == "spinal.core.Vec"))
      object GuardedVecLoops extends Transformer {
        override def transform(tree: Tree): Tree = tree match {
          case outer @ Apply(inner @ Apply(method @ Select(owner, _), args), List(callback))
              if method.symbol != null && method.symbol != NoSymbol &&
                Set("spinal.core.ElabFiniteRange.foreachConstant",
                  "spinal.core.ElabFiniteRange.foreachConstantFrom")(method.symbol.fullName) && usesVec(callback) =>
            val name = if (method.symbol.name.toString == "foreachConstant")
              "foreachGuardedVecConstant" else "foreachGuardedVecConstantFrom"
            val symbol = owner.tpe.member(TermName(name))
            if (symbol == NoSymbol) {
              reporter.error(tree.pos, "MORPHDL-CONSTANT-LOOP-BRIDGE-MISSING: runtime lacks guarded Vec bridge")
              super.transform(tree)
            } else {
              val selected = gen.mkAttributedSelect(transform(owner), symbol).setPos(method.pos)
              treeCopy.Apply(outer, treeCopy.Apply(inner, selected, args.map(transform)), List(transform(callback)))
            }
          case _ => super.transform(tree)
        }
      }
      unit.body = GuardedVecLoops.transform(unit.body)
    }
  }
}
