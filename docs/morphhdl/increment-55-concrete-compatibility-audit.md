# Increment 55 — concrete compatibility and approved-native-change audit

## Status

This document records the Increment 55 audit contract, compatibility repairs
and required merge evidence. It does not record a successful closure. Final
manifest counts and workflow results belong in the closure record near the end
of this document only after they have been observed from a clean, committed
revision.

Increment 55 is an audit and compatibility increment. It does not broaden the
typed library surface or migrate another native algorithm. Its scope is the
roadmap requirement to replace the old zero-diff check with an exact approved-
change manifest, prove that the remaining native delta is reviewed and narrow,
and run ordinary concrete compatibility alongside every inherited
parameterized gate. Increment 56 remains responsible for the native-looking
typed library-call surface.

## Selected upstream baseline

The sole native-source and public-compatibility baseline is the public
SpinalHDL `dev` commit recorded in `morphhdl/upstream-base.conf`:

| Property | Frozen value |
| --- | --- |
| Repository | `https://github.com/SpinalHDL/SpinalHDL.git` |
| Commit | `bec73bb9d2ff54897bee66d641b130b66d0db869` |
| Commit tree | `4dc89a4550737d0c24ea18916617627c52c4fea2` |
| Recorded commit date | `2026-08-06T09:23:46+02:00` |

The audit resolves that exact commit locally, verifies its complete tree, and
requires the configured baseline to be an ancestor of the checked MorphHDL
revision. A moving branch name, a later upstream tag target or the Increment 0
MorphHDL merge is not a substitute for this identity.

## Complete native production boundary

The manifest covers every production file below exactly these seven roots,
including non-Scala files should any be present:

| Order | Audited root |
| ---: | --- |
| 1 | `core/src/main` |
| 2 | `idslpayload/src/main` |
| 3 | `idslplugin/src/main` |
| 4 | `lib/src/main` |
| 5 | `scalaplugin/src/main` |
| 6 | `sim/src/main` |
| 7 | `tester/src/main` |

The ordered root set is fixed in the checker as well as the manifest. Editing
the JSON to omit an inconvenient module therefore fails with
`MORPH-NATIVE-AUDIT-ROOT-SET-MISMATCH`. The comparison uses Git's raw,
no-text-conversion, no-rename diff from the selected upstream commit to the
checked `HEAD`. Every native path in that diff must have one matching manifest
entry, and every manifest entry must still occur in the diff. All native files
outside those entries are consequently byte-identical to the selected
baseline.

The Increment 53g production-retirement guard remains conjunctive. Passing the
positive approved-change audit cannot restore native-`Int` reconstruction,
component recognition, witness inference or emitted-name recovery. The old
typed-overlay script path remains a compatibility entry point to the same
current audit; it is not an independent, weaker definition of the native
boundary.

## Exact approved-change manifest

`morphhdl/contracts/native-source-preservation.json` uses schema version 2.
The top-level record is closed: unknown or missing keys fail rather than being
ignored. It pins:

- repository identity, Git SHA-1 object format and SHA-256 content-hash format;
- the upstream configuration path and exact baseline commit/tree;
- all seven ordered source roots, with a baseline and approved tree object for
  each root;
- the complete, sorted approved-classification vocabulary; and
- a sorted, unique entry for every added, modified, removed or explicitly
  renamed native path.

Each file state records its normalized repository-relative path, Git mode,
blob object ID and independent SHA-256 of the blob bytes. Added files receive
whole-file approval. Removed files retain their baseline state and must be
absent at `HEAD`. A rename is represented by explicit baseline and approved
states rather than trusting rename similarity heuristics.

The closed file classifications are:

- `typed-signature`, `typed-overload` and `typed-support-file` for the reviewed
  parameter-sensitive API and its neutral carrier support;
- `mechanical-propagation` for metadata movement through the authoritative
  native algorithm; and
- `backend-isolation-hook` and `platform-integration-hook` for the already
  reviewed, narrow target-boundary hooks that cannot be expressed as a public
  overload.

