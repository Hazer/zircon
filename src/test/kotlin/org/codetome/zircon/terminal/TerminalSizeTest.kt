package org.codetome.zircon.terminal

import org.assertj.core.api.Assertions.assertThat
import org.codetome.zircon.TerminalPosition
import org.junit.Test

class TerminalSizeTest {

    @Test(expected = IllegalArgumentException::class)
    fun shouldThrowExceptionWhenTerminalColumnsAreNegative() {
        TerminalSize(
                columns = -1,
                rows = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shouldThrowExceptionWhenTerminalRowsAreNegative() {
        TerminalSize(
                columns = 1,
                rows = -1)
    }

    @Test
    fun shouldCreateNewSizeWithProperColumnsWhenWithColumnsIsCalled() {
        assertThat(TerminalSize(
                columns = Int.MAX_VALUE,
                rows = EXPECTED_ROW).withColumns(EXPECTED_COL))
                .isEqualTo(EXPECTED_TERMINAL_SIZE)
    }

    @Test
    fun shouldCreateNewSizeWithProperRelativeColumnsWhenWithRelativeColumnsIsCalled() {
        assertThat(TerminalSize(
                columns = EXPECTED_COL - 1,
                rows = EXPECTED_ROW).withRelativeColumns(1))
                .isEqualTo(EXPECTED_TERMINAL_SIZE)
    }

    @Test
    fun shouldCreateNewSizeWithProperRowsWhenWithRowsIsCalled() {
        assertThat(TerminalSize(
                columns = EXPECTED_COL,
                rows = Int.MAX_VALUE).withRows(EXPECTED_ROW))
                .isEqualTo(EXPECTED_TERMINAL_SIZE)
    }

    @Test
    fun shouldCreateNewSizeWithProperRelativeRowsWhenWithRelativeRowsIsCalled() {
        assertThat(TerminalSize(
                columns = EXPECTED_COL,
                rows = EXPECTED_ROW - 1).withRelativeRows(1))
                .isEqualTo(EXPECTED_TERMINAL_SIZE)
    }

    @Test
    fun shouldCreateNewSizeWithProperRelativesWhenWithRelativeIsCalled() {
        assertThat(TerminalSize(
                columns = EXPECTED_COL - 1,
                rows = EXPECTED_ROW - 1).withRelative(TerminalSize(1, 1)))
                .isEqualTo(EXPECTED_TERMINAL_SIZE)
    }

    @Test
    fun shouldFetchPositionsInCorrectIterationOrder() {
        assertThat(TerminalSize(2, 2).fetchPositions())
                .isEqualTo(listOf(
                        TerminalPosition(column = 0, row = 0),
                        TerminalPosition(column = 1, row = 0),
                        TerminalPosition(column = 0, row = 1),
                        TerminalPosition(column = 1, row = 1)))
    }

    companion object {
        val EXPECTED_COL = 5
        val EXPECTED_ROW = 5
        val EXPECTED_TERMINAL_SIZE = TerminalSize(
                columns = EXPECTED_COL,
                rows = EXPECTED_ROW)
    }
}