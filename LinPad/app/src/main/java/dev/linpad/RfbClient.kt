package dev.linpad

import android.graphics.Bitmap
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue

/**
 * Minimal VNC (RFB 3.8) client for QEMU's display, over a Unix domain socket.
 * Raw encoding only: the socket is local, so bandwidth is not a concern.
 */
class RfbClient(private val socketPath: String, private val listener: Listener) {
    interface Listener {
        /** Called from the reader thread whenever the framebuffer changed (including resizes). */
        fun onFrame()
        fun onDisconnected(reason: String)
    }

    /** Guards [bitmap]; hold it while drawing. */
    val lock = Any()
    var bitmap: Bitmap? = null
        private set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile var connected = false
        private set

    private val outbox = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    private var socket: LocalSocket? = null

    fun start() {
        Thread({ run() }, "rfb-reader").start()
    }

    fun close() {
        closed = true
        outbox.offer(ByteArray(0))
        runCatching { socket?.close() }
    }

    private fun connect(): LocalSocket {
        var last: Exception? = null
        // QEMU needs a moment after launch before the socket exists.
        repeat(100) {
            if (closed) throw InterruptedException()
            try {
                return LocalSocket().apply {
                    connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                }
            } catch (e: Exception) {
                last = e
                Thread.sleep(200)
            }
        }
        throw last ?: IllegalStateException("could not connect")
    }

    private fun run() {
        try {
            val s = connect()
            socket = s
            val input = DataInputStream(BufferedInputStream(s.inputStream, 1 shl 16))
            val output = s.outputStream
            handshake(input, output)
            connected = true
            Thread({ writeLoop(output) }, "rfb-writer").start()
            requestUpdate(false)
            while (!closed) readMessage(input)
        } catch (e: Exception) {
            if (!closed) listener.onDisconnected(e.message ?: e.javaClass.simpleName)
        } finally {
            connected = false
            close()
        }
    }

    private fun handshake(input: DataInputStream, output: OutputStream) {
        val version = ByteArray(12)
        input.readFully(version)
        output.write("RFB 003.008\n".toByteArray())
        val types = ByteArray(input.readUnsignedByte())
        if (types.isEmpty()) {
            val reason = ByteArray(input.readInt()).also { input.readFully(it) }
            error("VNC server refused: ${String(reason)}")
        }
        input.readFully(types)
        check(types.contains(1)) { "VNC server requires authentication" }
        output.write(1) // security type: None
        check(input.readInt() == 0) { "VNC security handshake failed" }
        output.write(1) // shared session
        val w = input.readUnsignedShort()
        val h = input.readUnsignedShort()
        input.skipBytes(16) // server pixel format; we set our own below
        input.skipBytes(input.readInt()) // desktop name
        resize(w, h)

        // 32 bpp little-endian xRGB, which reads straight into Android ARGB ints.
        output.write(
            ByteBuffer.allocate(20).apply {
                put(0).put(0).put(0).put(0)
                put(32).put(24).put(0).put(1)
                putShort(255).putShort(255).putShort(255)
                put(16).put(8).put(0)
                put(0).put(0).put(0)
            }.array()
        )
        // Encodings: Raw (0) and the DesktopSize pseudo-encoding (-223).
        output.write(
            ByteBuffer.allocate(12).apply {
                put(2).put(0).putShort(2)
                putInt(0).putInt(-223)
            }.array()
        )
        output.flush()
    }

    private fun resize(w: Int, h: Int) {
        synchronized(lock) {
            bitmap = Bitmap.createBitmap(maxOf(w, 1), maxOf(h, 1), Bitmap.Config.ARGB_8888)
            width = w
            height = h
        }
    }

    private fun readMessage(input: DataInputStream) {
        when (val type = input.readUnsignedByte()) {
            0 -> {
                input.skipBytes(1)
                var resized = false
                repeat(input.readUnsignedShort()) {
                    val x = input.readUnsignedShort()
                    val y = input.readUnsignedShort()
                    val w = input.readUnsignedShort()
                    val h = input.readUnsignedShort()
                    when (val encoding = input.readInt()) {
                        0 -> readRaw(input, x, y, w, h)
                        -223 -> {
                            resize(w, h)
                            resized = true
                        }
                        else -> error("unsupported VNC encoding $encoding")
                    }
                }
                listener.onFrame()
                requestUpdate(!resized)
            }
            1 -> { // SetColourMapEntries
                input.skipBytes(3)
                input.skipBytes(input.readUnsignedShort() * 6)
            }
            2 -> Unit // Bell
            3 -> { // ServerCutText
                input.skipBytes(3)
                input.skipBytes(input.readInt())
            }
            else -> error("unknown VNC message $type")
        }
    }

    private var rowBytes = ByteArray(0)
    private var rowInts = IntArray(0)

    private fun readRaw(input: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val rowsPerChunk = maxOf(1, 65536 / w)
        if (rowBytes.size < rowsPerChunk * w * 4) {
            rowBytes = ByteArray(rowsPerChunk * w * 4)
            rowInts = IntArray(rowsPerChunk * w)
        }
        var row = 0
        while (row < h) {
            val rows = minOf(rowsPerChunk, h - row)
            val count = rows * w
            input.readFully(rowBytes, 0, count * 4)
            ByteBuffer.wrap(rowBytes, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(rowInts, 0, count)
            for (i in 0 until count) rowInts[i] = rowInts[i] or 0xFF000000.toInt()
            synchronized(lock) {
                val b = bitmap
                if (b != null && x + w <= b.width && y + row + rows <= b.height) {
                    b.setPixels(rowInts, 0, w, x, y + row, w, rows)
                }
            }
            row += rows
        }
    }

    private fun writeLoop(output: OutputStream) {
        try {
            while (!closed) {
                val message = outbox.take()
                if (message.isEmpty()) continue
                output.write(message)
                // Coalesce whatever else is queued before flushing.
                while (true) output.write(outbox.poll() ?: break)
                output.flush()
            }
        } catch (_: Exception) {
            close()
        }
    }

    private fun requestUpdate(incremental: Boolean) {
        outbox.offer(
            ByteBuffer.allocate(10).apply {
                put(3).put((if (incremental) 1 else 0).toByte())
                putShort(0).putShort(0)
                putShort(width.toShort()).putShort(height.toShort())
            }.array()
        )
    }

    /** [buttons]: bit 0 left, 1 middle, 2 right, 3 wheel up, 4 wheel down. */
    fun pointer(x: Int, y: Int, buttons: Int) {
        if (!connected) return
        val cx = x.coerceIn(0, maxOf(width - 1, 0))
        val cy = y.coerceIn(0, maxOf(height - 1, 0))
        outbox.offer(
            ByteBuffer.allocate(6).apply {
                put(5).put(buttons.toByte())
                putShort(cx.toShort()).putShort(cy.toShort())
            }.array()
        )
    }

    fun key(keysym: Int, down: Boolean) {
        if (!connected) return
        outbox.offer(
            ByteBuffer.allocate(8).apply {
                put(4).put((if (down) 1 else 0).toByte()).putShort(0)
                putInt(keysym)
            }.array()
        )
    }
}
