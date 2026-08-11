# Upstream synchronization

MorphHDL keeps the full public SpinalHDL history and applies MorphHDL changes on
top of a recorded upstream commit. The private default branch is `main`; the
public upstream branch is `dev`.

## One-time local setup

```bash
git remote rename origin upstream
git remote add origin https://github.com/pysolvesemi/MorphHDL.git
git fetch --all --prune
```

The expected remotes are:

```text
origin    https://github.com/pysolvesemi/MorphHDL.git
upstream  https://github.com/SpinalHDL/SpinalHDL.git
```

Run the read-only status check with remote comparison:

```bash
./morphhdl/scripts/check-upstream-base.sh --check-remote
```

## Updating from upstream

1. Start from an up-to-date private `main` branch.
2. Fetch `origin` and `upstream`.
3. Create a dedicated `agent/upstream-sync-YYYYMMDD` branch.
4. Merge `upstream/dev` with a merge commit; do not squash or rebase the
   inherited history.
5. Resolve conflicts by preserving the existing concrete behavior and keeping
   MorphHDL-specific code isolated.
6. Update `morphhdl/upstream-base.conf` to the merged `upstream/dev` commit.
7. Run the baseline workflow and all MorphHDL tests.
8. Open a pull request to private `main`. Never force-push `main` to match
   upstream.

Example commands:

```bash
git switch main
git pull --ff-only origin main
git fetch upstream dev
git switch -c agent/upstream-sync-YYYYMMDD
git merge --no-ff upstream/dev
```

After the merge, update the manifest and verify it:

```bash
./morphhdl/scripts/check-upstream-base.sh --check-remote
```

## Conflict policy

- Inherited source without a MorphHDL change should follow upstream.
- MorphHDL code should normally live in new files or narrowly defined adapter
  points.
- Changes to existing core files require a short explanation in the pull
  request because they increase future merge cost.
- License files and inherited copyright notices must not be removed.
- A synchronization is incomplete until concrete generation and MorphHDL CI
  both pass.
