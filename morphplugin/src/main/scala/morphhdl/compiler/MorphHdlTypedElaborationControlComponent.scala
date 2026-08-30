package morphhdl.compiler

import java.util.IdentityHashMap

import scala.collection.mutable
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

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
  private case object OrdinaryBinding extends BindingKind

  private final case class ClassifiedTrees(
      typedIfs: IdentityHashMap[Tree, java.lang.Boolean],
      typedGenerates: IdentityHashMap[Tree, java.lang.Boolean],
      typedRequires: IdentityHashMap[Tree, java.lang.Boolean],
      typedEqualities: IdentityHashMap[Tree, java.lang.Boolean]
  )

  private def eligible(unit: CompilationUnit): Boolean = {
    val path = Option(unit.source)
      .flatMap(source => Option(source.file))
      .map(_.path.replace('\\', '/'))
      .getOrElse("")
    val content = Option(unit.source).map(_.content.mkString).getOrElse("")
    !path.contains("/morphplugin/src/main/scala/") &&
      (content.contains("ElabInt") || content.contains("ElabBool"))
  }

  private def decoded(name: Name): String = name.decodedName.toString

  private def terminalName(tree: Tree): String = tree match {
    case Ident(name)       => decoded(name)
    case Select(_, name)   => decoded(name)
    case TypeApply(fun, _) => terminalName(fun)
    case _                 => ""
  }

  private def simpleTypeName(tree: Tree): String = tree match {
    case Ident(name)           => decoded(name)
    case Select(_, name)       => decoded(name)
    case AppliedTypeTree(t, _) => simpleTypeName(t)
    case _                     => tree.toString.split('.').lastOption.getOrElse("")
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

    var scopes = List(mutable.LinkedHashMap.empty[TermName, BindingKind])

    def bindingKind(value: ValDef): BindingKind =
      simpleTypeName(value.tpt) match {
        case "ElabInt"  => TypedIntegerBinding
        case "ElabBool" => TypedBooleanBinding
        case _          => OrdinaryBinding
      }

    def bind(value: ValDef): Unit =
      scopes.head.update(value.name, bindingKind(value))

    def bindOrdinary(name: TermName): Unit =
      scopes.head.update(name, OrdinaryBinding)

    def lookup(name: TermName): Option[BindingKind] =
      scopes.collectFirst {
        case scope if scope.contains(name) => scope(name)
      }

    def withScope[A](body: => A): A = {
      scopes = mutable.LinkedHashMap.empty[TermName, BindingKind] :: scopes
      try body
      finally scopes = scopes.tail
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

      case Apply(Select(receiver, operator), List(argument)) =>
        val receiverKind = expressionKind(receiver)
        decoded(operator) match {
          case "+" | "-" | "*" | "/" | "%"
              if receiverKind == TypedIntegerBinding =>
            TypedIntegerBinding
          case "<" | "<=" | ">" | ">="
              if receiverKind == TypedIntegerBinding =>
            TypedBooleanBinding
          case "elabEq" | "elabNe"
              if receiverKind == TypedIntegerBinding =>
            TypedBooleanBinding
          case "==" | "!="
              if receiverKind == TypedIntegerBinding =>
            TypedBooleanBinding
          case "==" | "!="
              if expressionKind(argument) == TypedIntegerBinding =>
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
      case _ => OrdinaryBinding
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
        case template: Template =>
          withScope {
            // Class/object members are visible throughout their template.
            // Recording every member also lets ordinary members shadow typed
            // constructor parameters or enclosing values deterministically.
            template.body.foreach {
              case value: ValDef => bind(value)
              case _             =>
            }
            template.parents.foreach(traverse)
            traverse(template.self)
            template.body.foreach(traverse)
          }

        case definition: DefDef =>
          withScope {
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
            block.stats.foreach { statement =>
              traverse(statement)
              statement match {
                case value: ValDef => bind(value)
                case _             =>
              }
            }
            traverse(block.expr)
          }

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

        case original @ Apply(fun, predicate :: _)
            if terminalName(fun) == "require" || terminalName(fun) == "assert" =>
          if (isTypedBoolean(predicate)) mark(typedRequires, original)
          super.traverse(original)

        case original @ Apply(Select(left, operator), List(right))
            if decoded(operator) == "==" || decoded(operator) == "!=" =>
          val leftTyped = isTypedInteger(left)
          val rightTyped = isTypedInteger(right)
          if (leftTyped || rightTyped)
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

    private def rewriteAssert(
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
        rewriteAssert(original, predicate, rest)
      case original @ Apply(Select(_, operator), List(_))
          if (decoded(operator) == "==" || decoded(operator) == "!=") &&
            classified.typedEqualities.containsKey(original) =>
        condition(original)
      case _ => super.transform(tree)
    }
  }

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit =
      if (eligible(unit)) {
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
