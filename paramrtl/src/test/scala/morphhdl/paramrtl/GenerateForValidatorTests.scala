package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr._
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateFor, ModuleInstance}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import org.scalatest.funsuite.AnyFunSuite

class GenerateForValidatorTests extends AnyFunSuite {
  test("validates a canonical generated lane hierarchy and records scoped facts") {
    val validated = valid(laneDesign())

    assert(validated.orderedModules.map(_.name) == Vector("PixelLane", "LaneArray"))
    assert(validated.moduleFacts("LaneArray").instanceFacts.keySet == Set("g_lane.lane_inst"))
  }

  test("resolves a generate index only in its lexical indexed-select offset") {
    val base = laneDesign()
    val parent = module(base, "LaneArray")
    val leaked = parent.copy(localParameters = Vector(IntegerLocalParameter("LEAK", GenerateIndexRef("lane"))))
    assertCodes(base.copy(modules = Vector(leaked, module(base, "PixelLane"))), "PRTL-GENERATE-INDEX-OUT-OF-SCOPE")

    val wrongIndex = mapSelects(parent) { select =>
      select.copy(offset = Multiply(GenerateIndexRef("other"), select.width))
    }
    assertCodes(
      base.copy(modules = Vector(wrongIndex, module(base, "PixelLane"))),
      "PRTL-GENERATE-INDEX-OUT-OF-SCOPE",
      "PRTL-GENERATE-SLICE-NOT-CANONICAL"
    )

    val generate = onlyGenerate(parent)
    val selfCount = parent.copy(items = Vector(generate.copy(count = GenerateIndexRef("lane"))))
    assertCodes(
      base.copy(modules = Vector(selfCount, module(base, "PixelLane"))),
      "PRTL-GENERATE-INDEX-OUT-OF-SCOPE"
    )

    val instance = onlyInstance(generate)
    val indexBinding =
      instance.copy(parameterBindings = Vector(ParameterBinding("DATA_WIDTH", GenerateIndexRef("lane"))))
    val boundByIndex = parent.copy(items = Vector(generate.copy(body = Vector(indexBinding))))
    assertCodes(
      base.copy(modules = Vector(boundByIndex, module(base, "PixelLane"))),
      "PRTL-GENERATE-INDEX-OUT-OF-SCOPE"
    )
  }

