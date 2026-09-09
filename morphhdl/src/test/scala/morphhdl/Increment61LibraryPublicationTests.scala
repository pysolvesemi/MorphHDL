package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

final class Increment61ParameterizedStreamFifo(width: HdlInt, depth: HdlInt)
    extends Component {
  setDefinitionName("Increment61ParameterizedStreamFifo")

  val io = new Bundle {
    val push = slave Stream (Bits(width bits))
    val pop = master Stream (Bits(width bits))
  }

  val fifo = StreamFifo(Bits(width bits), depth)
  fifo.io.push << io.push
  io.pop << fifo.io.pop
}

final class Increment61ParameterizedVecSigned(width: HdlInt, count: HdlInt)
    extends Component {
  setDefinitionName("Increment61ParameterizedVecSigned")

  val io = new Bundle {
    val values = in Vec (SInt(width bits), count)
    val selected = out SInt (width bits)
  }

  io.selected := io.values(0)
}

final class Increment61ParameterizedMemory(width: HdlInt, depth: HdlInt)
    extends Component {
  setDefinitionName("Increment61ParameterizedMemory")

  val io = new Bundle {
    val writeEnable = in Bool ()
    val address = in UInt (16 bits)
    val writeData = in Bits (width bits)
    val readData = out Bits (width bits)
  }

  val memory = Mem(Bits(width bits), depth)
  when(io.writeEnable) {
    memory.write(io.address, io.writeData)
  }
  io.readData := memory.readAsync(io.address)
}

class Increment61LibraryPublicationTests extends AnyFunSuite {
  private val moduleDeclaration =
    "(?m)^\\s*module\\s+([A-Za-z_][A-Za-z0-9_$]*)\\b".r

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      .replace("\r\n", "\n")
      .replace('\r', '\n')

  private def generate(name: String)(component: => Component): (Path, MorphSingleSourceVerilogReport) = {
    val directory = Files.createTempDirectory("morphhdl-increment-61-library-" + name + "-")
    val report = MorphVerilog(
      SpinalConfig(
        targetDirectory = directory.toString,
        oneFilePerComponent = true
      )
    )(component)
    assert(report.generatedSourcesPaths.nonEmpty)
    report.generatedSourcesPaths.foreach(path => assert(Files.isRegularFile(Path.of(path))))
    (directory, report)
  }

  private def assertOneDefinitionPerFile(report: MorphSingleSourceVerilogReport): Unit = {
    val definitions = report.generatedSourcesPaths.map { value =>
      val path = Path.of(value)
      val names = moduleDeclaration.findAllMatchIn(read(path)).map(_.group(1)).toVector
      assert(names.size == 1, s"${path.getFileName} contains module definitions $names")
      assert(path.getFileName.toString == names.head + ".v")
      names.head
    }
    assert(definitions.distinct.size == definitions.size)
  }

  test("parameterized StreamFifo hierarchy publishes one canonical definition per file") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 128)
    val depth = HdlInt.param("DEPTH", default = 4, min = 2, max = 64)
    val (directory, report) = generate("stream-fifo") {
      new Increment61ParameterizedStreamFifo(width, depth)
    }

    assert(report.toplevelName == "Increment61ParameterizedStreamFifo")
    assert(report.generatedSourcesPaths.size >= 2)
    assertOneDefinitionPerFile(report)
    val list = directory.resolve("Increment61ParameterizedStreamFifo.lst")
    assert(Files.isRegularFile(list))
    assert(
      Files.readAllLines(list, StandardCharsets.UTF_8).asScala.toVector ==
        report.generatedSourcesPaths.map(path => Path.of(path).getFileName.toString)
    )
    val combined = report.generatedSourcesPaths.map(path => read(Path.of(path))).mkString("\n")
    assert(combined.contains("parameter integer WIDTH = 8"))
    assert(combined.contains("parameter integer DEPTH = 4"))
  }

  test("parameterized Vec of SInt stays flattened and signed in its owning file") {
    val width = HdlInt.param("WIDTH", default = 10, min = 1, max = 64)
    val count = HdlInt.param("COUNT", default = 3, min = 1, max = 16)
    val (_, report) = generate("vec-signed") {
      new Increment61ParameterizedVecSigned(width, count)
    }

    assertOneDefinitionPerFile(report)
    assert(report.generatedSourcesPaths.size == 1)
    val verilog = read(Path.of(report.generatedSourcesPaths.head))
    assert(verilog.contains("parameter integer WIDTH = 10"))
    assert(verilog.contains("parameter integer COUNT = 3"))
    assert(verilog.contains("signed"))
    assert(verilog.contains("WIDTH"))
    assert(verilog.contains("COUNT"))
  }

  test("parameterized memory geometry remains owned by one split module") {
    val width = HdlInt.param("WIDTH", default = 9, min = 1, max = 64)
    val depth = HdlInt.param("DEPTH", default = 17, min = 2, max = 256)
    val (_, report) = generate("memory") {
      new Increment61ParameterizedMemory(width, depth)
    }

    assertOneDefinitionPerFile(report)
    assert(report.generatedSourcesPaths.size == 1)
    val verilog = read(Path.of(report.generatedSourcesPaths.head))
    assert(verilog.contains("parameter integer WIDTH = 9"))
    assert(verilog.contains("parameter integer DEPTH = 17"))
    assert(verilog.contains("[0:DEPTH-1]") || verilog.contains("[0:(DEPTH - 1)]"))
  }
}

object Increment61LibraryPublicationArtifacts {
  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 1, "expected output directory")
    val root = Path.of(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)

    def emit(name: String)(component: => Component): Unit = {
      val directory = root.resolve(name)
      Files.createDirectories(directory)
      MorphVerilog(
        SpinalConfig(
          targetDirectory = directory.toString,
          oneFilePerComponent = true
        )
      )(component)
    }

    emit("stream-fifo") {
      new Increment61ParameterizedStreamFifo(
        HdlInt.param("WIDTH", default = 8, min = 1, max = 128),
        HdlInt.param("DEPTH", default = 4, min = 2, max = 64)
      )
    }
    emit("vec-signed") {
      new Increment61ParameterizedVecSigned(
        HdlInt.param("WIDTH", default = 10, min = 1, max = 64),
        HdlInt.param("COUNT", default = 3, min = 1, max = 16)
      )
    }
    emit("memory") {
      new Increment61ParameterizedMemory(
        HdlInt.param("WIDTH", default = 9, min = 1, max = 64),
        HdlInt.param("DEPTH", default = 17, min = 2, max = 256)
      )
    }
  }
}
