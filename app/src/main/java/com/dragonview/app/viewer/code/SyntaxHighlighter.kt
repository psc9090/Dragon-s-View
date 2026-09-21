// app/src/main/java/com/dragonview/app/viewer/code/SyntaxHighlighter.kt
package com.dragonview.app.viewer.code

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * Lightweight, regex-based syntax highlighter for plain TextView / Spannable.
 * Avoids heavy WebView or Monaco/Ace editor overhead, ensuring smooth execution on 3-4GB RAM devices.
 *
 * Performance Features:
 * - All Pattern objects are compiled once at class load time and reused across all files.
 * - Single-pass regex application over text.
 * - Files exceeding 500KB cap live syntax scanning and fall back to plain text to prevent scroll jank.
 */
object SyntaxHighlighter {

    /**
     * File size limit (500 KB) beyond which syntax highlighting is skipped to protect memory and FPS.
     */
    const val MAX_HIGHLIGHT_CHAR_LIMIT = 500_000

    private val highlightCache = android.util.LruCache<Int, SpannableStringBuilder>(20)

    fun clearCache() {
        highlightCache.evictAll()
    }

    // Official Dracula Theme Palette (https://spec.draculatheme.com)
    val DRACULA_BG = Color.parseColor("#282A36")          // Background
    val DRACULA_CURRENT = Color.parseColor("#44475A")     // Current Line / Selection
    val DRACULA_FG = Color.parseColor("#F8F8F2")          // Foreground
    val DRACULA_COMMENT = Color.parseColor("#6272A4")     // Comment
    val DRACULA_CYAN = Color.parseColor("#8BE9FD")        // Cyan
    val DRACULA_GREEN = Color.parseColor("#50FA7B")       // Green
    val DRACULA_ORANGE = Color.parseColor("#FFB86C")      // Orange
    val DRACULA_PINK = Color.parseColor("#FF79C6")        // Pink
    val DRACULA_PURPLE = Color.parseColor("#BD93F9")      // Purple
    val DRACULA_RED = Color.parseColor("#FF5555")         // Red
    val DRACULA_YELLOW = Color.parseColor("#F1FA8C")      // Yellow

    // Syntax role mapping following Dracula specification
    private val COLOR_KEYWORD = DRACULA_PINK              // Keywords, reserved operators
    private val COLOR_STRING = DRACULA_YELLOW             // Strings, template literals
    private val COLOR_COMMENT = DRACULA_COMMENT           // Comments, gutter
    private val COLOR_NUMBER = DRACULA_PURPLE             // Constants, numbers, booleans
    private val COLOR_TYPE = DRACULA_CYAN                 // Types, classes, interfaces
    private val COLOR_TAG = DRACULA_PINK                  // HTML/XML Tags
    private val COLOR_ATTR = DRACULA_GREEN                // HTML/XML Attributes
    private val COLOR_JSON_KEY = DRACULA_CYAN             // JSON keys
    private val COLOR_DECORATOR = DRACULA_ORANGE          // Decorators, annotations
    private val COLOR_CSS_SELECTOR = DRACULA_GREEN        // CSS Selectors
    private val COLOR_CSS_PROPERTY = DRACULA_CYAN         // CSS Properties
    private val COLOR_MD_HEADER = DRACULA_PURPLE          // Markdown headers
    private val COLOR_CODE_INLINE = DRACULA_GREEN         // Inline code
    private val COLOR_PUNCTUATION = DRACULA_FG            // Punctuation, symbols

    // --- Universal Numeric and String Patterns ---
    private val NUMBER_PATTERN = Pattern.compile("\\b(0x[0-9a-fA-F]+|\\d+(\\.\\d+)?([eE][+-]?\\d+)?[fFlLuU]?)\\b")
    private val DOUBLE_QUOTE_STRING = Pattern.compile("\"(\\\\.|[^\"\\\\])*\"")
    private val SINGLE_QUOTE_STRING = Pattern.compile("'(\\\\.|[^'\\\\])*'")
    private val BACKTICK_STRING = Pattern.compile("`(\\\\.|[^`\\\\])*`")
    private val PYTHON_TRIPLE_STRING = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''")

