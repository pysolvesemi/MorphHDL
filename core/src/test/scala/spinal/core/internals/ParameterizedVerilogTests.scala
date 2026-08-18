package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class ParameterizedVerilogTests extends AnyFunSuite {
  private val width =
    ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = 64)

  test("native Verilog retains a direct UInt width parameter and canonical port order") {
    withTemporaryDirectory { directory =>
      val report = generate(directory, parameterized = true, reversePorts = true)
      val verilog = read(directory.resolve("DirectWidthWire.v"))
      val module = verilog.substring(verilog.indexOf("module ")).trim + "\n"

      assert(
        module ==
          """module DirectWidthWire #(
            |  parameter integer WIDTH = 8
            |) (
            |  input  wire [WIDTH-1:0] din,
            |  output wire [WIDTH-1:0] dout
            |);
            |
            |  assign dout = din;
            |
            |endmodule
            |""".stripMargin
      )
      assert(ParameterizedWidth.parametersOf(report.toplevel) == Vector(width))
    }
  }

  test("legacy Verilog ignores retained width metadata unless explicitly enabled") {
    withTemporaryDirectory { directory =>
      generate(directory, parameterized = false, reversePorts = false)
      val verilog = read(directory.resolve("DirectWidthWire.v"))

      assert(!verilog.contains("parameter integer WIDTH"))
      assert(verilog.contains("input  wire [7:0]    din"))
      assert(verilog.contains("output wire [7:0]    dout"))
    }
  }

  test("parameterized mode rejects a concrete-only bit-count bridge") {
    withTemporaryDirectory { directory =>
      val failure = intercept[ParameterizedVerilogException] {
        SpinalVerilog(parameterizedConfig(directory)) {
          new Component {
            setDefinitionName("ConcreteOnlyWidthWire")
            private val concrete = ParameterizedBitCount(8, parameter = None)
            val din = in(ParameterizedWidth.UInt(concrete))
            val dout = out(ParameterizedWidth.UInt(concrete))
            dout := din
          }
        }
      }

      assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-UNTAGGED-PORT")
    }
  }

  test("rejects a parameter whose full domain exceeds the configured width limit") {
    withTemporaryDirectory { directory =>
      val tooWide = width.copy(maximum = 65)
      val failure = intercept[ParameterizedVerilogException] {
        generate(
          directory,
          parameterized = true,
          reversePorts = false,
          parameter = tooWide,
          bitVectorWidthMax = 64
        )
      }

      assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-DOMAIN-INVALID")
      assert(failure.detail.contains("bitVectorWidthMax=64"))
    }
  }

  test("rejects a same-module parameter and port identifier collision") {
    withTemporaryDirectory { directory =>
      val collision = width.copy(name = "din")
      val failure = intercept[ParameterizedVerilogException] {
        generate(
          directory,
          parameterized = true,
          reversePorts = false,
          parameter = collision
        )
      }

      assert(
        failure.code ==
          "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-PORT-NAME-COLLISION"
      )
    }
  }

  test("rejects an IEEE 1364 reserved parameter identifier") {
    withTemporaryDirectory { directory =>
      val reserved = width.copy(name = "wire")
      val failure = intercept[ParameterizedVerilogException] {
        generate(
          directory,
          parameterized = true,
          reversePorts = false,
          parameter = reserved
        )
      }

      assert(
        failure.code ==
          "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-NAME-RESERVED"
      )
    }
  }

  test("rejects direct assignments across distinct symbolic width schemas") {
    withTemporaryDirectory { directory =>
      val failure = intercept[ParameterizedVerilogException] {
        SpinalVerilog(parameterizedConfig(directory)) {
          new Component {
            setDefinitionName("MismatchedWidthWire")
            val din = in(ParameterizedWidth.UInt(ParameterizedBitCount(8, width)))
            val dout = out(
              ParameterizedWidth.UInt(
                ParameterizedBitCount(
                  8,
                  ElaborationIntegerParameter("OTHER_WIDTH", 8, 1, 64)
                )
              )
            )
            dout := din
          }
        }
      }

      assert(
        failure.code ==
          "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
      )
    }
  }

  test("rejects conflicting declarations for the same retained parameter name") {
    withTemporaryDirectory { directory =>
      val failure = intercept[ParameterizedVerilogException] {
        SpinalVerilog(parameterizedConfig(directory)) {
          new Component {
            setDefinitionName("ConflictingWidthWire")
            val din = in(
              ParameterizedWidth.UInt(
                ParameterizedBitCount(
                  8,
                  width,
                  sourceLocation = Some("ConflictingWidthWire.scala:12")
                )
              )
            )
            val dout = out(
              ParameterizedWidth.UInt(
                ParameterizedBitCount(
                  8,
                  width.copy(maximum = 32),
                  sourceLocation = Some("ConflictingWidthWire.scala:13")
                )
              )
            )
            dout := din
          }
        }
      }

      assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT")
      assert(failure.sourceLocation.contains("ConflictingWidthWire.scala:12"))
      assert(failure.getMessage.contains("ConflictingWidthWire.scala:12"))
    }
  }

  test("rejects an input-only tagged component outside the direct-wire slice") {
    withTemporaryDirectory { directory =>
      val failure = intercept[ParameterizedVerilogException] {
        SpinalVerilog(parameterizedConfig(directory)) {
          new Component {
            setDefinitionName("InputOnlyWidth")
            val din = in(ParameterizedWidth.UInt(ParameterizedBitCount(8, width)))
          }
        }
      }

      assert(
        failure.code ==
          "SPINAL-PARAMETERIZED-VERILOG-PORT-DIRECTIONS-UNSUPPORTED"
      )
    }
  }

  private def generate(
      directory: Path,
      parameterized: Boolean,
      reversePorts: Boolean,
      parameter: ElaborationIntegerParameter = width,
      bitVectorWidthMax: Int = 4096
  ): SpinalReport[Component] = {
    val config = parameterizedConfig(directory).copy(
      parameterizedVerilog = parameterized,
      bitVectorWidthMax = bitVectorWidthMax
    )
    SpinalVerilog(config) {
      new Component {
        setDefinitionName("DirectWidthWire")
        private val bitCount = ParameterizedBitCount(parameter.default.toInt, parameter)
        private val ports =
          if (reversePorts) {
            val output = out(ParameterizedWidth.UInt(bitCount)).setName("dout")
            val input = in(ParameterizedWidth.UInt(bitCount)).setName("din")
            (input, output)
          } else {
            val input = in(ParameterizedWidth.UInt(bitCount)).setName("din")
            val output = out(ParameterizedWidth.UInt(bitCount)).setName("dout")
            (input, output)
          }
        ports._2 := ports._1
      }
    }
  }

  private def parameterizedConfig(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      headerWithRepoHash = false,
      withTimescale = false,
      printFilelist = false,
      parameterizedVerilog = true
    )

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-parameterized-verilog-test-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
