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

  final class Stage private[TypedBalancedReductionStageReplay] (
      val geometry: TypedBalancedReductionStage,
      val operators: Vector[TypedBalancedReductionOperatorCertificate],
      val bridges: Vector[TypedBalancedReductionBridgeReplay.Proof]
  ) {
    val registerCountPerRow: Int = bridges.head.registerCount
  }

  final class Certificate[T <: BaseType] private[TypedBalancedReductionStageReplay] (
      val captured: UnvalidatedBalancedReduction[T],
      val stages: Vector[Stage],
      val resultEvidence: Evidence,
      private val inputs: Vector[Evidence],
      private val observations: Vector[() => Unit],
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
      observations.foreach(_.apply())
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
          .replay(left, right).asInstanceOf[T]
      }, (value: T, level: Int) => {
        if (level < 0 || level >= stages.size)
          fail("LEVEL", "native helper requested an uncertified bridge level")
        bridgeCalls(level) += 1
        stages(level).bridges.head.replay(value).asInstanceOf[T]
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
      bridge: (T, Int) => T, native: ElabBalancedReduction.Native[T],
      schema: Option[TypedBalancedReductionCertifiedCallbackPolicy.CaptureSchema] = None): Certificate[T] = {
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
      if (!ElabInt.equivalentExactFunction(evidence.width, shape.elementLeaves.head.width) ||
          (evidence.kind ne shape.elementLeaves.head.typeObject))
        fail("INPUT-SHAPE", "original Vec leaves lost their exact independent element-width authority")
    }
    val values = new IdentityHashMap[BaseType, Evidence]()
    inputEvidence.foreach(evidence => values.put(evidence.value, evidence))
    val observations = ArrayBuffer.empty[() => Unit]
    schema.foreach(value => observations += (() => value.validateBindings()))
    val operators = mutable.Map.empty[Int, TypedBalancedReductionOperatorCertificate]
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
            !callback.assignments.exists(_ eq value) &&
            !(schema.nonEmpty && callback.operands.size == 2 && value.isInstanceOf[WhenStatement])))
          fail("STATEMENT-EFFECT", "callback created a statement outside its recorded scalar data graph")
        previousStatements = currentStatements
        observations.foreach(_.apply())
        val evidence = callback.operands.size match {
          case 2 =>
            val proof: TypedBalancedReductionOperatorCertificate = schema match {
              case Some(captures) => TypedBalancedReductionScalarGraphReplay.certify(
                callback, callback.operands.map(evidenceOf), captures.hardwareInputs)
              case None => TypedBalancedReductionOperatorReplay.certify(callback, callback.operands.map(evidenceOf))
            }
            operators(callback.ordinal) = proof
            if (schema.nonEmpty) observations += (() => proof.validateFreshness())
            TypedBalancedReductionValueEvidence.fromOperator(proof)
          case 1 =>
            val proof = TypedBalancedReductionBridgeReplay.certify(callback, evidenceOf(callback.operands.head))
            bridges(callback.ordinal) = proof
            TypedBalancedReductionValueEvidence.fromBridge(proof)
          case _ => fail("ARITY", "native callback has an unexpected arity")
        }
        values.put(evidence.value, evidence)
        if (schema.isEmpty || callback.operands.size == 1) {
          val observed = TypedBalancedReductionClosedGraph.observe(callback)
          observations += (() => observed.requireUnchanged())
        }
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
    if (allOperators.nonEmpty && allOperators.exists(_.operationKey != allOperators.head.operationKey))
      fail("OPERATOR-NONUNIFORM", "the whole native tree must retain one certified ordered callback graph and capture binding")
    val terminal = evidenceOf(captured.result)
    if (!ElabInt.equivalentExactFunction(terminal.width, shape.elementLeaves.head.width))
      fail("RESULT-WIDTH", "terminal value lost the certified element-width transfer")
    val certificate = new Certificate(captured, stages, terminal, inputEvidence,
      observations.toVector, native, counts)
    certificate.requireFreshness()
    certificate
  }
}
