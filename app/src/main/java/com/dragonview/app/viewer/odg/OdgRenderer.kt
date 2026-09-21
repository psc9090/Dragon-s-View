// app/src/main/java/com/dragonview/app/viewer/odg/OdgRenderer.kt
package com.dragonview.app.viewer.odg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.otaliastudios.zoom.ZoomLayout
import com.dragonview.app.ui.ZoomHelper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.PathParser
import com.dragonview.app.performance.JankMonitor
import com.dragonview.app.router.FormatDetectionResult
import com.dragonview.app.ui.theme.DragonDarkBackground
import com.dragonview.app.ui.theme.DragonDarkSurface
import com.dragonview.app.ui.theme.DragonDarkTextMuted
import com.dragonview.app.ui.theme.DragonDarkTextPrimary
import com.dragonview.app.ui.theme.DragonDarkTextSecondary
import com.dragonview.app.ui.theme.DragonFlame
import kotlin.math.roundToInt

/**
 * OpenDocument Drawing (.odg, .otg, .fodg) Canvas-based Renderer.
 *
 * Features:
 * - Direct vector Canvas rendering for Rect, Ellipse, Line, Polygon, Path, Text, Image, and Group.
 * - Group transform hierarchy with save/concat/restore matrix stack.
 * - SVG Path data rendering via PathParser with cached Path objects.
 * - Multi-touch zoom and pan gesture support with transform-commit optimization.
 * - Graceful element-level error recovery and top-level corrupt file trap.
 */
