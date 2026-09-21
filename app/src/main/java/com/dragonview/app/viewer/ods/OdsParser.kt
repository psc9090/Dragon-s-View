// app/src/main/java/com/dragonview/app/viewer/ods/OdsParser.kt
package com.dragonview.app.viewer.ods

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.dragonview.app.router.FileFormat
import com.dragonview.app.viewer.xlsx.CellType
import com.dragonview.app.viewer.xlsx.SheetLoadState
import com.dragonview.app.viewer.xlsx.SpreadsheetParser
import com.dragonview.app.viewer.xlsx.XlsxCell
import com.dragonview.app.viewer.xlsx.XlsxRow
import com.dragonview.app.viewer.xlsx.XlsxSheet
import com.dragonview.app.viewer.xlsx.XlsxSheetRef
import com.dragonview.app.viewer.xlsx.XlsxWorkbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.DecimalFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * High-performance streaming parser for OpenDocument Spreadsheets (.ods, .ots, .fods).
 *
 * Core architectural highlights:
 * - Zero heavyweight dependencies (pure Android streaming XmlPullParser).
 * - Handles OpenDocument repeat-compression (table:number-columns-repeated and
 *   table:number-rows-repeated) by materializing only non-empty cells into the sparse
 *   Map<ColumnIndex, Cell> model without allocating thousands of repeated blank cells.
 * - Inspects office:value-type attribute (float/currency/date/boolean/string) directly.
 * - Parses styles.xml and <office:automatic-styles> for bold weights and font colors.
 * - Directly integrates with the existing shared SpreadsheetRenderer / XlsxRenderer pipeline.
 * - Handles .fods (Flat XML) directly without unzipping.
 * - Isolated per-sheet error boundaries: surfaces user-friendly error messages on corrupted
 *   sheets without failing the entire workbook.
 */
