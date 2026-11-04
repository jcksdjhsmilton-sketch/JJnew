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

    /** Pede permissao ao Shizuku (ou abre o Shizuku / a pagina de instalacao). Devolve: ok | not_installed | not_running | asked */
    @JavascriptInterface fun shizukuRequest(): String {
        if (!Shizu.installed(act)) {
            act.runOnUiThread {
                try { act.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
            }
            return "not_installed"
        }
        if (!Shizu.running()) {
            act.runOnUiThread {
                try {
                    val li = act.packageManager.getLaunchIntentForPackage(Shizu.PKG)
                    if (li != null) act.startActivity(li)
                } catch (e: Exception) {}
            }
            return "not_running"
        }
        if (Shizu.granted()) return "ok"
        Shizu.request()
        return "asked"
    }

    /** Configura TODAS as permissoes via Shizuku em segundo plano; o relatorio fica em shizukuReport(). */
    @JavascriptInterface fun shizukuSetup() {
        if (Shizu.lastReport == "executando...") return
        Shizu.lastReport = "executando..."
        val t = Thread {
            Shizu.lastReport = try { Shizu.setupAll(act) } catch (e: Throwable) { "erro: ${e.message}" }
            act.runOnUiThread { if (Phone.has(act, Manifest.permission.CAMERA) && Phone.has(act, Manifest.permission.RECORD_AUDIO)) PhoneService.start(act) }
        }
        t.isDaemon = true
        t.start()
    }

    @JavascriptInterface fun shizukuReport(): String = Shizu.lastReport
    @JavascriptInterface fun shizukuSetupDone(): Boolean = PhoneService.prefs(act).getBoolean("shz_setup", false)

    @JavascriptInterface fun auto(): Boolean = PhoneService.prefs(act).getBoolean("auto", true)

    @JavascriptInterface fun stop() {
        PhoneService.prefs(act).edit().putBoolean("auto", false).apply()
        PhoneService.stop(act)
    }

    @JavascriptInterface fun openSettings(kind: String) {
        act.runOnUiThread {
            try { act.startActivity(Phone.settingsIntent(act, kind)) } catch (e: Exception) {
                try { act.startActivity(Phone.settingsIntent(act, "app")) } catch (e2: Exception) {}  // aparelho sem essa tela (nao e Xiaomi)
            }
        }
    }

    @JavascriptInterface fun test(action: String, text: String): String =
        Phone.exec(act, action, JSONObject().put("text", text)).text

    @JavascriptInterface fun status(): String {
        val pm = act.getSystemService(Context.POWER_SERVICE) as PowerManager
        val o = JSONObject()
        o.put("service", PhoneService.running)
        o.put("acc", JJAccessibility.inst != null)
        o.put("acc_dead", AccGuard.enabledInSettings(act) && JJAccessibility.inst == null)
        o.put("notif", JJNotifListener.inst != null)
        o.put("overlay", Settings.canDrawOverlays(act))
        o.put("battery", pm.isIgnoringBatteryOptimizations(act.packageName))
        o.put("shz_installed", Shizu.installed(act))
        o.put("shz_running", Shizu.running())
        o.put("shz_granted", Shizu.granted())
        o.put("camera", Phone.has(act, Manifest.permission.CAMERA))
        o.put("mic", Phone.has(act, Manifest.permission.RECORD_AUDIO))
        o.put("loc", Phone.has(act, Manifest.permission.ACCESS_FINE_LOCATION))
        o.put("contacts", Phone.has(act, Manifest.permission.READ_CONTACTS))
        o.put("call", Phone.has(act, Manifest.permission.CALL_PHONE))
        o.put("sms", Phone.has(act, Manifest.permission.SEND_SMS))
        return o.toString()
    }
}
