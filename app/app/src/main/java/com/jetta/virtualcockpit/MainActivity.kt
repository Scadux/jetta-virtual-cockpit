package com.jetta.virtualcockpit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.text.InputType
import android.widget.EditText
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.Toast
import com.jetta.virtualcockpit.obd.ConnectionState
import com.jetta.virtualcockpit.obd.ObdData
import com.jetta.virtualcockpit.obd.ObdService
import com.jetta.virtualcockpit.update.UpdateChecker

/**
 * Tela única do app: uma WebView em tela cheia com a interface do
 * Virtual Cockpit (assets/index.html, baseada no protótipo v0.1).
 *
 * Esta Activity é responsável por: permissões, escolha do adaptador,
 * ligação com o [ObdService] e envio dos dados ao vivo para a página
 * via `window.updateLiveData(...)` / `window.setConnectionState(...)`.
 *
 * Usa apenas APIs do framework Android (sem dependências externas).
 */
class MainActivity : Activity(), ObdService.Listener {

    companion object {
        const val PREFS_NAME = "jetta_cockpit"
        const val PREF_DEVICE_ADDRESS = "device_address"
        const val PREF_DEVICE_NAME = "device_name"
        const val PREF_AUTOSTART = "autostart"
        const val PREF_DEMO = "demo_mode"

        private const val REQUEST_PERMISSIONS = 1001
    }

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private lateinit var updateChecker: UpdateChecker

