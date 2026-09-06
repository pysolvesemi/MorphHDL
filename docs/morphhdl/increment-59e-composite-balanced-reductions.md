# Increment 59e — Composite and nested Bundle/Vec reductions

## Status

Implementation and qualification are in progress on
`agent/increment-59e-composite-reductions`, based on merged
`parameterized-verilog` commit `d3a0f112ce3cab9f074e5a7cbbc165c9878ff40a`.
The controlling roadmap checkbox remains unchecked until the final source and
all applicable gates pass. This document does not claim a completed increment
or a merge.

## Native source contract

The ordinary `Vec(...).reduceBalancedTree(op, levelBridge)` source and the
existing native reduction algorithm remain authoritative. Adjacent elements
are paired in their original order. An odd tail bypasses the operation and
passes through its native level bridge. A singleton bypasses both callbacks.
No neutral element, padding operand or reassociation is introduced.

Composite callbacks retain the full record. A key comparison used to select a
record selects its tag, coordinates and payload together; separate output
fields do not imply separate selections. Cross-field modular add/subtract is
qualified against the native topology, including non-associative expressions.

## Capture and replay

`TypedBalancedReductionCompositeReplay` validates a recursive Bundle/Vec shape
before transferring any scalar leaf evidence. It retains field paths, native
leaf kinds, exact independent width functions, container ownership, nested
dimensions and complete assignments. Closed-graph observations freeze mutable
expression children and drivers before another callback can run.

Each admitted expression has a transfer rule and a constructor for its exact
native expression class. Replay builds a fresh native DAG from the certified
operand-leaf positions. Shared comparisons remain shared dependencies of the
whole record. It does not rerun the Scala callback or emit arithmetic Verilog.

The existing scalar identity/register bridge certificate is applied to each
complete leaf path. All leaves must advance together through equal register
counts and one exact clock domain. Native registers continue to define reset,
enable and initialization behavior. Missing fields, foreign reads or writes,
partial drivers, cycles, changed widths and unsupported effects reject.

Callback bytecode admission remains separate from graph admission. Native
clone, mux, access and assignment calls receive only the minimal audited
composite extension. User Bundle constructors, accessors and construction
hooks require explicit inspection; a successful sampled graph does not prove
that arbitrary host code is safe.

The initial construction profile admits final ordinary Bundle classes with
immutable `Int`, `ElabInt` or `HdlInt` shape parameters. Arbitrary object, Data or
Function0 constructor parameters, custom construction hooks, extra callback
traits and opaque HardType factories reject. Locally constructed native Vec
generators are recursively audited. These restrictions apply to symbolic
reduction admission; they do not change ordinary concrete callback execution.

## Packed transport and native propagation

The shared publication backend handles scalar or composite Data templates.
It reconnects exact native leaf anchors to factorized packed stage slices;
the native emitter still supplies every operator and clocked process.

`ParameterizedVecElementLayout` retains a recursive layout beside the finite
native carrier. Independent nested counts multiply the logical packed width;
they do not become a fixed list of numbered public ports. Native flat leaves
retain separate logical offsets and presence conditions. Inactive carrier
leaves do not occupy bits in the published logical record.

This increment uses the legacy packed Vec interface. Named field-vector
interfaces belong to 59c, changing stage widths to 59d, and combined feature
qualification to 59i. Those siblings are not dependencies of 59e.

The native `cloneOf` boundary propagates existing typed metadata, while a
native scalar mux retains width metadata only when all participating operands
prove the same exact width function. Concrete APIs and native width selection
remain authoritative. The approved native-change manifest records these
mechanical edits.

## Qualification contract

The Scala suites cover positive recursive graphs and rejection controls. The
tool harness independently elaborates ordinary native references for every
specialization. One parameterized candidate per static topology is generated
at COUNT=1 and reused for larger overrides. Independent input fields and Vecs
prevent swapped-record wiring from being hidden by identical stimuli.

Required evidence includes both supported Scala versions, deterministic A/B
generation, exact port and leaf widths, strict Verilog-2001 parsing, Icarus
simulation, Verilator lint, full Yosys synthesis and native-reference formal
equivalence. Real mutations must produce observable counterexamples for leaf
swaps, corrupted tags and cross-field wiring. Errors, missing tools, timeouts,
UNKNOWN and skipped cases do not qualify as proofs.

The minimum scalar geometry is WIDTH in `{1,5,8,32}` and COUNT in
`{1,2,3,5,8,9,16,17}`, extended by independent field widths and nested dimensions.
These finite specialization proofs are not universal formal quantification
over all parameter values. Domain validity and symbolic shape transfer have
their own implementation checks.

Final executed results and source-bound CI references will be recorded after
qualification.
