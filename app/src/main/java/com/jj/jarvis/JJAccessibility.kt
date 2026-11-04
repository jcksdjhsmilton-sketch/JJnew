package com.jj.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Serviço de acessibilidade: é o que permite ao JJ ler a tela, tocar, rolar, digitar e tirar capturas. */
class JJAccessibility : AccessibilityService() {
    companion object {
        @Volatile var inst: JJAccessibility? = null
        @Volatile var fg: String = ""
    }

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onDestroy() { inst = null; super.onDestroy() }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e != null && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString()
            if (p != null) fg = p
        }
    }

    /**
     * Lista o que esta na tela. Se for longa, ELIMINA O INICIO e mantem o FIM: numa conversa a mensagem mais recente
     * fica embaixo, e antes ela era cortada (o JJ lia mensagens antigas).
     */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "(tela indisponivel agora)"
        val lines = ArrayList<String>()
        fun walk(nd: AccessibilityNodeInfo?) {
            if (nd == null || lines.size > 700) return
            val t = (nd.text ?: nd.contentDescription)?.toString()?.replace("\n", " ")
            if (!t.isNullOrBlank() || nd.isClickable) {
                val r = Rect()
                nd.getBoundsInScreen(r)
                val sb = StringBuilder("- ").append((t ?: "").take(400))
                if (nd.isClickable) sb.append(" [clicavel]")
                if (nd.isEditable) sb.append(" [campo]")
                sb.append(" @").append(r.centerX()).append(',').append(r.centerY())
                lines.add(sb.toString())
            }
            for (i in 0 until nd.childCount) walk(nd.getChild(i))
        }
        walk(root)
        var start = 0
        var total = lines.sumOf { it.length + 1 }
        while (total > 7000 && start < lines.size - 1) { total -= lines[start].length + 1; start++ }
        val head = "app: ${root.packageName}" + if (start > 0) "\n[$start itens do INICIO omitidos; a lista termina no FIM da tela, onde ficam as mensagens mais recentes]" else ""
        return head + "\n" + lines.subList(start, lines.size).joinToString("\n")
    }

    /** Resumo curto do que esta na tela (app + primeiros textos). */
    fun summary(): String {
        val root = rootInActiveWindow ?: return "(tela indisponivel)"
        val parts = ArrayList<String>()
        fun walk(nd: AccessibilityNodeInfo?) {
            if (nd == null || parts.size > 400) return
            val t = (nd.text ?: nd.contentDescription)?.toString()?.replace("\n", " ")?.trim()
            if (!t.isNullOrEmpty() && t.length < 120) parts.add(t)
            for (i in 0 until nd.childCount) walk(nd.getChild(i))
        }
        walk(root)
        val pick = if (parts.size <= 14) parts else parts.take(4) + "..." + parts.takeLast(10)  // comeco (titulo) + FIM (o mais recente)
        return (root.packageName?.toString() ?: "?") + ": " + pick.joinToString(" | ").take(600)
    }

    fun gesture(p: Path, ms: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }
        val started = dispatchGesture(g, cb, Handler(Looper.getMainLooper()))
        if (!started) return false
        latch.await(4, TimeUnit.SECONDS)
        return ok
    }

    fun tapXY(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        return gesture(p, 60)
    }

    fun tapText(q: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val list = root.findAccessibilityNodeInfosByText(q)
        for (nd in list) {
            var cur: AccessibilityNodeInfo? = nd
            while (cur != null && !cur.isClickable) cur = cur.parent
            if (cur != null && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        if (list.isNotEmpty()) {
            val r = Rect()
            list[0].getBoundsInScreen(r)
            return tapXY(r.centerX().toFloat(), r.centerY().toFloat())
        }
        return false
    }

    fun swipe(dir: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val p = Path()
        when (dir.lowercase()) {
            "up" -> { p.moveTo(w / 2, h * 0.75f); p.lineTo(w / 2, h * 0.25f) }
            "down" -> { p.moveTo(w / 2, h * 0.25f); p.lineTo(w / 2, h * 0.75f) }
            "left" -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            "right" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
            else -> return false
        }
        return gesture(p, 350)
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val r = findEditable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    fun typeText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun submit(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val root = rootInActiveWindow ?: return false
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun globalKey(name: String): Boolean {
        val a = when (name.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            else -> return false
        }
        return performGlobalAction(a)
    }

    fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                val hb = r.hardwareBuffer
                val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                latch.countDown()
            }
            override fun onFailure(code: Int) { latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        return bmp
    }
}
