package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import morphhdl.ir.v1.{CanonicalIrSchema, Declaration, DeclarationKind, Design,
  Driver, DriverCoverage, DriverId, DriverKind, IntExpr, IntegerParameter,
  IntegerParameterDomain, Module, ModuleId, NameOrigin, Observability, PackedType,
  PackedValueSemantics, ParameterId, PortDirection, Scope, ScopeId, ScopeKind,
  Signedness, SymbolId}
import morphhdl.passes.api.{IrSymbolId, PassExecutionStatus, PassId,
  WireAliasPassConfiguration}
import morphhdl.passes.transform.{NamedWireExpressionEliminationPass,
  UnnamedWireExpressionEliminationPass}
import spinal.core._
import spinal.core.internals._

/**
  * Native writeback for the named/generated expression-elimination successor.
  *
  * Candidates come solely from pre-allocation `Nameable` provenance. USER_* and
  * DATAMODEL_* names are meaningful; REMOVABLE names are compiler-generated
  * candidates. A genuinely unnamed declaration remains the responsibility of
  * the historical unnamed-expression phase. The complete native RHS and every
  * receiver RHS must be representable by [[NativeWireExpressionCodec]] or the
  * candidate is retained.
  */
private[examples] final class NamedWireExpressionNativePhase(unnamedOnly: Boolean = false) extends Phase {
  private var completed = false
  private var visited = 0
  private var eliminated = Vector.empty[Int]
  private var eliminatedOrigins = Vector.empty[String]
  private var eliminatedNames = Vector.empty[String]
  private var rejected = Map.empty[String, Int]
  private var rewrittenReferences = 0
  private var proceduralReceiverRewrites = 0
  private var operators = Vector.empty[String]

  def report: NamedWireExpressionNativeReport = {
    if (!completed)
      throw new IllegalStateException("WA-09 native expression phase did not execute")
    NamedWireExpressionNativeReport(
      visitedCandidates = visited,
      eliminatedOrdinals = eliminated,
      eliminatedOrigins = eliminatedOrigins,
      eliminatedNames = eliminatedNames,
      rejectedByReason = rejected,
      rewrittenReferences = rewrittenReferences,
      expressionOperators = operators,
      proceduralReceiverRewrites = proceduralReceiverRewrites
    )
  }

  override def hasNetlistImpact: Boolean = true

  /** Used by the preceding direct-alias phase only to justify a no-op defer. */
  private[examples] def isIndependentlyRemovable(
      pc: PhaseContext,
      value: BaseType
  ): Boolean =
    NativeWireNameProvenance.origin(value).exists { origin =>
      isIndependentlyRemovableWithOrigin(pc, value, origin)
    }

  /**
    * Prove one exact native expression identity against the canonical pass for
    * its captured provenance. The preceding direct-alias phase uses this only
    * to decide whether it must defer; no native graph mutation occurs here.
    */
  private[examples] def isIndependentlyRemovableWithOrigin(
      pc: PhaseContext,
      value: BaseType,
      origin: NameOrigin
  ): Boolean = {
    val statements = pc.components().toVector.flatMap(statementsOf)
    candidateForValue(value, origin, statements).exists { candidate =>
      proveCandidate(pc, candidate) match {
        case Right(proof) => applyCanonicalDecision(candidate, proof).isRight
        case Left(_)      => false
      }
    }
  }

  override def impl(pc: PhaseContext): Unit = {
    if (completed)
      throw new IllegalStateException("WA-09 native expression phase executed more than once")

    val eliminatedBuilder = Vector.newBuilder[Int]
    val originBuilder = Vector.newBuilder[String]
    val nameBuilder = Vector.newBuilder[String]
    val operatorBuilder = Vector.newBuilder[String]
    var nextOrdinal = 0
    var progress = true

    while (progress) {
      progress = false
      val iterator = candidateSnapshot(pc).iterator
      while (iterator.hasNext && !progress) {
        val candidate = iterator.next()
        val ordinal = nextOrdinal
        nextOrdinal += 1
        visited += 1

        proveCandidate(pc, candidate) match {
          case Left(reason) => reject(reason)
          case Right(proof) =>
            applyCanonicalDecision(candidate, proof) match {
              case Left(reason) => reject(reason)
              case Right(_) =>
                val replacements = rewriteNativeIdentity(candidate)
                if (replacements < 1)
                  throw new IllegalStateException(
                    "WA-09 native expression rewrite removed a declaration without replacing a receiver"
                  )
                rewrittenReferences += replacements
                eliminatedBuilder += ordinal
                originBuilder += originLabel(candidate.nameOrigin)
                nameBuilder += candidate.nameOrigin.explicitName.getOrElse("")
                operatorBuilder += candidate.sourceExpression.opName
                progress = true
            }
        }
      }
    }

    eliminated = eliminatedBuilder.result()
    eliminatedOrigins = originBuilder.result()
    eliminatedNames = nameBuilder.result()
    operators = operatorBuilder.result()
    completed = true
  }

  private def reject(reason: String): Unit =
    rejected = rejected.updated(reason, rejected.getOrElse(reason, 0) + 1)

  private final case class NativeCandidate(
      component: Component,
      alias: BaseType,
      nameOrigin: NameOrigin,
      sourceExpression: Expression,
      sourceReferences: Vector[BaseType],
      assignment: DataAssignmentStatement,
      useStatements: Vector[Statement]
  ) {
    def receiverOccurrenceCount: Int =
      useStatements.map(references(_, alias)).sum
  }

  private final case class NativeProof(
      packedType: PackedType,
      parameters: Vector[IntegerParameter]
  )

  private def candidateSnapshot(pc: PhaseContext): Vector[NativeCandidate] = {
    val values = Vector.newBuilder[NativeCandidate]
    val statements = pc.components().toVector.flatMap(statementsOf)
    pc.components().foreach { component =>
      component.dslBody.walkDeclarations {
        case alias: BaseType =>
          val origin = if (unnamedOnly)
            NativeWireNameProvenance.origin(alias).filter(_ == NameOrigin.Unnamed)
          else NativeWireNameProvenance.successorExpressionOrigin(alias)
          origin.foreach(value => candidateForValue(alias, value, statements).foreach(values += _))
        case _ =>
      }
    }
    values.result()
  }

  private def candidateForValue(
      alias: BaseType,
      origin: NameOrigin,
      statements: Vector[Statement]
  ): Option[NativeCandidate] = {
    val provenanceMatches = origin match {
      case NameOrigin.Unnamed => alias.isUnnamed
      case NameOrigin.Explicit(_) | NameOrigin.Reflected(_) | NameOrigin.Generated =>
        alias.isNamed
      case NameOrigin.Unknown => false
    }
    if (
      !provenanceMatches || !alias.isComb || !alias.isDirectionLess ||
      alias.isAnalog || alias.isTypeNode || alias.parentScope == null ||
      !(alias.parentScope eq alias.rootScopeStatement) ||
      !alias.hasOnlyOneStatement
    ) return None

    alias.head match {
      case assignment: DataAssignmentStatement
          if assignment.parentScope != null &&
            (assignment.parentScope eq alias.rootScopeStatement) &&
            (assignment.target eq alias) &&
            (assignment.finalTarget eq alias) &&
            assignment.source != null =>
        assignment.source match {
          case _: BaseType => None
          case expression =>
            val uses = statements.filter { statement =>
              (statement ne assignment) && references(statement, alias) > 0
            }
            Some(
              NativeCandidate(
                alias.component,
                alias,
                origin,
                expression,
                referencedBaseTypes(expression).distinct,
                assignment,
                uses
              )
            )
        }
      case _ => None
    }
  }

  private def proveCandidate(
      pc: PhaseContext,
      candidate: NativeCandidate
  ): Either[String, NativeProof] = {
    val alias = candidate.alias
    val sharedSafety = new NamedWireAliasNativePhase

    if (candidate.useStatements.isEmpty)
      Left("WA09-NATIVE-NO-RECEIVER")
    else if (candidate.useStatements.exists(selectedAliasUse(_, alias)))
      Left("WA09-NATIVE-SELECTED-RECEIVER")
    else if ((!NativeWireExpressionCodec.fixedWidthTree(candidate.sourceExpression) ||
        ParameterizedWidth.expressionOf(alias).nonEmpty) &&
        !candidate.useStatements.forall {
          case assignment: DataAssignmentStatement =>
            (assignment.source eq alias) && samePackedBoundary(assignment.finalTarget, alias)
          case _ => false
        })
      // The existing exact symbolic boundary proof remains deliberately
      // narrower than the fixed-width nested-expression path.
      Left("WA10-NATIVE-SYMBOLIC-RECEIVER-PACKED-BOUNDARY")
    else if (alias.isInstanceOf[SInt] &&
        !candidate.sourceExpression.isInstanceOf[Literal] &&
        !candidate.useStatements.forall {
          case assignment: DataAssignmentStatement =>
            (assignment.source eq alias) && samePackedBoundary(assignment.finalTarget, alias)
          case _ => false
        })
      // The Verilog-2001 emitter currently needs declared signed arithmetic
      // boundaries in nested/mixed receivers. Retain this identity instead of
      // replacing one fence with several emitter-created fences.
      Left("WA10-NATIVE-SIGNED-RECEIVER-BOUNDARY")
    else if (candidate.receiverOccurrenceCount > 32 ||
        expressionNodeCount(candidate.sourceExpression) > 64 ||
        candidate.receiverOccurrenceCount * expressionNodeCount(candidate.sourceExpression) > 256)
      Left("WA10-NATIVE-EXPRESSION-EXPANSION-BUDGET")
    else if (NativePureExpressionCopy(candidate.sourceExpression).isEmpty ||
        !candidate.useStatements.forall {
          case assignment: DataAssignmentStatement =>
            NativePureExpressionCopy(assignment.source).nonEmpty
          case _ => false
        })
      Left("WA10-NATIVE-EXPRESSION-COPY-UNREPRESENTED")
    else
      sharedSafety.expressionRemovalBlocker(
        pc,
        candidate.component,
        alias,
        candidate.assignment,
        candidate.useStatements,
        allowRegisterRhs = true
      ) match {
        case Some(reason) => Left("WA09-NATIVE-" + reason)
        case None if candidate.receiverOccurrenceCount < 1 =>
          Left("WA09-NATIVE-NO-RECEIVER")
        case None if candidate.sourceReferences.exists { source =>
              (source eq alias) || (source.component ne candidate.component) ||
              (source.isDirectionLess &&
                (source.parentScope == null ||
                  !(source.parentScope eq source.rootScopeStatement)))
            } =>
          Left("WA09-NATIVE-SOURCE-BOUNDARY")
        case None if candidate.sourceReferences.exists(source =>
              source.isAnalog || source.isInOut) =>
          Left("WA09-NATIVE-SOURCE-KIND")
        case None if createsCycle(candidate) =>
          Left("WA09-NATIVE-CYCLE")
        case None =>
          packedTypeProof(alias, candidate.sourceExpression) match {
            case None => Left("WA09-NATIVE-PACKED-TYPE")
            case Some(proof) =>
              canonicalSnapshot(candidate, proof) match {
                case None    => Left("WA09-NATIVE-EXPRESSION-UNREPRESENTED")
                case Some(_) => Right(proof)
              }
          }
      }
  }

  private def packedTypeProof(
      alias: BaseType,
      expression: Expression
  ): Option[NativeProof] = {
    val expressionWidth = expression match {
      case value: WidthProvider => value.getWidth
      // Native Bool operators carry TypeBool but do not implement
      // WidthProvider; their packed width is exactly one bit.
      case _ if expression.getTypeObject == TypeBool => 1
      case _                                         => return None
    }
    if (
      expressionWidth != alias.getBitsWidth || alias.getBitsWidth < 1 ||
      expression.getTypeObject != alias.getTypeObject
    ) return None

    val semantics = packedSemantics(alias).getOrElse(return None)
    ParameterizedWidth.expressionOf(alias) match {
      case None =>
        Some(
          NativeProof(
            PackedType(IntExpr.Literal(BigInt(alias.getBitsWidth)), semantics._1, semantics._2),
            Vector.empty
          )
        )
      case Some(width) =>
        val minimum = width.minimum
        val maximum = width.maximum
        val size = maximum - minimum + 1
        if (
          minimum < 1 || maximum < minimum ||
          size > BigInt(morphhdl.ir.v1.CanonicalIrValidator.MaximumParameterDomainSize)
        ) None
        else {
          val parameterId = ParameterId.unsafe("parameter.native-named-expression-width")
          Some(
            NativeProof(
              PackedType(IntExpr.ParameterRef(parameterId), semantics._1, semantics._2),
              Vector(
                IntegerParameter(
                  parameterId,
                  "NATIVE_NAMED_EXPRESSION_WIDTH",
                  width.default,
                  IntegerParameterDomain(minimum, maximum, (minimum to maximum).toVector)
                )
              )
            )
          )
        }
    }
  }

  private def packedSemantics(
      value: BaseType
  ): Option[(Signedness, PackedValueSemantics)] = value match {
    case _: Bool => Some(Signedness.Unsigned -> PackedValueSemantics.Boolean)
    case _: Bits => Some(Signedness.Unsigned -> PackedValueSemantics.BitVector)
    case _: UInt => Some(Signedness.Unsigned -> PackedValueSemantics.UnsignedInteger)
    case _: SInt => Some(Signedness.Signed -> PackedValueSemantics.SignedInteger)
    case _       => None
  }

  private def samePackedBoundary(value: BaseType, alias: BaseType): Boolean =
    value.getBitsWidth == alias.getBitsWidth &&
      packedSemantics(value).nonEmpty &&
      packedSemantics(value) == packedSemantics(alias) &&
      ((ParameterizedWidth.expressionOf(value), ParameterizedWidth.expressionOf(alias)) match {
        case (None, None)              => true
        case (Some(left), Some(right)) => sameRetainedWidth(left, right)
        case _                        => false
      })

  private def sameRetainedWidth(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean = {
    // Independent declarations of Bits(width bits) retain separate expression
    // descriptors and source locations for the same authoritative parameter.
    // Compare that retained authority, never names or concrete witnesses alone.
    val exactRoots = (left.parameters.isEmpty || left.parameterRoots.nonEmpty) &&
      (right.parameters.isEmpty || right.parameterRoots.nonEmpty) &&
      left.parameterRoots.size == right.parameterRoots.size &&
      left.parameterRoots.zip(right.parameterRoots).forall { case (l, r) => l eq r }
    if (!exactRoots || left.verilog != right.verilog ||
        left.default != right.default || left.minimum != right.minimum ||
        left.maximum != right.maximum || left.parameters != right.parameters ||
        left.generateIndex != right.generateIndex) false
    else try {
      ElabInt.fromExpression(left).elabEq(ElabInt.fromExpression(right)).isAlwaysTrue
    } catch {
      case NonFatal(_) => false
    }
  }

  private final case class CanonicalSnapshot(
      design: Design,
      aliasId: SymbolId,
      nonblockingReceivers: Vector[(ModuleId, Driver)]
  )

  private def canonicalSnapshot(
      candidate: NativeCandidate,
      proof: NativeProof
  ): Option[CanonicalSnapshot] = {
    val moduleId = ModuleId.unsafe("module.native-named-expression")
    val scopeId = ScopeId.unsafe("scope.native-named-expression")
    val aliasId = SymbolId.unsafe("symbol.native-named-expression.alias")
    val receiverTargets = candidate.useStatements.collect {
      case assignment: DataAssignmentStatement => assignment.finalTarget
    }.distinct
    val targetPairs = receiverTargets.zipWithIndex.map { case (target, index) =>
      target -> SymbolId.unsafe(s"symbol.native-named-expression.receiver.$index")
    }
    val codec = new NativeWireExpressionCodec(
      scopeId,
      "native-named-expression",
      (Vector(candidate.alias -> aliasId) ++ targetPairs).distinct
    )
    val sourceExpression = codec.capture(candidate.sourceExpression).getOrElse(return None)
    val nativeScopes = new java.util.IdentityHashMap[ScopeStatement, String]()
    nativeScopes.put(candidate.alias.rootScopeStatement, scopeId.value)
    val snapshotScopes = ArrayBuffer(Scope(scopeId, None, ScopeKind.Module))
    def captureScope(scope: ScopeStatement): ScopeId = {
      val existing = nativeScopes.get(scope)
      if (existing != null) ScopeId.unsafe(existing)
      else {
        require(scope != null && scope.parentStatement != null,
          "WA-10 receiver scope is not owned by the candidate component")
        val parent = captureScope(scope.parentStatement.parentScope)
        val id = ScopeId.unsafe(s"scope.native-named-expression.receiver.${snapshotScopes.size}")
        nativeScopes.put(scope, id.value)
        snapshotScopes += Scope(id, Some(parent), ScopeKind.Block)
        id
      }
    }
    val receiverValues = candidate.useStatements.map {
      case assignment: DataAssignmentStatement =>
        codec.captureInScope(assignment.source, captureScope(assignment.parentScope))
          .getOrElse(return None)
      case _ => return None
    }
    if (codec.capturedSources.exists { case (native, _) => packedSemantics(native).isEmpty })
      return None

    // A fixed-width alias (for example a Bool comparison) can read one
    // retained symbolic width family. Enroll that family explicitly instead
    // of replacing its WIDTH by a concrete witness. A symbolic alias already
    // owns its one family; unrelated roots or expressions remain ineligible.
    var sourceWidthFamily = ParameterizedWidth.expressionOf(candidate.alias)
    var sourceWidthType = if (sourceWidthFamily.nonEmpty) Some(proof.packedType.width) else None
    val snapshotParameters = ArrayBuffer.empty[IntegerParameter] ++ proof.parameters
    codec.capturedSources.foreach { case (native, _) =>
      ParameterizedWidth.expressionOf(native).foreach { width =>
        sourceWidthFamily match {
          case Some(existing) if !sameRetainedWidth(existing, width) => return None
          case Some(_) =>
          case None =>
            try ElabInt.fromExpression(width)
            catch { case NonFatal(_) => return None }
            val minimum = width.minimum
            val maximum = width.maximum
            if (minimum < 1 || maximum < minimum ||
                maximum - minimum + 1 >
                  BigInt(morphhdl.ir.v1.CanonicalIrValidator.MaximumParameterDomainSize))
              return None
            val parameterId = ParameterId.unsafe("parameter.native-named-expression-source-width")
            sourceWidthFamily = Some(width)
            sourceWidthType = Some(IntExpr.ParameterRef(parameterId))
            snapshotParameters += IntegerParameter(
              parameterId,
              "NATIVE_NAMED_EXPRESSION_SOURCE_WIDTH",
              width.default,
              IntegerParameterDomain(minimum, maximum, (minimum to maximum).toVector)
            )
        }
      }
    }

    val declarations = codec.capturedSources.map { case (native, id) =>
      Declaration(
        id = id,
        owner = scopeId,
        kind = if (native eq candidate.alias) DeclarationKind.InternalCombinational
          else sourceKind(native),
        packedType = Some(packedTypeFor(native, sourceWidthFamily, sourceWidthType)
          .getOrElse(return None)),
        nameOrigin = if (native eq candidate.alias) candidate.nameOrigin
          else NativeWireNameProvenance.origin(native).getOrElse(NameOrigin.Unknown),
        sourceLocation = None,
        observability = if (native eq candidate.alias) Observability.Unobserved
          else sourceObservability(native)
      )
    }
    val receiverDrivers = candidate.useStatements.zip(receiverValues).zipWithIndex.map {
      case ((assignment: DataAssignmentStatement, value), index) =>
        val targetId = targetPairs.find(_._1 eq assignment.finalTarget).get._2
        Driver(
          DriverId.unsafe(s"driver.native-named-expression.receiver.$index"),
          captureScope(assignment.parentScope),
          targetId,
          if (assignment.finalTarget.isReg) DriverKind.Procedural else DriverKind.Continuous,
          DriverCoverage.FullObject,
          value
        )
      case _ => return None
    }
    val aliasDriver = Driver(
      DriverId.unsafe("driver.native-named-expression.alias"),
      scopeId,
      aliasId,
      DriverKind.Continuous,
      DriverCoverage.FullObject,
      sourceExpression
    )
    val design = Design(
      CanonicalIrSchema.schemaVersion,
      CanonicalIrSchema.stage,
      moduleId,
      Vector(
        Module(
          moduleId,
          "NativeNamedExpression",
          snapshotParameters.toVector,
          snapshotScopes.toVector,
          Vector.empty,
          declarations,
          aliasDriver +: receiverDrivers
        )
      )
    )
    Some(CanonicalSnapshot(design, aliasId,
      receiverDrivers.filter(driver => driver.kind == DriverKind.Procedural &&
        declarations.exists(value => value.id == driver.target &&
          value.kind == DeclarationKind.Register)).map(moduleId -> _)))
  }

  private def applyCanonicalDecision(
      candidate: NativeCandidate,
      proof: NativeProof
  ): Either[String, Unit] = {
    val snapshot = canonicalSnapshot(candidate, proof).getOrElse(
      return Left("WA09-NATIVE-EXPRESSION-UNREPRESENTED")
    )
    val result = candidate.nameOrigin match {
      case NameOrigin.Unnamed =>
        UnnamedWireExpressionEliminationPass.runWithNativeNonblockingReceivers(
          snapshot.design,
          WireAliasPassConfiguration.selectedForTesting(
            morphhdl.passes.api.PassId.UnnamedWireExpressionElimination
          ), snapshot.nonblockingReceivers
        )
      case NameOrigin.Explicit(_) | NameOrigin.Reflected(_) | NameOrigin.Generated =>
        NamedWireExpressionEliminationPass.runWithNativeNonblockingReceivers(
          snapshot.design,
          WireAliasPassConfiguration.selectedForTesting(
            morphhdl.passes.api.PassId.NamedWireExpressionElimination
          ), snapshot.nonblockingReceivers
        )
      case NameOrigin.Unknown =>
        return Left("WA09-NATIVE-UNKNOWN-PROVENANCE")
    }
    val eliminated = result.eliminationReport.eliminatedExpressions
    if (
      result.status == PassExecutionStatus.Changed &&
      eliminated.map(_.aliasSymbol) == Vector(IrSymbolId.unsafe(snapshot.aliasId.value)) &&
      eliminated.head.receiverCount == candidate.receiverOccurrenceCount
    ) Right(())
    else Left(result.eliminationReport.rejected.headOption.map(_.reasonCode)
      .getOrElse("WA09-NATIVE-CANONICAL-DECISION"))
  }

  private def packedTypeFor(
      value: BaseType,
      sourceWidthFamily: Option[ElaborationIntegerExpression],
      sourceWidthType: Option[IntExpr]
  ): Option[PackedType] = {
    val semantics = packedSemantics(value).getOrElse(return None)
    ParameterizedWidth.expressionOf(value) match {
      case None =>
        Some(PackedType(IntExpr.Literal(BigInt(value.getBitsWidth)), semantics._1, semantics._2))
      case Some(width) =>
        // The bounded snapshot owns only one exact symbolic width family.
        // Never replace another retained expression by its elaborated witness.
        for {
          family <- sourceWidthFamily if sameRetainedWidth(family, width)
          packedWidth <- sourceWidthType
        } yield PackedType(packedWidth, semantics._1, semantics._2)
    }
  }

  private def sourceKind(value: BaseType): DeclarationKind =
    if (value.isInput) DeclarationKind.Port(PortDirection.Input)
    else if (value.isOutput) DeclarationKind.Port(PortDirection.Output)
    else if (value.isReg) DeclarationKind.Register
    else DeclarationKind.InternalCombinational

  private def sourceObservability(value: BaseType): Observability =
    Observability(
      complete = true,
      externallyVisible = value.isInput || value.isOutput
    )

  private def rewriteNativeIdentity(candidate: NativeCandidate): Int = {
    // Clone receiver trees first: two statements may share native operator
    // objects. Mutating a shared subtree in place can silently rewrite a use
    // outside this receiver or make occurrence accounting depend on order.
    val receivers = candidate.useStatements.map {
      case assignment: DataAssignmentStatement =>
        assignment -> NativePureExpressionCopy(assignment.source).getOrElse(
          throw new IllegalStateException("WA-10 proven receiver copy became unsupported"))
      case _ => throw new IllegalStateException("WA-10 unsupported native receiver")
    }
    var replacements = 0
    receivers.foreach { case (assignment, copiedSource) =>
      // An exact whole-RHS assignment already supplies the removed wire's
      // packed fence, including retained width identity. An additional Resize
      // there needlessly recreates a wrapper with the legacy emitter. Nested
      // or differently typed receivers still require an explicit native fence.
      val receiverSuppliesFence = (copiedSource eq candidate.alias) &&
        samePackedBoundary(assignment.finalTarget, candidate.alias)
      assignment.source = copiedSource
      assignment.walkRemapDrivingExpressions {
        case reference: BaseType if reference eq candidate.alias =>
          replacements += 1
          if (assignment.finalTarget.isReg) proceduralReceiverRewrites += 1
          val copied = NativePureExpressionCopy(candidate.sourceExpression).getOrElse(
            throw new IllegalStateException("WA-10 proven source copy became unsupported"))
          if (!receiverSuppliesFence && ParameterizedWidth.expressionOf(candidate.alias).isEmpty &&
              NativeWireExpressionCodec.fixedWidthTree(candidate.sourceExpression))
            NativeWireExpressionCodec.fenced(copied, candidate.alias)
          else copied
        case other => other
      }
    }
    val remaining = statementsOf(candidate.component).map(references(_, candidate.alias)).sum
    if (remaining != 0)
      throw new IllegalStateException(
        s"WA-10 native expression rewrite left $remaining reference(s) to an inlined identity"
      )
    candidate.assignment.removeStatement()
    candidate.alias.removeStatement()
    replacements
  }

  private def selectedAliasUse(statement: Statement, alias: BaseType): Boolean = {
    var selected = false
    statement.walkDrivingExpressions {
      case access: SubAccess if expressionReferences(access.getBitVector, alias) =>
        selected = true
      case _ =>
    }
    selected
  }

  private def expressionReferences(expression: Expression, target: BaseType): Boolean = {
    if (expression == null) false
    else if (expression eq target) true
    else {
      var found = false
      expression.walkDrivingExpressions {
        case value: BaseType if value eq target => found = true
        case _                                  =>
      }
      found
    }
  }

  private def createsCycle(candidate: NativeCandidate): Boolean = {
    if (candidate.sourceReferences.exists(_ eq candidate.alias)) return true
    val edges = scala.collection.mutable.LinkedHashMap.empty[BaseType, Vector[BaseType]]
    statementsOf(candidate.component).foreach {
      case assignment: DataAssignmentStatement
          if assignment.parentScope != null &&
            (assignment.parentScope eq assignment.rootScopeStatement) &&
            (assignment.target eq assignment.finalTarget) &&
            assignment.finalTarget.isComb =>
        edges.update(
          assignment.finalTarget,
          referencedBaseTypes(assignment.source)
            .filter(_.component eq candidate.component).distinct
        )
      case _ =>
    }
    candidate.sourceReferences.exists { source =>
      val pending = scala.collection.mutable.Stack[BaseType](source)
      val visited = scala.collection.mutable.HashSet.empty[BaseType]
      var found = false
      while (pending.nonEmpty && !found) {
        val current = pending.pop()
        if (current eq candidate.alias) found = true
        else if (!visited.contains(current)) {
          visited += current
          edges.getOrElse(current, Vector.empty).reverse.foreach(pending.push)
        }
      }
      found
    }
  }

  private def expressionNodeCount(expression: Expression): Int = {
    var count = 1
    expression.walkDrivingExpressions(_ => count += 1)
    count
  }

  private def statementsOf(component: Component): Vector[Statement] = {
    val values = Vector.newBuilder[Statement]
    component.dslBody.walkStatements(values += _)
    values.result()
  }

  private def references(statement: Statement, target: BaseType): Int = {
    var count = 0
    statement.walkDrivingExpressions {
      case value: BaseType if value eq target => count += 1
      case _                                  =>
    }
    count
  }

  private def referencedBaseTypes(expression: Expression): Vector[BaseType] = {
    val values = Vector.newBuilder[BaseType]
    expression match {
      case value: BaseType => values += value
      case _               =>
    }
    expression.walkDrivingExpressions {
      case value: BaseType => values += value
      case _               =>
    }
    values.result()
  }

  private def originLabel(value: NameOrigin): String = value match {
    case _: NameOrigin.Explicit  => "explicit"
    case _: NameOrigin.Reflected => "reflected"
    case NameOrigin.Generated    => "generated"
    case NameOrigin.Unnamed      => "unnamed"
    case NameOrigin.Unknown      => "unknown"
  }
}

private[examples] final case class NamedWireExpressionNativeReport(
    visitedCandidates: Int,
    eliminatedOrdinals: Vector[Int],
    eliminatedOrigins: Vector[String],
    eliminatedNames: Vector[String],
    rejectedByReason: Map[String, Int],
    rewrittenReferences: Int,
    expressionOperators: Vector[String],
    proceduralReceiverRewrites: Int = 0
) {
  def eliminatedCount: Int = eliminatedOrdinals.size
}

/** One immutable WA-09 FIFO selection: its four stages or all current six stages. */
private[examples] final class NamedWireExpressionPipelineNativePhase(all: Boolean)
    extends Phase {
  private var completed = false
  private var rounds = 0
  private var eliminated = Vector.fill(4)(0)
  private var constantRewrites = 0
  private var ternaryRewrites = 0
  private var executionRounds = Vector.empty[Vector[PassId]]

  override def hasNetlistImpact: Boolean = true

  override def impl(pc: PhaseContext): Unit = {
    if (completed)
      throw new IllegalStateException("WA-09 native pipeline phase executed more than once")
    var progress = true
    while (progress) {
      rounds += 1
      if (rounds > 1024)
        throw new IllegalStateException("WA-09 native pipeline failed to converge")

      var roundEliminated = Vector.fill(4)(0)
      var roundConstant = 0
      var roundTernary = 0
      var executed = Vector.empty[PassId]
      if (all) {
        val unnamedAlias = new UnnamedWireAliasNativePhase
        unnamedAlias.impl(pc)
        executed :+= PassId.UnnamedWireAliasElimination
        val namedAlias = new NamedWireAliasNativePhase(deferPreferredExpressionSource = true)
        namedAlias.impl(pc)
        executed :+= PassId.NamedWireAliasElimination
        val unnamedExpression = new UnnamedWireExpressionNativePhase
        unnamedExpression.impl(pc)
        executed :+= PassId.UnnamedWireExpressionElimination
        val namedExpression = new NamedWireExpressionNativePhase
        namedExpression.impl(pc)
        executed :+= PassId.NamedWireExpressionElimination
        roundEliminated = Vector(
          unnamedAlias.report.eliminatedCount,
          namedAlias.report.eliminatedCount,
          unnamedExpression.report.eliminatedCount,
          namedExpression.report.eliminatedCount
        )
        val constant = new ConstantOperandNativePhase
        constant.impl(pc)
        executed :+= PassId.ConstantOperandSimplification
        roundConstant = constant.changedCount
        val ternary = new BooleanTernaryNativePhase
        ternary.impl(pc)
        executed :+= PassId.BooleanTernarySimplification
        roundTernary = ternary.changedCount
      } else {
        // The FIFO's source-level named expression is exposed only after its
        // three historical wire helpers are collapsed. Keep those prerequisites
        // explicit in this new runner without changing any archived runner.
        val unnamedAlias = new UnnamedWireAliasNativePhase
        unnamedAlias.impl(pc)
        executed :+= PassId.UnnamedWireAliasElimination
        val namedAlias = new NamedWireAliasNativePhase(deferPreferredExpressionSource = true)
        namedAlias.impl(pc)
        executed :+= PassId.NamedWireAliasElimination
        val unnamedExpression = new UnnamedWireExpressionNativePhase
        unnamedExpression.impl(pc)
        executed :+= PassId.UnnamedWireExpressionElimination
        val namedExpression = new NamedWireExpressionNativePhase
        namedExpression.impl(pc)
        executed :+= PassId.NamedWireExpressionElimination
        roundEliminated = Vector(
          unnamedAlias.report.eliminatedCount,
          namedAlias.report.eliminatedCount,
          unnamedExpression.report.eliminatedCount,
          namedExpression.report.eliminatedCount
        )
      }

      val expected = if (all) WireAliasPassConfiguration(enabled = true).enabledPasses
      else WireAliasPassConfiguration.selectedForTesting(
        (PassId.historicalWireAssignmentPasses :+
          PassId.NamedWireExpressionElimination): _*
      ).enabledPasses
      if (executed != expected)
        throw new IllegalStateException(
          "WA-09 native execution order differs from the canonical selection"
        )
      executionRounds :+= executed
      eliminated = eliminated.zip(roundEliminated).map { case (left, right) => left + right }
      constantRewrites += roundConstant
      ternaryRewrites += roundTernary
      progress = roundEliminated.sum + roundConstant + roundTernary > 0
    }
    completed = true
  }

  def namedExpressionEliminatedCount: Int = {
    require(completed, "WA-09 native pipeline phase did not execute")
    eliminated(3)
  }

  def toJson: String = {
    require(completed, "WA-09 native pipeline phase did not execute")
    val passes = executionRounds.headOption.getOrElse(Vector.empty)
    val passIds = passes.map(pass => quote(pass.value)).mkString(", ")
    val roundIds = executionRounds.map(round =>
      round.map(pass => quote(pass.value)).mkString("[", ", ", "]")
    ).mkString(", ")
    Vector(
      "{",
      "  \"schema_version\": 1,",
      s"""  "pass_id": ${quote(passes.map(_.value).mkString("+"))},""",
      s"""  "executed_passes": [$passIds],""",
      s"""  "executed_rounds": [$roundIds],""",
      s"""  "common_flag_enabled": $all,""",
      "  \"executed_before_name_allocation\": true,",
      "  \"actual_rhs_capture_writeback\": true,",
      "  \"receiver_shape\": \"whole-rhs-only\",",
      s"""  "rounds": $rounds,""",
      s"""  "unnamed_alias_eliminated_count": ${eliminated(0)},""",
      s"""  "named_alias_eliminated_count": ${eliminated(1)},""",
      s"""  "unnamed_expression_eliminated_count": ${eliminated(2)},""",
      s"""  "named_expression_eliminated_count": ${eliminated(3)},""",
      s"""  "constant_simplified_assignment_count": $constantRewrites,""",
      s"""  "ternary_simplified_assignment_count": $ternaryRewrites""",
      "}",
      ""
    ).mkString("\n")
  }

  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private[examples] object NamedWireExpressionWitnessPhasePlan {
  def install(
      config: SpinalConfig,
      phase: Option[Phase]
  ): Unit = {
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val nativeCleanup = phases.zipWithIndex.collect {
        case (_: PhaseRemoveIntermediateUnnameds, index) => index
      }
      if (nativeCleanup.size < 3)
        throw new IllegalStateException(
          s"WA-09 witness expected three native intermediate-removal phases, found ${nativeCleanup.size}"
        )
      phases.update(nativeCleanup(1), new PhaseRemoveIntermediateUnnameds(true))
      nativeCleanup.drop(3).reverse.foreach(index => phases.remove(index))
      phase match {
        case Some(value) => phases.update(nativeCleanup(2), value)
        case None        => phases.remove(nativeCleanup(2))
      }
    }
  }
}

/**
  * Emits the FIFO reference, named-expression-only result, or current six-pass
  * result using stable filenames suitable for strict compilation/formal proofs.
  */
object ParameterizedStreamFifoNamedExpressionPassWitness {
  def main(args: Array[String]): Unit = {
    if (args.length != 4)
      throw new IllegalArgumentException(
        "usage: MODE(reference|named-expression|all) OUTPUT_DIRECTORY OUTPUT_FILE REPORT_FILE"
      )
    val mode = args(0)
    val expectedFile = mode match {
      case "reference"        => "wire-expression-reference.v"
      case "named-expression" => "wire-expression-named.v"
      case "all"              => "wire-assignment-six-pass.v"
      case other => throw new IllegalArgumentException(s"unsupported WA-09 mode '$other'")
    }
    if (args(2) != expectedFile)
      throw new IllegalArgumentException(
        s"WA-09 mode '$mode' requires output filename '$expectedFile'"
      )
    val phase = mode match {
      case "reference"        => None
      case "named-expression" => Some(new NamedWireExpressionPipelineNativePhase(all = false))
      case "all"              => Some(new NamedWireExpressionPipelineNativePhase(all = true))
    }
    val output = Paths.get(args(1)).toAbsolutePath.normalize
    val report = Paths.get(args(3)).toAbsolutePath.normalize
    Files.createDirectories(output)
    Option(report.getParent).foreach(path => Files.createDirectories(path))
    val config = SpinalConfig(
      targetDirectory = output.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )
    config.netlistFileName = expectedFile
    NamedWireExpressionWitnessPhasePlan.install(config, phase)
    val width = HdlInt.param("WIDTH", default = BigInt(8), min = BigInt(1), max = BigInt(64))
    val depth = HdlInt.param("DEPTH", default = BigInt(5), min = BigInt(1), max = BigInt(8))
    // The standalone reference/candidate pair carries one explicit WA-09
    // witness. The all-six artifact intentionally uses the ordinary FIFO so it
    // is byte-identical to the public production runner's source topology.
    val namedExpressionWitness = mode != "all"
    val generated = MorphVerilog(morphhdl.MorphWireAssignmentPasses(config, enabled = false)) {
      new ParameterizedStreamFifo(width, depth, namedExpressionWitness)
    }
    val text = new String(
      Files.readAllBytes(Paths.get(generated.generatedSourcesPaths.head)),
      StandardCharsets.UTF_8
    )
    if (!text.contains("parameter integer WIDTH") || !text.contains("parameter integer DEPTH"))
      throw new IllegalStateException("WA-09 witness lost symbolic WIDTH or DEPTH")
    val json = phase match {
      case Some(value) =>
        // Only the dedicated named-expression slot enables that source
        // witness. The all-six slot keeps the exact ordinary production FIFO;
        // its symbolic sources may remain ineligible under the width proof.
        if (mode == "named-expression" && value.namedExpressionEliminatedCount < 1)
          throw new IllegalStateException(
            "WA-09 witness captured no removable named/generated expression"
          )
        value.toJson
      case None =>
        "{\n  \"schema_version\": 1,\n  \"mode\": \"common-pre-pass-reference\"\n}\n"
    }
    Files.write(report, json.getBytes(StandardCharsets.UTF_8))
    println(Paths.get(generated.generatedSourcesPaths.head).toAbsolutePath.normalize)
  }
}

/**
  * Ordinary non-library witness where WA-09 alone removes one real native XOR
  * expression. This is intentionally independent of the FIFO prerequisite
  * chain so the successor pass itself has a small production-backend witness.
  */
object NamedWireExpressionGenericNativeWitness {
  def main(args: Array[String]): Unit = {
    if (args.length != 3)
      throw new IllegalArgumentException(
        "usage: MODE(reference|named-expression) OUTPUT_DIRECTORY REPORT_FILE"
      )
    val phase = args(0) match {
      case "reference"        => None
      case "named-expression" => Some(new NamedWireExpressionNativePhase)
      case other => throw new IllegalArgumentException(s"unsupported WA-09 mode '$other'")
    }
    val output = Paths.get(args(1)).toAbsolutePath.normalize
    val reportPath = Paths.get(args(2)).toAbsolutePath.normalize
    Files.createDirectories(output)
    Option(reportPath.getParent).foreach(path => Files.createDirectories(path))
    val config = SpinalConfig(targetDirectory = output.toString)
    config.netlistFileName = args(0) match {
      case "reference"        => "native-reference.v"
      case "named-expression" => "native-named-expression.v"
    }
    NamedWireExpressionWitnessPhasePlan.install(config, phase)
    SpinalVerilog(config) {
      new Component {
        setDefinitionName(args(0) match {
          case "reference"        => "NamedWireExpressionNativeReference"
          case "named-expression" => "NamedWireExpressionNativeCandidate"
        })
        val a = in Bits (8 bits)
        val b = in Bits (8 bits)
        val result = out Bits (8 bits)
        val c = in Bool()
        val d = in Bool()
        val flagResult = out Bool()
        a.setName("a")
        b.setName("b")
        result.setName("result")
        c.setName("c")
        d.setName("d")
        flagResult.setName("flagResult")
        val expressionSignal = Bits(8 bits)
        expressionSignal.setName("expressionSignal")
        expressionSignal := a ^ b
        result := expressionSignal
        val flagExpression = Bool()
        flagExpression.setName("flagExpression")
        flagExpression := c ^ d
        flagResult := flagExpression
      }
    }

    val json = phase match {
      case None =>
        "{\n  \"schema_version\": 1,\n  \"mode\": \"common-pre-pass-reference\"\n}\n"
      case Some(value) =>
        val report = value.report
        if (report.eliminatedCount != 2 || report.rewrittenReferences != 2)
          throw new IllegalStateException(
            s"WA-09 generic witness expected two eliminations/writebacks, got ${report.eliminatedCount}/${report.rewrittenReferences}; " +
              s"visited=${report.visitedCandidates} rejected=${report.rejectedByReason} operators=${report.expressionOperators}"
          )
        val origins = report.eliminatedOrigins.map(quoteJson).mkString(", ")
        val names = report.eliminatedNames.map(quoteJson).mkString(", ")
        val operators = report.expressionOperators.map(quoteJson).mkString(", ")
        Vector(
          "{",
          "  \"schema_version\": 1,",
          s"""  "pass_id": ${quoteJson(PassId.NamedWireExpressionElimination.value)},""",
          s"""  "executed_passes": [${quoteJson(PassId.NamedWireExpressionElimination.value)}],""",
          "  \"common_flag_enabled\": false,",
          "  \"executed_before_name_allocation\": true,",
          "  \"actual_rhs_capture_writeback\": true,",
          "  \"receiver_shape\": \"whole-rhs-only\",",
          s"""  "visited_candidate_count": ${report.visitedCandidates},""",
          s"""  "eliminated_count": ${report.eliminatedCount},""",
          s"""  "rewritten_reference_count": ${report.rewrittenReferences},""",
          s"""  "eliminated_origins": [$origins],""",
          s"""  "eliminated_names": [$names],""",
          s"""  "expression_operators": [$operators]""",
          "}",
          ""
        ).mkString("\n")
    }
    Files.write(reportPath, json.getBytes(StandardCharsets.UTF_8))
  }

  private def quoteJson(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
  * Native WA-09 name-preference witness. It exercises the actual preceding
  * named-alias writeback so a fully inlined production artifact cannot hide
  * which intermediate identity won first.
  */
private[examples] final class NamedWirePreferenceNativeTopology(
    definitionName: String
) extends Component {
  setDefinitionName(definitionName)

  val a = in Bool()
  val b = in Bool()
  val reverseResult = out Bool()
  val forwardResult = out Bool()
  val lexicalResult = out Bool()
  val unnamedResult = out Bool()
  val protectedResult = out Bool()
  a.setName("a")
  b.setName("b")
  reverseResult.setName("reverseResult")
  forwardResult.setName("forwardResult")
  lexicalResult.setName("lexicalResult")
  unnamedResult.setName("unnamedResult")
  protectedResult.setName("protectedResult")

  @dontName val reverseExpression = Bool()
    .setName("reverseExpression", Nameable.REMOVABLE)
  reverseExpression := a ^ b
  val substantiallyLongerMeaningfulSource = Bool()
    .setName("substantiallyLongerMeaningfulSource", Nameable.USER_SET)
  substantiallyLongerMeaningfulSource := reverseExpression
  val q = Bool().setName("q", Nameable.USER_SET)
  q := substantiallyLongerMeaningfulSource
  reverseResult := q

  val abc = Bool().setName("abc", Nameable.USER_SET)
  abc := a & b
  val substantiallyLongerMeaningfulAlias = Bool()
    .setName("substantiallyLongerMeaningfulAlias", Nameable.USER_SET)
  substantiallyLongerMeaningfulAlias := abc
  forwardResult := substantiallyLongerMeaningfulAlias

  @dontName val equalExpression = Bool()
    .setName("equalExpression", Nameable.REMOVABLE)
  equalExpression := a | b
  val bbb = Bool().setName("bbb", Nameable.USER_SET)
  bbb := equalExpression
  val aaa = Bool().setName("aaa", Nameable.USER_SET)
  aaa := bbb
  lexicalResult := aaa

  @dontName val actualUnnamedExpression = Bool()
  actualUnnamedExpression := a =/= b
  val unnamedMeaningfulSurvivor = Bool()
    .setName("unnamedMeaningfulSurvivor", Nameable.USER_SET)
  unnamedMeaningfulSurvivor := actualUnnamedExpression
  unnamedResult := unnamedMeaningfulSurvivor

  val extraordinarilyLongProtectedName = Bool()
    .setName("extraordinarilyLongProtectedName", Nameable.USER_SET)
  extraordinarilyLongProtectedName.addAttribute("keep")
  extraordinarilyLongProtectedName := a === b
  val p = Bool().setName("p", Nameable.USER_SET)
  p := extraordinarilyLongProtectedName
  protectedResult := p
}

/**
  * Usage: MODE(reference|candidate) OUTPUT_DIRECTORY REPORT_FILE.
  *
  * Candidate output is `native-preference-candidate.v`; the report records the
  * names of the identities actually removed by the native alias phase and the
  * proven generated/unnamed expression-source deferrals.
  */
object NamedWirePreferenceNativeWitness {
  private val expectedEliminatedNames = Vector(
    "substantiallyLongerMeaningfulSource",
    "substantiallyLongerMeaningfulAlias",
    "bbb",
    "p"
  )
  private val expectedDeferredOrigins = Vector("generated", "generated", "unnamed")

  def main(args: Array[String]): Unit = {
    if (args.length != 3)
      throw new IllegalArgumentException(
        "usage: MODE(reference|candidate) OUTPUT_DIRECTORY REPORT_FILE"
      )
    val mode = args(0)
    val phase = mode match {
      case "reference" => None
      case "candidate" => Some(new NamedWireAliasNativePhase(deferPreferredExpressionSource = true))
      case other => throw new IllegalArgumentException(
        s"unsupported native preference mode '$other'"
      )
    }
    val output = Paths.get(args(1)).toAbsolutePath.normalize
    val reportPath = Paths.get(args(2)).toAbsolutePath.normalize
    Files.createDirectories(output)
    Option(reportPath.getParent).foreach(path => Files.createDirectories(path))
    val config = SpinalConfig(targetDirectory = output.toString)
    config.netlistFileName = mode match {
      case "reference" => "native-preference-reference.v"
      case "candidate" => "native-preference-candidate.v"
    }
    NamedWireAliasWitnessPhasePlan.install(config, phase)
    SpinalVerilog(config) {
      new NamedWirePreferenceNativeTopology(mode match {
        case "reference" => "NamedWirePreferenceNativeReference"
        case "candidate" => "NamedWirePreferenceNativeCandidate"
      })
    }

    val json = phase match {
      case None =>
        "{\n  \"schema_version\": 1,\n  \"mode\": \"common-pre-pass-reference\"\n}\n"
      case Some(value) =>
        val native = value.report
        val deferred = value.deferredPreferenceSourceOrigins
        if (
          native.eliminatedNames != expectedEliminatedNames ||
          native.eliminatedCount != expectedEliminatedNames.size ||
          native.rewrittenReferences != expectedEliminatedNames.size ||
          deferred != expectedDeferredOrigins
        ) throw new IllegalStateException(
          s"native preference witness mismatch: eliminated=${native.eliminatedNames}, " +
            s"rewritten=${native.rewrittenReferences}, deferred=$deferred, " +
            s"rejected=${native.rejectedByReason}"
        )
        Vector(
          "{",
          "  \"schema_version\": 1,",
          s"""  "pass_id": ${quoteJson(PassId.NamedWireAliasElimination.value)},""",
          s"""  "executed_passes": [${quoteJson(PassId.NamedWireAliasElimination.value)}],""",
          "  \"executed_before_name_allocation\": true,",
          "  \"actual_native_identity_writeback\": true,",
          "  \"receiver_shape\": \"whole-rhs-only\",",
          s"""  "visited_candidate_count": ${native.visitedCandidates},""",
          s"""  "eliminated_count": ${native.eliminatedCount},""",
          s"""  "rewritten_reference_count": ${native.rewrittenReferences},""",
          s"""  "eliminated_names": [${native.eliminatedNames.map(quoteJson).mkString(", ")}],""",
          s"""  "deferred_source_origins": [${deferred.map(quoteJson).mkString(", ")}]""",
          "}",
          ""
        ).mkString("\n")
    }
    Files.write(reportPath, json.getBytes(StandardCharsets.UTF_8))
  }

  private def quoteJson(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
