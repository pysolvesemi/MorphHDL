package morphhdl.frontend

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.BoolExpr.{And, GreaterThanOrEqual, Literal, LocalParameterRef, Not, Or, ParameterRef => BoolParameterRef}
import morphhdl.paramrtl.IntExpr.{Literal => IntLiteral, LocalParameterRef => IntLocalParameterRef, ParameterRef => IntParameterRef, Select}
import morphhdl.paramrtl.{BooleanLocalParameter, ModuleItem, PortDirection}
import org.scalatest.funsuite.AnyFunSuite

class BooleanLocalParameterFrontendTests extends AnyFunSuite {
  test("lowers Boolean locals with exact witnesses, expressions and dependency-first order") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val limit = localParam("LIMIT", HdlInt.literal(4))
    val base = localParam("BASE_ENABLED", enabled && (width >= limit))
    val routed = localParam("ROUTED", !base || HdlBool.literal(value = false))

    assert(base.witness)
    assert(routed.witness == false)
    assert(base.expression == LocalParameterRef("BASE_ENABLED"))
    assert(routed.expression == LocalParameterRef("ROUTED"))
    assert(
      booleanLocalParameter(base).raw == BooleanLocalParameter(
        "BASE_ENABLED",
        And(
          BoolParameterRef("ENABLED"),
          GreaterThanOrEqual(IntParameterRef("WIDTH"), IntLocalParameterRef("LIMIT"))
        )
      )
    )
    assert(
      booleanLocalParameter(routed).raw == BooleanLocalParameter(
        "ROUTED",
        Or(Not(LocalParameterRef("BASE_ENABLED")), Literal(false))
      )
    )

    val items = captureItems {
      generateIf(routed, "g_routed", "g_blocked") {
        emitInstance(name = "routed_inst", moduleName = "Routed")
      }.otherwise {
        emitInstance(name = "blocked_inst", moduleName = "Blocked")
      }
    }
    val module = moduleDef(
      name = "BooleanLocals",
      parameters = Vector(integerParameter(width)),
      ports = Vector.empty,
      items = items,
      localParameters = Vector(integerLocalParameter(limit)),
      booleanParameters = Vector(booleanParameter(enabled)),
      booleanLocalParameters = Vector(booleanLocalParameter(routed), booleanLocalParameter(base))
    )

