// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.phone

// 剧集详情：封面、简介、收藏、继续观看，以及全部分集网格。

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.Detail
import com.hongguotv.core.Series

internal class DetailPage(host: PhoneActivity, private val series: Series) : PhonePage(host) {
    private var detail: Detail? = null
    private val body = host.column()
    override val view: View =
        host.column().apply {
            setBackgroundColor(PhoneStyle.background)
            isClickable = true
            addView(header(host, series.title) { host.pop(this@DetailPage) })
            addView(body, lp(-1, 0).apply { weight = 1f })
        }
    private var playButton: TextView? = null
    private var grid: EpisodeGrid? = null

    init {
        load()
    }

    private fun load() {
        body.removeAllViews()
        body.addView(host.label("正在加载剧集…", 15f, PhoneStyle.muted).apply { gravity = Gravity.CENTER }, lp(-1, -1))
        work.run(
            { host.repository.detail(series.id) },
            { result ->
                detail = result
                render(result)
            },
            { problem ->
                body.removeAllViews()
                body.addView(
                    host.label("${friendlyError(problem)}\n点击重试", 15f, PhoneStyle.muted).apply {
                        gravity = Gravity.CENTER
                        setOnClickListener { load() }
                    },
                    lp(-1, -1),
                )
            },
        )
    }

    private fun render(data: Detail) {
        body.removeAllViews()
        body.setPadding(host.dp(16), host.dp(8), host.dp(16), 0)
        val info = data.series
        val hero = host.row().apply { gravity = Gravity.TOP }
        val cover = ImageView(host).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = host.rounded(PhoneStyle.surface, 8)
            clipToOutline = true
        }
        hero.addView(cover, lp(host.dp(110), host.dp(157)).apply { rightMargin = host.dp(14) })
        host.artwork.load(cover, series.cover.ifBlank { info.cover })
        val text = host.column()
        text.addView(host.label(series.title.ifBlank { info.title }, 19f, bold = true, lines = 2))
        text.addView(
            host.label("共 ${data.episodes.size} 集" + info.tags.ifBlank { series.tags }.let { if (it.isBlank()) "" else "  ·  $it" }, 13f, PhoneStyle.muted, lines = 2),
            lp().apply { topMargin = host.dp(8) },
        )
        val intro = host.label(info.description.ifBlank { series.description }.ifBlank { "暂无简介" }, 13f, PhoneStyle.muted, lines = 4)
        intro.setOnClickListener { intro.maxLines = if (intro.maxLines == 4) 30 else 4 }
        text.addView(intro, lp().apply { topMargin = host.dp(8) })
        hero.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(hero)

        val actions = host.row().apply { setPadding(0, host.dp(14), 0, host.dp(14)) }
        val play = host.pill("", selected = true) { host.play(series, data) }
        playButton = play
        actions.addView(play, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = host.dp(10) })
        lateinit var favorite: TextView
        favorite =
            host.pill(if (host.library.isLoaded && host.library.favorite(series.id)) "已收藏" else "收藏") {
                if (!host.library.isLoaded) return@pill
                val added = host.library.toggle(series)
                if (added) host.library.observeFavorite(series.id, data.episodes.size, System.currentTimeMillis(), acknowledge = true)
                favorite.text = if (added) "已收藏" else "收藏"
            }
        actions.addView(favorite, lp(host.dp(96), -2))
        body.addView(actions)
        body.addView(host.label("选集", 16f, bold = true))
        val episodes = EpisodeGrid(host, data.episodes.size, currentEpisode(data)) { index -> host.play(series, data, index) }
        grid = episodes
        body.addView(episodes.view, lp(-1, 0).apply {
            weight = 1f
            topMargin = host.dp(8)
        })
        refreshResume()
    }

    private fun currentEpisode(data: Detail): Int {
        val progress = if (host.library.isLoaded) host.library.progress(series.id) else null
        return progress?.let { data.episodes.indexOf(it.episodeId) }?.takeIf { it >= 0 } ?: -1
    }

    private fun refreshResume() {
        val data = detail ?: return
        val index = currentEpisode(data)
        playButton?.text = if (index >= 0) "▶ 继续看第 ${index + 1} 集" else "▶ 开始播放"
        if (index >= 0) grid?.mark(index)
    }

    override fun onShow() = refreshResume()
}

/** Top bar with a back arrow and a title, shared by pushed pages. */
internal fun header(host: PhoneActivity, title: String, onBack: () -> Unit): View =
    FrameLayout(host).apply {
        setBackgroundColor(PhoneStyle.background)
        addView(host.label("‹", 30f).apply {
            gravity = Gravity.CENTER
            setPadding(host.dp(14), 0, host.dp(14), host.dp(4))
            setOnClickListener { onBack() }
        }, frame(-2, host.dp(52), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(host.label(title, 17f, bold = true, lines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(host.dp(56), 0, host.dp(56), 0)
        }, frame(-1, host.dp(52), Gravity.CENTER))
    }
