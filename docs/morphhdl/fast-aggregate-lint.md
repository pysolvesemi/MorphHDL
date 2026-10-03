# Fast aggregate lint gate

Run before broad qualification after changing aggregate RTL emission:

```sh
python3 morphhdl/scripts/check-fast-aggregate-lint.py --output /tmp/morphhdl-fast-lint-unique
```

The gate incrementally compiles and elaborates 17 small cases, then runs
`verilator --lint-only -Wall --language 1364-2001 --top-module Top`.
It does not launch simulation, synthesis, formal proofs or full qualification.
Use `--scala 2.13.12` for the second supported Scala version. Output must be a
new directory so previous evidence cannot be silently overwritten.

The matrix includes depths 1 and 3, packed and unpacked internal storage,
flat vectors with Scala-unrolled or retained generate loops, and 2-dimensional
arrays with bulk assignments. Two derived-width port cases cover ANSI alias expansion
with aggregate preservation enabled and disabled. Three unsigned-mask cases lint
parameter overrides of 4, 8 and 32 bytes, including native arithmetic temporaries
and explicit operand sizing. Nested procedural/generate writes are not claimed
by this smoke matrix. This is an early gate, not exhaustive branch coverage.

Every case retains RTL, tool output, command, tool version and RTL SHA-256.
Missing cases or any Verilator warning/error fail the gate. There are no blanket
warning suppressions. `--rtl CORPUS` checks an existing fixture corpus as diagnostic
evidence only; it does not prove generation from the current source.

The direct-read repair passes all 17 lint cases on Scala 2.12.18 and 2.13.12,
without warning suppressions. Six dedicated lowering regressions cover dynamic
aligned and unaligned reads, invalid addresses, multidimensional ordering,
blocking write/read ordering, symbolic continuous slices and the procedural
helper fallback. They retain simulation and synthesis checks. A symbolic
procedural helper still needs its sensitivity witness; that intentionally retained
fallback is behavior-tested separately and is not claimed warning-free.

Fixed-width reads use actual array elements or bits directly. Continuous reads
with symbolic or large widths use an indexed packed-view slice. A procedural
read requiring the helper must still observe blocking writes immediately;
replacing it with a continuously assigned packed view is not valid in general.

The receipt binds the committed head/tree and hashes every dirty or untracked
input as well as both gate sources. Dirty-tree runs are repair evidence, not
remote qualification. Qualification remains stopped at the user's request.
