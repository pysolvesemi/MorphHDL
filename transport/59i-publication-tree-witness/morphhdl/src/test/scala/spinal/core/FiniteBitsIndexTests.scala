package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

private object FiniteBitsIndexFixture {
  final class ExactSource(count: ElabInt, width: Option[ElabInt]) extends Component {
    setDefinitionName("FiniteBitsIndexExactSource")

    val source = in(
      width match {
        case Some(value) => Bits(value bits)
        case None        => Bits(count.witness bits)
      }
    ).setName("source")
    val selected = out(Vec(Bool(), count)).setName("selected")

    ElabFiniteRange.foreach(count, "finite_bits_index") { index =>
      index(selected) := index(source)
    }
  }
}

class FiniteBitsIndexTests extends AnyFunSuite {
  import FiniteBitsIndexFixture._

  test("finite Bits indexing publishes one exact correlated structural slice") {
    withTemporaryDirectory { directory =>
      val count = parameter("COUNT", default = 3, minimum = 1, maximum = 8)
      val verilog = emit(
        directory,
        "finite_bits_index.v",
        new ExactSource(count, Some(count))
      )
      val compact = verilog.replaceAll("\\s+", "")

      assert(verilog.contains("for ("), verilog)
      assert(verilog.contains("< COUNT;"), verilog)
      assert(
        compact.contains("source[finite_bits_index_index_") &&
          compact.contains("+:1]"),
        verilog
      )
      assert(!compact.contains("source[0+:1]"), verilog)
    }
  }

  test("finite Bits indexing rejects a same-witness different width function") {
    withTemporaryDirectory { directory =>
      val count = parameter("COUNT", default = 1, minimum = 1, maximum = 4)
      expectFailure(
        directory,
        "finite_bits_index_function_mismatch.v",
        new ExactSource(count, Some(count * count)),
        "SPINAL-ELAB-FINITE-INDEX-BITS-WIDTH-MISMATCH"
      )
    }
  }

  test("finite Bits indexing rejects an independently rooted width") {
    withTemporaryDirectory { directory =>
      val count = parameter("COUNT", default = 3, minimum = 1, maximum = 8)
      val width = parameter("WIDTH", default = 3, minimum = 1, maximum = 8)
      expectFailure(
        directory,
        "finite_bits_index_root_mismatch.v",
        new ExactSource(count, Some(width)),
        "SPINAL-ELAB-FINITE-INDEX-BITS-WIDTH-MISMATCH"
      )
    }
  }

  test("finite Bits indexing rejects a native same-witness width carrier") {
    withTemporaryDirectory { directory =>
      val count = parameter("COUNT", default = 3, minimum = 1, maximum = 8)
      expectFailure(
        directory,
        "finite_bits_index_width_missing.v",
        new ExactSource(count, None),
        "SPINAL-ELAB-FINITE-INDEX-BITS-SOURCE-WIDTH-MISSING"
      )
    }
  }

  private def parameter(
      name: String,
      default: Int,
      minimum: Int,
      maximum: Int
  ): ElabInt =
    HdlInt
      .param(
        name,
        default = BigInt(default),
        min = BigInt(minimum),
        max = BigInt(maximum)
      )
      .asElabInt

  private def config(directory: Path, filename: String): SpinalConfig = {
    Files.createDirectories(directory)
    val value = SpinalConfig(targetDirectory = directory.toString)
    value.netlistFileName = filename
    value
  }

  private def emit(
      directory: Path,
      filename: String,
      component: => Component
  ): String = {
    MorphVerilog(config(directory, filename))(component)
    new String(
      Files.readAllBytes(directory.resolve(filename)),
      StandardCharsets.UTF_8
    )
  }

  private def expectFailure(
      directory: Path,
      filename: String,
      component: => Component,
      code: String
  ): Unit = {
    MorphVerilog.tryGenerate(config(directory, filename))(component) match {
      case Left(failure) =>
        assert(
          failure.detail.contains(code),
          s"expected $code, received ${failure.detail}"
        )
      case Right(report) =>
        fail(s"expected $code, generation succeeded with $report")
    }
    assert(
      !Files.exists(directory.resolve(filename)),
      s"$code failure published partial RTL"
    )
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-finite-bits-index-")
    try body(directory)
    finally deleteRecursively(directory)
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      val stream = Files.walk(path)
      try {
        val paths = stream.toArray.map(_.asInstanceOf[Path])
        paths.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      } finally stream.close()
    }
  }
}
