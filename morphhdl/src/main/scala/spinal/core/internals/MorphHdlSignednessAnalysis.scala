package spinal.core.internals

import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer
import morphhdl.analysis.SignednessFacts
import morphhdl.analysis.SignednessFacts.{Cast => CastRule, Resize => ResizeRule, Literal => LiteralRule, Mux => MuxRule, _}
import spinal.core._

final class MorphHdlSignednessException(val code: String, detail: String)
    extends IllegalArgumentException(s"[$code] $detail")

/** Read-only, generation-scoped analysis of exact native objects. No emitter
  * calls this automatically in 60b and no method grants cast-elision permission.
  */
object MorphHdlSignednessAnalysis {
  private def reject(code: String, detail: String): Nothing =
    throw new MorphHdlSignednessException("MORPH-SIGNEDNESS-" + code, detail)

  sealed trait Use
  case object DeclarationUse extends Use
  case object TemporaryUse extends Use
  case object MemoryElementUse extends Use
  case object AggregateUse extends Use
  case object ExpressionUse extends Use
  case object CastOperandUse extends Use

  /** Not a case class: no public constructor or copy operation. */
  final class Evidence private[MorphHdlSignednessAnalysis] (
      private[MorphHdlSignednessAnalysis] val owner: Snapshot,
      private[MorphHdlSignednessAnalysis] val subject: AnyRef,
      val use: Use,
      private[MorphHdlSignednessAnalysis] val parent: Expression,
      private[MorphHdlSignednessAnalysis] val slot: Int
  )

  private final case class Shape(
      kind: Kind, rule: Rule, nativeBits: Int,
      children: Vector[AnyRef], parameters: Vector[ElaborationIntegerExpression],
      details: Vector[Any], owners: Vector[AnyRef]
  )
  private final case class Entry(subject: AnyRef, shape: Shape, fact: Fact)
  private final case class Root(subject: AnyRef, use: Use)
  private val replayUseOrder: Vector[Use] = Vector(
    DeclarationUse, ExpressionUse, MemoryElementUse, AggregateUse, TemporaryUse
  )

