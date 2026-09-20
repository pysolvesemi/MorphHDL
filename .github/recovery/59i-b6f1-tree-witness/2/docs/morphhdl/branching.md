# Development branch policy

`main` remains the stable private fork baseline. True parameterized-Verilog
development is integrated on the long-lived `parameterized-verilog` branch and
merged to `main` only after the complete release gate passes.

## Increment workflow

Each increment uses a short-lived branch:

```text
agent/increment-N-description -> parameterized-verilog
```

Each increment pull request must contain its implementation, focused tests,
generated examples when applicable, compatibility impact and validation
results. Direct implementation commits to `parameterized-verilog` are avoided;
only integration-branch administration may be committed directly.

After all planned Verilog increments pass, one final pull request merges:

```text
parameterized-verilog -> main
```

## Upstream synchronization

Public upstream updates are first merged into private `main` using the procedure
in `upstream-sync.md`. The resulting private `main` merge is then merged into
`parameterized-verilog` through a dedicated synchronization pull request.

Do not rebase the long-lived integration branch after other contributors begin
using it. Merge commits preserve the public upstream ancestry and incremental
review history.
