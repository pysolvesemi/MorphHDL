package morphhdl.compiler

import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.transform.TypingTransformers

/**
  * Typed, identity-exact instrumentation for native library memory adapters.
  *
  * Only calls whose selected method is owned by the real
  * `spinal.lib.MemPimped` class and whose receiver is the real package-object
  * `memPimped` conversion are eligible. The exact stable `Mem` argument and
  * exact returned `Data` graph are passed to the core-only runtime registry.
  */
final class MorphHdlMemoryPortAdapterComponent(val global: Global)
    extends PluginComponent
    with TypingTransformers {
  import global._

  override val phaseName: String = "morphhdl-memory-port-adapters"
  // Scala 2.12 runs patmat before superaccessors, while Scala 2.13 runs it
  // after. Typer is the common point at which the selected MemPimped method,
  // receiver conversion and stable Mem symbol are all available.
  override val runsAfter: List[String] = List("typer")
  override val runsBefore: List[String] = List("patmat")

  private final class AdapterTransformer(unit: CompilationUnit)
      extends TypingTransformer(unit) {
    private lazy val dataClass = rootMirror.staticClass("spinal.core.Data")
    private lazy val memClass = rootMirror.staticClass("spinal.core.Mem")
    // The compiler-plugin unit tests intentionally compile fixtures without
    // the SpinalHDL library module on their compiler classpath.  Treat that
    // optional owner as absent; when the library is present, the exact typed
    // owner identity below remains mandatory.
    private lazy val memPimpedClass =
      try rootMirror.staticClass("spinal.lib.MemPimped")
      catch {
        case _: scala.ScalaReflectionException => NoSymbol
      }
    private lazy val libPackage = rootMirror.staticModule("spinal.lib.package")
    private lazy val memPimpedConversions: Set[Symbol] = {
      val member = libPackage.info.member(TermName("memPimped"))
      if (member == NoSymbol) Set.empty
      else if (member.isOverloaded) member.alternatives.toSet
      else Set(member)
    }
    private lazy val registryModule = rootMirror.staticModule(
      "spinal.core.ExternalParameterizedMemoryPortAdapterRegistry"
    )

    private def terminalSymbol(tree: Tree): Symbol = tree match {
      case TypeApply(fun, _) => terminalSymbol(fun)
      case value             => value.symbol
    }

    private def selectedCall(tree: Tree): Option[(Tree, Symbol)] = tree match {
      case Apply(fun, _) =>
        fun match {
          case TypeApply(Select(receiver, _), _) =>
            Some(receiver -> terminalSymbol(fun))
          case Select(receiver, _) => Some(receiver -> terminalSymbol(fun))
          case _                   => None
        }
      case selection @ Select(receiver, _) =>
        Some(receiver -> selection.symbol)
      case _ => None
    }

    private def exactAdapterMethod(symbol: Symbol): Boolean =
      symbol != null && symbol != NoSymbol &&
        memPimpedClass != NoSymbol &&
        (symbol.owner eq memPimpedClass) && {
          val name = symbol.name.decodedName.toString
          name == "writePort" || name == "readSyncPort"
        }

    private def exactMemory(receiver: Tree): Option[Tree] = receiver match {
      case Apply(fun, List(memory))
          if memPimpedConversions.contains(terminalSymbol(fun)) &&
            memory.tpe != null &&
            memory.tpe.baseClasses.contains(memClass) &&
            memory.symbol != null && memory.symbol != NoSymbol &&
            memory.symbol.isStable =>
        Some(memory)
      case _ => None
    }

    private def instrument(tree: Tree): Option[Tree] =
      selectedCall(tree).flatMap { case (receiver, method) =>
        if (
          !exactAdapterMethod(method) || tree.tpe == null ||
          !tree.tpe.baseClasses.contains(dataClass)
        ) None
        else {
          exactMemory(receiver).map { memory =>
            val rewrittenCall = super.transform(tree)
            val rewrittenMemory = super.transform(memory)
            val captureMemory = gen.mkMethodCall(
              registryModule,
              TermName("capture"),
              List(rewrittenCall.tpe),
              List(rewrittenMemory)
            )
            localTyper
              .typed(gen.mkMethodCall(captureMemory, List(rewrittenCall)))
              .setPos(tree.pos)
          }
        }
      }

    override def transform(tree: Tree): Tree =
      instrument(tree).getOrElse(super.transform(tree))
  }

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit =
      unit.body = new AdapterTransformer(unit).transform(unit.body)
  }
}
