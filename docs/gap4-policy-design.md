# Gap 4 retention policy design

**FOR REVIEW, NOT IMPLEMENTATION.** Independent review is unassigned.
This document grants no deletion, reclamation, overwrite, unpin, or release authority.
The extension changes tests, test configuration, reproduction scripts, and documents only.
#4 and #6 remain OPEN. This is not production-ready and has no device verification.

## Premises and limits

The immutable intent describes a plan, not delivery history. The current classifier
returns Retain whether intent validation succeeds or fails. Legacy pre-publication
generations without intent remain Retain and byte-identical. Existing legacy
manifest-authorized staging cleanup and independently authorized completed cleanup
are different cases; absence of an intent does not revoke their existing authority.

Limits stay at 67,108,864 logical bytes overall, with 65,536 bytes reserved for
controls inside that total, three retained DONE generations, 100 children,
100,000 records, 8,388,608 manifest-bound bytes, and 524,288 wire bytes per child.
Mapped bytes per child stay at 900,000 and records per child at 5,000. These are
logical file and encoded-content limits, not filesystem allocated-block or JVM
heap guarantees. Admission can refuse a valid envelope because metadata consumes
part of the unchanged quota.

`Store` is a generated Room DAO over Pending, Cursor, and SyncStatus, at database
version 1 with Room 2.6.1. Both `commitChunkCursors` and `success` have
`@Transaction`. Production `FreshChunkSync` contains an anonymous
`ChunkCursorStore` adapter that delegates to the former. It writes status after
`runner.run`, separately. There is no separately named Room cursor-adapter class.
Real Room tests of `Store.success` do not prove that the production fresh-sync
cursor commit and success status update form one transaction. They do not.
No production transaction is changed here.

## Exact reservation accounting

Let `q(s)` be `manifestStringBytes(s)`, including JSON quotes and its conservative
escaping allowance. A 64-character hexadecimal hash costs 66 bytes by this bound.
For a record ID `id` and its hash, the record contribution is
`q(id) + q(hash) + 2`, or `q(id) + 68`. Record IDs and hashes occur in both the
manifest and intent. The disk reservation counts the contribution five times,
three manifest bounds and two intent bounds, or `5 * (q(id) + 68)` per record.
Actual encoding may be smaller. This is a reservation bound, not an exact size
of every JSON spelling.

The existing manifest bound `M` is exactly the sum in `manifestEncodedBytes`:

```
4096 + q(generation) + q(endpointHash)
+ sum(q(targetType) + q(targetCursor) + 2)
+ q(parentFingerprint)
+ sum(q(recordId) + 1) + sum(q(recordHash) + 1)
+ (q(blockedRecordId) if present, otherwise 4)
+ sum(256 + q(batchId) + q(bodySha))
```

The per-intent bound `I = M + 32768`. It is capped by the admitted `M`, so its
maximum is 8,421,376 bytes. The 32,768-byte allowance covers fixed ownership and
reservation fields and at most 100 child filename/length descriptors. Admission
checks the actual encoded intent fits `I` before creating the generation.

For `R` retained payload/metadata bytes, `C` existing control bytes, and `B` planned
body bytes, the new version-2 reservation peak is:

```
P = R + C + B + 3*M + 256 + 2*I
  = R + C + B + 5*M + 65792
P <= 67108864
P - C <= 67108864 - 65536
```

The 256-byte existing reservation allowance remains. Both reservation encodings
and digests are bound by the intent. The reservation has eight newline-separated
fields. Its exact encoded byte count and SHA-256 are recorded in the intent.
`2*I` reserves the intent and an atomic-copy allowance. `3*M` is the existing
publication/current/replacement-copy allowance. Bodies move between temp and
final names; they are not budgeted as two simultaneous full copies. Actual logical
bytes, including leftover temps and unknown retained files, count on later admission.
The existing filesystem free-space headroom checks remain independent of `P`.
No new per-record or per-intent quota is introduced by this design document.

## Boundary classification and restart characterization

The new parameterized test uses a new outbox, runner, and disk-backed cursor test
double over the same directory after process-death injection. The disk-backed
double is outbox test support, not Room coverage. B1 and B2 model process death
with the visible files preserved, not actual power loss or filesystem rollback.

