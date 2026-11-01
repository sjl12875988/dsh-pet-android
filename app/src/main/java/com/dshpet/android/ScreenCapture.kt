package com.dshpet.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream

/**
 * 屏幕抓帧：授权一次后保持 MediaProjection 会话，之后按需取最新一帧。
 * 输出 /sdcard/Download/.dshpet/screen.jpg（半分辨率 JPEG）。
 */
object ScreenCapture {

    private const val DIR = "/sdcard/Download/.dshpet"

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var w = 0
    private var h = 0

    @Volatile var lastError: String = ""

    fun isRunning(): Boolean = reader != null

    fun frameWidth(): Int = w

    fun frameHeight(): Int = h

    fun start(ctx: Context, resultCode: Int, data: Intent?): Boolean {
        if (isRunning()) return true
        return try {
            val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = data ?: run { lastError = "projection data is null"; return false }
            val p = mpm.getMediaProjection(resultCode, intent)
            if (p == null) {
                lastError = "getMediaProjection returned null"
                return false
            }
            val dm = ctx.resources.displayMetrics
            w = Math.max(2, dm.widthPixels / 2)
            h = Math.max(2, dm.heightPixels / 2)
            val dpi = Math.max(1, dm.densityDpi / 2)
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val t = HandlerThread("dshpet-screen").also { it.start() }
            thread = t
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    release()
                }
            }, Handler(t.looper))
            val d = p.createVirtualDisplay(
                "dshpet-screen",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, null
            )
            if (d == null) {
                lastError = "createVirtualDisplay returned null"
                try { r.close() } catch (_: Throwable) {}
                try { p.stop() } catch (_: Throwable) {}
                return false
            }
            projection = p
            reader = r
            display = d
            lastError = ""
            true
        } catch (t: Throwable) {
            lastError = t.toString()
            release()
            false
        }
    }

    /** 取最新一帧，写成 screen.jpg；失败返回 null */
    fun grab(): File? {
        val r = reader ?: return null
        var img: android.media.Image? = null
        for (i in 0 until 8) {
            img = try { r.acquireLatestImage() } catch (t: Throwable) { null }
            if (img != null) break
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
        }
        if (img == null) {
            lastError = "no frame available"
            return null
        }
        return try {
            val plane = img.planes[0]
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val pad = rs - ps * w
            val padded = Bitmap.createBitmap(w + pad / ps, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
            val dir = File(DIR)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "screen.jpg")
            val tmp = File(dir, "screen.jpg.tmp")
            val out = FileOutputStream(tmp)
            try {
                cropped.compress(Bitmap.CompressFormat.JPEG, 80, out)
            } finally {
                try { out.close() } catch (_: Throwable) {}
            }
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, true)
                tmp.delete()
            }
            padded.recycle()
            cropped.recycle()
            f
        } catch (t: Throwable) {
            lastError = t.toString()
            null
        } finally {
            try { img.close() } catch (_: Throwable) {}
        }
    }

    fun release() {
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        display = null
        reader = null
        projection = null
        try { thread?.quitSafely() } catch (_: Throwable) {}
        thread = null
    }
}
