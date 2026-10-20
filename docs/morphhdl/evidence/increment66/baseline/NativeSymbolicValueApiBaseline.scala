package morphhdl.examples
import spinal.core._
object NativeSymbolicValueApiBaseline {
  def value(busBytes: ElabInt): UInt = U(busBytes, 8 bits)
}
