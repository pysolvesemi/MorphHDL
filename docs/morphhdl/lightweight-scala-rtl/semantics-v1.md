# Lightweight Scala RTL semantics, version 1

This contract tracks the 86 requirements in the application roadmap dated
14 October 2026. `baseline.json` records their exact text and the starting source
identities. Pending entries are requirements, not support claims. Historical
CA repairs remain completed historical work; composition probes determine the
new frontend gaps.

## Staging

Scala `Boolean` chooses elaboration code normally. A parameter predicate carries
its declaration identities and finite domain into a generate branch. Hardware
`Bool` belongs in `when`; it must not become a Scala Boolean or a parameter.
There is no implicit conversion from a parameter or index to Scala `Int` or
`Boolean`. The native elaborator's representative values are not public semantics.

The first public release uses `ElabInt` and `ElabBool` as canonical parameter
carriers rather than adding a parallel expression algebra. `ParamInt` in the
roadmap is proposed spelling. A concise declaration API must register a fresh
child-local formal with its actual, name and finite domain. Constructor forwarding
must preserve that distinction. Payload types and callbacks specialize a module;
integer parameters remain instance bindings.

The compiler rewrites symbolic control before Scala's Boolean type checking.
It must resolve lexical ownership, aliases and shadowing; matching identifier
spelling is insufficient. `==` and `!=` on authenticated parameter operands lower
to symbolic equality/inequality. `elabEq`/`elabNe` remain explicit escape hatches.
Concrete object equality is unchanged. An unrecognized symbolic expression must
fail, never silently select the default's branch.

## Supported subset and safety obligations

The target staged subset includes immutable local bindings, pure parameter helper
methods, hardware declarations/assignments, guarded child construction and finite
loops. Runtime register assignment remains `:=`. Host mutation, IO, exceptions,
early return and escaping branch/loop-owned values are not permitted inside a
staged alternative unless a separately reviewed operation explicitly supports
that effect. Expression-valued hardware branches require a common result type
and explicit compiler-owned join; choosing a representative branch's object alone
is not an implementation of a hardware result.

Concrete Scala loops retain their existing unrolling semantics. Symbolic loops
retain their index identities and bounds. Explicit retention of a concrete loop
continues to use the existing finite-range API until a documented opt-in exists.
Nonzero starts, inclusive ranges, reverse traversal and non-unit strides require
explicitly tested semantics; unsupported forms must reject. Ordered reductions
must preserve traversal and odd-tail order.

An affine selector retains the exact loop token, Vec identity and symbolic
coefficient/offset. An enclosing guard contributes its authenticated domain to
read, write, coverage and non-overlap proofs. Sibling or escaped indices cannot
borrow this authority. Nested arrays retain dimensions. Hardware `UInt` selection
is a separate runtime operation.

## Recursion and hierarchy

Direct self-construction requires compiler interception before executing the
recursive constructor. The definition's typed port schema and named parameters
must be available without recursively constructing its body. Each active recursive
edge must decrease over the full domain. A witness-depth cutoff, stack overflow
catch, authored BlackBox or textual HDL rewrite is not valid lowering. Mutual and
non-local recursion remain rejected until separately implemented and proved.

Adjacent-pair reduction preserves left-to-right operands, odd-tail bridges and
concrete bridge-level callbacks. Width, payload and callback identity specialize
the definition. Built-in pipeline masks compose with callback registers without
changing reset, enable or latency. Existing `reduceBalancedTree` behavior remains
unchanged until the new implementation passes equivalence checks.

Verilog-2001 aggregate ports use packed boundaries, with lane zero least
significant. Internal unpacked arrays retain symbolic dimensions and signedness.
Direct child connections must retain port ownership and geometry through all
intermediate expressions. User names, keep boundaries and attributes survive;
only authenticated compiler plumbing may be eliminated.

## Qualification and migration

Every capability needs compile-positive/negative probes and generated-RTL tests;
compile success alone does not establish retained symbolic semantics. Test multiple
overrides of one artifact, defaults at both domain boundaries, X/Z behavior and
negative ownership/domain cases. Test native RTL before cleanup independently.

Application migrations and compiler pin changes follow a qualified candidate.
The original application roadmap and source remain untouched during initial
compiler development. No baseline result qualifies a changed compiler. Remote
targeted CI precedes full CI under `AGENTS.md`.

