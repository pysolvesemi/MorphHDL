package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntExpr.{Add, Divide, GenerateIndexRef, Literal, Modulo, Multiply, Subtract}
import morphhdl.paramrtl.ModuleItem.{GenerateFor, ModuleInstance}
import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class LaneArrayEmitterTests extends AnyFunSuite {
  test("emits the LaneArray contract byte for byte") {
    assert(emit(LaneArrayFixture.design()) == LaneArrayFixture.expected)
  }

  test("emits dependencies first and ignores construction order") {
    val normal = emit(LaneArrayFixture.design())
    val reversed = emit(LaneArrayFixture.design(reverseConstructionOrder = true))

    assert(normal == reversed)
    assert(normal == emit(LaneArrayFixture.design()))
    assert(normal.indexOf("module PixelLane") < normal.indexOf("module LaneArray"))
  }

  test("retains one symbolic generate loop instead of unrolling configurations") {
    val verilog = emit(LaneArrayFixture.design())

    assert(verilog.contains("genvar lane;"))
    assert(verilog.contains("for (lane = 0; lane < LANES; lane = lane + 1) begin : g_lane"))
    assert(verilog.contains(".DATA_WIDTH(DATA_WIDTH)"))
    assert(verilog.contains("data_in[lane * DATA_WIDTH +: DATA_WIDTH]"))
    assert(verilog.contains("data_out[lane * DATA_WIDTH +: DATA_WIDTH]"))
    assert(!verilog.contains(".DATA_WIDTH(8)"))
    assert(!verilog.contains("PixelLane__"))
    assert(!verilog.contains("LaneArray__"))
    assert(verilog.split("module PixelLane", -1).length == 2)
    assert(verilog.split("module LaneArray", -1).length == 2)
    assert(verilog.split("for \\(", -1).length == 2)
  }

  test("sorts generated named associations deterministically") {
    val verilog = emit(LaneArrayFixture.design(reverseConstructionOrder = true))

    assert(verilog.indexOf(".data_in(") < verilog.indexOf(".data_out("))
  }

  test("sorts multiple generate loops and genvar declarations deterministically") {
    val normal = emit(twoGenerateDesign(reverseItems = false))
    val reversed = emit(twoGenerateDesign(reverseItems = true))

    assert(normal == reversed)
    assert(normal.indexOf("genvar a_lane;") < normal.indexOf("genvar z_lane;"))
    assert(normal.indexOf("begin : a_generate") < normal.indexOf("begin : z_generate"))
  }

  test("rejects a reserved generate label") {
    assertDiagnostic(mapGenerate(LaneArrayFixture.design())(_.copy(label = "generate")), "V2001-RESERVED-IDENTIFIER")
  }

  test("rejects a reserved generate index") {
    val invalid = mapGenerate(LaneArrayFixture.design()) { generate =>
      generate.copy(
        indexName = "wire",
        body = generate.body.map {
          case instance: ModuleInstance =>
            instance.copy(portConnections = instance.portConnections.map { connection =>
              connection.copy(actual = mapIndexedPartSelect(connection.actual) { select =>
                select.copy(offset = renameGenerateIndex(select.offset, "lane", "wire"))
              })
            })
          case item => item
        }
      )
    }

    assertDiagnostic(invalid, "V2001-RESERVED-IDENTIFIER")
  }

  test("checks portable integer bounds inside generated indexed selects") {
    val product = Multiply(Literal(50000), Literal(50000))
    val zero = Subtract(product, product)
    val invalid = mapGenerate(LaneArrayFixture.design()) { generate =>
      generate.copy(body = generate.body.map {
        case instance: ModuleInstance =>
          instance.copy(portConnections = instance.portConnections.map { connection =>
            connection.copy(actual = mapIndexedPartSelect(connection.actual) { select =>
              select.copy(
                offset = Multiply(
                  Add(GenerateIndexRef(generate.indexName), zero),
                  select.width
                )
              )
            })
          })
        case item => item
      })
    }

    assertDiagnostic(invalid, "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE")
  }

  test("rejects dead divide and modulo by zero terms before emitting") {
    Vector(Divide(Literal(1), Literal(0)), Modulo(Literal(1), Literal(0))).foreach { invalidTerm =>
      val invalid = mapGenerate(LaneArrayFixture.design()) { generate =>
        generate.copy(body = generate.body.map {
          case instance: ModuleInstance =>
            instance.copy(portConnections = instance.portConnections.map { connection =>
              connection.copy(actual = mapIndexedPartSelect(connection.actual) { select =>
                select.copy(offset = Add(select.offset, Multiply(Literal(0), invalidTerm)))
              })
            })
          case item => item
        })
      }

      assertDiagnostic(invalid, "PRTL-DIVISOR-MAY-BE-ZERO")
    }
  }

  test("checks reserved identifiers on instances nested in a generate body") {
    val invalid = mapGenerate(LaneArrayFixture.design()) { generate =>
      generate.copy(body = generate.body.map {
        case instance: ModuleInstance => instance.copy(name = "wire")
        case item                     => item
      })
    }

    assertDiagnostic(invalid, "V2001-RESERVED-IDENTIFIER")
  }

  private def mapGenerate(design: Design)(operation: GenerateFor => GenerateFor): Design =
    design.copy(modules = design.modules.map {
      case module if module.name == design.top =>
        module.copy(items = module.items.map {
          case generate: GenerateFor => operation(generate)
          case item                  => item
        })
      case module => module
    })

  private def twoGenerateDesign(reverseItems: Boolean): Design = {
    val design = LaneArrayFixture.design()
    val top = design.modules.find(_.name == design.top).get
    val original = top.items.collectFirst { case generate: GenerateFor => generate }.get
    val input = top.ports.find(_.name == "data_in").get
    val output = top.ports.find(_.name == "data_out").get
    val secondInput = input.copy(name = "second_data_in")
    val secondOutput = output.copy(name = "second_data_out")
    val first = retargetGenerate(
      original,
      label = "z_generate",
      indexName = "z_lane",
      instanceName = "z_lane_inst",
      inputName = input.name,
      outputName = output.name
    )
    val second = retargetGenerate(
      original,
      label = "a_generate",
      indexName = "a_lane",
      instanceName = "a_lane_inst",
      inputName = secondInput.name,
      outputName = secondOutput.name
    )
    val items = Vector(first, second)

    design.copy(modules = design.modules.map {
      case module if module.name == design.top =>
        module.copy(
          ports = module.ports ++ Vector(secondOutput, secondInput),
          items = if (reverseItems) items.reverse else items
        )
      case module => module
    })
  }

  private def retargetGenerate(
      generate: GenerateFor,
      label: String,
      indexName: String,
      instanceName: String,
      inputName: String,
      outputName: String
  ): GenerateFor =
    generate.copy(
      label = label,
      indexName = indexName,
      body = generate.body.map {
        case instance: ModuleInstance =>
          instance.copy(
            name = instanceName,
            portConnections = instance.portConnections.map { connection =>
              val baseName = if (connection.portName == "data_in") inputName else outputName
              connection.copy(actual = mapIndexedPartSelect(connection.actual) { select =>
                select.copy(
                  base = Ref(baseName),
                  offset = renameGenerateIndex(select.offset, generate.indexName, indexName)
                )
              })
            }
          )
        case item => item
      }
    )

  private def mapIndexedPartSelect(expression: RtlExpr)(
      operation: IndexedPartSelect => IndexedPartSelect
  ): RtlExpr = expression match {
    case select: IndexedPartSelect => operation(select)
    case other                     => other
  }

  private def renameGenerateIndex(expression: IntExpr, from: String, to: String): IntExpr = expression match {
    case GenerateIndexRef(name) if name == from => GenerateIndexRef(to)
    case Add(left, right) =>
      Add(renameGenerateIndex(left, from, to), renameGenerateIndex(right, from, to))
    case Subtract(left, right) =>
      Subtract(renameGenerateIndex(left, from, to), renameGenerateIndex(right, from, to))
    case Multiply(left, right) =>
      Multiply(renameGenerateIndex(left, from, to), renameGenerateIndex(right, from, to))
    case other => other
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
