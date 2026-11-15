package com.hopon.car

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import com.hopon.car.data.JourneyRepository
import com.hopon.car.map.MapTileStore
import com.hopon.car.ui.*
import com.hopon.car.voice.VoiceCommands
import com.hopon.car.voice.VoiceDialog
import com.hopon.car.voice.VoiceStopDialog
import com.hopon.car.voice.PassengerAnnouncements
import com.hopon.car.voice.vehicleVoiceOutput
import com.hopon.car.voice.WakeListeningService
import com.hopon.car.voice.VoiceTimer
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil

/** v0.3 presentation only. REST, SSE, persistence and vehicle application remain in v0.2 repository. */
class MainActivity:Activity(){
    private lateinit var repository:JourneyRepository
    private lateinit var tiles:MapTileStore
    private val d by lazy{Design(this)}
    private var screen="home"
    private var selectedMember=""
    private var openPreparationWhenReady=false
    private var lastPhase=""
    private var renderedKey=""
    private var mapView:JourneyMapView?=null
    private var etaView:TextView?=null
    private var arrivalView:TextView?=null
    private var supportTab=""
    private var selectedPickup=""
    private var mapStatus:TextView?=null
    private var modal:AlertDialog?=null
    private var voiceDialog:VoiceDialog?=null
    private var voiceContextKey=""
    private var voiceStopDialog:VoiceStopDialog?=null
    private var voiceStopContextKey=""
    private var foreground=false
    private var voiceOpening=false
    private var announcementPlaying=false
    private var wakeStatus:TextView?=null
    private val wakeTick=object:Runnable {override fun run(){if(foreground){updateWake();announcementHandler.postDelayed(this,500)}}}
    private fun updateWakeLabel(){
        wakeStatus?.text=when(WakeListeningService.current?.state ?: com.hopon.car.voice.WakeState.OFF){
            com.hopon.car.voice.WakeState.LISTENING->"● Hey Movo 대기"
            com.hopon.car.voice.WakeState.STARTING->"호출어 준비 중"
            com.hopon.car.voice.WakeState.ERROR->"호출어 오류 · 설정 확인"
            com.hopon.car.voice.WakeState.OFF->""
            else->"호출어 잠시 멈춤"
        }
    }
    private fun updateWake(){
        val enabled=preferences.getBoolean("hey-movo",false)
        if(enabled&&foreground)WakeListeningService.startFromVisibleActivity(this)
        val service=WakeListeningService.current
        if(!enabled)service?.shutdown()
        else {
            val eligible=repository.snapshot!=null&&!repository.authRequired
            service?.update(eligible,repository.busy||(foreground&&(voiceOpening||voiceDialog!=null||voiceStopDialog!=null||modal?.isShowing==true||announcementPlaying)))
            if(foreground&&eligible&&!repository.busy&&!voiceOpening&&voiceDialog==null&&voiceStopDialog==null&&modal?.isShowing!=true&&service?.consumeWake()==true)openVoice()
        }
        updateWakeLabel()
        if(enabled&&!WakeListeningService.permissionsReady(this))wakeStatus?.text="호출어 권한 설정 필요"
    }
    private fun enableWake(){
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO),710);return
        }
        if(!android.provider.Settings.canDrawOverlays(this)){
            AlertDialog.Builder(this).setTitle("기본 내비에서 MOVO 호출")
                .setMessage("‘헤이 모보’로 MOVO 음성창을 열려면 ‘다른 앱 위에 표시’ 권한이 필요해요. 설정에서 MOVO를 허용한 뒤 돌아와 주세요.")
                .setNegativeButton("취소"){_,_->preferences.edit().putBoolean("hey-movo",false).apply();render()}
                .setPositiveButton("설정 열기"){_,_->
                    runCatching{startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")),712)}
                        .onFailure{
                            preferences.edit().putBoolean("hey-movo",false).apply();render()
                            AlertDialog.Builder(this).setTitle("차량 권한 설정 필요")
                                .setMessage("이 차량은 ‘다른 앱 위에 표시’ 설정 화면을 제공하지 않아요. 차량 관리자에게 MOVO 호출 권한을 요청해 주세요. 설정 전에는 MOVO의 음성 버튼을 사용할 수 있어요.")
                                .setPositiveButton("확인",null).show()
                        }
                }.setOnCancelListener{preferences.edit().putBoolean("hey-movo",false).apply();render()}.show();return
        }
        if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),711)
        updateWake()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==710){
            if(grantResults.firstOrNull()==android.content.pm.PackageManager.PERMISSION_GRANTED)enableWake()
            else {preferences.edit().putBoolean("hey-movo",false).apply();render()}
        }
        updateWake()
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==712&&preferences.getBoolean("hey-movo",false)){
            if(android.provider.Settings.canDrawOverlays(this))enableWake()
            else {preferences.edit().putBoolean("hey-movo",false).apply();render();updateWake()}
        }
    }
    override fun onNewIntent(intent:Intent?){super.onNewIntent(intent);updateWake()}
    private val announcementHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val announcementPrefs by lazy{getSharedPreferences("movo-announcements",MODE_PRIVATE)}
    private val announcements by lazy{PassengerAnnouncements(
        {vehicleVoiceOutput(this)},VoiceTimer{delay,action->
            val task=Runnable{action()};announcementHandler.postDelayed(task,delay)
            val cancel:()->Unit={announcementHandler.removeCallbacks(task)};cancel
        },{key->announcementPrefs.getStringSet(key,null)?.toSet()},
        {key,ids->announcementPrefs.edit().putStringSet(key,ids).apply()},
        unavailable={Toast.makeText(this,"동승자 메시지를 읽지 못했어요. 화면에서 확인해 주세요.",Toast.LENGTH_LONG).show()},playbackChanged={playing->announcementPlaying=playing;updateWake()})}
    private fun updateAnnouncements(){
        announcements.update("${repository.base}|${repository.tripId}",repository.snapshot,
            foreground&&repository.online&&!repository.authRequired&&repository.phase() in listOf("planning","pickup")&&preferences.getBoolean("passenger-voice",true))
    }
    private fun voiceKey()="${repository.base}|${repository.tripId}|${repository.phase()}"
    private var scroll:ScrollView?=null
    private val scrollY=mutableMapOf<String,Int>()
    private val preferences by lazy{getSharedPreferences("hopon-ui-v3",MODE_PRIVATE)}
    private fun s(id:Int,vararg args:Any)=BrandText.display(getString(id,*args))
    private fun dp(n:Int)=d.dp(n)
    private fun snapshot()=repository.snapshot?:JSONObject()
    private fun room()=snapshot().optJSONObject("room")?:JSONObject()
    private fun members()=snapshot().optJSONArray("members")?:JSONArray()
    private fun people():List<JSONObject>{val list=members();return (0 until list.length()).mapNotNull{list.optJSONObject(it)}}
    private fun requests()=snapshot().optJSONArray("requests")?:JSONArray()
    private fun requestList():List<JSONObject>{val list=requests();return (0 until list.length()).mapNotNull{list.optJSONObject(it)}}
    private fun driving()=repository.phase()=="driving"
    private fun writable()=repository.online&&!repository.authRequired&&!repository.busy&&repository.phase()!="closed"
    private fun status(value:String)=s(when(value){"planning"->R.string.planning;"pickup"->R.string.pickup;"driving"->R.string.driving;"arrived"->R.string.arrived;"closed"->R.string.closed;"outside"->R.string.outside;"boarded"->R.string.boarded;"pending"->R.string.pending;"approved"->R.string.approved;"applied"->R.string.applied;"rejected"->R.string.rejected;"failed"->R.string.failed;else->R.string.preparing})
    private fun key():String{
        val data=JSONObject(snapshot().toString())
        listOf("telemetry","route","serverTime","updatedAt","eventId","freshness","seatUpdatedAt","proximity").forEach{data.remove(it)}
        return "$data|${snapshot().optJSONObject("route")?.optString("nextMemberId")}|${repository.online}|${repository.busy}|${repository.error}|${repository.authRequired}|${repository.sse}|${repository.parkingPending}|${repository.voiceStopPending}"
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);screen=savedInstanceState?.getString("screen")?:"home"
        tiles=MapTileStore(this);tiles.enabled=preferences.getBoolean("online-map",true)
        repository=JourneyRepository(this){onDataChanged()}
        lastPhase=repository.phase()
        if(savedInstanceState==null && repository.phase() in listOf("planning","closed"))screen="rooms"
        render()
    }
    override fun onStart(){super.onStart();foreground=true;updateAnnouncements();repository.start();announcementHandler.removeCallbacks(wakeTick);announcementHandler.post(wakeTick)}
    override fun onStop(){foreground=false;voiceOpening=false;announcementHandler.removeCallbacks(wakeTick);updateWake();announcements.stop();voiceDialog?.dismiss();voiceDialog=null;voiceStopDialog?.dismiss();voiceStopDialog=null;repository.stop();updateWake();super.onStop()}
    override fun onDestroy(){modal?.dismiss();repository.destroy();tiles.close();super.onDestroy()}
    override fun onSaveInstanceState(out:Bundle){out.putString("screen",screen);super.onSaveInstanceState(out)}
    private fun onDataChanged(){
        if(isDestroyed)return
        updateAnnouncements()
        if(voiceDialog!=null && (voiceContextKey!=voiceKey() || repository.authRequired)){voiceDialog?.dismiss();voiceDialog=null}
        if(voiceStopDialog!=null && (voiceStopContextKey!=voiceKey() || repository.authRequired)){voiceStopDialog?.dismiss();voiceStopDialog=null}
        if(openPreparationWhenReady && !repository.busy){
            openPreparationWhenReady=false
            if(repository.error.isBlank() && repository.online)screen="prepare"
        }
        val nextScreen=ScreenFlow.afterUpdate(screen,lastPhase,repository.phase())
        if(lastPhase!=repository.phase()){supportTab="";selectedPickup=""}
        lastPhase=repository.phase()
        if(nextScreen!=screen){screen=nextScreen;modal?.dismiss()}
        if(key()!=renderedKey)render() else {mapView?.update(snapshot());if(repository.phase() !in listOf("arrived","closed"))mapStatus?.text=routeLabel();etaView?.text=if(repository.phase() in listOf("arrived","closed"))status(repository.phase())else eta();arrivalView?.text=drivingArrival()}
    }
    private fun routeLabel():String {
        val route=snapshot().optJSONObject("route")
        return if(route?.optString("source")=="demo-distance")"도로 경로 연결 설정 필요" else route?.optString("label")?:"도로 경로 확인 중"
    }
    private fun scrollKey()="$screen|${repository.phase()}|$supportTab"
    private fun show(value:String){scroll?.let{scrollY[it.tag as? String ?:scrollKey()]=it.scrollY};scroll=null;screen=value;render()}
    private fun label(parent:LinearLayout,text:String,size:Int=22,color:Int=Palette.forest,bold:Boolean=false)=d.text(text,size,color,bold).also{parent.addView(it)}
    private fun button(parent:LinearLayout,text:String,primary:Boolean=false,enabled:Boolean=true,click:()->Unit)=d.button(text,primary,enabled&&!repository.busy,click).also{parent.addView(it,LinearLayout.LayoutParams(-1,dp(64)).apply{topMargin=dp(10)})}
    private fun card(parent:LinearLayout,color:Int=Color.WHITE)=d.column(color,22).also{parent.addView(it,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(16)})}
    private fun body(parent:FrameLayout,title:String,backTarget:String="home"):LinearLayout{
        val outer=d.column(Palette.background,12);parent.addView(outer,FrameLayout.LayoutParams(-1,-1))
        val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};outer.addView(head)
        head.addView(d.text(title,23,Palette.forest,true),LinearLayout.LayoutParams(0,-2,1f))
        head.addView(d.button(s(if(backTarget=="rooms")R.string.garage_title else if(backTarget=="history")R.string.v3_history else R.string.v3_back)){show(backTarget)},LinearLayout.LayoutParams(dp(120),dp(64)))
        return panelScroll(outer)
    }
    private fun panelScroll(parent:LinearLayout):LinearLayout {
        val view=ScrollView(this).apply{isFillViewport=true};scroll=view
        parent.addView(view,LinearLayout.LayoutParams(-1,0,1f))
        val content=d.column();view.addView(content)
        val key=scrollKey();view.tag=key;view.post{view.scrollTo(0,scrollY[key]?:0)}
        return content
    }
    private fun render(){
        arrivalView=null
        renderedKey=key();mapView=null;etaView=null;mapStatus=null
        scroll?.let{scrollY[it.tag as? String ?:scrollKey()]=it.scrollY};scroll=null
        val root=d.column(Palette.background,16);setContentView(root)
        val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};root.addView(head,LinearLayout.LayoutParams(-1,dp(70)))
        head.addView(d.text(s(R.string.brand),32,Palette.forest,true),LinearLayout.LayoutParams(dp(150),-2))
        head.addView(d.single(if(repository.tripId.isBlank())s(R.string.tagline) else room().optString("title"),22,Palette.secondary),LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(16)})
        val connection=if(repository.phase()=="closed")s(R.string.closed) else if(repository.online)s(R.string.v3_connected) else s(R.string.v3_offline)
        head.addView(d.text("●  $connection",18,if(repository.online)Palette.secondary else Palette.warning),LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(20)})
        wakeStatus=d.text("",15,Palette.secondary).apply {
            minHeight=dp(48);gravity=Gravity.CENTER_VERTICAL
            contentDescription="Hey Movo 대기 끄기"
            setOnClickListener { preferences.edit().putBoolean("hey-movo",false).apply();updateWake() }
        }
        head.addView(wakeStatus,LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(10)})
        updateWakeLabel()
        head.addView(d.button("음성",enabled=repository.snapshot!=null&&!repository.authRequired&&!repository.busy){openVoice()},LinearLayout.LayoutParams(dp(100),dp(60)).apply{marginEnd=dp(10)})
        head.addView(d.button(s(R.string.garage_title),enabled=repository.canSwitchParty){show("rooms")},LinearLayout.LayoutParams(dp(140),dp(60)).apply{marginEnd=dp(10)})
        head.addView(d.button(s(R.string.v3_settings),enabled=!driving()){show("settings")},LinearLayout.LayoutParams(dp(146),dp(60)))
        if(repository.error.isNotBlank()||(!repository.online&&repository.snapshot!=null&&repository.phase()!="closed")){
            val message=when{repository.closeUncertain->s(R.string.close_uncertain);repository.authRequired->s(R.string.auth_expired);repository.error.isNotBlank()->UserMessages.error(this,repository.error);else->s(R.string.offline_note)}
            label(root,message,18,Palette.warning)
        }
        if(screen=="rooms" || repository.tripId.isBlank()){
            val garage=FrameLayout(this);root.addView(garage,LinearLayout.LayoutParams(-1,0,1f));garage(garage);return
        }
        if(repository.snapshot==null){label(root,s(R.string.loading),30);button(root,s(R.string.pair)){pairing()};return}
        val row=LinearLayout(this);root.addView(row,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(10)})
        val journey=FrameLayout(this).apply{background=d.background(Color.WHITE,26);clipToOutline=true;contentDescription=s(R.string.v3_journey_region)}
        row.addView(journey,LinearLayout.LayoutParams(0,-1,.65f))
        val party=d.column(Color.WHITE,16).apply{contentDescription=s(R.string.v3_party_region)}
        row.addView(party,LinearLayout.LayoutParams(0,-1,.35f).apply{marginStart=dp(12)})
        home(journey)
        party(party)
    }
    private fun garage(parent:FrameLayout){
        val layout=d.column(Palette.background,12);parent.addView(layout,FrameLayout.LayoutParams(-1,-1))
        label(layout,s(R.string.garage_heading),36,Palette.forest,true)
        label(layout,s(R.string.garage_hint),20,Palette.secondary)
        val columns=LinearLayout(this)
        layout.addView(columns,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(20)})
        for(mode in listOf("solo","together")){
            val panel=d.column(Color.WHITE,22)
            columns.addView(panel,LinearLayout.LayoutParams(0,-1,1f).apply{if(mode=="solo")marginEnd=dp(16)})
            label(panel,s(if(mode=="solo")R.string.garage_solo else R.string.garage_together),28,Palette.forest,true)
            label(panel,s(if(mode=="solo")R.string.garage_solo_hint else R.string.garage_together_hint),18,Palette.warning)
            val list=ScrollView(this);panel.addView(list,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(14)})
            val content=d.column();list.addView(content)
            val entries=repository.parties().filter{it.optString("mode")==mode}
            if(entries.isEmpty()){
                label(content,s(R.string.garage_empty),25,Palette.forest,true)
                label(content,s(R.string.garage_add_hint),20,Palette.secondary)
            }
            for(entry in entries){
                val saved=entry.getJSONObject("snapshot");val r=saved.getJSONObject("room")
                val completed=r.optString("status")=="closed"
                val c=card(content,Palette.background)
                label(c,r.optString("title"),26,Palette.forest,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
                label(c,r.optJSONObject("destination")?.optString("name")?:"—",22,Palette.secondary)
                label(c,s(R.string.garage_card_detail,saved.optJSONArray("members")?.length()?:0,saved.optJSONArray("course")?.length()?:0),18,Palette.secondary)
                label(c,status(r.optString("status"))+if(entry.optBoolean("current"))" · "+s(R.string.garage_current) else "",18,Palette.warning)
                if(completed){
                    if(entry.optBoolean("current"))button(c,s(R.string.v3_view_summary)){show("history")}
                    else label(c,s(R.string.garage_completed),18,Palette.secondary)
                }else button(c,s(R.string.garage_select),true,repository.canSwitchParty){
                    openPreparationWhenReady=true;repository.selectParty(entry.getString("key"))
                }
            }
            button(panel,s(R.string.garage_add),true,repository.canSwitchParty){pairing(mode)}
        }
        label(layout,s(R.string.garage_saved_note),16,Palette.secondary)
    }
    private fun preparation(parent:LinearLayout){
        val content=panelScroll(parent)
        label(content,s(if(people().isEmpty())R.string.preparation_solo else R.string.preparation_hint),20,Palette.secondary)
        val stops=snapshot().optJSONArray("course")?:JSONArray()
        for(i in 0 until stops.length())label(content,"${i+1}  ·  ${stops.getJSONObject(i).optString("name")}",22)
        label(content,s(R.string.home_destination,room().optJSONObject("destination")?.optString("name")?:"—"),24,Palette.forest,true)
        label(content,s(R.string.preparation_people,people().size),20,Palette.secondary)
    }
    private fun eta():String{
        val route=snapshot().optJSONObject("route")?:JSONObject()
        val next=route.optString("nextMemberId")
        val seconds=if(repository.phase()=="pickup"&&next.isNotBlank())route.optJSONObject("perMemberETA")?.optJSONObject(next)?.opt("seconds") else route.opt("destinationSeconds")
        return if(!repository.online||seconds !is Number)s(R.string.eta_unknown) else s(R.string.eta,ceil(seconds.toDouble()/60).toInt())
    }
    private fun overlay(parent:FrameLayout,child:View,width:Int=-1,gravity:Int=Gravity.TOP or Gravity.START,top:Int=0,bottom:Int=0){
        parent.addView(child,FrameLayout.LayoutParams(width,-2,gravity).apply{setMargins(dp(22),dp(top),dp(22),dp(bottom))})
    }
    /** The map and cabin are siblings of the support panel, never its replacement. */
    private fun home(parent:FrameLayout){
        val phase=repository.phase()
        val layout=d.column(Color.WHITE,16)
        parent.addView(layout,FrameLayout.LayoutParams(-1,-1))
        val stages=LinearLayout(this)
        layout.addView(stages,LinearLayout.LayoutParams(-1,dp(36)))
        val active=when(phase){"planning"->0;"pickup"->1;"driving"->2;else->3}
        listOf(R.string.home_prepare,R.string.home_pickup,R.string.home_driving,R.string.home_arrival).forEachIndexed{index,id->
            stages.addView(d.single(s(id),18,if(index==active)Color.WHITE else Palette.secondary,true).apply{
                gravity=Gravity.CENTER;background=d.background(if(index==active)Palette.forest else Palette.background,12);isSelected=index==active
            },LinearLayout.LayoutParams(0,-1,1f).apply{if(index<3)marginEnd=dp(8)})
        }
        val nextId=snapshot().optJSONObject("route")?.optString("nextMemberId")
        val next=people().firstOrNull{it.optString("memberId")==nextId}
        val destination=room().optJSONObject("destination")?.optString("name")?.takeIf{it.isNotBlank()}?:"—"
        val heading=when(phase){
            "planning"->s(R.string.home_prepare)
            "pickup"->next?.let{s(R.string.v3_pickup_name,it.optString("displayName"))}?:s(if(people().isEmpty())R.string.preparation_ready else if(people().all{it.optString("status")=="boarded"})R.string.all_aboard else R.string.pickup_wait_location)
            "driving"->s(R.string.v3_toward,destination)
            "arrived"->s(R.string.layout_arrived)
            else->s(R.string.layout_completed)
        }
        val titleRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        layout.addView(titleRow,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(8)})
        titleRow.addView(d.single(heading,30,Palette.forest,true),LinearLayout.LayoutParams(0,-2,1f))
        val timing=d.column().apply{gravity=Gravity.END}
        titleRow.addView(timing,LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(12)})
        if(driving()){label(timing,s(R.string.layout_arrival_time),16,Palette.secondary);arrivalView=label(timing,drivingArrival(),30,Palette.forest,true)}
        etaView=label(timing,if(phase in listOf("arrived","closed"))status(phase)else eta(),if(driving())18 else 28,Palette.forest,true)
        val mapFrame=FrameLayout(this).apply{background=d.background(Palette.empty,18);clipToOutline=true}
        layout.addView(mapFrame,LinearLayout.LayoutParams(-1,0,.62f))
        val map=JourneyMapView(this,snapshot(),tiles){loaded->mapStatus?.text=s(if(!loaded)R.string.v3_map_unavailable else if(phase in listOf("arrived","closed"))R.string.arrival_map_note else R.string.v3_map_simulated);if(loaded&&phase !in listOf("arrived","closed"))mapStatus?.text=routeLabel()}
        mapView=map;mapFrame.addView(map,FrameLayout.LayoutParams(-1,-1))
        val destinationChip=d.single(s(R.string.home_destination,destination),18,Palette.forest,true).apply{
            background=d.background(Color.WHITE,12);setPadding(dp(12),dp(6),dp(12),dp(6))
        }
        mapFrame.addView(destinationChip,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply{setMargins(dp(12),dp(12),dp(110),0)})
        val controls=d.column()
        button(controls,"+"){map.zoom(1)}.contentDescription=s(R.string.map_zoom_in)
        button(controls,"−"){map.zoom(-1)}.contentDescription=s(R.string.map_zoom_out)
        button(controls,s(if(phase in listOf("arrived","closed"))R.string.arrival_map_fit else R.string.v3_fit)){map.fit()}
        overlay(mapFrame,controls,dp(80),Gravity.TOP or Gravity.END,4)
        val attribution=d.text(s(R.string.v3_attribution),14,Palette.forest).apply{
            setPadding(dp(8),dp(4),dp(8),dp(4));background=d.background(Color.WHITE,8)
            setOnClickListener{if(!driving())startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright")))}
        }
        overlay(mapFrame,attribution,-2,Gravity.BOTTOM or Gravity.START,bottom=8)
        val support=FrameLayout(this).apply{contentDescription=s(R.string.layout_support_region)}
        layout.addView(support,LinearLayout.LayoutParams(-1,0,.38f).apply{topMargin=dp(10)})
        when(screen){
            "party"->partyDetails(support)
            "settings"->settings(support)
            "parking"->parkingScreen(support)
            "history"->history(support)
            "requests"->requestScreen(support)
            else->if(phase=="closed")history(support)else supportPanel(support)
        }
        mapStatus=label(layout,if(phase in listOf("arrived","closed"))s(R.string.arrival_map_note)else routeLabel(),16,Palette.secondary)
        if(screen in listOf("home","prepare")||(screen=="parking"&&phase=="arrived"))journeyAction(layout)
    }
    private fun supportPanel(parent:FrameLayout){
        val outer=d.column(Palette.background,10);parent.addView(outer,FrameLayout.LayoutParams(-1,-1))
        val phase=repository.phase()
        val count=requestList().count{it.optString("status")=="pending"}
        val selected=supportTab.ifBlank{if(count>0)"requests" else "overview"}
        val tabs=LinearLayout(this);outer.addView(tabs,LinearLayout.LayoutParams(-1,dp(64)))
        val title=when(phase){"pickup"->R.string.pickup_overview;"driving"->R.string.drive_confirmed;"arrived"->R.string.arrival_overview;else->R.string.preparation_route}
        listOf("overview" to s(title),"requests" to s(R.string.drive_requests,count)).forEach{(tab,text)->
            tabs.addView(d.button(text,selected==tab){
                scroll?.let{scrollY[it.tag as? String ?:scrollKey()]=it.scrollY};scroll=null;supportTab=tab;render()
            },LinearLayout.LayoutParams(0,-1,1f).apply{if(tab=="overview")marginEnd=dp(8)})
        }
        val content=d.column();outer.addView(content,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(8)})
        if(selected=="requests")requestCards(panelScroll(content)) else when(phase){
            "pickup"->pickupPanel(content)
            "driving"->drivingPanel(content)
            "arrived"->arrivalPanel(content)
            else->preparation(content)
        }
    }
    private fun journeyAction(parent:LinearLayout){
        val phase=repository.phase()
        if(phase=="closed")return
        val ready=people().all{it.optString("status")=="boarded"}
        val synced=snapshot().optJSONObject("parking")!=null&&!repository.parkingPending
        val action=when(phase){
            "planning"->if(repository.partyMode=="solo"&&people().isEmpty())R.string.preparation_solo_ready else R.string.start_pickup
            "pickup"->if(repository.partyMode=="solo"&&people().isEmpty())R.string.preparation_depart else R.string.start_driving
            "driving"->R.string.arrive
            else->if(synced)R.string.finish else R.string.arrival_save_share
        }
        val enabled=when(phase){"pickup"->writable()&&ready;"arrived"->if(synced)writable() else !repository.parkingPending&&!repository.authRequired;else->writable()}
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        parent.addView(row,LinearLayout.LayoutParams(-1,dp(72)).apply{topMargin=dp(6)})
        val note=when{
            repository.parkingPending->s(R.string.arrival_queued)
            phase=="pickup"&&!ready->s(R.string.board_first)
            phase=="arrived"->s(if(synced)R.string.arrival_saved else R.string.arrival_share_after_save)
            else->s(if(phase=="driving")R.string.drive_eta_note else R.string.layout_seat_status)
        }
        row.addView(d.text(note,18,Palette.secondary),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(d.button(s(action),true,enabled&&!repository.busy){
            if(phase=="arrived"&&!synced)parkingDialog() else {screen="home";repository.advance()}
        },LinearLayout.LayoutParams(dp(240),-1).apply{marginStart=dp(12)})
    }
    private fun drivingArrival():String {
        val data=snapshot()
        val instant=DrivingPresentation.arrival(data,repository.online)?:return s(R.string.drive_eta_unknown)
        val zone=java.time.ZoneId.systemDefault()
        val arrival=instant.atZone(zone)
        val date=runCatching{java.time.OffsetDateTime.parse(data.getString("serverTime")).atZoneSameInstant(zone).toLocalDate()}.getOrNull()
        val pattern=if(arrival.toLocalDate()==date)"a h:mm" else "M/d a h:mm"
        return arrival.format(java.time.format.DateTimeFormatter.ofPattern(pattern,java.util.Locale.KOREAN))
    }
    private fun drivingPanel(parent:LinearLayout){
        val content=panelScroll(parent)
        val course=snapshot().optJSONArray("course")?:JSONArray()
        if(course.length()==0)label(content,s(R.string.drive_direct),22,Palette.secondary)
        for(i in 0 until course.length())label(content,"${i+1}  ·  ${course.getJSONObject(i).optString("name")}",24,Palette.forest,true)
        label(content,s(R.string.home_destination,room().optJSONObject("destination")?.optString("name")?:"—"),24,Palette.forest,true)
        label(content,s(R.string.drive_apply_note),18,Palette.secondary)
        button(content,"기본 내비 보기",enabled=!repository.busy){repository.showNativeNavigation()}
    }
    private fun pickupPanel(parent:LinearLayout){
        val stops=PickupPresentation.stops(snapshot())
        val content=panelScroll(parent)
        if(stops.isEmpty()){label(content,s(R.string.preparation_solo),22);pickupCourse(content);return}
        val selected=stops.firstOrNull{it.id==selectedPickup}?:stops.firstOrNull{it.next}?:stops.firstOrNull{!it.boarded}?:stops.first()
        val order=LinearLayout(this);content.addView(order,LinearLayout.LayoutParams(-1,dp(64)))
        for(stop in stops){
            order.addView(d.button("${stop.number?:"—"} · ${stop.member.optString("displayName")}",stop.id==selected.id){selectedPickup=stop.id;render()}.apply{
                contentDescription=s(R.string.pickup_card_description,stop.number?.toString()?:"—",stop.member.optString("displayName"),"",status(stop.member.optString("status")))
            },LinearLayout.LayoutParams(0,-1,1f).apply{marginEnd=dp(6)})
        }
        val m=selected.member;val location=m.optJSONObject("pickup")
        val address=if(selected.number==null)s(R.string.pickup_missing) else listOfNotNull(location?.optString("name")?.takeIf{it.isNotBlank()},location?.optString("memo")?.takeIf{it.isNotBlank()}).distinct().joinToString(" · ").ifBlank{
            s(R.string.coordinates,location!!.optDouble("lat"),location.optDouble("lng"))
        }
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        content.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        val info=d.column();row.addView(info,LinearLayout.LayoutParams(0,-2,1f))
        label(info,address,22,Palette.forest,true)
        label(info,(if(selected.next)s(R.string.pickup_next_badge)+" · " else "")+status(m.optString("status"))+" · "+s(R.string.assigned_seat,m.optInt("seat")),18,Palette.secondary)
        if(!selected.boarded)row.addView(d.button(s(R.string.confirm_boarding),true,writable()){
            if(repository.phase()=="pickup"&&writable()){selectedPickup="";repository.board(selected.id)}
        }.apply{contentDescription=s(R.string.pickup_confirm_person,m.optString("displayName"))},LinearLayout.LayoutParams(dp(140),dp(64)).apply{marginStart=dp(8)})
        pickupCourse(content)
    }
    private fun pickupCourse(content:LinearLayout){
        val card=card(content,Palette.softMint)
        label(card,"확정 경유지",23,Palette.forest,true)
        val course=snapshot().optJSONArray("course")?:JSONArray()
        if(course.length()==0)label(card,"승인된 경유지가 아직 없어요.",18,Palette.secondary)
        for(i in 0 until course.length())label(card,"${i+1} · ${course.getJSONObject(i).optString("name")}",22,Palette.forest,true)
        label(card,"최종 목적지 · ${room().optJSONObject("destination")?.optString("name")?:"—"}",21,Palette.forest,true)
        label(card,"승인·적용 완료된 장소만 표시돼요.",17,Palette.secondary)
        button(card,"기본 내비 보기",enabled=!repository.busy){repository.showNativeNavigation()}
    }
    private fun party(parent:LinearLayout){
        label(parent,s(R.string.party),28,Palette.forest,true)
        label(parent,s(R.string.party_count,people().count{it.optString("status")=="boarded"},people().size),20,Palette.secondary,true)
        val messages=snapshot().optJSONArray("messages")
        messages?.optJSONObject(messages.length()-1)?.let{message->
            PassengerAnnouncements.spokenText(message)?.let{text->label(parent,"최근 메시지 · $text",20,Palette.forest,true)}
        }
        val highlight=if(screen=="party")selectedMember else if(repository.phase()=="pickup")snapshot().optJSONObject("route")?.optString("nextMemberId").orEmpty() else ""
        parent.addView(PartySeatsView(this,members(),room().optString("hostName"),!driving(),highlight){
            selectedMember=it.getString("memberId");scroll=null;scrollY["party|${repository.phase()}|$supportTab"]=0;show("party")
        },LinearLayout.LayoutParams(-1,0,1f))
        if(repository.phase()=="closed")label(parent,s(R.string.layout_saved_cabin),18,Palette.secondary)
        button(parent,s(R.string.v3_party_details),enabled=!driving()){selectedMember="";show("party")}
        label(parent,s(if(driving())R.string.v3_driving_compact else R.string.v3_seat_hint),18,Palette.secondary)
    }
    private fun partyDetails(parent:FrameLayout){
        val content=body(parent,s(R.string.v3_party_details))
        if(people().isEmpty())label(content,s(R.string.party_empty),24,Palette.secondary)
        val ordered=people().sortedBy{if(it.optString("memberId")==selectedMember)0 else 1}
        for(m in ordered){
            val c=d.column(Palette.softMint,12);content.addView(c,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
            val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};c.addView(row)
            row.addView(AvatarView(this,m.optJSONObject("avatar"),m.optString("displayName")),LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginEnd=dp(12)})
            row.addView(d.text(m.optString("displayName"),24,Palette.forest,true),LinearLayout.LayoutParams(0,-2,1f))
            val detail=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};c.addView(detail)
            val info=d.column();detail.addView(info,LinearLayout.LayoutParams(0,-2,1f))
            label(info,status(m.optString("status"))+" · "+s(R.string.assigned_seat,m.optInt("seat")),20,Palette.secondary)
            val pickup=m.optJSONObject("pickup")
            label(info,pickup?.optString("memo")?.ifBlank{pickup.optString("name")}?.ifBlank{s(R.string.coordinates,pickup.getDouble("lat"),pickup.getDouble("lng"))}?:s(R.string.pickup_missing),20,Palette.secondary)
            if(m.optString("status")!="boarded")detail.addView(d.button(s(R.string.confirm_boarding),true,writable()&&repository.phase() in listOf("planning","pickup")){repository.board(m.getString("memberId"))},LinearLayout.LayoutParams(dp(140),dp(64)).apply{marginStart=dp(10)})
        }
        label(content,s(R.string.no_sensors),18,Palette.secondary)
    }
    private fun requestTitle(q:JSONObject)=if(q.optString("kind")=="stop")s(R.string.request_stop,q.optJSONObject("place")?.optString("name")?:"—")else s(R.string.request_climate,q.optInt("seat"),s(when(q.optString("feature")){"temperature"->R.string.temperature;"heat"->R.string.heat;else->R.string.ventilation}),if(q.optString("feature")=="temperature")s(R.string.celsius_value,q.optString("value"))else q.optString("value"))
    private fun requestScreen(parent:FrameLayout){
        requestCards(body(parent,s(R.string.v3_requests)))
    }
    private fun requestCards(content:LinearLayout){
        if(repository.voiceStopPending)label(content,"음성 경유지 요청 전송 확인 중 · 연결되면 승인 대기로 복구돼요.",20,Palette.warning)
        if(requestList().isEmpty()&&!repository.voiceStopPending)label(content,s(R.string.no_requests),28)
        for(q in requestList().reversed().sortedBy{if(it.optString("status") in listOf("pending","approved"))0 else 1}){
            val c=d.column(Color.WHITE,10);content.addView(c,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
            label(c,requestTitle(q),24,Palette.forest,true)
            if(q.optString("source")=="voice"){
                label(c,"음성 제안 · "+(if(q.optString("searchSource")=="sample-fallback")"시연용 샘플 장소" else "주변 장소 검색"),18,Palette.secondary)
                label(c,"경로 적용: 기본 내비 확인 후 완료 · 우회 시간 미확인",18,Palette.warning)
            }
            if(q.optString("kind")=="stop" && q.optString("status") in listOf("pending","approved")) label(c,"승인 시 기본 내비에 반영 · 경유지 최대 2곳",18,Palette.secondary)
            val member=people().firstOrNull{it.optString("memberId")==q.optString("memberId")}
            label(c,s(R.string.request_from,member?.optString("displayName")?:s(R.string.seat_driver))+" · "+status(q.optString("status")),18,if(q.optString("status")=="failed")Palette.warning else Palette.secondary,true)
            if(q.optString("status")=="pending"){
                val actions=LinearLayout(this);c.addView(actions,LinearLayout.LayoutParams(-1,dp(64)))
                listOf(false,true).forEach{approve->
                    actions.addView(d.button(s(if(approve)R.string.approve else R.string.reject),approve,writable()&&(!approve||q.optString("source")!="voice"||repository.phase() in listOf("pickup","driving"))){
                        if(writable()&&requestList().any{it.optString("requestId")==q.optString("requestId")&&it.optString("status")=="pending"}){supportTab="requests";repository.decide(q.getString("requestId"),approve)}
                    }.apply{contentDescription=s(R.string.drive_request_action,requestTitle(q),s(if(approve)R.string.approve else R.string.reject))},LinearLayout.LayoutParams(0,-1,1f).apply{if(!approve)marginEnd=dp(8)})
                }
            }else if(q.optString("status")=="failed")label(c,UserMessages.error(this,q.optString("reason")),20,Palette.warning)
        }
    }
    private fun parkingScreen(parent:FrameLayout){
        val content=body(parent,s(R.string.parking_history),if(repository.phase()=="closed")"history" else "home")
        parkingContent(content)
    }
    private fun arrivalPanel(parent:LinearLayout){parkingContent(panelScroll(parent),compact=true)}
    private fun parkingContent(content:LinearLayout,compact:Boolean=false){
        val queued=repository.parkingPending
        val saved=snapshot().optJSONObject("parking")
        val parking=repository.queuedParking?:saved
        val synced=saved!=null&&!queued
        val arrived=repository.phase()=="arrived"
        if(compact){
            val summary=d.column(Palette.softMint,12);content.addView(summary)
            val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};summary.addView(row)
            val info=d.column();row.addView(info,LinearLayout.LayoutParams(0,-2,1f))
            label(info,s(when{queued->R.string.arrival_queued;synced->R.string.arrival_saved;else->R.string.arrival_not_saved}),18,Palette.warning,true)
            label(info,parking?.optString("memo")?.takeIf{it.isNotBlank()}?:s(if(parking==null)R.string.arrival_memo_prompt else R.string.arrival_no_memo),26,Palette.forest,true)
            if(arrived&&synced)row.addView(d.button(s(R.string.arrival_edit),enabled=!repository.authRequired&&!repository.busy){parkingDialog()},LinearLayout.LayoutParams(dp(150),dp(64)))
            val sharing=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};summary.addView(sharing)
            sharing.addView(d.text(s(when{queued->R.string.arrival_share_wait;synced->R.string.arrival_share_ready;else->R.string.arrival_share_after_save}),20,Palette.secondary),LinearLayout.LayoutParams(0,-2,1f))
            sharing.addView(d.button(s(R.string.parking_history)){show("parking")},LinearLayout.LayoutParams(dp(150),dp(64)))
            return
        }
        val location=card(content,Palette.softMint)
        label(location,s(when{queued->R.string.arrival_queued;synced->R.string.arrival_saved;else->R.string.arrival_not_saved}),19,Palette.warning,true)
        label(location,parking?.optString("memo")?.takeIf{it.isNotBlank()}?:s(if(parking==null)R.string.arrival_memo_prompt else R.string.arrival_no_memo),30,Palette.forest,true)
        label(location,s(R.string.home_destination,room().optJSONObject("destination")?.optString("name")?:"—"),20,Palette.secondary)
        parking?.optJSONObject("position")?.let{label(location,s(R.string.coordinates,it.optDouble("lat"),it.optDouble("lng")),17,Palette.secondary)}
        parking?.optString("updatedAt")?.takeIf{it.isNotBlank()}?.let{stamp->
            val time=runCatching{java.time.OffsetDateTime.parse(stamp).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("M/d a h:mm",java.util.Locale.KOREAN))}.getOrNull()
            if(time!=null)label(location,s(R.string.arrival_saved_at,time),17,Palette.secondary)
        }
        if(arrived&&synced)button(location,s(R.string.arrival_edit),false,!repository.authRequired&&!repository.busy){parkingDialog()}
        val sharing=location
        label(sharing,s(when{queued->R.string.arrival_share_wait;synced->R.string.arrival_share_ready;else->R.string.arrival_share_after_save}),22,Palette.forest,true)
        if(people().isEmpty())label(sharing,s(R.string.arrival_solo),19,Palette.secondary)
        else {
            label(sharing,s(R.string.arrival_recipients,people().size),19,Palette.warning,true)
            label(sharing,people().joinToString(" · "){it.optString("displayName")},21,Palette.forest,true)
        }
        label(sharing,s(if(queued)R.string.arrival_retry_note else R.string.arrival_mobile_note),18,Palette.secondary)
        label(content,s(R.string.parking_simulated),16,Palette.secondary)
    }
    private fun history(parent:FrameLayout){
        val data=snapshot()
        val closed=repository.phase()=="closed"
        val boarded=people().filter{it.optString("status")=="boarded"}
        val others=people().filter{it.optString("status")!="boarded"}
        val course=data.optJSONArray("course")?:JSONArray()
        val visits=data.optJSONArray("visits")?:JSONArray()
        val parking=repository.queuedParking?:data.optJSONObject("parking")
        val queued=repository.parkingPending
        val content=body(parent,s(R.string.record_title),if(closed)"rooms" else "home")
        label(content,s(R.string.record_stats,status(repository.phase()),boarded.size,course.length(),visits.length()),19,Palette.warning,true)
        val routeColumn=content;val details=content
        val route=card(routeColumn,Color.WHITE)
        label(route,s(R.string.record_route),26,Palette.forest,true)
        label(route,s(R.string.record_route_note),17,Palette.secondary)
        fun stop(tag:String,place:JSONObject?){
            val line=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
            route.addView(line,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
            line.addView(d.text(tag,18,Palette.warning,true),LinearLayout.LayoutParams(dp(92),-2))
            line.addView(d.text(place?.optString("name")?.takeIf{it.isNotBlank()}?:s(R.string.record_unknown_place),23,Palette.forest,true),LinearLayout.LayoutParams(0,-2,1f))
        }
        stop(s(R.string.record_origin),data.optJSONObject("origin"))
        for(i in 0 until course.length())stop(s(R.string.record_stop,i+1),course.getJSONObject(i))
        stop(s(R.string.record_destination),room().optJSONObject("destination"))
        if(course.length()==0)label(route,s(R.string.record_no_stops),18,Palette.secondary)
        val visitCard=card(routeColumn,Palette.softMint)
        label(visitCard,s(R.string.record_visits,visits.length()),26,Palette.forest,true)
        if(visits.length()==0)label(visitCard,s(R.string.record_no_visits),21,Palette.secondary)
        for(i in 0 until visits.length()){
            val visit=visits.getJSONObject(i)
            label(visitCard,visit.optString("name").ifBlank{s(R.string.record_unknown_place)},23,Palette.forest,true)
            recordTime(visit.optString("confirmedAt"))?.let{label(visitCard,s(R.string.record_confirmed_at,it),17,Palette.secondary)}
        }
        val crew=card(details,Color.WHITE)
        label(crew,s(R.string.record_crew,boarded.size),26,Palette.forest,true)
        label(crew,s(R.string.record_driver,room().optString("hostName").ifBlank{s(R.string.seat_driver)}),18,Palette.warning,true)
        if(boarded.isEmpty())label(crew,s(if(people().isEmpty())R.string.record_solo else R.string.record_none_boarded),21,Palette.secondary)
        for(member in boarded){
            val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
            crew.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
            row.addView(AvatarView(this,member.optJSONObject("avatar"),member.optString("displayName")),LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginEnd=dp(12)})
            row.addView(d.text(member.optString("displayName"),23,Palette.forest,true),LinearLayout.LayoutParams(0,-2,1f))
            row.addView(d.text(s(R.string.boarded),17,Palette.secondary))
        }
        if(others.isNotEmpty())label(crew,s(R.string.record_not_boarded,others.joinToString(" · "){it.optString("displayName")}),18,Palette.secondary)
        val parked=card(details,Palette.softMint)
        label(parked,s(R.string.record_parking),26,Palette.forest,true)
        if(parking==null)label(parked,s(R.string.record_no_parking),21,Palette.secondary)else{
            label(parked,parking.optString("memo").ifBlank{s(R.string.arrival_no_memo)},28,Palette.forest,true)
            parking.optJSONObject("position")?.let{label(parked,s(R.string.coordinates,it.optDouble("lat"),it.optDouble("lng")),18,Palette.secondary)}
            recordTime(parking.optString("updatedAt"))?.let{label(parked,s(R.string.arrival_saved_at,it),17,Palette.secondary)}
            label(parked,s(if(queued)R.string.arrival_share_wait else R.string.arrival_share_ready),20,Palette.forest,true)
            if(parking.optBoolean("simulation"))label(parked,s(R.string.arrival_map_note),16,Palette.secondary)
        }
        button(parked,s(R.string.parking_history)){show("parking")}
        if(closed)label(content,s(R.string.record_retained),18,Palette.secondary)
    }
    private fun recordTime(value:String):String?=runCatching{
        java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("M/d a h:mm",java.util.Locale.KOREAN))
    }.getOrNull()
    private fun settings(parent:FrameLayout){
        val content=body(parent,s(R.string.v3_settings))
        val row=LinearLayout(this);content.addView(row)
        listOf(R.string.v3_requests to "requests",R.string.v3_history to "history",R.string.parking_history to "parking").forEach{(id,page)->row.addView(d.button(s(id)){show(page)},LinearLayout.LayoutParams(0,dp(68),1f).apply{marginEnd=dp(10)})}
        val c=card(content);label(c,s(R.string.connection),28,Palette.forest,true)
        label(c,s(if(repository.sse&&repository.online)R.string.live else if(repository.online)R.string.polling else R.string.offline),22,Palette.secondary)
        label(c,s(R.string.settings_detail,repository.base,repository.tripId),20,Palette.secondary)
        label(c,s(R.string.last_sync,if(repository.lastSync==0L)s(R.string.never_synced)else DateFormat.getDateTimeInstance(DateFormat.DEFAULT,DateFormat.DEFAULT,java.util.Locale.KOREAN).format(Date(repository.lastSync))),18,Palette.secondary)
        button(c,s(R.string.retry),enabled=!repository.authRequired&&repository.phase()!="closed"){repository.retry()}
        button(c,s(R.string.pair_another),enabled=repository.canSwitchParty||repository.authRequired){pairing()}
        content.addView(Switch(this).apply{
            text="동승자 메시지 음성 읽기";textSize=22*d.scale;setTextColor(Palette.forest)
            isChecked=preferences.getBoolean("passenger-voice",true);minHeight=dp(64)
            setOnCheckedChangeListener{_,checked->preferences.edit().putBoolean("passenger-voice",checked).apply();updateAnnouncements()}
        })
        label(content,"새 픽업 메시지를 이름과 함께 읽어요. 음성 대화 중에는 잠시 기다려요.",18,Palette.secondary)
        content.addView(Switch(this).apply {
            text="Hey Movo로 음성 호출";textSize=22*d.scale;setTextColor(Palette.forest);minHeight=dp(64)
            isChecked=preferences.getBoolean("hey-movo",false)
            setOnCheckedChangeListener{_,checked->
                preferences.edit().putBoolean("hey-movo",checked).apply();if(checked)enableWake()else updateWake()
            }
        })
        label(content,"기본 내비에서도 ‘헤이 모보’라고 말하면 MOVO 음성창이 열려요. 시작음을 듣고 명령을 말해 주세요. 켜둔 동안 알림을 표시하고 기기 내 음성인식으로 호출어를 확인해요. 녹음·인식 내용은 저장하거나 전송하지 않아요. 통화·화면 잠금·MOVO 음성 대화 중에는 대기를 멈춰요. 알림의 ‘대기 끄기’ 또는 상단 표시로 끌 수 있어요.",18,Palette.secondary)
        val toggle=Switch(this).apply{text=s(R.string.v3_online_map);textSize=22*d.scale;setTextColor(Palette.forest);isChecked=tiles.enabled;setPadding(dp(12),dp(12),dp(12),dp(12));minHeight=dp(64)
            setOnCheckedChangeListener{_,checked->tiles.enabled=checked;preferences.edit().putBoolean("online-map",checked).apply()}}
        content.addView(toggle);label(content,s(R.string.v3_map_privacy),18,Palette.secondary)
        label(content,s(R.string.simulation_note),20,Palette.secondary)
        if(tiles.lastFailure.isNotBlank())label(content,UserMessages.mapError(this,tiles.lastFailure),18,Palette.warning)
        button(content,s(R.string.legacy)){startActivity(Intent(this,LegacyActivity::class.java))}
    }
    private fun showModal(){
        modal?.show()
        listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE).forEach { which ->
            modal?.getButton(which)?.apply { textSize=22*d.scale;minHeight=dp(64);setTextColor(Palette.forest) }
        }
    }
    private fun pairing(mode:String="together"){
        if(!repository.canSwitchParty && !repository.authRequired)return
        val form=d.column(padding=24)
        val modes=RadioGroup(this).apply{orientation=RadioGroup.HORIZONTAL}
        val solo=RadioButton(this).apply{id=View.generateViewId();text=s(R.string.garage_solo);textSize=20f;setTextColor(Palette.forest)}
        val together=RadioButton(this).apply{id=View.generateViewId();text=s(R.string.garage_together);textSize=20f;setTextColor(Palette.forest)}
        modes.addView(solo);modes.addView(together);modes.check(if(mode=="solo")solo.id else together.id);form.addView(modes)
        label(form,s(R.string.backend_address),20,Palette.secondary)
        val base=EditText(this).apply{setText(repository.base);setSingleLine();textSize=22f;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI};form.addView(base)
        label(form,s(R.string.pair_code),20,Palette.secondary)
        val code=EditText(this).apply{hint=s(R.string.pair_code);setSingleLine();textSize=28f;inputType=android.text.InputType.TYPE_CLASS_NUMBER;filters=arrayOf(android.text.InputFilter.LengthFilter(6))};form.addView(code)
        modal=AlertDialog.Builder(this).setTitle(R.string.pair).setView(form).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.pair){_,_->openPreparationWhenReady=true;repository.pair(base.text.toString(),code.text.toString(),if(solo.isChecked)"solo" else "together")}.create();showModal()
    }
    private fun openVoice(dictateMemo:Boolean=false){
        if(!foreground||voiceOpening||repository.snapshot==null||repository.authRequired||repository.busy)return
        voiceOpening=true
        val expected=voiceKey()
        updateWake()
        val afterWake:()->Unit={
            if(foreground&&voiceOpening&&expected==voiceKey())openVoiceAfterWake(dictateMemo)
            voiceOpening=false;updateWake()
        }
        WakeListeningService.current?.pauseForVoice(afterWake) ?: afterWake()
    }
    private fun openVoiceAfterWake(dictateMemo:Boolean){
        voiceDialog?.dismiss()
        announcements.suspendPlayback(true)
        voiceContextKey=voiceKey()
        val startedFor=voiceContextKey
        voiceDialog=VoiceDialog(this,dictateMemo,{
            VoiceCommands.Context(repository.snapshot,repository.online,repository.authRequired,
                repository.lastSync,parkingPending=repository.parkingPending,busy=repository.busy)
        },memoReady={memo->
            if(startedFor==voiceKey()&&!repository.authRequired)parkingDialog(memo)
        },dismissed={voiceDialog=null;updateAnnouncements();announcements.suspendPlayback(false);updateWake()},stopReady={query->
            if(startedFor==voiceKey()&&!repository.authRequired)openVoiceStopCandidates(query)
        }).also{it.show()}
    }
    private fun openVoiceStopCandidates(query:String){
        if(!writable()||repository.phase() !in listOf("pickup","driving"))return
        voiceStopDialog?.dismiss()
        val session="${repository.base}|${repository.tripId}"
        voiceStopContextKey=voiceKey();val expected=voiceStopContextKey
        voiceStopDialog=VoiceStopDialog(this,query,
            valid={foreground&&expected==voiceKey()&&writable()&&!repository.voiceStopPending},
            search={callback->repository.findVoiceStops(query,session,callback)},
            selected={place,revision,source->
                if(expected==voiceKey()&&writable()){
                    repository.proposeVoiceStop(place,revision,source,session)
                    supportTab="requests";show("requests")
                }
            },dismissed=::onVoiceStopsDismissed).also{it.show()}
    }
    private fun onVoiceStopsDismissed(closed:VoiceStopDialog){
        // An older dialog's queued dismissal must not clear a newer candidate window.
        if(voiceStopDialog===closed){voiceStopDialog=null;updateWake()}
    }
    private fun parkingDialog(voiceMemo:String?=null){
        if(repository.phase()!="arrived"||repository.parkingPending||repository.authRequired||repository.busy)return
        val form=d.column(padding=24);label(form,s(R.string.parking_simulated),18,Palette.secondary)
        val input=EditText(this).apply{setSingleLine();filters=arrayOf(android.text.InputFilter.LengthFilter(160));textSize=26f;hint=s(R.string.parking_hint);setText(voiceMemo?:snapshot().optJSONObject("parking")?.optString("memo")?:"")};form.addView(input)
        val startedFor=voiceKey()
        modal=AlertDialog.Builder(this).setTitle(R.string.parking_memo).setView(form)
            .setNegativeButton(R.string.cancel,null)
            .setNeutralButton("말로 입력"){_,_->openVoice(dictateMemo=true)}
            .setPositiveButton(R.string.arrival_save_share){_,_->
                if(startedFor==voiceKey()&&!repository.authRequired&&!repository.parkingPending&&!repository.busy)
                    repository.park(input.text.toString())
            }.create();showModal()
    }
    @Suppress("DEPRECATION") override fun onBackPressed(){
        val target=ScreenFlow.back(screen,repository.phase(),repository.tripId.isNotBlank())
        if(target!=null)show(target)else super.onBackPressed()
    }
}
