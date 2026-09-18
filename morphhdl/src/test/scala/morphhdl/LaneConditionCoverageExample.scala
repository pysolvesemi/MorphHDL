package morphhdl

import spinal.core._

/** Standalone condition-receiver regression.
  * No signal is classified using its emitted name. @dontName leaves the
  * compiler free to attach removable source-location provenance in when().
  */
class LaneConditionCoverageExample(ppc: ElabInt) extends Component {
  setDefinitionName("LaneConditionCoverageExample")
  val io = new Bundle {
    val x = in UInt(13 bits)
    val y = in UInt(12 bits)
    val hTotal = in UInt(13 bits)
    val vTotal = in UInt(12 bits)
    val witnessIn = in Bits(ppc bits)
    val witnessOut = out Bits(ppc bits)
    val singleX = out UInt(13 bits)
    val sharedX = out UInt(13 bits)
    val sharedY = out UInt(12 bits)
    val nestedX = out UInt(13 bits)
    val nestedY = out UInt(12 bits)
    val priority = out Bits(2 bits)
    val mixedX = out UInt(13 bits)
    val booleanRhs = out Bool()
    val protectedX = out UInt(13 bits)
    val protectedBoolean = out Bool()
    val keptGeneratedX = out UInt(13 bits)
  }
  io.witnessOut := io.witnessIn

  // A single-use compiler-created condition.
  io.singleX := io.x + 1
  when(io.x === io.hTotal - 1) { io.singleX := 0 }

  // Separate when statements keep the two receiver trees distinct.
  @dontName val shared = Bool()
  shared := io.x === io.hTotal - 1
  io.sharedX := io.x + 1
  io.sharedY := io.y
  when(shared) { io.sharedX := 0 }
  when(shared) { io.sharedY := io.y + 1 }

  io.nestedX := io.x + 1
  io.nestedY := io.y
  when(io.x === io.hTotal - 1) {
    io.nestedX := 0
    io.nestedY := io.y + 1
    when(io.y === io.vTotal - 1) { io.nestedY := 0 }
  }

  // The first branch wins when hTotal=1 and x=0.
  when(io.x === 0) {
    io.priority := B(0, 2 bits)
  } elsewhen(io.x === io.hTotal - 1) {
    io.priority := B(1, 2 bits)
  } otherwise {
    io.priority := B(2, 2 bits)
  }

  @dontName val mixed = Bool()
  mixed := io.y === io.vTotal - 1
  io.booleanRhs := mixed
  io.mixedX := io.x
  when(mixed) { io.mixedX := 0 }

  // Explicit user identity plus protection: a generated-name lookalike.
  val when_example_l42 = Bool()
  when_example_l42.setName("when_example_l42")
  when_example_l42.addAttribute("keep")
  when_example_l42 := io.x === io.hTotal - 1
  io.protectedBoolean := when_example_l42
  io.protectedX := io.x + 1
  when(when_example_l42) { io.protectedX := 0 }

  // Independently exercise protection on a compiler-name-eligible signal.
  @dontName val keptGenerated = Bool()
  keptGenerated.addAttribute("keep")
  keptGenerated := io.x === io.hTotal - 1
  io.keptGeneratedX := io.x + 1
  when(keptGenerated) { io.keptGeneratedX := 0 }
}
