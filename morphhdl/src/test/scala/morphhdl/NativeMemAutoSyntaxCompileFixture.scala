package morphhdl

import spinal.core._

import morphhdl.frontend.{HdlInt, NativeMemFactoryOps}

/** Compile-only proof for the ordinary native-looking `Mem(..., HdlInt)` form. */
object NativeMemAutoSyntaxCompileFixture {
  final class Top(depth: HdlInt) extends Component {
    val memory = Mem(HardType(Bits(8 bits)), depth)
  }
}
