package morphhdl.frontend

import spinal.core.{Data, HardType, Mem => SpinalMem}

/**
  * MorphHDL typed adapter for an ordinary SpinalHDL Mem with retained depth.
  * The returned object is the native Mem and all port algorithms remain native.
  */
object Mem {
  def apply[T <: Data](
      wordType: HardType[T],
      wordCount: HdlInt
  )(implicit file: sourcecode.File, line: sourcecode.Line): SpinalMem[T] = {
    if (wordCount == null)
      throw new IllegalArgumentException("symbolic native memory depth must not be null")
    SpinalMem(wordType, wordCount.asElabInt)
  }

  def apply[T <: Data](wordType: HardType[T], wordCount: Int): SpinalMem[T] =
    spinal.core.Mem(wordType, wordCount)

  def apply[T <: Data](wordType: HardType[T], wordCount: BigInt): SpinalMem[T] =
    spinal.core.Mem(wordType, wordCount)

  def fill[T <: Data](wordCount: HdlInt)(
      wordType: HardType[T]
  )(implicit file: sourcecode.File, line: sourcecode.Line): SpinalMem[T] =
    apply(wordType, wordCount)

  def fill[T <: Data](wordCount: Int)(wordType: HardType[T]): SpinalMem[T] =
    spinal.core.Mem.fill(wordCount)(wordType)
}