## Initial implementation increment (qualification pending)

`RtlParam.int("COUNT", 3, 1, 64)` and `RtlParam.bool("ENABLED", true)`
declare native bounded integer and Boolean (0/1) formals. Typed-actual overloads
forward expressions explicitly without requiring `toElabInt` at call sites.
For now, annotate bindings as `ElabInt`/`ElabBool` to stage ordinary `if` reliably.
This is the declaration part of LWR-02, not automatic constructor inference.

A recognized `for (i <- 0 until count)` with an `ElabInt` bound lowers directly
to the existing finite-range capture. `0 to end` uses `end + 1`. Nonzero starts
reject explicitly; ordinary concrete loops remain unchanged. Direct `lanes(i)` delegates to the existing scoped selector. Literal affine
arithmetic and less-than guards delegate to native correlated proofs. Direct
recursive constructors are described in the prototype section below. No roadmap
phase is closed by these initial adapters alone.

Direct `lanes(i)` access for the exact scoped loop index delegates through
`ElabScopedIndex` to the retained finite selector. It does not grant authority to
an index used after its loop ends. Positive-coefficient, nonnegative-offset affine reads use the existing affine
proof; unit-coefficient shifted accesses use the existing subrange proof. Existing
`i.affine(...)` stays available. Exact complete affine write partitions are supported as described below; arbitrary affine writes remain rejected.


### Validated initial example

```scala
val count: ElabInt = RtlParam.int("COUNT", 8, 1, 17)
val nextCount = (count + 1) / 2
val source = in(Vec(UInt(8 bits), count))
val result = out(Vec(UInt(8 bits), nextCount))
for (i <- 0 until nextCount) {
  if (2 * i + 1 < count)
    result(i) := source(2 * i) - source(2 * i + 1)
  else
    result(i) := source(2 * i)
}
```

The independent scoreboard checks pair ordering and odd tails on COUNT overrides
1/2/3/5/7/8/15/16/17, including X/Z data, with source defaults 1 and 8. Scoped
statement guards support `<` and equality/inequality against an integer
specialization. Other scoped equality rejects; it cannot silently become host
object equality. Scoped branch expressions cannot return a selected host value.
These are local checks; roadmap completion requires remote qualification and the
remaining composition, pipeline, recursion and migration evidence.

## Direct-constructor prototype (LWR-06, qualification pending)

A final component annotated with `@_root_.spinal.core.rtlRecursive` can declare
integer/Boolean `RtlParam` formals and a single `io` Bundle, then construct itself directly
inside a proven parameter branch. The frontend derives an interface constructor
and exact formal bindings; it does not execute the recursive Scala constructor.
Every native edge still undergoes decreasing-domain, ownership, port geometry
and driver validation. The annotation requires the MorphHDL compiler plugin.

The current prototype requires one constructor argument list, formals declared
before `io`, and every recursive constructor argument supplied explicitly.
Positional and named bindings are supported; named bindings may be reordered.
Duplicate, unknown, missing and positional-after-named bindings reject. Authored
parameter expression evaluation order is retained. Integer and Boolean formals map to distinct constructor arguments; other arguments
must forward their exact unshadowed constructor binding. Changing an operation,
payload factory or other specialization during recursion is rejected. Construction paths
not rewritten by the frontend are rejected by an ancestry guard;
mutual recursion is not supported. An explicitly authored module name is retained;
otherwise the ordinary native class-derived definition name is established before
structural capture. This name is not an identity or termination proof.

Scalar and uniform-direction composite Vec ports are compared as authenticated
aggregate surfaces, not as their differing finite native lane inventories. Record
field order, scalar types and nested dimensions are retained; every geometry
factor is proved independently after substitution. Mixed-direction recursive
aggregate ports remain outside this prototype. Direct child Vec assignment works
without author-written packed transport carriers. The generated unpacked-to-packed
transport currently still includes a read helper; removing unnecessary transport
is tracked separately and is not claimed complete. Nested Vec/Bundle recursive
ports and fixed-width typed callbacks have local tests; register/pipeline
integration is not yet qualified. Nested affine reads carry the original
read-only proof through exact alias identities; this does not authorize affine
writes.

