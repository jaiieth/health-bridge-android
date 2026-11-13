# Durable attempt intent

This change adds provenance for newly owned fresh-sync generations. #4 remains OPEN.
It adds no staging release authority. Legacy pre-publication generations without intent retain
the existing classification and bytes. Consent, scheduling, quota, retention count,
and delivery envelope limits stay unchanged.

## Architecture trace

`ChunkSync.run` excludes legacy Pending under the caller's mutex, recovers staging,
reclaims independently completed generations, and loads an active generation.
Fresh list input uses `ChunkOutbox.stage`. Streaming input uses a read-only planning
pass in `StreamingChunkPlanner.stage`, followed by verified replay. Both routes
reserve storage, call `beginReserved`, stage bodies, then publish manifests and
pointers. Each transport call follows a durable SEND manifest. Cursor commit and
DONE precede the existing completed-receipt release path.

`classifyStaging` requires committed manifest evidence for its existing legacy
reclamation. Intent-bearing generations take a separate retain-only path. An
intent describes planned bytes and cannot establish absence of delivery.
`reclaimDone` retains its existing cursor proof, Pending exclusion, pointer
exclusion, three-generation retention, and receipt fences.

## Local design sketches

Two viable shapes were considered before editing.

| Shape | API sketch | Tradeoff |
| --- | --- | --- |
| Durable ownership token | `beginReserved(reservation, manifest, lengths)` produces an owned attempt only after the intent fence. Body and publish operations check the owning outbox's durable-intent registry. | One mandatory boundary for both input routes. The existing reservation API and quota callers remain small. |
| Separate intent operation | `beginReserved(reservation)` then `writeIntent(plan)` then stage and publish. | Callers must maintain another temporal rule, or every downstream operation must reconstruct fence state. |

The first shape is smaller and prevents a caller from staging or publishing before
the intent fence returns. The existing rollback token carries the expected intent
digest, so a conflicting final intent also prevents owned rollback from deleting it.
The immutable intent holds a typed inventory and the exact version 2 reservation
fields, byte length, and digest. Two intent encoding bounds enter admission before
ownership. The streaming route still replays once and retains no additional body
payload.

## Throughput checkpoint

- Blocking first steps are ref verification, source tracing, and the two local sketches.
- Independent workstreams do not apply. One owner changes coupled storage and replay contracts.
- Shared mutable state is the serialized outbox. The existing caller mutex remains its invariant.
- The smallest safe decomposition is one writer, focused synthetic tests, then full verification. The parent independently reviews.

## Decision trail

| Decision | Reason | Evidence |
| --- | --- | --- |
| Own the implementation directly. | The task forbids subagents. | Task brief and this report. |
| Continue with p3-mode after both agent-guide paths were absent. | Neither requested path nor the skill-directory search found `p3-agent.md`. | Initial tool output. |
| Keep new staging recovery retain-only. | Intent must not create deletion authority. | `ChunkOutbox.classifyStaging`. |
| Preserve completed receipt release. | DONE and cursor proof already authorize that independent cleanup. | `ChunkOutbox.reclaimDone` and receipt tests. |
| Use the no-replacement same-directory provider move under the existing mutex. | The local probe showed that `ATOMIC_MOVE` overwrites existing targets. | `/tmp/hb-gap4-verification/rename-probe.log`. |
| Make only intent temps exclusive. | Broad temp exclusivity broke interrupted receipt retries. | `/tmp/hb-gap4-verification/focused-2.log` and `DoneRetentionTest.tombstoneAtomicWriteCrashNeverReleasesBeforeItsDurabilityFence`. |
| Correct free-space and endpoint fixtures. | Intent storage is now reserved and the record requires an endpoint hash. | `/tmp/hb-gap4-verification/run-sa2mLa/full.log` and the focused fixture diffs. |
| Capture the generation at the cleanup crash boundary. | The new test assumed the wrong ordering among independently eligible completions. | `/tmp/hb-gap4-verification/run-N89Y7W/focused.log` and `DoneRetentionTest.releaseRestartsAfterIntentAndReservationMetadataRemoval`. |

## Storage and recovery decisions

The intent budget is `manifestEncodedBytes + 32768`. The existing manifest bound
already counts the record IDs, record hashes, targets, and child identities. The
extra allowance covers ownership, reservation strings and digests, and up to 100
child filename and length descriptors. Admission adds twice this bound to the
existing peak before the generation exists. This reserves the final intent and an
atomic-copy allowance without changing any configured limit. A real rename has
one intent name at a time; the allowance is conservative.

