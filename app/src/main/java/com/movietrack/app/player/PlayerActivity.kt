package com.movietrack.app.player

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.movietrack.app.App
import com.movietrack.app.download.Subtitles
import com.movietrack.app.util.Lang
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dahili oynatıcı (Media3/ExoPlayer): MP4/MKV/WebM/TS, harici altyazı (SRT/VTT/ASS), ses/altyazı izi
 * seçimi, hız, döngü, en-boy oranı, iki parmakla yakınlaştırma/kaydırma, altyazı zamanlaması,
 * ayarlanabilir sarma adımı, Resim içinde Resim, ekran kilidi, parlaklık/ses hareketleri,
 * kaldığın yerden devam.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var hint: TextView
    private lateinit var overlay: SubtitleView
    private lateinit var syncPanel: View
    private lateinit var syncValue: TextView
    private var locked = false
    private var resumeKey = ""
    private val settings get() = App.instance.settings
    private val seekMs get() = settings.seekStepSec * 1000L

    // Yakınlaştırma (MX Player gibi iki parmakla büyüt/küçült, sürükle)
    private var zoom = 1f
    private var pinching = false

    // Harici altyazılar uygulamada çizilir: böylece zamanlaması canlı kaydırılabilir
    private class ExtSub(val lang: String, val cues: List<Subtitles.Cue>)
    private var extSubs = listOf<ExtSub>()
    private var extIdx = -1
    private var subOffsetMs = 0L
    private var shownCues: List<Subtitles.Cue>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val uri = intent.data ?: run { finish(); return }
        resumeKey = uri.toString()
        subOffsetMs = settings.subOffset(resumeKey)

        // Telefonda yerleşik çözücüsü olmayan sesler (AC-3, E-AC-3, DTS, MP2…): uygulamada FFmpeg çözücü
        // varsa onunla çalınır (indirici'de var; yoksa bu ayar etkisizdir)
        player = ExoPlayer.Builder(this, androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)).build()
        // İleri/geri düğmeleri ayarlardaki adımı kullanır (oynatıcı yeniden kurulmadan değişebilir)
        val controlled = object : ForwardingPlayer(player) {
            override fun getSeekBackIncrement() = seekMs
            override fun getSeekForwardIncrement() = seekMs
            override fun seekBack() = seekBy(-seekMs)
            override fun seekForward() = seekBy(seekMs)
        }
        view = PlayerView(this).apply {
            this.player = controlled
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
        }
        overlay = SubtitleView(this).apply { setUserDefaultStyle(); setUserDefaultTextSize() }
        hint = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; visibility = View.GONE
            setBackgroundColor(0x88000000.toInt()); setPadding(24, 12, 24, 12)
        }
        val root = FrameLayout(this)
        root.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(toolbar(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        syncPanel = syncPanel()
        root.addView(syncPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(150) })
        setContentView(root)
        setupGestures()

        val subs = intent.getStringArrayListExtra("subs").orEmpty()
        val langs = intent.getStringArrayListExtra("subLangs").orEmpty()
        // SRT/VTT okunabiliyorsa uygulamada çizilir; okunamayanlar (ASS vb.) ExoPlayer'a bırakılır
        val parsed = ArrayList<ExtSub>()
        val rest = ArrayList<Int>()
        subs.forEachIndexed { i, s ->
            val cues = try {
                val text = contentResolver.openInputStream(Uri.parse(s))?.use { it.readBytes().toString(Charsets.UTF_8) }?.removePrefix("﻿") ?: ""
                if (text.contains("[Script Info]")) emptyList() else Subtitles.parseVtt(text).first.sortedBy { it.start }
            } catch (_: Exception) { emptyList() }
            if (cues.isNotEmpty()) parsed += ExtSub(langs.getOrNull(i) ?: "", cues) else rest += i
        }
        extSubs = parsed
        val preferred = langs.firstOrNull()?.let { Lang.normalize(it) }?.takeIf { it.isNotEmpty() && it != "und" }
        if (extSubs.isNotEmpty()) extIdx = extSubs.indexOfFirst { Lang.normalize(it.lang) == preferred }.coerceAtLeast(0)

        val subConfigs = rest.map { i ->
            val s = subs[i]
            val mime = when {
                s.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
                s.endsWith(".ass", true) || s.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s)).setMimeType(mime).setLanguage(langs.getOrNull(i)).setLabel("subtitle:${Lang.normalize(langs.getOrNull(i)).ifEmpty { "und" }}")
                .setSelectionFlags(if (extIdx < 0 && i == rest.first()) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            // Uygulamanın çizdiği altyazı açıkken videodaki altyazı üst üste binmesin
            if (extIdx >= 0) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else if (subConfigs.isNotEmpty()) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(preferred).setSelectUndeterminedTextLanguage(true)
        }.build()
        view.setShowSubtitleButton(extSubs.isEmpty())
        player.setMediaItem(MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subConfigs).build())
        player.prepare()
        val pos = settings.resumePosition(resumeKey)
        if (pos > 5000) player.seekTo(pos)
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) settings.saveResumePosition(resumeKey, 0)
            }
        })
        overlay.post(subTick)
    }

    // ------------------------------------------------------------------ altyazı çizimi

    private val subTick = object : Runnable {
        override fun run() {
            renderSubs()
            overlay.postDelayed(this, 50)
        }
    }

    private fun renderSubs() {
        val sub = extSubs.getOrNull(extIdx)
        val now = if (sub == null) emptyList() else {
            val t = (player.currentPosition - subOffsetMs) / 1000.0
            sub.cues.filter { it.start <= t && t < it.end }
        }
        if (now == shownCues) return
        shownCues = now
        overlay.setCues(now.map { c ->
            androidx.media3.common.text.Cue.Builder()
                .setText(HtmlCompat.fromHtml(c.text.replace("\n", "<br>"), HtmlCompat.FROM_HTML_MODE_LEGACY))
                .build()
        })
    }

    private fun chooseSubtitle() {
        val names = extSubs.map { s -> if (s.lang.isBlank()) "Altyazı" else Lang.displayName(s.lang) } + listOf("Videodaki altyazılar…", "Kapalı")
        AlertDialog.Builder(this).setTitle("Altyazı")
            .setSingleChoiceItems(names.toTypedArray(), if (extIdx >= 0) extIdx else -1) { d, which ->
                d.dismiss()
                when {
                    which < extSubs.size -> { extIdx = which; textTracks(false) }
                    which == extSubs.size -> { extIdx = -1; textTracks(true); trackDialog(C.TRACK_TYPE_TEXT, "Altyazı") }
                    else -> { extIdx = -1; textTracks(false) }
                }
                renderSubs()
            }.show()
    }

    private fun textTracks(on: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on).build()
    }

    /** Altta açılan zamanlama paneli: video oynamaya devam eder, değişiklik anında görünür. */
    private fun syncPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xDD202020.toInt()); cornerRadius = dp(16).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true // dokunuşlar alttaki oynatıcıya geçmesin
        }
        syncValue = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER }
        panel.addView(syncValue)
        panel.addView(TextView(this).apply {
            text = "Altyazı erken geliyorsa +, geç geliyorsa − bas"; setTextColor(0xFFBBBBBB.toInt()); textSize = 12f; gravity = Gravity.CENTER
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun b(label: String, action: () -> Unit) = row.addView(TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(10).toFloat() }
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(8), dp(3), 0) })
        b("−1") { shiftSubs(-1000) }
        b("−0,1") { shiftSubs(-100) }
        b("Sıfırla") { shiftSubs(-subOffsetMs) }
        b("+0,1") { shiftSubs(100) }
        b("+1") { shiftSubs(1000) }
        b("✓") { panel.visibility = View.GONE }
        panel.addView(row)
        return panel
    }

    private fun shiftSubs(deltaMs: Long) {
        subOffsetMs += deltaMs
        settings.saveSubOffset(resumeKey, subOffsetMs)
        updateSyncLabel()
        renderSubs()
    }

    private fun updateSyncLabel() {
        val s = String.format(Locale("tr"), "%+.1f sn", subOffsetMs / 1000.0)
        syncValue.text = when {
            subOffsetMs > 0 -> "Altyazı gecikmesi: $s (daha geç)"
            subOffsetMs < 0 -> "Altyazı gecikmesi: $s (daha erken)"
            else -> "Altyazı gecikmesi: 0 sn"
        }
    }

    private fun openSync() {
        if (extSubs.isEmpty()) { flash("Zamanı ayarlanabilir altyazı yok"); return }
        if (extIdx < 0) { extIdx = 0; textTracks(false) }
        updateSyncLabel()
        syncPanel.visibility = if (syncPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ üst çubuk ve menü

    private fun toolbar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(16, 48, 16, 0) }
        fun btn(icon: Int, desc: String, onClick: (View) -> Unit) = ImageButton(this).apply {
            setImageResource(icon); contentDescription = desc; background = null; setColorFilter(Color.WHITE)
            setOnClickListener(onClick)
        }.also { bar.addView(it, LinearLayout.LayoutParams(120, 120)) }
        val title = TextView(this).apply { text = intent.getStringExtra("title") ?: ""; setTextColor(Color.WHITE); textSize = 15f; maxLines = 1 }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        // Altyazı zamanlaması: kayan altyazıyı düzeltmek için
        bar.addView(TextView(this).apply {
            text = "Aa±"; contentDescription = "Altyazı zamanlaması"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setOnClickListener { openSync() }
        }, LinearLayout.LayoutParams(120, 120))
        btn(android.R.drawable.ic_menu_manage, "Hız ve ayarlar") { v -> menu(v) }
        btn(android.R.drawable.ic_menu_crop, "En-boy oranı") {
            resetZoom()
            view.resizeMode = when (view.resizeMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            flash(when (view.resizeMode) { AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Yakınlaştır"; AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Doldur"; else -> "Sığdır" })
        }
        btn(android.R.drawable.ic_lock_lock, "Ekranı kilitle") {
            locked = !locked
            view.useController = !locked
            flash(if (locked) "Kilitli (açmak için kilide dokun)" else "Kilit açıldı")
        }
        if (Build.VERSION.SDK_INT >= 26) btn(android.R.drawable.ic_menu_slideshow, "Resim içinde Resim") { enterPip() }
        return bar
    }

    private fun menu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val speed = pm.menu.addSubMenu("Hız (${player.playbackParameters.speed}×)")
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp -> speed.add("${sp}×").setOnMenuItemClickListener { player.playbackParameters = PlaybackParameters(sp); true } }
        val step = pm.menu.addSubMenu("Sarma adımı (${settings.seekStepSec} sn)")
        SEEK_STEPS.forEach { sec ->
            step.add("$sec sn").setOnMenuItemClickListener { settings.seekStepSec = sec; flash("Sarma adımı: $sec sn"); true }
        }
        pm.menu.add("Ses izi seç (aud:)").setOnMenuItemClickListener { trackDialog(C.TRACK_TYPE_AUDIO, "Ses izi"); true }
        pm.menu.add("Altyazı seç (subtitle:)").setOnMenuItemClickListener {
            if (extSubs.isNotEmpty()) chooseSubtitle() else trackDialog(C.TRACK_TYPE_TEXT, "Altyazı"); true
        }
        pm.menu.add("Altyazı zamanlaması").setOnMenuItemClickListener { openSync(); true }
        if (zoom != 1f) pm.menu.add("Yakınlaştırmayı sıfırla").setOnMenuItemClickListener { resetZoom(); true }
        pm.menu.add(if (player.repeatMode == Player.REPEAT_MODE_ONE) "Döngüyü kapat" else "Döngü").setOnMenuItemClickListener {
            player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE; true
        }
        pm.show()
    }

    private fun trackDialog(type: Int, title: String) {
        TrackSelectionDialogBuilder(this, title, player, type).build().show()
    }

    // ------------------------------------------------------------------ hareketler

    private fun contentFrame(): View? = view.findViewById(androidx.media3.ui.R.id.exo_content_frame)

    private fun applyZoom() {
        val f = contentFrame() ?: return
        f.scaleX = zoom; f.scaleY = zoom
        // Görüntü ekrandan tamamen kaçmasın
        val mx = (f.width * (zoom - 1f) / 2f).coerceAtLeast(0f)
        val my = (f.height * (zoom - 1f) / 2f).coerceAtLeast(0f)
        f.translationX = f.translationX.coerceIn(-mx, mx)
        f.translationY = f.translationY.coerceIn(-my, my)
    }

    private fun resetZoom() {
        zoom = 1f
        contentFrame()?.apply { translationX = 0f; translationY = 0f }
        applyZoom()
    }

    private fun seekBy(d: Long) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + d).coerceIn(0, dur))
    }

    private var seekSum = 0L
    private var lastSeekAt = 0L

    /**
     * Sol yarıda dikey kaydırma: parlaklık, sağ yarıda: ses. Çift dokunma: kenarlarda ayarlardaki adım kadar
     * sarar (art arda dokundukça toplanır), ortada oynat/duraklat. İki parmak: büyüt/küçült ve sürükle.
     */
    private fun setupGestures() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        var lastFx = 0f; var lastFy = 0f
        val sgd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                if (locked) return false
                lastFx = d.focusX; lastFy = d.focusY
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoom = (zoom * d.scaleFactor).coerceIn(0.25f, 4f)
                contentFrame()?.let { f ->
                    f.translationX += d.focusX - lastFx
                    f.translationY += d.focusY - lastFy
                }
                lastFx = d.focusX; lastFy = d.focusY
                applyZoom()
                flash("%${(zoom * 100).roundToInt()}")
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                // %100'e yakınsa tam oturt (orijinal boyuta dönmek kolay olsun)
                if (abs(zoom - 1f) < 0.06f) { resetZoom(); flash("Orijinal boyut") }
            }
        })
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || pinching || e1 == null || e2.pointerCount > 1 || abs(dy) < abs(dx)) return false
                val h = view.height.toFloat().coerceAtLeast(1f)
                if (e1.x < view.width / 2) {
                    val lp = window.attributes
                    val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                    lp.screenBrightness = (cur + dy / h).coerceIn(0.02f, 1f)
                    window.attributes = lp
                    flash("Parlaklık %${(lp.screenBrightness * 100).toInt()}")
                } else {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val step = (dy / h * max * 1.5f)
                    if (abs(step) >= 1) {
                        val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + step.toInt()).coerceIn(0, max)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        flash("Ses $v/$max")
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked || pinching) return false
                val third = view.width / 3f
                if (e.x in third..third * 2) {
                    player.playWhenReady = !player.playWhenReady
                    flash(if (player.playWhenReady) "▶" else "❚❚")
                    return true
                }
                val d = if (e.x < third) -seekMs else seekMs
                val now = System.currentTimeMillis()
                seekSum = if (now - lastSeekAt < 1500 && (seekSum > 0) == (d > 0)) seekSum + d else d
                lastSeekAt = now
                seekBy(d)
                flash(if (d < 0) "« ${abs(seekSum) / 1000} sn" else "${seekSum / 1000} sn »")
                return true
            }
        })
        view.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
            sgd.onTouchEvent(ev)
            if (ev.pointerCount > 1 || sgd.isInProgress) pinching = true
            if (pinching) return@setOnTouchListener ev.actionMasked != MotionEvent.ACTION_UP
            gd.onTouchEvent(ev)
            false
        }
    }

    private fun flash(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hint.removeCallbacks(hideHint)
        hint.postDelayed(hideHint, 900)
    }

    private val hideHint = Runnable { hint.visibility = View.GONE }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------------ PiP ve yaşam döngüsü

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        val fmt = player.videoFormat
        val ratio = if (fmt != null && fmt.width > 0 && fmt.height > 0) Rational(fmt.width, fmt.height) else Rational(16, 9)
        val clamped = when {
            ratio.toFloat() > 2.39f -> Rational(239, 100)
            ratio.toFloat() < 0.42f -> Rational(42, 100)
            else -> ratio
        }
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(clamped).build())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        view.useController = !isInPictureInPictureMode && !locked
        if (isInPictureInPictureMode) { syncPanel.visibility = View.GONE; resetZoom() }
    }

    override fun onStop() {
        super.onStop()
        settings.saveResumePosition(resumeKey, player.currentPosition)
        if (!isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.removeCallbacks(subTick)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    companion object {
        val SEEK_STEPS = listOf(5, 10, 15, 30, 60)
    }
}
