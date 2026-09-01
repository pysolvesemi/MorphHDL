package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import morphhdl.frontend._
import spinal.core.internals.{DataAssignmentStatement, Operator}

private object ProceduralIdentityAdversarialFixture {
  final class PositiveProceduralSlice(lanes: HdlInt) extends Component {
    setDefinitionName("PositiveProceduralSlice")
    val din = in(morphhdl.frontend.Bits(32 bits)).setName("din")
    val dout = out(morphhdl.frontend.Bits(32 bits)).setName("dout")
    dout := 0

    (0 until lanes).named("p_positive_lane", "positive_lane").foreach { lane =>
      val laneWidth = HdlInt.literal(BigInt(8))
      dout(lane * laneWidth, laneWidth) :=
        din(lane * laneWidth, laneWidth)
    }
  }

  final class RemovedProceduralAssignment(lanes: HdlInt) extends Component {
    setDefinitionName("RemovedProceduralAssignmentMustFailClosed")
    val din = in(morphhdl.frontend.Bits(32 bits)).setName("din")
    val dout = out(morphhdl.frontend.Bits(32 bits)).setName("dout")
    dout := 0

    (0 until lanes).named("p_stale_lane", "stale_lane").foreach { lane =>
      val laneWidth = HdlInt.literal(BigInt(8))
      dout(lane * laneWidth, laneWidth) :=
        din(lane * laneWidth, laneWidth)
    }

    val retained = ParameterizedProcess.loopsOf(this).lastOption.getOrElse {
      throw new IllegalStateException("stale procedural fixture retained no loop")
    }
    val copiedMarker = retained.assignment.locationString
    retained.assignment.removeStatement()

    val before = assignmentsOf(dout)
    dout(7 downto 0) := din(7 downto 0)
    val replacement = assignmentsOf(dout)
      .filterNot(candidate => before.exists(_ eq candidate)) match {
      case Vector(value) => value
      case values =>
        throw new IllegalStateException(
          s"stale procedural fixture created ${values.size} replacement assignments"
        )
    }
    replacement.locationString = copiedMarker
  }

  final class CoincidentProceduralSlice(lanes: HdlInt) extends Component {
    setDefinitionName("CoincidentProceduralSliceMustFailClosed")
    val din = in(morphhdl.frontend.Bits(32 bits)).setName("din")
    val dout = out(morphhdl.frontend.Bits(32 bits)).setName("dout")
    dout := 0
    val coincident = din(7 downto 0)

    (0 until lanes).named("p_coincident_lane", "coincident_lane").foreach { lane =>
      val laneWidth = HdlInt.literal(BigInt(8))
      val selected = din(lane * laneWidth, laneWidth)
      val target = dout(lane * laneWidth, laneWidth)
      val xor = new Operator.Bits.Xor
      xor.left = selected
      xor.right = coincident
      target.assignFrom(xor)
    }
  }

  private def assignmentsOf(value: BaseType): Vector[DataAssignmentStatement] = {
    val assignments = Vector.newBuilder[DataAssignmentStatement]
    value.foreachStatements {
      case assignment: DataAssignmentStatement => assignments += assignment
      case _                                   =>
    }
    assignments.result()
  }
}

class ProceduralIdentityAdversarialTests extends AnyFunSuite {
  import ProceduralIdentityAdversarialFixture._

  test("a live retained procedural assignment rewrites only its exact slices") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "positive_procedural_identity.v")
      MorphVerilog(config) {
        new PositiveProceduralSlice(lanes())
      }
      val verilog = readVerilog(directory, config)
      assert(
        verilog.contains(
          "for (positive_lane = 0; positive_lane < LANES; positive_lane = positive_lane + 1) begin : p_positive_lane"
        ),
        verilog
      )
      assert(
        verilog.contains(
          "dout[(positive_lane * 8) +: 8] = din[(positive_lane * 8) +: 8];"
        ),
        verilog
      )
    }
  }

  test("a removed procedural assignment cannot be replaced by copied marker text") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("removed_procedural_assignment.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new RemovedProceduralAssignment(lanes())
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected stale procedural-assignment rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-ASSIGNMENT-EVIDENCE-STALE"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "stale procedural assignment published partial RTL")
    }
  }

  test("coincident fixed and retained procedural slices remain distinct or fail closed") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("coincident_procedural_slice.v")
      MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new CoincidentProceduralSlice(lanes())
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-EMITTED-CARDINALITY-MISMATCH"
            ),
            failure.detail
          )
          assert(!Files.exists(rtl), "coincident procedural slice published partial RTL")
        case Right(_) =>
          val verilog = new String(Files.readAllBytes(rtl), StandardCharsets.UTF_8)
          val fixed = "din\\s*\\[\\s*7\\s*:\\s*0\\s*\\]".r
            .findAllMatchIn(verilog)
            .size
          val retained =
            "din\\s*\\[[^\\]]*coincident_lane[^\\]]*\\+:\\s*8\\s*\\]".r
              .findAllMatchIn(verilog)
              .size
          assert(fixed == 1, verilog)
          assert(retained == 1, verilog)
      }
    }
  }

  private def lanes(): HdlInt =
    HdlInt.param("LANES", default = 4, min = 1, max = 4)

  private def readVerilog(directory: Path, config: SpinalConfig): String =
    new String(
      Files.readAllBytes(directory.resolve(config.netlistFileName)),
      StandardCharsets.UTF_8
    )

  private def morphConfig(directory: Path, filename: String): SpinalConfig = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    config
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-procedural-identity-")
    try body(directory)
    finally {
      if (Files.exists(directory)) {
        val paths = Files.walk(directory)
        try
          paths
            .iterator()
            .asScala
            .toVector
            .sortBy(_.getNameCount)
            .reverse
            .foreach(Files.deleteIfExists)
        finally paths.close()
      }
    }
  }
}
