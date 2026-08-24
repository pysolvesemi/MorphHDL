#!/usr/bin/env python3
"""Close containing shared-process ownership and concrete hierarchy evidence."""

from pathlib import Path
import subprocess

structural_path = Path(
    'morphhdl/src/main/scala/spinal/core/internals/'
    'ParameterizedVerilogStructural.scala'
)
text = structural_path.read_text(encoding='utf-8')

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

structural_path.write_text(text, encoding='utf-8')

hierarchy_path = Path(
    'morphhdl/src/main/scala/spinal/core/internals/'
    'ExternalParameterizedVerilogHierarchy.scala'
)
hierarchy = hierarchy_path.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str) -> None:
    global hierarchy
    count = hierarchy.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one site, found {count}')
    hierarchy = hierarchy.replace(old, new, 1)


replace_once(
    '''          connectionEvidence(
            parent,
            child,
            actualByName(name),
            assignments,
            s"concrete port '$name' of instance '$instanceName'"
          ).foreach { expression =>
''',
    '''          connectionEvidence(
            parent,
            child,
            actualByName(name),
            assignments,
            s"concrete port '$name' of instance '$instanceName'",
            allowConcreteInternal = true
          ).foreach { expression =>
''',
    'concrete-port hierarchy evidence',
)

replace_once(
    '''  private def connectionEvidence(
      parent: Component,
      child: Component,
      port: BaseType,
      assignments: Vector[DataAssignmentStatement],
      context: String
  ): Vector[BindingExpr] = {
''',
    '''  private def connectionEvidence(
      parent: Component,
      child: Component,
      port: BaseType,
      assignments: Vector[DataAssignmentStatement],
      context: String,
      allowConcreteInternal: Boolean = false
  ): Vector[BindingExpr] = {
''',
    'connectionEvidence concrete-internal flag',
)

replace_once(
    '''          Vector(bindingOf(parent, assignment.source, context))
''',
    '''          Vector(
            bindingOf(
              parent,
              assignment.source,
              context,
              allowConcreteInternal
            )
          )
''',
    'child-input binding flag',
)

replace_once(
    '''          Vector(bindingOf(parent, assignment.finalTarget, context))
''',
    '''          Vector(
            bindingOf(
              parent,
              assignment.finalTarget,
              context,
              allowConcreteInternal
            )
          )
''',
    'child-output binding flag',
)

# Insert the concrete-only selected-output path by position inside the output
# arm. The earlier child-output replacement intentionally changes formatting,
# so matching the whole transformed block is brittle.
output_start = hierarchy.index('    } else if (port.isOutput) {')
output_error = (
    '            s"$context uses a sliced, indexed, converted or '
    'expression-wrapped child-output connection; direct full packed '
    'connections are required"\n'
)
error_index = hierarchy.index(output_error, output_start)
else_token = '        } else {\n'
else_index = hierarchy.rfind(else_token, output_start, error_index)
if else_index < 0:
    raise SystemExit('concrete selected child-output fail arm was not found')
selected_output = '''        } else if (
          allowConcreteInternal && assignment.source == port &&
          assignment.finalTarget.component == parent
        ) {
          Vector(
            bindingOf(
              parent,
              assignment.target,
              context,
              allowConcreteInternal
            )
          )
        } else {
'''
hierarchy = (
    hierarchy[:else_index]
    + selected_output
    + hierarchy[else_index + len(else_token):]
)

replace_once(
    '''  private def bindingOf(
      parent: Component,
      expression: Expression,
      context: String
  ): BindingExpr = expression match {
''',
    '''  private def bindingOf(
      parent: Component,
      expression: Expression,
      context: String,
      allowConcreteInternal: Boolean
  ): BindingExpr = expression match {
''',
    'bindingOf concrete-internal flag',
)

replace_once(
    '''  ): BindingExpr = expression match {
    case value: Bool => LiteralBinding(1)
''',
    '''  ): BindingExpr = expression match {
    case _: BitVectorBitAccessFixed if allowConcreteInternal =>
      LiteralBinding(1)
    case value: Bool => LiteralBinding(1)
''',
    'fixed selected bit binding',
)

replace_once(
    '''        case None if value.isIo => LiteralBinding(value.getBitsWidth)
        case None =>
''',
    '''        case None if value.isIo || allowConcreteInternal =>
          LiteralBinding(value.getBitsWidth)
        case None =>
''',
    'concrete internal binding acceptance',
)

hierarchy_path.write_text(hierarchy, encoding='utf-8')

# The historical repair workflow stages only the structural file explicitly.
# Stage the hierarchy fix here so its later commit includes both production files.
subprocess.run(['git', 'add', str(hierarchy_path)], check=True)
