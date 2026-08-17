package spinal.core.internals

import java.util.IdentityHashMap
import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable.ArrayBuffer

import spinal.core._

/**
  * Increment 35 lowering for one ordinary bounded Spinal Mem.
  *
  * The normal emitter still owns naming, declarations, read-port wiring and
  * every inherited validation phase. This pass validates one reviewed 1R1W
  * policy, rewrites the retained array geometry and replaces only the native
  * memory process with the existing guarded read-first contract.
  */
private[internals] object ParameterizedVerilogMemories {
  private val PortableIdentifier = "[A-Za-z_][A-Za-z0-9_$]*".r
  private val PortableLogCall = "\\bclog2\\s*\\(".r

  private final case class MemoryPlan(
      memory: Mem[_],
      metadata: ParameterizedMemoryMetadata,
      read: MemReadSync,
      write: MemWrite,
      memoryName: String,
      readTarget: String,
      readAddress: String,
      writeAddress: String,
      readEnable: String,
      writeEnable: String,
      writeData: String,
      clock: String,
      sharedAddress: Boolean,
      readAddressWidth: ElaborationIntegerExpression,
      writeAddressWidth: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  )

  private final case class LineRange(start: Int, endInclusive: Int)

  def rewrite(component: Component, verilog: String, pc: PhaseContext): String = {
    if (!pc.config.parameterizedVerilog) return verilog
    val memories = ParameterizedMemory.memoriesOf(component)
    if (memories.isEmpty) return verilog
    if (pc.config.isSystemVerilog) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-MODE-UNSUPPORTED",
        "native symbolic memories target strict Verilog-2001, not SystemVerilog"
      )
    }
    if (memories.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MULTIPLE-NATIVE-MEMORIES-UNSUPPORTED",
        s"component '${component.definitionName}' contains ${memories.size} symbolic memories; Increment 35 admits exactly one reviewed memory contract"
      )
    }

    val normalized = verilog.replace("\r\n", "\n").replace('\r', '\n')
    val plan = analyze(memories.head, component, pc)
    val used = identifiers(normalized)
    val helperNeeded =
      Vector(
        plan.metadata.depth,
        plan.metadata.elementWidth,
        plan.readAddressWidth,
        plan.writeAddressWidth
      ).exists(expression => PortableLogCall.findFirstIn(expression.verilog).nonEmpty) ||
        PortableLogCall.findFirstIn(normalized).nonEmpty ||
        (
          plan.metadata.depth.parameters.nonEmpty &&
            normalized.toLowerCase.contains("io_push_valid") &&
            normalized.toLowerCase.contains("io_push_ready") &&
            normalized.toLowerCase.contains("io_pop_valid") &&
            normalized.toLowerCase.contains("io_pop_ready") &&
            normalized.toLowerCase.contains("io_occupancy") &&
            normalized.toLowerCase.contains("io_availability")
        )
    val helperName =
      if (!helperNeeded || !declaresIdentifier(normalized, "clog2")) "clog2"
      else firstAvailable("clog2", used)
    val processLabel = firstAvailable(s"p_${plan.memoryName}", used + helperName)

    var lines = normalized.split("\\n", -1).toVector
    if (helperNeeded) {
      lines = lines.map(line => replacePortableLogName(line, helperName))
    }
    lines = rewriteMemoryDeclaration(lines, plan, helperName)
    lines = rewriteReadTargetDeclaration(lines, plan, helperName)
    lines = rewriteParameterizedStreamFifoDepth(lines, plan, helperName)

    val memoryBlocks = alwaysBlocks(lines).filter { block =>
      val text = lines.slice(block.start, block.endInclusive + 1).mkString("\n")
      containsIndexedReference(text, plan.memoryName)
    }
    if (memoryBlocks.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-PROCESS-NOT-FOUND",
        s"normal Verilog emission contains ${memoryBlocks.size} clocked processes for memory '${plan.memoryName}'; expected exactly one",
        plan.sourceLocation
      )
    }
    val removed = memoryBlocks.flatMap(block => block.start to block.endInclusive).toSet
    lines = lines.zipWithIndex.collect { case (line, index) if !removed(index) => line }

    val endmodule = lines.lastIndexWhere(_.trim == "endmodule")
    if (endmodule < 0) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-ENDMODULE-NOT-FOUND",
        s"normal Verilog emission did not terminate component '${component.definitionName}'"
      )
    }
    val process = renderProcess(plan, processLabel, helperName)
    lines = lines.patch(endmodule, Vector("") ++ process ++ Vector(""), 0)

    if (helperNeeded) {
      val moduleLine = lines.indexWhere(_.trim.startsWith(s"module ${component.definitionName}"))
      val portEnd =
        if (moduleLine < 0) -1
        else (moduleLine + 1 until lines.size).find(index => lines(index).trim == ");").getOrElse(-1)
      if (portEnd < 0) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MODULE-HEADER-NOT-FOUND",
          s"normal Verilog emission did not contain a complete module header for '${component.definitionName}'"
        )
      }
      lines = lines.patch(
        portEnd + 1,
        Vector("") ++ renderPortableLogFunction(helperName) ++ Vector(""),
        0
      )
    }

    lines.mkString("\n")
  }

  private def analyze(
      memory: Mem[_],
      component: Component,
      pc: PhaseContext
  ): MemoryPlan = {
    val metadata = ParameterizedMemory.metadataOf(memory).get
    val source = metadata.sourceLocation
    val reads = ArrayBuffer.empty[MemReadSync]
    val writes = ArrayBuffer.empty[MemWrite]
    val unsupported = ArrayBuffer.empty[MemPortStatement]
    memory.foreachStatements {
      case value: MemReadSync => reads += value
      case value: MemWrite    => writes += value
      case value: MemPortStatement => unsupported += value
    }
    if (reads.size != 1 || writes.size != 1 || unsupported.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-PORT-SHAPE-UNSUPPORTED",
        s"memory '${memory.getName()}' requires exactly one readSync and one write port, with no asynchronous, read/write-combined or additional ports",
        source
      )
    }
    val read = reads.head
    val write = writes.head

    if (memory.initialContent != null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-INITIALIZATION-UNSUPPORTED",
        s"memory '${memory.getName()}' has initialization; Increment 35 preserves unspecified unwritten storage",
        source
      )
    }
    if (memory.forceMemToBlackboxTranslation) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-BLACKBOX-UNSUPPORTED",
        s"memory '${memory.getName()}' requests black-box translation",
        source
      )
    }
    if (read.hasTag(AllowMixedWidth) || write.hasTag(AllowMixedWidth)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-MIXED-WIDTH-UNSUPPORTED",
        s"memory '${memory.getName()}' uses a mixed-width port",
        source
      )
    }
    if (write.mask != null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-MASK-UNSUPPORTED",
        s"memory '${memory.getName()}' uses a write mask; Increment 35 admits whole-word writes only",
        source
      )
    }
    if (read.readUnderWrite ne readFirst) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-COLLISION-POLICY-UNSUPPORTED",
        s"memory '${memory.getName()}' must select readUnderWrite = readFirst",
        source
      )
    }
    if (
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
    requireType(read.address, TypeUInt, "read address", memory, source)
    requireType(write.address, TypeUInt, "write address", memory, source)
    requireType(read.readEnable, TypeBool, "read enable", memory, source)
    requireType(write.writeEnable, TypeBool, "write enable", memory, source)
    requireExplicitEnable(read.readEnable, "read", memory, source)
    requireExplicitEnable(write.writeEnable, "write", memory, source)
    requireWidth(read.readEnable, 1, "read enable", memory, source)
    requireWidth(write.writeEnable, 1, "write enable", memory, source)
    if (read.getWidth != memory.getWidth || write.getWidth != memory.getWidth) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DATA-WIDTH-MISMATCH",
        s"memory '${memory.getName()}' read/write widths must equal its ${memory.getWidth}-bit element width",
        source
      )
    }
    if (write.data.getWidth != memory.getWidth) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-WRITE-DATA-WIDTH-MISMATCH",
        s"memory '${memory.getName()}' write data width ${write.data.getWidth} does not match ${memory.getWidth}",
        source
      )
    }
    if (
      metadata.depth.default != BigInt(memory.wordCount) ||
      metadata.depth.minimum < 1 ||
      metadata.depth.maximum > BigInt(Int.MaxValue)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-DOMAIN-INVALID",
        s"memory '${memory.getName()}' depth '${metadata.depth.verilog}' does not match concrete witness ${memory.wordCount} or leaves the positive Int domain",
        source
      )
    }
    if (
      metadata.elementWidth.default != BigInt(memory.getWidth) ||
      metadata.elementWidth.minimum < 1 ||
      metadata.elementWidth.maximum > BigInt(pc.config.bitVectorWidthMax)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID",
        s"memory '${memory.getName()}' element width '${metadata.elementWidth.verilog}' is outside the complete supported width domain",
        source
      )
    }

    val readAddressWidth = widthOf(read.address, source)
    val writeAddressWidth = widthOf(write.address, source)
    validateAddressWidth(read.address, readAddressWidth, metadata.depth, "read", memory, pc, source)
    validateAddressWidth(write.address, writeAddressWidth, metadata.depth, "write", memory, pc, source)
    if (!equivalentWidth(readAddressWidth, writeAddressWidth)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-TYPE-MISMATCH",
        s"memory '${memory.getName()}' read and write addresses retain different complete width expressions '${readAddressWidth.verilog}' and '${writeAddressWidth.verilog}'",
        source
      )
    }

    val memoryName = stableName(memory, "memory", source)
    if (!PortableIdentifier.pattern.matcher(memoryName).matches()) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-NAME-INVALID",
        s"memory name '$memoryName' is not a portable Verilog identifier",
        source
      )
    }
    val parameters =
      (metadata.depth.parameters ++ metadata.elementWidth.parameters ++
        readAddressWidth.parameters ++ writeAddressWidth.parameters).distinct
    if (parameters.exists(_.name == memoryName)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-SIGNAL-NAME-COLLISION",
        s"memory '$memoryName' collides with a retained public parameter",
        source
      )
    }

    val readTarget = stableName(read, "synchronous read result", source)
    val readAddress = stableName(read.address, "read address", source)
    val writeAddress = stableName(write.address, "write address", source)
    val readEnable = stableName(read.readEnable, "read enable", source)
    val writeEnable = stableName(write.writeEnable, "write enable", source)
    val writeData = stableName(write.data, "write data", source)
    val clock = stableName(read.clockDomain.clock, "memory clock", source)

    val nonAddressRoles = Vector(
      clock,
      readEnable,
      writeEnable,
      writeData,
      readTarget,
      memoryName
    )
    if (nonAddressRoles.distinct.size != nonAddressRoles.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ROLE-ALIAS",
        s"memory '$memoryName' clock, enables, data, read result and storage roles must be distinct",
        source
      )
    }
    if (nonAddressRoles.contains(readAddress) || nonAddressRoles.contains(writeAddress)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ROLE-ALIAS",
        s"memory '$memoryName' address roles cannot alias its clock, enable, data, result or storage roles",
        source
      )
    }

    MemoryPlan(
      memory,
      metadata,
      read,
      write,
      memoryName,
      readTarget,
      readAddress,
      writeAddress,
      readEnable,
      writeEnable,
      writeData,
      clock,
      sharedAddress = readAddress == writeAddress,
      readAddressWidth,
      writeAddressWidth,
      source
    )
  }

  private def validateAddressWidth(
      address: Expression with WidthProvider,
      width: ElaborationIntegerExpression,
      depth: ElaborationIntegerExpression,
      role: String,
      memory: Mem[_],
      pc: PhaseContext,
      source: Option[String]
  ): Unit = {
    if (
      width.default != BigInt(address.getWidth) || width.minimum < 1 ||
      width.maximum < width.minimum ||
      width.maximum > BigInt(pc.config.bitVectorWidthMax)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-WIDTH-INVALID",
        s"memory '${memory.getName()}' $role address retained width '${width.verilog}' does not match concrete ${address.getWidth} bits or leaves the supported width domain",
        source.orElse(width.sourceLocation)
      )
    }
    val exact = compact(width.verilog) == compact(s"clog2(${depth.verilog}, 1)")
    val conservative =
      width.minimum.isValidInt && width.minimum <= 65536 &&
        (BigInt(1) << width.minimum.toInt) >= depth.maximum
    if (!exact && !conservative) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-CAPACITY-NOT-PROVEN",
        s"memory '${memory.getName()}' $role address width '${width.verilog}' cannot address depth '${depth.verilog}' over the complete legal domain",
        source.orElse(width.sourceLocation)
      )
    }
  }

  private def widthOf(
      expression: Expression with WidthProvider,
      source: Option[String]
  ): ElaborationIntegerExpression = {
    val seen = new IdentityHashMap[Expression, java.lang.Boolean]()
    val retained = ArrayBuffer.empty[ElaborationIntegerExpression]

    def walk(value: Expression): Unit = {
      if (seen.put(value, java.lang.Boolean.TRUE) == null) {
        value match {
          case base: BaseType =>
            ParameterizedWidth.expressionOf(base).foreach(retained += _)
          case other => other.foreachExpression(walk)
        }
      }
    }
    walk(expression)
    val distinct = retained.groupBy { value =>
      (
        compact(value.verilog),
        value.default,
        value.minimum,
        value.maximum,
        value.parameters.sortBy(_.name)
      )
    }.values.map(_.head).toVector
    if (distinct.size > 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-WIDTH-AMBIGUOUS",
        s"address expression '${expression.opName}' depends on multiple incompatible retained widths",
        source
      )
    }
    distinct.headOption.getOrElse(
      ElaborationIntegerExpression(
        verilog = expression.getWidth.toString,
        default = BigInt(expression.getWidth),
        minimum = BigInt(expression.getWidth),
        maximum = BigInt(expression.getWidth),
        parameters = Vector.empty,
        sourceLocation = source
      )
    )
  }

  private def equivalentWidth(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean =
    compact(left.verilog) == compact(right.verilog) &&
      left.default == right.default && left.minimum == right.minimum &&
      left.maximum == right.maximum &&
      left.parameters.sortBy(_.name) == right.parameters.sortBy(_.name)

  private def requireType(
      expression: Expression,
      expected: Any,
      role: String,
      memory: Mem[_],
      source: Option[String]
  ): Unit =
    if (expression.getTypeObject != expected) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ROLE-TYPE-MISMATCH",
        s"memory '${memory.getName()}' $role must retain ${expected.toString}, found ${expression.getTypeObject}",
        source
      )
    }

  private def requireWidth(
      expression: Expression,
      expected: Int,
      role: String,
      memory: Mem[_],
      source: Option[String]
  ): Unit = expression match {
    case _: Bool if expected == 1 =>
    case width: WidthProvider if width.getWidth == expected =>
    case width: WidthProvider =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ROLE-WIDTH-MISMATCH",
        s"memory '${memory.getName()}' $role has width ${width.getWidth}; expected $expected",
        source
      )
    case _ =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ROLE-WIDTH-MISMATCH",
        s"memory '${memory.getName()}' $role does not expose a hardware width",
        source
      )
  }

  private def requireExplicitEnable(
      expression: Expression,
      role: String,
      memory: Mem[_],
      source: Option[String]
  ): Unit = expression match {
    case _: BoolLiteral =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ENABLE-POLICY-UNSUPPORTED",
        s"memory '${memory.getName()}' $role enable must be an explicit active-high runtime signal",
        source
      )
    case _ =>
  }

  /**
    * Retain the native non-power-of-two StreamFifo algorithm while replacing
    * witness-only geometry with the public bounded depth. A witness of five
    * selects the library's explicit terminal-count wrap path, which remains
    * valid for each supported override, including one and powers of two.
    */
  private def rewriteParameterizedStreamFifoDepth(
      lines: Vector[String],
      plan: MemoryPlan,
      helperName: String
  ): Vector[String] = {
    val depth = plan.metadata.depth
    if (depth.parameters.isEmpty) return lines

    val moduleText = lines.mkString("\n").toLowerCase
    val isStreamFifo =
      moduleText.contains("io_push_valid") &&
        moduleText.contains("io_push_ready") &&
        moduleText.contains("io_pop_valid") &&
        moduleText.contains("io_pop_ready") &&
        moduleText.contains("io_occupancy") &&
        moduleText.contains("io_availability")
    if (!isStreamFifo) return lines

    val depthExpression = render(depth, helperName)
    val pointerWidth = s"$helperName($depthExpression, 1)"
    val occupancyWidth = s"$helperName(($depthExpression + 1), 1)"
    val depthDefault = depth.default
    val packedRange = "\\[[^\\]]+\\]".r
    val sizedLiteral = "(?i)([0-9]+)'([s]?)([bodh])([0-9a-f_xz]+)".r
    val pushReadyAssignment =
      """^(\s*assign\s+io_push_ready\s*=\s*)(.*)(;\s*)$""".r

    def isDeclaration(line: String): Boolean = {
      val value = line.trim.toLowerCase
      value.startsWith("input ") || value.startsWith("output ") ||
      value.startsWith("inout ") || value.startsWith("wire ") ||
      value.startsWith("reg ")
    }

    def literalValue(value: String, radix: String): Option[BigInt] = {
      if (value.exists(c => c == 'x' || c == 'X' || c == 'z' || c == 'Z')) None
      else {
        val cleaned = value.replace("_", "")
        val base = radix.toLowerCase match {
          case "b" => 2
          case "o" => 8
          case "d" => 10
          case "h" => 16
        }
        Some(BigInt(cleaned, base))
      }
    }

    def replaceSized(line: String, target: BigInt, replacement: String): String =
      sizedLiteral.replaceAllIn(
        line,
        value =>
          literalValue(value.group(4), value.group(3)) match {
            case Some(parsed) if parsed == target =>
              java.util.regex.Matcher.quoteReplacement(replacement)
            case _ => value.matched
          }
      )

    def replaceDecimal(line: String, target: BigInt, replacement: String): String = {
      val pattern =
        ("(?<![A-Za-z0-9_$'])" + java.util.regex.Pattern.quote(target.toString) +
          "(?![A-Za-z0-9_$])").r
      pattern.replaceAllIn(
        line,
        java.util.regex.Matcher.quoteReplacement(replacement)
      )
    }

    lines.map { original =>
      val lower = original.toLowerCase
      // The witness-depth FIFO emits several related address and occupancy
      // pipeline names. Normalize separators and classify the complete native
      // path so every depth-derived declaration is rewritten consistently.
      val compactName = lower.replace("_", "")
      val pointerContext =
        compactName.contains("pushptr") || compactName.contains("popptr") ||
          compactName.contains("ptrpush") || compactName.contains("ptrpop") ||
          compactName.contains("poponio") || compactName.contains("popreg") ||
          compactName.contains("addressgenpayload") ||
          compactName.contains("addressgenrdata") ||
          compactName.contains("readarbitrationpayload") ||
          compactName.contains("readportcmdpayload") ||
          compactName.contains("toflowfirepayload") ||
          (lower.contains("address") &&
            (lower.contains("ram") || lower.contains("memory")))
      val occupancyContext =
        lower.contains("occupancy") || lower.contains("availability") ||
          (compactName.contains("notpow2counter") &&
            !lower.contains("[0:0]")) ||
          lower.contains("push_ready")
      val memoryArray = lower.contains("[0:")

      var line = original
      if (isDeclaration(line) && !memoryArray && packedRange.findFirstIn(line).nonEmpty) {
        if (pointerContext) {
          line = packedRange.replaceFirstIn(
            line,
            java.util.regex.Matcher.quoteReplacement(s"[$pointerWidth-1:0]")
          )
        } else if (occupancyContext) {
          line = packedRange.replaceFirstIn(
            line,
            java.util.regex.Matcher.quoteReplacement(s"[$occupancyWidth-1:0]")
          )
        }
      }
      if (pointerContext) {
        line = replaceSized(line, depthDefault - 1, s"($depthExpression - 1)")
        line = replaceDecimal(line, depthDefault - 1, s"($depthExpression - 1)")
      }
      if (occupancyContext) {
        line = replaceSized(line, depthDefault, depthExpression)
        line = replaceDecimal(line, depthDefault, depthExpression)
      }
      line match {
        case pushReadyAssignment(prefix, rhs, suffix) =>
          s"$prefix(($depthExpression == 1) ? ((io_occupancy == 0) || (io_pop_valid && io_pop_ready)) : ($rhs))$suffix"
        case _ => line
      }
    }
  }

  private def rewriteReadTargetDeclaration(
    lines: Vector[String],
    plan: MemoryPlan,
    helperName: String
  ): Vector[String] = {
    if (plan.metadata.elementWidth.parameters.isEmpty) return lines

    val regDeclaration = "^\\s*(?:output\\s+)?reg\\b".r
    val candidates = lines.zipWithIndex.collect {
      case (line, index)
          if regDeclaration.findFirstIn(line).nonEmpty &&
            containsIdentifier(line, plan.readTarget) =>
        index
    }
    if (candidates.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-READ-TARGET-DECLARATION-NOT-FOUND",
        s"normal Verilog emission contains ${candidates.size} register declarations matching synchronous read result '${plan.readTarget}'",
        plan.sourceLocation
      )
    }

    val index = candidates.head
    val line = lines(index)
    val nameMatch = identifierPattern(plan.readTarget).findFirstMatchIn(line).get
    var prefix = line.substring(0, nameMatch.start)
    val suffix = line.substring(nameMatch.end)
    val packed = "\\[[^\\]]+\\]\\s*$".r
    val range = s"[${render(plan.metadata.elementWidth, helperName)}-1:0]"
    packed.findFirstMatchIn(prefix) match {
      case Some(value) =>
        prefix = prefix.substring(0, value.start) + range + " "
      case None =>
        prefix = prefix + range + " "
    }
    lines.updated(index, prefix + plan.readTarget + suffix)
  }

  private def rewriteMemoryDeclaration(
      lines: Vector[String],
      plan: MemoryPlan,
      helperName: String
  ): Vector[String] = {
    val concreteRange = ("\\[0\\s*:\\s*" + (plan.memory.wordCount - 1) + "\\]").r
    val candidates = lines.zipWithIndex.collect {
      case (line, index)
          if containsIdentifier(line, plan.memoryName) &&
            concreteRange.findFirstIn(line).nonEmpty && line.contains("reg") =>
        index
    }
    if (candidates.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DECLARATION-NOT-FOUND",
        s"normal Verilog emission contains ${candidates.size} declarations matching memory '${plan.memoryName}'",
        plan.sourceLocation
      )
    }
    val index = candidates.head
    val line = lines(index)
    val namePattern = identifierPattern(plan.memoryName)
    val nameMatch = namePattern.findFirstMatchIn(line).get
    var prefix = line.substring(0, nameMatch.start)
    var suffix = line.substring(nameMatch.end)

    if (plan.metadata.elementWidth.parameters.nonEmpty) {
      val packed = "\\[[^\\]]+\\]\\s*$".r
      val range = s"[${render(plan.metadata.elementWidth, helperName)}-1:0]"
      packed.findFirstMatchIn(prefix) match {
        case Some(value) =>
          prefix = prefix.substring(0, value.start) + range + " "
        case None =>
          prefix = prefix + range + " "
      }
    }
    if (plan.metadata.depth.parameters.nonEmpty) {
      val depthRange = s"[0:${render(plan.metadata.depth, helperName)}-1]"
      suffix = concreteRange.replaceFirstIn(
        suffix,
        Matcher.quoteReplacement(depthRange)
      )
    }
    lines.updated(index, prefix + plan.memoryName + suffix)
  }

  private def renderProcess(
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

  private def alwaysBlocks(lines: Vector[String]): Vector[LineRange] = {
    val blocks = Vector.newBuilder[LineRange]
    var index = 0
    while (index < lines.size) {
      if (lines(index).trim.startsWith("always @")) {
        var cursor = index
        var depth = 0
        var sawBegin = false
        var complete = false
        while (cursor < lines.size && !complete) {
          val line = lines(cursor)
          val begins = "\\bbegin\\b".r.findAllMatchIn(line).size
          val ends = "\\bend\\b".r.findAllMatchIn(line).size
          if (begins != 0) sawBegin = true
          depth += begins - ends
          if (sawBegin && depth == 0) complete = true
          cursor += 1
        }
        if (!complete) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-MEMORY-PROCESS-MALFORMED",
            s"normal Verilog emission contains an unterminated always block at line ${index + 1}"
          )
        }
        blocks += LineRange(index, cursor - 1)
        index = cursor
      } else index += 1
    }
    blocks.result()
  }

  private def renderPortableLogFunction(name: String): Vector[String] =
    Vector(
      s"  function integer $name;",
      "    input integer value;",
      "    input integer minimum_result;",
      "    integer remaining;",
      "    begin",
      s"      $name = 0;",
      "      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin",
      s"        $name = $name + 1;",
      "      end",
      s"      if ($name < minimum_result) begin",
      s"        $name = minimum_result;",
      "      end",
      "    end",
      "  endfunction"
    )

  private def render(
      expression: ElaborationIntegerExpression,
      helperName: String
  ): String =
    replacePortableLogName(expression.verilog, helperName)

  private def replacePortableLogName(value: String, helperName: String): String =
    PortableLogCall.replaceAllIn(
      value,
      _ => Matcher.quoteReplacement(helperName + "(")
    )

  private def identifiers(value: String): Set[String] =
    "[A-Za-z_][A-Za-z0-9_$]*".r.findAllIn(value).toSet

  private def declaresIdentifier(value: String, name: String): Boolean = {
    val declaration =
      ("(?m)^\\s*(?:input|output|inout|wire|reg|integer|parameter(?:\\s+integer)?|localparam(?:\\s+integer)?)\\b[^\\n]*" +
        Pattern.quote(name) + "\\s*(?=[,;=])").r
    val module =
      ("(?m)^\\s*module\\s+" + Pattern.quote(name) + "\\b").r
    declaration.findFirstIn(value).nonEmpty || module.findFirstIn(value).nonEmpty
  }

  private def firstAvailable(base: String, used: Set[String]): String =
    if (!used(base)) base
    else {
      var suffix = 1
      var candidate = s"${base}_$suffix"
      while (used(candidate)) {
        suffix += 1
        candidate = s"${base}_$suffix"
      }
      candidate
    }

  private def stableName(
      value: AnyRef,
      role: String,
      source: Option[String]
  ): String = value match {
    case nameable: Nameable =>
      Option(nameable.getName()).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ROLE-NAME-MISSING",
          s"$role has no stable name after normal Verilog emission",
          source
        )
      }
    case _ =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ROLE-NAME-MISSING",
        s"$role is not a named native AST value",
        source
      )
  }

  private def compact(value: String): String = value.replaceAll("\\s+", "")

  private def identifierPattern(name: String) =
    ("(?<![A-Za-z0-9_$])" + Pattern.quote(name) + "(?![A-Za-z0-9_$])").r

  private def containsIdentifier(value: String, name: String): Boolean =
    identifierPattern(name).findFirstIn(value).nonEmpty

  private def containsIndexedReference(value: String, name: String): Boolean =
    ("(?<![A-Za-z0-9_$])" + Pattern.quote(name) + "\\s*\\[").r
      .findFirstIn(value)
      .nonEmpty

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
