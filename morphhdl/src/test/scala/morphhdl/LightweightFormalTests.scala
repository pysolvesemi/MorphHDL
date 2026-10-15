package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/** Two-state combinational proof. Four-state behavior is tested separately by
  * the simulation fixtures; a SAT result is never presented as X/Z equivalence.
  */
class LightweightFormalTests extends AnyFunSuite {
  for (subtract <- Seq(false, true)) test(s"direct recursive tree equals independent flat adjacent pairs subtract=$subtract") {
    val directory = Files.createTempDirectory("lightweight-recursive-formal-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
      vecLayout = MorphAggregateOptions.UnpackedArray)) {
      new LightweightRecursiveTree(ElabInt.literal(1), ElabInt.literal(8), subtract)
    }
    for ((count,width) <- Seq((1,1),(3,8),(17,36))) {
      val lines = scala.collection.mutable.ArrayBuffer.empty[String]
      lines += s"module Miter(input [${count*width-1}:0] data, output ok);"
      lines += s"wire [${width-1}:0] result;"
      lines += s"LightweightRecursiveTree #(.COUNT($count),.WIDTH($width)) dut(.terms(data),.result(result));"
      var words = (0 until count).map(i => s"data[${i*width}+:$width]").toVector
      var level = 0
      while (words.size > 1) {
        val previous = words
        words = previous.grouped(2).zipWithIndex.map { case (pair,index) =>
          val name = s"stage_${level}_$index"
          val expression = if (pair.size == 1) pair.head else pair.mkString(if (subtract) " - " else " + ")
          lines += s"wire [${width-1}:0] $name = $expression;"
          name
        }.toVector
        level += 1
      }
      lines += s"assign ok = result == ${words.head};"
      lines += "endmodule"
      Files.write(directory.resolve("miter.v"), lines.mkString("\n").getBytes("UTF-8"))
      val command = Seq("yosys", "-Q", "-T", "-p",
        "read_verilog -formal -DSYNTHESIS LightweightRecursiveTree.v miter.v; prep -top Miter -flatten; check -assert; sat -verify -prove ok 1 -set-def-inputs -show-inputs")
      val (status, log) = Increment66ToolEvidence.run(directory, command)
      assert(status == 0, s"count=$count width=$width $directory\n$log")
      Files.write(directory.resolve(s"formal-$count-$width.log"), log.getBytes("UTF-8"))
    }
  }
}
