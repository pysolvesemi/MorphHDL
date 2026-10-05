/*                                                                           *\
**        _____ ____  _____   _____    __                                    **
**       / ___// __ \/  _/ | / /   |  / /   HDL Core                         **
**       \__ \/ /_/ // //  |/ / /| | / /    (c) Dolu, All rights reserved    **
**      ___/ / ____// // /|  / ___ |/ /___                                   **
**     /____/_/   /___/_/ |_/_/  |_/_____/                                   **
**                                                                           **
**      This library is free software; you can redistribute it and/or        **
**    modify it under the terms of the GNU Lesser General Public             **
**    License as published by the Free Software Foundation; either           **
**    version 3.0 of the License, or (at your option) any later version.     **
**                                                                           **
**      This library is distributed in the hope that it will be useful,      **
**    but WITHOUT ANY WARRANTY; without even the implied warranty of         **
**    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU      **
**    Lesser General Public License for more details.                        **
**                                                                           **
**      You should have received a copy of the GNU Lesser General Public     **
**    License along with this library.                                       **
\*                                                                           */
package spinal.core.internals

import spinal.core._

trait VerilogTheme{
  def tab      = "  "
  def porttab  = ""
  def maintab  = ""
}

class Tab2 extends VerilogTheme {
  override def tab      = "  "
  override def porttab  = "  "
  override def maintab  = "  "
}

class Tab4 extends VerilogTheme {
  override def tab      = "    "
  override def porttab  = ""
  override def maintab  = ""
}


/** The native printer owns declaration occurrences. No occurrence contains an
  * emitted identifier, source location or a guessed concrete-width type.
  */
object VerilogBase {
  sealed trait DeclarationRole
  case object ScalarDeclaration extends DeclarationRole
  case object FunctionResultDeclaration extends DeclarationRole
  case object ExpressionWrapper extends DeclarationRole
  case object MemoryElementDeclaration extends DeclarationRole

  final class DeclarationOccurrence private[VerilogBase] (
      val emitter: VerilogBase,
      val subject: AnyRef,
      val role: DeclarationRole,
      val ansiPort: Boolean = false
  )

  /** Native cast site and the exact object that emitExpression will print.
    * The reference classification comes from the real native wrapper plan,
    * never from the resulting Verilog text or a caller-provided signed flag.
    */
  final class SignedCastOccurrence private[VerilogBase] (
      val emitter: VerilogBase,
      val printer: ComponentEmitterVerilog,
      val parent: Expression,
      val slot: Int,
      val operand: Expression
  ) {
    def component: Component = printer.component
    def isSignedLiteral: Boolean = operand match {
      case literal: BitVectorLiteral => emitter.literalIsSigned(literal)
      case _ => false
    }
    def referenceRole: Option[DeclarationRole] = {
      if (printer.wrappedExpressionToName.contains(operand)) Some(ExpressionWrapper)
      else operand match {
        case value: BaseType if (value.component eq component) && !value.isSuffix &&
            !printer.referencesOverrides.contains(value) => Some(ScalarDeclaration)
        case _ => None
      }
    }
  }

  final class SignedLiteralOccurrence private[VerilogBase] (
      val emitter: VerilogBase,
      val literal: BitVectorLiteral
  )

  final class SignedResizeOccurrence private[VerilogBase] (
      val emitter: VerilogBase,
      val printer: ComponentEmitterVerilog,
      val resize: Resize
  ) {
    def inputReferenceRole: Option[DeclarationRole] =
      new SignedCastOccurrence(emitter, printer, resize, 0, resize.input).referenceRole
    def inputText: String = printer.emitExpression(resize.input)
  }

  /** Exact native assignment callback for authenticated parameter publication.
    * Concrete emission has no bound policy and keeps its historical path.
    */
  trait AssignmentPublicationPolicy {
    def constantShift(printer: ComponentEmitterVerilog,
        expression: Operator.BitVector.ShiftRightByInt): Option[String] = None
    def blackBoxGeneric(printer: ComponentEmitterVerilog, blackBox: BlackBox,
        association: (String, Any)): Option[String] = None
    def memoryGeometry(printer: ComponentEmitterVerilog, memory: Mem[_]): Option[(String, String)] = None
    def condition(printer: ComponentEmitterVerilog, statement: WhenStatement): Option[String] = None
    def wrapperRange(printer: ComponentEmitterVerilog, expression: Expression): Option[String] = None
    def binaryOperand(printer: ComponentEmitterVerilog, expression: BinaryOperator, slot: Int): Option[String] = None
    def source(printer: ComponentEmitterVerilog, assignment: AssignmentStatement): Option[String]
    def target(printer: ComponentEmitterVerilog, assignment: AssignmentStatement): Option[String] = None
    def scope(printer: ComponentEmitterVerilog, tree: TreeStatement, scope: ScopeStatement,
        output: StringBuilder, indentation: String, body: String => Int): Option[Int] = None
  }

