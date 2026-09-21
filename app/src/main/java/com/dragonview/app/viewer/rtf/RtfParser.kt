// app/src/main/java/com/dragonview/app/viewer/rtf/RtfParser.kt
package com.dragonview.app.viewer.rtf

import android.content.Context
import android.net.Uri
import com.dragonview.app.viewer.document.DocumentAlignment
import com.dragonview.app.viewer.document.DocumentData
import com.dragonview.app.viewer.document.DocumentElement
import com.dragonview.app.viewer.document.DocumentRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque

/**
 * Lightweight, streaming RTF parser for DragonView.
 *
 * Designed for 3-4GB RAM devices:
 * - Reads character-by-character using a streaming reader (no heavy DOM tree or external library).
 * - Scopes formatting states using a stack for { ... } groups.
 * - Extracts color table definitions (\colortbl) and maps color indices (\cfN).
 * - Handles unicode escapes (\uN), hex escapes (\'hh), and control symbols (\\, \{, \}).
 * - Skips unsupported metadata groups (\fonttbl, \stylesheet, \info, \*) gracefully.
 * - Caps paragraph storage to prevent unbounded memory allocation.
 */
object RtfParser {

    private const val MAX_PARAGRAPHS = 4000

    private data class RtfStyleState(
        var isBold: Boolean = false,
        var isItalic: Boolean = false,
        var isUnderline: Boolean = false,
        var isStrike: Boolean = false,
        var fontSizeSp: Float = 14f,
        var colorHex: String? = null,
        var highlightColorHex: String? = null,
        var alignment: DocumentAlignment = DocumentAlignment.LEFT,
        var isHeading: Boolean = false,
        var headingLevel: Int = 0,
        var isBullet: Boolean = false,
        var destination: String = "",
        var skipGroup: Boolean = false,
        var unicodeSkipCount: Int = 1
    ) {
        fun copyState(): RtfStyleState = copy()
    }

    suspend fun parse(context: Context, uri: Uri): Result<DocumentData> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.applicationContext.contentResolver
            val inputStream = contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open input stream for RTF."))

