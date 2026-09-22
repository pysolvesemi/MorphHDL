package spinal.core

/** Exercise the same native scalar-formal capability used by typed libraries. */
object CdcPublicationFormalControl {
  def bind[T <: Component](actual: ElabInt)(build: ElabInt => T): T =
    ElabFormalComponent.parameter(actual, "COUNT_BITS", BigInt(4), BigInt(18))(build)
}
