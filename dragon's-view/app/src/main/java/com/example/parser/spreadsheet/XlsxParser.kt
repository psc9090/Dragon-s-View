package com.example.parser.spreadsheet

import com.example.model.*
import com.example.parser.xml.XmlPullHelpers
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipFile

object XlsxParser {

  fun parse(fileSource: FileSource): SheetWorkbook {
    val zip = fileSource.asZip()
    try {
      // 1. Parse shared strings
      val sharedStrings = parseSharedStrings(zip)

      // 2. Parse workbook to get sheets
      val sheetEntries = parseWorkbookSheets(zip)

      val sheetTabs = mutableListOf<SheetTab>()

      for ((index, info) in sheetEntries.withIndex()) {
        val sheetZipName = "xl/worksheets/sheet${index + 1}.xml"
        val sheetEntry = zip.getEntry(sheetZipName)
          ?: zip.getEntry("xl/${info.target}")
          ?: continue

        zip.getInputStream(sheetEntry).use { stream ->
          val tab = parseWorksheet(stream, info.name, sharedStrings)
          sheetTabs.add(tab)
        }
      }

      if (sheetTabs.isEmpty()) {
        sheetTabs.add(
          SheetTab(
            name = "Sheet1",
            maxRows = 1,
            maxCols = 1,
            rows = listOf(SheetRow(1, mapOf(1 to CellValue.Text("Empty Sheet"))))
          )
        )
      }

      return SheetWorkbook(sheets = sheetTabs)
    } finally {
      try { zip.close() } catch (_: Exception) {}
    }
  }

  private fun parseSharedStrings(zip: ZipFile): List<String> {
    val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
    val strings = mutableListOf<String>()

    zip.getInputStream(entry).use { stream ->
      val parser = XmlPullHelpers.createSafeParser(stream)
      var event = parser.eventType
      var inStringItem = false
      val currentItem = StringBuilder()

      while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
          if (parser.name == "si") {
            inStringItem = true
            currentItem.clear()
          } else if (inStringItem && parser.name == "t") {
            currentItem.append(parser.nextText())
          }
        } else if (event == XmlPullParser.END_TAG) {
          if (parser.name == "si") {
            inStringItem = false
            strings.add(currentItem.toString())
          }
        }
        event = parser.next()
      }
    }
    return strings
  }

  private data class SheetInfo(val name: String, val target: String)

  private fun parseWorkbookSheets(zip: ZipFile): List<SheetInfo> {
    val list = mutableListOf<SheetInfo>()
    val entry = zip.getEntry("xl/workbook.xml") ?: return listOf(SheetInfo("Sheet1", "worksheets/sheet1.xml"))

    zip.getInputStream(entry).use { stream ->
      val parser = XmlPullHelpers.createSafeParser(stream)
      var event = parser.eventType

      while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
          val name = parser.getAttributeValue(null, "name") ?: "Sheet"
          val rId = parser.getAttributeValue(null, "id") ?: ""
          list.add(SheetInfo(name, "worksheets/sheet${list.size + 1}.xml"))
        }
        event = parser.next()
      }
    }
    return list
  }

  private fun parseWorksheet(
    stream: InputStream,
    sheetName: String,
    sharedStrings: List<String>
  ): SheetTab {
    val parser = XmlPullHelpers.createSafeParser(stream)
    var event = parser.eventType

    val rows = mutableListOf<SheetRow>()
    var maxRow = 0
    var maxCol = 0

    var currentRowIndex = 0
    val currentCells = mutableMapOf<Int, CellValue>()

    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG) {
        val tagName = parser.name
        if (tagName == "row") {
          currentRowIndex = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: (currentRowIndex + 1)
          currentCells.clear()
          if (currentRowIndex > maxRow) maxRow = currentRowIndex
        } else if (tagName == "c") {
          val cellRef = parser.getAttributeValue(null, "r") ?: ""
          val cellType = parser.getAttributeValue(null, "t") ?: ""
          val (col, _) = parseCellReference(cellRef)

          if (col > maxCol) maxCol = col

          var cellValue: CellValue = CellValue.Empty

          var depth = 1
          var rawVal = ""
          var formulaVal = ""

          while (depth > 0) {
            val e = parser.next()
            if (e == XmlPullParser.START_TAG) {
              depth++
              if (parser.name == "v") {
                rawVal = parser.nextText()
                depth--
              } else if (parser.name == "f") {
                formulaVal = parser.nextText()
                depth--
              }
            } else if (e == XmlPullParser.END_TAG) {
              depth--
            } else if (e == XmlPullParser.END_DOCUMENT) break
          }

          if (rawVal.isNotEmpty() || formulaVal.isNotEmpty()) {
            cellValue = when (cellType) {
              "s" -> {
                val sIndex = rawVal.toIntOrNull() ?: -1
                val text = if (sIndex in sharedStrings.indices) sharedStrings[sIndex] else rawVal
                CellValue.Text(text)
              }
              "b" -> CellValue.Bool(rawVal == "1")
              "str" -> CellValue.Text(rawVal)
              else -> {
                if (formulaVal.isNotEmpty()) {
                  CellValue.Formula(formulaVal, rawVal)
                } else {
                  val dVal = rawVal.toDoubleOrNull()
                  if (dVal != null) {
                    val formatted = if (dVal == dVal.toLong().toDouble()) dVal.toLong().toString() else rawVal
                    CellValue.Number(dVal, formatted)
                  } else {
                    CellValue.Text(rawVal)
                  }
                }
              }
            }
          }

          if (cellValue != CellValue.Empty && col > 0) {
            currentCells[col] = cellValue
          }
        }
      } else if (event == XmlPullParser.END_TAG && parser.name == "row") {
        if (currentCells.isNotEmpty()) {
          rows.add(SheetRow(currentRowIndex, currentCells.toMap()))
        }
      }
      event = parser.next()
    }

    return SheetTab(
      name = sheetName,
      maxRows = maxRow.coerceAtLeast(10),
      maxCols = maxCol.coerceAtLeast(5),
      rows = rows
    )
  }

  private fun parseCellReference(ref: String): Pair<Int, Int> {
    var col = 0
    var row = 0
    val colStr = ref.takeWhile { it.isLetter() }
    val rowStr = ref.dropWhile { it.isLetter() }

    for (ch in colStr.uppercase()) {
      col = col * 26 + (ch - 'A' + 1)
    }
    row = rowStr.toIntOrNull() ?: 1
    return Pair(col, row)
  }
}
