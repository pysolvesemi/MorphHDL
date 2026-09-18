package morphhdl

import spinal.core._

/** Standalone selection-safety regression. */
class LaneReceiverCoverageExample(ppc: ElabInt) extends Component {
  setDefinitionName("LaneReceiverCoverageExample")
  val io = new Bundle {
    val running = in Bool()
    val x = in UInt(13 bits)
    val y = in UInt(12 bits)
    val hActive = in UInt(16 bits)
    val vActive = in UInt(16 bits)
    val mask = in Bits(4 bits)
    val overrideEnable = in Bool()
    val index = in UInt(2 bits)
    val whole = out Bool()
    val de = out Bits(ppc bits)
    val ranged = out Bits(8 bits)
    val overlap = out Bits(4 bits)
    val dynamic = out Bits(4 bits)
    val protectedBits = out Bits(4 bits)
    val sharedLess = out Bool()
    val sharedEqual = out Bool()
  }

  // Construct a fresh expression at every call; do not alter the application
  // to suppress carrier creation or add an inlining switch.
  def predicate(): Bool = io.running &&
    (io.x.resize(16) < io.hActive) &&
    (io.y.resize(16) < io.vActive)

  io.whole := predicate()
  val lanes = Bits(4 bits)
  val ranges = Bits(8 bits)
  val overlapping = Bits(4 bits)
  val dynamicallySelected = Bits(4 bits)
  val keptLane = Bits(4 bits)
  keptLane.setName("keptLane")
  keptLane.addAttribute("keep")
  for (n <- 0 until 4) {
    lanes(n) := io.mask(n) && predicate()
    ranges(2*n+1 downto 2*n) :=
      (io.running && (io.x.resize(16) < io.hActive)).asBits ##
      (io.running && (io.y.resize(16) < io.vActive)).asBits
    overlapping(n) := io.mask(n) && predicate()
    dynamicallySelected(n) := io.mask(n)
    keptLane(n) := io.mask(n) && predicate()
  }
  when(io.overrideEnable) {
    overlapping(0) := !predicate()
    dynamicallySelected(io.index) := predicate()
  }
  io.de := lanes.resize(ppc)
  io.ranged := ranges
  io.overlap := overlapping
  io.dynamic := dynamicallySelected
  io.protectedBits := keptLane

  @dontName val sharedWide = UInt(16 bits)
  sharedWide := io.x.resize(16)
  io.sharedLess := sharedWide < io.hActive
  io.sharedEqual := sharedWide === io.hActive - 1
}
