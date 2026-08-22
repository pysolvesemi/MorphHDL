package morphhdl.compiler

import scala.collection.mutable
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

/**
  * Parser-phase instrumentation for proven shadow-symbolic native Scala `Int`
  * values.
  *
  * The transformation starts only from an explicit Increment 49 selection
  * (`NativeIntShadow.captureArgument`, `NativeIntShadow.captureLocal`, or
  * `shadowInt`). It then propagates a deterministic source reference through
  * bounded native integer operations. Ordinary Int code with no proven source
  * reference is left byte-for-byte equivalent after typing.
  */
final class MorphHdlNativeIntShadowExpressionComponent(val global: Global)
    extends PluginComponent {
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
    val content = Option(unit.source).map(_.content.mkString).getOrElse("")
    !path.contains("/frontend/src/main/scala/") &&
      !path.contains("/morphplugin/src/main/scala/") &&
      (content.contains("NativeIntShadow") || content.contains("shadowInt"))
  }

  private def helperMethod(name: String): Tree = {
    val root = Ident(termNames.ROOTPKG)
    val morphhdl = Select(root, TermName("morphhdl"))
    val frontend = Select(morphhdl, TermName("frontend"))
    val helper = Select(frontend, TermName("NativeIntShadow"))
    Select(helper, TermName(name))
  }

  private final case class Rewrite(
      tree: Tree,
      intReference: Option[String] = None,
      booleanReference: Option[String] = None,
      intLiteral: Boolean = false
  )

  private final class ShadowTransformer(unit: CompilationUnit) extends Transformer {
    private var integerScopes =
      List(mutable.LinkedHashMap.empty[TermName, String])
    private var booleanScopes =
      List(mutable.LinkedHashMap.empty[TermName, String])

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
        .filter(_.nonEmpty)
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

    private def withScope[A](body: => A): A = {
      integerScopes = mutable.LinkedHashMap.empty[TermName, String] :: integerScopes
      booleanScopes = mutable.LinkedHashMap.empty[TermName, String] :: booleanScopes
      try body
      finally {
        integerScopes = integerScopes.tail
        booleanScopes = booleanScopes.tail
      }
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
    ): String = requested.filter(_.nonEmpty).getOrElse {
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

    private def call(name: String, arguments: List[Tree], original: Tree): Tree = {
      val rewritten = Apply(helperMethod(name), arguments)
      rewritten.setPos(original.pos)
    }

    private def curriedCall(
        name: String,
        arguments: List[Tree],
        body: Tree,
        original: Tree
    ): Tree = {
      val rewritten = Apply(Apply(helperMethod(name), arguments), List(body))
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
          unsupportedInt(
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

    override def transform(tree: Tree): Tree = tree match {
      case template: Template => withScope(super.transform(template))
      case block: Block       => withScope(super.transform(block))
      case function: Function => withScope(super.transform(function))
      case definition: DefDef => withScope(super.transform(definition))
      case value: ValDef =>
        val requested = Some(decoded(value.name))
        val rewritten = rewriteExpression(value.rhs, requested)
        val rhs =
          if (value.mods.hasFlag(Flag.MUTABLE)) {
            rewritten.intReference match {
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
        if (!value.mods.hasFlag(Flag.MUTABLE)) {
          rewritten.intReference.foreach(bindInteger(value.name, _))
          rewritten.booleanReference.foreach(bindBoolean(value.name, _))
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
