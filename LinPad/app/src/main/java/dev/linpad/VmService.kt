package dev.linpad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.util.concurrent.TimeUnit

/** Owns the single running QEMU process. One VM at a time. */
object VmRunner {
    @Volatile var process: Process? = null
        private set
    @Volatile var runningId: String? = null
        private set
    @Volatile var lastExit: String? = null
        private set

    fun isRunning(id: String) = runningId == id && process?.isAlive == true
    fun anyRunning() = process?.isAlive == true

    @Synchronized
    fun start(context: Context, store: VmStore, vm: VmConfig): Process {
        check(!anyRunning()) { "Another VM is already running" }
        lastExit = null
        val p = Qemu.startSystem(context, store, vm)
        process = p
        runningId = vm.id
        return p
    }

    fun onExited(code: Int) {
        lastExit = "QEMU exited with code $code"
        process = null
        runningId = null
    }

    /** Asks the guest to shut down cleanly (ACPI power button) through QMP. */
    fun powerDown(store: VmStore) {
        val id = runningId ?: return
        Thread { runCatching { qmp(store.qmpSocket(id), "system_powerdown") } }.start()
    }

    fun kill() {
        process?.destroy()
    }

    private fun qmp(socketFile: File, command: String) {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = 5000
            val reader = socket.inputStream.bufferedReader()
            val out = socket.outputStream
            reader.readLine() // greeting
            out.write("{\"execute\":\"qmp_capabilities\"}\n".toByteArray())
            reader.readLine()
            out.write("{\"execute\":\"$command\"}\n".toByteArray())
            reader.readLine()
        }
    }
}

/** Foreground service so Android keeps the VM alive while the app is in the background. */
class VmService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        val store = VmStore(this)
        val vm = id?.let { store.get(it) }
        if (vm == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification(vm)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        // Already running (this VM or another one): the existing worker thread owns the service.
        if (VmRunner.anyRunning()) return START_NOT_STICKY

        Thread {
            try {
                val process = VmRunner.start(this, store, vm)
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linpad:vm")
                    .apply { acquire(TimeUnit.HOURS.toMillis(12)) }
                VmRunner.onExited(process.waitFor())
            } catch (e: Exception) {
                store.qemuLog(vm.id).appendText("\nFailed to start: $e\n")
                VmRunner.onExited(-1)
            } finally {
                wakeLock?.takeIf { it.isHeld }?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The VM cannot outlive its service: Android would kill the orphan unpredictably.
        VmRunner.kill()
        super.onDestroy()
    }

    private fun buildNotification(vm: VmConfig): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Running virtual machine", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, VmActivity.intent(this, vm.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("${vm.name} is running")
            .setContentText("${vm.ramMb} MB RAM, ${vm.cpus} cores. Tap to open.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "vm"
        private const val EXTRA_ID = "id"

        fun start(context: Context, id: String) {
            context.startForegroundService(Intent(context, VmService::class.java).putExtra(EXTRA_ID, id))
        }
    }
}
