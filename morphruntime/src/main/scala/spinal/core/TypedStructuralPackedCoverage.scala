package spinal.core

import spinal.core.internals._
import scala.collection.mutable.ArrayBuffer

/** Complete disjoint static packed writes with a single-root width. Other
  * generate choices must prove identical coverage on both alternatives.
  * Native checks see only their construction-width coverage;
  * emitted targets retain the authored range, proved at every active value.
  */
private[core] object TypedStructuralPackedCoverage {
  private object Key
  private object ContinuousKey
  private def continuous(component: Component) = component.userCache.getOrElseUpdate(ContinuousKey,
    new java.util.IdentityHashMap[DataAssignmentStatement,java.lang.Boolean]())
    .asInstanceOf[java.util.IdentityHashMap[DataAssignmentStatement,java.lang.Boolean]]
  private final class Receipt(val replay: () => Unit)
  private def records(component: Component) = component.userCache.getOrElseUpdate(Key,
    new java.util.IdentityHashMap[DataAssignmentStatement,Receipt]())
    .asInstanceOf[java.util.IdentityHashMap[DataAssignmentStatement,Receipt]]
  def owns(component: Component, assignment: DataAssignmentStatement): Boolean =
    Option(records(component).get(assignment)).exists { receipt => receipt.replay(); true }
  def disjoint(component: Component, left: DataAssignmentStatement, right: DataAssignmentStatement): Boolean = {
    val a=records(component).get(left); val b=records(component).get(right)
    if(a==null || (a ne b) || (left eq right)) false else { a.replay(); true }
  }
  def retainNativeProcess(component: Component, statements: Vector[LeafStatement]): Boolean = {
    val exact = statements.collect { case a: DataAssignmentStatement if owns(component,a) => a }
    if(exact.isEmpty || exact.size!=statements.size) false
    else {
      exact.foreach(a => continuous(component).put(a,java.lang.Boolean.FALSE))
      true
    }
  }
  def converted(component: Component, assignment: DataAssignmentStatement): Unit =
    if(continuous(component).containsKey(assignment)) {
      require(owns(component,assignment),"continuous packed assignment lost its coverage proof")
      continuous(component).put(assignment,java.lang.Boolean.TRUE)
    }
  def validatePublication(component: Component): Unit = {
    val entries=continuous(component).entrySet().iterator()
    while(entries.hasNext) {
      val entry=entries.next()
      if(!entry.getValue.booleanValue() || !owns(component,entry.getKey))
        reject("constant packed process was not completely published as continuous drivers")
    }
  }
  def retain(component: Component): Unit = {
    val regions = ParameterizedStructure.regionsOf(component)
    def loopAssignments(items: Vector[ParameterizedStructure.StructuralRegion]): Vector[DataAssignmentStatement] =
      items.flatMap {
        case loop: ParameterizedStructure.StructuralFor =>
          ParameterizedStructure.allBlocks(loop).flatMap(_.assignments)
        case region => region.blocks.flatMap(block => loopAssignments(block.regions))
      }
    val indexed = loopAssignments(regions)
    val captured = regions
      .flatMap(ParameterizedStructure.allBlocks).flatMap(_.assignments)
    type Path = Vector[(ParameterizedStructure.StructuralRegion,Int)]
    def paths(items: Vector[ParameterizedStructure.StructuralRegion], prefix: Path):
        Vector[(DataAssignmentStatement,Path)] = items.flatMap { region =>
      region.blocks.zipWithIndex.flatMap { case (block,slot) =>
        val path=prefix :+ (region -> slot)
        block.assignments.map(_ -> path) ++ paths(block.regions,path)
      }
    }
    def samePath(left: Path, right: Path): Boolean = left.size==right.size &&
      left.zip(right).forall { case ((a,x),(b,y)) => (a eq b) && x==y }
    val capturedPaths=paths(regions,Vector.empty)
    val targets = captured.collect {
      case a if a.target.isInstanceOf[RangedAssignmentFixed] => a.finalTarget
    }.distinct
    targets.filter(_.isComb).foreach { target =>
      val drivers = ArrayBuffer.empty[DataAssignmentStatement]
      target.foreachStatements { case a: DataAssignmentStatement => drivers += a; case _ => }
      val width = ParameterizedWidth.expressionOf(target)
      val domain = width.flatMap(_.exactDomain)
      if (domain.nonEmpty && drivers.nonEmpty && drivers.forall(a =>
          a.target.isInstanceOf[RangedAssignmentFixed] && (a.parentScope eq component.dslBody) &&
            captured.count(_ eq a) == 1 && !indexed.exists(_ eq a) &&
            capturedPaths.find(_._1 eq a).exists(_._2.forall(_._1.isInstanceOf[ParameterizedStructure.StructuralIf])))) {
        val exact = domain.get
        val stable = drivers.toVector
        val ownerPaths=stable.map(a => capturedPaths.find(_._1 eq a).get._2)
        val original = drivers.toVector.map(a => a.target.asInstanceOf[RangedAssignmentFixed])
        val geometry = original.map(r => (r.out,r.hi,r.lo))
        val owner = ParameterizedStructure.exactDeclarationDomainOf(component,target,exact.root,
          exact.universe,"structural packed coverage",width.get.sourceLocation)
        def blockCoverage(block: ParameterizedStructuralBlock, value: BigInt): Vector[(Int,Int)] =
          block.assignments.filter(a => stable.exists(_ eq a)).map { a =>
            val r=a.target.asInstanceOf[RangedAssignmentFixed];r.lo -> r.hi
          } ++ block.regions.flatMap(regionCoverage(_,value))
        def regionCoverage(region: ParameterizedStructure.StructuralRegion, value: BigInt): Vector[(Int,Int)] = {
          if(!ParameterizedStructure.allBlocks(region).exists(_.assignments.exists(a => stable.exists(_ eq a))))
            return Vector.empty
          region match {
            case branch: ParameterizedStructure.StructuralIf =>
              branch.predicateDomain match {
                case Some(d) if d.root.elaborationRoot.exists(_ eq exact.root) && d.universe(value) =>
                  blockCoverage(if(d.whenTrue(value)) branch.whenTrue else branch.whenFalse,value)
                case _ =>
                  // Unknown parameter choices are deliberately overapproximated:
                  // both must drive precisely the same disjoint intervals.
                  val yes=blockCoverage(branch.whenTrue,value).sorted
                  val no=blockCoverage(branch.whenFalse,value).sorted
                  if(yes!=no) reject("packed coverage differs across an independently guarded alternative")
                  yes
              }
            case _ => reject("static packed coverage requires authenticated generate-if ownership")
          }
        }
        owner.values.foreach { value =>
          val size = exact.evaluate(value).getOrElse(reject("missing width evaluation"))
          val active = regions.flatMap(regionCoverage(_,value)).sortBy(_._1)
          var next = BigInt(0)
          active.foreach { case (lo,hi) =>
            if(lo != next || hi < lo || BigInt(hi) >= size)
              reject(s"overlapping, missing or out-of-domain packed write at ${exact.root.name}=$value")
            next = BigInt(hi)+1
          }
          if(next != size) reject(s"incomplete packed coverage at ${exact.root.name}=$value")
        }
        val scopes = stable.map(_.parentScope)
        val receipt = new Receipt(() => {
          val actual = ArrayBuffer.empty[DataAssignmentStatement]
          target.foreachStatements { case a: DataAssignmentStatement => actual += a; case _ => }
          val currentPaths=paths(ParameterizedStructure.regionsOf(component),Vector.empty)
          if(!target.isComb || actual.size!=stable.size || !actual.forall(a => stable.exists(_ eq a)) ||
              !ParameterizedWidth.expressionOf(target).exists(_ eq width.get) ||
              !stable.indices.forall { i =>
                val a=stable(i)
                val matching=currentPaths.filter(_._1 eq a)
                (a.parentScope eq scopes(i)) && matching.size==1 && samePath(matching.head._2,ownerPaths(i)) && (a.target match {
                  case r: RangedAssignmentFixed => (r.out eq geometry(i)._1) && r.hi==geometry(i)._2 && r.lo==geometry(i)._3
                  case _ => false
                })
              }) reject("packed coverage lost its complete driver inventory, geometry or exact domains")
        })
        drivers.zipWithIndex.foreach { case (assignment,index) =>
          val (source,high,low) = geometry(index)
          val scope = assignment.parentScope
          val replacement = new RangedAssignmentFixed {
            out=source; hi=high; lo=low
            private def authenticate(): Unit = {
              receipt.replay()
              if ((assignment.target ne this) || (out ne source) || hi!=high || lo!=low ||
                  (assignment.parentScope ne scope) ||
                  !ParameterizedWidth.expressionOf(target).exists(_ eq width.get) ||
                  !ParameterizedStructure.regionsOf(component).flatMap(ParameterizedStructure.allBlocks)
                    .exists(_.assignments.exists(_ eq assignment)))
                reject("packed coverage lost its exact assignment, geometry or owner")
            }
            override def normalizeInputs: Unit = authenticate()
            override def getMinAssignedBits: AssignedRange = {
              authenticate()
              if(high < source.getWidth) AssignedRange(high,low) else AssignedRange()
            }
            override def getMaxAssignedBits: AssignedRange = getMinAssignedBits
          }
          assignment.target=replacement
          records(component).put(assignment,receipt)
        }
        // All native overlaps are between alternatives proved disjoint above.
        // No unproven writer may be added without invalidating the receipt.
        target.allowOverride()
      }
    }
  }
  private def reject(message: String): Nothing = ParameterizedVerilogException.fail(
    "SPINAL-STRUCTURAL-PACKED-COVERAGE-UNPROVEN",message,None)
}
