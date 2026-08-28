#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogHierarchy.scala"
)
value = path.read_text()

plan_old = '''  private final case class InstancePlan(
      definitionName: String,
      instanceName: String,
      bindings: Vector[(String, BindingExpr)],
      ports: Vector[PortRewrite]
  )
'''
plan_new = '''  private final case class InstancePlan(
      definitionName: String,
      emittedDefinitionName: String,
      instanceName: String,
      bindings: Vector[(String, BindingExpr)],
      ports: Vector[PortRewrite]
  )
'''
if value.count(plan_old) != 1:
    raise SystemExit("InstancePlan marker is ambiguous")
value = value.replace(plan_old, plan_new, 1)

patterns_old = '''        val plainStartPattern =
          ("^(\\\\s*)" + Pattern.quote(instance.definitionName) + "\\\\s+" +
            Pattern.quote(instance.instanceName) + "\\\\s*\\\\(\\\\s*$").r
        val parameterizedStartPattern =
          ("^(\\\\s*)" + Pattern.quote(instance.definitionName) +
            "\\\\s*#\\\\s*\\\\(\\\\s*$").r
'''
patterns_new = '''        val plainStartPattern =
          ("^(\\\\s*)" + Pattern.quote(instance.emittedDefinitionName) + "\\\\s+" +
            Pattern.quote(instance.instanceName) + "\\\\s*\\\\(\\\\s*$").r
        val parameterizedStartPattern =
          ("^(\\\\s*)" + Pattern.quote(instance.emittedDefinitionName) +
            "\\\\s*#\\\\s*\\\\(\\\\s*$").r
'''
if value.count(patterns_old) != 1:
    raise SystemExit("emitted instance pattern marker is ambiguous")
value = value.replace(patterns_old, patterns_new, 1)

error_old = '''            s"normal Verilog emission contains ${starts.size} instances matching '${instance.definitionName} ${instance.instanceName}'"
'''
error_new = '''            s"normal Verilog emission contains ${starts.size} instances matching emitted '${instance.emittedDefinitionName} ${instance.instanceName}' for canonical definition '${instance.definitionName}'"
'''
if value.count(error_old) != 1:
    raise SystemExit("hierarchy instance error marker is ambiguous")
value = value.replace(error_old, error_new, 1)

name_old = '''    val definitionName = Option(canonical.definitionName).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-DEFINITION-NAME-MISSING",
        s"child '$instanceName' has no canonical definition name"
      )
    }

    val actualPorts = indexedPorts(child, "actual", instanceName)
'''
name_new = '''    val definitionName = Option(canonical.definitionName).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-DEFINITION-NAME-MISSING",
        s"child '$instanceName' has no canonical definition name"
      )
    }
    val emittedDefinitionName =
      Option(child.definitionName).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-DEFINITION-NAME-MISSING",
          s"child '$instanceName' has no emitted definition name"
        )
      }

    val actualPorts = indexedPorts(child, "actual", instanceName)
'''
if value.count(name_old) != 1:
    raise SystemExit("definition-name marker is ambiguous")
value = value.replace(name_old, name_new, 1)

construction_old = '''    InstancePlan(definitionName, instanceName, bindings, ports)
'''
construction_new = '''    InstancePlan(
      definitionName,
      emittedDefinitionName,
      instanceName,
      bindings,
      ports
    )
'''
if value.count(construction_old) != 1:
    raise SystemExit("InstancePlan construction marker is ambiguous")
value = value.replace(construction_old, construction_new, 1)

path.write_text(value)
