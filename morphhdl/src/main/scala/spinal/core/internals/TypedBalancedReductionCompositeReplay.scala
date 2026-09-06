package spinal.core.internals

import java.util.IdentityHashMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import spinal.core._
import TypedBalancedReductionValueEvidence.Evidence

/** Recursive data-layout and native-DAG certificates for composite reduction
  * callbacks. Replay preserves the authoritative native pair/odd-tail schedule;
  * it neither reassociates the callback nor emits an arithmetic RTL template.
  */
private[spinal] object TypedBalancedReductionCompositeReplay {
  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException(s"MORPH-REDUCE-BALANCED-COMPOSITE-$code: $detail")

  private def sameWidth(a: ElaborationIntegerExpression, b: ElaborationIntegerExpression): Boolean =
    (a eq b) || ElabInt.equivalentExactFunction(a, b)
  private val one = () => ElabInt.literal(1).expression

  // Every leaf in a record shares its producing proof. Rechecking that proof
  // recursively for every output field would grow exponentially with tree
  // depth. This memo exists only during one synchronous validation traversal;
  // no observation survives into another callback, replay or public check.
  private val freshnessTraversal = new ThreadLocal[IdentityHashMap[AnyRef, java.lang.Boolean]]()
  private def freshnessRead[A](body: => A): A = {
    val previous = freshnessTraversal.get()
    if (previous != null) body
    else {
      freshnessTraversal.set(new IdentityHashMap[AnyRef, java.lang.Boolean]())
      try body finally freshnessTraversal.remove()
    }
  }
  private def freshOnce(identity: AnyRef)(body: => Unit): Unit = freshnessRead {
    val checks = freshnessTraversal.get()
    if (!checks.containsKey(identity)) {
      body
      checks.put(identity, java.lang.Boolean.TRUE)
    }
  }

  /** Recursive container boundaries matter even when two layouts flatten to
    * the same names. Dynamic nested dimensions need their own publication
    * geometry; a finite native carrier is never accepted as that geometry.
    */
  private sealed trait Layout
  private final case class LeafLayout(kind: Class[_]) extends Layout
  private final case class BundleLayout(kind: Class[_], fields: Vector[(String, Layout)]) extends Layout
  private final case class VecLayout(size: Int, depth: Option[ElaborationIntegerExpression], element: Layout) extends Layout

  private def layout(value: Data): Layout = {
    val active = new IdentityHashMap[Data, java.lang.Boolean]()
    val leaves = new IdentityHashMap[BaseType, java.lang.Boolean]()
    var count = 0
    def visit(value: Data, depth: Int): Layout = {
      count += 1
      if (value == null || depth > 128 || count > 8192)
        fail("SHAPE-LIMIT", "recursive composite layout exceeds its reviewed finite bounds")
      if (active.put(value, java.lang.Boolean.TRUE) != null)
        fail("SHAPE-CYCLE", "recursive data containers cannot contain themselves")
      val result: Layout = value match {
    case leaf: BaseType if Set[Class[_]](classOf[Bool], classOf[Bits], classOf[UInt], classOf[SInt]).contains(leaf.getClass) =>
      if (leaves.put(leaf, java.lang.Boolean.TRUE) != null)
        fail("SHAPE-ALIAS", "distinct composite fields cannot share one scalar identity")
      LeafLayout(leaf.getClass)
    case vector: Vec[_] =>
      if (vector.vec.isEmpty) fail("EMPTY-SHAPE", "nested Vec must have nonempty elements")
      val retained = ParameterizedVec.shapeOf(vector)
      retained.foreach { shape =>
        if (shape.carrierCapacity != vector.vec.size)
          fail("VEC-SHAPE", "nested Vec changed its retained carrier geometry")
      }
      val element = visit(vector.vec.head, depth + 1)
      if (vector.vec.tail.exists(data => !equivalentLayout(element, visit(data, depth + 1))))
        fail("VEC-HETEROGENEOUS", "nested Vec elements must retain one recursive layout")
      VecLayout(vector.vec.size, retained.map(_.depth), element)
    case bundle: Bundle => BundleLayout(bundle.getClass,
      bundle.elements.toVector.map { case (name, data) => name -> visit(data, depth + 1) })
    case _ => fail("SHAPE", "composite data must be a recursive Bundle/Vec of supported native scalar leaves")
      }
      active.remove(value)
      result
    }
    visit(value, 0)
  }
  private def equivalentLayout(a: Layout, b: Layout): Boolean = (a, b) match {
    case (LeafLayout(x), LeafLayout(y)) => x == y
    case (BundleLayout(x, xs), BundleLayout(y, ys)) => x == y && xs.size == ys.size &&
      xs.zip(ys).forall { case ((xn, xv), (yn, yv)) => xn == yn && equivalentLayout(xv, yv) }
    case (VecLayout(x, xd, xe), VecLayout(y, yd, ye)) => x == y && equivalentLayout(xe, ye) &&
      ((xd, yd) match {
        case (Some(p), Some(q)) => sameWidth(p, q)
        case (None, None) => true
        case (Some(p), None) => p.parameters.isEmpty && p.default == x
        case (None, Some(q)) => q.parameters.isEmpty && q.default == y
      })
    case _ => false
  }

  def requireAcyclicShape(value: Data): Unit = { layout(value); () }

  private final class Shape(val template: Data, val expected: Layout,
      val paths: Vector[String], val evidence: Vector[Evidence]) {
    val leafDimensions: Vector[Vector[(Int, ElaborationIntegerExpression)]] = {
      def visit(node: Layout, dimensions: Vector[(Int, ElaborationIntegerExpression)]): Vector[Vector[(Int, ElaborationIntegerExpression)]] = node match {
        case _: LeafLayout => Vector(dimensions)
        case BundleLayout(_, fields) => fields.flatMap(pair => visit(pair._2, dimensions))
        case VecLayout(size, retained, element) =>
          val depth = retained.getOrElse(ElabInt.literal(size).expression)
          (0 until size).toVector.flatMap(index => visit(element, dimensions :+ (index -> depth)))
      }
      visit(expected, Vector.empty)
    }
    private val directions = evidence.map(proof =>
      (proof.value.isInput, proof.value.isOutput, proof.value.isInOut))
    private def containers(value: Data): Vector[Data] = value match {
      case vector: Vec[_] => value +: vector.vec.toVector.flatMap(containers)
      case bundle: Bundle => value +: bundle.elements.toVector.flatMap(pair => containers(pair._2))
      case _ => Vector(value)
    }
    private val identities = containers(template)
    private val vectorShapes = identities.collect { case vector: Vec[_] =>
      vector -> ParameterizedVec.shapeOf(vector)
    }
    def requireValue(value: Data, replacement: Boolean): Unit = freshnessRead {
      if (value == null || !equivalentLayout(expected, layout(value)) ||
          value.flattenLocalName.toVector != paths || value.flatten.size != evidence.size)
        fail("SHAPE-CHANGED", "callback must preserve recursive field names, Vec dimensions and leaf types")
      value.flatten.toVector.zip(evidence).foreach { case (leaf, proof) =>
        if (leaf.isInOut || leaf.isAnalog)
          fail("DIRECTION", "composite reduction leaves must be digital input, output or internal data")
        if (replacement) proof.requireReplacement(leaf) else proof.requireValue(leaf)
      }
      if (!replacement && value.flatten.toVector.map(leaf =>
          (leaf.isInput, leaf.isOutput, leaf.isInOut)) != directions)
        fail("DIRECTION-CHANGED", "a certified composite leaf changed direction")
    }
    def requireFreshness(): Unit = freshOnce(this) {
      requireValue(template, replacement = false)
      val now = containers(template)
      if (now.size != identities.size || now.zip(identities).exists { case (a, b) => a ne b })
        fail("CONTAINER-CHANGED", "recursive composite container identities changed")
      vectorShapes.foreach { case (vector, before) =>
        val unchanged = (before, ParameterizedVec.shapeOf(vector)) match {
          case (None, None) => true
          case (Some(a), Some(b)) => a eq b
          case _ => false
        }
        if (!unchanged) fail("VEC-AUTHORITY-CHANGED", "nested Vec lost its exact retained shape identity")
      }
    }
  }

  private def scalar(kind: AnyRef): BaseType = {
    if (kind eq TypeBool) Bool()
    else if (kind eq TypeBits) Bits()
    else if (kind eq TypeUInt) UInt()
    else if (kind eq TypeSInt) SInt()
    else fail("TYPE", "unsupported replay result type")
  }
  private def attach(value: BaseType, width: ElaborationIntegerExpression): BaseType = {
    value match {
      case bits: BitVector => ParameterizedWidth.attach(bits, ElabInt.fromExpression(width).bits)
      case _ =>
    }
    value
  }
  private def cloneShape(template: Data, widths: Vector[ElaborationIntegerExpression]): Data = {
    val result = ParameterizedWidth.cloneOf(template)
    result.setAsDirectionLess()
    result.flatten.toVector.zip(widths).foreach { case (leaf, width) => attach(leaf, width) }
    result
  }

  private val binaries: Map[Class[_], () => BinaryOperator] = Map(
    classOf[Operator.Bool.And] -> (() => new Operator.Bool.And),
    classOf[Operator.Bool.Or] -> (() => new Operator.Bool.Or),
    classOf[Operator.Bool.Xor] -> (() => new Operator.Bool.Xor),
    classOf[Operator.Bool.Equal] -> (() => new Operator.Bool.Equal),
    classOf[Operator.Bool.NotEqual] -> (() => new Operator.Bool.NotEqual),
    classOf[Operator.Bits.And] -> (() => new Operator.Bits.And),
    classOf[Operator.Bits.Or] -> (() => new Operator.Bits.Or),
    classOf[Operator.Bits.Xor] -> (() => new Operator.Bits.Xor),
    classOf[Operator.UInt.Add] -> (() => new Operator.UInt.Add),
    classOf[Operator.UInt.Sub] -> (() => new Operator.UInt.Sub),
    classOf[Operator.UInt.And] -> (() => new Operator.UInt.And),
    classOf[Operator.UInt.Or] -> (() => new Operator.UInt.Or),
    classOf[Operator.UInt.Xor] -> (() => new Operator.UInt.Xor),
    classOf[Operator.UInt.Smaller] -> (() => new Operator.UInt.Smaller),
    classOf[Operator.UInt.SmallerOrEqual] -> (() => new Operator.UInt.SmallerOrEqual),
    classOf[Operator.UInt.Equal] -> (() => new Operator.UInt.Equal),
    classOf[Operator.UInt.NotEqual] -> (() => new Operator.UInt.NotEqual),
    classOf[Operator.SInt.Add] -> (() => new Operator.SInt.Add),
    classOf[Operator.SInt.Sub] -> (() => new Operator.SInt.Sub),
    classOf[Operator.SInt.And] -> (() => new Operator.SInt.And),
    classOf[Operator.SInt.Or] -> (() => new Operator.SInt.Or),
    classOf[Operator.SInt.Xor] -> (() => new Operator.SInt.Xor),
    classOf[Operator.SInt.Smaller] -> (() => new Operator.SInt.Smaller),
    classOf[Operator.SInt.SmallerOrEqual] -> (() => new Operator.SInt.SmallerOrEqual),
    classOf[Operator.SInt.Equal] -> (() => new Operator.SInt.Equal),
    classOf[Operator.SInt.NotEqual] -> (() => new Operator.SInt.NotEqual)
  )
  private val unaries: Map[Class[_], () => UnaryOperator] = Map(
    classOf[Operator.Bool.Not] -> (() => new Operator.Bool.Not),
    classOf[Operator.Bits.Not] -> (() => new Operator.Bits.Not),
    classOf[Operator.UInt.Not] -> (() => new Operator.UInt.Not),
    classOf[Operator.SInt.Not] -> (() => new Operator.SInt.Not)
  )
  private val unsignedComparisons: Set[Class[_]] = Set(classOf[Operator.UInt.Smaller],
    classOf[Operator.UInt.SmallerOrEqual], classOf[Operator.UInt.Equal], classOf[Operator.UInt.NotEqual])
  private val signedComparisons: Set[Class[_]] = Set(classOf[Operator.SInt.Smaller],
    classOf[Operator.SInt.SmallerOrEqual], classOf[Operator.SInt.Equal], classOf[Operator.SInt.NotEqual])
  private val casts: Map[Class[_], () => Cast] = Map(
    classOf[CastUIntToBits] -> (() => new CastUIntToBits),
    classOf[CastSIntToBits] -> (() => new CastSIntToBits),
    classOf[CastBitsToUInt] -> (() => new CastBitsToUInt),
    classOf[CastBitsToSInt] -> (() => new CastBitsToSInt),
    classOf[CastSIntToUInt] -> (() => new CastSIntToUInt),
    classOf[CastUIntToSInt] -> (() => new CastUIntToSInt),
    classOf[CastBoolToBits] -> (() => new CastBoolToBits)
  )
  private val castInputs: Map[Class[_], AnyRef] = Map(
    classOf[CastUIntToBits] -> TypeUInt, classOf[CastUIntToSInt] -> TypeUInt,
    classOf[CastSIntToBits] -> TypeSInt, classOf[CastSIntToUInt] -> TypeSInt,
    classOf[CastBitsToUInt] -> TypeBits, classOf[CastBitsToSInt] -> TypeBits,
    classOf[CastBoolToBits] -> TypeBool)
  private val muxes: Set[Class[_]] = Set(classOf[BinaryMultiplexerBool],
    classOf[BinaryMultiplexerBits], classOf[BinaryMultiplexerUInt], classOf[BinaryMultiplexerSInt])

  /** A structural semantic key uses operand positions, exact expression
    * classes and typed widths. It never compares sampled native values.
    */
  private final case class Key(kind: Class[_], operand: Option[(Int, Int)],
      width: String, children: Vector[Key])
  private final class Recipe(val kind: AnyRef, val width: ElaborationIntegerExpression,
      val key: Key, val build: (Vector[Vector[BaseType]], IdentityHashMap[Recipe, BaseType]) => BaseType) {
    def replay(inputs: Vector[Vector[BaseType]], memo: IdentityHashMap[Recipe, BaseType]): BaseType = {
      val prior = memo.get(this)
      if (prior != null) prior
      else {
        val value = attach(build(inputs, memo), width)
        memo.put(this, value)
        value
      }
    }
  }

  final class OperatorProof private[TypedBalancedReductionCompositeReplay] (
      val nativeResult: Data,
      val resultWidths: Vector[ElaborationIntegerExpression],
      private val inputShapes: Vector[Shape],
      private val recipes: Vector[Recipe],
      private val observation: TypedBalancedReductionClosedGraph.Observation,
      private val guards: Vector[() => Unit]
  ) {
    private[TypedBalancedReductionCompositeReplay] def operationKey: Vector[Key] = recipes.map(_.key)
    def validateFreshness(): Unit = freshOnce(this) {
      inputShapes.foreach(_.requireFreshness())
      observation.requireUnchanged()
      guards.foreach(_.apply())
    }
    def replay(left: Data, right: Data): Data = {
      validateFreshness()
      if (Component.current ne inputShapes.head.evidence.head.owner)
        fail("OWNER", "composite replay requires the certified component")
      inputShapes(0).requireValue(left, replacement = true)
      inputShapes(1).requireValue(right, replacement = true)
      val inputs = Vector(left.flatten.toVector, right.flatten.toVector)
      val memo = new IdentityHashMap[Recipe, BaseType]()
      val output = cloneShape(nativeResult, resultWidths)
      output.flatten.toVector.zip(recipes).foreach { case (target, recipe) => target.assignFrom(recipe.replay(inputs, memo)) }
      output
    }
  }

  private def certifyOperator(callback: UnvalidatedBalancedCallback, inputs: Vector[Shape]): OperatorProof = {
    if (inputs.size != 2) fail("ARITY", "operator needs two composite values")
    callback.operands.zip(inputs).foreach { case (data, shape) => shape.requireValue(data, replacement = false) }
    if (!equivalentLayout(inputs.head.expected, layout(callback.result)) ||
        callback.result.flattenLocalName.toVector != inputs.head.paths)
      fail("RESULT-SHAPE", "operator result must preserve the input recursive layout")
    val observation = TypedBalancedReductionClosedGraph.observe(callback)
    val owner = inputs.head.evidence.head.owner
    val inputLeaves = new IdentityHashMap[BaseType, (Int, Int)]()
    inputs.zipWithIndex.foreach { case (shape, side) => shape.evidence.zipWithIndex.foreach {
      case (evidence, index) =>
        if (inputLeaves.put(evidence.value, (side, index)) != null)
          fail("OPERAND-ALIAS", "composite pair must retain distinct input leaf identities")
    }}
    val memo = new IdentityHashMap[Expression, Recipe]()
    val guards = ArrayBuffer.empty[() => Unit]

    def recipe(expression: Expression): Recipe = {
      val previous = memo.get(expression)
      if (previous != null) return previous
      val kind = expression.getTypeObject.asInstanceOf[AnyRef]
      def node(width: ElaborationIntegerExpression, children: Vector[Recipe])(
          body: (Vector[Vector[BaseType]], IdentityHashMap[Recipe, BaseType]) => BaseType): Recipe =
        new Recipe(kind, width, Key(expression.getClass, None, width.verilog, children.map(_.key)), body)
      val result: Recipe = expression match {
        case leaf: BaseType if inputLeaves.containsKey(leaf) =>
          val (side, index) = inputLeaves.get(leaf)
          val evidence = inputs(side).evidence(index)
          new Recipe(kind, evidence.width, Key(leaf.getClass, Some(side -> index), evidence.width.verilog, Vector.empty),
            (values, _) => values(side)(index))
        case leaf: BaseType =>
          if (!callback.declarations.exists(_ eq leaf) || leaf.isReg || leaf.hasTag(tagAutoResize) ||
              (leaf.component ne owner) || !leaf.isDirectionLess || (leaf.parentScope ne owner.dslBody))
            fail("LOCAL", "operator local must be a closed root-scope combinational scalar without auto-resize")
          val drivers = callback.assignments.filter(_.finalTarget eq leaf)
          val source = drivers match {
            case Vector(data: DataAssignmentStatement) if data.target eq leaf => recipe(data.source)
            case _ => fail("DRIVER", "operator local must have one complete combinational driver")
          }
          val retained = ParameterizedWidth.expressionOf(leaf)
          val fixed = leaf match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
          if ((kind ne source.kind) || BigInt(leaf.getBitsWidth) != source.width.default ||
              retained.exists(width => !sameWidth(width, source.width)) ||
              (fixed >= 0 && source.width.parameters.nonEmpty && retained.isEmpty))
            fail("WIDTH", "native local must preserve the proved width function, including symbolic clone authority")
          guards += (() => {
            val now = leaf match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
            if (leaf.hasTag(tagAutoResize) ||
                !TypedBalancedReductionValueEvidence.preservesFixedWidth(fixed, now, source.width))
              fail("STALE-WIDTH", "operator local width or auto-resize policy changed")
          })
          source
        case binary: BinaryOperator if binaries.contains(binary.getClass) =>
          val left = recipe(binary.left)
          val right = recipe(binary.right)
          val operandKind = if (unsignedComparisons.contains(binary.getClass)) TypeUInt
            else if (signedComparisons.contains(binary.getClass)) TypeSInt else kind
          if ((left.kind ne operandKind) || (left.kind ne right.kind) || !sameWidth(left.width, right.width))
            fail("BINARY-WIDTH", "native composite arithmetic requires equal authoritative operand widths and types")
          val width = if (kind eq TypeBool) one() else left.width
          node(width, Vector(left, right)) { (values, cache) =>
            val a = left.replay(values, cache)
            val b = right.replay(values, cache)
            val fresh = binaries(binary.getClass)()
            if (kind eq TypeBool) a.wrapLogicalOperator(b, fresh) else a.wrapBinaryOperator(b, fresh)
          }
        case unary: UnaryOperator if unaries.contains(unary.getClass) =>
          val source = recipe(unary.source)
          if (kind ne source.kind) fail("UNARY-TYPE", "native unary operator changes type")
          node(source.width, Vector(source)) { (values, cache) =>
            source.replay(values, cache).wrapUnaryOperator(unaries(unary.getClass)())
          }
        case cast: Cast if casts.contains(cast.getClass) =>
          val source = recipe(cast.input)
          if (source.kind ne castInputs(cast.getClass))
            fail("CAST-TYPE", "native cast source does not retain its exact primitive input type")
          node(source.width, Vector(source)) { (values, cache) =>
            source.replay(values, cache).wrapCast(scalar(kind), casts(cast.getClass)())
          }
        case resize: Resize if Set[Class[_]](classOf[ResizeBits], classOf[ResizeUInt], classOf[ResizeSInt]).contains(resize.getClass) =>
          val source = recipe(resize.input)
          val retained = ParameterizedWidth.resizeExpressionOf(resize)
          val width = retained.getOrElse(ElabInt.literal(resize.size).expression)
          guards += (() => {
            val now = ParameterizedWidth.resizeExpressionOf(resize)
            val unchanged = (retained, now) match {
              case (None, None) => true
              case (Some(a), Some(b)) => a eq b
              case _ => false
            }
            if (!unchanged) fail("STALE-RESIZE", "typed resize authority changed after certification")
          })
          ElabInt.requireAuthoritativeIntegerDomain(width, "composite resize width", "MORPH-REDUCE-BALANCED-COMPOSITE-RESIZE", false)
          if (width.minimum < 1 || kind != source.kind) fail("RESIZE", "resize must retain scalar type and positive width")
          node(width, Vector(source)) { (values, cache) =>
            source.replay(values, cache).asInstanceOf[BitVector].resize(ElabInt.fromExpression(width))
          }
        case mux: BinaryMultiplexer if muxes.contains(mux.getClass) =>
          val condition = recipe(mux.cond)
          val yes = recipe(mux.whenTrue)
          val no = recipe(mux.whenFalse)
          if ((condition.kind ne TypeBool) || (yes.kind ne no.kind) || (kind ne yes.kind) || !sameWidth(yes.width, no.width))
            fail("MUX", "native selector must preserve exact arm types and width functions")
          node(yes.width, Vector(condition, yes, no)) { (values, cache) =>
            val cond = condition.replay(values, cache).asInstanceOf[Bool]
            val a = yes.replay(values, cache)
            val b = no.replay(values, cache)
            val fresh = a.newBinaryMultiplexerExpression()
            if (fresh.getClass != mux.getClass) fail("MUX-CLASS", "native selector constructor changed")
            a.wrapWithWeakClone(a.newMultiplexer(cond, a, b, fresh))
          }
        case _ => fail("EXPRESSION", s"native expression ${expression.getClass.getName} has no composite replay width proof")
      }
      memo.put(expression, result)
      result
    }
    val outputs = callback.result.flatten.toVector.map(recipe)
    def dependencies(key: Key): Set[(Int, Int)] =
      key.operand.toSet ++ key.children.flatMap(child => dependencies(child)).toSet
    outputs.zipWithIndex.foreach { case (output, index) =>
      val active = inputs.head.leafDimensions(index)
      dependencies(output.key).foreach { case (side, leafIndex) =>
        inputs(side).leafDimensions(leafIndex).foreach { case (sourceIndex, sourceDepth) =>
          if (BigInt(sourceIndex) >= sourceDepth.minimum && !active.exists {
              case (targetIndex, targetDepth) => targetIndex >= sourceIndex && sameWidth(targetDepth, sourceDepth)
            })
            fail("INACTIVE-DEPENDENCY", "a live result field can read a nested Vec lane outside its logical depth")
        }
      }
    }
    outputs.zip(inputs.head.evidence).foreach { case (output, expected) =>
      if ((output.kind ne expected.kind) || !sameWidth(output.width, expected.width))
        fail("RESULT-WIDTH", "operator must preserve every independent field width over the full domain")
    }
    val proof = new OperatorProof(callback.result, outputs.map(_.width), inputs, outputs, observation, guards.toVector)
    proof.validateFreshness()
    proof
  }

  final class BridgeProof private[TypedBalancedReductionCompositeReplay] (
      val nativeResult: Data,
      private val input: Shape,
      val leaves: Vector[TypedBalancedReductionBridgeReplay.Proof],
      private val observation: TypedBalancedReductionClosedGraph.Observation
  ) {
    val registerCount: Int = leaves.head.registerCount
    def validateFreshness(): Unit = freshOnce(this) {
      input.requireFreshness()
      leaves.foreach(_.validateFreshness())
      observation.requireUnchanged()
    }
    def sameBehavior(other: BridgeProof): Boolean = {
      validateFreshness()
      other.validateFreshness()
      equivalentLayout(input.expected, other.input.expected) && leaves.size == other.leaves.size &&
        leaves.zip(other.leaves).forall { case (a, b) => a.sameBehavior(b) }
    }
    def replay(value: Data): Data = {
      validateFreshness()
      if (Component.current ne input.evidence.head.owner)
        fail("OWNER", "composite bridge replay requires the certified component")
      input.requireValue(value, replacement = true)
      if (leaves.forall(_.registerCount == 0)) value
      else {
        val result = cloneShape(nativeResult, leaves.map(_.resultWidth))
        result.flatten.toVector.zip(value.flatten.toVector).zip(leaves).foreach {
          case ((target, source), proof) => target.assignFrom(proof.replay(source))
        }
        result
      }
    }
  }

  private def certifyBridge(callback: UnvalidatedBalancedCallback, input: Shape): BridgeProof = {
    input.requireValue(callback.operands.head, replacement = false)
    if (!equivalentLayout(input.expected, layout(callback.result)) || callback.result.flattenLocalName.toVector != input.paths)
      fail("BRIDGE-SHAPE", "bridge must preserve all recursive fields and Vec dimensions")
    val observation = TypedBalancedReductionClosedGraph.observe(callback)
    val usedDeclarations = new IdentityHashMap[BaseType, java.lang.Boolean]()
    val usedAssignments = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
    val results = callback.result.flatten.toVector
    val leaves = results.zip(input.evidence).map { case (result, evidence) =>
      val visited = new IdentityHashMap[Expression, java.lang.Boolean]()
      val declarations = new IdentityHashMap[BaseType, java.lang.Boolean]()
      val assignments = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
      def walk(value: Expression): Unit = {
        if (visited.put(value, java.lang.Boolean.TRUE) != null) return
        value match {
          case leaf: BaseType if leaf eq evidence.value =>
          case leaf: BaseType =>
            if (!callback.declarations.exists(_ eq leaf))
              fail("BRIDGE-CROSS-FIELD", "bridge data paths must preserve their exact corresponding input field")
            declarations.put(leaf, java.lang.Boolean.TRUE)
            usedDeclarations.put(leaf, java.lang.Boolean.TRUE)
            callback.assignments.filter(_.finalTarget eq leaf).foreach { assignment =>
              assignments.put(assignment, java.lang.Boolean.TRUE)
              usedAssignments.put(assignment, java.lang.Boolean.TRUE)
              walk(assignment.source)
            }
          case expression => expression.foreachExpression(walk)
        }
      }
      walk(result)
      val partition = UnvalidatedBalancedCallback(callback.ordinal, Vector(evidence.value), result,
        callback.declarations.filter(declarations.containsKey), callback.assignments.filter(assignments.containsKey))
      TypedBalancedReductionBridgeReplay.certify(partition, evidence)
    }
    if (usedDeclarations.size != callback.declarations.size || usedAssignments.size != callback.assignments.size)
      fail("BRIDGE-EFFECT", "bridge contains effects outside its leaf paths")
    if (leaves.isEmpty || leaves.exists(_.registerCount != leaves.head.registerCount))
      fail("BRIDGE-LATENCY", "all composite leaves must advance in lockstep through equal register counts")
    val clocks = callback.declarations.filter(_.isReg).map(_.clockDomain)
    if (clocks.nonEmpty && clocks.exists(_ ne clocks.head))
      fail("BRIDGE-CLOCK", "composite registers must share their exact native clock domain")
    val proof = new BridgeProof(callback.result, input, leaves, observation)
    proof.validateFreshness()
    proof
  }

  final class Stage private[TypedBalancedReductionCompositeReplay] (
      val geometry: TypedBalancedReductionStage,
      val operators: Vector[OperatorProof], val bridges: Vector[BridgeProof]) {
    val registerCountPerRow: Int = bridges.head.registerCount
  }
  final class Certificate[T <: Data] private[TypedBalancedReductionCompositeReplay] (
      val captured: UnvalidatedBalancedReduction[T], val stages: Vector[Stage],
      private val inputs: Vector[Shape], private val result: Shape,
      private val observations: Vector[TypedBalancedReductionClosedGraph.Observation],
      private val native: ElabBalancedReduction.Native[T], private val counts: Set[Int]) {
    def requirePublicationCertificate(): Nothing =
      fail("PUBLICATION-UNVALIDATED", "native composite graph evidence alone is not post-phase publication permission")

    def requireFreshness(): Unit = freshOnce(this) {
      if (ParameterizedVec.shapeOf(captured.vector).forall(_ ne captured.shape) ||
          captured.vector.vec.size != inputs.size || captured.vector.vec.zip(inputs).exists { case (a, b) => a ne b.template })
        fail("SHAPE-CHANGED", "captured composite receiver or retained shape changed")
      inputs.foreach(_.requireFreshness())
      observations.foreach(_.requireUnchanged())
      result.requireFreshness()
    }
    def latencyFor(count: Int): Int = {
      requireFreshness()
      if (!counts.contains(count)) fail("COUNT", "count is outside the exact captured domain")
      stages.take((BigInt(count) - 1).bitLength).map(_.registerCountPerRow).sum
    }
    def replay(values: Vector[T]): T = {
      requireFreshness()
      if (Component.current ne inputs.head.evidence.head.owner)
        fail("OWNER", "whole-composite replay requires the certified component")
      if (values == null || !counts.contains(values.size)) fail("COUNT", "replay requires an admitted nonempty count")
      values.foreach(value => inputs.head.requireValue(value, replacement = true))
      val operator = stages.flatMap(_.operators).headOption
      var calls = 0
      val bridges = mutable.Map.empty[Int, Int].withDefaultValue(0)
      val output = native(values, (a: T, b: T) => {
        calls += 1
        operator.getOrElse(fail("SINGLETON", "singleton certificate has no operator")).replay(a, b).asInstanceOf[T]
      }, (value: T, level: Int) => {
        if (level < 0 || level >= stages.size) fail("LEVEL", "native helper requested an uncertified level")
        bridges(level) += 1
        stages(level).bridges.head.replay(value).asInstanceOf[T]
      })
      val depth = (BigInt(values.size) - 1).bitLength
      if (calls != values.size - 1 || bridges.keySet != (0 until depth).toSet ||
          bridges.exists { case (level, count) => count != ((BigInt(values.size) - 1) / (BigInt(1) << (level + 1)) + 1).toInt })
        fail("NATIVE-SCHEDULE", "native replay changed its certified pair and odd-tail schedule")
      output
    }
  }

  def capture[T <: Data](vector: Vec[T], op: (T, T) => T,
      bridge: (T, Int) => T, native: ElabBalancedReduction.Native[T]): Certificate[T] = {
    if (vector == null || op == null || bridge == null || native == null)
      fail("NULL", "receiver and native callbacks are required")
    val retained = ParameterizedVec.shapeOf(vector).getOrElse(fail("SHAPE", "receiver has no exact typed Vec shape"))
    val plan = TypedBalancedReductionPlan.forVec(vector).get
    val inputShapes = vector.vec.toVector.map { value =>
      val recursiveLayout = layout(value)
      val evidence = value.flatten.toVector.map(TypedBalancedReductionValueEvidence.input)
      new Shape(value, recursiveLayout, value.flattenLocalName.toVector, evidence)
    }
    if (inputShapes.isEmpty) fail("EMPTY", "composite Vec cannot be empty")
    inputShapes.foreach { shape =>
      if (!equivalentLayout(shape.expected, inputShapes.head.expected) || shape.paths != inputShapes.head.paths)
        fail("INPUT-SHAPE", "receiver elements differ in recursive layout")
      shape.evidence.zip(retained.elementLeaves).foreach { case (proof, leaf) =>
        if ((proof.kind ne leaf.typeObject) || !sameWidth(proof.width, leaf.width))
          fail("INPUT-WIDTH", "receiver leaf lost its retained independent width")
      }
    }
    val values = new IdentityHashMap[Data, Shape]()
    inputShapes.foreach(shape => values.put(shape.template, shape))
    val observations = ArrayBuffer.empty[TypedBalancedReductionClosedGraph.Observation]
    val operators = mutable.Map.empty[Int, OperatorProof]
    val bridges = mutable.Map.empty[Int, BridgeProof]
    def statements(): Vector[Statement] = {
      val result = ArrayBuffer.empty[Statement]
      vector.component.dslBody.walkStatements(result += _)
      result.toVector
    }
    var previousStatements = statements()
    def evidenceOf(value: Data): Shape = Option(values.get(value)).getOrElse(
      fail("PROVENANCE", "callback operand is not an exact certified input or prior result"))
    val captured = TypedBalancedReductionCapture(vector, op, bridge, native, (callback: UnvalidatedBalancedCallback) => freshnessRead {
      val current = statements()
      def inventory(values: Seq[Statement]): IdentityHashMap[Statement, java.lang.Boolean] = {
        val result = new IdentityHashMap[Statement, java.lang.Boolean]()
        values.foreach(value => result.put(value, java.lang.Boolean.TRUE))
        result
      }
      val beforeSet = inventory(previousStatements)
      val nowSet = inventory(current)
      val callbackSet = inventory(callback.declarations ++ callback.assignments)
      if (previousStatements.exists(old => !nowSet.containsKey(old)) ||
          current.exists(value => !beforeSet.containsKey(value) && !callbackSet.containsKey(value)))
        fail("STATEMENT-EFFECT", "callback changed statements outside its closed native data graph")
      previousStatements = current
      observations.foreach(_.requireUnchanged())
      val evidence = callback.operands.size match {
        case 2 =>
          val proof = certifyOperator(callback, callback.operands.map(evidenceOf))
          operators(callback.ordinal) = proof
          callback.result.flatten.indices.toVector.map(index => TypedBalancedReductionValueEvidence.fromComposite(proof, index))
        case 1 =>
          val proof = certifyBridge(callback, evidenceOf(callback.operands.head))
          bridges(callback.ordinal) = proof
          proof.leaves.map(TypedBalancedReductionValueEvidence.fromBridge)
        case _ => fail("ARITY", "native callback has unsupported arity")
      }
      val shape = new Shape(callback.result, layout(callback.result), callback.result.flattenLocalName.toVector, evidence)
      values.put(callback.result, shape)
      observations += TypedBalancedReductionClosedGraph.observe(callback)
    })
    freshnessRead {
    val stages = captured.plan.stages.map { geometry =>
      val rows = captured.rows.filter(_.level == geometry.level)
      val rowOperators = rows.flatMap(_.operator.map(record => operators(record.ordinal)))
      val rowBridges = rows.map(row => bridges(row.bridge.ordinal))
      if (rowBridges.isEmpty || rowBridges.exists(proof => !rowBridges.head.sameBehavior(proof)))
        fail("BRIDGE-NONUNIFORM", "pairs and odd tails at each level must have identical composite bridge behavior")
      new Stage(geometry, rowOperators, rowBridges)
    }
    val allOperators = stages.flatMap(_.operators)
    if (allOperators.nonEmpty && allOperators.exists(_.operationKey != allOperators.head.operationKey))
      fail("OPERATOR-NONUNIFORM", "all pair callbacks must retain the exact same closed composite operator DAG")
    val clocks = captured.rows.flatMap(_.bridge.declarations.filter(_.isReg).map(_.clockDomain))
    if (clocks.nonEmpty && clocks.exists(_ ne clocks.head))
      fail("CLOCK-NONUNIFORM", "all composite stages must share their native clock domain")
    val counts = plan.count.expression.exactDomain match {
      case Some(domain) => ElabInt.activeDomainEvaluations(domain, "composite balanced counts", None).map(_._2.toInt).toSet
      case None => Set(plan.count.expression.default.toInt)
    }
    val certificate = new Certificate(captured, stages, inputShapes, evidenceOf(captured.result), observations.toVector, native, counts)
    certificate.requireFreshness()
    certificate
    }
  }
}
