// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.ui

private const val CENTRED_INDENT = 8

/**
 * Undoes the hard line breaks of a text file written for a wide terminal, so the screen can wrap it itself.
 *
 * Lines of one paragraph are joined; blank lines, Markdown headings and table rows, list items and centred
 * titles (deeply indented lines) keep a line of their own.
 */
fun reflow(text: String): String {
    val lines = ArrayList<StringBuilder>()
    // Whether the last line is running text that the next one may continue.
    var open = false
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        val centred = raw.length - raw.trimStart().length >= CENTRED_INDENT
        val structural = line.startsWith("#") || line.startsWith("|")
        val listItem = line.startsWith("- ") || line.startsWith("* ")
        when {
            line.isEmpty() || structural || centred -> {
                lines.add(StringBuilder(line))
                open = false
            }
            listItem || !open -> {
                lines.add(StringBuilder(line))
                open = true
            }
            else -> lines.last().append(' ').append(line)
        }
    }
    return lines.joinToString("\n").trim()
}

/** One block of a bundled document, in the order it is shown. */
sealed interface DocBlock {
    data class Heading(val level: Int, val text: String) : DocBlock

    data class Paragraph(val text: String) : DocBlock

    data class Item(val text: String) : DocBlock

    /** A table; [header] names the columns, every row has its cells in the same order. */
    data class Table(val header: List<String>, val rows: List<List<String>>) : DocBlock
}

private val TABLE_RULE = Regex("^[|:\\s-]+$")

/**
 * Splits a document into blocks. With [markdown] off (the licence is plain text) every paragraph is a
 * [DocBlock.Paragraph], whatever it starts with.
 */
fun parseDocument(text: String, markdown: Boolean): List<DocBlock> {
    val blocks = ArrayList<DocBlock>()
    val table = ArrayList<List<String>>()
    fun closeTable() {
        if (table.isNotEmpty()) blocks.add(DocBlock.Table(table.first(), table.drop(1)))
        table.clear()
    }
    for (line in reflow(text).lineSequence()) {
        if (markdown && line.startsWith("|")) {
            if (!TABLE_RULE.matches(line)) table.add(line.trim('|').split('|').map { it.trim() })
            continue
        }
        closeTable()
        when {
            line.isEmpty() -> Unit
            markdown && line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length
                blocks.add(DocBlock.Heading(level, line.drop(level).trim()))
            }
            markdown && (line.startsWith("- ") || line.startsWith("* ")) -> blocks.add(DocBlock.Item(line.drop(2).trim()))
            else -> blocks.add(DocBlock.Paragraph(line))
        }
    }
    closeTable()
    return blocks
}

enum class InlineStyle { PLAIN, BOLD, CODE }

data class InlineSpan(val text: String, val style: InlineStyle)

// **bold**, `code`, [label](target) and <https://…>; a link is shown as its label or its address.
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\([^)]+\\)|<(https?://[^>]+)>")

/** The inline Markdown of one block, as runs of one style each. */
fun parseInline(text: String): List<InlineSpan> {
    val spans = ArrayList<InlineSpan>()
    var position = 0
    for (match in INLINE.findAll(text)) {
        if (match.range.first > position) spans.add(InlineSpan(text.substring(position, match.range.first), InlineStyle.PLAIN))
        val (bold, code, label, address) = match.destructured
        spans.add(
            when {
                bold.isNotEmpty() -> InlineSpan(bold, InlineStyle.BOLD)
                code.isNotEmpty() -> InlineSpan(code, InlineStyle.CODE)
                else -> InlineSpan(label.ifEmpty { address }, InlineStyle.PLAIN)
            },
        )
        position = match.range.last + 1
    }
    if (position < text.length) spans.add(InlineSpan(text.substring(position), InlineStyle.PLAIN))
    return spans
}
/**
 * Of a document that says the same thing in several languages, each under a level-two heading naming the
 * language, keeps the part before the first such heading and the one section titled [heading], without that
 * title. A document with no such section is returned as it is.
 */
fun selectLanguage(text: String, heading: String): String {
    val lines = text.lines()
    fun isSection(line: String) = line.startsWith("## ")
    val start = lines.indexOfFirst { it.trim() == "## $heading" }
    if (start < 0) return text
    val preamble = lines.take(lines.indexOfFirst(::isSection))
    val section = lines.drop(start + 1).takeWhile { !isSection(it) }
    return (preamble + section).joinToString("\n")
}
