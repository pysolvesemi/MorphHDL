# Parameter and local-constant declarations (LWR-39)

`@param` and `@localparam` select public HDL parameters and private module-local
constants. Ordinary uppercase Scala names do not select either behavior.

```scala
import spinal.core._

@param val COUNT = 8
require(COUNT >= 1 && COUNT <= 32)
@localparam val OKAY = B(0, 2 bits)
@localparam val SLVERR = B(2, 2 bits)
val DECERR = Param(B(3, 2 bits), local = true)

val response = out Bits(2 bits)
response := OKAY
```

The annotation forms and inferred `Param(value, local = ...)` declarations use
one declaration bridge. `local = false` is the default. An explicit factory name
or `@rtlParamName("NAME")` overrides the inferred HDL spelling. Existing typed
`Param[T]` declarations retain their elaboration-value semantics; adding explicit
`local = false` does not change them.

Hardware values retain Bits, UInt, SInt or Bool type and explicit width. They can
be assigned and used by hardware operations directly. Hardware local constants
publish `localparam` declarations and authenticated native references, rather
than constant-driver wires. Integer/Boolean declarations remain elaboration
values: integers retain integer parameter semantics, not a width guessed from
their default. Add finite `require` constraints for geometry and bounded domains.

Declarations must be immutable, eager and unconditional in their owning module.
Runtime signals, registers, conflicting annotations/options, nonconstant `local`
flags and reassignment are rejected. The `local` option currently requires a literal Boolean. Literal or authenticated elaboration-value
sources preserve parameter dependencies. Unsupported constant-expression forms
are rejected rather than evaluated as witness defaults. A private local constant
cannot become a public child formal. Named switch keys must be proven disjoint
throughout the legal parameter domain.

This change does not replace constructor/configuration parameter forwarding,
optional parameters, recursive-component capture or the existing `TypedLocalUInt`
API. Validation evidence is recorded separately when the increment is closed.

The CSR declarations above emit:

```verilog
localparam [1:0] OKAY = 2'b00;
localparam [1:0] SLVERR = 2'b10;
localparam [1:0] DECERR = 2'b11;
```

Initializer checking has two parts: resolved Scala symbols establish that a
source-visible helper is pure, then native assignment identities establish that
its result is constant. Register reads, input ports and runtime expressions do
not gain constant authority through an alias. Arithmetic on fixed-width native
constants preserves modular intermediate widths using sized localparams where
necessary. Parameter-derived values use the existing authenticated elaboration
expression/domain machinery; casts preserve signedness and sized references
avoid implicit Verilog truncation warnings.

The supported initializer subset is intentionally finite: explicitly sized known
literals, retained elaboration expressions, constant aliases, width-preserving
casts, and fixed-width addition/subtraction/bitwise expressions. Arbitrary library
callbacks and unsupported native expression nodes are rejected. Poison literals
are not constant declarations. Public packed formals retain the existing packed
parameter API's finite integer/domain limits; this change does not promise
arbitrary-width public value domains. Wide fixed local literals are supported.
Symbolic-width local values require a proven value domain. Pure helpers must be
source-visible to this compilation (or a recognized native intrinsic).

## Focused validation

Both normal compiler builds pass on Scala 2.12.18 and 2.13.12. Each version passes
72 native/library tests and 199 frontend tests (542 executions total). These
include existing typed-local, packed-parameter, constructor, whole-configuration,
optional-parameter and recursive-component regressions. New tests cover exact CSR
encodings, annotation/factory RTL equivalence, deterministic generation, signed
extension and modular intermediate arithmetic, parameter overrides, X/Z switch
selection, collisions, ownership, mutation, runtime sources and impure/cyclic
helpers.

Strict Verilog-2001 compilation, Icarus simulation, Verilator lint and Yosys
synthesis checks pass. Questa is unavailable. No CI or full qualification was
run, as requested for this increment. The [JSON receipt](parameter-annotations.json)
records verified source hashes, exact test commands, artifact/tool-log hashes,
versions and the durable local evidence locations. Tests were built from the
recorded source changes on baseline `e93d8014c63ad1623ae584ebc6572531fbf58cd5`;
the publication receipt binds those same source bytes to the final commit/tree.
