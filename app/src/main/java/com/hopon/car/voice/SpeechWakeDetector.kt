package com.hopon.car.voice

/** On-device STT gate owned by the visible microphone foreground service. Discards all non-wake speech, never executes commands. */
class SpeechWakeDetector(private val factory:()->VoiceInput,private val timer:VoiceTimer,
                         private val now:()->Long=System::currentTimeMillis):WakeDetector {
    private var input:VoiceInput?=null
    private var generation=0
    private var stopped=false
    private var cancel:(()->Unit)?=null
    private var ready:()->Unit={}
    private var detected:()->Unit={}
    private var failed:()->Unit={}
    override fun start(ready:()->Unit,detected:()->Unit,failed:()->Unit){
        this.ready=ready;this.detected=detected;this.failed=failed;cycle()
    }
    private fun dispose(){generation++;cancel?.invoke();cancel=null;val old=input;input=null;runCatching{old?.close()}}
    private fun later(ms:Long,action:()->Unit){
        cancel?.invoke();val token=generation
        cancel=timer.after(ms){if(!stopped&&token==generation)action()}
    }
    private fun again(){dispose();later(500){cycle()}}
    private fun failure(){dispose();stopped=true;failed()}
    private fun cycle(){
        if(stopped)return
        dispose();val token=generation;var listening=false;var startedAt=0L
        later(12000){failure()}
        try {
            val client=factory();input=client
            client.initialize(object:VoiceInput.Events {
                override fun ready(){
                    if(stopped||token!=generation||listening)return
                    listening=true;startedAt=now()
                    runCatching{client.listen();this@SpeechWakeDetector.ready();later(22000){again()}}.onFailure{failure()}
                }
                override fun result(text:String,final:Boolean){
                    if(stopped||token!=generation||!listening||!final)return
                    if(WakePhrase.matches(text)){
                        dispose();later(300){stopped=true;detected()}
                    }else again()
                }
                override fun ended(){if(!stopped&&token==generation&&listening)later(800){again()}}
                override fun failed(code:String){
                    if(stopped||token!=generation)return
                    if(code=="TIMEOUT"&&listening&&now()-startedAt>=5000)again() else failure()
                }
            })
        }catch(_:Exception){failure()}
    }
    override fun stop(completed:()->Unit){stopped=true;dispose();timer.after(300,completed)}
}

object WakePhrase {
    // Microphone-path Pleos output includes "해이무브" for the same Korean wake fixture.
    // Whole final utterance only: "hey mobile", quoted phrases and partial results never trigger.
    fun matches(text:String):Boolean {
        val value=text.lowercase(java.util.Locale.ROOT).trim().trimEnd('.', '!', '?', '。').trim()
        return Regex("^(?:hey\\s+(?:movo|mobo)|(?:헤이|해이)\\s*(?:모보|모브|무브))$").matches(value)
    }
}
