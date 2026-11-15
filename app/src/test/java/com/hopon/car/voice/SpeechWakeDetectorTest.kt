package com.hopon.car.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechWakeDetectorTest {
    private class Timer:VoiceTimer {val tasks=mutableListOf<()->Unit>();override fun after(delayMs:Long,action:()->Unit):()->Unit {tasks+=action;return{tasks.remove(action)}};fun fire(){val list=tasks.toList();tasks.clear();list.forEach{it()}}}
    private class Input:VoiceInput {lateinit var events:VoiceInput.Events;var closed=false;var listens=0;override fun initialize(events:VoiceInput.Events){this.events=events};override fun listen(){listens++};override fun close(){closed=true}}
    @Test fun onlyCompleteWakePhraseTriggers(){
        listOf("헤이 모보","헤이모보!","헤이 무브","Hey Movo.","hey mobo").forEach{assertTrue(it,WakePhrase.matches(it))}
        listOf("헤이 모바일","해마다","hey move over","헤이 모범","모보","여정 상태 알려줘","그 사람이 헤이 모보라고 했어","헤이 모보 여정 상태 알려줘").forEach{assertFalse(it,WakePhrase.matches(it))}
    }
    @Test fun microphoneRecognitionSpellingTriggersButNearbyPhrasesDoNot(){
        listOf("해이무브","해이 무브","해이모보","해이 모보!").forEach{assertTrue(it,WakePhrase.matches(it))}
        listOf("해이 모바일","해이모범","해이 무브먼트","해이무브 여정 상태 알려줘","해이무북").forEach{assertFalse(it,WakePhrase.matches(it))}
        val timer=Timer();val input=Input();var heard=0
        val detector=SpeechWakeDetector({input},timer)
        detector.start({},{heard++},{fail("Unexpected failure")})
        input.events.ready();input.events.result("해이무브",false);assertEquals(0,heard)
        input.events.result("해이무브",true);assertTrue(input.closed);timer.fire();assertEquals(1,heard)
    }
    @Test fun finalDetectionClosesMicrophoneBeforeOpeningDialog(){
        val timer=Timer();val input=Input();var heard=0
        val detector=SpeechWakeDetector({input},timer)
        detector.start({},{assertTrue(input.closed);heard++},{fail("Unexpected failure")})
        input.events.ready();input.events.result("헤이 모보",false);assertEquals(0,heard)
        input.events.result("헤이 모보",true);assertTrue(input.closed);assertEquals(0,heard)
        timer.fire();assertEquals(1,heard);input.events.result("헤이 모보",true);assertEquals(1,heard)
    }
    @Test fun nonWakeSpeechIsDiscardedAndDetectionRearms(){
        val timer=Timer();val inputs=mutableListOf<Input>();var heard=0
        val detector=SpeechWakeDetector({Input().also{inputs+=it}},timer)
        detector.start({},{heard++},{})
        inputs[0].events.ready();inputs[0].events.result("주차 메모 지하 3층",true)
        assertTrue(inputs[0].closed);timer.fire();assertEquals(2,inputs.size);assertEquals(0,heard)
    }
    @Test fun cancelSuppressesQueuedDetectionAndRearming(){
        val timer=Timer();val input=Input();var heard=0;var released=false
        val detector=SpeechWakeDetector({input},timer);detector.start({},{heard++},{})
        input.events.ready();input.events.result("헤이 모보",true);detector.stop{released=true};timer.fire()
        assertTrue(released);assertEquals(0,heard);assertTrue(input.closed)
    }
    @Test fun microphoneErrorsDoNotRetry(){
        val timer=Timer();val input=Input();var failure=0
        val detector=SpeechWakeDetector({input},timer);detector.start({},{},{failure++})
        input.events.ready();input.events.failed("MIC_ERROR");timer.fire();assertEquals(1,failure);assertTrue(input.closed)
    }
}
