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
