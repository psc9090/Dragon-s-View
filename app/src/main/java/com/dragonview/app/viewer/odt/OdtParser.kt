// app/src/main/java/com/dragonview/app/viewer/odt/OdtParser.kt
package com.dragonview.app.viewer.odt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Xml
import com.dragonview.app.performance.BitmapPool
import com.dragonview.app.router.FileFormat
import com.dragonview.app.viewer.document.DocumentAlignment
import com.dragonview.app.viewer.document.DocumentData
import com.dragonview.app.viewer.document.DocumentElement
import com.dragonview.app.viewer.document.DocumentRun
import com.dragonview.app.viewer.document.DocumentTableCell
import com.dragonview.app.viewer.document.DocumentTableRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * High-performance streaming parser for OpenDocument Text (.odt, .ott, .fodt).
 *
 * Design constraints:
 * - Zero heavy external dependencies (no Apache POI, ODFDOM, or external JARs).
 * - Stream-based XmlPullParser extraction for memory safety.
 * - Parses styles from styles.xml and content.xml (text formatting, alignments, colors).
 * - Handles .fodt (Flat XML) directly without unzipping.
 * - Extracts and downsamples images from Pictures/ with strict inSampleSize discipline.
 * - Direct mapping into shared DocumentData / DocumentElement model.
 */
object OdtParser {

    val odtBitmapPool = BitmapPool(maxEntries = 4)

    private const val CORRUPTED_ERROR_MESSAGE =
        "This ODT file appears corrupted or uses an unsupported structure."

    suspend fun parse(
        context: Context,
        uri: Uri,
        formatHint: FileFormat? = null,
        maxImageDimensionPx: Int = 1080
    ): Result<DocumentData> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        // Determine whether this is a Flat XML file (.fodt)
        val isFlatXml = formatHint == FileFormat.ODT_FLAT ||
            uri.lastPathSegment?.lowercase()?.endsWith(".fodt") == true

