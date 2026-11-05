package com.jj.jarvis

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper

/**
 * Tela virtual: apps abertos nela rodam (e tocam som) SEM aparecer na tela do usuario. E a mesma tecnica de controle
 * remoto/scrcpy. O JJ le e toca nessa tela pela acessibilidade (JJAccessibility.targetDisplay).
 */
object VDisplay {
    const val W = 1080
    const val H = 2160
    private const val DPI = 420
    private val lock = Any()
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var held: Image? = null
    @Volatile var id: Int = -1

    fun ensure(c: Context): Int {
        synchronized(lock) {
            val cur = vd
            if (cur != null && id > 0 && cur.display != null) return id
            release()
            val dm = c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val r = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3)
            r.setOnImageAvailableListener({ x ->
                val img = x.acquireLatestImage()
                if (img != null) {
                    synchronized(lock) {
                        held?.close()
                        held = img
                    }
                }
            }, Handler(Looper.getMainLooper()))
            reader = r
            val own = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val v = try {
                dm.createVirtualDisplay("JJ-bg", W, H, DPI, r.surface, own or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC)
            } catch (e: Exception) {
                dm.createVirtualDisplay("JJ-bg", W, H, DPI, r.surface, own)
            }
            vd = v
            id = v?.display?.displayId ?: -1
            return id
        }
    }

    /** Abre o app na tela virtual. */
    fun launch(c: Context, pkg: String): Boolean {
        val did = try { ensure(c) } catch (e: Exception) { return false }
        if (did <= 0) return false
        val li = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            val o = ActivityOptions.makeBasic()
            o.setLaunchDisplayId(did)
            c.startActivity(li, o.toBundle())
            true
        } catch (e: Exception) {
            // display publico: o shell (Shizuku) tambem consegue abrir
            val comp = li.component?.flattenToShortString()
            comp != null && Shizu.ready() && Shizu.run("am start --display $did -n $comp", 10).first == 0
        }
    }

    /** Ultima imagem da tela virtual (para a visao). */
    fun snapshot(): Bitmap? {
        synchronized(lock) {
            val img = held ?: return null
            val p = img.planes[0]
            val rowPx = p.rowStride / p.pixelStride
            val bmp = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
            p.buffer.rewind()
            bmp.copyPixelsFromBuffer(p.buffer)
            return if (rowPx == img.width) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        }
    }

    fun release() {
        synchronized(lock) {
            try { held?.close() } catch (e: Exception) {}
            held = null
            try { vd?.release() } catch (e: Exception) {}
            vd = null
            try { reader?.close() } catch (e: Exception) {}
            reader = null
            id = -1
        }
    }
}
