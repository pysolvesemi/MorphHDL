from pathlib import Path
import re

path = Path('morphhdl/src/test/scala/morphhdl/NativeLibraryReuseTests.scala')
text = path.read_text()
if 'one generated StreamFifo definition supports depth overrides 1, 3, 5 and 8' in text:
    raise SystemExit(0)

signature = re.search(
    r'private\s+def\s+emitMorph\s*\((.*?)\)\s*:\s*String',
    text,
    re.S
)
assert signature, 'emitMorph signature not found'
parameters = signature.group(1)
parts = []
start = 0
level = 0
for index, token in enumerate(parameters):
    if token in '([{':
        level += 1
    elif token in ')]}':
        level -= 1
    elif token == ',' and level == 0:
        parts.append(parameters[start:index].strip())
        start = index + 1
parts.append(parameters[start:].strip())

arguments = []
for part in parts:
    head = part.split('=', 1)[0]
    _, value_type = head.split(':', 1)
    value_type = value_type.strip()
    if 'Component' in value_type:
        component = 'StreamFifo(Bits(8 bits), depth)'
        if '() =>' in value_type or 'Function0' in value_type:
            component = '() => ' + component
        arguments.append(component)
    elif 'Path' in value_type:
        arguments.append('directory')
    elif 'Boolean' in value_type:
        arguments.append('false')
    elif 'String' in value_type:
        arguments.append('"StreamFifo"')
    else:
        raise AssertionError('unsupported emitMorph parameter: ' + part)
emit_call = 'emitMorph(' + ', '.join(arguments) + ')'

block = r'''

  test("one generated StreamFifo definition supports depth overrides 1, 3, 5 and 8") {
    withTemporaryDirectory { directory =>
      val depthParameter = ElaborationIntegerParameter(
        "DEPTH",
        BigInt(5),
        BigInt(1),
        BigInt(8)
      )
      val depthExpression = ElaborationIntegerExpression(
        verilog = "DEPTH",
        default = BigInt(5),
        minimum = BigInt(1),
        maximum = BigInt(8),
        parameters = Vector(depthParameter),
        sourceLocation = Some("NativeLibraryReuseTests.scala:parameterized-depth")
      )
      val depth = ParameterizedMemoryDepth(
        value = 5,
        expression = depthExpression,
        sourceLocation = depthExpression.sourceLocation
      )
      val verilog = __EMIT_CALL__

      assert(verilog.contains("DEPTH"))
      assert(verilog.contains("assign streamFifoDepth = DEPTH;"))
      assert(verilog.contains("[0:DEPTH-1]"))
      assert(verilog.contains("clog2(DEPTH, 1)"))
      assert(verilog.contains("clog2((DEPTH + 1), 1)"))
      assert("(?m)^module\\s+StreamFifo\\b".r.findAllIn(verilog).size == 1)

      val rtl = directory.resolve("StreamFifo_parameterized_depth.v")
      java.nio.file.Files.write(
        rtl,
        verilog.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )

      Seq(1, 3, 5, 8).foreach { selectedDepth =>
        val testbench =
          """`timescale 1ns/1ps
            |module tb;
            |  parameter integer DEPTH = @DEPTH@;
            |  reg clk = 1'b0;
            |  reg reset = 1'b1;
            |  reg io_push_valid = 1'b0;
            |  wire io_push_ready;
            |  reg [7:0] io_push_payload = 8'h00;
            |  wire io_pop_valid;
            |  reg io_pop_ready = 1'b0;
            |  wire [7:0] io_pop_payload;
            |  reg io_flush = 1'b0;
            |  integer capacity;
            |  integer sent;
            |  integer received;
            |  integer timeout;
            |
            |  always #5 clk = ~clk;
            |
            |  StreamFifo #(.DEPTH(DEPTH)) dut (
            |    .io_push_valid(io_push_valid),
            |    .io_push_ready(io_push_ready),
            |    .io_push_payload(io_push_payload),
            |    .io_pop_valid(io_pop_valid),
            |    .io_pop_ready(io_pop_ready),
            |    .io_pop_payload(io_pop_payload),
            |    .io_flush(io_flush),
            |    .io_occupancy(),
            |    .io_availability(),
            |    .clk(clk),
            |    .reset(reset)
            |  );
            |
            |  task tick;
            |    begin
            |      @(posedge clk);
            |      #1;
            |    end
            |  endtask
            |
            |  task fail;
            |    input [255:0] reason;
            |    begin
            |      $display("FAIL depth=%0d: %0s", DEPTH, reason);
            |      $finish(2);
            |    end
            |  endtask
            |
            |  initial begin
            |    repeat (3) tick;
            |    reset = 1'b0;
            |    tick;
            |
            |    capacity = (DEPTH == 1) ? 1 : DEPTH + 1;
            |    for (sent = 0; sent < capacity; sent = sent + 1) begin
            |      io_push_payload = 8'h40 + sent;
            |      io_push_valid = 1'b1;
            |      timeout = 0;
            |      while (!io_push_ready && timeout < 50) begin
            |        tick;
            |        timeout = timeout + 1;
            |      end
            |      if (!io_push_ready) fail("push timeout");
            |      tick;
            |    end
            |    io_push_valid = 1'b0;
            |    tick;
            |    if (io_push_ready !== 1'b0) fail("fifo did not report full");
            |
            |    io_pop_ready = 1'b1;
            |    received = 0;
            |    timeout = 0;
            |    while (received < capacity && timeout < 200) begin
            |      if (io_pop_valid) begin
            |        if (io_pop_payload !== (8'h40 + received))
            |          fail("payload ordering mismatch");
            |        received = received + 1;
            |      end
            |      tick;
            |      timeout = timeout + 1;
            |    end
            |    if (received != capacity) fail("pop timeout");
            |    io_pop_ready = 1'b0;
            |    tick;
            |    if (io_pop_valid !== 1'b0) fail("fifo did not become empty");
            |
            |    io_push_payload = 8'hA5;
            |    io_push_valid = 1'b1;
            |    while (!io_push_ready) tick;
            |    tick;
            |    io_push_valid = 1'b0;
            |    io_flush = 1'b1;
            |    tick;
            |    io_flush = 1'b0;
            |    tick;
            |    if (io_pop_valid !== 1'b0) fail("flush did not quarantine queued data");
            |
            |    $display("PASS depth=%0d", DEPTH);
            |    $finish;
            |  end
            |endmodule
            |""".stripMargin.replace("@DEPTH@", selectedDepth.toString)

        val tb = directory.resolve(s"tb_depth_$selectedDepth.v")
        val image = directory.resolve(s"tb_depth_$selectedDepth.out")
        java.nio.file.Files.write(
          tb,
          testbench.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )

        def run(command: Seq[String]): String = {
          val process = new ProcessBuilder(command: _*)
            .redirectErrorStream(true)
            .start()
          val source = scala.io.Source.fromInputStream(process.getInputStream)
          val output = try source.mkString finally source.close()
          val exit = process.waitFor()
          assert(exit == 0, command.mkString(" ") + " failed:\n" + output)
          output
        }

        run(
          Seq(
            "iverilog",
            "-g2001",
            "-s",
            "tb",
            "-o",
            image.toString,
            rtl.toString,
            tb.toString
          )
        )
        val output = run(Seq("vvp", image.toString))
        assert(output.contains(s"PASS depth=$selectedDepth"), output)
      }
    }
  }
'''.replace('__EMIT_CALL__', emit_call)

closing = text.rfind('\n}')
assert closing > 0
path.write_text(text[:closing] + block + text[closing:])
