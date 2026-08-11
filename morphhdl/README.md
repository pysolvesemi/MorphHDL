# MorphHDL fork integration

This directory contains MorphHDL-specific repository metadata and tooling. The
inherited compiler and library sources remain in their existing directories so
that updates from the public upstream repository can be merged with a small,
reviewable conflict surface.

The current upstream base is recorded in `upstream-base.conf`. Every upstream
synchronization must update that file in the same pull request as the merge.

Fork-specific implementation should follow these rules:

- Keep the existing concrete generation entry points behaviorally unchanged.
- Add new behavior through explicit MorphHDL entry points or isolated modules.
- Avoid mechanical renames of inherited packages and files.
- Preserve inherited license and copyright notices.
- Never update the recorded upstream commit without running the baseline gate.

See `docs/morphhdl/upstream-sync.md` for the synchronization procedure.
The parameterized-Verilog architecture contract starts at
`docs/morphhdl/architecture/README.md`.
