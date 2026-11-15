package com.hopon.car.voice

interface VoiceInput {
    interface Events {
        fun ready()
        fun result(text: String, final: Boolean)
        fun ended()
        fun failed(code: String)
    }
    fun initialize(events: Events)
    fun listen()
    fun close()
}
interface VoiceOutput {
    interface Events { fun ready(); fun done(); fun failed(code: String) }
    fun initialize(events: Events)
    fun speak(text: String)
    fun close()
}
fun interface VoiceTimer { fun after(delayMs: Long, action: () -> Unit): () -> Unit }
enum class VoiceStage { IDLE, CONNECTING, LISTENING, FINISHING, SPEAKING, DONE, ERROR }
data class VoiceState(val stage: VoiceStage = VoiceStage.IDLE, val transcript: String = "",
                      val reply: VoiceCommands.Reply? = null, val error: String = "") {
    val active get() = stage in setOf(VoiceStage.CONNECTING, VoiceStage.LISTENING, VoiceStage.FINISHING, VoiceStage.SPEAKING)
}

/** Main-thread owner. Each operation gets a fresh SDK client and a generation, including retries. */
class VoiceSession(private val inputFactory: () -> VoiceInput, private val outputFactory: () -> VoiceOutput,
                   private val timer: VoiceTimer, private val answer: (String) -> VoiceCommands.Reply,
                   private val changed: (VoiceState) -> Unit,
                   private val audio: VoiceAudio = SilentVoiceAudio) {
    var state = VoiceState(); private set
    private var generation = 0
    private var audioGeneration = 0
    private var closed = false
    private var input: VoiceInput? = null
    private var output: VoiceOutput? = null
    private var cancelTimer: (() -> Unit)? = null
    private fun publish(value: VoiceState) { state = value; changed(value) }
    private fun dispose(releaseAudio: Boolean = true) {
        generation++
        cancelTimer?.invoke(); cancelTimer = null
        val previousInput = input; val previousOutput = output
        input = null; output = null
        runCatching { previousInput?.close() }; runCatching { previousOutput?.close() }
        if (releaseAudio) { audioGeneration++; audio.release() }
    }
    private fun deadline(token: Int, ms: Long, failure: () -> Unit) {
        cancelTimer?.invoke()
        cancelTimer = timer.after(ms) { if (!closed && token == generation) failure() }
    }
    private fun fail(code: String, synthesis: Boolean = false) {
        val wasListening = state.stage == VoiceStage.LISTENING
        val detail = when(code) {
            "AUDIO_BUSY" -> "통화나 다른 오디오가 사용 중이에요. 끝난 뒤 다시 눌러 주세요."
            "AUDIO_INTERRUPTED" -> "다른 오디오 안내로 음성을 중단했어요. 다시 눌러 말해 주세요."
            "PERMISSION_DENIED" -> "음성 서비스 권한이 없어 사용할 수 없어요."
            "ON_DEVICE_ENGINE_ERROR", "NOT_SUPPORTED_API", "UNAVAILABLE_LANGUAGE" -> "이 차량 환경에서 한국어 음성 엔진을 사용할 수 없어요."
            "MIC_ERROR", "AUDIO_ERROR" -> "마이크 또는 차량 오디오를 사용할 수 없어요."
            "TIMEOUT" -> "음성 서비스 응답 시간이 초과됐어요. 다시 시도해 주세요."
            "NO_RESULT" -> "말씀을 인식하지 못했어요. 다시 눌러 말해 주세요."
            else -> "음성 서비스를 연결하지 못했어요. 다시 시도해 주세요."
        }
        val finish = { publish(state.copy(stage = VoiceStage.ERROR, error = if(synthesis) "음성 읽기를 사용할 수 없어요. 화면의 답변을 확인해 주세요. $detail" else detail)) }
        if (wasListening && code !in setOf("AUDIO_BUSY", "AUDIO_INTERRUPTED")) finishRecognition(finish)
        else { dispose(); finish() }
    }
    private fun finishRecognition(completed: () -> Unit) {
        dispose(releaseAudio = false) // close the mic before playing the end cue
        publish(state.copy(stage = VoiceStage.FINISHING))
        val token = generation
        var finished = false
        val finish = {
            if (!closed && token == generation && !finished) {
                finished = true; cancelTimer?.invoke(); cancelTimer = null
                audioGeneration++; audio.release(); completed()
            }
        }
        deadline(token, 1_500) { finish() }
        runCatching { audio.cue(false) { finish() } }.onFailure { finish() }
    }
    fun listen() {
        if(closed || state.active) return
        dispose()
        publish(VoiceState(VoiceStage.CONNECTING))
        val token = generation
        deadline(token, 12_000) { fail("TIMEOUT") }
        runCatching {
            val client = inputFactory(); input = client
            var readyHandled = false
            client.initialize(object : VoiceInput.Events {
                override fun ready() {
                    if(closed || token != generation || readyHandled || state.stage != VoiceStage.CONNECTING) return
                    readyHandled = true
                    val audioToken = ++audioGeneration
                    val granted = runCatching { audio.acquire(true) {
                        if (!closed && audioToken == audioGeneration) fail("AUDIO_INTERRUPTED")
                    } }.getOrDefault(false)
                    if (closed || token != generation) return
                    if (!granted) { fail("AUDIO_BUSY"); return }
                    runCatching { audio.cue(true) {
                        if (!closed && token == generation && state.stage == VoiceStage.CONNECTING) {
                            publish(state.copy(stage = VoiceStage.LISTENING))
                            deadline(token, 20_000) { fail("TIMEOUT") }
                            runCatching { client.listen() }.onFailure { fail("UNAVAILABLE") }
                        }
                    } }.onFailure { fail("AUDIO_ERROR") }
                }
                override fun result(text: String, final: Boolean) {
                    if(closed || token != generation || state.stage != VoiceStage.LISTENING) return
                    publish(state.copy(transcript = text.take(500)))
                    if(!final) return
                    if(text.isBlank()) { fail("NO_RESULT"); return }
                    finishRecognition {
                        val reply = runCatching { answer(text) }.getOrElse { VoiceCommands.Reply("여정 정보를 확인할 수 없어요. 화면에서 다시 확인해 주세요.") }
                        publish(state.copy(stage = VoiceStage.DONE, reply = reply))
                        speak(reply.text)
                    }
                }
                override fun ended() {
                    if(closed || token != generation || state.stage != VoiceStage.LISTENING) return
                    // Some services deliver the final result after the end event. Never execute an interim result.
                    deadline(token, 800) { fail("NO_RESULT") }
                }
                override fun failed(code: String) { if(!closed && token == generation) fail(code) }
            })
        }.onFailure { fail("UNAVAILABLE") }
    }
    private fun speak(text: String) {
        if(closed) return
        val token = generation
        publish(state.copy(stage = VoiceStage.SPEAKING))
        deadline(token, 20_000) { fail("TIMEOUT", true) }
        runCatching {
            val client = outputFactory(); output = client
            var started = false
            client.initialize(object : VoiceOutput.Events {
                override fun ready() {
                    if(closed || token != generation || started) return
                    started = true
                    runCatching { client.speak(text) }.onFailure { fail("UNAVAILABLE", true) }
                }
                override fun done() {
                    if(closed || token != generation || !started) return
                    dispose(); publish(state.copy(stage = VoiceStage.DONE))
                }
                override fun failed(code: String) { if(!closed && token == generation) fail(code, true) }
            })
        }.onFailure { fail("UNAVAILABLE", true) }
    }
    fun cancel() {
        if(closed) return
        dispose(); publish(VoiceState())
    }
    fun close() { if(!closed) { closed = true; dispose() } }
}
