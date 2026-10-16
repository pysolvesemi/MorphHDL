# Constructor-owned parameters

A plain primary-constructor `Param[T]` argument on a directly declared native
`Component` creates that child's HDL formal before its body runs:

```scala
class Synchronizer(DATA_BITS: Param[Int], SYNC_STAGES: Param[Int]) extends Component {
  require(DATA_BITS >= 1 && DATA_BITS <= 64)
  require(SYNC_STAGES >= 2 && SYNC_STAGES <= 4)
  val source = in Bits(DATA_BITS bits)
  val result = out Bits(DATA_BITS bits)
  val stages = Vec.fill(SYNC_STAGES)(Reg(Bits(DATA_BITS bits)) init(0))
  stages(0) := source
  for (i <- 0 until (SYNC_STAGES - 1)) stages(i + 1) := stages(i)
  result := stages(SYNC_STAGES - 1)
}
```

The parent may instantiate `new Synchronizer(BUS_WIDTH, 3)` or
`new Synchronizer(8, 2)`. The emitted child retains `DATA_BITS` and `SYNC_STAGES`;
its instances bind those names to `BUS_WIDTH`/`3` or `8`/`2`. Parent parameter
names and declaration identities remain independent. Ordinary constructor `Int`
arguments still describe Scala specialization.

Previously this required `actualDataBits: ElabInt` plus a body declaration
`val dataBits: Param[Int] = Param(actualDataBits, "DATA_BITS")`. Both existing
ElabInt/body-Param code and the new constructor form remain supported. A legacy
constructor Param argument consumed by an explicit typed body Param or native
RtlParam declaration retains its actual-only behavior, avoiding a duplicate formal.
Canonical factory imports, renamed imports and lexical shadowing are resolved
before this compatibility rule applies.

## Ownership and naming

Each actual expression is evaluated once in the caller. A typed boundary wrapper
holds that expression until the child's formal registration. No symbolic value
is converted to a Scala Int or hardware index. Supported finite `require` clauses
supply legal domains; defaults do not define legal override domains.

Positional, named and default arguments are supported in one non-implicit primary
constructor parameter list. Parameters must be plain arguments, without `val` or
`var` accessors. Secondary/multiple lists are diagnosed. Ordinary method parameters
and non-Component constructor annotations do not create HDL formals. Indirect
Component inheritance is outside this inference contract. Existing explicit
body declarations remain available for those cases.

Names are inferred from the argument, including lowercase names. To preserve a
Scala named-argument API with a different HDL name, use:

```scala
class WidthChild(@rtlParamName("DATA_BITS") width: Param[Int]) extends Component {
  require(width >= 1 && width <= 64)
  // ...
}
```

Use one portable literal name. Duplicate HDL formals are rejected. Body-local
names and method/pattern arguments that shadow a constructor name retain their
Scala lexical meaning. No `@dontName` or duplicate body alias is required.

## Boolean, packed and optional values

`Param[Boolean]` accepts Boolean literals or authenticated symbolic Boolean
actuals. `Param[UInt]`, `Param[Bits]` and `Param[SInt]` accept explicitly sized,
known native literals of the matching kind, or typed packed parameter values.
The exact kind, signedness and declared representation width are retained;
ordinary Int values cannot guess a packed representation. Root constructor
packed literals are captured in an isolated literal-only scope and audited again
at child completion. Additional hardware and altered literal lineage are rejected.

`Option[Param[Int]]` accepts `None`, `Some(Int)` and `Option[ElabInt]`. Within the
body it denotes the effective integer formal, with zero as the absent value.
Absence omits the HDL declaration. Use `Param.isPresent(NAME)` for presence and
`Param.forwardOptional(NAME)` when forwarding to another optional constructor,
including direct recursion. This preserves presence independently of numeric
value. Optional nonzero absent defaults and optional packed/Boolean constructor
policies use the existing explicit body APIs instead; they are not inferred here.

Direct `@rtlRecursive` construction uses the same termination, branch, binding
and port-geometry checks as body-declared formals. Library `BalancedTree` migrates
its mandatory count declaration while preserving its Scala `countActual` named
argument and emitted `COUNT` formal. Its operation order, odd-tail and bridge
semantics are unchanged.

## Local acceptance

`ConstructorParameterSyntaxTests`, `ConstructorParameterTests` and
`LightweightPortCompositionTests` exercise frontend and native behavior. The
completion receipt records exact tested source hashes, commands and outcomes.
This document defines the implementation contract; it is not itself passing
qualification evidence.

Fresh results: [LWR-13–16 local acceptance](constructor-composition-acceptance.md).
