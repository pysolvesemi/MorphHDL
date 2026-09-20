package morphhdl

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File, ObjectInputStream, ObjectOutputStream, ObjectStreamClass}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.Base64

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.HdlInt
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntegerParameter

class MorphSingleSourceVerilogTests extends AnyFunSuite {
  private val expectedPhaseIds = Vector(
    "PhaseCheckIoBundle",
    "PhaseCheckHierarchy",
    "PhaseInferWidth",
    "PhaseCheck_noLatchNoOverride",
    "PhaseCheck_noRegisterAsLatch",
    "PhaseCheckCombinationalLoops",
    "PhaseCheckCrossClock",
    "PhaseContext.checkGlobalData"
  )

  private val legacySerializedReportBase64 =
    if (scala.util.Properties.versionNumberString.startsWith("2.12"))
      """rO0ABXNyACdtb3JwaGhkbC5Nb3JwaFNpbmdsZVNvdXJjZVZlcmlsb2dSZXBvcnS/zQ4ThAvdXQIABEwAFWdlbmVyYXRlZFNv
        |dXJjZXNQYXRoc3QAI0xzY2FsYS9jb2xsZWN0aW9uL2ltbXV0YWJsZS9WZWN0b3I7TAAbaW5oZXJpdGVkVmFsaWRhdGlvblBo
        |YXNlSWRzcQB+AAFMAApwYXJhbWV0ZXJzcQB+AAFMAAx0b3BsZXZlbE5hbWV0ABJMamF2YS9sYW5nL1N0cmluZzt4cHNyACFz
        |Y2FsYS5jb2xsZWN0aW9uLmltbXV0YWJsZS5WZWN0b3Lte0zvWO5+QQIAC0kABWRlcHRoWgAFZGlydHlJAAhlbmRJbmRleEkA
        |BWZvY3VzSQAKc3RhcnRJbmRleFsACGRpc3BsYXkwdAATW0xqYXZhL2xhbmcvT2JqZWN0O1sACGRpc3BsYXkxcQB+AAVbAAhk
        |aXNwbGF5MnEAfgAFWwAIZGlzcGxheTNxAH4ABVsACGRpc3BsYXk0cQB+AAVbAAhkaXNwbGF5NXEAfgAFeHAAAAABAAAAAAEA
        |AAAAAAAAAHVyABNbTGphdmEubGFuZy5PYmplY3Q7kM5YnxBzKWwCAAB4cAAAACB0AAhsZWdhY3kudnBwcHBwcHBwcHBwcHBw
        |cHBwcHBwcHBwcHBwcHBwcHBwcHBwcHNxAH4ABAAAAAEAAAAAAQAAAAAAAAAAdXEAfgAHAAAAIHQAC1BoYXNlTGVnYWN5cHBw
        |cHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc3EAfgAEAAAAAQAAAAABAAAAAAAAAAB1cQB+AAcAAAAgc3IAIm1v
        |cnBoaGRsLnBhcmFtcnRsLkludGVnZXJQYXJhbWV0ZXIzuiFx3rkIrwIAA0wAC2NvbnN0cmFpbnRzcQB+AAFMAAdkZWZhdWx0
        |dAATTHNjYWxhL21hdGgvQmlnSW50O0wABG5hbWVxAH4AAnhwc3EAfgAEAAAAAQAAAAACAAAAAAAAAAB1cQB+AAcAAAAgc3IA
        |LG1vcnBoaGRsLnBhcmFtcnRsLkludENvbnN0cmFpbnQkTWluSW5jbHVzaXZl91B6GAyA764CAAFMAAV2YWx1ZXEAfgAQeHBz
        |cgARc2NhbGEubWF0aC5CaWdJbnSbSQEOc9kahgIAAUwACmJpZ0ludGVnZXJ0ABZMamF2YS9tYXRoL0JpZ0ludGVnZXI7eHIA
        |FnNjYWxhLm1hdGguU2NhbGFOdW1iZXISZddcfi6AgQIAAHhyABBqYXZhLmxhbmcuTnVtYmVyhqyVHQuU4IsCAAB4cHNyABRq
        |YXZhLm1hdGguQmlnSW50ZWdlcoz8nx+pO/sdAwAGSQAIYml0Q291bnRJAAliaXRMZW5ndGhJABNmaXJzdE5vbnplcm9CeXRl
        |TnVtSQAMbG93ZXN0U2V0Qml0SQAGc2lnbnVtWwAJbWFnbml0dWRldAACW0J4cQB+ABn///////////////7////+AAAAAXVy
        |AAJbQqzzF/gGCFTgAgAAeHAAAAABAXhzcgAsbW9ycGhoZGwucGFyYW1ydGwuSW50Q29uc3RyYWludCRNYXhJbmNsdXNpdmXq
        |NZxdEnVhzgIAAUwABXZhbHVlcQB+ABB4cHNxAH4AFnNxAH4AG////////////////v////4AAAABdXEAfgAeAAAAAUB4cHBw
        |cHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBzcQB+ABZzcQB+ABv///////////////7////+AAAAAXVxAH4AHgAA
        |AAEIeHQABVdJRFRIcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwdAAJTGVnYWN5VG9w""".stripMargin
    else
      """rO0ABXNyACdtb3JwaGhkbC5Nb3JwaFNpbmdsZVNvdXJjZVZlcmlsb2dSZXBvcnR5aHG5IMpChgIABEwAFWdlbmVyYXRlZFNv
        |dXJjZXNQYXRoc3QAI0xzY2FsYS9jb2xsZWN0aW9uL2ltbXV0YWJsZS9WZWN0b3I7TAAbaW5oZXJpdGVkVmFsaWRhdGlvblBo
        |YXNlSWRzcQB+AAFMAApwYXJhbWV0ZXJzcQB+AAFMAAx0b3BsZXZlbE5hbWV0ABJMamF2YS9sYW5nL1N0cmluZzt4cHNyADJz
        |Y2FsYS5jb2xsZWN0aW9uLmdlbmVyaWMuRGVmYXVsdFNlcmlhbGl6YXRpb25Qcm94eQAAAAAAAAADAwABTAAHZmFjdG9yeXQA
        |GkxzY2FsYS9jb2xsZWN0aW9uL0ZhY3Rvcnk7eHBzcgAqc2NhbGEuY29sbGVjdGlvbi5JdGVyYWJsZUZhY3RvcnkkVG9GYWN0
        |b3J5AAAAAAAAAAMCAAFMAAdmYWN0b3J5dAAiTHNjYWxhL2NvbGxlY3Rpb24vSXRlcmFibGVGYWN0b3J5O3hwc3IAJnNjYWxh
        |LnJ1bnRpbWUuTW9kdWxlU2VyaWFsaXphdGlvblByb3h5AAAAAAAAAAECAAFMAAttb2R1bGVDbGFzc3QAEUxqYXZhL2xhbmcv
        |Q2xhc3M7eHB2cgAic2NhbGEuY29sbGVjdGlvbi5pbW11dGFibGUuVmVjdG9yJAAAAAAAAAADAgAAeHB3BAAAAAF0AAhsZWdh
        |Y3kudnhzcQB+AARzcQB+AAdxAH4ADHcEAAAAAXQAC1BoYXNlTGVnYWN5eHNxAH4ABHNxAH4AB3EAfgAMdwQAAAABc3IAIm1v
        |cnBoaGRsLnBhcmFtcnRsLkludGVnZXJQYXJhbWV0ZXK1dbjOMIuKHAIAA0wAC2NvbnN0cmFpbnRzcQB+AAFMAAdkZWZhdWx0
        |dAATTHNjYWxhL21hdGgvQmlnSW50O0wABG5hbWVxAH4AAnhwc3EAfgAEc3EAfgAHcQB+AAx3BAAAAAJzcgAsbW9ycGhoZGwu
        |cGFyYW1ydGwuSW50Q29uc3RyYWludCRNaW5JbmNsdXNpdmWVDugG2R1gigIAAUwABXZhbHVlcQB+ABZ4cHNyABFzY2FsYS5t
        |YXRoLkJpZ0ludKb/Tg5sShJbAgACSgAFX2xvbmdMAAtfYmlnSW50ZWdlcnQAFkxqYXZhL21hdGgvQmlnSW50ZWdlcjt4cgAW
        |c2NhbGEubWF0aC5TY2FsYU51bWJlchJl11x+LoCBAgAAeHIAEGphdmEubGFuZy5OdW1iZXKGrJUdC5TgiwIAAHhwAAAAAAAA
        |AAFwc3IALG1vcnBoaGRsLnBhcmFtcnRsLkludENvbnN0cmFpbnQkTWF4SW5jbHVzaXZlhIsgiDQrztgCAAFMAAV2YWx1ZXEA
        |fgAWeHBzcQB+ABwAAAAAAAAAQHB4c3EAfgAcAAAAAAAAAAhwdAAFV0lEVEh4dAAJTGVnYWN5VG9w""".stripMargin

