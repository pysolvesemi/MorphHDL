package spinal.lib

import spinal.core._
import scala.collection.Seq

/** Adjacent-pair reduction with an operation-specific reusable definition.
  * COUNT is a bounded HDL parameter; payload/callbacks specialize the body.
  * Each operation returns the original payload type/width. Odd tails bypass
  * the operation but pass through the same bridge and selected stage register.
  */
@_root_.spinal.core.rtlRecursive
final class BalancedTree[T <: Data](elementType: HardType[T], capacity: Int,
    countActual: ElabInt, initialCountActual: ElabInt, pipelineMaskActual: ElabInt,
    widthActual: ElabInt, moduleName: String, clockConfig: ClockDomainConfig,
    operation: (T,T) => T, levelBridge: (T,Int) => T, widthFactory: ElabInt => T)
    extends Component {
  require(capacity >= 2 && capacity <= 64, "reduction capacity must be in 2..64")
  require(moduleName != null && moduleName.matches("[A-Za-z_][A-Za-z0-9_]*"),
    "provide an operation-specific module identifier")
  setDefinitionName(moduleName)
  noIoPrefix()
  private val levels: Int = log2Up(capacity)
  private val hasMask = pipelineMaskActual != null
  private val hasBridge = !(levelBridge eq BalancedTree.identityBridge[T])
  private val needsLevel = hasMask || hasBridge
  require((initialCountActual != null) == needsLevel,
    "initial count is present exactly when a bridge or pipeline mask needs levels")
  require((widthActual != null) == (widthFactory != null),
    "a symbolic payload width requires its type factory")
  val count: ElabInt = RtlParam.int("COUNT", countActual, 1, capacity)
  val initialCount: ElabInt = RtlParam.optionalInt("INITIAL_COUNT", initialCountActual, 1, capacity, 1)
  val mask: ElabInt = RtlParam.optionalInt("PIPELINE_MASK", pipelineMaskActual, 0, (1 << levels)-1, 0)
  val width: ElabInt = RtlParam.optionalInt("DATA_WIDTH", widthActual, 1, 64, 1)
  if (needsLevel) require(initialCount >= count)
  private val dataType = if (widthFactory == null) elementType else HardType(widthFactory(width))
  val io = new BalancedTree.Ports(
    if (widthFactory == null) elementType else HardType(widthFactory(width)), count)
  if (widthFactory != null) require(io.result.isInstanceOf[BitVector] &&
    ParameterizedWidth.expressionOf(io.result.asInstanceOf[BaseType]).exists(expression =>
      ElabInt.fromExpression(expression).elabEq(width).isAlwaysTrue),
    "payload factory must retain the exact DATA_WIDTH expression")
  private val domain = ClockDomain(io.clk, reset=io.reset, softReset=io.softReset,
    clockEnable=io.enable, config=clockConfig)
  val logic = new ClockingArea(domain) {
    if (count == 1) io.result := io.terms(0)
    else {
      val nextCount = (count + 1) / 2
      val combined = Vec(dataType(), nextCount)
      val nextData = if (!needsLevel) combined else Vec(dataType(), nextCount)
      for (i <- 0 until nextCount) {
        if (2*i+1 < count) combined(i) := operation(io.terms(2*i), io.terms(2*i+1))
        else combined(i) := io.terms(2*i)
        if (needsLevel) {
          def pipeline(value: T, selected: ElabBool): Unit = {
            if (!hasMask) nextData(i) := value
            else if (selected) {
              val registered = RegNext(value) init(value.getZero)
              nextData(i) := registered
            } else nextData(i) := value
          }
          if (!hasBridge && capacity <= 32) {
            val selected: ElabBool = ((mask * count.log2Up.pow2 / initialCount.log2Up.pow2) % 2).elabEq(1)
            pipeline(combined(i), selected)
          } else {
            val currentLevel = initialCount.log2Up - count.log2Up
            for (level <- 0 until levels) {
              if (currentLevel == level) {
                val bridged = dataType()
                bridged := levelBridge(combined(i), level)
                pipeline(bridged, ((mask / (1 << level)) % 2).elabEq(1))
              }
            }
          }
        }
      }
      val nextLevel = new BalancedTree[T](elementType, capacity, nextCount,
        initialCount, mask, width, moduleName, clockConfig, operation, levelBridge, widthFactory)
      nextLevel.io.clk := io.clk
      nextLevel.io.reset := io.reset
      nextLevel.io.softReset := io.softReset
      nextLevel.io.enable := io.enable
      nextLevel.io.terms := nextData
      io.result := nextLevel.io.result
    }
  }
}

object BalancedTree {
  private object IdentityBridge extends ((Data,Int) => Data) {
    def apply(value: Data, level: Int): Data = value
  }
  def identityBridge[T <: Data]: (T,Int) => T =
    IdentityBridge.asInstanceOf[(T,Int) => T]

