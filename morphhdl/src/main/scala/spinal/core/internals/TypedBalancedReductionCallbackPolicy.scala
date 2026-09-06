package spinal.core.internals

import java.lang.invoke.{MethodHandleInfo, SerializedLambda}
import scala.collection.JavaConverters._
import org.objectweb.asm.{ClassReader, Handle, Opcodes, Type}
import org.objectweb.asm.tree._

/** A deliberately small host-language contract, checked before either callback
  * executes. A uniform sampled hardware graph cannot prove Scala purity: a
  * counter hidden in a closure can change a later COUNT specialization.
  *
  * Admit compiler-generated, capture-free static lambdas only. Their complete
  * bytecode (including static adapters/helpers) may read arguments and locals,
  * call the enumerated native scalar construction methods, and return. Native
  * immutable source-location construction is admitted for DSL assignments.
  * Literal resize widths are allowed; native witness-width queries are not.
  * Only a bridge may branch, and its integer data can originate only in the
  * level argument or constants. Host fields, arbitrary calls, exceptions, allocations,
  * invokedynamic and loops all reject. This contract authorizes executing the
  * callback once for graph certification; it does not authorize native graph
  * replay, width transfer, associativity or publication by itself.
  */
private[spinal] object TypedBalancedReductionCallbackPolicy {
  private def fail(detail: String): Nothing =
    throw new IllegalArgumentException("MORPH-REDUCE-BALANCED-CALLBACK-UNSUPPORTED: " + detail)

  def requireSupported(op: AnyRef, bridge: AnyRef): Unit = {
    requireSupportedOperator(op)
    requireSupportedBridge(bridge)
  }

  def requireSupportedOperator(callback: AnyRef): Unit = check(callback, bridge = false)
  def requireSupportedBridge(callback: AnyRef): Unit = check(callback, bridge = true)

  private val scalarNames = Set("Bool", "Bits", "UInt", "SInt").map("spinal/core/" + _)
  private val dataNames = scalarNames ++ Set("spinal/core/Data", "spinal/core/BaseType", "spinal/core/BitVector")
  private val dataDescriptors = dataNames.map(name => "L" + name + ";")
  private val binaryNames = Set("$amp", "$bar", "$up", "$plus", "$plus$up", "$times", "min", "max")
  private val nativeModules = Set("RegNext", "U", "S", "B", "package").map("spinal/core/" + _ + "$")

  private def check(callback: AnyRef, bridge: Boolean): Unit = {
    if (callback == null) fail("callback must be present")
    val cls = callback.getClass
    if (!cls.isSynthetic || !java.lang.reflect.Modifier.isFinal(cls.getModifiers) ||
        cls.getDeclaredFields.nonEmpty)
      fail("callback must be a capture-free compiler lambda")
    val serialized = try {
      val method = cls.getDeclaredMethod("writeReplace")
      method.setAccessible(true)
      method.invoke(callback) match {
        case value: SerializedLambda => value
        case _ => fail("callback serialization does not identify a static lambda body")
      }
    } catch {
      case error: IllegalArgumentException => throw error
      case _: ReflectiveOperationException => fail("callback has no inspectable compiler lambda body")
      case _: SecurityException => fail("callback body cannot be inspected")
    }
    if (serialized.getCapturedArgCount != 0 ||
        serialized.getImplMethodKind != MethodHandleInfo.REF_invokeStatic ||
        serialized.getFunctionalInterfaceClass != "scala/Function2" ||
        serialized.getFunctionalInterfaceMethodName != "apply")
      fail("only capture-free static Scala Function2 callbacks are supported")
    val loader = cls.getClassLoader
    val owner = serialized.getImplClass
    val clazz = new ClassNode(Opcodes.ASM9)
    val stream = Option(loader.getResourceAsStream(owner + ".class"))
      .getOrElse(fail("exact callback class bytes are unavailable"))
    try new ClassReader(stream).accept(clazz, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
    finally stream.close()
    if (clazz.name != owner) fail("callback class resource changed its declared identity")
    val methods = clazz.methods.asScala.toVector
    val exactCallSite = methods.exists(_.instructions.toArray.exists {
      case call: InvokeDynamicInsnNode =>
        call.bsm.getOwner == "java/lang/invoke/LambdaMetafactory" &&
          Set("metafactory", "altMetafactory")(call.bsm.getName) &&
          Type.getArgumentTypes(call.desc).isEmpty &&
          Type.getReturnType(call.desc).getDescriptor == "Lscala/Function2;" &&
          call.bsmArgs.exists {
            case body: Handle => body.getTag == Opcodes.H_INVOKESTATIC &&
              body.getOwner == owner && body.getName == serialized.getImplMethodName &&
              body.getDesc == serialized.getImplMethodSignature
            case _ => false
          }
      case _ => false
    })
    if (!exactCallSite) fail("callback body lacks its exact capture-free JVM lambda call site")
    val active = scala.collection.mutable.Set.empty[(String, String)]
    val done = scala.collection.mutable.Set.empty[(String, String)]

    def audit(name: String, descriptor: String, depth: Int): Unit = {
      val key = name -> descriptor
      if (done(key)) return
      if (depth > 8 || active(key)) fail("recursive callback helper calls are unsupported")
      val method = methods.filter(value => value.name == name && value.desc == descriptor) match {
        case Vector(value) => value
        case _ => fail("exact static callback method is missing or ambiguous")
      }
      if ((method.access & Opcodes.ACC_STATIC) == 0 ||
          (method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNCHRONIZED)) != 0 ||
          !method.tryCatchBlocks.isEmpty)
        fail("callback methods must be ordinary static code without exception handlers")
      val arguments = Type.getArgumentTypes(descriptor).toVector
      if (arguments.size != 2 || !arguments.forall { arg =>
          dataDescriptors(arg.getDescriptor) || arg.getDescriptor == "Ljava/lang/Object;" ||
            (bridge && arg.getSort == Type.INT)
        } || !Set(Type.OBJECT).contains(Type.getReturnType(descriptor).getSort))
        fail("callback helper changed the two-argument scalar/level signature")
      active += key
      val instructions = method.instructions.toArray.toVector
      val positions = instructions.zipWithIndex.toMap
      def forward(label: LabelNode, from: Int): Unit =
        if (!positions.get(label).exists(_ > from)) fail("callback loops and backward branches are unsupported")
      instructions.zipWithIndex.foreach { case (instruction, index) =>
        instruction match {
          case _: LabelNode | _: FrameNode | _: LineNumberNode =>
          case insn: VarInsnNode =>
            if (!Set(Opcodes.ALOAD, Opcodes.ASTORE).contains(insn.getOpcode) &&
                !(bridge && Set(Opcodes.ILOAD, Opcodes.ISTORE).contains(insn.getOpcode)))
              fail("callback may only read/write scalar locals and the bridge level")
          case insn: TypeInsnNode =>
            val cast = insn.getOpcode == Opcodes.CHECKCAST &&
              (dataNames(insn.desc) || (bridge && insn.desc == "spinal/core/DataPrimitives"))
            val location = bridge && insn.getOpcode == Opcodes.NEW && insn.desc == "spinal/idslplugin/Location"
            if (!cast && !location)
              fail("callback allocation and non-scalar casts are unsupported")
          case insn: InsnNode =>
            val common = Set(Opcodes.NOP, Opcodes.ARETURN, Opcodes.ACONST_NULL, Opcodes.DUP, Opcodes.POP)
            val integerConstants = insn.getOpcode >= Opcodes.ICONST_M1 && insn.getOpcode <= Opcodes.ICONST_5
            if (!common(insn.getOpcode) && !integerConstants)
              fail("unsupported callback opcode " + insn.getOpcode)
          case insn: IntInsnNode =>
            if (!Set(Opcodes.BIPUSH, Opcodes.SIPUSH).contains(insn.getOpcode))
              fail("unsupported callback integer instruction")
          case insn: LdcInsnNode =>
            if (!(insn.cst.isInstanceOf[java.lang.Integer] || (bridge && insn.cst.isInstanceOf[String])))
              fail("callbacks may not read object, string or floating constants")
          case insn: FieldInsnNode =>
            val module = insn.name == "MODULE$" && nativeModules(insn.owner) && insn.desc == "L" + insn.owner + ";"
            val unit = insn.owner == "scala/runtime/BoxedUnit" && insn.name == "UNIT" &&
              insn.desc == "Lscala/runtime/BoxedUnit;"
            if (!bridge || insn.getOpcode != Opcodes.GETSTATIC || (!module && !unit))
              fail("callbacks may not read or write host fields")
          case insn: MethodInsnNode =>
            if (insn.owner == owner && insn.getOpcode == Opcodes.INVOKESTATIC)
              audit(insn.name, insn.desc, depth + 1)
            else if (!nativeCall(insn, bridge))
              fail("unsupported callback call " + insn.owner + "." + insn.name + insn.desc)
          case insn: JumpInsnNode =>
            val allowed = Set(Opcodes.GOTO, Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT,
              Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE, Opcodes.IF_ICMPEQ,
              Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
              Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE)
            if (!bridge || !allowed(insn.getOpcode)) fail("only bridge-level integer conditions are supported")
            forward(insn.label, index)
          case insn: TableSwitchInsnNode if bridge =>
            (insn.labels.asScala.toVector :+ insn.dflt).foreach(forward(_, index))
          case insn: LookupSwitchInsnNode if bridge =>
            (insn.labels.asScala.toVector :+ insn.dflt).foreach(forward(_, index))
          case _ => fail("unsupported callback instruction " + instruction.getClass.getSimpleName)
        }
      }
      active -= key
      done += key
    }
    audit(serialized.getImplMethodName, serialized.getImplMethodSignature, 0)
  }

  private def nativeCall(call: MethodInsnNode, bridge: Boolean): Boolean = {
    val scalarBinary = call.getOpcode == Opcodes.INVOKEVIRTUAL && scalarNames(call.owner) &&
      binaryNames(call.name) && {
        val args = Type.getArgumentTypes(call.desc)
        args.length == 1 && (dataDescriptors(args(0).getDescriptor) ||
          args(0).getDescriptor == "Ljava/lang/Object;") &&
          (dataDescriptors(Type.getReturnType(call.desc).getDescriptor) ||
            Type.getReturnType(call.desc).getDescriptor == "Ljava/lang/Object;")
      }
    if (scalarBinary) return !bridge
    // This method constructs a fresh native value. In particular, do not
    // admit setWidth/getWidth: the former mutates an operand and the latter
    // erases a symbolic width to its current native witness. Width transfer
    // and graph certification still decide whether a resized result replays.
    if (call.getOpcode == Opcodes.INVOKEVIRTUAL && scalarNames(call.owner) &&
        dataDescriptors(Type.getReturnType(call.desc).getDescriptor)) {
      if (call.name == "resize" && Type.getArgumentTypes(call.desc).toVector.map(_.getDescriptor) == Vector("I"))
        return true
    }
    if (!bridge) return false
    if (call.getOpcode == Opcodes.INVOKESPECIAL && call.owner == "spinal/idslplugin/Location" &&
        call.name == "<init>" && call.desc == "(Ljava/lang/String;II)V") return true
    if (call.getOpcode == Opcodes.INVOKEINTERFACE && call.owner == "spinal/core/DataPrimitives" &&
        call.name == "init" && call.desc == "(Lspinal/core/Data;)Lspinal/core/Data;") return true
    if (call.getOpcode == Opcodes.INVOKESTATIC && call.owner == "scala/runtime/BoxesRunTime" &&
        call.name == "unboxToInt" && call.desc == "(Ljava/lang/Object;)I") return true
    if (call.getOpcode != Opcodes.INVOKEVIRTUAL) return false
    if (call.owner == "spinal/core/package$") {
      if (Set("UInt", "SInt", "Bits", "Bool")(call.name) &&
          call.desc == "(Lscala/runtime/BoxedUnit;)Lspinal/core/" + call.name + ";") return true
      if (Set("UInt$default$1", "SInt$default$1", "Bits$default$1", "Bool$default$1")(call.name) &&
          call.desc == "()V") return true
    }
    if (call.owner == "spinal/core/RegNext$")
      return (call.name == "apply" && call.desc == "(Lspinal/core/Data;Lspinal/core/Data;)Lspinal/core/Data;") ||
        (call.name == "apply$default$2" && call.desc == "()Lspinal/core/Data;")
    if (Set("spinal/core/U$", "spinal/core/S$", "spinal/core/B$")(call.owner))
      return call.name == "apply" && call.desc == "(I)Lspinal/core/BitVector;"
    if (dataNames(call.owner)) {
      if (call.name == "setAsReg" && Type.getArgumentTypes(call.desc).isEmpty &&
          dataDescriptors(Type.getReturnType(call.desc).getDescriptor)) return true
      if (call.name == "$colon$eq" && call.desc == "(Lspinal/core/Data;Lspinal/idslplugin/Location;)V") return true
      if (Set("getZero", "getZeroUnconstrained")(call.name) && Type.getArgumentTypes(call.desc).isEmpty &&
          dataDescriptors(Type.getReturnType(call.desc).getDescriptor)) return true
      if (call.name == "initFrom" && call.desc == "(Ljava/lang/Object;Ljava/lang/Object;)V") return true
      if (call.name == "initFrom$default$2" && call.desc == "()Ljava/lang/Object;") return true
      if (call.name == "init" && call.desc == "(Lspinal/core/Data;)Lspinal/core/Data;") return true
    }
    false
  }
}
