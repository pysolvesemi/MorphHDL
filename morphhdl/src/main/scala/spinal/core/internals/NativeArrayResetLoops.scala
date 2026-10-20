package spinal.core.internals

import spinal.core._
import scala.collection.mutable

/** Compact only authenticated, whole-element native reset edges. Publication
  * text supplies the already-validated literal spelling, never array ownership.
  */
private[internals] object NativeArrayResetLoops {
  private object InitKey
  private object DriverKey
  private object ArrayKey
  private case class Element(owner:AnyRef,name:String,index:Int)
  private def initializers(c:Component)=c.userCache.getOrElseUpdate(InitKey,
    mutable.ArrayBuffer.empty[AssignmentStatement]).asInstanceOf[mutable.ArrayBuffer[AssignmentStatement]]
  private def elements(c:Component)=c.userCache.getOrElseUpdate(ArrayKey,
    new java.util.IdentityHashMap[BaseType,Element]).asInstanceOf[java.util.IdentityHashMap[BaseType,Element]]
  private def drivers(c:Component)=c.userCache.getOrElseUpdate(DriverKey,
    mutable.ArrayBuffer.empty[AssignmentStatement]).asInstanceOf[mutable.ArrayBuffer[AssignmentStatement]]
  def tracks(c:Component,a:AssignmentStatement):Boolean=drivers(c).exists(_ eq a)
  def capture(c:Component,clock:ClockDomain,init:Vector[LeafStatement],data:Vector[LeafStatement]):Unit={
    if(!clock.hasResetSignal || clock.hasSoftResetSignal ||
        !(clock.config.resetKind == ASYNC || clock.config.resetKind == SYNC))return
    val carriers=ParameterizedVec.vectorsOf(c).flatMap(v=>v.asInstanceOf[Data].flatten)
    (init++data).collect {case a:AssignmentStatement if carriers.exists(_ eq a.finalTarget)=>a}.foreach {a=>
      if(!drivers(c).exists(_ eq a))drivers(c)+=a
    }
    init.collect {case a:InitAssignmentStatement if carriers.exists(_ eq a.finalTarget) => a}.foreach {a=>
      if(!initializers(c).exists(_ eq a))initializers(c)+=a
    }
  }
  def owns(c:Component,a:AssignmentStatement):Boolean=initializers(c).exists(_ eq a)
  def array(c:Component,owner:AnyRef,name:String,lanes:Vector[(BaseType,Int)]):Unit=
    lanes.foreach {case (value,index)=>elements(c).put(value,Element(owner,name,index))}

  def publish(c:Component,source:String):String={
    val lines=source.split("\n",-1).toVector
    val edges=NativeFiniteStatementLineage.survivingLineAssignments(c,lines)
    case class Row(at:Int,element:Element,rhs:String,indent:String)
    val rows=edges.flatMap {case(at,a)=>
      if(!owns(c,a) || !a.target.isInstanceOf[BaseType] || !a.source.isInstanceOf[Literal])None else Option(elements(c).get(a.finalTarget)).flatMap {element=>
        val marker=NativeFiniteStatementLineage.suffixOfLine(c,lines(at))
        val code=lines(at).stripSuffix(marker).trim
        val prefix=s"${element.name}[${element.index}] <= "
        if(code.startsWith(prefix) && code.endsWith(";") && !code.contains("//"))
          Some(at->Row(at,element,code.drop(prefix.length).dropRight(1),lines(at).takeWhile(_.isWhitespace)))
        else None
      }
    }
    if(rows.isEmpty)return source
    val used=mutable.Set.empty[String] ++ "[A-Za-z_][A-Za-z0-9_$]*".r.findAllIn(source)
    def fresh(stem:String):String={var name=stem;var n=0;while(used(name)){n+=1;name=stem+"_"+n};used+=name;name}
    val replacements=mutable.Map.empty[Int,Vector[String]]
    val removed=mutable.Set.empty[Int]
    ParameterizedVerilogStructural.proceduralBlocks(lines,None).foreach {process=>
      // A named block admits local integer declarations in Verilog-2001.
      if(lines(process.start).trim.startsWith("always @") && lines(process.start).trim.endsWith(" begin")) {
        val resetEdges=process.indices.flatMap(edges.get).filter(owns(c,_)).flatMap(a=>
          Option(elements(c).get(a.finalTarget)).map(e=>a->e))
        val unsafe=resetEdges.groupBy(_._2.owner).collect {case(owner,items) if
          items.map(_._2.index).distinct.size!=items.size ||
          items.exists {case(a,_)=> !a.target.isInstanceOf[BaseType] || !a.source.isInstanceOf[Literal]} => owner}.toSet
        val runs=mutable.ArrayBuffer.empty[Vector[Row]]
        var at=process.start+1
        while(at<process.end) {
          rows.get(at) match {
            case Some(first) if !unsafe(first.element.owner)=>
              val run=mutable.ArrayBuffer(first);var next=at+1
              while(rows.get(next).exists(r=>(r.element.owner eq first.element.owner) &&
                  r.element.index==run.last.element.index+1 && r.rhs==first.rhs && r.indent==first.indent)) {
                run+=rows(next);next+=1
              }
              if(run.size>1)runs+=run.toVector
              at=next
            case _=>at+=1
          }
        }
        if(runs.nonEmpty) {
          val index=fresh("reset_index");val label=fresh("reset_scope")
          replacements(process.start)=Vector(lines(process.start)+" : "+label,
            lines(process.start).takeWhile(_.isWhitespace)+"  integer "+index+";")
          var i=0
          while(i<runs.size) {
            val first=runs(i);val shared=mutable.ArrayBuffer(first);var j=i+1
            // Only adjacent disjoint literal-only runs may be interleaved.
            while(j<runs.size && runs(j).head.at==shared.last.last.at+1 &&
                runs(j).head.element.index==first.head.element.index && runs(j).size==first.size &&
                runs(j).head.indent==first.head.indent &&
                !shared.exists(r=>r.head.element.owner eq runs(j).head.element.owner)) {shared+=runs(j);j+=1}
            val indent=first.head.indent
            replacements(first.head.at)=Vector(s"${indent}for ($index = ${first.head.element.index}; $index < ${first.last.element.index+1}; $index = $index + 1) begin") ++
              shared.map(r=>s"$indent  ${r.head.element.name}[$index] <= ${r.head.rhs};") ++ Vector(indent+"end")
            shared.flatten.foreach(r=>removed+=r.at)
            i=j
          }
        }
      }
    }
    lines.indices.flatMap(i=>replacements.getOrElse(i,if(removed(i))Vector.empty else Vector(lines(i)))).mkString("\n")
  }
}