  class Ports[T <: Data](elementType: HardType[T], count: ElabInt) extends Bundle {
    val clk = in Bool()
    val reset = in Bool()
    val softReset = in Bool()
    val enable = in Bool()
    val terms = in(Vec(elementType(), count))
    val result = out(elementType())
  }
  final class Operation[T <: Data] private[BalancedTree](elementType: HardType[T],
      capacity: Int, moduleName: String, operation: (T,T) => T,
      levelBridge: (T,Int) => T, widthFactory: ElabInt => T) {
    def component(count: ElabInt, pipelineMask: ElabInt = null,
        clockConfig: ClockDomainConfig = ClockDomainConfig()): BalancedTree[T] = {
      val needsLevel = pipelineMask != null || !(levelBridge eq identityBridge[T])
      new BalancedTree[T](elementType,capacity,count,if(needsLevel) count else null,
        pipelineMask,if(widthFactory==null) null else ElabInt.literal(elementType().getBitsWidth),
        moduleName,clockConfig,operation,levelBridge,widthFactory)
    }

    def reduceBalancedTree(inputs: Seq[T], pipelineMask: ElabInt = null): T =
      reduceBalancedTree(inputs,inputCount(inputs),pipelineMask)

    def reduceBalancedTree(inputs: Seq[T], count: ElabInt, pipelineMask: ElabInt): T = {
      val supplied = inputCount(inputs)
      require(supplied.minimum>=1 && supplied.maximum<=capacity, "input count must be in 1..capacity")
      require(count.minimum>=1 && count.maximum<=supplied.maximum, "live count must fit supplied inputs")
      val expected=elementType()
      val representative = inputs match {
        case vector: Vec[_] => Seq(vector(0).asInstanceOf[T])
        case _ => inputs
      }
      require(representative.forall(input => sameGeometry(input,expected)), "payload types and widths must match")
      require(representative.flatMap(_.flatten).forall(value =>
        ParameterizedWidth.expressionOf(value).forall(_.parameters.isEmpty)),
        "sequence adapters require concrete payload geometry")
      val clock=ClockDomain.current
      val tree=component(count,pipelineMask,clock.config)
      tree.io.clk := clock.readClockWire
      tree.io.reset := clock.readResetWire
      tree.io.softReset := clock.readSoftResetWire
      tree.io.enable := clock.readClockEnableWire
      inputs match {
        case vector: Vec[_] => tree.io.terms := vector.asInstanceOf[Vec[T]]
        case _ =>
          val terms=Vec(elementType(),count)
          terms.assignFromBits(Vec(inputs).asBits.resize(count*expected.getBitsWidth))
          tree.io.terms := terms
      }
      val result=elementType()
      result := tree.io.result
      result
    }
  }

  private def inputCount(inputs: Seq[_ <: Data]): ElabInt = inputs match {
    case vector: Vec[_] => ParameterizedVec.shapeOf(vector)
      .map(shape => ElabInt.fromExpression(shape.depth)).getOrElse(ElabInt.literal(vector.length))
    case _ => ElabInt.literal(inputs.size)
  }

  private def sameGeometry(a: Data,b: Data): Boolean =
    a.getClass==b.getClass && a.flatten.map(v=>(v.getClass,v.getBitsWidth))==
      b.flatten.map(v=>(v.getClass,v.getBitsWidth))

  def define[T <: Data](elementType: HardType[T],capacity: Int,moduleName: String)(
      operation: (T,T) => T,levelBridge: (T,Int) => T = identityBridge[T]): Operation[T] =
    new Operation(elementType,capacity,moduleName,operation,levelBridge,null)

  def defineWidth[T <: BitVector](factory: ElabInt => T,width: Int,
      capacity: Int,moduleName: String)(operation: (T,T) => T,
      levelBridge: (T,Int) => T = identityBridge[T]): Operation[T] = {
    require(width>=1 && width<=64,"payload width must be in 1..64")
    new Operation(HardType(factory(ElabInt.literal(width))),capacity,moduleName,
      operation,levelBridge,factory)
  }

  def reduceBalancedTree[T <: Data](inputs: Seq[T],moduleName: String,
      pipelineMask: ElabInt = null)(operation: (T,T) => T,
      levelBridge: (T,Int) => T = identityBridge[T]): T = {
    val count = inputCount(inputs)
    require(count.minimum>=1 && count.maximum<=64,"input count must be in 1..64")
    define(HardType(cloneOf(inputs(0))),scala.math.max(2,count.maximum.toInt),moduleName)(operation,levelBridge)
      .reduceBalancedTree(inputs,pipelineMask)
  }

}
