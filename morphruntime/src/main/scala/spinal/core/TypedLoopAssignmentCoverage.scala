package spinal.core

import spinal.core.internals._

/** Definite assignment for a retained loop, without changing its witness RTL
  * target or exempting a declaration from native latch/overlap checks.
  */
private[core] object TypedLoopAssignmentCoverage {
  def retain(component: Component, loop: ParameterizedStructure.StructuralFor,
      countProof: Option[ElabInt]): Unit = {
    val count = loop.count
    if (count.minimum < 0) return
    loop.body.slices.foreach { slice =>
      if (slice.offset.default == 0 && slice.offset.minimum == 0 &&
          slice.offset.generateIndex.contains(loop.indexName) &&
          slice.width.parameters.isEmpty && slice.width.minimum == slice.width.maximum &&
          ParameterizedProcess.isContiguous(slice, loop.indexName)) {
        val writes = loop.body.assignments.filter(ParameterizedProcess.matchesTargetSlice(_, slice))
        writes.foreach { assignment => assignment.target match {
          case native: RangedAssignmentFixed =>
            val total = countProof.getOrElse(ElabInt.fromExpression(count)) * ElabInt.fromExpression(slice.width)
            val targetWidth = ParameterizedWidth.expressionOf(slice.source)
              .getOrElse(ElabInt.literal(slice.source.getBitsWidth).expression)
            // A full correlated range covers the native target width at its
            // witness. Otherwise only the prefix present at EVERY admitted
            // count is definite: a full witness must not hide a partial override.
            val full = ElabBool.projectedTruth(total.elabEq(ElabInt.fromExpression(targetWidth))) == ElabBool.AlwaysTrue
            val extent = if (full) BigInt(slice.source.getBitsWidth) else total.expression.minimum
            val possibleExtent = if (full) BigInt(slice.source.getBitsWidth)
              else total.expression.maximum.min(BigInt(slice.source.getBitsWidth))
            if (extent >= 0 && extent <= slice.source.getBitsWidth && extent.isValidInt) {
              val scope = assignment.parentScope
              val source = native.out
              val low = native.lo
              val high = native.hi
              val covered = new RangedAssignmentFixed {
                out = source
                lo = low
                hi = high
                private def coverage(size: BigInt): AssignedRange = {
                  def registered(regions: Vector[ParameterizedStructure.StructuralRegion]): Boolean =
                    regions.exists(region => (region eq loop) || region.blocks.exists(block => registered(block.regions)))
                  if ((assignment.target ne this) || (out ne source) || lo != low || hi != high ||
                      (assignment.parentScope ne scope) || !loop.body.assignments.exists(_ eq assignment) ||
                      !loop.body.slices.exists(_ eq slice) || !registered(ParameterizedStructure.regionsOf(component)))
                    ParameterizedVerilogException.fail("SPINAL-TYPED-LOOP-COVERAGE-IDENTITY-MISMATCH",
                      "typed loop coverage lost its exact registered assignment, scope or slice", slice.sourceLocation)
                  if (size == 0) AssignedRange() else AssignedRange(size.toInt - 1, 0)
                }
                override def getMinAssignedBits: AssignedRange = coverage(extent)
                override def getMaxAssignedBits: AssignedRange = coverage(possibleExtent)
              }
              assignment.target = covered
            }
          case _ =>
        }}
      }
    }
  }
}
