package spinal.core.internals

import java.lang.invoke.{MethodHandleInfo, SerializedLambda}
import java.lang.reflect.Modifier
import org.objectweb.asm.{ClassReader, Handle, Opcodes, Type}
import org.objectweb.asm.tree._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal
import spinal.core._

/** Pre-execution effect certification of a deliberately bounded Scala subset.
  * This is an abstract interpreter over exact JVM bodies, not callback sampling.
  * Values carry read-only/fresh provenance through locals, helper arguments and
  * nested `when` closures. No host fields, loops, reflection, exception handlers
  * or unknown calls execute. Graph/width/topology certification is separate.
  */
private[spinal] object TypedBalancedReductionCertifiedCallbackPolicy {
  type CaptureSchema = TypedBalancedReductionCaptureSchema
  private def fail(detail: String): Nothing = throw new IllegalArgumentException(
    "MORPH-REDUCE-BALANCED-CALLBACK-UNSUPPORTED: " + detail)

  private sealed trait Value
  private final case class Hardware(writable: Boolean) extends Value
  private case object Configuration extends Value
  private case object Count extends Value
  private case object Integer extends Value
  private case object Text extends Value
  private case object UnitValue extends Value
  private case object NullValue extends Value
  private case object Location extends Value
  private case object Condition extends Value
  private final case class Module(owner: String) extends Value
  private final case class Nested(body: Handle, captures: Vector[Value]) extends Value

  private val scalars = Set("Bool", "Bits", "UInt", "SInt").map("spinal/core/" + _)
  private val data = scalars ++ Set("spinal/core/Data", "spinal/core/BaseType", "spinal/core/BitVector")
  private val nativeModules = Set("package", "U", "S", "B", "Mux", "when", "ElabInt",
    "ParameterizedNative", "BitCount", "package$IntBuilder").map("spinal/core/" + _ + "$")
  private val binary = Set("$amp", "$bar", "$up", "$plus", "$plus$up", "$minus", "$minus$up",
    "$times", "$less", "$greater", "$less$eq", "$greater$eq", "$eq$eq$eq", "$eq$div$eq",
    "min", "max", "$hash$hash")
  private val unary = Set("unary_$tilde", "unary_$bang", "unary_$minus", "asBits", "asUInt", "asSInt",
    "asBool", "msb", "lsb", "xorR", "orR", "andR")

  def requireSupportedOperator(callback: AnyRef): CaptureSchema = {
    if (callback == null) fail("callback must be present")
    val cls = callback.getClass
    if (!cls.isSynthetic || !Modifier.isFinal(cls.getModifiers) ||
        cls.getDeclaredFields.exists(field => !Modifier.isFinal(field.getModifiers)))
      fail("callback must be an immutable compiler-generated lambda")
    def serialized(): SerializedLambda = try {
      val method = cls.getDeclaredMethod("writeReplace")
      method.setAccessible(true)
      method.invoke(callback) match {
        case value: SerializedLambda => value
        case _ => fail("callback does not identify its exact JVM body")
      }
    } catch {
      case error: IllegalArgumentException => throw error
      case _: ReflectiveOperationException => fail("callback has no inspectable compiler lambda body")
      case _: SecurityException => fail("callback body cannot be inspected")
    }
    val lambda = serialized()
    if (lambda.getImplMethodKind != MethodHandleInfo.REF_invokeStatic ||
        lambda.getFunctionalInterfaceClass != "scala/Function2" ||
        lambda.getFunctionalInterfaceMethodName != "apply")
      fail("only static compiler Scala Function2 bodies are supported")
    def captures(value: SerializedLambda): Vector[AnyRef] =
      Vector.tabulate(value.getCapturedArgCount)(index => value.getCapturedArg(index).asInstanceOf[AnyRef])
    val captured = captures(lambda)
    val schema = TypedBalancedReductionCaptureSchema(callback, captured, () => captures(serialized()))
    val inspector = new Inspector(cls.getClassLoader, lambda.getImplClass)
    inspector.requireCallSite(lambda)
    val arguments = captured.map {
      case _: ElabInt => Configuration
      case _: BaseType => Hardware(writable = false)
      case _ => fail("capture lacks an explicit schema entry")
    } ++ Vector(Hardware(writable = false), Hardware(writable = false))
    inspector.audit(lambda.getImplClass, lambda.getImplMethodName, lambda.getImplMethodSignature,
      arguments, None, 0) match {
      case _: Hardware =>
      case _ => fail("operator must return a native scalar value")
    }
    schema
  }

  private final class Inspector(loader: ClassLoader, root: String) {
    private val classes = mutable.Map.empty[String, ClassNode]
    private val active = mutable.Set.empty[(String, String, String)]
    private val certifiedModules = mutable.Set.empty[String]
    private var instructionBudget = 8192

    private def clazz(owner: String): ClassNode = classes.getOrElseUpdate(owner, {
      val result = new ClassNode(Opcodes.ASM9)
      val stream = Option(loader.getResourceAsStream(owner + ".class"))
        .getOrElse(fail("exact class bytes are unavailable for " + owner))
      try new ClassReader(stream).accept(result, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
      catch {
        case NonFatal(_) => fail("exact class bytes cannot be inspected for " + owner)
      }
      finally stream.close()
      if (result.name != owner) fail("class resource changed its exact identity")
      result
    })

    def requireCallSite(lambda: SerializedLambda): Unit = {
      val expectedCaptures = Type.getArgumentTypes(lambda.getImplMethodSignature).toVector
        .take(lambda.getCapturedArgCount)
      val found = clazz(root).methods.asScala.exists(_.instructions.toArray.exists {
        case call: InvokeDynamicInsnNode =>
          isLambda(call) && Type.getArgumentTypes(call.desc).toVector == expectedCaptures &&
            Type.getReturnType(call.desc).getDescriptor == "Lscala/Function2;" &&
            call.bsmArgs.exists {
              case handle: Handle => handle.getTag == Opcodes.H_INVOKESTATIC && handle.getOwner == root &&
                handle.getName == lambda.getImplMethodName && handle.getDesc == lambda.getImplMethodSignature
              case _ => false
            }
        case _ => false
      })
      if (!found) fail("callback lacks its exact JVM lambda call site and capture signature")
    }

    private def isLambda(call: InvokeDynamicInsnNode): Boolean =
      call.bsm.getOwner == "java/lang/invoke/LambdaMetafactory" &&
        Set("metafactory", "altMetafactory")(call.bsm.getName)

    /** Scala helper objects may initialize only their own field-free singleton.
      * Inspect bytes before a GETSTATIC can initialize user code. */
    private def requirePureModule(owner: String): Unit = if (!certifiedModules(owner)) {
      val node = clazz(owner)
      if (!owner.endsWith("$") || (node.access & Opcodes.ACC_FINAL) == 0 ||
          node.superName != "java/lang/Object" || !node.interfaces.isEmpty ||
          node.fields.asScala.exists(field => field.name != "MODULE$" ||
            field.desc != "L" + owner + ";" || (field.access & Opcodes.ACC_STATIC) == 0))
        fail("helper receiver must be a final field-free Scala module")
      def real(method: MethodNode): Vector[AbstractInsnNode] =
        method.instructions.toArray.toVector.filter(_.getOpcode >= 0)
      val constructor = node.methods.asScala.find(m => m.name == "<init>" && m.desc == "()V")
        .getOrElse(fail("helper module has no exact empty constructor"))
      val initializer = node.methods.asScala.find(m => m.name == "<clinit>" && m.desc == "()V")
        .getOrElse(fail("helper module has no exact singleton initializer"))
      val init = real(initializer)
      val ctor = real(constructor)
      def alloc(instruction: AbstractInsnNode): Boolean = instruction match {
        case value: TypeInsnNode => value.getOpcode == Opcodes.NEW && value.desc == owner
        case _ => false
      }
      def selfConstructor(instruction: AbstractInsnNode): Boolean = instruction match {
        case value: MethodInsnNode => value.getOpcode == Opcodes.INVOKESPECIAL && value.owner == owner &&
          value.name == "<init>" && value.desc == "()V"
        case _ => false
      }
      def loadThis(instruction: AbstractInsnNode): Boolean = instruction match {
        case value: VarInsnNode => value.getOpcode == Opcodes.ALOAD && value.`var` == 0
        case _ => false
      }
      def objectConstructor(instruction: AbstractInsnNode): Boolean = instruction match {
        case value: MethodInsnNode => value.getOpcode == Opcodes.INVOKESPECIAL &&
          value.owner == "java/lang/Object" && value.name == "<init>" && value.desc == "()V"
        case _ => false
      }
      def publish(instruction: AbstractInsnNode): Boolean = instruction match {
        case value: FieldInsnNode => value.getOpcode == Opcodes.PUTSTATIC && value.owner == owner &&
          value.name == "MODULE$" && value.desc == "L" + owner + ";"
        case _ => false
      }
      // Scala 2.12 publishes MODULE$ in the constructor; Scala 2.13 may
      // publish it in <clinit>. Both exact compiler forms have the same sole effect.
      val ctorPublishes = ctor.size == 5 && loadThis(ctor(0)) && objectConstructor(ctor(1)) &&
        loadThis(ctor(2)) && publish(ctor(3)) && ctor(4).getOpcode == Opcodes.RETURN
      val ctorInert = ctor.size == 3 && loadThis(ctor(0)) && objectConstructor(ctor(1)) &&
        ctor(2).getOpcode == Opcodes.RETURN
      val initConstructs = (init.size == 3 && alloc(init(0)) && selfConstructor(init(1)) &&
        init(2).getOpcode == Opcodes.RETURN) || (init.size == 4 && alloc(init(0)) &&
        init(1).getOpcode == Opcodes.DUP && selfConstructor(init(2)) && init(3).getOpcode == Opcodes.RETURN)
      val initPublishes = init.size == 5 && alloc(init(0)) && init(1).getOpcode == Opcodes.DUP &&
        selfConstructor(init(2)) && publish(init(3)) && init(4).getOpcode == Opcodes.RETURN
      if (!((ctorPublishes && initConstructs) || (ctorInert && initPublishes)) ||
          !constructor.tryCatchBlocks.isEmpty || !initializer.tryCatchBlocks.isEmpty)
        fail("helper singleton initialization has unproved effects: " + owner)
      certifiedModules += owner
    }

    def audit(owner: String, name: String, descriptor: String, args: Vector[Value],
        receiver: Option[Value], depth: Int): Value = {
      if (owner != root && receiver.isEmpty) {
        if (owner.endsWith("$")) requirePureModule(owner)
        else {
          val node = clazz(owner)
          if (node.superName != "java/lang/Object" || !node.interfaces.isEmpty ||
              node.methods.asScala.exists(_.name == "<clinit>"))
            fail("foreign static helper has unproved class/supertype initialization")
        }
      }
      val key = (owner, name, descriptor)
      if (depth > 12 || active(key)) fail("recursive or overly deep callback helpers are unsupported")
      val method = clazz(owner).methods.asScala.filter(m => m.name == name && m.desc == descriptor).toVector match {
        case Vector(value) => value
        case _ => fail("exact helper body is missing or ambiguous")
      }
      if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNCHRONIZED)) != 0 ||
          !method.tryCatchBlocks.isEmpty || Type.getArgumentTypes(descriptor).length != args.size ||
          ((method.access & Opcodes.ACC_STATIC) == 0) != receiver.nonEmpty)
        fail("helper method has unsupported signature, synchronization or exception effects")
      active += key
      val locals = mutable.Map.empty[Int, Value]
      var slot = 0
      receiver.foreach { value => locals(slot) = value; slot += 1 }
      Type.getArgumentTypes(descriptor).zip(args).foreach { case (kind, value) =>
        if (kind.getSize != 1) fail("wide host primitive helper arguments are unsupported")
        locals(slot) = value; slot += 1
      }
      var stack = Vector.empty[Value]
      def push(value: Value): Unit = stack :+= value
      def pop(): Value = {
        if (stack.isEmpty) fail("malformed callback operand stack")
        val value = stack.last; stack = stack.init; value
      }
      var returned: Option[Value] = None
      method.instructions.toArray.foreach { instruction =>
        instructionBudget -= 1
        if (instructionBudget < 0) fail("callback complete call graph exceeds its bounded instruction budget")
        if (returned.nonEmpty && instruction.getOpcode >= 0) fail("unreachable or multi-return callback code is unsupported")
        instruction match {
          case _: LabelNode | _: FrameNode | _: LineNumberNode =>
          case value: VarInsnNode => value.getOpcode match {
            case Opcodes.ALOAD | Opcodes.ILOAD => push(locals.getOrElse(value.`var`, fail("uninitialized local")))
            case Opcodes.ASTORE | Opcodes.ISTORE => locals(value.`var`) = pop()
            case _ => fail("unsupported host local operation")
          }
          case value: InsnNode => value.getOpcode match {
            case Opcodes.NOP =>
            case Opcodes.DUP => val top = pop(); push(top); push(top)
            case Opcodes.POP => pop()
            case Opcodes.ACONST_NULL => push(NullValue)
            case Opcodes.ICONST_M1 | Opcodes.ICONST_0 | Opcodes.ICONST_1 | Opcodes.ICONST_2 |
                Opcodes.ICONST_3 | Opcodes.ICONST_4 | Opcodes.ICONST_5 => push(Integer)
            case Opcodes.ARETURN | Opcodes.IRETURN => returned = Some(pop())
            case Opcodes.RETURN => returned = Some(UnitValue)
            case _ => fail("unsupported host opcode " + value.getOpcode)
          }
          case value: IntInsnNode if Set(Opcodes.BIPUSH, Opcodes.SIPUSH)(value.getOpcode) => push(Integer)
          case value: LdcInsnNode => value.cst match {
            case _: java.lang.Integer => push(Integer)
            case _: String => push(Text)
            case _ => fail("unsupported host literal")
          }
          case value: TypeInsnNode if value.getOpcode == Opcodes.CHECKCAST =>
            if (!(data(value.desc) || Set("java/lang/Object", "spinal/core/ElabInt", "spinal/core/ParameterizedBitCount",
                "spinal/core/BitCount")(value.desc))) fail("unsupported callback cast")
          case value: TypeInsnNode if value.getOpcode == Opcodes.NEW && value.desc == "spinal/idslplugin/Location" =>
            push(Location)
          case value: FieldInsnNode =>
            if (value.getOpcode != Opcodes.GETSTATIC) fail("host fields and external mutation are forbidden")
            if (value.owner == "scala/runtime/BoxedUnit" && value.name == "UNIT") push(UnitValue)
            else if (value.name == "MODULE$" && value.desc == "L" + value.owner + ";") {
              if (!nativeModules(value.owner)) requirePureModule(value.owner)
              push(Module(value.owner))
            } else fail("unknown host field read")
          case value: InvokeDynamicInsnNode =>
            if (!isLambda(value) || !Set("Lscala/Function0;", "Lscala/runtime/java8/JFunction0$mcV$sp;")(
                Type.getReturnType(value.desc).getDescriptor))
              fail("only exact nested native when closures are admitted")
            val captures = Type.getArgumentTypes(value.desc).toVector.reverse.map(_ => pop()).reverse
            val body = value.bsmArgs.collect { case handle: Handle => handle }.toVector match {
              case Vector(handle) if handle.getTag == Opcodes.H_INVOKESTATIC => handle
              case _ => fail("nested callback has no exact static body")
            }
            val nested = Nested(body, captures)
            audit(body.getOwner, body.getName, body.getDesc, captures, None, depth + 1)
            push(nested)
          case value: MethodInsnNode =>
            val arguments = Type.getArgumentTypes(value.desc).toVector.reverse.map(_ => pop()).reverse
            val target = if (value.getOpcode == Opcodes.INVOKESTATIC) None else Some(pop())
            val result = call(value, target, arguments, depth)
            if (Type.getReturnType(value.desc).getSort != Type.VOID) push(result)
          case _ => fail("branches, loops, allocation and unknown callback effects are unsupported")
        }
      }
      active -= key
      if (stack.nonEmpty) fail("callback leaves malformed operand stack")
      returned.getOrElse(fail("callback lacks a certified return"))
    }

    private def call(call: MethodInsnNode, receiver: Option[Value], args: Vector[Value], depth: Int): Value = {
      val name = call.name
      val argumentTypes = Type.getArgumentTypes(call.desc).toVector.map(_.getDescriptor)
      val resultType = Type.getReturnType(call.desc).getDescriptor
      def dataType(descriptor: String): Boolean = data.exists(name => descriptor == "L" + name + ";")
      def erasedDataType(descriptor: String): Boolean = dataType(descriptor) || descriptor == "Ljava/lang/Object;"
      def exact(descriptor: String): Boolean = call.desc == descriptor
      def hardware(value: Value): Boolean = value.isInstanceOf[Hardware]
      def integral(value: Value): Boolean = value == Integer || value == Configuration || value == Count
      if (call.getOpcode == Opcodes.INVOKEVIRTUAL && data(call.owner) && receiver.exists(hardware)) {
        if (binary(name) && args.size == 1 && args.forall(hardware) &&
            argumentTypes.forall(erasedDataType) && erasedDataType(resultType)) return Hardware(writable = true)
        if (unary(name) && args.isEmpty && dataType(resultType))
          return Hardware(writable = !Set("msb", "lsb")(name))
        if (name == "resize" && args.size == 1 && args.forall(integral) && dataType(resultType) &&
            Set("I", "Lspinal/core/ElabInt;", "Lspinal/core/BitCount;")(argumentTypes.head))
          return Hardware(writable = true)
        // Extraction proxies can write through to their source. Never grant them write authority.
        if (name == "apply" && args.nonEmpty && args.size <= 2 && args.forall(v => integral(v) || hardware(v)) &&
            dataType(resultType) && Set(Vector("I"), Vector("Lspinal/core/UInt;"),
              Vector("I", "Lspinal/core/BitCount;"), Vector("Lspinal/core/UInt;", "Lspinal/core/BitCount;"))(argumentTypes))
          return Hardware(writable = false)
        if (Set("getZero", "getZeroUnconstrained")(name) && args.isEmpty && dataType(resultType))
          return Hardware(writable = true)
        if (name == "$colon$eq" && exact("(Lspinal/core/Data;Lspinal/idslplugin/Location;)V") &&
            args.size == 2 && hardware(args.head) && args(1) == Location) {
          if (!receiver.contains(Hardware(writable = true))) fail("write to callback argument or captured hardware is forbidden")
          return UnitValue
        }
      }
      if (call.getOpcode == Opcodes.INVOKEVIRTUAL && call.owner == "spinal/core/ElabInt" && receiver.contains(Configuration)) {
        if (Set("$plus", "$minus", "$times", "$div", "$percent")(name) && args.size == 1 &&
            (args.head == Integer || args.head == Configuration) &&
            (exact("(I)Lspinal/core/ElabInt;") || exact("(Lspinal/core/ElabInt;)Lspinal/core/ElabInt;")))
          return Configuration
        if (Set("log2Up", "addressWidth", "pow2")(name) && args.isEmpty && exact("()Lspinal/core/ElabInt;"))
          return Configuration
        if (Set("bit", "bits")(name) && args.isEmpty && exact("()Lspinal/core/ParameterizedBitCount;")) return Count
      }
      if (call.owner == "spinal/idslplugin/Location" && call.getOpcode == Opcodes.INVOKESPECIAL &&
          name == "<init>" && call.desc == "(Ljava/lang/String;II)V" && receiver.contains(Location) &&
          args == Vector(Text, Integer, Integer)) return UnitValue
      if (call.owner == "spinal/core/when$" && receiver.contains(Module(call.owner)) && name == "apply" &&
          exact("(Lspinal/core/Bool;Lscala/Function0;Lspinal/idslplugin/Location;)Lspinal/core/WhenContext;") &&
          args.size == 3 && hardware(args.head) && args(1).isInstanceOf[Nested] && args(2) == Location)
        return Condition
      if (call.owner == "spinal/core/WhenContext" && receiver.contains(Condition)) {
        if (name == "otherwise" && exact("(Lscala/Function0;)V") && args.size == 1 && args.head.isInstanceOf[Nested]) return UnitValue
        if (name == "elsewhen" && args.size == 3 && hardware(args.head) &&
            exact("(Lspinal/core/Bool;Lscala/Function0;Lspinal/idslplugin/Location;)Lspinal/core/WhenContext;") &&
            args(1).isInstanceOf[Nested] && args(2) == Location) return Condition
      }
      if (call.owner == "spinal/core/Mux$" && receiver.contains(Module(call.owner)) && name == "apply" &&
          exact("(Lspinal/core/Bool;Lspinal/core/Data;Lspinal/core/Data;)Lspinal/core/Data;") &&
          args.size == 3 && args.forall(hardware)) return Hardware(writable = true)
      if (Set("spinal/core/U$", "spinal/core/S$", "spinal/core/B$")(call.owner) &&
          receiver.contains(Module(call.owner)) && name == "apply" &&
          (exact("(I)Lspinal/core/BitVector;") || exact("(ILspinal/core/BitCount;)Lspinal/core/BitVector;")) &&
          (args == Vector(Integer) || args == Vector(Integer, Count))) return Hardware(writable = true)
      if (call.owner == "spinal/core/ElabInt$" && receiver.contains(Module(call.owner)) &&
          name == "literal" && exact("(I)Lspinal/core/ElabInt;") && args == Vector(Integer)) return Configuration
      if (Set("spinal/core/package$", "spinal/core/ParameterizedNative$")(call.owner) &&
          receiver.contains(Module(call.owner))) {
        if (Set("UInt", "SInt", "Bits", "Bool")(name) &&
            (exact("(Lspinal/core/BitCount;)Lspinal/core/" + name + ";") ||
              exact("(Lspinal/core/ParameterizedBitCount;)Lspinal/core/" + name + ";") ||
              exact("(Lscala/runtime/BoxedUnit;)Lspinal/core/" + name + ";")) &&
            (args == Vector(Count) || args == Vector(UnitValue))) return Hardware(writable = true)
        if (Set("UInt$default$1", "SInt$default$1", "Bits$default$1", "Bool$default$1")(name) && args.isEmpty && exact("()V"))
          return UnitValue
        if (name == "IntToBuilder" && exact("(I)Lspinal/core/package$IntBuilder;") && args == Vector(Integer)) return Count
        if (name == "IntToBuilder" && exact("(I)I") && args == Vector(Integer)) return Integer
        if (Set("IntToUInt", "IntToSInt", "IntToBits")(name) &&
            exact("(I)Lspinal/core/" + name.stripPrefix("IntTo") + ";") && args == Vector(Integer)) return Hardware(writable = true)
        if (Set("True", "False")(name) && exact("(Lspinal/idslplugin/Location;)Lspinal/core/Bool;") &&
            args == Vector(Location)) return Hardware(writable = true)
      }
      if (call.owner == "spinal/core/package$IntBuilder" && receiver.contains(Count) &&
          Set("bits", "bit")(name) && exact("()Lspinal/core/BitCount;") && args.isEmpty) return Count
      if (call.owner == "spinal/core/package$IntBuilder$" && receiver.contains(Module(call.owner)) &&
          Set("bits$extension", "bit$extension")(name) && exact("(I)Lspinal/core/BitCount;") && args == Vector(Integer))
        return Count
      if (call.getOpcode == Opcodes.INVOKESTATIC) {
        return audit(call.owner, name, call.desc, args, None, depth + 1)
      }
      receiver match {
        case Some(Module(owner)) if owner == call.owner && !nativeModules(owner) =>
          requirePureModule(owner)
          return audit(owner, name, call.desc, args, receiver, depth + 1)
        case _ =>
      }
      fail("unsupported callback call " + call.owner + "." + name + call.desc)
    }
  }
}
