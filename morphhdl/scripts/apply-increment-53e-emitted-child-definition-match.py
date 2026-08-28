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
        val parameterizedTerminatorPattern =
          ("^\\\\s*\\\\)\\\\s+" + Pattern.quote(instance.instanceName) +
            "\\\\s*\\\\(\\\\s*$").r
'''
patterns_new = '''        // The native emitter may suffix concrete module and instance names
        // after graph analysis. Locate the exact graph-derived instance base
        // plus only Spinal's deterministic numeric collision suffix, then
        // rewrite its module token to the already-proven canonical definition.
        // No emitted module or signal name is a provenance discovery key.
        val emittedInstancePattern =
          Pattern.quote(instance.instanceName) + "(?:_[0-9]+)?"
        val plainStartPattern =
          ("^(\\\\s*)[A-Za-z_][A-Za-z0-9_$]*\\\\s+" +
            emittedInstancePattern + "\\\\s*\\\\(\\\\s*$").r
        val parameterizedStartPattern =
          "^(\\\\s*)[A-Za-z_][A-Za-z0-9_$]*\\\\s*#\\\\s*\\\\(\\\\s*$".r
        val parameterizedTerminatorPattern =
          ("^\\\\s*\\\\)\\\\s+" + emittedInstancePattern +
            "\\\\s*\\\\(\\\\s*$").r
'''
if value.count(patterns_old) != 1:
    raise SystemExit("hierarchy instance pattern marker is ambiguous")
value = value.replace(patterns_old, patterns_new, 1)

error_old = '''            s"normal Verilog emission contains ${starts.size} instances matching '${instance.definitionName} ${instance.instanceName}'"
'''
error_new = '''            s"normal Verilog emission contains ${starts.size} uniquely named instances matching graph child base '${instance.instanceName}' for canonical definition '${instance.definitionName}'"
'''
if value.count(error_old) != 1:
    raise SystemExit("hierarchy instance error marker is ambiguous")
value = value.replace(error_old, error_new, 1)

path.write_text(value)
