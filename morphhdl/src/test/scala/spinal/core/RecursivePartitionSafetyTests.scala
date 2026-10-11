package spinal.core

import java.nio.file.Files
import morphhdl.MorphVerilog
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.internals._
import spinal.lib.RecursiveUnsignedSum

class RecursivePartitionSafetyTests extends AnyFunSuite {
  for(mutation <- Seq("source", "target", "width", "overlap", "missing"))
  test(s"recursive partition rejects altered $mutation lineage") {
    val error = intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("recursive-lineage-").toString)) {
        new RecursiveUnsignedSum(ElabInt.literal(4),ElabInt.literal(4),ElabInt.literal(8),8,8,8) {
          val entries = ElabPackedPartition.entries(this)
          val step = entries.tail
          val entry = step.last
          mutation match {
            case "source" => entry.assignment.source = terms
            case "target" => entry.assignment.target = Bits(entry.result.getBitsWidth bits)
            case "width" => entry.result.setWidth(entry.result.getBitsWidth+1)
            case "overlap" | "missing" =>
              val input = children.collect { case child: BlackBox => child }.last.getOrdredNodeIo
                .find(_.isInput).get
              val edge = input.head.asInstanceOf[DataAssignmentStatement]
              if(mutation=="overlap") edge.source = step.head.result
              else edge.source = B(0,input.getBitsWidth bits).head.source
          }
          ElabPackedPartition.of(this,entry.assignment)
        }
      }
    }
    assert(error.toString.contains("recursive partition") ||
      (mutation == "width" && error.toString.contains("getWidth result differ from last call")),error.toString)
  }
}
