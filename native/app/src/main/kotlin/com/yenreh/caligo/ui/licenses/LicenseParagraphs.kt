package com.yenreh.caligo.ui.licenses

/** A paragraph of a license and how far it is indented, or [CENTERED] */
data class LicenseParagraph(val text: String, val indent: Int) {
    companion object {
        const val CENTERED = -1
    }
}

/**
 * The paragraphs of a license whose text is hard-wrapped, as Flutter's
 * LicenseEntryWithLineBreaks reads them: blank lines and lines starting
 * further in than the one before begin a paragraph, other line breaks
 * become spaces, and the indentation is guessed from the leading spaces.
 */
fun licenseParagraphs(text: String): List<LicenseParagraph> {
    var lineStart = 0
    var position = 0
    var lastLineIndent = 0
    var lineIndent = 0
    var paragraphIndent: Int? = null
    var inParagraph = false
    val lines = mutableListOf<String>()
    val result = mutableListOf<LicenseParagraph>()

    fun addLine() {
        lines.add(text.substring(lineStart, position))
    }

    fun takeParagraph() {
        result.add(LicenseParagraph(lines.joinToString(" "), paragraphIndent!!))
        lines.clear()
    }

    fun startParagraph() {
        if (lines.isNotEmpty() && lineIndent > lastLineIndent) {
            takeParagraph()
            paragraphIndent = null
        }
        // Flutter's own wild heuristic, which fits the BSD and LGPL licenses
        if (paragraphIndent == null) {
            paragraphIndent = if (lineIndent > 10) LicenseParagraph.CENTERED else lineIndent / 3
        }
        inParagraph = true
    }

    while (position < text.length) {
        val c = text[position]
        if (!inParagraph) {
            when (c) {
                ' ' -> {
                    lineStart = position + 1
                    lineIndent += 1
                }
                '\t' -> {
                    lineStart = position + 1
                    lineIndent += 8
                }
                '\r', '\n', '\u000c' -> {
                    if (lines.isNotEmpty()) takeParagraph()
                    if (c == '\r' && position < text.length - 1 && text[position + 1] == '\n') position += 1
                    lastLineIndent = 0
                    lineIndent = 0
                    paragraphIndent = null
                    lineStart = position + 1
                }
                '[' -> {
                    // For the LGPL 2.1's "[this is a\n single paragraph]"
                    lineIndent += 1
                    startParagraph()
                }
                else -> startParagraph()
            }
        } else {
            when (c) {
                '\n' -> {
                    addLine()
                    lastLineIndent = lineIndent
                    lineIndent = 0
                    lineStart = position + 1
                    inParagraph = false
                }
                '\u000c' -> {
                    addLine()
                    takeParagraph()
                    lastLineIndent = 0
                    lineIndent = 0
                    paragraphIndent = null
                    lineStart = position + 1
                    inParagraph = false
                }
            }
        }
        position += 1
    }
    if (inParagraph) {
        addLine()
        takeParagraph()
    } else if (lines.isNotEmpty()) {
        takeParagraph()
    }
    return result
}
