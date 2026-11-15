package com.hopon.car

import android.test.AndroidTestCase
import com.hopon.car.ui.UserMessages
import org.json.JSONObject

@Suppress("DEPRECATION")
class KoreanPresentationTest : AndroidTestCase() {
    fun testFailurePresentationDoesNotChangeProtocolOrUserData() {
        val data = JSONObject().put("status", "failed")
            .put("reason", "Interrupted application; not repeated automatically")
            .put("title", "My Journey").put("displayName", "Passenger One")
        val before = data.toString()
        val shown = UserMessages.error(context, data.getString("reason"))
        assertTrue(shown.contains("자동으로 다시 실행하지"))
        assertEquals(before, data.toString())
        assertTrue(UserMessages.error(context, "페어링 코드가 만료되었어요").contains("차량 연결 코드"))
        assertEquals("탑승 완료", context.getString(R.string.boarded))
        assertTrue(context.getString(R.string.v3_seat_description, 2, data.getString("displayName"), context.getString(R.string.boarded)).contains("Passenger One"))
    }
    fun testUnknownAndNetworkFailuresDoNotExposeRawServerDetails() {
        for (raw in listOf("unexpected failure with private debug details", "오류 상세 unknown-token", "Unable to resolve host private.example", "Read timed out")) {
            val shown = UserMessages.error(context, raw)
            assertTrue(shown.any { it in '가'..'힣' })
            assertFalse(shown.contains("private"))
            assertFalse(shown.contains("unknown-token"))
            assertFalse(shown == raw)
        }
        assertTrue(UserMessages.mapError(context, "Map provider HTTP 429").contains("요청을 제한"))
        assertTrue(UserMessages.error(context, "HTTP 503").contains("서버에서 오류"))
    }
}
