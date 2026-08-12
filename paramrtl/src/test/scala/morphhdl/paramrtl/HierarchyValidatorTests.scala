package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr._
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, ModuleInstance}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import org.scalatest.funsuite.AnyFunSuite

class HierarchyValidatorTests extends AnyFunSuite {
  test("validates named forwarding through a derived parent local parameter") {
    val design = hierarchy(binding = LocalParameterRef("FORWARDED"), parentWidth = ParameterRef("WIDTH"))
    val validated = valid(design)
    val facts = validated.moduleFacts("Parent").instanceFacts("child")

    assert(facts.parameterFacts("WIDTH").defaultValue == 8)
    assert(facts.parameterFacts("WIDTH").interval == interval(1, 16))
    assert(facts.instantiatedPortTypes("din").width == ParameterRef("WIDTH"))
    assert(validated.orderedModules.map(_.name) == Vector("Child", "Parent"))
  }

  test("uses a child parameter default when the named binding is omitted") {
    val design = hierarchy(binding = Literal(8), parentWidth = Literal(8), includeBinding = false)
    val facts = valid(design).moduleFacts("Parent").instanceFacts("child")

    assert(facts.parameterFacts("WIDTH") == IntExprFacts(8, IntInterval.point(8)))
  }

  test("rejects a binding whose whole parent domain is not contained by child constraints") {
    val design = hierarchy(
      binding = ParameterRef("WIDTH"),
      parentWidth = ParameterRef("WIDTH"),
      parentMaximum = 32,
      childMaximum = 16
    )
    assertCodes(design, "PRTL-PARAMETER-BINDING-DOMAIN-NOT-PROVEN")
  }

  test("resolves binding expressions only in parent scope") {
    val parent = parentModule(
      binding = ParameterRef("CHILD_ONLY"),
      parentWidth = Literal(8),
      includeBinding = true
    )
    val child = childModule(parameterName = "CHILD_ONLY")
    val diagnostics = invalid(Design("Parent", Vector(parent, child)))
    assert(diagnostics.codes.contains("PRTL-UNRESOLVED-PARAMETER"))
  }

  test("rejects duplicate and unknown parameter bindings") {
    val base = parentModule(ParameterRef("WIDTH"), ParameterRef("WIDTH"))
    val instance = onlyInstance(base).copy(
      parameterBindings = Vector(
        ParameterBinding("WIDTH", ParameterRef("WIDTH")),
        ParameterBinding("WIDTH", Literal(8)),
        ParameterBinding("UNKNOWN", Literal(8))
      )
    )
    val design = Design("Parent", Vector(base.copy(items = Vector(instance)), childModule()))
    assertCodes(design, "PRTL-DUPLICATE-PARAMETER-BINDING", "PRTL-UNRESOLVED-INSTANCE-PARAMETER")
  }

  test("rejects duplicate unknown and missing named port connections") {
    val base = parentModule(ParameterRef("WIDTH"), ParameterRef("WIDTH"))
    val instance = onlyInstance(base).copy(
      portConnections = Vector(
        PortConnection("din", Ref("din")),
        PortConnection("din", Ref("din")),
        PortConnection("unknown", Ref("dout"))
      )
    )
    val design = Design("Parent", Vector(base.copy(items = Vector(instance)), childModule()))
    assertCodes(
      design,
      "PRTL-DUPLICATE-PORT-CONNECTION",
      "PRTL-UNRESOLVED-INSTANCE-PORT",
      "PRTL-MISSING-INSTANCE-PORT-CONNECTION",
      "PRTL-UNDRIVEN-OUTPUT"
    )
  }

  test("rejects an unresolved instance module") {
    val base = parentModule(ParameterRef("WIDTH"), ParameterRef("WIDTH"))
    val broken = onlyInstance(base).copy(moduleName = "Missing")
    assertCodes(Design("Parent", Vector(base.copy(items = Vector(broken)))), "PRTL-UNRESOLVED-INSTANCE-MODULE")
  }

  test("accepts commutatively equivalent widths and rejects equal intervals alone") {
    val equivalent = hierarchy(
      binding = Add(ParameterRef("WIDTH"), ParameterRef("PAD")),
      parentWidth = Add(ParameterRef("PAD"), ParameterRef("WIDTH")),
      parentExtraParameter = true,
      childMaximum = 32
    )
    assert(ParamRtlValidator.validate(equivalent).isRight)

    val sameRangeOnly = hierarchy(
      binding = ParameterRef("WIDTH"),
      parentWidth = ParameterRef("ALTERNATE"),
      parentExtraParameter = true
    )
    assertCodes(sameRangeOnly, "PRTL-INSTANCE-PORT-TYPE-MISMATCH")
  }

