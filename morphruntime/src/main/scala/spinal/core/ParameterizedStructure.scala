package spinal.core

import morphhdl.runtime.ParameterizedVerilogMode

import java.util.IdentityHashMap

import scala.collection.Seq
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core.internals._

/** Opaque snapshot of the ordinary SpinalHDL statements created by one
  * structural body. Frontend code can capture and register it, while the MorphHDL-owned external lowering inspects the native AST objects.
  */
final class ParameterizedStructuralBlock private[core] (
    private[core] var statements: Vector[Statement],
    private[core] var declarations: Vector[BaseType],
    private[core] var assignments: Vector[DataAssignmentStatement],
    private[core] var memories: Vector[Mem[_]],
    private[core] var children: Vector[Component],
    private[core] var slices: Vector[ParameterizedStructure.StructuralSlice],
    private[core] var vecIndices: Vector[ParameterizedStructure.StructuralVecIndex],
    private[core] var memoryIndices: Vector[ParameterizedStructure.StructuralMemoryIndex],
    private[core] var scalarOperators: Vector[ParameterizedStructure.StructuralScalarOperator],
    private[core] var regions: Vector[ParameterizedStructure.StructuralRegion],
    private[core] val sourceLocation: Option[String]
) {

  /** Extend this exact captured owner with native statements elaborated later.
    * The supplement has already passed the same capture validator, and is
    * merged by identity before final structural ownership analysis.
    */
  private[core] def append(
      supplement: ParameterizedStructuralBlock
  ): Unit = synchronized {
    if (supplement == null)
      throw new IllegalArgumentException("structural owner supplement must not be null")
    statements = statements ++ supplement.statements
    declarations = declarations ++ supplement.declarations
    assignments = assignments ++ supplement.assignments
    memories = memories ++ supplement.memories
    children = children ++ supplement.children
    slices = slices ++ supplement.slices
    vecIndices = vecIndices ++ supplement.vecIndices
    memoryIndices = memoryIndices ++ supplement.memoryIndices
    scalarOperators = scalarOperators ++ supplement.scalarOperators
    regions = regions ++ supplement.regions
  }
}

/** Exact handle for extending one already captured structural branch.
  *
  * The handle contains no rendered name or source-position key. Its component,
  * capture and elaboration-root identities must all still match when a native
  * library helper appends statements to that branch.
  */