@Composable
fun OdgRenderer(
    uri: Uri,
    detectionResult: FormatDetectionResult,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var odgDoc by remember { mutableStateOf<OdgDocument?>(null) }

    var scale by remember { mutableFloatStateOf(1.0f) }
    var zoomLayoutRef by remember { mutableStateOf<ZoomLayout?>(null) }

    DisposableEffect(Unit) {
        JankMonitor.start()
        onDispose {
            JankMonitor.stop()
        }
    }

    LaunchedEffect(uri) {
        isLoading = true
        errorMessage = null
        val result = OdgParser.parse(context, uri, detectionResult.format)
        result.onSuccess { doc ->
            odgDoc = doc
            isLoading = false
        }.onFailure { err ->
            errorMessage = err.localizedMessage ?: OdgParser.CORRUPTED_ERROR_MESSAGE
            isLoading = false
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = DragonDarkBackground
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Toolbar
            Surface(
                color = DragonDarkSurface,
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("odg_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = DragonDarkTextPrimary
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = odgDoc?.title ?: detectionResult.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = DragonDarkTextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = detectionResult.format.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = ComposeColor(0xFF00ACC1),
                                fontWeight = FontWeight.Bold
                            )
                            if (odgDoc != null) {
                                Text(
                                    text = "${odgDoc!!.elements.size} elements",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DragonDarkTextMuted
                                )
                            }
                        }
                    }

                    // Zoom Controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${(scale * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = DragonDarkTextSecondary,
                            modifier = Modifier
                                .clickable {
                                    zoomLayoutRef?.engine?.zoomTo(1.0f, true)
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                        IconButton(
                            onClick = {
                                zoomLayoutRef?.engine?.let {
                                    val nextZoom = (it.realZoom / 1.2f).coerceAtLeast(1.0f)
                                    it.zoomTo(nextZoom, true)
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("-", color = DragonDarkTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = {
                                zoomLayoutRef?.engine?.let {
                                    val nextZoom = (it.realZoom * 1.2f).coerceAtMost(5.0f)
                                    it.zoomTo(nextZoom, true)
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("+", color = DragonDarkTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Main Canvas Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor(0xFF130708))
            ) {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = DragonFlame)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Rendering OpenDocument Drawing...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = DragonDarkTextSecondary
                                )
                            }
                        }
                    }

                    errorMessage != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Error",
                                    tint = DragonFlame,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = errorMessage!!,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = DragonDarkTextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    odgDoc != null -> {
                        AndroidView(
                            factory = { ctx ->
                                val canvasView = OdgCanvasView(ctx).apply {
                                    tag = "OdgCanvasView_Content"
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    setDocument(odgDoc!!)
                                }
                                ZoomHelper.createZoomLayout(
                                    context = ctx,
                                    contentView = canvasView,
                                    tag = "ZoomLayout_Odg",
                                    onZoomChanged = { scale = it }
                                ).also {
                                    zoomLayoutRef = it
                                }
                            },
                            update = { zoomLayout ->
                                val canvasView = zoomLayout.findViewWithTag<OdgCanvasView>("OdgCanvasView_Content")
                                canvasView?.setDocument(odgDoc!!)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Custom Canvas View for high-performance ODG rendering.
 */
class OdgCanvasView(context: Context) : View(context) {

    private var document: OdgDocument? = null
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pageBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B0B0E")
        style = Paint.Style.FILL
    }
    private val pageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B151C")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // Path cache to avoid parsing SVG paths during frequent redraws
    private val pathCache = mutableMapOf<String, Path?>()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setDocument(doc: OdgDocument) {
        this.document = doc
        pathCache.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val doc = document ?: return

        // Center the page within the view
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val docW = doc.pageWidthPx
        val docH = doc.pageHeightPx

        // Compute fitting scale to center on screen
        val fitScale = (viewW / docW).coerceAtMost(viewH / docH) * 0.92f
        val startX = (viewW - docW * fitScale) / 2f
        val startY = (viewH - docH * fitScale) / 2f

        canvas.save()
        canvas.translate(startX, startY)
        canvas.scale(fitScale, fitScale)

        // Draw drawing page surface
        val pageRect = RectF(0f, 0f, docW, docH)
        canvas.drawRoundRect(pageRect, 8f, 8f, pageBgPaint)
        canvas.drawRoundRect(pageRect, 8f, 8f, pageBorderPaint)

        // Render shapes with group hierarchy
        for (element in doc.elements) {
            drawElement(canvas, element)
        }

        canvas.restore()
    }

    private fun drawElement(canvas: Canvas, element: DrawingElement) {
        val hasTransform = element.transform != null
        if (hasTransform) {
            canvas.save()
            canvas.concat(element.transform)
        }

        try {
            when (element) {
                is DrawingElement.Rect -> drawRect(canvas, element)
                is DrawingElement.Ellipse -> drawEllipse(canvas, element)
                is DrawingElement.Line -> drawLine(canvas, element)
                is DrawingElement.Polygon -> drawPolygon(canvas, element)
                is DrawingElement.Path -> drawPath(canvas, element)
                is DrawingElement.TextBox -> drawTextBox(canvas, element)
                is DrawingElement.Image -> drawImage(canvas, element)
                is DrawingElement.Group -> drawGroup(canvas, element)
            }
        } catch (e: Exception) {
            Log.w("OdgCanvasView", "Error drawing element: ${e.message}")
        }

        if (hasTransform) {
            canvas.restore()
        }
    }

    private fun drawRect(canvas: Canvas, rect: DrawingElement.Rect) {
        val rectF = RectF(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height)

        if (rect.fill != null) {
            applyFill(rect.fill)
            if (rect.cornerRadius > 0f) {
                canvas.drawRoundRect(rectF, rect.cornerRadius, rect.cornerRadius, fillPaint)
            } else {
                canvas.drawRect(rectF, fillPaint)
            }
        }

        if (rect.stroke != null) {
            applyStroke(rect.stroke)
            if (rect.cornerRadius > 0f) {
                canvas.drawRoundRect(rectF, rect.cornerRadius, rect.cornerRadius, strokePaint)
            } else {
                canvas.drawRect(rectF, strokePaint)
            }
        }

        if (rect.text != null) {
            drawParagraphsInRect(canvas, rectF, rect.text.paragraphs)
        }
    }

    private fun drawEllipse(canvas: Canvas, ellipse: DrawingElement.Ellipse) {
        val rectF = RectF(
            ellipse.cx - ellipse.rx,
            ellipse.cy - ellipse.ry,
            ellipse.cx + ellipse.rx,
            ellipse.cy + ellipse.ry
        )

        if (ellipse.fill != null) {
            applyFill(ellipse.fill)
            canvas.drawOval(rectF, fillPaint)
        }

        if (ellipse.stroke != null) {
            applyStroke(ellipse.stroke)
            canvas.drawOval(rectF, strokePaint)
        }

        if (ellipse.text != null) {
            drawParagraphsInRect(canvas, rectF, ellipse.text.paragraphs)
        }
    }

    private fun drawLine(canvas: Canvas, line: DrawingElement.Line) {
        if (line.stroke != null) {
            applyStroke(line.stroke)
            canvas.drawLine(line.x1, line.y1, line.x2, line.y2, strokePaint)
        }
    }

    private fun drawPolygon(canvas: Canvas, polygon: DrawingElement.Polygon) {
        if (polygon.points.isEmpty()) return
        val path = Path()
        val first = polygon.points[0]
        path.moveTo(first.x, first.y)
        for (i in 1 until polygon.points.size) {
            val pt = polygon.points[i]
            path.lineTo(pt.x, pt.y)
        }
        if (polygon.isClosed) {
            path.close()
        }

        if (polygon.fill != null && polygon.isClosed) {
            applyFill(polygon.fill)
            canvas.drawPath(path, fillPaint)
        }

        if (polygon.stroke != null) {
            applyStroke(polygon.stroke)
            canvas.drawPath(path, strokePaint)
        }

        if (polygon.text != null) {
            val bounds = RectF()
            path.computeBounds(bounds, true)
            drawParagraphsInRect(canvas, bounds, polygon.text.paragraphs)
        }
    }

    private fun drawPath(canvas: Canvas, pathElem: DrawingElement.Path) {
        val cached = pathCache.getOrPut(pathElem.svgPathData) {
            try {
                PathParser.createPathFromPathData(pathElem.svgPathData)
            } catch (e: Exception) {
                Log.w("OdgCanvasView", "Unsupported SVG path data: ${e.message}")
                null
            }
        } ?: return

        canvas.save()
        if (pathElem.x != 0f || pathElem.y != 0f) {
            canvas.translate(pathElem.x, pathElem.y)
        }

        if (pathElem.viewBox != null && pathElem.width > 0f && pathElem.height > 0f) {
            val sx = pathElem.width / pathElem.viewBox.width()
            val sy = pathElem.height / pathElem.viewBox.height()
            canvas.scale(sx, sy)
        }

        if (pathElem.fill != null) {
            applyFill(pathElem.fill)
            canvas.drawPath(cached, fillPaint)
        }

        if (pathElem.stroke != null) {
            applyStroke(pathElem.stroke)
            canvas.drawPath(cached, strokePaint)
        }

        canvas.restore()

        if (pathElem.text != null) {
            val bounds = RectF(pathElem.x, pathElem.y, pathElem.x + pathElem.width, pathElem.y + pathElem.height)
            drawParagraphsInRect(canvas, bounds, pathElem.text.paragraphs)
        }
    }

    private fun drawTextBox(canvas: Canvas, textBox: DrawingElement.TextBox) {
        val rectF = RectF(textBox.x, textBox.y, textBox.x + textBox.width, textBox.y + textBox.height)

        if (textBox.fill != null) {
            applyFill(textBox.fill)
            canvas.drawRect(rectF, fillPaint)
        }

        if (textBox.stroke != null) {
            applyStroke(textBox.stroke)
            canvas.drawRect(rectF, strokePaint)
        }

        drawParagraphsInRect(canvas, rectF, textBox.paragraphs)
    }

    private fun drawImage(canvas: Canvas, image: DrawingElement.Image) {
        val rectF = RectF(image.x, image.y, image.x + image.width, image.y + image.height)
        val bitmap = image.bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            canvas.drawBitmap(bitmap, null, rectF, imagePaint)
        } else {
            // Placeholder border for unresolved image
            strokePaint.color = Color.parseColor("#4A1F26")
            strokePaint.strokeWidth = 1.5f
            strokePaint.pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
            canvas.drawRect(rectF, strokePaint)
        }
    }

    private fun drawGroup(canvas: Canvas, group: DrawingElement.Group) {
        // Group transform is handled by drawElement calling canvas.concat(transform)
        for (child in group.children) {
            drawElement(canvas, child)
        }
    }

    private fun drawParagraphsInRect(canvas: Canvas, bounds: RectF, paragraphs: List<OdgParagraph>) {
        if (paragraphs.isEmpty() || bounds.width() <= 0f) return

        val padding = 8f
        val availWidth = (bounds.width() - padding * 2).toInt().coerceAtLeast(10)
        var curY = bounds.top + padding

        for (para in paragraphs) {
            val sb = StringBuilder()
            var bold = false
            var italic = false
            var fontSize = 12f
            var colorHex = "#FDE8EA"

            for (run in para.runs) {
                sb.append(run.text)
                if (run.isBold) bold = true
                if (run.isItalic) italic = true
                if (run.fontSizePt > fontSize) fontSize = run.fontSizePt
                colorHex = run.colorHex
            }

            val text = sb.toString()
            if (text.isBlank()) continue

            textPaint.color = parseColorSafe(colorHex)
            textPaint.textSize = fontSize * 1.333f // convert pt to px
            textPaint.isFakeBoldText = bold
            textPaint.textSkewX = if (italic) -0.25f else 0f

            val align = when (para.alignment) {
                OdgAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                OdgAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }

            val staticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(text, 0, text.length, textPaint, availWidth)
                    .setAlignment(align)
                    .setLineSpacing(2f, 1.15f)
                    .setIncludePad(false)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(text, textPaint, availWidth, align, 1.15f, 2f, false)
            }

            if (curY + staticLayout.height > bounds.bottom) break

            canvas.save()
            canvas.translate(bounds.left + padding, curY)
            staticLayout.draw(canvas)
            canvas.restore()

            curY += staticLayout.height + 4f
        }
    }

    private fun applyFill(fill: OdgFill) {
        fillPaint.color = parseColorSafe(fill.colorHex)
        fillPaint.alpha = (fill.opacity.coerceIn(0f, 1f) * 255).toInt()
    }

    private fun applyStroke(stroke: OdgStroke) {
        strokePaint.color = parseColorSafe(stroke.colorHex)
        strokePaint.strokeWidth = stroke.widthPx.coerceAtLeast(0.5f)
        strokePaint.alpha = (stroke.opacity.coerceIn(0f, 1f) * 255).toInt()
        strokePaint.pathEffect = if (stroke.isDashed) DashPathEffect(floatArrayOf(8f, 8f), 0f) else null
    }

    private fun parseColorSafe(colorHex: String): Int {
        return try {
            Color.parseColor(colorHex)
        } catch (_: Exception) {
            Color.WHITE
        }
    }
}

private fun Int.toComposeColor(): ComposeColor = ComposeColor(this)
