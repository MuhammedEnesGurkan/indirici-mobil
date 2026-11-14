package com.movietrack.app

import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.download.FileTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class FileTagTest {
    private val e = DownloadEntity(
        url = "https://cdn.example.com/v/master.m3u8?token=abc&x=1", type = "hls", fileName = "Dizi - S01E02.mp4",
        pageUrl = "https://example.com/dizi/1/2", title = "Dizi – S01E02 ğüşiöç", labels = "1080p aud:tr subtitle:en", createdAt = 1_700_000_000_000,
    )

    private fun temp(bytes: ByteArray): File = File.createTempFile("tag", ".bin").apply { deleteOnExit(); writeBytes(bytes) }

    private fun tag(f: File): Boolean = RandomAccessFile(f, "rw").use { FileTag.append(it.channel, it.channel, e) }

    private fun read(f: File): FileTag.Info? = RandomAccessFile(f, "r").use { FileTag.readTail(it.channel) }

    private fun box(type: String, body: Int): ByteArray =
        ByteBuffer.allocate(8 + body).putInt(8 + body).put(type.toByteArray()).array()

    private fun mp4(): ByteArray = box("ftyp", 16).also { System.arraycopy("isom".toByteArray(), 0, it, 8, 4) } + box("moov", 500) + box("mdat", 4000)

    private fun ts(packets: Int): ByteArray = ByteArray(188 * packets).also { b -> for (i in 0 until packets) { b[i * 188] = 0x47; b[i * 188 + 1] = 0x01; b[i * 188 + 2] = 0x00 } }

    private fun check(info: FileTag.Info?) {
        requireNotNull(info)
        assertEquals(e.url, info.url)
        assertEquals(e.pageUrl, info.pageUrl)
        assertEquals(e.title, info.title)
        assertEquals(e.labels, info.labels)
        assertEquals(e.createdAt, info.createdAt)
    }

    @Test fun mp4RoundTrip() {
        val f = temp(mp4())
        val before = f.length()
        assertTrue(tag(f))
        check(read(f))
        // Eklenen alan geçerli bir üst düzey "free" kutusu
        val raw = f.readBytes()
        val len = ByteBuffer.wrap(raw, before.toInt(), 4).int
        assertEquals(f.length() - before, len.toLong())
        assertEquals("free", String(raw, before.toInt() + 4, 4))
        // İkinci kez yazılmaz
        assertFalse(tag(f))
        assertEquals(before + len, f.length())
    }

    @Test fun mp4WithOpenEndedBoxIsLeftAlone() {
        val bytes = mp4()
        ByteBuffer.wrap(bytes).putInt(bytes.size - 4008, 0) // mdat boyutu 0: "dosya sonuna kadar"
        val f = temp(bytes)
        assertFalse(tag(f))
        assertEquals(bytes.size.toLong(), f.length())
    }

    @Test fun mkvRoundTrip() {
        val f = temp(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(3000))
        assertTrue(tag(f))
        check(read(f))
    }

    @Test fun tsRoundTripKeepsPacketAlignment() {
        val f = temp(ts(40))
        assertTrue(tag(f))
        assertEquals(0L, f.length() % 188)
        check(read(f))
    }

    @Test fun otherFilesAreNotTouched() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(5000)
        val f = temp(zip)
        assertFalse(tag(f))
        assertEquals(zip.size.toLong(), f.length())
        assertNull(read(f))
    }

    @Test fun untaggedFileReadsNull() {
        assertNull(read(temp(mp4())))
    }
}
