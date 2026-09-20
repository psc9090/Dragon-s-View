package com.example.parser.markdown

import com.example.model.FileSource

data class MarkdownDoc(
  val title: String,
  val frontMatter: Map<String, String>? = null,
  val blocks: List<MarkdownBlock> = emptyList()
)

sealed interface MarkdownBlock

data class MdHeading(val level: Int, val text: String) : MarkdownBlock
data class MdParagraph(val text: String) : MarkdownBlock
data class MdCodeBlock(val language: String, val code: String) : MarkdownBlock
data class MdBlockQuote(val text: String) : MarkdownBlock
data class MdCallout(val type: String, val title: String, val content: String) : MarkdownBlock
data class MdListItem(val text: String, val level: Int, val isOrdered: Boolean, val isTask: Boolean = false, val isChecked: Boolean = false) : MarkdownBlock
data class MdTable(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
object MdDivider : MarkdownBlock

object MarkdownParser {

  fun parse(fileSource: FileSource): MarkdownDoc {
    val lines = mutableListOf<String>()
    fileSource.openStream().use { stream ->
      stream.bufferedReader(Charsets.UTF_8).forEachLine { lines.add(it) }
    }

    var i = 0
    var frontMatter: MutableMap<String, String>? = null

    // 1. Check YAML front matter
    if (lines.isNotEmpty() && lines[0].trim() == "---") {
      i = 1
      val fm = mutableMapOf<String, String>()
      while (i < lines.size && lines[i].trim() != "---") {
        val line = lines[i]
        val colon = line.indexOf(':')
        if (colon != -1) {
          val key = line.substring(0, colon).trim()
          val value = line.substring(colon + 1).trim()
          fm[key] = value
        }
        i++
      }
      if (i < lines.size && lines[i].trim() == "---") {
        i++ // consume closing ---
        frontMatter = fm
      } else {
        // Reset if no closing ---
        i = 0
      }
    }

    val blocks = mutableListOf<MarkdownBlock>()

    while (i < lines.size) {
      val rawLine = lines[i]
      val line = rawLine.trim()

      if (line.isEmpty()) {
        i++
        continue
      }

      // Fenced code block
      if (line.startsWith("```")) {
        val lang = line.removePrefix("```").trim()
        i++
        val codeBuilder = StringBuilder()
        while (i < lines.size && !lines[i].trim().startsWith("```")) {
          codeBuilder.append(lines[i]).append("\n")
          i++
        }
        if (i < lines.size) i++ // consume closing ```
        blocks.add(MdCodeBlock(lang, codeBuilder.toString().trimEnd()))
        continue
      }

      // Heading
      if (line.startsWith("#")) {
        val level = line.takeWhile { it == '#' }.length.coerceAtMost(6)
        val text = line.drop(level).trim()
        blocks.add(MdHeading(level, text))
        i++
        continue
      }

      // Horizontal rule
      if (line == "---" || line == "***" || line == "___") {
        blocks.add(MdDivider)
        i++
        continue
      }

      // Callout or blockquote
      if (line.startsWith(">")) {
        val bqLines = mutableListOf<String>()
        while (i < lines.size && lines[i].trim().startsWith(">")) {
          bqLines.add(lines[i].trim().removePrefix(">").trim())
          i++
        }
        val first = bqLines.firstOrNull() ?: ""
        if (first.startsWith("[!") && first.contains("]")) {
          val calloutType = first.substring(2, first.indexOf(']')).uppercase()
          val content = bqLines.drop(1).joinToString("\n")
          blocks.add(MdCallout(calloutType, calloutType, content))
        } else {
          blocks.add(MdBlockQuote(bqLines.joinToString("\n")))
        }
        continue
      }

      // Table: line contains '|'
      if (line.startsWith("|") && line.endsWith("|")) {
        val tableLines = mutableListOf<String>()
        while (i < lines.size && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
          tableLines.add(lines[i].trim())
          i++
        }
        if (tableLines.size >= 2) {
          val headers = parseTableRow(tableLines[0])
          // skip separator line tableLines[1]
          val rows = mutableListOf<List<String>>()
          for (r in 2 until tableLines.size) {
            rows.add(parseTableRow(tableLines[r]))
          }
          blocks.add(MdTable(headers, rows))
        }
        continue
      }

      // Lists & Tasks
      if (line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("* [ ] ") || line.startsWith("* [x] ")) {
        val isChecked = line.contains("[x]") || line.contains("[X]")
        val text = line.substring(6).trim()
        blocks.add(MdListItem(text, level = 0, isOrdered = false, isTask = true, isChecked = isChecked))
        i++
        continue
      }

      if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
        val text = line.substring(2).trim()
        blocks.add(MdListItem(text, level = 0, isOrdered = false))
        i++
        continue
      }

      val orderedMatch = "^(\\d+)\\.\\s+(.*)".toRegex().matchEntire(line)
      if (orderedMatch != null) {
        val text = orderedMatch.groupValues[2]
        blocks.add(MdListItem(text, level = 0, isOrdered = true))
        i++
        continue
      }

      // Normal paragraph
      val pBuilder = StringBuilder(line)
      i++
      while (i < lines.size && lines[i].trim().isNotEmpty() &&
        !lines[i].trim().startsWith("#") &&
        !lines[i].trim().startsWith("```") &&
        !lines[i].trim().startsWith(">") &&
        !lines[i].trim().startsWith("- ") &&
        !lines[i].trim().startsWith("* ") &&
        !lines[i].trim().startsWith("|")
      ) {
        pBuilder.append(" ").append(lines[i].trim())
        i++
      }
      blocks.add(MdParagraph(pBuilder.toString()))
    }

    return MarkdownDoc(
      title = fileSource.displayName.substringBeforeLast('.'),
      frontMatter = frontMatter,
      blocks = blocks
    )
  }

  private fun parseTableRow(line: String): List<String> {
    return line.split("|")
      .map { it.trim() }
      .filterIndexed { index, _ -> index != 0 && index != line.split("|").lastIndex }
  }
}
