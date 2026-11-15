package com.hopon.car.ui

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

object DrivingPresentation {
    /** Anchor the estimate to the snapshot time, never to the phone's current clock. */
    fun arrival(snapshot:JSONObject,online:Boolean):Instant? {
        if(!online)return null
        val seconds=snapshot.optJSONObject("route")?.opt("destinationSeconds") as? Number ?: return null
        val value=seconds.toDouble()
        if(!value.isFinite() || value<0 || value>Long.MAX_VALUE.toDouble())return null
        return runCatching{OffsetDateTime.parse(snapshot.getString("serverTime")).toInstant().plusSeconds(kotlin.math.ceil(value).toLong())}.getOrNull()
    }
    /** All actionable requests, plus the most recent completed result; no proposal becomes a course stop here. */
    fun requests(snapshot:JSONObject):List<JSONObject> {
        val array=snapshot.optJSONArray("requests")?:return emptyList()
        val all=(0 until array.length()).map{array.getJSONObject(it)}
        val active=all.filter{it.optString("status") in listOf("pending","approved")}
        val completed=all.asReversed().filter{it.optString("status") in listOf("applied","rejected","failed")}.maxByOrNull{
            runCatching{OffsetDateTime.parse(it.optString("updatedAt",it.optString("createdAt"))).toInstant()}.getOrDefault(Instant.MIN)
        }
        return active+if(completed==null)emptyList() else listOf(completed)
    }
}
