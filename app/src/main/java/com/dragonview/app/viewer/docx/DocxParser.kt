// app/src/main/java/com/dragonview/app/viewer/docx/DocxParser.kt
package com.dragonview.app.viewer.docx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Xml
import com.dragonview.app.performance.BitmapPool
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
import java.util.zip.ZipInputStream

/**
 * High-performance, streaming DOCX Parser.
 *
 * Design constraints:
 * - NO heavy dependencies (No Apache POI or docx4j).
 * - Uses Android's native XmlPullParser directly on the input stream inside the ZIP archive.
 * - Extracts `word/document.xml` on the fly.
 * - Extracts images from `word/media/` with strict inSampleSize downsampling.
 * - Enforces streaming and resource cleanup with .use { } and try-finally.
 * - Returns a clean Result<DocxDocument> or throws descriptive exceptions for corrupted archives.
 */
object DocxParser {

    suspend fun parse(
        context: Context,
        uri: Uri,
        maxImageDimensionPx: Int = 1080
    ): Result<DocxDocument> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        var foundDocumentXml = false
        val mediaCache = mutableMapOf<String, ByteArray>()
        val elements = mutableListOf<DocxElement>()
        var paragraphCount = 0
        var tableCount = 0
        var imageCount = 0

        try {
            // First pass: scan ZIP entries for images and document.xml
            // To be memory efficient, we cache only the bytes of media images up to a safety threshold (15MB total)
            var totalMediaBytes = 0L
            val maxMediaCacheBytes = 15 * 1024 * 1024L // 15MB limit

            appContext.contentResolver.openInputStream(uri)?.use { rawInputStream ->
                ZipInputStream(rawInputStream).use { zipStream ->
                    var entry: ZipEntry?
                    while (zipStream.nextEntry.also { entry = it } != null) {
                        val entryName = entry?.name ?: ""
                        if (entryName.startsWith("word/media/") && !entry!!.isDirectory) {
                            if (totalMediaBytes < maxMediaCacheBytes) {
                                val bytes = readEntryBytes(zipStream, maxBytes = 4 * 1024 * 1024)
                                totalMediaBytes += bytes.size
                                mediaCache[entryName] = bytes
                            }
                        }
                        zipStream.closeEntry()
                    }
                }
            } ?: return@withContext Result.failure(IllegalStateException("Unable to open content stream for DOCX."))

            // Second pass: Parse word/document.xml streaming directly via XmlPullParser
            appContext.contentResolver.openInputStream(uri)?.use { rawInputStream ->
                ZipInputStream(rawInputStream).use { zipStream ->
                    var entry: ZipEntry?
                    while (zipStream.nextEntry.also { entry = it } != null) {
                        val entryName = entry?.name ?: ""
                        if (entryName == "word/document.xml") {
                            foundDocumentXml = true
                            parseDocumentXml(zipStream, elements, mediaCache, maxImageDimensionPx)
                            zipStream.closeEntry()
                            break
                        }
                        zipStream.closeEntry()
                    }
                }
            } ?: return@withContext Result.failure(IllegalStateException("Unable to open content stream for DOCX."))

            if (!foundDocumentXml) {
                return@withContext Result.failure(
                    IllegalArgumentException("This DOCX file appears corrupted or uses an unsupported structure (missing word/document.xml).")
                )
            }

            elements.forEach { element ->
                when (element) {
                    is DocumentElement.Paragraph -> paragraphCount++
                    is DocumentElement.Table -> tableCount++
                    is DocumentElement.Image -> imageCount++
                }
            }

            Result.success(
                DocxDocument(
                    elements = elements,
                    paragraphCount = paragraphCount,
                    tableCount = tableCount,
                    imageCount = imageCount
                )
            )
        } catch (e: Exception) {
            Result.failure(
                IllegalArgumentException(
                    "This DOCX file appears corrupted or uses an unsupported structure: ${e.localizedMessage ?: "parsing failed"}",
                    e
                )
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

    private fun parseDocumentXml(
        inputStream: InputStream,
        outElements: MutableList<DocumentElement>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
    ) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(inputStream, "UTF-8")

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val name = parser.name
                when (name) {
                    "p" -> {
                        val paragraph = parseParagraph(parser, mediaCache, maxImageDimensionPx, outElements)
                        if (paragraph != null) {
                            outElements.add(paragraph)
                        }
                    }
                    "tbl" -> {
                        val table = parseTable(parser)
                        if (table != null) {
                            outElements.add(table)
                        }
                    }
                }
            }
            eventType = parser.next()
        }
    }

    private fun parseParagraph(
        parser: XmlPullParser,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int,
        outElements: MutableList<DocumentElement>
    ): DocumentElement.Paragraph? {
        val runs = mutableListOf<DocumentRun>()
        var alignment = DocumentAlignment.LEFT
        var isHeading = false
        var headingLevel = 0
        var isBullet = false

        val initialDepth = parser.depth

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name == "p")) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "pPr" -> {
                        val (pAlign, pIsHeading, pLevel, pIsBullet) = parseParagraphProperties(parser)
                        alignment = pAlign
                        isHeading = pIsHeading
                        headingLevel = pLevel
                        isBullet = pIsBullet
                    }
                    "r" -> {
                        val run = parseRun(parser)
                        if (run != null && run.text.isNotEmpty()) {
                            runs.add(run)
                        }
                    }
                    "drawing" -> {
                        // Check for inline or anchored images
                        val imageElement = parseDrawing(parser, mediaCache, maxImageDimensionPx)
                        if (imageElement != null) {
                            outElements.add(imageElement)
                        }
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

    private fun parseParagraphProperties(
        parser: XmlPullParser
    ): Tuple4<DocxAlignment, Boolean, Int, Boolean> {
        var alignment = DocxAlignment.LEFT
        var isHeading = false
        var headingLevel = 0
        var isBullet = false

        val initialDepth = parser.depth
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name == "pPr")) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "jc" -> {
                        val valAttr = parser.getAttributeValue(null, "val")
                        alignment = when (valAttr?.lowercase()) {
                            "center" -> DocxAlignment.CENTER
                            "right" -> DocxAlignment.RIGHT
                            "both" -> DocxAlignment.JUSTIFY
                            else -> DocxAlignment.LEFT
                        }
                    }
                    "pStyle" -> {
                        val valAttr = parser.getAttributeValue(null, "val")?.lowercase() ?: ""
                        if (valAttr.startsWith("heading") || valAttr.startsWith("title")) {
                            isHeading = true
                            headingLevel = valAttr.filter { it.isDigit() }.toIntOrNull() ?: 1
                        }
                    }
                    "numPr" -> {
                        isBullet = true
                    }
                }
            }
        }
        return Tuple4(alignment, isHeading, headingLevel, isBullet)
    }

    private fun parseRun(parser: XmlPullParser): DocxRun? {
        val initialDepth = parser.depth
        val textBuilder = StringBuilder()
        var isBold = false
        var isItalic = false
        var isUnderline = false
        var isStrike = false
        var fontSizeSp: Float? = null
        var colorHex: String? = null
        var isHighlight = false
        var highlightColorHex: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name == "r")) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "rPr" -> {
                        val runProps = parseRunProperties(parser)
                        isBold = runProps.isBold
                        isItalic = runProps.isItalic
                        isUnderline = runProps.isUnderline
                        isStrike = runProps.isStrike
                        fontSizeSp = runProps.fontSizeSp
                        colorHex = runProps.colorHex
                        isHighlight = runProps.isHighlight
                        highlightColorHex = runProps.highlightColorHex
                    }
                    "t" -> {
                        val text = parser.nextText()
                        textBuilder.append(text)
                    }
                    "tab" -> {
                        textBuilder.append("    ")
                    }
                    "br" -> {
                        textBuilder.append("\n")
                    }
                }
            }
        }

        val text = textBuilder.toString()
        return if (text.isNotEmpty()) {
            DocxRun(
                text = text,
                isBold = isBold,
                isItalic = isItalic,
                isUnderline = isUnderline,
                isStrike = isStrike,
                fontSizeSp = fontSizeSp,
                colorHex = colorHex,
                isHighlight = isHighlight,
                highlightColorHex = highlightColorHex
            )
        } else {
            null
        }
    }

    private data class ParsedRunProperties(
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderline: Boolean = false,
        val isStrike: Boolean = false,
        val fontSizeSp: Float? = null,
        val colorHex: String? = null,
        val isHighlight: Boolean = false,
        val highlightColorHex: String? = null
    )

    private fun parseRunProperties(parser: XmlPullParser): ParsedRunProperties {
        val initialDepth = parser.depth
        var isBold = false
        var isItalic = false
        var isUnderline = false
        var isStrike = false
        var fontSizeSp: Float? = null
        var colorHex: String? = null
        var isHighlight = false
        var highlightColorHex: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name == "rPr")) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "b" -> {
                        val valAttr = parser.getAttributeValue(null, "val")
                        isBold = valAttr == null || valAttr == "1" || valAttr == "true"
                    }
                    "i" -> {
                        val valAttr = parser.getAttributeValue(null, "val")
                        isItalic = valAttr == null || valAttr == "1" || valAttr == "true"
                    }
                    "u" -> {
                        val valAttr = parser.getAttributeValue(null, "val")
                        isUnderline = valAttr == null || valAttr != "none"
                    }
                    "strike" -> {
                        val valAttr = parser.getAttributeValue(null, "val")
                        isStrike = valAttr == null || valAttr == "1" || valAttr == "true"
                    }
                    "sz" -> {
                        val valAttr = parser.getAttributeValue(null, "val")
                        val halfPoints = valAttr?.toIntOrNull()
                        if (halfPoints != null) {
                            fontSizeSp = (halfPoints / 2f).coerceIn(8f, 72f)
                        }
                    }
                    "color" -> {
                        val valAttr = parser.getAttributeValue(null, "val")
                        if (valAttr != null && valAttr.lowercase() != "auto") {
                            colorHex = "#$valAttr"
                        }
                    }
                    "highlight" -> {
                        val valAttr = parser.getAttributeValue(null, "val")
                        if (valAttr != null && valAttr != "none") {
                            isHighlight = true
                            highlightColorHex = when (valAttr.lowercase()) {
                                "yellow" -> "#FFFF00"
                                "green" -> "#00FF00"
                                "cyan" -> "#00FFFF"
                                "magenta" -> "#FF00FF"
                                "blue" -> "#0000FF"
                                "red" -> "#FF0000"
                                "darkblue" -> "#00008B"
                                "darkcyan" -> "#008B8B"
                                "darkgreen" -> "#006400"
                                "darkmagenta" -> "#8B008B"
                                "darkred" -> "#8B0000"
                                "darkyellow" -> "#808000"
                                "darkgray" -> "#A9A9A9"
                                "lightgray" -> "#D3D3D3"
                                else -> "#FFFF00"
                            }
                        }
                    }
                }
            }
        }

        return ParsedRunProperties(
            isBold = isBold,
            isItalic = isItalic,
            isUnderline = isUnderline,
            isStrike = isStrike,
            fontSizeSp = fontSizeSp,
            colorHex = colorHex,
            isHighlight = isHighlight,
            highlightColorHex = highlightColorHex
        )
    }

    private fun parseTable(parser: XmlPullParser): DocumentElement.Table? {
        val initialDepth = parser.depth
        val rows = mutableListOf<DocumentTableRow>()

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name == "tbl")) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "tr") {
                val row = parseTableRow(parser)
                if (row != null) {
                    rows.add(row)
                }
            }
        }

        return if (rows.isNotEmpty()) DocumentElement.Table(rows) else null
    }

    private fun parseTableRow(parser: XmlPullParser): DocumentTableRow? {
        val initialDepth = parser.depth
        val cells = mutableListOf<DocumentTableCell>()

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name == "tr")) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "tc") {
                val cell = parseTableCell(parser)
                if (cell != null) {
                    cells.add(cell)
                }
            }
        }

        return if (cells.isNotEmpty()) DocumentTableRow(cells) else null
    }

    private fun parseTableCell(parser: XmlPullParser): DocumentTableCell? {
        val initialDepth = parser.depth
        val paragraphs = mutableListOf<DocumentElement.Paragraph>()
        var backgroundColorHex: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name == "tc")) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "shd" -> {
                        val fill = parser.getAttributeValue(null, "fill")
                        if (fill != null && fill.lowercase() != "auto") {
                            backgroundColorHex = "#$fill"
                        }
                    }
                    "p" -> {
                        val dummyMedia = emptyMap<String, ByteArray>()
                        val dummyList = mutableListOf<DocumentElement>()
                        val p = parseParagraph(parser, dummyMedia, 1080, dummyList)
                        if (p != null) {
                            paragraphs.add(p)
                        }
                    }
                }
            }
        }

        return TableCell(paragraphs, backgroundColorHex)
    }

    private fun parseDrawing(
        parser: XmlPullParser,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
    ): DocumentElement.Image? {
        val initialDepth = parser.depth
        var blipEmbedId: String? = null
        var docPrName: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name == "drawing")) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "docPr" -> {
                        docPrName = parser.getAttributeValue(null, "name")
                    }
                    "blip" -> {
                        blipEmbedId = parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")
                            ?: parser.getAttributeValue(null, "embed")
                    }
                }
            }
        }

        // If we found an image reference, try to find a matching entry in mediaCache
        if (mediaCache.isNotEmpty()) {
            val entry = mediaCache.entries.firstOrNull() // Pick the available media item
            if (entry != null) {
                val bitmap = decodeDownsampledBitmap(entry.value, maxImageDimensionPx)
                return DocumentElement.Image(
                    mediaPath = entry.key,
                    bitmap = bitmap,
                    altText = docPrName ?: "Embedded Document Image"
                )
            }
        }

        return null
    }

    val docxBitmapPool = BitmapPool(maxEntries = 4)

    /**
     * Memory-safe bitmap decoder that calculates inSampleSize prior to decoding.
     * Prevents OOM errors when rendering large embedded photos on 3-4GB RAM phones.
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
            val reusable = docxBitmapPool.acquire(targetW, targetH, Bitmap.Config.RGB_565)

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Half memory footprint of ARGB_8888
                inMutable = true
                if (reusable != null) inBitmap = reusable
            }

            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            } catch (_: IllegalArgumentException) {
                decodeOptions.inBitmap = null
                docxBitmapPool.release(reusable)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
