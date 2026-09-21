// app/src/main/java/com/dragonview/app/viewer/pptx/PptxModel.kt
package com.dragonview.app.viewer.pptx

import android.graphics.Bitmap

/**
 * High-level presentation document model.
 * All coordinates and dimensions are tracked in EMUs (English Metric Units: 1 pt = 12,700 EMUs, 1 inch = 914,400 EMUs).
 */
data class PptxPresentation(
    val slides: List<PptxSlide>,
    val slideWidthEmu: Long = 9144000L,   // Default 4:3 (10" x 7.5") or 12192000L for 16:9
    val slideHeightEmu: Long = 6858000L,
    val title: String = "Presentation"
) {
    val slideCount: Int get() = slides.size
}

data class PptxSlide(
    val slideNumber: Int,
    val title: String? = null,
    val elements: List<SlideElement> = emptyList(),
    val backgroundColorHex: String? = null,
    val background: PptxBackground? = null
)

data class PptxBackground(
    val colorHex: String? = null,
    val gradientColorsHex: List<String>? = null,
    val isGradient: Boolean = false
)

sealed class SlideElement {
    abstract val xEmu: Long
    abstract val yEmu: Long
    abstract val wEmu: Long
    abstract val hEmu: Long
    abstract val rotationDeg: Float

    data class TextBox(
        override val xEmu: Long,
        override val yEmu: Long,
        override val wEmu: Long,
        override val hEmu: Long,
        val paragraphs: List<PptxParagraph>,
        val fillColorHex: String? = null,
        val gradientColorsHex: List<String>? = null,
        val borderColorHex: String? = null,
        val borderWidthPt: Float? = null,
        override val rotationDeg: Float = 0f
    ) : SlideElement() {
        fun plainText(): String = paragraphs.joinToString("\n") { it.plainText() }
    }

    data class ShapeBox(
        override val xEmu: Long,
        override val yEmu: Long,
        override val wEmu: Long,
        override val hEmu: Long,
        val fillColorHex: String? = null,
        val gradientColorsHex: List<String>? = null,
        val borderColorHex: String? = null,
        val borderWidthPt: Float? = null,
        val isRounded: Boolean = false,
        override val rotationDeg: Float = 0f
    ) : SlideElement()

    data class ImageBox(
        override val xEmu: Long,
        override val yEmu: Long,
        override val wEmu: Long,
        override val hEmu: Long,
        val bitmap: Bitmap?,
        val altText: String? = null,
        override val rotationDeg: Float = 0f
    ) : SlideElement()
}

data class PptxParagraph(
    val runs: List<PptxRun>,
    val alignment: PptxAlignment = PptxAlignment.LEFT,
    val isBullet: Boolean = false,
    val bulletChar: String? = null,
    val indentLevel: Int = 0
) {
    fun plainText(): String = runs.joinToString("") { it.text }
}

data class PptxRun(
    val text: String,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val fontSizePt: Float? = null,
    val colorHex: String? = null,
    val fontFamily: String? = null
)

enum class PptxAlignment {
    LEFT, CENTER, RIGHT, JUSTIFY
}
