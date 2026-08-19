# MorphHDL IR passes

This is a standalone MorphHDL-owned workspace for the two optional
wire-assignment passes controlled by
[`morphhdl-ir-wire-assignment-passes-todo.md`](morphhdl-ir-wire-assignment-passes-todo.md).

The workspace is intentionally not part of the repository root SBT or Mill
aggregate. It must not modify upstream-owned SpinalHDL source. The passes will
consume the canonical MorphHDL-owned IR after parameterization and before
Verilog-2001 emission; they will not parse generated Verilog.

WA-01 provides only:

- the isolated cross-Scala SBT build;
- immutable pass configuration, result, diagnostic and elimination-report
  contracts;
- the pass-workspace boundary guard and its self-tests; and
- CI validation for Scala 2.12.18 and 2.13.12.

No RTL transformation is implemented in WA-01.

## Local validation

From the repository root:

```bash
bash morphhdl-passes/scripts/test-boundary-guard.sh
(
  cd morphhdl-passes
  sbt -batch +test
)
```

The contracts keep both passes disabled by default. Future increments may bind
this workspace to the canonical MorphHDL IR only after their recorded
Parameterized-Verilog dependencies are merged.
