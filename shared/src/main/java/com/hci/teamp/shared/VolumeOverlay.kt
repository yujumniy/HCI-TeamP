package com.hci.teamp.shared

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 다른 앱 위에 뜨는 볼륨 표시 창.
 * 폰: 화면 위쪽 가운데 / 워치: 화면 정가운데. 마지막 변경 후 2초 뒤 사라집니다.
 */
class VolumeOverlay(context: Context) {
    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val isWatch = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

    private var root: LinearLayout? = null
    private lateinit var title: TextView
    private lateinit var percent: TextView
    private lateinit var detail: TextView
    private lateinit var bar: ProgressBar

    private val hideRunnable = Runnable { hide() }

    fun canDraw(): Boolean = Settings.canDrawOverlays(ctx)

    /** source: "폰" 또는 "워치" (어느 기기의 볼륨인지) */
    fun show(source: String, info: VolumeInfo) {
        if (!canDraw()) return
        if (root == null) attach()
        title.text = "$source · ${info.label}"
        percent.text = "${info.percent}%"
        detail.text = "${info.step} / ${info.steps}단계"
        bar.max = info.steps.coerceAtLeast(1)
        bar.progress = info.step
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun attach() {
        val pad = dp(if (isWatch) 12 else 16)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            minimumWidth = dp(if (isWatch) 140 else 220)
            background = GradientDrawable().apply {
                cornerRadius = dp(if (isWatch) 28 else 20).toFloat()
                setColor(Color.argb(230, 24, 24, 28))
            }
        }
        title = text(if (isWatch) 12f else 14f, Color.argb(200, 255, 255, 255))
        percent = text(if (isWatch) 30f else 36f, Color.WHITE, bold = true)
        detail = text(if (isWatch) 12f else 14f, Color.argb(200, 255, 255, 255))
        bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
        }
        layout.addView(title)
        layout.addView(percent)
        layout.addView(detail)
        layout.addView(
            bar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply {
                topMargin = dp(8)
            },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (isWatch) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = if (isWatch) 0 else dp(64)
        }
        wm.addView(layout, params)
        root = layout
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private companion object {
        const val HIDE_DELAY_MS = 2000L
    }
}
