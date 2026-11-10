package dev.linpad

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Resumable HTTP download into `<target>.part`, renamed to the target on success. */
object Downloader {
    fun download(
        url: String,
        target: File,
        cancelled: AtomicBoolean,
        progress: (done: Long, total: Long) -> Unit,
    ) {
        if (target.isFile && target.length() > 0) return
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        var attempt = 0
        while (true) {
            try {
                fetch(url, part, cancelled, progress)
                break
            } catch (e: IOException) {
                // Flaky networks are normal on tablets; resume a few times before giving up.
                if (cancelled.get() || ++attempt >= 5) throw e
                Thread.sleep(1500L * attempt)
            }
        }
        if (cancelled.get()) throw IOException("Download cancelled")
        if (!part.renameTo(target)) throw IOException("Could not move download into place")
    }

    private fun fetch(url: String, part: File, cancelled: AtomicBoolean, progress: (Long, Long) -> Unit) {
        var offset = if (part.isFile) part.length() else 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LinPad")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_OK -> offset = 0 // server ignored the range: start over
                416 -> { // range not satisfiable: the partial file is stale
                    part.delete()
                    throw IOException("Restarting download")
                }
                else -> throw IOException("Server answered HTTP $code")
            }
            val length = connection.contentLengthLong
            val total = if (length > 0) offset + length else -1L
            RandomAccessFile(part, "rw").use { file ->
                file.setLength(offset)
                file.seek(offset)
                val buffer = ByteArray(1 shl 16)
                var done = offset
                var lastReport = 0L
                connection.inputStream.use { input ->
                    while (!cancelled.get()) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        file.write(buffer, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            progress(done, total)
                        }
                    }
                }
                progress(done, total)
                if (!cancelled.get() && total > 0 && done < total) throw IOException("Connection closed early")
            }
        } finally {
            connection.disconnect()
        }
    }
}
