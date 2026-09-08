package spinal.core.internals

import java.util.IdentityHashMap
import java.util.regex.{Matcher, Pattern}
import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Generic balanced topology around certified, natively emitted Data bodies.
  * No operator syntax, register process or reset/enable rule is emitted here.
  */
object TypedBalancedReductionBackend {
  private object StorageKey
  private final class Storage {
    val records = ArrayBuffer.empty[Record]
    val recursiveTransport = new IdentityHashMap[Vec[_], java.lang.Boolean]()
  }
  private final case class Body(block: ParameterizedStructuralBlock,
      left: Data, right: Option[Data], result: Data,
      observations: Vector[() => Unit])
  private sealed trait Stage {
    def geometry: TypedBalancedReductionStage
    def bodies: Vector[Body]
  }
  private final case class ScalarStage(geometry: TypedBalancedReductionStage,
      inputFullWidth: ElaborationIntegerExpression, inputTailWidth: ElaborationIntegerExpression,
      outputFullWidth: ElaborationIntegerExpression, outputTailWidth: ElaborationIntegerExpression,
      inputPackedWidth: ElaborationIntegerExpression, outputPackedWidth: ElaborationIntegerExpression,
      fullPairPossible: Boolean, pair: Body, partialPair: Option[Body], tail: Option[Body]) extends Stage {
    def bodies: Vector[Body] = Vector(pair) ++ partialPair.toVector ++ tail.toVector
  }
  private final case class CompositeStage(geometry: TypedBalancedReductionStage,
      pair: Body, tail: Body) extends Stage {
    def bodies: Vector[Body] = Vector(pair, tail)
  }
  private final case class Record(vector: Vec[Data], shape: ParameterizedVecShape,
      input: Bits, output: Data, plan: TypedBalancedReductionPlan,
      stages: Vector[Stage], ordinal: Int,
      outputObservation: TypedBalancedReductionClosedGraph.Observation,
      lexicalOwner: ParameterizedStructuralLexicalOwner,
      captureSchema: TypedBalancedReductionCaptureSchema) {
    var handedOff = false
    var handoffOwner: Option[ParameterizedStructuralBlock] = None
    var published = false
  }

  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException("MORPH-REDUCE-BALANCED-PUBLICATION-" + code + ": " + detail)
  private def records(component: Component): Vector[Record] =
    component.userCache.get(StorageKey).map(_.asInstanceOf[Storage].records.toVector).getOrElse(Vector.empty)

  /** Read-only ownership evidence. Only private certified-template creation
    * can enter this registry; ordinary application Vecs cannot opt out of
    * native Vec publication and lineage checks.
    */
  private[internals] def ownsRecursiveTransport(vector: Vec[_]): Boolean =
    vector != null && vector.component != null &&
      vector.component.userCache.get(StorageKey)
        .exists(_.asInstanceOf[Storage].recursiveTransport.containsKey(vector))

  /** A procedural public-Vec boundary is admitted only for an exact result
    * whose scoped topology has already passed the lexical handoff. Template
    * operands and ordinary application Vecs cannot acquire this permission.
    */
  private[internals] def ownsPublishedRecursiveAssignment(vector: Vec[_],
      assignments: Vector[DataAssignmentStatement]): Boolean = {
    if (!ownsRecursiveTransport(vector) || assignments.isEmpty) return false
    records(vector.component).exists { record =>
      record.published && !record.lexicalOwner.isModuleScope &&
        ParameterizedVecElementLayout.nestedVectors(record.output).exists(_ eq vector) &&
        record.handoffOwner.exists { owner =>
          val allowed = (Vector(owner) ++ owner.regions.flatMap(ParameterizedStructure.allBlocks))
            .flatMap(_.assignments)
          assignments.forall(assignment => allowed.exists(_ eq assignment))
        }
    }
  }

  /** Scoped topology has already replaced these exact zero witness drivers.
    * The ordinary expression publisher must not reinterpret the private
    * anchors as user-authored zero assignments after that checked handoff.
    */
  private[internals] def publishedScopedAnchors(component: Component): Vector[BaseType] =
    records(component).filter(record => record.published && !record.lexicalOwner.isModuleScope)
      .flatMap(record => record.output.flatten.toVector ++ record.stages.flatMap(_.bodies)
        .flatMap(body => body.left.flatten.toVector ++ body.right.toVector.flatMap(_.flatten)))

  private def claimRecursiveTransport(value: Data): Unit = {
    val owner = Component.current
    val storage = owner.userCache(StorageKey).asInstanceOf[Storage]
    val vectors = ParameterizedVecElementLayout.nestedVectors(value)
    if (vectors.exists(vector => (vector.component ne owner) ||
        vector.asInstanceOf[Data].flatten.exists(_.isIo)))
      fail("RECURSIVE-TRANSPORT-OWNER", "certified transport can own only exact internal replay vectors")
    vectors.foreach(vector => storage.recursiveTransport.put(vector, java.lang.Boolean.TRUE))
  }