The eight reservation fields stay in their version 2 wire order. The intent binds
the exact newline-joined reservation bytes, including length and SHA-256. New
reservation arithmetic is recognized for completed cleanup, but staging evidence
and staging tombstones still require the legacy arithmetic. A missing new intent
therefore cannot reach legacy staging deletion.

Recovery validates only regular, readable files without symlinks. A usable intent
requires a matching reservation, exact planned bodies, one final or temp name per
child, matching surviving metadata, and no conflicting pointer, receipt, receipt
temp, or tombstone. Both verified and unusable intents return Retain. No new
staging tombstone or deletion call comes from this branch. Partial writes and
incomplete inventories retain every surviving byte.

Owned rollback and completed-receipt cleanup recognize the added metadata file.
Their authority remains the existing ownership token or completed receipt and
cursor proof. Completed cleanup removes intent before reservation and manifest.
The synthetic restart test checks both of those added metadata removal intervals.
The original receipt retry behavior remains intact. Existing completed-generation
release also passes with true legacy reservations and no intent.

`Model the Domain` shaped `AttemptIntent`, `AttemptChild`, and `PlannedGeneration`.
`Prove It Works` shaped the inventory comparisons, fence injections, XML counts,
and APK signature verification. The implementation brief required one owner, so
architecture exploration and comment review stayed local. The lead reviewed the
actual diff and reran the full verification command after the delegate finished.

## Facts and assumptions

The JVM probe showed that `ATOMIC_MOVE` overwrites an existing target on this
Linux provider. The no-options same-directory move refuses an existing target.
The installed provider's bytecode checks the target and then calls native rename.
The writer also checks the target after file fsync, including a same-inode hard
link, and creates intent temps exclusively. These checks run under the existing
`AppState.mutex` contract. `SyncWorker.doWork` supplies that mutex, and the
production outbox lives in app-private storage.

This relies on single-writer ownership and the platform's same-directory rename
implementation. It does not provide a kernel `renameat2(RENAME_NOREPLACE)`
guarantee against another process writing outside the mutex. Android filesystem
behavior and real power-loss durability are unverified. No new native library or
dependency was added to claim that guarantee.

The local probe and provider dump are in
`/tmp/hb-gap4-verification/rename-probe.log` and
`/tmp/hb-gap4-verification/unix-move-provider.txt`.

## Verification

The implementation commit is `09aae280fc9ff4d17dd19da6b02cc1e880a4483e`.
The final documentation commit records these results without changing tested source.
Verification ran on that source before the implementation commit. All data is
synthetic JVM data.

Run `scripts/verify-gap4.sh` to repeat the focused and full checks. It creates a
new directory beneath `/tmp/hb-gap4-verification` for every invocation. The passing
run is `/tmp/hb-gap4-verification/run-2w49OM`. Its `summary.json` derives the counts
from the copied XML, and `full.log` records the Gradle task results.

| Check | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Focused debug JVM tests | 108 | 0 | 0 | 0 |
| `testDebugUnitTest` | 298 | 0 | 0 | 3 |
| `testReleaseUnitTest` | 298 | 0 | 0 | 3 |

Each full variant has 301 tests, including the three unchanged ignored gap
fixtures. The change adds 18 attempt-intent tests and three completed-cleanup tests
to the 277 passing baseline. `lintDebug` reports 0 errors, 0 fatal issues, and 11
warnings. `assembleDebug` succeeds. The full verification command is
`./gradlew --no-daemon testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug`.

The build APK is `app/build/outputs/apk/debug/app-debug.apk`. A byte-identical copy
is `/tmp/hb-gap4-verification/run-2w49OM/health-bridge-gap4-debug.apk`.

- APK SHA-256 is `3a918e02c3744a8d1c06a6374e657a8281bc68aadb58eb25803d0f38dd763554`.
- The verified signer certificate SHA-256 is `d813dfca059eea31840113b9510ac51ced2e63b1ae16104134e4f72a3fd54fa6`.
- `apksigner verify --print-certs` succeeds. The signer matches the original candidate.

`AttemptIntentTest.bodyCrashesPreserveByteExactDurableIntentForListAndStreaming`
compares the exact bytes captured after the intent directory fence with the file
after the first body's TEMP_WRITTEN and DIRECTORY_SYNCED crashes. It also checks
zero sends, zero cursor commits, absent publication, and repeated unchanged
retention. The fence matrix covers every intent write boundary in both routes,
with process death and caught I/O failure. The directory fsync failure has a
separate fault injection.

