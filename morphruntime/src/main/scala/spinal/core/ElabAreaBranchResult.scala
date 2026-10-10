package spinal.core

/** Supported result kinds of a retained Area index conditional. */
sealed trait ElabAreaBranchResult[T] {
  def apply(index: ElabFiniteIndex, value: Int, role: String)(yes: => T)(no: => T): T
}
object ElabAreaBranchResult {
  implicit object Statement extends ElabAreaBranchResult[Unit] {
    def apply(index: ElabFiniteIndex, value: Int, role: String)(yes: => Unit)(no: => Unit): Unit =
      index.whenEqual(value, role)(yes)(no)
  }
  implicit object BooleanSignal extends ElabAreaBranchResult[Bool] {
    def apply(index: ElabFiniteIndex, value: Int, role: String)(yes: => Bool)(no: => Bool): Bool =
      index.selectBool(value, role)(yes)(no)
  }
}