`LightweightRecursiveTests` is the executable example: unsigned addition and
subtraction use adjacent pairs, carry odd tails unchanged, and truncate each
operation to the configured width. It uses ordinary `if`, `for`, `Vec(i)`, guarded
`Vec(2*i+1)`, and `new LightweightRecursiveTree(...)`. Subtraction is tested against
an adjacent-pair reference, not a flat or associative reduction.

## Ordinary word slices (LWR-05, implementation under test)

Within a retained finite loop, `bits(i * width, width bits)` forwards an
`ElabInt` stride and width to the existing correlated packed-access proof.
`bits(i * 8, 8 bits)` uses its fixed-width counterpart. Both forms return the
native assignable Bits slice, so the same syntax supports reads and writes.
Offsets carry the issuing loop identity; neither a Scala integer witness nor a
hardware UInt conversion participates. Escaping the offset does not extend its
authority. Nonzero affine offsets are currently rejected explicitly; arbitrary
shifted windows are not claimed by this adapter. Existing advanced packed-access
APIs remain available with their own supported bounds and ownership rules.


`LightweightCompositeRecursiveTests` covers signed comparison, bitwise XOR and
Bundle-valued subtraction/validity callbacks. `LightweightNestedRecursiveTests`
covers row-wise adjacent subtraction with independent count/column overrides.
`LightweightFormalTests` proves two-state scalar addition/subtraction against an
independently unrolled adjacent-pair reference at counts 1/3/17 and widths 1/8/36.
Simulation supplies the separate X/Z checks; the SAT result does not establish
four-state equivalence. Synthesis profiles instantiate the emitted generic
module from a concrete wrapper, retaining its original recursive definition.

## Staged local declaration names

An immutable local hardware declaration inside a retained branch or finite loop
receives its authored Scala spelling through native weak naming. For example,
`val combined = Vec(...)` keeps `combined` in its generate scope. Explicit
`setName` choices and keep attributes retain their native priority. This does not
remove `_zz_` wires, shorten arbitrary names, or grant new ownership/access rights.
Host values are unchanged; annotated locals are left to their annotation policy.

The SBT build uses a content-addressed MorphHDL plugin path. Zinc must recompile
plugin consumers when plugin bytes change; an unchanged artifact keeps the same
option. `checkCompilerPluginCache` verifies stable options, changed-artifact
invalidation and rejection of altered cached artifacts. This avoids counting
stale transformed classes as tests of a new frontend. Mill now uses the same
content-addressing rule for both compiler plugins, returns a tracked `PathRef`,
and has its own `checkCompilerPluginCache` target. Mill's local cross-version
checks cover seven frontend and 17 native tests per version. SBT and Mill remain
separate qualification paths; neither local result substitutes for remote CI.


## Explicit helper boundaries and range traversal

A non-overloaded local/member helper with an explicitly declared `ElabInt` or
`ElabBool` result may feed staged control through an ordinary unqualified call:

```scala
def nextCount(n: ElabInt): ElabInt = (n + 1) / 2
def isLeaf(n: ElabInt): ElabBool = n.elabEq(1)
if (isLeaf(count)) io.result := io.terms(0)
else for (i <- 0 until nextCount(count)) { /* typed body */ }
```

The syntax pass uses the lexical return-type declaration, then ordinary Scala
typing and native expression validation still apply. A shadowing host helper
retains host semantics. Simple, non-overloaded single-expression helper returns are inferred through
lexical calls, including forward helper calls. Overloaded helpers, block-valued
helpers and arbitrary qualified member calls need explicit typed result bindings;
no carrier meaning is guessed from a method name or argument subtree. This does
not authorize side effects or cross-component captures.

Symbolic `reverse` and `by` traversals are rejected with
`MORPHDL-TYPED-RANGE-TRAVERSAL-UNSUPPORTED`; they do not silently unroll. Forward
zero-based `until` and `to` retain the existing supported meanings. Ordinary
concrete Scala ranges are unaffected.

## Optional integer parameters

`RtlParam.optionalInt("PIPELINE_MASK", actual, 0, 7, 0)` declares a native formal
when `actual` is non-null, including an explicit zero. A null actual omits the
formal and returns a declaration-owned constant with the supplied absent value.
That value must belong to the declared finite domain. Presence is a Scala
specialization choice; it cannot change inside one recursive definition.

