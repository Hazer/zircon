package org.codetome.zircon.input

import org.codetome.zircon.TerminalPosition
import org.codetome.zircon.input.InputType.*


/**
 * Represents the user pressing a key on the keyboard.
 *
 * Use the <code>inputType</code> field to determine what kind of key was pressed.
 * For ordinary letters, numbers and symbols, the <code>inputType</code> will be <code>InputType.Character</code>
 * and the actual character value of the key is in the <code>character</code> field.
 * Please note that
 * - return (`\n`)
 * - tab (`\t`) and
 * - backspace (`\b`)
 * are not sorted under type
 * <code>InputType.Character</code> but under
 * - <code>InputType.Enter</code>
 * - <code>InputType.Tab</code> and
 * - <code>InputType.Backspace</code>
 * respectively.
 */
sealed class Input(private val inputType: InputType = InputType.Character,
                   private val eventTime: Long = System.currentTimeMillis()) {

    fun getInputType() = inputType

    fun getEventTime() = eventTime


}

data class KeyStroke(
        private val character: Char = ' ',
        private val it: InputType = InputType.Character,
        private val ctrlDown: Boolean = false,
        private val altDown: Boolean = false,
        private val shiftDown: Boolean = false) : Input(it) {

    fun isCtrlDown() = ctrlDown

    fun isAltDown() = altDown

    fun isShiftDown() = shiftDown

    fun getCharacter() = char

    private val char: Char = when (getInputType()) {
        Backspace -> '\b'
        Enter -> '\n'
        Tab -> '\t'
        else -> {
            character
        }
    }
}

/**
 * MouseAction, a Input in disguise, this class contains the information of a single mouse action event.
 */
data class MouseAction(
        val actionType: MouseActionType,
        val button: Int,
        val position: TerminalPosition)
    : Input(inputType = InputType.MouseEvent)

