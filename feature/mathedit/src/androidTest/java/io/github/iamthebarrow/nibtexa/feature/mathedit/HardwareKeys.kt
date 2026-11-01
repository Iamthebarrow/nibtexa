package io.github.iamthebarrow.nibtexa.feature.mathedit

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Injects key presses the way a physical keyboard delivers them, including the Linux evdev
 * scan code. WebView derives `KeyboardEvent.code` from the scan code, and MathLive maps
 * navigation keys by `code`, so a plain `sendKeyDownUpSync` (scan code 0) does not navigate.
 */
internal object HardwareKeys {
    // Linux input-event-codes.h
    private val SCAN_CODES = mapOf(
        KeyEvent.KEYCODE_MOVE_HOME to 102,
        KeyEvent.KEYCODE_MOVE_END to 107,
        KeyEvent.KEYCODE_DPAD_UP to 103,
        KeyEvent.KEYCODE_DPAD_LEFT to 105,
        KeyEvent.KEYCODE_DPAD_RIGHT to 106,
        KeyEvent.KEYCODE_DPAD_DOWN to 108,
        KeyEvent.KEYCODE_DEL to 14,
        KeyEvent.KEYCODE_FORWARD_DEL to 111,
        KeyEvent.KEYCODE_TAB to 15,
        KeyEvent.KEYCODE_MINUS to 12,
        KeyEvent.KEYCODE_1 to 2,
        KeyEvent.KEYCODE_2 to 3,
        KeyEvent.KEYCODE_NUMPAD_ADD to 78,
    )

    fun press(vararg keyCodes: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (keyCode in keyCodes) {
            val scanCode = SCAN_CODES.getValue(keyCode)
            val down = SystemClock.uptimeMillis()
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(
                    down, SystemClock.uptimeMillis(), action, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, scanCode, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
                )
                instrumentation.sendKeySync(event)
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(KEY_GAP_MS)
        }
    }

    private const val KEY_GAP_MS = 80L
}