There is no `unrelated`, `temporary`, `review-required` or wildcard class.
Classification is review metadata, not permission to change arbitrary bytes:
the byte contract below remains authoritative.

`morphhdl/contracts/increment-55-native-change-review.json` is the explicit
generation policy retained beside the derived manifest. It names every native
path, classification, introducing increment history, file reason and every
deterministic changed-span ID/kind/owner/reason plus an exact text assertion.
The generator refuses missing or extra paths, span-count drift, unknown
metadata or a dirty native worktree; it cannot infer an approval from a diff.

### Modified-file byte spans

A modified file contains one or more ordered pairs of half-open byte spans.
Each pair has a globally unique stable ID, one closed edit kind, an owner and
reason, and separate baseline/approved coordinates and SHA-256 values. The
checker proves all of the following:

1. both source blobs and modes match their recorded states;
2. every baseline and approved span lies within its blob, is ordered and does
   not overlap or move backward;
3. every byte before, between and after the reviewed spans is identical on the
   two sides;
4. both sides of every reviewed span match their own recorded SHA-256; and
5. required exact review text occurs the specified number of times on the
   specified side of the span (a signature/API token where the minimized span
   contains one).

The exact-text assertions make disappearance and accidental duplication of a
reviewed overload visible without using a Scala parser, and anchor mechanical
micro-spans to their reviewed tokens. The independent segment hashes prevent a
changed body from retaining approval merely because it still contains the
expected review text.

The manifest deliberately does not pin an approved commit. Such a field would
make a same-commit manifest update self-referential and would become unstable
under a merge commit. Instead, the seven approved root trees, per-file blobs,
SHA-256 values and byte spans seal the complete native state; the manifest
itself lives outside those native roots and is reviewed through the Increment
55 exact source-scope contract.

### Deterministic diagnostics and tamper checks

All guard failures use the stable `MORPH-NATIVE-AUDIT-` prefix. Important
failure families include:

| Boundary | Representative diagnostics |
| --- | --- |
| Manifest and baseline identity | `MANIFEST-MISSING`, `MANIFEST-INVALID`, `OBJECT-FORMAT-MISMATCH`, `BASE-COMMIT-MISMATCH`, `BASE-TREE-MISMATCH`, `UPSTREAM-CONFIG-MISMATCH` |
| Complete roots and inventory | `ROOT-SET-MISMATCH`, `BASE-ROOT-TREE-MISMATCH`, `APPROVED-ROOT-TREE-MISMATCH`, `UNAPPROVED-PATH`, `STALE-ENTRY`, `STATUS-MISMATCH` |
| File identity | `MODE-MISMATCH`, `BLOB-MISMATCH`, `CONTENT-HASH-MISMATCH` |
| Reviewed spans | `HUNK-ORDER`, `HUNK-RANGE-INVALID`, `HUNK-BASE-MISMATCH`, `HUNK-APPROVED-MISMATCH`, `UNAPPROVED-CONTENT`, `SIGNATURE-MISSING`, `SIGNATURE-DUPLICATE` |
| Working state and retirement | `DIRTY-WORKTREE`, `RETIREMENT-MISSING`, `RETIREMENT-FAILED` |

The isolated self-test exercises a valid modified file and whole-file addition,
then proves exact failures for root narrowing, a stale entry, an unapproved
path, a mutation outside a reviewed span, a mutation inside a reviewed span, a
changed added-file blob and a dirty native worktree. These tests demonstrate
the checker's tamper response; they do not replace review of a deliberate
manifest edit.

## Native public-compatibility cleanup

The audit found four public-surface changes that could not simply be placed on
an allow list. Increment 55 changes their ownership or supplies the missing
legacy descriptor.

### `SpinalConfig` remains the upstream case class

