package morphhdl.frontend

import morphhdl.paramrtl.IntExpr.GenerateIndexRef

final class GenIndex private[frontend] (
    private[frontend] val witness: BigInt,
    private[frontend] val token: ScopeToken,
    private[frontend] val origin: SourceOrigin
) extends scala.math.ScalaNumber {
  def *(that: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt = {
    val resultOrigin = SourceOrigin.capture
    FrontendSession.requireActiveScope(token, "generate-index multiplication", resultOrigin)
    that.requireUsable("generate-index multiplication")
    HdlInt.fromGenerateIndex(
      witness * that.witness,
      morphhdl.paramrtl.IntExpr.Multiply(GenerateIndexRef(token.indexName), that.expression),
      token,
      that.parameters,
      that.localParameters,
      resultOrigin
    )
  }

  override def equals(that: Any): Boolean = {
    FrontendSession.requireActiveScope(token, "symbolic comparison", origin)
    FrontendException.failAt(
      "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED",
      s"generate index '${token.indexName}' cannot be compared with ${GenIndex.describe(that)}",
      origin
    )
  }

  override def hashCode: Int = {
    FrontendSession.requireActiveScope(token, "symbolic hashing", origin)
    FrontendException.failAt(
      "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED",
      s"generate index '${token.indexName}' cannot be hashed by Scala",
      origin
    )
  }

  override def intValue(): Int = conversionFailure("Int")
  override def longValue(): Long = conversionFailure("Long")
  override def floatValue(): Float = conversionFailure("Float")
  override def doubleValue(): Double = conversionFailure("Double")
  override def isWhole(): Boolean = true
  override def underlying(): Object = this

  private def conversionFailure[A](target: String): A = {
    FrontendSession.requireActiveScope(token, s"conversion to Scala $target", origin)
    FrontendException.failAt(
      "MORPH-FRONTEND-SYMBOLIC-CONVERSION-UNSUPPORTED",
      s"generate index '${token.indexName}' cannot be converted to Scala $target",
      origin
    )
  }

  override def toString: String = "GenIndex(<scoped>)"
}

private[frontend] object GenIndex {
  private def describe(value: Any): String = value match {
    case _: GenIndex => "another GenIndex"
    case _: HdlInt   => "an HdlInt"
    case null        => "null"
    case other       => s"a ${other.getClass.getName} value"
  }
}
