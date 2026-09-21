// app/src/main/java/com/dragonview/app/viewer/odg/OdgParser.kt
package com.dragonview.app.viewer.odg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.util.Xml
import com.dragonview.app.router.FileFormat
import com.dragonview.app.viewer.odp.OdpParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * OpenDocument Drawing (.odg, .otg, .fodg) Structured Model.
 */
data class OdgDocument(
    val title: String,
    val pageWidthPx: Float,
    val pageHeightPx: Float,
    val elements: List<DrawingElement>
)

sealed class DrawingElement {
    abstract val transform: Matrix?

    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val cornerRadius: Float = 0f,
        val fill: OdgFill? = null,
        val stroke: OdgStroke? = null,
        val text: OdgTextBox? = null,
        override val transform: Matrix? = null
    ) : DrawingElement()

    data class Ellipse(
        val cx: Float,
        val cy: Float,
        val rx: Float,
        val ry: Float,
        val fill: OdgFill? = null,
        val stroke: OdgStroke? = null,
        val text: OdgTextBox? = null,
        override val transform: Matrix? = null
    ) : DrawingElement()

    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val stroke: OdgStroke? = null,
        override val transform: Matrix? = null
    ) : DrawingElement()

    data class Polygon(
        val points: List<PointF>,
        val isClosed: Boolean = true,
        val fill: OdgFill? = null,
        val stroke: OdgStroke? = null,
        val text: OdgTextBox? = null,
        override val transform: Matrix? = null
    ) : DrawingElement()

    data class Path(
        val svgPathData: String,
        val x: Float = 0f,
        val y: Float = 0f,
        val width: Float = 0f,
        val height: Float = 0f,
        val viewBox: RectF? = null,
        val fill: OdgFill? = null,
        val stroke: OdgStroke? = null,
        val text: OdgTextBox? = null,
        override val transform: Matrix? = null
    ) : DrawingElement()

    data class TextBox(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val paragraphs: List<OdgParagraph>,
        val fill: OdgFill? = null,
        val stroke: OdgStroke? = null,
        override val transform: Matrix? = null
    ) : DrawingElement()

    data class Image(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val bitmap: Bitmap?,
        val altText: String? = null,
        override val transform: Matrix? = null
    ) : DrawingElement()

    data class Group(
        val children: List<DrawingElement>,
        override val transform: Matrix? = null
    ) : DrawingElement()
}

data class OdgFill(
    val colorHex: String,
    val opacity: Float = 1.0f
)

data class OdgStroke(
    val colorHex: String,
    val widthPx: Float = 1.0f,
    val opacity: Float = 1.0f,
    val isDashed: Boolean = false
)

data class OdgTextBox(
    val paragraphs: List<OdgParagraph>
)

data class OdgParagraph(
    val runs: List<OdgRun>,
    val alignment: OdgAlignment = OdgAlignment.LEFT
)

data class OdgRun(
    val text: String,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val fontSizePt: Float = 12f,
    val colorHex: String = "#FDE8EA"
)

enum class OdgAlignment {
    LEFT, CENTER, RIGHT, JUSTIFY
}

/**
 * High-performance streaming OpenDocument Drawing (.odg, .otg, .fodg) Parser.
 *
 * Extracts shapes from <draw:page> elements:
 * - <draw:rect>, <draw:ellipse>, <draw:circle>, <draw:line>
 * - <draw:polygon>, <draw:polyline>, <draw:path>, <draw:custom-shape>
 * - <draw:text-box>, <draw:image>, <draw:frame>
 * - <draw:g> (nested groups with transforms)
 *
 * Reuses SVG real unit conversion logic from OdpParser (cm, mm, in, pt, px).
 * Resolves styling from styles.xml and automatic-styles.
 */
object OdgParser {

    private const val TAG = "OdgParser"
    const val CORRUPTED_ERROR_MESSAGE = "This ODG file appears corrupted or uses an unsupported structure."

    // Standard A4 portrait drawing dimensions: 21cm x 29.7cm @ 96 DPI (~794px x 1123px)
    private const val DEFAULT_PAGE_WIDTH_PX = 794f
    private const val DEFAULT_PAGE_HEIGHT_PX = 1123f

    suspend fun parse(
        context: Context,
        uri: Uri,
        formatHint: FileFormat? = null,
        maxImageDimensionPx: Int = 1080
    ): Result<OdgDocument> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val isFlatXml = formatHint == FileFormat.ODG_FLAT ||
            uri.lastPathSegment?.lowercase()?.endsWith(".fodg") == true

