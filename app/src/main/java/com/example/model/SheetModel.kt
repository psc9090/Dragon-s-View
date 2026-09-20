package com.example.model

data class SheetWorkbook(
  val sheets: List<SheetTab>,
  val activeSheetIndex: Int = 0
)

data class SheetTab(
  val name: String,
  val maxRows: Int,
  val maxCols: Int,
  val rows: List<SheetRow>,
  val mergedRanges: List<CellRange> = emptyList(),
  val frozenRows: Int = 1,
  val frozenCols: Int = 1
)

data class SheetRow(
  val rowIndex: Int, // 1-based
  val cells: Map<Int, CellValue> // key is 1-based colIndex
)

sealed interface CellValue {
  val displayText: String

  data class Text(override val displayText: String) : CellValue
  data class Number(val value: Double, override val displayText: String) : CellValue
  data class Date(val dateStr: String, override val displayText: String) : CellValue
  data class Bool(val value: Boolean) : CellValue {
    override val displayText: String = if (value) "TRUE" else "FALSE"
  }
  data class Formula(val expression: String, val cachedResult: String) : CellValue {
    override val displayText: String = cachedResult
  }
  object Empty : CellValue {
    override val displayText: String = ""
  }
}

data class CellRange(
  val startRow: Int,
  val startCol: Int,
  val endRow: Int,
  val endCol: Int
)

fun colIndexToLetter(col: Int): String {
  var c = col
  val sb = StringBuilder()
  while (c > 0) {
    val rem = (c - 1) % 26
    sb.append(('A'.code + rem).toChar())
    c = (c - 1) / 26
  }
  return sb.reverse().toString()
}