| Boundary | Surviving evidence | Current outcome and permitted action |
| --- | --- | --- |
| B0 | Durable reservation, no intent temp | Retain; release nothing; no send occurred. |
| B1 | Fsynced intent temp, no final intent | Retain; release nothing; no body or send. |
| B2 | Renamed intent, directory fence incomplete | Retain; release nothing; no body or send. |
| B3 | Durable intent, no bodies | Retain; release nothing; plan alone is insufficient. |
| B4 | One full child of two, second temp partial | Retain every surviving byte; partial digest does not match. |
| B5 | Both bodies durable, no manifest | Retain; release nothing; no publication or send. |
| B6 before pointer | Manifest and publication durable, no active pointer | Retain; no automatic adoption or send of the orphan. |
| B6 referenced | Publication and active pointer durable, zero sends | Recovery retains; normal runner resumes original IDs and exact bodies. |
| B7 | Durable SEND, transport invoked, no ACK | Retain and persist UNKNOWN_ACCEPTANCE pause; no resend. |
| B8 first ACK | First ACK durable, second child unsent | Retain; resume only the unsent child. |
| B8 all ACKs | All ACKs durable, no COMMIT | Retain; resume commit without another send. |
| B8 before commit | COMMIT durable, cursor transaction not called | Retain; retry cursor commit, no send. |
| B8 after commit | Cursor test file written, DONE not durable | Retain; retry idempotent commit, no send. |

Each case runs three recovery passes with fresh instances and checks identical
file inventories before resuming. Successful resume checks original payload and
intent bytes and unique transport IDs. B7 repeats restart twice after the first
pause and checks the transport count stays one. These are finite observed paths,
not a universal proof against every possible duplicate delivery.

The existing `DoneRetentionTest.restartsEveryReleaseBoundaryWithNoDanglingPointerOrPendingLoss`
and `releaseRestartsAfterIntentAndReservationMetadataRemoval` are reused for
B8's independently authorized completed-release intervals. They inject every
existing release boundary and reopen the same directory with a fresh outbox.
No release implementation is added by this extension.

`AttemptIntentTest` is reused for referenced, malformed, unreadable, symlinked,
unknown-file, legacy, receipt, receipt-temp, tombstone, and all six pointer-name
cases. Ambiguous provenance never creates a reclaim decision. A corrupt reference
may additionally return CORRUPT_OUTBOX; Retain here means preserved inventory,
not that every error path returns the same status code.

## Repeated crash liveness

`AttemptLivenessTest` runs seven attempts over one directory. Each admitted
attempt stages 42 children from synthetic 520,000-character payloads, then crashes
at the last body's directory fence before publication. Every restart preserves
all prior generation files, uses a new outbox and runner, and sends nothing.
Per-cycle measurements are recorded in the verification report.

The observed outcome is three admitted crashes followed by four quota refusals,
with no new payload bytes on refusals. Admission therefore bounds retained logical
bytes but does not provide forward progress. The remaining #4 blocker is
`unpublished-intents-have-no-release-authority`. A retry cannot turn intent into
release authority. Increasing retries does not free storage.

A bounded no-delete policy cannot guarantee indefinite ingestion under these
conditions. Each admitted crash retains a positive number of bytes, storage is
finite, and every retained generation must remain. Eventually admission must
stop. Refusing before ownership preserves safety and the byte bound, but does not
meet ingestion liveness. This document proposes no such guarantee.

## Smallest proposed reclamation policy requiring owner approval

**The following is a proposed policy change. It must not be implemented without
explicit owner approval and independent review.** It is deliberately limited to
fully verified orphan attempts. It does not solve every crash or corruption case.

Only generations created by the intent writer protocol may be considered. Legacy
no-intent generations remain ineligible. The caller must hold the existing mutex,
exclude legacy Pending, stop collection and transport during the decision, and
verify every input using regular-file, no-symlink, bounded reads. Any unreadable
root entry or malformed reference stops the decision.

The eligible generation file set is exactly `reservation`, `attempt-intent.json`,
zero or more of the four existing manifest/publication final/temp names, and
exactly one final or temp basename per planned child. No unknown entries,
reservation temp, intent temp, duplicate child name, missing child, subdirectory,
symlink, partial body, or mismatched digest is eligible. Every child length and
SHA-256 must equal the immutable plan. Ordered record IDs, hashes, target cursors,
endpoint hash, generation, attempt ID, writer version, reservation fields,
reservation byte count, and reservation SHA-256 must all agree. Metadata must fit
the existing bounds above, with child indexes contiguous and unique batch IDs.

Every surviving manifest must bind the same inventory, be STAGE or BLOCKED with
no UNKNOWN_ACCEPTANCE, no sentAt or ackedAt values, and all acked flags false. Any
SEND, ACK, COMMIT, DONE, PAUSED, contradictory identity, or unparseable metadata
makes the generation ineligible. Missing manifests can be accepted only after an
independent review establishes the writer invariant that SEND is durable before
transport and no permitted writer removes that evidence while a body survives.
The intent by itself does not establish this invariant.

All active, active.tmp, active-proof, active-proof.tmp, previous, and previous.tmp
references must parse and exclude this generation. Any completed receipt or its
temp for the generation, or any staging tombstone or temp, stops eligibility.
No implicit unpin, deletion of a reference, or assumption that a missing active
pointer means ownership ended is allowed.