  test("rejects indexed part-selects outside generate bodies without throwing") {
    val packed = PackedBits(Literal(8), Unsigned)
    val top = ModuleDef(
      "Top",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), IndexedPartSelect(Ref("din"), Literal(0), Literal(8))))
    )
    assertCodes(Design("Top", Vector(top)), "PRTL-INDEXED-PART-SELECT-REQUIRES-GENERATE")

    val child = ModuleDef(
      "Child",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    val instanceTop = top.copy(items =
      Vector(
        ModuleInstance(
          "child",
          "Child",
          portConnections = Vector(
            PortConnection("din", IndexedPartSelect(Ref("din"), Literal(0), Literal(8))),
            PortConnection("dout", Ref("dout"))
          )
        )
      )
    )
    assertCodes(
      Design("Top", Vector(instanceTop, child)),
      "PRTL-INDEXED-PART-SELECT-REQUIRES-GENERATE"
    )
  }

  test("validates generate labels and module-scope index declarations") {
    val base = laneDesign()
    val parent = module(base, "LaneArray")
    val first = onlyGenerate(parent)
    val duplicate = first.copy()
    val broken = parent.copy(items = Vector(first, duplicate))

    assertCodes(
      base.copy(modules = Vector(broken, module(base, "PixelLane"))),
      "PRTL-DUPLICATE-GENERATE-LABEL",
      "PRTL-DUPLICATE-GENERATE-INDEX",
      "PRTL-MULTIPLE-DRIVERS"
    )

    val colliding = parent.copy(items = Vector(first.copy(indexName = "data_in")))
    assertCodes(
      base.copy(modules = Vector(colliding, module(base, "PixelLane"))),
      "PRTL-DUPLICATE-DECLARATION"
    )
  }

  test("requires a generate count proven positive over its full legal domain") {
    val base = laneDesign()
    val parent = module(base, "LaneArray")
    val lanes = parent.parameters.find(_.name == "LANES").get
    val mayBeZero = lanes.copy(constraints = Vector(MinInclusive(0), MaxInclusive(32)))
    val changed = parent.copy(parameters =
      parent.parameters.map(parameter => if (parameter.name == "LANES") mayBeZero else parameter)
    )

    assertCodes(
      base.copy(modules = Vector(changed, module(base, "PixelLane"))),
      "PRTL-GENERATE-COUNT-NOT-PROVEN-POSITIVE"
    )
  }

  test("proves canonical offset and complete parent-vector coverage") {
    val base = laneDesign()
    val parent = module(base, "LaneArray")
    val shifted = mapSelects(parent) { select =>
      select.copy(offset = Add(select.offset, Literal(1)))
    }
    assertCodes(
      base.copy(modules = Vector(shifted, module(base, "PixelLane"))),
      "PRTL-GENERATE-SLICE-NOT-CANONICAL"
    )

    val shortParent = parent.copy(ports = parent.ports.map { port =>
      port.copy(dataType = port.dataType.copy(width = ParameterRef("DATA_WIDTH")))
    })
    assertCodes(
      base.copy(modules = Vector(shortParent, module(base, "PixelLane"))),
      "PRTL-GENERATE-SLICE-NOT-CANONICAL"
    )

    val sliceWidth = LocalParameterRef("SLICE_WIDTH")
    val totalWidth = LocalParameterRef("TOTAL_WIDTH")
    val generate = onlyGenerate(parent)
    val instance = onlyInstance(generate).copy(
      parameterBindings = Vector(ParameterBinding("DATA_WIDTH", sliceWidth)),
      portConnections = onlyInstance(generate).portConnections.map { connection =>
        connection.copy(actual =
          IndexedPartSelect(
            connection.actual.asInstanceOf[IndexedPartSelect].base,
            Multiply(GenerateIndexRef("lane"), sliceWidth),
            sliceWidth
          )
        )
      }
    )
    val derived = parent.copy(
      ports = parent.ports.map(port => port.copy(dataType = port.dataType.copy(width = totalWidth))),
      items = Vector(generate.copy(body = Vector(instance))),
      localParameters = Vector(
        IntegerLocalParameter("SLICE_WIDTH", ParameterRef("DATA_WIDTH")),
        IntegerLocalParameter("TOTAL_WIDTH", Multiply(ParameterRef("LANES"), sliceWidth))
      )
    )
    assert(ParamRtlValidator.validate(base.copy(modules = Vector(derived, module(base, "PixelLane")))).isRight)
  }

  test("analyzes dead offset terms before canonical equivalence simplification") {
    val base = laneDesign()
    val parent = module(base, "LaneArray")
    val invalidTerms = Vector(
      "/" -> Divide(Literal(1), Literal(0)),
      "%" -> Modulo(Literal(1), Literal(0))
    )

    invalidTerms.foreach { case (operator, invalidTerm) =>
      val broken = mapSelects(parent) { select =>
        select.copy(offset = Add(select.offset, Multiply(Literal(0), invalidTerm)))
      }
      val diagnostics = invalid(base.copy(modules = Vector(broken, module(base, "PixelLane"))))
      val divisorFailures = diagnostics.values.filter(_.code == "PRTL-DIVISOR-MAY-BE-ZERO")

      assert(divisorFailures.size == 2)
      assert(divisorFailures.forall(_.message.startsWith(s"Divisor of '$operator'")))
      assert(divisorFailures.forall(_.path.last == "offset"))
    }
  }

  test("requires loop-invariant positive slice widths") {
    val base = laneDesign()
    val parent = module(base, "LaneArray")
    val varying = mapSelects(parent) { select =>
      select.copy(width = Add(select.width, GenerateIndexRef("lane")))
    }
    assertCodes(
      base.copy(modules = Vector(varying, module(base, "PixelLane"))),
      "PRTL-GENERATE-SLICE-WIDTH-VARIES",
      "PRTL-GENERATE-SLICE-NOT-CANONICAL"
    )
  }

  test("requires generated outputs to use canonical indexed part-selects") {
    val base = laneDesign()
    val parent = module(base, "LaneArray")
    val generate = onlyGenerate(parent)
    val instance = onlyInstance(generate)
    val changed = instance.copy(portConnections = instance.portConnections.map {
      case connection if connection.portName == "data_out" => connection.copy(actual = Ref("data_out"))
      case connection                                      => connection
    })
    val broken = parent.copy(items = Vector(generate.copy(body = Vector(changed))))

    assertCodes(
      base.copy(modules = Vector(broken, module(base, "PixelLane"))),
      "PRTL-GENERATE-OUTPUT-NOT-CANONICAL",
      "PRTL-UNDRIVEN-OUTPUT"
    )
  }

  test("counts each complete generated output partition as one driver") {
    val base = laneDesign()
    val parent = module(base, "LaneArray")
    val first = onlyGenerate(parent)
    val secondIndex = "lane_b"
    val second = first.copy(
      label = "g_lane_b",
      indexName = secondIndex,
      body = first.body.map {
        case instance: ModuleInstance =>
          instance.copy(portConnections = instance.portConnections.map { connection =>
            connection.actual match {
              case select: IndexedPartSelect =>
                connection.copy(actual = select.copy(offset = Multiply(GenerateIndexRef(secondIndex), select.width)))
              case _ => connection
            }
          })
        case other => other
      }
    )
    val twiceDriven = parent.copy(items = Vector(first, second))

    assertCodes(
      base.copy(modules = Vector(twiceDriven, module(base, "PixelLane"))),
      "PRTL-MULTIPLE-DRIVERS"
    )
  }

  test("scopes repeated body instance names beneath distinct generate labels") {
    val base = laneDesign()
    val original = module(base, "LaneArray")
    val packed = original.ports.head.dataType
    val ports = Vector(
      Port("data_in_a", Input, packed),
      Port("data_in_b", Input, packed),
      Port("data_out_a", Output, packed),
      Port("data_out_b", Output, packed)
    )
    def loop(label: String, index: String, suffix: String): GenerateFor = {
      val width = ParameterRef("DATA_WIDTH")
      val offset = Multiply(GenerateIndexRef(index), width)
      GenerateFor(
        label,
        index,
        ParameterRef("LANES"),
        Vector(
          ModuleInstance(
            "lane_inst",
            "PixelLane",
            Vector(ParameterBinding("DATA_WIDTH", width)),
            Vector(
              PortConnection("data_in", IndexedPartSelect(Ref(s"data_in_$suffix"), offset, width)),
              PortConnection("data_out", IndexedPartSelect(Ref(s"data_out_$suffix"), offset, width))
            )
          )
        )
      )
    }
    val parent = original.copy(
      ports = ports,
      items = Vector(loop("g_a", "lane_a", "a"), loop("g_b", "lane_b", "b"))
    )
    val validated = valid(base.copy(modules = Vector(parent, module(base, "PixelLane"))))

    assert(
      validated.moduleFacts("LaneArray").instanceFacts.keySet ==
        Set("g_a.lane_inst", "g_b.lane_inst")
    )
  }

  test("treats an indexed part-select as unsigned per Verilog semantics") {
    val base = laneDesign()
    val signedChild = module(base, "PixelLane").copy(ports = module(base, "PixelLane").ports.map { port =>
      port.copy(dataType = port.dataType.copy(signedness = Signed))
    })
    val signedParent = module(base, "LaneArray").copy(ports = module(base, "LaneArray").ports.map { port =>
      port.copy(dataType = port.dataType.copy(signedness = Signed))
    })

    assertCodes(
      base.copy(modules = Vector(signedParent, signedChild)),
      "PRTL-INSTANCE-PORT-TYPE-MISMATCH"
    )
  }

  test("rejects unsupported body items and deeply nested invalid loops stack-safely") {
    val packed = PackedBits(Literal(1), Unsigned)
    val bodyAssignment = ModuleDef(
      "AssignmentBody",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(GenerateFor("g", "i", Literal(1), Vector(ContinuousAssign(Ref("dout"), Ref("din")))))
    )
    assertCodes(Design("AssignmentBody", Vector(bodyAssignment)), "PRTL-GENERATE-BODY-ITEM-UNSUPPORTED")

    var nested: ModuleItem = GenerateFor("inner", "inner_i", Literal(1), Vector.empty)
    (0 until 10000).foreach { index =>
      nested = GenerateFor(s"g_$index", s"i_$index", Literal(1), Vector(nested))
    }
    val deep = ModuleDef("Deep", Vector.empty, Vector.empty, Vector(nested))
    assertCodes(Design("Deep", Vector(deep)), "PRTL-NESTED-GENERATE-UNSUPPORTED")
  }

  test("analyzes generate-index ranges when an explicit lexical fact is supplied") {
    val indexFacts = IntExprFacts(0, IntInterval(Some(0), Some(3)))
    val result = IntExpressionAnalysis.analyze(
      Multiply(GenerateIndexRef("lane"), Literal(8)),
      Map.empty,
      Map.empty,
      Map("lane" -> indexFacts)
    )
    assert(result == Right(IntExprFacts(0, IntInterval(Some(0), Some(24)))))
  }

  private def laneDesign(): Design = {
    val dataWidth = bounded("DATA_WIDTH", 8, 1, 64)
    val childPacked = PackedBits(ParameterRef("DATA_WIDTH"), Unsigned)
    val child = ModuleDef(
      "PixelLane",
      Vector(dataWidth),
      Vector(Port("data_in", Input, childPacked), Port("data_out", Output, childPacked)),
      Vector(ContinuousAssign(Ref("data_out"), Ref("data_in")))
    )

    val lanes = bounded("LANES", 4, 1, 32)
    val parentWidth = Multiply(ParameterRef("LANES"), ParameterRef("DATA_WIDTH"))
    val parentPacked = PackedBits(parentWidth, Unsigned)
    val sliceWidth = ParameterRef("DATA_WIDTH")
    val sliceOffset = Multiply(GenerateIndexRef("lane"), sliceWidth)
    val instance = ModuleInstance(
      "lane_inst",
      "PixelLane",
      Vector(ParameterBinding("DATA_WIDTH", ParameterRef("DATA_WIDTH"))),
      Vector(
        PortConnection("data_in", IndexedPartSelect(Ref("data_in"), sliceOffset, sliceWidth)),
        PortConnection("data_out", IndexedPartSelect(Ref("data_out"), sliceOffset, sliceWidth))
      )
    )
    val parent = ModuleDef(
      "LaneArray",
      Vector(lanes, dataWidth),
      Vector(Port("data_in", Input, parentPacked), Port("data_out", Output, parentPacked)),
      Vector(GenerateFor("g_lane", "lane", ParameterRef("LANES"), Vector(instance)))
    )

    Design("LaneArray", Vector(parent, child))
  }

  private def mapSelects(module: ModuleDef)(f: IndexedPartSelect => IndexedPartSelect): ModuleDef = {
    val generate = onlyGenerate(module)
    val instance = onlyInstance(generate)
    val changed = instance.copy(portConnections = instance.portConnections.map { connection =>
      connection.actual match {
        case select: IndexedPartSelect => connection.copy(actual = f(select))
        case _                         => connection
      }
    })
    module.copy(items = Vector(generate.copy(body = Vector(changed))))
  }

  private def bounded(name: String, default: BigInt, minimum: BigInt, maximum: BigInt): IntegerParameter =
    IntegerParameter(name, default, Vector(MinInclusive(minimum), MaxInclusive(maximum)))

  private def onlyGenerate(module: ModuleDef): GenerateFor =
    module.items.collectFirst { case generate: GenerateFor => generate }.get

  private def onlyInstance(generate: GenerateFor): ModuleInstance =
    generate.body.collectFirst { case instance: ModuleInstance => instance }.get

  private def module(design: Design, name: String): ModuleDef = design.modules.find(_.name == name).get

  private def valid(design: Design): ValidatedDesign = ParamRtlValidator.validate(design) match {
    case Right(value)      => value
    case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
  }

  private def invalid(design: Design): DiagnosticSet = ParamRtlValidator.validate(design) match {
    case Left(value) => value
    case Right(_)    => fail("Expected design validation to fail")
  }

  private def assertCodes(design: Design, expected: String*): Unit = {
    val codes = invalid(design).codes.toSet
    expected.foreach(code => assert(codes.contains(code), s"Missing $code in ${codes.toVector.sorted.mkString(", ")}"))
  }
}
