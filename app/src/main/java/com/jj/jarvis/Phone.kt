package com.jj.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Res(val ok: Boolean, val text: String, val image: String? = null)

/** Executa os comandos que o servidor (IA) manda para o celular. */
object Phone {
    /** true enquanto a tela do JJ esta na frente (MainActivity.onWindowFocusChanged) */
    @Volatile var jjFront = false

    private const val NO_ACC = "Acessibilidade desativada. Ative em: app JJ > icone do celular > Acessibilidade."

    fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun installed(c: Context, pkg: String): Boolean =
        try { c.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    /** Evita a telinha "Abrir com...": fixa o app que vai tratar o link/acao. */
    private fun pinHandler(c: Context, i: Intent) {
        val pm = c.packageManager
        val d = i.dataString ?: ""
        if ((d.contains("youtube.com") || d.contains("youtu.be")) && installed(c, "com.google.android.youtube")) {
            i.setPackage("com.google.android.youtube")
            return
        }
        val ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        if (ri == null || ri.activityInfo.name.contains("ResolverActivity")) {
            val list = pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
            val pick = list.firstOrNull { it.activityInfo.packageName == "com.android.chrome" } ?: list.firstOrNull()
            if (pick != null) i.setPackage(pick.activityInfo.packageName)
        }
    }

    private fun launch(c: Context, i: Intent): String {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.action == Intent.ACTION_VIEW && i.getPackage() == null) pinHandler(c, i)
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
        "autostart" -> Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        "powerkeeper" -> Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", c.packageName).putExtra("package_label", "JJ")
        "xiaomi_perms" -> Intent("miui.intent.action.APP_PERM_EDITOR")
            .setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"))
            .putExtra("extra_pkgname", c.packageName)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))
    }

    @Volatile private var fastMode = false

    /** Comando "input" do shell, apontando para a tela virtual quando ela esta em uso. */
    private fun inp(): String = if (JJAccessibility.targetDisplay > 0) "input -d ${JJAccessibility.targetDisplay}" else "input"
    private val KEYEV = mapOf("back" to "KEYCODE_BACK", "home" to "KEYCODE_HOME", "recents" to "KEYCODE_APP_SWITCH",
        "lock" to "KEYCODE_SLEEP", "screenshot" to "KEYCODE_SYSRQ")

    fun exec(c: Context, action: String, a: JSONObject): Res {
        val text = a.optString("text", "")
        val extra = a.optString("extra", "")
        fastMode = a.optBoolean("fast", false)
        return try {
            when (action) {
                "status" -> Res(true, status(c))
                "open_app" -> seen(c, openApp(c, text))
                "open_url" -> seen(c, openUrl(c, text))
                "play_music" -> playMusic(c, text, extra)
                "set_player" -> setPlayer(c, text)
                "now_playing" -> {
                    val np = nowPlaying(c)
                    Res(true, if (np == null) "nada tocando (ou o acesso as notificacoes esta desligado)" else (if (np.second) "tocando: " else "pausado: ") + np.first)
                }
                "media" -> media(c, text)
                "volume" -> volume(c, text)
                "camera" -> Cam.shot(c, text.lowercase().startsWith("fron"))
                "screenshot" -> screenshot(c)
                "read_screen" -> Res(true, (JJAccessibility.inst ?: return Res(false, NO_ACC)).readScreen())
                "tap" -> tap(c, text, a.optInt("x", 0), a.optInt("y", 0))
                "swipe" -> swipe(c, text)
                "type" -> typeText(c, text, extra)
                "key" -> keyAction(c, text)
                "notifications" -> Res(true, (JJNotifListener.inst ?: return Res(false, "Acesso as notificacoes desativado. Ative no painel do JJ.")).dump(text, extra.toIntOrNull() ?: 12))
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
                "bg_open" -> bgOpen(c, text)
                "bg_off" -> {
                    JJAccessibility.targetDisplay = -1
                    Res(true, "voltei a controlar a tela principal (o app continua rodando na tela virtual)")
                }
                "batch" -> batch(c, text)
                "shell" -> shell(text)
                "system" -> system(text)
                "wake" -> wake(c)
                "apps" -> apps(c)
                else -> Res(false, "acao desconhecida: $action")
            }
        } catch (e: Exception) {
            Res(false, "erro ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Varias acoes numa ida so (menos viagens ate a IA = mais rapido). text = JSON: [{"action":"tap","text":"OK","wait":300}, ...] */
    private fun batch(c: Context, json: String): Res {
        val arr = JSONArray(json)
        val prev = fastMode
        val sb = StringBuilder()
        var ok = true
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val a2 = JSONObject().put("text", o.optString("text", "")).put("extra", o.optString("extra", ""))
                .put("x", o.optInt("x", 0)).put("y", o.optInt("y", 0)).put("fast", true)
            val r = exec(c, o.optString("action"), a2)
            sb.append(i + 1).append(". ").append(o.optString("action")).append(": ").append(if (r.ok) "" else "FALHOU ").append(r.text.lines().first()).append('\n')
            if (!r.ok) { ok = false; break }
            Thread.sleep(o.optLong("wait", 250))
        }
        fastMode = prev
        return seen(c, Res(ok, sb.toString().trim()))
    }

    private fun screenshot(c: Context): Res {
        if (JJAccessibility.targetDisplay > 0) {
            val vb = VDisplay.snapshot() ?: return Res(false, "tela virtual ainda sem imagem")
            return Res(true, "captura da tela virtual", Img.fromBitmap(vb))
        }
        if (Shizu.ready()) {
            val b = Shizu.bytes("screencap -p")
            if (b != null && b.size > 1000) {
                val bm = BitmapFactory.decodeByteArray(b, 0, b.size)
                if (bm != null) return Res(true, "captura da tela", Img.fromBitmap(bm))
            }
        }
        val acc = JJAccessibility.inst ?: return Res(false, NO_ACC)
        val bm = acc.screenshot() ?: return Res(false, "captura de tela indisponivel (precisa Android 11+ ou Shizuku)")
        return Res(true, "captura da tela", Img.fromBitmap(bm))
    }

    private fun tap(c: Context, text: String, x: Int, y: Int): Res {
        val acc = JJAccessibility.inst
        val ok = when {
            text.isNotEmpty() -> (acc ?: return Res(false, NO_ACC)).tapText(text)
            Shizu.ready() -> Shizu.run("${inp()} tap $x $y").first == 0
            acc != null -> acc.tapXY(x.toFloat(), y.toFloat())
            else -> return Res(false, NO_ACC)
        }
        return seen(c, Res(ok, if (ok) "toquei" else "nao consegui tocar (confira o [tela agora] ou use read_screen)"))
    }

    private fun swipe(c: Context, dir: String): Res {
        if (Shizu.ready()) {
            val dm = c.resources.displayMetrics
            val vd = JJAccessibility.targetDisplay > 0
            val w = if (vd) VDisplay.W else dm.widthPixels
            val h = if (vd) VDisplay.H else dm.heightPixels
            val (x1, y1, x2, y2) = when (dir.lowercase()) {
                "up" -> listOf(w / 2, h * 3 / 4, w / 2, h / 4)
                "down" -> listOf(w / 2, h / 4, w / 2, h * 3 / 4)
                "left" -> listOf(w * 4 / 5, h / 2, w / 5, h / 2)
                "right" -> listOf(w / 5, h / 2, w * 4 / 5, h / 2)
                else -> return Res(false, "use up, down, left ou right")
            }
            val ok = Shizu.run("${inp()} swipe $x1 $y1 $x2 $y2 300").first == 0
            return seen(c, Res(ok, if (ok) "rolei $dir" else "gesto falhou"))
        }
        val acc = JJAccessibility.inst ?: return Res(false, NO_ACC)
        val ok = acc.swipe(dir)
        return seen(c, Res(ok, if (ok) "rolei $dir" else "gesto falhou"))
    }

    private fun typeText(c: Context, text: String, extra: String): Res {
        val acc = JJAccessibility.inst
        var ok = acc?.typeText(text) ?: false
        if (!ok && Shizu.ready() && text.all { it.code in 32..126 }) {
            ok = Shizu.run("${inp()} text " + Shizu.q(text.replace(" ", "%s"))).first == 0
        }
        if (ok && extra == "submit") {
            if (Shizu.ready()) Shizu.run("${inp()} keyevent KEYCODE_ENTER") else acc?.submit()
        }
        return seen(c, Res(ok, if (ok) "digitei" else "nenhum campo de texto em foco"))
    }

    private fun keyAction(c: Context, name: String): Res {
        val n = name.lowercase()
        if (Shizu.ready()) {
            val cmd = when (n) {
                "notifications" -> "cmd statusbar expand-notifications"
                "quick_settings" -> "cmd statusbar expand-settings"
                else -> KEYEV[n]?.let { "${inp()} keyevent $it" }
            }
            if (cmd != null) {
                val ok = Shizu.run(cmd).first == 0
                return seen(c, Res(ok, if (ok) "ok: $name" else "falhou: $name"))
            }
        }
        val acc = JJAccessibility.inst ?: return Res(false, NO_ACC)
        val ok = acc.globalKey(n)
        return seen(c, Res(ok, if (ok) "ok: $name" else "tecla nao suportada: $name"))
    }

    private fun shell(cmd: String): Res {
        if (!Shizu.ready()) return Res(false, "Shizuku nao esta ativo: ligue o Shizuku e conceda ao JJ (painel do celular)")
        val (code, out) = Shizu.run(cmd, 25)
        return Res(code == 0, "[exit $code] " + out.take(3000))
    }

    private fun system(what: String): Res {
        if (!Shizu.ready()) return Res(false, "precisa do Shizuku ativo")
        val w = what.lowercase().trim()
        val cmd = when {
            w == "wifi_on" -> "svc wifi enable"
            w == "wifi_off" -> "svc wifi disable"
            w == "data_on" -> "svc data enable"
            w == "data_off" -> "svc data disable"
            w == "bt_on" -> "svc bluetooth enable"
            w == "bt_off" -> "svc bluetooth disable"
            w == "dnd_on" -> "settings put global zen_mode 1"
            w == "dnd_off" -> "settings put global zen_mode 0"
            w == "airplane_on" -> "cmd connectivity airplane-mode enable"
            w == "airplane_off" -> "cmd connectivity airplane-mode disable"
            w == "rotate_on" -> "settings put system accelerometer_rotation 1"
            w == "rotate_off" -> "settings put system accelerometer_rotation 0"
            w.startsWith("brightness:") -> {
                val n = w.substringAfter(':').trim().toIntOrNull() ?: return Res(false, "use brightness:0-255")
                "settings put system screen_brightness_mode 0; settings put system screen_brightness ${n.coerceIn(1, 255)}"
            }
            else -> return Res(false, "opcoes: wifi_on/off, data_on/off, bt_on/off, dnd_on/off, airplane_on/off, rotate_on/off, brightness:0-255")
        }
        val (code, out) = Shizu.run(cmd)
        return Res(code == 0, if (code == 0) "ok: $what" else "falhou: ${out.take(120)}")
    }

    @Suppress("DEPRECATION")
    private fun wake(c: Context): Res {
        if (Shizu.ready()) {
            Shizu.run("${inp()} keyevent KEYCODE_WAKEUP")
            return Res(true, "tela acesa")
        }
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jj:wake")
        wl.acquire(4000)
        return Res(true, "tela acesa")
    }

    private fun apps(c: Context): Res {
        val pm = c.packageManager
        val names = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() }.distinct().sorted()
        return Res(true, names.take(150).joinToString(", "))
    }

    fun status(c: Context): String {
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        fun f(b: Boolean) = if (b) "ok" else "FALTA"
        return "bateria $bat%${if (bm.isCharging) " (carregando)" else ""}; tela ${if (pm.isInteractive) "ligada" else "apagada"}; " +
            "app na frente: ${JJAccessibility.fg}; acessibilidade ${f(JJAccessibility.inst != null)}; " +
            "notificacoes ${f(JJNotifListener.inst != null)}; sobreposicao ${f(Settings.canDrawOverlays(c))}; " +
            "camera ${f(has(c, Manifest.permission.CAMERA))}; contatos ${f(has(c, Manifest.permission.READ_CONTACTS))}; " +
            "shizuku ${if (Shizu.ready()) "ok" else "nao"}; tocando: ${nowPlaying(c)?.first ?: "nada"}"
    }

    private fun appLabel(c: Context, pkg: String): String =
        try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }

    /** Acha o app instalado pelo NOME (ou pacote): exato, depois "comeca com", depois "contem", depois todas as palavras. */
    private fun resolveApp(c: Context, name: String): String? {
        val q = norm(name).trim()
        if (q.isEmpty()) return null
        if (name.contains('.') && installed(c, name.trim())) return name.trim()
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { Pair(norm(it.loadLabel(pm).toString()), it.activityInfo.packageName) }
            .filter { it.first.isNotEmpty() && it.second != c.packageName }
        val words = q.split(' ').filter { it.isNotEmpty() }
        return apps.firstOrNull { it.first == q }?.second
            ?: apps.firstOrNull { it.first.startsWith(q) }?.second
            ?: apps.firstOrNull { it.first.contains(q) || q.contains(it.first) }?.second
            ?: apps.firstOrNull { a -> words.all { a.first.contains(it) } }?.second
            ?: apps.firstOrNull { a -> words.isNotEmpty() && a.first.contains(words[0]) && words[0].length >= 4 }?.second
    }

    private fun launchPkg(c: Context, pkg: String): Boolean {
        if (Shizu.ready() && Shizu.run("monkey -p $pkg -c android.intent.category.LAUNCHER 1", 10).first == 0) return true
        val li = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return launch(c, li) == "ok"
    }

    private fun openApp(c: Context, name: String): Res {
        val q = norm(name)
        if (q.isEmpty()) return Res(false, "diga o nome do app")
        val pkg = resolveApp(c, name) ?: return Res(false, "app nao encontrado: $name (use a acao apps para listar)")
        val label = appLabel(c, pkg)
        if (!launchPkg(c, pkg)) return Res(false, "sem tela de abertura: $label")
        return Res(true, "abri $label")
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

    /** Resumo curto da tela depois de uma acao: e o "olho" do JJ para saber se deu certo. */
    private fun seen(c: Context, r: Res): Res {
        if (!r.ok || fastMode) return r
        Thread.sleep(700)
        val acc = JJAccessibility.inst ?: return r
        return Res(true, r.text + "\n[tela agora] " + acc.summary())
    }

    private fun returnToJJ(c: Context) {
        if (Shizu.ready()) {
            Shizu.run("am start -n ${c.packageName}/.MainActivity")
            return
        }
        try {
            c.startActivity(Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {}
        Thread.sleep(700)
        val acc = JJAccessibility.inst
        if (acc != null && JJAccessibility.fg != c.packageName) acc.globalKey("back")  // plano B
    }

    private val PLAYERS = listOf("com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app",
        "com.amazon.mp3", "com.miui.player", "com.google.android.music")

    /** Escolhe o player sozinho (sem a telinha "Abrir com"): o salvo, senao o primeiro conhecido instalado. */
    private fun pickPlayer(c: Context): String {
        val found = c.packageManager.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
            .map { it.activityInfo.packageName }.filter { it != c.packageName }
        if (found.isEmpty()) return ""
        val saved = PhoneService.prefs(c).getString("music_pkg", "") ?: ""
        if (saved.isNotEmpty() && found.contains(saved)) return saved
        return PLAYERS.firstOrNull { found.contains(it) } ?: found.first()
    }

    private fun setPlayer(c: Context, t: String): Res {
        val pkg = resolveApp(c, t) ?: return Res(false, "player nao encontrado: $t")
        PhoneService.prefs(c).edit().putString("music_pkg", pkg).apply()
        return Res(true, "player padrao: ${appLabel(c, pkg)}")
    }

    private fun controller(c: Context, pkg: String): MediaController? {
        if (JJNotifListener.inst == null) return null
        return try {
            val msm = c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(ComponentName(c, JJNotifListener::class.java)).firstOrNull { pkg.isEmpty() || it.packageName == pkg }
        } catch (e: Exception) { null }
    }

    /** (titulo - artista, tocando?) da sessao de midia ativa; precisa do acesso as notificacoes. */
    fun nowPlaying(c: Context): Pair<String, Boolean>? {
        if (JJNotifListener.inst == null) return null
        return try {
            val msm = c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(ComponentName(c, JJNotifListener::class.java))
            val s = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull() ?: return null
            val md = s.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val name = listOf(title, artist).filter { it.isNotEmpty() }.joinToString(" - ").ifEmpty { s.packageName }
            Pair(name, s.playbackState?.state == PlaybackState.STATE_PLAYING)
        } catch (e: Exception) { null }
    }

    /** Espera comecar a tocar (devolve assim que comeca, sem tempo fixo). */
    private fun waitPlaying(c: Context, ms: Long): Pair<String, Boolean>? {
        if (JJNotifListener.inst == null) {
            Thread.sleep(minOf(ms, 3500L))
            return null
        }
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val np = nowPlaying(c)
            if (np != null && np.second) return np
            Thread.sleep(250)
        }
        return nowPlaying(c)
    }

    /** Segundo plano REAL: conecta no servico de midia do app (o mesmo que o Android Auto usa) e manda tocar a busca. */
    private fun browserPlay(c: Context, pkg: String, q: String): Boolean {
        val svc = c.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
            .firstOrNull { it.serviceInfo.packageName == pkg } ?: return false
        val comp = ComponentName(pkg, svc.serviceInfo.name)
        val latch = CountDownLatch(1)
        var sent = false
        var browser: MediaBrowser? = null
        Handler(Looper.getMainLooper()).post {
            try {
                val b = MediaBrowser(c, comp, object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        try {
                            val tok = browser?.sessionToken
                            if (tok != null) {
                                MediaController(c, tok).transportControls.playFromSearch(q, Bundle())
                                sent = true
                            }
                        } catch (e: Exception) {}
                        latch.countDown()
                    }
                    override fun onConnectionFailed() { latch.countDown() }
                    override fun onConnectionSuspended() { latch.countDown() }
                }, null)
                browser = b
                b.connect()
            } catch (e: Exception) { latch.countDown() }
        }
        latch.await(5, TimeUnit.SECONDS)
        var ok = sent
        if (sent && JJNotifListener.inst != null) {
            val np = waitPlaying(c, 4500)
            ok = np != null && np.second
        } else if (sent) {
            Thread.sleep(1500)
        }
        Handler(Looper.getMainLooper()).post { try { browser?.disconnect() } catch (e: Exception) {} }
        return ok
    }

    private fun vdEnabled(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 30 && PhoneService.prefs(c).getBoolean("vdisplay", true) && JJAccessibility.inst != null

    /** Abre o app na TELA VIRTUAL (invisivel) e passa a controlar ela. false = indisponivel (usa a tela principal). */
    private fun openInVirtual(c: Context, pkg: String): Boolean {
        if (!vdEnabled(c)) return false
        val acc = JJAccessibility.inst ?: return false
        if (!VDisplay.launch(c, pkg)) return false
        JJAccessibility.targetDisplay = VDisplay.id
        var t = 0
        while (acc.rootPkg() != pkg && t < 16) { Thread.sleep(250); t++ }
        if (acc.rootPkg() == pkg) return true
        JJAccessibility.targetDisplay = -1
        return false
    }

    private fun bgOpen(c: Context, name: String): Res {
        val pkg = resolveApp(c, name) ?: return Res(false, "app nao encontrado: $name")
        if (!openInVirtual(c, pkg)) return Res(false, "tela virtual indisponivel neste aparelho (precisa Android 11+, acessibilidade ligada); use open_app")
        Thread.sleep(700)
        return Res(true, "abri ${appLabel(c, pkg)} na tela virtual (invisivel). As acoes read_screen/tap/type/swipe/screenshot valem para ela ate bg_off.\n[tela agora] " + (JJAccessibility.inst?.summary() ?: ""))
    }

    /**
     * Apps sem comando de busca: abre (na tela virtual, se der), pesquisa, toca no resultado e no play.
     * Roda inteiro aqui no celular, sem IA no meio (a IA era o que deixava tudo lento e confuso).
     */
    private fun playViaUi(c: Context, pkg: String, q: String, wasFront: Boolean, tr: StringBuilder): Res {
        val acc = JJAccessibility.inst ?: return Res(false, "esse app nao aceita comando de busca e a acessibilidade esta desligada")
        val label = appLabel(c, pkg)
        val bg = openInVirtual(c, pkg)
        tr.append(if (bg) "tela virtual; " else "tela principal; ")
        if (!bg) {
            launchPkg(c, pkg)
            var t = 0
            while (JJAccessibility.fg != pkg && t < 20) { Thread.sleep(250); t++ }
        }
        Thread.sleep(900)
        try {
            var typed = false
            for (i in 0 until 3) {
                if (!acc.hasEditable()) {
                    val tapped = acc.tapAny(listOf("pesquis", "buscar", "search", "procurar"))
                    tr.append(if (tapped) "toquei em pesquisar; " else "sem botao de pesquisa; ")
                    Thread.sleep(900)
                }
                typed = acc.typeText(q)
                if (!typed && Shizu.ready() && q.all { it.code in 32..126 }) {
                    // campos que nao se declaram "editaveis" (Flutter/React): digita direto no foco
                    Shizu.run("${inp()} text " + Shizu.q(q.replace(" ", "%s")))
                    Thread.sleep(400)
                    typed = acc.screenHasText(q)
                }
                if (typed) break
                Thread.sleep(500)
            }
            if (!typed) {
                tr.append("nao consegui digitar; ")
                return Res(false, "nao achei o campo de busca em $label. [tela agora] ${acc.summary()}")
            }
            tr.append("digitei; ")
            if (Shizu.ready()) Shizu.run("${inp()} keyevent KEYCODE_ENTER") else acc.submit()
            Thread.sleep(1600)
            tr.append(if (acc.tapResult(q)) "toquei no resultado; " else "nao achei o resultado; ")
            Thread.sleep(1200)
            var np = nowPlaying(c)
            if (np == null || !np.second) tr.append(if (acc.tapPlay()) "toquei em play; " else "sem botao play; ")
            np = waitPlaying(c, 5000)
            val scr = acc.summary()
            val npf = np
            val back = if (!bg && wasFront) " (voltei para o JJ)" else ""
            val where = if (bg) " em segundo plano (tela virtual)" else ""
            return if (npf != null && npf.second) Res(true, "tocando$where: ${npf.first}$back")
            else Res(true, "busquei \"$q\" em $label$where mas nao confirmei que esta tocando. [tela agora] $scr$back")
        } finally {
            if (bg) JJAccessibility.targetDisplay = -1  // o app continua rodando na tela virtual
            else if (wasFront) returnToJJ(c)
        }
    }

    /**
     * Toca musica. appIn = NOME do app pedido pelo usuario (ex.: "fyzer"); vazio = player padrao.
     * Ordem: sessao ativa > servico de midia (segundo plano real) > comando de busca do app > operar o app (tela virtual).
     * Nunca troca de app sozinho e nunca abre pagina web. O resultado traz "passos" para diagnostico.
     */
    private fun playMusic(c: Context, q: String, appIn: String): Res {
        val tr = StringBuilder()
        val r = playMusicCore(c, q, appIn, tr)
        return if (tr.isEmpty()) r else Res(r.ok, r.text + " | passos: " + tr.toString().trim(), r.image)
    }

    private fun playMusicCore(c: Context, q: String, appIn: String, tr: StringBuilder): Res {
        val wasFront = jjFront || JJAccessibility.fg == c.packageName
        val explicit = appIn.isNotBlank()
        val player = if (explicit) (resolveApp(c, appIn) ?: return Res(false, "nao achei o app \"$appIn\" instalado (use a acao apps para listar)")) else pickPlayer(c)
        if (player.isEmpty()) return Res(false, "nao ha app de musica padrao; diga em qual app tocar")
        val label = appLabel(c, player)
        val back = if (wasFront) " (voltei para o JJ)" else ""
        tr.append("app=$label; ")

        // 1) o app ja tem sessao ativa: manda tocar sem abrir nada
        val ctl = controller(c, player)
        if (ctl != null) {
            try { ctl.transportControls.playFromSearch(q, Bundle()) } catch (e: Exception) {}
            val np0 = waitPlaying(c, 3000)
            if (np0 != null && np0.second) return Res(true, "tocando em segundo plano no $label: ${np0.first}")
            tr.append("sessao ativa nao tocou; ")
        } else {
            tr.append("sem sessao ativa; ")
        }
        // 2) servico de midia do app (sem abrir nada na tela)
        if (browserPlay(c, player, q)) {
            PhoneService.prefs(c).edit().putString("music_pkg", player).apply()
            val np1 = nowPlaying(c)
            return Res(true, "tocando em segundo plano no $label" + (if (np1 != null) ": ${np1.first}" else ""))
        }
        tr.append("servico de midia indisponivel; ")
        // 3) o app entende o comando de busca
        val handles = c.packageManager.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0).any { it.activityInfo.packageName == player }
        if (handles) {
            var started = false
            if (Shizu.ready()) {
                started = Shizu.run("am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH -p $player --es query ${Shizu.q(q)} --es android.intent.extra.focus 'vnd.android.cursor.item/*'", 10).first == 0
            }
            if (!started) {
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                i.putExtra(SearchManager.QUERY, q)
                i.setPackage(player)
                started = launch(c, i) == "ok"
            }
            if (started) {
                val verifiable = JJNotifListener.inst != null
                var np = waitPlaying(c, 7000)
                if (np == null || !np.second) {
                    key(c, KeyEvent.KEYCODE_MEDIA_PLAY)
                    np = waitPlaying(c, 2500)
                }
                if (np != null && np.second || !verifiable) {
                    if (wasFront) returnToJJ(c)
                    PhoneService.prefs(c).edit().putString("music_pkg", player).apply()
                    return Res(true, if (np != null) "tocando no $label: ${np.first}$back" else "pedi para tocar \"$q\" no $label$back")
                }
                tr.append("comando de busca nao tocou; ")
            }
        } else {
            tr.append("app sem comando de busca; ")
        }
        // 4) opera o app (de preferencia na tela virtual)
        return playViaUi(c, player, q, wasFront, tr)
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
