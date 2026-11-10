package dev.linpad

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Unpacks the bundled QEMU runtime into files/usr.
 *
 * The prebuilt binaries look for everything under /data/data/dev.linpad/files/usr
 * (see tools/bundle.py), which is exactly where this puts it.
 */
object Runtime {
    private val lock = Any()

    fun prefix(context: Context) = File(context.filesDir, "usr")
    fun dataDir(context: Context) = File(prefix(context), "share/qemu")

    private fun stamp(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${info.longVersionCode}:${info.lastUpdateTime}"
    }

    /** Idempotent; cheap after the first call for a given install. Call off the main thread. */
    fun ensureInstalled(context: Context) = synchronized(lock) {
        val prefix = prefix(context)
        val stampFile = File(prefix, ".installed")
        val wanted = stamp(context)
        if (stampFile.isFile && stampFile.readText() == wanted) return
        File(prefix, "lib").deleteRecursively()
        File(prefix, "share").deleteRecursively()
        for (asset in listOf("runtime.zip", "firmware.zip")) unzip(context, asset, context.filesDir)
        for (dir in listOf("tmp", "etc", "var/run")) File(prefix, dir).mkdirs()
        stampFile.writeText(wanted)
    }

    private fun unzip(context: Context, asset: String, into: File) {
        val base = into.canonicalPath + File.separator
        ZipInputStream(context.assets.open(asset).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(into, entry.name)
                require(out.canonicalPath.startsWith(base)) { "bad zip entry ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it, 1 shl 16) }
                }
            }
        }
    }

    /**
     * QEMU's user-mode network stack forwards guest DNS to the servers in
     * usr/etc/resolv.conf. Android has no such file, so write the active network's.
     */
    fun writeResolvConf(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val servers = runCatching {
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers
                ?.mapNotNull { it.hostAddress }
                ?.filter { !it.contains('%') }
        }.getOrNull().orEmpty()
        val all = (servers + listOf("8.8.8.8", "1.1.1.1")).distinct()
        val etc = File(prefix(context), "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText(all.joinToString("") { "nameserver $it\n" })
    }
}
