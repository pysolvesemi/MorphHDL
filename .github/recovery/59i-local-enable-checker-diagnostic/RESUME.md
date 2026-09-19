# Increment 59i targeted checker diagnostic continuation

Repository: `pysolvesemi/MorphHDL`; PR: #177.
Recovery controller branch: `recovery/increment-59i-history-20260914`.
Workflow: `.github/workflows/increment-59i-local-enable-checker-diagnostic.yml`.
Use the latest workflow run matching the controller commit that contains this file; the run ID is intentionally not written by another triggering push.

Exact checker source: `545134a42dd200c5cee679db5dde0fea0acb15c9`.
Exact checker source tree: `cab84063b2933dd146f2b5ff37aab42f7ea0b191`.
Exact main checker SHA-256: `e522d80a10040cc59e9c484e56573b15b85ff8d4041b4fe62d4720cf0b6f3a40`.
Preserved development bundle SHA-256: `65c033ddfbcad4583c664c38aae3f14e2215ea25d08818a1029752ad76e25105`.
Compressed development bundle SHA-256: `2bcac920ba36d9f7698c2fe5d5f6cee4186fd512b2eb4d97949db9ec71ddd3cf`.
Preserved development patch SHA-256: `480ca6ccd51aad84d4beeff7ccac052a6e674998a3adf27853853f7c782e9f63`.
Source history contains 11 preserved commits beyond qualified predecessor `90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6`.

This is an unsealed, retained-RTL diagnostic. It executes checkers from the exact source above against original Scala 2.13 RTL from `cb093b5aa235760f2c3149f4327324976aa0495b`.
It does not compile the current source, regenerate RTL, qualify a committed head, complete the TODO, or qualify full CI.
Input artifact: 10583436619 from run 35435278904, ZIP SHA-256 `a4589bfd9bf2b604828fe1c5ef6a0350823482278b6163488a5d0377a1f60e16`.
All 124 original HDL files and the earlier 138 test identities were verified; 64 upstream publication metadata files were missing, so that earlier artifact remains incomplete.
The prior checker run 35443564906 timed out on COUNT5, candidate 0, unsigned bit 0 after bit-level normalization. Its supplemental checker and unchanged-RTL check passed; the complete main phase was skipped after the priority failure.

Execution order: authenticate source and retained input; retry precisely that failed 18-step bit obligation; run the complete main checker only after it passes; always attempt supplemental and unchanged checks after valid input, including when priority or main fails.
A successful diagnostic requires all four phases, all 96 main cases/192 comparisons, all 1,024 original formal bit obligations plus 16 normalized-baseline obligations, both main emitted-RTL mutation counterexamples, and all six supplemental cases/24 comparisons plus its emitted-RTL mutation.
The proof keeps independent arbitrary initial state, native enabled reset at step 1, and unconstrained data/reset/global enable thereafter. The 18-step bound and 120-second SAT timeout are unchanged.
The priority phase uses exactly the same positive-proof helper as the complete main checker. It retains the original normalized netlist, the strengthened netlist, the actual RTL state correspondence, extraction/proof scripts and logs, and a finite-proof receipt. State equalities are additional proof obligations: original cells, input constraints and arbitrary initial state remain intact. The single priority result never substitutes for the complete main checker.

The requested monitoring cadence is once per hour after targeted CI starts. Inspect run and job outcomes read-only; while active, do not dispatch a duplicate or modify this controller merely to record a run ID. On terminal status, download and verify the ZIP digest, all retained file hashes, exact source identity, every required proof/script/log, mutations, and unchanged original RTL before taking the next authorized step.

## Authorized continuation

Preserve PR #177 as draft at feature head `90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6` on `agent/increment-59i-combined-reduction-closure`; target `parameterized-verilog` currently points to `e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d`. The only remaining failed-first gate is composite local-enable committed-head qualification. Do not count successful historical replacements as final-head passes.

After a narrow checker pass, run a fresh full current-source diagnostic: exactly 138 test cases in 10 suites on Scala 2.12.18 and 2.13.12, deterministic independent A/B RTL generation, complete main and supplemental hardware checks, strict tools, bounded proofs, and real emitted-RTL mutation rejection. The narrow diagnostic reuses cb093-generated RTL and cannot replace this fresh-source run.

Only after successful complete diagnostic evidence, construct a legitimate exact two-file schema4 source seal, then run all 21 source gates and publish preserved exact history through the prepared `publication-controller/` workflow. Preserve original commit SHAs, ordered parents, trees, author/committer identities and dates, prior certificates and source history. Never force-push. The first source-branch CI after publication is only the mandatory committed-head local-enable qualification.

After the remaining local-enable qualification passes on the exact committed head, run all 52 applicable final-head workflows through `full-ci-controller/`; inspect all 149 required jobs and 94 artifacts with `full-ci-monitor/`. No final qualification claim is allowed from old heads or controller commits. Only after every required final gate passes, complete the TODO and final qualification report, then merge PR #177. Avoid duplicate postmerge broad CI.

Use `59i-current-state.md` in the current workspace when available, then the immutable source bundle/payload, this controller and exact GitHub evidence when scratch is unavailable. The enabled hourly automation Continue 59i CI (ID6aae834192c8819195f25e56cdc525f7) continues at the user's requested cadence; update that task rather than creating a duplicate. Continue authorized diagnosis and minimal source fixes on terminal failures; regenerate diagnostic input after any generator/runtime changes. Keep all scope limitations and remaining work explicit.

The recovery commit also preserves the inactive next full diagnostic under `.github/recovery/59i-next-development/`; promote its prepared workflow/controller only after this narrow diagnostic passes. The seal, exact-history publication, complete CI dispatcher and final-run monitoring toolkit are preserved under `.github/recovery/59i-continuation-toolkit/`. These inactive files are recovery material and do not represent a dispatched run or source qualification.

Use the successor finite-proof verifiers for the repaired source: verify-local-enable-checker-diagnostic-v3.py for the narrow run and verify-local-enable-diagnostic-successor-v2.py for the fresh full source diagnostic, with verify-local-enable-diagnostic-v5.py and the unchanged historical input verifier beside them. Exact source/checker hashes must be supplied. Require all17base-case results and terminal17-step marker for steps2through18, the additive-only strengthened netlist and complete state correspondence for every obligation. Historical v2/v4 inspectors retain their original proof format and cannot qualify this successor.
