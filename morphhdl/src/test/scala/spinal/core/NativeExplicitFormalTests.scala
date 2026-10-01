package spinal.core

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import scala.sys.process.{Process, ProcessLogger}
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.{HdlInt, formalParam}
import org.scalatest.funsuite.AnyFunSuite

class NativeExplicitFormalTests extends AnyFunSuite {
  class WidthChild(actual: ElabInt) extends Component {
    @dontName private val width = formalParam(actual, "WIDTH", 1, 256)
    val din = in Bits(width bits)
    val dout = out Bits(width bits)
    dout := din
  }
  class ScalarChild(actual: ElabInt, zero: Boolean) extends Component {
    @dontName private val scalar = if (zero) formalParam(actual, "PPC4", 0, 1)
      else formalParam(actual, "BUS_LOG2_BYTES", 2, 5)
    @dontName private val width = if (zero) scalar * 3 + 1 else scalar.pow2 * 8
    val din = in Bits(width bits)
    val dout = out Bits(width bits)
    dout := din
  }
  class Middle(actual: ElabInt) extends Component {
    @dontName private val width = formalParam(actual, "MIDDLE_WIDTH", 1, 256)
    val din = in Bits(width bits)
    val dout = out Bits(width bits)
    val child = new WidthChild(width)
    child.din := din
    dout := child.dout
  }
  class Parent(kind: Int) extends Component {
    @dontName private val root = if (kind == 2) HdlInt.param("PARENT_PPC4", 0, 0, 1).asElabInt
      else HdlInt.param("PARENT_LOG_BYTES", 3, 2, 5).asElabInt
    @dontName private val width = if (kind == 2) root * 3 + 1 else root.pow2 * 8
    val din = in Bits(width bits)
    val dout = out Bits(width bits)
    if (kind == 3) {
      val child = new Middle(width)
      child.din := din
      dout := child.dout
    } else if (kind == 0) {
      val child = new WidthChild(width)
      child.din := din
      dout := child.dout
    } else {
      val child = new ScalarChild(root, kind == 2)
      child.din := din
      dout := child.dout
    }
  }
  private def run(dir: Path, args: Seq[String]): (Int, String) =
    morphhdl.Increment66ToolEvidence.run(dir,args)
  for (kind <- 0 to 3; split <- Seq(false, true))
  test(s"explicit native child formals preserve derived actuals and scalar zero, kind=$kind split=$split") {
    val dir = Files.createTempDirectory("native-explicit-formal-")
    def emit(destination: Path): Unit = {
      MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory = destination.toString,
        oneFilePerComponent = split, headerWithDate = false), enabled = false))(new Parent(kind))
    }
    emit(dir)
    val repeated = Files.createTempDirectory("native-explicit-formal-repeat-")
    emit(repeated)
    val stream = Files.list(dir)
    val sources = try {
      import scala.collection.JavaConverters._
      stream.iterator().asScala.filter(_.toString.endsWith(".v")).map(_.toString).toVector
    } finally stream.close()
    sources.foreach { path =>
      val file = java.nio.file.Paths.get(path)
      assert(java.util.Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(repeated.resolve(file.getFileName))),
        s"nondeterministic formal artifact $file")
    }
    val lint = run(dir, Seq("verilator", "--lint-only", "-Wno-fatal", "--top-module", "Parent") ++ sources)
    assert(lint._1 == 0, lint._2)
    val rtl = sources.map(x => new String(Files.readAllBytes(java.nio.file.Paths.get(x)), UTF_8)).mkString("\n")
    assert(rtl.contains(if (kind == 0 || kind == 3) ".WIDTH(" else if (kind == 1) ".BUS_LOG2_BYTES(" else ".PPC4("), rtl)
    if (kind == 0) assert(!rtl.contains(".WIDTH(PARENT_LOG_BYTES)"), rtl)
    val values = if (kind == 2) 0 to 1 else 2 to 5
    for (value <- values) {
      val parameter = if (kind == 2) "PARENT_PPC4" else "PARENT_LOG_BYTES"
      val width = if (kind == 2) value * 3 + 1 else (1 << value) * 8
      val synthesis = run(dir, Seq("yosys", "-p",
        s"read_verilog -DSYNTHESIS ${sources.mkString(" ")}; chparam -set $parameter $value Parent; prep -top Parent -flatten; check -assert; sat -verify -prove dout din -show-ports"))
      assert(synthesis._1 == 0, synthesis._2)
      Files.write(dir.resolve("tb.v"), s"""module tb;
        |reg [$width-1:0] din; wire [$width-1:0] dout; integer i;
        |Parent #(.$parameter($value)) dut(din,dout);
        |initial begin
        |for(i=0;i<$width;i=i+1) begin din=0; din[i]=1'b1; #1; if(dout !== din) $$fatal(1,"FORMAL_BINDING"); end
        |din={$width{1'bx}}; #1; if(dout !== din) $$fatal(1,"FORMAL_X");
        |din={$width{1'bz}}; #1; if(dout !== din) $$fatal(1,"FORMAL_Z");
        |$$finish; end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      val compiled = run(dir, Seq("iverilog", "-g2001", "-s", "tb", "-o", "formal.vvp") ++ sources :+ "tb.v")
      assert(compiled._1 == 0, compiled._2)
      val simulated = run(dir, Seq("vvp", "formal.vvp"))
      assert(simulated._1 == 0, simulated._2)
    }
  }
}
