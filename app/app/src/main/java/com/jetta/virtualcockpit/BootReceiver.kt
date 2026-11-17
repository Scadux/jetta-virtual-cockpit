package com.jetta.virtualcockpit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Abre o app junto com a multimídia quando a opção está ativada.
 * Obs.: em Android 10+ o sistema pode bloquear o início de Activity a partir
 * do boot; nesse caso a multimídia precisará abrir o app (launcher) ou o
 * usuário abre manualmente uma vez.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MainActivity.PREF_AUTOSTART, false)) return
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launch)
    }
}