    private var obdService: ObdService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val obd = (service as ObdService.LocalBinder).getService()
            obdService = obd
            serviceBound = true
            obd.addListener(this@MainActivity)
            if (prefs.getBoolean(PREF_DEMO, false)) {
                obd.setDemoMode(true)
            } else {
                prefs.getString(PREF_DEVICE_ADDRESS, null)?.let { address ->
                    obd.connect(address)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            obdService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        updateChecker = UpdateChecker(this, prefs)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        setupFullscreen()
        setupWebView()
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        checkPermissionsAndStart()
        // Verificação silenciosa ao abrir: só avisa se houver versão nova.
        checkForUpdates(silent = true)
    }

    override fun onDestroy() {
        obdService?.removeListener(this)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        webView.destroy()
        super.onDestroy()
    }

    // ---- ObdService.Listener (chamado na thread principal) ----

    override fun onData(data: ObdData) {
        pushToWebView("window.updateLiveData(${data.toJson()})")
    }

    override fun onStateChanged(state: ConnectionState, detail: String?) {
        pushToWebView("window.setConnectionState('${state.name}')")
    }

    // ---- Interface / WebView ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(JsBridge(), "AndroidObd")
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    /** Ponte JS -> Kotlin (a página pode chamar `AndroidObd.*`). */
    inner class JsBridge {
        @JavascriptInterface
        fun onModeChanged(mode: String) {
            // Reservado para uso futuro (ex.: métricas por modo).
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { showSettingsDialog() }
        }

        /** Lê os DTCs (somente leitura) e devolve via window.onDtcsResult. */
        @JavascriptInterface
        fun readDtcs() {
            Thread {
                val service = obdService
                val connected = service?.getState() == ConnectionState.CONNECTED
                val codes = if (connected) service?.readDtcs() ?: emptyList() else emptyList()
                val json = org.json.JSONArray(codes).toString()
                runOnUiThread { pushToWebView("window.onDtcsResult($connected, $json)") }
            }.start()
        }

        /** Abre outro app instalado (Waze, Maps, Spotify...). */
        @JavascriptInterface
        fun openApp(packageName: String) {
            runOnUiThread {
                try {
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        startActivity(intent)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "App não instalado",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Não foi possível abrir o app",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun pushToWebView(js: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) webView.evaluateJavascript(js, null)
        }
    }

    // ---- Serviço OBD2 ----

    private fun startObdServiceIfNeeded() {
        val intent = Intent(this, ObdService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ---- Permissões ----

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun checkPermissionsAndStart() {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startObdServiceIfNeeded()
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startObdServiceIfNeeded()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    // ---- Diálogos ----

    private fun showSettingsDialog() {
        val service = obdService
        val connected = service?.getState() == ConnectionState.CONNECTED
        val demoOn = prefs.getBoolean(PREF_DEMO, false)
        val autostartOn = prefs.getBoolean(PREF_AUTOSTART, false)
        val deviceName = prefs.getString(PREF_DEVICE_NAME, null) ?: "nenhum"
        val updateConfigured = updateChecker.updateUrl() != null
        val items = arrayOf(
            "Adaptador: $deviceName",
            if (connected) getString(R.string.disconnect) else getString(R.string.connect),
            "Modo demonstração: ${if (demoOn) "ligado" else "desligado"}",
            "Abrir junto com a multimídia: ${if (autostartOn) "ligado" else "desligado"}",
            "Verificar atualização",
            "Endereço de atualizações: ${if (updateConfigured) "configurado" else "não configurado"}"
        )
        AlertDialog.Builder(this)
            .setTitle("Jetta Virtual Cockpit")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDevicePicker()
                    1 -> if (connected) service?.disconnect() else reconnectSaved()
                    2 -> toggleDemo(!demoOn)
                    3 -> prefs.edit().putBoolean(PREF_AUTOSTART, !autostartOn).apply()
                    4 -> checkForUpdates(silent = false)
                    5 -> showUpdateUrlDialog()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---- Atualização dentro do app ----

    private fun checkForUpdates(silent: Boolean) {
        updateChecker.check { result ->
            when (result) {
                is UpdateChecker.CheckResult.Available -> showUpdateDialog(result.info)
                UpdateChecker.CheckResult.UpToDate ->
                    if (!silent) Toast.makeText(
                        this,
                        "Você já está na versão mais recente",
                        Toast.LENGTH_SHORT
                    ).show()
                UpdateChecker.CheckResult.NotConfigured ->
                    if (!silent) Toast.makeText(
                        this,
                        "Endereço de atualização não configurado",
                        Toast.LENGTH_LONG
                    ).show()
                is UpdateChecker.CheckResult.Failed ->
                    if (!silent) Toast.makeText(
                        this,
                        "Não foi possível verificar a atualização",
                        Toast.LENGTH_LONG
                    ).show()
            }
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val message = StringBuilder("Versão ${info.versionName} disponível.")
        info.notes?.takeIf { it.isNotBlank() }?.let { message.append("\n\n$it") }
        message.append("\n\nA atualização mantém todas as suas configurações.")
        AlertDialog.Builder(this)
            .setTitle("Nova versão disponível")
            .setMessage(message.toString())
            .setPositiveButton("Atualizar agora") { _, _ -> startUpdateDownload(info) }
            .setNegativeButton("Depois", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun startUpdateDownload(info: UpdateChecker.UpdateInfo) {
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Baixando atualização")
            setMessage("Versão ${info.versionName}")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }
        updateChecker.download(
            info,
            onProgress = { pct -> progressDialog.progress = pct }
        ) { file ->
            progressDialog.dismiss()
            if (file == null) {
                Toast.makeText(this, "Falha ao baixar a atualização", Toast.LENGTH_LONG).show()
            } else if (!updateChecker.install(file)) {
                Toast.makeText(
                    this,
                    "Autorize \"instalar apps desconhecidos\" e verifique de novo",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showUpdateUrlDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(updateChecker.updateUrl() ?: "")
            hint = "https://…/version.json"
        }
        AlertDialog.Builder(this)
            .setTitle("Endereço de atualizações")
            .setMessage(
                "Cole o endereço do arquivo version.json que lista a versão " +
                    "mais recente (ex.: um arquivo num repositório GitHub). " +
                    "O app verifica esse endereço ao abrir."
            )
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                updateChecker.setUpdateUrl(input.text.toString())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDevicePicker() {
        val btAdapter = bluetoothAdapter()
        if (btAdapter == null) {
            Toast.makeText(this, R.string.bluetooth_required, Toast.LENGTH_LONG).show()
            return
        }
        val paired = btAdapter.bondedDevices.toList()
        if (paired.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_adapter)
                .setMessage(R.string.no_paired_devices)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val names = paired.map { "${it.name ?: "Sem nome"} (${it.address})" }.toTypedArray()
        val savedAddress = prefs.getString(PREF_DEVICE_ADDRESS, null)
        val checked = paired.indexOfFirst { it.address == savedAddress }.coerceAtLeast(-1)
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_adapter)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val device = paired[which]
                prefs.edit()
                    .putString(PREF_DEVICE_ADDRESS, device.address)
                    .putString(PREF_DEVICE_NAME, device.name ?: device.address)
                    .apply()
                obdService?.connect(device.address)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun reconnectSaved() {
        val address = prefs.getString(PREF_DEVICE_ADDRESS, null)
        if (address == null) {
            showDevicePicker()
        } else {
            obdService?.connect(address)
        }
    }

    private fun toggleDemo(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_DEMO, enabled).apply()
        obdService?.setDemoMode(enabled)
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter
    }
}
