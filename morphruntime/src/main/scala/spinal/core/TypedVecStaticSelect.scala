package spinal.core
import spinal.core.internals._

/** A module-parameter index is a constant selector, not a runtime mux input. */
private[spinal] object TypedVecStaticSelect {
  private object Key
  final case class Entry(vector: Vec[UInt], index: ElabInt, source: UInt,
      result: UInt, assignment: DataAssignmentStatement)
  private[core] def entries(component: Component) = component.userCache.getOrElseUpdate(Key,
    scala.collection.mutable.ArrayBuffer.empty[Entry]).asInstanceOf[scala.collection.mutable.ArrayBuffer[Entry]]
  def apply(vector: Vec[UInt], index: ElabInt): UInt = {
    val depth = ParameterizedVec.shapeOf(vector).get.depth
    require(index.expression.parameters.forall(p => depth.parameters.exists(_ eq p)) &&
      index.expression.generateIndex.isEmpty && index.minimum >= 0 &&
      ElabBool.projectedTruth(index < ElabInt.fromExpression(depth)) == ElabBool.AlwaysTrue,
      "constant Vec selection must be in range over the complete domain")
    if(index.isConcrete) return vector(index.witness)
    NativeLocalParameters.bind(vector.component, index, "selected_stage_index")
    val source = vector.vec(index.witness)
    vector.vec.foreach(_.flatten.foreach { value => value.setAsVital(); value.dontSimplifyIt() })
    val result = ParameterizedWidth.cloneOf(source)
    result.setWeakName("selected_stage")
    result.dontSimplifyIt()
    result.noBackendCombMerge()
    result := source
    entries(vector.component) += Entry(vector,index,source,result,result.head.asInstanceOf[DataAssignmentStatement])
    result
  }
  def of(component: Component, assignment: AssignmentStatement): Option[Entry] =
    entries(component).find(_.assignment eq assignment).map { entry =>
      require((assignment.source eq entry.source) && (assignment.target eq entry.result) &&
        (entry.vector.vec(entry.index.witness) eq entry.source) &&
        entry.result.hasOnlyOneStatement && (entry.result.head eq assignment),
        "constant Vec selector lost its exact native assignment")
      entry
    }
}
