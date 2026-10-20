package spinal.core

import spinal.core.internals._
import scala.collection.mutable.ArrayBuffer
import java.util.IdentityHashMap

/** Exact declaration tokens for hardware-typed parameter/localparam values.
  * Capturing a constant never grants authority to a runtime signal or witness.
  */
object NativeParameterConstants {
  private object Key
  private final class ValueSource(val expression:ElaborationIntegerExpression, val hint:String,
      val width:Option[ElaborationIntegerExpression], val defaultWidth:Int, val signed:Boolean) {
    var name:String=null
    var sourceWidth=32
    var declared=false
    def render:String = {
      val raw=NativeParameterValueSizing.reference(name,sourceWidth,width,defaultWidth,signed,expression.minimum)
      if(signed)s"$$signed($raw)" else raw
    }
  }
  private final class Operation(val width: ElaborationIntegerExpression, val signed: Boolean,
      val rhs: () => String, val hint: String) {
    var name: String = null
    def declaration: String = s"  localparam ${if(signed) "signed " else ""}[(${width.verilog})-1:0] $name = ${rhs()};\n"
  }
  private case class State(entries: ArrayBuffer[Declaration], adapters: ArrayBuffer[BitVector], sources:ArrayBuffer[ValueSource], operations:ArrayBuffer[Operation])
  private def state(owner: Component): State = owner.userCache.getOrElseUpdate(Key,
    State(ArrayBuffer.empty, ArrayBuffer.empty, ArrayBuffer.empty, ArrayBuffer.empty)).asInstanceOf[State]
  private def entries(owner: Component): Vector[Declaration] =
    Option(owner).flatMap(_.userCache.get(Key)).toVector.flatMap(_.asInstanceOf[State].entries)
  private def fail(detail: String): Nothing = ParameterizedVerilogException.fail(
    "SPINAL-PARAM-CONSTANT-INVALID", detail, None)
  private def ownerCheck(owner: Component): Unit =
    if (owner == null || (Component.current ne owner) || (DslScopeStack.get ne owner.dslBody) ||
        ElaborationDomainContext.hasActiveRestrictions) fail("declare constants at their Component's unconditional module scope")

