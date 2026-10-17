package spinal.core

/** Compiler constructor boundary. These wrappers hold parent actuals; only
  * RtlParam creates child-owned formals. No conversion extracts a Scala witness.
  */
object ConstructorParam {
  final class Integer private[ConstructorParam](val value: ElabInt)
  object Integer {
    implicit def literal(value: Int): Integer = new Integer(ElabInt.literal(value))
    implicit def symbolic(value: ElabInt): Integer = {
      require(value != null, "SPINAL-PARAM-ACTUAL-NULL: integer actual is null")
      new Integer(value)
    }
  }
  final class BooleanValue private[ConstructorParam](val value: ElabBool)
  object BooleanValue {
    implicit def literal(value: Boolean): BooleanValue = new BooleanValue(ElabBool.literal(value))
    implicit def symbolic(value: ElabBool): BooleanValue = {
      require(value != null, "SPINAL-PARAM-ACTUAL-NULL: Boolean actual is null")
      new BooleanValue(value)
    }
  }
  final class Packed private[ConstructorParam](captured: ElabInt, audit: Option[() => Unit] = None) {
    def value:ElabInt = {
      audit.foreach { check=>
        require(Component.current!=null,"SPINAL-PARAM-ACTUAL-OWNER-MISSING: packed formal needs a component")
        check()
        Component.current.addPrePopTask(check)
      }
      captured
    }
  }
  object Packed {
    implicit def literal(value: => BitVector): Packed = {
      if(DslScopeStack.get != null) new Packed(Param(value))
      else {
        // Root constructor arguments precede Component creation. Capture only a
        // detached literal assignment, never a dummy Component or hardware graph.
        val scope=new spinal.core.internals.ScopeStatement(null)
        val context=scope.push()
        try {
          val previousTop=GlobalData.get.toplevel
          val literal=value
          require((GlobalData.get.toplevel eq previousTop) && literal.component==null,
            "SPINAL-PARAM-ACTUAL-SCOPE-UNSUPPORTED: root packed actual must not create a Component")
          val actual=Param(literal)
          require(scope.statementIterable.forall {
            case declaration:BaseType=>declaration eq literal
            case assignment:spinal.core.internals.DataAssignmentStatement=>
              (assignment.target eq literal) && (assignment eq literal.head)
            case _=>false
          }, "SPINAL-PARAM-ACTUAL-SCOPE-UNSUPPORTED: root packed actual must contain only its literal")
          val assignment=literal.head
          val source=assignment.source.asInstanceOf[spinal.core.internals.BitVectorLiteral]
          val width=source.getWidth
          val number=source.getValue()
          val statements=scope.statementIterable.toVector
          val audit=()=>require(literal.isTypeNode && !literal.isIo && !literal.isReg &&
            literal.hasOnlyOneStatement && (literal.head eq assignment) &&
            (assignment.source eq source) && !source.hasPoison() && source.hasSpecifiedBitCount &&
            source.getWidth==width && literal.getBitsWidth==width && source.getValue()==number &&
            scope.statementIterable.toVector==statements,
            "SPINAL-PARAM-LITERAL-LINEAGE-CHANGED: root packed actual changed after capture")
          new Packed(actual,Some(audit))
        } finally context.restore()
      }
    }
    implicit def symbolic(value: ElabInt): Packed = {
      require(value != null && value.packedRepresentation.nonEmpty,
        "SPINAL-PARAM-REPRESENTATION-MISSING: packed actual needs declared representation")
      new Packed(value)
    }
  }
  sealed trait IntegerInput[A] { def apply(value:A):ElabInt }
  object IntegerInput {
    implicit val literal:IntegerInput[Int]=new IntegerInput[Int] {
      def apply(value:Int):ElabInt=ElabInt.literal(value)
    }
    implicit val symbolic:IntegerInput[ElabInt]=new IntegerInput[ElabInt] {
      def apply(value:ElabInt):ElabInt={
        require(value!=null,"SPINAL-PARAM-ACTUAL-NULL: optional actual is null"); value
      }
    }
  }
  final class OptionalInteger private[ConstructorParam](val value: Option[ElabInt])
  object OptionalInteger {
    implicit def absent(value:None.type):OptionalInteger=new OptionalInteger(None)
    implicit def supplied[A](value:Option[A])(implicit input:IntegerInput[A]):OptionalInteger={
      require(value!=null,"SPINAL-PARAM-ACTUAL-NULL: optional actual is null")
      new OptionalInteger(value.map(input.apply))
    }
  }
  def integer(value:Integer):ElabInt=value.value
  def integer(value:ElabInt):ElabInt=value
  def integer(value:Int):ElabInt=ElabInt.literal(value)
  def boolean(value:BooleanValue):ElabBool=value.value
  def boolean(value:ElabBool):ElabBool=value
  def boolean(value:Boolean):ElabBool=ElabBool.literal(value)
  def packed(value:Packed):ElabInt=value.value
  def packed(value:ElabInt):ElabInt=value
  def packed(value:BitVector):ElabInt=Param(value)
  def optional(value:OptionalInteger):Option[ElabInt]=value.value
  def optional[A](value:Option[A])(implicit input:IntegerInput[A]):Option[ElabInt]=
    OptionalInteger.supplied(value).value
  def optional(value:None.type):Option[ElabInt]=None
}