  /** Optional storage owned by the concrete emitter, not by the historical
    * VerilogBase interface. A field in that interface would add abstract JVM
    * accessors and break already-compiled implementations. The strong reference
    * lives exactly as long as its owner; no global or thread-local registry is
    * involved, even when a policy itself retains the emitter.
    */
  private[spinal] trait DeclarationPolicyOwner { self: VerilogBase =>
    @volatile private var boundPolicy: DeclarationPolicy = null
    @volatile private var assignmentPolicy: AssignmentPublicationPolicy = null
    private[VerilogBase] final def assignmentPublication: AssignmentPublicationPolicy = assignmentPolicy
    private[VerilogBase] final def installAssignmentPublication(policy: AssignmentPublicationPolicy): Unit = synchronized {
      require(policy != null && assignmentPolicy == null, "assignment publication must be bound exactly once")
      assignmentPolicy = policy
    }

    private[VerilogBase] final def currentDeclarationPolicy: DeclarationPolicy = boundPolicy

    private[VerilogBase] final def installDeclarationPolicy(policy: DeclarationPolicy): Unit = synchronized {
      require(policy != null && boundPolicy == null,
        "a Verilog declaration policy must be non-null and bound exactly once")
      boundPolicy = policy
    }
  }

  trait DeclarationPolicy {
    def signed(occurrence: DeclarationOccurrence): Boolean
    def wrapperRange(occurrence: DeclarationOccurrence): Option[String]
    def unsignedTransport(expression: Expression): Boolean
    def elideSignedCast(occurrence: SignedCastOccurrence): Boolean = false
    def signedLiteral(occurrence: SignedLiteralOccurrence): Boolean = false
    def signedResize(occurrence: SignedResizeOccurrence): Option[String] = None
    def functionRange(occurrence: DeclarationOccurrence): Option[String] = None
    def scalarRange(occurrence: DeclarationOccurrence): Option[String] = None
  }
}

trait VerilogBase extends VhdlVerilogBase{
  import VerilogBase._

  // Concrete default accessors keep old VerilogBase implementations stateless
  // and unsigned. Only an explicitly policy-capable emitter owns a policy slot.
  private def declarationPolicy: DeclarationPolicy = this match {
    case owner: DeclarationPolicyOwner => owner.currentDeclarationPolicy
    case _ => null
  }

  private def declarationPolicy_=(policy: DeclarationPolicy): Unit = this match {
    case owner: DeclarationPolicyOwner => owner.installDeclarationPolicy(policy)
    case _ => throw new IllegalArgumentException(
      "binding a Verilog declaration policy requires DeclarationPolicyOwner")
  }

  /** One generation-local opt-in; an ordinary native emitter has no policy. */
  private[spinal] final def bindDeclarationPolicy(policy: DeclarationPolicy): Unit = {
    require(policy != null && declarationPolicy == null,
      "a Verilog declaration policy must be non-null and bound exactly once")
    declarationPolicy = policy
  }

  private[spinal] final def bindAssignmentPublication(policy: AssignmentPublicationPolicy): Unit = this match {
    case owner: DeclarationPolicyOwner => owner.installAssignmentPublication(policy)
    case _ => throw new IllegalArgumentException("assignment publication requires a policy-capable emitter")
  }

