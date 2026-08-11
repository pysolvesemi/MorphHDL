# Increment 0: repository baseline

Increment 0 establishes a reproducible starting point before parameter-aware
compiler work begins.

## Recorded source state

| Item | Value |
|---|---|
| Private repository | `pysolvesemi/MorphHDL` |
| Private default branch | `main` |
| Public upstream | `SpinalHDL/SpinalHDL` |
| Public upstream branch | `dev` |
| Upstream commit | `bec73bb9d2ff54897bee66d641b130b66d0db869` |
| Commit date | `2026-08-06T09:23:46+02:00` |
| Commit subject | `Fix tilelink -> apb3 bridge unburstifier` |

At capture time, private `main` and public `dev` pointed to the same commit.

## Baseline invariants

- Increment 0 does not modify compiler, simulation, library, plugin or tester
  implementation sources.
- Existing concrete generation entry points remain the reference behavior.
- The full upstream commit history is retained.
- Fork-specific files are isolated so upstream merges remain reviewable.
- The existing LGPL and MIT license files and notices remain unchanged.

## Required checks

The MorphHDL baseline workflow performs:

1. Upstream-base ancestry validation.
2. Compile of production and test sources using Scala 2.12.18.
3. Compile of production and test sources using Scala 2.13.12.
4. The existing `AttributeEmitTests` concrete RTL generation smoke test.

The smoke test exercises both `SpinalVerilog` and `SpinalVhdl`. It is retained
as an upstream-owned reference test; Increment 0 does not replace or modify it.

The full inherited upstream regression remains available through the existing
upstream workflows. Later MorphHDL increments will add focused parameter-aware
tests without weakening this concrete baseline.

## Initial validation result

The baseline was captured on 2026-08-12 using OpenJDK 17 and sbt 1.10.0.

| Check | Result |
|---|---|
| Upstream ancestry and remote-head check | Passed; zero pending upstream commits |
| Scala 2.12.18 production compile | Passed |
| Scala 2.12.18 test-source compile | Passed |
| Scala 2.13.12 production compile | Passed |
| Scala 2.13.12 test-source compile | Passed |
| Concrete Verilog/VHDL code-generation smoke test | Passed; 1 test, 0 failures |

The compiler emitted existing deprecation and exhaustiveness warnings. No new
warning was introduced because Increment 0 adds no Scala implementation source.
