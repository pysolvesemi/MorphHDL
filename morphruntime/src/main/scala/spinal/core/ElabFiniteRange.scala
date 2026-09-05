package spinal.core

import scala.collection.mutable

import spinal.core.internals.DataAssignmentStatement

/** One generic typed population-count operation retained for final portable
  * publication. The native zero assignment is only a validated graph anchor;
  * [[count]] and [[resultWidth]] remain the authoritative geometry.
  */
private[spinal] final case class ParameterizedFiniteCountOne(
    source: Bits,
    result: UInt,
    assignment: DataAssignmentStatement,
    count: ElaborationIntegerExpression,
    resultWidth: ElaborationIntegerExpression,
    ordinal: Long,
    sourceLocation: Option[String]
)

/** Opaque identity shared only by one exact finite-range invocation, its
  * captured generate-for and the structural selections made by its index.
  * Public names and coincident index expressions cannot recreate this token.
  */
private[core] final class ElabFiniteIndexToken private[core] ()

/** Read-only affine access minted by one exact finite index. The retained
  * selector, Vec and range identities authorize replay; emitted arithmetic is
  * only checked against that already-established relation.
  */
private[core] final class ElabFiniteAffineVecRead private[core] (
    val vector: Vec[_],
    val selector: ElaborationIntegerExpression,
    val baseIndex: ElaborationIntegerExpression,
    val count: ElaborationIntegerExpression,
    val originalDepth: ElaborationIntegerExpression,
    val depth: ElaborationIntegerExpression,
    val admitted: Set[BigInt],
    val token: ElabFiniteIndexToken,
    val coefficient: Int,
    val offset: Int
) {
  private[core] def matches(
      actualVector: Vec[_],
      actualSelector: ElaborationIntegerExpression,
      actualToken: ElabFiniteIndexToken,
      ownerCount: Option[ElaborationIntegerExpression] = None
  ): Boolean = {
    val domainMatches = (count.exactDomain, depth.exactDomain) match {
      case (Some(c), Some(d)) if c.root eq d.root =>
        admitted.nonEmpty && admitted.subsetOf(c.evidenceValues) &&
          admitted.subsetOf(d.evidenceValues) && admitted.forall { root =>
            (c.evaluate(root), d.evaluate(root)) match {
              case (Some(n), Some(length)) =>
                n > 0 && BigInt(coefficient) * (n - 1) + offset < length
              case _ => false
            }
          } && ownerCount.forall { owner =>
            owner.parameters == count.parameters &&
              owner.exactDomain.exists { o =>
                (o.root eq c.root) &&
                  o.evaluations.collect { case (root, n) if n > 0 => root }.toSet == admitted &&
                  admitted.forall(root => o.evaluate(root) == c.evaluate(root))
              }
          }
      case _ => false
    }
    (actualVector eq vector) && (actualSelector eq selector) &&
      (actualToken eq token) && coefficient > 0 && offset >= 0 &&
      ParameterizedVec.shapeOf(vector).exists(_.depth eq originalDepth) &&
      selector.generateIndex == baseIndex.generateIndex &&
      selector.parameters.isEmpty && selector.completedParameterRoots.isEmpty &&
      selector.exactDomain.isEmpty && selector.projectionProvenance.isEmpty &&
      selector.verilog == s"($coefficient * ${baseIndex.verilog} + $offset)" &&
      selector.default == offset && selector.minimum == offset &&
      selector.maximum == BigInt(coefficient) * (count.maximum - 1) + offset &&
      baseIndex.default == 0 && baseIndex.minimum == 0 &&
      baseIndex.generateIndex.contains(baseIndex.verilog) && domainMatches
  }
}

/** Scoped generate-time index for one exact typed finite range.
  *
  * The representative value exists only to let the inherited native Vec/Mem
  * algorithms construct one ordinary graph. Structural publication retains
  * [[expression]] and replaces that representative with the Verilog genvar.
  */