  private[spinal] final def emitNativeConstantShift(printer: ComponentEmitterVerilog,
      expression: Operator.BitVector.ShiftRightByInt): Option[String] = {
    require(printer.usesVerilogBase(this), "constant shift must use its exact native emitter")
    this match {
      case owner: DeclarationPolicyOwner => Option(owner.assignmentPublication)
        .flatMap(_.constantShift(printer, expression))
      case _ => None
    }
  }
  private[spinal] final def emitNativeBinaryOperand(printer: ComponentEmitterVerilog,
      expression: BinaryOperator, slot: Int): Option[String] = this match {
    case owner: DeclarationPolicyOwner =>
      Option(owner.assignmentPublication).flatMap(_.binaryOperand(printer, expression, slot))
    case _ => None
  }
  private[spinal] final def emitNativeBlackBoxGeneric(printer: ComponentEmitterVerilog,
      blackBox: BlackBox, association: (String, Any)): Option[String] = {
    require(printer.usesVerilogBase(this) && (blackBox.parent eq printer.component) &&
      blackBox.genericElements.exists(_ eq association), "generic must be its exact native instance association")
    this match {
      case owner: DeclarationPolicyOwner => Option(owner.assignmentPublication)
        .flatMap(_.blackBoxGeneric(printer, blackBox, association))
      case _ => None
    }
  }
  private[spinal] final def emitNativeWhenCondition(printer: ComponentEmitterVerilog,
      statement: WhenStatement): Option[String] = {
    require(printer.usesVerilogBase(this), "condition must belong to this native emitter")
    this match {
      case owner: DeclarationPolicyOwner =>
        Option(owner.assignmentPublication).flatMap(_.condition(printer, statement))
      case _ => None
    }
  }
  private[spinal] final def emitAssignmentSource(printer: ComponentEmitterVerilog,
      assignment: AssignmentStatement): Option[String] = {
    require(printer.usesVerilogBase(this), "assignment must belong to this native emitter")
    this match {
      case owner: DeclarationPolicyOwner if owner.assignmentPublication != null =>
        owner.assignmentPublication.source(printer, assignment)
      case _ => None
    }
  }

  private[spinal] final def emitAssignmentTarget(printer: ComponentEmitterVerilog,
      assignment: AssignmentStatement): Option[String] = {
    require(printer.usesVerilogBase(this), "assignment must belong to this native emitter")
    this match {
      case owner: DeclarationPolicyOwner if owner.assignmentPublication != null =>
        owner.assignmentPublication.target(printer, assignment)
      case _ => None
    }
  }
  private[spinal] final def emitPublicationScope(printer: ComponentEmitterVerilog,
      tree: TreeStatement, scope: ScopeStatement, output: StringBuilder,
      indentation: String, body: String => Int): Option[Int] = {
    require(printer.usesVerilogBase(this), "scope must belong to this native emitter")
    this match {
      case owner: DeclarationPolicyOwner if owner.assignmentPublication != null =>
        owner.assignmentPublication.scope(printer, tree, scope, output, indentation, body)
      case _ => None
    }
  }

  private[spinal] final def hasDeclarationPolicy: Boolean = declarationPolicy != null

  private[spinal] final def needsUnsignedTransport(expression: Expression): Boolean =
    declarationPolicy != null && declarationPolicy.unsignedTransport(expression)

  private[spinal] final def canElideSignedCast(printer: ComponentEmitterVerilog,
      parent: Expression, slot: Int, operand: Expression): Boolean = {
    require(printer != null && printer.usesVerilogBase(this),
      "a signed cast occurrence must belong to this native emitter")
    declarationPolicy != null && declarationPolicy.elideSignedCast(
      new SignedCastOccurrence(this, printer, parent, slot, operand))
  }

  private[spinal] final def literalIsSigned(literal: BitVectorLiteral): Boolean =
    declarationPolicy != null && declarationPolicy.signedLiteral(new SignedLiteralOccurrence(this, literal))

  private[spinal] final def emitSignedResize(printer: ComponentEmitterVerilog,
      resize: Resize): Option[String] = {
    require(printer != null && printer.usesVerilogBase(this),
      "a signed resize occurrence must belong to this native emitter")
    if (declarationPolicy == null) None
    else declarationPolicy.signedResize(new SignedResizeOccurrence(this, printer, resize))
  }

  private def declarationPrefix(subject: AnyRef, role: DeclarationRole): String =
    if (declarationPolicy != null && declarationPolicy.signed(
        new DeclarationOccurrence(this, subject, role))) "signed " else ""

  private def emitWrapperType(e: Expression): String = {
    if (declarationPolicy == null) return emitType(e)
    val nativeType = emitUnqualifiedType(e)
    val section = {
      val occurrence = new DeclarationOccurrence(this, e, ExpressionWrapper)
      declarationPolicy.wrapperRange(occurrence).getOrElse(nativeType)
    }
    declarationPrefix(e, ExpressionWrapper) + section
  }

  var globalPrefix = ""

  val theme = new Tab2 //TODO add into SpinalConfig
  def expressionAlign(net: String, section: String, name: String) = {
    f"$net%-10s $section%-8s $name"
  }

  def emitExpressionWrap(e: Expression, name: String): String = {
//    s"  wire ${emitType(e)} ${name};\n"
    if (!e.isInstanceOf[SpinalStruct]) {
      val isReg = e.isInstanceOf[Multiplexer]
      theme.maintab + expressionAlign(if(isReg) "reg" else "wire", emitWrapperType(e), name) + ";\n"
    } else
      theme.maintab + expressionAlign(e.asInstanceOf[SpinalStruct].getTypeString, "", name) + ";\n"
  }

