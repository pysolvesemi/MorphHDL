# Follow-up tools for sealed transport repair268cdc63

Active staging run35473599529 uses immutable controller792aa785b10a6502a80b342a27a45cde985091b0.
Its checkout passed; source checks are still pending. No repaired feature update,
committed-head retry, full-CI dispatch, completion or merge has happened yet.
These files are inactive continuation tools. Publishing them with[skip ci]
must not dispatch another staging/runtime/full-CI run.

verify-59i-transport-staging.py independently validates actual downloaded
source, commit or dispatch ZIPs against their separately fetched API digest.
Use --controller pointing to the exact publication-controller-transport-ready
payload from792aa785, --artifact ZIP --sha256 API_DIGEST --phase source|commits|dispatch,
--extract-root NEW_DIRECTORY --output NEW_JSON. It requires the original25
commands, original parent audit, diagnostic identities, exact bundle and all
three preserved commits/tree requests. It never writes a remote object/ref.
Before any handoff also verify that the actual staging job is waiting for that
handoff, the controller SHA is792aa785, and live feature/target/PR are unchanged.

The committed-head artifact inspector is separately pinned to268cdc63/tree9c78547.
Use its --run-json and --jobs-json inputs from fresh raw GitHub REST responses,
--source-artifact ZIP SHA256, one --artifact SCALA ZIP SHA256 per lane,
--repo-root exact source checkout, --extract-root NEW_DIRECTORY and --output NEW_JSON.
Neither this inspector nor the hardware-only helper promotes diagnostic
metadata into committed-head qualification. The existing failed9de3run and
seven foreign-head/failed/skipped/diagnostic/attempt controls were rejected.

The prepared full-CI packages are planning-only:
build_59i_transport_ci_mapping.py,59i-transport-full-ci-workflow-map.json,
full-ci-controller-transport/ and full-ci-monitor-transport/.
They were prepared from the clean sealed268cdc63source with zero network requests.
Expected requirements remain53workflows,150requiredjobs,2intentionalpublisher
skips,95artifacts,2307tests/230suites. The dispatcher now uses the direct target
ref to establish4b8a86 and still enforces the exact feature ref/head, both branch
names, repository and draft/open PR identity; it tolerates only stale PR
base.sha metadata. All original12 API-failure/reuse controls plus2current
target-ref controls passed (14total, all mocked; no source or CI proof credit).
No POST/full-CI run was issued. Preserve every historical prerequisite and
dispatch journal. The full controller must not be activated until actual
source+bothScala+crossScala committed-head qualification and evidence succeed.

After that success refresh refs, workflow hashes and the map/manifest/plan as
needed, then publish the existing full-CI workflow template in plan mode on the
existing recovery branch, preserving a journal for every eventual dispatch.
Do not start another feature branch, drop source/proof/CI gates, send messages,
or duplicate successful/active runs. Keep the existing hourly automation current.
