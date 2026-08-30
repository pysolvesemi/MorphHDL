package spinal.core.internals

import java.util.IdentityHashMap
import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core._

/** MorphHDL-owned Increment 33 relocation of validated ordinary SpinalHDL module items into
  * Verilog-2001 generate regions.
  *
  * The native emitter remains authoritative for declarations, assignments,
  * instance port order and expression syntax. This pass only extracts the
  * module items recorded by [[ParameterizedStructure]], substitutes retained
  * generate-index slices/Vec selections, and places those exact items beneath
  * generate-for/if/case control.
  */
private[internals] object ParameterizedVerilogStructural {
  private final case class LineRange(start: Int, end: Int) {
    require(start <= end)
    def indices: Range.Inclusive = start to end
    def overlaps(that: LineRange): Boolean = start <= that.end && that.start <= end
  }

  private final case class AssignmentEvidence(
      target: String,
      sourceNames: Set[String],
      sourceBooleanLiteral: Option[BigInt]
  )

  private final case class ContinuousAssignmentPromotionProof(
      target: String,
      declarationLine: Int,
      promotedOwner: ParameterizedStructuralBlock,
      rhsNames: Set[String]
  )

  private final case class ContinuousAssignmentResolution(
      owners: Map[Int, ParameterizedStructuralBlock],
      moduleScopeLines: Set[Int],
      promotions: Map[Int, ContinuousAssignmentPromotionProof]
  ) {
    def isEmpty: Boolean =
      owners.isEmpty && moduleScopeLines.isEmpty && promotions.isEmpty
  }

  private object ContinuousAssignmentResolution {
    val empty = ContinuousAssignmentResolution(Map.empty, Set.empty, Map.empty)
  }

  private final case class BlockPlan(
      block: ParameterizedStructuralBlock,
      ranges: Vector[LineRange],
      body: String,
      ownedNames: Set[String],
      directSourceNames: Set[String],
      childOutputActualNames: Set[String],
      assignmentEvidence: Vector[AssignmentEvidence]
  )

  private final case class AlternativeStep(
      region: ParameterizedStructure.StructuralRegion,
      branch: Int
  )

  def hasRegions(component: Component): Boolean =
    ParameterizedStructure.regionsOf(component).nonEmpty

  def rewrite(
      component: Component,
      verilog: String,
      pc: PhaseContext,
      canonicalOf: Component => Component
  ): String = {
    val regions = ParameterizedStructure.regionsOf(component)
    if (regions.isEmpty) return verilog
    if (pc.config.isSystemVerilog) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MODE-UNSUPPORTED",
        "native structural generate lowering targets Verilog-2001, not SystemVerilog"
      )
    }

    val normalized = verilog.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split("\n", -1).toVector
    val allBlocks = regions.flatMap(region => ParameterizedStructure.allBlocks(region))
    val duplicateBlocks = allBlocks.groupBy(identity).collectFirst {
      case (block, values) if values.size != 1 => block
    }
    duplicateBlocks.foreach { block =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-BLOCK-DUPLICATE",
        "one captured structural block is referenced by multiple generate regions",
        block.sourceLocation
      )
    }

    val componentPorts = component.getOrdredNodeIo.toVector
    val portNames = componentPorts
      .flatMap(port => Option(port.getName()))
      .toSet
    val inputPortNames = componentPorts
      .filter(_.dir == in)
      .flatMap(port => Option(port.getName()))
      .toSet
    val parameters = mergeParameters(
      ParameterizedWidth.parametersOf(component) ++
        ExternalParameterizedMemoryRegistry.parametersOf(component) ++
        ExternalParameterizedValueRegistry.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
        ParameterizedProcess.parametersOf(component)
    )
    validateParameters(component, parameters, portNames, pc)

    val assignmentOwners = mutable.LinkedHashMap.empty[
      String,
      mutable.LinkedHashSet[ParameterizedStructuralBlock]
    ]
    allBlocks.foreach { block =>
      block.assignments.foreach { assignment =>
        Option(assignment.finalTarget.getName()).filter(_.nonEmpty).foreach { name =>
          assignmentOwners
            .getOrElseUpdate(
              name,
              mutable.LinkedHashSet.empty[ParameterizedStructuralBlock]
            ) += block
        }
      }
    }
    val uniquelyOwnedAssignmentTargets = assignmentOwners.collect {
      case (name, owners) if owners.size == 1 => name
    }.toSet

    val alternativePaths = structuralAlternativePaths(regions)
    val containmentPaths = structuralContainmentPaths(regions)
    def plansWithContinuousResolution(
        continuousResolution: ContinuousAssignmentResolution
    ): Vector[BlockPlan] = allBlocks.map { block =>
      planBlock(
        component,
        block,
        lines,
        portNames,
        parameters.map(_.name).toSet,
        uniquelyOwnedAssignmentTargets,
        continuousResolution,
        canonicalOf
      )
    }
    val preliminaryPlans =
      plansWithContinuousResolution(ContinuousAssignmentResolution.empty)
    val continuousResolution = sharedContinuousAssignmentResolution(
      preliminaryPlans,
      lines,
      alternativePaths,
      containmentPaths,
      portNames ++ parameters.map(_.name),
      inputPortNames ++ parameters.map(_.name),
      parameters.map(_.name).toSet
    )
    val rawPlans =
      if (continuousResolution.isEmpty) preliminaryPlans
      else plansWithContinuousResolution(continuousResolution)
    validateSharedContinuousAssignmentReplay(
      rawPlans,
      lines,
      continuousResolution
    )
    val (resolvedPlans, sharedProcessRanges) =
      resolveSharedProceduralProcesses(
        rawPlans,
        lines,
        alternativePaths,
        containmentPaths,
        portNames ++ parameters.map(_.name)
      )
    validateBranchLocalReferences(resolvedPlans, lines)
    val plans = resolvedPlans.map(finalizePlan)
    validateContinuousAssignmentDominance(
      plans,
      lines,
      containmentPaths,
      continuousResolution
    )
    val allRanges = plans.flatMap(_.ranges)
    allRanges.combinations(2).foreach {
      case Vector(left, right)
          if left.overlaps(right) &&
            !(left == right && sharedProcessRanges(left)) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CAPTURE-OVERLAP",
          s"captured native module-item ranges ${left.start}-${left.end} and ${right.start}-${right.end} overlap"
        )
      case _ =>
    }

    val removed = allRanges.flatMap(_.indices).toSet
    val withoutCaptured = lines.zipWithIndex.collect {
      case (line, index) if !removed(index) => line
    }
    val withHeader = ensureParameterHeader(
      component.definitionName,
      withoutCaptured,
      parameters
    )
    val planByBlock = plans.map(plan => plan.block -> plan).toMap
    val renderedRegions = regions.map(region => renderRegion(region, planByBlock))

    val endmodule = withHeader.lastIndexWhere(_.trim == "endmodule")
    if (endmodule < 0) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-ENDMODULE-NOT-FOUND",
        s"native Verilog for '${component.definitionName}' contains no endmodule"
      )
    }
    (
      withHeader.take(endmodule) ++
        Vector("") ++
        renderedRegions.flatMap(_.split("\n", -1)).toVector ++
        withHeader.drop(endmodule)
    ).mkString("\n")
  }

  private def planBlock(
      component: Component,
      block: ParameterizedStructuralBlock,
      lines: Vector[String],
      portNames: Set[String],
      parameterNames: Set[String],
      uniquelyOwnedAssignmentTargets: Set[String],
      continuousResolution: ContinuousAssignmentResolution,
      canonicalOf: Component => Component
  ): BlockPlan = {
    val ranges = ArrayBuffer.empty[LineRange]
    val trackedInternalNames = mutable.LinkedHashSet.empty[String]
    val ownedTargetNames = mutable.LinkedHashSet.empty[String]
    val proceduralOwnedTargetNames = mutable.LinkedHashSet.empty[String]
    val directSourceNames = mutable.LinkedHashSet.empty[String]
    val childOutputActualNames = mutable.LinkedHashSet.empty[String]
    val assignmentEvidence = ArrayBuffer.empty[AssignmentEvidence]
    val emittedActualByExpression = new IdentityHashMap[Expression, String]()

    // Normal Spinal transforms may forward or prune captured temporaries before
    // native emission. Such values have no emitted declaration (and therefore
    // no stable name); the instance connections and slice references below
    // recover the concrete wrapper nets that remain in Verilog.
    block.declarations.foreach { declaration =>
      Option(declaration.getName()).filter(_.nonEmpty).foreach { name =>
        ownedTargetNames += name
        directSourceNames += name
        trackedInternalNames += name
        ranges += findDeclarationLine(lines, name, block.sourceLocation)
      }
    }

    block.memories.foreach { memory =>
      val name = requiredName(
        memory,
        "captured native memory",
        block.sourceLocation
      )
      if (memory.initialContent != null) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEMORY-INITIALIZATION-UNSUPPORTED",
          s"captured native memory '$name' uses initialization; nested structural capture currently retains inferred runtime storage only",
          block.sourceLocation
        )
      }
      if (memory.forceMemToBlackboxTranslation) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEMORY-BLACKBOX-UNSUPPORTED",
          s"captured native memory '$name' requests black-box translation",
          block.sourceLocation
        )
      }
      ownedTargetNames += name
      proceduralOwnedTargetNames += name
      directSourceNames += name
      trackedInternalNames += name
      memory.foreachStatements {
        case read: MemReadSync =>
          val readTarget = requiredName(
            read,
            "captured native synchronous memory read result",
            block.sourceLocation
          )
          ownedTargetNames += readTarget
          proceduralOwnedTargetNames += readTarget
        case _ =>
      }
      ranges += findMemoryDeclarationLine(
        lines,
        memory,
        name,
        block.sourceLocation
      )
    }

    block.assignments.foreach { assignment =>
      Option(assignment.finalTarget.getName()).filter(_.nonEmpty).foreach { name =>
        ownedTargetNames += name
        proceduralOwnedTargetNames += name
      }
    }
    def recordInitializationTarget(statement: Statement): Unit = statement match {
      case initialization: InitAssignmentStatement =>
        Option(initialization.finalTarget.getName()).filter(_.nonEmpty).foreach { name =>
          proceduralOwnedTargetNames += name
        }
      case tree: TreeStatement => tree.foreachStatements(recordInitializationTarget)
      case _                   =>
    }
    block.statements.foreach(recordInitializationTarget)

    block.children.foreach { child =>
      val instanceName = Option(child.getName()).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-INSTANCE-NAME-MISSING",
          s"captured child of '${component.definitionName}' has no stable instance name",
          block.sourceLocation
        )
      }
      val canonical = canonicalOf(child)
      val definitionName = Option(canonical.definitionName).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-DEFINITION-NAME-MISSING",
          s"captured child '$instanceName' has no canonical definition name",
          block.sourceLocation
        )
      }
      val range = findInstanceRange(
        lines,
        definitionName,
        instanceName,
        block.sourceLocation
      )
      ranges += range
      val instanceText = range.indices.map(lines).mkString("\n")
      val actualByPort = connectionActualByPort(instanceText)
      child.getOrdredNodeIo.toVector.foreach { port =>
        Option(port.getName()).flatMap(actualByPort.get).foreach { actual =>
          emittedActualByExpression.put(port, actual)
          if (port.dir == out) childOutputActualNames += actual
        }
      }
      connectionActualNames(instanceText).foreach { name =>
        if (!portNames(name) && !parameterNames(name)) trackedInternalNames += name
      }
    }

    block.assignments.foreach { assignment =>
      Option(assignment.finalTarget.getName()).filter(_.nonEmpty).foreach { name =>
        assignmentEvidence += AssignmentEvidence(
          name,
          expressionNames(assignment.source) ++
            emittedActualNames(assignment.source, emittedActualByExpression),
          wholeTargetBooleanLiteral(assignment)
        )
      }
    }

    val nativeProceduralBlocks = proceduralBlocks(lines, block.sourceLocation)
    nativeProceduralBlocks.foreach { processRange =>
      processRange.indices.foreach { index =>
        val stripped = stripLineComment(lines(index)).trim
        DirectProceduralAssignment.findFirstMatchIn(stripped).foreach { value =>
          if (
            ownedTargetNames(value.group(1)) &&
            uniquelyOwnedAssignmentTargets(value.group(1))
          ) {
            identifierTokens(value.group(3)).foreach { name =>
              if (!portNames(name) && !parameterNames(name)) {
                trackedInternalNames += name
              }
            }
          }
        }
      }
    }

    val fixedSlicePatterns = block.slices.map(fixedSlicePattern)
    var changed = true
    while (changed) {
      changed = false
      lines.zipWithIndex.foreach { case (line, index) =>
        if (!ranges.exists(_.indices.contains(index))) {
          val trimmed = line.trim
          val declaration = isDeclarationLine(trimmed)
          val mentionsInternal = trackedInternalNames.exists(name => containsName(line, name))
          val mentionsSlice = fixedSlicePatterns.exists(_.matcher(line).find())
          val continuousAssignment =
            DirectContinuousAssignment.findFirstMatchIn(trimmed)
          val ownedContinuousAssignment = continuousAssignment.exists { value =>
            val target = value.group(1)
            val explicitlyOwned =
              continuousResolution.owners.get(index).exists(_ eq block)
            !continuousResolution.moduleScopeLines(index) &&
            continuousResolution.owners.get(index).forall(_ eq block) &&
            (explicitlyOwned || ownedTargetNames(target) ||
              trackedInternalNames(target) || mentionsSlice)
          }
          if ((declaration && mentionsInternal) || ownedContinuousAssignment) {
            ranges += LineRange(index, index)
            val dependencies = continuousAssignment match {
              case Some(value) =>
                val target = value.group(1)
                if (!portNames(target) && !parameterNames(target)) {
                  if (trackedInternalNames.add(target)) changed = true
                }
                identifierTokens(value.group(3))
              case None => identifiers(line).toSet
            }
            dependencies.foreach { name =>
              if (
                !portNames(name) && !parameterNames(name) &&
                !VerilogWords(name)
              ) {
                if (trackedInternalNames.add(name)) changed = true
              }
            }
          }
        }
      }
    }

    nativeProceduralBlocks.foreach { processRange =>
      if (!ranges.exists(_.overlaps(processRange))) {
        val processText = processRange.indices.map(lines).mkString("\n")
        val targets = proceduralAssignmentTargets(processText)
        val capturedTargets = targets.filter(proceduralOwnedTargetNames)
        if (capturedTargets.nonEmpty) {
          val foreignTargets = targets.filterNot(ownedTargetNames)
          if (foreignTargets.nonEmpty) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-MIXED-OWNERSHIP",
              s"one native process assigns captured targets ${capturedTargets.mkString(", ")} and non-captured targets ${foreignTargets
                  .mkString(", ")}; split the clocked logic before placing it in a symbolic alternative",
              block.sourceLocation
            )
          }
          ranges += processRange
        }
      }
    }

    val mergedRanges = mergeRanges(ranges.toVector)
    val selectedLines = mergedRanges.flatMap(_.indices.map(lines))
    var body = stripCommonIndent(selectedLines.mkString("\n").trim)
    if (
      body.isEmpty &&
      (ParameterizedStructuralSynthetic.isSyntheticEmpty(block) || block.regions.nonEmpty)
    ) {
      return BlockPlan(
        block,
        mergedRanges,
        "",
        (trackedInternalNames ++ ownedTargetNames).toSet,
        (directSourceNames ++ childOutputActualNames).toSet,
        childOutputActualNames.toSet,
        assignmentEvidence.toVector
      )
    }
    if (body.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-BODY-NOT-FOUND",
        "captured structural body did not map to native Verilog module items",
        block.sourceLocation
      )
    }
    BlockPlan(
      block,
      mergedRanges,
      body,
      (trackedInternalNames ++ ownedTargetNames).toSet,
      (directSourceNames ++ childOutputActualNames).toSet,
      childOutputActualNames.toSet,
      assignmentEvidence.toVector
    )
  }

  private def validateBranchLocalReferences(
      plans: Vector[BlockPlan],
      lines: Vector[String]
  ): Unit = {
    val removedIndices = plans.flatMap(_.ranges).flatMap(_.indices).toSet
    val branchLocalNames = plans
      .flatMap { plan =>
        plan.block.declarations.flatMap { declaration =>
          Option(declaration.getName()).filter(_.nonEmpty).flatMap { name =>
            val range = findDeclarationLine(
              lines,
              name,
              plan.block.sourceLocation
            )
            if (range.indices.exists(removedIndices)) Some(name) else None
          }
        }
      }
      .distinct
      .sorted

    branchLocalNames.foreach { name =>
      lines.zipWithIndex
        .collectFirst {
          case (line, index)
              if !removedIndices(index) &&
                containsName(stripLineComment(line), name) =>
            index -> stripLineComment(line).trim
        }
        .foreach { case (index, line) =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-BRANCH-LOCAL-REFERENCE-ESCAPES",
            s"native module-scope line ${index + 1} references branch-local '$name': '$line'"
          )
        }
    }
  }

  private def expressionNames(root: Expression): Set[String] = {
    val names = mutable.LinkedHashSet.empty[String]
    val visited = new IdentityHashMap[Expression, java.lang.Boolean]()

    def visit(value: Expression): Unit = {
      if ((value ne null) && !visited.containsKey(value)) {
        visited.put(value, java.lang.Boolean.TRUE)
        value match {
          case named: Nameable =>
            Option(named.getName()).filter(_.nonEmpty).foreach(names += _)
          case _ =>
        }
        value.foreachExpression(visit)
      }
    }

    visit(root)
    names.toSet
  }

  /** Retain only the literal case whose emitted width and assignment footprint
    * are exact without parsing Verilog sizing rules: a direct whole-Bool 0/1
    * assignment. Wider, selected, resized and folded literals use stronger
    * source evidence or residual-capacity proof and otherwise fail closed.
    */
  private def wholeTargetBooleanLiteral(
      assignment: DataAssignmentStatement
  ): Option[BigInt] = assignment.target match {
    case target: BaseType
        if (target eq assignment.finalTarget) &&
          target.getTypeObject == TypeBool && target.getBitsWidth == 1 =>
      assignment.source match {
        case literal: BoolLiteral if !literal.hasPoison() =>
          Some(if (literal.value) BigInt(1) else BigInt(0))
        case _ => None
      }
    case _ => None
  }

  private def emittedActualNames(
      root: Expression,
      actualByExpression: IdentityHashMap[Expression, String]
  ): Set[String] = {
    val names = mutable.LinkedHashSet.empty[String]
    val visited = new IdentityHashMap[Expression, java.lang.Boolean]()

    def visit(value: Expression): Unit = {
      if ((value ne null) && !visited.containsKey(value)) {
        visited.put(value, java.lang.Boolean.TRUE)
        Option(actualByExpression.get(value)).foreach(names += _)
        value.foreachExpression(visit)
      }
    }

    visit(root)
    names.toSet
  }

  private def finalizePlan(plan: BlockPlan): BlockPlan = {
    if (plan.body.trim.isEmpty) plan
    else {
      var body = rewriteSlices(plan.body, plan.block.slices)
      body = rewriteVecSelections(
        body,
        plan.block.vecIndices,
        plan.block.sourceLocation
      )
      plan.copy(body = body)
    }
  }

  private def structuralAlternativePaths(
      regions: Vector[ParameterizedStructure.StructuralRegion]
  ): Map[ParameterizedStructuralBlock, Vector[AlternativeStep]] = {
    val paths = mutable.LinkedHashMap.empty[
      ParameterizedStructuralBlock,
      Vector[AlternativeStep]
    ]

    object Walker {
      def visitBlock(
          block: ParameterizedStructuralBlock,
          path: Vector[AlternativeStep]
      ): Unit = {
        paths(block) = path
        block.regions.foreach(region => visitRegion(region, path))
      }

      def visitRegion(
          region: ParameterizedStructure.StructuralRegion,
          path: Vector[AlternativeStep]
      ): Unit = region match {
        case value: ParameterizedStructure.StructuralFor =>
          visitBlock(value.body, path)
        case value: ParameterizedStructure.StructuralIf =>
          visitBlock(
            value.whenTrue,
            path :+ AlternativeStep(value, branch = 0)
          )
          visitBlock(
            value.whenFalse,
            path :+ AlternativeStep(value, branch = 1)
          )
        case value: ParameterizedStructure.StructuralCase =>
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
    }

    regions.foreach(region => Walker.visitRegion(region, Vector.empty))
    paths.toMap
  }

  private def structuralContainmentPaths(
      regions: Vector[ParameterizedStructure.StructuralRegion]
  ): Map[ParameterizedStructuralBlock, Vector[ParameterizedStructuralBlock]] = {
    val paths = mutable.LinkedHashMap.empty[
      ParameterizedStructuralBlock,
      Vector[ParameterizedStructuralBlock]
    ]

    object Walker {
      def visitBlock(
          block: ParameterizedStructuralBlock,
          ancestors: Vector[ParameterizedStructuralBlock]
      ): Unit = {
        val path = ancestors :+ block
        paths(block) = path
        block.regions.foreach(region => visitRegion(region, path))
      }

      def visitRegion(
          region: ParameterizedStructure.StructuralRegion,
          ancestors: Vector[ParameterizedStructuralBlock]
      ): Unit = region match {
        case value: ParameterizedStructure.StructuralFor =>
          visitBlock(value.body, ancestors)
        case value: ParameterizedStructure.StructuralIf =>
          visitBlock(value.whenTrue, ancestors)
          visitBlock(value.whenFalse, ancestors)
        case value: ParameterizedStructure.StructuralCase =>
          value.choices.foreach(choice => visitBlock(choice.body, ancestors))
          visitBlock(value.defaultBody, ancestors)
      }
    }

    regions.foreach(region => Walker.visitRegion(region, Vector.empty))
    paths.toMap
  }

  private def containmentPrefix(
      prefix: Vector[ParameterizedStructuralBlock],
      path: Vector[ParameterizedStructuralBlock]
  ): Boolean =
    prefix.size <= path.size && prefix.zip(path).forall { case (left, right) =>
      left eq right
    }

  private def containmentPathOf(
      block: ParameterizedStructuralBlock,
      containmentPaths: Map[
        ParameterizedStructuralBlock,
        Vector[ParameterizedStructuralBlock]
      ]
  ): Vector[ParameterizedStructuralBlock] =
    containmentPaths.getOrElse(
      block,
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTAINMENT-MISSING",
        "captured structural block has no exact lexical containment path",
        block.sourceLocation
      )
    )

  private def leastCommonContainingBlock(
      blocks: Vector[ParameterizedStructuralBlock],
      containmentPaths: Map[
        ParameterizedStructuralBlock,
        Vector[ParameterizedStructuralBlock]
      ]
  ): Option[ParameterizedStructuralBlock] = {
    if (blocks.isEmpty) None
    else {
      val paths = blocks.map(block => containmentPathOf(block, containmentPaths))
      val shortest = paths.map(_.size).min
      var common = 0
      while (
        common < shortest &&
        paths.tail.forall(path => path(common) eq paths.head(common))
      ) common += 1
      if (common == 0) None else Some(paths.head(common - 1))
    }
  }

  private def mutuallyExclusive(
      left: Vector[AlternativeStep],
      right: Vector[AlternativeStep]
  ): Boolean = ParameterizedStructure.mutuallyExclusiveAlternatives(
    left.map(value => value.region -> value.branch),
    right.map(value => value.region -> value.branch)
  )

  private val DirectProceduralAssignment =
    """^\s*([A-Za-z_][A-Za-z0-9_$]*)(\s*\[[^\]]+\])?\s*(?:<=|=(?!=))\s*(.*?)\s*;\s*$""".r

  private val DirectContinuousAssignment =
    """^\s*assign\s+([A-Za-z_][A-Za-z0-9_$]*)(\s*\[[^\]]+\])?\s*=\s*(.*?)\s*;\s*$""".r

  private val VerilogBasedIntegerLiteral =
    """(?i)^\s*[0-9]*'s?([bodh])([0-9a-f_]+)\s*$""".r

  private val VerilogDecimalIntegerLiteral =
    """^\s*([0-9]+)\s*$""".r

  private def verilogLiteral(value: String): Option[BigInt] = value match {
    case VerilogBasedIntegerLiteral(base, digits) =>
      val radix = base.toLowerCase match {
        case "b" => 2
        case "o" => 8
        case "d" => 10
        case "h" => 16
      }
      try Some(BigInt(digits.replace("_", ""), radix))
      catch { case _: NumberFormatException => None }
    case VerilogDecimalIntegerLiteral(digits) => Some(BigInt(digits))
    case _                                    => None
  }

  private val VerilogIdentifier =
    "[A-Za-z_][A-Za-z0-9_$]*".r

  private val VerilogStringLiteral =
    "\"(?:\\\\.|[^\"\\\\])*\"".r

  private val VerilogBasedLiteral =
    "(?i)(?:[0-9][0-9_]*)?'s?[bodh][0-9a-f_xz?]+".r

  private val VerilogUnbasedLiteral =
    "(?i)'[01xz]".r

  private val VerilogSystemIdentifier =
    "(?<![A-Za-z0-9_$])\\$([A-Za-z_][A-Za-z0-9_$]*)".r

  private val VerilogCallIdentifier =
    "([A-Za-z_][A-Za-z0-9_$]*)[ \\t]*\\(".r

  private val VerilogHierarchicalReference =
    "[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)+".r

  private def identifierTokens(value: String): Set[String] =
    VerilogIdentifier.findAllIn(value).filterNot(VerilogWords).toSet

  /** Remove non-code lexical regions without hiding identifiers passed to a
    * native call or instance connection. Strings are erased before comments
    * so a `//` embedded in a message cannot truncate a later real reference.
    */
  private def verilogReferenceText(value: String): String = {
    val withoutStrings = VerilogStringLiteral.replaceAllIn(
      value,
      matched => matched.matched.map(character => if (character == '\n') '\n' else ' ')
    )
    val withoutComments = new StringBuilder(withoutStrings.length)
    var index = 0
    var blockComment = false
    while (index < withoutStrings.length) {
      if (blockComment) {
        if (
          index + 1 < withoutStrings.length &&
          withoutStrings.charAt(index) == '*' &&
          withoutStrings.charAt(index + 1) == '/'
        ) {
          withoutComments.append("  ")
          index += 2
          blockComment = false
        } else {
          val current = withoutStrings.charAt(index)
          withoutComments.append(if (current == '\n') '\n' else ' ')
          index += 1
        }
      } else if (
        index + 1 < withoutStrings.length &&
        withoutStrings.charAt(index) == '/' &&
        withoutStrings.charAt(index + 1) == '/'
      ) {
        withoutComments.append("  ")
        index += 2
        while (
          index < withoutStrings.length &&
          withoutStrings.charAt(index) != '\n'
        ) {
          withoutComments.append(' ')
          index += 1
        }
      } else if (
        index + 1 < withoutStrings.length &&
        withoutStrings.charAt(index) == '/' &&
        withoutStrings.charAt(index + 1) == '*'
      ) {
        withoutComments.append("  ")
        index += 2
        blockComment = true
      } else {
        withoutComments.append(withoutStrings.charAt(index))
        index += 1
      }
    }
    val withoutBased = VerilogBasedLiteral.replaceAllIn(
      withoutComments.result(),
      matched => " " * matched.matched.length
    )
    VerilogUnbasedLiteral.replaceAllIn(
      withoutBased,
      matched => " " * matched.matched.length
    )
  }

  private val VerilogSimpleInstanceHeader =
    "(?m)^[ \\t]*[A-Za-z_][A-Za-z0-9_$]*[ \\t]+[A-Za-z_][A-Za-z0-9_$]*[ \\t]*\\(".r

  private val VerilogParameterizedInstanceHeader =
    "(?m)^[ \\t]*[A-Za-z_][A-Za-z0-9_$]*[ \\t]*#[ \\t]*\\(".r

  private val VerilogNamedBlockLabel =
    "(?m)\\bbegin[ \\t]*:[ \\t]*([A-Za-z_][A-Za-z0-9_$]*)".r

  private def blankVerilogSyntax(value: String): String =
    value.map(character => if (character == '\n') '\n' else ' ')

  private[internals] def verilogReferenceNames(value: String): Set[String] = {
    val lexical = verilogReferenceText(value)
    val withoutHierarchy = VerilogHierarchicalReference.replaceAllIn(
      lexical,
      matched => blankVerilogSyntax(matched.matched)
    )
    val withoutInstances = VerilogSimpleInstanceHeader.replaceAllIn(
      VerilogParameterizedInstanceHeader.replaceAllIn(
        withoutHierarchy,
        matched => blankVerilogSyntax(matched.matched)
      ),
      matched => blankVerilogSyntax(matched.matched)
    )
    val withoutSystem = VerilogSystemIdentifier.replaceAllIn(
      withoutInstances,
      matched => blankVerilogSyntax(matched.matched)
    )
    val withoutCallees = VerilogCallIdentifier.replaceAllIn(
      withoutSystem,
      matched => {
        val openingParenthesis = matched.matched.lastIndexOf('(')
        blankVerilogSyntax(matched.matched.substring(0, openingParenthesis)) +
          matched.matched.substring(openingParenthesis)
      }
    )
    val withoutLabels = VerilogNamedBlockLabel.replaceAllIn(
      withoutCallees,
      matched => blankVerilogSyntax(matched.matched)
    )
    identifierTokens(withoutLabels)
  }

  private def sanitizedIdentifierTokens(value: String): Set[String] = {
    val withoutStrings = VerilogStringLiteral.replaceAllIn(value, " ")
    val withoutBased = VerilogBasedLiteral.replaceAllIn(withoutStrings, " ")
    val sanitized = VerilogUnbasedLiteral.replaceAllIn(withoutBased, " ")
    val excluded =
      VerilogSystemIdentifier
        .findAllMatchIn(sanitized)
        .map(_.group(1))
        .toSet ++
        VerilogCallIdentifier
          .findAllMatchIn(sanitized)
          .map(_.group(1))
          .toSet ++
        VerilogHierarchicalReference
          .findAllIn(sanitized)
          .flatMap(identifierTokens)
          .toSet
    identifierTokens(sanitized) -- excluded
  }

  private def continuousAssignmentSourceTokens(
      value: String,
      target: String
  ): Set[String] = {
    if (value.contains('\\')) Set.empty
    else {
      val withoutStrings = VerilogStringLiteral.replaceAllIn(value, " ")
      val withoutBased = VerilogBasedLiteral.replaceAllIn(withoutStrings, " ")
      val sanitized = VerilogUnbasedLiteral.replaceAllIn(withoutBased, " ")
      val excluded =
        VerilogSystemIdentifier
          .findAllMatchIn(sanitized)
          .map(_.group(1))
          .toSet ++
          VerilogCallIdentifier
            .findAllMatchIn(sanitized)
            .map(_.group(1))
            .toSet ++
          VerilogHierarchicalReference
            .findAllIn(sanitized)
            .flatMap(identifierTokens)
            .toSet
      identifierTokens(sanitized) -- excluded - target
    }
  }

  /** Promotion is allowed only when every identifier in a whole-target driver
    * can be enumerated without interpreting Verilog scoping or call syntax.
    * The ordinary ownership path may still retain richer native expressions;
    * this predicate is deliberately specific to moving a driver outward.
    */
  private val PortableBooleanToken =
    """1'[bB][01]|&&|\|\||[!~&|^()]|[A-Za-z_][A-Za-z0-9_$]*""".r

  private def portableBooleanExpression(
      value: String,
      expectedNames: Set[String]
  ): Boolean = {
    val matches = PortableBooleanToken.findAllMatchIn(value).toVector
    val tokens = matches.map(_.matched)
    var cursor = 0
    val tokensCoverExpression = matches.forall { matched =>
      val gapIsWhitespace =
        value.substring(cursor, matched.start).forall(_.isWhitespace)
      cursor = matched.end
      gapIsWhitespace
    } && value.substring(cursor).forall(_.isWhitespace)
    if (!tokensCoverExpression || tokens.isEmpty) return false

    var position = 0
    def isIdentifier(token: String): Boolean =
      VerilogIdentifier.pattern.matcher(token).matches()
    def isLiteral(token: String): Boolean =
      token.matches("1'[bB][01]")
    def parsePrimary(): Boolean = {
      if (position >= tokens.size) false
      else if (isIdentifier(tokens(position)) || isLiteral(tokens(position))) {
        position += 1
        true
      } else if (tokens(position) == "(") {
        position += 1
        val nested = parseExpression()
        if (nested && position < tokens.size && tokens(position) == ")") {
          position += 1
          true
        } else false
      } else false
    }
    def parseUnary(): Boolean = {
      while (
        position < tokens.size &&
        (tokens(position) == "!" || tokens(position) == "~")
      ) position += 1
      parsePrimary()
    }
    def parseExpression(): Boolean = {
      if (!parseUnary()) false
      else {
        var valid = true
        while (
          valid && position < tokens.size &&
          Set("&&", "||", "&", "|", "^")(tokens(position))
        ) {
          position += 1
          valid = parseUnary()
        }
        valid
      }
    }

    val syntaxValid = parseExpression() && position == tokens.size
    val identifierNames = tokens.filter(isIdentifier).filterNot(VerilogWords).toSet
    syntaxValid && identifierNames == expectedNames
  }

  private def portableWholeContinuousDriver(
      lhsSelection: String,
      rhsText: String,
      rhsNames: Set[String]
  ): Boolean =
    Option(lhsSelection).forall(_.trim.isEmpty) &&
      !rhsText.contains('\\') &&
      VerilogStringLiteral.findFirstIn(rhsText).isEmpty &&
      VerilogSystemIdentifier.findFirstIn(rhsText).isEmpty &&
      VerilogCallIdentifier.findFirstIn(rhsText).isEmpty &&
      VerilogHierarchicalReference.findFirstIn(rhsText).isEmpty &&
      sanitizedIdentifierTokens(rhsText) == rhsNames &&
      portableBooleanExpression(rhsText, rhsNames)

  private def sharedContinuousAssignmentResolution(
      plans: Vector[BlockPlan],
      lines: Vector[String],
      paths: Map[ParameterizedStructuralBlock, Vector[AlternativeStep]],
      containmentPaths: Map[
        ParameterizedStructuralBlock,
        Vector[ParameterizedStructuralBlock]
      ],
      moduleScopeNames: Set[String],
      intrinsicSourceNames: Set[String],
      parameterNames: Set[String]
  ): ContinuousAssignmentResolution = {
    val claims = planClaimsByLine(plans)
    val proceduralRanges = proceduralBlocks(lines, None)

    val owners = mutable.LinkedHashMap.empty[Int, ParameterizedStructuralBlock]
    val moduleScopeLines = mutable.LinkedHashSet.empty[Int]
    val promotions = mutable.LinkedHashMap.empty[
      Int,
      ContinuousAssignmentPromotionProof
    ]
    def uniqueMostSpecificOwner(
        candidates: Vector[BlockPlan]
    ): Option[BlockPlan] = {
      val distinct = candidates.distinct
      val deepest = distinct.filter { candidate =>
        val candidatePath =
          containmentPathOf(candidate.block, containmentPaths)
        distinct.forall { other =>
          containmentPrefix(
            containmentPathOf(other.block, containmentPaths),
            candidatePath
          )
        }
      }
      if (deepest.size == 1) Some(deepest.head) else None
    }

    lines.zipWithIndex.foreach { case (line, index) =>
      val normalized = stripLineComment(line).trim
      DirectContinuousAssignment.findFirstMatchIn(normalized).foreach { statement =>
        val claimed = claims.getOrElse(index, Vector.empty)
        if (claimed.size > 1 || claimed.isEmpty) {
          if (proceduralRanges.exists(_.indices.contains(index))) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-CONTINUOUS-ASSIGNMENT-SCOPE-UNSUPPORTED",
              s"shared native continuous assignment at line ${index + 1} is nested inside a procedural scope"
            )
          }
          val target = statement.group(1)
          val rhsNames = continuousAssignmentSourceTokens(
            statement.group(3),
            target
          )
          val targetOwners = plans.filter { plan =>
            plan.assignmentEvidence.exists(_.target == target)
          }
          def sourceProvenOwners(
              candidates: Vector[BlockPlan],
              sourceNamesOf: BlockPlan => Set[String]
          ): Vector[BlockPlan] = {
            val sourceNamesByOwner = candidates.map { plan =>
              plan.block -> (sourceNamesOf(plan) - target)
            }.toMap
            val sourceFrequency = mutable.LinkedHashMap
              .empty[String, Int]
              .withDefaultValue(0)
            sourceNamesByOwner.values.foreach { names =>
              names.foreach { name =>
                sourceFrequency(name) = sourceFrequency(name) + 1
              }
            }
            candidates.filter { plan =>
              sourceNamesByOwner(plan.block).exists { name =>
                sourceFrequency(name) == 1 && rhsNames(name)
              }
            }
          }
          val targetEvidenceOwners = sourceProvenOwners(
            targetOwners,
            _.assignmentEvidence
              .filter(_.target == target)
              .flatMap(_.sourceNames)
              .toSet
          )
          val directSourceEvidenceOwners =
            sourceProvenOwners(plans, _.directSourceNames)
          val exactReferenceOwners = plans.filter { plan =>
            plan.directSourceNames.exists(rhsNames)
          }
          val targetDeclarationOwners = plans.filter { plan =>
            plan.directSourceNames(target)
          }
          val pathCandidates =
            if (targetEvidenceOwners.nonEmpty) targetEvidenceOwners
            else if (directSourceEvidenceOwners.nonEmpty)
              directSourceEvidenceOwners
            else if (exactReferenceOwners.nonEmpty) exactReferenceOwners
            else targetDeclarationOwners
          val evidenceOwners =
            if (targetEvidenceOwners.nonEmpty) targetEvidenceOwners
            else directSourceEvidenceOwners
          val pathOwner = uniqueMostSpecificOwner(pathCandidates)
          val needsResolution =
            claimed.size > 1 || pathCandidates.nonEmpty
          if (needsResolution) pathOwner.toVector match {
            case Vector(owner) =>
              val ownerPath =
                containmentPathOf(owner.block, containmentPaths)
              val targetDemandOwners = plans.filter { plan =>
                plan.assignmentEvidence.exists { evidence =>
                  evidence.sourceNames(target)
                }
              }
              val undominatedDemandOwners = targetDemandOwners.filterNot { demand =>
                containmentPrefix(
                  ownerPath,
                  containmentPathOf(demand.block, containmentPaths)
                )
              }
              if (undominatedDemandOwners.isEmpty) owners(index) = owner.block
              else {
                val rhsText = statement.group(3)
                val portableWholeDriver =
                  portableWholeContinuousDriver(
                    statement.group(2),
                    rhsText,
                    rhsNames
                  )
                val targetDeclarationLines = lines.zipWithIndex.collect {
                  case (candidate, declarationIndex) if standaloneDeclarationName(candidate).contains(target) =>
                    declarationIndex
                }
                val continuousDriverLines = lines.zipWithIndex.collect {
                  case (candidate, driverIndex)
                      if DirectContinuousAssignment
                        .findFirstMatchIn(stripLineComment(candidate).trim)
                        .exists(_.group(1) == target) =>
                    driverIndex
                }
                val claimantBlocks = claimed.map(_.block).toSet
                val declarationClaimants = targetDeclarationLines match {
                  case Vector(declarationIndex) =>
                    Some(
                      claims
                        .getOrElse(declarationIndex, Vector.empty)
                        .map(_.block)
                        .toSet
                    )
                  case _ => None
                }
                val exactDemandsClaimed =
                  targetDemandOwners.nonEmpty && targetDemandOwners.forall { demand =>
                    claimantBlocks(demand.block)
                  }
                val promotedOwner =
                  leastCommonContainingBlock(
                    claimed.map(_.block),
                    containmentPaths
                  )
                val promotedOwnerDominatesProof = promotedOwner.exists { promoted =>
                  val promotedPath =
                    containmentPathOf(promoted, containmentPaths)
                  (claimed ++ targetDemandOwners).forall { plan =>
                    containmentPrefix(
                      promotedPath,
                      containmentPathOf(plan.block, containmentPaths)
                    )
                  }
                }

                def declarationVisibleAt(
                    name: String,
                    promoted: ParameterizedStructuralBlock
                ): Boolean = {
                  if (parameterNames(name)) return true
                  val declarationLines = lines.zipWithIndex.collect {
                    case (candidate, declarationIndex) if standaloneDeclarationName(candidate).contains(name) =>
                      declarationIndex
                  }
                  val portDeclarationLines = lines.zipWithIndex.collect {
                    case (candidate, declarationIndex) if portDeclarationName(candidate).contains(name) =>
                      declarationIndex
                  }
                  (declarationLines, portDeclarationLines) match {
                    case (Vector(), Vector(portDeclarationIndex)) =>
                      declarationIsScalar(lines(portDeclarationIndex))
                    case (Vector(declarationIndex), Vector()) =>
                      if (
                        proceduralRanges.exists(
                          _.indices.contains(declarationIndex)
                        )
                      ) false
                      else if (!declarationIsScalar(lines(declarationIndex)))
                        false
                      else {
                        val declarationOwners = claims
                          .getOrElse(declarationIndex, Vector.empty)
                          .map(_.block)
                          .distinct
                        if (declarationOwners.isEmpty) true
                        else
                          leastCommonContainingBlock(
                            declarationOwners,
                            containmentPaths
                          ).exists { declarationOwner =>
                            containmentPrefix(
                              containmentPathOf(
                                declarationOwner,
                                containmentPaths
                              ),
                              containmentPathOf(promoted, containmentPaths)
                            )
                          }
                      }
                    case _ => false
                  }
                }

                def sourceProducedAt(
                    name: String,
                    promoted: ParameterizedStructuralBlock
                ): Boolean = {
                  if (intrinsicSourceNames(name)) return true

                  val exactProducers = plans.filter { plan =>
                    plan.assignmentEvidence.exists(_.target == name) ||
                    plan.childOutputActualNames(name)
                  }
                  var moduleScopeProducer = false
                  val directProducers = lines.zipWithIndex.flatMap { case (candidate, producerIndex) =>
                    val normalized = stripLineComment(candidate).trim
                    val producedName =
                      DirectContinuousAssignment
                        .findFirstMatchIn(normalized)
                        .map(_.group(1))
                        .orElse(
                          DirectProceduralAssignment
                            .findFirstMatchIn(normalized)
                            .map(_.group(1))
                        )
                    if (!producedName.contains(name)) Vector.empty
                    else
                      claims.getOrElse(producerIndex, Vector.empty).distinct match {
                        case Vector() =>
                          moduleScopeProducer = true
                          Vector.empty
                        case Vector(producer) => Vector(producer)
                        case _                => Vector.empty
                      }
                  }
                  val promotedPath =
                    containmentPathOf(promoted, containmentPaths)
                  moduleScopeProducer ||
                  (exactProducers ++ directProducers).distinct.exists { producer =>
                    containmentPrefix(
                      containmentPathOf(producer.block, containmentPaths),
                      promotedPath
                    ) &&
                    (producer.block.vecIndices.isEmpty ||
                      (producer.block eq promoted))
                  }
                }

                val rhsDeclarationsVisible = promotedOwner.exists { promoted =>
                  rhsNames.forall(name => declarationVisibleAt(name, promoted))
                }
                val rhsSourcesAvailable = promotedOwner.exists { promoted =>
                  rhsNames.forall(name => sourceProducedAt(name, promoted))
                }
                val declarationMatchesClaimants =
                  declarationClaimants.contains(claimantBlocks)
                val uniqueDriver = continuousDriverLines == Vector(index)
                val targetDeclarationIsScalar =
                  targetDeclarationLines match {
                    case Vector(declarationIndex) =>
                      declarationIsScalar(lines(declarationIndex))
                    case _ => false
                  }
                val promotionProven =
                  portableWholeDriver &&
                    targetDeclarationLines.size == 1 &&
                    targetDeclarationIsScalar &&
                    declarationMatchesClaimants &&
                    uniqueDriver &&
                    exactDemandsClaimed &&
                    promotedOwnerDominatesProof &&
                    rhsDeclarationsVisible &&
                    rhsSourcesAvailable
                if (!promotionProven) {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN",
                    s"native continuous assignment at line ${index + 1} to '$target' is owned below ${undominatedDemandOwners.size} exact consuming blocks, but outward promotion is unproven; portableWholeDriver=$portableWholeDriver standaloneDeclarations=${targetDeclarationLines.size} targetDeclarationIsScalar=$targetDeclarationIsScalar declarationMatchesClaimants=$declarationMatchesClaimants continuousDrivers=${continuousDriverLines.size} exactDemandsClaimed=$exactDemandsClaimed promotedOwner=${promotedOwner.nonEmpty} promotedOwnerDominatesProof=$promotedOwnerDominatesProof rhsDeclarationsVisible=$rhsDeclarationsVisible rhsSourcesAvailable=$rhsSourcesAvailable rhs=[${rhsNames.toVector.sorted
                        .mkString(",")}]"
                  )
                }
                val promoted = promotedOwner.get
                owners(index) = promoted
                promotions(index) = ContinuousAssignmentPromotionProof(
                  target,
                  targetDeclarationLines.head,
                  promoted,
                  rhsNames
                )
              }
            case Vector()
                if targetEvidenceOwners.isEmpty &&
                  targetOwners.isEmpty &&
                  plans.forall(plan => !plan.directSourceNames(target)) &&
                  claimed.size > 1 &&
                  commonModuleScopeContinuousAssignment(
                    index,
                    target,
                    rhsNames,
                    claimed,
                    plans,
                    lines,
                    claims,
                    paths,
                    moduleScopeNames
                  ) =>
              leastCommonContainingBlock(
                claimed.map(_.block),
                containmentPaths
              ) match {
                case Some(owner) => owners(index) = owner
                case None        => moduleScopeLines += index
              }
            case _ =>
              val ownershipSummary = targetOwners.zipWithIndex
                .map { case (plan, ownerIndex) =>
                  val exactSources = plan.assignmentEvidence
                    .filter(_.target == target)
                    .flatMap(_.sourceNames)
                    .distinct
                    .sorted
                    .mkString(",")
                  val directSources = plan.directSourceNames.toVector.sorted
                    .mkString(",")
                  s"$ownerIndex:exact=[$exactSources]:direct=[$directSources]"
                }
                .mkString(";")
              val claimantSummary = claimed.toVector.zipWithIndex
                .map { case (plan, claimantIndex) =>
                  val assignmentSummary = plan.assignmentEvidence
                    .map { evidence =>
                      s"${evidence.target}<-[${evidence.sourceNames.toVector.sorted.mkString(",")}]"
                    }
                    .mkString("|")
                  val rhsOwned = plan.ownedNames
                    .intersect(rhsNames)
                    .toVector
                    .sorted
                    .mkString(",")
                  val location = plan.block.sourceLocation.getOrElse("<unknown>")
                  s"$claimantIndex@$location:assign=[$assignmentSummary]:rhsOwned=[$rhsOwned]"
                }
                .mkString(";")
              val rhsDeclarationSummary = rhsNames.toVector.sorted
                .map { name =>
                  val declarationLines = lines.zipWithIndex.collect {
                    case (line, declarationIndex)
                        if isDeclarationLine(line.trim) &&
                          containsName(line, name) =>
                      val declarationClaimants = planClaimsByLine(plans)
                        .getOrElse(declarationIndex, Vector.empty)
                        .size
                      s"${declarationIndex + 1}:$declarationClaimants"
                  }
                  s"$name=[${declarationLines.mkString(",")}]"
                }
                .mkString(";")
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-CONTINUOUS-ASSIGNMENT-OWNER-UNPROVEN",
                s"native continuous assignment at line ${index + 1} has ${evidenceOwners.size} source-proven owners and ${pathCandidates.size} exact path candidates; one most-specific compatible structural owner is required; target='$target' rhs=[${rhsNames.toVector.sorted
                    .mkString(",")}] targetOwners=${targetOwners.size} evidence={$ownershipSummary} claimants={$claimantSummary} declarations={$rhsDeclarationSummary}"
              )
          }
        }
      }
    }
    ContinuousAssignmentResolution(
      owners.toMap,
      moduleScopeLines.toSet,
      promotions.toMap
    )
  }

  private def commonModuleScopeContinuousAssignment(
      index: Int,
      target: String,
      rhsNames: Set[String],
      claimed: Vector[BlockPlan],
      plans: Vector[BlockPlan],
      lines: Vector[String],
      claims: Map[Int, Vector[BlockPlan]],
      paths: Map[ParameterizedStructuralBlock, Vector[AlternativeStep]],
      moduleScopeNames: Set[String]
  ): Boolean = {
    val claimedBlocks = claimed.map(_.block).toSet
    val pairwiseExclusive = claimed.combinations(2).forall { pair =>
      mutuallyExclusive(
        paths.getOrElse(pair(0).block, Vector.empty),
        paths.getOrElse(pair(1).block, Vector.empty)
      )
    }
    if (!pairwiseExclusive) return false

    def declarationClaims(name: String): Option[Set[ParameterizedStructuralBlock]] = {
      val declarationLines = lines.zipWithIndex.collect {
        case (line, declarationIndex) if isDeclarationLine(line.trim) && containsName(line, name) =>
          declarationIndex
      }
      declarationLines match {
        case Vector(declarationIndex) =>
          Some(claims.getOrElse(declarationIndex, Vector.empty).map(_.block).toSet)
        case Vector() if moduleScopeNames(name) => Some(Set.empty)
        case _                                  => None
      }
    }

    val referencedNames = rhsNames + target
    referencedNames.forall { name =>
      declarationClaims(name).exists { declarationOwners =>
        declarationOwners.isEmpty || declarationOwners == claimedBlocks
      }
    }
  }

  private def planClaimsByLine(
      plans: Vector[BlockPlan]
  ): Map[Int, Vector[BlockPlan]] = {
    val claims = mutable.LinkedHashMap.empty[Int, ArrayBuffer[BlockPlan]]
    plans.foreach { plan =>
      plan.ranges.flatMap(_.indices).distinct.foreach { index =>
        claims.getOrElseUpdate(index, ArrayBuffer.empty) += plan
      }
    }
    claims.map { case (index, values) => index -> values.toVector }.toMap
  }

  private def validateSharedContinuousAssignmentReplay(
      plans: Vector[BlockPlan],
      lines: Vector[String],
      resolution: ContinuousAssignmentResolution
  ): Unit = {
    val claims = planClaimsByLine(plans)
    resolution.owners.toVector.sortBy(_._1).foreach { case (index, expectedOwner) =>
      val actual = claims.getOrElse(index, Vector.empty)
      if (actual.size != 1 || !(actual.head.block eq expectedOwner)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-CONTINUOUS-ASSIGNMENT-REPLAY-MISMATCH",
          s"source-proven continuous assignment at line ${index + 1} has ${actual.size} owners after replay; exactly its proven owner is required"
        )
      }
    }
    resolution.moduleScopeLines.toVector.sorted.foreach { index =>
      val actual = claims.getOrElse(index, Vector.empty)
      if (actual.nonEmpty) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-CONTINUOUS-ASSIGNMENT-MODULE-SCOPE-REPLAY-MISMATCH",
          s"source-proven common continuous assignment at line ${index + 1} retained ${actual.size} structural claimants after replay"
        )
      }
    }
    claims.toVector
      .filter { case (index, values) =>
        values.size > 1 &&
        DirectContinuousAssignment
          .findFirstMatchIn(stripLineComment(lines(index)).trim)
          .nonEmpty &&
        !resolution.owners.contains(index) &&
        !resolution.moduleScopeLines(index)
      }
      .sortBy(_._1)
      .headOption
      .foreach { case (index, values) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-CONTINUOUS-ASSIGNMENT-OWNER-MISSING",
          s"shared native continuous assignment at line ${index + 1} retained ${values.size} owners without a source-proven owner"
        )
      }

  }

  /** Ownership selection above uses exact captured assignment evidence. Native
    * emission can also reference a helper from an instance actual, condition,
    * or other raw module item, so validate lexical dominance again after
    * shared declarations and procedural processes have reached final owners.
    */
  private def validateContinuousAssignmentDominance(
      plans: Vector[BlockPlan],
      lines: Vector[String],
      containmentPaths: Map[
        ParameterizedStructuralBlock,
        Vector[ParameterizedStructuralBlock]
      ],
      resolution: ContinuousAssignmentResolution
  ): Unit = {
    val claims = planClaimsByLine(plans)
    val capturedIndices = plans.flatMap(_.ranges).flatMap(_.indices).toSet
    val lexicalLines =
      verilogReferenceText(lines.mkString("\n")).split("\n", -1).toVector
    if (lexicalLines.size != lines.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN",
        s"native lexical reference scan produced ${lexicalLines.size} lines for ${lines.size} source lines"
      )
    }

    def ownerBlocks(index: Int): Vector[ParameterizedStructuralBlock] =
      claims
        .getOrElse(index, Vector.empty)
        .map(_.block)
        .distinct

    def exactOwner(
        index: Int,
        target: String,
        kind: String
    ): Option[ParameterizedStructuralBlock] = {
      val owners = ownerBlocks(index)
      owners match {
        case Vector()      => None
        case Vector(owner) => Some(owner)
        case _ =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN",
            s"native $kind for continuously driven '$target' at line ${index + 1} has ${owners.size} resolved structural owners; module scope or one exact owner is required"
          )
      }
    }

    def lineDefinesName(line: String, name: String): Boolean = {
      val normalized = stripLineComment(line).trim
      standaloneDeclarationName(normalized).contains(name) ||
      portDeclarationName(normalized).contains(name) ||
      DirectContinuousAssignment
        .findFirstMatchIn(normalized)
        .exists(_.group(1) == name)
    }

    def bodyConsumesName(body: String, name: String): Boolean = {
      val withoutDefinitions = verilogReferenceText(body)
        .split("\n", -1)
        .map { line =>
          val normalized = line.trim
          val isDefinition =
            standaloneDeclarationName(normalized).contains(name) ||
              DirectContinuousAssignment
                .findFirstMatchIn(normalized)
                .exists(_.group(1) == name)
          if (isDefinition) "" else line
        }
        .mkString("\n")
      verilogReferenceNames(withoutDefinitions)(name)
    }

    def validateDriverConsumers(
        target: String,
        driverLocation: String,
        owner: ParameterizedStructuralBlock,
        role: String
    ): Unit = {
      val ownerPath = containmentPathOf(owner, containmentPaths)
      val undominatedConsumers = plans.filter { plan =>
        bodyConsumesName(plan.body, target) &&
        ((owner.vecIndices.nonEmpty && !(plan.block eq owner)) ||
          !containmentPrefix(
            ownerPath,
            containmentPathOf(plan.block, containmentPaths)
          ))
      }
      if (undominatedConsumers.nonEmpty) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN",
          s"$role structural continuous driver for '$target' at $driverLocation does not dominate ${undominatedConsumers.size} resolved bodies that consume it"
        )
      }
      lexicalLines.indices
        .collectFirst {
          case index
              if !capturedIndices(index) &&
                !lineDefinesName(lexicalLines(index), target) &&
                verilogReferenceNames(lexicalLines(index))(target) =>
            index -> lines(index).trim
        }
        .foreach { case (index, line) =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN",
            s"$role structural continuous driver for '$target' at $driverLocation does not dominate module-scope reference at line ${index + 1}: '$line'"
          )
        }
    }

    resolution.promotions.toVector.sortBy(_._1).foreach { case (driverIndex, proof) =>
      val target = proof.target
      DirectContinuousAssignment
        .findFirstMatchIn(stripLineComment(lines(driverIndex)).trim)
        .getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-PROMOTION-PROOF-MISMATCH",
            s"promoted native continuous assignment at line ${driverIndex + 1} is absent after structural process resolution"
          )
        }
      val targetDriverLines = lines.zipWithIndex.collect {
        case (line, index)
            if DirectContinuousAssignment
              .findFirstMatchIn(stripLineComment(line).trim)
              .exists(_.group(1) == target) =>
          index
      }
      if (targetDriverLines != Vector(driverIndex)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN",
          s"promoted captured name '$target' has ${targetDriverLines.size} native continuous drivers after replay; its recorded driver at line ${driverIndex + 1} must be unique"
        )
      }
      val declarationLines = lines.zipWithIndex.collect {
        case (line, index) if standaloneDeclarationName(line).contains(target) =>
          index
      }
      if (declarationLines != Vector(proof.declarationLine)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN",
          s"promoted captured name '$target' has ${declarationLines.size} exact standalone native declarations; only its recorded declaration at line ${proof.declarationLine + 1} is permitted"
        )
      }
      val declarationIndex = proof.declarationLine
      val driverOwner = exactOwner(driverIndex, target, "driver")
      val declarationOwner =
        exactOwner(declarationIndex, target, "declaration")

      if (!driverOwner.exists(_ eq proof.promotedOwner)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-PROMOTION-PROOF-MISMATCH",
          s"promoted continuous driver for '$target' at line ${driverIndex + 1} is not retained by its recorded structural owner"
        )
      }

      val declarationDominatesDriver =
        (declarationOwner, driverOwner) match {
          case (None, _)       => true
          case (Some(_), None) => false
          case (Some(declaration), Some(driver)) =>
            containmentPrefix(
              containmentPathOf(declaration, containmentPaths),
              containmentPathOf(driver, containmentPaths)
            )
        }
      if (!declarationDominatesDriver) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN",
          s"standalone declaration for '$target' at line ${declarationIndex + 1} does not lexically dominate its continuous driver at line ${driverIndex + 1}"
        )
      }

      val owner = driverOwner.get
      validateDriverConsumers(
        target,
        s"line ${driverIndex + 1}",
        owner,
        "promoted"
      )

      val ownerPlans = plans.filter(plan => plan.block eq owner)
      if (ownerPlans.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-PROMOTION-PROOF-MISMATCH",
          s"promoted continuous driver for '$target' at line ${driverIndex + 1} has ${ownerPlans.size} finalized owner plans; exactly one is required"
        )
      }
      val ownerBodyLines = ownerPlans.head.body.split("\n", -1).toVector
      val emittedDeclarations = ownerBodyLines.filter { line =>
        standaloneDeclarationName(line).contains(target)
      }
      if (emittedDeclarations.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-PROMOTION-PROOF-MISMATCH",
          s"promoted continuous driver for '$target' at line ${driverIndex + 1} has ${emittedDeclarations.size} finalized owner-body declarations; exactly one is required"
        )
      }
      val emittedDrivers = ownerBodyLines
        .flatMap { line =>
          DirectContinuousAssignment
            .findFirstMatchIn(stripLineComment(line).trim)
            .filter(_.group(1) == target)
        }
      if (emittedDrivers.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-PROMOTION-PROOF-MISMATCH",
          s"promoted continuous driver for '$target' at line ${driverIndex + 1} has ${emittedDrivers.size} finalized owner-body statements; exactly one is required"
        )
      }
      val statement = emittedDrivers.head

      val rhsNames =
        continuousAssignmentSourceTokens(statement.group(3), target)
      val portableWholeDriver = portableWholeContinuousDriver(
        statement.group(2),
        statement.group(3),
        rhsNames
      )
      if (proof.rhsNames != rhsNames || !portableWholeDriver) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-PROMOTION-PROOF-MISMATCH",
          s"resolved continuous driver for '$target' at line ${driverIndex + 1} no longer satisfies its recorded outward-promotion proof; portableWholeDriver=$portableWholeDriver"
        )
      }
    }

    val promotedTargets = resolution.promotions.values.map(_.target).toSet
    val directDrivers = plans
      .flatMap { plan =>
        plan.body
          .split("\n", -1)
          .flatMap { line =>
            DirectContinuousAssignment
              .findFirstMatchIn(stripLineComment(line).trim)
              .map(statement => statement.group(1) -> plan.block)
          }
      }
      .groupBy(_._1)
      .map { case (target, values) =>
        target -> values.map(_._2).distinct
      }
    directDrivers.toVector.sortBy(_._1).foreach {
      case (target, Vector(owner)) if !promotedTargets(target) =>
        validateDriverConsumers(
          target,
          "its finalized owner body",
          owner,
          "uniquely owned"
        )
      case _ =>
        // Multiple direct drivers are outside this narrow dominance proof.
        ()
    }
  }

  private def removeUniqueProcess(
      body: String,
      process: String,
      range: LineRange,
      sourceLocation: Option[String]
  ): String = {
    val first = body.indexOf(process)
    val second =
      if (first < 0) -1
      else body.indexOf(process, first + process.length)
    if (first < 0 || second >= 0) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-TEXT-NOT-UNIQUE",
        s"native process ${range.start}-${range.end} maps ${if (first < 0) 0 else 2} times into one captured body",
        sourceLocation
      )
    }
    body.substring(0, first) + body.substring(first + process.length)
  }

  private def resolveSharedProceduralProcesses(
      plans: Vector[BlockPlan],
      lines: Vector[String],
      paths: Map[ParameterizedStructuralBlock, Vector[AlternativeStep]],
      containmentPaths: Map[
        ParameterizedStructuralBlock,
        Vector[ParameterizedStructuralBlock]
      ],
      moduleScopeNames: Set[String]
  ): (Vector[BlockPlan], Set[LineRange]) = {
    val claims = mutable.LinkedHashMap.empty[LineRange, ArrayBuffer[BlockPlan]]
    plans.foreach { plan =>
      plan.ranges.foreach { range =>
        claims.getOrElseUpdate(range, ArrayBuffer.empty) += plan
      }
    }

    val current = mutable.LinkedHashMap.empty[
      ParameterizedStructuralBlock,
      BlockPlan
    ]
    plans.foreach(plan => current(plan.block) = plan)
    val sharedProcessRanges = mutable.LinkedHashSet.empty[LineRange]
    val factoredModuleRanges = mutable.LinkedHashSet.empty[LineRange]
    val proceduralRanges = proceduralBlocks(lines, None)

    def claimantPlans(
        range: LineRange,
        claimed: ArrayBuffer[BlockPlan],
        kind: String
    ): Vector[BlockPlan] = {
      val claimants = claimed.toVector.map(plan => current(plan.block))
      val foreignOverlaps = current.values.toVector.flatMap { plan =>
        plan.ranges.collect {
          case candidate if candidate != range && candidate.overlaps(range) =>
            plan -> candidate
        }
      }
      if (foreignOverlaps.nonEmpty) {
        val (_, overlap) = foreignOverlaps.head
        fail(
          s"SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-${kind}-RANGE-OVERLAP",
          s"native ${kind.toLowerCase} range ${range.start}-${range.end} overlaps captured range ${overlap.start}-${overlap.end}; exact ownership is required"
        )
      }
      claimants.foreach { claimant =>
        if (!claimant.ranges.contains(range)) {
          fail(
            s"SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-${kind}-RANGE-MISSING",
            s"native ${kind.toLowerCase} range ${range.start}-${range.end} is absent from one proven claimant",
            claimant.block.sourceLocation
          )
        }
      }
      val dominatedDeclaration =
        kind == "DECLARATION" &&
          leastCommonContainingBlock(
            claimants.map(_.block),
            containmentPaths
          ).nonEmpty
      if (!dominatedDeclaration) {
        claimants.combinations(2).foreach { pair =>
          val left = pair(0)
          val right = pair(1)
          if (
            !mutuallyExclusive(
              paths.getOrElse(left.block, Vector.empty),
              paths.getOrElse(right.block, Vector.empty)
            )
          ) {
            fail(
              s"SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-${kind}-NONEXCLUSIVE",
              s"native ${kind.toLowerCase} range ${range.start}-${range.end} ('${range.indices.map(lines).mkString(" ").trim}') is shared by structural blocks at ${pair
                  .flatMap(_.block.sourceLocation)
                  .mkString(" and ")} that are not proven mutually exclusive",
              left.block.sourceLocation.orElse(right.block.sourceLocation)
            )
          }
        }
      }
      claimants
    }

    def bodyFromRanges(ranges: Vector[LineRange]): String =
      stripCommonIndent(
        ranges.flatMap(_.indices.map(lines)).mkString("\n").trim
      )

    def relocateSharedDeclaration(
        range: LineRange,
        claimants: Vector[BlockPlan],
        owner: Option[ParameterizedStructuralBlock]
    ): Unit = {
      claimants.filterNot(claimant => owner.exists(_ eq claimant.block)).foreach { claimant =>
        val plan = current(claimant.block)
        val retainedRanges = plan.ranges.filterNot(_ == range)
        current(claimant.block) = plan.copy(
          ranges = retainedRanges,
          body = bodyFromRanges(retainedRanges)
        )
      }
      owner.foreach { block =>
        if (!claimants.exists(_.block eq block)) {
          val plan = current(block)
          val retainedRanges = (plan.ranges :+ range).distinct.sortBy(_.start)
          current(block) = plan.copy(
            ranges = retainedRanges,
            body = bodyFromRanges(retainedRanges)
          )
        }
      }
      factoredModuleRanges += range
    }

    claims.toVector
      .filter(_._2.size > 1)
      .sortBy(_._1.start)
      .foreach { case (range, claimed) =>
        val rangeLines = range.indices.map(lines).toVector
        val normalizedLines =
          rangeLines.map(line => stripLineComment(line).trim)
        val standaloneDeclarations =
          normalizedLines.nonEmpty &&
            normalizedLines.forall { line =>
              line.nonEmpty && isStandaloneDeclarationLine(line)
            }
        if (standaloneDeclarations) {
          if (proceduralRanges.exists(_.overlaps(range))) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-DECLARATION-SCOPE-UNSUPPORTED",
              s"native declaration range ${range.start}-${range.end} is nested inside a procedural scope"
            )
          }
          val claimants = claimantPlans(range, claimed, "DECLARATION")
          relocateSharedDeclaration(
            range,
            claimants,
            leastCommonContainingBlock(
              claimants.map(_.block),
              containmentPaths
            )
          )
        }
      }

    claims.toVector
      .filter { case (range, claimed) =>
        claimed.size > 1 && !factoredModuleRanges(range)
      }
      .sortBy(_._1.start)
      .foreach { case (range, claimed) =>
        val initialClaimants = claimed.toVector
        val processLines = range.indices.map(lines).toVector
        val normalized = processLines.map(line => stripLineComment(line).trim)
        if (
          normalized.isEmpty ||
          !normalized.head.startsWith("always @") ||
          !normalized.head.contains("begin") ||
          normalized.last != "end"
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-RANGE-UNSUPPORTED",
            s"captured native range ${range.start}-${range.end} is shared by multiple structural alternatives but is not one simple always block: '${normalized
                .mkString(" | ")}'"
          )
        }

        val completeProcess =
          stripCommonIndent(processLines.mkString("\n").trim)
        val selectedTargetCounts =
          mutable.LinkedHashMap.empty[String, Int].withDefaultValue(0)
        (range.start + 1 until range.end).foreach { index =>
          val stripped = stripLineComment(lines(index)).trim
          DirectProceduralAssignment.findFirstMatchIn(stripped).foreach { statement =>
            if (Option(statement.group(2)).exists(_.trim.nonEmpty)) {
              val target = statement.group(1)
              selectedTargetCounts(target) = selectedTargetCounts(target) + 1
            }
          }
        }
        val selectedTargets = selectedTargetCounts.keys.toVector.sorted
        val expectedTargetCounts =
          selectedTargets.map(target => target -> selectedTargetCounts(target)).toMap
        def capturedTargetCounts(values: Vector[BlockPlan]): Map[String, Int] =
          selectedTargets.map { target =>
            target -> values.map { plan =>
              plan.block.assignments.count { assignment =>
                Option(assignment.finalTarget.getName()).contains(target)
              }
            }.sum
          }.toMap

        val initialBlocks = initialClaimants.map(_.block).toSet
        val missingCandidates = plans.filterNot(plan => initialBlocks(plan.block)).filter { plan =>
          val touchesSelectedTarget = plan.block.assignments.exists { assignment =>
            Option(assignment.finalTarget.getName()).exists(selectedTargetCounts.contains)
          }
          val exclusiveFromInitial = initialClaimants.forall { owner =>
            mutuallyExclusive(
              paths.getOrElse(plan.block, Vector.empty),
              paths.getOrElse(owner.block, Vector.empty)
            )
          }
          val containsProcess = plan.ranges.exists { candidateRange =>
            candidateRange.start <= range.start && candidateRange.end >= range.end
          }
          touchesSelectedTarget && exclusiveFromInitial && containsProcess
        }
        val initialTargetCounts = capturedTargetCounts(initialClaimants)
        val selectedMissing: Vector[BlockPlan] =
          if (initialTargetCounts == expectedTargetCounts) Vector.empty
          else {
            val combinedTargetCounts =
              capturedTargetCounts(initialClaimants ++ missingCandidates)
            if (combinedTargetCounts != expectedTargetCounts) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CLAIMANT-COVERAGE",
                s"shared native process ${range.start}-${range.end} selected assignment counts ${expectedTargetCounts.toVector
                    .sortBy(_._1)
                    .mkString(",")} do not match initial captured counts ${initialTargetCounts.toVector.sortBy(_._1).mkString(",")} or exact mutually-exclusive candidate counts ${combinedTargetCounts.toVector.sortBy(_._1).mkString(",")}"
              )
            }
            missingCandidates
          }
        selectedMissing.foreach { plan =>
          val containingRanges = plan.ranges.filter { candidateRange =>
            candidateRange.start <= range.start && candidateRange.end >= range.end
          }
          if (containingRanges.size != 1) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CONTAINING-RANGE-AMBIGUOUS",
              s"native process ${range.start}-${range.end} has ${containingRanges.size} containing ranges in one recovered claimant",
              plan.block.sourceLocation
            )
          }
          val containing = containingRanges.head
          val splitRanges = plan.ranges
            .flatMap { candidateRange =>
              if (candidateRange != containing) Vector(candidateRange)
              else {
                val before =
                  if (candidateRange.start < range.start)
                    Vector(LineRange(candidateRange.start, range.start - 1))
                  else Vector.empty[LineRange]
                val after =
                  if (range.end < candidateRange.end)
                    Vector(LineRange(range.end + 1, candidateRange.end))
                  else Vector.empty[LineRange]
                before ++ Vector(range) ++ after
              }
            }
            .distinct
            .sortBy(_.start)
          current(plan.block) = plan.copy(ranges = splitRanges)
        }
        val claimants =
          (initialClaimants ++ selectedMissing).map(plan => current(plan.block))

        claimants.combinations(2).foreach { pair =>
          val left = pair(0)
          val right = pair(1)
          val leftPath = paths.getOrElse(left.block, Vector.empty)
          val rightPath = paths.getOrElse(right.block, Vector.empty)
          if (!mutuallyExclusive(leftPath, rightPath)) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-NONEXCLUSIVE",
              s"native process ${range.start}-${range.end} ('${normalized
                  .mkString(" | ")}') is shared by structural blocks at ${pair.flatMap(_.block.sourceLocation).mkString(" and ")} that are not proven mutually exclusive",
              left.block.sourceLocation.orElse(right.block.sourceLocation)
            )
          }
        }

        val rawEvidence = claimants.map { plan =>
          val outsideProcess = removeUniqueProcess(
            plan.body,
            completeProcess,
            range,
            plan.block.sourceLocation
          )
          plan.block -> (plan.ownedNames ++ sanitizedIdentifierTokens(outsideProcess))
        }.toMap
        val frequency = mutable.LinkedHashMap
          .empty[String, Int]
          .withDefaultValue(0)
        rawEvidence.values.foreach { names =>
          names.foreach { name =>
            frequency(name) = frequency(name) + 1
          }
        }
        val uniqueEvidence = rawEvidence.map { case (block, names) =>
          block -> names.filter(name => frequency(name) == 1)
        }
        val exactDeclarationNames = claimants.map { plan =>
          plan.block -> plan.block.declarations.flatMap(value => Option(value.getName()).filter(_.nonEmpty)).toSet
        }.toMap
        val exactControlNames = claimants.map { plan =>
          val names = mutable.LinkedHashSet.empty[String]
          def visit(statement: Statement): Unit = statement match {
            case value: WhenStatement =>
              names ++= expressionNames(value.cond)
              value.foreachStatements(visit)
            case value: SwitchStatement =>
              names ++= expressionNames(value.value)
              value.foreachStatements(visit)
            case _ =>
          }
          plan.block.statements.foreach(visit)
          plan.block -> names.toSet
        }.toMap

        val emittedLiteralCounts = mutable.LinkedHashMap
          .empty[
            (String, BigInt),
            Int
          ]
          .withDefaultValue(0)
        val emittedWholeLiteralIndices = mutable.LinkedHashMap.empty[
          (String, BigInt),
          ArrayBuffer[Int]
        ]
        (range.start + 1 until range.end).foreach { index =>
          DirectProceduralAssignment
            .findFirstMatchIn(stripLineComment(lines(index)).trim)
            .flatMap { statement =>
              verilogLiteral(statement.group(3)).map { literal =>
                (statement.group(1) -> literal) ->
                  !Option(statement.group(2)).exists(_.trim.nonEmpty)
              }
            }
            .foreach { case (key, wholeTarget) =>
              emittedLiteralCounts(key) = emittedLiteralCounts(key) + 1
              if (wholeTarget) {
                emittedWholeLiteralIndices
                  .getOrElseUpdate(key, ArrayBuffer.empty) += index
              }
            }
        }
        val capturedLiteralCounts = mutable.LinkedHashMap
          .empty[
            (String, BigInt),
            Int
          ]
          .withDefaultValue(0)
        claimants.foreach { plan =>
          plan.assignmentEvidence.foreach { evidence =>
            evidence.sourceBooleanLiteral.foreach { literal =>
              val key = evidence.target -> literal
              capturedLiteralCounts(key) = capturedLiteralCounts(key) + 1
            }
          }
        }
        val capturedLiteralOwners = mutable.LinkedHashMap.empty[
          (String, BigInt),
          ArrayBuffer[BlockPlan]
        ]
        claimants.foreach { plan =>
          plan.assignmentEvidence.foreach { evidence =>
            evidence.sourceBooleanLiteral.foreach { literal =>
              capturedLiteralOwners
                .getOrElseUpdate(
                  evidence.target -> literal,
                  ArrayBuffer.empty
                ) += plan
            }
          }
        }
        val literalOwnerByIndex = mutable.LinkedHashMap.empty[Int, BlockPlan]
        capturedLiteralOwners.foreach { case (key, owners) =>
          val emitted = emittedWholeLiteralIndices
            .getOrElse(key, ArrayBuffer.empty)
          if (
            owners.nonEmpty && emitted.size == owners.size &&
            emittedLiteralCounts(key) == owners.size &&
            capturedLiteralCounts(key) == owners.size
          ) {
            // Capture and native emission both preserve statement order. Once
            // the complete whole-Bool literal multiset is exact, equal text may
            // be paired by that retained order without using literal equality
            // as a discovery key for any wider or selected assignment.
            emitted.zip(owners).foreach { case (index, owner) =>
              literalOwnerByIndex(index) = owner
            }
          }
        }

        def directEvidenceOwners(
            statementIndex: Int,
            statement: scala.util.matching.Regex.Match,
            statementText: String
        ): Vector[BlockPlan] = {
          val targetName = statement.group(1)
          val rhsNames = sanitizedIdentifierTokens(statement.group(3))
          val childOutputOwners = claimants.filter { plan =>
            plan.childOutputActualNames.exists(rhsNames.contains)
          }
          val exactOwners = claimants.filter { plan =>
            plan.assignmentEvidence.exists { evidence =>
              evidence.target == targetName &&
              evidence.sourceNames.exists(rhsNames.contains)
            }
          }
          val sourceOwnerCounts = rhsNames.map { name =>
            val count = claimants.count { plan =>
              plan.assignmentEvidence.exists { evidence =>
                evidence.target == targetName && evidence.sourceNames(name)
              }
            }
            name -> count
          }.toMap
          val exactUniqueOwners = exactOwners.filter { plan =>
            plan.assignmentEvidence.exists { evidence =>
              evidence.target == targetName &&
              evidence.sourceNames.exists(name => rhsNames(name) && sourceOwnerCounts.get(name).contains(1))
            }
          }
          val literalOwners = literalOwnerByIndex.get(statementIndex).toVector
          if (childOutputOwners.nonEmpty) childOutputOwners
          else if (exactUniqueOwners.nonEmpty) exactUniqueOwners
          else if (exactOwners.size == 1) exactOwners
          else if (literalOwners.nonEmpty) literalOwners
          else {
            val assignmentNames = sanitizedIdentifierTokens(statementText)
            claimants.filter { plan =>
              uniqueEvidence(plan.block).exists(assignmentNames)
            }
          }
        }

        val nestedConditionalIndices = mutable.LinkedHashSet.empty[Int]
        var conditionalDepth = 0
        (range.start + 1 until range.end).foreach { index =>
          val text = stripLineComment(lines(index))
          if (conditionalDepth > 0) nestedConditionalIndices += index
          val begins = "\\bbegin\\b".r.findAllMatchIn(text).size
          val ends = "\\bend\\b".r.findAllMatchIn(text).size
          conditionalDepth += begins - ends
          if (conditionalDepth < 0) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CONDITIONAL-MALFORMED",
              s"shared native process ${range.start}-${range.end} has an unmatched conditional end at line ${index + 1}"
            )
          }
        }
        if (conditionalDepth != 0) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CONDITIONAL-MALFORMED",
            s"shared native process ${range.start}-${range.end} has unbalanced nested begin/end statements"
          )
        }

        val preclassifiedOwners = mutable.LinkedHashMap.empty[Int, BlockPlan]
        (range.start + 1 until range.end)
          .filterNot(nestedConditionalIndices)
          .foreach { index =>
            val statementText = stripLineComment(lines(index)).trim
            DirectProceduralAssignment.findFirstMatchIn(statementText).foreach { statement =>
              directEvidenceOwners(index, statement, statementText) match {
                case Vector(owner) => preclassifiedOwners(index) = owner
                case Vector()      =>
                case _ =>
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-OWNER-AMBIGUOUS",
                    s"shared native process ${range.start}-${range.end} assignment '$statementText' references multiple branch owners"
                  )
              }
            }
          }

        def preclassifiedCount(
            plan: BlockPlan,
            targetName: String
        ): Int = preclassifiedOwners.count { case (index, owner) =>
          (owner.block eq plan.block) &&
          DirectProceduralAssignment
            .findFirstMatchIn(stripLineComment(lines(index)).trim)
            .exists(_.group(1) == targetName)
        }

        val commonIndices = mutable.LinkedHashSet.empty[Int]
        val ownedIndices = mutable.LinkedHashMap.empty[
          ParameterizedStructuralBlock,
          mutable.LinkedHashSet[Int]
        ]
        claimants.foreach { plan =>
          ownedIndices(plan.block) = mutable.LinkedHashSet.empty[Int]
        }

        def targetCapacity(
            plan: BlockPlan,
            targetName: String
        ): (Int, Int) = {
          val expected = plan.block.assignments.count { assignment =>
            Option(assignment.finalTarget.getName()).contains(targetName)
          }
          val alreadyOwnedResidual = ownedIndices(plan.block).count { ownedIndex =>
            !preclassifiedOwners.contains(ownedIndex) &&
            DirectProceduralAssignment
              .findFirstMatchIn(stripLineComment(lines(ownedIndex)).trim)
              .exists(matched => matched.group(1) == targetName)
          }
          (preclassifiedCount(plan, targetName) + alreadyOwnedResidual) -> expected
        }

        preclassifiedOwners.values.toVector.distinct.foreach { plan =>
          val targets = preclassifiedOwners
            .collect {
              case (index, owner) if owner.block eq plan.block =>
                DirectProceduralAssignment
                  .findFirstMatchIn(stripLineComment(lines(index)).trim)
                  .map(_.group(1))
            }
            .flatten
            .toSet
          targets.foreach { targetName =>
            val (reserved, expected) = targetCapacity(plan, targetName)
            if (reserved > expected) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-OWNER-CAPACITY",
                s"shared native process ${range.start}-${range.end} reserves $reserved assignments to '$targetName' for one exact captured owner with capacity $expected",
                plan.block.sourceLocation
              )
            }
          }
        }

        val lineClaims = planClaimsByLine(plans)

        var processIndex = range.start + 1
        while (processIndex < range.end) {
          val index = processIndex
          val original = lines(index)
          val stripped = stripLineComment(original).trim
          if (stripped.isEmpty) {
            commonIndices += index
            processIndex += 1
          } else {
            DirectProceduralAssignment.findFirstMatchIn(stripped) match {
              case None if stripped.startsWith("if") && stripped.endsWith("begin") =>
                var cursor = index
                var depth = 0
                var sawBegin = false
                var complete = false
                while (cursor < range.end && !complete) {
                  val nested = stripLineComment(lines(cursor))
                  val begins = "\\bbegin\\b".r.findAllMatchIn(nested).size
                  val ends = "\\bend\\b".r.findAllMatchIn(nested).size
                  if (begins != 0) sawBegin = true
                  depth += begins - ends
                  cursor += 1
                  if (sawBegin && depth == 0) complete = true
                }
                if (!complete) {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CONDITIONAL-MALFORMED",
                    s"shared native process ${range.start}-${range.end} contains an unterminated conditional beginning at line ${index + 1}"
                  )
                }
                val conditionalIndices = (index until cursor).toVector
                val conditionalLines = conditionalIndices.map { nestedIndex =>
                  stripLineComment(lines(nestedIndex)).trim
                }
                val unsupported = conditionalLines.drop(1).dropRight(1).find { line =>
                  line.nonEmpty &&
                  DirectProceduralAssignment.findFirstMatchIn(line).isEmpty &&
                  !(line.startsWith("if") && line.endsWith("begin")) &&
                  line != "end"
                }
                if (
                  unsupported.nonEmpty ||
                  conditionalLines.exists(line =>
                    line.startsWith("else") || line.startsWith("case") ||
                      line.startsWith("for") || line.startsWith("while")
                  )
                ) {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CONDITIONAL-SHAPE-UNSUPPORTED",
                    s"shared native process ${range.start}-${range.end} conditional contains unsupported statement '${unsupported
                        .getOrElse(conditionalLines.find(_.startsWith("else")).getOrElse(stripped))}'"
                  )
                }
                val conditionalAssignments = conditionalIndices.flatMap { nestedIndex =>
                  DirectProceduralAssignment
                    .findFirstMatchIn(
                      stripLineComment(lines(nestedIndex)).trim
                    )
                    .map(nestedIndex -> _)
                }
                if (conditionalAssignments.isEmpty) {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CONDITIONAL-EMPTY",
                    s"shared native process ${range.start}-${range.end} conditional contains no assignment"
                  )
                }
                val conditionalNames = sanitizedIdentifierTokens(
                  conditionalIndices.map(lines).mkString("\n")
                )
                val headerNames = sanitizedIdentifierTokens(conditionalLines.head)
                val exactControlOwners = claimants.filter { plan =>
                  exactControlNames(plan.block).exists(headerNames)
                }
                val exactDeclarationOwners = claimants.filter { plan =>
                  exactDeclarationNames(plan.block).exists(headerNames)
                }
                val uniqueConditionalOwners = claimants.filter { plan =>
                  uniqueEvidence(plan.block).exists(conditionalNames)
                }
                val headerOwners =
                  if (exactControlOwners.nonEmpty) exactControlOwners
                  else if (exactDeclarationOwners.nonEmpty) exactDeclarationOwners
                  else uniqueConditionalOwners
                val nestedOwnerSets = conditionalAssignments.map { case (nestedIndex, assignment) =>
                  val statementText =
                    stripLineComment(lines(nestedIndex)).trim
                  directEvidenceOwners(
                    nestedIndex,
                    assignment,
                    statementText
                  )
                }
                nestedOwnerSets.find(_.size > 1).foreach { owners =>
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CONDITIONAL-NESTED-OWNER-AMBIGUOUS",
                    s"shared native process ${range.start}-${range.end} conditional '$stripped' contains an assignment with ${owners.size} branch owners"
                  )
                }
                val nestedOwners = nestedOwnerSets.flatten.distinct
                if (nestedOwners.size > 1) {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CONDITIONAL-NESTED-OWNER-CONFLICT",
                    s"shared native process ${range.start}-${range.end} conditional '$stripped' contains assignments owned by different structural alternatives"
                  )
                }
                val conditionalOwners = nestedOwners match {
                  case Vector(nestedOwner) =>
                    if (headerOwners.nonEmpty && !headerOwners.exists(owner => owner.block eq nestedOwner.block)) {
                      fail(
                        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CONDITIONAL-OWNER-CONFLICT",
                        s"shared native process ${range.start}-${range.end} conditional '$stripped' has header evidence that excludes its exact nested assignment owner"
                      )
                    }
                    Vector(nestedOwner)
                  case Vector() => headerOwners
                }
                conditionalOwners match {
                  case Vector(owner) =>
                    val alreadyOwnedByTarget = conditionalAssignments
                      .map(_._2)
                      .groupBy(_.group(1))
                      .map { case (targetName, statements) =>
                        val (alreadyOwned, expected) =
                          targetCapacity(owner, targetName)
                        targetName -> (alreadyOwned, statements.size, expected)
                      }
                    alreadyOwnedByTarget
                      .collectFirst {
                        case (targetName, (alreadyOwned, nestedCount, expected))
                            if expected < alreadyOwned + nestedCount =>
                          targetName
                      }
                      .foreach { targetName =>
                        fail(
                          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CONDITIONAL-OWNER-CAPACITY",
                          s"shared native process ${range.start}-${range.end} conditional assigns '$targetName' more times than its exact captured owner",
                          owner.block.sourceLocation
                        )
                      }
                    conditionalIndices.foreach(ownedIndices(owner.block) += _)
                  case Vector() =>
                    fail(
                      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CONDITIONAL-UNOWNED",
                      s"shared native process ${range.start}-${range.end} conditional '$stripped' has no branch-unique condition or expression evidence"
                    )
                  case _ =>
                    fail(
                      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CONDITIONAL-OWNER-AMBIGUOUS",
                      s"shared native process ${range.start}-${range.end} conditional '$stripped' references multiple branch owners"
                    )
                }
                processIndex = cursor
              case None =>
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-SHAPE-UNSUPPORTED",
                  s"shared native process ${range.start}-${range.end} contains non-flat statement '$stripped'; claimants are ${claimants
                      .flatMap(_.block.sourceLocation)
                      .mkString(", ")}; process is '${normalized.mkString(" | ")}'"
                )
              case Some(statement) =>
                val targetName = statement.group(1)
                val evidenceOwners: Vector[BlockPlan] =
                  preclassifiedOwners.get(index).toVector
                val residualOwners: Vector[BlockPlan] =
                  if (evidenceOwners.nonEmpty) Vector.empty
                  else
                    claimants.filter { plan =>
                      val (alreadyOwned, expected) =
                        targetCapacity(plan, targetName)
                      expected > alreadyOwned
                    }
                val residualCapacitySummary =
                  claimants.zipWithIndex
                    .map { case (plan, claimantIndex) =>
                      val (alreadyOwned, expected) =
                        targetCapacity(plan, targetName)
                      s"$claimantIndex:$alreadyOwned/$expected"
                    }
                    .mkString(",")
                val owners: Vector[BlockPlan] =
                  if (evidenceOwners.nonEmpty) evidenceOwners
                  else if (residualOwners.size == 1) residualOwners
                  else Vector.empty
                owners match {
                  case Vector(owner) =>
                    ownedIndices(owner.block) += index
                  case Vector() =>
                    if (residualOwners.nonEmpty) {
                      fail(
                        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-ASSIGNMENT-UNOWNED",
                        s"shared native process ${range.start}-${range.end} contains assignment '$stripped' without one branch-unique source or residual-capacity owner; target=$targetName evidenceOwners=${evidenceOwners.size} residualOwners=${residualOwners.size} capacities=$residualCapacitySummary"
                      )
                    } else {
                      val rhsNames =
                        sanitizedIdentifierTokens(statement.group(3))
                      val noDirectTargetSource = plans.forall(plan => !plan.directSourceNames(targetName))
                      val positiveModuleScopeEvidence =
                        commonModuleScopeContinuousAssignment(
                          index,
                          targetName,
                          rhsNames,
                          claimants,
                          plans,
                          lines,
                          lineClaims,
                          paths,
                          moduleScopeNames
                        )
                      val provenCommon =
                        noDirectTargetSource &&
                          positiveModuleScopeEvidence
                      if (!provenCommon) {
                        fail(
                          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-ASSIGNMENT-UNOWNED",
                          s"shared native process ${range.start}-${range.end} contains assignment '$stripped' without one exact structural owner or positive module-scope evidence; target=$targetName capacities=$residualCapacitySummary noDirectTargetSource=$noDirectTargetSource positiveModuleScopeEvidence=$positiveModuleScopeEvidence"
                        )
                      }
                      commonIndices += index
                    }
                  case _ =>
                    fail(
                      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-OWNER-AMBIGUOUS",
                      s"shared native process ${range.start}-${range.end} assignment '$stripped' references multiple branch owners"
                    )
                }
                processIndex += 1
            }
          }
        }

        claimants.foreach { claimant =>
          val owned = ownedIndices(claimant.block)
          if (owned.isEmpty) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-OWNER-EMPTY",
              s"structural block sharing native process ${range.start}-${range.end} owns no emitted assignment; process is '${normalized
                  .mkString(" | ")}'",
              claimant.block.sourceLocation
            )
          }
          val selected =
            (Vector(range.start) ++ commonIndices.toVector ++
              owned.toVector ++ Vector(range.end)).distinct.sorted
          val fragment =
            stripCommonIndent(selected.map(lines).mkString("\n").trim)
          val plan = current(claimant.block)
          current(claimant.block) = plan.copy(
            body = replaceUniqueProcess(
              plan.body,
              completeProcess,
              fragment,
              range,
              claimant.block.sourceLocation
            )
          )
        }
        sharedProcessRanges += range
      }

    (
      plans.map(plan => current(plan.block)),
      sharedProcessRanges.toSet
    )
  }

  private def replaceUniqueProcess(
      body: String,
      process: String,
      replacement: String,
      range: LineRange,
      sourceLocation: Option[String]
  ): String = {
    val first = body.indexOf(process)
    val second =
      if (first < 0) -1
      else body.indexOf(process, first + process.length)
    if (first < 0 || second >= 0) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-TEXT-NOT-UNIQUE",
        s"native process ${range.start}-${range.end} maps ${if (first < 0) 0 else 2} times into one captured body",
        sourceLocation
      )
    }
    body.substring(0, first) + replacement +
      body.substring(first + process.length)
  }

  private def findDeclarationLine(
      lines: Vector[String],
      name: String,
      sourceLocation: Option[String]
  ): LineRange = {
    val candidates = lines.zipWithIndex.collect {
      case (line, index)
          if isDeclarationLine(line.trim) && containsName(line, name) &&
            line.trim.endsWith(";") =>
        index
    }
    if (candidates.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-DECLARATION-NOT-FOUND",
        s"native Verilog contains ${candidates.size} declaration lines for captured signal '$name'",
        sourceLocation
      )
    }
    LineRange(candidates.head, candidates.head)
  }

  private def findMemoryDeclarationLine(
      lines: Vector[String],
      memory: Mem[_],
      name: String,
      sourceLocation: Option[String]
  ): LineRange = {
    val concreteRange =
      ("\\[0\\s*:\\s*" + (memory.wordCount - 1) + "\\]").r
    val unpackedArray =
      ("\\b" + Pattern.quote(name) + "\\b\\s*\\[[^\\]]+\\]\\s*;").r
    val hasSymbolicDepth =
      ExternalParameterizedMemoryRegistry
        .metadataOf(memory)
        .exists(_.depth.parameters.nonEmpty)
    val candidates = lines.zipWithIndex.collect {
      case (line, index)
          if isDeclarationLine(line.trim) && line.contains("reg") &&
            containsName(line, name) &&
            (if (hasSymbolicDepth) unpackedArray.findFirstIn(line).nonEmpty
             else concreteRange.findFirstIn(line).nonEmpty) =>
        index
    }
    if (candidates.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEMORY-DECLARATION-NOT-FOUND",
        s"native Verilog contains ${candidates.size} declaration lines for captured memory '$name'",
        sourceLocation
      )
    }
    LineRange(candidates.head, candidates.head)
  }

  private def findInstanceRange(
      lines: Vector[String],
      definitionName: String,
      instanceName: String,
      sourceLocation: Option[String]
  ): LineRange = {
    val instancePattern =
      ("\\b" + Pattern.quote(instanceName) + "\\s*\\(").r
    val instanceLines = lines.zipWithIndex.collect {
      case (line, index) if instancePattern.findFirstIn(line).nonEmpty => index
    }
    val candidates = instanceLines.flatMap { instanceLine =>
      val lower = math.max(0, instanceLine - 64)
      val start = (instanceLine to lower by -1).find { index =>
        containsName(lines(index), definitionName) &&
        !lines(index).trim.startsWith("module ")
      }
      val end = (instanceLine until lines.size).find(index => lines(index).trim == ");")
      for {
        from <- start
        to <- end
      } yield LineRange(from, to)
    }.distinct
    if (candidates.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-INSTANCE-NOT-FOUND",
        s"native Verilog contains ${candidates.size} instance blocks for '$definitionName $instanceName'",
        sourceLocation
      )
    }
    candidates.head
  }

  private def rewriteSlices(
      body: String,
      slices: Vector[ParameterizedStructure.StructuralSlice]
  ): String =
    slices.foldLeft(body) { case (current, slice) =>
      val sourceName = requiredName(
        slice.source,
        "structural slice source",
        slice.sourceLocation
      )
      val low = slice.offset.default
      val high = low + slice.width.default - 1
      val source = Pattern.quote(sourceName)
      val descending =
        ("\\b" + source + "\\s*\\[\\s*" + high + "\\s*:\\s*" + low + "\\s*\\]").r
      val indexed =
        ("\\b" + source + "\\s*\\[\\s*" + low + "\\s*\\+:\\s*" +
          slice.width.default + "\\s*\\]").r
      val replacement =
        s"$sourceName[${slice.offset.verilog} +: ${slice.width.verilog}]"
      val first = descending.replaceAllIn(current, Matcher.quoteReplacement(replacement))
      val rewritten = indexed.replaceAllIn(first, Matcher.quoteReplacement(replacement))
      if (rewritten == current) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-NOT-FOUND",
          s"native structural body contains no witness slice '$sourceName[$high:$low]'",
          slice.sourceLocation
        )
      }
      rewritten
    }

  private def rewriteVecSelections(
      body: String,
      selections: Vector[ParameterizedStructure.StructuralVecIndex],
      sourceLocation: Option[String]
  ): String = {
    if (selections.isEmpty) return body
    val selectors = selections
      .map(_.index)
      .foldLeft(
        Vector.empty[ElaborationIntegerExpression]
      ) {
        case (known, selector) if known.exists(ElabInt.equivalentExpression(_, selector)) =>
          known
        case (known, selector) => known :+ selector
      }
    if (selectors.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-SELECTOR-CONFLICT",
        "one structural body uses multiple distinct symbolic Vec selectors",
        sourceLocation
      )
    }
    val selector = selectors.head
    val minimum = selector.minimum.toInt
    val maximum = selector.maximum.toInt
    val default = selector.default.toInt

    val witnessMappings = selections.flatMap { selection =>
      val selectedLeaves = selection.selected.flatten.toVector
      val witnessLeaves = selection.vector.vec(default).asInstanceOf[Data].flatten.toVector
      if (
        selectedLeaves.size != witnessLeaves.size ||
        !selectedLeaves.zip(witnessLeaves).forall { case (left, right) => left eq right }
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-WITNESS-MISMATCH",
          s"recorded Vec witness $default does not match the selected native element",
          selection.sourceLocation
        )
      }
      selectedLeaves.map { leaf =>
        requiredName(leaf, "Vec witness leaf", selection.sourceLocation)
      }
    }.distinct
    witnessMappings.foreach { name =>
      if (!containsName(body, name)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-REFERENCE-NOT-FOUND",
          s"native structural body contains no reference to Vec witness leaf '$name'",
          sourceLocation
        )
      }
    }

    val branches = (minimum to maximum)
      .map { value =>
        var branchBody = body
        selections.foreach { selection =>
          val from = selection.selected.flatten.toVector
          val to = selection.vector.vec(value).asInstanceOf[Data].flatten.toVector
          if (from.size != to.size) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-LAYOUT-MISMATCH",
              s"Vec element $value has a different flattened layout from its witness",
              selection.sourceLocation
            )
          }
          from.zip(to).foreach { case (sourceLeaf, targetLeaf) =>
            val sourceName = requiredName(
              sourceLeaf,
              "Vec witness leaf",
              selection.sourceLocation
            )
            val targetName = requiredName(
              targetLeaf,
              s"Vec element $value leaf",
              selection.sourceLocation
            )
            branchBody = replaceName(branchBody, sourceName, targetName)
          }
        }
        s"${value}: begin : g_vec_${value}\n${indent(branchBody, 2)}\nend"
      }
      .mkString("\n")
    s"case (${selector.verilog})\n${indent(branches, 1)}\n  default: begin : g_vec_default\n  end\nendcase"
  }

  private def renderRegion(
      region: ParameterizedStructure.StructuralRegion,
      plans: Map[ParameterizedStructuralBlock, BlockPlan]
  ): String = {
    val declarations = generateIndices(region).map(name => s"  genvar $name;")
    (
      declarations ++
        Vector(
          "  generate",
          renderNestedRegion(region, plans, level = 2),
          "  endgenerate"
        )
    ).mkString("\n")
  }

  private def generateIndices(
      region: ParameterizedStructure.StructuralRegion
  ): Vector[String] = {
    val current = region match {
      case value: ParameterizedStructure.StructuralFor => Vector(value.indexName)
      case _                                           => Vector.empty[String]
    }
    current ++ region.blocks
      .flatMap(_.regions)
      .flatMap(nested => generateIndices(nested))
  }

  private def renderNestedRegion(
      region: ParameterizedStructure.StructuralRegion,
      plans: Map[ParameterizedStructuralBlock, BlockPlan],
      level: Int
  ): String = region match {
    case value: ParameterizedStructure.StructuralFor =>
      renderFor(value, plans, level)
    case value: ParameterizedStructure.StructuralIf =>
      renderIf(value, plans, level, includePrefix = true)
    case value: ParameterizedStructure.StructuralCase =>
      renderCase(value, plans, level)
  }

  private def renderFor(
      value: ParameterizedStructure.StructuralFor,
      plans: Map[ParameterizedStructuralBlock, BlockPlan],
      level: Int
  ): String = {
    val prefix = "  " * level
    s"${prefix}for (${value.indexName} = 0; ${value.indexName} < ${value.count.verilog}; " +
      s"${value.indexName} = ${value.indexName} + 1) begin : ${value.label}\n" +
      renderBlock(value.body, plans, level + 1) + "\n" +
      s"${prefix}end"
  }

  private def renderIf(
      value: ParameterizedStructure.StructuralIf,
      plans: Map[ParameterizedStructuralBlock, BlockPlan],
      level: Int,
      includePrefix: Boolean
  ): String = {
    val prefix = "  " * level
    val startPrefix = if (includePrefix) prefix else ""
    val start =
      s"${startPrefix}if (${value.condition.verilog}) begin : ${value.whenTrueLabel}\n" +
        renderBlock(value.whenTrue, plans, level + 1) + "\n" +
        s"${prefix}end else "
    chainedElseIf(value, plans) match {
      case Some(next) =>
        start + renderIf(next, plans, level, includePrefix = false)
      case None =>
        start + s"begin : ${value.whenFalseLabel}\n" +
          renderBlock(value.whenFalse, plans, level + 1) + "\n" +
          s"${prefix}end"
    }
  }

  private def chainedElseIf(
      value: ParameterizedStructure.StructuralIf,
      plans: Map[ParameterizedStructuralBlock, BlockPlan]
  ): Option[ParameterizedStructure.StructuralIf] = {
    if (!value.whenFalseLabel.startsWith("morphhdl_else_if_")) return None
    val block = value.whenFalse
    if (plans(block).body.trim.isEmpty && block.regions.size == 1) {
      block.regions.head match {
        case nested: ParameterizedStructure.StructuralIf => Some(nested)
        case _ =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-ELSE-IF-CONTINUATION-INVALID",
            "marked else-if continuation is not a structural if",
            value.sourceLocation
          )
      }
    } else {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-ELSE-IF-CONTINUATION-INVALID",
        "marked else-if continuation contains direct hardware or multiple nested regions",
        value.sourceLocation
      )
    }
  }

  private def renderCase(
      value: ParameterizedStructure.StructuralCase,
      plans: Map[ParameterizedStructuralBlock, BlockPlan],
      level: Int
  ): String = {
    val prefix = "  " * level
    val choices = value.choices
      .map { choice =>
        s"${prefix}  ${choice.value}: begin : ${choice.label}\n" +
          renderBlock(choice.body, plans, level + 2) + "\n" +
          s"${prefix}  end"
      }
      .mkString("\n")
    s"${prefix}case (${value.selector.verilog})\n" +
      choices + "\n" +
      s"${prefix}  default: begin : ${value.defaultLabel}\n" +
      renderBlock(value.defaultBody, plans, level + 2) + "\n" +
      s"${prefix}  end\n" +
      s"${prefix}endcase"
  }

  private def renderBlock(
      block: ParameterizedStructuralBlock,
      plans: Map[ParameterizedStructuralBlock, BlockPlan],
      level: Int
  ): String = {
    val direct = Option(plans(block).body)
      .filter(_.trim.nonEmpty)
      .map(value => indent(value, level))
      .toVector
    val nested = block.regions.map(region => renderNestedRegion(region, plans, level))
    (direct ++ nested).mkString("\n")
  }

  private def ensureParameterHeader(
      definitionName: String,
      lines: Vector[String],
      parameters: Vector[ElaborationIntegerParameter]
  ): Vector[String] = {
    if (parameters.isEmpty) return lines
    val modulePattern =
      ("^module\\s+" + Pattern.quote(definitionName) + "\\b").r
    val moduleLines = lines.zipWithIndex.collect {
      case (line, index) if modulePattern.findFirstIn(line.trim).nonEmpty => index
    }
    if (moduleLines.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MODULE-HEADER-NOT-FOUND",
        s"native Verilog contains ${moduleLines.size} module headers for '$definitionName'"
      )
    }
    val moduleIndex = moduleLines.head
    val moduleLine = lines(moduleIndex)
    if (!moduleLine.contains("#(")) {
      val plain =
        ("^(\\s*)module\\s+" + Pattern.quote(definitionName) + "\\s*\\(\\s*$").r
      val matched = plain.findFirstMatchIn(moduleLine).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MODULE-HEADER-UNSUPPORTED",
          s"module '$definitionName' does not use the expected portable header form"
        )
      }
      val indentText = matched.group(1)
      val declarations = parameters.zipWithIndex.map { case (parameter, index) =>
        val comma = if (index == parameters.size - 1) "" else ","
        s"${indentText}  parameter integer ${parameter.name} = ${parameter.default}$comma"
      }
      lines.take(moduleIndex) ++
        Vector(s"${indentText}module $definitionName #(") ++
        declarations ++
        Vector(s"${indentText}) (") ++
        lines.drop(moduleIndex + 1)
    } else {
      val close = (moduleIndex + 1 until lines.size)
        .find(index => lines(index).trim == ") (")
        .getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MODULE-HEADER-UNSUPPORTED",
            s"parameterized module '$definitionName' has no closing ') (' header line"
          )
        }
      val existingNames = lines
        .slice(moduleIndex + 1, close)
        .flatMap { line =>
          val pattern = "\\bparameter\\s+integer\\s+([A-Za-z_][A-Za-z0-9_]*)".r
          pattern.findFirstMatchIn(line).map(_.group(1))
        }
        .toSet
      val missing = parameters.filterNot(parameter => existingNames(parameter.name))
      if (missing.isEmpty) lines
      else {
        val existing = lines.slice(moduleIndex + 1, close)
        val withComma =
          if (existing.isEmpty) existing
          else {
            val last = existing.last
            if (last.trim.endsWith(",")) existing
            else existing.dropRight(1) :+ (last + ",")
          }
        val additions = missing.zipWithIndex.map { case (parameter, index) =>
          val comma = if (index == missing.size - 1) "" else ","
          s"  parameter integer ${parameter.name} = ${parameter.default}$comma"
        }
        lines.take(moduleIndex + 1) ++ withComma ++ additions ++ lines.drop(close)
      }
    }
  }

  private def mergeParameters(
      values: Vector[ElaborationIntegerParameter]
  ): Vector[ElaborationIntegerParameter] = {
    val grouped = values.groupBy(_.name)
    grouped
      .collectFirst {
        case (name, declarations) if declarations.distinct.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
          s"parameter '$name' has conflicting width and structural declarations"
        )
      }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private def validateParameters(
      component: Component,
      parameters: Vector[ElaborationIntegerParameter],
      portNames: Set[String],
      pc: PhaseContext
  ): Unit = {
    val signalNames = ArrayBuffer.empty[String]
    component.dslBody.walkDeclarations {
      case baseType: BaseType => Option(baseType.getName()).foreach(signalNames += _)
      case _                  =>
    }
    parameters.foreach { parameter =>
      if (pc.verilogKeywords.contains(parameter.name)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-NAME-RESERVED",
          s"structural parameter '${parameter.name}' is reserved by IEEE 1364"
        )
      }
      if (portNames(parameter.name)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-PORT-COLLISION",
          s"structural parameter '${parameter.name}' collides with a module port"
        )
      }
      if (signalNames.contains(parameter.name)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-SIGNAL-COLLISION",
          s"structural parameter '${parameter.name}' collides with a native signal"
        )
      }
    }
  }

  private val ProceduralAssignmentTarget =
    """^\s*([A-Za-z_][A-Za-z0-9_$]*)(?:\s*\[[^\]]+\])?\s*(?:<=|=(?!=))""".r

  private def proceduralBlocks(
      lines: Vector[String],
      sourceLocation: Option[String]
  ): Vector[LineRange] = {
    val blocks = Vector.newBuilder[LineRange]
    var index = 0
    while (index < lines.size) {
      val trimmed = stripLineComment(lines(index)).trim
      if (
        trimmed.startsWith("always @") || trimmed == "initial" ||
        trimmed.startsWith("initial ")
      ) {
        var cursor = index
        var depth = 0
        var sawBegin = false
        var complete = false
        while (cursor < lines.size && !complete) {
          val line = stripLineComment(lines(cursor))
          val begins = "\\bbegin\\b".r.findAllMatchIn(line).size
          val ends = "\\bend\\b".r.findAllMatchIn(line).size
          if (begins != 0) sawBegin = true
          depth += begins - ends
          if (sawBegin) complete = depth == 0
          else complete = line.trim.endsWith(";")
          cursor += 1
        }
        if (!complete) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-MALFORMED",
            s"native Verilog contains an unterminated procedural block at line ${index + 1}",
            sourceLocation
          )
        }
        blocks += LineRange(index, cursor - 1)
        index = cursor
      } else index += 1
    }
    blocks.result()
  }

  private def proceduralAssignmentTargets(value: String): Vector[String] =
    value
      .split("\n", -1)
      .toVector
      .flatMap { line =>
        ProceduralAssignmentTarget
          .findFirstMatchIn(stripLineComment(line))
          .map(_.group(1))
      }
      .distinct

  private def stripLineComment(value: String): String = {
    val index = value.indexOf("//")
    if (index < 0) value else value.substring(0, index)
  }

  private def fixedSlicePattern(slice: ParameterizedStructure.StructuralSlice): Pattern = {
    val sourceName = requiredName(slice.source, "structural slice source", slice.sourceLocation)
    val low = slice.offset.default
    val high = low + slice.width.default - 1
    Pattern.compile("\\b" + Pattern.quote(sourceName) + "\\s*\\[\\s*" + high + "\\s*:\\s*" + low + "\\s*\\]")
  }

  private def connectionActualByPort(instanceText: String): Map[String, String] = {
    val pattern = "\\.([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)".r
    pattern
      .findAllMatchIn(instanceText)
      .map { matched =>
        matched.group(1) -> matched.group(2)
      }
      .toMap
  }

  private def connectionActualNames(instanceText: String): Vector[String] = {
    val pattern = "\\.[A-Za-z_][A-Za-z0-9_]*\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)".r
    pattern.findAllMatchIn(instanceText).map(_.group(1)).toVector
  }

  private def mergeRanges(ranges: Vector[LineRange]): Vector[LineRange] = {
    val sorted = ranges.sortBy(_.start)
    val merged = ArrayBuffer.empty[LineRange]
    sorted.foreach { range =>
      merged.lastOption match {
        // Exact module-item boundaries carry structural ownership.  Coalescing
        // adjacent items can hide a shared process inside one claimant's range.
        case Some(previous) if range.start <= previous.end =>
          merged.update(merged.size - 1, LineRange(previous.start, math.max(previous.end, range.end)))
        case _ => merged += range
      }
    }
    merged.toVector
  }

  private def stripCommonIndent(value: String): String = {
    val bodyLines = value.split("\n", -1).toVector
    val nonEmpty = bodyLines.filter(_.trim.nonEmpty)
    if (nonEmpty.isEmpty) value
    else {
      val prefix = nonEmpty.map(_.takeWhile(_.isWhitespace).length).min
      bodyLines
        .map { line =>
          if (line.length >= prefix) line.drop(prefix) else line
        }
        .mkString("\n")
    }
  }

  private def indent(value: String, levels: Int): String = {
    val spaces = "  " * levels
    value.split("\n", -1).map(spaces + _).mkString("\n")
  }

  private val VerilogWords = Set(
    "assign",
    "wire",
    "reg",
    "input",
    "output",
    "inout",
    "module",
    "endmodule",
    "begin",
    "end",
    "generate",
    "endgenerate",
    "if",
    "else",
    "for",
    "case",
    "endcase"
  )

  private def stripLeadingVerilogAttributes(value: String): String = {
    var remaining = value.trim
    while (remaining.startsWith("(*")) {
      val end = remaining.indexOf("*)")
      if (end < 0) return remaining
      remaining = remaining.substring(end + 2).trim
    }
    remaining
  }

  private def isDeclarationLine(value: String): Boolean = {
    val declaration = stripLeadingVerilogAttributes(value)
    declaration.startsWith("wire ") || declaration.startsWith("reg ") ||
    declaration.startsWith("integer ")
  }

  private def isStandaloneDeclarationLine(value: String): Boolean = {
    val declaration = stripLeadingVerilogAttributes(value)
    isDeclarationLine(declaration) &&
    declaration.endsWith(";") &&
    declaration.count(_ == ';') == 1 &&
    !declaration.dropRight(1).contains("=")
  }

  private val StandaloneDeclaration =
    """^(?:wire|reg|integer)\b(?:\s+(?:signed|unsigned))*\s*(?:\[[^\]]+\]\s*)?([A-Za-z_][A-Za-z0-9_$]*)(?:\s*\[[^\]]+\])*\s*;\s*$""".r

  private val PortDeclaration =
    """^(?:input|output|inout)\b(?:\s+(?:wire|reg|signed|unsigned))*\s*(?:\[[^\]]+\]\s*)?([A-Za-z_][A-Za-z0-9_$]*)(?:\s*\[[^\]]+\])*\s*[,;]?\s*$""".r

  /** Return the declarator, excluding identifiers used only in its widths. */
  private def standaloneDeclarationName(value: String): Option[String] = {
    val declaration =
      stripLeadingVerilogAttributes(stripLineComment(value)).trim
    if (!isStandaloneDeclarationLine(declaration)) None
    else
      declaration match {
        case StandaloneDeclaration(name) => Some(name)
        case _                           => None
      }
  }

  private def portDeclarationName(value: String): Option[String] =
    stripLeadingVerilogAttributes(stripLineComment(value)).trim match {
      case PortDeclaration(name) => Some(name)
      case _                     => None
    }

  private def declarationIsScalar(value: String): Boolean = {
    val declaration =
      stripLeadingVerilogAttributes(stripLineComment(value)).trim
    !declaration.contains("[") &&
    (standaloneDeclarationName(declaration).nonEmpty ||
      portDeclarationName(declaration).nonEmpty)
  }

  private def identifiers(value: String): Vector[String] =
    "[A-Za-z_][A-Za-z0-9_]*".r.findAllIn(value).toVector

  private def containsName(value: String, name: String): Boolean =
    ("(?<![A-Za-z0-9_$])" + Pattern.quote(name) + "(?![A-Za-z0-9_$])").r.findFirstIn(value).nonEmpty

  private def replaceName(value: String, from: String, to: String): String =
    ("(?<![A-Za-z0-9_$])" + Pattern.quote(from) + "(?![A-Za-z0-9_$])").r
      .replaceAllIn(value, Matcher.quoteReplacement(to))

  private def requiredName(
      value: Nameable,
      role: String,
      sourceLocation: Option[String]
  ): String =
    Option(value.getName()).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-NAME-MISSING",
        s"$role has no stable native name",
        sourceLocation
      )
    }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
