#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/test/scala/morphhdl/"
    "NativeStreamFifoCCFormalEquivalenceTests.scala"
)
value = path.read_text()


def once(old: str, new: str, label: str) -> None:
    global value
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    value = value.replace(old, new, 1)

once(
'''  private final case class Generated(
      candidate: Path,
      concrete: Map[(Int, Boolean), Path]
  )
''',
'''  private final case class Generated(
      candidateByResetMode: Map[Boolean, Path],
      concrete: Map[(Int, Boolean), Path]
  )
''',
"generated candidate map",
)

start = value.index("  private def generate(directory: Path): Generated = {\n")
end = value.index("  private def validateGenerated", start)
replacement = '''  private def generate(directory: Path): Generated = {
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(8),
      min = BigInt(4),
      max = BigInt(16)
    )
    val candidateByResetMode = ResetModes.map { buffered =>
      val candidateDirectory =
        directory.resolve(s"candidate-${resetMode(buffered)}")
      Files.createDirectories(candidateDirectory)
      val candidateConfig = SpinalConfig(targetDirectory = candidateDirectory.toString)
      val file = s"stream_fifocc_parameterized_${resetMode(buffered)}.v"
      candidateConfig.netlistFileName = file
      MorphVerilog(candidateConfig) {
        val (pushClock, popClock) = clocks()
        val component = morphhdl.frontend.StreamFifoCC(
          HardType(Bits(8 bits)),
          depth,
          pushClock,
          popClock,
          withPopBufferedReset = buffered
        )
        component.setDefinitionName(parameterizedTop(buffered))
        component
      }
      buffered -> candidateDirectory.resolve(file)
    }.toMap

    val concrete = (for {
      selectedDepth <- Depths
      buffered <- ResetModes
    } yield {
      val concreteDirectory =
        directory.resolve(s"concrete-${suffix(selectedDepth, buffered)}")
      Files.createDirectories(concreteDirectory)
      val config = SpinalConfig(targetDirectory = concreteDirectory.toString)
      val file = s"stream_fifocc_concrete_${suffix(selectedDepth, buffered)}.v"
      config.netlistFileName = file
      SpinalVerilog(config) {
        val (pushClock, popClock) = clocks()
        val component = new spinal.lib.StreamFifoCC(
          HardType(Bits(8 bits)),
          selectedDepth,
          pushClock,
          popClock,
          withPopBufferedReset = buffered
        )
        component.setDefinitionName(concreteTop(selectedDepth, buffered))
        component
      }
      (selectedDepth -> buffered) -> concreteDirectory.resolve(file)
    }).toMap

    Generated(candidateByResetMode, concrete)
  }

'''
value = value[:start] + replacement + value[end:]

start = value.index("  private def validateGenerated(generated: Generated): Unit = {\n")
end = value.index("  private def prepare", start)
replacement = '''  private def validateGenerated(generated: Generated): Unit = {
    generated.candidateByResetMode.foreach { case (buffered, path) =>
      val candidate = read(path)
      assert(candidate.contains(s"module ${parameterizedTop(buffered)} #("))
      assert(candidate.contains("parameter integer DEPTH = 8"))
      requiredPorts.foreach(port => assert(candidate.contains(port), s"missing $port"))
    }
    generated.concrete.foreach { case ((depth, buffered), path) =>
      val concrete = read(path)
      assert(concrete.contains(s"module ${concreteTop(depth, buffered)}"))
      assert(!concrete.contains("parameter integer DEPTH"))
      requiredPorts.foreach(port => assert(concrete.contains(port), s"missing $port"))
    }
    assert(generated.candidateByResetMode.values.map(read).toSet.size == ResetModes.size)
    assert(generated.concrete.values.map(read).toSet.size == Depths.size * ResetModes.size)
  }

'''
value = value[:start] + replacement + value[end:]

once(
'''      s"""read_verilog -defer ${quote(generated.candidate)}
         |chparam -set DEPTH $depth ParameterizedStreamFifoCC
         |hierarchy -check -top ParameterizedStreamFifoCC
''',
'''      s"""read_verilog -defer ${quote(generated.candidateByResetMode(buffered))}
         |chparam -set DEPTH $depth ${parameterizedTop(buffered)}
         |hierarchy -check -top ${parameterizedTop(buffered)}
''',
"candidate preparation by reset mode",
)

once(
'''  private def candidateTop(depth: Int, buffered: Boolean) = s"candidate_${suffix(depth, buffered)}"
''',
'''  private def parameterizedTop(buffered: Boolean) =
    s"ParameterizedStreamFifoCC_${resetMode(buffered)}"
  private def candidateTop(depth: Int, buffered: Boolean) = s"candidate_${suffix(depth, buffered)}"
''',
"parameterized top naming",
)

once(
'''  private def suffix(depth: Int, buffered: Boolean) = s"d${depth}_${if (buffered) "buffered" else "direct"}"
''',
'''  private def resetMode(buffered: Boolean) = if (buffered) "buffered" else "direct"
  private def suffix(depth: Int, buffered: Boolean) = s"d${depth}_${resetMode(buffered)}"
''',
"reset mode helper",
)

path.write_text(value)
