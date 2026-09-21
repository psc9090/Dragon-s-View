// app/src/main/java/com/dragonview/app/viewer/xlsx/XlsxParser.kt
package com.dragonview.app.viewer.xlsx

import android.content.Context
import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * High-performance, streaming OpenXML Spreadsheet (XLSX) Parser.
 *
 * Key design & memory discipline tenets:
 * - Zero heavyweight dependencies (pure Android XmlPullParser).
 * - Loads sharedStrings.xml once into memory for instant O(1) cell text lookups.
 * - Parses styles.xml to detect cell types (date vs numeric, bold, colors).
 * - Streams worksheet XML (sheetN.xml) on-demand per sheet.
 * - Stores rows as sparse Map<ColumnIndex, Cell> to prevent allocating empty cells.
 * - Caps initial row parse (default 1000 rows) with pagination capability.
 * - Isolated error handling: a corrupted sheet surfaces an error message specifically
 *   for that sheet without crashing the workbook or other functional sheets.
 */
class XlsxParser private constructor(
    private val context: Context,
    private val uri: Uri,
    override val workbook: XlsxWorkbook,
    private val sharedStrings: List<String>,
    private val styles: List<XlsxStyleInfo>
) : SpreadsheetParser {

    // Simple 2-item LRU sheet cache (keeps active sheet + last visited sheet)
    private val sheetCache = LinkedHashMap<String, XlsxSheet>(4, 0.75f, true)

    companion object {
        const val DEFAULT_ROW_LIMIT = 1000

        /**
         * Initializes the workbook metadata, shared strings, and style tables.
         */
        suspend fun create(context: Context, uri: Uri): Result<XlsxParser> = withContext(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver

                var workbookXmlBytes: ByteArray? = null
                var workbookRelsXmlBytes: ByteArray? = null
                var sharedStringsXmlBytes: ByteArray? = null
                var stylesXmlBytes: ByteArray? = null

                contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipStream ->
                        var entry: ZipEntry?
                        while (zipStream.nextEntry.also { entry = it } != null) {
                            val name = entry?.name ?: ""
                            when {
                                name == "xl/workbook.xml" -> {
                                    workbookXmlBytes = zipStream.readBytesCompat()
                                }
                                name == "xl/_rels/workbook.xml.rels" -> {
                                    workbookRelsXmlBytes = zipStream.readBytesCompat()
                                }
                                name == "xl/sharedStrings.xml" -> {
                                    sharedStringsXmlBytes = zipStream.readBytesCompat()
                                }
                                name == "xl/styles.xml" -> {
                                    stylesXmlBytes = zipStream.readBytesCompat()
                                }
                            }
                            zipStream.closeEntry()
                        }
                    }
                } ?: return@withContext Result.failure(
                    IllegalArgumentException("Unable to open stream for XLSX document.")
                )

                if (workbookXmlBytes == null) {
                    return@withContext Result.failure(
                        IllegalArgumentException("This XLSX file appears corrupted or uses an unsupported structure (missing xl/workbook.xml).")
                    )
                }

                // 1. Parse workbook relationships (rId -> worksheet path)
                val relsMap = workbookRelsXmlBytes?.let { parseWorkbookRels(it) } ?: emptyMap()

                // 2. Parse workbook sheet list
                val sheets = parseWorkbookXml(workbookXmlBytes!!, relsMap)
                if (sheets.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("This XLSX file appears corrupted or uses an unsupported structure (no worksheets defined).")
                    )
                }

                // 3. Parse sharedStrings.xml if present
                val sharedStrings = sharedStringsXmlBytes?.let { parseSharedStrings(it) } ?: emptyList()

                // 4. Parse styles.xml if present
                val styles = stylesXmlBytes?.let { parseStyles(it) } ?: emptyList()

                val workbook = XlsxWorkbook(
                    sheets = sheets,
                    title = "Spreadsheet"
                )

                Result.success(XlsxParser(context, uri, workbook, sharedStrings, styles))
            } catch (e: Exception) {
                Result.failure(
                    IllegalArgumentException("This XLSX file appears corrupted or uses an unsupported structure: ${e.message}", e)
                )
            }
        }

        private fun parseWorkbookRels(xmlBytes: ByteArray): Map<String, String> {
            val map = mutableMapOf<String, String>()
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(ByteArrayInputStream(xmlBytes), null)
            }

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                    val id = parser.getAttributeValue(null, "Id")
                    val target = parser.getAttributeValue(null, "Target")
                    if (id != null && target != null) {
                        val normalized = if (target.startsWith("xl/")) target else "xl/$target"
                        map[id] = normalized
                    }
                }
                eventType = parser.next()
            }
            return map
        }

        private fun parseWorkbookXml(xmlBytes: ByteArray, relsMap: Map<String, String>): List<XlsxSheetRef> {
            val sheets = mutableListOf<XlsxSheetRef>()
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(ByteArrayInputStream(xmlBytes), null)
            }

            var eventType = parser.eventType
            var fallbackSheetIndex = 1
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tag = parser.name.substringAfter(":")
                    if (tag == "sheet") {
                        val name = parser.getAttributeValue(null, "name") ?: "Sheet $fallbackSheetIndex"
                        val sheetId = parser.getAttributeValue(null, "sheetId") ?: "$fallbackSheetIndex"
                        val rId = parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                            ?: parser.getAttributeValue(null, "r:id")
                            ?: parser.getAttributeValue(null, "id")
                            ?: "rId$fallbackSheetIndex"

                        val targetPath = relsMap[rId] ?: "xl/worksheets/sheet$fallbackSheetIndex.xml"
                        sheets.add(
                            XlsxSheetRef(
                                sheetId = sheetId,
                                name = name,
                                relationId = rId,
                                targetPath = targetPath
                            )
                        )
                        fallbackSheetIndex++
                    }
                }
                eventType = parser.next()
            }
            return sheets
        }

        private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
            val list = ArrayList<String>()
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(ByteArrayInputStream(xmlBytes), null)
            }

            var eventType = parser.eventType
            val currentSb = StringBuilder()
            var insideSi = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.substringAfter(":")
                        if (tag == "si") {
                            insideSi = true
                            currentSb.setLength(0)
                        } else if (insideSi && tag == "t") {
                            currentSb.append(parser.nextText())
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.substringAfter(":")
                        if (tag == "si") {
                            list.add(currentSb.toString())
                            insideSi = false
                        }
                    }
                }
                eventType = parser.next()
            }
            return list
        }

        private fun parseStyles(xmlBytes: ByteArray): List<XlsxStyleInfo> {
            val customNumFmts = mutableMapOf<Int, String>()
            val fonts = mutableListOf<FontStyle>()
            val cellXfs = mutableListOf<XlsxStyleInfo>()

            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(ByteArrayInputStream(xmlBytes), null)
            }

            var eventType = parser.eventType
            var inNumFmts = false
            var inFonts = false
            var inCellXfs = false

            var currentFontBold = false
            var currentFontColor: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.substringAfter(":")
                        when (tag) {
                            "numFmts" -> inNumFmts = true
                            "numFmt" -> if (inNumFmts) {
                                val id = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                                val code = parser.getAttributeValue(null, "formatCode") ?: ""
                                if (id != null) customNumFmts[id] = code
                            }
                            "fonts" -> inFonts = true
                            "font" -> if (inFonts) {
                                currentFontBold = false
                                currentFontColor = null
                            }
                            "b" -> if (inFonts) currentFontBold = true
                            "color" -> if (inFonts) {
                                val rgb = parser.getAttributeValue(null, "rgb")
                                if (!rgb.isNullOrBlank()) {
                                    currentFontColor = if (rgb.length == 8) "#${rgb.substring(2)}" else "#$rgb"
                                }
                            }
                            "cellXfs" -> inCellXfs = true
                            "xf" -> if (inCellXfs) {
                                val numFmtId = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0
                                val fontId = parser.getAttributeValue(null, "fontId")?.toIntOrNull() ?: 0

                                val isDate = isDateFormat(numFmtId, customNumFmts[numFmtId])
                                val font = fonts.getOrNull(fontId)

                                cellXfs.add(
                                    XlsxStyleInfo(
                                        isDate = isDate,
                                        isBold = font?.isBold ?: false,
                                        colorHex = font?.colorHex
                                    )
                                )
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.substringAfter(":")
                        when (tag) {
                            "numFmts" -> inNumFmts = false
                            "fonts" -> inFonts = false
                            "font" -> if (inFonts) {
                                fonts.add(FontStyle(currentFontBold, currentFontColor))
                            }
                            "cellXfs" -> inCellXfs = false
                        }
                    }
                }
                eventType = parser.next()
            }
            return cellXfs
        }

        private fun isDateFormat(numFmtId: Int, customCode: String?): Boolean {
            // Built-in standard Excel date and time format IDs
            if (numFmtId in 14..22 || numFmtId in 27..36 || numFmtId in 45..47 || numFmtId in 50..58) {
                return true
            }
            if (customCode != null) {
                val lower = customCode.lowercase(Locale.ROOT)
                // Check for common date/time format tokens while ignoring escaped text
                val hasDateToken = lower.contains("yy") || lower.contains("dd") || lower.contains("mmm")
                val hasTimeToken = lower.contains("hh") || lower.contains("ss")
                if (hasDateToken || hasTimeToken) {
                    return true
                }
            }
            return false
        }

        private fun ZipInputStream.readBytesCompat(): ByteArray {
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var read: Int
            while (this.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /**
     * Loads or retrieves from LRU cache a worksheet's data up to [maxRows].
     */
    override suspend fun loadSheet(sheetRef: XlsxSheetRef, maxRows: Int): SheetLoadState = withContext(Dispatchers.IO) {
        synchronized(sheetCache) {
            val cached = sheetCache[sheetRef.sheetId]
            if (cached != null && (cached.totalRowsParsed >= maxRows || !cached.hasMoreRows)) {
                return@withContext SheetLoadState.Success(cached)
            }
        }

        try {
            val sheetXmlBytes = readSheetBytes(sheetRef.targetPath)
                ?: return@withContext SheetLoadState.Error(
                    "This XLSX file appears corrupted or uses an unsupported structure (missing sheet data)."
                )

            val parsedSheet = parseWorksheetXml(sheetRef, sheetXmlBytes, maxRows)
            synchronized(sheetCache) {
                // Keep max 2 sheets in memory
                if (sheetCache.size >= 2) {
                    val firstKey = sheetCache.keys.firstOrNull()
                    if (firstKey != null) sheetCache.remove(firstKey)
                }
                sheetCache[sheetRef.sheetId] = parsedSheet
            }
            SheetLoadState.Success(parsedSheet)
        } catch (e: Exception) {
            SheetLoadState.Error(
                "This XLSX file appears corrupted or uses an unsupported structure for '${sheetRef.name}'."
            )
        }
    }

    private fun readSheetBytes(targetPath: String): ByteArray? {
        val normalizedPath = targetPath.removePrefix("/")
        val simpleName = normalizedPath.substringAfterLast("/")

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zipStream ->
                var entry: ZipEntry?
                while (zipStream.nextEntry.also { entry = it } != null) {
                    val name = entry?.name ?: ""
                    if (name == normalizedPath || name == "xl/$normalizedPath" || name.endsWith("/$simpleName")) {
                        val bytes = zipStream.readBytesCompat()
                        zipStream.closeEntry()
                        return bytes
                    }
                    zipStream.closeEntry()
                }
            }
        }
        return null
    }

    private fun parseWorksheetXml(sheetRef: XlsxSheetRef, xmlBytes: ByteArray, maxRows: Int): XlsxSheet {
        val rows = mutableListOf<XlsxRow>()
        var maxColIndex = 0
        var hasMoreRows = false

        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(xmlBytes), null)
        }

        var eventType = parser.eventType
        var currentRowNum = 0
        var currentCells = mutableMapOf<Int, XlsxCell>()
        var inRow = false
        var currentCellRef = ""
        var currentCellType = ""
        var currentCellStyleIndex = -1
        var currentFormula: String? = null
        var currentVal: String? = null
        var currentInlineText: String? = null
        var inCell = false
        var inFormula = false
        var inValue = false
        var inInlineStr = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(":")
                    when (tag) {
                        "row" -> {
                            if (rows.size >= maxRows) {
                                hasMoreRows = true
                                break
                            }
                            inRow = true
                            val r = parser.getAttributeValue(null, "r")?.toIntOrNull()
                            currentRowNum = r ?: (currentRowNum + 1)
                            currentCells = mutableMapOf()
                        }
                        "c" -> if (inRow) {
                            inCell = true
                            currentCellRef = parser.getAttributeValue(null, "r") ?: ""
                            currentCellType = parser.getAttributeValue(null, "t") ?: ""
                            currentCellStyleIndex = parser.getAttributeValue(null, "s")?.toIntOrNull() ?: -1
                            currentFormula = null
                            currentVal = null
                            currentInlineText = null
                        }
                        "f" -> if (inCell) inFormula = true
                        "v" -> if (inCell) inValue = true
                        "is" -> if (inCell) inInlineStr = true
                        "t" -> if (inInlineStr) {
                            currentInlineText = parser.nextText()
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inFormula && currentFormula == null) {
                        currentFormula = parser.text
                    } else if (inValue && currentVal == null) {
                        currentVal = parser.text
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(":")
                    when (tag) {
                        "f" -> inFormula = false
                        "v" -> inValue = false
                        "is" -> inInlineStr = false
                        "c" -> if (inCell) {
                            inCell = false
                            val colIndex = extractColumnIndex(currentCellRef, currentCells.size)
                            if (colIndex > maxColIndex) maxColIndex = colIndex

                            val styleInfo = if (currentCellStyleIndex >= 0) styles.getOrNull(currentCellStyleIndex) else null
                            val cell = buildCell(
                                colIndex = colIndex,
                                rowIndex = currentRowNum,
                                rawVal = currentVal,
                                inlineText = currentInlineText,
                                cellTypeAttr = currentCellType,
                                formula = currentFormula,
                                styleInfo = styleInfo
                            )
                            if (cell != null) {
                                currentCells[colIndex] = cell
                            }
                        }
                        "row" -> if (inRow) {
                            inRow = false
                            if (currentCells.isNotEmpty()) {
                                rows.add(XlsxRow(rowIndex = currentRowNum, cells = currentCells))
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return XlsxSheet(
            sheetRef = sheetRef,
            rows = rows,
            maxColumnIndex = maxColIndex,
            totalRowsParsed = rows.size,
            hasMoreRows = hasMoreRows
        )
    }

    private fun buildCell(
        colIndex: Int,
        rowIndex: Int,
        rawVal: String?,
        inlineText: String?,
        cellTypeAttr: String,
        formula: String?,
        styleInfo: XlsxStyleInfo?
    ): XlsxCell? {
        val raw = rawVal ?: inlineText ?: ""
        if (raw.isEmpty() && formula == null) return null

        var type = CellType.TEXT
        var displayVal = raw

        when (cellTypeAttr) {
            "s" -> {
                // Shared String lookup
                val sstIdx = raw.toIntOrNull()
                if (sstIdx != null && sstIdx >= 0 && sstIdx < sharedStrings.size) {
                    displayVal = sharedStrings[sstIdx]
                }
                type = CellType.TEXT
            }
            "inlineStr" -> {
                displayVal = inlineText ?: raw
                type = CellType.TEXT
            }
            "b" -> {
                displayVal = if (raw == "1" || raw.equals("true", ignoreCase = true)) "TRUE" else "FALSE"
                type = CellType.BOOLEAN
            }
            "e" -> {
                displayVal = raw
                type = CellType.ERROR
            }
            else -> {
                // Default is numeric or formula result
                val numVal = raw.toDoubleOrNull()
                if (numVal != null) {
                    if (styleInfo?.isDate == true) {
                        displayVal = formatExcelDate(numVal)
                        type = CellType.DATE
                    } else {
                        displayVal = formatNumber(numVal)
                        type = CellType.NUMBER
                    }
                } else if (formula != null) {
                    type = CellType.FORMULA
                    displayVal = raw.ifEmpty { "=$formula" }
                } else {
                    type = CellType.TEXT
                }
            }
        }

        return XlsxCell(
            colIndex = colIndex,
            rowIndex = rowIndex,
            rawValue = raw,
            displayValue = displayVal,
            type = type,
            formula = formula,
            isBold = styleInfo?.isBold ?: false,
            colorHex = styleInfo?.colorHex
        )
    }

    private fun extractColumnIndex(cellRef: String, fallbackCol: Int): Int {
        val letters = cellRef.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return fallbackCol
        return columnLetterToIndex(letters)
    }

    private fun columnLetterToIndex(colStr: String): Int {
        var col = 0
        for (ch in colStr.uppercase(Locale.ROOT)) {
            if (ch in 'A'..'Z') {
                col = col * 26 + (ch - 'A' + 1)
            }
        }
        return (col - 1).coerceAtLeast(0)
    }

    private fun formatNumber(num: Double): String {
        return if (num == num.toLong().toDouble()) {
            num.toLong().toString()
        } else {
            val df = DecimalFormat("#,##0.####")
            df.format(num)
        }
    }

    private fun formatExcelDate(serialDate: Double): String {
        return try {
            // Excel dates count days since 1899-12-30 (accounting for the 1900 leap year bug)
            val wholeDays = serialDate.toLong()
            val fractionOfDay = serialDate - wholeDays

            // Calculate millis
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
                add(Calendar.DAY_OF_YEAR, wholeDays.toInt())
                val millisInDay = (fractionOfDay * 86400000).toLong()
                add(Calendar.MILLISECOND, millisInDay.toInt())
            }

            val date = calendar.time
            val sdf = if (fractionOfDay > 0.0001) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            } else {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            }
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.format(date)
        } catch (_: Exception) {
            formatNumber(serialDate)
        }
    }
}

data class XlsxStyleInfo(
    val isDate: Boolean = false,
    val isBold: Boolean = false,
    val colorHex: String? = null
)

private data class FontStyle(
    val isBold: Boolean = false,
    val colorHex: String? = null
)

/**
 * Utility to convert a 0-based column index to Excel column string (0 -> "A", 25 -> "Z", 26 -> "AA").
 */
fun indexToColumnLetter(index: Int): String {
    var num = index + 1
    val sb = StringBuilder()
    while (num > 0) {
        val rem = (num - 1) % 26
        sb.append(('A'.code + rem).toChar())
        num = (num - 1) / 26
    }
    return sb.reverse().toString()
}
