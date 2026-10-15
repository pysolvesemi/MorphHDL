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
    @rtlHardwareCallback operation: (T,T) => T, @rtlHardwareCallback levelBridge: (T,Int) => T, @rtlHardwareCallback widthFactory: ElabInt => T)
    extends Component {
  require(capacity >= 2 && capacity <= 64, "reduction capacity must be in 2..64")
  require(moduleName != null && moduleName.matches("[A-Za-z_][A-Za-z0-9_]*"),
    "provide an operation-specific module identifier")
  setDefinitionName(moduleName)
  noIoPrefix()
  private val levels: Int = log2Up(capacity)
  private val hasMask = pipelineMaskActual != null
  private val hasBridge = BalancedTree.bridgeEnabled(levelBridge)
  private val needsLevel = hasMask || hasBridge
  require((initialCountActual != null) == needsLevel,
    "initial count is present exactly when a bridge or pipeline mask needs levels")
  require((widthActual != null) == (widthFactory != null),
    "a symbolic payload width requires its type factory")
  val count: Param[Int] = Param(countActual, "COUNT")
  require(count >= 1 && count <= capacity)
  val initialCount: Param[Int] = Param.optional(initialCountActual, 1, "INITIAL_COUNT")
  require(initialCount >= 1 && initialCount <= capacity)
  private val maskLimit: Int = (1 << levels)-1
  val mask: Param[Int] = Param.optional(pipelineMaskActual, 0, "PIPELINE_MASK")
  require(mask >= 0 && mask <= maskLimit)
  val width: Param[Int] = Param.optional(widthActual, 1, "DATA_WIDTH")
  require(width >= 1 && width <= 64)
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
          {
            val currentLevel = initialCount.log2Up - count.log2Up
            for (level <- 0 until levels) {
              if (currentLevel == level) {
                val bridged = dataType()
                bridged := levelBridge(combined(i), level)
                pipeline(bridged, ((mask >> level) & 1).elabNe(0))
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
  private trait BridgePolicy { def enabled: Boolean }
  private object IdentityBridge extends ((Data,Int) => Data) with BridgePolicy {
    val enabled = false
    def apply(value: Data, level: Int): Data = value
  }
  private final class SelectedBridge[T <: Data](@rtlHardwareCallback callback:(T,Int)=>T)
      extends ((T,Int)=>T) with BridgePolicy {
    val enabled = true
    def apply(value:T, level:Int):T=callback(value,level)
  }
  private[lib] def bridgeEnabled(bridge: AnyRef): Boolean = bridge match {
    case policy: BridgePolicy => policy.enabled
    case _ => true // Legacy ordinary callbacks explicitly request a bridge.
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
      capacity: Int, moduleName: String, @rtlHardwareCallback operation: (T,T) => T,
      @rtlHardwareCallback levelBridge: (T,Int) => T, @rtlHardwareCallback widthFactory: ElabInt => T) {
    def component(count: ElabInt, pipelineMask: ElabInt = null,
        clockConfig: ClockDomainConfig = ClockDomainConfig(),
        payloadWidth: ElabInt = null): BalancedTree[T] = {
      require(payloadWidth == null || widthFactory != null,
        "an explicit payload width requires defineWidth")
      val needsLevel = pipelineMask != null || bridgeEnabled(levelBridge)
      new BalancedTree[T](elementType,capacity,count,if(needsLevel) count else null,
        pipelineMask,if(widthFactory==null) null else if(payloadWidth != null) payloadWidth
        else elementType() match {
          case scalar:BitVector => ParameterizedWidth.expressionOf(scalar)
            .map(ElabInt.fromExpression).getOrElse(ElabInt.literal(scalar.getBitsWidth))
          case other => ElabInt.literal(other.getBitsWidth)
        },
        moduleName,clockConfig,operation,levelBridge,widthFactory)
    }

    def reduceBalancedTree(inputs: Seq[T], pipelineMask: ElabInt = null): T =
      reduceBalancedTree(inputs,inputCount(inputs),pipelineMask)

    def reduceBalancedTree(inputs: Seq[T], count: ElabInt, pipelineMask: ElabInt): T = {
      val supplied = inputCount(inputs)
      require(supplied.minimum>=1 && supplied.maximum<=capacity, "input count must be in 1..capacity")
      require(count.minimum>=1 && (count <= supplied).isAlwaysTrue, "live count must fit supplied inputs over the complete domain")
      val representative = inputs match {
        case vector: Vec[_] => Seq(vector(0).asInstanceOf[T])
        case _ => inputs
      }
      val symbolicWidth = inputs match {
        case vector: Vec[_] if widthFactory != null && ParameterizedVec.shapeOf(vector).nonEmpty => vector(0) match {
          case value: BitVector => ParameterizedWidth.expressionOf(value).map(ElabInt.fromExpression)
          case _ => None
        }
        case _ => None
      }
      val expected=if(symbolicWidth.isDefined) widthFactory(symbolicWidth.get) else elementType()
      require(representative.forall(input => sameGeometry(input,expected)), "payload types and widths must match")
      require(representative.flatMap(_.flatten).forall(value =>
        ParameterizedWidth.expressionOf(value).forall(expression => expression.parameters.isEmpty ||
          symbolicWidth.exists(width => ElabInt.fromExpression(expression).elabEq(width).isAlwaysTrue))),
        "symbolic payload geometry requires an authenticated native Vec and width factory")
      val clock=ClockDomain.current
      val tree=component(count,pipelineMask,clock.config,symbolicWidth.orNull)
      tree.io.clk := clock.readClockWire
      tree.io.reset := clock.readResetWire
      tree.io.softReset := clock.readSoftResetWire
      tree.io.enable := clock.readClockEnableWire
      inputs match {
        case vector: Vec[_] if count.elabEq(supplied).isAlwaysTrue =>
          tree.io.terms := vector.asInstanceOf[Vec[T]]
        case vector: Vec[_] =>
          // The public live-count overload selects the low, lane-zero-first
          // prefix. Prove its length against the source before resizing; never
          // use the source or payload's elaborated witness as geometry.
          val terms = Vec(cloneOf(expected), count)
          terms.assignFromBits(vector.asBits.resize(count * widthOfExpr(expected)))
          tree.io.terms := terms
        case _ =>
          val terms=Vec(elementType(),count)
          terms.assignFromBits(Vec(inputs).asBits.resize(count*expected.getBitsWidth))
          tree.io.terms := terms
      }
      val result=if(symbolicWidth.isDefined) widthFactory(symbolicWidth.get) else elementType()
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

  private def nativeScalarFactory[T <: Data](prototype:T): ElabInt=>T = prototype match {
      case value: BitVector if ParameterizedWidth.expressionOf(value).exists(_.parameters.nonEmpty) =>
        if(value.getClass == classOf[UInt]) (w: ElabInt) => UInt(w bits).asInstanceOf[T]
        else if(value.getClass == classOf[SInt]) (w: ElabInt) => SInt(w bits).asInstanceOf[T]
        else if(value.getClass == classOf[Bits]) (w: ElabInt) => Bits(w bits).asInstanceOf[T]
        else null
      case _ => null
    }

  def define[T <: Data](elementType: HardType[T],capacity: Int,moduleName: String)(
      @rtlHardwareCallback operation: (T,T) => T,@rtlHardwareCallback levelBridge: (T,Int) => T = identityBridge[T]): Operation[T] =
    new Operation(elementType,capacity,moduleName,operation,levelBridge,nativeScalarFactory(elementType()))

  def defineWidth[T <: BitVector](@rtlHardwareCallback factory: ElabInt => T,width: Int,
      capacity: Int,moduleName: String)(@rtlHardwareCallback operation: (T,T) => T,
      @rtlHardwareCallback levelBridge: (T,Int) => T = identityBridge[T]): Operation[T] = {
    require(width>=1 && width<=64,"payload width must be in 1..64")
    new Operation(HardType(factory(ElabInt.literal(width))),capacity,moduleName,
      operation,levelBridge,factory)
  }

  /** Option-based entry point. None omits the mask formal; Some(0) retains
    * an overridable zero-default mask. Bridge presence is explicit policy,
    * independent of the callback implementation or its sampled result.
    */
  def reduce[T <: Data](inputs: Seq[T], moduleName: String,
      pipelineMask: Option[Param[Int]] = None,
      @rtlHardwareCallback levelBridge: Option[(T,Int)=>T] = None)(
      @rtlHardwareCallback operation: (T,T)=>T): T = {
    require(pipelineMask != null && levelBridge != null,
      "optional reduction policies must use None rather than null")
    require(!pipelineMask.contains(null) && !levelBridge.contains(null),
      "optional reduction policies cannot contain null")
    val bridge = levelBridge.map(callback => new SelectedBridge(callback):((T,Int)=>T))
      .getOrElse(identityBridge[T])
    reduceBalancedTree(inputs,moduleName,pipelineMask.orNull)(operation,bridge)
  }

  /** Shorthand for the common bridge-free Option policy; legacy ElabInt/null
    * callers retain the existing overload. Use reduce for an optional bridge.
    */
  def reduceBalancedTree[T <: Data](inputs: Seq[T], moduleName: String,
      pipelineMask: Option[ElabInt])(@rtlHardwareCallback operation:(T,T)=>T):T =
    reduce(inputs,moduleName,pipelineMask)(operation)

  // Disambiguate explicit legacy null against the Option overload.
  def reduceBalancedTree[T <: Data](inputs: Seq[T], moduleName: String,
      pipelineMask: Null)(@rtlHardwareCallback operation:(T,T)=>T,
      @rtlHardwareCallback levelBridge: ((T,Int)=>T)*):T = {
    require(levelBridge.size<=1, "at most one legacy level bridge is supported")
    reduceBalancedTree(inputs,moduleName,null:ElabInt)(operation,
      levelBridge.headOption.getOrElse(identityBridge[T]))
  }

  def reduceBalancedTree[T <: Data](inputs: Seq[T],moduleName: String,
      pipelineMask: ElabInt = null)(@rtlHardwareCallback operation: (T,T) => T,
      @rtlHardwareCallback levelBridge: (T,Int) => T = identityBridge[T]): T = {
    val count = inputCount(inputs)
    require(count.minimum>=1 && count.maximum<=64,"input count must be in 1..64")
    val first=inputs(0)
    // Exact native scalar classes have a known symbolic type factory. Subclasses
    // and composites retain their authored type-factory contract.
    val factory = nativeScalarFactory(first)
    new Operation(HardType(cloneOf(first)),scala.math.max(2,count.maximum.toInt),moduleName,
      operation,levelBridge,factory).reduceBalancedTree(inputs,pipelineMask)
  }

}