  private def validateNativeAnchors(body: Body): Unit = {
    (Vector(body.left) ++ body.right.toVector).flatMap(_.flatten).foreach { value =>
      if (!value.isNamed || !value.dontSimplify || !value.isComb ||
          !value.hasTag(noBackendCombMerge))
        fail("ANCHOR-POLICY", "native input wires must remain named and protected from propagation/merging")
      val assignments = ArrayBuffer.empty[Statement]
      value.foreachStatements(assignments += _)
      if (assignments.size != 1 || !assignments.head.isInstanceOf[DataAssignmentStatement] ||
          (assignments.head.asInstanceOf[DataAssignmentStatement].target ne value))
        fail("ANCHOR-DRIVER", "native input anchor must retain one exact full driver")
    }
  }

  private def validatePackedWidths(record: Record, pc: PhaseContext): Unit = {
    record.stages.collect { case stage: ScalarStage => stage }
      .flatMap(stage => Vector(stage.inputPackedWidth, stage.outputPackedWidth)).foreach { width =>
      NativePublicationWidth.validate(width, record.vector.component, record.input,
        "balanced packed transport")
      if (width.minimum < 1 || width.maximum > BigInt(pc.config.bitVectorWidthMax))
        fail("TRANSPORT-WIDTH", "packed native stage reaches [" + width.minimum + ", " + width.maximum +
          "], outside [1, " + pc.config.bitVectorWidthMax + "] allowed by SpinalConfig.bitVectorWidthMax")
    }
  }

  private def validateResultOwnership(record: Record, block: ParameterizedStructuralBlock): Unit = {
    val allowed = new IdentityHashMap[Statement, java.lang.Boolean]()
    def retain(statement: Statement): Unit = {
      if (!allowed.containsKey(statement)) {
        allowed.put(statement, java.lang.Boolean.TRUE)
        statement match {
          case tree: TreeStatement => tree.foreachStatements(retain)
          case _ =>
        }
      }
    }
    (Vector(block) ++ block.regions.flatMap(ParameterizedStructure.allBlocks))
      .flatMap(_.statements).foreach(retain)
    val leaves = record.output.flatten.toVector
    record.vector.component.dslBody.walkStatements { statement =>
      if (!allowed.containsKey(statement)) {
        val visited = new IdentityHashMap[Expression, java.lang.Boolean]()
        def inspect(expression: Expression): Unit = {
          if (expression != null && !visited.containsKey(expression)) {
            visited.put(expression, java.lang.Boolean.TRUE)
            if (leaves.exists(_ eq expression))
              fail("RESULT-ESCAPE", "a reduction result is consumed outside its exact lexical owner")
            expression match {
              case _: BaseType => // Only direct references, not transitive native drivers.
              case _ => expression.foreachExpression(inspect)
            }
          }
        }
        statement.foreachExpression(inspect)
      }
    }
  }

  /** Scoped inside the native elaboration closure, including native retries. */
  def elaborate[A](body: => A): A = ElabBalancedReduction.withBackend(Backend)(body)

  /** Check the actual replay templates after elaboration/user transformations,
    * before native naming, pruning and normalization can change their graph.
    */
  def install(phases: ArrayBuffer[Phase]): Unit = {
    val boundary = phases.indexWhere(_.isInstanceOf[PhaseNameNodesByReflection])
    require(boundary >= 0, "native pre-normalization graph handoff is missing")
    phases.insert(boundary, new PhaseMisc {
      override def impl(pc: PhaseContext): Unit = pc.walkComponents { owner =>
        records(owner).foreach { record =>
          val lexicalBlock = ParameterizedStructure.blockOfLexicalOwner(record.lexicalOwner)
          if (record.lexicalOwner.component ne owner)
            fail("LEXICAL-OWNER", "reduction storage lost its exact owning component")
          val lexicalBlocks = ParameterizedStructure.regionsOf(owner).flatMap(ParameterizedStructure.allBlocks)
          val declarations = Vector(record.input) ++ record.output.flatten.toVector ++
            record.stages.flatMap(_.bodies.flatMap(_.block.declarations))
          declarations.foreach { declaration =>
            val owners = lexicalBlocks.filter(_.declarations.exists(_ eq declaration))
            if (owners.size != lexicalBlock.size ||
                owners.zip(lexicalBlock).exists { case (actual, expected) => actual ne expected })
              fail("LEXICAL-DECLARATION", "reduction anchors and templates must retain one exact direct lexical owner")
          }
          lexicalBlock.foreach { block =>
            validateResultOwnership(record, block)
          }
          ParameterizedStructure.withLexicalOwnerDomain(record.lexicalOwner) {
            validatePackedWidths(record, pc)
            val bodies = record.stages.flatMap(_.bodies)
            bodies.foreach(validateNativeAnchors)
            bodies.flatMap(_.observations).foreach(_.apply())
            record.outputObservation.requireUnchanged()
          }
          record.handoffOwner = lexicalBlock
          record.handedOff = true
        }
      }
    })
  }

