// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.phone

// 排行页：综合、真人剧、漫剧、AI 剧四个热播榜，名次列表自动续页。

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RankPosition
import com.hongguotv.core.Series

internal class RankPage(host: PhoneActivity) : PhonePage(host) {
    private class Board {
        var items = emptyList<Series>()
        val positions = mutableMapOf<String, RankPosition>()
        var page = 0
        var hasMore = true
        var loading = false
        var updated = ""
    }

    private val boards = RankBoard.entries.associateWith { Board() }
    private var board = RankBoard.ALL
    private val current
        get() = boards.getValue(board)
    private val tabs = mutableMapOf<RankBoard, TextView>()
    private val subtitle = host.label("", 12f, PhoneStyle.muted).apply { setPadding(host.dp(16), 0, host.dp(16), host.dp(6)) }
    private var footer = ""

    private val adapter =
        object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = current.items.size + 1

            override fun getItemViewType(position: Int) = if (position < current.items.size) 0 else 1

            override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(if (type == 0) RankRow() else host.label("", 13f, PhoneStyle.muted).apply {
                    gravity = Gravity.CENTER
                    layoutParams = RecyclerView.LayoutParams(-1, host.dp(56))
                    setOnClickListener { load() }
                }) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val view = holder.itemView
                if (view is RankRow) view.bind(current.items[position], current.positions[current.items[position].id], position)
                else (view as TextView).text = footer
            }
        }

    private val list =
        RecyclerView(host).apply {
            val manager = LinearLayoutManager(host)
            layoutManager = manager
            adapter = this@RankPage.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0 && manager.findLastVisibleItemPosition() >= current.items.size - 4) load()
                }
            })
        }

    override val view: View =
        host.column().apply {
            setBackgroundColor(PhoneStyle.background)
            val bar = host.row().apply { setPadding(host.dp(8), host.dp(10), host.dp(8), host.dp(6)) }
            RankBoard.entries.forEach { item ->
                val tab = host.label(item.label, 16f, PhoneStyle.muted).apply {
                    setPadding(host.dp(10), host.dp(6), host.dp(10), host.dp(6))
                    setOnClickListener { switch(item) }
                }
                tabs[item] = tab
                bar.addView(tab)
            }
            addView(bar)
            addView(subtitle)
            addView(list, lp(-1, 0).apply { weight = 1f })
        }

    init {
        paintTabs()
        load()
    }

    private fun paintTabs() =
        tabs.forEach { (item, tab) ->
            tab.setTextColor(if (item == board) PhoneStyle.text else PhoneStyle.muted)
            tab.setTypeface(null, if (item == board) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun switch(next: RankBoard) {
        if (next == board) return
        board = next
        paintTabs()
        footer = if (current.items.isEmpty()) "正在加载…" else if (current.hasMore) "" else "已经到底了"
        subtitle.text = current.updated
        @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
        list.scrollToPosition(0)
        if (current.items.isEmpty()) load()
    }

    private fun load() {
        val target = current
        val name = board
        if (target.loading || !target.hasMore) return
        target.loading = true
        val requested = target.page + 1
        if (target === current) {
            footer = "正在加载…"
            adapter.notifyItemChanged(target.items.size)
        }
        work.run(
            { host.repository.rankPage(name, requested) },
            { page ->
                target.loading = false
                target.page = requested
                val before = target.items.size
                target.items = (target.items + page.items).distinctBy { it.id }
                page.ranking?.let {
                    target.positions.putAll(it.positions)
                    target.updated = it.updatedText.ifBlank { "来源：红果热播榜" }
                    target.hasMore = page.hasMore && requested < it.totalPages
                } ?: run { target.hasMore = page.hasMore }
                if (target !== current) return@run
                subtitle.text = target.updated
                footer = if (target.hasMore) "" else "已经到底了"
                if (before == 0) @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
                else {
                    adapter.notifyItemRangeInserted(before, target.items.size - before)
                    adapter.notifyItemChanged(target.items.size)
                }
            },
            { problem ->
                target.loading = false
                if (target !== current) return@run
                footer = "${friendlyError(problem)}，点击重试"
                adapter.notifyItemChanged(target.items.size)
            },
        )
    }

    private inner class RankRow : LinearLayout(host) {
        private val rank = host.label("", 20f, PhoneStyle.muted, bold = true).apply { gravity = Gravity.CENTER }
        private val cover = ImageView(host).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = host.rounded(PhoneStyle.surface, 6)
            clipToOutline = true
        }
        private val title = host.label("", 15f, bold = true, lines = 2)
        private val tags = host.label("", 12f, PhoneStyle.muted, lines = 1)
        private val heat = host.label("", 12f, PhoneStyle.accent, lines = 1)
        private var item: Series? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(8), host.dp(8), host.dp(16), host.dp(8))
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            addView(rank, LayoutParams(host.dp(44), -2))
            addView(cover, LayoutParams(host.dp(66), host.dp(94)).apply { rightMargin = host.dp(12) })
            val text = host.column()
            text.addView(title)
            text.addView(tags, lp().apply { topMargin = host.dp(6) })
            text.addView(heat, lp().apply { topMargin = host.dp(6) })
            addView(text, LayoutParams(0, -2, 1f))
            setOnClickListener { item?.let { host.openDetail(it) } }
        }

        fun bind(series: Series, position: RankPosition?, index: Int) {
            item = series
            val number = position?.rank ?: (index + 1)
            rank.text = "$number"
            rank.setTextColor(if (number <= 3) PhoneStyle.accent else PhoneStyle.muted)
            title.text = series.title
            tags.text = listOf(series.badge, series.tags).filter { it.isNotBlank() }.joinToString("  ·  ")
            heat.text = position?.heat?.ifBlank { null }?.let { "🔥 $it" }.orEmpty()
            host.artwork.load(cover, series.cover, widthPx = host.dp(66), heightPx = host.dp(94))
        }
    }
}