The MorphHDL-only `parameterizedVerilog` case-class element is removed.
Appending it had changed the constructor, companion `apply` and `copy`
descriptors, default getters and the `Product` arity of a widely used public
case class. Parameterized mode is now a MorphHDL-owned private marker carried
in a cloned copy of the existing upstream `SpinalConfig.flags` collection.
Enabling or disabling the marker does not mutate the caller's collection.

The unchanged source-compatibility fixture fixes the upstream 59-element
positional construction shape and also exercises default, named and partial
construction, `copy`, and the inherited `Product` surface. MorphHDL code reads
the marker through its runtime helper; ordinary SpinalHDL does not acquire a
new configuration field.

### Phase inventory is external observation

Native `SpinalReport`, `Phase`, `PhaseCheck`, `SpinalVerilogBoot` and
`Spinal.scala` no longer publish MorphHDL-specific validation-inventory state.
The native `SpinalVerilogPhasePlan` sidecar is removed, and the upstream phase
construction and final `PhaseContext.checkGlobalData()` behavior remain in
their baseline files.

`morphhdl.integration.ExternalSpinalVerilog` instead clones the baseline
`phasesInserters` collection, installs a final MorphHDL-owned observer and
records the phase class names from the actual successful native plan. It maps
the selected inherited validation classes to MorphHDL-owned stable IDs and
records the final global-data check only after native generation succeeds.
This retains validation-parity evidence without adding a native report field,
phase trait method or alternate phase planner.

### `BitVector.resize(ElabInt)` is additive and concrete

Adding an abstract typed resize method to the existing public `BitVector`
abstract class would break separately compiled third-party subclasses. The
typed overload therefore has a concrete default implementation. A concrete
`ElabInt` delegates to the existing authoritative `resize(Int)` method. A
symbolic width fails closed with
`SPINAL-ELAB-INT-BITVECTOR-RESIZE-SUBTYPE-UNSUPPORTED` unless the concrete
subclass supplies its parameter-sensitive override. The default never erases
a symbolic width to its witness.

### The legacy StreamFifo inner constructor remains linkable

The typed `StreamFifo.CounterUpDownFmax(ElabInt, ElabInt)` implementation keeps
its typed primary path, while a secondary `(BigInt, BigInt)` constructor
restores the upstream JVM descriptor and source call. The legacy constructor
converts both values with `ElabInt.fromBigInt` and delegates to the single typed
implementation. Because this is a non-static inner class, the binary check
also retains the enclosing `StreamFifo` instance in the constructor descriptor.

## Dual-Scala binary and source compatibility

`morphhdl/scripts/check-binary-compatibility.sh` constructs a detached baseline
worktree, builds the current checked-out worktree, packages `idslplugin`,
`core` and `lib`, and runs the same checks independently on Scala 2.12.18 and
2.13.12.

The dependency-free classfile checker compares every baseline public or
protected API class and linkable member. It rejects missing or narrowed
classes, fields, methods and constructors; changed descriptors, class kinds,
staticness, superclass or implemented interfaces; newly final or abstract
surface; and a newly added directly declared or inherited public/protected
abstract requirement on a pre-existing type. Only compiler-generated lambda
implementation bodies are excluded; synthetic trait helpers, outer accessors
and bridge methods remain contractual. Additive concrete API is allowed.
Diagnostics use stable `JVMABI_*` codes, and isolated generated-class fixtures
exercise each policy without requiring SBT or a Java compiler.

The external `LegacySourceCompatibilityFixture.scala` is copied unchanged and
compiled separately against both worktrees. In addition to the 59-element
`SpinalConfig` contract, it covers ordinary `Bits`/`UInt` resizing and
selection, the established Counter factories and constructor, concrete
StreamFifo constructors/companion call, and the legacy
`CounterUpDownFmax(BigInt, BigInt)` inner constructor. The fixture is then
executed against both builds to assert the exact 59-element `Product` arity,
first element and iterator length; compilation alone cannot hide a changed
case-class shape behind legacy constructor shims.

## Ordinary `SpinalVerilog` parity

