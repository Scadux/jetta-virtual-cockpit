package com.jetta.virtualcockpit.obd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.jetta.virtualcockpit.R

/**
 * Serviço em primeiro plano que mantém a conexão OBD2 e publica os dados.
 *
 * - Conecta via Bluetooth clássico (SPP) ao adaptador pareado.
 * - Consulta RPM/velocidade a ~4 Hz e os demais parâmetros a ~1 Hz.
 * - Reconecta sozinho com backoff em caso de perda de sinal.
 * - Modo demonstração opcional, sempre sinalizado como simulado.
 *
 * Usa apenas APIs do framework Android (sem dependências externas):
 * a leitura roda numa thread dedicada e os ouvintes são avisados na
 * thread principal via [Handler].
 */
class ObdService : Service() {

    companion object {
        private const val TAG = "ObdService"
        private const val CHANNEL_ID = "obd_connection"
        private const val NOTIFICATION_ID = 1
        private const val FAST_INTERVAL_MS = 250L
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 3000L

        const val ACTION_START = "com.jetta.virtualcockpit.obd.START"
        const val ACTION_STOP = "com.jetta.virtualcockpit.obd.STOP"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
    }

    /** Ouvinte de dados e estado, sempre chamado na thread principal. */
    interface Listener {
        fun onData(data: ObdData)
        fun onStateChanged(state: ConnectionState, detail: String?)
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()

    @Volatile private var currentData: ObdData? = null
    @Volatile private var currentState: ConnectionState = ConnectionState.DISCONNECTED
    @Volatile private var currentDetail: String? = null

    private var worker: Thread? = null
    @Volatile private var stopRequested = false
    private var client: Elm327Client? = null
    @Volatile private var demoMode = false

    inner class LocalBinder : Binder() {
        fun getService(): ObdService = this@ObdService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Aguardando conexão"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopWorker()
                stopSelf()
            }
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address != null) connect(address)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopWorker()
        super.onDestroy()
    }

    // ---- Ouvintes ----

    fun addListener(listener: Listener) {
        synchronized(listeners) { listeners.add(listener) }
        // Entrega o estado atual imediatamente ao novo ouvinte.
        listener.onStateChanged(currentState, currentDetail)
        currentData?.let { listener.onData(it) }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun getState(): ConnectionState = currentState

    fun isDemoMode(): Boolean = demoMode

    private fun publishData(data: ObdData) {
        currentData = data
        val copy = synchronized(listeners) { listeners.toList() }
        mainHandler.post { copy.forEach { it.onData(data) } }
    }

    private fun publishState(state: ConnectionState, detail: String?) {
        currentState = state
        currentDetail = detail
        val copy = synchronized(listeners) { listeners.toList() }
        mainHandler.post { copy.forEach { it.onStateChanged(state, detail) } }
    }

    // ---- Controle ----

    /** Liga/desliga o modo demonstração (valores simulados, sinalizados). */
    fun setDemoMode(enabled: Boolean) {
        if (demoMode == enabled) return
        demoMode = enabled
        stopWorker()
        if (enabled) {
            publishState(ConnectionState.DEMO, "Valores simulados")
            val generator = DemoDataGenerator()
            startWorker {
                while (!stopRequested) {
                    publishData(generator.next())
                    if (!sleepInterruptibly(FAST_INTERVAL_MS)) break
                }
            }
        } else {
            currentData = null
            publishState(ConnectionState.DISCONNECTED, null)
        }
    }

    /** Conecta ao adaptador pelo endereço MAC e inicia a leitura. */
    fun connect(deviceAddress: String) {
        if (demoMode) setDemoMode(false)
        stopWorker()
        startWorker {
            var attempt = 0
            while (!stopRequested && attempt < MAX_RETRIES) {
                attempt++
                try {
                    publishState(
                        ConnectionState.CONNECTING,
                        "Conectando (tentativa $attempt/$MAX_RETRIES)…"
                    )
                    val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    val btAdapter: BluetoothAdapter = btManager.adapter
                        ?: throw IllegalStateException("Bluetooth indisponível")
                    val device = btAdapter.getRemoteDevice(deviceAddress)
                    val elm = Elm327Client(btAdapter)
                    client = elm
                    elm.connect(device)
                    publishState(ConnectionState.CONNECTED, device.name ?: deviceAddress)
                    updateNotification("Conectado: ${device.name ?: deviceAddress}")
                    pollLoop(elm)
                    // pollLoop só retorna se desconectar; tenta reconectar.
                    attempt = 0
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Falha na conexão (tentativa $attempt)", e)
                    if (!stopRequested) {
                        publishState(ConnectionState.ERROR, e.message ?: "Falha na conexão")
                    }
                    try {
                        client?.disconnect()
                    } catch (ignored: Exception) {
                    }
                    client = null
                    if (attempt < MAX_RETRIES && !sleepInterruptibly(RETRY_DELAY_MS)) break
                }
            }
            if (!stopRequested) {
                publishState(ConnectionState.DISCONNECTED, "Não foi possível conectar ao adaptador")
            }
        }
    }

    fun disconnect() {
        stopWorker()
        currentData = null
        publishState(ConnectionState.DISCONNECTED, null)
    }

    /**
     * Lê os DTCs armazenados (somente leitura, nunca apaga).
     * Bloqueante: chamar fora da thread principal.
     */
    fun readDtcs(): List<String> = client?.readDtcs() ?: emptyList()

    // ---- Internos ----

    private fun startWorker(job: () -> Unit) {
        stopRequested = false
        worker = Thread(job, "ObdWorker").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * Para a thread de trabalho. Chamar apenas da thread principal:
     * aguarda a thread terminar (até 2 s) e fecha a conexão.
     */
    private fun stopWorker() {
        stopRequested = true
        val w = worker
        worker = null
        w?.interrupt()
        try {
            // Nunca aguardar a si mesma.
            if (w != null && w != Thread.currentThread()) w.join(2000)
        } catch (ignored: InterruptedException) {
        }
        try {
            client?.disconnect()
        } catch (ignored: Exception) {
        }
        client = null
    }

    /** Dorme podendo ser interrompida; retorna false se interrompida. */
    private fun sleepInterruptibly(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            !stopRequested
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Loop de leitura: RPM e velocidade a cada ciclo (~4 Hz);
     * demais parâmetros a cada 4º ciclo (~1 Hz). Um parâmetro que
     * falha mantém o último valor válido; nunca inventa leitura.
     */
    private fun pollLoop(elm: Elm327Client) {
        var cycle = 0
        var current = ObdData()
        while (!stopRequested && elm.isConnected) {
            try {
                val rpm = elm.readRpm()
                val speed = elm.readSpeed()
                current = current.copy(
                    rpm = rpm ?: current.rpm,
                    speedKmh = speed ?: current.speedKmh
                )
                if (cycle % 4 == 0) {
                    val map = elm.readMap()
                    current = current.copy(
                        coolantC = elm.readCoolant() ?: current.coolantC,
                        intakeC = elm.readIntake() ?: current.intakeC,
                        mapKpa = map ?: current.mapKpa,
                        loadPct = elm.readLoad() ?: current.loadPct,
                        throttlePct = elm.readThrottle() ?: current.throttlePct,
                        voltage = elm.readVoltage() ?: current.voltage,
                        boostBar = ObdData.boostFromMap(map ?: current.mapKpa),
                        timestampMs = System.currentTimeMillis()
                    )
                }
                publishData(current)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Erro no loop de leitura, tentando reconectar", e)
                return
            }
            cycle++
            if (!sleepInterruptibly(FAST_INTERVAL_MS)) return
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.obd_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
