package com.jj.jarvis

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONObject

/** Ponte JS (window.JJP) usada pelo painel "Controle do celular" (assets/phone.js). */
class PhoneBridge(private val act: Activity) {
    private fun runtimePerms(): List<String> {
        val l = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    @JavascriptInterface fun setConfig(gw: String, tok: String) {
        val g = gw.trim().trimEnd('/')
        if (g.isEmpty()) return
        PhoneService.prefs(act).edit().putString("gw", g).putString("tok", tok).apply()
    }

    @JavascriptInterface fun requestPerms() {
        val need = runtimePerms().filter { !Phone.has(act, it) }
        if (need.isNotEmpty()) act.runOnUiThread { act.requestPermissions(need.toTypedArray(), 7) }
    }

    /** Devolve "ok" ou "perm" (faltam camera/microfone, necessarios para o servico em segundo plano). */
    @JavascriptInterface fun start(): String {
        if (!Phone.has(act, Manifest.permission.CAMERA) || !Phone.has(act, Manifest.permission.RECORD_AUDIO)) return "perm"
        PhoneService.prefs(act).edit().putBoolean("auto", true).apply()
        act.runOnUiThread { PhoneService.start(act) }
        return "ok"
    }

    @JavascriptInterface fun auto(): Boolean = PhoneService.prefs(act).getBoolean("auto", true)

    @JavascriptInterface fun stop() {
        PhoneService.prefs(act).edit().putBoolean("auto", false).apply()
        PhoneService.stop(act)
    }

    @JavascriptInterface fun openSettings(kind: String) {
        act.runOnUiThread { try { act.startActivity(Phone.settingsIntent(act, kind)) } catch (e: Exception) {} }
    }

    @JavascriptInterface fun test(action: String, text: String): String =
        Phone.exec(act, action, JSONObject().put("text", text)).text

    @JavascriptInterface fun status(): String {
        val pm = act.getSystemService(Context.POWER_SERVICE) as PowerManager
        val o = JSONObject()
        o.put("service", PhoneService.running)
        o.put("acc", JJAccessibility.inst != null)
        o.put("notif", JJNotifListener.inst != null)
        o.put("overlay", Settings.canDrawOverlays(act))
        o.put("battery", pm.isIgnoringBatteryOptimizations(act.packageName))
        o.put("camera", Phone.has(act, Manifest.permission.CAMERA))
        o.put("mic", Phone.has(act, Manifest.permission.RECORD_AUDIO))
        o.put("loc", Phone.has(act, Manifest.permission.ACCESS_FINE_LOCATION))
        o.put("contacts", Phone.has(act, Manifest.permission.READ_CONTACTS))
        o.put("call", Phone.has(act, Manifest.permission.CALL_PHONE))
        o.put("sms", Phone.has(act, Manifest.permission.SEND_SMS))
        return o.toString()
    }
}
