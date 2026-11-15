package com.hopon.car.voice

/** A search draft only. Recognized speech is never an approval or a coordinate. */
object VoiceStopIntent {
    fun query(text: String): String? {
        val clean=text.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (Regex("말고|취소|하지|말아|않|안\\s").containsMatchIn(clean)) return null
        val visit=Regex("^(?:(?:가는\\s*길에|근처에|주변에)\\s*)?(.+?)(?:에)?\\s*(?:들르자|들러 줘|들러줘|들려줘|들르고 싶어)$")
        val add=Regex("^(.+?)(?:을|를)?\\s*경유지로?\\s*추가(?:해 줘|해줘|하자)$")
        val raw=(visit.matchEntire(clean) ?: add.matchEntire(clean))?.groupValues?.get(1)?.trim() ?: return null
        if (raw.length !in 1..40 || !raw.all { it.isLetterOrDigit() || it in " -·" }) return null
        return raw
    }
}
