package com.movietrack.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.movietrack.app.App
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.data.Status
import com.movietrack.app.util.Category
import com.movietrack.app.util.Format
import com.movietrack.app.util.KV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** İndirme isteği (tarayıcı, URL ekleme, panodan, paylaşımdan). */
data class NewDownload(
    val url: String,
    val fileName: String,
    val type: String = "direct",
    val mime: String = "",
    val size: Long = 0,
    val referer: String? = null,
    val userAgent: String? = null,
    val pageUrl: String? = null,
    val title: String? = null,
    val meta: Map<String, String> = emptyMap(),
    val labels: String = "",
)

/** Anlık (veritabanına yazılmayan) ilerleme bilgisi. */
data class LiveProgress(val bytes: Long, val total: Long, val speed: Long, val etaSec: Long, val phase: String, val segDone: Int = 0, val segTotal: Int = 0)

class DownloadManager(private val app: App) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = app.db.downloads()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val lock = Mutex()
    private val _live = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<Long, LiveProgress>> = _live
    val all = dao.observeAll()

    val activeCount get() = jobs.size

    /** Süren indirmelerin adları (bildirim için). */
    val runningNames = ConcurrentHashMap<Long, String>()

    /** Yeni indirme başlatılabilir mi (ör. zamanlayıcı penceresi). Varsayılan: her zaman. */
    @Volatile var gate: () -> Boolean = { true }

    /** Sıradakilerin başlama önceliği: meta'daki "prio" küçükten büyüğe; olmayanlar eklenme sırasında. */
    fun setPriorities(ids: List<Long>) = scope.launch {
        ids.forEachIndexed { i, id ->
            dao.get(id)?.let { e -> e.meta = KV.encode(KV.decode(e.meta) + ("prio" to i.toString())); dao.update(e) }
        }
        schedule()
    }

    fun restore() {
        scope.launch {
            // Uygulama kapanırken süren işler kaldığı yerden devam eder
            for (e in dao.byStatus(listOf(Status.RUNNING, Status.MERGING))) { e.status = Status.QUEUED; dao.update(e) }
            schedule()
        }
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { schedule() }
            override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) { schedule() }
        })
    }

    private fun metered(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    suspend fun add(req: NewDownload): Long {
        val name = Format.safeName(req.fileName)
        val e = DownloadEntity(
            url = req.url, type = req.type, fileName = name, mime = req.mime, category = Category.of(name, req.mime),
            totalBytes = req.size, referer = req.referer, userAgent = req.userAgent, pageUrl = req.pageUrl, title = req.title,
            meta = com.movietrack.app.util.KV.encode(req.meta), labels = req.labels,
        )
        val id = dao.insert(e)
        schedule()
        return id
    }

    suspend fun existing(url: String) = dao.findByUrl(url)

    fun schedule() {
        scope.launch {
            lock.withLock {
                if (!gate()) return@withLock
                val waiting = dao.byStatus(listOf(Status.QUEUED, Status.WAITING_WIFI))
                    .sortedBy { KV.decode(it.meta)["prio"]?.toLongOrNull() ?: Long.MAX_VALUE }
                for (e in waiting) {
                    if (jobs.size >= app.settings.maxConcurrent) break
                    if (jobs.containsKey(e.id)) continue
                    if (app.settings.wifiOnly && metered()) {
                        if (e.status != Status.WAITING_WIFI) { e.status = Status.WAITING_WIFI; dao.update(e) }
                        continue
                    }
                    start(e)
                }
                if (jobs.isNotEmpty()) DownloadService.start(app)
            }
        }
    }

    private suspend fun start(e: DownloadEntity) {
        e.status = Status.RUNNING
        e.error = null
        dao.update(e)
        val job = scope.launch { run(e) }
        jobs[e.id] = job
        runningNames[e.id] = e.fileName
        job.invokeOnCompletion {
            jobs.remove(e.id)
            runningNames.remove(e.id)
            _live.update { it - e.id }
            schedule()
        }
    }

    private suspend fun run(e: DownloadEntity) {
        val ctx = JobContext(e, dao, scope) { p -> _live.update { it + (e.id to p) } }
        try {
            when (e.type) {
                "hls", "dash", "youtube" -> StreamDownloader(app, ctx).run()
                else -> DirectDownloader(app, ctx).run()
            }
            e.status = Status.DONE
            e.finishedAt = System.currentTimeMillis()
            e.downloadedBytes = maxOf(e.downloadedBytes, e.totalBytes)
            dao.update(e)
            Notifications.finished(app, e, null)
        } catch (c: CancellationException) {
            withContext(NonCancellable) { ctx.saveNow() }
            throw c
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                e.status = Status.ERROR
                e.error = when (t) {
                    is HttpException -> if (t.code == 403 || t.code == 410) "Sunucu indirmeyi reddetti (HTTP ${t.code}). Videonun olduğu sayfayı uygulamada açık tutup tekrar deneyin; olmazsa sayfayı yenileyip videoyu yeniden indirin." else "Sunucu hatası: HTTP ${t.code}"
                    else -> t.message ?: t.toString()
                }
                ctx.saveNow()
                Notifications.finished(app, e, e.error)
            }
        }
    }

    fun pause(id: Long) = scope.launch {
        jobs[id]?.cancelAndJoin()
        dao.get(id)?.let { if (it.status != Status.DONE) { it.status = Status.PAUSED; dao.update(it) } }
    }

    fun resume(id: Long) = scope.launch {
        dao.get(id)?.let { if (it.status != Status.DONE) { it.status = Status.QUEUED; it.error = null; dao.update(it) } }
        schedule()
    }

    fun pauseAll() = scope.launch { for (id in jobs.keys.toList()) pause(id).join(); dao.byStatus(listOf(Status.QUEUED, Status.WAITING_WIFI)).forEach { it.status = Status.PAUSED; dao.update(it) } }

    fun resumeAll() = scope.launch { dao.byStatus(listOf(Status.PAUSED, Status.ERROR)).forEach { it.status = Status.QUEUED; it.error = null; dao.update(it) }; schedule() }

    /** Süresi dolan bağlantı için yeni adres (kalan parçalar korunur). */
    fun refreshUrl(id: Long, newUrl: String) = scope.launch {
        jobs[id]?.cancelAndJoin()
        dao.get(id)?.let { it.url = newUrl; it.status = Status.QUEUED; it.error = null; dao.update(it) }
        schedule()
    }

    fun remove(id: Long, deleteFile: Boolean) = scope.launch {
        jobs[id]?.cancelAndJoin()
        val e = dao.get(id) ?: return@launch
        if (deleteFile || e.status != Status.DONE) {
            OutputStore.delete(app, e.outputUri)
            e.extraOutputs.lines().filter { it.isNotBlank() }.forEach { OutputStore.delete(app, it.substringBefore('|')) }
        }
        File(app.filesDir, "streams/${e.id}").deleteRecursively()
        dao.delete(e)
    }

    fun clearFinished() = scope.launch { dao.byStatus(listOf(Status.DONE)).forEach { dao.delete(it) } }
}

