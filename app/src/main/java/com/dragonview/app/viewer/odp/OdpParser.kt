// app/src/main/java/com/dragonview/app/viewer/odp/OdpParser.kt
package com.dragonview.app.viewer.odp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Xml
import com.dragonview.app.performance.BitmapDownsampler
import com.dragonview.app.router.FileFormat
import com.dragonview.app.viewer.pptx.PptxAlignment
import com.dragonview.app.viewer.pptx.PptxParagraph
import com.dragonview.app.viewer.pptx.PptxPresentation
import com.dragonview.app.viewer.pptx.PptxRun
import com.dragonview.app.viewer.pptx.PptxSlide
import com.dragonview.app.viewer.pptx.SlideElement
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
 * High-performance, streaming OpenDocument Presentation (.odp, .otp, .fodp) Parser.
 *
 * Design constraints:
 * - Native streaming XmlPullParser for slide & frame extraction.
 * - Extracts slides from <draw:page> elements.
 * - Parses <draw:frame> dimensions using SVG-style real units (cm, in, pt, mm, px).
 * - Resolves text formatting (bold, italic, color, alignment) via styles.xml and automatic-styles.
 * - Decodes embedded media from Pictures/ with memory-disciplined downsampling.
 * - Gracefully skips unsupported elements (charts, complex animations) without crashing.
 * - Reuses the shared Presentation model (PptxPresentation, PptxSlide, SlideElement).
 */
object OdpParser {

    const val CORRUPTED_ERROR_MESSAGE = "This ODP file appears corrupted or uses an unsupported structure."

    // Standard OpenDocument Impress slide dimensions: 28cm x 21cm (4:3)
    private const val DEFAULT_SLIDE_WIDTH_EMU = 10080000L  // 28cm * 360,000 EMUs/cm
    private const val DEFAULT_SLIDE_HEIGHT_EMU = 7560000L  // 21cm * 360,000 EMUs/cm

    suspend fun parse(
        context: Context,
        uri: Uri,
        formatHint: FileFormat? = null,
        maxImageDimensionPx: Int = 1080
    ): Result<PptxPresentation> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val isFlatXml = formatHint == FileFormat.ODP_FLAT ||
            uri.lastPathSegment?.lowercase()?.endsWith(".fodp") == true

