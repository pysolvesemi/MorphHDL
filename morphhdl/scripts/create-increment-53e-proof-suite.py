#!/usr/bin/env python3
from pathlib import Path
import re

source_path = Path(
    "morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoCCTests.scala"
)
target_path = Path(
    "morphhdl/src/test/scala/morphhdl/"
    "NativeStreamFifoCCProofGenerationTests.scala"
)
source = source_path.read_text()

class_marker = "final class NativeParameterizedStreamFifoCCHarness"
start = source.find(class_marker)
if start < 0:
    raise SystemExit("native parameterized StreamFifoCC harness was not found")
open_brace = source.find("{", start)
if open_brace < 0:
    raise SystemExit("native parameterized StreamFifoCC harness has no body")
depth = 0
end = None
for index in range(open_brace, len(source)):
    character = source[index]
    if character == "{":
        depth += 1
    elif character == "}":
        depth -= 1
        if depth == 0:
            end = index + 1
            break
if end is None:
    raise SystemExit("native parameterized StreamFifoCC harness body is unterminated")

prefix = source[:start]
# Keep only package/import declarations and comments before the harness. The
# generated file receives its own proof test class.
harness = source[start:end]

signature = re.compile(
    r"final class NativeParameterizedStreamFifoCCHarness\((.*?)\)\s*"
    r"extends Component",
    re.S,
)
match = signature.search(harness)
if not match:
    raise SystemExit("native parameterized StreamFifoCC harness signature changed")
arguments = match.group(1).strip()
if "depth: HdlInt" not in arguments:
    raise SystemExit("native parameterized StreamFifoCC harness no longer exposes HdlInt depth")
if "withPopBufferedReset" not in arguments:
    arguments = arguments + ", withPopBufferedReset: Boolean"
harness = harness[: match.start()] + (
    "final class NativeParameterizedStreamFifoCCFormalHarness(" + arguments + ") "
    "extends Component"
) + harness[match.end() :]

# Replace or insert one deterministic top definition name.
definition_statement = '''  setDefinitionName(
    if (withPopBufferedReset)
      "NativeParameterizedStreamFifoCCFormalHarnessBuffered"
    else "NativeParameterizedStreamFifoCCFormalHarnessPlain"
  )
'''
set_definition = re.compile(r"\s*setDefinitionName\([^\n]*\)\s*\n")
if set_definition.search(harness):
    harness = set_definition.sub("\n" + definition_statement, harness, count=1)
else:
    insertion = harness.find("{") + 1
    harness = harness[:insertion] + "\n" + definition_statement + harness[insertion:]

# The ordinary focused harness may explicitly use false or omit the optional
# native argument. Make the proof mode an explicit constructor input.
if "withPopBufferedReset = false" in harness:
    harness = harness.replace(
        "withPopBufferedReset = false",
        "withPopBufferedReset = withPopBufferedReset",
        1,
    )
elif "withPopBufferedReset = withPopBufferedReset" not in harness:
    token = "StreamFifoCC("
    call = harness.find(token)
    if call < 0:
        raise SystemExit("StreamFifoCC constructor call was not found in focused harness")
    open_call = harness.find("(", call)
    level = 0
    close_call = None
    for index in range(open_call, len(harness)):
        if harness[index] == "(":
            level += 1
        elif harness[index] == ")":
            level -= 1
            if level == 0:
                close_call = index
                break
    if close_call is None:
        raise SystemExit("StreamFifoCC constructor call is unterminated")
    harness = (
        harness[:close_call]
        + ",\n    withPopBufferedReset = withPopBufferedReset"
        + harness[close_call:]
    )

concrete = harness.replace(
    "NativeParameterizedStreamFifoCCFormalHarness",
    "NativeConcreteStreamFifoCCFormalHarness",
)
concrete = concrete.replace("depth: HdlInt", "depth: Int", 1)
# Resolve only the constructor call to the exact upstream implementation.
constructor = re.compile(
    r"(?<![A-Za-z0-9_$])(?:morphhdl\.frontend\.)?StreamFifoCC\("
)
concrete, count = constructor.subn("spinal.lib.StreamFifoCC(", concrete, count=1)
if count != 1:
    raise SystemExit(f"native StreamFifoCC constructor replacement count={count}")
