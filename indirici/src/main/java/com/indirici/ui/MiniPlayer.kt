package com.indirici.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Uygulama içi basit müzik oynatıcı: Dosyalar'da bir ses dosyasına dokununca alttaki çubukta çalar. */
object MiniPlayer {
    private var player: ExoPlayer? = null
    val current = MutableStateFlow<DownloadEntity?>(null)
    val playing = MutableStateFlow(false)

    fun play(ctx: Context, e: DownloadEntity) {
        val uri = Open.contentUri(ctx, e.outputUri) ?: return
        val p = player ?: ExoPlayer.Builder(ctx.applicationContext, androidx.media3.exoplayer.DefaultRenderersFactory(ctx.applicationContext)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)).build().also { pl ->
            player = pl
            pl.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing.value = isPlaying }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) playing.value = false }
            })
        }
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.play()
        current.value = e
    }

    fun toggle() { player?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } } }
    fun close() { player?.release(); player = null; current.value = null; playing.value = false }
    fun position() = player?.currentPosition ?: 0L
    fun duration() = player?.duration?.takeIf { it > 0 } ?: 0L
}

@Composable
fun MiniPlayerBar(modifier: Modifier = Modifier) {
    val e by MiniPlayer.current.collectAsState()
    val playing by MiniPlayer.playing.collectAsState()
    val item = e ?: return
    val c = MaterialTheme.colorScheme
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(item.id) { while (true) { pos = MiniPlayer.position(); dur = MiniPlayer.duration(); delay(500) } }
    Box(modifier.padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerHighest)
        .semantics(mergeDescendants = false) { contentDescription = "Oynatıcı: ${item.fileName}" }) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FileThumb(categoryOf(item), size = 44.dp, radius = 12.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp).clickable { MiniPlayer.toggle() }) {
                Text(item.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${Format.duration(pos / 1000)} / ${if (dur > 0) Format.duration(dur / 1000) else "—"}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            FilledIconButton(onClick = { MiniPlayer.toggle() }, shape = RoundedCornerShape(14.dp)) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Duraklat" else "Oynat")
            }
            IconButton(onClick = { MiniPlayer.close() }) { Icon(Icons.Rounded.Close, "Oynatıcıyı kapat") }
        }
        if (dur > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).height(3.dp).background(c.primary))
    }
}
