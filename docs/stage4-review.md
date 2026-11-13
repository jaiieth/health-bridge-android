# Stage 4 integration candidate

Scratch clone only, based on b387fea. Applies 6fdc468 on top of containment
b06171f, UI 9bc3264 and scheduling fix b387fea. No remote was added.
This candidate is for CTO review. It is not applied to the canonical branch
and is not shippable.

## Integration decisions

Git reported no textual conflicts. SyncWorker.kt merged automatically;
Containment.kt applied cleanly; MainActivity.kt was unchanged.

A semantic conflict existed in Containment.run: chunking put the OFF guard
before the isolated empty Test branch. The integration puts that guard inside
the normal-health branch, preserving containment's explicit Test bypass while
OFF. The original OFF empty-Test test is restored. No health collection or
chunk transport is available through the Test branch.

There is one Settings.syncEnabled consent key. MainActivity's SyncScheduler
adapter connects SyncControl to Schedule. Opening the UI and saving settings
while OFF schedule nothing. Turning OFF persists false before cancelling health
work. A normal worker while OFF writes Sync OFF before reading Pending or
credentials. An explicit Test is the existing exception to health consent.

Turning ON persists consent and schedules periodic work. SyncWorker holds
AppState.mutex and invokes Containment with runFreshChunkSync as its only health
delivery callback. Retained legacy Pending blocks that callback read-only.
The isolated empty Test can still run without touching Pending or the outbox.
Legacy Transport.send and Store.success remain unchanged and have no production
callers.

ChunkSync saves each ACK durably before saving COMMIT and calling
Store.commitChunkCursors. That DAO method checks Pending and writes changed
cursor targets inside one Room transaction. Generated Store_Impl.java uses
RoomDatabaseKt.withTransaction. A restart after cursor commit but before DONE
can invoke the transaction again, but matching values prevent another cursor
write. This is idempotent advancement, not a guarantee of one DAO invocation.
The ACK files and Room commit are separate durability domains.

One added wiring test connects SyncControl.setEnabled(true), the stored consent,
the scheduled interval, Containment and successful synthetic chunk delivery.
It reuses the existing settings fixture. It does not instantiate WorkManager,
the Android worker, Health Connect or the production filesystem adapter.

## Work sequence

- Blocking first steps: verify scratch location, clean initial state and absent remote, then apply the fetched commit.
- Independent workstreams: read-only composition trace alongside local JVM and lint verification.
- Shared mutable state: one writer owns the index and working tree; the source trace does not write.
- Smallest safe decomposition: retain the existing production architecture and add only the missing wiring assertion plus review evidence.

## Verification

Run with the sourced /home/ubuntu/toolchain/env.sh environment and
/home/ubuntu/toolchain/gradle-8.9/bin/gradle --offline.

- test --rerun-tasks: 152 debug tests and 152 release tests, 304 executions. Both variants have 17 classes, zero failures, zero errors and zero skips.
- The 33 integration-baseline tests and 133 chunk-baseline tests overlap. The chunk tree already contains the shared containment, record and transport tests. It has nine containment tests instead of ten because unconditional health pausing is obsolete. The merged tree has 151 tests before the one new wiring test, then 152 per variant.
- lint: successful, zero errors and two warnings. ApplySharedPref flags the intentional synchronous consent commit; GradleDependency flags the existing Health Connect version. Neither was changed.
- scripts/audit-stage4.py checks unchanged entities, Room declaration/version, Settings, legacy transaction, HMAC, empty transport, UI and collection, and reports XML totals. Imported stage2 documents and audit script describe their earlier baseline, not this combined candidate.
- Logs: /home/ubuntu/scratch/phase23-logs/stage4-tests.log and /home/ubuntu/scratch/phase23-logs/stage4-lint.log.

## Remaining composition gaps

The UI retains containment-era copy that says health delivery is always paused,
and the manual action says Record paused health status even though it now
requests fresh chunk delivery. SyncStatusDisplay does not interpret chunk
results or the new legacy-Pending status; they fall through to Unknown.
Progress is static containment text. UI layout and resources were preserved.

Invalid setup or unavailable permissions can throw during fresh collection
before runFreshChunkSync writes a new status. This pre-existing chunk behavior
was not expanded into error-handling work during integration.

JVM tests use synthetic data and transport substitutes. Android rendering,
WorkManager execution, Android directory fsync, real Room runtime rollback,
Health Connect, device behavior and receiver compatibility were not exercised.
No install, emulator, receiver or infrastructure contact occurred. No real
health data, credentials or retained database state was used or changed.
