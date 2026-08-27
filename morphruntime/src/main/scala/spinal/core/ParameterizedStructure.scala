package spinal.core

import java.util.IdentityHashMap

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core.internals._

/**
  * Opaque snapshot of the ordinary SpinalHDL statements created by one
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

/**
  * MorphHDL-owned structural-capture registry retained from Increment 33.
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

  /**
    * Capture-local identity for the canonical native constructor argument used
    * by structural predicate proofs.  Identity equality is intentional: equal
    * rendered expressions from independent captures are not interchangeable.
    */
  private[core] final class StructuralPredicateRoot(
      val verilog: String,
      val default: BigInt,
      val minimum: BigInt,
      val maximum: BigInt,
      val parameters: Vector[ElaborationIntegerParameter]
  ) {
    require(minimum <= maximum)
    require(default >= minimum && default <= maximum)
  }

  /** One exact, closed portion of a canonical constructor-argument domain. */
  private[core] final case class StructuralPredicateInterval(
      minimum: BigInt,
      maximum: BigInt
  ) {
    require(minimum <= maximum)
  }

  /**
    * Exact truth domain for one compiler-proven predicate.  Closed intervals
    * retain large canonical domains without enumerating their members.
    */
  private[core] final case class StructuralPredicateDomain(
      root: StructuralPredicateRoot,
      whenTrue: Vector[StructuralPredicateInterval]
  ) {
    require(whenTrue == normalizePredicateIntervals(whenTrue))
    require(
      whenTrue.forall(interval =>
        interval.minimum >= root.minimum && interval.maximum <= root.maximum
      )
    )

    def valuesFor(branch: Int): Option[Vector[StructuralPredicateInterval]] =
      branch match {
        case 0 => Some(whenTrue)
        case 1 => Some(complementPredicateIntervals(root, whenTrue))
        case _ => None
      }
  }

  private[core] sealed trait StructuralRegion {
    def blocks: Vector[ParameterizedStructuralBlock]
    def parameters: Vector[ElaborationIntegerParameter]
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
  }

  private final case class AlternativeStep(
      region: StructuralRegion,
      branch: Int
  )

  private final case class CapturedAssignment(
      statement: DataAssignmentStatement,
      path: Vector[AlternativeStep]
  )

  private final case class StatementScopeSnapshot(
      scope: ScopeStatement,
      parentStatement: TreeStatement,
      component: Component,
      statements: Vector[Statement]
  )

  private final case class StatementGraphSnapshot(
      rootStatements: Vector[Statement],
      scopes: Vector[StatementScopeSnapshot],
      statements: Vector[Statement]
  )

  private final case class DesignGraphSnapshot(
      components: Vector[(Component, StatementGraphSnapshot)],
      componentStates: Vector[ComponentStateSnapshot],
      assignmentContainers: Vector[AssignmentContainerSnapshot],
      memoryPortContainers: Vector[MemoryPortContainerSnapshot]
  )

  private final case class ComponentStateSnapshot(
      component: Component,
      children: Vector[Component],
      io: Vector[BaseType]
  )

  private final case class AssignmentContainerSnapshot(
      target: BaseType,
      statements: Vector[AssignmentStatement]
  )

  private final case class MemoryPortContainerSnapshot(
      memory: Mem[_],
      statements: Vector[MemPortStatement]
  )

  private final case class StorageSnapshot(
      regions: Vector[StructuralRegion],
      pending: Vector[(Long, ParameterizedStructuralPending)],
      labels: Vector[(String, Option[String])],
      assignmentValidationScheduled: Boolean
  )

  private val activeCapture = new ThreadLocal[CaptureState]()

  /** True only while constructing a component for parameterized Verilog. */
  def captureEnabled: Boolean =
    (Component.current ne null) &&
      (try GlobalData.get.config.parameterizedVerilog
       catch { case _: Throwable => false })

  /**
    * Capture one representative ordinary SpinalHDL body.
    *
    * Only declarations, data assignments, ordinary native memories, native
    * when/switch hardware trees and child Components are accepted. Arbitrary
    * statement kinds are rejected explicitly instead of silently changing
    * Scala semantics.
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
    requireComponentContext(component, sourceLocation)
    val previousCapture = activeCapture.get()
    if ((previousCapture ne null) && (previousCapture.component ne component)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CAPTURE-COMPONENT-MISMATCH",
        "nested structural capture crossed an active Component boundary",
        sourceLocation
      )
    }

    val beforeDesign = snapshotDesignGraph(component, sourceLocation)
    val beforeGraph = graphOf(beforeDesign, component, sourceLocation)
    val beforeChildren = component.children.toVector
    val entryScope = DslScopeStack.get
    val entryContext = ScopeProperty.capture()
    val creationBoundary = GlobalData.get.instanceCounter
    val storage = storageOf(component)
    val storageSnapshot = snapshotStorage(storage)
    storage.nextCaptureId += 1
    val state = new CaptureState(
      component,
      sourceLocation,
      storage.nextCaptureId
    )
    activeCapture.set(state)
    try {
      var returnedScope: ScopeStatement = null
      try body
      finally {
        returnedScope = DslScopeStack.get
        restoreActiveCapture(previousCapture)
        entryContext.restore()
      }
      if (returnedScope ne entryScope) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-COMPONENT-CONTEXT-MISMATCH",
          "structural body returned with a different DSL scope; nested process-control context must close inside its capture",
          sourceLocation
        )
      }
      finishCapture(
        component,
        sourceLocation,
        beforeDesign,
        beforeGraph,
        beforeChildren,
        creationBoundary,
        state
      )
    } catch {
      case error: Throwable =>
        if (activeCapture.get() eq state) restoreActiveCapture(previousCapture)
        entryContext.restore()
        try {
          rollbackCapture(
            component,
            beforeDesign,
            storage,
            storageSnapshot
          )
        } catch {
          case rollbackError: Throwable => error.addSuppressed(rollbackError)
        }
        throw error
    }
  }

  private def finishCapture(
      component: Component,
      sourceLocation: Option[String],
      beforeDesign: DesignGraphSnapshot,
      beforeGraph: StatementGraphSnapshot,
      beforeChildren: Vector[Component],
      creationBoundary: Int,
      state: CaptureState
  ): ParameterizedStructuralBlock = {
    val afterDesign = snapshotDesignGraph(component, sourceLocation)
    val afterGraph = graphOf(afterDesign, component, sourceLocation)
    val beforeStatements = beforeGraph.rootStatements
    val beforeHardwareStatements = beforeGraph.statements
    val nestedBlocks =
      state.regions.toVector.flatMap(region => allBlocks(region))
    val nestedStatements = nestedBlocks.flatMap(_.statements)
    val nestedHardwareStatements = statementForest(
      nestedStatements,
      sourceLocation
    )
    val nestedChildren = nestedBlocks.flatMap(_.children)
    val statements =
      afterGraph.rootStatements.filterNot(value =>
        beforeStatements.exists(_ eq value) ||
        nestedStatements.exists(_ eq value)
      )
    val children =
      component.children.toVector.filterNot(value =>
        beforeChildren.exists(_ eq value) ||
        nestedChildren.exists(_ eq value)
      )

    val hardwareStatements = statementForest(statements, sourceLocation)
    val capturedHardware = identitySet(hardwareStatements)
    val beforeHardware = identitySet(beforeHardwareStatements)
    val nestedHardware = identitySet(nestedHardwareStatements)
    hardwareStatements
      .find(value => value.getInstanceCounter < creationBoundary)
      .foreach { value =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-FOREIGN-OWNERSHIP",
          s"captured process tree adopted native statement '${value.getClass.getSimpleName}' created before this structural block; every statement in a relocated process must originate in exactly one capture",
          sourceLocation
        )
      }
    children
      .find(value => value.getInstanceCounter < creationBoundary)
      .foreach { value =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CHILD-FOREIGN-OWNERSHIP",
          s"structural block adopted pre-existing child Component '${value.getClass.getSimpleName}'; every relocated child must be constructed inside the same capture",
          sourceLocation
        )
      }
    hardwareStatements
      .find(value =>
        beforeHardware.containsKey(value) || nestedHardware.containsKey(value)
      )
      .foreach { value =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-FOREIGN-OWNERSHIP",
          s"captured process tree adopted pre-existing native statement '${value.getClass.getSimpleName}'; every statement in a relocated process must originate in exactly one structural block",
          sourceLocation
        )
      }
    validatePreexistingDesignGraph(
      component,
      beforeDesign,
      afterDesign,
      identityDistinct(children ++ nestedChildren),
      sourceLocation
    )
    val beforeDesignHardware = identitySet(
      beforeDesign.components.flatMap(_._2.statements)
    )
    val newStatementsOnExistingComponents = beforeDesign.components
      .flatMap { case (owner, _) =>
        graphOption(afterDesign, owner).toVector.flatMap(_.statements)
      }
      .filterNot(beforeDesignHardware.containsKey)
    newStatementsOnExistingComponents
      .find(value =>
        !nestedHardware.containsKey(value) && !capturedHardware.containsKey(value)
      )
      .foreach { value =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-FOREIGN-OWNERSHIP",
          s"structural body inserted native statement '${value.getClass.getSimpleName}' into a process tree that existed before this capture; each relocated process tree must be created wholly inside one structural block",
          sourceLocation
        )
      }
    val beforeContainerStatements = identitySet(
      beforeDesign.assignmentContainers.flatMap(_.statements) ++
        beforeDesign.memoryPortContainers.flatMap(_.statements)
    )
    val capturedOrNestedHardware = identitySet(
      hardwareStatements ++ nestedHardwareStatements
    )
    val beforeComponents = identitySet(beforeDesign.components.map(_._1))
    val capturedChildComponents = identitySet(
      identityDistinct(children ++ nestedChildren).flatMap(componentTree)
    )
    val capturedChildHardware = identitySet(
      afterDesign.components.flatMap {
        case (owner, graph)
            if capturedChildComponents.containsKey(owner) &&
              !beforeComponents.containsKey(owner) &&
              owner.getInstanceCounter >= creationBoundary =>
          graph.statements
        case _ => Vector.empty[Statement]
      }
    )
    val newContainerStatements =
      (afterDesign.assignmentContainers.flatMap(_.statements) ++
        afterDesign.memoryPortContainers.flatMap(_.statements))
        .filterNot(beforeContainerStatements.containsKey)
    newContainerStatements
      .find(value =>
        !capturedOrNestedHardware.containsKey(value) &&
          !capturedChildHardware.containsKey(value)
      )
      .foreach { value =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-FOREIGN-OWNERSHIP",
          s"structural body left new native statement '${value.getClass.getSimpleName}' in an assignment or memory container without one exact captured process scope",
          sourceLocation
        )
      }

    val declarations = hardwareStatements.collect {
      case value: BaseType => value
    }.toVector
    val assignments = hardwareStatements.collect {
      case value: DataAssignmentStatement => value
    }.toVector
    val initializationAssignments = hardwareStatements.collect {
      case value: InitAssignmentStatement    => value
      case value: InitialAssignmentStatement => value
    }.toVector
    val memories = hardwareStatements.collect {
      case value: Mem[_] => value
    }.toVector
    val memoryPorts = hardwareStatements.collect {
      case value: MemPortStatement => value
    }.toVector
    memoryPorts.find(port => !memories.exists(_ eq port.mem)).foreach { port =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FOREIGN-MEMORY-PORT-UNSUPPORTED",
        s"structural body emitted a memory port for '${Option(port.mem).flatMap(value => Option(value.getName())).getOrElse("<unnamed>")}' without declaring that memory inside the same captured block",
        sourceLocation
      )
    }

    val capturedDeclarations = new IdentityHashMap[
      BaseType,
      java.lang.Boolean
    ]()
    declarations.foreach(value =>
      capturedDeclarations.put(value, java.lang.Boolean.TRUE)
    )
    initializationAssignments
      .find(value => !capturedDeclarations.containsKey(value.finalTarget))
      .foreach { value =>
        val targetName = Option(value.finalTarget.getName())
          .filter(_.nonEmpty)
          .getOrElse("<unnamed>")
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-INITIALIZER-FOREIGN-TARGET",
          s"captured ${value.getClass.getSimpleName} targets '$targetName', whose exact declaration was not created in the same structural block; reset and initial values may move only with their owned declaration",
          sourceLocation
        )
      }

    // Every source alternative must remain available to the native emitter even
    // when it is not selected by the concrete witness. Preserve the exact
    // declarations and memory ports until the MorphHDL relocation pass extracts
    // them into their parameterized structural region.
    declarations.foreach { value =>
      value.setAsVital()
      value.dontSimplifyIt()
    }
    memories.foreach(_.preventAsBlackBox())
    memoryPorts.foreach(port => port.isVital = true)

    val unsupported = hardwareStatements.filterNot {
      case _: BaseType                   => true
      case _: DataAssignmentStatement    => true
      case _: InitAssignmentStatement    => true
      case _: InitialAssignmentStatement => true
      case _: Mem[_]                     => true
      case _: MemPortStatement           => true
      case _: WhenStatement              => true
      case _: SwitchStatement            => true
      case _                             => false
    }

    unsupported.headOption.foreach { value =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SCALA-SIDE-EFFECT-UNSUPPORTED",
        s"structural body emitted unsupported native statement '${value.getClass.getSimpleName}'; only declarations, owned data/init assignments, native memories, native when/switch trees and child Components may be captured",
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

    new ParameterizedStructuralBlock(
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
      value.index == index
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
      component.addPrePopTask(() =>
        authorizeMutuallyExclusiveAssignments(component)
      )
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
        entries.foreach(value =>
          capturedStatements.put(value.statement, java.lang.Boolean.TRUE)
        )
        val targetAssignments = graphAssignments
          .filter(value => value.finalTarget eq target)
          .toVector
        val completeCapture =
          targetAssignments.size == entries.size &&
          targetAssignments.forall(capturedStatements.containsKey) &&
          entries.forall(value =>
            targetAssignments.exists(_ eq value.statement)
          )
        val pairwiseExclusive = entries.indices.forall { left =>
          (left + 1 until entries.size).forall { right =>
            mutuallyExclusive(entries(left).path, entries(right).path)
          }
        }
        if (completeCapture && pairwiseExclusive) target.allowOverride()
      }
    }
  }

  private def capturedAssignments(
      regions: Vector[StructuralRegion]
  ): Vector[CapturedAssignment] = {
    val values = ArrayBuffer.empty[CapturedAssignment]

    def visitBlock(
        block: ParameterizedStructuralBlock,
        path: Vector[AlternativeStep]
    ): Unit = {
      block.assignments.foreach(value =>
        values += CapturedAssignment(value, path)
      )
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

  private def mutuallyExclusive(
      left: Vector[AlternativeStep],
      right: Vector[AlternativeStep]
  ): Boolean = mutuallyExclusiveAlternatives(
    left.map(value => value.region -> value.branch),
    right.map(value => value.region -> value.branch)
  )

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
    ): (Boolean, Map[StructuralPredicateRoot, Vector[StructuralPredicateInterval]]) = {
      val domains = mutable.LinkedHashMap.empty[
        StructuralPredicateRoot,
        Vector[StructuralPredicateInterval]
      ]
      var impossible = false
      path.foreach {
        case (value: StructuralIf, branch) =>
          value.predicateDomain.foreach { domain =>
            domain.valuesFor(branch).foreach { allowed =>
              val constrained = domains
                .get(domain.root)
                .map(intersectPredicateIntervals(_, allowed))
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
    leftImpossible || rightImpossible || leftDomains.exists {
      case (root, leftValues) =>
        rightDomains.get(root).exists(rightValues =>
          intersectPredicateIntervals(leftValues, rightValues).isEmpty
        )
    }
  }

  private[core] def normalizePredicateIntervals(
      intervals: Vector[StructuralPredicateInterval]
  ): Vector[StructuralPredicateInterval] =
    intervals.sortBy(_.minimum).foldLeft(Vector.empty[StructuralPredicateInterval]) {
      case (Vector(), next) => Vector(next)
      case (current, next) =>
        val previous = current.last
        if (next.minimum <= previous.maximum + 1)
          current.init :+ StructuralPredicateInterval(
            previous.minimum,
            previous.maximum.max(next.maximum)
          )
        else current :+ next
    }

  private[core] def intersectPredicateIntervals(
      left: Vector[StructuralPredicateInterval],
      right: Vector[StructuralPredicateInterval]
  ): Vector[StructuralPredicateInterval] = {
    val normalizedLeft = normalizePredicateIntervals(left)
    val normalizedRight = normalizePredicateIntervals(right)
    val result = ArrayBuffer.empty[StructuralPredicateInterval]
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < normalizedLeft.size && rightIndex < normalizedRight.size) {
      val leftValue = normalizedLeft(leftIndex)
      val rightValue = normalizedRight(rightIndex)
      val minimum = leftValue.minimum.max(rightValue.minimum)
      val maximum = leftValue.maximum.min(rightValue.maximum)
      if (minimum <= maximum)
        result += StructuralPredicateInterval(minimum, maximum)
      if (leftValue.maximum < rightValue.maximum) leftIndex += 1
      else rightIndex += 1
    }
    result.toVector
  }

  private[core] def complementPredicateIntervals(
      root: StructuralPredicateRoot,
      intervals: Vector[StructuralPredicateInterval]
  ): Vector[StructuralPredicateInterval] = {
    val normalized = normalizePredicateIntervals(intervals)
    val result = ArrayBuffer.empty[StructuralPredicateInterval]
    var nextMinimum = root.minimum
    normalized.foreach { interval =>
      if (nextMinimum < interval.minimum)
        result += StructuralPredicateInterval(nextMinimum, interval.minimum - 1)
      nextMinimum = interval.maximum + 1
    }
    if (nextMinimum <= root.maximum)
      result += StructuralPredicateInterval(nextMinimum, root.maximum)
    result.toVector
  }

  def registerFor(
      component: Component,
      label: String,
      indexName: String,
      count: ElaborationIntegerExpression,
      body: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ): Unit = {
    val captureId = currentCaptureId(component, sourceLocation)
    val storage = storageOf(component)
    validateIntegerExpression(count, "generate count")
    if (count.default < 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-COUNT-NONPOSITIVE",
        s"generate count '${count.verilog}' has non-positive concrete witness ${count.default}",
        sourceLocation
      )
    }
    if (count.minimum < 0 || count.maximum > BigInt(Int.MaxValue)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-COUNT-DOMAIN-UNSUPPORTED",
        s"generate count '${count.verilog}' reaches [${count.minimum}, ${count.maximum}], outside the supported non-negative Int-sized domain",
        sourceLocation
      )
    }
    reserveNames(
      storage,
      Vector(label -> "generate label", indexName -> "generate index"),
      sourceLocation
    )
    registerRegion(
      component,
      captureId,
      StructuralFor(
        label,
        indexName,
        count,
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
    val captureId = currentCaptureId(component, sourceLocation)
    val storage = storageOf(component)
    storage.nextPendingId += 1
    val token = new ParameterizedStructuralPending(
      component,
      storage.nextPendingId,
      kind,
      captureId,
      sourceLocation
    )
    storage.pending(token.id) = token
    token
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
    validateRegistrationContext(
      pending.component,
      pending.captureId,
      sourceLocation
    )
    val storage = storageOf(pending.component)
    requirePending(storage, pending)
    validateParameters(condition.parameters, sourceLocation)
    reserveNames(
      storage,
      Vector(
        whenTrueLabel -> "generate-if true label",
        whenFalseLabel -> "generate-if false label"
      ),
      sourceLocation
    )
    registerRegion(
      pending.component,
      pending.captureId,
      StructuralIf(
        condition,
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
    validateRegistrationContext(
      pending.component,
      pending.captureId,
      sourceLocation
    )
    val storage = storageOf(pending.component)
    requirePending(storage, pending)
    validateIntegerExpression(selector, "generate-case selector")
    if (choices.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CASE-EMPTY",
        "generate-case requires at least one literal choice before default",
        sourceLocation
      )
    }
    choices.groupBy(_._1).collectFirst {
      case (value, entries) if entries.size != 1 => value
    }.foreach { value =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CASE-CHOICE-DUPLICATE",
        s"generate-case contains duplicate literal choice $value",
        sourceLocation
      )
    }
    reserveNames(
      storage,
      choices.map { case (_, label, _) =>
        label -> "generate-case choice label"
      } :+ (defaultLabel -> "generate-case default label"),
      sourceLocation
    )
    registerRegion(
      pending.component,
      pending.captureId,
      StructuralCase(
        selector,
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
    val values = storageOption(component).toVector
      .flatMap(_.regions)
      .flatMap(region => regionParameters(region))
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

  /**
    * Width evidence for a hierarchy boundary that uses a recorded symbolic
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
    found.distinct.toVector match {
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

  private def currentCaptureId(
      component: Component,
      sourceLocation: Option[String]
  ): Option[Long] = {
    requireComponentContext(component, sourceLocation)
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
    validateRegistrationContext(component, expectedCaptureId, sourceLocation)
    val capture = activeCapture.get()
    scheduleAssignmentValidation(component)
    if (capture eq null) storageOf(component).regions += region
    else capture.regions += region
  }

  private def validateRegistrationContext(
      component: Component,
      expectedCaptureId: Option[Long],
      sourceLocation: Option[String]
  ): Unit = {
    requireComponentContext(component, sourceLocation)
    val capture = activeCapture.get()
    val actualCaptureId = Option(capture).map(_.id)
    if (actualCaptureId != expectedCaptureId) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CAPTURE-CONTEXT-MISMATCH",
        s"structural region expected capture ${expectedCaptureId.getOrElse("root")} but active capture is ${actualCaptureId.getOrElse("root")}",
        sourceLocation
      )
    }
    if ((capture ne null) && (capture.component ne component)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CAPTURE-COMPONENT-MISMATCH",
        "nested structural region belongs to a different Component",
        sourceLocation
      )
    }
  }

  private def requireComponentContext(
      component: Component,
      sourceLocation: Option[String]
  ): Unit = {
    val scope = DslScopeStack.get
    if (
      (component eq null) || (Component.current ne component) ||
      (scope eq null) || (scope.component ne component)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-COMPONENT-CONTEXT-MISMATCH",
        "structural capture or registration must run in a DSL scope owned by its exact Component",
        sourceLocation
      )
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

  private def reserveNames(
      storage: Storage,
      values: Vector[(String, String)],
      sourceLocation: Option[String]
  ): Unit = {
    val portable = "[A-Za-z_][A-Za-z0-9_]*".r
    val local = mutable.LinkedHashSet.empty[String]
    values.foreach { case (value, role) =>
      if (value == null || !portable.pattern.matcher(value).matches()) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-NAME-INVALID",
          s"$role '$value' is not a portable Verilog identifier",
          sourceLocation
        )
      }
      if (!local.add(value)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-NAME-DUPLICATE",
          s"$role '$value' duplicates another name in the same structural registration",
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
    }
    values.foreach { case (value, _) => storage.labels(value) = sourceLocation }
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
    grouped.collectFirst {
      case (name, values) if values.distinct.size != 1 => name
    }.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"structural parameter '$name' has conflicting declarations",
        sourceLocation
      )
    }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private final case class ReachableStatementGraph(
      scopes: Vector[ScopeStatement],
      statements: Vector[Statement]
  )

  private def snapshotDesignGraph(
      component: Component,
      sourceLocation: Option[String]
  ): DesignGraphSnapshot = {
    val root = designRoot(component)
    val components = componentTree(root).map { owner =>
      owner -> snapshotStatementGraph(owner.dslBody, sourceLocation)
    }
    val statements = components.flatMap(_._2.statements)
    val assignmentContainers = identityDistinct(
      statements.collect { case value: BaseType => value }
    ).map(value => snapshotAssignmentContainer(value, sourceLocation))
    val memoryPortContainers = identityDistinct(
      statements.collect { case value: Mem[_] => value }
    ).map(value => snapshotMemoryPortContainer(value, sourceLocation))
    DesignGraphSnapshot(
      components,
      components.map { case (owner, _) =>
        ComponentStateSnapshot(
          owner,
          owner.children.toVector,
          owner.ioSet.toVector
        )
      },
      assignmentContainers,
      memoryPortContainers
    )
  }

  private def collectReachableDesignGraph(
      component: Component,
      additionalComponents: Vector[Component]
  ): Vector[(Component, ReachableStatementGraph)] = {
    val root = designRoot(component)
    identityDistinct(componentTree(root) ++ additionalComponents).map { owner =>
      owner -> collectReachableStatementGraph(owner.dslBody)
    }
  }

  private def designRoot(component: Component): Component = {
    var current = component
    val seen = new IdentityHashMap[Component, java.lang.Boolean]()
    while ((current.parent ne null) && !seen.containsKey(current)) {
      seen.put(current, java.lang.Boolean.TRUE)
      current = current.parent
    }
    current
  }

  private def componentTree(root: Component): Vector[Component] = {
    val values = ArrayBuffer.empty[Component]
    val seen = new IdentityHashMap[Component, java.lang.Boolean]()
    def visit(value: Component): Unit = {
      if ((value ne null) && !seen.containsKey(value)) {
        seen.put(value, java.lang.Boolean.TRUE)
        values += value
        value.children.foreach(visit)
      }
    }
    visit(root)
    values.toVector
  }

  private def graphOption(
      design: DesignGraphSnapshot,
      component: Component
  ): Option[StatementGraphSnapshot] =
    design.components.collectFirst {
      case (owner, graph) if owner eq component => graph
    }

  private def graphOf(
      design: DesignGraphSnapshot,
      component: Component,
      sourceLocation: Option[String]
  ): StatementGraphSnapshot =
    graphOption(design, component).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-COMPONENT-GRAPH-MISSING",
        "active structural Component is absent from its own design graph snapshot",
        sourceLocation
      )
    }

  private def validatePreexistingDesignGraph(
      activeComponent: Component,
      before: DesignGraphSnapshot,
      after: DesignGraphSnapshot,
      allowedNewChildren: Vector[Component],
      sourceLocation: Option[String]
  ): Unit = {
    def changed(detail: String): Nothing =
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PREEXISTING-GRAPH-MUTATED",
        detail,
        sourceLocation
      )

    before.components.foreach { case (owner, beforeGraph) =>
      val afterGraph = graphOption(after, owner).getOrElse {
        changed(
          s"structural body detached pre-existing Component '${owner.getClass.getSimpleName}' from the design graph"
        )
      }
      beforeGraph.scopes.foreach { beforeScope =>
        val afterScope = afterGraph.scopes.find(value =>
          value.scope eq beforeScope.scope
        ).getOrElse {
          changed(
            "structural body detached a pre-existing native process scope"
          )
        }
        if (
          (afterScope.parentStatement ne beforeScope.parentStatement) ||
          (afterScope.component ne beforeScope.component)
        ) {
          changed(
            "structural body changed the owner metadata of a pre-existing native process scope"
          )
        }
        val beforeIdentities = identitySet(beforeScope.statements)
        val projected = afterScope.statements.filter(beforeIdentities.containsKey)
        if (
          projected.size != beforeScope.statements.size ||
          !projected.zip(beforeScope.statements).forall { case (left, right) =>
            left eq right
          }
        ) {
          changed(
            "structural body removed, reordered or reparented a pre-existing native statement"
          )
        }
      }
    }

    before.componentStates.foreach { beforeState =>
      val afterState = after.componentStates.find(value =>
        value.component eq beforeState.component
      ).getOrElse {
        changed(
          s"structural body detached pre-existing Component '${beforeState.component.getClass.getSimpleName}' from component ownership"
        )
      }
      val beforeChildren = identitySet(beforeState.children)
      val projectedChildren = afterState.children.filter(beforeChildren.containsKey)
      if (
        projectedChildren.size != beforeState.children.size ||
        !projectedChildren.zip(beforeState.children).forall {
          case (left, right) => left eq right
        }
      ) {
        changed(
          s"structural body removed or reordered pre-existing children of Component '${beforeState.component.getClass.getSimpleName}'"
        )
      }
      val addedChildren = afterState.children.filterNot(beforeChildren.containsKey)
      val allowed =
        if (beforeState.component eq activeComponent)
          identitySet(allowedNewChildren)
        else identitySet(Vector.empty[Component])
      if (
        addedChildren.exists(value => !allowed.containsKey(value))
      ) {
        changed(
          s"structural body added an unowned child to pre-existing Component '${beforeState.component.getClass.getSimpleName}'"
        )
      }
      if (
        afterState.io.size != beforeState.io.size ||
        !afterState.io.zip(beforeState.io).forall { case (left, right) =>
          left eq right
        }
      ) {
        changed(
          s"structural body added, removed or reordered I/O of pre-existing Component '${beforeState.component.getClass.getSimpleName}'"
        )
      }
    }

    before.assignmentContainers.foreach { beforeContainer =>
      val afterContainer = after.assignmentContainers.find(value =>
        value.target eq beforeContainer.target
      ).getOrElse {
        changed("structural body detached a pre-existing assignment container")
      }
      val beforeAssignments = identitySet(beforeContainer.statements)
      val projected = afterContainer.statements.filter(
        beforeAssignments.containsKey
      )
      if (
        projected.size != beforeContainer.statements.size ||
        !projected.zip(beforeContainer.statements).forall {
          case (left, right) => left eq right
        }
      ) {
        changed(
          "structural body removed, reordered or reparented a pre-existing assignment-container statement"
        )
      }
    }
    before.memoryPortContainers.foreach { beforeContainer =>
      val afterContainer = after.memoryPortContainers.find(value =>
        value.memory eq beforeContainer.memory
      ).getOrElse {
        changed("structural body detached a pre-existing memory-port container")
      }
      val beforePorts = identitySet(beforeContainer.statements)
      val projected = afterContainer.statements.filter(beforePorts.containsKey)
      if (
        projected.size != beforeContainer.statements.size ||
        !projected.zip(beforeContainer.statements).forall {
          case (left, right) => left eq right
        }
      ) {
        changed(
          "structural body removed, reordered or reparented a pre-existing memory-port statement"
        )
      }
    }
  }

  private def snapshotStatementGraph(
      root: ScopeStatement,
      sourceLocation: Option[String]
  ): StatementGraphSnapshot = {
    val scopes = ArrayBuffer.empty[StatementScopeSnapshot]
    val statements = ArrayBuffer.empty[Statement]
    val visitedScopes = new IdentityHashMap[
      ScopeStatement,
      java.lang.Boolean
    ]()
    val owners = new IdentityHashMap[Statement, ScopeStatement]()

    def visitScope(scope: ScopeStatement): Unit = {
      if (scope eq null) return
      if (visitedScopes.containsKey(scope)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-SCOPE-ALIASED",
          "one native process scope is reachable through more than one tree owner",
          sourceLocation
        )
      }
      visitedScopes.put(scope, java.lang.Boolean.TRUE)
      val values = exactScopeStatements(scope, sourceLocation)
      scopes += StatementScopeSnapshot(
        scope,
        scope.parentStatement,
        scope.component,
        values
      )
      values.foreach { value =>
        val previousOwner = owners.put(value, scope)
        if (previousOwner ne null) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-STATEMENT-ALIASED",
            s"native statement '${value.getClass.getSimpleName}' is owned by multiple process scopes",
            sourceLocation
          )
        }
        statements += value
      }
      values.foreach {
        case tree: TreeStatement =>
          childScopes(tree).foreach(visitScope)
        case _ =>
      }
    }

    visitScope(root)
    StatementGraphSnapshot(
      scopes.headOption.map(_.statements).getOrElse(Vector.empty),
      scopes.toVector,
      statements.toVector
    )
  }

  private def snapshotAssignmentContainer(
      target: BaseType,
      sourceLocation: Option[String]
  ): AssignmentContainerSnapshot = {
    val values = ArrayBuffer.empty[AssignmentStatement]
    val seen = new IdentityHashMap[
      AssignmentStatement,
      java.lang.Boolean
    ]()
    var previous: AssignmentStatement = null
    var current = target.dlcHead
    while (current ne null) {
      if (seen.containsKey(current)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-ASSIGNMENT-CONTAINER-ALIASED",
          s"native assignment container for '${Option(target.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")}' contains one statement more than once",
          sourceLocation
        )
      }
      seen.put(current, java.lang.Boolean.TRUE)
      if ((current.dlcParent ne target) || (current.dlceLast ne previous)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-ASSIGNMENT-CONTAINER-OWNER-MISMATCH",
          s"native assignment container for '${Option(target.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")}' has inconsistent owner or ordering links",
          sourceLocation
        )
      }
      values += current
      previous = current
      current = current.dlceNext
    }
    if (target.dlcLast ne previous) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-ASSIGNMENT-CONTAINER-ORDER-MISMATCH",
        s"native assignment container for '${Option(target.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")}' has inconsistent head/last links",
        sourceLocation
      )
    }
    AssignmentContainerSnapshot(target, values.toVector)
  }

  private def snapshotMemoryPortContainer(
      memory: Mem[_],
      sourceLocation: Option[String]
  ): MemoryPortContainerSnapshot = {
    val values = ArrayBuffer.empty[MemPortStatement]
    val seen = new IdentityHashMap[
      MemPortStatement,
      java.lang.Boolean
    ]()
    var previous: MemPortStatement = null
    var current = memory.dlcHead
    while (current ne null) {
      if (seen.containsKey(current)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEMORY-CONTAINER-ALIASED",
          s"native memory-port container for '${Option(memory.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")}' contains one port more than once",
          sourceLocation
        )
      }
      seen.put(current, java.lang.Boolean.TRUE)
      if ((current.dlcParent ne memory) || (current.dlceLast ne previous)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEMORY-CONTAINER-OWNER-MISMATCH",
          s"native memory-port container for '${Option(memory.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")}' has inconsistent owner or ordering links",
          sourceLocation
        )
      }
      values += current
      previous = current
      current = current.dlceNext
    }
    if (memory.dlcLast ne previous) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEMORY-CONTAINER-ORDER-MISMATCH",
        s"native memory-port container for '${Option(memory.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")}' has inconsistent head/last links",
        sourceLocation
      )
    }
    MemoryPortContainerSnapshot(memory, values.toVector)
  }

  /**
    * Deterministic ownership-checked traversal of selected structural roots.
    * Reaching one Statement or ScopeStatement twice is an error, never a
    * deduplication opportunity: relocation requires one exact native owner.
    */
  private def statementForest(
      roots: Vector[Statement],
      sourceLocation: Option[String]
  ): Vector[Statement] = {
    val statements = ArrayBuffer.empty[Statement]
    val owners = new IdentityHashMap[Statement, ScopeStatement]()
    val visitedScopes = new IdentityHashMap[
      ScopeStatement,
      java.lang.Boolean
    ]()

    def visitStatement(value: Statement, owner: ScopeStatement): Unit = {
      if ((value eq null) || (owner eq null) || (value.parentScope ne owner)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-STATEMENT-OWNER-MISMATCH",
          s"native statement '${Option(value).map(_.getClass.getSimpleName).getOrElse("<null>")}' is not linked to its exact process scope",
          sourceLocation
        )
      }
      val previousOwner = owners.put(value, owner)
      if (previousOwner ne null) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-STATEMENT-ALIASED",
          s"native statement '${value.getClass.getSimpleName}' is reachable through more than one process owner",
          sourceLocation
        )
      }
      statements += value
      value match {
        case tree: TreeStatement => childScopes(tree).foreach(visitScope)
        case _                   =>
      }
    }

    def visitScope(scope: ScopeStatement): Unit = {
      if (visitedScopes.containsKey(scope)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-SCOPE-ALIASED",
          "one native process scope is reachable through more than one captured tree",
          sourceLocation
        )
      }
      visitedScopes.put(scope, java.lang.Boolean.TRUE)
      exactScopeStatements(scope, sourceLocation)
        .foreach(value => visitStatement(value, scope))
    }

    roots.foreach(value => visitStatement(value, value.parentScope))
    statements.toVector
  }

  private def exactScopeStatements(
      scope: ScopeStatement,
      sourceLocation: Option[String]
  ): Vector[Statement] = {
    val values = ArrayBuffer.empty[Statement]
    val seen = new IdentityHashMap[Statement, java.lang.Boolean]()
    var previous: Statement = null
    var current = scope.head
    while (current ne null) {
      if (seen.containsKey(current)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-STATEMENT-ALIASED",
          s"native process scope contains statement '${current.getClass.getSimpleName}' more than once",
          sourceLocation
        )
      }
      seen.put(current, java.lang.Boolean.TRUE)
      if ((current.parentScope ne scope) || (current.lastScopeStatement ne previous)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-STATEMENT-OWNER-MISMATCH",
          s"native statement '${current.getClass.getSimpleName}' has inconsistent owner or ordering links",
          sourceLocation
        )
      }
      values += current
      previous = current
      current = current.nextScopeStatement
    }
    if (scope.last ne previous) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-SCOPE-ORDER-MISMATCH",
        "native process scope head/last links do not describe one exact statement order",
        sourceLocation
      )
    }
    values.toVector
  }

  private def childScopes(tree: TreeStatement): Vector[ScopeStatement] = {
    val (candidates, exactBranchInventory) = tree match {
      case value: WhenStatement =>
        Vector(value.whenTrue, value.whenFalse) -> true
      case value: SwitchStatement =>
        (value.elements.toVector.map(_.scopeStatement) ++
          Option(value.defaultScope).toVector) -> true
      case value =>
        val discovered = ArrayBuffer.empty[ScopeStatement]
        value.foreachStatements { statement =>
          if (statement.parentScope ne null) discovered += statement.parentScope
        }
        discovered.toVector -> false
    }
    if (exactBranchInventory) {
      val seen = new IdentityHashMap[
        ScopeStatement,
        java.lang.Boolean
      ]()
      candidates.filter(_ ne null).foreach { scope =>
        if (seen.containsKey(scope)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-SCOPE-ALIASED",
            s"native ${tree.getClass.getSimpleName} references one branch scope more than once"
          )
        }
        seen.put(scope, java.lang.Boolean.TRUE)
      }
    }
    val distinct = identityDistinct(candidates.filter(_ ne null))
    distinct.foreach { scope =>
      if (scope.parentStatement ne tree) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-STATEMENT-OWNER-MISMATCH",
          s"native ${tree.getClass.getSimpleName} references a scope owned by another tree"
        )
      }
    }
    distinct
  }

  private def collectReachableStatementGraph(
      root: ScopeStatement
  ): ReachableStatementGraph = {
    val scopes = ArrayBuffer.empty[ScopeStatement]
    val statements = ArrayBuffer.empty[Statement]
    val visitedScopes = new IdentityHashMap[
      ScopeStatement,
      java.lang.Boolean
    ]()
    val visitedStatements = new IdentityHashMap[
      Statement,
      java.lang.Boolean
    ]()

    def visitScope(scope: ScopeStatement): Unit = {
      if ((scope eq null) || visitedScopes.containsKey(scope)) return
      visitedScopes.put(scope, java.lang.Boolean.TRUE)
      scopes += scope
      val local = new IdentityHashMap[Statement, java.lang.Boolean]()
      var current = scope.head
      while ((current ne null) && !local.containsKey(current)) {
        local.put(current, java.lang.Boolean.TRUE)
        if (!visitedStatements.containsKey(current)) {
          visitedStatements.put(current, java.lang.Boolean.TRUE)
          statements += current
          current match {
            case tree: WhenStatement =>
              visitScope(tree.whenTrue)
              visitScope(tree.whenFalse)
            case tree: SwitchStatement =>
              tree.elements.foreach(value => visitScope(value.scopeStatement))
              visitScope(tree.defaultScope)
            case tree: TreeStatement =>
              try {
                tree.foreachStatements { value =>
                  if (value.parentScope ne null) visitScope(value.parentScope)
                }
              } catch { case _: Throwable => () }
            case _ =>
          }
        }
        current = current.nextScopeStatement
      }
    }

    visitScope(root)
    ReachableStatementGraph(scopes.toVector, statements.toVector)
  }

  private def rollbackCapture(
      component: Component,
      beforeDesign: DesignGraphSnapshot,
      storage: Storage,
      beforeStorage: StorageSnapshot
  ): Unit = {
    val afterDesign = collectReachableDesignGraph(
      component,
      beforeDesign.componentStates.map(_.component)
    )
    val afterStatements = afterDesign.flatMap(_._2.statements)

    // Statement scopes and assignment/memory containers are independent
    // intrusive lists. Restore the complete pre-capture container inventories
    // in two phases, so even malformed cross-container aliases cannot damage a
    // list restored earlier in the transaction.
    restoreAssignmentContainers(beforeDesign.assignmentContainers)
    restoreMemoryPortContainers(beforeDesign.memoryPortContainers)

    val allScopes = identityDistinct(
      beforeDesign.components.flatMap(_._2.scopes.map(_.scope)) ++
        afterDesign.flatMap(_._2.scopes)
    )
    val allStatements = identityDistinct(
      beforeDesign.components.flatMap(_._2.statements) ++ afterStatements
    )
    allScopes.foreach { scope =>
      scope.head = null
      scope.last = null
    }
    allStatements.foreach { statement =>
      statement.lastScopeStatement = null
      statement.nextScopeStatement = null
      statement.parentScope = null
    }
    beforeDesign.components.foreach { case (_, graph) =>
      graph.scopes.foreach { snapshot =>
        snapshot.scope.parentStatement = snapshot.parentStatement
        snapshot.scope.component = snapshot.component
        snapshot.statements.foreach(snapshot.scope.append)
      }
    }

    beforeDesign.componentStates.foreach { snapshot =>
      snapshot.component.children.clear()
      snapshot.component.children ++= snapshot.children
      snapshot.component.ioSet.clear()
      snapshot.component.ioSet ++= snapshot.io
    }
    restoreStorage(storage, beforeStorage)
  }

  private def restoreAssignmentContainers(
      snapshots: Vector[AssignmentContainerSnapshot]
  ): Unit = {
    val current = snapshots.flatMap(snapshot =>
      collectAssignmentContainer(snapshot.target)
    )
    identityDistinct(current ++ snapshots.flatMap(_.statements)).foreach {
      value =>
      value.dlceLast = null
      value.dlceNext = null
    }
    snapshots.foreach { snapshot =>
      snapshot.target.dlcHead = null
      snapshot.target.dlcLast = null
    }
    snapshots.foreach { snapshot =>
      snapshot.statements.foreach(snapshot.target.dlcAppend)
    }
  }

  private def restoreMemoryPortContainers(
      snapshots: Vector[MemoryPortContainerSnapshot]
  ): Unit = {
    val current = snapshots.flatMap(snapshot =>
      collectMemoryPortContainer(snapshot.memory)
    )
    identityDistinct(current ++ snapshots.flatMap(_.statements)).foreach {
      value =>
      value.dlceLast = null
      value.dlceNext = null
    }
    snapshots.foreach { snapshot =>
      snapshot.memory.dlcHead = null
      snapshot.memory.dlcLast = null
    }
    snapshots.foreach { snapshot =>
      snapshot.statements.foreach(snapshot.memory.dlcAppend)
    }
  }

  private def collectAssignmentContainer(
      target: BaseType
  ): Vector[AssignmentStatement] = {
    val values = ArrayBuffer.empty[AssignmentStatement]
    val seen = new IdentityHashMap[
      AssignmentStatement,
      java.lang.Boolean
    ]()
    var current = target.dlcHead
    while ((current ne null) && !seen.containsKey(current)) {
      seen.put(current, java.lang.Boolean.TRUE)
      values += current
      current = current.dlceNext
    }
    values.toVector
  }

  private def collectMemoryPortContainer(
      memory: Mem[_]
  ): Vector[MemPortStatement] = {
    val values = ArrayBuffer.empty[MemPortStatement]
    val seen = new IdentityHashMap[
      MemPortStatement,
      java.lang.Boolean
    ]()
    var current = memory.dlcHead
    while ((current ne null) && !seen.containsKey(current)) {
      seen.put(current, java.lang.Boolean.TRUE)
      values += current
      current = current.dlceNext
    }
    values.toVector
  }

  private def snapshotStorage(storage: Storage): StorageSnapshot =
    StorageSnapshot(
      storage.regions.toVector,
      storage.pending.toVector,
      storage.labels.toVector,
      storage.assignmentValidationScheduled
    )

  private def restoreStorage(
      storage: Storage,
      snapshot: StorageSnapshot
  ): Unit = {
    val validationWasScheduled = storage.assignmentValidationScheduled
    storage.regions.clear()
    storage.regions ++= snapshot.regions
    storage.pending.clear()
    snapshot.pending.foreach { case (id, value) => storage.pending(id) = value }
    storage.labels.clear()
    snapshot.labels.foreach { case (name, value) => storage.labels(name) = value }
    // A failed nested region may already have installed the one pre-pop task.
    // Keep that fact while restoring every externally visible registry entry.
    storage.assignmentValidationScheduled =
      snapshot.assignmentValidationScheduled || validationWasScheduled
  }

  private def restoreActiveCapture(previous: CaptureState): Unit = {
    if (previous eq null) activeCapture.remove()
    else activeCapture.set(previous)
  }

  private def identityDistinct[T <: AnyRef](values: Vector[T]): Vector[T] = {
    val seen = new IdentityHashMap[T, java.lang.Boolean]()
    values.filter { value =>
      if ((value eq null) || seen.containsKey(value)) false
      else {
        seen.put(value, java.lang.Boolean.TRUE)
        true
      }
    }
  }

  private def identitySet[T <: AnyRef](
      values: Vector[T]
  ): IdentityHashMap[T, java.lang.Boolean] = {
    val result = new IdentityHashMap[T, java.lang.Boolean]()
    values.foreach(value => result.put(value, java.lang.Boolean.TRUE))
    result
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
