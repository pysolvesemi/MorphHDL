package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, AddressWidth, Literal, LocalParameterRef, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{
  ContinuousAssign,
  GenerateFor,
  SynchronousReadFirstSinglePortMemory
}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import org.scalatest.funsuite.AnyFunSuite

class SynchronousReadFirstSinglePortMemoryValidatorTests extends AnyFunSuite {
  test("accepts a bounded parameterized synchronous read-first single-port memory") {
    assert(ParamRtlValidator.validate(memoryDesign()).isRight)
    assert(ParamRtlValidator.validate(memoryDesign(signed = true)).isRight)
  }

  test("requires a positive finitely bounded depth and sufficient address capacity") {
    val zeroDepth = memoryDesign(depth = Literal(0), parameters = widthParameter)
    assertCodes(zeroDepth, "PRTL-MEMORY-DEPTH-NOT-POSITIVE")

    val unboundedDepth = memoryDesign(
      parameters = widthParameter :+ IntegerParameter("DEPTH", 3, Vector(MinInclusive(1)))
    )
    assertCodes(
      unboundedDepth,
      "PRTL-MEMORY-DEPTH-UPPER-BOUND-NOT-PROVEN",
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-ADDRESS-CAPACITY-NOT-PROVEN"
    )

    val tooDeep = memoryDesign(
      addressWidth = Literal(2),
      parameters = widthParameter :+ IntegerParameter(
        "DEPTH",
        3,
        Vector(MinInclusive(1), MaxInclusive(5))
      )
    )
    assertCodes(
      tooDeep,
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-ADDRESS-CAPACITY-NOT-PROVEN"
    )

    val defaultFitsButDomainDoesNot = memoryDesign(
      addressWidth = ParameterRef("ADDRESS_WIDTH"),
      parameters = Vector(
        IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(31))),
        IntegerParameter("DEPTH", 5, Vector(MinInclusive(1), MaxInclusive(5))),
        IntegerParameter("ADDRESS_WIDTH", 3, Vector(MinInclusive(2), MaxInclusive(3)))
      )
    )
    assertCodes(
      defaultFitsButDomainDoesNot,
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-ADDRESS-CAPACITY-NOT-PROVEN"
    )

    assert(ParamRtlValidator.validate(
      memoryDesign(
        depth = Literal(1),
        addressWidth = Literal(1),
        parameters = widthParameter
      )
    ).isRight)
  }

  test("accepts an address width correlated to the dynamic memory depth") {
    assert(
      ParamRtlValidator.validate(
        memoryDesign(addressWidth = AddressWidth(ParameterRef("DEPTH")))
      ).isRight
    )
    assert(
      ParamRtlValidator.validate(
        memoryDesign(addressWidth = AddressWidth(Add(ParameterRef("DEPTH"), Literal(0))))
      ).isRight
    )

    val base = memoryDesign(addressWidth = AddressWidth(LocalParameterRef("RAW_DEPTH"))).modules.head
    val throughLocal = base.copy(
      localParameters = Vector(IntegerLocalParameter("RAW_DEPTH", Add(ParameterRef("DEPTH"), Literal(0))))
    )
    assert(ParamRtlValidator.validate(Design(throughLocal.name, Vector(throughLocal))).isRight)

    var expandedDepth: IntExpr = ParameterRef("DEPTH")
    (1 to 900).foreach { _ =>
      expandedDepth = Add(expandedDepth, Literal(1))
    }
    assert(
      ParamRtlValidator.validate(
        memoryDesign(
          depth = expandedDepth,
          addressWidth = AddressWidth(Add(ParameterRef("DEPTH"), Literal(900))),
          parameters = Vector(
            IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(31))),
            IntegerParameter("DEPTH", 3, Vector(MinInclusive(1), MaxInclusive(1000000000)))
          )
        )
      ).isRight
    )

    assertCodes(
      memoryDesign(addressWidth = AddressWidth(Add(ParameterRef("DEPTH"), Literal(1)))),
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-ADDRESS-CAPACITY-NOT-PROVEN"
    )
  }

  test("requires exact control, address and data port roles") {
    assertCodes(
      memoryDesign(readEnable = "missing_read_enable"),
      "PRTL-UNRESOLVED-RTL-REFERENCE"
    )

    val outputClock = memoryDesign(clock = "read_data")
    assertCodes(
      outputClock,
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-CLOCK-NOT-INPUT",
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-CLOCK-TYPE-MISMATCH",
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-ROLE-ALIAS"
    )

    val outputReadEnable = memoryDesign(readEnable = "read_data")
    assertCodes(
      outputReadEnable,
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-READ-ENABLE-NOT-INPUT",
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-READ-ENABLE-TYPE-MISMATCH",
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-ROLE-ALIAS"
    )

    val signedReadEnableBase = memoryDesign().modules.head
    val signedReadEnable = Design(
      signedReadEnableBase.name,
      Vector(signedReadEnableBase.copy(ports = signedReadEnableBase.ports.map {
        case port if port.name == "read_enable" =>
          port.copy(dataType = PackedBits(Literal(1), Signed))
        case port => port
      }))
    )
    assertCodes(
      signedReadEnable,
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-READ-ENABLE-TYPE-MISMATCH"
    )
    val wideReadEnable = Design(
      signedReadEnableBase.name,
      Vector(signedReadEnableBase.copy(ports = signedReadEnableBase.ports.map {
        case port if port.name == "read_enable" =>
          port.copy(dataType = PackedBits(Literal(2), Unsigned))
        case port => port
      }))
    )
    assertCodes(
      wideReadEnable,
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-READ-ENABLE-TYPE-MISMATCH"
    )

    val wideEnableBase = memoryDesign().modules.head
    val wideEnable = Design(wideEnableBase.name, Vector(wideEnableBase.copy(ports = wideEnableBase.ports.map {
      case port if port.name == "write_enable" =>
        port.copy(dataType = PackedBits(Literal(2), Unsigned))
      case port => port
    })))
    assertCodes(wideEnable, "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-WRITE-ENABLE-TYPE-MISMATCH")

    val signedAddressBase = memoryDesign().modules.head
    val signedAddress = Design(signedAddressBase.name, Vector(signedAddressBase.copy(ports = signedAddressBase.ports.map {
      case port if port.name == "address" => port.copy(dataType = port.dataType.copy(signedness = Signed))
      case port                            => port
    })))
    assertCodes(signedAddress, "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-ADDRESS-TYPE-MISMATCH")

    assertCodes(
      memoryDesign(writeData = "address"),
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-WRITE-DATA-TYPE-MISMATCH",
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-ROLE-ALIAS"
    )

    assertCodes(
      memoryDesign(readEnable = "write_enable"),
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-ROLE-ALIAS"
    )
  }

  test("requires both data ports to match the explicit element type") {
    val base = memoryDesign().modules.head
    val badWrite = Design(base.name, Vector(base.copy(ports = base.ports.map {
      case port if port.name == "write_data" => port.copy(dataType = PackedBits(Literal(7), Unsigned))
      case port                               => port
    })))
    assertCodes(badWrite, "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-WRITE-DATA-TYPE-MISMATCH")

    val badRead = Design(base.name, Vector(base.copy(ports = base.ports.map {
      case port if port.name == "read_data" => port.copy(dataType = port.dataType.copy(signedness = Signed))
      case port                              => port
    })))
    assertCodes(badRead, "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-READ-DATA-TYPE-MISMATCH")
  }

  test("requires the memory read data to be the sole module output") {
    val base = memoryDesign().modules.head
    val extraOutput = base.copy(
      ports = base.ports :+ Port("aux", Output, PackedBits(ParameterRef("WIDTH"), Unsigned))
    )
    assertCodes(
      Design(extraOutput.name, Vector(extraOutput)),
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-OUTPUT-SHAPE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )
  }

  test("rejects sibling items, multiple memories and memories nested in generate") {
    val base = memoryDesign().modules.head
    val memory = base.items.head.asInstanceOf[SynchronousReadFirstSinglePortMemory]
    val sibling = base.copy(items = base.items :+ ContinuousAssign(Ref("read_data"), Ref("write_data")))
    assertCodes(
      Design(sibling.name, Vector(sibling)),
      "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-MIXED-ITEMS-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )

    val multiple = base.copy(items = Vector(memory, memory.copy(label = "p_other", memoryName = "other")))
    assertCodes(
      Design(multiple.name, Vector(multiple)),
      "PRTL-MULTIPLE-SYNCHRONOUS-READ-FIRST-MEMORIES-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )

    val nested = base.copy(items = Vector(GenerateFor("g_memory", "i", Literal(1), Vector(memory))))
    assertCodes(
      Design(nested.name, Vector(nested)),
      "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )
  }

  test("validates memory and process identifiers and declaration collisions") {
    assertCodes(memoryDesign(label = "bad-label"), "PRTL-INVALID-IDENTIFIER")
    assertCodes(memoryDesign(memoryName = "bad-name"), "PRTL-INVALID-IDENTIFIER")
    assertCodes(memoryDesign(memoryName = "address"), "PRTL-DUPLICATE-DECLARATION")
    assertCodes(memoryDesign(label = "memory"), "PRTL-DUPLICATE-DECLARATION")
  }

  private def widthParameter = Vector(
    IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(31)))
  )

  private def memoryDesign(
      name: String = "SinglePortMemory",
      label: String = "p_memory",
      memoryName: String = "memory",
      clock: String = "clk",
      readEnable: String = "read_enable",
      writeEnable: String = "write_enable",
      address: String = "address",
      writeData: String = "write_data",
      readData: String = "read_data",
      signed: Boolean = false,
      addressWidth: IntExpr = Literal(3),
      depth: IntExpr = ParameterRef("DEPTH"),
      parameters: Vector[IntegerParameter] = Vector(
        IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(31))),
        IntegerParameter("DEPTH", 3, Vector(MinInclusive(1), MaxInclusive(5)))
      )
  ): Design = {
    val elementType = PackedBits(ParameterRef("WIDTH"), if (signed) Signed else Unsigned)
    val ports = Vector(
      Port("clk", Input, PackedBits(Literal(1), Unsigned)),
      Port("read_enable", Input, PackedBits(Literal(1), Unsigned)),
      Port("write_enable", Input, PackedBits(Literal(1), Unsigned)),
      Port("address", Input, PackedBits(addressWidth, Unsigned)),
      Port("write_data", Input, elementType),
      Port("read_data", Output, elementType)
    )
    val module = ModuleDef(
      name,
      parameters,
      ports,
      Vector(SynchronousReadFirstSinglePortMemory(
        label,
        memoryName,
        Ref(clock),
        Ref(readEnable),
        Ref(writeEnable),
        Ref(address),
        Ref(writeData),
        Ref(readData),
        elementType,
        depth
      ))
    )
    Design(module.name, Vector(module))
  }

  private def assertCodes(design: Design, expected: String*): Unit =
    ParamRtlValidator.validate(design) match {
      case Left(diagnostics) =>
        expected.foreach(code => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n")))
      case Right(_) => fail(s"Expected ${expected.mkString(", ")}")
    }
}
