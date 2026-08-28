package com.timefloat.app

import android.content.Context
import android.content.SharedPreferences

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
}
