package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateFor, SynchronousStreamFifo}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import org.scalatest.funsuite.AnyFunSuite

class SynchronousStreamFifoValidatorTests extends AnyFunSuite {
  private val Prefix = "PRTL-SYNCHRONOUS-STREAM-FIFO"

  test("accepts a sole FIFO with exact ready-valid roles and direct bounded depth") {
    assert(ParamRtlValidator.validate(fifoDesign()).isRight)
    assert(ParamRtlValidator.validate(fifoDesign(signed = true)).isRight)
    assert(
      ParamRtlValidator
        .validate(fifoDesign(parameters = boundedParameters(depthDefault = 1, depthMaximum = 1)))
        .isRight
    )
  }

  test("requires direct positive finitely bounded public depth") {
    assertCodes(
      fifoDesign(depth = Add(ParameterRef("DEPTH"), Literal(0))),
      s"$Prefix-DEPTH-NOT-DIRECT-PUBLIC-PARAMETER"
    )
    assertCodes(
      fifoDesign(parameters = boundedParameters(depthMinimum = 0)),
      s"$Prefix-DEPTH-NOT-PROVEN-POSITIVE"
    )
    assertCodes(
      fifoDesign(parameters = Vector(
        IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32))),
        IntegerParameter("DEPTH", 5, Vector(MinInclusive(1)))
      )),
      s"$Prefix-DEPTH-NOT-FINITELY-BOUNDED"
    )
  }

  test("requires exact role directions types aliases and output ownership") {
    val base = fifoDesign().modules.head
    val wideReady = Design(base.name, Vector(base.copy(ports = base.ports.map {
      case port if port.name == "push_ready" =>
        port.copy(dataType = PackedBits(Literal(2), Unsigned))
      case port => port
    })))
    assertCodes(wideReady, s"$Prefix-PUSH-READY-TYPE-MISMATCH")

    val badPushData = Design(base.name, Vector(base.copy(ports = base.ports.map {
      case port if port.name == "push_data" =>
        port.copy(dataType = PackedBits(Literal(7), Unsigned))
      case port => port
    })))
    assertCodes(badPushData, s"$Prefix-PUSH-DATA-TYPE-MISMATCH")

    assertCodes(
      fifoDesign(popReady = "push_valid"),
      s"$Prefix-ROLE-ALIAS"
    )
    assertCodes(
      fifoDesign(popData = "push_data"),
      s"$Prefix-POP-DATA-NOT-OUTPUT",
      s"$Prefix-ROLE-ALIAS",
      s"$Prefix-OUTPUT-SHAPE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )

    val extraOutput = base.copy(
      ports = base.ports :+ Port("extra", Output, PackedBits(Literal(1), Unsigned))
    )
    assertCodes(
      Design(extraOutput.name, Vector(extraOutput)),
      s"$Prefix-OUTPUT-SHAPE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )
  }

  test("rejects every non-sole placement and remains construction-order deterministic") {
    val base = fifoDesign().modules.head
    val fifo = base.items.head.asInstanceOf[SynchronousStreamFifo]
    assertCodes(
      Design(base.name, Vector(base.copy(items = base.items :+
        ContinuousAssign(Ref("push_ready"), Ref("push_valid"))))),
      s"$Prefix-MIXED-ITEMS-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )
    assertCodes(
      Design(base.name, Vector(base.copy(items = Vector(
        fifo,
        fifo.copy(label = "p_other", memoryName = "other_memory")
      )))),
      s"$Prefix-MULTIPLE-FIFOS-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )
    assertCodes(
      Design(base.name, Vector(base.copy(items = Vector(
        GenerateFor("g_fifo", "i", Literal(1), Vector(fifo))
      )))),
      "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )

    val swapped = fifo.copy(pushValid = fifo.popReady, popReady = fifo.pushValid)
    val forward = Design(base.name, Vector(base.copy(items = Vector(fifo, swapped))))
    val reverse = Design(base.name, Vector(base.copy(items = Vector(swapped, fifo))))
    assert(
      ParamRtlValidator.validate(forward).left.map(_.values) ==
        ParamRtlValidator.validate(reverse).left.map(_.values)
    )
  }

  private def boundedParameters(
      depthDefault: BigInt = 5,
      depthMinimum: BigInt = 1,
      depthMaximum: BigInt = 8
  ): Vector[IntegerParameter] =
    Vector(
      IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32))),
      IntegerParameter(
        "DEPTH",
        depthDefault,
        Vector(MinInclusive(depthMinimum), MaxInclusive(depthMaximum))
      )
    )

  private def fifoDesign(
      label: String = "p_fifo",
      memoryName: String = "memory",
      clock: String = "clk",
      reset: String = "reset",
      pushValid: String = "push_valid",
      pushReady: String = "push_ready",
      pushData: String = "push_data",
      popValid: String = "pop_valid",
      popReady: String = "pop_ready",
      popData: String = "pop_data",
      signed: Boolean = false,
      depth: IntExpr = ParameterRef("DEPTH"),
      parameters: Vector[IntegerParameter] = boundedParameters()
  ): Design = {
    val elementType = PackedBits(ParameterRef("WIDTH"), if (signed) Signed else Unsigned)
    val module = ModuleDef(
      "SynchronousStreamFifo",
      parameters,
      Vector(
        Port("clk", Input, PackedBits(Literal(1), Unsigned)),
        Port("reset", Input, PackedBits(Literal(1), Unsigned)),
        Port("push_valid", Input, PackedBits(Literal(1), Unsigned)),
        Port("push_ready", Output, PackedBits(Literal(1), Unsigned)),
        Port("push_data", Input, elementType),
        Port("pop_valid", Output, PackedBits(Literal(1), Unsigned)),
        Port("pop_ready", Input, PackedBits(Literal(1), Unsigned)),
        Port("pop_data", Output, elementType)
      ),
      Vector(SynchronousStreamFifo(
        label,
        memoryName,
        Ref(clock),
        Ref(reset),
        Ref(pushValid),
        Ref(pushReady),
        Ref(pushData),
        Ref(popValid),
        Ref(popReady),
        Ref(popData),
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