    // --- Comments ---
    private val LINE_COMMENT_SLASH = Pattern.compile("//.*")
    private val LINE_COMMENT_HASH = Pattern.compile("#.*")
    private val BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/")
    private val HTML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->")

    // --- Language Keyword Pattern Strings ---
    private const val PYTHON_KEYWORDS = "\\b(def|class|import|from|return|if|elif|else|while|for|in|try|except|finally|with|as|lambda|yield|raise|pass|break|continue|None|True|False|self|and|or|not|is|async|await)\\b"
    private const val CPP_KEYWORDS = "\\b(#include|#define|#ifdef|#ifndef|#endif|int|char|float|double|void|bool|long|short|unsigned|signed|struct|class|union|enum|public|private|protected|template|typename|namespace|using|return|if|else|for|while|do|switch|case|break|continue|default|auto|const|static|virtual|override|new|delete|nullptr|true|false)\\b"
    private const val JAVA_KOTLIN_KEYWORDS = "\\b(package|import|fun|val|var|class|interface|object|enum|sealed|data|override|public|private|protected|internal|return|if|else|when|for|while|do|try|catch|finally|throw|null|true|false|this|super|new|static|final|abstract|void|int|boolean|suspend|companion|lateinit)\\b"
    private const val JS_KEYWORDS = "\\b(const|let|var|function|return|import|export|from|default|async|await|if|else|for|while|do|switch|case|break|continue|try|catch|finally|throw|new|this|typeof|instanceof|class|extends|super|yield|void|static|null|undefined|true|false)\\b"
    private const val SHELL_KEYWORDS = "\\b(if|then|else|elif|fi|case|esac|for|while|until|do|done|in|function|select|return|exit|echo|export|source|local)\\b"
    private const val SQL_KEYWORDS = "\\b(SELECT|FROM|WHERE|INSERT|INTO|UPDATE|DELETE|JOIN|LEFT|RIGHT|INNER|OUTER|ON|GROUP|BY|ORDER|HAVING|LIMIT|OFFSET|CREATE|TABLE|DROP|ALTER|PRIMARY|KEY|FOREIGN|REFERENCES|NOT|NULL|AND|OR|IN|LIKE|AS|DISTINCT|UNION|VALUES)\\b"

    // --- Precompiled Pattern Instances (Compiled Once, Reused) ---
    private val PATTERN_PYTHON_KW = Pattern.compile(PYTHON_KEYWORDS)
    private val PATTERN_CPP_KW = Pattern.compile(CPP_KEYWORDS)
    private val PATTERN_JAVA_KOTLIN_KW = Pattern.compile(JAVA_KOTLIN_KEYWORDS)
    private val PATTERN_JS_KW = Pattern.compile(JS_KEYWORDS)
    private val PATTERN_JS_ARROW = Pattern.compile("=>")
    private val PATTERN_SHELL_KW = Pattern.compile(SHELL_KEYWORDS)
    private val PATTERN_SQL_KW = Pattern.compile(SQL_KEYWORDS, Pattern.CASE_INSENSITIVE)

    // Python Decorators
    private val PATTERN_PYTHON_DECORATOR = Pattern.compile("@[a-zA-Z_][a-zA-Z0-9_.]*")

    // HTML / XML Patterns
    private val PATTERN_HTML_TAG = Pattern.compile("</?[a-zA-Z0-9:_-]+(\\s|>|/)")
    private val PATTERN_HTML_ATTR = Pattern.compile("\\b[a-zA-Z0-9:_-]+(?=\\s*=)")
    private val PATTERN_HTML_DOCTYPE = Pattern.compile("<!DOCTYPE[^>]*>", Pattern.CASE_INSENSITIVE)
    private val PATTERN_XML_PI = Pattern.compile("<\\?[\\s\\S]*?\\?>")
    private val PATTERN_XML_CDATA = Pattern.compile("<!\\[CDATA\\[[\\s\\S]*?\\]\\]>")

