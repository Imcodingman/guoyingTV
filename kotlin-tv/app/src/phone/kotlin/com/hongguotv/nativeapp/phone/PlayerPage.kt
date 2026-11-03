// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.phone

// 正式播放页：全屏竖屏播放，上下滑动切集，底部进度条、倍速、清晰度、选集；记录本机观看进度。

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hongguotv.core.Detail
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.PlaybackSpeed
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.ResumePlayback
import com.hongguotv.core.Series
import com.hongguotv.nativeapp.CodecCapabilities
import com.hongguotv.nativeapp.NoPlayableQualityException
import com.hongguotv.nativeapp.OpenedStream
import com.hongguotv.nativeapp.TvSocial
import com.hongguotv.nativeapp.VideoDataSource
import com.hongguotv.nativeapp.WatchProgress
import com.hongguotv.nativeapp.openStream
import com.hongguotv.nativeapp.playbackFailureSummary
import kotlin.math.abs

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlayerPage(
    host: PhoneActivity,
    private val series: Series,
    private var detail: Detail?,
    private val requestedEpisode: Int?,
) : PhonePage(host) {
    private val player =
        ExoPlayer.Builder(host, DefaultRenderersFactory(host).setEnableDecoderFallback(true))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15000, 30000, 1000, 2000)
                    .setTargetBufferBytes(12 * 1024 * 1024)
                    .build()
            )
            .build()
    private val surface =
        PlayerView(host).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            player = this@PlayerPage.player
        }
    // 弹幕复用电视版的时间轴弹幕（匿名读取、Canvas 逐帧绘制、本机保存开关与样式）。
    private val social =
        TvSocial(host, { player }, { detail?.episodes?.getOrNull(episode)?.let { series.id to it } })
    private val danmakuLayer = FrameLayout(host)
    private var comments: CommentSheet? = null
    private var episode = 0
    private var video: RemoteVideo? = null
    private var quality = ""
    private var ready = false
    private var failed = false
    private var visible = false
    private var userPaused = false
    private var landscape = false
    private var lastSaved = 0L
    private var dragging = false

    // 下一集预加载：地址与 MP4 索引就绪后追加到播放列表，由 ExoPlayer 在本集结束时无缝接续。
    private val preloadWork = PageWork(host)
    private var nextVideo: RemoteVideo? = null
    private var nextIndex = -1
    private var preloadAttempts = 0
    private var preloadRetryAt = 0L
    private var readyAt = 0L
    private var autoRetries = 0
    private var isDestroyed = false

    private companion object {
        val AUTO_RETRY_DELAYS = longArrayOf(1500, 3000, 6000, 12000)
    }

    private val title = host.label("${series.title}", 16f, Color.WHITE, bold = true, lines = 1)
    private val status =
        host.label("正在加载剧集…", 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
    private val retry = host.row().apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val playButton = host.label("暂停", 14f, Color.WHITE).apply { padButton(this) }
    private val speedButton = host.label("倍速", 14f, Color.WHITE).apply { padButton(this) }
    private val qualityButton = host.label("清晰度", 14f, Color.WHITE).apply { padButton(this) }
    private val episodesButton = host.label("选集", 14f, Color.WHITE).apply { padButton(this) }
    private val time = host.label("00:00 / 00:00", 12f, Color.argb(220, 255, 255, 255))
    private val seek =
        SeekBar(host).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
        }
    private val topBar = host.row()
    private val bottomBar = host.column()
    private val centerPause =
        host.label("▶", 64f, Color.argb(210, 255, 255, 255)).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
    private val episodeToast =
        host.label("", 15f, Color.WHITE, bold = true).apply {
            setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(8))
            background = host.rounded(Color.argb(150, 0, 0, 0), 18)
            alpha = 0f
        }
    private var panel: View? = null

    private val tick =
        object : Runnable {
            override fun run() {
                updateProgress()
                val now = android.os.SystemClock.elapsedRealtime()
                if (player.isPlaying && now - lastSaved > 5000) {
                    lastSaved = now
                    saveProgress()
                }
                maybePreloadNext(now)
                host.main.postDelayed(this, 500)
            }
        }
    private val hideControls = Runnable { if (player.isPlaying) showControls(false) }

    override val view: View = FrameLayout(host).apply { setBackgroundColor(Color.BLACK) }

    init {
        buildLayout()
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            if (!ready) readyAt = android.os.SystemClock.elapsedRealtime()
                            ready = true
                            autoRetries = 0
                            status.text = ""
                        }
                        Player.STATE_ENDED -> {
                            saveProgress(completed = true)
                            val data = detail ?: return
                            if (episode < data.episodes.lastIndex) playEpisode(episode + 1, 0)
                            else status.text = "已经是最后一集"
                        }
                    }
                    paintPlay()
                }

                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    if (item != null && nextIndex >= 0 && item.mediaId == detail?.episodes?.getOrNull(nextIndex)) advanced()
                }


                override fun onIsPlayingChanged(playing: Boolean) {
                    paintPlay()
                    if (playing) host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                override fun onPlayerError(error: PlaybackException) = fail(error)
            }
        )
        load()
    }

    private fun padButton(view: TextView) {
        view.gravity = Gravity.CENTER
        view.setPadding(host.dp(9), host.dp(10), host.dp(9), host.dp(10))
    }

    private fun buildLayout() {
        val root = view as FrameLayout
        root.addView(surface, frame())
        root.addView(danmakuLayer, frame())
        social.attach(danmakuLayer)
        val gestures = GestureDetector(host, Gestures())
        val touch = View(host).apply { setOnTouchListener { _, event -> gestures.onTouchEvent(event); true } }
        root.addView(touch, frame())
        root.addView(centerPause, frame(-2, -2, Gravity.CENTER))
        root.addView(episodeToast, frame(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = host.dp(96) })
        val middle = host.column().apply { gravity = Gravity.CENTER }
        middle.addView(status)
        middle.addView(retry, lp().apply { topMargin = host.dp(12) })
        root.addView(middle, frame(-1, -2, Gravity.CENTER))

        topBar.setPadding(host.dp(4), host.dp(8), host.dp(8), host.dp(24))
        topBar.background =
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT))
        topBar.addView(host.label("‹", 30f, Color.WHITE).apply {
            setPadding(host.dp(12), 0, host.dp(12), host.dp(4))
            setOnClickListener { host.pop(this@PlayerPage) }
        })
        topBar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(host.label("横屏", 14f, Color.WHITE).apply {
            padButton(this)
            setOnClickListener { toggleOrientation() }
        })
        root.addView(topBar, frame(-1, -2, Gravity.TOP))

        bottomBar.setPadding(host.dp(12), host.dp(28), host.dp(12), host.dp(10))
        bottomBar.background =
            GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT))
        val seekRow = host.row()
        seekRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        seekRow.addView(time)
        bottomBar.addView(seekRow)
        val buttons = host.row()
        buttons.addView(playButton.apply { setOnClickListener { togglePause() } })
        buttons.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(speedButton.apply { setOnClickListener { pickSpeed() } })
        buttons.addView(qualityButton.apply { setOnClickListener { pickQuality() } })
        buttons.addView(host.label("弹幕", 14f, Color.WHITE).apply {
            padButton(this)
            setOnClickListener { danmakuSettings() }
        })
        buttons.addView(host.label("评论", 14f, Color.WHITE).apply {
            padButton(this)
            setOnClickListener { showComments() }
        })
        buttons.addView(episodesButton.apply { setOnClickListener { showEpisodes() } })
        bottomBar.addView(buttons)
        root.addView(bottomBar, frame(-1, -2, Gravity.BOTTOM))

        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser && player.duration > 0) time.text = "${format(player.duration * value / 1000)} / ${format(player.duration)}"
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    dragging = true
                    host.main.removeCallbacks(hideControls)
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    dragging = false
                    if (player.duration > 0) player.seekTo(player.duration * bar.progress / 1000)
                    scheduleHide()
                }
            }
        )
    }

    private inner class Gestures : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (panel != null) {
                closePanel()
                return true
            }
            showControls(topBar.visibility != View.VISIBLE)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            togglePause()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            val dy = e2.y - start.y
            if (abs(dy) < host.dp(80) || abs(vy) < abs(vx) || landscape) return false
            val data = detail ?: return false
            val target = if (dy < 0) episode + 1 else episode - 1
            if (target !in data.episodes.indices) {
                toast(if (dy < 0) "已经是最后一集" else "已经是第一集")
                return true
            }
            playEpisode(target, 0)
            return true
        }
    }

    private fun load() {
        val known = detail
        if (known != null) {
            start(known)
            return
        }
        work.run(
            { host.repository.detail(series.id) },
            { result ->
                detail = result
                start(result)
            },
            { problem -> showFailure("剧集加载失败：${friendlyError(problem)}") { load() } },
        )
    }

    private fun start(data: Detail) {
        if (data.episodes.isEmpty()) {
            showFailure("这部剧暂时没有可播放的剧集", null)
            return
        }
        val local = if (host.library.isLoaded) host.library.progress(series.id) else null
        val target =
            requestedEpisode?.let { com.hongguotv.core.ResumeTarget(it.coerceIn(0, data.episodes.lastIndex), if (local?.episodeIndex == it && local.completed.not()) local.position else 0) }
                ?: ResumePlayback.target(data.episodes, local?.episodeId, local?.position ?: 0, local?.completed == true)
        if (host.library.isLoaded) host.library.observeFavorite(series.id, data.episodes.size, System.currentTimeMillis(), acknowledge = true)
        playEpisode(target.index, target.position)
    }

    private fun maxQuality() = if (host.library.isLoaded) host.library.maxQuality else 720

    private fun episodeSource(remote: RemoteVideo, id: String) =
        ProgressiveMediaSource.Factory { VideoDataSource(remote) }
            .createMediaSource(
                MediaItem.Builder()
                    .setMediaId(id)
                    .setUri("hongguotv://episode/$id")
                    .setMimeType(MimeTypes.VIDEO_MP4)
                    .build()
            )

    /**
     * Prepare the next episode once the current one has played a few seconds with a healthy buffer:
     * resolve its address and MP4 index, then append it to the playlist so ExoPlayer buffers into it
     * and crosses the boundary without a stop.
     */
    private fun maybePreloadNext(now: Long) {
        val data = detail ?: return
        if (!ready || failed || !player.isPlaying || nextIndex >= 0 || episode >= data.episodes.lastIndex) return
        if (now - readyAt < 3000 || now < preloadRetryAt || preloadAttempts >= 3) return
        val remaining = player.duration - player.currentPosition
        if (player.totalBufferedDuration < 8000 && remaining > 45000) return
        val index = episode + 1
        val id = data.episodes[index]
        val quality = maxQuality()
        preloadAttempts++
        nextIndex = index
        preloadWork.run(
            {
                openStream(
                    host.repository.http,
                    host.repository,
                    id,
                    quality,
                    CodecCapabilities::supports,
                    // 与首页试播一致，按单集请求播放信息；下一集由预加载单独准备。
                    metadataAhead = emptyList(),
                ) {
                    it == null || CodecCapabilities.supports(it)
                } ?: throw java.io.IOException("下一集没有可播放的清晰度")
            },
            { opened: OpenedStream ->
                if (nextIndex != index || episode + 1 != index || nextVideo != null) {
                    opened.video.close()
                    return@run
                }
                nextVideo = opened.video
                player.addMediaSource(episodeSource(opened.video, id))
                android.util.Log.i("HongguoTV", "Phone next episode queued episode=${index + 1}")
            },
            {
                if (nextIndex == index) nextIndex = -1
                preloadRetryAt = android.os.SystemClock.elapsedRealtime() + 15000
            },
        )
    }

    /** The playlist moved onto the preloaded episode: adopt it as current and drop the old item. */
    private fun advanced() {
        val data = detail ?: return
        val incoming = nextVideo ?: return
        val previous = episode
        if (previous in data.episodes.indices && host.library.isLoaded)
            host.library.save(
                WatchProgress(
                    data.series.copy(title = series.title, cover = series.cover.ifBlank { data.series.cover }),
                    data.episodes[previous],
                    previous,
                    0,
                    0,
                    true,
                    System.currentTimeMillis(),
                )
            )
        episode = nextIndex
        nextIndex = -1
        nextVideo = null
        preloadAttempts = 0
        preloadRetryAt = 0
        readyAt = android.os.SystemClock.elapsedRealtime()
        val old = video
        video = incoming
        // The old period is finished; remove it before closing its stream.
        while (player.mediaItemCount > 1 && player.currentMediaItemIndex > 0) player.removeMediaItem(0)
        old?.close()
        title.text = "${series.title} · 第 ${episode + 1} 集"
        episodesButton.text = "选集 ${episode + 1}/${data.episodes.size}"
        seek.progress = 0
        seek.secondaryProgress = 0
        time.text = "00:00 / --:--"
        flashEpisode("第 ${episode + 1} 集")
        saveProgress()
        android.util.Log.i("HongguoTV", "Phone seamless episode transition episode=${episode + 1}")
    }

    private fun discardNext() {
        preloadWork.cancel()
        nextIndex = -1
        nextVideo?.close()
        nextVideo = null
        preloadAttempts = 0
        preloadRetryAt = 0
    }

    private fun flashEpisode(text: String) {
        episodeToast.text = text
        episodeToast.animate().cancel()
        episodeToast.alpha = 0f
        episodeToast.animate().alpha(1f).setDuration(200).withEndAction {
            episodeToast.animate().alpha(0f).setStartDelay(1400).setDuration(400).start()
        }.start()
    }

    private fun playEpisode(index: Int, position: Long, automatic: Boolean = false) {
        val data = detail ?: return
        if (!automatic) autoRetries = 0
        if (ready) saveProgress()
        // 手动切到已预加载的下一集：直接跳到播放列表下一项，无需重新取地址。
        if (index == nextIndex && nextVideo != null && position == 0L && !failed && player.hasNextMediaItem()) {
            closePanel()
            userPaused = false
            centerPause.visibility = View.GONE
            player.seekToNextMediaItem()
            player.playWhenReady = visible
            return
        }
        work.cancel()
        discardNext()
        closePanel()
        episode = index
        ready = false
        failed = false
        userPaused = false
        retry.visibility = View.GONE
        centerPause.visibility = View.GONE
        title.text = "${series.title} · 第 ${index + 1} 集"
        episodesButton.text = "选集 ${index + 1}/${data.episodes.size}"
        status.text = "正在获取播放地址…"
        player.stop()
        player.clearMediaItems()
        video?.close()
        video = null
        val id = data.episodes[index]
        val quality = maxQuality()
        work.run(
            {
                openStream(
                    host.repository.http,
                    host.repository,
                    id,
                    quality,
                    CodecCapabilities::supports,
                    // 与首页试播一致，按单集请求播放信息；下一集由预加载单独准备。
                    metadataAhead = emptyList(),
                ) {
                    it == null || CodecCapabilities.supports(it)
                } ?: throw java.io.IOException("没有手机可播放的清晰度，请尝试其他剧集")
            },
            { opened: OpenedStream ->
                video = opened.video
                this.quality = opened.rendition?.let { "${it.quality}P" } ?: opened.video.info.quality
                qualityButton.text = this.quality.ifBlank { "清晰度" }
                status.text = "正在缓冲…"
                val source = episodeSource(opened.video, id)
                        player.setPlaybackSpeed(if (host.library.isLoaded) host.library.playbackSpeed else 1f)
                speedButton.text = PlaybackSpeed.label(player.playbackParameters.speed)
                player.setMediaSource(source)
                player.seekTo(position.coerceAtLeast(0))
                player.prepare()
                player.playWhenReady = visible
                scheduleHide()
            },
            { problem -> fail(problem) },
        )
    }

    private fun fail(problem: Throwable) {
        failed = true
        player.pause()
        val decode = problem is PlaybackException && problem.errorCode in 4000..4999
        val resume = if (ready) player.currentPosition else 0L
        android.util.Log.w("HongguoTV", "Phone playback failure episode=${episode + 1}: ${playbackFailureSummary(problem)}")
        val causes = generateSequence(problem) { it.cause }.take(8).toList()
        // 本集只有本机无法解码的编码（bytevc2）：重试结果相同，直接提示并提供下一集。
        if (causes.any { it is NoPlayableQualityException }) {
            showFailure("本集只有手机无法解码的编码（VVC），可以先看其他集") { playEpisode(episode, 0) }
            val data = detail
            if (data != null && episode < data.episodes.lastIndex)
                retry.addView(host.pill("看下一集") { playEpisode(episode + 1, 0) }, 0, lp(-2, -2).apply { rightMargin = host.dp(10) })
            return
        }
        // 空响应、网络抖动属于平台侧的短暂故障：自动重试几次（仓库在连续空响应后会轮换设备身份）。
        val transient =
            !decode && causes.any {
                it is com.hongguotv.core.EmptyPlaybackResponseException || it is java.io.IOException
            }
        if (transient && autoRetries < AUTO_RETRY_DELAYS.size) {
            val delay = AUTO_RETRY_DELAYS[autoRetries++]
            val target = episode
            status.text = "${playbackFailureSummary(problem)}，${delay / 1000} 秒后自动重试（$autoRetries/${AUTO_RETRY_DELAYS.size}）"
            retry.visibility = View.GONE
            host.main.postDelayed({ if (failed && episode == target && !isDestroyed) playEpisode(target, resume, automatic = true) }, delay)
            return
        }
        showFailure(
            if (decode) "手机无法解码当前视频，可尝试降低清晰度" else "播放失败：${playbackFailureSummary(problem)}",
        ) { playEpisode(episode, if (ready) player.currentPosition else 0) }
    }

    private fun showFailure(message: String, onRetry: (() -> Unit)?) {
        status.text = message
        retry.removeAllViews()
        if (onRetry != null) retry.addView(host.pill("重试", selected = true) { onRetry() }, lp(-2, -2).apply { rightMargin = host.dp(10) })
        if (detail != null && maxQuality() > 480)
            retry.addView(host.pill("降到 480P") {
                if (host.library.isLoaded) host.library.maxQuality = 480
                playEpisode(episode, 0)
            })
        retry.visibility = View.VISIBLE
        showControls(true)
    }

    private fun togglePause() {
        if (failed || !ready) return
        userPaused = player.playWhenReady
        player.playWhenReady = !userPaused
        if (player.playbackState == Player.STATE_ENDED && !userPaused) player.seekTo(0)
        centerPause.visibility = if (userPaused) View.VISIBLE else View.GONE
        if (userPaused) saveProgress()
        paintPlay()
        scheduleHide()
    }

    private fun paintPlay() {
        playButton.text = if (player.playWhenReady) "暂停" else "播放"
    }

    private fun showControls(show: Boolean) {
        val value = if (show) View.VISIBLE else View.GONE
        topBar.visibility = value
        bottomBar.visibility = value
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        host.main.removeCallbacks(hideControls)
        host.main.postDelayed(hideControls, 4000)
    }

    private fun updateProgress() {
        if (dragging) return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        seek.progress = (position * 1000 / duration).toInt()
        seek.secondaryProgress = (player.bufferedPosition * 1000 / duration).toInt()
        time.text = "${format(position)} / ${format(duration)}"
    }

    private fun format(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun saveProgress(completed: Boolean = false) {
        val data = detail ?: return
        if (!ready || !host.library.isLoaded || episode !in data.episodes.indices) return
        host.library.save(
            WatchProgress(
                data.series.copy(
                    title = series.title,
                    cover = series.cover.ifBlank { data.series.cover },
                ),
                data.episodes[episode],
                episode,
                player.currentPosition.coerceAtLeast(0),
                player.duration.coerceAtLeast(0),
                completed || player.playbackState == Player.STATE_ENDED,
                System.currentTimeMillis(),
            )
        )
    }

    /**
     * Phone-sized danmaku settings. Edits the same "social" preferences TvSocial reads (enabled,
     * font, lanes, travel), then reloads so lane layout and visibility take effect immediately.
     */
    private fun danmakuSettings() {
        val prefs = host.getSharedPreferences("social", 0)
        fun labels() =
            arrayOf(
                "弹幕：${if (prefs.getBoolean("enabled", true)) "开" else "关"}",
                "字号：${prefs.getInt("font", 24)}",
                "密度：${prefs.getInt("lanes", 2)} 行",
                "速度：${if (prefs.getInt("travel", 12000) == 12000) "慢速" else "标准"}",
            )
        AlertDialog.Builder(host)
            .setTitle("弹幕设置")
            .setItems(labels()) { _, which ->
                val edit = prefs.edit()
                when (which) {
                    0 -> edit.putBoolean("enabled", !prefs.getBoolean("enabled", true))
                    1 -> prefs.getInt("font", 24).let { edit.putInt("font", if (it >= 32) 20 else it + 4) }
                    2 -> edit.putInt("lanes", prefs.getInt("lanes", 2) % 4 + 1)
                    else -> edit.putInt("travel", if (prefs.getInt("travel", 12000) == 12000) 9000 else 12000)
                }
                edit.apply()
                social.reset()
                host.main.post { danmakuSettings() }
            }
            .setNegativeButton("完成", null)
            .show()
    }

    private fun pickSpeed() {
        val options = PlaybackSpeed.options
        AlertDialog.Builder(host)
            .setTitle("倍速")
            .setSingleChoiceItems(options.map(PlaybackSpeed::label).toTypedArray(), options.indexOf(player.playbackParameters.speed)) { dialog, index ->
                player.setPlaybackSpeed(options[index])
                if (host.library.isLoaded) host.library.playbackSpeed = options[index]
                speedButton.text = PlaybackSpeed.label(options[index])
                dialog.dismiss()
            }
            .show()
    }

    private fun pickQuality() {
        val options = PlaybackQuality.options
        AlertDialog.Builder(host)
            .setTitle("最高清晰度")
            .setSingleChoiceItems(options.map { "${it}P" }.toTypedArray(), options.indexOf(maxQuality())) { dialog, index ->
                dialog.dismiss()
                if (options[index] == maxQuality()) return@setSingleChoiceItems
                if (host.library.isLoaded) host.library.maxQuality = options[index]
                playEpisode(episode, if (ready) player.currentPosition else 0)
            }
            .show()
    }

    private fun showEpisodes() {
        val data = detail ?: return
        closePanel()
        val sheet =
            host.column().apply {
                setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16))
                background = host.rounded(PhoneStyle.surface, 16)
                isClickable = true
            }
        sheet.addView(host.label("选集 · 共 ${data.episodes.size} 集", 16f, bold = true))
        val grid = EpisodeGrid(host, data.episodes.size, episode) { index ->
            closePanel()
            if (index != episode) playEpisode(index, 0)
        }
        sheet.addView(grid.view, lp(-1, (host.resources.displayMetrics.heightPixels * .45f).toInt()).apply { topMargin = host.dp(12) })
        panel = sheet
        (view as FrameLayout).addView(sheet, frame(-1, -2, Gravity.BOTTOM))
        grid.scrollTo(episode)
    }

    private fun closePanel() {
        panel?.let { (view as FrameLayout).removeView(it) }
        panel = null
        comments?.dispose()
        comments = null
    }

    private fun showComments() {
        val data = detail ?: return
        val id = data.episodes.getOrNull(episode) ?: return
        closePanel()
        host.main.removeCallbacks(hideControls)
        val sheet = CommentSheet(host, series.id, id, "第 ${episode + 1} 集") { closePanel() }
        comments = sheet
        panel = sheet.view
        (view as FrameLayout).addBottomSheet(host, sheet.view)
    }

    private fun toggleOrientation() {
        landscape = !landscape
        host.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun toast(message: String) = android.widget.Toast.makeText(host, message, android.widget.Toast.LENGTH_SHORT).show()

    /** Hide or restore system bars without touching the activity's edge-to-edge layout setup. */
    private fun immersive(on: Boolean) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(host.window, host.window.decorView)
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (on) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(bars)
        } else controller.show(bars)
    }

    override fun onBack(): Boolean {
        if (panel != null) {
            closePanel()
            return true
        }
        if (landscape) {
            toggleOrientation()
            return true
        }
        return false
    }

    override fun onShow() {
        visible = true
        immersive(true)
        if (landscape) host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (ready && !userPaused && !failed) player.play()
        host.main.removeCallbacks(tick)
        host.main.post(tick)
        social.foreground(true)
    }

    override fun onHide() {
        visible = false
        saveProgress()
        player.pause()
        social.foreground(false)
        host.main.removeCallbacks(tick)
        host.main.removeCallbacks(hideControls)
        immersive(false)
        host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun destroy() {
        super.destroy()
        isDestroyed = true
        saveProgress()
        discardNext()
        closePanel()
        social.destroy()
        host.main.removeCallbacks(tick)
        host.main.removeCallbacks(hideControls)
        surface.player = null
        player.release()
        video?.close()
        video = null
    }
}

