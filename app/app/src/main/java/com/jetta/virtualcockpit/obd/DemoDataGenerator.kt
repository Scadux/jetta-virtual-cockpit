package com.jetta.virtualcockpit.obd

import kotlin.math.sin
import kotlin.random.Random

/**
 * Gera dados SIMULADOS para demonstração da interface sem o carro.
 * Tudo que sai daqui tem [ObdData.isDemo] = true e a interface deve exibir
 * "MODO DEMONSTRAÇÃO" — nunca apresentar como leitura real.
 */
class DemoDataGenerator {

    private var t = 0.0
    private var speed = 62.0
    private var coolant = 88.0

    fun next(): ObdData {
        t += 0.25
        speed = (speed + Random.nextDouble(-4.0, 4.0)).coerceIn(0.0, 135.0)
        coolant = (coolant + Random.nextDouble(-0.2, 0.2)).coerceIn(82.0, 94.0)
        val rpm = (900 + speed * 22 + sin(t) * 320).toInt().coerceIn(800, 6500)
        val load = (22 + speed * 0.45 + sin(t * 0.7) * 12).toInt().coerceIn(8, 96)
        val map = (38 + load * 1.6).toInt().coerceIn(30, 220)
        return ObdData(
            rpm = rpm,
            speedKmh = speed.toInt(),
            coolantC = coolant.toInt(),
            intakeC = (28 + sin(t * 0.3) * 4).toInt(),
            mapKpa = map,
            loadPct = load,
            throttlePct = (load * 0.8).toInt().coerceIn(0, 100),
            voltage = 13.8f + sin(t * 0.2).toFloat() * 0.3f,
            boostBar = ObdData.boostFromMap(map),
            isDemo = true
        )
    }
}