    // CSS Patterns
    private val PATTERN_CSS_SELECTOR = Pattern.compile("(?m)^\\s*([.#]?[a-zA-Z0-9_:-]+[^{;]*?)(?=\\s*\\{)")
    private val PATTERN_CSS_PROPERTY = Pattern.compile("\\b([a-zA-Z-]+)(?=\\s*:)")
    private val PATTERN_CSS_UNIT = Pattern.compile("\\b\\d+(\\.\\d+)?(px|em|rem|%|vh|vw|pt|s|ms|deg)?\\b")
    private val PATTERN_CSS_HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{3,8}\\b")

    // JSON Patterns
    private val PATTERN_JSON_KEY = Pattern.compile("\"(\\\\.|[^\"\\\\])*\"(?=\\s*:)")
    private val PATTERN_JSON_BOOLEAN_NULL = Pattern.compile("\\b(true|false|null)\\b")
    private val PATTERN_JSON_PUNCTUATION = Pattern.compile("[\\[\\]{},:]")

    // Markdown Patterns
    private val PATTERN_MD_HEADER = Pattern.compile("(?m)^#{1,6}\\s+.*$")
    private val PATTERN_MD_BOLD = Pattern.compile("(\\*\\*|__)(.*?)\\1")
    private val PATTERN_MD_ITALIC = Pattern.compile("(?<!\\*)(\\*|_)(?!\\*)(.*?)(?<!\\*)\\1(?!\\*)")
    private val PATTERN_MD_INLINE_CODE = Pattern.compile("`[^`\\n]+`")

    /**
     * Checks whether text length is within safe syntax highlighting boundaries.
     */
    fun isEligibleForHighlight(length: Int): Boolean = length <= MAX_HIGHLIGHT_CHAR_LIMIT

