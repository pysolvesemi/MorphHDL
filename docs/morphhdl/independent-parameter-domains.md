# Independent typed parameter domains

## Status and executable contract

This is the requested independent-parameter corrective repair, not a newly
numbered roadmap increment. The target remains `parameterized-verilog` and the
work continues on `agent/independent-parameter-domain-composition`. Completion
and merge require the applicable final-head checks; an offline test result is
not a substitute for those checks.

The baseline `3b547ae5622ae212c17f6e67cc96924127a46a41` compiler rejects the unchanged
fixture at
`repro/independent-parameters/src/main/scala/repro/IndependentParameterRepro.scala`
at addition with `SPINAL-ELAB-DOMAIN-EXACT-CORRELATION-UNSUPPORTED`.

The unchanged compiler also failed in the exact standalone SBT layout in
GitHub Actions run `34827208111` at fixture-only commit
`0c3628467db669e7b398208357e73fbf50ae64f6`. Its retained artifact
`10342090830` records SBT 1.10.0, successful Scala compilation and the same
`requireNoLostExactCorrelation -> combineDomains -> binary -> add` failure.
Compiler sources at that commit are unchanged from the reported baseline.
It uses Scala 2.12.18, SBT 1.10.0 and both required compiler plugins.

```sh
cd repro/independent-parameters
sbt -batch 'runMain repro.IndependentParameterRepro out'
sbt -batch 'runMain repro.IndependentParameterRepro out-repeat'
cmp out/RecordWidth.v out-repeat/RecordWidth.v
python3 -m unittest -v test_check
python3 check.py
```

`check.py` runs separate generator JVMs, compares published bytes without
normalization, compiles the actual files as Verilog-2001, then simulates each
same artifact with independent overrides. It checks actual hierarchical DUT
port widths, parameter values, every one-hot bit and return to zero. Native
naming such as `record_1` is read from the generated fixture interface; the DUT
is never renamed or modified. The hierarchy fixture also checks actual child
port widths and each child parameter. The projection fixture checks both
branches, the highest kept bit, and the discarded highest source bit in the
narrow branch. Failed legality/unsupported-branch fixtures must publish no
Verilog file.

The exact Scala component remains:

```scala
class RecordWidth(dataBits: ElabInt, generationBits: ElabInt) extends Component {
  setDefinitionName("RecordWidth")
  val record = in Bits((dataBits + generationBits) bits)
  val observed = out Bool()
  observed := record.orR
}
```

Actual native output from the repaired local compiler:

```verilog
module RecordWidth #(
  parameter integer DATA_BITS = 32,
  parameter integer GENERATION_BITS = 32
) (
  input  wire [(DATA_BITS + GENERATION_BITS)-1:0]   record_1,
  output wire          observed
);
  assign observed = (|record_1);
endmodule
```

The default/minimum/maximum widths are 64/3/2112. The real record fixture retains
three independent parameters in `dataBits + 7 + generationBits + lanes + 16 + 1`.
No PROFILE, concrete-witness extraction, specialized module family, application
rewrite or repair of the published Verilog is used.

## Root cause and proof design

`HdlInt.asElabInt` gives each public declaration a stable frontend token, exact
schema object and native root identity. Independently declared parameters are
not equal roots merely because their names, defaults or schema values compare
equal. The old native arithmetic and Boolean combination paths only composed
one exact root (or that root with literals). Their correlation guard correctly
rejected an operation that could not retain such evidence. Later authoritative
width consumers also required evidence, so deleting that guard was not a fix.
The older width-only Cartesian evaluator is additionally capped at 65,536
combinations; the minimal fixture alone has 129,024 legal tuples.

`ElaborationProductDomain` supplies a private certificate for the exact
expression object. Only authenticated single-root leaves or existing certified
operations enter it. Public case-class copies do not inherit the certificate.
A certificate contains exact value functions and every source axis, including
axes canceled from the result. Original root/schema objects, legal values and
active branch restrictions stay attached.

Addition, subtraction and constant scaling normalize linear combinations of
value functions. Terms with overlapping dependency sets form connected groups;
independent groups compose attainable extrema. Thus partially overlapping
`{a,b}` and `{b,c}` sets cannot be mistaken for disjoint inputs. Shared-root
cancellation and equivalent derived sums retain correlation. Independent
nonlinear operations use exact compositional rules where supported. The
fallback streams every tuple of a bounded overlapping group; it never samples
only defaults and never eagerly constructs a large Cartesian table.

