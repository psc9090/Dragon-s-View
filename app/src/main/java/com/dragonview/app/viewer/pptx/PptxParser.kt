// app/src/main/java/com/dragonview/app/viewer/pptx/PptxParser.kt
package com.dragonview.app.viewer.pptx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Xml
import com.dragonview.app.performance.BitmapPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * High-performance, memory-disciplined PPTX Parser.
 *
 * Design constraints:
 * - Zero heavy external dependencies (no Apache POI or Aspose).
 * - Native streaming XmlPullParser for slide XML extraction.
 * - Resolves slide hierarchy and layout dimensions in native EMUs (English Metric Units).
 * - Full slide background support (<p:bg>/solidFill/gradFill) with layout/master inheritance.
 * - Full run formatting (font family, size, color, bold, italic, underline).
 * - Shape position and size inheritance from slide layout placeholders.
 * - Accurate embedded media resolution per slide via .rels.
 * - Decorative solid-fill shapes (<p:sp> with fill but no text).
 */
object PptxParser {

    private const val DEFAULT_SLIDE_WIDTH_EMU = 9144000L  // Standard 4:3 (10" x 7.5")
    private const val DEFAULT_SLIDE_HEIGHT_EMU = 6858000L

    val pptxBitmapPool = BitmapPool(maxEntries = 4)

    private val DEFAULT_SCHEME_COLORS = mapOf(
        "bg1" to "#FFFFFF", "tx1" to "#000000",
        "bg2" to "#EAEAEA", "tx2" to "#595959",
        "lt1" to "#FFFFFF", "dk1" to "#000000",
        "lt2" to "#EEECE1", "dk2" to "#1F497D",
        "accent1" to "#4472C4", "accent2" to "#ED7D31",
        "accent3" to "#A5A5A5", "accent4" to "#FFC000",
        "accent5" to "#5B9BD5", "accent6" to "#70AD47",
        "hlink" to "#0563C1", "folHlink" to "#954F72"
    )

    data class ShapeBounds(
        val xEmu: Long,
        val yEmu: Long,
        val wEmu: Long,
        val hEmu: Long,
        val fillColorHex: String? = null,
        val borderColorHex: String? = null,
        val borderWidthPt: Float? = null
    )

    data class GroupTransform(
        val offX: Long = 0L,
        val offY: Long = 0L,
        val extW: Long = 0L,
        val extH: Long = 0L,
        val chOffX: Long = 0L,
        val chOffY: Long = 0L,
        val chExtW: Long = 0L,
        val chExtH: Long = 0L
    ) {
        fun transform(x: Long, y: Long, w: Long, h: Long): LongArray {
            val scaleX = if (chExtW > 0) extW.toDouble() / chExtW.toDouble() else 1.0
            val scaleY = if (chExtH > 0) extH.toDouble() / chExtH.toDouble() else 1.0
            val transformedX = offX + ((x - chOffX) * scaleX).toLong()
            val transformedY = offY + ((y - chOffY) * scaleY).toLong()
            val transformedW = (w * scaleX).toLong()
            val transformedH = (h * scaleY).toLong()
            return longArrayOf(transformedX, transformedY, transformedW, transformedH)
        }
    }

    private fun applyColorModifiers(
        baseHex: String,
        lumMod: Double?,
        lumOff: Double?,
        tint: Double?,
        shade: Double?
    ): String {
        return try {
            val color = Color.parseColor(baseHex)
            var r = Color.red(color)
            var g = Color.green(color)
            var b = Color.blue(color)

            if (shade != null) {
                r = (r * shade).toInt().coerceIn(0, 255)
                g = (g * shade).toInt().coerceIn(0, 255)
                b = (b * shade).toInt().coerceIn(0, 255)
            }
            if (tint != null) {
                r = (r + (255 - r) * (1.0 - tint)).toInt().coerceIn(0, 255)
                g = (g + (255 - g) * (1.0 - tint)).toInt().coerceIn(0, 255)
                b = (b + (255 - b) * (1.0 - tint)).toInt().coerceIn(0, 255)
            }
            if (lumMod != null || lumOff != null) {
                val mod = lumMod ?: 1.0
                val off = lumOff ?: 0.0
                r = (r * mod + 255 * off).toInt().coerceIn(0, 255)
                g = (g * mod + 255 * off).toInt().coerceIn(0, 255)
                b = (b * mod + 255 * off).toInt().coerceIn(0, 255)
            }
            String.format("#%02X%02X%02X", r, g, b)
        } catch (_: Exception) {
            baseHex
        }
    }

