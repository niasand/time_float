package com.timefloat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.view.children
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * 前台服务：持有 WindowManager 悬浮窗，秒级刷新时间。
 * 颜色/透明度/字号变化通过 SharedPreferences 监听即时生效，无需重建悬浮窗。
 * 悬浮窗右列竖排展示字幕标记：点击字幕高亮数秒，并追加一条 SRT 记录。
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "overlay_clock"
        private const val NOTIF_ID = 1
        private const val CLICK_SLOP = 12 // dp，小于该位移视为点击

        /** 点击字幕后的高亮底色（淡亮绿）与白字粗体 */
        private const val HIGHLIGHT_COLOR = 0xFF66BB6A.toInt()
        private const val HIGHLIGHT_MS = 3000L

        const val ACTION_STOP = "com.timefloat.app.action.STOP"

        /** 服务存活标记：onCreate 置真 / onDestroy 置假，供设置页自愈判断 */
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, OverlayService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private var overlay: View? = null
    private lateinit var params: WindowManager.LayoutParams

    /** 落盘专用后台线程：SRT 读-改-写不阻塞主线程 */
    private var ioThread: HandlerThread? = null
    private var ioHandler: Handler? = null

    /** 当前高亮中的字幕 chip 与其自动恢复任务 */
    private var activeChip: TextView? = null
    private var revertRunnable: Runnable? = null

    /** 上次渲染的字幕列表：内容未变时跳过重建，避免打断进行中的高亮；null 表示尚未渲染过 */
    private var lastCaptions: List<String>? = null

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd EEE", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            // 对齐下一个整秒，避免累计漂移
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000 + 5)
        }
    }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Prefs.KEY_CAPTIONS -> rebuildChips()
                else -> applyAppearance()
            }
        }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefsListener)

        val thread = HandlerThread("tf_io").apply { start() }
        ioThread = thread
        ioHandler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (overlay == null && Settings.canDrawOverlays(this)) {
            addOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(ticker)
        revertRunnable?.let { handler.removeCallbacks(it) }
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        ioHandler?.removeCallbacksAndMessages(null)
        ioThread?.quitSafely()
        ioThread = null
        ioHandler = null
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
        )
        val contentPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(contentPi)
            .addAction(0, getString(R.string.notif_stop), stopPi)
            .setOngoing(true)
            .build()
    }

    private fun addOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.view_overlay, null)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.posX(this@OverlayService)
            y = Prefs.posY(this@OverlayService)
        }
        attachDrag(view)
        wm.addView(view, params)
        overlay = view

        // 首次显示（从未拖动过）：默认放在屏幕顶部居中
        if (params.x < 0 || params.y < 0) {
            view.post {
                val screen = screenRealSize()
                params.x = (screen.x - view.width) / 2
                params.y = (screen.y * 0.06f).toInt()
                runCatching { wm.updateViewLayout(view, params) }
                savePos()
            }
        }
        rebuildChips()
        applyAppearance()
        handler.post(ticker)
    }

    /** 拖动更新悬浮窗位置；松手时按位移区分点击（回设置页）与拖动（存位置）。
     *  字幕 chip 自身可点击并消费手势，不经此监听。 */
    private fun attachDrag(view: View) {
        val slop = CLICK_SLOP * resources.displayMetrics.density
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    val screen = screenRealSize()
                    params.x = (startX + dx).toInt()
                        .coerceIn(0, (screen.x - view.width).coerceAtLeast(0))
                    params.y = (startY + dy).toInt()
                        .coerceIn(0, (screen.y - view.height).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (moved) savePos() else openMain()
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun updateClock() {
        val view = overlay ?: return
        val now = System.currentTimeMillis()
        view.findViewById<TextView>(R.id.tvDate).text = dateFmt.format(now)
        view.findViewById<TextView>(R.id.tvTime).text = timeFmt.format(now)
    }

    /** 背景（颜色+透明度+圆角）与文字（对比色+字号）统一在此应用 */
    private fun applyAppearance() {
        val view = overlay ?: return
        val base = Prefs.color(this)
        val alpha = (Prefs.alpha(this) * 255 / 100).coerceIn(0, 255)
        view.background = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(
                Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
            )
        }
        // 按背景亮度选黑/白文字，保证可读
        val textColor = if (luminance(base) > 0.6f) Color.BLACK else Color.WHITE
        val fontSp = Prefs.fontSp(this).toFloat()
        view.findViewById<TextView>(R.id.tvTime).apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        }
        view.findViewById<TextView>(R.id.tvDate).apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp * 0.55f)
        }
        styleChips()
    }

    /** 按 Prefs 重建字幕 chip 列表；内容未变时跳过，不打断进行中的高亮 */
    private fun rebuildChips() {
        val view = overlay ?: return
        val container = view.findViewById<LinearLayout>(R.id.chipsContainer)
        val captions = Prefs.captions(this)
        if (captions == lastCaptions) return
        lastCaptions = captions

        // 清理旧高亮状态，避免恢复任务指向已移除的视图
        revertRunnable?.let { handler.removeCallbacks(it) }
        revertRunnable = null
        activeChip = null

        container.removeAllViews()
        val density = resources.displayMetrics.density
        captions.forEach { caption ->
            val chip = TextView(this).apply {
                text = caption
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (2 * density).toInt()
                    bottomMargin = (2 * density).toInt()
                }
                setPadding(
                    (10 * density).toInt(), (4 * density).toInt(),
                    (10 * density).toInt(), (4 * density).toInt()
                )
            }
            chip.setOnClickListener { onChipTapped(caption, chip) }
            container.addView(chip)
        }
        container.visibility = if (captions.isEmpty()) View.GONE else View.VISIBLE
        styleChips()
    }

    /** 全部 chip 统一样式（保持当前高亮项的覆盖态） */
    private fun styleChips() {
        val view = overlay ?: return
        view.findViewById<LinearLayout>(R.id.chipsContainer)
            .children.forEach { styleChip(it as TextView, it === activeChip) }
    }

    private fun styleChip(chip: TextView, highlighted: Boolean) {
        val base = Prefs.color(this)
        val lightBg = luminance(base) > 0.6f
        chip.setTextColor(if (highlighted) Color.WHITE else chipTextColor(base))
        chip.setTypeface(null, if (highlighted) Typeface.BOLD else Typeface.NORMAL)
        chip.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            (Prefs.fontSp(this) * 0.5f).coerceAtLeast(9f)
        )
        chip.background = GradientDrawable().apply {
            cornerRadius = 8f
            setColor(
                when {
                    highlighted -> HIGHLIGHT_COLOR
                    lightBg -> 0x22000000 // 浅底用半透明黑
                    else -> 0x33FFFFFF     // 深底用半透明白
                }
            )
        }
    }

    private fun chipTextColor(base: Int): Int =
        if (luminance(base) > 0.6f) Color.BLACK else Color.WHITE

    /** 点击字幕：高亮数秒自动恢复，并追加一条 SRT 记录（后台线程落盘） */
    private fun onChipTapped(caption: String, chip: TextView) {
        highlightChip(chip)
        val at = System.currentTimeMillis()
        ioHandler?.post {
            when (val result = MarkerWriter.appendSrtLine(this, caption, at)) {
                is SrtResult.Renamed -> {
                    // 卸载重装等 uid 变化导致记录文件被系统改名，提示用户合并旧文件
                    handler.post {
                        Toast.makeText(
                            this,
                            getString(R.string.toast_srt_renamed, result.actualName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                else -> Unit
            }
        }
    }

    private fun highlightChip(chip: TextView) {
        revertRunnable?.let { handler.removeCallbacks(it) }
        activeChip?.let { styleChip(it, false) }
        activeChip = chip
        styleChip(chip, true)
        val revert = Runnable {
            activeChip?.let { styleChip(it, false) }
            activeChip = null
            revertRunnable = null
        }
        revertRunnable = revert
        handler.postDelayed(revert, HIGHLIGHT_MS)
    }

    private fun luminance(color: Int): Float =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f

    private fun savePos() {
        Prefs.setPos(this, params.x, params.y)
    }

    @Suppress("DEPRECATION")
    private fun screenRealSize(): Point = Point().also { wm.defaultDisplay.getRealSize(it) }
}