  final class Snapshot private[MorphHdlSignednessAnalysis] (
      private val entries: Vector[Entry],
      private val indices: IdentityHashMap[AnyRef, java.lang.Integer],
      private val widths: Vector[ElaborationIntegerExpression],
      private val capturedUses: IdentityHashMap[AnyRef, Set[Use]]
  ) {
    /** Membership is not emission permission. Publication can leave unrelated
      * unsigned objects with the native backend, but covered objects must still
      * pass the same identity, role, freshness and width checks below.
      */
    private[spinal] def contains(subject: AnyRef): Boolean =
      subject != null && indices.containsKey(subject)

    /** Immutable observations for inspection/replay; these are not use evidence. */
    val facts: Vector[Fact] = entries.map(_.fact)
    def replay: String = {
      val rootIds = new IdentityHashMap[ElaborationIntegerParameterRoot, java.lang.Integer]()
      val domains = widths.zipWithIndex.map { case (width, index) =>
        val roots = width.parameterRoots.map { root =>
          var id = rootIds.get(root)
          if (id == null) {
            id = java.lang.Integer.valueOf(rootIds.size())
            rootIds.put(root, id)
          }
          id.intValue
        }
        s"width.$index(default=${width.default},min=${width.minimum},max=${width.maximum},roots=$roots)"
      }
      val roles = entries.map { entry =>
        val present = Option(capturedUses.get(entry.subject)).getOrElse(Set.empty[Use])
        s"use.${entry.fact.id}(${replayUseOrder.filter(present).mkString(",")})"
      }
      (domains ++ roles ++ facts.map(_.toString)).mkString("\n") + "\n"
    }

    private def entry(subject: AnyRef): Entry = {
      if (subject == null) reject("NULL-SUBJECT", "a use needs an exact non-null graph object")
      val index = indices.get(subject)
      if (index == null) reject("FOREIGN-SUBJECT", "object is not in this analysis snapshot")
      entries(index.intValue)
    }

    private def fresh(subject: AnyRef, seen: IdentityHashMap[AnyRef, java.lang.Boolean]): Unit = {
      if (seen.put(subject, java.lang.Boolean.TRUE) != null) return
      val saved = entry(subject).shape
      val live = describe(subject)
      def sameIdentity[A <: AnyRef](a: Vector[A], b: Vector[A]): Boolean =
        a.size == b.size && a.zip(b).forall { case (x, y) => x eq y }
      if (saved.kind != live.kind || saved.rule != live.rule ||
          saved.nativeBits != live.nativeBits || saved.details != live.details ||
          !sameIdentity(saved.children, live.children) ||
          !sameIdentity(saved.parameters, live.parameters) ||
          !sameIdentity(saved.owners, live.owners))
        reject("STALE-EVIDENCE", "graph type, width, operand, ownership or boundary changed after capture")
      live.parameters.foreach(validateWidth(_, subject))
      saved.children.foreach(child => fresh(child, seen))
    }

    private def check(subject: AnyRef): Entry = {
      fresh(subject, new IdentityHashMap[AnyRef, java.lang.Boolean]())
      entry(subject)
    }

    private def requireRole(subject: AnyRef, use: Use): Unit = {
      check(subject)
      if (!Option(capturedUses.get(subject)).exists(_.contains(use)))
        reject("USE-ROLE", "this graph object was not captured in the requested occurrence role")
      // A previously indexed memory template or detached declaration is not an
      // emitted declaration. Verify actual scope membership, not just an owner
      // pointer. Role facts are not supplied by callers or inferred from type.
      if (use == DeclarationUse || use == MemoryElementUse) {
        val declaration = subject.asInstanceOf[DeclarationStatement]
        val scope = declaration.parentScope
        var present = false
        if (scope != null) scope.foreachStatements(statement =>
          if (statement eq declaration) present = true)
        if (!present) reject("USE-ROLE", "declaration no longer occurs in its captured native scope")
      }
    }

    def expression(subject: Expression): Evidence = {
      requireRole(subject, ExpressionUse)
      new Evidence(this, subject, ExpressionUse, null, -1)
    }
    def declaration(subject: BaseType): Evidence = {
      requireRole(subject, DeclarationUse)
      new Evidence(this, subject, DeclarationUse, null, -1)
    }
    /** Reserved for an exact emitter wrapper plan. A pre-emission graph alone
      * cannot prove that any expression becomes a temporary, so 60b captures no
      * TemporaryUse and this request fails closed. Never guess from TypeSInt.
      */
    def temporary(subject: Expression): Evidence = {
      requireRole(subject, TemporaryUse)
      new Evidence(this, subject, TemporaryUse, null, -1)
    }
    def memoryElement(subject: Mem[_]): Evidence = {
      requireRole(subject, MemoryElementUse)
      new Evidence(this, subject, MemoryElementUse, null, -1)
    }
    def aggregate(subject: MultiData): Evidence = {
      requireRole(subject, AggregateUse)
      new Evidence(this, subject, AggregateUse, null, -1)
    }
    def castOperand(parent: Expression, slot: Int): Evidence = {
      requireRole(parent, ExpressionUse)
      val operands = expressionChildren(parent)
      if (slot < 0 || slot >= operands.size)
        reject("OPERAND-SLOT", "cast operand index is outside the exact expression")
      new Evidence(this, operands(slot), CastOperandUse, parent, slot)
    }

    /** Validate identity, session, use role and the entire dependency subtree.
      * A Fact returned here still needs its listed target/width obligations.
      */
    def validate(subject: AnyRef, evidence: Evidence, use: Use): Fact = {
      if (evidence == null || (evidence.owner ne this))
        reject("FOREIGN-EVIDENCE", "evidence belongs to another analysis session")
      if ((evidence.subject ne subject) || evidence.use != use)
        reject("USE-IDENTITY", "evidence is not for this exact object and use role")
      if (use == CastOperandUse) {
        requireRole(evidence.parent, ExpressionUse)
        requireRole(subject, ExpressionUse)
        val operands = expressionChildren(evidence.parent)
        if (evidence.slot < 0 || evidence.slot >= operands.size ||
            (operands(evidence.slot) ne subject))
          reject("OPERAND-IDENTITY", "cast use no longer denotes the captured operand edge")
      }
      if (use != CastOperandUse) requireRole(subject, use)
      val fact = check(subject).fact
      if (use == TemporaryUse) fact.copy(value = fact.intent,
        requirements = (fact.requirements :+ TargetDeclarationMode).distinct)
      else if (use == CastOperandUse) fact.copy(
        requirements = (fact.requirements ++ entry(evidence.parent).fact.requirements).distinct)
      else fact
    }

    def validateCastOperand(parent: Expression, slot: Int, evidence: Evidence): Fact = {
      if (evidence == null || (evidence.parent ne parent) || evidence.slot != slot)
        reject("OPERAND-IDENTITY", "cast decision must use its exact parent and operand slot")
      check(parent)
      val operands = expressionChildren(parent)
      if (slot < 0 || slot >= operands.size) reject("OPERAND-SLOT", "invalid cast operand slot")
      validate(operands(slot), evidence, CastOperandUse)
    }

    // Positive sizing must hold throughout the retained parameter domain, not
    // merely at the elaborated native witness. Interval bounds are deliberately
    // conservative; an uncertain result never becomes an emission permission.
    private def widthBounds(width: Width): Option[(BigInt, BigInt)] = {
      def combine(parts: Vector[Width])(f: ((BigInt, BigInt), (BigInt, BigInt)) => (BigInt, BigInt))
          : Option[(BigInt, BigInt)] = {
        val bounds = parts.map(widthBounds)
        if (bounds.isEmpty || bounds.exists(_.isEmpty)) None
        else Some(bounds.map(_.get).reduce(f))
      }
      width match {
        case Fixed(bits) => Some((bits, bits))
        case Retained(key) if key >= 0 && key < widths.size =>
          val value = widths(key)
          Some((value.minimum, value.maximum))
        case Sum(parts) => combine(parts) { case ((al, ah), (bl, bh)) => (al + bl, ah + bh) }
        case Product(parts) => combine(parts) { case ((al, ah), (bl, bh)) =>
          val products = Vector(al * bl, al * bh, ah * bl, ah * bh)
          (products.min, products.max)
        }
        case Maximum(parts) => combine(parts) { case ((al, ah), (bl, bh)) => (al.max(bl), ah.max(bh)) }
        case Minimum(parts) => combine(parts) { case ((al, ah), (bl, bh)) => (al.min(bl), ah.min(bh)) }
        case Difference(left, right) => for (a <- widthBounds(left); b <- widthBounds(right))
          yield (a._1 - b._2, a._2 - b._1)
        case _ => None
      }
    }

    def requireKnown(subject: AnyRef, evidence: Evidence, use: Use): Fact = {
      val fact = validate(subject, evidence, use)
      if (fact.value == Unknown || fact.requirements.contains(UnknownSemantics) ||
          !resolved(fact.width) || fact.nativeBits <= 0 ||
          !widthBounds(fact.width).exists(_._1 > 0))
        reject("UNKNOWN-FACT", "unknown semantics or non-positive/uncertain width domain cannot qualify a use")
      fact
    }

    def widthSource(subject: AnyRef, evidence: Evidence, use: Use, key: Int): ElaborationIntegerExpression = {
      val fact = validate(subject, evidence, use)
      def contains(width: Width): Boolean = width match {
        case Retained(value) => value == key
        case Sum(parts) => parts.exists(contains)
        case Product(parts) => parts.exists(contains)
        case Maximum(parts) => parts.exists(contains)
        case Minimum(parts) => parts.exists(contains)
        case Difference(left, right) => contains(left) || contains(right)
        case _ => false
      }
      if (key < 0 || key >= widths.size || !contains(fact.width))
        reject("WIDTH-USE-IDENTITY", "width token does not belong to this validated use")
      // validate above rechecks the complete dependency subtree, including
      // each retained width at its exact native owner. Revalidating this token
      // without that owner would lose a captured construction-branch domain.
      widths(key)
    }

    /** An exact width object is available only through validated subject use;
      * callers cannot substitute a same-valued or case-class-copied expression.
      */
    def retainedWidths(subject: AnyRef, evidence: Evidence, use: Use): Vector[ElaborationIntegerExpression] = {
      validate(subject, evidence, use)
      entry(subject).shape.parameters
    }
  }

