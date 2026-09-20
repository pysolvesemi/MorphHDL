package spinal.core

/** Scoped observation of the existing native clone and Vec zero algorithms.
  * A backend must authenticate each exact receiver; presence of a scope alone
  * never permits symbolic zero construction. Ordinary callers keep the guard.
  */
private[spinal] object NativeVecZeroConstruction {
  trait Backend {
    def cloned(source: Data, result: Data): Unit
    def zero[T <: Data](receiver: Vec[T], native: () => Vec[T]): Vec[T]
  }

  private val backend = new ScopeProperty[Backend] {
    override def default: Backend = null
  }

  def withBackend[A](value: Backend)(body: => A): A = {
    require(value != null, "SPINAL-NATIVE-VEC-ZERO-BACKEND-NULL")
    val previous = backend.set(value)
    try body finally previous.restore()
  }

  private[core] def cloned(source: Data, result: Data): Unit = {
    val selected = backend.get
    if (selected != null) selected.cloned(source, result)
  }

  private[core] def construct[T <: Data](receiver: Vec[T])(native: => Vec[T]): Vec[T] = {
    val selected = backend.get
    if (ParameterizedVec.shapeOf(receiver).isEmpty) native
    else if (selected == null) {
      ParameterizedVec.rejectUnsupported(receiver, "Vec zero construction")
      native
    } else selected.zero(receiver, () => native)
  }
}
