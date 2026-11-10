package com.notefloat.app

import com.notefloat.app.notes.RichText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The note body is stored as plain text with light markup, so it syncs and exports cleanly.
 * These checks pin the transformations that must not drift: titles, plain-text export and
 * checklist toggling.
 */
class RichTextTest {

    @Test
    fun titleOf_usesHeadingTextWithoutMarkup() {
        assertEquals("Groceries", RichText.titleOf("# Groceries\nmilk\neggs"))
    }

    @Test
    fun titleOf_skipsBlankLeadingLines() {
        assertEquals("Call the ward officer", RichText.titleOf("\n\n   \nCall the ward officer\nsecond line"))
    }

    @Test
    fun titleOf_stripsBulletAndChecklistPrefixes() {
        assertEquals("milk", RichText.titleOf("- milk"))
        assertEquals("pay rent", RichText.titleOf("[ ] pay rent"))
        assertEquals("pay rent", RichText.titleOf("[x] pay rent"))
    }

    @Test
    fun titleOf_isEmptyForBlankBody() {
        assertEquals("", RichText.titleOf("   \n\n"))
    }

    @Test
    fun titleOf_capsVeryLongTitles() {
        val long = "# " + "a".repeat(200)
        assertEquals(80, RichText.titleOf(long).length)
    }

    @Test
    fun plain_removesBoldAndItalicMarkers() {
        assertEquals("bold and italic", RichText.plain("**bold** and _italic_"))
    }

    @Test
    fun plain_keepsUnderscoresInsideWords() {
        assertEquals("snake_case stays", RichText.plain("snake_case stays"))
    }

    @Test
    fun toggleCheckbox_flipsBothWays() {
        val open = "[ ] buy milk"
        val done = RichText.toggleCheckbox(open, 0)
        assertEquals("[x] buy milk", done)
        assertEquals(open, RichText.toggleCheckbox(done, 0))
    }

    @Test
    fun toggleCheckbox_handlesCapitalX() {
        assertEquals("[ ] done item", RichText.toggleCheckbox("[X] done item", 0))
    }

    @Test
    fun toggleCheckbox_leavesPlainLinesAlone() {
        val body = "just a line\n[ ] checklist"
        assertEquals(body, RichText.toggleCheckbox(body, 0))
        assertEquals("just a line\n[x] checklist", RichText.toggleCheckbox(body, 1))
    }

    @Test
    fun toggleCheckbox_ignoresOutOfRangeLine() {
        val body = "[ ] one"
        assertEquals(body, RichText.toggleCheckbox(body, 7))
        assertTrue(body.contains("[ ]"))
    }
}
