#!/usr/bin/env python3
from pathlib import Path
import re

path = Path('morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala')
text = path.read_text(encoding='utf-8')


def require_once(fragment: str, label: str) -> None:
    count = text.count(fragment)
    if count != 1:
        raise SystemExit(f'{label}: expected one site, found {count}')


if 'private def resolveSharedProceduralProcesses(' in text:
    print('Increment 52 shared-process resolver is already present')
    raise SystemExit(0)

pattern = re.compile(
    r"  private final case class BlockPlan\(\n"
    r"      block: ParameterizedStructuralBlock,\n"
    r"      ranges: Vector\[LineRange\],\n"
    r"      body: String\n"
    r"  \)\n"
)
replacement = '''  private final case class BlockPlan(
      block: ParameterizedStructuralBlock,
      ranges: Vector[LineRange],
      body: String,
      ownedNames: Set[String],
      processRanges: Set[LineRange]
  )

  private final case class AlternativeStep(
      region: ParameterizedStructure.StructuralRegion,
      branch: Int
  )
'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit(f'BlockPlan extension: expected one site, found {count}')

start_marker = '    val plans = allBlocks.map { block =>\n'
end_marker = '    val removed = allRanges.flatMap(_.indices).toSet\n'
start = text.index(start_marker)
end = text.index(end_marker, start)
planning = '''    val rawPlans = allBlocks.map { block =>
      planBlock(
        component,
        block,
        lines,
        portNames,
        parameters.map(_.name).toSet,
        canonicalOf
      )
    }
    val alternativePaths = structuralAlternativePaths(regions)
    val (resolvedPlans, sharedProcessRanges) =
      resolveSharedProceduralProcesses(rawPlans, lines, alternativePaths)
    val plans = resolvedPlans.map(finalizePlan)
    val allRanges = plans.flatMap(_.ranges)
    allRanges.combinations(2).foreach {
      case Vector(left, right)
          if left.overlaps(right) &&
            !overlapCoveredBySharedProcess(
              left,
              right,
              sharedProcessRanges
            ) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CAPTURE-OVERLAP",
          s"captured native module-item ranges ${left.start}-${left.end} and ${right.start}-${right.end} overlap"
        )
      case _ =>
    }

'''
text = text[:start] + planning + text[end:]

owned_marker = '''    val trackedInternalNames = mutable.LinkedHashSet.empty[String]
    val ownedTargetNames = mutable.LinkedHashSet.empty[String]
'''
require_once(owned_marker, 'process range state')
text = text.replace(
    owned_marker,
    owned_marker + '    val claimedProcessRanges = mutable.LinkedHashSet.empty[LineRange]\n',
    1,
)

process_marker = '          ranges += processRange\n'
require_once(process_marker, 'process claim')
text = text.replace(
    process_marker,
    process_marker + '          claimedProcessRanges += processRange\n',
    1,
)

empty_marker = '      return BlockPlan(block, mergedRanges, "")\n'
require_once(empty_marker, 'empty plan')
text = text.replace(
    empty_marker,
    '''      return BlockPlan(
        block,
        mergedRanges,
        "",
        (trackedInternalNames ++ ownedTargetNames).toSet,
        claimedProcessRanges.toSet
      )
''',
    1,
)

final_marker = '''    body = rewriteSlices(body, block.slices)
    body = rewriteVecSelections(body, block.vecIndices, block.sourceLocation)
    BlockPlan(block, mergedRanges, body)
  }

  private def findDeclarationLine(
'''
require_once(final_marker, 'final plan')
helpers = r'''    BlockPlan(
      block,
      mergedRanges,
      body,
      (trackedInternalNames ++ ownedTargetNames).toSet,
      claimedProcessRanges.toSet
    )
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

  private def overlapCoveredBySharedProcess(
      left: LineRange,
      right: LineRange,
      shared: Set[LineRange]
  ): Boolean = {
    if (!left.overlaps(right)) false
    else {
      val overlapStart = math.max(left.start, right.start)
      val overlapEnd = math.min(left.end, right.end)
      shared.exists { range =>
        range.start <= overlapStart && overlapEnd <= range.end
      }
    }
  }

  private val DirectSharedProcessAssignment =
    """^\s*([A-Za-z_][A-Za-z0-9_$]*)(\s*\[[^\]]+\])?\s*(?:<=|=(?!=))\s*(.*?)\s*;\s*$""".r

  private val SharedProcessIdentifier =
    "[A-Za-z_][A-Za-z0-9_$]*".r

  private def sharedProcessIdentifierTokens(value: String): Set[String] =
    SharedProcessIdentifier.findAllIn(value).filterNot(VerilogWords).toSet

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

  private def resolveSharedProceduralProcesses(
      plans: Vector[BlockPlan],
      lines: Vector[String],
      paths: Map[ParameterizedStructuralBlock, Vector[AlternativeStep]]
  ): (Vector[BlockPlan], Set[LineRange]) = {
    val claims = mutable.LinkedHashMap.empty[LineRange, ArrayBuffer[BlockPlan]]
    plans.foreach { plan =>
      plan.processRanges.foreach { range =>
        claims.getOrElseUpdate(range, ArrayBuffer.empty) += plan
      }
    }

    val current = mutable.LinkedHashMap.empty[
      ParameterizedStructuralBlock,
      BlockPlan
    ]
    plans.foreach(plan => current(plan.block) = plan)
    val sharedProcessRanges = mutable.LinkedHashSet.empty[LineRange]

    claims.toVector
      .filter(_._2.size > 1)
      .sortBy(_._1.start)
      .foreach { case (range, claimed) =>
        val claimants = claimed.toVector
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
            s"captured native range ${range.start}-${range.end} is shared by multiple structural alternatives but is not one simple always block"
          )
        }

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

        val completeProcess =
          stripCommonIndent(processLines.mkString("\n").trim)
        val rawEvidence = claimants.map { original =>
          val plan = current(original.block)
          val outsideProcess = removeUniqueProcess(
            plan.body,
            completeProcess,
            range,
            plan.block.sourceLocation
          )
          plan.block ->
            (plan.ownedNames ++ sharedProcessIdentifierTokens(outsideProcess))
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
            DirectSharedProcessAssignment.findFirstMatchIn(stripped) match {
              case None =>
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-SHAPE-UNSUPPORTED",
                  s"shared native process ${range.start}-${range.end} contains non-flat statement '$stripped'"
                )
              case Some(statement) =>
                val assignmentNames = sharedProcessIdentifierTokens(stripped)
                val owners = claimants.filter { plan =>
                  uniqueEvidence(plan.block).exists { name =>
                    assignmentNames(name)
                  }
                }
                owners match {
                  case Vector(owner) =>
                    ownedIndices(owner.block) += index
                  case Vector() =>
                    val selectedTarget =
                      Option(statement.group(2)).exists(_.trim.nonEmpty)
                    if (selectedTarget) {
                      fail(
                        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-ASSIGNMENT-UNOWNED",
                        s"shared native process ${range.start}-${range.end} contains selected assignment '$stripped' without branch-unique source evidence"
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

  private def findDeclarationLine(
'''
text = text.replace(final_marker, helpers, 1)

path.write_text(text, encoding='utf-8')
print(f'patched {path}')
