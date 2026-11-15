package com.hopon.car

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.hopon.car.ui.UserMessages
import com.hopon.car.ui.BrandText
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Parked-only prototype. No distractionOptimized declaration until CarUxRestrictions integration. */
class LegacyActivity : Activity() {
 private val ink=Color.rgb(13,23,31)
 private val panel=Color.rgb(24,38,48)
 private val muted=Color.rgb(163,181,188)
 private val green=Color.rgb(155,239,197)
 private val main=Handler(Looper.getMainLooper())
 private val worker=Executors.newSingleThreadExecutor()
 private val prefs by lazy {getSharedPreferences("hopon",MODE_PRIVATE)}
 private var roomId=""
 private var token=""
 private var snapshot:JSONObject?=null
 private var tab=0
 private var busy=false
 private var online=false
 private var active=false
 private var lastError=""
 private var lastSync=0L
 private lateinit var body:LinearLayout
 private val poll=object:Runnable {override fun run(){if(active){refresh();main.postDelayed(this,3000)}}}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=ink;window.navigationBarColor=ink
 roomId=prefs.getString("room","")!!;token=prefs.getString("token","")!!
 snapshot=prefs.getString("snapshot",null)?.let{runCatching{JSONObject(it)}.getOrNull()};render()}
 override fun onStart(){super.onStart();active=true;main.post(poll)}
 override fun onStop(){active=false;main.removeCallbacks(poll);super.onStop()}
 override fun onDestroy(){worker.shutdown();super.onDestroy()}
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 private fun bg(color:Int,radius:Int=18)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
 private fun column(parent:LinearLayout,color:Int?=null):LinearLayout=LinearLayout(this).also{it.orientation=LinearLayout.VERTICAL;it.setPadding(dp(20),dp(16),dp(20),dp(16));if(color!=null)it.background=bg(color);parent.addView(it,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)})}
 private fun label(parent:LinearLayout,value:String,size:Int=18,color:Int=Color.WHITE,bold:Boolean=false):TextView=TextView(this).also{it.text=BrandText.display(value);it.textSize=size.toFloat();it.setTextColor(color);if(bold)it.setTypeface(null,Typeface.BOLD);it.setPadding(0,dp(5),0,dp(7));parent.addView(it)}
 private fun button(parent:LinearLayout,title:String,primary:Boolean=false,enabled:Boolean=true,click:()->Unit){val v=Button(this);v.text=BrandText.display(title);v.textSize=17f;v.isAllCaps=false;v.setTextColor(if(primary)ink else Color.WHITE);v.background=bg(if(primary)green else Color.rgb(44,61,73),12);v.isEnabled=enabled&&!busy;v.alpha=if(v.isEnabled)1f else .45f;v.setOnClickListener{click()};parent.addView(v,LinearLayout.LayoutParams(-1,dp(58)).apply{topMargin=dp(8);bottomMargin=dp(6)})}
 private fun api(method:String,path:String,data:JSONObject?=null,auth:String=token):JSONObject {
 val con=URL("http://10.0.2.2:8787$path").openConnection() as HttpURLConnection
 try {con.requestMethod=method;con.connectTimeout=4000;con.readTimeout=5000;con.setRequestProperty("Content-Type","application/json");if(auth.isNotEmpty())con.setRequestProperty("Authorization","Bearer $auth")
 if(data!=null){con.doOutput=true;con.outputStream.use{it.write(data.toString().toByteArray(Charsets.UTF_8))}}
 val code=con.responseCode;val text=(if(code<400)con.inputStream else con.errorStream)?.bufferedReader()?.use{it.readText()}?:"{}"
 if(code>=400)throw IllegalStateException(JSONObject(text).optString("error","HTTP $code"))
 return JSONObject(text)
 } finally {con.disconnect()}
 }
 private fun action(task:()->Unit){if(busy)return;busy=true;render();worker.execute{try{task();if(roomId.isNotEmpty())snapshot=api("GET","/rooms/$roomId/snapshot");online=true;lastError="";lastSync=System.currentTimeMillis();snapshot?.let{prefs.edit().putString("snapshot",it.toString()).apply()}}catch(e:Exception){online=false;lastError=e.message?:"연결 오류"}finally{main.post{busy=false;if(!isDestroyed)render()}}}}
 private fun refresh(){if(roomId.isEmpty()||busy)return;busy=true;worker.execute{try{val data=api("GET","/rooms/$roomId/snapshot");val changed=data.toString()!=snapshot?.toString()||!online; snapshot=data;online=true;lastError="";lastSync=System.currentTimeMillis();prefs.edit().putString("snapshot",data.toString()).apply();main.post{busy=false;if(changed&&!isDestroyed)render()}}catch(e:Exception){main.post{busy=false;val changed=online||lastError.isEmpty();online=false;lastError="저장소에 연결할 수 없습니다. 서버 실행을 확인해 주세요.";if(changed&&!isDestroyed)render()}}}}
 private fun room()=snapshot?.optJSONObject("room")?:JSONObject()
 private fun members()=snapshot?.optJSONArray("members")?:JSONArray()
 private fun phase()=room().optString("status","planning")
 private fun writable()=online&&phase()!="driving"&&phase()!="closed"
 private fun status(s:String)=mapOf("planning" to "여행 준비 중","pickup" to "크루 픽업 중","driving" to "함께 이동 중","arrived" to "도착 완료","closed" to "여행 완료","preparing" to "준비 중","outside" to "나왔어요","boarded" to "탑승 완료")[s]?:getString(R.string.status_unknown)
 private fun render(){
 val root=LinearLayout(this);root.orientation=LinearLayout.VERTICAL;root.setPadding(dp(24),dp(16),dp(24),0);root.setBackgroundColor(ink);setContentView(root)
 val head=LinearLayout(this);head.gravity=Gravity.CENTER_VERTICAL;root.addView(head)
 val branding=TextView(this);branding.setText(R.string.brand);branding.textSize=32f;branding.setTextColor(green);branding.setTypeface(null,Typeface.BOLD);head.addView(branding,LinearLayout.LayoutParams(0,-2,1f))
 val badge=TextView(this);badge.text=if(busy)"동기화 중…" else if(online)"● 저장소 연결됨" else "○ 오프라인";badge.textSize=15f;badge.setTextColor(if(online)green else muted);head.addView(badge)
 label(root,"함께 타고, 함께 도착하는 여행",15,muted)
 val scroll=ScrollView(this);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));body=LinearLayout(this);body.orientation=LinearLayout.VERTICAL;scroll.addView(body)
 if(lastError.isNotEmpty()){val warn=column(body,Color.rgb(70,48,37));label(warn,"연결 확인이 필요해요",18,Color.rgb(255,209,160),true);label(warn,UserMessages.error(this,lastError),15);label(warn,"화면의 기록은 마지막 저장 상태입니다. 연결이 복구되면 자동 갱신됩니다.",14,muted);button(warn,"다시 연결"){refresh()}}
 if(roomId.isEmpty()){welcome();return}
 if(snapshot==null){label(body,"여행을 불러오는 중이에요.",24);return}
 val nav=LinearLayout(this);nav.gravity=Gravity.CENTER;root.addView(nav,LinearLayout.LayoutParams(-1,dp(64)))
 listOf("여행","크루","주차·기록","시연 도구").forEachIndexed{i,t->val v=TextView(this);v.text=t;v.gravity=Gravity.CENTER;v.textSize=17f;v.setTextColor(if(tab==i)green else muted);v.setTypeface(null,if(tab==i)Typeface.BOLD else Typeface.NORMAL);v.setOnClickListener{tab=i;render()};nav.addView(v,LinearLayout.LayoutParams(0,-1,1f))}
 when(tab){0->overview();1->passengers();2->parking();else->demo()}
 }
 private fun welcome(){val hero=column(body,panel);label(hero,"오늘은 누구와\n함께 갈까요?",34,Color.WHITE,true);label(hero,"초대 코드로 크루원과 모여\n픽업부터 도착까지 함께해요.",20,muted);button(hero,"새 여행 만들기",true){createDialog()};label(hero,"정차 중 개발용 데모 · 실제 차량 제어 미연결",14,muted);val info=column(body);label(info,"01 여행 만들기  02 초대 코드 공유  03 함께 출발",16,green);label(info,"Mac에서 실행한 로컬 저장소에 연결합니다.\n이 버전에서는 실명이나 실제 위치 대신 샘플을 사용하세요.",16,muted)}
 private fun createDialog(){val title=EditText(this);title.setSingleLine();title.setText("친구들과 서울숲 나들이");AlertDialog.Builder(this).setTitle("여행 이름").setView(title).setNegativeButton("취소",null).setPositiveButton("만들기"){_,_->action{val r=api("POST","/rooms",JSONObject().put("title",title.text.toString()),"");roomId=r.getString("roomId");token=r.getString("hostToken");prefs.edit().putString("room",roomId).putString("token",token).apply()}}.show()}
 private fun overview(){
 val ms=members()
 val row=LinearLayout(this)
 val narrow=resources.configuration.screenWidthDp<760
 row.orientation=if(narrow)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
 body.addView(row,LinearLayout.LayoutParams(-1,if(narrow)-2 else dp(820)))
 val map=FrameLayout(this)
 row.addView(map,if(narrow)LinearLayout.LayoutParams(-1,dp(500)) else LinearLayout.LayoutParams(0,-1,1.9f).apply{marginEnd=dp(18)})
 map.addView(LegacyJourneyMapView(this,ms),FrameLayout.LayoutParams(-1,-1))
 val mapHeader=LinearLayout(this);mapHeader.orientation=LinearLayout.VERTICAL;mapHeader.setPadding(dp(20),dp(16),dp(20),dp(16));mapHeader.background=bg(panel)
 label(mapHeader,"서울숲으로 함께 가는 길",25,Color.WHITE,true)
 label(mapHeader,"샘플 지도 · 실제 위치 및 길 안내 아님",15,green)
 map.addView(mapHeader,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply{setMargins(dp(18),dp(18),dp(18),0)})
 val notice=LinearLayout(this);notice.orientation=LinearLayout.VERTICAL;notice.setPadding(dp(22),dp(16),dp(22),dp(16));notice.background=bg(panel)
 val saved=prefs.getBoolean("recommendation-$roomId",false)
 val dismissed=prefs.getBoolean("dismissed-$roomId",false)
 if(saved){label(notice,"들를 곳 후보에 저장했어요",23,green,true);label(notice,"서울숲 카페 거리 · 샘플 추천\n내비 경로에는 아직 반영되지 않았어요.",17,muted)}
 else if(!dismissed){label(notice,"함께 잠깐 들러볼까요?",25,Color.WHITE,true);label(notice,"서울숲 카페 거리",21,green,true);label(notice,"도착 전 쉬어갈 곳을 골라보세요.\n샘플 추천이며 실제 경로와는 무관합니다.",16,muted)
 button(notice,"들를 곳 후보로 저장",true,writable()){prefs.edit().putBoolean("recommendation-$roomId",true).apply();render()}
 button(notice,"나중에",false,writable()){prefs.edit().putBoolean("dismissed-$roomId",true).apply();render()}}
 else {label(notice,"지금은 픽업에 집중해요",22,Color.WHITE,true);label(notice,"크루원의 준비 상태를 좌석에서 확인해 주세요.",17,muted)}
 map.addView(notice,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(dp(18),0,dp(18),dp(18))})
 val sideScroll=ScrollView(this)
 row.addView(sideScroll,if(narrow)LinearLayout.LayoutParams(-1,dp(720)).apply{topMargin=dp(16)} else LinearLayout.LayoutParams(0,-1,1f))
 val side=LinearLayout(this);side.orientation=LinearLayout.VERTICAL;side.setPadding(dp(16),dp(14),dp(16),dp(16));side.background=bg(panel);sideScroll.addView(side)
 label(side,room().optString("title"),24,Color.WHITE,true);label(side,status(phase()),16,green,true)
 label(side,"앞좌석  ·  진행 방향 ↑",15,muted)
 val front=LinearLayout(this);side.addView(front)
 seat(front,"운전석",null,true)
 seat(front,"조수석",ms.optJSONObject(0))
 label(side,"뒷좌석",15,muted)
 val rear=LinearLayout(this);side.addView(rear)
 seat(rear,"왼쪽",ms.optJSONObject(1));seat(rear,"가운데",ms.optJSONObject(2));seat(rear,"오른쪽",ms.optJSONObject(3))
 label(side,"좌석을 누르면 탑승 상태를 확인할 수 있어요.",14,muted)
 var boarded=0;for(i in 0 until ms.length())if(ms.getJSONObject(i).optString("status")=="boarded")boarded++
 label(side,"크루원 ${ms.length()}명 · ${boarded}명 탑승 완료",18,Color.WHITE,true)
 label(side,"초대 코드(이전 시연)  "+room().optString("inviteCode"),23,green,true)
 val nextPhase=mapOf("planning" to "pickup","pickup" to "driving","driving" to "arrived","arrived" to "closed")[phase()]
 if(nextPhase!=null)button(side,mapOf("pickup" to "픽업 시작","driving" to "모두 함께 출발","arrived" to "도착했어요","closed" to "여행 마치기")[nextPhase]!!,true,online){
 if(nextPhase=="driving" && (ms.length()==0||boarded!=ms.length())){AlertDialog.Builder(this).setMessage("함께 가는 크루원의 탑승을 먼저 확인해 주세요.").setPositiveButton("확인",null).show()}else action{api("PATCH","/rooms/$roomId",JSONObject().put("status",nextPhase));if(nextPhase=="closed")api("POST","/rooms/$roomId/trip",JSONObject().put("title",room().optString("title")).put("memberCount",ms.length()).put("source","demo"))}}
 label(body,"시연용 지도와 좌석이에요. 실제 차량의 위치·좌석 센서는 연결되지 않았어요.",14,muted)
 }
 private fun seat(parent:LinearLayout,seatName:String,member:JSONObject?,driver:Boolean=false){
 val tile=LinearLayout(this);tile.orientation=LinearLayout.VERTICAL;tile.gravity=Gravity.CENTER;tile.setPadding(dp(5),dp(12),dp(5),dp(12))
 val boarded=driver||member?.optString("status")=="boarded"
 val occupied=driver||member!=null
 tile.background=bg(if(boarded)Color.rgb(31,86,78) else if(occupied)Color.rgb(27,75,96) else Color.rgb(36,52,62),24)
 parent.addView(tile,LinearLayout.LayoutParams(0,dp(174),1f).apply{setMargins(dp(4),dp(6),dp(4),dp(6))})
 val avatar=TextView(this);avatar.text=if(driver)"나" else member?.optString("displayName")?.take(1)?:"+";avatar.textSize=26f;avatar.gravity=Gravity.CENTER;avatar.setTextColor(if(occupied)ink else muted);avatar.background=bg(if(occupied)green else Color.rgb(51,68,78),40)
 tile.addView(avatar,LinearLayout.LayoutParams(dp(55),dp(55)))
 label(tile,if(driver)"운전자" else member?.optString("displayName")?:"빈 좌석",18,Color.WHITE,true).gravity=Gravity.CENTER
 label(tile,if(driver)"왼쪽 앞 · 고정" else if(member==null)seatName else status(member.optString("status")),13,if(occupied)green else muted).gravity=Gravity.CENTER
 tile.contentDescription=if(driver)"앞좌석 왼쪽 운전자 고정" else "$seatName ${member?.optString("displayName")?:"빈 좌석"}"
 tile.isFocusable=true;tile.setOnClickListener{
 if(driver){AlertDialog.Builder(this).setTitle("앞좌석 왼쪽 · 운전자").setMessage("운전석은 이 위치에 고정되어 있어요.").setPositiveButton("확인",null).show()}
 else if(member==null){AlertDialog.Builder(this).setTitle("$seatName · 빈 좌석").setMessage("초대 코드 ${room().optString("inviteCode")}를 크루원에게 공유해 주세요.\n시연 도구에서 샘플 크루원을 추가할 수도 있어요.").setPositiveButton("확인",null).show()}
 else {val dialog=AlertDialog.Builder(this).setTitle("$seatName · ${member.optString("displayName")}").setMessage("${status(member.optString("status"))}\n\n픽업 위치: ${member.optJSONObject("pickup")?.optString("memo")?:"입력 대기"}\n\n좌석은 합류 순서에 따라 배치한 데모입니다.").setNegativeButton("닫기",null)
 if(writable()&&phase()!="arrived"&&member.optString("status")!="boarded")dialog.setPositiveButton("탑승 확인"){_,_->action{api("PATCH","/rooms/$roomId/members/${member.getString("memberId")}",JSONObject().put("status","boarded"))}}
 dialog.show()}
 }
 }
 private fun passengers(){label(body,"함께 가는 크루",28,Color.WHITE,true);label(body,"참여 순서대로 픽업해요. 경로 최적화는 적용되지 않았어요.",15,muted)
 val ms=members();if(ms.length()==0){val c=column(body,panel);label(c,"아직 함께 가는 크루원이 없어요",23);label(c,"초대 코드를 공유하거나 시연 도구에서 샘플 크루원을 추가해 보세요.",17,muted)}
 for(i in 0 until ms.length()){val m=ms.getJSONObject(i);val c=column(body,panel);label(c,"${i+1}.  ${m.optString("displayName")}",25,Color.WHITE,true);label(c,status(m.optString("status")),20,green);val pickup=m.optJSONObject("pickup");label(c,pickup?.optString("memo")?.takeIf{it.isNotBlank()}?:"픽업 위치 입력 대기",16,muted)
 if(m.optString("status")!="boarded")button(c,"탑승 확인",true,writable()){action{api("PATCH","/rooms/$roomId/members/${m.getString("memberId")}",JSONObject().put("status","boarded"))}}}
 }
 private fun parking(){label(body,"도착 후에도 함께",28,Color.WHITE,true);val records=snapshot?.optJSONObject("records");val park=records?.optJSONArray("parking")?.optJSONObject(0)
 val c=column(body,panel)
 if(park==null){label(c,"주차 기록이 없어요",24);label(c,"도착 후 위치와 구역 메모를 저장하세요. 현재 위치는 샘플 좌표를 사용합니다.",17,muted)}else{label(c,"서울숲 · 샘플 주차 위치",23,green,true);label(c,park.optString("memo"),25);label(c,"${park.optJSONObject("position")?.optDouble("lat")}, ${park.optJSONObject("position")?.optDouble("lng")}",17,muted);label(c,"저장 시각  "+park.optString("updatedAt"),14,muted)}
 button(c,"샘플 주차 위치 + 메모 저장",true,online&&phase()=="arrived"){val input=EditText(this);input.setSingleLine();input.setText(park?.optString("memo")?:"지하 2층 C-14");AlertDialog.Builder(this).setTitle("주차 구역 메모").setView(input).setNegativeButton("취소",null).setPositiveButton("저장"){_,_->action{api("PUT","/rooms/$roomId/parking",JSONObject().put("position",JSONObject().put("lat",37.5445).put("lng",127.0374)).put("memo",input.text.toString()).put("source","demo"))}}.show()}
 label(body,"주차 기록은 ‘도착했어요’를 누른 뒤 저장할 수 있습니다.",15,muted)
 if(records?.optJSONArray("trip")?.length()?:0>0){val history=column(body,panel);label(history,"여행 기록 저장 완료",23,green);label(history,room().optString("title"),20);label(history,"함께한 크루원 ${members().length()}명",17,muted)}
 }
 private fun demo(){label(body,"크루원 시뮬레이터",28,Color.WHITE,true);label(body,"실제 폰 없이 상태 동기화를 시험하는 개발 도구입니다.",16,muted)
 button(body,"샘플 크루원 추가",true,writable()&&members().length()<4){action{val names=listOf("민지","준호","수빈","지우");api("POST","/join",JSONObject().put("inviteCode",room().getString("inviteCode")).put("displayName",names[members().length().coerceAtMost(3)]),"")}}
 val ms=members();for(i in 0 until ms.length()){val m=ms.getJSONObject(i);button(body,"${m.optString("displayName")} → 나왔어요",false,writable()&&m.optString("status")!="boarded"){action{api("PATCH","/rooms/$roomId/members/${m.getString("memberId")}",JSONObject().put("status","outside").put("pickup",JSONObject().put("lat",37.5572+i*.001).put("lng",126.9245+i*.001).put("memo","샘플 픽업 지점 ${i+1}")))}}}
 val c=column(body,panel);label(c,"연결 정보",20,green,true);label(c,"차량 앱 → 10.0.2.2:8787\nMac API → 127.0.0.1:8787\n상태 확인: 3초 간격",16,muted);label(c,"데이터는 Mac의 SQLite 파일에 보관됩니다. 클라우드 공개 및 실제 위치 수집은 하지 않습니다.",15,muted)
 if(phase()=="closed")button(body,"다음 여행 만들기",true,online){roomId="";token="";snapshot=null;prefs.edit().clear().apply();tab=0;render()}
 }
}
