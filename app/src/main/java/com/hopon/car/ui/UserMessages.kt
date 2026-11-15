package com.hopon.car.ui

import android.content.Context
import com.hopon.car.R

/** UI-only translation. Never write translated messages back to snapshots, API bodies or preferences. */
object UserMessages {
    private val messages = mapOf(
        "진행 중인 여정을 마친 뒤 파티방을 선택해 주세요." to R.string.garage_locked,
        "진행 중인 여정을 마친 뒤 파티방을 연결해 주세요." to R.string.garage_locked,
        "요청 처리 완료 후 파티방을 선택해 주세요." to R.string.garage_wait,
        "요청 처리 완료 후 연결해 주세요." to R.string.garage_wait,
        "이 파티방을 다시 연결해 주세요. 모바일에서 새 코드를 발급받을 수 있어요." to R.string.garage_reconnect,
        "완료된 여정은 새 연결 코드로 다시 시작해 주세요." to R.string.garage_completed,
        "Enter an HTTP(S) backend address" to R.string.msg_address,
        "Enter the six-digit pairing code" to R.string.msg_pair_code,
        "Sync queued parking before pairing another journey" to R.string.msg_queued_pair,
        "Queued parking belongs to the previous journey. Pair that journey first." to R.string.msg_previous_parking,
        "Wait for parking acknowledgement" to R.string.msg_parking_ack,
        "Save parking before completing the journey" to R.string.msg_save_before_close,
        "Arrive before saving parking" to R.string.msg_arrive_before_park,
        "Parking is already queued" to R.string.msg_parking_queued,
        "No observed simulator position yet" to R.string.msg_no_position,
        "Interrupted application; not repeated automatically" to R.string.msg_interrupted,
        "Unsupported seat or capability" to R.string.msg_unsupported_seat,
        "Invalid climate value" to R.string.msg_climate_value,
        "Unsupported request" to R.string.msg_unsupported_request,
        "Could not persist simulator result" to R.string.msg_persist,
        "Simulator failure" to R.string.msg_simulator,
        "Simulator applied; no physical vehicle control" to R.string.msg_simulator_applied,
        "Driver approved; awaiting simulator" to R.string.msg_driver_approved,
        "Declined by driver" to R.string.msg_driver_declined,
        "Connection failed" to R.string.msg_connection,
        "Request rejected" to R.string.msg_rejected,
        "Request too large" to R.string.msg_large,
        "JSON object required" to R.string.msg_request_format,
        "Invalid request fields" to R.string.msg_fields,
        "Internal server error" to R.string.msg_server,
        "Invite not found" to R.string.msg_invite,
        "Room no longer accepting members" to R.string.msg_closed_join,
        "Passenger limit reached" to R.string.msg_capacity,
        "Name required" to R.string.msg_name,
        "Not found" to R.string.msg_not_found,
        "Room not found" to R.string.msg_room,
        "Valid room token required" to R.string.msg_auth,
        "Host only" to R.string.msg_host,
        "Invalid phase transition" to R.string.msg_phase,
        "Confirm boarding first" to R.string.msg_board_first,
        "Member edits locked in this phase" to R.string.msg_member_locked,
        "Own member only" to R.string.msg_own_member,
        "Member not found" to R.string.msg_member,
        "Invalid member status" to R.string.msg_member_status,
        "Boarding controlled by host" to R.string.msg_boarding_owner,
        "Invalid coordinates" to R.string.msg_coordinates,
        "Method not allowed" to R.string.msg_method,
        "Parking requires arrived phase" to R.string.msg_park_phase,
        "Invalid message type" to R.string.msg_message_type,
        "UTC offset required" to R.string.msg_timezone,
        "Journey is closed" to R.string.msg_closed,
        "Finish approved requests before closing" to R.string.msg_unfinished_requests,
        "Idempotency-Key required" to R.string.msg_request_key,
        "Invalid status" to R.string.msg_status,
        "Invalid state" to R.string.msg_state,
        "Invalid sequence" to R.string.msg_sequence,
        "Invalid source" to R.string.msg_source,
        "Invalid kind" to R.string.msg_kind,
        "Invalid feature" to R.string.msg_feature,
        "Invalid order" to R.string.msg_order,
        "Invalid preference" to R.string.msg_preference,
        "Invalid preferences" to R.string.msg_preferences,
        "Invalid avatar" to R.string.msg_avatar,
        "Invalid vehicle profile" to R.string.msg_vehicle_profile,
        "Boolean required" to R.string.msg_boolean,
        "Capability must be boolean or null" to R.string.msg_capability,
        "Seat values must be boolean or null" to R.string.msg_seat_value,
        "페어링 코드가 만료되었어요" to R.string.msg_expired_code,
        "순서에 맞는 여정 전환이 필요해요" to R.string.msg_phase_order,
        "멤버가 없어요" to R.string.msg_missing_crew,
        "모든 멤버를 한 번씩 지정해 주세요" to R.string.msg_crew_order,
        "lat/lng 숫자를 입력해 주세요" to R.string.msg_lat_lng,
    )
    private val knownKorean = setOf(
        "기존 내비 경유지 좌표를 확인하지 못했어요. 기존 경로를 유지했어요.",
        "내비게이션에 연결하지 못했어요. 기본 내비 앱을 확인해 주세요.",
        "내비게이션 경로 적용을 확인하지 못했어요. 기본 내비에서 경로를 확인해 주세요. 자동 재요청하지 않아요.",
        "내비게이션 응답 시간이 초과됐어요. 기본 내비에서 경로를 확인해 주세요. 자동 재요청하지 않아요.",
        "픽업 또는 주행 중에 경유지를 승인해 주세요.",
        "내비게이션이 탐색 중이에요. 완료 후 다시 요청해 주세요.",
        "기본 내비의 목적지가 현재 여정과 달라요. 기존 경로를 유지했어요.",
        "기존 경유지를 확인하지 못했어요.",
        "기존 내비 경로가 비어 있는지 확인하지 못했어요. 기본 내비를 확인해 주세요.",
        "기본 내비는 경유지를 최대 2곳까지 지원해요. 기존 경유지를 유지하고 추가하지 않았어요.",
        "최종 목적지는 경유지로 중복 추가할 수 없어요.",
        "장소 이름과 좌표를 확인해 주세요.",

        "같은 키에 다른 요청을 보낼 수 없어요",
        "곡 제목을 입력해 주세요",
        "기기 시각 또는 오래된 관측을 확인해 주세요",
        "다른 사람이 코스를 수정했어요. 새로 확인해 주세요",
        "도착 후 방문을 확인해 주세요",
        "도착 후 주차를 저장해 주세요",
        "만료되거나 폐기된 탑승권이에요",
        "먼저 테스트 계정으로 로그인해 주세요",
        "세션 또는 여행 참가 권한을 확인해 주세요",
        "승인 후 적용 결과를 보내 주세요",
        "여유 시간 범위 0~120분",
        "여행을 찾을 수 없어요",
        "요청이 많아요. 60초 후 다시 시도해 주세요",
        "요청이 없어요",
        "위치 공유가 철회되었어요",
        "위치 정보 또는 검색어가 필요해요",
        "이 기능은 차량 지원 확인이 필요해요",
        "이 작업의 권한이 없어요",
        "이름 또는 비밀번호를 확인해 주세요",
        "이름과 6자 이상 테스트 비밀번호를 입력해 주세요",
        "이미 등록된 이름이에요. 로그인해 주세요",
        "종료된 여행이에요",
        "좌표 범위를 확인해 주세요",
        "좌표가 필요해요",
        "주행 중에는 차량 화면을 이용해 주세요",
        "지원되지 않는 기능이에요",
        "차량 출처는 차량만 기록해요",
        "참가가 마감되었어요",
        "체류 시간을 확인해 주세요",
        "코스는 20곳까지예요",
        "탑승을 먼저 확인해 주세요",
        "프로필 형식을 확인해 주세요",
        "픽업 또는 주행 중 요청해 주세요",
        "픽업 변경이 마감되었어요",
        "픽업 시작 후 전송해 주세요",
        "픽업 지점 공유 동의가 필요해요",
        "확정 경로 변경은 경유지 요청을 이용해 주세요",
        "휴대폰 위치 확인이 필요해요",
    )
    fun error(context: Context, raw: String): String {
        messages[raw]?.let { return context.getString(it) }
        if (raw in knownKorean) return raw
        val text = raw.lowercase(java.util.Locale.ROOT)
        val id = when {
            listOf("expected scheme", "expected authority", "illegal character", "expected hostname").any { it in text } -> R.string.msg_address
            "timed out" in text || "timeout" in text -> R.string.msg_timeout
            listOf("unable to resolve host", "unknownhost", "failed to connect", "connection refused", "network is unreachable", "socket closed", "connection reset").any { it in text } -> R.string.msg_connection
            Regex("(?:Event stream )?HTTP (401|403)").matches(raw) -> R.string.msg_auth
            Regex("(?:Event stream )?HTTP 5[0-9]{2}").matches(raw) -> R.string.msg_server
            else -> R.string.msg_unknown
        }
        return context.getString(id)
    }
    fun mapError(context: Context, raw: String): String = context.getString(
        if (Regex("Map provider HTTP (403|429)").matches(raw)) R.string.msg_map_limit else R.string.msg_map_unavailable
    )
}