  private[core] final class Declaration(val owner: Component, val name: String,
      val prototype: BaseType, val width: Option[ElaborationIntegerExpression],
      val dependencies: Vector[ElaborationIntegerExpression], val rhs: () => String,
      val formal: Boolean, val semantic: Option[Either[BigInt,ElabInt]],
      val localSource: Option[ElaborationIntegerExpression] = None,
      val helpers: Vector[() => String] = Vector.empty) {
    val bitWidth: Int = prototype.getBitsWidth
    var prepared = false
    def check(component: Component): Unit = if (component ne owner) fail(s"constant '$name' used in a foreign Component")
  }
  private[spinal] final class Reference private[core] (private[core] val declaration: Declaration)
      extends Expression with WidthProvider {
    override def opName = "authenticated parameter constant"
    override def getTypeObject: Any = declaration.prototype.getTypeObject
    override def getWidth: Int = declaration.bitWidth
    override def foreachExpression(f: Expression => Unit): Unit = ()
    override def remapExpressions(f: Expression => Expression): Unit = ()
    def fresh: Reference = new Reference(declaration)
    def widthExpression: Option[ElaborationIntegerExpression] = declaration.width
    def render(owner: Component): String = {
      declaration.check(owner)
      if (!declaration.prepared) fail("constant declaration was not prepared")
      declaration.localSource match {
        case None=>declaration.name
        case Some(source)=>
          val name=NativeLocalParameters.reference(owner,source).getOrElse(fail("local value lost its declaration"))
          val sourceWidth=NativeLocalParameters.referenceWidth(owner,source).get
          val raw=NativeParameterValueSizing.reference(name,sourceWidth,declaration.width,
            declaration.bitWidth,getTypeObject==TypeSInt,source.minimum)
          s"$$${if(getTypeObject==TypeSInt) "signed" else "unsigned"}($raw)"
      }
    }
  }
  private[core] def validateSwitch(statement: SwitchStatement): Unit = {
    val keys=statement.elements.zipWithIndex.flatMap {case(e,i)=>e.keys.map(_ -> i)}
    if(!keys.exists(_._1.isInstanceOf[Reference])) return
    def semantic(e:Expression):Either[BigInt,ElabInt]=e match {
      case r:Reference=>r.declaration.check(statement.parentScope.component)
        r.declaration.semantic.getOrElse(fail("unsupported constant expression in switch key"))
      case l:Literal=>Left(l.getValue())
      case _=>fail("constant switch keys may mix only with authenticated constants and literals")
    }
    def disjoint(a:Either[BigInt,ElabInt],b:Either[BigInt,ElabInt]):Boolean=(a,b) match {
      case (Left(x),Left(y))=>x!=y
      case (Right(x),Right(y))=>x.elabEq(y).isAlwaysFalse
      case (Right(x),Left(y))=> !y.isValidInt || x.elabEq(ElabInt.fromBigInt(y)).isAlwaysFalse
      case (Left(x),Right(y))=> !x.isValidInt || y.elabEq(ElabInt.fromBigInt(x)).isAlwaysFalse
    }
    for(i<-keys.indices;j<-0 until i if keys(i)._2 != keys(j)._2)
      if(!disjoint(semantic(keys(i)._1),semantic(keys(j)._1))) fail("constant switch keys overlap in the legal parameter domain")
  }
  private[spinal] def expressions(owner: Component): Vector[ElaborationIntegerExpression] =
    entries(owner).flatMap(d => d.dependencies ++ d.width.toVector)
  private[spinal] def nonEmpty(owner: Component): Boolean = entries(owner).nonEmpty
  private[spinal] def prepare(owner: Component): Unit = {
    entries(owner).foreach { d =>
    if (!d.prepared) {
      if (!d.formal && d.localSource.isEmpty && owner.localNamingScope.allocateName(d.name) != d.name)
        fail(s"constant '${d.name}' collides with another declaration")
      d.prepared = true
    }
    }
    state(owner).operations.foreach(op => op.name = owner.localNamingScope.allocateName(op.hint + "_EXPR"))
    state(owner).sources.foreach { source=>
      val local=NativeLocalParameters.reference(owner,source.expression).map(name=>
        name -> NativeLocalParameters.referenceWidth(owner,source.expression).get)
      val direct=source.expression.parameters match {
        case Vector(p) if source.expression.verilog==p.name && source.expression.generateIndex.isEmpty=>
          Some(p.name -> p.representation.map(_.width).getOrElse(32))
        case _=>None
      }
      local.orElse(direct) match {
        case Some((name,width))=>source.name=name;source.sourceWidth=width
        case None=>source.name=owner.localNamingScope.allocateName(source.hint+"_VALUE");source.declared=true
      }
    }
  }
  private[core] def declarations(owner: Component): String = {
    entries(owner).filter(d=> !d.formal && d.localSource.isEmpty).map { d =>
    val range = d.width.map(w=>s"[(${w.verilog})-1:0]").getOrElse(s"[${d.bitWidth-1}:0]")
    val signed = if (d.prototype.isInstanceOf[SInt]) "signed " else ""
    d.helpers.map(_()).mkString + s"  localparam $signed$range ${d.name} = ${d.rhs()};\n"
  }.mkString
  }

  /** Adapt an already-owned integer local directly using the same sizing rules
    * as native parameter-value publication, including crossing width domains.
    */
  private[core] def fromLocal[T <: BitVector](value:ElabInt,prototype:T):Option[T] = {
    val owner=Component.current
    val source=value.expression
    if(source.typedLocalOwner==null) return None
    if(source.typedLocalOwner ne owner) fail("local constant used in a foreign Component")
    if(NativeLocalParameters.declarationWidth(owner,source).isEmpty)fail("local declaration identity is missing")
    val width=ParameterizedWidth.expressionOf(prototype)
    val declaration=new Declaration(owner,"",prototype,width,Vector(source),()=>"",false,Some(Right(value)),Some(source))
    state(owner).entries += declaration
    val result=cloneOf(prototype)
    result.assignFrom(new Reference(declaration))
    result.addTag(ConstantCarrier)
    Some(result)
  }