/** Bir indirme işinin ilerleme/kayıt yardımcısı. */
class JobContext(
    val e: DownloadEntity,
    private val dao: com.movietrack.app.data.DownloadDao,
    private val scope: CoroutineScope,
    private val publish: (LiveProgress) -> Unit,
) {
    @Volatile var phase = "İndiriliyor"
    @Volatile var segDone = 0
    @Volatile var segTotal = 0
    @Volatile var partsProvider: (() -> String)? = null
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var lastPublish = 0L
    private var lastSave = 0L

    @Synchronized
    fun onBytes(n: Int) {
        e.downloadedBytes += n
        val now = System.currentTimeMillis()
        samples.addLast(now to e.downloadedBytes)
        while (samples.size > 2 && now - samples.first().first > 3000) samples.removeFirst()
        if (now - lastPublish > 500) { lastPublish = now; emit() }
        if (now - lastSave > 2000) { lastSave = now; partsProvider?.let { e.parts = it() }; saveAsync() }
    }

    @Synchronized
    fun emit() {
        val speed = if (samples.size >= 2) {
            val (t0, b0) = samples.first(); val (t1, b1) = samples.last()
            if (t1 > t0) (b1 - b0) * 1000 / (t1 - t0) else 0
        } else 0
        val total = e.totalBytes
        val eta = if (speed > 0 && total > 0) (total - e.downloadedBytes) / speed else 0
        publish(LiveProgress(e.downloadedBytes, total, speed, eta, phase, segDone, segTotal))
    }

    private fun saveAsync() {
        val id = e.id; val b = e.downloadedBytes; val t = e.totalBytes; val p = e.parts
        scope.launch { dao.updateProgress(id, b, t, p) }
    }

    suspend fun saveNow() { partsProvider?.let { e.parts = it() }; dao.update(e) }
}