    assert(module.booleanLocalParameters.map(_.name) == Vector("BASE_ENABLED", "ROUTED"))
    assert(
      module.items.head.asInstanceOf[ModuleItem.GenerateIf].condition ==
        LocalParameterRef("ROUTED")
    )
  }

  test("retains Boolean-local provenance through select, integer locals and child bindings") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val useWide = localParam("USE_WIDE", width >= 8)
    val selected = localParam("SELECTED", useWide.select(width, 4))
    val allowChild = localParam("ALLOW_CHILD", useWide && (selected >= 8))

    assert(
      integerLocalParameter(selected).raw.value == Select(
        LocalParameterRef("USE_WIDE"),
        IntParameterRef("WIDTH"),
        IntLiteral(4)
      )
    )
    assert(selected.booleanLocalParameters == useWide.booleanLocalParameters)
    assert(allowChild.localParameters == selected.localParameters)

    val items = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        booleanParameterBindings = Vector(parameterBinding("ENABLED", allowChild))
      )
    }
    val module = moduleDef(
      name = "MixedLocalDependencies",
      parameters = Vector(integerParameter(width)),
      ports = Vector(port("data", PortDirection.Input, packedBits(selected))),
      items = items,
      localParameters = Vector(integerLocalParameter(selected)),
      booleanLocalParameters = Vector(
        booleanLocalParameter(allowChild),
        booleanLocalParameter(useWide)
      )
    )

    assert(module.localParameters.map(_.name) == Vector("SELECTED"))
    assert(module.booleanLocalParameters.map(_.name) == Vector("USE_WIDE", "ALLOW_CHILD"))
    assert(items.booleanLocalParameters == allowChild.booleanLocalParameters)
  }

  test("orders independent Boolean locals lexically and preserves old positional moduleDef calls") {
    def names(reverse: Boolean): Vector[String] = {
      val zed = localParam("ZED", HdlBool.literal(value = true))
      val alpha = localParam("ALPHA", HdlBool.literal(value = false))
      val declarations = Vector(booleanLocalParameter(zed), booleanLocalParameter(alpha))
      moduleDef(
        if (reverse) "Reverse" else "Forward",
        Vector.empty,
        Vector.empty,
        captureItems {},
        Vector.empty,
        Vector.empty,
        if (reverse) declarations.reverse else declarations
      ).booleanLocalParameters.map(_.name)
    }

    assert(names(reverse = false) == Vector("ALPHA", "ZED"))
    assert(names(reverse = true) == Vector("ALPHA", "ZED"))
  }

  test("rejects missing use-only and dependency-only Boolean local declarations") {
    val useOnly = localParam("USE_ONLY", HdlBool.literal(value = true))
    val usedItems = captureItems {
      generateIf(useOnly) {
        emitInstance(name = "on_inst", moduleName = "On")
      }.otherwise {
        emitInstance(name = "off_inst", moduleName = "Off")
      }
    }
    val missingUse = intercept[FrontendException] {
      moduleDef("MissingUse", Vector.empty, Vector.empty, usedItems)
    }
    assert(missingUse.code == "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NOT-DECLARED")
    assert(missingUse.origin == useOnly.origin)

    val base = localParam("BASE", HdlBool.literal(value = true))
    val derived = localParam("DERIVED", !base)
    val missingDependency = intercept[FrontendException] {
      moduleDef(
        name = "MissingDependency",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        booleanLocalParameters = Vector(booleanLocalParameter(derived))
      )
    }
    assert(
      missingDependency.code == "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NOT-DECLARED"
    )
    assert(missingDependency.origin == base.origin)
  }

  test("rejects duplicate handles, duplicate names and distinct same-named uses") {
    val one = localParam("ONE", HdlBool.literal(value = true))
    val duplicateHandle = intercept[FrontendException] {
      moduleDef(
        name = "DuplicateHandle",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        booleanLocalParameters = Vector(booleanLocalParameter(one), booleanLocalParameter(one))
      )
    }
    assert(
      duplicateHandle.code ==
        "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-DECLARATION-DUPLICATE"
    )

    val first = localParam("SAME", HdlBool.literal(value = true))
    val second = localParam("SAME", HdlBool.literal(value = false))
    val duplicateName = intercept[FrontendException] {
      moduleDef(
        name = "DuplicateName",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        booleanLocalParameters = Vector(
          booleanLocalParameter(first),
          booleanLocalParameter(second)
        )
      )
    }
    assert(
      duplicateName.code == "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NAME-DUPLICATE"
    )

    val declared = localParam("ALIASED", HdlBool.literal(value = true))
    val used = localParam("ALIASED", HdlBool.literal(value = false))
    val aliasedItems = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        booleanParameterBindings = Vector(parameterBinding("FLAG", used))
      )
    }
    val mismatch = intercept[FrontendException] {
      moduleDef(
        name = "Aliased",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = aliasedItems,
        booleanLocalParameters = Vector(booleanLocalParameter(declared))
      )
    }
    assert(mismatch.code == "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-TOKEN-MISMATCH")
    assert(mismatch.origin == used.origin)
    assert(mismatch.detail.contains(declared.origin.rendered))
  }

  test("rejects local kind collisions and public-local name collisions") {
    val integerLocal = localParam("SHARED", HdlInt.literal(1))
    val booleanLocal = localParam("SHARED", HdlBool.literal(value = true))
    val kindCollision = intercept[FrontendException] {
      moduleDef(
        name = "LocalKindCollision",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        localParameters = Vector(integerLocalParameter(integerLocal)),
        booleanLocalParameters = Vector(booleanLocalParameter(booleanLocal))
      )
    }
    assert(kindCollision.code == "MORPH-FRONTEND-LOCAL-PARAMETER-KIND-COLLISION")
    assert(kindCollision.origin == booleanLocal.origin)

    val public = HdlBool.param("PUBLIC_NAME", default = true)
    val local = localParam("PUBLIC_NAME", HdlBool.literal(value = false))
    val publicCollision = intercept[FrontendException] {
      moduleDef(
        name = "PublicCollision",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        booleanParameters = Vector(booleanParameter(public)),
        booleanLocalParameters = Vector(booleanLocalParameter(local))
      )
    }
    assert(publicCollision.code == "MORPH-FRONTEND-LOCAL-PARAMETER-NAME-COLLISION")
    assert(publicCollision.origin == local.origin)
  }

  test("rejects opposite-kind same-named local uses without weakening typed adapters") {
    val declaredBoolean = localParam("VALUE", HdlBool.literal(value = true))
    val usedInteger = localParam("VALUE", HdlInt.literal(8))
    val integerUse = intercept[FrontendException] {
      moduleDef(
        name = "IntegerUseOfBooleanLocal",
        parameters = Vector.empty,
        ports = Vector(port("data", PortDirection.Input, packedBits(usedInteger))),
        items = captureItems {},
        booleanLocalParameters = Vector(booleanLocalParameter(declaredBoolean))
      )
    }
    assert(integerUse.code == "MORPH-FRONTEND-LOCAL-PARAMETER-KIND-MISMATCH")
    assert(integerUse.origin == usedInteger.origin)

    val declaredInteger = localParam("FLAG", HdlInt.literal(1))
    val usedBoolean = localParam("FLAG", HdlBool.literal(value = true))
    val booleanItems = captureItems {
      generateIf(usedBoolean) {
        emitInstance(name = "on_inst", moduleName = "On")
      }.otherwise {
        emitInstance(name = "off_inst", moduleName = "Off")
      }
    }
    val booleanUse = intercept[FrontendException] {
      moduleDef(
        name = "BooleanUseOfIntegerLocal",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = booleanItems,
        localParameters = Vector(integerLocalParameter(declaredInteger))
      )
    }
    assert(booleanUse.code == "MORPH-FRONTEND-LOCAL-PARAMETER-KIND-MISMATCH")
    assert(booleanUse.origin == usedBoolean.origin)

    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      integerLocalParameter(localParam("FLAG", HdlBool.literal(value = true)))
    """)
    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      booleanLocalParameter(localParam("WIDTH", HdlInt.literal(8)))
    """)
  }

  test("rejects foreign Boolean locals at declarations, uses and dependencies") {
    val foreign = localParam("FOREIGN", HdlBool.literal(value = true))
    val declaration = booleanLocalParameter(foreign)
    moduleDef(
      name = "Owner",
      parameters = Vector.empty,
      ports = Vector.empty,
      items = captureItems {},
      booleanLocalParameters = Vector(declaration)
    )

    val redeclared = intercept[FrontendException] {
      moduleDef(
        name = "OtherDeclaration",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        booleanLocalParameters = Vector(declaration)
      )
    }
    val useItems = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        booleanParameterBindings = Vector(parameterBinding("FLAG", foreign))
      )
    }
    val useOnly = intercept[FrontendException] {
      moduleDef("OtherUse", Vector.empty, Vector.empty, useItems)
    }
    val derived = localParam("DERIVED", !foreign)
    val dependencyOnly = intercept[FrontendException] {
      moduleDef(
        name = "OtherDependency",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        booleanLocalParameters = Vector(booleanLocalParameter(derived))
      )
    }

    Vector(redeclared, useOnly, dependencyOnly).foreach { error =>
      assert(error.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
      assert(error.origin == foreign.origin)
      assert(error.detail.contains("Owner"))
    }
  }

  test("does not claim a Boolean local when module validation rolls back") {
    val missing = HdlBool.param("MISSING", default = true)
    val local = localParam("LOCAL", missing)
    val first = intercept[FrontendException] {
      moduleDef(
        name = "InvalidOwner",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        booleanLocalParameters = Vector(booleanLocalParameter(local))
      )
    }
    assert(first.code == "MORPH-FRONTEND-BOOLEAN-PARAMETER-NOT-DECLARED")

    val recovered = moduleDef(
      name = "RecoveredOwner",
      parameters = Vector.empty,
      ports = Vector.empty,
      items = captureItems {},
      booleanParameters = Vector(booleanParameter(missing)),
      booleanLocalParameters = Vector(booleanLocalParameter(local))
    )
    assert(recovered.booleanLocalParameters.map(_.name) == Vector("LOCAL"))
  }

  test("creates fresh factory handles and atomically assigns one owner under contention") {
    def fresh(): HdlBool = localParam("FLAG", HdlBool.literal(value = true))
    val first = fresh()
    val second = fresh()
    assert(!(first.localDeclaration.get eq second.localDeclaration.get))

    moduleDef(
      name = "FreshOne",
      parameters = Vector.empty,
      ports = Vector.empty,
      items = captureItems {},
      booleanLocalParameters = Vector(booleanLocalParameter(first))
    )
    moduleDef(
      name = "FreshTwo",
      parameters = Vector.empty,
      ports = Vector.empty,
      items = captureItems {},
      booleanLocalParameters = Vector(booleanLocalParameter(second))
    )

    val contested = fresh()
    val contestedDeclaration = booleanLocalParameter(contested)
    implicit val executionContext: ExecutionContext = ExecutionContext.global
    val attempts = Vector("ThreadOne", "ThreadTwo").map { moduleName =>
      Future {
        try {
          moduleDef(
            name = moduleName,
            parameters = Vector.empty,
            ports = Vector.empty,
            items = captureItems {},
            booleanLocalParameters = Vector(contestedDeclaration)
          )
          Right(moduleName)
        } catch {
          case error: FrontendException => Left(error)
        }
      }
    }
    val results = Await.result(Future.sequence(attempts), 10.seconds)
    assert(results.count(_.isRight) == 1)
    val failure = results.collect { case Left(error) => error }.head
    assert(failure.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
    assert(failure.origin == contested.origin)
  }

  test("rejects null, invalid, forged and non-local Boolean declarations") {
    val nullValue = intercept[FrontendException] {
      localParam("NULL_VALUE", null.asInstanceOf[HdlBool])
    }
    assert(nullValue.code == "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-VALUE-NULL")

    val invalid = intercept[FrontendException] {
      localParam("not-portable", HdlBool.literal(value = true))
    }
    assert(invalid.code == "MORPH-FRONTEND-INVALID-LOCAL-PARAMETER-NAME")

    val forged = FrontendNode(
      BooleanLocalParameter("RAW", Literal(true)),
      origin = SourceOrigin("forged.scala", 9)
    )
    val unresolved = intercept[FrontendException] {
      moduleDef(
        name = "Forged",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        booleanLocalParameters = Vector(forged)
      )
    }
    assert(
      unresolved.code == "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-IDENTITY-UNRESOLVED"
    )
    assert(unresolved.sourceLocation == "forged.scala:9")

    val nonLocal = intercept[FrontendException] {
      booleanLocalParameter(HdlBool.literal(value = true))
    }
    assert(nonLocal.code == "MORPH-FRONTEND-NOT-A-BOOLEAN-LOCAL-PARAMETER")

    val integerHandle = localParam("INTEGER_RAW", HdlInt.literal(1))
    val wrongBooleanCollection = FrontendNode(
      BooleanLocalParameter("INTEGER_RAW", Literal(true)),
      localDeclaration = integerHandle.localDeclaration,
      origin = SourceOrigin("wrong-boolean.scala", 10)
    )
    val wrongBoolean = intercept[FrontendException] {
      moduleDef(
        name = "WrongBooleanCollection",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        booleanLocalParameters = Vector(wrongBooleanCollection)
      )
    }
    assert(
      wrongBoolean.code == "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-IDENTITY-UNRESOLVED"
    )

    val booleanHandle = localParam("BOOLEAN_RAW", HdlBool.literal(value = true))
    val wrongIntegerCollection = FrontendNode(
      morphhdl.paramrtl.IntegerLocalParameter("BOOLEAN_RAW", IntLiteral(1)),
      booleanLocalDeclaration = booleanHandle.localDeclaration,
      origin = SourceOrigin("wrong-integer.scala", 11)
    )
    val wrongInteger = intercept[FrontendException] {
      moduleDef(
        name = "WrongIntegerCollection",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        localParameters = Vector(wrongIntegerCollection)
      )
    }
    assert(wrongInteger.code == "MORPH-FRONTEND-LOCAL-PARAMETER-IDENTITY-UNRESOLVED")
  }

  test("keeps Boolean locals fail-closed for Scala equality, hashing and conversion") {
    val local = localParam("LOCAL", HdlBool.literal(value = true))
    val errors = Vector(
      intercept[FrontendException](local == HdlBool.literal(value = true)),
      intercept[FrontendException](local != false),
      intercept[FrontendException](local.hashCode)
    )
    errors.foreach { error =>
      assert(error.code == "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED")
      assert(error.origin == local.origin)
    }

    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      val local = localParam("LOCAL", HdlBool.literal(value = true))
      val collapsed: Boolean = local
    """)
  }
}