  test("rejects signedness mismatch") {
    val child = childModule().copy(
      ports = childModule().ports.map(port => port.copy(dataType = port.dataType.copy(signedness = Signed)))
    )
    val parent = parentModule(ParameterRef("WIDTH"), ParameterRef("WIDTH"))
    assertCodes(Design("Parent", Vector(parent, child)), "PRTL-INSTANCE-PORT-TYPE-MISMATCH")
  }

  test("counts child output drivers with continuous assignments and rejects driving a parent input") {
    val base = parentModule(ParameterRef("WIDTH"), ParameterRef("WIDTH"))
    val withSecondDriver = base.copy(items = base.items :+ ContinuousAssign(Ref("dout"), Ref("din")))
    assertCodes(Design("Parent", Vector(withSecondDriver, childModule())), "PRTL-MULTIPLE-DRIVERS")

    val instance = onlyInstance(base)
    val reversed = instance.copy(portConnections =
      Vector(
        PortConnection("din", Ref("dout")),
        PortConnection("dout", Ref("din"))
      )
    )
    assertCodes(
      Design("Parent", Vector(base.copy(items = Vector(reversed)), childModule())),
      "PRTL-ILLEGAL-INPUT-DRIVER"
    )
  }

  test("rejects instance namespace collisions and duplicates") {
    val base = parentModule(ParameterRef("WIDTH"), ParameterRef("WIDTH"))
    val instance = onlyInstance(base)
    val colliding = instance.copy(name = "din")
    val duplicate = instance.copy(name = "din")
    assertCodes(
      Design("Parent", Vector(base.copy(items = Vector(colliding, duplicate)), childModule())),
      "PRTL-DUPLICATE-DECLARATION",
      "PRTL-DUPLICATE-INSTANCE"
    )
  }

  test("rejects self and mutual module cycles deterministically") {
    val self = emptyModule("Self", Vector(simpleInstance("self", "Self")))
    val a = emptyModule("A", Vector(simpleInstance("b", "B")))
    val b = emptyModule("B", Vector(simpleInstance("a", "A")))

    val normal = invalid(Design("Self", Vector(self, a, b))).values
      .filter(_.code == "PRTL-MODULE-INSTANTIATION-CYCLE")
    val reversed = invalid(Design("Self", Vector(b, a, self))).values
      .filter(_.code == "PRTL-MODULE-INSTANTIATION-CYCLE")
    assert(normal == reversed)
    assert(
      normal.map(_.message) == Vector(
        "Module-instantiation cycle members: A, B",
        "Module-instantiation cycle members: Self"
      )
    )
  }

  test("orders a long hierarchy dependency-first without recursion") {
    val count = 2000
    val names = (0 until count).map(index => f"Chain_$index%04d").toVector
    val modules = names.zipWithIndex.map { case (name, index) =>
      if (index == 0) emptyModule(name)
      else emptyModule(name, Vector(simpleInstance("child", names(index - 1))))
    }
    val validated = valid(Design(names.last, modules.reverse))
    assert(validated.orderedModules.map(_.name) == names)
  }

  test("cyclic local parameter in a binding fails diagnostically without throwing") {
    val base = parentModule(LocalParameterRef("A"), Literal(8)).copy(
      localParameters = Vector(
        IntegerLocalParameter("A", LocalParameterRef("B")),
        IntegerLocalParameter("B", LocalParameterRef("A"))
      )
    )
    val diagnostics = invalid(Design("Parent", Vector(base, childModule())))
    assert(diagnostics.codes.contains("PRTL-LOCAL-PARAMETER-CYCLE"))
    assert(!diagnostics.codes.contains("PRTL-UNRESOLVED-LOCAL-PARAMETER"))
  }

  test("illegal parameter default in a binding does not produce an unresolved-reference cascade") {
    val base = parentModule(ParameterRef("WIDTH"), Literal(8)).copy(
      parameters = Vector(bounded("WIDTH", 0, 1, 16))
    )
    val diagnostics = invalid(Design("Parent", Vector(base, childModule())))
    assert(diagnostics.codes.contains("PRTL-DEFAULT-VIOLATES-CONSTRAINT"))
    assert(!diagnostics.codes.contains("PRTL-UNRESOLVED-PARAMETER"))
  }

