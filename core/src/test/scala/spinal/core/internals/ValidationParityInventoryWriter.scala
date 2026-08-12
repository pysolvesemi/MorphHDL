package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import scala.collection.JavaConverters._

import spinal.core._

object ValidationParityInventoryWriter {
  def main(args: Array[String]): Unit = {
    val output = args.toVector match {
      case Vector("--output", path) => Paths.get(path)
      case _ =>
        throw new IllegalArgumentException(
          "Usage: ValidationParityInventoryWriter --output <file>"
        )
    }

    val witnessDirectory = Files.createTempDirectory("morphhdl-phase-inventory-")
    try {
      val report = SpinalVerilog(SpinalConfig(targetDirectory = witnessDirectory.toString)) {
        new Component {
          setDefinitionName("ValidationParityInventoryProbe")
          val input = in(Bool())
          val output = out(Bool())
          output := input
        }
      }
      if (report.inheritedValidationPhaseIds != report.expectedInheritedValidationPhaseIds) {
        throw new IllegalStateException(
          s"shared phase plan drift: expected ${report.expectedInheritedValidationPhaseIds.mkString(",")}, " +
            s"observed ${report.inheritedValidationPhaseIds.mkString(",")}"
        )
      }
      if (report.inheritedValidationPhaseIds.distinct.size != report.inheritedValidationPhaseIds.size) {
        throw new IllegalStateException("shared phase plan contains duplicate validation IDs")
      }
      Option(output.getParent).foreach { parent => Files.createDirectories(parent) }
      Files.write(
        output,
        (report.inheritedValidationPhaseIds.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
    } finally deleteTree(witnessDirectory)
  }

  private def deleteTree(root: Path): Unit = {
    val stream = Files.walk(root)
    try {
      stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
        Files.deleteIfExists(path)
      }
    } finally stream.close()
  }
}
