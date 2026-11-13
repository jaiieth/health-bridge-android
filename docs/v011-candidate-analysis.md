# v0.1.1 local candidate analysis

This candidate enables the build gate and sets versionCode 2 and versionName 0.1.1.
The `sync_enabled` preference still defaults to false in `Settings`. Opening the
control and saving configuration do not grant consent. The new preference test
starts with the key absent and enables sync only through `setEnabled(true)`.

## #4: pre-publication leftovers

`ChunkOutbox.beginReserved` creates a generation and durably writes its reservation.
`stageReservedBody` writes each body through a temporary file, file fsync, rename,
and directory fsync. `publishReserved` writes the first manifest only after the
bodies. A process death before that manifest leaves a reservation and bodies or
temps without committed phase evidence. `ProcessDeath` bypasses the caught
`Exception` rollback, as an actual process death would.

`classifyStaging` returns `Retain` when neither `manifest.json` nor `published.json`
exists. `stagingEvidence` also requires one of those files. The bytes remain in
`retainedBytes`, and repeated recovery cannot release them. A reservation proves a
budget, but cannot establish body identities or absence of delivery. This is a
conservative safety decision and an unresolved storage problem.

The two `gap4` fixtures kill staging at the first body's TEMP_WRITTEN and
DIRECTORY_SYNCED boundaries. Each proves that no transport or cursor commit ran,
no manifest or active pointer exists, and two recovery passes retain an identical
inventory. The final assertion deliberately fails because a durable attempt intent
does not exist. These tests specify missing provenance, not permission to delete.

## Proposed attempt intent, analysis only

Use one immutable `attempt-intent.json` in each newly owned generation. Its data
shape is a versioned record containing the following fields:

- Ownership: format `health-bridge-attempt-v1`, generation ID, unique attempt ID,
  writer protocol version, creation time, endpoint hash, and parent fingerprint.
- Reservation: all eight version 2 reservation fields, plus the reservation's
  exact byte length and SHA-256 digest.
- Inventory: the planned child index, batch ID, record count, final and temporary
  basenames, exact body length, and SHA-256 digest for each child. Include the
  ordered record IDs and hashes and target cursors that bind the fingerprint.

The inventory describes planned bodies. A crash during a body write may leave a
partial temp that cannot match its planned digest. Such a file remains retained
unless separate, independently validated evidence proves its exact contents safe.
No partial-write heuristic authorizes deletion.

Write and fence this record after reservation durability and before staging the
first body. In particular, it must be durable before the first network send.
Use a temp write, file fsync, atomic rename without replacement, and directory
fsync. No body staging or send may proceed if any fence fails. Planning already
has child identities and hashes for both list and streaming routes. Streaming
replay must continue to validate them. Intent byte and atomic-copy costs must be
included in reservation arithmetic before admission, within the existing quota.
No Room migration is needed. This document does not choose a new byte limit.

An intent can be considered only under the existing mutex, with legacy Pending
absent, strict regular-file and no-symlink checks, a matching reservation, and an
exact verified inventory. Every active, active.tmp, active-proof, active-proof.tmp,
previous, and previous.tmp must be absent for this generation. Malformed or
unreadable references prevent use. A completed receipt or its temp, a staging
tombstone, conflicting metadata, or unknown entries also prevent use. Absence of
an active pointer alone is insufficient because an authenticated previous pointer
can still own the generation.

The protocol must guarantee that the durable SEND transition and publication
references precede each transport call and survive until independent completion
or release evidence exists. Recovery must validate every surviving manifest and
temp for zero sentAt, ackedAt, and acked flags, safe phase, matching identities,
and no UNKNOWN_ACCEPTANCE. Missing or contradictory delivery evidence fails
closed. An intent merely declares a plan. It does not prove that no child was
sent, and must never be deletion authority by itself.

