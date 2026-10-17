# Comments API local validation receipt

The user authorized the complete comments roadmap and limited acceptance to Scala
compilation and generated-comment validation. CI and hourly monitoring remain
paused. This receipt is not synthesis, simulation, formal, remote-CI or merge
qualification.

## Source and commands

Implementation branch: `work/remaining-parameterized-increments`.
Tests ran from base HEAD `d59747bc894ee8f8610a9283d00ae879da0ba291` with the comments
implementation in the working tree. The exact tested build/Scala file hashes are
retained in `~/.local/state/morphhdl/comments-api/evidence/tested-source-sha256.json`;
closure verifies those bytes against implementation commit
`4f8ccf6b1d0ef273b859d3bffc35de14a822810a` and the final source snapshot. Integration target
`parameterized-verilog` remains `db54d01e5b21c7664f7a0de3795f061d77a3d259`.

Environment: OpenJDK 17.0.20.1, sbt 1.10.0, Scala 2.12.18 and 2.13.12.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$HOME/.local/bin:/usr/bin:/bin"
export COURSIER_REPOSITORIES=https://repo.maven.apache.org/maven2
export JAVA_TOOL_OPTIONS='-Xmx3G -XX:ActiveProcessorCount=2'
export SBT_TEST_PARALLEL=0
sbt -batch '++2.12.18' \
  'idslplugin/testOnly spinal.idslplugin.DocumentationPluginTests' \
  'morph/testOnly morphhdl.RtlDocumentationTests' \
  '++2.13.12' \
  'idslplugin/testOnly spinal.idslplugin.DocumentationPluginTests' \
  'morph/testOnly morphhdl.RtlDocumentationTests'
```

Both Scala versions passed **6 compiler tests and 29 generated-output tests**:
70 successful test executions, no failures, skipped tests or pending tests. Fresh
compiler fixtures cover clean compilation; same-directory comment-only
recompilation covers changed embedded metadata. Tests delete original source
before loading precompiled fixtures. sbt also rebuilt affected modules and ran
incrementally during repairs.

## Coverage

| Roadmap | Retained evidence |
| --- | --- |
| C01–C04 | Explicit annotations/fluent APIs, scalar/module/instance placement, text validation, initialization once, unsupported targets, source locations and independent default-off capture flags. |
| C05 | Packed/unpacked/named-field layouts, nested arrays, mixed directions, scalar/Vec projection ordering, singleton coordinates, split files, parameterized widths and module deduplication. |
| C06–C07 | Trailing/preceding/header/Scaladoc comments, annotation boundaries, multiline initializers, detached/ambiguous exclusions, CRLF/Unicode, precompiled libraries, comment-only recompilation and missing-range diagnostics. |
| C08 | Assignments, if/elsewhen/else, switch/multiple labels/defaults, removed/empty regions, exception cleanup, procedural loops, conditional selection, reset/clock-enable placement and shared region identity. |
| C08b | Symbolic/compound generate conditions, parameterized bounds, constant/zero-default modes, nested finite loops, branch/body notes, automatic nested capture, sibling constructs and exact assignment/instance relocation. |
| C09 | Combined example, repeated deterministic output, unchanged non-comment tokens, directive/attribute/string-aware comparison and regression inventory updates. |

The tests compare paired emitted token streams, retaining synthesis/tool directives,
attributes and quoted strings. Differing definition documentation intentionally
prevents module deduplication; tests verify that distinction separately, including
punctuation that previously made signature encodings ambiguous.

## Artifacts and limits

Full final log: `~/.local/state/morphhdl/comments-api/evidence/qualification.log`.
Generated examples in the adjacent `generated/` directory include combined APIs,
conditional loops, nested loops, partial regions and shared generated constructs.
Local report snapshots and SHA-256 receipts are retained beside them.

The retained regression inventory now lists 2,213 cases, including these 35 new
cases. Only the comments subset was run here; no claim is made that all 2,213 cases
or any historical hardware qualifications ran. Inventory and integration guard
self-tests are source checks, not hardware qualification.

Automatic source capture requires `-Yrangepos` and recompilation. Supported DSL
methods and attachment boundaries are documented in
[the contract](comments-api-contract.md); use `rtlDoc` for ambiguous regions.
Ordinary Scala control flow is not converted into new hardware by this feature.
Other HDL backends and dual-factory Morph generation are outside this contract.

## Source review and closure

The native source audit passed against the unchanged upstream baseline: seven
roots, 66 approved paths and 275 reviewed byte-span edits. Its positive control,
eight exact rejection controls and the production-retirement guard passed. The
retained-regression inventory guard passed its positive/seven-rejection controls;
the integration guard passed its edit/reintroduction/symlink controls. Checks and
source sealing use:

```bash
python3 morphhdl/scripts/check-native-source-preservation.py
python3 morphhdl/scripts/check-native-source-preservation.py --self-test
python3 morphhdl/scripts/check-retained-parameterized-regressions.py --self-test
python3 morphhdl/scripts/check-parameterized-integration-source.py --self-test
python3 morphhdl/scripts/check-parameterized-integration-source.py
```

The source seal and durable local checkpoint record the final commit/tree after
these documentation and manifest updates. All tested Scala/build hashes remain
unchanged. The hourly systemd timer was verified disabled/inactive. There was no
push, remote CI dispatch, target-branch change or merge.
