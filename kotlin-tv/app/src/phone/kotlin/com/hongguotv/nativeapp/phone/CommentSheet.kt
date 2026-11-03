// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.phone

// 评论底部面板：本集评论（游标续页）与整剧剧评（官网节选）；匿名只读，不发送、不回复。

import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongguotv.core.AppCommentPages
import com.hongguotv.core.PublicComments
import com.hongguotv.nativeapp.CommentEmoji
import org.json.JSONObject

internal class CommentSheet(
    private val host: PhoneActivity,
    private val seriesId: String,
    private val episodeId: String,
    episodeLabel: String,
    private val onClose: () -> Unit,
) {
    private companion object {
        val reviewHttp = okhttp3.OkHttpClient()
    }

    private val work = PageWork(host)
    private val emoji = CommentEmoji(host)
    private val list = host.column()
    private val status = host.label("正在读取…", 12f, PhoneStyle.muted)
    private val footer =
        host.label("", 13f, PhoneStyle.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, host.dp(14), 0, host.dp(18))
            setOnClickListener { read() }
        }
    private val tabs = mutableListOf<TextView>()
    private var reviews = false
    private var cursor = ""
    private var more = true
    private var busy = false
    private val seen = mutableSetOf<String>()
    private val cursors = mutableSetOf<String>()

    private val scroll =
        ScrollView(host).apply {
            addView(host.column().apply {
                addView(list)
                addView(footer, lp())
            })
            setOnScrollChangeListener { _, _, y, _, _ ->
                val content = getChildAt(0)
                if (!reviews && y > 0 && y + height >= content.height - host.dp(120)) read()
            }
        }

    val view: View =
        host.column().apply {
            isClickable = true
            background = host.rounded(PhoneStyle.surface, 16)
            setPadding(host.dp(16), host.dp(14), host.dp(16), 0)
            val head = host.row()
            head.addView(host.label("评论 · $episodeLabel", 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(host.label("关闭", 14f, PhoneStyle.muted).apply {
                setPadding(host.dp(12), host.dp(6), 0, host.dp(6))
                setOnClickListener { onClose() }
            })
            addView(head)
            val bar = host.row().apply { setPadding(0, host.dp(10), 0, host.dp(6)) }
            listOf("本集评论" to false, "整剧剧评" to true).forEach { (name, mode) ->
                val tab = host.label(name, 14f).apply {
                    setPadding(0, host.dp(4), host.dp(18), host.dp(4))
                    setOnClickListener { select(mode) }
                }
                tabs += tab
                bar.addView(tab)
            }
            addView(bar)
            addView(status, lp().apply { bottomMargin = host.dp(8) })
            addView(scroll, lp(-1, 0).apply { weight = 1f })
        }

    init {
        select(false)
    }

    private fun select(mode: Boolean) {
        work.cancel()
        reviews = mode
        busy = false
        cursor = ""
        more = true
        seen.clear()
        cursors.clear()
        list.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val active = (index == 1) == mode
            tab.setTextColor(if (active) PhoneStyle.text else PhoneStyle.muted)
            tab.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        status.text = if (mode) "整剧剧评为官网节选" else "匿名读取 · 只读，不能发送或回复"
        read()
    }

    private fun read() {
        if (busy || !more) return
        busy = true
        footer.text = "正在读取…"
        val sent = cursor
        val mode = reviews
        work.run(
            {
                if (mode) PublicComments(reviewHttp).read(seriesId, episodeId).getJSONObject("reviews")
                else AppCommentPages().read(seriesId, episodeId, sent)
            },
            { page ->
                if (mode != reviews) return@run
                busy = false
                val rows = page.getJSONArray("items")
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    if (seen.add(row.optString("id"))) addRow(row)
                }
                more = !mode && page.optBoolean("hasMore")
                if (more) {
                    val next = page.optString("nextCursor")
                    if (next.isBlank() || !cursors.add(next)) more = false
                    cursor = next
                }
                val total = page.optLong("total")
                status.text =
                    if (mode) "官网剧评节选 ${seen.size} 条 / 共 $total 条"
                    else "已加载 ${seen.size} 条 / 共 $total 条 · 只读"
                footer.text =
                    when {
                        seen.isEmpty() -> if (mode) "暂无剧评" else "这一集还没有评论"
                        more -> "上滑加载更多"
                        else -> "已经到底了"
                    }
            },
            { problem ->
                if (mode != reviews) return@run
                busy = false
                footer.text = "${friendlyError(problem)}，点击重试"
            },
        )
    }

    private fun addRow(row: JSONObject) {
        val created = row.optLong("created")
        val date =
            if (created > 0) java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(created * 1000))
            else ""
        val author = row.optString("author").ifBlank { "红果用户" }
        val content = row.optString("content")
        val card = host.column().apply { setPadding(0, host.dp(10), 0, host.dp(12)) }
        card.addView(host.label(listOf(author, "赞 ${row.optLong("likes")}", date).filter { it.isNotBlank() }.joinToString("  ·  "), 12f, PhoneStyle.muted, lines = 1))
        val body =
            host.label("", 15f, lines = 6).apply {
                text = emoji.render(content, textSize * 1.2f)
                setLineSpacing(host.dp(4).toFloat(), 1f)
            }
        card.addView(body, lp().apply { topMargin = host.dp(6) })
        card.setOnClickListener {
            AlertDialog.Builder(host).setTitle(author).setMessage(emoji.render(content, host.dp(20).toFloat())).setPositiveButton("关闭", null).show()
        }
        list.addView(card, lp())
        list.addView(View(host).apply { setBackgroundColor(PhoneStyle.outline) }, lp(-1, 1))
    }

    fun dispose() = work.cancel()
}

/** Adds [sheet] to [parent] as a bottom panel taking about 65% of the screen height. */
internal fun FrameLayout.addBottomSheet(host: PhoneActivity, sheet: View) =
    addView(sheet, frame(-1, (host.resources.displayMetrics.heightPixels * .65f).toInt(), Gravity.BOTTOM))
