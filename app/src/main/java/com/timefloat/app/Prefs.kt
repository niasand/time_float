package com.timefloat.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 全部持久化状态的唯一入口，设置页与悬浮窗服务共享。
 * 数值约束常量（MIN/MAX）供双方统一取值范围。
 */
object Prefs {

    private const val FILE = "timefloat"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_COLOR = "color"
    private const val KEY_ALPHA = "alpha"     // 不透明度 20..100（%）
    private const val KEY_FONT_SP = "font_sp" // 时间字号 12..48（sp）
    private const val KEY_POS_X = "pos_x"     // -1 表示从未设置过，首显时居中
    private const val KEY_POS_Y = "pos_y"

    /** 字幕标记列表（JSON 数组）。公开供服务按 key 分发监听 */
    const val KEY_CAPTIONS = "captions"

    const val MAX_CAPTIONS = 10     // 字幕条数上限，防止悬浮窗占满屏幕
    const val MAX_CAPTION_LEN = 12  // 单条字幕长度上限（字符）

    const val DEFAULT_COLOR = 0xFF2E2E2E.toInt() // 深灰
    const val DEFAULT_ALPHA = 100
    const val DEFAULT_FONT_SP = 24
    const val MIN_ALPHA = 20
    const val MIN_FONT_SP = 12
    const val MAX_FONT_SP = 48

    fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun color(context: Context): Int = sp(context).getInt(KEY_COLOR, DEFAULT_COLOR)

    fun setColor(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_COLOR, value).apply()
    }

    fun alpha(context: Context): Int = sp(context).getInt(KEY_ALPHA, DEFAULT_ALPHA)

    fun setAlpha(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_ALPHA, value.coerceIn(MIN_ALPHA, 100)).apply()
    }

    fun fontSp(context: Context): Int = sp(context).getInt(KEY_FONT_SP, DEFAULT_FONT_SP)

    fun setFontSp(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_FONT_SP, value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)).apply()
    }

    fun posX(context: Context): Int = sp(context).getInt(KEY_POS_X, -1)

    fun posY(context: Context): Int = sp(context).getInt(KEY_POS_Y, -1)

    fun setPos(context: Context, x: Int, y: Int) {
        sp(context).edit().putInt(KEY_POS_X, x).putInt(KEY_POS_Y, y).apply()
    }

    /** 字幕标记列表；存量数据损坏时按空列表处理 */
    fun captions(context: Context): MutableList<String> {
        val raw = sp(context).getString(KEY_CAPTIONS, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { arr.optString(it) }
        }.getOrDefault(mutableListOf())
    }

    /** 落库前统一清洗：去首尾空白、去空项、去重、截断条数上限 */
    fun setCaptions(context: Context, list: List<String>) {
        val cleaned = list.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_CAPTIONS)
        sp(context).edit().putString(KEY_CAPTIONS, JSONArray(cleaned).toString()).apply()
    }
}
