package com.tactilesight.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Is that server up, and does it speak our contract?" — answered from the
 * phone, because that is where the endpoint is typed and where it goes wrong.
 *
 * Probes two shapes, because two different things get pointed at that field:
 *
 * - `GET /health` → `{status, backend}` — our `server/app.py`. Ready.
 * - `GET /v1/models` → `{data:[{id}]}` — an OpenAI-compatible server such as
 *   LM Studio, llama-server or vLLM. **Reachable but wrong**: it answers
 *   `/v1/models` happily and then 404s on `POST /v1/describe`, so the endpoint
 *   looks alive while every press fails. Saying so plainly here is the whole
 *   point — otherwise that failure surfaces as a dead press with no
 *   explanation.
 */
object ServerCheck {

    sealed interface Result {
        /** Our server, speaking the frozen contract. */
        data class Ready(val backend: String) : Result

        /** Alive, but it does not implement `/v1/describe`. */
        data class WrongContract(val models: List<String>) : Result

        data class Unreachable(val detail: String) : Result
    }

    suspend fun probe(baseUrl: String): Result = withContext(Dispatchers.IO) {
        val root = baseUrl.trim().trimEnd('/')
        if (root.isBlank()) return@withContext Result.Unreachable("No address set")

        get("$root/health")?.let { json ->
            if (json.has("status")) {
                return@withContext Result.Ready(json.optString("backend", "unknown"))
            }
        }

        get("$root/v1/models")?.let { json ->
            val models = json.optJSONArray("data")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("id") }
            }.orEmpty()
            if (models.isNotEmpty()) return@withContext Result.WrongContract(models)
        }

        Result.Unreachable("No /health or /v1/models at $root")
    }

    /** Null on any failure — the caller only needs "did this shape answer". */
    private fun get(url: String): JSONObject? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                JSONObject(connection.inputStream.bufferedReader().readText())
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** Short: this runs while someone waits, on a venue network. */
    private const val TIMEOUT_MS = 5_000
}
