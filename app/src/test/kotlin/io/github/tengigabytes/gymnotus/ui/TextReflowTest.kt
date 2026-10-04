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
}
