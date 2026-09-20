package morphhdl.compiler

import java.util.IdentityHashMap

import scala.collection.mutable
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent
import scala.util.control.NonFatal

/**
  * Pre-typer syntax bridge for neutral `spinal.core.ElabInt` / `ElabBool`
  * control flow.
  *
  * The carrier type already owns symbolic provenance. This phase performs
  * syntax lowering only; it never discovers or reconstructs symbolism from a
  * plain Scala `Int` or `Boolean`.
  */
final class MorphHdlTypedElaborationControlComponent(val global: Global)
    extends PluginComponent {
  import global._

  override val phaseName: String = "morphhdl-typed-elaboration-control"
  override val runsAfter: List[String] = List("parser")
  override val runsBefore: List[String] =
    List("morphhdl-natural-symbolic-conditionals", "namer")

  private sealed trait BindingKind
  private case object TypedIntegerBinding extends BindingKind
  private case object TypedBooleanBinding extends BindingKind
  private case object ScalaIntegerBinding extends BindingKind
  private case object OrdinaryBinding extends BindingKind

  private final case class ClassifiedTrees(
      typedIfs: IdentityHashMap[Tree, java.lang.Boolean],
      typedGenerates: IdentityHashMap[Tree, java.lang.Boolean],
      typedRequires: IdentityHashMap[Tree, java.lang.Boolean],
      typedEqualities: IdentityHashMap[Tree, java.lang.Boolean]
  )

  private final case class WildcardMembers(
      typeNames: Set[String],
      termNames: Set[String]
  )

  private def decoded(name: Name): String = name.decodedName.toString

  private def syntaxPath(tree: Tree): Option[Vector[String]] = tree match {
    case Ident(name) => Some(Vector(decoded(name)))
    case Select(qualifier, name) =>
      syntaxPath(qualifier).map(_ :+ decoded(name))
    case _ => None
  }

  private def normalizedPath(tree: Tree): Option[Vector[String]] =
    syntaxPath(tree).map(
      _.filterNot(name =>
        name == "_root_" || name == "<root>" || name == "<empty>"
      )
    )

  private def rootedPath(tree: Tree): Boolean =
    syntaxPath(tree).exists(_.headOption.exists(name => name == "_root_" || name == "<root>"))

  private var wildcardProviderRun: AnyRef = null
  private var wildcardProviders = Set.empty[Vector[String]]
  private var wildcardStableTerms = Set.empty[Vector[String]]
  private var opaqueWildcardPackageObjects = Set.empty[Vector[String]]

  /**
    * Do not trust a stale or partially entered classpath symbol for a wildcard
    * provider which is also declared by this compiler run. The parser has
    * completed for every unit before this phase runs, so source package and
    * module ownership can be collected once per run without naming trees.
    */
  private def currentRunWildcardProviders: Set[Vector[String]] =
    this.synchronized {
      val run = currentRun.asInstanceOf[AnyRef]
      if (wildcardProviderRun ne run) {
        val providers = mutable.LinkedHashSet.empty[Vector[String]]
        val stableTerms = mutable.LinkedHashSet.empty[Vector[String]]
        val opaquePackageObjects =
          mutable.LinkedHashSet.empty[Vector[String]]

        object SourceProviders extends Traverser {
          private var owner = Vector.empty[String]

          override def traverse(current: Tree): Unit = current match {
            case definition: PackageDef =>
              val previous = owner
              val declared = normalizedPath(definition.pid)
                .getOrElse(Vector.empty)
                .flatMap(_.split("\\.").filter(_.length != 0))
              owner =
                if (rootedPath(definition.pid)) declared else previous ++ declared
              if (owner.nonEmpty) providers += owner
              try definition.stats.foreach(traverse)
              finally owner = previous

            case definition: ModuleDef
                if decoded(definition.name) == "package" =>
              // Package-object members participate in unrooted qualifier
              // lookup from the enclosing package. Record direct stable-name
              // candidates syntactically; custom parents remain opaque until
              // namer and therefore force conservative classification.
              definition.impl.body.foreach {
                case member: ValDef =>
                  val path = owner :+ decoded(member.name)
                  providers += path
                  stableTerms += path
                case member: DefDef =>
                  providers += owner :+ decoded(member.name)
                case member: ModuleDef =>
                  val path = owner :+ decoded(member.name)
                  providers += path
                  stableTerms += path
                case member: ClassDef =>
                  // A source-level companion may be synthesized later.
                  val path = owner :+ decoded(member.name)
                  providers += path
                  stableTerms += path
                case _                 =>
              }
              val hasOpaqueParent = definition.impl.parents.exists { parent =>
                normalizedPath(parent) match {
                  case Some(Vector("scala", "AnyRef"))       => false
                  case Some(Vector("java", "lang", "Object")) => false
                  case _                                        => true
                }
              }
              if (hasOpaqueParent)
                opaquePackageObjects += owner
              traverse(definition.impl)

            case definition: ModuleDef =>
              val previous = owner
              owner = previous :+ decoded(definition.name)
              providers += owner
              stableTerms += owner
              definition.impl.body.foreach {
                case member: ValDef =>
                  stableTerms += owner :+ decoded(member.name)
                case _ =>
              }
              try traverse(definition.impl)
              finally owner = previous

            case definition: ClassDef =>
              val previous = owner
              owner = previous :+ decoded(definition.name)
              try traverse(definition.impl)
              finally owner = previous

            case _ => super.traverse(current)
          }
        }

        currentRun.units.foreach(unit => SourceProviders.traverse(unit.body))
        wildcardProviders = providers.toSet
        wildcardStableTerms = stableTerms.toSet
        opaqueWildcardPackageObjects = opaquePackageObjects.toSet
        wildcardProviderRun = run
      }
      wildcardProviders
    }

  private def currentRunOpaquePackageObjects: Set[Vector[String]] = {
    currentRunWildcardProviders
    this.synchronized(opaqueWildcardPackageObjects)
  }

  private def currentRunWildcardStableTerms: Set[Vector[String]] = {
    currentRunWildcardProviders
    this.synchronized(wildcardStableTerms)
  }

  /**
    * Classify typed control flow with lexical bindings before rewriting it.
    * Every declaration is recorded, including ordinary declarations, so an
    * inner `Int`/`Boolean` reliably shadows a same-named typed outer binding.
    */
  private def classify(tree: Tree): ClassifiedTrees = {
    val typedIfs = new IdentityHashMap[Tree, java.lang.Boolean]()
    val typedGenerates = new IdentityHashMap[Tree, java.lang.Boolean]()
    val typedRequires = new IdentityHashMap[Tree, java.lang.Boolean]()
    val typedEqualities = new IdentityHashMap[Tree, java.lang.Boolean]()

    final class LexicalScope {
      val terms = mutable.LinkedHashMap.empty[TermName, BindingKind]
      val localTypes = mutable.LinkedHashMap.empty[TypeName, BindingKind]
      val explicitTypes =
        mutable.LinkedHashMap.empty[TypeName, mutable.LinkedHashSet[BindingKind]]
      val wildcardTypes =
        mutable.LinkedHashMap.empty[TypeName, mutable.LinkedHashSet[BindingKind]]
      val importedCalls =
        mutable.LinkedHashMap.empty[TermName, mutable.LinkedHashSet[Boolean]]
      val importedTerms = mutable.LinkedHashSet.empty[TermName]
    }

    var scopes = List(new LexicalScope)
    var currentPackage = Vector.empty[String]
    val sourceWildcardProviders = currentRunWildcardProviders
    val sourceWildcardStableTerms = currentRunWildcardStableTerms
    val sourceOpaquePackageObjects = currentRunOpaquePackageObjects

    def lookup(name: TermName): Option[BindingKind] =
      scopes.collectFirst {
        case scope if scope.terms.contains(name) => scope.terms(name)
      }

    def hasVisibleTerm(name: TermName): Boolean =
      scopes.exists(scope => scope.terms.contains(name) || scope.importedTerms(name))

    def withScope[A](body: => A): A = {
      scopes = new LexicalScope :: scopes
      try body
      finally scopes = scopes.tail
    }

    def merged(candidates: mutable.LinkedHashSet[BindingKind]): BindingKind =
      if (candidates.size == 1) candidates.head else OrdinaryBinding

    def lookupType(name: TypeName): BindingKind = {
      val iterator = scopes.iterator
      while (iterator.hasNext) {
        val scope = iterator.next()
        scope.localTypes.get(name) match {
          case Some(kind) => return kind
          case None       =>
        }
        scope.explicitTypes.get(name) match {
          case Some(candidates) if candidates.nonEmpty => return merged(candidates)
          case _                                       =>
        }
        scope.wildcardTypes.get(name) match {
          case Some(candidates) if candidates.nonEmpty => return merged(candidates)
          case _                                       =>
        }
      }
      decoded(name) match {
        case "Int" | "Byte" | "Short" | "Char" => ScalaIntegerBinding
        case "ElabInt" if currentPackage == Vector("spinal", "core") =>
          TypedIntegerBinding
        case "ElabBool" if currentPackage == Vector("spinal", "core") =>
          TypedBooleanBinding
        case _                                      => OrdinaryBinding
      }
    }

    def providerInfos(provider: Symbol): Vector[Type] = {
      val primary =
        if (provider.isModule) provider.moduleClass.info else provider.info
      val packageObject = primary.packageObject
      if (packageObject != NoSymbol)
        Vector(primary, packageObject.moduleClass.info)
      else Vector(primary)
    }

    def sameRunOwnsTarget(candidate: Vector[String]): Boolean =
      sourceWildcardProviders(candidate) ||
        sourceWildcardStableTerms.exists(term => candidate.startsWith(term))

    def sameRunOwnsRelativeRoot(
        enclosing: Vector[String],
        name: String
    ): Boolean = {
      val candidate = enclosing :+ name
      sourceOpaquePackageObjects(enclosing) ||
      sourceWildcardProviders.exists(_.startsWith(candidate))
    }

    def classpathOwnsRelativeRoot(
        enclosing: Vector[String],
        name: String
    ): Boolean = {
      val direct = rootMirror.getModuleIfDefined((enclosing :+ name).mkString("."))
      if (direct != NoSymbol) true
      else {
        val owner = rootMirror.getModuleIfDefined(enclosing.mkString("."))
        if (owner == NoSymbol || !owner.isModule) false
        else {
          val packageObject = owner.moduleClass.info.packageObject
          packageObject != NoSymbol &&
          packageObject.moduleClass.info.nonPrivateMember(TermName(name)) != NoSymbol
        }
      }
    }

    def enclosingPackageShadowsRoot(name: String): Boolean =
      try {
        currentPackage.inits
          .filter(_.nonEmpty)
          .exists { enclosing =>
            sameRunOwnsRelativeRoot(enclosing, name) ||
            classpathOwnsRelativeRoot(enclosing, name)
          }
      } catch {
        // Completion failures cannot establish canonical root ownership.
        case NonFatal(_) => true
      }

    def unshadowedRoot(name: String, tree: Tree): Boolean =
      rootedPath(tree) ||
        (!hasVisibleTerm(TermName(name)) &&
          !enclosingPackageShadowsRoot(name))

    def qualifiedTypeKind(tree: Tree): BindingKind =
      normalizedPath(tree) match {
        case Some(Vector("spinal", "core", "ElabInt"))
            if unshadowedRoot("spinal", tree) =>
          TypedIntegerBinding
        case Some(Vector("spinal", "core", "ElabBool"))
            if unshadowedRoot("spinal", tree) =>
          TypedBooleanBinding
        case Some(Vector("scala", "Int" | "Byte" | "Short" | "Char"))
            if unshadowedRoot("scala", tree) =>
          ScalaIntegerBinding
        case _ => OrdinaryBinding
      }

    def typeKind(tpt: Tree): BindingKind = tpt match {
      case tree if tree == null || tree.isEmpty => OrdinaryBinding
      case Ident(name: TypeName)                => lookupType(name)
      case AppliedTypeTree(constructor, _)      => typeKind(constructor)
      case selected: Select                    => qualifiedTypeKind(selected)
      case _ => OrdinaryBinding
    }

    def addCandidate(
        values: mutable.LinkedHashMap[TypeName, mutable.LinkedHashSet[BindingKind]],
        name: TypeName,
        kind: BindingKind
    ): Unit =
      values.getOrElseUpdate(name, mutable.LinkedHashSet.empty) += kind

    def addImportedCall(name: TermName, canonicalPredef: Boolean): Unit =
      scopes.head.importedCalls
        .getOrElseUpdate(name, mutable.LinkedHashSet.empty) += canonicalPredef

    val predefControlNames = Set("require", "assert")
    val trackedWildcardTypes = Set("ElabInt", "ElabBool")
    val trackedWildcardTerms =
      predefControlNames ++ Set("spinal", "scala", "Predef")

    def selectorName(name: Name): String =
      if (name == null) "" else decoded(name)

    def externalWildcardProvider(
        expr: Tree,
        path: Vector[String]
    ): Option[Symbol] =
      try {
        if (rootedPath(expr)) {
          if (sameRunOwnsTarget(path)) None
          else Option(rootMirror.getModuleIfDefined(path.mkString(".")))
            .filter(_ != NoSymbol)
        } else if (hasVisibleTerm(TermName(path.head))) None
        else {
          val enclosingIterator = currentPackage.inits.filter(_.nonEmpty)
          while (enclosingIterator.hasNext) {
            val enclosing = enclosingIterator.next()
            val candidate = enclosing ++ path
            if (
              sameRunOwnsRelativeRoot(enclosing, path.head) ||
              sameRunOwnsTarget(candidate)
            ) return None

            val relative =
              rootMirror.getModuleIfDefined(candidate.mkString("."))
            if (relative != NoSymbol) return Some(relative)
            if (classpathOwnsRelativeRoot(enclosing, path.head)) return None
          }

          if (sameRunOwnsTarget(path)) None
          else Option(rootMirror.getModuleIfDefined(path.mkString(".")))
            .filter(_ != NoSymbol)
        }
      } catch {
        case NonFatal(_) => None
      }

    /**
      * Resolve only classpath-backed wildcard providers.  If the provider is
      * absent, shadowed, declared by this run, or cannot be inspected safely,
      * return None so the caller keeps the fail-closed ambiguity behavior.
      */
    def resolvedExternalWildcardMembers(expr: Tree): Option[WildcardMembers] =
      normalizedPath(expr).filter(_.nonEmpty).flatMap { path =>
        externalWildcardProvider(expr, path).flatMap { provider =>
          try {
            val infos = providerInfos(provider)
            val types = trackedWildcardTypes.filter { name =>
              infos.exists(_.nonPrivateMember(TypeName(name)) != NoSymbol)
            }
            val terms = trackedWildcardTerms.filter { name =>
              infos.exists(_.nonPrivateMember(TermName(name)) != NoSymbol)
            }
            Some(WildcardMembers(types, terms))
          } catch {
            case NonFatal(_) => None
          }
        }
      }

    def recordImport(value: Import): Unit = {
      val prefix = normalizedPath(value.expr).getOrElse(Vector.empty)
      val canonicalCorePrefix =
        prefix == Vector("spinal", "core") && unshadowedRoot("spinal", value.expr)
      val canonicalPredefPrefix =
        prefix == Vector("scala", "Predef") && unshadowedRoot("scala", value.expr)
      val selectors = value.selectors.toVector
      val excluded = selectors.collect {
        case selector if selectorName(selector.rename) == "_" =>
          selectorName(selector.name)
      }.toSet

      selectors.foreach { selector =>
        val imported = selectorName(selector.name)
        val renamed = selectorName(selector.rename)
        val wildcard = imported == "_"
        val hidden = renamed == "_"
        val exposed =
          if (renamed.length != 0 && renamed != imported) renamed else imported

        if (wildcard) {
          val resolvedMembers =
            if (canonicalCorePrefix || canonicalPredefPrefix) None
            else resolvedExternalWildcardMembers(value.expr)

          if (canonicalCorePrefix) {
            if (!excluded("ElabInt"))
              addCandidate(
                scopes.head.wildcardTypes,
                TypeName("ElabInt"),
                TypedIntegerBinding
              )
            if (!excluded("ElabBool"))
              addCandidate(
                scopes.head.wildcardTypes,
                TypeName("ElabBool"),
                TypedBooleanBinding
              )
          } else if (!canonicalPredefPrefix) {
            // Known external providers contribute only members that they
            // actually export. Unknown and current-run providers remain
            // conservative so an outer spinal.core import is never borrowed
            // through a possible source-local collision.
            val possibleTypes = resolvedMembers
              .map(_.typeNames)
              .getOrElse(trackedWildcardTypes)
            val possibleTerms = resolvedMembers
              .map(_.termNames)
              .getOrElse(trackedWildcardTerms)
            if (possibleTypes("ElabInt") && !excluded("ElabInt"))
              addCandidate(
                scopes.head.wildcardTypes,
                TypeName("ElabInt"),
                OrdinaryBinding
              )
            if (possibleTypes("ElabBool") && !excluded("ElabBool"))
              addCandidate(
                scopes.head.wildcardTypes,
                TypeName("ElabBool"),
                OrdinaryBinding
              )
            Vector("spinal", "scala", "Predef").foreach { name =>
              if (possibleTerms(name) && !excluded(name))
                scopes.head.importedTerms += TermName(name)
            }
          }
          predefControlNames.foreach { name =>
            if (canonicalPredefPrefix) {
              if (!excluded(name))
                addImportedCall(TermName(name), canonicalPredef = true)
            } else if (
              !excluded(name) &&
              (!canonicalCorePrefix || name == "assert") &&
              (canonicalCorePrefix ||
                resolvedMembers.forall(_.termNames(name)))
            )
              // At this pre-namer phase the members of an arbitrary wildcard
              // import are unknown. It may provide its own control helper, so
              // do not silently treat an unqualified call as a Predef call.
              // spinal.core is known not to provide `require`, but its
              // hardware/host overloads do own `assert`.
              addImportedCall(TermName(name), canonicalPredef = false)
          }
        } else if (!hidden && exposed.length != 0) {
          scopes.head.importedTerms += TermName(exposed)
          val importedKind =
            if (canonicalCorePrefix)
              imported match {
                case "ElabInt"  => TypedIntegerBinding
                case "ElabBool" => TypedBooleanBinding
                case _          => OrdinaryBinding
              }
            else OrdinaryBinding
          addCandidate(
            scopes.head.explicitTypes,
            TypeName(exposed),
            importedKind
          )
          if (predefControlNames(imported) || predefControlNames(exposed))
            addImportedCall(
              TermName(exposed),
              canonicalPredef =
                canonicalPredefPrefix && predefControlNames(imported)
            )
        }
      }
    }

    /**
      * Infer only the carrier expressions which can be proven from the
      * untyped syntax tree and explicitly typed lexical bindings.  In
      * particular, do not propagate carrier meaning through an arbitrary
      * method call or member selection merely because its subtree mentions an
      * `ElabInt`/`ElabBool`: `width.parameters.size`,
      * `width.parameters.nonEmpty` and `Seq(width).nonEmpty` are ordinary
      * Scala values.
      */
    def expressionKind(current: Tree): BindingKind = current match {
      case Ident(name: TermName) => lookup(name).getOrElse(OrdinaryBinding)
      case Select(This(_), name: TermName) =>
        lookup(name).getOrElse(OrdinaryBinding)
      case Literal(Constant(_: Byte))  => ScalaIntegerBinding
      case Literal(Constant(_: Short)) => ScalaIntegerBinding
      case Literal(Constant(_: Char))  => ScalaIntegerBinding
      case Literal(Constant(_: Int))   => ScalaIntegerBinding

      case Apply(Select(receiver, operator), List(argument)) =>
        val receiverKind = expressionKind(receiver)
        val argumentKind = expressionKind(argument)
        val receiverIsInteger =
          receiverKind == TypedIntegerBinding || receiverKind == ScalaIntegerBinding
        val argumentIsInteger =
          argumentKind == TypedIntegerBinding || argumentKind == ScalaIntegerBinding
        decoded(operator) match {
          case "+" | "-" | "*" | "/" | "%"
              if receiverKind == TypedIntegerBinding && argumentIsInteger =>
            TypedIntegerBinding
          case "+" | "-" | "*" | "/" | "%"
              if receiverKind == ScalaIntegerBinding &&
                argumentKind == ScalaIntegerBinding =>
            ScalaIntegerBinding
          case "<" | "<=" | ">" | ">="
              if receiverKind == TypedIntegerBinding && argumentIsInteger =>
            TypedBooleanBinding
          case "elabEq" | "elabNe"
              if receiverKind == TypedIntegerBinding && argumentIsInteger =>
            TypedBooleanBinding
          case "==" | "!="
              if receiverIsInteger && argumentIsInteger &&
                (receiverKind == TypedIntegerBinding ||
                  argumentKind == TypedIntegerBinding) =>
            TypedBooleanBinding
          case "&&" | "||" if receiverKind == TypedBooleanBinding =>
            TypedBooleanBinding
          case _ => OrdinaryBinding
        }

      case Select(receiver, operator)
          if decoded(operator) == "unary_!" &&
            expressionKind(receiver) == TypedBooleanBinding =>
        TypedBooleanBinding
      case Apply(Select(receiver, operator), Nil)
          if decoded(operator) == "unary_!" &&
            expressionKind(receiver) == TypedBooleanBinding =>
        TypedBooleanBinding
      case Select(receiver, operator)
          if (decoded(operator) == "unary_+" || decoded(operator) == "unary_-") &&
            expressionKind(receiver) == ScalaIntegerBinding =>
        ScalaIntegerBinding
      case Apply(Select(receiver, operator), Nil)
          if (decoded(operator) == "unary_+" || decoded(operator) == "unary_-") &&
            expressionKind(receiver) == ScalaIntegerBinding =>
        ScalaIntegerBinding
      case _ => OrdinaryBinding
    }

    def bindingKind(value: ValDef): BindingKind =
      if (value.tpt == null || value.tpt.isEmpty) expressionKind(value.rhs)
      else typeKind(value.tpt)

    def bind(value: ValDef): Unit =
      scopes.head.terms.update(value.name, bindingKind(value))

    def bindOrdinary(name: TermName): Unit =
      scopes.head.terms.update(name, OrdinaryBinding)

    def bindTypeOrdinary(name: TypeName): Unit =
      scopes.head.localTypes.update(name, OrdinaryBinding)

    def prebindTypes(values: List[Tree]): Unit = {
      values.foreach {
        case definition: TypeDef  => bindTypeOrdinary(definition.name)
        case definition: ClassDef => bindTypeOrdinary(definition.name)
        case _                    =>
      }

      var changed = true
      var remaining = values.size + 1
      while (changed && remaining > 0) {
        changed = false
        values.foreach {
          case definition: TypeDef if definition.rhs != null && !definition.rhs.isEmpty =>
            val resolved = typeKind(definition.rhs)
            if (
              resolved != OrdinaryBinding &&
              scopes.head.localTypes.get(definition.name) != Some(resolved)
            ) {
              scopes.head.localTypes.update(definition.name, resolved)
              changed = true
            }
          case _ =>
        }
        remaining -= 1
      }
    }

    def prebindTerms(values: List[Tree]): Unit = {
      values.foreach {
        case definition: ValDef    => bindOrdinary(definition.name)
        case definition: DefDef    => bindOrdinary(definition.name)
        case definition: ModuleDef => bindOrdinary(definition.name)
        case _                     =>
      }

      var changed = true
      var remaining = values.size + 1
      while (changed && remaining > 0) {
        changed = false
        values.foreach {
          case definition: ValDef =>
            val resolved = bindingKind(definition)
            if (
              resolved != OrdinaryBinding &&
              scopes.head.terms.get(definition.name) != Some(resolved)
            ) {
              scopes.head.terms.update(definition.name, resolved)
              changed = true
            }
          case _ =>
        }
        remaining -= 1
      }
    }

    def prepareScope(values: List[Tree]): Unit = {
      prebindTypes(values)
      // Package/object qualifiers and Predef names can themselves be
      // shadowed by members visible throughout this scope. Record those names
      // before deciding whether an unrooted import is canonical.
      prebindTerms(values)
      values.foreach {
        case imported: Import => recordImport(imported)
        case _                =>
      }
      // Imports can make a type alias canonical, and aliases can in turn make
      // explicitly typed term bindings canonical. Resolve in that order.
      prebindTypes(values)
      prebindTerms(values)
    }

    def lookupImportedCall(name: TermName): Option[Boolean] = {
      val iterator = scopes.iterator
      while (iterator.hasNext) {
        val scope = iterator.next()
        if (scope.terms.contains(name)) return Some(false)
        scope.importedCalls.get(name) match {
          case Some(candidates) if candidates.size == 1 => return Some(candidates.head)
          case Some(candidates) if candidates.nonEmpty  => return Some(false)
          case _                                        =>
        }
      }
      None
    }

    def canonicalPredefQualifier(tree: Tree): Boolean =
      normalizedPath(tree) match {
        case Some(Vector("scala", "Predef")) =>
          unshadowedRoot("scala", tree)
        case Some(Vector("Predef")) =>
          !hasVisibleTerm(TermName("Predef")) &&
            !enclosingPackageShadowsRoot("Predef")
        case _ => false
      }

    def isPredefControl(fun: Tree): Boolean = fun match {
      case TypeApply(method, _) => isPredefControl(method)
      case Ident(name: TermName) =>
        lookupImportedCall(name).getOrElse {
          predefControlNames(decoded(name)) &&
          !enclosingPackageShadowsRoot(decoded(name)) &&
          !(decoded(name) == "assert" && currentPackage == Vector("spinal", "core"))
        }
      case Select(qualifier, name) if predefControlNames(decoded(name)) =>
        canonicalPredefQualifier(qualifier)
      case _ => false
    }

    def isTypedInteger(current: Tree): Boolean =
      expressionKind(current) == TypedIntegerBinding

    def isTypedBoolean(current: Tree): Boolean =
      expressionKind(current) == TypedBooleanBinding

    def patternNames(pattern: Tree): Vector[TermName] = {
      val names = mutable.ArrayBuffer.empty[TermName]
      object Finder extends Traverser {
        override def traverse(current: Tree): Unit = current match {
          case Bind(name: TermName, body) =>
            names += name
            super.traverse(body)
          case _ => super.traverse(current)
        }
      }
      Finder.traverse(pattern)
      names.toVector
    }

    def mark(
        values: IdentityHashMap[Tree, java.lang.Boolean],
        value: Tree
    ): Unit = values.put(value, java.lang.Boolean.TRUE)

    object Classifier extends Traverser {
      override def traverse(current: Tree): Unit = current match {
        case definition: PackageDef =>
          val previousPackage = currentPackage
          val declaredPackage = normalizedPath(definition.pid)
            .getOrElse(Vector.empty)
            .flatMap(_.split("\\.").filter(_.length != 0))
          currentPackage =
            if (rootedPath(definition.pid)) declaredPackage
            else previousPackage ++ declaredPackage
          try {
            withScope {
              prepareScope(definition.stats)
              if (currentPackage == Vector("spinal", "core")) {
                definition.stats.foreach {
                  case declared: ClassDef if decoded(declared.name) == "ElabInt" =>
                    scopes.head.localTypes.update(declared.name, TypedIntegerBinding)
                  case declared: ClassDef if decoded(declared.name) == "ElabBool" =>
                    scopes.head.localTypes.update(declared.name, TypedBooleanBinding)
                  case _ =>
                }
              }
              definition.stats.foreach(traverse)
            }
          } finally currentPackage = previousPackage

        case definition: ClassDef =>
          withScope {
            definition.tparams.foreach(parameter => bindTypeOrdinary(parameter.name))
            traverse(definition.impl)
          }

        case template: Template =>
          withScope {
            // Class/object members are visible throughout their template.
            // Recording every member also lets ordinary members shadow typed
            // constructor parameters or enclosing values deterministically.
            prepareScope(template.body)
            template.parents.foreach(traverse)
            traverse(template.self)
            template.body.foreach(traverse)
          }

        case definition: DefDef =>
          withScope {
            definition.tparams.foreach(parameter => bindTypeOrdinary(parameter.name))
            definition.vparamss.flatten.foreach(bind)
            definition.tparams.foreach(traverse)
            definition.vparamss.flatten.foreach { parameter =>
              traverse(parameter.tpt)
              traverse(parameter.rhs)
            }
            traverse(definition.tpt)
            traverse(definition.rhs)
          }

        case function: Function =>
          withScope {
            function.vparams.foreach(bind)
            function.vparams.foreach { parameter =>
              traverse(parameter.tpt)
              traverse(parameter.rhs)
            }
            traverse(function.body)
          }

        case block: Block =>
          withScope {
            prebindTypes(block.stats)
            block.stats.foreach {
              case definition: ValDef => bindOrdinary(definition.name)
              case definition: DefDef => bindOrdinary(definition.name)
              case definition: ModuleDef => bindOrdinary(definition.name)
              case _ =>
            }
            block.stats.foreach {
              case imported: Import => recordImport(imported)
              case _                =>
            }
            block.stats.foreach { statement =>
              traverse(statement)
              statement match {
                case value: ValDef => bind(value)
                case _             =>
              }
            }
            traverse(block.expr)
          }

        case imported: Import =>
          recordImport(imported)

        case value: ValDef =>
          traverse(value.tpt)
          traverse(value.rhs)

        case branch: CaseDef =>
          withScope {
            patternNames(branch.pat).foreach(bindOrdinary)
            traverse(branch.pat)
            traverse(branch.guard)
            traverse(branch.body)
          }

        case original: If =>
          if (isTypedBoolean(original.cond)) mark(typedIfs, original)
          super.traverse(original)

        case original @ Apply(Select(predicate, operator), List(_))
            if decoded(operator) == "generate" =>
          if (isTypedBoolean(predicate)) mark(typedGenerates, original)
          super.traverse(original)

        case original @ Apply(fun, predicate :: _) if isPredefControl(fun) =>
          if (isTypedBoolean(predicate)) mark(typedRequires, original)
          super.traverse(original)

        case original @ Apply(Select(left, operator), List(right))
            if decoded(operator) == "==" || decoded(operator) == "!=" =>
          val leftTyped = isTypedInteger(left)
          val rightTyped = isTypedInteger(right)
          if (
            (leftTyped || rightTyped) &&
            expressionKind(original) == TypedBooleanBinding
          )
            typedEqualities.put(
              original,
              java.lang.Boolean.valueOf(leftTyped)
            )
          super.traverse(original)

        case _ => super.traverse(current)
      }
    }

    Classifier.traverse(tree)
    ClassifiedTrees(
      typedIfs,
      typedGenerates,
      typedRequires,
      typedEqualities
    )
  }

  private def helperMethod(name: String): Tree = {
    val root = Ident(termNames.ROOTPKG)
    val spinal = Select(root, TermName("spinal"))
    val core = Select(spinal, TermName("core"))
    val helper = Select(core, TermName("ElabControl"))
    Select(helper, TermName(name))
  }

  private def scalaSeqApply: Tree = {
    val root = Ident(termNames.ROOTPKG)
    val scalaPkg = Select(root, TermName("scala"))
    val seq = Select(scalaPkg, TermName("Seq"))
    Select(seq, TermName("apply"))
  }

  private def tuple4Apply: Tree = {
    val root = Ident(termNames.ROOTPKG)
    val scalaPkg = Select(root, TermName("scala"))
    val tuple4 = Select(scalaPkg, TermName("Tuple4"))
    Select(tuple4, TermName("apply"))
  }

  private final class TypedControlTransformer(
      unit: CompilationUnit,
      classified: ClassifiedTrees
  ) extends Transformer {
    private def sourceFile: String =
      Option(unit.source)
        .flatMap(source => Option(source.file))
        .map(_.path)
        .filter(value => value.length != 0)
        .getOrElse("<typed-elaboration>")

    private def sourceLine(tree: Tree): Int =
      if (tree.pos != null && tree.pos.isDefined) math.max(1, tree.pos.line)
      else 1

    private def sourcePoint(tree: Tree): Int =
      if (tree.pos != null && tree.pos.isDefined) tree.pos.point else -1

    private def sourceEnd(tree: Tree): Int =
      if (tree.pos != null && tree.pos.isDefined) tree.pos.end else -1

    private final class ConditionTransformer extends Transformer {
      override def transform(tree: Tree): Tree = tree match {
        case original @ Apply(Select(left, operator), List(right))
            if classified.typedEqualities.containsKey(original) =>
          val leftIsTyped = classified.typedEqualities.get(original).booleanValue()
          val receiver = if (leftIsTyped) transform(left) else transform(right)
          val argument = if (leftIsTyped) transform(right) else transform(left)
          val method =
            if (decoded(operator) == "==") TermName("elabEq")
            else TermName("elabNe")
          val rewritten = Apply(Select(receiver, method), List(argument))
          rewritten.setPos(tree.pos)
        case _ => super.transform(tree)
      }
    }

    private def condition(tree: Tree): Tree =
      new ConditionTransformer().transform(tree)

    private def sourceTokens(from: Int, until: Int): Vector[String] = {
      val content = Option(unit.source).map(_.content).getOrElse(Array.empty[Char])
      val start = math.max(0, math.min(from, content.length))
      val end = math.max(start, math.min(until, content.length))
      val tokens = Vector.newBuilder[String]
      var index = start

      def has(offset: Int): Boolean = index + offset < end

      while (index < end) {
        val current = content(index)
        if (Character.isWhitespace(current)) {
          index += 1
        } else if (current == '/' && has(1) && content(index + 1) == '/') {
          index += 2
          while (index < end && content(index) != '\n' && content(index) != '\r') index += 1
        } else if (current == '/' && has(1) && content(index + 1) == '*') {
          index += 2
          var depth = 1
          while (index < end && depth > 0) {
            if (index + 1 < end && content(index) == '/' && content(index + 1) == '*') {
              depth += 1
              index += 2
            } else if (index + 1 < end && content(index) == '*' && content(index + 1) == '/') {
              depth -= 1
              index += 2
            } else index += 1
          }
        } else if (current == '"') {
          tokens += "<string>"
          if (index + 2 < end && content(index + 1) == '"' && content(index + 2) == '"') {
            index += 3
            while (
              index + 2 < end &&
              !(content(index) == '"' && content(index + 1) == '"' && content(index + 2) == '"')
            ) index += 1
            index = math.min(end, index + 3)
          } else {
            index += 1
            var escaped = false
            var closed = false
            while (index < end && !closed) {
              val value = content(index)
              if (escaped) escaped = false
              else if (value == '\\') escaped = true
              else if (value == '"') closed = true
              index += 1
            }
          }
        } else if (current == '\'') {
          tokens += "<char>"
          index += 1
          var escaped = false
          var closed = false
          while (index < end && !closed) {
            val value = content(index)
            if (escaped) escaped = false
            else if (value == '\\') escaped = true
            else if (value == '\'') closed = true
            index += 1
          }
        } else if (Character.isJavaIdentifierStart(current)) {
          val tokenStart = index
          index += 1
          while (index < end && Character.isJavaIdentifierPart(content(index))) index += 1
          tokens += new String(content, tokenStart, index - tokenStart)
        } else {
          tokens += current.toString
          index += 1
        }
      }
      tokens.result()
    }

    private def directElseIf(parent: If, child: If): Boolean = {
      val from = sourcePoint(parent)
      val until = math.max(sourceEnd(child.cond), sourcePoint(child.thenp))
      if (from < 0 || until <= from) true
      else {
        val tokens = sourceTokens(from, until)
        val childIf = tokens.lastIndexOf("if")
        childIf > 0 && tokens(childIf - 1) == "else"
      }
    }

    private def collectChain(tree: If): (Vector[(Tree, Tree, Int)], Tree) = {
      val alternatives = Vector.newBuilder[(Tree, Tree, Int)]
      var current = tree
      var otherwise: Tree = tree.elsep
      var done = false
      while (!done) {
        alternatives += ((condition(current.cond), transform(current.thenp), sourceLine(current)))
        current.elsep match {
          case next: If
              if classified.typedIfs.containsKey(next) && directElseIf(current, next) =>
            current = next
          case other =>
            otherwise = transform(other)
            done = true
        }
      }
      alternatives.result() -> otherwise
    }

    private def function0(body: Tree): Tree = Function(Nil, body)

    private def rewriteIf(original: If): Tree = {
      val (alternatives, otherwise) = collectChain(original)
      val rewritten =
        if (alternatives.size == 1) {
          val (predicate, body, line) = alternatives.head
          Apply(
            Apply(
              Apply(
                helperMethod("selectSymbolic"),
                List(
                  predicate,
                  Literal(Constant(sourceFile)),
                  Literal(Constant(line))
                )
              ),
              List(body)
            ),
            List(otherwise)
          )
        } else {
          val sequence = Apply(
            scalaSeqApply,
            alternatives.map { case (predicate, body, line) =>
              Apply(
                tuple4Apply,
                List(
                  predicate,
                  function0(body),
                  Literal(Constant(sourceFile)),
                  Literal(Constant(line))
                )
              )
            }.toList
          )
          Apply(
            helperMethod("selectSymbolicChain"),
            List(
              sequence,
              function0(otherwise),
              Literal(Constant(sourceFile)),
              Literal(Constant(sourceLine(otherwise)))
            )
          )
        }
      rewritten.setPos(original.pos)
    }

    private def rewriteGenerate(
        original: Tree,
        predicate: Tree,
        body: Tree
    ): Tree = {
      val rewritten = Apply(
        Apply(
          helperMethod("generateSymbolic"),
          List(
            condition(predicate),
            Literal(Constant(sourceFile)),
            Literal(Constant(sourceLine(original)))
          )
        ),
        List(transform(body))
      )
      rewritten.setPos(original.pos)
    }

    private def rewriteRequire(
        original: Tree,
        predicate: Tree,
        rest: List[Tree]
    ): Tree = {
      val transformedPredicate = condition(predicate)
      val source = Literal(Constant(sourceFile))
      val line = Literal(Constant(sourceLine(original)))
      val arguments = rest match {
        case Nil => List(transformedPredicate, source, line)
        case message :: Nil =>
          List(transformedPredicate, transform(message), source, line)
        case _ =>
          global.reporter.error(
            original.pos,
            "MORPHDL-TYPED-REQUIRE-ARITY-UNSUPPORTED: typed require/assert accepts zero or one message argument"
          )
          List(transformedPredicate, source, line)
      }
      val rewritten = Apply(helperMethod("requireCondition"), arguments)
      rewritten.setPos(original.pos)
    }

    override def transform(tree: Tree): Tree = tree match {
      case original: If if classified.typedIfs.containsKey(original) =>
        rewriteIf(original)
      case original @ Apply(Select(predicate, _), List(body))
          if classified.typedGenerates.containsKey(original) =>
        rewriteGenerate(original, predicate, body)
      case original @ Apply(_, predicate :: rest)
          if classified.typedRequires.containsKey(original) =>
        rewriteRequire(original, predicate, rest)
      case original @ Apply(Select(_, operator), List(_))
          if (decoded(operator) == "==" || decoded(operator) == "!=") &&
            classified.typedEqualities.containsKey(original) =>
        condition(original)
      case _ => super.transform(tree)
    }
  }

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit = {
      val classified = classify(unit.body)
      if (
        !classified.typedIfs.isEmpty ||
        !classified.typedGenerates.isEmpty ||
        !classified.typedRequires.isEmpty ||
        !classified.typedEqualities.isEmpty
      )
        unit.body =
          new TypedControlTransformer(unit, classified).transform(unit.body)
    }
  }
}
