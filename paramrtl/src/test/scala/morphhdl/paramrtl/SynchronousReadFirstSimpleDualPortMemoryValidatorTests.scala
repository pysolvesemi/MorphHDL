package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{AddressWidth, Literal, LocalParameterRef, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{
  ContinuousAssign,
  GenerateFor,
  SynchronousReadFirstSimpleDualPortMemory,
  SynchronousReadFirstSinglePortMemory
}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import org.scalatest.funsuite.AnyFunSuite

class SynchronousReadFirstSimpleDualPortMemoryValidatorTests extends AnyFunSuite {
  private val Prefix = "PRTL-SYNCHRONOUS-READ-FIRST-SIMPLE-DUAL-PORT-MEMORY"

  test("accepts correlated and conservative overwide independent address ports") {
    assert(ParamRtlValidator.validate(memoryDesign()).isRight)
    assert(
      ParamRtlValidator
        .validate(memoryDesign(readAddressWidth = Literal(4), writeAddressWidth = Literal(4)))
        .isRight
    )

    val localBase = memoryDesign(
      readAddressWidth = AddressWidth(LocalParameterRef("RAW_DEPTH")),
      writeAddressWidth = AddressWidth(LocalParameterRef("RAW_DEPTH"))
    ).modules.head
    val throughLocal = localBase.copy(
      localParameters = Vector(IntegerLocalParameter("RAW_DEPTH", ParameterRef("DEPTH")))
    )
    assert(ParamRtlValidator.validate(Design(throughLocal.name, Vector(throughLocal))).isRight)
    assert(
      ParamRtlValidator
        .validate(
          memoryDesign(
            depth = Literal(1),
            readAddressWidth = Literal(1),
            writeAddressWidth = Literal(1),
            parameters = widthParameter
          )
        )
        .isRight
    )
  }

  test("requires matching unsigned address types and proves each capacity independently") {
    assertCodes(
      memoryDesign(readAddressWidth = Literal(2), writeAddressWidth = Literal(3)),
      s"$Prefix-ADDRESS-TYPE-MISMATCH",
      s"$Prefix-READ-ADDRESS-CAPACITY-NOT-PROVEN"
    )
    assertCodes(
      memoryDesign(readAddressWidth = Literal(3), writeAddressWidth = Literal(2)),
      s"$Prefix-ADDRESS-TYPE-MISMATCH",
      s"$Prefix-WRITE-ADDRESS-CAPACITY-NOT-PROVEN"
    )
    val bothNarrow = diagnosticCodes(
      memoryDesign(readAddressWidth = Literal(2), writeAddressWidth = Literal(2))
    )
    assert(bothNarrow.contains(s"$Prefix-READ-ADDRESS-CAPACITY-NOT-PROVEN"))
    assert(bothNarrow.contains(s"$Prefix-WRITE-ADDRESS-CAPACITY-NOT-PROVEN"))
    assert(!bothNarrow.contains(s"$Prefix-ADDRESS-TYPE-MISMATCH"))

    val base = memoryDesign().modules.head
    val bothSigned = Design(
      base.name,
      Vector(base.copy(ports = base.ports.map {
        case port if port.name == "read_address" || port.name == "write_address" =>
          port.copy(dataType = port.dataType.copy(signedness = Signed))
        case port => port
      }))
    )
    val signedCodes = diagnosticCodes(bothSigned)
    assert(signedCodes.contains(s"$Prefix-READ-ADDRESS-TYPE-MISMATCH"))
    assert(signedCodes.contains(s"$Prefix-WRITE-ADDRESS-TYPE-MISMATCH"))
    assert(!signedCodes.contains(s"$Prefix-ADDRESS-TYPE-MISMATCH"))
  }

  test("requires exact controls data directions roles and sole output ownership") {
    val base = memoryDesign().modules.head
    val wideReadEnable = Design(
      base.name,
      Vector(base.copy(ports = base.ports.map {
        case port if port.name == "read_enable" =>
          port.copy(dataType = PackedBits(Literal(2), Unsigned))
        case port => port
      }))
    )
    assertCodes(wideReadEnable, s"$Prefix-READ-ENABLE-TYPE-MISMATCH")

    val badWriteData = Design(
      base.name,
      Vector(base.copy(ports = base.ports.map {
        case port if port.name == "write_data" =>
          port.copy(dataType = PackedBits(Literal(7), Unsigned))
        case port => port
      }))
    )
    assertCodes(badWriteData, s"$Prefix-WRITE-DATA-TYPE-MISMATCH")

    assertCodes(
      memoryDesign(readEnable = "write_enable"),
      s"$Prefix-ROLE-ALIAS"
    )
    assertCodes(
      memoryDesign(readData = "write_data"),
      s"$Prefix-READ-DATA-NOT-OUTPUT",
      s"$Prefix-ROLE-ALIAS",
      "PRTL-UNDRIVEN-OUTPUT"
    )

    val extraOutput = base.copy(
      ports = base.ports :+ Port("extra", Output, PackedBits(ParameterRef("WIDTH"), Unsigned))
    )
    assertCodes(
      Design(extraOutput.name, Vector(extraOutput)),
      s"$Prefix-OUTPUT-SHAPE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )
  }

  test("requires positive finite depth and rejects every non-sole placement") {
    assertCodes(
      memoryDesign(depth = Literal(0), parameters = widthParameter),
      "PRTL-MEMORY-DEPTH-NOT-POSITIVE"
    )
    assertCodes(
      memoryDesign(
        parameters = widthParameter :+
          IntegerParameter("DEPTH", 5, Vector(MinInclusive(1)))
      ),
      "PRTL-MEMORY-DEPTH-UPPER-BOUND-NOT-PROVEN"
    )
    assertCodes(
      memoryDesign(writeAddress = "missing_write_address"),
      "PRTL-UNRESOLVED-RTL-REFERENCE",
      s"$Prefix-WRITE-ADDRESS-CAPACITY-NOT-PROVEN"
    )

    val base = memoryDesign().modules.head
    val memory = base.items.head.asInstanceOf[SynchronousReadFirstSimpleDualPortMemory]
    assertCodes(
      Design(base.name, Vector(base.copy(items = base.items :+ ContinuousAssign(Ref("read_data"), Ref("write_data"))))),
      s"$Prefix-MIXED-ITEMS-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )
    assertCodes(
      Design(
        base.name,
        Vector(base.copy(items = Vector(memory, memory.copy(label = "p_other", memoryName = "other"))))
      ),
      s"$Prefix-MULTIPLE-MEMORIES-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )
    assertCodes(
      Design(
        base.name,
        Vector(base.copy(items = Vector(GenerateFor("g_memory", "i", Literal(1), Vector(memory)))))
      ),
      "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )

    val single = SynchronousReadFirstSinglePortMemory(
      "p_single",
      "memory",
      Ref("clk"),
      Ref("read_enable"),
      Ref("write_enable"),
      Ref("read_address"),
      Ref("write_data"),
      Ref("read_data"),
      PackedBits(ParameterRef("WIDTH"), Unsigned),
      ParameterRef("DEPTH")
    )
    assertCodes(
      Design(base.name, Vector(base.copy(items = Vector(memory, single)))),
      "PRTL-DUPLICATE-MEMORY-NAME",
      s"$Prefix-MIXED-ITEMS-UNSUPPORTED"
    )

    val swapped = memory.copy(
      readAddress = memory.writeAddress,
      writeAddress = memory.readAddress
    )
    val forward = Design(base.name, Vector(base.copy(items = Vector(memory, swapped))))
    val reverse = Design(base.name, Vector(base.copy(items = Vector(swapped, memory))))
    assert(
      ParamRtlValidator.validate(forward).left.map(_.values) ==
        ParamRtlValidator.validate(reverse).left.map(_.values)
    )
  }

  private def widthParameter = Vector(
    IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32)))
  )

  private def memoryDesign(
      label: String = "p_memory",
      memoryName: String = "memory",
      clock: String = "clk",
      readEnable: String = "read_enable",
      writeEnable: String = "write_enable",
      readAddress: String = "read_address",
      writeAddress: String = "write_address",
      writeData: String = "write_data",
      readData: String = "read_data",
      readAddressWidth: IntExpr = AddressWidth(ParameterRef("DEPTH")),
      writeAddressWidth: IntExpr = AddressWidth(ParameterRef("DEPTH")),
      depth: IntExpr = ParameterRef("DEPTH"),
      parameters: Vector[IntegerParameter] = Vector(
        IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32))),
        IntegerParameter("DEPTH", 5, Vector(MinInclusive(1), MaxInclusive(8)))
      )
  ): Design = {
    val elementType = PackedBits(ParameterRef("WIDTH"), Unsigned)
    val module = ModuleDef(
      "SimpleDualPortMemory",
      parameters,
      Vector(
        Port("clk", Input, PackedBits(Literal(1), Unsigned)),
        Port("read_enable", Input, PackedBits(Literal(1), Unsigned)),
        Port("write_enable", Input, PackedBits(Literal(1), Unsigned)),
        Port("read_address", Input, PackedBits(readAddressWidth, Unsigned)),
        Port("write_address", Input, PackedBits(writeAddressWidth, Unsigned)),
        Port("write_data", Input, elementType),
        Port("read_data", Output, elementType)
      ),
      Vector(SynchronousReadFirstSimpleDualPortMemory(
        label,
        memoryName,
        Ref(clock),
        Ref(readEnable),
        Ref(writeEnable),
        Ref(readAddress),
        Ref(writeAddress),
        Ref(writeData),
        Ref(readData),
        elementType,
        depth
      ))
    )
    Design(module.name, Vector(module))
  }

  private def diagnosticCodes(design: Design): Vector[String] =
    ParamRtlValidator.validate(design) match {
      case Left(diagnostics) => diagnostics.codes
      case Right(_)          => fail("Expected validation diagnostics")
    }

  private def assertCodes(design: Design, expected: String*): Unit = {
    val actual = diagnosticCodes(design)
    expected.foreach(code => assert(actual.contains(code), actual.mkString("\n")))
  }
}
