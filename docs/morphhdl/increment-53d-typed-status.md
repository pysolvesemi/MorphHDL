# Increment 53d typed-elaboration status

- Recorded: 2026-08-29T19:42:18Z
- Reconciliation run: 33271474028
- Run status: completed
- Run conclusion: failure
- parameterized-verilog SHA: 242b2fa396c6f8a722641da176f84dc6b26b00f3
- typed candidate SHA: 242b2fa396c6f8a722641da176f84dc6b26b00f3
- commits ahead: 0

## Candidate changed files
```text

```

## Failed-step tail
```text
reconcile	Ensure typed roadmap is merged	﻿2026-08-29T19:40:54.0941991Z ##[group]Run set -euo pipefail
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0942366Z ^[[36;1mset -euo pipefail^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0942659Z ^[[36;1mgit fetch origin parameterized-verilog^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0943265Z ^[[36;1mif ! git show origin/parameterized-verilog:docs/morphhdl/typed-elaboration-architecture.md >/dev/null 2>&1; then^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0944148Z ^[[36;1m  git checkout --detach origin/parameterized-verilog^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0944782Z ^[[36;1m  cp /tmp/typed53d/docs/morphhdl/parameterized-verilog-todo.md docs/morphhdl/parameterized-verilog-todo.md^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0945614Z ^[[36;1m  cp /tmp/typed53d/docs/morphhdl/typed-elaboration-architecture.md docs/morphhdl/typed-elaboration-architecture.md^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0946175Z ^[[36;1m  git diff --check^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0946447Z ^[[36;1m  git config user.name 'MorphHDL Agent'^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0947145Z ^[[36;1m  git config user.email 'morphhdl-agent@users.noreply.github.com'^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0947930Z ^[[36;1m  git add docs/morphhdl/parameterized-verilog-todo.md docs/morphhdl/typed-elaboration-architecture.md^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0948636Z ^[[36;1m  git commit -m 'Adopt typed elaboration architecture from Increment 53d'^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0949168Z ^[[36;1m  git push --force origin HEAD:agent/typed-elaboration-roadmap-approved^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0950008Z ^[[36;1m  number="$(gh pr list --head agent/typed-elaboration-roadmap-approved --base parameterized-verilog --state open --json number --jq '.[0].number // empty')"^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0950724Z ^[[36;1m  if [ -z "$number" ]; then^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0951837Z ^[[36;1m    url="$(gh pr create --head agent/typed-elaboration-roadmap-approved --base parameterized-verilog --title 'Adopt typed elaboration architecture from Increment 53d' --body 'Makes the typed ElabInt/ElabBool architecture authoritative for Increment 53d and all later parameterization work.')"^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0952942Z ^[[36;1m    number="${url##*/}"^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0953188Z ^[[36;1m  fi^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0953812Z ^[[36;1m  gh pr merge "$number" --squash --admin --subject 'Adopt typed elaboration architecture from Increment 53d'^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0954302Z ^[[36;1mfi^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0954540Z ^[[36;1mgit fetch origin parameterized-verilog^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0955266Z ^[[36;1mgit show origin/parameterized-verilog:docs/morphhdl/typed-elaboration-architecture.md | grep -Fq 'Approved production architecture from Increment 53d onward'^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0956438Z ^[[36;1mgit show origin/parameterized-verilog:docs/morphhdl/parameterized-verilog-todo.md | grep -Fq 'Increment 54 — Typed StreamFifo depth and structural-domain validation'^[[0m
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0957436Z shell: bash --noprofile --norc -e -o pipefail {0}
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0957733Z env:
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0960407Z   GH_TOKEN: ***
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0960678Z   XDG_CACHE_HOME: /tmp/morphhdl-cache
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.0960959Z ##[endgroup]
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.4349017Z From https://github.com/pysolvesemi/MorphHDL
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.4349558Z  * branch                parameterized-verilog -> FETCH_HEAD
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.5789643Z HEAD is now at 242b2fa39 Increment 53d: parameterize native StreamWidthAdapter
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.6131806Z [detached HEAD df172ad3b] Adopt typed elaboration architecture from Increment 53d
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.6132657Z  2 files changed, 345 insertions(+), 127 deletions(-)
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:54.6133300Z  create mode 100644 docs/morphhdl/typed-elaboration-architecture.md
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:55.7771914Z To https://github.com/pysolvesemi/MorphHDL
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:55.7772715Z    242b2fa39..df172ad3b  HEAD -> agent/typed-elaboration-roadmap-approved
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:55.7826509Z /__w/_temp/6b766162-3bfa-4b0c-9947-e503360203e9.sh: line 13: gh: command not found
reconcile	Ensure typed roadmap is merged	2026-08-29T19:40:55.7870005Z ##[error]Process completed with exit code 127.
```
