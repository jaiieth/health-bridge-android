# Stage 2 collection and outbox budgets

This is a plan only. It implements no collection, retention, cleanup, deletion, or wire-format change. Keep fresh chunking OFF until the admission limits, failure tests, and SDK memory qualification below pass. Consent ON is not readiness.

## Current numbers observed in source

These are code constants and control-flow observations, not runtime memory measurements. Reproduce with `nl -ba app/src/main/java/dev/healthbridge/{HealthReader,RecordJson,FreshChunkSync,ChunkPlan,ChunkSync,ChunkOutbox}.kt`.

- `HealthReader.kt:17` lists six record types: sleep sessions, HRV RMSSD, resting heart rate, heart rate, steps, and exercise sessions.
- `HealthReader.kt:72` uses the saved cursor, otherwise a 30-day lookback, or 2,592,000 seconds. An old cursor can make the window longer than 30 days.
- `HealthReader.kt:81` requests 1,000 records per page. Pages are read sequentially, not six pages simultaneously. `records` retains every converted record until all types finish.
- `RecordJson.kt` expands nested sleep stages and heart-rate samples without a local count or byte cap. A record is not a fixed-size item.
- `FreshChunkSync.kt:32` converts the complete JSONArray into another complete list of JSON strings. `ChunkPlan.kt:36` retains size lists and all planned child bodies. The manifest also retains all record IDs and hashes.
- `ChunkPlan.kt:9` caps each child at 524,288 wire bytes, 5,000 records, and 900,000 mapped-array UTF-8 bytes. Wire size includes the envelope. Mapped size includes brackets, commas, and escaping.
- `ChunkSync.kt` has no generation record, child, or aggregate byte cap. `ChunkOutbox.kt:89` stages every child and retains prior generation directories. It has no GC.

## Current arithmetic projections

Let P be the total pages across the six types and R the total records. R is at most 1,000P if the provider honors pageSize, but P has no local upper bound. Six types do not imply a maximum of 6,000 records. The window does not constrain sample density. Therefore worst-case retained records and bytes in memory are **unbounded by this code**, including for a 30-day initial read. Actual JVM heap usage and RSS were not measured.

For C children, their bodies occupy at most 524,288C bytes. The mapped cap is a receiver-size calculation, not another file stored locally. Let M be encoded manifest bytes, A the active marker bytes, T the total incomplete temporary files, and F filesystem allocation and directory overhead. One generation occupies at most 524,288C + M + A + T + F bytes. Production UUID generation makes A 36 bytes. Neither C nor M has a finite generation cap. Record IDs are not length-capped locally.

For N retained completed generations, total bytes are the sum over each generation of its actual bodies and manifest, plus the active marker, inert orphan directories, temporary files, and filesystem overhead. If all have C children and M manifest bytes, the projection is N(524,288C + M) + 36 + orphan bytes + temporary bytes + F. N and orphan bytes are unbounded. During manifest replacement, both the previous manifest and its temporary replacement can coexist.

Illustrative projection only: 100 children can consume 52,428,800 body bytes, or 50 MiB, per generation. Ten such generations consume 524,288,000 body bytes, or 500 MiB, before manifests, orphans, or filesystem overhead. This is not a measured generation size or an existing cap.

## Proposed bounded collection

Use one producer and one incremental partitioner under the existing sync mutex. Fix the end timestamp and target cursors before collection. Read one type and one page at a time. Feed records individually into the partitioner, release each page before fetching the next, and spool completed children into an unpublished generation. Do not retain the entire JSONArray, complete JSON-string list, all child bodies, or all IDs in memory. Stream IDs and hashes into a bounded manifest spool. Preserve the existing envelope, batch IDs, ordering, receiver mapping, and cursor transaction.

Proposed admission limits, all new design numbers rather than current constants:

- Start with pageSize 1, not 1,000, to minimize opaque SDK allocations. Retain only one page at a time. A page may span several chunks; it need not fit a single chunk.
- Keep the existing child caps. Serialize using bounded sinks that reject before allocating past their limits, including JSON escaping and nested arrays. Incrementally count mapped bytes without retaining the mapped object tree.
- Limit each generation to 100 children, 100,000 records, and a 16 MiB manifest spool. Refuse admission before the next item would exceed any limit. Never silently truncate or advance cursors for an incomplete window. A blocked window needs explicit review or a separately designed smaller time slice.
- Limit application-owned collection buffers to 16 MiB: 4 MiB for page adaptation, 2 MiB for one record and mapping scratch, 1 MiB for child construction and transport copies, 1 MiB for manifest streaming, and 8 MiB for explicitly bounded queues, indexes, and allocation overhead. Sum: 4 + 2 + 1 + 1 + 8 = 16 MiB. Eliminate unbounded maps and string concatenation before claiming this limit.
- Bound ACK response reads to 64 KiB and include them in the 1 MiB transport allocation. Bound resume reads and validation as well as fresh collection. A manifest remains disk-backed and is validated incrementally.

