package com.hopon.car.voice

import org.junit.Assert.*
import org.junit.Test

class WakeWordControllerTest {
    private class Engine:WakeDetector {
        lateinit var ready:()->Unit;lateinit var hit:()->Unit;lateinit var error:()->Unit
        var stopped:(()->Unit)?=null
        override fun start(ready:()->Unit,detected:()->Unit,failed:()->Unit){this.ready=ready;hit=detected;error=failed}
        override fun stop(completed:()->Unit){stopped=completed}
    }
    @Test fun microphoneRequiresOptInAndForegroundEligibility(){
        val engines=mutableListOf<Engine>();val c=WakeWordController({Engine().also{engines+=it}},{},{})
        c.update(false,true);c.update(true,false);assertTrue(engines.isEmpty())
        c.update(true,true);assertEquals(1,engines.size);engines[0].ready();assertEquals(WakeState.LISTENING,c.state)
        c.update(true,false);assertNotNull(engines[0].stopped);assertEquals(WakeState.PAUSED,c.state)
    }
    @Test fun dialogWaitsUntilCaptureReallyClosesEvenWithRepeatedPause(){
        val e=Engine();val c=WakeWordController({e},{},{})
        c.update(true,true);c.update(true,false)
        var dialogOpened=false;c.pause{dialogOpened=true}
        assertFalse(dialogOpened);e.stopped!!.invoke();assertTrue(dialogOpened)
    }
    @Test fun staleAndRepeatedDetectionsCannotOpenAnotherDialog(){
        val e=Engine();var calls=0;val c=WakeWordController({e},{calls++},{})
        c.update(true,true);e.ready();e.hit();e.hit();assertEquals(1,calls)
        c.update(false,false);e.hit();assertEquals(1,calls)
    }
    @Test fun lateReadyOrHitAfterLeavingScreenIsIgnored(){
        val e=Engine();var calls=0;val c=WakeWordController({e},{calls++},{})
        c.update(true,true);c.update(true,false);e.ready();e.hit()
        assertEquals(WakeState.PAUSED,c.state);assertEquals(0,calls)
    }
    @Test fun errorDoesNotCauseAnInfiniteMicrophoneRestartLoop(){
        val es=mutableListOf<Engine>();val c=WakeWordController({Engine().also{es+=it}},{},{})
        c.update(true,true);es[0].error();repeat(20){c.update(true,true)}
        assertEquals(1,es.size);assertEquals(WakeState.ERROR,c.state)
        c.update(false,true);c.update(true,true);assertEquals(2,es.size)
    }
    @Test fun restartWaitsForPreviousMicRelease(){
        val es=mutableListOf<Engine>();val c=WakeWordController({Engine().also{es+=it}},{},{})
        c.update(true,true);c.update(true,false);c.update(true,true)
        assertEquals(1,es.size);es[0].stopped!!.invoke();assertEquals(2,es.size)
    }
}
