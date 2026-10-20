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

  private def actualName(tree:Tree):Option[TermName]=tree match {
    case Ident(n:TermName)=>Some(n)
    case Select(Ident(n:TermName),name) if name.decodedName.toString=="value"=>Some(n)
    case _=>None
  }
  private def declaration(value: ValDef): Option[(TermName, TermName)] = value.rhs match {
    case Apply(Select(factory, method), args) if path(factory).lastOption.contains("RtlParam") &&
        Set("int","inferredInt","packed","optionalInt","optional","bool")(method.decodedName.toString) && args.size>=2 =>
      value.attachments.get[ConfigurationFormalPath] match {
        case Some(origin) if path(args(1)) == origin.root :: (origin.fields :+ "value") =>
          Some(value.name -> TermName(origin.root))
        case Some(_) => reject(value,"configuration formal lost its authenticated field extraction");None
        case None=>actualName(args(1)).map(value.name -> _)
      }
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
      reject(original, "declare one io value and integer/Boolean RtlParam formals bound directly to constructor arguments")
      return original
    }
    val configurationFormals=formals.flatMap { case (value,(name,_))=>
      value.attachments.get[ConfigurationFormalPath].map(name -> _)
    }.toMap
    val bindingPaths=formals.map {case (_, (formal,actual))=>
      configurationFormals.get(formal).map(p=>p.root :: p.fields).getOrElse(List(actual.decodedName.toString))
    }
    if (bindingPaths.distinct.size != formals.size ||
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
    val constructorWrappers = parameters.flatMap { parameter =>
      val name=path(parameter.tpt).filterNot(n=>n=="_root_" || n=="<root>")
      if(name.take(3)==List("spinal","core","ConstructorParam") && name.size==4 &&
          Set("Integer","BooleanValue","Packed","OptionalInteger")(name.last))
        Some(parameter.name -> (name.last match {
          case "Integer"=>"integer";case "BooleanValue"=>"boolean"
          case "Packed"=>"packed";case "OptionalInteger"=>"optional"
        })) else None
    }.toMap
    formals.foreach { case (value,(_,actual))=>value.rhs match {
      case Apply(_,args) if args(1).isInstanceOf[Select] && !constructorWrappers.contains(actual) && !configurationFormals.contains(value.name)=>
        reject(value,"constructor value extraction requires an authenticated ConstructorParam boundary")
      case _=>
    }}
    val conversions=formals.flatMap {case (_, (formal,actual))=>constructorWrappers.get(actual).map(formal -> _)}.toMap
    val booleanFormals = formals.collect {
      case (value, (formal, _)) if (value.rhs match {
        case Apply(Select(_, method), _) => method.decodedName.toString == "bool"
        case _ => false
      }) => formal
    }.toSet
    val optionalFormals = formals.collect {
      case (value,(formal,_)) if (value.rhs match {
        case Apply(Select(_,method),_)=>method.decodedName.toString=="optional"
        case _=>false
      })=>formal
    }.toSet
    def pair(formal: TermName, raw: Tree, declaration: Boolean): Tree = {
      val actual=configurationFormals.get(formal) match {
        case Some(origin)=>
          val field=origin.fields.foldLeft(raw)((base,name)=>Select(base,TermName(name)))
          Apply(Select(core("ConstructorParam"),TermName(origin.conversion)),List(field))
        case None=>conversions.get(formal).map(method=>Apply(Select(core("ConstructorParam"),TermName(method)),List(raw))).getOrElse(raw)
      }

      if (booleanFormals(formal))
        Apply(Select(core("RtlParam"), TermName(if (declaration) "recursiveBooleanBinding" else "recursiveBooleanOverride")),
          List(Ident(formal), actual))
      else if (optionalFormals(formal))
        Apply(Select(core("RtlParam"),TermName(if(declaration) "recursiveOptionalBinding" else "recursiveOptionalOverride")),
          List(Ident(formal),actual))
      else Apply(Select(Select(Ident(termNames.ROOTPKG), TermName("scala")), TermName("Tuple2")),
        List(Ident(formal), actual))
    }
    val bindings = formals.map(_._2)
    val overridesByArgument = bindings.groupBy(_._2).map { case (actual,entries)=>actual -> entries.map(_._1) }
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
              val captures=scala.collection.mutable.ArrayBuffer.empty[Tree]
              val pairs = ordered.flatMap { case (parameter, actual) =>
                overridesByArgument.get(parameter) match {
                  case Some(formalNames) =>
                    val configurations=formalNames.flatMap(configurationFormals.get)
                    val supplied=if(configurations.nonEmpty) {
                      val name=TermName(unitFreshName("recursiveConfiguration"))
                      captures+=ValDef(Modifiers(),name,TypeTree(),transform(actual))
                      configurations.flatMap(_.specializations).distinct.foreach { fields=>
                        def select(base:Tree):Tree=fields.foldLeft(base)((t,n)=>Select(t,TermName(n)))
                        captures+=Apply(Select(core("RtlParam"),TermName("requireSameSpecialization")),List(
                          select(Ident(parameter)),select(Ident(name)),Literal(Constant((parameter.decodedName.toString::fields).mkString(".")))))
                      }
                      Ident(name)
                    } else transform(actual)
                    formalNames.map(formal=>pair(formal, supplied, declaration = false))
                  case None =>
                    actual match {
                      case Ident(n: TermName) if n == parameter =>
                      case _ => reject(actual, "recursive specialization arguments must forward their exact constructor binding")
                    }
                    None
                }
              }
              val rewritten=Apply(Ident(boundaryName), pairs).setPos(call.pos)
              if(captures.isEmpty)rewritten else Block(captures.toList,rewritten).setPos(call.pos)
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
        pair(formal, Ident(actual), declaration = true)
      })
    val locals = bindings.zipWithIndex.map { case ((formal, _), index) =>
      val value = Apply(Ident(valuesName), List(Literal(Constant(index))))
      ValDef(Modifiers(), formal, coreType(if (booleanFormals(formal)) "ElabBool" else "ElabInt"),
        if (booleanFormals(formal)) Apply(Select(value, TermName("elabEq")), List(Literal(Constant(1)))) else value)
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
