# Increment 40: external symbolic width and data-shape retention

Increment 40 removes MorphHDL width hooks from the native `BaseType`, `Bits`,
`UInt` and `SInt` implementation while preserving the reviewed Increment 29 and
30 contracts.

## Architecture

The four upstream-owned files are restored byte-for-byte to the Increment 0
baseline. Symbolic geometry is now associated with native `BaseType` objects by
an identity-keyed weak registry in the existing MorphHDL sidecar
`ParameterizedWidth.scala`.

MorphHDL-owned adapters delegate to ordinary SpinalHDL algorithms:

- `Bits`, `UInt` and `SInt` construct concrete witness types through native
  factories, then register symbolic metadata externally;
- `cloneOf` invokes native cloning, restores concrete leaf widths and copies the
  external identity association;
- `HardType`, `Reg` and `Vec` use native implementations with an externally
  shape-preserving generator;
- native `Stream` and `Flow` receive that ordinary retained `HardType`, so their
  existing payload and pipeline algorithms remain authoritative.

No native constructor or clone method contains a MorphHDL callback.

## Preservation guard

The native-source preservation manifest is repinned to the implementation commit
and no longer lists the four restored files. Any later difference in those files
is therefore rejected by the existing guard.

## Validation

The focused workflow checks exact byte parity of all four restored files and
runs the width/data-shape, frontend, single-source, native library and native
memory regression suites on Scala 2.12.18 and 2.13.12. The regular native-source,
Mill, baseline, deterministic RTL and strict Verilog-2001 gates remain required.