The inferred recursive boundary retains every exact declaration slot internally,
but publishes generics only for present parameters. Recursive source may forward
the returned optional slot directly. An omitted slot may be forwarded only by
its exact declaration identity or null; replacing it with an unrelated expression
rejects. Active slots retain ordinary native domain/substitution validation.
There is still no positional formal vector in authored recursive code.

## Inferred simple helper results

Unique local/member helper definitions with a single recognized carrier
expression can omit their return annotation. For example, `def pairs(n: ElabInt)
= (n + 1) / 2` retains the parameter result. Forward calls between such helpers
are resolved to a bounded fixed point using lexical bindings and typed arguments.
Ordinary helpers that shadow a name, and metadata/host operations such as
`toString.length`, remain ordinary Scala. Overloaded, qualified and block-valued
helpers still require an explicit typed intermediate or return annotation; there
is no inference based merely on a subtree mentioning a parameter. Existing domain,
index-escape and recursive-construction checks remain authoritative.

## Boolean recursive bindings (local validation, remote pending)

An `ElabBool` constructor actual declared with `RtlParam.bool` can be forwarded
by a direct recursive call, including a named argument. The compiler authenticates
the Boolean declaration and its exact constructor actual, then binds the retained
0/1 integer formal. No user conversion to `ElabInt` is required. Equivalent-looking
Boolean expressions cannot impersonate the declaration or borrow another owner's
slot. Interface reconstruction uses the substituted slot's Boolean predicate.

`LightweightBooleanRecursiveTests` checks source defaults COUNT=1/5 and both
Boolean defaults, with COUNT overrides 1/2/3/5/7/8 and both Boolean overrides on
each artifact. It includes singleton Z passthrough, randomized and one-hot inputs,
compile/lint/synthesis checks and declaration-identity negatives.


## Exact affine write partitions

An ordinary loop can assign `lanes(2*i)` and, under `2*i+1 < count`,
`lanes(2*i+1)`. Positive literal strides and nonnegative offsets use retained
loop/guard identities. Every legal parameter value must cover the complete Vec
exactly once. Three-way partitions are tested too. Proofs bind exact Vec leaves,
selectors and assignment identities; later competing loops invalidate the proof.
This is not permission to write via read-only previous-iteration views.

Combinational and initialized register arrays retain the same partition. A loop
inside native `when`/`otherwise` carries its enclosing runtime conditions into
capture without absorbing neighboring state updates. Reset, hold, enable and
attributes remain attached to the selected registers. Source-absent scoped else
branches are distinguished from authored branches containing unsupported effects.

Current limits: each native lane must have one stable alias owner throughout the
legal domain; the new partition proof does not combine separate scalar-prefix
writes or mixed affine/unit selectors. Existing unit-offset prefix/tail support
is unchanged. New nested affine-parent writes are not authorized by this proof.
Explicit rejection remains preferable to a witness-only coverage assumption.
The cross-version simulation covers symbolic widths 1/8/32/36/64, count overrides
1/2/3/5/7/8/15/16/17, reset/stalls and X/Z data. Runtime X/Z control equivalence
is not claimed by these tests. Remote qualification remains pending.


## Inferred recursive clocks

Compiler-inferred recursive boundaries now pull the enclosing component's exact
current clock domain when its native registers require external clock inputs.
Clock, reset, soft reset and enable use native pull identities and exact input
assignment receipts; final publication rechecks both inventories and bindings.
A copied or unrelated external domain is rejected by this first inference path.
Explicit authored clock ports remain supported and unchanged for genuine CDC.
No clock ports are added to purely combinational recursive definitions.

Local tests compare the recursive pipeline with ordinary unrolled Scala RTL at
counts 1/2/3/5/7/8, defaults 1/5, asynchronous/synchronous and active-high/low reset,
soft reset, enable stalls, reset while disabled, one-hot/random and X/Z data.
Changed input sources and cleared native pull caches are rejected. Both Scala
versions pass 32 tests across four suites. This does not claim implicit multi-domain
recursion or remote qualification. Complete assignments to a capture-local register in both arms of a scoped guard
are supported by the next-value normalization below.


