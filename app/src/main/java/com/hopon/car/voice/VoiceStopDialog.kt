package com.hopon.car.voice

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.hopon.car.ui.Design
import com.hopon.car.ui.Palette
import org.json.JSONObject
import java.time.Instant

/** Search and selection only. Approval stays in the existing driver request screen. */
class VoiceStopDialog(
    private val activity: Activity, private val query: String,
    private val valid: () -> Boolean,
    private val search: ((Result<JSONObject>) -> Unit) -> Unit,
    private val selected: (JSONObject, Int, String) -> Unit,
    private val dismissed: (VoiceStopDialog) -> Unit = {}
) {
    private val d=Design(activity)
    private val body=d.column(Color.WHITE,24)
    private val title=d.text("$query · 경유지 후보",28,Palette.forest,true)
    private val note=d.text("현재 위치 주변의 장소를 찾고 있어요.",20,Palette.secondary)
    private val choices=d.column()
    private val retry=d.button("다시 검색") { load() }
    private val dialog=AlertDialog.Builder(activity).create()
    private var generation=0
    private var closed=false
    private var submitting=false
    private var detourPolls=0
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    init {
        body.addView(title);body.addView(note,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d.dp(10)})
        body.addView(ScrollView(activity).apply{addView(choices)},LinearLayout.LayoutParams(-1,d.dp(260)).apply{topMargin=d.dp(14)})
        val actions=LinearLayout(activity)
        actions.addView(d.button("취소"){dismiss()},LinearLayout.LayoutParams(0,d.dp(64),1f))
        actions.addView(retry,LinearLayout.LayoutParams(0,d.dp(64),1f).apply{marginStart=d.dp(12)})
        body.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d.dp(12)})
        dialog.setView(body,0,0,0,0)
        dialog.setOnDismissListener{handler.removeCallbacksAndMessages(null);closed=true;generation++;dismissed(this)}
    }
    fun show(){
        dialog.show()
        val width=activity.window.decorView.width.takeIf{it>0}?:activity.resources.displayMetrics.widthPixels
        dialog.window?.setLayout(d.dp(760).coerceAtMost(width-d.dp(40)),ViewGroup.LayoutParams.WRAP_CONTENT)
        load()
    }
    private fun load(){
        if(closed)return
        if(!valid()){note.text="여정 상태가 바뀌었어요. 창을 닫고 다시 시도해 주세요.";return}
        val token=++generation;choices.removeAllViews();retry.isEnabled=false
        note.text="현재 위치 주변의 장소를 찾고 있어요."
        search { result ->
            if(closed||token!=generation)return@search
            retry.isEnabled=true
            if(!valid()){note.text="여정 상태가 바뀌었어요. 창을 닫고 다시 시도해 주세요.";return@search}
            result.onFailure{note.text=it.message?:"장소를 찾지 못했어요. 다시 검색해 주세요."}
            result.onSuccess { data ->
                val source=data.optString("source");val revision=data.optInt("revision",-1)
                val searched=runCatching{Instant.parse(data.getString("searchedAt")).toEpochMilli()}.getOrDefault(0)
                note.text=(if(source=="sample-fallback")"시연용 샘플 장소" else "현재 위치 주변 장소")+
                    "\n추가 시간은 네이버 경로 기준 · 체류 제외\n승인 후 기본 내비 경로에 적용 · 최대 2곳"
                val places=data.optJSONArray("places")
                var count=0
                for(i in 0 until (places?.length()?:0)){
                    val p=places?.optJSONObject(i)?:continue
                    val lat=p.optDouble("lat");val lng=p.optDouble("lng");val name=p.optString("name")
                    if(name.isBlank()||name.length>100||!lat.isFinite()||!lng.isFinite()||lat !in -90.0..90.0||lng !in -180.0..180.0)continue
                    count++
                    val place=JSONObject().put("name",name).put("lat",lat).put("lng",lng)
                        .put("id",p.optString("id")).put("address",p.optString("address").take(200))
                        .put("category",p.optString("category").take(80)).put("stayMinutes",20)
                    val card=d.column(Palette.softMint,14)
                    card.addView(d.text(name,25,Palette.forest,true))
                    card.addView(d.text(place.optString("address").ifBlank{"주소 정보 없음"},18,Palette.secondary))
                    val seconds=p.opt("detourSeconds") as? Number
                    val detour=if(seconds!=null)"추가 주행 ${kotlin.math.ceil(seconds.toDouble()/60).toInt()}분 · ${String.format(java.util.Locale.KOREA,"%.1f",p.optDouble("detourMeters",0.0)/1000)}km" else if(p.optString("detourStatus")=="pending")"추가 주행시간 계산 중…" else "추가 주행시간 확인 불가"
                    card.addView(d.text(detour,18,Palette.secondary))
                    card.addView(d.button("이 장소 제안",true){
                        if(submitting||closed)return@button
                        if(!valid()||System.currentTimeMillis()-searched !in 0..120_000){
                            note.text="장소 정보를 다시 검색한 뒤 선택해 주세요.";choices.removeAllViews();return@button
                        }
                        submitting=true;dismiss();selected(place,revision,source)
                    },LinearLayout.LayoutParams(-1,d.dp(64)).apply{topMargin=d.dp(8)})
                    choices.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=d.dp(10)})
                }
                val pending=(0 until (places?.length()?:0)).any{places?.optJSONObject(it)?.optString("detourStatus")=="pending"}
                if(pending&&detourPolls++<10)handler.postDelayed({if(!closed&&generation==token&&!submitting)load()},1500)
                if(count==0)note.text="일치하는 장소가 없어요. 다른 장소 이름으로 다시 말해 주세요."
            }
        }
    }
    fun dismiss(){handler.removeCallbacksAndMessages(null);closed=true;generation++;dialog.dismiss()}
}
