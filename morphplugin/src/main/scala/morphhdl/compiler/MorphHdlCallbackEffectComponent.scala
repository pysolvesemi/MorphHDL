package morphhdl.compiler

import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent
import scala.collection.mutable

/** Check source-visible effects at explicitly declared hardware callback boundaries.
  * Uses typed parameter and definition symbols, never callback/receiver spellings.
  * This is not an assertion that an opaque external implementation is pure.
  */
final class MorphHdlCallbackEffectComponent(val global: Global) extends PluginComponent {
  import global._
  override val phaseName = "morphhdl-callback-effects"
  override val runsAfter = List("typer")
  override val runsBefore = List("patmat")

  private def checked(symbol: Symbol): Boolean = symbol != null && symbol != NoSymbol &&
    symbol.annotations.exists(_.atp.typeSymbol.fullName == "spinal.core.rtlHardwareCallback")

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    private var indexedRun: global.Run = null
    private val definitions = mutable.Map.empty[Symbol, Tree]
    private def indexCurrentRun(): Unit = {
      if (indexedRun eq global.currentRun) return
      definitions.clear()
      object Index extends Traverser {
        override def traverse(tree: Tree): Unit = {
          tree match {
            case definition: DefDef => definitions(definition.symbol) = definition.rhs
            case definition: ValDef if definition.symbol != null && definition.symbol != NoSymbol &&
                definition.symbol.info.finalResultType.baseClasses.exists(s =>
                  s.fullName.matches("scala.Function[0-9]+")) =>
              // Values are evaluated before capture. Only their returned closure
              // executes on invocation; unrelated initializer effects do not.
              def closure(tree: Tree): Tree = tree match {
                case Block(_, result) => closure(result)
                case Typed(value, _) => closure(value)
                case other => other
              }
              definitions(definition.symbol) = closure(definition.rhs)
            case _ =>
          }
          super.traverse(tree)
        }
      }
      // typer has completed every source unit before this phase starts. Build
      // the identity table once per Run, not once per callback or source file.
      global.currentRun.units.foreach(unit => Index.traverse(unit.body))
      indexedRun = global.currentRun
    }
    override def apply(unit: CompilationUnit): Unit = {
      indexCurrentRun()
      val reported = mutable.Set.empty[(String, Int, String)]
      def inspect(body: Tree, call: Tree): Unit = {
        val visiting = mutable.Set.empty[Symbol]
        def reject(tree: Tree, reason: String): Unit = {
          val position = if (tree.pos.isDefined) tree.pos else call.pos
          val key = (if (position.isDefined) position.source.file.path else "",
            if (position.isDefined) position.point else -1, reason)
          if (reported.add(key)) reporter.error(position,
            "MORPHDL-HARDWARE-CALLBACK-HOST-EFFECT: " + reason +
              " in a hardware callback; use immutable elaboration values and hardware assignments")
        }
        def follow(symbol: Symbol): Unit = {
          if (symbol != null && symbol != NoSymbol && !visiting(symbol))
            definitions.get(symbol).foreach { rhs =>
              visiting += symbol
              Effects.traverse(rhs)
              visiting -= symbol
            }
        }
        object Effects extends Traverser {
          override def traverse(tree: Tree): Unit = tree match {
            case _: DefDef | _: ClassDef | _: ModuleDef => // only follow invoked definitions
            case assignment: Assign => reject(assignment, "host assignment")
            case value: ValDef if value.mods.hasFlag(Flag.MUTABLE) => reject(value, "mutable host declaration")
            case statement: Return => reject(statement, "early return")
            case statement: Throw => reject(statement, "throw")
            case application: Apply =>
              // A typed setter is a host write even when the original source
              // used compound assignment through a qualified receiver.
              if (application.fun.symbol != null && application.fun.symbol != NoSymbol &&
                  application.fun.symbol.isSetter) reject(application, "host field assignment")
              else follow(application.fun.symbol)
              super.traverse(tree)
            case reference: Ident => follow(reference.symbol)
            case reference: Select => follow(reference.symbol); super.traverse(tree)
            case _ => super.traverse(tree)
          }
        }
        Effects.traverse(body)
      }
      object Calls extends Traverser {
        override def traverse(tree: Tree): Unit = {
          tree match {
            case application: Apply => application.fun.tpe match {
              case MethodType(parameters, _) =>
                parameters.zip(application.args).foreach { case (parameter, argument) =>
                  if (checked(parameter)) inspect(argument, application)
                }
              case _ =>
            }
            case _ =>
          }
          super.traverse(tree)
        }
      }
      Calls.traverse(unit.body)
    }
  }
}
