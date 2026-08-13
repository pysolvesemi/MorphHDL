package morphhdl

package object frontend {
  import scala.language.implicitConversions

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
}
