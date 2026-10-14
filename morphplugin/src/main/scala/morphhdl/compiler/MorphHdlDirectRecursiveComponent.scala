package morphhdl.compiler

import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent

/** Explicitly annotated direct recursion. Native runtime still authenticates
  * declarations, every edge, guards, geometry and termination. */
final class MorphHdlDirectRecursiveComponent(val global: Global) extends PluginComponent {
  import global._
  override val phaseName = "morphhdl-direct-recursion"
  override val runsAfter = List("parser")
  override val runsBefore = List("morphhdl-typed-elaboration-control", "namer")

  private def path(tree: Tree): List[String] = tree match {
    case Ident(n) => List(n.decodedName.toString)
    case Select(q, n) => path(q) :+ n.decodedName.toString
    case _ => Nil
  }
  private def core(name: String): Tree =
    Select(Select(Select(Ident(termNames.ROOTPKG), TermName("spinal")), TermName("core")), TermName(name))
  private def coreType(name: String): Tree = {
    val root = Select(Select(Ident(termNames.ROOTPKG), TermName("spinal")), TermName("core"))
    Select(root, TypeName(name))
  }
  private def annotated(mods: Modifiers): Boolean = mods.annotations.exists {
    case Apply(Select(New(tpt), _), Nil) =>
      path(tpt).filterNot(n => n == "_root_" || n == "<root>") == List("spinal", "core", "rtlRecursive") &&
        path(tpt).headOption.exists(n => n == "_root_" || n == "<root>")
    case _ => false
  }
  private def reject(tree: Tree, detail: String): Unit =
    reporter.error(tree.pos, "MORPHDL-DIRECT-RECURSION-UNSUPPORTED: " + detail)

  private def declaration(value: ValDef): Option[(TermName, TermName)] = value.rhs match {
    case Apply(Select(factory, method), List(_, Ident(actual: TermName), _, _))
        if method.decodedName.toString == "int" && path(factory).lastOption.contains("RtlParam") =>
      Some(value.name -> actual)
    case Apply(Select(factory, method), List(_, Ident(actual: TermName), _, _, _))
        if method.decodedName.toString == "optionalInt" && path(factory).lastOption.contains("RtlParam") =>
      Some(value.name -> actual)
    case _ => None
  }

