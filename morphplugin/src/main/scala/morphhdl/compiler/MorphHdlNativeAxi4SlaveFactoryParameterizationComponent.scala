package morphhdl.compiler

import scala.collection.mutable
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

/**
  * MorphHDL-owned parser-phase bridge for symbolic native-Int addresses passed
  * to the real, untouched SpinalHDL `Axi4SlaveFactory`.
  *
  * This phase does not implement a bus factory. It instruments ordinary calls
  * on an exact native factory object so the concrete `BigInt` entering
  * `BusSlaveFactory` retains definition-side parameter provenance. It also
  * instruments only the native `is(address.address)` case-key use inside
  * `Axi4SlaveFactory.scala`; the factory's own delayed build, grouping,
  * register actions, handshakes and switch construction remain authoritative.
  */
final class MorphHdlNativeAxi4SlaveFactoryParameterizationComponent(
    val global: Global
) extends PluginComponent {
  import global._

  override val phaseName: String =
    "morphhdl-native-axi4-slave-factory-parameterization"
  override val runsAfter: List[String] = List("parser")
  override val runsBefore: List[String] =
    List("morphhdl-native-int-shadow-expressions", "namer")

  private def normalizedPath(unit: CompilationUnit): String =
    "/" + Option(unit.source)
      .flatMap(source => Option(source.file))
      .map(_.path.replace('\\', '/'))
      .getOrElse("")
      .stripPrefix("/")

  private def eligible(unit: CompilationUnit): Boolean = {
    val path = normalizedPath(unit)
    val content = Option(unit.source).map(_.content.mkString).getOrElse("")
    !path.contains("/morphplugin/src/main/scala/") &&
      !path.contains("/morphruntime/src/main/scala/") &&
      !path.contains("/frontend/src/main/scala/") &&
      (
        path.endsWith(
          "/lib/src/main/scala/spinal/lib/bus/amba4/axi/Axi4SlaveFactory.scala"
        ) || content.contains("Axi4SlaveFactory")
      )
  }

  private def nativeIntRuntimeMethod(name: String): Tree = {
    val root = Ident(termNames.ROOTPKG)
    val spinal = Select(root, TermName("spinal"))
    val core = Select(spinal, TermName("core"))
    val helper = Select(core, TermName("ExternalNativeIntCompilerRuntime"))
    Select(helper, TermName(name))
  }

  private def axiRuntimeMethod(name: String): Tree = {
    val root = Ident(termNames.ROOTPKG)
    val spinal = Select(root, TermName("spinal"))
    val core = Select(spinal, TermName("core"))
    val helper = Select(
      core,
      TermName("ExternalNativeAxi4SlaveFactoryParameterization")
    )
    Select(helper, TermName(name))
  }

  private final case class TrackedInt(
      reference: String,
      stableName: String,
      root: Boolean
  )

  private final case class IntRewrite(
      tree: Tree,
      reference: Option[String],
      literal: Boolean = false
  )

  private final class AxiTransformer(unit: CompilationUnit) extends Transformer {
    private var integerScopes =
      List(mutable.LinkedHashMap.empty[TermName, TrackedInt])
    private var factoryScopes =
      List(mutable.LinkedHashSet.empty[TermName])

    private val binaryOperations = Set("+", "-", "*", "/", "%", "min", "max")

    private val singleWordAddressMethods = Set(
      "read",
      "write",
      "readAndWrite",
      "onRead",
      "onWrite",
      "isReading",
      "isWriting",
      "createWriteOnly",
      "createReadOnly",
      "createReadAndWrite",
      "createReadAndClearOnSet",
      "readAndClearOnSet",
      "clearOnSet",
      "createReadAndSetOnSet",
      "readAndSetOnSet",
      "setOnSet",
      "createReadAndClearOnRead",
      "readAndClearOnRead",
      "createReadAndSetOnRead",
      "readAndSetOnRead",
      "createReadAndClearOnWrite",
      "readAndClearOnWrite",
      "createReadAndSetOnWrite",
      "readAndSetOnWrite",
      "drive",
      "driveAndRead",
      "driveAndReadAt",
      "driveAndReadAtOffset",
      "driveAndReadAtOffsetAndSet",
      "readAndWriteAt",
      "readAndWriteAtOffset"
    )

    private val multiWordAddressMethods = Set(
      "readMultiWord",
      "writeMultiWord",
      "readAndWriteMultiWord"
    )

    private def sourceFile: String =
      Option(unit.source)
        .flatMap(source => Option(source.file))
        .map(_.path)
        .filter(_.nonEmpty)
        .getOrElse("<native-axi4-slave-factory>")

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

    private def terminalName(tree: Tree): String = tree match {
      case Ident(name)       => decoded(name)
      case Select(_, name)   => decoded(name)
      case TypeApply(fun, _) => terminalName(fun)
      case _                 => ""
    }

    private def stableAddressName(tree: Tree): String =
      s"axi4_factory_address_${sourceLine(tree)}_${sourceColumn(tree)}"

    private def lookupInteger(name: TermName): Option[TrackedInt] =
      integerScopes.collectFirst {
        case scope if scope.contains(name) => scope(name)
      }

    private def bindInteger(name: TermName, value: TrackedInt): Unit =
      integerScopes.head.update(name, value)

    private def lookupFactory(name: TermName): Boolean =
      factoryScopes.exists(_.contains(name))

    private def bindFactory(name: TermName): Unit =
      factoryScopes.head += name

    private def withScope[T](body: => T): T = {
      integerScopes = mutable.LinkedHashMap.empty[TermName, TrackedInt] :: integerScopes
      factoryScopes = mutable.LinkedHashSet.empty[TermName] :: factoryScopes
      try body
      finally {
        integerScopes = integerScopes.tail
        factoryScopes = factoryScopes.tail
      }
    }

    private def typeTerminalName(tree: Tree): String = tree match {
      case Ident(name)                => decoded(name)
      case Select(_, name)            => decoded(name)
      case AppliedTypeTree(base, _)   => typeTerminalName(base)
      case _                          => terminalName(tree)
    }

    private def isNativeIntParameter(value: ValDef): Boolean =
      typeTerminalName(value.tpt) == "Int"

    private def constructorParameters(value: ClassDef): List[ValDef] =
      value.impl.body.collectFirst {
        case method: DefDef if decoded(method.name) == "<init>" =>
          method.vparamss.flatten
      }.getOrElse(Nil)

    private def isNativeAxiSource: Boolean =
      sourceFile.replace('\\', '/').endsWith(
        "/lib/src/main/scala/spinal/lib/bus/amba4/axi/Axi4SlaveFactory.scala"
      )

    private def isAddressField(tree: Tree): Boolean = tree match {
      case Select(Ident(owner), field)
          if decoded(owner) == "address" && decoded(field) == "address" => true
      case Select(Select(This(_), owner), field)
          if decoded(owner) == "address" && decoded(field) == "address" => true
      case _ => false
    }

    private def transformNativeCaseKey(original: Apply): Tree = {
      val argument = original.args.head
      val bridged = Apply(
        axiRuntimeMethod("compilerCaseKey"),
        List(super.transform(argument)) ++ sourceArguments(original)
      )
      bridged.setPos(argument.pos)
      val result = treeCopy.Apply(
        original,
        super.transform(original.fun),
        List(bridged)
      )
      result.setPos(original.pos)
    }

    private def literalInteger(tree: Tree): Option[Int] = tree match {
      case Literal(Constant(value: Int))   => Some(value)
      case Literal(Constant(value: Short)) => Some(value.toInt)
      case Literal(Constant(value: Byte))  => Some(value.toInt)
      case _                               => None
    }

    private def trackedSelection(tree: Tree): Option[TrackedInt] = tree match {
      case Ident(name: TermName)             => lookupInteger(name)
      case Select(This(_), name: TermName)   => lookupInteger(name)
      case _                                 => None
    }

    private def trackRoot(tree: Tree, value: TrackedInt): Tree = {
      val call = Apply(
        nativeIntRuntimeMethod("compilerTrackArgument"),
        List(
          super.transform(tree),
          Literal(Constant(value.stableName)),
          Literal(Constant(value.reference))
        ) ++ sourceArguments(tree)
      )
      call.setPos(tree.pos)
    }

    private def compilerBinary(
        original: Tree,
        operation: String,
        left: IntRewrite,
        right: IntRewrite,
        requestedName: Option[String]
    ): IntRewrite = {
      val resultReference = sourceReference(
        original,
        s"axi4-address-expression:$operation"
      )
      val arguments = List(
        Literal(Constant(operation)),
        left.tree,
        Literal(
          Constant(
            left.reference.getOrElse(
              sourceReference(original, "axi4-address-left-literal")
            )
          )
        ),
        Literal(Constant(left.literal)),
        right.tree,
        Literal(
          Constant(
            right.reference.getOrElse(
              sourceReference(original, "axi4-address-right-literal")
            )
          )
        ),
        Literal(Constant(right.literal)),
        Literal(Constant(resultReference)),
        Literal(Constant(requestedName.getOrElse("axi4Address")))
      ) ++ sourceArguments(original)
      val call = Apply(nativeIntRuntimeMethod("compilerBinary"), arguments)
      call.setPos(original.pos)
      IntRewrite(call, Some(resultReference))
    }

    private def compilerUnary(
        original: Tree,
        operation: String,
        operand: IntRewrite,
        requestedName: Option[String]
    ): IntRewrite = {
      val resultReference = sourceReference(
        original,
        s"axi4-address-expression:$operation"
      )
      val arguments = List(
        Literal(Constant(operation)),
        operand.tree,
        Literal(Constant(operand.reference.get)),
        Literal(Constant(resultReference)),
        Literal(Constant(requestedName.getOrElse("axi4Address")))
      ) ++ sourceArguments(original)
      val call = Apply(nativeIntRuntimeMethod("compilerUnary"), arguments)
      call.setPos(original.pos)
      IntRewrite(call, Some(resultReference))
    }

    private def rewriteInt(
        tree: Tree,
        requestedName: Option[String] = None
    ): IntRewrite = tree match {
      case value if literalInteger(value).nonEmpty =>
        IntRewrite(super.transform(value), None, literal = true)
      case value @ Ident(_: TermName) =>
        trackedSelection(value) match {
          case Some(binding) if binding.root =>
            IntRewrite(trackRoot(value, binding), Some(binding.reference))
          case Some(binding) =>
            IntRewrite(super.transform(value), Some(binding.reference))
          case None => IntRewrite(super.transform(value), None)
        }
      case value @ Select(This(_), _: TermName) =>
        trackedSelection(value) match {
          case Some(binding) if binding.root =>
            IntRewrite(trackRoot(value, binding), Some(binding.reference))
          case Some(binding) =>
            IntRewrite(super.transform(value), Some(binding.reference))
          case None => IntRewrite(super.transform(value), None)
        }
      case original @ Apply(Select(leftTree, operationName), List(rightTree))
          if binaryOperations.contains(decoded(operationName)) =>
        val left = rewriteInt(leftTree)
        val right = rewriteInt(rightTree)
        if (left.reference.nonEmpty || right.reference.nonEmpty) {
          if (left.reference.isEmpty && !left.literal) {
            global.reporter.error(
              leftTree.pos,
              "MORPHDL-NATIVE-AXI4-ADDRESS-OPERAND-UNPROVEN: left address operand is neither a tracked native Int nor a literal"
            )
            IntRewrite(super.transform(original), None)
          } else if (right.reference.isEmpty && !right.literal) {
            global.reporter.error(
              rightTree.pos,
              "MORPHDL-NATIVE-AXI4-ADDRESS-OPERAND-UNPROVEN: right address operand is neither a tracked native Int nor a literal"
            )
            IntRewrite(super.transform(original), None)
          } else {
            compilerBinary(
              original,
              decoded(operationName),
              left,
              right,
              requestedName
            )
          }
        } else IntRewrite(super.transform(original), None)
      case original @ Apply(Select(valueTree, operationName), Nil)
          if decoded(operationName) == "unary_-" =>
        val value = rewriteInt(valueTree)
        value.reference match {
          case Some(_) => compilerUnary(original, "negate", value, requestedName)
          case None    => IntRewrite(super.transform(original), None)
        }
      case original @ Select(valueTree, operationName)
          if decoded(operationName) == "unary_-" =>
        val value = rewriteInt(valueTree)
        value.reference match {
          case Some(_) => compilerUnary(original, "negate", value, requestedName)
          case None    => IntRewrite(super.transform(original), None)
        }
      case original @ Apply(fun, List(argument))
          if terminalName(fun) == "BigInt" =>
        rewriteInt(argument, requestedName)
      case other => IntRewrite(super.transform(other), None)
    }

    private def containsTrackedInteger(tree: Tree): Boolean = {
      var found = false
      object Finder extends Traverser {
        override def traverse(current: Tree): Unit = if (!found) {
          if (trackedSelection(current).nonEmpty) found = true
          else super.traverse(current)
        }
      }
      Finder.traverse(tree)
      found
    }

    private def isFactoryConstruction(tree: Tree): Boolean = tree match {
      case Apply(fun, _) if terminalName(fun) == "Axi4SlaveFactory" => true
      case Apply(Select(New(tpt), constructor), _)
          if decoded(constructor) == "<init>" &&
            typeTerminalName(tpt) == "Axi4SlaveFactory" => true
      case TypeApply(fun, _) => isFactoryConstruction(fun)
      case _ => false
    }

    private def isFactoryReceiver(tree: Tree): Boolean = tree match {
      case Ident(name: TermName)           => lookupFactory(name)
      case Select(This(_), name: TermName) => lookupFactory(name)
      case value if isFactoryConstruction(value) => true
      case _ => false
    }

    private def namedArgument(tree: Tree): Option[(String, Tree)] = tree match {
      case AssignOrNamedArg(Ident(name), value) => Some(decoded(name) -> value)
      case _                                    => None
    }

    private def rebuildArgument(original: Tree, value: Tree): Tree = original match {
      case named: AssignOrNamedArg =>
        treeCopy.AssignOrNamedArg(
          named,
          super.transform(named.lhs),
          value
        )
      case _ => value
    }

    private def candidateIndices(
        method: String,
        arguments: List[Tree]
    ): Set[Int] = {
      val named = arguments.zipWithIndex.collect {
        case (argument, index)
            if namedArgument(argument).exists(_._1 == "address") => index
      }.toSet
      if (named.nonEmpty) named
      else method match {
        case "onRead" | "onWrite" | "isReading" | "isWriting" => Set(0)
        case "read" | "write" => Set(0, 1).filter(_ < arguments.size)
        case _ => Set(1).filter(_ < arguments.size)
      }
    }

    private def argumentValue(tree: Tree): Tree =
      namedArgument(tree).map(_._2).getOrElse(tree)

    private def transformFactoryCall(
        original: Apply,
        receiver: Tree,
        methodName: TermName,
        arguments: List[Tree]
    ): Tree = {
      val method = decoded(methodName)
      val candidates = candidateIndices(method, arguments)
      val symbolic = candidates.toVector.sorted.flatMap { index =>
        val value = argumentValue(arguments(index))
        val rewritten = rewriteInt(value, Some("axi4Address"))
        rewritten.reference.map(reference => (index, value, rewritten, reference))
      }

      if (symbolic.size > 1) {
        global.reporter.error(
          original.pos,
          "MORPHDL-NATIVE-AXI4-ADDRESS-AMBIGUOUS: one factory call exposes multiple tracked native-Int address candidates"
        )
        super.transform(original)
      } else if (symbolic.isEmpty) {
        val unsupported = candidates.toVector.sorted.collectFirst {
          case index if containsTrackedInteger(argumentValue(arguments(index))) =>
            argumentValue(arguments(index))
        }
        unsupported.foreach { value =>
          global.reporter.error(
            value.pos,
            "MORPHDL-NATIVE-AXI4-ADDRESS-EXPRESSION-UNSUPPORTED: symbolic factory address is outside the bounded native-Int arithmetic contract"
          )
        }
        treeCopy.Apply(
          original,
          treeCopy.Select(
            original.fun.asInstanceOf[Select],
            transform(receiver),
            methodName
          ),
          arguments.map(transform)
        )
      } else {
        val (selectedIndex, selectedValue, rewritten, reference) = symbolic.head
        if (multiWordAddressMethods.contains(method)) {
          global.reporter.error(
            selectedValue.pos,
            "MORPHDL-NATIVE-AXI4-MULTIWORD-ADDRESS-UNSUPPORTED: native multiword BigInt expansion requires a separate provenance-propagation increment"
          )
          super.transform(original)
        } else {
          val bridged = Apply(
            axiRuntimeMethod("compilerAddress"),
            List(
              rewritten.tree,
              Literal(Constant(reference)),
              Literal(Constant(stableAddressName(selectedValue)))
            ) ++ sourceArguments(selectedValue)
          )
          bridged.setPos(selectedValue.pos)
          val transformedArguments = arguments.zipWithIndex.map {
            case (argument, index) if index == selectedIndex =>
              rebuildArgument(argument, bridged)
            case (argument, _) => transform(argument)
          }
          val transformedFun = treeCopy.Select(
            original.fun.asInstanceOf[Select],
            transform(receiver),
            methodName
          )
          val result = treeCopy.Apply(original, transformedFun, transformedArguments)
          result.setPos(original.pos)
        }
      }
    }

    private def transformClass(value: ClassDef): Tree = withScope {
      constructorParameters(value)
        .filter(isNativeIntParameter)
        .foreach { parameter =>
          val name = decoded(parameter.name)
          bindInteger(
            parameter.name,
            TrackedInt(
              reference = sourceReference(parameter, s"argument:$name"),
              stableName = name,
              root = true
            )
          )
        }
      super.transform(value)
    }

    private def transformVal(value: ValDef): Tree = {
      val immutable = !value.mods.hasFlag(Flag.MUTABLE)
      val factory = isFactoryConstruction(value.rhs) || (value.rhs match {
        case Ident(name: TermName)           => lookupFactory(name)
        case Select(This(_), name: TermName) => lookupFactory(name)
        case _                               => false
      })
      val integer =
        if (immutable) rewriteInt(value.rhs, Some(decoded(value.name)))
        else IntRewrite(transform(value.rhs), None)
      val rhs = integer.reference match {
        case Some(reference) if immutable =>
          bindInteger(
            value.name,
            TrackedInt(reference, decoded(value.name), root = false)
          )
          integer.tree
        case Some(_) =>
          global.reporter.error(
            value.pos,
            "MORPHDL-NATIVE-AXI4-ADDRESS-MUTABLE-ESCAPE: symbolic AXI4 addresses may not escape through mutable Scala state"
          )
          integer.tree
        case None =>
          if (containsTrackedInteger(value.rhs)) {
            global.reporter.error(
              value.pos,
              "MORPHDL-NATIVE-AXI4-ADDRESS-LOCAL-UNSUPPORTED: symbolic AXI4 address local is outside the bounded native-Int arithmetic contract"
            )
          }
          transform(value.rhs)
      }
      if (factory && immutable) bindFactory(value.name)
      treeCopy.ValDef(
        value,
        value.mods,
        value.name,
        super.transform(value.tpt),
        rhs
      )
    }

    override def transform(tree: Tree): Tree = tree match {
      case value: ClassDef if !isNativeAxiSource => transformClass(value)
      case original: Apply
          if isNativeAxiSource &&
            original.args.size == 1 &&
            terminalName(original.fun) == "is" &&
            isAddressField(original.args.head) =>
        transformNativeCaseKey(original)
      case original @ Apply(Select(receiver, methodName: TermName), arguments)
          if isFactoryReceiver(receiver) &&
            (singleWordAddressMethods.contains(decoded(methodName)) ||
              multiWordAddressMethods.contains(decoded(methodName))) =>
        transformFactoryCall(original, receiver, methodName, arguments)
      case value: ValDef if !isNativeAxiSource => transformVal(value)
      case template: Template => withScope(super.transform(template))
      case block: Block       => withScope(super.transform(block))
      case function: Function => withScope(super.transform(function))
      case definition: DefDef => withScope(super.transform(definition))
      case other              => super.transform(other)
    }
  }

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit =
      if (eligible(unit))
        unit.body = new AxiTransformer(unit).transform(unit.body)
  }
}
