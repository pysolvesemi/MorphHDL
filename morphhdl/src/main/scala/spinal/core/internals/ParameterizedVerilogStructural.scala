package spinal.core.internals

import java.util.IdentityHashMap
import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core._

/**
  * MorphHDL-owned Increment 33 relocation of validated ordinary SpinalHDL module items into
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
      sourceNames: Set[String]
  )

  private final case class BlockPlan(
      block: ParameterizedStructuralBlock,
      ranges: Vector[LineRange],
      body: String,
      ownedNames: Set[String],
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

    val portNames = component.getOrdredNodeIo.toVector
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

    def plansWithContinuousOwners(
        continuousOwners: Map[Int, ParameterizedStructuralBlock]
    ): Vector[BlockPlan] = allBlocks.map { block =>
      planBlock(
        component,
        block,
        lines,
        portNames,
        parameters.map(_.name).toSet,
        uniquelyOwnedAssignmentTargets,
        continuousOwners,
        canonicalOf
      )
    }
    val preliminaryPlans = plansWithContinuousOwners(Map.empty)
    val continuousOwners =
      sharedContinuousAssignmentOwners(preliminaryPlans, lines)
    val rawPlans =
      if (continuousOwners.isEmpty) preliminaryPlans
      else plansWithContinuousOwners(continuousOwners)
    validateSharedContinuousAssignmentReplay(
      rawPlans,
      lines,
      continuousOwners
    )
    val alternativePaths = structuralAlternativePaths(regions)
    val (resolvedPlans, sharedProcessRanges) =
      resolveSharedProceduralProcesses(rawPlans, lines, alternativePaths)
    validateBranchLocalReferences(resolvedPlans, lines)
    val plans = resolvedPlans.map(finalizePlan)
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
      continuousOwners: Map[Int, ParameterizedStructuralBlock],
      canonicalOf: Component => Component
  ): BlockPlan = {
    val ranges = ArrayBuffer.empty[LineRange]
    val trackedInternalNames = mutable.LinkedHashSet.empty[String]
    val ownedTargetNames = mutable.LinkedHashSet.empty[String]
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
      trackedInternalNames += name
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
      }
    }

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
            emittedActualNames(assignment.source, emittedActualByExpression)
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
            continuousOwners.get(index).forall(_ eq block) &&
            (ownedTargetNames(target) || trackedInternalNames(target) || mentionsSlice)
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
        val capturedTargets = targets.filter(ownedTargetNames)
        if (capturedTargets.nonEmpty) {
          val foreignTargets = targets.filterNot(ownedTargetNames)
          if (foreignTargets.nonEmpty) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-MIXED-OWNERSHIP",
              s"one native process assigns captured targets ${capturedTargets.mkString(", ")} and non-captured targets ${foreignTargets.mkString(", ")}; split the clocked logic before placing it in a symbolic alternative",
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
      childOutputActualNames.toSet,
      assignmentEvidence.toVector
    )
  }

  private def validateBranchLocalReferences(
      plans: Vector[BlockPlan],
      lines: Vector[String]
  ): Unit = {
    val removedIndices = plans.flatMap(_.ranges).flatMap(_.indices).toSet
    val branchLocalNames = plans.flatMap { plan =>
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
    }.distinct.sorted

    branchLocalNames.foreach { name =>
      lines.zipWithIndex.collectFirst {
        case (line, index)
            if !removedIndices(index) &&
              containsName(stripLineComment(line), name) =>
          index -> stripLineComment(line).trim
      }.foreach { case (index, line) =>
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

  private def mutuallyExclusive(
      left: Vector[AlternativeStep],
      right: Vector[AlternativeStep]
  ): Boolean =
    left.exists { leftStep =>
      right.exists { rightStep =>
        (leftStep.region eq rightStep.region) &&
        leftStep.branch != rightStep.branch
      }
    }

  private val DirectProceduralAssignment =
    """^\s*([A-Za-z_][A-Za-z0-9_$]*)(\s*\[[^\]]+\])?\s*(?:<=|=(?!=))\s*(.*?)\s*;\s*$""".r

  private val DirectContinuousAssignment =
    """^\s*assign\s+([A-Za-z_][A-Za-z0-9_$]*)(\s*\[[^\]]+\])?\s*=\s*(.*?)\s*;\s*$""".r

  private val VerilogIdentifier =
    "[A-Za-z_][A-Za-z0-9_$]*".r

  private val VerilogStringLiteral =
    "\"(?:\\\\.|[^\"\\\\])*\"".r

  private val VerilogBasedLiteral =
    "(?i)(?:[0-9][0-9_]*)?'s?[bodh][0-9a-f_xz?]+".r

  private val VerilogUnbasedLiteral =
    "(?i)'[01xz]".r

  private val VerilogSystemIdentifier =
    "\\$([A-Za-z_][A-Za-z0-9_$]*)".r

  private val VerilogCallIdentifier =
    "([A-Za-z_][A-Za-z0-9_$]*)\\s*\\(".r

  private val VerilogHierarchicalReference =
    "[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)+".r

  private def identifierTokens(value: String): Set[String] =
    VerilogIdentifier.findAllIn(value).filterNot(VerilogWords).toSet

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

  private def sharedContinuousAssignmentOwners(
      plans: Vector[BlockPlan],
      lines: Vector[String]
  ): Map[Int, ParameterizedStructuralBlock] = {
    val claims = planClaimsByLine(plans)
    val proceduralRanges = proceduralBlocks(lines, None)
    val owners = mutable.LinkedHashMap.empty[Int, ParameterizedStructuralBlock]
    claims.toVector
      .filter(_._2.size > 1)
      .sortBy(_._1)
      .foreach { case (index, claimed) =>
        val normalized = stripLineComment(lines(index)).trim
        DirectContinuousAssignment.findFirstMatchIn(normalized).foreach { statement =>
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
          val sourceNamesByOwner = targetOwners.map { plan =>
            plan.block -> plan.assignmentEvidence
              .filter(_.target == target)
              .flatMap(_.sourceNames)
              .filterNot(_ == target)
              .toSet
          }.toMap
          val sourceFrequency = mutable.LinkedHashMap.empty[String, Int]
            .withDefaultValue(0)
          sourceNamesByOwner.values.foreach { names =>
            names.foreach { name =>
              sourceFrequency(name) = sourceFrequency(name) + 1
            }
          }
          val evidenceOwners = targetOwners.filter { plan =>
            sourceNamesByOwner(plan.block).exists { name =>
              sourceFrequency(name) == 1 && rhsNames(name)
            }
          }
          evidenceOwners match {
            case Vector(owner) =>
              if (!claimed.exists(plan => plan.block eq owner.block)) {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-CONTINUOUS-ASSIGNMENT-OWNER-NOT-CLAIMANT",
                  s"shared native continuous assignment at line ${index + 1} has one source-proven owner that did not claim its emitted range",
                  owner.block.sourceLocation
                )
              }
              owners(index) = owner.block
            case _ =>
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-CONTINUOUS-ASSIGNMENT-OWNER-UNPROVEN",
                s"shared native continuous assignment at line ${index + 1} has ${evidenceOwners.size} source-proven owners; exact unique ownership is required"
              )
          }
        }
      }
    owners.toMap
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
      owners: Map[Int, ParameterizedStructuralBlock]
  ): Unit = {
    val claims = planClaimsByLine(plans)
    owners.toVector.sortBy(_._1).foreach { case (index, expectedOwner) =>
      val actual = claims.getOrElse(index, Vector.empty)
      if (actual.size != 1 || !(actual.head.block eq expectedOwner)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-CONTINUOUS-ASSIGNMENT-REPLAY-MISMATCH",
          s"source-proven continuous assignment at line ${index + 1} has ${actual.size} owners after replay; exactly its proven owner is required"
        )
      }
    }
    claims.toVector
      .filter { case (index, values) =>
        values.size > 1 &&
        DirectContinuousAssignment
          .findFirstMatchIn(stripLineComment(lines(index)).trim)
          .nonEmpty &&
        !owners.contains(index)
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
      paths: Map[ParameterizedStructuralBlock, Vector[AlternativeStep]]
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
            s"native ${kind.toLowerCase} range ${range.start}-${range.end} is shared by structural blocks that are not proven mutually exclusive",
            left.block.sourceLocation.orElse(right.block.sourceLocation)
          )
        }
      }
      claimants
    }

    def bodyFromRanges(ranges: Vector[LineRange]): String =
      stripCommonIndent(
        ranges.flatMap(_.indices.map(lines)).mkString("\n").trim
      )

    def factorAtModuleScope(
        range: LineRange,
        claimants: Vector[BlockPlan]
    ): Unit = {
      claimants.foreach { claimant =>
        val plan = current(claimant.block)
        val retainedRanges = plan.ranges.filterNot(_ == range)
        current(claimant.block) = plan.copy(
          ranges = retainedRanges,
          body = bodyFromRanges(retainedRanges)
        )
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
          factorAtModuleScope(range, claimants)
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
            s"captured native range ${range.start}-${range.end} is shared by multiple structural alternatives but is not one simple always block: '${normalized.mkString(" | ")}'"
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
                s"shared native process ${range.start}-${range.end} selected assignment counts ${expectedTargetCounts.toVector.sortBy(_._1).mkString(",")} do not match initial captured counts ${initialTargetCounts.toVector.sortBy(_._1).mkString(",")} or exact mutually-exclusive candidate counts ${combinedTargetCounts.toVector.sortBy(_._1).mkString(",")}"
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
          val splitRanges = plan.ranges.flatMap { candidateRange =>
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
          }.distinct.sortBy(_.start)
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
              s"native process ${range.start}-${range.end} is shared by structural blocks that are not proven mutually exclusive",
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
          plan.block -> (plan.ownedNames ++ identifierTokens(outsideProcess))
        }.toMap
        val frequency = mutable.LinkedHashMap.empty[String, Int]
          .withDefaultValue(0)
        rawEvidence.values.foreach { names =>
          names.foreach { name =>
            frequency(name) = frequency(name) + 1
          }
        }
        val uniqueEvidence = rawEvidence.map { case (block, names) =>
          block -> names.filter(name => frequency(name) == 1)
        }

        val commonIndices = mutable.LinkedHashSet.empty[Int]
        val ownedIndices = mutable.LinkedHashMap.empty[
          ParameterizedStructuralBlock,
          mutable.LinkedHashSet[Int]
        ]
        claimants.foreach { plan =>
          ownedIndices(plan.block) = mutable.LinkedHashSet.empty[Int]
        }

        (range.start + 1 until range.end).foreach { index =>
          val original = lines(index)
          val stripped = stripLineComment(original).trim
          if (stripped.isEmpty) commonIndices += index
          else {
            DirectProceduralAssignment.findFirstMatchIn(stripped) match {
              case None =>
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-SHAPE-UNSUPPORTED",
                  s"shared native process ${range.start}-${range.end} contains non-flat statement '$stripped'"
                )
              case Some(statement) =>
                val targetName = statement.group(1)
                val rhsNames = identifierTokens(statement.group(3))
                val childOutputOwners = claimants.filter { plan =>
                  plan.childOutputActualNames.exists(rhsNames.contains)
                }
                val exactOwners = claimants.filter { plan =>
                  plan.assignmentEvidence.exists { evidence =>
                    evidence.target == targetName &&
                    evidence.sourceNames.exists(rhsNames.contains)
                  }
                }
                val evidenceOwners: Vector[BlockPlan] =
                  if (childOutputOwners.nonEmpty) childOutputOwners
                  else if (exactOwners.nonEmpty) exactOwners
                  else {
                    val assignmentNames = identifierTokens(stripped)
                    claimants.filter { plan =>
                      uniqueEvidence(plan.block).exists(name => assignmentNames(name))
                    }
                  }
                val isSelectedTarget =
                  Option(statement.group(2)).exists(_.trim.nonEmpty)
                def targetCapacity(plan: BlockPlan): (Int, Int) = {
                  val expected = plan.block.assignments.count { assignment =>
                    Option(assignment.finalTarget.getName()).contains(targetName)
                  }
                  val alreadyOwned = ownedIndices(plan.block).count { ownedIndex =>
                    DirectProceduralAssignment
                      .findFirstMatchIn(
                        stripLineComment(lines(ownedIndex)).trim
                      )
                      .exists(matched => matched.group(1) == targetName)
                  }
                  alreadyOwned -> expected
                }
                val residualOwners: Vector[BlockPlan] =
                  if (!isSelectedTarget || evidenceOwners.nonEmpty) Vector.empty
                  else
                    claimants.filter { plan =>
                      val (alreadyOwned, expected) = targetCapacity(plan)
                      expected > alreadyOwned
                    }
                val residualCapacitySummary =
                  claimants.zipWithIndex.map { case (plan, claimantIndex) =>
                    val (alreadyOwned, expected) = targetCapacity(plan)
                    s"$claimantIndex:$alreadyOwned/$expected"
                  }.mkString(",")
                val owners: Vector[BlockPlan] =
                  if (evidenceOwners.nonEmpty) evidenceOwners
                  else if (residualOwners.size == 1) residualOwners
                  else Vector.empty
                owners match {
                  case Vector(owner) =>
                    ownedIndices(owner.block) += index
                  case Vector() =>
                    val selectedTarget =
                      Option(statement.group(2)).exists(_.trim.nonEmpty)
                    if (selectedTarget) {
                      fail(
                        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-ASSIGNMENT-UNOWNED",
                        s"shared native process ${range.start}-${range.end} contains selected assignment '$stripped' without branch-unique source evidence; target=$targetName evidenceOwners=${evidenceOwners.size} residualOwners=${residualOwners.size} capacities=$residualCapacitySummary"
                      )
                    }
                    commonIndices += index
                  case _ =>
                    fail(
                      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-OWNER-AMBIGUOUS",
                      s"shared native process ${range.start}-${range.end} assignment '$stripped' references multiple branch owners"
                    )
                }
            }
          }
        }

        claimants.foreach { claimant =>
          val owned = ownedIndices(claimant.block)
          if (owned.isEmpty) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-OWNER-EMPTY",
              s"structural block sharing native process ${range.start}-${range.end} owns no emitted assignment",
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
            line.trim.endsWith(";") => index
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
    val candidates = lines.zipWithIndex.collect {
      case (line, index)
          if isDeclarationLine(line.trim) && line.contains("reg") &&
            containsName(line, name) && concreteRange.findFirstIn(line).nonEmpty =>
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
    val selectors = selections.map(_.index).distinct
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

    val branches = (minimum to maximum).map { value =>
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
    }.mkString("\n")
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
    val choices = value.choices.map { choice =>
      s"${prefix}  ${choice.value}: begin : ${choice.label}\n" +
        renderBlock(choice.body, plans, level + 2) + "\n" +
        s"${prefix}  end"
    }.mkString("\n")
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
    val nested = block.regions.map(region =>
      renderNestedRegion(region, plans, level)
    )
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
      val close = (moduleIndex + 1 until lines.size).find(index => lines(index).trim == ") (")
        .getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MODULE-HEADER-UNSUPPORTED",
            s"parameterized module '$definitionName' has no closing ') (' header line"
          )
        }
      val existingNames = lines.slice(moduleIndex + 1, close).flatMap { line =>
        val pattern = "\\bparameter\\s+integer\\s+([A-Za-z_][A-Za-z0-9_]*)".r
        pattern.findFirstMatchIn(line).map(_.group(1))
      }.toSet
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
    grouped.collectFirst {
      case (name, declarations) if declarations.distinct.size != 1 => name
    }.foreach { name =>
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
    value.split("\n", -1).toVector.flatMap { line =>
      ProceduralAssignmentTarget
        .findFirstMatchIn(stripLineComment(line))
        .map(_.group(1))
    }.distinct

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
    pattern.findAllMatchIn(instanceText).map { matched =>
      matched.group(1) -> matched.group(2)
    }.toMap
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
        case Some(previous) if range.start <= previous.end + 1 =>
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
      bodyLines.map { line =>
        if (line.length >= prefix) line.drop(prefix) else line
      }.mkString("\n")
    }
  }

  private def indent(value: String, levels: Int): String = {
    val spaces = "  " * levels
    value.split("\n", -1).map(spaces + _).mkString("\n")
  }

  private val VerilogWords = Set(
    "assign", "wire", "reg", "input", "output", "inout", "module", "endmodule",
    "begin", "end", "generate", "endgenerate", "if", "else", "for", "case", "endcase"
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
