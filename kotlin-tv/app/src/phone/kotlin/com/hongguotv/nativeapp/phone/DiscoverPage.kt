// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.phone

// 找剧页：顶部搜索框，组合筛选标签，下方三列海报网格自动续页。

import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.hongguotv.core.DiscoveryCursor
import com.hongguotv.core.DiscoveryGroup
import com.hongguotv.core.DiscoverySelection
import com.hongguotv.core.FilteredDiscoveryPage
import com.hongguotv.core.Series

internal class DiscoverPage(host: PhoneActivity) : PhonePage(host) {
    private var selection = DiscoverySelection()
    private var groups = emptyList<DiscoveryGroup>()
    private var items = emptyList<Series>()
    private var next: DiscoveryCursor? = null
    private var loading = false
    private var expanded = false
    private val filters = host.column().apply { setPadding(0, 0, 0, host.dp(4)) }
    private val grid = PosterGrid(host, onClick = { host.openDetail(it) }, onEnd = { loadMore() })

    override val view: View =
        host.column().apply {
            setBackgroundColor(PhoneStyle.background)
            addView(searchBox(host), lp().apply { setMargins(host.dp(12), host.dp(10), host.dp(12), host.dp(6)) })
            addView(filters)
            addView(grid.view, lp(-1, 0).apply { weight = 1f })
        }

    init {
        reload()
    }

    private fun reload() {
        work.cancel()
        loading = true
        next = null
        items = emptyList()
        grid.submit(emptyList(), "正在加载…")
        val requested = selection
        work.run(
            { host.repository.filteredDiscovery(requested) },
            { page -> apply(page, replace = true) },
            { problem ->
                loading = false
                grid.submit(emptyList(), "${friendlyError(problem)}，下拉或点筛选重试")
                if (groups.isEmpty()) renderFilters()
            },
        )
    }

    private fun loadMore() {
        val cursor = next ?: return
        if (loading) return
        loading = true
        grid.status("正在加载更多…")
        val requested = selection
        work.run(
            { host.repository.filteredDiscovery(requested, cursor) },
            { page -> apply(page, replace = false) },
            { problem ->
                loading = false
                grid.status("${friendlyError(problem)}，继续上滑重试")
            },
        )
    }

    private fun apply(page: FilteredDiscoveryPage, replace: Boolean) {
        loading = false
        if (page.groups.isNotEmpty()) groups = page.groups
        items = if (replace) page.items else (items + page.items).distinctBy { it.id }
        next = page.next
        grid.submit(items, if (items.isEmpty()) "没有符合条件的剧，换个筛选试试" else if (next == null) "没有更多了" else "")
        if (replace) {
            grid.scrollTop()
            renderFilters()
        }
    }

    private fun renderFilters() {
        filters.removeAllViews()
        val shown = if (expanded) groups else groups.take(2)
        shown.forEach { group ->
            val chips = host.row().apply { setPadding(host.dp(12), host.dp(4), host.dp(12), host.dp(4)) }
            val selected = selection.values[group.type].orEmpty()
            chips.addView(chip("全部${group.label}", selected.isEmpty()) { choose(group.type, "") })
            group.options.forEach { option ->
                chips.addView(chip(option.label, option.id == selected) { choose(group.type, option.id) })
            }
            filters.addView(HorizontalScrollView(host).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
        }
        if (groups.size > 2)
            filters.addView(
                host.label(if (expanded) "收起筛选 ▲" else "更多筛选 ▼", 13f, PhoneStyle.muted).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, host.dp(6), 0, host.dp(6))
                    setOnClickListener {
                        expanded = !expanded
                        renderFilters()
                    }
                },
                lp(),
            )
        if (groups.isEmpty())
            filters.addView(host.label("重新加载", 13f, PhoneStyle.accent).apply {
                gravity = Gravity.CENTER
                setPadding(0, host.dp(8), 0, host.dp(8))
                setOnClickListener { reload() }
            }, lp())
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) =
        host.label(text, 13f, if (selected) PhoneStyle.accent else PhoneStyle.muted, bold = selected).apply {
            setPadding(host.dp(10), host.dp(6), host.dp(10), host.dp(6))
            if (selected) background = host.rounded(PhoneStyle.surface, 14)
            setOnClickListener { onClick() }
        }

    private fun choose(type: String, id: String) {
        selection = selection.choose(type, id)
        renderFilters()
        reload()
    }
}

/** A tappable fake search field that opens the search page. */
internal fun searchBox(host: PhoneActivity): View =
    host.label("🔍  搜索剧名、关键词", 14f, PhoneStyle.muted).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(host.dp(14), 0, host.dp(14), 0)
        minHeight = host.dp(40)
        background = host.rounded(PhoneStyle.surface, 20)
        setOnClickListener { host.openSearch() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(-1, host.dp(40)) }
