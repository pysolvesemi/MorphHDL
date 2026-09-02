package spinal.core.internals

import morphhdl.runtime.ParameterizedVerilogMode

import java.util.IdentityHashMap
import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable.ArrayBuffer

import spinal.core._

/** MorphHDL-owned external lowering for one ordinary bounded Spinal Mem.
  *
  * The normal emitter still owns naming, declarations, read-port wiring and
  * every inherited validation phase. This pass validates one reviewed 1R1W
  * policy, rewrites the retained array geometry and replaces only the native
  * memory process with the existing guarded contract. Explicit readFirst is
  * preserved. An independent-address simple-dual-port dontCare read is lowered
  * deterministically as read-first, which is one legal dontCare outcome.
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
      independentDontCare: Boolean,
      readAddressWidth: ElaborationIntegerExpression,
      writeAddressWidth: ElaborationIntegerExpression,
      sourceLocation: Option[String]
  )

  private final case class StructuralAsyncPlan(
      memory: Mem[_],
      metadata: ParameterizedMemoryMetadata,
      memoryName: String,
      sourceLocation: Option[String],
      capturedReads: Vector[MemReadAsync],
      hasUncapturedPorts: Boolean
  )

  private final case class LineRange(start: Int, endInclusive: Int)

  def rewrite(component: Component, verilog: String, pc: PhaseContext): String = {
    if (!ParameterizedVerilogMode.isEnabled(pc.config)) return verilog
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
    val structuralAsync = analyzeStructuralAsync(memories.head, component, pc)
    structuralAsync match {
      case Some(plan) if !plan.hasUncapturedPorts =>
        return rewriteStructuralAsync(component, normalized, plan)
      case _ =>
    }

    val plan = analyze(
      memories.head,
      component,
      pc,
      structuralAsync.toVector.flatMap(_.capturedReads)
    )
    val used = identifiers(normalized)
    val helperNeeded =
      Vector(
        plan.metadata.depth,
        plan.metadata.elementWidth,
        plan.readAddressWidth,
        plan.writeAddressWidth
      ).exists(expression => PortableLogCall.findFirstIn(expression.verilog).nonEmpty) ||
        PortableLogCall.findFirstIn(normalized).nonEmpty
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
    lines = rewriteAddressDeclarations(lines, plan, helperName)

    val memoryBlocks = alwaysBlocks(lines).filter { block =>
      val text = lines.slice(block.start, block.endInclusive + 1).mkString("\n")
      containsIndexedReference(text, plan.memoryName)
    }
    val acceptedMemoryBlocks =
      memoryBlocks.size == 1 ||
        independentDontCareProcessesAreComplete(lines, memoryBlocks, plan)
    if (!acceptedMemoryBlocks) {
      val expected =
        if (plan.independentDontCare)
          "one combined process or one proven read process plus one proven write process"
        else "exactly one process"
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-PROCESS-NOT-FOUND",
        s"normal Verilog emission contains ${memoryBlocks.size} clocked processes for memory '${plan.memoryName}'; expected $expected",
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

    if (helperNeeded)
      lines = insertPortableLogFunction(component, lines, helperName)

    lines.mkString("\n")
  }

  /** A finite structural Mem read owns only the exact async ports captured in
    * its generate body.  It needs symbolic array geometry, not the generic
    * 1R1W process policy.  Recognizing that path by retained statement identity
    * keeps ordinary async symbolic memories outside the reviewed surface.
    */
  private def analyzeStructuralAsync(
      memory: Mem[_],
      component: Component,
      pc: PhaseContext
  ): Option[StructuralAsyncPlan] = {
    val blocks = ParameterizedStructure
      .regionsOf(component)
      .flatMap(ParameterizedStructure.allBlocks)
    val matchingBlocks = blocks.filter(_.memoryIndices.exists(value => value.memory eq memory))
    if (matchingBlocks.isEmpty) return None

    val metadata = ParameterizedMemory.metadataOf(memory).get
    val source = metadata.sourceLocation
    if (metadata.elementWidth.parameters.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-ASYNC-MEM-ELEMENT-WIDTH-UNSUPPORTED",
        s"finite structural memory '${memory.getName()}' requires one concrete native element width; symbolic packed element geometry remains owned by the reviewed 1R1W contract",
        source
      )
    }
    if (memory.initialContent != null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-INITIALIZATION-UNSUPPORTED",
        s"finite structural memory '${memory.getName()}' has initialization",
        source
      )
    }
    if (memory.forceMemToBlackboxTranslation) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-BLACKBOX-UNSUPPORTED",
        s"finite structural memory '${memory.getName()}' requests black-box translation",
        source
      )
    }

    val ports = ArrayBuffer.empty[MemPortStatement]
    memory.foreachStatements {
      case value: MemPortStatement => ports += value
      case _                       =>
    }
    val captured = new IdentityHashMap[Statement, java.lang.Boolean]()
    def retain(value: Statement): Unit = {
      if (captured.put(value, java.lang.Boolean.TRUE) == null) {
        value match {
          case tree: TreeStatement => tree.foreachStatements(retain)
          case _                   =>
        }
      }
    }
    matchingBlocks.flatMap(_.statements).foreach(retain)
    val capturedPorts = ports.filter(captured.containsKey).toVector
    val reads = capturedPorts.collect { case value: MemReadAsync => value }
    if (ports.isEmpty || capturedPorts.isEmpty || reads.size != capturedPorts.size) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-PORT-SHAPE-UNSUPPORTED",
        s"finite structural memory '${memory.getName()}' requires every port retained by its exact structural owner to be asynchronous; found ${reads.size} async ports among ${capturedPorts.size} retained ports and ${ports.size} total ports",
        source
      )
    }
    val selections = matchingBlocks.flatMap(_.memoryIndices).filter(value => value.memory eq memory)
    if (
      selections.isEmpty ||
      selections.exists(value =>
        (value.port.mem ne memory) ||
          !reads.exists(_ eq value.port) ||
          !captured.containsKey(value.port)
      ) ||
      selections.indices.exists { left =>
        selections.indices.exists(right => left < right && (selections(left).port eq selections(right).port))
      }
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-PORT-IDENTITY-MISMATCH",
        s"finite structural memory '${memory.getName()}' lost its unique captured async-port identities",
        source
      )
    }
    reads.foreach { read =>
      if (read.hasTag(AllowMixedWidth) || read.getWidth != memory.getWidth) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-MIXED-WIDTH-UNSUPPORTED",
          s"finite structural memory '${memory.getName()}' uses a mixed-width asynchronous port",
          source
        )
      }
      requireType(read.address, TypeUInt, "finite structural read address", memory, source)
      if (read.address.getWidth < 1 || read.address.getWidth > pc.config.bitVectorWidthMax) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-WIDTH-INVALID",
          s"finite structural memory '${memory.getName()}' has an invalid ${read.address.getWidth}-bit native address witness",
          source
        )
      }
    }

    val projectedDepth =
      if (metadata.depth.exactDomain.nonEmpty)
        ParameterizedStructure.projectedMemoryEvaluationOf(
          component,
          memory,
          metadata.depth,
          s"finite structural memory '${memory.getName()}' depth",
          source.orElse(metadata.depth.sourceLocation)
        )
      else None
    val depthMinimum =
      projectedDepth.map(_.results.map(_._2).min).getOrElse(metadata.depth.minimum)
    val depthMaximum =
      projectedDepth.map(_.results.map(_._2).max).getOrElse(metadata.depth.maximum)
    if (
      metadata.depth.default != BigInt(memory.wordCount) ||
      depthMinimum < 1 || depthMaximum > BigInt(Int.MaxValue)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-DOMAIN-INVALID",
        s"finite structural memory '${memory.getName()}' depth '${metadata.depth.verilog}' does not match concrete witness ${memory.wordCount} or leaves the positive Int domain",
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
        s"finite structural memory '${memory.getName()}' element width '${metadata.elementWidth.verilog}' is outside the complete supported width domain",
        source
      )
    }
    ElabInt.validateParameterRootInventory(
      s"finite structural memory '${memory.getName()}' geometry",
      Vector(metadata.depth, metadata.elementWidth)
    )
    Some(
      StructuralAsyncPlan(
        memory,
        metadata,
        stableName(memory, "finite structural memory", source),
        source,
        reads,
        ports.exists(value => !captured.containsKey(value))
      )
    )
  }

  private def rewriteStructuralAsync(
      component: Component,
      normalized: String,
      plan: StructuralAsyncPlan
  ): String = {
    val helperNeeded =
      Vector(plan.metadata.depth, plan.metadata.elementWidth).exists(expression =>
        PortableLogCall.findFirstIn(expression.verilog).nonEmpty
      ) || PortableLogCall.findFirstIn(normalized).nonEmpty
    val used = identifiers(normalized)
    val helperName =
      if (!helperNeeded || !declaresIdentifier(normalized, "clog2")) "clog2"
      else firstAvailable("clog2", used)
    var lines = normalized.split("\\n", -1).toVector
    if (helperNeeded)
      lines = lines.map(line => replacePortableLogName(line, helperName))
    lines = rewriteMemoryDeclaration(
      lines,
      plan.memory,
      plan.memoryName,
      plan.metadata,
      helperName,
      plan.sourceLocation
    )
    if (helperNeeded)
      lines = insertPortableLogFunction(component, lines, helperName)
    lines.mkString("\n")
  }

  private def analyze(
      memory: Mem[_],
      component: Component,
      pc: PhaseContext,
      ignoredStructuralReads: Vector[MemReadAsync] = Vector.empty
  ): MemoryPlan = {
    val metadata = ParameterizedMemory.metadataOf(memory).get
    val source = metadata.sourceLocation
    val reads = ArrayBuffer.empty[MemReadSync]
    val writes = ArrayBuffer.empty[MemWrite]
    val unsupported = ArrayBuffer.empty[MemPortStatement]
    val ignored = new IdentityHashMap[MemPortStatement, java.lang.Boolean]()
    ignoredStructuralReads.foreach(value => ignored.put(value, java.lang.Boolean.TRUE))
    memory.foreachStatements {
      case value: MemPortStatement if ignored.containsKey(value) =>
      case value: MemReadSync                                    => reads += value
      case value: MemWrite                                       => writes += value
      case value: MemPortStatement                               => unsupported += value
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
    val projectedDepth =
      if (metadata.depth.exactDomain.nonEmpty)
        ParameterizedStructure.projectedMemoryEvaluationOf(
          component,
          memory,
          metadata.depth,
          s"memory '${memory.getName()}' depth",
          source.orElse(metadata.depth.sourceLocation)
        )
      else None
    val depthMinimum =
      projectedDepth.map(_.results.map(_._2).min).getOrElse(metadata.depth.minimum)
    val depthMaximum =
      projectedDepth.map(_.results.map(_._2).max).getOrElse(metadata.depth.maximum)
    if (
      metadata.depth.default != BigInt(memory.wordCount) ||
      depthMinimum < 1 ||
      depthMaximum > BigInt(Int.MaxValue)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-DOMAIN-INVALID",
        s"memory '${memory.getName()}' depth '${metadata.depth.verilog}' does not match concrete witness ${memory.wordCount} or leaves the positive Int domain",
        source
      )
    }
    val projectedElementWidth =
      if (metadata.elementWidth.exactDomain.nonEmpty)
        ParameterizedStructure.projectedMemoryEvaluationOf(
          component,
          memory,
          metadata.elementWidth,
          s"memory '${memory.getName()}' element width",
          source.orElse(metadata.elementWidth.sourceLocation)
        )
      else None
    val elementWidthMinimum = projectedElementWidth
      .map(_.results.map(_._2).min)
      .getOrElse(metadata.elementWidth.minimum)
    val elementWidthMaximum = projectedElementWidth
      .map(_.results.map(_._2).max)
      .getOrElse(metadata.elementWidth.maximum)
    if (
      metadata.elementWidth.default != BigInt(memory.getWidth) ||
      elementWidthMinimum < 1 ||
      elementWidthMaximum > BigInt(pc.config.bitVectorWidthMax)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-WIDTH-INVALID",
        s"memory '${memory.getName()}' element width '${metadata.elementWidth.verilog}' is outside the complete supported width domain",
        source
      )
    }

    val nativeAddressWidth =
      nativeMemoryAddressWidth(metadata.depth, source)
    val readAddressWidth = selectAddressWidth(
      read.address,
      widthOf(read.address, source),
      nativeAddressWidth,
      memory,
      source
    )
    val writeAddressWidth = selectAddressWidth(
      write.address,
      widthOf(write.address, source),
      nativeAddressWidth,
      memory,
      source
    )
    ElabInt.validateParameterRootInventory(
      s"native memory '${memory.getName()}' geometry",
      Vector(
        metadata.depth,
        metadata.elementWidth,
        readAddressWidth,
        writeAddressWidth
      )
    )
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
    val independentDontCare =
      (read.readUnderWrite eq dontCare) && readAddress != writeAddress
    if ((read.readUnderWrite ne readFirst) && !independentDontCare) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-COLLISION-POLICY-UNSUPPORTED",
        s"memory '${memory.getName()}' must select readUnderWrite = readFirst; only independent-address simple-dual-port dontCare reads are admitted",
        source
      )
    }
    val readEnable = stableName(read.readEnable, "read enable", source)
    val writeEnable = stableName(write.writeEnable, "write enable", source)
    val writeData = stablePackedName(write.data, "write data", source)
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
      independentDontCare = independentDontCare,
      readAddressWidth,
      writeAddressWidth,
      source
    )
  }

  private def nativeMemoryAddressWidth(
      depth: ElaborationIntegerExpression,
      source: Option[String]
  ): ElaborationIntegerExpression = {
    val exactDomain = depth.exactDomain.map { domain =>
      ElabInt.checkedIntegerDerivedDomain(
        domain,
        domain.evaluations.map { case (rootValue, value) =>
          rootValue -> portableAddressWidth(value)
        },
        source.orElse(depth.sourceLocation),
        "native memory address width"
      )
    }
    val derived = ElaborationIntegerExpression(
      verilog = s"clog2(${depth.verilog}, 1)",
      default = portableAddressWidth(depth.default),
      minimum = portableAddressWidth(depth.minimum),
      maximum = portableAddressWidth(depth.maximum),
      parameters = depth.parameters,
      sourceLocation = source.orElse(depth.sourceLocation),
      parameterRoots = depth.completedParameterRoots,
      exactDomain = exactDomain
    )
    (depth.projectionProvenance, exactDomain) match {
      case (Some(projection), Some(domain)) =>
        derived.attachProjection(
          domain,
          projection.admitted,
          projection.representative,
          "native memory address width",
          derived.sourceLocation
        )
      case _ => derived
    }
  }

  private def portableAddressWidth(value: BigInt): BigInt = {
    if (value < 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-DOMAIN-INVALID",
        s"native memory address width requires a positive depth, received $value"
      )
    }
    BigInt(math.max(1, (value - 1).bitLength))
  }

  private def selectAddressWidth(
      address: Expression with WidthProvider,
      retained: ElaborationIntegerExpression,
      native: ElaborationIntegerExpression,
      memory: Mem[_],
      source: Option[String]
  ): ElaborationIntegerExpression = {
    val retainedIsConcreteWitness =
      retained.parameters.isEmpty &&
        retained.default == BigInt(address.getWidth) &&
        retained.minimum == retained.default &&
        retained.maximum == retained.default
    def assignmentsOf(target: BaseType): Vector[DataAssignmentStatement] = {
      val assignments = ArrayBuffer.empty[DataAssignmentStatement]
      memory.component.dslBody.walkLeafStatements {
        case value: DataAssignmentStatement if value.finalTarget eq target =>
          assignments += value
        case _ =>
      }
      assignments.toVector
    }
    val addressAssignments = address match {
      case target: BaseType => assignmentsOf(target)
      case _                => Vector.empty
    }
    // A native Mem has one address type across its reviewed read/write ports.
    // One exact retained native resize edge or reconstructible typed boundary
    // may retain that shared fixed ABI only when the current concrete port
    // still covers the complete native address domain. A smaller branch
    // representative must be promoted.
    val memoryHasFixedResizeConsumer = {
      val portAddresses = ArrayBuffer.empty[UInt]
      memory.foreachStatements {
        case port: MemReadSync =>
          port.address match {
            case target: UInt => portAddresses += target
            case _            =>
          }
        case port: MemWrite =>
          port.address match {
            case target: UInt => portAddresses += target
            case _            =>
          }
        case _ =>
      }
      portAddresses.exists { target =>
        val assignments = assignmentsOf(target)
        assignments.size == 1 && {
          val assignment = assignments.head
          ExternalParameterizedAutoResize.preservesFixedTypedResizeConsumer(
            memory.component,
            assignment,
            target
          ) || ExternalParameterizedAutoResize.proves(
            memory.component,
            assignment,
            target
          )
        }
      }
    }
    val fixedResizeConsumerCoversAddressDomain =
      memoryHasFixedResizeConsumer &&
        retainedIsConcreteWitness &&
        retained.minimum >= native.maximum
    val driverProvesNativeWidth = address match {
      case _: BaseType =>
        val widths = addressAssignments.flatMap { assignment =>
          assignment.source match {
            case value: Expression with WidthProvider =>
              Some(widthOf(value, source))
            case _ => None
          }
        }
        val distinct = distinctWidths(widths)
        distinct.size == 1 &&
        (equivalentWidth(distinct.head, native) ||
          provesAddressCapacityOnMemoryOwner(
            distinct.head,
            native,
            memory,
            source
          ))
      case _ => false
    }
    if (
      native.parameters.nonEmpty &&
      retainedIsConcreteWitness &&
      native.default == retained.default &&
      driverProvesNativeWidth &&
      !fixedResizeConsumerCoversAddressDomain
    ) native
    else retained
  }

  private def provesAddressCapacityOnMemoryOwner(
      candidate: ElaborationIntegerExpression,
      required: ElaborationIntegerExpression,
      memory: Mem[_],
      source: Option[String]
  ): Boolean =
    (candidate.exactDomain, required.exactDomain) match {
      case (Some(candidateDomain), Some(requiredDomain)) if candidateDomain.root eq requiredDomain.root =>
        val candidateEvaluation = ParameterizedStructure
          .projectedMemoryEvaluationOf(
            memory.component,
            memory,
            candidate,
            s"memory '${memory.getName()}' address driver width",
            source.orElse(candidate.sourceLocation)
          )
        val requiredEvaluation = ParameterizedStructure
          .projectedMemoryEvaluationOf(
            memory.component,
            memory,
            required,
            s"memory '${memory.getName()}' required address width",
            source.orElse(required.sourceLocation)
          )
        (candidateEvaluation, requiredEvaluation) match {
          case (Some(actual), Some(needed)) if actual.rootValues == needed.rootValues =>
            val actualByRoot = actual.results.toMap
            needed.results.forall { case (rootValue, width) =>
              actualByRoot.get(rootValue).exists(_ >= width)
            }
          case _ => false
        }
      case _ => false
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
    val exact =
      provesAddressCapacityOnMemoryOwner(
        width,
        nativeMemoryAddressWidth(depth, source),
        memory,
        source
      )
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
    val distinct = distinctWidths(retained.toVector)
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
      left.parameters.sortBy(_.name) == right.parameters.sortBy(_.name) &&
      sameParameterRoots(left, right) &&
      ((left.exactDomain, right.exactDomain) match {
        case (None, None) => true
        case (Some(l), Some(r)) =>
          (l.root eq r.root) && l.parameter == r.parameter &&
          l.universe == r.universe && l.evaluations == r.evaluations
        case _ => false
      }) &&
      ((left.projectionProvenance, right.projectionProvenance) match {
        case (None, None)                      => true
        case (Some(l), Some(r))                => l.sameAs(r)
        case (Some(_), None) | (None, Some(_)) => false
      })

  private def sameParameterRoots(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean = {
    val leftRoots = distinctParameterRoots(left.completedParameterRoots)
    val rightRoots = distinctParameterRoots(right.completedParameterRoots)
    leftRoots.size == rightRoots.size &&
    leftRoots.forall(root => rightRoots.exists(_ eq root))
  }

  private def distinctParameterRoots(
      roots: Vector[ElaborationIntegerParameterRoot]
  ): Vector[ElaborationIntegerParameterRoot] =
    roots.foldLeft(Vector.empty[ElaborationIntegerParameterRoot]) {
      case (known, root) if known.exists(_ eq root) => known
      case (known, root)                            => known :+ root
    }

  private def distinctWidths(
      values: Vector[ElaborationIntegerExpression]
  ): Vector[ElaborationIntegerExpression] =
    values.foldLeft(Vector.empty[ElaborationIntegerExpression]) {
      case (known, value) if known.exists(equivalentWidth(_, value)) => known
      case (known, value)                                            => known :+ value
    }

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
    case _: Bool if expected == 1                           =>
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

  private def rewriteAddressDeclarations(
      lines: Vector[String],
      plan: MemoryPlan,
      helperName: String
  ): Vector[String] = {
    val roles = Vector(
      plan.readAddress -> plan.readAddressWidth,
      plan.writeAddress -> plan.writeAddressWidth
    ).groupBy(_._1).toVector.sortBy(_._1).map { case (name, values) =>
      val widths = distinctWidths(values.map(_._2))
      if (widths.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-TYPE-MISMATCH",
          s"memory '${plan.memoryName}' address '$name' has incompatible retained declaration widths",
          plan.sourceLocation
        )
      }
      name -> widths.head
    }
    roles.foldLeft(lines) { case (current, (name, width)) =>
      rewriteAddressDeclaration(
        current,
        plan,
        name,
        width,
        helperName
      )
    }
  }

  private def rewriteAddressDeclaration(
      lines: Vector[String],
      plan: MemoryPlan,
      name: String,
      width: ElaborationIntegerExpression,
      helperName: String
  ): Vector[String] = {
    if (width.parameters.isEmpty) return lines
    val declaration =
      "^\\s*(?:input|output|inout|wire|reg)\\b".r
    val candidates = lines.zipWithIndex.collect {
      case (line, index)
          if declaration.findFirstIn(line).nonEmpty &&
            containsIdentifier(line, name) =>
        index
    }
    if (candidates.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-DECLARATION-NOT-FOUND",
        s"normal Verilog emission contains ${candidates.size} declarations matching memory address '$name' for '${plan.memoryName}'",
        plan.sourceLocation
      )
    }

    val index = candidates.head
    val line = lines(index)
    val nameMatch = identifierPattern(name).findFirstMatchIn(line).get
    var prefix = line.substring(0, nameMatch.start)
    val suffix = line.substring(nameMatch.end)
    val packed = "\\[[^\\]]+\\]\\s*$".r
    val range = s"[${render(width, helperName)}-1:0]"
    packed.findFirstMatchIn(prefix) match {
      case Some(value) =>
        prefix = prefix.substring(0, value.start) + range + " "
      case None =>
        prefix = prefix + range + " "
    }
    lines.updated(index, prefix + name + suffix)
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
  ): Vector[String] =
    rewriteMemoryDeclaration(
      lines,
      plan.memory,
      plan.memoryName,
      plan.metadata,
      helperName,
      plan.sourceLocation
    )

  private def rewriteMemoryDeclaration(
      lines: Vector[String],
      memory: Mem[_],
      memoryName: String,
      metadata: ParameterizedMemoryMetadata,
      helperName: String,
      sourceLocation: Option[String]
  ): Vector[String] = {
    val concreteRange = ("\\[0\\s*:\\s*" + (memory.wordCount - 1) + "\\]").r
    val candidates = lines.zipWithIndex.collect {
      case (line, index)
          if containsIdentifier(line, memoryName) &&
            concreteRange.findFirstIn(line).nonEmpty && line.contains("reg") =>
        index
    }
    if (candidates.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DECLARATION-NOT-FOUND",
        s"normal Verilog emission contains ${candidates.size} declarations matching memory '$memoryName'",
        sourceLocation
      )
    }
    val index = candidates.head
    val line = lines(index)
    val namePattern = identifierPattern(memoryName)
    val nameMatch = namePattern.findFirstMatchIn(line).get
    var prefix = line.substring(0, nameMatch.start)
    var suffix = line.substring(nameMatch.end)

    if (metadata.elementWidth.parameters.nonEmpty) {
      val packed = "\\[[^\\]]+\\]\\s*$".r
      val range = s"[${render(metadata.elementWidth, helperName)}-1:0]"
      packed.findFirstMatchIn(prefix) match {
        case Some(value) =>
          prefix = prefix.substring(0, value.start) + range + " "
        case None =>
          prefix = prefix + range + " "
      }
    }
    if (metadata.depth.parameters.nonEmpty) {
      val depthRange = s"[0:${render(metadata.depth, helperName)}-1]"
      suffix = concreteRange.replaceFirstIn(
        suffix,
        Matcher.quoteReplacement(depthRange)
      )
    }
    lines.updated(index, prefix + memoryName + suffix)
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

  private def independentDontCareProcessesAreComplete(
      lines: Vector[String],
      blocks: Vector[LineRange],
      plan: MemoryPlan
  ): Boolean = {
    if (!plan.independentDontCare || blocks.size != 2) return false
    val expectedHeader = s"always @(posedge ${plan.clock})"
    val texts = blocks.map { block =>
      val header = lines(block.start).replaceAll("\\s+", " ").trim
      if (!header.startsWith(expectedHeader)) return false
      lines.slice(block.start, block.endInclusive + 1).mkString("\n")
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

  private def insertPortableLogFunction(
      component: Component,
      lines: Vector[String],
      helperName: String
  ): Vector[String] = {
    val moduleLine = lines.indexWhere(
      _.trim.startsWith(s"module ${component.definitionName}")
    )
    val portEnd =
      if (moduleLine < 0) -1
      else
        (moduleLine + 1 until lines.size)
          .find(index => lines(index).trim == ");")
          .getOrElse(-1)
    if (portEnd < 0) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MODULE-HEADER-NOT-FOUND",
        s"normal Verilog emission did not contain a complete module header for '${component.definitionName}'"
      )
    }
    lines.patch(
      portEnd + 1,
      Vector("") ++ renderPortableLogFunction(helperName) ++ Vector(""),
      0
    )
  }

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

  /** Native Mem writes normalize UInt/SInt payloads through a transparent
    * same-width `.asBits` cast. Verilog needs only the named packed source, so
    * peel that ordinary native cast without inventing a separate memory data
    * path.
    */
  private def stablePackedName(
      value: AnyRef,
      role: String,
      source: Option[String]
  ): String = value match {
    case cast: CastBitVectorToBitVector if cast.input != null && cast.getWidth == cast.input.getWidth =>
      stableName(cast.input, role, source)
    case _ => stableName(value, role, source)
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

  private def containsIndexedAccess(
      value: String,
      name: String,
      index: String
  ): Boolean =
    ("(?<![A-Za-z0-9_$])" + Pattern.quote(name) + "\\s*\\[\\s*" +
      Pattern.quote(index) + "\\s*\\]").r
      .findFirstIn(value)
      .nonEmpty

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