  /** Unit/compiler utility for exact expression roots, not a rendered-HDL parser. */
  def expressions(roots: Vector[Expression]): Snapshot = {
    if (roots == null || roots.exists(_ == null)) reject("NULL-SUBJECT", "expression roots must not contain null")
    build(roots.map(root => Root(root, ExpressionUse)))
  }

  /** Capture all declarations, memory elements, aggregate ancestors and all
    * expression occurrences in deterministic native statement/hierarchy order.
    */
  def capture(top: Component): Snapshot = captureGraph(top, publication = false)

  /** A publication policy owns signed operations and their complete dependency
    * subgraphs, not unrelated unsigned widths. Strict observer capture remains
    * unchanged. In particular an unsigned branch-local Counter/FIFO must not
    * acquire a second, module-wide width validator merely because signed
    * declaration spelling is enabled elsewhere in the design.
    */
  private def capturePublication(top: Component): Snapshot =
    captureGraph(top, publication = true)

  private def captureGraph(top: Component, publication: Boolean): Snapshot = {
    if (top == null) reject("NULL-TOP", "capture needs an elaborated component")
    val roots = ArrayBuffer.empty[Root]
    val aggregates = new IdentityHashMap[MultiData, java.lang.Boolean]()
    def parents(data: Data): Unit = data.parent match {
      case aggregate: MultiData if aggregates.put(aggregate, java.lang.Boolean.TRUE) == null =>
        roots += Root(aggregate, AggregateUse)
        parents(aggregate)
      case _ => ()
    }
    def component(value: Component): Unit = {
      value.dslBody.walkStatements {
        case memory: Mem[_] => roots += Root(memory, MemoryElementUse)
        case base: BaseType => roots += Root(base, DeclarationUse); parents(base)
        case expression: Expression => roots += Root(expression, ExpressionUse)
        case statement => statement.foreachExpression(expression =>
          if (expression != null) roots += Root(expression, ExpressionUse))
      }
      value.children.foreach(component)
    }
    component(top)
    if (!publication) return build(roots.toVector)

    val relevant = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    val active = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    def touchesSigned(subject: AnyRef): Boolean = {
      val known = relevant.get(subject)
      if (known != null) return known.booleanValue
      if (active.put(subject, java.lang.Boolean.TRUE) != null)
        reject("EXPRESSION-CYCLE", "non-reference expression dependency cycle")
      val shape = describe(subject)
      // Type selects work only. It never grants cast or declaration permission;
      // build/validate retain their original exact typed authority requirements.
      val signed = shape.kind == SignedScalar || shape.children.exists(touchesSigned)
      active.remove(subject)
      relevant.put(subject, java.lang.Boolean.valueOf(signed))
      signed
    }
    val needed = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    def include(subject: AnyRef): Unit =
      if (needed.put(subject, java.lang.Boolean.TRUE) == null)
        describe(subject).children.foreach(include)
    roots.foreach { root =>
      // Ancestor aggregate capture is for observers, not scalar publication.
      // A needed memory/packed expression retains its own complete shape.
      if (root.use != AggregateUse && touchesSigned(root.subject)) include(root.subject)
    }
    // Retain every occurrence role of each needed exact object, including
    // unsigned declaration references used as signed-operation dependencies.
    build(roots.filter(root => needed.containsKey(root.subject)).toVector)
  }

