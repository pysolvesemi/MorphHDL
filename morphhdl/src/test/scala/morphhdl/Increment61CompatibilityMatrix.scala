package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

import nativeapplication.BoundedRecursivePowerFixture
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.{ParameterizedVerilogException, SpinalConfig}

object Increment61CompatibilityMatrix {
  import Increment61CompatibilityCatalog.CompatibilityCase

  def publish(
      root: Path,
      entry: CompatibilityCase
  ): MorphSingleSourceVerilogReport = {
    val directory = root.resolve(entry.id)
    Files.createDirectories(directory)
    val report = MorphVerilog(
      SpinalConfig(
        targetDirectory = directory.toString,
        oneFilePerComponent = true
      )
    )(entry.build())
    require(
      report.toplevelName == entry.generatedTop,
      s"${entry.id}: unexpected generated top ${report.toplevelName}"
    )

    val sourcePaths = report.generatedSourcesPaths.map(Paths.get(_))
    val sourceNames = sourcePaths.map(_.getFileName.toString)
    require(
      sourcePaths.nonEmpty && sourceNames.distinct.size == sourceNames.size,
      s"${entry.id}: duplicate or empty generated source list"
    )
    val definitions = sourcePaths.flatMap { path =>
      require(
        path.normalize().getParent == directory.normalize(),
        s"${entry.id}: generated source escaped its case directory: $path"
      )
      val modules = moduleDefinitions(read(path))
      require(
        modules.size == 1,
        s"${entry.id}: ${path.getFileName} owns ${modules.size} module definitions"
      )
      require(
        path.getFileName.toString == modules.head + ".v",
        s"${entry.id}: ${path.getFileName} does not own module ${modules.head}"
      )
      modules
    }
    require(
      definitions.distinct.size == definitions.size,
      s"${entry.id}: duplicate generated module definition"
    )
    require(
      entry.requiredGeneratedModules.subsetOf(definitions.toSet),
      s"${entry.id}: missing generated module(s) ${entry.requiredGeneratedModules.diff(definitions.toSet)}"
    )
    require(
      entry.forbiddenGeneratedModules.intersect(definitions.toSet).isEmpty,
      s"${entry.id}: external definition was regenerated"
    )
    require(
      definitions.last == entry.generatedTop,
      s"${entry.id}: generated top is not the final dependency-ordered source"
    )

    val list = read(directory.resolve(entry.generatedTop + ".lst"))
      .split("\n", -1)
      .toVector
      .filter(_.nonEmpty)
    require(list == sourceNames, s"${entry.id}: source list differs from report order")
    entry.supportFile.foreach { case (name, text) =>
      val support = directory.resolve(name)
      Files.write(support, text.getBytes(StandardCharsets.UTF_8))
      require(
        !sourceNames.contains(name),
        s"${entry.id}: external/tool support file leaked into generated source ownership"
      )
    }
    report
  }

  private def moduleDefinitions(verilog: String): Vector[String] =
    "(?m)^\\s*module\\s+([A-Za-z_][A-Za-z0-9_$]*)\\b".r
      .findAllMatchIn(verilog)
      .map(_.group(1))
      .toVector

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
}

object Increment61CompatibilityMatrixArtifacts {
  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      throw new IllegalArgumentException(
        "Usage: Increment61CompatibilityMatrixArtifacts <output-directory>"
      )
    }
    val root = Paths.get(args(0))
    Files.createDirectories(root)
    val rows = Increment61CompatibilityCatalog.cases.map { entry =>
      Increment61CompatibilityMatrix.publish(root, entry)
      val support = entry.supportFile.map(_._1).getOrElse("-")
      Vector(entry.id, entry.generatedTop, entry.toolTop, support).mkString("\t")
    }
    Files.write(
      root.resolve("cases.tsv"),
      (rows.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)
    )
  }
}

class Increment61CompatibilityMatrixTests extends AnyFunSuite {
  test("roadmap compatibility matrix preserves exact component ownership") {
    withTemporaryDirectory { directory =>
      Increment61CompatibilityCatalog.cases.foreach { entry =>
        val report = Increment61CompatibilityMatrix.publish(directory, entry)
        assert(report.generatedSourcesPaths.nonEmpty, entry.id)
      }
    }
  }

  test("recursive split publication still rejects a non-decreasing metric") {
    withTemporaryDirectory { directory =>
      val error = intercept[MorphVerilogException] {
        MorphVerilog(
          SpinalConfig(
            targetDirectory = directory.toString,
            oneFilePerComponent = true
          )
        )(BoundedRecursivePowerFixture.nonDecreasing())
      }
      assert(
        parameterizedDiagnostic(error).code ==
          "SPINAL-PARAMETERIZED-VERILOG-RECURSION-METRIC-NONDECREASING"
      )
    }
  }

  test("recursive split publication still rejects an unproven negative-domain metric") {
    withTemporaryDirectory { directory =>
      val error = intercept[MorphVerilogException] {
        MorphVerilog(
          SpinalConfig(
            targetDirectory = directory.toString,
            oneFilePerComponent = true
          )
        )(BoundedRecursivePowerFixture.negativeDomain())
      }
      assert(
        parameterizedDiagnostic(error).code ==
          "SPINAL-PARAMETERIZED-VERILOG-RECURSION-BINDING-UNPROVEN"
      )
    }
  }

  @annotation.tailrec
  private def parameterizedDiagnostic(error: Throwable): ParameterizedVerilogException =
    error match {
      case diagnostic: ParameterizedVerilogException => diagnostic
      case value if value != null && value.getCause != null =>
        parameterizedDiagnostic(value.getCause)
      case _ => fail("no parameterized Verilog diagnostic in exception chain")
    }

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-increment-61-compatibility-")
    try body(directory)
    finally deleteTree(directory)
  }

  private def deleteTree(root: Path): Unit = {
    if (Files.exists(root)) {
      val stream = Files.walk(root)
      try {
        stream.iterator().asScala.toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(Files.deleteIfExists)
      } finally stream.close()
    }
  }
}
