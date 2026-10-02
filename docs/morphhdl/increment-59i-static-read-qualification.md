# Increment 59i — Static composite read and signed-scope qualification

Status: development checkpoint. The controlling 59i item remains unchecked;
PR #177 is not ready to merge. This is the first scoped fixed-shape composite
slice, not full widening/capture/inner-Vec integration closure.

## Reproduced CI failure

At source `58ec8d33e6ee565b7f3db2e6fca4b5cce0645b54`, both Scala versions
compiled successfully. Both then failed 13 of the 17 combined tests at the
same preflight diagnostic: an opaque native assignment redirect on a UInt.
The enabled-reset Python model repair remains intact; this new failure occurs
before hardware publication or formal execution.

The CI build kits were downloaded and checked against GitHub artifact digests:

| Scala | Artifact | ZIP SHA-256 |
| --- | --- | --- |
| 2.12.18 | 10044784419 | `8271d661c0935c595a99749cadb57c7206d25bd891e76413df90cd1dbc3860ac` |
| 2.13.12 | 10045603007 | `12577a2c096614bf44bff797d4ecd1d4e870514953224a65de4470cd5307001a` |

Their source archives and exported classpaths identify that exact commit.
Local diagnosis recompiles changed classes with the matching Scala compiler,
plugins and dependencies, ahead of the unchanged compiled classpath. This is
not a claim of a clean local SBT build or fresh final-head CI.

## Exact read evidence, not a callback escape

A native static `records(0)` read installs `ParameterizedVecStaticAccessAssign`
on each record leaf. The previous composite preflight rejected that wrapper,
although the native scalar preflight already supported a scalar-only form.
The join first audits the actual recursive Data classes and containment. It
then validates each read wrapper against the exact selected native element,
leaf index and scalar identity, retained carrier/leaf counts, and bounded
previous-wrapper chain. It does not call application `flatten`, clone,
assignment or source hooks while establishing that authority.

The native edit is confined to the existing static-access wrapper seam in
`Vec.scala`. Its assignment body, exception restoration, scalar-only read
check and `getRealSourceNoRec` body remain unchanged. The reviewed native
manifest retains every old exact-text requirement and adds the composite
identity requirements. Other native files and source roots are unchanged.

Negative tests cover different equal-width fields, different elements,
invalid indices, stale shapes, an unretained Vec, an opaque previous hook,
an unsafe containing record reached through a scalar field, and excessive
wrapper depth. Unknown hooks remain rejected before execution.

## Preserve signedness and structural ownership

A constant signed Vec leaf is reconstructed only after the existing exact
static-read and live-driver checks. Packed transport remains unsigned. Its
private scalar alias now retains the actual native declaration qualifier,
rather than requiring cast cleanup to be enabled. The already-qualified
named-field alias contract remains unchanged. Legacy packed SInt declarations
therefore remain unsigned with native casts; declaration-only and cast-cleanup
modes retain their signed aliases. No unsigned/signed reinterpretation is
inferred from a name or concrete width.

Declaration-only mode also exposed a structural dependency bug: `signed` was
being treated as a signal name, allowing an earlier template to consume
unrelated signed declarations. Native type keywords are now excluded from
that reference vocabulary. The original driver, branch, result-escape and
scope checks remain active.

The structural consumer and overlap checks now build immutable indexes instead
of rescanning each body for each possible signal or enumerating quadratic
interval combinations. Differential range tests cover 2,048 randomized
inventories, exact shared-process duplicates, containment and touching ranges.
Necessary identifier filters preserve the complete declaration parser and
uniqueness checks. They do not change emitted operator or register logic.

## Qualification and limits

The dedicated workflow now requires the seven new static-redirect tests and
six structural lexical/index tests in addition to the unchanged 17 combined
tests and inherited reduction suites. It also requires the complete 59i
source-delta review before restoring only those pinned bytes for unchanged
59g/59h and downstream inherited source checks. The existing 59g/59h review
contracts are not rehashed or relaxed. Canonical native-source validation
remains independent.

Local pure source tests reverse all nine reviewed files to the frozen
integration source and reject 59 source/manifest mutations. These byte tests
are not a substitute for Git-ancestor, full-inventory or canonical native
audit execution on the real CI checkout.

The complete focused local selection passed on both Scala 2.12.18 and 2.13.12:
**30 tests in three suites, zero failures, cancellations, ignored or pending
tests**. This includes all 12 packed/named-field, signedness and alternate-default
publication profiles, the remaining five combined safety/compatibility tests,
seven redirect tests and six lexical/index tests. These runs generated actual
Verilog through the repaired native publication path. They used the explicitly
recompiled changed-source overlay described above, not fresh exact-head CI.

The unchanged Python checker also passed all 16 regression tests and the
96-case / 12-profile / 117-negative-control self-test. Pure restoration checks
verified the successor composition through all ten existing register review
paths and eighteen existing owner review paths. No synthetic Git ancestry was
used to report a complete source gate.

The 96 parameter cases, 12 publication/default profiles, seven actual-RTL
mutation requirements, reset-entry assumptions, induction commands, solver
timeouts and independent native reference bodies remain unchanged. Passing
Scala generation is not hardware proof. The full 59i roadmap and final-head
CI/formal/tool requirements remain open.
