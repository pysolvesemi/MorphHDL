package spinal.core.internals

import scala.collection.mutable.ArrayBuffer

import spinal.core._

/** One typed BlackBox generic retained beside the native concrete witness.
  *
  * Native Verilog/VHDL emitters continue to see only Int/Boolean values.
  * MorphHDL's external Verilog-2001 publication path consumes this immutable
  * expression record by exact BlackBox object identity.
  */
private[spinal] sealed trait ParameterizedBlackBoxGeneric {
  def name: String
  def parameters: Vector[ElaborationIntegerParameter]
  def sourceLocation: Option[String]
}

private[spinal] final case class ParameterizedBlackBoxIntegerGeneric(
    name: String,
    expression: ElaborationIntegerExpression,
    witness: Int
) extends ParameterizedBlackBoxGeneric {
  override def parameters: Vector[ElaborationIntegerParameter] =
    expression.parameters
  override def sourceLocation: Option[String] = expression.sourceLocation
}

private[spinal] final case class ParameterizedBlackBoxBooleanGeneric(
    name: String,
    expression: ElaborationBooleanExpression,
    witness: Boolean
) extends ParameterizedBlackBoxGeneric {
  override def parameters: Vector[ElaborationIntegerParameter] =
    expression.parameters
  override def sourceLocation: Option[String] = expression.sourceLocation
}

private[spinal] final case class ParameterizedBlackBoxPortWidth(
    blackBox: BlackBox,
    port: BaseType,
    expression: ElaborationIntegerExpression
)

/** Exact-identity storage for typed BlackBox generic actuals.
  *
  * Records live in the owning BlackBox user cache. There is no global runtime
  * registry, source-position lookup, component-name recognizer or witness-value
  * reconstruction path.
  */
private[spinal] object ParameterizedBlackBoxGenericRegistry {
  private object CacheKey

  private def cache(
      blackBox: BlackBox
  ): ArrayBuffer[ParameterizedBlackBoxGeneric] =
    blackBox.userCache
      .getOrElseUpdate(
        CacheKey,
        ArrayBuffer.empty[ParameterizedBlackBoxGeneric]
      )
      .asInstanceOf[ArrayBuffer[ParameterizedBlackBoxGeneric]]

  def retain(
      blackBox: BlackBox,
      name: String,
      value: ElabInt
  ): Int = {
    requireOwnerAndName(blackBox, name)
    if (value == null)
      throw new IllegalArgumentException("typed BlackBox integer generic must not be null")

    val role = s"BlackBox integer generic '$name'"
    val expression = value.authoritativeProjectedExpression(
      role,
      failureCode =
        "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-INTEGER-GENERIC-DOMAIN-INVALID",
      requireProjectedExactExtrema = false
    )
    if (
      expression.default < BigInt(Int.MinValue) ||
      expression.default > BigInt(Int.MaxValue)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-INTEGER-GENERIC-WITNESS-OUT-OF-RANGE",
        s"$role witness ${expression.default} does not fit the native BlackBox Int generic carrier",
        expression.sourceLocation
      )
    }
    if (expression.generateIndex.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-GENERATE-DEPENDENT",
        s"$role expression '${expression.verilog}' depends on a generate index",
        expression.sourceLocation
      )
    }

    val witness = expression.default.toInt
    cache(blackBox) += ParameterizedBlackBoxIntegerGeneric(
      name,
      expression,
      witness
    )
    witness
  }

  def retain(
      blackBox: BlackBox,
      name: String,
      value: ElabBool
  ): Boolean = {
    requireOwnerAndName(blackBox, name)
    if (value == null)
      throw new IllegalArgumentException("typed BlackBox Boolean generic must not be null")

    val role = s"BlackBox Boolean generic '$name'"
    val expression = value.projectedExpression(role)
    val witness = expression.default
    cache(blackBox) += ParameterizedBlackBoxBooleanGeneric(
      name,
      expression,
      witness
    )
    witness
  }

  def recordsOf(
      blackBox: BlackBox
  ): Vector[ParameterizedBlackBoxGeneric] =
    blackBox.userCache
      .get(CacheKey)
      .map(
        _.asInstanceOf[ArrayBuffer[ParameterizedBlackBoxGeneric]].toVector
      )
      .getOrElse(Vector.empty)

  def blackBoxesOf(component: Component): Vector[BlackBox] =
    component.children.toVector.collect {
      case blackBox: BlackBox if blackBox.isBlackBox => blackBox
    }

  def recordsOf(
      component: Component
  ): Vector[(BlackBox, ParameterizedBlackBoxGeneric)] =
    blackBoxesOf(component).flatMap { blackBox =>
      recordsOf(blackBox).map(blackBox -> _)
    }

  def portWidthsOf(
      component: Component
  ): Vector[ParameterizedBlackBoxPortWidth] =
    blackBoxesOf(component).flatMap { blackBox =>
      blackBox.getOrdredNodeIo.toVector
        .filterNot(_.isSuffix)
        .flatMap { port =>
          ParameterizedWidth
            .expressionOf(port)
            .filter(_.parameters.nonEmpty)
            .map(
              ParameterizedBlackBoxPortWidth(
                blackBox,
                port,
                _
              )
            )
        }
    }

  def integerExpressionsOf(
      component: Component
  ): Vector[ElaborationIntegerExpression] =
    recordsOf(component).collect {
      case (_, value: ParameterizedBlackBoxIntegerGeneric) =>
        value.expression
    } ++ portWidthsOf(component).map(_.expression)

  def booleanExpressionsOf(
      component: Component
  ): Vector[ElaborationBooleanExpression] =
    recordsOf(component).collect {
      case (_, value: ParameterizedBlackBoxBooleanGeneric) =>
        value.expression
    }

  def parametersOf(
      component: Component
  ): Vector[ElaborationIntegerParameter] =
    (
      integerExpressionsOf(component).flatMap(_.parameters) ++
        booleanExpressionsOf(component).flatMap(_.parameters)
    ).distinct.sortBy(_.name)

  def hasSymbolicBindings(component: Component): Boolean =
    recordsOf(component).exists(_._2.parameters.nonEmpty) ||
      portWidthsOf(component).nonEmpty

  private def requireOwnerAndName(
      blackBox: BlackBox,
      name: String
  ): Unit = {
    if (blackBox == null)
      throw new IllegalArgumentException("typed BlackBox generic owner must not be null")
    if (name == null || name.trim.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-NAME-INVALID",
        "typed BlackBox generic name must not be null or empty",
        None
      )
    }
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
