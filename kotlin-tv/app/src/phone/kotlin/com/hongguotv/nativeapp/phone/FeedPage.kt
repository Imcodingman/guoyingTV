// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.phone

// 推荐页：上下滑动换剧，自动试播第一集；当前剧播放稳定后预加载下一部。

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.hongguotv.core.DramaCategory
import com.hongguotv.core.DramaFeedState
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.FeedStartupCache
import com.hongguotv.nativeapp.Media3TrialPlayback
import com.hongguotv.nativeapp.PublicFeedBackend
import com.hongguotv.nativeapp.TrialClip
import com.hongguotv.nativeapp.TrialStatus
import com.hongguotv.nativeapp.playbackFailureSummary

internal class FeedPage(host: PhoneActivity) : PhonePage(host) {
    private enum class Source(val label: String, val category: DramaCategory?) {
        RECOMMEND("推荐", null),
        COMIC("漫剧", DramaCategory.COMIC),
        REAL("真人剧", DramaCategory.REAL),
    }

    private class SourceState(val feed: DramaFeedState) {
        var items = emptyList<Series>()
        var index = 0
        var loading = false
        var failed: String? = null
    }

    private val backend = PublicFeedBackend(720, startupCache = FeedStartupCache(host))
    private val playback = Media3TrialPlayback(host)
    private val clipWork = PageWork(host)
    private val preloadWork = PageWork(host)
    private val states =
        Source.entries.associateWith { source ->
            SourceState(DramaFeedState().apply { source.category?.let { select(it) } })
        }
    private var source = Source.RECOMMEND
    private val state
        get() = states.getValue(source)

    private val clips = linkedMapOf<String, TrialClip>()
    private var preloading: String? = null
    private var selected = -1
    private var shownStatus = ""
    private var shownReady = false
    private var trialRetries = 0
    private var visible = false
    private var userPaused = false
    private val sourceButtons = mutableMapOf<Source, TextView>()

    private val adapter =
        object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = state.items.size