## Static alternatives updating one local register

A scalar register declared in an enclosing captured body may receive one
unconditional whole-leaf assignment from each arm of a complete static `if`.
The compiler joins those exact next-value writes on a typed combinational carrier
and retains one assignment to the original register. Initialization, register
identity, attributes and clock/reset/enable semantics remain unchanged. Feedback
continues to read the original register. This transformation happens in the native
graph before process emission; it does not reconstruct HDL text.

The local declaration snapshot prevents a loop from borrowing outside storage.
Partial targets, additional live writers and runtime-conditional writes do not
enter this narrow normalization. Existing ownership/driver checks still apply.
Cross-version tests cover Bool/Bits/UInt/SInt, symbolic widths, feedback, register
attributes, two layouts and recursive clock/reset/enable behavior. The 11-suite
regression passes 163 tests per Scala version; remote qualification remains open.


## Direct host effects in ordinary staged syntax (qualification pending)

Ordinary symbolic `if` and `for` reject direct host assignments, mutable local
declarations, early `return` and `throw`. Compound assignment is rejected when
its unqualified receiver resolves to an exact mutable lexical binding. Immutable
hardware values still use their normal `:=` and hardware methods. Concrete Scala
control flow outside staged bodies keeps its existing behavior.

The established explicit `.generate` API retains its advanced native metadata
capture behavior, used by the FIFO library to retain authenticated branch-owner
handles. It still passes native capture/ownership checks. An explicit `.generate`
inside an ordinary checked body does not clear that body's effect boundary.
Runtime escape and transaction-rollback tests use explicit capture APIs so the
underlying defenses remain tested independently of frontend rejection.

Unique unqualified lexical helpers retain exact definition identities. Known
host effects propagate through their call graph, including forward calls and
parameterless references; a staged reference to an effectful helper is rejected.
Nearest lexical bindings determine identity, so a same-named pure local helper
is not confused with an effectful outer method. Unqualified `this` and named `Outer.this` references resolve against the exact
enclosing template, independently of local shadowing and intervening anonymous
templates. The same ownership resolution is used for helper result inference and
effect checks. Parameterless helper results retain their value classification
through import resolution. Concrete calls remain unchanged.

This is not a proof of arbitrary helper or callback purity. Other qualified mutable
receivers, overloaded/external helpers, collection mutation and I/O need
additional analysis before the broader effect acceptance item can close.

Compiler-supplied staged local names preserve explicit weak `setName` requests,
as well as strong names and `keep` attributes. This fallback does not replace
native naming priorities for ordinary reflected component fields. FIFO width
assertions and behavioral regressions cover the weak-name compatibility path.

## Mixed hardware and scoped array reads (qualification pending)

`source(row)(j)` admits a hardware UInt row and a scoped finite-loop column
without converting either coordinate into the other kind. Existing nested Vec
carriers supply the native transpose shape; counting leaves must not allocate
an unused typed Vec. The selected row retains signed element geometry and its
ordinary finite column selection. Known out-of-range hardware rows select the
last row, matching the retained native read policy; unknown row selectors produce
unknown data. This is distinct from static selector domain rejection.

Both layouts have local cross-version simulation, lint, synthesis and independent
two-state SAT evidence, including a rejected corrupt reference. The signed and
Boolean guarded-register fixtures also cover nonzero resets. See `toolchain.md`
for the pinned simulator needed by the singleton unpacked-array case. Mixed
hardware/scoped decoded writes remain a separate, unclosed composition.

## Hardware-selected rows with scoped column writes

A complete scoped column loop may update one hardware-selected row of a
combinational two-dimensional scalar Vec that has an authored whole-array
default. For example:

```scala
result := source
for (column <- 0 until columns) {
  when(enable) {
    result(row)(column) := replacement(column)
  }
}
```

`row` is a hardware `UInt`; `columns` is a typed finite elaboration bound.
The compiler retains the column loop and normalizes its exact writes into a
complete next-row value, then uses the native row write decoder. Disabled lanes
retain the corresponding authored default. An out-of-range row writes no lane.
Hardware conditions retain Verilog `if` four-state behavior; an unknown condition
does not select the conditional update. This does not change the separately
specified out-of-range behavior of hardware-indexed reads.

