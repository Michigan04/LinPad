package dev.linpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Shows the VM's screen and turns Android input into VNC pointer and key events.
 *
 * Touch: one finger is the left mouse button (tap, drag); a two-finger tap is a
 * right click; a two-finger vertical drag scrolls. A hardware mouse and keyboard
 * are passed straight through.
 */
class VmView(context: Context) : View(context) {
    var client: RfbClient? = null

    /** Sticky modifiers toggled from the toolbar; applied to the next key press. */
    var stickyCtrl = false
    var stickyAlt = false
    var onStickyConsumed: (() -> Unit)? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    // ---- drawing -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val c = client ?: return
        synchronized(c.lock) {
            val bitmap = c.bitmap ?: return
            val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate((width - bitmap.width * scale) / 2f, (height - bitmap.height * scale) / 2f)
            matrix.invert(inverse)
            canvas.drawBitmap(bitmap, matrix, paint)
        }
    }

    private fun toGuest(x: Float, y: Float): IntArray {
        val point = floatArrayOf(x, y)
        inverse.mapPoints(point)
        return intArrayOf(point[0].toInt(), point[1].toInt())
    }

    private fun send(x: Float, y: Float, buttons: Int) {
        val p = toGuest(x, y)
        client?.pointer(p[0], p[1], buttons)
    }

    // ---- touch and mouse -----------------------------------------------------------------

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var leftHeld = false
    private var pendingPress = false
    private var twoFinger = false
    private var twoFingerScrolled = false
    private var scrollAnchorY = 0f

    private val pressAfterHold = Runnable {
        if (pendingPress) {
            pendingPress = false
            leftHeld = true
            send(lastX, lastY, BUTTON_LEFT)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            send(event.x, event.y, mouseButtons(event))
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                twoFinger = false
                leftHeld = false
                pendingPress = true
                send(event.x, event.y, 0)
                // Press only after a short hold or a drag, so a second finger can still arrive.
                handler.postDelayed(pressAfterHold, 180)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(pressAfterHold)
                pendingPress = false
                if (leftHeld) {
                    leftHeld = false
                    send(lastX, lastY, 0)
                }
                twoFinger = true
                twoFingerScrolled = false
                scrollAnchorY = averageY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger) {
                    if (event.pointerCount >= 2) scroll(averageY(event))
                } else {
                    lastX = event.x
                    lastY = event.y
                    if (pendingPress && hypot(event.x - downX, event.y - downY) > slop) {
                        handler.removeCallbacks(pressAfterHold)
                        pendingPress = false
                        leftHeld = true
                        send(downX, downY, BUTTON_LEFT)
                    }
                    send(event.x, event.y, if (leftHeld) BUTTON_LEFT else 0)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(pressAfterHold)
                if (twoFinger) {
                    if (!twoFingerScrolled && event.actionMasked == MotionEvent.ACTION_UP) {
                        send(downX, downY, BUTTON_RIGHT)
                        send(downX, downY, 0)
                    }
                } else if (pendingPress && event.actionMasked == MotionEvent.ACTION_UP) {
                    send(event.x, event.y, BUTTON_LEFT) // quick tap = click
                    send(event.x, event.y, 0)
                } else if (leftHeld) {
                    send(event.x, event.y, 0)
                }
                pendingPress = false
                leftHeld = false
                twoFinger = false
            }
        }
        return true
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }

    private fun scroll(y: Float) {
        val step = 28f * resources.displayMetrics.density
        while (abs(y - scrollAnchorY) >= step) {
            // Content follows the fingers: dragging down scrolls up.
            val button = if (y > scrollAnchorY) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN
            scrollAnchorY += if (y > scrollAnchorY) step else -step
            send(downX, downY, button)
            send(downX, downY, 0)
            twoFingerScrolled = true
        }
    }

    private fun mouseButtons(event: MotionEvent): Int {
        var mask = 0
        val state = event.buttonState
        if (state and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or BUTTON_LEFT
        if (state and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or BUTTON_MIDDLE
        if (state and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or BUTTON_RIGHT
        return mask
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> send(event.x, event.y, mouseButtons(event))
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) {
                    val button = if (v > 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN
                    send(event.x, event.y, mouseButtons(event) or button)
                    send(event.x, event.y, mouseButtons(event))
                }
            }
            else -> return super.onGenericMotionEvent(event)
        }
        return true
    }

    // ---- keyboard ------------------------------------------------------------------------

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL makes soft keyboards behave like a raw keyboard (no autocorrect or composing).
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                typeText(text)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(maxOf(beforeLength, 1)) { tapKey(Keysyms.BACKSPACE) }
                return true
            }
        }
    }

    fun typeText(text: CharSequence) {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            when (cp) {
                '\n'.code -> tapKey(Keysyms.RETURN)
                '\t'.code -> tapKey(Keysyms.TAB)
                else -> {
                    val shift = Keysyms.needsShift(cp)
                    if (shift) client?.key(Keysyms.SHIFT_L, true)
                    tapKey(Keysyms.fromCodePoint(cp))
                    if (shift) client?.key(Keysyms.SHIFT_L, false)
                }
            }
        }
    }

    /** Press and release, wrapped in any sticky toolbar modifiers. */
    fun tapKey(keysym: Int) {
        pressSticky()
        client?.key(keysym, true)
        client?.key(keysym, false)
        releaseSticky()
    }

    private var stickyDown = false

    private fun pressSticky() {
        if (stickyCtrl) client?.key(Keysyms.CONTROL_L, true)
        if (stickyAlt) client?.key(Keysyms.ALT_L, true)
        stickyDown = stickyCtrl || stickyAlt
    }

    private fun releaseSticky() {
        if (!stickyDown) return
        if (stickyAlt) client?.key(Keysyms.ALT_L, false)
        if (stickyCtrl) client?.key(Keysyms.CONTROL_L, false)
        stickyCtrl = false
        stickyAlt = false
        stickyDown = false
        onStickyConsumed?.invoke()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = Keysyms.fromKeyEvent(event) ?: return super.onKeyDown(keyCode, event)
        val modifier = KeyEvent.isModifierKey(keyCode)
        if (!modifier) pressSticky()
        client?.key(keysym, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = Keysyms.fromKeyEvent(event) ?: return super.onKeyUp(keyCode, event)
        client?.key(keysym, false)
        if (!KeyEvent.isModifierKey(keyCode)) releaseSticky()
        return true
    }

    companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_MIDDLE = 2
        const val BUTTON_RIGHT = 4
        const val BUTTON_WHEEL_UP = 8
        const val BUTTON_WHEEL_DOWN = 16
    }
}

