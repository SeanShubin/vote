package com.seanshubin.vote.tools.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownTablePadderTest {

    @Test
    fun `pads a basic table so pipes align`() {
        val input = """
            |Name|Score|
            |---|---|
            |Alice|10|
            |Bob|2|
        """.trimIndent()

        val expected = """
            | Name  | Score |
            | ----- | ----- |
            | Alice | 10    |
            | Bob   | 2     |
        """.trimIndent()

        assertEquals(expected, MarkdownTablePadder.padContent(input))
    }

    @Test
    fun `pad is idempotent`() {
        val padded = MarkdownTablePadder.padContent(
            """
            |Name|Score|
            |---|---|
            |Alice|10|
            """.trimIndent()
        )
        assertEquals(padded, MarkdownTablePadder.padContent(padded))
    }

    @Test
    fun `preserves left alignment marker`() {
        val out = MarkdownTablePadder.padContent(
            """
            |a|b|
            |:-|:-|
            |xx|yy|
            """.trimIndent()
        )
        assertTrue(out.contains("| :-- | :-- |"), "expected left-aligned separator, got:\n$out")
    }

    @Test
    fun `preserves right alignment marker`() {
        val out = MarkdownTablePadder.padContent(
            """
            |a|b|
            |-:|-:|
            |xx|yy|
            """.trimIndent()
        )
        assertTrue(out.contains("| --: | --: |"), "expected right-aligned separator, got:\n$out")
    }

    @Test
    fun `preserves center alignment marker`() {
        val out = MarkdownTablePadder.padContent(
            """
            |a|b|
            |:-:|:-:|
            |xx|yy|
            """.trimIndent()
        )
        assertTrue(out.contains("| :-: | :-: |"), "expected centered separator, got:\n$out")
    }

    @Test
    fun `min column width is three`() {
        val out = MarkdownTablePadder.padContent(
            """
            |a|b|
            |-|-|
            |c|d|
            """.trimIndent()
        )
        // Cells of length 1 padded to width 3
        assertTrue(out.contains("| a   | b   |"), "got:\n$out")
        assertTrue(out.contains("| --- | --- |"), "got:\n$out")
    }

    @Test
    fun `non-table prose is left untouched`() {
        val input = """
            Hello world
            Some |inline| text
            And `code|with|pipes` too
        """.trimIndent()
        assertEquals(input, MarkdownTablePadder.padContent(input))
    }

    @Test
    fun `lines with pipes but no separator row do not get padded`() {
        // Two pipe-bracketed lines but second is not a separator — leave alone.
        val input = """
            |not a|table|
            |still|not one|
        """.trimIndent()
        assertEquals(input, MarkdownTablePadder.padContent(input))
    }

    @Test
    fun `table embedded in prose only rewrites the table block`() {
        val input = """
            Intro paragraph.

            |a|b|
            |---|---|
            |1|22|

            Trailing prose.
        """.trimIndent()
        val out = MarkdownTablePadder.padContent(input)
        assertTrue(out.startsWith("Intro paragraph."), "prose preserved at start")
        assertTrue(out.endsWith("Trailing prose."), "prose preserved at end")
        assertTrue(out.contains("| a   | b   |"))
        assertTrue(out.contains("| --- | --- |"))
        assertTrue(out.contains("| 1   | 22  |"))
    }

    @Test
    fun `CRLF input is normalised to LF on output`() {
        val input = "|a|b|\r\n|---|---|\r\n|x|y|\r\n"
        val out = MarkdownTablePadder.padContent(input)
        assertTrue(!out.contains('\r'), "expected LF only, got: ${out.replace("\r", "<CR>").replace("\n", "<LF>\n")}")
        assertTrue(out.contains("| a   | b   |"))
    }

    @Test
    fun `multiple tables in one document are padded independently`() {
        val input = """
            |a|b|
            |---|---|
            |1|2|

            text

            |longer header|x|
            |---|---|
            |z|y|
        """.trimIndent()
        val out = MarkdownTablePadder.padContent(input)
        // First table widths driven by "longer header" don't leak into second.
        assertTrue(out.contains("| a   | b   |"), "first table should be 3-wide, got:\n$out")
        assertTrue(out.contains("| longer header | x   |"), "second table widths driven by its own content, got:\n$out")
    }

    @Test
    fun `parseRow returns null for non-table line`() {
        assertNull(MarkdownTablePadder.parseRow("not a table"))
        assertNull(MarkdownTablePadder.parseRow(""))
        assertNull(MarkdownTablePadder.parseRow("|"))
        assertNull(MarkdownTablePadder.parseRow("starts with text |ends with pipe|"))
    }

    @Test
    fun `parseRow trims cells`() {
        assertEquals(listOf("a", "b"), MarkdownTablePadder.parseRow("|  a  |  b  |"))
    }

    @Test
    fun `isSeparator accepts canonical patterns`() {
        assertTrue(MarkdownTablePadder.isSeparator(listOf("---")))
        assertTrue(MarkdownTablePadder.isSeparator(listOf(":---")))
        assertTrue(MarkdownTablePadder.isSeparator(listOf("---:")))
        assertTrue(MarkdownTablePadder.isSeparator(listOf(":---:")))
        assertTrue(MarkdownTablePadder.isSeparator(listOf("-")))
    }

    @Test
    fun `isSeparator rejects garbage`() {
        assertTrue(!MarkdownTablePadder.isSeparator(listOf("")))
        assertTrue(!MarkdownTablePadder.isSeparator(listOf("abc")))
        assertTrue(!MarkdownTablePadder.isSeparator(listOf(":")))
        assertTrue(!MarkdownTablePadder.isSeparator(listOf("---:--")))
    }

    @Test
    fun `ragged column counts are normalised`() {
        val input = """
            |a|b|c|
            |---|---|---|
            |1|2|
        """.trimIndent()
        val out = MarkdownTablePadder.padContent(input)
        // Last row gets a third empty cell padded to min width 3
        assertTrue(out.contains("| 1   | 2   |     |"), "got:\n$out")
    }
}
