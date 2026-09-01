package spinal.core

import java.util.IdentityHashMap

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core.internals._

/** One safe parameter-bounded loop retained inside an ordinary native process.
  *
  * The Scala body is elaborated once at index zero, so `assignment` remains a
  * normal SpinalHDL statement and therefore continues through the inherited
  * driver, latch, clock and reset phases. The Verilog post-pass replaces only
  * that witnessed assignment with the bounded procedural loop.
  */
private[core] final case class ParameterizedProceduralFor(
    label: String,
    indexName: String,
    count: ElaborationIntegerExpression,
    assignment: DataAssignmentStatement,
    slices: Vector[ParameterizedStructure.StructuralSlice],
    marker: String,
    sourceLocation: Option[String]
)

/** MorphHDL-owned Increment 34 classifier for parameter-bounded ranges.
  *
  * Bodies that construct declarations or child Components remain structural
  * generate regions. A body containing exactly one direct assignment to an
  * existing signal is retained as a procedural loop when every target slice is
  * statically proven contiguous and in range over the complete parameter
  * domain. More complex mixed/nested loop bodies fail explicitly.
  */
object ParameterizedProcess {
  private object StorageKey

  private final class Storage {
    val loops = ArrayBuffer.empty[ParameterizedProceduralFor]
    val names = mutable.LinkedHashMap.empty[String, Option[String]]
    var nextMarkerId = 0L
  }

  private final class CaptureState(
      val component: Component,
      val indexName: String,
      val sourceLocation: Option[String]
  ) {
    val slices = ArrayBuffer.empty[ParameterizedStructure.StructuralSlice]
    val vecIndices =
      ArrayBuffer.empty[ParameterizedStructure.StructuralVecIndex]
  }

  private val activeCapture = new ThreadLocal[CaptureState]()

  def captureActive: Boolean = activeCapture.get() ne null

  /** Capture and classify one representative range body without executing it
    * twice. The body stays in the caller's real DSL scope so ordinary child
    * hierarchy and process ownership remain identical to concrete elaboration.
    */
  def captureRange(
      component: Component,
      label: String,
      indexName: String,
      count: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  )(body: => Unit): Unit =
    captureRangeImpl(
      component,
      label,
      indexName,
      count,
      sourceLocation,
      requireExactDomain = true
    )(body)

  /** Established HdlInt entry point. StructuralExpressionBridge has already
    * exhaustively validated its (possibly multi-root) frontend AST through
    * IntExpressionAnalysis. Keeping this as a distinct API makes that proof
    * provenance explicit without fabricating a typed single-root side table.
    */
  private[spinal] def captureAnalyzedFrontendRange(
      component: Component,
      label: String,
      indexName: String,
      count: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  )(body: => Unit): Unit =
    captureRangeImpl(
      component,
      label,
      indexName,
      count,
      sourceLocation,
      requireExactDomain = false
    )(body)

