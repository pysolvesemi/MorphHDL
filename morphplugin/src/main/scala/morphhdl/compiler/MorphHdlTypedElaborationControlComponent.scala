package morphhdl.compiler

import scala.collection.mutable
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

/**
  * Small pre-typer syntax bridge for explicitly typed `ElabInt`/`ElabBool`
  * expressions.
  *
  * Unlike the legacy native-Int shadow transformer, this phase never infers
  * symbolic provenance from an ordinary Scala `Int` or `Boolean`. It rewrites
  * only syntax that directly references declarations whose source type is
  * `ElabInt` or `ElabBool`.
  */
final class MorphHdlTypedElaborationControlComponent(val global: Global)
    extends PluginComponent {
  import global._

  override val phaseName: String = "morphhdl-typed-elaboration-control"
  override val runsAfter: List[String] = List("parser")
  override val runsBefore: List[String] =
    List(
      "morphhdl-native-int-shadow-expressions",
      "morphhdl-natural-symbolic-conditionals",
      "namer"
    )

  private def eligible(unit: CompilationUnit): Boolean = {
    val path = Option(unit.source)
      .flatMap(source => Option(source.file))
      .map(_.path.replace('\\', '/'))
      .getOrElse("")
    val content = Option(unit.source).map(_.content.mkString).getOrElse("")
    !path.contains("/frontend/src/main/scala/") &&
      !path.contains("/morphplugin/src/main/scala/") &&
      !path.contains("/morphruntime/src/main/scala/") &&
      !path.endsWith("/core/src/main/scala/spinal/core/ElabInt.scala") &&
      (content.contains("ElabInt") || content.contains("ElabBool"))
  }

  private def decoded(name: Name): String = name.decodedName.toString

  private def terminalName(tree: Tree): String = tree match {
    case Ident(name)       => decoded(name)
    case Select(_, name)   => decoded(name)
    case TypeApply(fun, _) => terminalName(fun)
    case _                 => ""
  }

  private def path(tree: Tree): String = tree match {
    case Ident(name)        => decoded(name)
    case Select(base, name) =>
      val prefix = path(base)
      if (prefix.isEmpty) decoded(name) else s"$prefix.${decoded(name)}"
    case This(name)         => decoded(name)
    case _                  => ""
  }

  private final case class TypedNames(
      integers: Set[TermName],
      booleans: Set[TermName]
  ) {
    val all: Set[TermName] = integers ++ booleans
  }

  private def declaredTypedNames(tree: Tree): TypedNames = {
    val integers = mutable.LinkedHashSet.empty[TermName]
    val booleans = mutable.LinkedHashSet.empty[TermName]

    def typeName(tree: Tree): String = terminalName(tree)

    object Collector extends Traverser {
      override def traverse(current: Tree): Unit = current match {
        case value: ValDef if typeName(value.tpt) == "ElabInt" =>
          integers += value.name
          super.traverse(current)
        case value: ValDef if typeName(value.tpt) == "ElabBool" =>
          booleans += value.name
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
        .filter(_.nonEmpty)
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

    private def rewriteCondition(tree: Tree): Tree =
      new ConditionTransformer().transform(tree)

    /** Minimal source tokenization retained to distinguish `else if` from a braced nested if. */
    private def sourceTokens(from: Int, until: Int): Vector[String] = {
      val content = Option(unit.source).map(_.content).getOrElse(Array.empty[Char])
      val start = math.max(0, math.min(from, content.length))
      val end = math.max(start, math.min(until, content.length))
      val tokens = Vector.newBuilder[String]
      var index = start

      def has(offset: Int): Boolean = index + offset < end

      while (index < end) {
        val current = content(index)
        if (Character.isWhitespace(current)) index += 1
        else if (current == '/' && has(1) && content(index + 1) == '/') {
          index += 2
          while (index < end && content(index) != '\n' && content(index) != '\r')
            index += 1
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
          while (index < end && Character.isJavaIdentifierPart(content(index)))
            index += 1
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

    private def symbolicCondition(tree: Tree): Boolean =
      references(tree, names.all)

    private def function0(body: Tree): Tree = Function(Nil, transform(body))

    private def collectChain(
        original: If
    ): (Vector[(Tree, Tree, Int)], Tree) = {
      val alternatives = Vector.newBuilder[(Tree, Tree, Int)]
      var current = original
      var otherwise: Tree = original.elsep
      var done = false
      while (!done) {
        if (symbolicCondition(current.cond)) {
          alternatives += ((rewriteCondition(current.cond), current.thenp, sourceLine(current)))
          current.elsep match {
            case next: If if symbolicCondition(next.cond) && directElseIf(current, next) =>
              current = next
            case other =>
              otherwise = other
              done = true
          }
        } else {
          otherwise = current
          done = true
        }
      }
      alternatives.result() -> otherwise
    }

    private def rewriteSingle(original: If): Tree = {
      val rewritten = Apply(
        Apply(
          Apply(
            helperMethod("selectSymbolic"),
            List(
              rewriteCondition(original.cond),
              Literal(Constant(sourceFile)),
              Literal(Constant(sourceLine(original)))
            )
          ),
          List(transform(original.thenp))
        ),
        List(transform(original.elsep))
      )
      rewritten.setPos(original.pos)
    }

    private def rewriteChain(original: If): Tree = {
      val (alternatives, otherwise) = collectChain(original)
      val sequence = Apply(
        scalaSeqApply,
        alternatives.map { case (condition, body, line) =>
          Apply(
            tuple4Apply,
            List(
              condition,
              function0(body),
              Literal(Constant(sourceFile)),
              Literal(Constant(line))
            )
          )
        }.toList
      )
      val rewritten = Apply(
        helperMethod("selectSymbolicChain"),
        List(
          sequence,
          function0(otherwise),
          Literal(Constant(sourceFile)),
          Literal(Constant(sourceLine(otherwise)))
        )
      )
      rewritten.setPos(original.pos)
    }

    private def rewriteRequire(
        original: Apply,
        arguments: List[Tree]
    ): Tree = {
      val condition = rewriteCondition(arguments.head)
      val rewritten = arguments match {
        case _ :: Nil =>
          Apply(
            helperMethod("requireCondition"),
            List(
              condition,
              Literal(Constant(sourceFile)),
              Literal(Constant(sourceLine(original)))
            )
          )
        case _ :: message :: Nil =>
          Apply(
            helperMethod("requireCondition"),
            List(
              condition,
              transform(message),
              Literal(Constant(sourceFile)),
              Literal(Constant(sourceLine(original)))
            )
          )
        case _ => super.transform(original)
      }
      rewritten.setPos(original.pos)
    }

    override def transform(tree: Tree): Tree = tree match {
      case original: If if symbolicCondition(original.cond) =>
        original.elsep match {
          case next: If if symbolicCondition(next.cond) && directElseIf(original, next) =>
            rewriteChain(original)
          case _ => rewriteSingle(original)
        }
      case original @ Apply(fun, arguments)
          if arguments.nonEmpty && arguments.size <= 2 &&
            terminalName(fun) == "require" &&
            symbolicCondition(arguments.head) &&
            Set("require", "Predef.require", "scala.Predef.require").contains(path(fun)) =>
        rewriteRequire(original, arguments)
      case _ => super.transform(tree)
    }
  }

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit =
      if (eligible(unit)) {
        val names = declaredTypedNames(unit.body)
        if (names.all.nonEmpty)
          unit.body = new TypedControlTransformer(unit, names).transform(unit.body)
      }
  }
}
