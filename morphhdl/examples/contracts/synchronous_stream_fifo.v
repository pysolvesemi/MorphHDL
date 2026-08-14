module SynchronousStreamFifo #(
  parameter integer DEPTH = 5,
  parameter integer WIDTH = 8
) (
  input  wire [0:0] clk,
  output reg [WIDTH-1:0] pop_data,
  input  wire [0:0] pop_ready,
  output reg [0:0] pop_valid,
  input  wire [WIDTH-1:0] push_data,
  output wire [0:0] push_ready,
  input  wire [0:0] push_valid,
  input  wire [0:0] reset
);

  function integer clog2;
    input integer value;
    input integer minimum_result;
    integer remaining;
    begin
      clog2 = 0;
      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
        clog2 = clog2 + 1;
      end
      if (clog2 < minimum_result) begin
        clog2 = minimum_result;
      end
    end
  endfunction

  localparam integer POINTER_WIDTH = clog2(DEPTH, 1);
  localparam integer OCCUPANCY_WIDTH = clog2(DEPTH + 1, 1);

  reg [WIDTH-1:0] memory [0:DEPTH-1];
  reg [POINTER_WIDTH-1:0] read_pointer;
  reg [POINTER_WIDTH-1:0] write_pointer;
  reg [OCCUPANCY_WIDTH-1:0] occupancy;
  wire push_fire;
  wire pop_fire;

  assign push_ready = occupancy < DEPTH;
  assign push_fire = push_valid && push_ready;
  assign pop_fire = pop_valid && pop_ready;

  always @(posedge clk) begin : p_fifo
    if (reset == 1'b1) begin
      read_pointer <= {POINTER_WIDTH{1'b0}};
      write_pointer <= {POINTER_WIDTH{1'b0}};
      occupancy <= {OCCUPANCY_WIDTH{1'b0}};
      pop_valid <= 1'b0;
    end else begin
      if (push_fire == 1'b1) begin
        memory[write_pointer] <= push_data;
        if (write_pointer == DEPTH - 1) begin
          write_pointer <= {POINTER_WIDTH{1'b0}};
        end else begin
          write_pointer <= write_pointer + 1'b1;
        end
      end
      if (pop_valid == 1'b0) begin
        if (occupancy > 0) begin
          pop_data <= memory[read_pointer];
          pop_valid <= 1'b1;
          if (read_pointer == DEPTH - 1) begin
            read_pointer <= {POINTER_WIDTH{1'b0}};
          end else begin
            read_pointer <= read_pointer + 1'b1;
          end
        end
      end else if (pop_fire == 1'b1) begin
        if (occupancy > 1) begin
          pop_data <= memory[read_pointer];
          pop_valid <= 1'b1;
          if (read_pointer == DEPTH - 1) begin
            read_pointer <= {POINTER_WIDTH{1'b0}};
          end else begin
            read_pointer <= read_pointer + 1'b1;
          end
        end else begin
          pop_valid <= 1'b0;
        end
      end
      if (push_fire != pop_fire) begin
        if (push_fire == 1'b1) begin
          occupancy <= occupancy + 1'b1;
        end else begin
          occupancy <= occupancy - 1'b1;
        end
      end
    end
  end

endmodule
