package com.hopon.car.ui

/** Presentation navigation only; never advances or rewrites a Journey. */
object ScreenFlow {
    fun afterUpdate(screen:String,previousPhase:String,phase:String):String=when{
        phase=="closed" && previousPhase!="closed"->"history"
        phase=="driving" && screen!="home"->"home"
        phase=="pickup" && screen=="prepare"->"home"
        else->screen
    }
    fun back(screen:String,phase:String,hasJourney:Boolean):String?=when(screen){
        "prepare"->"rooms"
        "rooms"->if(hasJourney && phase!="closed")"home" else null
        "history"->if(phase=="closed")"rooms" else "home"
        "parking"->if(phase=="closed")"history" else "home"
        "home"->if(phase=="closed")"rooms" else null
        else->"home"
    }
}
