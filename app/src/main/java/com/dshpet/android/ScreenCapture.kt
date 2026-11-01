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
 * 屏幕抓帧：授权一次后保持 MediaProjection 会话。
 *
 * 关键点（v1.8.21 修）：ImageReader 必须**一直有人消费**。
 *  之前只在 grab() 时取一帧，队列（2 个 buffer）很快装满，producer 被卡住，
 *  之后 acquireLatestImage 拿到的永远是几分钟前那两帧 —— 表现为"截图停在旧画面"。
 *  现在挂常驻 listener 持续 drain，只在需要时（wantFrame）把当前帧转成 Bitmap 留着。
 */
object ScreenCapture {

    private const val DIR = "/sdcard/Download/.dshpet"

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var w = 0
    private var h = 0

    private val lock = Any()
    private var latest: Bitmap? = null

    @Volatile private var wantFrame = false
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
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            val t = HandlerThread("dshpet-screen").also { it.start() }
            thread = t
            val handler = Handler(t.looper)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    release()
                }
            }, handler)
            r.setOnImageAvailableListener({ rd -> handleFrame(rd) }, handler)
            val d = p.createVirtualDisplay(
                "dshpet-screen",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
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

    /** 常驻消费：保证画面永远是最新的；只有被要求时才把当前帧转 Bitmap */
    private fun handleFrame(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (t: Throwable) { null } ?: return
        try {
            if (wantFrame) {
                wantFrame = false
                val bmp = toBitmap(img)
                if (bmp != null) {
                    synchronized(lock) {
                        latest?.recycle()
                        latest = bmp
                    }
                }
            }
        } finally {
            try { img.close() } catch (_: Throwable) {}
        }
    }

    private fun toBitmap(img: android.media.Image): Bitmap? {
        return try {
            val plane = img.planes[0]
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val pad = rs - ps * w
            val padded = Bitmap.createBitmap(w + pad / ps, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
            padded.recycle()
            cropped
        } catch (t: Throwable) {
            lastError = t.toString()
            null
        }
    }

    /** 取"此刻"的一帧并写成 screen.jpg；失败返回 null */
    fun grab(): File? {
        if (reader == null) return null
        synchronized(lock) {
            latest?.recycle()
            latest = null
        }
        wantFrame = true
        val deadline = System.currentTimeMillis() + 2500L
        while (System.currentTimeMillis() < deadline) {
            val bmp = synchronized(lock) {
                val b = latest
                latest = null
                b
            }
            if (bmp != null) {
                return try {
                    writeJpeg(bmp)
                } finally {
                    bmp.recycle()
                }
            }
            try { Thread.sleep(60L) } catch (_: InterruptedException) {}
        }
        lastError = "no fresh frame in 2.5s"
        return null
    }

    private fun writeJpeg(bmp: Bitmap): File? {
        return try {
            val dir = File(DIR)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "screen.jpg")
            val tmp = File(dir, "screen.jpg.tmp")
            val out = FileOutputStream(tmp)
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            } finally {
                try { out.close() } catch (_: Throwable) {}
            }
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, true)
                tmp.delete()
            }
            f
        } catch (t: Throwable) {
            lastError = t.toString()
            null
        }
    }

    fun release() {
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        display = null
        reader = null
        projection = null
        synchronized(lock) {
            latest?.recycle()
            latest = null
        }
        try { thread?.quitSafely() } catch (_: Throwable) {}
        thread = null
    }
}
