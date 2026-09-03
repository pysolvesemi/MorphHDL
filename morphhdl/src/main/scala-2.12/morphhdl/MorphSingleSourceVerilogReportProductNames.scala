package morphhdl

/** Scala 2.12 has no Product.productElementName hook. */
private[morphhdl] trait MorphSingleSourceVerilogReportProductNames { self: Product => }

private[morphhdl] object MorphSingleSourceVerilogReportProductNames {
  final val reportSerialVersionUID = -4626025765257093795L
  final val companionSerialVersionUID = -6620878606554409185L
}
