# WA-10: general typed expression inlining

This repair extends the existing default-enabled six-stage wire-assignment
pipeline and its structured Verilog emitter. It adds no application switch,
signal-name convention, HDL parser or text postprocessor. Boolean/integer
parameter normalization remains separate: `PPC4` retains the existing native
symbolic expression and is compiled at both supported Boolean bindings.

## Executed baseline

The baseline compiler is `086cb4c642d0182e33bd86fd391f46fc7c0eb2a8`, the
`parameterized-verilog` head before this repair. Both diagnostic sources were
compiled unchanged by the checked-out typed API through production
`MorphVerilog`; neither needed a concrete `SpinalVerilog` substitute.

```sh
sbt "morph/Test/runMain GenerateTimingExpressionExample /tmp/timing-expression-repro"
sbt "morph/Test/runMain TimingExpressionArtifactWriter target/wa10-evidence/baseline/timing"
sbt "morph/Test/runMain morphhdl.examples.GeneralExpressionInliningArtifactWriter target/wa10-evidence/baseline/general"
```

The artifact writers run the same source in default, explicitly enabled and
explicitly disabled modes, twice. The baseline's six repeat comparisons passed;
default and explicit enable were byte-identical for both fixtures. The retained
files and SHA-256 identities are in [`evidence/wa10/baseline`](evidence/wa10/baseline).

The reduced fixture corrects part of the suspected diagnosis: its comparison
constants **already inline**. Its enabled output contains two subtraction
carriers and five carriers around the conditional register-update sum:

```verilog
assign _zz_io_finalGroup = (io_hTotal - 13'h0001);
assign _zz_vSyncEnd = (_zz_vSyncEnd_1 + _zz_vSyncEnd_4);
assign _zz_vSyncEnd_4 = {2'd0, io_vSync};
```

The separate `GeneralExpressionInlining` source uses `@dontName` declarations
with two receiver contexts to preserve real unnamed candidates through earlier
single-use cleanup. Its enabled baseline reproduces **all three** patterns:

```verilog
assign _zz_constantOK = 16'h0001;
assign _zz_extensionOK = {2'd0, word};
assign _zz_subtractionOK = (total - 13'h0001);
```

These names describe inspected output only. Pass eligibility and substitution
use native expression/symbol identities and retained naming provenance.

## Boundaries being repaired

| Boundary | Baseline behavior | WA-10 treatment |
| --- | --- | --- |
| Native unnamed expression bridge | Rejects non-direct receivers before canonical substitution; historical bridge also uses a separate capture path. | Share the lossless typed expression engine while retaining distinct unnamed/named pass identities and provenance ownership. |
| Native named expression bridge | Requires the whole receiver RHS to be the candidate and the receiver to supply the same packed boundary. | Prove fixed-width nested RHS uses and preserve an assignment or explicit native sizing fence at each replacement; retain the stricter symbolic-boundary proof. |
| Native codec | Rejects every `Resize`; operator capture lacks explicit result-width fences. | Capture proven fixed resizes and authoritative per-node width/signedness, retaining unsupported symbolic cases. |
| Constant-operand bridge | Accepts Boolean targets and a bounded Boolean algebra. | Remains a separate simplification stage. Literal carrier elimination belongs to expression inlining and does not require expanding this algebra. |
| Final emitter | `fillExpressionToWrap` reintroduces carriers after the native passes; suppression is limited to homogeneous unsigned additions in whole combinational assignments. `cutLongExpressions` can subsequently add literal leaves as depth-frontier carriers. | Plan wrapper suppression using typed receiver contexts across supported comparisons, arithmetic, muxes and procedural RHS expressions. Preserve required depth fences; suppress only newly depth-wrapped literal leaves already proved eligible. |

The native observer runs at the production phase boundary and records each
candidate's actual naming provenance, `typeNode` flag, packed facts and pass
result. Its output is retained under the baseline/final `diagnostics` directories.
The reduced timing fixture has **no native unnamed candidates**: its reflected
`proposedHTotal` is a type node and stays under the existing type-node exclusion.
Its subtraction and sync-extension carriers first appear during emission.
The dedicated fixture's three real unnamed candidates instead hit the baseline
`NON-DIRECT-RECEIVER` rejection. The fixed native run removes nine candidates
with fourteen substitutions, including one conditional register RHS.

## Semantic boundaries

Parentheses are not a Verilog sizing fence. A same-width unsigned subtraction
can inline into a same-width comparison, but the same expression under a wider
receiver must retain its modular domain. A widening concatenation can provide
that domain because its operands are self-determined. A narrowing resize or
slice can require a named select base in strict Verilog-2001; that carrier stays
even when redundant arithmetic/extension carriers inside it disappear.