  /** The final plan is rechecked at execution, not only at installation:
    * another phase inserter must not move analysis before validation or away
    * from the exact emission boundary after this inserter has returned.
    */
  private final class ObservationPhase(
      plan: () => ArrayBuffer[Phase],
      val context: PhaseContext
  ) extends PhaseMisc {
    private def requireRegistrationOpen(): Unit =
      if (context.hasStartedPhaseExecution)
        reject("PHASE-PLAN", "signedness registration is closed once phase execution starts")

    private var observer: Option[Snapshot => Unit] = None
    private var publisher: Option[(Snapshot => Unit, () => Boolean)] = None
    private var entered = false

    def observe(callback: Snapshot => Unit): Unit = {
      requireRegistrationOpen()
      if (entered || observer.nonEmpty)
        reject("PHASE-PLAN", "a strict observer may only be installed once before execution")
      observer = Some(callback)
    }

    def publish(callback: Snapshot => Unit, enabled: () => Boolean): Unit = {
      requireRegistrationOpen()
      if (entered || publisher.nonEmpty)
        reject("PHASE-PLAN", "a publication consumer may only be installed once before execution")
      publisher = Some((callback, enabled))
    }

    override def impl(pc: PhaseContext): Unit = {
      if ((pc ne context) || !context.hasStartedPhaseExecution)
        reject("PHASE-PLAN", "capture requires its exact executing native phase context")
      if (entered) reject("PHASE-PLAN", "a signedness phase may only execute once")
      entered = true
      val phases = plan()
      val emission = validatedEmission(phases)
      if (phases.count(_ eq this) != 1 || emission == 0 || (phases(emission - 1) ne this))
        reject("PHASE-PLAN", "analysis must remain immediately before the validated Verilog emitter")
      // Both snapshots precede any caller callback. Observation is still full
      // and strict; publication retains its independently selected dependency
      // scope. A callback cannot mutate the graph and obtain fresh publication
      // permission by causing the second capture to run after its mutation.
      val observed = observer.map(callback => (callback, capture(pc.topLevel)))
      val published = publisher.filter { case (_, enabled) => enabled() }
        .map { case (callback, _) => (callback, capturePublication(pc.topLevel)) }
      observed.foreach { case (callback, snapshot) => callback(snapshot) }
      published.foreach { case (callback, snapshot) => callback(snapshot) }
    }
  }

  private def validatedEmission(phases: ArrayBuffer[Phase]): Int = {
    if (phases == null || phases.exists(_ == null))
      reject("PHASE-PLAN", "analysis requires a non-null native phase plan")
    def exactly(kind: Class[_]): Int = {
      val found = phases.zipWithIndex.collect { case (phase, index) if phase.getClass == kind => index }
      if (found.size != 1) reject("PHASE-PLAN", "analysis requires each reviewed native boundary exactly once")
      found.head
    }
    val normalized = exactly(classOf[PhaseNormalizeNodeInputs])
    val checked = exactly(classOf[PhaseCheckCrossClock])
    val allocated = exactly(classOf[PhaseAllocateNames])
    val emitted = exactly(classOf[PhaseVerilog])
    if (!(normalized < checked && checked < allocated && allocated < emitted))
      reject("PHASE-PLAN", "analysis requires ordered normalization, validation, allocation and emission")
    emitted
  }

  /** A strict read-only observer does not change the publication policy. */
  def install(observer: Snapshot => Unit)(phases: ArrayBuffer[Phase]): Unit = {
    if (observer == null) reject("PHASE-PLAN", "analysis requires a non-null observer")
    sharedPhase(phases).observe(observer)
  }

  /** The strict observer and publication consumer share one physical validated
    * boundary in either installation order. Duplicate consumers of either role
    * remain errors; sharing never repairs a moved or duplicated analysis phase.
    */
  def installPublication(observer: Snapshot => Unit, enabled: () => Boolean)(
      phases: ArrayBuffer[Phase]): Unit = {
    if (observer == null || enabled == null)
      reject("PHASE-PLAN", "publication requires a non-null consumer and selector")
    sharedPhase(phases).publish(observer, enabled)
  }

  private def sharedPhase(phases: ArrayBuffer[Phase]): ObservationPhase = {
    val emission = validatedEmission(phases)
    val context = Option(GlobalData.get).flatMap(data => Option(data.phaseContext))
      .getOrElse(reject("PHASE-PLAN", "registration requires its native phase context"))
    // This evidence is owned by the native scheduler, not a movable guard
    // phase or a test of whether top-level elaboration has happened yet.
    if (context.hasStartedPhaseExecution)
      reject("PHASE-PLAN", "signedness registration is closed once phase execution starts")
    val existing = phases.collect { case phase: ObservationPhase => phase }
    if (existing.size > 1)
      reject("PHASE-PLAN", "signedness requires exactly one physical capture boundary")
    if (existing.isEmpty) {
      val phase = new ObservationPhase(() => phases, context)
      phases.insert(emission, phase)
      phase
    } else {
      val phase = existing.head
      if ((phase.context ne context) || emission == 0 || (phases(emission - 1) ne phase))
        reject("PHASE-PLAN", "an existing analysis must retain its exact context and validated emission boundary")
      phase
    }
  }

  private def resolved(width: Width): Boolean = width match {
    case UnknownWidth => false
    case Sum(parts) => parts.nonEmpty && parts.forall(resolved)
    case Product(parts) => parts.nonEmpty && parts.forall(resolved)
    case Maximum(parts) => parts.nonEmpty && parts.forall(resolved)
    case Minimum(parts) => parts.nonEmpty && parts.forall(resolved)
    case Difference(left, right) => resolved(left) && resolved(right)
    case _ => true
  }

