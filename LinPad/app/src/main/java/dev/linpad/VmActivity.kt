package dev.linpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.RandomAccessFile

/** The VM's screen: display, input toolbar, and a log panel for diagnosing boots. */
class VmActivity : Activity(), RfbClient.Listener {
    private lateinit var store: VmStore
    private lateinit var vmId: String
    private lateinit var view: VmView
    private lateinit var status: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var ctrlButton: Button
    private lateinit var altButton: Button
    private var client: RfbClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private var gotFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = VmStore(this)
        vmId = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val vm = store.get(vmId) ?: return finish()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        view = VmView(this)
        view.onStickyConsumed = { refreshSticky() }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            text = "Starting ${vm.name}…"
        }
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(0xFFD0D7DE.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            setBackgroundColor(0xE6101418.toInt())
            visibility = View.GONE
            addView(logText)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1B1F2A.toInt())
        }
        fun tool(label: String, action: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            isFocusable = false // keep hardware-keyboard focus on the VM
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { action() }
            toolbar.addView(this, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)))
        }
        tool("Keyboard") { view.showKeyboard() }
        tool("Esc") { view.tapKey(Keysyms.ESCAPE) }
        tool("Tab") { view.tapKey(Keysyms.TAB) }
        // Boot menus and text installers are driven by arrow keys, which phone keyboards lack.
        tool("←") { view.tapKey(0xff51) }
        tool("↓") { view.tapKey(0xff54) }
        tool("↑") { view.tapKey(0xff52) }
        tool("→") { view.tapKey(0xff53) }
        tool("Enter") { view.tapKey(Keysyms.RETURN) }
        ctrlButton = tool("Ctrl") { view.stickyCtrl = !view.stickyCtrl; refreshSticky() }
        altButton = tool("Alt") { view.stickyAlt = !view.stickyAlt; refreshSticky() }
        tool("Del") { view.tapKey(Keysyms.DELETE) }
        tool("Log") { toggleLog() }
        tool("Shut down") { confirmShutdown() }

        val screen = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(logScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                fitsSystemWindows = true
                setBackgroundColor(Color.BLACK)
                addView(
                    HorizontalScrollView(this@VmActivity).apply {
                        isHorizontalScrollBarEnabled = false
                        setBackgroundColor(0xFF1B1F2A.toInt())
                        addView(toolbar)
                    },
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
                )
                addView(screen, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
        )

        if (!VmRunner.isRunning(vmId)) VmService.start(this, vmId)
        connect()
        handler.post(watchdog)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun refreshSticky() {
        ctrlButton.setTextColor(if (view.stickyCtrl) 0xFF7CE38B.toInt() else Color.WHITE)
        altButton.setTextColor(if (view.stickyAlt) 0xFF7CE38B.toInt() else Color.WHITE)
    }

    private fun connect() {
        client?.close()
        gotFrame = false
        val c = RfbClient(store.vncSocket(vmId).absolutePath, this)
        client = c
        view.client = c
        c.start()
    }

    override fun onFrame() {
        view.postInvalidateOnAnimation()
        if (!gotFrame) {
            gotFrame = true
            handler.post { status.visibility = View.GONE }
        }
    }

    override fun onDisconnected(reason: String) {
        handler.post {
            if (isFinishing) return@post
            status.visibility = View.VISIBLE
            status.text = if (VmRunner.isRunning(vmId)) "Display disconnected: $reason" else stoppedMessage()
        }
    }

    private fun stoppedMessage(): String {
        val tail = tail(store.qemuLog(vmId), 600).trim()
        return "The VM has stopped.\n${VmRunner.lastExit.orEmpty()}\n\n$tail"
    }

    /** Polls the QEMU process so the screen says so when the VM exits, and refreshes the log. */
    private val watchdog = object : Runnable {
        private var seenRunning = false
        override fun run() {
            if (isFinishing) return
            if (VmRunner.isRunning(vmId)) {
                seenRunning = true
            } else if (seenRunning) {
                status.visibility = View.VISIBLE
                status.text = stoppedMessage()
            }
            if (logScroll.visibility == View.VISIBLE) refreshLog()
            handler.postDelayed(this, 1000)
        }
    }

    private fun toggleLog() {
        if (logScroll.visibility == View.VISIBLE) {
            logScroll.visibility = View.GONE
        } else {
            logScroll.visibility = View.VISIBLE
            refreshLog()
        }
    }

    private fun refreshLog() {
        val text = "── qemu.log ──\n" + tail(store.qemuLog(vmId), 4000) +
            "\n── serial console ──\n" + tail(store.serialLog(vmId), 12000)
        if (text != logText.text.toString()) {
            logText.text = text
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun tail(file: File, bytes: Int): String = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val start = maxOf(0L, f.length() - bytes)
            f.seek(start)
            val buffer = ByteArray((f.length() - start).toInt())
            f.readFully(buffer)
            // Drop terminal escape sequences so the serial console stays readable.
            String(buffer).replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "").replace("\r", "")
        }
    }.getOrDefault("")

    private fun confirmShutdown() {
        AlertDialog.Builder(this)
            .setTitle("Shut down VM")
            .setMessage("“Shut down” asks Linux to power off cleanly. “Force stop” is like pulling the plug.")
            .setPositiveButton("Shut down") { _, _ -> VmRunner.powerDown(store) }
            .setNegativeButton("Force stop") { _, _ -> VmRunner.kill() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        client?.close()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        fun intent(context: Context, id: String): Intent =
            Intent(context, VmActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
