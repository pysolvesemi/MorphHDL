package spinal.core

/** External retained-shape adapter for ordinary native library payloads. */
object ExternalParameterizedHardTypeShape {
  def attach[T <: Data](data: T, dataType: HardType[_]): T = {
    if (data == null)
      throw new IllegalArgumentException("native payload must not be null")
    if (dataType == null)
      throw new IllegalArgumentException("native payload HardType must not be null")

    ExternalParameterizedHardTypeRegistry.expressionsOf(dataType).foreach { expressions =>
      val leaves = data.flatten.toVector
      if (leaves.size != expressions.size) {
        throw new IllegalArgumentException(
          s"native payload leaf count ${leaves.size} does not match retained HardType leaf count ${expressions.size}"
        )
      }
      leaves.zip(expressions).zipWithIndex.foreach {
        case ((leaf: BitVector, expression), index) if expression.parameters.nonEmpty =>
          if (expression.default != BigInt(leaf.getBitsWidth)) {
            throw new IllegalArgumentException(
              s"native payload leaf $index concrete width ${leaf.getBitsWidth} does not match retained default ${expression.default}"
            )
          }
          if (!expression.default.isValidInt) {
            throw new IllegalArgumentException(
              s"native payload leaf $index retained default ${expression.default} is outside Int range"
            )
          }
          val direct = expression.parameters match {
            case Vector(parameter) if expression.verilog == parameter.name => Some(parameter)
            case _                                                         => None
          }
          ParameterizedWidth.attach(
            leaf,
            ParameterizedBitCount(
              value = expression.default.toInt,
              parameter = direct,
              sourceLocation = expression.sourceLocation,
              expression = Some(expression)
            )
          )
        case ((_: BitVector, _), _) =>
        case ((_, expression), index) if expression.parameters.nonEmpty =>
          throw new IllegalArgumentException(
            s"native payload leaf $index carries symbolic packed geometry but is not a BitVector"
          )
        case _ =>
      }
    }
    data
  }
}
