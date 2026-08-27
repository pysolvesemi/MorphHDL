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

  private final case class ContinuousAssignmentResolution(
      owners: Map[Int, ParameterizedStructuralBlock],
      moduleScopeLines: Set[Int]
  ) {
    def isEmpty: Boolean = owners.isEmpty && moduleScopeLines.isEmpty
  }

  private object ContinuousAssignmentResolution {
    val empty = ContinuousAssignmentResolution(Map.empty, Set.empty)
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

    val capturedNameOwners = mutable.LinkedHashMap.empty[
      String,
      mutable.LinkedHashSet[ParameterizedStructuralBlock]
    ]
    val declarationNameOwners = mutable.LinkedHashMap.empty[
      String,
      mutable.LinkedHashSet[ParameterizedStructuralBlock]
    ]
    def recordCapturedName(
        name: String,
        block: ParameterizedStructuralBlock
    ): Unit =
      capturedNameOwners
        .getOrElseUpdate(
          name,
          mutable.LinkedHashSet.empty[ParameterizedStructuralBlock]
        ) += block

    def recordDeclarationName(
        name: String,
        block: ParameterizedStructuralBlock
    ): Unit = {
      recordCapturedName(name, block)
      declarationNameOwners
        .getOrElseUpdate(
          name,
          mutable.LinkedHashSet.empty[ParameterizedStructuralBlock]
        ) += block
    }

    allBlocks.foreach { block =>
      block.declarations.foreach { declaration =>
        Option(declaration.getName()).filter(_.nonEmpty).foreach { name =>
          recordDeclarationName(name, block)
        }
      }
      block.statements.collect { case port: MemPortStatement => port }.foreach {
        port =>
          Option(port.getName()).filter(_.nonEmpty).foreach { name =>
            recordDeclarationName(name, block)
          }
      }
      block.memories.foreach { memory =>
        Option(memory.getName()).filter(_.nonEmpty).foreach { name =>
          recordDeclarationName(name, block)
        }
      }
      block.assignments.foreach { assignment =>
        Option(assignment.finalTarget.getName()).filter(_.nonEmpty).foreach { name =>
          recordCapturedName(name, block)
        }
      }
    }
    val uniquelyOwnedCapturedTargets = capturedNameOwners.collect {
      case (name, owners) if owners.size == 1 => name
    }.toSet
    val globallyCapturedTargets = capturedNameOwners.keySet.toSet
    val uniquelyOwnedDeclarations = declarationNameOwners.collect {
      case (name, owners) if owners.size == 1 => name -> owners.head
    }.toMap

    val alternativePaths = structuralAlternativePaths(regions)
    val parentBlocks = structuralParentBlocks(regions)
    val replicatedBlocks = structuralReplicatedBlocks(regions)
    def plansWithContinuousResolution(
        continuousResolution: ContinuousAssignmentResolution
    ): Vector[BlockPlan] = allBlocks.map { block =>
      planBlock(
        component,
        block,
        lines,
        portNames,
        parameters.map(_.name).toSet,
        uniquelyOwnedCapturedTargets,
        globallyCapturedTargets,
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
      parentBlocks,
      replicatedBlocks,
      portNames ++ parameters.map(_.name)
    )
    val rawPlans =
      if (continuousResolution.isEmpty) preliminaryPlans
      else plansWithContinuousResolution(continuousResolution)
    validateSharedContinuousAssignmentReplay(
      rawPlans,
      lines,
      continuousResolution
    )
    val continuousDeclarationOwners = continuousResolution.owners.toVector
      .flatMap { case (index, owner) =>
        DirectContinuousAssignment
          .findFirstMatchIn(stripLineComment(lines(index)).trim)
          .map(_.group(1) -> owner)
      }
      .groupBy(_._1)
      .map { case (name, values) =>
        val owners = values.map(_._2).foldLeft(
          Vector.empty[ParameterizedStructuralBlock]
        ) { (known, candidate) =>
          if (known.exists(_ eq candidate)) known else known :+ candidate
        }
        if (owners.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-DECLARATION-OWNER-CONFLICT",
            s"native continuous target '$name' has ${owners.size} structural owners"
          )
        }
        name -> owners.head
      }
    continuousDeclarationOwners.foreach { case (name, owner) =>
      uniquelyOwnedDeclarations.get(name).foreach { existing =>
        if (!(existing eq owner)) {
          val scalarDeclaration =
            ("^(?:wire|reg)\\s+" + Pattern.quote(name) + "\\s*;\\s*$").r
          val declarationCount = lines.count { line =>
            scalarDeclaration
              .findFirstIn(stripLineComment(line).trim)
              .nonEmpty
          }
          val safeAncestorRelocation =
            declarationCount == 1 &&
              sameOrDescendantBlock(existing, owner, parentBlocks) &&
              !replicatedBlocks(existing) && !replicatedBlocks(owner)
          if (!safeAncestorRelocation) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-DECLARATION-OWNER-CONFLICT",
              s"native continuous target '$name' has conflicting captured declaration and dependency owners"
            )
          }
        }
      }
    }
    val resolvedDeclarationOwners =
      uniquelyOwnedDeclarations ++ continuousDeclarationOwners
    val (resolvedPlans, sharedProcessRanges) =
      resolveSharedProceduralProcesses(
        rawPlans,
        lines,
        alternativePaths,
        resolvedDeclarationOwners,
        parentBlocks,
        replicatedBlocks
      )
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
      uniquelyOwnedCapturedTargets: Set[String],
      globallyCapturedTargets: Set[String],
      continuousResolution: ContinuousAssignmentResolution,
      canonicalOf: Component => Component
  ): BlockPlan = {
    val ranges = ArrayBuffer.empty[LineRange]
    val trackedInternalNames = mutable.LinkedHashSet.empty[String]
    val ownedTargetNames = mutable.LinkedHashSet.empty[String]
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
      directSourceNames += name
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
            uniquelyOwnedCapturedTargets(value.group(1))
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
            !continuousResolution.moduleScopeLines(index) &&
            continuousResolution.owners.get(index).forall(_ eq block) &&
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
          val uncapturedTargets =
            targets.filterNot(globallyCapturedTargets)
          if (uncapturedTargets.nonEmpty) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-MIXED-OWNERSHIP",
              s"one native process assigns captured targets ${capturedTargets.mkString(", ")} and non-captured targets ${uncapturedTargets.mkString(", ")}; split the clocked logic before placing it in a symbolic alternative",
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

  private def structuralParentBlocks(
      regions: Vector[ParameterizedStructure.StructuralRegion]
  ): Map[ParameterizedStructuralBlock, Option[ParameterizedStructuralBlock]] = {
    val parents = mutable.LinkedHashMap.empty[
      ParameterizedStructuralBlock,
      Option[ParameterizedStructuralBlock]
    ]

    def visitRegion(
        region: ParameterizedStructure.StructuralRegion,
        parent: Option[ParameterizedStructuralBlock]
    ): Unit = {
      region.blocks.foreach { block =>
        parents(block) = parent
        block.regions.foreach(nested => visitRegion(nested, Some(block)))
      }
    }

    regions.foreach(region => visitRegion(region, None))
    parents.toMap
  }

  private def structuralReplicatedBlocks(
      regions: Vector[ParameterizedStructure.StructuralRegion]
  ): Set[ParameterizedStructuralBlock] = {
    val replicated = mutable.LinkedHashSet.empty[ParameterizedStructuralBlock]

    def visitBlock(
        block: ParameterizedStructuralBlock,
        insideFor: Boolean
    ): Unit = {
      if (insideFor) replicated += block
      block.regions.foreach(region => visitRegion(region, insideFor))
    }

    def visitRegion(
        region: ParameterizedStructure.StructuralRegion,
        insideFor: Boolean
    ): Unit = region match {
      case value: ParameterizedStructure.StructuralFor =>
        visitBlock(value.body, insideFor = true)
      case value: ParameterizedStructure.StructuralIf =>
        visitBlock(value.whenTrue, insideFor)
        visitBlock(value.whenFalse, insideFor)
      case value: ParameterizedStructure.StructuralCase =>
        value.choices.foreach(choice => visitBlock(choice.body, insideFor))
        visitBlock(value.defaultBody, insideFor)
    }

    regions.foreach(region => visitRegion(region, insideFor = false))
    replicated.toSet
  }

  private def sameOrDescendantBlock(
      candidate: ParameterizedStructuralBlock,
      ancestor: ParameterizedStructuralBlock,
      parents: Map[
        ParameterizedStructuralBlock,
        Option[ParameterizedStructuralBlock]
      ]
  ): Boolean = {
    var current = Option(candidate)
    val visited = mutable.LinkedHashSet.empty[ParameterizedStructuralBlock]
    while (current.nonEmpty && !visited(current.get)) {
      val block = current.get
      if (block eq ancestor) return true
      visited += block
      current = parents.getOrElse(block, None)
    }
    false
  }

  private def deepestClaimedCommonAncestor(
      claimants: Vector[BlockPlan],
      parents: Map[
        ParameterizedStructuralBlock,
        Option[ParameterizedStructuralBlock]
      ],
      replicated: Set[ParameterizedStructuralBlock]
  ): Option[BlockPlan] = {
    def depth(block: ParameterizedStructuralBlock): Int = {
      var current = Option(block)
      var value = 0
      val visited = mutable.LinkedHashSet.empty[ParameterizedStructuralBlock]
      while (current.nonEmpty && !visited(current.get)) {
        val next = current.get
        visited += next
        value += 1
        current = parents.getOrElse(next, None)
      }
      value
    }

    val candidates = claimants.filter { candidate =>
      !replicated(candidate.block) && claimants.forall(plan =>
        sameOrDescendantBlock(plan.block, candidate.block, parents)
      )
    }
    if (candidates.isEmpty) None
    else {
      val maximumDepth = candidates.map(plan => depth(plan.block)).max
      val deepest = candidates.filter(plan => depth(plan.block) == maximumDepth)
      if (deepest.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-COMMON-ANCESTOR-AMBIGUOUS",
          s"shared native continuous assignment has ${deepest.size} equally deep claimed structural ancestors"
        )
      }
      Some(deepest.head)
    }
  }

  private def exactlyCoversRootAlternativeTree(
      claimants: Vector[ParameterizedStructuralBlock],
      paths: Map[ParameterizedStructuralBlock, Vector[AlternativeStep]]
  ): Boolean = {
    val claimantPaths = claimants.map(block =>
      paths.getOrElse(block, Vector.empty)
    )

    def sameStep(left: AlternativeStep, right: AlternativeStep): Boolean =
      left.region.eq(right.region) && left.branch == right.branch

    def samePath(
        left: Vector[AlternativeStep],
        right: Vector[AlternativeStep]
    ): Boolean =
      left.size == right.size && left.indices.forall(index =>
        sameStep(left(index), right(index))
      )

    def exactlyCoversFrom(
        candidates: Vector[Vector[AlternativeStep]],
        index: Int
    ): Boolean = {
      if (candidates.isEmpty || candidates.exists(_.size <= index)) false
      else {
        val region = candidates.head(index).region
        if (!candidates.forall(path => path(index).region.eq(region))) false
        else {
          val byBranch = candidates.groupBy(path => path(index).branch)
          val expectedBranches = (0 until region.blocks.size).toSet
          byBranch.keySet == expectedBranches && byBranch.forall {
            case (_, branchPaths) =>
              val completeAtBranch = branchPaths.filter(_.size == index + 1)
              if (completeAtBranch.nonEmpty)
                completeAtBranch.size == 1 && branchPaths.size == 1
              else exactlyCoversFrom(branchPaths, index + 1)
          }
        }
      }
    }

    claimantPaths.nonEmpty &&
    !claimantPaths.combinations(2).exists { pair =>
      samePath(pair(0), pair(1)) || !mutuallyExclusive(pair(0), pair(1))
    } &&
    exactlyCoversFrom(claimantPaths, index = 0)
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

  private val DirectNonblockingProceduralAssignment =
    """^\s*([A-Za-z_][A-Za-z0-9_$]*)(\s*\[[^\]]+\])?\s*<=\s*(.*?)\s*;\s*$""".r

  private val EdgeTriggeredAlwaysHeader =
    """^always\s*@\s*\(\s*(?:pos|neg)edge\s+[A-Za-z_][A-Za-z0-9_$]*(?:\s+or\s+(?:pos|neg)edge\s+[A-Za-z_][A-Za-z0-9_$]*)*\s*\)\s*begin(?:\s*:\s*[A-Za-z_][A-Za-z0-9_$]*)?\s*$""".r

  private final case class ExactProceduralPartition(
      owners: Map[Int, BlockPlan],
      common: Set[Int]
  )

  private val SimpleProceduralIfHeader =
    """^if\s*\(\s*([A-Za-z_][A-Za-z0-9_$]*)\s*\)\s*begin\s*$""".r

  private val StructuredProceduralEndElse =
    """^end\s+else\s+begin\s*$""".r

  private sealed trait StructuredProceduralNode

  private final case class StructuredProceduralAssignment(
      index: Int,
      target: String
  ) extends StructuredProceduralNode

  private final case class StructuredProceduralIf(
      headerIndex: Int,
      whenTrue: Vector[StructuredProceduralNode],
      elseIndex: Option[Int],
      whenFalse: Vector[StructuredProceduralNode],
      endIndex: Int
  ) extends StructuredProceduralNode

  private sealed trait StructuredProceduralTerminator {
    def index: Int
  }

  private final case class StructuredProceduralEnd(index: Int)
      extends StructuredProceduralTerminator

  private final case class StructuredProceduralElse(index: Int)
      extends StructuredProceduralTerminator

  private final case class StructuredProceduralParse(
      nodes: Vector[StructuredProceduralNode],
      nextIndex: Int,
      terminator: Option[StructuredProceduralTerminator]
  )

  private def parseStructuredProceduralSequence(
      lines: Vector[String],
      startIndex: Int,
      endExclusive: Int,
      allowTerminator: Boolean
  ): Either[String, StructuredProceduralParse] = {
    val nodes = Vector.newBuilder[StructuredProceduralNode]
    var index = startIndex

    while (index < endExclusive) {
      val stripped = stripLineComment(lines(index)).trim
      stripped match {
        case StructuredProceduralEndElse() =>
          if (!allowTerminator)
            return Left(s"unexpected 'end else begin' at line ${index + 1}")
          return Right(
            StructuredProceduralParse(
              nodes.result(),
              index + 1,
              Some(StructuredProceduralElse(index))
            )
          )
        case "end" =>
          if (!allowTerminator)
            return Left(s"unexpected 'end' at line ${index + 1}")
          return Right(
            StructuredProceduralParse(
              nodes.result(),
              index + 1,
              Some(StructuredProceduralEnd(index))
            )
          )
        case SimpleProceduralIfHeader(_) =>
          parseStructuredProceduralSequence(
            lines,
            index + 1,
            endExclusive,
            allowTerminator = true
          ) match {
            case Left(detail) => return Left(detail)
            case Right(whenTrueResult) =>
              whenTrueResult.terminator match {
                case Some(StructuredProceduralEnd(endIndex)) =>
                  nodes += StructuredProceduralIf(
                    index,
                    whenTrueResult.nodes,
                    None,
                    Vector.empty,
                    endIndex
                  )
                  index = whenTrueResult.nextIndex
                case Some(StructuredProceduralElse(elseIndex)) =>
                  parseStructuredProceduralSequence(
                    lines,
                    whenTrueResult.nextIndex,
                    endExclusive,
                    allowTerminator = true
                  ) match {
                    case Left(detail) => return Left(detail)
                    case Right(whenFalseResult) =>
                      whenFalseResult.terminator match {
                        case Some(StructuredProceduralEnd(endIndex)) =>
                          nodes += StructuredProceduralIf(
                            index,
                            whenTrueResult.nodes,
                            Some(elseIndex),
                            whenFalseResult.nodes,
                            endIndex
                          )
                          index = whenFalseResult.nextIndex
                        case Some(_: StructuredProceduralElse) =>
                          return Left(
                            s"else body beginning at line ${elseIndex + 1} ends with another else"
                          )
                        case None =>
                          return Left(
                            s"else body beginning at line ${elseIndex + 1} is unterminated"
                          )
                      }
                  }
                case None =>
                  return Left(
                    s"if statement beginning at line ${index + 1} is unterminated"
                  )
              }
          }
        case "" =>
          index += 1
        case DirectProceduralAssignment(target, _, _) =>
          nodes += StructuredProceduralAssignment(index, target)
          index += 1
        case other =>
          return Left(
            s"unsupported procedural statement at line ${index + 1}: '$other'"
          )
      }
    }

    Right(StructuredProceduralParse(nodes.result(), index, None))
  }

  private def renderStructuredProceduralIndexSelection(
      nodes: Vector[StructuredProceduralNode],
      includedAssignments: Set[Int]
  ): Vector[Int] =
    nodes.flatMap {
      case value: StructuredProceduralAssignment =>
        if (includedAssignments(value.index)) Vector(value.index)
        else Vector.empty
      case value: StructuredProceduralIf =>
        val whenTrue = renderStructuredProceduralIndexSelection(
          value.whenTrue,
          includedAssignments
        )
        val whenFalse = renderStructuredProceduralIndexSelection(
          value.whenFalse,
          includedAssignments
        )
        if (whenTrue.isEmpty && whenFalse.isEmpty) Vector.empty
        else {
          val elseBody =
            if (whenFalse.nonEmpty)
              value.elseIndex.toVector ++ whenFalse
            else Vector.empty[Int]
          Vector(value.headerIndex) ++ whenTrue ++ elseBody ++
            Vector(value.endIndex)
        }
    }

  private def structuredProceduralAssignmentNodes(
      nodes: Vector[StructuredProceduralNode]
  ): Vector[StructuredProceduralAssignment] =
    nodes.flatMap {
      case value: StructuredProceduralAssignment => Vector(value)
      case value: StructuredProceduralIf =>
        structuredProceduralAssignmentNodes(value.whenTrue) ++
          structuredProceduralAssignmentNodes(value.whenFalse)
    }

  private def renderStructuredProceduralSelection(
      nodes: Vector[StructuredProceduralNode],
      owner: ParameterizedStructuralBlock,
      ownersByTarget: Map[String, ParameterizedStructuralBlock]
  ): Vector[Int] =
    nodes.flatMap {
      case value: StructuredProceduralAssignment =>
        if (ownersByTarget.get(value.target).exists(_ eq owner))
          Vector(value.index)
        else Vector.empty
      case value: StructuredProceduralIf =>
        val whenTrue = renderStructuredProceduralSelection(
          value.whenTrue,
          owner,
          ownersByTarget
        )
        val whenFalse = renderStructuredProceduralSelection(
          value.whenFalse,
          owner,
          ownersByTarget
        )
        if (whenTrue.isEmpty && whenFalse.isEmpty) Vector.empty
        else {
          val elseBody =
            if (whenFalse.nonEmpty)
              value.elseIndex.toVector ++ whenFalse
            else Vector.empty[Int]
          Vector(value.headerIndex) ++ whenTrue ++ elseBody ++
            Vector(value.endIndex)
        }
    }

  private def emittedAssignmentTarget(
      lines: Vector[String],
      index: Int
  ): Option[String] =
    DirectProceduralAssignment
      .findFirstMatchIn(stripLineComment(lines(index)).trim)
      .map(_.group(1))

  private def exactOwnedTargetCounts(
      partition: ExactProceduralPartition,
      owner: ParameterizedStructuralBlock,
      lines: Vector[String]
  ): Map[String, Int] =
    partition.owners.iterator.collect {
      case (index, plan) if plan.block eq owner =>
        emittedAssignmentTarget(lines, index).getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-IDENTITY-SHAPE-MISMATCH",
            s"exact procedural assignment line ${index + 1} no longer has one direct emitted target"
          )
        }
    }.toVector.groupBy(identity).map { case (target, values) =>
      target -> values.size
    }

  private def structuredAncestorProcessSelections(
      range: LineRange,
      lines: Vector[String],
      claimants: Vector[BlockPlan],
      paths: Map[ParameterizedStructuralBlock, Vector[AlternativeStep]],
      parentBlocks: Map[
        ParameterizedStructuralBlock,
        Option[ParameterizedStructuralBlock]
      ],
      replicatedBlocks: Set[ParameterizedStructuralBlock],
      nativeTargetCounts: Map[String, Int],
      exactPartition: ExactProceduralPartition
  ): Map[ParameterizedStructuralBlock, Vector[Int]] = {
    val processHeader = stripLineComment(lines(range.start)).trim
    if (EdgeTriggeredAlwaysHeader.findFirstIn(processHeader).isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CLOCKED-SHAPE-UNSUPPORTED",
        s"native process ${range.start}-${range.end} is not one exact edge-triggered always block"
      )
    }
    if (claimants.exists(plan => replicatedBlocks(plan.block))) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-REPLICATED-UNSUPPORTED",
        s"native process ${range.start}-${range.end} cannot be split across a replicated structural-for block"
      )
    }

    claimants.combinations(2).foreach { pair =>
      if (
        !mutuallyExclusive(
          paths.getOrElse(pair(0).block, Vector.empty),
          paths.getOrElse(pair(1).block, Vector.empty)
        )
      ) {
        val left = pair(0).block
        val right = pair(1).block
        if (
          !sameOrDescendantBlock(left, right, parentBlocks) &&
          !sameOrDescendantBlock(right, left, parentBlocks)
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-NONEXCLUSIVE",
            s"native process ${range.start}-${range.end} is shared by simultaneously active structural blocks without an ancestor/descendant relationship",
            left.sourceLocation.orElse(right.sourceLocation)
          )
        }
      }
    }

    if (exactPartition.common.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-COMMON-COVERAGE-UNPROVEN",
        s"native clocked process ${range.start}-${range.end} contains assignments without an exact structural owner"
      )
    }
    val capturedCounts = claimants.map { plan =>
      plan.block -> exactOwnedTargetCounts(exactPartition, plan.block, lines)
    }.toMap
    val ownersByTarget = nativeTargetCounts.map { case (target, nativeCount) =>
      val owners = claimants.filter(plan =>
        capturedCounts(plan.block).getOrElse(target, 0) > 0
      )
      if (owners.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-TARGET-OWNER-AMBIGUOUS",
          s"native process ${range.start}-${range.end} target '$target' has ${owners.size} exact structural owners"
        )
      }
      val capturedCount = capturedCounts(owners.head.block)(target)
      if (capturedCount != nativeCount) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-TARGET-COVERAGE",
          s"native process ${range.start}-${range.end} target '$target' has $nativeCount emitted assignments but its exact structural owner captured $capturedCount assignments",
          owners.head.block.sourceLocation
        )
      }
      target -> owners.head.block
    }
    claimants.foreach { plan =>
      if (!ownersByTarget.values.exists(_ eq plan.block)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-OWNER-EMPTY",
          s"structural block sharing native process ${range.start}-${range.end} owns no exactly covered process target",
          plan.block.sourceLocation
        )
      }
    }

    val parsed = parseStructuredProceduralSequence(
      lines,
      range.start + 1,
      range.end,
      allowTerminator = false
    ) match {
      case Left(detail) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-TREE-UNSUPPORTED",
          s"native process ${range.start}-${range.end} cannot be split safely: $detail"
        )
      case Right(value)
          if value.nextIndex == range.end && value.terminator.isEmpty => value
      case Right(value) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-TREE-INCOMPLETE",
          s"native process ${range.start}-${range.end} procedural tree stopped at ${value.nextIndex}"
        )
    }
    val assignmentNodes = structuredProceduralAssignmentNodes(parsed.nodes)
    val parsedCounts = assignmentNodes.map(_.target).groupBy(identity).map {
      case (target, values) => target -> values.size
    }
    if (parsedCounts != nativeTargetCounts) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-TREE-COVERAGE",
        s"native process ${range.start}-${range.end} parsed assignment counts ${parsedCounts.toVector.sorted.mkString(",")} do not match emitted counts ${nativeTargetCounts.toVector.sorted.mkString(",")}"
      )
    }

    val selections = claimants.map { plan =>
      val body = renderStructuredProceduralSelection(
        parsed.nodes,
        plan.block,
        ownersByTarget
      )
      val selectedCounts = body.flatMap(index =>
        emittedAssignmentTarget(lines, index)
      ).groupBy(identity).map { case (target, values) =>
        target -> values.size
      }
      val expectedCounts = capturedCounts(plan.block).filter {
        case (target, _) => ownersByTarget.get(target).exists(_ eq plan.block)
      }
      if (selectedCounts != expectedCounts) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-FRAGMENT-COVERAGE",
          s"native process ${range.start}-${range.end} fragment counts ${selectedCounts.toVector.sorted.mkString(",")} do not match exact captured counts ${expectedCounts.toVector.sorted.mkString(",")}",
          plan.block.sourceLocation
        )
      }
      plan.block -> (Vector(range.start) ++ body ++ Vector(range.end))
    }.toMap

    val selectedAssignments = selections.values.toVector.flatten.flatMap(index =>
      emittedAssignmentTarget(lines, index).map(_ => index)
    )
    val nativeAssignments = assignmentNodes.map(_.index)
    if (
      selectedAssignments.distinct.sorted != nativeAssignments.sorted ||
      selectedAssignments.size != nativeAssignments.size
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-FRAGMENT-PARTITION",
        s"native process ${range.start}-${range.end} assignments are not partitioned exactly once"
      )
    }
    selections
  }

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

  private def sharedContinuousAssignmentResolution(
      plans: Vector[BlockPlan],
      lines: Vector[String],
      paths: Map[ParameterizedStructuralBlock, Vector[AlternativeStep]],
      parentBlocks: Map[
        ParameterizedStructuralBlock,
        Option[ParameterizedStructuralBlock]
      ],
      replicatedBlocks: Set[ParameterizedStructuralBlock],
      moduleScopeNames: Set[String]
  ): ContinuousAssignmentResolution = {
    val claims = planClaimsByLine(plans)
    val proceduralRanges = proceduralBlocks(lines, None)
    val continuousTargetCounts = lines.flatMap { line =>
      DirectContinuousAssignment
        .findFirstMatchIn(stripLineComment(line).trim)
        .map(_.group(1))
    }.groupBy(identity).map { case (target, values) => target -> values.size }
    val proceduralTargets = proceduralRanges.flatMap { range =>
      range.indices.flatMap { index =>
        DirectProceduralAssignment
          .findFirstMatchIn(stripLineComment(lines(index)).trim)
          .map(_.group(1))
      }
    }.toSet
    val owners = mutable.LinkedHashMap.empty[Int, ParameterizedStructuralBlock]
    val moduleScopeLines = mutable.LinkedHashSet.empty[Int]
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
          def sourceProvenOwners(
              candidates: Vector[BlockPlan],
              sourceNamesOf: BlockPlan => Set[String]
          ): Vector[BlockPlan] = {
            val sourceNamesByOwner = candidates.map { plan =>
              plan.block -> (sourceNamesOf(plan) - target)
            }.toMap
            val sourceFrequency = mutable.LinkedHashMap.empty[String, Int]
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
          val evidenceOwners =
            if (targetEvidenceOwners.nonEmpty) targetEvidenceOwners
            else sourceProvenOwners(plans, _.directSourceNames)
          val commonAncestor = deepestClaimedCommonAncestor(
            claimed.toVector,
            parentBlocks,
            replicatedBlocks
          ).filter { _ =>
            val allEvidenceClaimed = evidenceOwners.forall { evidence =>
              claimed.exists(plan => plan.block eq evidence.block)
            }
            evidenceOwners.size <= 1 && allEvidenceClaimed &&
            claimed.forall(plan => !replicatedBlocks(plan.block)) &&
            continuousTargetCounts.getOrElse(target, 0) == 1 &&
            !proceduralTargets(target) && rhsNames.nonEmpty &&
            rhsNames.subsetOf(moduleScopeNames)
          }
          (commonAncestor, evidenceOwners) match {
            case (Some(owner), _) =>
              owners(index) = owner.block
            case (None, Vector(owner)) =>
              if (!claimed.exists(plan => plan.block eq owner.block)) {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-CONTINUOUS-ASSIGNMENT-OWNER-NOT-CLAIMANT",
                  s"shared native continuous assignment at line ${index + 1} has one source-proven owner that did not claim its emitted range",
                  owner.block.sourceLocation
                )
              }
              owners(index) = owner.block
            case (None, Vector())
                if targetOwners.isEmpty &&
                  plans.forall(plan => !plan.directSourceNames(target)) &&
                  commonModuleScopeContinuousAssignment(
                    index,
                    target,
                    rhsNames,
                    claimed.toVector,
                    plans,
                    lines,
                    claims,
                    paths,
                    moduleScopeNames
                  ) =>
              moduleScopeLines += index
            case _ =>
              val ownershipSummary = targetOwners.zipWithIndex.map {
                case (plan, ownerIndex) =>
                  val exactSources = plan.assignmentEvidence
                    .filter(_.target == target)
                    .flatMap(_.sourceNames)
                    .distinct
                    .sorted
                    .mkString(",")
                  val directSources = plan.directSourceNames.toVector.sorted
                    .mkString(",")
                  s"$ownerIndex:exact=[$exactSources]:direct=[$directSources]"
              }.mkString(";")
              val claimantSummary = claimed.toVector.zipWithIndex.map {
                case (plan, claimantIndex) =>
                  val assignmentSummary = plan.assignmentEvidence.map { evidence =>
                    s"${evidence.target}<-[${evidence.sourceNames.toVector.sorted.mkString(",")}]"
                  }.mkString("|")
                  val rhsOwned = plan.ownedNames.intersect(rhsNames).toVector.sorted
                    .mkString(",")
                  val location = plan.block.sourceLocation.getOrElse("<unknown>")
                  s"$claimantIndex@$location:assign=[$assignmentSummary]:rhsOwned=[$rhsOwned]"
              }.mkString(";")
              val rhsDeclarationSummary = rhsNames.toVector.sorted.map { name =>
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
              }.mkString(";")
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-CONTINUOUS-ASSIGNMENT-OWNER-UNPROVEN",
                s"shared native continuous assignment at line ${index + 1} has ${evidenceOwners.size} source-proven owners; exact unique ownership is required; target='$target' rhs=[${rhsNames.toVector.sorted.mkString(",")}] targetOwners=${targetOwners.size} evidence={$ownershipSummary} claimants={$claimantSummary} declarations={$rhsDeclarationSummary}"
              )
          }
        }
      }
    ContinuousAssignmentResolution(owners.toMap, moduleScopeLines.toSet)
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
    if (
      !pairwiseExclusive ||
      !exactlyCoversRootAlternativeTree(claimed.map(_.block), paths)
    ) return false

    def declarationClaims(name: String): Option[Set[ParameterizedStructuralBlock]] = {
      val declarationLines = lines.zipWithIndex.collect {
        case (line, declarationIndex)
            if isDeclarationLine(line.trim) && containsName(line, name) =>
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
      uniqueDeclarationOwners: Map[String, ParameterizedStructuralBlock],
      parentBlocks: Map[
        ParameterizedStructuralBlock,
        Option[ParameterizedStructuralBlock]
      ],
      replicatedBlocks: Set[ParameterizedStructuralBlock]
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
    val deferredDeclarationRanges = mutable.LinkedHashMap.empty[
      LineRange,
      (Vector[ParameterizedStructuralBlock], Vector[String], Vector[String])
    ]
    val proceduralRanges = proceduralBlocks(lines, None)

    def capturedProceduralAssignments(
        block: ParameterizedStructuralBlock
    ): Vector[AssignmentStatement] = {
      val seen = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
      val captured = ArrayBuffer.empty[AssignmentStatement]
      def add(value: AssignmentStatement): Unit = {
        if (!seen.containsKey(value)) {
          seen.put(value, java.lang.Boolean.TRUE)
          captured += value
        }
      }
      block.assignments.foreach(add)
      block.initializations.foreach(add)
      captured.toVector
    }

    val capturedAssignmentOwners =
      new IdentityHashMap[AssignmentStatement, BlockPlan]()
    plans.foreach { plan =>
      capturedProceduralAssignments(plan.block).foreach { assignment =>
        val previous = capturedAssignmentOwners.get(assignment)
        if ((previous ne null) && !(previous.block eq plan.block)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-ASSIGNMENT-CAPTURE-DUPLICATE",
            "one native assignment is captured by multiple structural blocks",
            plan.block.sourceLocation.orElse(previous.block.sourceLocation)
          )
        }
        capturedAssignmentOwners.put(assignment, plan)
      }
    }

    def exactProceduralPartition(
        range: LineRange,
        claimants: Vector[BlockPlan]
    ): Option[ExactProceduralPartition] = {
      val header = stripLineComment(lines(range.start)).trim
      val combinational = header == "always @(*) begin"
      val clocked = EdgeTriggeredAlwaysHeader.findFirstIn(header).nonEmpty
      if (!combinational && !clocked) return None

      val nativeByTarget = mutable.LinkedHashMap.empty[
        String,
        ArrayBuffer[(Int, Boolean)]
      ]
      var incompatible = false
      (range.start + 1 until range.end).foreach { index =>
        val normalized = stripLineComment(lines(index)).trim
        DirectProceduralAssignment.findFirstMatchIn(normalized).foreach {
          statement =>
            val nonblocking = DirectNonblockingProceduralAssignment
              .findFirstIn(normalized)
              .nonEmpty
            if ((clocked && !nonblocking) || (combinational && nonblocking))
              incompatible = true
            nativeByTarget
              .getOrElseUpdate(statement.group(1), ArrayBuffer.empty) +=
              index -> Option(statement.group(2)).exists(_.trim.nonEmpty)
        }
      }
      if (incompatible || nativeByTarget.isEmpty) return None

      val claimantBlocks = claimants.map(_.block).toSet
      val owners = mutable.LinkedHashMap.empty[Int, BlockPlan]
      val common = mutable.LinkedHashSet.empty[Int]
      nativeByTarget.foreach { case (targetName, nativeAssignments) =>
        val roots = new IdentityHashMap[BaseType, java.lang.Boolean]()
        plans.foreach { plan =>
          capturedProceduralAssignments(plan.block).foreach { assignment =>
            if (Option(assignment.finalTarget.getName()).contains(targetName))
              roots.put(assignment.finalTarget, java.lang.Boolean.TRUE)
          }
        }
        if (roots.size() != 1) return None
        val root = roots.keySet().iterator().next()
        val liveInitAssignments = ArrayBuffer.empty[InitAssignmentStatement]
        val liveDataAssignments = ArrayBuffer.empty[DataAssignmentStatement]
        root.foreachStatements {
          case assignment: InitAssignmentStatement
              if (assignment.finalTarget eq root) =>
            liveInitAssignments += assignment
          case assignment: DataAssignmentStatement
              if (assignment.finalTarget eq root) =>
            liveDataAssignments += assignment
          case _ =>
        }
        val liveAssignments: Vector[AssignmentStatement] =
          if (combinational) {
            if (liveInitAssignments.nonEmpty) return None
            liveDataAssignments.toVector
          } else {
            val dataOnly = liveDataAssignments.toVector
            val resetThenData =
              liveInitAssignments.toVector ++ liveDataAssignments.toVector
            if (
              liveInitAssignments.nonEmpty &&
              resetThenData.size == nativeAssignments.size
            ) resetThenData
            else if (dataOnly.size == nativeAssignments.size) dataOnly
            else resetThenData
          }
        if (liveAssignments.size != nativeAssignments.size) return None

        nativeAssignments.zip(liveAssignments).foreach {
          case ((index, nativeIsSlice), assignment) =>
            val capturedIsSlice = !(assignment.target eq assignment.finalTarget)
            if (nativeIsSlice != capturedIsSlice) return None
            val owner = capturedAssignmentOwners.get(assignment)
            if (owner eq null) common += index
            else if (!claimantBlocks(owner.block)) return None
            else owners(index) = owner
        }
      }

      val native = nativeByTarget.valuesIterator.flatten.map(_._1).toSet
      if ((owners.keySet.toSet ++ common.toSet) != native) None
      else Some(ExactProceduralPartition(owners.toMap, common.toSet))
    }

    def exhaustivelyCoverAlternativeTree(
        claimants: Vector[BlockPlan]
    ): Boolean = {
      val claimantPaths = claimants.map(plan =>
        paths.getOrElse(plan.block, Vector.empty)
      )
      def sameStep(left: AlternativeStep, right: AlternativeStep): Boolean =
        left.region.eq(right.region) && left.branch == right.branch

      def samePath(
          left: Vector[AlternativeStep],
          right: Vector[AlternativeStep]
      ): Boolean =
        left.size == right.size && left.indices.forall(index =>
          sameStep(left(index), right(index))
        )

      def exactlyCoversFrom(
          candidates: Vector[Vector[AlternativeStep]],
          index: Int
      ): Boolean = {
        if (candidates.isEmpty || candidates.exists(_.size <= index)) false
        else {
          val region = candidates.head(index).region
          if (!candidates.forall(path => path(index).region.eq(region))) false
          else {
            val byBranch = candidates.groupBy(path => path(index).branch)
            val expectedBranches = (0 until region.blocks.size).toSet
            byBranch.keySet == expectedBranches && byBranch.forall {
              case (_, branchPaths) =>
                val completeAtBranch =
                  branchPaths.filter(_.size == index + 1)
                if (completeAtBranch.nonEmpty)
                  completeAtBranch.size == 1 && branchPaths.size == 1
                else exactlyCoversFrom(branchPaths, index + 1)
            }
          }
        }
      }

      if (
        claimantPaths.isEmpty ||
        claimantPaths.combinations(2).exists { pair =>
          samePath(pair(0), pair(1)) || !mutuallyExclusive(pair(0), pair(1))
        }
      ) false
      // A common assignment has no captured owner, so the claimant frontier
      // must cover the complete top-level alternative tree.  Starting below a
      // shared path prefix would drop the assignment from uncovered siblings.
      else exactlyCoversFrom(claimantPaths, index = 0)
    }

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
      claimants
    }

    def bodyFromRanges(ranges: Vector[LineRange]): String =
      stripCommonIndent(
        ranges.flatMap(_.indices.map(lines)).mkString("\n").trim
      )

    def bodyWithoutRange(
        plan: BlockPlan,
        range: LineRange
    ): String = {
      val exactText = bodyFromRanges(Vector(range))
      val first = plan.body.indexOf(exactText)
      val second =
        if (first < 0) -1
        else plan.body.indexOf(exactText, first + exactText.length)
      if (first < 0 || second >= 0) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-DECLARATION-TEXT-NOT-UNIQUE",
          s"native declaration range ${range.start}-${range.end} maps ${if (first < 0) 0 else 2} times into one captured body",
          plan.block.sourceLocation
        )
      }
      plan.body.substring(0, first) +
        plan.body.substring(first + exactText.length)
    }

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

    def retainInDeclarationOwner(
        range: LineRange,
        owner: ParameterizedStructuralBlock,
        claimants: Vector[BlockPlan]
    ): Unit = {
      claimants.filterNot(_.block eq owner).foreach { claimant =>
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
          val declarationNames =
            normalizedLines.flatMap(standaloneDeclarationName)
          val owners = declarationNames.flatMap(uniqueDeclarationOwners.get)
          val oneAncestorOwner =
            declarationNames.size == normalizedLines.size &&
              owners.size == declarationNames.size &&
              owners.nonEmpty &&
              owners.forall(_ eq owners.head) &&
              claimants.exists(_.block eq owners.head) &&
              claimants.forall(plan =>
                sameOrDescendantBlock(
                  plan.block,
                  owners.head,
                  parentBlocks
                )
              )
          if (oneAncestorOwner)
            retainInDeclarationOwner(range, owners.head, claimants)
          else {
            deferredDeclarationRanges(range) = (
              claimants.map(_.block),
              normalizedLines,
              declarationNames
            )
            factoredModuleRanges += range
          }
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
        val structuredTree =
          if ((range.start + 1 until range.end).exists { index =>
            val normalized = stripLineComment(lines(index)).trim
            normalized.nonEmpty &&
            DirectProceduralAssignment.findFirstMatchIn(normalized).isEmpty
          }) {
            parseStructuredProceduralSequence(
              lines,
              range.start + 1,
              range.end,
              allowTerminator = false
            ) match {
              case Left(detail) =>
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-TREE-UNSUPPORTED",
                  s"native process ${range.start}-${range.end} cannot be pruned safely: $detail"
                )
              case Right(value)
                  if value.nextIndex == range.end && value.terminator.isEmpty =>
                Some(value.nodes)
              case Right(value) =>
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-TREE-INCOMPLETE",
                  s"native process ${range.start}-${range.end} procedural tree stopped at ${value.nextIndex}"
                )
            }
          } else None
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
        val unfilteredClaimants =
          (initialClaimants ++ selectedMissing).map(plan => current(plan.block))

        val processTargetCounts = (range.start + 1 until range.end)
          .flatMap { index =>
            DirectProceduralAssignment
              .findFirstMatchIn(stripLineComment(lines(index)).trim)
              .map(_.group(1))
          }
          .groupBy(identity)
          .map { case (target, values) => target -> values.size }
        val contributingClaimants = unfilteredClaimants.filter { plan =>
          plan.block.assignments.exists { assignment =>
            Option(assignment.finalTarget.getName()).exists(
              processTargetCounts.contains
            )
          }
        }
        val noncontributingClaimants =
          unfilteredClaimants.filterNot(contributingClaimants.contains)
        val capturedContributingCounts = processTargetCounts.keys.map { target =>
          target -> contributingClaimants.map { plan =>
            plan.block.assignments.count { assignment =>
              Option(assignment.finalTarget.getName()).contains(target)
            }
          }.sum
        }.toMap
        val contributingPairwiseExclusive =
          contributingClaimants.combinations(2).forall { pair =>
            mutuallyExclusive(
              paths.getOrElse(pair(0).block, Vector.empty),
              paths.getOrElse(pair(1).block, Vector.empty)
            )
          }
        val canDropNoncontributors =
          noncontributingClaimants.nonEmpty &&
            contributingClaimants.nonEmpty &&
            capturedContributingCounts == processTargetCounts &&
            contributingPairwiseExclusive &&
            noncontributingClaimants.forall(_.ranges.contains(range))
        if (canDropNoncontributors) {
          noncontributingClaimants.foreach { claimant =>
            val plan = current(claimant.block)
            current(claimant.block) = plan.copy(
              ranges = plan.ranges.filterNot(_ == range),
              body = removeUniqueProcess(
                plan.body,
                completeProcess,
                range,
                plan.block.sourceLocation
              )
            )
          }
        }
        val claimants =
          if (canDropNoncontributors) contributingClaimants
          else unfilteredClaimants

        val exactIdentityPartition = exactProceduralPartition(range, claimants)
          .getOrElse {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-IDENTITY-UNPROVEN",
              s"native process ${range.start}-${range.end} shared by structural alternatives cannot be partitioned one-to-one by captured assignment identity"
            )
          }
        if (exactIdentityPartition.common.nonEmpty) {
          if (claimants.exists(plan => replicatedBlocks(plan.block))) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-REPLICATED-COMMON-UNSUPPORTED",
              s"native process ${range.start}-${range.end} contains uncaptured assignments that would be duplicated by a structural-for claimant"
            )
          }
          if (!exhaustivelyCoverAlternativeTree(claimants)) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-COMMON-COVERAGE-UNPROVEN",
              s"native process ${range.start}-${range.end} contains uncaptured assignments but its structural claimants do not exactly cover every leaf of one nested alternative tree"
            )
          }
        }

        val hasNonExclusivePair = claimants.combinations(2).exists { pair =>
          !mutuallyExclusive(
            paths.getOrElse(pair(0).block, Vector.empty),
            paths.getOrElse(pair(1).block, Vector.empty)
          )
        }
        val ancestorSelections =
          if (hasNonExclusivePair) {
            Some(
              structuredAncestorProcessSelections(
                range,
                lines,
                claimants,
                paths,
                parentBlocks,
                replicatedBlocks,
                processTargetCounts,
                exactIdentityPartition
              )
            )
          } else None

        claimants.combinations(2).foreach { pair =>
          val left = pair(0)
          val right = pair(1)
          val leftPath = paths.getOrElse(left.block, Vector.empty)
          val rightPath = paths.getOrElse(right.block, Vector.empty)
          if (
            ancestorSelections.isEmpty &&
            !mutuallyExclusive(leftPath, rightPath)
          ) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-NONEXCLUSIVE",
              s"native process ${range.start}-${range.end} is shared by structural blocks that are not proven mutually exclusive",
              left.block.sourceLocation.orElse(right.block.sourceLocation)
            )
          }
        }

        val commonIndices = mutable.LinkedHashSet.empty[Int]
        val ownedIndices = mutable.LinkedHashMap.empty[
          ParameterizedStructuralBlock,
          mutable.LinkedHashSet[Int]
        ]
        claimants.foreach { plan =>
          ownedIndices(plan.block) = mutable.LinkedHashSet.empty[Int]
        }

        ancestorSelections.foreach { selections =>
          claimants.foreach { plan =>
            selections(plan.block).foreach { index =>
              if (emittedAssignmentTarget(lines, index).nonEmpty)
                ownedIndices(plan.block) += index
            }
          }
        }

        if (ancestorSelections.isEmpty) {
          (range.start + 1 until range.end).foreach { index =>
            if (stripLineComment(lines(index)).trim.isEmpty)
              commonIndices += index
          }
          exactIdentityPartition.common.foreach(commonIndices += _)
          exactIdentityPartition.owners.foreach { case (index, owner) =>
            ownedIndices(owner.block) += index
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
          val selected = ancestorSelections match {
            case Some(selections) => selections(claimant.block)
            case None =>
              val selectedBody = structuredTree match {
                case Some(nodes) =>
                  val includedAssignments =
                    (commonIndices.toSet ++ owned.toSet).filter { index =>
                      DirectProceduralAssignment
                        .findFirstMatchIn(stripLineComment(lines(index)).trim)
                        .nonEmpty
                    }
                  renderStructuredProceduralIndexSelection(
                    nodes,
                    includedAssignments
                  )
                case None =>
                  (commonIndices.toVector ++ owned.toVector).distinct.sorted
              }
              (Vector(range.start) ++ selectedBody ++ Vector(range.end)).distinct
          }
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

    deferredDeclarationRanges.toVector.sortBy(_._1.start).foreach {
      case (range, (claimantBlocks, normalizedLines, declarationNames)) =>
        val claimants = claimantBlocks.map(current)
        val owners = declarationNames.flatMap(uniqueDeclarationOwners.get)
        val exactOwner =
          if (
            declarationNames.size == normalizedLines.size &&
            owners.size == declarationNames.size &&
            owners.nonEmpty &&
            owners.forall(_ eq owners.head) &&
            claimants.exists(_.block eq owners.head)
          ) Some(owners.head)
          else None

        exactOwner match {
          case Some(owner) =>
            val activeClaimants = claimants.filter { plan =>
              if (plan.block eq owner) true
              else {
                val executableText = bodyWithoutRange(plan, range)
                  .split("\\n", -1)
                  .map(stripLineComment)
                  .mkString("\n")
                declarationNames.exists(name =>
                  containsName(executableText, name)
                )
              }
            }
            val escapingClaimants = activeClaimants.filter(plan =>
              !sameOrDescendantBlock(plan.block, owner, parentBlocks)
            )
            if (escapingClaimants.nonEmpty) {
              val enclosingGenerateDepth = lines.take(range.start).foldLeft(0) {
                case (depth, line) =>
                  stripLineComment(line).trim match {
                    case "generate"    => depth + 1
                    case "endgenerate" => math.max(0, depth - 1)
                    case _             => depth
                  }
              }
              val safeModuleScopeFactoring =
                !replicatedBlocks(owner) &&
                  claimants.forall(plan => !replicatedBlocks(plan.block)) &&
                  enclosingGenerateDepth == 0 &&
                  activeClaimants.combinations(2).forall { pair =>
                    val left = pair(0).block
                    val right = pair(1).block
                    mutuallyExclusive(
                      paths.getOrElse(left, Vector.empty),
                      paths.getOrElse(right, Vector.empty)
                    ) ||
                    sameOrDescendantBlock(left, right, parentBlocks) ||
                    sameOrDescendantBlock(right, left, parentBlocks)
                  }
              if (!safeModuleScopeFactoring) {
                val escapingPlan = escapingClaimants.head
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-DECLARATION-OWNER-ESCAPE",
                  s"native declaration range ${range.start}-${range.end} (${normalizedLines.mkString(" | ")}) owned by one structural block is referenced by a non-descendant structural block without exact non-replicated module-scope provenance",
                  owner.sourceLocation.orElse(escapingPlan.block.sourceLocation)
                )
              }
              // The declaration originated at module scope and all escaping
              // consumers are in mutually exclusive, non-replicated regions.
              // Remove every structural claim so the exact declaration stays
              // once at its original module scope.
              claimants.foreach { claimant =>
                val plan = current(claimant.block)
                current(claimant.block) = plan.copy(
                  ranges = plan.ranges.filterNot(_ == range),
                  body = bodyWithoutRange(plan, range)
                )
              }
            } else {
              claimants.filterNot(_.block eq owner).foreach { claimant =>
                val plan = current(claimant.block)
                current(claimant.block) = plan.copy(
                  ranges = plan.ranges.filterNot(_ == range),
                  body = bodyWithoutRange(plan, range)
                )
              }
            }
          case None =>
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-DECLARATION-OWNER-UNPROVEN",
              s"native declaration range ${range.start}-${range.end} (${normalizedLines.mkString(" | ")}) has no single exact captured declaration owner after shared-process resolution"
            )
        }
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
    def compactRange(value: String): String = value.replaceAll("\\s+", "")
    val exactZeroBasedArray =
      ("^reg(?:\\s+signed)?(?:\\s*\\[[^\\[\\]]+\\])?\\s+" +
        Pattern.quote(name) +
        "\\s*\\[\\s*0\\s*:\\s*([^\\[\\]]+)\\s*\\]\\s*;$").r
    val expectedUpperBounds =
      Set((memory.wordCount - 1).toString) ++
        ExternalParameterizedMemoryRegistry
          .metadataOf(memory)
          .filter(_.depth.parameters.nonEmpty)
          .map(metadata => compactRange(metadata.depth.verilog + "-1"))
    val candidates = lines.zipWithIndex.collect {
      case (line, index)
          if {
            val declaration = stripLeadingVerilogAttributes(
              stripLineComment(line).trim
            )
            exactZeroBasedArray
              .findFirstMatchIn(declaration)
              .exists(value => expectedUpperBounds(compactRange(value.group(1))))
          } =>
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

  private val SingleNetDeclaration =
    ("^(?:wire|reg)(?:\\s+signed)?(?:\\s*\\[[^\\[\\]]+\\])?\\s+" +
      "([A-Za-z_][A-Za-z0-9_$]*)(?:\\s*\\[[^\\[\\]]+\\])?\\s*;$").r
  private val SingleIntegerDeclaration =
    "^integer\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*;$".r

  private def standaloneDeclarationName(value: String): Option[String] =
    stripLeadingVerilogAttributes(value) match {
      case SingleNetDeclaration(name)     => Some(name)
      case SingleIntegerDeclaration(name) => Some(name)
      case _                              => None
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