# Give every independently elaborated witness a disjoint top definition name.
concrete = re.sub(
    r'''  setDefinitionName\(\n    if \(withPopBufferedReset\)\n      "NativeConcreteStreamFifoCCFormalHarnessBuffered"\n    else "NativeConcreteStreamFifoCCFormalHarnessPlain"\n  \)''',
    '''  setDefinitionName(
    if (withPopBufferedReset)
      s"NativeConcreteStreamFifoCCFormalHarnessDepth${depth}Buffered"
    else s"NativeConcreteStreamFifoCCFormalHarnessDepth${depth}Plain"
  )''',
    concrete,
    count=1,
)

proof = r'''

class NativeStreamFifoCCProofGenerationTests extends AnyFunSuite {
  private val Depths = Vector(4, 8, 16)
  private val WorkspaceEnvironment = "MORPHDL_STREAMFIFOCC_PROOF_WORKSPACE"

  test("generate one parameterized candidate and independent native witnesses") {
    val workspace = sys.env.get(WorkspaceEnvironment).map(Path.of(_)).getOrElse {
      cancel(s"Set $WorkspaceEnvironment to a persistent proof workspace")
    }
    Files.createDirectories(workspace)
    val manifest = Vector.newBuilder[String]

    Vector(false, true).foreach { buffered =>
      val mode = if (buffered) "buffered" else "plain"
      val candidateDirectory = workspace.resolve(s"candidate-$mode")
      Files.createDirectories(candidateDirectory)
      val candidateFile = s"streamfifocc_candidate_$mode.v"
      val candidateConfig = SpinalConfig(targetDirectory = candidateDirectory.toString)
      candidateConfig.netlistFileName = candidateFile
      val depth = HdlInt.param(
        "DEPTH",
        default = BigInt(8),
        min = BigInt(4),
        max = BigInt(16)
      )
      MorphVerilog(candidateConfig) {
        new NativeParameterizedStreamFifoCCFormalHarness(
          depth,
          withPopBufferedReset = buffered
        )
      }
      val candidateTop =
        if (buffered) "NativeParameterizedStreamFifoCCFormalHarnessBuffered"
        else "NativeParameterizedStreamFifoCCFormalHarnessPlain"
      manifest += s"candidate|$mode|${candidateDirectory.resolve(candidateFile)}|$candidateTop"

      Depths.foreach { selectedDepth =>
        val concreteDirectory =
          workspace.resolve(s"concrete-$mode-depth-$selectedDepth")
        Files.createDirectories(concreteDirectory)
        val concreteFile =
          s"streamfifocc_concrete_${mode}_depth_$selectedDepth.v"
        val concreteConfig =
          SpinalConfig(targetDirectory = concreteDirectory.toString)
        concreteConfig.netlistFileName = concreteFile
        SpinalVerilog(concreteConfig) {
          new NativeConcreteStreamFifoCCFormalHarness(
            selectedDepth,
            withPopBufferedReset = buffered
          )
        }
        val concreteTop =
          s"NativeConcreteStreamFifoCCFormalHarnessDepth${selectedDepth}" +
            (if (buffered) "Buffered" else "Plain")
        manifest +=
          s"concrete|$mode|$selectedDepth|${concreteDirectory.resolve(concreteFile)}|$concreteTop"
      }
    }

    Files.write(
      workspace.resolve("manifest.txt"),
      manifest.result().mkString("\n").getBytes(StandardCharsets.UTF_8)
    )
  }
}
'''

# The source prefix already contains package/import declarations used by the
# focused harness. Ensure proof-generation utilities are available exactly once.
required_imports = []
if "java.nio.charset.StandardCharsets" not in prefix:
    required_imports.append("import java.nio.charset.StandardCharsets")
if "java.nio.file.{Files, Path}" not in prefix:
    required_imports.append("import java.nio.file.{Files, Path}")
if "org.scalatest.funsuite.AnyFunSuite" not in prefix:
    required_imports.append("import org.scalatest.funsuite.AnyFunSuite")
if required_imports:
    package_end = prefix.find("\n") + 1
    prefix = prefix[:package_end] + "\n" + "\n".join(required_imports) + "\n" + prefix[package_end:]

result = prefix + harness + "\n\n" + concrete + proof
# The generated test source may mention the component under test; production
# compiler/runtime/backend sources are separately guarded against such names.
target_path.write_text(result)
