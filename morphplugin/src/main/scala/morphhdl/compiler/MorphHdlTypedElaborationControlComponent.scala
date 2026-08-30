package morphhdl.compiler

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

  private final case class TypedNames(
      integers: Set[TermName],
      booleans: Set[TermName]
  ) {
    def nonEmpty: Boolean = integers.nonEmpty || booleans.nonEmpty
  }

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

  private def declaredTypedNames(tree: Tree): TypedNames = {
    val integers = mutable.LinkedHashSet.empty[TermName]
    val booleans = mutable.LinkedHashSet.empty[TermName]
    object Collector extends Traverser {
      override def traverse(current: Tree): Unit = current match {
        case value: ValDef =>
          simpleTypeName(value.tpt) match {
            case "ElabInt"  => integers += value.name
            case "ElabBool" => booleans += value.name
            case _          =>
          }
          super.traverse(current)
        case _ => super.traverse(current)
      }
    }
    Collector.traverse(tree)
    TypedNames(integers.toSet, booleans.toSet)
  }

  private def references(tree: Tree, names: Set[TermName]): Boolean = {
    var found = false
    object Finder extends Traverser {
      override def traverse(current: Tree): Unit = if (!found) current match {
        case Ident(name: TermName) if names.contains(name) => found = true
        case _                                             => super.traverse(current)
      }
    }
    Finder.traverse(tree)
    found
  }

  private def referencesTyped(tree: Tree, names: TypedNames): Boolean =
    references(tree, names.integers) || references(tree, names.booleans)

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
      names: TypedNames
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
        case Apply(Select(left, operator), List(right))
            if decoded(operator) == "==" && references(tree, names.integers) =>
          val rewritten = Apply(
            Select(transform(left), TermName("elabEq")),
            List(transform(right))
          )
          rewritten.setPos(tree.pos)
        case Apply(Select(left, operator), List(right))
            if decoded(operator) == "!=" && references(tree, names.integers) =>
          val rewritten = Apply(
            Select(transform(left), TermName("elabNe")),
            List(transform(right))
          )
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
              if referencesTyped(next.cond, names) && directElseIf(current, next) =>
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
      val rewritten = Apply(
        helperMethod("select"),
        List(
          sequence,
          function0(otherwise),
          Literal(Constant(sourceFile)),
          Literal(Constant(sourceLine(otherwise)))
        )
      )
      rewritten.setPos(original.pos)
    }

    private def rewriteGenerate(
        original: Tree,
        predicate: Tree,
        body: Tree
    ): Tree = {
      val rewritten = Apply(
        Apply(
          helperMethod("generate"),
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
        fun: Tree,
        predicate: Tree,
        rest: List[Tree]
    ): Tree = {
      val rewritten = Apply(
        helperMethod("require"),
        List(
          condition(predicate),
          Literal(Constant(sourceFile)),
          Literal(Constant(sourceLine(original)))
        ) ++ rest.map(transform)
      )
      rewritten.setPos(original.pos)
    }

    override def transform(tree: Tree): Tree = tree match {
      case original: If if referencesTyped(original.cond, names) =>
        rewriteIf(original)
      case original @ Apply(Select(predicate, operator), List(body))
          if decoded(operator) == "generate" && referencesTyped(predicate, names) =>
        rewriteGenerate(original, predicate, body)
      case original @ Apply(fun, predicate :: rest)
          if (terminalName(fun) == "require" || terminalName(fun) == "assert") &&
            referencesTyped(predicate, names) =>
        rewriteAssert(original, fun, predicate, rest)
      case _ => super.transform(tree)
    }
  }

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit =
      if (eligible(unit)) {
        val names = declaredTypedNames(unit.body)
        if (names.nonEmpty)
          unit.body = new TypedControlTransformer(unit, names).transform(unit.body)
      }
  }
}
