package spinal.core

import spinal.core.internals._

/** Exact write unions for positive-stride selectors. This authority is separate
  * from an affine read's bounds proof and cannot authorize previous-index reads.
  */
private[core] object TypedLoopAffineWrites {
  private final case class Claim(selection: ParameterizedStructure.StructuralVecIndex,
      block: ParameterizedStructuralBlock, writes: Vector[DataAssignmentStatement],
      rows: Set[(BigInt, BigInt)], lanes: Set[Int])

  final class Evidence private[TypedLoopAffineWrites] (group: Group, claim: Claim) {
    def matches: Boolean = group.matches && claim.selection.affineWrite.exists(_ eq this)
    def authorizes(vector: Vec[_], index: ElaborationIntegerExpression): Boolean =
      (claim.selection.vector eq vector) && (claim.selection.index eq index) && matches
    def laneIndices: Set[Int] = { if (!matches) fail("affine lane ownership lost its exact write union"); claim.lanes }
    def authorizesBridge(bridge: TypedLoopVecBridges.Bridge): Boolean = matches &&
      claim.lanes.exists(i => claim.selection.vector.vec(i).asInstanceOf[Data].flatten
        .zip(claim.selection.result.flatten).exists { case (value,alias) =>
          (bridge.value eq value) && (bridge.alias eq alias)
        })
    private[TypedLoopAffineWrites] def retainBridges(component: Component): Unit = {
      if (!matches) fail("affine bridge lost its exact write union")
      val selection = claim.selection
      if (selection.registerStorage.nonEmpty) return
      val pairs = claim.lanes.toVector.sorted.flatMap(i =>
        selection.vector.vec(i).asInstanceOf[Data].flatten.zip(selection.result.flatten))
      if (pairs.exists { case (value,_) => value.isReg || value.isInput ||
          (value.component ne component) || !value.dlcIsEmpty })
        fail("affine combinational carriers must have no competing native drivers")
      pairs.foreach { case (value,alias) =>
        value.removeTag(allowFloating)
        val assignment = DataAssignmentStatement(value,alias)
        component.dslBody.append(assignment)
        selection.coverageBridges :+= TypedLoopVecBridges.Bridge(value,alias,assignment)
        claim.block.assignments :+= assignment
        claim.block.statements :+= assignment
      }
    }
  }

  private final class Group(component: Component, loop: ParameterizedStructure.StructuralFor,
      vector: Vec[_], claims: Vector[Claim]) {
    private val shape = ParameterizedVec.shapeOf(vector).get
    private val values = vector.vec.toVector.map(_.asInstanceOf[Data].flatten.toVector)
    def matches: Boolean = {
      def registered(regions: Vector[ParameterizedStructure.StructuralRegion]): Boolean =
        regions.exists(r => (r eq loop) || r.blocks.exists(b => registered(b.regions)))
      def allBlocks(regions: Vector[ParameterizedStructure.StructuralRegion]): Vector[ParameterizedStructuralBlock] =
        regions.flatMap(_.blocks).flatMap(b => Vector(b) ++ allBlocks(b.regions))
      val writers = allBlocks(ParameterizedStructure.regionsOf(component)).flatMap(b =>
        b.vecIndices.filter(s => (s.vector eq vector) &&
          b.assignments.exists(a => s.result.flatten.exists(_ eq a.finalTarget))).map(b -> _))
      registered(ParameterizedStructure.regionsOf(component)) && vector.vec.size == values.size &&
        writers.size == claims.size && writers.forall { case (block,selection) =>
          claims.exists(c => (c.block eq block) && (c.selection eq selection))
        } &&
        ParameterizedVec.shapeOf(vector).exists(_ eq shape) &&
        vector.vec.toVector.map(_.asInstanceOf[Data].flatten.toVector).zip(values).forall {
          case (now, before) => now.size == before.size && now.zip(before).forall { case (a,b) => a eq b }
        } && claims.forall { claim =>
          val selection = claim.selection
          val aliases = selection.result.flatten
          claim.block.vecIndices.exists(_ eq selection) &&
            selection.finiteIndexToken.exists(t => loop.finiteIndexToken.exists(_ eq t) &&
              selection.affineRead.exists(e => e.previous.isEmpty &&
                e.matches(vector, selection.index, t, Some(loop.count)) &&
                e.guarded.forall(_.owns(claim.block)))) &&
            claim.writes.forall(w => claim.block.assignments.exists(_ eq w) &&
              aliases.exists(_ eq w.target)) &&
            claim.block.assignments.filter(a => aliases.exists(_ eq a.finalTarget)).toSet == claim.writes.toSet
        }
    }
  }

  private def fail(message: String): Nothing = ParameterizedVerilogException.fail(
    "SPINAL-TYPED-LOOP-AFFINE-WRITE-COVERAGE", message, None)

  private def contains(root: Expression, values: Vector[BaseType]): Boolean = {
    val seen = new java.util.IdentityHashMap[Expression, java.lang.Boolean]()
    def visit(value: Expression): Boolean = value != null &&
      seen.put(value, java.lang.Boolean.TRUE) == null && {
        if (values.exists(_ eq value)) true
        else { var found = false; value.foreachExpression(e => if (visit(e)) found = true); found }
      }
    visit(root)
  }

  def retainBridges(component: Component, loop: ParameterizedStructure.StructuralFor): Unit = {
    def visit(block: ParameterizedStructuralBlock): Unit = {
      block.vecIndices.flatMap(_.affineWrite).foreach(_.retainBridges(component))
      block.regions.collect { case branch: ParameterizedStructure.StructuralIf => branch }
        .flatMap(_.blocks).foreach(visit)
    }
    visit(loop.body)
  }

  def retain(component: Component, loop: ParameterizedStructure.StructuralFor): Unit = {
    def blocks(block: ParameterizedStructuralBlock): Vector[ParameterizedStructuralBlock] =
      Vector(block) ++ block.regions.collect { case b: ParameterizedStructure.StructuralIf => b }
        .flatMap(_.blocks).flatMap(blocks)
    val owned = blocks(loop.body)
    val selections = owned.flatMap(b => b.vecIndices.map(b -> _))
    val writes = selections.filter { case (b,s) =>
      s.affineRead.nonEmpty && b.assignments.exists(a => s.result.flatten.exists(_ eq a.finalTarget))
    }
    if (writes.exists(_._2.affineRead.exists(_.previous.nonEmpty)))
      ParameterizedVerilogException.fail("SPINAL-ELAB-FINITE-AFFINE-WRITE-UNSUPPORTED",
        "previous-iteration capabilities authorize reads only", None)
    val vectors = writes.map(_._2.vector).foldLeft(Vector.empty[Vec[_]]) {
      case (known,v) => if (known.exists(_ eq v)) known else known :+ v
    }
    vectors.foreach { vector =>
      val shape = ParameterizedVec.shapeOf(vector).getOrElse(fail("affine write requires retained Vec geometry"))
      val relevant = writes.filter(_._2.vector eq vector)
      val allLeaves = vector.vec.toVector.flatMap(_.asInstanceOf[Data].flatten)
      val aliases = selections.filter(_._2.vector eq vector).flatMap(_._2.result.flatten)
      if (allLeaves.exists(v => v.component != component || v.isInput) ||
          owned.flatMap(_.assignments).exists(a => contains(a.source, allLeaves ++ aliases)))
        fail("affine writes require local destinations without same-loop target feedback")
      if (selections.exists { case (b,s) => (s.vector eq vector) && s.affineRead.isEmpty &&
          b.assignments.exists(a => s.result.flatten.exists(_ eq a.finalTarget)) })
        fail("mixed affine and other indexed writers require a separate union proof")
      val depth = shape.depth.exactDomain.getOrElse(fail("affine write depth requires an exact parameter domain"))
      val claims = relevant.map { case (block,selection) =>
        val read = selection.affineRead.get
        if (read.previous.nonEmpty) fail("previous-iteration capabilities remain read-only")
        val count = read.count.exactDomain.getOrElse(fail("affine write count requires an exact parameter domain"))
        if (count.root ne depth.root) fail("affine write count and depth must share their exact root")
        val active = selection.result.flatten.toVector.flatMap { alias =>
          val assignments = block.assignments.filter(_.finalTarget eq alias)
          if (assignments.size != 1 || (assignments.head.target ne alias))
            fail("affine writes require one exact whole-leaf assignment per selector")
          assignments
        }
        val rows = read.guarded.map(_.permittedRows).getOrElse(read.admitted.flatMap { root =>
          val n = count.evaluate(root).getOrElse(fail("missing affine count evidence"))
          (BigInt(0) until n).map(root -> _)
        })
        if (rows.isEmpty || rows.exists { case (root,i) =>
            !depth.evaluate(root).exists(n => read.coefficient*i+read.offset < n) })
          fail("affine writer has an empty or out-of-domain index set")
        val lanes = rows.map { case (_,i) => (read.coefficient*i+read.offset).toInt }
        Claim(selection,block,active,rows,lanes)
      }
      val roots = relevant.head._2.affineRead.get.count.exactDomain.get.evidenceValues
      roots.foreach { root =>
        val n = depth.evaluate(root).getOrElse(fail("missing affine depth evidence"))
        val lanes = claims.flatMap { claim =>
          val read = claim.selection.affineRead.get
          claim.rows.collect { case (`root`,i) => read.coefficient*i+read.offset }
        }
        if (lanes.size != n || lanes.toSet != (BigInt(0) until n).toSet)
          fail("affine write union has missing lanes or overlapping writers in its legal domain")
      }
      if (claims.flatMap(_.lanes).distinct.size != shape.carrierCapacity ||
          claims.combinations(2).exists(pair => (pair(0).lanes intersect pair(1).lanes).nonEmpty))
        fail("native affine carrier ownership requires disjoint complete lane sets")
      val group = new Group(component,loop,vector,claims)
      claims.foreach(c => c.selection.affineWrite = Some(new Evidence(group,c)))
      if (!group.matches) fail("affine write union lost its exact capture identities")
    }
  }
}