`AttemptIntentTest.legacyNoIntentPrepublicationInventoryRetainsExactly` writes a
true legacy reservation and no intent. Three recovery passes return the existing
Retain behavior and compare every file path, length, and SHA-256 with the original
inventory. Synthetic legacy Pending and cursor files, bodies, and a partial temp
stay unchanged. The common retention assertion also invokes completed cleanup and
checks zero release callbacks. `StagingRecoveryTest` continues to exercise legacy
manifest-authorized reclamation. `DoneRetentionTest` covers completed cleanup for
both protocols and interrupted removal of the new metadata.

`preservation.json` in the passing evidence directory records equal base and
working Git blob IDs for the historical analysis, original ignored fixtures,
Store, Containment, worker, consent controls, production adapter, runner, and build
configuration. It also records the exact skipped test names and unchanged shared
candidate APK hashes. Both shared candidate APKs remain
`cd224c87618edd2a0e7f5d287ce218541d6ea8ac6227718dd492421d2a67bf96`.
No shared evidence file was written or deleted.

The actual protected refs were checked before and after verification.
`hb/phase23-integration` remains
`947fad21910c19a83ed9d745f892d1a339922b9a`.
`hb/v0.1.1-gate-candidate` remains
`3be5bd332b3fc94382dea917c883b3a1300e81de`.
The working branch stays `hb/gap4-attempt-intent`.

The original three ignored fixtures and historical analysis are byte-identical to
the base. `sync_enabled` still defaults to false, and scheduling is unchanged.
#4 and #6 remain OPEN. There is no device verification, no storage reclamation
solution for new pre-publication leftovers, and no production-readiness claim.

## Independent lead verification

The lead waited for the delegated implementation task to finish before accessing
or building the checkout. The reviewed source HEAD was
`fda88c62816a7c7965fff286f46da6d53aa100f4`. The lead then ran
`./gradlew testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug --rerun-tasks`
with the repository's JDK 17, Android SDK, and QEMU toolchain environment.
Gradle reported BUILD SUCCESSFUL and 79 executed tasks. No source changes followed
that run. This section is a documentation-only follow-up.

The independent XML counts are 298 passed, 0 failed, 0 errors, and 3 skipped in
each variant, out of 301 tests. Lint has 0 errors, 0 fatal issues, and 11 warnings.
`assembleDebug` succeeded. The original three gap fixtures remain skipped.
The lead copied the XML and lint report to `/tmp/hb-gap4-lead-verification`.
`summary.json` contains the independently computed counts. `gradle.log` records
the actual run.

The rebuilt APK is `/tmp/hb-gap4-lead-verification/health-bridge-gap4-debug.apk`.
Its SHA-256 is
`65d0749c290130cb58feda00aef780c65559e4742d9408c0e2b1d66388756fa7`.
`apksigner verify --print-certs` succeeds with signer certificate SHA-256
`d813dfca059eea31840113b9510ac51ced2e63b1ae16104134e4f72a3fd54fa6`.
This is the lead's rebuild identity, separate from the earlier delegate build.

In both independent test runs,
`AttemptIntentTest.legacyNoIntentPrepublicationInventoryRetainsExactly` passed.
The test checks identical paths, lengths, and SHA-256 hashes through three recovery
passes and zero completed-release callbacks. Source review confirms the existing
no-manifest branch still returns Retain. This evidence concerns legacy
pre-publication leftovers. Existing independently authorized legacy completed
cleanup remains covered by `DoneRetentionTest`; absence of intent does not revoke
that prior authority.

The lead confirmed that the intent classification branch always returns Retain,
including after validation fails. It never produces a staging-reclamation result.
Owned rollback and completed-receipt cleanup still require their pre-existing
authority. No new intent-only deletion authority was introduced.

The lead also confirmed unchanged protected branch refs, consent and scheduling
sources, runner and database sources, build configuration, historical analysis,
and original ignored gap fixtures against the base. Both shared candidate APK
hashes still match the earlier preservation report. No push, tag, release, or
shared evidence mutation was performed.

The atomic rename requirement remains conditional on the existing single-writer
mutex and the platform provider's same-directory behavior. The implementation
uses `Files.move` without `ATOMIC_MOVE` for no-replacement writes. Passing JVM
tests do not prove that this operation provides an atomic no-replacement contract
on Android. An unconditional atomic no-replacement guarantee is therefore not
established by this implementation or verification. This limitation is outstanding,
not an accepted change to the requirement. #4 remains OPEN, device behavior is
unverified, and this branch is not production-ready.
