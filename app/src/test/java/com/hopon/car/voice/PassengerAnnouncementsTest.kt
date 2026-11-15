package com.hopon.car.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PassengerAnnouncementsTest {
    private class Output : VoiceOutput {
        lateinit var events: VoiceOutput.Events
        val texts=mutableListOf<String>(); var closed=false
        override fun initialize(events: VoiceOutput.Events){this.events=events}
        override fun speak(text:String){texts+=text}
        override fun close(){closed=true}
    }
    private class Fixture {
        var now=1_800_000_000_000L;var failures=0
        val saved=mutableMapOf<String,Set<String>>();val outputs=mutableListOf<Output>();val deadlines=mutableListOf<()->Unit>()
        fun controller()=PassengerAnnouncements({Output().also{outputs+=it}},VoiceTimer{_,action->deadlines+=action;{}},
            {saved[it]},{k,v->saved[k]=v},{now},{failures++})
        val c=controller()
        fun msg(id:String,type:String="late_5min",time:Long=now)=JSONObject().put("id",id).put("memberId","M-1")
            .put("displayName","민지").put("type",type).put("text","untrusted text").put("createdAt",Instant.ofEpochMilli(time).toString())
        fun snap(vararg messages:JSONObject)=JSONObject().put("messages",JSONArray(messages.toList()))
        fun baseline(){c.update("A",snap(),true)}
    }
    @Test fun ignoresHistoryAndSpeaksNewMessageOnceAcrossPollingAndRestart(){
        val f=Fixture();val old=f.msg("old");val fresh=f.msg("new")
        f.c.update("A",f.snap(old),true);assertTrue(f.outputs.isEmpty())
        f.c.update("A",f.snap(old,fresh),true);val o=f.outputs.single()
        o.events.ready();o.events.ready();assertEquals(listOf("민지님의 메시지입니다. 5분 늦어요."),o.texts)
        o.events.done();f.c.update("A",f.snap(old,fresh),true)
        f.controller().update("A",f.snap(old,fresh),true);assertEquals(1,f.outputs.size)
    }
    @Test fun queuesDuringVoiceDialogAndPlaysSerially(){
        val f=Fixture();f.baseline();f.c.suspendPlayback(true)
        f.c.update("A",f.snap(f.msg("1"),f.msg("2","came_out")),true);assertTrue(f.outputs.isEmpty())
        f.c.suspendPlayback(false);val one=f.outputs.single();one.events.ready();one.events.done()
        val two=f.outputs.last();two.events.ready();assertEquals("민지님의 메시지입니다. 나왔어요.",two.texts.single())
        assertEquals(2,f.outputs.size)
    }
    @Test fun manualVoiceInterruptsPlaybackAndLateCallbacksCannotResumeIt(){
        val f=Fixture();f.baseline();f.c.update("A",f.snap(f.msg("1"),f.msg("2")),true)
        val old=f.outputs.single();f.c.suspendPlayback(true);assertTrue(old.closed)
        old.events.ready();old.events.done();assertTrue(old.texts.isEmpty())
        f.c.suspendPlayback(false);assertEquals(2,f.outputs.size)
        f.deadlines.first().invoke();assertEquals(0,f.failures)
    }
    @Test fun tripSwitchStopsPlaybackAndDoesNotReadOtherTripsHistory(){
        val f=Fixture();f.baseline();f.c.update("A",f.snap(f.msg("1")),true);val old=f.outputs.single()
        f.c.update("B",f.snap(f.msg("2")),true);assertTrue(old.closed);old.events.ready()
        assertTrue(old.texts.isEmpty());assertEquals(1,f.outputs.size)
    }
    @Test fun muteOfflineAndStopDiscardPendingWithoutReplay(){
        val f=Fixture();f.baseline();val s=f.snap(f.msg("1"))
        f.c.update("A",s,false);f.c.update("A",s,true);assertTrue(f.outputs.isEmpty())
        f.c.suspendPlayback(true);val next=f.snap(f.msg("1"),f.msg("2"));f.c.update("A",next,true)
        f.c.stop();f.c.suspendPlayback(false);f.c.update("A",next,true);assertTrue(f.outputs.isEmpty())
    }
    @Test fun staleFutureMalformedAndUnsupportedMessagesAreNotSpoken(){
        val f=Fixture();f.baseline()
        f.c.update("A",f.snap(f.msg("old",time=f.now-120001),f.msg("future",time=f.now+5001),
            f.msg("bad").put("createdAt","broken"),f.msg("command","delete_trip")),true)
        assertTrue(f.outputs.isEmpty())
    }
    @Test fun waitingMessagesExpireAndBurstQueueIsBounded(){
        val f=Fixture();f.baseline();f.c.suspendPlayback(true)
        f.c.update("A",f.snap(*(1..30).map{f.msg("$it")}.toTypedArray()),true)
        f.c.suspendPlayback(false)
        repeat(10){f.outputs.last().events.ready();f.outputs.last().events.done()};assertEquals(10,f.outputs.size)
        f.c.suspendPlayback(true);f.c.update("A",f.snap(f.msg("31")),true);f.now+=120001;f.c.suspendPlayback(false)
        assertEquals(10,f.outputs.size)
    }
    @Test fun timeoutAndSdkErrorReleaseAudioAndDoNotRetryTheSameMessage(){
        val f=Fixture();f.baseline();val s=f.snap(f.msg("1"),f.msg("2"));f.c.update("A",s,true)
        f.deadlines.last().invoke();assertTrue(f.outputs.single().closed);assertEquals(1,f.failures)
        f.outputs.single().events.failed("ERROR");assertEquals(1,f.failures)
        f.c.update("A",s,true);assertEquals(1,f.outputs.size)
        f.c.update("A",f.snap(f.msg("3")),true);f.outputs.last().events.failed("ERROR");assertEquals(2,f.failures)
    }
}
