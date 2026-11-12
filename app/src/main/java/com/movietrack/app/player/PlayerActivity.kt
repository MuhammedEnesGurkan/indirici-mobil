package com.movietrack.app.player

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.movietrack.app.App
import com.movietrack.app.util.Lang
import kotlin.math.abs

/**
 * Dahili oynatıcı (Media3/ExoPlayer): MP4/MKV/WebM/TS, harici altyazı (SRT/VTT/ASS), ses/altyazı izi
 * seçimi, hız, döngü, en-boy oranı, Resim içinde Resim, ekran kilidi, parlaklık/ses hareketleri,
 * kaldığın yerden devam.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var hint: TextView
    private var locked = false
    private var resumeKey = ""

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

        // Telefonda yerleşik çözücüsü olmayan sesler (AC-3, E-AC-3, DTS, MP2…): uygulamada FFmpeg çözücü
        // varsa onunla çalınır (indirici'de var; yoksa bu ayar etkisizdir)
        player = ExoPlayer.Builder(this, androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)).build()
        view = PlayerView(this).apply {
            this.player = this@PlayerActivity.player
            setShowSubtitleButton(true)
            keepScreenOn = true
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        hint = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE); textSize = 18f; visibility = View.GONE
            setBackgroundColor(0x88000000.toInt()); setPadding(24, 12, 24, 12)
        }
        val root = FrameLayout(this)
        root.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(toolbar(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
        setupGestures()

        val subs = intent.getStringArrayListExtra("subs").orEmpty()
        val langs = intent.getStringArrayListExtra("subLangs").orEmpty()
        val subConfigs = subs.mapIndexed { i, s ->
            val mime = when {
                s.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
                s.endsWith(".ass", true) || s.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s)).setMimeType(mime).setLanguage(langs.getOrNull(i)).setLabel("subtitle:${Lang.normalize(langs.getOrNull(i)).ifEmpty { "und" }}")
                .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        if (subConfigs.isNotEmpty()) {
            // Altyazı varsa açık başlasın: ilk altyazının dili tercih edilir, dili bilinmeyen de seçilebilir
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(langs.firstOrNull()?.let { Lang.normalize(it) }?.takeIf { it.isNotEmpty() && it != "und" })
                .setSelectUndeterminedTextLanguage(true)
                .build()
        }
        player.setMediaItem(MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subConfigs).build())
        player.prepare()
        val pos = App.instance.settings.resumePosition(resumeKey)
        if (pos > 5000) player.seekTo(pos)
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) App.instance.settings.saveResumePosition(resumeKey, 0)
            }
        })
    }

    private fun toolbar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(16, 48, 16, 0) }
        fun btn(icon: Int, desc: String, onClick: (View) -> Unit) = ImageButton(this).apply {
            setImageResource(icon); contentDescription = desc; background = null; setColorFilter(android.graphics.Color.WHITE)
            setOnClickListener(onClick)
        }.also { bar.addView(it, LinearLayout.LayoutParams(120, 120)) }
        val title = TextView(this).apply { text = intent.getStringExtra("title") ?: ""; setTextColor(android.graphics.Color.WHITE); textSize = 15f; maxLines = 1 }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        btn(android.R.drawable.ic_menu_manage, "Hız ve ayarlar") { v -> menu(v) }
        btn(android.R.drawable.ic_menu_crop, "En-boy oranı") {
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
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp -> pm.menu.add("Hız ${sp}×").setOnMenuItemClickListener { player.playbackParameters = PlaybackParameters(sp); true } }
        pm.menu.add("Ses izi seç (aud:)").setOnMenuItemClickListener { trackDialog(C.TRACK_TYPE_AUDIO, "Ses izi"); true }
        pm.menu.add("Altyazı seç (subtitle:)").setOnMenuItemClickListener { trackDialog(C.TRACK_TYPE_TEXT, "Altyazı"); true }
        pm.menu.add(if (player.repeatMode == Player.REPEAT_MODE_ONE) "Döngüyü kapat" else "Döngü").setOnMenuItemClickListener {
            player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE; true
        }
        pm.show()
    }

    private fun trackDialog(type: Int, title: String) {
        TrackSelectionDialogBuilder(this, title, player, type).build().show()
    }

    /** Sol yarıda dikey kaydırma: parlaklık, sağ yarıda: ses. Çift dokunma: ±10 sn. */
    private fun setupGestures() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || e1 == null || abs(dy) < abs(dx)) return false
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
                if (locked) return false
                val d = if (e.x < view.width / 2) -10_000 else 10_000
                player.seekTo((player.currentPosition + d).coerceAtLeast(0))
                flash(if (d < 0) "« 10 sn" else "10 sn »")
                return true
            }
        })
        view.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); false }
    }

    private fun flash(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hint.removeCallbacks(hideHint)
        hint.postDelayed(hideHint, 900)
    }

    private val hideHint = Runnable { hint.visibility = View.GONE }

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
    }

    override fun onStop() {
        super.onStop()
        App.instance.settings.saveResumePosition(resumeKey, player.currentPosition)
        if (!isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
