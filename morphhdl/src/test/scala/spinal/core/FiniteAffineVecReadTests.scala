package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.sys.process.{Process, ProcessLogger}
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

private object FiniteAffineVecReadFixture {
  final class PairMinimum(width: ElabInt, count: ElabInt) extends Component {
    setDefinitionName("FiniteAffinePairMinimum")
    val source = in(Vec(SInt(width bits), count)).setName("source")
    val result = out(Vec(SInt(width bits), count / 2)).setName("result")
    ElabFiniteRange.foreach(count / 2, "pairs") { index =>
      val left = index.affine(source, 2, 0)
      val right = index.affine(source, 2, 1)
      index(result) := Mux(left < right, left, right)
    }
  }

  final class Selection(count: ElabInt, depth: ElabInt, coefficient: Int,
      offset: Int, write: Boolean = false) extends Component {
    val source = in(Vec(UInt(4 bits), depth)).setName("source")
    val result = out(Vec(UInt(4 bits), count)).setName("result")
    ElabFiniteRange.foreach(count, "selection") { index =>
      val selected = index.affine(source, coefficient, offset)
      if (write) selected := U(0, 4 bits)
      index(result) := selected
    }
  }
}

class FiniteAffineVecReadTests extends AnyFunSuite {
  import FiniteAffineVecReadFixture._

  test("affine Vec pairs retain distinct selectors and signed native comparison") {
    val directory = Files.createTempDirectory("finite-affine-pairs-")
    val width = parameter("WIDTH", 5, 1, 8)
    val count = parameter("COUNT", 3, 2, 9)
    val rtl = emit(directory, new PairMinimum(width, count))
    val compact = rtl.replaceAll("\\s+", "")
    assert(compact.contains("(2*pairs_index_") && compact.contains("+1)"), rtl)
    assert(compact.contains("$signed(source["), rtl)
    assert(compact.contains("parameterintegerWIDTH=5") && compact.contains("parameterintegerCOUNT=3"), rtl)
    assert("(?<![A-Za-z0-9_$])morphhdl_structural_vec_alias_[0-9]+".r.findFirstIn(rtl).isEmpty, rtl)
    if (available("iverilog") && available("vvp")) {
      for (w <- Vector(1, 5, 8); n <- Vector(2, 3, 5, 8, 9)) {
        simulatePairs(directory, w, n)
      }
    }
  }

  test("affine Vec read rejects an independently rooted logical depth") {
    val count = parameter("COUNT", 2, 1, 4)
    val depth = parameter("DEPTH", 4, 2, 8)
    reject(new Selection(count, depth, 2, 0), "SPINAL-ELAB-FINITE-AFFINE-ROOT-MISMATCH")
  }

  test("affine Vec read proves every logical depth beyond matching extrema") {
    val depth = parameter("DEPTH", 4, 2, 8)
    reject(new Selection((depth + 1) / 2, depth, 2, 1), "SPINAL-ELAB-FINITE-AFFINE-DOMAIN-OUT-OF-RANGE")
  }

  test("affine Vec selectors reject writes even when the pair count is safe") {
    val depth = parameter("DEPTH", 4, 2, 8)
    reject(new Selection(depth / 2, depth, 2, 0, write = true), "SPINAL-ELAB-FINITE-AFFINE-WRITE-UNSUPPORTED")
  }

  test("affine Vec selectors reject zero or negative coefficients and offsets") {
    Vector((0, 0), (-1, 0), (2, -1)).foreach { case (coefficient, offset) =>
      val depth = parameter("DEPTH", 4, 2, 8)
      reject(new Selection(depth / 2, depth, coefficient, offset), "SPINAL-ELAB-FINITE-AFFINE-COEFFICIENT-INVALID")
    }
  }

  test("affine Vec selectors cannot escape into a different finite-range owner") {
    val depth = parameter("DEPTH", 4, 2, 8)
    reject(new Component {
      val source = in(Vec(UInt(4 bits), depth))
      val result = out(Vec(UInt(4 bits), depth / 2))
      ElabFiniteRange.foreach(depth / 2, "outer") { outer =>
        ElabFiniteRange.foreach(depth / 2, "inner") { inner =>
          inner(result) := outer.affine(source, 2, 0)
        }
      }
    }, "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-FINITE-INDEX-TOKEN-CONFLICT")
  }

  test("literal affine Vec indexing retains ordinary native static behavior") {
    val directory = Files.createTempDirectory("finite-affine-literal-")
    config(directory).generateVerilog(new Selection(ElabInt.literal(2), ElabInt.literal(4), 2, 1))
    val rtl = new String(Files.readAllBytes(directory.resolve("design.v")), StandardCharsets.UTF_8)
    assert(!rtl.contains("genvar"), rtl)
  }

  private def parameter(name: String, default: Int, minimum: Int, maximum: Int): ElabInt =
    HdlInt.param(name, default = default, min = minimum, max = maximum).asElabInt

  private def config(directory: Path): SpinalConfig = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = "design.v"
    config
  }

  private def emit(directory: Path, component: => Component): String = {
    MorphVerilog(config(directory))(component)
    new String(Files.readAllBytes(directory.resolve("design.v")), StandardCharsets.UTF_8)
  }

  private def reject(component: => Component, code: String): Unit = {
    val directory = Files.createTempDirectory("finite-affine-reject-")
    MorphVerilog.tryGenerate(config(directory))(component) match {
      case Left(error) => assert(error.detail.contains(code), error.detail)
      case Right(_) => fail(s"expected $code")
    }
    assert(!Files.exists(directory.resolve("design.v")))
  }

  private def available(command: String): Boolean =
    Process(Seq("sh", "-c", s"command -v $command")).!(ProcessLogger(_ => (), _ => ())) == 0

  private def simulatePairs(directory: Path, width: Int, count: Int): Unit = {
    val testbench = directory.resolve(s"tb_${width}_$count.v")
    val executable = directory.resolve(s"tb_${width}_$count.out")
    val text = s"""module tb;
      |localparam W = $width;
      |localparam N = $count;
      |reg [(W*N)-1:0] source;
      |wire [(W*(N/2))-1:0] result;
      |reg signed [W-1:0] left, right, expected;
      |integer iteration, lane;
      |FiniteAffinePairMinimum #(.WIDTH(W), .COUNT(N)) dut (.source(source), .result(result));
      |initial begin
      |  for (iteration = 0; iteration < 128; iteration = iteration + 1) begin
      |    for (lane = 0; lane < N; lane = lane + 1)
      |      source[lane*W +: W] = iteration * (lane + 1) + lane;
      |    #1;
      |    for (lane = 0; lane < N/2; lane = lane + 1) begin
      |      left = source[(2*lane)*W +: W];
      |      right = source[(2*lane+1)*W +: W];
      |      expected = left < right ? left : right;
      |      if (result[lane*W +: W] !== expected) begin
      |        $$display("FAIL width=%0d count=%0d lane=%0d", W, N, lane);
      |        $$finish;
      |      end
      |    end
      |  end
      |  $$display("PASS affine signed pairs");
      |  $$finish;
      |end
      |endmodule
      |""".stripMargin
    Files.write(testbench, text.getBytes(StandardCharsets.UTF_8))
    assert(Process(Seq("iverilog", "-g2001", "-s", "tb", "-o", executable.toString,
      directory.resolve("design.v").toString, testbench.toString)).! == 0)
    val output = Process(Seq("vvp", executable.toString)).!!
    assert(output.contains("PASS affine signed pairs") && !output.contains("FAIL"), output)
  }
}
