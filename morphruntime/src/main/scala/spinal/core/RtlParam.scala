package spinal.core

/** Lightweight declarations for bounded HDL parameters.
  *
  * Each call declares a formal in the current component. An ElabInt/ElabBool
  * argument is the instance actual, not a request to reuse the parent's formal.
  * Scala values select defaults; they never replace the declared legal domain.
  * Use an explicit ElabInt/ElabBool annotation for natural Scala control flow.
  */
object RtlParam {
  private object DeclarationKey
  private type Declarations = scala.collection.mutable.ArrayBuffer[(ElabInt, ElabInt)]
  private[core] def authenticates(owner: Component, formal: ElabInt, actual: ElabInt): Boolean =
    owner.userCache.get(DeclarationKey).exists(_.asInstanceOf[Declarations].exists {
      case (issued, supplied) => (issued eq formal) && (supplied eq actual)
    })

  def int(name: String, default: Int, minimum: BigInt, maximum: BigInt): ElabInt =
    int(name, ElabInt.literal(default), minimum, maximum)

  def int(name: String, actual: ElabInt, minimum: BigInt, maximum: BigInt): ElabInt = {
    val formal = ElabFormalParameter(actual, name, minimum, maximum)
    val entries = Component.current.userCache.getOrElseUpdate(DeclarationKey,
      scala.collection.mutable.ArrayBuffer.empty[(ElabInt, ElabInt)]).asInstanceOf[Declarations]
    entries += ((formal, actual))
    formal
  }

  /** Boolean parameters are represented by a bounded 0/1 HDL integer. */
  def bool(name: String, default: Boolean): ElabBool =
    bool(name, ElabBool.literal(default))

  def bool(name: String, actual: ElabBool): ElabBool = {
    require(actual != null, "Boolean parameter actual must not be null")
    ElabFormalParameter(actual.toElabInt, name, 0, 1).elabEq(1)
  }
}
