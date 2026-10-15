# Lightweight RTL toolchain development evidence

Remote qualification is pending. The simulator revision below is an isolated
local validation dependency. The structural/process workflow now enrolls its authenticated build in the new lightweight lanes; those lanes have not yet run. The compiler
and application dependency pins are unaffected.

The mixed hardware-row/scoped-column fixture exposed an Icarus 12.0 runtime
assertion in `compile_array_alias` for a one-element unpacked array aliased to
a packed result. Larger column counts and packed layout passed. The failing
native artifact also passed Verilator lint and Yosys synthesis without latches.
Do not remove singleton profiles or treat the simulator crash as a pass.

The same native artifact and testbench pass with unmodified upstream Icarus:

- Repository: https://github.com/steveicarus/iverilog
- Commit: `af348e2a468d3e58bf2aa10a5ae97734b59e48ea`
- Tree: `67bc8b44e363d4eaf0babf9e6ec1765ae976ea39`
- Build: `sh autoconf.sh`, `./configure --prefix=<isolated-prefix>`,
  `make -j2`, `make install`.
- Tests: `iverilog -g2001 -s tb -o sim MixedIndex.v tb.v`, `vvp sim`.

The upstream array-alias emitter checks that the alias destination is itself an
array before emitting an array alias. No compiler output or simulator source was
patched for this replay. Native RTL SHA-256:
`0fbcd6ae8d255f1023db23b1a51a54f27f108456baa0347b07c122688435f380`.
Testbench SHA-256:
`bc00b22416014f2e09139d73a160f1989429749d62ab94260bfe8d9edf124417`.

Local evidence is retained under
`/home/kartik/.local/state/morphhdl/lightweight-scala-rtl/`:
`mixed-index-repair/` retains the system-simulator failure;
`mixed-index-upstream-replay/results.json` records exact commands, executable
hashes and source hashes; `iverilog-build.log` records the tool build.
`mixed-index-formal/results.json` records three independent two-state SAT
comparisons and a corrupted-reference rejection. Those proofs do not establish
X/Z behavior; four-state simulation is a separate gate.

Before remote qualification, pin and authenticate the simulator build in the
affected workflows, run the same complete profile set on the final compiler
commit/tree, and retain fresh tool/source/evidence hashes. A local replay on a
development tree does not qualify a later candidate. Questa is unavailable in
this development environment and no Questa result is claimed.


The clean installer is `morphhdl/scripts/lightweight-scala-rtl/install-iverilog.py`.
It requires autoconf, gperf, bison, flex, make and a C++ compiler, builds into a
fresh isolated prefix, and records source commit/tree plus every installed file.
Its verification mode rejects missing/changed/extra files, modes and escaping
symlinks. A fresh local build and retained four-state array replay passed; logs
are `iverilog-installer-validation-v2.log` and `iverilog-installer-replay/` in the
durable evidence directory. The first failed attempt lacking gperf is retained.

The closed `suites.json` inventory and `run-contracts.py` enroll both Scala
versions under SBT and Mill. The runner requires a clean candidate, records
commit/tree and tool versions/hashes, and validates real XML reports for every
requested suite, rejecting missing, empty, skipped, failed and duplicate cases.
Report-parser tests and retained SBT/Mill XML replays pass; remote execution and
current-head source sealing remain required. These gates add coverage; they do
not replace inherited structural/process jobs or the full qualification.
