package spinal.core.internals

import scala.collection.JavaConverters._
import scala.collection.mutable
import org.objectweb.asm.{ClassReader, Handle, Opcodes, Type}
import org.objectweb.asm.tree._

/** The additional host-language surface needed to construct ordinary composite
  * callbacks. User Bundle methods are never trusted by their names: an accessor
  * must be exactly a final field read, and every constructor which native clone
  * can invoke is inspected before the reduction callback runs. This remains a
  * bytecode admission check, not the graph/width/publication certificate.
  */
private[internals] final class TypedBalancedReductionCompositeCallbackPolicy(loader: ClassLoader) {
  private def fail(detail: String): Nothing =
    throw new IllegalArgumentException("MORPH-REDUCE-BALANCED-CALLBACK-UNSUPPORTED: " + detail)

  private val scalarNames = Set("Bool", "Bits", "UInt", "SInt").map("spinal/core/" + _)
  private val nativeData = scalarNames ++ Set("spinal/core/Data", "spinal/core/BaseType",
    "spinal/core/BitVector", "spinal/core/Bundle", "spinal/core/MultiData", "spinal/core/Vec")
  private val immutableNames = Set("spinal/core/ElabInt", "morphhdl/frontend/HdlInt")
  private val classes = mutable.Map.empty[String, ClassNode]
  private val checked = mutable.Set.empty[String]
  private val active = mutable.Set.empty[String]
  private val checkedCompanions = mutable.Set.empty[String]
  private val inspectedValues = new java.util.IdentityHashMap[spinal.core.Data, java.lang.Boolean]()

  /** Native clone may dispatch through an object's actual class or hardtype,
    * even when the callback signature says only Data/Bundle. Inspect those
    * exact values before any callback or reflective constructor can execute.
    */
  def requireValue(value: spinal.core.Data): Unit = {
    val path = new java.util.IdentityHashMap[spinal.core.Data, java.lang.Boolean]()
    def visit(data: spinal.core.Data, depth: Int): Unit = {
      if (data == null) fail("composite callback input contains null data")
      if (path.containsKey(data)) fail("composite callback input has a cyclic Data container")
      if (inspectedValues.containsKey(data)) return
      if (depth > 64 || inspectedValues.size() >= 32768)
        fail("composite callback input tree exceeds the inspection budget")
      inspectedValues.put(data, java.lang.Boolean.TRUE)
      path.put(data, java.lang.Boolean.TRUE)
      val owner = data.getClass.getName.replace('.', '/')
      if (scalarNames(owner)) ()
      else if (owner == "spinal/core/Vec") {
        data.asInstanceOf[spinal.core.Vec[spinal.core.Data]].vec.foreach(child => visit(child, depth + 1))
      } else if (customBundle(owner)) {
        auditBundle(owner)
        val bundle = data.asInstanceOf[spinal.core.Bundle]
        if (bundle.hardtype != null)
          fail("composite callback Bundle has an opaque native clone factory: " + owner)
        bundle.elements.foreach { case (_, child) => visit(child, depth + 1) }
      } else fail("composite callback input has an unsupported runtime Data class: " + owner)
      path.remove(data)
    }
    visit(value, 0)
  }

  private def read(owner: String): ClassNode = classes.getOrElseUpdate(owner, {
    val node = new ClassNode(Opcodes.ASM9)
    val stream = Option(loader.getResourceAsStream(owner + ".class"))
      .getOrElse(fail("composite callback class bytes are unavailable: " + owner))
    try new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
    finally stream.close()
    if (node.name != owner) fail("composite callback class resource changed its identity")
    node
  })

  private def customBundle(owner: String): Boolean = {
    if (nativeData(owner) || owner.startsWith("java/") || owner.startsWith("scala/")) false
    else {
      val node = read(owner)
      node.superName == "spinal/core/Bundle" || node.superName == "spinal/core/BundleCase"
    }
  }

  def dataName(owner: String): Boolean = {
    if (nativeData(owner)) true
    else if (customBundle(owner)) { auditBundle(owner); true }
    else false
  }

  def dataDescriptor(descriptor: String): Boolean =
    descriptor.startsWith("L") && descriptor.endsWith(";") &&
      dataName(descriptor.substring(1, descriptor.length - 1))

  private def simple(method: MethodNode): Vector[AbstractInsnNode] =
    method.instructions.toArray.toVector.filter(_.getOpcode >= 0)

  private def exact(owner: String, name: String, descriptor: String): MethodNode =
    read(owner).methods.asScala.filter(m => m.name == name && m.desc == descriptor).toVector match {
      case Vector(method) => method
      case _ => fail("missing or ambiguous composite method " + owner + "." + name)
    }

  private def accessor(owner: String, name: String, descriptor: String, allowInteger: Boolean = false): Boolean = {
    if (!customBundle(owner) || Type.getArgumentTypes(descriptor).nonEmpty) return false
    val candidates = read(owner).methods.asScala.filter(m => m.name == name && m.desc == descriptor).toVector
    if (candidates.size != 1) return false
    val method = candidates.head
    if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE)) != 0 ||
        !method.tryCatchBlocks.isEmpty) return false
    simple(method) match {
      case Vector(load: VarInsnNode, field: FieldInsnNode, ret: InsnNode) =>
        load.getOpcode == Opcodes.ALOAD && load.`var` == 0 &&
          field.getOpcode == Opcodes.GETFIELD && field.owner == owner &&
          field.desc == Type.getReturnType(descriptor).getDescriptor &&
          read(owner).fields.asScala.exists(f => f.name == field.name && f.desc == field.desc &&
            (f.access & Opcodes.ACC_FINAL) != 0 && (f.access & Opcodes.ACC_STATIC) == 0) &&
          ((ret.getOpcode == Opcodes.ARETURN &&
            (dataDescriptor(field.desc) ||
              (Type.getReturnType(descriptor).getSort == Type.OBJECT &&
                immutableNames(Type.getReturnType(descriptor).getInternalName)))) ||
            (allowInteger && ret.getOpcode == Opcodes.IRETURN && field.desc == "I"))
      case _ => false
    }
  }

  private val modules = Set("spinal/core/cloneOf$", "spinal/core/Mux$", "spinal/core/U$",
    "spinal/core/S$", "spinal/core/B$", "spinal/core/package$", "spinal/core/RegNext$")

  def moduleField(field: FieldInsnNode): Boolean =
    field.getOpcode == Opcodes.GETSTATIC && field.name == "MODULE$" &&
      field.desc == "L" + field.owner + ";" && modules(field.owner)

  def nativeCall(call: MethodInsnNode, bridge: Boolean): Boolean = {
    val args = Type.getArgumentTypes(call.desc).toVector
    val result = Type.getReturnType(call.desc)
    if (call.getOpcode == Opcodes.INVOKESPECIAL && call.owner == "spinal/idslplugin/Location" &&
        call.name == "<init>" && call.desc == "(Ljava/lang/String;II)V") return true
    if (call.getOpcode == Opcodes.INVOKEINTERFACE && call.owner == "spinal/core/DataPrimitives" &&
        call.name == "$colon$eq" && call.desc == "(Lspinal/core/Data;Lspinal/idslplugin/Location;)V") return true
    if (call.getOpcode != Opcodes.INVOKEVIRTUAL) return false
    // Bundle/Vec assignment uses the ordinary DataPimped conversion, whereas
    // scalar assignment is encoded directly on its BaseType class. The native
    // wrapper only retains its exact Data receiver; admit just its assignment.
    if (call.owner == "spinal/core/package$" && call.name == "DataPimped" &&
        call.desc == "(Lspinal/core/Data;)Lspinal/core/DataPimper;") return true
    if (call.owner == "spinal/core/DataPimper" && call.name == "$colon$eq" &&
        call.desc == "(Lspinal/core/Data;Lspinal/idslplugin/Location;)V") return true
    if (customBundle(call.owner)) {
      auditBundle(call.owner)
      if (accessor(call.owner, call.name, call.desc)) return true
    }
    if (call.owner == "spinal/core/cloneOf$")
      return call.name == "apply" && call.desc == "(Lspinal/core/Data;)Lspinal/core/Data;"
    if (call.owner == "spinal/core/Mux$")
      return call.name == "apply" && call.desc == "(Lspinal/core/Bool;Lspinal/core/Data;Lspinal/core/Data;)Lspinal/core/Data;"
    if (call.owner == "spinal/core/Vec" && call.name == "apply")
      return call.desc == "(I)Lspinal/core/Data;"
    if (scalarNames(call.owner) && Set("$minus", "$times", "$less", "$less$eq", "$greater", "$greater$eq", "$eq$eq$eq", "$eq$div$eq")(call.name))
      return !bridge && args.size == 1 && dataDescriptor(args.head.getDescriptor) && dataDescriptor(result.getDescriptor)
    if (scalarNames(call.owner) && call.name == "resized" && args.isEmpty &&
        dataDescriptor(result.getDescriptor)) return !bridge
    if (dataName(call.owner)) {
      if (call.name == "$colon$eq" && call.desc == "(Lspinal/core/Data;Lspinal/idslplugin/Location;)V") return true
      if (bridge && call.name == "setAsReg" && args.isEmpty && dataDescriptor(result.getDescriptor)) return true
      if (bridge && Set("getZero", "getZeroUnconstrained")(call.name) && args.isEmpty && dataDescriptor(result.getDescriptor)) return true
      if (bridge && call.name == "init" && call.desc == "(Lspinal/core/Data;)Lspinal/core/Data;") return true
    }
    false
  }

  /** Native Bundle.clone uses the actual class constructor, so allow no custom
    * Data hooks and no unchecked constructor bodies or constructor closures.
    * Fields are immutable constructor parameters or native Data declarations.
    */
  private def auditBundle(owner: String): Unit = {
    if (checked(owner) || active(owner)) return
    val node = read(owner)
    if ((node.access & Opcodes.ACC_FINAL) == 0)
      fail("composite callback Bundle classes must be final: " + owner)
    if (node.interfaces.asScala.exists(name =>
        !Set("scala/Product", "scala/Serializable", "java/io/Serializable")(name)))
      fail("composite callback Bundle adds an unchecked construction interface: " + owner)
    def inheritedMethods(name: String, seen: Set[String]): Set[(String, String)] = {
      if (name == null || name == "java/lang/Object" || seen(name)) Set.empty
      else {
        val parent = read(name)
        parent.methods.asScala.filter(m => m.name != "<init>" &&
          (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0).map(m => m.name -> m.desc).toSet ++
          inheritedMethods(parent.superName, seen + name) ++
          parent.interfaces.asScala.flatMap(inheritedMethods(_, seen + name))
      }
    }
    val inherited = inheritedMethods(node.superName, Set.empty)
    node.methods.asScala.find(m => inherited(m.name -> m.desc) && m.name != "toString").foreach { method =>
      fail("composite callback Bundle overrides a native construction hook: " + owner + "." + method.name + method.desc)
    }
    node.methods.asScala.filter(_.name == "toString").foreach { method =>
      simple(method) match {
        case Vector(module: FieldInsnNode, self: VarInsnNode, call: MethodInsnNode, ret: InsnNode)
            if module.getOpcode == Opcodes.GETSTATIC && module.owner == "scala/runtime/ScalaRunTime$" &&
              module.name == "MODULE$" && self.getOpcode == Opcodes.ALOAD && self.`var` == 0 &&
              call.getOpcode == Opcodes.INVOKEVIRTUAL && call.owner == module.owner &&
              call.name == "_toString" && call.desc == "(Lscala/Product;)Ljava/lang/String;" &&
              ret.getOpcode == Opcodes.ARETURN =>
        case _ => fail("composite callback Bundle overrides native string conversion: " + owner)
      }
    }
    if (node.fields.asScala.exists(f => (f.access & Opcodes.ACC_STATIC) == 0 &&
        ((f.access & Opcodes.ACC_FINAL) == 0 || f.name == "$outer")))
      fail("composite callback Bundle contains mutable or enclosing host state: " + owner)
    active += owner
    node.methods.asScala.filter(_.name == "<init>").foreach { method =>
      // Data.clone reconstructs a case class using its leading declared fields
      // and public same-name getters, not simply its bytecode constructor args.
      val arguments = Type.getArgumentTypes(method.desc).toVector
      // A final field does not make the object stored in it immutable. In
      // particular Function0/HardType could run uninspected host code when a
      // native Vec factory consumes it, and Data parameters introduce a second
      // reflective clone path outside the owned element tree. Only immutable
      // shape values may cross this constructor boundary.
      val shapeDescriptors = Set("I", "Lspinal/core/ElabInt;", "Lmorphhdl/frontend/HdlInt;")
      if (arguments.exists(argument => !shapeDescriptors(argument.getDescriptor)))
        fail("composite clone constructor parameters must be immutable shape values: " + owner)
      val fields = node.fields.asScala.filter(f => (f.access & Opcodes.ACC_STATIC) == 0).toVector
      if (arguments.size > fields.size || arguments.zip(fields).exists { case (argument, field) =>
          argument.getDescriptor != field.desc || !accessor(owner, field.name, "()" + field.desc, allowInteger = true)
        }) fail("composite clone constructor parameters lack exact immutable field getters: " + owner)
      auditConstruction(owner, method, Set.empty)
    }
    active -= owner
    checked += owner
  }

  private def auditConstruction(owner: String, method: MethodNode, stack: Set[(String, String, String)]): Unit = {
    val key = (owner, method.name, method.desc)
    if (stack(key) || stack.size > 16) fail("recursive composite constructor helper")
    if (!method.tryCatchBlocks.isEmpty ||
        (method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNCHRONIZED)) != 0)
      fail("composite constructor has unsupported exception or synchronization behavior")
    method.instructions.toArray.foreach {
      case _: LabelNode | _: FrameNode | _: LineNumberNode =>
      case insn: VarInsnNode if Set(Opcodes.ALOAD, Opcodes.ASTORE, Opcodes.ILOAD, Opcodes.ISTORE)(insn.getOpcode) =>
      case insn: InsnNode if Set(Opcodes.NOP, Opcodes.DUP, Opcodes.POP, Opcodes.RETURN,
          Opcodes.ARETURN, Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0,
          Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5)(insn.getOpcode) =>
      case insn: IntInsnNode if Set(Opcodes.BIPUSH, Opcodes.SIPUSH)(insn.getOpcode) =>
      case insn: LdcInsnNode if insn.cst.isInstanceOf[String] || insn.cst.isInstanceOf[java.lang.Integer] =>
      case insn: TypeInsnNode if insn.getOpcode == Opcodes.CHECKCAST &&
          (dataName(insn.desc) || immutableNames(insn.desc)) =>
      case insn: TypeInsnNode if insn.getOpcode == Opcodes.NEW &&
          (Set("sourcecode/File", "sourcecode/Line", "spinal/idslplugin/Location")(insn.desc) || customBundle(insn.desc)) =>
        if (customBundle(insn.desc)) {
          if (active(insn.desc)) fail("recursive composite constructor dependency: " + insn.desc)
          auditBundle(insn.desc)
        }
      case field: FieldInsnNode if field.getOpcode == Opcodes.PUTFIELD && field.owner == owner &&
          method.name == "<init>" && read(owner).fields.asScala.exists(f =>
            f.name == field.name && f.desc == field.desc && (f.access & Opcodes.ACC_FINAL) != 0) =>
      case field: FieldInsnNode if field.getOpcode == Opcodes.GETFIELD && field.owner == owner &&
          read(owner).fields.asScala.exists(f => f.name == field.name && f.desc == field.desc &&
            (f.access & Opcodes.ACC_FINAL) != 0) =>
      case field: FieldInsnNode if constructorModule(field) =>
      case call: MethodInsnNode if constructorCall(owner, call, stack + key) =>
      case call: InvokeDynamicInsnNode if call.bsm.getOwner == "java/lang/invoke/LambdaMetafactory" &&
          Set("metafactory", "altMetafactory")(call.bsm.getName) &&
          Type.getReturnType(call.desc).getDescriptor == "Lscala/Function0;" =>
        val body = call.bsmArgs.collect { case h: Handle if h.getTag == Opcodes.H_INVOKESTATIC &&
          h.getOwner == owner => h }.toVector
        if (body.size != 1) fail("composite constructor closure lacks an exact local static body")
        auditConstruction(owner, exact(owner, body.head.getName, body.head.getDesc), stack + key)
      case insn => fail("unsupported composite constructor instruction in " + owner + "." + method.name +
        ": " + insn.getClass.getSimpleName + " opcode=" + insn.getOpcode + (insn match {
          case call: MethodInsnNode => " " + call.owner + "." + call.name + call.desc
          case field: FieldInsnNode => " " + field.owner + "." + field.name
          case _ => ""
        }))
    }
  }

  private val constructorModules = modules ++ Set("morphhdl/frontend/HdlInt$",
    "morphhdl/frontend/HdlInt$HdlIntBitCountOps$", "spinal/core/ElabInt$",
    "spinal/core/Vec$", "spinal/core/IntBuilder$", "spinal/core/package$IntBuilder$",
    "scala/Predef$$eq$colon$eq$", "scala/$eq$colon$eq$",
    "sourcecode/File$", "sourcecode/Line$", "scala/runtime/BoxedUnit")

  private def constructorModule(field: FieldInsnNode): Boolean = {
    if (field.getOpcode != Opcodes.GETSTATIC) return false
    if (field.owner == "scala/runtime/BoxedUnit")
      return field.name == "UNIT" && field.desc == "Lscala/runtime/BoxedUnit;"
    if (field.name != "MODULE$" || field.desc != "L" + field.owner + ";") return false
    if (constructorModules(field.owner)) return true
    if (field.owner.endsWith("$") && customBundle(field.owner.dropRight(1))) {
      auditCompanion(field.owner)
      true
    } else false
  }

  private def auditCompanion(owner: String): Unit = {
    if (checkedCompanions(owner)) return
    val node = read(owner)
    if (node.fields.asScala.exists(f => f.name != "MODULE$" || f.desc != "L" + owner + ";" ||
        (f.access & Opcodes.ACC_STATIC) == 0))
      fail("composite constructor companion contains host state: " + owner)
    if (node.superName != "java/lang/Object" && !node.superName.matches("scala/runtime/AbstractFunction[0-9]+"))
      fail("composite constructor companion has a custom superclass: " + owner)
    Vector("<init>", "<clinit>").foreach { name =>
      val method = exact(owner, name, "()V")
      if (!method.tryCatchBlocks.isEmpty) fail("composite companion initializer has an exception handler")
      simple(method).foreach {
        case insn: VarInsnNode if name == "<init>" && insn.getOpcode == Opcodes.ALOAD && insn.`var` == 0 =>
        case insn: TypeInsnNode if name == "<clinit>" && insn.getOpcode == Opcodes.NEW && insn.desc == owner =>
        case insn: InsnNode if Set(Opcodes.RETURN, Opcodes.DUP, Opcodes.POP)(insn.getOpcode) =>
        case field: FieldInsnNode if name == "<init>" && field.getOpcode == Opcodes.PUTSTATIC &&
            field.owner == owner && field.name == "MODULE$" && field.desc == "L" + owner + ";" =>
        case call: MethodInsnNode if call.getOpcode == Opcodes.INVOKESPECIAL && call.name == "<init>" &&
            call.desc == "()V" && call.owner == (if (name == "<init>") node.superName else owner) =>
        case _ => fail("composite constructor companion initializer has host effects: " + owner)
      }
    }
    checkedCompanions += owner
  }

  private def constructorCall(owner: String, call: MethodInsnNode, stack: Set[(String, String, String)]): Boolean = {
    if (call.getOpcode == Opcodes.INVOKESPECIAL && call.name == "<init>") {
      if (Set("spinal/core/Bundle", "spinal/core/BundleCase")(call.owner) && call.desc == "()V") return true
      if (Set("sourcecode/File", "sourcecode/Line")(call.owner) &&
          Set("(Ljava/lang/String;)V", "(I)V")(call.desc)) return true
      if (customBundle(call.owner)) {
        if (active(call.owner)) fail("recursive composite constructor dependency: " + call.owner)
        auditBundle(call.owner); return true
      }
    }
    if (call.getOpcode == Opcodes.INVOKESTATIC && call.owner == "scala/Product" &&
        call.name == "$init$" && call.desc == "(Lscala/Product;)V") return true
    if (call.getOpcode == Opcodes.INVOKESTATIC && call.owner == owner) {
      auditConstruction(owner, exact(owner, call.name, call.desc), stack); return true
    }
    if (call.getOpcode != Opcodes.INVOKEVIRTUAL) return false
    if (customBundle(call.owner)) {
      if (call.name == "valCallback" && call.desc == "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;") return true
      if (accessor(call.owner, call.name, call.desc, allowInteger = true)) return true
    }
    if (call.owner.endsWith("$") && customBundle(call.owner.dropRight(1)) && call.name == "apply") {
      auditCompanion(call.owner)
      auditBundle(call.owner.dropRight(1))
      auditConstruction(call.owner, exact(call.owner, call.name, call.desc), stack)
      return true
    }
    if (call.owner == "spinal/core/package$") {
      if (scalarNames("spinal/core/" + call.name)) {
        val args = Type.getArgumentTypes(call.desc).map(_.getDescriptor).toVector
        return args.size == 1 && Set("Lspinal/core/ParameterizedBitCount;", "Lspinal/core/BitCount;",
          "Lscala/runtime/BoxedUnit;")(args.head) &&
          Type.getReturnType(call.desc).getDescriptor == "Lspinal/core/" + call.name + ";"
      }
      if (Set("Bool$default$1", "UInt$default$1", "SInt$default$1", "Bits$default$1")(call.name) && call.desc == "()V") return true
      if (call.name == "Vec" && Set("(Lscala/Function0;I)Lspinal/core/Vec;",
          "(Lscala/Function0;Lspinal/core/ElabInt;)Lspinal/core/Vec;")(call.desc)) return true
      if (call.name == "IntToBuilder" && call.desc == "(I)I") return true
    }
    if (call.owner == "morphhdl/frontend/HdlInt$" && call.name == "HdlIntBitCountOps" &&
        call.desc == "(Lmorphhdl/frontend/HdlInt;)Lmorphhdl/frontend/HdlInt;") return true
    if (call.owner == "morphhdl/frontend/HdlInt$" && call.name == "hdlIntToElabInt" &&
        Set("(Lmorphhdl/frontend/HdlInt;Lscala/Predef$$eq$colon$eq;)Ljava/lang/Object;",
          "(Lmorphhdl/frontend/HdlInt;Lscala/$eq$colon$eq;)Ljava/lang/Object;")(call.desc)) return true
    if (call.owner == "scala/Predef$$eq$colon$eq$" && call.name == "tpEquals" &&
        call.desc == "()Lscala/Predef$$eq$colon$eq;") return true
    if (call.owner == "scala/$eq$colon$eq$" && call.name == "refl" &&
        call.desc == "()Lscala/$eq$colon$eq;") return true
    if (call.owner == "morphhdl/frontend/HdlInt$HdlIntBitCountOps$" && call.name == "bits$extension" &&
        call.desc == "(Lmorphhdl/frontend/HdlInt;Lsourcecode/File;Lsourcecode/Line;)Lspinal/core/ParameterizedBitCount;") return true
    if (call.owner == "spinal/core/ElabInt" && call.name == "bits" && call.desc == "()Lspinal/core/ParameterizedBitCount;") return true
    if (Set("spinal/core/IntBuilder$", "spinal/core/package$IntBuilder$")(call.owner) &&
        call.name == "bits$extension" && call.desc == "(I)Lspinal/core/BitCount;") return true
    if (Set("sourcecode/File$", "sourcecode/Line$")(call.owner) && call.name == "apply" &&
        Set("(Ljava/lang/String;)Ljava/lang/String;", "(I)I")(call.desc)) return true
    false
  }
}
