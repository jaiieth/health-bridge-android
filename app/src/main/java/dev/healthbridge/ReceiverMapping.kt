package dev.healthbridge

/** The supplied worker.mjs mapping, with conservative rejection of calendar rollovers. */
internal class ProductionReceiverMapping(private val timestampKey: (Any?) -> String = ::receiverTimestampKey) : VerifiedReceiverMapping {
    override fun mappedRecordJson(record: ChunkRecord): String {
        val parsed = EcmaJson.parse(record.json) as? Map<*, *> ?: error("INVALID_RECORD")
        fun text(value: Any?) = value as? String ?: error("INVALID_RECORD")
        val source = parsed["source"] as? Map<*, *> ?: error("INVALID_RECORD")
        val id = text(parsed["recordId"])
        require(id == record.recordId) { "INVALID_RECORD" }
        return EcmaJson.stringify(linkedMapOf(
            "type" to text(parsed["type"]),
            "source" to text(source["package"]),
            "id" to id,
            "modified" to timestampKey(parsed["lastModifiedTime"]),
            "payload" to EcmaJson.stringify(parsed),
        ))
    }
}
