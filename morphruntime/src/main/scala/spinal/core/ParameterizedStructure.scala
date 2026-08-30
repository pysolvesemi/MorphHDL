package spinal.core

import java.util.IdentityHashMap

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core.internals._

/** Opaque snapshot of the ordinary SpinalHDL statements created by one
  * structural body. Frontend code can capture and register it, while the MorphHDL-owned external lowering inspects the native AST objects.
  */
final class ParameterizedStructuralBlock private[core] (
    private[core] val statements: Vector[Statement],
    private[core] val declarations: Vector[BaseType],
    private[core] val assignments: Vector[DataAssignmentStatement],
    private[core] val memories: Vector[Mem[_]],
    private[core] val children: Vector[Component],
    private[core] val slices: Vector[ParameterizedStructure.StructuralSlice],
    private[core] val vecIndices: Vector[ParameterizedStructure.StructuralVecIndex],
    private[core] val regions: Vector[ParameterizedStructure.StructuralRegion],
    private[core] val sourceLocation: Option[String]
)

/** Opaque completion guard for the mandatory otherwise/default continuation. */
final class ParameterizedStructuralPending private[core] (
    private[core] val component: Component,
    private[core] val id: Long,
    private[core] val kind: String,
    private[core] val captureId: Option[Long],
    private[core] val sourceLocation: Option[String]
)

/** MorphHDL-owned structural-capture registry retained from Increment 33.
  *
  * The normal SpinalHDL graph remains authoritative. Parameterized mode
  * elaborates one representative body, records exactly which declarations,
  * assignments and children that body added, and later relocates those module
  * items into a Verilog generate region. Ordinary non-parameterized
  * elaboration never consults this registry and may continue to unroll the
  * concrete witness.
  */
object ParameterizedStructure {
  private object StorageKey

  private final class Storage {
    val regions = ArrayBuffer.empty[StructuralRegion]
    val pending = mutable.LinkedHashMap.empty[Long, ParameterizedStructuralPending]
    val labels = mutable.LinkedHashMap.empty[String, Option[String]]
    val typedPredicateRoots = new IdentityHashMap[
      ElaborationIntegerParameterRoot,
      StructuralPredicateRoot
    ]()
    var nextPendingId = 0L
    var nextCaptureId = 0L
    var assignmentValidationScheduled = false
  }

  private final class CaptureState(
      val component: Component,
      val sourceLocation: Option[String],
      val id: Long
  ) {
    val slices = ArrayBuffer.empty[StructuralSlice]
    val vecIndices = ArrayBuffer.empty[StructuralVecIndex]
    val regions = ArrayBuffer.empty[StructuralRegion]
  }

  private[core] final case class StructuralSlice(
      source: BitVector,
      result: BitVector,
      offset: ElaborationIntegerExpression,
      width: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  )

  private[core] final case class StructuralVecIndex(
      vector: Vec[_],
      selected: Data,
      index: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  )

  private[core] final class StructuralPredicateRoot(
      val verilog: String,
      val default: BigInt,
      val minimum: BigInt,
      val maximum: BigInt,
      val parameters: Vector[ElaborationIntegerParameter],
      val elaborationRoot: Option[ElaborationIntegerParameterRoot] = None
  )

  /** Exact truth set for one compiler-proven predicate over a bounded native
    * constructor argument.  This evidence is optional and never inferred from
    * rendered Verilog text.
    */
  private[core] final case class StructuralPredicateDomain(
      root: StructuralPredicateRoot,
      universe: Set[BigInt],
      whenTrue: Set[BigInt]
  ) {
    require(root.minimum <= root.maximum)
    require(root.default >= root.minimum && root.default <= root.maximum)
    require(universe.forall(value => value >= root.minimum && value <= root.maximum))
    require(BigInt(universe.size) == root.maximum - root.minimum + 1)
    require(whenTrue.subsetOf(universe))

    def valuesFor(branch: Int): Option[Set[BigInt]] = branch match {
      case 0 => Some(whenTrue)
      case 1 => Some(universe -- whenTrue)
      case _ => None
    }
  }

  /** Exact bounded root values under which one captured assignment may exist.
    * Construction is internal and derives only from captured statement identity
    * plus compiler-proven structural predicate domains.
    */
  private[core] final case class CapturedAssignmentDomain(
      root: StructuralPredicateRoot,
      values: Set[BigInt]
  ) {
    require(root ne null)
    require(values.nonEmpty)
    require(
      BigInt(values.size) <=
        ElaborationExactDomain.MaximumDomainSize
    )
    require(values.forall(value => value >= root.minimum && value <= root.maximum))
  }

  /** Exact bounded root values admitted by the final owner of one native
    * declaration or memory. `captured` distinguishes a structural owner from
    * an ordinary module-scope declaration; both are established only by JVM
    * identity in the native AST.
    */
  private[core] final case class ExactNativeObjectDomain(
      values: Set[BigInt],
      captured: Boolean
  ) {
    require(values != null && values.nonEmpty)
    require(BigInt(values.size) <= ElaborationExactDomain.MaximumDomainSize)
  }

  /** Exact typed expression results certified for one native object's owner. */
  private[core] final case class ExactProjectedObjectEvaluation(
      rootValues: Set[BigInt],
      results: Vector[(BigInt, BigInt)],
      captured: Boolean
  ) {
    require(rootValues != null && rootValues.nonEmpty)
    require(results != null && results.map(_._1).toSet == rootValues)
  }

  private[core] sealed trait StructuralRegion {
    def blocks: Vector[ParameterizedStructuralBlock]
    def parameters: Vector[ElaborationIntegerParameter]
    def parameterRoots: Vector[ElaborationIntegerParameterRoot]
    def sourceLocation: Option[String]
  }

  private[core] final case class StructuralFor(
      label: String,
      indexName: String,
      count: ElaborationIntegerExpression,
      body: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ) extends StructuralRegion {
    override val blocks: Vector[ParameterizedStructuralBlock] = Vector(body)
    override val parameters: Vector[ElaborationIntegerParameter] = count.parameters
    override val parameterRoots: Vector[ElaborationIntegerParameterRoot] =
      count.parameterRoots
  }

  private[core] final case class StructuralIf(
      condition: ElaborationBooleanExpression,
      whenTrueLabel: String,
      whenFalseLabel: String,
      whenTrue: ParameterizedStructuralBlock,
      whenFalse: ParameterizedStructuralBlock,
      predicateDomain: Option[StructuralPredicateDomain],
      sourceLocation: Option[String]
  ) extends StructuralRegion {
    override val blocks: Vector[ParameterizedStructuralBlock] =
      Vector(whenTrue, whenFalse)
    override val parameters: Vector[ElaborationIntegerParameter] =
      condition.parameters
    override val parameterRoots: Vector[ElaborationIntegerParameterRoot] =
      condition.parameterRoots
  }

  private[core] final case class StructuralCaseChoice(
      value: BigInt,
      label: String,
      body: ParameterizedStructuralBlock
  )

  private[core] final case class StructuralCase(
      selector: ElaborationIntegerExpression,
      choices: Vector[StructuralCaseChoice],
      defaultLabel: String,
      defaultBody: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ) extends StructuralRegion {
    override val blocks: Vector[ParameterizedStructuralBlock] =
      choices.map(_.body) :+ defaultBody
    override val parameters: Vector[ElaborationIntegerParameter] =
      selector.parameters
    override val parameterRoots: Vector[ElaborationIntegerParameterRoot] =
      selector.parameterRoots
  }

  private final case class AlternativeStep(
      region: StructuralRegion,
      branch: Int
  )

  private final case class CapturedAssignment(
      statement: DataAssignmentStatement,
      path: Vector[AlternativeStep]
  )

  private final case class CapturedDeclaration(
      declaration: BaseType,
      path: Vector[AlternativeStep]
  )

  private final case class CapturedMemory(
      memory: Mem[_],
      path: Vector[AlternativeStep]
  )

  private final case class CapturedChild(
      child: Component,
      path: Vector[AlternativeStep]
  )