Native replacement copies expression nodes, retains signal/parameter identity,
and removes the declaration and continuous driver only after checking that no
references remain. Register declarations and procedural driver kinds/scopes
are preserved. The generic canonical entry point still rejects procedural
receivers because canonical IR does not encode blocking/nonblocking timing.
The native adapter supplies private evidence tied by identity to the exact
captured driver for an existing Spinal nonblocking register RHS. Evidence from
another snapshot, procedural combinational assignments and unrepresented output
registers fail closed. Ports, hierarchy, preserved identities and metadata remain subject
to the existing safety checks. Shared uses and expression expansion are bounded.

Native expansion is limited to 32 reference occurrences, 64 source-expression
nodes and 256 projected nodes; each copied/planned tree also has a 256-node
budget. Operators are copied freshly per expression edge and receiver, while
real signal/parameter identities remain shared. A final reference scan precedes
declaration/driver removal. The emitter conservatively retains shared synthetic
nodes, annotated nodes, unsupported operators and symbolic boundaries.

Fixed, disjoint in-range bit/range assignment targets can use the same typed
RHS plan. Whole/partial overlaps, dynamic targets and tagged or symbolic target
boundaries retain their wrappers. Boolean operands and mux conditions are
self-determined, so a proven Boolean subtree may inline even when an unsupported
sibling stays wrapped; arithmetic and mux value-arm proofs remain transactional.

Remaining limits are deliberate: nested signed nonliteral aliases retain the
`WA10-NATIVE-SIGNED-RECEIVER-BOUNDARY` fence; symbolic resize and distinct
parameter identities cannot be justified from default widths. Native arithmetic
signed right shifts and case-equality nodes are rejected rather than captured
as different operators. Native uses as selections or control predicates,
unknown/tagged expression subclasses, multiple drivers and unproved scheduling
remain outside this extension. Signed/mixed arithmetic, slices, protected/vital
signals and these negative cases are covered by retained-control tests.

## Executed final output and checks

The final dedicated fixture emits the eligible expressions directly:

```verilog
assign constantOK = (16'h0001 <= word);
assign constantMux = (choose ? 16'h0001 : other);
assign extensionSum = ({2'd0, word} + {2'd0, other});
assign extensionOK = ({2'd0, word} <= 18'h0ffff);
assign subtractionOK = (index == (total - 13'h0001));
assign subtractionMux = (choose ? (total - 13'h0001) : index);
```

The reduced final module retains one 18-bit sync-sum carrier as the legal
Verilog-2001 base for the eventual `[11:0]` truncation. The arithmetic inside that
carrier is inlined. The reflected total and the register itself also remain.
In the dedicated fixture, the signed subtraction, kept/vital declarations and
necessary select/sizing bases remain with the reasons described above.

| Retained dedicated-fixture carriers | Actual reason |
| --- | --- |
| `_zz_signedOK` | Native signed subtraction shared by a signed comparison and sign extension; explicit signed-receiver boundary rejection. |
| `_zz_mixedSigned`, `_zz_mixedSigned_1` | Signed extension and unsigned-to-signed transport under the existing declaration/cast printer. Inline signed-cast forms are outside the unsigned policy. |
| `_zz_mixedSigned_2` | Conservative extra zero-extension wrapper inside that unsupported signed target cone; not claimed to be intrinsically necessary. |
| `_zz_sliced`, `_zz_sliced_1` | Cast/select base and underlying subtraction under a ranged-access root. One legal select base is needed; the two-wrapper chain is conservative because this root is outside the typed emitter plan. |
| `_zz_truncated`, `_zz_state` | 18-bit addition bases for `[4:0]` and procedural `[11:0]` respectively; their inner extension wrappers disappear. |
| `protectedDifference`, `vitalDifference` | Existing preservation metadata blocks declaration removal. |

Names in this evidence table identify inspected output; no compiler rule tests
their spelling.

The focused suites passed **27 core tests** (25 emitter, two copier) and
**14 MorphHDL tests** (11 public integration, three codec/bridge) on both Scala versions. The canonical suite
passed **160 tests on each of Scala 2.12.18 and 2.13.12**. See
[`verification/wa10-regression-commands.md`](verification/wa10-regression-commands.md)
for runnable commands and artifact layout.

The final public-artifact validator passed 12 strict Verilog-2001 Icarus
simulations, covering 9,792 samples; four Yosys sequential equivalence proofs;
and four rejected deliberately wrong underflow oracles. Both `PPC4` bindings
compile from the same symbolic artifact. Default/explicit enable, repeated
generation, mode interfaces and exact disabled legacy bytes match. Tests cover
zero/max values, every eight-bit input, boundaries, overflow/underflow,
truncation, mixed signedness, multiple uses, conditional state updates and
explicit X/Z witnesses. Formal checks prove the recorded two-state domains;
the four-state witnesses are simulations, not an exhaustive four-state proof.
The retained proof scripts/logs and validation report are under
[`evidence/wa10/final`](evidence/wa10/final); `manifest.json` binds the evidence.

