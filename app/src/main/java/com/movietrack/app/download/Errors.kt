package com.movietrack.app.download

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Kullanıcıya olduğu gibi gösterilebilen hata: mesajı kısa, açıklayıcı ve iç ayrıntı içermez.
 * Teknik ayrıntı gerekiyorsa [cause] olarak taşınır (yalnızca tanı raporuna gider).
 */
class UserError(message: String, cause: Throwable? = null) : Exception(message, cause)

fun userError(message: String): Nothing = throw UserError(message)

/** Hataları kullanıcı tarafı (ağ, depolama, kaynak) ve sistem hatası olarak sınıflar. */
object Errors {
    enum class Kind { NETWORK, SERVER, SOURCE, STORAGE, SYSTEM }

    /** @property message ekranda gösterilecek metin; @property technical yalnızca tanı raporu için */
    data class Info(val kind: Kind, val message: String, val technical: String)

    const val SYSTEM_MESSAGE = "Sistem hatası oluştu. Yeniden deneyin; sürerse indirmenin tanı raporunu gönderin."

    fun classify(t: Throwable): Info {
        val tech = technical(t)
        fun info(k: Kind, m: String) = Info(k, m, tech)
        for (c in chain(t)) {
            val msg = c.message.orEmpty()
            when {
                c is UserError -> return info(Kind.SOURCE, c.message ?: SYSTEM_MESSAGE)
                c is HttpException -> return info(Kind.SERVER, when (c.code) {
                    401, 403, 410 -> "Sunucu erişimi reddetti; bağlantının süresi dolmuş olabilir. Sayfayı yeniden açıp tekrar deneyin."
                    404 -> "Dosya sunucuda bulunamadı; bağlantının süresi dolmuş olabilir. Sayfayı yeniden açıp tekrar deneyin."
                    408 -> "Sunucu zamanında yanıt vermedi. Biraz sonra yeniden deneyin."
                    429 -> "Sunucu çok yoğun, istekleri sınırladı. Biraz sonra yeniden deneyin; kaldığı yerden devam eder."
                    in 500..599 -> "Sunucu şu an yanıt vermiyor (HTTP ${c.code}). Biraz sonra yeniden deneyin."
                    else -> "Sunucu isteği kabul etmedi (HTTP ${c.code})."
                })
                c is UnknownHostException -> return info(Kind.NETWORK, "İnternet bağlantısı yok ya da sunucu bulunamadı. Bağlantınızı kontrol edip yeniden deneyin.")
                c is SocketTimeoutException || (c is InterruptedIOException && c !is java.util.concurrent.CancellationException) || msg.contains("zaman aşımı") || msg.contains("timeout", true) ->
                    return info(Kind.NETWORK, "Bağlantı zaman aşımına uğradı: internet yavaş ya da kesik. Bağlantınızı kontrol edip yeniden deneyin; kaldığı yerden devam eder.")
                c is ConnectException || c is NoRouteToHostException ->
                    return info(Kind.NETWORK, "Sunucuya bağlanılamadı. İnternetinizi kontrol edip yeniden deneyin.")
                c is SSLException -> return info(Kind.NETWORK, "Güvenli bağlantı kurulamadı; bağlı olduğunuz ağ bağlantıyı engelliyor olabilir. Başka bir ağla deneyin.")
                c is SocketException || c is EOFException || c is ProtocolException || c.javaClass.name.endsWith("StreamResetException") ->
                    return info(Kind.NETWORK, "Bağlantı yarıda koptu. Yeniden denerseniz kaldığı yerden devam eder.")
                msg.contains("ENOSPC") || msg.contains("No space left", true) ->
                    return info(Kind.STORAGE, "Telefonda yeterli boş alan yok. Yer açıp yeniden deneyin.")
                c is SecurityException || msg.contains("EACCES") || msg.contains("Permission denied", true) || (c is FileNotFoundException && msg.contains("open failed")) ->
                    return info(Kind.STORAGE, "Kayıt klasörüne yazılamıyor. Ayarlar › İndirmeler'den klasörü yeniden seçin ya da varsayılana dönün.")
            }
        }
        // Tanımadığımız her şey: iç ayrıntı göstermeden sistem hatası
        return info(Kind.SYSTEM, SYSTEM_MESSAGE)
    }

    /** Ağ/zaman aşımı gibi kullanıcının bağlantısından kaynaklanan hata mı */
    fun isNetwork(t: Throwable) = classify(t).kind == Kind.NETWORK

    private fun chain(t: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        var c: Throwable? = t
        while (c != null && out.size < 8 && c !in out) { out += c; c = c.cause }
        return out
    }

    /** Tanı raporu için kısa teknik özet (tür adı + mesaj, en fazla 3 halka) */
    fun technical(t: Throwable): String = chain(t).take(3).joinToString(" ← ") {
        "${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}"
    }

    /** Ekranda gösterilecek metin */
    fun message(t: Throwable) = classify(t).message
}
