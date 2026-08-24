#!/usr/bin/env python3
"""Add focused structural-plan diagnostics after the Increment 52 candidate is materialized."""

from pathlib import Path

path = Path(
    'morphhdl/src/main/scala/spinal/core/internals/'
    'ParameterizedVerilogStructural.scala'
)
text = path.read_text(encoding='utf-8')
old = '''            if (combinedTargetCounts != expectedTargetCounts) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CLAIMANT-COVERAGE",
                s"shared native process ${range.start}-${range.end} selected assignment counts ${expectedTargetCounts.toVector.sortBy(_._1).mkString(",")} do not match initial captured counts ${initialTargetCounts.toVector.sortBy(_._1).mkString(",")} or exact mutually-exclusive candidate counts ${combinedTargetCounts.toVector.sortBy(_._1).mkString(",")}"
              )
            }
'''
new = '''            if (combinedTargetCounts != expectedTargetCounts) {
              val planDetails = plans.zipWithIndex.map { case (plan, planIndex) =>
                val pathValue = paths.getOrElse(plan.block, Vector.empty)
                val pathText = pathValue.map { step =>
                  s"${System.identityHashCode(step.region)}:${step.branch}"
                }.mkString("[", ",", "]")
                val assignmentTargets = plan.block.assignments.flatMap { assignment =>
                  Option(assignment.finalTarget.getName())
                }.mkString("[", ",", "]")
                val childNames = plan.block.children.flatMap { child =>
                  Option(child.getName())
                }.mkString("[", ",", "]")
                val rangesText = plan.ranges.map { planRange =>
                  s"${planRange.start}-${planRange.end}"
                }.mkString("[", ",", "]")
                val exclusiveFromInitial = initialClaimants.forall { owner =>
                  mutuallyExclusive(
                    pathValue,
                    paths.getOrElse(owner.block, Vector.empty)
                  )
                }
                val overlapsProcess = plan.ranges.exists(_.overlaps(range))
                val source = plan.block.sourceLocation.getOrElse("<none>")
                s"$planIndex:path=$pathText:assignments=$assignmentTargets:children=$childNames:ranges=$rangesText:overlap=$overlapsProcess:exclusive=$exclusiveFromInitial:source=$source"
              }.mkString(";")
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SHARED-PROCESS-CLAIMANT-COVERAGE",
                s"shared native process ${range.start}-${range.end} selected assignment counts ${expectedTargetCounts.toVector.sortBy(_._1).mkString(",")} do not match initial captured counts ${initialTargetCounts.toVector.sortBy(_._1).mkString(",")} or exact mutually-exclusive candidate counts ${combinedTargetCounts.toVector.sortBy(_._1).mkString(",")}; plans=$planDetails"
              )
            }
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f'expected one claimant-coverage diagnostic site, found {count}')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