        if (isFlatXml) {
            parseFlatXml(appContext, uri, maxImageDimensionPx)
        } else {
            parseZipPackage(appContext, uri, maxImageDimensionPx)
        }
    }

    /**
     * Parses standard ZIP OpenDocument drawing (.odg, .otg).
     */
    private fun parseZipPackage(
        context: Context,
        uri: Uri,
        maxImageDimensionPx: Int
    ): Result<OdgDocument> {
        return try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri)
                ?: return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))

            var contentXmlBytes: ByteArray? = null
            var stylesXmlBytes: ByteArray? = null
            val mediaCache = mutableMapOf<String, ByteArray>()

            ZipInputStream(inputStream).use { zip ->
                var entry: ZipEntry?
                while (zip.nextEntry.also { entry = it } != null) {
                    val entryName = entry!!.name
                    val lowerName = entryName.lowercase()

                    when {
                        lowerName == "content.xml" -> {
                            contentXmlBytes = readEntryBytes(zip, maxBytes = 15 * 1024 * 1024)
                        }
                        lowerName == "styles.xml" -> {
                            stylesXmlBytes = readEntryBytes(zip, maxBytes = 5 * 1024 * 1024)
                        }
                        lowerName.startsWith("pictures/") || isImageFileName(lowerName) -> {
                            val cleanKey = entryName.removePrefix("./").lowercase()
                            val shortKey = entryName.substringAfterLast('/').lowercase()
                            val imageBytes = readEntryBytes(zip, maxBytes = 10 * 1024 * 1024)
                            mediaCache[cleanKey] = imageBytes
                            mediaCache[shortKey] = imageBytes
                        }
                    }
                    zip.closeEntry()
                }
            }

            if (contentXmlBytes == null) {
                return Result.failure(IllegalStateException(CORRUPTED_ERROR_MESSAGE))
            }

            val styles = mutableMapOf<String, OdgStyle>()
            stylesXmlBytes?.let { parseStylesXml(it, styles) }

            parseContentXml(
                contentBytes = contentXmlBytes!!,
                styles = styles,
                mediaCache = mediaCache,
                maxImageDimensionPx = maxImageDimensionPx,
                title = uri.lastPathSegment?.substringAfterLast('/') ?: "Drawing"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ODG ZIP package: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Parses Flat XML OpenDocument drawing (.fodg).
     */
    private fun parseFlatXml(
        context: Context,
        uri: Uri,
        maxImageDimensionPx: Int
    ): Result<OdgDocument> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))

            val bytes = inputStream.use { it.readBytes() }
            val styles = mutableMapOf<String, OdgStyle>()
            val mediaCache = mutableMapOf<String, ByteArray>()

            parseContentXml(
                contentBytes = bytes,
                styles = styles,
                mediaCache = mediaCache,
                maxImageDimensionPx = maxImageDimensionPx,
                title = uri.lastPathSegment?.substringAfterLast('/') ?: "Flat Drawing"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Flat ODG XML: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Parses content.xml (or flat XML) to extract page dimensions, styles, and shapes.
     */
    fun parseContentXml(
        contentBytes: ByteArray,
        styles: MutableMap<String, OdgStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int,
        title: String
    ): Result<OdgDocument> {
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(contentBytes), "UTF-8")

            var pageWidth = DEFAULT_PAGE_WIDTH_PX
            var pageHeight = DEFAULT_PAGE_HEIGHT_PX
            val elements = mutableListOf<DrawingElement>()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tag = parser.name.substringAfter(':')
                    when (tag) {
                        "style" -> {
                            parseSingleStyle(parser, styles)
                        }
                        "page", "drawing-page" -> {
                            val w = getAttr(parser, "width")
                            val h = getAttr(parser, "height")
                            if (w != null) pageWidth = unitToPx(w)
                            if (h != null) pageHeight = unitToPx(h)

                            parseDrawingPage(
                                parser = parser,
                                styles = styles,
                                mediaCache = mediaCache,
                                maxImageDimensionPx = maxImageDimensionPx,
                                outElements = elements
                            )
                        }
                        "page-layout-properties" -> {
                            val pw = getAttr(parser, "page-width")
                            val ph = getAttr(parser, "page-height")
                            if (pw != null) pageWidth = unitToPx(pw)
                            if (ph != null) pageHeight = unitToPx(ph)
                        }
                    }
                }
                eventType = parser.next()
            }

            if (elements.isEmpty()) {
                // Check if any drawing content was present at all
                Log.w(TAG, "No drawing elements found in ODG content")
                return Result.failure(IllegalStateException(CORRUPTED_ERROR_MESSAGE))
            }

            Result.success(
                OdgDocument(
                    title = title,
                    pageWidthPx = if (pageWidth > 50f) pageWidth else DEFAULT_PAGE_WIDTH_PX,
                    pageHeightPx = if (pageHeight > 50f) pageHeight else DEFAULT_PAGE_HEIGHT_PX,
                    elements = elements
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ODG content XML: ${e.message}", e)
            Result.failure(IllegalStateException(CORRUPTED_ERROR_MESSAGE, e))
        }
    }

    /**
     * Parses the <draw:page> child shapes.
     */
    private fun parseDrawingPage(
        parser: XmlPullParser,
        styles: Map<String, OdgStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int,
        outElements: MutableList<DrawingElement>
    ) {
        val pageDepth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == pageDepth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                try {
                    val element = parseShape(parser, styles, mediaCache, maxImageDimensionPx)
                    if (element != null) {
                        outElements.add(element)
                    }
                } catch (e: Exception) {
                    // Skip unsupported or corrupted shape without crashing entire drawing
                    Log.w(TAG, "Skipping malformed ODG shape '${parser.name}': ${e.message}")
                    skipSubtree(parser)
                }
            }
        }
    }

    /**
     * Parses a single shape element or group.
     */
    private fun parseShape(
        parser: XmlPullParser,
        styles: Map<String, OdgStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
    ): DrawingElement? {
        val tag = parser.name.substringAfter(':')
        val styleName = getAttr(parser, "style-name")
        val shapeStyle = styleName?.let { styles[it] }

        val transform = parseTransform(getAttr(parser, "transform"))

        return when (tag) {
            "g" -> parseGroup(parser, styles, mediaCache, maxImageDimensionPx, transform)
            "rect" -> parseRect(parser, shapeStyle, transform)
            "ellipse", "circle" -> parseEllipse(parser, shapeStyle, transform)
            "line" -> parseLine(parser, shapeStyle, transform)
            "polygon" -> parsePolygon(parser, shapeStyle, transform, isClosed = true)
            "polyline" -> parsePolygon(parser, shapeStyle, transform, isClosed = false)
            "path" -> parsePath(parser, shapeStyle, transform)
            "custom-shape" -> parseCustomShape(parser, shapeStyle, transform)
            "text-box" -> parseTextBoxShape(parser, shapeStyle, transform)
            "frame" -> parseFrame(parser, styles, mediaCache, maxImageDimensionPx, shapeStyle, transform)
            "image" -> parseDirectImage(parser, mediaCache, maxImageDimensionPx, transform)
            else -> {
                skipSubtree(parser)
                null
            }
        }
    }

    private fun parseGroup(
        parser: XmlPullParser,
        styles: Map<String, OdgStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int,
        groupTransform: Matrix?
    ): DrawingElement.Group {
        val groupDepth = parser.depth
        val children = mutableListOf<DrawingElement>()

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == groupDepth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                try {
                    val child = parseShape(parser, styles, mediaCache, maxImageDimensionPx)
                    if (child != null) {
                        children.add(child)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping malformed group child '${parser.name}': ${e.message}")
                    skipSubtree(parser)
                }
            }
        }

        return DrawingElement.Group(children = children, transform = groupTransform)
    }

    private fun parseRect(
        parser: XmlPullParser,
        style: OdgStyle?,
        transform: Matrix?
    ): DrawingElement.Rect {
        val x = unitToPx(getAttr(parser, "x"))
        val y = unitToPx(getAttr(parser, "y"))
        val w = unitToPx(getAttr(parser, "width"))
        val h = unitToPx(getAttr(parser, "height"))
        val cornerRadius = unitToPx(getAttr(parser, "corner-radius"))

        val fill = resolveFill(parser, style)
        val stroke = resolveStroke(parser, style)
        val textBox = parseInnerTextBox(parser, style)

        return DrawingElement.Rect(
            x = x,
            y = y,
            width = w,
            height = h,
            cornerRadius = cornerRadius,
            fill = fill,
            stroke = stroke,
            text = textBox,
            transform = transform
        )
    }

    private fun parseEllipse(
        parser: XmlPullParser,
        style: OdgStyle?,
        transform: Matrix?
    ): DrawingElement.Ellipse {
        val cxAttr = getAttr(parser, "cx")
        val cyAttr = getAttr(parser, "cy")
        val rxAttr = getAttr(parser, "rx")
        val ryAttr = getAttr(parser, "ry")

        val (cx, cy, rx, ry) = if (cxAttr != null && cyAttr != null) {
            val cxVal = unitToPx(cxAttr)
            val cyVal = unitToPx(cyAttr)
            val rVal = unitToPx(rxAttr ?: ryAttr ?: "1cm")
            val rxVal = unitToPx(rxAttr)
            val ryVal = unitToPx(ryAttr)
            Quad(cxVal, cyVal, if (rxVal > 0) rxVal else rVal, if (ryVal > 0) ryVal else rVal)
        } else {
            val x = unitToPx(getAttr(parser, "x"))
            val y = unitToPx(getAttr(parser, "y"))
            val w = unitToPx(getAttr(parser, "width"))
            val h = unitToPx(getAttr(parser, "height"))
            Quad(x + w / 2f, y + h / 2f, w / 2f, h / 2f)
        }

        val fill = resolveFill(parser, style)
        val stroke = resolveStroke(parser, style)
        val textBox = parseInnerTextBox(parser, style)

        return DrawingElement.Ellipse(
            cx = cx,
            cy = cy,
            rx = rx,
            ry = ry,
            fill = fill,
            stroke = stroke,
            text = textBox,
            transform = transform
        )
    }

    private fun parseLine(
        parser: XmlPullParser,
        style: OdgStyle?,
        transform: Matrix?
    ): DrawingElement.Line {
        val x1 = unitToPx(getAttr(parser, "x1") ?: getAttr(parser, "x"))
        val y1 = unitToPx(getAttr(parser, "y1") ?: getAttr(parser, "y"))
        val x2 = unitToPx(getAttr(parser, "x2") ?: (getAttr(parser, "width")?.let { "${unitToPx(it) + x1}px" }))
        val y2 = unitToPx(getAttr(parser, "y2") ?: (getAttr(parser, "height")?.let { "${unitToPx(it) + y1}px" }))

        val stroke = resolveStroke(parser, style)
        skipSubtree(parser)

        return DrawingElement.Line(
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            stroke = stroke ?: OdgStroke(colorHex = "#000000", widthPx = 2f),
            transform = transform
        )
    }

    private fun parsePolygon(
        parser: XmlPullParser,
        style: OdgStyle?,
        transform: Matrix?,
        isClosed: Boolean
    ): DrawingElement.Polygon {
        val pointsAttr = getAttr(parser, "points") ?: ""
        val offsetX = unitToPx(getAttr(parser, "x"))
        val offsetY = unitToPx(getAttr(parser, "y"))

        val points = mutableListOf<PointF>()
        val pointTokens = pointsAttr.trim().split(Regex("\\s+"))
        for (token in pointTokens) {
            val parts = token.split(',')
            if (parts.size == 2) {
                val px = unitToPx(parts[0]) + offsetX
                val py = unitToPx(parts[1]) + offsetY
                points.add(PointF(px, py))
            }
        }

        val fill = if (isClosed) resolveFill(parser, style) else null
        val stroke = resolveStroke(parser, style)
        val textBox = parseInnerTextBox(parser, style)

        return DrawingElement.Polygon(
            points = points,
            isClosed = isClosed,
            fill = fill,
            stroke = stroke,
            text = textBox,
            transform = transform
        )
    }

    private fun parsePath(
        parser: XmlPullParser,
        style: OdgStyle?,
        transform: Matrix?
    ): DrawingElement.Path {
        val pathData = getAttr(parser, "d") ?: getAttr(parser, "path") ?: ""
        val x = unitToPx(getAttr(parser, "x"))
        val y = unitToPx(getAttr(parser, "y"))
        val w = unitToPx(getAttr(parser, "width"))
        val h = unitToPx(getAttr(parser, "height"))

        val viewBoxAttr = getAttr(parser, "viewBox")
        val viewBox = viewBoxAttr?.let { parseViewBox(it) }

        val fill = resolveFill(parser, style)
        val stroke = resolveStroke(parser, style)
        val textBox = parseInnerTextBox(parser, style)

        return DrawingElement.Path(
            svgPathData = pathData,
            x = x,
            y = y,
            width = w,
            height = h,
            viewBox = viewBox,
            fill = fill,
            stroke = stroke,
            text = textBox,
            transform = transform
        )
    }

    private fun parseCustomShape(
        parser: XmlPullParser,
        style: OdgStyle?,
        transform: Matrix?
    ): DrawingElement? {
        val x = unitToPx(getAttr(parser, "x"))
        val y = unitToPx(getAttr(parser, "y"))
        val w = unitToPx(getAttr(parser, "width"))
        val h = unitToPx(getAttr(parser, "height"))

        var enhancedPath: String? = null
        var innerTextBox: OdgTextBox? = null
        val depth = parser.depth

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "enhanced-geometry" -> {
                        enhancedPath = getAttr(parser, "enhanced-path")
                    }
                    "p", "text-box" -> {
                        innerTextBox = parseParagraphsSubtree(parser, style)
                    }
                }
            }
        }

        val fill = resolveFill(parser, style)
        val stroke = resolveStroke(parser, style)

        return if (!enhancedPath.isNullOrBlank()) {
            // Enhanced paths in ODF can be converted to standard SVG paths
            val svgPath = convertEnhancedPathToSvg(enhancedPath)
            DrawingElement.Path(
                svgPathData = svgPath,
                x = x,
                y = y,
                width = w,
                height = h,
                fill = fill,
                stroke = stroke,
                text = innerTextBox,
                transform = transform
            )
        } else {
            // Fallback to bounding rectangle
            DrawingElement.Rect(
                x = x,
                y = y,
                width = w,
                height = h,
                cornerRadius = 0f,
                fill = fill,
                stroke = stroke,
                text = innerTextBox,
                transform = transform
            )
        }
    }

    private fun parseTextBoxShape(
        parser: XmlPullParser,
        style: OdgStyle?,
        transform: Matrix?
    ): DrawingElement.TextBox {
        val x = unitToPx(getAttr(parser, "x"))
        val y = unitToPx(getAttr(parser, "y"))
        val w = unitToPx(getAttr(parser, "width"))
        val h = unitToPx(getAttr(parser, "height"))

        val fill = resolveFill(parser, style)
        val stroke = resolveStroke(parser, style)
        val textBox = parseInnerTextBox(parser, style)

        return DrawingElement.TextBox(
            x = x,
            y = y,
            width = w,
            height = h,
            paragraphs = textBox?.paragraphs ?: emptyList(),
            fill = fill,
            stroke = stroke,
            transform = transform
        )
    }

    private fun parseFrame(
        parser: XmlPullParser,
        styles: Map<String, OdgStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int,
        frameStyle: OdgStyle?,
        transform: Matrix?
    ): DrawingElement? {
        val x = unitToPx(getAttr(parser, "x"))
        val y = unitToPx(getAttr(parser, "y"))
        val w = unitToPx(getAttr(parser, "width"))
        val h = unitToPx(getAttr(parser, "height"))

        val frameDepth = parser.depth
        var resultElement: DrawingElement? = null

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == frameDepth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "image" -> {
                        resultElement = parseDirectImageAt(
                            parser = parser,
                            x = x,
                            y = y,
                            width = w,
                            height = h,
                            mediaCache = mediaCache,
                            maxImageDimensionPx = maxImageDimensionPx,
                            transform = transform
                        )
                    }
                    "text-box" -> {
                        val textBox = parseInnerTextBox(parser, frameStyle)
                        resultElement = DrawingElement.TextBox(
                            x = x,
                            y = y,
                            width = w,
                            height = h,
                            paragraphs = textBox?.paragraphs ?: emptyList(),
                            fill = resolveFill(parser, frameStyle),
                            stroke = resolveStroke(parser, frameStyle),
                            transform = transform
                        )
                    }
                    else -> skipSubtree(parser)
                }
            }
        }

        return resultElement
    }

    private fun parseDirectImage(
        parser: XmlPullParser,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int,
        transform: Matrix?
    ): DrawingElement.Image {
        val x = unitToPx(getAttr(parser, "x"))
        val y = unitToPx(getAttr(parser, "y"))
        val w = unitToPx(getAttr(parser, "width"))
        val h = unitToPx(getAttr(parser, "height"))
        return parseDirectImageAt(parser, x, y, w, h, mediaCache, maxImageDimensionPx, transform)
    }

    private fun parseDirectImageAt(
        parser: XmlPullParser,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int,
        transform: Matrix?
    ): DrawingElement.Image {
        val href = getAttr(parser, "href")
        val depth = parser.depth
        var binaryBase64: String? = null

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                if (tag == "binary-data") {
                    binaryBase64 = readTagText(parser)
                }
            }
        }

        val bitmap: Bitmap? = if (binaryBase64 != null) {
            decodeBase64Image(binaryBase64, maxImageDimensionPx)
        } else if (href != null) {
            val cleanHref = href.removePrefix("./").lowercase()
            val shortHref = href.substringAfterLast('/').lowercase()
            val bytes = mediaCache[cleanHref] ?: mediaCache[shortHref]
            bytes?.let { decodeDownsampledBitmap(it, maxImageDimensionPx) }
        } else {
            null
        }

        return DrawingElement.Image(
            x = x,
            y = y,
            width = width,
            height = height,
            bitmap = bitmap,
            altText = href?.substringAfterLast('/'),
            transform = transform
        )
    }

    /**
     * Parses text inside shapes (<text:p>, <text:span>, etc.).
     */
    private fun parseInnerTextBox(
        parser: XmlPullParser,
        inheritedStyle: OdgStyle?
    ): OdgTextBox? {
        val depth = parser.depth
        val paragraphs = mutableListOf<OdgParagraph>()

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "p" -> {
                        val p = parseParagraph(parser, inheritedStyle)
                        if (p != null) paragraphs.add(p)
                    }
                    "text-box" -> {
                        val sub = parseInnerTextBox(parser, inheritedStyle)
                        if (sub != null) paragraphs.addAll(sub.paragraphs)
                    }
                    else -> skipSubtree(parser)
                }
            }
        }

        return if (paragraphs.isNotEmpty()) OdgTextBox(paragraphs) else null
    }

    private fun parseParagraphsSubtree(
        parser: XmlPullParser,
        inheritedStyle: OdgStyle?
    ): OdgTextBox? {
        val depth = parser.depth
        val paragraphs = mutableListOf<OdgParagraph>()
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.name.substringAfter(':') == "p") {
                    parseParagraph(parser, inheritedStyle)?.let { paragraphs.add(it) }
                } else {
                    skipSubtree(parser)
                }
            }
        }
        return if (paragraphs.isNotEmpty()) OdgTextBox(paragraphs) else null
    }

    private fun parseParagraph(
        parser: XmlPullParser,
        inheritedStyle: OdgStyle?
    ): OdgParagraph? {
        val depth = parser.depth
        val runs = mutableListOf<OdgRun>()

        val baseBold = inheritedStyle?.isBold ?: false
        val baseItalic = inheritedStyle?.isItalic ?: false
        val baseFontSize = inheritedStyle?.fontSizePt ?: 12f
        val baseColor = inheritedStyle?.colorHex ?: "#FDE8EA"
        val alignment = inheritedStyle?.alignment ?: OdgAlignment.LEFT

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            when (parser.eventType) {
                XmlPullParser.TEXT -> {
                    val t = parser.text
                    if (!t.isNullOrEmpty()) {
                        runs.add(
                            OdgRun(
                                text = t,
                                isBold = baseBold,
                                isItalic = baseItalic,
                                fontSizePt = baseFontSize,
                                colorHex = baseColor
                            )
                        )
                    }
                }
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    when (tag) {
                        "span" -> {
                            val text = readTagText(parser)
                            if (text.isNotEmpty()) {
                                runs.add(
                                    OdgRun(
                                        text = text,
                                        isBold = baseBold,
                                        isItalic = baseItalic,
                                        fontSizePt = baseFontSize,
                                        colorHex = baseColor
                                    )
                                )
                            }
                        }
                        "s" -> {
                            val count = getAttr(parser, "c")?.toIntOrNull() ?: 1
                            runs.add(OdgRun(text = " ".repeat(count.coerceIn(1, 50))))
                        }
                        "tab" -> runs.add(OdgRun(text = "\t"))
                        "line-break" -> runs.add(OdgRun(text = "\n"))
                        else -> skipSubtree(parser)
                    }
                }
            }
        }

        return if (runs.isNotEmpty()) OdgParagraph(runs = runs, alignment = alignment) else null
    }

    // -------------------------------------------------------------------------
    // Style Resolution
    // -------------------------------------------------------------------------

    private fun parseStylesXml(bytes: ByteArray, styles: MutableMap<String, OdgStyle>) {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "style") {
                    parseSingleStyle(parser, styles)
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing styles.xml: ${e.message}")
        }
    }

    private fun parseSingleStyle(parser: XmlPullParser, styles: MutableMap<String, OdgStyle>) {
        val styleName = getAttr(parser, "name") ?: return
        val depth = parser.depth

        var isBold: Boolean? = null
        var isItalic: Boolean? = null
        var fontSizePt: Float? = null
        var colorHex: String? = null
        var alignment: OdgAlignment? = null
        var fillColorHex: String? = null
        var fillOpacity: Float? = null
        var hasFill: Boolean? = null
        var strokeColorHex: String? = null
        var strokeWidthPx: Float? = null
        var strokeOpacity: Float? = null
        var hasStroke: Boolean? = null
        var isDashed: Boolean? = null

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "graphic-properties" -> {
                        val fillAttr = getAttr(parser, "fill")
                        if (fillAttr == "none") {
                            hasFill = false
                        } else if (fillAttr == "solid" || fillAttr == "color") {
                            hasFill = true
                        }

                        val fillCol = getAttr(parser, "fill-color")
                        if (fillCol != null) {
                            fillColorHex = parseColorHex(fillCol)
                            if (hasFill == null) hasFill = true
                        }

                        val op = getAttr(parser, "opacity")
                        if (op != null) {
                            fillOpacity = parseOpacity(op)
                            strokeOpacity = fillOpacity
                        }

                        val strokeAttr = getAttr(parser, "stroke")
                        if (strokeAttr == "none") {
                            hasStroke = false
                        } else if (strokeAttr == "solid" || strokeAttr == "dash") {
                            hasStroke = true
                            if (strokeAttr == "dash") isDashed = true
                        }

                        val strokeCol = getAttr(parser, "stroke-color")
                        if (strokeCol != null) {
                            strokeColorHex = parseColorHex(strokeCol)
                            if (hasStroke == null) hasStroke = true
                        }

                        val strokeW = getAttr(parser, "stroke-width")
                        if (strokeW != null) {
                            strokeWidthPx = unitToPx(strokeW)
                        }
                    }
                    "text-properties" -> {
                        val weight = getAttr(parser, "font-weight")
                        if (weight == "bold" || weight?.toIntOrNull()?.let { it >= 700 } == true) isBold = true
                        val style = getAttr(parser, "font-style")
                        if (style == "italic" || style == "oblique") isItalic = true
                        val col = getAttr(parser, "color")
                        if (col != null) colorHex = parseColorHex(col)
                        val size = getAttr(parser, "font-size")
                        if (size != null) fontSizePt = parseFontSizePt(size)
                    }
                    "paragraph-properties" -> {
                        val align = getAttr(parser, "text-align")
                        if (align != null) {
                            alignment = when (align.lowercase()) {
                                "center" -> OdgAlignment.CENTER
                                "end", "right" -> OdgAlignment.RIGHT
                                "justify" -> OdgAlignment.JUSTIFY
                                else -> OdgAlignment.LEFT
                            }
                        }
                    }
                    else -> skipSubtree(parser)
                }
            }
        }

        styles[styleName] = OdgStyle(
            isBold = isBold,
            isItalic = isItalic,
            fontSizePt = fontSizePt,
            colorHex = colorHex,
            alignment = alignment,
            fillColorHex = fillColorHex,
            fillOpacity = fillOpacity ?: 1f,
            hasFill = hasFill,
            strokeColorHex = strokeColorHex,
            strokeWidthPx = strokeWidthPx ?: 1f,
            strokeOpacity = strokeOpacity ?: 1f,
            hasStroke = hasStroke,
            isDashed = isDashed ?: false
        )
    }

    private fun resolveFill(parser: XmlPullParser, style: OdgStyle?): OdgFill? {
        val directFill = getAttr(parser, "fill")
        val directColor = getAttr(parser, "fill-color")?.let { parseColorHex(it) }
        val directOpacity = getAttr(parser, "opacity")?.let { parseOpacity(it) }

        if (directFill == "none") return null
        if (directColor != null) {
            return OdgFill(colorHex = directColor, opacity = directOpacity ?: style?.fillOpacity ?: 1f)
        }

        if (style?.hasFill == false) return null
        if (style?.fillColorHex != null) {
            return OdgFill(colorHex = style.fillColorHex, opacity = style.fillOpacity)
        }

        // Default shape fill in ODG is subtle themed fill
        return OdgFill(colorHex = "#2E1218", opacity = 1.0f)
    }

    private fun resolveStroke(parser: XmlPullParser, style: OdgStyle?): OdgStroke? {
        val directStroke = getAttr(parser, "stroke")
        val directColor = getAttr(parser, "stroke-color")?.let { parseColorHex(it) }
        val directWidth = getAttr(parser, "stroke-width")?.let { unitToPx(it) }
        val directOpacity = getAttr(parser, "opacity")?.let { parseOpacity(it) }

        if (directStroke == "none") return null
        if (directColor != null) {
            return OdgStroke(
                colorHex = directColor,
                widthPx = directWidth ?: style?.strokeWidthPx ?: 1f,
                opacity = directOpacity ?: style?.strokeOpacity ?: 1f,
                isDashed = directStroke == "dash" || style?.isDashed == true
            )
        }

        if (style?.hasStroke == false) return null
        if (style?.strokeColorHex != null) {
            return OdgStroke(
                colorHex = style.strokeColorHex,
                widthPx = style.strokeWidthPx,
                opacity = style.strokeOpacity,
                isDashed = style.isDashed
            )
        }

        return OdgStroke(colorHex = "#D32F2F", widthPx = 1.5f, opacity = 1.0f)
    }

    /**
     * Parses SVG or ODF transform attribute (e.g., matrix(...), rotate(...), translate(...)).
     */
    fun parseTransform(transformStr: String?): Matrix? {
        if (transformStr.isNullOrBlank()) return null
        val matrix = Matrix()
        var hasTransform = false

        val regex = Regex("([a-zA-Z]+)\\s*\\(([^)]+)\\)")
        val matches = regex.findAll(transformStr)

        for (match in matches) {
            val cmd = match.groupValues[1].lowercase()
            val args = match.groupValues[2].split(Regex("[,\\s]+")).mapNotNull { it.trim().toDoubleOrNull() }

            when (cmd) {
                "matrix" -> {
                    if (args.size >= 6) {
                        // SVG matrix: [a, c, e; b, d, f; 0, 0, 1]
                        // e and f are x and y translations
                        val a = args[0].toFloat()
                        val b = args[1].toFloat()
                        val c = args[2].toFloat()
                        val d = args[3].toFloat()
                        val e = args[4].toFloat()
                        val f = args[5].toFloat()
                        val temp = Matrix().apply {
                            setValues(floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f))
                        }
                        matrix.postConcat(temp)
                        hasTransform = true
                    }
                }
                "translate" -> {
                    if (args.isNotEmpty()) {
                        val tx = args[0].toFloat()
                        val ty = if (args.size > 1) args[1].toFloat() else 0f
                        matrix.postTranslate(tx, ty)
                        hasTransform = true
                    }
                }
                "rotate" -> {
                    if (args.isNotEmpty()) {
                        val angle = args[0].toFloat()
                        if (args.size >= 3) {
                            matrix.postRotate(angle, args[1].toFloat(), args[2].toFloat())
                        } else {
                            matrix.postRotate(angle)
                        }
                        hasTransform = true
                    }
                }
                "scale" -> {
                    if (args.isNotEmpty()) {
                        val sx = args[0].toFloat()
                        val sy = if (args.size > 1) args[1].toFloat() else sx
                        matrix.postScale(sx, sy)
                        hasTransform = true
                    }
                }
            }
        }

        return if (hasTransform) matrix else null
    }

    /**
     * Converts SVG length strings (e.g. "2.5cm", "10in", "72pt", "100mm", "1920px") to display pixels at 96 DPI.
     * Reuses OdpParser.parseLengthToEmu: 9525 EMUs = 1 px @ 96 DPI.
     */
    fun unitToPx(value: String?): Float {
        if (value.isNullOrBlank()) return 0f
        val emu = OdpParser.parseLengthToEmu(value)
        return emu.toFloat() / 9525f
    }

    private fun parseViewBox(value: String): RectF? {
        val parts = value.trim().split(Regex("[,\\s]+")).mapNotNull { it.toFloatOrNull() }
        if (parts.size == 4) {
            return RectF(parts[0], parts[1], parts[0] + parts[2], parts[1] + parts[3])
        }
        return null
    }

    /**
     * Converts OpenDocument enhanced-path into standard SVG path syntax where applicable.
     */
    private fun convertEnhancedPathToSvg(path: String): String {
        return path.replace('N', ' ')
            .replace('X', 'Z')
            .replace('x', 'z')
            .replace('m', 'M')
            .replace('l', 'L')
            .replace('c', 'C')
    }

    private fun parseOpacity(value: String): Float {
        val trimmed = value.trim()
        if (trimmed.endsWith("%")) {
            val num = trimmed.dropLast(1).toFloatOrNull() ?: 100f
            return (num / 100f).coerceIn(0f, 1f)
        }
        return (trimmed.toFloatOrNull() ?: 1.0f).coerceIn(0f, 1f)
    }

    private fun parseFontSizePt(value: String): Float? {
        val trimmed = value.trim()
        val numPart = trimmed.takeWhile { it.isDigit() || it == '.' }
        val unitPart = trimmed.substring(numPart.length).trim().lowercase()
        val num = numPart.toFloatOrNull() ?: return null
        return when (unitPart) {
            "pt" -> num
            "in" -> num * 72f
            "cm" -> num * 28.346f
            "mm" -> num * 2.8346f
            "px" -> num * 0.75f
            else -> num
        }
    }

    private fun parseColorHex(value: String): String? {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("#") -> {
                if (trimmed.length == 7 || trimmed.length == 9) trimmed else null
            }
            trimmed.startsWith("rgb", ignoreCase = true) -> {
                val numbers = trimmed.filter { it.isDigit() || it == ',' }
                    .split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                if (numbers.size >= 3) {
                    val r = numbers[0].coerceIn(0, 255)
                    val g = numbers[1].coerceIn(0, 255)
                    val b = numbers[2].coerceIn(0, 255)
                    String.format("#%02X%02X%02X", r, g, b)
                } else null
            }
            else -> null
        }
    }

    private fun decodeBase64Image(base64Str: String, maxDimensionPx: Int): Bitmap? {
        return try {
            val clean = base64Str.filter { !it.isWhitespace() }
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            decodeDownsampledBitmap(bytes, maxDimensionPx)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeDownsampledBitmap(bytes: ByteArray, maxDimensionPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            val origW = bounds.outWidth
            val origH = bounds.outHeight
            if (origW <= 0 || origH <= 0) return null

            var sampleSize = 1
            while ((origW / sampleSize) > maxDimensionPx || (origH / sampleSize) > maxDimensionPx) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readTagText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.TEXT) {
                val t = parser.text
                if (!t.isNullOrEmpty()) sb.append(t)
            } else if (parser.eventType == XmlPullParser.START_TAG) {
                val inner = readTagText(parser)
                if (inner.isNotEmpty()) sb.append(inner)
            }
        }
        return sb.toString()
    }

    private fun getAttr(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            val attrLocal = parser.getAttributeName(i).substringAfter(':')
            if (attrLocal.equals(localName, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun skipSubtree(parser: XmlPullParser) {
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            // skip until matching end tag
        }
    }

    private fun isImageFileName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
    }

    private fun readEntryBytes(zipStream: ZipInputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var totalRead = 0
        var read: Int
        while (zipStream.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
            totalRead += read
            if (totalRead >= maxBytes) break
        }
        return out.toByteArray()
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    data class OdgStyle(
        val isBold: Boolean? = null,
        val isItalic: Boolean? = null,
        val fontSizePt: Float? = null,
        val colorHex: String? = null,
        val alignment: OdgAlignment? = null,
        val fillColorHex: String? = null,
        val fillOpacity: Float = 1f,
        val hasFill: Boolean? = null,
        val strokeColorHex: String? = null,
        val strokeWidthPx: Float = 1f,
        val strokeOpacity: Float = 1f,
        val hasStroke: Boolean? = null,
        val isDashed: Boolean = false
    )
}
