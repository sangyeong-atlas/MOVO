package com.hopon.car.vehicle

import android.content.SharedPreferences
import org.json.JSONObject

interface VehicleAdapter {
    val simulated: Boolean
    fun capabilities(): JSONObject
    fun apply(request: JSONObject): JSONObject
}

/** Absolute, durable simulator settings. Repeated IDs never reapply effects. */
class SimulatorVehicleAdapter(private val prefs: SharedPreferences) : VehicleAdapter {
    override val simulated = true
    override fun capabilities() = JSONObject().put("occupancy", false).put("belt", false)
        .put("temperature", true).put("heat", true).put("ventilation", true)

    override fun apply(request: JSONObject): JSONObject {
        val id = request.getString("requestId")
        prefs.getString("effect-$id", null)?.let { return JSONObject(it) }
        val edit = prefs.edit()
        when (request.getString("kind")) {
            "stop" -> edit.putString("simulated-stop-$id", request.getJSONObject("place").toString())
            "climate" -> {
                val feature = request.getString("feature")
                val value = request.getDouble("value")
                val seat = request.getInt("seat")
                require(seat in 1..4 && capabilities().optBoolean(feature)) { "Unsupported seat or capability" }
                require(if (feature == "temperature") value in 16.0..30.0 else value in 0.0..3.0) { "Invalid climate value" }
                edit.putString("simulated-climate-$seat-$feature", value.toString())
            }
            else -> error("Unsupported request")
        }
        val result = JSONObject().put("status", "applied").put("reason", "Simulator applied; no physical vehicle control")
        check(edit.putString("effect-$id", result.toString()).commit()) { "Could not persist simulator result" }
        return result
    }
}