final class ElabFiniteIndex private[core] (
    private[core] val expression: ElaborationIntegerExpression,
    private[core] val count: ElaborationIntegerExpression,
    private[core] val token: ElabFiniteIndexToken
) {
  private def witness: Int = {
    if (!expression.default.isValidInt) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-INDEX-WITNESS-OUT-OF-RANGE",
        s"finite index witness ${expression.default} does not fit Scala Int",
        expression.sourceLocation
      )
    }
    expression.default.toInt
  }

  /** Select one element through the authoritative native Vec static access. */
  def apply[T <: Data](vector: Vec[T]): T = {
    if (vector == null)
      throw new IllegalArgumentException("finite-index Vec must not be null")
    val selected = vector(witness)
    if (ParameterizedStructure.captureEnabled && expression.generateIndex.nonEmpty) {
      val vectorDepth = ParameterizedVec
        .shapeOf(vector)
        .map(_.depth)
        .getOrElse(ElabInt.literal(vector.length).expression)
      if (!ElabFiniteRange.equivalentLogicalCount(vectorDepth, count)) {
        ParameterizedVerilogException.fail(
          "SPINAL-ELAB-FINITE-RANGE-VEC-DEPTH-MISMATCH",
          s"finite range '${count.verilog}' does not match Vec depth '${vectorDepth.verilog}'",
          expression.sourceLocation.orElse(vectorDepth.sourceLocation)
        )
      }
      return ParameterizedStructure.recordVecIndex(
        vector,
        selected,
        expression,
        token,
        expression.sourceLocation
      )
    }
    selected
  }

  /** Read `coefficient * index + offset` from a typed Vec. Every admitted
    * positive loop extent is checked against that same root's logical Vec
    * depth. This deliberately does not grant write-coverage evidence.
    */
  def affine[T <: Data](vector: Vec[T], coefficient: Int, offset: Int): T = {
    if (vector == null)
      throw new IllegalArgumentException("finite-index affine Vec must not be null")
    if (coefficient <= 0 || offset < 0)
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-AFFINE-COEFFICIENT-INVALID",
        "finite affine Vec reads require a positive coefficient and non-negative offset",
        expression.sourceLocation
      )
    val selectedWitness = BigInt(coefficient) * witness + offset
    if (!selectedWitness.isValidInt || selectedWitness < 0 || selectedWitness >= vector.carrierLength)
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-AFFINE-WITNESS-OUT-OF-RANGE",
        s"finite affine Vec witness $selectedWitness is outside its native carrier",
        expression.sourceLocation
      )
    if (!ParameterizedStructure.captureEnabled || expression.generateIndex.isEmpty)
      return vector(selectedWitness.toInt)

    val shape = ParameterizedVec.shapeOf(vector).getOrElse {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-AFFINE-VEC-SHAPE-MISSING",
        "finite affine Vec reads require identity-retained typed Vec geometry",
        expression.sourceLocation
      )
    }
    val projectedCount = ElabInt.projectExpression(count, "finite affine Vec loop count")
    val projectedDepth = ElabInt.projectExpression(shape.depth, "finite affine Vec depth")
    val countDomain = ElabFiniteRange.requireCompleteSymbolicDomain(
      projectedCount, "finite affine Vec loop count", "SPINAL-ELAB-FINITE-AFFINE-EXACT-DOMAIN-REQUIRED"
    )
    val depthDomain = ElabFiniteRange.requireCompleteSymbolicDomain(
      projectedDepth, "finite affine Vec depth", "SPINAL-ELAB-FINITE-AFFINE-EXACT-DOMAIN-REQUIRED"
    )
    val admitted = (countDomain, depthDomain) match {
      case (Some((c, roots)), Some((d, depthRoots)))
          if (c.root eq d.root) && roots == depthRoots && projectedCount.parameters == projectedDepth.parameters => roots
      case _ =>
        ParameterizedVerilogException.fail(
          "SPINAL-ELAB-FINITE-AFFINE-ROOT-MISMATCH",
          "finite affine Vec count and logical depth must retain the same complete exact root domain",
          expression.sourceLocation
        )
    }
    val maximum = BigInt(coefficient) * (projectedCount.maximum - 1) + offset
    if (!maximum.isValidInt || maximum >= shape.carrierCapacity)
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-AFFINE-DOMAIN-OUT-OF-RANGE",
        "finite affine Vec index exceeds the retained native carrier domain",
        expression.sourceLocation
      )
    val selector = ElaborationIntegerExpression(
      verilog = s"($coefficient * ${expression.verilog} + $offset)",
      default = selectedWitness,
      minimum = BigInt(offset),
      maximum = maximum,
      parameters = Vector.empty,
      generateIndex = expression.generateIndex,
      sourceLocation = expression.sourceLocation
    )
    val evidence = new ElabFiniteAffineVecRead(
      vector, selector, expression, projectedCount, shape.depth,
      projectedDepth, admitted, token, coefficient, offset
    )
    if (!evidence.matches(vector, selector, token))
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-AFFINE-DOMAIN-OUT-OF-RANGE",
        "finite affine Vec index exceeds the logical depth at an admitted exact-domain point",
        expression.sourceLocation
      )
    val selected = vector(selectedWitness.toInt)
    ParameterizedStructure.recordAffineVecRead(vector, selected, selector, token, evidence, expression.sourceLocation)
  }

  /** Select one bit from an exact-width packed carrier through this generated
    * index.
    *
    * The native witness remains one ordinary fixed 1-bit slice. Structural
    * publication may replace that slice only after proving that the exact
    * packed width is the same pointwise function as this index's enclosing
    * finite count. This dedicated correlated proof does not relax the generic
    * packed-slice rule for independently varying offsets and widths.
    */
  def apply(source: Bits): Bool = {
    if (source == null)
      throw new IllegalArgumentException("finite-index Bits must not be null")
    if (!ParameterizedStructure.captureEnabled || expression.generateIndex.isEmpty)
      return source(witness)

    val selected = source(witness, 1 bits)
    ParameterizedStructure.recordFiniteIndexSlice(
      source,
      selected,
      expression,
      count,
      token,
      expression.sourceLocation
    )
    selected.asBool
  }

  /** Select one word through an ordinary native asynchronous Mem port. */
  def apply[T <: Data](memory: Mem[T]): T = {
    if (memory == null)
      throw new IllegalArgumentException("finite-index Mem must not be null")
    val memoryDepth = ParameterizedMemory
      .depthExpressionOf(memory)
      .projectedExpression("typed finite-range Mem depth")
    if (
      expression.generateIndex.nonEmpty &&
      !ElabFiniteRange.equivalentLogicalCount(memoryDepth, count)
    ) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-RANGE-MEM-DEPTH-MISMATCH",
        s"finite range '${count.verilog}' does not match Mem depth '${memoryDepth.verilog}'",
        expression.sourceLocation.orElse(memoryDepth.sourceLocation)
      )
    }
    val selected = memory.readAsync(
      U(witness, memory.nativePortAddressWidth bits)
    )
    if (ParameterizedStructure.captureEnabled && expression.generateIndex.nonEmpty) {
      val port = memory.dlcLast match {
        case value: MemReadAsync => value
        case other =>
          ParameterizedVerilogException.fail(
            "SPINAL-ELAB-FINITE-INDEX-MEM-PORT-MISSING",
            s"finite Mem selection produced '${Option(other).map(_.getClass.getName).getOrElse("null")}', expected a native asynchronous read port",
            expression.sourceLocation
          )
      }
      ParameterizedStructure.recordMemoryIndex(
        memory,
        port,
        selected,
        expression,
        token,
        expression.sourceLocation
      )
    }
    selected
  }
}

