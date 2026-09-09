#!/usr/bin/env python3
"""Admit only freshly allocated, fully audited native Bundle construction.

The composite callback policy already proves each Bundle class, immutable shape
constructor and constructor call graph. The outer abstract interpreter did not
model the JVM NEW/DUP/<init> state transition, so even that audited path was
rejected as generic host allocation. This source-bound development patch adds
an uninitialized composite value and allows it to become writable hardware only
after the exact audited constructor runs. Arbitrary Java/Scala allocation,
partially initialized values, Data constructor arguments, captured writes and
unknown factories remain rejected.
"""
from __future__ import annotations

import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CERTIFIED = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCertifiedCallbackPolicy.scala"
COMPOSITE = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeCallbackPolicy.scala"
TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeWideningConstructionPolicyTests.scala"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def git_blob(path: Path) -> str:
    raw = path.read_bytes()
    return hashlib.sha1((b"blob %d\0" % len(raw)) + raw).hexdigest()


def replace_once(text: str, before: str, after: str, label: str) -> str:
    require(text.count(before) == 1, f"{label} exact anchor count changed: {text.count(before)}")
    return text.replace(before, after, 1)


def main() -> None:
    require(git_blob(CERTIFIED) == "a9b37e7cf52df372a989fef5af76810dc012988a",
            "certified callback policy baseline changed")
    require(git_blob(COMPOSITE) == "29560d97f12bf3537151e55d65dae5816841db87",
            "composite callback policy baseline changed")
    require(not TEST.exists(), "widening construction policy test already exists")

    composite = COMPOSITE.read_text()
    composite = replace_once(composite, '''  def dataName(owner: String): Boolean = {
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
    * audited custom Bundle runtime class. Native scalar and host classes never
    * enter through this path. */
  def constructionType(owner: String): Boolean = {
    if (!customBundle(owner)) false
    else { auditBundle(owner); true }
  }

  /** Companion MODULE$ reads are admitted only when the companion belongs to
    * the same audited Bundle class and its initializer has no host state. */
  def constructionModule(owner: String): Boolean = {
    val result = owner.endsWith("$") && customBundle(owner.dropRight(1))
    if (result) auditCompanion(owner)
    result
  }

  /** The abstract interpreter may complete only the exact constructor of the
    * uninitialized Bundle value it already observed. auditBundle recursively
    * proves all constructor bodies and immutable shape arguments first. */
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
    COMPOSITE.write_text(composite)

    certified = CERTIFIED.read_text()
    certified = replace_once(certified, '''  private final case class Hardware(writable: Boolean) extends Value
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
''', "fresh composite value state")
    certified = replace_once(certified, '''    inspector.audit(lambda.getImplClass, lambda.getImplMethodName, lambda.getImplMethodSignature,
      arguments, None, 0) match {
      case _: Hardware =>
      case _ => fail("operator must return native hardware")
    }
''', '''    inspector.audit(lambda.getImplClass, lambda.getImplMethodName, lambda.getImplMethodSignature,
      arguments, None, 0) match {
      case value if hardwareValue(value) =>
      case _ => fail("operator must return initialized native hardware")
    }
''', "operator result state")
    certified = replace_once(certified, '''          case value: TypeInsnNode if value.getOpcode == Opcodes.NEW && value.desc == "spinal/idslplugin/Location" =>
            push(Location)
          case value: FieldInsnNode =>
''', '''          case value: TypeInsnNode if value.getOpcode == Opcodes.NEW && value.desc == "spinal/idslplugin/Location" =>
            push(Location)
          case value: TypeInsnNode if value.getOpcode == Opcodes.NEW && composite &&
              composites.exists(_.constructionType(value.desc)) =>
            push(new FreshComposite(value.desc))
          case value: FieldInsnNode =>
''', "audited NEW instruction")
    certified = replace_once(certified, '''            else if (value.name == "MODULE$" && value.desc == "L" + value.owner + ";") {
              if (!nativeModule(value.owner)) requirePureModule(value.owner)
              push(Module(value.owner))
''', '''            else if (value.name == "MODULE$" && value.desc == "L" + value.owner + ";") {
              if (!nativeModule(value.owner) &&
                  !composites.exists(_.constructionModule(value.owner))) requirePureModule(value.owner)
              push(Module(value.owner))
''', "audited companion module")
    certified = replace_once(certified, '''      def hardware(value: Value): Boolean = value.isInstanceOf[Hardware]
      def integral(value: Value): Boolean = value == Integer || value == Configuration || value == Count
      if (composite) {
''', '''      def hardware(value: Value): Boolean = hardwareValue(value)
      def integral(value: Value): Boolean = value == Integer || value == Configuration || value == Count
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
''', "audited constructor transition")
    certified = replace_once(certified,
        '''          return AssignmentTarget(args.head.asInstanceOf[Hardware])
''', '''          return AssignmentTarget(args.head)
''', "assignment target provenance")
    certified = replace_once(certified, '''            if (!receiver.contains(AssignmentTarget(Hardware(writable = true))))
              fail("write to callback argument or captured hardware is forbidden")
''', '''            receiver match {
              case Some(AssignmentTarget(value)) if writableValue(value) =>
              case _ => fail("write to callback argument or captured hardware is forbidden")
            }
''', "DataPimper fresh-write check")
    direct = '''            if (!receiver.contains(Hardware(writable = true)))
              fail("write to callback argument or captured hardware is forbidden")
'''
    replacement = '''            if (!receiver.exists(writableValue))
              fail("write to callback argument or captured hardware is forbidden")
'''
    require(certified.count(direct) == 1,
            f"direct composite assignment anchor count changed: {certified.count(direct)}")
    certified = certified.replace(direct, replacement, 1)
    generic = '''          if (!receiver.contains(Hardware(writable = true))) fail("write to callback argument or captured hardware is forbidden")
'''
    generic_replacement = '''          if (!receiver.exists(writableValue)) fail("write to callback argument or captured hardware is forbidden")
'''
    require(certified.count(generic) == 1,
            f"generic scalar assignment anchor count changed: {certified.count(generic)}")
    certified = certified.replace(generic, generic_replacement, 1)
    certified = replace_once(certified, '''        case Some(Module(owner)) if owner == call.owner && !nativeModule(owner) =>
          requirePureModule(owner)
          return audit(owner, name, call.desc, args, receiver, depth + 1)
''', '''        case Some(Module(owner)) if owner == call.owner && !nativeModule(owner) =>
          if (!composites.exists(_.constructionModule(owner))) requirePureModule(owner)
          return audit(owner, name, call.desc, args, receiver, depth + 1)
''', "audited companion call")
    CERTIFIED.write_text(certified)

    TEST.write_text(r'''package spinal.core.internals

import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

private object ForbiddenWideningHostAllocation {
  def combine(a: BalancedCompositeWideningValue,
      b: BalancedCompositeWideningValue): BalancedCompositeWideningValue = {
    val host = new java.lang.StringBuilder()
    host.append("not hardware")
    CompositeWideningPublicationHelpers.combine(a, b)
  }
}

private final class ForbiddenWideningHostAllocation(width: HdlInt,
    count: HdlInt) extends Component {
  private val initial = ElabInt.fromExpression(width.bits.expression.get)
  val values = in(Vec(BalancedCompositeWideningValue(initial, initial, initial, initial), count))
  val reduced = values.reduceBalancedTree(
    (a: BalancedCompositeWideningValue, b: BalancedCompositeWideningValue) =>
      ForbiddenWideningHostAllocation.combine(a, b))
}

class TypedBalancedReductionCompositeWideningConstructionPolicyTests extends AnyFunSuite {
  test("audited Bundle construction does not admit arbitrary host allocation") {
    val directory = Files.createTempDirectory("widening-forbidden-allocation-")
    val config = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false,
      bitVectorWidthMax = 65536)
    config.netlistFileName = "forbidden.v"
    val failure = intercept[Exception] {
      MorphVerilog(config) {
        new ForbiddenWideningHostAllocation(HdlInt.param("WIDTH", 5, 1, 16),
          HdlInt.param("COUNT", 3, 1, 5))
      }
    }
    val messages = scala.collection.mutable.ArrayBuffer.empty[String]
    var error: Throwable = failure
    while (error != null) {
      messages += Option(error.getMessage).getOrElse("")
      error = error.getCause
    }
    assert(messages.mkString("\n").contains("MORPH-REDUCE-BALANCED-CALLBACK-UNSUPPORTED"))
    assert(!Files.exists(directory.resolve("forbidden.v")))
  }
}
''')
    print("admitted only fully audited fresh Bundle construction")
    print("retained rejection of arbitrary host allocation")


if __name__ == "__main__":
    main()
