# Finite proved-state evidence inspector

This successor inspects source `545134a42dd200c5cee679db5dde0fea0acb15c9`, tree `cab84063b2933dd146f2b5ff37aab42f7ea0b191`, main checker SHA-256 `e522d80a10040cc59e9c484e56573b15b85ff8d4041b4fe62d4720cf0b6f3a40`. It does not execute HDL tools or qualify source by itself. Historical inspectors remain unchanged.

## Dependencies to retain together

- `verify-local-enable-checker-diagnostic-v3.py`
- `verify-local-enable-diagnostic-v5.py`
- `verify-local-enable-diagnostic-successor-v2.py`
- Existing `verify-local-enable-diagnostic.py` for the original nested cb093 artifact.
- Matching controller directories, exact Git source history, downloaded artifact ZIPs and separately obtained GitHub archive SHA-256 digests.

The v3 narrow inspector requires the exact current controller's `payload.json`, `checker-config.json`, and `input-controller/{payload.json,expected-suites.json}`. The full successor requires the separately built matching full controller's `payload.json` and `expected-suites.json`.

## Narrow retained-RTL inspection

Replace ZIP, ZIP_DIGEST, and unique output paths with the actual downloaded artifact values. Use `--mode success` only when the full narrow run claims success; use `--mode failed` for terminal failure evidence. Failed mode never promotes evidence to a pass.

```sh
python3 -B verify-local-enable-checker-diagnostic-v3.py \
  --mode success --artifact ZIP --github-sha256 ZIP_DIGEST \
  --extract-root evidence/UNIQUE-narrow-extraction \
  --output evidence/UNIQUE-narrow-inspection.json \
  --repo-root 59i-dev --controller controller-checker-diagnostic-545134a4 \
  --source 545134a42dd200c5cee679db5dde0fea0acb15c9 \
  --checker-sha e522d80a10040cc59e9c484e56573b15b85ff8d4041b4fe62d4720cf0b6f3a40
```

## Fresh full diagnostic inspection

The retained-RTL run does not replace this fresh two-Scala diagnostic. This command is for artifacts produced later by the matching prepared full controller, after narrow evidence passes.

```sh
python3 -B verify-local-enable-diagnostic-successor-v2.py \
  --mode success \
  --artifact 2.12.18 ZIP_212 ZIP_DIGEST_212 \
  --artifact 2.13.12 ZIP_213 ZIP_DIGEST_213 \
  --extract-root evidence/UNIQUE-full-extraction \
  --output evidence/UNIQUE-full-inspection.json \
  --repo-root 59i-dev --controller controller-development-v6 \
  --source 545134a42dd200c5cee679db5dde0fea0acb15c9 \
  --tree cab84063b2933dd146f2b5ff37aab42f7ea0b191 \
  --checker-sha e522d80a10040cc59e9c484e56573b15b85ff8d4041b4fe62d4720cf0b6f3a40
```

## Added qualification checks

Every one of the 1,024 selected matrix output-bit obligations and 16 normalized positive baseline obligations requires all nine model/script/log/receipt files. The inspector regenerates the exact miter, extraction script, structural pairing and strengthened document from reviewed source. It independently checks that all original cells, ports, initialization metadata and connections survive unchanged; only the additional XOR/OR property cells and `bad` binding may differ. New property signals must not alias any original cell, port or retained netname bit.

Proof scripts must read that exact strengthened model and contain only the reviewed Yosys 0.41 finite base command with prefix 1 and lengths 1 through 17. Every ordered base success must appear exactly once, the terminal finite-success marker must follow the last base, and timeout, failure, skipped-proof or error text rejects success. These lengths prove steps 2 through 18 after the enabled native reset at step 1. All inputs remain free afterwards. State equalities are additional proved obligations. There is no initial-state correlation, cutpoint assumption, weakened time bound or unbounded-induction claim. Both genuine emitted-RTL mutation checks remain required.

The narrow priority receipt includes the shared helper's complete proof result and SHA-256 hashes of all nine files. The original nested cb093 upload remains incomplete by exactly its 64 known publication metadata files; it is never relabeled complete. Every current upload must have complete file-hash coverage in both success and failed modes.

## Local verification and limits

All three new Python files parse, the exact committed source/controller/checker binding passes, and a synthetic schema fixture using the actual 657-cell priority normalized model reproduces 71 independent state relations. Negative checks reject omitted or duplicate bases, a premature terminal marker, timeout or skipped-proof text, changed original cells, and signal aliasing. The original 86f6 timeout log is rejected. These are schema/guard checks only: no SAT solver executed and no current diagnostic or qualification pass is claimed. The explicitly scoped report is `evidence/finite-verifier-schema-negative-checks.json`.