  private def rewrite(original: ClassDef): Tree = {
    val body = original.impl.body
    val constructors = body.collect { case d: DefDef if d.name == termNames.CONSTRUCTOR => d }
    if (constructors.size != 1 || constructors.head.vparamss.size != 1) {
      reject(original, "one constructor parameter list is required")
      return original
    }
    if (!original.mods.hasFlag(Flag.FINAL)) {
      reject(original, "the first direct-recursion release requires a final component class")
      return original
    }
    val parameters = constructors.head.vparamss.head
    val names = parameters.map(_.name)
    val ports = body.collect { case v: ValDef if v.name.decodedName.toString == "io" => v }
    val formals = body.collect { case v: ValDef if declaration(v).nonEmpty => v -> declaration(v).get }
    if (ports.size != 1 || formals.isEmpty || formals.exists(f => !names.contains(f._2._2))) {
      reject(original, "declare one io value and integer RtlParam formals bound directly to constructor arguments")
      return original
    }
    if (formals.map(_._2._2).distinct.size != formals.size ||
        formals.exists(f => body.indexOf(f._1) > body.indexOf(ports.head))) {
      reject(original, "each recursive formal must precede io and own a distinct constructor argument")
      return original
    }
    val io = ports.head
    if (io.mods.hasFlag(Flag.MUTABLE)) {
      reject(io, "recursive ports must be immutable")
      return original
    }
    val boundaryName = TermName(unitFreshName("recursiveBoundary"))
    val valuesName = TermName(unitFreshName("recursiveValues"))
    val bindings = formals.map(_._2)
    val overridesByArgument = bindings.map { case (formal, actual) => actual -> formal }.toMap
    var selfEdges = 0
    var shadowed = false
    object ShadowScan extends Traverser {
      override def traverse(tree: Tree): Unit = tree match {
        case c: ClassDef if c.name == original.name => shadowed = true
        case _ => super.traverse(tree)
      }
    }
    body.foreach(ShadowScan.traverse)
    if (shadowed) {
      reject(original, "a local class must not shadow the recursive component")
      return original
    }
    def selfType(tpt: Tree): Boolean = tpt match {
      case Ident(n: TypeName) => n == original.name
      case AppliedTypeTree(Ident(n: TypeName), arguments) =>
        n == original.name && arguments.map(_.toString) == original.tparams.map(_.name.toString)
      case _ => false
    }
    // Parse named constructor bindings before typer, when no default getters or
    // argument reordering have obscured their authored identities. Both supported
    // Scala parsers expose a two-child named-argument node with different names.
    def resolvedArguments(call: Tree, args: List[Tree]): Option[List[(TermName, Tree)]] = {
      val ordered = scala.collection.mutable.LinkedHashMap.empty[TermName, Tree]
      var positional = 0
      var sawNamed = false
      var invalid = false
      def fail(at: Tree, reason: String): Unit = { reject(at, reason); invalid = true }
      args.foreach { arg =>
        val named = Set("AssignOrNamedArg", "NamedArg")(arg.productPrefix)
        if (named) {
          sawNamed = true
          arg.children match {
            case List(Ident(name: TermName), value) if names.contains(name) =>
              if (ordered.contains(name)) fail(arg, "duplicate recursive constructor argument: " + name)
              else ordered(name) = value
            case List(Ident(name), _) => fail(arg, "unknown recursive constructor argument: " + name)
            case _ => fail(arg, "recursive named argument must name a constructor parameter")
          }
        } else if (sawNamed) fail(arg, "positional recursive argument follows a named argument")
        else if (positional >= names.size) fail(arg, "too many recursive constructor arguments")
        else { ordered(names(positional)) = arg; positional += 1 }
      }
      val missing = names.filterNot(ordered.contains)
      if (missing.nonEmpty) fail(call, "recursive calls require explicit bindings for: " + missing.mkString(", "))
      if (invalid) None else Some(ordered.toList)
    }
    object Calls extends Transformer {
      private var shadows = Set.empty[String]
      private val protectedNames = names.map(_.decodedName.toString).toSet ++
        original.tparams.map(_.name.decodedName.toString)
      private def scoped(bound: Set[String])(body: => Tree): Tree = {
        val previous = shadows
        shadows ++= bound.intersect(protectedNames)
        try body finally shadows = previous
      }
      override def transform(tree: Tree): Tree = tree match {
        case value: ValDef if (value.rhs match {
          case Apply(Select(New(tpt), name), _) => name == termNames.CONSTRUCTOR && selfType(tpt)
          case _ => false
        }) =>
          val rewritten = transform(value.rhs)
          val named = Apply(Select(rewritten, TermName("authoredName")),
            List(Literal(Constant(value.name.decodedName.toString))))
          treeCopy.ValDef(value, value.mods, value.name, value.tpt, named)
        case d: DefDef if d.name != termNames.CONSTRUCTOR =>
          scoped(d.vparamss.flatten.map(_.name.decodedName.toString).toSet ++ d.tparams.map(_.name.decodedName.toString)) {
            super.transform(d)
          }
        case f: Function =>
          scoped(f.vparams.map(_.name.decodedName.toString).toSet) { super.transform(f) }
        case b: Block =>
          val bound = b.stats.collect {
            case v: ValDef => v.name.decodedName.toString
            case d: DefDef => d.name.decodedName.toString
            case t: TypeDef => t.name.decodedName.toString
          }.toSet
          scoped(bound) { super.transform(b) }
        case call @ Apply(Select(New(tpt), name), args) if name == termNames.CONSTRUCTOR && selfType(tpt) =>
          selfEdges += 1
          if (shadows.nonEmpty) {
            reject(call, "recursive constructor bindings are shadowed in this scope: " + shadows.toVector.sorted.mkString(", "))
            call
          } else resolvedArguments(call, args) match {
            case None => call
            case Some(ordered) =>
              val pairs = ordered.flatMap { case (parameter, actual) =>
                overridesByArgument.get(parameter) match {
                  case Some(formal) =>
                    Some(Apply(Select(Select(Ident(termNames.ROOTPKG), TermName("scala")), TermName("Tuple2")),
                      List(Ident(formal), transform(actual))))
                  case None =>
                    actual match {
                      case Ident(n: TermName) if n == parameter =>
                      case _ => reject(actual, "recursive specialization arguments must forward their exact constructor binding")
                    }
                    None
                }
              }
              Apply(Ident(boundaryName), pairs).setPos(call.pos)
          }
        case _ => super.transform(tree)
      }
    }
    val rewrittenBody = body.map(Calls.transform)
    if (selfEdges == 0) {
      reject(original, "annotated component has no supported direct self-construction")
      return original
    }
    val formalVector = Apply(Select(Select(Ident(termNames.ROOTPKG), TermName("scala")), TermName("Vector")),
      bindings.map { case (formal, actual) =>
        Apply(Select(Select(Ident(termNames.ROOTPKG), TermName("scala")), TermName("Tuple2")),
          List(Ident(formal), Ident(actual)))
      })
    val locals = bindings.zipWithIndex.map { case ((formal, _), index) =>
      ValDef(Modifiers(), formal, coreType("ElabInt"),
        Apply(Ident(valuesName), List(Literal(Constant(index)))))
    }
    val valuesType = AppliedTypeTree(Select(Select(Ident(termNames.ROOTPKG), TermName("scala")), TypeName("Vector")),
      List(coreType("ElabInt")))
    val factory = Function(List(ValDef(Modifiers(Flag.PARAM), valuesName, valuesType, EmptyTree)),
      Block(locals, io.rhs.duplicate))
    val boundary = ValDef(Modifiers(Flag.PRIVATE), boundaryName, TypeTree(),
      Apply(Apply(Select(core("ElabRecursiveComponent"), TermName("inferred")), List(formalVector)), List(factory)))
    val replacementIo = treeCopy.ValDef(io, io.mods, io.name, io.tpt, Select(Ident(boundaryName), TermName("ports")))
    val guard = Apply(Select(core("ElabRecursiveComponent"), TermName("guardConstructor")), List(This(typeNames.EMPTY)))
    val combined = rewrittenBody.flatMap {
      case value: ValDef if value.name == io.name => List(boundary, replacementIo)
      case constructor: DefDef if constructor.name == termNames.CONSTRUCTOR =>
        List(constructor, guard)
      case other => List(other)
    }
    treeCopy.ClassDef(original, original.mods, original.name, original.tparams,
      treeCopy.Template(original.impl, original.impl.parents, original.impl.self, combined))
  }
  private var serial = 0
  private def unitFreshName(stem: String): String = {
    serial += 1
    "$" + stem + "$" + serial
  }
  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit = {
      serial = 0
      object Rewrite extends Transformer {
        override def transform(tree: Tree): Tree = tree match {
          case component: ClassDef if annotated(component.mods) => rewrite(component)
          case _ => super.transform(tree)
        }
      }
      unit.body = Rewrite.transform(unit.body)
    }
  }
}
