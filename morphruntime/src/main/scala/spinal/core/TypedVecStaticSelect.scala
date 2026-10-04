package spinal.core
import spinal.core.internals._

/** A module-parameter index is a constant selector, not a runtime mux input. */
private[spinal] object TypedVecStaticSelect {
  private object Key
  final class Entry private[TypedVecStaticSelect](val vector: Vec[_ <: BaseType], val index: ElabInt, val source: BaseType,
      val result: BaseType, val assignment: DataAssignmentStatement,
      private[TypedVecStaticSelect] val selectedLane: Int,
      private[TypedVecStaticSelect] val shape: ParameterizedVecShape)
  private[core] def entries(component: Component) = component.userCache.getOrElseUpdate(Key,
    scala.collection.mutable.ArrayBuffer.empty[Entry]).asInstanceOf[scala.collection.mutable.ArrayBuffer[Entry]]
  def apply[T <: BaseType](vector: Vec[T], index: ElabInt): T = {
    require(vector != null && index != null && vector.vec.nonEmpty,
      "constant Vec selection requires a nonempty vector and a typed index")
    require(vector.component eq Component.current,
      "constant Vec selection must be authored in the vector's declaring component")
    require(vector.vec.forall(value => value.isInstanceOf[Bool] || value.isInstanceOf[Bits] ||
      value.isInstanceOf[UInt] || value.isInstanceOf[SInt]),
      "constant Vec selection supports Bool, Bits, UInt and SInt leaves")
    ElabInt.validateExpression(index.expression, "constant Vec selector")
    ParameterizedVec.retainConstantLoopOperand(vector)
    val shape = ParameterizedVec.shapeOf(vector).get
    val depth = shape.depth
    require(index.expression.generateIndex.isEmpty && index.minimum >= 0 &&
      ElabBool.projectedTruth(index < ElabInt.fromExpression(depth)) == ElabBool.AlwaysTrue,
      "constant Vec selection must be in range over the complete domain")
    if(index.isConcrete) return vector(index.witness)
    NativeLocalParameters.bind(vector.component, index, "selected_stage_index")
    val selectedLane = index.witness
    val source = vector.vec(selectedLane)
    vector.vec.foreach(_.flatten.foreach { value => value.setAsVital(); value.dontSimplifyIt() })
    val result = ParameterizedWidth.cloneOf(source)
    result.setWeakName("selected_stage")
    result.dontSimplifyIt()
    result.noBackendCombMerge()
    result := source
    entries(vector.component) += new Entry(vector,index,source,result,
      result.head.asInstanceOf[DataAssignmentStatement],selectedLane,shape)
    result
  }
  def of(component: Component, assignment: AssignmentStatement): Option[Entry] =
    entries(component).find(_.assignment eq assignment).map { entry =>
      require((assignment.source eq entry.source) && (assignment.target eq entry.result) &&
        ParameterizedVec.shapeOf(entry.vector).exists(_ eq entry.shape) &&
        (entry.vector.vec(entry.selectedLane) eq entry.source) &&
        entry.result.hasOnlyOneStatement && (entry.result.head eq assignment),
        "constant Vec selector lost its exact native assignment")
      entry
    }
  // The complete bound proof was made in the declaration's active domain.
  // Publication must authenticate that exact evidence, not project its witness
  // again after leaving the branch that owns the array.
  def authorizes(vector: Vec[_], index: ElabInt): Boolean =
    entries(vector.component).exists(entry => (entry.vector eq vector) && (entry.index eq index) &&
      of(vector.component, entry.assignment).exists(_ eq entry))
}