            override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
                object : RecyclerView.ViewHolder(SlideView()) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val slide = holder.itemView as SlideView
                slide.bind(state.items[position], position)
                if (position == state.index) attachPlayer(slide)
            }
        }

    private val pager =
        ViewPager2(host).apply {
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
            adapter = this@FeedPage.adapter
            registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        if (position != selected) select(position)
                    }
                }
            )
        }

    private val empty =
        host.label("正在加载推荐…", 15f, PhoneStyle.muted).apply {
            gravity = Gravity.CENTER
            setOnClickListener { if (state.failed != null) loadMore() }
        }

    override val view: View =
        FrameLayout(host).apply {
            setBackgroundColor(Color.BLACK)
            addView(pager, frame())
            addView(empty, frame())
            addView(topBar(), frame(-1, -2, Gravity.TOP))
        }

    init {
        playback.status = { status -> onStatus(status) }
        loadMore()
    }

    private fun topBar(): View {
        val bar =
            FrameLayout(host).apply {
                setPadding(host.dp(8), host.dp(10), host.dp(8), host.dp(18))
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.argb(150, 0, 0, 0), Color.TRANSPARENT),
                    )
            }
        val sources = host.row().apply { gravity = Gravity.CENTER }
        Source.entries.forEach { item ->
            val button =
                host.label(item.label, 16f, Color.WHITE).apply {
                    setPadding(host.dp(12), host.dp(6), host.dp(12), host.dp(6))
                    setShadowLayer(4f, 0f, 1f, Color.BLACK)
                    setOnClickListener { switchSource(item) }
                }
            sourceButtons[item] = button
            sources.addView(button)
        }
        bar.addView(sources, frame(-1, -2, Gravity.CENTER))
        bar.addView(
            host.label("搜索", 15f, Color.WHITE).apply {
                setPadding(host.dp(12), host.dp(6), host.dp(12), host.dp(6))
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
                setOnClickListener { host.openSearch() }
            },
            frame(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL),
        )
        paintSources()
        return bar
    }

    private fun paintSources() =
        sourceButtons.forEach { (item, button) ->
            button.alpha = if (item == source) 1f else .6f
            button.textSize = if (item == source) 17f else 15f
        }

    private fun switchSource(next: Source) {
        if (next == source) return
        stopClips()
        source = next
        paintSources()
        @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
        if (state.items.isEmpty()) {
            empty.text = "正在加载推荐…"
            empty.visibility = View.VISIBLE
            loadMore()
        } else {
            empty.visibility = View.GONE
            pager.setCurrentItem(state.index, false)
            if (selected != state.index) select(state.index)
        }
    }

    /** Re-tapping the 推荐 tab fetches a fresh batch, like most short-video apps. */
    fun refreshFromTab() {
        stopClips()
        work.cancel()
        val current = state
        current.loading = false
        current.feed.refresh()
        current.items = emptyList()
        current.index = 0
        current.failed = null
        @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
        empty.text = "正在换一批…"
        empty.visibility = View.VISIBLE
        loadMore()
    }

    private fun loadMore() {
        val target = state
        if (target.loading || (!target.feed.hasMore && target.items.isNotEmpty())) return
        target.loading = true
        target.failed = null
        val requested = target.feed.page + 1
        val category = source.category
        val cursor = target.feed.recommendationCursor
        work.run(
            {
                if (category == null) backend.recommendations(requested, cursor)
                else backend.categoryRecommendations(category, requested, cursor)
            },
            { result ->
                target.loading = false
                if (requested != target.feed.page + 1) return@run
                target.feed.append(result, requested)
                val before = target.items.size
                target.items = (target.items + result.items).distinctBy { it.id }
                if (target !== state) return@run
                if (before == 0) {
                    @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
                    empty.visibility = if (target.items.isEmpty()) View.VISIBLE else View.GONE
                    if (target.items.isEmpty()) empty.text = "暂时没有推荐，点击重试"
                    else if (selected != 0) select(0)
                } else adapter.notifyItemRangeInserted(before, target.items.size - before)
            },
            { problem ->
                target.loading = false
                target.failed = friendlyError(problem)
                if (target === state && target.items.isEmpty()) {
                    empty.text = "${target.failed}\n点击重试"
                    empty.visibility = View.VISIBLE
                }
            },
        )
    }

    private fun currentSlide(): SlideView? =
        ((pager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(state.index)?.itemView as? SlideView)

    private fun attachPlayer(slide: SlideView) {
        val surface = playback.view
        if (surface.parent === slide.videoSlot) return
        (surface.parent as? ViewGroup)?.removeView(surface)
        slide.videoSlot.addView(surface, frame())
    }

    private fun currentId() = state.items.getOrNull(state.index)?.id

    /**
     * Status of the current item lives here, not only on its view: the slide may be bound or re-bound
     * after a result arrives, and binding must not reset it to "loading" over a playing video.
     */
    private fun show(id: String, text: String, ready: Boolean = false) {
        if (id != currentId()) return
        shownStatus = text
        shownReady = ready
        host.main.removeCallbacks(slowWatchdog)
        currentSlide()?.applyStatus()
    }

    private val slowWatchdog = Runnable {
        val id = currentId() ?: return@Runnable
        if (!shownReady && shownStatus == "正在加载…") show(id, "加载较慢，点击重试")
    }

    private fun select(position: Int, retrying: Boolean = false) {
        val items = state.items
        val series = items.getOrNull(position) ?: return
        state.index = position
        selected = position
        userPaused = false
        if (!retrying) trialRetries = 0
        clipWork.cancel()
        playback.setCurrentIndex(position)
        show(series.id, "正在加载…")
        host.main.postDelayed(slowWatchdog, 12000)
        currentSlide()?.let { attachPlayer(it) }
        if (position >= items.size - 3) loadMore()
        trimClips(position)
        val ready = clips[series.id]
        if (ready != null) {
            if (!playback.select(ready, 0, visible && !userPaused)) playback.load(ready, 0, visible && !userPaused)
            return
        }
        android.util.Log.i("HongguoTV", "Phone feed trial request index=$position retry=$trialRetries")
        clipWork.run(
            { backend.trial(series) },
            { clip ->
                clips[series.id]?.close()
                clips[series.id] = clip
                if (currentId() == series.id) playback.load(clip, 0, visible && !userPaused)
            },
            { problem ->
                android.util.Log.w("HongguoTV", "Phone feed trial failure index=$position: ${playbackFailureSummary(problem)}")
                if (currentId() != series.id) return@run
                if (trialRetries < 1) {
                    trialRetries++
                    show(series.id, "正在重试…")
                    host.main.postDelayed({ if (currentId() == series.id && selected == position) select(position, retrying = true) }, 2000)
                } else show(series.id, "${playbackFailureSummary(problem)}\n点击重试")
            },
        )
    }

    /** Keep the previous item and the next two; release the rest. */
    private fun trimClips(position: Int) {
        val keep = state.items.subList((position - 1).coerceAtLeast(0), (position + 3).coerceAtMost(state.items.size)).map { it.id }.toSet()
        clips.keys.filter { it !in keep }.forEach { id ->
            playback.remove(id)
            clips.remove(id)?.close()
        }
    }

    private fun preloadNext() {
        if (!playback.canPrefetch()) return
        val next = state.items.getOrNull(state.index + 1) ?: return
        if (next.id in clips || preloading == next.id) return
        preloading = next.id
        val index = state.index + 1
        preloadWork.run(
            { backend.preload(next) },
            { clip ->
                preloading = null
                if (next.id in clips || state.items.getOrNull(index)?.id != next.id) {
                    clip.close()
                    return@run
                }
                clips[next.id] = clip
                playback.queue(clip, index)
            },
            { preloading = null },
        )
    }

    private fun onStatus(status: TrialStatus) {
        val id = currentId() ?: return
        when (status) {
            // Buffering keeps the current text; the player view shows its own spinner.
            TrialStatus.BUFFERING -> Unit
            TrialStatus.READY -> {
                show(id, "", ready = true)
                host.main.postDelayed({ if (visible) preloadNext() }, 2000)
            }
            TrialStatus.ENDED -> show(id, "试看结束 · 点「看全集」继续", ready = true)
            TrialStatus.ERROR -> show(id, "播放失败，点击重试")
        }
    }

    private fun stopClips() {
        clipWork.cancel()
        preloadWork.cancel()
        preloading = null
        selected = -1
        shownReady = false
        host.main.removeCallbacks(slowWatchdog)
        playback.clear()
        clips.values.forEach { it.close() }
        clips.clear()
    }

    private fun togglePause() {
        val slide = currentSlide() ?: return
        val series = state.items.getOrNull(state.index) ?: return
        if (series.id !in clips) {
            select(state.index)
            return
        }
        userPaused = !userPaused
        if (userPaused) playback.pause() else playback.resume()
        slide.paused.visibility = if (userPaused) View.VISIBLE else View.GONE
    }

    override fun onShow() {
        visible = true
        if (!userPaused && clips.containsKey(state.items.getOrNull(state.index)?.id)) playback.resume()
        currentSlide()?.refreshFavorite()
    }

    override fun onHide() {
        visible = false
        playback.pause()
    }

    override fun destroy() {
        super.destroy()
        stopClips()
        playback.release()
        backend.close()
    }

    private inner class SlideView : FrameLayout(host) {
        val cover = ImageView(host).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val videoSlot = FrameLayout(host)
        private val statusText =
            host.label("", 14f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
            }
        val paused =
            host.label("▶", 56f, Color.argb(200, 255, 255, 255)).apply {
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
        private val title = host.label("", 18f, Color.WHITE, bold = true, lines = 2)
        private val meta = host.label("", 13f, Color.argb(220, 255, 255, 255), lines = 1)
        private val intro = host.label("", 13f, Color.argb(200, 255, 255, 255), lines = 2)
        private val favorite = host.pill("收藏") { toggleFavorite() }
        private var series: Series? = null

        init {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            addView(cover, frame())
            addView(videoSlot, frame())
            addView(paused, frame(-2, -2, Gravity.CENTER))
            addView(statusText, frame(-2, -2, Gravity.CENTER))
            val info =
                host.column().apply {
                    setPadding(host.dp(16), host.dp(40), host.dp(16), host.dp(16))
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.BOTTOM_TOP,
                            intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT),
                        )
                }
            info.addView(title)
            info.addView(meta, lp().apply { topMargin = host.dp(6) })
            info.addView(intro, lp().apply { topMargin = host.dp(6) })
            val actions = host.row().apply { setPadding(0, host.dp(12), 0, 0) }
            actions.addView(
                host.pill("▶ 看全集", selected = true) { series?.let { host.play(it) } },
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = host.dp(10) },
            )
            actions.addView(host.pill("详情") { series?.let { host.openDetail(it) } }, lp(-2, -2).apply { rightMargin = host.dp(10) })
            actions.addView(favorite, lp(-2, -2))
            info.addView(actions)
            addView(info, frame(-1, -2, Gravity.BOTTOM))
            setOnClickListener {
                if (statusText.text.contains("重试")) select(state.index) else togglePause()
            }
        }

        fun bind(item: Series, position: Int) {
            series = item
            title.text = item.title
            meta.text = listOf(item.badge, item.tags).filter { it.isNotBlank() }.joinToString("  ·  ")
            intro.text = item.description
            paused.visibility = View.GONE
            host.artwork.load(cover, item.cover)
            applyStatus()
            refreshFavorite()
        }

        /** Shows the page-held status for the current item; other slides show just their poster. */
        fun applyStatus() {
            val current = series?.id != null && series?.id == currentId()
            statusText.text = if (current) shownStatus else ""
            cover.visibility = if (current && shownReady) View.INVISIBLE else View.VISIBLE
            if (current && shownStatus.isNotEmpty()) paused.visibility = View.GONE
        }

        fun refreshFavorite() {
            val item = series ?: return
            val saved = host.library.isLoaded && host.library.favorite(item.id)
            favorite.text = if (saved) "已收藏" else "收藏"
        }

        private fun toggleFavorite() {
            val item = series ?: return
            if (!host.library.isLoaded) return
            val added = host.library.toggle(item)
            favorite.text = if (added) "已收藏" else "收藏"
        }
    }
}
