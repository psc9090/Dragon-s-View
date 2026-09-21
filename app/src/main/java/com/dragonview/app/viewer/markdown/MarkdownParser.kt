// app/src/main/java/com/dragonview/app/viewer/markdown/MarkdownParser.kt
package com.dragonview.app.viewer.markdown

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Structured block model and parser for Markdown (.md) documents.
 * Designed for Obsidian "Reading View" rendering.
 */
data class MarkdownDocument(
    val title: String,
    val frontmatter: Map<String, String>? = null,
    val blocks: List<MarkdownBlock>
)

sealed class MarkdownBlock {
    data class Frontmatter(
        val entries: Map<String, String>
    ) : MarkdownBlock()

    data class Heading(
        val level: Int, // 1 to 6
        val text: String,
        val runs: List<MarkdownInlineRun> = emptyList()
    ) : MarkdownBlock()

    data class Paragraph(
        val runs: List<MarkdownInlineRun>
    ) : MarkdownBlock()

    data class BulletList(
        val items: List<MarkdownListItem>
    ) : MarkdownBlock()

    data class NumberedList(
        val items: List<MarkdownListItem>
    ) : MarkdownBlock()

    data class Checkbox(
        val checked: Boolean,
        val text: String,
        val runs: List<MarkdownInlineRun> = emptyList(),
        val indentLevel: Int = 0
    ) : MarkdownBlock()

    data class CodeBlock(
        val language: String,
        val code: String
    ) : MarkdownBlock()

    data class Blockquote(
        val content: List<MarkdownBlock>
    ) : MarkdownBlock()

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>
    ) : MarkdownBlock()

    data class Image(
        val altText: String,
        val pathOrUrl: String
    ) : MarkdownBlock()

    object HorizontalRule : MarkdownBlock()
}

data class MarkdownListItem(
    val text: String,
    val runs: List<MarkdownInlineRun>,
    val indentLevel: Int = 0,
    val number: Int? = null
)

data class MarkdownInlineRun(
    val text: String,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isCode: Boolean = false,
    val isStrikethrough: Boolean = false,
    val linkUrl: String? = null
)

object MarkdownParser {

