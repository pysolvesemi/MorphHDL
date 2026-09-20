package morphhdl

import spinal.core.ElaborationIntegerParameter

import morphhdl.paramrtl.IntegerParameter

/**
  * Result of parameterized Verilog generation from one ordinary SpinalHDL
  * Component factory.
  *
  * The first four fields deliberately retain the historical serialized form.
  * The additive native field is transient, so deserialized reports retain the
  * compatibility view but expose no live elaboration identity.
  */
@SerialVersionUID(MorphSingleSourceVerilogReportProductNames.reportSerialVersionUID)
final class MorphSingleSourceVerilogReport private (
    val toplevelName: String,
    val generatedSourcesPaths: Vector[String],
    @deprecated(
      "Use elaborationParameters or MorphCanonicalIrReport.handoff; ParamRTL is compatibility-only",
      "Increment 58"
    )
    val parameters: Vector[IntegerParameter],
    val inheritedValidationPhaseIds: Vector[String],
    @transient private val serializedElaborationParameters: Vector[ElaborationIntegerParameter]
) extends Product
    with Serializable
    with MorphSingleSourceVerilogReportProductNames {
  @deprecated(
    "Construct reports from native elaboration parameters; ParamRTL is compatibility-only",
    "Increment 58"
  )
  def this(
      toplevelName: String,
      generatedSourcesPaths: Vector[String],
      parameters: Vector[IntegerParameter],
      inheritedValidationPhaseIds: Vector[String]
  ) = this(
    toplevelName,
    generatedSourcesPaths,
    parameters,
    inheritedValidationPhaseIds,
    Vector.empty
  )

  def elaborationParameters: Vector[ElaborationIntegerParameter] =
    if (serializedElaborationParameters eq null) Vector.empty
    else serializedElaborationParameters

  private[morphhdl] def compatibilityParameters: Vector[IntegerParameter] =
    parameters

  @deprecated(
    "Copying the ParamRTL report view is compatibility-only",
    "Increment 58"
  )
  def copy(
      toplevelName: String = this.toplevelName,
      generatedSourcesPaths: Vector[String] = this.generatedSourcesPaths,
      parameters: Vector[IntegerParameter] = this.parameters,
      inheritedValidationPhaseIds: Vector[String] = this.inheritedValidationPhaseIds
  ): MorphSingleSourceVerilogReport = {
    val retainedTyped =
      if (
        parameters.asInstanceOf[AnyRef] eq
          this.parameters.asInstanceOf[AnyRef]
      ) elaborationParameters
      else Vector.empty
    new MorphSingleSourceVerilogReport(
      toplevelName,
      generatedSourcesPaths,
      parameters,
      inheritedValidationPhaseIds,
      retainedTyped
    )
  }

  override def productPrefix: String = "MorphSingleSourceVerilogReport"
  override def productArity: Int = 4
  override def productElement(index: Int): Any = index match {
    case 0 => toplevelName
    case 1 => generatedSourcesPaths
    case 2 => parameters
    case 3 => inheritedValidationPhaseIds
    case _ => throw new IndexOutOfBoundsException(index.toString)
  }
  override def canEqual(other: Any): Boolean =
    other.isInstanceOf[MorphSingleSourceVerilogReport]

  override def equals(other: Any): Boolean = other match {
    case value: MorphSingleSourceVerilogReport =>
      value.canEqual(this) &&
      toplevelName == value.toplevelName &&
      generatedSourcesPaths == value.generatedSourcesPaths &&
      parameters == value.parameters &&
      inheritedValidationPhaseIds == value.inheritedValidationPhaseIds
    case _ => false
  }

  override def hashCode(): Int =
    scala.util.hashing.MurmurHash3.productHash(this)

  override def toString: String =
    scala.runtime.ScalaRunTime._toString(this)
}

@SerialVersionUID(MorphSingleSourceVerilogReportProductNames.companionSerialVersionUID)
object MorphSingleSourceVerilogReport
    extends scala.runtime.AbstractFunction4[
      String,
      Vector[String],
      Vector[IntegerParameter],
      Vector[String],
      MorphSingleSourceVerilogReport
    ]
    with Serializable {
  override final def toString: String = "MorphSingleSourceVerilogReport"

  private[morphhdl] def fromTyped(
      toplevelName: String,
      generatedSourcesPaths: Vector[String],
      elaborationParameters: Vector[ElaborationIntegerParameter],
      inheritedValidationPhaseIds: Vector[String]
  ): MorphSingleSourceVerilogReport =
    new MorphSingleSourceVerilogReport(
      toplevelName,
      generatedSourcesPaths,
      MorphSingleSourceVerilogReportCompatibility.toLegacy(elaborationParameters),
      inheritedValidationPhaseIds,
      elaborationParameters
    )

  @deprecated(
    "Construct reports from native elaboration parameters; ParamRTL is compatibility-only",
    "Increment 58"
  )
  override def apply(
      toplevelName: String,
      generatedSourcesPaths: Vector[String],
      parameters: Vector[IntegerParameter],
      inheritedValidationPhaseIds: Vector[String]
  ): MorphSingleSourceVerilogReport =
    new MorphSingleSourceVerilogReport(
      toplevelName,
      generatedSourcesPaths,
      parameters,
      inheritedValidationPhaseIds,
      serializedElaborationParameters = Vector.empty
    )

  @deprecated(
    "Pattern matching the ParamRTL compatibility view is deprecated",
    "Increment 58"
  )
  def unapply(
      report: MorphSingleSourceVerilogReport
  ): Option[(String, Vector[String], Vector[IntegerParameter], Vector[String])] =
    Option(report).map(value => (
      value.toplevelName,
      value.generatedSourcesPaths,
      value.parameters,
      value.inheritedValidationPhaseIds
    ))
}

private[morphhdl] object MorphSingleSourceVerilogReportCompatibility {
  import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}

  def toLegacy(
      parameters: Vector[ElaborationIntegerParameter]
  ): Vector[IntegerParameter] =
    parameters.map { parameter =>
      IntegerParameter(
        parameter.name,
        parameter.default,
        Vector(
          MinInclusive(parameter.minimum),
          MaxInclusive(parameter.maximum)
        )
      )
    }
}
