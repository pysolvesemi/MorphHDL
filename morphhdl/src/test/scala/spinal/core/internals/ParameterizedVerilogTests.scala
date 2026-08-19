package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.annotation.tailrec
import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.{MorphSingleSourceVerilogReport, MorphVerilog, MorphVerilogException}

class ParameterizedVerilogTests extends AnyFunSuite {
  private val width =
    ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = 64)

  test("external Verilog retains a direct UInt width parameter and canonical port order") {
    withTemporaryDirectory { directory =>
      val report = generateParameterized(directory, reversePorts = true)
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
      assert(report.parameters.map(_.name) == Vector("WIDTH"))

      val metadataDirectory = directory.resolve("metadata")
      Files.createDirectories(metadataDirectory)
      val metadata = SpinalVerilog(concreteConfig(metadataDirectory)) {
        directWidthComponent(reversePorts = true, width)
      }
      assert(ParameterizedWidth.parametersOf(metadata.toplevel) == Vector(width))
    }
  }

  test("legacy Verilog ignores retained width metadata unless explicitly enabled") {
    withTemporaryDirectory { directory =>
      SpinalVerilog(concreteConfig(directory)) {
        directWidthComponent(reversePorts = false, width)
      }
      val verilog = read(directory.resolve("DirectWidthWire.v"))

      assert(!verilog.contains("parameter integer WIDTH"))
      assert(verilog.contains("input  wire [7:0]    din"))
      assert(verilog.contains("output wire [7:0]    dout"))
    }
  }

  test("parameterized mode rejects a concrete-only bit-count bridge") {
    val failure = interceptParameterized() { () =>
      new Component {
        setDefinitionName("ConcreteOnlyWidthWire")
        private val concrete = ParameterizedBitCount(8, parameter = None)
        val din = in(ParameterizedWidth.UInt(concrete))
        val dout = out(ParameterizedWidth.UInt(concrete))
        dout := din
      }
    }

    assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-UNTAGGED-PORT")
  }

  test("rejects a parameter whose full domain exceeds the configured width limit") {
    val tooWide = width.copy(maximum = 65)
    val failure = interceptParameterized(bitVectorWidthMax = 64) { () =>
      directWidthComponent(reversePorts = false, tooWide)
    }

    assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-DOMAIN-INVALID")
    assert(failure.detail.contains("bitVectorWidthMax=64"))
  }

  test("rejects a same-module parameter and port identifier collision") {
    val collision = width.copy(name = "din")
    val failure = interceptParameterized() { () =>
      directWidthComponent(reversePorts = false, collision)
    }

    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-PORT-NAME-COLLISION"
    )
  }

  test("rejects an IEEE 1364 reserved parameter identifier") {
    val reserved = width.copy(name = "wire")
    val failure = interceptParameterized() { () =>
      directWidthComponent(reversePorts = false, reserved)
    }

    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-NAME-RESERVED"
    )
  }

  test("rejects direct assignments across distinct symbolic width schemas") {
    val failure = interceptParameterized() { () =>
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

    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
    )
  }

  test("rejects conflicting declarations for the same retained parameter name") {
    val failure = interceptParameterized() { () =>
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

    assert(failure.code == "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT")
    assert(failure.sourceLocation.contains("ConflictingWidthWire.scala:12"))
    assert(failure.getMessage.contains("ConflictingWidthWire.scala:12"))
  }

  test("rejects an input-only tagged component outside the direct-wire slice") {
    val failure = interceptParameterized() { () =>
      new Component {
        setDefinitionName("InputOnlyWidth")
        val din = in(ParameterizedWidth.UInt(ParameterizedBitCount(8, width)))
      }
    }

    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-PORT-DIRECTIONS-UNSUPPORTED"
    )
  }

  private def directWidthComponent(
      reversePorts: Boolean,
      parameter: ElaborationIntegerParameter
  ): Component =
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

  private def generateParameterized(
      directory: Path,
      reversePorts: Boolean,
      parameter: ElaborationIntegerParameter = width,
      bitVectorWidthMax: Int = 4096
  ): MorphSingleSourceVerilogReport =
    MorphVerilog(
      SpinalConfig(
        targetDirectory = directory.toString,
        bitVectorWidthMax = bitVectorWidthMax
      )
    ) {
      directWidthComponent(reversePorts, parameter)
    }

  private def concreteConfig(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      headerWithRepoHash = false,
      withTimescale = false,
      printFilelist = false
    )

  private def interceptParameterized(
      bitVectorWidthMax: Int = 4096
  )(factory: () => Component): ParameterizedVerilogException =
    withTemporaryDirectory { directory =>
      val error = intercept[MorphVerilogException] {
        MorphVerilog(
          SpinalConfig(
            targetDirectory = directory.toString,
            bitVectorWidthMax = bitVectorWidthMax
          )
        ) {
          factory()
        }
      }
      findParameterized(error).getOrElse {
        fail(s"Expected ParameterizedVerilogException, received ${error.failure}")
      }
    }

  @tailrec
  private def findParameterized(error: Throwable): Option[ParameterizedVerilogException] =
    if (error == null) None
    else
      error match {
        case value: ParameterizedVerilogException => Some(value)
        case _                                    => findParameterized(error.getCause)
      }

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
