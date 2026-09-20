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
      var inheritedValidationPhaseIds: Vector[String] = null
      val config = SpinalConfig(targetDirectory = witnessDirectory.toString)
      config.phasesInserters += { phases =>
        inheritedValidationPhaseIds = ValidationPhaseInventory.idsOf(phases)
      }
      SpinalVerilog(config) {
        new Component {
          setDefinitionName("ValidationParityInventoryProbe")
          val input = in(Bool())
          val output = out(Bool())
          output := input
        }
      }
      if (inheritedValidationPhaseIds == null) {
        throw new IllegalStateException("baseline phase inserter did not run")
      }
      if (inheritedValidationPhaseIds != ValidationPhaseInventory.expectedIds) {
        throw new IllegalStateException(
          s"inherited phase inventory drift: expected ${ValidationPhaseInventory.expectedIds.mkString(",")}, " +
            s"observed ${inheritedValidationPhaseIds.mkString(",")}"
        )
      }
      if (inheritedValidationPhaseIds.distinct.size != inheritedValidationPhaseIds.size) {
        throw new IllegalStateException("inherited phase inventory contains duplicate validation IDs")
      }
      Option(output.getParent).foreach { parent => Files.createDirectories(parent) }
      Files.write(
        output,
        (inheritedValidationPhaseIds.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8),
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
