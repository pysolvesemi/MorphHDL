package morphhdl

package object frontend {
  import scala.language.implicitConversions

  import spinal.core._

  implicit def intToHdlInt(value: Int)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlInt = HdlInt.literal(BigInt(value))(file, line)

  implicit def booleanToHdlBool(value: Boolean)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlBool = HdlBool.literal(value)(file, line)

  implicit final class HdlIntRangeStart(private val start: Int) extends AnyVal {
    def until(end: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlRange =
      HdlRange(start, end, SourceOrigin.capture)

    /** Inclusive symbolic range; `0 to end` lowers with count `end + 1`. */
    def to(end: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlRange =
      HdlRange(start, end + HdlInt.literal(BigInt(1)), SourceOrigin.capture)
  }

  /** Keeps Int-left arithmetic in the dual-valued HdlInt expression domain. */
  implicit final class HdlIntLeftOperand(private val left: Int) extends AnyVal {
    def +(right: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt =
      HdlInt.literal(BigInt(left)) + right

    def -(right: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt =
      HdlInt.literal(BigInt(left)) - right

    def *(right: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt =
      HdlInt.literal(BigInt(left)) * right

    def /(right: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt =
      HdlInt.literal(BigInt(left)) / right

    def %(right: HdlInt)(implicit file: sourcecode.File, line: sourcecode.Line): HdlInt =
      HdlInt.literal(BigInt(left)) % right
  }

  /**
    * Parameter-controlled structural generate-if for ordinary SpinalHDL
    * Component construction. Keeping it as an HdlBool extension avoids
    * colliding with the established ParamRtlFrontend.generateIf import.
    */
  implicit final class StructuralGenerateIfOps(private val condition: HdlBool) extends AnyVal {
    def generateIf(whenTrue: => Unit)(implicit
        file: sourcecode.File,
        line: sourcecode.Line
    ): GenerateIfBuilder =
      startStructuralGenerateIf(condition, None, whenTrue, SourceOrigin.capture)

    def generateIf(
        whenTrueLabel: String,
        whenFalseLabel: String
    )(whenTrue: => Unit)(implicit
        file: sourcecode.File,
        line: sourcecode.Line
    ): GenerateIfBuilder = {
      val origin = SourceOrigin.capture
      HdlRange.requireIdentifier(whenTrueLabel, "generate-if true label", origin)
      HdlRange.requireIdentifier(whenFalseLabel, "generate-if false label", origin)
      startStructuralGenerateIf(
        condition,
        Some(GenerateIfNames(whenTrueLabel, whenFalseLabel)),
        whenTrue,
        origin
      )
    }
  }

  /**
    * Parameter-controlled structural generate-case for ordinary SpinalHDL
    * Component construction.
    */
  implicit final class StructuralGenerateCaseOps(private val selector: HdlInt) extends AnyVal {
    def generateCase(implicit
        file: sourcecode.File,
        line: sourcecode.Line
    ): GenerateCaseBuilder = {
      val origin = SourceOrigin.capture
      try FrontendSession.startGenerateCase(selector, origin)
      catch {
        case error: FrontendException
            if error.code == "MORPH-FRONTEND-SESSION-MISSING" =>
          NativeStructuralFrontend.startGenerateCase(selector, origin)
      }
    }
  }

  /**
    * Static-witness packed slice which retains a generate-index expression for
    * the native Verilog structural rewrite.
    */
  implicit final class StructuralBitVectorOps[T <: BitVector](private val source: T) {
    def apply(offset: HdlInt, width: HdlInt)(implicit
        file: sourcecode.File,
        line: sourcecode.Line
    ): T = {
      val origin = SourceOrigin.capture
      val offsetValue = witnessInt(offset, "packed-slice offset", origin)
      val widthValue = witnessInt(width, "packed-slice width", origin)
      val result = source(offsetValue, widthValue bits)
      if (ParameterizedStructure.captureEnabled) {
        val offsetExpression =
          StructuralExpressionBridge.integer(offset, "packed-slice offset")
        val widthExpression =
          StructuralExpressionBridge.integer(width, "packed-slice width")
        ParameterizedStructure.recordSlice(
          source,
          result,
          offsetExpression,
          widthExpression,
          Some(origin.rendered)
        )
      }
      result.asInstanceOf[T]
    }

    def apply(offset: GenIndex, width: HdlInt)(implicit
        file: sourcecode.File,
        line: sourcecode.Line
    ): T = apply(offset.asHdlInt("packed-slice offset"), width)
  }

  /** Static Vec selection retained as a generate-time index. */
  implicit final class StructuralVecOps[T <: Data](private val vector: Vec[T]) {
    def apply(index: HdlInt)(implicit
        file: sourcecode.File,
        line: sourcecode.Line
    ): T = {
      val origin = SourceOrigin.capture
      val indexValue = witnessInt(index, "Vec index", origin)
      if (indexValue < 0 || indexValue >= vector.length) {
        FrontendException.failAt(
          "MORPH-FRONTEND-STRUCTURAL-VEC-WITNESS-OUT-OF-RANGE",
          s"Vec index witness $indexValue is outside 0 until ${vector.length}",
          origin
        )
      }
      val selected = vector(indexValue)
      if (ParameterizedStructure.captureEnabled) {
        ParameterizedStructure.recordVecIndex(
          vector,
          selected,
          StructuralExpressionBridge.integer(index, "Vec index"),
          Some(origin.rendered)
        )
      }
      selected
    }

    def apply(index: GenIndex)(implicit
        file: sourcecode.File,
        line: sourcecode.Line
    ): T = apply(index.asHdlInt("Vec index"))
  }

  private def startStructuralGenerateIf(
      condition: HdlBool,
      names: Option[GenerateIfNames],
      whenTrue: => Unit,
      origin: SourceOrigin
  ): GenerateIfBuilder =
    try FrontendSession.startGenerateIf(condition, names, whenTrue, origin)
    catch {
      case error: FrontendException
          if error.code == "MORPH-FRONTEND-SESSION-MISSING" =>
        NativeStructuralFrontend.startGenerateIf(condition, names, whenTrue, origin)
    }

  private def witnessInt(
      value: HdlInt,
      role: String,
      origin: SourceOrigin
  ): Int = {
    if (value eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-STRUCTURAL-INTEGER-NULL",
        s"$role requires a non-null HdlInt",
        origin
      )
    }
    value.requireUsable(role)
    if (!value.witness.isValidInt) {
      FrontendException.failAt(
        "MORPH-FRONTEND-STRUCTURAL-WITNESS-TOO-LARGE",
        s"$role witness ${value.witness} does not fit a Scala Int",
        origin
      )
    }
    value.witness.toInt
  }
}
