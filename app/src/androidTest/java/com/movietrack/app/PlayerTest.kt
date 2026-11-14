package com.movietrack.app

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.movietrack.app.player.PlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Oynatıcı: harici altyazı uygulamada çizilir, zamanlaması kaydırılabilir; sarma adımı ayardan gelir. */
@RunWith(AndroidJUnit4::class)
class PlayerTest {
    @get:org.junit.Rule val timeoutRule: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    private fun field(a: Any, name: String): Any? = a.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(a)
    private fun call(a: Any, name: String, vararg args: Any) {
        a.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(a, *args)
    }

    @Test fun subtitleSyncAndSeekStep() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val video = File(ctx.cacheDir, "player-test.ts")
        inst.context.assets.open("fx/split/v0.ts").use { i -> video.outputStream().use { i.copyTo(it) } }
        val srt = File(ctx.cacheDir, "player-test.tr.srt")
        srt.writeText("﻿1\n00:00:00,000 --> 00:01:40,000\n<i>Merhaba</i> altyazı\n")
        App.instance.settings.seekStepSec = 30
        val key = Uri.fromFile(video).toString()
        App.instance.settings.saveSubOffset(key, 0)
        val i = Intent(ctx, PlayerActivity::class.java).setData(Uri.fromFile(video))
            .putStringArrayListExtra("subs", arrayListOf(Uri.fromFile(srt).toString()))
            .putStringArrayListExtra("subLangs", arrayListOf("tr"))
        ActivityScenario.launch<PlayerActivity>(i).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a ->
                assertEquals("harici altyazı okunmalı", 1, (field(a, "extSubs") as List<*>).size)
                val shown = field(a, "shownCues") as List<*>?
                Log.i("PlayerTest", "gösterilen: $shown")
                assertEquals("altyazı ekranda olmalı", 1, shown?.size)
                // Altyazıyı 200 sn geciktir: şu an hiçbir satır görünmemeli
                call(a, "shiftSubs", 200_000L)
                assertTrue("kaydırınca altyazı kaybolmalı", (field(a, "shownCues") as List<*>).isEmpty())
                assertEquals(200_000L, App.instance.settings.subOffset(key))
                call(a, "shiftSubs", -200_000L)
                assertEquals(1, (field(a, "shownCues") as List<*>).size)
                val view = field(a, "view") as androidx.media3.ui.PlayerView
                assertEquals("ileri düğmesi ayardaki adımı kullanmalı", 30_000L, view.player!!.seekForwardIncrement)
                assertEquals(30_000L, view.player!!.seekBackIncrement)
            }
        }
        App.instance.settings.seekStepSec = 10
        Log.i("PlayerTest", "TAMAM")
    }
}
