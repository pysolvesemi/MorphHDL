package morphhdl.compiler

import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent

/** Explicit Param declarations are lowered before ordinary symbolic control.
  * Domain evidence comes from authored unconditional requirements, never defaults.
  */
final class MorphHdlParameterSyntaxComponent(val global: Global) extends PluginComponent {
  import global._
  override val phaseName = "morphhdl-parameter-syntax"
  override val runsAfter = List("parser")
  override val runsBefore = List("morphhdl-direct-recursion", "morphhdl-typed-elaboration-control", "namer")
  private def path(t: Tree): List[String] = t match {
    case Ident(n) => List(n.decodedName.toString)
    case Select(q,n) => path(q) :+ n.decodedName.toString
    case _ => Nil
  }
  private def core(n: String): Tree =
    Select(Select(Select(Ident(termNames.ROOTPKG),TermName("spinal")),TermName("core")),TermName(n))
  private def coreType(n: String): Tree =
    Select(Select(Select(Ident(termNames.ROOTPKG),TermName("spinal")),TermName("core")),TypeName(n))
  private def constant(t: Tree): Option[BigInt] = t match {
    case Literal(Constant(n: Int)) => Some(BigInt(n))
    case Apply(Select(v,n),Nil) if n.decodedName.toString == "unary_-" => constant(v).map(-_)
    case Select(v,n) if n.decodedName.toString == "unary_-" => constant(v).map(-_)
    case Apply(Select(a,n),List(b)) => for(x<-constant(a);y<-constant(b);z<- n.decodedName.toString match {
      case "+" => Some(x+y);case "-" => Some(x-y);case "*" => Some(x*y)
      case "/" if y!=0 => Some(x/y);case _ => None
    }) yield z
    case _ => None
  }
  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit = {
      // Resolve source spellings lexically before rewriting them. An import in
      // another package/block must never grant a declaration parameter authority.
      val recognized = new java.util.IdentityHashMap[Tree, java.lang.Boolean]()
      lazy val builtinRequire: Tree => Boolean = {
        val resolver = new MorphHdlTypedElaborationControlComponent(global)
        val calls = resolver.canonicalPredefCalls(unit.body.asInstanceOf[resolver.global.Tree])
        (tree: Tree) => calls.containsKey(tree)
      }
      var names = Set.empty[String]
      var componentNames = Set.empty[String]
      var rtlParamNames = Set.empty[String]
      val rtlFactories = new java.util.IdentityHashMap[Tree, java.lang.Boolean]()
      val components = new java.util.IdentityHashMap[Tree, java.lang.Boolean]()
      val copiedRequires = new java.util.IdentityHashMap[Tree, java.lang.Boolean]()
      var blocked = Set.empty[String]
      var ambiguous = Set.empty[String]
      var typeNames = Map("Int" -> "Int", "Boolean" -> "Boolean", "Option" -> "Option")
      val recognizedKinds = new java.util.IdentityHashMap[Tree,String]()
      def defined(t: Tree): Option[String] = t match {
        case d: MemberDef => Some(d.name.decodedName.toString)
        case _ => None
      }
      val sourceProviders = scala.collection.mutable.Set.empty[List[String]]
      object Providers extends Traverser {
        private var prefix=List.empty[String]
        override def traverse(t:Tree):Unit=t match {
          case PackageDef(p,stats)=>
            val old=prefix;prefix=prefix++path(p).filterNot(_=="<empty>")
            sourceProviders += prefix;stats.foreach(traverse);prefix=old
          case m:ModuleDef=>
            val old=prefix;prefix=prefix :+ m.name.decodedName.toString
            sourceProviders += prefix;super.traverse(t);prefix=old
          case _=>super.traverse(t)
        }
      }
      global.currentRun.units.foreach(u=>Providers.traverse(u.body))
      def wildcardExports(q:Tree):Option[Set[String]] = {
        val full=path(q);val p=full.filterNot(n=>n=="_root_" || n=="<root>")
        if(p.isEmpty || sourceProviders.exists(_.takeRight(p.size)==p) ||
            (blocked(p.head) && !full.headOption.exists(n=>n=="_root_" || n=="<root>"))) return None
        try {
          val provider=rootMirror.getModuleIfDefined(p.mkString("."))
          if(provider==NoSymbol)None else {
            val primary=if(provider.isModule)provider.moduleClass.info else provider.info
            val pkg=primary.packageObject
            val infos=if(pkg==NoSymbol)List(primary)else List(primary,pkg.moduleClass.info)
            val tracked=names++componentNames++rtlParamNames++typeNames.keySet++Set("Param","RtlParam","Component","UInt","Bits","SInt","rtlParamName")
            Some(tracked.filter(n=>infos.exists(i=>i.nonPrivateMember(TermName(n))!=NoSymbol || i.nonPrivateMember(TypeName(n))!=NoSymbol)))
          }
        } catch { case scala.util.control.NonFatal(_)=>None }
      }
      object Resolve extends Traverser {
        def scope(stats: List[Tree])(body: => Unit): Unit = {
          val saved = names
          val savedComponents = componentNames
          val savedRtl = rtlParamNames
          val savedBlocked = blocked
          val savedAmbiguous = ambiguous
          val savedTypes = typeNames
          val declared = stats.flatMap(defined).toSet
          names --= declared
          componentNames --= declared
          rtlParamNames --= declared
          blocked ++= declared
          typeNames --= stats.collect {case d:ClassDef=>d.name.decodedName.toString;case d:TypeDef=>d.name.decodedName.toString}
          try body finally { names=saved; componentNames=savedComponents; rtlParamNames=savedRtl; blocked=savedBlocked; ambiguous=savedAmbiguous; typeNames=savedTypes }
        }
        def stats(trees: List[Tree]): Unit = trees.foreach(traverse)
        override def traverse(t: Tree): Unit = {
          val p=path(t).filterNot(n=>n=="_root_" || n=="<root>")
          val rooted=path(t).headOption.exists(n=>n=="_root_" || n=="<root>")
          val nativeKind=if(p.size==1)typeNames.get(p.head)
            else if(p.size==3 && p.take(2)==List("spinal","core") && (rooted || !blocked("spinal")) && Set("UInt","Bits","SInt","rtlParamName")(p.last))Some(p.last)
            else if(p.size==2 && p.head=="scala" && (rooted || !blocked("scala")) && Set("Int","Boolean","Option")(p.last))Some(p.last)
            else None
          if((p==List("spinal","core","Component") && (rooted || !blocked("spinal"))) ||
              (p.size==1 && componentNames(p.head))) components.put(t,true)
          if((p==List("spinal","core","RtlParam") && (rooted || !blocked("spinal"))) ||
              (p.size==1 && rtlParamNames(p.head))) rtlFactories.put(t,true)
          nativeKind.foreach(k=>recognizedKinds.put(t,k))
          if((p==List("spinal","core","Param") && (rooted || !blocked("spinal"))) ||
              (p.size==1 && names(p.head))) recognized.put(t,true)
          t match {
            case PackageDef(_,trees) => scope(trees) { stats(trees) }
            case template: Template => scope(template.body) {
              template.parents.foreach(traverse); stats(template.body)
            }
            case Block(trees,result) => scope(trees) { stats(trees); traverse(result) }
            case method: DefDef => scope(method.vparamss.flatten ::: method.tparams) {
              super.traverse(t)
            }
            case Import(q,selectors) =>
              val isCore=path(q).filterNot(n=>n=="_root_" || n=="<root>")==List("spinal","core") &&
                (path(q).headOption.exists(n=>n=="_root_" || n=="<root>") || !blocked("spinal"))
              selectors.foreach { selector =>
                val from=selector.name.decodedName.toString
                val to=Option(selector.rename).map(_.decodedName.toString).getOrElse(from)
                if(isCore && from=="_") {
                  val hidden=selectors.map(_.name.decodedName.toString).toSet
                  if(!hidden("Component") && !blocked("Component") && !ambiguous("Component"))componentNames += "Component"
                  if(!hidden("RtlParam") && !blocked("RtlParam") && !ambiguous("RtlParam"))rtlParamNames += "RtlParam"
                  if(!hidden("Param") && !blocked("Param") && !ambiguous("Param"))names += "Param"
                  for(k<-Seq("UInt","Bits","SInt","rtlParamName") if !hidden(k) && !blocked(k) && !ambiguous(k))typeNames += k->k
                }
                else if(from=="_") {
                  val hidden=selectors.map(_.name.decodedName.toString).toSet
                  val conflicts=wildcardExports(q).getOrElse(names++componentNames++rtlParamNames++typeNames.keySet++Set("Param","RtlParam","Component","UInt","Bits","SInt","rtlParamName")) -- hidden
                  ambiguous ++= conflicts
                  names --= conflicts
                  componentNames --= conflicts
                  rtlParamNames --= conflicts
                  typeNames --= conflicts
                } else if(from!="_") {
                  names -= to
                  componentNames -= to
                  rtlParamNames -= to
                  typeNames -= to
                  if(isCore && from=="Component" && to!="_" && !blocked(to))componentNames += to
                  if(isCore && from=="RtlParam" && to!="_" && !blocked(to))rtlParamNames += to
                  if(isCore && from=="Param" && to!="_" && !blocked(to)) names += to
                  if(isCore && Set("UInt","Bits","SInt","rtlParamName")(from) && to!="_" && !blocked(to))typeNames += to->from
                  if(path(q).filterNot(_=="_root_")==List("scala") && Set("Int","Boolean","Option")(from) && to!="_" && !blocked(to))typeNames += to->from
                }
              }
            case _ => super.traverse(t)
          }
        }
      }
      Resolve.traverse(unit.body)
      def isParam(t:Tree):Boolean = recognized.containsKey(t)
      def kind(t:Tree):Option[String]=t match {
        case AppliedTypeTree(factory,List(arg)) if isParam(factory) => Some(Option(recognizedKinds.get(arg)).getOrElse("unsupported source type"))
        case _=>None
      }
      def arguments(t:Tree):Option[List[Tree]]=t match {
        case Apply(factory,args) if isParam(factory) => Some(args)
        case Apply(Select(factory,n),args) if isParam(factory) && Set("apply","optional")(n.decodedName.toString) => Some(args)
        case _=>None
      }
      def optionalKind(t:Tree):Option[String]=t match {
        case AppliedTypeTree(option,List(inner)) if Option(recognizedKinds.get(option)).contains("Option") => kind(inner)
        case _=>None
      }
      def parameterType(t:Tree):Option[(String,Boolean)] = kind(t).map(_ -> false).orElse(optionalKind(t).map(_ -> true))
      def wrapper(k:String,optional:Boolean):Tree = Select(core("ConstructorParam"),TypeName(
        if(optional) "OptionalInteger" else if(k=="Boolean") "BooleanValue" else if(k=="Int") "Integer" else "Packed"))
      object Rewrite extends Transformer {
        private var bounds=Map.empty[String,(BigInt,BigInt)]
        private var dynamicBounds=Map.empty[String,(Vector[Tree],Vector[Tree])]
        private def bigint(value:Tree):Tree=Apply(Select(Select(Select(Ident(termNames.ROOTPKG),
          TermName("scala")),TermName("math")),TermName("BigInt")),List(value))
        private def boundArguments(name:String,lo:BigInt,hi:BigInt):List[Tree] = {
          val (lower,upper)=dynamicBounds.getOrElse(name,(Vector.empty,Vector.empty))
          def combine(n:BigInt,terms:Vector[Tree],op:String):Tree =
            if(terms.isEmpty) Literal(Constant(n.toInt))
            else terms.foldLeft(bigint(Literal(Constant(n.toInt)))) { (base,next) =>
              Apply(Select(base,TermName(op)),List(next.duplicate))
            }
          List(combine(lo,lower,"max"),combine(hi,upper,"min"))
        }
        override def transform(t:Tree):Tree=t match {
          case cls:ClassDef if cls.impl.parents.exists(p=>components.containsKey(p)) =>
            val ctors=cls.impl.body.collect {case d:DefDef if d.name==termNames.CONSTRUCTOR=>d}
            val forwarded=cls.impl.body.collect {case v:ValDef =>
              val actual = if(kind(v.tpt).nonEmpty) arguments(v.rhs).flatMap(_.headOption) else None
              actual.orElse(v.rhs match {
                case Apply(Select(factory,method),args) if rtlFactories.containsKey(factory) &&
                    Set("int","inferredInt","packed","bool","optional","optionalInt")(method.decodedName.toString) && args.size>=2 =>
                  Some(args(1))
                case _=>None
              }).collect {case Ident(n)=>n}
            }.flatten.toSet
            val params=ctors.headOption.toList.flatMap(_.vparamss.flatten)
            val owned=params.filter(p=>parameterType(p.tpt).nonEmpty && !forwarded(p.name))
            if(owned.isEmpty) super.transform(t)
            else if(ctors.size!=1 || ctors.head.vparamss.size!=1 || owned.exists(p=>p.mods.hasFlag(Flag.IMPLICIT))) {
              reporter.error(cls.pos,"MORPHDL-CONSTRUCTOR-PARAM-UNSUPPORTED: use one non-implicit primary constructor parameter list")
              super.transform(t)
            } else {
              val fields=cls.impl.body.collect {case v:ValDef if v.mods.hasFlag(Flag.PARAMACCESSOR)=>v}
              val unsupported=fields.filter(f=>owned.exists(_.name==f.name) &&
                (f.mods.hasFlag(Flag.MUTABLE) || !f.mods.hasFlag(Flag.LOCAL)))
              if(unsupported.nonEmpty) reporter.error(unsupported.head.pos,
                "MORPHDL-CONSTRUCTOR-PARAM-UNSUPPORTED: use plain immutable constructor arguments, not val/var accessors")
              val mapping=owned.map(p=>p.name -> TermName(unit.fresh.newName("parameter_"+p.name.decodedName.toString+"_"))).toMap
              val ownedByName=owned.map(p=>p.name->p).toMap
              object Rename extends Transformer {
                private var hidden=Set.empty[Name]
                private var untouched:Tree=EmptyTree
                private var nestedOwners=Set.empty[String]
                private def ownerScope[A](name:Name)(body: => A):A={
                  val saved=nestedOwners;nestedOwners+=name.decodedName.toString
                  try body finally nestedOwners=saved
                }
                private def scoped[A](names:Set[Name])(body: => A):A={val saved=hidden;hidden++=names;try body finally hidden=saved}
                override def transform(tree:Tree):Tree={
                  val result=tree match {
                    case same if same eq untouched=>same
                    case Ident(n) if mapping.contains(n.toTermName) && !hidden(n)=>Ident(mapping(n.toTermName)).setPos(tree.pos)
                    case Select(th:This,n) if mapping.contains(n.toTermName) &&
                        ((th.qual==global.typeNames.EMPTY && nestedOwners.isEmpty) ||
                          (th.qual==cls.name && !nestedOwners(th.qual.decodedName.toString)))=>
                      Select(th,mapping(n.toTermName)).setPos(tree.pos)
                    case d:DefDef=>scoped(d.vparamss.flatten.map(_.name:Name).toSet++d.tparams.map(_.name:Name)) {super.transform(tree)}
                    case c:CaseDef=>
                      val bound=scala.collection.mutable.HashSet.empty[Name]
                      object PatternNames extends Traverser {override def traverse(t:Tree):Unit=t match {
                        case b:Bind=>bound+=b.name;super.traverse(t)
                        case _=>super.traverse(t)
                      }}
                      PatternNames.traverse(c.pat)
                      scoped(bound.toSet){super.transform(tree)}
                    case f:Function=>scoped(f.vparams.map(_.name:Name).toSet){super.transform(tree)}
                    case b:Block=>scoped(b.stats.collect {case m:MemberDef=>m.name:Name}.toSet){super.transform(tree)}
                    case c:ClassDef=>ownerScope(c.name){scoped(c.impl.body.collect{case m:MemberDef=>m.name:Name}.toSet){super.transform(tree)}}
                    case m:ModuleDef=>ownerScope(m.name){scoped(m.impl.body.collect{case d:MemberDef=>d.name:Name}.toSet){super.transform(tree)}}
                    // Named-argument labels are not variable reads.
                    case a if Set("AssignOrNamedArg","NamedArg")(a.productPrefix)=>
                      val parts=a.children
                      if(parts.size==2) {
                        val saved=untouched;untouched=parts.head
                        try super.transform(tree) finally untouched=saved
                      } else super.transform(tree)
                    case _=>super.transform(tree)
                  }
                  if(builtinRequire(tree))copiedRequires.put(result,true)
                  result
                }
              }
              val declarations=owned.map { p=>
                val (k,optional)=parameterType(p.tpt).get
                if(optional && k!="Int")reporter.error(p.pos,"MORPHDL-CONSTRUCTOR-PARAM-UNSUPPORTED: optional constructor parameters support Param[Int]")
                val scalarType=if(optional)p.tpt.asInstanceOf[AppliedTypeTree].args.head else p.tpt
                val factory=core("Param")
                recognized.put(factory,true)
                val annotations=p.mods.annotations.collect {
                  case Apply(Select(New(tpt),_),args)
                    if Option(recognizedKinds.get(tpt)).contains("rtlParamName")=>args
                }
                val overrides=annotations.collect {
                  case List(value @ Literal(Constant(name:String))) if name.matches("[A-Za-z_][A-Za-z0-9_]*")=>value
                }
                if(annotations.size!=overrides.size || overrides.size>1)
                  reporter.error(p.pos,"MORPHDL-CONSTRUCTOR-PARAM-NAME: use one portable literal HDL-name override")
                val hdlName=overrides.headOption.getOrElse(Literal(Constant(p.name.decodedName.toString)))
                val actual=Select(Ident(p.name),TermName("value"))
                val rhs=if(optional)Apply(Select(factory,TermName("optional")),List(actual,Literal(Constant(0)),hdlName))
                  else Apply(factory,List(actual,hdlName))
                ValDef(Modifiers(Flag.PRIVATE),mapping(p.name),scalarType,rhs).setPos(p.pos)
              }
              val body=cls.impl.body.flatMap {
                case ctor:DefDef if ctor.name==termNames.CONSTRUCTOR=>
                  val lists=ctor.vparamss.map(_.map { p=>ownedByName.get(p.name) match {
                    case Some(source)=>val(k,opt)=parameterType(source.tpt).get
                      treeCopy.ValDef(p,p.mods,p.name,wrapper(k,opt),p.rhs)
                    case None=>p
                  }})
                  List(treeCopy.DefDef(ctor,ctor.mods,ctor.name,ctor.tparams,lists,ctor.tpt,ctor.rhs))++declarations
                case field:ValDef if field.mods.hasFlag(Flag.PARAMACCESSOR) && ownedByName.contains(field.name)=>
                  val(k,opt)=parameterType(ownedByName(field.name).tpt).get
                  List(treeCopy.ValDef(field,field.mods,field.name,wrapper(k,opt),field.rhs))
                case other=>List(Rename.transform(other))
              }
              // Generated formal spellings retain the exact recognized Param type;
              // copied require calls retain canonical Predef authority only.
              super.transform(treeCopy.ClassDef(cls,cls.mods,cls.name,cls.tparams,
                treeCopy.Template(cls.impl,cls.impl.parents,cls.impl.self,body)))
            }
          case template: Template =>
            val old=bounds
            val oldDynamic=dynamicBounds
            dynamicBounds=Map.empty
            val constructorLimits=template.body.collect {
              case v:ValDef if !v.mods.hasFlag(Flag.MUTABLE) &&
                Set(List("Int"),List("scala","Int"))(path(v.tpt).filterNot(_=="_root_")) => v.name.decodedName.toString
            }.toSet
            val constants=template.body.collect {
              case v:ValDef if !v.mods.hasFlag(Flag.MUTABLE) && constant(v.rhs).nonEmpty =>
                v.name.decodedName.toString -> constant(v.rhs).get
            }.toMap
            def number(t:Tree):Option[BigInt]=constant(t).orElse(t match {
              case Ident(n)=>constants.get(n.decodedName.toString)
              case _=>None
            })
            val declarations=template.body.collect {case v:ValDef if kind(v.tpt).nonEmpty && arguments(v.rhs).nonEmpty=>v}
            bounds=declarations.map(v=>v.name.decodedName.toString ->
              (if(kind(v.tpt).contains("SInt")) BigInt(Int.MinValue) else BigInt(0),BigInt(Int.MaxValue))).toMap
            def constrain(condition:Tree):Unit=condition match {
              case Apply(Select(a,n),List(b)) if n.decodedName.toString=="&&" =>constrain(a);constrain(b)
              case Apply(Select(Ident(name),op),List(rhs)) if bounds.contains(name.decodedName.toString) && number(rhs).nonEmpty =>
                val key=name.decodedName.toString;val (lo,hi)=bounds(key);val value=number(rhs).get
                bounds += key -> (op.decodedName.toString match {
                  case ">=" => (lo.max(value),hi);case ">" => (lo.max(value+1),hi)
                  case "<=" => (lo,hi.min(value));case "<" => (lo,hi.min(value-1))
                  case "==" => (lo.max(value),hi.min(value));case _ => (lo,hi)
                })
              case Apply(Select(Ident(name),op),List(rhs @ Ident(limit)))
                  if bounds.contains(name.decodedName.toString) && constructorLimits(limit.decodedName.toString) =>
                val key=name.decodedName.toString
                val (lower,upper)=dynamicBounds.getOrElse(key,(Vector.empty,Vector.empty))
                val bound=bigint(rhs.duplicate)
                val increment=Apply(Select(bound,TermName("$plus")),List(Literal(Constant(1))))
                val decrement=Apply(Select(bound,TermName("$minus")),List(Literal(Constant(1))))
                dynamicBounds += key -> (op.decodedName.toString match {
                  case ">=" => (lower :+ bound,upper)
                  case ">" => (lower :+ increment,upper)
                  case "<=" => (lower,upper :+ bound)
                  case "<" => (lower,upper :+ decrement)
                  case "==" => (lower :+ bound,upper :+ bound)
                  case _ => (lower,upper)
                })
              case _ =>
            }
            if(declarations.nonEmpty) template.body.foreach {
              case call @ Apply(fun,args) if path(fun).lastOption.contains("require") && (builtinRequire(call) || copiedRequires.containsKey(call)) && args.nonEmpty =>constrain(args.head)
              case _ =>
            }
            try super.transform(template) finally { bounds=old; dynamicBounds=oldDynamic }
          case value:ValDef if kind(value.tpt).nonEmpty && arguments(value.rhs).nonEmpty =>
            val k=kind(value.tpt).get
            val supplied=arguments(value.rhs).get
            val optional=value.rhs match {
              case Apply(Select(_,n),_)=>n.decodedName.toString=="optional"
              case _=>false
            }
            val args=if(optional && supplied.size>=2) supplied.head :: supplied.drop(2) else supplied
            if((optional && (supplied.size<2 || supplied.size>3 || k!="Int")) || (args.size!=1 && args.size!=2)) {
              reporter.error(value.pos,"MORPHDL-PARAM-DECLARATION: Param needs an actual/default and optional HDL name; Param.optional[Int] also needs an absent integer value")
              value
            } else {
              val name=if(args.size==2) args(1) else Literal(Constant(value.name.decodedName.toString))
              val actual=transform(args.head)
              val rhs=k match {
                case "Boolean" => Apply(Select(core("RtlParam"),TermName("bool")),List(name,actual))
                case "Int" =>
                  val (lo,hi)=bounds.getOrElse(value.name.decodedName.toString,(BigInt(0),BigInt(Int.MaxValue)))
                  if(lo>hi || !lo.isValidInt || !hi.isValidInt || (hi-lo+1>65536 && !dynamicBounds.contains(value.name.decodedName.toString))) {
                    reporter.error(value.pos,"MORPHDL-PARAM-DOMAIN-UNPROVEN: provide unconditional require constraints proving a finite domain of at most 65536 values; the default is not a domain bound")
                    value.rhs
                  } else {
                    val parameters=List(name,actual) ++ boundArguments(value.name.decodedName.toString,lo,hi)
                    Apply(Select(core("RtlParam"),TermName(if(optional)"optional" else "inferredInt")),
                      if(optional) parameters :+ transform(supplied(1)) else parameters)
                  }
                case "UInt" | "Bits" | "SInt" =>
                  val (lo,hi)=bounds.getOrElse(value.name.decodedName.toString,
                    (if(k=="SInt")BigInt(Int.MinValue) else BigInt(0),BigInt(Int.MaxValue)))
                  Apply(Select(core("RtlParam"),TermName("packed")),List(name,actual) ++ boundArguments(value.name.decodedName.toString,lo,hi) :+ Literal(Constant(k)))
                case _ =>
                  reporter.error(value.pos,s"MORPHDL-PARAM-TYPE-UNSUPPORTED: Param[$k] declaration is not supported")
                  value.rhs
              }
              treeCopy.ValDef(value,value.mods,value.name,coreType(if(k=="Boolean")"ElabBool" else "ElabInt"),rhs).setPos(value.pos)
            }
          case AppliedTypeTree(factory,List(arg)) if isParam(factory) =>
            Option(recognizedKinds.get(arg)) match {
              case Some("Int" | "UInt" | "Bits" | "SInt")=>coreType("ElabInt").setPos(t.pos)
              case Some("Boolean")=>coreType("ElabBool").setPos(t.pos)
              case _=>super.transform(t)
            }
          case _=>super.transform(t)
        }
      }
      unit.body=Rewrite.transform(unit.body)
    }
  }
}
