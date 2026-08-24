#!/usr/bin/env python3
"""Teach the materialized Increment 52 resolver about containing process ranges."""

from pathlib import Path

path = Path(
    'morphhdl/src/main/scala/spinal/core/internals/'
    'ParameterizedVerilogStructural.scala'
)
text = path.read_text(encoding='utf-8')

old_candidates = '''        val initialBlocks = initialClaimants.map(_.block).toSet
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
'''
new_candidates = '''        val initialBlocks = initialClaimants.map(_.block).toSet
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
'''
count = text.count(old_candidates)
if count != 1:
    raise SystemExit(f'expected one missing-candidate site, found {count}')
text = text.replace(old_candidates, new_candidates, 1)

old_selection = '''        selectedMissing.foreach { plan =>
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
'''
new_selection = '''        selectedMissing.foreach { plan =>
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
'''
count = text.count(old_selection)
if count != 1:
    raise SystemExit(f'expected one recovered-claimant materialization site, found {count}')
text = text.replace(old_selection, new_selection, 1)

path.write_text(text, encoding='utf-8')
