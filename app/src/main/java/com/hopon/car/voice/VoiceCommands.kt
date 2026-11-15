package com.hopon.car.voice

import org.json.JSONObject
import java.time.Instant
import kotlin.math.ceil

/** Read-only interpretation. A memo is a draft; only the existing parking confirmation may write it. */
object VoiceCommands {
    data class Context(val snapshot: JSONObject?, val online: Boolean, val authRequired: Boolean,
                       val lastSync: Long, val now: Long = System.currentTimeMillis(),
                       val parkingPending: Boolean = false, val busy: Boolean = false)
    data class Reply(val text: String, val memo: String? = null, val stopQuery: String? = null)
    private val punctuation = Regex("[?!.,。？！]")
    private fun normalized(text: String) = text.lowercase().replace(punctuation, "").replace(Regex("\\s+"), "")
    fun answer(utterance: String, context: Context, dictateMemo: Boolean = false): Reply {
        val text = utterance.trim()
        val command = normalized(text)
        val snapshot = context.snapshot ?: return Reply("연결된 여정이 없어요. 먼저 파티방을 연결해 주세요.")
        if (context.authRequired) return Reply("여정 연결을 다시 확인해 주세요. 인증이 필요한 상태예요.")
        val room = snapshot.optJSONObject("room") ?: return Reply("여정 정보를 아직 불러오지 못했어요.")
        val phase = room.optString("status")
        val memoPrefix = Regex("^주차\\s*메모(?:\\s*저장)?[ :：,]*")
        if (dictateMemo || memoPrefix.containsMatchIn(text)) {
            if (phase != "arrived") return Reply("주차 메모는 목적지에 도착한 뒤 저장할 수 있어요.")
            if (context.parkingPending || context.busy) return Reply("이전 요청을 처리하고 있어요. 완료 후 다시 말해 주세요.")
            val memo = (if (dictateMemo) text else text.replaceFirst(memoPrefix, "")).trim()
            if (memo.isBlank()) return Reply("주차 메모 뒤에 위치를 말해 주세요. 예를 들어 주차 메모 지하 3층 기둥 12라고 말해 주세요.")
            if (memo.length > 160) return Reply("주차 메모는 160자 이내로 짧게 말해 주세요.")
            return Reply("주차 메모를 확인한 뒤 저장 버튼을 눌러 주세요.", memo)
        }
        val fresh = context.online && context.now - context.lastSync in 0..30_000
        VoiceStopIntent.query(text)?.let { query ->
            if (phase !in setOf("pickup", "driving")) return Reply("경유지 제안은 픽업 또는 주행 중에 사용할 수 있어요.")
            if (!fresh) return Reply("최신 여정 연결을 확인한 뒤 경유지를 제안해 주세요.")
            if (context.busy) return Reply("이전 요청을 처리하고 있어요. 완료 후 다시 말해 주세요.")
            return Reply("${query} 후보를 확인해 주세요. 선택한 장소는 운전자 승인 후에만 경로에 반영돼요.", stopQuery = query)
        }
        val prefix = if (fresh || phase == "closed") "" else "최신 상태를 확인할 수 없어 마지막으로 받은 정보를 안내할게요. "
        return when {
            command in setOf("여정상태", "여정상태알려줘", "현재상태", "현재상태알려줘", "지금어디까지왔어", "여정알려줘") -> {
                val state = when (phase) { "planning" -> "출발 준비 중이에요"; "pickup" -> "동승자 픽업 중이에요"; "driving" -> "목적지로 이동 중이에요"; "arrived" -> "목적지에 도착했어요"; "closed" -> "종료됐어요"; else -> "상태를 확인하고 있어요" }
                Reply(prefix + "${room.optString("title", "현재 여정")}. $state.")
            }
            command in setOf("몇분남았어", "얼마나남았어", "언제도착해", "도착시간", "도착시간알려줘", "남은시간알려줘") -> {
                if (phase in setOf("arrived", "closed")) return Reply(prefix + "이미 목적지에 도착한 여정이에요.")
                if (phase == "planning") return Reply("아직 출발 전이에요. 출발 후 남은 시간을 확인해 주세요.")
                val seconds = snapshot.optJSONObject("route")?.opt("destinationSeconds") as? Number
                val telemetry = snapshot.optJSONObject("telemetry")
                val staleMs = ((snapshot.optJSONObject("thresholds")?.optDouble("stale", 10.0) ?: 10.0)
                    .takeIf { it.isFinite() && it > 0 } ?: 10.0).coerceAtMost(30.0).times(1000).toLong()
                fun recent(value: String?) = runCatching { context.now - Instant.parse(value).toEpochMilli() }
                    .getOrNull()?.let { it in 0 until staleMs } == true
                val valid = fresh && recent(telemetry?.optString("observedAt")) &&
                    recent(telemetry?.optString("receivedAt")) && recent(snapshot.optString("serverTime")) &&
                    snapshot.optString("freshness") == "fresh" && seconds != null &&
                    seconds.toDouble().isFinite() && seconds.toDouble() >= 0 && seconds.toDouble() <= 604800
                if (!valid) Reply("현재 남은 시간을 확인할 수 없어요. 최신 위치와 경로 정보가 필요해요.")
                else Reply("${if (telemetry?.optString("source") == "simulator") "시뮬레이션 기준 " else ""}목적지까지 약 ${ceil(seconds!!.toDouble() / 60).toInt()}분으로 추정돼요. 실제 내비게이션 도착 시간은 아직 연결되지 않았어요.")
            }
            command in setOf("목적지어디야", "어디로가", "목적지알려줘", "목적지") ->
                Reply(prefix + (room.optJSONObject("destination")?.optString("name")?.takeIf { it.isNotBlank() }?.let { "목적지는 $it 이에요." } ?: "등록된 목적지를 확인할 수 없어요."))
            command.endsWith("어디야") || command.endsWith("상태알려줘") -> {
                val name = command.removeSuffix("어디야").removeSuffix("상태알려줘")
                val members = snapshot.optJSONArray("members")
                val matches = (0 until (members?.length() ?: 0)).mapNotNull { members?.optJSONObject(it) }
                    .filter { normalized(it.optString("displayName")) == name }
                if (matches.size != 1) Reply(if (matches.isEmpty()) "그 이름의 파티원을 찾지 못했어요." else "같은 이름의 파티원이 여러 명이에요. 화면에서 확인해 주세요.")
                else {
                    val member = matches.single()
                    val state = when (member.optString("status")) { "preparing" -> "준비 중"; "outside" -> "픽업 장소에 나왔어요"; "boarded" -> "탑승 완료"; "dropped" -> "하차 완료"; else -> "상태 확인이 필요해요" }
                    Reply(prefix + "${member.optString("displayName")} 님은 $state. 현재 GPS 위치를 확인한 정보는 아니에요.")
                }
            }
            else -> Reply("여정 상태 알려줘, 몇 분 남았어, 목적지 어디야, 가는 길에 카페 들르자, 또는 주차 메모 뒤에 내용을 말해 주세요.")
        }
    }
}
