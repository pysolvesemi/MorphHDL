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
patterns_new = '''        // Native syntax attributes are emitted on the same line before the
        // child module token. Match and preserve only complete one-line
        // attribute blocks, then select the exact graph-derived instance base
        // plus Spinal's deterministic numeric collision suffix. The instance
        // token itself is retained so a legitimate emitted suffix is never
        // discarded while the module token is canonicalized.
        val emittedInstancePattern =
          Pattern.quote(instance.instanceName) + "(?:_[0-9]+)?"
        val emittedAttributePattern =
          "(?:(?:\\\\(\\\\*[^\\\\r\\\\n]*?\\\\*\\\\))\\\\s*)*"
        val plainStartPattern =
          ("^(\\\\s*)(" + emittedAttributePattern + ")" +
            "[A-Za-z_][A-Za-z0-9_$]*\\\\s+(" +
            emittedInstancePattern + ")\\\\s*\\\\(\\\\s*$").r
        val parameterizedStartPattern =
          ("^(\\\\s*)(" + emittedAttributePattern + ")" +
            "[A-Za-z_][A-Za-z0-9_$]*\\\\s*#\\\\s*\\\\(\\\\s*$").r
        val parameterizedTerminatorPattern =
          ("^\\\\s*\\\\)\\\\s+(" + emittedInstancePattern +
            ")\\\\s*\\\\(\\\\s*$").r
'''
if value.count(patterns_old) != 1:
    raise SystemExit("hierarchy instance pattern marker is ambiguous")
value = value.replace(patterns_old, patterns_new, 1)

starts_old = '''        val plainStarts = lines.zipWithIndex.collect {
          case (line, index)
              if plainStartPattern.findFirstIn(line).nonEmpty =>
            val indent = plainStartPattern.findFirstMatchIn(line).get.group(1)
            (index, index, indent)
        }
        val parameterizedStarts = lines.zipWithIndex.flatMap {
          case (line, index)
              if parameterizedStartPattern.findFirstIn(line).nonEmpty =>
            val terminator =
              (index + 1 until lines.size).find(candidate =>
                anyParameterizedTerminatorPattern
                  .findFirstIn(lines(candidate))
                  .nonEmpty
              )
            terminator.collect {
              case bodyStart
                  if parameterizedTerminatorPattern
                    .findFirstIn(lines(bodyStart))
                    .nonEmpty =>
                val indent =
                  parameterizedStartPattern.findFirstMatchIn(line).get.group(1)
                (index, bodyStart, indent)
            }
          case _ => None
        }
        val starts = plainStarts ++ parameterizedStarts
'''
starts_new = '''        val plainStarts = lines.zipWithIndex.collect {
          case (line, index)
              if plainStartPattern.findFirstIn(line).nonEmpty =>
            val matched = plainStartPattern.findFirstMatchIn(line).get
            (
              index,
              index,
              matched.group(1),
              matched.group(2),
              matched.group(3)
            )
        }
        val parameterizedStarts = lines.zipWithIndex.flatMap {
          case (line, index)
              if parameterizedStartPattern.findFirstIn(line).nonEmpty =>
            val terminator =
              (index + 1 until lines.size).find(candidate =>
                anyParameterizedTerminatorPattern
                  .findFirstIn(lines(candidate))
                  .nonEmpty
              )
            terminator.flatMap { bodyStart =>
              parameterizedTerminatorPattern
                .findFirstMatchIn(lines(bodyStart))
                .map { terminatorMatch =>
                  val startMatch =
                    parameterizedStartPattern.findFirstMatchIn(line).get
                  (
                    index,
                    bodyStart,
                    startMatch.group(1),
                    startMatch.group(2),
                    terminatorMatch.group(1)
                  )
                }
            }
          case _ => None
        }
        val starts = plainStarts ++ parameterizedStarts
        val emittedInstanceInventory = lines.zipWithIndex.collect {
          case (line, index)
              if line.toLowerCase.contains("buffercc") ||
                line.contains(instance.definitionName) ||
                line.contains(instance.instanceName) =>
            s"$index:${line.trim}"
        }.mkString(" | ")
'''
if value.count(starts_old) != 1:
    raise SystemExit("hierarchy instance start mapping marker is ambiguous")
value = value.replace(starts_old, starts_new, 1)

error_old = '''            s"normal Verilog emission contains ${starts.size} instances matching '${instance.definitionName} ${instance.instanceName}'"
'''
error_new = '''            s"normal Verilog emission contains ${starts.size} uniquely named instances matching graph child base '${instance.instanceName}' for canonical definition '${instance.definitionName}'; emitted inventory: $emittedInstanceInventory"
'''
if value.count(error_old) != 1:
    raise SystemExit("hierarchy instance error marker is ambiguous")
value = value.replace(error_old, error_new, 1)

unpack_old = '''        val (start, bodyStart, indent) = starts.head
'''
unpack_new = '''        val (start, bodyStart, indent, attributes, emittedInstanceName) =
          starts.head
'''
if value.count(unpack_old) != 1:
    raise SystemExit("hierarchy instance tuple marker is ambiguous")
value = value.replace(unpack_old, unpack_new, 1)

header_old = '''        val header =
          Vector(s"${indent}${instance.definitionName} #(") ++
            bindingLines ++
            Vector(s"${indent}) ${instance.instanceName} (")
'''
header_new = '''        val header =
          Vector(s"${indent}${attributes}${instance.definitionName} #(") ++
            bindingLines ++
            Vector(s"${indent}) $emittedInstanceName (")
'''
if value.count(header_old) != 1:
    raise SystemExit("hierarchy instance header marker is ambiguous")
value = value.replace(header_old, header_new, 1)

path.write_text(value)
