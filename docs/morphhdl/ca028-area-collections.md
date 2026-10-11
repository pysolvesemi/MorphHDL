# CA-028: retained Area collection branches

The frontend now captures zero-based `for ... yield new Area` collections with
index-dependent equality branches. Publication retains a finite generate loop,
independent state and child instances, nested generate alternatives, packed
slices and index values, and statically exported common-scope members. Runtime
conditional defaults and assignment order remain within their owning process.
The API and intentionally changed generated hierarchy are documented in
[aggregate publication](aggregate-publication.md#area-collections-with-index-branches).

Native printer hooks pass exact assignment and child-port identities to the
MorphHDL publisher. They are optional and leave ordinary native emission
unchanged. Module-scope exports require a proven total driver; external child
instances, packed writes, index values and nested predicates retain their exact
capture receipts. No product-specific names or generated-RTL patches implement
the feature.

## Local repair evidence

Validated on Scala 2.12.18 and 2.13.12:

- Five frontend tests cover ordinary syntax, lexical shadowing, unrelated Area
  classes, unsupported index aliases and collection iteration.
- Nine Area runtime tests cover count one/three, parameterized count overrides
  one/three in both Vec layouts, Bool and statement branches, packed tags/slices,
  priority, static exports, reset/hold and native/external children. Rejection
  cases cover bounds, tag width, escaped indices and competing drivers.
- Four adversarial checks reject modified index source/target/width lineage and
  hardware changes inside a documentation callback, including child drivers.
- 171 existing structural, register ownership, carrier coverage, lexical scope,
  recursion, scalar identity and documentation tests pass on each Scala version.
- The new positive fixtures pass strict Verilator lint, Icarus four-state
  simulation, Yosys synthesis/check and deterministic generation. Retained and
  unrolled count-one/count-three fixtures pass Yosys sequential equivalence;
  the three-lane proof closes all 82 equivalence cells.

The display-controller repository was read only. Its current Scala source was
compiled with both plugins into an external evidence directory. No application
source, generated delivery, compiler pin or application manifest was changed.

The real selector retains the adapter generate loop and genuine skid children.
PPC1/PPC4 standalone and transparent Scala-parent artifacts pass lint and their
main, control-accounting and genlock benches. Standalone PPC4 also passes the
padding force-injection bench in Icarus. Dedicated four-cycle timeout tests pass
at both PPC values. Standalone artifacts pass Yosys synthesis/no-latch checks.
The existing production-timeout main/control/genlock benches pass in Verilator.

The current full product top generates and passes its external-port smoke
simulation. It supports PPC1; PPC4 hierarchy coverage uses the transparent Scala
parent instead of modifying product architecture. Strict product-top lint reports
four existing warnings: three Dan `axivdisplay` width warnings and one reader
constant comparison. All four also occur in checked-in baseline RTL. Selector
lint itself is clean. This does not claim the complete product DV suite passed.

Evidence, exact commands, source hashes, artifacts and unsuccessful preliminary
harness attempts are retained at
`/home/kartik/.local/state/morphhdl/qualification/23dc9248-resume/ca028`.
The initial parent harness duplicated clock/reset assignments; it was corrected.
The short-timeout bench requires 2..8 cycles and was rerun at four, after rejecting
an initial 256-cycle invocation. Verilator does not reproduce the padding bench's
force-injection behavior; its passing Icarus run is the applicable evidence.

## Qualification boundary

These are local compiler repair checks, not remote qualification or increment
closure. New suites are enrolled in the structural/process workflow and retained
regression inventory. Publish the reviewed, sealed candidate and qualify affected
workflows on its exact head before full CI. Predecessor full-CI success belongs to
`fdeaebef9175d6cb39674a78f48c8e4f9ec70cf3` and is not evidence for this repair.

## 11 October: previous-iteration reads and scope-local names

Guarded literal `vector(i - distance)` reads in retained Area collections now
use the exact finite index, Vec geometry and branch truth set. Symbolic counts,
singleton branches and Bool/Bits/UInt/SInt values retain their authored geometry.
A per-iteration dependency check rejects combinational feedback, including a
last-element feedback into the first element. Sequential arrays preserve reset,
enable stalls and data order. The shared Vec remains module scope.

The publisher shortens only identity-proven local Area declaration names, after
symbolic width and array rewriting. Flattened nested Area prefixes, explicit
names, module-scope storage and native `_zz_` helper names remain. Keywords and
visible names are reserved. The A-to-B descendant-branch case also passes the
corrected scalar dominance check; actual aggregate edges retain their separate
coverage and dependency proofs.

The focused compiler/ownership/lineage suites pass 46 tests on each supported
Scala version, and the Area frontend suite passes seven tests on each version.
Checks include strict lint, synthesis, four-state simulation, count overrides
1/2/3/4, register width overrides 1/5/64, reset and hold, deterministic output,
name collisions and explicit names. Existing retained-versus-unrolled sequential
equivalence remains passing. Verilator uses an exact-array `split_var`
configuration for combinational prefix scheduling; no warning is waived and
no generated RTL is patched. Existing packed sub-selection restrictions are
unchanged. These are local results; exact-head remote qualification is separate.

Evidence is retained under
`/home/kartik/.local/state/morphhdl/qualification/23dc9248-resume/area-dependencies`.

Fresh read-only application validation also passes selector and transparent-parent
PPC1/PPC4 lint and simulations, selector synthesis and product-top simulation.
Product-top lint remains failing on the same four baseline warnings: three
`axivdisplay.v` width warnings and one frame-reader constant comparison. No
application sources were edited and those warnings are not counted as passes.
The final lineage run passes 54 tests per Scala version, including the existing
affine-read suite and rejection of altered selector/guard metadata.