  private def validateWidth(value: ElaborationIntegerExpression, subject: AnyRef): Unit = {
    subject match {
      case base: BaseType if ElaborationWidthAuthority.isRetained(value) =>
        NativePublicationWidth.validate(value, base.component, base, "signedness width authority")
      case _ if ElaborationWidthAuthority.isRetained(value) =>
        ElaborationWidthAuthority.requireAuthoritative(value, "signedness width authority",
          "MORPH-SIGNEDNESS-WIDTH-AUTHORITY")
      case _ =>
        ElabInt.requireAuthoritativeIntegerDomain(value, "signedness width authority",
          "MORPH-SIGNEDNESS-WIDTH-AUTHORITY", requireExactExtrema = false)
    }
    if (value.minimum < 0) reject("WIDTH-AUTHORITY", "negative logical width domain")
  }

  private def expressionChildren(expression: Expression): Vector[Expression] = expression match {
    case _: BaseType => Vector.empty // A reference is a terminal, not its driver.
    case _ =>
      val result = ArrayBuffer.empty[Expression]
      expression.foreachExpression { child =>
        if (child == null) reject("NULL-OPERAND", "a graph expression contains an uninitialized operand")
        result += child
      }
      result.toVector
  }

  private def scalarIntent(base: BaseType): Kind = base match {
    case _: SInt if base.getTypeObject == TypeSInt => SignedScalar
    case _: UInt if base.getTypeObject == TypeUInt => UnsignedScalar
    case _: Bits if base.getTypeObject == TypeBits => UnsignedScalar
    case _: Bool if base.getTypeObject == TypeBool => BooleanValue
    case _ => Unknown
  }

  private def typeIntent(value: Any): Kind = value match {
    case TypeSInt => SignedScalar
    case TypeUInt | TypeBits => UnsignedScalar
    case TypeBool => BooleanValue
    case _ => Unknown
  }

  // Runtime class identity, never a class-name or emitted-name recognizer.
  // Unknown downstream operators cannot inherit authority merely by claiming TypeSInt.
  private val reviewedExpressions: Set[Class[_]] = Set(
    classOf[SIntLiteral], classOf[UIntLiteral], classOf[BitsLiteral],
    classOf[BoolLiteral], classOf[ResizeSInt], classOf[ResizeUInt],
    classOf[ResizeBits], classOf[CastBitsToSInt], classOf[CastUIntToSInt],
    classOf[CastSIntToBits], classOf[CastUIntToBits], classOf[CastBitsToUInt],
    classOf[CastSIntToUInt], classOf[CastBoolToBits], classOf[MemReadSync],
    classOf[MemReadAsync], classOf[MemReadWrite], classOf[MemReadAsyncWrite],
    classOf[Operator.SInt.Not], classOf[Operator.SInt.And], classOf[Operator.SInt.Or],
    classOf[Operator.SInt.Xor], classOf[Operator.SInt.Equal], classOf[Operator.SInt.EqualSim],
    classOf[Operator.SInt.NotEqual], classOf[Operator.SInt.Repeat], classOf[Operator.SInt.ShiftLeftByInt],
    classOf[Operator.SInt.ShiftRightByInt], classOf[Operator.SInt.ShiftLeftByUInt], classOf[Operator.SInt.ShiftRightByUInt],
    classOf[Operator.SInt.ShiftLeftByIntFixedWidth], classOf[Operator.SInt.ShiftRightByIntFixedWidth], classOf[Operator.SInt.ShiftLeftByUIntFixedWidth],
    classOf[Operator.SInt.Add], classOf[Operator.SInt.Sub], classOf[Operator.SInt.Mul],
    classOf[Operator.SInt.Div], classOf[Operator.SInt.Mod], classOf[Operator.SInt.Smaller],
    classOf[Operator.SInt.SmallerOrEqual], classOf[Operator.SInt.Minus], classOf[SIntBitAccessFixed],
    classOf[SIntBitAccessFloating], classOf[SIntRangedAccessFixed], classOf[SIntRangedAccessFloating],
    classOf[Operator.UInt.Not], classOf[Operator.UInt.And], classOf[Operator.UInt.Or],
    classOf[Operator.UInt.Xor], classOf[Operator.UInt.Equal], classOf[Operator.UInt.EqualSim],
    classOf[Operator.UInt.NotEqual], classOf[Operator.UInt.Repeat], classOf[Operator.UInt.ShiftLeftByInt],
    classOf[Operator.UInt.ShiftRightByInt], classOf[Operator.UInt.ShiftLeftByUInt], classOf[Operator.UInt.ShiftRightByUInt],
    classOf[Operator.UInt.ShiftLeftByIntFixedWidth], classOf[Operator.UInt.ShiftRightByIntFixedWidth], classOf[Operator.UInt.ShiftLeftByUIntFixedWidth],
    classOf[Operator.UInt.Add], classOf[Operator.UInt.Sub], classOf[Operator.UInt.Mul],
    classOf[Operator.UInt.Div], classOf[Operator.UInt.Mod], classOf[Operator.UInt.Smaller],
    classOf[Operator.UInt.SmallerOrEqual], classOf[UIntBitAccessFixed], classOf[UIntBitAccessFloating],
    classOf[UIntRangedAccessFixed], classOf[UIntRangedAccessFloating], classOf[Operator.Bits.Not],
    classOf[Operator.Bits.And], classOf[Operator.Bits.Or], classOf[Operator.Bits.Xor],
    classOf[Operator.Bits.Equal], classOf[Operator.Bits.EqualSim], classOf[Operator.Bits.NotEqual],
    classOf[Operator.Bits.Repeat], classOf[Operator.Bits.ShiftLeftByInt], classOf[Operator.Bits.ShiftRightByInt],
    classOf[Operator.Bits.ShiftLeftByUInt], classOf[Operator.Bits.ShiftRightByUInt], classOf[Operator.Bits.ShiftLeftByIntFixedWidth],
    classOf[Operator.Bits.ShiftRightByIntFixedWidth], classOf[Operator.Bits.ShiftLeftByUIntFixedWidth], classOf[BitsBitAccessFixed],
    classOf[BitsBitAccessFloating], classOf[BitsRangedAccessFixed], classOf[BitsRangedAccessFloating],
    classOf[Operator.Bits.Cat], classOf[Operator.Bool.Not], classOf[Operator.Bool.And],
    classOf[Operator.Bool.Or], classOf[Operator.Bool.Xor], classOf[Operator.Bool.Equal],
    classOf[Operator.Bool.EqualSim], classOf[Operator.Bool.NotEqual], classOf[Operator.Bool.Repeat],
    classOf[Operator.BitVector.orR], classOf[Operator.BitVector.andR], classOf[Operator.BitVector.xorR],
    classOf[MultiplexerSInt], classOf[MultiplexerUInt], classOf[MultiplexerBits],
    classOf[MultiplexerBool], classOf[BinaryMultiplexerSInt], classOf[BinaryMultiplexerUInt],
    classOf[BinaryMultiplexerBits], classOf[BinaryMultiplexerBool]
  )

