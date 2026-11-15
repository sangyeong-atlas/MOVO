package com.hopon.car.voice

import org.json.JSONObject
import java.time.Instant

/** Main-thread owner; playback never acknowledges or changes a passenger's Journey. */
class PassengerAnnouncements(
    private val outputFactory: () -> VoiceOutput,
    private val timer: VoiceTimer,
    private val loadSeen: (String) -> Set<String>?,
    private val saveSeen: (String, Set<String>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val unavailable: () -> Unit = {},
    private val playbackChanged: (Boolean) -> Unit = {}
) {
    private data class Item(val text: String, val created: Long)
    private var scope = ""
    private val seen = linkedSetOf<String>()
    private val pending = ArrayDeque<Item>()
    private var output: VoiceOutput? = null
    private var cancelTimer: (() -> Unit)? = null
    private var generation = 0
    private var suspended = false
    private var enabled = false

    /** The first snapshot is a baseline, so pairing never reads out old conversation history. */
    fun update(key: String, snapshot: JSONObject?, allowPlayback: Boolean) {
        if (key != scope) {
            stop(); scope = key; seen.clear()
            loadSeen(key)?.let { seen.addAll(it) }
        }
        enabled = allowPlayback
        if (!enabled) stop()
        if (snapshot == null || key.isBlank()) return
        val first = loadSeen(key) == null
        val messages = snapshot.optJSONArray("messages")
        var changed = first
        for (i in 0 until (messages?.length() ?: 0)) {
            val message = messages?.optJSONObject(i) ?: continue
            val id = message.optString("id")
            if (id.isBlank() || !seen.add(id)) continue
            changed = true
            val text = spokenText(message) ?: continue
            val created = runCatching { Instant.parse(message.getString("createdAt")).toEpochMilli() }.getOrNull() ?: continue
            if (!first && enabled && fresh(created)) {
                if (pending.size == 10) pending.removeFirst()
                pending.addLast(Item(text, created))
            }
        }
        if (changed) {
            // Persist receipt before playback: reconnects and process death cannot repeat announcements.
            while (seen.size > 256) seen.remove(seen.first())
            saveSeen(key, seen.toSet())
        }
        drain()
    }
    fun suspendPlayback(value: Boolean) {
        suspended = value
        if (value) dispose() else drain()
    }
    fun stop() { pending.clear(); dispose() }
    private fun fresh(created: Long) = now() - created in -5_000L..120_000L
    private fun dispose() {
        generation++; cancelTimer?.invoke(); cancelTimer = null
        val old = output; output = null
        runCatching { old?.close() }
        playbackChanged(false)
    }
    private fun drain() {
        if (!enabled || suspended || output != null) return
        while (pending.isNotEmpty() && !fresh(pending.first().created)) pending.removeFirst()
        val item = pending.removeFirstOrNull() ?: return
        val token = generation
        fun failed() {
            if (token != generation) return
            stop(); unavailable()
        }
        cancelTimer = timer.after(20_000) { failed() }
        runCatching {
            playbackChanged(true)
            val client = outputFactory(); output = client
            var started = false
            client.initialize(object : VoiceOutput.Events {
                override fun ready() {
                    if (token != generation || started) return
                    started = true
                    runCatching { client.speak(item.text) }.onFailure { failed() }
                }
                override fun done() {
                    if (token != generation || !started) return
                    dispose(); drain()
                }
                override fun failed(code: String) { failed() }
            })
        }.onFailure { failed() }
    }
    companion object {
        /** Only supported quick-message types are spoken. Server text never becomes a voice command. */
        fun spokenText(message: JSONObject): String? {
            val text = when (message.optString("type")) {
                "late_5min" -> "5분 늦어요"
                "came_out" -> "나왔어요"
                "arrived" -> "도착했어요"
                else -> return null
            }
            if (message.optString("memberId").isBlank()) return null
            val name = message.optString("displayName").replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").trim().take(30).ifBlank { "동승자" }
            return "${name}님의 메시지입니다. $text."
        }
    }
}