  private def serializeReport(report: MorphSingleSourceVerilogReport): Array[Byte] = {
    val bytes = new ByteArrayOutputStream
    val stream = new ObjectOutputStream(bytes)
    try stream.writeObject(report)
    finally stream.close()
    bytes.toByteArray
  }

  private def deserializeReport(bytes: Array[Byte]): MorphSingleSourceVerilogReport = {
    val stream = new ObjectInputStream(new ByteArrayInputStream(bytes))
    try stream.readObject().asInstanceOf[MorphSingleSourceVerilogReport]
    finally stream.close()
  }

  test("deprecated ParamRTL report construction retains the reviewed legacy Product surface") {
    val legacyParameters = Vector(
      IntegerParameter(
        "WIDTH",
        8,
        Vector(MinInclusive(1), MaxInclusive(64))
      )
    )
    val constructed = new MorphSingleSourceVerilogReport(
      "LegacyTop",
      Vector("legacy.v"),
      legacyParameters,
      expectedPhaseIds
    )
    val applied = MorphSingleSourceVerilogReport(
      "LegacyTop",
      Vector("legacy.v"),
      legacyParameters,
      expectedPhaseIds
    )

    assert(constructed == applied)
    assert(applied.productArity == 4)
    assert(applied.productElement(2) == legacyParameters)
    assert(applied.productIterator.toVector == Vector(
      "LegacyTop",
      Vector("legacy.v"),
      legacyParameters,
      expectedPhaseIds
    ))
    val elementName = applied.getClass.getMethods.find { method =>
      method.getName == "productElementName" && method.getParameterTypes.toVector == Vector(
        java.lang.Integer.TYPE
      )
    }
    if (scala.util.Properties.versionNumberString.startsWith("2.13"))
      assert(elementName.nonEmpty)
    elementName.foreach { method =>
      assert((0 until 4).map(index => method.invoke(applied, Int.box(index))).toVector == Vector(
        "toplevelName",
        "generatedSourcesPaths",
        "parameters",
        "inheritedValidationPhaseIds"
      ))
    }
    assert(applied.copy(toplevelName = "CopiedTop").toplevelName == "CopiedTop")
    assert(
      MorphSingleSourceVerilogReport.tupled((
        "LegacyTop",
        Vector("legacy.v"),
        legacyParameters,
        expectedPhaseIds
      )) == applied
    )
    assert(applied.elaborationParameters.isEmpty)
    val MorphSingleSourceVerilogReport(top, paths, parameters, phases) = applied
    assert((top, paths, parameters, phases) == (
      "LegacyTop",
      Vector("legacy.v"),
      legacyParameters,
      expectedPhaseIds
    ))
  }