  /** Runtime bridge used only after the frontend has supplied declaration identity. */
  def declare[T <: BaseType](name: String, value: T,
      formalValue: Option[ElabInt] = None): T = {
    val owner = Component.current
    ownerCheck(owner)
    if (value == null || name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*"))
      fail("a constant needs a value and a portable HDL name")
    if (entries(owner).exists(_.name == name)) fail(s"duplicate constant '$name'")
    val width = ParameterizedWidth.expressionOf(value)
    width.foreach(w => ElaborationWidthAuthority.requireAuthoritative(w,"constant width","SPINAL-PARAM-CONSTANT-WIDTH"))
    if (value.getBitsWidth < 1 || width.exists(_.minimum < 1)) fail("constant width must be positive throughout its domain")
    val dependencies = ArrayBuffer.empty[ElaborationIntegerExpression]
    val visiting = new IdentityHashMap[Expression, java.lang.Boolean]()
    val checks = ArrayBuffer.empty[() => Unit]
    val helpers = ArrayBuffer.empty[() => String]
    def operation(e: Expression, left: Expression, right: Expression, operator: String): () => String = {
      val l = capture(left); val r = capture(right)
      val geometry = NativeWidthProvenance.widthOf(e).getOrElse(fail("constant operator has no authenticated width"))
      ElaborationWidthAuthority.requireAuthoritative(geometry, "constant operator width", "SPINAL-PARAM-CONSTANT-WIDTH")
      dependencies += geometry
      def sized(input: Expression, render: () => String): () => String = {
        val w = NativeWidthProvenance.widthOf(input).getOrElse(fail("constant operand width is unauthenticated"))
        if(w.minimum != w.maximum || geometry.minimum != geometry.maximum)
          fail("native constant arithmetic requires fixed operand widths; use an elaboration expression for symbolic geometry")
        val extra = geometry.minimum - w.minimum
        if(extra < 0) fail("constant operator narrows an operand without an authenticated resize")
        if(extra == 0) render
        else if(input.getTypeObject != TypeSInt) () => s"{{${extra}{1'b0}},${render()}}"
        else {
          val operand = new Operation(w, true, render, name + "_OPERAND")
          state(owner).operations += operand
          helpers += (() => operand.declaration)
          () => s"$$signed({{${extra}{${operand.name}[${w.minimum - 1}]}},${operand.name}})"
        }
      }
      val leftValue = sized(left, l); val rightValue = sized(right, r)
      val op = new Operation(geometry, e.getTypeObject == TypeSInt, () => s"(${leftValue()} $operator ${rightValue()})", name)
      state(owner).operations += op
      helpers += (() => op.declaration)
      () => op.name
    }
    def capture(e: Expression): () => String = {
      if (visiting.containsKey(e)) fail("cyclic constant dependency")
      visiting.put(e,true)
      val result: () => String = e match {
        case r: Reference => r.declaration.check(owner); dependencies ++= r.declaration.dependencies; () => r.render(owner)
        case b: BoolLiteral => val text=if(b.value) "1'b1" else "1'b0"; () => text
        case b: BitVectorLiteral if !b.hasPoison() && b.hasSpecifiedBitCount =>
          val n=b.getValue(); val w=b.getWidth
          if(w<1 || (b.getTypeObject != TypeSInt && (n<0 || n >= (BigInt(1)<<w))) ||
              (b.getTypeObject == TypeSInt && (n < -(BigInt(1)<<(w-1)) || n >= (BigInt(1)<<(w-1))))) fail("literal is outside its declared representation")
          val bits=(n & ((BigInt(1)<<w)-1)).toString(2)
          val text=s"$w'${if(b.getTypeObject==TypeSInt) "s" else ""}b${"0"*(w-bits.length)}$bits"
          () => text
        case b: BaseType =>
          if ((b.component ne owner) || b.isIo || b.isReg) fail("runtime signals, registers and foreign values are not elaboration constants")
          val assignments=ArrayBuffer.empty[DataAssignmentStatement]
          b.foreachStatements {
            case a:DataAssignmentStatement if (a.target eq b) && (a.parentScope eq owner.dslBody)=>assignments += a
            case _=>fail("constant has conditional, partial or unsupported assignments")
          }
          if(assignments.size!=1) fail("constant needs exactly one immutable assignment")
          val assignment=assignments.head;val source=assignment.source
          checks += (()=> {
            val current=ArrayBuffer.empty[DataAssignmentStatement]
            b.foreachStatements {case a:DataAssignmentStatement=>current += a;case _=>fail("constant changed after capture")}
            if(current.size!=1 || (current.head ne assignment) || (assignment.source ne source)) fail("constant reassigned after capture")
          })
          b match {
            case vector: BitVector if ExternalParameterizedValueRegistry.recordOf(vector).nonEmpty=>
              val record=ExternalParameterizedValueRegistry.recordOf(vector).get
              if(!record.assignment.contains(assignment) || !record.witnessSource.contains(source)) fail("constant value lineage changed")
              ElaborationWidthAuthority.requireAuthoritative(record.expression,"constant value","SPINAL-PARAM-CONSTANT-AUTHORITY")
              state(owner).adapters += vector
              dependencies += record.expression
              // Only the declaration's whole unsigned initializer can use
              // assignment sizing. Nested hardware operations still need their
              // explicit operand widths to preserve modular arithmetic.
              if ((vector eq value) && !vector.isInstanceOf[SInt] && formalValue.isEmpty &&
                  width.exists(_.parameters.nonEmpty)) {
                ExternalParameterizedValueRegistry.validateCarrierDomain(vector, record.expression, None)
                () => record.expression.verilog
              } else {
                val valueSource=new ValueSource(record.expression,name,ParameterizedWidth.expressionOf(vector),vector.getBitsWidth,vector.isInstanceOf[SInt])
                state(owner).sources += valueSource
                helpers += (() => if(valueSource.declared) s"  localparam [31:0] ${valueSource.name} = ${valueSource.expression.verilog};\n" else "")
                () => valueSource.render
              }
            case _=>
              val targetWidth = NativeWidthProvenance.widthOf(b).getOrElse(fail("constant target width is unauthenticated"))
              val sourceWidth = NativeWidthProvenance.widthOf(source).getOrElse(fail("constant source width is unauthenticated"))
              if(!ElaborationWidthAuthority.equivalent(targetWidth, sourceWidth))
                fail("constant assignment width differs across the legal domain")
              capture(source)
          }
        case cast: CastBitVectorToBitVector =>
          val input=capture(cast.input)
          () => s"$$${if(cast.getTypeObject==TypeSInt) "signed" else "unsigned"}(${input()})"
        case cast: CastBoolToBits => capture(cast.input)
        case op: Operator.BitVector.Add => operation(op, op.left, op.right, "+")
        case op: Operator.BitVector.Sub => operation(op, op.left, op.right, "-")
        case op: Operator.BitVector.And => operation(op, op.left, op.right, "&")
        case op: Operator.BitVector.Or => operation(op, op.left, op.right, "|")
        case op: Operator.BitVector.Xor => operation(op, op.left, op.right, "^")
        case _ => fail(s"unsupported constant initializer ${e.opName}; use a literal or authenticated elaboration expression")
      }
      visiting.remove(e);result
    }
    val captured=capture(value)
    val rhs = formalValue match {
      case Some(v)=>dependencies += v.expression; () => v.expression.verilog
      case None=>captured
    }
    def semantic(e:Expression):Option[Either[BigInt,ElabInt]]=e match {
      case r:Reference=>r.declaration.semantic
      case l:Literal=>Some(Left(l.getValue()))
      case c:CastUIntToBits=>semantic(c.input)
      case c:CastBitsToUInt=>semantic(c.input)
      case b:BitVector if ExternalParameterizedValueRegistry.recordOf(b).nonEmpty=>
        Some(Right(ElabInt.fromExpression(ExternalParameterizedValueRegistry.recordOf(b).get.expression)))
      case b:BaseType=>b.head match {
        case a:DataAssignmentStatement=>semantic(a.source)
        case _=>None
      }
      case _=>None
    }
    val meaning=formalValue.map(v=>Right(v):Either[BigInt,ElabInt]).orElse(semantic(value))
    meaning match {
      case Some(Left(n))=>
        val minimumWidth=width.map(_.minimum.toInt).getOrElse(value.getBitsWidth)
        val signed=value.isInstanceOf[SInt]
        val low=if(signed) -(BigInt(1)<<(minimumWidth-1)) else BigInt(0)
        val high=(BigInt(1)<<(minimumWidth-(if(signed)1 else 0)))-1
        if(n<low || n>high) fail("constant does not fit every legal width override")
      case Some(Right(n)) if value.isInstanceOf[BitVector]=>
        ExternalParameterizedValueRegistry.validateCarrierDomain(value.asInstanceOf[BitVector],n.expression,None)
      case _ if width.exists(_.parameters.nonEmpty)=>fail("symbolic constant width requires an authenticated value domain")
      case _=>
    }
    val d=new Declaration(owner,name,value,width,dependencies.toVector,rhs,formalValue.nonEmpty,meaning, helpers = helpers.toVector)
    state(owner).entries += d
    val result=cloneOf(value)
    result.assignFrom(new Reference(d))
    result.addTag(ConstantCarrier)
    val issued=result.head
    owner.addPrePopTask(()=> {
      checks.foreach(_())
      val assignments=ArrayBuffer.empty[DataAssignmentStatement]
      result.foreachStatements {case a:DataAssignmentStatement=>assignments += a;case _=>fail("constant declaration mutated")}
      if(result.isIo || result.isReg || assignments.size!=1 || (assignments.head ne issued))
        fail("constant declaration reassigned after capture")
    })
    result
  }
  private[spinal] def retireAdapters(owner:Component):Unit = {
    val referenced=new IdentityHashMap[BaseType,java.lang.Boolean]()
    owner.dslBody.walkStatements {s=>s.walkDrivingExpressions {
      case b:BaseType=>referenced.put(b,true)
      case _=>
    }}
    val candidates=Option(owner.userCache.getOrElse(Key,null)).toVector.flatMap(_.asInstanceOf[State].adapters)
    val removed=new IdentityHashMap[BaseType,java.lang.Boolean]()
    candidates.foreach { b=>
      if(!referenced.containsKey(b) && !removed.containsKey(b) && b.parentScope!=null && b.hasOnlyOneStatement) {
        val authenticated=ExternalParameterizedValueRegistry.recordOf(b).exists {record=>
          record.assignment.exists(a=>(a eq b.head) && record.witnessSource.exists(_ eq a.source))
        }
        if(authenticated) {b.foreachStatements(_.removeStatement());b.removeStatement();removed.put(b,true)}
      }
    }
  }

  /** Lower exact immutable declaration carriers, even when debug settings retain
    * named ordinary wires. This is constant publication, not optional cleanup.
    */
  private[core] def lower(owner:Component):Unit = {
    val carriers=ArrayBuffer.empty[BaseType]
    owner.dslBody.walkDeclarations {case b:BaseType if isCarrier(b)=>carriers += b;case _=>}
    def reference(b:BaseType):Reference=b.head match {
      case a:DataAssignmentStatement=>a.source match {
        case r:Reference if b.hasOnlyOneStatement && (r.declaration.owner eq owner)=>r
        case _=>fail("constant carrier changed before native lowering")
      }
      case _=>fail("constant carrier lost its driver")
    }
    carriers.foreach(reference)
    owner.dslBody.walkStatements { s=>s.walkRemapDrivingExpressions {
      case b:BaseType if isCarrier(b)=>reference(b).fresh
      case other=>other
    }}
    carriers.foreach { b=>b.foreachStatements(_.removeStatement());b.removeStatement() }
    retireAdapters(owner)
  }
  private[core] def formalActual(value: BaseType): Option[ElabInt] = {
    if(!isCarrier(value)) return None
    value.head match {
      case a:DataAssignmentStatement=>a.source match {
        case r:Reference=>
          r.declaration.check(Component.current)
          if(!r.declaration.formal) fail("private local constants cannot be promoted to child generics")
          r.declaration.semantic.collect {case Right(v)=>v}
        case _=>fail("parameter carrier lineage changed")
      }
      case _=>fail("parameter carrier lineage changed")
    }
  }
  private[core] object ConstantCarrier extends SpinalTag { override def canSymplifyHost = true }
  private[core] def isCarrier(value: BaseType): Boolean = value.hasTag(ConstantCarrier)
}
