package com.hopon.car.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VoiceCommandsTest {
    private val now = 1_800_000_000_000L
    private fun context(phase: String = "driving") = VoiceCommands.Context(JSONObject()
        .put("room", JSONObject().put("title", "주말 나들이").put("status", phase)
            .put("destination", JSONObject().put("name", "서울숲")))
        .put("route", JSONObject().put("destinationSeconds", 125))
        .put("freshness", "fresh").put("serverTime", Instant.ofEpochMilli(now).toString())
        .put("telemetry", JSONObject().put("source", "simulator").put("receivedAt", Instant.ofEpochMilli(now).toString()).put("observedAt", Instant.ofEpochMilli(now).toString()))
        .put("members", JSONArray().put(JSONObject().put("displayName", "민수").put("status", "outside"))),
        online = true, authRequired = false, lastSync = now, now = now)
    @Test fun stopSpeechProducesOnlyASearchDraft() {
        for(text in listOf("가는 길에 카페 들르자", "카페 들러줘", "성수 온실 카페 들르자", "식당을 경유지로 추가해줘")) {
            val ctx=context();val original=ctx.snapshot.toString()
            val reply=VoiceCommands.answer(text,ctx)
            assertNotNull(text,reply.stopQuery);assertNull(reply.memo);assertTrue(reply.text.contains("운전자 승인"))
            assertEquals(original,ctx.snapshot.toString())
        }
        assertEquals("카페",VoiceStopIntent.query("가는 길에 카페 들르자"))
        assertEquals("카페",VoiceStopIntent.query("가는길에카페들르자"))
        assertEquals("식당",VoiceStopIntent.query("식당을 경유지로 추가해줘"))
    }
    @Test fun stopDraftRequiresCurrentActiveJourneyAndDoesNotAcceptSpeechApproval() {
        for(phase in listOf("planning","arrived","closed"))assertNull(VoiceCommands.answer("카페 들르자",context(phase)).stopQuery)
        for(ctx in listOf(context().copy(online=false),context().copy(authRequired=true),context().copy(busy=true),context().copy(lastSync=now-31000)))
            assertNull(VoiceCommands.answer("카페 들르자",ctx).stopQuery)
        for(text in listOf("승인해줘", "카페 들르지 마", "카페 말고 식당 들르자", "경유지 추가하지 마", "안 들르자"))
            assertNull(text,VoiceCommands.answer(text,context()).stopQuery)
    }
    @Test fun statusAndDestinationUseCurrentJourney() {
        assertTrue(VoiceCommands.answer("여정 상태 알려 줘!", context()).text.contains("이동 중"))
        assertTrue(VoiceCommands.answer("목적지 어디야?", context()).text.contains("서울숲"))
    }
    @Test fun etaIsExplicitlyAnEstimateAndSimulatorIsDisclosed() {
        val reply = VoiceCommands.answer("몇 분 남았어?", context()).text
        assertTrue(reply.contains("3분")); assertTrue(reply.contains("추정")); assertTrue(reply.contains("시뮬레이션"))
    }
    @Test fun offlineSnapshotNeverProducesLiveEta() {
        assertTrue(VoiceCommands.answer("여정 상태", context().copy(online = false)).text.contains("마지막"))
        assertTrue(VoiceCommands.answer("몇 분 남았어", context().copy(online = false)).text.contains("확인할 수 없어요"))
    }
    @Test fun stalePositionAndOldSyncCannotProduceEta() {
        for (age in listOf(-60_000L, 10_000L, 60_000L)) {
            val ctx = context(); ctx.snapshot!!.getJSONObject("telemetry").put("observedAt", Instant.ofEpochMilli(now - age).toString())
            assertTrue(VoiceCommands.answer("언제 도착해", ctx).text.contains("확인할 수 없어요"))
        }
        assertTrue(VoiceCommands.answer("몇 분 남았어", context().copy(lastSync = now - 60_000)).text.contains("확인할 수 없어요"))
    }
    @Test fun staleServerTimeAndReceivedTimeAreRejected() {
        val ctx=context(); ctx.snapshot!!.put("serverTime", Instant.ofEpochMilli(now - 11_000).toString())
        assertTrue(VoiceCommands.answer("몇 분 남았어", ctx).text.contains("확인할 수 없어요"))
        val received=context(); received.snapshot!!.getJSONObject("telemetry").put("receivedAt", Instant.ofEpochMilli(now - 11_000).toString())
        assertTrue(VoiceCommands.answer("몇 분 남았어", received).text.contains("확인할 수 없어요"))
    }
    @Test fun absentAndInvalidEtaAreNotZeroMinutes() {
        for (seconds in listOf<Any>(JSONObject.NULL, -10, "123", 999999999)) {
            val ctx=context(); ctx.snapshot!!.getJSONObject("route").put("destinationSeconds", seconds)
            assertTrue(VoiceCommands.answer("몇 분 남았어", ctx).text.contains("확인할 수 없어요"))
        }
    }
    @Test fun missingTelemetryAndLostFreshnessAreRejected() {
        val ctx=context(); ctx.snapshot!!.remove("telemetry")
        assertTrue(VoiceCommands.answer("몇 분 남았어", ctx).text.contains("확인할 수 없어요"))
        val lost=context(); lost.snapshot!!.put("freshness", "lost")
        assertTrue(VoiceCommands.answer("몇 분 남았어", lost).text.contains("확인할 수 없어요"))
    }
    @Test fun phasesHaveDifferentAnswers() {
        assertTrue(VoiceCommands.answer("몇 분 남았어", context("planning")).text.contains("출발 전"))
        assertTrue(VoiceCommands.answer("몇 분 남았어", context("arrived")).text.contains("이미"))
        assertTrue(VoiceCommands.answer("여정 상태", context("closed")).text.contains("종료"))
    }
    @Test fun memberAnswerDoesNotInventGps() {
        val reply=VoiceCommands.answer("민수 어디야", context()).text
        assertTrue(reply.contains("나왔어요")); assertTrue(reply.contains("GPS 위치를 확인한 정보는 아니에요"))
    }
    @Test fun duplicateNamesAndUnknownMembersAreHandled() {
        val ctx=context(); ctx.snapshot!!.getJSONArray("members").put(JSONObject().put("displayName", "민수"))
        assertTrue(VoiceCommands.answer("민수 어디야", ctx).text.contains("여러 명"))
        assertTrue(VoiceCommands.answer("없는사용자 어디야", ctx).text.contains("찾지 못했어요"))
    }
    @Test fun memoIsADraftAndDoesNotMutateSnapshot() {
        val ctx=context("arrived"); val before=ctx.snapshot.toString()
        val reply=VoiceCommands.answer("주차 메모 지하 3층 기둥 12", ctx)
        assertEquals("지하 3층 기둥 12", reply.memo); assertTrue(reply.text.contains("확인"))
        assertEquals(before, ctx.snapshot.toString())
    }
    @Test fun dictationAcceptsBareParkingLocationOnlyAfterArrival() {
        assertEquals("B3 기둥 12", VoiceCommands.answer("B3 기둥 12", context("arrived"), true).memo)
        assertNull(VoiceCommands.answer("B3 기둥 12", context(), true).memo)
        assertNull(VoiceCommands.answer("B3 기둥 12", context("closed"), true).memo)
    }
    @Test fun emptyOverlongPendingAndBusyMemosCannotBeSaved() {
        val ctx=context("arrived")
        assertNull(VoiceCommands.answer("주차 메모", ctx).memo)
        assertNull(VoiceCommands.answer("가".repeat(161), ctx, true).memo)
        assertNull(VoiceCommands.answer("B3", ctx.copy(parkingPending=true), true).memo)
        assertNull(VoiceCommands.answer("B3", ctx.copy(busy=true), true).memo)
    }
    @Test fun noSessionOrExpiredAuthNeverReturnsPrivateJourneyDetails() {
        assertTrue(VoiceCommands.answer("여정 상태", context().copy(snapshot=null)).text.contains("연결된 여정이 없어요"))
        assertFalse(VoiceCommands.answer("목적지", context().copy(authRequired=true)).text.contains("서울숲"))
        assertNull(VoiceCommands.answer("주차 메모 B3", context("arrived").copy(authRequired=true)).memo)
    }
    @Test fun unsupportedCommandsCannotChangeJourney() {
        val ctx=context(); val before=ctx.snapshot.toString()
        for (command in listOf("여정 마쳐", "카페 들르자", "승인해", "열선 켜", "출발해")) {
            assertNull(VoiceCommands.answer(command, ctx).memo)
        }
        assertEquals(before, ctx.snapshot.toString())
    }
}
