#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogHierarchy.scala"
)
value = path.read_text()

method = '''  private def debugPackedAssignment(
      component: Component,
      assignment: DataAssignmentStatement,
      selected: Option[BitVector]
  ): Unit = {
    if (!sys.env.get("MORPHDL_INCREMENT_53E_LINEAGE_DEBUG").contains("1")) return
    val finalTarget = assignment.finalTarget
    val targetName = Option(finalTarget.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")
    val sourceWidth = assignment.source match {
      case width: WidthProvider => width.getWidth.toString
      case _                    => "n/a"
    }
    val selectedName = selected.flatMap(value =>
      Option(value.getName()).filter(_.nonEmpty)
    ).getOrElse("<none>")
    System.err.println(
      s"MORPHDL-IMPLICIT-LINEAGE assignment component=${component.definitionName} " +
        s"target=$targetName targetClass=${assignment.target.getClass.getName} " +
        s"finalClass=${finalTarget.getClass.getName} sourceClass=${assignment.source.getClass.getName} " +
        s"targetEqFinal=${assignment.target eq finalTarget} targetWidth=${finalTarget.getBitsWidth} " +
        s"sourceWidth=$sourceWidth selected=$selectedName selectedOwner=${selected.map(_.component == component).getOrElse(false)}"
    )
  }

'''
call = '''        debugPackedAssignment(component, assignment, selected)
        selected.foreach(source =>
'''
call_replacement = '''        selected.foreach(source =>
'''
result = '''    if (sys.env.get("MORPHDL_INCREMENT_53E_LINEAGE_DEBUG").contains("1")) {
      val names = result.map(value =>
        Option(value.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")
      ).mkString(",")
      System.err.println(
        s"MORPHDL-IMPLICIT-LINEAGE result component=${component.definitionName} slot=$slotIdentity leaves=$names"
      )
    }

'''

for label, marker, replacement in (
    ("method", method, ""),
    ("call", call, call_replacement),
    ("result", result, ""),
):
    count = value.count(marker)
    if count > 1:
        raise SystemExit(
            f"lineage diagnostic {label} marker count={count}; expected at most one"
        )
    if count == 1:
        value = value.replace(marker, replacement, 1)

if "MORPHDL-IMPLICIT-LINEAGE" in value or "MORPHDL_INCREMENT_53E_LINEAGE_DEBUG" in value:
    raise SystemExit("lineage diagnostic cleanup left a temporary marker")

path.write_text(value)
