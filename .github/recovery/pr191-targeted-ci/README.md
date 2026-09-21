# PR191 targeted CI launcher

The user authorized the existing 59i controller approach for PR191 on September
21, 2026, superseding the earlier handoff restriction on adding a launch route.
This separate recovery branch contains orchestration only. It does not modify
PR191's feature branch, source seal, target branch or the existing 59i controller.

The manifest pins the repaired commit and tree, target, eight failed workflows,
their exact YAML bytes, and the original failing run for each. The workflow
authenticates the repaired source seal before invoking the dispatcher. The
dispatcher uses GitHub's ordinary workflow_dispatch API with the runner token
and actions:write. It has no source/ref writes or arbitrary API-write capability.

It checks live refs before every dispatch, reuses active/successful exact-head
runs, and stops for exact-head failures or duplicate runs. It records and fsyncs
an intent before each POST, never retries a POST, and retains the journal even
on failure. It refuses controller retries or a previous controller run. A partial
or uncertain launch must be reconciled from the journal and actual GitHub runs
before a separately reviewed continuation is published.

Dispatch acceptance is not test success. The hourly monitor verifies actual
head/run identities, every required job and retained evidence. Full CI and merge
remain subject to the existing completion requirements. The old pass-workspace
run is left running; this launcher targets only the eight diagnosed failures.
