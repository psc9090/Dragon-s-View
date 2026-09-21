// app/src/main/java/com/dragonview/app/viewer/pptx/SlideView.kt
package com.dragonview.app.viewer.pptx

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory

/**
 * Custom ViewGroup that renders a single PowerPoint slide.
 *
 * Scales PowerPoint native English Metric Units (EMU) coordinates proportionally
 * to the measured slide canvas size on mobile devices, ensuring faithful spatial layout
 * without clipping or distortion.
 */
class SlideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private var slideWidthEmu: Long = 9144000L
    private var slideHeightEmu: Long = 6858000L
    private var elements: List<SlideElement> = emptyList()

    init {
        // High-contrast clean presentation slide canvas
        setBackgroundColor(Color.WHITE)
        clipToOutline = true
    }

    fun setSlide(slide: PptxSlide, widthEmu: Long, heightEmu: Long) {
        this.slideWidthEmu = if (widthEmu > 0) widthEmu else 9144000L
        this.slideHeightEmu = if (heightEmu > 0) heightEmu else 6858000L
        this.elements = slide.elements

        if (slide.background?.isGradient == true && !slide.background.gradientColorsHex.isNullOrEmpty()) {
            val colors = slide.background.gradientColorsHex.mapNotNull { hex ->
                try { Color.parseColor(hex) } catch (_: Exception) { null }
            }.toIntArray()
            if (colors.size >= 2) {
                val gd = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    colors
                )
                background = gd
            } else if (colors.isNotEmpty()) {
                setBackgroundColor(colors[0])
            } else {
                setBackgroundColor(Color.WHITE)
            }
        } else {
            val bgColorHex = slide.background?.colorHex ?: slide.backgroundColorHex
            val bgColor = bgColorHex?.let {
                try {
                    Color.parseColor(it)
                } catch (_: Exception) {
                    Color.WHITE
                }
            } ?: Color.WHITE
            setBackgroundColor(bgColor)
        }

        removeAllViews()

        for (element in elements) {
            when (element) {
                is SlideElement.TextBox -> {
                    val textView = createTextView(element)
                    textView.tag = element
                    addView(textView)
                }
                is SlideElement.ShapeBox -> {
                    val shapeView = createShapeView(element)
                    shapeView.tag = element
                    addView(shapeView)
                }
                is SlideElement.ImageBox -> {
                    val imageView = createImageView(element)
                    imageView.tag = element
                    addView(imageView)
                }
            }
        }
        requestLayout()
        invalidate()
    }

    private fun createShapeView(shapeBox: SlideElement.ShapeBox): View {
        return View(context).apply {
            val gd: android.graphics.drawable.GradientDrawable
            if (shapeBox.gradientColorsHex != null && shapeBox.gradientColorsHex.size >= 2) {
                val colors = shapeBox.gradientColorsHex.mapNotNull {
                    try { Color.parseColor(it) } catch (_: Exception) { null }
                }.toIntArray()
                gd = if (colors.size >= 2) {
                    android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, colors)
                } else {
                    android.graphics.drawable.GradientDrawable().apply {
                        if (shapeBox.fillColorHex != null) {
                            try { setColor(Color.parseColor(shapeBox.fillColorHex)) } catch (_: Exception) {}
                        }
                    }
                }
            } else {
                gd = android.graphics.drawable.GradientDrawable()
                if (shapeBox.fillColorHex != null) {
                    try {
                        gd.setColor(Color.parseColor(shapeBox.fillColorHex))
                    } catch (_: Exception) {}
                }
            }
            if (shapeBox.borderColorHex != null) {
                try {
                    val strokeColor = Color.parseColor(shapeBox.borderColorHex)
                    val strokeWidthPx = shapeBox.borderWidthPt?.let { (it * context.resources.displayMetrics.density).toInt() } ?: 2
                    gd.setStroke(strokeWidthPx.coerceAtLeast(1), strokeColor)
                } catch (_: Exception) {}
            }
            if (shapeBox.isRounded) {
                gd.cornerRadius = 16f
            }
            background = gd
        }
    }

    private fun createTextView(textBox: SlideElement.TextBox): TextView {
        val textView = TextView(context).apply {
            setPadding(8, 6, 8, 6)
            includeFontPadding = false
            setTextColor(Color.parseColor("#1C1B1F"))
            if (textBox.gradientColorsHex != null && textBox.gradientColorsHex.size >= 2) {
                val colors = textBox.gradientColorsHex.mapNotNull {
                    try { Color.parseColor(it) } catch (_: Exception) { null }
                }.toIntArray()
                val gd = if (colors.size >= 2) {
                    android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, colors)
                } else {
                    android.graphics.drawable.GradientDrawable()
                }
                if (textBox.borderColorHex != null) {
                    try {
                        val strokeColor = Color.parseColor(textBox.borderColorHex)
                        val strokeWidthPx = textBox.borderWidthPt?.let { (it * context.resources.displayMetrics.density).toInt() } ?: 2
                        gd.setStroke(strokeWidthPx.coerceAtLeast(1), strokeColor)
                    } catch (_: Exception) {}
                }
                background = gd
            } else if (textBox.fillColorHex != null || textBox.borderColorHex != null) {
                val gd = android.graphics.drawable.GradientDrawable()
                if (textBox.fillColorHex != null) {
                    try {
                        gd.setColor(Color.parseColor(textBox.fillColorHex))
                    } catch (_: Exception) {}
                }
                if (textBox.borderColorHex != null) {
                    try {
                        val strokeColor = Color.parseColor(textBox.borderColorHex)
                        val strokeWidthPx = textBox.borderWidthPt?.let { (it * context.resources.displayMetrics.density).toInt() } ?: 2
                        gd.setStroke(strokeWidthPx.coerceAtLeast(1), strokeColor)
                    } catch (_: Exception) {}
                }
                background = gd
            }
        }

        // Compute alignment from first paragraph or left
        val firstAlign = textBox.paragraphs.firstOrNull()?.alignment ?: PptxAlignment.LEFT
        textView.gravity = when (firstAlign) {
            PptxAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
            PptxAlignment.RIGHT -> Gravity.END
            PptxAlignment.JUSTIFY -> Gravity.START
            PptxAlignment.LEFT -> Gravity.START
        }

        textView.text = formatSpannable(textBox, 0.001f)
        return textView
    }

    private fun formatSpannable(textBox: SlideElement.TextBox, scale: Float): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        for ((pIndex, paragraph) in textBox.paragraphs.withIndex()) {
            if (pIndex > 0) {
                ssb.append("\n")
            }

            if (paragraph.indentLevel > 0) {
                val indent = "    ".repeat(paragraph.indentLevel.coerceIn(0, 6))
                ssb.append(indent)
            }

            if (paragraph.isBullet) {
                val bChar = paragraph.bulletChar ?: when (paragraph.indentLevel) {
                    0 -> "•"
                    1 -> "◦"
                    2 -> "▪"
                    else -> "▫"
                }
                ssb.append("$bChar  ")
            }

            for (run in paragraph.runs) {
                val rStart = ssb.length
                ssb.append(run.text)
                val rEnd = ssb.length

                if (rStart < rEnd) {
                    if (run.isBold && run.isItalic) {
                        ssb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), rStart, rEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else if (run.isBold) {
                        ssb.setSpan(StyleSpan(Typeface.BOLD), rStart, rEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else if (run.isItalic) {
                        ssb.setSpan(StyleSpan(Typeface.ITALIC), rStart, rEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }

                    if (run.isUnderline) {
                        ssb.setSpan(UnderlineSpan(), rStart, rEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }

                    if (run.colorHex != null) {
                        try {
                            val color = Color.parseColor(run.colorHex)
                            ssb.setSpan(ForegroundColorSpan(color), rStart, rEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } catch (_: Exception) {}
                    }

                    if (run.fontFamily != null) {
                        try {
                            ssb.setSpan(android.text.style.TypefaceSpan(run.fontFamily), rStart, rEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } catch (_: Exception) {}
                    }

                    if (run.fontSizePt != null && run.fontSizePt > 0 && scale > 0f) {
                        val runPx = (run.fontSizePt * 12700f * scale).toInt().coerceIn(10, 200)
                        ssb.setSpan(AbsoluteSizeSpan(runPx, false), rStart, rEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }
        return ssb
    }

    private fun createImageView(imageBox: SlideElement.ImageBox): ImageView {
        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            if (imageBox.bitmap != null) {
                setImageBitmap(imageBox.bitmap)
            } else {
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            }
            contentDescription = imageBox.altText
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        var availableHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (availableWidth <= 0) {
            val dm = context.resources.displayMetrics
            availableWidth = dm.widthPixels.coerceAtLeast(320)
        }

        val targetAspect = slideHeightEmu.toDouble() / slideWidthEmu.toDouble()

        var finalWidth = availableWidth
        var finalHeight = (availableWidth * targetAspect).toInt()

        // Fit within available container bounds while preserving aspect ratio
        if (availableHeight > 0 && finalHeight > availableHeight) {
            finalHeight = availableHeight
            finalWidth = (availableHeight / targetAspect).toInt()
        }

        val scale = finalWidth.toFloat() / slideWidthEmu.toFloat()

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val element = child.tag as? SlideElement ?: continue

            val childWidthPx = (element.wEmu * scale).toInt().coerceAtLeast(1)
            val childHeightPx = (element.hEmu * scale).toInt().coerceAtLeast(1)

            // Scale text size dynamically based on slide width
            if (child is TextView && element is SlideElement.TextBox) {
                val primaryRun = element.paragraphs.firstOrNull()?.runs?.firstOrNull { it.text.isNotBlank() }
                val ptSize = primaryRun?.fontSizePt ?: 14f
                // 1 pt = 12700 EMUs
                val scaledPx = (ptSize * 12700f * scale).coerceIn(14f, 96f)
                child.setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledPx)
                child.text = formatSpannable(element, scale)

                // Text measures within bounded width, height wrap content or at least shape height
                child.measure(
                    MeasureSpec.makeMeasureSpec(childWidthPx, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeightPx, MeasureSpec.UNSPECIFIED)
                )
            } else {
                // Images and graphical elements are strictly bounded by their shape bounds
                child.measure(
                    MeasureSpec.makeMeasureSpec(childWidthPx, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeightPx, MeasureSpec.EXACTLY)
                )
            }
        }

        setMeasuredDimension(finalWidth, finalHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val currentWidth = r - l
        val scale = currentWidth.toFloat() / slideWidthEmu.toFloat()

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val element = child.tag as? SlideElement ?: continue

            val left = (element.xEmu * scale).toInt()
            val top = (element.yEmu * scale).toInt()
            val width = (element.wEmu * scale).toInt().coerceAtLeast(1)
            val targetHeight = (element.hEmu * scale).toInt().coerceAtLeast(1)
            val height = if (child is TextView) maxOf(targetHeight, child.measuredHeight) else targetHeight

            child.layout(left, top, left + width, top + height)
            if (element.rotationDeg != 0f) {
                child.pivotX = width / 2f
                child.pivotY = height / 2f
                child.rotation = element.rotationDeg
            } else {
                child.rotation = 0f
            }
        }
    }
}