  private def captureRangeImpl(
      component: Component,
      label: String,
      indexName: String,
      count: ElaborationIntegerExpression,
      sourceLocation: Option[String],
      requireExactDomain: Boolean
  )(body: => Unit): Unit = {
    if (component eq null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-COMPONENT-MISSING",
        "parameterized range capture requires an active Component",
        sourceLocation
      )
    }
    if (activeCapture.get() ne null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-NESTED-PROCESS-LOOP-UNSUPPORTED",
        "nested parameterized range capture is outside the safe Increment 34 process-loop surface",
        sourceLocation
      )
    }
    validateName(label, "loop label", sourceLocation)
    validateName(indexName, "loop index", sourceLocation)
    validateCount(count, sourceLocation, requireExactDomain)

    val originalScope = DslScopeStack.get
    if ((originalScope eq null) || (originalScope.component ne component)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SCOPE-MISMATCH",
        "parameterized range capture must remain inside the active Component scope",
        sourceLocation
      )
    }

    val beforeStatements = originalScope.statementIterable.toVector
    val beforeChildren = component.children.toVector
    val state = new CaptureState(component, indexName, sourceLocation)
    activeCapture.set(state)

    var bodyCompleted = false
    try {
      body
      bodyCompleted = true
    } finally {
      activeCapture.remove()
      if (!bodyCompleted) {
        rollbackNewStatements(originalScope, beforeStatements)
        rollbackNewChildren(component, beforeChildren)
      }
    }

    val statements =
      originalScope.statementIterable.toVector.filterNot(value => beforeStatements.exists(_ eq value))
    val children =
      component.children.toVector.filterNot(value => beforeChildren.exists(_ eq value))
    var committed = false
    try {
      classify(
        component,
        statements,
        children,
        state,
        label,
        indexName,
        count,
        requireExactDomain,
        sourceLocation
      )
      committed = true
    } finally {
      if (!committed) {
        rollbackNewStatements(originalScope, beforeStatements)
        rollbackNewChildren(component, beforeChildren)
      }
    }
  }

  /** Record one symbolic packed slice while the range role is undecided. */
  private[spinal] def recordSlice(
      source: BitVector,
      result: BitVector,
      offset: ElaborationIntegerExpression,
      width: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  ): Unit = {
    val state = requireCapture("packed slice", sourceLocation)
    if ((source eq null) || (result eq null)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-NULL",
        "parameterized packed slice requires non-null source and result",
        sourceLocation
      )
    }
    if (!offset.generateIndex.contains(state.indexName)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-NOT-INDEXED",
        s"packed slice offset '${offset.verilog}' does not depend on active loop index '${state.indexName}'",
        sourceLocation
      )
    }
    if (
      width.default < 1 || width.minimum < 1 ||
      offset.default < 0 || offset.minimum < 0
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-DOMAIN-UNSUPPORTED",
        s"slice '${offset.verilog} +: ${width.verilog}' must retain a non-negative offset and positive width over its complete domain",
        sourceLocation
      )
    }
    val sourceWidth = BigInt(source.getBitsWidth)
    if (offset.default + width.default > sourceWidth) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-WITNESS-MISMATCH",
        s"slice witness [${offset.default}, ${offset.default + width.default - 1}] exceeds concrete source width $sourceWidth",
        sourceLocation
      )
    }
    ParameterizedStructure.validateSliceCompleteDomain(
      source,
      offset,
      width,
      sourceLocation,
      "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-DOMAIN-UNSUPPORTED"
    )
    val duplicate = state.slices.exists { value =>
      (value.source eq source) && (value.result eq result) &&
      value.offset == offset && value.width == width
    }
    if (!duplicate) {
      val assignment = ParameterizedStructure.sliceAssignment(
        source,
        result,
        sourceLocation
      )
      state.slices += ParameterizedStructure.StructuralSlice(
        source,
        result,
        assignment,
        offset,
        width,
        sourceLocation
      )
    }
  }

  /** Preserve structural Vec evidence while the range role is undecided. */
  private[spinal] def recordVecIndex[T <: Data](
      vector: Vec[T],
      selected: T,
      index: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  ): T = {
    val state = requireCapture("Vec index", sourceLocation)
    if ((vector eq null) || (selected eq null)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-VEC-NULL",
        "parameterized Vec access requires non-null vector and selected element",
        sourceLocation
      )
    }
    if (!index.generateIndex.contains(state.indexName)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-VEC-NOT-INDEXED",
        s"Vec index '${index.verilog}' does not depend on active loop index '${state.indexName}'",
        sourceLocation
      )
    }
    val carrierLength = ParameterizedVec
      .shapeOf(vector)
      .map(_.carrierCapacity)
      .getOrElse(vector.carrierLength)
    if (
      index.default < 0 || index.default >= carrierLength ||
      index.minimum < 0 || index.maximum >= carrierLength
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-VEC-DOMAIN-UNSUPPORTED",
        s"Vec index '${index.verilog}' reaches [${index.minimum}, ${index.maximum}], outside 0 until $carrierLength",
        sourceLocation
      )
    }
    val retained = ParameterizedStructure.createVecIndexAlias(
      vector,
      selected,
      index,
      sourceLocation
    )
    state.vecIndices += retained
    retained.result.asInstanceOf[T]
  }

  /** Public process-loop parameter inventory for MorphVerilog reports. */
  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val expressions = loopsOf(component).map(_.count)
    ElabInt.validateParameterRootInventory(
      s"process-loop component '${component.definitionName}'",
      expressions
    )
    val values = expressions.flatMap(_.parameters)
    val grouped = values.groupBy(_.name)
    grouped
      .collectFirst {
        case (name, declarations) if declarations.distinct.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
          s"process-loop parameter '$name' has conflicting declarations"
        )
      }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private[core] def loopsOf(
      component: Component
  ): Vector[ParameterizedProceduralFor] =
    storageOption(component).toVector.flatMap(_.loops).toVector

  private def classify(
      component: Component,
      statements: Vector[Statement],
      children: Vector[Component],
      state: CaptureState,
      label: String,
      indexName: String,
      count: ElaborationIntegerExpression,
      requireExactDomain: Boolean,
      sourceLocation: Option[String]
  ): Unit = {
    val declarations = statements.collect { case value: BaseType => value }
    val structuralDeclarations = declarations.filterNot { declaration =>
      state.slices.exists(slice => slice.result eq declaration) ||
      state.vecIndices.exists(_.result.flatten.exists(_ eq declaration))
    }
    val assignments = statements.collect { case value: DataAssignmentStatement =>
      value
    }
    val memories = statements.collect { case value: Mem[_] => value }
    val memoryPorts = statements.collect { case value: MemPortStatement => value }
    memoryPorts.find(port => !memories.exists(_ eq port.mem)).foreach { port =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FOREIGN-MEMORY-PORT-UNSUPPORTED",
        s"structural range body emitted a memory port for '${Option(port.mem).flatMap(value => Option(value.getName())).getOrElse("<unnamed>")}' without declaring that memory inside the same captured block",
        sourceLocation
      )
    }
    val processAssignments = assignments.filterNot { assignment =>
      state.slices.exists { slice =>
        assignment.finalTarget eq slice.result
      }
    }
    val unsupported = statements.filterNot {
      case _: BaseType                => true
      case _: DataAssignmentStatement => true
      case _: Mem[_]                  => true
      case _: MemPortStatement        => true
      case _                          => false
    }

    if (
      statements.isEmpty && children.isEmpty &&
      state.slices.isEmpty && state.vecIndices.isEmpty
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SCALA-SIDE-EFFECT-UNSUPPORTED",
        "parameterized range body produced no native hardware; Scala-only mutation, I/O or collection side effects cannot be lowered",
        sourceLocation
      )
    }

    val hasNativeStructuralConstruction =
      children.nonEmpty || structuralDeclarations.nonEmpty ||
        memories.nonEmpty || memoryPorts.nonEmpty
    if (hasNativeStructuralConstruction || state.vecIndices.nonEmpty) {
      unsupported.headOption.foreach { value =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SCALA-SIDE-EFFECT-UNSUPPORTED",
          s"structural body emitted unsupported native statement '${value.getClass.getSimpleName}'; only declarations, concurrent assignments, native memories and child Components may be captured",
          sourceLocation
        )
      }
      children.collectFirst { case value: BlackBox => value }.foreach { value =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-BLACKBOX-UNSUPPORTED",
          s"structural body instantiated BlackBox '${value.getName()}'; Increment 34 retains the Increment 33 ordinary-Component boundary",
          sourceLocation
        )
      }

      if (hasNativeStructuralConstruction)
        assignments
          .find { assignment =>
            val target = assignment.finalTarget
            val declaredInBody = declarations.exists(_ eq target)
            val ownedByNewChild = children.exists(child => target.component eq child)
            !declaredInBody && !ownedByNewChild
          }
          .foreach { assignment =>
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-MIXED-STRUCTURAL-PROCESS-LOOP-UNSUPPORTED",
              s"one parameterized range mixes structural construction with a process assignment to '${assignment.finalTarget
                  .getName()}'; split the structural and procedural loops",
              sourceLocation
            )
          }
      if (
        declarations.isEmpty && assignments.isEmpty && memories.isEmpty &&
        memoryPorts.isEmpty && children.isEmpty && state.slices.isEmpty &&
        state.vecIndices.isEmpty
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SCALA-SIDE-EFFECT-UNSUPPORTED",
          "structural body produced no native hardware; Scala-only mutation, I/O or collection side effects cannot be lowered",
          sourceLocation
        )
      }

      // Structural range bodies must retain inferred memories as native
      // declarations until MorphHDL relocates them into the generate region.
      state.slices.foreach(_.result.dontSimplifyIt())
      memories.foreach(_.preventAsBlackBox())

      val block = new ParameterizedStructuralBlock(
        statements,
        declarations,
        assignments,
        memories,
        children,
        state.slices.toVector,
        state.vecIndices.toVector,
        Vector.empty,
        ParameterizedStructure.capturedScalarOperators(
          component,
          statements
        ),
        Vector.empty,
        sourceLocation
      )
      if (requireExactDomain)
        ParameterizedStructure.registerExactFor(
          component,
          label,
          indexName,
          count,
          block,
          sourceLocation
        )
      else
        ParameterizedStructure.registerFor(
          component,
          label,
          indexName,
          count,
          block,
          sourceLocation
        )
      return
    }

    unsupported.headOption.foreach { value =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-LOOP-STATEMENT-UNSUPPORTED",
        s"safe procedural loops accept one direct assignment; nested '${value.getClass.getSimpleName}' control must remain outside the parameterized range",
        sourceLocation
      )
    }
    if (processAssignments.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-LOOP-ASSIGNMENT-COUNT-UNSUPPORTED",
        s"safe procedural loops require exactly one direct assignment after excluding ${assignments.size - processAssignments.size} native slice witness copies, received ${processAssignments.size}",
        sourceLocation
      )
    }
    if (state.vecIndices.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-VEC-UNSUPPORTED",
        "procedural parameter loops currently require packed slices; static Vec construction remains a structural generate loop",
        sourceLocation
      )
    }
    if (count.parameters.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-LOOP-COUNT-NOT-PARAMETERIZED",
        s"procedural loop count '${count.verilog}' does not retain a public parameter",
        sourceLocation
      )
    }

    val assignment = processAssignments.head
    if (assignment.finalTarget.component ne component) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-LOOP-TARGET-UNSUPPORTED",
        "procedural parameter loops may assign only signals owned by the current Component",
        sourceLocation
      )
    }
    if (!assignment.finalTarget.isReg && !hasOtherDataAssignment(assignment)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-LOOP-CONTEXT-MISSING",
        s"assignment to '${assignment.finalTarget.getName()}' would be a continuous assignment; provide the normal process default/priority assignment outside the parameterized loop",
        sourceLocation
      )
    }

    val targetSlices = state.slices.filter(slice => matchesTargetSlice(assignment, slice))
    if (targetSlices.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-LOOP-TARGET-SLICE-UNSUPPORTED",
        s"safe procedural loops require exactly one indexed packed target slice, received ${targetSlices.size}",
        sourceLocation
      )
    }
    val targetSlice = targetSlices.head
    if (!isContiguous(targetSlice, indexName)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-LOOP-NONCONTIGUOUS",
        s"target slice offset '${targetSlice.offset.verilog}' must be '$indexName * ${targetSlice.width.verilog}' (or the commuted form)",
        sourceLocation
      )
    }

    state.slices.foreach { slice =>
      if (!slice.offset.generateIndex.contains(indexName)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-LOOP-INDEX-MISMATCH",
          s"slice offset '${slice.offset.verilog}' references a different loop index",
          slice.sourceLocation.orElse(sourceLocation)
        )
      }
    }

    val storage = storageOf(component)
    reserveName(storage, label, "procedural loop label", sourceLocation)
    reserveName(storage, indexName, "procedural loop index", sourceLocation)
    storage.nextMarkerId += 1
    val marker = s"MORPH_PROC_FOR_${storage.nextMarkerId}"
    assignment.locationString = Option(assignment.locationString)
      .map(value => s"$value $marker")
      .getOrElse(marker)

    storage.loops += ParameterizedProceduralFor(
      label,
      indexName,
      count,
      assignment,
      state.slices.toVector,
      marker,
      sourceLocation
    )
  }

  private def hasOtherDataAssignment(
      captured: DataAssignmentStatement
  ): Boolean = {
    var found = false
    captured.finalTarget.foreachStatements {
      case value: DataAssignmentStatement if (value ne captured) => found = true
      case _                                                     =>
    }
    found
  }

  private def matchesTargetSlice(
      assignment: DataAssignmentStatement,
      slice: ParameterizedStructure.StructuralSlice
  ): Boolean = {
    val matchesWitness = assignment.target match {
      case target: RangedAssignmentFixed =>
        (target.out eq slice.source) &&
        BigInt(target.lo) == slice.offset.default &&
        BigInt(target.getWidth) == slice.width.default
      case _ => containsIdentity(assignment.target, slice.result)
    }
    matchesWitness && !containsIdentity(assignment.source, slice.result)
  }

  private def containsIdentity(
      root: Expression,
      target: Expression
  ): Boolean = {
    val visited = new IdentityHashMap[Expression, java.lang.Boolean]()
    var found = false
    def visit(value: Expression): Unit = {
      if (!found && (value ne null) && !visited.containsKey(value)) {
        visited.put(value, java.lang.Boolean.TRUE)
        if (value eq target) found = true
        else value.foreachExpression(visit)
      }
    }
    visit(root)
    found
  }

  private def isContiguous(
      slice: ParameterizedStructure.StructuralSlice,
      indexName: String
  ): Boolean = {
    val offset = compact(stripOuterParentheses(slice.offset.verilog))
    val width = compact(stripOuterParentheses(slice.width.verilog))
    offset == s"$indexName*$width" || offset == s"$width*$indexName"
  }

  private def stripOuterParentheses(value: String): String = {
    var current = Option(value).getOrElse("").trim
    var changed = true
    while (
      changed && current.length >= 2 &&
      current.head == '(' && current.last == ')'
    ) {
      var depth = 0
      var wrapsAll = true
      var index = 0
      while (index < current.length - 1 && wrapsAll) {
        current.charAt(index) match {
          case '(' => depth += 1
          case ')' =>
            depth -= 1
            if (depth == 0) wrapsAll = false
          case _ =>
        }
        index += 1
      }
      if (wrapsAll) current = current.substring(1, current.length - 1).trim
      else changed = false
    }
    current
  }

  private def compact(value: String): String =
    value.replaceAll("\\s+", "")

  private def validateCount(
      count: ElaborationIntegerExpression,
      sourceLocation: Option[String],
      requireExactDomain: Boolean
  ): Unit = {
    if ((count eq null) || count.verilog == null || count.verilog.trim.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-COUNT-INVALID",
        "parameterized loop count has no portable Verilog expression",
        sourceLocation
      )
    }
    if (
      count.minimum < 0 ||
      count.maximum < count.minimum ||
      count.default < count.minimum || count.default > count.maximum ||
      count.maximum > BigInt(Int.MaxValue)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-COUNT-DOMAIN-UNSUPPORTED",
        s"loop count '${count.verilog}' must have a non-negative Int-sized bounded domain containing its default",
        sourceLocation
      )
    }
    val exactDomain =
      if (requireExactDomain)
        ElabFiniteRange.requireCompleteSymbolicDomain(
          count,
          "parameterized process-loop count",
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-COUNT-EXACT-DOMAIN-REQUIRED"
        )
      else None
    val hasPositiveRepresentative = exactDomain match {
      case Some((exact, admitted)) =>
        exact.evaluations.exists { case (rootValue, result) =>
          admitted.contains(rootValue) && result > 0
        }
      case None => count.maximum > 0
    }
    if (!hasPositiveRepresentative) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-COUNT-DOMAIN-UNSUPPORTED",
        s"loop count '${count.verilog}' has no ${if (requireExactDomain) "exact" else "frontend-analyzed"} positive-domain point for its index-zero representative body",
        sourceLocation
      )
    }
  }

  private def validateName(
      value: String,
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    val portable = "[A-Za-z_][A-Za-z0-9_]*".r
    if (value == null || !portable.pattern.matcher(value).matches()) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-NAME-INVALID",
        s"$role '$value' is not a portable Verilog identifier",
        sourceLocation
      )
    }
  }

  private def reserveName(
      storage: Storage,
      value: String,
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    validateName(value, role, sourceLocation)
    storage.names.get(value).foreach { previous =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-NAME-DUPLICATE",
        s"$role '$value' duplicates another process-loop name${previous.map(" at " + _).getOrElse("")}",
        sourceLocation
      )
    }
    storage.names(value) = sourceLocation
  }

  private def requireCapture(
      operation: String,
      sourceLocation: Option[String]
  ): CaptureState = {
    val state = activeCapture.get()
    if (state eq null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-CONSUMER-OUTSIDE-CAPTURE",
        s"$operation is valid only inside a parameterized range body",
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

  private def rollbackNewStatements(
      scope: ScopeStatement,
      before: Vector[Statement]
  ): Unit =
    scope.statementIterable.toVector
      .filterNot(value => before.exists(_ eq value))
      .reverse
      .foreach(_.removeStatement())

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
