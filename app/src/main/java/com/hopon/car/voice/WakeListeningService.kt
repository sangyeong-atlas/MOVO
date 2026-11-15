package com.hopon.car.voice

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import com.hopon.car.MainActivity
import com.hopon.car.R

/** One microphone owner across MOVO/native navigation. Started only from a visible Activity.
 * No Journey repository, network, recordings, transcripts or automatic restart. */
class WakeListeningService:Service() {
    companion object {
        const val OPEN_VOICE="com.hopon.car.OPEN_WAKE_VOICE"
        private const val STOP="com.hopon.car.STOP_WAKE"
        private const val CHANNEL="movo-wake"
        private const val NOTIFICATION=4101
        var current:WakeListeningService?=null; private set
        var starting=false; private set
        fun permissionsReady(context:Context)=Settings.canDrawOverlays(context) &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        fun startFromVisibleActivity(context:Context) {
            if(current!=null||starting||!permissionsReady(context))return
            starting=true
            try {context.startForegroundService(Intent(context,WakeListeningService::class.java))}
            catch(_:RuntimeException){starting=false}
        }
    }
    private val handler=Handler(Looper.getMainLooper())
    private val prefs by lazy{getSharedPreferences("hopon-ui-v3",MODE_PRIVATE)}
    private var eligible=false
    private var appBlocked=false
    private var pendingAt=0L
    private var stopping=false
    private val wake=WakeWordController({SpeechWakeDetector({PleosVoiceInput(this)},VoiceTimer { ms,action ->
        val task=Runnable{action()};handler.postDelayed(task,ms)
        val cancel:()->Unit={handler.removeCallbacks(task)};cancel
    })},heard={onWake()},changed={if(!stopping)publishNotification()})
    val state get()=wake.state
    private val tick=object:Runnable {override fun run(){
        if(stopping)return
        if(!prefs.getBoolean("hey-movo",false)||!permissionsReady(this@WakeListeningService)){shutdown();return}
        if(pendingAt!=0L&&SystemClock.elapsedRealtime()-pendingAt>10000)pendingAt=0L
        val unlocked=!(getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val screenOn=getSystemService(PowerManager::class.java).isInteractive
        wake.update(true,eligible&&!appBlocked&&pendingAt==0L&&unlocked&&screenOn&&
            getSystemService(AudioManager::class.java).mode==AudioManager.MODE_NORMAL)
        handler.postDelayed(this,500)
    }}
    override fun onCreate(){
        super.onCreate();current=this;starting=false
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Hey Movo 호출어 대기",NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION,notification())
        handler.post(tick)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){prefs.edit().putBoolean("hey-movo",false).apply();shutdown()}
        return START_NOT_STICKY
    }
    override fun onBind(intent:Intent?)=null
    fun update(eligible:Boolean,blocked:Boolean){
        this.eligible=eligible;appBlocked=blocked
        if(!eligible)pendingAt=0L
        if(!eligible||blocked)wake.update(true,false)
    }
    fun pauseForVoice(completed:()->Unit){appBlocked=true;wake.update(true,false);wake.pause(completed)}
    fun consumeWake():Boolean {
        if(pendingAt==0L)return false
        val valid=SystemClock.elapsedRealtime()-pendingAt<=10000
        pendingAt=0L;appBlocked=true
        return valid
    }
    private fun onWake(){
        if(stopping||!eligible||!prefs.getBoolean("hey-movo",false))return
        pendingAt=SystemClock.elapsedRealtime();publishNotification()
        // SpeechWakeDetector releases its SDK capture before this callback. Only opens the
        // existing voice UI; no recognized command or Journey mutation travels in the Intent.
        try {startActivity(Intent(this,MainActivity::class.java).setAction(OPEN_VOICE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))}
        catch(_:RuntimeException){/* Notification remains a user-controlled fallback. */}
    }
    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,WakeListeningService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        val detail=when {
            pendingAt!=0L->"호출을 확인했어요 · 눌러서 MOVO 열기"
            wake.state==WakeState.ERROR->"음성인식을 시작하지 못했어요 · 설정에서 다시 켜 주세요"
            wake.state==WakeState.LISTENING->"기본 내비에서도 ‘헤이 모보’라고 불러 주세요"
            else->"통화·음성 대화·화면 잠금 중에는 잠시 멈춰요"
        }
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_movo)
            .setContentTitle("Hey Movo 호출어 대기").setContentText(detail).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null,"대기 끄기",stop).build()).build()
    }
    private fun publishNotification(){getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification())}
    fun shutdown(){
        if(stopping)return
        stopping=true;pendingAt=0;handler.removeCallbacks(tick)
        wake.update(false,false)
        wake.pause{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    }
    override fun onTaskRemoved(rootIntent:Intent?){shutdown();super.onTaskRemoved(rootIntent)}
    override fun onDestroy(){
        stopping=true;handler.removeCallbacks(tick);wake.update(false,false)
        if(current===this)current=null
        starting=false;super.onDestroy()
    }
}