/** Generic typed finite-range bridge for native library algorithms.
  *
  * Concrete or non-parameterized generation executes the ordinary Scala
  * range. Parameterized publication captures exactly one representative body
  * and retains the exact typed count as a Verilog-2001 generate-for bound.
  */
object ElabFiniteRange {
  private object StorageKey

  private final class Storage {
    var nextId = 0L
    var nextFoldId = 0L
    val stems = mutable.LinkedHashMap.empty[String, Int]
    val countOnes = mutable.ArrayBuffer.empty[ParameterizedFiniteCountOne]
  }

  /** Require exhaustive identity-bearing evidence for every symbolic value
    * consumed by a finite native algorithm. A branch projection is complete
    * when it covers every root value admitted by the active exact-domain
    * context; a partial table cannot escape that context and authorize a later
    * finite capture.
    */
  private[spinal] def requireCompleteSymbolicDomain(
      expression: ElaborationIntegerExpression,
      role: String,
      failureCode: String
  ): Option[(ElaborationExactDomain[BigInt], Set[BigInt])] = {
    ElabInt
      .requireAuthoritativeIntegerDomain(
        expression,
        role,
        failureCode,
        requireExactExtrema = true
      )
      .map { exact =>
        val admitted = ElaborationDomainContext.requireEvidence(
          exact,
          role,
          expression.sourceLocation
        )
        val representative = ElaborationDomainContext.representative(exact)
        val evaluations = exact.evaluations.filter { case (rootValue, _) =>
          admitted.contains(rootValue)
        }
        val results = evaluations.map(_._2)
        val narrowedProjectionMatches =
          admitted == exact.universe || expression.projectionProvenance.exists { projection =>
            (projection.root eq exact.root) &&
            projection.admitted == admitted &&
            projection.representative == representative
          }
        val activeSummaryMatches =
          results.nonEmpty && results.min == expression.minimum &&
            results.max == expression.maximum
        if (
          admitted.isEmpty || !narrowedProjectionMatches ||
          evaluations.size != admitted.size ||
          !activeSummaryMatches ||
          !exact.evaluate(representative).contains(expression.default)
        ) {
          ParameterizedVerilogException.fail(
            failureCode,
            s"$role symbolic expression '${expression.verilog}' does not match its complete active-domain evidence and representative",
            expression.sourceLocation.orElse(exact.root.sourceLocation)
          )
        }
        exact -> admitted
      }
  }

