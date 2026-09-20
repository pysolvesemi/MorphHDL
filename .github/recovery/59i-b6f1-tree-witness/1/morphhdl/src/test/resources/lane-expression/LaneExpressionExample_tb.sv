`timescale 1ns/1ps
module LaneExpressionExample_tb;
  reg running;
  reg [15:0] h_active, v_active;
  reg [12:0] base_x, h_total;
  reg [11:0] base_y, v_total;
  wire [12:0] next_x_1, next_x_4;
  wire [11:0] next_y_1, next_y_4;
  wire [0:0] de_1, frame_end_1;
  wire [3:0] de_4, frame_end_4;
  integer cases = 0;
  integer i, j, k;
  reg [31:0] random_state = 32'h13579bdf;

  LaneExpressionExample #(.PPC4(0)) one (
    .io_running(running), .io_hActive(h_active), .io_vActive(v_active),
    .io_baseX(base_x), .io_baseY(base_y), .io_hTotal(h_total), .io_vTotal(v_total),
    .io_nextX(next_x_1), .io_nextY(next_y_1), .io_de(de_1), .io_frameEnd(frame_end_1)
  );
  LaneExpressionExample #(.PPC4(1)) four (
    .io_running(running), .io_hActive(h_active), .io_vActive(v_active),
    .io_baseX(base_x), .io_baseY(base_y), .io_hTotal(h_total), .io_vTotal(v_total),
    .io_nextX(next_x_4), .io_nextY(next_y_4), .io_de(de_4), .io_frameEnd(frame_end_4)
  );

  task next_random;
    begin
      random_state = random_state ^ (random_state << 13);
      random_state = random_state ^ (random_state >> 17);
      random_state = random_state ^ (random_state << 5);
    end
  endtask

  task check;
    reg [12:0] x, nx, last_x;
    reg [11:0] y, ny, last_y;
    reg [15:0] extended_x, extended_y, last_active_x, last_active_y;
    reg [3:0] expected_de, expected_end;
    integer lane;
    begin
      // Explicit sizes define each original modular arithmetic domain.
      x = base_x;
      y = base_y;
      last_x = h_total - 13'd1;
      last_y = v_total - 12'd1;
      last_active_x = h_active - 16'd1;
      last_active_y = v_active - 16'd1;
      for (lane = 0; lane < 4; lane = lane + 1) begin
        extended_x = {3'd0, x};
        extended_y = {4'd0, y};
        expected_de[lane] = running && (extended_x < h_active) && (extended_y < v_active);
        expected_end[lane] = running && (extended_x == last_active_x) && (extended_y == last_active_y);
        nx = x + 13'd1;
        ny = y;
        // Use procedural if, not a ternary: X/Z conditions select no branch.
        if (x == last_x) begin
          nx = 13'd0;
          ny = y + 12'd1;
          if (y == last_y) ny = 12'd0;
        end
        x = nx;
        y = ny;
      end
      #1;
      cases = cases + 1;
      if (next_x_1 !== x || next_x_4 !== x || next_y_1 !== y || next_y_4 !== y ||
          de_1 !== expected_de[0:0] || de_4 !== expected_de ||
          frame_end_1 !== expected_end[0:0] || frame_end_4 !== expected_end) begin
        $display("FAIL case=%0d running=%b base=(%h,%h) total=(%h,%h) active=(%h,%h)",
          cases, running, base_x, base_y, h_total, v_total, h_active, v_active);
        $display("expected next=(%h,%h) de=%b end=%b actual next=(%h,%h) de=%b end=%b",
          x,y,expected_de,expected_end,next_x_4,next_y_4,de_4,frame_end_4);
        $fatal(1, "lane lookahead mismatch");
      end
    end
  endtask

  initial begin
    if ($bits(one.io_de) != 1 || $bits(four.io_de) != 4 ||
        $bits(one.io_frameEnd) != 1 || $bits(four.io_frameEnd) != 4)
      $fatal(1, "parameterized port width mismatch");
    running = 0; h_active = 0; v_active = 0;
    base_x = 0; base_y = 0; h_total = 0; v_total = 0;
    check;
    for (i = 0; i < 8; i = i + 1) begin
      case (i)
        0: begin h_total=0; v_total=0; end
        1: begin h_total=1; v_total=1; end
        2: begin h_total=2; v_total=2; end
        3: begin h_total=3; v_total=5; end
        4: begin h_total=1920; v_total=1080; end
        5: begin h_total=8191; v_total=4095; end
        6: begin h_total=1; v_total=4095; end
        7: begin h_total=8191; v_total=1; end
      endcase
      for (j = 0; j < 8; j = j + 1) begin
        case (j)
          0: begin h_active=0; v_active=0; end
          1: begin h_active=1; v_active=1; end
          2: begin h_active=65535; v_active=65535; end
          3: begin h_active=1920; v_active=1080; end
          4: begin h_active=8191; v_active=4095; end
          5: begin h_active=8192; v_active=4096; end
          6: begin h_active=0; v_active=65535; end
          7: begin h_active=65535; v_active=0; end
        endcase
        for (k = 0; k < 12; k = k + 1) begin
          running = k[0];
          case (k / 2)
            0: begin base_x=0; base_y=0; end
            1: begin base_x=h_total-13'd1; base_y=v_total-12'd1; end
            2: begin base_x=h_total-13'd2; base_y=v_total-12'd1; end
            3: begin base_x=8191; base_y=4095; end
            4: begin base_x=h_active-16'd1; base_y=v_active-16'd1; end
            5: begin base_x=h_active; base_y=v_active; end
          endcase
          check;
        end
      end
    end
    for (i = 0; i < 2048; i = i + 1) begin
      next_random; running=random_state[0]; base_x=random_state[13:1]; base_y=random_state[25:14];
      next_random; h_total=random_state[12:0]; v_total=random_state[24:13];
      next_random; h_active=random_state[15:0]; v_active=random_state[31:16];
      check;
    end
    // X/Z predicate and comparison tests, including known-false gating.
    for (i = 0; i < 16; i = i + 1) begin
      running=1; base_x=0; base_y=0; h_total=1; v_total=1; h_active=1; v_active=1;
      case (i)
        0: running=1'bx;
        1: running=1'bz;
        2: h_total[0]=1'bx;
        3: h_total[0]=1'bz;
        4: v_total[0]=1'bx;
        5: v_total[0]=1'bz;
        6: base_x[0]=1'bx;
        7: base_x[0]=1'bz;
        8: base_y[0]=1'bx;
        9: base_y[0]=1'bz;
        10: h_active[0]=1'bx;
        11: h_active[0]=1'bz;
        12: v_active[0]=1'bx;
        13: v_active[0]=1'bz;
        14: begin running=0; base_x=13'bx; base_y=12'bz; end
        15: begin running=1'bx; h_active=0; v_active=0; end
      endcase
      check;
    end
    $display("PASS: %0d independent lane vectors; PPC4=0/1; all lanes; modular boundaries; X/Z", cases);
    $finish;
  end
  initial begin #10000; $fatal(1, "simulation timeout"); end
endmodule
