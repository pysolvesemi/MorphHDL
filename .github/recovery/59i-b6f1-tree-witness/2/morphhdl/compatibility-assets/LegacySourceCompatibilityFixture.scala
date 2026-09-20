package morphhdl.compatibility

import spinal.core._
import spinal.lib._

/**
  * An unchanged external consumer compiled against both the selected upstream
  * baseline and the current MorphHDL artifacts.  Keep every expression on an
  * ordinary, concrete SpinalHDL API surface shared by both builds.
  */
object LegacySourceCompatibilityFixture {
  private val defaultConfig = SpinalConfig()

  // Default arguments, positional arguments, and named arguments exercise the
  // companion's apply/default-getter source contract.
  val defaultConstructedConfig: SpinalConfig = SpinalConfig()
  val partiallyPositionalConfig: SpinalConfig = SpinalConfig(Verilog)
  val namedConfig: SpinalConfig = SpinalConfig(
    mode = Verilog,
    keepAll = true,
    targetDirectory = "legacy-source-output",
    oneFilePerComponent = true,
    svInterface = false
  )

  // This full positional call intentionally fixes the legacy 59-element case
  // class shape.  Appending, removing, reordering, or retyping a constructor
  // element must fail this source fixture even if named/default calls survive.
  val fullyPositionalConfig: SpinalConfig = SpinalConfig(
    defaultConfig.mode,
    defaultConfig.flags,
    defaultConfig.debugComponents,
    defaultConfig.keepAll,
    defaultConfig.defaultConfigForClockDomains,
    defaultConfig.onlyStdLogicVectorAtTopLevelIo,
    defaultConfig.defaultClockDomainFrequency,
    defaultConfig.targetDirectory,
    defaultConfig.oneFilePerComponent,
    defaultConfig.netlistFileName,
    null,
    defaultConfig.globalPrefix,
    defaultConfig.privateNamespace,
    defaultConfig.formalAsserts,
    defaultConfig.anonymSignalPrefix,
    defaultConfig.device,
    defaultConfig.inlineRom,
    defaultConfig.caseRom,
    defaultConfig.romReuse,
    defaultConfig.genVhdlPkg,
    defaultConfig.verbose,
    defaultConfig.mergeAsyncProcess,
    defaultConfig.mergeSyncProcess,
    defaultConfig.asyncResetCombSensitivity,
    defaultConfig.anonymSignalUniqueness,
    defaultConfig.inlineConditionalExpression,
    defaultConfig.nameWhenByFile,
    defaultConfig.genLineComments,
    defaultConfig.noRandBoot,
    defaultConfig.randBootFixValue,
    defaultConfig.noAssert,
    defaultConfig.fixToWithWrap,
    defaultConfig.headerWithDate,
    defaultConfig.headerWithRepoHash,
    defaultConfig.removePruned,
    defaultConfig.allowOutOfRangeLiterals,
    defaultConfig.dontCareGenAsZero,
    defaultConfig.obfuscateNames,
    defaultConfig.obfuscate,
    defaultConfig.normalizeComponentClockDomainName,
    defaultConfig.devicePhaseHandler,
    defaultConfig.phasesInserters,
    defaultConfig.transformationPhases,
    defaultConfig.memBlackBoxers,
    defaultConfig.rtlHeader,
    defaultConfig.scopeProperties,
    true, // private[core] _withEnumString retains its public apply position
    defaultConfig.enumPrefixEnable,
    defaultConfig.enumGlobalEnable,
    defaultConfig.bitVectorWidthMax,
    defaultConfig.singleTopLevel,
    defaultConfig.noAssertAtTimeZero,
    defaultConfig.cutLongExpressions,
    defaultConfig.withTimescale,
    defaultConfig.printFilelist,
    defaultConfig.emitFullComponentBindings,
    defaultConfig.reportIncludeSourceLocation,
    defaultConfig.reportSourceLocationFormat,
    defaultConfig.svInterface
  )

  val copiedConfig: SpinalConfig = namedConfig.copy(
    mode = SystemVerilog,
    keepAll = false,
    targetDirectory = "legacy-source-copy"
  )

  // Scala 2 deliberately omits a generated unapply for case classes beyond
  // Tuple22.  SpinalConfig has 59 elements, so Product is its available legacy
  // decomposition surface in both supported lanes.
  val configProduct: Product = fullyPositionalConfig
  val configProductArity: Int = configProduct.productArity
  val configFirstProductElement: Any = configProduct.productElement(0)
  val configProductIterator: Iterator[Any] = configProduct.productIterator

  /** Execute the Product contract in both worktrees after source compilation.
    * Constructor/default shims alone must not hide a changed case-class shape.
    */
  def main(arguments: Array[String]): Unit = {
    require(arguments.isEmpty, "legacy source compatibility fixture takes no arguments")
    require(configProductArity == 59, s"SpinalConfig Product arity changed: $configProductArity")
    require(
      configFirstProductElement == defaultConfig.mode,
      "SpinalConfig Product element zero no longer contains mode"
    )
    require(
      configProductIterator.size == 59,
      "SpinalConfig Product iterator no longer exposes exactly 59 elements"
    )
    println("LEGACY_SOURCE_COMPATIBILITY_OK productArity=59")
  }

  final class OrdinaryLegacyConsumer extends Component {
    val sourceBits = Bits(8 bits)
    val resizedByInt: Bits = sourceBits.resize(12)
    val resizedByBitCount: Bits = sourceBits.resize(12 bits)
    val rotatedByInt: Bits = sourceBits.rotateLeft(3)
    val selectedByRange: Bits = sourceBits(7 downto 4)

    val sourceUInt = UInt(8 bits)
    val resizedUIntByInt: UInt = sourceUInt.resize(12)
    val resizedUIntByBitCount: UInt = sourceUInt.resize(12 bits)

    val counterFromStateCount: Counter = Counter(BigInt(16))
    val counterFromBounds: Counter = Counter(BigInt(2), BigInt(15))
    val counterFromRange: Counter = Counter(2 until 16)
    val counterFromBitCount: Counter = Counter(5 bits)
    val downCounter: Counter = Counter.down(BigInt(16))
    val bothCounter: Counter = Counter.both(BigInt(16))
    val freeRunningCounter: Counter = CounterFreeRun(BigInt(16))

    val directlyConstructedCounter: Counter = new Counter(
      start = BigInt(2),
      end = BigInt(15),
      direction = CounterDirection.Both,
      upper = BoundaryPolicy.Wrap,
      lower = BoundaryPolicy.Wrap,
      handleOverflow = true
    )

    val fifoFromConstructor: StreamFifo[Bits] = new StreamFifo[Bits](
      dataType = HardType(Bits(8 bits)),
      depth = 16,
      withAsyncRead = true,
      withBypass = false,
      allowExtraMsb = true,
      forFMax = false,
      useVec = true,
      initPayload = None
    )
    val fifoFromConstructorDefaults: StreamFifo[Bits] =
      new StreamFifo[Bits](HardType(Bits(8 bits)), 8)
    val fifoFromCompanion: StreamFifo[Bits] = StreamFifo(
      dataType = HardType(Bits(8 bits)),
      depth = 8,
      latency = 2,
      forFMax = false,
      initPayload = None
    )
  }

  // The non-static inner class constructor descriptor includes the enclosing
  // StreamFifo instance followed by the two legacy scala.math.BigInt values.
  def newLegacyFmaxCounter(
      fifo: StreamFifo[Bits]
  ): fifo.CounterUpDownFmax =
    new fifo.CounterUpDownFmax(states = BigInt(16), init = BigInt(0))
}