  test("handles matching and mismatched 8000-local child hierarchies without recursive expansion") {
    val count = 8000
    val names = (0 until count).map(index => f"LOCAL_$index%05d").toVector
    val locals = names.zipWithIndex.map { case (name, index) =>
      IntegerLocalParameter(
        name,
        if (index == 0) Literal(1) else Add(LocalParameterRef(names(index - 1)), Literal(1))
      )
    }
    val childPacked = PackedBits(LocalParameterRef(names.last), Unsigned)
    val child = ModuleDef(
      "Child",
      Vector.empty,
      Vector(Port("din", Input, childPacked), Port("dout", Output, childPacked)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      locals
    )
    val parentPacked = PackedBits(Literal(count), Unsigned)
    val parent = ModuleDef(
      "Parent",
      Vector.empty,
      Vector(Port("din", Input, parentPacked), Port("dout", Output, parentPacked)),
      Vector(
        ModuleInstance(
          "child",
          "Child",
          portConnections = Vector(
            PortConnection("din", Ref("din")),
            PortConnection("dout", Ref("dout"))
          )
        )
      )
    )
    assert(ParamRtlValidator.validate(Design("Parent", Vector(parent, child))).isRight)

    val mismatchedPacked = PackedBits(Literal(count - 1), Unsigned)
    val mismatchedParent = parent.copy(
      ports = parent.ports.map(port => port.copy(dataType = mismatchedPacked))
    )
    val diagnostics = invalid(Design("Parent", Vector(mismatchedParent, child)))
    assert(diagnostics.codes.contains("PRTL-INSTANCE-PORT-TYPE-MISMATCH"))
  }

  test("does not use two equal unbounded intervals as symbolic width equality proof") {
    val left = IntExprFacts(8, IntInterval(None, None))
    val right = IntExprFacts(8, IntInterval(None, None))
    assert(left.interval.lower.isEmpty && right.interval.upper.isEmpty)
    assert(!IntExpressionEquivalence.equivalent(ParameterRef("LEFT"), ParameterRef("RIGHT")))
  }

  test("compares separately built shared expression DAGs without exponential revisits") {
    def sharedDag(): IntExpr = {
      var value: IntExpr = ParameterRef("WIDTH")
      (0 until 80).foreach { _ => value = Add(value, value) }
      value
    }
    assert(IntExpressionEquivalence.equivalent(sharedDag(), sharedDag()))
  }

  private def hierarchy(
      binding: IntExpr,
      parentWidth: IntExpr,
      includeBinding: Boolean = true,
      parentMaximum: BigInt = 16,
      childMaximum: BigInt = 16,
      parentExtraParameter: Boolean = false
  ): Design =
    Design(
      "Parent",
      Vector(
        parentModule(binding, parentWidth, includeBinding, parentMaximum, parentExtraParameter),
        childModule(maximum = childMaximum)
      )
    )

  private def parentModule(
      binding: IntExpr,
      parentWidth: IntExpr,
      includeBinding: Boolean = true,
      maximum: BigInt = 16,
      extraParameter: Boolean = false
  ): ModuleDef = {
    val parameters = Vector(bounded("WIDTH", 8, 1, maximum)) ++
      (if (extraParameter) Vector(bounded("PAD", 1, 1, 1), bounded("ALTERNATE", 8, 1, maximum)) else Vector.empty)
    val packed = PackedBits(parentWidth, Unsigned)
    val instance = ModuleInstance(
      "child",
      "Child",
      if (includeBinding) Vector(ParameterBinding("WIDTH", binding)) else Vector.empty,
      Vector(PortConnection("din", Ref("din")), PortConnection("dout", Ref("dout")))
    )
    ModuleDef(
      "Parent",
      parameters,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(instance),
      Vector(IntegerLocalParameter("FORWARDED", ParameterRef("WIDTH")))
    )
  }

  private def childModule(parameterName: String = "WIDTH", maximum: BigInt = 16): ModuleDef = {
    val packed = PackedBits(ParameterRef(parameterName), Unsigned)
    ModuleDef(
      "Child",
      Vector(bounded(parameterName, 8, 1, maximum)),
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
  }

  private def emptyModule(name: String, items: Vector[ModuleItem] = Vector.empty): ModuleDef =
    ModuleDef(name, Vector.empty, Vector.empty, items)

  private def simpleInstance(name: String, moduleName: String): ModuleInstance =
    ModuleInstance(name, moduleName)

  private def onlyInstance(module: ModuleDef): ModuleInstance =
    module.items.collectFirst { case instance: ModuleInstance => instance }.get

  private def bounded(name: String, default: BigInt, minimum: BigInt, maximum: BigInt): IntegerParameter =
    IntegerParameter(name, default, Vector(MinInclusive(minimum), MaxInclusive(maximum)))

  private def interval(lower: BigInt, upper: BigInt): IntInterval =
    IntInterval.bounded(lower, upper).get

  private def valid(design: Design): ValidatedDesign = ParamRtlValidator.validate(design) match {
    case Right(value)      => value
    case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
  }

  private def invalid(design: Design): DiagnosticSet = ParamRtlValidator.validate(design) match {
    case Left(value) => value
    case Right(_)    => fail("Expected validation failure")
  }

  private def assertCodes(design: Design, expected: String*): Unit = {
    val diagnostics = invalid(design)
    expected.foreach(code => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n")))
    assert(diagnostics.values == diagnostics.values.sortBy(d => (d.pathString, d.code, d.message)))
  }
}
