# Successor retained-RTL controller review

Source `86f6e3d9c7c4dfbd629533668a6f5dfe296bf602`, tree `4ade045ae6d8debdf933a1d965a934a686276e6e`; main checker SHA-256 `b41a076012e91c4a95829dabd2db84fe617be9416d36246faf4aba1e7461b8b7`.

Ready payload: `controller-checker-diagnostic-86f6e3d9/`. Its build-summary lists all 14 publication files, including four source bundle parts, durable resume instructions, payload, checker config and build summary. The emitted RTL remains original cb093 Scala 2.13 evidence. No current Scala compilation, RTL generation, sealing, qualification, full CI, commit, or dispatch was performed by this preparation.

The controller retries precisely COUNT5/candidate0/unsigned0 first, with the same 18-step proof and 120-second timeout. It records the normalized netlist, miter, script, log and exact hashes. A failed priority skips the expensive main phase; supplemental and original-RTL checks run after valid input regardless of priority/main outcome. A passing priority always leads to the complete unchanged main case inventory, all 1,024 bit obligations and 16 normalized-baseline obligations, and real mutation checks. No reduced proof is a substitute.

Validation completed: isolated exact-source bundle reconstruction and preserved 308-file predecessor audit; clean resulting source; Python AST, workflow YAML and every embedded Bash step; six synthetic, temporary-only verifier rejection checks (changed bound, added init assumptions, duplicate SAT marker, wrong bit, wrong source, changed normalized netlist). No local HDL execution or synthetic runtime evidence was claimed. Historical cb093/6a verifiers were not edited.

## Narrow artifact verification

Replace ZIP, GitHub digest and fresh output paths with actual downloaded values. Use `--mode failed` on a failed run. This verifier permits a missing main-phase receipt only after nonzero priority, and still requires complete upload hash coverage and unchanged original RTL. Every claimed-passing phase must have complete valid evidence, even in failed mode.

```bash
python3 -B verify-local-enable-checker-diagnostic-v2.py \
  --source 86f6e3d9c7c4dfbd629533668a6f5dfe296bf602 \
  --checker-sha b41a076012e91c4a95829dabd2db84fe617be9416d36246faf4aba1e7461b8b7 \
  --repo-root 59i-dev \
  --controller controller-checker-diagnostic-86f6e3d9 \
  --artifact ZIP --github-sha256 SHA256 \
  --extract-root evidence/86f6-checker-verified \
  --mode success --output evidence/86f6-checker-verification.json
```

## Fresh full diagnostic verification

The small wrapper binds the existing reviewed per-bit proof inspector to explicit source, tree and independently supplied checker digest. Both success and failure modes require all current-upload inventory entries, including hidden publication files. It does not weaken historical checks or execute HDL tools. Both lanes are required for a successful full diagnostic conclusion.

```bash
python3 -B verify-local-enable-diagnostic-successor.py \
  --source 86f6e3d9c7c4dfbd629533668a6f5dfe296bf602 \
  --tree 4ade045ae6d8debdf933a1d965a934a686276e6e \
  --checker-sha b41a076012e91c4a95829dabd2db84fe617be9416d36246faf4aba1e7461b8b7 \
  --repo-root 59i-dev --controller controller-development-v5 \
  --artifact 2.12.18 ZIP212 SHA256212 --artifact 2.13.12 ZIP213 SHA256213 \
  --extract-root evidence/86f6-full-verified \
  --mode success --output evidence/86f6-full-verification.json
```

Durability: keep the narrow verifier alongside `verify-local-enable-diagnostic-v4.py` and `verify-local-enable-diagnostic.py`; keep the full wrapper alongside `verify-local-enable-diagnostic-v4.py`. Preserve their filenames because imports are explicit. Supply the restored controller directory to each command. The user requested hourly monitoring after the next targeted run starts; root owns that automation and all remote publication/dispatch.
