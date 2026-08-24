#!/usr/bin/env python3
"""Materialize the reviewed Increment 52 repair with fail-closed claimant recovery."""

from pathlib import Path
import subprocess


BASE_SCRIPT = Path('.github/scripts/apply_increment52_shared_process_fix.py')
SOURCE = Path(
    'morphhdl/src/main/scala/spinal/core/internals/'
    'ParameterizedVerilogStructural.scala'
)
REVIEWED_CARRIER_COMMIT = 'a5f39bc0cb253df65f19e17bd78ed6a403133d20'
CARRIER_PATH = '.github/workflows/morphhdl-native-int-nested-control-flow.yml'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one site, found {count}')
    return text.replace(old, new, 1)


# The historical carrier stored Scala newline escapes one level too shallow.
base_text = BASE_SCRIPT.read_text(encoding='utf-8')
old_escape = 'mkString("\\n")'
new_escape = 'mkString("\\\\n")'
escape_count = base_text.count(old_escape)
if escape_count != 2:
    raise SystemExit(
        f'expected two Scala newline escape sites, found {escape_count}'
    )
BASE_SCRIPT.write_text(
    base_text.replace(old_escape, new_escape),
    encoding='utf-8',
)
subprocess.run(['python3', str(BASE_SCRIPT)], check=True)

# Reuse the already reviewed child-output/AST ownership patch exactly.
previous = subprocess.check_output(
    [
        'git',
        'show',
        f'{REVIEWED_CARRIER_COMMIT}:{CARRIER_PATH}',
    ],
    text=True,
)
lines = previous.splitlines()
starts = [
    index + 1
    for index, line in enumerate(lines)
    if line == "          python3 - <<'PY'"
]
if len(starts) < 2:
    raise SystemExit(
        f'expected two Python carriers in reviewed predecessor, found {len(starts)}'
    )
start = starts[1]
end = next(
    index
    for index in range(start, len(lines))
    if lines[index] == '          PY'
)
reviewed_script = '\n'.join(
    line[10:] if line.startswith('          ') else line
    for line in lines[start:end]
)
namespace = {'__name__': '__increment52_reviewed_patch__'}
exec(
    compile(
        reviewed_script,
        '/tmp/increment-52-reviewed-child-output-patch.py',
        'exec',
    ),
    namespace,
    namespace,
)

source_text = SOURCE.read_text(encoding='utf-8')

# A branch can own an assignment in a native shared process even when its own
# plan did not initially claim that process range (the fallback Area in the
# Increment 52 fixture is the concrete example). Recover such a claimant only
# when all of these facts hold:
#   * it captured an assignment to a selected target in this process;
#   * it is mutually exclusive with every initial claimant;
#   * it does not already overlap the process range; and
#   * adding every qualifying candidate makes captured assignment cardinality
#     exactly equal the native selected-assignment cardinality per target.
# Any under-count, over-count, or competing candidate remains a hard failure.
source_text = replace_once(
    source_text,
    '''        val claimants = claimed.toVector
        val processLines = range.indices.map(lines).toVector
''',
    '''        val initialClaimants = claimed.toVector
        val processLines = range.indices.map(lines).toVector
''',
    'initial shared-process claimants',
)
claimant_augmentation = '''        val completeProcess =
          stripCommonIndent(processLines.mkString("\\n").trim)
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
          touchesSelectedTarget && exclusiveFromInitial &&
            !plan.ranges.exists(_.overlaps(range))
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
          if (plan.body.contains(completeProcess)) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-TEXT-NOT-UNIQUE",
              s"native process ${range.start}-${range.end} already appears in a recovered claimant body",
              plan.block.sourceLocation
            )
          }
          val augmentedBody =
            if (plan.body.trim.isEmpty) completeProcess
            else s"${plan.body.trim}\\n$completeProcess"
          current(plan.block) = plan.copy(
            ranges = (plan.ranges :+ range).distinct.sortBy(_.start),
            body = augmentedBody
          )
        }
        val claimants =
          (initialClaimants ++ selectedMissing).map(plan => current(plan.block))

'''
source_text = replace_once(
    source_text,
    '''        claimants.combinations(2).foreach { pair =>
''',
    claimant_augmentation + '''        claimants.combinations(2).foreach { pair =>
''',
    'shared-process claimant augmentation',
)
source_text = replace_once(
    source_text,
    '''        val completeProcess =
          stripCommonIndent(processLines.mkString("\\n").trim)
        val rawEvidence = claimants.map { plan =>
''',
    '''        val rawEvidence = claimants.map { plan =>
''',
    'deduplicate complete shared process',
)

# For a selected assignment still lacking textual/AST/child-output evidence,
# accept an owner only when exactly one mutually-exclusive claimant has an
# unmatched captured assignment to the same native target.
old_ownership = '''                val owners =
                  if (childOutputOwners.nonEmpty) childOutputOwners
                  else if (exactOwners.nonEmpty) exactOwners
                  else {
                    val assignmentNames = identifierTokens(stripped)
                    claimants.filter { plan =>
                      uniqueEvidence(plan.block).exists(name => assignmentNames(name))
                    }
                  }
                owners match {
'''
new_ownership = '''                val evidenceOwners: Vector[BlockPlan] =
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
'''
source_text = replace_once(
    source_text,
    old_ownership,
    new_ownership,
    'residual ownership insertion',
)
old_error = '''                      s"shared native process ${range.start}-${range.end} contains selected assignment '$stripped' without branch-unique source evidence"
'''
new_error = '''                      s"shared native process ${range.start}-${range.end} contains selected assignment '$stripped' without branch-unique source evidence; target=$targetName evidenceOwners=${evidenceOwners.size} residualOwners=${residualOwners.size} capacities=$residualCapacitySummary"
'''
source_text = replace_once(
    source_text,
    old_error,
    new_error,
    'residual ownership diagnostic',
)
SOURCE.write_text(source_text, encoding='utf-8')