        if (isFlatXml) {
            parseFlatXml(appContext, uri, maxImageDimensionPx)
        } else {
            parseZipPackage(appContext, uri, maxImageDimensionPx)
        }
    }

    /**
     * Parses standard zipped ODF presentation packages (.odp and .otp).
     */
    private suspend fun parseZipPackage(
        context: Context,
        uri: Uri,
        maxImageDimensionPx: Int
    ): Result<PptxPresentation> {
        val mediaCache = mutableMapOf<String, ByteArray>()
        var stylesXmlBytes: ByteArray? = null
        var contentXmlBytes: ByteArray? = null
        var totalMediaBytes = 0L
        val maxMediaCacheBytes = 20 * 1024 * 1024L // 20MB limit

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
                        val lowerName = entryName.lowercase()

                        if ((lowerName.startsWith("pictures/") || lowerName.startsWith("media/") ||
                                isImageFileName(lowerName)) && !entry.isDirectory
                        ) {
                            if (totalMediaBytes < maxMediaCacheBytes) {
                                val bytes = readEntryBytes(zipStream, maxBytes = 4 * 1024 * 1024)
                                totalMediaBytes += bytes.size
                                mediaCache[entryName] = bytes
                                val simpleName = entryName.substringAfterLast('/')
                                if (simpleName.isNotEmpty()) {
                                    mediaCache[simpleName] = bytes
                                }
                            }
                        } else if (entryName == "styles.xml") {
                            stylesXmlBytes = readEntryBytes(zipStream, maxBytes = 3 * 1024 * 1024)
                        } else if (entryName == "content.xml") {
                            contentXmlBytes = readEntryBytes(zipStream, maxBytes = 16 * 1024 * 1024)
                        }
                        zipStream.closeEntry()
                    }
                }
            } ?: return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))
        } catch (ze: ZipException) {
            return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE, ze))
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE, e))
        }

        val contentBytes = contentXmlBytes
            ?: return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))

        return try {
            val styles = mutableMapOf<String, OdpStyle>()
            var slideWidthEmu = DEFAULT_SLIDE_WIDTH_EMU
            var slideHeightEmu = DEFAULT_SLIDE_HEIGHT_EMU

            // 1. Parse styles.xml first if present
            stylesXmlBytes?.let { sBytes ->
                ByteArrayInputStream(sBytes).use { inStream ->
                    val (w, h) = parseStylesXml(inStream, styles)
                    if (w > 0) slideWidthEmu = w
                    if (h > 0) slideHeightEmu = h
                }
            }

            // 2. Parse content.xml for slides, automatic styles, and frames
            val slides = mutableListOf<PptxSlide>()
            ByteArrayInputStream(contentBytes).use { inStream ->
                val (w, h) = parsePresentationXml(
                    inputStream = inStream,
                    styles = styles,
                    mediaCache = mediaCache,
                    maxImageDimensionPx = maxImageDimensionPx,
                    outSlides = slides
                )
                if (w > 0) slideWidthEmu = w
                if (h > 0) slideHeightEmu = h
            }

            if (slides.isEmpty()) {
                // Return at least one slide so viewer displays gracefully
                slides.add(PptxSlide(slideNumber = 1, title = "Slide 1", elements = emptyList()))
            }

            val docTitle = slides.firstOrNull()?.title ?: "OpenDocument Presentation"
            Result.success(
                PptxPresentation(
                    slides = slides,
                    slideWidthEmu = slideWidthEmu,
                    slideHeightEmu = slideHeightEmu,
                    title = docTitle
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE, e))
        }
    }

    /**
     * Parses Flat OpenDocument Presentation XML (.fodp) directly without unzipping.
     */
    private suspend fun parseFlatXml(
        context: Context,
        uri: Uri,
        maxImageDimensionPx: Int
    ): Result<PptxPresentation> {
        return try {
            val styles = mutableMapOf<String, OdpStyle>()
            val slides = mutableListOf<PptxSlide>()
            var slideWidthEmu = DEFAULT_SLIDE_WIDTH_EMU
            var slideHeightEmu = DEFAULT_SLIDE_HEIGHT_EMU

            context.contentResolver.openInputStream(uri)?.use { inStream ->
                val (w, h) = parsePresentationXml(
                    inputStream = inStream,
                    styles = styles,
                    mediaCache = emptyMap(),
                    maxImageDimensionPx = maxImageDimensionPx,
                    outSlides = slides
                )
                if (w > 0) slideWidthEmu = w
                if (h > 0) slideHeightEmu = h
            } ?: return Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE))

            if (slides.isEmpty()) {
                slides.add(PptxSlide(slideNumber = 1, title = "Slide 1", elements = emptyList()))
            }

            val docTitle = slides.firstOrNull()?.title ?: "OpenDocument Presentation"
            Result.success(
                PptxPresentation(
                    slides = slides,
                    slideWidthEmu = slideWidthEmu,
                    slideHeightEmu = slideHeightEmu,
                    title = docTitle
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException(CORRUPTED_ERROR_MESSAGE, e))
        }
    }

    /**
     * Parses presentation XML (content.xml or .fodp).
     * Returns Pair(slideWidthEmu, slideHeightEmu).
     */
    private fun parsePresentationXml(
        inputStream: InputStream,
        styles: MutableMap<String, OdpStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int,
        outSlides: MutableList<PptxSlide>
    ): Pair<Long, Long> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, "UTF-8")

        var slideWidthEmu = 0L
        var slideHeightEmu = 0L
        var slideIndex = 1

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "automatic-styles", "styles" -> {
                        val (w, h) = parseStylesBlock(parser, styles)
                        if (w > 0) slideWidthEmu = w
                        if (h > 0) slideHeightEmu = h
                    }
                    "page-layout" -> {
                        val (w, h) = parsePageLayout(parser)
                        if (w > 0) slideWidthEmu = w
                        if (h > 0) slideHeightEmu = h
                    }
                    "page" -> {
                        // <draw:page draw:name="page1" draw:style-name="dp1">
                        val slide = parseSlidePage(
                            parser = parser,
                            slideNumber = slideIndex++,
                            styles = styles,
                            mediaCache = mediaCache,
                            maxImageDimensionPx = maxImageDimensionPx
                        )
                        if (slide != null) {
                            outSlides.add(slide)
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return Pair(slideWidthEmu, slideHeightEmu)
    }

    /**
     * Parses styles.xml.
     */
    private fun parseStylesXml(
        inputStream: InputStream,
        styles: MutableMap<String, OdpStyle>
    ): Pair<Long, Long> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, "UTF-8")

        var slideWidthEmu = 0L
        var slideHeightEmu = 0L

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "style" -> parseSingleStyle(parser, styles)
                    "page-layout" -> {
                        val (w, h) = parsePageLayout(parser)
                        if (w > 0) slideWidthEmu = w
                        if (h > 0) slideHeightEmu = h
                    }
                }
            }
            eventType = parser.next()
        }

        return Pair(slideWidthEmu, slideHeightEmu)
    }

    private fun parseStylesBlock(
        parser: XmlPullParser,
        styles: MutableMap<String, OdpStyle>
    ): Pair<Long, Long> {
        val depth = parser.depth
        var w = 0L
        var h = 0L

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "style" -> parseSingleStyle(parser, styles)
                    "page-layout" -> {
                        val (pw, ph) = parsePageLayout(parser)
                        if (pw > 0) w = pw
                        if (ph > 0) h = ph
                    }
                }
            }
        }
        return Pair(w, h)
    }

    private fun parsePageLayout(parser: XmlPullParser): Pair<Long, Long> {
        val depth = parser.depth
        var widthEmu = 0L
        var heightEmu = 0L

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                if (tag == "page-layout-properties") {
                    val wAttr = getAttr(parser, "page-width")
                    val hAttr = getAttr(parser, "page-height")
                    if (wAttr != null) widthEmu = parseLengthToEmu(wAttr)
                    if (hAttr != null) heightEmu = parseLengthToEmu(hAttr)
                }
            }
        }
        return Pair(widthEmu, heightEmu)
    }

    private fun parseSingleStyle(
        parser: XmlPullParser,
        styles: MutableMap<String, OdpStyle>
    ) {
        val styleName = getAttr(parser, "name") ?: return
        val depth = parser.depth

        var isBold: Boolean? = null
        var isItalic: Boolean? = null
        var isUnderline: Boolean? = null
        var fontSizePt: Float? = null
        var colorHex: String? = null
        var alignment: PptxAlignment? = null
        var fillColorHex: String? = null
        var slideBgColorHex: String? = null

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "text-properties" -> {
                        val weight = getAttr(parser, "font-weight")
                        if (weight != null && (weight.equals("bold", ignoreCase = true) || (weight.toIntOrNull() ?: 0) >= 700)) {
                            isBold = true
                        }
                        val style = getAttr(parser, "font-style")
                        if (style != null && (style.equals("italic", ignoreCase = true) || style.equals("oblique", ignoreCase = true))) {
                            isItalic = true
                        }
                        val underline = getAttr(parser, "text-underline-style")
                            ?: getAttr(parser, "text-decoration")
                        if (underline != null && !underline.equals("none", ignoreCase = true)) {
                            isUnderline = true
                        }
                        val size = getAttr(parser, "font-size")
                        if (size != null) {
                            fontSizePt = parseFontSizePt(size)
                        }
                        val color = getAttr(parser, "color")
                        if (color != null) {
                            colorHex = parseColorHex(color)
                        }
                    }
                    "paragraph-properties" -> {
                        val alignStr = getAttr(parser, "text-align")
                        if (alignStr != null) {
                            alignment = when (alignStr.lowercase()) {
                                "center" -> PptxAlignment.CENTER
                                "right", "end" -> PptxAlignment.RIGHT
                                "justify" -> PptxAlignment.JUSTIFY
                                else -> PptxAlignment.LEFT
                            }
                        }
                    }
                    "graphic-properties" -> {
                        val fill = getAttr(parser, "fill")
                        val fillCol = getAttr(parser, "fill-color") ?: getAttr(parser, "fill")
                        if (fillCol != null && fill != "none") {
                            fillColorHex = parseColorHex(fillCol)
                        }
                    }
                    "drawing-page-properties" -> {
                        val fillCol = getAttr(parser, "fill-color")
                        if (fillCol != null) {
                            slideBgColorHex = parseColorHex(fillCol)
                        }
                    }
                }
            }
        }

        styles[styleName] = OdpStyle(
            isBold = isBold,
            isItalic = isItalic,
            isUnderline = isUnderline,
            fontSizePt = fontSizePt,
            colorHex = colorHex,
            alignment = alignment,
            fillColorHex = fillColorHex ?: slideBgColorHex,
            slideBgColorHex = slideBgColorHex
        )
    }

    /**
     * Parses a single <draw:page> into a PptxSlide.
     */
    private fun parseSlidePage(
        parser: XmlPullParser,
        slideNumber: Int,
        styles: Map<String, OdpStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
    ): PptxSlide? {
        val pageDepth = parser.depth
        val pageName = getAttr(parser, "name")
        val pageStyleName = getAttr(parser, "style-name")
        val pageStyle = pageStyleName?.let { styles[it] }

        val elements = mutableListOf<SlideElement>()
        var slideTitle: String? = null

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == pageDepth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "frame" -> {
                        val frameElement = parseFrame(
                            parser = parser,
                            styles = styles,
                            mediaCache = mediaCache,
                            maxImageDimensionPx = maxImageDimensionPx
                        )
                        if (frameElement != null) {
                            elements.add(frameElement)
                            if (slideTitle == null && frameElement is SlideElement.TextBox) {
                                val text = frameElement.plainText().trim()
                                if (text.isNotBlank()) {
                                    slideTitle = text.lines().firstOrNull()?.take(60)
                                }
                            }
                        }
                    }
                    // Handle standalone <draw:image> or shapes gracefully
                    "image" -> {
                        val img = parseDirectImage(parser, 0L, 0L, DEFAULT_SLIDE_WIDTH_EMU, DEFAULT_SLIDE_HEIGHT_EMU, mediaCache, maxImageDimensionPx)
                        if (img != null) elements.add(img)
                    }
                }
            }
        }

        return PptxSlide(
            slideNumber = slideNumber,
            title = slideTitle ?: pageName ?: "Slide $slideNumber",
            elements = elements,
            backgroundColorHex = pageStyle?.slideBgColorHex
        )
    }

    /**
     * Parses a <draw:frame> shape into either a TextBox or ImageBox.
     */
    private fun parseFrame(
        parser: XmlPullParser,
        styles: Map<String, OdpStyle>,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
    ): SlideElement? {
        val frameDepth = parser.depth
        val xAttr = getAttr(parser, "x")
        val yAttr = getAttr(parser, "y")
        val wAttr = getAttr(parser, "width")
        val hAttr = getAttr(parser, "height")
        val frameStyleName = getAttr(parser, "style-name")
        val frameStyle = frameStyleName?.let { styles[it] }

        val xEmu = parseLengthToEmu(xAttr)
        val yEmu = parseLengthToEmu(yAttr)
        val wEmu = parseLengthToEmu(wAttr).coerceAtLeast(100000L)
        val hEmu = parseLengthToEmu(hAttr).coerceAtLeast(100000L)

        var createdElement: SlideElement? = null
        val paragraphs = mutableListOf<PptxParagraph>()
        var altText: String? = null

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == frameDepth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "text-box" -> {
                        parseTextBox(parser, styles, frameStyle, paragraphs)
                    }
                    "image" -> {
                        val imageBox = parseDirectImage(
                            parser = parser,
                            xEmu = xEmu,
                            yEmu = yEmu,
                            wEmu = wEmu,
                            hEmu = hEmu,
                            mediaCache = mediaCache,
                            maxImageDimensionPx = maxImageDimensionPx
                        )
                        if (imageBox != null) {
                            createdElement = imageBox
                        }
                    }
                    "title", "desc" -> {
                        val desc = readTagText(parser)
                        if (desc.isNotBlank()) altText = desc
                    }
                }
            }
        }

        if (createdElement != null) {
            return if (altText != null && createdElement is SlideElement.ImageBox) {
                createdElement.copy(altText = altText)
            } else {
                createdElement
            }
        }

        if (paragraphs.isNotEmpty()) {
            return SlideElement.TextBox(
                xEmu = xEmu,
                yEmu = yEmu,
                wEmu = wEmu,
                hEmu = hEmu,
                paragraphs = paragraphs,
                fillColorHex = frameStyle?.fillColorHex
            )
        }

        return null
    }

    /**
     * Parses <draw:text-box> contents into a list of PptxParagraph.
     */
    private fun parseTextBox(
        parser: XmlPullParser,
        styles: Map<String, OdpStyle>,
        inheritedStyle: OdpStyle?,
        outParagraphs: MutableList<PptxParagraph>
    ) {
        val textBoxDepth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == textBoxDepth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "p", "h" -> {
                        val para = parseParagraph(parser, styles, inheritedStyle, isBullet = false)
                        if (para != null) outParagraphs.add(para)
                    }
                    "list" -> {
                        parseList(parser, styles, inheritedStyle, outParagraphs)
                    }
                }
            }
        }
    }

    /**
     * Parses a <text:list> into bullet paragraphs.
     */
    private fun parseList(
        parser: XmlPullParser,
        styles: Map<String, OdpStyle>,
        inheritedStyle: OdpStyle?,
        outParagraphs: MutableList<PptxParagraph>
    ) {
        val listDepth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == listDepth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                if (tag == "list-item") {
                    parseListItem(parser, styles, inheritedStyle, outParagraphs)
                }
            }
        }
    }

    private fun parseListItem(
        parser: XmlPullParser,
        styles: Map<String, OdpStyle>,
        inheritedStyle: OdpStyle?,
        outParagraphs: MutableList<PptxParagraph>
    ) {
        val itemDepth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == itemDepth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                when (tag) {
                    "p", "h" -> {
                        val para = parseParagraph(parser, styles, inheritedStyle, isBullet = true)
                        if (para != null) outParagraphs.add(para)
                    }
                    "list" -> {
                        parseList(parser, styles, inheritedStyle, outParagraphs)
                    }
                }
            }
        }
    }

    /**
     * Parses <text:p> or <text:h> into a PptxParagraph.
     */
    private fun parseParagraph(
        parser: XmlPullParser,
        styles: Map<String, OdpStyle>,
        inheritedStyle: OdpStyle?,
        isBullet: Boolean
    ): PptxParagraph? {
        val paraDepth = parser.depth
        val styleName = getAttr(parser, "style-name")
        val paraStyle = styleName?.let { styles[it] }

        val alignment = paraStyle?.alignment ?: inheritedStyle?.alignment ?: PptxAlignment.LEFT
        val runs = mutableListOf<PptxRun>()

        val baseBold = paraStyle?.isBold ?: inheritedStyle?.isBold ?: false
        val baseItalic = paraStyle?.isItalic ?: inheritedStyle?.isItalic ?: false
        val baseUnderline = paraStyle?.isUnderline ?: inheritedStyle?.isUnderline ?: false
        val baseFontSize = paraStyle?.fontSizePt ?: inheritedStyle?.fontSizePt ?: 18f
        val baseColor = paraStyle?.colorHex ?: inheritedStyle?.colorHex

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == paraDepth)) {
            when (parser.eventType) {
                XmlPullParser.TEXT -> {
                    val text = parser.text
                    if (!text.isNullOrEmpty()) {
                        runs.add(
                            PptxRun(
                                text = text,
                                isBold = baseBold,
                                isItalic = baseItalic,
                                isUnderline = baseUnderline,
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
                            val spanStyleName = getAttr(parser, "style-name")
                            val spanStyle = spanStyleName?.let { styles[it] }
                            val spanText = readTagText(parser)

                            if (spanText.isNotEmpty()) {
                                runs.add(
                                    PptxRun(
                                        text = spanText,
                                        isBold = spanStyle?.isBold ?: baseBold,
                                        isItalic = spanStyle?.isItalic ?: baseItalic,
                                        isUnderline = spanStyle?.isUnderline ?: baseUnderline,
                                        fontSizePt = spanStyle?.fontSizePt ?: baseFontSize,
                                        colorHex = spanStyle?.colorHex ?: baseColor
                                    )
                                )
                            }
                        }
                        "s" -> {
                            val count = getAttr(parser, "c")?.toIntOrNull() ?: 1
                            runs.add(PptxRun(text = " ".repeat(count.coerceIn(1, 100))))
                        }
                        "tab" -> {
                            runs.add(PptxRun(text = "\t"))
                        }
                        "line-break" -> {
                            runs.add(PptxRun(text = "\n"))
                        }
                    }
                }
            }
        }

        if (runs.isEmpty()) return null
        return PptxParagraph(runs = runs, alignment = alignment, isBullet = isBullet)
    }

    /**
     * Parses <draw:image> into a SlideElement.ImageBox.
     */
    private fun parseDirectImage(
        parser: XmlPullParser,
        xEmu: Long,
        yEmu: Long,
        wEmu: Long,
        hEmu: Long,
        mediaCache: Map<String, ByteArray>,
        maxImageDimensionPx: Int
    ): SlideElement.ImageBox? {
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
            val cleanHref = href.removePrefix("./")
            val imageBytes = mediaCache[cleanHref]
                ?: mediaCache[cleanHref.lowercase()]
                ?: mediaCache[cleanHref.substringAfterLast('/')]
            imageBytes?.let { decodeDownsampledBitmap(it, maxImageDimensionPx) }
        } else {
            null
        }

        return SlideElement.ImageBox(
            xEmu = xEmu,
            yEmu = yEmu,
            wEmu = wEmu,
            hEmu = hEmu,
            bitmap = bitmap,
            altText = href?.substringAfterLast('/')
        )
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
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            var sampleSize = 1
            while ((origWidth / sampleSize) > maxDimensionPx || (origHeight / sampleSize) > maxDimensionPx) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Converts SVG length strings (e.g., "2.5cm", "10in", "72pt", "100mm", "1920px") to EMUs.
     * 1 inch = 914,400 EMUs
     * 1 cm = 360,000 EMUs (914400 / 2.54)
     * 1 mm = 36,000 EMUs
     * 1 pt = 12,700 EMUs
     * 1 pc = 152,400 EMUs
     * 1 px = 9,525 EMUs (assuming standard 96 DPI)
     */
    fun parseLengthToEmu(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val trimmed = value.trim()
        val numPart = trimmed.takeWhile { it.isDigit() || it == '.' || it == '-' || it == '+' }
        val unitPart = trimmed.substring(numPart.length).trim().lowercase()
        val num = numPart.toDoubleOrNull() ?: return 0L

        return when (unitPart) {
            "in", "inch", "inches" -> (num * 914400.0).toLong()
            "cm" -> (num * 360000.0).toLong()
            "mm" -> (num * 36000.0).toLong()
            "pt" -> (num * 12700.0).toLong()
            "pc" -> (num * 152400.0).toLong()
            "px" -> (num * 9525.0).toLong()
            else -> {
                // If unit is missing, treat small values (< 100) as cm, larger as pt
                if (num < 100.0) (num * 360000.0).toLong() else (num * 12700.0).toLong()
            }
        }
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
                val rgbNumbers = trimmed.filter { it.isDigit() || it == ',' }
                    .split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                if (rgbNumbers.size >= 3) {
                    val r = rgbNumbers[0].coerceIn(0, 255)
                    val g = rgbNumbers[1].coerceIn(0, 255)
                    val b = rgbNumbers[2].coerceIn(0, 255)
                    String.format("#%02X%02X%02X", r, g, b)
                } else null
            }
            else -> null
        }
    }

    private fun readTagText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.TEXT) {
                val text = parser.text
                if (!text.isNullOrEmpty()) sb.append(text)
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

    private data class OdpStyle(
        val isBold: Boolean? = null,
        val isItalic: Boolean? = null,
        val isUnderline: Boolean? = null,
        val fontSizePt: Float? = null,
        val colorHex: String? = null,
        val alignment: PptxAlignment? = null,
        val fillColorHex: String? = null,
        val slideBgColorHex: String? = null
    )
}
