# WA-07b resume checkpoint — 2026-09-07

**IN PROGRESS: not final qualification, completion or merge evidence.**

PR #173 continues on `agent/wa-07b-boolean-ternary`, targeting
`parameterized-verilog`. WA-07b remains unchecked; WA-08 remains blocked.
Production publication/writeback is still WA-08 scope.

## Repairs and source audit

- `7ee8209e6d1b36481c35f38962e315f7f6e17c86` fixes the unit suite's
  `value(...)` name collision with an inherited ScalaTest matcher. The imported
  canonical RHS accessor is now explicitly named `rhsValue`; the earlier
  `not` to `logicalNot` disambiguation remains. This was a real assertion bug,
  not a change to the expected RTL semantics.
- `7d6bbca6bac04f9dc27f5a927c9644ddefbe53d4` registers seven new proof/source
  files and refreshes ten changed registered entries. Existing unchanged
  proof-code hashes are retained. The registry contains 63 entries; validation
  still compares actual bytes to the checked-in registry and never updates it.
- `38deaa42c3fe542cb672ccafd0a993782f8c0a90` fixes the native generic fixture's
  module naming. `setDefinitionName(name)` resolved the inherited `Component`
  field rather than the enclosing local value and emitted `module toplevel`.
  The local is now `witnessDefinitionName`. The reviewed registry update for
  this repair uses native-source SHA-256
  `a7ff7af033b8d69e3dc8bdc4d0a0376e05e832efbdce55978ad53388e158f194`.

Changed registered files and new files were reconstructed locally only after
matching their Git blob hashes to the PR's source. This audit does not claim
that an older retained repository archive is a complete current-head checkout.

## Supplemental checks actually executed

Scala 2.13.12 compiled the current canonical IR and pass sources. The three
focused suites completed **25 tests: 25 succeeded, 0 failed**, with no canceled,
ignored or pending tests:

- `BooleanTernarySimplificationPassSpec`;
- `WireAssignmentAllPassPipelineSpec`;
- `AllPassConfigurationSpec`.

The available local ScalaTest runtime was **3.2.14**, not CI's pinned 3.2.18.
These checks therefore supplement, but do not replace, either required SBT/CI
lane. The unit-run log SHA-256 is
`b212ec98beaeaf5d5dad087a287631a86faf0a91f273d74d14107bd979ed8cdb`.

The new four-state oracle suite compiled successfully, but its simulator and
SAT tests were **not executed locally**. The current WA-06, WA-07 and WA-07b
static-guard self-tests passed, including dependency and genericity mutations.
The WA-07b shell runner passed `bash -n`; its guard passed Python compilation.
These are not substitutes for the complete final-head boundary job.

## Actual supplemental native emission

The fixed native witness was compiled from the current pass/bridge sources and
executed with a retained native backend runtime from commit
`39977b32cc47c54a0481716c181069184a32fbe5`. This runtime is **not** the PR's final
production checkout. The following are actual emitted lines from this local
smoke test, not hand-authored claims of final-head generation.

Source excerpt from `BooleanTernaryGenericNativeWitness`:

```scala
val a = in Bool()
val b = in Bool()
val y0, y1, y2, y3 = out Bool()
y0 := Mux(a === b, True, False)
y1 := Mux(a =/= b, False, True)
y2 := Mux(a, True, False)
y3 := Mux(a, False, True)
```

Before all passes:

```verilog
assign _zz_y0 = ((a == b) ? 1'b1 : 1'b0);
assign _zz_y1 = ((a != b) ? 1'b0 : 1'b1);
assign _zz_y2 = (a ? 1'b1 : 1'b0);
assign _zz_y3 = (a ? 1'b0 : 1'b1);
```

After standalone WA-07b; the all-five candidate has the same four lines:

```verilog
assign _zz_y0 = (a == b);
assign _zz_y1 = (! (a != b));
assign _zz_y2 = (! (! a));
assign _zz_y3 = (! a);
```

The complete eight-output generic fixture reports **five positive and four
inverse rewrites**, nine changed assignments, two rounds, and zero procedural
receiver rewrites in each candidate. The extra assignments are internal
expression temporaries, not additional public outputs. Both candidates and the
reference were generated twice; their Verilog and JSON reports were
byte-identical on repeat. The expected reference/candidate/all module names
were present after the naming repair.

Emitted-file SHA-256 values:

| Mode | SHA-256 |
| --- | --- |
| Reference | `301ae559b3814a7fdb3c887db48a3a8db1e60f22ca1653fd32afd4ab42f3bd0b` |
| Standalone ternary | `83c146a97622e26dae36f17551b2cda279a0587caeb4b7a83405b20d7138d9b9` |
| All five | `05bca15e42088f2b59fb4d8df999cdfb594ea3e1977c3341a9ea96c8b2c33cb3` |

## Remaining completion gates

Require successful final-head static/signature checks and Scala 2.12.18 and
2.13.12 CI with the declared dependencies. Then require exact-final-head native
emission, four-state simulation and mutations, strict Verilog-2001 compile/lint/
synthesis, all 16 formal shards and their exact complete-domain aggregation,
and repeated-run evidence. Both new candidates and all historical proof legs
must still compare against the unchanged snapshot before the entire passes
phase over all 512 admitted WIDTH/DEPTH bindings. Local emission or a no-op FIFO
candidate cannot replace these proofs.

No successful full-domain formal qualification is recorded by this checkpoint.
Review the final emitted RTL and retained proof evidence before the completion
checkbox change and merge. See [implementation notes](wa07b-implementation-notes.md)
for the unchanged semantic contract and commands.
