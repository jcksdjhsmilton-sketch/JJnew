package com.jj.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Serviço em primeiro plano: mantém o JJ vivo com a tela apagada e fica escutando os comandos do servidor
 * (long-poll em /phone/poll). Cada comando é executado por Phone.exec e o resultado volta por /phone/result.
 */
class PhoneService : Service() {
    companion object {
        @Volatile var running = false
        fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("jj_phone", Context.MODE_PRIVATE)
        fun start(c: Context) {
            val i = Intent(c, PhoneService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.stopService(Intent(c, PhoneService::class.java)) }
    }

    @Volatile private var alive = false
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        if (!alive) {
            alive = true
            running = true
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jj:phone")
            wl?.acquire()
            val t = Thread { loop() }
            t.isDaemon = true
            t.start()
            val g = Thread { guardLoop() }
            g.isDaemon = true
            g.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        running = false
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun goForeground(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("jj_phone", "JJ em segundo plano", NotificationManager.IMPORTANCE_LOW))
        }
        val fl = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(fl), PendingIntent.FLAG_IMMUTABLE)
        val talk = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(fl).putExtra("talk", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "jj_phone") else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_jj)
            .setContentTitle("JJ ativo")
            .setContentText("Pronto para controlar o celular")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(0, "Falar com o JJ", talk).build())
        return try { startForeground(1, b.build()); true } catch (e: Exception) { false }
    }

    /** A cada 20 s confere se a acessibilidade continua viva (ver AccGuard). */
    private fun guardLoop() {
        while (alive) {
            try { AccGuard.check(applicationContext) } catch (e: Exception) {}
            try { Thread.sleep(20000) } catch (e: InterruptedException) { break }
        }
    }

    private fun http(method: String, url: String, tok: String, body: String?, readMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = readMs
        c.setRequestProperty("X-JJ-Token", tok)
        c.setRequestProperty("ngrok-skip-browser-warning", "1")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        if (code >= 400) throw RuntimeException("HTTP $code")
        return text
    }

    private fun loop() {
        var backoff = 2000L
        while (alive) {
            val p = prefs(this)
            val gw = p.getString("gw", "") ?: ""
            val tok = p.getString("tok", "") ?: ""
            if (gw.isEmpty()) { Thread.sleep(4000); continue }
            try {
                val r = http("GET", "$gw/phone/poll?wait=25", tok, null, 40000)
                backoff = 2000L
                val cmd = JSONObject(r).optJSONObject("cmd")
                if (cmd != null) handle(gw, tok, cmd)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                try { Thread.sleep(backoff) } catch (ie: InterruptedException) { break }
                backoff = minOf(backoff * 2, 30000L)
            }
        }
    }

    private fun handle(gw: String, tok: String, cmd: JSONObject) {
        val id = cmd.optString("id")
        val action = cmd.optString("action")
        val args = cmd.optJSONObject("args") ?: JSONObject()
        val res = Phone.exec(applicationContext, action, args)
        val out = JSONObject().put("id", id).put("ok", res.ok).put("text", res.text)
        if (res.image != null) out.put("image", res.image)
        try { http("POST", "$gw/phone/result", tok, out.toString(), 30000) } catch (e: Exception) {}
    }
}