    suspend fun highlight(
        source: CharSequence,
        language: CodeLanguage,
        maxLinesToHighlight: Int = 4000
    ): SpannableStringBuilder = withContext(Dispatchers.Default) {
        val cacheKey = (source.hashCode() * 31 + language.hashCode()) * 31 + maxLinesToHighlight
        synchronized(highlightCache) {
            highlightCache.get(cacheKey)?.let { cached ->
                return@withContext SpannableStringBuilder(cached)
            }
        }

        val ssb = SpannableStringBuilder(source)

        // Safety cap: if file exceeds 500KB, fall back to plain text immediately
        if (source.length > MAX_HIGHLIGHT_CHAR_LIMIT) {
            return@withContext ssb
        }

        // Restrict scanning to maxLinesToHighlight to prevent unbounded regex iteration
        val lengthToScan = if (ssb.lines().size > maxLinesToHighlight) {
            val lines = ssb.lines().take(maxLinesToHighlight)
            lines.sumOf { it.length + 1 }
        } else {
            ssb.length
        }
        val scanSlice = ssb.subSequence(0, minOf(lengthToScan, ssb.length))

        when (language) {
            CodeLanguage.PYTHON -> {
                // Strings first (including triple quoted)
                applyPattern(ssb, scanSlice, PYTHON_TRIPLE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, DOUBLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, SINGLE_QUOTE_STRING, COLOR_STRING)
                // Numbers & Decorators
                applyPattern(ssb, scanSlice, NUMBER_PATTERN, COLOR_NUMBER)
                applyPattern(ssb, scanSlice, PATTERN_PYTHON_DECORATOR, COLOR_DECORATOR)
                // Keywords
                applyPattern(ssb, scanSlice, PATTERN_PYTHON_KW, COLOR_KEYWORD)
                // Comments last to ensure full line masking
                applyPattern(ssb, scanSlice, LINE_COMMENT_HASH, COLOR_COMMENT)
            }

            CodeLanguage.JAVASCRIPT -> {
                // Keywords & arrow
                applyPattern(ssb, scanSlice, PATTERN_JS_KW, COLOR_KEYWORD)
                applyPattern(ssb, scanSlice, PATTERN_JS_ARROW, COLOR_KEYWORD)
                applyPattern(ssb, scanSlice, NUMBER_PATTERN, COLOR_NUMBER)
                // Strings (double, single, template literals)
                applyPattern(ssb, scanSlice, DOUBLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, SINGLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, BACKTICK_STRING, COLOR_STRING)
                // Comments
                applyPattern(ssb, scanSlice, LINE_COMMENT_SLASH, COLOR_COMMENT)
                applyPattern(ssb, scanSlice, BLOCK_COMMENT, COLOR_COMMENT)
            }

            CodeLanguage.HTML -> {
                // Doctype
                applyPattern(ssb, scanSlice, PATTERN_HTML_DOCTYPE, COLOR_KEYWORD)
                // Tags & attributes
                applyPattern(ssb, scanSlice, PATTERN_HTML_TAG, COLOR_TAG)
                applyPattern(ssb, scanSlice, PATTERN_HTML_ATTR, COLOR_ATTR)
                // Attribute values
                applyPattern(ssb, scanSlice, DOUBLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, SINGLE_QUOTE_STRING, COLOR_STRING)
                // HTML comments
                applyPattern(ssb, scanSlice, HTML_COMMENT, COLOR_COMMENT)
            }

            CodeLanguage.XML -> {
                // Processing instructions & CDATA
                applyPattern(ssb, scanSlice, PATTERN_XML_PI, COLOR_KEYWORD)
                applyPattern(ssb, scanSlice, PATTERN_XML_CDATA, COLOR_STRING)
                // Tags & attributes (shared with HTML tokenizer)
                applyPattern(ssb, scanSlice, PATTERN_HTML_TAG, COLOR_TAG)
                applyPattern(ssb, scanSlice, PATTERN_HTML_ATTR, COLOR_ATTR)
                // Attribute values
                applyPattern(ssb, scanSlice, DOUBLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, SINGLE_QUOTE_STRING, COLOR_STRING)
                // Comments
                applyPattern(ssb, scanSlice, HTML_COMMENT, COLOR_COMMENT)
            }

            CodeLanguage.CSS -> {
                // Selectors & properties
                applyPattern(ssb, scanSlice, PATTERN_CSS_SELECTOR, COLOR_CSS_SELECTOR)
                applyPattern(ssb, scanSlice, PATTERN_CSS_PROPERTY, COLOR_CSS_PROPERTY)
                // Values: units, numbers, hex colors, strings
                applyPattern(ssb, scanSlice, PATTERN_CSS_UNIT, COLOR_NUMBER)
                applyPattern(ssb, scanSlice, PATTERN_CSS_HEX_COLOR, COLOR_ATTR)
                applyPattern(ssb, scanSlice, DOUBLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, SINGLE_QUOTE_STRING, COLOR_STRING)
                // Comments
                applyPattern(ssb, scanSlice, BLOCK_COMMENT, COLOR_COMMENT)
            }

            CodeLanguage.JSON -> {
                // Structural punctuation & numbers
                applyPattern(ssb, scanSlice, PATTERN_JSON_PUNCTUATION, COLOR_PUNCTUATION)
                applyPattern(ssb, scanSlice, NUMBER_PATTERN, COLOR_NUMBER)
                applyPattern(ssb, scanSlice, PATTERN_JSON_BOOLEAN_NULL, COLOR_KEYWORD)
                // String values
                applyPattern(ssb, scanSlice, DOUBLE_QUOTE_STRING, COLOR_STRING)
                // Keys (quoted strings before colon override string values with distinct key color)
                applyPattern(ssb, scanSlice, PATTERN_JSON_KEY, COLOR_JSON_KEY)
            }

            CodeLanguage.MARKDOWN -> {
                // Headers: bold + light coral
                applyPatternWithStyle(ssb, scanSlice, PATTERN_MD_HEADER, COLOR_MD_HEADER, Typeface.BOLD)
                // Bold markers
                applyStyleOnly(ssb, scanSlice, PATTERN_MD_BOLD, Typeface.BOLD)
                // Italic markers
                applyStyleOnly(ssb, scanSlice, PATTERN_MD_ITALIC, Typeface.ITALIC)
                // Inline code
                applyPattern(ssb, scanSlice, PATTERN_MD_INLINE_CODE, COLOR_CODE_INLINE)
            }

            CodeLanguage.C_CPP -> {
                applyPattern(ssb, scanSlice, PATTERN_CPP_KW, COLOR_KEYWORD)
                applyPattern(ssb, scanSlice, NUMBER_PATTERN, COLOR_NUMBER)
                applyPattern(ssb, scanSlice, DOUBLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, SINGLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, LINE_COMMENT_SLASH, COLOR_COMMENT)
                applyPattern(ssb, scanSlice, BLOCK_COMMENT, COLOR_COMMENT)
            }

            CodeLanguage.JAVA_KOTLIN -> {
                applyPattern(ssb, scanSlice, PATTERN_JAVA_KOTLIN_KW, COLOR_KEYWORD)
                applyPattern(ssb, scanSlice, NUMBER_PATTERN, COLOR_NUMBER)
                applyPattern(ssb, scanSlice, DOUBLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, SINGLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, LINE_COMMENT_SLASH, COLOR_COMMENT)
                applyPattern(ssb, scanSlice, BLOCK_COMMENT, COLOR_COMMENT)
            }

            CodeLanguage.SHELL -> {
                applyPattern(ssb, scanSlice, PATTERN_SHELL_KW, COLOR_KEYWORD)
                applyPattern(ssb, scanSlice, NUMBER_PATTERN, COLOR_NUMBER)
                applyPattern(ssb, scanSlice, DOUBLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, SINGLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, LINE_COMMENT_HASH, COLOR_COMMENT)
            }

            CodeLanguage.SQL -> {
                applyPattern(ssb, scanSlice, PATTERN_SQL_KW, COLOR_KEYWORD)
                applyPattern(ssb, scanSlice, NUMBER_PATTERN, COLOR_NUMBER)
                applyPattern(ssb, scanSlice, SINGLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, DOUBLE_QUOTE_STRING, COLOR_STRING)
                applyPattern(ssb, scanSlice, LINE_COMMENT_SLASH, COLOR_COMMENT)
            }

            CodeLanguage.PLAIN_TEXT -> {
                // Minimal styling for plain text logs
                applyPattern(ssb, scanSlice, NUMBER_PATTERN, COLOR_NUMBER)
            }
        }

        synchronized(highlightCache) {
            highlightCache.put(cacheKey, ssb)
        }

        ssb
    }

    private fun applyPattern(
        ssb: SpannableStringBuilder,
        text: CharSequence,
        pattern: Pattern,
        color: Int
    ) {
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            ssb.setSpan(
                ForegroundColorSpan(color),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun applyPatternWithStyle(
        ssb: SpannableStringBuilder,
        text: CharSequence,
        pattern: Pattern,
        color: Int,
        style: Int
    ) {
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            ssb.setSpan(
                ForegroundColorSpan(color),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ssb.setSpan(
                StyleSpan(style),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun applyStyleOnly(
        ssb: SpannableStringBuilder,
        text: CharSequence,
        pattern: Pattern,
        style: Int
    ) {
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            ssb.setSpan(
                StyleSpan(style),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
