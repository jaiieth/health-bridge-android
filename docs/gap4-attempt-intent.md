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
and APK signature verification. The user required one implementation owner, so
architecture exploration and comment review stayed local. The parent review is
still outstanding.

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

Pending final full-run results. All test data is synthetic JVM data.
There is no device verification. This work does not establish production readiness.
