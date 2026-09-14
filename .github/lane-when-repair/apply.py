#!/usr/bin/env python3
"""Apply reviewed source hunks to the exact baseline for the repair workbench.

This is a temporary development recipe, not a compiler or Verilog postprocessor.
It refuses changed source and prints the resulting production diff. The actual
Scala files, not this recipe, must be published and qualified before completion.
"""
from pathlib import Path
import hashlib
import subprocess

ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {
    'morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala': 'f0e25ae12132cfbac18aa97c4aea5bcb754b4124',
    'core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala': 'af934dc2743eb2ea31577b8b5282ad9839d3aec2',
    'core/src/main/scala/spinal/core/internals/ComponentEmitter.scala': '1afbf2dcecbdd4c930e9b35933f30c491f352657',
    'core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala': 'ebd1f2227f2e726b11a36b184c2c39048a8c3fb1',
}


def replace_once(text: str, old: str, new: str) -> str:
    if text.count(old) != 1:
        raise RuntimeError('Expected exactly one reviewed source hunk: ' + old[:120])
    return text.replace(old, new, 1)


def main() -> None:
    sources = {}
    for name, expected in EXPECTED.items():
        data = (ROOT / name).read_bytes()
        actual = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if actual != expected:
            raise RuntimeError(f'{name}: unexpected source blob {actual}, expected {expected}')
        sources[name] = data.decode('utf-8')

    path = 'morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala'
    text = sources[path]
    text = replace_once(text,
        '  ): Either[String, NativeProof] = {\n    val alias = candidate.alias\n    val sharedSafety',
        '  ): Either[String, NativeProof] = {\n    if (candidate.useStatements.exists(_.isInstanceOf[WhenStatement]))\n      return proveConditionCandidate(pc, candidate)\n    val alias = candidate.alias\n    val sharedSafety')
    text = replace_once(text,
        '  ): Option[CanonicalSnapshot] = {\n    val moduleId = ModuleId.unsafe("module.native-named-expression")',
        '  ): Option[CanonicalSnapshot] = {\n    if (candidate.useStatements.exists(_.isInstanceOf[WhenStatement]))\n      return conditionSnapshot(candidate, proof)\n    val moduleId = ModuleId.unsafe("module.native-named-expression")')
    text = replace_once(text,
        '  private def rewriteNativeIdentity(candidate: NativeCandidate): Int = {\n',
        '  private def rewriteNativeIdentity(candidate: NativeCandidate): Int = {\n    if (candidate.useStatements.exists(_.isInstanceOf[WhenStatement]))\n      return rewriteConditionIdentity(candidate)\n')
    methods = (ROOT / '.github/lane-when-repair/condition-methods.scala.inc').read_text()
    text = replace_once(text, '  private def applyCanonicalDecision(\n', methods + '\n  private def applyCanonicalDecision(\n')
    sources[path] = text

    path = 'core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala'
    method = '''  /** A condition may be printed once per split process even when the native
    * expression has only one use. Keep this distinct from arbitrary expression
    * sharing: the ordinary complete sizing proof must already accept the exact
    * condition identity, and the complete subtree supplies a conservative
    * upper bound on the number of emitted process copies. This only declines
    * the condition-sharing request; other mandatory wrapping reasons survive.
    */
  private[internals] def redundantSharedCondition(
      component: Component,
      config: SpinalConfig,
      expression: Expression,
      approved: IdentityHashMap[Expression, java.lang.Boolean]
  ): Boolean = {
    if (!isEnabled(config) || !approved.containsKey(expression) ||
        expression.getTypeObject != TypeBool) return false
    val pending = ArrayBuffer(expression)
    var nodes = 0
    while (pending.nonEmpty) {
      val next = pending.remove(pending.size - 1)
      nodes += 1
      if (next == null || nodes > 64) return false
      next match {
        case _: BaseType =>
        case _ => next.foreachDrivingExpression(child => pending += child)
      }
    }
    var nativeConditions = 0
    var leaves = 0
    component.dslBody.walkStatements {
      case when: WhenStatement if when.cond eq expression =>
        nativeConditions += 1
        when.whenTrue.walkLeafStatements(_ => leaves += 1)
        when.whenFalse.walkLeafStatements(_ => leaves += 1)
      case _ =>
    }
    nativeConditions == 1 && leaves >= 1 && leaves <= 32 &&
      leaves.toLong * nodes <= 256
  }

'''
    sources[path] = replace_once(sources[path], '  /** Prove which synthetic expression carriers can be omitted, before emission.\n',
        method + '  /** Prove which synthetic expression carriers can be omitted, before emission.\n')

    path = 'core/src/main/scala/spinal/core/internals/ComponentEmitter.scala'
    text = replace_once(sources[path],
        '  def readedOutputWrapEnable : Boolean = false\n',
        '  def readedOutputWrapEnable : Boolean = false\n\n  /** Backend-specific proof for this one condition-sharing request. */\n  def canInlineRepeatedWhenCondition(condition: Expression): Boolean = false\n')
    text = replace_once(text,
        '        for ((c, n) <- whenCondOccurences if n > 1) {\n',
        '        for ((c, n) <- whenCondOccurences if n > 1 && !canInlineRepeatedWhenCondition(c)) {\n')
    sources[path] = text

    path = 'core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala'
    sources[path] = replace_once(sources[path],
        '  private lazy val wrappersProvenRedundant =\n    VerilogEmitterExpressionInlining.redundantWrappers(component, spinalConfig)\n',
        '  private lazy val wrappersProvenRedundant =\n    VerilogEmitterExpressionInlining.redundantWrappers(component, spinalConfig)\n\n  override def canInlineRepeatedWhenCondition(condition: Expression): Boolean =\n    VerilogEmitterExpressionInlining.redundantSharedCondition(\n      component, spinalConfig, condition, wrappersProvenRedundant)\n')

    # All source anchors/hunks are checked before any production file is changed.
    for name, text in sources.items():
        (ROOT / name).write_text(text)
        print(hashlib.sha256(text.encode()).hexdigest(), name)
    subprocess.run(['git', 'diff', '--check'], cwd=ROOT, check=True)
    subprocess.run(['git', 'diff', '--stat'], cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
