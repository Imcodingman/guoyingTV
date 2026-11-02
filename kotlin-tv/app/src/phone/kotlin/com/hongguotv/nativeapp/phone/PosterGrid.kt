// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.phone

// 竖屏海报网格：三列卡片、滚到底部自动加载下一页，底部显示加载状态。

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.Series

internal class PosterGrid(
    private val host: PhoneActivity,
    private val columns: Int = 3,
    private val onClick: (Series) -> Unit,
    private val onEnd: () -> Unit = {},
    private val badge: (Series) -> String = { it.badge },
    private val corner: (Series) -> String? = { null },
) {
    private var items = emptyList<Series>()
    private var footer = ""
    private val gap = host.dp(10)
    private val side = host.dp(12)

    private val adapter =
        object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size + 1

            override fun getItemViewType(position: Int) = if (position < items.size) 0 else 1

            override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
                val view: View =
                    if (type == 0) PosterCard(host).apply { layoutParams = RecyclerView.LayoutParams(-1, -2) }
                    else
                        host.label("", 13f, PhoneStyle.muted).apply {
                            gravity = Gravity.CENTER
                            layoutParams = RecyclerView.LayoutParams(-1, host.dp(56))
                        }
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val view = holder.itemView
                if (view is TextView) {
                    view.text = footer
                    return
                }
                val card = view as PosterCard
                val item = items[position]
                val width = (host.resources.displayMetrics.widthPixels - side * 2 - gap * (columns - 1)) / columns
                card.sizeFor(width)
                card.title.text = item.title
                card.badge.text = badge(item)
                val tag = corner(item)
                card.corner.text = tag.orEmpty()
                card.corner.visibility = if (tag.isNullOrBlank()) View.GONE else View.VISIBLE
                host.artwork.load(card.cover, item.cover, widthPx = width, heightPx = (width / PhoneStyle.POSTER_ASPECT).toInt())
                card.setOnClickListener { onClick(item) }
            }
        }

    val view =
        RecyclerView(host).apply {
            val grid = GridLayoutManager(host, columns)
            grid.spanSizeLookup =
                object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (position < items.size) 1 else columns
                }
            layoutManager = grid
            adapter = this@PosterGrid.adapter
            clipToPadding = false
            setPadding(side, host.dp(8), side, host.dp(8))
            addItemDecoration(
                object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        out: android.graphics.Rect,
                        view: View,
                        parent: RecyclerView,
                        state: RecyclerView.State,
                    ) {
                        val position = parent.getChildAdapterPosition(view)
                        if (position < 0 || position >= items.size) return
                        val column = position % columns
                        out.left = column * gap / columns
                        out.right = gap - (column + 1) * gap / columns
                        out.bottom = host.dp(14)
                    }
                }
            )
            addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                        val last = grid.findLastVisibleItemPosition()
                        if (dy > 0 && last >= items.size - columns * 2) onEnd()
                    }
                }
            )
        }

    fun submit(next: List<Series>, status: String = "") {
        items = next
        footer = status
        @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
    }

    fun status(value: String) {
        footer = value
        adapter.notifyItemChanged(items.size)
    }

    fun scrollTop() = view.scrollToPosition(0)
}
