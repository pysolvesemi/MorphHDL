package spinal.core.internals

import java.util.IdentityHashMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import spinal.core._
import TypedBalancedReductionValueEvidence.Evidence

/** Certify the entire captured native schedule, not just its first operator.
  * This remains a pre-normalization native-graph certificate. It does not
  * authorize parameterized publication or assert arbitrary Scala purity.
  */
private[spinal] object TypedBalancedReductionStageReplay {
  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException(s"MORPH-REDUCE-BALANCED-STAGE-$code: $detail")

  final case class WidthStage(inputFull: ElaborationIntegerExpression,
      inputTail: ElaborationIntegerExpression, outputFull: ElaborationIntegerExpression,
      outputTail: ElaborationIntegerExpression, fullPairPossible: Boolean,
      fullInput: ElaborationIntegerExpression, fullPairActive: ElaborationBooleanExpression,
      partialLeft: ElaborationIntegerExpression, partialRight: ElaborationIntegerExpression,
      partialPairActive: ElaborationBooleanExpression, partialPairPossible: Boolean,
      tailPossible: Boolean, inputPacked: ElaborationIntegerExpression,
      outputPacked: ElaborationIntegerExpression)
  final case class WidthSchedule(stages: Vector[WidthStage], terminal: ElaborationIntegerExpression,
      initialPacked: ElaborationIntegerExpression)

  /** The native topology's full groups and last, possibly smaller group.
    * Transfer comes exclusively from the closed native operator graph. This
    * recurrence never recognizes an adder or multiplier callback. */
  def widths(plan: TypedBalancedReductionPlan, leaf: ElaborationIntegerExpression,
      operator: Option[TypedBalancedReductionOperatorReplay.Proof]): WidthSchedule = {
    var full = leaf
    var tail = leaf
    val initialPacked = ElaborationWidthAuthority.multiply(leaf, plan.count.expression)
    var packed = initialPacked
    val stages = plan.stages.map { geometry =>
      val proof = operator.getOrElse(fail("WIDTH-PROFILE", "an active stage requires a certified native operator"))
      val fullActive = plan.count >= ElabInt.fromBigInt(BigInt(1) << (geometry.level + 1))
      val fullMinimum = ElaborationWidthAuthority.minimumWhen(full, fullActive.expression)
      val fullMaximum = ElaborationWidthAuthority.maximumWhen(full, fullActive.expression)
      val fullPairPossible = fullMinimum.nonEmpty
      // Every admitted native width transfer is monotone in its scalar input
      // widths. Keep existing geometry when the active domain already attains
      // its maximum; guard only larger, unreachable full-group widths.
      val fullInput = if (fullMaximum.exists(_ < full.maximum)) {
        ElaborationWidthAuthority.choose(fullActive.expression, full,
          ElabInt.fromBigInt(fullMinimum.get).expression)
      } else full
      val paired = geometry.active && !geometry.hasOddTail
      val leftMinimum = ElaborationWidthAuthority.minimumWhen(full, paired.expression)
      val rightMinimum = ElaborationWidthAuthority.minimumWhen(tail, paired.expression)
      val partialPairPossible = leftMinimum.nonEmpty
      if (leftMinimum.nonEmpty != rightMinimum.nonEmpty)
        fail("WIDTH-PROFILE", "paired native input widths disagree on their exact active domain")
      // These positive constants describe only inactive template metadata.
      // There is no native operand, resize or inserted neutral value on a
      // bypass path. Actual pair widths are untouched, so overflow on an
      // active native node remains an error.
      val partialLeft = leftMinimum.map(minimum => ElaborationWidthAuthority.choose(
        paired.expression, full, ElabInt.fromBigInt(minimum).expression)).getOrElse(full)
      val partialRight = rightMinimum.map(minimum => ElaborationWidthAuthority.choose(
        paired.expression, tail, ElabInt.fromBigInt(minimum).expression)).getOrElse(tail)
      val nextTail = if (partialPairPossible) {
        val pairedTail = proof.resultWidthFor(partialLeft, partialRight)
        ElaborationWidthAuthority.choose(paired.expression, pairedTail, tail)
      } else tail
      val nextFull = if (fullPairPossible) proof.resultWidthFor(fullInput, fullInput) else nextTail
      // Native scalar legality alone does not authorize packing a collection
      // into one finite-width transport. Prove the exact ragged packed range
      // before emitting it; positive terms cannot hide intermediate overflow.
      val outputPacked = ElaborationWidthAuthority.add(
        ElaborationWidthAuthority.multiply(nextFull, (geometry.outputCount - 1).expression), nextTail)
      val stage = WidthStage(full, tail, nextFull, nextTail, fullPairPossible,
        fullInput, fullActive.expression, partialLeft, partialRight, paired.expression, partialPairPossible,
        !geometry.hasOddTail.isAlwaysFalse, packed, outputPacked)
      full = nextFull
      tail = nextTail
      packed = outputPacked
      stage
    }
    WidthSchedule(stages, tail, initialPacked)
  }

  final class Stage private[TypedBalancedReductionStageReplay] (
      val geometry: TypedBalancedReductionStage,
      val operators: Vector[TypedBalancedReductionOperatorReplay.Proof],
      val bridges: Vector[TypedBalancedReductionBridgeReplay.Proof]
  ) {
    val registerCountPerRow: Int = bridges.head.registerCount
  }

  final class Certificate[T <: BaseType] private[TypedBalancedReductionStageReplay] (
      val captured: UnvalidatedBalancedReduction[T],
      val stages: Vector[Stage],
      val resultEvidence: Evidence,
      private val inputs: Vector[Evidence],
      private val observations: Vector[TypedBalancedReductionClosedGraph.Observation],
      private val native: ElabBalancedReduction.Native[T],
      private val admittedCounts: Set[Int]
  ) {
    val operatorClass: Option[Class[_]] = stages.flatMap(_.operators).headOption.map(_.operatorClass)

    def requireFreshness(): Unit = {
      if (ParameterizedVec.shapeOf(captured.vector).forall(_ ne captured.shape) ||
          captured.vector.vec.size != inputs.size ||
          captured.vector.vec.zip(inputs).exists { case (value, proof) => value ne proof.value })
        fail("SHAPE-CHANGED", "the exact captured receiver or its shape changed after certification")
      inputs.foreach(_.requireFreshness())
      observations.foreach(_.requireUnchanged())
      resultEvidence.requireFreshness()
    }

    /** Pipeline depth in enabled sampling edges of the one certified clock domain. */
    def latencyFor(count: Int): Int = {
      requireFreshness()
      if (!admittedCounts.contains(count)) fail("COUNT", "count is outside the exact captured domain")
      val depth = (BigInt(count) - 1).bitLength
      stages.take(depth).map(_.registerCountPerRow).sum
    }

    /** Replay the unchanged native algorithm at a concrete admitted count.
      * This exercises whole-stage/bridge certificates; it is not an emitter
      * and cannot publish a concrete carrier as a symbolic-count candidate.
      */
    def replay(values: Vector[T]): T = {
      requireFreshness()
      if (Component.current ne inputs.head.owner)
        fail("OWNER", "whole-stage replay must retain the owning component")
      if (values == null || !admittedCounts.contains(values.size))
        fail("COUNT", "replay needs a nonempty exact admitted count")
      values.foreach(inputs.head.requireReplacement)
      val operator = stages.flatMap(_.operators).headOption
      var calls = 0
      val bridgeCalls = mutable.Map.empty[Int, Int].withDefaultValue(0)
      val result = native(values, (left: T, right: T) => {
        calls += 1
        operator.getOrElse(fail("SINGLETON", "singleton-only evidence has no operator body"))
          .replayWithWidths(left, right,
            ParameterizedWidth.expressionOf(left).getOrElse(ElabInt.literal(left.getBitsWidth).expression),
            ParameterizedWidth.expressionOf(right).getOrElse(ElabInt.literal(right.getBitsWidth).expression)).asInstanceOf[T]
      }, (value: T, level: Int) => {
        if (level < 0 || level >= stages.size)
          fail("LEVEL", "native helper requested an uncertified bridge level")
        bridgeCalls(level) += 1
        stages(level).bridges.head.replayWithWidth(value,
          ParameterizedWidth.expressionOf(value).getOrElse(ElabInt.literal(value.getBitsWidth).expression)).asInstanceOf[T]
      })
      val depth = (BigInt(values.size) - 1).bitLength
      if (calls != values.size - 1 || bridgeCalls.keySet != (0 until depth).toSet ||
          bridgeCalls.exists { case (level, count) =>
            count != ((BigInt(values.size) - 1) / (BigInt(1) << (level + 1)) + 1).toInt
          })
        fail("NATIVE-SCHEDULE", "native replay did not follow its certified pair/bridge counts")
      result
    }

    def requirePublicationCertificate(): Nothing =
      fail("PUBLICATION-UNVALIDATED", "native stage evidence is not post-phase parameterized publication permission")
  }

  def capture[T <: BaseType](vector: Vec[T], op: (T, T) => T,
      bridge: (T, Int) => T, native: ElabBalancedReduction.Native[T]): Certificate[T] = {
    if (vector == null || op == null || bridge == null || native == null)
      fail("NULL", "Vec, callbacks and the authoritative native helper are required")
    val shape = ParameterizedVec.shapeOf(vector)
      .getOrElse(fail("SHAPE", "receiver must retain its exact typed Vec shape"))
    if (shape.elementLeaves.size != 1 || shape.elementLeaves.head.path != "")
      fail("SHAPE", "stage replay currently requires one supported native scalar per element")
    val plan = TypedBalancedReductionPlan.forVec(vector).get
    val counts = plan.count.expression.exactDomain match {
      case Some(exact) => ElabInt.activeDomainEvaluations(exact, "balanced stage counts", None)
        .map(_._2.toInt).toSet
      case None => Set(plan.count.expression.default.toInt)
    }
    val inputEvidence = vector.vec.toVector.map(TypedBalancedReductionValueEvidence.input)
    inputEvidence.foreach { evidence =>
      if (!ElaborationWidthAuthority.equivalent(evidence.width, shape.elementLeaves.head.width) ||
          (evidence.kind ne shape.elementLeaves.head.typeObject))
        fail("INPUT-SHAPE", "original Vec leaves lost their exact independent element-width authority")
    }
    val values = new IdentityHashMap[BaseType, Evidence]()
    inputEvidence.foreach(evidence => values.put(evidence.value, evidence))
    val observations = ArrayBuffer.empty[TypedBalancedReductionClosedGraph.Observation]
    val operators = mutable.Map.empty[Int, TypedBalancedReductionOperatorReplay.Proof]
    val bridges = mutable.Map.empty[Int, TypedBalancedReductionBridgeReplay.Proof]

    def statements(): Vector[Statement] = {
      val result = ArrayBuffer.empty[Statement]
      vector.component.dslBody.walkStatements(result += _)
      result.toVector
    }
    var previousStatements = statements()

    def evidenceOf(data: Data): Evidence = data match {
      case scalar: BaseType =>
        Option(values.get(scalar)).getOrElse(fail("PROVENANCE", "callback operand is not an exact prior certified result or input"))
      case _ => fail("SHAPE", "callback data must remain scalar")
    }

    val captured = TypedBalancedReductionCapture(vector, op, bridge, native,
      (callback: UnvalidatedBalancedCallback) => {
        // Declaration/assignment-only capture must not silently discard an
        // assertion, memory or other statement-producing callback effect.
        val currentStatements = statements()
        if (previousStatements.exists(old => !currentStatements.exists(_ eq old)))
          fail("STATEMENT-EFFECT", "callback removed a pre-existing native statement")
        val added = currentStatements.filterNot(value => previousStatements.exists(_ eq value))
        if (added.exists(value => !callback.declarations.exists(_ eq value) &&
            !callback.assignments.exists(_ eq value)))
          fail("STATEMENT-EFFECT", "callback created a statement outside its recorded scalar data graph")
        previousStatements = currentStatements
        observations.foreach(_.requireUnchanged())
        val evidence = callback.operands.size match {
          case 2 =>
            val proof = TypedBalancedReductionOperatorReplay.certify(callback, callback.operands.map(evidenceOf))
            operators(callback.ordinal) = proof
            TypedBalancedReductionValueEvidence.fromOperator(proof)
          case 1 =>
            val proof = TypedBalancedReductionBridgeReplay.certify(callback, evidenceOf(callback.operands.head))
            bridges(callback.ordinal) = proof
            TypedBalancedReductionValueEvidence.fromBridge(proof)
          case _ => fail("ARITY", "native callback has an unexpected arity")
        }
        values.put(evidence.value, evidence)
        observations += TypedBalancedReductionClosedGraph.observe(callback)
      })
    val stages = captured.plan.stages.map { geometry =>
      val rows = captured.rows.filter(_.level == geometry.level)
      val rowOperators = rows.flatMap(_.operator.map(record => operators(record.ordinal)))
      val rowBridges = rows.map(row => bridges(row.bridge.ordinal))
      if (rowBridges.isEmpty || rowBridges.exists(proof => !rowBridges.head.sameBehavior(proof)))
        fail("BRIDGE-NONUNIFORM", "every pair and odd tail at one level must have identical bridge behavior")
      new Stage(geometry, rowOperators, rowBridges)
    }
    val clocks = captured.rows.flatMap(_.bridge.declarations.filter(_.isReg).map(_.clockDomain))
    if (clocks.nonEmpty && clocks.exists(_ ne clocks.head))
      fail("CLOCK-NONUNIFORM", "fixed enabled-edge latency requires one exact clock domain across all stages and register chains")
    val allOperators = stages.flatMap(_.operators)
    if (allOperators.nonEmpty && allOperators.exists(proof =>
        proof.operationKey != allOperators.head.operationKey || proof.transferKey != allOperators.head.transferKey))
      fail("OPERATOR-NONUNIFORM", "the whole native tree must use one certified scalar native graph and width transfer")
    val terminal = evidenceOf(captured.result)
    val certificate = new Certificate(captured, stages, terminal, inputEvidence,
      observations.toVector, native, counts)
    certificate.requireFreshness()
    certificate
  }
}
