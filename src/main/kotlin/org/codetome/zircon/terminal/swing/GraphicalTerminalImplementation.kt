package org.codetome.zircon.terminal.swing

import org.codetome.zircon.*
import org.codetome.zircon.input.*
import org.codetome.zircon.input.MouseActionType.*
import org.codetome.zircon.terminal.config.CursorStyle.*
import org.codetome.zircon.terminal.config.TerminalColorConfiguration
import org.codetome.zircon.terminal.config.TerminalDeviceConfiguration
import org.codetome.zircon.terminal.virtual.VirtualTerminal
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.awt.image.BufferedImage
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This is the class that does the heavy lifting for [SwingTerminalComponent]. It maintains
 * most of the external terminal state and also the main back buffer that is copied to the component area on draw
 * operations.
 */
@Suppress("unused")
abstract class GraphicalTerminalImplementation(
        private val deviceConfiguration: TerminalDeviceConfiguration,
        private val colorConfiguration: TerminalColorConfiguration,
        private val virtualTerminal: VirtualTerminal)
    : VirtualTerminal by virtualTerminal {

    private val keyQueue = LinkedBlockingQueue<Input>()
    private val dirtyCellsLookupTable = DirtyCellsLookupTable()
    private var cursorIsVisible = true
    private var enableInput = false
    private var hasBlinkingText = false
    private var blinkOn = true
    private var needFullRedraw = false
    private var lastDrawnCursorPosition: TerminalPosition = TerminalPosition.UNKNOWN
    private var lastBufferUpdateScrollPosition: Int = 0
    private var lastComponentWidth: Int = 0
    private var lastComponentHeight: Int = 0

    private var blinkTimer: Optional<Timer> = Optional.empty()
    private var backBuffer: Optional<BufferedImage> = Optional.empty()

    /**
     * Used to find out the font height, in pixels.
     */
    abstract fun getFontHeight(): Int

    /**
     * Used to find out the font width, in pixels.
     */
    abstract fun getFontWidth(): Int

    /**
     * Used when requiring the total height of the terminal component, in pixels.
     */
    abstract fun getHeight(): Int

    /**
     * Used when requiring the total width of the terminal component, in pixels.
     */
    abstract fun getWidth(): Int

    /**
     * Returning the AWT font to use for the specific character.
     */
    internal abstract fun getFontForCharacter(character: TextCharacter): Font

    /**
     * Returns `true` if anti-aliasing is enabled, `false` otherwise.
     */
    abstract fun isTextAntiAliased(): Boolean

    /**
     * Called by the [GraphicalTerminalImplementation] when it would like the OS to schedule a repaint of the
     * window.
     */
    abstract fun repaint()

    @Synchronized
    fun onCreated() {
        startBlinkTimer()
        enableInput = true
        keyQueue.clear()
    }

    @Synchronized
    fun onDestroyed() {
        stopBlinkTimer()
        enableInput = false
        keyQueue.add(KeyStroke(character = ' ', it = InputType.EOF))
    }

    /**
     * Start the timer that triggers blinking
     */
    @Synchronized
    fun startBlinkTimer() {
        if (blinkTimer.isPresent) {
            return
        }
        blinkTimer = Optional.of(Timer("BlinkTimer", true))
        blinkTimer.get().schedule(object : TimerTask() {
            override fun run() {
                blinkOn = !blinkOn
                if (hasBlinkingText) {
                    repaint()
                }
            }
        }, deviceConfiguration.blinkLengthInMilliSeconds, deviceConfiguration.blinkLengthInMilliSeconds)
    }

    /**
     * Stops the timer the triggers blinking
     */
    @Synchronized
    fun stopBlinkTimer() {
        if (blinkTimer.isPresent) {
            blinkTimer.get().cancel()
            blinkTimer = Optional.empty()
        }
    }

    /**
     * Calculates the preferred size of this terminal.
     */
    fun getPreferredSize() = Dimension(
            getFontWidth() * virtualTerminal.getTerminalSize().columns,
            getFontHeight() * virtualTerminal.getTerminalSize().rows)

    /**
     * Updates the back buffer (if necessary) and draws it to the component's surface.
     */
    @Synchronized
    fun paintComponent(componentGraphics: Graphics) {
        var needToUpdateBackBuffer = hasBlinkingText.or(needFullRedraw)

        // Detect resize
        if (resizeHappened()) {
            val columns = getWidth() / getFontWidth()
            val rows = getHeight() / getFontHeight()
            val terminalSize = virtualTerminal.getTerminalSize().withColumns(columns).withRows(rows)
            virtualTerminal.setTerminalSize(terminalSize)
            needToUpdateBackBuffer = true
        }

        if (needToUpdateBackBuffer) {
            updateBackBuffer()
        }

        ensureGraphicBufferHasRightSize()
        var clipBounds: Rectangle? = componentGraphics.clipBounds
        if (clipBounds == null) {
            clipBounds = Rectangle(0, 0, getWidth(), getHeight())
        }
        componentGraphics.drawImage(
                backBuffer.get(),
                // Destination coordinates
                clipBounds.x,
                clipBounds.y,
                clipBounds.getWidth().toInt(),
                clipBounds.getHeight().toInt(),
                // Source coordinates
                clipBounds.x,
                clipBounds.y,
                clipBounds.getWidth().toInt(),
                clipBounds.getHeight().toInt(), null)

        // Take care of the left-over area at the bottom and right of the component where no character can fit
        //int leftoverHeight = getHeight() % getFontHeight();
        val leftoverWidth = getWidth() % getFontWidth()
        componentGraphics.color = Color.BLACK
        if (leftoverWidth > 0) {
            componentGraphics.fillRect(getWidth() - leftoverWidth, 0, leftoverWidth, getHeight())
        }

        //0, 0, getWidth(), getHeight(), 0, 0, getWidth(), getHeight(), null);
        this.lastComponentWidth = getWidth()
        this.lastComponentHeight = getHeight()
        componentGraphics.dispose()
    }

    @Synchronized
    private fun updateBackBuffer() {
        //Retrieve the position of the cursor, relative to the scrolling state
        val cursorPosition = virtualTerminal.getCursorPosition()
        val viewportSize = virtualTerminal.getTerminalSize()

        val firstVisibleRowIndex = 0
        val lastVisibleRowIndex = getHeight() / getFontHeight()

        //Setup the graphics object
        ensureGraphicBufferHasRightSize()
        val backBufferGraphics = backBuffer.get().createGraphics()

        if (isTextAntiAliased()) {
            backBufferGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            backBufferGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        }

        val foundBlinkingCharacters = AtomicBoolean(deviceConfiguration.isCursorBlinking)
        buildDirtyCellsLookupTable(firstVisibleRowIndex, lastVisibleRowIndex)

        // Detect component resize
        if (lastComponentWidth < getWidth()) {
            if (!dirtyCellsLookupTable.isAllDirty()) {
                //Mark right columns as dirty so they are repainted
                val lastVisibleColumnIndex = getWidth() / getFontWidth()
                val previousLastVisibleColumnIndex = lastComponentWidth / getFontWidth()
                for (column in previousLastVisibleColumnIndex..lastVisibleColumnIndex) {
                    dirtyCellsLookupTable.setColumnDirty(column)
                }
            }
        }
        if (lastComponentHeight < getHeight()) {
            if (!dirtyCellsLookupTable.isAllDirty()) {
                //Mark bottom rows as dirty so they are repainted
                val previousLastVisibleRowIndex = (lastComponentHeight) / getFontHeight()
                for (row in previousLastVisibleRowIndex..lastVisibleRowIndex) {
                    dirtyCellsLookupTable.setRowDirty(row)
                }
            }
        }

        virtualTerminal.forEachLine(firstVisibleRowIndex, lastVisibleRowIndex, object : VirtualTerminal.BufferWalker {
            override fun onLine(rowNumber: Int, bufferLine: VirtualTerminal.BufferLine) {
                var column = 0
                while (column < viewportSize.columns) {
                    val textCharacter = bufferLine.getCharacterAt(column)
                    val atCursorLocation = cursorPosition == TerminalPosition(column, rowNumber)
                    val isBlinking = textCharacter.getModifiers().contains(Modifier.BLINK)
                    if (isBlinking) {
                        foundBlinkingCharacters.set(true)
                    }
                    if (dirtyCellsLookupTable.isAllDirty() || dirtyCellsLookupTable.isDirty(rowNumber, column) || isBlinking) {
                        val characterWidth = getFontWidth()
                        val foregroundColor = deriveTrueForegroundColor(textCharacter, atCursorLocation)
                        val backgroundColor = deriveTrueBackgroundColor(textCharacter, atCursorLocation)
                        val drawCursor = atCursorLocation && (!deviceConfiguration.isCursorBlinking || //Always draw if the cursor isn't blinking
                                deviceConfiguration.isCursorBlinking && blinkOn)    //If the cursor is blinking, only draw when blinkOn is true

                        drawCharacter(backBufferGraphics,
                                textCharacter,
                                column,
                                rowNumber,
                                foregroundColor,
                                backgroundColor,
                                characterWidth,
                                drawCursor)
                    }
                    column++
                }
            }
        })

        backBufferGraphics.dispose()

        // Update the blink status according to if there were any blinking characters or not
        this.hasBlinkingText = foundBlinkingCharacters.get()
        this.lastDrawnCursorPosition = cursorPosition
        this.needFullRedraw = false

    }

    private fun buildDirtyCellsLookupTable(firstRowOffset: Int, lastRowOffset: Int) {
        if (virtualTerminal.isWholeBufferDirtyThenReset() || needFullRedraw) {
            dirtyCellsLookupTable.setAllDirty()
            return
        }

        val viewportSize = virtualTerminal.getTerminalSize()
        val cursorPosition = virtualTerminal.getCursorPosition()

        dirtyCellsLookupTable.resetAndInitialize(firstRowOffset, lastRowOffset, viewportSize.columns)
        dirtyCellsLookupTable.setDirty(cursorPosition)
        if (lastDrawnCursorPosition != cursorPosition) {
            dirtyCellsLookupTable.setDirty(lastDrawnCursorPosition)
        }

        val dirtyCells = virtualTerminal.getAndResetDirtyCells()
        dirtyCells.forEach { position -> dirtyCellsLookupTable.setDirty(position) }
    }

    private fun ensureGraphicBufferHasRightSize() {
        if (backBuffer.isPresent.not()) {
            backBuffer = Optional.of(BufferedImage(getWidth() * 2, getHeight() * 2, BufferedImage.TYPE_INT_RGB))

            val graphics = backBuffer.get().createGraphics()
            graphics.color = colorConfiguration.toAWTColor(TextColor.ANSI.DEFAULT, false, false)
            graphics.fillRect(0, 0, getWidth() * 2, getHeight() * 2)
            graphics.dispose()
        }
        val backBufferRef = backBuffer.get()
        if (backBufferRef.width < getWidth() || backBufferRef.width > getWidth() * 4 ||
                backBufferRef.height < getHeight() || backBufferRef.height > getHeight() * 4) {

            val newBackBuffer = BufferedImage(Math.max(getWidth(), 1) * 2, Math.max(getHeight(), 1) * 2, BufferedImage.TYPE_INT_RGB)
            val graphics = newBackBuffer.createGraphics()
            graphics.fillRect(0, 0, newBackBuffer.width, newBackBuffer.height)
            graphics.drawImage(backBufferRef, 0, 0, null)
            graphics.dispose()
            backBuffer = Optional.of(newBackBuffer)
        }
    }

    private fun drawCharacter(
            graphics: Graphics,
            character: TextCharacter,
            columnIndex: Int,
            rowIndex: Int,
            foregroundColor: Color,
            backgroundColor: Color,
            characterWidth: Int,
            drawCursor: Boolean) {

        val x = columnIndex * getFontWidth()
        val y = rowIndex * getFontHeight()
        graphics.color = backgroundColor
        graphics.setClip(x, y, characterWidth, getFontHeight())
        graphics.fillRect(x, y, characterWidth, getFontHeight())

        graphics.color = foregroundColor
        val font = getFontForCharacter(character) // TODO: custom tileset support
        graphics.font = font
        val fontMetrics = graphics.fontMetrics
        graphics.drawString(Character.toString(character.getCharacter()), x, y + getFontHeight() - fontMetrics.descent + 1)

        if (character.isCrossedOut()) {
            val lineStartX = x
            val lineStartY = y + getFontHeight() / 2
            val lineEndX = lineStartX + characterWidth
            graphics.drawLine(lineStartX, lineStartY, lineEndX, lineStartY)
        }
        if (character.isUnderlined()) {
            val lineStartX = x
            val lineStartY = y + getFontHeight() - fontMetrics.descent + 1
            val lineEndX = lineStartX + characterWidth
            graphics.drawLine(lineStartX, lineStartY, lineEndX, lineStartY)
        }

        if (drawCursor) {
            graphics.color = colorConfiguration.toAWTColor(deviceConfiguration.cursorColor, false, false)
            if (deviceConfiguration.cursorStyle === UNDER_BAR) {
                graphics.fillRect(x, y + getFontHeight() - 3, characterWidth, 2)
            } else if (deviceConfiguration.cursorStyle === VERTICAL_BAR) {
                graphics.fillRect(x, y + 1, 2, getFontHeight() - 2)
            }
        }
    }


    private fun deriveTrueForegroundColor(character: TextCharacter, atCursorLocation: Boolean): Color {
        val foregroundColor = character.getForegroundColor()
        val backgroundColor = character.getBackgroundColor()
        var inverse = character.isInverse()
        val blink = character.isBlinking()

        if (cursorIsVisible && atCursorLocation) {
            if (deviceConfiguration.cursorStyle === REVERSED) {
                inverse = true
            }
        }

        if (inverse && (!blink || !blinkOn)) {
            return colorConfiguration.toAWTColor(backgroundColor, backgroundColor !== TextColor.ANSI.DEFAULT, character.isBold())
        } else if (!inverse && blink && blinkOn) {
            return colorConfiguration.toAWTColor(backgroundColor, false, character.isBold())
        } else {
            return colorConfiguration.toAWTColor(foregroundColor, true, character.isBold())
        }
    }

    private fun deriveTrueBackgroundColor(character: TextCharacter, atCursorLocation: Boolean): Color {
        val foregroundColor = character.getForegroundColor()
        var backgroundColor = character.getBackgroundColor()
        var reverse = false
        if (cursorIsVisible && atCursorLocation) {
            if (deviceConfiguration.cursorStyle === REVERSED && (!deviceConfiguration.isCursorBlinking || !blinkOn)) {
                reverse = true
            } else if (deviceConfiguration.cursorStyle === FIXED_BACKGROUND) {
                backgroundColor = deviceConfiguration.cursorColor
            }
        }

        if (reverse) {
            return colorConfiguration.toAWTColor(foregroundColor, backgroundColor === TextColor.ANSI.DEFAULT, character.isBold())
        } else {
            return colorConfiguration.toAWTColor(backgroundColor, false, false)
        }
    }

    override fun pollInput(): Optional<Input> {
        if (!enableInput) {
            return Optional.of(KeyStroke(character = ' ', it = InputType.EOF))
        }
        return Optional.ofNullable(keyQueue.poll())
    }


    override fun readInput(): Input {
        synchronized(keyQueue) {
            if (!enableInput) {
                return KeyStroke(character = ' ', it = InputType.EOF)
            }
            return keyQueue.take()
        }
    }

    @Synchronized
    override fun clearScreen() {
        virtualTerminal.clearScreen()
        clearBackBuffer()
    }

    private fun clearBackBuffer() {
        if (backBuffer.isPresent) {
            val graphics = backBuffer.get().createGraphics()
            val backgroundColor = colorConfiguration.toAWTColor(TextColor.ANSI.DEFAULT, false, false)
            graphics.color = backgroundColor
            graphics.fillRect(0, 0, getWidth(), getHeight())
            graphics.dispose()
        }
    }

    @Synchronized
    override fun setCursorPosition(cursorPosition: TerminalPosition) {
        var fixedPos = cursorPosition
        if (fixedPos.column < 0) {
            fixedPos = fixedPos.withColumn(0)
        }
        if (fixedPos.row < 0) {
            fixedPos = fixedPos.withRow(0)
        }
        virtualTerminal.setCursorPosition(fixedPos)
    }

    override fun setCursorVisible(cursorVisible: Boolean) {
        cursorIsVisible = cursorVisible
    }

    @Synchronized
    override fun flush() {
        updateBackBuffer()
        repaint()
    }

    override fun close() {
        // No action
    }

    protected inner class TerminalInputListener : KeyAdapter() {

        override fun keyTyped(e: KeyEvent) {
            var character = e.keyChar
            val altDown = e.modifiersEx and InputEvent.ALT_DOWN_MASK != 0
            val ctrlDown = e.modifiersEx and InputEvent.CTRL_DOWN_MASK != 0
            val shiftDown = e.modifiersEx and InputEvent.SHIFT_DOWN_MASK != 0

            if (!TYPED_KEYS_TO_IGNORE.contains(character)) {
                //We need to re-adjust alphabet characters if ctrl was pressed, just like for the AnsiTerminal
                if (ctrlDown && character.toInt() > 0 && character.toInt() < 0x1a) {
                    character = ('a' - 1 + character.toInt()).toChar()
                    if (shiftDown) {
                        character = Character.toUpperCase(character)
                    }
                }

                // Check if clipboard is avavilable and this was a paste (ctrl + shift + v) before
                // adding the key to the input queue
                if (!altDown && ctrlDown && shiftDown && character == 'V' && deviceConfiguration.isClipboardAvailable) {
                    pasteClipboardContent()
                } else {
                    keyQueue.add(KeyStroke(
                            character = character,
                            ctrlDown = ctrlDown,
                            altDown = altDown,
                            shiftDown = shiftDown))
                }
            }
        }

        override fun keyPressed(e: KeyEvent) {
            val altDown = e.modifiersEx and InputEvent.ALT_DOWN_MASK != 0
            val ctrlDown = e.modifiersEx and InputEvent.CTRL_DOWN_MASK != 0
            val shiftDown = e.modifiersEx and InputEvent.SHIFT_DOWN_MASK != 0

            if (e.keyCode == KeyEvent.VK_INSERT) {
                // This could be a paste (shift+insert) if the clipboard is available
                if (!altDown && !ctrlDown && shiftDown && deviceConfiguration.isClipboardAvailable) {
                    pasteClipboardContent()
                } else {
                    keyQueue.add(KeyStroke(it = InputType.Insert,
                            ctrlDown = ctrlDown,
                            altDown = altDown,
                            shiftDown = shiftDown))
                }
            } else if (e.keyCode == KeyEvent.VK_TAB) {
                if (e.isShiftDown) {
                    keyQueue.add(KeyStroke(it = InputType.ReverseTab,
                            ctrlDown = ctrlDown,
                            altDown = altDown,
                            shiftDown = shiftDown))
                } else {
                    keyQueue.add(KeyStroke(it = InputType.Tab,
                            ctrlDown = ctrlDown,
                            altDown = altDown,
                            shiftDown = shiftDown))
                }
            } else if (KEY_EVENT_TO_KEY_TYPE_LOOKUP.containsKey(e.keyCode)) {
                keyQueue.add(KeyStroke(it = KEY_EVENT_TO_KEY_TYPE_LOOKUP[e.keyCode]!!,
                        ctrlDown = ctrlDown,
                        altDown = altDown,
                        shiftDown = shiftDown))
            } else {
                //keyTyped doesn't catch this scenario (for whatever reason...) so we have to do it here
                if (altDown && ctrlDown && e.keyCode >= 'A'.toByte() && e.keyCode <= 'Z'.toByte()) {
                    var character = e.keyCode.toChar()
                    if (!shiftDown) {
                        character = Character.toLowerCase(character)
                    }
                    keyQueue.add(KeyStroke(
                            character = character,
                            ctrlDown = ctrlDown,
                            altDown = altDown,
                            shiftDown = shiftDown))
                }
            }
        }
    }

    protected open inner class TerminalMouseListener : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (MouseInfo.getNumberOfButtons() > 2 &&
                    e.button == MouseEvent.BUTTON2 &&
                    deviceConfiguration.isClipboardAvailable) {
                pasteSelectionContent()
            }
            addActionToKeyQueue(MOUSE_CLICKED, e)
        }

        override fun mouseReleased(e: MouseEvent) {
            addActionToKeyQueue(MOUSE_RELEASED, e)
        }

        override fun mouseMoved(e: MouseEvent) {
            addActionToKeyQueue(MOUSE_MOVED, e)
        }

        override fun mouseEntered(e: MouseEvent) {
            addActionToKeyQueue(MOUSE_ENTERED, e)
        }

        override fun mouseExited(e: MouseEvent) {
            addActionToKeyQueue(MOUSE_EXITED, e)
        }

        override fun mouseDragged(e: MouseEvent) {
            addActionToKeyQueue(MOUSE_DRAGGED, e)
        }

        override fun mousePressed(e: MouseEvent) {
            addActionToKeyQueue(MOUSE_PRESSED, e)
        }

        override fun mouseWheelMoved(e: MouseWheelEvent) {
            val actionType = if (e.preciseWheelRotation > 0) {
                MOUSE_WHEEL_ROTATED_DOWN
            } else {
                MOUSE_WHEEL_ROTATED_UP
            }
            (0..e.preciseWheelRotation.toInt()).forEach {
                addActionToKeyQueue(actionType, e)
            }
        }

        private fun addActionToKeyQueue(actionType: MouseActionType, e: MouseEvent) {
            keyQueue.add(MouseAction(
                    actionType = actionType,
                    button = e.button,
                    position = TerminalPosition(
                            column = e.x.div(getFontWidth()),
                            row = e.y.div(getFontHeight()))
            ))
        }
    }

    private fun pasteClipboardContent() {
        Toolkit.getDefaultToolkit().systemClipboard?.let {
            injectStringAsKeyStrokes(it.getData(DataFlavor.stringFlavor) as String)
        }
    }

    private fun pasteSelectionContent() {
        Toolkit.getDefaultToolkit().systemSelection?.let {
            injectStringAsKeyStrokes(it.getData(DataFlavor.stringFlavor) as String)
        }
    }

    private fun injectStringAsKeyStrokes(string: String) {
        string
                .filter {
                    TextUtils.isPrintableCharacter(it)
                }
                .forEach {
                    keyQueue.add(KeyStroke(character = it))
                }
    }

    private fun resizeHappened() = getWidth() != lastComponentWidth || getHeight() != lastComponentHeight

    companion object {
        private val TYPED_KEYS_TO_IGNORE = HashSet(Arrays.asList('\n', '\t', '\r', '\b', '\u001b', 127.toChar()))

        val KEY_EVENT_TO_KEY_TYPE_LOOKUP = mapOf(
                Pair(KeyEvent.VK_ENTER, InputType.Enter),
                Pair(KeyEvent.VK_ESCAPE, InputType.Escape),
                Pair(KeyEvent.VK_BACK_SPACE, InputType.Backspace),
                Pair(KeyEvent.VK_LEFT, InputType.ArrowLeft),
                Pair(KeyEvent.VK_RIGHT, InputType.ArrowRight),
                Pair(KeyEvent.VK_UP, InputType.ArrowUp),
                Pair(KeyEvent.VK_DOWN, InputType.ArrowDown),
                Pair(KeyEvent.VK_DELETE, InputType.Delete),
                Pair(KeyEvent.VK_HOME, InputType.Home),
                Pair(KeyEvent.VK_END, InputType.End),
                Pair(KeyEvent.VK_PAGE_UP, InputType.PageUp),
                Pair(KeyEvent.VK_PAGE_DOWN, InputType.PageDown),
                Pair(KeyEvent.VK_F1, InputType.F1),
                Pair(KeyEvent.VK_F2, InputType.F2),
                Pair(KeyEvent.VK_F3, InputType.F3),
                Pair(KeyEvent.VK_F4, InputType.F4),
                Pair(KeyEvent.VK_F5, InputType.F5),
                Pair(KeyEvent.VK_F6, InputType.F6),
                Pair(KeyEvent.VK_F7, InputType.F7),
                Pair(KeyEvent.VK_F8, InputType.F8),
                Pair(KeyEvent.VK_F9, InputType.F9),
                Pair(KeyEvent.VK_F10, InputType.F10),
                Pair(KeyEvent.VK_F11, InputType.F11),
                Pair(KeyEvent.VK_F12, InputType.F12))
    }
}