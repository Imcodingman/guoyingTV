// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.phone

// 搜索页：输入框、搜索历史、输入联想，结果以三列海报展示并自动续页。

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.hongguotv.core.ContentType
import com.hongguotv.core.Series

internal class SearchPage(host: PhoneActivity) : PhonePage(host) {
    private val suggestWork = PageWork(host)
    private var keyword = ""
    private var page = 0
    private var hasMore = false
    private var loading = false
    private var items = emptyList<Series>()
    private var typing = ""

    private val input =
        EditText(host).apply {
            hint = "搜索剧名、关键词"
            textSize = 15f
            setTextColor(PhoneStyle.text)
            setHintTextColor(PhoneStyle.muted)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
            background = host.rounded(PhoneStyle.surface, 20)
            setPadding(host.dp(14), 0, host.dp(14), 0)
            setOnEditorActionListener { _, _, _ ->
                search(text.toString())
                true
            }
        }
    private val hints = host.column().apply { setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(8)) }
    private val hintScroll = ScrollView(host).apply { addView(hints) }
    private val grid = PosterGrid(host, onClick = { host.openDetail(it) }, onEnd = { loadMore() })
    private val content = FrameLayout(host)
    private val suggest = Runnable { fetchSuggestions() }

    override val view: View =
        host.column().apply {
            setBackgroundColor(PhoneStyle.background)
            isClickable = true
            val bar = host.row().apply { setPadding(host.dp(4), host.dp(8), host.dp(12), host.dp(8)) }
            bar.addView(host.label("‹", 30f).apply {
                setPadding(host.dp(12), 0, host.dp(12), host.dp(4))
                setOnClickListener { host.pop(this@SearchPage) }
            })
            bar.addView(input, LinearLayout.LayoutParams(0, host.dp(40), 1f))
            bar.addView(host.label("搜索", 15f, PhoneStyle.accent).apply {
                setPadding(host.dp(12), host.dp(8), 0, host.dp(8))
                setOnClickListener { search(input.text.toString()) }
            })
            addView(bar)
            content.addView(hintScroll, frame())
            content.addView(grid.view, frame())
            addView(content, lp(-1, 0).apply { weight = 1f })
        }

    init {
        grid.view.visibility = View.GONE
        input.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    typing = s?.toString()?.trim().orEmpty()
                    host.main.removeCallbacks(suggest)
                    if (typing.isEmpty()) showHistory() else host.main.postDelayed(suggest, 300)
                    if (typing != keyword) {
                        hintScroll.visibility = View.VISIBLE
                        grid.view.visibility = View.GONE
                    }
                }
            }
        )
        showHistory()
        input.requestFocus()
        host.main.postDelayed({ keyboard(true) }, 250)
    }

    private fun keyboard(show: Boolean) {
        val manager = host.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        else manager.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun showHistory() {
        hints.removeAllViews()
        val history = if (host.library.isLoaded) host.library.searches().map { it.query }.distinct() else emptyList()
        if (history.isEmpty()) {
            hints.addView(host.label("输入剧名或关键词开始搜索", 14f, PhoneStyle.muted), lp().apply { topMargin = host.dp(16) })
            return
        }
        val head = host.row()
        head.addView(host.label("搜索历史", 15f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(host.label("清空", 13f, PhoneStyle.muted).apply {
            setPadding(host.dp(8), host.dp(6), 0, host.dp(6))
            setOnClickListener {
                host.library.clearSearches()
                showHistory()
            }
        })
        hints.addView(head)
        val flow = FlowLayout(host)
        history.take(20).forEach { query -> flow.addView(host.pill(query) { fill(query) }) }
        hints.addView(flow, lp().apply { topMargin = host.dp(10) })
    }

    private fun fetchSuggestions() {
        val query = typing
        if (query.isEmpty()) return
        suggestWork.cancel()
        suggestWork.run(
            { host.repository.searchSuggestions(query) },
            { words ->
                if (query != typing) return@run
                hints.removeAllViews()
                words.forEach { word ->
                    hints.addView(host.label(word, 15f).apply {
                        setPadding(0, host.dp(12), 0, host.dp(12))
                        setOnClickListener { fill(word) }
                    }, lp())
                }
            },
        )
    }

    private fun fill(query: String) {
        input.setText(query)
        input.setSelection(query.length)
        search(query)
    }

    private fun search(raw: String) {
        val query = raw.trim()
        if (query.isEmpty() || query.length > 80) return
        keyboard(false)
        host.main.removeCallbacks(suggest)
        suggestWork.cancel()
        work.cancel()
        if (host.library.isLoaded) host.library.rememberSearch(query, ContentType.SHORT)
        keyword = query
        page = 0
        hasMore = true
        loading = false
        items = emptyList()
        hintScroll.visibility = View.GONE
        grid.view.visibility = View.VISIBLE
        grid.submit(emptyList(), "正在搜索…")
        loadMore()
    }

    private fun loadMore() {
        if (loading || !hasMore || keyword.isEmpty()) return
        loading = true
        val query = keyword
        val requested = page + 1
        if (requested > 1) grid.status("正在加载更多…")
        work.run(
            { host.repository.searchAll(query, requested) },
            { result ->
                loading = false
                page = requested
                items = (items + result.items).distinctBy { it.id }
                hasMore = result.hasMore && result.items.isNotEmpty() && requested < 100
                grid.submit(items, if (items.isEmpty()) "没有找到「$query」相关的剧" else if (hasMore) "" else "没有更多了")
                if (requested == 1) grid.scrollTop()
            },
            { problem ->
                loading = false
                grid.status("${friendlyError(problem)}，上滑重试")
            },
        )
    }

    override fun onShow() {
        if (keyword.isEmpty() && typing.isEmpty()) showHistory()
    }

    override fun onHide() = keyboard(false)

    override fun destroy() {
        super.destroy()
        suggestWork.cancel()
        host.main.removeCallbacks(suggest)
    }
}

/** Minimal wrapping row layout for history chips. */
internal class FlowLayout(context: Context) : android.view.ViewGroup(context) {
    private val gap = context.dp(8)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        var x = 0
        var y = 0
        var line = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChild(child, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightSpec)
            if (x > 0 && x + child.measuredWidth > width) {
                x = 0
                y += line + gap
                line = 0
            }
            x += child.measuredWidth + gap
            line = maxOf(line, child.measuredHeight)
        }
        setMeasuredDimension(width, y + line)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = 0
        var y = 0
        var line = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (x > 0 && x + child.measuredWidth > width) {
                x = 0
                y += line + gap
                line = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap
            line = maxOf(line, child.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(-2, -2)
}
