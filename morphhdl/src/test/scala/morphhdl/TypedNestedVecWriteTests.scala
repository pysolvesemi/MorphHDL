package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class TypedNestedVecWriteTests extends AnyFunSuite {
  private def verify(dir: java.nio.file.Path): Unit = {
      Seq(
        Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "tb.v"),
        Seq("timeout", "10", "vvp", "sim"),
        Seq("verilator", "--lint-only", "-Wall", "--language", "1364-2001", "--top-module", "Top", "Top.v"),
        Seq("yosys", "-Q", "-p", "read_verilog Top.v; synth -top Top; check -assert")
      ).foreach { command =>
        val (code, log) = Increment66ToolEvidence.run(dir, command)
        assert(code == 0, s"$command failed in $dir\n$log")
      }
  }
  for (unpacked <- Seq(false, true); mode <- Seq("partial", "conditional", "read-only")) {
    test(s"nested aliases retain native missing-driver and latch checks mode=$mode unpacked=$unpacked") {
      val dir = Files.createTempDirectory("typed-nested-reject-")
      val error = intercept[Exception] {
        MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
          headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
          vecLayout = if (unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
          new Component {
            setDefinitionName("Top")
            val en = in Bool()
            val x = in(Vec(Vec(Bits(8 bits), 3), 2))
            val y = out(Vec(Vec(Bits(8 bits), 3), 2))
            val observed = out(Vec(Bits(8 bits), 2))
            ElabFiniteRange.foreach(ElabInt.literal(2), "rows") { row =>
              val xr = row(x); val yr = row(y)
              row(observed) := xr(0)
              ElabFiniteRange.foreach(ElabInt.literal(if (mode == "partial") 2 else 3), "columns") { column =>
                if (mode == "conditional") when(en) { column(yr) := column(xr) }
                else if (mode != "read-only") column(yr) := column(xr)
                else column(yr)
              }
            }
          }
        }
      }
      val detail = Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null)
        .flatMap(e => Option(e.getMessage)).mkString("\n")
      val expected = mode match {
        case "partial" => "SPINAL-ELAB-FINITE-RANGE-VEC-DEPTH-MISMATCH"
        case "conditional" => "LATCH DETECTED"
        case "read-only" => "NO DRIVER"
      }
      assert(detail.contains(expected), detail)

      assert(!Files.exists(dir.resolve("Top.v")))
    }
  }
  private class ParameterizedGrid(rows: ElabInt, columns: ElabInt) extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Vec(Bits(8 bits), columns), rows))
    val y = out(Vec(Vec(Bits(8 bits), columns), rows))
    ElabFiniteRange.foreach(rows, "rows") { row =>
      val xr = row(x); val yr = row(y)
      ElabFiniteRange.foreach(columns, "columns") { column => column(yr) := ~column(xr) }
    }
  }
  for (unpacked <- Seq(false, true)) {
    test(s"nested selections retain independent parameter bounds unpacked=$unpacked") {
      val dir = Files.createTempDirectory("typed-nested-parameters-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
        headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
        vecLayout = if (unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
        new ParameterizedGrid(morphhdl.frontend.HdlInt.param("ROWS", 2, 1, 4).asElabInt, morphhdl.frontend.HdlInt.param("COLUMNS", 3, 1, 5).asElabInt)
      }
      Files.write(dir.resolve("tb.v"), """module check #(parameter R=1,C=1)(output reg done);
reg [R*C*8-1:0] x; wire [R*C*8-1:0] y;
Top #(.ROWS(R),.COLUMNS(C)) dut(.x(x),.y(y));
integer n;
initial begin done=0; x=0; #1; if(y!==~x) $fatal;
for(n=0;n<R*C*8;n=n+1) begin x=0; x[n]=1; #1; if(y!==~x) $fatal; end
done=1; end
endmodule
module tb;
wire [3:0] done;
check #(1,1) a(done[0]); check #(2,3) b(done[1]);
check #(4,5) c(done[2]); check #(1,5) d(done[3]);
initial begin wait(&done); $finish; end
initial begin #1000; $fatal; end
endmodule
""".getBytes(UTF_8))
      verify(dir)
    }
    test(s"three nested selections preserve singleton axes unpacked=$unpacked") {
      val dir = Files.createTempDirectory("typed-nested-singleton-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
        headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
        vecLayout = if (unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
        new Component {
          setDefinitionName("Top")
          val x = in(Vec(Vec(Vec(Bits(8 bits), 1), 3), 2))
          val y = out(Vec(Vec(Vec(Bits(8 bits), 1), 3), 2))
          ElabFiniteRange.foreach(ElabInt.literal(2), "planes") { plane =>
            val xp=plane(x); val yp=plane(y)
            ElabFiniteRange.foreach(ElabInt.literal(3), "rows") { row =>
              val xr=row(xp); val yr=row(yp)
              ElabFiniteRange.foreach(ElabInt.literal(1), "columns") { column => column(yr) := ~column(xr) }
            }
          }
        }
      }
      Files.write(dir.resolve("tb.v"), """module tb;
reg [47:0] x; wire [47:0] y; integer n;
Top dut(.x(x),.y(y));
initial begin for(n=0;n<48;n=n+1) begin x=48'b1<<n; #1; if(y!==~x) $fatal; end $finish; end
endmodule
""".getBytes(UTF_8))
      verify(dir)
    }
  }

  for (unpacked <- Seq(false, true); retainedRow <- Seq(false, true)) {
    test(s"two typed Vec selections preserve every coordinate unpacked=$unpacked retainedRow=$retainedRow") {
      val dir = Files.createTempDirectory("typed-nested-write-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
        headerWithDate = false), preserveConstantVecs = true, preserveConstantLoops = true,
        vecLayout = if (unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
        new Component {
          setDefinitionName("Top")
          val x = in(Vec(Vec(Bits(8 bits), 3), 2))
          val y = out(Vec(Vec(Bits(8 bits), 3), 2))
          ElabFiniteRange.foreach(ElabInt.literal(2), "rows") { row =>
            if (retainedRow) {
              val xr = row(x)
              val yr = row(y)
              ElabFiniteRange.foreach(ElabInt.literal(3), "columns") { column =>
                column(yr) := ~column(xr)
              }
            } else {
              ElabFiniteRange.foreach(ElabInt.literal(3), "columns") { column =>
                column(row(y)) := ~column(row(x))
              }
            }
          }
        }
      }
      Files.write(dir.resolve("tb.v"), """module tb;
reg [47:0] x; wire [47:0] y;
Top dut(.x(x),.y(y));
integer n;
initial begin
x=48'h123456789abc; #1; if(y!==~x) $fatal;
for(n=0;n<48;n=n+1) begin x=48'b1<<n; #1; if(y!==~x) $fatal; end
$finish; end
endmodule
""".getBytes(UTF_8))
      verify(dir)
    }
  }
}
