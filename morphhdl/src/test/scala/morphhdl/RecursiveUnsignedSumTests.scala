package morphhdl

import java.nio.file.{Files, Path, Paths}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.RecursiveUnsignedSum
import morphhdl.frontend._

object RecursiveUnsignedSumArtifactWriter {
  def generate(directory: Path, default: Int, split: Boolean = false): String = {
    Files.createDirectories(directory)
    MorphVerilog(SpinalConfig(targetDirectory = directory.toString, oneFilePerComponent = split))(
      new RecursiveUnsignedSum(ElabInt.literal(default), ElabInt.literal(32), ElabInt.literal(36), 17, 32, 36))
    new String(Files.readAllBytes(directory.resolve("RecursiveUnsignedSum.v")), "UTF-8")
  }
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "supply an output directory")
    for (default <- Seq(1,16); split <- Seq(false,true))
      generate(Paths.get(args(0)).resolve(s"default-$default-split-$split"), default, split)
  }
}

private object RecursiveUnsignedSumNegativeFixture {
  final class Broken(mode: String) extends Component {
    setDefinitionName("BrokenRecursiveSum")
    @dontName val count = HdlInt.param("COUNT", default=3, min=1, max=8).asElabInt
    val terms = in Bits((count * 4) bits)
    val sum = out UInt(8 bits)
    class Reference(next: ElabInt, wrongWidth: Boolean = false) extends BlackBox {
      setBlackBoxName("BrokenRecursiveSum")
      addGeneric("COUNT", next)
      val terms = in Bits((next * 4 + (if(wrongWidth) 1 else 0)) bits)
      val sum = out UInt(8 bits)
    }
    def step(): Unit = {
      val left = new Reference(if(mode=="missing-base") (count+1)/2 else count/2)
      val right = new Reference(if(mode=="nondecreasing") count else if(mode=="out-of-domain") count-2 else if(mode=="missing-base") (count+1)/2 else count-count/2,
        mode=="geometry")
      left.terms := 0
      right.terms := 0
      sum := left.sum + right.sum
      if(mode=="competing") sum := left.sum
    }
    if(mode=="missing-base") step()
    else ElabControl.selectSymbolic(count <= 1, "RecursiveSumNegative", 1) { sum := 0 } { step() }
  }
}

class RecursiveUnsignedSumTests extends AnyFunSuite {
  for(default <- Seq(1,16); split <- Seq(false,true))
  test(s"one recursive unsigned definition retains both branches default=$default split=$split") {
    val dir = Files.createTempDirectory("recursive-sum-")
    val rtl = RecursiveUnsignedSumArtifactWriter.generate(dir,default,split)
    assert("(?m)^module RecursiveUnsignedSum".r.findAllIn(rtl).size == 1)
    assert("(?m)^\\s*RecursiveUnsignedSum #".r.findAllIn(rtl).size == 2, rtl)
    assert(rtl.contains(".COUNT      ((COUNT / 2)"),rtl)
    assert(rtl.contains(".COUNT      ((COUNT - (COUNT / 2))"),rtl)
    assert(rtl.contains("terms[0 +: ((COUNT / 2) * TERM_WIDTH)]"),rtl)
    assert(rtl.contains("terms[((COUNT / 2) * TERM_WIDTH) +: ((COUNT - (COUNT / 2)) * TERM_WIDTH)]"),rtl)
    assert(rtl.contains(") left (") && rtl.contains(") right ("),rtl)
    assert(rtl.contains("COUNT must be in 1..17"),rtl)
    assert(!rtl.contains("$fatal") && rtl.contains("$finish"),rtl)
    assert(!rtl.contains("posedge") && !rtl.contains("negedge"),rtl)
    assert(rtl == RecursiveUnsignedSumArtifactWriter.generate(Files.createTempDirectory("recursive-sum-repeat-"),default,split))
  }