When one page cannot fit a chunk, split only between records and continue feeding the next chunk. When a single record cannot fit the existing wire or mapped cap, stop with OVERSIZED before publication or sending any child. Do not split a record, drop nested samples, or alter the wire format. Preserve the unpublished evidence and leave Pending, attempts, and cursors unchanged. Apply the same fail-closed policy to any proposed memory or generation limit.

### SDK memory qualification is a live-enabling gate

The 16 MiB arithmetic bounds proposed application-owned buffers, **not whole-process RSS**. Health Connect materializes a page before our bounded serializer sees it. Even pageSize 1 does not bound a sleep-stage or heart-rate-sample array. Checking size after `readRecords` returns cannot prevent that allocation. No finite worst-case SDK page bytes or whole-process RSS follows from the current API usage.

The required qualification target is at most 16 MiB of SDK page and decode allocations, plus the 16 MiB application budget. Projected incremental resident memory is then at most 32 MiB. With a separately qualified runtime baseline B, projected whole-process RSS is B + 32 MiB. B has not been measured; neither the SDK 16 MiB ceiling nor this RSS bound is proven. Garbage collector peaks, native allocations, and transport copies must be included in qualification, not assumed away.

Before enabling, obtain an enforceable provider-side page byte and nested-item limit, or a supported isolated collector with an enforced memory limit. A time-window split alone cannot bound a single oversized record. If the SDK cannot supply an enforceable bound and the platform cannot isolate it within a qualified limit, this part of the plan remains blocked and the feature stays OFF. Do not present an observed average, a JVM heap limit, or a post-allocation size check as a worst-case RSS proof. Qualification must exercise an oversized single record and allocation failure with no publish, send, or cursor write.

## Proposed disk admission and retention

With the proposed 100-child cap, bodies use at most 50 MiB. With a 16 MiB manifest cap, stable files use at most 66 MiB per generation plus the 36-byte active marker. Replacing the largest manifest needs another 16 MiB temporarily, giving 82 MiB plus active-marker temporaries and F. Writing a child temp before rename does not duplicate that child in a new generation. Reserve a conservative **84 MiB per admitted generation**, including 2 MiB for filesystem and marker overhead, subject to filesystem qualification. These are projections, not current enforced limits. Reserve free-space headroom separately for Room, the OS, and existing evidence. A free-space precheck alone is insufficient because another writer can consume space.

For N retained admitted generations and O bytes of protected orphans, the stable projection is at most 66N MiB + O + 36 + F; one active manifest rewrite can add 16 MiB. Protected UNKNOWN generations and unknown orphans mean no unconditional global disk bound is possible. The safe bound is admission control: stop creating generations when evidence consumes the quota.

Propose a 256 MiB outbox admission quota and retain at most two eligible completed generations for seven days. Reserve 84 MiB before collection and account for actual allocated disk blocks, not only file lengths. Count all prior generations, orphans, and temporaries toward the quota. Refuse new collection when the reservation cannot be satisfied. Never evict protected evidence to make room.

A future GC must follow these rules:

1. Never access or delete legacy Pending, its body, attempts, or cursors. GC applies only to the chunk directory.
2. Never delete a generation with SEND, UNKNOWN_ACCEPTANCE, PAUSED, BLOCKED, corrupt metadata, uncertain provenance, or an unproven commit. Keep all related temporary files as evidence. Do not infer safety from age or absence of `active`.
3. A generation is eligible only after every child has a durable validated ACK, the existing Room cursor transaction has committed, and a durable DONE manifest proves the order. COMMIT without DONE remains protected. Cursor equality alone is not sufficient evidence.
4. Keep the active generation protected. Publish and fsync its successor before considering an older eligible DONE generation. Under the mutex, validate the candidate and its durable completion proof again immediately before GC.
5. Inert orphan directories are protected by default. Delete only an orphan with an independently validated durable DONE and cursor-commit proof under the same rules. Otherwise require a separate explicit evidence-disposition decision. This plan authorizes no deletion.
6. A future GC must tolerate crashes during deletion without making remaining incomplete data resumable. Sync directories after each deletion boundary. A disk-backed inventory must count partially deleted directories and refuse new admission if classification is uncertain.

On disk full or any failed write, flush, fsync, rename, or directory fsync, fail closed. Preserve all surviving files and the previous active marker. Do not send from a partially staged generation, advance cursors, retry UNKNOWN, or publish a partial manifest. A rename followed by failed directory fsync is an uncertain publication, not proof of durability. Revalidate and re-establish durability before any permitted resume. No automatic cleanup may erase the failure evidence.

## Required proof before live enabling

Add fault-injection tests at every new budget and publication boundary, including disk exhaustion during each child, manifest, and active-marker write. Exercise multi-page input, a page spanning chunks, one oversized nested record, generation-cap exhaustion, protected UNKNOWN quota exhaustion, and crash recovery after cursor commit. Confirm bounded allocation with a real SDK workload and qualify B and the SDK page ceiling. Confirm that every rejection preserves Pending, attempts, cursors, prior active data, and UNKNOWN evidence. None of these future bounded-collection or GC tests was run as part of this plan-only document.
