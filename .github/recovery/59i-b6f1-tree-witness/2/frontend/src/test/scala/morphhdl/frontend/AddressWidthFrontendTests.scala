package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.BoolExpr.{LocalParameterRef => BoolLocalParameterRef}
import morphhdl.paramrtl.IntExpr.{
  Add,
  AddressWidth,
  Literal,
  LocalParameterRef,
  ParameterRef,
  Select
}
import morphhdl.paramrtl.ModuleItem.{ModuleInstance, SynchronousReadFirstSinglePortMemory}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class AddressWidthFrontendTests extends AnyFunSuite {
  test("computes exact witnesses and retains the unary symbolic expression") {
    val veryLarge = (BigInt(1) << 4096) + 1
    val cases = Vector(
      BigInt(1) -> BigInt(1),
      BigInt(2) -> BigInt(1),
      BigInt(3) -> BigInt(2),
      BigInt(5) -> BigInt(3),
      veryLarge -> BigInt(4097)
    )

    cases.foreach { case (value, expectedWidth) =>
      val input = HdlInt.literal(value)
      val width = input.addressWidth

      assert(width.witness == expectedWidth)
      assert(width.expression == AddressWidth(Literal(value)))
      assert(width.parameters.isEmpty)
      assert(width.booleanParameters.isEmpty)
      assert(width.localParameters.isEmpty)
      assert(width.booleanLocalParameters.isEmpty)
      assert(width.scope.isEmpty)
    }
  }

  test("rejects zero and negative witnesses at the operation source with an exact suggestion") {
    val expectedSuggestion =
      "Choose a positive concrete witness and declare the full symbolic input domain as strictly positive before using addressWidth."
    val zeroLine = sourcecode.Line() + 1
    val zero = intercept[FrontendException](HdlInt.literal(0).addressWidth)
    val negativeLine = sourcecode.Line() + 1
    val negative = intercept[FrontendException](HdlInt.literal(-7).addressWidth)

    Vector(zero -> zeroLine, negative -> negativeLine).foreach { case (error, expectedLine) =>
      assert(error.code == "MORPH-FRONTEND-ADDRESS-WIDTH-WITNESS-NONPOSITIVE")
      assert(error.origin.file.endsWith("AddressWidthFrontendTests.scala"))
      assert(error.origin.line == expectedLine)
      assert(error.detail.contains("positive concrete witness"))
      assert(error.suggestion == expectedSuggestion)
      assert(error.suggestedReplacement == expectedSuggestion)
      assert(error.getMessage.contains(s"Suggested replacement: $expectedSuggestion"))
    }
  }

  test("preserves public Boolean and local provenance through select addressWidth arithmetic and comparison") {
    val primary = HdlInt.param("PRIMARY_DEPTH", default = 5, min = 1, max = 17)
    val fallback = HdlInt.param("FALLBACK_DEPTH", default = 3, min = 1, max = 9)
    val enabled = HdlBool.param("USE_PRIMARY", default = true)
    val enabledLocal = localParam("LOCAL_USE_PRIMARY", enabled)
    val selected = enabledLocal.select(primary, fallback)
    val width = selected.addressWidth
    val padded = width + 1
    val compared = padded >= fallback.addressWidth

    assert(width.witness == 3)
    assert(
      width.expression == AddressWidth(
        Select(
          BoolLocalParameterRef("LOCAL_USE_PRIMARY"),
          ParameterRef("PRIMARY_DEPTH"),
          ParameterRef("FALLBACK_DEPTH")
        )
      )
    )
    assert(width.parameters == primary.parameters ++ fallback.parameters)
    assert(width.booleanParameters == enabled.parameters)
    assert(width.booleanLocalParameters == enabledLocal.booleanLocalParameters)
    assert(width.localParameters.isEmpty)
    assert(padded.expression == Add(width.expression, Literal(1)))
    assert(compared.integerParameters == primary.parameters ++ fallback.parameters)
    assert(compared.parameters == enabled.parameters)
    assert(compared.booleanLocalParameters == enabledLocal.booleanLocalParameters)
  }

  test("propagates addressWidth through ports local parameters and child bindings") {
    val primary = HdlInt.param("PRIMARY_DEPTH", default = 5, min = 1, max = 17)
    val fallback = HdlInt.param("FALLBACK_DEPTH", default = 3, min = 1, max = 9)
    val enabled = HdlBool.param("USE_PRIMARY", default = true)
    val enabledLocal = localParam("LOCAL_USE_PRIMARY", enabled)
    val selected = enabledLocal.select(primary, fallback)
    val addressWidth = localParam("ADDRESS_WIDTH", selected.addressWidth)
    val items = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        parameterBindings = Vector(parameterBinding("ADDRESS_WIDTH", addressWidth))
      )
    }

    val module = moduleDef(
      name = "AddressWidthConsumers",
      parameters = Vector(integerParameter(primary), integerParameter(fallback)),
      ports = Vector(port("address", Input, packedBits(addressWidth))),
      items = items,
      localParameters = Vector(integerLocalParameter(addressWidth)),
      booleanParameters = Vector(booleanParameter(enabled)),
      booleanLocalParameters = Vector(booleanLocalParameter(enabledLocal))
    )

    assert(module.localParameters == Vector(
      IntegerLocalParameter(
        "ADDRESS_WIDTH",
        AddressWidth(
          Select(
            BoolLocalParameterRef("LOCAL_USE_PRIMARY"),
            ParameterRef("PRIMARY_DEPTH"),
            ParameterRef("FALLBACK_DEPTH")
          )
        )
      )
    ))
    assert(module.ports.head.dataType == PackedBits(LocalParameterRef("ADDRESS_WIDTH"), Unsigned))
    assert(
      module.items == Vector(
        ModuleInstance(
          "child_inst",
          "Child",
          Vector(ParameterBinding("ADDRESS_WIDTH", LocalParameterRef("ADDRESS_WIDTH")))
        )
      )
    )
  }

  test("uses depth.addressWidth for a single-port-memory address port and retains memory provenance") {
    val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 17)
    val elementRange = HdlInt.param("ELEMENT_RANGE", default = 256, min = 1, max = 1024)
    val addressWidth = depth.addressWidth
    val elementWidth = elementRange.addressWidth
    val items = captureItems {
      emitSynchronousReadFirstSinglePortMemory(
        "p_memory",
        "memory",
        ref("clk"),
        ref("read_enable"),
        ref("write_enable"),
        ref("address"),
        ref("write_data"),
        ref("read_data"),
        packedBits(elementWidth),
        depth
      )
    }

    val module = moduleDef(
      name = "DerivedMemoryAddress",
      parameters = Vector(integerParameter(depth), integerParameter(elementRange)),
      ports = Vector(
        port("clk", Input, packedBits(1)),
        port("read_enable", Input, packedBits(1)),
        port("write_enable", Input, packedBits(1)),
        port("address", Input, packedBits(addressWidth)),
        port("write_data", Input, packedBits(elementWidth)),
        port("read_data", Output, packedBits(elementWidth))
      ),
      items = items
    )

    assert(module.ports(3).dataType.width == AddressWidth(ParameterRef("DEPTH")))
    assert(module.items == Vector(
      SynchronousReadFirstSinglePortMemory(
        "p_memory",
        "memory",
        Ref("clk"),
        Ref("read_enable"),
        Ref("write_enable"),
        Ref("address"),
        Ref("write_data"),
        Ref("read_data"),
        PackedBits(AddressWidth(ParameterRef("ELEMENT_RANGE")), Unsigned),
        ParameterRef("DEPTH")
      )
    ))
    assert(ParamRtlValidator.validate(Design(module.name, Vector(module))).isRight)
  }

  test("retains AddressWidth provenance when the derived value is itself a memory depth") {
    val capacity = HdlInt.param("CAPACITY", default = 17, min = 1, max = 257)
    val memoryDepth = capacity.addressWidth
    val items = captureItems {
      emitSynchronousReadFirstSinglePortMemory(
        "p_memory",
        "memory",
        ref("clk"),
        ref("read_enable"),
        ref("write_enable"),
        ref("address"),
        ref("write_data"),
        ref("read_data"),
        packedBits(8),
        memoryDepth
      )
    }

    val module = moduleDef(
      name = "DerivedMemoryDepth",
      parameters = Vector(integerParameter(capacity)),
      ports = Vector(
        port("clk", Input, packedBits(1)),
        port("read_enable", Input, packedBits(1)),
        port("write_enable", Input, packedBits(1)),
        port("address", Input, packedBits(memoryDepth.addressWidth)),
        port("write_data", Input, packedBits(8)),
        port("read_data", Output, packedBits(8))
      ),
      items = items
    )

    assert(
      module.items.head.asInstanceOf[SynchronousReadFirstSinglePortMemory].depth ==
        AddressWidth(ParameterRef("CAPACITY"))
    )
  }

  test("lets ParamRTL reject unsafe legal domains and inactive unsafe select operands") {
    def diagnosticsFor(
        name: String,
        value: HdlInt,
        parameters: Vector[HdlInt],
        booleans: Vector[HdlBool] = Vector.empty
    ) = {
      val module = moduleDef(
        name = name,
        parameters = parameters.map(integerParameter),
        ports = Vector(port("address", Input, packedBits(value.addressWidth))),
        items = captureItems {},
        booleanParameters = booleans.map(booleanParameter)
      )
      ParamRtlValidator.validate(Design(module.name, Vector(module))) match {
        case Left(diagnostics) => diagnostics
        case Right(_)          => fail(s"expected $name to reject an unsafe addressWidth operand")
      }
    }

    val includesZero = HdlInt.param("INCLUDES_ZERO", default = 5, min = 0, max = 17)
    val domainDiagnostics = diagnosticsFor("UnsafeDomain", includesZero, Vector(includesZero))
    assert(
      domainDiagnostics.codes.contains("PRTL-ADDRESS-WIDTH-OPERAND-NOT-PROVEN-POSITIVE"),
      domainDiagnostics.values.mkString("\n")
    )

    val enabled = HdlBool.param("ENABLED", default = true)
    val safe = HdlInt.param("SAFE_DEPTH", default = 5, min = 1, max = 17)
    val inactiveUnsafe = enabled.select(safe, 0)
    assert(inactiveUnsafe.witness == 5)
    val inactiveDiagnostics = diagnosticsFor(
      "InactiveUnsafeOperand",
      inactiveUnsafe,
      Vector(safe),
      Vector(enabled)
    )
    assert(
      inactiveDiagnostics.codes.contains("PRTL-ADDRESS-WIDTH-OPERAND-NOT-PROVEN-POSITIVE"),
      inactiveDiagnostics.values.mkString("\n")
    )
  }

  test("preserves missing mismatched Boolean and foreign identities through addressWidth") {
    val missing = HdlInt.param("MISSING_DEPTH", default = 5, min = 1, max = 17)
    val missingError = intercept[FrontendException] {
      moduleDef(
        "MissingAddressWidthIdentity",
        Vector.empty,
        Vector(port("address", Input, packedBits(missing.addressWidth))),
        captureItems {}
      )
    }
    assert(missingError.code == "MORPH-FRONTEND-PARAMETER-NOT-DECLARED")
    assert(missingError.origin == missing.origin)

    val declared = HdlInt.param("DEPTH", default = 5, min = 1, max = 17)
    val used = HdlInt.param("DEPTH", default = 3, min = 1, max = 9)
    val mismatchError = intercept[FrontendException] {
      moduleDef(
        "MismatchedAddressWidthIdentity",
        Vector(integerParameter(declared)),
        Vector(port("address", Input, packedBits(used.addressWidth))),
        captureItems {}
      )
    }
    assert(mismatchError.code == "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH")
    assert(mismatchError.origin == used.origin)

    val condition = HdlBool.param("MISSING_CONDITION", default = true)
    val selected = condition.select(declared, 1).addressWidth
    val booleanError = intercept[FrontendException] {
      moduleDef(
        "MissingBooleanAddressWidthIdentity",
        Vector(integerParameter(declared)),
        Vector(port("address", Input, packedBits(selected))),
        captureItems {}
      )
    }
    assert(booleanError.code == "MORPH-FRONTEND-BOOLEAN-PARAMETER-NOT-DECLARED")
    assert(booleanError.origin == condition.origin)

    val foreign = localParam("FOREIGN_DEPTH", HdlInt.literal(5))
    moduleDef(
      "AddressWidthLocalOwner",
      Vector.empty,
      Vector.empty,
      captureItems {},
      localParameters = Vector(integerLocalParameter(foreign))
    )
    val foreignError = intercept[FrontendException] {
      moduleDef(
        "AddressWidthLocalBorrower",
        Vector.empty,
        Vector(port("address", Input, packedBits(foreign.addressWidth))),
        captureItems {}
      )
    }
    assert(foreignError.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
    assert(foreignError.origin == foreign.origin)
  }

  test("preflights generate scope before witness positivity and rejects escaped indices") {
    val count = HdlInt.param("COUNT", default = 1, min = 1, max = 1)
    var loopError: FrontendException = null
    var escaped: HdlInt = null

    captureItems {
      for (index <- 0 until count) {
        val indexed = index * HdlInt.literal(1)
        loopError = intercept[FrontendException](indexed.addressWidth)
        escaped = indexed
      }
    }

    assert(loopError.code == "MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED")
    assert(loopError.code != "MORPH-FRONTEND-ADDRESS-WIDTH-WITNESS-NONPOSITIVE")
    val escapedError = intercept[FrontendException](escaped.addressWidth)
    assert(escapedError.code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
  }

  test("keeps declaration identity equality hashing and implicit-conversion safety fail-closed") {
    val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 17)
    val width = depth.addressWidth
    val local = localParam("LOCAL_DEPTH", depth)

    assert(intercept[FrontendException](integerParameter(width)).code ==
      "MORPH-FRONTEND-NOT-A-PUBLIC-PARAMETER")
    assert(intercept[FrontendException](integerLocalParameter(local.addressWidth)).code ==
      "MORPH-FRONTEND-NOT-A-LOCAL-PARAMETER")
    assert(intercept[FrontendException](width == depth).code ==
      "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED")
    assert(intercept[FrontendException](width.hashCode).code ==
      "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED")

    assertCompiles("""
      import morphhdl.frontend._
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 17)
      val addressWidth: HdlInt = depth.addressWidth
      val unchangedArithmetic: HdlInt = depth + 1
    """)
    assertTypeError("""
      import morphhdl.frontend._
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 17)
      val addressWidth: Int = depth.addressWidth
    """)
    assertTypeError("""
      import morphhdl.frontend._
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 17)
      depth.addressWidth.toInt
    """)
  }
}