The production artifact validator also passed on Scala 2.13.12, and all six
fixture/mode artifacts are byte-identical between the two Scala versions.
An initial Scala 2.13 compile caught the immutable `Seq` alias at the emitter's
buffer-publishing helper; using explicit `scala.collection.Seq` fixed it, and
the complete focused/build/artifact checks above were rerun successfully.
The failed first attempt is retained alongside the successful rerun logs.

The unchanged production timing module was also available and regenerated.
All three exact reported patterns disappear; eight strict Verilog-2001
compilations and 2,136-cycle traces match across pristine/final enabled/disabled
artifacts at both `PPC4` bindings. Repeated/observed generation is identical,
and disabled output is byte-identical to pristine. Final output retains 32
diagnostic carriers: 24 in lane vectors carrying `noBackendCombMerge` metadata,
four required sync truncation bases, two aliases rejected by the existing
conditional-use rule and two mux carriers under multiply assigned register
targets. These are documented as conservative safeguards where appropriate,
not all as mathematically unavoidable. The public-safe
[`production summary`](evidence/wa10/final/production-summary.md) records source
identities, exact commands, hashes and every retained class. The standalone
regression has no dependency on the private application source.

Exact-source workflow gates are pending at this source revision. WA-10 remains
open until those gates pass.

## CI follow-up: inherited symbolic Stream shape

The reported source-overlay run
[`34684811098`](https://github.com/pysolvesemi/MorphHDL/actions/runs/34684811098)
checked source commit `1163ad3a8f6af4243a029f49dbd19f8f6aa7d66c` before its
updated source-review seal. It rejected the stale hash for
`NamedWireExpressionNativeBridge.scala`. Seal commit `43169985ec6886441c95d47d35c8ad4fd866e4c8`
resolved that mismatch; its corresponding
[`34684853063`](https://github.com/pysolvesemi/MorphHDL/actions/runs/34684853063)
passed the source audit/mutations, both production Scala lanes and cross-Scala
comparison.

The later full SBT and Mill Scala 2.13 runs exposed one common obsolete shape
assertion in `TypedPrimitiveClosureTests`: the test required
`stream_s2m_payload`, an unprotected `s2mPipe` mux consumed as the whole RHS of
the next payload register. WA-10 legitimately removes this combinational alias
under the existing identical symbolic-width/parameter-identity proof. The
`WIDTH`-wide register, asynchronous reset and guarded nonblocking assignment
remain. No further compiler change is needed.

The existing test now checks both optimization modes, retaining all previous
port/payload/resize/slice checks and adding exact register, reset, guard and mux
checks. Disabled mode must retain the old alias and register source; enabled
mode must inline the mux into the same guarded register update. The suite still
contains exactly 28 tests, preserving its inherited inventory contract. The
unchanged formal pipeline suite remains a required CI gate; structural tests
are not described as formal equivalence. Commands and executed results are
recorded in the [CI follow-up evidence](evidence/wa10/ci-followup/README.md).

## Inherited runtime compatibility

An additional inherited WA-09 native witness caught a redundant resize inserted
at a direct whole-RHS receiver when using the historical emitter. Its four-state
simulation and miter proof passed, but its required direct XOR assignment gained
an avoidable wrapper. The bridge now recognizes when the exact receiver already
supplies the removed packed boundary. It omits only that redundant fence;
nested/differently typed uses retain explicit fences. A dedicated legacy-emitter
regression and the unchanged inherited emitted-assignment assertion pass.

The complete inherited WA-08 production writer also exposed an obsolete
retention assertion: the unprotected combinational `sampledAlias` is now eligible
for substitution into the authenticated nonblocking `sampled` register RHS.
The successor assertion requires that alias to disappear in enabled output,
requires its legacy disabled identity, and independently retains the actual
register, nonblocking update and registered output. The existing semantic proof
and genuine metadata/control/hierarchy preservation checks remain mandatory.
The complete 31-artifact production writer (with repeated generation) and
successor checker passed **6,672 four-state cases, 51 formal equivalence cases
and four negative mutations**. The original SBT launch initially failed locally
loading `spinal.lib.slave$` because a generated Scala 2.12 library JAR was
truncated. The same plugin-compiled writer first ran successfully through
production MorphVerilog using the exact exported SBT Test classpath with Java.
After rebuilding only that corrupt generated JAR, the standard SBT writer and
complete qualification also passed. Both attempts, the diagnosed local cause
and the successful standard commands are recorded.
