package morphhdl

package object frontend {
  import scala.language.implicitConversions

  import spinal.core._

  /** MorphHDL-owned symbolic data factories. These shadow only explicit
    * `morphhdl.frontend` calls and delegate concrete construction to untouched
    * SpinalHDL factories.
    */
  def Bits(width: ParameterizedBitCount): spinal.core.Bits =
    ExternalFormalParameterRegistry.attach(ParameterizedWidth.Bits(width), width)
  def Bits(width: BitCount): spinal.core.Bits = spinal.core.Bits(width)

  def UInt(width: ParameterizedBitCount): spinal.core.UInt =
    ExternalFormalParameterRegistry.attach(ParameterizedWidth.UInt(width), width)
  def UInt(width: BitCount): spinal.core.UInt = spinal.core.UInt(width)

  def SInt(width: ParameterizedBitCount): spinal.core.SInt =
    ExternalFormalParameterRegistry.attach(ParameterizedWidth.SInt(width), width)
  def SInt(width: BitCount): spinal.core.SInt = spinal.core.SInt(width)

  def cloneOf[T <: Data](data: T): T = ParameterizedWidth.cloneOf(data)
  def HardType[T <: Data](dataType: => T): spinal.core.HardType[T] =
    ParameterizedWidth.HardType(dataType)
  def Reg[T <: Data](dataType: => T): T = ParameterizedWidth.Reg(dataType)
  def Vec[T <: Data](dataType: => T, size: Int): spinal.core.Vec[T] =
    ParameterizedWidth.Vec(dataType, size)
  def Vec[T <: Data](dataType: => T, size: ElabInt): spinal.core.Vec[T] =
    ParameterizedWidth.Vec(dataType, size)
  def Vec[T <: Data](dataType: => T, size: HdlInt)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): spinal.core.Vec[T] =
    ParameterizedWidth.Vec(dataType, size.asElabInt)

  def Vec[T <: Data](
      dataType: spinal.core.HardType[T],
      size: Int
  ): spinal.core.Vec[T] =
    ParameterizedWidth.Vec(dataType, size)
  def Vec[T <: Data](
      dataType: spinal.core.HardType[T],
      size: ElabInt
  ): spinal.core.Vec[T] =
    ParameterizedWidth.Vec(dataType, size)
  def Vec[T <: Data](
      dataType: spinal.core.HardType[T],
      size: HdlInt
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): spinal.core.Vec[T] =
    ParameterizedWidth.Vec(dataType, size.asElabInt)

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

  /** Parameter-controlled structural generate-if for ordinary SpinalHDL
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

  /** Parameter-controlled structural generate-case for ordinary SpinalHDL
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
        case error: FrontendException if error.code == "MORPH-FRONTEND-SESSION-MISSING" =>
          NativeStructuralFrontend.startGenerateCase(selector, origin)
      }
    }
  }

  /** Static-witness packed slice which retains a generate-index expression for
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
        val processCapture = ParameterizedProcess.captureActive
        val targets = Vector[AnyRef](source, result)
        val generateIndices = NativeStructuralFrontend.currentGenerateIndices
        val offsetAnalysis = StructuralExpressionBridge.analyzedStructuralInteger(
          offset,
          "packed-slice offset",
          generateIndices,
          if (processCapture)
            AnalyzedStructuralIntegerKind.ProcessSliceOffset
          else AnalyzedStructuralIntegerKind.StructuralSliceOffset,
          targets
        )
        val widthAnalysis = StructuralExpressionBridge.analyzedStructuralInteger(
          width,
          "packed-slice width",
          generateIndices,
          if (processCapture)
            AnalyzedStructuralIntegerKind.ProcessSliceWidth
          else AnalyzedStructuralIntegerKind.StructuralSliceWidth,
          targets
        )
        if (processCapture) {
          ExternalAnalyzedStructuralPublisher.recordProcessSlice(
            offsetAnalysis,
            widthAnalysis,
            source,
            result,
            Some(origin.rendered)
          )
        } else {
          ExternalAnalyzedStructuralPublisher.recordStructuralSlice(
            offsetAnalysis,
            widthAnalysis,
            source,
            result,
            Some(origin.rendered)
          )
        }
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
        val processCapture = ParameterizedProcess.captureActive
        val analysis = StructuralExpressionBridge.analyzedStructuralInteger(
          index,
          "Vec index",
          NativeStructuralFrontend.currentGenerateIndices,
          if (processCapture)
            AnalyzedStructuralIntegerKind.ProcessVecIndex
          else AnalyzedStructuralIntegerKind.StructuralVecIndex,
          Vector[AnyRef](vector, selected)
        )
        if (processCapture) {
          ExternalAnalyzedStructuralPublisher.recordProcessVecIndex(
            analysis,
            vector,
            selected,
            Some(origin.rendered)
          )
        } else {
          ExternalAnalyzedStructuralPublisher.recordStructuralVecIndex(
            analysis,
            vector,
            selected,
            Some(origin.rendered)
          )
        }
      } else selected
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
      case error: FrontendException if error.code == "MORPH-FRONTEND-SESSION-MISSING" =>
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
