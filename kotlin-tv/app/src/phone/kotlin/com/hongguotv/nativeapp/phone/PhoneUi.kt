// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.phone

// 手机版公共样式与小部件：竖屏触控尺寸、海报卡片、异步任务作用域。

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.RequestScope
import com.hongguotv.nativeapp.TvStyle

internal object PhoneStyle {
    val background = TvStyle.background
    val surface = TvStyle.surface
    val raised = TvStyle.raised
    val accent = TvStyle.accent
    val text = TvStyle.text
    val muted = TvStyle.muted
    val outline = TvStyle.outline
    const val POSTER_ASPECT = TvStyle.POSTER_ASPECT
}

internal fun Context.dp(value: Number) = (value.toFloat() * resources.displayMetrics.density).toInt()

internal fun Context.rounded(color: Int, radiusDp: Int = 10, border: Int = Color.TRANSPARENT) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (border != Color.TRANSPARENT) setStroke(dp(1), border)
    }

internal fun Context.label(
    value: String,
    size: Float = 14f,
    color: Int = PhoneStyle.text,
    bold: Boolean = false,
    lines: Int = 0,
) =
    TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (lines > 0) {
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }
    }

/** Rounded touch button, at least 40dp high. */
internal fun Context.pill(
    value: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) =
    label(value, 14f, if (selected) PhoneStyle.background else PhoneStyle.text, selected).apply {
        gravity = Gravity.CENTER
        minHeight = dp(36)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(if (selected) PhoneStyle.accent else PhoneStyle.surface, 18)
        setOnClickListener { onClick() }
    }

internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

internal fun Context.row() =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

internal fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
    LinearLayout.LayoutParams(w, h)

internal fun frame(w: Int = -1, h: Int = -1, gravity: Int = Gravity.NO_GRAVITY) =
    FrameLayout.LayoutParams(w, h, gravity)

/** Poster card: cover, title and a one-line badge. Width is set by the parent. */
internal class PosterCard(context: Context) : LinearLayout(context) {
    val cover =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = context.rounded(PhoneStyle.surface, 8)
            clipToOutline = true
        }
    val corner =
        context.label("", 11f, PhoneStyle.background, bold = true).apply {
            setPadding(context.dp(6), context.dp(3), context.dp(6), context.dp(3))
            background = context.rounded(PhoneStyle.accent, 6)
            visibility = View.GONE
        }
    val title = context.label("", 13f, lines = 2).apply { minLines = 2 }
    val badge = context.label("", 11f, PhoneStyle.muted, lines = 1)

    init {
        orientation = VERTICAL
        val art = FrameLayout(context)
        art.addView(cover, frame())
        art.addView(corner, frame(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(context.dp(4), context.dp(4), 0, 0)
        })
        addView(art, lp(-1, 0).apply { weight = 0f })
        addView(title, lp().apply { topMargin = context.dp(6) })
        addView(badge, lp().apply { topMargin = context.dp(3) })
        isClickable = true
        foreground = context.getDrawable(android.R.drawable.list_selector_background)
    }

    fun sizeFor(width: Int) {
        (getChildAt(0).layoutParams as LayoutParams).height = (width / PhoneStyle.POSTER_ASPECT).toInt()
    }
}

/**
 * Background work owned by one page. Results arriving after [cancel] (or after the activity is gone)
 * are dropped, and Closeable results are closed so no stream leaks.
 */
internal class PageWork(private val host: PhoneActivity) {
    private var generation = 0
    private val scopes = mutableSetOf<RequestScope>()

    fun <T> run(action: () -> T, done: (T) -> Unit, failed: (Throwable) -> Unit = {}) {
        val ticket = generation
        val scope = RequestScope()
        scopes += scope
        host.io.execute {
            val result = runCatching { scope.run(action) }
            host.main.post {
                scopes -= scope
                if (ticket != generation || host.isDestroyed) {
                    (result.getOrNull() as? java.io.Closeable)?.close()
                    return@post
                }
                result.fold(done, failed)
            }
        }
    }

    fun cancel() {
        generation++
        scopes.forEach { it.cancel() }
        scopes.clear()
    }
}

internal fun friendlyError(problem: Throwable): String =
    when {
        problem is java.net.UnknownHostException -> "无法连接网络，请检查网络后重试"
        problem is java.net.SocketTimeoutException -> "连接超时，请稍后重试"
        else -> problem.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(80).ifBlank { "加载失败，请重试" }
    }
