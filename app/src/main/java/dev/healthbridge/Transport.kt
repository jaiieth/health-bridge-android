package dev.healthbridge

import java.net.URI
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object Transport {
    fun validateUrl(value: String): String {
        val uri = URI(value)
        require(
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535)
        ) {
            "Use an HTTPS URL without user info or fragment"
        }
        require(value.none { it.isWhitespace() || it.isISOControl() })
        return value
    }

    fun signature(body: ByteArray, token: String): String =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            doFinal(body).joinToString("") { "%02x".format(it.toInt() and 255) }
        }

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    fun send(endpoint: String, token: String, batch: Pending): Int {
        val request =
            Request.Builder()
                .url(validateUrl(endpoint))
                .header("Authorization", "Bearer $token")
                .header("X-Health-Bridge-Signature", "sha256=" + signature(batch.body, token))
                .header("X-Health-Bridge-Batch-Id", batch.batchId)
                .post(batch.body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        return client.newCall(request).execute().use { it.code }
    }
}

object RetryPolicy {
    const val MAX = 5

    fun delayMillis(attempt: Int): Long = 30_000L * (1L shl (attempt - 1).coerceIn(0, 4))

    fun exhausted(attempt: Int) = attempt >= MAX
}
