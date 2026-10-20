package spinal.core.internals

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodInsnNode
import org.scalatest.funsuite.AnyFunSuite

/** The bytecode seam grants construction admission only. End-to-end nested
  * bridge fixtures and inherited bridge graphs prove reset/owner semantics.
  */
class TypedBalancedReductionCompositeBridgeInitializationTests extends AnyFunSuite {
  test("native aggregate initialization is bridge-only with one exact signature") {
    val policy = new TypedBalancedReductionCompositeCallbackPolicy(getClass.getClassLoader)
    val native = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "spinal/core/DataPimper",
      "init", "(Lspinal/core/Data;)Lspinal/core/Data;", false)
    assert(policy.nativeCall(native, bridge = true))
    assert(!policy.nativeCall(native, bridge = false))
    val wrongDescriptor = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "spinal/core/DataPimper",
      "init", "(Ljava/lang/Object;)Lspinal/core/Data;", false)
    assert(!policy.nativeCall(wrongDescriptor, bridge = true))
    val wrongOpcode = new MethodInsnNode(Opcodes.INVOKESTATIC, "spinal/core/DataPimper",
      "init", "(Lspinal/core/Data;)Lspinal/core/Data;", false)
    assert(!policy.nativeCall(wrongOpcode, bridge = true))
  }
}
