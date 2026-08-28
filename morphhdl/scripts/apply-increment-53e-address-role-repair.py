#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ParameterizedVerilogMemories.scala"
)
value = path.read_text()

# Add a graph-backed address role. Named BaseTypes retain declaration rewriting;
# an explicit native UInt truncation renders from its AST input and retained
# target width instead of depending on the anonymous emitter wrapper name.
role_marker = '  private val PortableLogCall = "\\\\bclog2\\\\s*\\\\(".r\n\n'
if value.count(role_marker) != 1:
    raise SystemExit(f"address-role insertion marker count={value.count(role_marker)}")
role_code = '''  private sealed trait MemoryAddressRole {
    def declarationName: Option[String]
    def referenceNames: Vector[String]
    def concrete: String
    def description: String
    def sameAddress(other: MemoryAddressRole): Boolean
    def render(helperName: String): String
  }

  private final case class NamedMemoryAddress(
      node: BaseType,
      name: String
  ) extends MemoryAddressRole {
    override def declarationName: Option[String] = Some(name)
    override def referenceNames: Vector[String] = Vector(name)
    override def concrete: String = name
    override def description: String = name
    override def sameAddress(other: MemoryAddressRole): Boolean = other match {
      case value: NamedMemoryAddress => value.node eq node
      case _                         => false
    }
    override def render(helperName: String): String = name
  }

  private final case class TruncatedMemoryAddress(
      node: ResizeUInt,
      input: BaseType,
      inputName: String,
      width: ElaborationIntegerExpression
  ) extends MemoryAddressRole {
    override def declarationName: Option[String] = None
    override def referenceNames: Vector[String] = Vector(inputName)
    override def concrete: String =
      s"$inputName[${node.getWidth - 1}:0]"
    override def description: String =
      s"$inputName[${width.verilog}-1:0]"
    override def sameAddress(other: MemoryAddressRole): Boolean = other match {
      case value: TruncatedMemoryAddress =>
        (value.input eq input) && value.node.getWidth == node.getWidth
      case _ => false
    }
    override def render(helperName: String): String =
      s"$inputName[${ParameterizedVerilogMemories.render(width, helperName)}-1:0]"
  }

'''
value = value.replace(role_marker, role_marker + role_code, 1)

old_fields = '''      readAddress: String,
      writeAddress: String,
'''
new_fields = '''      readAddress: MemoryAddressRole,
      writeAddress: MemoryAddressRole,
'''
if value.count(old_fields) != 1:
    raise SystemExit(f"MemoryPlan address field marker count={value.count(old_fields)}")
value = value.replace(old_fields, new_fields, 1)

# Recover the native address width through direct driver width provenance and
# explicit ResizeUInt/tagAutoResize nodes. The latter remains authoritative even
# when stale retained metadata still describes the pre-resize source width.
select_start_marker = "  private def selectAddressWidth(\n"
select_end_marker = "  private def validateAddressWidth(\n"
if value.count(select_start_marker) != 1 or value.count(select_end_marker) != 1:
    raise SystemExit("selectAddressWidth/validateAddressWidth boundaries are ambiguous")
