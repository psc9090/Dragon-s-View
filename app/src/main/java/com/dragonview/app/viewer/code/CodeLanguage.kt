// app/src/main/java/com/dragonview/app/viewer/code/CodeLanguage.kt
package com.dragonview.app.viewer.code

/**
 * Recognized source code and text file types for syntax highlighting.
 */
enum class CodeLanguage(val displayName: String, val extensions: List<String>) {
    PYTHON("Python", listOf("py", "pyw", "ipy")),
    C_CPP("C / C++", listOf("c", "cpp", "cc", "cxx", "h", "hpp", "hxx")),
    JAVA_KOTLIN("Java / Kotlin", listOf("java", "kt", "kts")),
    JAVASCRIPT("JavaScript / TypeScript", listOf("js", "jsx", "mjs", "cjs", "ts", "tsx")),
    HTML("HTML", listOf("html", "htm", "xhtml")),
    XML("XML", listOf("xml")),
    CSS("CSS", listOf("css", "scss", "sass", "less")),
    JSON("JSON", listOf("json")),
    SHELL("Shell Script", listOf("sh", "bash", "zsh")),
    SQL("SQL", listOf("sql")),
    MARKDOWN("Markdown", listOf("md", "markdown")),
    PLAIN_TEXT("Plain Text", listOf("txt", "log", "conf", "cfg", "ini"));

    companion object {
        fun fromExtension(ext: String): CodeLanguage {
            val cleanExt = ext.lowercase().trimStart('.')
            return entries.firstOrNull { it.extensions.contains(cleanExt) } ?: PLAIN_TEXT
        }

        fun isCodeExtension(ext: String): Boolean {
            val cleanExt = ext.lowercase().trimStart('.')
            return entries.any { it.extensions.contains(cleanExt) }
        }
    }
}