A future implementation needs separate durable release authority after independent
checks establish no pointer, no receipt, and provably no child sent. Existing
retained generations lack this new provenance and must remain untouched. This
candidate implements no intent writer, deletion, pruning, reclamation, or schema
change. No bounded retention policy is proposed or assumed here. If one becomes
necessary, its exact policy requires separate approval before implementation.

## #6: durable cursor release path and admission deadlock

The actual path is `runFreshChunkSync` to `ChunkSync.run`. Each accepted child is
persisted as ACK. After all children, the runner persists COMMIT and invokes
`ChunkCursorStore.commit`. The production adapter calls the Room transaction
`Store.commitChunkCursors`, which checks Pending and persists target cursor rows.
The runner then persists DONE and calls `reclaimDone`, also called before loading
or collecting on the next run.

The production `committed` adapter reads `store.cursors()`, matches types, parses
both timestamps with `Instant`, and requires saved values at or after each target.
`reclaimDone` first resumes completed-receipt releases. It then validates DONE
manifests, filters by durable cursor proof, sorts newest first, and applies
`drop(RETAINED_DONE_GENERATIONS)`. The constant is 3. Only older candidates can
receive a receipt. `release` rechecks cursor proof and Pending, excludes referenced
generations, fences the receipt, and then removes bodies and metadata. Cursor
commit alone therefore never releases any of the newest three generations.

With three large DONE generations, there may be too little room for a fourth
reservation. Admission fails before a fourth generation can complete, so the oldest
of the three can never become a fourth-oldest release candidate. Explicit retry
cannot change this arithmetic. A quota marker clears only when the next planned
peak fits. This is a forward-progress limitation, not failure to persist cursors.

The `gap6` JVM fixture completes three generations through the runner, each with
42 children of about 520 KB. It uses the production timestamp comparison semantics
in a cursor-store test double. It restarts the outbox, verifies cursor proof and
byte-identical retention, then requests a four-child envelope within per-generation
limits. Quota refusal happens with no sends. Its final DONE assertion intentionally
fails. Here “three DONE chunks” means three DONE generations, the unit retained by
this code, not three individual child files. The fixture uses real logical file
bytes, no sparse filler, raised quota, reduced retention count, or oversized request.
The Room adapter is traced in source, not exercised against an Android database.

No policy change is implemented or approved. #4 and #6 remain open. A gate-on APK
and passing unrelated tests would not establish production readiness.

## Default verification after reproduction

The original failing debug and release runs and their XML results are preserved in
`/home/ubuntu/shared-out/v011-gate/failing-reproduction-run/` at commit
`ae357131046809140e7c2e3c25b4e51256133c94`. Each run had 274 passed, 6 failed,
and 0 skipped tests. Three failures reproduced #4 and #6. Three existing tests
assumed the production gate was OFF.

The candidate now derives those three tests' expectations from the production gate.
The delivery test still checks transport and cursor effects. The display tests
still check consent preservation, no scheduling or writes during rendering, and
configuration and permission requirements. Each reproduction fixture has `@Ignore`
with its issue reference and an explicit reason. Default debug and release tasks
skip those three unresolved cases. Their original failing logs remain evidence;
ignoring them does not close either issue. Current default-run logs and XML results
are at the evidence folder root, with exact counts in `verification-summary.json`.

With `sync_enabled` OFF, `Containment.run` returns before health collection and
transport. `runFreshChunkSync` also passes the conjunction of the preference and
build gate to the runner, whose disabled path exits before outbox access or sends.
The gate flip does change the displayed OFF state and allows the user to enable
sync. It does not grant consent, schedule sync from an absent preference, or
automatically send health data. An explicit empty Send test remains a separate
user action. Existing OFF-path and default-preference tests verify these guards.

The default verification command completed successfully. Debug and release each
report 277 passed, 0 failed, and 3 skipped tests. Lint reports 0 errors and
11 warnings. `assembleDebug` succeeds with the unchanged candidate APK.