  private val activeCapture = new ThreadLocal[CaptureState]()

  /** True only while constructing a component for parameterized Verilog. */
  def captureEnabled: Boolean =
    (Component.current ne null) &&
      (try GlobalData.get.config.parameterizedVerilog
      catch { case _: Throwable => false })

  /** Capture one representative ordinary SpinalHDL body.
    *
    * Only declarations, data and declaration-local initialization assignments,
    * ordinary native memories, native when/switch hardware trees and child
    * Components are accepted. Arbitrary statement kinds are rejected explicitly
    * instead of silently changing Scala semantics.
    */
  def captureBlock(
      component: Component,
      sourceLocation: Option[String]
  )(body: => Unit): ParameterizedStructuralBlock = {
    if (component eq null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-COMPONENT-MISSING",
        "structural capture requires an active Component",
        sourceLocation
      )
    }
    val previousCapture = activeCapture.get()
    if ((previousCapture ne null) && (previousCapture.component ne component)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CAPTURE-COMPONENT-MISMATCH",
        "nested structural capture crossed an active Component boundary",
        sourceLocation
      )
    }

    val beforeStatements = component.dslBody.statementIterable.toVector
    val beforeHardwareStatements = allStatementsOf(component)
    val beforeChildren = component.children.toVector
    val storage = storageOf(component)
    storage.nextCaptureId += 1
    val state = new CaptureState(
      component,
      sourceLocation,
      storage.nextCaptureId
    )
    activeCapture.set(state)

    var accepted = false
    try {
      try body
      finally {
        if (previousCapture eq null) activeCapture.remove()
        else activeCapture.set(previousCapture)
      }

      val nestedBlocks =
        state.regions.toVector.flatMap(region => allBlocks(region))
      val nestedStatements = nestedBlocks.flatMap(_.statements)
      val nestedChildren = nestedBlocks.flatMap(_.children)
      val statements =
        component.dslBody.statementIterable.toVector.filterNot(value =>
          beforeStatements.exists(_ eq value) ||
            nestedStatements.exists(_ eq value)
        )
      val children =
        component.children.toVector.filterNot(value =>
          beforeChildren.exists(_ eq value) ||
            nestedChildren.exists(_ eq value)
        )

      val nestedStatementIdentities =
        new IdentityHashMap[Statement, java.lang.Boolean]()
      def recordNestedStatement(value: Statement): Unit = {
        if (!nestedStatementIdentities.containsKey(value)) {
          nestedStatementIdentities.put(value, java.lang.Boolean.TRUE)
          value match {
            case tree: TreeStatement => tree.foreachStatements(recordNestedStatement)
            case _                   =>
          }
        }
      }
      nestedBlocks.flatMap(_.statements).foreach(recordNestedStatement)

      val hardwareStatements = ArrayBuffer.empty[Statement]
      def recordHardwareStatement(value: Statement): Unit = {
        if (!nestedStatementIdentities.containsKey(value)) {
          hardwareStatements += value
          value match {
            case tree: WhenStatement   => tree.foreachStatements(recordHardwareStatement)
            case tree: SwitchStatement => tree.foreachStatements(recordHardwareStatement)
            case _                     =>
          }
        }
      }
      statements.foreach(recordHardwareStatement)

      val declarations = hardwareStatements.collect { case value: BaseType =>
        value
      }.toVector
      val assignments = hardwareStatements.collect { case value: DataAssignmentStatement =>
        value
      }.toVector
      val initializations = hardwareStatements.collect { case value: InitAssignmentStatement =>
        value
      }.toVector
      val memories = hardwareStatements.collect { case value: Mem[_] => value }.toVector
      val memoryPorts = hardwareStatements.collect { case value: MemPortStatement =>
        value
      }.toVector
      memoryPorts.find(port => !memories.exists(_ eq port.mem)).foreach { port =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FOREIGN-MEMORY-PORT-UNSUPPORTED",
          s"structural body emitted a memory port for '${Option(port.mem).flatMap(value => Option(value.getName())).getOrElse("<unnamed>")}' without declaring that memory inside the same captured block",
          sourceLocation
        )
      }
      initializations
        .find(value => !declarations.exists(_ eq value.finalTarget))
        .foreach { value =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FOREIGN-INITIALIZATION-UNSUPPORTED",
            s"structural body initialized '${Option(value.finalTarget).flatMap(target => Option(target.getName())).getOrElse("<unnamed>")}' without declaring that register inside the same captured block",
            sourceLocation
          )
        }

      val unsupported = hardwareStatements.filterNot {
        case _: BaseType                => true
        case _: DataAssignmentStatement => true
        case _: InitAssignmentStatement => true
        case _: Mem[_]                  => true
        case _: MemPortStatement        => true
        case _: WhenStatement           => true
        case _: SwitchStatement         => true
        case _                          => false
      }

      unsupported.headOption.foreach { value =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SCALA-SIDE-EFFECT-UNSUPPORTED",
          s"structural body emitted unsupported native statement '${value.getClass.getSimpleName}'; only declarations, data and declaration-local initialization assignments, native memories, native when/switch trees and child Components may be captured",
          sourceLocation
        )
      }
      children.collectFirst { case value: BlackBox => value }.foreach { value =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-BLACKBOX-UNSUPPORTED",
          s"structural body instantiated BlackBox '${value.getName()}'; Increment 33 covers ordinary Component instances",
          sourceLocation
        )
      }
      if (
        declarations.isEmpty && assignments.isEmpty && memories.isEmpty &&
        memoryPorts.isEmpty && children.isEmpty && state.slices.isEmpty &&
        state.vecIndices.isEmpty && state.regions.isEmpty
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SCALA-SIDE-EFFECT-UNSUPPORTED",
          "structural body produced no native hardware; Scala-only mutation, I/O or collection side effects cannot be lowered into a generate region",
          sourceLocation
        )
      }

      // Every source alternative must remain available to the native emitter even
      // when it is not selected by the concrete witness. Preserve declared
      // hardware and memory ports until the MorphHDL relocation pass extracts
      // them into their parameterized structural region. Native unnamed type
      // nodes remain simplifiable so literal and cast carriers are normalized at
      // their exact assignment edge.
      declarations.filterNot(_.isTypeNode).foreach { value =>
        value.setAsVital()
        value.dontSimplifyIt()
        if (value.isComb) value.noBackendCombMerge()
        if (value.isReg) value.addTag(noBackendSyncMerge)
      }
      memories.foreach(_.preventAsBlackBox())
      memoryPorts.foreach(port => port.isVital = true)

      val result = new ParameterizedStructuralBlock(
        statements,
        declarations,
        assignments,
        memories,
        children,
        state.slices.toVector,
        state.vecIndices.toVector,
        state.regions.toVector,
        sourceLocation
      )
      accepted = true
      result
    } finally {
      if (!accepted) {
        rollbackNewStatements(component, beforeHardwareStatements)
        rollbackNewChildren(component, beforeChildren)
      }
    }
  }

  /** Record one symbolic fixed-width packed slice selected at its witness. */
  def recordSlice(
      source: BitVector,
      result: BitVector,
      offset: ElaborationIntegerExpression,
      width: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  ): Unit = {
    val state = requireCapture("packed slice", sourceLocation)
    if ((source eq null) || (result eq null)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-NULL",
        "structural packed slice requires non-null source and result",
        sourceLocation
      )
    }
    if (offset.generateIndex.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-NOT-INDEXED",
        s"packed slice offset '${offset.verilog}' does not depend on the active generate index",
        sourceLocation
      )
    }
    if (
      width.default < 1 || width.minimum < 1 ||
      offset.default < 0 || offset.minimum < 0
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-DOMAIN-UNSUPPORTED",
        s"slice '${offset.verilog} +: ${width.verilog}' must retain a non-negative offset and positive width over its complete domain",
        sourceLocation
      )
    }
    val sourceWidth = BigInt(source.getBitsWidth)
    if (offset.default + width.default > sourceWidth) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-WITNESS-MISMATCH",
        s"slice witness [${offset.default}, ${offset.default + width.default - 1}] exceeds concrete source width $sourceWidth",
        sourceLocation
      )
    }
    state.slices += StructuralSlice(source, result, offset, width, sourceLocation)
  }

  /** Record one internal static Vec element selected by a generate index. */
  def recordVecIndex(
      vector: Vec[_],
      selected: Data,
      index: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  ): Unit = {
    val state = requireCapture("Vec index", sourceLocation)
    if ((vector eq null) || (selected eq null)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-NULL",
        "structural Vec access requires non-null vector and selected element",
        sourceLocation
      )
    }
    if (index.generateIndex.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-NOT-INDEXED",
        s"Vec index '${index.verilog}' does not depend on the active generate index",
        sourceLocation
      )
    }
    if (
      index.default < 0 || index.default >= vector.length ||
      index.minimum < 0 || index.maximum >= vector.length
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-DOMAIN-UNSUPPORTED",
        s"Vec index '${index.verilog}' reaches [${index.minimum}, ${index.maximum}], outside 0 until ${vector.length}",
        sourceLocation
      )
    }
    val duplicate = state.vecIndices.exists { value =>
      (value.vector eq vector) && (value.selected eq selected) &&
      ElabInt.equivalentExpression(value.index, index)
    }
    if (!duplicate)
      state.vecIndices += StructuralVecIndex(
        vector,
        selected,
        index,
        sourceLocation
      )
  }

  private def scheduleAssignmentValidation(component: Component): Unit = {
    val storage = storageOf(component)
    if (!storage.assignmentValidationScheduled) {
      storage.assignmentValidationScheduled = true
      component.addPrePopTask(() => authorizeMutuallyExclusiveAssignments(component))
    }
  }

  private def authorizeMutuallyExclusiveAssignments(
      component: Component
  ): Unit = {
    val regions = storageOption(component).toVector
      .flatMap(_.regions)
      .toVector
    val captured = capturedAssignments(regions)
    if (captured.size < 2) return

    val byTarget = new IdentityHashMap[
      BaseType,
      ArrayBuffer[CapturedAssignment]
    ]()
    captured.foreach { value =>
      val target = value.statement.finalTarget
      var entries = byTarget.get(target)
      if (entries eq null) {
        entries = ArrayBuffer.empty[CapturedAssignment]
        byTarget.put(target, entries)
      }
      entries += value
    }

    val graphAssignments = ArrayBuffer.empty[DataAssignmentStatement]
    component.dslBody.walkStatements {
      case value: DataAssignmentStatement => graphAssignments += value
      case _                              =>
    }

    val targets = byTarget.entrySet().iterator()
    while (targets.hasNext) {
      val entry = targets.next()
      val target = entry.getKey
      val entries = entry.getValue.toVector
      if (entries.size > 1) {
        val capturedStatements =
          new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
        entries.foreach(value => capturedStatements.put(value.statement, java.lang.Boolean.TRUE))
        val targetAssignments = graphAssignments
          .filter(value => value.finalTarget eq target)
          .toVector
        val completeCapture =
          targetAssignments.size == entries.size &&
            targetAssignments.forall(capturedStatements.containsKey) &&
            entries.forall(value => targetAssignments.exists(_ eq value.statement))
        val pairwiseCompatible = entries.indices.forall { left =>
          (left + 1 until entries.size).forall { right =>
            sameAlternativePath(entries(left).path, entries(right).path) ||
            mutuallyExclusive(entries(left).path, entries(right).path)
          }
        }
        val alternatives = ArrayBuffer.empty[ArrayBuffer[CapturedAssignment]]
        entries.foreach { captured =>
          alternatives
            .find(group => sameAlternativePath(group.head.path, captured.path))
            .getOrElse {
              val group = ArrayBuffer.empty[CapturedAssignment]
              alternatives += group
              group
            } += captured
        }
        val hasExclusiveAlternatives = alternatives.size > 1
        val alternativesAreLocallySafe =
          alternatives.forall(group => nativeOverlapSafe(component, target, group.toVector))
        if (
          completeCapture && hasExclusiveAlternatives &&
          pairwiseCompatible && alternativesAreLocallySafe
        ) target.allowOverride()
      }
    }
  }

  /** Replay native definite-assignment overlap rules for one structural path.
    * This proves that an allowOverride tag suppresses only false overlap between
    * parameter alternatives, never an overlap already present inside a branch.
    */
  private def nativeOverlapSafe(
      component: Component,
      target: BaseType,
      captured: Vector[CapturedAssignment]
  ): Boolean = {
    val selected =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    captured.foreach(value => selected.put(value.statement, java.lang.Boolean.TRUE))
    val seen =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    val width = target.getBitsWidth

    final case class ScopeFacts(
        definite: AssignedBits,
        touched: Boolean,
        safe: Boolean
    )

    def assignmentBits(
        statement: DataAssignmentStatement
    ): Option[(AssignedBits, Boolean)] = {
      val bits = new AssignedBits(width)
      statement.target match {
        case value: BaseType if value eq target =>
          bits.add(width - 1, 0)
          Some(bits -> true)
        case value: BitVectorAssignmentExpression if value.finalTarget eq target =>
          val range = value.getMinAssignedBits
          bits.add(range)
          Some(bits -> (range.hi == width - 1 && range.lo == 0))
        case _ => None
      }
    }

    def walk(scope: ScopeStatement): ScopeFacts = {
      val definite = new AssignedBits(width)
      var touched = false
      var safe = true

      def mergeConditional(value: ScopeFacts): Unit = {
        if (value.touched) touched = true
        safe &&= value.safe
      }

      scope.foreachStatements {
        case statement: DataAssignmentStatement if selected.containsKey(statement) =>
          seen.put(statement, java.lang.Boolean.TRUE)
          val poison = statement.source match {
            case literal: Literal => literal.hasPoison()
            case _                => false
          }
          if (!poison) {
            assignmentBits(statement) match {
              case Some((bits, fullOrigin)) =>
                if (fullOrigin && bits.isFull && touched) safe = false
                definite.add(bits)
                touched = true
              case None => safe = false
            }
          }
        case statement: WhenStatement =>
          val whenTrue = walk(statement.whenTrue)
          val whenFalse = walk(statement.whenFalse)
          mergeConditional(whenTrue)
          mergeConditional(whenFalse)
          if (whenTrue.touched && whenFalse.touched) {
            definite.add(
              whenTrue.definite.clone().intersect(whenFalse.definite)
            )
          }
        case statement: SwitchStatement =>
          val bodies =
            statement.elements.map(_.scopeStatement) ++
              Option(statement.defaultScope)
          val branches = bodies.map(walk)
          branches.foreach(mergeConditional)
          if (
            branches.nonEmpty &&
            (statement.isFullyCoveredWithoutDefault ||
              statement.defaultScope != null) &&
            branches.forall(_.touched)
          ) {
            val intersection = branches.head.definite.clone()
            branches.tail.foreach(value => intersection.intersect(value.definite))
            definite.add(intersection)
          }
        case _ =>
      }
      ScopeFacts(definite, touched, safe)
    }

    val result = walk(component.dslBody)
    result.safe && captured.forall(value => seen.containsKey(value.statement))
  }

  private def capturedAssignments(
      regions: Vector[StructuralRegion]
  ): Vector[CapturedAssignment] = {
    val values = ArrayBuffer.empty[CapturedAssignment]

    def visitBlock(
        block: ParameterizedStructuralBlock,
        path: Vector[AlternativeStep]
    ): Unit = {
      block.assignments.foreach(value => values += CapturedAssignment(value, path))
      block.regions.foreach(value => visitRegion(value, path))
    }

    def visitRegion(
        region: StructuralRegion,
        path: Vector[AlternativeStep]
    ): Unit = region match {
      case value: StructuralFor =>
        visitBlock(value.body, path)
      case value: StructuralIf =>
        visitBlock(
          value.whenTrue,
          path :+ AlternativeStep(value, branch = 0)
        )
        visitBlock(
          value.whenFalse,
          path :+ AlternativeStep(value, branch = 1)
        )
      case value: StructuralCase =>
        value.choices.zipWithIndex.foreach { case (choice, index) =>
          visitBlock(
            choice.body,
            path :+ AlternativeStep(value, branch = index)
          )
        }
        visitBlock(
          value.defaultBody,
          path :+ AlternativeStep(value, branch = value.choices.size)
        )
    }

    regions.foreach(value => visitRegion(value, Vector.empty))
    values.toVector
  }

  private def capturedDeclarations(
      regions: Vector[StructuralRegion]
  ): Vector[CapturedDeclaration] = {
    val values = ArrayBuffer.empty[CapturedDeclaration]

    def visitBlock(
        block: ParameterizedStructuralBlock,
        path: Vector[AlternativeStep]
    ): Unit = {
      block.declarations.foreach(value => values += CapturedDeclaration(value, path))
      block.regions.foreach(value => visitRegion(value, path))
    }

    def visitRegion(
        region: StructuralRegion,
        path: Vector[AlternativeStep]
    ): Unit = region match {
      case value: StructuralFor =>
        visitBlock(value.body, path)
      case value: StructuralIf =>
        visitBlock(
          value.whenTrue,
          path :+ AlternativeStep(value, branch = 0)
        )
        visitBlock(
          value.whenFalse,
          path :+ AlternativeStep(value, branch = 1)
        )
      case value: StructuralCase =>
        value.choices.zipWithIndex.foreach { case (choice, index) =>
          visitBlock(
            choice.body,
            path :+ AlternativeStep(value, branch = index)
          )
        }
        visitBlock(
          value.defaultBody,
          path :+ AlternativeStep(value, branch = value.choices.size)
        )
    }

    regions.foreach(value => visitRegion(value, Vector.empty))
    values.toVector
  }

  private def capturedMemories(
      regions: Vector[StructuralRegion]
  ): Vector[CapturedMemory] = {
    val values = ArrayBuffer.empty[CapturedMemory]

    def visitBlock(
        block: ParameterizedStructuralBlock,
        path: Vector[AlternativeStep]
    ): Unit = {
      block.memories.foreach(value => values += CapturedMemory(value, path))
      block.regions.foreach(value => visitRegion(value, path))
    }

    def visitRegion(
        region: StructuralRegion,
        path: Vector[AlternativeStep]
    ): Unit = region match {
      case value: StructuralFor =>
        visitBlock(value.body, path)
      case value: StructuralIf =>
        visitBlock(
          value.whenTrue,
          path :+ AlternativeStep(value, branch = 0)
        )
        visitBlock(
          value.whenFalse,
          path :+ AlternativeStep(value, branch = 1)
        )
      case value: StructuralCase =>
        value.choices.zipWithIndex.foreach { case (choice, index) =>
          visitBlock(
            choice.body,
            path :+ AlternativeStep(value, branch = index)
          )
        }
        visitBlock(
          value.defaultBody,
          path :+ AlternativeStep(value, branch = value.choices.size)
        )
    }

    regions.foreach(value => visitRegion(value, Vector.empty))
    values.toVector
  }

  private def capturedChildren(
      regions: Vector[StructuralRegion]
  ): Vector[CapturedChild] = {
    val values = ArrayBuffer.empty[CapturedChild]

    def visitBlock(
        block: ParameterizedStructuralBlock,
        path: Vector[AlternativeStep]
    ): Unit = {
      block.children.foreach(value => values += CapturedChild(value, path))
      block.regions.foreach(value => visitRegion(value, path))
    }

    def visitRegion(
        region: StructuralRegion,
        path: Vector[AlternativeStep]
    ): Unit = region match {
      case value: StructuralFor =>
        visitBlock(value.body, path)
      case value: StructuralIf =>
        visitBlock(
          value.whenTrue,
          path :+ AlternativeStep(value, branch = 0)
        )
        visitBlock(
          value.whenFalse,
          path :+ AlternativeStep(value, branch = 1)
        )
      case value: StructuralCase =>
        value.choices.zipWithIndex.foreach { case (choice, index) =>
          visitBlock(
            choice.body,
            path :+ AlternativeStep(value, branch = index)
          )
        }
        visitBlock(
          value.defaultBody,
          path :+ AlternativeStep(value, branch = value.choices.size)
        )
    }

    regions.foreach(value => visitRegion(value, Vector.empty))
    values.toVector
  }

  private def inactiveAtWitness(step: AlternativeStep): Boolean =
    step.region match {
      case value: StructuralIf =>
        step.branch match {
          case 0 => !value.condition.default
          case 1 => value.condition.default
          case _ => false
        }
      case value: StructuralCase if step.branch >= 0 && step.branch < value.choices.size =>
        value.choices(step.branch).value != value.selector.default
      case value: StructuralCase if step.branch == value.choices.size =>
        value.choices.exists(_.value == value.selector.default)
      case _ => false
    }

  private def validAlternativeStep(step: AlternativeStep): Boolean =
    step.region match {
      case _: StructuralIf => step.branch == 0 || step.branch == 1
      case value: StructuralCase =>
        step.branch >= 0 && step.branch <= value.choices.size
      case _ => false
    }

  private def witnessInactive(path: Vector[AlternativeStep]): Boolean =
    path.nonEmpty &&
      path.forall(validAlternativeStep) &&
      path.exists(inactiveAtWitness)

  /** Exact captured data-assignment identities that occur only below a branch
    * which the concrete elaboration witness does not select. Invalid or
    * branchless paths fail closed, as does an identity also seen on an active
    * path.
    */
  private[core] def capturedWitnessInactiveDataAssignmentsOf(
      component: Component
  ): Vector[DataAssignmentStatement] = {
    if (component eq null) return Vector.empty
    val captured = capturedAssignments(regionsOf(component))
    val active =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    captured.foreach { value =>
      if (!witnessInactive(value.path))
        active.put(value.statement, java.lang.Boolean.TRUE)
    }

    val seen =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    val values = ArrayBuffer.empty[DataAssignmentStatement]
    captured.foreach { value =>
      val statement = value.statement
      if (
        witnessInactive(value.path) &&
        !active.containsKey(statement) &&
        !seen.containsKey(statement)
      ) {
        seen.put(statement, java.lang.Boolean.TRUE)
        values += statement
      }
    }
    values.toVector
  }

  /** Resolve the exact predicate-domain intersection for one captured data
    * assignment. Every alternative step must carry compiler-proven bounded
    * evidence for one shared root. Missing, ambiguous, impossible or oversized
    * paths fail closed.
    */
  private[core] def capturedAssignmentDomainOf(
      component: Component,
      statement: DataAssignmentStatement
  ): Option[CapturedAssignmentDomain] = {
    if (component == null || statement == null) return None
    val matches = capturedAssignments(regionsOf(component)).filter(value => value.statement eq statement)
    if (matches.size != 1 || matches.head.path.isEmpty) return None

    val constrained = matches.head.path.map { step =>
      step.region match {
        case value: StructuralIf =>
          value.predicateDomain.flatMap(domain => domain.valuesFor(step.branch).map(allowed => domain -> allowed))
        case _ => None
      }
    }
    if (constrained.exists(_.isEmpty)) return None
    val domains = constrained.flatten
    if (domains.isEmpty) return None
    val root = domains.head._1.root
    val universe = domains.head._1.universe
    if (
      root == null ||
      BigInt(universe.size) >
        ElaborationExactDomain.MaximumDomainSize ||
        domains.exists { case (domain, _) =>
          (domain.root ne root) || domain.universe != universe
        }
    ) return None

    val values = domains.foldLeft(universe) { case (remaining, (_, allowed)) =>
      remaining intersect allowed
    }
    if (values.isEmpty) None
    else Some(CapturedAssignmentDomain(root, values))
  }

  /** Resolve the final structural owner of one exact native declaration over
    * one exact typed root. A declaration absent from captured blocks is treated
    * as module-scope only after its JVM identity is found in the component AST.
    */
  private[core] def exactDeclarationDomainOf(
      component: Component,
      declaration: BaseType,
      root: ElaborationIntegerParameterRoot,
      universe: Set[BigInt],
      role: String,
      sourceLocation: Option[String]
  ): ExactNativeObjectDomain = {
    if (component == null || declaration == null) {
      fail(
        "SPINAL-ELAB-PROJECTION-OBJECT-NULL",
        s"$role requires a non-null component and native declaration",
        sourceLocation
      )
    }
    val matches = capturedDeclarations(regionsOf(component)).collect {
      case value if value.declaration eq declaration => value.path
    }
    exactNativeObjectDomainOf(
      component,
      matches,
      allStatementsOf(component).exists(_ eq declaration),
      root,
      universe,
      role,
      sourceLocation
    )
  }

  /** Exact-memory counterpart of [[exactDeclarationDomainOf]]. */
  private[core] def exactMemoryDomainOf(
      component: Component,
      memory: Mem[_],
      root: ElaborationIntegerParameterRoot,
      universe: Set[BigInt],
      role: String,
      sourceLocation: Option[String]
  ): ExactNativeObjectDomain = {
    if (component == null || memory == null) {
      fail(
        "SPINAL-ELAB-PROJECTION-OBJECT-NULL",
        s"$role requires a non-null component and native memory",
        sourceLocation
      )
    }
    val matches = capturedMemories(regionsOf(component)).collect {
      case value if value.memory eq memory => value.path
    }
    exactNativeObjectDomainOf(
      component,
      matches,
      allStatementsOf(component).exists(_ eq memory),
      root,
      universe,
      role,
      sourceLocation
    )
  }

  /** Exact-child counterpart of [[exactDeclarationDomainOf]]. */
  private[core] def exactChildDomainOf(
      parent: Component,
      child: Component,
      root: ElaborationIntegerParameterRoot,
      universe: Set[BigInt],
      role: String,
      sourceLocation: Option[String]
  ): ExactNativeObjectDomain = {
    if (parent == null || child == null) {
      fail(
        "SPINAL-ELAB-PROJECTION-OBJECT-NULL",
        s"$role requires a non-null parent and native child component",
        sourceLocation
      )
    }
    val matches = capturedChildren(regionsOf(parent)).collect {
      case value if value.child eq child => value.path
    }
    exactNativeObjectDomainOf(
      parent,
      matches,
      parent.children.exists(_ eq child),
      root,
      universe,
      role,
      sourceLocation
    )
  }

  /** Validate one projected integer expression against the exact structural
    * owner of a native declaration. Expressions without exact typed evidence
    * are outside this validator and return `None`.
    */
  private[core] def projectedDeclarationEvaluationOf(
      component: Component,
      declaration: BaseType,
      expression: ElaborationIntegerExpression,
      role: String,
      sourceLocation: Option[String]
  ): Option[ExactProjectedObjectEvaluation] =
    projectedObjectEvaluationOf(
      expression,
      role,
      sourceLocation,
      (root, universe) =>
        exactDeclarationDomainOf(
          component,
          declaration,
          root,
          universe,
          role,
          sourceLocation
        )
    )

  /** Exact-memory counterpart of [[projectedDeclarationEvaluationOf]]. */
  private[core] def projectedMemoryEvaluationOf(
      component: Component,
      memory: Mem[_],
      expression: ElaborationIntegerExpression,
      role: String,
      sourceLocation: Option[String]
  ): Option[ExactProjectedObjectEvaluation] =
    projectedObjectEvaluationOf(
      expression,
      role,
      sourceLocation,
      (root, universe) =>
        exactMemoryDomainOf(
          component,
          memory,
          root,
          universe,
          role,
          sourceLocation
        )
    )

  /** Exact-child actual counterpart of [[projectedDeclarationEvaluationOf]]. */
  private[core] def projectedChildEvaluationOf(
      parent: Component,
      child: Component,
      expression: ElaborationIntegerExpression,
      role: String,
      sourceLocation: Option[String]
  ): Option[ExactProjectedObjectEvaluation] =
    projectedObjectEvaluationOf(
      expression,
      role,
      sourceLocation,
      (root, universe) =>
        exactChildDomainOf(
          parent,
          child,
          root,
          universe,
          role,
          sourceLocation
        )
    )

  private def projectedObjectEvaluationOf(
      expression: ElaborationIntegerExpression,
      role: String,
      sourceLocation: Option[String],
      ownerDomain: (
          ElaborationIntegerParameterRoot,
          Set[BigInt]
      ) => ExactNativeObjectDomain
  ): Option[ExactProjectedObjectEvaluation] = {
    if (expression == null) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-NULL",
        s"$role requires a non-null retained expression",
        sourceLocation
      )
    }
    expression.exactDomain.map { domain =>
      val projection = expression.projectionProvenance.getOrElse {
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-IDENTITY-MISSING",
          s"$role expression '${expression.verilog}' has exact evidence but no projection provenance on this exact expression object",
          sourceLocation.orElse(expression.sourceLocation)
        )
      }
      if (projection.root ne domain.root) {
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-ROOT-IDENTITY-MISMATCH",
          s"$role expression '${expression.verilog}' projection and exact evidence have different root identities",
          sourceLocation.orElse(expression.sourceLocation)
        )
      }
      if (!projection.admitted.subsetOf(domain.evidenceValues)) {
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-OUTSIDE-EVIDENCE",
          s"$role expression '${expression.verilog}' projection admits values without exact evaluation evidence",
          sourceLocation.orElse(expression.sourceLocation)
        )
      }
      val expectedRepresentative =
        if (projection.admitted.contains(domain.parameter.default))
          domain.parameter.default
        else projection.admitted.min
      if (projection.representative != expectedRepresentative) {
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-REPRESENTATIVE-INVALID",
          s"$role expression '${expression.verilog}' has non-deterministic projection representative ${projection.representative}",
          sourceLocation.orElse(expression.sourceLocation)
        )
      }

      val owner = ownerDomain(domain.root, domain.universe)
      if (!owner.values.subsetOf(projection.admitted)) {
        val escaped = owner.values -- projection.admitted
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-SCOPE-MISMATCH",
          s"$role expression '${expression.verilog}' was projected for root values ${projection.admitted.toVector.sorted
              .mkString(", ")}, but its exact native owner also exists for ${escaped.toVector.sorted.mkString(", ")}",
          sourceLocation.orElse(expression.sourceLocation)
        )
      }
      val ownerRepresentative =
        if (owner.values.contains(domain.parameter.default))
          domain.parameter.default
        else owner.values.min
      val ownerDefault = domain.evaluate(ownerRepresentative).getOrElse {
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-INCOMPLETE",
          s"$role expression '${expression.verilog}' has no exact evaluation at final-owner representative ${domain.root.name}=$ownerRepresentative",
          sourceLocation.orElse(expression.sourceLocation)
        )
      }
      if (expression.default != ownerDefault) {
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-REPRESENTATIVE-MISMATCH",
          s"$role expression '${expression.verilog}' was constructed with default ${expression.default}, but its final native owner requires representative ${domain.root.name}=$ownerRepresentative and default $ownerDefault",
          sourceLocation.orElse(expression.sourceLocation)
        )
      }
      val results = owner.values.toVector.sorted.map { rootValue =>
        val result = domain.evaluate(rootValue).getOrElse {
          fail(
            "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-INCOMPLETE",
            s"$role expression '${expression.verilog}' has no exact evaluation at ${domain.root.name}=$rootValue",
            sourceLocation.orElse(expression.sourceLocation)
          )
        }
        if (result < expression.minimum || result > expression.maximum) {
          fail(
            "SPINAL-ELAB-DOMAIN-PROJECTION-BOUNDS-MISMATCH",
            s"$role expression '${expression.verilog}' evaluates to $result at ${domain.root.name}=$rootValue, outside retained bounds [${expression.minimum}, ${expression.maximum}]",
            sourceLocation.orElse(expression.sourceLocation)
          )
        }
        rootValue -> result
      }
      val projectedDefault = domain.evaluate(projection.representative).getOrElse {
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-INCOMPLETE",
          s"$role expression '${expression.verilog}' has no exact evaluation at representative ${domain.root.name}=${projection.representative}",
          sourceLocation.orElse(expression.sourceLocation)
        )
      }
      if (expression.default != projectedDefault) {
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-DEFAULT-MISMATCH",
          s"$role expression '${expression.verilog}' retains default ${expression.default}, but its exact projection representative evaluates to $projectedDefault",
          sourceLocation.orElse(expression.sourceLocation)
        )
      }
      ExactProjectedObjectEvaluation(owner.values, results, owner.captured)
    }
  }

  private def exactNativeObjectDomainOf(
      component: Component,
      matches: Vector[Vector[AlternativeStep]],
      presentInComponent: Boolean,
      root: ElaborationIntegerParameterRoot,
      universe: Set[BigInt],
      role: String,
      sourceLocation: Option[String]
  ): ExactNativeObjectDomain = {
    if (component == null || root == null || universe == null) {
      fail(
        "SPINAL-ELAB-PROJECTION-OWNERSHIP-NULL",
        s"$role requires a non-null component, exact root and universe",
        sourceLocation
      )
    }
    if (
      universe.isEmpty ||
      BigInt(universe.size) > ElaborationExactDomain.MaximumDomainSize
    ) {
      fail(
        "SPINAL-ELAB-PROJECTION-UNIVERSE-INVALID",
        s"$role exact root '${root.name}' has an empty or oversized universe",
        sourceLocation.orElse(root.sourceLocation)
      )
    }
    if (matches.size > 1) {
      fail(
        "SPINAL-ELAB-PROJECTION-OBJECT-OWNERSHIP-AMBIGUOUS",
        s"$role exact native object is captured by ${matches.size} structural paths",
        sourceLocation
      )
    }
    if (matches.isEmpty) {
      if (!presentInComponent) {
        fail(
          "SPINAL-ELAB-PROJECTION-OBJECT-NOT-OWNED",
          s"$role exact native object is not owned by the component AST",
          sourceLocation
        )
      }
      return ExactNativeObjectDomain(universe, captured = false)
    }

    val values = matches.head.foldLeft(universe) { (remaining, step) =>
      step.region match {
        case value: StructuralIf =>
          val domain = value.predicateDomain.getOrElse {
            fail(
              "SPINAL-ELAB-PROJECTION-STRUCTURAL-DOMAIN-UNPROVEN",
              s"$role is captured below a structural conditional without exact typed predicate evidence",
              sourceLocation.orElse(value.sourceLocation)
            )
          }
          val predicateRoot = domain.root.elaborationRoot.getOrElse {
            fail(
              "SPINAL-ELAB-PROJECTION-STRUCTURAL-DOMAIN-UNPROVEN",
              s"$role is captured below a structural conditional whose root has no exact typed identity",
              sourceLocation.orElse(value.sourceLocation)
            )
          }
          val allowed = domain.valuesFor(step.branch).getOrElse {
            fail(
              "SPINAL-ELAB-PROJECTION-STRUCTURAL-DOMAIN-UNPROVEN",
              s"$role is captured below an invalid structural alternative",
              sourceLocation.orElse(value.sourceLocation)
            )
          }
          if (allowed.isEmpty) {
            fail(
              "SPINAL-ELAB-PROJECTION-STRUCTURAL-DOMAIN-EMPTY",
              s"$role is captured below an impossible typed structural alternative",
              sourceLocation.orElse(value.sourceLocation)
            )
          }
          if (predicateRoot eq root) {
            if (domain.universe != universe) {
              fail(
                "SPINAL-ELAB-PROJECTION-ROOT-DOMAIN-MISMATCH",
                s"$role structural root '${root.name}' has a universe different from its retained exact evidence",
                sourceLocation.orElse(value.sourceLocation)
              )
            }
            remaining intersect allowed
          } else remaining

        case value: StructuralCase =>
          fail(
            "SPINAL-ELAB-PROJECTION-STRUCTURAL-DOMAIN-UNPROVEN",
            s"$role is captured below a structural case without exact per-alternative root evidence",
            sourceLocation.orElse(value.sourceLocation)
          )

        case value =>
          fail(
            "SPINAL-ELAB-PROJECTION-STRUCTURAL-DOMAIN-UNPROVEN",
            s"$role is captured below unsupported structural region '${value.getClass.getSimpleName}'",
            sourceLocation.orElse(value.sourceLocation)
          )
      }
    }
    if (values.isEmpty) {
      fail(
        "SPINAL-ELAB-PROJECTION-STRUCTURAL-DOMAIN-EMPTY",
        s"$role exact structural owner admits no value of root '${root.name}'",
        sourceLocation.orElse(root.sourceLocation)
      )
    }
    ExactNativeObjectDomain(values, captured = true)
  }

  private def mutuallyExclusive(
      left: Vector[AlternativeStep],
      right: Vector[AlternativeStep]
  ): Boolean = mutuallyExclusiveAlternatives(
    left.map(value => value.region -> value.branch),
    right.map(value => value.region -> value.branch)
  )

  private def sameAlternativePath(
      left: Vector[AlternativeStep],
      right: Vector[AlternativeStep]
  ): Boolean =
    left.size == right.size && left.zip(right).forall { case (leftStep, rightStep) =>
      (leftStep.region eq rightStep.region) &&
      leftStep.branch == rightStep.branch
    }

  /** Shared fail-closed exclusivity proof used by graph validation and RTL relocation. */
  private[core] def mutuallyExclusiveAlternatives(
      left: Vector[(StructuralRegion, Int)],
      right: Vector[(StructuralRegion, Int)]
  ): Boolean = {
    val siblingExclusive = left.exists { case (leftRegion, leftBranch) =>
      right.exists { case (rightRegion, rightBranch) =>
        (leftRegion eq rightRegion) && leftBranch != rightBranch
      }
    }
    if (siblingExclusive) return true

    def constrainedDomains(
        path: Vector[(StructuralRegion, Int)]
    ): (Boolean, Map[StructuralPredicateRoot, Set[BigInt]]) = {
      val domains = mutable.LinkedHashMap.empty[
        StructuralPredicateRoot,
        Set[BigInt]
      ]
      var impossible = false
      path.foreach {
        case (value: StructuralIf, branch) =>
          value.predicateDomain.foreach { domain =>
            domain.valuesFor(branch).foreach { allowed =>
              val constrained = domains
                .get(domain.root)
                .map(_ intersect allowed)
                .getOrElse(allowed)
              domains(domain.root) = constrained
              if (constrained.isEmpty) impossible = true
            }
          }
        case _ =>
      }
      impossible -> domains.toMap
    }

    val (leftImpossible, leftDomains) = constrainedDomains(left)
    val (rightImpossible, rightDomains) = constrainedDomains(right)
    leftImpossible || rightImpossible || leftDomains.exists { case (root, leftValues) =>
      rightDomains.get(root).exists(rightValues => (leftValues intersect rightValues).isEmpty)
    }
  }

  def registerFor(
      component: Component,
      label: String,
      indexName: String,
      count: ElaborationIntegerExpression,
      body: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ): Unit = {
    ElabInt.validateExpression(count, "generate count")
    val normalizedCount = ElabInt.withCompleteParameterRoots(count)
    val storage = storageOf(component)
    reserveName(storage, label, "generate label", sourceLocation)
    reserveName(storage, indexName, "generate index", sourceLocation)
    validateIntegerExpression(normalizedCount, "generate count")
    if (normalizedCount.default < 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-COUNT-NONPOSITIVE",
        s"generate count '${normalizedCount.verilog}' has non-positive concrete witness ${normalizedCount.default}",
        sourceLocation
      )
    }
    if (
      normalizedCount.minimum < 0 ||
      normalizedCount.maximum > BigInt(Int.MaxValue)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-COUNT-DOMAIN-UNSUPPORTED",
        s"generate count '${normalizedCount.verilog}' reaches [${normalizedCount.minimum}, ${normalizedCount.maximum}], outside the supported non-negative Int-sized domain",
        sourceLocation
      )
    }
    registerRegion(
      component,
      currentCaptureId(component, sourceLocation),
      StructuralFor(
        label,
        indexName,
        normalizedCount,
        body,
        sourceLocation
      ),
      sourceLocation
    )
  }

  def beginPending(
      component: Component,
      kind: String,
      sourceLocation: Option[String]
  ): ParameterizedStructuralPending = {
    val storage = storageOf(component)
    storage.nextPendingId += 1
    val token = new ParameterizedStructuralPending(
      component,
      storage.nextPendingId,
      kind,
      currentCaptureId(component, sourceLocation),
      sourceLocation
    )
    storage.pending(token.id) = token
    token
  }

  /** Build exact typed predicate evidence and reuse one structural root for the
    * exact declaration identity throughout a component.
    */
  private[core] def typedPredicateDomainOf(
      component: Component,
      condition: ElaborationBooleanExpression
  ): StructuralPredicateDomain = {
    if (component == null || condition == null) {
      fail(
        "SPINAL-ELAB-CONTROL-PREDICATE-DOMAIN-NULL",
        "typed predicate-domain construction requires a component and condition",
        Option(condition).flatMap(_.sourceLocation)
      )
    }
    ElabInt.validateExpression(condition, "typed structural predicate")
    val exact = condition.exactDomain.getOrElse {
      fail(
        "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING",
        s"typed structural predicate '${condition.verilog}' lacks exact single-root evidence",
        condition.sourceLocation
      )
    }
    val storage = storageOf(component)
    var structuralRoot = storage.typedPredicateRoots.get(exact.root)
    if (structuralRoot == null) {
      structuralRoot = new StructuralPredicateRoot(
        exact.parameter.name,
        exact.parameter.default,
        exact.parameter.minimum,
        exact.parameter.maximum,
        Vector(exact.parameter),
        Some(exact.root)
      )
      storage.typedPredicateRoots.put(exact.root, structuralRoot)
    } else if (
      structuralRoot.default != exact.parameter.default ||
      structuralRoot.minimum != exact.parameter.minimum ||
      structuralRoot.maximum != exact.parameter.maximum ||
      structuralRoot.parameters != Vector(exact.parameter) ||
      !structuralRoot.elaborationRoot.exists(_ eq exact.root)
    ) {
      fail(
        "SPINAL-ELAB-DOMAIN-ROOT-SCHEMA-MISMATCH",
        s"typed structural root '${exact.parameter.name}' was reused with an incompatible schema",
        condition.sourceLocation.orElse(exact.root.sourceLocation)
      )
    }
    StructuralPredicateDomain(
      root = structuralRoot,
      universe = exact.universe,
      whenTrue = exact.evaluations.collect { case (rootValue, true) =>
        rootValue
      }.toSet
    )
  }

  def registerIf(
      pending: ParameterizedStructuralPending,
      condition: ElaborationBooleanExpression,
      whenTrueLabel: String,
      whenFalseLabel: String,
      whenTrue: ParameterizedStructuralBlock,
      whenFalse: ParameterizedStructuralBlock,
      sourceLocation: Option[String],
      predicateDomain: Option[StructuralPredicateDomain] = None
  ): Unit = {
    ElabInt.validateExpression(condition, "generate-if condition")
    val normalizedCondition = ElabInt.withCompleteParameterRoots(condition)
    val storage = storageOf(pending.component)
    requirePending(storage, pending)
    reserveName(storage, whenTrueLabel, "generate-if true label", sourceLocation)
    reserveName(storage, whenFalseLabel, "generate-if false label", sourceLocation)
    ElabInt.validateExpression(normalizedCondition, "generate-if condition")
    validateParameters(normalizedCondition.parameters, sourceLocation)
    validateParameterRoots(
      normalizedCondition.parameters,
      normalizedCondition.parameterRoots,
      sourceLocation,
      "generate-if condition"
    )
    registerRegion(
      pending.component,
      pending.captureId,
      StructuralIf(
        normalizedCondition,
        whenTrueLabel,
        whenFalseLabel,
        whenTrue,
        whenFalse,
        predicateDomain,
        sourceLocation
      ),
      sourceLocation
    )
    storage.pending.remove(pending.id)
  }

  def registerCase(
      pending: ParameterizedStructuralPending,
      selector: ElaborationIntegerExpression,
      choices: Vector[(BigInt, String, ParameterizedStructuralBlock)],
      defaultLabel: String,
      defaultBody: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ): Unit = {
    ElabInt.validateExpression(selector, "generate-case selector")
    val normalizedSelector = ElabInt.withCompleteParameterRoots(selector)
    val storage = storageOf(pending.component)
    requirePending(storage, pending)
    validateIntegerExpression(normalizedSelector, "generate-case selector")
    if (choices.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CASE-EMPTY",
        "generate-case requires at least one literal choice before default",
        sourceLocation
      )
    }
    choices
      .groupBy(_._1)
      .collectFirst {
        case (value, entries) if entries.size != 1 => value
      }
      .foreach { value =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CASE-CHOICE-DUPLICATE",
          s"generate-case contains duplicate literal choice $value",
          sourceLocation
        )
      }
    choices.foreach { case (_, label, _) =>
      reserveName(storage, label, "generate-case choice label", sourceLocation)
    }
    reserveName(storage, defaultLabel, "generate-case default label", sourceLocation)
    registerRegion(
      pending.component,
      pending.captureId,
      StructuralCase(
        normalizedSelector,
        choices.map { case (value, label, body) =>
          StructuralCaseChoice(value, label, body)
        },
        defaultLabel,
        defaultBody,
        sourceLocation
      ),
      sourceLocation
    )
    storage.pending.remove(pending.id)
  }

  /** Public structural parameter inventory for MorphVerilog reports. */
  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val regions = storageOption(component).toVector
      .flatMap(_.regions)
    val values = regions
      .flatMap(region => regionParameters(region))
    val roots = regions
      .flatMap(region => regionParameterRoots(region))
    validateParameterRoots(
      values,
      roots,
      None,
      s"component '${component.definitionName}' structural regions"
    )
    validateParameterVector(values, None)
  }

  private[core] def regionsOf(component: Component): Vector[StructuralRegion] = {
    val storage = storageOption(component)
    storage.foreach { value =>
      value.pending.headOption.foreach { case (_, pending) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUATION-MISSING",
          s"${pending.kind} capture is missing its mandatory otherwise/default continuation",
          pending.sourceLocation
        )
      }
    }
    storage.toVector.flatMap(_.regions).toVector
  }

  /** Width evidence for a hierarchy boundary that uses a recorded symbolic
    * slice. Normalization may wrap the recorded access, so descendants are
    * searched by identity and must agree on one expression.
    */
  private[core] def structuralWidthOf(
      component: Component,
      expression: Expression
  ): Option[ElaborationIntegerExpression] = {
    val slices = regionsOf(component)
      .flatMap(region => allBlocks(region))
      .flatMap(_.slices)
    val found = ArrayBuffer.empty[ElaborationIntegerExpression]
    val visited = new IdentityHashMap[Expression, java.lang.Boolean]()

    def visit(value: Expression): Unit = {
      if ((value ne null) && !visited.containsKey(value)) {
        visited.put(value, java.lang.Boolean.TRUE)
        slices.foreach { slice =>
          if (slice.result eq value) found += slice.width
        }
        value.foreachExpression(visit)
      }
    }
    visit(expression)
    val distinct = found.toVector.foldLeft(
      Vector.empty[ElaborationIntegerExpression]
    ) {
      case (known, value) if known.exists(ElabInt.equivalentExpression(_, value)) =>
        known
      case (known, value) => known :+ value
    }
    distinct match {
      case Vector()      => None
      case Vector(value) => Some(value)
      case _ =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-WIDTH-CONFLICT",
          "one hierarchy expression references multiple incompatible structural slice widths"
        )
    }
  }

  private[core] def isStructuralSlice(
      component: Component,
      expression: Expression
  ): Boolean = structuralWidthOf(component, expression).nonEmpty

  private[core] def allBlocks(
      region: StructuralRegion
  ): Vector[ParameterizedStructuralBlock] =
    region.blocks.flatMap { block =>
      block +: block.regions.flatMap(nested => allBlocks(nested))
    }

  private def regionParameters(
      region: StructuralRegion
  ): Vector[ElaborationIntegerParameter] =
    region.parameters ++ region.blocks
      .flatMap(_.regions)
      .flatMap(nested => regionParameters(nested))

  private def regionParameterRoots(
      region: StructuralRegion
  ): Vector[ElaborationIntegerParameterRoot] =
    region.parameterRoots ++ region.blocks
      .flatMap(_.regions)
      .flatMap(nested => regionParameterRoots(nested))

  private def currentCaptureId(
      component: Component,
      sourceLocation: Option[String]
  ): Option[Long] = {
    val capture = activeCapture.get()
    if ((capture ne null) && (capture.component ne component)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CAPTURE-COMPONENT-MISMATCH",
        "structural region registration crossed an active Component boundary",
        sourceLocation
      )
    }
    Option(capture).map(_.id)
  }

  private def registerRegion(
      component: Component,
      expectedCaptureId: Option[Long],
      region: StructuralRegion,
      sourceLocation: Option[String]
  ): Unit = {
    scheduleAssignmentValidation(component)
    val capture = activeCapture.get()
    val actualCaptureId = Option(capture).map(_.id)
    if (actualCaptureId != expectedCaptureId) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CAPTURE-CONTEXT-MISMATCH",
        s"structural region expected capture ${expectedCaptureId
            .getOrElse("root")} but active capture is ${actualCaptureId.getOrElse("root")}",
        sourceLocation
      )
    }
    if (capture eq null) storageOf(component).regions += region
    else {
      if (capture.component ne component) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CAPTURE-COMPONENT-MISMATCH",
          "nested structural region belongs to a different Component",
          sourceLocation
        )
      }
      capture.regions += region
    }
  }

  private def requireCapture(
      operation: String,
      sourceLocation: Option[String]
  ): CaptureState = {
    val state = activeCapture.get()
    if (state eq null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONSUMER-OUTSIDE-CAPTURE",
        s"$operation is valid only inside a parameterized structural generate body",
        sourceLocation
      )
    }
    state
  }

  private def storageOf(component: Component): Storage =
    component.userCache
      .getOrElseUpdate(StorageKey, new Storage)
      .asInstanceOf[Storage]

  private def storageOption(component: Component): Option[Storage] =
    component.userCache.get(StorageKey).map(_.asInstanceOf[Storage])

  private def reserveName(
      storage: Storage,
      value: String,
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    val portable = "[A-Za-z_][A-Za-z0-9_]*".r
    if (value == null || !portable.pattern.matcher(value).matches()) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-NAME-INVALID",
        s"$role '$value' is not a portable Verilog identifier",
        sourceLocation
      )
    }
    storage.labels.get(value).foreach { previous =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-NAME-DUPLICATE",
        s"$role '$value' duplicates another structural name${previous.map(" at " + _).getOrElse("")}",
        sourceLocation
      )
    }
    storage.labels(value) = sourceLocation
  }

  private def requirePending(
      storage: Storage,
      pending: ParameterizedStructuralPending
  ): Unit =
    if (!storage.pending.get(pending.id).exists(_ eq pending)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUATION-STALE",
        s"${pending.kind} continuation is stale or was already completed",
        pending.sourceLocation
      )
    }

  private def validateIntegerExpression(
      expression: ElaborationIntegerExpression,
      role: String
  ): Unit = {
    if ((expression eq null) || expression.verilog == null || expression.verilog.trim.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-EXPRESSION-INVALID",
        s"$role has no portable Verilog expression",
        Option(expression).flatMap(_.sourceLocation)
      )
    }
    if (
      expression.minimum > expression.maximum ||
      expression.default < expression.minimum ||
      expression.default > expression.maximum
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-EXPRESSION-DOMAIN-INVALID",
        s"$role '${expression.verilog}' has default ${expression.default} outside [${expression.minimum}, ${expression.maximum}]",
        expression.sourceLocation
      )
    }
    validateParameters(expression.parameters, expression.sourceLocation)
    validateParameterRoots(
      expression.parameters,
      expression.parameterRoots,
      expression.sourceLocation,
      role
    )
  }

  private def validateParameterRoots(
      parameters: Vector[ElaborationIntegerParameter],
      roots: Vector[ElaborationIntegerParameterRoot],
      sourceLocation: Option[String],
      role: String
  ): Unit = {
    roots.foreach { root =>
      if (root == null) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-ROOT-NULL",
          s"$role carries a null parameter root",
          sourceLocation
        )
      }
      if (!parameters.exists(_.name == root.name)) {
        fail(
          "SPINAL-ELAB-INT-PARAMETER-ROOT-UNKNOWN",
          s"$role carries provenance for unknown parameter '${root.name}'",
          root.sourceLocation.orElse(sourceLocation)
        )
      }
    }
    val distinct = roots.foldLeft(Vector.empty[ElaborationIntegerParameterRoot]) {
      case (known, root) if known.exists(_ eq root) => known
      case (known, root)                            => known :+ root
    }
    distinct
      .groupBy(_.name)
      .collectFirst {
        case (name, declarations) if declarations.size > 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
          s"$role combines independently sourced declarations for parameter '$name'",
          distinct.find(_.name == name).flatMap(_.sourceLocation).orElse(sourceLocation)
        )
      }
  }

  private def validateParameters(
      parameters: Vector[ElaborationIntegerParameter],
      sourceLocation: Option[String]
  ): Unit = {
    validateParameterVector(parameters, sourceLocation)
    ()
  }

  private def validateParameterVector(
      parameters: Vector[ElaborationIntegerParameter],
      sourceLocation: Option[String]
  ): Vector[ElaborationIntegerParameter] = {
    val portable = "[A-Za-z_][A-Za-z0-9_]*".r
    parameters.foreach { parameter =>
      if (
        parameter.name == null ||
        !portable.pattern.matcher(parameter.name).matches()
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PARAMETER-NAME-INVALID",
          s"structural parameter name '${parameter.name}' is not portable",
          sourceLocation
        )
      }
      if (
        parameter.minimum > parameter.maximum ||
        parameter.default < parameter.minimum ||
        parameter.default > parameter.maximum
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PARAMETER-DOMAIN-INVALID",
          s"structural parameter '${parameter.name}' default ${parameter.default} is outside [${parameter.minimum}, ${parameter.maximum}]",
          sourceLocation
        )
      }
    }
    val grouped = parameters.groupBy(_.name)
    grouped
      .collectFirst {
        case (name, values) if values.distinct.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
          s"structural parameter '$name' has conflicting declarations",
          sourceLocation
        )
      }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private def rollbackNewStatements(
      component: Component,
      before: Vector[Statement]
  ): Unit = {
    val retained = new IdentityHashMap[Statement, java.lang.Boolean]()
    before.foreach(value => retained.put(value, java.lang.Boolean.TRUE))
    allStatementsOf(component)
      .filterNot(retained.containsKey)
      .reverse
      .foreach { value =>
        if (value.parentScope ne null) value.removeStatement()
      }
  }

  private def allStatementsOf(component: Component): Vector[Statement] = {
    val values = ArrayBuffer.empty[Statement]
    component.dslBody.walkStatements(value => values += value)
    values.toVector
  }

  private def rollbackNewChildren(
      component: Component,
      before: Vector[Component]
  ): Unit = {
    val retained = component.children.filter(value => before.exists(_ eq value))
    component.children.clear()
    component.children ++= retained
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
