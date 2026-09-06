package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}
import java.util.IdentityHashMap

import scala.collection.Seq
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core.internals.{
  BitsBitAccessFixed,
  BitsRangedAccessFixed,
  CastBitsToEnum,
  CastBitsToSInt,
  CastBitsToUInt,
  CastBoolToBits,
  CastEnumToBits,
  CastSIntToBits,
  CastUIntToBits,
  DataAssignmentStatement,
  Expression,
  Multiplexer,
  Operator,
  Resize,
  ResizeUInt,
  TypeBits,
  TypeBool,
  TypeEnum,
  TypeSInt,
  TypeStruct,
  TypeUInt,
  UIntBitAccessFixed,
  UIntLiteral,
  WhenStatement
}

/** One ordered native leaf in a typed Vec element.
  *
  * The packed backend keeps this factorized layout beside the ordinary Vec.
  * In particular it does not collapse an independently parameterized element
  * width and depth into one lossy single-root [[ElabInt]].
  */
private[spinal] final case class ParameterizedVecLeafShape(
    path: String,
    typeObject: AnyRef,
    width: ElaborationIntegerExpression
)

/** One exact recursive Vec dimension. The first retained axis is the
  * outermost; native element zero occupies the least significant slice.
  */
private[spinal] final case class ParameterizedVecDimensionShape(
    depth: ElaborationIntegerExpression,
    witnessDepth: Int,
    carrierCapacity: Int
)

/** One scalar field path through an element's recursive Bundle/Vec shape.
  *
  * Field names come directly from native MultiData element ownership. Vec
  * indices never become field names: every intervening Vec contributes one
  * dimension instead. `carrierLeafIndices` indexes the unchanged flattened
  * native element, in row-major order with the last Vec axis varying fastest.
  * These exact indices also retain the direction-bearing BaseType identities;
  * direction must be observed after the user applies in/out/master/slave.
  */
private[spinal] final case class ParameterizedVecFieldShape(
    path: Vector[String],
    dimensions: Vector[ParameterizedVecDimensionShape],
    typeObject: AnyRef,
    width: ElaborationIntegerExpression,
    carrierLeafIndices: Vector[Int]
) {
  def geometryExpressions: Vector[ElaborationIntegerExpression] =
    dimensions.map(_.depth) :+ width

  def packedWidthVerilog: String =
    geometryExpressions.map(value => s"(${value.verilog})").mkString(" * ")

  def packedWidthDefault: BigInt =
    geometryExpressions.map(_.default).product

  def packedWidthMinimum: BigInt =
    geometryExpressions.map(_.minimum).product

  def packedWidthMaximum: BigInt =
    geometryExpressions.map(_.maximum).product
}

/** Exact native packing topology. Records preserve native declaration order;
  * arrays preserve element-major ordering. The publication bridge uses this
  * tree to compute symbolic sibling offsets without reconstructing shape from
  * flattened names or concrete witness offsets.
  */
private[spinal] sealed trait ParameterizedVecLayoutNode
private[spinal] final case class ParameterizedVecLayoutScalar(
    path: Vector[String],
    width: ElaborationIntegerExpression,
    typeObject: AnyRef
) extends ParameterizedVecLayoutNode
private[spinal] final case class ParameterizedVecLayoutRecord(
    children: Vector[ParameterizedVecLayoutNode]
) extends ParameterizedVecLayoutNode
private[spinal] final case class ParameterizedVecLayoutArray(
    dimension: ParameterizedVecDimensionShape,
    element: ParameterizedVecLayoutNode
) extends ParameterizedVecLayoutNode

/** Logical shape retained beside one ordinary native Vec.
  *
  * `carrierCapacity` is the audited finite native capacity used to let the
  * inherited Vec algorithms build and validate a representative graph.  It is
  * deliberately distinct from both the public symbolic `depth` and its
  * `witnessDepth`; publication must use `depth`, never this capacity.
  */
private[spinal] final case class ParameterizedVecShape(
    depth: ElaborationIntegerExpression,
    witnessDepth: Int,
    carrierCapacity: Int,
    elementLeaves: Vector[ParameterizedVecLeafShape],
    elementFields: Vector[ParameterizedVecFieldShape],
    elementLayout: ParameterizedVecLayoutNode,
    sourceLocation: Option[String]
) {
  def elementWidthDefault: BigInt =
    elementLeaves.foldLeft(BigInt(0))((sum, leaf) => sum + leaf.width.default)

  def elementWidthMinimum: BigInt =
    elementLeaves.foldLeft(BigInt(0))((sum, leaf) => sum + leaf.width.minimum)

  def elementWidthMaximum: BigInt =
    elementLeaves.foldLeft(BigInt(0))((sum, leaf) => sum + leaf.width.maximum)

  /** Logical recursive geometry, distinct from the finite flattened carrier
    * widths above. Independent nested dimensions remain separate factors.
    */
  def logicalElementWidthDefault: BigInt = elementFields.map(_.packedWidthDefault).sum
  def logicalElementWidthMinimum: BigInt = elementFields.map(_.packedWidthMinimum).sum
  def logicalElementWidthMaximum: BigInt = elementFields.map(_.packedWidthMaximum).sum
  def logicalElementWidthVerilog: String =
    elementFields.map { field =>
      if (field.dimensions.isEmpty) s"(${field.width.verilog})"
      else s"(${field.packedWidthVerilog})"
    }.mkString(" + ")

  def geometryExpressions: Vector[ElaborationIntegerExpression] =
    depth +: elementFields.flatMap(_.geometryExpressions)

  /** Factorized Verilog-2001 expression for one logical element.  Keeping
    * this textual composition here avoids constructing an [[ElabInt]] across
    * independent parameter roots merely to publish a packed Vec boundary.
    */
  def elementWidthVerilog: String =
    elementLeaves.map(leaf => s"(${leaf.width.verilog})").mkString(" + ")

  /** Factorized Verilog-2001 expression for the flattened packed boundary. */
  def totalPackedWidthVerilog: String =
    s"(${depth.verilog}) * ($logicalElementWidthVerilog)"

  def parameters: Vector[ElaborationIntegerParameter] =
    geometryExpressions.flatMap(_.parameters)
      .groupBy(_.name)
      .toVector
      .map(_._2.head)
      .sortBy(_.name)
}

/** Exact mapping from one descendant Vec's native leaf to its enclosing
  * retained Vec. Both element/leaf ordinals refer to native carrier order.
  */
private[spinal] final case class ParameterizedVecNestedLeafProjection(
    elementIndex: Int,
    elementLeafIndex: Int,
    rootElementIndex: Int,
    rootElementLeafIndex: Int
)

/** One structurally owned descendant Vec. The ancestor coordinates include
  * the enclosing Vec's element index and every intervening Vec element index,
  * in outermost-first order. Their length is the descendant's own varying axis
  * position in each enclosing field vector. `fieldPath` contains only exact
  * Bundle/MultiData ownership names before the descendant Vec.
  */
private[spinal] final case class ParameterizedVecNestedProjection(
    root: Vec[_],
    vector: Vec[_],
    fieldPath: Vector[String],
    ancestorCoordinates: Vector[Int],
    leaves: Vector[ParameterizedVecNestedLeafProjection]
)

/** Exact native operations whose witness graph must be generalized by the
  * parameterized Vec publication pass.  Unrecorded witness-wide operations are
  * intentionally outside the supported surface.
  */
private[spinal] sealed trait ParameterizedVecOperation {
  def sourceLocation: Option[String]
}

private[spinal] final case class ParameterizedVecStaticIndex(
    index: Int,
    selected: Data,
    sourceLocation: Option[String]
) extends ParameterizedVecOperation

/** One exact scalar assignment made through an ordinary static Vec index.
  * The native assignment algorithm still owns the operation; these snapshots
  * prevent later driver, source, slice or control mutations being reclassified
  * as an authored static write.
  */
private[spinal] final case class ParameterizedVecStaticWrite(
    elementIndex: Int,
    elementLeafIndex: Int,
    selected: BaseType,
    assignment: DataAssignmentStatement,
    source: Expression,
    target: Expression,
    enclosingConditions: Vector[ParameterizedVecWriteCondition],
    assignmentKind: String,
    sourceLocation: Option[String]
) extends ParameterizedVecOperation

/** Exact per-access native carrier select. Distinct retained select identities
  * keep separate Vec muxes from becoming one backend wrapper solely because
  * callers supplied the same address object.
  */
private[spinal] final case class ParameterizedVecReadSelect(
    select: UInt,
    assignments: Vector[DataAssignmentStatement],
    sources: Vector[Expression],
    carrierWidth: Int
)

private[spinal] final case class ParameterizedVecDynamicAccess(
    address: UInt,
    result: Data,
    assignments: Vector[DataAssignmentStatement],
    writable: Boolean,
    readSelect: Option[ParameterizedVecReadSelect],
    sourceLocation: Option[String]
) extends ParameterizedVecOperation

private[spinal] final case class ParameterizedVecDynamicWrite(
    address: UInt,
    carrierAddress: UInt,
    carrierAddressAssignments: Vector[DataAssignmentStatement],
    decoderOne: UInt,
    decoderOneAssignments: Vector[DataAssignmentStatement],
    decoder: UInt,
    decoderAssignments: Vector[DataAssignmentStatement],
    guards: Vector[ParameterizedVecDynamicWriteGuard],
    elementLeafIndex: Int,
    assignments: Vector[DataAssignmentStatement],
    assignmentKind: String,
    sourceLocation: Option[String]
) extends ParameterizedVecOperation

/** Immutable native control evidence captured before normalization. */
private[spinal] final case class ParameterizedVecWriteCondition(
    whenStatement: WhenStatement,
    condition: Expression,
    whenTrue: Boolean
)

private[spinal] final case class ParameterizedVecDynamicWriteGuard(
    elementIndex: Int,
    enable: Bool,
    enableAssignments: Vector[DataAssignmentStatement],
    whenStatement: WhenStatement,
    assignment: DataAssignmentStatement,
    enclosingConditions: Vector[ParameterizedVecWriteCondition]
)

private[spinal] final case class ParameterizedVecWholeAssignment(
    source: Vec[_],
    assignments: Vector[DataAssignmentStatement],
    assignmentKind: String,
    sourceLocation: Option[String]
) extends ParameterizedVecOperation

private[spinal] final case class ParameterizedVecPackedRead(
    result: Bits,
    carrier: Bits,
    resultAssignments: Vector[DataAssignmentStatement],
    carrierAssignments: Vector[DataAssignmentStatement],
    carrierLeavesLowToHigh: Vector[BaseType],
    sourceLocation: Option[String],
    supportAssignments: Vector[DataAssignmentStatement] = Vector.empty
) extends ParameterizedVecOperation

/** One exact fixed native slice used by MultiData.assignFromBits for a typed
  * Vec carrier leaf.  Offsets are witness/capacity implementation facts only;
  * the public packed geometry remains the retained [[ParameterizedVecShape]].
  */
private[spinal] final case class ParameterizedVecPackedSlice(
    target: BaseType,
    lo: Int,
    width: Int
)

private[spinal] final case class ParameterizedVecPackedAssignment(
    source: Bits,
    carrier: Bits,
    assignments: Vector[DataAssignmentStatement],
    carrierAssignments: Vector[DataAssignmentStatement],
    slices: Vector[ParameterizedVecPackedSlice],
    sourceLocation: Option[String],
    sourceAliases: Vector[ParameterizedVecPackedSourceAlias] = Vector.empty
) extends ParameterizedVecOperation

