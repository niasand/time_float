package com.timefloat.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager

/**
 * 整屏截屏（MediaProjection）。
 *
 * Android 14 约束：consent token 单次有效，同一 MediaProjection 只允许
 * createVirtualDisplay 一次。因此授权成功后常驻一个 VirtualDisplay + ImageReader，
 * 每次点击字幕按需取最新帧，绝不重复建屏。
 * 取帧事件在专属 HandlerThread 上处理，结果一律回调主线程。
 */
class ProjectionCapture(
    private val context: Context,
    private val mainHandler: Handler
) {

    /** 投影被系统停止（状态栏入口/锁屏/其他投影抢占）时回调，主线程 */
    var onProjectionStopped: (() -> Unit)? = null

    val isReady: Boolean get() = projection != null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    /** 待交付的取帧请求；ImageReader 回调据此区分“丢弃帧”与“交付帧” */
    @Volatile
    private var pendingCallback: ((Bitmap?) -> Unit)? = null
    private var timeoutRunnable: Runnable? = null

    /** 接入授权结果（服务收到 consent extras 后调用一次）；幂等，失败自动回收 */
    fun setup(resultCode: Int, data: Intent) {
        if (projection != null) return
        val thread = HandlerThread("tf_capture").apply { start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)

        runCatching {
            val mpm =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
            projection = proj

            // Android 14：必须在 createVirtualDisplay 之前注册 Callback，否则抛 IllegalStateException
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    release()
                    mainHandler.post { onProjectionStopped?.invoke() }
                }
            }, captureHandler)

            val (w, h) = screenSize()
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ onImageAvailable(it) }, captureHandler)

            virtualDisplay = proj.createVirtualDisplay(
                "timefloat", w, h,
                context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, captureHandler
            )
        }.onFailure {
            release()
        }
    }

    /**
     * 请求一帧整屏截图。未就绪或超时内无新帧时以 null 回调（主线程）；
     * 超时取 1500ms，须大于时钟 1s 刷新间隔，保证 armed 后必有新帧。
     */
    fun capture(onDone: (Bitmap?) -> Unit) {
        if (!isReady) {
            mainHandler.post { onDone(null) }
            return
        }
        pendingCallback = onDone
        val timeout = Runnable {
            val cb = pendingCallback
            pendingCallback = null
            timeoutRunnable = null
            mainHandler.post { cb?.invoke(null) }
        }
        timeoutRunnable = timeout
        captureHandler?.postDelayed(timeout, TIMEOUT_MS)
    }

    /** 释放全部资源；投影 onStop 与服务 onDestroy 双入口，幂等 */
    fun release() {
        pendingCallback = null
        timeoutRunnable?.let { captureHandler?.removeCallbacks(it) }
        timeoutRunnable = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    private fun onImageAvailable(reader: ImageReader) {
        val cb = pendingCallback ?: run {
            // 无待处理请求：丢弃最新帧，保证下次取帧必然等到含高亮的新画面
            runCatching { reader.acquireLatestImage()?.close() }
            return
        }
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        pendingCallback = null
        timeoutRunnable?.let { captureHandler?.removeCallbacks(it) }
        timeoutRunnable = null
        val bitmap = runCatching { imageToBitmap(image) }
        // 拿到的 Image 必须 close，否则缓冲队列堵死
        image.close()
        mainHandler.post { cb(bitmap.getOrNull()) }
    }

    /** 按行跨度拷贝成 Bitmap：rowStride 常大于 width*4，超宽分配吸收填充后裁剪 */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val rowPadding = plane.rowStride - plane.pixelStride * w
        val full = Bitmap.createBitmap(
            w + rowPadding / plane.pixelStride, h, Bitmap.Config.ARGB_8888
        )
        full.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return full
        return Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() }
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val p = Point().also { wm.defaultDisplay.getRealSize(it) }
            p.x to p.y
        }
    }

    companion object {
        private const val TIMEOUT_MS = 1500L
    }
}
