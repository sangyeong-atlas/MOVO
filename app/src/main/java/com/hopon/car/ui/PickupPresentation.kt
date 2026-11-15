package com.hopon.car.ui

import org.json.JSONObject

/** Same pickupOrder + member-order fallback as routeinfo; never sorts by seat or readiness. */
object PickupPresentation {
    data class Stop(val member:JSONObject,val number:Int?,val next:Boolean) {
        val id get()=member.optString("memberId")
        val boarded get()=member.optString("status")=="boarded"
    }
    fun stops(snapshot:JSONObject):List<Stop> {
        val array=snapshot.optJSONArray("members")
        val members=if(array==null)emptyList() else (0 until array.length()).map{array.getJSONObject(it)}
        val order=snapshot.optJSONArray("pickupOrder")
        val ordered=mutableListOf<JSONObject>()
        if(order!=null)for(i in 0 until order.length())members.firstOrNull{it.optString("memberId")==order.optString(i)}?.let{if(it !in ordered)ordered.add(it)}
        members.forEach{if(it !in ordered)ordered.add(it)}
        val next=snapshot.optJSONObject("route")?.optString("nextMemberId")
        var number=0
        return ordered.map{m->
            val pickup=m.optJSONObject("pickup")
            val located=pickup!=null && pickup.has("lat") && pickup.has("lng") &&
                pickup.optDouble("lat").isFinite() && pickup.optDouble("lng").isFinite()
            Stop(m,if(located)++number else null,m.optString("memberId")==next && m.optString("status")!="boarded")
        }
    }
    fun remaining(snapshot:JSONObject)=stops(snapshot).filter{it.number!=null && !it.boarded}
}
