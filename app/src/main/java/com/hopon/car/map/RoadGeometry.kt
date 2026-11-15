package com.hopon.car.map

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Geometry is shared by the server; stop markers are never inferred from road vertices. */
object RoadGeometry {
    fun pairs(array: JSONArray?): List<Pair<Double, Double>> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { i ->
            val p = array.optJSONArray(i) ?: return@mapNotNull null
            val lat = p.optDouble(0); val lng = p.optDouble(1)
            if (lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) lat to lng else null
        }

    fun path(snapshot: JSONObject): List<Pair<Double, Double>> {
        val route = snapshot.optJSONObject("route") ?: return emptyList()
        return if (route.optString("source") == "naver" && route.optString("status") == "ready") pairs(route.optJSONArray("polyline")) else emptyList()
    }

    fun stops(snapshot: JSONObject): List<Pair<Double, Double>> {
        val explicit = pairs(snapshot.optJSONObject("route")?.optJSONArray("stops"))
        if (explicit.isNotEmpty()) return explicit
        // Older servers still have sparse demo geometry; use it for pins only.
        return pairs(snapshot.optJSONObject("route")?.optJSONArray("polyline"))
    }

    /** Keep Journey/offline ACK recovery, but do not persist provider road results. */
    fun forStorage(snapshot: JSONObject): JSONObject {
        val saved=JSONObject(snapshot.toString())
        val route=saved.optJSONObject("route")?:return saved
        if(route.optString("source")!="naver")return saved
        route.put("source","unavailable").put("status","unavailable").put("label","도로 경로 재조회 필요")
            .put("polyline",JSONArray()).put("remainingMeters",JSONObject.NULL).put("drivingSeconds",JSONObject.NULL)
            .put("destinationSeconds",JSONObject.NULL).put("recommendedDepartureAt",JSONObject.NULL)
        route.remove("routeId");route.remove("queriedAt")
        route.optJSONObject("perMemberETA")?.let { etas ->
            etas.keys().forEach { id -> etas.optJSONObject(id)?.put("seconds",JSONObject.NULL) }
        }
        return saved
    }

    /** Advance the explicitly labelled simulator along road segments, never toward vertex #1 forever. */
    fun advance(lat: Double, lng: Double, path: List<Pair<Double, Double>>, meters: Double = 20.0): Pair<Double, Double>? {
        if (path.size < 2) return null
        val sx = 111000.0 * cos(Math.toRadians(lat)); val sy = 111000.0
        fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot((a.first-b.first)*sy,(a.second-b.second)*sx)
        var best = Double.POSITIVE_INFINITY; var index = 0; var projected = lat to lng
        path.zipWithNext().forEachIndexed { i, (a,b) ->
            val dx=(b.second-a.second)*sx; val dy=(b.first-a.first)*sy
            val length=dx*dx+dy*dy
            val f=if(length==0.0) 0.0 else (((lng-a.second)*sx*dx+(lat-a.first)*sy*dy)/length).coerceIn(0.0,1.0)
            val p=(a.first+(b.first-a.first)*f) to (a.second+(b.second-a.second)*f)
            val d=distance(lat to lng,p)
            if(d<best){best=d;index=i;projected=p}
        }
        if(best>100.0)return null // Wait for a new route instead of teleporting to a distant road.
        var remaining=meters;var current=projected
        for(i in index+1 until path.size){
            val next=path[i];val d=distance(current,next)
            if(d>remaining){val f=remaining/d;return (current.first+(next.first-current.first)*f) to (current.second+(next.second-current.second)*f)}
            remaining-=d;current=next
        }
        return current
    }
}