final class ParameterizedStructuralOwner private[core] (
    private[core] val component: Component,
    private[core] val captureId: Long,
    private[core] val root: ElaborationIntegerParameterRoot,
    private[core] val admitted: Set[BigInt],
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
    val blocksByCaptureId = mutable.LinkedHashMap.empty[
      Long,
      ParameterizedStructuralBlock
    ]
    var nextPendingId = 0L
    var nextCaptureId = 0L
    var nextVecAliasId = 0L
    var assignmentValidationScheduled = false
  }

  private final class CaptureState(
      val component: Component,
      val sourceLocation: Option[String],
      val id: Long,
      val ownerRoot: Option[ElaborationIntegerParameterRoot]
  ) {
    val slices = ArrayBuffer.empty[StructuralSlice]
    val vecIndices = ArrayBuffer.empty[StructuralVecIndex]
    val memoryIndices = ArrayBuffer.empty[StructuralMemoryIndex]
    val regions = ArrayBuffer.empty[StructuralRegion]
  }

  private[core] final case class StructuralSlice(
      source: BitVector,
      result: BitVector,
      assignment: DataAssignmentStatement,
      offset: ElaborationIntegerExpression,
      width: ElaborationIntegerExpression,
      sourceLocation: Option[String],
      finiteIndexToken: Option[ElabFiniteIndexToken] = None
  )

  /** Identity-retained alias for one native static Vec element selected by a
    * generate index.
    *
    * `selected` remains the authoritative witnessed carrier. `result` is a
    * fresh, directionless capture-local clone. Publication substitutes only
    * that unique alias, never the witnessed carrier; the same mechanism is
    * consequently valid when the caller uses the returned element on either
    * side of an assignment.
    */
  private[core] final class StructuralVecIndex(
      val vector: Vec[_],
      val selected: Data,
      val result: Data,
      val staticAccess: Option[ParameterizedVecStaticIndex],
      val index: ElaborationIntegerExpression,
      val finiteIndexToken: Option[ElabFiniteIndexToken],
      val sourceLocation: Option[String],
      val affineRead: Option[ElabFiniteAffineVecRead] = None
  )

  /** One exact native asynchronous read selected by a retained generate index.
    * The ordinary Mem port remains authoritative; publication changes only its
    * witnessed constant address into the enclosing Verilog generate index.
    */
  private[core] final case class StructuralMemoryIndex(
      memory: Mem[_],
      port: MemReadAsync,
      address: Expression with WidthProvider,
      addressWitness: BitVectorLiteral,
      addressAssignment: Option[DataAssignmentStatement],
      readBits: Bits,
      readBitsAssignment: DataAssignmentStatement,
      selected: Data,
      selectedAssignments: Vector[DataAssignmentStatement],
      selectedSupportAssignments: Vector[Vector[DataAssignmentStatement]],
      index: ElaborationIntegerExpression,
      sourceLocation: Option[String],
      finiteIndexToken: Option[ElabFiniteIndexToken] = None
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
      finiteIndexToken: Option[ElabFiniteIndexToken],
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

  /** One Bool type-node operator reached from an exact captured native
    * statement. Ownership is retained only through the result, direct driver,
    * operator and ordered operand identities; emitted names are replay
    * evidence in the external backend, never lookup authority.
    */
  private[core] final case class StructuralScalarOperator(
      result: BaseType,
      assignment: DataAssignmentStatement,
      operator: Operator,
      sources: Vector[Expression]
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

  private final case class CapturedStatement(
      statement: Statement,
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
      ParameterizedVerilogMode.isEnabledInCurrentElaboration

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
  )(body: => Unit): ParameterizedStructuralBlock =
    captureBlockWithOwnerRoot(component, sourceLocation, None)(body)

  /** Capture a body whose active capture id is owned by one exact root.
    * Installing the branch-domain constraint and the root binding is one
    * atomic operation: independently nested constraints can never lend their
    * root to another active capture.
    */
  private[spinal] def captureExactBlock(
      component: Component,
      root: ElaborationIntegerParameterRoot,
      admitted: Set[BigInt],
      sourceLocation: Option[String]
  )(body: => Unit): ParameterizedStructuralBlock =
    ElaborationDomainContext.withAdmitted(root, admitted, sourceLocation) {
      captureBlockWithOwnerRoot(component, sourceLocation, Some(root))(body)
    }

  private def captureBlockWithOwnerRoot(
      component: Component,
      sourceLocation: Option[String],
      ownerRoot: Option[ElaborationIntegerParameterRoot]
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
      storage.nextCaptureId,
      ownerRoot
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
      val scalarOperators = capturedScalarOperators(
        component,
        hardwareStatements.toVector
      )
      memoryPorts
        .find(port =>
          !memories.exists(_ eq port.mem) &&
            !state.memoryIndices.exists(value => value.port eq port) &&
            // An unrelated ordinary read of the same existing Mem is legal
            // only while that captured body still contains the exact indexed
            // port which proves ownership of the foreign memory. Removing the
            // retained port and replacing it with same-name/text hardware does
            // not satisfy this identity co-presence requirement.
            !state.memoryIndices.exists(value =>
              (value.memory eq port.mem) &&
                memoryPorts.exists(candidate => candidate eq value.port)
            )
        )
        .foreach { port =>
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
      children.collect { case value: BlackBox => value }.foreach { value =>
        // A same-name reference is an explicit Verilog module identity,
        // not a second implementation. Retain its exact child object for
        // mandatory final-owner termination and interface validation.
        val typedSelfReference =
          ownerRoot.nonEmpty && !component.isInstanceOf[BlackBox] &&
            (value.parent eq component) && value.isBlackBox &&
            Option(component.definitionName).exists(_.nonEmpty) &&
            value.definitionName == component.definitionName &&
            value.impl == null && value.listRTLPath.isEmpty &&
            value.children.isEmpty &&
            ParameterizedBlackBoxGenericRegistry.recordsOf(value).exists {
              case binding: ParameterizedBlackBoxIntegerGeneric =>
                binding.parameters.nonEmpty
              case _ => false
            }
        if (!typedSelfReference)
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-BLACKBOX-UNSUPPORTED",
            s"structural body instantiated unsupported BlackBox '${value.getName()}'; only typed direct self-references without separate RTL may be captured",
            sourceLocation
          )
      }
      if (
        declarations.isEmpty && assignments.isEmpty && memories.isEmpty &&
        memoryPorts.isEmpty && children.isEmpty && state.slices.isEmpty &&
        state.vecIndices.isEmpty && state.memoryIndices.isEmpty &&
        state.regions.isEmpty
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

      // A directionless finite-Vec alias may be the target of an ordinary
      // whole-leaf assignment. The native witness graph contains no bridge
      // from that alias to the output carrier (publication substitutes the
      // alias directly with the exact generated packed slice), so permit only
      // those exact reachable carrier leaves to remain undriven until the
      // structural lowering consumes the retained identity. RHS-only aliases
      // do not grant this exception, and packed/partial alias targets remain
      // subject to the backend's fail-closed whole-leaf validation.
      state.vecIndices.foreach { access =>
        val aliasLeaves = access.result.flatten.toVector
        val aliasPaths = access.result.flattenLocalName.toVector
        val writtenLeafIndices = aliasLeaves.indices.filter { leafIndex =>
          assignments.exists(value =>
            (value.finalTarget eq aliasLeaves(leafIndex)) &&
              (value.target eq aliasLeaves(leafIndex))
          )
        }
        writtenLeafIndices.foreach { leafIndex =>
          (access.index.minimum.toInt to access.index.maximum.toInt).foreach { elementIndex =>
            val element = access.vector.vec(elementIndex).asInstanceOf[Data]
            val leaves = element.flatten.toVector
            val paths = element.flattenLocalName.toVector
            if (
              leaves.size != aliasLeaves.size || paths != aliasPaths ||
              leaves(leafIndex).getClass != aliasLeaves(leafIndex).getClass ||
              leaves(leafIndex).getBitsWidth != aliasLeaves(leafIndex).getBitsWidth
            ) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-LAYOUT-MISMATCH",
                s"structural Vec LHS carrier element $elementIndex no longer matches its exact alias leaf layout",
                access.sourceLocation.orElse(sourceLocation)
              )
            }
            leaves(leafIndex).addTag(allowFloating)
          }
        }
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
        state.memoryIndices.toVector,
        scalarOperators,
        state.regions.toVector,
        sourceLocation
      )
      storage.blocksByCaptureId.get(state.id) match {
        case Some(existing) if existing ne result =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CAPTURE-ID-CONFLICT",
            s"structural capture id ${state.id} was registered more than once",
            sourceLocation
          )
        case _ => storage.blocksByCaptureId(state.id) = result
      }
      accepted = true
      result
    } finally {
      if (!accepted) {
        rollbackNewStatements(component, beforeHardwareStatements)
        rollbackNewChildren(component, beforeChildren)
      }
    }
  }

  /** Retain portable Bool operator helpers reached by exact captured native
    * statements, including When conditions whose anonymous type-node
    * declaration and driver were created outside the statement's root-scope
    * list. A helper is admitted only with one live whole-target driver and an
    * exact supported operator/operand graph.
    */
  private[core] def capturedScalarOperators(
      component: Component,
      statements: Vector[Statement]
  ): Vector[StructuralScalarOperator] = {
    if (component == null || statements == null || statements.isEmpty)
      return Vector.empty

    val live = new IdentityHashMap[Statement, java.lang.Boolean]()
    allStatementsOf(component).foreach(value => live.put(value, java.lang.Boolean.TRUE))
    val captured = new IdentityHashMap[Statement, java.lang.Boolean]()
    statements.foreach(value => captured.put(value, java.lang.Boolean.TRUE))
    def supported(operator: Operator): Boolean = operator match {
      case _: Operator.Bool.And      => true
      case _: Operator.Bool.Equal    => true
      case _: Operator.Bool.Not      => true
      case _: Operator.Bool.NotEqual => true
      case _: Operator.Bool.Or       => true
      case _: Operator.Bool.Xor      => true
      case _                         => false
    }

    def directSources(operator: Operator): Vector[Expression] = {
      val sources = ArrayBuffer.empty[Expression]
      operator.foreachExpression(value => sources += value)
      sources.toVector
    }

    def scan(
        seeds: Vector[Statement]
    ): (
        Vector[StructuralScalarOperator],
        IdentityHashMap[BaseType, java.lang.Boolean]
    ) = {
      val visited = new IdentityHashMap[Expression, java.lang.Boolean]()
      val retained = new IdentityHashMap[BaseType, java.lang.Boolean]()
      val referenced = new IdentityHashMap[BaseType, java.lang.Boolean]()
      val values = ArrayBuffer.empty[StructuralScalarOperator]

      def visit(value: Expression): Unit = {
        if (
          value != null &&
          visited.put(value, java.lang.Boolean.TRUE) == null
        ) {
          value match {
            case result: BaseType =>
              referenced.put(result, java.lang.Boolean.TRUE)
              if (
                result.isTypeNode &&
                result.getTypeObject == TypeBool &&
                result.getBitsWidth == 1 &&
                live.containsKey(result) &&
                captured.containsKey(result) &&
                !retained.containsKey(result)
              ) {
                val assignments = ArrayBuffer.empty[DataAssignmentStatement]
                result.foreachStatements {
                  case assignment: DataAssignmentStatement
                      if (assignment.finalTarget eq result) &&
                        (assignment.target eq result) &&
                        live.containsKey(assignment) &&
                        captured.containsKey(assignment) =>
                    assignments += assignment
                  case _ =>
                }
                assignments.toVector match {
                  case Vector(assignment) if assignment.source.isInstanceOf[Operator] =>
                    val operator = assignment.source.asInstanceOf[Operator]
                    val sources = directSources(operator)
                    if (
                      supported(operator) &&
                      sources.nonEmpty &&
                      sources.forall {
                        case source: BaseType =>
                          source.getTypeObject == TypeBool &&
                          source.getBitsWidth == 1
                        case _ => false
                      }
                    ) {
                      retained.put(result, java.lang.Boolean.TRUE)
                      values += StructuralScalarOperator(
                        result,
                        assignment,
                        operator,
                        sources
                      )
                    }
                  case _ =>
                }
              }
            case _ =>
          }
          value.foreachExpression(visit)
        }
      }

      seeds.foreach(_.foreachExpression(visit))
      values.toVector -> referenced
    }

    val (direct, _) = scan(statements)
    direct.foreach { value =>
      value.result.setAsVital().dontSimplifyIt().noBackendCombMerge()
      // The result identity alone cannot preserve its replay proof when a
      // later native simplification replaces one anonymous Bool operand. Keep
      // the exact ordered BaseType operands live as graph evidence; they are
      // still only revalidated (never promoted or looked up by name) by the
      // publication backend.
      value.sources.foreach {
        case source: BaseType =>
          source.setAsVital().dontSimplifyIt()
          if (source.isComb) source.noBackendCombMerge()
          if (source.isReg) source.addTag(noBackendSyncMerge)
        case _ =>
      }
    }
    direct
  }

  /** Retain the exact active structural owner for a later native helper.
    *
    * The caller supplies its typed control carrier explicitly so the handle
    * can retain the current admitted root values. This is intentionally not a
    * lookup by a generated label, Scala source position or emitted signal.
    */
  def currentOwner(
      control: ElabInt,
      role: String
  ): ParameterizedStructuralOwner = {
    if (control == null)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-CONTROL-NULL",
        s"$role requires a non-null typed control carrier"
      )
    if (!captureEnabled)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-CAPTURE-DISABLED",
        s"$role requires parameterized structural capture",
        control.sourceLocation
      )
    val state = activeCapture.get()
    val component =
      if (state ne null) state.component
      else
        Option(Component.current).getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-COMPONENT-MISSING",
            s"$role requires an active Component",
            control.sourceLocation
          )
        }
    val exact = control.expression.exactDomain.getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-DOMAIN-MISSING",
        s"$role control '${control.expression.verilog}' lacks exact single-root evidence",
        control.sourceLocation
      )
    }
    if (
      (state ne null) &&
      (!state.ownerRoot.exists(_ eq exact.root) ||
        !ElaborationDomainContext.constrains(exact.root))
    )
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-ACTIVE-ROOT-MISMATCH",
        s"$role control '${control.expression.verilog}' is not constrained by the active structural branch",
        control.sourceLocation.orElse(exact.root.sourceLocation)
      )
    val admitted = ElaborationDomainContext.admitted(exact)
    if (admitted.isEmpty)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-DOMAIN-EMPTY",
        s"$role control '${control.expression.verilog}' has an empty active domain",
        control.sourceLocation
      )
    storageOf(component)
    new ParameterizedStructuralOwner(
      component,
      if (state eq null) 0L else state.id,
      exact.root,
      admitted,
      control.sourceLocation
    )
  }

  /** Elaborate one validated native supplement inside an exact earlier owner.
    *
    * `captureBlock` remains the authority for accepted hardware. Consequently
    * an extension cannot smuggle a Scala-only side effect into publication,
    * and all declarations, assignments, memories, ports and nested regions are
    * attributed to the original branch before the backend computes ownership.
    */
  def captureInto[T](
      owner: ParameterizedStructuralOwner,
      control: ElabInt,
      role: String
  )(body: => T): T = {
    if (owner == null)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-NULL",
        s"$role requires a retained structural owner",
        Option(control).flatMap(_.sourceLocation)
      )
    if (control == null)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-CONTROL-NULL",
        s"$role requires a non-null typed control carrier",
        owner.sourceLocation
      )
    if (activeCapture.get() ne null)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-NESTED-EXTENSION",
        s"$role cannot extend an owner while another structural capture is active",
        owner.sourceLocation.orElse(control.sourceLocation)
      )
    val current = Option(Component.current).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-COMPONENT-MISSING",
        s"$role requires an active Component",
        owner.sourceLocation.orElse(control.sourceLocation)
      )
    }
    val expectedComponent = owner.component
    if (
      (current ne expectedComponent) &&
      (expectedComponent.parent ne current)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-COMPONENT-MISMATCH",
        s"$role attempted to extend a structural owner from an unrelated Component",
        owner.sourceLocation.orElse(control.sourceLocation)
      )
    }
    val exact = control.expression.exactDomain.getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-DOMAIN-MISSING",
        s"$role control '${control.expression.verilog}' lacks exact single-root evidence",
        control.sourceLocation.orElse(owner.sourceLocation)
      )
    }
    if (exact.root ne owner.root)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-ROOT-MISMATCH",
        s"$role control '${control.expression.verilog}' has a different exact root from its retained owner",
        control.sourceLocation.orElse(owner.sourceLocation)
      )
    if (!owner.admitted.subsetOf(exact.evidenceValues))
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-EVIDENCE-INCOMPLETE",
        s"$role owner admits root values without exact control evidence",
        control.sourceLocation.orElse(owner.sourceLocation)
      )
    val storage = storageOption(expectedComponent).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-STORAGE-MISSING",
        s"$role component lost its structural capture storage",
        owner.sourceLocation.orElse(control.sourceLocation)
      )
    }
    val target =
      if (owner.captureId == 0L) None
      else
        Some(
          storage.blocksByCaptureId.getOrElse(
            owner.captureId,
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-CAPTURE-MISSING",
              s"$role capture ${owner.captureId} is not registered on its retained Component",
              owner.sourceLocation.orElse(control.sourceLocation)
            )
          )
        )

    var result: Option[T] = None
    def captureSupplement(): ParameterizedStructuralBlock =
      captureExactBlock(
        expectedComponent,
        owner.root,
        owner.admitted,
        owner.sourceLocation.orElse(control.sourceLocation)
      ) {
        result = Some(body)
        ()
      }
    val supplement =
      if (current eq expectedComponent) captureSupplement()
      else expectedComponent.rework(captureSupplement())
    target match {
      case Some(block) =>
        block.append(supplement)
        // `captureInto` may reopen a Component whose original pre-pop task has
        // already run. Re-evaluate the complete exact owner graph after each
        // supplement so the final mutually-exclusive alternative can receive
        // the same narrowly proven allowOverride authorization as statements
        // captured during initial construction.
        authorizeMutuallyExclusiveAssignments(expectedComponent)
      case None =>
        // Module-scope symbolic alternatives (for example a domain proven
        // DEPTH >= 2) have no enclosing structural block. Their directly
        // emitted native statements already remain at module scope; promote
        // only the validated nested generate regions, exactly once.
        supplement.regions.foreach { region =>
          if (storage.regions.exists(_ eq region))
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-REGION-DUPLICATE",
              s"$role attempted to promote one nested region more than once",
              owner.sourceLocation.orElse(control.sourceLocation)
            )
          storage.regions += region
        }
    }
    result.getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-RESULT-MISSING",
        s"$role extension did not execute",
        owner.sourceLocation.orElse(control.sourceLocation)
      )
    }
  }

  /** Prove that a set of retained branch owners exactly and uniquely covers
    * the current typed domain before a later helper extends those branches.
    */
  def requireOwnerCoverage(
      component: Component,
      control: ElabInt,
      owners: Seq[ParameterizedStructuralOwner],
      role: String
  ): Unit = {
    if (component == null || control == null || owners == null)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-COVERAGE-NULL",
        s"$role requires a non-null Component, control and owner collection",
        Option(control).flatMap(_.sourceLocation)
      )
    val exact = control.expression.exactDomain.getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-DOMAIN-MISSING",
        s"$role control '${control.expression.verilog}' lacks exact single-root evidence",
        control.sourceLocation
      )
    }
    val expected = ElaborationDomainContext.admitted(exact)
    val retained = owners.filter(_ != null).toVector
    if (retained.isEmpty)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-COVERAGE-EMPTY",
        s"$role has no retained structural owner",
        control.sourceLocation
      )
    var covered = Set.empty[BigInt]
    retained.foreach { owner =>
      if (owner.component ne component)
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-COMPONENT-MISMATCH",
          s"$role includes an owner from a different Component",
          owner.sourceLocation.orElse(control.sourceLocation)
        )
      if (owner.root ne exact.root)
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-ROOT-MISMATCH",
          s"$role includes an owner from a different exact root",
          owner.sourceLocation.orElse(control.sourceLocation)
        )
      val overlap = covered intersect owner.admitted
      if (overlap.nonEmpty)
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-COVERAGE-OVERLAP",
          s"$role owners overlap at ${overlap.toVector.sorted.mkString(", ")}",
          owner.sourceLocation.orElse(control.sourceLocation)
        )
      covered ++= owner.admitted
    }
    if (covered != expected) {
      val missing = expected -- covered
      val escaped = covered -- expected
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-COVERAGE-MISMATCH",
        s"$role owner coverage is incomplete or escaped (missing: ${missing.toVector.sorted
            .mkString(", ")}; escaped: ${escaped.toVector.sorted.mkString(", ")})",
        control.sourceLocation
      )
    }
  }

  /** Record the one-bit native slice selected by an exact finite index.
    *
    * Generic symbolic slices must compare their widest offset with the
    * source's narrowest width because their expressions may vary
    * independently. An [[ElabFiniteIndex]] instead carries the private count
    * which created its generate index. Admit the correlated one-bit slice only
    * when the source's retained packed width is the exact same pointwise
    * function of the same declaration root. No native witness, rendered name
    * or reconstructed parameter schema is authority for this exception.
    */
  private[core] def recordFiniteIndexSlice(
      source: Bits,
      result: Bits,
      index: ElaborationIntegerExpression,
      count: ElaborationIntegerExpression,
      finiteIndexToken: ElabFiniteIndexToken,
      sourceLocation: Option[String]
  ): Unit = {
    val state = requireCapture("finite-index packed bit", sourceLocation)
    if ((source eq null) || (result eq null)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FINITE-INDEX-NULL",
        "finite-index packed bit requires non-null source and result",
        sourceLocation
      )
    }
    if (index == null || count == null || finiteIndexToken == null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FINITE-INDEX-DOMAIN-NULL",
        "finite-index packed bit requires non-null index/count expressions and opaque range identity",
        sourceLocation
      )
    }
    if (index.generateIndex.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FINITE-INDEX-NOT-INDEXED",
        s"finite index '${index.verilog}' does not depend on the active generate index",
        sourceLocation
      )
    }
    val generateIndex = index.generateIndex.get
    if (
      index.verilog != generateIndex || index.parameters.nonEmpty ||
      index.completedParameterRoots.nonEmpty || index.exactDomain.nonEmpty ||
      index.projectionProvenance.nonEmpty
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FINITE-INDEX-SHAPE-MISMATCH",
        s"finite index '${index.verilog}' must be the exact rootless direct generate index '$generateIndex'",
        sourceLocation
      )
    }
    val component = Option(Component.current).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FINITE-INDEX-COMPONENT-MISSING",
        "finite-index packed bit requires an active Component",
        sourceLocation
      )
    }
    if (
      (state.component ne component) || (source.component ne component) ||
      (result.component ne component)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FINITE-INDEX-COMPONENT-MISMATCH",
        "finite-index packed source, result and capture must belong to the active Component",
        sourceLocation
      )
    }
    if (
      index.default != 0 || index.minimum != 0 ||
      count.minimum < 1 || index.maximum != count.maximum - 1 ||
      index.default >= source.getBitsWidth || result.getBitsWidth != 1
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FINITE-INDEX-DOMAIN-MISMATCH",
        s"finite index '${index.verilog}' in [${index.minimum}, ${index.maximum}] must select one witnessed bit inside its positive enclosing count '${count.verilog}' in [${count.minimum}, ${count.maximum}]",
        sourceLocation.orElse(count.sourceLocation)
      )
    }

    val retainedWidth = ParameterizedVec
      .packedWidthExpressionOf(source)
      .orElse(ParameterizedWidth.expressionOf(source))
      .getOrElse {
        fail(
          "SPINAL-ELAB-FINITE-INDEX-BITS-SOURCE-WIDTH-MISSING",
          s"finite-index Bits source has only native witness width ${source.getBitsWidth}; exact typed width provenance is required",
          sourceLocation.orElse(count.sourceLocation)
        )
      }
    val projectedWidth = ElabInt.projectExpression(
      retainedWidth,
      "finite-index Bits source width"
    )
    val projectedCount = ElabInt.projectExpression(
      count,
      "finite-index Bits enclosing count"
    )
    ElabFiniteRange.requireCompleteSymbolicDomain(
      projectedWidth,
      "finite-index Bits source width",
      "SPINAL-ELAB-FINITE-INDEX-BITS-EXACT-DOMAIN-REQUIRED"
    )
    ElabFiniteRange.requireCompleteSymbolicDomain(
      projectedCount,
      "finite-index Bits enclosing count",
      "SPINAL-ELAB-FINITE-INDEX-BITS-EXACT-DOMAIN-REQUIRED"
    )
    if (
      !ElabFiniteRange.equivalentLogicalCount(
        projectedWidth,
        projectedCount
      )
    ) {
      fail(
        "SPINAL-ELAB-FINITE-INDEX-BITS-WIDTH-MISMATCH",
        s"finite-index Bits source width '${projectedWidth.verilog}' does not match enclosing count '${projectedCount.verilog}'",
        projectedWidth.sourceLocation.orElse(projectedCount.sourceLocation)
      )
    }

    val assignment = retainSliceAssignment(source, result, sourceLocation)
    state.slices += StructuralSlice(
      source,
      result,
      assignment,
      index,
      ElabInt.literal(1).expression,
      sourceLocation,
      Some(finiteIndexToken)
    )
  }

  /** Record one symbolic fixed-width packed slice selected at its witness. */
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
    validateSliceCompleteDomain(
      source,
      offset,
      width,
      sourceLocation,
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-DOMAIN-UNSUPPORTED"
    )
    val assignment = retainSliceAssignment(source, result, sourceLocation)
    state.slices += StructuralSlice(
      source,
      result,
      assignment,
      offset,
      width,
      sourceLocation
    )
  }

  /** Require one indexed packed slice to fit the source at every admitted
    * parameter value and every generated index. Exact typed-domain tables do
    * not enumerate the generate index, so correlation cannot be inferred from
    * a shared parameter root; compare the slice's widest reach against the
    * source's narrowest retained width.
    */
  private[core] def validateSliceCompleteDomain(
      source: BitVector,
      offset: ElaborationIntegerExpression,
      width: ElaborationIntegerExpression,
      sourceLocation: Option[String],
      failureCode: String
  ): Unit = {
    val sourceWidthExpression =
      (source match {
        case bits: Bits => ParameterizedVec.packedWidthExpressionOf(bits)
        case _          => None
      }).orElse(ParameterizedWidth.expressionOf(source))
        .getOrElse(ElabInt.literal(source.getBitsWidth).expression)
    if (offset.maximum + width.maximum > sourceWidthExpression.minimum) {
      fail(
        failureCode,
        s"slice '${offset.verilog} +: ${width.verilog}' reaches offset [${offset.minimum}, ${offset.maximum}] and width [${width.minimum}, ${width.maximum}], beyond source width '${sourceWidthExpression.verilog}' in [${sourceWidthExpression.minimum}, ${sourceWidthExpression.maximum}] over its complete domain",
        sourceLocation.orElse(sourceWidthExpression.sourceLocation)
      )
    }
  }

  /** Keep the exact native carrier assignment which materializes one witnessed
    * packed slice. Structural publication is then able to rewrite that one
    * assignment instead of every coincident textual slice in the captured
    * body.
    */
  private[core] def retainSliceAssignment(
      source: BitVector,
      result: BitVector,
      sourceLocation: Option[String]
  ): DataAssignmentStatement = {
    result.dontSimplifyIt()
    sliceAssignment(source, result, sourceLocation)
  }

  /** Resolve the one exact native assignment which materializes a witnessed
    * packed slice without changing its simplification policy. Procedural range
    * capture needs the identity proof during classification, but must leave the
    * carrier eligible for native inlining into the marked process statement.
    */
  private[core] def sliceAssignment(
      source: BitVector,
      result: BitVector,
      sourceLocation: Option[String]
  ): DataAssignmentStatement = {
    val assignments = ArrayBuffer.empty[DataAssignmentStatement]
    result.foreachStatements {
      case value: DataAssignmentStatement
          if (value.finalTarget eq result) &&
            expressionContains(value.source, source) =>
        assignments += value
      case _ =>
    }
    if (assignments.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-LINEAGE-MISMATCH",
        s"structural packed slice result retains ${assignments.size} exact native assignments from its source; exactly one is required",
        sourceLocation
      )
    }
    assignments.head
  }

  /** Record one internal static Vec element selected by a generate index. */
  private[spinal] def recordVecIndex[T <: Data](
      vector: Vec[T],
      selected: T,
      index: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  ): T =
    recordVecIndexImpl(
      vector,
      selected,
      index,
      None,
      sourceLocation
    )

  /** Exact typed finite-range counterpart. The token is not a public selector
    * attribute: it is the opaque identity of the one foreach invocation which
    * created both this index and its eventual StructuralFor.
    */
  private[core] def recordVecIndex[T <: Data](
      vector: Vec[T],
      selected: T,
      index: ElaborationIntegerExpression,
      finiteIndexToken: ElabFiniteIndexToken,
      sourceLocation: Option[String]
  ): T = {
    if (finiteIndexToken == null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-FINITE-INDEX-TOKEN-NULL",
        "typed finite Vec access requires one non-null opaque range identity",
        sourceLocation
      )
    }
    recordVecIndexImpl(
      vector,
      selected,
      index,
      Some(finiteIndexToken),
      sourceLocation
    )
  }

  private def recordVecIndexImpl[T <: Data](
      vector: Vec[T],
      selected: T,
      index: ElaborationIntegerExpression,
      finiteIndexToken: Option[ElabFiniteIndexToken],
      sourceLocation: Option[String],
      affineRead: Option[ElabFiniteAffineVecRead] = None
  ): T = {
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
    val carrierLength = ParameterizedVec
      .shapeOf(vector)
      .map(_.carrierCapacity)
      .getOrElse(vector.carrierLength)
    if (
      index.default < 0 || index.default >= carrierLength ||
      index.minimum < 0 || index.maximum >= carrierLength
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-DOMAIN-UNSUPPORTED",
        s"Vec index '${index.verilog}' reaches [${index.minimum}, ${index.maximum}], outside 0 until $carrierLength",
        sourceLocation
      )
    }
    val retained = createVecIndexAliasImpl(
      vector,
      selected,
      index,
      finiteIndexToken,
      sourceLocation,
      affineRead
    )
    state.vecIndices += retained
    retained.result.asInstanceOf[T]
  }

  private[core] def recordAffineVecRead[T <: Data](
      vector: Vec[T],
      selected: T,
      index: ElaborationIntegerExpression,
      token: ElabFiniteIndexToken,
      evidence: ElabFiniteAffineVecRead,
      sourceLocation: Option[String]
  ): T = {
    if (evidence == null || !evidence.matches(vector, index, token))
      fail(
        "SPINAL-ELAB-FINITE-AFFINE-EVIDENCE-MISMATCH",
        "affine Vec read lost its exact vector, selector or finite-range evidence",
        sourceLocation
      )
    recordVecIndexImpl(vector, selected, index, Some(token), sourceLocation, Some(evidence))
  }

  /** Create the distinct identity anchor shared by the typed runtime and the
    * optional structural frontend. The caller remains responsible for adding
    * the returned record to its exact active capture.
    */
  private[core] def createVecIndexAlias[T <: Data](
      vector: Vec[T],
      selected: T,
      index: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  ): StructuralVecIndex =
    createVecIndexAliasImpl(
      vector,
      selected,
      index,
      None,
      sourceLocation
    )

  private def createVecIndexAliasImpl[T <: Data](
      vector: Vec[T],
      selected: T,
      index: ElaborationIntegerExpression,
      finiteIndexToken: Option[ElabFiniteIndexToken],
      sourceLocation: Option[String],
      affineRead: Option[ElabFiniteAffineVecRead] = None
  ): StructuralVecIndex = {
    if (
      finiteIndexToken == null ||
      finiteIndexToken.exists(_ == null)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-FINITE-INDEX-TOKEN-NULL",
        "structural Vec alias requires a non-null opaque-token option",
        sourceLocation
      )
    }
    val component = Option(Component.current).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-COMPONENT-MISSING",
        "structural Vec alias requires an active Component",
        sourceLocation
      )
    }
    if ((vector.component ne component) || (selected.component ne component)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-COMPONENT-MISMATCH",
        "structural Vec alias and its witnessed element must belong to the active Component",
        sourceLocation
      )
    }

    // The alias itself no longer reads the witnessed carrier in the native
    // graph. Preserve every exact element that the finite selector may reach,
    // so an internal (non-port) Vec cannot disappear before packed structural
    // publication consumes its retained identity.
    (index.minimum.toInt to index.maximum.toInt).foreach { elementIndex =>
      vector.vec(elementIndex).asInstanceOf[Data].flatten.foreach { leaf =>
        leaf.setAsVital()
        leaf.dontSimplifyIt()
        if (leaf.isComb) leaf.noBackendCombMerge()
        if (leaf.isReg) leaf.addTag(noBackendSyncMerge)
      }
    }

    val selectedLeaves = selected.flatten.toVector
    val selectedPaths = selected.flattenLocalName.toVector
    if (selectedLeaves.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-ELEMENT-EMPTY",
        "structural Vec elements must contain at least one packed leaf",
        sourceLocation
      )
    }
    val result = ParameterizedWidth.cloneOf(selected)
    result.setAsDirectionLess()
    result.setName(nextVecAliasName(component))
    result.dontSimplifyIt()
    result.flatten.foreach { leaf =>
      leaf.setAsVital()
      // The alias is intentionally undriven in the native witness graph. Its
      // only semantics are the exact capture-local substitution performed by
      // MorphHDL after native checks and emission.
      leaf.addTag(allowFloating)
    }
    val resultLeaves = result.flatten.toVector
    val resultPaths = result.flattenLocalName.toVector
    if (
      resultLeaves.size != selectedLeaves.size ||
      selectedPaths.size != selectedLeaves.size ||
      resultPaths != selectedPaths
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-LAYOUT-MISMATCH",
        s"structural Vec alias has ${resultLeaves.size} leaves and paths ${resultPaths
            .mkString("[", ", ", "]")} for a ${selectedLeaves.size}-leaf element with paths ${selectedPaths
            .mkString("[", ", ", "]")}",
        sourceLocation
      )
    }
    resultLeaves.zip(selectedLeaves).foreach { case (resultLeaf, selectedLeaf) =>
      if (
        (resultLeaf eq selectedLeaf) ||
        resultLeaf.getClass != selectedLeaf.getClass ||
        resultLeaf.getBitsWidth != selectedLeaf.getBitsWidth
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-LAYOUT-MISMATCH",
          "structural Vec alias changed one witnessed element leaf identity, type or width",
          sourceLocation
        )
      }
    }

    val retained = new StructuralVecIndex(
      vector,
      selected,
      result,
      retainedStaticVecAccess(vector, selected, index, sourceLocation),
      index,
      finiteIndexToken,
      sourceLocation,
      affineRead
    )
    retained
  }

  private def nextVecAliasName(component: Component): String = {
    val storage = storageOf(component)
    storage.synchronized {
      storage.nextVecAliasId += 1
      s"morphhdl_structural_vec_alias_${storage.nextVecAliasId}"
    }
  }

  /** Retain the exact typed static-access record created immediately before a
    * structural Vec selection is registered. Other constant accesses to the
    * same carrier element, including accesses in sibling alternatives, are not
    * evidence for this selection.
    */
  private[core] def retainedStaticVecAccess(
      vector: Vec[_],
      selected: Data,
      index: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  ): Option[ParameterizedVecStaticIndex] =
    ParameterizedVec.shapeOf(vector).map { _ =>
      val matching = ParameterizedVec.operationsOf(vector).reverse.collect {
        case value: ParameterizedVecStaticIndex
            if value.index == index.default.toInt &&
              (value.selected eq selected) =>
          value
      }
      matching.headOption.getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-STATIC-EVIDENCE-MISSING",
          s"structural Vec witness ${index.default} lost its exact typed static-access record",
          sourceLocation
        )
      }
    }

  /** Record one native asynchronous read of an existing Mem selected by the
    * enclosing structural generate index.
    */
  private[spinal] def recordMemoryIndex(
      memory: Mem[_],
      port: MemReadAsync,
      selected: Data,
      index: ElaborationIntegerExpression,
      finiteIndexToken: ElabFiniteIndexToken,
      sourceLocation: Option[String]
  ): Unit = {
    val state = requireCapture("Mem index", sourceLocation)
    if (
      (memory eq null) || (port eq null) || (selected eq null) ||
      finiteIndexToken == null
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-NULL",
        "structural Mem selection requires a native Mem, read port, selected value and opaque range identity",
        sourceLocation
      )
    }
    if (port.mem ne memory) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-PORT-MISMATCH",
        "structural Mem selection port does not belong to its retained memory",
        sourceLocation
      )
    }
    val generateIndex = index.generateIndex.getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-NOT-INDEXED",
        s"Mem index '${index.verilog}' does not depend on the active generate index",
        sourceLocation
      )
    }
    val address = port.address
    val (addressWitness, addressAssignment) = address match {
      case literal: BitVectorLiteral if !literal.hasPoison() && literal.getValue() == index.default =>
        literal -> None
      case target: BaseType =>
        val assignments = ArrayBuffer.empty[DataAssignmentStatement]
        target.foreachStatements {
          case value: DataAssignmentStatement if (value.finalTarget eq target) && (value.target eq target) =>
            assignments += value
          case _ =>
        }
        val matching = assignments.collect {
          case value if value.source.isInstanceOf[BitVectorLiteral] && {
                val literal = value.source.asInstanceOf[BitVectorLiteral]
                !literal.hasPoison() && literal.getValue() == index.default
              } =>
            value -> value.source.asInstanceOf[BitVectorLiteral]
        }
        if (assignments.size != 1 || matching.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-ADDRESS-LINEAGE-MISMATCH",
            s"structural Mem selection address retains ${assignments.size} exact direct assignments and ${matching.size} literal witness assignments for ${index.default}; exactly one shared identity is required",
            sourceLocation
          )
        }
        target.setName(
          s"morphhdl_structural_mem_address_${generateIndex}_${state.memoryIndices.size + 1}"
        )
        target.setAsVital()
        target.dontSimplifyIt()
        if (target.isComb) target.noBackendCombMerge()
        if (target.isReg) target.addTag(noBackendSyncMerge)
        matching.head._2 -> Some(matching.head._1)
      case _ =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-ADDRESS-LINEAGE-MISMATCH",
          s"structural Mem selection must retain the exact native literal address witness ${index.default}",
          sourceLocation
        )
    }
    val readBits = port.elaborationReadBits
    if (readBits == null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-READ-LINEAGE-MISSING",
        "structural Mem selection port lost its native readBits carrier",
        sourceLocation
      )
    }
    readBits.setAsVital()
    readBits.dontSimplifyIt()
    readBits.noBackendCombMerge()
    selected.flatten.foreach { leaf =>
      leaf.setAsVital()
      leaf.dontSimplifyIt()
      if (leaf.isComb) leaf.noBackendCombMerge()
      if (leaf.isReg) leaf.addTag(noBackendSyncMerge)
    }
    val readBitsAssignments = ArrayBuffer.empty[DataAssignmentStatement]
    readBits.foreachStatements {
      case value: DataAssignmentStatement
          if (value.finalTarget eq readBits) &&
            (value.target eq readBits) &&
            (value.source eq port) =>
        readBitsAssignments += value
      case _ =>
    }
    if (readBitsAssignments.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-READ-BRIDGE-MISMATCH",
        s"structural Mem readBits retains ${readBitsAssignments.size} exact direct assignments from its native port; exactly one is required",
        sourceLocation
      )
    }
    var selectedOffset = 0
    val selectedLineages = selected.flatten.zipWithIndex.map { case (leaf, leafIndex) =>
      val assignments = ArrayBuffer.empty[DataAssignmentStatement]
      leaf.foreachStatements {
        case value: DataAssignmentStatement if value.finalTarget eq leaf =>
          assignments += value
        case _ =>
      }
      val matching = assignments.flatMap { value =>
        memoryReadLineage(value.source, readBits, port).collect {
          case lineage
              if lineage.low == selectedOffset &&
                lineage.width == leaf.getBitsWidth =>
            value -> lineage.support
        }
      }
      if (assignments.size != 1 || matching.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-READ-LINEAGE-MISMATCH",
          s"structural Mem selected leaf $leafIndex has ${assignments.size} direct assignments and ${matching.size} exact packed lineages from bits $selectedOffset through ${selectedOffset + leaf.getBitsWidth - 1} of its retained native read port; exactly one shared identity is required",
          sourceLocation
        )
      }
      selectedOffset += leaf.getBitsWidth
      matching.head
    }.toVector
    val selectedAssignments = selectedLineages.map(_._1)
    val selectedSupportAssignments = selectedLineages.map(_._2)
    if (
      selectedAssignments.indices.exists { left =>
        selectedAssignments.indices.exists { right =>
          left < right &&
          (selectedAssignments(left) eq selectedAssignments(right))
        }
      }
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-READ-LINEAGE-MISMATCH",
        "structural Mem selected leaves share one ambiguous native assignment",
        sourceLocation
      )
    }
    if (index.default < 0 || index.default >= memory.wordCount || index.minimum < 0) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-WITNESS-UNSUPPORTED",
        s"Mem index '${index.verilog}' has invalid witness/domain [${index.minimum}, ${index.maximum}] for native depth ${memory.wordCount}",
        sourceLocation
      )
    }
    if (state.memoryIndices.exists(value => value.port eq port)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-PORT-DUPLICATE",
        "one native Mem port was recorded more than once in one structural body",
        sourceLocation
      )
    }
    state.memoryIndices += StructuralMemoryIndex(
      memory,
      port,
      address,
      addressWitness,
      addressAssignment,
      readBits,
      readBitsAssignments.head,
      selected,
      selectedAssignments,
      selectedSupportAssignments,
      index,
      sourceLocation,
      Some(finiteIndexToken)
    )
  }

  /** Exact native packed lineage used by MultiData.assignFromBits.  Fixed
    * accesses and the ordinary cast/type-node copies are followed only through
    * their unique assignment identities; arbitrary operators are never
    * admitted as memory-read evidence.
    */
  private[core] final case class StructuralMemoryReadLineage(
      low: Int,
      width: Int,
      support: Vector[DataAssignmentStatement]
  )

  private[core] def memoryReadLineage(
      root: Expression,
      readBits: Bits,
      port: MemReadAsync
  ): Option[StructuralMemoryReadLineage] = {
    val visited = new IdentityHashMap[Expression, java.lang.Boolean]()

    def trace(value: Expression): Option[StructuralMemoryReadLineage] = {
      if (
        value == null ||
        visited.put(value, java.lang.Boolean.TRUE) != null
      ) return None

      value match {
        case source if (source eq readBits) || (source eq port) =>
          Some(StructuralMemoryReadLineage(0, readBits.getBitsWidth, Vector.empty))
        case access: BitVectorRangedAccessFixed =>
          trace(access.source).flatMap { parent =>
            val width = access.getWidth
            if (
              access.lo < 0 || width < 1 ||
              access.lo + width > parent.width
            ) None
            else
              Some(
                StructuralMemoryReadLineage(
                  parent.low + access.lo,
                  width,
                  parent.support
                )
              )
          }
        case access: BitVectorBitAccessFixed =>
          trace(access.source).flatMap { parent =>
            if (access.bitId < 0 || access.bitId >= parent.width) None
            else
              Some(
                StructuralMemoryReadLineage(
                  parent.low + access.bitId,
                  1,
                  parent.support
                )
              )
          }
        case cast: CastBitsToUInt => trace(cast.input)
        case cast: CastBitsToSInt => trace(cast.input)
        case cast: CastBitsToEnum => trace(cast.input)
        case intermediate: BaseType if intermediate ne readBits =>
          val drivers = ArrayBuffer.empty[DataAssignmentStatement]
          intermediate.foreachStatements {
            case value: DataAssignmentStatement
                if (value.finalTarget eq intermediate) &&
                  (value.target eq intermediate) =>
              drivers += value
            case _ =>
          }
          if (drivers.size != 1) None
          else
            trace(drivers.head.source).map(lineage => lineage.copy(support = drivers.head +: lineage.support))
        case _ => None
      }
    }

    trace(root)
  }

  private def expressionContains(
      root: Expression,
      target: Expression
  ): Boolean = {
    if (root == null || target == null) return false
    val visited = new IdentityHashMap[Expression, java.lang.Boolean]()
    var found = false
    def visit(value: Expression): Unit = {
      if (!found && value != null && visited.put(value, java.lang.Boolean.TRUE) == null) {
        if (value eq target) found = true
        else value.foreachExpression(visit)
      }
    }
    visit(root)
    found
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

  /** Retain every exact statement below a structural alternative, including
    * tree controls and memory ports whose driving expressions do not occur in
    * a DataAssignmentStatement. This is deliberately separate from the
    * assignment inventory used by structural publication: pre-normalization
    * provenance capture needs a complete driving-use universe.
    */
  private def capturedStatements(
      regions: Vector[StructuralRegion]
  ): Vector[CapturedStatement] = {
    val values = ArrayBuffer.empty[CapturedStatement]

    def visitStatement(
        statement: Statement,
        path: Vector[AlternativeStep]
    ): Unit = {
      values += CapturedStatement(statement, path)
      statement match {
        case tree: TreeStatement =>
          tree.foreachStatements(value => visitStatement(value, path))
        case _ =>
      }
    }

    def visitBlock(
        block: ParameterizedStructuralBlock,
        path: Vector[AlternativeStep]
    ): Unit = {
      block.statements.foreach(value => visitStatement(value, path))
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

  /** Exact captured statement identities which occur only below a branch the
    * concrete witness does not select. Unlike the assignment-only view, this
    * includes tree controls and memory ports so a one-use provenance proof
    * cannot miss a direct selector/address consumer. Identities also present
    * on an active path fail closed and are excluded.
    */
  private[core] def capturedWitnessInactiveStatementsOf(
      component: Component
  ): Vector[Statement] = {
    if (component eq null) return Vector.empty
    val captured = capturedStatements(regionsOf(component))
    val active = new IdentityHashMap[Statement, java.lang.Boolean]()
    captured.foreach { value =>
      if (!witnessInactive(value.path))
        active.put(value.statement, java.lang.Boolean.TRUE)
    }

    val seen = new IdentityHashMap[Statement, java.lang.Boolean]()
    val values = ArrayBuffer.empty[Statement]
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

  /** Exact-assignment counterpart of [[exactDeclarationDomainOf]]. The
    * assignment itself, rather than its target declaration, owns expressions
    * that are introduced only while lowering that surviving assignment.
    */
  private[core] def exactAssignmentDomainOf(
      component: Component,
      assignment: DataAssignmentStatement,
      root: ElaborationIntegerParameterRoot,
      universe: Set[BigInt],
      role: String,
      sourceLocation: Option[String]
  ): ExactNativeObjectDomain = {
    if (component == null || assignment == null) {
      fail(
        "SPINAL-ELAB-PROJECTION-OBJECT-NULL",
        s"$role requires a non-null component and native assignment",
        sourceLocation
      )
    }
    val matches = capturedAssignments(regionsOf(component)).collect {
      case value if value.statement eq assignment => value.path
    }
    exactNativeObjectDomainOf(
      component,
      matches,
      allStatementsOf(component).exists(_ eq assignment),
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

  /** Require a projected expression to cover every root value for which one
    * exact assignment survives. Unlike declaration geometry, an expression
    * introduced into an assignment is not rebased to that owner's
    * representative: module-scope operands may be used safely in a narrower
    * branch. Identity, evidence and owner dominance remain mandatory.
    */
  private[core] def validateProjectedAssignmentDominance(
      component: Component,
      assignment: DataAssignmentStatement,
      expression: ElaborationIntegerExpression,
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    if (expression == null) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-NULL",
        s"$role requires a non-null retained expression",
        sourceLocation
      )
    }
    expression.exactDomain.foreach { domain =>
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

      val owner = exactAssignmentDomainOf(
        component,
        assignment,
        domain.root,
        domain.universe,
        role,
        sourceLocation
      )
      if (!owner.values.subsetOf(projection.admitted)) {
        val escaped = owner.values -- projection.admitted
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-SCOPE-MISMATCH",
          s"$role expression '${expression.verilog}' was projected for root values ${projection.admitted.toVector.sorted
              .mkString(", ")}, but its exact native owner also exists for ${escaped.toVector.sorted.mkString(", ")}",
          sourceLocation.orElse(expression.sourceLocation)
        )
      }
      owner.values.foreach { rootValue =>
        if (domain.evaluate(rootValue).isEmpty) {
          fail(
            "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-INCOMPLETE",
            s"$role expression '${expression.verilog}' has no exact evaluation at ${domain.root.name}=$rootValue",
            sourceLocation.orElse(expression.sourceLocation)
          )
        }
      }
    }
  }

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

  private[spinal] def registerFor(
      component: Component,
      label: String,
      indexName: String,
      count: ElaborationIntegerExpression,
      body: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ): Unit =
    registerForImpl(
      component,
      label,
      indexName,
      count,
      body,
      Some(new ElabFiniteIndexToken()),
      sourceLocation,
      requireExactDomain = false
    )

  /** Typed finite-range entry point. Unlike the established frontend-analyzed
    * structural bridge, this path requires a complete exact single-root table
    * before its representative body can be registered.
    */
  private[spinal] def registerExactFor(
      component: Component,
      label: String,
      indexName: String,
      count: ElaborationIntegerExpression,
      body: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ): Unit =
    registerExactFor(
      component,
      label,
      indexName,
      count,
      body,
      new ElabFiniteIndexToken(),
      sourceLocation
    )

  /** Exact typed finite-range registration carrying the same opaque identity
    * as every Vec selection made by this foreach index.
    */
  private[spinal] def registerExactFor(
      component: Component,
      label: String,
      indexName: String,
      count: ElaborationIntegerExpression,
      body: ParameterizedStructuralBlock,
      finiteIndexToken: ElabFiniteIndexToken,
      sourceLocation: Option[String]
  ): Unit = {
    if (finiteIndexToken == null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FINITE-INDEX-TOKEN-NULL",
        "typed finite generate-for requires one non-null opaque range identity",
        sourceLocation
      )
    }
    registerForImpl(
      component,
      label,
      indexName,
      count,
      body,
      Some(finiteIndexToken),
      sourceLocation,
      requireExactDomain = true
    )
  }

  private def registerForImpl(
      component: Component,
      label: String,
      indexName: String,
      count: ElaborationIntegerExpression,
      body: ParameterizedStructuralBlock,
      finiteIndexToken: Option[ElabFiniteIndexToken],
      sourceLocation: Option[String],
      requireExactDomain: Boolean
  ): Unit = {
    if (
      finiteIndexToken == null ||
      finiteIndexToken.exists(_ == null)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FINITE-INDEX-TOKEN-NULL",
        "generate-for requires a non-null opaque-token option",
        sourceLocation
      )
    }
    ElabInt.validateExpression(count, "generate count")
    val normalizedCount = ElabInt.withCompleteParameterRoots(count)
    validateIntegerExpression(normalizedCount, "generate count")
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
    val exactDomain =
      if (requireExactDomain)
        ElabFiniteRange.requireCompleteSymbolicDomain(
          normalizedCount,
          "structural generate count",
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-COUNT-EXACT-DOMAIN-REQUIRED"
        )
      else None
    val hasPositiveRepresentative = exactDomain match {
      case Some((exact, admitted)) =>
        exact.evaluations.exists { case (rootValue, result) =>
          admitted.contains(rootValue) && result > 0
        }
      case None => normalizedCount.maximum > 0
    }
    if (!hasPositiveRepresentative) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-COUNT-NONPOSITIVE",
        s"generate count '${normalizedCount.verilog}' has no ${if (requireExactDomain) "exact"
          else "structurally validated"} positive-domain point for its index-zero representative body",
        sourceLocation
      )
    }
    finiteIndexToken.foreach { token =>
      body.synchronized {
        body.vecIndices = body.vecIndices.map { selection =>
          selection.finiteIndexToken match {
            case Some(existing) if existing eq token => selection
            case Some(_) =>
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-FINITE-INDEX-TOKEN-CONFLICT",
                "one exact structural Vec selection belongs to a different finite-range identity than its captured generate-for",
                selection.sourceLocation.orElse(sourceLocation)
              )
            case None =>
              new StructuralVecIndex(
                selection.vector,
                selection.selected,
                selection.result,
                selection.staticAccess,
                selection.index,
                Some(token),
                selection.sourceLocation,
                selection.affineRead
              )
          }
        }
      }
    }
    val storage = storageOf(component)
    reserveName(storage, label, "generate label", sourceLocation)
    reserveName(storage, indexName, "generate index", sourceLocation)
    registerRegion(
      component,
      currentCaptureId(component, sourceLocation),
      StructuralFor(
        label,
        indexName,
        normalizedCount,
        body,
        finiteIndexToken,
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

  /** Certify an analyzer-private exhaustive predicate table, then publish only
    * the resulting structural-domain capability.  The opaque permit binds the
    * exact source, expression, table, component and operation identities; raw
    * expression/table pairs cannot enter this path.
    */
  private[spinal] def analyzedPredicateDomainOf(
      component: Component,
      operationIdentity: AnyRef,
      sourceIdentity: AnyRef,
      condition: ElaborationBooleanExpression,
      evaluations: Vector[(BigInt, Boolean)],
      permit: ExternalStructuralPredicatePermit
  ): StructuralPredicateDomain = {
    if (
      component == null || operationIdentity == null || sourceIdentity == null ||
      condition == null || evaluations == null
    ) {
      fail(
        "SPINAL-ELAB-CONTROL-ANALYZED-PREDICATE-DOMAIN-NULL",
        "analyzed predicate-domain construction requires non-null exact publication identities",
        Option(condition).flatMap(_.sourceLocation)
      )
    }
    ExternalStructuralPredicatePermit.requireAnalyzed(
      permit,
      sourceIdentity,
      condition,
      evaluations,
      component,
      operationIdentity
    )
    ElabInt.validateExpression(condition, "analyzed structural predicate")
    if (condition.exactDomain.nonEmpty) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-RECERTIFICATION-UNSUPPORTED",
        s"analyzed structural predicate '${condition.verilog}' already carries exact evidence",
        condition.sourceLocation
      )
    }
    val normalized = ElabInt.withCompleteParameterRoots(condition)
    val root = normalized.parameterRoots match {
      case Vector(value) => value
      case values =>
        fail(
          "SPINAL-ELAB-DOMAIN-ROOT-COUNT-UNSUPPORTED",
          s"analyzed structural predicate '${normalized.verilog}' retains ${values.size} roots",
          normalized.sourceLocation
        )
    }
    val parameter = normalized.parameters match {
      case Vector(value) => value
      case values =>
        fail(
          "SPINAL-ELAB-DOMAIN-PARAMETER-COUNT-UNSUPPORTED",
          s"analyzed structural predicate '${normalized.verilog}' retains ${values.size} parameter schemas",
          normalized.sourceLocation
        )
    }
    val exact = ElaborationExactDomain.checked(
      root,
      parameter,
      evaluations,
      normalized.sourceLocation,
      s"analyzed structural predicate '${normalized.verilog}'"
    )
    val certified = normalized
      .copy(exactDomain = Some(exact))
      .attachExactAuthority(exact, "analyzed structural predicate certification")
    val authoritative = ElabInt
      .requireAuthoritativeBooleanDomain(
        certified,
        "analyzed structural predicate",
        "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-DOMAIN-INVALID"
      )
      .getOrElse {
        fail(
          "SPINAL-ELAB-BOOL-ANALYZED-PREDICATE-DOMAIN-INVALID",
          s"analyzed structural predicate '${normalized.verilog}' did not retain one exact domain",
          normalized.sourceLocation
        )
      }
    structuralPredicateDomainOf(component, authoritative, normalized.sourceLocation)
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
    structuralPredicateDomainOf(component, exact, condition.sourceLocation)
  }

  private def structuralPredicateDomainOf(
      component: Component,
      exact: ElaborationExactDomain[Boolean],
      sourceLocation: Option[String]
  ): StructuralPredicateDomain = {
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
        sourceLocation.orElse(exact.root.sourceLocation)
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

  private[spinal] def registerIf(
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

  private[spinal] def registerCase(
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
