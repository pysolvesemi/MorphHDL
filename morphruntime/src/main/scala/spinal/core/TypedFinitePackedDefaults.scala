package spinal.core

import spinal.core.internals._

/** Project an authenticated whole-vector constant default into disjoint loop
  * lanes. Keeping the whole default inside every generated process would create
  * overlapping drivers. Native checks still see the original complete default.
  */
private[spinal] object TypedFinitePackedDefaults {
  private object Key
  private final case class Entry(target: Bits, assignment: DataAssignmentStatement,
      source: Expression, literal: BitsLiteral, value: BigInt, width: Int,
      writes: Vector[TypedFinitePackedAccess.Write], drivers: Vector[DataAssignmentStatement])
  private def entries(component: Component): Vector[Entry] = component.userCache.get(Key)
    .map(_.asInstanceOf[Vector[Entry]]).getOrElse(Vector.empty)

  def retain(component: Component): Unit = {
    val result=Vector.newBuilder[Entry]
    TypedFinitePackedAccess.writes(component).groupBy(_.source).foreach { case (target,writes) =>
      val drivers=scala.collection.mutable.ArrayBuffer.empty[DataAssignmentStatement]
      target.foreachStatements { case a:DataAssignmentStatement=>drivers+=a;case _=> }
      val defaults=drivers.filterNot(a=>writes.exists(_.assignment eq a)).toVector
      if(defaults.nonEmpty && target.isComb) {
        val width=NativeWidthProvenance.widthOf(target)
        val first=writes.head
        val complete=width.exists(_.parameters.isEmpty) && first.index.count.parameters.isEmpty &&
          writes.forall(w=>(w.index.token eq first.index.token) && w.complete && w.width==first.width &&
            w.stride==w.width && w.widthExpression.parameters.isEmpty && w.strideExpression.parameters.isEmpty) &&
          first.index.count.default*first.width==target.getBitsWidth && (target.component eq component) &&
          defaults.forall(a=>(a.target eq target) && (a.parentScope eq component.dslBody)) &&
          drivers.take(defaults.size).toVector==defaults
        if(!complete) reject("whole packed defaults require a complete, single-owner bit/word loop with preceding module-scope constants")
        defaults.foreach { assignment=>
          val literal=assignment.source match {
            case value:BitsLiteral=>Some(value)
            case value:Bits if value.isTypeNode && value.hasOnlyOneStatement=>value.head.source match {
              case bits:BitsLiteral=>Some(bits)
              case _=>None
            }
            case _=>None
          }
          literal match {
            case Some(bits) if !bits.hasPoison() && bits.getWidth==target.getBitsWidth=>
              result += Entry(target,assignment,assignment.source,bits,bits.getValue(),target.getBitsWidth,writes,drivers.toVector)
            case _=>reject("whole packed defaults currently require an exact fixed-width known constant")
          }
        }
      }
    }
    component.userCache(Key)=result.result()
  }
  private def entry(component:Component,assignment:AssignmentStatement):Option[Entry] =
    entries(component).find(_.assignment eq assignment).map { e=>
      val live=scala.collection.mutable.ArrayBuffer.empty[DataAssignmentStatement]
      e.target.foreachStatements {case a:DataAssignmentStatement=>live+=a;case _=>}
      // Native normalization removes the literal's temporary carrier. Its
      // exact original literal edge remains the authority after that removal.
      val sourceAuthentic=(assignment.source eq e.literal) || (e.source match {
        case bits:Bits=>bits.isTypeNode && bits.hasOnlyOneStatement && (bits.head.source eq e.literal)
        case literal:BitsLiteral=>literal eq e.literal
        case _=>false
      })
      val checks=Vector(
        "source carrier" -> sourceAuthentic,
        "target" -> (assignment.target eq e.target),
        "source edge" -> ((assignment.source eq e.source) || (assignment.source eq e.literal)),
        "literal" -> (!e.literal.hasPoison() && e.literal.getValue()==e.value && e.literal.getWidth==e.width),
        "target width" -> (e.target.getBitsWidth==e.width),
        "driver inventory" -> (live.toVector==e.drivers),
        "indexed writes" -> e.writes.forall(w=>TypedFinitePackedAccess.writes(component).exists(_ eq w) &&
          (w.assignment.target eq w.target)))
      if(checks.exists(!_._2)) reject("packed default lost " + checks.filterNot(_._2).map(_._1).mkString(", "))
      e
    }
  def owns(component:Component,assignment:AssignmentStatement):Boolean=entry(component,assignment).nonEmpty
  def target(component:Component,assignment:AssignmentStatement,reference:BaseType=>String):Option[String]=
    entry(component,assignment).map { e=>
      val write=e.writes.head
      val index=write.index.expression.verilog
      if(write.width==1)s"${reference(e.target)}[$index]"
      else s"${reference(e.target)}[($index) * ${write.stride} +: ${write.width}]"
    }
  def source(component:Component,assignment:AssignmentStatement):Option[String]=entry(component,assignment).map { e=>
    val write=e.writes.head
    val index=write.index.expression.verilog
    val mask=(BigInt(1)<<write.width)-1
    val word=e.value & mask
    val repeated=(0 until e.width/write.width).forall(lane=>((e.value>>(lane*write.width)) & mask)==word)
    if(repeated)s"${write.width}'h${word.toString(16)}"
    else {
      def bit(offset:String)=s"(((${e.width}'h${e.value.toString(16)} >> ($offset)) & ${e.width}'d1) != ${e.width}'d0)"
      if(write.width==1)bit(index)
      else (0 until write.width).reverse.map(b=>bit(s"($index) * ${write.stride} + $b")).mkString("{",", ","}")
    }
  }
  private def reject(message:String):Nothing=ParameterizedVerilogException.fail(
    "SPINAL-FINITE-PACKED-DEFAULT-UNPROVEN",message,None)
}
