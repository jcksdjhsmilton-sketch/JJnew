package com.jj.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Img {
    fun fromBitmap(src: Bitmap): String {
        val mx = 1024f
        val sc = minOf(1f, mx / maxOf(src.width, src.height).toFloat())
        val bm = if (sc < 1f) Bitmap.createScaledBitmap(src, (src.width * sc).toInt(), (src.height * sc).toInt(), true) else src
        val out = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun fromBytes(b: ByteArray): String {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1
        while (o.outWidth / s > 2048) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        var bm = BitmapFactory.decodeByteArray(b, 0, b.size, o2)
        val rot = try {
            when (ExifInterface(ByteArrayInputStream(b)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        if (rot != 0f) {
            val m = Matrix()
            m.postRotate(rot)
            bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
        }
        return fromBitmap(bm)
    }
}

/** Tira uma foto com a câmera sem precisar de tela (funciona em segundo plano com o serviço ativo). */
object Cam {
    @SuppressLint("MissingPermission")
    fun shot(c: Context, front: Boolean): Res {
        if (!Phone.has(c, Manifest.permission.CAMERA)) return Res(false, "sem permissao de camera")
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: return Res(false, "camera nao encontrada")
        val ch = cm.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Res(false, "camera sem configuracao")
        val jsizes = map.getOutputSizes(ImageFormat.JPEG)
        val size = jsizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height } ?: jsizes[0]
        val ysizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val psize = ysizes.filter { it.width >= 480 }.minByOrNull { it.width * it.height } ?: ysizes[0]
        val so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val ht = HandlerThread("jjcam")
        ht.start()
        val h = Handler(ht.looper)
        val jpeg = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        val prev = ImageReader.newInstance(psize.width, psize.height, ImageFormat.YUV_420_888, 2)
        val done = CountDownLatch(1)
        var bytes: ByteArray? = null
        var err = ""
        var dev: CameraDevice? = null

        prev.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, h)
        jpeg.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage()
            if (img != null) {
                val buf = img.planes[0].buffer
                val arr = ByteArray(buf.remaining())
                buf.get(arr)
                bytes = arr
                img.close()
                done.countDown()
            }
        }, h)

        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    dev = d
                    try {
                        d.createCaptureSession(listOf(prev.surface, jpeg.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                try {
                                    val pr = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    pr.addTarget(prev.surface)
                                    s.setRepeatingRequest(pr.build(), null, h)
                                    h.postDelayed({
                                        try {
                                            val st = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                            st.addTarget(jpeg.surface)
                                            st.set(CaptureRequest.JPEG_ORIENTATION, so)
                                            s.capture(st.build(), null, h)
                                        } catch (e: Exception) { err = e.message ?: "falha na captura"; done.countDown() }
                                    }, 1200)
                                } catch (e: Exception) { err = e.message ?: "falha no preview"; done.countDown() }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { err = "configuracao da camera falhou"; done.countDown() }
                        }, h)
                    } catch (e: Exception) { err = e.message ?: "falha na sessao"; done.countDown() }
                }
                override fun onDisconnected(d: CameraDevice) { err = "camera desconectou (em uso por outro app?)"; d.close(); done.countDown() }
                override fun onError(d: CameraDevice, e: Int) { err = "erro de camera $e"; d.close(); done.countDown() }
            }, h)
        } catch (e: Exception) {
            ht.quitSafely()
            return Res(false, "nao abriu a camera: ${e.message}")
        }

        done.await(15, TimeUnit.SECONDS)
        try { dev?.close() } catch (e: Exception) {}
        ht.quitSafely()
        val b = bytes ?: return Res(false, "foto falhou: $err")
        return Res(true, "foto da camera ${if (front) "frontal" else "traseira"}", Img.fromBytes(b))
    }
}
