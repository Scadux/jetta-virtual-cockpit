package com.jetta.virtualcockpit.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Cliente ELM327 sobre Bluetooth clássico (SPP).
 *
 * Fala OBD2 Modo 01 (dados ao vivo) e Modo 03 (códigos de falha).
 * Não apaga códigos (Modo 04) nem executa codificação/adaptação.
 *
 * Todas as operações de I/O são bloqueantes e devem rodar fora da UI thread.
 */
class Elm327Client(private val adapter: BluetoothAdapter) {

    companion object {
        private const val TAG = "Elm327Client"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val DEFAULT_TIMEOUT_MS = 3000L
        private const val PID_TIMEOUT_MS = 2000L
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /**
     * Serializa comando + resposta: o loop de leitura e a leitura de DTCs
     * rodam em threads diferentes e não podem intercalar bytes no socket.
     */
    private val ioLock = Any()

    val isConnected: Boolean
        get() = socket?.isConnected == true

    /**
     * Conecta ao adaptador e executa a inicialização ELM327.
     * @throws Exception se a conexão ou a inicialização falhar.
     */
    @Throws(Exception::class)
    fun connect(device: BluetoothDevice) {
        disconnect()
        adapter.cancelDiscovery()
        val sock = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: Exception) {
            // Fallback para adaptadores/centrais que recusam o UUID SPP padrão.
            Log.w(TAG, "createRfcommSocketToServiceRecord falhou, tentando canal 1", e)
            @SuppressLint("DiscouragedPrivateApi")
            device.javaClass
                .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
        }
        socket = sock
        sock.connect() // bloqueante
        input = sock.inputStream
        output = sock.outputStream
        initialize()
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao fechar socket", e)
        }
        socket = null
        input = null
        output = null
    }

    /** Sequência de inicialização ELM327. */
    private fun initialize() {
        sendCommand("ATZ", 4000L) // reset do adaptador
        Thread.sleep(300)
        sendCommand("ATE0") // eco desligado
        sendCommand("ATL0") // line feeds desligados
        sendCommand("ATS0") // espaços desligados
        sendCommand("ATH0") // cabeçalhos desligados
        sendCommand("ATSP0") // protocolo automático
        // Leitura de aquecimento para a ECU "acordar".
        queryPid("0100")
    }

    /** Envia um comando e lê até o prompt '>' ou o timeout. */
    private fun sendCommand(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String =
        synchronized(ioLock) {
            val out: OutputStream = output ?: throw IllegalStateException("Adaptador não conectado")
            val inp: InputStream = input ?: throw IllegalStateException("Adaptador não conectado")
            // Descarta bytes residuais de respostas anteriores.
            while (inp.available() > 0) inp.read()
            out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            out.flush()
            val sb = StringBuilder()
            val buf = ByteArray(256)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) {
                    val n = inp.read(buf)
                    if (n > 0) {
                        sb.append(String(buf, 0, n, Charsets.US_ASCII))
                        if (sb.contains(">")) break
                    }
                } else {
                    Thread.sleep(10)
                }
            }
            sb.toString()
        }

    /**
     * Extrai os bytes de dados de uma resposta Modo 01 ("41 0C 1A 2B").
     * Retorna null para "NO DATA", "SEARCHING..." sem resposta, erros, etc.
     */
    private fun parseObdResponse(raw: String): List<Int>? {
        var s = raw.replace(" ", "").replace("\r", "").replace("\n", "").replace(">", "")
        val idx = s.indexOf("41")
        if (idx < 0) return null
        s = s.substring(idx)
        if (s.length < 6) return null
        val dataHex = s.substring(4) // remove modo (41) + PID
        val bytes = mutableListOf<Int>()
        var i = 0
        while (i + 1 < dataHex.length) {
            bytes.add(dataHex.substring(i, i + 2).toIntOrNull(16) ?: return null)
            i += 2
        }
        return bytes
    }

    private fun queryPid(pid: String): List<Int>? {
        return try {
            parseObdResponse(sendCommand(pid, PID_TIMEOUT_MS))
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao consultar $pid", e)
            null
        }
    }

    // ---- Modo 01: dados ao vivo (fórmulas OBD2 padrão) ----

    fun readRpm(): Int? =
        queryPid("010C")?.let { if (it.size >= 2) ((it[0] * 256) + it[1]) / 4 else null }

    fun readSpeed(): Int? = queryPid("010D")?.firstOrNull()

    fun readCoolant(): Int? = queryPid("0105")?.firstOrNull()?.minus(40)

    fun readMap(): Int? = queryPid("010B")?.firstOrNull()

    fun readIntake(): Int? = queryPid("010F")?.firstOrNull()?.minus(40)

    fun readLoad(): Int? = queryPid("0104")?.firstOrNull()?.let { it * 100 / 255 }

    fun readThrottle(): Int? = queryPid("0111")?.firstOrNull()?.let { it * 100 / 255 }

    /** Tensão medida pelo próprio adaptador (ATRV). */
    fun readVoltage(): Float? {
        return try {
            val raw = sendCommand("ATRV")
            Regex("[0-9]+\\.?[0-9]*").find(raw)?.value?.toFloatOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao ler tensão", e)
            null
        }
    }

    // ---- Modo 03: códigos de falha (somente leitura) ----

    /** Lê os DTCs armazenados. Retorna lista vazia se não houver códigos. */
    fun readDtcs(): List<String> {
        return try {
            var s = sendCommand("03", 5000L)
                .replace(" ", "").replace("\r", "").replace("\n", "").replace(">", "")
            val idx = s.indexOf("43")
            if (idx < 0) return emptyList()
            s = s.substring(idx + 2)
            val codes = mutableListOf<String>()
            var i = 0
            while (i + 3 < s.length) {
                val b0 = s.substring(i, i + 2).toIntOrNull(16) ?: break
                val b1 = s.substring(i + 2, i + 4).toIntOrNull(16) ?: break
                if (b0 == 0 && b1 == 0) break // preenchimento
                codes.add(decodeDtc(b0, b1))
                i += 4
            }
            codes
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao ler DTCs", e)
            emptyList()
        }
    }

    private fun decodeDtc(b0: Int, b1: Int): String {
        val type = when ((b0 shr 6) and 0x03) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val d1 = (b0 shr 4) and 0x03
        val d2 = b0 and 0x0F
        val d3 = (b1 shr 4) and 0x0F
        val d4 = b1 and 0x0F
        return "$type$d1${d2.toString(16).uppercase()}${d3.toString(16).uppercase()}${d4.toString(16).uppercase()}"
    }
}