  private object Backend extends ElabBalancedReduction.Backend {
    override def reduce[T <: Data](vector: Vec[T], op: (T, T) => T,
        bridge: (T, Int) => T, native: ElabBalancedReduction.Native[T]): T = {
      if (!ParameterizedStructure.captureEnabled)
        fail("MODE", "symbolic reduction requires parameterized native elaboration")
      val plan = TypedBalancedReductionPlan.forVec(vector).get
      if (plan.count.expression.maximum == 1) return vector(0)
      // Callback code admission precedes its first execution. Graph sampling
      // is not used to infer the absence of host state or external effects.
      TypedBalancedReductionCallbackPolicy.requireSupportedValues(vector.vec)
      val schema = if (vector.vec.head.isInstanceOf[BaseType]) {
        val scalarClasses: Set[Class[_]] = Set(classOf[Bool], classOf[Bits], classOf[UInt], classOf[SInt])
        if (vector.vec.exists(value => !scalarClasses(value.getClass)))
          fail("SHAPE", "callback operands must have exact native scalar classes without overriding methods")
        val captures = TypedBalancedReductionCertifiedCallbackPolicy.requireSupportedOperator(op)
        TypedBalancedReductionCallbackPolicy.requireSupportedBridge(bridge)
        Left(captures)
      } else {
        val captures = TypedBalancedReductionCertifiedCallbackPolicy.requireSupportedCompositeOperator(op)
        TypedBalancedReductionCallbackPolicy.requireSupportedBridge(bridge)
        Right(captures)
      }
      schema match {
        case Left(captures) => buildScalar(vector.asInstanceOf[Vec[BaseType]],
          op.asInstanceOf[(BaseType, BaseType) => BaseType],
          bridge.asInstanceOf[(BaseType, Int) => BaseType],
          native.asInstanceOf[ElabBalancedReduction.Native[BaseType]], captures).asInstanceOf[T]
        case Right(captures) => buildComposite(vector.asInstanceOf[Vec[Data]],
          op.asInstanceOf[(Data, Data) => Data],
          bridge.asInstanceOf[(Data, Int) => Data],
          native.asInstanceOf[ElabBalancedReduction.Native[Data]], captures).asInstanceOf[T]
      }
    }
  }

  private def preserve(value: BaseType, name: String): BaseType = {
    value.setName(name)
    value.setAsVital(); value.dontSimplifyIt()
    if (value.isReg) value.addTag(noBackendSyncMerge) else value.noBackendCombMerge()
    value
  }

  private def driveZero(value: Data): Unit = value match {
    case scalar: Bool => scalar := False
    case scalar: Bits => scalar := 0
    case scalar: UInt => scalar := 0
    case scalar: SInt => scalar := 0
    case _: MultiData => value.flatten.foreach(driveZero)
    case _ => fail("SHAPE", "unsupported native anchor")
  }