        if (isFlatXml) {
            parseFlatXml(appContext, uri, maxImageDimensionPx)
        } else {
            parseZipPackage(appContext, uri, formatHint, maxImageDimensionPx)
        }
    }

    /**
     * Parses standard zipped ODF packages (.odt and .ott).
     */
    private fun parseZipPackage(
        context: Context,
        uri: Uri,
        formatHint: FileFormat?,
        maxImageDimensionPx: Int
    ): Result<DocumentData> {
        var foundContentXml = false
        val mediaCache = mutableMapOf<String, ByteArray>()
        var stylesXmlBytes: ByteArray? = null
        var contentXmlBytes: ByteArray? = null
        var totalMediaBytes = 0L
        val maxMediaCacheBytes = 15 * 1024 * 1024L // 15MB limit

        return try {
            // First pass: Read styles.xml, cache Pictures/* images, and read content.xml
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
                        val lowerName = entryName.lowercase()

                        if ((lowerName.startsWith("pictures/") || lowerName.startsWith("media/") ||
                                isImageFileName(lowerName)) && !entry.isDirectory
                        ) {
                            if (totalMediaBytes < maxMediaCacheBytes) {
                                val bytes = readEntryBytes(zipStream, maxBytes = 4 * 1024 * 1024)
                                totalMediaBytes += bytes.size
                                mediaCache[entryName] = bytes
                                // Also index by filename only for relaxed lookup
                                val simpleName = entryName.substringAfterLast('/')
                                if (simpleName.isNotEmpty()) {
                                    mediaCache[simpleName] = bytes
                                }
                            }
                        } else if (entryName == "styles.xml") {
                            stylesXmlBytes = readEntryBytes(zipStream, maxBytes = 2 * 1024 * 1024)
                        } else if (entryName == "content.xml") {
                            foundContentXml = true
                            contentXmlBytes = readEntryBytes(zipStream, maxBytes = 16 * 1024 * 1024)
                        }
                        zipStream.closeEntry()
                    }
                }
            } ?: return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))

            if (!foundContentXml || contentXmlBytes == null || contentXmlBytes.isEmpty()) {
                return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))
            }

            val textStyles = mutableMapOf<String, OdtTextStyle>()
            val paragraphStyles = mutableMapOf<String, OdtParagraphStyle>()

            // Parse styles.xml first if present
            stylesXmlBytes?.let { bytes ->
                try {
                    ByteArrayInputStream(bytes).use { inStream ->
                        parseStyles(inStream, textStyles, paragraphStyles)
                    }
                } catch (_: Exception) {
                    // Non-fatal if secondary styles fail, fallback to defaults
                }
            }

            // Parse content.xml
            val elements = mutableListOf<DocumentElement>()
            ByteArrayInputStream(contentXmlBytes).use { inStream ->
                parseOdtDocumentXml(
                    inputStream = inStream,
                    outElements = elements,
                    textStyles = textStyles,
                    paragraphStyles = paragraphStyles,
                    mediaCache = mediaCache,
                    maxImageDimensionPx = maxImageDimensionPx
                )
            }

            var paragraphCount = 0
            var tableCount = 0
            var imageCount = 0

            elements.forEach { el ->
                when (el) {
                    is DocumentElement.Paragraph -> paragraphCount++
                    is DocumentElement.Table -> tableCount++
                    is DocumentElement.Image -> imageCount++
                }
            }

            val label = if (formatHint == FileFormat.ODT_TEMPLATE) {
                "OpenDocument Template"
            } else {
                "OpenDocument Text"
            }

            Result.success(
                DocumentData(
                    elements = elements,
                    paragraphCount = paragraphCount,
                    tableCount = tableCount,
                    imageCount = imageCount,
                    formatLabel = label
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE, e))
        }
    }

    /**
     * Parses single-file Flat XML OpenDocument (.fodt).
     */
    private fun parseFlatXml(
        context: Context,
        uri: Uri,
        maxImageDimensionPx: Int
    ): Result<DocumentData> {
        return try {
            val textStyles = mutableMapOf<String, OdtTextStyle>()
            val paragraphStyles = mutableMapOf<String, OdtParagraphStyle>()
            val elements = mutableListOf<DocumentElement>()
            val mediaCache = mutableMapOf<String, ByteArray>()

            context.contentResolver.openInputStream(uri)?.use { inStream ->
                parseOdtDocumentXml(
                    inputStream = inStream,
                    outElements = elements,
                    textStyles = textStyles,
                    paragraphStyles = paragraphStyles,
                    mediaCache = mediaCache,
                    maxImageDimensionPx = maxImageDimensionPx
                )
            } ?: return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))

            var paragraphCount = 0
            var tableCount = 0
            var imageCount = 0

            elements.forEach { el ->
                when (el) {
                    is DocumentElement.Paragraph -> paragraphCount++
                    is DocumentElement.Table -> tableCount++
                    is DocumentElement.Image -> imageCount++
                }
            }

            Result.success(
                DocumentData(
                    elements = elements,
                    paragraphCount = paragraphCount,
                    tableCount = tableCount,
                    imageCount = imageCount,
                    formatLabel = "Flat OpenDocument XML"
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE, e))
        }
    }

    /**
     * Streams through an ODF XML stream (content.xml or .fodt).
     */
    private fun parseOdtDocumentXml(
        inputStream: InputStream,
        outElements: MutableList<DocumentElement>,
        textStyles: MutableMap<String, OdtTextStyle>,
        paragraphStyles: MutableMap<String, OdtParagraphStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
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
                        parseStylesBlock(parser, textStyles, paragraphStyles)
                    }
                    "p", "h" -> {
                        val isHeading = tag == "h"
                        val headingLevel = if (isHeading) {
                            getAttr(parser, "outline-level")?.toIntOrNull() ?: 1
                        } else {
                            0
                        }
                        val paragraph = parseParagraph(
                            parser = parser,
                            tag = tag,
                            isHeading = isHeading,
                            headingLevel = headingLevel,
                            textStyles = textStyles,
                            paragraphStyles = paragraphStyles,
                            mediaCache = mediaCache,
                            maxImageDimensionPx = maxImageDimensionPx,
                            outElements = outElements
                        )
                        if (paragraph != null) {
                            outElements.add(paragraph)
                        }
                    }
                    "table" -> {
                        val table = parseTable(parser, textStyles, paragraphStyles, mediaCache, maxImageDimensionPx)
                        if (table != null) {
                            outElements.add(table)
                        }
                    }
                    "image" -> {
                        val image = parseImageElement(parser, mediaCache, maxImageDimensionPx)
                        if (image != null) {
                            outElements.add(image)
                        }
                    }
                    "list" -> {
                        parseList(parser, outElements, textStyles, paragraphStyles, mediaCache, maxImageDimensionPx)
                    }
                }
            }
            eventType = parser.next()
        }
    }

    /**
     * Parses <style:style> elements within styles.xml or <office:styles>/<office:automatic-styles>.
     */
    private fun parseStyles(
        inputStream: InputStream,
        textStyles: MutableMap<String, OdtTextStyle>,
        paragraphStyles: MutableMap<String, OdtParagraphStyle>
    ) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, "UTF-8")

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                if (tag == "style") {
                    parseSingleStyle(parser, textStyles, paragraphStyles)
                }
            }
            eventType = parser.next()
        }
    }

    private fun parseStylesBlock(
        parser: XmlPullParser,
        textStyles: MutableMap<String, OdtTextStyle>,
        paragraphStyles: MutableMap<String, OdtParagraphStyle>
    ) {
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                if (tag == "style") {
                    parseSingleStyle(parser, textStyles, paragraphStyles)
                }
            }
        }
    }

    private fun parseSingleStyle(
        parser: XmlPullParser,
        textStyles: MutableMap<String, OdtTextStyle>,
        paragraphStyles: MutableMap<String, OdtParagraphStyle>
    ) {
        val styleName = getAttr(parser, "name") ?: return
        val depth = parser.depth

        var bold: Boolean? = null
        var italic: Boolean? = null
        var underline: Boolean? = null
        var strike: Boolean? = null
        var fontSize: Float? = null
        var color: String? = null
        var highlight: Boolean? = null
        var highlightColor: String? = null

        var align: DocumentAlignment? = null
        var paraBg: String? = null

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
                                "font-style", "font-style-asian", "font-style-complex" -> {
                                    if (attrVal.equals("italic", ignoreCase = true) ||
                                        attrVal.equals("oblique", ignoreCase = true)
                                    ) {
                                        italic = true
                                    }
                                }
                                "text-underline-style", "text-underline-type", "text-underline-width" -> {
                                    if (!attrVal.equals("none", ignoreCase = true)) {
                                        underline = true
                                    }
                                }
                                "text-decoration" -> {
                                    if (attrVal.contains("underline", ignoreCase = true)) {
                                        underline = true
                                    }
                                    if (attrVal.contains("line-through", ignoreCase = true)) {
                                        strike = true
                                    }
                                }
                                "text-line-through-style", "text-line-through-type" -> {
                                    if (!attrVal.equals("none", ignoreCase = true)) {
                                        strike = true
                                    }
                                }
                                "color" -> {
                                    if (attrVal.isNotBlank() && !attrVal.equals("auto", ignoreCase = true)) {
                                        color = if (attrVal.startsWith("#")) attrVal else "#$attrVal"
                                    }
                                }
                                "font-size", "font-size-asian", "font-size-complex" -> {
                                    fontSize = parseFontSize(attrVal) ?: fontSize
                                }
                                "background-color", "text-background-color" -> {
                                    if (!attrVal.equals("transparent", ignoreCase = true) &&
                                        !attrVal.equals("none", ignoreCase = true)
                                    ) {
                                        highlight = true
                                        highlightColor = if (attrVal.startsWith("#")) attrVal else "#$attrVal"
                                    }
                                }
                            }
                        }
                    }
                    "paragraph-properties" -> {
                        val textAlign = getAttr(parser, "text-align")
                        if (textAlign != null) {
                            align = when (textAlign.lowercase()) {
                                "center" -> DocumentAlignment.CENTER
                                "right", "end" -> DocumentAlignment.RIGHT
                                "justify" -> DocumentAlignment.JUSTIFY
                                else -> DocumentAlignment.LEFT
                            }
                        }
                        val bg = getAttr(parser, "background-color")
                        if (bg != null && !bg.equals("transparent", ignoreCase = true)) {
                            paraBg = if (bg.startsWith("#")) bg else "#$bg"
                        }
                    }
                }
            }
        }

        textStyles[styleName] = OdtTextStyle(
            isBold = bold ?: false,
            isItalic = italic ?: false,
            isUnderline = underline ?: false,
            isStrike = strike ?: false,
            fontSizeSp = fontSize,
            colorHex = color,
            isHighlight = highlight ?: false,
            highlightColorHex = highlightColor
        )

        if (align != null || paraBg != null) {
            paragraphStyles[styleName] = OdtParagraphStyle(
                alignment = align ?: DocumentAlignment.LEFT,
                backgroundColorHex = paraBg
            )
        }
    }

    /**
     * Parses <text:p> or <text:h> paragraph into DocumentElement.Paragraph and any inline images.
     */
    private fun parseParagraph(
        parser: XmlPullParser,
        tag: String,
        isHeading: Boolean,
        headingLevel: Int,
        textStyles: Map<String, OdtTextStyle>,
        paragraphStyles: Map<String, OdtParagraphStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int,
        outElements: MutableList<DocumentElement>,
        isBullet: Boolean = false
    ): DocumentElement.Paragraph? {
        val pStyleName = getAttr(parser, "style-name")
        val paraStyle = pStyleName?.let { paragraphStyles[it] }
        val baseTextStyle = pStyleName?.let { textStyles[it] } ?: OdtTextStyle()
        val alignment = paraStyle?.alignment ?: DocumentAlignment.LEFT

        val runs = mutableListOf<DocumentRun>()
        val depth = parser.depth

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val childTag = parser.name.substringAfter(':')
                    when (childTag) {
                        "span" -> {
                            val spanStyleName = getAttr(parser, "style-name")
                            val spanStyle = spanStyleName?.let { textStyles[it] } ?: baseTextStyle
                            val spanText = readTagText(parser)
                            if (spanText.isNotEmpty()) {
                                runs.add(
                                    DocumentRun(
                                        text = spanText,
                                        isBold = spanStyle.isBold || baseTextStyle.isBold,
                                        isItalic = spanStyle.isItalic || baseTextStyle.isItalic,
                                        isUnderline = spanStyle.isUnderline || baseTextStyle.isUnderline,
                                        isStrike = spanStyle.isStrike || baseTextStyle.isStrike,
                                        fontSizeSp = spanStyle.fontSizeSp ?: baseTextStyle.fontSizeSp,
                                        colorHex = spanStyle.colorHex ?: baseTextStyle.colorHex,
                                        isHighlight = spanStyle.isHighlight || baseTextStyle.isHighlight,
                                        highlightColorHex = spanStyle.highlightColorHex ?: baseTextStyle.highlightColorHex
                                    )
                                )
                            }
                        }
                        "s" -> {
                            val count = (getAttr(parser, "c")?.toIntOrNull() ?: 1).coerceIn(1, 100)
                            runs.add(DocumentRun(text = " ".repeat(count)))
                        }
                        "tab" -> {
                            runs.add(DocumentRun(text = "\t"))
                        }
                        "line-break" -> {
                            runs.add(DocumentRun(text = "\n"))
                        }
                        "image" -> {
                            val img = parseImageElement(parser, mediaCache, maxImageDimensionPx)
                            if (img != null) {
                                outElements.add(img)
                            }
                        }
                        "a" -> {
                            val linkText = readTagText(parser)
                            if (linkText.isNotEmpty()) {
                                runs.add(
                                    DocumentRun(
                                        text = linkText,
                                        isUnderline = true,
                                        colorHex = "#1E88E5"
                                    )
                                )
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text
                    if (!text.isNullOrEmpty()) {
                        runs.add(
                            DocumentRun(
                                text = text,
                                isBold = baseTextStyle.isBold,
                                isItalic = baseTextStyle.isItalic,
                                isUnderline = baseTextStyle.isUnderline,
                                isStrike = baseTextStyle.isStrike,
                                fontSizeSp = baseTextStyle.fontSizeSp,
                                colorHex = baseTextStyle.colorHex,
                                isHighlight = baseTextStyle.isHighlight,
                                highlightColorHex = baseTextStyle.highlightColorHex
                            )
                        )
                    }
                }
            }
        }

        return if (runs.isNotEmpty() || isHeading) {
            DocumentElement.Paragraph(
                runs = runs,
                alignment = alignment,
                isHeading = isHeading,
                headingLevel = headingLevel,
                isBullet = isBullet
            )
        } else {
            null
        }
    }

    /**
     * Reads internal text of an element (handles nested <text:s>, <text:tab>, etc.).
     */
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

    /**
     * Parses <text:list> into bullet paragraphs.
     */
    private fun parseList(
        parser: XmlPullParser,
        outElements: MutableList<DocumentElement>,
        textStyles: Map<String, OdtTextStyle>,
        paragraphStyles: Map<String, OdtParagraphStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
    ) {
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                if (tag == "list-item") {
                    parseListItem(parser, outElements, textStyles, paragraphStyles, mediaCache, maxImageDimensionPx)
                }
            }
        }
    }

    private fun parseListItem(
        parser: XmlPullParser,
        outElements: MutableList<DocumentElement>,
        textStyles: Map<String, OdtTextStyle>,
        paragraphStyles: Map<String, OdtParagraphStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
    ) {
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "p", "h" -> {
                        val p = parseParagraph(
                            parser = parser,
                            tag = tag,
                            isHeading = tag == "h",
                            headingLevel = 0,
                            textStyles = textStyles,
                            paragraphStyles = paragraphStyles,
                            mediaCache = mediaCache,
                            maxImageDimensionPx = maxImageDimensionPx,
                            outElements = outElements,
                            isBullet = true
                        )
                        if (p != null) {
                            outElements.add(p)
                        }
                    }
                    "list" -> {
                        parseList(parser, outElements, textStyles, paragraphStyles, mediaCache, maxImageDimensionPx)
                    }
                }
            }
        }
    }

    /**
     * Parses <table:table> into DocumentElement.Table.
     */
    private fun parseTable(
        parser: XmlPullParser,
        textStyles: Map<String, OdtTextStyle>,
        paragraphStyles: Map<String, OdtParagraphStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
    ): DocumentElement.Table? {
        val depth = parser.depth
        val rows = mutableListOf<DocumentTableRow>()

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "table-row", "table-header-rows" -> {
                        if (tag == "table-header-rows") {
                            // Container holding header table-row items
                            val headerDepth = parser.depth
                            while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == headerDepth)) {
                                if (parser.eventType == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "table-row") {
                                    val row = parseTableRow(parser, textStyles, paragraphStyles, mediaCache, maxImageDimensionPx)
                                    if (row != null) rows.add(row)
                                }
                            }
                        } else {
                            val row = parseTableRow(parser, textStyles, paragraphStyles, mediaCache, maxImageDimensionPx)
                            if (row != null) rows.add(row)
                        }
                    }
                }
            }
        }

        return if (rows.isNotEmpty()) DocumentElement.Table(rows) else null
    }

    private fun parseTableRow(
        parser: XmlPullParser,
        textStyles: Map<String, OdtTextStyle>,
        paragraphStyles: Map<String, OdtParagraphStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
    ): DocumentTableRow? {
        val depth = parser.depth
        val cells = mutableListOf<DocumentTableCell>()

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                if (tag == "table-cell") {
                    val repeat = (getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 16)
                    val cell = parseTableCell(parser, textStyles, paragraphStyles, mediaCache, maxImageDimensionPx)
                    if (cell != null) {
                        repeat(repeat) {
                            cells.add(cell)
                        }
                    }
                }
            }
        }

        return if (cells.isNotEmpty()) DocumentTableRow(cells) else null
    }

    private fun parseTableCell(
        parser: XmlPullParser,
        textStyles: Map<String, OdtTextStyle>,
        paragraphStyles: Map<String, OdtParagraphStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
    ): DocumentTableCell? {
        val depth = parser.depth
        val cellStyleName = getAttr(parser, "style-name")
        val cellBg = cellStyleName?.let { paragraphStyles[it]?.backgroundColorHex }

        val paragraphs = mutableListOf<DocumentElement.Paragraph>()
        val dummyElements = mutableListOf<DocumentElement>()

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                if (tag == "p" || tag == "h") {
                    val p = parseParagraph(
                        parser = parser,
                        tag = tag,
                        isHeading = tag == "h",
                        headingLevel = 0,
                        textStyles = textStyles,
                        paragraphStyles = paragraphStyles,
                        mediaCache = mediaCache,
                        maxImageDimensionPx = maxImageDimensionPx,
                        outElements = dummyElements
                    )
                    if (p != null) {
                        paragraphs.add(p)
                    }
                }
            }
        }

        return DocumentTableCell(paragraphs, cellBg)
    }

    /**
     * Parses <draw:image> and resolves embedded binary data or media path references.
     */
    private fun parseImageElement(
        parser: XmlPullParser,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
    ): DocumentElement.Image? {
        val href = getAttr(parser, "href")
        val depth = parser.depth
        var binaryData: String? = null

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                if (tag == "binary-data") {
                    binaryData = parser.nextText()
                }
            }
        }

        // 1. Direct Base64 binary data inside FODT
        if (!binaryData.isNullOrBlank()) {
            try {
                val decodedBytes = Base64.decode(binaryData.trim(), Base64.DEFAULT)
                val bmp = decodeDownsampledBitmap(decodedBytes, maxImageDimensionPx)
                if (bmp != null) {
                    return DocumentElement.Image(
                        mediaPath = href ?: "inline_binary",
                        bitmap = bmp,
                        altText = "Embedded Image"
                    )
                }
            } catch (_: Exception) {}
        }

        // 2. Referenced in ZIP media cache
        if (href != null && mediaCache.isNotEmpty()) {
            val normalizedHref = href.removePrefix("./").removePrefix("/")
            val simpleName = normalizedHref.substringAfterLast('/')

            val bytes = mediaCache[normalizedHref]
                ?: mediaCache[simpleName]
                ?: mediaCache.entries.firstOrNull { it.key.endsWith(simpleName, ignoreCase = true) }?.value

            if (bytes != null) {
                val bmp = decodeDownsampledBitmap(bytes, maxImageDimensionPx)
                return DocumentElement.Image(
                    mediaPath = href,
                    bitmap = bmp,
                    altText = "Embedded Image ($simpleName)"
                )
            }
        }

        return null
    }

    /**
     * Memory-safe downsampled bitmap decoder using inSampleSize discipline.
     */
    fun decodeDownsampledBitmap(bytes: ByteArray, maxDimensionPx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0) return null

            var inSampleSize = 1
            if (width > maxDimensionPx || height > maxDimensionPx) {
                val halfWidth = width / 2
                val halfHeight = height / 2
                while ((halfWidth / inSampleSize) >= maxDimensionPx && (halfHeight / inSampleSize) >= maxDimensionPx) {
                    inSampleSize *= 2
                }
            }

            val targetW = (width / inSampleSize).coerceAtLeast(1)
            val targetH = (height / inSampleSize).coerceAtLeast(1)
            val reusable = odtBitmapPool.acquire(targetW, targetH, Bitmap.Config.RGB_565)

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = true
                if (reusable != null) inBitmap = reusable
            }

            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            } catch (_: IllegalArgumentException) {
                decodeOptions.inBitmap = null
                odtBitmapPool.release(reusable)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            }
        } catch (_: Throwable) {
            null
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

    private fun getAttr(parser: XmlPullParser, attrLocalName: String): String? {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i).substringAfter(':')
            if (name.equals(attrLocalName, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun parseFontSize(sizeStr: String): Float? {
        val clean = sizeStr.trim().lowercase()
        return try {
            when {
                clean.endsWith("pt") -> clean.removeSuffix("pt").trim().toFloatOrNull()
                clean.endsWith("sp") -> clean.removeSuffix("sp").trim().toFloatOrNull()
                clean.endsWith("px") -> (clean.removeSuffix("px").trim().toFloatOrNull()?.times(0.75f))
                clean.endsWith("%") -> {
                    val pct = clean.removeSuffix("%").trim().toFloatOrNull() ?: 100f
                    (pct / 100f) * 14f
                }
                else -> clean.toFloatOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isImageFileName(name: String): Boolean {
        return name.endsWith(".png") || name.endsWith(".jpg") ||
            name.endsWith(".jpeg") || name.endsWith(".gif") ||
            name.endsWith(".webp") || name.endsWith(".bmp")
    }

    private data class OdtTextStyle(
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderline: Boolean = false,
        val isStrike: Boolean = false,
        val fontSizeSp: Float? = null,
        val colorHex: String? = null,
        val isHighlight: Boolean = false,
        val highlightColorHex: String? = null
    )

    private data class OdtParagraphStyle(
        val alignment: DocumentAlignment = DocumentAlignment.LEFT,
        val backgroundColorHex: String? = null
    )
}
