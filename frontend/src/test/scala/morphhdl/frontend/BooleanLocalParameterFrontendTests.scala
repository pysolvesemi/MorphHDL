package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.BoolExpr.{
  And,
  GreaterThanOrEqual,
  Literal => BoolLiteral,
  LocalParameterRef => BooleanLocalRef,
  Not,
  Or,
  ParameterRef => BooleanParameterRef
}
import morphhdl.paramrtl.IntExpr.{
  Add,
  Literal => IntLiteral,
  LocalParameterRef => IntegerLocalRef,
  ParameterRef => IntegerParameterRef,
  Select
}
import morphhdl.paramrtl.PortDirection.Input
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class BooleanLocalParameterFrontendTests extends AnyFunSuite {
  test("lowers identity-bearing Boolean locals with public and mixed local dependencies") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val limit = localParam("LIMIT", width + 1)
    val base = localParam("BASE_OK", enabled && (limit >= width))
    val selected = localParam("SELECTED", base.select(limit, width))
    val finalFlag = localParam("FINAL_OK", !base || (selected >= limit))

    assert(base.witness)
    assert(base.expression == BooleanLocalRef("BASE_OK"))
    assert(finalFlag.expression == BooleanLocalRef("FINAL_OK"))
    assert(base.integerParameters == width.parameters)
    assert(base.parameters == enabled.parameters)
    assert(base.localParameters == limit.localParameters)
    assert(finalFlag.localParameters == Set(limit.localDeclaration.get, selected.localDeclaration.get))
    assert(finalFlag.booleanLocalParameters == Set(base.localDeclaration.get, finalFlag.localDeclaration.get))

    val module = moduleDef(
      name = "BooleanLocals",
      parameters = Vector(integerParameter(width)),
      ports = Vector(port("data", Input, packedBits(selected))),
      items = captureItems {
        generateIf(finalFlag) {
          emitInstance("enabled_inst", "Enabled")
        }.otherwise {
          emitInstance("disabled_inst", "Disabled")
        }
      },
      localParameters = Vector(integerLocalParameter(selected), integerLocalParameter(limit)),
      booleanParameters = Vector(booleanParameter(enabled)),
      booleanLocalParameters = Vector(
        booleanLocalParameter(finalFlag),
        booleanLocalParameter(base)
      )
    )

    assert(module.localParameters.map(_.name) == Vector("LIMIT", "SELECTED"))
    assert(module.booleanLocalParameters.map(_.name) == Vector("BASE_OK", "FINAL_OK"))
    assert(
      module.booleanLocalParameters == Vector(
        BooleanLocalParameter(
          "BASE_OK",
          And(
            BooleanParameterRef("ENABLED"),
            GreaterThanOrEqual(IntegerLocalRef("LIMIT"), IntegerParameterRef("WIDTH"))
          )
        ),
        BooleanLocalParameter(
          "FINAL_OK",
          Or(
            Not(BooleanLocalRef("BASE_OK")),
            GreaterThanOrEqual(IntegerLocalRef("SELECTED"), IntegerLocalRef("LIMIT"))
          )
        )
      )
    )
    assert(
      module.localParameters.find(_.name == "SELECTED").get.value == Select(
        BooleanLocalRef("BASE_OK"),
        IntegerLocalRef("LIMIT"),
        IntegerParameterRef("WIDTH")
      )
    )
  }

  test("propagates Boolean-local identity through every guarded frontend consumer") {
    val enabled = localParam("ENABLED_LOCAL", HdlBool.literal(value = true))
    val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 8)
    val selected = enabled.select(width + 1, width + 2)

    assert(selected.booleanLocalParameters == enabled.booleanLocalParameters)
    val arithmetic = Vector(
      selected + 1,
      selected - 1,
      selected * 2,
      selected / 1,
      selected % 2,
      -selected
    )
    val comparisons = Vector(
      selected < width,
      selected <= width,
      selected > width,
      selected >= width,
      selected.hdlEq(width),
      selected.hdlNe(width)
    )
    assert(arithmetic.forall(_.booleanLocalParameters == enabled.booleanLocalParameters))
    assert(comparisons.forall(_.booleanLocalParameters == enabled.booleanLocalParameters))
    assert(packedBits(selected).booleanLocalParameters == enabled.booleanLocalParameters)
    assert(parameterBinding("WIDTH", selected).booleanLocalParameters == enabled.booleanLocalParameters)
    assert(parameterBinding("ENABLE", enabled).booleanLocalParameters == enabled.booleanLocalParameters)
    assert(
      indexedPartSelect("data", selected - 1, selected).booleanLocalParameters ==
        enabled.booleanLocalParameters
    )

    val indexed = indexedPartSelect("data", 0, selected)
    assert(portConnection("data", indexed).booleanLocalParameters == enabled.booleanLocalParameters)

    val loopItems = captureItems {
      for (_ <- 0 until selected) {
        emitInstance(
          "leaf_inst",
          "Leaf",
          parameterBindings = Vector(parameterBinding("WIDTH", selected)),
          portConnections = Vector(portConnection("data", indexed)),
          booleanParameterBindings = Vector(parameterBinding("ENABLE", enabled))
        )
      }
    }
    assert(loopItems.booleanLocalParameters == enabled.booleanLocalParameters)

    val conditionalItems = captureItems {
      generateIf(enabled && (selected >= width)) {
        emitInstance("yes_inst", "Yes")
      }.otherwise {
        emitInstance("no_inst", "No")
      }
    }
    assert(conditionalItems.booleanLocalParameters == enabled.booleanLocalParameters)
    val assignmentItems = captureItems {
      emitContinuousAssign("out", indexed)
    }
    assert(assignmentItems.booleanLocalParameters == enabled.booleanLocalParameters)

    val module = moduleDef(
      name = "AllBooleanLocalConsumers",
      parameters = Vector(integerParameter(width)),
      ports = Vector(port("data", Input, packedBits(selected))),
      items = loopItems,
      booleanLocalParameters = Vector(booleanLocalParameter(enabled))
    )
    assert(module.booleanLocalParameters.map(_.name) == Vector("ENABLED_LOCAL"))
  }

  test("orders independent Boolean locals deterministically and preserves explicit unused locals") {
    def names(reverse: Boolean): Vector[String] = {
      val zed = localParam("ZED", HdlBool.literal(value = true))
      val alpha = localParam("ALPHA", HdlBool.literal(value = false))
      val unused = localParam("UNUSED", alpha || zed)
      val declarations = Vector(
        booleanLocalParameter(zed),
        booleanLocalParameter(unused),
        booleanLocalParameter(alpha)
      )
      moduleDef(
        name = if (reverse) "BooleanLocalReverse" else "BooleanLocalForward",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        booleanLocalParameters = if (reverse) declarations.reverse else declarations
      ).booleanLocalParameters.map(_.name)
    }

    assert(names(reverse = false) == Vector("ALPHA", "ZED", "UNUSED"))
    assert(names(reverse = true) == Vector("ALPHA", "ZED", "UNUSED"))
  }

  test("rejects Boolean-local dependency cycles including cross-kind cycles") {
    val first = localParam("FIRST", HdlBool.literal(value = true))
    val second = localParam("SECOND", !first)
    first.localDeclaration.get.dependencies = Set(second.localDeclaration.get)

    val booleanCycle = intercept[FrontendException] {
      moduleDef(
        name = "BooleanCycle",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        booleanLocalParameters = Vector(
          booleanLocalParameter(second),
          booleanLocalParameter(first)
        )
      )
    }
    assert(booleanCycle.code == "MORPH-FRONTEND-LOCAL-PARAMETER-CYCLE")
    assert(booleanCycle.detail.contains("FIRST"))

    val integer = localParam("INTEGER_LOCAL", HdlInt.literal(1))
    val boolean = localParam("BOOLEAN_LOCAL", HdlBool.literal(value = true))
    integer.localDeclaration.get.dependencies = Set(boolean.localDeclaration.get)
    boolean.localDeclaration.get.dependencies = Set(integer.localDeclaration.get)

    val crossKindCycle = intercept[FrontendException] {
      moduleDef(
        name = "CrossKindCycle",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        localParameters = Vector(integerLocalParameter(integer)),
        booleanLocalParameters = Vector(booleanLocalParameter(boolean))
      )
    }
    assert(crossKindCycle.code == "MORPH-FRONTEND-LOCAL-PARAMETER-CYCLE")
    assert(crossKindCycle.detail.contains("BOOLEAN_LOCAL"))
  }

  test("rejects missing, same-name mismatch, duplicate and foreign Boolean-local identities") {
    val missing = localParam("MISSING", HdlBool.literal(value = true))
    val missingItems = captureItems {
      generateIf(missing) {
        emitInstance("yes_inst", "Yes")
      }.otherwise {
        emitInstance("no_inst", "No")
      }
    }
    val missingError = intercept[FrontendException] {
      moduleDef("MissingBooleanLocal", Vector.empty, Vector.empty, missingItems)
    }
    assert(missingError.code == "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NOT-DECLARED")
    assert(missingError.origin == missing.origin)

    val declared = localParam("SAME", HdlBool.literal(value = true))
    val used = localParam("SAME", HdlBool.literal(value = false))
    val mismatchItems = captureItems {
      generateIf(used) {
        emitInstance("yes_inst", "Yes")
      }.otherwise {
        emitInstance("no_inst", "No")
      }
    }
    val mismatch = intercept[FrontendException] {
      moduleDef(
        "MismatchedBooleanLocal",
        Vector.empty,
        Vector.empty,
        mismatchItems,
        booleanLocalParameters = Vector(booleanLocalParameter(declared))
      )
    }
    assert(mismatch.code == "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-TOKEN-MISMATCH")
    assert(mismatch.origin == used.origin)

    val duplicateHandle = localParam("DUPLICATE", HdlBool.literal(value = true))
    val duplicateHandleError = intercept[FrontendException] {
      moduleDef(
        "DuplicateBooleanHandle",
        Vector.empty,
        Vector.empty,
        captureItems {},
        booleanLocalParameters = Vector(
          booleanLocalParameter(duplicateHandle),
          booleanLocalParameter(duplicateHandle)
        )
      )
    }
    assert(
      duplicateHandleError.code ==
        "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-DECLARATION-DUPLICATE"
    )

    val firstName = localParam("DUPLICATE_NAME", HdlBool.literal(value = true))
    val secondName = localParam("DUPLICATE_NAME", HdlBool.literal(value = false))
    val duplicateNameError = intercept[FrontendException] {
      moduleDef(
        "DuplicateBooleanName",
        Vector.empty,
        Vector.empty,
        captureItems {},
        booleanLocalParameters = Vector(
          booleanLocalParameter(firstName),
          booleanLocalParameter(secondName)
        )
      )
    }
    assert(
      duplicateNameError.code == "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NAME-DUPLICATE"
    )

    val owned = localParam("OWNED", HdlBool.literal(value = true))
    val declaration = booleanLocalParameter(owned)
    moduleDef(
      "FirstBooleanOwner",
      Vector.empty,
      Vector.empty,
      captureItems {},
      booleanLocalParameters = Vector(declaration)
    )
    val foreign = intercept[FrontendException] {
      moduleDef(
        "SecondBooleanOwner",
        Vector.empty,
        Vector.empty,
        captureItems {},
        booleanLocalParameters = Vector(declaration)
      )
    }
    assert(foreign.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
    assert(foreign.detail.contains("FirstBooleanOwner"))

    val useOnlyItems = captureItems {
      generateIf(owned) {
        emitInstance("yes_inst", "Yes")
      }.otherwise {
        emitInstance("no_inst", "No")
      }
    }
    val useOnly = intercept[FrontendException] {
      moduleDef("UseOnlyBooleanOwner", Vector.empty, Vector.empty, useOnlyItems)
    }
    val derived = localParam("DERIVED_OWNED", !owned)
    val dependencyOnly = intercept[FrontendException] {
      moduleDef(
        "DependencyOnlyBooleanOwner",
        Vector.empty,
        Vector.empty,
        captureItems {},
        booleanLocalParameters = Vector(booleanLocalParameter(derived))
      )
    }
    Vector(useOnly, dependencyOnly).foreach { error =>
      assert(error.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
      assert(error.origin == owned.origin)
      assert(error.detail.contains("FirstBooleanOwner"))
    }
  }

  test("rejects public, local-kind and unresolved declaration collisions") {
    val public = HdlBool.param("MODE", default = true)
    val local = localParam("MODE", HdlBool.literal(value = false))
    val publicCollision = intercept[FrontendException] {
      moduleDef(
        "PublicBooleanLocalCollision",
        Vector.empty,
        Vector.empty,
        captureItems {},
        booleanParameters = Vector(booleanParameter(public)),
        booleanLocalParameters = Vector(booleanLocalParameter(local))
      )
    }
    assert(publicCollision.code == "MORPH-FRONTEND-LOCAL-PARAMETER-NAME-COLLISION")

    val integerLocal = localParam("SHARED", HdlInt.literal(1))
    val booleanLocal = localParam("SHARED", HdlBool.literal(value = true))
    val kindCollision = intercept[FrontendException] {
      moduleDef(
        "LocalKindCollision",
        Vector.empty,
        Vector.empty,
        captureItems {},
        localParameters = Vector(integerLocalParameter(integerLocal)),
        booleanLocalParameters = Vector(booleanLocalParameter(booleanLocal))
      )
    }
    assert(kindCollision.code == "MORPH-FRONTEND-LOCAL-PARAMETER-KIND-COLLISION")

    val usedAsBoolean = localParam("INTEGER_ONLY", HdlBool.literal(value = true))
    val declaredAsInteger = localParam("INTEGER_ONLY", HdlInt.literal(1))
    val kindItems = captureItems {
      generateIf(usedAsBoolean) {
        emitInstance("yes_inst", "Yes")
      }.otherwise {
        emitInstance("no_inst", "No")
      }
    }
    val kindMismatch = intercept[FrontendException] {
      moduleDef(
        "LocalKindMismatch",
        Vector.empty,
        Vector.empty,
        kindItems,
        localParameters = Vector(integerLocalParameter(declaredAsInteger))
      )
    }
    assert(kindMismatch.code == "MORPH-FRONTEND-LOCAL-PARAMETER-KIND-MISMATCH")

    val forged = FrontendNode(
      BooleanLocalParameter("RAW", BoolLiteral(true)),
      origin = SourceOrigin("forged.scala", 13)
    )
    val unresolved = intercept[FrontendException] {
      moduleDef(
        "ForgedBooleanLocal",
        Vector.empty,
        Vector.empty,
        captureItems {},
        booleanLocalParameters = Vector(forged)
      )
    }
    assert(
      unresolved.code == "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-IDENTITY-UNRESOLVED"
    )
    assert(unresolved.sourceLocation == "forged.scala:13")
  }

  test("retains inactive dependencies and preflights generate-index comparisons") {
    val always = localParam("ALWAYS", HdlBool.literal(value = true))
    val missingPublic = HdlBool.param("MISSING_PUBLIC", default = false)
    val retained = localParam("RETAINED", always || missingPublic)
    val inactiveError = intercept[FrontendException] {
      moduleDef(
        "InactiveBooleanDependency",
        Vector.empty,
        Vector.empty,
        captureItems {},
        booleanLocalParameters = Vector(
          booleanLocalParameter(retained),
          booleanLocalParameter(always)
        )
      )
    }
    assert(inactiveError.code == "MORPH-FRONTEND-BOOLEAN-PARAMETER-NOT-DECLARED")
    assert(inactiveError.origin == missingPublic.origin)

    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 1)
    var loopError: FrontendException = null
    captureItems {
      for (lane <- 0 until lanes) {
        loopError = intercept[FrontendException] {
          localParam(
            "INDEXED_BOOLEAN",
            HdlBool.literal(value = true) || (lane * HdlInt.literal(1) >= 0)
          )
        }
      }
    }
    assert(loopError.code == "MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED")
  }

  test("keeps Boolean-local handles fresh and Scala equality and hashing fail closed") {
    def factory(): HdlBool = localParam("FLAG", HdlBool.literal(value = true))
    val first = factory()
    val second = factory()

    assert(!(first.localDeclaration.get eq second.localDeclaration.get))
    val errors = Vector(
      intercept[FrontendException](first == second),
      intercept[FrontendException](first != second),
      intercept[FrontendException](first == true),
      intercept[FrontendException](first.hashCode)
    )
    errors.foreach { error =>
      assert(error.code == "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED")
      assert(error.origin == first.origin)
    }

    val notLocal = intercept[FrontendException] {
      booleanLocalParameter(HdlBool.literal(value = true))
    }
    assert(notLocal.code == "MORPH-FRONTEND-NOT-A-BOOLEAN-LOCAL-PARAMETER")

    val invalidName = intercept[FrontendException] {
      localParam("not-portable", HdlBool.literal(value = true))
    }
    assert(invalidName.code == "MORPH-FRONTEND-INVALID-LOCAL-PARAMETER-NAME")

    val nullValue = intercept[FrontendException] {
      localParam("NULL_LOCAL", null.asInstanceOf[HdlBool])
    }
    assert(nullValue.code == "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NULL")
  }
}
