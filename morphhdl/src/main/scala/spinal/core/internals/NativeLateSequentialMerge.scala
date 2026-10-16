package spinal.core.internals

import spinal.core._
import scala.collection.mutable

/** Late regrouping through the original native renderer. Final assignment lines
  * are transplanted only by exact native edge identity, never by target spelling.
  * Any changed guard skeleton or generated-scope placement leaves a group alone.
  */
private[internals] object NativeLateSequentialMerge {
  private object Key
  private object ReplayKey
  private final case class Group(clock: ClockDomain, scope: ScopeStatement, hasInit: Boolean,
      init: Vector[LeafStatement], data: Vector[LeafStatement],
      targets: Vector[BaseType], render: (Vector[LeafStatement],Vector[LeafStatement])=>String,
      var original: String = "") {
    def assignments: Vector[AssignmentStatement]=(init++data).collect {case a:AssignmentStatement=>a}
  }
  private def groups(component:Component):mutable.ArrayBuffer[Group]=component.userCache.getOrElseUpdate(Key,
    mutable.ArrayBuffer.empty[Group]).asInstanceOf[mutable.ArrayBuffer[Group]]
  def replaying(component:Component):Boolean=component.userCache.get(ReplayKey).contains(true)
  def owns(component:Component,assignment:AssignmentStatement):Boolean=
    groups(component).exists(_.assignments.exists(_ eq assignment))

  def capture(printer:ComponentEmitterVerilog,clock:ClockDomain,scope:ScopeStatement,hasInit:Boolean,
      init:Vector[LeafStatement],data:Vector[LeafStatement],
      render:(Vector[LeafStatement],Vector[LeafStatement])=>String):Option[String]={
    val component=printer.component
    val assignments=(init++data).collect {case a:AssignmentStatement=>a}
    val targets=assignments.map(_.finalTarget).distinct
    val structural=ParameterizedStructure.regionsOf(component).flatMap(ParameterizedStructure.allBlocks)
    // The native renderer reuses its initializer text for soft reset. Until
    // repeated reset occurrences have separate emission identities, retain
    // these processes unchanged instead of issuing a single-use edge marker.
    val eligible=printer.spinalConfig.mergeSyncProcess && !clock.hasSoftResetSignal &&
      (scope eq component.dslBody) && data.nonEmpty &&
      assignments.size==init.size+data.size && targets.nonEmpty && targets.forall(t=>
        t.isReg && (t.component eq component) && t.hasTag(structuralSyncSeparation) && !t.hasTag(noBackendSyncMerge)) &&
      !structural.exists(block=>block.assignments.exists(a=>assignments.exists(_ eq a)) ||
        block.declarations.exists(t=>targets.exists(_ eq t)))
    if(!eligible)return None
    val group=Group(clock,scope,hasInit,init,data,targets,render)
    groups(component)+=group
    group.original=render(init,data)
    Some(group.original)
  }

  def publish(component:Component,source:String):String={
    val candidates=groups(component).toVector
    if(candidates.size<2)return source
    val lines=source.split("\n",-1).toVector
    val edges=NativeFiniteStatementLineage.survivingLineAssignments(component,lines)
    val processes=ParameterizedVerilogStructural.proceduralBlocks(lines,None)
    def skeleton(text:Vector[String]):Vector[String]=text.filter(line=>
      NativeFiniteStatementLineage.suffixOfLine(component,line).isEmpty).map(_.trim).filter(_.nonEmpty)
    def moduleScope(at:Int):Boolean={
      var depth=0
      lines.take(at).foreach { line=>
        val code=line.takeWhile(_!='`').trim
        if(code=="generate")depth+=1
        if(code=="endgenerate")depth-=1
      }
      depth==0
    }
    val located=candidates.flatMap { group=>
      val matching=edges.collect {case (line,a) if group.assignments.exists(_ eq a)=>line}.toVector
      val scopes=processes.filter(range=>matching.exists(range.indices.contains))
      if(matching.size!=group.assignments.size || scopes.size!=1 || !moduleScope(scopes.head.start))None
      else {
        val range=scopes.head
        val actual=range.indices.flatMap(edges.get).toVector
        if(actual.size!=group.assignments.size || !actual.forall(a=>group.assignments.exists(_ eq a)) ||
          skeleton(range.indices.map(lines).toVector)!=skeleton(group.original.split("\n",-1).toVector))None
        else Some(group->range)
      }
    }
    val order=new java.util.IdentityHashMap[LeafStatement,Int]()
    var ordinal=0
    component.dslBody.walkLeafStatements {leaf=>order.put(leaf,ordinal);ordinal+=1}
    val partitions=mutable.ArrayBuffer.empty[mutable.ArrayBuffer[(Group,ParameterizedVerilogStructural.LineRange)]]
    located.foreach { item=>
      partitions.find { partition=>
        val first=partition.head._1
        (first.clock eq item._1.clock) && (first.scope eq item._1.scope) && first.hasInit==item._1.hasInit &&
          !partition.exists(_._1.targets.exists(t=>item._1.targets.exists(_ eq t)))
      }.getOrElse {val p=mutable.ArrayBuffer.empty[(Group,ParameterizedVerilogStructural.LineRange)];partitions+=p;p} += item
    }
    val replacements=mutable.Map.empty[Int,Vector[String]]
    val removed=mutable.Set.empty[Int]
    partitions.filter(_.size>1).foreach { partition=>
      val selected=partition.map(_._1).toVector
      val all=selected.flatMap(_.assignments)
      if(all.forall(order.containsKey)) {
        val init=selected.flatMap(_.init).sortBy(order.get(_))
        val data=selected.flatMap(_.data).sortBy(order.get(_))
        component.userCache(ReplayKey)=true
        val rendered=try selected.head.render(init,data) finally component.userCache.remove(ReplayKey)
        val template=rendered.split("\n",-1).toVector
        val templateEdges=NativeFiniteStatementLineage.survivingLineAssignments(component,template)
        require(templateEdges.size==all.size && templateEdges.values.forall(a=>all.exists(_ eq a)),
          "late native process replay changed its exact assignment inventory")
        val transplanted=template.zipWithIndex.map {case (line,index)=>templateEdges.get(index) match {
          case Some(assignment)=>
            val original=edges.collect {case (at,a) if a eq assignment=>lines(at)}.toVector
            require(original.size==1,"late process assignment lost its unique final emission")
            line.takeWhile(_.isWhitespace)+original.head.trim
          case None=>line
        }}
        val ranges=partition.map(_._2).sortBy(_.start)
        replacements(ranges.head.start)=transplanted
        ranges.foreach(r=>removed++=r.indices)
      }
    }
    lines.indices.flatMap(i=>replacements.getOrElse(i,if(removed(i))Vector.empty else Vector(lines(i)))).mkString("\n")
  }
}
