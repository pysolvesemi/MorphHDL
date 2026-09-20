package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, Literal, Multiply, Subtract}
import morphhdl.paramrtl.ModuleItem.ModuleInstance
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class ParameterForwardingEmitterTests extends AnyFunSuite {
  test("emits the ParameterForwarding contract byte for byte") {
    assert(emit(ParameterForwardingFixture.design()) == ParameterForwardingFixture.expected)
  }

  test("emits dependencies before users and ignores construction order") {
    val normal = emit(ParameterForwardingFixture.design())
    val reversed = emit(ParameterForwardingFixture.design(reverseConstructionOrder = true))

    assert(normal == reversed)
    assert(normal.indexOf("module ForwardingLeaf") < normal.indexOf("module ParameterForwarding"))
  }

  test("retains symbolic named parameter forwarding") {
    val verilog = emit(ParameterForwardingFixture.design())

    assert(verilog.contains(".WIDTH(TOTAL_WIDTH)"))
    assert(verilog.contains(".din(din)"))
    assert(verilog.contains(".dout(dout)"))
    assert(!verilog.contains(".WIDTH(32)"))
    assert(verilog.split("module ForwardingLeaf", -1).length == 2)
  }

  test("sorts instances and their named associations") {
    val design = ParameterForwardingFixture.design()
    val top = design.modules.find(_.name == design.top).get
    val leaf = design.modules.find(_.name == "ForwardingLeaf").get
    val original = top.items.collectFirst { case value: ModuleInstance => value }.get
    val reverseBindings = Vector(
      original.parameterBindings.head,
      ParameterBinding("ALPHA", Literal(2))
    )
    val zInstance = original.copy(
      name = "z_instance",
      parameterBindings = reverseBindings,
      portConnections = original.portConnections.reverse
    )
    val aInstance = zInstance.copy(
      name = "a_instance",
      portConnections = Vector(
        PortConnection("dout", Ref("dout2")),
        PortConnection("din", Ref("din"))
      )
    )
    val extraOutput = top.ports.find(_.name == "dout").get.copy(name = "dout2")
    val reordered = design.copy(
      modules = design.modules.map {
        case module if module.name == leaf.name =>
          module.copy(
            parameters = module.parameters :+
              IntegerParameter("ALPHA", 2, Vector(MinInclusive(2), MaxInclusive(2)))
          )
        case module if module.name == top.name =>
          module.copy(ports = module.ports :+ extraOutput, items = Vector(zInstance, aInstance))
        case module => module
      }
    )

    val verilog = emit(reordered)
    assert(verilog.indexOf(") a_instance (") < verilog.indexOf(") z_instance ("))
    assert(verilog.indexOf(".ALPHA(2)") < verilog.indexOf(".WIDTH(TOTAL_WIDTH)"))
    assert(verilog.indexOf(".din(din)") < verilog.indexOf(".dout(dout2)"))
  }

  test("omits the parameter override block when bindings are omitted") {
    val design = ParameterForwardingFixture.design()
    val top = design.modules.find(_.name == design.top).get
    val instance = top.items.collectFirst { case value: ModuleInstance => value }.get
    val oneBit = PackedBits(Literal(1), Unsigned)
    val omitted = design.copy(
      modules = design.modules.map {
        case module if module.name == design.top =>
          module.copy(
            parameters = Vector.empty,
            localParameters = Vector.empty,
            ports = Vector(Port("din", Input, oneBit), Port("dout", Output, oneBit)),
            items = Vector(instance.copy(parameterBindings = Vector.empty))
          )
        case module => module
      }
    )

    val verilog = emit(omitted)
    assert(verilog.contains("ForwardingLeaf forwarded_inst ("))
    assert(!verilog.contains("  ForwardingLeaf #(\n"))
  }

  test("rejects a reserved instance identifier") {
    assertDiagnostic(withInstanceName("wire"), "V2001-RESERVED-IDENTIFIER")
  }

  test("rejects an out-of-range parameter-binding subtree") {
    val product = Multiply(Literal(50000), Literal(50000))
    val expression = Add(Subtract(product, product), Literal(1))
    val design = ParameterForwardingFixture.design()
    val invalid = design.copy(
      modules = design.modules.map {
        case module if module.name == design.top =>
          module.copy(
            parameters = Vector.empty,
            localParameters = Vector.empty,
            ports = Vector(
              Port("din", Input, PackedBits(Literal(1), Unsigned)),
              Port("dout", Output, PackedBits(Literal(1), Unsigned))
            ),
            items = module.items.map {
              case instance: ModuleInstance =>
                instance.copy(parameterBindings = Vector(ParameterBinding("WIDTH", expression)))
              case item => item
            }
          )
        case module => module
      }
    )

    assertDiagnostic(invalid, "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE")
  }

  private def withInstanceName(name: String): Design = {
    val design = ParameterForwardingFixture.design()
    design.copy(
      modules = design.modules.map {
        case module if module.name == design.top =>
          module.copy(items = module.items.map {
            case instance: ModuleInstance => instance.copy(name = name)
            case item                     => item
          })
        case module => module
      }
    )
  }

  private def emit(design: Design): String =
    Verilog2001Emitter.emit(design) match {
      case Right(value)      => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }

  private def assertDiagnostic(design: Design, code: String): Unit =
    Verilog2001Emitter.emit(design) match {
      case Left(diagnostics) => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n"))
      case Right(verilog)    => fail(s"Expected diagnostic $code, emitted:\n$verilog")
    }
}