Packed-width construction, width inference, equivalence, conditional extrema,
native graph capture and native publication consume the resulting evidence.
Post-capture validation uses each exact declaration's retained structural owner,
not a reopened construction branch. Resize identity checks now validate the
source and target at their respective owners before comparing their functions.

Ordinary child constructors may inherit compound parameter widths when parent,
actual child and canonical definition retain the same authenticated declaration
axes and identical connected width functions over full domains. This is an
identity binding, not solving A+B from a port's default width. Unrelated
same-name declarations, mismatched functions, partial connections and ambiguous
bindings remain rejected. Explicit formal-slot capability rules are unchanged.

## Predicates and supported-domain limits

Universally true predicates are proven over all admitted tuples; universally
false predicates retain false classification. Mixed predicates retain symbolic
classification. For example, with independently bounded positive A and B,
`(a > 0) && (b > 0)` is always true, while `!(lanes > 1) || data >= lanes` can be
mixed even when the defaults satisfy it. A mixed `require` fails with
`SPINAL-ELAB-REQUIRE-DOMAIN-UNPROVEN`; it does not silently admit all overrides.
An always-false `require` fails with `SPINAL-ELAB-REQUIRE-ALWAYS-FALSE`.

The limits are explicit:

* Per-root exact-domain limits remain unchanged. Product certificates support
  at most 32 axes and 256 normalized terms. Non-separable joint fallback and
  arbitrary callback relations are limited to 65,536 tuples; larger unproved
  correlations fail with `SPINAL-ELAB-DOMAIN-PRODUCT-CORRELATION-UNSUPPORTED`.
  Large independent sums/products do not consume this Cartesian budget.
* Existing single-root structural branches can project every dependent product
  width. Mixed **multi-root structural branch predicates** are not lowered by
  this repair: their joint non-rectangular branch domains need a separate native
  structural-owner representation and fail explicitly. Classification of those
  predicates is supported; structural capture is not falsely flattened into
  independent per-axis ranges.
* The new inherited compound child binding requires full, unprojected domains
  and full direct packed connections. Arbitrary remapping of separately
  declared multiple formal slots is not inferred from widths.
* Consumers that explicitly require a single-root state/capability contract,
  such as typed Counter state domains and existing scalar formal APIs, retain
  that restriction. Legacy frontend-composed expressions without native exact
  product authority remain rejected. Use the native typed operations shown in
  the reproducer; no symbolic-to-Int/Boolean erasure is introduced.

## Regression coverage and local qualification

The core regression checks include a 4096^4-domain separable expression,
same-root and partial-overlap correlation, an independent exhaustive three-root
oracle, mixed legality, schema/root collisions, no-op and stale public copies,
invalid/overflowing widths, non-default overflow, division by zero, excessive
nonlinear correlation, and branch-scope escape through canceled dependencies.
Native publication tests exercise exact owner identity and copied evidence,
compound child inheritance, foreign child roots and equal-default but different
width functions. Existing safety checks are not removed; obsolete blanket
independence rejection expectations are replaced by the appropriate supported
composition or still-required identity/consumer rejection.

Local execution used the real Scala 2.12.18 compiler and both plugins against
archived compiled dependencies, with rebuilt core/native/test class overlays.
`check.py --java-classpath <explicit classpath>` records that offline mode and
all exact commands. It is not reported as an SBT run or clean final-head CI.
The source-preservation guard passed the local compiler commit with 7 roots,
46 approved paths and 229 byte-span edits; the retirement guard also passed.

The native matrix passed 65 instances and 18,576 one-hot positions across five
fixtures, including all seven requested independent width overrides and an
unoverridden default instance. Each of the five published artifacts was
identical across two separate JVM generations. Always-false, mixed-validity
and unsupported joint structural-branch generation were rejected. A consolidated
run with the repository's pinned Yosys 0.41 passed **318 tests in 32 suites**:
303 existing tests, 11 new core tests and 4 new native-publication tests. All
14 memory tests passed with that toolchain; the five earlier Yosys 0.9 failures
were not ignored. All 13 Python driver tests, 7 JUnit gate negative/positive controls, and the
source-preservation, typed-layering and retirement adversarial self-tests
also passed. Clean final-head CI remains a separate
qualification requirement.

The reproducible clean-build gate is:

```sh
python3 repro/independent-parameters/qualify.py --scala 2.12.18
python3 repro/independent-parameters/qualify.py --scala 2.13.12
```

It starts SBT from the checkout without offline classpath overlays, cleans all
build products, and requires complete zero-failure, zero-skipped JUnit reports
for every suite in `regression-suites.json`. The native standalone fixture
retains its exact Scala 2.12.18/SBT 1.10.0 dual-plugin build.