  /** Exact owning printer for late native expression wrappers. */
  def emitExpressionWrap(e: Expression, name: String, printer: ComponentEmitterVerilog): String = {
    val signedRange = if (declarationPolicy == null) None else declarationPolicy.wrapperRange(
      new DeclarationOccurrence(this, e, ExpressionWrapper))
    if (signedRange.nonEmpty) return emitExpressionWrap(e, name)
    val range = emitOwnedRange(printer, e)
    range match {
      case Some(value) => theme.maintab + expressionAlign(if(e.isInstanceOf[Multiplexer]) "reg" else "wire",
        declarationPrefix(e, ExpressionWrapper) + value, name) + ";\n"
      case None => emitExpressionWrap(e, name)
    }
  }

  def emitExpressionWrap(e: Expression, name: String, nature: String, printer: ComponentEmitterVerilog): String =
    emitOwnedRange(printer, e).map(range => theme.maintab +
      expressionAlign(nature, declarationPrefix(e, ExpressionWrapper) + range, name) + ";\n")
      .getOrElse(emitExpressionWrap(e, name, nature))

  private[spinal] def emitMemoryGeometry(printer: ComponentEmitterVerilog, memory: Mem[_]): Option[(String, String)] = {
    require(printer.usesVerilogBase(this), "memory must belong to this native emitter")
    this match {
      case owner: DeclarationPolicyOwner => Option(owner.assignmentPublication).flatMap(_.memoryGeometry(printer, memory))
      case _ => None
    }
  }

  private[spinal] def emitOwnedRange(printer: ComponentEmitterVerilog, e: Expression): Option[String] = {
    require(printer.usesVerilogBase(this), "range must belong to this native emitter")
    this match {
      case owner: DeclarationPolicyOwner => Option(owner.assignmentPublication).flatMap(_.wrapperRange(printer, e))
      case _ => None
    }
  }

  def emitType(e: Expression, printer: ComponentEmitterVerilog): String =
    emitOwnedRange(printer, e).map(declarationPrefix(e, ScalarDeclaration) + _).getOrElse(emitType(e))

  def emitExpressionWrap(e: Expression, name: String, nature: String): String = {
//    s"  $nature ${emitType(e)} ${name};\n"
    theme.maintab + expressionAlign(nature, emitWrapperType(e), name) + ";\n"
  }

