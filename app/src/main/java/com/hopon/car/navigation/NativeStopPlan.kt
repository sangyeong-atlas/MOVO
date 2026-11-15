package com.hopon.car.navigation

import org.json.JSONObject

/** Keeps an existing native route's remaining stops. Never overwrites an unrelated destination. */
data class NativeStopPlan(val destination: NavigationTarget, val waypoints: List<NavigationTarget>) {
    companion object {
        fun create(journey: JSONObject, place: JSONObject, native: NavigationSnapshot): NativeStopPlan {
            require(journey.getJSONObject("room").getString("status") in listOf("pickup", "driving")) { "픽업 또는 주행 중에 경유지를 승인해 주세요." }
            val destination = target(journey.getJSONObject("room").getJSONObject("destination"))
            val requested = target(place)
            require(native.route.value?.routing == false) { "내비게이션이 탐색 중이에요. 완료 후 다시 요청해 주세요." }
            val prior = mutableListOf<NavigationTarget>()
            if(native.activeGuidance) {
                require(destination.matches(native.destination.value)) { "기본 내비의 목적지가 현재 여정과 달라요. 기존 경로를 유지했어요." }
                val points = requireNotNull(native.waypoints.value) { "기존 경유지를 확인하지 못했어요." }
                prior += points.map { observed ->
                    if(observed.latitude == 0.0 && observed.longitude == 0.0) {
                        val course=journey.optJSONArray("course")
                        val candidates=(0 until (course?.length() ?: 0)).map { target(course!!.getJSONObject(it)) }
                            .filter { it.name == observed.name && it.matchesWaypoint(observed) }
                        require(candidates.size == 1) { "기존 내비 경유지 좌표를 확인하지 못했어요. 기존 경로를 유지했어요." }
                        candidates.single()
                    } else NavigationTarget(observed.name, observed.latitude, observed.longitude, "", observed.address.ifBlank { observed.name })
                }
            } else {
                require(native.route.value?.mode in listOf("SAFE_GUIDING", "FREE_DRIVE") &&
                    native.destination.value?.let { it.name.isBlank() && it.latitude == 0.0 && it.longitude == 0.0 } == true &&
                    native.waypoints.value?.isEmpty() == true) { "기존 내비 경로가 비어 있는지 확인하지 못했어요. 기본 내비를 확인해 주세요." }
                // Known pickup points precede leisure stops; no member state is changed here.
                if(journey.getJSONObject("room").getString("status") == "pickup") {
                    val members = journey.optJSONArray("members")
                    val order = journey.optJSONArray("pickupOrder")
                    val ids = (0 until (order?.length() ?: 0)).map { order!!.getString(it) }
                    val ordered = (0 until (members?.length() ?: 0)).map { members!!.getJSONObject(it) }
                        .sortedBy { ids.indexOf(it.optString("memberId")).let { n -> if(n < 0) Int.MAX_VALUE else n } }
                    for(m in ordered) {
                        val pickup = m.optJSONObject("pickup")
                        if(m.optString("status") != "boarded" && pickup != null)
                            prior += target(JSONObject(pickup.toString()).put("name", "${m.optString("displayName", "동승자")} 픽업"))
                    }
                }
                val course = journey.optJSONArray("course")
                for(i in 0 until (course?.length() ?: 0)) prior += target(course!!.getJSONObject(i))
            }
            prior += requested
            val unique = mutableListOf<NavigationTarget>()
            for(p in prior) if(unique.none { same(it,p) }) unique += p
            require(unique.size <= 2) { "기본 내비는 경유지를 최대 2곳까지 지원해요. 기존 경유지를 유지하고 추가하지 않았어요." }
            require(unique.isNotEmpty() && !same(destination, requested)) { "최종 목적지는 경유지로 중복 추가할 수 없어요." }
            return NativeStopPlan(destination, unique)
        }
        private fun same(a: NavigationTarget, b: NavigationTarget) = kotlin.math.abs(a.latitude-b.latitude) < 0.00001 && kotlin.math.abs(a.longitude-b.longitude) < 0.00001
        private fun target(p: JSONObject): NavigationTarget {
            val name = p.optString("name").trim()
            // Kakao/sample IDs are not Pleos POI IDs. Use coordinates, never pass foreign IDs.
            val value = NavigationTarget(name,p.getDouble("lat"),p.getDouble("lng"),"",p.optString("address").ifBlank { name })
            require(value.valid()) { "장소 이름과 좌표를 확인해 주세요." }
            return value
        }
    }
}
