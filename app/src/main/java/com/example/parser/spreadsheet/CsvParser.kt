package com.example.parser.spreadsheet

import com.example.model.*
import java.io.BufferedReader
import java.io.InputStreamReader

object CsvParser {

  fun parse(fileSource: FileSource): SheetWorkbook {
    val rows = mutableListOf<SheetRow>()
    var maxCols = 0

    fileSource.openStream().use { stream ->
      val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
      val sampleLines = mutableListOf<String>()
      var line = reader.readLine()
      var count = 0
      while (line != null && count < 10) {
        sampleLines.add(line)
        line = reader.readLine()
        count++
      }

      val delimiter = detectDelimiter(sampleLines)

      // Reset stream or re-read
      var rowIndex = 1

      fun processLine(raw: String) {
        val tokens = parseCsvLine(raw, delimiter)
        if (tokens.size > maxCols) maxCols = tokens.size
        val cellMap = mutableMapOf<Int, CellValue>()
        for ((idx, tok) in tokens.withIndex()) {
          val text = tok.trim()
          if (text.isNotEmpty()) {
            val num = text.toDoubleOrNull()
            if (num != null) {
              val formatted = if (num == num.toLong().toDouble()) num.toLong().toString() else text
              cellMap[idx + 1] = CellValue.Number(num, formatted)
            } else {
              cellMap[idx + 1] = CellValue.Text(text)
            }
          }
        }
        rows.add(SheetRow(rowIndex++, cellMap))
      }

      for (s in sampleLines) {
        processLine(s)
      }

      line = reader.readLine()
      while (line != null && rowIndex < 10000) {
        processLine(line)
        line = reader.readLine()
      }
    }

    val tab = SheetTab(
      name = fileSource.displayName.substringBeforeLast('.'),
      maxRows = rows.size.coerceAtLeast(10),
      maxCols = maxCols.coerceAtLeast(5),
      rows = rows
    )

    return SheetWorkbook(sheets = listOf(tab))
  }

  private fun detectDelimiter(lines: List<String>): Char {
    if (lines.isEmpty()) return ','
    val sample = lines.joinToString("\n")
    val commas = sample.count { it == ',' }
    val tabs = sample.count { it == '\t' }
    val semicolons = sample.count { it == ';' }
    val pipes = sample.count { it == '|' }

    return when {
      tabs > commas && tabs > semicolons -> '\t'
      semicolons > commas && semicolons > pipes -> ';'
      pipes > commas -> '|'
      else -> ','
    }
  }

  private fun parseCsvLine(line: String, delimiter: Char): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false

    var i = 0
    while (i < line.length) {
      val c = line[i]
      if (c == '"') {
        if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
          current.append('"')
          i++
        } else {
          inQuotes = !inQuotes
        }
      } else if (c == delimiter && !inQuotes) {
        tokens.add(current.toString())
        current.clear()
      } else {
        current.append(c)
      }
      i++
    }
    tokens.add(current.toString())
    return tokens
  }
}
