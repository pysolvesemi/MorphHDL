package morphhdl.compiler

import scala.collection.mutable
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

/**
  * Parser-phase instrumentation for proven shadow-symbolic native Scala `Int`
  * values.
  *
  * The transformation starts from an explicit Increment 49 selection
  * (`NativeIntShadow.captureArgument`, `NativeIntShadow.captureLocal`, or
  * `shadowInt`) or from one exact generic formal-component constructor slot.
  * It then propagates a deterministic source reference through bounded native
  * integer operations. Increment 51 additionally consumes only the proven
  * Boolean references produced by those operations to retain native Scala
  * conditional alternatives. Ordinary Int and Boolean code with no proven
  * source reference is left equivalent after typing.
  */
final class MorphHdlNativeIntShadowExpressionComponent(val global: Global)
    extends PluginComponent {
  import global._

  override val phaseName: String = "morphhdl-native-int-shadow-expressions"
  override val runsAfter: List[String] = List("parser")
  override val runsBefore: List[String] =
    List("morphhdl-natural-symbolic-conditionals", "namer")

  private def eligible(unit: CompilationUnit): Boolean = {
    val content = Option(unit.source).map(_.content.mkString).getOrElse("")
    content.contains("NativeIntShadow") ||
    content.contains("shadowInt")
  }

  private def helperMethod(name: String): Tree = {
    val root = Ident(termNames.ROOTPKG)
    val spinal = Select(root, TermName("spinal"))
    val core = Select(spinal, TermName("core"))
    val helper = Select(core, TermName("ExternalNativeIntCompilerRuntime"))
    Select(helper, TermName(name))
  }

  private def frontendHelperMethod(name: String): Tree = {
    val root = Ident(termNames.ROOTPKG)
    val morphhdl = Select(root, TermName("morphhdl"))
    val frontend = Select(morphhdl, TermName("frontend"))
    val helper = Select(frontend, TermName("NativeIntShadow"))
    Select(helper, TermName(name))
  }

  private def frontendConditionalHelperMethod(name: String): Tree = {
    val root = Ident(termNames.ROOTPKG)
    val morphhdl = Select(root, TermName("morphhdl"))
    val frontend = Select(morphhdl, TermName("frontend"))
    val helper = Select(frontend, TermName("NativeIntSymbolicConditional"))
    Select(helper, TermName(name))
  }

  private def scalaSeqApply: Tree = {
    val root = Ident(termNames.ROOTPKG)
    val scalaPkg = Select(root, TermName("scala"))
    val seq = Select(scalaPkg, TermName("Seq"))
    Select(seq, TermName("apply"))
  }

  private def tuple5Apply: Tree = {
    val root = Ident(termNames.ROOTPKG)
    val scalaPkg = Select(root, TermName("scala"))
    val tuple5 = Select(scalaPkg, TermName("Tuple5"))
    Select(tuple5, TermName("apply"))
  }

  private final case class Rewrite(
      tree: Tree,
      intReference: Option[String] = None,
      booleanReference: Option[String] = None,
      intLiteral: Boolean = false,
    booleanConcrete: Boolean = false
  )

  private sealed trait SyntacticShape
  private case object UnknownShape extends SyntacticShape
  private case object UIntShape extends SyntacticShape
  private final case class RecordShape(
      members: Map[TermName, SyntacticShape]
  ) extends SyntacticShape

  /**
    * One exact native constructor slot selected by a generic formal-component
    * boundary in the same compilation unit. The source transformation derives
    * this mapping from the boundary lambda and the primary constructor; no
    * component, file or parameter spelling is built into the plugin.
    */
  private final case class NativeConstructorSelection(
      className: String,
      argumentIndex: Int,
      argumentName: Option[String],
      formalName: String,
      boundary: Tree
  )

  private final case class NativeConstructorContext(
      className: String,
      parameterName: TermName,
      formalName: String,
      reference: String,
      parameterLine: Int,
      hardTypeParameters: Set[TermName],
      concreteBooleanParameters: Set[TermName]
  )

  private final class ShadowTransformer(unit: CompilationUnit) extends Transformer {
    private var integerScopes =
      List(mutable.LinkedHashMap.empty[TermName, String])
    private var booleanScopes =
      List(mutable.LinkedHashMap.empty[TermName, String])
    private var shapeScopes =
      List(mutable.LinkedHashMap.empty[TermName, SyntacticShape])

    private var nativeConstructorContext: Option[NativeConstructorContext] = None
    private val binaryOperations = Set("+", "-", "*", "/", "%", "min", "max")
    private val uintCarrierOperations = Set("+", "-", "*", "/", "^", "===", "=/=")
    private val comparisonOperations = Set("<", "<=", ">", ">=", "==", "!=")
    private val helperOperations = Set("addressWidth", "ceilLog2", "log2Up", "log2Down")
    private val unsupportedIntegerCalls = Set(
      "abs",
      "signum",
      "rotateLeft",
      "rotateRight",
      "highestOneBit",
      "lowestOneBit",
      "numberOfLeadingZeros",
      "numberOfTrailingZeros",
      "reverse",
      "reverseBytes",
      "bitCount"
    )

    private def sourceFile: String =
      Option(unit.source)
        .flatMap(source => Option(source.file))
        .map(_.path)
        .filter(value => value.length != 0)
        .getOrElse("<native-int-shadow>")

    private def sourceLine(tree: Tree): Int =
      if (tree.pos != null && tree.pos.isDefined) math.max(1, tree.pos.line)
      else 1

    private def sourceColumn(tree: Tree): Int =
      if (tree.pos != null && tree.pos.isDefined) math.max(1, tree.pos.column)
      else 1

    private def sourcePoint(tree: Tree): Int =
      if (tree.pos != null && tree.pos.isDefined) math.max(0, tree.pos.point)
      else 0

    private def sourceReference(tree: Tree, role: String): String =
      List(
        sourceFile.replace('\\', '/'),
        sourceLine(tree).toString,
        sourceColumn(tree).toString,
        sourcePoint(tree).toString,
        role
      ).mkString(":")

    private def sourceArguments(tree: Tree): List[Tree] =
      List(
        Literal(Constant(sourceFile)),
        Literal(Constant(sourceLine(tree)))
      )

    private def decoded(name: Name): String = name.decodedName.toString

    private def path(tree: Tree): String = tree match {
      case Ident(name)       => decoded(name)
      case Select(base, name) =>
        val prefix = path(base)
        if (prefix.isEmpty) decoded(name) else s"$prefix.${decoded(name)}"
      case This(name)        => decoded(name)
      case _                 => ""
    }

    private def terminalName(tree: Tree): String = tree match {
      case Ident(name)        => decoded(name)
      case Select(_, name)    => decoded(name)
      case TypeApply(fun, _)  => terminalName(fun)
      case _                  => ""
    }

    private def terminalTypeName(tree: Tree): String = tree match {
      case Ident(name: TypeName)          => decoded(name)
      case Select(_, name: TypeName)      => decoded(name)
      case AppliedTypeTree(base, _)       => terminalTypeName(base)
      case Annotated(_, value)            => terminalTypeName(value)
      case _                              => ""
    }

    private def constructors(value: ClassDef): Vector[DefDef] =
      value.impl.body.collect {
        case method: DefDef if decoded(method.name) == "<init>" => method
      }.toVector

    private def primaryConstructorParameters(value: ClassDef): List[ValDef] =
      constructors(value).headOption.toList.flatMap(_.vparamss.flatten)

    private final case class SourceArgument(
        name: Option[String],
        value: Tree
    )

    private def sourceArgument(tree: Tree): SourceArgument = {
      // Scala 2.12 calls this parser node AssignOrNamedArg, while Scala 2.13
      // calls it NamedArg. Avoid linking the plugin to either version-specific
      // extractor; both expose the value as their final child.
      val parserNode = tree.getClass.getSimpleName
      if (parserNode == "AssignOrNamedArg" || parserNode == "NamedArg") {
        val children = tree.children
        val name = children.headOption.collect {
          case Ident(value) => decoded(value)
        }
        SourceArgument(name, children.lastOption.getOrElse(tree))
      } else SourceArgument(None, tree)
    }

    private def localConstructorType(tree: Tree): Option[String] = tree match {
      case Ident(name: TypeName) => Some(decoded(name))
      case AppliedTypeTree(base, _) => localConstructorType(base)
      case Annotated(_, value) => localConstructorType(value)
      case _ => None
    }

    private def directConstructor(
        tree: Tree
    ): Option[(String, List[Tree])] = tree match {
      case Apply(Select(New(target), constructor), arguments)
          if decoded(constructor) == "<init>" =>
        localConstructorType(target).map(_ -> arguments)
      case Block(Nil, result) => directConstructor(result)
      case Typed(result, _)   => directConstructor(result)
      case _                  => None
    }

    private def directWitness(tree: Tree, witness: TermName): Boolean =
      sourceArgument(tree).value match {
        case Ident(name: TermName) => name == witness
        case Typed(value, _)       => directWitness(value, witness)
        case _                     => false
      }

    /**
      * Discover exact constructor roots from the generic scalar boundary.
      * The selected lambda witness must feed one direct argument of one direct
      * `new` expression, and that target must have one unambiguous class
      * declaration in this compilation unit. These restrictions make source
      * identity authoritative and keep witness equality validation-only.
      */
    private def discoverNativeConstructors(
        root: Tree
    ): Map[String, NativeConstructorSelection] = {
      val classes = mutable.ArrayBuffer.empty[ClassDef]
      val requested = mutable.ArrayBuffer.empty[NativeConstructorSelection]

      object Finder extends Traverser {
        override def traverse(tree: Tree): Unit = tree match {
          case value: ClassDef =>
            classes += value
            super.traverse(tree)
          case boundary @ Apply(
                Apply(fun, boundaryArguments),
                List(Function(List(witness: ValDef), body))
              )
              if terminalName(fun) == "parameter" &&
                path(fun).endsWith("ExternalNativeIntFormalComponent.parameter") =>
            val parsedBoundaryArguments = boundaryArguments.map(sourceArgument)
            val formalName = parsedBoundaryArguments
              .find(_.name.contains("name"))
              .orElse {
                if (parsedBoundaryArguments.forall(_.name.isEmpty))
                  parsedBoundaryArguments.lift(1)
                else None
              }
              .map(_.value)
              .flatMap {
                case Literal(Constant(value: String)) if value.length != 0 =>
                  Some(value)
                case _ => None
              }
            directConstructor(body) match {
              case Some((className, constructorArguments)) =>
                val parsedConstructorArguments =
                  constructorArguments.map(sourceArgument)
                val selected = parsedConstructorArguments.zipWithIndex.collect {
                  case (argument, index)
                      if directWitness(argument.value, witness.name) =>
                    argument -> index
                }
                (formalName, selected) match {
                  case (Some(name), List((argument, argumentIndex))) =>
                    requested += NativeConstructorSelection(
                      className = className,
                      argumentIndex = argumentIndex,
                      argumentName = argument.name,
                      formalName = name,
                      boundary = boundary
                    )
                  case (None, _) =>
                    global.reporter.error(
                      boundary.pos,
                      "MORPHDL-NATIVE-INT-CONSTRUCTOR-FORMAL-NAME-INVALID: generic constructor boundaries require one literal formal name"
                    )
                  case (_, indexes) =>
                    global.reporter.error(
                      boundary.pos,
                      s"MORPHDL-NATIVE-INT-CONSTRUCTOR-ARGUMENT-AMBIGUOUS: boundary witness must feed exactly one direct constructor argument, found ${indexes.size}"
                    )
                }
              case None =>
                global.reporter.error(
                  boundary.pos,
                  "MORPHDL-NATIVE-INT-CONSTRUCTOR-DIRECT-NEW-REQUIRED: generic constructor boundaries require one direct native constructor call"
                )
            }
            super.traverse(tree)
          case _ => super.traverse(tree)
        }
      }
      Finder.traverse(root)

      val byName = classes.groupBy(value => decoded(value.name))
      val result = mutable.LinkedHashMap.empty[String, NativeConstructorSelection]
      requested.foreach { selection =>
        byName.getOrElse(selection.className, mutable.ArrayBuffer.empty).toVector match {
          case Vector(target) =>
            if (constructors(target).size != 1) {
              global.reporter.error(
                selection.boundary.pos,
                "MORPHDL-NATIVE-INT-CONSTRUCTOR-SHAPE-AMBIGUOUS: selected native class must expose exactly one primary constructor and no auxiliary constructors"
              )
            } else {
              val parameters = primaryConstructorParameters(target)
              val selectedIndexes = selection.argumentName match {
                case Some(name) =>
                  parameters.zipWithIndex.collect {
                    case (parameter, index) if decoded(parameter.name) == name =>
                      index
                  }
                case None =>
                  parameters.lift(selection.argumentIndex).toList.map(_ =>
                    selection.argumentIndex
                  )
              }
              selectedIndexes match {
                case List(selectedIndex) =>
                  val parameter = parameters(selectedIndex)
                  if (terminalTypeName(parameter.tpt) != "Int") {
                    global.reporter.error(
                      selection.boundary.pos,
                      "MORPHDL-NATIVE-INT-CONSTRUCTOR-ARGUMENT-TYPE-INVALID: selected native constructor argument must be declared as scala.Int"
                    )
                  } else {
                    val resolved = selection.copy(argumentIndex = selectedIndex)
                    result.get(selection.className) match {
                      case Some(existing)
                          if existing.argumentIndex != resolved.argumentIndex ||
                            existing.formalName != resolved.formalName =>
                        global.reporter.error(
                          selection.boundary.pos,
                          "MORPHDL-NATIVE-INT-CONSTRUCTOR-SELECTION-CONFLICT: one native class received conflicting generic constructor selections"
                        )
                      case _ => result.update(selection.className, resolved)
                    }
                  }
                case Nil =>
                  global.reporter.error(
                    selection.boundary.pos,
                    "MORPHDL-NATIVE-INT-CONSTRUCTOR-ARGUMENT-MISSING: selected native constructor argument does not resolve to the primary constructor"
                  )
                case _ =>
                  global.reporter.error(
                    selection.boundary.pos,
                    "MORPHDL-NATIVE-INT-CONSTRUCTOR-ARGUMENT-AMBIGUOUS: selected native constructor argument name is not unique"
                  )
              }
            }
          case Vector() =>
            global.reporter.error(
              selection.boundary.pos,
              "MORPHDL-NATIVE-INT-CONSTRUCTOR-DECLARATION-MISSING: selected native constructor must be declared in the same compilation unit"
            )
          case _ =>
            global.reporter.error(
              selection.boundary.pos,
              "MORPHDL-NATIVE-INT-CONSTRUCTOR-DECLARATION-AMBIGUOUS: selected native constructor declaration is not unique in its compilation unit"
            )
        }
      }
      result.toMap
    }

    private lazy val nativeConstructorSelections =
      discoverNativeConstructors(unit.body)

    def hasNativeConstructorSelections: Boolean =
      nativeConstructorSelections.nonEmpty

    private def spinalCoreMethod(name: String): Tree = {
      val root = Ident(termNames.ROOTPKG)
      val spinal = Select(root, TermName("spinal"))
      val core = Select(spinal, TermName("core"))
      Select(core, TermName(name))
    }

    private def inNativeConstructor: Boolean = nativeConstructorContext.nonEmpty

    private def nativeHardTypeParameter(tree: Tree): Boolean = tree match {
      case Ident(name: TermName) =>
        nativeConstructorContext.exists(_.hardTypeParameters(name))
      case Select(This(_), name: TermName) =>
        nativeConstructorContext.exists(_.hardTypeParameters(name))
      case _ => false
    }

    private def selectedNativeIntParameter(tree: Tree): Boolean = tree match {
      case Ident(name: TermName) =>
        nativeConstructorContext.exists(_.parameterName == name)
      case Select(This(owner), name: TermName) =>
        nativeConstructorContext.exists(context =>
          context.parameterName == name && decoded(owner) == context.className
        )
      case _ => false
    }

    private def shadowsSelectedNativeParameter(value: ValDef): Boolean =
      nativeConstructorContext.exists { context =>
        value.name == context.parameterName &&
        sourceReference(value, s"argument:${context.formalName}") !=
          context.reference &&
        !value.mods.hasFlag(Flag.PARAMACCESSOR)
      }

    private def nestedConstructorShadowsSelectedParameter(
        value: ClassDef
    ): Boolean =
      nativeConstructorContext.exists { context =>
        primaryConstructorParameters(value).exists(
          _.name == context.parameterName
        )
      }

    private def nativeParameterSourceArguments: List[Tree] =
      nativeConstructorContext.toList.flatMap { context =>
        List(
          Literal(Constant(sourceFile)),
          Literal(Constant(context.parameterLine))
        )
      }

    private def isConcreteBoolean(tree: Tree): Boolean = tree match {
      case Literal(Constant(_: Boolean)) => true
      case Ident(name: TermName) =>
        nativeConstructorContext.exists(_.concreteBooleanParameters(name))
      case Select(This(_), name: TermName) =>
        nativeConstructorContext.exists(_.concreteBooleanParameters(name))
      case Apply(Select(value, name), Nil)
          if decoded(name) == "unary_!" =>
        isConcreteBoolean(value)
      case Select(value, name) if decoded(name) == "unary_!" =>
        isConcreteBoolean(value)
      case Apply(Select(left, name), List(right))
          if decoded(name) == "&&" || decoded(name) == "||" =>
        isConcreteBoolean(left) && isConcreteBoolean(right)
      case _ => false
    }

    private def lookupShape(
        name: TermName,
        scopes: List[scala.collection.Map[TermName, SyntacticShape]] = shapeScopes
    ): SyntacticShape =
      scopes.collectFirst {
        case scope if scope.contains(name) => scope(name)
      }.getOrElse(UnknownShape)

    private def anonymousTemplate(tree: Tree): Option[Template] = tree match {
      case Block(
            statements,
            Apply(Select(New(Ident(created: TypeName)), constructor), _)
          ) if decoded(constructor) == "<init>" =>
        statements.collectFirst {
          case ClassDef(_, name, _, implementation) if name == created =>
            implementation
        }
      case _ => None
    }

    private def recordShape(
        template: Template,
        inherited: List[scala.collection.Map[TermName, SyntacticShape]]
    ): SyntacticShape = {
      val local = mutable.LinkedHashMap.empty[TermName, SyntacticShape]
      template.body.foreach {
        case value: ValDef
            if !value.mods.hasFlag(Flag.MUTABLE) &&
              !value.mods.hasFlag(Flag.LAZY) && value.rhs != EmptyTree =>
          val shape = inferShape(value.rhs, local :: inherited)
          local.update(value.name, shape)
        case _ =>
      }
      // Member existence is useful provenance even when a member's value shape
      // is not otherwise one of the bounded hardware shapes. In particular,
      // parser-phase effect analysis must distinguish a proven anonymous-record
      // field read from a same-spelled, zero-argument Scala method call.
      if (local.nonEmpty) RecordShape(local.toMap) else UnknownShape
    }

    private def inferShape(
        tree: Tree,
        scopes: List[scala.collection.Map[TermName, SyntacticShape]] = shapeScopes
    ): SyntacticShape =
      anonymousTemplate(tree)
        .map(recordShape(_, scopes))
        .getOrElse {
          tree match {
            case Ident(name: TermName) => lookupShape(name, scopes)
            case Select(This(_), name: TermName) => lookupShape(name, scopes)
            case Select(base, name: TermName) =>
              inferShape(base, scopes) match {
                case RecordShape(members) =>
                  members.getOrElse(name, UnknownShape)
                case UIntShape
                    if Set("resized", "resize", "asUInt").contains(decoded(name)) =>
                  UIntShape
                case _ => UnknownShape
              }
            case Apply(fun, List(_)) if terminalName(fun) == "UInt" =>
              UIntShape
            case Apply(fun, List(value))
                if Set("Reg", "cloneOf", "in", "out").contains(
                  terminalName(fun)
                ) && inferShape(value, scopes) == UIntShape =>
              UIntShape
            case Apply(Select(base, name), _)
                if decoded(name) == "init" &&
                  inferShape(base, scopes) == UIntShape =>
              UIntShape
            case Apply(Select(left, name), List(right))
                if decoded(name) == "^" &&
                  inferShape(left, scopes) == UIntShape &&
                  inferShape(right, scopes) == UIntShape =>
              UIntShape
            case Apply(Select(left, name), List(_))
                if Set("+^", "-^").contains(decoded(name)) &&
                  inferShape(left, scopes) == UIntShape =>
              UIntShape
            case Typed(value, _) => inferShape(value, scopes)
            case _               => UnknownShape
          }
        }

    private def lookupInteger(name: TermName): Option[String] =
      integerScopes.collectFirst {
        case scope if scope.contains(name) => scope(name)
      }

    private def lookupBoolean(name: TermName): Option[String] =
      booleanScopes.collectFirst {
        case scope if scope.contains(name) => scope(name)
      }

    private def bindInteger(name: TermName, reference: String): Unit =
      integerScopes.head.update(name, reference)

    private def bindBoolean(name: TermName, reference: String): Unit =
      booleanScopes.head.update(name, reference)

    private def bindShape(name: TermName, shape: SyntacticShape): Unit =
      shapeScopes.head.update(name, shape)

    private def withScope[A](body: => A): A = {
      integerScopes = mutable.LinkedHashMap.empty[TermName, String] :: integerScopes
      booleanScopes = mutable.LinkedHashMap.empty[TermName, String] :: booleanScopes
      shapeScopes =
        mutable.LinkedHashMap.empty[TermName, SyntacticShape] :: shapeScopes
      try body
      finally {
        integerScopes = integerScopes.tail
        booleanScopes = booleanScopes.tail
        shapeScopes = shapeScopes.tail
      }
    }

    private def withoutNativeConstructorContext[A](body: => A): A = {
      val previous = nativeConstructorContext
      nativeConstructorContext = None
      try body
      finally nativeConstructorContext = previous
    }

    private def trackedInteger(tree: Tree): Option[String] = tree match {
      case Ident(name: TermName) => lookupInteger(name)
      case Select(This(_), name: TermName) => lookupInteger(name)
      case _ => None
    }

    private def trackedBoolean(tree: Tree): Option[String] = tree match {
      case Ident(name: TermName) => lookupBoolean(name)
      case Select(This(_), name: TermName) => lookupBoolean(name)
      case _ => None
    }

    private def literalInteger(tree: Tree): Option[Int] = tree match {
      case Literal(Constant(value: Int))   => Some(value)
      case Literal(Constant(value: Short)) => Some(value.toInt)
      case Literal(Constant(value: Byte))  => Some(value.toInt)
      case Apply(Select(value, name), Nil) if decoded(name) == "unary_-" =>
        literalInteger(value).map(-_)
      case Select(value, name) if decoded(name) == "unary_-" =>
        literalInteger(value).map(-_)
      case _ => None
    }

    private def literalString(tree: Tree): Option[String] = tree match {
      case Literal(Constant(value: String)) => Some(value)
      case _                                => None
    }

    private def resultName(
        requested: Option[String],
        operation: String,
        tree: Tree
    ): String = requested.filter(value => value.length != 0).getOrElse {
      val cleaned = operation.map {
        case value if value.isLetterOrDigit => value
        case _                              => '_'
      }
      s"native_${cleaned}_${sourceLine(tree)}_${sourceColumn(tree)}_${sourcePoint(tree)}"
    }

    private def markerCall(
        tree: Tree,
        method: String
    ): Option[(Tree, Tree)] = tree match {
      case Apply(fun, List(value, name))
          if terminalName(fun) == method &&
            path(fun).contains("NativeIntShadow") =>
        Some(value -> name)
      case _ => None
    }

    private def shadowCall(tree: Tree): Option[(Tree, Tree)] = tree match {
      case Apply(fun, List(value, name)) =>
        val rendered = path(fun)
        if (
          rendered == "shadowInt" || rendered.endsWith(".shadowInt") ||
          rendered == "shadowInt.apply" || rendered.endsWith(".shadowInt.apply")
        ) Some(value -> name)
        else markerCall(tree, "captureLocal")
      case _ => None
    }

    private def selectedHelperMethod(name: String): Tree =
      if (inNativeConstructor) helperMethod(name) else frontendHelperMethod(name)

    private def selectedConditionalHelperMethod(name: String): Tree =
      if (inNativeConstructor) helperMethod(name)
      else frontendConditionalHelperMethod(name)

    private def call(name: String, arguments: List[Tree], original: Tree): Tree = {
      val rewritten = Apply(selectedHelperMethod(name), arguments)
      rewritten.setPos(original.pos)
    }

    private def curriedCall(
        name: String,
        arguments: List[Tree],
        body: Tree,
        original: Tree
    ): Tree = {
      val rewritten = Apply(Apply(selectedHelperMethod(name), arguments), List(body))
      rewritten.setPos(original.pos)
    }

    private def unsupportedInt(
        reference: String,
        code: String,
        detail: String,
        original: Tree,
        nativeTree: Tree
    ): Rewrite =
      Rewrite(
        curriedCall(
          "compilerUnsupportedInt",
          List(
            Literal(Constant(reference)),
            Literal(Constant(code)),
            Literal(Constant(detail))
          ) ++ sourceArguments(original),
          nativeTree,
          original
        )
      )

    private def unsupportedValue(
        reference: String,
        code: String,
        detail: String,
        original: Tree,
        nativeTree: Tree
    ): Rewrite =
      Rewrite(
        curriedCall(
          "compilerUnsupportedValue",
          List(
            Literal(Constant(reference)),
            Literal(Constant(code)),
            Literal(Constant(detail))
          ) ++ sourceArguments(original),
          nativeTree,
          original
        )
      )

    private def unsupportedBoolean(
        reference: String,
        code: String,
        detail: String,
        original: Tree,
        nativeTree: Tree
    ): Rewrite =
      Rewrite(
        curriedCall(
          "compilerUnsupportedBoolean",
          List(
            Literal(Constant(reference)),
            Literal(Constant(code)),
            Literal(Constant(detail))
          ) ++ sourceArguments(original),
          nativeTree,
          original
        )
      )

    private def rewriteMarker(
        original: Tree,
        value: Tree,
        nameTree: Tree,
        requestedName: Option[String]
    ): Rewrite = {
      literalString(nameTree) match {
        case None =>
          global.reporter.error(
            nameTree.pos,
            "MORPHDL-NATIVE-INT-SHADOW-NAME-INVALID: selected native Int names must be string literals"
          )
          Rewrite(super.transform(original))
        case Some(name) =>
          val reference = sourceReference(original, s"argument:$name")
          val rewrittenValue = super.transform(value)
          Rewrite(
            call(
              "compilerTrackArgument",
              List(
                rewrittenValue,
                Literal(Constant(name)),
                Literal(Constant(reference))
              ) ++ sourceArguments(original),
              original
            ),
            intReference = Some(reference)
          )
      }
    }

    private def rewriteShadowLocal(
        original: Tree,
        value: Tree,
        nameTree: Tree,
        requestedName: Option[String]
    ): Rewrite = {
      literalString(nameTree) match {
        case None =>
          global.reporter.error(
            nameTree.pos,
            "MORPHDL-NATIVE-INT-SHADOW-NAME-INVALID: selected native Int names must be string literals"
          )
          Rewrite(super.transform(original))
        case Some(name) =>
          val source = rewriteExpression(value, None)
          source.intReference match {
            case None =>
              // Preserve Increment 49 direct-alias behavior. A derived chain
              // starts only after one exact compiler-proven source is present.
              Rewrite(super.transform(original))
            case Some(sourceRef) =>
              val resultRef = sourceReference(original, s"local:$name")
              Rewrite(
                call(
                  "compilerTrackLocal",
                  List(
                    source.tree,
                    Literal(Constant(name)),
                    Literal(Constant(sourceRef)),
                    Literal(Constant(resultRef))
                  ) ++ sourceArguments(original),
                  original
                ),
                intReference = Some(resultRef)
              )
          }
      }
    }

    private def rewriteSelectedNativeIntParameter(original: Tree): Rewrite = {
      val context = nativeConstructorContext.getOrElse(
        throw new IllegalStateException("selected native constructor context is missing")
      )
      Rewrite(
        call(
          "compilerTrackArgument",
          List(
            super.transform(original),
            Literal(Constant(context.formalName)),
            Literal(Constant(context.reference))
          ) ++ nativeParameterSourceArguments,
          original
        ),
        intReference = Some(context.reference)
      )
    }

    private def rewriteAlias(
        original: Tree,
        reference: String,
        requestedName: Option[String]
    ): Rewrite = requestedName match {
      case None => Rewrite(super.transform(original), intReference = Some(reference))
      case Some(name) =>
        val resultRef = sourceReference(original, s"alias:$name")
        Rewrite(
          call(
            "compilerAlias",
            List(
              super.transform(original),
              Literal(Constant(name)),
              Literal(Constant(reference)),
              Literal(Constant(resultRef))
            ) ++ sourceArguments(original),
            original
          ),
          intReference = Some(resultRef)
        )
    }

    private def operand(
        tree: Tree
    ): Rewrite = {
      val rewritten = rewriteExpression(tree, None)
      if (rewritten.intReference.nonEmpty) rewritten
      else literalInteger(tree) match {
        case Some(_) => rewritten.copy(intLiteral = true)
        case None    => rewritten
      }
    }

    private def nativeBinaryTree(
        original: Tree,
        left: Tree,
        operatorName: Name,
        right: Tree
    ): Tree = {
      val value = Apply(Select(left, operatorName), List(right))
      value.setPos(original.pos)
    }

    private def rewriteBinary(
        original: Tree,
        leftTree: Tree,
        operatorName: Name,
        rightTree: Tree,
        operation: String,
        requestedName: Option[String]
    ): Rewrite = {
      val left = operand(leftTree)
      val right = operand(rightTree)
      val proven = left.intReference.orElse(right.intReference)
      proven match {
        case None =>
          Rewrite(
            nativeBinaryTree(original, left.tree, operatorName, right.tree),
            intLiteral = literalInteger(original).nonEmpty
          )
        case Some(reference)
            if (left.intReference.isEmpty && !left.intLiteral) ||
              (right.intReference.isEmpty && !right.intLiteral) =>
          rewriteMixedHardwareBinary(
            original,
            leftTree,
            operatorName,
            rightTree,
            operation
          ).getOrElse {
            val unsupported =
              if (
                inferShape(leftTree) == UIntShape ||
                inferShape(rightTree) == UIntShape
              ) unsupportedValue _
              else unsupportedInt _
            unsupported(
              reference,
              "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-OPERAND-UNPROVEN",
              s"native Int '$operation' requires every nonliteral operand to carry exact shadow provenance",
              original,
              nativeBinaryTree(original, left.tree, operatorName, right.tree)
            )
          }
        case Some(_) =>
          val name = resultName(requestedName, operation, original)
          val resultRef = sourceReference(original, s"expression:$name")
          Rewrite(
            call(
              "compilerBinary",
              List(
                Literal(Constant(operation)),
                left.tree,
                Literal(Constant(left.intReference.getOrElse(""))),
                Literal(Constant(left.intLiteral)),
                right.tree,
                Literal(Constant(right.intReference.getOrElse(""))),
                Literal(Constant(right.intLiteral)),
                Literal(Constant(resultRef)),
                Literal(Constant(name))
              ) ++ sourceArguments(original),
              original
            ),
            intReference = Some(resultRef)
          )
      }
    }

    private def booleanOperand(tree: Tree): Rewrite = {
      val rewritten = rewriteExpression(tree, None)
      if (rewritten.booleanReference.nonEmpty) rewritten
      else if (isConcreteBoolean(tree)) rewritten.copy(booleanConcrete = true)
      else rewritten
    }

    private def rewriteBooleanBinary(
        original: Tree,
        leftTree: Tree,
        operatorName: Name,
        rightTree: Tree,
        operation: String,
        requestedName: Option[String]
    ): Rewrite = {
      val left = booleanOperand(leftTree)
      val right = booleanOperand(rightTree)
      val native = nativeBinaryTree(original, left.tree, operatorName, right.tree)
      val proven = left.booleanReference.orElse(right.booleanReference)
      proven match {
        case None =>
          Rewrite(
            native,
            booleanConcrete = left.booleanConcrete && right.booleanConcrete
          )
        case Some(reference)
            if (left.booleanReference.isEmpty && !left.booleanConcrete) ||
              (right.booleanReference.isEmpty && !right.booleanConcrete) =>
          unsupportedBoolean(
            reference,
            "MORPH-FRONTEND-NATIVE-INT-PREDICATE-OPERAND-UNPROVEN",
            s"native Boolean '$operation' requires every operand to be proven symbolic or an exact concrete constructor Boolean",
            original,
            native
          )
        case Some(_) =>
          val name = resultName(requestedName, s"boolean_$operation", original)
          val resultRef = sourceReference(original, s"predicate:$name")
          Rewrite(
            call(
              "compilerBooleanBinary",
              List(
                Literal(Constant(operation)),
                left.tree,
                Literal(Constant(left.booleanReference.getOrElse(""))),
                Literal(Constant(left.booleanConcrete)),
                right.tree,
                Literal(Constant(right.booleanReference.getOrElse(""))),
                Literal(Constant(right.booleanConcrete)),
                Literal(Constant(resultRef)),
                Literal(Constant(name))
              ) ++ sourceArguments(original),
              original
            ),
            booleanReference = Some(resultRef)
          )
      }
    }

    private def rewriteBooleanNot(
        original: Tree,
        valueTree: Tree,
        requestedName: Option[String]
    ): Rewrite = {
      val value = booleanOperand(valueTree)
      val native = original match {
        case Apply(Select(_, operatorName), Nil) =>
          val result = Apply(Select(value.tree, operatorName), Nil)
          result.setPos(original.pos)
        case Select(_, operatorName) =>
          val result = Select(value.tree, operatorName)
          result.setPos(original.pos)
        case _ => super.transform(original)
      }
      value.booleanReference match {
        case None => Rewrite(native, booleanConcrete = value.booleanConcrete)
        case Some(reference) =>
          val name = resultName(requestedName, "boolean_not", original)
          val resultRef = sourceReference(original, s"predicate:$name")
          Rewrite(
            call(
              "compilerBooleanNot",
              List(
                value.tree,
                Literal(Constant(reference)),
                Literal(Constant(value.booleanConcrete)),
                Literal(Constant(resultRef)),
                Literal(Constant(name))
              ) ++ sourceArguments(original),
              original
            ),
            booleanReference = Some(resultRef)
          )
      }
    }

    private def rewriteBooleanToInt(
        original: Tree,
        valueTree: Tree,
        requestedName: Option[String]
    ): Rewrite = {
      val value = booleanOperand(valueTree)
      value.booleanReference match {
        case None => Rewrite(super.transform(original))
        case Some(reference) =>
          val name = resultName(requestedName, "boolean_to_int", original)
          val resultRef = sourceReference(original, s"expression:$name")
          Rewrite(
            call(
              "compilerBooleanToInt",
              List(
                value.tree,
                Literal(Constant(reference)),
                Literal(Constant(value.booleanConcrete)),
                Literal(Constant(resultRef)),
                Literal(Constant(name))
              ) ++ sourceArguments(original),
              original
            ),
            intReference = Some(resultRef)
          )
      }
    }

    private def bitCountValue(tree: Tree): Option[Tree] = tree match {
      case Apply(Select(value, name), Nil)
          if decoded(name) == "bits" || decoded(name) == "bit" => Some(value)
      case Select(value, name)
          if decoded(name) == "bits" || decoded(name) == "bit" => Some(value)
      case Apply(fun, List(value)) if terminalName(fun) == "BitCount" => Some(value)
      case _ => None
    }

    private def rebuildBitCount(original: Tree, value: Tree): Tree = {
      val result = original match {
        case Apply(Select(_, name), Nil) => Apply(Select(value, name), Nil)
        case Select(_, name) => Select(value, name)
        case Apply(fun, List(_)) => Apply(super.transform(fun), List(value))
        case _ => original
      }
      result.setPos(original.pos)
    }

    private def rewriteBitVectorFactory(
        original: Tree,
        fun: Tree,
        bitCount: Tree,
        method: String
    ): Rewrite = bitCountValue(bitCount) match {
      case None => Rewrite(super.transform(original))
      case Some(widthTree) =>
        val width = rewriteExpression(widthTree, None)
        width.intReference match {
          case None => Rewrite(super.transform(original))
          case Some(reference) =>
            val rebuilt = rebuildBitCount(bitCount, width.tree)
            val native = Apply(super.transform(fun), List(rebuilt))
            native.setPos(original.pos)
            Rewrite(
              curriedCall(
                method,
                List(Literal(Constant(reference))) ++ sourceArguments(original),
                native,
                original
              )
            )
        }
    }

    private def rewriteNativeMem(
        original: Tree,
        fun: Tree,
        dataType: Tree,
        depthTree: Tree
    ): Rewrite = {
      val depth = rewriteExpression(depthTree, None)
      depth.intReference match {
        case None => Rewrite(super.transform(original))
        case Some(reference) =>
          val transformedType = transform(dataType)
          val native = Apply(
            super.transform(fun),
            List(transformedType, depth.tree)
          )
          native.setPos(original.pos)
          Rewrite(
            curriedCall(
              "compilerMem",
              List(
                depth.tree,
                Literal(Constant(reference))
              ) ++ sourceArguments(original),
              native,
              original
            )
          )
      }
    }

    private def rewriteNativeReg(
        original: Tree,
        fun: Tree,
        dataType: Tree
    ): Rewrite = {
      val transformedType = transform(dataType)
      val native = Apply(super.transform(fun), List(transformedType))
      native.setPos(original.pos)
      val helper =
        if (nativeHardTypeParameter(dataType)) "compilerRegHardType"
        else "compilerReg"
      Rewrite(
        curriedCall(
          helper,
          List(transformedType),
          native,
          original
        )
      )
    }

    private def rewriteNativeClone(
        original: Tree,
        fun: Tree,
        data: Tree
    ): Rewrite = {
      val transformedData = transform(data)
      val native = Apply(super.transform(fun), List(transformedData))
      native.setPos(original.pos)
      Rewrite(
        curriedCall(
          "compilerCloneOf",
          List(transformedData),
          native,
          original
        )
      )
    }

    private def rewriteNativeCopyShape(
        original: Tree,
        fun: Tree,
        arguments: List[Tree]
    ): Rewrite = {
      val transformed = arguments.map(transform)
      val native = Apply(super.transform(fun), transformed)
      native.setPos(original.pos)
      Rewrite(
        curriedCall(
          "compilerCopyShape",
          List(transformed.head),
          native,
          original
        )
      )
    }

    private def rewriteNativeStream(
        original: Tree,
        fun: Tree,
        dataType: Tree
    ): Rewrite = {
      val transformedType = transform(dataType)
      val nativeHardType = Apply(
        spinalCoreMethod("HardType"),
        List(transformedType)
      )
      nativeHardType.setPos(original.pos)
      val retainedHardType = curriedCall(
        "compilerHardType",
        List(transformedType),
        nativeHardType,
        original
      )
      val native = Apply(super.transform(fun), List(retainedHardType))
      native.setPos(original.pos)
      Rewrite(native)
    }

    private def nativeValueCarrier(
        value: Rewrite,
        prototype: Tree,
        original: Tree,
        role: String
    ): Tree = {
      val reference = value.intReference.getOrElse(
        throw new IllegalStateException("native value carrier lost its provenance")
      )
      val name = resultName(None, s"carrier_$role", original)
      prototype match {
        case Apply(Select(left, operatorName), List(right))
            if decoded(operatorName) == "^" &&
              inferShape(left) == UIntShape && inferShape(right) == UIntShape =>
          call(
            "compilerUIntValueLikeBinary",
            List(
              value.tree,
              Literal(Constant(reference)),
              Literal(Constant("^")),
              transform(left),
              transform(right),
              Literal(Constant(name))
            ) ++ sourceArguments(original),
            original
          )
        case _ =>
          call(
            "compilerUIntValueLike",
            List(
              value.tree,
              Literal(Constant(reference)),
              transform(prototype),
              Literal(Constant(name))
            ) ++ sourceArguments(original),
            original
          )
      }
    }

    private def rewriteMixedHardwareBinary(
        original: Tree,
        leftTree: Tree,
        operatorName: Name,
        rightTree: Tree,
        operation: String
    ): Option[Rewrite] = {
      if (!inNativeConstructor || !uintCarrierOperations(operation)) return None
      val left = rewriteExpression(leftTree, None)
      val right = rewriteExpression(rightTree, None)
      if (left.intReference.nonEmpty && inferShape(rightTree) == UIntShape) {
        val carrier = nativeValueCarrier(left, rightTree, original, "left")
        Some(Rewrite(nativeBinaryTree(original, carrier, operatorName, transform(rightTree))))
      } else if (right.intReference.nonEmpty && inferShape(leftTree) == UIntShape) {
        val carrier = nativeValueCarrier(right, leftTree, original, "right")
        Some(Rewrite(nativeBinaryTree(original, transform(leftTree), operatorName, carrier)))
      } else None
    }

    private def rewriteStaticMinMax(
        original: Tree,
        fun: Tree,
        arguments: List[Tree],
        requestedName: Option[String]
    ): Option[Rewrite] = {
      val operation = terminalName(fun)
      if ((operation == "min" || operation == "max") && arguments.size == 2) {
        val syntheticName = TermName(operation)
        Some(
          rewriteBinary(
            original,
            arguments.head,
            syntheticName,
            arguments(1),
            operation,
            requestedName
          )
        )
      } else None
    }

    private def rewriteUnary(
        original: Tree,
        operation: String,
        valueTree: Tree,
        requestedName: Option[String]
    ): Rewrite = {
      val value = operand(valueTree)
      value.intReference match {
        case None => Rewrite(super.transform(original), intLiteral = literalInteger(original).nonEmpty)
        case Some(reference) =>
          val name = resultName(requestedName, operation, original)
          val resultRef = sourceReference(original, s"expression:$name")
          Rewrite(
            call(
              "compilerUnary",
              List(
                Literal(Constant(operation)),
                value.tree,
                Literal(Constant(reference)),
                Literal(Constant(resultRef)),
                Literal(Constant(name))
              ) ++ sourceArguments(original),
              original
            ),
            intReference = Some(resultRef)
          )
      }
    }

    private def rewriteComparison(
        original: Tree,
        leftTree: Tree,
        operatorName: Name,
        rightTree: Tree,
        operation: String,
        requestedName: Option[String]
    ): Rewrite = {
      val left = operand(leftTree)
      val right = operand(rightTree)
      val proven = left.intReference.orElse(right.intReference)
      val native = nativeBinaryTree(original, left.tree, operatorName, right.tree)
      proven match {
        case None => Rewrite(native)
        case Some(reference)
            if (left.intReference.isEmpty && !left.intLiteral) ||
              (right.intReference.isEmpty && !right.intLiteral) =>
          unsupportedBoolean(
            reference,
            "MORPH-FRONTEND-NATIVE-INT-PREDICATE-OPERAND-UNPROVEN",
            s"native Int predicate '$operation' requires every nonliteral operand to carry exact shadow provenance",
            original,
            native
          )
        case Some(_) =>
          val name = resultName(requestedName, s"predicate_$operation", original)
          val predicateRef = sourceReference(original, s"predicate:$name")
          Rewrite(
            call(
              "compilerComparison",
              List(
                Literal(Constant(operation)),
                left.tree,
                Literal(Constant(left.intReference.getOrElse(""))),
                Literal(Constant(left.intLiteral)),
                right.tree,
                Literal(Constant(right.intReference.getOrElse(""))),
                Literal(Constant(right.intLiteral)),
                Literal(Constant(predicateRef)),
                Literal(Constant(name))
              ) ++ sourceArguments(original),
              original
            ),
            booleanReference = Some(predicateRef)
          )
      }
    }

    private def rewritePowerOfTwo(
        original: Tree,
        valueTree: Tree,
        requestedName: Option[String]
    ): Rewrite = {
      val value = operand(valueTree)
      value.intReference match {
        case None => Rewrite(super.transform(original))
        case Some(reference) =>
          val name = resultName(requestedName, "isPow2", original)
          val predicateRef = sourceReference(original, s"predicate:$name")
          Rewrite(
            call(
              "compilerPowerOfTwo",
              List(
                value.tree,
                Literal(Constant(reference)),
                Literal(Constant(predicateRef)),
                Literal(Constant(name))
              ) ++ sourceArguments(original),
              original
            ),
            booleanReference = Some(predicateRef)
          )
      }
    }

    private def firstTrackedInteger(tree: Tree): Option[String] = {
      var finding: Option[String] = None
      object Finder extends Traverser {
        override def traverse(current: Tree): Unit =
          if (finding.isEmpty) {
            if (selectedNativeIntParameter(current))
              finding = nativeConstructorContext.map(_.reference)
            else
              trackedInteger(current) match {
                case Some(value) => finding = Some(value)
                case None        => super.traverse(current)
              }
          }
      }
      Finder.traverse(tree)
      finding
    }

    private def firstTrackedBoolean(tree: Tree): Option[String] = {
      var finding: Option[String] = None
      object Finder extends Traverser {
        override def traverse(current: Tree): Unit =
          if (finding.isEmpty) {
            trackedBoolean(current) match {
              case Some(value) => finding = Some(value)
              case None        => super.traverse(current)
            }
          }
      }
      Finder.traverse(tree)
      finding
    }

    private final case class UnsafeAlternativeEffect(
        code: String,
        detail: String,
        tree: Tree
    )

    private val ioEffectMethods = Set(
      "print",
      "println",
      "printf",
      "readLine",
      "flush"
    )
    private val reflectionEffectMethods = Set(
      "getClass",
      "forName",
      "getDeclaredField",
      "getDeclaredFields",
      "getDeclaredMethod",
      "getDeclaredMethods",
      "getField",
      "getFields",
      "getMethod",
      "getMethods",
      "newInstance",
      "setAccessible"
    )
    private val nondeterministicEffectMethods = Set(
      "currentTimeMillis",
      "nanoTime",
      "randomUUID",
      "nextBoolean",
      "nextBytes",
      "nextDouble",
      "nextFloat",
      "nextGaussian",
      "nextInt",
      "nextLong",
      "now"
    )
    private val arbitraryEffectMethods = Set(
      "synchronized",
      "wait",
      "notify",
      "notifyAll",
      "sleep",
      "start",
      "join",
      "exit",
      "exec",
      "load",
      "loadLibrary"
    )

    private def effect(
        code: String,
        detail: String,
        tree: Tree
    ): UnsafeAlternativeEffect =
      UnsafeAlternativeEffect(code, detail, tree)

    private def unsafeCallEffect(
        fun: Tree,
        original: Tree
    ): Option[UnsafeAlternativeEffect] = {
      val rendered = path(fun)
      val method = terminalName(fun)
      if (method.endsWith("_=") || method.endsWith("+=")) Some(effect(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-MUTABLE-STATE-UNSUPPORTED",
        s"setter or update call '$rendered' mutates Scala state and is not permitted inside a captured native symbolic alternative",
        original
      ))
      else if (
        ioEffectMethods.contains(method) || rendered.contains("Console") ||
        rendered.contains("java.io") || rendered.contains("java.nio.file") ||
        rendered.contains("scala.io") || rendered.contains("Socket") ||
        rendered.contains("InputStream") || rendered.contains("OutputStream")
      ) Some(effect(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-IO-UNSUPPORTED",
        s"I/O call '$rendered' is not permitted while capturing a native symbolic alternative",
        original
      ))
      else if (
        reflectionEffectMethods.contains(method) ||
        rendered.contains("scala.reflect") || rendered.contains("java.lang.reflect")
      ) Some(effect(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-REFLECTION-UNSUPPORTED",
        s"reflection call '$rendered' is not permitted while capturing a native symbolic alternative",
        original
      ))
      else if (
        nondeterministicEffectMethods.contains(method) &&
        (rendered.contains("Random") || rendered.contains("System") ||
          rendered.contains("UUID") || rendered.contains("Instant") ||
          rendered.contains("LocalDate") || rendered.contains("ZonedDate"))
      ) Some(effect(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-NONDETERMINISM-UNSUPPORTED",
        s"nondeterministic call '$rendered' is not permitted while capturing a native symbolic alternative",
        original
      ))
      else if (arbitraryEffectMethods.contains(method)) Some(effect(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-ARBITRARY-EFFECT-UNSUPPORTED",
        s"Scala effect '$rendered' is outside the Increment 52 safe structural contract",
        original
      ))
      else None
    }

    private def unsafeAlternativeEffect(
        tree: Tree
    ): Option[UnsafeAlternativeEffect] = {
      def provenRecordField(selection: Tree): Boolean = selection match {
        case Select(base, name: TermName) =>
          inferShape(base) match {
            case RecordShape(members) => members.contains(name)
            case _                    => false
          }
        case _ => false
      }

      var finding: Option[UnsafeAlternativeEffect] = None
      object Finder extends Traverser {
        override def traverse(current: Tree): Unit = if (finding.isEmpty) current match {
          case value: ValDef if value.mods.hasFlag(Flag.MUTABLE) =>
            finding = Some(effect(
              "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-MUTABLE-STATE-UNSUPPORTED",
              s"mutable Scala variable '${decoded(value.name)}' is not permitted inside a captured native symbolic alternative",
              current
            ))
          case _: Assign =>
            finding = Some(effect(
              "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-MUTABLE-STATE-UNSUPPORTED",
              "assignment to Scala mutable state is not permitted inside a captured native symbolic alternative",
              current
            ))
          case _: Return =>
            finding = Some(effect(
              "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-CONTROL-EFFECT-UNSUPPORTED",
              "return is outside the safe native symbolic alternative contract",
              current
            ))
          case _: Throw =>
            finding = Some(effect(
              "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-CONTROL-EFFECT-UNSUPPORTED",
              "throw is outside the safe native symbolic alternative contract",
              current
            ))
          case _: Try =>
            finding = Some(effect(
              "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-CONTROL-EFFECT-UNSUPPORTED",
              "try/catch/finally is outside the safe native symbolic alternative contract",
              current
            ))
          case _: LabelDef =>
            finding = Some(effect(
              "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-UNBOUNDED-LOOP-UNSUPPORTED",
              "while/do-style mutable loops are not permitted; use a finite immutable range loop",
              current
            ))
          case application @ Apply(fun, _) =>
            unsafeCallEffect(fun, application) match {
              case value @ Some(_) => finding = value
              case None            => super.traverse(current)
            }
          case selection @ Select(_, _) =>
            if (provenRecordField(selection)) super.traverse(current)
            else
              unsafeCallEffect(selection, selection) match {
                case value @ Some(_) => finding = value
                case None            => super.traverse(current)
              }
          case _ => super.traverse(current)
        }
      }
      Finder.traverse(tree)
      finding
    }

    private def transformAlternative(tree: Tree): Tree = {
      val unsafe = unsafeAlternativeEffect(tree)
      val transformed = withScope(transform(tree))
      unsafe match {
        case None => transformed
        case Some(value) =>
          val guarded = Apply(
            Apply(
              selectedConditionalHelperMethod("guardAlternative"),
              List(
                Literal(Constant(value.code)),
                Literal(Constant(value.detail)),
                Literal(Constant(sourceFile)),
                Literal(Constant(sourceLine(value.tree)))
              )
            ),
            List(transformed)
          )
          guarded.setPos(tree.pos)
      }
    }

    private final case class NativeConditionalAlternative(
        condition: Tree,
        reference: String,
        body: Tree,
        line: Int
    )

    private def function0(body: Tree): Tree =
      Function(Nil, transformAlternative(body))

    private def collectNativeConditionalChain(
        original: If,
        firstCondition: Rewrite
    ): (Vector[NativeConditionalAlternative], Tree) = {
      val alternatives = Vector.newBuilder[NativeConditionalAlternative]
      var current = original
      var condition = firstCondition
      var otherwise: Tree = original.elsep
      var done = false
      while (!done) {
        condition.booleanReference match {
          case Some(reference) =>
            alternatives += NativeConditionalAlternative(
              condition.tree,
              reference,
              current.thenp,
              sourceLine(current)
            )
            current.elsep match {
              case next: If =>
                val nextCondition = rewriteExpression(next.cond, None)
                nextCondition.booleanReference match {
                  case Some(_) =>
                    current = next
                    condition = nextCondition
                  case None =>
                    otherwise = current.elsep
                    done = true
                }
              case other =>
                otherwise = other
                done = true
            }
          case None =>
            otherwise = current
            done = true
        }
      }
      alternatives.result() -> otherwise
    }

    private def isUnitLiteral(tree: Tree): Boolean = tree match {
      case Literal(Constant(value)) if value == () => true
      case _                                        => false
    }

    private def rewriteNativeConditionalSingle(
        original: If,
        alternative: NativeConditionalAlternative,
        otherwise: Tree
    ): Tree = {
      val rewritten = Apply(
        Apply(
          Apply(
            selectedConditionalHelperMethod(
              if (inNativeConstructor && isUnitLiteral(otherwise)) "selectSymbolicUnit"
              else "selectSymbolic"
            ),
            List(
              alternative.condition,
              Literal(Constant(alternative.reference)),
              Literal(Constant(sourceFile)),
              Literal(Constant(alternative.line))
            )
          ),
          List(transformAlternative(alternative.body))
        ),
        List(transformAlternative(otherwise))
      )
      rewritten.setPos(original.pos)
    }

    private def rewriteNativeConditionalChain(
        original: If,
        alternatives: Vector[NativeConditionalAlternative],
        otherwise: Tree
    ): Tree = {
      val sequence = Apply(
        scalaSeqApply,
        alternatives.map { value =>
          Apply(
            tuple5Apply,
            List(
              Function(Nil, value.condition),
              Literal(Constant(value.reference)),
              function0(value.body),
              Literal(Constant(sourceFile)),
              Literal(Constant(value.line))
            )
          )
        }.toList
      )
      val rewritten = Apply(
        selectedConditionalHelperMethod(
          if (inNativeConstructor && isUnitLiteral(otherwise)) "selectSymbolicChainUnit"
          else "selectSymbolicChain"
        ),
        List(
          sequence,
          function0(otherwise),
          Literal(Constant(sourceFile)),
          Literal(Constant(sourceLine(otherwise)))
        )
      )
      rewritten.setPos(original.pos)
    }

    private def rewriteIf(original: If): Tree = {
      val condition = rewriteExpression(original.cond, None)
      condition.booleanReference match {
        case Some(_) =>
          val (alternatives, otherwise) =
            collectNativeConditionalChain(original, condition)
          if (alternatives.size == 1)
            rewriteNativeConditionalSingle(original, alternatives.head, otherwise)
          else rewriteNativeConditionalChain(original, alternatives, otherwise)
        case None =>
          val unsupportedReference =
            firstTrackedBoolean(original.cond).orElse(firstTrackedInteger(original.cond))
          val retainedCondition = unsupportedReference match {
            case Some(reference) =>
              unsupportedBoolean(
                reference,
                "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-PREDICATE-UNSUPPORTED",
                "native symbolic conditional predicate is outside the bounded Increment 51 comparison/isPow2 set",
                original.cond,
                condition.tree
              ).tree
            case None => condition.tree
          }
          treeCopy.If(
            original,
            retainedCondition,
            withScope(transform(original.thenp)),
            withScope(transform(original.elsep))
          )
      }
    }

    private def boxingCall(tree: Tree): Boolean = tree match {
      case Apply(fun, _) =>
        val rendered = path(fun)
        val terminal = terminalName(fun)
        terminal == "Option" || terminal == "Some" ||
        rendered.endsWith("Integer.valueOf") ||
        rendered.endsWith("java.lang.Integer.valueOf")
      case TypeApply(Select(_, name), _) if decoded(name) == "asInstanceOf" => true
      case _ => false
    }

    private def rewriteBoxing(original: Tree, reference: String): Rewrite =
      Rewrite(
        curriedCall(
          "compilerBoxing",
          List(
            Literal(Constant(reference)),
            Literal(Constant("native Int value escapes through boxing or a generic container"))
          ) ++ sourceArguments(original),
          super.transform(original),
          original
        )
      )

    private def rewriteUnsupportedKnownCall(
        original: Tree,
        reference: String,
        method: String
    ): Rewrite =
      unsupportedInt(
        reference,
        "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-CALL-UNSUPPORTED",
        s"native Int call '$method' is outside the bounded Increment 50 operation set",
        original,
        super.transform(original)
      )

    private def rewriteExpression(
        tree: Tree,
        requestedName: Option[String]
    ): Rewrite = {
      tree match {
        case application @ Apply(Select(condition, name), List(body))
            if inNativeConstructor && decoded(name) == "generate" =>
          // ValDef roots enter rewriteExpression directly, rather than the
          // Transformer dispatcher below. Dispatch here as well so a native
          // constructor's top-level `condition generate body` is retained.
          return Rewrite(normalizeGenerate(application, condition, body))
        case _ =>
      }

      if (selectedNativeIntParameter(tree))
        return rewriteSelectedNativeIntParameter(tree)

      markerCall(tree, "captureArgument") match {
        case Some((value, name)) =>
          return rewriteMarker(tree, value, name, requestedName)
        case None =>
      }
      shadowCall(tree) match {
        case Some((value, name)) =>
          return rewriteShadowLocal(tree, value, name, requestedName)
        case None =>
      }

      trackedInteger(tree) match {
        case Some(reference) => return rewriteAlias(tree, reference, requestedName)
        case None =>
      }
      trackedBoolean(tree) match {
        case Some(reference) =>
          return Rewrite(super.transform(tree), booleanReference = Some(reference))
        case None =>
      }

      tree match {
        case Apply(Select(left, operatorName), List(right))
            if inNativeConstructor &&
              (decoded(operatorName) == "&&" || decoded(operatorName) == "||") =>
          rewriteBooleanBinary(
            tree,
            left,
            operatorName,
            right,
            decoded(operatorName),
            requestedName
          )
        case Apply(Select(value, operatorName), Nil)
            if inNativeConstructor && decoded(operatorName) == "unary_!" =>
          rewriteBooleanNot(tree, value, requestedName)
        case Select(value, operatorName)
            if inNativeConstructor && decoded(operatorName) == "unary_!" =>
          rewriteBooleanNot(tree, value, requestedName)
        case Apply(Select(value, methodName), Nil)
            if inNativeConstructor && decoded(methodName) == "toInt" =>
          rewriteBooleanToInt(tree, value, requestedName)
        case Select(value, methodName)
            if inNativeConstructor && decoded(methodName) == "toInt" =>
          rewriteBooleanToInt(tree, value, requestedName)
        case Apply(fun, List(bitCount))
            if inNativeConstructor && terminalName(fun) == "UInt" =>
          rewriteBitVectorFactory(tree, fun, bitCount, "compilerUInt")
        case Apply(fun, List(bitCount))
            if inNativeConstructor && terminalName(fun) == "Bits" =>
          rewriteBitVectorFactory(tree, fun, bitCount, "compilerBits")
        case Apply(fun, List(bitCount))
            if inNativeConstructor && terminalName(fun) == "SInt" =>
          rewriteBitVectorFactory(tree, fun, bitCount, "compilerSInt")
        case Apply(fun, List(dataType, depth))
            if inNativeConstructor && terminalName(fun) == "Mem" =>
          rewriteNativeMem(tree, fun, dataType, depth)
        case Apply(fun, List(dataType))
            if inNativeConstructor && terminalName(fun) == "Reg" =>
          rewriteNativeReg(tree, fun, dataType)
        case Apply(fun, List(data))
            if inNativeConstructor && terminalName(fun) == "cloneOf" =>
          rewriteNativeClone(tree, fun, data)
        case Apply(fun, arguments)
            if inNativeConstructor &&
              (terminalName(fun) == "RegNextWhen" || terminalName(fun) == "RegNext") &&
              arguments.nonEmpty =>
          rewriteNativeCopyShape(tree, fun, arguments)
        case Apply(fun, List(dataType))
            if inNativeConstructor && terminalName(fun) == "Stream" &&
              firstTrackedInteger(dataType).nonEmpty =>
          rewriteNativeStream(tree, fun, dataType)
        case Apply(Select(left, operatorName), List(right))
            if Set("^", "===", "=/=").contains(decoded(operatorName)) =>
          rewriteMixedHardwareBinary(
            tree,
            left,
            operatorName,
            right,
            decoded(operatorName)
          ).getOrElse(Rewrite(super.transform(tree)))
        case Apply(Select(left, operatorName), List(right))
            if binaryOperations.contains(decoded(operatorName)) =>
          rewriteBinary(
            tree,
            left,
            operatorName,
            right,
            decoded(operatorName),
            requestedName
          )
        case Apply(Select(left, operatorName), List(right))
            if comparisonOperations.contains(decoded(operatorName)) =>
          rewriteComparison(
            tree,
            left,
            operatorName,
            right,
            decoded(operatorName),
            requestedName
          )
        case Apply(Select(value, operatorName), Nil)
            if decoded(operatorName) == "unary_-" =>
          rewriteUnary(tree, "negate", value, requestedName)
        case Select(value, operatorName) if decoded(operatorName) == "unary_-" =>
          rewriteUnary(tree, "negate", value, requestedName)
        case Apply(fun, arguments) =>
          rewriteStaticMinMax(tree, fun, arguments, requestedName).getOrElse {
            val method = terminalName(fun)
            if (helperOperations.contains(method) && arguments.size == 1)
              rewriteUnary(tree, method, arguments.head, requestedName)
            else if (method == "isPow2" && arguments.size == 1)
              rewritePowerOfTwo(tree, arguments.head, requestedName)
            else {
              firstTrackedInteger(tree) match {
                case Some(reference) if boxingCall(tree) => rewriteBoxing(tree, reference)
                case Some(reference) if unsupportedIntegerCalls.contains(method) =>
                  rewriteUnsupportedKnownCall(tree, reference, method)
                case _ => Rewrite(super.transform(tree), intLiteral = literalInteger(tree).nonEmpty)
              }
            }
          }
        case Select(value, methodName) if helperOperations.contains(decoded(methodName)) =>
          rewriteUnary(tree, decoded(methodName), value, requestedName)
        case Select(_, methodName) if unsupportedIntegerCalls.contains(decoded(methodName)) =>
          firstTrackedInteger(tree)
            .map(rewriteUnsupportedKnownCall(tree, _, decoded(methodName)))
            .getOrElse(Rewrite(super.transform(tree)))
        case _ => Rewrite(super.transform(tree), intLiteral = literalInteger(tree).nonEmpty)
      }
    }

    private def transformNativeConstructor(
        value: ClassDef,
        selection: NativeConstructorSelection
    ): Tree = {
      val parameters = primaryConstructorParameters(value)
      parameters.lift(selection.argumentIndex) match {
        case None =>
          global.reporter.error(
            value.pos,
            "MORPHDL-NATIVE-INT-CONSTRUCTOR-ARGUMENT-MISSING: selected native constructor argument disappeared before transformation"
          )
          super.transform(value)
        case Some(parameter) =>
          val previous = nativeConstructorContext
          nativeConstructorContext = Some(
            NativeConstructorContext(
              className = selection.className,
              parameterName = parameter.name,
              formalName = selection.formalName,
              reference = sourceReference(
                parameter,
                s"argument:${selection.formalName}"
              ),
              parameterLine = sourceLine(parameter),
              hardTypeParameters = parameters.collect {
                case candidate
                    if terminalTypeName(candidate.tpt) == "HardType" =>
                  candidate.name
              }.toSet,
              concreteBooleanParameters = parameters.collect {
                case candidate
                    if terminalTypeName(candidate.tpt) == "Boolean" =>
                  candidate.name
              }.toSet
            )
          )
          try super.transform(value)
          finally nativeConstructorContext = previous
      }
    }

    private def normalizeGenerate(original: Tree, condition: Tree, body: Tree): Tree = {
      val rewrittenCondition = rewriteExpression(condition, None)
      rewrittenCondition.booleanReference match {
        case Some(reference) =>
          val rewritten = Apply(
            Apply(
              selectedConditionalHelperMethod("selectSymbolicGenerate"),
              List(
                rewrittenCondition.tree,
                Literal(Constant(reference)),
                Literal(Constant(sourceFile)),
                Literal(Constant(sourceLine(original)))
              )
            ),
            List(transformAlternative(body))
          )
          rewritten.setPos(original.pos)
        case None =>
          val unsupportedReference =
            firstTrackedBoolean(condition).orElse(firstTrackedInteger(condition))
          val retainedCondition = unsupportedReference match {
            case Some(reference) =>
              unsupportedBoolean(
                reference,
                "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-PREDICATE-UNSUPPORTED",
                "native symbolic generate predicate is outside the bounded Increment 51 comparison/isPow2 set",
                condition,
                rewrittenCondition.tree
              ).tree
            case None => rewrittenCondition.tree
          }
          val conditional = If(
            retainedCondition,
            withScope(transform(body)),
            Literal(Constant(null))
          )
          conditional.setPos(original.pos)
      }
    }

    private def normalizeBooleanMatch(original: Match): Option[Tree] = {
      var whenTrue: Option[Tree] = None
      var whenFalse: Option[Tree] = None
      var supported = true
      original.cases.foreach { value =>
        if (value.guard != EmptyTree) supported = false
        value.pat match {
          case Literal(Constant(true)) if whenTrue.isEmpty =>
            whenTrue = Some(value.body)
          case Literal(Constant(false)) if whenFalse.isEmpty =>
            whenFalse = Some(value.body)
          case _ => supported = false
        }
      }
      if (supported && whenTrue.nonEmpty && whenFalse.nonEmpty && original.cases.size == 2) {
        val conditional = If(original.selector, whenTrue.get, whenFalse.get)
        conditional.setPos(original.pos)
        Some(rewriteIf(conditional))
      } else None
    }

    override def transform(tree: Tree): Tree = tree match {
      case value: ClassDef
          if nativeConstructorSelections.contains(decoded(value.name)) =>
        transformNativeConstructor(
          value,
          nativeConstructorSelections(decoded(value.name))
        )
      case value: ClassDef
          if inNativeConstructor &&
            nestedConstructorShadowsSelectedParameter(value) =>
        global.reporter.error(
          value.pos,
          "MORPHDL-NATIVE-INT-CONSTRUCTOR-LEXICAL-SHADOW-UNSUPPORTED: nested constructor shadows the exact selected native Int parameter"
        )
        withoutNativeConstructorContext(super.transform(value))
      case application @ Apply(Select(condition, name), List(body))
          if inNativeConstructor && decoded(name) == "generate" =>
        normalizeGenerate(application, condition, body)
      case value: Match if inNativeConstructor =>
        normalizeBooleanMatch(value).getOrElse(super.transform(value))
      case template: Template => withScope(super.transform(template))
      case block: Block       => withScope(super.transform(block))
      case function: Function => withScope(super.transform(function))
      case definition: DefDef
          if inNativeConstructor && decoded(definition.name) != "<init>" =>
        withoutNativeConstructorContext {
          withScope(super.transform(definition))
        }
      case definition: DefDef => withScope(super.transform(definition))
      case conditional: If    => rewriteIf(conditional)
      case value: ValDef =>
        if (shadowsSelectedNativeParameter(value)) {
          global.reporter.error(
            value.pos,
            "MORPHDL-NATIVE-INT-CONSTRUCTOR-LEXICAL-SHADOW-UNSUPPORTED: local declaration shadows the exact selected native Int parameter"
          )
        }
        val mutable = value.mods.hasFlag(Flag.MUTABLE)
        val requested = if (mutable) None else Some(decoded(value.name))
        val syntacticShape = inferShape(value.rhs)
        val rewritten = rewriteExpression(value.rhs, requested)
        val rhs =
          if (mutable) {
            // A mutable declaration is rejected against an already retained
            // source reference. Do not manufacture an alias/result reference
            // that cannot exist until the rejected RHS is evaluated.
            firstTrackedInteger(value.rhs).orElse(rewritten.intReference) match {
              case Some(reference) =>
                curriedCall(
                  "compilerMutableInt",
                  List(
                    Literal(Constant(reference)),
                    Literal(Constant(s"mutable variable '${decoded(value.name)}'"))
                  ) ++ sourceArguments(value),
                  rewritten.tree,
                  value.rhs
                )
              case None => rewritten.tree
            }
          } else rewritten.tree
        if (!mutable) {
          rewritten.intReference.foreach(bindInteger(value.name, _))
          rewritten.booleanReference.foreach(bindBoolean(value.name, _))
          bindShape(value.name, syntacticShape)
        }
        treeCopy.ValDef(
          value,
          value.mods,
          value.name,
          super.transform(value.tpt),
          rhs
        )
      case assignment: Assign =>
        val rewritten = rewriteExpression(assignment.rhs, None)
        rewritten.intReference match {
          case Some(reference) =>
            val guarded = curriedCall(
              "compilerMutableInt",
              List(
                Literal(Constant(reference)),
                Literal(Constant("assignment into mutable state"))
              ) ++ sourceArguments(assignment),
              rewritten.tree,
              assignment.rhs
            )
            treeCopy.Assign(assignment, super.transform(assignment.lhs), guarded)
          case None => super.transform(assignment)
        }
      case other => rewriteExpression(other, None).tree
    }
  }

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit = {
      val transformer = new ShadowTransformer(unit)
      if (eligible(unit) || transformer.hasNativeConstructorSelections)
        unit.body = transformer.transform(unit.body)
    }
  }
}
