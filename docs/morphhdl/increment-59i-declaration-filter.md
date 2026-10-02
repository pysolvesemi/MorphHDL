# Increment 59i — Declaration publication filter

Status: development checkpoint, not complete 59i integration closure. The
controlling roadmap remains unchecked and PR #177 must not merge on this
checkpoint alone.

## Change and boundary

The combined scoped-record fixture retains many exact symbolic scalar widths.
The existing declaration rewriter compiled two name-specific regular
expressions for every retained signal on every declaration line, even when
that signal's literal name was absent. This made repeated publication of the
combined layout/signedness/default matrix unnecessarily expensive.

The rewriter now skips an entry only when its literal name is absent from the
**current** line. Both original exact declaration patterns and their replacement
ordering remain authoritative for every possible match. Testing the current
line, rather than the original input, preserves the existing sequential behavior
when an earlier replacement introduces text relevant to a later entry.

This is a necessary negative filter, not permission to infer symbolic widths
from a name. The upstream native graph/width certificates, assignment checks,
structural ownership, signedness, source audits and all rejection paths remain
unchanged. No callback, arithmetic expression, register, packing rule or
parameter domain is replaced. No native core/lib source changes in this patch.

## Executed local checks

The six new Scala tests passed on both Scala 2.12.18 and 2.13.12. Their test-only
reference is the frozen original declaration method. Coverage includes native
declaration kinds and qualifiers, attributes and comments, identifier substrings
and regex metacharacters, repeated entries, sequential replacement behavior,
2,048 randomized inventories and a 4,096-unrelated-leaf inventory.

All **12 actual candidate Verilog files** from the existing joined writer match
byte-for-byte before and after the filter: both packed/named layouts, all three
signedness modes and COUNT defaults 1/5. The 96 independently elaborated native
references retain their original bodies. Local materialization commits changed
some native-reference repository-hash comments during the development run; that
comparison is not counted as an A/B determinism pass. The separate unchanged
checker requires exact bytes for its independent A/B generation.

An initial HDL-tool probe of WIDTH=5, TAG_WIDTH=5, COORD_WIDTH=5, COUNT=5, MODE=0
passed strict Verilog-2001 lint, Icarus simulation (227 cycles), Yosys synthesis,
combinational SAT, arbitrary-initial-state reset entry and zero-state unbounded
induction for both legacy packed COUNT-default profiles against the separate
native reference. This probe is not the full 96-case / 12-profile matrix and
creates no full-slice certificate.

The source review adds one explicitly bounded declaration-method span to the
existing nine reviewed files without changing any previous review entry. All
ten current sources reverse exactly to the frozen integration source bytes;
65 source/manifest mutation controls passed. These local byte-level controls
used the real archived baseline, not fabricated Git ancestry. Full current-Git
inventory, native and inherited source audits remain mandatory in CI.

Local Scala execution recompiles the changed production/test classes with the
matching compiler and plugins, ahead of unchanged classes from digest-verified
CI build kits at `58ec8d33e6ee565b7f3db2e6fca4b5cce0645b54`. This is not a
claim of a clean local SBT build or fresh final-head CI. The repaired source from
`92ac3f4d` and checker update `6fe8c5c4` are preserved.

## Remaining scope

The full hardware matrix, seven actual-RTL mutation controls, dual-Scala clean
CI, inherited gates and full 59i pairwise/end-to-end closure remain required.
A development probe combining independently counted inner Vecs with alternative
typed owners still rejects ambiguous recursive-result assignments. That safety
restriction is not removed by this performance change. Widening/captured
composites and expanded hierarchy/register combinations remain separate open
implementation obligations, not features claimed by this checkpoint.
