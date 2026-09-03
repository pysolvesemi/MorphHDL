# MorphHDL IR passes

This is a standalone MorphHDL-owned workspace for the two optional
wire-assignment passes controlled by
[`morphhdl-ir-wire-assignment-passes-todo.md`](morphhdl-ir-wire-assignment-passes-todo.md).

The workspace is intentionally not part of the repository root SBT or Mill
aggregate. It must not modify upstream-owned SpinalHDL source. The passes
consume the versioned `morphhdl.ir.v1` canonical IR after parameterization and
before Verilog-2001 emission; they do not parse generated Verilog.

Every adapter and pass is component-generic. Decisions may depend only on
validated canonical identities and metadata. They must never special-case
`StreamFifo`, `StreamFifoCC`, another component class or module name, a source
filename, or a generated identifier.

WA-01 established:

- the isolated cross-Scala SBT build;
- immutable pass configuration, result, diagnostic and elimination-report
  contracts;
- the pass-workspace boundary guard and its self-tests; and
- CI validation for Scala 2.12.18 and 2.13.12.

WA-02 adds:

- a nested-build dependency on the separately owned `morphir` project without
  adding this workspace to the repository root aggregate;
- a read-only adapter that accepts only a validated canonical-v1 `Design` at
  `PostParameterizationPreEmission`;
- exact identity-indexed access to declarations, drivers, reference
  occurrences, packed types, parameter domains, naming provenance, source
  locations and observability metadata;
- fail-closed canonical diagnostics for incomplete or invalid metadata; and
- a mutation-tested source guard against generated-HDL parsing, regex/name
  recognition, Spinal implementation coupling and component-specific logic.

WA-02 does not eliminate, rewrite or rename any declaration, driver, reference
or expression.

## Local validation

From the repository root:

```bash
bash morphhdl-passes/scripts/test-boundary-guard.sh
python3 morphhdl-passes/scripts/check-wa02-adapter-boundary.py --self-test
python3 morphhdl-passes/scripts/check-wa02-adapter-boundary.py
(
  cd morphhdl-passes
  sbt -batch +test
)
```

The contracts keep both passes disabled by default. Alias safety and
transformation remain later roadmap increments.
