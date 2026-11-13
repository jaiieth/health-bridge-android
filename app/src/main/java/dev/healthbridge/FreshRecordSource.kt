package dev.healthbridge

internal fun interface FreshRecordProducer {
    suspend fun produce(accept: suspend (ChunkRecord) -> Unit)
}

internal data class FreshPage<T>(val records: List<T>, val nextToken: String?)

internal suspend fun <T> produceFreshPages(
    readPage: suspend (String?) -> FreshPage<T>,
    accept: suspend (T) -> Unit,
) {
    suspend fun consumePageAndReturnToken(token: String?): String? {
        val page = readPage(token)
        require(page.records.size <= 1) { "FRESH_PAGE_TOO_LARGE" }
        for (record in page.records) accept(record)
        return page.nextToken
    }
    var token: String? = null
    do { token = consumePageAndReturnToken(token) } while (token != null)
}

internal val freshTypeNames = setOf(
    "SleepSessionRecord", "HeartRateVariabilityRmssdRecord", "RestingHeartRateRecord",
    "HeartRateRecord", "StepsRecord", "ExerciseSessionRecord",
)