            BufferedReader(InputStreamReader(inputStream, Charsets.ISO_8859_1)).use { reader ->
                // Step 1: Verify header starts with "{\rtf"
                reader.mark(10)
                val headerBuf = CharArray(6)
                val readLen = reader.read(headerBuf, 0, 6)
                reader.reset()

                val headerStr = if (readLen > 0) String(headerBuf, 0, readLen) else ""
                if (!headerStr.startsWith("{\\rtf")) {
                    return@withContext Result.failure(
                        IllegalArgumentException("This RTF file appears corrupted or invalid (missing {\\rtf header).")
                    )
                }

                // Step 2: Stream tokens
                val elements = mutableListOf<DocumentElement>()
                val stateStack = ArrayDeque<RtfStyleState>()
                var currentState = RtfStyleState()

                val colorTable = mutableListOf<String>()
                var pendingRed = 0
                var pendingGreen = 0
                var pendingBlue = 0

                val currentRuns = mutableListOf<DocumentRun>()
                val runTextBuilder = StringBuilder()
                var paragraphCount = 0

                fun flushCurrentRun() {
                    if (runTextBuilder.isNotEmpty()) {
                        currentRuns.add(
                            DocumentRun(
                                text = runTextBuilder.toString(),
                                isBold = currentState.isBold,
                                isItalic = currentState.isItalic,
                                isUnderline = currentState.isUnderline,
                                isStrike = currentState.isStrike,
                                fontSizeSp = currentState.fontSizeSp,
                                colorHex = currentState.colorHex,
                                isHighlight = currentState.highlightColorHex != null,
                                highlightColorHex = currentState.highlightColorHex
                            )
                        )
                        runTextBuilder.clear()
                    }
                }

                fun flushParagraph() {
                    flushCurrentRun()
                    if (currentRuns.isNotEmpty() || elements.isNotEmpty()) {
                        if (paragraphCount < MAX_PARAGRAPHS) {
                            elements.add(
                                DocumentElement.Paragraph(
                                    runs = currentRuns.toList(),
                                    alignment = currentState.alignment,
                                    isHeading = currentState.isHeading,
                                    headingLevel = currentState.headingLevel,
                                    isBullet = currentState.isBullet
                                )
                            )
                            paragraphCount++
                        }
                        currentRuns.clear()
                    }
                }

                var chInt = reader.read()
                while (chInt != -1) {
                    val ch = chInt.toChar()

                    when (ch) {
                        '{' -> {
                            flushCurrentRun()
                            stateStack.push(currentState.copyState())
                        }

                        '}' -> {
                            flushCurrentRun()
                            if (stateStack.isNotEmpty()) {
                                currentState = stateStack.pop()
                            }
                        }

                        '\\' -> {
                            // Control word or control symbol
                            val nextInt = reader.read()
                            if (nextInt == -1) break
                            val nextChar = nextInt.toChar()

                            when (nextChar) {
                                '\\', '{', '}' -> {
                                    if (!currentState.skipGroup) {
                                        runTextBuilder.append(nextChar)
                                    }
                                }

                                '~' -> {
                                    if (!currentState.skipGroup) {
                                        runTextBuilder.append('\u00A0') // Non-breaking space
                                    }
                                }

                                '_' -> {
                                    if (!currentState.skipGroup) {
                                        runTextBuilder.append('-') // Optional/non-breaking hyphen
                                    }
                                }

                                '\'' -> {
                                    // Hex escape \'hh
                                    val h1 = reader.read()
                                    val h2 = reader.read()
                                    if (h1 != -1 && h2 != -1) {
                                        val hexStr = "${h1.toChar()}${h2.toChar()}"
                                        val byteVal = hexStr.toIntOrNull(16)
                                        if (byteVal != null && !currentState.skipGroup) {
                                            runTextBuilder.append(byteVal.toChar())
                                        }
                                    }
                                }

                                '*' -> {
                                    // Ignorable destination marker
                                    currentState.skipGroup = true
                                }

                                '\r', '\n' -> {
                                    // Escaped newline counts as paragraph break in some RTF variants
                                    flushParagraph()
                                }

                                else -> {
                                    if (nextChar.isLetter()) {
                                        // Read control word
                                        val wordBuilder = StringBuilder().append(nextChar)
                                        reader.mark(64)
                                        var peek = reader.read()
                                        while (peek != -1 && peek.toChar().isLetter()) {
                                            wordBuilder.append(peek.toChar())
                                            reader.mark(64)
                                            peek = reader.read()
                                        }

                                        // Read optional numeric parameter (can start with '-')
                                        val paramBuilder = StringBuilder()
                                        if (peek != -1 && (peek.toChar().isDigit() || peek.toChar() == '-')) {
                                            paramBuilder.append(peek.toChar())
                                            reader.mark(64)
                                            peek = reader.read()
                                            while (peek != -1 && peek.toChar().isDigit()) {
                                                paramBuilder.append(peek.toChar())
                                                reader.mark(64)
                                                peek = reader.read()
                                            }
                                        }

                                        // If followed by space, consume space delimiter
                                        if (peek != -1 && peek.toChar() == ' ') {
                                            // Delimiter consumed
                                        } else if (peek != -1) {
                                            reader.reset()
                                        }

                                        val word = wordBuilder.toString().lowercase()
                                        val param = paramBuilder.toString().toIntOrNull()

                                        // Process control word
                                        when (word) {
                                            "par" -> {
                                                if (!currentState.skipGroup) {
                                                    flushParagraph()
                                                }
                                            }

                                            "line" -> {
                                                if (!currentState.skipGroup) {
                                                    runTextBuilder.append("\n")
                                                }
                                            }

                                            "tab" -> {
                                                if (!currentState.skipGroup) {
                                                    runTextBuilder.append("    ")
                                                }
                                            }

                                            "bullet" -> {
                                                if (!currentState.skipGroup) {
                                                    runTextBuilder.append("• ")
                                                }
                                            }

                                            "b" -> {
                                                flushCurrentRun()
                                                currentState.isBold = (param == null || param != 0)
                                            }

                                            "i" -> {
                                                flushCurrentRun()
                                                currentState.isItalic = (param == null || param != 0)
                                            }

                                            "ul" -> {
                                                flushCurrentRun()
                                                currentState.isUnderline = (param == null || param != 0)
                                            }

                                            "ulnone" -> {
                                                flushCurrentRun()
                                                currentState.isUnderline = false
                                            }

                                            "strike" -> {
                                                flushCurrentRun()
                                                currentState.isStrike = (param == null || param != 0)
                                            }

                                            "fs" -> {
                                                flushCurrentRun()
                                                if (param != null) {
                                                    currentState.fontSizeSp = (param / 2f).coerceIn(8f, 72f)
                                                }
                                            }

                                            "cf" -> {
                                                flushCurrentRun()
                                                currentState.colorHex = if (param != null && param > 0) {
                                                    colorTable.getOrNull(param - 1)
                                                } else null
                                            }

                                            "highlight" -> {
                                                flushCurrentRun()
                                                currentState.highlightColorHex = if (param != null && param > 0) {
                                                    colorTable.getOrNull(param - 1)
                                                } else null
                                            }

                                            "ql" -> currentState.alignment = DocumentAlignment.LEFT
                                            "qc" -> currentState.alignment = DocumentAlignment.CENTER
                                            "qr" -> currentState.alignment = DocumentAlignment.RIGHT
                                            "qj" -> currentState.alignment = DocumentAlignment.JUSTIFY

                                            "red" -> pendingRed = (param ?: 0).coerceIn(0, 255)
                                            "green" -> pendingGreen = (param ?: 0).coerceIn(0, 255)
                                            "blue" -> pendingBlue = (param ?: 0).coerceIn(0, 255)

                                            "u" -> {
                                                if (param != null && !currentState.skipGroup) {
                                                    val codePoint = if (param < 0) param + 65536 else param
                                                    runTextBuilder.append(codePoint.toChar())

                                                    // Skip Unicode fallback replacement character(s)
                                                    repeat(currentState.unicodeSkipCount) {
                                                        reader.mark(10)
                                                        val skipChar = reader.read()
                                                        if (skipChar != -1 && skipChar.toChar() == '\\') {
                                                            reader.reset()
                                                        }
                                                    }
                                                }
                                            }

                                            "uc" -> {
                                                if (param != null) {
                                                    currentState.unicodeSkipCount = param.coerceIn(0, 10)
                                                }
                                            }

                                            "fonttbl", "stylesheet", "info", "pict", "header", "footer", "generator" -> {
                                                currentState.skipGroup = true
                                                currentState.destination = word
                                            }

                                            "colortbl" -> {
                                                currentState.skipGroup = true
                                                currentState.destination = "colortbl"
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        ';' -> {
                            // Semicolon inside colortbl finishes a color entry
                            if (currentState.destination == "colortbl") {
                                val hex = String.format("#%02X%02X%02X", pendingRed, pendingGreen, pendingBlue)
                                colorTable.add(hex)
                                pendingRed = 0
                                pendingGreen = 0
                                pendingBlue = 0
                            } else if (!currentState.skipGroup) {
                                runTextBuilder.append(';')
                            }
                        }

                        '\r', '\n' -> {
                            // Unescaped CR / LF are whitespace in raw RTF
                        }

                        else -> {
                            if (!currentState.skipGroup) {
                                runTextBuilder.append(ch)
                            }
                        }
                    }

                    chInt = reader.read()
                }

                // Flush final run and paragraph
                flushParagraph()

                // If document is completely empty or has no paragraphs, create fallback empty paragraph
                if (elements.isEmpty()) {
                    elements.add(
                        DocumentElement.Paragraph(
                            runs = listOf(DocumentRun(text = "Empty RTF Document"))
                        )
                    )
                }

                Result.success(
                    DocumentData(
                        elements = elements,
                        paragraphCount = elements.filterIsInstance<DocumentElement.Paragraph>().size,
                        tableCount = 0,
                        imageCount = 0,
                        formatLabel = "Rich Text Format (RTF)"
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(
                IllegalArgumentException(
                    "This RTF file appears corrupted or invalid: ${e.localizedMessage ?: "Unknown parsing error"}",
                    e
                )
            )
        }
    }
}