select_start = value.index(select_start_marker)
select_end = value.index(select_end_marker, select_start)
select_replacement = '''  private def directDriverExpressions(
      target: BaseType,
      component: Component
  ): Vector[Expression] = {
    val assignments = ArrayBuffer.empty[DataAssignmentStatement]
    component.dslBody.walkLeafStatements {
      case value: DataAssignmentStatement if value.finalTarget eq target =>
        assignments += value
      case _ =>
    }
    assignments.toVector.flatMap { assignment =>
      assignment.source match {
        case expression: Expression => Some(expression)
        case _                      => None
      }
    }
  }

  private def explicitlyResizedToWidth(
      expression: Expression,
      expectedWidth: Int,
      component: Component
  ): Boolean = {
    val seen = new IdentityHashMap[Expression, java.lang.Boolean]()

    def walk(value: Expression): Boolean = {
      if (seen.put(value, java.lang.Boolean.TRUE) != null) false
      else {
        value match {
          case resize: ResizeUInt =>
            resize.getWidth == expectedWidth
          case base: BaseType if base.hasTag(tagAutoResize) =>
            base.getBitsWidth == expectedWidth
          case base: BaseType =>
            val drivers = directDriverExpressions(base, component)
            drivers.size == 1 && walk(drivers.head)
          case other =>
            val children = ArrayBuffer.empty[Expression]
            other.foreachExpression(children += _)
            children.size == 1 && walk(children.head)
        }
      }
    }

    walk(expression)
  }

  private def selectAddressWidth(
      address: Expression with WidthProvider,
      retained: ElaborationIntegerExpression,
      native: ElaborationIntegerExpression,
      component: Component,
      source: Option[String]
  ): ElaborationIntegerExpression = {
    val retainedIsConcreteWitness =
      retained.parameters.isEmpty &&
      retained.default == BigInt(address.getWidth) &&
      retained.minimum == retained.default &&
      retained.maximum == retained.default

    val driverExpressions = address match {
      case target: BaseType => directDriverExpressions(target, component)
      case expression       => Vector(expression)
    }

    val retainedDriverWidths = driverExpressions.flatMap {
      case value: Expression with WidthProvider => Some(widthOf(value, source))
      case _                                     => None
    }.distinct
    val widthExpressionProvesNative =
      retainedDriverWidths.size == 1 &&
      equivalentWidth(retainedDriverWidths.head, native)

    val explicitResizeProvesNative =
      native.default.isValidInt &&
      address.getWidth == native.default.toInt &&
      explicitlyResizedToWidth(address, native.default.toInt, component)

    val concreteDriverProvesNative =
      retainedIsConcreteWitness &&
      native.default == retained.default &&
      widthExpressionProvesNative

    if (
      native.parameters.nonEmpty &&
      (explicitResizeProvesNative || concreteDriverProvesNative)
    ) native
    else retained
  }

  private def memoryAddressRole(
      address: Expression with WidthProvider,
      width: ElaborationIntegerExpression,
      role: String,
      source: Option[String]
  ): MemoryAddressRole = address match {
    case base: BaseType =>
      NamedMemoryAddress(base, stableName(base, role, source))
    case resize: ResizeUInt =>
      if (!width.default.isValidInt || resize.getWidth != width.default.toInt) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-RESIZE-WIDTH-MISMATCH",
          s"$role explicit UInt resize width ${resize.getWidth} does not match retained default '${width.default}'",
          source
        )
      }
      if (resize.getWidth >= resize.input.getWidth) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-EXPRESSION-UNSUPPORTED",
          s"$role requires a narrowing native UInt resize; received ${resize.input.getWidth} to ${resize.getWidth} bits",
          source
        )
      }
      resize.input match {
        case input: BaseType =>
          TruncatedMemoryAddress(
            resize,
            input,
            stableName(input, s"$role resize input", source),
            width
          )
        case other =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-EXPRESSION-UNSUPPORTED",
            s"$role native UInt resize input '${other.getClass.getName}' is not one named AST value",
            source
          )
      }
    case other =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-EXPRESSION-UNSUPPORTED",
        s"$role expression '${other.getClass.getName}' is neither a named UInt nor an explicit narrowing UInt resize",
        source
      )
  }

'''
value = value[:select_start] + select_replacement + value[select_end:]

# Treat the native address-width helper and the portable clog2 helper as the
# same complete function while retaining exact defaults, bounds and parameters.
equivalent_start_marker = "  private def equivalentWidth(\n"
equivalent_end_marker = "  private def requireType(\n"
if value.count(equivalent_start_marker) != 1 or value.count(equivalent_end_marker) != 1:
    raise SystemExit("equivalentWidth/requireType boundaries are ambiguous")
equivalent_start = value.index(equivalent_start_marker)
equivalent_end = value.index(equivalent_end_marker, equivalent_start)
equivalent_replacement = '''  private def canonicalWidthVerilog(value: String): String = {
    val normalized = compact(value)
    val addressWidthPrefix = "morphhdl_address_width("
    if (
      normalized.startsWith(addressWidthPrefix) &&
      normalized.endsWith(")")
    ) {
      val operand = normalized.substring(
        addressWidthPrefix.length,
        normalized.length - 1
      )
      s"clog2($operand,1)"
    } else normalized
  }

  private def equivalentWidth(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean =
    canonicalWidthVerilog(left.verilog) == canonicalWidthVerilog(right.verilog) &&
      left.default == right.default && left.minimum == right.minimum &&
      left.maximum == right.maximum &&
      left.parameters.sortBy(_.name) == right.parameters.sortBy(_.name)

'''
value = value[:equivalent_start] + equivalent_replacement + value[equivalent_end:]

old_roles = '''    val readTarget = stableName(read, "synchronous read result", source)
    val readAddress = stableName(read.address, "read address", source)
    val writeAddress = stableName(write.address, "write address", source)
    val independentDontCare =
      (read.readUnderWrite eq dontCare) && readAddress != writeAddress
'''
new_roles = '''    val readTarget = stableName(read, "synchronous read result", source)
    val readAddress = memoryAddressRole(
      read.address,
      readAddressWidth,
      "read address",
      source
    )
    val writeAddress = memoryAddressRole(
      write.address,
      writeAddressWidth,
      "write address",
      source
    )
    val sameAddress = readAddress.sameAddress(writeAddress)
    val independentDontCare =
      (read.readUnderWrite eq dontCare) && !sameAddress
'''
if value.count(old_roles) != 1:
    raise SystemExit(f"address role analysis marker count={value.count(old_roles)}")
