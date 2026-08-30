#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if old in text:
        text = text.replace(old, new, 1)
    elif new not in text:
        raise SystemExit(f"{label}: expected source fragment not found in {path}")
    file.write_text(text, encoding="utf-8")


plugin = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlTypedElaborationControlComponent.scala"
)
text = plugin.read_text(encoding="utf-8")

old_if = '''    private def rewriteIf(original: If): Tree = {
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
'''
new_if = '''    private def rewriteIf(original: If): Tree = {
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
'''
if old_if in text:
    text = text.replace(old_if, new_if, 1)
elif new_if not in text:
    raise SystemExit("typed if lowering source fragment not found")
text = text.replace('helperMethod("generate")', 'helperMethod("generateSymbolic")')

old_require = '''    private def rewriteAssert(
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
'''
new_require = '''    private def rewriteAssert(
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
'''
if old_require in text:
    text = text.replace(old_require, new_require, 1)
elif new_require not in text:
    raise SystemExit("typed require lowering source fragment not found")
text = text.replace(
    "rewriteAssert(original, fun, predicate, rest)",
    "rewriteAssert(original, predicate, rest)",
)
plugin.write_text(text, encoding="utf-8")

replace_once(
    "morphruntime/src/main/scala/spinal/core/ElabControl.scala",
    '''  /** Preserve one source-ordered typed `if / else if / ... / else` chain. */
''',
    '''  /** Typed counterpart of SpinalHDL's host-language `.generate` helper. */
  def generateSymbolic[T](
      condition: ElabBool,
      sourceFile: String,
      sourceLine: Int
  )(body: => T): T =
    selectSymbolic(condition, sourceFile, sourceLine)(body)(null.asInstanceOf[T])

  /** Preserve one source-ordered typed `if / else if / ... / else` chain. */
''',
    "typed generate runtime",
)

replace_once(
    "core/src/main/scala/spinal/core/core.scala",
    '''  type Module = spinal.core.Component
  type dontName = spinal.core.DontName @field
''',
    '''  type Module = spinal.core.Component
  type dontName = spinal.core.DontName @field

  /** Parameter-preserving counterpart of [[widthOf]] for typed native APIs. */
  def widthOfExpr[T <: Data](that: T): ElabInt = ElabInt.packedWidthOf(that)
''',
    "widthOfExpr helper",
)

for path, kind in (
    ("core/src/main/scala/spinal/core/Bits.scala", "Bits"),
    ("core/src/main/scala/spinal/core/UInt.scala", "UInt"),
    ("core/src/main/scala/spinal/core/SInt.scala", "SInt"),
):
    old = f'''  /** Create a new {kind} of a given width */
  def {kind}(width: BitCount): {kind} = {kind}().setWidth(width.value)
'''
    new = old + f'''  /** Create a new {kind} while retaining one typed elaboration width. */
  def {kind}(width: ParameterizedBitCount): {kind} =
    ParameterizedWidth.{kind}(width)
'''
    replace_once(path, old, new, f"{kind} typed factory")

replace_once(
    "core/src/main/scala/spinal/core/BitVector.scala",
    '''  def resize(width: Int): BitVector
  def resize(width: BitCount): BitVector
''',
    '''  def resize(width: Int): BitVector
  def resize(width: BitCount): BitVector
  def resize(width: ElabInt): BitVector
''',
    "typed BitVector resize surface",
)

replace_once(
    "core/src/main/scala/spinal/core/Bits.scala",
    '''  override def resize(width: BitCount) : Bits = resize(width.value)
''',
    '''  override def resize(width: BitCount) : Bits = resize(width.value)

  /** Resize while retaining the exact typed target width. */
  override def resize(width: ElabInt): Bits =
    ParameterizedWidth.attach(
      resize(width.witness),
      width.toParameterizedBitCount("typed resize")
    )
''',
    "typed Bits resize",
)

for path, kind in (
    ("core/src/main/scala/spinal/core/UInt.scala", "UInt"),
    ("core/src/main/scala/spinal/core/SInt.scala", "SInt"),
):
    old = f'''  override def resize(width: BitCount) : {kind} = resize(width.value)
'''
    if kind == "UInt":
        old = '''  override def resize(width: BitCount) : this.type = resize(width.value)
'''
    new = old + '''
  /** Resize while retaining the exact typed target width. */
  override def resize(width: ElabInt): this.type =
    ParameterizedWidth
      .attach(resize(width.witness), width.toParameterizedBitCount("typed resize"))
      .asInstanceOf[this.type]
'''
    replace_once(path, old, new, f"typed {kind} resize")

replace_once(
    "frontend/src/main/scala/morphhdl/frontend/HdlInt.scala",
    '''  /** Retain one bounded native Mem word-count expression. */
''',
    '''  /**
    * Cross the approved typed native-library boundary without collapsing this
    * value to Scala `Int`.
    */
  def asElabInt: spinal.core.ElabInt =
    spinal.core.ElabInt.fromExpression(
      StructuralExpressionBridge.width(this, "typed elaboration integer")
    )

  /** Retain one bounded native Mem word-count expression. */
''',
    "HdlInt typed bridge",
)

replace_once(
    "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlPlugin.scala",
    '''      new MorphHdlNativeIntShadowExpressionComponent(global),
      new MorphHdlNaturalSymbolicConditionalComponent(global)
''',
    '''      new MorphHdlNativeIntShadowExpressionComponent(global),
      new MorphHdlTypedElaborationControlComponent(global),
      new MorphHdlNaturalSymbolicConditionalComponent(global)
''',
    "typed compiler phase registration",
)

for path in (
    "core/src/main/scala/spinal/core/core.scala",
    "core/src/main/scala/spinal/core/Bits.scala",
    "core/src/main/scala/spinal/core/UInt.scala",
    "core/src/main/scala/spinal/core/SInt.scala",
    "core/src/main/scala/spinal/core/BitVector.scala",
    "frontend/src/main/scala/morphhdl/frontend/HdlInt.scala",
    "morphruntime/src/main/scala/spinal/core/ElabControl.scala",
    "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlTypedElaborationControlComponent.scala",
    "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlPlugin.scala",
):
    if not Path(path).is_file():
        raise SystemExit(f"typed foundation output missing: {path}")
