// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.phone

// 手机版入口：底部四个标签（推荐、找剧、排行、我的），搜索、详情、播放页按栈叠加。

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.Detail
import com.hongguotv.core.PlaybackDeviceSession
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.ArtworkLoader
import com.hongguotv.nativeapp.CodecCapabilities
import com.hongguotv.nativeapp.Library
import java.util.concurrent.Executors

internal abstract class PhonePage(val host: PhoneActivity) {
    val work = PageWork(host)
    abstract val view: View

    /** Page became the visible, foreground page. */
    open fun onShow() {}

    /** Page was covered, left, or the activity paused. */
    open fun onHide() {}

    /** Return true when the page consumed the back press itself. */
    open fun onBack(): Boolean = false

    open fun destroy() = work.cancel()
}

class PhoneActivity : Activity() {
    internal val main = Handler(Looper.getMainLooper())
    internal val io = Executors.newFixedThreadPool(4) { task -> Thread(task, "guoying-phone-io") }
    internal val repository = ContentRepository(playbackSession = PlaybackDeviceSession.shared)
    internal lateinit var library: Library
    internal lateinit var artwork: ArtworkLoader

    private lateinit var root: FrameLayout
    private lateinit var tabContent: FrameLayout
    private val tabButtons = mutableListOf<TextView>()
    private val tabs = arrayOfNulls<PhonePage>(4)
    private var tab = 0
    private val stack = mutableListOf<PhonePage>()
    private var foreground = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        CodecCapabilities.init(this)
        library = Library(this)
        artwork = ArtworkLoader(this)
        root = FrameLayout(this).apply { setBackgroundColor(PhoneStyle.background) }
        val shell = column()
        tabContent = FrameLayout(this)
        shell.addView(tabContent, lp(-1, 0).apply { weight = 1f })
        shell.addView(bottomBar(), lp(-1, dp(56)))
        root.addView(shell, frame())
        setContentView(root)
        library.loadAsync { if (!isDestroyed) (visiblePage() as? MinePage)?.onShow() }
        switchTab(0)
    }

    private fun bottomBar(): View {
        val bar = row().apply { setBackgroundColor(PhoneStyle.surface) }
        listOf("推荐", "找剧", "排行", "我的").forEachIndexed { index, name ->
            val button =
                label(name, 15f, PhoneStyle.muted).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { if (index == tab) (tabs[index] as? FeedPage)?.refreshFromTab() else switchTab(index) }
                }
            tabButtons += button
            bar.addView(button, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return bar
    }

    private fun createTab(index: Int): PhonePage =
        when (index) {
            0 -> FeedPage(this)
            1 -> DiscoverPage(this)
            2 -> RankPage(this)
            else -> MinePage(this)
        }

    private fun switchTab(index: Int) {
        val previous = tabs[tab]
        if (stack.isEmpty() && previous != null && index != tab) previous.onHide()
        tab = index
        tabButtons.forEachIndexed { i, button ->
            button.setTextColor(if (i == index) PhoneStyle.text else PhoneStyle.muted)
            button.setTypeface(null, if (i == index) Typeface.BOLD else Typeface.NORMAL)
        }
        val page = tabs[index] ?: createTab(index).also { tabs[index] = it }
        tabContent.removeAllViews()
        tabContent.addView(page.view, frame())
        if (stack.isEmpty() && foreground) page.onShow()
    }

    internal fun visiblePage(): PhonePage? = stack.lastOrNull() ?: tabs[tab]

    internal fun push(page: PhonePage) {
        if (foreground) visiblePage()?.onHide()
        stack += page
        root.addView(page.view, frame())
        if (foreground) page.onShow()
    }

    internal fun pop(page: PhonePage) {
        if (!stack.remove(page)) return
        page.onHide()
        page.destroy()
        root.removeView(page.view)
        if (foreground) visiblePage()?.onShow()
    }

    internal fun openSearch() = push(SearchPage(this))

    internal fun openDetail(series: Series) = push(DetailPage(this, series))

    /** Starts full playback; with no [detail] the page loads it and resumes local progress. */
    internal fun play(series: Series, detail: Detail? = null, episode: Int? = null) =
        push(PlayerPage(this, series, detail, episode))

    @Deprecated("Phone back navigation is handled by the page stack")
    override fun onBackPressed() {
        val top = stack.lastOrNull()
        when {
            top != null -> if (!top.onBack()) pop(top)
            tabs[tab]?.onBack() == true -> Unit
            tab != 0 -> switchTab(0)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        visiblePage()?.onShow()
    }

    override fun onPause() {
        foreground = false
        visiblePage()?.onHide()
        if (library.isLoaded) library.flush()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        artwork.trimMemory(level)
    }

    override fun onDestroy() {
        stack.toList().forEach { it.destroy() }
        stack.clear()
        tabs.forEach { it?.destroy() }
        library.close()
        artwork.close()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        val http = repository.http
        Thread({ http.dispatcher.cancelAll(); http.connectionPool.evictAll() }, "guoying-phone-cleanup").start()
        super.onDestroy()
    }
}
