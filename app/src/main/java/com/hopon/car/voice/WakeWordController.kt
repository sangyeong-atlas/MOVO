package com.hopon.car.voice

interface WakeDetector {
    fun start(ready: () -> Unit, detected: () -> Unit, failed: () -> Unit)
    /** Completion runs only after capture and model resources have been released. */
    fun stop(completed: () -> Unit)
}
enum class WakeState { OFF, STARTING, LISTENING, PAUSED, TRIGGERED, ERROR }

/** Main-thread lifecycle owner. No command text is ever produced by a wake detection. */
class WakeWordController(private val factory: () -> WakeDetector,
                         private val heard: () -> Unit, private val changed: (WakeState) -> Unit) {
    var state=WakeState.OFF; private set
    private var enabled=false
    private var available=false
    private var detector:WakeDetector?=null
    private var generation=0
    private var stopping=false
    private val stoppedCallbacks=mutableListOf<()->Unit>()
    private var blockedByError=false
    private fun publish(next:WakeState){if(state!=next){state=next;changed(next)}}
    fun update(enabled:Boolean,available:Boolean){
        if(!enabled)blockedByError=false
        this.enabled=enabled;this.available=available
        if(!enabled||!available){pause();return}
        resume()
    }
    private fun resume(){
        if(!enabled||!available||stopping||detector!=null||blockedByError)return
        val token=++generation
        publish(WakeState.STARTING)
        try {
            val next=factory();detector=next
            next.start(ready={if(token==generation)publish(WakeState.LISTENING)},detected={
                if(token==generation&&enabled&&available){detector=null;generation++;publish(WakeState.TRIGGERED);heard()}
            },failed={
                if(token==generation){detector=null;generation++;blockedByError=true;publish(WakeState.ERROR)}
            })
        }catch(_:Exception){detector=null;blockedByError=true;publish(WakeState.ERROR)}
    }
    fun pause(completed:()->Unit={}){
        generation++
        val old=detector;detector=null
        publish(if(!enabled)WakeState.OFF else if(blockedByError)WakeState.ERROR else WakeState.PAUSED)
        stoppedCallbacks += completed
        if(stopping)return
        fun finish(){val callbacks=stoppedCallbacks.toList();stoppedCallbacks.clear();callbacks.forEach{it()}}
        if(old==null){finish();return}
        stopping=true
        old.stop { stopping=false;finish();resume() }
    }
}
