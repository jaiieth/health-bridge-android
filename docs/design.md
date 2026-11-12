# Storage choice

Room gives the pending request and all six cursors one transactional boundary. A single-row pending table retains exact HTTP bytes and retry state across process death. Success advances cursors and deletes that row in one transaction.

An atomic file could store the whole state with rename, while DataStore could update a serialized state atomically. Both require custom schema evolution and more care around binary request storage and inspection. Separate files for cursors and requests would risk partial commits.

Choose Room. One process mutex serializes settings, collection, and delivery. One durable pending batch blocks new collection and endpoint/token edits until delivery succeeds. A failed batch remains available for explicit retry. Test batches use the same queue with an empty cursor map. HTTP success means a 2xx response. A crash after server acceptance can resend the same batch ID; receivers must deduplicate it. Queries use a fixed upper bound and paginate each permitted record type. This is an incremental time-window export, not a change-token mirror of edits or deletions.