`morphhdl/scripts/check-concrete-spinalverilog-parity.sh` copies one byte-
identical, concrete-only external client into detached upstream and current
worktrees. The fixture uses ordinary imported SpinalHDL APIs and invokes
`SpinalVerilog`; it does not enable MorphHDL's parameterized mode. Deterministic
configuration disables dates, live repository hashes, source comments and
other incidental output, and clones every mutable `SpinalConfig` collection
for each generation.

The complete relative-path and SHA-256 inventory of every generated file is
compared for these fixture families:

| Fixture family | Concrete coverage |
| --- | --- |
| `primitive-process` | `Bits`, `UInt`, `SInt`, resize/rotate/arithmetic, registers, `when`/`elsewhen`/`otherwise` and `switch` |
| `structure-hierarchy` | `Bundle`, `HardType`, `Vec`, `cloneOf`, structured assignment and repeated child components |
| `memory` | Depth-5 `Mem`, synchronous/asynchronous reads and enabled writes |
| `counter-stream-flow` | Concrete Counter, Stream translation/pipeline and Flow translation/stage |
| `counter-variants` | Inclusive bounded, down, bidirectional and free-running Counter factories, including boundary/completion outputs |
| `stream-width-adapter` | 24-to-8 and 8-to-24 native width adaptation with opposite fragment ordering |
| `stream-fifo-depth-*` | The same ordinary StreamFifo definition at depths 0, 1, 3, 5 and 8 |
| `stream-fifo-fmax-depth-5` | The concrete FMax tracker configuration and its inner up/down counters |
| `stream-fifo-async-*-bypass-depth-5` | Async-read bypass with both native RAM and Vec storage backends |

After source parity, the gate runs every external fixture and client class
compiled in the upstream worktree against the current production dependency
classpath and compares that output with the upstream output again. No current
test class participates in this executable leg. This adds an executable
linkage check to the static classfile and source-compilation checks.

## Inherited validation matrix

The canonical Increment 55 workflow uses the pinned
`ghcr.io/spinalhdl/docker:v1.2.0` image for compilation and tool-based gates.
Every Scala-dependent job is a non-fail-fast matrix over 2.12.18 and 2.13.12.
This table defines required evidence; it does not report a result.

| Gate | Required evidence |
| --- | --- |
| Approved native source and exact scope | Manifest/checker syntax and self-tests; exact real audit; Increment 53g retirement guard; compatibility shim; sorted, sealed Increment 55 branch inventory with no unexpected implementation path |
| Binary/source/concrete parity | `idslplugin`, `core` and `lib` JVM API comparison; unchanged legacy source fixture compiled on both sides; identical ordinary `SpinalVerilog` inventories; upstream-compiled client linked to the current runtime |
| SBT full | All production and test compilation, complete `morphir` and MorphHDL tests, native Counter/FIFO/adapter simulations, production packaging and retired-class JAR checks |
| Mill full | Independent `morphir`, MorphHDL and native Counter/FIFO/adapter suites through the Mill graph |
| Focused typed compatibility | Canonical IR, compiler ownership/equality safety, typed carriers/domains/projections, primitives, memory, Vec, Counter, Stream/Flow, FIFO, hierarchy, control/process and frontend bridges |
| Verilog-2001, lint, simulation and synthesis | Live inherited phase inventory, strict contracts and retained parameterized designs exercised through Verilator, Icarus and Yosys |
| Formal and mutation | StreamWidthAdapter, Vec, primitive closure and StreamFifo equivalence with live mutation controls, plus native Counter formal checks; proof status and counterexample workspaces uploaded even on failure |
| Determinism | Repeated MorphVerilog output and retained adapter, FIFO, enum-localization, single-source and concrete compatibility paths remain byte-identical where specified |
| Closure | Every named prerequisite job reports `success`; skipped, cancelled or merely planned evidence cannot close the increment |