  // Scalar certificates own the changing full/partial/tail lane widths. The
  // composite path below retains its independent, fixed recursive leaf layout.
  private def buildScalar(vector: Vec[BaseType], op: (BaseType, BaseType) => BaseType,
      bridge: (BaseType, Int) => BaseType,
      native: ElabBalancedReduction.Native[BaseType],
      schema: TypedBalancedReductionCertifiedCallbackPolicy.CaptureSchema): BaseType = {
    val owner = Component.current
    val lexicalOwner = ParameterizedStructure.currentLexicalOwner("balanced scalar publication")
    val storage = owner.userCache.getOrElseUpdate(StorageKey, new Storage).asInstanceOf[Storage]
    val ordinal = storage.records.size + 1
    val prefix = s"morphhdl_balanced_$ordinal"
    val certificate = TypedBalancedReductionStageReplay.capture(vector, op, bridge, native, Some(schema))
    val shape = certificate.captured.shape
    val plan = certificate.captured.plan
    def fresh(name: String, width: ElaborationIntegerExpression): BaseType = {
      val bits = ElabInt.fromExpression(width).bits
      val result: BaseType = vector.vec.head match {
        case _: Bool =>
          if (width.minimum != 1 || width.maximum != 1) fail("BOOL-WIDTH", "Bool width must remain one")
          Bool()
        case _: Bits => Bits(bits)
        case _: UInt => UInt(bits)
        case _: SInt => SInt(bits)
        case _ => fail("SHAPE", "unsupported scalar template")
      }
      result.setAsDirectionLess()
      preserve(result, name)
    }
    def template(stage: TypedBalancedReductionStageReplay.Stage, suffix: String,
        leftWidth: ElaborationIntegerExpression,
        rightWidth: Option[ElaborationIntegerExpression],
        active: ElaborationBooleanExpression): Body = {
      var left: BaseType = null
      var right: Option[BaseType] = None
      var result: BaseType = null
      val observations = ArrayBuffer.empty[() => Unit]
      observations += (() => schema.validateBindings())
      def record(operands: Vector[Data])(body: => BaseType): UnvalidatedBalancedCallback = {
        def inventory(): Vector[Statement] = {
          val values = ArrayBuffer.empty[Statement]
          owner.dslBody.walkStatements(values += _)
          values.toVector
        }
        val before = inventory()
        val value = body
        val added = inventory().filterNot(statement => before.exists(_ eq statement))
        UnvalidatedBalancedCallback(0, operands, value,
          added.collect { case declaration: BaseType => declaration },
          added.collect { case assignment: AssignmentStatement => assignment }, added)
      }
      val label = prefix + "_l" + stage.geometry.level + "_" + suffix
      val anchors = ParameterizedStructure.captureBlock(owner, None) {
        left = fresh(label + "_left", leftWidth)
        driveZero(left)
        rightWidth.foreach { width =>
          val other = fresh(label + "_right", width)
          driveZero(other)
          right = Some(other)
        }
      }
      val block = ParameterizedStructure.captureBlock(owner, None) {
        val resultWidth = rightWidth.map(stage.operators.head.resultWidthFor(leftWidth, _)).getOrElse(leftWidth)
        val operated = rightWidth.map { width =>
          val callback = record(Vector(left, right.get)) {
            stage.operators.head.replayWithWidths(left, right.get, leftWidth, width)
          }
          val proof = TypedBalancedReductionScalarGraphReplay.certify(callback,
            Vector(left, right.get).map(TypedBalancedReductionValueEvidence.input), schema.hardwareInputs)
          observations += (() => proof.validateFreshness())
          callback.result.asInstanceOf[BaseType]
        }.getOrElse(left)
        val bridgeRecord = record(Vector(operated)) {
          stage.bridges.head.replayWithWidth(operated, resultWidth, active)
        }
        val bridgeObservation = TypedBalancedReductionClosedGraph.observe(bridgeRecord)
        observations += (() => bridgeObservation.requireUnchanged())
        val resultRecord = record(Vector(bridgeRecord.result)) {
          result = fresh(label + "_result", resultWidth)
          result.assignFrom(bridgeRecord.result)
          result
        }
        val resultObservation = TypedBalancedReductionClosedGraph.observe(resultRecord)
        observations += (() => resultObservation.requireUnchanged())
      }
      val anchorObservation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
        0, Vector(vector.vec.head), Vec(Vector(left) ++ right.toVector), anchors.declarations,
        anchors.statements.collect { case a: AssignmentStatement => a }))
      block.append(anchors)
      observations += (() => anchorObservation.requireUnchanged())
      Body(block, left, right, result, observations.toVector)
    }
    val widthSchedule = TypedBalancedReductionStageReplay.widths(plan,
      shape.elementLeaves.head.width, certificate.stages.flatMap(_.operators).headOption)
    val stages = certificate.stages.zip(widthSchedule.stages).map { case (stage, widths) =>
      def sameOnPartial(left: ElaborationIntegerExpression, right: ElaborationIntegerExpression): Boolean = {
        val difference = ElaborationWidthAuthority.subtract(left, right)
        ElaborationWidthAuthority.minimumWhen(difference, widths.partialPairActive).contains(BigInt(0)) &&
          ElaborationWidthAuthority.maximumWhen(difference, widths.partialPairActive).contains(BigInt(0))
      }
      val partial = if (!widths.fullPairPossible || !widths.partialPairPossible ||
          (sameOnPartial(widths.fullInput, widths.partialLeft) &&
            sameOnPartial(widths.fullInput, widths.partialRight))) None
        else Some(template(stage, "partial_pair", widths.partialLeft, Some(widths.partialRight),
          widths.partialPairActive))
      ScalarStage(stage.geometry, widths.inputFull, widths.inputTail, widths.outputFull, widths.outputTail,
        widths.inputPacked, widths.outputPacked, widths.fullPairPossible,
        template(stage, if (widths.fullPairPossible) "pair" else "partial_pair",
          if (widths.fullPairPossible) widths.fullInput else widths.partialLeft,
          Some(if (widths.fullPairPossible) widths.fullInput else widths.partialRight),
          if (!widths.fullPairPossible) widths.partialPairActive
          else if (partial.nonEmpty) widths.fullPairActive else stage.geometry.active.expression), partial,
        if (widths.tailPossible) Some(template(stage, "tail", widths.inputTail, None,
          stage.geometry.hasOddTail.expression)) else None)
    }
    certificate.requireFreshness()
    // Probe hardware has discharged the pre-normalization obligations and is
    // never published. Only the distinct replay templates enter native phases.
    certificate.captured.rows.flatMap(r => r.operator.toVector :+ r.bridge)
      .flatMap(_.assignments).foreach(_.removeStatement())
    certificate.captured.rows.flatMap(r => r.operator.toVector :+ r.bridge)
      .flatMap(_.declarations).foreach(_.removeStatement())
    certificate.captured.rows.flatMap(r => r.operator.toVector :+ r.bridge)
      .flatMap(_.statements).collect { case statement: WhenStatement => statement }
      .reverse.foreach(_.removeStatement())
    val input = vector.asBits
    preserve(input, prefix + "_input")
    var output: BaseType = null
    val outputBlock = ParameterizedStructure.captureBlock(owner, None) {
      output = fresh(prefix + "_result", widthSchedule.terminal)
      driveZero(output)
    }
    val outputObservation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
      0, Vector(vector.vec.head), output, outputBlock.declarations,
      outputBlock.statements.collect { case a: AssignmentStatement => a }))
    storage.records += Record(vector.asInstanceOf[Vec[Data]], shape, input, output, plan, stages, ordinal, outputObservation, lexicalOwner, schema)
    output
  }

  private def buildComposite(vector: Vec[Data], op: (Data, Data) => Data,
      bridge: (Data, Int) => Data,
      native: ElabBalancedReduction.Native[Data],
      schema: TypedBalancedReductionCaptureSchema): Data = {
    val owner = Component.current
    val lexicalOwner = ParameterizedStructure.currentLexicalOwner("balanced composite publication")
    // Composite templates use the same exact lexical owner, pre-normalization
    // handoff and result-escape validation as scalar templates. No ownership is
    // inferred from the element layout or the generated block name.
    val storage = owner.userCache.getOrElseUpdate(StorageKey, new Storage).asInstanceOf[Storage]
    val ordinal = storage.records.size + 1
    val prefix = s"morphhdl_balanced_$ordinal"
    val certificate = TypedBalancedReductionCompositeReplay.capture(vector, op, bridge, native, Some(schema))
    val shape = certificate.captured.shape
    val plan = certificate.captured.plan
    def fresh(name: String): Data = {
      val result = ParameterizedWidth.cloneOf(vector.vec.head)
      result.setAsDirectionLess()
      result.setName(name)
      // Explicit leaf anchors preserve exact identity and avoid flatten-name
      // collisions between distinct recursive field paths.
      result.flatten.toVector.zipWithIndex.foreach { case (leaf, index) =>
        preserve(leaf, if (result.isInstanceOf[BaseType]) name else name + "_leaf_" + index)
      }
      claimRecursiveTransport(result)
      result
    }
    def template(stage: TypedBalancedReductionCompositeReplay.Stage, pair: Boolean): Body = {
      var left: Data = null
      var right: Option[Data] = None
      var result: Data = null
      val observations = ArrayBuffer.empty[() => Unit]
      observations += (() => schema.validateBindings())
      val label = prefix + "_l" + stage.geometry.level + (if (pair) "_pair" else "_tail")
      val anchors = ParameterizedStructure.captureBlock(owner, None) {
        left = fresh(label + "_left")
        driveZero(left)
        if (pair) {
          val other = fresh(label + "_right")
          driveZero(other)
          right = Some(other)
        }
      }
      val block = ParameterizedStructure.captureBlock(owner, None) {
        val operated = if (pair) stage.operators.head.replay(left, right.get) else left
        claimRecursiveTransport(operated)
        val bridged = stage.bridges.head.replay(operated)
        claimRecursiveTransport(bridged)
        result = fresh(label + "_result")
        result.assignFrom(bridged)
      }
      val protectedCarriers = block.declarations.filter(leaf =>
        leaf.dontSimplify && leaf.hasTag(noBackendCombMerge))
      observations += (() => {
        if (protectedCarriers.exists(leaf => !leaf.dontSimplify || !leaf.hasTag(noBackendCombMerge)))
          fail("CARRIER-POLICY", "proved composite intermediates lost their native carrier policy")
      })
      val assignments = block.statements.collect { case a: AssignmentStatement => a }
      val observation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
        0, Vector(left) ++ right.toVector ++ schema.hardwareInputs, result, block.declarations, assignments))
      observations += (() => observation.requireUnchanged())
      val anchorObservation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
        0, Vector(vector.vec.head), Vec(Vector(left) ++ right.toVector), anchors.declarations,
        anchors.statements.collect { case a: AssignmentStatement => a }))
      block.append(anchors)
      observations += (() => anchorObservation.requireUnchanged())
      Body(block, left, right, result, observations.toVector)
    }
    val stages = certificate.stages.map(s => CompositeStage(s.geometry, template(s, true), template(s, false)))
    certificate.requireFreshness()
    // Probe hardware has discharged the pre-normalization obligations and is
    // never published. Only the distinct replay templates enter native phases.
    certificate.captured.rows.flatMap(r => r.operator.toVector :+ r.bridge)
      .flatMap(_.assignments).foreach(_.removeStatement())
    certificate.captured.rows.flatMap(r => r.operator.toVector :+ r.bridge)
      .flatMap(_.declarations).foreach(_.removeStatement())
    certificate.captured.rows.flatMap(r => r.operator.toVector :+ r.bridge)
      .flatMap(_.statements).collect { case statement: WhenStatement => statement }
      .reverse.foreach(_.removeStatement())
    val input = vector.asBits
    preserve(input, prefix + "_input")
    var output: Data = null
    val outputBlock = ParameterizedStructure.captureBlock(owner, None) {
      output = fresh(prefix + "_result")
      driveZero(output)
    }
    val outputObservation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
      0, Vector(vector.vec.head), output, outputBlock.declarations,
      outputBlock.statements.collect { case a: AssignmentStatement => a }))
    storage.records += Record(vector, shape, input, output, plan, stages, ordinal, outputObservation, lexicalOwner, schema)
    output
  }

  private def replaceDriver(body: String, value: Data, rhs: String): String = {
    if (!value.isInstanceOf[BaseType])
      fail("ANCHOR", "one native scalar leaf is required for each driver replacement")
    val pattern = ("(?m)^([ \\t]*assign[ \\t]+" + Pattern.quote(value.getName()) +
      "[ \\t]*=[ \\t]*)[^;]+;").r
    if (pattern.findAllMatchIn(body).size != 1)
      fail("ANCHOR", "native scalar input/result must retain exactly one full assignment")
    pattern.replaceAllIn(body, m => Matcher.quoteReplacement(m.group(1) + rhs + ";"))
  }
  private def indent(text: String, spaces: Int): String =
    text.split("\n", -1).map(" " * spaces + _).mkString("\n")

  private def structuralNames(component: Component): Set[String] = {
    def names(region: ParameterizedStructure.StructuralRegion): Vector[String] = {
      val direct = region match {
        case value: ParameterizedStructure.StructuralFor => Vector(value.label, value.indexName)
        case value: ParameterizedStructure.StructuralIf => Vector(value.whenTrueLabel, value.whenFalseLabel)
        case value: ParameterizedStructure.StructuralCase => value.choices.map(_.label) :+ value.defaultLabel
      }
      direct ++ region.blocks.flatMap(_.regions.flatMap(names))
    }
    ParameterizedStructure.regionsOf(component).flatMap(names).toSet
  }

  private[internals] def rewrite(component: Component, verilog: String,
      pc: PhaseContext, canonicalOf: Component => Component): String = {
    records(component).filterNot(_.lexicalOwner.isModuleScope).foreach { record =>
      if (!record.published)
        fail("LEXICAL-PUBLICATION", "a nested reduction was not consumed by its exact structural block")
    }
    rewriteRecords(component, records(component).filter { record =>
      ParameterizedStructure.blockOfLexicalOwner(record.lexicalOwner).isEmpty
    }, verilog, pc, canonicalOf, nested = false)
  }

  /** Called only after the structural publisher has validated and normalized
    * the complete native graph of this exact lexical block. Its native inputs,
    * templates and output remain subject to the ordinary owner/driver checks.
    */
  private[internals] def rewriteScoped(component: Component,
      block: ParameterizedStructuralBlock, body: String,
      pc: PhaseContext, canonicalOf: Component => Component): String =
    rewriteRecords(component, records(component).filter { record =>
      ParameterizedStructure.blockOfLexicalOwner(record.lexicalOwner).exists(_ eq block)
    }, body, pc, canonicalOf, nested = true)

  private def rewriteRecords(component: Component, selected: Vector[Record], verilog: String,
      pc: PhaseContext, canonicalOf: Component => Component, nested: Boolean): String = {
    selected.foldLeft(verilog) { (current, record) =>
      if (record.published)
        fail("DUPLICATE-PUBLICATION", "one exact native reduction cannot be published more than once")
      if (ParameterizedVec.shapeOf(record.vector).forall(_ ne record.shape))
        fail("SHAPE-CHANGED", "the captured Vec no longer owns its original shape")
      if (!record.handedOff)
        fail("HANDOFF", "native template graph was not validated before normalization")
      val lexicalBlock = ParameterizedStructure.blockOfLexicalOwner(record.lexicalOwner)
      if (lexicalBlock.size != record.handoffOwner.size ||
          lexicalBlock.zip(record.handoffOwner).exists { case (actual, frozen) => actual ne frozen })
        fail("LEXICAL-OWNER", "native normalization changed the exact reduction owner")
      ParameterizedStructure.withLexicalOwnerDomain(record.lexicalOwner) {
        validatePackedWidths(record, pc)
        record.stages.flatMap(_.bodies).foreach(validateNativeAnchors)
      }
      val updated = if (record.output.isInstanceOf[BaseType]) rewriteScalar(component, current, record, pc, canonicalOf, nested)
      else rewriteComposite(component, current, record, pc, canonicalOf, nested)
      record.published = true
      updated
    }
  }

  private def rewriteScalar(component: Component, current: String, record: Record,
      pc: PhaseContext, canonicalOf: Component => Component, nested: Boolean): String = {
    val stages = record.stages.map {
      case stage: ScalarStage => stage
      case _ => fail("TRANSPORT-LAYOUT", "a certified transport changed its scalar/composite stage kind")
    }
    val base = s"morphhdl_balanced_${record.ordinal}"
    val identifiers = "[A-Za-z_][A-Za-z0-9_$]*".r.findAllIn(current).toSet ++ structuralNames(component)
    def reserved(prefix: String): Vector[String] =
      (0 to stages.size).map(i => prefix + "_stage_" + i).toVector ++
        stages.indices.flatMap(i => Vector(prefix + "_i_" + i,
          prefix + "_active_" + i, prefix + "_bypass_" + i))
    var suffix = 0
    var prefix = base
    while (reserved(prefix).exists(identifiers)) {
      suffix += 1
      prefix = base + "_" + suffix
    }
    val blocks = stages.flatMap(_.bodies.map(_.block))
    val (remaining, bodies) = ParameterizedVerilogStructural.extractNativeTemplates(
      component, blocks, current, pc, canonicalOf, Some(record.captureSchema))
    def slice(source: String, index: String, stride: ElaborationIntegerExpression,
        width: ElaborationIntegerExpression): String =
      s"$source[(($index) * (${stride.verilog})) +: (${width.verilog})]"
    val lines = ArrayBuffer.empty[String]
    val scopedDeclarations = ArrayBuffer.empty[String]
    def declare(line: String): Unit =
      if (nested) scopedDeclarations += line else lines += line
    val first = prefix + "_stage_0"
    declare(s"  wire [(${stages.head.inputPackedWidth.verilog})-1:0] $first;")
    lines += s"  assign $first = ${record.input.getName()};"
    var bodyIndex = 0
    stages.zipWithIndex.foreach { case (stage, index) =>
      val before = prefix + "_stage_" + index
      val after = prefix + "_stage_" + (index + 1)
      val genvar = prefix + "_i_" + index
      val geometry = stage.geometry
      val pairs = geometry.pairCount.expression.verilog
      val inputs = geometry.inputCount.expression.verilog
      val partialCondition = s"((($inputs) % 2) == 0) && ($genvar == (($pairs) - 1))"
      var pairBody = replaceDriver(bodies(bodyIndex), stage.pair.left,
        slice(before, "2 * " + genvar, stage.inputFullWidth, stage.inputFullWidth))
      pairBody = replaceDriver(pairBody, stage.pair.right.get,
        slice(before, "2 * " + genvar + " + 1", stage.inputFullWidth,
          if (stage.fullPairPossible) stage.inputFullWidth else stage.inputTailWidth))
      bodyIndex += 1
      val partialBody = stage.partialPair.map { body =>
        var text = replaceDriver(bodies(bodyIndex), body.left,
          slice(before, "2 * " + genvar, stage.inputFullWidth, stage.inputFullWidth))
        text = replaceDriver(text, body.right.get,
          slice(before, "2 * " + genvar + " + 1", stage.inputFullWidth, stage.inputTailWidth))
        bodyIndex += 1
        text
      }
      val tailBody = stage.tail.map { body =>
        val text = replaceDriver(bodies(bodyIndex), body.left,
          slice(before, s"($inputs) - 1", stage.inputFullWidth, stage.inputTailWidth))
        bodyIndex += 1
        text
      }
      declare(s"  wire [(${stage.outputPackedWidth.verilog})-1:0] $after;")
      declare(s"  genvar $genvar;")
      if (!nested) lines += "  generate"
      lines += s"    if (${geometry.active.expression.verilog}) begin : ${prefix}_active_$index"
      lines += s"      for ($genvar = 0; $genvar < ($pairs); $genvar = $genvar + 1) begin : pairs"
      stage.partialPair match {
        case Some(body) =>
          lines += s"        if ($partialCondition) begin : partial_pair"
          lines += indent(partialBody.get, 10)
          lines += s"          assign ${slice(after, genvar, stage.outputFullWidth, stage.outputTailWidth)} = ${body.result.getName()};"
          lines += "        end else begin : full_pair"
          lines += indent(pairBody, 10)
          lines += s"          assign ${slice(after, genvar, stage.outputFullWidth, stage.outputFullWidth)} = ${stage.pair.result.getName()};"
          lines += "        end"
        case None =>
          lines += indent(pairBody, 8)
          lines += s"        assign ${slice(after, genvar, stage.outputFullWidth, stage.outputFullWidth)} = ${stage.pair.result.getName()};"
      }
      lines += "      end"
      stage.tail.foreach { body =>
        lines += s"      if (${geometry.hasOddTail.expression.verilog}) begin : tail"
        lines += indent(tailBody.get, 8)
        lines += s"        assign ${slice(after, pairs, stage.outputFullWidth, stage.outputTailWidth)} = ${body.result.getName()};"
        lines += "      end"
      }
      lines += s"    end else begin : ${prefix}_bypass_$index"
      lines += s"      assign $after = $before;"
      lines += "    end"
      if (!nested) lines += "  endgenerate"
    }
    val last = prefix + "_stage_" + stages.size
    val finalWidth = stages.last.outputTailWidth
    val updated = replaceDriver(remaining, record.output,
      slice(last, "0", stages.last.outputFullWidth, finalWidth))
    if (nested) return scopedDeclarations.mkString("\n") + "\n" + updated + "\n" + lines.mkString("\n")
    val end = updated.lastIndexOf("endmodule")
    if (end < 0) fail("MODULE", "native module terminator missing")
    updated.substring(0, end) + lines.mkString("\n") + "\n" + updated.substring(end)
  }

  private def rewriteComposite(component: Component, current: String, record: Record,
      pc: PhaseContext, canonicalOf: Component => Component, nested: Boolean): String = {
    val stages = record.stages.map {
      case stage: CompositeStage => stage
      case _ => fail("TRANSPORT-LAYOUT", "a certified transport changed its scalar/composite stage kind")
    }
    val width = if (record.shape.elementLeaves.size == 1 && !record.shape.elementLayout.hasNestedVectors)
      record.shape.elementLeaves.head.width.verilog else record.shape.elementWidthVerilog
    val base = s"morphhdl_balanced_${record.ordinal}"
    val identifiers = "[A-Za-z_][A-Za-z0-9_$]*".r.findAllIn(current).toSet ++ structuralNames(component)
    def reserved(prefix: String): Vector[String] =
      (0 to stages.size).map(i => prefix + "_stage_" + i).toVector ++
        stages.indices.flatMap(i => Vector(prefix + "_i_" + i,
          prefix + "_active_" + i, prefix + "_bypass_" + i))
    var suffix = 0
    var prefix = base
    while (reserved(prefix).exists(identifiers)) {
      suffix += 1
      prefix = base + "_" + suffix
    }
    val allocatedNames = scala.collection.mutable.HashSet.empty[String] ++ identifiers ++ reserved(prefix)
    def allocateLabel(base: String): String = {
      var result = base
      var ordinal = 0
      while (allocatedNames.contains(result)) {
        ordinal += 1
        result = base + "_" + ordinal
      }
      allocatedNames += result
      result
    }
    val blocks = stages.flatMap(s => Vector(s.pair.block, s.tail.block))
    val (remaining, bodies) = ParameterizedVerilogStructural.extractNativeTemplates(
      component, blocks, current, pc, canonicalOf, Some(record.captureSchema))
    def slice(source: String, index: String): String = s"$source[(($index) * ($width)) +: ($width)]"
    def leafSlice(source: String, index: String, leafIndex: Int): String = {
      if (record.shape.elementLeaves.size == 1 && !record.shape.elementLayout.hasNestedVectors) slice(source, index)
      else {
        val offset = record.shape.elementLeaves.take(leafIndex).map(leaf => s"(${leaf.width.verilog})")
        val offsetText = if (record.shape.elementLayout.hasNestedVectors)
          record.shape.elementLayout.leaves(leafIndex).offset(_.verilog)
          else if (offset.isEmpty) "0" else offset.mkString(" + ")
        val leafWidth = record.shape.elementLeaves(leafIndex).width.verilog
        s"$source[((($index) * ($width)) + ($offsetText)) +: ($leafWidth)]"
      }
    }
    def connect(body: String, value: Data, source: String, index: String,
        moduleScope: Boolean = false): String =
      value.flatten.toVector.zipWithIndex.foldLeft(body) { case (text, (leaf, leafIndex)) =>
        val connected = replaceDriver(text, leaf, leafSlice(source, index, leafIndex))
        val active = record.shape.elementLayout.leaves(leafIndex).activeCondition(_.verilog)
        if (active == "1") connected
        else {
          val assignment = ("(?m)^[ \\t]*assign[ \\t]+" + Pattern.quote(leaf.getName()) +
            "[ \\t]*=[^;]+;").r
          if (assignment.findAllMatchIn(connected).size != 1)
            fail("ANCHOR", "recursive leaf must retain one exact driver")
          val presentLabel = allocateLabel(leaf.getName() + "_present")
          val absentLabel = allocateLabel(leaf.getName() + "_absent")
          assignment.replaceAllIn(connected, m => Matcher.quoteReplacement(
            (if (moduleScope) "  generate\n" else "") +
              s"  if ($active) begin : $presentLabel\n${m.matched}\n" +
              s"  end else begin : $absentLabel\n    assign ${leaf.getName()} = 0;\n  end" +
              (if (moduleScope) "\n  endgenerate" else "")))
        }
      }
    def packed(value: Data): String = {
      val names = value.flatten.toVector.reverse.map(_.getName())
      if (names.size == 1) names.head else names.mkString("{", ", ", "}")
    }
    def publishResult(target: String, index: String, value: Data): Vector[String] = {
      if (!record.shape.elementLayout.hasNestedVectors)
        Vector(s"        assign ${slice(target, index)} = ${packed(value)};")
      else value.flatten.toVector.zipWithIndex.flatMap { case (leaf, leafIndex) =>
        val line = s"        assign ${leafSlice(target, index, leafIndex)} = ${leaf.getName()};"
        val active = record.shape.elementLayout.leaves(leafIndex).activeCondition(_.verilog)
        if (active == "1") Vector(line)
        else Vector(s"        if ($active) begin : ${allocateLabel(leaf.getName() + "_published")}", line, "        end")
      }
    }
    val lines = ArrayBuffer.empty[String]
    val scopedDeclarations = ArrayBuffer.empty[String]
    def declare(line: String): Unit =
      if (nested) scopedDeclarations += line else lines += line
    val first = prefix + "_stage_0"
    declare(s"  wire [(($width) * (${record.plan.count.expression.verilog}))-1:0] $first;")
    lines += s"  assign $first = ${record.input.getName()};"
    stages.zipWithIndex.foreach { case (stage, index) =>
      val before = prefix + "_stage_" + index
      val after = prefix + "_stage_" + (index + 1)
      val genvar = prefix + "_i_" + index
      val geometry = stage.geometry
      val pairs = geometry.pairCount.expression.verilog
      val inputs = geometry.inputCount.expression.verilog
      val outputs = geometry.outputCount.expression.verilog
      var pairBody = connect(bodies(2 * index), stage.pair.left, before, "2 * " + genvar)
      pairBody = connect(pairBody, stage.pair.right.get, before, "2 * " + genvar + " + 1")
      val tailBody = connect(bodies(2 * index + 1), stage.tail.left, before, s"($inputs) - 1")
      declare(s"  wire [(($width) * ($outputs))-1:0] $after;")
      declare(s"  genvar $genvar;")
      if (!nested) lines += "  generate"
      lines += s"    if (${geometry.active.expression.verilog}) begin : ${prefix}_active_$index"
      lines += s"      for ($genvar = 0; $genvar < ($pairs); $genvar = $genvar + 1) begin : pairs"
      lines += indent(pairBody, 8)
      lines ++= publishResult(after, genvar, stage.pair.result)
      lines += "      end"
      lines += s"      if (${geometry.hasOddTail.expression.verilog}) begin : tail"
      lines += indent(tailBody, 8)
      lines ++= publishResult(after, pairs, stage.tail.result)
      lines += "      end"
      lines += s"    end else begin : ${prefix}_bypass_$index"
      lines += s"      assign $after = $before;"
      lines += "    end"
      if (!nested) lines += "  endgenerate"
    }
    val last = prefix + "_stage_" + stages.size
    val updated = connect(remaining, record.output, last, "0", moduleScope = !nested)
    if (nested) return scopedDeclarations.mkString("\n") + "\n" + updated + "\n" + lines.mkString("\n")
    val end = updated.lastIndexOf("endmodule")
    if (end < 0) fail("MODULE", "native module terminator missing")
    updated.substring(0, end) + lines.mkString("\n") + "\n" + updated.substring(end)
  }

}
