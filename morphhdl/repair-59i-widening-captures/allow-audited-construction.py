#!/usr/bin/env python3
"""Stage the exact callback-policy prerequisites for 59i widening captures.

This development transaction deliberately remains source-bound. It admits only:

* a fresh custom Bundle whose exact runtime class, immutable shape constructor,
  companion initializer and constructor call graph already pass the composite
  callback audit;
* one read-only ElabInt width query for an exact native BaseType; and
* the exact native UInt `+|` method, whose generated graph is still certified by
  the existing closed-graph and replay proofs.

Arbitrary allocation, partial construction, direct registry access, captured
writes, mutable Bundle state, unknown factories and cross-field replay remain
rejected by the inherited checks.
"""
from __future__ import annotations

import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ELAB = ROOT / "core/src/main/scala/spinal/core/ElabInt.scala"
CERTIFIED = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCertifiedCallbackPolicy.scala"
COMPOSITE = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeCallbackPolicy.scala"
WIDENING_TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeWideningTests.scala"
SATURATION_TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeSaturationTests.scala"

EXPECTED_BLOBS = {
    ELAB: "defb22e2847b8792ce4cf51a2b98fb1066593bc1",
    CERTIFIED: "a9b37e7cf52df372a989fef5af76810dc012988a",
    COMPOSITE: "29560d97f12bf3537151e55d65dae5816841db87",
    WIDENING_TEST: "e4007803503cc22b03c2ca1314ff57f49b524e7c",
    SATURATION_TEST: "fc36e1e7c15bc059b25f9dbf1d55caa6dc627b00",
}


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def git_blob(raw: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()


def load_exact(path: Path) -> str:
    require(path.is_file() and not path.is_symlink(), "missing regular source: " + str(path))
    raw = path.read_bytes()
    found = git_blob(raw)
    require(found == EXPECTED_BLOBS[path],
            "source predecessor changed: " + path.relative_to(ROOT).as_posix() +
            " expected=" + EXPECTED_BLOBS[path] + " found=" + found)
    return raw.decode()


def replace_once(text: str, before: str, after: str, label: str) -> str:
    require(text.count(before) == 1,
            label + " exact anchor count changed: " + str(text.count(before)))
    return text.replace(before, after, 1)


def patch_elab_int() -> None:
    text = load_exact(ELAB)
    text = replace_once(text, '''  def fromExpression(expression: ElaborationIntegerExpression): ElabInt = {
    validateExpression(expression, "ElabInt expression")
    new ElabInt(withCompleteParameterRoots(expression))
  }

''', '''  def fromExpression(expression: ElaborationIntegerExpression): ElabInt = {
    validateExpression(expression, "ElabInt expression")
    new ElabInt(withCompleteParameterRoots(expression))
  }

  /** Read one exact retained native width without exposing the mutable width
    * registry or its Option carrier to callback bytecode. This observes only
    * elaboration geometry; it cannot read runtime hardware values. */
  private[spinal] def widthOf(value: BaseType): ElabInt = {
    if (value == null)
      throw new IllegalArgumentException("typed width query target must not be null")
    ParameterizedWidth.expressionOf(value) match {
      case Some(expression) => fromExpression(expression)
      case None             => literal(value.getBitsWidth)
    }
  }

''', "exact read-only typed width query")
    ELAB.write_text(text)


def patch_composite_policy() -> None:
    text = load_exact(COMPOSITE)
    text = replace_once(text, '''  def dataName(owner: String): Boolean = {
    if (nativeData(owner)) true
    else if (customBundle(owner)) { auditBundle(owner); true }
    else false
  }

''', '''  def dataName(owner: String): Boolean = {
    if (nativeData(owner)) true
    else if (customBundle(owner)) { auditBundle(owner); true }
    else false
  }

  /** A NEW instruction is hardware construction authority only for one exact
    * audited custom Bundle runtime class. */
  def constructionType(owner: String): Boolean = {
    if (!customBundle(owner)) false
    else { auditBundle(owner); true }
  }

  /** Admit only the field-free companion of the same audited Bundle class. */
  def constructionModule(owner: String): Boolean = {
    val result = owner.endsWith("$") && customBundle(owner.dropRight(1))
    if (result) auditCompanion(owner)
    result
  }

  /** Complete only the exact constructor of an already observed uninitialized
    * custom Bundle. auditBundle recursively proves its constructor graph. */
  def constructionCall(call: MethodInsnNode): Boolean = {
    val result = call.getOpcode == Opcodes.INVOKESPECIAL && call.name == "<init>" &&
      customBundle(call.owner)
    if (result) {
      auditBundle(call.owner)
      exact(call.owner, call.name, call.desc)
    }
    result
  }

''', "audited construction API")
    COMPOSITE.write_text(text)


def patch_certified_policy() -> None:
    text = load_exact(CERTIFIED)
    text = replace_once(text,
        '"$amp", "$bar", "$up", "$plus", "$plus$up", "$minus",',
        '"$amp", "$bar", "$up", "$plus", "$plus$up", "$plus$bar", "$minus",',
        "exact native UInt saturation method")
    text = replace_once(text, '''  private final case class Hardware(writable: Boolean) extends Value
  private final case class AssignmentTarget(value: Hardware) extends Value
''', '''  private final case class Hardware(writable: Boolean) extends Value
  /** One exact JVM object identity survives NEW/DUP until its audited <init>.
    * It is not hardware and cannot be returned or accessed before completion. */
  private final class FreshComposite(val owner: String) extends Value {
    var initialized = false
  }
  private final case class AssignmentTarget(value: Value) extends Value
  private def hardwareValue(value: Value): Boolean = value match {
    case _: Hardware => true
    case fresh: FreshComposite => fresh.initialized
    case _ => false
  }
  private def writableValue(value: Value): Boolean = value match {
    case Hardware(writable) => writable
    case fresh: FreshComposite => fresh.initialized
    case _ => false
  }
''', "fresh composite lifecycle")
    text = replace_once(text, '''    inspector.audit(lambda.getImplClass, lambda.getImplMethodName, lambda.getImplMethodSignature,
      arguments, None, 0) match {
      case _: Hardware =>
      case _ => fail("operator must return native hardware")
    }
''', '''    inspector.audit(lambda.getImplClass, lambda.getImplMethodName, lambda.getImplMethodSignature,
      arguments, None, 0) match {
      case value if hardwareValue(value) =>
      case _ => fail("operator must return initialized native hardware")
    }
''', "initialized callback result")
    text = replace_once(text, '''          case value: TypeInsnNode if value.getOpcode == Opcodes.NEW && value.desc == "spinal/idslplugin/Location" =>
            push(Location)
          case value: FieldInsnNode =>
''', '''          case value: TypeInsnNode if value.getOpcode == Opcodes.NEW && value.desc == "spinal/idslplugin/Location" =>
            push(Location)
          case value: TypeInsnNode if value.getOpcode == Opcodes.NEW && composite &&
              composites.exists(_.constructionType(value.desc)) =>
            push(new FreshComposite(value.desc))
          case value: FieldInsnNode =>
''', "audited NEW instruction")
    text = replace_once(text, '''            else if (value.name == "MODULE$" && value.desc == "L" + value.owner + ";") {
              if (!nativeModule(value.owner)) requirePureModule(value.owner)
              push(Module(value.owner))
''', '''            else if (value.name == "MODULE$" && value.desc == "L" + value.owner + ";") {
              if (!nativeModule(value.owner) &&
                  !composites.exists(_.constructionModule(value.owner))) requirePureModule(value.owner)
              push(Module(value.owner))
''', "audited companion module")
    text = replace_once(text, '''      def hardware(value: Value): Boolean = value.isInstanceOf[Hardware]
      def integral(value: Value): Boolean = value == Integer || value == Configuration || value == Count
      if (composite) {
''', '''      def hardware(value: Value): Boolean = hardwareValue(value)
      def integral(value: Value): Boolean = value == Integer || value == Configuration || value == Count
      if (composite && call.owner == "spinal/core/ElabInt$" &&
          receiver.contains(Module(call.owner)) && name == "widthOf" &&
          exact("(Lspinal/core/BaseType;)Lspinal/core/ElabInt;") &&
          args.size == 1 && args.forall(hardware))
        return Configuration
      if (composite && call.getOpcode == Opcodes.INVOKESPECIAL && name == "<init>" &&
          args.forall(integral) && composites.exists(_.constructionCall(call))) {
        receiver match {
          case Some(fresh: FreshComposite) if fresh.owner == call.owner && !fresh.initialized =>
            fresh.initialized = true
            return UnitValue
          case _ => fail("audited Bundle constructor lacks its exact uninitialized object identity")
        }
      }
      if (composite) {
''', "audited width query and constructor transition")
    text = replace_once(text,
        '''          return AssignmentTarget(args.head.asInstanceOf[Hardware])
''', '''          return AssignmentTarget(args.head)
''', "assignment target provenance")
    text = replace_once(text, '''            if (!receiver.contains(AssignmentTarget(Hardware(writable = true))))
              fail("write to callback argument or captured hardware is forbidden")
''', '''            receiver match {
              case Some(AssignmentTarget(value)) if writableValue(value) =>
              case _ => fail("write to callback argument or captured hardware is forbidden")
            }
''', "composite assignment authority")
    text = replace_once(text, '''            if (!receiver.contains(Hardware(writable = true)))
              fail("write to callback argument or captured hardware is forbidden")
''', '''            if (!receiver.exists(writableValue))
              fail("write to callback argument or captured hardware is forbidden")
''', "direct assignment authority")
    text = replace_once(text, '''          if (!receiver.contains(Hardware(writable = true))) fail("write to callback argument or captured hardware is forbidden")
''', '''          if (!receiver.exists(writableValue)) fail("write to callback argument or captured hardware is forbidden")
''', "scalar assignment authority")
    text = replace_once(text, '''        case Some(Module(owner)) if owner == call.owner && !nativeModule(owner) =>
          requirePureModule(owner)
          return audit(owner, name, call.desc, args, receiver, depth + 1)
''', '''        case Some(Module(owner)) if owner == call.owner && !nativeModule(owner) =>
          if (!composites.exists(_.constructionModule(owner))) requirePureModule(owner)
          return audit(owner, name, call.desc, args, receiver, depth + 1)
''', "audited companion invocation")
    CERTIFIED.write_text(text)


def patch_tests() -> None:
    widening = load_exact(WIDENING_TEST)
    widening = replace_once(widening, '''  private def typedWidth(value: BaseType): ElabInt =
    ElabInt.fromExpression(ParameterizedWidth.expressionOf(value)
      .getOrElse(ElabInt.literal(value.getBitsWidth).expression))
''', '''  private def typedWidth(value: BaseType): ElabInt =
    ElabInt.widthOf(value)
''', "widening exact typed-width query")
    WIDENING_TEST.write_text(widening)

    saturation = load_exact(SATURATION_TEST)
    saturation = replace_once(saturation,
        '''    val result = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = false, bitVectorWidthMax = 8192)
''', '''    val result = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, bitVectorWidthMax = 8192)
''', "supported saturation publication config")
    SATURATION_TEST.write_text(saturation)


def main() -> None:
    patch_elab_int()
    patch_composite_policy()
    patch_certified_policy()
    patch_tests()
    print("59i audited widening construction and exact UInt saturation policy staged")


if __name__ == "__main__":
    main()
