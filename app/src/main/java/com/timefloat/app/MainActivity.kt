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
import android.view.LayoutInflater
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

/**
 * 设置页：悬浮窗权限引导、显示开关、圆盘取色（色相+饱和度）、透明度与字号调节、
 * 字幕标记管理。所有改动写入 Prefs，运行中的悬浮窗通过监听即时生效。
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
    private lateinit var vPreview: View
    private lateinit var sbAlpha: SeekBar
    private lateinit var tvAlphaValue: TextView
    private lateinit var sbFont: SeekBar
    private lateinit var tvFontValue: TextView
    private lateinit var etCaption: EditText
    private lateinit var btnAddCaption: Button
    private lateinit var captionList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        initColorPicker()
        initSeekBars()
        initCaptions()
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
        vPreview = findViewById(R.id.vPreview)
        sbAlpha = findViewById(R.id.sbAlpha)
        tvAlphaValue = findViewById(R.id.tvAlphaValue)
        sbFont = findViewById(R.id.sbFont)
        tvFontValue = findViewById(R.id.tvFontValue)
        etCaption = findViewById(R.id.etCaption)
        btnAddCaption = findViewById(R.id.btnAddCaption)
        captionList = findViewById(R.id.captionList)

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
        wheel.onColorPicked = { color ->
            Prefs.setColor(this, color)
            refreshPreview()
        }
    }

    /** 实时预览：颜色×透明度 渲染的圆角色块 */
    private fun refreshPreview() {
        val base = Prefs.color(this)
        val alpha = (Prefs.alpha(this) * 255 / 100).coerceIn(0, 255)
        vPreview.background = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)))
        }
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

    /** 字幕标记管理：添加校验 + 列表渲染，落库后悬浮窗经 prefs 监听即时重建 */
    private fun initCaptions() {
        renderCaptionRows()
        btnAddCaption.setOnClickListener {
            val text = etCaption.text.toString().trim()
            when {
                text.isEmpty() -> toast(getString(R.string.toast_caption_empty))
                text.length > Prefs.MAX_CAPTION_LEN ->
                    toast(getString(R.string.toast_caption_len, Prefs.MAX_CAPTION_LEN))
                else -> {
                    val list = Prefs.captions(this)
                    when {
                        text in list -> toast(getString(R.string.toast_caption_dup))
                        list.size >= Prefs.MAX_CAPTIONS ->
                            toast(getString(R.string.toast_caption_full, Prefs.MAX_CAPTIONS))
                        else -> {
                            list.add(text)
                            Prefs.setCaptions(this, list)
                            etCaption.setText("")
                            renderCaptionRows()
                        }
                    }
                }
            }
        }
    }

    private fun renderCaptionRows() {
        captionList.removeAllViews()
        Prefs.captions(this).forEach { text ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_caption, captionList, false)
            row.findViewById<TextView>(R.id.tvCaptionText).text = text
            row.findViewById<Button>(R.id.btnDeleteCaption).setOnClickListener {
                Prefs.setCaptions(this, Prefs.captions(this).filterNot { it == text })
                renderCaptionRows()
            }
            captionList.addView(row)
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
