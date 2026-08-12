package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.PortDirection.Input
import morphhdl.paramrtl.RtlExpr
import org.scalatest.funsuite.AnyFunSuite

class FrontendProvenanceTests extends AnyFunSuite {
  test("rejects a distinct same-named parameter used as a loop count") {
    val declared = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    val used = HdlInt.param("LANES", default = 2, min = 1, max = 64)
    val items = captureItems {
      for (_ <- 0 until used) {
        emitInstance(name = "lane_inst", moduleName = "PixelLane")
      }
    }

    val error = intercept[FrontendException] {
      moduleDef(
        name = "AliasCount",
        parameters = Vector(integerParameter(declared)),
        ports = Vector.empty,
        items = items
      )
    }

    assert(error.code == "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH")
    assert(error.detail.contains("distinct declaration"))
  }

  test("rejects same-named aliases in ports and child bindings") {
    val declared = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val used = HdlInt.param("WIDTH", default = 4, min = 1, max = 32)
    val emptyItems = captureItems {}

    val portError = intercept[FrontendException] {
      moduleDef(
        name = "AliasPort",
        parameters = Vector(integerParameter(declared)),
        ports = Vector(port("data", Input, packedBits(used))),
        items = emptyItems
      )
    }
    assert(portError.code == "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH")

    val boundItems = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        parameterBindings = Vector(parameterBinding("WIDTH", used))
      )
    }
    val bindingError = intercept[FrontendException] {
      moduleDef(
        name = "AliasBinding",
        parameters = Vector(integerParameter(declared)),
        ports = Vector.empty,
        items = boundItems
      )
    }
    assert(bindingError.code == "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH")
  }

  test("rejects undeclared and duplicate public parameters at the module boundary") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val items = captureItems {}

    val missing = intercept[FrontendException] {
      moduleDef(
        name = "MissingDeclaration",
        parameters = Vector.empty,
        ports = Vector(port("data", Input, packedBits(width))),
        items = items
      )
    }
    assert(missing.code == "MORPH-FRONTEND-PARAMETER-NOT-DECLARED")

    val duplicate = intercept[FrontendException] {
      moduleDef(
        name = "DuplicateDeclaration",
        parameters = Vector(integerParameter(width), integerParameter(width)),
        ports = Vector.empty,
        items = items
      )
    }
    assert(duplicate.code == "MORPH-FRONTEND-PARAMETER-NAME-DUPLICATE")
  }

  test("keeps parameter identity local to each module") {
    val firstWidth = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val secondWidth = HdlInt.param("WIDTH", default = 4, min = 1, max = 32)
    val sharedWidth = HdlInt.param("SHARED", default = 2, min = 1, max = 16)

    val first = moduleDef(
      name = "First",
      parameters = Vector(integerParameter(firstWidth), integerParameter(sharedWidth)),
      ports = Vector(port("data", Input, packedBits(firstWidth * sharedWidth))),
      items = captureItems {}
    )
    val second = moduleDef(
      name = "Second",
      parameters = Vector(integerParameter(secondWidth), integerParameter(sharedWidth)),
      ports = Vector(port("data", Input, packedBits(secondWidth * sharedWidth))),
      items = captureItems {}
    )

    assert(first.parameters.map(_.default) == Vector[BigInt](8, 2))
    assert(second.parameters.map(_.default) == Vector[BigInt](4, 2))
  }

  test("retains generate scope provenance until the final instance emission") {
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    var escapedExpression: FrontendNode[RtlExpr] = null

    captureItems {
      for (lane <- (0 until lanes).named("g_first", "first")) {
        escapedExpression = indexedPartSelect("data", lane * width, width)
      }
    }

    val error = intercept[FrontendException] {
      captureItems {
        for (_ <- (0 until lanes).named("g_second", "second")) {
          emitInstance(
            name = "lane_inst",
            moduleName = "PixelLane",
            portConnections = Vector(portConnection("data", escapedExpression))
          )
        }
      }
    }

    assert(error.code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
  }

  test("rejects a connection emitted after its generate scope closes") {
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    var escapedConnection: FrontendNode[morphhdl.paramrtl.PortConnection] = null

    captureItems {
      for (lane <- 0 until lanes) {
        escapedConnection = portConnection(
          "data",
          indexedPartSelect("data", lane * width, width)
        )
      }
    }

    val error = intercept[FrontendException] {
      captureItems {
        emitInstance(
          name = "lane_inst",
          moduleName = "PixelLane",
          portConnections = Vector(escapedConnection)
        )
      }
    }
    assert(error.code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
  }

  test("does not expose guarded expressions or connections as raw ParamRTL") {
    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      import morphhdl.paramrtl.RtlExpr
      val lanes = HdlInt.param("LANES", 2, 1, 64)
      val width = HdlInt.param("WIDTH", 8, 1, 64)
      captureItems {
        for (lane <- 0 until lanes) {
          val raw: RtlExpr = indexedPartSelect("data", lane * width, width)
        }
      }
    """)
    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      import morphhdl.paramrtl._
      emitInstance(
        name = "forged",
        moduleName = "Child",
        portConnections = Vector(
          PortConnection("data", RtlExpr.IndexedPartSelect(
            RtlExpr.Ref("data"),
            IntExpr.GenerateIndexRef("forged_index"),
            IntExpr.Literal(8)
          ))
        )
      )
    """)
  }
}