  test("historical serialized reports remain readable and typed reports serialize through legacy fields") {
    val expectedUid =
      if (scala.util.Properties.versionNumberString.startsWith("2.12"))
        -4626025765257093795L
      else 8748367316100203142L
    val serialForm = ObjectStreamClass.lookup(classOf[MorphSingleSourceVerilogReport])
    assert(serialForm.getSerialVersionUID == expectedUid)
    val expectedCompanionUid =
      if (scala.util.Properties.versionNumberString.startsWith("2.12"))
        -6620878606554409185L
      else -2414537719037776747L
    assert(
      ObjectStreamClass
        .lookup(MorphSingleSourceVerilogReport.getClass)
        .getSerialVersionUID == expectedCompanionUid
    )
    assert(serialForm.getFields.map(_.getName).toVector == Vector(
      "generatedSourcesPaths",
      "inheritedValidationPhaseIds",
      "parameters",
      "toplevelName"
    ))
    val directInterfaces = classOf[MorphSingleSourceVerilogReport].getInterfaces.map(_.getName).toVector
    assert(directInterfaces.contains("scala.Product"))
    assert(
      directInterfaces.contains(
        if (scala.util.Properties.versionNumberString.startsWith("2.12"))
          "scala.Serializable"
        else "java.io.Serializable"
      )
    )

    val legacyParameters = Vector(
      IntegerParameter(
        "WIDTH",
        8,
        Vector(MinInclusive(1), MaxInclusive(64))
      )
    )
    val historical = deserializeReport(
      Base64.getMimeDecoder.decode(legacySerializedReportBase64)
    )
    assert(historical.toplevelName == "LegacyTop")
    assert(historical.generatedSourcesPaths == Vector("legacy.v"))
    assert(historical.parameters == legacyParameters)
    assert(historical.inheritedValidationPhaseIds == Vector("PhaseLegacy"))
    assert(historical.elaborationParameters.isEmpty)
    assert(historical.productElement(2) == legacyParameters)

    val schema = ElaborationIntegerParameter("WIDTH", 8, 1, 64)
    val typed = MorphSingleSourceVerilogReport.fromTyped(
      "TypedTop",
      Vector("typed.v"),
      Vector(schema),
      Vector("PhaseTyped")
    )
    assert(typed.elaborationParameters.head eq schema)
    assert(typed.copy(toplevelName = "TypedCopy").elaborationParameters.head eq schema)
    val reconstructedLegacyParameters = Vector(
      IntegerParameter(
        "WIDTH",
        8,
        Vector(MinInclusive(1), MaxInclusive(64))
      )
    )
    assert(reconstructedLegacyParameters == typed.parameters)
    assert(!(reconstructedLegacyParameters.asInstanceOf[AnyRef] eq
      typed.parameters.asInstanceOf[AnyRef]))
    assert(
      typed.copy(parameters = reconstructedLegacyParameters).elaborationParameters.isEmpty
    )
    val roundTripped = deserializeReport(serializeReport(typed))
    assert(roundTripped.toplevelName == "TypedTop")
    assert(roundTripped.generatedSourcesPaths == Vector("typed.v"))
    assert(roundTripped.parameters == legacyParameters)
    assert(roundTripped.inheritedValidationPhaseIds == Vector("PhaseTyped"))
    assert(roundTripped.elaborationParameters.isEmpty)
  }

