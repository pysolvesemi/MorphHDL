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
  // Literal-only Scala Int operations use JVM sizing, overflow and masked shift
  // distances. Symbolic ElabInt arithmetic is deliberately a separate grammar.
  private def hostInteger(t: Tree): Option[Int] = t match {
    case Literal(Constant(n: Int)) => Some(n)
    case Apply(Select(v,n),Nil) if n.decodedName.toString == "unary_-" => hostInteger(v).map(-_)
    case Select(v,n) if n.decodedName.toString == "unary_-" => hostInteger(v).map(-_)
    case Apply(Select(a,n),List(b)) => for(x<-hostInteger(a);y<-hostInteger(b);z<- n.decodedName.toString match {
      case "+" => Some(x+y);case "-" => Some(x-y);case "*" => Some(x*y)
      case "/" if y!=0 => Some(x/y);case "%" if y!=0 => Some(x%y)
      case "<<" => Some(x << y);case ">>" => Some(x >> y);case ">>>" => Some(x >>> y)
      case "&" => Some(x & y);case "|" => Some(x | y);case "^" => Some(x ^ y)
      case _ => None
    }) yield z
    case _ => None
  }
  private def constant(t: Tree): Option[BigInt] = hostInteger(t).map(BigInt(_))
  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    private var schemaRun: global.Run = null
    private var sourceTrees = Vector.empty[Tree]
    private val sharedRecognized = new java.util.IdentityHashMap[Tree, java.lang.Boolean]()
    private val sharedFactories = new java.util.IdentityHashMap[Tree, java.lang.Boolean]()
    private val sharedComponents = new java.util.IdentityHashMap[Tree, java.lang.Boolean]()
    private val sharedKinds = new java.util.IdentityHashMap[Tree, String]()
    private var resolvedSchemas = false
    private var retainedSchemas = false
    private final case class ConfigurationValueAccess(field:ValDef)
    private final case class ConfigSchema(owner: List[String], definition: ClassDef, fields: List[ValDef])
    private val sharedSchemas = scala.collection.mutable.LinkedHashMap.empty[List[String], ConfigSchema]
    private val ambiguousSchemas = scala.collection.mutable.Set.empty[List[String]]
    private val schemaImports = new java.util.IdentityHashMap[Tree, Vector[List[String]]]()
    private val sharedComponentClasses = scala.collection.mutable.ArrayBuffer.empty[(List[String], ClassDef)]
    private val configurationRequirements = new java.util.IdentityHashMap[Tree, Tree]()
    private val canonicalConfigurationRequires = new java.util.IdentityHashMap[Tree, java.lang.Boolean]()
    private val derivedExpressions = new java.util.IdentityHashMap[ValDef, Tree]()
    private val sourceHelpers = scala.collection.mutable.LinkedHashMap.empty[List[String], Vector[DefDef]]
    private val helperObjects = scala.collection.mutable.Map.empty[List[String], ModuleDef]
    private val helperImports = new java.util.IdentityHashMap[Tree, Vector[List[String]]]()
    private val sharedConfigClasses = scala.collection.mutable.Set.empty[ClassDef]
    override def apply(unit: CompilationUnit): Unit = {
      if (schemaRun ne currentRun) {
        schemaRun = currentRun
        sourceTrees = currentRun.units.map(_.body).toVector
        sharedRecognized.clear(); sharedFactories.clear(); sharedComponents.clear(); sharedKinds.clear()
        sharedSchemas.clear(); ambiguousSchemas.clear(); schemaImports.clear(); sharedComponentClasses.clear(); sharedConfigClasses.clear()
        configurationRequirements.clear(); canonicalConfigurationRequires.clear(); derivedExpressions.clear(); sourceHelpers.clear(); helperImports.clear(); helperObjects.clear()
        resolvedSchemas=false; retainedSchemas=false
      }
      // Resolve source spellings lexically before rewriting them. An import in
      // another package/block must never grant a declaration parameter authority.
      val recognized = sharedRecognized
      lazy val builtinRequire: Tree => Boolean = {
        val resolver = new MorphHdlTypedElaborationControlComponent(global, () => false)
        val calls = resolver.canonicalPredefCalls(unit.body.asInstanceOf[resolver.global.Tree])
        (tree: Tree) => calls.containsKey(tree)
      }
      var names = Set.empty[String]
      var componentNames = Set.empty[String]
      var rtlParamNames = Set.empty[String]
      val rtlFactories = sharedFactories
      val components = sharedComponents
      val copiedRequires = new java.util.IdentityHashMap[Tree, java.lang.Boolean]()
      var blocked = Set.empty[String]
      var ambiguous = Set.empty[String]
      var typeNames = Map("Int" -> "Int", "Boolean" -> "Boolean", "Option" -> "Option")
      val recognizedKinds = sharedKinds
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
            val tracked=names++componentNames++rtlParamNames++typeNames.keySet++Set("Param","RtlParam","Component","UInt","Bits","SInt","rtlParamName","ElabInt")
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
            else if(p.size==3 && p.take(2)==List("spinal","core") && (rooted || !blocked("spinal")) && Set("UInt","Bits","SInt","rtlParamName","ElabInt")(p.last))Some(p.last)
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
                  for(k<-Seq("UInt","Bits","SInt","rtlParamName","ElabInt") if !hidden(k) && !blocked(k) && !ambiguous(k))typeNames += k->k
                }
                else if(from=="_") {
                  val hidden=selectors.map(_.name.decodedName.toString).toSet
                  val conflicts=wildcardExports(q).getOrElse(names++componentNames++rtlParamNames++typeNames.keySet++Set("Param","RtlParam","Component","UInt","Bits","SInt","rtlParamName","ElabInt")) -- hidden
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
                  if(isCore && Set("UInt","Bits","SInt","rtlParamName","ElabInt")(from) && to!="_" && !blocked(to))typeNames += to->from
                  if(path(q).filterNot(_=="_root_")==List("scala") && Set("Int","Boolean","Option")(from) && to!="_" && !blocked(to))typeNames += to->from
                }
              }
            case _ => super.traverse(t)
          }
        }
      }
      if (!resolvedSchemas) { sourceTrees.foreach(Resolve.traverse); resolvedSchemas=true }
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
      // Source-owned immutable configuration schemas. No reflection/getter
      // evaluation can grant a field parameter authority.
      val schemas = sharedSchemas
      val componentClasses = sharedComponentClasses
      object Schemas extends Traverser {
        private var owner = List.empty[String]
        private var imported = Map.empty[String,List[String]]
        private var wildcard = Vector.empty[List[String]]
        private def scope(body: => Unit):Unit={
          val oldImported=imported;val oldWildcard=wildcard
          try body finally {imported=oldImported;wildcard=oldWildcard}
        }
        private def captureType(tpt:Tree):Unit={
          val names=path(tpt).filterNot(_=="_root_")
          if(names.nonEmpty) {
            val candidates=imported.get(names.head).map(_++names.tail).toVector ++
              (if(names.size==1)wildcard.map(_++names)else Vector.empty)
            schemaImports.put(tpt,candidates)
          }
        }
        override def traverse(tree: Tree): Unit = tree match {
          case Import(qualifier,selectors)=>
            val prefix=path(qualifier).filterNot(_=="_root_")
            selectors.foreach { selector=>
              val name=selector.name.decodedName.toString
              val renamed=Option(selector.rename).map(_.decodedName.toString).getOrElse(name)
              if(name=="_")wildcard :+= prefix
              else if(renamed!="_")imported+=renamed -> (prefix :+ name)
            }
          case PackageDef(name, body) =>
            val old = owner; owner = owner ++ path(name).filterNot(_ == "<empty>")
            scope(body.foreach(traverse)); owner = old
          case cls: ClassDef =>
            val key = owner :+ cls.name.decodedName.toString
            val fields = cls.impl.body.collectFirst {
              case ctor: DefDef if ctor.name == termNames.CONSTRUCTOR => ctor.vparamss.flatten
            }.getOrElse(Nil)
            fields.foreach(field=>captureType(field.tpt))
            if (schemas.contains(key)) ambiguousSchemas += key
            else schemas(key) = ConfigSchema(key, cls, fields)
            if (cls.impl.parents.exists(components.containsKey)) componentClasses += key -> cls
            val old=owner; owner=key; scope(super.traverse(tree)); owner=old
          case module: ModuleDef =>
            helperObjects(owner :+ module.name.decodedName.toString)=module
            val old=owner; owner=owner :+ module.name.decodedName.toString
            scope(super.traverse(tree)); owner=old
          case call: Apply =>
            val names=path(call.fun).filterNot(_=="_root_")
            if(names.nonEmpty) helperImports.put(call, imported.get(names.head).map(_++names.tail).toVector ++
              (if(names.size<=2)wildcard.map(_++names)else Vector.empty))
            super.traverse(tree)
          case method: DefDef =>
            if(method.name!=termNames.CONSTRUCTOR) {
              val key=owner :+ method.name.decodedName.toString
              sourceHelpers(key)=sourceHelpers.getOrElse(key,Vector.empty) :+ method
            }
            val old=owner; owner=owner :+ ("$method:" + method.name.decodedName.toString)
            scope(super.traverse(tree)); owner=old
          case block:Block=>scope(super.traverse(block))
          case _ => super.traverse(tree)
        }
      }
      if (!retainedSchemas) sourceTrees.foreach { tree=>
        Schemas.traverse(tree)
        val resolver=new MorphHdlTypedElaborationControlComponent(global,()=>false)
        val calls=resolver.canonicalPredefCalls(tree.asInstanceOf[resolver.global.Tree]).keySet().iterator()
        while(calls.hasNext)canonicalConfigurationRequires.put(calls.next().asInstanceOf[Tree],true)
      }
      def configuration(tpt: Tree, owner: List[String]): Option[ConfigSchema] = {
        val names=path(tpt).filterNot(_ == "_root_")
        if (names.isEmpty) None else {
          val rooted=path(tpt).headOption.contains("_root_")
          val candidates=if(rooted)Vector(names)else owner.inits.map(_ ++ names).toVector :+ names
          val local=candidates.iterator.flatMap(schemas.get).take(1).toList.headOption
          val imported=Option(schemaImports.get(tpt)).toVector.flatten.flatMap { candidate=>
            (owner.inits.map(_++candidate).toVector :+ candidate).iterator.flatMap(schemas.get).take(1).toVector
          }.distinct
          if(local.isEmpty && imported.size>1)reporter.error(tpt.pos,
            "MORPHDL-CONFIG-SHAPE-UNSUPPORTED: ambiguous imported configuration declaration")
          local.orElse(imported.headOption).map { schema=>
            if(ambiguousSchemas(schema.owner)) reporter.error(tpt.pos,
              "MORPHDL-CONFIG-SHAPE-UNSUPPORTED: ambiguous local configuration declaration; use a uniquely owned class")
            schema
          }
        }
      }
      def containsParameters(schema: ConfigSchema, seen: Set[List[String]] = Set.empty): Boolean =
        !seen(schema.owner) && schema.fields.exists(field => parameterType(field.tpt).nonEmpty ||
          configuration(field.tpt,schema.owner.init).exists(containsParameters(_,seen + schema.owner)))
      val configClasses = sharedConfigClasses
      // Derivations are rebuilt from a closed, source-authenticated arithmetic
      // grammar. Inline helper bodies rather than invoking arbitrary objects or
      // getters. Reconstructing the configuration later substitutes owned roots.
      // Constant body fields keep Scala Int specialization semantics, including
      // per-operation overflow; references must not reinterpret them as symbolic
      // arithmetic (or read a forward field before Scala initializes it).
      def derived(schema:ConfigSchema, field:ValDef, active:Set[ValDef]=Set.empty, predicate:Boolean=false):Tree = {
        Option(derivedExpressions.get(field)).getOrElse {
          def reject(t:Tree, detail:String):Tree={
            reporter.error(t.pos,"MORPHDL-CONFIG-DERIVED-UNSUPPORTED: "+detail)
            Apply(Select(core("ElabInt"),TermName("literal")),List(Literal(Constant(0))))
          }
          if(active(field)) return reject(field,"cyclic derived field dependency")
          val members=schema.definition.impl.body.collect {
            case v:ValDef if !v.mods.hasFlag(Flag.PARAMACCESSOR)=>v.name.decodedName.toString -> v
          }.toMap
          def arithmetic(t:Tree, env:Map[String,Tree], owner:List[String], calls:Set[List[String]]):Tree = {
            def loop(v:Tree)=arithmetic(v,env,owner,calls)
            def memberPath(t:Tree):List[String]=t match {
              case Ident(n)=>List(n.decodedName.toString)
              case Select(th:This,n) if th.qual==global.typeNames.EMPTY || th.qual==schema.definition.name=>List(n.decodedName.toString)
              case Select(_:This,_)=>Nil
              case Select(q,n)=>val prefix=memberPath(q);if(prefix.nonEmpty)prefix :+ n.decodedName.toString else Nil
              case _=>Nil
            }
            val names=memberPath(t)
            def fieldAccess(current:ConfigSchema, rest:List[String], prefix:Tree):Option[Tree]=rest match {
              case name::Nil if current.definition.impl.body.exists {
                case v:ValDef=> !v.mods.hasFlag(Flag.PARAMACCESSOR) && v.name.decodedName.toString==name
                case _=>false
              }=>
                val member=current.definition.impl.body.collectFirst {
                  case v:ValDef if !v.mods.hasFlag(Flag.PARAMACCESSOR) && v.name.decodedName.toString==name=>v
                }.get
                val expanded=derived(current,member,active+field).duplicate
                val roots=current.fields.map(_.name).toSet
                object Qualify extends Transformer {
                  override def transform(t:Tree):Tree=t match {
                    case Ident(n) if roots(n.toTermName) && prefix!=EmptyTree=>Select(prefix.duplicate,n)
                    case _=>super.transform(t)
                  }
                }
                Some(Qualify.transform(expanded))
              case name::tail=>current.fields.find(_.name.decodedName.toString==name).flatMap { f=>
                val access=if(prefix==EmptyTree)Ident(f.name) else Select(prefix,f.name)
                if(tail.isEmpty) parameterType(f.tpt) match {
                  case Some(("Int",false))=>Some(Select(access,TermName("value")).updateAttachment(ConfigurationValueAccess(f)))
                  case None if Option(recognizedKinds.get(f.tpt)).exists(Set("Int","ElabInt"))=>
                    Some(Apply(Select(core("ConstructorParam"),TermName("integer")),List(access)))
                  case _=>None
                } else configuration(f.tpt,current.owner.init).flatMap(fieldAccess(_,tail,access))
              }
              case _=>None
            }
            t match {
              case _ if hostInteger(t).nonEmpty=>Apply(Select(core("ElabInt"),TermName("literal")),List(Literal(Constant(hostInteger(t).get))))
              case Apply(Select(_,op),_) if Set("<<",">>",">>>","&","|","^")(op.decodedName.toString)=>
                reject(t,"symbolic integer shift/bitwise operation is unsupported; only literal host operations may be folded")
              case Ident(n) if env.contains(n.decodedName.toString)=>env(n.decodedName.toString).duplicate
              case Ident(n) if calls.isEmpty && members.contains(n.decodedName.toString)=>
                derived(schema,members(n.decodedName.toString),active+field).duplicate
              case _ if calls.isEmpty && names.nonEmpty && fieldAccess(schema,names,EmptyTree).nonEmpty=>
                fieldAccess(schema,names,EmptyTree).get
              case Apply(Select(a,op),List(b)) if predicate && Set("<","<=",">",">=","==","!=","&&","||")(op.decodedName.toString)=>
                val method=op.decodedName.toString match {case "=="=>TermName("elabEq");case "!="=>TermName("elabNe");case _=>op}
                Apply(Select(loop(a),method),List(loop(b)))
              case Apply(Select(a,op),List(b)) if Set("+","-","*","/","%")(op.decodedName.toString)=>
                Apply(Select(loop(a),op),List(loop(b)))
              case Select(a,op) if op.decodedName.toString=="unary_-"=>
                Apply(Select(loop(Literal(Constant(0))),TermName("$minus")),List(loop(a)))
              case Apply(Select(a,op),Nil) if op.decodedName.toString=="unary_-"=>
                Apply(Select(loop(Literal(Constant(0))),TermName("$minus")),List(loop(a)))
              case Select(a,op) if Set("pow2","log2Up","addressWidth")(op.decodedName.toString)=>Select(loop(a),op)
              case Apply(Select(a,op),Nil) if Set("pow2","log2Up","addressWidth")(op.decodedName.toString)=>Select(loop(a),op)
              case call:Apply=>
                if(call.fun.exists(_.isInstanceOf[This]))return reject(call,"helper receiver must be a source-owned object path")
                val name=path(call.fun).filterNot(_=="_root_")
                val rooted=path(call.fun).headOption.contains("_root_")
                val shadowed= !rooted && name.headOption.exists(n=>env.contains(n) ||
                  (calls.isEmpty && (members.contains(n) || schema.fields.exists(_.name.decodedName.toString==n))))
                val local=(if(rooted)Vector(name)else owner.inits.map(_++name).toVector)
                  .find(sourceHelpers.contains)
                val imported=Option(helperImports.get(call)).toVector.flatten.flatMap { candidate=>
                  (owner.inits.map(_++candidate).toVector :+ candidate).find(sourceHelpers.contains)
                }.distinct
                val found=if(shadowed || (local.isEmpty && imported.size>1))Nil else (if(local.nonEmpty)local.toList else imported.toList)
                found.headOption match {
                  case Some(key) if !calls(key) && sourceHelpers(key).size==1 && helperObjects.contains(key.init)=>
                    val obj=helperObjects(key.init)
                    val pureObject=obj.impl.parents.forall(p=>Set("AnyRef","scala.AnyRef","Object","java.lang.Object")(path(p).mkString("."))) &&
                      obj.impl.body.forall {
                        case d:DefDef=>true
                        case _:Import=>true
                        case v:ValDef=> !v.mods.hasFlag(Flag.MUTABLE) && !v.mods.hasFlag(Flag.LAZY) && constant(v.rhs).nonEmpty &&
                          (v.tpt.isEmpty || Option(recognizedKinds.get(v.tpt)).contains("Int"))
                        case _=>false
                      }
                    if(!pureObject) return reject(call,"helper object has unsupported initialization or inheritance")
                    val helper=sourceHelpers(key).head
                    val params=helper.vparamss.flatten
                    if(helper.tparams.nonEmpty || helper.vparamss.size!=1 || params.size!=call.args.size ||
                      (helper.tpt.nonEmpty && !Option(recognizedKinds.get(helper.tpt)).exists(Set("Int","ElabInt"))) ||
                      params.exists(p=>p.mods.hasFlag(Flag.IMPLICIT) || !Option(recognizedKinds.get(p.tpt)).exists(Set("Int","ElabInt"))))
                      reject(call,"helper needs one explicit immutable integer argument list")
                    else arithmetic(helper.rhs,params.map(_.name.decodedName.toString).zip(call.args.map(loop)).toMap,key.init,calls+key)
                  case _=>reject(call,"helper must have a unique nonrecursive source body")
                }
              case Block(stats,result)=>
                var locals=env
                stats.foreach {
                  case v:ValDef if !v.mods.hasFlag(Flag.MUTABLE) && !v.mods.hasFlag(Flag.LAZY) &&
                    (v.tpt.isEmpty || Option(recognizedKinds.get(v.tpt)).exists(Set("Int","ElabInt")))=>
                    locals+=v.name.decodedName.toString -> arithmetic(v.rhs,locals,owner,calls)
                  case other=>reject(other,"helper blocks admit immutable arithmetic values only")
                }
                arithmetic(result,locals,owner,calls)
              case _=>reject(t,"expected immutable integer fields, arithmetic or a pure source helper")
            }
          }
          val result=hostInteger(field.rhs).map(n=>Apply(Select(core("ElabInt"),TermName("literal")),List(Literal(Constant(n)))):Tree)
            .getOrElse(arithmetic(field.rhs,Map.empty,schema.owner.init,Set.empty))
          derivedExpressions.put(field,result);result
        }
      }
      def retainConfiguration(schema: ConfigSchema, seen: Set[List[String]]): Unit = {
        if (seen(schema.owner)) {
          reporter.error(schema.definition.pos,"MORPHDL-CONFIG-CYCLE: cyclic configuration shapes are unsupported")
          return
        }
        if (configClasses(schema.definition)) return
        configClasses += schema.definition
        val cls=schema.definition
        val accessors=cls.impl.body.collect { case value: ValDef if value.mods.hasFlag(Flag.PARAMACCESSOR) => value }
        val constructors=cls.impl.body.collect { case d: DefDef if d.name==termNames.CONSTRUCTOR => d }
        val ordinaryParents=Set("AnyRef","scala.AnyRef","Object","java.lang.Object","Product","scala.Product","Serializable","scala.Serializable")
        if (cls.tparams.nonEmpty || cls.impl.parents.exists(p=> !ordinaryParents(path(p).filterNot(_=="_root_").mkString("."))) ||
            constructors.size!=1 || constructors.head.vparamss.size!=1 ||
            accessors.exists(v=>v.mods.hasFlag(Flag.MUTABLE) || v.mods.hasFlag(Flag.LOCAL)) ||
            cls.impl.body.exists {
              case _: TypeDef => true
              case d: DefDef => d.name!=termNames.CONSTRUCTOR
              case v: ValDef => !v.mods.hasFlag(Flag.PARAMACCESSOR) &&
                (v.mods.hasFlag(Flag.MUTABLE) || v.mods.hasFlag(Flag.LAZY))
              case _: Import => false
              case call:Apply if canonicalConfigurationRequires.containsKey(call) && path(call.fun).lastOption.contains("require") &&
                (call.args.size==1 || (call.args.size==2 && call.args(1).isInstanceOf[Literal]))=>false
              case _ => true
            }) reporter.error(cls.pos,
          "MORPHDL-CONFIG-SHAPE-UNSUPPORTED: use an immutable constructor-only case class or class with public val fields; mutable, lazy or effectful members are unsupported; derived values require authenticated integer expressions")
        cls.impl.body.collect {case v:ValDef if !v.mods.hasFlag(Flag.PARAMACCESSOR)=>v}.foreach { v=>
          if(v.tpt.nonEmpty && !Option(recognizedKinds.get(v.tpt)).exists(Set("Int","ElabInt")))
            reporter.error(v.pos,"MORPHDL-CONFIG-DERIVED-UNSUPPORTED: derived fields require native integer types")
          derived(schema,v)
        }
        cls.impl.body.foreach {
          case call:Apply if canonicalConfigurationRequires.containsKey(call) && path(call.fun).lastOption.contains("require") && call.args.nonEmpty=>
            val condition=ValDef(Modifiers(),TermName("configuration_requirement"),EmptyTree,call.args.head).setPos(call.pos)
            configurationRequirements.put(call,derived(schema,condition,predicate=true))
          case _=>
        }
        schema.fields.foreach(field=>configuration(field.tpt,schema.owner.init)
          .filter(containsParameters(_)).foreach(retainConfiguration(_,seen+schema.owner)))
      }
      if (!retainedSchemas) componentClasses.foreach { case (owner, cls) =>
        schemas(owner).fields.foreach(field=>configuration(field.tpt,owner.init)
          .filter(containsParameters(_)).foreach(retainConfiguration(_,Set.empty)))
      }
      retainedSchemas = true
      object Rewrite extends Transformer {
        private val parameterOrigins = new java.util.IdentityHashMap[Tree, String]()
        private val configurationOrigins = new java.util.IdentityHashMap[Tree, ConfigurationFormalPath]()
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
          case cls: ClassDef if configClasses(cls) =>
            val body=cls.impl.body.map {
              case ctor: DefDef if ctor.name==termNames.CONSTRUCTOR =>
                val lists=ctor.vparamss.map(_.map { field=>parameterType(field.tpt) match {
                  case Some((k,optional)) => treeCopy.ValDef(field,field.mods,field.name,wrapper(k,optional),transform(field.rhs))
                  case None => field
                }})
                treeCopy.DefDef(ctor,ctor.mods,ctor.name,ctor.tparams,lists,ctor.tpt,ctor.rhs)
              case field: ValDef if field.mods.hasFlag(Flag.PARAMACCESSOR) && parameterType(field.tpt).nonEmpty =>
                val(k,optional)=parameterType(field.tpt).get
                treeCopy.ValDef(field,field.mods,field.name,wrapper(k,optional),transform(field.rhs))
              case field:ValDef if derivedExpressions.containsKey(field) && hostInteger(field.rhs).isEmpty=>
                treeCopy.ValDef(field,field.mods,field.name,coreType("ElabInt"),derivedExpressions.get(field).duplicate)
              case call:Apply if configurationRequirements.containsKey(call)=>
                Apply(Select(core("ElabControl"),TermName("requireCondition")),List(
                  configurationRequirements.get(call).duplicate) ++ call.args.drop(1).map(_.duplicate) ++ List(
                  Literal(Constant(Option(call.pos.source).map(_.file.name).getOrElse("configuration"))),
                  Literal(Constant(if(call.pos.isDefined)call.pos.line else 0))))
              case other=>other
            }
            super.transform(treeCopy.ClassDef(cls,cls.mods,cls.name,cls.tparams,
              treeCopy.Template(cls.impl,cls.impl.parents,cls.impl.self,body)))
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
            val classOwner=schemas.values.find(_.definition eq cls).map(_.owner.init).getOrElse(Nil)
            val configurations=params.flatMap(p=>configuration(p.tpt,classOwner)
              .filter(schema=>configClasses(schema.definition)).map(p -> _))
            val configActuals=scala.collection.mutable.LinkedHashMap.empty[TermName,Tree]
            val configPaths=scala.collection.mutable.LinkedHashMap.empty[List[String],TermName]
            val configDerivedPaths=scala.collection.mutable.LinkedHashMap.empty[List[String],TermName]
            val specializationPaths=scala.collection.mutable.ArrayBuffer.empty[List[String]]
            val origins=scala.collection.mutable.LinkedHashMap.empty[TermName,String]
            val configParameters=scala.collection.mutable.ArrayBuffer.empty[ValDef]
            val configConstraints=scala.collection.mutable.ArrayBuffer.empty[Tree]
            def selectPath(names:List[String]):Tree=names.tail.foldLeft[Tree](Ident(TermName(names.head))) {
              case (base,name)=>Select(base,TermName(name))
            }
            def collect(schema:ConfigSchema,prefix:List[String],seen:Set[List[String]]):Unit={
              if(seen(schema.owner))return
              schema.definition.impl.body.collect {
                case field:ValDef if derivedExpressions.containsKey(field) && hostInteger(field.rhs).isEmpty=>field
              }.foreach { field=>
                configDerivedPaths(prefix :+ field.name.decodedName.toString)=TermName(unit.fresh.newName("derived_" + (prefix :+ field.name.decodedName.toString).mkString("_") + "_"))
              }
              val roots=schema.fields.map(_.name).toSet
              schema.definition.impl.body.foreach { call=>
                if(configurationRequirements.containsKey(call)) {
                  object QualifyConstraint extends Transformer {
                    override def transform(t:Tree):Tree=t match {
                      case read @ Select(a,_) if read.attachments.get[ConfigurationValueAccess].nonEmpty=>transform(a)
                      case Apply(Select(factory,n),List(value:Literal)) if path(factory)==List("_root_","spinal","core","ElabInt") && n.decodedName.toString=="literal"=>value
                      case Ident(n) if roots(n.toTermName)=>Select(selectPath(prefix),n)
                      case _=>super.transform(t)
                    }
                  }
                  val condition=QualifyConstraint.transform(configurationRequirements.get(call).duplicate)
                  val requireFun=Select(Select(Select(Ident(termNames.ROOTPKG),TermName("scala")),TermName("Predef")),TermName("require"))
                  val requirement=Apply(requireFun,List(condition) ++ call.asInstanceOf[Apply].args.drop(1).map(_.duplicate)).setPos(call.pos)
                  copiedRequires.put(requirement,true);configConstraints+=requirement
                }
              }
              schema.fields.foreach { field=>
                val location=prefix :+ field.name.decodedName.toString
                parameterType(field.tpt) match {
                  case Some(_) =>
                    val name=TermName(unit.fresh.newName("config_parameter_"))
                    val parameter=ValDef(field.mods,name,field.tpt,EmptyTree).setPos(field.pos)
                    configParameters += parameter
                    configActuals(name)=Select(selectPath(location),TermName("value"))
                    configPaths(location)=name
                    origins(name)=location.mkString(".")
                  case None=>configuration(field.tpt,schema.owner.init).filter(s=>configClasses(s.definition)) match {
                    case Some(child)=>collect(child,location,seen+schema.owner)
                    case None=>specializationPaths+=location
                  }
                }
              }
            }
            configurations.foreach { case (parameter,schema)=>collect(schema,List(parameter.name.decodedName.toString),Set.empty) }
            val directOwned=params.filter(p=>parameterType(p.tpt).nonEmpty && !forwarded(p.name))
            val owned=directOwned ++ configParameters.toList
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
              val ownedByName=directOwned.map(p=>p.name->p).toMap
              val configAliases=configurations.map { case(p,_)=>p.name -> TermName(unit.fresh.newName("owned_config_")) }.toMap
              object Rename extends Transformer {
                private var hidden=Set.empty[Name]
                private var untouched:Tree=EmptyTree
                private var nestedOwners=Set.empty[String]
                private def ownerScope[A](name:Name)(body: => A):A={
                  val saved=nestedOwners;nestedOwners+=name.decodedName.toString
                  try body finally nestedOwners=saved
                }
                private def scoped[A](names:Set[Name])(body: => A):A={val saved=hidden;hidden++=names;try body finally hidden=saved}
                private def configurationPath(tree:Tree):List[String]=tree match {
                  case Ident(n)=>List(n.decodedName.toString)
                  case Select(th:This,n) if (th.qual==global.typeNames.EMPTY && nestedOwners.isEmpty) ||
                    (th.qual==cls.name && !nestedOwners(th.qual.decodedName.toString))=>List(n.decodedName.toString)
                  case Select(_:This,_)=>Nil
                  case Select(q,n)=>val prefix=configurationPath(q);if(prefix.nonEmpty)prefix :+ n.decodedName.toString else Nil
                  case _=>Nil
                }
                override def transform(tree:Tree):Tree={
                  val result=tree match {
                    case same if same eq untouched=>same
                    case access if configDerivedPaths.contains(configurationPath(access)) && !hidden(TermName(configurationPath(access).head))=>
                      Ident(configDerivedPaths(configurationPath(access))).setPos(access.pos)
                    case access if configPaths.contains(configurationPath(access)) && !hidden(TermName(configurationPath(access).head)) =>
                      Ident(mapping(configPaths(configurationPath(access)))).setPos(access.pos)
                    case Ident(n) if configAliases.contains(n.toTermName) && !hidden(n)=>Ident(configAliases(n.toTermName)).setPos(tree.pos)
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
                  if(builtinRequire(tree) || copiedRequires.containsKey(tree))copiedRequires.put(result,true)
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
                val hdlName=overrides.headOption.getOrElse(Literal(Constant(ParameterHdlNames.inferred(origins.getOrElse(p.name,p.name.decodedName.toString)))))
                val actual=configActuals.getOrElse(p.name,Select(Ident(p.name),TermName("value")))
                val rhs=if(optional)Apply(Select(factory,TermName("optional")),List(actual,Literal(Constant(0)),hdlName))
                  else Apply(factory,List(actual,hdlName))
                val declaration = ValDef(Modifiers(Flag.PRIVATE),mapping(p.name),scalarType,rhs).setPos(p.pos)
                parameterOrigins.put(declaration, origins.getOrElse(p.name,p.name.decodedName.toString))
                configPaths.find(_._2==p.name).foreach { case (location,_)=>
                  val conversion=if(optional)"optional" else if(k=="Int")"integer" else if(k=="Boolean")"boolean" else "packed"
                  configurationOrigins.put(declaration,ConfigurationFormalPath(location.head,location.tail,conversion,
                    specializationPaths.filter(_.head==location.head).map(_.tail).toList))
                }
                declaration
              }
              def rebuild(schema:ConfigSchema,tpt:Tree,prefix:List[String],seen:Set[List[String]]):Tree={
                if(seen(schema.owner))return selectPath(prefix)
                val arguments=schema.fields.map { field=>
                  val location=prefix :+ field.name.decodedName.toString
                  configPaths.get(location) match {
                    case Some(name) if parameterType(field.tpt).exists(_._2)=>
                      Apply(Select(core("Param"),TermName("forwardOptional")),List(Ident(mapping(name))))
                    case Some(name)=>Ident(mapping(name))
                    case None=>configuration(field.tpt,schema.owner.init).filter(s=>configClasses(s.definition)) match {
                      case Some(child)=>rebuild(child,field.tpt,location,seen+schema.owner)
                      case None=>selectPath(location)
                    }
                  }
                }
                Apply(Select(New(tpt),termNames.CONSTRUCTOR),arguments)
              }
              val rebound=configurations.map { case (parameter,schema)=>
                ValDef(Modifiers(Flag.PRIVATE),configAliases(parameter.name),parameter.tpt,
                  rebuild(schema,parameter.tpt,List(parameter.name.decodedName.toString),Set.empty))
              }
              val derivedAliases=configDerivedPaths.toList.map { case (location,name)=>
                val value=selectPath(configAliases(TermName(location.head)).decodedName.toString :: location.tail)
                ValDef(Modifiers(Flag.PRIVATE),name,coreType("ElabInt"),value)
              }
              val body=(cls.impl.body ++ configConstraints.toList).flatMap {
                case ctor:DefDef if ctor.name==termNames.CONSTRUCTOR=>
                  val lists=ctor.vparamss.map(_.map { p=>ownedByName.get(p.name) match {
                    case Some(source)=>val(k,opt)=parameterType(source.tpt).get
                      treeCopy.ValDef(p,p.mods,p.name,wrapper(k,opt),p.rhs)
                    case None=>p
                  }})
                  List(treeCopy.DefDef(ctor,ctor.mods,ctor.name,ctor.tparams,lists,ctor.tpt,ctor.rhs))++declarations++rebound++derivedAliases
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
            val names = scala.collection.mutable.LinkedHashMap.empty[String, String]
            declarations.foreach { declaration =>
              val supplied = arguments(declaration.rhs).get
              val optional = declaration.rhs match {
                case Apply(Select(_, n), _) => n.decodedName.toString == "optional"
                case _ => false
              }
              val explicit = if (optional) supplied.drop(2).headOption else supplied.drop(1).headOption
              val sourcePath = Option(parameterOrigins.get(declaration)).getOrElse(declaration.name.decodedName.toString)
              val published = explicit match {
                case Some(Literal(Constant(name: String))) => Some(name)
                case None => Some(ParameterHdlNames.inferred(sourcePath))
                case _ => None // Runtime validation remains authoritative for nonliteral explicit names.
              }
              published.foreach { name =>
                names.get(name).foreach { previous =>
                  reporter.error(declaration.pos, s"MORPHDL-PARAM-NAME-COLLISION: '$previous' and '$sourcePath' both declare HDL parameter '$name'; use distinct explicit names")
                }
                names(name) = sourcePath
              }
            }
            bounds=declarations.map(v=>v.name.decodedName.toString ->
              (if(kind(v.tpt).contains("SInt")) BigInt(Int.MinValue) else BigInt(0),BigInt(Int.MaxValue)+1)).toMap
            // Abstract intervals, not value enumeration. Unknown endpoints stay
            // outside Int until an authored bound proves them. OR joins branch
            // envelopes; the original predicate remains an emitted legality guard.
            def interval(t:Tree):Option[(BigInt,BigInt)]=number(t).map(n=>(n,n)).orElse(t match {
              case Ident(n)=>bounds.get(n.decodedName.toString).filter {case(l,h)=>l<=h && l.isValidInt && h.isValidInt}
              case Select(a,n) if n.decodedName.toString=="pow2"=>interval(a).filter {case(l,h)=>l>=0 && h<=30}.map {case(l,h)=>(BigInt(1)<<l.toInt,BigInt(1)<<h.toInt)}
              case Apply(Select(a,n),Nil) if n.decodedName.toString=="pow2"=>interval(Select(a,n))
              case Apply(Select(a,n),List(b))=>for(x<-interval(a);y<-interval(b);r<-n.decodedName.toString match {
                case "+"=>Some((x._1+y._1,x._2+y._2))
                case "-"=>Some((x._1-y._2,x._2-y._1))
                case "*"=>val v=Seq(x._1*y._1,x._1*y._2,x._2*y._1,x._2*y._2);Some((v.min,v.max))
                case "/" if y._1>0 || y._2<0=>val v=Seq(x._1/y._1,x._1/y._2,x._2/y._1,x._2/y._2);Some((v.min,v.max))
                case _=>None
              })yield r
              case _=>None
            })
            def constrain(condition:Tree):Unit=condition match {
              case Apply(Select(a,n),List(b)) if n.decodedName.toString=="&&" =>constrain(a);constrain(b)
              case Apply(Select(a,n),List(b)) if n.decodedName.toString=="||" =>
                val before=bounds;val beforeDynamic=dynamicBounds
                constrain(a);val left=bounds
                bounds=before;dynamicBounds=beforeDynamic
                constrain(b);val right=bounds
                bounds=before.map {case(k,_)=>k -> (left(k)._1.min(right(k)._1),left(k)._2.max(right(k)._2))}
                // Runtime constructor bounds may not escape just one OR arm.
                dynamicBounds=beforeDynamic
              case Apply(Select(Ident(name),op),List(rhs)) if bounds.contains(name.decodedName.toString) && interval(rhs).nonEmpty =>
                val key=name.decodedName.toString;val (lo,hi)=bounds(key);val (low,high)=interval(rhs).get
                bounds += key -> (op.decodedName.toString match {
                  case ">=" => (lo.max(low),hi);case ">" => (lo.max(low+1),hi)
                  case "<=" => (lo,hi.min(high));case "<" => (lo,hi.min(high-1))
                  case "==" | "elabEq" => (lo.max(low),hi.min(high));case _ => (lo,hi)
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
                  case "==" | "elabEq" => (lower :+ bound,upper :+ bound)
                  case _ => (lower,upper)
                })
              case _ =>
            }
            val requirements=template.body.collect {
              case call @ Apply(fun,args) if path(fun).lastOption.contains("require") && (builtinRequire(call) || copiedRequires.containsKey(call)) && args.nonEmpty =>args.head
            }
            // A bounded number of monotone passes resolves dependency chains,
            // independent of declaration order and domain cardinality.
            if(declarations.nonEmpty) for(_ <- 0 to declarations.size) requirements.foreach(constrain)
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
              val name=if(args.size==2) args(1) else Literal(Constant(ParameterHdlNames.inferred(value.name.decodedName.toString)))
              val actual=transform(args.head)
              val rhs=k match {
                case "Boolean" => Apply(Select(core("RtlParam"),TermName("bool")),List(name,actual))
                case "Int" =>
                  val (lo,hi)=bounds.getOrElse(value.name.decodedName.toString,(BigInt(0),BigInt(Int.MaxValue)))
                  if(lo>hi || !lo.isValidInt || (!hi.isValidInt && !dynamicBounds.contains(value.name.decodedName.toString))) {
                    reporter.error(value.pos,"MORPHDL-PARAM-DOMAIN-UNPROVEN: provide unconditional require constraints proving a finite Int domain; the default is not a domain bound")
                    value.rhs
                  } else {
                    val parameters=List(name,actual) ++ boundArguments(value.name.decodedName.toString,lo,hi.min(BigInt(Int.MaxValue)))
                    Apply(Select(core("RtlParam"),TermName(if(optional)"optional" else "inferredInt")),
                      if(optional) parameters :+ transform(supplied(1)) else parameters)
                  }
                case "UInt" | "Bits" | "SInt" =>
                  val (lo,hi)=bounds.getOrElse(value.name.decodedName.toString,
                    (if(k=="SInt")BigInt(Int.MinValue) else BigInt(0),BigInt(Int.MaxValue)))
                  Apply(Select(core("RtlParam"),TermName("packed")),List(name,actual) ++ boundArguments(value.name.decodedName.toString,lo,hi.min(BigInt(Int.MaxValue))) :+ Literal(Constant(k)))
                case _ =>
                  reporter.error(value.pos,s"MORPHDL-PARAM-TYPE-UNSUPPORTED: Param[$k] declaration is not supported")
                  value.rhs
              }
              val rewritten=treeCopy.ValDef(value,value.mods,value.name,coreType(if(k=="Boolean")"ElabBool" else "ElabInt"),rhs).setPos(value.pos)
              Option(configurationOrigins.get(value)).foreach(rewritten.updateAttachment(_))
              rewritten
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
