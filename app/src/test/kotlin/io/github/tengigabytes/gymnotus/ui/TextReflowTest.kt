package io.github.tengigabytes.gymnotus.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TextReflowTest {
    @Test
    fun joinsTheLinesOfAParagraph() {
        assertEquals("one two three\n\nfour five", reflow("  one two\nthree\n\nfour\nfive\n"))
    }

    @Test
    fun keepsCentredTitlesOnTheirOwnLines() {
        val text = "                    GNU GENERAL PUBLIC LICENSE\n                       Version 3, 29 June 2007\n\n Copyright\n notice"
        assertEquals("GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007\n\nCopyright notice", reflow(text))
    }

    @Test
    fun keepsMarkdownStructure() {
        val text = "# Title\ntext that\nwraps\n- item one\n  continued\n- item two\n\n| a | b |\n|---|---|\n| 1 | 2 |\nafter"
        assertEquals(
            "# Title\ntext that wraps\n- item one continued\n- item two\n\n| a | b |\n|---|---|\n| 1 | 2 |\nafter",
            reflow(text),
        )
    }

    @Test
    fun parsesMarkdownBlocks() {
        val text = "# Title\n\ntext that\nwraps\n\n- one\n- two\n\n| Name | Use |\n|---|---|\n| a | b |\n| c | d |\n\nend"
        assertEquals(
            listOf(
                DocBlock.Heading(1, "Title"),
                DocBlock.Paragraph("text that wraps"),
                DocBlock.Item("one"),
                DocBlock.Item("two"),
                DocBlock.Table(listOf("Name", "Use"), listOf(listOf("a", "b"), listOf("c", "d"))),
                DocBlock.Paragraph("end"),
            ),
            parseDocument(text, markdown = true),
        )
    }

    @Test
    fun plainTextIsParagraphsOnly() {
        assertEquals(
            listOf(DocBlock.Paragraph("# not a heading"), DocBlock.Paragraph("- not an item")),
            parseDocument("# not a heading\n\n- not an item", markdown = false),
        )
    }

    @Test
    fun parsesInlineMarkdown() {
        assertEquals(
            listOf(
                InlineSpan("Bold", InlineStyle.BOLD),
                InlineSpan(": see ", InlineStyle.PLAIN),
                InlineSpan("Build", InlineStyle.CODE),
                InlineSpan(", ", InlineStyle.PLAIN),
                InlineSpan("the licence", InlineStyle.PLAIN),
                InlineSpan(" and ", InlineStyle.PLAIN),
                InlineSpan("https://example.org/x", InlineStyle.PLAIN),
                InlineSpan(".", InlineStyle.PLAIN),
            ),
            parseInline("**Bold**: see `Build`, [the licence](LICENSE) and <https://example.org/x>."),
        )
    }
}
