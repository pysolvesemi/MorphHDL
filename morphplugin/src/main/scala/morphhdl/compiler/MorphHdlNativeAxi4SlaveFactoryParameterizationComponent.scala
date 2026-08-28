package morphhdl.compiler

import scala.collection.mutable
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

/**
  * MorphHDL-owned parser bridge for parameterized addresses passed to the real,
  * untouched SpinalHDL Axi4SlaveFactory.
  *
  * This component never implements a bus factory. It preserves one native Int
  * expression at the application call site and replaces only the corresponding
  * native `is(address.address)` switch key while Axi4SlaveFactory.build()
  * remains responsible for grouping, AXI protocol handling and register-map
  * actions.
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
    val source = Option(unit.source).map(_.content.mkString).getOrElse("")
    !path.contains("/morphplugin/src/main/scala/") &&
      !path.contains("/morphruntime/src/main/scala/") &&
      !path.contains("/frontend/src/main/scala/") &&
      (path.endsWith(
        "/lib/src/main/scala/spinal/lib/bus/amba4/axi/Axi4SlaveFactory.scala"
      ) || source.contains("Axi4SlaveFactory"))
  }

  private def runtimeObject(name: String): Tree = {
    val root = Ident(termNames.ROOTPKG)
    val spinal = Select(root, TermName("spinal"))
    val core = Select(spinal, TermName("core"))
    Select(core, TermName(name))
  }

  private def nativeIntMethod(name: String): Tree =
    Select(runtimeObject("ExternalNativeIntCompilerRuntime"), TermName(name))

  private def axiMethod(name: String): Tree =
    Select(
      runtimeObject("ExternalNativeAxi4SlaveFactoryParameterization"),
      TermName(name)
    )

  private final case class Root(
      reference: String,
      name: String,
      sourceLine: Int
  )
  private final case class Rewritten(value: Tree, reference: String)

  private final class TransformerImpl(unit: CompilationUnit) extends Transformer {
    private var rootScopes = List(mutable.LinkedHashMap.empty[TermName, Root])
    private var factoryScopes = List(mutable.LinkedHashSet.empty[TermName])

    private val addressAtOne = Set(
      "read",
      "write",
      "readAndWrite",
      "createWriteOnly",
      "createReadOnly",
      "createReadAndWrite",
      "drive",
      "driveAndRead"
    )
    private val addressAtZero = Set(
      "onRead",
      "onWrite",
      "isReading",
      "isWriting"
    )
    private val rejectedMultiWord = Set(
      "readMultiWord",
      "writeMultiWord",
      "readAndWriteMultiWord"
    )

    private def sourceFile: String = {
      val path = Option(unit.source)
        .flatMap(source => Option(source.file))
        .map(_.path)
        .orNull
      if (path == null || path.length == 0) "<native-axi4-slave-factory>"
      else path
    }

    private def line(tree: Tree): Int =
      if (tree.pos != null && tree.pos.isDefined) math.max(1, tree.pos.line)
      else 1

    private def column(tree: Tree): Int =
      if (tree.pos != null && tree.pos.isDefined) math.max(1, tree.pos.column)
      else 1

    private def point(tree: Tree): Int =
      if (tree.pos != null && tree.pos.isDefined) math.max(0, tree.pos.point)
      else 0

    private def reference(tree: Tree, role: String): String =
      List(
        sourceFile.replace('\\', '/'),
        line(tree).toString,
        column(tree).toString,
        point(tree).toString,
        role
      ).mkString(":")

    private def location(tree: Tree): List[Tree] =
      List(Literal(Constant(sourceFile)), Literal(Constant(line(tree))))

    private def rootLocation(root: Root): List[Tree] =
      List(Literal(Constant(sourceFile)), Literal(Constant(root.sourceLine)))

    private def decoded(name: Name): String = name.decodedName.toString

    private def terminal(tree: Tree): String = tree match {
      case Ident(name)       => decoded(name)
      case Select(_, name)   => decoded(name)
      case TypeApply(fun, _) => terminal(fun)
      case _                 => ""
    }

    private def typeTerminal(tree: Tree): String = tree match {
      case Ident(name)              => decoded(name)
      case Select(_, name)          => decoded(name)
      case AppliedTypeTree(base, _) => typeTerminal(base)
      case _                        => terminal(tree)
    }

    private def nativeFactorySource: Boolean =
      sourceFile.replace('\\', '/').endsWith(
        "/lib/src/main/scala/spinal/lib/bus/amba4/axi/Axi4SlaveFactory.scala"
      )

    private def withScope[T](body: => T): T = {
      rootScopes = mutable.LinkedHashMap.empty[TermName, Root] :: rootScopes
      factoryScopes = mutable.LinkedHashSet.empty[TermName] :: factoryScopes
      try body
      finally {
        rootScopes = rootScopes.tail
        factoryScopes = factoryScopes.tail
      }
    }

    private def lookupRoot(name: TermName): Option[Root] =
      rootScopes.collectFirst { case scope if scope.contains(name) => scope(name) }

    private def isFactory(name: TermName): Boolean =
      factoryScopes.exists(_.contains(name))

    private def constructorParameters(value: ClassDef): List[ValDef] =
      value.impl.body.collectFirst {
        case method: DefDef if decoded(method.name) == "<init>" =>
          method.vparamss.flatten
      }.getOrElse(Nil)

    private def bindConstructorRoots(value: ClassDef): Unit =
      constructorParameters(value).foreach { parameter =>
        if (typeTerminal(parameter.tpt) == "Int") {
          val name = decoded(parameter.name)
          rootScopes.head.update(
            parameter.name,
            Root(
              reference(parameter, s"argument:$name"),
              name,
              line(parameter)
            )
          )
        }
      }

    private def factoryConstruction(tree: Tree): Boolean = tree match {
      case Apply(fun, _) if terminal(fun) == "Axi4SlaveFactory" => true
      case Apply(Select(New(tpt), constructor), _)
          if decoded(constructor) == "<init>" &&
            typeTerminal(tpt) == "Axi4SlaveFactory" => true
      case TypeApply(fun, _) => factoryConstruction(fun)
      case _                 => false
    }

    private def factoryReceiver(tree: Tree): Boolean = tree match {
      case Ident(name: TermName)           => isFactory(name)
      case Select(This(_), name: TermName) => isFactory(name)
      case value                           => factoryConstruction(value)
    }

    private def trackedRoot(tree: Tree): Option[Root] = tree match {
      case Ident(name: TermName)           => lookupRoot(name)
      case Select(This(_), name: TermName) => lookupRoot(name)
      case _                               => None
    }

    private def trackRoot(tree: Tree, root: Root): Rewritten = {
      val call = Apply(
        nativeIntMethod("compilerTrackArgument"),
        List(
          super.transform(tree),
          Literal(Constant(root.name)),
          Literal(Constant(root.reference))
        ) ++ rootLocation(root)
      )
      call.setPos(tree.pos)
      Rewritten(call, root.reference)
    }

    private def literalInt(tree: Tree): Option[Int] = tree match {
      case Literal(Constant(value: Int))   => Some(value)
      case Literal(Constant(value: Short)) => Some(value.toInt)
      case Literal(Constant(value: Byte))  => Some(value.toInt)
      case _                               => None
    }

    private def rewriteAddress(tree: Tree): Option[Rewritten] =
      trackedRoot(tree).map(root => trackRoot(tree, root)).orElse {
        tree match {
          case original @ Apply(Select(leftTree, operationName), List(rightTree))
              if Set("+", "-", "*", "/", "%", "min", "max")
                .contains(decoded(operationName)) =>
            val left = trackedRoot(leftTree).map(root => trackRoot(leftTree, root))
            val rightLiteral = literalInt(rightTree)
            (left, rightLiteral) match {
              case (Some(lhs), Some(_)) =>
                val resultReference = reference(
                  original,
                  s"axi4-address-expression:${decoded(operationName)}"
                )
                val rightReference = reference(rightTree, "axi4-address-literal")
                val call = Apply(
                  nativeIntMethod("compilerBinary"),
                  List(
                    Literal(Constant(decoded(operationName))),
                    lhs.value,
                    Literal(Constant(lhs.reference)),
                    Literal(Constant(false)),
                    super.transform(rightTree),
                    Literal(Constant(rightReference)),
                    Literal(Constant(true)),
                    Literal(Constant(resultReference)),
                    Literal(Constant("axi4Address"))
                  ) ++ location(original)
                )
                call.setPos(original.pos)
                Some(Rewritten(call, resultReference))
              case _ => None
            }
          case _ => None
        }
      }

    private def bridgeAddress(tree: Tree): Option[Tree] =
      rewriteAddress(tree).map { rewritten =>
        val call = Apply(
          axiMethod("compilerAddress"),
          List(
            rewritten.value,
            Literal(Constant(rewritten.reference)),
            Literal(Constant(s"axi4_factory_address_${line(tree)}_${column(tree)}"))
          ) ++ location(tree)
        )
        call.setPos(tree.pos)
      }

    private def isNativeAddressField(tree: Tree): Boolean = tree match {
      case Select(Ident(owner), field)
          if decoded(owner) == "address" && decoded(field) == "address" => true
      case Select(Select(This(_), owner), field)
          if decoded(owner) == "address" && decoded(field) == "address" => true
      case _ => false
    }

    private def transformNativeCase(original: Apply): Tree = {
      val argument = original.args.head
      val key = Apply(
        axiMethod("compilerCaseKey"),
        List(super.transform(argument)) ++ location(original)
      )
      key.setPos(argument.pos)
      val result = treeCopy.Apply(
        original,
        super.transform(original.fun),
        List(key)
      )
      result.setPos(original.pos)
    }

    private def addressIndex(method: String, size: Int): Option[Int] =
      if (addressAtOne.contains(method) && size > 1) Some(1)
      else if (addressAtZero.contains(method) && size > 0) Some(0)
      else None

    private def transformFactoryCall(
        original: Apply,
        receiver: Tree,
        methodName: TermName,
        arguments: List[Tree]
    ): Tree = {
      val method = decoded(methodName)
      if (rejectedMultiWord.contains(method)) {
        val index = if (arguments.size > 1) 1 else 0
        if (index < arguments.size && rewriteAddress(arguments(index)).nonEmpty)
          global.reporter.error(
            arguments(index).pos,
            "MORPHDL-NATIVE-AXI4-MULTIWORD-ADDRESS-UNSUPPORTED: symbolic multiword expansion is outside Increment 53C"
          )
        super.transform(original)
      } else {
        addressIndex(method, arguments.size) match {
          case Some(index) =>
            bridgeAddress(arguments(index)) match {
              case Some(bridged) =>
                val rewrittenArgs = arguments.zipWithIndex.map {
                  case (_, current) if current == index => bridged
                  case (argument, _)                    => transform(argument)
                }
                val rewrittenFun = treeCopy.Select(
                  original.fun.asInstanceOf[Select],
                  transform(receiver),
                  methodName
                )
                val result = treeCopy.Apply(original, rewrittenFun, rewrittenArgs)
                result.setPos(original.pos)
              case None => super.transform(original)
            }
          case None => super.transform(original)
        }
      }
    }

    override def transform(tree: Tree): Tree = tree match {
      case value: ClassDef if !nativeFactorySource => withScope {
        bindConstructorRoots(value)
        super.transform(value)
      }
      case original: Apply
          if nativeFactorySource &&
            original.args.size == 1 &&
            terminal(original.fun) == "is" &&
            isNativeAddressField(original.args.head) =>
        transformNativeCase(original)
      case original @ Apply(Select(receiver, methodName: TermName), arguments)
          if !nativeFactorySource && factoryReceiver(receiver) =>
        transformFactoryCall(original, receiver, methodName, arguments)
      case value: ValDef if !nativeFactorySource =>
        val rhs = transform(value.rhs)
        if (!value.mods.hasFlag(Flag.MUTABLE) && factoryConstruction(value.rhs))
          factoryScopes.head += value.name
        treeCopy.ValDef(
          value,
          value.mods,
          value.name,
          transform(value.tpt),
          rhs
        )
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
        unit.body = new TransformerImpl(unit).transform(unit.body)
  }
}
