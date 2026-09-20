package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.BoolExpr.{
  Equal,
  GreaterThanOrEqual,
  Literal => BoolLiteral,
  Or,
  ParameterRef => BoolParameterRef
}
import morphhdl.paramrtl.IntExpr.{
  Add,
  Literal => IntLiteral,
  LocalParameterRef,
  ParameterRef => IntParameterRef
}
import morphhdl.paramrtl.{BooleanParameterBinding, ModuleItem, ParameterBinding}
import org.scalatest.funsuite.AnyFunSuite

class BooleanParameterBindingFrontendTests extends AnyFunSuite {
  test("lowers literal, parent, integer and local-derived Boolean child bindings") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val localLimit = localParam("LOCAL_LIMIT", width + 1)

    val literalBinding = parameterBinding("LITERAL_FLAG", false)
    val parentBinding = parameterBinding("PARENT_FLAG", enabled)
    val comparisonBinding = parameterBinding("WIDTH_OK", width >= 4)
    val localBinding = parameterBinding("LOCAL_OK", localLimit.hdlEq(width + 1))

    assert(
      literalBinding.raw == BooleanParameterBinding("LITERAL_FLAG", BoolLiteral(false))
    )
    assert(
      parentBinding.raw == BooleanParameterBinding(
        "PARENT_FLAG",
        BoolParameterRef("ENABLED")
      )
    )
    assert(
      comparisonBinding.raw == BooleanParameterBinding(
        "WIDTH_OK",
        GreaterThanOrEqual(IntParameterRef("WIDTH"), IntLiteral(4))
      )
    )
    assert(
      localBinding.raw == BooleanParameterBinding(
        "LOCAL_OK",
        Equal(
          LocalParameterRef("LOCAL_LIMIT"),
          Add(IntParameterRef("WIDTH"), IntLiteral(1))
        )
      )
    )

    assert(literalBinding.parameters.isEmpty)
    assert(literalBinding.booleanParameters.isEmpty)
    assert(literalBinding.localParameters.isEmpty)
    assert(parentBinding.booleanParameters == enabled.parameters)
    assert(comparisonBinding.parameters == width.parameters)
    assert(localBinding.parameters == width.parameters)
    assert(localBinding.localParameters == localLimit.localParameters)

