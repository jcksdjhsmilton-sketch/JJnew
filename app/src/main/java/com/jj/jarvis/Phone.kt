package com.jj.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import org.json.JSONObject
import java.text.Normalizer

class Res(val ok: Boolean, val text: String, val image: String? = null)

/** Executa os comandos que o servidor (IA) manda para o celular. */
object Phone {
    private const val NO_ACC = "Acessibilidade desativada. Ative em: app JJ > icone do celular > Acessibilidade."

    fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun launch(c: Context, i: Intent): String {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { c.startActivity(i); "ok" } catch (e: Exception) { "erro ao abrir: ${e.message}" }
    }

    fun settingsIntent(c: Context, kind: String): Intent = when (kind.lowercase()) {
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "notification" -> Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.packageName))
        "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
        "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))
    }

    fun exec(c: Context, action: String, a: JSONObject): Res {
        val text = a.optString("text", "")
        val extra = a.optString("extra", "")
        return try {
            when (action) {
                "status" -> Res(true, status(c))
                "open_app" -> openApp(c, text)
                "open_url" -> openUrl(c, text)
                "play_music" -> playMusic(c, text, extra)
                "media" -> media(c, text)
                "volume" -> volume(c, text)
                "camera" -> Cam.shot(c, text.lowercase().startsWith("front") || text.lowercase().startsWith("fron"))
                "screenshot" -> {
                    val acc = JJAccessibility.inst ?: return Res(false, NO_ACC)
                    val bm = acc.screenshot() ?: return Res(false, "captura de tela indisponivel (precisa Android 11+)")
                    Res(true, "captura da tela", Img.fromBitmap(bm))
                }
                "read_screen" -> Res(true, (JJAccessibility.inst ?: return Res(false, NO_ACC)).readScreen())
                "tap" -> {
                    val acc = JJAccessibility.inst ?: return Res(false, NO_ACC)
                    val x = a.optInt("x", 0)
                    val y = a.optInt("y", 0)
                    val ok = if (text.isNotEmpty()) acc.tapText(text) else acc.tapXY(x.toFloat(), y.toFloat())
                    Res(ok, if (ok) "toquei" else "nao consegui tocar (use read_screen para ver os rotulos)")
                }
                "swipe" -> {
                    val acc = JJAccessibility.inst ?: return Res(false, NO_ACC)
                    val ok = acc.swipe(text)
                    Res(ok, if (ok) "rolei $text" else "gesto falhou")
                }
                "type" -> {
                    val acc = JJAccessibility.inst ?: return Res(false, NO_ACC)
                    val ok = acc.typeText(text)
                    if (ok && extra == "submit") acc.submit()
                    Res(ok, if (ok) "digitei" else "nenhum campo de texto em foco")
                }
                "key" -> {
                    val acc = JJAccessibility.inst ?: return Res(false, NO_ACC)
                    val ok = acc.globalKey(text)
                    Res(ok, if (ok) "ok: $text" else "tecla nao suportada: $text")
                }
                "notifications" -> Res(true, (JJNotifListener.inst ?: return Res(false, "Acesso as notificacoes desativado. Ative no painel do JJ.")).dump())
                "call" -> call(c, text)
                "sms" -> sms(c, text, extra)
                "flashlight" -> torch(c, text.lowercase() != "off")
                "alarm" -> alarm(c, text, extra)
                "timer" -> timer(c, text)
                "location" -> location(c)
                "clipboard_set" -> {
                    (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("JJ", text))
                    Res(true, "copiado")
                }
                "clipboard_get" -> {
                    val t = (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(c)?.toString()
                    Res(true, t ?: "(vazio ou bloqueado em segundo plano)")
                }
                "settings" -> {
                    val r = launch(c, settingsIntent(c, text))
                    Res(r == "ok", if (r == "ok") "abri configuracoes: $text" else r)
                }
                else -> Res(false, "acao desconhecida: $action")
            }
        } catch (e: Exception) {
            Res(false, "erro ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun status(c: Context): String {
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        fun f(b: Boolean) = if (b) "ok" else "FALTA"
        return "bateria $bat%${if (bm.isCharging) " (carregando)" else ""}; tela ${if (pm.isInteractive) "ligada" else "apagada"}; " +
            "app na frente: ${JJAccessibility.fg}; acessibilidade ${f(JJAccessibility.inst != null)}; " +
            "notificacoes ${f(JJNotifListener.inst != null)}; sobreposicao ${f(Settings.canDrawOverlays(c))}; " +
            "camera ${f(has(c, Manifest.permission.CAMERA))}; contatos ${f(has(c, Manifest.permission.READ_CONTACTS))}"
    }

    private fun openApp(c: Context, name: String): Res {
        val pm = c.packageManager
        val q = norm(name)
        if (q.isEmpty()) return Res(false, "diga o nome do app")
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var best: String? = null
        var bestLabel = ""
        for (r in list) {
            val label = r.loadLabel(pm).toString()
            val n = norm(label)
            if (n == q) { best = r.activityInfo.packageName; bestLabel = label; break }
            if (best == null && n.isNotEmpty() && (n.contains(q) || q.contains(n))) { best = r.activityInfo.packageName; bestLabel = label }
        }
        val pkg = best ?: return Res(false, "app nao encontrado: $name")
        val li = pm.getLaunchIntentForPackage(pkg) ?: return Res(false, "sem tela de abertura: $bestLabel")
        val r = launch(c, li)
        return Res(r == "ok", if (r == "ok") "abri $bestLabel" else r)
    }

    private fun openUrl(c: Context, url: String): Res {
        val u = if (url.contains("://")) url else "https://$url"
        val r = launch(c, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        return Res(r == "ok", if (r == "ok") "abri $u" else r)
    }

    private fun key(c: Context, code: Int) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun playMusic(c: Context, q: String, pkg: String): Res {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        i.putExtra(SearchManager.QUERY, q)
        if (pkg.isNotEmpty()) i.setPackage(pkg)
        if (i.resolveActivity(c.packageManager) == null) {
            val r = launch(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + Uri.encode(q))))
            return Res(r == "ok", "nenhum app de musica aceitou a busca; abri a busca web de \"$q\"")
        }
        val r = launch(c, i)
        if (r != "ok") return Res(false, r)
        Thread.sleep(3500)
        key(c, KeyEvent.KEYCODE_MEDIA_PLAY)
        return Res(true, "tocando \"$q\"")
    }

    private fun media(c: Context, what: String): Res {
        val code = when (what.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return Res(false, "use play, pause, playpause, next, previous ou stop")
        }
        key(c, code)
        return Res(true, "midia: $what")
    }

    private fun volume(c: Context, v: String): Res {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        when (v.lowercase()) {
            "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            "unmute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            else -> {
                val p = v.trim().toIntOrNull() ?: return Res(false, "use up, down, mute, unmute ou 0 a 100")
                am.setStreamVolume(AudioManager.STREAM_MUSIC, p.coerceIn(0, 100) * max / 100, 0)
            }
        }
        return Res(true, "volume em ${am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}%")
    }

    private fun torch(c: Context, on: Boolean): Res {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (id in cm.cameraIdList) {
            if (cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                cm.setTorchMode(id, on)
                return Res(true, if (on) "lanterna ligada" else "lanterna desligada")
            }
        }
        return Res(false, "este aparelho nao tem lanterna")
    }

    private fun contactNumber(c: Context, name: String): String? {
        if (!has(c, Manifest.permission.READ_CONTACTS)) return null
        val cur = c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, null
        ) ?: return null
        val q = norm(name)
        cur.use {
            while (it.moveToNext()) {
                if (norm(it.getString(1) ?: "").contains(q)) return it.getString(0)
            }
        }
        return null
    }

    private fun resolveNumber(c: Context, t: String): String? =
        if (t.any { it.isDigit() } && t.none { it.isLetter() }) t else contactNumber(c, t)

    private fun call(c: Context, who: String): Res {
        val n = resolveNumber(c, who) ?: return Res(false, "nao achei numero para $who (permita Contatos)")
        val act = if (has(c, Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val r = launch(c, Intent(act, Uri.parse("tel:" + Uri.encode(n))))
        return Res(r == "ok", if (r == "ok") "ligando para $who" else r)
    }

    @Suppress("DEPRECATION")
    private fun sms(c: Context, who: String, msg: String): Res {
        if (!has(c, Manifest.permission.SEND_SMS)) return Res(false, "sem permissao de SMS")
        val n = resolveNumber(c, who) ?: return Res(false, "nao achei numero para $who")
        val sm = SmsManager.getDefault()
        sm.sendMultipartTextMessage(n, null, sm.divideMessage(msg), null, null)
        return Res(true, "SMS enviado para $who")
    }

    private fun alarm(c: Context, hhmm: String, label: String): Res {
        val p = hhmm.split(":")
        val h = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return Res(false, "use HH:MM")
        val m = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val r = launch(c, i)
        return Res(r == "ok", if (r == "ok") "alarme as %02d:%02d".format(h, m) else r)
    }

    private fun timer(c: Context, secs: String): Res {
        val s = secs.trim().toIntOrNull() ?: return Res(false, "informe os segundos")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val r = launch(c, i)
        return Res(r == "ok", if (r == "ok") "timer de $s s" else r)
    }

    @SuppressLint("MissingPermission")
    private fun location(c: Context): Res {
        if (!has(c, Manifest.permission.ACCESS_FINE_LOCATION) && !has(c, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return Res(false, "sem permissao de localizacao")
        }
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null }
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        val b = best ?: return Res(false, "sem localizacao disponivel")
        return Res(true, "lat ${b.latitude}, lon ${b.longitude}")
    }
}