  /** Hardware OR fold over exactly `count` packed bits.
    *
    * The native reduction operator is already width-generic in Verilog. This
    * adapter exists to require exact typed width/count compatibility before the
    * logical fold is admitted.
    */
  def reduceOr(source: Bits, count: ElabInt): Bool = {
    val expression = validatePackedCount(source, count, "typed OR fold")
    if (expression.minimum < 1) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-FOLD-EMPTY-DOMAIN-UNSUPPORTED",
        s"typed OR fold count '${expression.verilog}' must remain positive",
        expression.sourceLocation
      )
    }
    source.orR
  }

  /** Hardware population count over exactly `count` packed bits.
    *
    * Symbolic publication lowers this identity-retained operation to one
    * strict-Verilog-2001 combinational integer loop. No Scala sequence is
    * constructed from the default witness. Concrete callers retain their
    * ordinary native population-count algorithm.
    */
  def countOne(source: Bits, count: ElabInt)(
      concrete: => UInt
  ): UInt = {
    val expression = validatePackedCount(source, count, "typed population count")
    if (expression.minimum < 1) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-FOLD-EMPTY-DOMAIN-UNSUPPORTED",
        s"typed population-count '${expression.verilog}' must remain positive",
        expression.sourceLocation
      )
    }
    if (expression.parameters.isEmpty) return concrete
    val component = Option(Component.current).getOrElse {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-FOLD-COMPONENT-MISSING",
        "typed population count requires an active Component",
        expression.sourceLocation
      )
    }
    val width = (count + 1).addressWidth.projectedExpression(
      "typed population-count result width"
    )
    val storage = storageOf(component)
    storage.nextFoldId += 1
    val ordinal = storage.nextFoldId
    if (Option(source.getName()).forall(_.isEmpty))
      source.setName(s"morphhdl_finite_fold_source_$ordinal")
    source.dontSimplifyIt()
    val result = UInt(ElabInt.fromExpression(width) bits)
      .setName(s"morphhdl_finite_count_one_$ordinal")
    result.dontSimplifyIt()
    val (_, assignments) = ParameterizedVec.captureAssignments(result) {
      result := 0
    }
    assignments match {
      case Vector(assignment) =>
        storage.countOnes += ParameterizedFiniteCountOne(
          source,
          result,
          assignment,
          expression,
          width,
          ordinal,
          expression.sourceLocation
        )
      case other =>
        ParameterizedVerilogException.fail(
          "SPINAL-ELAB-FINITE-FOLD-ANCHOR-MISMATCH",
          s"typed population count created ${other.size} native anchor assignments instead of one",
          expression.sourceLocation
        )
    }
    result
  }

  private[spinal] def countOnesOf(
      component: Component
  ): Vector[ParameterizedFiniteCountOne] =
    if (component == null) Vector.empty
    else
      component.userCache
        .get(StorageKey)
        .map(_.asInstanceOf[Storage].countOnes.toVector)
        .getOrElse(Vector.empty)

  def foreach(
      count: ElabInt,
      role: String
  )(body: ElabFiniteIndex => Unit): Unit = {
    if (count == null)
      throw new IllegalArgumentException("typed finite count must not be null")
    if (body == null)
      throw new IllegalArgumentException("typed finite-range body must not be null")

    count.requireAuthoritativeIntegerDomain(
      role,
      "SPINAL-ELAB-FINITE-RANGE-EXACT-DOMAIN-REQUIRED",
      requireExactExtrema = false
    )
    val expression = count.projectedExpression(role)
    val indexToken = new ElabFiniteIndexToken()
    if (
      expression.minimum < 0 || expression.maximum < expression.minimum ||
      expression.maximum > BigInt(Int.MaxValue)
    ) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-RANGE-DOMAIN-INVALID",
        s"$role expression '${expression.verilog}' must remain in the finite non-negative Int domain, but reaches [${expression.minimum}, ${expression.maximum}]",
        expression.sourceLocation
      )
    }
    val exactDomain = requireCompleteSymbolicDomain(
      expression,
      role,
      "SPINAL-ELAB-FINITE-RANGE-EXACT-DOMAIN-REQUIRED"
    )

    if (expression.parameters.isEmpty) {
      var index = 0
      while (index < count.witness) {
        body(
          new ElabFiniteIndex(
            ElaborationIntegerExpression(
              verilog = index.toString,
              default = BigInt(index),
              minimum = BigInt(index),
              maximum = BigInt(index),
              parameters = Vector.empty,
              sourceLocation = expression.sourceLocation
            ),
            expression,
            indexToken
          )
        )
        index += 1
      }
      return
    }

    if (!ParameterizedStructure.captureEnabled) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-RANGE-SYMBOLIC-CAPTURE-REQUIRED",
        s"$role expression '${expression.verilog}' is symbolic and cannot be witness-unrolled outside parameterized structural capture",
        expression.sourceLocation
      )
    }

    val positiveRootValues = exactDomain.toVector.flatMap { case (exact, admitted) =>
      exact.evaluations.collect {
        case (rootValue, result) if admitted.contains(rootValue) && result > 0 =>
          rootValue
      }
    }.toSet
    if (positiveRootValues.isEmpty) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-RANGE-POSITIVE-WITNESS-REQUIRED",
        s"$role expression '${expression.verilog}' needs at least one exact positive-domain point to capture the index-zero representative body",
        expression.sourceLocation
      )
    }
    val component = Option(Component.current).getOrElse {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-RANGE-COMPONENT-MISSING",
        s"$role requires an active Component",
        expression.sourceLocation
      )
    }
    val names = allocateNames(component, role)
    val indexExpression = ElaborationIntegerExpression(
      verilog = names._2,
      default = BigInt(0),
      minimum = BigInt(0),
      maximum = expression.maximum - 1,
      parameters = Vector.empty,
      generateIndex = Some(names._2),
      sourceLocation = expression.sourceLocation
    )
    val exactRoot = exactDomain.map(_._1.root).getOrElse {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-RANGE-EXACT-DOMAIN-REQUIRED",
        s"$role expression '${expression.verilog}' lost its exact root before structural capture",
        expression.sourceLocation
      )
    }
    val block = ParameterizedStructure.captureExactBlock(
      component,
      exactRoot,
      positiveRootValues,
      expression.sourceLocation
    ) {
      body(new ElabFiniteIndex(indexExpression, expression, indexToken))
    }
    ParameterizedStructure.registerExactFor(
      component,
      names._1,
      names._2,
      expression,
      block,
      indexToken,
      expression.sourceLocation
    )
  }

  private def allocateNames(
      component: Component,
      role: String
  ): (String, String) = {
    val storage = component.userCache
      .getOrElseUpdate(StorageKey, new Storage)
      .asInstanceOf[Storage]
    storage.nextId += 1
    val stem = Option(role)
      .getOrElse("finite_range")
      .replaceAll("[^A-Za-z0-9_]", "_")
      .replaceAll("_+", "_")
      .stripPrefix("_")
      .stripSuffix("_") match {
      case value if value.nonEmpty && value.charAt(0).isDigit => s"_$value"
      case value if value.nonEmpty                            => value
      case _                                                  => "finite_range"
    }
    val ordinal = storage.stems.getOrElse(stem, 0) + 1
    storage.stems(stem) = ordinal
    val suffix = s"${storage.nextId}_$ordinal"
    s"g_${stem}_$suffix" -> s"${stem}_index_$suffix"
  }

  private def storageOf(component: Component): Storage =
    component.userCache
      .getOrElseUpdate(StorageKey, new Storage)
      .asInstanceOf[Storage]

  private def validatePackedCount(
      source: Bits,
      count: ElabInt,
      role: String
  ): ElaborationIntegerExpression = {
    if (source == null)
      throw new IllegalArgumentException(s"$role source must not be null")
    if (count == null)
      throw new IllegalArgumentException(s"$role count must not be null")
    count.requireAuthoritativeIntegerDomain(
      role,
      "SPINAL-ELAB-FINITE-FOLD-EXACT-DOMAIN-REQUIRED",
      requireExactExtrema = false
    )
    val expression = count.projectedExpression(role)
    if (
      expression.minimum < 0 || expression.maximum < expression.minimum ||
      expression.maximum > BigInt(Int.MaxValue)
    ) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-FOLD-COUNT-DOMAIN-INVALID",
        s"$role count '${expression.verilog}' must remain in the finite non-negative Int domain, but reaches [${expression.minimum}, ${expression.maximum}]",
        expression.sourceLocation
      )
    }
    requireCompleteSymbolicDomain(
      expression,
      role,
      "SPINAL-ELAB-FINITE-FOLD-EXACT-DOMAIN-REQUIRED"
    )
    val retainedWidth = ParameterizedVec
      .packedWidthExpressionOf(source)
      .orElse(ParameterizedWidth.expressionOf(source))
      .orElse(
        if (expression.parameters.isEmpty)
          Some(ElabInt.literal(source.getBitsWidth).expression)
        else None
      )
      .getOrElse {
        ParameterizedVerilogException.fail(
          "SPINAL-ELAB-FINITE-FOLD-SOURCE-WIDTH-MISSING",
          s"$role source has only native witness width ${source.getBitsWidth}; exact typed width provenance is required",
          expression.sourceLocation
        )
      }
    val width = ElabInt.projectExpression(
      retainedWidth,
      s"$role source width"
    )
    requireCompleteSymbolicDomain(
      width,
      s"$role source width",
      "SPINAL-ELAB-FINITE-FOLD-EXACT-DOMAIN-REQUIRED"
    )
    if (!equivalentLogicalCount(width, expression)) {
      ParameterizedVerilogException.fail(
        "SPINAL-ELAB-FINITE-FOLD-WIDTH-MISMATCH",
        s"$role source width '${width.verilog}' does not match count '${expression.verilog}'",
        width.sourceLocation.orElse(expression.sourceLocation)
      )
    }
    expression
  }

  /** Equality for one logical finite count projected independently at two
    * exact native boundaries. Projection objects intentionally retain their
    * own construction identity, so compare their pointwise tables only after
    * root identity and parameter schema agree. Rendered algebra may differ
    * (`DEPTH` versus `1 * DEPTH`) without changing the exact logical count.
    */
  private[core] def equivalentLogicalCount(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean =
    ElabInt.equivalentExpression(left, right) || {
      left.parameters == right.parameters &&
      left.generateIndex == right.generateIndex &&
      left.completedParameterRoots.size == right.completedParameterRoots.size &&
      left.completedParameterRoots.zip(right.completedParameterRoots).forall { case (l, r) =>
        l eq r
      } &&
      ((left.exactDomain, right.exactDomain) match {
        case (Some(l), Some(r)) if l.root eq r.root =>
          ElabInt
            .activeDomainEvaluations(l, "finite-count left", left.sourceLocation)
            .toMap ==
            ElabInt
              .activeDomainEvaluations(r, "finite-count right", right.sourceLocation)
              .toMap
        // Bounds are not a proof that two symbolic functions are equal (for
        // example, distinct expressions can share a default, minimum and
        // maximum). Parameter-free literals are already handled by
        // equivalentExpression above; every remaining symbolic comparison
        // therefore requires the exact pointwise tables in the case above.
        case (None, None) => false
        case _            => false
      })
    }
}
