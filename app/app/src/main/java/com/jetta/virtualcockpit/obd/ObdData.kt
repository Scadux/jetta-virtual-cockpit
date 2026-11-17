package com.jetta.virtualcockpit.obd

import org.json.JSONObject

/** Estado da ligação com o adaptador OBD2. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DEMO,
    ERROR
}

/**
 * Leitura atual do veículo. Todos os campos são anuláveis: `null` significa
 * "parâmetro indisponível / ainda não lido" e a interface deve mostrar "--",
 * nunca inventar um valor.
 *
 * [boostBar] é ESTIMADO a partir do MAP: (MAP - pressão atmosférica) / 100.
 * A interface deve marcá-lo com "*" até que uma leitura direta seja validada.
 */
data class ObdData(
    val rpm: Int? = null,
    val speedKmh: Int? = null,
    val coolantC: Int? = null,
    val intakeC: Int? = null,
    val mapKpa: Int? = null,
    val loadPct: Int? = null,
    val throttlePct: Int? = null,
    val voltage: Float? = null,
    val boostBar: Float? = null,
    val isDemo: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
) {
    /** Serializa para JSON (válido também como literal JS) para a WebView. */
    fun toJson(): String = JSONObject().apply {
        put("rpm", rpm ?: JSONObject.NULL)
        put("speedKmh", speedKmh ?: JSONObject.NULL)
        put("coolantC", coolantC ?: JSONObject.NULL)
        put("intakeC", intakeC ?: JSONObject.NULL)
        put("mapKpa", mapKpa ?: JSONObject.NULL)
        put("loadPct", loadPct ?: JSONObject.NULL)
        put("throttlePct", throttlePct ?: JSONObject.NULL)
        put("voltage", voltage ?: JSONObject.NULL)
        put("boostBar", boostBar ?: JSONObject.NULL)
        put("isDemo", isDemo)
    }.toString()

    companion object {
        /** Pressão atmosférica padrão em kPa, usada no cálculo estimado do turbo. */
        const val ATMOSPHERIC_KPA = 101

        fun boostFromMap(mapKpa: Int?): Float? =
            mapKpa?.let { (it - ATMOSPHERIC_KPA) / 100f }
    }
}
