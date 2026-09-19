package spinal.core

/** Adversarial statement-lineage fixtures kept in `spinal.core` so tests can
  * remove the exact retained native statements without exposing that internal
  * evidence API to normal MorphHDL users.
  */
object TypedParameterizedVecLineageFixture {
  final class RemovedWholeAssignment(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("RemovedWholeAssignmentVec")
    val first = in(Vec(UInt(width bits), depth)).setName("first")
    val second = in(Vec(UInt(width bits), depth)).setName("second")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")
    val target = Vec(UInt(width bits), depth)
      .setName("target")
      .dontSimplifyIt()

    target.allowOverride()
    target := first
    val firstOperation = ParameterizedVec
      .operationsOf(target)
      .collect { case value: ParameterizedVecWholeAssignment => value }
      .last
    require(
      firstOperation.assignments.nonEmpty,
      "stale-lineage fixture retained no first whole-Vec assignment evidence"
    )
    firstOperation.assignments.foreach(_.removeStatement())

    target := second
    vecOut := target
  }

  /** Remove the exact assignments captured by Vec.autoConnect, then recreate
    * the same emitted target/source relation through a distinct aggregate
    * assignment. Matching names and text must not revive stale auto-connect
    * evidence.
    */
  final class RemovedAutoConnectAssignment(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("RemovedAutoConnectAssignmentVec")
    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")

    vecIn <> vecOut
    val operation = ParameterizedVec
      .operationsOf(vecIn)
      .collect { case value: ParameterizedVecAutoConnect => value }
      .last
    require(
      operation.assignments.nonEmpty,
      "stale auto-connect fixture retained no native assignment evidence"
    )
    operation.assignments.foreach(_.removeStatement())

    // This replacement emits the same aggregate relationship after lowering,
    // but its assignment identities are deliberately different.
    vecOut := vecIn
  }
}