  for (reverse <- Seq(false, true); split <- Seq(false, true))
  test(s"recursive unsigned children share one definition reverse=$reverse split=$split") {
    val dir = Files.createTempDirectory("recursive-parent-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString, oneFilePerComponent=split)) {
      new Component {
        val a = in Bits(ElabInt.literal(3*4) bits)
        val b = in Bits(ElabInt.literal(16*4) bits)
        val x = out UInt(ElabInt.literal(8) bits)
        val y = out UInt(ElabInt.literal(8) bits)
        val reductions = (if(reverse) Seq(16,3) else Seq(3,16)).map { n =>
          n -> new RecursiveUnsignedSum(ElabInt.literal(n),ElabInt.literal(4),ElabInt.literal(8),17,32,36)
        }.toMap
        val first = reductions(3)
        val second = reductions(16)
        first.terms := a; second.terms := b
        x := first.sum; y := second.sum
      }
    }
    val files = dir.toFile.listFiles().filter(_.getName.endsWith(".v"))
    val all = files.map(f => new String(Files.readAllBytes(f.toPath), "UTF-8")).mkString("\n")
    assert("(?m)^module RecursiveUnsignedSum".r.findAllIn(all).size == 1, all)
    assert(all.contains(".COUNT"), all)
  }

  test("recursive unsigned component retains a symbolic parent count binding") {
    val dir = Files.createTempDirectory("recursive-symbolic-parent-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString)) {
      new Component {
        setDefinitionName("SymbolicSumParent")
        @dontName val count = HdlInt.param("TERMS",3,1,17).asElabInt
        val terms = in Bits((count * 4) bits)
        val sum = out UInt(ElabInt.literal(8) bits)
        val child = new RecursiveUnsignedSum(count,ElabInt.literal(4),ElabInt.literal(8),17,32,36)
        child.terms := terms
        sum := child.sum
      }
    }
    val rtl = new String(Files.readAllBytes(dir.resolve("SymbolicSumParent.v")),"UTF-8")
    assert("\\.COUNT\\s*\\(TERMS\\s*\\)".r.findFirstIn(rtl).nonEmpty,rtl)
  }

  test("recursive unsigned components reject conflicting definition domains") {
    intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("recursive-conflict-").toString)) {
        new Component {
          val terms = in Bits(ElabInt.literal(12) bits)
          val sums = out Bits(ElabInt.literal(16) bits)
          val first = new RecursiveUnsignedSum(ElabInt.literal(3),ElabInt.literal(4),ElabInt.literal(8),17,32,36)
          val second = new RecursiveUnsignedSum(ElabInt.literal(3),ElabInt.literal(4),ElabInt.literal(8),16,32,36)
          first.terms := terms; second.terms := terms
          sums := first.sum.asBits ## second.sum.asBits
        }
      }
    }
  }

  for((count,term,sum) <- Seq((0,4,8),(-1,4,8),(18,4,8),(3,0,8),(3,-1,8),(3,33,8),(3,4,0),(3,4,-1),(3,4,37)))
  test(s"recursive unsigned API rejects invalid dimensions $count $term $sum") {
    intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("recursive-invalid-").toString))(
        new RecursiveUnsignedSum(ElabInt.literal(count),ElabInt.literal(term),ElabInt.literal(sum),17,32,36))
    }
  }

  for(mode <- Seq("nondecreasing","out-of-domain","geometry","missing-base","competing"))
  test(s"recursive unsigned child validation rejects $mode") {
    val error = intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("recursive-invalid-child-").toString))(
        new RecursiveUnsignedSumNegativeFixture.Broken(mode))
    }
    if(mode=="geometry") assert(error.getMessage.contains("RECURSION-PORT-SCHEMA-MISMATCH"))
    if(mode=="nondecreasing") assert(error.getMessage.contains("RECURSION-METRIC-NONDECREASING"))
    if(mode=="missing-base") assert(error.getMessage.contains("RECURSION-BINDING-UNPROVEN"))
  }
}
