package nativeapplication

import spinal.core._

/** An operation-named, adjacent-pair reduction module.
  *
  * COUNT determines the terms port width and the number of array words. One
  * self-instance consumes the next level, so non-associative operations retain
  * Spinal's pairing, including the bridge on an unpaired last lane.
  * Payload geometry and callbacks specialize the definition; COUNT and
  * PIPELINE_MASK remain Verilog parameters. Bit zero registers the first level.
  */
class NativeRecursiveTreeFixture[T <: Data](
    elementType: HardType[T],
    capacity: Int,
    countActual: ElabInt,
    moduleName: String,
    pipelineMaskActual: ElabInt = ElabInt.literal(0),
    clockConfig: ClockDomainConfig = ClockDomainConfig(),
    operation: (T, T) => T,
    levelBridge: (T, Int) => T = NativeRecursiveTreeFixture.identityBridge[T],
    guardedPairs: Boolean = false
) extends Component {
  require(capacity >= 2 && capacity <= 64, "capacity must be in 2..64")
  require(moduleName != null && moduleName.matches("[A-Za-z_][A-Za-z0-9_]*") &&
    !moduleName.toLowerCase.contains("recursive") &&
    !moduleName.toLowerCase.contains("reducebalancedtree"),
    "provide an operation-specific Verilog module name")
  setDefinitionName(moduleName)
  noIoPrefix()
  private val levels = log2Up(capacity)
  @dontName val count = ElabFormalParameter(countActual, "COUNT", 1, capacity)
  @dontName val initialCount = ElabFormalParameter(countActual, "INITIAL_COUNT", 1, capacity)
  @dontName val pipelineMask = ElabFormalParameter(pipelineMaskActual, "PIPELINE_MASK", 0, (1 << levels) - 1)
  @dontName private val payload = elementType()
  require(payload.flatten.forall(value => ParameterizedWidth.expressionOf(value).forall(_.parameters.isEmpty)),
    "payload widths must be concrete; specialize the operation module for each payload geometry")
  ElabControl.requireCondition(initialCount >= count,
    "INITIAL_COUNT must be at least COUNT (bind both to the input count at the root)", "NativeRecursiveTreeFixture", 6)
  private val wordBits = payload.getBitsWidth
  require(wordBits > 0, "payload must have positive fixed width")

  // Calculate the shared elaboration-time pipeline selection once.
  // Separate nonnegative exponents cover the full formal domain, including
  // combinations rejected by INITIAL_COUNT >= COUNT at instantiation.
  val REGISTER_LEVEL = if ((levelBridge eq NativeRecursiveTreeFixture.identityBridge[T]) && capacity <= 32)
    (pipelineMask * count.log2Up.pow2 / initialCount.log2Up.pow2) % 2
  else ElabInt.literal(0)

  @dontName private val recursion = ElabRecursiveComponent(Vector(count, initialCount, pipelineMask)) {
    values => new NativeRecursiveTreeFixture.Ports(elementType, values(0), wordBits)
  }
  val io = recursion.ports
  private val domain = ClockDomain(io.clk, reset = io.reset,
    softReset = io.softReset, clockEnable = io.enable, config = clockConfig)

  val logic = new ClockingArea(domain) {
    val lanes = Vec(elementType(), count).setName("lanes")
    ElabFiniteRange.foreach(count, "unpackTerms") { index =>
      index(lanes).assignFromBits(index.packed(io.terms, wordBits, wordBits bits))
    }

    ElabControl.selectSymbolic(count <= 1, "NativeRecursiveTreeFixture", 1) {
      io.result := lanes(0)
    } {
      val nextCount = (count + 1) / 2
      val nextLanes = Vec(elementType(), nextCount).setName("nextData")
      val combinedLanes = Vec(elementType(), nextCount).setName("combined")
      if (guardedPairs) {
        ElabFiniteRange.foreach(nextCount, "combine") { index =>
          index.whenAffineLessThan(2, 1, count, "pair") {
            index(combinedLanes) := operation(index.affine(lanes, 2, 0), index.affine(lanes, 2, 1))
          } {
            index(combinedLanes) := index.affine(lanes, 2, 0)
          }
        }
      } else {
      val pairedLanes = Vec(elementType(), count / 2).setName("paired")
      ElabFiniteRange.foreach(count / 2, "combinePairs") { index =>
        index(pairedLanes) := operation(index.affine(lanes, 2, 0), index.affine(lanes, 2, 1))
      }
      for (i <- 0 until (capacity + 1) / 2) {
        ElabControl.generateSymbolic(count > 2 * i, "NativeRecursiveTreeFixture", 2) {
          val combined = combinedLanes(i)
          if (2 * i + 1 < capacity) {
            ElabControl.selectSymbolic(count > 2 * i + 1, "NativeRecursiveTreeFixture", 3) {
              combined := pairedLanes(i)
            } {
              combined := lanes(2 * i)
            }
          } else combined := lanes(2 * i)
        }
      }
      }
      ElabFiniteRange.foreach(nextCount, "pipelineLanes") { index =>
        val combined = index(combinedLanes)
        def pipeline(value: T, selected: ElabBool, name: String): Unit = {
          ElabControl.selectSymbolic(selected, "NativeRecursiveTreeFixture", 5) {
            val registered = (RegNext(value) init(value.getZero)).setName(name)
            index(nextLanes) := registered
          } {
            index(nextLanes) := value
          }
        }
        val currentLevel = initialCount.log2Up - count.log2Up
        if ((levelBridge eq NativeRecursiveTreeFixture.identityBridge[T]) && capacity <= 32) {
          pipeline(combined, REGISTER_LEVEL.elabEq(1), "registered")
        } else {
          for (level <- 0 until levels) {
            ElabControl.generateSymbolic(currentLevel.elabEq(level), "NativeRecursiveTreeFixture", 4) {
              val bridged = elementType().setName(s"bridge_$level")
              bridged.flatten.foreach(_.dontSimplifyIt().addAttribute("spinalKeep"))
              bridged := levelBridge(combined, level)
              pipeline(bridged, ((pipelineMask / (1 << level)) % 2).elabEq(1), s"registeredLevel_$level")
            }
          }
        }
      }
      val nextLevel = recursion(count -> nextCount)
      nextLevel.ports.clk := io.clk
      nextLevel.ports.reset := io.reset
      nextLevel.ports.softReset := io.softReset
      nextLevel.ports.enable := io.enable
      val nextTerms = Bits((nextCount * wordBits) bits).setName("nextTerms")
      ElabFiniteRange.foreach(nextCount, "packTerms") { index =>
        index.packed(nextTerms, wordBits, wordBits bits) := index(nextLanes).asBits
      }
      nextLevel.ports.terms := nextTerms
      io.result := nextLevel.ports.result
    }
  }
}

object NativeRecursiveTreeFixture {
  private object IdentityBridge extends ((Data, Int) => Data) {
    override def apply(value: Data, level: Int): Data = value
  }
  private[nativeapplication] def identityBridge[T <: Data]: (T, Int) => T =
    IdentityBridge.asInstanceOf[(T, Int) => T]

  private[nativeapplication] class Ports[T <: Data](elementType: HardType[T], count: ElabInt, wordBits: Int) extends Bundle {
    val clk = in Bool()
    val reset = in Bool()
    val softReset = in Bool()
    val enable = in Bool()
    val terms = in Bits((count * wordBits) bits)
    val result = out(elementType())
  }

}