A future implementation would first fence a separate release-authorization record
under the control reserve, binding generation, attempt ID, reservation digest,
complete filename/length/digest inventory, zero-delivery proof, and Pending
checks. It must use a proven atomic no-replacement primitive. If this bounded
record and its temp cannot fit the existing control reserve, release stops.
The proposed record is UTF-8 with seven newline-terminated header lines, in this
order: `hb-orphan-release-v1`, generation ID, attempt UUID, intent SHA-256,
reservation SHA-256, parent fingerprint, inventory row count. Then come sorted
rows of `basename<TAB>decimalLength<TAB>sha256<NEWLINE>`. Only the exact ASCII
basenames above are accepted. Generation is at most 100 ASCII characters, UUID
is 36 characters, each digest is 64 lowercase hex characters, and row count is
at most 106. A row is at most 106 bytes by the conservative 32-character filename,
7-digit length, 64-character digest, two tabs, and newline bounds. Headers and
106 rows fit below 16,384 bytes. No record IDs or cursor strings are copied;
the immutable intent digest binds them. Exact row count and trailing EOF are
mandatory. The filename is `orphan-release-<generation>.txt`, with `.tmp` during
creation. Unknown versions or a second conflicting authorization stop release.

This design caps the record at 16,384 bytes and reserves two copies within
the unchanged 65,536-byte control reserve; admission must preserve space for that
record instead of increasing the quota. The exact admission check is
`C + 32768 <= 65536`; otherwise authorization cannot start. Existing receipts
and pins are not pruned to satisfy it. This space is not reserved by today's code.

Crash resume would validate the durable authorization and all remaining files,
recheck references and Pending, remove only listed matching bodies, fence the
generation directory, remove bound metadata last, fence directory removal in the
parent, then retire the authorization. A surviving temp authorization alone is
not authority; it must be fully validated and durably fenced or retained.
Every interruption resumes from the same authorization. Any extra or changed
file, missing evidence before authorization durability, or reference change stops
release. No fresh authorization is inferred from an incomplete deletion.
The proposed encoding and size bound require independent review before implementation.

B0 reservation-only attempts, intent-temp-only attempts, incomplete inventories,
and partial or corrupt bodies remain Retain under this narrow proposal. Their
repeated crashes can still exhaust quota. A broader policy for them would require
separate durable ownership and no-delivery proof, or explicit owner adjudication
of the exact retained inventory. Intent existence alone is not that proof.
There is no automatic liveness claim for these residual states.

## Explicit #6 newest-three and pin starvation analysis

Current completed cleanup excludes references, requires durable cursor proof and
receipt authority, and only considers generations older than the newest three.
Three large DONE generations can consume nearly the quota. A fourth cannot be
admitted, so no member can become fourth-oldest. This is admission starvation,
not a missing cursor adapter or failure to persist a cursor.

The smallest targeted policy change would permit admission-triggered release of
the oldest fully verified, unreferenced, cursor-proven DONE generation even when
it belongs to the newest three, only until the exact planned peak fits. This
changes the newest-three protection despite leaving the numeric constant at 3.
It needs explicit owner approval. This extension implements none of it.

Pins remain a separate blocker. If active, authenticated previous, legacy Pending,
ambiguous acceptance, or other protected generations occupy the needed bytes,
this proposal refuses admission. It never clears or invalidates a pin. A separate
owner-approved pin lifecycle or retention policy is necessary to guarantee
progress in that case. Neither the #4 orphan proposal nor real Room transactions
solve pin starvation. No no-delete scheme can guarantee arbitrary new arrivals
within the unchanged finite quota while retaining all pinned bytes.

## Residual risks and facts not proven

- Atomic no-replacement on Android remains unestablished by the current Java move.
- JVM process-death injection does not simulate device power loss, fsync behavior,
  torn storage writes, multiple OS processes, or disk controller failures.
- Cursor test doubles in outbox tests establish runner behavior only. Real Room
  tests use the generated DAO and SQLite, with reopen and trigger-induced rollback.
  They do not establish Android process-kill durability or production integration.
- Store.success rollback is not evidence of atomic cursor and status writes in
  FreshChunkSync, whose status write is outside commitChunkCursors.
- No global exactly-once delivery or unbounded ingestion liveness is proven.
- B0, intent-temp-only, partial-body, corrupt, ambiguous, legacy, and pinned
  generations remain potential quota blockers under the narrow proposal.
- The proposed release record, control-reserve allocation, crash resume, and
  admission-driven newest-three exception are designs only and unreviewed. A full
  control reserve can also prevent the proposed authorization from being written.
- Record content uses synthetic data only. No real health data or device is used.
- Published v0.1.1 facts supplied by the owner are accepted context and are not
  reverified. Protected branches, tag, release, signing, consent, and infra are untouched.
