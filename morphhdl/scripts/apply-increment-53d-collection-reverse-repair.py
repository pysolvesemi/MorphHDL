#!/usr/bin/env python3
from pathlib import Path


def replace_or_verify(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    old_count = text.count(old)
    new_count = text.count(new)
    if old_count == 1 and new_count == 0:
        path.write_text(text.replace(old, new, 1), encoding="utf-8")
    elif old_count == 0 and new_count == 1:
        return
    else:
        raise SystemExit(
            f"{label}: expected one old or one new anchor; "
            f"found old={old_count}, new={new_count}"
        )


plugin = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
tests = Path(
    "morphhdl/src/test/scala/morphhdl/"
    "ExternalNativeIntShadowExpressionTests.scala"
)

helper_old = '''    private def rewriteUnsupportedKnownCall(
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
'''
helper_new = '''    private def rewriteUnsupportedKnownCall(
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
'''
replace_or_verify(plugin, helper_old, helper_new, "unsupported call helpers")

# An earlier narrow repair changed only the Select arm. Canonicalize it back to
# the pre-repair anchor so the complete receiver/argument rewrite can be applied.
simple_select = '''        case Select(value, methodName) if unsupportedIntegerCalls.contains(decoded(methodName)) =>
          // A tracked Int nested somewhere below the receiver is not proof that
          // the selected method operates on an Int. In particular,
          // `subdivideIn(factor slices).reverse` is a collection reversal whose
          // collection expression merely contains the symbolic `factor`.
          trackedInteger(value)
            .map(rewriteUnsupportedKnownCall(tree, _, decoded(methodName)))
            .getOrElse(Rewrite(super.transform(tree)))
'''
original_select = '''        case Select(_, methodName) if unsupportedIntegerCalls.contains(decoded(methodName)) =>
          firstTrackedInteger(tree)
            .map(rewriteUnsupportedKnownCall(tree, _, decoded(methodName)))
            .getOrElse(Rewrite(super.transform(tree)))
'''
plugin_text = plugin.read_text(encoding="utf-8")
if simple_select in plugin_text and original_select not in plugin_text:
    plugin.write_text(
        plugin_text.replace(simple_select, original_select, 1),
        encoding="utf-8",
    )

match_old = '''        case Apply(fun, arguments) =>
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
'''
match_new = '''        case Apply(Select(receiver, methodName), arguments)
            if unsupportedIntegerCalls.contains(decoded(methodName)) =>
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
        case Select(receiver, methodName)
            if unsupportedIntegerCalls.contains(decoded(methodName)) =>
          rewriteUnsupportedReceiverCall(
            tree,
            receiver,
            methodName,
            None,
            decoded(methodName)
          )
'''
replace_or_verify(plugin, match_old, match_new, "unsupported call match arms")

replace_or_verify(
    tests,
    '''    @dontName val compound = (root + 1) * 2

    @dontName val less = root < 12
''',
    '''    @dontName val compound = (root + 1) * 2
    @dontName val collectionReverse = Vector(root, root + 1).reverse.head

    @dontName val less = root < 12
''',
    "collection reverse regression fixture",
)
replace_or_verify(
    tests,
    '''      case "unsupported" =>
        @dontName val broken = math.abs(root)
      case "boxing" =>
''',
    '''      case "unsupported" =>
        @dontName val broken = math.abs(root)
      case "unsupported-receiver" =>
        @dontName val broken = root.abs
      case "unsupported-derived-receiver" =>
        @dontName val broken = (root + 1).abs
      case "boxing" =>
''',
    "direct receiver regression fixtures",
)

test_text = tests.read_text(encoding="utf-8")
wrong_assertion = "      assert(top.leaf.collectionReverse == Vector(9, 8))\n"
correct_assertion = "      assert(top.leaf.collectionReverse == 9)\n"
if wrong_assertion in test_text:
    test_text = test_text.replace(wrong_assertion, correct_assertion, 1)
    tests.write_text(test_text, encoding="utf-8")
else:
    replace_or_verify(
        tests,
        '''      assert(top.leaf.plus == 10)
      assert(top.leaf.compound == 18)
''',
        '''      assert(top.leaf.plus == 10)
      assert(top.leaf.compound == 18)
      assert(top.leaf.collectionReverse == 9)
''',
        "collection reverse regression assertion",
    )

replace_or_verify(
    tests,
    '''  test("unsupported native Int calls fail closed") {
    val failure = failureFor(8, 2, 16, "unsupported")
    assert(failure.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-CALL-UNSUPPORTED"))
  }
''',
    '''  test("unsupported native Int calls fail closed") {
    val failure = failureFor(8, 2, 16, "unsupported")
    assert(failure.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-CALL-UNSUPPORTED"))

    val receiver = failureFor(8, 2, 16, "unsupported-receiver")
    assert(receiver.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-CALL-UNSUPPORTED"))

    val derivedReceiver = failureFor(8, 2, 16, "unsupported-derived-receiver")
    assert(derivedReceiver.contains("MORPH-FRONTEND-NATIVE-INT-EXPRESSION-CALL-UNSUPPORTED"))
  }
''',
    "unsupported receiver regression assertions",
)
