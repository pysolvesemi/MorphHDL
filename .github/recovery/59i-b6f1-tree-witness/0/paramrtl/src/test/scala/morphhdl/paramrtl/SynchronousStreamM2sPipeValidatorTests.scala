package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateFor, SynchronousStreamM2sPipe}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import org.scalatest.funsuite.AnyFunSuite

class SynchronousStreamM2sPipeValidatorTests extends AnyFunSuite {
  private val Prefix = "PRTL-SYNCHRONOUS-STREAM-M2S-PIPE"

  test("accepts a sole atomic pipe with exact ready-valid roles") {
    assert(ParamRtlValidator.validate(pipeDesign()).isRight)
    assert(ParamRtlValidator.validate(pipeDesign(signed = true)).isRight)
  }

  test("requires exact role directions types aliases and output ownership") {
    val base = pipeDesign().modules.head
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

    assertCodes(pipeDesign(popReady = "push_valid"), s"$Prefix-ROLE-ALIAS")
    assertCodes(
      pipeDesign(popData = "push_data"),
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
    val base = pipeDesign().modules.head
    val pipe = base.items.head.asInstanceOf[SynchronousStreamM2sPipe]
    assertCodes(
      Design(base.name, Vector(base.copy(items = base.items :+
        ContinuousAssign(Ref("push_ready"), Ref("push_valid"))))),
      s"$Prefix-MIXED-ITEMS-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )
    assertCodes(
      Design(base.name, Vector(base.copy(items = Vector(
        pipe,
        pipe.copy(label = "p_other")
      )))),
      s"$Prefix-MULTIPLE-PIPES-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )
    assertCodes(
      Design(base.name, Vector(base.copy(items = Vector(
        GenerateFor("g_pipe", "i", Literal(1), Vector(pipe))
      )))),
      "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )

    val swapped = pipe.copy(pushValid = pipe.popReady, popReady = pipe.pushValid)
    val forward = Design(base.name, Vector(base.copy(items = Vector(pipe, swapped))))
    val reverse = Design(base.name, Vector(base.copy(items = Vector(swapped, pipe))))
    assert(
      ParamRtlValidator.validate(forward).left.map(_.values) ==
        ParamRtlValidator.validate(reverse).left.map(_.values)
    )
  }

  private def pipeDesign(
      label: String = "p_m2s_pipe",
      clock: String = "clk",
      reset: String = "reset",
      pushValid: String = "push_valid",
      pushReady: String = "push_ready",
      pushData: String = "push_data",
      popValid: String = "pop_valid",
      popReady: String = "pop_ready",
      popData: String = "pop_data",
      signed: Boolean = false
  ): Design = {
    val elementType = PackedBits(ParameterRef("WIDTH"), if (signed) Signed else Unsigned)
    val module = ModuleDef(
      "SynchronousStreamM2sPipe",
      Vector(IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32)))),
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
      Vector(SynchronousStreamM2sPipe(
        label,
        Ref(clock),
        Ref(reset),
        Ref(pushValid),
        Ref(pushReady),
        Ref(pushData),
        Ref(popValid),
        Ref(popReady),
        Ref(popData),
        elementType
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
