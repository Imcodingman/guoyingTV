// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.phone

// 我的：继续观看（本机进度）、我的收藏、默认清晰度。手机版始终连播下一集。

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.hongguotv.core.PlaybackQuality

internal class MinePage(host: PhoneActivity) : PhonePage(host) {
    private val body = host.column().apply { setPadding(0, host.dp(12), 0, host.dp(24)) }
    override val view: View =
        ScrollView(host).apply {
            setBackgroundColor(PhoneStyle.background)
            addView(body)
        }

    override fun onShow() = render()

    private fun section(title: String, action: String? = null, onAction: () -> Unit = {}) {
        val head = host.row().apply { setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(10)) }
        head.addView(host.label(title, 17f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) head.addView(host.label(action, 13f, PhoneStyle.muted).apply { setOnClickListener { onAction() } })
        body.addView(head)
    }

    private fun render() {
        body.removeAllViews()
        body.addView(host.label("我的", 24f, bold = true).apply { setPadding(host.dp(16), host.dp(8), host.dp(16), 0) })
        if (!host.library.isLoaded) {
            body.addView(host.label("正在读取本机记录…", 14f, PhoneStyle.muted).apply { setPadding(host.dp(16), host.dp(24), 0, 0) })
            return
        }
        val library = host.library
        val history = library.history()
        section("继续观看", if (history.isNotEmpty()) "清空" else null) {
            AlertDialog.Builder(host).setMessage("清空本机全部观看记录？收藏会保留。")
                .setPositiveButton("清空") { _, _ ->
                    library.clearHistory()
                    render()
                }
                .setNegativeButton("取消", null).show()
        }
        if (history.isEmpty()) empty("看过的剧会出现在这里")
        else {
            val strip = host.row().apply { setPadding(host.dp(16), 0, host.dp(8), 0) }
            val width = host.dp(104)
            history.take(30).forEach { progress ->
                val card = PosterCard(host)
                card.sizeFor(width)
                card.title.text = progress.series.title
                card.badge.text = if (progress.completed) "第 ${progress.episodeIndex + 1} 集 · 已看完" else "看到第 ${progress.episodeIndex + 1} 集"
                host.artwork.load(card.cover, progress.series.cover, widthPx = width, heightPx = (width / PhoneStyle.POSTER_ASPECT).toInt())
                val art = card.getChildAt(0) as FrameLayout
                if (progress.duration > 0)
                    art.addView(
                        ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
                            max = 100
                            this.progress = (progress.position * 100 / progress.duration).toInt().coerceIn(0, 100)
                            progressTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
                        },
                        frame(-1, host.dp(3), Gravity.BOTTOM),
                    )
                card.setOnClickListener { host.play(progress.series) }
                card.setOnLongClickListener {
                    AlertDialog.Builder(host).setTitle(progress.series.title)
                        .setItems(arrayOf("查看详情", "删除这条记录")) { _, which ->
                            if (which == 0) host.openDetail(progress.series)
                            else {
                                library.removeHistory(progress.series.id)
                                render()
                            }
                        }.show()
                    true
                }
                strip.addView(card, lp(width, -2).apply { rightMargin = host.dp(10) })
            }
            body.addView(HorizontalScrollView(host).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            })
        }

        val favorites = library.favorites()
        section("我的收藏 · ${favorites.size}")
        if (favorites.isEmpty()) empty("在推荐页或详情页点「收藏」，喜欢的剧会出现在这里")
        else {
            val columns = 3
            val gap = host.dp(10)
            val width = (host.resources.displayMetrics.widthPixels - host.dp(32) - gap * (columns - 1)) / columns
            favorites.chunked(columns).forEach { group ->
                val line = host.row().apply {
                    gravity = Gravity.TOP
                    setPadding(host.dp(16), 0, host.dp(16), host.dp(14))
                }
                group.forEachIndexed { index, series ->
                    val card = PosterCard(host)
                    card.sizeFor(width)
                    card.title.text = series.title
                    card.badge.text = library.progress(series.id)?.let { "看到第 ${it.episodeIndex + 1} 集" } ?: series.badge
                    host.artwork.load(card.cover, series.cover, widthPx = width, heightPx = (width / PhoneStyle.POSTER_ASPECT).toInt())
                    card.setOnClickListener { host.openDetail(series) }
                    card.setOnLongClickListener {
                        AlertDialog.Builder(host).setMessage("取消收藏《${series.title}》？")
                            .setPositiveButton("取消收藏") { _, _ ->
                                library.toggle(series)
                                render()
                            }
                            .setNegativeButton("保留", null).show()
                        true
                    }
                    line.addView(card, lp(width, -2).apply { if (index < columns - 1) rightMargin = gap })
                }
                body.addView(line)
            }
        }

        section("播放")
        setting("最高清晰度", "${library.maxQuality}P") {
            val options = PlaybackQuality.options
            AlertDialog.Builder(host).setTitle("最高清晰度")
                .setSingleChoiceItems(options.map { "${it}P" }.toTypedArray(), options.indexOf(library.maxQuality)) { dialog, index ->
                    library.maxQuality = options[index]
                    dialog.dismiss()
                    render()
                }.show()
        }
        body.addView(
            host.label("观看记录与收藏只保存在本机 · 果映手机版", 12f, PhoneStyle.muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, host.dp(24), 0, 0)
            },
            lp(),
        )
    }

    private fun empty(message: String) =
        body.addView(host.label(message, 13f, PhoneStyle.muted).apply { setPadding(host.dp(16), 0, host.dp(16), host.dp(8)) })

    private fun setting(name: String, value: String, onClick: () -> Unit) {
        val line = host.row().apply {
            setPadding(host.dp(16), host.dp(14), host.dp(16), host.dp(14))
            setOnClickListener { onClick() }
        }
        line.addView(host.label(name, 15f), LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(host.label("$value  ›", 14f, PhoneStyle.muted))
        body.addView(line)
    }
}
