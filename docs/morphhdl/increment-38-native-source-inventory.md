# Increment 38 — Native-source inventory and zero-diff guard

## Objective

Turn the reviewed native SpinalHDL source-preservation audit into an executable,
machine-readable contract without changing parameterized elaboration, generated
RTL or concrete SpinalHDL behavior.

## Immutable source states

The manifest records exact Git commit and root-tree identities for the two
reviewed states:

- baseline commit `8c4241396cd718a36227dcd89a2e6a29d9077f11` with tree
  `34018854da821367a6af566e5034a86cd955d5b2`;
- approved post-Increment-37 planning state
  `36e3d3b4988c4b09670b327fd617f070223c3d9a` with tree
  `80bd849b93ff5cd4e7ac26ad7f54412f22667bc2`.

The approved state contains the same native source as Increment 37; the later
roadmap commit changed documentation only.

## Machine-readable classification

`morphhdl/contracts/native-source-preservation.json` covers every Scala source
change between those states under:

- `core/src/main/scala/spinal/core`;
- `idslplugin/src/main/scala/spinal/idslplugin`;
- `lib/src/main/scala/spinal/lib`.

The 23 reviewed paths are classified as:

- 8 `direct_edit` entries for modifications to native data, memory and library
  algorithms or entrypoints;
- 5 `morphhdl_sidecar` entries for MorphHDL metadata/capture files currently
  located inside native source trees;
- 10 `generated_backend_coupling` entries for native phase/emitter/plugin edits
  and MorphHDL generated-Verilog lowerers in native internal packages.

Each entry records its path, add/modify status, originating increment or
platform work and the reason it belongs to that class.

## Enforcement

`morphhdl/scripts/check-native-source-preservation.py` validates that:

1. the manifest schema, repository, roots, classifications and sorted path
   inventory are exact;
2. both recorded commits and root-tree hashes resolve exactly;
3. the baseline is an ancestor of the approved state and the approved state is
   an ancestor of the checked HEAD;
4. the Git name/status diff between baseline and approved state exactly equals
   the manifest inventory;
5. every `added` path is absent at baseline and every `modified` path has
   distinct baseline and approved blobs;
6. no committed native-source change exists after the approved state; and
7. the checked working tree contains no tracked or untracked native-source
   modification.

The script includes an isolated Git-repository self-test proving the positive
case and rejection of a dirty native file, a committed unapproved file, an
incomplete classification inventory and an incorrect tree hash.

## Continuous integration

`.github/workflows/morphhdl-native-source-guard.yml` runs the self-test and the
real repository check on pull requests and pushes to `main` and
`parameterized-verilog`. It checks out complete history so both immutable states
and every audited blob are available.

Future native-source changes therefore fail until a reviewed manifest update
records the new approved state and fully classifies its source diff. This guard
does not itself authorize such a change; the native-change approval rule in the
controlling roadmap still applies.

## Behavioral boundary

Increment 38 changes only documentation, repository contracts, a Python guard
and its workflow. It does not modify Scala implementation source, elaboration,
validation, Verilog generation, contract RTL or library behavior. Existing
Scala 2.12.18, Scala 2.13.12 and strict Verilog-2001 workflows remain the
behavioral regression authority.
