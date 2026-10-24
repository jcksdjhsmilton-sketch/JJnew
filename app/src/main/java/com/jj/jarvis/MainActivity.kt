package com.jj.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(0xFF020912.toInt())
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.addJavascriptInterface(Bridge(), "JJ")
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                cb.invoke(origin, true, false)
            }
        }
        initTts()
        requestPerms()
        web.loadUrl("file:///android_asset/index.html")
        hideBars()
    }

    private fun js(code: String) = web.post { web.evaluateJavascript(code, null) }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val need = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filter { !has(it) }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        js("if(window.onPerms)onPerms()")
    }

    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale("pt", "BR")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { js("onSpeak('start')") }
                    override fun onDone(id: String?) { js("onSpeak('end')") }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { js("onSpeak('end')") }
                })
            }
        }
    }

    private fun startListening() {
        if (!has(Manifest.permission.RECORD_AUDIO)) { requestPerms(); js("onVoice('error','perm')"); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { js("onVoice('error','unavailable')"); return }
        tts?.stop()
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { js("onVoice('start','')") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) { js("if(window.onRms)onRms($v)") }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(e: Int) { js("onVoice('error','$e')") }
                override fun onResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    js("onVoice('final',${JSONObject.quote(t)})")
                }
                override fun onPartialResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (t.isNotEmpty()) js("onVoice('partial',${JSONObject.quote(t)})")
                }
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            js("onLoc(null,null)"); return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        var best: android.location.Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null }
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        val b = best
        if (b != null) js("onLoc(${b.latitude},${b.longitude})") else js("onLoc(null,null)")
    }

    inner class Bridge {
        @JavascriptInterface fun listen() { runOnUiThread { startListening() } }
        @JavascriptInterface fun stopListen() { runOnUiThread { rec?.cancel() } }
        @JavascriptInterface fun speak(t: String) {
            runOnUiThread { if (ttsReady) tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "jj") else js("onSpeak('end')") }
        }
        @JavascriptInterface fun stopSpeak() { runOnUiThread { tts?.stop(); js("onSpeak('end')") } }
        @JavascriptInterface fun location() { runOnUiThread { fetchLocation() } }
        // HTTP nativo (sem CORS): usado para falar com a API do Kaggle e do GitHub
        @JavascriptInterface fun http(id: Int, method: String, url: String, headers: String, body: String) {
            Thread {
                var status = 0
                var text = ""
                try {
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    c.requestMethod = method
                    c.connectTimeout = 20000
                    c.readTimeout = 40000
                    val h = JSONObject(headers)
                    for (k in h.keys()) c.setRequestProperty(k, h.getString(k))
                    if (body.isNotEmpty()) {
                        c.doOutput = true
                        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    status = c.responseCode
                    val st = if (status >= 400) c.errorStream else c.inputStream
                    text = st?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                } catch (e: Exception) {
                    text = e.message ?: e.toString()
                }
                js("onHttp($id,$status,${JSONObject.quote(text)})")
            }.start()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    // Controle remoto: MENU = configurações, botão vermelho = chat curto
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { js("toggleSettings()"); return true }
            KeyEvent.KEYCODE_PROG_RED -> { js("toggleChat()"); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("jjBack()") { r -> if (r != "true") finish() }
    }

    override fun onDestroy() {
        rec?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