/** Episode number grid used by the player sheet and the detail page. */
internal class EpisodeGrid(
    private val host: PhoneActivity,
    private val count: Int,
    private var current: Int,
    private val onPick: (Int) -> Unit,
) {
    private val adapter =
        object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = count

            override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
                object : androidx.recyclerview.widget.RecyclerView.ViewHolder(
                    host.label("", 15f).apply {
                        gravity = Gravity.CENTER
                        layoutParams = ViewGroup.MarginLayoutParams(-1, host.dp(44)).apply { setMargins(host.dp(4), host.dp(4), host.dp(4), host.dp(4)) }
                    }
                ) {}

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val cell = holder.itemView as TextView
                val active = position == current
                cell.text = "${position + 1}"
                cell.setTextColor(if (active) PhoneStyle.background else PhoneStyle.text)
                cell.background = host.rounded(if (active) PhoneStyle.accent else PhoneStyle.raised, 8)
                cell.setOnClickListener { onPick(position) }
            }
        }

    val view =
        androidx.recyclerview.widget.RecyclerView(host).apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(host, 6)
            adapter = this@EpisodeGrid.adapter
            isNestedScrollingEnabled = true
        }

    fun scrollTo(index: Int) = view.scrollToPosition(index)

    fun mark(index: Int) {
        val old = current
        current = index
        if (old in 0 until count) adapter.notifyItemChanged(old)
        if (index in 0 until count) adapter.notifyItemChanged(index)
    }
}
