package com.example.parser.spreadsheet

import com.example.model.*
import com.example.parser.xml.XmlPullHelpers
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipFile

object OdsParser {

  fun parse(fileSource: FileSource, isFlatXml: Boolean = false): SheetWorkbook {
    val tabs = mutableListOf<SheetTab>()

    if (isFlatXml) {
      fileSource.openStream().use { stream ->
        parseContentStream(stream, tabs)
      }
    } else {
      val zip = fileSource.asZip()
      try {
        val entry = zip.getEntry("content.xml")
          ?: throw IllegalStateException("content.xml not found in ODS package")
        zip.getInputStream(entry).use { stream ->
          parseContentStream(stream, tabs)
        }
      } finally {
        try { zip.close() } catch (_: Exception) {}
      }
    }

    if (tabs.isEmpty()) {
      tabs.add(
        SheetTab(
          name = "Sheet1",
          maxRows = 1,
          maxCols = 1,
          rows = listOf(SheetRow(1, mapOf(1 to CellValue.Text("Empty Sheet"))))
        )
      )
    }

    return SheetWorkbook(sheets = tabs)
  }

  private fun parseContentStream(stream: InputStream, tabs: MutableList<SheetTab>) {
    val parser = XmlPullHelpers.createSafeParser(stream)
    var event = parser.eventType

    var currentSheetName = "Sheet1"
    var rows = mutableListOf<SheetRow>()
    var currentRowIndex = 0
    var maxCols = 0

    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG) {
        val name = parser.name
        if (name == "table") {
          currentSheetName = parser.getAttributeValue(null, "name") ?: "Sheet${tabs.size + 1}"
          rows = mutableListOf()
          currentRowIndex = 0
          maxCols = 0
        } else if (name == "table-row") {
          currentRowIndex++
          val repeatRows = parser.getAttributeValue(null, "number-rows-repeated")?.toIntOrNull() ?: 1
          val cells = mutableMapOf<Int, CellValue>()
          var currentCol = 0

          var depth = 1
          while (depth > 0) {
            val e = parser.next()
            if (e == XmlPullParser.START_TAG) {
              depth++
              if (parser.name == "table-cell") {
                currentCol++
                val repeatCols = parser.getAttributeValue(null, "number-columns-repeated")?.toIntOrNull() ?: 1
                val cellText = StringBuilder()

                var cellDepth = 1
                while (cellDepth > 0) {
                  val ce = parser.next()
                  if (ce == XmlPullParser.START_TAG) {
                    cellDepth++
                  } else if (ce == XmlPullParser.TEXT) {
                    cellText.append(parser.text)
                  } else if (ce == XmlPullParser.END_TAG) {
                    cellDepth--
                  } else if (ce == XmlPullParser.END_DOCUMENT) break
                }
                depth-- // cell parsed

                val text = cellText.toString().trim()
                if (text.isNotEmpty()) {
                  val value = text.toDoubleOrNull()?.let {
                    val formatted = if (it == it.toLong().toDouble()) it.toLong().toString() else text
                    CellValue.Number(it, formatted)
                  } ?: CellValue.Text(text)
                  cells[currentCol] = value
                }

                // If repeated columns with empty value, don't expand thousands of empty cells!
                if (repeatCols > 1 && text.isNotEmpty()) {
                  val count = repeatCols.coerceAtMost(32)
                  for (r in 1 until count) {
                    cells[currentCol + r] = CellValue.Text(text)
                  }
                  currentCol += (count - 1)
                }
              }
            } else if (e == XmlPullParser.END_TAG) {
              depth--
            } else if (e == XmlPullParser.END_DOCUMENT) break
          }

          if (currentCol > maxCols) maxCols = currentCol
          if (cells.isNotEmpty()) {
            rows.add(SheetRow(currentRowIndex, cells))
            if (repeatRows in 2..10) {
              for (r in 1 until repeatRows) {
                rows.add(SheetRow(currentRowIndex + r, cells))
              }
              currentRowIndex += (repeatRows - 1)
            }
          }
        }
      } else if (event == XmlPullParser.END_TAG && parser.name == "table") {
        tabs.add(
          SheetTab(
            name = currentSheetName,
            maxRows = currentRowIndex.coerceAtLeast(10),
            maxCols = maxCols.coerceAtLeast(5),
            rows = rows
          )
        )
      }
      event = parser.next()
    }
  }
}