private[spinal] final case class ParameterizedVecPackedSourceAlias(
    target: Bits,
    source: Bits,
    assignment: DataAssignmentStatement
)

private[spinal] final case class ParameterizedVecAutoConnect(
    peer: Vec[_],
    assignments: Vector[DataAssignmentStatement],
    sourceLocation: Option[String]
) extends ParameterizedVecOperation

/** Exact definition-formal to parent-actual relation retained on Vecs owned by
  * one typed child instance.  The core Vec algorithm owns only this neutral
  * typed pair; hierarchy publication retains its broader registry separately.
  */
private[spinal] final case class ParameterizedVecFormalBinding(
    formal: ElaborationIntegerParameter,
    actual: ElaborationIntegerExpression
)

private[core] final class ParameterizedVecIdentityRef(
    value: Vec[_],
    queue: ReferenceQueue[Vec[_]]
) extends WeakReference[Vec[_]](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ParameterizedVecIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** Weak identity key for one exact packed value produced from a typed Vec. */
private[core] final class ParameterizedVecPackedIdentityRef(
    value: Bits,
    queue: ReferenceQueue[Bits]
) extends WeakReference[Bits](value, queue) {
  private val identityHash = System.identityHashCode(value)

  override def hashCode(): Int = identityHash

  override def equals(other: Any): Boolean = other match {
    case that: ParameterizedVecPackedIdentityRef =>
      (this eq that) || {
        val left = get()
        val right = that.get()
        (left ne null) && (right ne null) && (left eq right)
      }
    case _ => false
  }
}

/** Identity-retained typed Vec geometry and native-operation evidence.
  *
  * The ordinary [[Vec]] remains the logical data structure and continues to
  * execute the inherited SpinalHDL algorithms.  This registry records only the
  * symbolic shape and exact algorithm boundaries needed to replace the finite
  * native carrier graph with one parameterized packed Verilog-2001 vector.
  */
object ParameterizedVec {
  // Only inherited Vec packing algorithms enter this scope. It prevents an
  // inner Vec from publishing an intermediate witness-width wrapper while the
  // enclosing native MultiData algorithm is constructing its audited carrier.
  private val nativeCarrierDepth = new ThreadLocal[Int] {
    override def initialValue(): Int = 0
  }

  private[core] def usesNativeCarrierGeometry: Boolean = nativeCarrierDepth.get() != 0

  private[core] def withNativeCarrierGeometry[A](body: => A): A = {
    val previous = nativeCarrierDepth.get()
    nativeCarrierDepth.set(previous + 1)
    try body finally nativeCarrierDepth.set(previous)
  }

  private final class Entry(val shape: ParameterizedVecShape) {
    val operations = ArrayBuffer.empty[ParameterizedVecOperation]
    val formalBindings = ArrayBuffer.empty[ParameterizedVecFormalBinding]
  }

  private val queue = new ReferenceQueue[Vec[_]]()
  private val retained =
    mutable.HashMap.empty[ParameterizedVecIdentityRef, Entry]
  private val packedQueue = new ReferenceQueue[Bits]()
  private val retainedPacked =
    mutable.HashMap.empty[ParameterizedVecPackedIdentityRef, ParameterizedVecShape]

  private def reap(): Unit = {
    var reference = queue.poll().asInstanceOf[ParameterizedVecIdentityRef]
    while (reference != null) {
      retained.remove(reference)
      reference = queue.poll().asInstanceOf[ParameterizedVecIdentityRef]
    }
  }

  private def reapPacked(): Unit = {
    var reference = packedQueue.poll().asInstanceOf[ParameterizedVecPackedIdentityRef]
    while (reference != null) {
      retainedPacked.remove(reference)
      reference = packedQueue.poll().asInstanceOf[ParameterizedVecPackedIdentityRef]
    }
  }

  private def retainPacked(
      packed: Bits,
      shape: ParameterizedVecShape
  ): Unit = synchronized {
    if (packed == null || shape == null)
      throw new IllegalArgumentException("typed Vec packed metadata must not be null")
    reapPacked()
    val lookup = new ParameterizedVecPackedIdentityRef(packed, null)
    retainedPacked.get(lookup) match {
      case Some(existing) if equivalentShape(existing, shape) =>
      case Some(existing) =>
        fail(
          "SPINAL-ELAB-VEC-PACKED-ORIGIN-CONFLICT",
          s"one exact packed value carries incompatible Vec depths '${existing.depth.verilog}' and '${shape.depth.verilog}'",
          shape.sourceLocation.orElse(existing.sourceLocation)
        )
      case None =>
        retainedPacked.update(
          new ParameterizedVecPackedIdentityRef(packed, packedQueue),
          shape
        )
    }
  }

  /** Logical Vec shape carried by this exact native packed-result identity.
    *
    * Publication width analysis uses this before descending into the finite
    * full-capacity witness wrapper.  A same-width or same-name Bits value is
    * deliberately not sufficient evidence.
    */
  private[spinal] def packedShapeOf(
      packed: Bits
  ): Option[ParameterizedVecShape] = exactPackedSource(packed).map(_._1)

  private def directPackedShapeOf(packed: Bits): Option[ParameterizedVecShape] = synchronized {
    reapPacked()
    if (packed == null) None
    else retainedPacked.get(new ParameterizedVecPackedIdentityRef(packed, null))
  }

  /** Exact packed width of a value produced by the native Vec packing path.
    * Generic typed folds use this instead of reconstructing width provenance
    * from the native carrier's finite construction capacity.
    */
  private[spinal] def packedWidthExpressionOf(
      packed: Bits
  ): Option[ElaborationIntegerExpression] =
    packedShapeOf(packed).flatMap { shape =>
      expectedPackedWidth(shape).map { expected =>
        ParameterizedWidth.expressionOf(packed) match {
          case Some(retained) if equivalentPackedWidth(retained, expected) =>
            // The exact packed identity can carry a narrower structural
            // projection than the shape from which it was produced.  Keep
            // that identity-owned expression after proving that it denotes
            // the same complete value function; recreating `expected` here
            // would silently discard the projection at the hierarchy edge.
            retained
          case Some(retained) =>
            fail(
              "SPINAL-ELAB-VEC-PACKED-WIDTH-MISMATCH",
              s"packed Vec identity width '${retained.verilog}' does not match its retained logical width '${expected.verilog}'",
              retained.sourceLocation.orElse(shape.sourceLocation)
            )
          case None => expected
        }
      }
    }

  /** Logical packed width when the single-root ElabInt contract can represent
    * it. Independently rooted width and depth remain factorized in the shape.
    */
  private[spinal] def logicalPackedWidthExpressionOf(
      vector: Vec[_]
  ): Option[ElaborationIntegerExpression] =
    shapeOf(vector).flatMap(expectedPackedWidth)

  /** Typed Vec factory. Parameter-free carriers delegate to the exact ordinary
    * Int overload; a symbolic carrier uses its finite maximum only as internal
    * native capacity and retains its exact public depth separately.
    */
  private[spinal] def create[T <: Data](
      size: ElabInt,
      role: String
  )(dataType: => T): Vec[T] = {
    if (size == null)
      throw new IllegalArgumentException("typed Vec size must not be null")

    if (size.isConcrete) {
      return spinal.core.Vec(dataType, size.witness)
    }

    val depth = size.authoritativeProjectedExpression(
      role,
      failureCode = "SPINAL-ELAB-INT-VEC-DEPTH-DOMAIN-INVALID",
      requireProjectedExactExtrema = true
    )
    validateDepth(depth, size.witness, role)
    val capacity = depth.maximum.toInt
    // Delegate the finite audited carrier graph to the same native builder as
    // Vec.fill(Int). Only the logical symbolic depth metadata differs.
    val vector = spinal.core.Vec.fillCarrier(capacity)(dataType)
    attach(vector, depth, size.witness, capacity, depth.sourceLocation)
  }

  /** Retain a literal logical depth when only the element geometry is
    * symbolic.  This hook is deliberately applied after the unchanged Int
    * Vec factory has completed, so ordinary concrete Vec construction remains
    * byte-for-byte on its historical path.
    */
  private[spinal] def attachConcreteDepthIfSymbolicElement[T <: Data](
      vector: Vec[T],
      depth: Int
  ): Vec[T] = {
    if (
      vector != null && vector.vec.nonEmpty &&
      containsSymbolicGeometry(vector.vec.head)
    ) {
      val sourceLocation = vector.vec.head.flatten.iterator
        .flatMap(ParameterizedWidth.sourceLocationOf)
        .toSeq
        .headOption
      attach(
        vector,
        literal(depth),
        witnessDepth = depth,
        carrierCapacity = depth,
        sourceLocation = sourceLocation
      )
    }
    vector
  }

  private def containsSymbolicGeometry(data: Data): Boolean = data match {
    case vector: Vec[_] =>
      shapeOf(vector).exists(_.geometryExpressions.exists(_.parameters.nonEmpty)) ||
        vector.vec.exists(element => containsSymbolicGeometry(element.asInstanceOf[Data]))
    case composite: MultiData =>
      composite.elements.exists { case (_, child) => containsSymbolicGeometry(child) }
    case leaf: BaseType =>
      ParameterizedWidth.expressionOf(leaf).exists(_.parameters.nonEmpty)
    case _ => false
  }

  private def validateDepth(
      depth: ElaborationIntegerExpression,
      witness: Int,
      role: String
  ): Unit = {
    ElabInt.validateExpression(depth, role)
    val exact = depth.exactDomain.getOrElse {
      fail(
        "SPINAL-ELAB-VEC-DEPTH-EXACT-DOMAIN-MISSING",
        s"$role '${depth.verilog}' must retain complete exact-domain evidence before typed Vec construction",
        depth.sourceLocation
      )
    }
    val evaluations = ElabInt.activeDomainEvaluations(
      exact,
      role,
      depth.sourceLocation
    )
    val evaluatedDepths = evaluations.map(_._2)
    val exactRoot = depth.completedParameterRoots match {
      case Vector(root) => root eq exact.root
      case _            => false
    }
    if (
      depth.parameters.isEmpty || depth.generateIndex.nonEmpty ||
      depth.default != BigInt(witness) || depth.minimum < 1 ||
      depth.maximum < depth.minimum || depth.maximum > BigInt(Int.MaxValue) ||
      !exactRoot || evaluatedDepths.isEmpty ||
      evaluatedDepths.exists(value => value < 1 || !value.isValidInt) ||
      evaluatedDepths.min != depth.minimum ||
      evaluatedDepths.max != depth.maximum ||
      !evaluatedDepths.contains(depth.default)
    ) {
      fail(
        "SPINAL-ELAB-INT-VEC-DEPTH-DOMAIN-INVALID",
        s"$role '${depth.verilog}' must retain one exact public parameter root, a positive finite Int-sized domain, and witness $witness",
        depth.sourceLocation
      )
    }
  }

  private def attach[T <: Data](
      vector: Vec[T],
      depth: ElaborationIntegerExpression,
      witnessDepth: Int,
      carrierCapacity: Int,
      sourceLocation: Option[String]
  ): Vec[T] = synchronized {
    if (vector == null)
      throw new IllegalArgumentException("typed Vec target must not be null")
    if (vector.vec.size != carrierCapacity || carrierCapacity < 1) {
      fail(
        "SPINAL-ELAB-VEC-CARRIER-CAPACITY-MISMATCH",
        s"typed Vec carrier has ${vector.vec.size} elements, expected audited capacity $carrierCapacity",
        sourceLocation
      )
    }
    val leaves = elementShape(vector.vec.head, sourceLocation)
    val fields = recursiveElementFields(vector.vec.head, sourceLocation)
    vector.vec.zipWithIndex.foreach { case (element, index) =>
      validateElementShape(element, leaves, index, sourceLocation)
      if (!equivalentFields(fields, recursiveElementFields(element, sourceLocation))) {
        fail(
          "SPINAL-ELAB-VEC-ELEMENT-LAYOUT-MISMATCH",
          s"typed Vec element $index changed its recursive field paths or dimensions",
          sourceLocation
        )
      }
    }
    val shape = ParameterizedVecShape(
      depth = depth,
      witnessDepth = witnessDepth,
      carrierCapacity = carrierCapacity,
      elementLeaves = leaves,
      elementFields = fields,
      elementLayout = recursiveElementLayout(vector.vec.head, Vector.empty, sourceLocation),
      sourceLocation = sourceLocation
    )
    retain(vector, shape)
    vector
  }

  private def elementShape(
      element: Data,
      sourceLocation: Option[String]
  ): Vector[ParameterizedVecLeafShape] = {
    val leaves = element.flatten.toVector
    val paths = element.flattenLocalName.toVector
    if (leaves.isEmpty || paths.size != leaves.size) {
      fail(
        "SPINAL-ELAB-VEC-ELEMENT-SHAPE-INVALID",
        s"typed Vec element exposes ${leaves.size} native leaves and ${paths.size} logical leaf paths",
        sourceLocation
      )
    }
    leaves.zip(paths).zipWithIndex.map { case ((leaf, path), index) =>
      val width = ParameterizedWidth
        .expressionOf(leaf)
        .getOrElse(literal(leaf.getBitsWidth))
      ElabInt.validateExpression(width, s"typed Vec element leaf $index")
      if (
        width.default != BigInt(leaf.getBitsWidth) || width.minimum < 1 ||
        width.maximum < width.minimum
      ) {
        fail(
          "SPINAL-ELAB-VEC-ELEMENT-WIDTH-INVALID",
          s"typed Vec element leaf $index has concrete width ${leaf.getBitsWidth}, but retained width '${width.verilog}' has witness ${width.default} in [${width.minimum}, ${width.maximum}]",
          sourceLocation.orElse(width.sourceLocation)
        )
      }
      ParameterizedVecLeafShape(
        path = Option(path).getOrElse(""),
        typeObject = exactLeafTypeObject(leaf, index, sourceLocation),
        width = width
      )
    }
  }

  /** Traverse exact structural ownership before flattened names lose Vec
    * dimensions and nested Bundle boundaries. Every actual carrier leaf must
    * agree with the first leaf of its field; equal widths alone are insufficient.
    */
  private def recursiveElementFields(
      element: Data,
      sourceLocation: Option[String]
  ): Vector[ParameterizedVecFieldShape] = {
    val fields = mutable.LinkedHashMap.empty[Vector[String], ParameterizedVecFieldShape]
    var leafIndex = 0
    def visit(
        data: Data,
        path: Vector[String],
        dimensions: Vector[ParameterizedVecDimensionShape]
    ): Unit = data match {
      case vector: Vec[_] =>
        if (vector.vec.isEmpty) {
          fail("SPINAL-ELAB-VEC-ELEMENT-SHAPE-INVALID",
            s"typed Vec field '${path.mkString(".")}' contains an empty nested Vec", sourceLocation)
        }
        val dimension = shapeOf(vector) match {
          case Some(shape) =>
            ParameterizedVecDimensionShape(shape.depth, shape.witnessDepth, shape.carrierCapacity)
          case None =>
            ParameterizedVecDimensionShape(literal(vector.vec.size), vector.vec.size, vector.vec.size)
        }
        vector.vec.foreach(child => visit(child.asInstanceOf[Data], path, dimensions :+ dimension))
      case composite: MultiData =>
        composite.elements.foreach { case (name, child) =>
          visit(child, path :+ name, dimensions)
        }
      case leaf: BaseType =>
        val width = ParameterizedWidth.expressionOf(leaf).getOrElse(literal(leaf.getBitsWidth))
        val current = ParameterizedVecFieldShape(path, dimensions,
          exactLeafTypeObject(leaf, leafIndex, sourceLocation), width, Vector(leafIndex))
        fields.get(path) match {
          case Some(previous) =>
            if (!equivalentFieldGeometry(previous, current)) {
              fail("SPINAL-ELAB-VEC-ELEMENT-LAYOUT-MISMATCH",
                s"typed Vec field '${path.mkString(".")}' changed its native leaf type, width or recursive dimensions",
                sourceLocation)
            }
            fields.update(path, previous.copy(carrierLeafIndices = previous.carrierLeafIndices :+ leafIndex))
          case None => fields.update(path, current)
        }
        leafIndex += 1
      case _ =>
        fail("SPINAL-ELAB-VEC-ELEMENT-SHAPE-INVALID",
          "typed Vec recursive shape contains an unsupported native Data node", sourceLocation)
    }
    visit(element, Vector.empty, Vector.empty)
    val result = fields.values.toVector
    if (leafIndex != element.flatten.size || result.exists { field =>
        field.dimensions.map(dimension => BigInt(dimension.carrierCapacity)).product !=
          BigInt(field.carrierLeafIndices.size)
      }) {
      fail("SPINAL-ELAB-VEC-ELEMENT-LAYOUT-MISMATCH",
        "typed Vec recursive fields do not exactly partition the native carrier leaves", sourceLocation)
    }
    result
  }

  private def recursiveElementLayout(
      data: Data,
      path: Vector[String],
      sourceLocation: Option[String]
  ): ParameterizedVecLayoutNode = data match {
    case vector: Vec[_] =>
      val dimension = shapeOf(vector) match {
        case Some(shape) => ParameterizedVecDimensionShape(shape.depth, shape.witnessDepth, shape.carrierCapacity)
        case None => ParameterizedVecDimensionShape(literal(vector.vec.size), vector.vec.size, vector.vec.size)
      }
      ParameterizedVecLayoutArray(dimension,
        recursiveElementLayout(vector.vec.head.asInstanceOf[Data], path, sourceLocation))
    case composite: MultiData =>
      ParameterizedVecLayoutRecord(composite.elements.map { case (name, child) =>
        recursiveElementLayout(child, path :+ name, sourceLocation)
      }.toVector)
    case leaf: BaseType =>
      ParameterizedVecLayoutScalar(path,
        ParameterizedWidth.expressionOf(leaf).getOrElse(literal(leaf.getBitsWidth)),
        exactLeafTypeObject(leaf, 0, sourceLocation))
    case _ =>
      fail("SPINAL-ELAB-VEC-ELEMENT-SHAPE-INVALID",
        "typed Vec packing topology contains an unsupported native Data node", sourceLocation)
  }

  private def exactLeafTypeObject(
      leaf: BaseType,
      index: Int,
      sourceLocation: Option[String]
  ): AnyRef = {
    val kind = leaf.getTypeObject.asInstanceOf[AnyRef]
    if (
      kind == null ||
      !((kind eq TypeBool) || (kind eq TypeBits) || (kind eq TypeUInt) ||
        (kind eq TypeSInt) || (kind eq TypeEnum) || (kind eq TypeStruct))
    ) {
      fail(
        "SPINAL-ELAB-VEC-ELEMENT-TYPE-UNSUPPORTED",
        s"typed Vec element leaf $index lacks a supported exact native type-object identity",
        sourceLocation
      )
    }
    kind
  }

  private def validateElementShape(
      element: Data,
      expected: Vector[ParameterizedVecLeafShape],
      elementIndex: Int,
      sourceLocation: Option[String]
  ): Unit = {
    val actual = elementShape(element, sourceLocation)
    if (actual.size != expected.size) {
      fail(
        "SPINAL-ELAB-VEC-ELEMENT-LAYOUT-MISMATCH",
        s"typed Vec element $elementIndex has ${actual.size} leaves, expected ${expected.size}",
        sourceLocation
      )
    }
    actual.zip(expected).zipWithIndex.foreach {
      case ((found, required), leafIndex)
          if found.path != required.path ||
            (found.typeObject ne required.typeObject) ||
            !ElabInt.equivalentExactFunction(found.width, required.width) =>
        fail(
          "SPINAL-ELAB-VEC-ELEMENT-LAYOUT-MISMATCH",
          s"typed Vec element $elementIndex leaf $leafIndex does not match the retained element type",
          sourceLocation.orElse(found.width.sourceLocation).orElse(required.width.sourceLocation)
        )
      case _ =>
    }
  }

  private def retain(vector: Vec[_], shape: ParameterizedVecShape): Unit = synchronized {
    reap()
    val lookup = new ParameterizedVecIdentityRef(vector, null)
    retained.get(lookup) match {
      case Some(entry) if equivalentShape(entry.shape, shape) => ()
      case Some(entry) =>
        fail(
          "SPINAL-ELAB-VEC-SHAPE-CONFLICT",
          s"one native Vec carries conflicting symbolic depths '${entry.shape.depth.verilog}' and '${shape.depth.verilog}'",
          shape.sourceLocation.orElse(entry.shape.sourceLocation)
        )
      case None =>
        retained.update(new ParameterizedVecIdentityRef(vector, queue), new Entry(shape))
    }
  }

  private[spinal] def shapeOf(vector: Vec[_]): Option[ParameterizedVecShape] = synchronized {
    if (vector == null) None
    else {
      reap()
      retained.get(new ParameterizedVecIdentityRef(vector, null)).map(_.shape)
    }
  }

  /** Project a public collection count at its exact observation site. The
    * host API returns Int, so varying depth must not expose carrier capacity.
    */
  private[spinal] def logicalLengthOf(
      vector: Vec[_],
      operation: String
  ): Option[Int] =
    shapeOf(vector).map { shape =>
      projectedConstant(
        shape.depth,
        operation,
        "logical Vec depth",
        shape.sourceLocation
      ).toInt
    }

  /** Project public packed geometry without traversing the finite carrier.
    * Each factor must be constant; correlated but individually varying factors
    * remain available through typed width metadata instead of an Int witness.
    */
  private[spinal] def logicalBitsWidthOf(
      vector: Vec[_],
      operation: String
  ): Option[Int] =
    shapeOf(vector).map { shape =>
      val depth = projectedConstant(
        shape.depth,
        operation,
        "logical Vec depth",
        shape.sourceLocation
      )
      val elementWidth = shape.elementFields.zipWithIndex.foldLeft(BigInt(0)) { case (sum, (field, index)) =>
        sum + field.geometryExpressions.map { expression =>
          projectedConstant(expression, operation, s"logical Vec element field $index geometry",
            shape.sourceLocation.orElse(expression.sourceLocation))
        }.product
      }
      val total = depth * elementWidth
      if (!total.isValidInt || total < 0) {
        fail(
          "SPINAL-ELAB-VEC-PUBLIC-GEOMETRY-OUT-OF-RANGE",
          s"$operation computes logical Vec packed width $total outside the Scala Int domain",
          shape.sourceLocation
        )
      }
      total.toInt
    }

  private[spinal] def logicalDepthDisplayOf(vector: Vec[_]): Option[String] =
    shapeOf(vector).map(_.depth.verilog)

  private[spinal] def operationsOf(
      vector: Vec[_]
  ): Vector[ParameterizedVecOperation] = synchronized {
    if (vector == null) Vector.empty
    else {
      reap()
      retained
        .get(new ParameterizedVecIdentityRef(vector, null))
        .map(_.operations.toVector)
        .getOrElse(Vector.empty)
    }
  }

  private[spinal] def vectorsOf(component: Component): Vector[Vec[_]] = synchronized {
    if (component == null) Vector.empty
    else {
      reap()
      val liveLeaves = new IdentityHashMap[BaseType, java.lang.Boolean]()
      component.dslBody.walkLeafStatements {
        case leaf: BaseType =>
          liveLeaves.put(leaf, java.lang.Boolean.TRUE)
        case _ =>
      }
      val vectors = ArrayBuffer.empty[Vec[_]]
      retained.keysIterator.foreach { key =>
        val vector = key.get()
        if (
          vector != null && (vector.component eq component) &&
          vector
            .asInstanceOf[MultiData]
            .flatten
            .exists(leaf => liveLeaves.containsKey(leaf))
        ) {
          vectors += vector
        }
      }
      vectors.toVector.sortBy(_.instanceCounter)
    }
  }

  /** Every retained Vec owned by one exact component, including a carrier
    * whose declarations were pruned later. Publication uses this inventory to
    * distinguish a harmless unused Vec from lost live operation/port/hierarchy
    * semantics; emitted names are never used for discovery.
    */
  private[spinal] def retainedVectorsOf(component: Component): Vector[Vec[_]] = synchronized {
    if (component == null) Vector.empty
    else {
      reap()
      val vectors = ArrayBuffer.empty[Vec[_]]
      retained.keysIterator.foreach { key =>
        val vector = key.get()
        if (vector != null && (vector.component eq component)) vectors += vector
      }
      vectors.toVector.sortBy(_.instanceCounter)
    }
  }

  /** Exact recursively owned Vec identities below one native vector. Dynamic
    * selection results are separate Data objects and therefore never appear as
    * descendants of the source vector. Unretained concrete Vecs are included
    * so callers can audit all intervening structural dimensions.
    */
  private[spinal] def nestedVectorsOf(vector: Vec[_]): Vector[Vec[_]] = {
    val result = ArrayBuffer.empty[Vec[_]]
    def visit(data: Data): Unit = data match {
      case child: Vec[_] =>
        result += child
        child.vec.foreach(value => visit(value.asInstanceOf[Data]))
      case composite: MultiData => composite.elements.foreach { case (_, child) => visit(child) }
      case _ =>
    }
    if (vector != null) vector.vec.foreach(value => visit(value.asInstanceOf[Data]))
    result.toVector
  }

  private[spinal] def descendantVectorsOf(vector: Vec[_]): Vector[Vec[_]] =
    nestedVectorsOf(vector)

  private[spinal] def nestedVectorProjection(
      root: Vec[_],
      child: Vec[_]
  ): Option[ParameterizedVecNestedProjection] = {
    if (root == null || child == null || (root eq child)) return None
    val locations = ArrayBuffer.empty[(Vector[String], Vector[Int])]
    def visit(data: Data, path: Vector[String], coordinates: Vector[Int]): Unit = data match {
      case vector: Vec[_] =>
        if (vector eq child) locations += ((path, coordinates))
        vector.vec.zipWithIndex.foreach { case (element, index) =>
          visit(element.asInstanceOf[Data], path, coordinates :+ index)
        }
      case composite: MultiData => composite.elements.foreach { case (name, value) =>
        visit(value, path :+ name, coordinates)
      }
      case _ =>
    }
    root.vec.zipWithIndex.foreach { case (element, index) =>
      visit(element.asInstanceOf[Data], Vector.empty, Vector(index))
    }
    if (locations.isEmpty) return None
    if (locations.size != 1) {
      fail("SPINAL-ELAB-VEC-NESTED-OWNERSHIP-AMBIGUOUS",
        "one descendant Vec occupies multiple native structural positions", shapeOf(root).flatMap(_.sourceLocation))
    }
    val rootLeaves = new IdentityHashMap[BaseType, (Int, Int)]()
    root.vec.zipWithIndex.foreach { case (element, elementIndex) =>
      element.asInstanceOf[Data].flatten.zipWithIndex.foreach { case (leaf, leafIndex) =>
        if (rootLeaves.put(leaf, (elementIndex, leafIndex)) != null) {
          fail("SPINAL-ELAB-VEC-NESTED-OWNERSHIP-AMBIGUOUS",
            "one native carrier leaf occupies multiple enclosing Vec positions", shapeOf(root).flatMap(_.sourceLocation))
        }
      }
    }
    val projected = child.vec.zipWithIndex.flatMap { case (element, elementIndex) =>
      element.asInstanceOf[Data].flatten.zipWithIndex.map { case (leaf, leafIndex) =>
        val position = rootLeaves.get(leaf)
        if (position == null) {
          fail("SPINAL-ELAB-VEC-NESTED-OWNERSHIP-MISMATCH",
            "a descendant Vec leaf is absent from its exact enclosing native carrier", shapeOf(root).flatMap(_.sourceLocation))
        }
        ParameterizedVecNestedLeafProjection(elementIndex, leafIndex, position._1, position._2)
      }
    }.toVector
    Some(ParameterizedVecNestedProjection(root, child, locations.head._1, locations.head._2, projected))
  }

  /** Mechanically attach one typed child formal to every logical Vec owned by
    * that exact component.  Called by the typed ElabInt child adapter after
    * construction; no native Int shadow or emitted-name inference is involved.
    */
  private[spinal] def retainComponentFormal(
      component: Component,
      formal: ElaborationIntegerParameter,
      actual: ElaborationIntegerExpression
  ): Unit = synchronized {
    if (component == null || formal == null || actual == null)
      throw new IllegalArgumentException(
        "typed Vec component formal metadata must not be null"
      )
    ElabInt.validateExpression(actual, s"typed Vec child formal '${formal.name}'")
    reap()
    retained.foreach { case (reference, entry) =>
      val vector = reference.get()
      if (vector != null && (vector.component eq component)) {
        val incoming = ParameterizedVecFormalBinding(formal, actual)
        entry.formalBindings.find(binding => binding.formal eq formal) match {
          case Some(existing) if !ElabInt.equivalentExpression(existing.actual, actual) =>
            fail(
              "SPINAL-ELAB-VEC-FORMAL-ACTUAL-CONFLICT",
              s"typed Vec child formal '${formal.name}' maps to incompatible actual expressions '${existing.actual.verilog}' and '${actual.verilog}'",
              actual.sourceLocation
            )
          case Some(_) =>
          case None    => entry.formalBindings += incoming
        }
      }
    }
  }

  def parametersOf(
      component: Component
  ): Vector[ElaborationIntegerParameter] = {
    val vectors = vectorsOf(component)
    val expressions = vectors.flatMap { vector =>
      val shape = shapeOf(vector).get
      shape.geometryExpressions
    }
    ElabInt.validateParameterRootInventory(
      s"typed Vec component '${component.definitionName}'",
      expressions
    )
    val values = expressions.flatMap(_.parameters)
    values
      .groupBy(_.name)
      .collectFirst {
        case (name, schemas) if schemas.distinct.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
          s"parameter '$name' has conflicting typed Vec declarations on component '${component.definitionName}'"
        )
      }
    values.groupBy(_.name).toVector.map(_._2.head).sortBy(_.name)
  }

  /** Copy typed Vec metadata after the native clone and flattened-width copy. */
  private[spinal] def copyShape[T <: Data](from: Vec[T], to: Vec[T]): Vec[T] = synchronized {
    shapeOf(from).foreach { shape =>
      if (to.vec.size != shape.carrierCapacity) {
        fail(
          "SPINAL-ELAB-VEC-CLONE-CAPACITY-MISMATCH",
          s"typed Vec clone has ${to.vec.size} elements, expected ${shape.carrierCapacity}",
          shape.sourceLocation
        )
      }
      val actual = elementShape(to.vec.head, shape.sourceLocation)
      if (!equivalentLeaves(shape.elementLeaves, actual) ||
          !equivalentFields(shape.elementFields, recursiveElementFields(to.vec.head, shape.sourceLocation))) {
        fail(
          "SPINAL-ELAB-VEC-CLONE-SHAPE-MISMATCH",
          "typed Vec clone changed its logical element layout",
          shape.sourceLocation
        )
      }
      retain(to, shape)
    }
    to
  }

  private[spinal] def validateStaticIndex[T <: Data](
      vector: Vec[T],
      index: Int
  ): Unit = {
    shapeOf(vector).foreach { shape =>
      val projected = ElabInt.projectExpression(
        shape.depth,
        "typed Vec constant index"
      )
      if (index < 0 || BigInt(index) >= projected.minimum) {
        fail(
          "SPINAL-ELAB-VEC-STATIC-INDEX-DOMAIN-INVALID",
          s"constant Vec index $index is not present for every admitted depth of '${shape.depth.verilog}' in [${projected.minimum}, ${projected.maximum}]",
          shape.sourceLocation.orElse(projected.sourceLocation)
        )
      }
      vector.vec(index).flatten.zipWithIndex.foreach { case (leaf, leafIndex) =>
        leaf.compositeAssign match {
          case existing: ParameterizedVecStaticAccessAssign
              if existing.captures(vector, index, leafIndex) =>
          case previous =>
            leaf.compositeAssign = new ParameterizedVecStaticAccessAssign(vector, index, leafIndex, leaf, previous)
        }
      }
      append(
        vector,
        ParameterizedVecStaticIndex(
          index,
          vector.vec(index),
          shape.sourceLocation
        )
      )
    }
  }

  private[core] def recordStaticWrite(
      vector: Vec[_],
      elementIndex: Int,
      elementLeafIndex: Int,
      selected: BaseType,
      assignments: Vector[DataAssignmentStatement],
      kind: AnyRef
  ): Unit = shapeOf(vector).foreach { shape =>
    assignments.foreach { assignment =>
      // Preserve exact authored scalar source identities, just as dynamic
      // access retains its exact native mux targets and decoder identities.
      assignment.source match {
        case value: BaseType => value.dontSimplifyIt()
        case _ =>
      }
      append(vector, ParameterizedVecStaticWrite(elementIndex, elementLeafIndex, selected,
        assignment, assignment.source, assignment.target,
        capturedConditions(assignment.parentScope), kindName(kind), shape.sourceLocation))
    }
  }

  private[spinal] def validateDynamicAddress(
      vector: Vec[_],
      address: UInt
  ): Unit = {
    shapeOf(vector).foreach { shape =>
      if (address == null)
        throw new IllegalArgumentException("typed Vec address must not be null")
      val width = ParameterizedWidth
        .expressionOf(address)
        .getOrElse(literal(address.getWidth))
      ElabInt.validateExpression(width, "typed Vec address width")
      val depth = ElabInt.projectExpression(
        shape.depth,
        "typed Vec dynamic-address depth"
      )
      val projectedWidth = ElabInt.projectExpression(
        width,
        "typed Vec dynamic-address width"
      )
      def covers(addressWidth: BigInt, selectedDepth: BigInt): Boolean =
        addressWidth >= 0 && addressWidth.isValidInt &&
          (addressWidth >= 31 ||
            (BigInt(1) << addressWidth.toInt) >= selectedDepth)
      val pointwiseValid = (depth.exactDomain, projectedWidth.exactDomain) match {
        case (Some(depthDomain), Some(widthDomain))
            if (depthDomain.root eq widthDomain.root) &&
              depthDomain.universe == widthDomain.universe =>
          val depthValues = ElabInt
            .activeDomainEvaluations(
              depthDomain,
              "typed Vec dynamic-address depth",
              depth.sourceLocation
            )
            .toMap
          ElabInt
            .activeDomainEvaluations(
              widthDomain,
              "typed Vec dynamic-address width",
              projectedWidth.sourceLocation
            )
            .forall { case (rootValue, addressWidth) =>
              depthValues.get(rootValue).exists { selectedDepth =>
                covers(addressWidth, selectedDepth)
              }
            }
        case _ =>
          covers(projectedWidth.minimum, depth.maximum)
      }
      if (
        projectedWidth.default != BigInt(address.getWidth) ||
        projectedWidth.minimum < 0 ||
        projectedWidth.maximum < projectedWidth.minimum || !pointwiseValid
      ) {
        fail(
          "SPINAL-ELAB-VEC-ADDRESS-CAPACITY-INVALID",
          s"Vec address width '${width.verilog}' cannot select every element of depth '${shape.depth.verilog}' over its complete domain",
          ParameterizedWidth.sourceLocationOf(address).orElse(shape.sourceLocation)
        )
      }
    }
  }

  private[spinal] def recordDynamicAccess(
      vector: Vec[_],
      address: UInt,
      result: Data,
      writable: Boolean
  ): Unit =
    shapeOf(vector).foreach { shape =>
      // Keep each exact typed mux result addressable so publication can consume
      // its retained assignment identity. This prevents native optimization
      // from coalescing it with an unrelated expression or procedural block.
      // Retain unused sibling leaves too: selecting one Bundle field must not
      // partially erase the exact native read evidence for the other fields.
      // Stale/replaced assignments still fail the publication identity audit.
      // The ordinary concrete Vec path has no retained shape and is unchanged.
      // Native pruning protects a vital scalar only when it is named. Give
      // the exact result aggregate a weak name so nested field ownership still
      // supplies readable leaf names and later user naming remains stronger.
      result.setName("morphhdl_typed_vec_access_result", weak = true)
      result.flatten.foreach(_.dontSimplifyIt().setAsVital())
      val assignments = assignmentStatementsOf(result)
      val selections = assignments.collect { case assignment if assignment.source.isInstanceOf[Multiplexer] =>
        assignment.source.asInstanceOf[Multiplexer].select
      }.foldLeft(Vector.empty[Expression]) { (known, selection) =>
        if (known.exists(_ eq selection)) known else known :+ selection
      }
      val readSelect = if (shape.carrierCapacity <= 1) None else selections match {
        case Vector(selection: UInt) if selection ne address =>
          val drivers = assignmentStatementsOf(selection).filter(_.finalTarget eq selection)
          if (drivers.size != 1) {
            fail("SPINAL-ELAB-VEC-READ-SELECT-EVIDENCE-MISMATCH",
              "typed Vec read lost its exact single native carrier-select driver", shape.sourceLocation)
          }
          Some(ParameterizedVecReadSelect(selection, drivers, drivers.map(_.source), log2Up(shape.carrierCapacity)))
        case _ =>
          fail("SPINAL-ELAB-VEC-READ-SELECT-EVIDENCE-MISMATCH",
            "typed Vec read lost its exact distinct per-access native select identity", shape.sourceLocation)
      }
      append(
        vector,
        ParameterizedVecDynamicAccess(address, result, assignments, writable, readSelect, shape.sourceLocation)
      )
    }

  private[spinal] def recordDynamicWrite(
      vector: Vec[_],
      address: UInt,
      carrierAddress: UInt,
      decoderOne: UInt,
      decoder: UInt,
      enables: Seq[Bool],
      targets: Seq[BaseType],
      elementLeafIndex: Int,
      assignments: Vector[DataAssignmentStatement],
      kind: AnyRef
  ): Unit =
    shapeOf(vector).foreach { shape =>
      if (elementLeafIndex < 0 || elementLeafIndex >= shape.elementLeaves.size) {
        fail(
          "SPINAL-ELAB-VEC-DYNAMIC-WRITE-LAYOUT-MISMATCH",
          s"dynamic Vec write leaf $elementLeafIndex is outside the ${shape.elementLeaves.size}-leaf element layout",
          shape.sourceLocation
        )
      }
      if (
        carrierAddress == null || decoderOne == null || decoder == null ||
        enables.size != decoder.getBitsWidth ||
        enables.size < shape.carrierCapacity ||
        targets.size != shape.carrierCapacity
      ) {
        fail(
          "SPINAL-ELAB-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
          s"dynamic Vec write leaf $elementLeafIndex lost its exact carrier address, decoder or authoritative ${shape.carrierCapacity}-of-${decoder.getBitsWidth} guard geometry",
          shape.sourceLocation
        )
      }
      val carrierAddressAssignments =
        if (carrierAddress eq address) Vector.empty
        else assignmentStatementsOf(carrierAddress)
      if (carrierAddress ne address) {
        val projections = carrierAddressAssignments.filter(
          _.finalTarget eq carrierAddress
        )
        val carrierWidth = log2Up(shape.carrierCapacity)
        if (
          projections.size != 1 || !(projections.head.source match {
            case resize: ResizeUInt =>
              (resize.input eq address) && resize.size == carrierWidth
            case _ => false
          })
        ) {
          fail(
            "SPINAL-ELAB-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
            "dynamic Vec write carrier address is not the exact native carrier-width Resize of its retained address",
            shape.sourceLocation
          )
        }
      }
      val decoderOneAssignments = assignmentStatementsOf(decoderOne)
        .filter(_.finalTarget eq decoderOne)
      val exactDecoderOne = decoderOneAssignments match {
        case Vector(driver) =>
          driver.source match {
            case literal: UIntLiteral =>
              !literal.hasPoison() && literal.value == BigInt(1) &&
              decoderOne.getBitsWidth == 1
            case _ => false
          }
        case _ => false
      }
      if (!exactDecoderOne) {
        fail(
          "SPINAL-ELAB-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
          "dynamic Vec write decoder did not retain the exact native one-literal identity",
          shape.sourceLocation
        )
      }
      val decoderAssignments = assignmentStatementsOf(decoder)
        .filter(_.finalTarget eq decoder)
      val exactDecoder = decoderAssignments match {
        case Vector(driver) =>
          driver.source match {
            case shift: Operator.UInt.ShiftLeftByUInt =>
              (shift.right eq carrierAddress) && (shift.left eq decoderOne)
            case _ => false
          }
        case _ => false
      }
      if (!exactDecoder) {
        fail(
          "SPINAL-ELAB-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
          "dynamic Vec write decoder is not the exact native one shifted by its retained carrier address",
          shape.sourceLocation
        )
      }
      val guards = targets
        .zip(enables)
        .zipWithIndex
        .map { case ((target, enable), elementIndex) =>
          val matching = assignments.filter(_.finalTarget eq target)
          val enableAssignments = assignmentStatementsOf(enable)
            .filter(_.finalTarget eq enable)
          val exactEnable = enableAssignments match {
            case Vector(driver) =>
              driver.source match {
                case access: UIntBitAccessFixed =>
                  (access.source eq decoder) && access.bitId == elementIndex
                case _ => false
              }
            case _ => false
          }
          val parentWhen = matching.headOption.flatMap { assignment =>
            Option(assignment.parentScope)
              .flatMap(scope => Option(scope.parentStatement))
              .collect { case value: WhenStatement => value }
          }
          if (
            matching.size != 1 || !exactEnable || parentWhen.isEmpty ||
            (parentWhen.get.cond ne enable)
          ) {
            fail(
              "SPINAL-ELAB-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
              s"dynamic Vec write carrier element $elementIndex does not retain one exact decoder-bit When guard",
              shape.sourceLocation
            )
          }
          ParameterizedVecDynamicWriteGuard(
            elementIndex,
            enable,
            enableAssignments,
            parentWhen.get,
            matching.head,
            capturedWriteConditions(parentWhen.get)
          )
        }
        .toVector
      append(
        vector,
        ParameterizedVecDynamicWrite(
          address,
          carrierAddress,
          carrierAddressAssignments,
          decoderOne,
          decoderOneAssignments,
          decoder,
          decoderAssignments,
          guards,
          elementLeafIndex,
          assignments,
          kindName(kind),
          shape.sourceLocation
        )
      )
    }

  private def capturedWriteConditions(
      guard: WhenStatement
  ): Vector[ParameterizedVecWriteCondition] = {
    capturedConditions(guard.parentScope)
  }

  private def capturedConditions(
      initialScope: spinal.core.internals.ScopeStatement
  ): Vector[ParameterizedVecWriteCondition] = {
    val conditions = ArrayBuffer.empty[ParameterizedVecWriteCondition]
    var scope = initialScope
    var complete = false
    while (scope != null && scope.parentStatement != null && !complete) {
      scope.parentStatement match {
        case parent: WhenStatement =>
          // Preserve this exact Bool identity through ordinary alias cleanup.
          // Publication still validates the native condition and scope graph.
          parent.cond match {
            case value: Bool => value.dontSimplifyIt()
            case _ =>
          }
          conditions += ParameterizedVecWriteCondition(parent, parent.cond, scope eq parent.whenTrue)
          scope = parent.parentScope
        case _ => complete = true // Unsupported ownership remains fail-closed at publication.
      }
    }
    conditions.reverse.toVector
  }

  private[spinal] def requireCompatible(
      target: Vec[_],
      source: Vec[_]
  ): Boolean = {
    (shapeOf(target), shapeOf(source)) match {
      case (None, None) => false
      case (Some(left), Some(right))
          if equivalentShape(left, right) ||
            equivalentDirectHierarchyShape(target, left, source, right) =>
        true
      case (Some(left), Some(right)) =>
        fail(
          "SPINAL-ELAB-VEC-ASSIGNMENT-SHAPE-MISMATCH",
          s"Vec assignment crosses incompatible symbolic depths '${left.depth.verilog}' and '${right.depth.verilog}'",
          left.sourceLocation.orElse(right.sourceLocation)
        )
      case (Some(shape), None) =>
        fail(
          "SPINAL-ELAB-VEC-ASSIGNMENT-SHAPE-MISMATCH",
          s"symbolic Vec depth '${shape.depth.verilog}' cannot be assigned from a concrete Vec",
          shape.sourceLocation
        )
      case (None, Some(shape)) =>
        fail(
          "SPINAL-ELAB-VEC-ASSIGNMENT-SHAPE-MISMATCH",
          s"concrete Vec cannot be assigned from symbolic Vec depth '${shape.depth.verilog}'",
          shape.sourceLocation
        )
    }
  }

  private[spinal] def recordWholeAssignment(
      target: Vec[_],
      source: Vec[_],
      assignments: Vector[DataAssignmentStatement],
      kind: AnyRef
  ): Unit =
    shapeOf(target).foreach { shape =>
      append(
        target,
        ParameterizedVecWholeAssignment(
          source,
          assignments,
          kindName(kind),
          shape.sourceLocation
        )
      )
    }

  /** Follow only the native nodes used by MultiData.asBits.  Intermediate
    * type-node copies are admitted through one exact assignment identity;
    * arithmetic, slicing, resizing and any multi-driver node are rejected.
    * The returned order is low packed bit to high packed bit.
    */
  private def exactPackedReadLeaves(
      root: Expression,
      expected: Vector[BaseType],
      blocked: BaseType,
      supportAssignments: ArrayBuffer[DataAssignmentStatement]
  ): Option[Vector[BaseType]] = {
    val expectedSet = new IdentityHashMap[BaseType, java.lang.Boolean]()
    expected.foreach(leaf => expectedSet.put(leaf, java.lang.Boolean.TRUE))
    val visited = new IdentityHashMap[Expression, java.lang.Boolean]()

    def trace(value: Expression): Option[Vector[BaseType]] = {
      if (value == null || visited.put(value, java.lang.Boolean.TRUE) != null)
        return None
      value match {
        case leaf: BaseType if expectedSet.containsKey(leaf) =>
          Some(Vector(leaf))
        case cat: Operator.Bits.Cat =>
          for {
            low <- trace(cat.right)
            high <- trace(cat.left)
          } yield low ++ high
        case cast: CastUIntToBits => trace(cast.input)
        case cast: CastSIntToBits => trace(cast.input)
        case cast: CastBoolToBits => trace(cast.input)
        case cast: CastEnumToBits => trace(cast.input)
        case intermediate: BaseType if intermediate ne blocked =>
          val drivers = assignmentStatementsOf(intermediate)
            .filter(_.finalTarget eq intermediate)
          if (drivers.size == 1) {
            val proof = trace(drivers.head.source)
            proof.foreach { _ =>
              if (!supportAssignments.exists(_ eq drivers.head)) supportAssignments += drivers.head
              // Preserve only an intermediate already proven to be on this
              // exact native packing path.  Otherwise native alias folding can
              // give it the emitted name of an unrelated multi-driver leaf,
              // making the final identity audit ambiguous.
              intermediate
                .setName("morphhdl_typed_vec_packed_support", weak = true)
                .dontSimplifyIt()
                .setAsVital()
            }
            proof
          } else None
        case _ => None
      }
    }

    trace(root)
  }

  private def validatePackedReadCarrierLineage(
      vector: Vec[_],
      carrier: Bits,
      assignments: Vector[DataAssignmentStatement],
      shape: ParameterizedVecShape,
      supportAssignments: ArrayBuffer[DataAssignmentStatement]
  ): Vector[BaseType] = {
    val expected = vector.vec.flatMap(element => element.asInstanceOf[Data].flatten).toVector
    val drivers = assignments.filter(_.finalTarget eq carrier)
    val actual =
      if (drivers.size == 1)
        exactPackedReadLeaves(drivers.head.source, expected, carrier, supportAssignments)
      else None
    if (
      actual.isEmpty || actual.get.size != expected.size ||
      !actual.get.zip(expected).forall { case (left, right) => left eq right }
    ) {
      fail(
        "SPINAL-ELAB-VEC-PACKED-READ-CARRIER-LAYOUT-MISMATCH",
        s"typed Vec packed read does not retain one exact native low-to-high concatenation of its ${expected.size} carrier leaves",
        shape.sourceLocation
      )
    }
    expected
  }

  /** Recover one native fixed source range through only the casts used by
    * BaseType.assignFromBits and one-driver type-node copies.  This audits the
    * inherited MultiData slice layout without authoring another Vec packing
    * algorithm.
    */
  private def exactPackedAssignmentRange(
      root: Expression,
      carrier: Bits
  ): Option[(Int, Int)] = {
    val visited = new IdentityHashMap[Expression, java.lang.Boolean]()
    def trace(value: Expression): Option[(Int, Int)] = {
      if (value == null || visited.put(value, java.lang.Boolean.TRUE) != null)
        return None
      value match {
        case source if source eq carrier =>
          Some(0 -> carrier.getBitsWidth)
        case access: BitsRangedAccessFixed =>
          trace(access.source).flatMap { case (parentLo, parentWidth) =>
            val width = access.getWidth
            if (
              access.lo < 0 || width < 1 ||
              access.lo + width > parentWidth
            ) None
            else Some((parentLo + access.lo) -> width)
          }
        case access: BitsBitAccessFixed =>
          trace(access.source).flatMap { case (parentLo, parentWidth) =>
            if (access.bitId < 0 || access.bitId >= parentWidth) None
            else Some((parentLo + access.bitId) -> 1)
          }
        case cast: CastBitsToUInt => trace(cast.input)
        case cast: CastBitsToSInt => trace(cast.input)
        case cast: CastBitsToEnum => trace(cast.input)
        case intermediate: BaseType if intermediate ne carrier =>
          val drivers = assignmentStatementsOf(intermediate)
            .filter(_.finalTarget eq intermediate)
          if (drivers.size == 1) trace(drivers.head.source) else None
        case _ => None
      }
    }
    trace(root)
  }

  private def validatePackedAssignmentSlices(
      vector: Vec[_],
      carrier: Bits,
      assignments: Vector[DataAssignmentStatement],
      shape: ParameterizedVecShape
  ): Vector[ParameterizedVecPackedSlice] = {
    val leaves = vector.vec.flatMap(element => element.asInstanceOf[Data].flatten).toVector
    var offset = 0
    val slices = leaves.map { leaf =>
      val width = leaf.getBitsWidth
      val matching = assignments.filter(_.finalTarget eq leaf)
      val actual =
        if (matching.size == 1)
          exactPackedAssignmentRange(matching.head.source, carrier)
        else None
      if (actual != Some(offset -> width)) {
        fail(
          "SPINAL-ELAB-VEC-PACKED-ASSIGNMENT-SLICE-MISMATCH",
          s"typed Vec packed assignment leaf at native offset $offset width $width does not retain its exact fixed carrier slice",
          shape.sourceLocation
        )
      }
      val slice = ParameterizedVecPackedSlice(leaf, offset, width)
      offset += width
      slice
    }
    if (offset != carrier.getBitsWidth) {
      fail(
        "SPINAL-ELAB-VEC-PACKED-ASSIGNMENT-SLICE-MISMATCH",
        s"typed Vec packed assignment covers $offset carrier bits, expected ${carrier.getBitsWidth}",
        shape.sourceLocation
      )
    }
    slices
  }

  private[spinal] def recordPackedRead(vector: Vec[_], carrier: Bits): Bits =
    shapeOf(vector) match {
      case None => carrier
      case Some(shape) =>
        val physicalWidth = BigInt(shape.carrierCapacity) * shape.elementWidthDefault
        val logicalWitnessWidth = BigInt(shape.witnessDepth) * shape.logicalElementWidthDefault
        if (
          !physicalWidth.isValidInt || !logicalWitnessWidth.isValidInt ||
          physicalWidth < 1 || logicalWitnessWidth < 1 ||
          BigInt(carrier.getBitsWidth) != physicalWidth ||
          logicalWitnessWidth > physicalWidth
        ) {
          fail(
            "SPINAL-ELAB-VEC-PACKED-READ-CARRIER-INVALID",
            s"typed Vec packed read has native carrier width ${carrier.getBitsWidth}, logical witness width $logicalWitnessWidth and audited capacity width $physicalWidth",
            shape.sourceLocation
          )
        }

        // The native Vec concatenation remains the exact full-capacity audit
        // carrier.  A smaller public result is only a witness-width wrapper so
        // ordinary native width checks can run; publication consumes both
        // exact identities and restores the logical factorized Vec width.
        vector.vec.foreach(element =>
          element
            .asInstanceOf[Data]
            .flatten
            .foreach(_.dontSimplifyIt().setAsVital())
        )
        // PhaseRemoveIntermediateUnnameds keeps a protected node only when it
        // also has a stable name.  Give the exact native carrier/result weak
        // generic names so a later user name remains authoritative, while
        // vital and dontSimplify preserve the identity needed by publication.
        carrier
          .setName("morphhdl_typed_vec_packed_carrier", weak = true)
          .dontSimplifyIt()
          .setAsVital()
        val result =
          if (logicalWitnessWidth == physicalWidth) carrier
          else
            carrier
              .resize(logicalWitnessWidth.toInt)
              .setName("morphhdl_typed_vec_packed_result", weak = true)
              .dontSimplifyIt()
              .setAsVital()
        val carrierAssignments = assignmentStatementsOf(carrier)
        val supportAssignments = ArrayBuffer.empty[DataAssignmentStatement]
        val carrierLeaves = validatePackedReadCarrierLineage(
          vector,
          carrier,
          carrierAssignments,
          shape,
          supportAssignments
        )
        val resultAssignments = assignmentStatementsOf(result)
        if (result ne carrier) {
          val bridges = resultAssignments.filter(_.finalTarget eq result)
          if (bridges.size != 1) {
            fail(
              "SPINAL-ELAB-VEC-PACKED-READ-WRAPPER-INVALID",
              s"typed Vec packed read retains ${bridges.size} native logical-witness wrappers; exactly one is required",
              shape.sourceLocation
            )
          }
          bridges.head.source match {
            case resize: Resize
                if (resize.input eq carrier) &&
                  resize.size == logicalWitnessWidth.toInt &&
                  resize.size < carrier.getBitsWidth =>
            case _ =>
              fail(
                "SPINAL-ELAB-VEC-PACKED-READ-WRAPPER-INVALID",
                "typed Vec packed read logical witness is not the exact LSB-preserving Resize of its full-capacity native carrier",
                shape.sourceLocation
              )
          }
        }
        retainPacked(result, shape)
        append(
          vector,
          ParameterizedVecPackedRead(
            result,
            carrier,
            resultAssignments,
            carrierAssignments,
            carrierLeaves,
            shape.sourceLocation,
            supportAssignments.toVector
          )
        )
        result
    }

  /** Prove that a packed assignment retains the exact logical Vec shape.
    *
    * A value returned by Vec.asBits carries factorized shape identity.  An
    * independently authored Bits value is accepted only when its typed width
    * can be proven equivalent to the logical element-width times depth; a
    * matching native witness width alone is never provenance.
    */
  private[spinal] def validatePackedAssignment(
      vector: Vec[_],
      source: Bits
  ): Unit =
    shapeOf(vector).foreach { targetShape =>
      if (source == null)
        fail(
          "SPINAL-ELAB-VEC-PACKED-SOURCE-NULL",
          "typed Vec packed assignment requires a non-null Bits source",
          targetShape.sourceLocation
        )
      exactPackedSource(source).map(_._1) match {
        case Some(sourceShape) if equivalentShape(targetShape, sourceShape) =>
        case Some(sourceShape) =>
          fail(
            "SPINAL-ELAB-VEC-PACKED-SHAPE-MISMATCH",
            s"packed Vec assignment crosses incompatible symbolic depths '${targetShape.depth.verilog}' and '${sourceShape.depth.verilog}'",
            targetShape.sourceLocation.orElse(sourceShape.sourceLocation)
          )
        case None =>
          val authored = ParameterizedWidth.expressionOf(source).getOrElse {
            fail(
              "SPINAL-ELAB-VEC-PACKED-SOURCE-PROVENANCE-MISSING",
              s"packed source '${source.getName()}' has only native witness width ${source.getBitsWidth}; a typed width or Vec.asBits origin is required",
              targetShape.sourceLocation
            )
          }
          expectedPackedWidth(targetShape) match {
            case Some(expected) if equivalentPackedWidth(authored, expected) =>
            case Some(expected) =>
              fail(
                "SPINAL-ELAB-VEC-PACKED-WIDTH-MISMATCH",
                s"packed source width '${authored.verilog}' does not match Vec total width '${expected.verilog}'",
                authored.sourceLocation.orElse(targetShape.sourceLocation)
              )
            case None =>
              fail(
                "SPINAL-ELAB-VEC-PACKED-SOURCE-PROVENANCE-MISSING",
                s"packed source width '${authored.verilog}' cannot prove the independently rooted Vec total width '${targetShape.elementLeaves
                    .map(_.width.verilog)
                    .mkString(" + ")} times ${targetShape.depth.verilog}'",
                authored.sourceLocation.orElse(targetShape.sourceLocation)
              )
          }
      }
    }

  /** Follow only complete, unconditional, same-owner native Bits copies.
    * The returned assignment identities are frozen into the consuming Vec
    * operation and re-audited before publication; width coincidence is never
    * used to discover an alias.
    */
  private def exactPackedSource(
      source: Bits
  ): Option[(ParameterizedVecShape, Vector[ParameterizedVecPackedSourceAlias])] = {
    val visited = new IdentityHashMap[Bits, java.lang.Boolean]()
    def visit(value: Bits): Option[(ParameterizedVecShape, Vector[ParameterizedVecPackedSourceAlias])] = {
      if (value == null || visited.put(value, java.lang.Boolean.TRUE) != null) return None
      directPackedShapeOf(value) match {
        case Some(shape) => Some(shape -> Vector.empty)
        case None if !value.isReg && value.component != null =>
          val drivers = assignmentStatementsOf(value).filter(_.finalTarget eq value)
          drivers match {
            case Vector(driver) if (driver.target eq value) && (driver.parentScope eq value.component.dslBody) =>
              driver.source match {
                case upstream: Bits if upstream.component eq value.component =>
                  visit(upstream).filter { case (shape, _) =>
                    !value.isFixedWidth || (for {
                      width <- ParameterizedWidth.expressionOf(value)
                      expected <- expectedPackedWidth(shape)
                    } yield equivalentPackedWidth(width, expected)).getOrElse(false)
                  }.map { case (shape, aliases) =>
                    shape -> (ParameterizedVecPackedSourceAlias(value, upstream, driver) +: aliases)
                  }
                case _ => None
              }
            case _ => None
          }
        case _ => None
      }
    }
    visit(source)
  }

  private[spinal] def recordPackedAssignment(
      vector: Vec[_],
      source: Bits,
      carrier: Bits,
      assignments: Vector[DataAssignmentStatement]
  ): Unit = {
    validatePackedAssignment(vector, source)
    shapeOf(vector).foreach { shape =>
      val expected = BigInt(shape.carrierCapacity) * shape.elementWidthDefault
      if (!expected.isValidInt || BigInt(carrier.getBitsWidth) != expected) {
        fail(
          "SPINAL-ELAB-VEC-PACKED-ASSIGNMENT-CARRIER-INVALID",
          s"typed Vec packed assignment carrier has ${carrier.getBitsWidth} bits, expected audited capacity width $expected",
          shape.sourceLocation
        )
      }
      val carrierAssignments = assignmentStatementsOf(carrier)
      if (carrier ne source) {
        if (carrier.getBitsWidth < source.getBitsWidth || carrierAssignments.size != 1) {
          fail(
            "SPINAL-ELAB-VEC-PACKED-ASSIGNMENT-CARRIER-INVALID",
            "typed Vec packed assignment requires one exact zero-extending native carrier wrapper",
            shape.sourceLocation
          )
        }
        val bridge = carrierAssignments.head
        bridge.source match {
          case resize: Resize
              if (bridge.finalTarget eq carrier) &&
                (resize.input eq source) &&
                resize.size == carrier.getBitsWidth =>
          case _ =>
            fail(
              "SPINAL-ELAB-VEC-PACKED-ASSIGNMENT-CARRIER-INVALID",
              "typed Vec packed assignment carrier is not the exact native LSB-preserving Resize of its logical source",
              shape.sourceLocation
            )
        }
      }
      val slices = validatePackedAssignmentSlices(
        vector,
        carrier,
        assignments,
        shape
      )
      val sourceAliases = exactPackedSource(source).map(_._2).getOrElse(Vector.empty)
      sourceAliases.foreach { alias =>
        alias.target.dontSimplifyIt()
        alias.source.dontSimplifyIt()
      }
      append(
        vector,
        ParameterizedVecPackedAssignment(
          source,
          carrier,
          assignments,
          carrierAssignments,
          slices,
          shape.sourceLocation,
          sourceAliases
        )
      )
    }
  }

  private[spinal] def recordAutoConnect(
      vector: Vec[_],
      peer: Vec[_],
      assignments: Vector[DataAssignmentStatement]
  ): Unit =
    shapeOf(vector).foreach { shape =>
      append(
        vector,
        ParameterizedVecAutoConnect(peer, assignments, shape.sourceLocation)
      )
    }

  private[spinal] def captureAssignments[T](data: Data)(body: => T): (T, Vector[DataAssignmentStatement]) = {
    captureAssignments(Vector(data))(body)
  }

  private[spinal] def captureAssignments[T](
      data: Vector[Data]
  )(body: => T): (T, Vector[DataAssignmentStatement]) = {
    val before = data.flatMap(assignmentStatementsOf)
    val result = body
    val after = data.flatMap(assignmentStatementsOf)
    val added = after
      .filterNot(statement => before.exists(_ eq statement))
      .foldLeft(Vector.empty[DataAssignmentStatement]) { (known, statement) =>
        if (known.exists(_ eq statement)) known else known :+ statement
      }
    result -> added
  }

  private def assignmentStatementsOf(data: Data): Vector[DataAssignmentStatement] = {
    val values = ArrayBuffer.empty[DataAssignmentStatement]
    val seen = new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    data.flatten.foreach { leaf =>
      leaf.foreachStatements {
        case value: DataAssignmentStatement if seen.put(value, java.lang.Boolean.TRUE) == null =>
          values += value
        case _ =>
      }
    }
    values.toVector
  }

  private[spinal] def rejectUnsupported(vector: Vec[_], operation: String): Unit =
    shapeOf(vector).foreach { shape =>
      fail(
        "SPINAL-ELAB-VEC-OPERATION-UNSUPPORTED",
        s"$operation is not yet proven generic for symbolic Vec depth '${shape.depth.verilog}'",
        shape.sourceLocation
      )
    }

  private def append(vector: Vec[_], operation: ParameterizedVecOperation): Unit = synchronized {
    reap()
    retained
      .get(new ParameterizedVecIdentityRef(vector, null))
      .getOrElse {
        throw new IllegalStateException("typed Vec operation has no retained shape")
      }
      .operations += operation
  }

  private def expectedPackedWidth(
      shape: ParameterizedVecShape
  ): Option[ElaborationIntegerExpression] = {
    val expressions = shape.geometryExpressions
    val roots = expressions
      .flatMap(_.completedParameterRoots)
      .foldLeft(
        Vector.empty[ElaborationIntegerParameterRoot]
      ) { (known, root) =>
        if (known.exists(_ eq root)) known else known :+ root
      }
    if (roots.size > 1) None
    else {
      val elementWidth = shape.elementFields
        .map(field => field.geometryExpressions.map(ElabInt.fromExpression).reduce(_ * _))
        .reduce(_ + _)
      Some(
        (elementWidth * ElabInt.fromExpression(shape.depth)).expression
      )
    }
  }

  private def equivalentPackedWidth(
      authored: ElaborationIntegerExpression,
      expected: ElaborationIntegerExpression
  ): Boolean =
    ElabInt.equivalentExactFunction(authored, expected)

  private def equivalentShape(
      left: ParameterizedVecShape,
      right: ParameterizedVecShape
  ): Boolean =
    ElabInt.equivalentExactFunction(left.depth, right.depth) &&
      left.witnessDepth == right.witnessDepth &&
      left.carrierCapacity == right.carrierCapacity &&
      equivalentLeaves(left.elementLeaves, right.elementLeaves) &&
      equivalentFields(left.elementFields, right.elementFields)

  /** Recover one parent-side formal actual from two exact corresponding Vec
    * port objects.  Port order is established by the hierarchy caller through
    * the exact flattened BaseType identities; this method validates the
    * complete retained Vec layout and returns a binding only when the
    * canonical shape contains the exact direct formal root.  Same names,
    * witnesses, bounds or rendered algebra are never sufficient.
    */
  private[spinal] def exactAggregateHierarchyBinding(
      canonicalVector: Vec[_],
      actualVector: Vec[_],
      canonicalFormal: ElaborationIntegerParameter,
      canonicalActual: ElaborationIntegerExpression,
      actualFormal: ElaborationIntegerParameter,
      actualActual: ElaborationIntegerExpression
  ): Option[ElaborationIntegerExpression] = {
    if (
      canonicalVector == null || actualVector == null ||
      canonicalFormal == null || canonicalActual == null ||
      actualFormal == null || actualActual == null
    )
      return None
    val canonical = shapeOf(canonicalVector).getOrElse(return None)
    val actual = shapeOf(actualVector).getOrElse(return None)
    if (canonicalFormal != actualFormal) return None
    if (
      canonical.witnessDepth != actual.witnessDepth ||
      canonical.carrierCapacity != actual.carrierCapacity ||
      !equivalentFieldLayout(canonical.elementFields, actual.elementFields) ||
      canonical.elementLeaves.size != actual.elementLeaves.size ||
      !canonical.elementLeaves.zip(actual.elementLeaves).forall { case (left, right) =>
        left.path == right.path &&
        (left.typeObject eq right.typeObject)
      }
    ) return None

    val dimensions = canonical.geometryExpressions.zip(actual.geometryExpressions)
    val retainedCanonical = formalBindingsOf(canonicalVector).filter(binding => binding.formal eq canonicalFormal)
    val retainedActual = formalBindingsOf(actualVector).filter(binding => binding.formal eq actualFormal)
    if (
      retainedCanonical.size != 1 || retainedActual.size != 1 ||
      !ElabInt.equivalentExactFunction(
        retainedCanonical.head.actual,
        canonicalActual
      ) ||
      !ElabInt.equivalentExactFunction(
        retainedActual.head.actual,
        actualActual
      )
    ) return None
    if (
      !dimensions.forall { case (definition, instance) =>
        ElabInt.equivalentExactFunction(definition, instance) ||
        equivalentDeclaredFormalFunction(
          definition,
          canonicalFormal,
          instance,
          actualFormal
        )
      }
    ) return None

    val directDimensions = dimensions.collect {
      case (definition, instance)
          if isExactDirectFormalExpression(definition, canonicalFormal) &&
            isExactDirectFormalExpression(instance, actualFormal) =>
        definition -> instance
    }
    if (directDimensions.isEmpty) return None

    Some(actualActual)
  }

  /** Recover an aggregate binding when the child was constructed directly
    * from the parent's exact ElabInt root and therefore has no explicit
    * typed child-formal slot. Canonical and actual Vecs must share the complete
    * exact function/root layout; equal schemas or rendered expressions alone
    * cannot bridge independently rooted declarations.
    */
  private[spinal] def exactDirectAggregateHierarchyBinding(
      canonicalVector: Vec[_],
      actualVector: Vec[_],
      parameterSchema: ElaborationIntegerParameter
  ): Option[ElaborationIntegerExpression] = {
    if (
      canonicalVector == null || actualVector == null ||
      parameterSchema == null
    ) return None
    val canonical = shapeOf(canonicalVector).getOrElse(return None)
    val actual = shapeOf(actualVector).getOrElse(return None)
    if (
      canonical.witnessDepth != actual.witnessDepth ||
      canonical.carrierCapacity != actual.carrierCapacity ||
      !equivalentFieldLayout(canonical.elementFields, actual.elementFields) ||
      canonical.elementLeaves.size != actual.elementLeaves.size ||
      !canonical.elementLeaves.zip(actual.elementLeaves).forall { case (left, right) =>
        left.path == right.path &&
        (left.typeObject eq right.typeObject)
      }
    ) return None

    val dimensions = canonical.geometryExpressions.zip(actual.geometryExpressions)
    if (
      !dimensions.forall { case (definition, instance) =>
        ElabInt.equivalentExactFunction(definition, instance)
      }
    ) return None

    val direct = dimensions.collect {
      case (definition, instance)
          if isExactDirectParameterSchema(definition, parameterSchema) &&
            isExactDirectParameterSchema(instance, parameterSchema) =>
        instance
    }
    val distinct = direct.foldLeft(Vector.empty[ElaborationIntegerExpression]) { (known, expression) =>
      if (known.exists(ElabInt.equivalentExactFunction(_, expression))) known
      else known :+ expression
    }
    if (distinct.size == 1) Some(distinct.head) else None
  }

  /** Exact direct-parameter proof keyed by the retained domain root.  The
    * supplied value is only a public schema selector; its independently lazy
    * declarationRoot is deliberately not treated as provenance.
    */
  private[spinal] def isExactDirectParameterSchema(
      expression: ElaborationIntegerExpression,
      parameterSchema: ElaborationIntegerParameter
  ): Boolean =
    expression != null && parameterSchema != null &&
      expression.generateIndex.isEmpty &&
      expression.exactDomain.exists { domain =>
        domain.parameter == parameterSchema &&
        expression.parameters == Vector(domain.parameter) &&
        (expression.completedParameterRoots match {
          case Vector(root) => root eq domain.root
          case _            => false
        }) &&
        domain.evidenceValues == domain.universe &&
        domain.evaluations.forall { case (rootValue, result) =>
          rootValue == result
        }
      }

  /** Compare two definition-side formal functions after an exact formal-slot
    * identity has authorized renaming one declaration root to the other.  The
    * complete exact domains, not rendered algebra or parameter names, remain
    * authoritative for the function comparison.
    */
  private def equivalentDeclaredFormalFunction(
      canonical: ElaborationIntegerExpression,
      canonicalFormal: ElaborationIntegerParameter,
      actual: ElaborationIntegerExpression,
      actualFormal: ElaborationIntegerParameter
  ): Boolean = {
    val canonicalRoots = canonical.completedParameterRoots
    val actualRoots = actual.completedParameterRoots
    if (
      canonicalRoots != Vector(canonicalFormal.declarationRoot) ||
      actualRoots != Vector(actualFormal.declarationRoot)
    ) return false

    (canonical.exactDomain, actual.exactDomain) match {
      case (Some(canonicalDomain), Some(actualDomain))
          if (canonicalDomain.root eq canonicalFormal.declarationRoot) &&
            (actualDomain.root eq actualFormal.declarationRoot) &&
            canonicalDomain.parameter == canonicalFormal &&
            actualDomain.parameter == actualFormal &&
            canonicalDomain.universe == actualDomain.universe &&
            canonicalDomain.evidenceValues == canonicalDomain.universe &&
            actualDomain.evidenceValues == actualDomain.universe =>
        canonicalDomain.evaluations.toMap == actualDomain.evaluations.toMap
      case _ => false
    }
  }

  /** Exact packed-result ownership check used at a pulled Vec hierarchy edge. */
  private[spinal] def exactPackedShapeMatches(
      vector: Vec[_],
      packed: Bits
  ): Boolean =
    shapeOf(vector).exists(shape => packedShapeOf(packed).exists(retained => equivalentShape(shape, retained)))

  /** Compare a child definition shape with its parent-side actual shape only
    * through an explicit typed scalar-formal binding retained on that exact
    * child instance.  Equal rendered names or equal witness values are never
    * sufficient: the child expression must carry the formal declaration root
    * and the peer must carry that binding's exact actual expression.
    */
  private def equivalentDirectHierarchyShape(
      leftVector: Vec[_],
      left: ParameterizedVecShape,
      rightVector: Vec[_],
      right: ParameterizedVecShape
  ): Boolean = {
    val leftComponent = leftVector.component
    val rightComponent = rightVector.component
    if (leftComponent == null || rightComponent == null) return false

    val relation =
      if (leftComponent.parent eq rightComponent)
        Some((leftComponent, left, right))
      else if (rightComponent.parent eq leftComponent)
        Some((rightComponent, right, left))
      else None

    relation.exists { case (child, childShape, parentShape) =>
      val bindings = formalBindingsOf(
        if (child eq leftComponent) leftVector else rightVector
      )
      childShape.witnessDepth == parentShape.witnessDepth &&
      childShape.carrierCapacity == parentShape.carrierCapacity &&
      equivalentBoundaryExpression(
        childShape.depth,
        parentShape.depth,
        bindings
      ) &&
      equivalentFieldLayout(childShape.elementFields, parentShape.elementFields) &&
      childShape.elementFields.zip(parentShape.elementFields).forall { case (childField, parentField) =>
        childField.geometryExpressions.zip(parentField.geometryExpressions).forall { case (childExpression, parentExpression) =>
          equivalentBoundaryExpression(childExpression, parentExpression, bindings)
        }
      } &&
      childShape.elementLeaves.size == parentShape.elementLeaves.size &&
      childShape.elementLeaves.zip(parentShape.elementLeaves).forall { case (childLeaf, parentLeaf) =>
        childLeaf.path == parentLeaf.path &&
        (childLeaf.typeObject eq parentLeaf.typeObject) &&
        equivalentBoundaryExpression(
          childLeaf.width,
          parentLeaf.width,
          bindings
        )
      }
    }
  }

  private def equivalentBoundaryExpression(
      child: ElaborationIntegerExpression,
      parent: ElaborationIntegerExpression,
      bindings: Vector[ParameterizedVecFormalBinding]
  ): Boolean =
    ElabInt.equivalentExactFunction(child, parent) || bindings.exists { binding =>
      (isDirectFormalExpression(child, binding.formal) &&
        ElabInt.equivalentExactFunction(binding.actual, parent)) ||
      equivalentComposedFormalExpression(child, parent, binding)
    }

  /** Prove a compound definition expression by exact-domain composition.
    *
    * The child evaluator consumes values of its exact formal root.  The
    * retained binding maps every value of the parent's exact root to such a
    * formal value, and the parent expression supplies the expected result for
    * that same root.  This is typed substitution evidence; rendered component
    * names and textual expression parsing are never involved.
    */
  private def equivalentComposedFormalExpression(
      child: ElaborationIntegerExpression,
      parent: ElaborationIntegerExpression,
      binding: ParameterizedVecFormalBinding
  ): Boolean = {
    val childRoots = child.completedParameterRoots
    val actualRoots = binding.actual.completedParameterRoots
    val parentRoots = parent.completedParameterRoots
    if (
      childRoots.size != 1 ||
      !(childRoots.head eq binding.formal.declarationRoot) ||
      actualRoots.size != 1 || parentRoots.size != 1 ||
      !(actualRoots.head eq parentRoots.head)
    ) return false

    (child.exactDomain, binding.actual.exactDomain, parent.exactDomain) match {
      case (Some(childDomain), Some(actualDomain), Some(parentDomain))
          if (childDomain.root eq binding.formal.declarationRoot) &&
            (actualDomain.root eq parentDomain.root) &&
            actualDomain.universe == parentDomain.universe &&
            actualDomain.evidenceValues == actualDomain.universe &&
            parentDomain.evidenceValues == parentDomain.universe =>
        val parentValues = parentDomain.evaluations.toMap
        actualDomain.evaluations.forall { case (rootValue, formalValue) =>
          childDomain
            .evaluate(formalValue)
            .flatMap(value => parentValues.get(rootValue).map(_ == value))
            .contains(true)
        } &&
        childDomain.evaluate(binding.actual.default).contains(parent.default)
      case _ => false
    }
  }

  private[spinal] def formalBindingsOf(
      vector: Vec[_]
  ): Vector[ParameterizedVecFormalBinding] = synchronized {
    reap()
    retained
      .get(new ParameterizedVecIdentityRef(vector, null))
      .map(_.formalBindings.toVector)
      .getOrElse(Vector.empty)
  }

  private def isDirectFormalExpression(
      expression: ElaborationIntegerExpression,
      formal: ElaborationIntegerParameter
  ): Boolean =
    expression.generateIndex.isEmpty &&
      expression.verilog == formal.name &&
      expression.parameters == Vector(formal) &&
      (expression.completedParameterRoots match {
        case Vector(root) => root eq formal.declarationRoot
        case _            => false
      })

  /** Identity-only direct-formal proof for aggregate hierarchy discovery. */
  private def isExactDirectFormalExpression(
      expression: ElaborationIntegerExpression,
      formal: ElaborationIntegerParameter
  ): Boolean =
    expression.generateIndex.isEmpty &&
      expression.parameters == Vector(formal) &&
      (expression.completedParameterRoots match {
        case Vector(root) => root eq formal.declarationRoot
        case _            => false
      }) &&
      expression.exactDomain.exists { domain =>
        (domain.root eq formal.declarationRoot) &&
        domain.parameter == formal &&
        domain.evidenceValues == domain.universe &&
        domain.evaluations.forall { case (rootValue, result) =>
          rootValue == result
        }
      }

  private def equivalentFieldLayout(
      left: Vector[ParameterizedVecFieldShape],
      right: Vector[ParameterizedVecFieldShape]
  ): Boolean = left.size == right.size && left.zip(right).forall { case (l, r) =>
    l.path == r.path && (l.typeObject eq r.typeObject) &&
      l.carrierLeafIndices == r.carrierLeafIndices &&
      l.dimensions.size == r.dimensions.size &&
      l.dimensions.zip(r.dimensions).forall { case (ld, rd) =>
        ld.witnessDepth == rd.witnessDepth && ld.carrierCapacity == rd.carrierCapacity
      }
  }

  private def equivalentFieldGeometry(
      left: ParameterizedVecFieldShape,
      right: ParameterizedVecFieldShape
  ): Boolean =
    left.path == right.path && (left.typeObject eq right.typeObject) &&
      ElabInt.equivalentExactFunction(left.width, right.width) &&
      left.dimensions.size == right.dimensions.size &&
      left.dimensions.zip(right.dimensions).forall { case (l, r) =>
        l.witnessDepth == r.witnessDepth && l.carrierCapacity == r.carrierCapacity &&
          ElabInt.equivalentExactFunction(l.depth, r.depth)
      }

  private def equivalentFields(
      left: Vector[ParameterizedVecFieldShape],
      right: Vector[ParameterizedVecFieldShape]
  ): Boolean = equivalentFieldLayout(left, right) &&
    left.zip(right).forall { case (l, r) => equivalentFieldGeometry(l, r) }

  private def equivalentLeaves(
      left: Vector[ParameterizedVecLeafShape],
      right: Vector[ParameterizedVecLeafShape]
  ): Boolean =
    left.size == right.size && left.zip(right).forall { case (l, r) =>
      l.path == r.path && (l.typeObject eq r.typeObject) &&
      ElabInt.equivalentExactFunction(l.width, r.width)
    }

  private def projectedConstant(
      expression: ElaborationIntegerExpression,
      operation: String,
      geometry: String,
      sourceLocation: Option[String]
  ): BigInt = {
    val projected = ElabInt.projectExpression(expression, operation)
    if (projected.minimum != projected.maximum) {
      fail(
        "SPINAL-ELAB-VEC-OPERATION-UNSUPPORTED",
        s"$operation requires domain-constant $geometry, but '${projected.verilog}' varies over [${projected.minimum}, ${projected.maximum}]",
        sourceLocation.orElse(projected.sourceLocation)
      )
    }
    projected.default
  }

  private def kindName(kind: AnyRef): String =
    Option(kind).map(_.getClass.getName).getOrElse("<null>")

  private def literal(value: Int): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = value.toString,
      default = BigInt(value),
      minimum = BigInt(value),
      maximum = BigInt(value),
      parameters = Vector.empty
    )

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
