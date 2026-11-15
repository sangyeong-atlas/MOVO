package com.hopon.car.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val status: Int, message: String) : Exception(message)

/** Tokens are headers only. No logging of credentials or request bodies. */
class VehicleApi(val base: String, private val token: String = "") {
    @Volatile private var stream: HttpURLConnection? = null

    private fun connection(path: String): HttpURLConnection = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 4000
        readTimeout = 7000
        instanceFollowRedirects = false
        setRequestProperty("Accept", "application/json")
        if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
    }

    fun request(method: String, path: String, body: JSONObject? = null, key: String? = null): JSONObject {
        val c = connection(path)
        try {
            c.requestMethod = method
            c.setRequestProperty("Content-Type", "application/json")
            if (key != null) c.setRequestProperty("Idempotency-Key", key)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val raw = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val result = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            if (status !in 200..299) throw ApiException(status, result.optString("error", "HTTP $status"))
            return result
        } finally { c.disconnect() }
    }

    /** Full snapshots; reconnect always recovers without delta replay. */
    fun events(path: String, alive: () -> Boolean, receive: (JSONObject) -> Unit) {
        val c = connection(path)
        stream = c
        try {
            c.setRequestProperty("Accept", "text/event-stream")
            val status = c.responseCode
            if (status != 200) throw ApiException(status, "Event stream HTTP $status")
            c.inputStream.bufferedReader().use { reader ->
                val data = StringBuilder()
                while (alive()) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) { receive(JSONObject(data.toString())); data.setLength(0) }
                        }
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).trimStart())
                        }
                    }
                }
            }
        } finally { stream = null; c.disconnect() }
    }
    fun cancelEvents() { stream?.disconnect() }
}