Formal artifacts are retained for 14 days, and a missing artifact directory is
an error rather than a silent successful upload. The exact source-scope file
excludes only the roadmap checkbox file, whose unique Increment 55 line is
validated separately so closure can remain a final checkbox-only change.

## Reproduction

Run the native audit only from a clean worktree whose native changes are
committed, because its approved blobs and root trees are resolved at `HEAD` and
dirty native roots are rejected.

```bash
python3 morphhdl/scripts/check-native-source-preservation.py --self-test
python3 morphhdl/scripts/check-native-source-preservation.py
python3 morphhdl/scripts/check-typed-native-source-overlay.py --self-test
python3 morphhdl/scripts/check-production-retirement.py --self-test
python3 morphhdl/scripts/check-production-retirement.py
```

The compatibility checker has a tool-independent self-test. Full checks need
SBT plus the selected upstream commit in the local object database:

```bash
bash morphhdl/scripts/check-binary-compatibility.sh --self-test
bash morphhdl/scripts/check-binary-compatibility.sh --scala-version 2.12.18
bash morphhdl/scripts/check-binary-compatibility.sh --scala-version 2.13.12
bash morphhdl/scripts/check-concrete-spinalverilog-parity.sh --scala 2.12.18
bash morphhdl/scripts/check-concrete-spinalverilog-parity.sh --scala 2.13.12
```

When only an SBT launcher JAR is available, set `SBT_LAUNCH_JAR` for the
concrete parity script. The binary/source script accepts
`MORPHDL_SBT_COMMAND` and `MORPHDL_SBT_ARGS`; a normal `sbt` executable is the
canonical CI path. Tool-based simulation, synthesis and formal reproduction
should use the pinned workflow container because tool versions are part of the
evidence boundary.

## Limits of the claim

- The manifest proves an exact native-source delta against one selected
  upstream commit. It does not make a later upstream revision compatible; an
  upstream sync requires a new baseline and independent manifest review.
- A deliberate edit to both code and manifest can propose a different approved
  state. Cryptographic hashes make that proposal exact, not automatically
  trustworthy. Human review and the sealed Increment 55 source inventory remain
  part of the trust boundary.
- The classfile checker proves backward JVM linkage for the public/protected
  baseline surface it models. It does not prove behavioral equivalence, Scala
  implicit-resolution stability, serialization compatibility or every possible
  reflective use. The unchanged source and executable parity fixtures cover the
  high-risk Scala surfaces identified by this audit.
- Ordinary `SpinalVerilog` parity is complete for the declared generated
  fixture inventory, not for every construct in SpinalHDL. Parameterized
  behavior is covered separately by the inherited focused, tool, formal,
  mutation and determinism suites.
- Formal and finite-domain tests establish their declared designs, domains and
  tool configurations. They do not imply an unbounded proof for unrelated
  library components.
- Non-native implementation files are not byte-compared to upstream. They are
  constrained by the Increment 55 exact branch inventory, ordinary tests and
  the architecture/retirement guards appropriate to their owners.

## Closure record

The fixed contract values may be recorded now; observations remain pending
until the final committed revision is audited and every canonical workflow job
finishes.

| Evidence | Final observation |
| --- | --- |
| Baseline commit/tree | `bec73bb9d2ff54897bee66d641b130b66d0db869` / `4dc89a4550737d0c24ea18916617627c52c4fea2` |
| Audited native roots | 7 |
| Approved native path entries | 21 |
| Reviewed modified-file byte spans | 128 |
| Unchanged baseline native paths | 685 |
| Scala 2.12.18 compatibility and concrete parity | _Pending final gate_ |
| Scala 2.13.12 compatibility and concrete parity | _Pending final gate_ |
| Inherited SBT/Mill/tool/formal/mutation/determinism matrix | _Pending canonical workflow_ |
| Exact source-scope review | 41 implementation/audit paths; roadmap checkbox verified separately |

Only after these observations are filled, the exact source scope is sealed and
the canonical closure job succeeds may the roadmap checkbox be changed in a
separate closure step.
