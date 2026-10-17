# LWR-22–24: ordinary loops, configuration formals and late register grouping

Implemented on `work/remaining-parameterized-increments`, starting from
`9977badabc213e5d12b332eb8ee620c1b9ac0de1`. The [focused receipt](ordinary-loops-configurations-late-merge.json)
records exact compiler overlays, commands, artifact hashes and durable evidence
locations. Historical results are not reused as current-source checks.

## Ordinary statement loops

With the MorphHDL plugin, zero-based `for` statement loops containing hardware
assignments can use literal or immutable configuration bounds:

```scala
outKeep := B((BigInt(1) << p.bytesPerBeat) - 1, p.bytesPerBeat bits)
when(outEol && tail =/= 0) {
  for (lane <- 0 until p.bytesPerBeat) outKeep(lane) := tail > lane
}
```

`until` excludes the endpoint; `to` includes it. Empty ranges execute no body.
`preserveConstantLoops` controls retained loop publication; disabling it expands
the authenticated body. Immutable bounds remain specialization values, not new
HDL parameters. Typed `ElabInt` bounds continue to use their existing path.
Mutation, effectful bound getters and unsupported effects in retained bodies
are rejected. Host-only loops are unchanged. The explicit compilation option
`-P:morphhdl:ordinary-loops=host` retains host elaboration for libraries whose
loops construct host topology; MorphHDL's existing `lib` build uses this option.
`-P:morphhdl:ordinary-loops=capture` is the application default.

A complete bit/word loop can override preceding module-scope fixed-width known
constant defaults. Publication projects each default into the corresponding
lane; it never duplicates the whole default across generated drivers. Exact
native default and write identities remain checked after optimization. Partial
coverage, nonconstant defaults, changing geometry or competing loop identities
are not admitted by this projection rule. Existing typed APIs retain their own
coverage rules. This is not general Scala loop compilation.

## Immutable configuration formals

```scala
case class Video(numBytes: Param[Int], lanes: Int)
case class Config(video: Video)
class Copy(c: Config) extends Component {
  require(c.video.numBytes >= 1 && c.video.numBytes <= 8)
  val source = in Bits(c.video.numBytes bits)
  val result = out Bits(c.video.numBytes bits)
  result := source
}
// A literal, symbolic parent parameter or derived expression can supply numBytes.
new Copy(Config(Video(4, 2)))
```

Configuration schemas must be present in the same Scala compilation run.
Supported schemas are constructor-only immutable case classes and immutable
classes with public `val` constructor fields. Generic schema types, computed or
lazy fields, mutable fields, effectful members and cyclic schema graphs are not
supported. Discovery inspects source trees; it does not execute user getters or
reflectively walk runtime objects. Compile shared configuration definitions
alongside their component consumers. Binary-only schema discovery is outside
this increment.

Explicit and renamed imports are supported across source files. Existing
conservative source-wildcard shadowing rules remain: use explicit imports, or
root-qualified native `Component`/`Predef.require` names when a wildcard provider
could obscure them. Schema discovery does not grant an import authority to
redefine native parameter or domain-check APIs.

Each declaration path gets a child-owned formal before the body is elaborated.
Ordinary `Int`, Boolean and other non-`Param` fields remain constructor
specializations. Shared subobjects do not collapse distinct paths into one
formal. Reconstructed owned configurations preserve pass-through child bindings;
guarded recursive edges bind each formal separately and reject changes to
ordinary specialization values. Arbitrary reference-valued specializations must
retain reference identity across recursion.

Explicit domains are required; defaults never supply bounds. Existing supported
integer, Boolean, packed UInt/Bits/SInt and optional integer parameter contracts
remain in force, including packed signedness and width checks.

### Public naming compatibility

Inferred direct constructor names, body parameter names and nested paths now use
uppercase snake case. Examples:

| Declaration path | HDL name |
| --- | --- |
| `num_bytes`, `numBytes` | `NUM_BYTES` |
| `c.num_bytes` | `C_NUM_BYTES` |
| `c.video.numBytes` | `C_VIDEO_NUM_BYTES` |
| `RGBWidth` | `RGB_WIDTH` |
| `rgb2Bytes` | `RGB2_BYTES` |
| `video2D` | `VIDEO2_D` |

Acronym boundaries split before a following capitalized word; lowercase/digit to
uppercase boundaries split; existing underscores are retained. Conversion uses
locale-independent uppercase. The constructor prefix `c` is retained. Local
aliases and object identities do not rename parameters. Normalized and explicit
name collisions fail with both originating declaration paths.

This intentionally changes public inferred HDL parameter names. Update external
HDL override names or annotate the declaration/field with
`@rtlParamName("previousName")` to retain an established interface. Explicit
names retain their exact case and spelling. Native generated child bindings
follow the formal identity automatically.

## Late sequential grouping

Compiler-owned structural separation has a distinct internal tag; explicit
`noBackendSyncMerge` requests are still honored. Early processing retains
separation. After array publication, eligible module-scope register processes
can be regrouped using their original native AST and renderer.

Eligibility requires identical clock-domain and lexical-scope identities,
disjoint register targets, exact surviving assignment inventories and unchanged
control/reset skeletons. Final assignment bodies are associated through native
edge identities. The native renderer preserves shared switch/guard trees and
nonblocking assignment ordering. It can emit one CSR address case for compatible
shadow writers while leaving the snapshot generate loop in its original scope.

Different clocks, resets, enables, generated scopes, explicit separation,
unsupported statements or unproven transformed controls remain separate. This
is a conservative readability transformation, not an area/timing promise or a
license to merge arbitrary sequential processes.
Processes with a soft-reset signal remain separate because native initializer
text may be emitted for both asynchronous and soft reset. Their reset and enable
behavior is covered without claiming repeated reset-edge merge support.

## Focused validation

Scala 2.12.18 and 2.13.12 builds pass. Each version passes 104 runtime and 143
frontend tests: **494 tests total**. These include the DDR-reader `outKeep`
source pattern for beat widths 1/2/4/8/16/32 and all tail values, X/Z controls,
packed-word defaults, signed/unpacked copies, empty/inclusive ranges, invalid
indices and host effects. Configuration checks cover field ownership, source
imports/order, shared subobjects, literal/symbolic/derived actuals, packed and
optional types, explicit names, recursive bindings, defaults and rejection cases.

CSR fixtures cover 3/15-register banks in packed/unpacked layouts, differing
resets, byte masks, partial commit clears, locks, simultaneous snapshots,
independent clocks, enables, soft reset and explicit separation. Existing
constructor, parameter, recursion, balanced-tree, switch-register, bit-write and
generate-naming regressions pass. Repeated fixtures check deterministic output.

Across both versions, 556 recorded HDL-tool invocations pass: 164 Icarus
Verilog-2001 compilations, 164 simulations (including bounded simulator calls),
124 Verilator lint checks and 104 Yosys synthesis/check runs. The source/report
inventory guard retains its rejection controls. Native-source and integration
seals are refreshed separately after committing the tested implementation.

Questa is unavailable. No CI, full qualification or new formal campaign was run.
The generic fixtures reproduce the requested application patterns; this is not
an application-wide passing claim. Application Scala, delivered RTL and Rust
cleanup remain unchanged.
