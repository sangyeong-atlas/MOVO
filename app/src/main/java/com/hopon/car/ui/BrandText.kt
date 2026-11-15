package com.hopon.car.ui

/** Display-only branding. Stored names, tokens, identifiers and endpoint URLs stay unchanged. */
object BrandText {
    private val legacy=Regex("https?://\\S+|HopOn Weekend Trip|HopOnDemo[0-9]*|HopOn",RegexOption.IGNORE_CASE)
    fun display(value:String):String=legacy.replace(value){match->
        when {
            match.value.startsWith("http",ignoreCase=true)->match.value
            match.value.equals("HopOn Weekend Trip",ignoreCase=true)->"MOVO 주말 여행"
            match.value.startsWith("HopOnDemo",ignoreCase=true)->"MOVO 운전자"
            else->"MOVO"
        }
    }
}
