package spinal.core.internals

import java.util.IdentityHashMap
import java.util.regex.Pattern

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core._

/** Generic publication of one identity-retained typed [[Vec]] as a packed
  * IEEE-1364 vector.
  *
  * Native SpinalHDL keeps the logical Vec and builds a finite, audited carrier
  * graph.  This pass consumes only [[ParameterizedVec]] identity metadata and
  * replaces that carrier at the final publication boundary.  It never treats
  * a [[Mem]] declaration as a Vec and never discovers a Vec from emitted-name
  * conventions.
  */
private[internals] object ParameterizedVerilogVecs {
  private val PortableIdentifier = "[A-Za-z_][A-Za-z0-9_$]*".r
  private val SyntheticAggregatePrefix = "morphhdl_typed_vec_"

  private final case class Leaf(
      value: BaseType,
      name: String,
      elementIndex: Int,
      leafIndex: Int,
      shape: ParameterizedVecLeafShape
  )

  private final case class VecPlan(
      vector: Vec[_],
      shape: ParameterizedVecShape,
      name: String,
      leaves: Vector[Leaf],
      elementWidth: String,
      range: String,
      sourceLocation: Option[String]
  ) {
    val leafNames: Set[String] = leaves.map(_.name).toSet

    def leaf(elementIndex: Int, leafIndex: Int): Leaf =
      leaves.find(value => value.elementIndex == elementIndex && value.leafIndex == leafIndex).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-LEAF-MISSING",
          s"Vec '$name' has no retained leaf ($elementIndex, $leafIndex)",
          sourceLocation
        )
      }

    def offsetOf(leafIndex: Int): String =
      renderSum(shape.elementLeaves.take(leafIndex).map(_.width))

    def constantSlice(elementIndex: Int, leafIndex: Int): String = {
      val base = addTerms(
        if (elementIndex == 0) "0"
        else s"$elementIndex * ${factor(elementWidth)}",
        offsetOf(leafIndex)
      )
      s"$name[${parenthesize(base)} +: ${render(shape.elementLeaves(leafIndex).width)}]"
    }

    def dynamicSlice(
        address: String,
        leafIndex: Int,
        clampRead: Boolean
    ): String = {
      val selected =
        if (clampRead) {
          val depth = render(shape.depth)
          s"(($address) < ($depth) ? ($address) : (($depth) - 1))"
        } else address
      val base = addTerms(
        s"${parenthesize(selected)} * ${factor(elementWidth)}",
        offsetOf(leafIndex)
      )
      s"$name[${parenthesize(base)} +: ${render(shape.elementLeaves(leafIndex).width)}]"
    }
  }

  private final case class ParsedAssignment(
      lineIndex: Int,
      indentation: String,
      continuous: Boolean,
      operator: String,
      rhs: String
  )

  private final case class ParsedDeclaration(
      lineIndex: Int,
      indentation: String,
      syntax: String,
      direction: Option[String],
      net: String,
      comma: Boolean,
      declaratorStart: Int,
      declaratorEnd: Int
  )

  private final case class CaseBlock(
      start: Int,
      caseLine: Int,
      end: Int,
      select: String
  )

  private final case class DynamicReadSupport(
      resultNames: Vector[String],
      muxNames: Vector[String],
      assignmentsByBlock: Vector[(CaseBlock, Vector[(Int, String)])]
  )

  private final case class AlwaysBlock(start: Int, end: Int)

  private final case class WholeAssignmentRewrite(
      lines: Vector[String],
      consumedDynamicWrites: Vector[ParameterizedVecDynamicWrite]
  )

  private final case class DynamicWriteAssignment(
      statement: DataAssignmentStatement,
      parsed: ParsedAssignment,
      leaf: Leaf,
      owner: AlwaysBlock
  )

  private final case class ParsedDynamicWrite(
      operation: ParameterizedVecDynamicWrite,
      rhs: String,
      operator: String,
      assignments: Vector[DynamicWriteAssignment]
  )

  private final case class PackedAssignmentProof(
      lo: Int,
      width: Int,
      supportAssignments: Vector[DataAssignmentStatement]
  )

  private final case class PackedReadProof(
      leavesLowToHigh: Vector[BaseType],
      supportAssignments: Vector[DataAssignmentStatement]
  )

  private final case class PackedReadRewrite(
      lines: Vector[String],
      supportAssignments: Vector[DataAssignmentStatement]
  )

  private[internals] final case class PackedReadAggregateDeclarationUse(
      consumerLines: Set[Int],
      declarations: Vector[(BaseType, Int)],
      consumers: Vector[DataAssignmentStatement],
      root: ElaborationIntegerParameterRoot,
      universe: Set[BigInt],
      role: String,
      sourceLocation: Option[String]
  )

  private[internals] final case class PackedReadStructuralEvidence(
      transientLines: Set[Int],
      coveredLeafUses: Set[(Int, String)],
      aggregateDeclarationUses: Vector[PackedReadAggregateDeclarationUse]
  )

  def hasVectors(component: Component): Boolean =
    component != null && ParameterizedVec.retainedVectorsOf(component).nonEmpty

  def parametersOf(
      component: Component
  ): Vector[ElaborationIntegerParameter] =
    ParameterizedVec.parametersOf(component)

  /** Narrow admission proof for an otherwise output-only native surface.
    * Every port must be an exact flattened carrier leaf of one publication
    * Vec, and every such owning Vec must participate in an exact retained
    * structural selection. Names, widths and component classes are not
    * discovery evidence.
    */
  private[internals] def isExactStructuralOutputSurface(
      component: Component,
      ports: Vector[BaseType]
  ): Boolean = {
    if (
      component == null || ports.isEmpty ||
      !ports.forall(port => port.isOutput && !port.isInput && !port.isInOut)
    )
      return false
    val vectors = publicationVectors(component)
    val selections = structuralVecSelectionsOf(component)
    val owners = ArrayBuffer.empty[Vec[_]]
    val exactCoverage = ports.forall { port =>
      val matches = vectors.filter(vector => vectorLeaves(vector).exists(_ eq port))
      if (matches.size != 1) false
      else {
        val owner = matches.head
        if (!owners.exists(_ eq owner)) owners += owner
        true
      }
    }
    exactCoverage && owners.nonEmpty && owners.forall(owner => selections.exists(_.vector eq owner))
  }

  /** Render one exact constant leaf of an identity-retained typed Vec for the
    * structural pass. The retained Vec object and logical ordinals are the
    * authority; native carrier names are not used to discover the relation.
    */
  private[internals] def structuralConstantSlice(
      vector: Vec[_],
      elementIndex: Int,
      leafIndex: Int,
      sourceLocation: Option[String]
  ): String = {
    val shape = ParameterizedVec.shapeOf(vector).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-SHAPE-MISSING",
        "structural constant slice requires an identity-retained typed Vec",
        sourceLocation
      )
    }
    if (elementIndex < 0 || elementIndex >= shape.carrierCapacity) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-INDEX-INVALID",
        s"structural Vec element $elementIndex is outside carrier capacity ${shape.carrierCapacity}",
        sourceLocation.orElse(shape.sourceLocation)
      )
    }
    val element = vector.vec(elementIndex).asInstanceOf[Data]
    val leaves = element.flatten.toVector
    val paths = element.flattenLocalName.toVector
    if (
      leafIndex < 0 || leafIndex >= shape.elementLeaves.size ||
      leaves.size != shape.elementLeaves.size || paths.size != leaves.size
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-LAYOUT-MISMATCH",
        s"structural Vec element $elementIndex exposes ${leaves.size} leaves for retained layout ${shape.elementLeaves.size}",
        sourceLocation.orElse(shape.sourceLocation)
      )
    }
    leaves.zip(paths).zip(shape.elementLeaves).zipWithIndex.foreach { case (((leaf, path), expected), ordinal) =>
      val width = ParameterizedWidth
        .expressionOf(leaf)
        .getOrElse(ElabInt.literal(leaf.getBitsWidth).expression)
      if (
        Option(path).getOrElse("") != expected.path ||
        (leaf.getTypeObject.asInstanceOf[AnyRef] ne expected.typeObject) ||
        !ElabInt.equivalentExpression(width, expected.width)
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-LAYOUT-MISMATCH",
          s"structural Vec element $elementIndex leaf $ordinal changed its retained path, type or width",
          sourceLocation.orElse(shape.sourceLocation)
        )
      }
    }
    val name = requiredVecName(vector, sourceLocation.orElse(shape.sourceLocation))
    if (isSignedLeaf(shape.elementLeaves(leafIndex))) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-SIGNED-SLICE-UNSUPPORTED",
        s"structural constant SInt leaf $leafIndex of Vec '$name' requires context-sensitive signed lowering",
        sourceLocation.orElse(shape.sourceLocation)
      )
    }
    val elementWidth = renderSum(shape.elementLeaves.map(_.width))
    val offset = renderSum(shape.elementLeaves.take(leafIndex).map(_.width))
    val base = addTerms(
      if (elementIndex == 0) "0"
      else s"$elementIndex * ${factor(elementWidth)}",
      offset
    )
    s"$name[${parenthesize(base)} +: ${render(shape.elementLeaves(leafIndex).width)}]"
  }

  /** Audit the emitted aggregate spelling only after a caller has selected
    * the exact retained Vec object through structural identity evidence.  The
    * name cannot discover or authorize a Vec relation; it merely confirms
    * that a finalized body still refers to that already-proven aggregate.
    */
  private[internals] def isExactStructuralAggregateName(
      vector: Vec[_],
      name: String
  ): Boolean =
    vector != null && name != null &&
      ParameterizedVec.shapeOf(vector).exists { shape =>
        Option(vector.component).exists { component =>
          publicationVectors(component).exists(_ eq vector) &&
          requiredVecName(vector, shape.sourceLocation) == name
        }
      }

  /** Render one generate-indexed leaf of an exact retained typed Vec.
    *
    * The caller has already proved finite-range count/depth equality while
    * recording the [[ParameterizedStructure.StructuralVecIndex]].  Recheck the
    * retained physical domain and element layout here before replacing its
    * witness alias with one packed indexed part-select.  Concrete Vecs retain
    * the older finite case expansion.
    */
  private[internals] def structuralDynamicSlice(
      vector: Vec[_],
      selector: ElaborationIntegerExpression,
      leafIndex: Int,
      sourceLocation: Option[String]
  ): String = {
    if (vector == null || selector == null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-DYNAMIC-NULL",
        "structural dynamic Vec slice requires non-null vector and selector",
        sourceLocation
      )
    }
    val shape = ParameterizedVec.shapeOf(vector).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-SHAPE-MISSING",
        "structural dynamic slice requires an identity-retained typed Vec",
        sourceLocation
      )
    }
    ElabInt.validateExpression(selector, "structural typed Vec selector")
    val indexName = selector.generateIndex.getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-INDEX-MISSING",
        s"structural typed Vec selector '${selector.verilog}' has no retained generate-index identity",
        sourceLocation.orElse(selector.sourceLocation)
      )
    }
    if (
      selector.verilog != indexName || selector.parameters.nonEmpty ||
      selector.default != 0 || selector.minimum != 0 ||
      selector.maximum != shape.depth.maximum - 1 ||
      shape.depth.maximum != BigInt(shape.carrierCapacity)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-DOMAIN-MISMATCH",
        s"structural typed Vec selector '${selector.verilog}' in [${selector.minimum}, ${selector.maximum}] does not cover exactly the retained carrier domain 0 until ${shape.carrierCapacity}",
        sourceLocation.orElse(selector.sourceLocation).orElse(shape.sourceLocation)
      )
    }
    if (leafIndex < 0 || leafIndex >= shape.elementLeaves.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-LAYOUT-MISMATCH",
        s"structural dynamic Vec leaf $leafIndex is outside retained element layout ${shape.elementLeaves.size}",
        sourceLocation.orElse(shape.sourceLocation)
      )
    }
    vector.vec.zipWithIndex.foreach { case (element, elementIndex) =>
      val leaves = element.asInstanceOf[Data].flatten.toVector
      val paths = element.asInstanceOf[Data].flattenLocalName.toVector
      if (leaves.size != shape.elementLeaves.size || paths.size != leaves.size) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-LAYOUT-MISMATCH",
          s"structural typed Vec element $elementIndex exposes ${leaves.size} leaves and ${paths.size} paths for retained layout ${shape.elementLeaves.size}",
          sourceLocation.orElse(shape.sourceLocation)
        )
      }
      leaves.zip(paths).zip(shape.elementLeaves).zipWithIndex.foreach { case (((leaf, path), expected), ordinal) =>
        val width = ParameterizedWidth
          .expressionOf(leaf)
          .getOrElse(ElabInt.literal(leaf.getBitsWidth).expression)
        if (
          Option(path).getOrElse("") != expected.path ||
          (leaf.getTypeObject.asInstanceOf[AnyRef] ne expected.typeObject) ||
          !ElabInt.equivalentExactFunction(width, expected.width)
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-LAYOUT-MISMATCH",
            s"structural typed Vec element $elementIndex leaf $ordinal changed its retained path, type or width",
            sourceLocation.orElse(shape.sourceLocation)
          )
        }
      }
    }
    val name = requiredVecName(vector, sourceLocation.orElse(shape.sourceLocation))
    if (isSignedLeaf(shape.elementLeaves(leafIndex))) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-SIGNED-SLICE-UNSUPPORTED",
        s"structural dynamic SInt leaf $leafIndex of Vec '$name' requires context-sensitive signed lowering",
        sourceLocation.orElse(shape.sourceLocation)
      )
    }
    val elementWidth = renderSum(shape.elementLeaves.map(_.width))
    val offset = renderSum(shape.elementLeaves.take(leafIndex).map(_.width))
    val base = addTerms(
      s"${parenthesize(indexName)} * ${factor(elementWidth)}",
      offset
    )
    s"$name[${parenthesize(base)} +: ${render(shape.elementLeaves(leafIndex).width)}]"
  }

  /** Authorize native width validation only for the exact statements retained
    * by a core-validated Vec packed assignment in this owning component.
    * Shape compatibility is proven when that operation is recorded; emitted
    * names and matching witness widths are deliberately not evidence here.
    */
  def isExactPackedAssignment(
      component: Component,
      assignment: DataAssignmentStatement
  ): Boolean =
    component != null && assignment != null &&
      ParameterizedVec.vectorsOf(component).exists { vector =>
        ParameterizedVec.operationsOf(vector).exists {
          case value: ParameterizedVecPackedAssignment =>
            value.assignments.exists(_ eq assignment)
          case _ => false
        }
      }

  /** Identify only native support assignments that the exact retained
    * `MultiData.asBits` carrier proof will erase during Vec publication.
    *
    * Structural capture runs before Vec publication. A packed read formed
    * outside a captured owner therefore needs a two-part handoff: transient
    * printer aliases are removed by [[rewritePackedReadCarrierGraph]], while
    * the exact module-owned leaf declarations needed by the surviving packed
    * aggregate consumer remain at module scope. Both parts are authorized only
    * after re-auditing the retained assignment identities, complete disjoint
    * captured-driver coverage, exact declaration identities and carrier
    * layout. Expose those emitted locations here so structural lowering can
    * defer only the proven aliases and avoid relocating only the proven native
    * declarations. A name, RHS text, width, or coincident assignment can never
    * create this evidence.
    */
  def exactPackedReadStructuralEvidence(
      component: Component,
      lines: Vector[String],
      pc: PhaseContext
  ): PackedReadStructuralEvidence = {
    if (component == null || lines.isEmpty)
      return PackedReadStructuralEvidence(Set.empty, Set.empty, Vector.empty)

    val live = new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    val liveAssignments = ArrayBuffer.empty[DataAssignmentStatement]
    component.dslBody.walkStatements {
      case assignment: DataAssignmentStatement =>
        live.put(assignment, java.lang.Boolean.TRUE)
        liveAssignments += assignment
      case _ =>
    }
    // Captured witness-inactive statements can still remain discoverable in
    // the native DSL graph. Count each exact assignment identity once: a
    // duplicate entry would make two identical branch domains look like
    // overlapping independent drivers and reject otherwise exhaustive
    // coverage.
    val coverageAssignments =
      liveAssignments.toVector ++
        ParameterizedStructure
          .capturedWitnessInactiveDataAssignmentsOf(component)
          .filterNot(live.containsKey)

    def exactCompleteDepth(plan: VecPlan) =
      plan.shape.depth.exactDomain.filter { domain =>
        domain.evaluations.size == domain.evidenceValues.size &&
        domain.evidenceValues == domain.universe &&
        (plan.shape.depth.completedParameterRoots match {
          case Vector(root) => root eq domain.root
          case _            => false
        })
      }

    def exactInternalDeclarations(plan: VecPlan): Vector[(BaseType, Int)] =
      plan.leaves.flatMap { leaf =>
        val declaration = parseDeclaration(
          lines,
          leaf.name,
          plan.sourceLocation
        )
        if (declaration.direction.isEmpty)
          Vector(leaf.value -> declaration.lineIndex)
        else Vector.empty
      }

    val plans = analyze(component, publicationVectors(component), pc)
    val evidence = plans.flatMap { plan =>
      val operations = ParameterizedVec.operationsOf(plan.vector)
      operations.flatMap {
        case operation: ParameterizedVecPackedRead =>
          val expected = plan.leaves.map(_.value)
          val carriers = operation.carrierAssignments.filter(assignment =>
            (assignment.finalTarget eq operation.carrier) &&
              live.containsKey(assignment)
          )
          val proof = carriers match {
            case Vector(carrier) =>
              exactPackedReadLeaves(
                carrier.source,
                expected,
                operation.carrier,
                live
              )
            case _ => None
          }
          val exactRecordedLayout =
            operation.carrierLeavesLowToHigh.size == expected.size &&
              operation.carrierLeavesLowToHigh.zip(expected).forall { case (actual, retained) =>
                actual eq retained
              }
          proof
            .filter(value =>
              exactRecordedLayout &&
                value.leavesLowToHigh.size == expected.size &&
                value.leavesLowToHigh.zip(expected).forall { case (actual, retained) =>
                  actual eq retained
                }
            )
            .toVector
            .map { exact =>
              val carrierName = requiredBaseName(
                carriers.head.finalTarget,
                "packed Vec read transient carrier",
                operation.sourceLocation
              )
              val carrier = findAssignment(
                lines,
                carrierName,
                None,
                "packed Vec read transient carrier",
                operation.sourceLocation
              )
              val aliases = packedReadCarrierAliases(
                lines,
                carrier,
                carrierName,
                plan,
                exact.supportAssignments,
                live,
                operation.sourceLocation
              ).map(_._2)
              val coveredLeaves = exactCapturedLeafCoverage(
                component,
                plan,
                coverageAssignments
              )
              val coveredUses = (carrier +: aliases).flatMap { parsed =>
                if (!parsed.continuous) Vector.empty
                else
                  coveredLeaves.collect {
                    case leafName if containsReferenceIdentifier(parsed.rhs, leafName) =>
                      parsed.lineIndex -> leafName
                  }.toVector
              }.toSet
              val exactAggregateUse =
                exactCompleteDepth(plan)
                  .filter(_ =>
                    carrier.continuous &&
                      coveredLeaves == plan.leaves.map(_.name).toSet
                  )
                  .toVector
                  .map { domain =>
                    PackedReadAggregateDeclarationUse(
                      Set(carrier.lineIndex),
                      exactInternalDeclarations(plan),
                      Vector(carriers.head),
                      domain.root,
                      domain.universe,
                      "packed Vec read aggregate consumer",
                      operation.sourceLocation
                    )
                  }
              PackedReadStructuralEvidence(
                aliases.map(_.lineIndex).toSet,
                coveredUses,
                exactAggregateUse
              )
            }

        case operation: ParameterizedVecWholeAssignment =>
          plans
            .find(_.vector eq operation.source)
            .toVector
            .flatMap { source =>
              val coveredLeaves = exactCapturedLeafCoverage(
                component,
                source,
                coverageAssignments
              )
              if (coveredLeaves.isEmpty) Vector.empty
              else {
                requireCompatible(plan, source, operation.sourceLocation)
                validateWholeAssignmentLineage(
                  plan,
                  source,
                  operation.assignments,
                  live,
                  "branch-covered whole Vec assignment",
                  operation.sourceLocation
                )
                val parsedUses = plan.leaves.map { targetLeaf =>
                  val sourceLeaf = source.leaf(
                    targetLeaf.elementIndex,
                    targetLeaf.leafIndex
                  )
                  val assignment = operation.assignments
                    .find(value =>
                      (value.finalTarget eq targetLeaf.value) &&
                        (value.source eq sourceLeaf.value)
                    )
                    .get
                  val targetName = requiredBaseName(
                    assignment.finalTarget,
                    "branch-covered whole Vec target",
                    operation.sourceLocation
                  )
                  val parsed = findAssignment(
                    lines,
                    targetName,
                    Some(sourceLeaf.name),
                    "branch-covered whole Vec assignment",
                    operation.sourceLocation
                  )
                  (assignment, sourceLeaf, parsed)
                }
                val coveredUses = parsedUses.flatMap {
                  case (_, sourceLeaf, parsed) if coveredLeaves(sourceLeaf.name) && parsed.continuous =>
                    Vector(parsed.lineIndex -> sourceLeaf.name)
                  case _ => Vector.empty
                }.toSet
                val exactAggregateUse =
                  exactCompleteDepth(source)
                    .filter(_ =>
                      coveredLeaves == source.leaves.map(_.name).toSet &&
                        parsedUses.forall(_._3.continuous)
                    )
                    .toVector
                    .map { domain =>
                      PackedReadAggregateDeclarationUse(
                        parsedUses.map(_._3.lineIndex).toSet,
                        exactInternalDeclarations(source),
                        parsedUses.map(_._1),
                        domain.root,
                        domain.universe,
                        "branch-covered whole Vec aggregate consumer",
                        operation.sourceLocation
                      )
                    }
                Vector(
                  PackedReadStructuralEvidence(
                    Set.empty,
                    coveredUses,
                    exactAggregateUse
                  )
                )
              }
            }
        case _ => Vector.empty[PackedReadStructuralEvidence]
      }
    }
    PackedReadStructuralEvidence(
      evidence.flatMap(_.transientLines).toSet,
      evidence.flatMap(_.coveredLeafUses).toSet,
      evidence.flatMap(_.aggregateDeclarationUses)
    )
  }

  /** Prove the exact root values for which each native carrier leaf is live
    * are covered once by captured structural drivers. This is deliberately
    * stricter than recognizing a packed-read graph: a module-scope temporary
    * may consume a branch-owned leaf only when every live value is covered by
    * pairwise-disjoint assignment identities over the same opaque root.
    */
  private def exactCapturedLeafCoverage(
      component: Component,
      plan: VecPlan,
      liveAssignments: Vector[DataAssignmentStatement]
  ): Set[String] = {
    val exactDepth = plan.shape.depth.exactDomain.filter { domain =>
      domain.evaluations.size == domain.evidenceValues.size &&
      domain.evidenceValues == domain.universe &&
      (plan.shape.depth.completedParameterRoots match {
        case Vector(root) => root eq domain.root
        case _            => false
      })
    }
    exactDepth.toVector.flatMap { depthDomain =>
      plan.leaves.flatMap { leaf =>
        val drivers = liveAssignments.filter(_.finalTarget eq leaf.value)
        val domains = drivers.map(assignment =>
          ParameterizedStructure.capturedAssignmentDomainOf(
            component,
            assignment
          )
        )
        val structuralDomains = exactStructuralVecWriteDomains(
          component,
          plan,
          leaf
        )
        val expectedValues = depthDomain.evaluations.collect {
          case (rootValue, depth) if depth > BigInt(leaf.elementIndex) =>
            rootValue
        }.toSet
        val exactDomains =
          domains.flatten ++ structuralDomains.toVector.flatten
        val sameRoot = exactDomains.headOption.exists { first =>
          first.root.elaborationRoot.exists(_ eq depthDomain.root) &&
          exactDomains.forall { domain =>
            (domain.root eq first.root) &&
            domain.root.elaborationRoot.exists(_ eq depthDomain.root)
          }
        }
        val coveredDomains = exactDomains
          .map(_.values intersect expectedValues)
          .filter(_.nonEmpty)
        val pairwiseDisjoint = coveredDomains.indices.forall { left =>
          (left + 1 until coveredDomains.size).forall { right =>
            (coveredDomains(left) intersect coveredDomains(right)).isEmpty
          }
        }
        val coveredValues = coveredDomains.foldLeft(Set.empty[BigInt]) {
          case (known, values) => known ++ values
        }
        if (
          (drivers.nonEmpty || structuralDomains.exists(_.nonEmpty)) &&
          domains.forall(_.nonEmpty) && structuralDomains.nonEmpty &&
          exactDomains.size ==
            drivers.size + structuralDomains.toVector.flatten.size &&
          expectedValues.nonEmpty &&
          sameRoot && pairwiseDisjoint && coveredValues == expectedValues
        ) Vector(leaf.name)
        else Vector.empty
      }
    }.toSet
  }

  /** Correlate one branch-projected finite loop with the full logical Vec
    * depth only over the exact root values which own the captured write.
    *
    * A finite loop constructed inside a typed branch deliberately retains a
    * partial exact table (for example DEPTH=2..8), while the Vec shape retains
    * the complete table (DEPTH=1..8). Generic expression equality must reject
    * that pair. For this ownership proof, however, the captured assignment's
    * exact domain is the authority for the narrower comparison: the loop must
    * carry that exact projection, the Vec must carry complete evidence for the
    * same opaque root and schema, and both functions must agree pointwise at
    * every admitted value. Rendered expressions, summaries and witnesses are
    * never correlation evidence.
    */
  private def exactProjectedLoopCountMatchesVecDepth(
      loopCount: ElaborationIntegerExpression,
      vectorDepth: ElaborationIntegerExpression,
      assignmentDomain: ParameterizedStructure.CapturedAssignmentDomain
  ): Boolean = {
    if (loopCount == null || vectorDepth == null || assignmentDomain == null)
      return false

    assignmentDomain.root.elaborationRoot.exists { root =>
      val structuralUniverse =
        ElaborationExactDomain
          .boundedValues(
            assignmentDomain.root.minimum,
            assignmentDomain.root.maximum
          )
          .toSet
      (for {
        countDomain <- loopCount.exactDomain
        depthDomain <- vectorDepth.exactDomain
        countProjection <- loopCount.projectionProvenance
      } yield {
        val exactRoots =
          (countDomain.root eq root) &&
            (depthDomain.root eq root) &&
            (countProjection.root eq root) &&
            (loopCount.completedParameterRoots match {
              case Vector(value) => value eq root
              case _             => false
            }) &&
            (vectorDepth.completedParameterRoots match {
              case Vector(value) => value eq root
              case _             => false
            })
        val exactSchema =
          (countDomain.parameter eq depthDomain.parameter) &&
            root.isAuthoritativeSchema(countDomain.parameter) &&
            (loopCount.parameters match {
              case Vector(parameter) => parameter eq countDomain.parameter
              case _                 => false
            }) &&
            (vectorDepth.parameters match {
              case Vector(parameter) => parameter eq depthDomain.parameter
              case _                 => false
            }) &&
            countDomain.parameter.default == assignmentDomain.root.default &&
            countDomain.parameter.minimum == assignmentDomain.root.minimum &&
            countDomain.parameter.maximum == assignmentDomain.root.maximum
        val expectedRepresentative =
          if (assignmentDomain.values(countDomain.parameter.default))
            countDomain.parameter.default
          else assignmentDomain.values.min
        val exactProjection =
          countProjection.admitted == assignmentDomain.values &&
            countDomain.universe == structuralUniverse &&
            countDomain.evidenceValues == assignmentDomain.values &&
            countDomain.evaluations.size == assignmentDomain.values.size &&
            countProjection.representative == expectedRepresentative &&
            countDomain
              .evaluate(countProjection.representative)
              .contains(loopCount.default)
        val completeDepth =
          depthDomain.evidenceValues == structuralUniverse &&
            depthDomain.universe == structuralUniverse &&
            depthDomain.evaluations.size == structuralUniverse.size &&
            vectorDepth.projectionProvenance.forall { projection =>
              (projection.root eq root) &&
              projection.admitted == structuralUniverse
            }
        val exactFunctions = assignmentDomain.values.forall { rootValue =>
          countDomain.evaluate(rootValue).nonEmpty &&
          countDomain.evaluate(rootValue) == depthDomain.evaluate(rootValue)
        }
        exactRoots && exactSchema && exactProjection && completeDepth &&
          loopCount.hasExactAuthority && vectorDepth.hasExactAuthority &&
          loopCount.generateIndex.isEmpty &&
          vectorDepth.generateIndex.isEmpty && exactFunctions
      }).contains(true)
    }
  }

  /** Resolve exact write aliases created by one typed generate-for over this
    * Vec. The alias assignment, loop/count identity and witnessed static Vec
    * access must all agree before its captured branch domain contributes to a
    * leaf's coverage. Read-only selections and any ambiguous alias fail closed
    * by contributing no evidence.
    */
  private def exactStructuralVecWriteDomains(
      component: Component,
      plan: VecPlan,
      leaf: Leaf
  ): Option[Vector[ParameterizedStructure.CapturedAssignmentDomain]] = {
    val values = ArrayBuffer.empty[ParameterizedStructure.CapturedAssignmentDomain]
    val seen = new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    var invalid = false

    def visitBlock(
        block: ParameterizedStructuralBlock,
        owner: Option[ParameterizedStructure.StructuralFor]
    ): Unit = {
      block.vecIndices.filter(_.vector eq plan.vector).foreach { selection =>
        val resultLeaves = selection.result.flatten.toVector
        val assignments =
          if (leaf.leafIndex >= 0 && leaf.leafIndex < resultLeaves.size)
            block.assignments.filter(_.finalTarget eq resultLeaves(leaf.leafIndex))
          else Vector.empty
        if (assignments.nonEmpty) {
          val witness = selection.index.default
          val exactSelection = for {
            loop <- owner
            finiteIndexToken <- selection.finiteIndexToken
            if loop.finiteIndexToken.exists(_ eq finiteIndexToken)
            access <- selection.staticAccess
            if witness.isValidInt
            witnessIndex = witness.toInt
            if witnessIndex >= 0 && witnessIndex < plan.shape.carrierCapacity
            // Identity above establishes the range/selection relation.  The
            // finalized name below is only a replay check for that exact pair.
            if selection.index.generateIndex.contains(loop.indexName)
            if ParameterizedVec.operationsOf(plan.vector).exists(_ eq access)
            if access.index == witnessIndex
            if access.selected eq selection.selected
            if selection.selected.flatten.toVector.lift(leaf.leafIndex)
              .exists(_ eq plan.leaf(witnessIndex, leaf.leafIndex).value)
            if resultLeaves.size == plan.shape.elementLeaves.size
            if assignments.size == 1
            if assignments.head.target eq resultLeaves(leaf.leafIndex)
            domain <- ParameterizedStructure.capturedAssignmentDomainOf(
              component,
              assignments.head
            )
            if exactProjectedLoopCountMatchesVecDepth(
              loop.count,
              plan.shape.depth,
              domain
            )
          } yield assignments.head -> domain
          exactSelection match {
            case Some((assignment, domain))
                if seen.put(assignment, java.lang.Boolean.TRUE) == null =>
              values += domain
            case _ => invalid = true
          }
        }
      }
      block.regions.foreach {
        case loop: ParameterizedStructure.StructuralFor =>
          visitBlock(loop.body, Some(loop))
        case region =>
          region.blocks.foreach(child => visitBlock(child, owner))
      }
    }

    ParameterizedStructure.regionsOf(component).foreach {
      case loop: ParameterizedStructure.StructuralFor =>
        visitBlock(loop.body, Some(loop))
      case region =>
        region.blocks.foreach(block => visitBlock(block, None))
    }
    if (invalid) None else Some(values.toVector)
  }

  /** Defer one child-output Vec bridge only when its exact packed result has
    * a live parent assignment.  Structural capture precedes aggregate Vec
    * publication, so the native finite leaf bridge can temporarily appear to
    * read an owner-local leaf from module scope.  Publication will replace
    * that exact whole-Vec assignment with the packed aggregate bridge, while
    * hierarchy lowering independently revalidates the canonical/actual
    * roots, functions, shapes, and port identities.  An unused packed read,
    * a scalar port, or coincident emitted text cannot authorize this deferral.
    */
  def exactPackedReadAggregateBridgeLines(
      component: Component,
      lines: Vector[String],
      pc: PhaseContext
  ): Set[Int] = {
    if (component == null || component.parent == null || lines.isEmpty)
      return Set.empty

    val plans = analyze(component, publicationVectors(component), pc)
    val parent = component.parent
    val parentAssignments = ArrayBuffer.empty[DataAssignmentStatement]
    parent.dslBody.walkStatements {
      case assignment: DataAssignmentStatement if assignment.finalTarget.component eq parent =>
        parentAssignments += assignment
      case _ =>
    }

    def exactReferences(expression: Expression, target: Expression): Boolean = {
      var found = false
      def visit(value: Expression): Unit =
        if (!found && value != null) {
          if (value eq target) found = true
          else value.foreachExpression(visit)
        }
      visit(expression)
      found
    }

    plans.flatMap { target =>
      val operations = ParameterizedVec.operationsOf(target.vector)
      val pureOutputSurface =
        target.leaves.nonEmpty && target.leaves.forall { leaf =>
          val value = leaf.value
          value.isIo && value.isOutput && !value.isInput &&
          (value.component eq component)
        }
      val liveReads = operations.collect {
        case read: ParameterizedVecPackedRead
            if pureOutputSurface &&
              ParameterizedVec.exactPackedShapeMatches(
                target.vector,
                read.result
              ) &&
              ParameterizedVec.packedWidthExpressionOf(read.result).nonEmpty &&
              parentAssignments.exists(assignment =>
                exactReferences(assignment.source, read.result) ||
                  exactReferences(assignment.source, read.carrier)
              ) =>
          read
      }
      if (liveReads.isEmpty) Vector.empty
      else
        operations.flatMap {
          case whole: ParameterizedVecWholeAssignment =>
            plans.find(_.vector eq whole.source) match {
              case Some(source) =>
                requireCompatible(target, source, whole.sourceLocation)
                whole.assignments.map { assignment =>
                  val targetName = requiredBaseName(
                    assignment.finalTarget,
                    "pulled packed Vec aggregate bridge",
                    whole.sourceLocation
                  )
                  val parsed = findAssignment(
                    lines,
                    targetName,
                    None,
                    "pulled packed Vec aggregate bridge",
                    whole.sourceLocation
                  )
                  if (!parsed.continuous) {
                    fail(
                      "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-READ-EVIDENCE-MISMATCH",
                      s"pulled packed Vec bridge '$targetName' is not one native continuous assignment",
                      whole.sourceLocation
                    )
                  }
                  parsed.lineIndex
                }
              case None => Vector.empty
            }
          case _ => Vector.empty
        }
    }.toSet
  }

  /** Locate only the native procedural wrappers emitted for an exact typed
    * Vec dynamic read whose retained result assignments belong to one
    * structural block.
    *
    * Structural relocation runs before Vec publication. ComponentEmitter
    * materializes a retained [[Multiplexer]] as a synthetic module-scope case
    * process even when the exact assignment and select were created inside a
    * captured owner. The synthetic process has no native DSL statement
    * identity of its own, so the structural pass may claim it only after this
    * Vec-owned proof revalidates the retained assignment, Multiplexer, select,
    * carrier inputs, emitted result bridge and unique canonical case mapping.
    * Coincident names, widths or case text cannot select an operation.
    */
  private[internals] def exactCapturedDynamicReadProcessRanges(
      component: Component,
      capturedAssignments: Vector[DataAssignmentStatement],
      lines: Vector[String],
      pc: PhaseContext
  ): Vector[(Int, Int)] = {
    if (
      component == null || capturedAssignments == null ||
      capturedAssignments.isEmpty || lines.isEmpty
    ) return Vector.empty

    requireDistinctAssignmentIdentities(
      capturedAssignments,
      "captured structural block",
      None
    )
    val graphLive = new IdentityHashMap[
      DataAssignmentStatement,
      java.lang.Boolean
    ]()
    component.dslBody.walkStatements {
      case assignment: DataAssignmentStatement =>
        graphLive.put(assignment, java.lang.Boolean.TRUE)
      case _ =>
    }

    val ranges = analyze(component, publicationVectors(component), pc).flatMap { plan =>
      ParameterizedVec.operationsOf(plan.vector).flatMap {
        case access: ParameterizedVecDynamicAccess
            if plan.shape.carrierCapacity > 1 =>
          val capturedCounts = access.assignments.map { assignment =>
            capturedAssignments.count(_ eq assignment)
          }
          if (capturedCounts.forall(_ == 0)) Vector.empty
          else {
            requireDistinctAssignmentIdentities(
              access.assignments,
              "captured dynamic Vec read",
              access.sourceLocation.orElse(plan.sourceLocation)
            )
            if (!capturedCounts.forall(_ == 1)) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-STRUCTURAL-OWNERSHIP-MISMATCH",
                s"dynamic read of Vec '${plan.name}' does not retain every exact result assignment exactly once in one structural block",
                access.sourceLocation.orElse(plan.sourceLocation)
              )
            }
            val exactOwner = exactCapturedDynamicReadOwner(
              component,
              access,
              access.sourceLocation.orElse(plan.sourceLocation)
            ).getOrElse {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-STRUCTURAL-OWNERSHIP-MISMATCH",
                s"dynamic read of Vec '${plan.name}' has no exact structural owner",
                access.sourceLocation.orElse(plan.sourceLocation)
              )
            }
            val graphLiveCount = access.assignments.count(graphLive.containsKey)
            val effectiveLive =
              if (graphLiveCount == access.assignments.size) graphLive
              else if (
                graphLiveCount == 0 &&
                !hasLiveDynamicReadResultTarget(access, graphLive)
              ) copyAssignmentEvidence(graphLive, exactOwner.assignments)
              else {
                requireLiveAssignmentEvidence(
                  access.assignments,
                  graphLive,
                  "captured dynamic Vec read",
                  access.sourceLocation.orElse(plan.sourceLocation)
                )
                graphLive
              }
            requireLiveAssignmentEvidence(
              access.assignments,
              effectiveLive,
              "captured dynamic Vec read",
              access.sourceLocation.orElse(plan.sourceLocation)
            )
            exactDynamicReadSupport(lines, plan, access, effectiveLive)
              .assignmentsByBlock
              .map { case (block, _) => block.start -> block.end }
          }
        case _ => Vector.empty
      }
    }

    val distinct = ranges.distinct.sortBy { case (start, end) => start -> end }
    if (distinct.size != ranges.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-STRUCTURAL-PROCESS-AMBIGUOUS",
        "one native dynamic-read support process is claimed by multiple exact retained operations"
      )
    }
    distinct
  }

  /** Select retained Vecs for publication only through exact live IR
    * ownership. A fully pruned internal Vec may be omitted, but a port,
    * hierarchy-bound Vec, partially retained carrier, or operation with live
    * assignment evidence must never be hidden merely because its aggregate
    * emitted name disappeared.
    */
  private def publicationVectors(component: Component): Vector[Vec[_]] = {
    if (component == null) return Vector.empty
    val declarations = new IdentityHashMap[BaseType, java.lang.Boolean]()
    component.dslBody.walkDeclarations {
      case value: BaseType if !value.isSuffix =>
        declarations.put(value, java.lang.Boolean.TRUE)
      case _ =>
    }
    component.getOrdredNodeIo.foreach { value =>
      if (!value.isSuffix) declarations.put(value, java.lang.Boolean.TRUE)
    }
    val liveAssignments =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    component.dslBody.walkStatements {
      case value: DataAssignmentStatement =>
        liveAssignments.put(value, java.lang.Boolean.TRUE)
      case _ =>
    }

    def assignmentsOf(
        operation: ParameterizedVecOperation
    ): Vector[DataAssignmentStatement] = operation match {
      case value: ParameterizedVecDynamicAccess   => value.assignments
      case value: ParameterizedVecDynamicWrite    => value.assignments
      case value: ParameterizedVecWholeAssignment => value.assignments
      case value: ParameterizedVecPackedRead =>
        value.resultAssignments ++ value.carrierAssignments
      case value: ParameterizedVecPackedAssignment =>
        value.assignments ++ value.carrierAssignments
      case value: ParameterizedVecAutoConnect => value.assignments
      case _                                  => Vector.empty
    }

    ParameterizedVec.retainedVectorsOf(component).filter { vector =>
      val shape = ParameterizedVec.shapeOf(vector).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-SHAPE-MISSING",
          "one retained Vec lost its identity shape"
        )
      }
      val carriers = vectorLeaves(vector)
      val liveCarriers = carriers.count(declarations.containsKey)
      if (liveCarriers == carriers.size) true
      else if (liveCarriers != 0) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DECLARATION-OWNERSHIP-MISMATCH",
          s"typed Vec retains $liveCarriers of ${carriers.size} exact carrier declarations",
          shape.sourceLocation
        )
      } else {
        val operations = ParameterizedVec.operationsOf(vector)
        val hasLiveOperation =
          operations.exists(operation => assignmentsOf(operation).exists(liveAssignments.containsKey))
        val requiresPublication =
          carriers.exists(_.isIo) || hasLiveOperation ||
            ParameterizedVec.formalBindingsOf(vector).nonEmpty ||
            structuralVecSelectionsOf(component).exists(_.vector eq vector)
        if (requiresPublication) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-PRUNED-REQUIRED",
            "typed Vec lost every exact carrier declaration while a live port, operation or hierarchy binding still requires publication",
            shape.sourceLocation
          )
        }
        false
      }
    }
  }

  /** Stable logical schema used in native module canonicalization. */
  def logicalSchema(component: Component): Vector[String] =
    publicationVectors(component).map { vector =>
      val shape = ParameterizedVec.shapeOf(vector).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-SHAPE-MISSING",
          "one retained Vec lost its identity shape"
        )
      }
      val name = requiredVecName(vector, shape.sourceLocation)
      val leaves = shape.elementLeaves.map { leaf =>
        val width = ExternalFormalParameterRegistry
          .normalizedDefinitionSchema(leaf.width)
        s"${leaf.path}:${leafTypeSchema(leaf)}:${expressionSchema(width)}"
      }
      val depth = ExternalFormalParameterRegistry
        .normalizedDefinitionSchema(shape.depth)
      s"$name:${expressionSchema(depth)}:${shape.witnessDepth}:${shape.carrierCapacity}:${leaves.mkString("|")}"
    }.sorted

  def rewrite(
      component: Component,
      verilog: String,
      pc: PhaseContext
  ): String = {
    val vectors = publicationVectors(component)
    if (vectors.isEmpty) return verilog

    val plans = analyze(component, vectors, pc)
    val byIdentity = new IdentityHashMap[Vec[_], VecPlan]()
    plans.foreach(plan => byIdentity.put(plan.vector, plan))
    val liveAssignments =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    component.dslBody.walkStatements {
      case assignment: DataAssignmentStatement =>
        liveAssignments.put(assignment, java.lang.Boolean.TRUE)
      case _ =>
    }

    var lines = verilog
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .split("\n", -1)
      .toVector

    val claimedAssignments =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    lines = rewriteChildConnections(
      component,
      lines,
      plans,
      claimedAssignments,
      liveAssignments
    )

    plans.foreach { plan =>
      val operations = ParameterizedVec.operationsOf(plan.vector)
      val consumedDynamicWrites =
        new IdentityHashMap[ParameterizedVecDynamicWrite, java.lang.Boolean]()

      operations.foreach {
        case value: ParameterizedVecWholeAssignment =>
          Option(byIdentity.get(value.source)) match {
            case Some(source) =>
              requireCompatible(plan, source, value.sourceLocation)
              validateWholeAssignmentLineage(
                plan,
                source,
                value.assignments,
                liveAssignments,
                "whole Vec assignment",
                value.sourceLocation
              )
              val rewritten = rewriteWholeAssignment(
                lines,
                plan,
                source,
                value.assignments,
                value.sourceLocation,
                claimedAssignments,
                liveAssignments,
                operations
              )
              lines = rewritten.lines
              rewritten.consumedDynamicWrites.foreach(value => consumedDynamicWrites.put(value, java.lang.Boolean.TRUE))
            case None
                if isClaimedChildOutputBoundary(
                  component,
                  plan.vector,
                  value.source,
                  value.assignments,
                  claimedAssignments
                ) =>
              requireCompatibleBoundary(plan, value.source, value.sourceLocation)
            case None
                if isDirectHierarchyBoundary(
                  component,
                  plan.vector,
                  value.source
                ) =>
              requireCompatibleBoundary(plan, value.source, value.sourceLocation)
            case None =>
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-SHAPE-MISSING",
                s"Vec '${plan.name}' is assigned from a symbolic Vec outside its emitted component",
                value.sourceLocation
              )
          }

        case value: ParameterizedVecPackedAssignment =>
          val supportAssignments = validatePackedAssignmentLineage(
            plan,
            value,
            liveAssignments
          )
          val sourceName = requiredBaseName(
            value.source,
            "packed Vec assignment source",
            value.sourceLocation
          )
          validatePackedCarrier(value.carrier, plan, value.sourceLocation)
          lines = rewritePackedDeclaration(lines, sourceName, plan.range, value.sourceLocation)
          if (value.carrier ne value.source) {
            claimAssignmentEvidence(
              value.carrierAssignments,
              liveAssignments,
              claimedAssignments,
              "claiming packed Vec assignment carrier bridge",
              value.sourceLocation
            )
            lines = rewritePackedCarrierBridge(
              lines,
              value.source,
              value.carrier,
              value.carrierAssignments,
              plan,
              "packed Vec assignment carrier bridge",
              value.sourceLocation
            )
          }
          lines = rewriteAssignmentGroup(
            lines,
            value.assignments ++ supportAssignments,
            plan.name,
            sourceName,
            "packed Vec assignment",
            value.sourceLocation,
            claimedAssignments,
            liveAssignments
          )
          lines = removeSupportDeclarations(
            lines,
            supportAssignments,
            "packed Vec assignment support",
            "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-ASSIGNMENT-SUPPORT-RESIDUAL",
            value.sourceLocation
          )

        case value: ParameterizedVecAutoConnect =>
          Option(byIdentity.get(value.peer)) match {
            case Some(peer) =>
              requireCompatible(plan, peer, value.sourceLocation)
              val direction = validateAutoConnectLineage(
                plan,
                peer,
                value.assignments,
                liveAssignments,
                value.sourceLocation
              )
              // Auto-connect bypasses rewriteWholeAssignment, so establish
              // the same final emitted-line proof here: every exact direct
              // peer-leaf identity must remain a bare RHS, never a slice or
              // expression that merely contains the peer's emitted name.
              parseOperationAssignments(
                lines,
                value.assignments,
                "Vec auto-connect",
                value.sourceLocation
              )
              lines = rewriteAssignmentGroup(
                lines,
                value.assignments,
                direction._1.name,
                direction._2.name,
                "Vec auto-connect",
                value.sourceLocation,
                claimedAssignments,
                liveAssignments
              )
            case None
                if isClaimedChildOutputBoundary(
                  component,
                  plan.vector,
                  value.peer,
                  value.assignments,
                  claimedAssignments
                ) =>
              requireCompatibleBoundary(plan, value.peer, value.sourceLocation)
            case None
                if isDirectHierarchyBoundary(
                  component,
                  plan.vector,
                  value.peer
                ) =>
              requireCompatibleBoundary(plan, value.peer, value.sourceLocation)
            case None =>
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-VEC-AUTOCONNECT-SHAPE-MISSING",
                s"Vec '${plan.name}' auto-connects to a symbolic Vec outside its emitted component",
                value.sourceLocation
              )
          }

        case _ =>
      }

      operations.foreach {
        case value: ParameterizedVecPackedRead =>
          validatePackedCarrier(value.carrier, plan, value.sourceLocation)
          validatePackedLogicalWitness(value.result, plan, value.sourceLocation)
          val rewritten = rewritePackedRead(
            lines,
            plan,
            value,
            liveAssignments
          )
          lines = rewritten.lines
          val packedEvidence =
            (value.resultAssignments ++ value.carrierAssignments ++
              rewritten.supportAssignments)
              .filter(liveAssignments.containsKey)
              .foldLeft(
                Vector.empty[DataAssignmentStatement]
              ) { (known, assignment) =>
                if (known.exists(_ eq assignment)) known else known :+ assignment
              }
          // Re-audit the retained expressions before claiming the surviving
          // statements. A nested packed Vec can consume the exact carrier
          // assignment during native alias folding; in that case the retained
          // AST layout plus the unique emitted carrier line remain the proof,
          // while there is no detached live statement to claim.
          if (packedEvidence.nonEmpty)
            claimAssignmentEvidence(
              packedEvidence,
              liveAssignments,
              claimedAssignments,
              "claiming packed Vec read result and carrier",
              value.sourceLocation
            )
          val resultName = requiredBaseName(
            value.result,
            "packed Vec read result",
            value.sourceLocation
          )
          lines = rewritePackedDeclaration(lines, resultName, plan.range, value.sourceLocation)
          if (value.carrier ne value.result) {
            val carrierName = requiredBaseName(
              value.carrier,
              "packed Vec read carrier",
              value.sourceLocation
            )
            lines = rewritePackedDeclaration(
              lines,
              carrierName,
              plan.range,
              value.sourceLocation
            )
          }

        case value: ParameterizedVecDynamicAccess =>
          lines = rewriteDynamicRead(
            component,
            lines,
            plan,
            value,
            claimedAssignments,
            liveAssignments
          )

        case _ =>
      }

      val pendingDynamicWrites = operations.collect {
        case value: ParameterizedVecDynamicWrite if !consumedDynamicWrites.containsKey(value) => value
      }
      if (pendingDynamicWrites.nonEmpty) {
        lines = rewriteDynamicWrites(
          lines,
          plan,
          pendingDynamicWrites,
          claimedAssignments,
          liveAssignments
        )
      }
    }

    plans.foreach { plan =>
      lines = collapseDeclaration(lines, plan)
    }

    val structuralNames = structuralRegionNamesOf(component)
    plans.foreach { plan =>
      plan.leaves.find(leaf => structuralNames.contains(leaf.name)).foreach { leaf =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-NAME-COLLISION",
          s"Vec '${plan.name}' carrier '${leaf.name}' collides with an exact retained generate label or index name",
          plan.sourceLocation
        )
      }

      // A native constant Vec access is represented by the exact carrier
      // element returned from Vec.apply(Int).  Do not infer that relation from
      // a carrier spelling or from a matching native assignment: the retained
      // static-index operation and the selected object identity are the sole
      // authority.  After declaration collapse, the existing lexical rewrite
      // is used only to replace occurrences of that already-proven identity.
      val authorizedStaticLeaves =
        new IdentityHashMap[BaseType, java.lang.Boolean]()
      val residualReferences = plan.leaves.map { leaf =>
        leaf.name -> countReferenceIdentifier(lines, leaf.name)
      }.toMap
      ParameterizedVec.operationsOf(plan.vector).foreach {
        case access: ParameterizedVecStaticIndex =>
          val expectedLeaves =
            if (
              access.index >= 0 &&
              access.index < plan.shape.carrierCapacity
            ) plan.leaves.filter(_.elementIndex == access.index)
            else Vector.empty
          if (expectedLeaves.exists(leaf => residualReferences(leaf.name) != 0)) {
            val minimumDepth = ElabInt
              .projectExpression(
                plan.shape.depth,
                "parameterized Vec static-index publication"
              )
              .minimum
            if (BigInt(access.index) >= minimumDepth) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-VEC-STATIC-INDEX-EVIDENCE-MISMATCH",
                s"constant Vec index ${access.index} of '${plan.name}' is outside its retained carrier or complete depth domain",
                access.sourceLocation.orElse(plan.sourceLocation)
              )
            }
            val expectedElement =
              plan.vector.vec(access.index).asInstanceOf[Data]
            if (access.selected ne expectedElement) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-VEC-STATIC-INDEX-EVIDENCE-MISMATCH",
                s"constant Vec index ${access.index} of '${plan.name}' does not retain its exact carrier element identity",
                access.sourceLocation.orElse(plan.sourceLocation)
              )
            }
            val selectedLeaves = access.selected.flatten.toVector
            if (
              selectedLeaves.size != expectedLeaves.size ||
              !selectedLeaves.zip(expectedLeaves).forall { case (selected, leaf) =>
                selected eq leaf.value
              }
            ) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-VEC-STATIC-INDEX-EVIDENCE-MISMATCH",
                s"constant Vec index ${access.index} of '${plan.name}' does not retain its exact flattened carrier layout",
                access.sourceLocation.orElse(plan.sourceLocation)
              )
            }
            expectedLeaves.foreach(leaf => authorizedStaticLeaves.put(leaf.value, java.lang.Boolean.TRUE))
          }
        case _ =>
      }

      plan.leaves.foreach { leaf =>
        val found = residualReferences(leaf.name)
        // StructuralVecIndex uses are lowered by the structural pass before
        // this one. Any remaining carrier reference must therefore be backed
        // by one exact live constant Vec access retained above; a dynamic or
        // otherwise unrecorded native carrier use must fail closed.
        if (found != 0 && !authorizedStaticLeaves.containsKey(leaf.value)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-RESIDUAL-CARRIER-REFERENCE",
            s"Vec '${plan.name}' retains $found references to carrier '${leaf.name}' without one exact live constant Vec access identity",
            plan.sourceLocation
          )
        }
        if (found != 0) {
          if (isSignedLeaf(leaf.shape)) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-VEC-SIGNED-SLICE-UNSUPPORTED",
              s"constant indexed SInt leaf '${leaf.name}' of Vec '${plan.name}' requires context-sensitive signed lowering",
              plan.sourceLocation
            )
          }
          lines = replaceReferenceIdentifier(
            lines,
            leaf.name,
            plan.constantSlice(leaf.elementIndex, leaf.leafIndex)
          )
        }
      }
    }

    val result = lines.mkString("\n")
    plans.foreach { plan =>
      plan.leafNames.foreach { name =>
        if (containsReferenceIdentifier(result, name)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-RESIDUAL-CARRIER-REFERENCE",
            s"Vec '${plan.name}' retains native carrier reference '$name' after packed publication",
            plan.sourceLocation
          )
        }
      }
    }
    result
  }

  private def structuralVecSelectionsOf(
      component: Component
  ): Vector[ParameterizedStructure.StructuralVecIndex] = {
    val values = Vector.newBuilder[ParameterizedStructure.StructuralVecIndex]
    def visitBlock(block: ParameterizedStructuralBlock): Unit = {
      values ++= block.vecIndices
      block.regions.foreach(visitRegion)
    }
    def visitRegion(region: ParameterizedStructure.StructuralRegion): Unit =
      region.blocks.foreach(visitBlock)
    ParameterizedStructure.regionsOf(component).foreach(visitRegion)
    values.result()
  }

  private def structuralBlocksOf(
      component: Component
  ): Vector[ParameterizedStructuralBlock] = {
    val values = Vector.newBuilder[ParameterizedStructuralBlock]
    def visitBlock(block: ParameterizedStructuralBlock): Unit = {
      values += block
      block.regions.foreach(visitRegion)
    }
    def visitRegion(region: ParameterizedStructure.StructuralRegion): Unit =
      region.blocks.foreach(visitBlock)
    ParameterizedStructure.regionsOf(component).foreach(visitRegion)
    values.result()
  }

  private def structuralRegionNamesOf(component: Component): Set[String] = {
    val names = mutable.HashSet.empty[String]
    def visitBlock(block: ParameterizedStructuralBlock): Unit =
      block.regions.foreach(visitRegion)
    def visitRegion(region: ParameterizedStructure.StructuralRegion): Unit = {
      region match {
        case value: ParameterizedStructure.StructuralFor =>
          names += value.label
          names += value.indexName
        case value: ParameterizedStructure.StructuralIf =>
          names += value.whenTrueLabel
          names += value.whenFalseLabel
        case value: ParameterizedStructure.StructuralCase =>
          value.choices.foreach(choice => names += choice.label)
          names += value.defaultLabel
      }
      region.blocks.foreach(visitBlock)
    }
    ParameterizedStructure.regionsOf(component).foreach(visitRegion)
    names.toSet
  }

  private def analyze(
      component: Component,
      vectors: Vector[Vec[_]],
      pc: PhaseContext
  ): Vector[VecPlan] = {
    val declarations = new IdentityHashMap[BaseType, java.lang.Boolean]()
    component.dslBody.walkDeclarations {
      case baseType: BaseType if !baseType.isSuffix =>
        declarations.put(baseType, java.lang.Boolean.TRUE)
      case _ =>
    }
    component.getOrdredNodeIo.foreach { baseType =>
      if (!baseType.isSuffix)
        declarations.put(baseType, java.lang.Boolean.TRUE)
    }

    val plans = vectors.flatMap { vector =>
      val shape = ParameterizedVec.shapeOf(vector).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-SHAPE-MISSING",
          "one retained Vec lost its identity shape"
        )
      }
      validateShape(shape, pc)
      if (vector.vec.size != shape.carrierCapacity) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-CARRIER-CAPACITY-MISMATCH",
          s"retained Vec carrier has ${vector.vec.size} elements, expected ${shape.carrierCapacity}",
          shape.sourceLocation
        )
      }
      val allLeaves = vector.vec.zipWithIndex.flatMap { case (element, elementIndex) =>
        val flattened = element.asInstanceOf[Data].flatten.toVector
        if (flattened.size != shape.elementLeaves.size) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-ELEMENT-LAYOUT-MISMATCH",
            s"Vec element $elementIndex has ${flattened.size} leaves, expected ${shape.elementLeaves.size}",
            shape.sourceLocation
          )
        }
        flattened.zipWithIndex.map { case (leaf, leafIndex) =>
          Leaf(
            leaf,
            requiredBaseName(leaf, "Vec carrier leaf", shape.sourceLocation),
            elementIndex,
            leafIndex,
            shape.elementLeaves(leafIndex)
          )
        }
      }
      val retainedLeaves = allLeaves.filter(leaf => declarations.containsKey(leaf.value))
      if (retainedLeaves.isEmpty) None
      else {
        if (retainedLeaves.size != allLeaves.size) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-DECLARATION-OWNERSHIP-MISMATCH",
            "one typed Vec mixes emitted component leaves with non-declaration carrier leaves",
            shape.sourceLocation
          )
        }
        val name = requiredVecName(vector, shape.sourceLocation)
        if (Option(vector.getName()).forall(_.isEmpty)) {
          val occupied = mutable.HashSet.empty[String]
          component.dslBody.walkDeclarations {
            case value: BaseType =>
              Option(value.getName()).filter(_.nonEmpty).foreach(occupied += _)
            case _ =>
          }
          component.getOrdredNodeIo.foreach(value => Option(value.getName()).filter(_.nonEmpty).foreach(occupied += _))
          component.children.foreach(value => Option(value.getName()).filter(_.nonEmpty).foreach(occupied += _))
          occupied ++= shape.parameters.map(_.name)
          if (occupied.contains(name)) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-VEC-SYNTHETIC-NAME-COLLISION",
              s"synthetic typed Vec aggregate name '$name' collides with one exact native declaration, child instance or retained parameter",
              shape.sourceLocation
            )
          }
        }
        if (pc.verilogKeywords.contains(name)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-NAME-RESERVED",
            s"Vec aggregate name '$name' is reserved by IEEE 1364",
            shape.sourceLocation
          )
        }
        if (shape.parameters.exists(_.name == name)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-PARAMETER-NAME-COLLISION",
            s"Vec aggregate name '$name' collides with one of its retained parameters",
            shape.sourceLocation
          )
        }
        val elementWidth = renderSum(shape.elementLeaves.map(_.width))
        val totalWidth = multiplyTerms(elementWidth, render(shape.depth))
        val totalRange = s"[${parenthesize(totalWidth)}-1:0]"
        Some(
          VecPlan(
            vector,
            shape,
            name,
            allLeaves,
            elementWidth,
            totalRange,
            shape.sourceLocation
          )
        )
      }
    }

    plans
      .flatMap(_.leaves)
      .groupBy(_.name)
      .collectFirst {
        case (name, values) if values.map(_.value).distinct.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-LEAF-NAME-AMBIGUOUS",
          s"multiple typed Vec leaves resolve to emitted name '$name'"
        )
      }
    plans
      .groupBy(_.name)
      .collectFirst {
        case (name, values) if values.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-NAME-AMBIGUOUS",
          s"multiple typed Vec aggregates resolve to emitted name '$name'"
        )
      }
    val ownerByLeaf = new IdentityHashMap[BaseType, VecPlan]()
    plans.foreach { plan =>
      plan.leaves.foreach { leaf =>
        val existing = ownerByLeaf.put(leaf.value, plan)
        if (existing != null && (existing.vector ne plan.vector)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-NESTING-UNSUPPORTED",
            s"overlapping typed Vec aggregates '${existing.name}' and '${plan.name}' share one emitted leaf",
            plan.sourceLocation.orElse(existing.sourceLocation)
          )
        }
      }
    }
    plans
  }

  private def validateShape(
      shape: ParameterizedVecShape,
      pc: PhaseContext
  ): Unit = {
    ElabInt.validateExpression(shape.depth, "typed Vec publication depth")
    if (
      (shape.depth.parameters.isEmpty &&
        shape.elementLeaves.forall(_.width.parameters.isEmpty)) ||
      shape.depth.minimum < 1 ||
      shape.depth.maximum < shape.depth.minimum ||
      shape.depth.default != BigInt(shape.witnessDepth) ||
      shape.depth.maximum != BigInt(shape.carrierCapacity)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DEPTH-INVALID",
        s"typed Vec depth '${shape.depth.verilog}' must be positive and match witness ${shape.witnessDepth} plus carrier capacity ${shape.carrierCapacity}",
        shape.sourceLocation.orElse(shape.depth.sourceLocation)
      )
    }
    if (shape.elementLeaves.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ELEMENT-EMPTY",
        "typed Vec elements must contain at least one packed leaf",
        shape.sourceLocation
      )
    }
    shape.elementLeaves.foreach { leaf =>
      ElabInt.validateExpression(leaf.width, "typed Vec element width")
      if (leaf.width.minimum < 1 || leaf.width.default < 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ELEMENT-WIDTH-INVALID",
          s"Vec leaf '${leaf.path}' width '${leaf.width.verilog}' is not positive over its complete domain",
          shape.sourceLocation.orElse(leaf.width.sourceLocation)
        )
      }
    }
    val maximum = shape.depth.maximum * shape.elementWidthMaximum
    if (maximum > BigInt(pc.config.bitVectorWidthMax)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-TOTAL-WIDTH-TOO-LARGE",
        s"typed Vec total packed width reaches $maximum, above SpinalConfig.bitVectorWidthMax=${pc.config.bitVectorWidthMax}",
        shape.sourceLocation
      )
    }
  }

  private def rewriteChildConnections(
      component: Component,
      original: Vector[String],
      parentPlans: Vector[VecPlan],
      claimed: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Vector[String] = {
    var lines = original
    component.children.foreach { child =>
      val childVectors = ArrayBuffer.empty[(Vec[_], ParameterizedVecShape)]
      ParameterizedVec.vectorsOf(child).foreach { vector =>
        val shape = ParameterizedVec.shapeOf(vector).get
        val leaves = vector.vec.flatMap(element => element.asInstanceOf[Data].flatten).toVector
        if (leaves.nonEmpty && leaves.forall(_.isIo))
          childVectors += ((vector, shape))
      }
      childVectors.foreach { case (vector, shape) =>
        val formalName = requiredVecName(vector, shape.sourceLocation)
        val childLeaves = vector.vec.flatMap(element => element.asInstanceOf[Data].flatten).toVector
        val childLeafWidths = Vector
          .fill(shape.carrierCapacity)(shape.elementLeaves.map(_.width))
          .flatten
        if (childLeafWidths.size != childLeaves.size) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-SHAPE-MISMATCH",
            s"child Vec port '$formalName' exposes ${childLeaves.size} native leaves for retained carrier layout ${childLeafWidths.size}",
            shape.sourceLocation
          )
        }
        val formalLeaves = childLeaves.map { leaf =>
          requiredBaseName(leaf, "child Vec port leaf", shape.sourceLocation)
        }
        val block = instanceBlock(lines, child, shape.sourceLocation)
        val connections = formalLeaves.map { name =>
          parsePortConnection(lines, block, name, shape.sourceLocation)
        }
        val connectionNames = connections.zip(childLeafWidths).map { case (connection, leafWidth) =>
          directConnectionName(connection._2, leafWidth).getOrElse {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-CONNECTION-EXPRESSION",
              s"child Vec port '$formalName' of instance '${child.getName()}' has non-direct native connection '${connection._2}'",
              shape.sourceLocation
            )
          }
        }

        // A child input is a packed Vec boundary only when the authoritative
        // Vec algorithm retained the complete parent-to-child relation by
        // assignment identity. Native connection names merely validate that
        // proven relation; a leaf-by-leaf user connection with the same names
        // must never be promoted into an aggregate Vec connection.
        val retainedInputs = parentPlans.flatMap { candidate =>
          val parentLeaves = candidate.leaves.map(_.value)
          val childOwned = ParameterizedVec.operationsOf(vector).flatMap {
            case value: ParameterizedVecWholeAssignment
                if (value.source eq candidate.vector) &&
                  isExactInputBoundary(
                    value.assignments,
                    childLeaves,
                    parentLeaves
                  ) =>
              requireLiveAssignmentEvidence(
                value.assignments,
                live,
                "child Vec input boundary",
                value.sourceLocation.orElse(shape.sourceLocation)
              )
              Some(candidate -> value.assignments)
            case value: ParameterizedVecAutoConnect
                if (value.peer eq candidate.vector) &&
                  isExactInputBoundary(
                    value.assignments,
                    childLeaves,
                    parentLeaves
                  ) =>
              requireLiveAssignmentEvidence(
                value.assignments,
                live,
                "child Vec input auto-connect boundary",
                value.sourceLocation.orElse(shape.sourceLocation)
              )
              Some(candidate -> value.assignments)
            case _ => None
          }
          val parentOwned = ParameterizedVec.operationsOf(candidate.vector).flatMap {
            case value: ParameterizedVecAutoConnect
                if (value.peer eq vector) &&
                  isExactInputBoundary(
                    value.assignments,
                    childLeaves,
                    parentLeaves
                  ) =>
              requireLiveAssignmentEvidence(
                value.assignments,
                live,
                "parent Vec input auto-connect boundary",
                value.sourceLocation.orElse(shape.sourceLocation)
              )
              Some(candidate -> value.assignments)
            case _ => None
          }
          childOwned ++ parentOwned
        }
        val directlyConnected = retainedInputs.collect {
          case (candidate, assignments)
              if candidate.leaves.size == connectionNames.size &&
                candidate.leaves.zip(connectionNames).forall { case (leaf, connectionName) =>
                  connectionName == leaf.name
                } =>
            candidate -> assignments
        }

        // Native Verilog connects a child output through one temporary wire
        // per flattened leaf.  Recover that output bridge only from the exact
        // Vec assignment identities retained by the authoritative algorithm;
        // neither the temporary names nor their spelling establish ownership.
        val bridged = parentPlans.flatMap { candidate =>
          ParameterizedVec.operationsOf(candidate.vector).flatMap {
            case value: ParameterizedVecWholeAssignment
                if (value.source eq vector) &&
                  isExactBoundaryBridge(
                    value.assignments,
                    candidate.leaves.map(_.value),
                    childLeaves
                  ) =>
              requireLiveAssignmentEvidence(
                value.assignments,
                live,
                "child Vec output boundary",
                value.sourceLocation.orElse(shape.sourceLocation)
              )
              Some(candidate -> value.assignments)
            case value: ParameterizedVecAutoConnect
                if (value.peer eq vector) &&
                  isExactBoundaryBridge(
                    value.assignments,
                    candidate.leaves.map(_.value),
                    childLeaves
                  ) =>
              requireLiveAssignmentEvidence(
                value.assignments,
                live,
                "child Vec output auto-connect boundary",
                value.sourceLocation.orElse(shape.sourceLocation)
              )
              Some(candidate -> value.assignments)
            case _ => None
          }
        }

        val (parent, boundaryAssignments, bridgeAssignments) =
          if (
            directlyConnected.size == 1 &&
            bridged.forall(value => value._1.vector eq directlyConnected.head._1.vector)
          )
            (
              directlyConnected.head._1,
              directlyConnected.head._2,
              Option.empty[Vector[DataAssignmentStatement]]
            )
          else if (directlyConnected.isEmpty && bridged.size == 1)
            (bridged.head._1, bridged.head._2, Some(bridged.head._2))
          else {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-CONNECTION-UNSUPPORTED",
              s"child Vec port '$formalName' of instance '${child.getName()}' maps to ${directlyConnected.size} direct and ${bridged.size} identity-retained parent Vec connections; native connections are ${connections
                  .map(_._2)
                  .mkString(", ")}",
              shape.sourceLocation
            )
          }

        claimAssignmentEvidence(
          boundaryAssignments,
          live,
          claimed,
          s"lowering child Vec boundary '$formalName'",
          shape.sourceLocation
        )

        requireCompatibleBoundary(parent, vector, shape.sourceLocation)
        if (
          shape.carrierCapacity != parent.shape.carrierCapacity ||
          shape.elementLeaves.size != parent.shape.elementLeaves.size
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-SHAPE-MISMATCH",
            s"child Vec port '$formalName' and parent Vec '${parent.name}' have incompatible carrier layouts",
            shape.sourceLocation.orElse(parent.sourceLocation)
          )
        }

        val removed = mutable.HashSet.empty[Int]
        bridgeAssignments.foreach { assignments =>
          val orderedAssignments = parent.leaves.zip(childLeaves).map { case (parentLeaf, childLeaf) =>
            assignments
              .find { assignment =>
                (assignment.finalTarget eq parentLeaf.value) &&
                (assignment.source match {
                  case source: BaseType => source eq childLeaf
                  case _                => false
                })
              }
              .getOrElse {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-BRIDGE-LAYOUT",
                  s"child Vec output bridge into '${parent.name}' lost one exact logical carrier assignment",
                  shape.sourceLocation
                )
              }
          }
          val parsed = orderedAssignments.zip(parent.leaves.zip(connectionNames)).map {
            case (assignment, (parentLeaf, connectionName)) =>
              val targetName = requiredBaseName(
                parentLeaf.value,
                "child Vec output bridge target",
                shape.sourceLocation
              )
              val value = findAssignment(
                lines,
                targetName,
                None,
                "child Vec output bridge",
                shape.sourceLocation
              )
              if (
                !directConnectionName(value.rhs, parentLeaf.shape.width)
                  .contains(connectionName)
              ) {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-BRIDGE-LAYOUT",
                  s"child Vec output bridge into '${parent.name}' does not use its exact native instance connection",
                  shape.sourceLocation
                )
              }
              value
          }
          removed ++= parsed.map(_.lineIndex)
          connectionNames.foreach { name =>
            val declaration = parseDeclaration(lines, name, shape.sourceLocation)
            if (declaration.direction.nonEmpty) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-BRIDGE-PORT",
                s"child Vec output bridge '$name' unexpectedly resolves to a parent module port",
                shape.sourceLocation
              )
            }
            removed += declaration.lineIndex
          }
        }
        val indexes = connections.map(_._1)
        val insertion = indexes.max
        val comma = connections.find(_._1 == insertion).get._3
        val indentation = lines(insertion).takeWhile(_.isWhitespace)
        val replacement =
          s"$indentation.$formalName (${parent.name})${if (comma) "," else ""}"
        lines = lines.zipWithIndex.flatMap { case (line, index) =>
          if (index == insertion) Vector(replacement)
          else if (indexes.contains(index) || removed.contains(index)) Vector.empty
          else Vector(line)
        }
        if (bridgeAssignments.nonEmpty) {
          connectionNames.foreach { name =>
            if (containsReferenceIdentifier(lines.mkString("\n"), name)) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-BRIDGE-RESIDUAL",
                s"child Vec output bridge '$name' remains referenced after packed connection publication",
                shape.sourceLocation
              )
            }
          }
        }
      }
    }
    lines
  }

  private def isExactBoundaryBridge(
      assignments: Vector[DataAssignmentStatement],
      parentLeaves: Vector[BaseType],
      childLeaves: Vector[BaseType]
  ): Boolean =
    assignments.size == parentLeaves.size &&
      parentLeaves.size == childLeaves.size &&
      parentLeaves.zip(childLeaves).forall { case (parentLeaf, childLeaf) =>
        assignments.count { assignment =>
          (assignment.finalTarget eq parentLeaf) && (assignment.source match {
            case source: BaseType => source eq childLeaf
            case _                => false
          })
        } == 1
      }

  private def isExactInputBoundary(
      assignments: Vector[DataAssignmentStatement],
      childLeaves: Vector[BaseType],
      parentLeaves: Vector[BaseType]
  ): Boolean =
    assignments.size == childLeaves.size &&
      childLeaves.size == parentLeaves.size &&
      childLeaves.zip(parentLeaves).forall { case (childLeaf, parentLeaf) =>
        assignments.count { assignment =>
          (assignment.finalTarget eq childLeaf) && (assignment.source match {
            case source: BaseType => source eq parentLeaf
            case _                => false
          })
        } == 1
      }

  private def instanceBlock(
      lines: Vector[String],
      child: Component,
      sourceLocation: Option[String]
  ): (Int, Int) = {
    val definitionName = Option(child.definitionName).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-NAME-MISSING",
        "child with a typed Vec port has no definition name",
        sourceLocation
      )
    }
    val instanceName = Option(child.getName()).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-NAME-MISSING",
        s"child '$definitionName' with a typed Vec port has no instance name",
        sourceLocation
      )
    }
    val terminator =
      ("^\\s*\\)\\s+" + Pattern.quote(instanceName) + "\\s*\\(\\s*$").r
    val plain =
      ("^\\s*" + Pattern.quote(definitionName) + "\\s+" +
        Pattern.quote(instanceName) + "\\s*\\(\\s*$").r
    val starts = lines.zipWithIndex.collect {
      case (line, index) if plain.findFirstIn(line).nonEmpty      => index
      case (line, index) if terminator.findFirstIn(line).nonEmpty => index
    }
    if (starts.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-INSTANCE-NOT-FOUND",
        s"native Verilog contains ${starts.size} connection blocks for instance '$instanceName'",
        sourceLocation
      )
    }
    val start = starts.head
    val end = (start + 1 until lines.size).find(index => lines(index).trim == ");").getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-INSTANCE-NOT-FOUND",
        s"native Verilog does not terminate instance '$instanceName'",
        sourceLocation
      )
    }
    start -> end
  }

  private def parsePortConnection(
      lines: Vector[String],
      block: (Int, Int),
      formalName: String,
      sourceLocation: Option[String]
  ): (Int, String, Boolean) = {
    val pattern =
      ("^\\s*\\." + Pattern.quote(formalName) +
        "\\s*\\(\\s*(.*?)\\s*\\)\\s*(,?)\\s*(?://.*)?$").r
    val matches = (block._1 + 1 until block._2).flatMap { index =>
      pattern.findFirstMatchIn(lines(index)).map(value => (index, value.group(1), value.group(2) == ","))
    }
    if (matches.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-HIERARCHY-PORT-NOT-FOUND",
        s"instance contains ${matches.size} direct connections for Vec leaf '$formalName'",
        sourceLocation
      )
    }
    matches.head
  }

  private def directConnectionName(
      value: String,
      expectedWidth: ElaborationIntegerExpression
  ): Option[String] = {
    val direct = "^([A-Za-z_][A-Za-z0-9_$]*)$".r
    val fullRange =
      "^([A-Za-z_][A-Za-z0-9_$]*)\\s*\\[\\s*(.*?)\\s*:\\s*0\\s*\\]$".r
    value.trim match {
      case direct(name) => Some(name)
      case fullRange(name, high) if isExactFullWidthHigh(high, expectedWidth) =>
        Some(name)
      case _ => None
    }
  }

  private def isExactFullWidthHigh(
      high: String,
      width: ElaborationIntegerExpression
  ): Boolean = {
    val compactHigh = compactExpression(high)
    compactHigh == compactExpression(s"${width.verilog}-1") ||
    (width.parameters.isEmpty && compactHigh == (width.default - 1).toString)
  }

  /** Compare only the exact emitted full-width expression retained on the
    * native leaf.  Whitespace is presentation syntax; every other token,
    * including parentheses and operators, must remain identical.  This keeps
    * hierarchy acceptance limited to a bare signal or `[typedWidth-1:0]` and
    * cannot authorize a witness-width, partial or offset slice.
    */
  private def compactExpression(value: String): String =
    value.filterNot(_.isWhitespace)

  private def rewriteWholeAssignment(
      original: Vector[String],
      plan: VecPlan,
      source: VecPlan,
      assignments: Vector[DataAssignmentStatement],
      sourceLocation: Option[String],
      claimed: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      operations: Vector[ParameterizedVecOperation]
  ): WholeAssignmentRewrite = {
    val parsed = parseOperationAssignments(
      original,
      assignments,
      "whole Vec assignment",
      sourceLocation
    )
    val indexes = parsed.map(_.lineIndex)
    val ordered = indexes.sorted
    val intervening = (ordered.head to ordered.last).filterNot(indexes.contains)
    val isContiguous = !intervening.exists { index =>
      val trimmed = original(index).trim
      trimmed.nonEmpty && !trimmed.startsWith("//")
    }
    val dynamicWrites = operations.collect { case value: ParameterizedVecDynamicWrite =>
      value
    }
    if (isContiguous && dynamicWrites.isEmpty) {
      return WholeAssignmentRewrite(
        rewriteAssignmentGroup(
          original,
          assignments,
          plan.name,
          source.name,
          "whole Vec assignment",
          sourceLocation,
          claimed,
          live
        ),
        Vector.empty
      )
    }

    val kinds = parsed.map(value => value.continuous -> value.operator).distinct
    if (kinds.size != 1 || kinds.head._1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-NONCONTIGUOUS",
        s"whole Vec assignment of '${plan.name}' is noncontiguous without one uniform procedural assignment kind",
        sourceLocation
      )
    }

    if (dynamicWrites.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-NONCONTIGUOUS",
        s"whole Vec assignment of '${plan.name}' is noncontiguous without a recorded dynamic Vec override",
        sourceLocation
      )
    }
    dynamicWrites.foreach { write =>
      requireLiveAssignmentEvidence(
        write.assignments,
        live,
        "dynamic Vec write",
        write.sourceLocation
      )
      validateDynamicWriteGuardLineage(plan, write, live)
    }
    validateSharedDynamicWriteAccess(plan, dynamicWrites)
    claimDynamicWriteSupportEvidence(
      plan,
      dynamicWrites,
      live,
      claimed
    )
    val parsedWrites = dynamicWrites.map { write =>
      val values = parseOperationAssignments(
        original,
        write.assignments,
        "dynamic Vec write",
        write.sourceLocation
      )
      val writeKinds = values.map(value => value.continuous -> value.operator).distinct
      val rightHandSides = values.map(_.rhs).distinct
      if (
        values.isEmpty || writeKinds.size != 1 || writeKinds.head._1 ||
        rightHandSides.size != 1
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-LAYOUT-MISMATCH",
          s"dynamic write of Vec '${plan.name}' does not retain one uniform procedural source expression",
          write.sourceLocation
        )
      }
      write -> values
    }

    val blocks = alwaysBlocks(original)
    def ownerOf(lineIndex: Int, role: String): AlwaysBlock = {
      val owners = blocks.filter(block => lineIndex > block.start && lineIndex < block.end)
      if (owners.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-PROCEDURAL-BLOCK-AMBIGUOUS",
          s"$role of Vec '${plan.name}' is enclosed by ${owners.size} native always blocks",
          sourceLocation
        )
      }
      owners.head
    }

    val wholeByBlock = assignments.zip(parsed).groupBy { case (_, value) =>
      ownerOf(value.lineIndex, "whole assignment leaf")
    }
    if (
      wholeByBlock.size != assignments.size ||
      wholeByBlock.exists(_._2.size != 1)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-PROCEDURAL-LAYOUT-MISMATCH",
        s"whole assignment of Vec '${plan.name}' is not emitted as one native block per retained carrier leaf",
        sourceLocation
      )
    }
    val retainedBlocks = wholeByBlock.keySet
    val writeEntries = parsedWrites.flatMap { case (write, values) =>
      write.assignments.zip(values).map { case (assignment, parsedValue) =>
        val block = ownerOf(parsedValue.lineIndex, "dynamic assignment leaf")
        if (!retainedBlocks.contains(block)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-PROCEDURAL-LAYOUT-MISMATCH",
            s"dynamic override of Vec '${plan.name}' is outside its whole-assignment carrier blocks",
            write.sourceLocation
          )
        }
        (write, assignment, parsedValue, block)
      }
    }
    val writesByBlock = writeEntries.groupBy(_._4)
    if (writesByBlock.exists(_._2.size > 1)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-LAYOUT-MISMATCH",
        s"Vec '${plan.name}' retains multiple dynamic overrides in one carrier block; conditional composition is not proven generic",
        sourceLocation
      )
    }

    val wholeLineIndexes = parsed.map(_.lineIndex).toSet
    val writeLineIndexes = writeEntries.map(_._3.lineIndex).toSet
    val indexedGuardPattern =
      "^if\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s*\\[\\s*([0-9]+)\\s*\\]\\s*\\)\\s*begin\\s*$".r
    val directGuardPattern =
      "^if\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s*\\)\\s*begin\\s*$".r
    retainedBlocks.foreach { block =>
      val meaningful = (block.start to block.end).filter { index =>
        val value = original(index).trim
        value.nonEmpty && !value.startsWith("//")
      }
      val structural =
        meaningful.filterNot(index => wholeLineIndexes.contains(index) || writeLineIndexes.contains(index))
      val expectedStructural = if (writesByBlock.contains(block)) 4 else 2
      if (
        structural.size != expectedStructural ||
        original(block.start).trim != "always @(*) begin" ||
        original(block.end).trim != "end"
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-PROCEDURAL-LAYOUT-MISMATCH",
          s"carrier block of Vec '${plan.name}' contains control flow beyond one authoritative dynamic-index override",
          sourceLocation
        )
      }
      writesByBlock.get(block).foreach { entries =>
        val (write, assignment, _, _) = entries.head
        val leaf = plan.leaves.find(value => value.value eq assignment.finalTarget).getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-LAYOUT-MISMATCH",
            s"dynamic write target of Vec '${plan.name}' is not one retained carrier leaf",
            write.sourceLocation
          )
        }
        if (leaf.leafIndex != write.elementLeafIndex) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-LAYOUT-MISMATCH",
            s"dynamic write target of Vec '${plan.name}' disagrees with retained element-leaf index ${write.elementLeafIndex}",
            write.sourceLocation
          )
        }
        val guards = structural.flatMap(index =>
          original(index).trim match {
            case indexedGuardPattern(name, indexText) =>
              Some(name -> indexText.toInt)
            case directGuardPattern(name) =>
              write.guards
                .find { guard =>
                  requiredBaseName(
                    guard.enable,
                    s"dynamic Vec write guard ${guard.elementIndex}",
                    write.sourceLocation
                  ) == name
                }
                .map(guard => name -> guard.elementIndex)
            case _ => None
          }
        )
        val decoderName = requiredBaseName(
          write.decoder,
          "dynamic Vec write decoder",
          write.sourceLocation
        )
        val exactGuard = write.guards.find(_.assignment eq assignment)
        val emittedGuardMatches = guards.headOption.exists { case (name, index) =>
          exactGuard.exists { guard =>
            val enableName = requiredBaseName(
              guard.enable,
              s"dynamic Vec write guard ${guard.elementIndex}",
              write.sourceLocation
            )
            index == guard.elementIndex &&
            (name == decoderName || name == enableName)
          }
        }
        if (
          guards.size != 1 || guards.head._2 != leaf.elementIndex ||
          !emittedGuardMatches
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-LAYOUT-MISMATCH",
            s"dynamic write carrier block of Vec '${plan.name}' is not guarded by its authoritative one-hot element",
            write.sourceLocation
          )
        }
      }
    }

    claimAssignmentEvidence(
      assignments ++ dynamicWrites.flatMap(_.assignments),
      live,
      claimed,
      s"consolidating dynamic write of Vec '${plan.name}'",
      sourceLocation
    )

    val firstBlock = retainedBlocks.minBy(_.start)
    val indentation = original(firstBlock.start).takeWhile(_.isWhitespace)
    val bodyIndentation = indentation + "  "
    val dynamicStatements = parsedWrites.map { case (write, values) =>
      val address = requiredBaseName(
        write.address,
        "dynamic Vec write address",
        write.sourceLocation
      )
      val depth = render(plan.shape.depth)
      val slice = plan.dynamicSlice(address, write.elementLeafIndex, clampRead = false)
      val parsedValue = values.head
      bodyIndentation +
        s"if (($address) < ($depth)) $slice ${parsedValue.operator} ${parsedValue.rhs};"
    }
    val replacement =
      Vector(
        s"${indentation}always @(*) begin",
        s"$bodyIndentation${plan.name} ${kinds.head._2} ${source.name};"
      ) ++ dynamicStatements ++ Vector(s"${indentation}end")
    val removed = retainedBlocks.flatMap(block => block.start to block.end)
    val rewritten = original.zipWithIndex.flatMap { case (line, index) =>
      if (index == firstBlock.start) replacement
      else if (removed.contains(index)) Vector.empty
      else Vector(line)
    }
    WholeAssignmentRewrite(rewritten, dynamicWrites)
  }

  private def parseOperationAssignments(
      lines: Vector[String],
      assignments: Vector[DataAssignmentStatement],
      role: String,
      sourceLocation: Option[String]
  ): Vector[ParsedAssignment] = {
    if (assignments.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-MISSING",
        s"$role retained no native leaf assignments",
        sourceLocation
      )
    }
    val parsed = assignments.map { assignment =>
      val targetName = requiredBaseName(assignment.finalTarget, s"$role target", sourceLocation)
      val sourceName = assignment.source match {
        case value: BaseType =>
          Some(requiredBaseName(value, s"$role source", sourceLocation))
        case _ => None
      }
      val value =
        findAssignment(lines, targetName, sourceName, role, sourceLocation)
      sourceName.foreach { exactSourceName =>
        if (value.rhs.trim != exactSourceName) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINEAGE-UNSUPPORTED",
            s"$role exact direct source '$exactSourceName' was emitted as the non-direct expression '${value.rhs.trim}'",
            sourceLocation
          )
        }
      }
      value
    }
    if (parsed.map(_.lineIndex).distinct.size != parsed.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINE-AMBIGUOUS",
        s"multiple $role statements map to one native Verilog line",
        sourceLocation
      )
    }
    parsed
  }

  private def alwaysBlocks(lines: Vector[String]): Vector[AlwaysBlock] = {
    val opener = "^\\s*always\\s*@\\s*\\(\\s*\\*\\s*\\)\\s*begin\\s*$".r
    val beginWord = "\\bbegin\\b".r
    val endWord = "\\bend\\b".r
    lines.zipWithIndex.flatMap {
      case (opener(), start) =>
        var depth = 0
        var index = start
        var end = -1
        while (index < lines.size && end < 0) {
          depth += beginWord.findAllMatchIn(lines(index)).size
          depth -= endWord.findAllMatchIn(lines(index)).size
          if (index > start && depth == 0) end = index
          index += 1
        }
        if (end < 0) None else Some(AlwaysBlock(start, end))
      case _ => None
    }
  }

  private def expressionContainsIdentity(
      root: Expression,
      expected: Expression
  ): Boolean = {
    if (root == null || expected == null) return false
    val seen = new IdentityHashMap[Expression, java.lang.Boolean]()
    var found = false
    def visit(value: Expression): Unit = {
      if (
        !found && value != null &&
        seen.put(value, java.lang.Boolean.TRUE) == null
      ) {
        if (value eq expected) found = true
        else value.foreachExpression(visit)
      }
    }
    visit(root)
    found
  }

  private def requireDistinctAssignmentIdentities(
      assignments: Vector[DataAssignmentStatement],
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    val distinct =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    assignments.foreach { assignment =>
      if (assignment == null) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-STALE",
          s"$role retained a null assignment identity",
          sourceLocation
        )
      }
      if (distinct.put(assignment, java.lang.Boolean.TRUE) != null) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-CARDINALITY",
          s"$role retains one native assignment identity more than once",
          sourceLocation
        )
      }
    }
  }

  private def copyAssignmentEvidence(
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      additional: Vector[DataAssignmentStatement]
  ): IdentityHashMap[DataAssignmentStatement, java.lang.Boolean] = {
    val retained =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    val iterator = live.entrySet().iterator()
    while (iterator.hasNext) {
      val entry = iterator.next()
      retained.put(entry.getKey, entry.getValue)
    }
    additional.foreach { assignment =>
      retained.put(assignment, java.lang.Boolean.TRUE)
    }
    retained
  }

  private def hasLiveDynamicReadResultTarget(
      access: ParameterizedVecDynamicAccess,
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Boolean = {
    val resultIdentities = new IdentityHashMap[BaseType, java.lang.Boolean]()
    access.result.flatten.foreach { leaf =>
      resultIdentities.put(leaf, java.lang.Boolean.TRUE)
    }
    val iterator = live.keySet().iterator()
    var found = false
    while (iterator.hasNext && !found) {
      found = resultIdentities.containsKey(iterator.next().finalTarget)
    }
    found
  }

  private def exactCapturedDynamicReadOwner(
      component: Component,
      access: ParameterizedVecDynamicAccess,
      sourceLocation: Option[String]
  ): Option[ParameterizedStructuralBlock] = {
    if (component == null || access.assignments.isEmpty) return None
    val blocks = structuralBlocksOf(component)
    val occurrences = access.assignments.map { assignment =>
      blocks.iterator.map(_.assignments.count(_ eq assignment)).sum
    }
    if (occurrences.forall(_ == 0)) return None
    if (!occurrences.forall(_ == 1)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-STRUCTURAL-OWNERSHIP-MISMATCH",
        "dynamic Vec read does not retain every exact result assignment exactly once across its structural owners",
        sourceLocation
      )
    }
    val owners = blocks.filter { block =>
      access.assignments.forall { assignment =>
        block.assignments.exists(_ eq assignment)
      }
    }
    if (owners.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-STRUCTURAL-OWNERSHIP-MISMATCH",
        s"dynamic Vec read resolves to ${owners.size} exact structural owners",
        sourceLocation
      )
    }
    requireDistinctAssignmentIdentities(
      owners.head.assignments,
      "captured structural dynamic Vec read owner",
      sourceLocation
    )
    Some(owners.head)
  }

  private def requireLiveAssignmentEvidence(
      assignments: Vector[DataAssignmentStatement],
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    if (assignments.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-MISSING",
        s"$role retained no native assignment identities",
        sourceLocation
      )
    }
    val distinct =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    assignments.foreach { assignment =>
      if (assignment == null || live.get(assignment) == null) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-STALE",
          s"$role retained an assignment identity that is no longer live in the owning component",
          sourceLocation
        )
      }
      if (distinct.put(assignment, java.lang.Boolean.TRUE) != null) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-CARDINALITY",
          s"$role retains one native assignment identity more than once",
          sourceLocation
        )
      }
    }
  }

  private def claimAssignmentEvidence(
      assignments: Vector[DataAssignmentStatement],
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      claimed: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    requireLiveAssignmentEvidence(
      assignments,
      live,
      role,
      sourceLocation
    )
    assignments.foreach { assignment =>
      if (claimed.put(assignment, java.lang.Boolean.TRUE) != null) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-OVERLAP",
          s"one live native statement is claimed by multiple typed Vec operations while $role",
          sourceLocation
        )
      }
    }
  }

  private def validateWholeAssignmentLineage(
      target: VecPlan,
      source: VecPlan,
      assignments: Vector[DataAssignmentStatement],
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    requireLiveAssignmentEvidence(assignments, live, role, sourceLocation)
    if (assignments.size != target.leaves.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINEAGE-UNSUPPORTED",
        s"$role of Vec '${target.name}' retains ${assignments.size} assignments for ${target.leaves.size} exact carrier leaves",
        sourceLocation
      )
    }
    target.leaves.foreach { targetLeaf =>
      val sourceLeaf = source.leaf(targetLeaf.elementIndex, targetLeaf.leafIndex)
      val matching = assignments.filter(assignment =>
        (assignment.finalTarget eq targetLeaf.value) &&
          (assignment.source eq sourceLeaf.value)
      )
      if (matching.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINEAGE-UNSUPPORTED",
          s"$role of Vec '${target.name}' retains ${matching.size} exact source-to-target identities for carrier (${targetLeaf.elementIndex}, ${targetLeaf.leafIndex})",
          sourceLocation
        )
      }
    }
  }

  private def exactLiveDrivers(
      target: BaseType,
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Vector[DataAssignmentStatement] = {
    val values = ArrayBuffer.empty[DataAssignmentStatement]
    val seen = new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    target.foreachStatements {
      case assignment: DataAssignmentStatement
          if (assignment.finalTarget eq target) &&
            live.containsKey(assignment) &&
            seen.put(assignment, java.lang.Boolean.TRUE) == null =>
        values += assignment
      case _ =>
    }
    values.toVector
  }

  /** Re-audit the final live native MultiData.asBits graph.  Only Cat, the
    * native casts to Bits and one-driver type-node copies are admitted; the
    * result is ordered from low packed bit to high packed bit.
    */
  private def exactPackedReadLeaves(
      root: Expression,
      expected: Vector[BaseType],
      blocked: BaseType,
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Option[PackedReadProof] = {
    val expectedSet = new IdentityHashMap[BaseType, java.lang.Boolean]()
    expected.foreach(leaf => expectedSet.put(leaf, java.lang.Boolean.TRUE))
    val visited = new IdentityHashMap[Expression, java.lang.Boolean]()
    def trace(value: Expression): Option[PackedReadProof] = {
      if (value == null || visited.put(value, java.lang.Boolean.TRUE) != null)
        return None
      value match {
        case leaf: BaseType if expectedSet.containsKey(leaf) =>
          Some(PackedReadProof(Vector(leaf), Vector.empty))
        case cat: Operator.Bits.Cat =>
          for {
            low <- trace(cat.right)
            high <- trace(cat.left)
          } yield PackedReadProof(
            low.leavesLowToHigh ++ high.leavesLowToHigh,
            (low.supportAssignments ++ high.supportAssignments).foldLeft(
              Vector.empty[DataAssignmentStatement]
            ) { (known, assignment) =>
              if (known.exists(_ eq assignment)) known else known :+ assignment
            }
          )
        case cast: CastUIntToBits => trace(cast.input)
        case cast: CastSIntToBits => trace(cast.input)
        case cast: CastBoolToBits => trace(cast.input)
        case cast: CastEnumToBits => trace(cast.input)
        case intermediate: BaseType if intermediate ne blocked =>
          val drivers = exactLiveDrivers(intermediate, live)
          if (drivers.size == 1)
            trace(drivers.head.source).map(proof =>
              proof.copy(
                supportAssignments = proof.supportAssignments :+ drivers.head
              )
            )
          else None
        case _ => None
      }
    }
    trace(root)
  }

  private def exactPackedAssignmentRange(
      root: Expression,
      carrier: Bits,
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Option[PackedAssignmentProof] = {
    val visited = new IdentityHashMap[Expression, java.lang.Boolean]()
    def trace(value: Expression): Option[PackedAssignmentProof] = {
      if (value == null || visited.put(value, java.lang.Boolean.TRUE) != null)
        return None
      value match {
        case source if source eq carrier =>
          Some(PackedAssignmentProof(0, carrier.getBitsWidth, Vector.empty))
        case access: BitsRangedAccessFixed =>
          trace(access.source).flatMap { parent =>
            val width = access.getWidth
            if (
              access.lo < 0 || width < 1 ||
              access.lo + width > parent.width
            ) None
            else Some(parent.copy(lo = parent.lo + access.lo, width = width))
          }
        case access: BitsBitAccessFixed =>
          trace(access.source).flatMap { parent =>
            if (access.bitId < 0 || access.bitId >= parent.width) None
            else Some(parent.copy(lo = parent.lo + access.bitId, width = 1))
          }
        case cast: CastBitsToUInt => trace(cast.input)
        case cast: CastBitsToSInt => trace(cast.input)
        case cast: CastBitsToEnum => trace(cast.input)
        case intermediate: BaseType if intermediate ne carrier =>
          val drivers = exactLiveDrivers(intermediate, live)
          if (drivers.size == 1)
            trace(drivers.head.source).map(proof =>
              proof.copy(
                supportAssignments = proof.supportAssignments :+ drivers.head
              )
            )
          else None
        case _ => None
      }
    }
    trace(root)
  }

  private def validatePackedAssignmentLineage(
      target: VecPlan,
      operation: ParameterizedVecPackedAssignment,
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Vector[DataAssignmentStatement] = {
    val role = "packed Vec assignment"
    requireLiveAssignmentEvidence(
      operation.assignments,
      live,
      role,
      operation.sourceLocation
    )
    if (operation.assignments.size != target.leaves.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINEAGE-UNSUPPORTED",
        s"$role of Vec '${target.name}' retains ${operation.assignments.size} assignments for ${target.leaves.size} exact carrier leaves",
        operation.sourceLocation
      )
    }
    if (operation.slices.size != target.leaves.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-ASSIGNMENT-SLICE-MISMATCH",
        s"$role of Vec '${target.name}' retains ${operation.slices.size} fixed-slice proofs for ${target.leaves.size} exact carrier leaves",
        operation.sourceLocation
      )
    }
    var expectedOffset = 0
    val supportAssignments = ArrayBuffer.empty[DataAssignmentStatement]
    val knownSupport =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    target.leaves.foreach { targetLeaf =>
      val expectedWidth = targetLeaf.value.getBitsWidth
      val matching = operation.assignments.filter(assignment => assignment.finalTarget eq targetLeaf.value)
      val retainedSlice = operation.slices.filter(_.target eq targetLeaf.value)
      val actual =
        if (matching.size == 1)
          exactPackedAssignmentRange(
            matching.head.source,
            operation.carrier,
            live
          )
        else None
      if (
        matching.size != 1 || retainedSlice.size != 1 ||
        retainedSlice.head.lo != expectedOffset ||
        retainedSlice.head.width != expectedWidth ||
        actual.map(value => value.lo -> value.width) !=
          Some(expectedOffset -> expectedWidth)
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-ASSIGNMENT-SLICE-MISMATCH",
          s"$role of Vec '${target.name}' lost the exact fixed carrier slice at offset $expectedOffset width $expectedWidth for carrier (${targetLeaf.elementIndex}, ${targetLeaf.leafIndex})",
          operation.sourceLocation
        )
      }
      actual.foreach(_.supportAssignments.foreach { assignment =>
        if (
          !operation.assignments.exists(_ eq assignment) &&
          knownSupport.put(assignment, java.lang.Boolean.TRUE) == null
        ) supportAssignments += assignment
      })
      expectedOffset += expectedWidth
    }
    if (expectedOffset != operation.carrier.getBitsWidth) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-ASSIGNMENT-SLICE-MISMATCH",
        s"$role of Vec '${target.name}' covers $expectedOffset carrier bits, expected ${operation.carrier.getBitsWidth}",
        operation.sourceLocation
      )
    }
    supportAssignments.toVector
  }

  private def validateAutoConnectLineage(
      vector: VecPlan,
      peer: VecPlan,
      assignments: Vector[DataAssignmentStatement],
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      sourceLocation: Option[String]
  ): (VecPlan, VecPlan) = {
    val role = "Vec auto-connect"
    requireLiveAssignmentEvidence(assignments, live, role, sourceLocation)
    if (assignments.size != vector.leaves.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINEAGE-UNSUPPORTED",
        s"$role of Vec '${vector.name}' retains ${assignments.size} assignments for ${vector.leaves.size} exact carrier leaves",
        sourceLocation
      )
    }
    val directions = vector.leaves.map { leaf =>
      val peerLeaf = peer.leaf(leaf.elementIndex, leaf.leafIndex)
      val forward = assignments.filter { assignment =>
        (assignment.finalTarget eq leaf.value) &&
        (assignment.source eq peerLeaf.value)
      }
      val reverse = assignments.filter { assignment =>
        (assignment.finalTarget eq peerLeaf.value) &&
        (assignment.source eq leaf.value)
      }
      if (forward.size + reverse.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINEAGE-UNSUPPORTED",
          s"$role of Vec '${vector.name}' retains ${forward.size + reverse.size} exact direct peer identities for carrier (${leaf.elementIndex}, ${leaf.leafIndex})",
          sourceLocation
        )
      }
      forward.nonEmpty
    }.distinct
    if (directions.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINEAGE-UNSUPPORTED",
        s"$role of Vec '${vector.name}' mixes forward and reverse carrier directions",
        sourceLocation
      )
    }
    if (directions.head) vector -> peer else peer -> vector
  }

  private def rewriteAssignmentGroup(
      original: Vector[String],
      assignments: Vector[DataAssignmentStatement],
      target: String,
      source: String,
      role: String,
      sourceLocation: Option[String],
      claimed: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Vector[String] = {
    if (assignments.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-MISSING",
        s"$role retained no native leaf assignments",
        sourceLocation
      )
    }
    claimAssignmentEvidence(
      assignments,
      live,
      claimed,
      s"lowering $role",
      sourceLocation
    )
    val parsed = assignments.map { assignment =>
      val targetName = requiredBaseName(
        assignment.finalTarget,
        s"$role target",
        sourceLocation
      )
      val sourceName = assignment.source match {
        case value: BaseType =>
          Some(requiredBaseName(value, s"$role source", sourceLocation))
        case _ => None
      }
      val value =
        findAssignment(original, targetName, sourceName, role, sourceLocation)
      sourceName.foreach { exactSourceName =>
        if (value.rhs.trim != exactSourceName) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINEAGE-UNSUPPORTED",
            s"$role exact direct source '$exactSourceName' was emitted as the non-direct expression '${value.rhs.trim}'",
            sourceLocation
          )
        }
      }
      value
    }
    val indexes = parsed.map(_.lineIndex)
    if (indexes.distinct.size != indexes.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINE-AMBIGUOUS",
        s"multiple $role statements map to one native Verilog line",
        sourceLocation
      )
    }
    val kinds = parsed.map(value => value.continuous -> value.operator).distinct
    if (kinds.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-KIND-MISMATCH",
        s"$role crosses continuous/procedural or blocking/nonblocking assignment kinds",
        sourceLocation
      )
    }
    val ordered = indexes.sorted
    val intervening = (ordered.head to ordered.last).filterNot(indexes.contains)
    if (
      intervening.exists(index => {
        val trimmed = original(index).trim
        trimmed.nonEmpty && !trimmed.startsWith("//")
      })
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-NONCONTIGUOUS",
        s"$role native leaf statements are not one contiguous emitted operation",
        sourceLocation
      )
    }
    val first = parsed.minBy(_.lineIndex)
    val statement =
      first.indentation + (if (first.continuous) "assign " else "") +
        s"$target ${first.operator} $source;"
    original.zipWithIndex.flatMap { case (line, index) =>
      if (index == first.lineIndex) Vector(statement)
      else if (indexes.contains(index)) Vector.empty
      else Vector(line)
    }
  }

  /** Remove only exact native temporaries traversed while proving a packed
    * assignment slice.  Their drivers and every consuming leaf assignment
    * were just collapsed into the aggregate assignment; any remaining use
    * means the temporary was shared outside that authoritative operation and
    * must fail closed instead of leaking finite carrier geometry.
    */
  private def removeSupportDeclarations(
      original: Vector[String],
      assignments: Vector[DataAssignmentStatement],
      role: String,
      residualCode: String,
      sourceLocation: Option[String]
  ): Vector[String] = {
    var lines = original
    assignments.foreach { assignment =>
      val target = assignment.finalTarget
      val name = requiredBaseName(
        target,
        s"$role target",
        sourceLocation
      )
      val references = countReferenceIdentifier(lines, name)
      if (references != 1) {
        fail(
          residualCode,
          s"$role '$name' retains ${references - 1} uses outside its exact native operation",
          sourceLocation
        )
      }
      val declaration = parseDeclaration(lines, name, sourceLocation)
      if (declaration.direction.nonEmpty) {
        fail(
          residualCode,
          s"$role '$name' unexpectedly resolves to a module port",
          sourceLocation
        )
      }
      lines = lines.updated(declaration.lineIndex, "")
    }
    lines
  }

  private def findAssignment(
      lines: Vector[String],
      targetName: String,
      sourceName: Option[String],
      role: String,
      sourceLocation: Option[String]
  ): ParsedAssignment = {
    val pattern =
      ("^([ \\t]*)(assign\\s+)?" + Pattern.quote(targetName) +
        "\\s*(<=|=)\\s*(.*?)\\s*;\\s*(?://.*)?$").r
    val matches = lines.zipWithIndex.flatMap { case (line, index) =>
      pattern.findFirstMatchIn(line).flatMap { value =>
        val rhs = value.group(4).trim
        if (sourceName.forall(name => containsIdentifier(rhs, name))) {
          Some(
            ParsedAssignment(
              index,
              value.group(1),
              value.group(2) != null,
              value.group(3),
              rhs
            )
          )
        } else None
      }
    }
    if (matches.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-NOT-FOUND",
        s"native Verilog contains ${matches.size} exact lines for $role target '$targetName'",
        sourceLocation
      )
    }
    matches.head
  }

  private def rewritePackedRead(
      original: Vector[String],
      plan: VecPlan,
      operation: ParameterizedVecPackedRead,
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): PackedReadRewrite = {
    val carrierRetained = operation.carrierAssignments.filter(assignment => assignment.finalTarget eq operation.carrier)
    if (carrierRetained.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-READ-EVIDENCE-MISMATCH",
        s"packed read of Vec '${plan.name}' retains ${carrierRetained.size} exact native carrier assignments",
        operation.sourceLocation
      )
    }
    val expectedLeaves = plan.leaves.map(_.value)
    val exactProof = exactPackedReadLeaves(
      carrierRetained.head.source,
      expectedLeaves,
      operation.carrier,
      live
    )
    if (
      operation.carrierLeavesLowToHigh.size != expectedLeaves.size ||
      !operation.carrierLeavesLowToHigh.zip(expectedLeaves).forall { case (actual, expected) =>
        actual eq expected
      } ||
      exactProof.forall { actual =>
        actual.leavesLowToHigh.size != expectedLeaves.size ||
        !actual.leavesLowToHigh.zip(expectedLeaves).forall { case (left, right) =>
          left eq right
        }
      }
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-READ-LAYOUT-MISMATCH",
        s"packed read of Vec '${plan.name}' lost the exact native low-to-high carrier concatenation",
        operation.sourceLocation
      )
    }
    val carrierName = requiredBaseName(
      carrierRetained.head.finalTarget,
      "packed Vec read carrier",
      operation.sourceLocation
    )
    val carrierParsed = findAssignment(
      original,
      carrierName,
      None,
      "packed Vec read carrier",
      operation.sourceLocation
    )
    val proof = exactProof.get
    val withCarrier = rewritePackedReadCarrierGraph(
      original,
      carrierParsed,
      carrierName,
      plan,
      proof.supportAssignments,
      live,
      operation.sourceLocation
    )
    if (operation.result eq operation.carrier)
      return PackedReadRewrite(withCarrier, proof.supportAssignments)

    val resultRetained = operation.resultAssignments.filter(assignment => assignment.finalTarget eq operation.result)
    val logicalWitnessWidth =
      BigInt(plan.shape.witnessDepth) * plan.shape.elementWidthDefault
    val exactLogicalResize = resultRetained match {
      case Vector(assignment) =>
        assignment.source match {
          case resize: Resize =>
            (resize.input eq operation.carrier) &&
            logicalWitnessWidth.isValidInt &&
            resize.size == logicalWitnessWidth.toInt &&
            resize.size < operation.carrier.getBitsWidth
          case _ => false
        }
      case _ => false
    }
    if (!exactLogicalResize) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-READ-EVIDENCE-MISMATCH",
        s"packed read of Vec '${plan.name}' does not retain one exact logical-witness wrapper over its native carrier",
        operation.sourceLocation
      )
    }
    val resultName = requiredBaseName(
      resultRetained.head.finalTarget,
      "packed Vec read result",
      operation.sourceLocation
    )
    val resultParsed = findAssignment(
      withCarrier,
      resultName,
      None,
      "packed Vec read logical result",
      operation.sourceLocation
    )
    PackedReadRewrite(
      withCarrier.updated(
        resultParsed.lineIndex,
        resultParsed.indentation +
          (if (resultParsed.continuous) "assign " else "") +
          s"$resultName ${resultParsed.operator} ${plan.name};"
      ),
      proof.supportAssignments
    )
  }

  /** Follow only printer aliases reachable from the exact retained carrier
    * assignment.  The native AST proof above establishes leaf order and
    * identity; this emitted-graph audit accounts for backend-introduced cast
    * wires that have no DSL statement identity of their own.  A coincident
    * user assignment is never discovered globally: an alias is admitted only
    * when the exact carrier RHS reaches it and it resolves exclusively to the
    * already-proven leaf identities.
    */
  private def packedReadCarrierAliases(
      original: Vector[String],
      carrier: ParsedAssignment,
      carrierName: String,
      plan: VecPlan,
      retainedSupport: Vector[DataAssignmentStatement],
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      sourceLocation: Option[String]
  ): Vector[(String, ParsedAssignment)] = {
    val identifier = "(?<![A-Za-z0-9_$'])([A-Za-z_][A-Za-z0-9_$]*)".r
    val seenAliases = mutable.HashSet.empty[String]
    val aliases = ArrayBuffer.empty[(String, ParsedAssignment)]

    /** The native emitter may legally fold one exact Vec carrier leaf into
      * its direct identity-retained source.  Admit that source only through
      * the exact live driver of this exact leaf; a coincident emitted name is
      * never sufficient evidence.  Multi-driver and general expression
      * sources deliberately stop at the carrier leaf and retain the stricter
      * emitted-assignment audit below.
      */
    def terminalsOf(leaf: Leaf): Set[String] = {
      val names = mutable.HashSet(leaf.name)
      val visited = new IdentityHashMap[Expression, java.lang.Boolean]()

      def retainName(value: BaseType): Unit =
        Option(value)
          .flatMap(candidate => Option(candidate.getName()))
          .filter(_.nonEmpty)
          .foreach { name =>
            if (!PortableIdentifier.pattern.matcher(name).matches()) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-VEC-LEAF-NAME-INVALID",
                s"packed Vec read exact terminal name '$name' is not a portable Verilog identifier",
                sourceLocation
              )
            }
            names += name
          }

      def visit(value: Expression): Unit = {
        if (value == null || visited.put(value, java.lang.Boolean.TRUE) != null)
          return
        value match {
          case terminal: BaseType =>
            retainName(terminal)
            val drivers = exactLiveDrivers(terminal, live)
            drivers.foreach(assignment => visit(assignment.source))
          case _: Literal => ()
          case expression =>
            expression.foreachDrivingExpression(visit)
        }
      }

      visit(leaf.value)
      names.toSet
    }

    val terminalNames = plan.leaves.flatMap(terminalsOf).toSet
    val exactSignalNames = mutable.HashSet.empty[String]
    val exactAggregateNames = mutable.HashSet.empty[String]
    Option(plan.vector.component).foreach { component =>
      def retainExactSignal(value: BaseType): Unit =
        Option(value)
          .flatMap(signal => Option(signal.getName()))
          .filter(name => PortableIdentifier.pattern.matcher(name).matches())
          .foreach(exactSignalNames += _)
      component.dslBody.walkDeclarations {
        case signal: BaseType if !signal.isSuffix => retainExactSignal(signal)
        case _                                    =>
      }
      component.getAllIo.foreach(retainExactSignal)
      publicationVectors(component).foreach { vector =>
        val shape = ParameterizedVec.shapeOf(vector).getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-SHAPE-MISSING",
            "one exact packed-read peer Vec lost its retained shape",
            sourceLocation
          )
        }
        exactAggregateNames += requiredVecName(
          vector,
          sourceLocation.orElse(shape.sourceLocation)
        )
      }
    }
    val retainedNames = retainedSupport
      .map(assignment =>
        requiredBaseName(
          assignment.finalTarget,
          "packed Vec read retained support target",
          sourceLocation
        )
      )
      .toSet

    def visit(expression: String): Unit =
      identifier.findAllMatchIn(expression).foreach { value =>
        val name = value.group(1)
        if (terminalNames.contains(name)) ()
        else if (retainedNames.contains(name) && !seenAliases.contains(name)) {
          val parsed = findAssignment(
            original,
            name,
            None,
            "packed Vec read retained support",
            sourceLocation
          )
          seenAliases += name
          aliases += name -> parsed
          visit(parsed.rhs)
        } else if (name == carrierName || name == plan.name) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-READ-LAYOUT-MISMATCH",
            s"packed read of Vec '${plan.name}' contains a cyclic emitted carrier reference '$name'",
            sourceLocation
          )
        } else if (exactSignalNames.contains(name) || exactAggregateNames.contains(name)) {
          // The exact AST proof already establishes the logical leaf.  A
          // printer-only chain may terminate at a retained local signal or
          // port, or at another exact packed Vec already rewritten in this
          // component. Those shared identities must not be removed as packing
          // support.
          ()
        } else if (!seenAliases.contains(name)) {
          val parsed = findAssignment(
            original,
            name,
            None,
            "packed Vec read emitted support",
            sourceLocation
          )
          seenAliases += name
          aliases += name -> parsed
          visit(parsed.rhs)
        }
      }

    visit(carrier.rhs)

    if (!retainedNames.subsetOf(seenAliases.toSet)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-READ-EVIDENCE-MISMATCH",
        s"packed read of Vec '${plan.name}' retains support identities that are absent from its emitted carrier graph",
        sourceLocation
      )
    }

    aliases.toVector
  }

  private def rewritePackedReadCarrierGraph(
      original: Vector[String],
      carrier: ParsedAssignment,
      carrierName: String,
      plan: VecPlan,
      retainedSupport: Vector[DataAssignmentStatement],
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      sourceLocation: Option[String]
  ): Vector[String] = {
    val aliases = packedReadCarrierAliases(
      original,
      carrier,
      carrierName,
      plan,
      retainedSupport,
      live,
      sourceLocation
    )

    var lines = original.updated(
      carrier.lineIndex,
      carrier.indentation +
        (if (carrier.continuous) "assign " else "") +
        s"$carrierName ${carrier.operator} ${plan.name};"
    )
    aliases.foreach { case (_, parsed) =>
      lines = lines.updated(parsed.lineIndex, "")
    }
    aliases.foreach { case (name, _) =>
      val references = countReferenceIdentifier(lines, name)
      if (references != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-READ-SUPPORT-RESIDUAL",
          s"packed Vec read emitted support '$name' retains ${references - 1} uses outside its exact carrier graph",
          sourceLocation
        )
      }
      val declaration = parseDeclaration(lines, name, sourceLocation)
      if (declaration.direction.nonEmpty) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-READ-SUPPORT-RESIDUAL",
          s"packed Vec read emitted support '$name' unexpectedly resolves to a module port",
          sourceLocation
        )
      }
      lines = lines.updated(declaration.lineIndex, "")
    }
    lines
  }

  private def rewritePackedCarrierBridge(
      original: Vector[String],
      source: Bits,
      carrier: Bits,
      assignments: Vector[DataAssignmentStatement],
      plan: VecPlan,
      role: String,
      sourceLocation: Option[String]
  ): Vector[String] = {
    val retained = assignments.filter(assignment => assignment.finalTarget eq carrier)
    val expectedWidth = BigInt(plan.shape.carrierCapacity) * plan.shape.elementWidthDefault
    if (retained.size != 1 || !expectedWidth.isValidInt) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-CARRIER-EVIDENCE-MISMATCH",
        s"$role retains ${retained.size} exact bridge assignments",
        sourceLocation
      )
    }
    retained.head.source match {
      case resize: Resize
          if (resize.input eq source) &&
            resize.size == expectedWidth.toInt &&
            source.getBitsWidth <= resize.size =>
      case _ =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-CARRIER-EVIDENCE-MISMATCH",
          s"$role is not the exact native LSB-preserving zero-extension of its logical source",
          sourceLocation
        )
    }
    val carrierName = requiredBaseName(carrier, s"$role target", sourceLocation)
    val sourceName = requiredBaseName(source, s"$role source", sourceLocation)
    val parsed = findAssignment(
      original,
      carrierName,
      None,
      role,
      sourceLocation
    )
    val rewritten = original.updated(
      parsed.lineIndex,
      parsed.indentation + (if (parsed.continuous) "assign " else "") +
        s"$carrierName ${parsed.operator} $sourceName;"
    )
    rewritePackedDeclaration(
      rewritten,
      carrierName,
      plan.range,
      sourceLocation
    )
  }

  /** Revalidate and locate the exact native Multiplexer support graph shared
    * by structural relocation and final packed Vec publication.
    */
  private def exactDynamicReadSupport(
      original: Vector[String],
      plan: VecPlan,
      access: ParameterizedVecDynamicAccess,
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): DynamicReadSupport = {
    val resultLeaves = access.result.flatten.toVector
    if (resultLeaves.size != plan.shape.elementLeaves.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-LAYOUT-MISMATCH",
        s"dynamic read result of Vec '${plan.name}' has ${resultLeaves.size} leaves, expected ${plan.shape.elementLeaves.size}",
        access.sourceLocation
      )
    }
    if (plan.shape.carrierCapacity <= 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-SUPPORT-UNEXPECTED",
        s"dynamic read of Vec '${plan.name}' has no native Multiplexer support process at carrier capacity ${plan.shape.carrierCapacity}",
        access.sourceLocation.orElse(plan.sourceLocation)
      )
    }

    val resultNames = Array.fill[String](resultLeaves.size)(null)
    val muxNames = Array.fill[String](resultLeaves.size)(null)
    val blocks = caseBlocks(original)
    val assignmentsByBlock = mutable.LinkedHashMap.empty[
      CaseBlock,
      ArrayBuffer[(Int, String)]
    ]

    plan.shape.elementLeaves.indices.foreach { leafIndex =>
      val sourceNames = plan.leaves
        .filter(_.leafIndex == leafIndex)
        .map(_.name)
      val retained = access.assignments.filter(assignment =>
        assignment.finalTarget eq resultLeaves(leafIndex)
      )
      if (retained.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-EVIDENCE-MISMATCH",
          s"dynamic read leaf $leafIndex of Vec '${plan.name}' retains ${retained.size} exact mux assignments",
          access.sourceLocation
        )
      }
      val resultName = requiredBaseName(
        retained.head.finalTarget,
        "dynamic Vec read result",
        access.sourceLocation
      )
      val mux = retained.head.source match {
        case value: Multiplexer => value
        case _ =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-EVIDENCE-MISMATCH",
            s"dynamic read leaf $leafIndex of Vec '${plan.name}' is not driven by its retained native Multiplexer",
            access.sourceLocation
          )
      }
      val expectedInputs = plan.leaves
        .filter(_.leafIndex == leafIndex)
        .map(_.value)
      if (
        mux.inputs.size != expectedInputs.size ||
        !mux.inputs.zip(expectedInputs).forall { case (actual, expected) =>
          actual eq expected
        }
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-EVIDENCE-MISMATCH",
          s"retained Multiplexer for dynamic read leaf $leafIndex of Vec '${plan.name}' does not contain the exact carrier identities",
          access.sourceLocation
        )
      }
      val exactSelect = exactDynamicReadSelect(
        mux.select,
        access.address,
        log2Up(plan.shape.carrierCapacity),
        live
      )
      if (!exactSelect) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-EVIDENCE-MISMATCH",
          s"retained Multiplexer for dynamic read leaf $leafIndex of Vec '${plan.name}' does not retain the exact access address or its canonical carrier-width Resize",
          access.sourceLocation
        )
      }

      // ComponentEmitter gives every Multiplexer a procedural wrapper and
      // leaves the exact retained assignment as a direct connection from that
      // wrapper into its final target. The retained IR identities above select
      // the operation before emitted syntax is used to verify that wrapper.
      val resultAssignment = findAssignment(
        original,
        resultName,
        None,
        "dynamic Vec read result",
        access.sourceLocation
      )
      val muxName = requiredDirectIdentifier(
        resultAssignment.rhs,
        "dynamic Vec read Multiplexer wrapper",
        access.sourceLocation
      )
      val candidates = blocks.filter { block =>
        val mappings = dynamicReadMappings(original, block)
        sourceNames.forall(source => mappings.contains(muxName -> source))
      }
      if (candidates.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-NOT-FOUND",
          s"native Verilog contains ${candidates.size} exact identity-targeted witness muxes for dynamic read leaf $leafIndex of Vec '${plan.name}'",
          access.sourceLocation
        )
      }
      val block = candidates.head
      resultNames(leafIndex) = resultName
      muxNames(leafIndex) = muxName
      assignmentsByBlock
        .getOrElseUpdate(block, ArrayBuffer.empty[(Int, String)]) +=
        leafIndex -> muxName
    }
    if (
      resultNames.exists(_ == null) || muxNames.exists(_ == null) ||
      resultNames.distinct.length != resultNames.length ||
      muxNames.distinct.length != muxNames.length
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-LAYOUT-MISMATCH",
        s"dynamic read of composite Vec '${plan.name}' does not retain one distinct native mux result per element leaf",
        access.sourceLocation
      )
    }

    val exactBlocks = assignmentsByBlock.toVector.map { case (block, targets) =>
      val retainedTargets = targets.toVector
      validateCanonicalDynamicReadBlock(
        original,
        block,
        retainedTargets,
        plan,
        access.sourceLocation
      )
      block -> retainedTargets
    }
    DynamicReadSupport(
      resultNames.toVector,
      muxNames.toVector,
      exactBlocks
    )
  }

  private def rewriteDynamicRead(
      component: Component,
      original: Vector[String],
      plan: VecPlan,
      access: ParameterizedVecDynamicAccess,
      claimed: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Vector[String] = {
    val resultLeaves = access.result.flatten.toVector
    if (resultLeaves.size != plan.shape.elementLeaves.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-LAYOUT-MISMATCH",
        s"dynamic read result of Vec '${plan.name}' has ${resultLeaves.size} leaves, expected ${plan.shape.elementLeaves.size}",
        access.sourceLocation
      )
    }
    val addressName = requiredBaseName(
      access.address,
      "dynamic Vec read address",
      access.sourceLocation
    )

    requireDistinctAssignmentIdentities(
      access.assignments,
      "dynamic Vec read",
      access.sourceLocation.orElse(plan.sourceLocation)
    )
    val graphLiveCount = access.assignments.count(live.containsKey)
    val effectiveLive =
      if (graphLiveCount == access.assignments.size) live
      else if (
        graphLiveCount == 0 &&
        !hasLiveDynamicReadResultTarget(access, live)
      ) {
        exactCapturedDynamicReadOwner(
          component,
          access,
          access.sourceLocation.orElse(plan.sourceLocation)
        ) match {
          case Some(owner) => copyAssignmentEvidence(live, owner.assignments)
          case None        => live
        }
      } else live

    val liveRetained = access.assignments.filter(effectiveLive.containsKey)
    if (liveRetained.size != access.assignments.size) {
      val liveResultTarget = {
        val resultIdentities =
          new IdentityHashMap[BaseType, java.lang.Boolean]()
        resultLeaves.foreach(leaf => resultIdentities.put(leaf, java.lang.Boolean.TRUE))
        val iterator = effectiveLive.entrySet().iterator()
        var found = false
        while (iterator.hasNext && !found) {
          val assignment = iterator.next().getKey
          found = resultIdentities.containsKey(assignment.finalTarget)
        }
        found
      }
      // A writable indexed access may be used only as an assignment target;
      // then the inherited readEmu result is legitimately pruned in full and
      // the separately retained DynamicWrite operations remain authoritative.
      // Any partial pruning or later live assignment to the same exact result
      // identity is stale evidence and must fail closed.
      if (access.writable && liveRetained.isEmpty && !liveResultTarget) return original
      requireLiveAssignmentEvidence(
        access.assignments,
        effectiveLive,
        "dynamic Vec read",
        access.sourceLocation
      )
    }
    claimAssignmentEvidence(
      access.assignments,
      effectiveLive,
      claimed,
      s"lowering dynamic read of Vec '${plan.name}'",
      access.sourceLocation
    )

    // A one-element carrier is optimized by the native Vec algorithm into a
    // direct assignment instead of a case mux. Consume only the assignment
    // identities retained by that exact dynamic access. An unused writable
    // result needs no emitted replacement; another expression that happens to
    // mention the sole carrier is never evidence for this operation.
    if (plan.shape.carrierCapacity == 1) {
      var rewritten = original
      val published = ArrayBuffer.empty[(String, ParameterizedVecLeafShape)]
      resultLeaves.zipWithIndex.foreach { case (resultLeaf, leafIndex) =>
        val sourceName = plan.leaf(0, leafIndex).name
        val retained = access.assignments.filter(assignment => assignment.finalTarget eq resultLeaf)
        if (retained.size == 1) {
          if (retained.head.source ne plan.leaf(0, leafIndex).value) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-EVIDENCE-MISMATCH",
              s"single-carrier dynamic read leaf $leafIndex of Vec '${plan.name}' is not driven directly by its exact sole carrier identity",
              access.sourceLocation
            )
          }
          val resultName = requiredBaseName(
            retained.head.finalTarget,
            "single-carrier dynamic Vec read result",
            access.sourceLocation
          )
          val parsed = findAssignment(
            rewritten,
            resultName,
            Some(sourceName),
            "single-carrier dynamic Vec read",
            access.sourceLocation
          )
          if (parsed.rhs.trim != sourceName) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-EVIDENCE-MISMATCH",
              s"single-carrier dynamic read leaf $leafIndex of Vec '${plan.name}' was not emitted as its exact direct carrier source",
              access.sourceLocation
            )
          }
          val slice = plan.dynamicSlice(
            addressName,
            leafIndex,
            clampRead = true
          )
          val rhs =
            if (isSignedLeaf(plan.shape.elementLeaves(leafIndex)))
              s"$$signed($slice)"
            else slice
          rewritten = rewritten.updated(
            parsed.lineIndex,
            parsed.indentation + (if (parsed.continuous) "assign " else "") +
              s"$resultName ${parsed.operator} $rhs;"
          )
          published += resultName -> plan.shape.elementLeaves(leafIndex)
        } else if (!(access.writable && retained.isEmpty)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-EVIDENCE-MISMATCH",
            s"single-carrier dynamic read leaf $leafIndex of Vec '${plan.name}' retains ${retained.size} exact result assignments",
            access.sourceLocation
          )
        }
      }
      return published.foldLeft(rewritten) {
        case (lines, (name, leafShape)) if leafShape.width.parameters.nonEmpty =>
          rewritePackedDeclaration(
            lines,
            name,
            s"[${factor(render(leafShape.width))}-1:0]",
            access.sourceLocation
          )
        case (lines, _) => lines
      }
    }

    val support = exactDynamicReadSupport(
      original,
      plan,
      access,
      effectiveLive
    )
    val rewritten = support.assignmentsByBlock
      .sortBy { case (block, _) => -block.start }
      .foldLeft(original) { case (lines, (block, targets)) =>
        val indentation = lines(block.start).takeWhile(_.isWhitespace)
        val statements = targets.sortBy(_._1).map { case (leafIndex, target) =>
          val slice = plan.dynamicSlice(addressName, leafIndex, clampRead = true)
          val rhs =
            if (isSignedLeaf(plan.shape.elementLeaves(leafIndex))) s"$$signed($slice)"
            else slice
          s"$indentation  $target = $rhs;"
        }
        val replacement =
          Vector(s"${indentation}always @(*) begin") ++ statements ++
            Vector(s"${indentation}end")
        lines.patch(block.start, replacement, block.end - block.start + 1)
      }
    support.resultNames.zip(support.muxNames).zip(plan.shape.elementLeaves).foldLeft(rewritten) {
      case (lines, ((resultName, muxName), leafShape)) if leafShape.width.parameters.nonEmpty =>
        Vector(resultName, muxName).distinct.foldLeft(lines) { (current, name) =>
          rewritePackedDeclaration(
            current,
            name,
            s"[${factor(render(leafShape.width))}-1:0]",
            access.sourceLocation
          )
        }
      case (lines, _) => lines
    }
  }

  /** Accept only the authoritative address or the one carrier-width Resize
    * introduced by Vec.readEmu. A named type node may wrap that Resize through
    * one exact live assignment; no general subtree provenance is admitted.
    */
  private def exactDynamicReadSelect(
      select: Expression,
      address: UInt,
      carrierWidth: Int,
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Boolean = {
    if (select eq address) return true
    select match {
      case resize: ResizeUInt =>
        (resize.input eq address) && resize.size == carrierWidth
      case wrapper: BaseType =>
        val drivers = exactLiveDrivers(wrapper, live)
        drivers.size == 1 && (drivers.head.source match {
          case resize: ResizeUInt =>
            (resize.input eq address) && resize.size == carrierWidth
          case _ => false
        })
      case _ => false
    }
  }

  private val DynamicReadMapping =
    ("^\\s*(?:(?:[0-9]+(?:'b[01xXzZ?_]+)?|default)\\s*:\\s*)?" +
      "([A-Za-z_][A-Za-z0-9_$]*)\\s*=\\s*" +
      "([A-Za-z_][A-Za-z0-9_$]*)\\s*;\\s*(?://.*)?$").r

  private val DynamicReadCaseBegin =
    "^\\s*(?:[0-9]+(?:'b[01xXzZ?_]+)?|default)\\s*:\\s*begin\\s*(?://.*)?$".r

  private val DynamicReadCaseEnd = "^\\s*end\\s*(?://.*)?$".r

  private def dynamicReadMappings(
      lines: Vector[String],
      block: CaseBlock
  ): Vector[(String, String)] =
    (block.caseLine + 1 until block.end - 1).flatMap { index =>
      lines(index) match {
        case DynamicReadMapping(target, source) => Some(target -> source)
        case _                                  => None
      }
    }.toVector

  /** A retained mux assignment may be replaced only when its complete native
    * block is the canonical flat case emitted by Vec.readEmu. Extra user
    * statements, nested control or coincident assignments fail closed rather
    * than being deleted with the witness mux.
    */
  private def validateCanonicalDynamicReadBlock(
      lines: Vector[String],
      block: CaseBlock,
      targets: Vector[(Int, String)],
      plan: VecPlan,
      sourceLocation: Option[String]
  ): Unit = {
    val expected = targets.flatMap { case (leafIndex, target) =>
      plan.leaves
        .filter(_.leafIndex == leafIndex)
        .map(leaf => target -> leaf.name)
    }
    val mappings = dynamicReadMappings(lines, block)
    val meaningfulBody = (block.caseLine + 1 until block.end - 1).filter { index =>
      val line = lines(index).trim
      line.nonEmpty && !line.startsWith("//")
    }
    val everyBodyLineIsCanonical = meaningfulBody.forall { index =>
      val line = lines(index)
      DynamicReadMapping.findFirstMatchIn(line).nonEmpty ||
      DynamicReadCaseBegin.findFirstMatchIn(line).nonEmpty ||
      DynamicReadCaseEnd.findFirstMatchIn(line).nonEmpty
    }
    def counts(values: Vector[(String, String)]): Map[(String, String), Int] =
      values.groupBy(identity).map { case (value, occurrences) =>
        value -> occurrences.size
      }
    if (
      !everyBodyLineIsCanonical ||
      counts(mappings) != counts(expected)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-READ-CONTROL-UNSUPPORTED",
        s"exact witness mux for Vec '${plan.name}' contains statements beyond its retained canonical carrier mapping",
        sourceLocation.orElse(plan.sourceLocation)
      )
    }
  }

  private def rewriteDynamicWrites(
      original: Vector[String],
      plan: VecPlan,
      writes: Vector[ParameterizedVecDynamicWrite],
      claimed: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Vector[String] = {
    if (writes.isEmpty) return original
    if (writes.exists(_.assignments.isEmpty)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-EVIDENCE-MISSING",
        s"one dynamic write of Vec '${plan.name}' retained no native assignments",
        writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
      )
    }
    writes.foreach { write =>
      requireLiveAssignmentEvidence(
        write.assignments,
        live,
        "dynamic Vec write",
        write.sourceLocation
      )
      validateDynamicWriteGuardLineage(plan, write, live)
    }
    val firstAddress = writes.head.address
    if (writes.exists(write => write.address ne firstAddress)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-ADDRESS-CONFLICT",
        s"Vec '${plan.name}' retains multiple dynamic-write address identities; priority composition is not proven generic",
        writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
      )
    }
    validateSharedDynamicWriteAccess(plan, writes)
    claimDynamicWriteSupportEvidence(plan, writes, live, claimed)
    val firstDecoder = writes.head.decoder
    val firstGuards = writes.head.guards.sortBy(_.elementIndex)
    writes
      .groupBy(_.elementLeafIndex)
      .collectFirst {
        case (leafIndex, values) if values.size != 1 => leafIndex
      }
      .foreach { leafIndex =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-PRIORITY-UNSUPPORTED",
          s"Vec '${plan.name}' retains multiple dynamic writes for element leaf $leafIndex; their assignment priority is not proven generic",
          writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
        )
      }
    val address = requiredBaseName(
      firstAddress,
      "dynamic Vec write address",
      writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
    )
    val depth = render(plan.shape.depth)
    val parsedWrites = writes.map { write =>
      if (
        write.elementLeafIndex < 0 ||
        write.elementLeafIndex >= plan.shape.elementLeaves.size
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-LAYOUT-MISMATCH",
          s"dynamic write of Vec '${plan.name}' retains invalid element-leaf index ${write.elementLeafIndex}",
          write.sourceLocation
        )
      }
      val expectedLeaves = plan.leaves
        .filter(_.leafIndex == write.elementLeafIndex)
        .sortBy(_.elementIndex)
      if (write.assignments.size != expectedLeaves.size) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-LAYOUT-MISMATCH",
          s"dynamic write of Vec '${plan.name}' retains ${write.assignments.size} assignments for ${expectedLeaves.size} carrier elements of leaf ${write.elementLeafIndex}",
          write.sourceLocation
        )
      }
      val values = expectedLeaves.map { leaf =>
        val matching = write.assignments.filter(assignment => assignment.finalTarget eq leaf.value)
        if (matching.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-LAYOUT-MISMATCH",
            s"dynamic write of Vec '${plan.name}' retains ${matching.size} exact assignments for carrier element ${leaf.elementIndex}, leaf ${leaf.leafIndex}",
            write.sourceLocation
          )
        }
        val assignment = matching.head
        claimAssignmentEvidence(
          Vector(assignment),
          live,
          claimed,
          s"lowering dynamic write of Vec '${plan.name}'",
          write.sourceLocation
        )
        val target = requiredBaseName(
          assignment.finalTarget,
          "dynamic Vec write target",
          write.sourceLocation
        )
        val source = assignment.source match {
          case value: BaseType =>
            Some(requiredBaseName(value, "dynamic Vec write source", write.sourceLocation))
          case _ => None
        }
        val parsed = findAssignment(
          original,
          target,
          source,
          "dynamic Vec write",
          write.sourceLocation
        )
        source.foreach { exactSourceName =>
          if (parsed.rhs.trim != exactSourceName) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINEAGE-UNSUPPORTED",
              s"dynamic Vec write exact direct source '$exactSourceName' was emitted as the non-direct expression '${parsed.rhs.trim}'",
              write.sourceLocation
            )
          }
        }
        if (parsed.continuous) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-CONTINUOUS",
            s"dynamic write of Vec '${plan.name}' was emitted as a continuous assignment",
            write.sourceLocation
          )
        }
        DynamicWriteAssignment(
          assignment,
          parsed,
          leaf,
          proceduralBlockContaining(
            original,
            parsed.lineIndex,
            write.sourceLocation
          )
        )
      }
      val rightHandSides = values.map(_.parsed.rhs).distinct
      val operators = values.map(_.parsed.operator).distinct
      if (rightHandSides.size != 1 || operators.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-SOURCE-MISMATCH",
          s"dynamic write of Vec '${plan.name}' does not retain one uniform source and assignment operator for leaf ${write.elementLeafIndex}",
          write.sourceLocation
        )
      }
      ParsedDynamicWrite(
        write,
        rightHandSides.head,
        operators.head,
        values
      )
    }

    val allAssignments = parsedWrites.flatMap(_.assignments)
    val owners = allAssignments.map(_.owner).distinct.sortBy(_.start)
    val assignmentsByOwner = allAssignments.groupBy(_.owner)
    val normalizedEvents = owners.map { owner =>
      val opener = original(owner.start)
      val pattern = "^([ \\t]*)always\\s*@\\s*(.*?)\\s*begin\\s*$".r
      opener match {
        case pattern(indentation, event) =>
          (indentation, event.replaceAll("\\s+", ""))
        case _ =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-EVENT-UNSUPPORTED",
            s"dynamic write of Vec '${plan.name}' has a non-canonical native event block '${opener.trim}'",
            writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
          )
      }
    }
    if (normalizedEvents.map(_._2).distinct.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-EVENT-MISMATCH",
        s"dynamic write of Vec '${plan.name}' crosses incompatible native event controls",
        writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
      )
    }

    val indexedGuardPattern =
      "^if\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s*\\[\\s*([0-9]+)\\s*\\]\\s*\\)\\s*begin\\s*$".r
    val directGuardPattern =
      "^if\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s*\\)\\s*begin\\s*$".r
    val decoderName = requiredBaseName(
      firstDecoder,
      "dynamic Vec write decoder",
      writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
    )
    val enableIndexByName = firstGuards.map { guard =>
      requiredBaseName(
        guard.enable,
        s"dynamic Vec write guard ${guard.elementIndex}",
        writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
      ) -> guard.elementIndex
    }.toMap
    if (enableIndexByName.size != firstGuards.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
        s"Vec '${plan.name}' dynamic-write guard identities do not have distinct emitted names",
        writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
      )
    }
    owners.foreach { owner =>
      val entries = assignmentsByOwner.getOrElse(owner, Vector.empty)
      val byLine = entries.map(value => value.parsed.lineIndex -> value).toMap
      if (byLine.size != entries.size) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-LINE-AMBIGUOUS",
          s"dynamic write carrier block of Vec '${plan.name}' maps multiple retained assignments to one native line",
          writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
        )
      }
      val meaningful = (owner.start to owner.end).filter { index =>
        val line = original(index).trim
        line.nonEmpty && !line.startsWith("//")
      }
      if (
        meaningful.size < 5 || meaningful.head != owner.start ||
        meaningful.last != owner.end || original(owner.end).trim != "end"
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-CONTROL-UNSUPPORTED",
          s"dynamic write carrier block of Vec '${plan.name}' is not one canonical sequence of authoritative one-hot guards",
          writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
        )
      }
      val seen = mutable.HashSet.empty[Int]
      var cursor = 1
      while (cursor < meaningful.size - 1) {
        val guard = original(meaningful(cursor)).trim match {
          case indexedGuardPattern(name, indexText) if name == decoderName =>
            name -> indexText.toInt
          case directGuardPattern(name) if enableIndexByName.contains(name) =>
            name -> enableIndexByName(name)
          case _ =>
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-CONTROL-UNSUPPORTED",
              s"dynamic write carrier block of Vec '${plan.name}' contains reset, enable or control flow beyond its authoritative one-hot element guard at '${original(meaningful(cursor)).trim}'",
              writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
            )
        }
        cursor += 1
        val group = ArrayBuffer.empty[DynamicWriteAssignment]
        while (
          cursor < meaningful.size - 1 &&
          byLine.contains(meaningful(cursor))
        ) {
          group += byLine(meaningful(cursor))
          seen += meaningful(cursor)
          cursor += 1
        }
        val groupElements = group.map(_.leaf.elementIndex).distinct
        if (
          group.isEmpty || cursor >= meaningful.size - 1 ||
          original(meaningful(cursor)).trim != "end" ||
          groupElements.size != 1 || groupElements.head != guard._2
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-CONTROL-UNSUPPORTED",
            s"dynamic write carrier block of Vec '${plan.name}' does not map one authoritative guard bit to its exact retained carrier element",
            writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
          )
        }
        cursor += 1
      }
      if (cursor != meaningful.size - 1 || seen.size != entries.size) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-CONTROL-UNSUPPORTED",
          s"dynamic write carrier block of Vec '${plan.name}' contains unproven native statements",
          writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
        )
      }
    }

    val firstOwner = owners.head
    val indentation = normalizedEvents.head._1
    val bodyIndentation = indentation + "  "
    val statements = parsedWrites.sortBy(_.operation.elementLeafIndex).map { parsedWrite =>
      val slice = plan.dynamicSlice(
        address,
        parsedWrite.operation.elementLeafIndex,
        clampRead = false
      )
      s"$bodyIndentation$slice ${parsedWrite.operator} ${parsedWrite.rhs};"
    }
    val guarded =
      Vector(s"${bodyIndentation}if (($address) < ($depth)) begin") ++
        statements.map(value => bodyIndentation + "  " + value.trim) ++
        Vector(s"${bodyIndentation}end")
    val replacement =
      Vector(original(firstOwner.start)) ++ guarded ++ Vector(s"${indentation}end")
    val removed = owners.flatMap(owner => owner.start to owner.end).toSet
    original.zipWithIndex.flatMap { case (line, index) =>
      if (index == firstOwner.start) replacement
      else if (removed.contains(index)) Vector.empty
      else Vector(line)
    }
  }

  private def validateDynamicWriteGuardLineage(
      plan: VecPlan,
      write: ParameterizedVecDynamicWrite,
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Unit = {
    val carrierWidth = log2Up(plan.shape.carrierCapacity)
    if (write.carrierAddress eq write.address) {
      if (write.carrierAddressAssignments.nonEmpty) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
          s"dynamic write of Vec '${plan.name}' retains unexpected carrier-address bridge evidence",
          write.sourceLocation
        )
      }
    } else {
      requireLiveAssignmentEvidence(
        write.carrierAddressAssignments,
        live,
        "dynamic Vec write carrier address",
        write.sourceLocation
      )
      val exactProjection = write.carrierAddressAssignments match {
        case Vector(assignment) if assignment.finalTarget eq write.carrierAddress =>
          assignment.source match {
            case resize: ResizeUInt =>
              (resize.input eq write.address) && resize.size == carrierWidth
            case _ => false
          }
        case _ => false
      }
      if (!exactProjection) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
          s"dynamic write of Vec '${plan.name}' lost its exact carrier-width address Resize",
          write.sourceLocation
        )
      }
    }

    requireLiveAssignmentEvidence(
      write.decoderOneAssignments,
      live,
      "dynamic Vec write decoder one literal",
      write.sourceLocation
    )
    val exactDecoderOne = write.decoderOneAssignments match {
      case Vector(assignment) if assignment.finalTarget eq write.decoderOne =>
        assignment.source match {
          case literal: UIntLiteral =>
            !literal.hasPoison() && literal.value == BigInt(1) &&
            write.decoderOne.getBitsWidth == 1
          case _ => false
        }
      case _ => false
    }
    if (!exactDecoderOne) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
        s"dynamic write of Vec '${plan.name}' lost its exact native decoder one-literal identity",
        write.sourceLocation
      )
    }

    requireLiveAssignmentEvidence(
      write.decoderAssignments,
      live,
      "dynamic Vec write decoder",
      write.sourceLocation
    )
    val exactDecoder = write.decoderAssignments match {
      case Vector(assignment) if assignment.finalTarget eq write.decoder =>
        assignment.source match {
          case shift: Operator.UInt.ShiftLeftByUInt =>
            (shift.right eq write.carrierAddress) &&
            (shift.left eq write.decoderOne)
          case _ => false
        }
      case _ => false
    }
    val decoderAddressWidth = write.carrierAddress.getBitsWidth
    if (decoderAddressWidth < 0 || decoderAddressWidth >= 31) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
        s"dynamic write of Vec '${plan.name}' has unsupported carrier-address width $decoderAddressWidth",
        write.sourceLocation
      )
    }
    val decoderWidth = 1 << decoderAddressWidth
    if (!exactDecoder || write.decoder.getBitsWidth != decoderWidth) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
        s"dynamic write of Vec '${plan.name}' lost its exact one-shifted carrier decoder",
        write.sourceLocation
      )
    }

    if (
      write.guards.size != plan.shape.carrierCapacity ||
      write.guards.map(_.elementIndex).sorted !=
        (0 until plan.shape.carrierCapacity).toVector
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
        s"dynamic write of Vec '${plan.name}' does not retain one exact guard per carrier element",
        write.sourceLocation
      )
    }
    write.guards.foreach { guard =>
      requireLiveAssignmentEvidence(
        guard.enableAssignments,
        live,
        s"dynamic Vec write guard ${guard.elementIndex}",
        write.sourceLocation
      )
      val expectedDataAssignments = write.assignments.filter(
        _ eq guard.assignment
      )
      val exactEnable = guard.enableAssignments match {
        case Vector(assignment) if assignment.finalTarget eq guard.enable =>
          assignment.source match {
            case access: UIntBitAccessFixed =>
              (access.source eq write.decoder) &&
              access.bitId == guard.elementIndex
            case _ => false
          }
        case _ => false
      }
      val directStatements = ArrayBuffer.empty[Statement]
      guard.whenStatement.whenTrue.foreachStatements(directStatements += _)
      val exactWhen =
        guard.assignment.parentScope != null &&
          (guard.assignment.parentScope.parentStatement eq guard.whenStatement) &&
          (guard.whenStatement.cond eq guard.enable) &&
          (guard.whenStatement.parentScope eq plan.vector.component.dslBody) &&
          guard.whenStatement.whenFalse.isEmpty &&
          directStatements.size == 1 &&
          (directStatements.head eq guard.assignment)
      if (expectedDataAssignments.size != 1 || !exactEnable || !exactWhen) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-CONTROL-UNSUPPORTED",
          s"dynamic write carrier element ${guard.elementIndex} of Vec '${plan.name}' is not controlled solely by its exact retained decoder-bit When statement",
          write.sourceLocation
        )
      }
    }
  }

  private def validateSharedDynamicWriteAccess(
      plan: VecPlan,
      writes: Vector[ParameterizedVecDynamicWrite]
  ): Unit = {
    if (writes.isEmpty) return
    def sameAssignments(
        left: Vector[DataAssignmentStatement],
        right: Vector[DataAssignmentStatement]
    ): Boolean =
      left.size == right.size && left.zip(right).forall { case (a, b) => a eq b }

    val first = writes.head
    val firstGuards = first.guards.sortBy(_.elementIndex)
    val oneAccessIdentity = writes.forall { write =>
      val guards = write.guards.sortBy(_.elementIndex)
      (write.address eq first.address) &&
      (write.carrierAddress eq first.carrierAddress) &&
      (write.decoderOne eq first.decoderOne) &&
      (write.decoder eq first.decoder) &&
      sameAssignments(
        write.carrierAddressAssignments,
        first.carrierAddressAssignments
      ) &&
      sameAssignments(
        write.decoderOneAssignments,
        first.decoderOneAssignments
      ) &&
      sameAssignments(write.decoderAssignments, first.decoderAssignments) &&
      guards.size == firstGuards.size &&
      guards.zip(firstGuards).forall { case (left, right) =>
        left.elementIndex == right.elementIndex &&
        (left.enable eq right.enable) &&
        sameAssignments(left.enableAssignments, right.enableAssignments)
      }
    }
    if (!oneAccessIdentity) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-GUARD-MISMATCH",
        s"Vec '${plan.name}' dynamic writes do not share one exact carrier address, decoder and guard identity set",
        writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
      )
    }
  }

  private def claimDynamicWriteSupportEvidence(
      plan: VecPlan,
      writes: Vector[ParameterizedVecDynamicWrite],
      live: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean],
      claimed: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Unit = {
    if (writes.isEmpty) return
    val first = writes.head
    val raw =
      first.carrierAddressAssignments ++
        first.decoderOneAssignments ++
        first.decoderAssignments ++
        first.guards.sortBy(_.elementIndex).flatMap(_.enableAssignments)
    val exact = raw.foldLeft(Vector.empty[DataAssignmentStatement]) {
      case (known, assignment) if known.exists(_ eq assignment) => known
      case (known, assignment)                                  => known :+ assignment
    }
    claimAssignmentEvidence(
      exact,
      live,
      claimed,
      s"claiming dynamic-write support identities of Vec '${plan.name}'",
      writes.flatMap(_.sourceLocation).headOption.orElse(plan.sourceLocation)
    )
  }

  private def proceduralBlockContaining(
      lines: Vector[String],
      lineIndex: Int,
      sourceLocation: Option[String]
  ): AlwaysBlock = {
    val owners = proceduralBlocks(lines).filter(block => lineIndex > block.start && lineIndex < block.end)
    if (owners.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-PROCEDURAL-BLOCK",
        s"dynamic Vec write statement is enclosed by ${owners.size} native procedural blocks",
        sourceLocation
      )
    }
    owners.head
  }

  private def proceduralBlocks(lines: Vector[String]): Vector[AlwaysBlock] = {
    val opener = "^\\s*always\\b.*\\bbegin\\s*$".r
    val beginWord = "\\bbegin\\b".r
    val endWord = "\\bend\\b".r
    lines.zipWithIndex.flatMap {
      case (line, start) if opener.findFirstIn(line).nonEmpty =>
        var depth = 0
        var index = start
        var end = -1
        while (index < lines.size && end < 0) {
          depth += beginWord.findAllMatchIn(lines(index)).size
          depth -= endWord.findAllMatchIn(lines(index)).size
          if (index > start && depth == 0) end = index
          index += 1
        }
        if (end < 0) None else Some(AlwaysBlock(start, end))
      case _ => None
    }
  }

  private def caseBlocks(lines: Vector[String]): Vector[CaseBlock] = {
    val casePattern = "^\\s*case\\((.*)\\)\\s*$".r
    lines.zipWithIndex.flatMap {
      case (casePattern(select), caseLine) if caseLine > 0 && lines(caseLine - 1).trim == "always @(*) begin" =>
        (caseLine + 1 until lines.size).find(index => lines(index).trim == "endcase").flatMap { endCase =>
          val end = endCase + 1
          if (end < lines.size && lines(end).trim == "end")
            Some(CaseBlock(caseLine - 1, caseLine, end, select.trim))
          else None
        }
      case _ => None
    }
  }

  private def collapseDeclaration(
      original: Vector[String],
      plan: VecPlan
  ): Vector[String] = {
    val declarations = plan.leaves.map { leaf =>
      parseDeclaration(original, leaf.name, plan.sourceLocation)
    }
    val indexes = declarations.map(_.lineIndex)
    if (indexes.distinct.size != indexes.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DECLARATION-AMBIGUOUS",
        s"multiple leaves of Vec '${plan.name}' share one native declaration line",
        plan.sourceLocation
      )
    }
    val direction = declarations.map(_.direction).distinct
    val net = declarations.map(_.net).distinct
    val syntax = declarations.map(_.syntax).distinct
    if (direction.size != 1 || net.size != 1 || syntax.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DECLARATION-KIND-MISMATCH",
        s"Vec '${plan.name}' carrier leaves do not share one direction, net kind and declaration syntax",
        plan.sourceLocation
      )
    }
    direction.head match {
      case Some(_) if !plan.leaves.forall(_.value.isIo) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-PORT-OWNERSHIP-MISMATCH",
          s"Vec '${plan.name}' mixes port and internal leaves",
          plan.sourceLocation
        )
      case None if plan.leaves.exists(_.value.isIo) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-PORT-OWNERSHIP-MISMATCH",
          s"Vec '${plan.name}' mixes port and internal leaves",
          plan.sourceLocation
        )
      case _ =>
    }
    val insertion = indexes.max
    val last = declarations.find(_.lineIndex == insertion).get
    val declaration = direction.head match {
      case Some(value) =>
        last.indentation + last.syntax + s"$value ${last.net} ${plan.range} ${plan.name}" +
          (if (last.comma) "," else "")
      case None =>
        last.indentation + last.syntax + s"${last.net} ${plan.range} ${plan.name};"
    }
    original.zipWithIndex.flatMap { case (line, index) =>
      if (index == insertion) Vector(declaration)
      else if (indexes.contains(index)) Vector.empty
      else Vector(line)
    }
  }

  private def parseDeclaration(
      lines: Vector[String],
      name: String,
      sourceLocation: Option[String]
  ): ParsedDeclaration = {
    val port =
      ("^([ \\t]*)(.*?)(input|output|inout)\\s+(wire|reg|logic)" +
        "\\s*(?:\\[[^\\]]+\\])?\\s*(" + Pattern.quote(name) + ")" +
        "\\s*(,?)\\s*(?://.*)?$").r
    val signal =
      ("^([ \\t]*)(.*?)(wire|reg|logic)\\s*" +
        "(?:\\[[^\\]]+\\])?\\s*(" + Pattern.quote(name) + ")" +
        "\\s*;\\s*(?://.*)?$").r
    val matches = lines.zipWithIndex.flatMap { case (line, index) =>
      port
        .findFirstMatchIn(line)
        .map { value =>
          ParsedDeclaration(
            index,
            value.group(1),
            value.group(2),
            Some(value.group(3)),
            value.group(4),
            value.group(6) == ",",
            value.start(5),
            value.end(5)
          )
        }
        .orElse {
          signal.findFirstMatchIn(line).map { value =>
            ParsedDeclaration(
              index,
              value.group(1),
              value.group(2),
              None,
              value.group(3),
              comma = false,
              value.start(4),
              value.end(4)
            )
          }
        }
    }
    if (matches.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-DECLARATION-NOT-FOUND",
        s"native Verilog contains ${matches.size} declarations for Vec carrier leaf '$name'",
        sourceLocation
      )
    }
    matches.head
  }

  private def rewritePackedDeclaration(
      original: Vector[String],
      name: String,
      range: String,
      sourceLocation: Option[String]
  ): Vector[String] = {
    val parsed = parseDeclaration(original, name, sourceLocation)
    val line = original(parsed.lineIndex)
    var prefix = line.substring(0, parsed.declaratorStart)
    val suffix = line.substring(parsed.declaratorEnd)
    val packed = "\\[[^\\]]+\\]\\s*$".r
    prefix = packed.findFirstMatchIn(prefix) match {
      case Some(value) => prefix.substring(0, value.start) + range + " "
      case None        => prefix + range + " "
    }
    original.updated(parsed.lineIndex, prefix + name + suffix)
  }

  private def validatePackedCarrier(
      data: Bits,
      plan: VecPlan,
      sourceLocation: Option[String]
  ): Unit = {
    val expected = BigInt(plan.shape.carrierCapacity) * plan.shape.elementWidthDefault
    if (BigInt(data.getBitsWidth) != expected) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-CARRIER-WIDTH-MISMATCH",
        s"packed carrier '${data.getName()}' has ${data.getBitsWidth} bits, expected audited Vec capacity width $expected",
        sourceLocation
      )
    }
  }

  private def validatePackedLogicalWitness(
      data: Bits,
      plan: VecPlan,
      sourceLocation: Option[String]
  ): Unit = {
    val expected = BigInt(plan.shape.witnessDepth) * plan.shape.elementWidthDefault
    if (BigInt(data.getBitsWidth) != expected) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-WITNESS-WIDTH-MISMATCH",
        s"packed logical result '${data.getName()}' has ${data.getBitsWidth} bits, expected Vec witness width $expected",
        sourceLocation
      )
    }
  }

  private def requireCompatible(
      left: VecPlan,
      right: VecPlan,
      sourceLocation: Option[String]
  ): Unit =
    requireCompatibleShapes(
      left.shape,
      left.name,
      right.shape,
      right.name,
      sourceLocation.orElse(left.sourceLocation).orElse(right.sourceLocation)
    )

  private def requireCompatibleBoundary(
      local: VecPlan,
      peer: Vec[_],
      sourceLocation: Option[String]
  ): Unit = {
    ParameterizedVec.shapeOf(peer).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-SHAPE-MISSING",
        s"hierarchy peer of Vec '${local.name}' has no retained symbolic shape",
        sourceLocation.orElse(local.sourceLocation)
      )
    }
    // The core Vec algorithm is authoritative for direct hierarchy
    // compatibility.  It admits distinct definition/actual roots only when
    // the exact child component carries an explicit typed formal binding.
    ParameterizedVec.requireCompatible(local.vector, peer)
  }

  private def requireCompatibleShapes(
      left: ParameterizedVecShape,
      leftName: String,
      right: ParameterizedVecShape,
      rightName: String,
      sourceLocation: Option[String]
  ): Unit = {
    val leavesCompatible =
      left.elementLeaves.size == right.elementLeaves.size &&
        left.elementLeaves.zip(right.elementLeaves).forall { case (l, r) =>
          l.path == r.path && (l.typeObject eq r.typeObject) &&
          ElabInt.equivalentExpression(l.width, r.width)
        }
    if (
      !ElabInt.equivalentExpression(left.depth, right.depth) ||
      left.witnessDepth != right.witnessDepth ||
      left.carrierCapacity != right.carrierCapacity ||
      !leavesCompatible
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-SHAPE-MISMATCH",
        s"packed Vecs '$leftName' and '$rightName' do not have identical logical shapes",
        sourceLocation
      )
    }
  }

  private def isDirectHierarchyBoundary(
      component: Component,
      local: Vec[_],
      peer: Vec[_]
  ): Boolean = {
    val localLeaves = vectorLeaves(local)
    val peerLeaves = vectorLeaves(peer)
    if (
      localLeaves.isEmpty || peerLeaves.isEmpty ||
      !localLeaves.forall(leaf => leaf.isIo && (leaf.component eq component)) ||
      !peerLeaves.forall(_.isIo)
    ) return false

    val peerComponents = peerLeaves.map(_.component).distinct
    peerComponents.size == 1 && peerComponents.head != null && {
      val peerComponent = peerComponents.head
      (peerComponent.parent != null && (peerComponent.parent eq component)) ||
      (component.parent != null && (component.parent eq peerComponent))
    }
  }

  /** Authorize one already-consumed child-output bridge for an internal
    * parent Vec.
    *
    * [[rewriteChildConnections]] runs before operation validation and rewrites
    * this exact finite leaf bridge into the packed instance connection. The
    * operation must therefore remain accepted after its source child Vec has
    * fallen outside the current component's publication plan, but only when
    * every retained assignment identity was claimed by that earlier rewrite.
    * Internal/child ownership, output direction and complete leaf lineage are
    * rechecked here; a compatible shape, name or unclaimed assignment cannot
    * create hierarchy authority.
    */
  private def isClaimedChildOutputBoundary(
      component: Component,
      local: Vec[_],
      peer: Vec[_],
      assignments: Vector[DataAssignmentStatement],
      claimed: IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]
  ): Boolean = {
    if (
      component == null || local == null || peer == null ||
      assignments == null || assignments.isEmpty || claimed == null ||
      (local.component ne component)
    ) return false

    val localLeaves = vectorLeaves(local)
    val peerLeaves = vectorLeaves(peer)
    if (
      localLeaves.isEmpty || peerLeaves.isEmpty ||
      !localLeaves.forall(leaf => !leaf.isIo && (leaf.component eq component)) ||
      !isExactBoundaryBridge(assignments, localLeaves, peerLeaves) ||
      !assignments.forall(claimed.containsKey)
    ) return false

    val peerComponents = peerLeaves.map(_.component).distinct
    if (peerComponents.size != 1 || peerComponents.head == null) return false
    val child = peerComponents.head
    (peer.component eq child) &&
    (child.parent eq component) &&
    component.children.count(_ eq child) == 1 &&
    peerLeaves.forall { leaf =>
      (leaf.component eq child) && leaf.isIo && leaf.isOutput &&
      !leaf.isInput && !leaf.isInOut
    }
  }

  private def vectorLeaves(vector: Vec[_]): Vector[BaseType] =
    vector.vec.flatMap(element => element.asInstanceOf[Data].flatten).toVector

  private def requiredVecName(
      vector: Vec[_],
      sourceLocation: Option[String]
  ): String = {
    val component = Option(vector.component).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-NAME-MISSING",
        "one typed Vec has no exact owning component",
        sourceLocation
      )
    }
    val allocated = allocatedVecNames(component)
    val name = allocated.collectFirst {
      case (candidate, value) if candidate eq vector => value
    }.getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-NAME-MISSING",
        "one typed Vec is absent from its exact component publication inventory",
        sourceLocation
      )
    }
    if (!PortableIdentifier.pattern.matcher(name).matches()) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-NAME-INVALID",
        s"typed Vec aggregate name '$name' is not a portable Verilog identifier",
        sourceLocation
      )
    }
    name
  }

  /** Allocate every packed aggregate spelling from one exact component
    * inventory before returning any individual name.  A Vec aggregate is a
    * publication artifact, so its preferred root spelling must never shadow
    * a different native declaration (in particular another Vec's exact
    * carrier leaf).  All identity joins remain on the retained Vec objects;
    * emitted names are allocated only after that inventory is fixed.
    *
    * Recompute in publication order instead of caching transient Components.
    * This keeps hierarchy/schema callers deterministic without introducing a
    * second weak-identity side table.
    */
  private def allocatedVecNames(
      component: Component
  ): Vector[(Vec[_], String)] = {
    val vectors = publicationVectors(component)
    val occupied = mutable.LinkedHashSet.empty[String]

    def retainName(value: DeclarationStatement): Unit =
      Option(value)
        .flatMap(declaration => Option(declaration.getName()))
        .filter(_.nonEmpty)
        .foreach(occupied += _)

    component.dslBody.walkDeclarations {
      case value: BaseType if !value.isSuffix => retainName(value)
      case value: BaseType                    => ()
      case value: DeclarationStatement        => retainName(value)
      case _                                  =>
    }
    component.getOrdredNodeIo.foreach { value =>
      if (!value.isSuffix) retainName(value)
    }
    component.children.foreach { child =>
      Option(child.getName()).filter(_.nonEmpty).foreach(occupied += _)
    }

    val retainedParameters =
      ParameterizedWidth.parametersOf(component) ++
        ExternalParameterizedAutoResize.parametersOf(component) ++
        ParameterizedMemory.parametersOf(component) ++
        ExternalParameterizedValueRegistry.parametersOf(component) ++
        ParameterizedVec.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
        ParameterizedProcess.parametersOf(component) ++
        ExternalFormalParameterRegistry.bindingsOf(component).map(_.formal)
    retainedParameters.foreach(parameter => occupied += parameter.name)
    occupied ++= structuralRegionNamesOf(component)
    ParameterizedProcess.loopsOf(component).foreach { loop =>
      occupied += loop.label
      occupied += loop.indexName
    }

    val occurrences = mutable.HashMap.empty[String, Int]
    vectors.zipWithIndex.map { case (vector, ordinal) =>
      val preferred = Option(vector.getName())
        .filter(_.nonEmpty)
        .map { base =>
          val occurrence = occurrences.getOrElse(base, 0)
          occurrences.update(base, occurrence + 1)
          if (occurrence == 0) base else s"${base}_$occurrence"
        }
        .getOrElse(s"$SyntheticAggregatePrefix${ordinal + 1}")

      val allocated =
        if (!occupied.contains(preferred)) preferred
        else {
          val fallback = s"${preferred}_morphhdl_vec"
          var candidate = fallback
          var suffix = 2
          while (occupied.contains(candidate)) {
            candidate = s"${fallback}_$suffix"
            suffix += 1
          }
          candidate
        }
      occupied += allocated
      vector -> allocated
    }
  }

  private def requiredBaseName(
      value: BaseType,
      role: String,
      sourceLocation: Option[String]
  ): String = {
    val name = Option(value).flatMap(value => Option(value.getName())).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-LEAF-NAME-MISSING",
        s"$role has no final emitted name",
        sourceLocation
      )
    }
    if (!PortableIdentifier.pattern.matcher(name).matches()) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-LEAF-NAME-INVALID",
        s"$role name '$name' is not a portable Verilog identifier",
        sourceLocation
      )
    }
    name
  }

  private def requiredDirectIdentifier(
      value: String,
      role: String,
      sourceLocation: Option[String]
  ): String = {
    val name = Option(value).map(_.trim).getOrElse("")
    if (!PortableIdentifier.pattern.matcher(name).matches()) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-IDENTITY-BRIDGE-INVALID",
        s"$role is not emitted as one direct portable identifier",
        sourceLocation
      )
    }
    name
  }

  private def render(expression: ElaborationIntegerExpression): String = {
    val value = expression.verilog.trim
    if (value.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-EXPRESSION-EMPTY",
        "typed Vec retains an empty Verilog integer expression",
        expression.sourceLocation
      )
    }
    value
  }

  private def renderSum(
      expressions: Vector[ElaborationIntegerExpression]
  ): String = {
    if (expressions.isEmpty) "0"
    else if (expressions.forall(_.parameters.isEmpty))
      expressions.foldLeft(BigInt(0))((sum, value) => sum + value.default).toString
    else expressions.map(value => factor(render(value))).mkString(" + ")
  }

  private def addTerms(left: String, right: String): String =
    if (right == "0") left
    else if (left == "0") right
    else s"$left + $right"

  private def multiplyTerms(left: String, right: String): String =
    if (left == "0" || right == "0") "0"
    else if (left == "1") right
    else if (right == "1") left
    else s"${factor(left)} * ${factor(right)}"

  private def factor(value: String): String = {
    val trimmed = value.trim
    if (trimmed.matches("[A-Za-z_][A-Za-z0-9_$]*") || trimmed.matches("-?[0-9]+")) trimmed
    else parenthesize(trimmed)
  }

  private def parenthesize(value: String): String = s"(${value.trim})"

  private def expressionSchema(
      expression: ElaborationIntegerExpression
  ): String =
    s"${expression.verilog}:${expression.default}:${expression.minimum}:${expression.maximum}:" +
      expression.parameters.sortBy(_.name).mkString(",")

  private def isSignedLeaf(shape: ParameterizedVecLeafShape): Boolean =
    shape.typeObject eq TypeSInt

  /** Stable schema spelling selected only after exact native type-object
    * identity has established the leaf kind. Runtime class names are not type
    * evidence: downstream Bits subclasses may legally have names such as
    * `SInt` without acquiring signed lowering semantics.
    */
  private def leafTypeSchema(shape: ParameterizedVecLeafShape): String = {
    val kind = shape.typeObject
    if (kind eq TypeBool) "TypeBool"
    else if (kind eq TypeBits) "TypeBits"
    else if (kind eq TypeUInt) "TypeUInt"
    else if (kind eq TypeSInt) "TypeSInt"
    else if (kind eq TypeEnum) "TypeEnum"
    else if (kind eq TypeStruct) "TypeStruct"
    else
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VEC-LEAF-TYPE-UNSUPPORTED",
        "typed Vec leaf lost an exact native type-object identity",
        shape.width.sourceLocation
      )
  }

  /** Match a signal reference without matching an instance formal label such
    * as the left side of `.formal(actual)`. Aggregate Vec carrier publication
    * may rewrite exact signal identities, never Verilog hierarchy syntax.
    */
  private def replaceReferenceIdentifier(
      lines: Vector[String],
      name: String,
      replacement: String
  ): Vector[String] = {
    mapReferenceCode(lines)(code => replaceReferencesInCode(code, name, replacement))
  }

  private def containsIdentifier(value: String, name: String): Boolean =
    identifierPattern(name).findFirstIn(value).nonEmpty

  private def containsReferenceIdentifier(value: String, name: String): Boolean =
    countReferenceIdentifier(value.split("\n", -1).toVector, name) != 0

  private def countReferenceIdentifier(
      lines: Vector[String],
      name: String
  ): Int = {
    val pattern = identifierPattern(name)
    var count = 0
    mapReferenceCode(lines) { code =>
      count += pattern.findAllMatchIn(code).count(value => isSignalReference(code, value.start, value.end))
      code
    }
    count
  }

  private def replaceReferencesInCode(
      code: String,
      name: String,
      replacement: String
  ): String = {
    val pattern = identifierPattern(name)
    val out = new StringBuilder
    var copiedUntil = 0
    pattern.findAllMatchIn(code).foreach { value =>
      if (isSignalReference(code, value.start, value.end)) {
        out.append(code.substring(copiedUntil, value.start))
        out.append(replacement)
        copiedUntil = value.end
      }
    }
    out.append(code.substring(copiedUntil))
    out.result()
  }

  /** A named-port formal is syntax, not a signal reference. Skip it even
    * when legal whitespace separates the dot and portable identifier.
    */
  private def isSignalReference(
      code: String,
      start: Int,
      end: Int
  ): Boolean = {
    var previous = start - 1
    while (previous >= 0 && code.charAt(previous).isWhitespace) previous -= 1
    if (previous >= 0 && code.charAt(previous) == '.') return false

    var wordStart = previous
    while (
      wordStart >= 0 &&
      (code.charAt(wordStart).isLetterOrDigit ||
        code.charAt(wordStart) == '_' || code.charAt(wordStart) == '$')
    ) wordStart -= 1
    val previousWord =
      if (wordStart == previous) ""
      else code.substring(wordStart + 1, previous + 1)
    if (previousWord == "module" || previousWord == "macromodule")
      return false

    val before = code.substring(0, start).trim
    val after = code.substring(end)
    val portable = "[A-Za-z_][A-Za-z0-9_$]*"
    val firstModuleType =
      before.isEmpty && (
        ("^\\s*#\\s*\\(.*$").r.findFirstIn(after).nonEmpty ||
          ("^\\s+" + portable + "\\s*(?:#\\s*\\(|\\().*$").r
            .findFirstIn(after)
            .nonEmpty
      )
    val instanceName =
      (before.matches(portable) || before.endsWith(")")) &&
        "^\\s*\\(.*$".r.findFirstIn(after).nonEmpty
    !firstModuleType && !instanceName
  }

  /** Apply an identifier transformation only to Verilog code. Quoted strings,
    * line comments and block comments are copied byte-for-byte, so a carrier
    * spelling there can neither authorize nor be changed by packed lowering.
    */
  private def mapReferenceCode(
      lines: Vector[String]
  )(transform: String => String): Vector[String] = {
    var insideBlockComment = false
    var insideAttribute = false
    var attributeQuoted = false
    var attributeEscaped = false
    lines.map { line =>
      val out = new StringBuilder
      var index = 0
      while (index < line.length) {
        if (insideAttribute) {
          if (attributeQuoted) {
            val value = line.charAt(index)
            out.append(value)
            index += 1
            if (attributeEscaped) attributeEscaped = false
            else if (value == '\\') attributeEscaped = true
            else if (value == '"') attributeQuoted = false
          } else if (line.startsWith("*)", index)) {
            out.append("*)")
            index += 2
            insideAttribute = false
          } else {
            val value = line.charAt(index)
            out.append(value)
            index += 1
            if (value == '"') {
              attributeQuoted = true
              attributeEscaped = false
            }
          }
        } else if (insideBlockComment) {
          val close = line.indexOf("*/", index)
          if (close < 0) {
            out.append(line.substring(index))
            index = line.length
          } else {
            out.append(line.substring(index, close + 2))
            index = close + 2
            insideBlockComment = false
          }
        } else if (line.startsWith("//", index)) {
          out.append(line.substring(index))
          index = line.length
        } else if (line.startsWith("/*", index)) {
          out.append("/*")
          index += 2
          insideBlockComment = true
        } else if (isAttributeOpen(line, index)) {
          out.append("(*")
          index += 2
          insideAttribute = true
          attributeQuoted = false
          attributeEscaped = false
        } else if (line.charAt(index) == '"') {
          val start = index
          index += 1
          var escaped = false
          var closed = false
          while (index < line.length && !closed) {
            val value = line.charAt(index)
            index += 1
            if (escaped) escaped = false
            else if (value == '\\') escaped = true
            else if (value == '"') closed = true
          }
          out.append(line.substring(start, index))
        } else {
          val start = index
          while (
            index < line.length &&
            line.charAt(index) != '"' &&
            !line.startsWith("//", index) &&
            !line.startsWith("/*", index) &&
            !isAttributeOpen(line, index)
          ) index += 1
          out.append(transform(line.substring(start, index)))
        }
      }
      out.result()
    }
  }

  /** IEEE-1364 uses the same adjacent `(*` tokens for an attribute opener and
    * for the wildcard event control in `always @(*)`.  The nearest preceding
    * non-whitespace `@` distinguishes the event-control form; treating it as
    * an attribute would hide all following code while waiting for a `*)` that
    * does not exist.
    */
  private def isAttributeOpen(line: String, index: Int): Boolean = {
    if (!line.startsWith("(*", index)) return false
    var previous = index - 1
    while (previous >= 0 && line.charAt(previous).isWhitespace) previous -= 1
    previous < 0 || line.charAt(previous) != '@'
  }

  private def identifierPattern(name: String) =
    ("(?<![A-Za-z0-9_$])" + Pattern.quote(name) +
      "(?![A-Za-z0-9_$])").r

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