  def emitClockEdge(clock: String, edgeKind: EdgeKind): String = {
    s"${
      edgeKind match {
        case RISING  => "posedge"
        case FALLING => "negedge"
      }
    } ${clock}"
  }

  def emitResetEdge(reset: String, polarity: Polarity): String = {
    s"${
      polarity match {
        case HIGH => "posedge"
        case LOW  => "negedge"
      }
    } ${reset}"
  }
  def emitQuotedString(string: String): String = {
    "\"" + string.replace("\"", "\\\"") + "\""
  }
  def emitSyntaxAttributes(attributes: Iterable[Attribute]): String = {
    val values = for (attribute <- attributes if attribute.attributeKind() == DEFAULT_ATTRIBUTE) yield attribute match {
      case attribute: AttributeString => attribute.getName + " = " + emitQuotedString(attribute.value)
      case attribute: AttributeInteger => attribute.getName + " = " + attribute.value.toString
      case attribute: AttributeFlag => attribute.getName
    }

    if(values.isEmpty) return ""

    "(* " + values.reduce(_ + " , " + _) + " *) "
  }

  def emitCommentAttributes(attributes: Iterable[Attribute]): String = {
    val values = for (attribute <- attributes if attribute.attributeKind() == COMMENT_ATTRIBUTE) yield attribute match {
      case attribute: AttributeString => attribute.getName + " = " + emitQuotedString(attribute.value)
      case attribute: AttributeInteger => attribute.getName + " = " + attribute.value.toString
      case attribute: AttributeFlag => attribute.getName
    }

    if(values.isEmpty) return ""

    " /* " + values.reduce(_ + " , " + _) + " */ "
  }

  def emitCommentEarlyAttributes(attributes: Iterable[Attribute]): String = {
    val values = for (attribute <- attributes if attribute.attributeKind() == COMMENT_TYPE_ATTRIBUTE) yield attribute match {
      case attribute: AttributeString => attribute.getName + " = " + emitQuotedString(attribute.value)
      case attribute: AttributeInteger => attribute.getName + " = " + attribute.value.toString
      case attribute: AttributeFlag => attribute.getName
    }

    if(values.isEmpty) return ""

    " /* " + values.reduce(_ + " , " + _) + " */ "
  }

  def emitEnumLiteral[T <: SpinalEnum](senum: SpinalEnumElement[T], encoding: SpinalEnumEncoding, prefix: String = "`"): String = {
//    prefix + senum.spinalEnum.getName() + "_" + encoding.getName() + "_" + senum.getName()
    var prefix_fix = prefix
    if(prefix=="`" && !senum.spinalEnum.isGlobalEnable) prefix_fix = ""

    val withEncoding = senum.spinalEnum.defaultEncoding != encoding && (senum.spinalEnum.defaultEncoding == native && encoding != binarySequential)
    if(senum.spinalEnum.isGlobalEnable) {
      prefix_fix + globalPrefix + senum.spinalEnum.getName() + (if(withEncoding) "_" + encoding.getName() else "") + "_" + senum.getName()
    } else {
      prefix_fix + senum.spinalEnum.getName() + (if(withEncoding) "_" + encoding.getName() else "") + "_" + senum.getName()
    }
  }

  def emitEnumType[T <: SpinalEnum](senum: SpinalEnumCraft[T], prefix: String): String = emitEnumType(senum.spinalEnum, senum.getEncoding, prefix)

  def emitEnumType(senum: SpinalEnum, encoding: SpinalEnumEncoding, prefix: String = "`"): String = {
//    prefix + senum.getName() + "_" + encoding.getName() + "_type"
    val bitCount     = encoding.getWidth(senum)
    s"[${bitCount - 1}:0]"
  }

  def getReEncodingFuntion(spinalEnum: SpinalEnum, source: SpinalEnumEncoding, target: SpinalEnumEncoding): String = {
    s"${globalPrefix}${spinalEnum.getName()}_${source.getName()}_to_${target.getName()}"
  }

  def emitStructType(struct: SpinalStruct): String = {
    return struct.getTypeString
  }

  def emitType(e: Expression): String = {
    val range = if (declarationPolicy == null) None else declarationPolicy.scalarRange(
      new DeclarationOccurrence(this, e, ScalarDeclaration))
    declarationPrefix(e, ScalarDeclaration) + range.getOrElse(emitUnqualifiedType(e))
  }

  /** Called at the ANSI declaration site, never for a child-port body wrapper. */
  def emitPortType(e: Expression): String = {
    if (declarationPolicy == null) return emitType(e)
    val range = declarationPolicy.scalarRange(
      new DeclarationOccurrence(this, e, ScalarDeclaration, ansiPort = true))
    declarationPrefix(e, ScalarDeclaration) + range.getOrElse(emitUnqualifiedType(e))
  }

  def emitFunctionType(e: BaseType): String = {
    val prefix = declarationPrefix(e, FunctionResultDeclaration)
    val range = if (declarationPolicy == null) None else declarationPolicy.functionRange(
      new DeclarationOccurrence(this, e, FunctionResultDeclaration))
    prefix + range.getOrElse(emitUnqualifiedType(e))
  }

  private def emitUnqualifiedType(e: Expression): String = e.getTypeObject match {
    case `TypeBool` => ""
    case `TypeBits` => emitRange(e.asInstanceOf[WidthProvider])
    case `TypeUInt` => emitRange(e.asInstanceOf[WidthProvider])
    case `TypeSInt` => emitRange(e.asInstanceOf[WidthProvider])
    case `TypeEnum` => e match {
      case e : EnumEncoded => emitEnumType(e.getDefinition, e.getEncoding)
    }
    case `TypeStruct` => emitStructType(e.asInstanceOf[SpinalStruct])
  }

  def emitDirection(baseType: BaseType) = baseType.dir match {
    case `in`    => "input "
    case `out`   => "output"
    case `inout` => "inout "
    case _       => throw new Exception("Unknown direction"); ""
  }

  def emitRange(node: WidthProvider) = {
    val prefix = node match {
      case memory: Mem[_] => declarationPrefix(memory, MemoryElementDeclaration)
      case _ => ""
    }
    prefix + s"[${node.getWidth - 1}:0]"
  }

  def signalNeedProcess(baseType: BaseType): Boolean = {
    if(baseType.isReg) return true
    if(baseType.dlcIsEmpty || baseType.isAnalog) return false
    if(!baseType.hasOnlyOneStatement || baseType.head.parentScope != baseType.rootScopeStatement) return true
    return false
  }

}