/** X11 keysyms, the key identifiers VNC uses. */
object Keysyms {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val DELETE = 0xffff
    const val SHIFT_L = 0xffe1
    const val CONTROL_L = 0xffe3
    const val ALT_L = 0xffe9

    private val special = mapOf(
        KeyEvent.KEYCODE_ENTER to RETURN,
        KeyEvent.KEYCODE_NUMPAD_ENTER to 0xff8d,
        KeyEvent.KEYCODE_DEL to BACKSPACE,
        KeyEvent.KEYCODE_FORWARD_DEL to DELETE,
        KeyEvent.KEYCODE_TAB to TAB,
        KeyEvent.KEYCODE_ESCAPE to ESCAPE,
        KeyEvent.KEYCODE_SPACE to 0x20,
        KeyEvent.KEYCODE_DPAD_LEFT to 0xff51,
        KeyEvent.KEYCODE_DPAD_UP to 0xff52,
        KeyEvent.KEYCODE_DPAD_RIGHT to 0xff53,
        KeyEvent.KEYCODE_DPAD_DOWN to 0xff54,
        KeyEvent.KEYCODE_MOVE_HOME to 0xff50,
        KeyEvent.KEYCODE_MOVE_END to 0xff57,
        KeyEvent.KEYCODE_PAGE_UP to 0xff55,
        KeyEvent.KEYCODE_PAGE_DOWN to 0xff56,
        KeyEvent.KEYCODE_INSERT to 0xff63,
        KeyEvent.KEYCODE_SHIFT_LEFT to SHIFT_L,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 0xffe2,
        KeyEvent.KEYCODE_CTRL_LEFT to CONTROL_L,
        KeyEvent.KEYCODE_CTRL_RIGHT to 0xffe4,
        KeyEvent.KEYCODE_ALT_LEFT to ALT_L,
        KeyEvent.KEYCODE_ALT_RIGHT to 0xffea,
        KeyEvent.KEYCODE_META_LEFT to 0xffeb,
        KeyEvent.KEYCODE_META_RIGHT to 0xffec,
        KeyEvent.KEYCODE_CAPS_LOCK to 0xffe5,
        KeyEvent.KEYCODE_SYSRQ to 0xff61,
        KeyEvent.KEYCODE_BREAK to 0xff13,
        KeyEvent.KEYCODE_SCROLL_LOCK to 0xff14,
        KeyEvent.KEYCODE_NUM_LOCK to 0xff7f,
        KeyEvent.KEYCODE_MENU to 0xff67,
    )

    /** Returns null for keys Android should keep handling (Back, volume, ...). */
    fun fromKeyEvent(event: KeyEvent): Int? {
        val code = event.keyCode
        special[code]?.let { return it }
        if (code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return 0xffbe + (code - KeyEvent.KEYCODE_F1)
        if (event.isSystem) return null
        // Resolve the character with Shift/CapsLock only; Ctrl and Alt are sent as their own keys.
        val meta = event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON)
        val cp = event.getUnicodeChar(meta)
        if (cp <= 0 || cp and android.view.KeyCharacterMap.COMBINING_ACCENT != 0) return null
        return fromCodePoint(cp)
    }

    fun fromCodePoint(cp: Int): Int = if (cp in 0x20..0xff) cp else 0x01000000 or cp

    /** Whether a US keyboard needs Shift held to produce this character. */
    fun needsShift(cp: Int): Boolean =
        cp in 'A'.code..'Z'.code || (cp < 0x7f && "~!@#$%^&*()_+{}|:\"<>?".indexOf(cp.toChar()) >= 0)
}
