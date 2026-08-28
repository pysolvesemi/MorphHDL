#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, role: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{role}: expected one exact match, found {count}")
    return text.replace(old, new, 1)


path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ParameterizedVerilogMemories.scala"
)
text = path.read_text(encoding="utf-8")

if "sharedClock: Boolean" not in text:
    text = replace_once(
        text,
        """      writeEnable: String,
      writeData: String,
      clock: String,
      sharedAddress: Boolean,
""",
        """      writeEnable: String,
      writeData: String,
      readClock: String,
      writeClock: String,
      sharedClock: Boolean,
      sharedAddress: Boolean,
""",
        "memory plan clock fields",
    )

    text = replace_once(
        text,
        """    val processLabel = firstAvailable(s"p_${plan.memoryName}", used + helperName)
""",
        """    val processLabel = firstAvailable(s"p_${plan.memoryName}", used + helperName)
    val readProcessLabel =
      if (plan.sharedClock) processLabel
      else firstAvailable(s"${processLabel}_read", used + helperName + processLabel)
    val writeProcessLabel =
      if (plan.sharedClock) processLabel
      else
        firstAvailable(
          s"${processLabel}_write",
          used + helperName + processLabel + readProcessLabel
        )
""",
        "memory process labels",
    )

    text = replace_once(
        text,
        """    val process = renderProcess(plan, processLabel, helperName)
""",
        """    val process = renderProcess(
      plan,
      readProcessLabel,
      writeProcessLabel,
      helperName
    )
""",
        "memory process rendering call",
    )

    text = replace_once(
        text,
        """    if (
      read.clockDomain == null || write.clockDomain == null ||
      (read.clockDomain.clock ne write.clockDomain.clock) ||
      read.clockDomain.config.clockEdge != RISING ||
      write.clockDomain.config.clockEdge != RISING
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-CLOCK-POLICY-UNSUPPORTED",
        s"memory '${memory.getName()}' must use one shared positive-edge clock",
        source
      )
    }
""",
        """    if (
      read.clockDomain == null || write.clockDomain == null ||
      read.clockDomain.clock == null || write.clockDomain.clock == null ||
      read.clockDomain.config.clockEdge != RISING ||
      write.clockDomain.config.clockEdge != RISING
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-CLOCK-POLICY-UNSUPPORTED",
        s"memory '${memory.getName()}' requires named positive-edge read and write clocks",
        source
      )
    }
    val sharedClock = read.clockDomain.clock eq write.clockDomain.clock
""",
        "memory clock policy",
    )

    text = replace_once(
        text,
        """    val independentDontCare =
      (read.readUnderWrite eq dontCare) && readAddress != writeAddress
    if ((read.readUnderWrite ne readFirst) && !independentDontCare) {
""",
        """    val independentDontCare =
      (read.readUnderWrite eq dontCare) && readAddress != writeAddress
    if (!sharedClock && !independentDontCare) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DUAL-CLOCK-POLICY-UNSUPPORTED",
        s"memory '${memory.getName()}' uses independent clocks and therefore requires independent read/write addresses with readUnderWrite = dontCare",
        source
      )
    }
    if ((read.readUnderWrite ne readFirst) && !independentDontCare) {
""",
        "dual-clock collision policy",
    )

    text = replace_once(
        text,
        """    val readEnable = stableName(read.readEnable, "read enable", source)
    val writeEnable = stableName(write.writeEnable, "write enable", source)
    val writeData = stableName(write.data, "write data", source)
    val clock = stableName(read.clockDomain.clock, "memory clock", source)

    val nonAddressRoles = Vector(
      clock,
      readEnable,
""",
        """    val readEnable = stableName(read.readEnable, "read enable", source)
    val writeEnable = stableName(write.writeEnable, "write enable", source)
    val writeData = stableName(write.data, "write data", source)
    val readClock = stableName(read.clockDomain.clock, "memory read clock", source)
    val writeClock = stableName(write.clockDomain.clock, "memory write clock", source)
    if (!sharedClock && readClock == writeClock) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-CLOCK-NAME-ALIAS",
        s"memory '${memory.getName()}' has distinct read/write clock objects which emit the same identifier '$readClock'",
        source
      )
    }

    val clockRoles =
      if (sharedClock) Vector(readClock) else Vector(readClock, writeClock)
    val nonAddressRoles = clockRoles ++ Vector(
      readEnable,
""",
        "memory clock role names",
    )

    text = replace_once(
        text,
        """        s"memory '$memoryName' clock, enables, data, read result and storage roles must be distinct",
""",
        """        s"memory '$memoryName' clocks, enables, data, read result and storage roles must be distinct",
""",
        "memory clock role diagnostic",
    )

    text = replace_once(
        text,
        """      writeEnable,
      writeData,
      clock,
      sharedAddress = readAddress == writeAddress,
""",
        """      writeEnable,
      writeData,
      readClock,
      writeClock,
      sharedClock = sharedClock,
      sharedAddress = readAddress == writeAddress,
""",
        "memory plan construction",
    )

    old_render = """  private def renderProcess(
      plan: MemoryPlan,
      label: String,
      helperName: String
  ): Vector[String] = {
    val depth = render(plan.metadata.depth, helperName)
    val zeroWidth = render(plan.metadata.elementWidth, helperName)
    val lines = Vector.newBuilder[String]
    lines += s"  always @(posedge ${plan.clock}) begin : $label"
    if (plan.sharedAddress) {
      lines += s"    if (${plan.readAddress} < $depth) begin"
      lines += s"      if (${plan.readEnable} == 1'b1) begin"
      lines += s"        ${plan.readTarget} <= ${plan.memoryName}[${plan.readAddress}];"
      lines += "      end"
      lines += s"      if (${plan.writeEnable} == 1'b1) begin"
      lines += s"        ${plan.memoryName}[${plan.writeAddress}] <= ${plan.writeData};"
      lines += "      end"
      lines += s"    end else if (${plan.readEnable} == 1'b1) begin"
      lines += s"      ${plan.readTarget} <= {$zeroWidth{1'b0}};"
      lines += "    end"
    } else {
      lines += s"    if (${plan.readAddress} < $depth) begin"
      lines += s"      if (${plan.readEnable} == 1'b1) begin"
      lines += s"        ${plan.readTarget} <= ${plan.memoryName}[${plan.readAddress}];"
      lines += "      end"
      lines += s"    end else if (${plan.readEnable} == 1'b1) begin"
      lines += s"      ${plan.readTarget} <= {$zeroWidth{1'b0}};"
      lines += "    end"
      lines += s"    if (${plan.writeAddress} < $depth) begin"
      lines += s"      if (${plan.writeEnable} == 1'b1) begin"
      lines += s"        ${plan.memoryName}[${plan.writeAddress}] <= ${plan.writeData};"
      lines += "      end"
      lines += "    end"
    }
    lines += "  end"
    lines.result()
  }
"""
    new_render = """  private def renderProcess(
      plan: MemoryPlan,
      readLabel: String,
      writeLabel: String,
      helperName: String
  ): Vector[String] = {
    val depth = render(plan.metadata.depth, helperName)
    val zeroWidth = render(plan.metadata.elementWidth, helperName)
    val lines = Vector.newBuilder[String]

    if (plan.sharedClock) {
      lines += s"  always @(posedge ${plan.readClock}) begin : $readLabel"
      if (plan.sharedAddress) {
        lines += s"    if (${plan.readAddress} < $depth) begin"
        lines += s"      if (${plan.readEnable} == 1'b1) begin"
        lines += s"        ${plan.readTarget} <= ${plan.memoryName}[${plan.readAddress}];"
        lines += "      end"
        lines += s"      if (${plan.writeEnable} == 1'b1) begin"
        lines += s"        ${plan.memoryName}[${plan.writeAddress}] <= ${plan.writeData};"
        lines += "      end"
        lines += s"    end else if (${plan.readEnable} == 1'b1) begin"
        lines += s"      ${plan.readTarget} <= {$zeroWidth{1'b0}};"
        lines += "    end"
      } else {
        lines += s"    if (${plan.readAddress} < $depth) begin"
        lines += s"      if (${plan.readEnable} == 1'b1) begin"
        lines += s"        ${plan.readTarget} <= ${plan.memoryName}[${plan.readAddress}];"
        lines += "      end"
        lines += s"    end else if (${plan.readEnable} == 1'b1) begin"
        lines += s"      ${plan.readTarget} <= {$zeroWidth{1'b0}};"
        lines += "    end"
        lines += s"    if (${plan.writeAddress} < $depth) begin"
        lines += s"      if (${plan.writeEnable} == 1'b1) begin"
        lines += s"        ${plan.memoryName}[${plan.writeAddress}] <= ${plan.writeData};"
        lines += "      end"
        lines += "    end"
      }
      lines += "  end"
    } else {
      lines += s"  always @(posedge ${plan.readClock}) begin : $readLabel"
      lines += s"    if (${plan.readAddress} < $depth) begin"
      lines += s"      if (${plan.readEnable} == 1'b1) begin"
      lines += s"        ${plan.readTarget} <= ${plan.memoryName}[${plan.readAddress}];"
      lines += "      end"
      lines += s"    end else if (${plan.readEnable} == 1'b1) begin"
      lines += s"      ${plan.readTarget} <= {$zeroWidth{1'b0}};"
      lines += "    end"
      lines += "  end"
      lines += ""
      lines += s"  always @(posedge ${plan.writeClock}) begin : $writeLabel"
      lines += s"    if (${plan.writeAddress} < $depth) begin"
      lines += s"      if (${plan.writeEnable} == 1'b1) begin"
      lines += s"        ${plan.memoryName}[${plan.writeAddress}] <= ${plan.writeData};"
      lines += "      end"
      lines += "    end"
      lines += "  end"
    }
    lines.result()
  }
"""
    text = replace_once(
        text,
        old_render,
        new_render,
        "dual-clock memory process rendering",
    )

    old_complete = """  private def independentDontCareProcessesAreComplete(
      lines: Vector[String],
      blocks: Vector[LineRange],
      plan: MemoryPlan
  ): Boolean = {
    if (!plan.independentDontCare || blocks.size != 2) return false
    val expectedHeader = s"always @(posedge ${plan.clock})"
    val texts = blocks.map { block =>
      val header = lines(block.start).replaceAll("\\s+", " ").trim
      if (!header.startsWith(expectedHeader)) return false
      lines.slice(block.start, block.endInclusive + 1).mkString("\\n")
    }
    val readBlocks = texts.zipWithIndex.collect {
      case (text, index)
          if containsIdentifier(text, plan.readTarget) &&
            containsIndexedAccess(text, plan.memoryName, plan.readAddress) =>
        index
    }
    val writeBlocks = texts.zipWithIndex.collect {
      case (text, index)
          if containsIdentifier(text, plan.writeData) &&
            containsIndexedAccess(text, plan.memoryName, plan.writeAddress) =>
        index
    }
    readBlocks.size == 1 && writeBlocks.size == 1 &&
      readBlocks.head != writeBlocks.head
  }
"""
    new_complete = """  private def independentDontCareProcessesAreComplete(
      lines: Vector[String],
      blocks: Vector[LineRange],
      plan: MemoryPlan
  ): Boolean = {
    if (!plan.independentDontCare || blocks.size != 2) return false
    val entries = blocks.map { block =>
      val header = lines(block.start).replaceAll("\\s+", " ").trim
      val text = lines.slice(block.start, block.endInclusive + 1).mkString("\\n")
      header -> text
    }
    val readBlocks = entries.zipWithIndex.collect {
      case ((_, text), index)
          if containsIdentifier(text, plan.readTarget) &&
            containsIndexedAccess(text, plan.memoryName, plan.readAddress) =>
        index
    }
    val writeBlocks = entries.zipWithIndex.collect {
      case ((_, text), index)
          if containsIdentifier(text, plan.writeData) &&
            containsIndexedAccess(text, plan.memoryName, plan.writeAddress) =>
        index
    }
    if (
      readBlocks.size != 1 || writeBlocks.size != 1 ||
      readBlocks.head == writeBlocks.head
    ) return false

    val readHeader = s"always @(posedge ${plan.readClock})"
    val writeHeader = s"always @(posedge ${plan.writeClock})"
    entries(readBlocks.head)._1.startsWith(readHeader) &&
      entries(writeBlocks.head)._1.startsWith(writeHeader)
  }
"""
    text = replace_once(
        text,
        old_complete,
        new_complete,
        "dual-clock emitted-process validation",
    )

    text = text.replace(
        "This pass validates one reviewed 1R1W\n  * policy, rewrites the retained array geometry and replaces only the native\n  * memory process with the existing guarded contract.",
        "This pass validates one reviewed 1R1W\n  * policy on either one shared rising-edge clock or independent rising-edge\n  * read/write clocks, rewrites the retained array geometry and replaces only\n  * the native memory process with the existing guarded contract.",
        1,
    )

    path.write_text(text, encoding="utf-8")
