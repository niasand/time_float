package com.timefloat.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 设置页：悬浮窗权限引导、显示开关、圆盘取色（色相+饱和度+明度）、透明度与字号调节。
 * 所有改动写入 Prefs，运行中的悬浮窗通过监听即时生效。
 */
class MainActivity : Activity() {

    /** 开关联动的唯一逻辑：未授权时强制回弹；否则落库并启停服务 */
    private val switchToggleListener =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            if (checked && !Settings.canDrawOverlays(this)) {
                setSwitchSilently(false)
                return@OnCheckedChangeListener
            }
            Prefs.setEnabled(this, checked)
            if (checked) OverlayService.start(this) else OverlayService.stop(this)
        }

    private lateinit var tvPermStatus: TextView
    private lateinit var btnGrant: Button
    private lateinit var switchShow: Switch
    private lateinit var wheel: ColorWheelView
    private lateinit var tvPreview: TextView
    private lateinit var sbBright: SeekBar
    private lateinit var tvBrightValue: TextView
    private lateinit var sbAlpha: SeekBar
    private lateinit var tvAlphaValue: TextView
    private lateinit var sbFont: SeekBar
    private lateinit var tvFontValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        initColorPicker()
        initSeekBars()
        requestNotifPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        // 自愈：开关为开、权限在、但服务被系统/重装杀掉时，自动拉起
        if (Prefs.isEnabled(this) && Settings.canDrawOverlays(this) && !OverlayService.isRunning) {
            OverlayService.start(this)
        }
        refreshPreview()
    }

    private fun bindViews() {
        tvPermStatus = findViewById(R.id.tvPermStatus)
        btnGrant = findViewById(R.id.btnGrant)
        switchShow = findViewById(R.id.switchShow)
        wheel = findViewById(R.id.colorWheel)
        tvPreview = findViewById(R.id.tvPreview)
        sbBright = findViewById(R.id.sbBright)
        tvBrightValue = findViewById(R.id.tvBrightValue)
        sbAlpha = findViewById(R.id.sbAlpha)
        tvAlphaValue = findViewById(R.id.tvAlphaValue)
        sbFont = findViewById(R.id.sbFont)
        tvFontValue = findViewById(R.id.tvFontValue)

        btnGrant.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        switchShow.setOnCheckedChangeListener(switchToggleListener)
    }

    /** 授权状态回来后刷新权限提示与开关可用态 */
    private fun refreshPermissionState() {
        val granted = Settings.canDrawOverlays(this)
        tvPermStatus.text = getString(if (granted) R.string.perm_granted else R.string.perm_needed)
        btnGrant.visibility = if (granted) View.GONE else View.VISIBLE
        switchShow.isEnabled = granted
        setSwitchSilently(Prefs.isEnabled(this) && granted)
    }

    private fun setSwitchSilently(checked: Boolean) {
        switchShow.setOnCheckedChangeListener(null)
        switchShow.isChecked = checked
        switchShow.setOnCheckedChangeListener(switchToggleListener)
    }

    private fun initColorPicker() {
        wheel.setColor(Prefs.color(this))
        sbBright.progress = (wheel.brightness() * 100).toInt()
        tvBrightValue.text = "${sbBright.progress}%"
        refreshBrightnessBar()

        wheel.onColorPicked = { color ->
            Prefs.setColor(this, color)
            // 色相/饱和度变了，明度条渐变终点跟着变
            refreshBrightnessBar()
            refreshPreview()
        }
        sbBright.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBrightValue.text = "$progress%"
                wheel.setBrightness(progress / 100f)
                Prefs.setColor(this@MainActivity, wheel.currentColor())
                refreshPreview()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    /** 明度条：黑 → 当前最亮色 的渐变，进度按 ClipDrawable 裁剪；必须给固有尺寸否则不可见 */
    private fun refreshBrightnessBar() {
        val density = resources.displayMetrics.density
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.BLACK, wheel.fullBrightnessColor())
        ).apply { setSize((density * 12).toInt(), (density * 12).toInt()) }
        sbBright.progressDrawable =
            ClipDrawable(gradient, Gravity.START, ClipDrawable.HORIZONTAL)
    }

    /** 实时预览：按当前 颜色×透明度+字号+对比文字色 渲染，与悬浮窗同款效果 */
    private fun refreshPreview() {
        val base = Prefs.color(this)
        val alpha = (Prefs.alpha(this) * 255 / 100).coerceIn(0, 255)
        tvPreview.background = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)))
        }
        val lum = (0.299f * Color.red(base) + 0.587f * Color.green(base) + 0.114f * Color.blue(base)) / 255f
        tvPreview.setTextColor(if (lum > 0.6f) Color.BLACK else Color.WHITE)
        tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.fontSp(this).toFloat())
    }

    private fun initSeekBars() {
        // 进度 = 实际值 - MIN，max 已在 XML 里设为 (MAX - MIN)
        sbAlpha.progress = Prefs.alpha(this) - Prefs.MIN_ALPHA
        tvAlphaValue.text = "${Prefs.alpha(this)}%"
        sbAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + Prefs.MIN_ALPHA
                tvAlphaValue.text = "$value%"
                Prefs.setAlpha(this@MainActivity, value)
                refreshPreview()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        sbFont.progress = Prefs.fontSp(this) - Prefs.MIN_FONT_SP
        tvFontValue.text = "${Prefs.fontSp(this)}sp"
        sbFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + Prefs.MIN_FONT_SP
                tvFontValue.text = "${value}sp"
                Prefs.setFontSp(this@MainActivity, value)
                refreshPreview()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    /** API 33+ 前台服务通知需要运行时通知权限 */
    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