value = value.replace(old_roles, new_roles, 1)

old_alias = '''    if (nonAddressRoles.contains(readAddress) || nonAddressRoles.contains(writeAddress)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ROLE-ALIAS",
        s"memory '$memoryName' address roles cannot alias its clock, enable, data, result or storage roles",
        source
      )
    }
'''
new_alias = '''    val addressReferenceNames =
      (readAddress.referenceNames ++ writeAddress.referenceNames).distinct
    if (addressReferenceNames.exists(nonAddressRoles.contains)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ROLE-ALIAS",
        s"memory '$memoryName' address roles cannot alias its clock, enable, data, result or storage roles",
        source
      )
    }
'''
if value.count(old_alias) != 1:
    raise SystemExit(f"address alias marker count={value.count(old_alias)}")
value = value.replace(old_alias, new_alias, 1)

old_shared = "      sharedAddress = readAddress == writeAddress,\n"
if value.count(old_shared) != 1:
    raise SystemExit(f"shared-address marker count={value.count(old_shared)}")
value = value.replace(old_shared, "      sharedAddress = sameAddress,\n", 1)

rewrite_start_marker = "  private def rewriteAddressDeclarations(\n"
rewrite_end_marker = "  private def rewriteAddressDeclaration(\n"
if value.count(rewrite_start_marker) != 1 or value.count(rewrite_end_marker) != 1:
    raise SystemExit("rewriteAddressDeclarations boundaries are ambiguous")
rewrite_start = value.index(rewrite_start_marker)
rewrite_end = value.index(rewrite_end_marker, rewrite_start)
rewrite_replacement = '''  private def rewriteAddressDeclarations(
      lines: Vector[String],
      plan: MemoryPlan,
      helperName: String
  ): Vector[String] = {
    val roles = Vector(
      plan.readAddress.declarationName.map(_ -> plan.readAddressWidth),
      plan.writeAddress.declarationName.map(_ -> plan.writeAddressWidth)
    ).flatten.groupBy(_._1).toVector.sortBy(_._1).map {
      case (name, values) =>
        val widths = values.map(_._2).distinct
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
      rewriteAddressDeclaration(current, plan, name, width, helperName)
    }
  }

'''
value = value[:rewrite_start] + rewrite_replacement + value[rewrite_end:]

process_start_marker = "  private def renderProcess(\n"
process_end_marker = "  private def independentDontCareProcessesAreComplete(\n"
if value.count(process_start_marker) != 1 or value.count(process_end_marker) != 1:
    raise SystemExit("renderProcess boundaries are ambiguous")
process_start = value.index(process_start_marker)
process_end = value.index(process_end_marker, process_start)
process = value[process_start:process_end]
builder_marker = "    val lines = Vector.newBuilder[String]\n\n"
if process.count(builder_marker) != 1:
    raise SystemExit(f"renderProcess builder marker count={process.count(builder_marker)}")
process = process.replace(
    builder_marker,
    builder_marker +
    "    val readAddress = plan.readAddress.render(helperName)\n" +
    "    val writeAddress = plan.writeAddress.render(helperName)\n\n",
    1,
)
process = process.replace("${plan.readAddress}", "${readAddress}")
process = process.replace("${plan.writeAddress}", "${writeAddress}")
value = value[:process_start] + process + value[process_end:]

# The original native emitter may wrap an inline expression in an anonymous
# wire. Identify the two removed memory processes by graph-owned data/result and
# clock roles, not by matching that anonymous address name.
old_blocks = '''    val readBlocks = entries.zipWithIndex.collect {
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
'''
new_blocks = '''    val readBlocks = entries.zipWithIndex.collect {
      case ((_, text), index)
          if containsIdentifier(text, plan.readTarget) &&
            containsIndexedReference(text, plan.memoryName) =>
        index
    }
    val writeBlocks = entries.zipWithIndex.collect {
      case ((_, text), index)
          if containsIdentifier(text, plan.writeData) &&
            containsIndexedReference(text, plan.memoryName) =>
        index
    }
'''
if value.count(old_blocks) != 1:
    raise SystemExit(f"independent process marker count={value.count(old_blocks)}")
value = value.replace(old_blocks, new_blocks, 1)

path.write_text(value)