  test("one ordinary component factory emits the parameterized wire contract") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "parameterized_wire.v"

      val report = MorphVerilog(config) {
        ParameterizedWireContractFixture.component(reverseConstructionOrder = false)
      }

      val output = directory.resolve("parameterized_wire.v")
      assert(report.toplevelName == "ParameterizedWire")
      assert(report.generatedSourcesPaths == Vector(output.toString))
      assert(
        report.parameters == Vector(
          IntegerParameter(
            "WIDTH",
            8,
            Vector(MinInclusive(1), MaxInclusive(64))
          )
        )
      )
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(read(output) == expectedParameterizedWire)
      val listing = Files.list(directory)
      try {
        assert(listing.iterator().asScala.map(_.getFileName.toString).toVector == Vector("parameterized_wire.v"))
      } finally listing.close()
    }
  }

  test("one ordinary component retains symbolic native and aggregate data shapes") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "symbolic_data_shapes.v"

      val report = MorphVerilog(config) {
        SymbolicDataShapesContractFixture.component(reverseConstructionOrder = false)
      }

      val output = directory.resolve("symbolic_data_shapes.v")
      assert(report.toplevelName == "SymbolicDataShapes")
      assert(report.generatedSourcesPaths == Vector(output.toString))
      assert(
        report.parameters == Vector(
          IntegerParameter(
            "WIDTH",
            8,
            Vector(MinInclusive(1), MaxInclusive(64))
          )
        )
      )
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(read(output) == read(contractGolden("symbolic_data_shapes.v")))

      val verilog = read(output)
      val normalizedVerilog = verilog.replaceAll("\\s+", " ")
      assert(normalizedVerilog.contains("module SymbolicDataShapes #("))
      assert(normalizedVerilog.contains("parameter integer WIDTH = 8"))
      assert(normalizedVerilog.contains("[WIDTH-1:0] bits_in"))
      assert(normalizedVerilog.contains("[WIDTH-1:0] uint_in"))
      assert(normalizedVerilog.contains("[WIDTH-1:0] sint_in"))
      val packedVecPort =
        """\b(input|output)\s+wire\s+\[([^\]]+)\]\s+(vec_in|vec_out)\b""".r
          .findAllMatchIn(normalizedVerilog)
          .map(value => value.group(1) -> (value.group(2), value.group(3)))
          .toVector
      assert(packedVecPort.size == 2)
      assert(packedVecPort.map(_._1).toSet == Set("input", "output"))
      assert(packedVecPort.map(_._2._2).toSet == Set("vec_in", "vec_out"))
      packedVecPort.foreach { case (_, (range, _)) =>
        assert(range.contains("WIDTH"))
        assert(range.contains("*"))
      }
      assert(!normalizedVerilog.matches(".*\\bvec_(in|out)_[0-9]+.*"))
      assert(normalizedVerilog.contains("[WIDTH-1:0] stream_in_payload_sint"))
      assert(normalizedVerilog.contains("[WIDTH-1:0] flow_out_payload_uint"))
      assert(normalizedVerilog.contains("[WIDTH-1:0] internal_payload_bits"))
      assert(normalizedVerilog.contains("[WIDTH-1:0] payload_register_sint"))
      assert(normalizedVerilog.contains("always @(posedge clk)"))
      assert(!normalizedVerilog.contains("parameterizedDesign"))
    }
  }

  test("ordinary SpinalVerilog is byte-identical for symbolic-default and literal data shapes") {
    withTemporaryDirectory { directory =>
      val symbolicDirectory = directory.resolve("symbolic")
      val literalDirectory = directory.resolve("literal")
      Files.createDirectories(symbolicDirectory)
      Files.createDirectories(literalDirectory)

      val symbolicConfig = SpinalConfig(targetDirectory = symbolicDirectory.toString)
      symbolicConfig.netlistFileName = "SymbolicDataShapes.v"
      val symbolicReport = SpinalVerilog(symbolicConfig) {
        SymbolicDataShapesContractFixture.component(reverseConstructionOrder = false)
      }
      val literalConfig = SpinalConfig(targetDirectory = literalDirectory.toString)
      literalConfig.netlistFileName = "SymbolicDataShapes.v"
      val literalReport = SpinalVerilog(literalConfig) {
        SymbolicDataShapesContractFixture.componentWithWidth(
          HdlInt.literal(8),
          reverseConstructionOrder = false
        )
      }

      val verilog = read(Paths.get(symbolicReport.generatedSourcesPaths.head))
      assert(verilog == read(Paths.get(literalReport.generatedSourcesPaths.head)))
      assert(verilog.contains("module SymbolicDataShapes ("))
      assert(verilog.contains("[7:0]"))
      assert(!verilog.contains("parameter integer WIDTH"))
      assert(!verilog.contains("[WIDTH-1:0]"))
    }
  }

  test("single-source emission is independent of component and parameter names") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "renamed_payload_bridge.v"

      val report = MorphVerilog(config) {
        genericDirectWire("RenamedPayloadBridge", "PAYLOAD_BITS", defaultWidth = 13)
      }

      val verilog = read(directory.resolve("renamed_payload_bridge.v"))
      assert(report.toplevelName == "RenamedPayloadBridge")
      assert(verilog.contains("module RenamedPayloadBridge #("))
      assert(verilog.contains("parameter integer PAYLOAD_BITS = 13"))
      assert(verilog.contains("input  wire [PAYLOAD_BITS-1:0] payload"))
      assert(verilog.contains("output wire [PAYLOAD_BITS-1:0] result"))
      assert(verilog.contains("assign result = payload;"))
      assert(!verilog.contains("ParameterizedWire"))
    }
  }

  test("parameterized native emission canonicalizes reversed port construction") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "parameterized_wire.v"

      MorphVerilog(config) {
        ParameterizedWireContractFixture.component(reverseConstructionOrder = true)
      }

      assert(read(directory.resolve("parameterized_wire.v")) == expectedParameterizedWire)
    }
  }

  test("ordinary SpinalVerilog ignores symbolic metadata when parameterized mode is disabled") {
    withTemporaryDirectory { directory =>
      val report = SpinalVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        ParameterizedWireContractFixture.component(reverseConstructionOrder = false)
      }

      val verilog = read(Paths.get(report.generatedSourcesPaths.head))
      assert(verilog.contains("module ParameterizedWire ("))
      assert(verilog.contains("input  wire [7:0]    din"))
      assert(verilog.contains("output wire [7:0]    dout"))
      assert(!verilog.contains("parameter integer WIDTH"))
      assert(!verilog.contains("[WIDTH-1:0]"))
    }
  }

  test("a bounded derived symbolic width atomically replaces the previous public output") {
    withTemporaryDirectory { directory =>
      val output = directory.resolve("preserved.v")
      Files.write(output, "previous-good-output".getBytes(StandardCharsets.UTF_8))
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "preserved.v"

      val result = MorphVerilog.tryGenerate(config) {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
        val derivedWidth = width + HdlInt.literal(1)
        new Component {
          setDefinitionName("BoundedDerivedWidth")
          val payload = in(morphhdl.frontend.UInt(derivedWidth bits))
          val result = out(morphhdl.frontend.UInt(derivedWidth bits))
          result := payload
        }
      }

      result match {
        case Left(failure) =>
          fail(s"Expected derived symbolic-width success, received $failure")
        case Right(report) =>
          assert(report.toplevelName == "BoundedDerivedWidth")
          assert(report.generatedSourcesPaths == Vector(output.toString))
          assert(
            report.parameters == Vector(
              IntegerParameter(
                "WIDTH",
                8,
                Vector(MinInclusive(1), MaxInclusive(64))
              )
            )
          )
          assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      }

      val verilog = read(output)
      assert(verilog != "previous-good-output")
      assert(verilog.contains("module BoundedDerivedWidth #("))
      assert(verilog.contains("parameter integer WIDTH = 8"))
      assert(verilog.contains("(WIDTH + 1)"))
      assert(verilog.contains("assign result = payload;"))
      val listing = Files.list(directory)
      try {
        assert(listing.iterator().asScala.map(_.getFileName.toString).toVector == Vector("preserved.v"))
      } finally listing.close()
    }
  }

  test("a native bridge failure preserves its stable code and publishes nothing") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "untagged.v"

      val result = MorphVerilog.tryGenerate(config) {
        new Component {
          setDefinitionName("UntaggedDirectWire")
          val payload = in(morphhdl.frontend.UInt(8 bits))
          val result = out(morphhdl.frontend.UInt(8 bits))
          result := payload
        }
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.SingleSourceGeneration)
          assert(failure.detail.contains("SPINAL-PARAMETERIZED-VERILOG-UNTAGGED-PORT"))
        case Right(report) => fail(s"Expected untagged-port failure, received $report")
      }
      assert(!Files.exists(directory.resolve("untagged.v")))
    }
  }

  test("native bridge diagnostics retain the symbolic width source location") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "colliding_parameter.v"

      val result = MorphVerilog.tryGenerate(config) {
        val width = HdlInt.param("payload", default = 8, min = 1, max = 64)
        new Component {
          setDefinitionName("CollidingParameterWire")
          val payload = in(morphhdl.frontend.UInt(width bits))
          val result = out(morphhdl.frontend.UInt(width bits))
          result := payload
        }
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.SingleSourceGeneration)
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-PORT-NAME-COLLISION"
            )
          )
          assert(failure.detail.contains("MorphSingleSourceVerilogTests.scala:"))
        case Right(report) => fail(s"Expected parameter/port collision, received $report")
      }
      assert(!Files.exists(directory.resolve("colliding_parameter.v")))
    }
  }

  test("configuration failure occurs before the single component factory runs") {
    withTemporaryDirectory { directory =>
      var factoryRuns = 0
      val result = MorphVerilog.tryGenerate(
        SpinalConfig(mode = SystemVerilog, targetDirectory = directory.toString)
      ) {
        factoryRuns += 1
        genericDirectWire("NeverElaborated", "WIDTH", defaultWidth = 8)
      }

      result match {
        case Left(failure) => assert(failure.stage == MorphVerilogStage.Configuration)
        case Right(report) => fail(s"Expected configuration failure, received $report")
      }
      assert(factoryRuns == 0)
      val listing = Files.list(directory)
      try assert(listing.iterator().asScala.isEmpty)
      finally listing.close()
    }
  }

  test("single-source includeFormal retains native COVER publication") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.includeFormal
      config.netlistFileName = "formal_parameterized_wire.v"

      val report = MorphVerilog(config) {
        formalDirectWire("FormalParameterizedWire", "WIDTH", defaultWidth = 8)
      }
      val verilog = read(directory.resolve(config.netlistFileName))

      assert(report.parameters.map(_.name) == Vector("WIDTH"))
      assert("(?m)^\\s*cover\\s*\\(".r.findFirstIn(verilog).nonEmpty, verilog)
    }
  }

  test("single-source formal configuration rejects unpaired and other flags") {
    withTemporaryDirectory { directory =>
      val formalFlagOnly = SpinalConfig(
        targetDirectory = directory.resolve("formal_flag_only").toString
      )
      formalFlagOnly.flags += GenerationFlags.formal

      val formalAssertsOnly = SpinalConfig(
        targetDirectory = directory.resolve("formal_asserts_only").toString,
        formalAsserts = true
      )

      val simulation = SpinalConfig(
        targetDirectory = directory.resolve("simulation").toString
      )
      simulation.includeSimulation

      val multiple = SpinalConfig(
        targetDirectory = directory.resolve("multiple").toString
      )
      multiple.includeFormal
      multiple.includeSimulation

      Vector(
        formalFlagOnly,
        formalAssertsOnly,
        simulation,
        multiple
      ).foreach { config =>
        var factoryRuns = 0
        val result = MorphVerilog.tryGenerate(config) {
          factoryRuns += 1
          genericDirectWire("RejectedFormalConfig", "WIDTH", defaultWidth = 8)
        }
        result match {
          case Left(failure) =>
            assert(failure.stage == MorphVerilogStage.Configuration)
          case Right(report) =>
            fail(s"Expected formal configuration failure, received $report")
        }
        assert(factoryRuns == 0)
      }
    }
  }

  private def genericDirectWire(
      componentName: String,
      parameterName: String,
      defaultWidth: Int
  ): Component = {
    val width = HdlInt.param(parameterName, default = defaultWidth, min = 1, max = 64)
    new Component {
      setDefinitionName(componentName)
      val payload = in(morphhdl.frontend.UInt(width bits))
      val result = out(morphhdl.frontend.UInt(width bits))
      result := payload
    }
  }

  private def formalDirectWire(
      componentName: String,
      parameterName: String,
      defaultWidth: Int
  ): Component = {
    val width =
      HdlInt.param(parameterName, default = defaultWidth, min = 1, max = 64)
    new Component {
      setDefinitionName(componentName)
      val payload = in(morphhdl.frontend.UInt(width bits))
      val result = out(morphhdl.frontend.UInt(width bits))
      val seen = RegInit(False) setWhen payload.orR
      cover(seen)
      result := payload
    }
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def contractGolden(fileName: String): Path = {
    val relativePath = Paths.get("morphhdl", "examples", "contracts", fileName)
    val codeSource =
      Option(getClass.getProtectionDomain)
        .flatMap(domain => Option(domain.getCodeSource))
        .flatMap(source => Option(source.getLocation))
        .filter(_.getProtocol == "file")
        .map(location => Paths.get(location.toURI))
        .toVector
    val classPath =
      sys.props
        .get("java.class.path")
        .toVector
        .flatMap(_.split(File.pathSeparator).toVector)
        .filter(_.nonEmpty)
        .map(Paths.get(_))
    val searchRoots = codeSource ++ Vector(Paths.get("")) ++ classPath
    searchRoots.iterator
      .flatMap(pathAndAncestors)
      .map(_.toAbsolutePath.normalize.resolve(relativePath))
      .find(path => Files.isRegularFile(path))
      .getOrElse(throw new java.nio.file.NoSuchFileException(relativePath.toString))
  }

  private def pathAndAncestors(path: Path): Iterator[Path] =
    Iterator
      .iterate(Option(path.toAbsolutePath.normalize))(
        _.flatMap(current => Option(current.getParent))
      )
      .takeWhile(_.nonEmpty)
      .map(_.get)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-single-source-test-")
    try body(directory)
    finally deleteTree(directory)
  }

  private def deleteTree(root: Path): Unit =
    if (Files.exists(root)) {
      val stream = Files.walk(root)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }

  private val expectedParameterizedWire =
    """module ParameterizedWire #(
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
}