    val items = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        booleanParameterBindings = Vector(
          literalBinding,
          parentBinding,
          comparisonBinding,
          localBinding
        )
      )
    }
    val module = moduleDef(
      name = "BooleanBindingParent",
      parameters = Vector(integerParameter(width)),
      ports = Vector.empty,
      items = items,
      localParameters = Vector(integerLocalParameter(localLimit)),
      booleanParameters = Vector(booleanParameter(enabled))
    )

    val instance = module.items.head.asInstanceOf[ModuleItem.ModuleInstance]
    assert(
      instance.booleanParameterBindings == Vector(
        literalBinding.raw,
        parentBinding.raw,
        comparisonBinding.raw,
        localBinding.raw
      )
    )
  }

  test("keeps the integer binding overload and positional instance arguments source compatible") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val enabled = HdlBool.param("ENABLED", default = true)
    val integerBinding: FrontendNode[ParameterBinding] = parameterBinding("WIDTH", width)
    val booleanBinding: FrontendNode[BooleanParameterBinding] =
      parameterBinding("ENABLED", enabled)

    val items = captureItems {
      emitInstance(
        "child_inst",
        "Child",
        Vector(integerBinding),
        Vector.empty,
        Vector(booleanBinding)
      )
    }
    val instance = items.raw.head.asInstanceOf[ModuleItem.ModuleInstance]

    assert(instance.parameterBindings == Vector(integerBinding.raw))
    assert(instance.booleanParameterBindings == Vector(booleanBinding.raw))
  }

  test("rejects null Boolean bindings at their source location") {
    val callLine = sourcecode.Line() + 1
    val error = intercept[FrontendException] {
      parameterBinding("ENABLED", null.asInstanceOf[HdlBool])
    }

    assert(error.code == "MORPH-FRONTEND-BOOLEAN-PARAMETER-BINDING-NULL")
    assert(error.origin.file.endsWith("BooleanParameterBindingFrontendTests.scala"))
    assert(error.origin.line == callLine + 1)
  }

  test("discharges exact Boolean identities used only by child bindings") {
    val declared = HdlBool.param("ENABLED", default = true)
    val used = HdlBool.param("ENABLED", default = false)
    val items = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        booleanParameterBindings = Vector(parameterBinding("CHILD_ENABLED", used))
      )
    }

    val error = intercept[FrontendException] {
      moduleDef(
        name = "AliasedBooleanBinding",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = items,
        booleanParameters = Vector(booleanParameter(declared))
      )
    }

    assert(error.code == "MORPH-FRONTEND-BOOLEAN-PARAMETER-TOKEN-MISMATCH")
    assert(error.origin == used.origin)
    assert(error.detail.contains(declared.origin.rendered))
  }

  test("rejects missing Boolean and integer identities used only by child bindings") {
    val missingBoolean = HdlBool.param("MISSING_BOOLEAN", default = true)
    val booleanItems = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        booleanParameterBindings = Vector(parameterBinding("CHILD_FLAG", missingBoolean))
      )
    }
    val booleanError = intercept[FrontendException] {
      moduleDef(
        name = "MissingBooleanBindingSource",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = booleanItems
      )
    }
    assert(booleanError.code == "MORPH-FRONTEND-BOOLEAN-PARAMETER-NOT-DECLARED")
    assert(booleanError.origin == missingBoolean.origin)

    val missingInteger = HdlInt.param("MISSING_INTEGER", default = 3, min = 0, max = 8)
    val integerItems = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        booleanParameterBindings = Vector(parameterBinding("CHILD_FLAG", missingInteger >= 2))
      )
    }
    val integerError = intercept[FrontendException] {
      moduleDef(
        name = "MissingIntegerBindingSource",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = integerItems
      )
    }
    assert(integerError.code == "MORPH-FRONTEND-PARAMETER-NOT-DECLARED")
    assert(integerError.origin == missingInteger.origin)
  }

  test("rejects opposite-kind public declarations used by Boolean child bindings") {
    val integerDeclaration = HdlInt.param("MODE", default = 1, min = 0, max = 1)
    val booleanUse = HdlBool.param("MODE", default = true)
    val booleanItems = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        booleanParameterBindings = Vector(parameterBinding("CHILD_MODE", booleanUse))
      )
    }
    val booleanError = intercept[FrontendException] {
      moduleDef(
        name = "BooleanUsedAsInteger",
        parameters = Vector(integerParameter(integerDeclaration)),
        ports = Vector.empty,
        items = booleanItems
      )
    }
    assert(booleanError.code == "MORPH-FRONTEND-PARAMETER-KIND-MISMATCH")
    assert(booleanError.origin == booleanUse.origin)

    val booleanDeclaration = HdlBool.param("COUNT", default = true)
    val integerUse = HdlInt.param("COUNT", default = 1, min = 0, max = 4)
    val integerItems = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        booleanParameterBindings = Vector(parameterBinding("CHILD_FLAG", integerUse >= 1))
      )
    }
    val integerError = intercept[FrontendException] {
      moduleDef(
        name = "IntegerUsedAsBoolean",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = integerItems,
        booleanParameters = Vector(booleanParameter(booleanDeclaration))
      )
    }
    assert(integerError.code == "MORPH-FRONTEND-PARAMETER-KIND-MISMATCH")
    assert(integerError.origin == integerUse.origin)
  }

  test("rejects missing and foreign local identities used only by Boolean bindings") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val localLimit = localParam("LOCAL_LIMIT", width + 1)
    val bindingItems = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        booleanParameterBindings = Vector(parameterBinding("CHILD_OK", localLimit > width))
      )
    }

    val missing = intercept[FrontendException] {
      moduleDef(
        name = "MissingLocalBindingSource",
        parameters = Vector(integerParameter(width)),
        ports = Vector.empty,
        items = bindingItems
      )
    }
    assert(missing.code == "MORPH-FRONTEND-LOCAL-PARAMETER-NOT-DECLARED")
    assert(missing.origin == localLimit.origin)

    moduleDef(
      name = "LocalOwner",
      parameters = Vector(integerParameter(width)),
      ports = Vector.empty,
      items = captureItems {},
      localParameters = Vector(integerLocalParameter(localLimit))
    )
    val foreign = intercept[FrontendException] {
      moduleDef(
        name = "ForeignLocalBindingSource",
        parameters = Vector(integerParameter(width)),
        ports = Vector.empty,
        items = bindingItems
      )
    }
    assert(foreign.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
    assert(foreign.origin == localLimit.origin)
    assert(foreign.detail.contains("LocalOwner"))
  }

  test("retains inactive Boolean operands and rejects loop-variant comparisons before binding") {
    val outer = HdlBool.param("OUTER", default = true)
    val inactiveWidth = HdlInt.param("INACTIVE_WIDTH", default = 8, min = 1, max = 64)
    val inactiveExpression = outer || (inactiveWidth >= 4)
    assert(
      inactiveExpression.expression == Or(
        BoolParameterRef("OUTER"),
        GreaterThanOrEqual(IntParameterRef("INACTIVE_WIDTH"), IntLiteral(4))
      )
    )

    val items = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        booleanParameterBindings = Vector(parameterBinding("CHILD_FLAG", inactiveExpression))
      )
    }
    val inactiveError = intercept[FrontendException] {
      moduleDef(
        name = "InactiveBindingSource",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = items,
        booleanParameters = Vector(booleanParameter(outer))
      )
    }
    assert(inactiveError.code == "MORPH-FRONTEND-PARAMETER-NOT-DECLARED")
    assert(inactiveError.origin == inactiveWidth.origin)

    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 1)
    var loopError: FrontendException = null
    captureItems {
      for (lane <- 0 until lanes) {
        loopError = intercept[FrontendException] {
          parameterBinding(
            "CHILD_FLAG",
            HdlBool.literal(value = true) || (lane * HdlInt.literal(1) >= 0)
          )
        }
      }
    }
    assert(loopError.code == "MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED")
  }

  test("does not weaken symbolic Boolean equality through the binding overload") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val other = HdlBool.literal(value = true)
    val error = intercept[FrontendException] {
      parameterBinding("CHILD_FLAG", enabled == other)
    }

    assert(error.code == "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED")
    assert(error.origin == enabled.origin)
  }
}
