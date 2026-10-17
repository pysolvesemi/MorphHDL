# Native reduction wiring (LWR-25)

This increment simplifies authenticated native wiring. It does not change
`BalancedTree`'s adjacent-pair ordering, odd-tail bypass, width contract, bridges,
pipeline mask, clocks or latency. No application Scala or generated delivery RTL
is rewritten. The component still has one recursive next-level instance.

For example:

```scala
val sum = spinal.lib.BalancedTree.defineWidth(
  (width: ElabInt) => UInt(width bits), 8, 64, "WordSum"
)((left: UInt, right: UInt) => left + right)
val tree = sum.component(ElabInt.literal(16), payloadWidth = ElabInt.literal(36))
```

Each operation returns the declared payload width. Unsigned addition wraps modulo
`2^DATA_WIDTH`; extending sixteen 32-bit terms to 36 bits before supplying them
allows their sum without overflow. This change does not automatically widen terms
or alter an operator's arithmetic contract.

The generated combinational body has this structure (formatting shortened):

```verilog
if (COUNT == 1) begin : g_GEN_1_TRUE
  assign result = terms[0 +: DATA_WIDTH];
end else begin : g_GEN_1_FALSE
  wire [DATA_WIDTH-1:0] combined [0:((COUNT+1)/2)-1];
  wire [DATA_WIDTH*((COUNT+1)/2)-1:0] combined_packed_view;
  WordSum #(.COUNT((COUNT+1)/2), .DATA_WIDTH(DATA_WIDTH)) nextLevel (
    .clk(clk), .reset(reset), .softReset(softReset), .enable(enable),
    .terms(combined_packed_view), .result(result)
  );
  for (i=0; i<(COUNT+1)/2; i=i+1) begin : g_GEN_2
    assign combined_packed_view[i*DATA_WIDTH +: DATA_WIDTH] = combined[i];
    if (2*i+1 < COUNT) begin : g_GEN_3_TRUE
      assign combined[i] = terms[(2*i)*DATA_WIDTH +: DATA_WIDTH]
                         + terms[(2*i+1)*DATA_WIDTH +: DATA_WIDTH];
    end else begin : g_GEN_3_FALSE
      assign combined[i] = terms[(2*i)*DATA_WIDTH +: DATA_WIDTH];
    end
  end
end
```

## Proof boundaries

* Whole symbolic net-array reads use their packed view only at proven zero offset
  and exactly matching total geometry. Register reads retain the direct-memory
  helper when blocking writes can affect same-activation ordering. Partial,
  nonzero-offset and dynamic reads retain their existing lowering; surviving
  helpers keep the packed sensitivity dependency.
* Flat branch assignment families become continuous only after the existing
  ownership pass proves all exact native drivers, one complete independent write
  per target per branch, compatible net declarations and no target dependencies.
  Runtime priority and non-flat processes remain procedural.
* Child forwarding retirement requires exact assignment and child identities,
  identical types and authenticated width expressions, two complete alternatives,
  no other drivers or consumers, and unique native declaration/connection edges.
  Kept or documented boundaries and width/sign conversions remain intact.
* Packing joins a retained loop only when the array declaration has that exact
  lexical owner, its depth matches the loop count and its Vec/index token is
  retained. Nested array dimensions, ambiguous loops and other unproved cases
  keep separate packing. Register and bridge stages are not moved.
* Affine `+ 0` elimination follows selector/domain authentication. It does not
  simplify arbitrary hardware arithmetic. Conditional default labels use one
  shared ordinal for `_TRUE` and `_FALSE`, coordinated with loop numbering.
  Explicit names and declaration collisions remain reserved. Internal identities
  and diagnostics retain their original source locations.

Generated hierarchy names for automatically labelled conditionals intentionally
change. Consumers of those hierarchy paths must update their references; explicit
scope names remain stable. `_zz` simplification is outside this increment.

## Focused validation

Scala 2.12.18 and 2.13.12 each pass 111 tests (222 total). All 380 HDL-tool
invocations pass: Verilog-2001 compilation/simulation, lint and synthesis/checks.
The [unmodified emitted example](reduction-wiring-example.v) is a sum fixture
with source COUNT=1 and independently overridable DATA_WIDTH.

See [the evidence receipt](reduction-wiring.json) for exact tested source hashes, commands, tool logs,
artifact hashes and final results. Tests cover sum, OR and non-associative
subtraction, count/width overrides, changing inputs with no clocks, unknowns,
unsigned wraparound, procedural blocking-read ordering, partial/dynamic reads,
retained attributes/comments, priority and signed width conversion, deterministic
labels, recursion/ownership rejection and existing bridge/pipeline behavior.

Questa is unavailable. No CI, full qualification, application-wide simulation or
new formal-equivalence claim is made. The roadmap explicitly requests focused
local checks for this increment.

Direct resizing of a symbolic signed child output remains a pre-existing rejected
form; materialize a native signed carrier before resizing. The signed-width
regression uses that supported path and verifies sign extension across overrides.