class OdsParser private constructor(
    private val context: Context,
    private val uri: Uri,
    override val workbook: XlsxWorkbook,
    private val isFlatXml: Boolean,
    private val contentXmlBytes: ByteArray?,
    private val cellStyles: Map<String, OdsCellStyle>
) : SpreadsheetParser {

    // Simple 2-item LRU sheet cache (keeps active sheet + last visited sheet)
    private val sheetCache = LinkedHashMap<String, XlsxSheet>(4, 0.75f, true)

    companion object {
        const val DEFAULT_ROW_LIMIT = 1000
        private const val CORRUPTED_ERROR_MESSAGE =
            "This ODS file appears corrupted or uses an unsupported structure."

        private val decimalFormat = DecimalFormat("#,##0.##")

        /**
         * Initializes workbook metadata and sheet list.
         */
        suspend fun create(
            context: Context,
            uri: Uri,
            formatHint: FileFormat? = null
        ): Result<OdsParser> = withContext(Dispatchers.IO) {
            val isFlat = formatHint == FileFormat.ODS_FLAT ||
                uri.lastPathSegment?.lowercase()?.endsWith(".fods") == true

            if (isFlat) {
                createFromFlatXml(context, uri)
            } else {
                createFromZip(context, uri, formatHint)
            }
        }

        private fun createFromZip(
            context: Context,
            uri: Uri,
            formatHint: FileFormat?
        ): Result<OdsParser> {
            var foundContentXml = false
            var stylesXmlBytes: ByteArray? = null
            var contentXmlBytes: ByteArray? = null

            try {
                context.contentResolver.openInputStream(uri)?.use { rawInputStream ->
                    ZipInputStream(rawInputStream).use { zipStream ->
                        var entry: ZipEntry?
                        while (true) {
                            try {
                                entry = zipStream.nextEntry
                            } catch (ze: ZipException) {
                                return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE, ze))
                            } catch (e: Exception) {
                                return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE, e))
                            }
                            if (entry == null) break

                            val entryName = entry.name
                            if (entryName == "styles.xml") {
                                stylesXmlBytes = readEntryBytes(zipStream, maxBytes = 4 * 1024 * 1024)
                            } else if (entryName == "content.xml") {
                                foundContentXml = true
                                contentXmlBytes = readEntryBytes(zipStream, maxBytes = 32 * 1024 * 1024)
                            }
                            zipStream.closeEntry()
                        }
                    }
                } ?: return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))

                if (!foundContentXml || contentXmlBytes == null || contentXmlBytes.isEmpty()) {
                    return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))
                }

                val cellStyles = mutableMapOf<String, OdsCellStyle>()

                // 1. Parse global styles from styles.xml
                stylesXmlBytes?.let { bytes ->
                    try {
                        ByteArrayInputStream(bytes).use { inStream ->
                            parseStylesXml(inStream, cellStyles)
                        }
                    } catch (_: Exception) {
                        // Fallback gracefully
                    }
                }

                // 2. Discover sheets and local automatic styles from content.xml
                val sheetRefs = mutableListOf<XlsxSheetRef>()
                ByteArrayInputStream(contentXmlBytes).use { inStream ->
                    discoverSheetsAndStyles(inStream, sheetRefs, cellStyles)
                }

                if (sheetRefs.isEmpty()) {
                    return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))
                }

                val title = uri.lastPathSegment
                    ?.removeSuffix(".ods")
                    ?.removeSuffix(".ots")
                    ?: "ODS Spreadsheet"

                return Result.success(
                    OdsParser(
                        context = context,
                        uri = uri,
                        workbook = XlsxWorkbook(sheets = sheetRefs, title = title),
                        isFlatXml = false,
                        contentXmlBytes = contentXmlBytes,
                        cellStyles = cellStyles
                    )
                )
            } catch (e: Exception) {
                return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE, e))
            }
        }

        private fun createFromFlatXml(
            context: Context,
            uri: Uri
        ): Result<OdsParser> {
            try {
                val contentXmlBytes: ByteArray = context.contentResolver.openInputStream(uri)?.use { stream ->
                    readStreamBytes(stream, maxBytes = 32 * 1024 * 1024)
                } ?: return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))

                if (contentXmlBytes.isEmpty()) {
                    return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))
                }

                val cellStyles = mutableMapOf<String, OdsCellStyle>()
                val sheetRefs = mutableListOf<XlsxSheetRef>()

                ByteArrayInputStream(contentXmlBytes).use { inStream ->
                    discoverSheetsAndStyles(inStream, sheetRefs, cellStyles)
                }

                if (sheetRefs.isEmpty()) {
                    return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))
                }

                val title = uri.lastPathSegment?.removeSuffix(".fods") ?: "Flat ODS Spreadsheet"

                return Result.success(
                    OdsParser(
                        context = context,
                        uri = uri,
                        workbook = XlsxWorkbook(sheets = sheetRefs, title = title),
                        isFlatXml = true,
                        contentXmlBytes = contentXmlBytes,
                        cellStyles = cellStyles
                    )
                )
            } catch (e: Exception) {
                return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE, e))
            }
        }

        private fun discoverSheetsAndStyles(
            inputStream: InputStream,
            outSheetRefs: MutableList<XlsxSheetRef>,
            cellStyles: MutableMap<String, OdsCellStyle>
        ) {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tag = parser.name.substringAfter(':')
                    when (tag) {
                        "automatic-styles", "styles" -> {
                            parseStylesBlock(parser, cellStyles)
                        }
                        "table" -> {
                            val sheetName = getAttr(parser, "name")
                                ?: "Sheet ${outSheetRefs.size + 1}"
                            val sheetId = "sheet_${outSheetRefs.size}"
                            outSheetRefs.add(
                                XlsxSheetRef(
                                    sheetId = sheetId,
                                    name = sheetName,
                                    relationId = sheetName,
                                    targetPath = "content.xml"
                                )
                            )
                        }
                    }
                }
                eventType = parser.next()
            }
        }

        private fun parseStylesXml(
            inputStream: InputStream,
            cellStyles: MutableMap<String, OdsCellStyle>
        ) {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tag = parser.name.substringAfter(':')
                    if (tag == "style") {
                        parseSingleStyle(parser, cellStyles)
                    }
                }
                eventType = parser.next()
            }
        }

        private fun parseStylesBlock(
            parser: XmlPullParser,
            cellStyles: MutableMap<String, OdsCellStyle>
        ) {
            val depth = parser.depth
            while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    val tag = parser.name.substringAfter(':')
                    if (tag == "style") {
                        parseSingleStyle(parser, cellStyles)
                    }
                }
            }
        }

        private fun parseSingleStyle(
            parser: XmlPullParser,
            cellStyles: MutableMap<String, OdsCellStyle>
        ) {
            val styleName = getAttr(parser, "name") ?: return
            val depth = parser.depth

            var bold: Boolean? = null
            var color: String? = null
            var bg: String? = null

            while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    val tag = parser.name.substringAfter(':')
                    when (tag) {
                        "text-properties" -> {
                            for (i in 0 until parser.attributeCount) {
                                val attrName = parser.getAttributeName(i).substringAfter(':')
                                val attrVal = parser.getAttributeValue(i) ?: continue
                                when (attrName.lowercase()) {
                                    "font-weight", "font-weight-asian", "font-weight-complex" -> {
                                        if (attrVal.equals("bold", ignoreCase = true) ||
                                            (attrVal.toIntOrNull() ?: 0) >= 700
                                        ) {
                                            bold = true
                                        }
                                    }
                                    "color" -> {
                                        if (attrVal.isNotBlank() && !attrVal.equals("auto", ignoreCase = true)) {
                                            color = if (attrVal.startsWith("#")) attrVal else "#$attrVal"
                                        }
                                    }
                                }
                            }
                        }
                        "table-cell-properties" -> {
                            val background = getAttr(parser, "background-color")
                            if (!background.isNullOrBlank() &&
                                !background.equals("transparent", ignoreCase = true) &&
                                !background.equals("none", ignoreCase = true)
                            ) {
                                bg = if (background.startsWith("#")) background else "#$background"
                            }
                        }
                    }
                }
            }

            if (bold != null || color != null || bg != null) {
                cellStyles[styleName] = OdsCellStyle(
                    isBold = bold ?: false,
                    colorHex = color,
                    backgroundColorHex = bg
                )
            }
        }

        private fun readEntryBytes(zipStream: ZipInputStream, maxBytes: Int): ByteArray {
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var bytesRead: Int
            var totalRead = 0
            while (zipStream.read(buffer).also { bytesRead = it } != -1) {
                baos.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalRead >= maxBytes) break
            }
            return baos.toByteArray()
        }

        private fun readStreamBytes(inputStream: InputStream, maxBytes: Int): ByteArray {
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var bytesRead: Int
            var totalRead = 0
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                baos.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalRead >= maxBytes) break
            }
            return baos.toByteArray()
        }

        private fun getAttr(parser: XmlPullParser, attrLocalName: String): String? {
            for (i in 0 until parser.attributeCount) {
                val name = parser.getAttributeName(i).substringAfter(':')
                if (name.equals(attrLocalName, ignoreCase = true)) {
                    return parser.getAttributeValue(i)
                }
            }
            return null
        }
    }

    /**
     * Loads or retrieves from LRU cache a worksheet's data up to [maxRows].
     *
     * Handles table:number-rows-repeated and table:number-columns-repeated safely
     * to avoid materializing millions of blank cells.
     */
    override suspend fun loadSheet(sheetRef: XlsxSheetRef, maxRows: Int): SheetLoadState = withContext(Dispatchers.IO) {
        synchronized(sheetCache) {
            val cached = sheetCache[sheetRef.sheetId]
            if (cached != null && (cached.totalRowsParsed >= maxRows || !cached.hasMoreRows)) {
                return@withContext SheetLoadState.Success(cached)
            }
        }

        val contentBytes = contentXmlBytes
        if (contentBytes == null || contentBytes.isEmpty()) {
            return@withContext SheetLoadState.Error(CORRUPTED_ERROR_MESSAGE)
        }

        try {
            ByteArrayInputStream(contentBytes).use { inStream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(inStream, "UTF-8")

                var eventType = parser.eventType
                var targetTableFound = false
                val rows = mutableListOf<XlsxRow>()
                var currentRowIndex = 1
                var maxColumnIndex = 0
                var hasMoreRows = false

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val tag = parser.name.substringAfter(':')
                        if (tag == "table") {
                            val tableName = getAttr(parser, "name") ?: ""
                            if (tableName == sheetRef.name || sheetRef.sheetId == "sheet_0" && !targetTableFound) {
                                targetTableFound = true
                                val tableDepth = parser.depth

                                while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == tableDepth)) {
                                    if (parser.eventType == XmlPullParser.START_TAG) {
                                        val childTag = parser.name.substringAfter(':')
                                        if (childTag == "table-row") {
                                            val rowRepeat = (getAttr(parser, "number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                                            val rowCells = parseRowCells(parser, currentRowIndex, cellStyles)

                                            if (rowCells.isNotEmpty()) {
                                                val highestColInRow = rowCells.keys.maxOrNull() ?: 0
                                                if (highestColInRow > maxColumnIndex) {
                                                    maxColumnIndex = highestColInRow
                                                }

                                                val remaining = maxRows - rows.size
                                                val toAdd = minOf(rowRepeat, remaining)
                                                for (r in 0 until toAdd) {
                                                    val actualRowIdx = currentRowIndex + r
                                                    val cellsForThisRow = if (r == 0) {
                                                        rowCells
                                                    } else {
                                                        rowCells.mapValues { (_, cell) ->
                                                            cell.copy(rowIndex = actualRowIdx)
                                                        }
                                                    }
                                                    rows.add(XlsxRow(rowIndex = actualRowIdx, cells = cellsForThisRow))
                                                }

                                                currentRowIndex += rowRepeat
                                                if (rows.size >= maxRows) {
                                                    hasMoreRows = true
                                                    break
                                                }
                                            } else {
                                                // Entire row was empty: do NOT allocate empty cells, just advance row index
                                                currentRowIndex += rowRepeat
                                            }
                                        }
                                    }
                                }
                                break // Completed parsing the target table
                            }
                        }
                    }
                    eventType = parser.next()
                }

                if (!targetTableFound) {
                    return@withContext SheetLoadState.Error(CORRUPTED_ERROR_MESSAGE)
                }

                val loadedSheet = XlsxSheet(
                    sheetRef = sheetRef,
                    rows = rows,
                    maxColumnIndex = maxColumnIndex,
                    totalRowsParsed = rows.size,
                    hasMoreRows = hasMoreRows
                )

                synchronized(sheetCache) {
                    sheetCache[sheetRef.sheetId] = loadedSheet
                }

                SheetLoadState.Success(loadedSheet)
            }
        } catch (e: Exception) {
            SheetLoadState.Error(CORRUPTED_ERROR_MESSAGE)
        }
    }

    private fun parseRowCells(
        parser: XmlPullParser,
        currentRowIndex: Int,
        cellStyles: Map<String, OdsCellStyle>
    ): Map<Int, XlsxCell> {
        val cells = mutableMapOf<Int, XlsxCell>()
        var currentColIndex = 0
        val rowDepth = parser.depth

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == rowDepth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                if (tag == "table-cell" || tag == "covered-table-cell") {
                    val colRepeat = (getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    val cell = parseSingleCell(parser, currentColIndex, currentRowIndex, cellStyles)

                    if (cell != null) {
                        // Materialize only non-empty cells.
                        // Cap repeat count to prevent memory explosion if malicious/unusual.
                        val repeatsToStore = minOf(colRepeat, 512)
                        for (c in 0 until repeatsToStore) {
                            val targetCol = currentColIndex + c
                            cells[targetCol] = if (c == 0) cell else cell.copy(colIndex = targetCol)
                        }
                    }
                    // Always advance currentColIndex by the full repeat count
                    currentColIndex += colRepeat
                }
            }
        }
        return cells
    }

    private fun parseSingleCell(
        parser: XmlPullParser,
        colIndex: Int,
        rowIndex: Int,
        cellStyles: Map<String, OdsCellStyle>
    ): XlsxCell? {
        val valueType = getAttr(parser, "value-type")
        val rawNum = getAttr(parser, "value")
        val dateVal = getAttr(parser, "date-value")
        val timeVal = getAttr(parser, "time-value")
        val boolVal = getAttr(parser, "boolean-value")
        val strVal = getAttr(parser, "string-value")
        val formulaAttr = getAttr(parser, "formula")
        val styleName = getAttr(parser, "style-name")

        val cellDepth = parser.depth
        val textBuilder = StringBuilder()

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == cellDepth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val childTag = parser.name.substringAfter(':')
                when (childTag) {
                    "p" -> {
                        val pText = readTagText(parser)
                        if (pText.isNotEmpty()) {
                            if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                            textBuilder.append(pText)
                        }
                    }
                }
            } else if (parser.eventType == XmlPullParser.TEXT) {
                val text = parser.text
                if (!text.isNullOrBlank()) {
                    textBuilder.append(text)
                }
            }
        }

        // Check if cell is completely empty/blank
        if (valueType == null && textBuilder.isEmpty() && rawNum == null &&
            dateVal == null && timeVal == null && boolVal == null &&
            strVal == null && formulaAttr == null
        ) {
            return null
        }

        val formula = formulaAttr?.let { cleanFormula(it) }

        val rawValue: String
        val displayValue: String

        when (valueType?.lowercase()) {
            "float", "percentage" -> {
                rawValue = rawNum ?: textBuilder.toString()
                displayValue = if (textBuilder.isNotEmpty()) {
                    textBuilder.toString()
                } else {
                    formatNumber(rawValue, valueType.equals("percentage", ignoreCase = true))
                }
            }
            "currency" -> {
                rawValue = rawNum ?: textBuilder.toString()
                displayValue = if (textBuilder.isNotEmpty()) {
                    textBuilder.toString()
                } else {
                    "$" + formatNumber(rawValue, false)
                }
            }
            "date", "time" -> {
                rawValue = dateVal ?: timeVal ?: textBuilder.toString()
                displayValue = if (textBuilder.isNotEmpty()) textBuilder.toString() else rawValue
            }
            "boolean" -> {
                rawValue = boolVal ?: textBuilder.toString()
                displayValue = rawValue.uppercase()
            }
            "string" -> {
                rawValue = strVal ?: textBuilder.toString()
                displayValue = rawValue
            }
            else -> {
                if (formula != null) {
                    rawValue = textBuilder.toString().ifEmpty { formula }
                    displayValue = rawValue
                } else if (textBuilder.isNotEmpty()) {
                    rawValue = textBuilder.toString()
                    displayValue = rawValue
                } else {
                    rawValue = ""
                    displayValue = ""
                }
            }
        }

        val type = if (formula != null) {
            CellType.FORMULA
        } else {
            when (valueType?.lowercase()) {
                "float", "percentage" -> CellType.NUMBER
                "currency" -> CellType.NUMBER
                "date", "time" -> CellType.DATE
                "boolean" -> CellType.BOOLEAN
                "string" -> CellType.TEXT
                else -> if (textBuilder.isNotEmpty()) CellType.TEXT else CellType.BLANK
            }
        }

        val style = styleName?.let { cellStyles[it] }
        val isBold = style?.isBold ?: false
        val colorHex = style?.colorHex ?: style?.backgroundColorHex

        return XlsxCell(
            colIndex = colIndex,
            rowIndex = rowIndex,
            rawValue = rawValue,
            displayValue = displayValue,
            type = type,
            formula = formula,
            isBold = isBold,
            colorHex = colorHex
        )
    }

    private fun readTagText(parser: XmlPullParser): String {
        val depth = parser.depth
        val sb = StringBuilder()
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            when (parser.eventType) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    when (tag) {
                        "s" -> {
                            val c = (getAttr(parser, "c")?.toIntOrNull() ?: 1).coerceIn(1, 100)
                            sb.append(" ".repeat(c))
                        }
                        "tab" -> sb.append("\t")
                        "line-break" -> sb.append("\n")
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun cleanFormula(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("of:=")) s = s.removePrefix("of:")
        else if (s.startsWith("oooc:=")) s = s.removePrefix("oooc:")
        else if (!s.startsWith("=")) s = "=$s"

        // Replace [.A1] with A1 and [.A1:.A10] with A1:A10
        return s.replace("\\[\\.?([A-Za-z0-9]+):?\\.?([A-Za-z0-9]*)\\]".toRegex()) { match ->
            val first = match.groupValues[1]
            val second = match.groupValues[2]
            if (second.isNotEmpty()) "$first:$second" else first
        }
    }

    private fun formatNumber(raw: String, isPercentage: Boolean): String {
        val num = raw.toDoubleOrNull() ?: return raw
        return if (isPercentage) {
            val pct = num * 100.0
            "${decimalFormat.format(pct)}%"
        } else {
            decimalFormat.format(num)
        }
    }

    data class OdsCellStyle(
        val isBold: Boolean = false,
        val colorHex: String? = null,
        val backgroundColorHex: String? = null
    )
}
