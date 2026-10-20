package spinal.core

/** Source-level parameter type, resolved by the MorphHDL compiler plugin.
  * Integer declarations derive their finite domain from unconditional require
  * constraints. A factory call outside a declaration is an actual literal.
  */
@scala.annotation.compileTimeOnly("Param[T] requires the MorphHDL compiler plugin")
sealed trait Param[T]
object Param {
  /** `local` defaults to false for declaration syntax. Explicit options require
    * a compiler-owned declaration so a name and lexical owner are available.
    */
  @scala.annotation.compileTimeOnly("Param local options require an immutable declaration and the MorphHDL compiler plugin")
  def apply[T](value:T, local:Boolean):T =
    throw new UnsupportedOperationException("unlowered parameter declaration")
  @scala.annotation.compileTimeOnly("Param local options require an immutable declaration and the MorphHDL compiler plugin")
  def apply[T](value:T, name:String, local:Boolean):T =
    throw new UnsupportedOperationException("unlowered parameter declaration")

  /** Declaration lowering shared by annotations and inferred factory declarations. */
  def declareInt(name:String,value:Int,local:Boolean,minimum:BigInt,maximum:BigInt):ElabInt =
    declareInt(name,ElabInt.literal(value),local,minimum,maximum)
  def declareInt(name:String,value:ElabInt,local:Boolean,minimum:BigInt,maximum:BigInt):ElabInt = {
    require(value != null,"SPINAL-PARAM-CONSTANT-INVALID: null or cyclic parameter initializer")
    require(local || value.expression.typedLocalOwner == null,
      "SPINAL-PARAM-LOCAL-FORWARD: a private local cannot become a public formal")
    if(local) declare(name,value,true) else RtlParam.int(name,value,minimum,maximum)
  }
  def declare(name:String, value:Int, local:Boolean):ElabInt = declare(name,ElabInt.literal(value),local)
  def declare(name:String, value:Boolean, local:Boolean):ElabBool = declare(name,ElabBool.literal(value),local)
  def declare(name:String, value:ElabBool, local:Boolean):ElabBool = {
    require(value != null,"SPINAL-PARAM-CONSTANT-INVALID: null or cyclic Boolean initializer")
    if(local) declare(name,value.toElabInt,true).elabEq(1) else RtlParam.bool(name,value)
  }
  def declare(name:String, value:ElabInt, local:Boolean):ElabInt = {
    require(Component.current != null && (DslScopeStack.get eq Component.current.dslBody),
      "SPINAL-PARAM-DECLARATION-SCOPE: parameters need an unconditional Component declaration")
    require(value != null && (local || value.expression.typedLocalOwner == null),
      "SPINAL-PARAM-LOCAL-FORWARD: a private local cannot become a public formal")
    if(local) NativeLocalParameters.bindInteger(Component.current,value,name)
    else RtlParam.int(name,value,0,Int.MaxValue)
  }
  def declare[T <: BaseType](name:String, value:T, local:Boolean):T = {
    if(local) NativeParameterConstants.declare(name,value)
    else {
      val (actual,kind)=value match {
        case b:BitVector => (apply(b),b match {case _:Bits=>"Bits";case _:UInt=>"UInt";case _:SInt=>"SInt"})
        case b:Bool =>
          val literal=b.head match {
            case spinal.core.internals.DataAssignmentStatement(_,v:spinal.core.internals.BoolLiteral) => v.value
            case _=>throw new IllegalArgumentException("SPINAL-PARAM-LITERAL-REQUIRED: Bool default must be a literal")
          }
          val formal=RtlParam.packed(name,U(if(literal)1 else 0,1 bits),0,1,"UInt")
          return NativeParameterConstants.declare(name,value,Some(formal))
        case _=>throw new IllegalArgumentException("SPINAL-PARAM-TYPE-UNSUPPORTED")
      }
      val representation=actual.packedRepresentation.get
      val formal=RtlParam.packed(name,actual,representation.minimum,representation.maximum,kind)
      NativeParameterConstants.declare(name,value,Some(formal))
    }
  }

  /** Forward an optional constructor formal with its exact declared presence.
    * The effective value in the component body is zero when absent.
    */
  def forwardOptional(formal:ElabInt):Option[ElabInt]=RtlParam.forwardOptional(formal)
  def isPresent(formal:ElabInt):Boolean=forwardOptional(formal).isDefined

  @scala.annotation.compileTimeOnly("Param.optional requires a typed Param[Int] declaration and finite require constraints")
  def optional(actual:Option[ElabInt], absent:Int, name:String = ""):ElabInt =
    throw new UnsupportedOperationException("Param.optional must be lowered by the compiler plugin")
  @scala.annotation.compileTimeOnly("Param.optional requires a typed Param[Int] declaration and finite require constraints")
  def optional(actual:ElabInt, absent:Int, name:String):ElabInt =
    throw new UnsupportedOperationException("Param.optional must be lowered by the compiler plugin")
  def apply(value: Int): ElabInt = ElabInt.literal(value)
  def apply(value: Boolean): ElabBool = ElabBool.literal(value)
  def apply(value: ElabInt): ElabInt = value
  def apply(value: ElabBool): ElabBool = value

  /** Only an explicitly sized, poison-free literal can supply a packed actual.
    * Hardware signals and inferred-width literals cannot become parameters.
    */
  def apply(value: BitVector): ElabInt = {
    NativeParameterConstants.formalActual(value).foreach(v => return v)
    import spinal.core.internals.{BitVectorLiteral, DataAssignmentStatement}
    require(value != null && value.isTypeNode && !value.isIo && !value.isReg,
      "SPINAL-PARAM-LITERAL-REQUIRED: packed defaults must be literal values")
    val sources = scala.collection.mutable.ArrayBuffer.empty[BitVectorLiteral]
    var other = false
    value.foreachStatements {
      case statement: DataAssignmentStatement if (statement.target eq value) => statement.source match {
        case literal: BitVectorLiteral => sources += literal
        case _ => other = true
      }
      case _ => other = true
    }
    require(!other && sources.size == 1 && !sources.head.hasPoison() && sources.head.hasSpecifiedBitCount && sources.head.getWidth > 0,
      s"SPINAL-PARAM-LITERAL-REQUIRED: packed defaults need one explicitly sized known literal (${value.getClass.getSimpleName}, sources=${sources.size}, other=$other)")
    val kind = value match {
      case _: UInt => "UInt"
      case _: SInt => "SInt"
      case _: Bits => "Bits"
    }
    val representation = ElaborationPackedParameter(kind, sources.head.getWidth)
    val number = sources.head.getValue()
    require(number >= representation.minimum && number <= representation.maximum,
      "SPINAL-PARAM-REPRESENTATION-OVERFLOW: default must fit its declared packed type")
    val literal = sources.head
    Option(value.component).foreach(_.addPrePopTask(() => {
      val live = scala.collection.mutable.ArrayBuffer.empty[DataAssignmentStatement]
      value.foreachStatements {
        case statement: DataAssignmentStatement => live += statement
        case _ =>
      }
      require(value.isTypeNode && !value.isIo && !value.isReg && live.size == 1 &&
        (live.head.target eq value) && (live.head.source eq literal) &&
        !literal.hasPoison() && literal.getWidth == representation.width && literal.getValue() == number,
        "SPINAL-PARAM-LITERAL-LINEAGE-CHANGED: packed actual changed after parameter capture")
    }))
    val result = ElabInt.fromBigInt(number)
    result.packedRepresentation = Some(representation)
    result
  }
}