  private def primitiveRule(expression: Expression): Rule = expression match {
    case _: BaseType => Reference
    case other if !reviewedExpressions.contains(other.getClass) => Unsupported
    case _: SIntLiteral | _: UIntLiteral | _: BitsLiteral | _: BoolLiteral => LiteralRule
    case _: Operator.SInt.Minus | _: Operator.SInt.Not | _: Operator.UInt.Not | _: Operator.Bits.Not => Unary
    case _: Operator.BitVector.Add | _: Operator.BitVector.Sub | _: Operator.BitVector.Mul |
         _: Operator.BitVector.Div | _: Operator.BitVector.Mod | _: Operator.BitVector.And |
         _: Operator.BitVector.Or | _: Operator.BitVector.Xor => Arithmetic
    case _: Operator.BitVector.Equal | _: Operator.BitVector.NotEqual | _: Operator.BitVector.EqualSim |
         _: Operator.SInt.Smaller | _: Operator.SInt.SmallerOrEqual |
         _: Operator.UInt.Smaller | _: Operator.UInt.SmallerOrEqual => Comparison
    case _: Operator.Bool.Not | _: Operator.Bool.And | _: Operator.Bool.Or | _: Operator.Bool.Xor |
         _: Operator.Bool.Equal | _: Operator.Bool.NotEqual | _: Operator.Bool.EqualSim |
         _: Operator.BitVector.orR | _: Operator.BitVector.andR | _: Operator.BitVector.xorR => Logical
    case _: Operator.BitVector.ShiftOperator => Shift
    case _: BinaryMultiplexer | _: Multiplexer => MuxRule
    case _: CastBitsToSInt | _: CastUIntToSInt | _: CastSIntToBits | _: CastUIntToBits |
         _: CastBitsToUInt | _: CastSIntToUInt | _: CastBoolToBits => CastRule
    case _: spinal.core.internals.Resize => ResizeRule
    case _: Operator.Bits.Cat => Concatenation
    case _: Operator.BitVector.Repeat | _: Operator.Bool.Repeat => Replication
    case _: SubAccess => Selection
    case _: MemReadSync | _: MemReadAsync | _: MemReadWrite | _: MemReadAsyncWrite => MemoryRead
    case _ => Unsupported
  }

