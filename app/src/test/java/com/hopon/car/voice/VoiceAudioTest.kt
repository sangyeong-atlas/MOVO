package com.hopon.car.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceAudioTest {
    private class Audio : VoiceAudio {
        var grant=true;var held=false;var released=0
        val modes=mutableListOf<Boolean>();val losses=mutableListOf<()->Unit>()
        val cues=mutableListOf<Pair<Boolean,()->Unit>>()
        override fun acquire(listening:Boolean,interrupted:()->Unit):Boolean {
            modes+=listening;losses+=interrupted;held=grant;return grant
        }
        override fun cue(start:Boolean,completed:()->Unit){cues+=start to completed}
        override fun release(){held=false;released++}
        fun finishCue(){cues.last().second()}
    }
    private class Input:VoiceInput {
        lateinit var events:VoiceInput.Events;var listens=0;var closed=false
        override fun initialize(events:VoiceInput.Events){this.events=events}
        override fun listen(){listens++}
        override fun close(){closed=true}
    }
    private class Output:VoiceOutput {
        lateinit var events:VoiceOutput.Events;var speaks=0;var closed=false
        override fun initialize(events:VoiceOutput.Events){this.events=events}
        override fun speak(text:String){speaks++}
        override fun close(){closed=true}
    }
    private class Fixture {
        val audio=Audio();val input=Input();val output=Output();val timers=mutableListOf<()->Unit>();var answers=0
        val session=VoiceSession({input},{output},VoiceTimer{_,task->timers+=task;{}},
            {answers++;VoiceCommands.Reply("답변")},{},audio)
        fun ready(){session.listen();input.events.ready()}
        fun listening(){ready();audio.finishCue()}
    }
    @Test fun micWaitsForStartCueAndTtsWaitsForEndCue(){
        val f=Fixture();f.ready();assertEquals(0,f.input.listens);assertEquals(VoiceStage.CONNECTING,f.session.state.stage)
        f.input.events.ready();assertEquals(1,f.audio.cues.size)
        f.audio.finishCue();assertEquals(1,f.input.listens);assertEquals(VoiceStage.LISTENING,f.session.state.stage)
        f.input.events.result("여정 상태",true);assertTrue(f.input.closed);assertEquals(0,f.answers)
        assertEquals(VoiceStage.FINISHING,f.session.state.stage);assertTrue(f.audio.held)
        f.input.events.result("중복",true);f.session.listen();assertEquals(listOf(true,false),f.audio.cues.map{it.first})
        f.audio.finishCue();assertFalse(f.audio.held);assertEquals(1,f.answers);assertEquals(VoiceStage.SPEAKING,f.session.state.stage)
        f.audio.finishCue();assertEquals(1,f.answers)
    }
    @Test fun focusDeniedNeverOpensMicOrPlaysCues(){
        val f=Fixture();f.audio.grant=false;f.ready();assertEquals(0,f.input.listens)
        assertTrue(f.audio.cues.isEmpty());assertTrue(f.input.closed);assertTrue(f.session.state.error.contains("사용 중"))
    }
    @Test fun focusLossDuringStartCueCancelsMicAndDoesNotResume(){
        val f=Fixture();f.ready();f.audio.losses.last()();f.audio.finishCue()
        assertEquals(0,f.input.listens);assertEquals(VoiceStage.ERROR,f.session.state.stage);assertTrue(f.input.closed)
    }
    @Test fun focusLossDuringCaptureStopsWithoutAnEndSoundOrCommand(){
        val f=Fixture();f.listening();f.audio.losses.last()();f.input.events.result("주차 메모 B3",true)
        assertEquals(0,f.answers);assertTrue(f.input.closed);assertEquals(1,f.audio.cues.size);assertFalse(f.audio.held)
    }
    @Test fun focusLossDuringEndCueCancelsPendingAnswer(){
        val f=Fixture();f.listening();f.input.events.result("결과",true)
        f.audio.losses.last()();f.audio.finishCue();assertEquals(0,f.answers);assertEquals(VoiceStage.ERROR,f.session.state.stage)
    }
    @Test fun closedOrCancelledSessionsIgnoreQueuedCueCompletions(){
        for(close in listOf(true,false)){
            val f=Fixture();f.ready();if(close)f.session.close() else f.session.cancel();f.audio.finishCue()
            assertEquals(0,f.input.listens);assertFalse(f.audio.held)
        }
        val f=Fixture();f.listening();f.input.events.result("결과",true);f.session.close();f.audio.finishCue();assertEquals(0,f.answers)
    }
    @Test fun oldFocusLossCannotInterruptRetry(){
        val f=Fixture();f.ready();val old=f.audio.losses.last();f.session.cancel();f.ready();old()
        assertEquals(VoiceStage.CONNECTING,f.session.state.stage);f.audio.finishCue();assertEquals(1,f.input.listens)
    }
    @Test fun noResultStopsMicBeforeEndCueAndShowsErrorAfterward(){
        val f=Fixture();f.listening();f.input.events.ended();f.timers.last()()
        assertTrue(f.input.closed);assertEquals(listOf(true,false),f.audio.cues.map{it.first})
        f.audio.finishCue();assertEquals(VoiceStage.ERROR,f.session.state.stage);assertEquals(0,f.answers)
    }
    @Test fun endCueTimeoutDoesNotHangOrExecuteTwice(){
        val f=Fixture();f.listening();f.input.events.result("결과",true);f.timers.last()();f.audio.finishCue()
        assertEquals(1,f.answers);assertFalse(f.audio.held)
    }
    @Test fun playbackDeniedRetainsTextAndNeverCallsSdkSpeak(){
        val a=Audio().apply{grant=false};val o=Output();val codes=mutableListOf<String>()
        val wrapped=FocusedVoiceOutput(o,a)
        wrapped.initialize(object:VoiceOutput.Events{
            override fun ready()=Unit;override fun done()=Unit;override fun failed(code:String){codes+=code}
        })
        wrapped.speak("내용");assertEquals(0,o.speaks);assertEquals(listOf("AUDIO_BUSY"),codes);assertTrue(o.closed)
    }
    @Test fun playbackLossStopsServiceReleasesFocusAndIgnoresLateDone(){
        val a=Audio();val o=Output();var done=0;val codes=mutableListOf<String>();val wrapped=FocusedVoiceOutput(o,a)
        wrapped.initialize(object:VoiceOutput.Events{
            override fun ready()=Unit;override fun done(){done++};override fun failed(code:String){codes+=code}
        })
        wrapped.speak("내용");assertEquals(listOf(false),a.modes);a.losses.single()()
        o.events.done();o.events.failed("late");wrapped.speak("다시")
        assertEquals(1,o.speaks);assertTrue(o.closed);assertFalse(a.held);assertEquals(0,done);assertEquals(listOf("AUDIO_INTERRUPTED"),codes)
    }
    @Test fun successfulPlaybackAndCloseBothReturnFocusExactlyOnce(){
        val a=Audio();val o=Output();var done=0;val wrapped=FocusedVoiceOutput(o,a)
        wrapped.initialize(object:VoiceOutput.Events{
            override fun ready()=Unit;override fun done(){done++};override fun failed(code:String)=Unit
        })
        wrapped.speak("내용");o.events.done();wrapped.close();o.events.done()
        assertEquals(1,done);assertEquals(1,a.released);assertTrue(o.closed)
    }
}
