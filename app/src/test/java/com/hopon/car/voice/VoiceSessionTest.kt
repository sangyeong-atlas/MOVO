package com.hopon.car.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionTest {
    private class Input : VoiceInput {
        lateinit var events: VoiceInput.Events
        var listens=0; var closes=0
        override fun initialize(events: VoiceInput.Events) { this.events=events }
        override fun listen() { listens++ }
        override fun close() { closes++ }
    }
    private class Output : VoiceOutput {
        lateinit var events: VoiceOutput.Events
        val texts=mutableListOf<String>(); var closes=0
        override fun initialize(events: VoiceOutput.Events) { this.events=events }
        override fun speak(text:String) { texts+=text }
        override fun close() { closes++ }
    }
    private class Timer : VoiceTimer {
        data class Task(val delay:Long,val run:()->Unit,var cancelled:Boolean=false)
        val tasks=mutableListOf<Task>()
        override fun after(delayMs:Long,action:()->Unit):()->Unit {
            val task=Task(delayMs,action); tasks+=task; return { task.cancelled=true }
        }
        fun fire() { tasks.last { !it.cancelled }.run() }
    }
    private class Fixture {
        val inputs=mutableListOf<Input>(); val outputs=mutableListOf<Output>(); val timer=Timer()
        var answers=0
        val session=VoiceSession({Input().also{inputs+=it}},{Output().also{outputs+=it}},timer,
            { answers++; VoiceCommands.Reply("확인해 주세요", "B3") }, {})
        fun listening():Input { session.listen(); return inputs.last().also { it.events.ready() } }
    }
    @Test fun initializationMustFinishBeforeListeningAndCannotRepeat() {
        val f=Fixture(); f.session.listen(); val i=f.inputs.single()
        assertEquals(0,i.listens); i.events.ready(); i.events.ready(); f.session.listen()
        assertEquals(1,i.listens); assertEquals(1,f.inputs.size)
    }
    @Test fun partialResultsNeverExecuteAndFinalIsConsumedOnce() {
        val f=Fixture(); val i=f.listening(); i.events.result("주차",false)
        assertEquals(0,f.answers); i.events.result("주차 메모 B3",true); i.events.result("주차 메모 B3",true)
        assertEquals(1,f.answers); assertEquals(1,i.closes)
        val o=f.outputs.single(); assertTrue(o.texts.isEmpty()); o.events.ready(); o.events.ready()
        assertEquals(listOf("확인해 주세요"),o.texts); o.events.done()
        assertEquals(VoiceStage.DONE,f.session.state.stage); assertEquals(1,o.closes)
    }
    @Test fun cancellationRevokesLateRecognitionAndRetryUsesNewClient() {
        val f=Fixture(); val old=f.listening(); f.session.cancel(); val current=f.listening()
        old.events.result("B3",true); old.events.failed("PERMISSION_DENIED")
        assertEquals(0,f.answers); assertEquals(VoiceStage.LISTENING,f.session.state.stage)
        current.events.result("B4",true); assertEquals(1,f.answers)
    }
    @Test fun closeStopsClientsAndIsTerminal() {
        val f=Fixture(); val i=f.listening(); f.session.close(); f.session.close(); f.session.listen()
        i.events.result("B3",true); assertEquals(0,f.answers); assertEquals(1,i.closes); assertEquals(1,f.inputs.size)
    }
    @Test fun finalAfterEndIsAcceptedButMissingFinalTimesOut() {
        val f=Fixture(); val i=f.listening(); i.events.ended(); i.events.result("B3",true)
        assertEquals(1,f.answers)
        val missing=Fixture(); val j=missing.listening(); j.events.result("B3",false); j.events.ended(); missing.timer.fire()
        assertEquals(0,missing.answers); assertEquals(VoiceStage.ERROR,missing.session.state.stage)
    }
    @Test fun cancelledTimersCannotBreakNewSession() {
        val f=Fixture(); f.listening(); val old=f.timer.tasks.toList(); f.session.cancel(); f.listening()
        old.forEach{it.run()}; assertEquals(VoiceStage.LISTENING,f.session.state.stage)
    }
    @Test fun initializationAndRecognitionHaveBoundedTimeouts() {
        val f=Fixture(); f.session.listen(); f.timer.fire(); assertEquals(VoiceStage.ERROR,f.session.state.stage)
        f.listening(); f.timer.fire(); assertEquals(VoiceStage.ERROR,f.session.state.stage)
        assertEquals(0,f.answers)
    }
    @Test fun synthesisFailureRetainsTextAndMemoDraft() {
        val f=Fixture(); f.listening().events.result("B3",true)
        f.outputs.single().events.failed("ON_DEVICE_ENGINE_ERROR")
        assertEquals("B3",f.session.state.reply?.memo); assertTrue(f.session.state.error.contains("화면의 답변"))
    }
    @Test fun synthTimeoutAndLateDoneAreSafe() {
        val f=Fixture(); f.listening().events.result("B3",true); val o=f.outputs.single()
        f.timer.fire(); o.events.ready(); o.events.done()
        assertEquals(VoiceStage.ERROR,f.session.state.stage); assertTrue(o.texts.isEmpty())
    }
    @Test fun permissionFailureDoesNotExecuteCommands() {
        val f=Fixture(); val i=f.listening(); i.events.failed("PERMISSION_DENIED"); i.events.result("B3",true)
        assertEquals(0,f.answers); assertTrue(f.session.state.error.contains("권한"))
    }
    @Test fun emptyFinalAndFactoryFailureAreRecoverable() {
        val f=Fixture(); f.listening().events.result(" ",true); assertEquals(0,f.answers)
        val session=VoiceSession({throw IllegalStateException()}, {Output()},Timer(),{VoiceCommands.Reply("unused")},{})
        session.listen(); assertEquals(VoiceStage.ERROR,session.state.stage)
    }
    @Test fun cancellingSynthesisStopsAudioAndIgnoresLateCallbacks() {
        val f=Fixture(); f.listening().events.result("B3",true); val o=f.outputs.single(); o.events.ready()
        f.session.cancel(); o.events.done(); o.events.failed("AUDIO_ERROR")
        assertEquals(1,o.closes); assertEquals(VoiceStage.IDLE,f.session.state.stage)
    }
}
