package com.hopon.car.data

import org.json.JSONObject
import java.time.OffsetDateTime

/** Reject another journey and old state; allow same-event freshness updates. */
internal object SnapshotOrder {
    fun accepts(previous: JSONObject?, incoming: JSONObject, tripId: String): Boolean {
        if (incoming.optJSONObject("room")?.optString("roomId") != tripId) return false
        if (previous == null) return true
        val current = previous.optLong("eventId", -1)
        val next = incoming.optLong("eventId", -1)
        if (next < current) return false
        if (next > current) return true
        val before = runCatching { OffsetDateTime.parse(previous.getString("serverTime")).toInstant() }.getOrNull()
        val after = runCatching { OffsetDateTime.parse(incoming.getString("serverTime")).toInstant() }.getOrNull()
        return before == null || (after != null && !after.isBefore(before))
    }
}
