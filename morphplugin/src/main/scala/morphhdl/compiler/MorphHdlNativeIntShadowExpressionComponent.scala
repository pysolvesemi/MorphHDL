package morphhdl.compiler

import scala.collection.mutable
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

/** Parser-phase instrumentation for proven shadow-symbolic native Scala `Int`
  * values.
  *
  * The transformation starts only from an explicit Increment 49 selection
  * (`NativeIntShadow.captureArgument`, `NativeIntShadow.captureLocal`, or
  * `shadowInt`). It then propagates a deterministic source reference through
  * bounded native integer operations. Increment 51 additionally consumes only
  * the proven Boolean references produced by those operations to retain native
  * Scala conditional alternatives. Ordinary Int and Boolean code with no
  * proven source reference is left equivalent after typing.
  */
final class MorphHdlNativeIntShadowExpressionComponent(val global: Global) extends PluginComponent {
  import global._

  override val phaseName: String = "morphhdl-native-int-shadow-expressions"
  override val runsAfter: List[String] = List("parser")
  override val runsBefore: List[String] =
    List("morphhdl-natural-symbolic-conditionals", "namer")

  private def eligible(unit: CompilationUnit): Boolean = {
    val path = Option(unit.source)
      .flatMap(source => Option(source.file))
      .map(_.path.replace('\\', '/'))
      .getOrElse("")
    val normalizedPath = "/" + path.stripPrefix("/")
    val content = Option(unit.source).map(_.content.mkString).getOrElse("")
    !normalizedPath.contains("/frontend/src/main/scala/") &&
    !normalizedPath.contains("/morphplugin/src/main/scala/") &&
    (
      content.contains("NativeIntShadow") ||
        content.contains("shadowInt")
    )
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

  private final class ShadowTransformer(unit: CompilationUnit) extends Transformer {
    private var integerScopes =
      List(mutable.LinkedHashMap.empty[TermName, String])
    private var booleanScopes =
      List(mutable.LinkedHashMap.empty[TermName, String])
    private var shapeScopes =
      List(mutable.LinkedHashMap.empty[TermName, SyntacticShape])

    private var nativeWidthFunctionStaticBooleans = Set.empty[TermName]
    private var nativeWidthFunctionDepth = 0

    private val binaryOperations = Set("+", "-", "*", "/", "%", "min", "max")
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
      case Ident(name) => decoded(name)
      case Select(base, name) =>
        val prefix = path(base)
        if (prefix.isEmpty) decoded(name) else s"$prefix.${decoded(name)}"
      case This(name) => decoded(name)
      case _          => ""
    }

    private def terminalName(tree: Tree): String = tree match {
      case Ident(name)       => decoded(name)
      case Select(_, name)   => decoded(name)
      case TypeApply(fun, _) => terminalName(fun)
      case _                 => ""
    }

    private def inNativeWidthFunction: Boolean = nativeWidthFunctionDepth > 0

    private def inNativeRuntimeContext: Boolean =
      inNativeWidthFunction

    private def isConcreteBoolean(tree: Tree): Boolean = tree match {
      case Literal(Constant(_: Boolean)) => true
      case Ident(name: TermName) =>
        nativeWidthFunctionStaticBooleans(name)
      case Select(This(_), name: TermName) =>
        nativeWidthFunctionStaticBooleans(name)
      case Apply(Select(value, name), Nil) if decoded(name) == "unary_!" =>
        isConcreteBoolean(value)
      case Select(value, name) if decoded(name) == "unary_!" =>
        isConcreteBoolean(value)
      case Apply(Select(left, name), List(right)) if decoded(name) == "&&" || decoded(name) == "||" =>
        isConcreteBoolean(left) && isConcreteBoolean(right)
      case _ => false
    }

    private def lookupShape(
        name: TermName,
        scopes: List[scala.collection.Map[TermName, SyntacticShape]] = shapeScopes
    ): SyntacticShape =
      scopes
        .collectFirst {
          case scope if scope.contains(name) => scope(name)
        }
        .getOrElse(UnknownShape)

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
        case value: ValDef if !value.mods.hasFlag(Flag.MUTABLE) && value.rhs != EmptyTree =>
          val shape = inferShape(value.rhs, local :: inherited)
          local.update(value.name, shape)
        case _ =>
      }
      if (local.values.exists(_ != UnknownShape)) RecordShape(local.toMap)
      else UnknownShape
    }

    private def inferShape(
        tree: Tree,
        scopes: List[scala.collection.Map[TermName, SyntacticShape]] = shapeScopes
    ): SyntacticShape =
      anonymousTemplate(tree)
        .map(recordShape(_, scopes))
        .getOrElse {
          tree match {
            case Ident(name: TermName)           => lookupShape(name, scopes)
            case Select(This(_), name: TermName) => lookupShape(name, scopes)
            case Select(base, name: TermName) =>
              inferShape(base, scopes) match {
                case RecordShape(members) =>
                  members.getOrElse(name, UnknownShape)
                case UIntShape if Set("resized", "resize", "asUInt").contains(decoded(name)) =>
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
      shapeScopes = mutable.LinkedHashMap.empty[TermName, SyntacticShape] :: shapeScopes
      try body
      finally {
        integerScopes = integerScopes.tail
        booleanScopes = booleanScopes.tail
        shapeScopes = shapeScopes.tail
      }
    }

    private def trackedInteger(tree: Tree): Option[String] = tree match {
      case Ident(name: TermName)           => lookupInteger(name)
      case Select(This(_), name: TermName) => lookupInteger(name)
      case _                               => None
    }

    private def trackedBoolean(tree: Tree): Option[String] = tree match {
      case Ident(name: TermName)           => lookupBoolean(name)
      case Select(This(_), name: TermName) => lookupBoolean(name)
      case _                               => None
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
      if (inNativeRuntimeContext) helperMethod(name) else frontendHelperMethod(name)

    private def selectedConditionalHelperMethod(name: String): Tree =
      if (inNativeRuntimeContext) helperMethod(name)
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
      else
        literalInteger(tree) match {
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
      case Apply(Select(value, name), Nil) if decoded(name) == "bits" || decoded(name) == "bit" => Some(value)
      case Select(value, name) if decoded(name) == "bits" || decoded(name) == "bit"             => Some(value)
      case Apply(fun, List(value)) if terminalName(fun) == "BitCount"                           => Some(value)
      case _                                                                                    => None
    }

    private def rebuildBitCount(original: Tree, value: Tree): Tree = {
      val result = original match {
        case Apply(Select(_, name), Nil) => Apply(Select(value, name), Nil)
        case Select(_, name)             => Select(value, name)
        case Apply(fun, List(_))         => Apply(super.transform(fun), List(value))
        case _                           => original
      }
      result.setPos(original.pos)
    }

    private def rewriteNativeWidthOf(
        original: Tree,
        fun: Tree,
        data: Tree,
        requestedName: Option[String]
    ): Rewrite = {
      val reference = sourceReference(original, "widthOf")
      val transformedData = transform(data)
      val native = Apply(super.transform(fun), List(transformedData))
      native.setPos(original.pos)
      Rewrite(
        call(
          "compilerWidthOf",
          List(
            transformedData,
            native,
            Literal(Constant(reference)),
            Literal(Constant(resultName(requestedName, "widthOf", original)))
          ) ++ sourceArguments(original),
          original
        ),
        intReference = Some(reference)
      )
    }

    private def rewriteNativeResize(
        original: Tree,
        source: Tree,
        methodName: Name,
        widthTree: Tree
    ): Rewrite = {
      val width = rewriteExpression(widthTree, None)
      width.intReference match {
        case None => Rewrite(super.transform(original))
        case Some(reference) =>
          val native = Apply(
            Select(transform(source), methodName),
            List(width.tree)
          )
          native.setPos(original.pos)
          Rewrite(
            curriedCall(
              "compilerResize",
              List(Literal(Constant(reference))) ++ sourceArguments(original),
              native,
              original
            )
          )
      }
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

    private def rewriteNativeReg(
        original: Tree,
        fun: Tree,
        dataType: Tree
    ): Rewrite = {
      val transformedType = transform(dataType)
      val native = Apply(super.transform(fun), List(transformedType))
      native.setPos(original.pos)
      Rewrite(
        curriedCall(
          "compilerReg",
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
      if (method.endsWith("_=") || method.endsWith("+="))
        Some(
          effect(
            "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-MUTABLE-STATE-UNSUPPORTED",
            s"setter or update call '$rendered' mutates Scala state and is not permitted inside a captured native symbolic alternative",
            original
          )
        )
      else if (
        (original.isInstanceOf[Apply] && ioEffectMethods.contains(method)) ||
        rendered.contains("Console") ||
        rendered.contains("System.out") || rendered.contains("System.err") ||
        rendered.contains("java.io") || rendered.contains("java.nio.file") ||
        rendered.contains("scala.io") || rendered.contains("Socket") ||
        rendered.contains("InputStream") || rendered.contains("OutputStream")
      )
        Some(
          effect(
            "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-IO-UNSUPPORTED",
            s"I/O call '$rendered' is not permitted while capturing a native symbolic alternative",
            original
          )
        )
      else if (
        reflectionEffectMethods.contains(method) ||
        rendered.contains("scala.reflect") || rendered.contains("java.lang.reflect")
      )
        Some(
          effect(
            "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-REFLECTION-UNSUPPORTED",
            s"reflection call '$rendered' is not permitted while capturing a native symbolic alternative",
            original
          )
        )
      else if (
        nondeterministicEffectMethods.contains(method) &&
        (rendered.contains("Random") || rendered.contains("System") ||
          rendered.contains("UUID") || rendered.contains("Instant") ||
          rendered.contains("LocalDate") || rendered.contains("ZonedDate"))
      )
        Some(
          effect(
            "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-NONDETERMINISM-UNSUPPORTED",
            s"nondeterministic call '$rendered' is not permitted while capturing a native symbolic alternative",
            original
          )
        )
      else if (arbitraryEffectMethods.contains(method))
        Some(
          effect(
            "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-ARBITRARY-EFFECT-UNSUPPORTED",
            s"Scala effect '$rendered' is outside the Increment 52 safe structural contract",
            original
          )
        )
      else None
    }

    private def unsafeAlternativeEffect(
        tree: Tree
    ): Option[UnsafeAlternativeEffect] = {
      var finding: Option[UnsafeAlternativeEffect] = None
      object Finder extends Traverser {
        override def traverse(current: Tree): Unit = if (finding.isEmpty) current match {
          case value: ValDef if value.mods.hasFlag(Flag.MUTABLE) =>
            finding = Some(
              effect(
                "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-MUTABLE-STATE-UNSUPPORTED",
                s"mutable Scala variable '${decoded(value.name)}' is not permitted inside a captured native symbolic alternative",
                current
              )
            )
          case _: Assign =>
            finding = Some(
              effect(
                "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-MUTABLE-STATE-UNSUPPORTED",
                "assignment to Scala mutable state is not permitted inside a captured native symbolic alternative",
                current
              )
            )
          case _: Return =>
            finding = Some(
              effect(
                "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-CONTROL-EFFECT-UNSUPPORTED",
                "return is outside the safe native symbolic alternative contract",
                current
              )
            )
          case _: Throw =>
            finding = Some(
              effect(
                "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-CONTROL-EFFECT-UNSUPPORTED",
                "throw is outside the safe native symbolic alternative contract",
                current
              )
            )
          case _: Try =>
            finding = Some(
              effect(
                "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-CONTROL-EFFECT-UNSUPPORTED",
                "try/catch/finally is outside the safe native symbolic alternative contract",
                current
              )
            )
          case _: LabelDef =>
            finding = Some(
              effect(
                "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-UNBOUNDED-LOOP-UNSUPPORTED",
                "while/do-style mutable loops are not permitted; use a finite immutable range loop",
                current
              )
            )
          case application @ Apply(fun, _) =>
            unsafeCallEffect(fun, application) match {
              case value @ Some(_) => finding = value
              case None            => super.traverse(current)
            }
          case selection @ Select(_, _) =>
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
      case _                                       => false
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
              if (inNativeRuntimeContext && isUnitLiteral(otherwise)) "selectSymbolicUnit"
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
          if (inNativeRuntimeContext && isUnitLiteral(otherwise)) "selectSymbolicChainUnit"
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
      case _                                                                => false
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
        method: String,
        nativeTree: Tree
    ): Rewrite =
      unsupportedInt(
        reference,
        "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-CALL-UNSUPPORTED",
        s"native Int call '$method' is outside the bounded Increment 50 operation set",
        original,
        nativeTree
      )

    private def rewriteUnsupportedKnownCall(
        original: Tree,
        reference: String,
        method: String
    ): Rewrite =
      rewriteUnsupportedKnownCall(
        original,
        reference,
        method,
        super.transform(original)
      )

    private def rewriteUnsupportedReceiverCall(
        original: Tree,
        receiver: Tree,
        methodName: Name,
        arguments: Option[List[Tree]],
        method: String
    ): Rewrite = {
      val rewrittenReceiver = rewriteExpression(receiver, None)
      val rewrittenArguments = arguments.getOrElse(Nil).map { argument =>
        rewriteExpression(argument, None)
      }
      val selected = Select(rewrittenReceiver.tree, methodName)
      selected.setPos(original.pos)
      val native: Tree = arguments match {
        case Some(_) =>
          val applied = Apply(selected, rewrittenArguments.map(_.tree))
          applied.setPos(original.pos)
        case None => selected
      }
      val reference = rewrittenReceiver.intReference.orElse(
        rewrittenArguments.collectFirst {
          case value if value.intReference.nonEmpty => value.intReference.get
        }
      )
      reference match {
        case Some(value) =>
          rewriteUnsupportedKnownCall(original, value, method, native)
        case None =>
          Rewrite(native, intLiteral = literalInteger(original).nonEmpty)
      }
    }

    private def rewriteUnsupportedFunctionCall(
        original: Tree,
        fun: Tree,
        arguments: List[Tree],
        method: String
    ): Rewrite = {
      val rewrittenArguments = arguments.map { argument =>
        rewriteExpression(argument, None)
      }
      val native = Apply(super.transform(fun), rewrittenArguments.map(_.tree))
      native.setPos(original.pos)
      rewrittenArguments.collectFirst {
        case value if value.intReference.nonEmpty => value.intReference.get
      } match {
        case Some(reference) =>
          rewriteUnsupportedKnownCall(original, reference, method, native)
        case None =>
          Rewrite(native, intLiteral = literalInteger(original).nonEmpty)
      }
    }

    private def rewriteExpression(
        tree: Tree,
        requestedName: Option[String]
    ): Rewrite = {
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
        case None            =>
      }
      trackedBoolean(tree) match {
        case Some(reference) =>
          return Rewrite(super.transform(tree), booleanReference = Some(reference))
        case None =>
      }

      tree match {
        case application @ Apply(fun, List(data)) if inNativeWidthFunction && terminalName(fun) == "widthOf" =>
          rewriteNativeWidthOf(application, fun, data, requestedName)
        case application @ Apply(Select(source, methodName), List(width))
            if inNativeRuntimeContext && decoded(methodName) == "resize" =>
          rewriteNativeResize(application, source, methodName, width)
        // ValDef initializers enter rewriteExpression directly and therefore
        // do not reach the transform-level generate case below.
        case application @ Apply(Select(condition, name), List(body))
            if inNativeRuntimeContext && decoded(name) == "generate" =>
          Rewrite(normalizeGenerate(application, condition, body))
        case Apply(Select(left, operatorName), List(right))
            if inNativeRuntimeContext &&
              (decoded(operatorName) == "&&" || decoded(operatorName) == "||") =>
          rewriteBooleanBinary(
            tree,
            left,
            operatorName,
            right,
            decoded(operatorName),
            requestedName
          )
        case Apply(Select(value, operatorName), Nil) if inNativeRuntimeContext && decoded(operatorName) == "unary_!" =>
          rewriteBooleanNot(tree, value, requestedName)
        case Select(value, operatorName) if inNativeRuntimeContext && decoded(operatorName) == "unary_!" =>
          rewriteBooleanNot(tree, value, requestedName)
        case Apply(Select(value, methodName), Nil) if inNativeRuntimeContext && decoded(methodName) == "toInt" =>
          rewriteBooleanToInt(tree, value, requestedName)
        case Select(value, methodName) if inNativeRuntimeContext && decoded(methodName) == "toInt" =>
          rewriteBooleanToInt(tree, value, requestedName)
        case Apply(fun, List(bitCount))
            if terminalName(fun) == "UInt" &&
              (inNativeRuntimeContext || firstTrackedInteger(bitCount).nonEmpty) =>
          rewriteBitVectorFactory(tree, fun, bitCount, "compilerUInt")
        case Apply(fun, List(bitCount))
            if terminalName(fun) == "Bits" &&
              (inNativeRuntimeContext || firstTrackedInteger(bitCount).nonEmpty) =>
          rewriteBitVectorFactory(tree, fun, bitCount, "compilerBits")
        case Apply(fun, List(bitCount))
            if terminalName(fun) == "SInt" &&
              (inNativeRuntimeContext || firstTrackedInteger(bitCount).nonEmpty) =>
          rewriteBitVectorFactory(tree, fun, bitCount, "compilerSInt")
        case Apply(fun, List(dataType)) if inNativeRuntimeContext && terminalName(fun) == "Reg" =>
          rewriteNativeReg(tree, fun, dataType)
        case Apply(fun, List(data)) if inNativeRuntimeContext && terminalName(fun) == "cloneOf" =>
          rewriteNativeClone(tree, fun, data)
        case Apply(fun, arguments)
            if inNativeRuntimeContext &&
              (terminalName(fun) == "RegNextWhen" || terminalName(fun) == "RegNext") &&
              arguments.nonEmpty =>
          rewriteNativeCopyShape(tree, fun, arguments)
        case Apply(Select(left, operatorName), List(right)) if binaryOperations.contains(decoded(operatorName)) =>
          rewriteBinary(
            tree,
            left,
            operatorName,
            right,
            decoded(operatorName),
            requestedName
          )
        case Apply(Select(left, operatorName), List(right)) if comparisonOperations.contains(decoded(operatorName)) =>
          rewriteComparison(
            tree,
            left,
            operatorName,
            right,
            decoded(operatorName),
            requestedName
          )
        case Apply(Select(value, operatorName), Nil) if decoded(operatorName) == "unary_-" =>
          rewriteUnary(tree, "negate", value, requestedName)
        case Select(value, operatorName) if decoded(operatorName) == "unary_-" =>
          rewriteUnary(tree, "negate", value, requestedName)
        case Apply(Select(receiver, methodName), arguments) if unsupportedIntegerCalls.contains(decoded(methodName)) =>
          rewriteUnsupportedReceiverCall(
            tree,
            receiver,
            methodName,
            Some(arguments),
            decoded(methodName)
          )
        case Apply(fun, arguments) =>
          rewriteStaticMinMax(tree, fun, arguments, requestedName).getOrElse {
            val method = terminalName(fun)
            if (helperOperations.contains(method) && arguments.size == 1)
              rewriteUnary(tree, method, arguments.head, requestedName)
            else if (method == "isPow2" && arguments.size == 1)
              rewritePowerOfTwo(tree, arguments.head, requestedName)
            else if (unsupportedIntegerCalls.contains(method))
              rewriteUnsupportedFunctionCall(tree, fun, arguments, method)
            else {
              firstTrackedInteger(tree) match {
                case Some(reference) if boxingCall(tree) => rewriteBoxing(tree, reference)
                case _ => Rewrite(super.transform(tree), intLiteral = literalInteger(tree).nonEmpty)
              }
            }
          }
        case Select(value, methodName) if helperOperations.contains(decoded(methodName)) =>
          rewriteUnary(tree, decoded(methodName), value, requestedName)
        case Select(receiver, methodName) if unsupportedIntegerCalls.contains(decoded(methodName)) =>
          rewriteUnsupportedReceiverCall(
            tree,
            receiver,
            methodName,
            None,
            decoded(methodName)
          )
        case _ => Rewrite(super.transform(tree), intLiteral = literalInteger(tree).nonEmpty)
      }
    }

    private def stableNativeWidthRoot(tree: Tree): Boolean = tree match {
      case Ident(_)        => true
      case This(_)         => true
      case Select(base, _) => stableNativeWidthRoot(base)
      case Typed(value, _) => stableNativeWidthRoot(value)
      case _               => false
    }

    /** Discover direct `widthOf(Data)` roots in one native method body. Nested
      * definitions own independent lifetimes and are deliberately excluded.
      */
    private def nativeWidthRoots(tree: Tree): Vector[Tree] = {
      val found = mutable.ArrayBuffer.empty[Tree]
      object Finder extends Traverser {
        override def traverse(current: Tree): Unit = current match {
          case _: DefDef | _: ClassDef | _: ModuleDef | _: Function =>
          case Apply(fun, List(data)) if terminalName(fun) == "widthOf" =>
            if (!stableNativeWidthRoot(data)) {
              global.reporter.error(
                current.pos,
                "MORPHDL-NATIVE-WIDTH-FUNCTION-ROOT-UNSTABLE: widthOf provenance requires an Ident/Select Data root"
              )
            } else found += data
          case _ => super.traverse(current)
        }
      }
      Finder.traverse(tree)
      found
        .groupBy(path)
        .toVector
        .sortBy(_._1)
        .map(_._2.head)
    }

    private def transformNativeWidthFunction(
        definition: DefDef,
        roots: Vector[Tree]
    ): Tree = {
      val previousStaticBooleans = nativeWidthFunctionStaticBooleans
      nativeWidthFunctionStaticBooleans = definition.vparamss.flatten.collect {
        case parameter if terminalName(parameter.tpt) == "Boolean" =>
          parameter.name
      }.toSet
      nativeWidthFunctionDepth += 1
      try {
        val transformed = withScope(super.transform(definition)).asInstanceOf[DefDef]
        val rootSequence = Apply(
          scalaSeqApply,
          roots.map(root => super.transform(root).duplicate).toList
        )
        val wrapped = Apply(
          Apply(
            helperMethod("withWidthFunctionBoundary"),
            List(rootSequence) ++ sourceArguments(definition)
          ),
          List(transformed.rhs)
        )
        wrapped.setPos(definition.rhs.pos)
        treeCopy.DefDef(
          transformed,
          transformed.mods,
          transformed.name,
          transformed.tparams,
          transformed.vparamss,
          transformed.tpt,
          wrapped
        )
      } finally {
        nativeWidthFunctionDepth -= 1
        nativeWidthFunctionStaticBooleans = previousStaticBooleans
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

    override def transform(tree: Tree): Tree = tree match {
      case template: Template => withScope(super.transform(template))
      case block: Block       => withScope(super.transform(block))
      case function: Function => withScope(super.transform(function))
      case definition: DefDef if decoded(definition.name) != "<init>" =>
        val roots = nativeWidthRoots(definition.rhs)
        if (roots.nonEmpty) transformNativeWidthFunction(definition, roots)
        else withScope(super.transform(definition))
      case definition: DefDef => withScope(super.transform(definition))
      case conditional: If    => rewriteIf(conditional)
      case value: ValDef =>
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
    override def apply(unit: CompilationUnit): Unit =
      if (eligible(unit))
        unit.body = new ShadowTransformer(unit).transform(unit.body)
  }
}
