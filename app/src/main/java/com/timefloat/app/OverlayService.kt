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
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * 前台服务：持有 WindowManager 悬浮窗，秒级刷新时间。
 * 颜色/透明度/字号变化通过 SharedPreferences 监听即时生效，无需重建悬浮窗。
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "overlay_clock"
        private const val NOTIF_ID = 1
        private const val CLICK_SLOP = 12 // dp，小于该位移视为点击

        const val ACTION_STOP = "com.timefloat.app.action.STOP"

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
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyAppearance() }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefsListener)
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
        handler.removeCallbacks(ticker)
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
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
        applyAppearance()
        handler.post(ticker)
    }

    /** 拖动更新悬浮窗位置；松手时按位移区分点击（回设置页）与拖动（存位置） */
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
    }

    private fun luminance(color: Int): Float =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f

    private fun savePos() {
        Prefs.setPos(this, params.x, params.y)
    }

    @Suppress("DEPRECATION")
    private fun screenRealSize(): Point = Point().also { wm.defaultDisplay.getRealSize(it) }
}