  private def describe(subject: AnyRef): Shape = subject match {
    case memory: Mem[_] =>
      val leaves = memory.wordTypeLeaves
      val scalar = leaves.size == 1 && !leaves.head.parent.isInstanceOf[MultiData]
      val kind = if (scalar) scalarIntent(leaves.head) else UnsignedAggregate
      val metadata = ParameterizedMemory.metadataOf(memory)
      Shape(kind, MemoryElement, memory.getWidth, leaves.map(_.asInstanceOf[AnyRef]),
        metadata.toVector.flatMap(m => Vector(m.depth, m.elementWidth)),
        Vector(memory.wordCount), Vector(memory.parentScope))
    case aggregate: MultiData =>
      val shape = aggregate match { case vec: Vec[_] => ParameterizedVec.shapeOf(vec); case _ => None }
      // Native storage geometry is not the logical (possibly symbolic) Vec
      // size. Keep immediate children so nested Vec/Bundle widths retain every
      // factor instead of silently summing finite carrier capacity.
      val nativeBits = aggregate.flatten.foldLeft(BigInt(0))((sum, leaf) => sum + leaf.getBitsWidth)
      if (!nativeBits.isValidInt) reject("WIDTH-AUTHORITY", "aggregate native carrier width overflows Int")
      Shape(UnsignedAggregate, Aggregate, nativeBits.toInt,
        aggregate.elements.toVector.map(_._2.asInstanceOf[AnyRef]),
        shape.toVector.flatMap(s => Vector(s.depth) ++ s.elementLeaves.map(_.width)),
        Vector.empty, Vector(aggregate.parent))
    case expression: Expression =>
      val rule = primitiveRule(expression)
      // An arbitrary expression claiming TypeSInt is not a reviewed operator.
      val intent = expression match {
        case base: BaseType => scalarIntent(base)
        case _ if rule == Unsupported => Unknown
        case _ => typeIntent(expression.getTypeObject)
      }
      val nativeBits = expression match {
        case vector: BitVector if vector.isFixedWidth => vector.fixedWidth
        case width: WidthProvider => width.getWidth
        case _ if expression.getTypeObject == TypeBool => 1
        case _ => -1
      }
      val parameters = expression match {
        case bits: Bits if ParameterizedVec.packedShapeOf(bits).nonEmpty =>
          val s = ParameterizedVec.packedShapeOf(bits).get
          Vector(s.depth) ++ s.elementLeaves.map(_.width)
        case base: BaseType => ParameterizedWidth.expressionOf(base).toVector
        case resize: spinal.core.internals.Resize => ParameterizedWidth.resizeExpressionOf(resize).toVector
        case _ => Vector.empty
      }
      val details: Vector[Any] = expression match {
        case vector: BitVector => Vector(vector.getDirection, vector.isReg, vector.isAnalog, vector.isSuffix, vector.fixedWidth)
        case base: BaseType => Vector(base.getDirection, base.isReg, base.isAnalog, base.isSuffix)
        case literal: BitVectorLiteral => Vector(literal.value, literal.poisonMask, literal.hasSpecifiedBitCount)
        case literal: BoolLiteral => Vector(literal.value)
        case op: Operator.BitVector.ShiftRightByInt => Vector(op.shift)
        case op: Operator.BitVector.ShiftLeftByInt => Vector(op.shift)
        case op: Operator.BitVector.ShiftRightByIntFixedWidth => Vector(op.shift)
        case op: Operator.BitVector.ShiftLeftByIntFixedWidth => Vector(op.shift)
        case op: Operator.BitVector.Repeat => Vector(op.count)
        case op: Operator.Bool.Repeat => Vector(op.count)
        case access: BitVectorRangedAccessFixed => Vector(access.hi, access.lo)
        case access: BitVectorRangedAccessFloating => Vector(access.size)
        case access: BitVectorBitAccessFixed => Vector(access.bitId)
        case resize: spinal.core.internals.Resize => Vector(resize.size)
        case port: MemPortStatement => Vector(port.hasTag(AllowMixedWidth))
        case _ => Vector.empty
      }
      val owners = expression match {
        case base: BaseType => Vector(base.component, base.parentScope, base.parent,
          Option(base.component).map(_.parent).orNull)
        case port: MemPortStatement => Vector(port.mem, port.parentScope)
        case _ => Vector.empty
      }
      val memory = expression match { case port: MemPortStatement => Vector(port.mem); case _ => Vector.empty }
      val packed = expression.isInstanceOf[Bits] && parameters.size > 1
      Shape(if (packed) UnsignedAggregate else intent, if (packed) Aggregate else rule,
        nativeBits, expressionChildren(expression).map(_.asInstanceOf[AnyRef]) ++ memory,
        parameters, details, owners :+ expression.getTypeObject.asInstanceOf[AnyRef])
    case _ => reject("UNSUPPORTED-SUBJECT", "only exact native declarations, aggregates and expressions are admitted")
  }

