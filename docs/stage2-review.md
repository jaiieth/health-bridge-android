# Stage 2 preparation review

This is a local preparation commit on containment base `b06171f`, for CTO design review. It is not integration and is not shippable. Work is confined to `/home/ubuntu/scratch/hb-chunk2`. The clone has no remote.

The supplied patch is `/home/ubuntu/scratch/phase23-logs/chunking.patch`. Its SHA-256 is `06d559e98be6414a106cca99dbea06afa7a1d95e6cbe4efd978d4a6f5da26a7b`. It was based on `bcb2f07`.

## Conflict resolutions

| Area | Resolution |
| --- | --- |
| SyncWorker | Retain the containment worker, mutex, and success return. Supply fresh chunk sync as the sole health callback. Discard the patch's old-worker insertion and legacy delivery fallback. |
| Consent | Settings remains unchanged. Remove the patch's separate `freshSyncChunkingEnabled` preference and disable controls. OFF writes `Sync OFF` and stops before Test or health work. |
| Test | Keep `EmptyTest` and `Transport.sendEmpty` unchanged. ON Test precedes Pending inspection and never opens the health outbox. Earlier containment tests that allowed Test while OFF now require ON, as requested for this stage. |
| Legacy Pending | Any row pauses normal work before credentials, cursors, or collection. No Pending field, body, attempt, or legacy cursor payload changes. |
| Store | Add only a cursor transaction method. It checks Pending absence and writes all target cursors together. Entities, database version, schema declaration, settings, and old methods remain unchanged. |
| Transport | Add the patch's detailed response method alongside containment's empty Test method. Legacy `Transport.send`, HMAC, client configuration, and Test request bytes remain unchanged. The new path has no legacy-send caller. |
| HTTP outcomes | Use unchanged `Containment.classify`. Every BLOCKED classification blocks and every RETRY classification pauses. ACCEPTED still requires HTTP 200 and a strict matching ACK before progress. |

## Corrections to the reference patch

The reference replay test expected two cursor writes after a crash between Room commit and DONE. The transaction now compares current target values and skips an already-applied write. A wiring test calls the production Store method, injects death after it returns, reconstructs the outbox, and observes exactly one cursor write across restart.

Directory creation now retries parent fsync even if an earlier attempt left the directory present. Verified unfinished generations also pass a delivery-time durability fence over the root parent, root, and generation. This prevents a visible but undurable active or ACK rename from being used for resumed delivery after a failed directory fsync. Read-only load and status calls do not repair files. Tests cover both publication failures and a failed resume fence before allowing transport or cursor commit.

The patch's unused UI status adapter and runner Test switch are removed. Behavioral dispatcher tests replace their shallow checks. Historical claims about prior Node runs and receiver verification are removed from the design document. Mapping fixtures are supplied assumptions, not evidence of a live receiver contract.

## Reproduce verification

Run from the scratch clone. Never invoke `scripts/verify.sh`, which uses the wrapper and has a broader scope than this preparation.

```bash
source /home/ubuntu/toolchain/env.sh
set -o pipefail
/home/ubuntu/toolchain/gradle-8.9/bin/gradle --offline --no-daemon :app:testDebugUnitTest --rerun-tasks 2>&1 | tee /home/ubuntu/scratch/phase23-logs/stage2-tests.log
python3 scripts/audit-stage2.py
git diff --check
```

The audit script checks prohibited diff areas, compares containment's classifier, Room declarations, legacy send, empty Test send, and HMAC against `b06171f`, and counts the actual JUnit XML results. It does not prove the age of those results. The forced Gradle run and its log establish execution for this preparation.

The final forced run passed 133 tests across 14 suites, with zero failures, errors, or skips. Gradle reported 25 actionable tasks, all executed. Counts come from the final JUnit XML files, not test annotations. The supplied patch adds 114 test methods to the earlier baseline. This port removes two unused adapter checks, adds three durability and HTTP checks plus four wiring tests, and retains nine containment tests and five original serialization and transport tests.

| Suite | Tests |
| --- | ---: |
| ChunkDurabilityTest | 12 |
| ChunkPartitionerTest | 24 |
| ChunkRestartMatrixTest | 2 |
| ChunkReviewTest | 4 |
| ChunkSafetyTest | 8 |
| ChunkSyncTest | 31 |
| ContainmentTest | 9 |
| DetailedTransportTest | 2 |
| EcmaNumberTest | 3 |
| ReceiverMappingTest | 8 |
| ReceiverTimestampTest | 21 |
| RecordJsonTest | 2 |
| SyncWiringTest | 4 |
| TransportTest | 3 |
| Total | 133 |

The cap tests measure exactly 524288 wire bytes, 5000 records before splitting record 5001, and 900000 mapped bytes. The full receiver-mapping fixture reaches the mapped cap with a 450047-byte wire body. These are local budget checks, not measurements of a live receiver's capacity.

## Verification limits

JVM tests use synthetic records, temporary files, injected crashes, and fake transport. They prove ordering and restart behavior in that environment. They do not establish Android power-loss durability, actual Room transaction execution on Android, Health Connect collection or permission behavior, or a deployed receiver's mapping and ACK contract. No device, emulator, receiver, credentials, real health data, or infrastructure is used.

The production cursor method is exercised on an observable Store implementation. Room generates its transaction adapter during compilation, but there is no instrumented database run. Outbox hashes detect independent corruption, not coordinated modification of a body and its manifest. Collection still materializes a complete source window, and completed or unpublished outbox generations are retained without garbage collection.

The Model the Domain principle kept delivery progress in the existing manifest state machine. Prove It Works required the forced run and actual XML counts. Build the Lever produced `scripts/audit-stage2.py` so the containment and scope checks can be rerun.
