package com.jj.jarvis

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream

/**
 * Ponte com o Shizuku: da ao JJ poderes de "adb shell" (tocar na tela, abrir apps, dar permissoes) sem precisar de PC.
 * Fluxo: o usuario liga o Shizuku (depuracao sem fio), o JJ pede permissao UMA vez e depois configura o resto sozinho.
 */
object Shizu {
    const val REQ = 4242
    const val PKG = "moe.shizuku.privileged.api"
    @Volatile var lastReport = ""

    fun installed(c: Context): Boolean = try { c.packageManager.getPackageInfo(PKG, 0); true } catch (e: Exception) { false }
    fun running(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
    fun granted(): Boolean = try { running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }
    fun ready(): Boolean = granted()

    fun request() {
        try {
            if (running() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(REQ)
        } catch (e: Throwable) {}
    }

    /** Aspas simples seguras para o shell. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun proc(cmd: String): Process? = try {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    } catch (e: Throwable) { null }

    /** Executa um comando como shell. Devolve (codigo de saida, texto). */
    fun run(cmd: String, timeoutSec: Long = 20): Pair<Int, String> {
        if (!granted()) return Pair(-1, "Shizuku nao esta pronto")
        val p = proc(cmd) ?: return Pair(-1, "Shizuku indisponivel nesta versao")
        val out = StringBuilder()
        val t1 = Thread { try { p.inputStream.bufferedReader().forEachLine { if (out.length < 8000) out.append(it).append('\n') } } catch (e: Exception) {} }
        val t2 = Thread { try { p.errorStream.bufferedReader().forEachLine { if (out.length < 8000) out.append(it).append('\n') } } catch (e: Exception) {} }
        t1.start()
        t2.start()
        var code = -1
        val w = Thread { try { code = p.waitFor() } catch (e: Exception) {} }
        w.start()
        w.join(timeoutSec * 1000)
        if (w.isAlive) {
            try { p.destroy() } catch (e: Exception) {}
            return Pair(-2, "timeout apos ${timeoutSec}s")
        }
        t1.join(500)
        t2.join(500)
        return Pair(code, out.toString().trim())
    }

    /** Saida binaria (ex.: screencap -p). */
    fun bytes(cmd: String, timeoutSec: Long = 15): ByteArray? {
        if (!granted()) return null
        val p = proc(cmd) ?: return null
        val bo = ByteArrayOutputStream()
        val t = Thread { try { p.inputStream.copyTo(bo) } catch (e: Exception) {} }
        val d = Thread { try { p.errorStream.copyTo(ByteArrayOutputStream()) } catch (e: Exception) {} }
        t.start()
        d.start()
        t.join(timeoutSec * 1000)
        if (t.isAlive) {
            try { p.destroy() } catch (e: Exception) {}
            return null
        }
        return bo.toByteArray()
    }

    /** Da ao JJ todas as permissoes de uma vez (o que antes eram ~10 toques em configuracoes). */
    fun setupAll(c: Context): String {
        if (!granted()) return "Shizuku nao esta pronto: abra o Shizuku, inicie o servico e toque em Pedir."
        val pkg = c.packageName
        val log = StringBuilder()
        fun step(label: String, cmd: String) {
            val (code, out) = run(cmd)
            log.append(if (code == 0) "ok     " else "FALHOU ").append(label)
            if (code != 0 && out.isNotEmpty()) log.append(": ").append(out.lines().first().take(90))
            log.append('\n')
        }
        val perms = mutableListOf("RECORD_AUDIO", "CAMERA", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
            "READ_CONTACTS", "CALL_PHONE", "SEND_SMS", "WRITE_SECURE_SETTINGS")
        if (Build.VERSION.SDK_INT >= 33) perms.add("POST_NOTIFICATIONS")
        for (p in perms) step("permissao $p", "pm grant $pkg android.permission.$p")
        step("sobrepor outros apps", "appops set $pkg SYSTEM_ALERT_WINDOW allow")
        step("configuracoes restritas liberadas", "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        step("bateria sem restricao", "dumpsys deviceidle whitelist +$pkg")
        step("rodar em segundo plano", "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        step("app sempre ativo", "am set-standby-bucket $pkg active")
        step("acesso as notificacoes", "cmd notification allow_listener $pkg/$pkg.JJNotifListener")
        // acessibilidade: soma o JJ a lista atual
        val id = "$pkg/$pkg.JJAccessibility"
        val cur = run("settings get secure enabled_accessibility_services").second.trim()
        val list = if (cur.isEmpty() || cur == "null") emptyList() else cur.split(':').filter { it.isNotEmpty() }
        if (!list.any { it.equals(id, true) }) {
            step("acessibilidade", "settings put secure enabled_accessibility_services " + q((list + id).joinToString(":")))
        } else {
            log.append("ok     acessibilidade ja estava na lista\n")
        }
        step("acessibilidade ligada", "settings put secure accessibility_enabled 1")
        val brand = Build.MANUFACTURER.lowercase()
        if (brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) {
            step("Xiaomi: pop-up em segundo plano (tentativa)", "appops set $pkg 10021 allow")
            step("Xiaomi: tela de bloqueio (tentativa)", "appops set $pkg 10020 allow")
        }
        PhoneService.prefs(c).edit().putBoolean("shz_setup", true).apply()
        return log.toString().trim()
    }
}