    private fun parseColorElement(parser: XmlPullParser, themeColors: Map<String, String>): String? {
        val tag = parser.name.substringAfter(":")
        var baseColor: String? = null
        when (tag) {
            "srgbClr" -> {
                val valHex = parser.getAttributeValue(null, "val")
                if (!valHex.isNullOrBlank()) baseColor = "#$valHex"
            }
            "schemeClr" -> {
                val scheme = parser.getAttributeValue(null, "val")
                if (scheme != null) baseColor = themeColors[scheme]
            }
            "sysClr" -> {
                val lastClr = parser.getAttributeValue(null, "lastClr")
                if (!lastClr.isNullOrBlank()) baseColor = "#$lastClr"
            }
            else -> return null
        }
        if (baseColor == null) return null

        val initialDepth = parser.depth
        var lumMod: Double? = null
        var lumOff: Double? = null
        var tint: Double? = null
        var shade: Double? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth)) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG) {
                val subTag = parser.name.substringAfter(":")
                val v = parser.getAttributeValue(null, "val")?.toDoubleOrNull()
                if (v != null) {
                    when (subTag) {
                        "lumMod" -> lumMod = v / 100000.0
                        "lumOff" -> lumOff = v / 100000.0
                        "tint" -> tint = v / 100000.0
                        "shade" -> shade = v / 100000.0
                    }
                }
            }
        }
        return if (lumMod != null || lumOff != null || tint != null || shade != null) {
            applyColorModifiers(baseColor, lumMod, lumOff, tint, shade)
        } else {
            baseColor
        }
    }

    suspend fun parse(
        context: Context,
        uri: Uri,
        maxImageDimensionPx: Int = 1080
    ): Result<PptxPresentation> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver

            var presentationXmlBytes: ByteArray? = null
            var presentationRelsBytes: ByteArray? = null
            val slideXmlMap = mutableMapOf<String, ByteArray>()
            val slideRelsMap = mutableMapOf<String, ByteArray>()
            val slideLayoutXmlMap = mutableMapOf<String, ByteArray>()
            val slideLayoutRelsMap = mutableMapOf<String, ByteArray>()
            val slideMasterXmlMap = mutableMapOf<String, ByteArray>()
            val themeXmlMap = mutableMapOf<String, ByteArray>()
            val mediaBitmaps = mutableMapOf<String, Bitmap>()

            contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry: ZipEntry?
                    while (zipStream.nextEntry.also { entry = it } != null) {
                        val name = entry?.name ?: ""
                        when {
                            name == "ppt/presentation.xml" -> {
                                presentationXmlBytes = zipStream.readBytesCompat()
                            }
                            name == "ppt/_rels/presentation.xml.rels" -> {
                                presentationRelsBytes = zipStream.readBytesCompat()
                            }
                            name.startsWith("ppt/slides/_rels/") && name.endsWith(".rels") -> {
                                val filename = name.substringAfterLast("/")
                                slideRelsMap[filename] = zipStream.readBytesCompat()
                            }
                            name.startsWith("ppt/slides/") && name.endsWith(".xml") -> {
                                val filename = name.substringAfterLast("/")
                                slideXmlMap[filename] = zipStream.readBytesCompat()
                            }
                            name.startsWith("ppt/slideLayouts/_rels/") && name.endsWith(".rels") -> {
                                val filename = name.substringAfterLast("/")
                                slideLayoutRelsMap[filename] = zipStream.readBytesCompat()
                            }
                            name.startsWith("ppt/slideLayouts/") && name.endsWith(".xml") -> {
                                val filename = name.substringAfterLast("/")
                                slideLayoutXmlMap[filename] = zipStream.readBytesCompat()
                            }
                            name.startsWith("ppt/slideMasters/") && name.endsWith(".xml") -> {
                                val filename = name.substringAfterLast("/")
                                slideMasterXmlMap[filename] = zipStream.readBytesCompat()
                            }
                            name.startsWith("ppt/theme/") && name.endsWith(".xml") -> {
                                val filename = name.substringAfterLast("/")
                                themeXmlMap[filename] = zipStream.readBytesCompat()
                            }
                            name.startsWith("ppt/media/") -> {
                                val rawBytes = zipStream.readBytesCompat()
                                val bitmap = decodeDownsampledBitmap(rawBytes, maxImageDimensionPx)
                                if (bitmap != null) {
                                    val mediaKey = name.removePrefix("ppt/")
                                    val simpleName = name.substringAfterLast("/")
                                    mediaBitmaps[name] = bitmap
                                    mediaBitmaps[mediaKey] = bitmap
                                    mediaBitmaps[simpleName] = bitmap
                                    mediaBitmaps[name.lowercase()] = bitmap
                                    mediaBitmaps[mediaKey.lowercase()] = bitmap
                                    mediaBitmaps[simpleName.lowercase()] = bitmap
                                    try {
                                        val decoded = URLDecoder.decode(simpleName, "UTF-8")
                                        mediaBitmaps[decoded] = bitmap
                                        mediaBitmaps[decoded.lowercase()] = bitmap
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        zipStream.closeEntry()
                    }
                }
            } ?: return@withContext Result.failure(IllegalStateException("Unable to open content stream for PPTX."))

            val presBytes = presentationXmlBytes
                ?: return@withContext Result.failure(
                    IllegalArgumentException("This PPTX file appears corrupted or uses an unsupported structure (missing ppt/presentation.xml).")
                )

            // Parse theme colors
            val themeColors = parseThemeColors(themeXmlMap["theme1.xml"] ?: themeXmlMap.values.firstOrNull())

            // Parse presentation.xml for dimensions and slide order
            val (slideWidthEmu, slideHeightEmu, slideOrderRIds) = parsePresentationXml(presBytes)

            // Parse presentation.xml.rels to resolve rIds to slide filenames
            val rIdToSlideFileMap = presentationRelsBytes?.let { parseRels(it) } ?: emptyMap()

            // Resolve ordered slide filenames
            val orderedSlideFilenames = mutableListOf<String>()
            if (slideOrderRIds.isNotEmpty()) {
                for (rId in slideOrderRIds) {
                    val target = rIdToSlideFileMap[rId]
                    if (target != null) {
                        val simpleFilename = target.substringAfterLast("/")
                        if (slideXmlMap.containsKey(simpleFilename)) {
                            orderedSlideFilenames.add(simpleFilename)
                        }
                    }
                }
            }

            // Fallback: If rels order didn't find slides, natural sort by slide number (slide1.xml, slide2.xml...)
            if (orderedSlideFilenames.isEmpty()) {
                orderedSlideFilenames.addAll(
                    slideXmlMap.keys.sortedWith(Comparator { s1, s2 ->
                        extractNumber(s1).compareTo(extractNumber(s2))
                    })
                )
            }

            if (orderedSlideFilenames.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("This PPTX file appears corrupted or uses an unsupported structure (no slides found).")
                )
            }

            // Parse each slide
            val slides = mutableListOf<PptxSlide>()
            for ((index, slideFilename) in orderedSlideFilenames.withIndex()) {
                val slideBytes = slideXmlMap[slideFilename] ?: continue
                val relsFilename = "$slideFilename.rels"
                val relsBytes = slideRelsMap[relsFilename]
                val slideRels = relsBytes?.let { parseRels(it) } ?: emptyMap()

                // Resolve slide layout and master for placeholder and background inheritance
                val layoutTarget = slideRels.entries.firstOrNull { it.key.contains("layout", ignoreCase = true) || it.value.contains("slideLayout", ignoreCase = true) }?.value
                    ?: slideRels.values.firstOrNull { it.contains("slideLayout") }
                val layoutFilename = layoutTarget?.substringAfterLast("/")
                val layoutBytes = layoutFilename?.let { slideLayoutXmlMap[it] }

                val layoutRelsFilename = layoutFilename?.let { "$it.rels" }
                val layoutRelsBytes = layoutRelsFilename?.let { slideLayoutRelsMap[it] }
                val layoutRels = layoutRelsBytes?.let { parseRels(it) } ?: emptyMap()

                val masterTarget = layoutRels.entries.firstOrNull { it.key.contains("master", ignoreCase = true) || it.value.contains("slideMaster", ignoreCase = true) }?.value
                    ?: layoutRels.values.firstOrNull { it.contains("slideMaster") }
                val masterFilename = masterTarget?.substringAfterLast("/")
                val masterBytes = masterFilename?.let { slideMasterXmlMap[it] }

                // 1. Background inheritance: slide -> layout -> master -> default
                var background = parseBackground(slideBytes, themeColors)
                if (background == null && layoutBytes != null) {
                    background = parseBackground(layoutBytes, themeColors)
                }
                if (background == null && masterBytes != null) {
                    background = parseBackground(masterBytes, themeColors)
                }
                if (background == null) {
                    background = PptxBackground(colorHex = "#FFFFFF", isGradient = false)
                }

                // 2. Shape position/size inheritance: parse placeholders from layout
                val layoutPlaceholders = layoutBytes?.let { parseLayoutPlaceholders(it, themeColors) } ?: emptyMap()

                val slide = parseSlideXml(
                    slideNumber = index + 1,
                    slideXmlBytes = slideBytes,
                    mediaRelMap = slideRels,
                    mediaBitmaps = mediaBitmaps,
                    layoutPlaceholders = layoutPlaceholders,
                    themeColors = themeColors,
                    background = background
                )
                slides.add(slide)
            }

            Result.success(
                PptxPresentation(
                    slides = slides,
                    slideWidthEmu = slideWidthEmu,
                    slideHeightEmu = slideHeightEmu,
                    title = slides.firstOrNull()?.title ?: "Presentation"
                )
            )
        } catch (e: Exception) {
            Result.failure(
                IllegalArgumentException("This PPTX file appears corrupted or uses an unsupported structure: ${e.message}", e)
            )
        }
    }

    private fun parsePresentationXml(xmlBytes: ByteArray): Triple<Long, Long, List<String>> {
        var widthEmu = DEFAULT_SLIDE_WIDTH_EMU
        var heightEmu = DEFAULT_SLIDE_HEIGHT_EMU
        val slideRIds = mutableListOf<String>()

        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(xmlBytes), null)
        }

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val name = parser.name.substringAfter(":")
                when (name) {
                    "sldSz" -> {
                        val cx = parser.getAttributeValue(null, "cx")?.toLongOrNull()
                        val cy = parser.getAttributeValue(null, "cy")?.toLongOrNull()
                        if (cx != null && cx > 0) widthEmu = cx
                        if (cy != null && cy > 0) heightEmu = cy
                    }
                    "sldId" -> {
                        val rId = parser.getAttributeValue(null, "r:id")
                            ?: parser.getAttributeValue(null, "id")
                        if (!rId.isNullOrEmpty()) {
                            slideRIds.add(rId)
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return Triple(widthEmu, heightEmu, slideRIds)
    }

    private fun parseRels(xmlBytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(xmlBytes), null)
        }

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.name == "Relationship") {
                    val id = parser.getAttributeValue(null, "Id")
                        ?: parser.getAttributeValue("", "Id")
                    val target = parser.getAttributeValue(null, "Target")
                        ?: parser.getAttributeValue("", "Target")
                    if (id != null && target != null) {
                        // Normalize path: e.g. "../media/image1.png" -> "media/image1.png"
                        val normalized = target.replace("../", "").removePrefix("/")
                        map[id] = normalized
                    }
                }
            }
            eventType = parser.next()
        }
        return map
    }

    private fun parseThemeColors(xmlBytes: ByteArray?): Map<String, String> {
        val result = DEFAULT_SCHEME_COLORS.toMutableMap()
        if (xmlBytes == null || xmlBytes.isEmpty()) return result

        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(ByteArrayInputStream(xmlBytes), null)
            }
            var currentSchemeTag: String? = null
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val name = parser.name.substringAfter(":")
                    when {
                        name in listOf("dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink") -> {
                            currentSchemeTag = name
                        }
                        name == "srgbClr" && currentSchemeTag != null -> {
                            val valHex = parser.getAttributeValue(null, "val")
                            if (!valHex.isNullOrBlank()) {
                                val color = "#$valHex"
                                result[currentSchemeTag] = color
                                if (currentSchemeTag == "lt1") result["bg1"] = color
                                if (currentSchemeTag == "dk1") result["tx1"] = color
                                if (currentSchemeTag == "lt2") result["bg2"] = color
                                if (currentSchemeTag == "dk2") result["tx2"] = color
                            }
                            currentSchemeTag = null
                        }
                        name == "sysClr" && currentSchemeTag != null -> {
                            val lastClr = parser.getAttributeValue(null, "lastClr")
                            if (!lastClr.isNullOrBlank()) {
                                val color = "#$lastClr"
                                result[currentSchemeTag] = color
                                if (currentSchemeTag == "lt1") result["bg1"] = color
                                if (currentSchemeTag == "dk1") result["tx1"] = color
                            }
                            currentSchemeTag = null
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    val name = parser.name.substringAfter(":")
                    if (name == currentSchemeTag) {
                        currentSchemeTag = null
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {}

        return result
    }

    private fun parseBackground(
        xmlBytes: ByteArray?,
        themeColors: Map<String, String>
    ): PptxBackground? {
        if (xmlBytes == null || xmlBytes.isEmpty()) return null
        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(ByteArrayInputStream(xmlBytes), null)
            }
            var inBg = false
            var eventType = parser.eventType
            val gradStops = mutableListOf<String>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tag = parser.name.substringAfter(":")
                    if (tag == "bg" || tag == "bgPr" || tag == "bgRef") {
                        inBg = true
                    }
                    if (inBg) {
                        when (tag) {
                            "srgbClr" -> {
                                val hex = parser.getAttributeValue(null, "val")
                                if (!hex.isNullOrBlank()) {
                                    gradStops.add("#$hex")
                                }
                            }
                            "schemeClr" -> {
                                val scheme = parser.getAttributeValue(null, "val")
                                val resolved = themeColors[scheme]
                                if (resolved != null) {
                                    gradStops.add(resolved)
                                }
                            }
                            "sysClr" -> {
                                val lastClr = parser.getAttributeValue(null, "lastClr")
                                if (!lastClr.isNullOrBlank()) {
                                    gradStops.add("#$lastClr")
                                }
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    val tag = parser.name.substringAfter(":")
                    if (tag == "bg" || tag == "bgPr" || tag == "bgRef") {
                        if (gradStops.size >= 2) {
                            return PptxBackground(
                                colorHex = gradStops.first(),
                                gradientColorsHex = gradStops,
                                isGradient = true
                            )
                        } else if (gradStops.size == 1) {
                            return PptxBackground(
                                colorHex = gradStops.first(),
                                isGradient = false
                            )
                        }
                        inBg = false
                    }
                }
                eventType = parser.next()
            }

            if (gradStops.size >= 2) {
                return PptxBackground(
                    colorHex = gradStops.first(),
                    gradientColorsHex = gradStops,
                    isGradient = true
                )
            } else if (gradStops.size == 1) {
                return PptxBackground(
                    colorHex = gradStops.first(),
                    isGradient = false
                )
            }
        } catch (_: Exception) {}
        return null
    }

    private fun parseLayoutPlaceholders(
        xmlBytes: ByteArray,
        themeColors: Map<String, String>
    ): Map<String, ShapeBounds> {
        val placeholders = mutableMapOf<String, ShapeBounds>()
        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(ByteArrayInputStream(xmlBytes), null)
            }
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.endsWith("sp")) {
                    val initialDepth = parser.depth
                    var xEmu = 0L
                    var yEmu = 0L
                    var wEmu = 0L
                    var hEmu = 0L
                    var phType: String? = null
                    var phIdx: String? = null
                    var fillColorHex: String? = null

                    while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name.endsWith("sp"))) {
                        parser.next()
                        if (parser.eventType == XmlPullParser.START_TAG) {
                            val tag = parser.name.substringAfter(":")
                            when (tag) {
                                "ph" -> {
                                    phType = parser.getAttributeValue(null, "type")
                                    phIdx = parser.getAttributeValue(null, "idx")
                                }
                                "off" -> {
                                    xEmu = parser.getAttributeValue(null, "x")?.toLongOrNull() ?: xEmu
                                    yEmu = parser.getAttributeValue(null, "y")?.toLongOrNull() ?: yEmu
                                }
                                "ext" -> {
                                    wEmu = parser.getAttributeValue(null, "cx")?.toLongOrNull() ?: wEmu
                                    hEmu = parser.getAttributeValue(null, "cy")?.toLongOrNull() ?: hEmu
                                }
                                "srgbClr" -> {
                                    val hex = parser.getAttributeValue(null, "val")
                                    if (!hex.isNullOrBlank() && fillColorHex == null) {
                                        fillColorHex = "#$hex"
                                    }
                                }
                                "schemeClr" -> {
                                    val scheme = parser.getAttributeValue(null, "val")
                                    if (fillColorHex == null && scheme != null) {
                                        fillColorHex = themeColors[scheme]
                                    }
                                }
                            }
                        }
                    }

                    val bounds = ShapeBounds(xEmu, yEmu, wEmu, hEmu, fillColorHex)
                    if (phType != null && phIdx != null) {
                        placeholders["${phType}_$phIdx"] = bounds
                    }
                    if (phType != null) {
                        placeholders[phType] = bounds
                    }
                    if (phIdx != null) {
                        placeholders[phIdx] = bounds
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {}
        return placeholders
    }

    private fun parseSlideXml(
        slideNumber: Int,
        slideXmlBytes: ByteArray,
        mediaRelMap: Map<String, String>,
        mediaBitmaps: Map<String, Bitmap>,
        layoutPlaceholders: Map<String, ShapeBounds>,
        themeColors: Map<String, String>,
        background: PptxBackground
    ): PptxSlide {
        val elements = mutableListOf<SlideElement>()
        var slideTitle: String? = null

        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(slideXmlBytes), null)
        }

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(":")
                when (tag) {
                    "sp" -> {
                        val shape = parseShape(parser, mediaRelMap, mediaBitmaps, layoutPlaceholders, themeColors)
                        if (shape != null) {
                            elements.add(shape)
                            if (slideTitle == null && shape is SlideElement.TextBox && shape.plainText().isNotBlank()) {
                                slideTitle = shape.plainText().lines().firstOrNull { it.isNotBlank() }?.trim()
                            }
                        }
                    }
                    "pic" -> {
                        val picture = parsePicture(parser, mediaRelMap, mediaBitmaps)
                        if (picture != null) {
                            elements.add(picture)
                        }
                    }
                    "grpSp" -> {
                        parseGroupShapes(parser, mediaRelMap, mediaBitmaps, layoutPlaceholders, themeColors, elements)
                    }
                }
            }
            eventType = parser.next()
        }

        return PptxSlide(
            slideNumber = slideNumber,
            title = slideTitle ?: "Slide $slideNumber",
            elements = elements,
            backgroundColorHex = background.colorHex,
            background = background
        )
    }

    private fun parseGroupShapes(
        parser: XmlPullParser,
        mediaRelMap: Map<String, String>,
        mediaBitmaps: Map<String, Bitmap>,
        layoutPlaceholders: Map<String, ShapeBounds>,
        themeColors: Map<String, String>,
        outElements: MutableList<SlideElement>,
        parentTransform: GroupTransform? = null
    ) {
        val initialDepth = parser.depth
        var currentTransform = GroupTransform()
        var inGrpSpPr = false

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name.endsWith("grpSp"))) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(":")
                when (tag) {
                    "grpSpPr" -> inGrpSpPr = true
                    "off" -> if (inGrpSpPr) {
                        val ox = parser.getAttributeValue(null, "x")?.toLongOrNull() ?: currentTransform.offX
                        val oy = parser.getAttributeValue(null, "y")?.toLongOrNull() ?: currentTransform.offY
                        currentTransform = currentTransform.copy(offX = ox, offY = oy)
                    }
                    "ext" -> if (inGrpSpPr) {
                        val ew = parser.getAttributeValue(null, "cx")?.toLongOrNull() ?: currentTransform.extW
                        val eh = parser.getAttributeValue(null, "cy")?.toLongOrNull() ?: currentTransform.extH
                        currentTransform = currentTransform.copy(extW = ew, extH = eh)
                    }
                    "chOff" -> if (inGrpSpPr) {
                        val cox = parser.getAttributeValue(null, "x")?.toLongOrNull() ?: currentTransform.chOffX
                        val coy = parser.getAttributeValue(null, "y")?.toLongOrNull() ?: currentTransform.chOffY
                        currentTransform = currentTransform.copy(chOffX = cox, chOffY = coy)
                    }
                    "chExt" -> if (inGrpSpPr) {
                        val cew = parser.getAttributeValue(null, "cx")?.toLongOrNull() ?: currentTransform.chExtW
                        val ceh = parser.getAttributeValue(null, "cy")?.toLongOrNull() ?: currentTransform.chExtH
                        currentTransform = currentTransform.copy(chExtW = cew, chExtH = ceh)
                    }
                    "sp" -> {
                        val shape = parseShape(parser, mediaRelMap, mediaBitmaps, layoutPlaceholders, themeColors)
                        if (shape != null) {
                            val effectiveTransform = if (parentTransform != null) {
                                val pCoords = parentTransform.transform(currentTransform.offX, currentTransform.offY, currentTransform.extW, currentTransform.extH)
                                currentTransform.copy(offX = pCoords[0], offY = pCoords[1], extW = pCoords[2], extH = pCoords[3])
                            } else currentTransform

                            val tCoords = effectiveTransform.transform(shape.xEmu, shape.yEmu, shape.wEmu, shape.hEmu)
                            val transformedShape = when (shape) {
                                is SlideElement.TextBox -> shape.copy(xEmu = tCoords[0], yEmu = tCoords[1], wEmu = tCoords[2], hEmu = tCoords[3])
                                is SlideElement.ShapeBox -> shape.copy(xEmu = tCoords[0], yEmu = tCoords[1], wEmu = tCoords[2], hEmu = tCoords[3])
                                is SlideElement.ImageBox -> shape.copy(xEmu = tCoords[0], yEmu = tCoords[1], wEmu = tCoords[2], hEmu = tCoords[3])
                            }
                            outElements.add(transformedShape)
                        }
                    }
                    "pic" -> {
                        val picture = parsePicture(parser, mediaRelMap, mediaBitmaps)
                        if (picture != null) {
                            val effectiveTransform = if (parentTransform != null) {
                                val pCoords = parentTransform.transform(currentTransform.offX, currentTransform.offY, currentTransform.extW, currentTransform.extH)
                                currentTransform.copy(offX = pCoords[0], offY = pCoords[1], extW = pCoords[2], extH = pCoords[3])
                            } else currentTransform

                            val tCoords = effectiveTransform.transform(picture.xEmu, picture.yEmu, picture.wEmu, picture.hEmu)
                            outElements.add(picture.copy(xEmu = tCoords[0], yEmu = tCoords[1], wEmu = tCoords[2], hEmu = tCoords[3]))
                        }
                    }
                    "grpSp" -> {
                        val effectiveTransform = if (parentTransform != null) {
                            val pCoords = parentTransform.transform(currentTransform.offX, currentTransform.offY, currentTransform.extW, currentTransform.extH)
                            currentTransform.copy(offX = pCoords[0], offY = pCoords[1], extW = pCoords[2], extH = pCoords[3])
                        } else currentTransform
                        parseGroupShapes(parser, mediaRelMap, mediaBitmaps, layoutPlaceholders, themeColors, outElements, effectiveTransform)
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG) {
                val tag = parser.name.substringAfter(":")
                if (tag == "grpSpPr") inGrpSpPr = false
            }
        }
    }

    private fun parseShape(
        parser: XmlPullParser,
        mediaRelMap: Map<String, String>,
        mediaBitmaps: Map<String, Bitmap>,
        layoutPlaceholders: Map<String, ShapeBounds>,
        themeColors: Map<String, String>
    ): SlideElement? {
        val initialDepth = parser.depth
        var xEmu = 0L
        var yEmu = 0L
        var wEmu = 0L
        var hEmu = 0L
        var rotationDeg = 0f

        var phType: String? = null
        var phIdx: String? = null

        var fillColorHex: String? = null
        var borderColorHex: String? = null
        var borderWidthPt: Float? = null
        var isRounded = false
        var blipEmbedId: String? = null

        var inLn = false
        var inGradFill = false
        val gradColors = mutableListOf<String>()
        val paragraphs = mutableListOf<PptxParagraph>()

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name.endsWith("sp"))) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(":")
                when (tag) {
                    "xfrm" -> {
                        val rot = parser.getAttributeValue(null, "rot")?.toFloatOrNull()
                        if (rot != null) {
                            rotationDeg = rot / 60000f
                        }
                    }
                    "ph" -> {
                        phType = parser.getAttributeValue(null, "type")
                        phIdx = parser.getAttributeValue(null, "idx")
                    }
                    "off" -> {
                        xEmu = parser.getAttributeValue(null, "x")?.toLongOrNull() ?: xEmu
                        yEmu = parser.getAttributeValue(null, "y")?.toLongOrNull() ?: yEmu
                    }
                    "ext" -> {
                        wEmu = parser.getAttributeValue(null, "cx")?.toLongOrNull() ?: wEmu
                        hEmu = parser.getAttributeValue(null, "cy")?.toLongOrNull() ?: hEmu
                    }
                    "ln" -> {
                        inLn = true
                        val w = parser.getAttributeValue(null, "w")?.toIntOrNull()
                        if (w != null && w > 0) {
                            borderWidthPt = w / 12700f
                        }
                    }
                    "gradFill" -> {
                        inGradFill = true
                    }
                    "prstGeom" -> {
                        val prst = parser.getAttributeValue(null, "prst")
                        if (prst == "roundRect") {
                            isRounded = true
                        }
                    }
                    "blip" -> {
                        blipEmbedId = parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")
                            ?: parser.getAttributeValue(null, "r:embed")
                            ?: parser.getAttributeValue(null, "embed")
                            ?: parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "link")
                            ?: parser.getAttributeValue(null, "r:link")
                            ?: parser.getAttributeValue(null, "link")
                    }
                    "srgbClr", "schemeClr", "sysClr" -> {
                        val parsedColor = parseColorElement(parser, themeColors)
                        if (parsedColor != null) {
                            if (inLn) {
                                borderColorHex = parsedColor
                            } else if (inGradFill) {
                                gradColors.add(parsedColor)
                            } else if (fillColorHex == null) {
                                fillColorHex = parsedColor
                            }
                        }
                    }
                    "p" -> {
                        val p = parseParagraph(parser, themeColors)
                        if (p != null) {
                            paragraphs.add(p)
                        }
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG) {
                val tag = parser.name.substringAfter(":")
                if (tag == "ln") {
                    inLn = false
                } else if (tag == "gradFill") {
                    inGradFill = false
                }
            }
        }

        // Apply placeholder inheritance from slide layout if position/size is missing
        if (wEmu == 0L || hEmu == 0L) {
            val inheritedBounds = (if (phType != null && phIdx != null) layoutPlaceholders["${phType}_$phIdx"] else null)
                ?: (if (phType != null) layoutPlaceholders[phType] else null)
                ?: (if (phIdx != null) layoutPlaceholders[phIdx] else null)
                ?: (if (phType == null && phIdx == null) layoutPlaceholders["body"] ?: layoutPlaceholders["ctrTitle"] ?: layoutPlaceholders["title"] else null)

            if (inheritedBounds != null) {
                if (wEmu == 0L) wEmu = inheritedBounds.wEmu
                if (hEmu == 0L) hEmu = inheritedBounds.hEmu
                if (xEmu == 0L) xEmu = inheritedBounds.xEmu
                if (yEmu == 0L) yEmu = inheritedBounds.yEmu
                if (fillColorHex == null) fillColorHex = inheritedBounds.fillColorHex
                if (borderColorHex == null) borderColorHex = inheritedBounds.borderColorHex
                if (borderWidthPt == null) borderWidthPt = inheritedBounds.borderWidthPt
            }
        }

        val effectiveW = if (wEmu > 0) wEmu else 3000000L
        val effectiveH = if (hEmu > 0) hEmu else 1000000L

        // If shape has an embedded blip image
        if (blipEmbedId != null) {
            val bitmap = resolveMediaBitmap(blipEmbedId, mediaRelMap, mediaBitmaps)
            if (bitmap != null) {
                return SlideElement.ImageBox(
                    xEmu = xEmu,
                    yEmu = yEmu,
                    wEmu = effectiveW,
                    hEmu = effectiveH,
                    bitmap = bitmap,
                    altText = "Shape Image",
                    rotationDeg = rotationDeg
                )
            }
        }

        // If shape has non-empty text
        val hasText = paragraphs.isNotEmpty() && paragraphs.any { it.runs.isNotEmpty() && it.runs.any { r -> r.text.isNotBlank() } }
        if (hasText) {
            return SlideElement.TextBox(
                xEmu = xEmu,
                yEmu = yEmu,
                wEmu = effectiveW,
                hEmu = effectiveH,
                paragraphs = paragraphs,
                fillColorHex = fillColorHex,
                gradientColorsHex = if (gradColors.size >= 2) gradColors else null,
                borderColorHex = borderColorHex,
                borderWidthPt = borderWidthPt,
                rotationDeg = rotationDeg
            )
        }

        // Decorative shape with fill, border, or gradient but no text
        if (fillColorHex != null || borderColorHex != null || gradColors.size >= 2) {
            return SlideElement.ShapeBox(
                xEmu = xEmu,
                yEmu = yEmu,
                wEmu = effectiveW,
                hEmu = effectiveH,
                fillColorHex = fillColorHex,
                gradientColorsHex = if (gradColors.size >= 2) gradColors else null,
                borderColorHex = borderColorHex,
                borderWidthPt = borderWidthPt,
                isRounded = isRounded,
                rotationDeg = rotationDeg
            )
        }

        return null
    }

    private fun parseParagraph(parser: XmlPullParser, themeColors: Map<String, String>): PptxParagraph? {
        val initialDepth = parser.depth
        val runs = mutableListOf<PptxRun>()
        var alignment = PptxAlignment.LEFT
        var isBullet = false
        var bulletChar: String? = null
        var indentLevel = 0

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name.endsWith("p"))) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(":")
                when (tag) {
                    "pPr" -> {
                        val algn = parser.getAttributeValue(null, "algn")
                        alignment = when (algn) {
                            "ctr" -> PptxAlignment.CENTER
                            "r" -> PptxAlignment.RIGHT
                            "just" -> PptxAlignment.JUSTIFY
                            else -> PptxAlignment.LEFT
                        }
                        val lvl = parser.getAttributeValue(null, "lvl")?.toIntOrNull()
                        if (lvl != null) {
                            indentLevel = lvl
                        }
                    }
                    "buChar" -> {
                        bulletChar = parser.getAttributeValue(null, "char")
                        isBullet = true
                    }
                    "buAutoNum" -> {
                        isBullet = true
                    }
                    "buNone" -> {
                        isBullet = false
                        bulletChar = null
                    }
                    "r" -> {
                        val run = parseRun(parser, themeColors)
                        if (run != null && run.text.isNotEmpty()) {
                            runs.add(run)
                        }
                    }
                    "br" -> {
                        runs.add(PptxRun(text = "\n"))
                    }
                }
            }
        }

        return if (runs.isNotEmpty()) {
            PptxParagraph(
                runs = runs,
                alignment = alignment,
                isBullet = isBullet,
                bulletChar = bulletChar,
                indentLevel = indentLevel
            )
        } else {
            null
        }
    }

    private fun parseRun(parser: XmlPullParser, themeColors: Map<String, String>): PptxRun? {
        val initialDepth = parser.depth
        var text = ""
        var isBold = false
        var isItalic = false
        var isUnderline = false
        var fontSizePt: Float? = null
        var colorHex: String? = null
        var fontFamily: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name.endsWith("r"))) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(":")
                when (tag) {
                    "rPr" -> {
                        val b = parser.getAttributeValue(null, "b")
                        isBold = b == "1" || b == "true"
                        val i = parser.getAttributeValue(null, "i")
                        isItalic = i == "1" || i == "true"
                        val u = parser.getAttributeValue(null, "u")
                        isUnderline = u != null && u != "none"
                        val sz = parser.getAttributeValue(null, "sz")?.toIntOrNull()
                        if (sz != null && sz > 0) {
                            // PowerPoint sz is in 1/100 of a point (e.g. 2400 = 24pt)
                            fontSizePt = sz / 100f
                        }
                    }
                    "latin" -> {
                        val typeface = parser.getAttributeValue(null, "typeface")
                        if (!typeface.isNullOrBlank()) {
                            fontFamily = typeface
                        }
                    }
                    "ea", "cs" -> {
                        if (fontFamily == null) {
                            val typeface = parser.getAttributeValue(null, "typeface")
                            if (!typeface.isNullOrBlank()) {
                                fontFamily = typeface
                            }
                        }
                    }
                    "srgbClr", "schemeClr", "sysClr" -> {
                        val parsedColor = parseColorElement(parser, themeColors)
                        if (parsedColor != null) {
                            colorHex = parsedColor
                        }
                    }
                    "t" -> {
                        text = parser.nextText()
                    }
                }
            }
        }

        return if (text.isNotEmpty()) {
            PptxRun(
                text = text,
                isBold = isBold,
                isItalic = isItalic,
                isUnderline = isUnderline,
                fontSizePt = fontSizePt,
                colorHex = colorHex,
                fontFamily = fontFamily
            )
        } else {
            null
        }
    }

    private fun parsePicture(
        parser: XmlPullParser,
        mediaRelMap: Map<String, String>,
        mediaBitmaps: Map<String, Bitmap>
    ): SlideElement.ImageBox? {
        val initialDepth = parser.depth
        var xEmu = 0L
        var yEmu = 0L
        var wEmu = 0L
        var hEmu = 0L
        var rotationDeg = 0f
        var blipEmbedId: String? = null
        var altText: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == initialDepth && parser.name.endsWith("pic"))) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(":")
                when (tag) {
                    "cNvPr" -> {
                        altText = parser.getAttributeValue(null, "name")
                            ?: parser.getAttributeValue(null, "descr")
                    }
                    "xfrm" -> {
                        val rot = parser.getAttributeValue(null, "rot")?.toFloatOrNull()
                        if (rot != null) {
                            rotationDeg = rot / 60000f
                        }
                    }
                    "off" -> {
                        xEmu = parser.getAttributeValue(null, "x")?.toLongOrNull() ?: xEmu
                        yEmu = parser.getAttributeValue(null, "y")?.toLongOrNull() ?: yEmu
                    }
                    "ext" -> {
                        wEmu = parser.getAttributeValue(null, "cx")?.toLongOrNull() ?: wEmu
                        hEmu = parser.getAttributeValue(null, "cy")?.toLongOrNull() ?: hEmu
                    }
                    "blip" -> {
                        blipEmbedId = parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")
                            ?: parser.getAttributeValue(null, "r:embed")
                            ?: parser.getAttributeValue(null, "embed")
                            ?: parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "link")
                            ?: parser.getAttributeValue(null, "r:link")
                            ?: parser.getAttributeValue(null, "link")
                    }
                }
            }
        }

        val bitmap = resolveMediaBitmap(blipEmbedId, mediaRelMap, mediaBitmaps)

        return if (bitmap != null || (wEmu > 0 && hEmu > 0)) {
            SlideElement.ImageBox(
                xEmu = xEmu,
                yEmu = yEmu,
                wEmu = if (wEmu > 0) wEmu else 3000000L,
                hEmu = if (hEmu > 0) hEmu else 2000000L,
                bitmap = bitmap,
                altText = altText ?: "Slide Image",
                rotationDeg = rotationDeg
            )
        } else {
            null
        }
    }

    private fun resolveMediaBitmap(
        embedId: String?,
        mediaRelMap: Map<String, String>,
        mediaBitmaps: Map<String, Bitmap>
    ): Bitmap? {
        if (embedId == null) return null
        val target = mediaRelMap[embedId] ?: return null
        val normalized = target.replace("../", "").removePrefix("/")
        val simpleName = normalized.substringAfterLast("/")

        return mediaBitmaps[normalized]
            ?: mediaBitmaps["ppt/$normalized"]
            ?: mediaBitmaps[simpleName]
            ?: mediaBitmaps[normalized.lowercase()]
            ?: mediaBitmaps[simpleName.lowercase()]
            ?: runCatching {
                val decoded = URLDecoder.decode(simpleName, "UTF-8")
                mediaBitmaps[decoded] ?: mediaBitmaps[decoded.lowercase()]
            }.getOrNull()
    }

    private fun decodeDownsampledBitmap(bytes: ByteArray, maxDimensionPx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
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

            val targetW = (origWidth / sampleSize).coerceAtLeast(1)
            val targetH = (origHeight / sampleSize).coerceAtLeast(1)
            val reusable = pptxBitmapPool.acquire(targetW, targetH, Bitmap.Config.RGB_565)

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = true
                if (reusable != null) inBitmap = reusable
            }
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            } catch (_: IllegalArgumentException) {
                decodeOptions.inBitmap = null
                pptxBitmapPool.release(reusable)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun extractNumber(s: String): Int {
        val numStr = s.filter { it.isDigit() }
        return numStr.toIntOrNull() ?: 0
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