    /**
     * Parses a Markdown stream asynchronously into a structured block model.
     */
    suspend fun parse(context: Context, uri: Uri): Result<MarkdownDocument> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.applicationContext.contentResolver
            val inputStream = contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalArgumentException("Could not open stream for $uri"))

            val text = inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            }

            val fallbackTitle = uri.lastPathSegment?.substringAfterLast('/') ?: "Markdown Document"
            Result.success(parseText(text, fallbackTitle))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Synchronous parser for direct string or test inputs.
     */
    fun parseText(rawText: String, defaultTitle: String = "Untitled"): MarkdownDocument {
        val lines = rawText.lines()
        var lineIndex = 0
        val blocks = mutableListOf<MarkdownBlock>()
        var frontmatterMap: Map<String, String>? = null

        // 1. Parse YAML Frontmatter at the very top (--- ... ---)
        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            val frontmatterLines = mutableListOf<String>()
            var closed = false
            var i = 1
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line == "---" || line == "...") {
                    closed = true
                    i++
                    break
                }
                frontmatterLines.add(lines[i])
                i++
            }
            if (closed) {
                lineIndex = i
                val entries = parseYamlLines(frontmatterLines)
                if (entries.isNotEmpty()) {
                    frontmatterMap = entries
                    blocks.add(MarkdownBlock.Frontmatter(entries))
                }
            }
        }

        // 2. Parse Markdown Blocks line-by-line
        parseBlockRange(lines, lineIndex, lines.size, blocks)

        // Determine title from first H1 or YAML frontmatter title or default
        val docTitle = frontmatterMap?.get("title")
            ?: blocks.filterIsInstance<MarkdownBlock.Heading>().firstOrNull { it.level == 1 }?.text
            ?: defaultTitle

        return MarkdownDocument(
            title = docTitle,
            frontmatter = frontmatterMap,
            blocks = blocks
        )
    }

    private fun parseBlockRange(
        lines: List<String>,
        startIndex: Int,
        endIndex: Int,
        outBlocks: MutableList<MarkdownBlock>
    ) {
        var i = startIndex
        while (i < endIndex) {
            val line = lines[i]
            val trimmed = line.trim()

            // Skip blank lines
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // A. Fenced Code Blocks (``` or ~~~)
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fence = if (trimmed.startsWith("```")) "```" else "~~~"
                val lang = trimmed.removePrefix(fence).trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < endIndex) {
                    val cur = lines[i]
                    if (cur.trim().startsWith(fence)) {
                        i++
                        break
                    }
                    codeLines.add(cur)
                    i++
                }
                outBlocks.add(MarkdownBlock.CodeBlock(language = lang, code = codeLines.joinToString("\n")))
                continue
            }

            // B. Horizontal Rule (---, ***, ___)
            if (isHorizontalRule(trimmed)) {
                outBlocks.add(MarkdownBlock.HorizontalRule)
                i++
                continue
            }

            // C. Standalone Image (![alt](url))
            val imageMatch = REGEX_IMAGE.matchEntire(trimmed)
            if (imageMatch != null) {
                val alt = imageMatch.groupValues[1]
                val path = imageMatch.groupValues[2]
                outBlocks.add(MarkdownBlock.Image(altText = alt, pathOrUrl = path))
                i++
                continue
            }

            // D. Headings (# H1 to ###### H6)
            val headingMatch = REGEX_HEADING.matchEntire(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length.coerceIn(1, 6)
                val hText = headingMatch.groupValues[2].trim()
                outBlocks.add(
                    MarkdownBlock.Heading(
                        level = level,
                        text = hText,
                        runs = parseInlineRuns(hText)
                    )
                )
                i++
                continue
            }

            // E. Blockquotes (> ...)
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < endIndex && lines[i].trim().startsWith(">")) {
                    val raw = lines[i].trim()
                    // Strip leading '>' and optional single space
                    val stripped = if (raw.length > 1 && raw[1] == ' ') raw.substring(2) else raw.substring(1)
                    quoteLines.add(stripped)
                    i++
                }
                val nestedBlocks = mutableListOf<MarkdownBlock>()
                parseBlockRange(quoteLines, 0, quoteLines.size, nestedBlocks)
                outBlocks.add(MarkdownBlock.Blockquote(content = nestedBlocks))
                continue
            }

            // F. Tables (| Col 1 | Col 2 |)
            if (line.contains("|") && i + 1 < endIndex && isTableSeparator(lines[i + 1])) {
                val headers = splitTableRow(line)
                i += 2 // skip header and separator
                val rows = mutableListOf<List<String>>()
                while (i < endIndex && lines[i].contains("|")) {
                    rows.add(splitTableRow(lines[i]))
                    i++
                }
                outBlocks.add(MarkdownBlock.Table(headers = headers, rows = rows))
                continue
            }

            // G. Checkboxes (- [ ] or - [x])
            val checkMatch = REGEX_CHECKBOX.matchEntire(line)
            if (checkMatch != null) {
                val indentSpaces = checkMatch.groupValues[1].length
                val isChecked = checkMatch.groupValues[2].equals("x", ignoreCase = true)
                val cText = checkMatch.groupValues[3].trim()
                outBlocks.add(
                    MarkdownBlock.Checkbox(
                        checked = isChecked,
                        text = cText,
                        runs = parseInlineRuns(cText),
                        indentLevel = indentSpaces / 2
                    )
                )
                i++
                continue
            }

            // H. Bullet Lists (- item, * item, + item)
            val bulletMatch = REGEX_BULLET.matchEntire(line)
            if (bulletMatch != null) {
                val items = mutableListOf<MarkdownListItem>()
                while (i < endIndex) {
                    val curLine = lines[i]
                    val bMatch = REGEX_BULLET.matchEntire(curLine)
                    val cMatch = REGEX_CHECKBOX.matchEntire(curLine)
                    if (cMatch != null) {
                        break // Switch to checkbox
                    }
                    if (bMatch == null) {
                        break
                    }
                    val indent = bMatch.groupValues[1].length / 2
                    val text = bMatch.groupValues[2].trim()
                    items.add(
                        MarkdownListItem(
                            text = text,
                            runs = parseInlineRuns(text),
                            indentLevel = indent
                        )
                    )
                    i++
                }
                outBlocks.add(MarkdownBlock.BulletList(items = items))
                continue
            }

            // I. Numbered Lists (1. item, 2. item)
            val numMatch = REGEX_NUMBERED.matchEntire(line)
            if (numMatch != null) {
                val items = mutableListOf<MarkdownListItem>()
                while (i < endIndex) {
                    val curLine = lines[i]
                    val nMatch = REGEX_NUMBERED.matchEntire(curLine)
                    if (nMatch == null) break
                    val indent = nMatch.groupValues[1].length / 2
                    val num = nMatch.groupValues[2].toIntOrNull()
                    val text = nMatch.groupValues[3].trim()
                    items.add(
                        MarkdownListItem(
                            text = text,
                            runs = parseInlineRuns(text),
                            indentLevel = indent,
                            number = num
                        )
                    )
                    i++
                }
                outBlocks.add(MarkdownBlock.NumberedList(items = items))
                continue
            }

            // J. Paragraphs (consecutive non-blank lines)
            val paraLines = mutableListOf<String>()
            while (i < endIndex) {
                val curLine = lines[i]
                val curTrim = curLine.trim()
                if (curTrim.isEmpty()) break
                // Stop if a new block type starts
                if (curTrim.startsWith("```") || curTrim.startsWith("~~~") ||
                    isHorizontalRule(curTrim) ||
                    REGEX_HEADING.matches(curTrim) ||
                    curTrim.startsWith(">") ||
                    REGEX_CHECKBOX.matches(curLine) ||
                    REGEX_BULLET.matches(curLine) ||
                    REGEX_NUMBERED.matches(curLine) ||
                    (curLine.contains("|") && i + 1 < endIndex && isTableSeparator(lines[i + 1]))
                ) {
                    break
                }
                paraLines.add(curLine)
                i++
            }
            if (paraLines.isNotEmpty()) {
                val fullText = paraLines.joinToString(" ")
                outBlocks.add(MarkdownBlock.Paragraph(runs = parseInlineRuns(fullText)))
            }
        }
    }

    /**
     * Parses inline formatting runs within text:
     * - **bold** or __bold__
     * - *italic* or _italic_
     * - ***bold italic*** or ___bold italic___
     * - `inline code`
     * - [link text](url)
     * - ~~strikethrough~~
     *
     * Leaves [[wikilinks]], ![[embeds]], #tags, and callouts intact as raw text.
     */
    fun parseInlineRuns(input: String): List<MarkdownInlineRun> {
        if (input.isEmpty()) return emptyList()
        val runs = mutableListOf<MarkdownInlineRun>()
        var i = 0
        val len = input.length
        val plainBuffer = StringBuilder()

        fun flushPlain() {
            if (plainBuffer.isNotEmpty()) {
                runs.add(MarkdownInlineRun(text = plainBuffer.toString()))
                plainBuffer.clear()
            }
        }

        while (i < len) {
            // 1. Inline Code: `...`
            if (input[i] == '`') {
                val closeIdx = input.indexOf('`', i + 1)
                if (closeIdx > i + 1) {
                    flushPlain()
                    val codeText = input.substring(i + 1, closeIdx)
                    runs.add(MarkdownInlineRun(text = codeText, isCode = true))
                    i = closeIdx + 1
                    continue
                }
            }

            // 2. Standard Link: [text](url) - ignore [[wikilinks]]
            if (input[i] == '[' && (i + 1 >= len || input[i + 1] != '[')) {
                val closeBracket = input.indexOf(']', i + 1)
                if (closeBracket > i && closeBracket + 1 < len && input[closeBracket + 1] == '(') {
                    val closeParen = input.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 1) {
                        flushPlain()
                        val linkText = input.substring(i + 1, closeBracket)
                        val linkUrl = input.substring(closeBracket + 2, closeParen).trim()
                        runs.add(MarkdownInlineRun(text = linkText, linkUrl = linkUrl))
                        i = closeParen + 1
                        continue
                    }
                }
            }

            // 3. Strikethrough: ~~...~~
            if (i + 1 < len && input[i] == '~' && input[i + 1] == '~') {
                val closeIdx = input.indexOf("~~", i + 2)
                if (closeIdx > i + 1) {
                    flushPlain()
                    val strikeText = input.substring(i + 2, closeIdx)
                    runs.add(MarkdownInlineRun(text = strikeText, isStrikethrough = true))
                    i = closeIdx + 2
                    continue
                }
            }

            // 4. Bold + Italic: ***...*** or ___...___
            if (i + 2 < len && ((input[i] == '*' && input[i + 1] == '*' && input[i + 2] == '*') ||
                                (input[i] == '_' && input[i + 1] == '_' && input[i + 2] == '_'))
            ) {
                val delim = input.substring(i, i + 3)
                val closeIdx = input.indexOf(delim, i + 3)
                if (closeIdx > i + 2) {
                    flushPlain()
                    val innerText = input.substring(i + 3, closeIdx)
                    runs.add(MarkdownInlineRun(text = innerText, isBold = true, isItalic = true))
                    i = closeIdx + 3
                    continue
                }
            }

            // 5. Bold: **...** or __...__
            if (i + 1 < len && ((input[i] == '*' && input[i + 1] == '*') ||
                                (input[i] == '_' && input[i + 1] == '_'))
            ) {
                val delim = input.substring(i, i + 2)
                val closeIdx = input.indexOf(delim, i + 2)
                if (closeIdx > i + 1) {
                    flushPlain()
                    val innerText = input.substring(i + 2, closeIdx)
                    runs.add(MarkdownInlineRun(text = innerText, isBold = true))
                    i = closeIdx + 2
                    continue
                }
            }

            // 6. Italic: *...* or _..._
            if (input[i] == '*' || input[i] == '_') {
                val delim = input[i]
                val isIntraWord = delim == '_' && i > 0 && i + 1 < len &&
                        input[i - 1].isLetterOrDigit() && input[i + 1].isLetterOrDigit()
                if (!isIntraWord) {
                    val closeIdx = input.indexOf(delim, i + 1)
                    if (closeIdx > i) {
                        val innerText = input.substring(i + 1, closeIdx)
                        if (innerText.isNotBlank() && !innerText.contains('\n')) {
                            flushPlain()
                            runs.add(MarkdownInlineRun(text = innerText, isItalic = true))
                            i = closeIdx + 1
                            continue
                        }
                    }
                }
            }

            // Plain text
            plainBuffer.append(input[i])
            i++
        }
        flushPlain()
        return runs
    }

    private fun isHorizontalRule(line: String): Boolean {
        if (line.length < 3) return false
        val clean = line.filter { !it.isWhitespace() }
        if (clean.length < 3) return false
        val char = clean[0]
        if (char != '-' && char != '*' && char != '_') return false
        return clean.all { it == char }
    }

    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains("-") || !trimmed.contains("|")) return false
        val cleaned = trimmed.replace("|", "").replace(":", "").replace("-", "").replace(" ", "")
        return cleaned.isEmpty()
    }

    private fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        return trimmed.split('|').map { it.trim() }
    }

    private fun parseYamlLines(lines: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val key = trimmed.substring(0, colonIdx).trim()
                var value = trimmed.substring(colonIdx + 1).trim()
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))
                ) {
                    value = value.substring(1, value.length - 1)
                }
                result[key] = value
            }
        }
        return result
    }

    private val REGEX_HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val REGEX_CHECKBOX = Regex("^([ \\t]*)[-*+]\\s+\\[([ xX])\\]\\s*(.*)$")
    private val REGEX_BULLET = Regex("^([ \\t]*)[-*+]\\s+(.*)$")
    private val REGEX_NUMBERED = Regex("^([ \\t]*)(\\d+)[.)]\\s+(.*)$")
    private val REGEX_IMAGE = Regex("^!\\[(.*?)\\]\\((.*?)\\)$")
}
