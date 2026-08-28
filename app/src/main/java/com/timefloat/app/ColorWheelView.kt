package com.timefloat.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HSV 圆盘取色器：角度=色相，半径=饱和度（中心白），明度固定 1（所见即所得）。
 * 零依赖自绘：色相环位图只在尺寸变化时重建一次。
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 拖动选色回调（不含 alpha） */
    var onColorPicked: ((Int) -> Unit)? = null

    private var hue = 0f
    private var sat = 1f

    private val hsv = FloatArray(3)

    private var wheelBitmap: Bitmap? = null
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    private val pointerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pointerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.BLACK
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = min(cx, cy) - 12f // 留出指针空间
        buildWheel()
    }

    /** 色相 SweepGradient 叠加中心向外的白色径向渐变（中心趋白 = 低饱和） */
    private fun buildWheel() {
        if (radius <= 0f) return
        val size = (radius * 2).toInt()
        val half = size / 2f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 0° 在 3 点钟方向顺时针，与触摸角度计算保持一致
        val colors = IntArray(13) { Color.HSVToColor(floatArrayOf(it * 30f, 1f, 1f)) }
        canvas.drawCircle(
            half, half, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = SweepGradient(half, half, colors, null)
            }
        )
        canvas.drawCircle(
            half, half, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    half, half, radius,
                    Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
            }
        )
        wheelBitmap = bitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        wheelBitmap?.let { canvas.drawBitmap(it, cx - radius, cy - radius, null) }
        val angle = Math.toRadians(hue.toDouble())
        val px = cx + (cos(angle) * sat * radius).toFloat()
        val py = cy + (sin(angle) * sat * radius).toFloat()
        canvas.drawCircle(px, py, 12f, pointerFill)
        canvas.drawCircle(px, py, 12f, pointerStroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_MOVE
        ) {
            val dx = event.x - cx
            val dy = event.y - cy
            val dist = sqrt(dx * dx + dy * dy).coerceAtMost(radius)
            hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
            sat = if (radius <= 0f) 0f else dist / radius
            onColorPicked?.invoke(currentColor())
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        onColorPicked?.invoke(currentColor())
        return super.performClick()
    }

    /** 外部同步当前颜色（初始化定位指针用，明度被忽略） */
    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        sat = hsv[1]
        invalidate()
    }

    fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, 1f))
}
