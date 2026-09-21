// app/src/main/java/com/dragonview/app/viewer/xlsx/XlsxModel.kt
package com.dragonview.app.viewer.xlsx

/**
 * High-level XLSX Spreadsheet Data Model.
 * Memory-efficient representation using sparse cell maps.
 */
data class XlsxWorkbook(
    val sheets: List<XlsxSheetRef>,
    val title: String = "Spreadsheet"
)

data class XlsxSheetRef(
    val sheetId: String,
    val name: String,
    val relationId: String,
    val targetPath: String // e.g. "xl/worksheets/sheet1.xml"
)

data class XlsxSheet(
    val sheetRef: XlsxSheetRef,
    val rows: List<XlsxRow>,
    val maxColumnIndex: Int, // 0-based maximum column index encountered
    val totalRowsParsed: Int,
    val hasMoreRows: Boolean = false,
    val errorMessage: String? = null
) {
    val rowCount: Int get() = rows.size
    val columnCount: Int get() = if (rows.isEmpty()) 0 else maxColumnIndex + 1
}

data class XlsxRow(
    val rowIndex: Int, // 1-based (e.g., Row 1, Row 2)
    val cells: Map<Int, XlsxCell> // 0-based columnIndex -> Cell (sparse map)
)

data class XlsxCell(
    val colIndex: Int, // 0-based (0 = A, 1 = B, etc.)
    val rowIndex: Int, // 1-based
    val rawValue: String,
    val displayValue: String,
    val type: CellType,
    val formula: String? = null,
    val isBold: Boolean = false,
    val colorHex: String? = null
)

enum class CellType {
    TEXT,
    NUMBER,
    DATE,
    BOOLEAN,
    FORMULA,
    ERROR,
    BLANK
}

sealed class SheetLoadState {
    object Loading : SheetLoadState()
    data class Success(val sheet: XlsxSheet) : SheetLoadState()
    data class Error(val message: String) : SheetLoadState()
}

/**
 * Common spreadsheet parser interface for XLSX and ODS formats.
 */
interface SpreadsheetParser {
    val workbook: XlsxWorkbook
    suspend fun loadSheet(sheetRef: XlsxSheetRef, maxRows: Int = 1000): SheetLoadState
}

