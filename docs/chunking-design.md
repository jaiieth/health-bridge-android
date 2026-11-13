# Fresh sync chunking preparation

This scratch-only preparation ports the reference chunking patch onto containment commit b06171f. It is for CTO design review. It is not integration or a shippable build.

## Consent and delivery ownership

SyncWorker holds AppState.mutex and calls Containment.run. The dispatcher checks Settings.syncEnabled first. OFF writes `Sync OFF` and returns before credentials, Pending, cursors, collection, or transport are accessed. While ON, Test invokes only Transport.sendEmpty. It neither inspects nor changes Pending or the chunk outbox. Normal work checks Pending next. Any legacy row produces a paused status without reading cursors or collecting. Only the remaining case enters fresh chunk sync.

The alternative was a second dispatcher around Containment.run. Keeping the gates together makes the tested coordinator the production entry point and avoids duplicating consent checks. There is no chunking preference or alternate legacy delivery branch. ChunkSync's enabled parameter is an internal consent guard, not an independent feature flag.

Transport.send and the Room entities and schema remain unchanged. The new transport method builds the same envelope and HMAC headers from immutable child bytes. It reads the response body for strict ACK validation. The new path never calls Transport.send or Store.success, writes Pending, deletes Pending, or changes its retry accounting.

## Plan and budgets

FreshChunkInput contains ordered immutable ChunkRecord values and target cursors. The pure partitioner returns either complete PlannedChild values or a blocked indivisible record. Concatenating child record lists preserves the input order, fields, and nested arrays. An oversized record blocks the entire plan before any child file is staged.

Every child must fit all three local caps independently:

- The POST body is at most 524288 UTF-8 bytes, including envelope, ID, and punctuation.
- The child contains at most 5000 records.
- The mapped JSON array is at most 900000 UTF-8 bytes, including escaping and array punctuation.

The receiver mapping and fixture files are inherited from the supplied reference patch. They model five fields, type, source package, record ID, normalized modified timestamp, and the JSON-stringified complete payload. EcmaJson and EcmaNumber model ECMAScript escaping and numeric serialization. ReceiverTimestamp accepts strict Gregorian UTC timestamps with up to nine fractional digits and pads the fraction to nine digits. Calendar rollovers are conservatively rejected. These local fixtures do not verify a deployed receiver or its current limits. No receiver was contacted in this preparation.

## File outbox

The outbox is filesDir/fresh-sync-chunks. Each generation contains immutable child JSON files and a manifest. An active marker identifies the published generation. The manifest records an endpoint hash, parent fingerprint, record IDs and hashes, target cursors, child IDs and body hashes, ACK state, timestamps, phase, and fixed reason codes. It contains no credentials. Target cursors stay local and are absent from POST bodies.

Every write uses a same-directory temporary file, file fsync, atomic rename, and directory fsync. Directory creation also syncs the parent directory. Android uses Os.fsync for directories. Unsupported operations fail closed. Before resuming a verified unfinished generation, a separate durability fence syncs the outbox parent, root, and generation directories. A failed rename-directory sync cannot be bypassed merely because the newly renamed file remains visible in the page cache. Read-only load and status do not perform this fence. Child files are durable before the manifest, and the manifest is durable before active publication. Partial unpublished generations are inert and retained.

Load verifies the fingerprint, generation and child IDs, child order and counts, ACK prefix, phase and send-marker consistency, body SHA-256 values, and envelope IDs and record order. Corruption stops delivery without repair or deletion. All access is serialized by the existing app mutex. Completed generations and orphan files accumulate. Garbage collection is outside this preparation.

## ACK and cursor barrier

Before each transport call, SEND and sentAt are written durably. Restart at SEND pauses as acceptance unknown and never resends automatically. Each valid response produces a durable ACK before the next child is sent. Restart at an ACK resumes only unsent children with the original IDs and bytes.

Containment.classify controls HTTP outcomes. BLOCKED responses persist a block. RETRY responses pause and have no automatic retry. HTTP 400, 409, and 413 retain specific reason codes. An ACCEPTED classification still needs HTTP 200 and a strict ACK containing boolean ok true and the exact batchId. Optional accepted must be a nonnegative integer and duplicate must be boolean. Unknown fields, duplicate keys, malformed JSON, mismatched IDs, and incorrect types pause. Response text and exception messages are never persisted in status. A valid duplicate ACK acknowledges the original immutable child.

Once all ACKs are durable, COMMIT is persisted. Store.commitChunkCursors checks Pending absence, compares existing targets, and replaces target cursors in one existing Room transaction. It skips the cursor write when every target already matches. This handles a crash after Room commits but before DONE without advancing cursors twice. DONE is persisted last. No new table, entity, version, or migration is required.

## Verification and limits

Tests use synthetic records, temporary directories, fake transport, injected IDs and clocks, and observable cursor storage. They cover partition conservation, all three default budget limits, oversized records, ACK strictness, durable write boundaries, restart at every ACK prefix, unknown acceptance, Pending preservation, consent precedence, and a post-Room pre-DONE crash using the production Store transaction method. The forced offline JVM run is recorded in /home/ubuntu/scratch/phase23-logs/stage2-tests.log.

Blocking work was reference checksum and containment inspection. One implementation owner handled coupled worker, outbox, and cursor changes. Parent review is separate. The existing mutex serializes shared outbox writes and the transaction guards cursor writes.

JVM fault injection proves local ordering and restart behavior. It cannot establish Android power-loss durability, Health Connect permission behavior, or live receiver identity and acceptance semantics. Collection still materializes the complete source window in memory. Endpoint changes pause an unfinished generation instead of redirecting retained bytes. There is no automatic unknown-acceptance recovery.
