package com.timefloat.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * 设置页：悬浮窗权限引导、显示开关、预设色板 + HEX 输入、透明度与字号调节。
 * 所有改动写入 Prefs，运行中的悬浮窗通过监听即时生效。
 */
class MainActivity : Activity() {

    /** 预设色板，第一个为默认深灰 */
    private val presetColors = intArrayOf(
        0xFF2E2E2E.toInt(), // 深灰（默认）
        0xFF000000.toInt(), // 纯黑
        0xFF1A237E.toInt(), // 藏青
        0xFF1B5E20.toInt(), // 墨绿
        0xFF8E1418.toInt(), // 酒红
        0xFF4A148C.toInt(), // 紫
        0xFF4E342E.toInt(), // 棕
        0xFF00695C.toInt(), // 青
    )

    private lateinit var tvPermStatus: TextView
    private lateinit var btnGrant: Button
    private lateinit var switchShow: Switch
    private lateinit var etHex: EditText
    private lateinit var sbAlpha: SeekBar
    private lateinit var tvAlphaValue: TextView
    private lateinit var sbFont: SeekBar
    private lateinit var tvFontValue: TextView
    private val swatches = mutableListOf<View>()

    private val hexPattern = Regex("^[0-9A-Fa-f]{6}$")

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        buildSwatches()
        initSeekBars()
        requestNotifPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun bindViews() {
        tvPermStatus = findViewById(R.id.tvPermStatus)
        btnGrant = findViewById(R.id.btnGrant)
        switchShow = findViewById(R.id.switchShow)
        etHex = findViewById(R.id.etHex)
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
        findViewById<Button>(R.id.btnApply).setOnClickListener { applyHex() }
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

    private fun buildSwatches() {
        val row = findViewById<LinearLayout>(R.id.swatchRow)
        row.removeAllViews()
        swatches.clear()
        val dp = resources.displayMetrics.density
        presetColors.forEach { color ->
            val swatch = View(this)
            swatch.layoutParams = LinearLayout.LayoutParams(
                (36 * dp).roundToInt(), (36 * dp).roundToInt()
            ).apply {
                weight = 1f
                marginStart = (3 * dp).roundToInt()
                marginEnd = (3 * dp).roundToInt()
            }
            swatch.background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(color)
            }
            swatch.setOnClickListener {
                Prefs.setColor(this@MainActivity, color)
                highlightSwatch(color)
            }
            row.addView(swatch)
            swatches.add(swatch)
        }
        highlightSwatch(Prefs.color(this))
    }

    /** 当前色对应的色块加白色描边；HEX 自定义色无匹配时全部取消描边 */
    private fun highlightSwatch(color: Int) {
        val dp = resources.displayMetrics.density
        swatches.forEachIndexed { i, v ->
            (v.background as GradientDrawable).apply {
                if (presetColors[i] == color) {
                    setStroke((2 * dp).roundToInt(), Color.WHITE)
                } else {
                    setStroke(0, Color.TRANSPARENT)
                }
            }
        }
    }

    private fun applyHex() {
        val raw = etHex.text.toString().trim().removePrefix("#")
        if (!hexPattern.matches(raw)) {
            Toast.makeText(this, R.string.hex_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val color = 0xFF000000.toInt() or raw.toInt(16)
        Prefs.setColor(this, color)
        highlightSwatch(color)
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
