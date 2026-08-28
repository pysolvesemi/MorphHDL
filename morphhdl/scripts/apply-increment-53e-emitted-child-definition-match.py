#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogHierarchy.scala"
)
value = path.read_text()

patterns_old = '''        val plainStartPattern =
          ("^(\\\\s*)" + Pattern.quote(instance.definitionName) + "\\\\s+" +
            Pattern.quote(instance.instanceName) + "\\\\s*\\\\(\\\\s*$").r
        val parameterizedStartPattern =
          ("^(\\\\s*)" + Pattern.quote(instance.definitionName) +
            "\\\\s*#\\\\s*\\\\(\\\\s*$").r
'''
patterns_new = '''        // The native emitter may suffix one concrete child definition after
        // graph analysis (for example the second occurrence of one Component
        // class). Locate the exact graph-derived instance name first, then
        // rewrite its emitted module token to the already-proven canonical
        // definition below. No emitted module or signal name is a provenance
        // discovery key.
        val plainStartPattern =
          ("^(\\\\s*)[A-Za-z_][A-Za-z0-9_$]*\\\\s+" +
            Pattern.quote(instance.instanceName) + "\\\\s*\\\\(\\\\s*$").r
        val parameterizedStartPattern =
          "^(\\\\s*)[A-Za-z_][A-Za-z0-9_$]*\\\\s*#\\\\s*\\\\(\\\\s*$".r
'''
if value.count(patterns_old) != 1:
    raise SystemExit("hierarchy instance pattern marker is ambiguous")
value = value.replace(patterns_old, patterns_new, 1)

error_old = '''            s"normal Verilog emission contains ${starts.size} instances matching '${instance.definitionName} ${instance.instanceName}'"
'''
error_new = '''            s"normal Verilog emission contains ${starts.size} uniquely named instances matching graph child '${instance.instanceName}' for canonical definition '${instance.definitionName}'"
'''
if value.count(error_old) != 1:
    raise SystemExit("hierarchy instance error marker is ambiguous")
value = value.replace(error_old, error_new, 1)

path.write_text(value)
