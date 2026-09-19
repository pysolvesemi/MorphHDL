# 59i committed-head startup repair

Continue PR177 in pysolvesemi/MorphHDL on the existing feature
agent/increment-59i-combined-reduction-closure, target parameterized-verilog.
This checkpoint supersedes the old publication handoff at400fb3b8.

The old staging35465640896 succeeded and published seal9de3fd243a28a6d1e6ba385bc2d746b57e337fc1.
Committed-head run35468824371 then failed: source review succeeded, but both
Scala lanes stopped before compilation because SBT's Unix-domain socket path
was127bytes. No XML tests, hardware generation or hardware proofs ran.
All three original ZIP digests and head identities were independently checked;
see failure-and-repair-review.json. Do not rerun this unrepaired head.

Repair lineage (all exact, [skip ci]):
1. a44e08786bfa603a437245718d60a5631af6cb05 shortens the per-Scala JVM temporary
   directory to /tmp/59i-$SCALA_VERSION and retains/authenticates tmp-path.txt.
2. 8c634b150c778607b280dd6831095dd6e474a4c1 corrects the historical merge-parent
   metadata, advances every dependent reviewer pin, and adds an independent
   Git-parent assertion to the existing integration controls.
3. 268cdc63ca5b12177e01b8715688a6e001dff06f is the exact two-file production seal,
   tree9c78547d73fdf08a8c9fbb7f1d836ba4af2c1ad4.

Manifest SHA256ee52b48dec8bd07079232dc2838c46a3349b1035f3b7e486dffe4d730c504edf,
normalized helper44cddecca2f07d3ea00b8bcbb1d66d502e5fc1ea564b1ee3762f95479d6c015c.
All345 source records remain (330 exact reuse,15 reviewed updates), all40
current target records remain (36 exact reuse), zero historical records removed.
The original9de3 certificate is replayed independently, preserving its complete
58fb/schema4 and older certificate chain. Actual PR190 merge parents remain
58fb59773a2deebba0251b5b626c19a22453f0a4 and4b8a86e25f5a1a3f0cb4c37dc537a8dd8aa7b097.
The rejected unpublished provisional seal5c3b39416 remains in its original
local worktree for diagnosis; do not publish it. No published history was rewritten.

The workflow differs only in temporary-path setup and reuse. All test identities,
proof bounds, mutations, deterministic generation, source checks and timeouts
remain unchanged. The calculated socket path becomes65bytes; actual SBT/Scala
execution is pending CI. Local socket creation is unavailable in this runtime.
All14 current integration tests passed, including the new independent parent
assertion. The publication builder verifies1847 runtime/build/resource/hardware
checker files and one gitlink equal the successful fresh diagnostic35458181915
at sourceccec54986c70077e361f291cd322f0aa547aee15. Diagnostic evidence is preserved
in the earlier publication commit400fb3b8; it is not committed-head qualification.

The new publication controller retains all25 mandatory source commands,
budgets, runtime equality checks, exact raw commit metadata and explicit
connector ref handoff. Only its published BASE9de3 and current helper identity
advance. Exact bundle SHA25640ac2ff1d6a0602f95fa904091af4c4c8d794e371b99f2e9733571bdec0e3aa7.

Next: identify the staging run on the recovery commit containing this checkpoint.
After25 source commands pass, download the source-and-tree-requests artifact,
compare its ZIP SHA256 with the Actions artifact API, and independently verify
the payload, source/seal, exact three-commit history, command/returncode/log hashes,
and tree requests. Create only the requested exact trees in order with the GitHub
connector. Do not advance feature until exact-commit-staging receipts have been
downloaded, hash-checked and compared to every exact commit/tree/ordered parent.
Refresh live refs and PR, then non-force fast-forward feature from9de3 to268cdc63.
Complete both actionable handoffs within the controller's60minute windows.
If it times out, diagnose and refresh all live state before any retry; never
duplicate a run or weaken a gate. The controller alone dispatches only the
mandatory increment-59i-local-enable-committed-head.yml gate on the repaired head.

After dispatch, monitor every hour through the existing automation
6aae834192c8819195f25e56cdc525f7. Keep PR draft and TODO unchecked. Require source,
both Scala lanes and cross-Scala jobs to pass on the exact repaired head. Use
verify-local-enable-committed.py with fresh raw run/jobs JSON, the source ZIP and
both Scala ZIPs plus independently fetched digests, a new extraction directory,
and the exact source repository. The inspector shares only pinned hardware
validation functions from verify-local-enable-diagnostic-v5.py; it explicitly
rejects diagnostic run metadata. It requires138 actual tests/10suites each,
124 original deterministic RTL files,192 main native comparisons,32 bounded
18-step cases/1024 output-bit+16 baseline obligations,24 supplemental comparisons,
2 main+1 supplemental actual mutants, exact compiled index and cross-Scala
identity. Supplemental formal remains not-run; no unbounded proof is claimed.

Only after actual committed-head qualification succeeds may full final-head CI
run. Refresh the versioned full-ci-controller-pr190, full-ci-monitor-pr190 and
build_59i_pr190_ci_mapping.py from the preserved120-file continuation toolkit at
8c5e7735827a847ebb24499e3ed74cc1321ac9d9. Preserve53 applicable workflows,
150 required jobs,2 intentional publisher skips,95 artifacts,2307 tests/230suites.
Fix the dispatcher PR base.sha freshness check using the authoritative live
target ref, while retaining exact target/head/PR guards. Reuse active/successful
same-head runs, preserve uncertain dispatch journals and retry only concretely
diagnosed failed jobs. Complete/merge only after every required final-head gate
and retained evidence passes. Report actual Scala/generated-Verilog then;
the current transport repair itself has no generated-Verilog effect. Avoid
duplicate broad postmerge CI. Do not message others or create a new increment.