Normalization requires exact array, alias, default, source, address and assignment
identities. The publication checks reject missing defaults, partial column loops,
read-only target views, competing writers, combinational target feedback and
altered normalization edges. It does not infer intent from signal names or from
a parameter's witness value. Complete native row assignments retain a separate
forwarding journal, so removing a forwarding record cannot turn the selected
row view into an independent writable array.

The current admission is one module-owned unit-stride column loop over scalar
row elements. Structurally nested decoded-write loops, multiple independently
captured write aliases, partial row updates and register destinations are not
covered by this normalization. Their support must be established separately.
Development checks and their exact source snapshots are listed in `progress.json`;
remote qualification remains pending.

## Hardware conditions in Scala control

With the ordinary `import spinal.core._`, a hardware `Bool` used where Scala
requires `Boolean` reports `SPINAL-HARDWARE-BOOL-AS-SCALA-BOOLEAN` and directs the
author to `when`. This includes composed hardware conditions, typed helper
results and hardware comparisons. Native `when` and ordinary Scala `Boolean`
control retain their existing behavior.

The diagnostic uses a `compileTimeOnly` adaptation declared by the core factory.
It must be rejected by scalac; it provides no usable hardware-to-host conversion,
extracts no witness and emits no control logic. The normal parameter-only staging
rules remain separate. This diagnostic does not reinterpret ordinary Scala
object equality as a hardware comparison.


## Nested hierarchy capacities (qualification pending)

Direct module-owned Vec connections admit narrower physical audit capacities at
both outer and inner dimensions, including Vecs of records with nested Vec fields.
The compiler compares logical dimensions and scalar widths after exact child
formal substitution. It pairs native carriers by their recursive coordinates and
ordered field identities, rather than taking a prefix of flattened leaves.
Child-only input audit lanes receive authenticated typed zeros; child-only output
lanes must remain unconsumed. Publication validates every paired native edge,
padding edge, width, owner and driver before removing audit-only ports.

Local fixtures cover Bool/Bits/UInt/SInt, literal and symbolic dimensions,
WIDTH 1/8/36/64, packed and unpacked internal layouts, and both packed and
named-field boundaries. Changed coordinate sources, missing/duplicate writes,
changed interleaved padding, consumed audit outputs and unrelated parameter
roots remain errors. Mixed-direction and branch-local capacity adaptation remain
outside this admission. These results are development evidence, not remote
qualification or permission to migrate application code.


## Source-visible hardware callback effects (qualification pending)

The lightweight BalancedTree entry points mark operation, bridge and width-factory
parameters with `rtlHardwareCallback`. This library contract causes a typed
compiler check; it is not an assertion supplied by the callback author and does
not bypass native ownership or width validation. Direct host writes, qualified
field setters, mutable locals, early return and throw are rejected. The checker
follows exact helper and stored-closure symbols across the source units of one compilation,
including forward references and overload resolution. An immutable captured
value's already evaluated initializer is not replayed as part of the callback.
Ordinary Scala callbacks outside marked API boundaries are unchanged.

Known Scala mutable-collection mutators, array writes, console calls and JVM
stream/file I/O reject by resolved method-owner identity. Unrelated authored
methods with the same names and immutable collection operations retain their
semantics. Opaque external helper/closure implementations and arbitrary host
effects are not proven pure by this check. The broader callback-effect roadmap
requirement remains open. Native checks still reject captured foreign hardware,
changed payload widths and illegal recursive ownership. The existing native
`Vec.reduceBalancedTree` API and its topology are unchanged.


## Immutable block helper results (qualification pending)

Exact lexical helpers may infer a symbolic result through nested blocks of
immutable local values. For example, `def pairs(n: ElabInt) = { val rounded =
n + 1; rounded / 2 }` retains parameter arithmetic without an explicit return
type. Local terms shadow enclosing names before inference; initializers are then
classified in authored order. This does not infer arbitrary statement blocks,
mutable local results or opaque external helper implementations. Explicit return
types remain available at unsupported inference boundaries.

The same helper composes with guarded pair/tail loops and direct recursion;
local native tests exercise counts 1/2/3/5/7/8/15/16/17, defaults 1/5, deterministic
emission, X/Z simulation, lint and synthesis on both supported Scala versions.
