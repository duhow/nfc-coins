package net.duhowpi.nfccoins

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * An [AppCompatEditText] that fires [onBackspaceWhenEmpty] when the user presses
 * backspace while the field is already empty. Works for both hardware keyboards
 * (via [onKeyDown]) and soft keyboards that call [InputConnection.deleteSurroundingText]
 * directly (e.g. GBoard).
 */
class BackspaceEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var onBackspaceWhenEmpty: (() -> Unit)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && text.isNullOrEmpty()) {
            onBackspaceWhenEmpty?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && text.isNullOrEmpty()) {
                    onBackspaceWhenEmpty?.invoke()
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_DEL
                    && event.action == KeyEvent.ACTION_DOWN
                    && text.isNullOrEmpty()
                ) {
                    onBackspaceWhenEmpty?.invoke()
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}