  private def build(roots: Vector[Root]): Snapshot = {
    val indices = new IdentityHashMap[AnyRef, java.lang.Integer]()
    val entries = ArrayBuffer.empty[Entry]
    val widthIndices = new IdentityHashMap[ElaborationIntegerExpression, java.lang.Integer]()
    val widths = ArrayBuffer.empty[ElaborationIntegerExpression]
    def retained(value: ElaborationIntegerExpression): Width = {
      // The exact subject's parameters are validated before token allocation.
      // Root-free generate-index expressions are not necessarily constants.
      if (value.parameters.isEmpty && value.generateIndex.isEmpty && value.minimum == value.maximum)
        return Fixed(value.default)
      var id = widthIndices.get(value)
      if (id == null) {
        id = java.lang.Integer.valueOf(widths.size)
        widthIndices.put(value, id)
        widths += value
      }
      Retained(id.intValue)
    }
    def visit(subject: AnyRef): Fact = {
      if (subject == null) reject("NULL-SUBJECT", "a graph dependency must not be null")
      val existing = indices.get(subject)
      if (existing != null) {
        val entry = entries(existing.intValue)
        if (entry == null) reject("EXPRESSION-CYCLE", "non-reference expression dependency cycle")
        return entry.fact
      }
      val id = entries.size
      indices.put(subject, java.lang.Integer.valueOf(id))
      entries += null
      val shape = describe(subject)
      shape.parameters.foreach(validateWidth(_, subject))
      val children = shape.children.map(visit)
      val valueChildren = subject match {
        case mux: BinaryMultiplexer => Vector(visit(mux.whenTrue), visit(mux.whenFalse))
        case mux: Multiplexer => mux.inputs.toVector.map(visit)
        case _: MemPortStatement => Vector.empty[Fact]
        case _ => children
      }
      val baseWidth: Width = if (shape.nativeBits >= 0) Fixed(shape.nativeBits) else UnknownWidth
      val widthsOfChildren = valueChildren.map(_.width)
      def leftWidth: Width = widthsOfChildren.headOption.getOrElse(UnknownWidth)
      val width: Width = if (shape.rule == Unsupported) UnknownWidth else subject match {
        case _: MultiData | _: Bits if shape.rule == Aggregate && shape.parameters.nonEmpty =>
          Product(Vector(retained(shape.parameters.head), Sum(shape.parameters.tail.map(retained))))
        case _: MultiData => Sum(children.map(_.width))
        case _: Mem[_] =>
          if (shape.parameters.size == 2) retained(shape.parameters(1)) else Sum(children.map(_.width))
        // Inferred widths may be generalized at parameterized publication.
        // An unregistered carrier witness cannot establish a constant width.
        // Keep references terminal; the exact operator has separate evidence.
        case vector: BitVector if !vector.isFixedWidth && shape.parameters.isEmpty => UnknownWidth
        case _: BaseType => shape.parameters.headOption.map(retained).getOrElse(baseWidth)
        case resize: spinal.core.internals.Resize => shape.parameters.headOption.map(retained).getOrElse(Fixed(resize.size))
        case _: CastBitVectorToBitVector => leftWidth
        case _: CastBoolToBits => Fixed(1)
        case _: Operator.Bits.Cat | _: Operator.BitVector.Mul => Sum(widthsOfChildren)
        case _: Operator.BitVector.Div => leftWidth
        case _: Operator.BitVector.Mod => Minimum(widthsOfChildren)
        case op: Operator.BitVector.Repeat => Product(Vector(leftWidth, Fixed(op.count)))
        case op: Operator.Bool.Repeat => Fixed(op.count)
        case op: Operator.BitVector.ShiftLeftByInt => Sum(Vector(leftWidth, Fixed(op.shift)))
        case op: Operator.BitVector.ShiftRightByInt => Maximum(Vector(Fixed(0), Difference(leftWidth, Fixed(op.shift))))
        case _: Operator.BitVector.ShiftLeftByUInt => UnknownWidth
        case _: Operator.BitVector.ShiftOperator => leftWidth
        case port: MemPortStatement if shape.rule == MemoryRead =>
          if (!port.hasTag(AllowMixedWidth) && shape.nativeBits == port.mem.getWidth)
            visit(port.mem).width else UnknownWidth
        case _ if shape.rule == Arithmetic || shape.rule == MuxRule => Maximum(widthsOfChildren)
        case _ if shape.rule == Unary => leftWidth
        case _ if shape.rule == Unsupported => UnknownWidth
        case _ => baseWidth
      }
      var value = SignednessFacts.transfer(shape.rule, shape.kind, valueChildren.map(_.value))
      subject match {
        case literal: BitVectorLiteral if !literal.hasSpecifiedBitCount || literal.hasPoison() => value = Unknown
        case _ => ()
      }
      if (shape.rule == ResizeRule && leftWidth == width && width != UnknownWidth)
        value = valueChildren.headOption.map(_.value).getOrElse(Unknown)
      if (Set[Rule](Arithmetic, Unary, Shift, MuxRule).contains(shape.rule) && value != shape.kind)
        value = Unknown
      if (shape.rule == Reference || shape.rule == Aggregate) value = shape.kind
      val hierarchy = subject match {
        case base: BaseType => base.isIo && base.component != null &&
          (base.component.isInstanceOf[BlackBox] || base.component.parent != null)
        case _ => false
      }
      val requirements = (SignednessFacts.requirements(shape.rule) ++
        (if (hierarchy) Vector(HierarchyBoundary) else Vector.empty) ++
        (if (shape.rule == Reference && width == UnknownWidth) Vector(InferredWidthAuthority) else Vector.empty) ++
        (if (value == Unknown || !resolved(width) || children.exists(child => child.value == Unknown || child.requirements.contains(UnknownSemantics)))
          Vector(UnknownSemantics) else Vector.empty)).distinct
      val fact = Fact(id, shape.kind, value, shape.nativeBits, width, shape.rule, children.map(_.id), requirements)
      entries(id) = Entry(subject, shape, fact)
      fact
    }
    roots.foreach(root => visit(root.subject))
    val capturedUses = new IdentityHashMap[AnyRef, Set[Use]]()
    def mark(subject: AnyRef, use: Use): Boolean = {
      val previous = Option(capturedUses.get(subject)).getOrElse(Set.empty[Use])
      capturedUses.put(subject, previous + use)
      !previous.contains(use)
    }
    def markExpression(expression: Expression): Unit = {
      if (mark(expression, ExpressionUse)) expressionChildren(expression).foreach(markExpression)
    }
    // Only graph roots and real expression edges grant use roles. Shape-only
    // dependencies (notably Mem.wordTypeLeaves) are indexed for facts but do not
    // acquire declaration/expression/temporary evidence merely by being indexed.
    roots.foreach { root =>
      root.use match {
        case ExpressionUse => markExpression(root.subject.asInstanceOf[Expression])
        case DeclarationUse =>
          mark(root.subject, DeclarationUse)
          markExpression(root.subject.asInstanceOf[Expression])
        case MemoryElementUse | AggregateUse => mark(root.subject, root.use)
        case _ => reject("USE-ROLE", "unsupported role at native graph capture")
      }
    }
    new Snapshot(entries.toVector, indices, widths.toVector, capturedUses)
  }
}
