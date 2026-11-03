package com.jj.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Vigia a acessibilidade do JJ. O Android/HyperOS desliga o servico quando o app e fechado a forca ou morto pela economia
 * de bateria. Se ela estava ligada e caiu: com WRITE_SECURE_SETTINGS (dado uma vez por adb) o JJ religa sozinho;
 * sem essa permissao ele avisa por notificacao (toque = abre a tela para religar).
 */
object AccGuard {
    private const val ID = "com.jj.jarvis/com.jj.jarvis.JJAccessibility"
    private const val SECURE = "android.permission.WRITE_SECURE_SETTINGS"
    @Volatile private var lastWarn = 0L

    fun enabledInSettings(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { it.equals(ID, true) }
    }

    fun check(c: Context) {
        val p = PhoneService.prefs(c)
        if (JJAccessibility.inst != null) {
            if (!p.getBoolean("acc_was_on", false)) p.edit().putBoolean("acc_was_on", true).apply()
            return
        }
        if (!p.getBoolean("acc_was_on", false)) return  // nunca foi ligada: nao incomodar
        if (c.checkSelfPermission(SECURE) == PackageManager.PERMISSION_GRANTED && reenable(c)) return
        val now = System.currentTimeMillis()
        if (now - lastWarn > 30 * 60 * 1000L) {
            lastWarn = now
            warn(c)
        }
    }

    private fun reenable(c: Context): Boolean {
        return try {
            val cr = c.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val others = cur.split(':').filter { it.isNotEmpty() && !it.equals(ID, true) }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            Thread.sleep(600)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + ID).joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: Exception) { false }
    }

    private fun warn(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("jj_alert", "Avisos do JJ", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(c, 2, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, "jj_alert") else Notification.Builder(c)
        b.setSmallIcon(R.drawable.ic_jj)
            .setContentTitle("A acessibilidade do JJ desligou")
            .setContentText("Toque, abra JJ Controle e ligue de novo para eu voltar a controlar o celular.")
            .setContentIntent(pi)
            .setAutoCancel(true)
        nm.notify(2, b.build())
    }
}
