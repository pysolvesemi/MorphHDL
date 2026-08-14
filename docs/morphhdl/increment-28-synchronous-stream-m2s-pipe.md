# Increment 28: synchronous Stream m2s pipeline stage

Increment 28 adds the first one-entry ready/valid pipeline primitive. It is an
atomic ParamRTL node matching the default `spinal.lib.Stream.m2sPipe()` policy,
without generalizing the bounded runtime model into arbitrary Stream graphs or
clocked statements.

## Public contract

The public fixture declares `WIDTH=8`, with a legal domain of `1..32`, and the
same flat eight-port Stream ABI as Increment 27: `clk`, `reset`, three push
signals and three pop signals. There is no depth, occupancy, flush or status
port. The stage holds at most one transaction.

The symbolic frontend exposes one atomic helper:

```scala
emitSynchronousStreamM2sPipe(
  label = "p_m2s_pipe",
  clock = ref("clk"),
  reset = ref("reset"),
  pushValid = ref("push_valid"),
  pushReady = ref("push_ready"),
  pushData = ref("push_data"),
  popValid = ref("pop_valid"),
  popReady = ref("pop_ready"),
  popData = ref("pop_data"),
  elementType = elementType
)
```

All eight references are direct, guarded and pairwise distinct. Clock, reset
and ready/valid controls are exact unsigned one-bit ports. Push and pop data
exactly match one owned positive packed element type. Arguments, roles,
ownership and the complete item are validated transactionally before
publication. Sibling or nested structural/runtime items fail closed.

## Target-neutral semantics

`ModuleItem.SynchronousStreamM2sPipe` fixes one policy:

- one positive-edge clock and active-high synchronous reset;
- one registered ready/valid stage with capacity one;
- `push_ready = pop_ready || !pop_valid`;
- one-edge input-to-output latency with no combinational valid or payload
  bypass;
- full and stalled rejects input and holds both output valid and payload;
- full pop plus valid push replaces the resident transaction on the same edge,
  with no invalid bubble and one transfer per cycle under sustained traffic;
- when `push_ready` is high and `push_valid` is low, `pop_valid` clears and
  invalid payload may update;
- reset clears `pop_valid` only.

Payload capture follows the default Spinal `holdPayload=false` policy: whenever
`push_ready` is high, `pop_data` captures `push_data`, independently of
`push_valid` and even on an edge where synchronous reset clears `pop_valid`.
Payload is meaningful only while `pop_valid` is high. Reset does not initialize
or promise a value for invalid payload.

Transfers are contractually meaningful only while reset is low. No fall-through
payload/valid path, selectable capacity, flush, status, packet policy,
initialization, asynchronous reset or second clock is implied.

## Concrete library witness

The default concrete witness builds an eight-bit `spinal.lib.Stream`, applies
the pinned default `m2sPipe()` transformation, and exposes its six handshake
signals through the reviewed flat ABI. The fixture generator selects an
active-high synchronous default clock-domain configuration. Generation proves
exactly eight public ports and zero child modules.

The pinned implementation uses an initialized valid register and an
uninitialized payload register, both enabled by upstream ready. Its ready
equation admits bubble-free full replacement. The concrete witness proves the
default shape and inherited Spinal validation; the parameterized Verilog
testbench remains authoritative for non-default widths.

## Strict Verilog-2001 lowering

The backend emits one continuous ready equation and one named process:

```verilog
assign push_ready = pop_ready || !pop_valid;

always @(posedge clk) begin : p_m2s_pipe
  if (reset == 1'b1) begin
    pop_valid <= 1'b0;
  end else if (push_ready == 1'b1) begin
    pop_valid <= push_valid;
  end
  if (push_ready == 1'b1) begin
    pop_data <= push_data;
  end
end
```

There is no helper function, local parameter, memory, pointer, occupancy,
initial block or internal status signal. The valid and payload assignments are
deliberately separate: moving payload capture beneath the reset `else`, gating
it with valid/fire, or resetting it would change the pinned primitive's
contract.

## Public executable contract

`synchronous_stream_m2s_pipe.v` is the twentieth reviewed artifact. Normal and
reverse-construction fixture generation must be byte-identical and match the
golden. Its strict Verilog-2001 testbench instantiates default eight-bit,
minimum one-bit and awkward five-bit widths simultaneously. It proves
synchronous reset, no combinational bypass, one-edge capture, full stall hold,
two consecutive bubble-free replacements, pop without replacement, refill and
payload capture on a reset edge.

Verilator parses and lints widths 1, 5, 8 and 32. Icarus runs the behavioral
testbench. Yosys requires exactly one active-high synchronously reset enabled
valid register, one unreset enabled payload register, a valid inverter and a
ready OR gate, with exact bit-for-bit port connections at every tested width.
Source and JSON mutations reject wrong ready equations, lost replacement,
valid/payload gating drift, payload reset, reset/clock changes, disconnected
data and extra or misconnected state.

## Validation parity

The concrete witness continues to execute every inherited validation phase.
ParamRTL separately proves exact role/type agreement, sole output ownership,
one shared clock, valid-only reset and complete capture/hold/replacement
behavior. Because the node owns the entire state policy, incomplete sequential
coverage and combinational feedback cannot be introduced through sibling
items.

## Deferred by design

This increment does not add `s2mPipe`, `halfPipe`, `stage`, `queue`, `Flow`
conversion, fall-through FIFO storage, composition of Stream adapters,
selectable `collapsBubble`/`holdPayload`, flush, status, initialization,
asynchronous reset or CDC support.

## Recommended next increment

Increment 29 should add the atomic default `SynchronousStreamS2mPipe`: a
zero-latency output bypass with one-entry skid storage that cuts the ready path.
It is the natural dual of this m2s stage and should freeze stall capture,
same-cycle bypass, buffered replay and active-high synchronous valid-only reset
before attempting a composed full pipeline stage.
