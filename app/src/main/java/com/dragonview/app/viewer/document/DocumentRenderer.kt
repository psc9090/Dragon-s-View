// app/src/main/java/com/dragonview/app/viewer/document/DocumentRenderer.kt
package com.dragonview.app.viewer.document

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
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
import android.graphics.drawable.GradientDrawable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.FrameLayout
import com.dragonview.app.performance.JankMonitor
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatDetectionResult
import com.dragonview.app.ui.theme.DragonDarkBackground
import com.dragonview.app.ui.theme.DragonDarkBorder
import com.dragonview.app.ui.theme.DragonDarkSurface
import com.dragonview.app.ui.theme.DragonDarkTextMuted
import com.dragonview.app.ui.theme.DragonDarkTextPrimary
import com.dragonview.app.ui.theme.DragonDarkTextSecondary
import com.dragonview.app.ui.theme.DragonFlame
import com.dragonview.app.ui.theme.DragonPrimary
import com.dragonview.app.ui.theme.DragonPrimaryContainer
import com.dragonview.app.ui.theme.DragonSecondary
import com.dragonview.app.ui.theme.DragonSecondaryContainer
import com.dragonview.app.ui.theme.DragonSurfaceDim
import com.dragonview.app.ui.theme.DragonSurfaceHigh
import com.dragonview.app.ui.theme.DragonSurfaceLowest
import com.dragonview.app.ui.theme.DragonTertiary
import com.dragonview.app.viewer.docx.DocxParser
import com.dragonview.app.viewer.odt.OdtParser
import com.dragonview.app.viewer.rtf.RtfParser
import kotlin.math.roundToInt

/**
 * Universal native document renderer for DOCX, RTF, and ODT.
 *
 * Performance highlights:
 * - Stacked white document pages with native screen-width text reflow and wrapping.
 * - Clear page separation with elevation shadows.
 * - Single SpannableStringBuilder pipeline applied per paragraph run.
 * - Background streaming parsing via Dispatchers.IO.
 * - Tactical UI paradigm with HUD telemetry, floating bottom hub, and right tool dock.
 */
@Composable
fun DocumentRenderer(
    uri: Uri,
    detectionResult: FormatDetectionResult,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var parsedDocument by remember { mutableStateOf<DocumentData?>(null) }
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(Unit) {
        JankMonitor.start()
        onDispose {
            JankMonitor.stop()
            DocxParser.docxBitmapPool.clear()
            OdtParser.odtBitmapPool.clear()
        }
    }

    LaunchedEffect(uri) {
        isLoading = true
        errorMessage = null

        val result = when (detectionResult.format) {
            FileFormat.RTF -> RtfParser.parse(context, uri)
            FileFormat.DOCX -> DocxParser.parse(context, uri)
            FileFormat.ODT, FileFormat.ODT_TEMPLATE, FileFormat.ODT_FLAT ->
                OdtParser.parse(context, uri, detectionResult.format)
            else -> {
                when (detectionResult.extension.lowercase()) {
                    "rtf" -> RtfParser.parse(context, uri)
                    "odt", "ott", "fodt" -> OdtParser.parse(context, uri, detectionResult.format)
                    else -> DocxParser.parse(context, uri)
                }
            }
        }

        if (result.isSuccess) {
            parsedDocument = result.getOrNull()
            isLoading = false
        } else {
            errorMessage = result.exceptionOrNull()?.localizedMessage
                ?: "This document appears corrupted or invalid."
            isLoading = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DragonSurfaceDim)
    ) {
        // Atmospheric Ambient Background Glows
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = 380.dp, height = 180.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            DragonPrimaryContainer.copy(alpha = 0.12f),
                            ComposeColor.Transparent
                        )
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // Header: Status bar insets + Tactical Top Bar
            Surface(
                color = DragonSurfaceLowest.copy(alpha = 0.88f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    // System status micro-tier
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(DragonPrimary)
                            )
                            Text(
                                text = "NATIVE DOCUMENT STREAM // ZERO-WEBVIEW",
                                color = DragonPrimary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = DragonTertiary,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = "E2EE LOCAL",
                                color = DragonDarkTextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Tactical App & File Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (onBack != null) {
                                Surface(
                                    onClick = onBack,
                                    shape = CircleShape,
                                    color = DragonSurfaceHigh.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .size(38.dp)
                                        .testTag("doc_back_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back to Home",
                                            tint = DragonDarkTextPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            Column {
                                Text(
                                    text = detectionResult.fileName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = DragonDarkTextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 220.dp)
                                )
                                Text(
                                    text = "${parsedDocument?.formatLabel ?: detectionResult.format.label} • ${detectionResult.fileSize / 1024} KB",
                                    color = DragonPrimary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        // Paragraph / Table Count Badge
                        parsedDocument?.let { doc ->
                            Surface(
                                shape = CircleShape,
                                color = DragonSecondaryContainer.copy(alpha = 0.45f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DragonPrimary.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "${doc.paragraphCount} §",
                                        color = DragonPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Main Viewer Body
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = DragonPrimary)
                                Spacer(modifier = Modifier.size(16.dp))
                                Text(
                                    text = "Streaming document structure...",
                                    color = DragonDarkTextSecondary,
                                    style = MaterialTheme.typography.bodyMedium
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
                            Surface(
                                color = DragonDarkSurface,
                                shape = RoundedCornerShape(16.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = ComposeColor(0xFFFF5252),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.size(12.dp))
                                    Text(
                                        text = "Unable to Display Document",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = DragonDarkTextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.size(8.dp))
                                    Text(
                                        text = errorMessage ?: "Corrupted file structure.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = DragonDarkTextSecondary
                                    )
                                }
                            }
                        }
                    }

                    parsedDocument != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(ComposeColor(0xFFE8EAED))
                                .graphicsLayer {
                                    scaleX = zoomScale
                                    scaleY = zoomScale
                                }
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        zoomScale = (zoomScale * zoom).coerceIn(1.0f, 3.5f)
                                    }
                                }
                        ) {
                            AndroidDocumentRecyclerView(
                                document = parsedDocument!!,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Floating Right Edge Quick Tool Dock
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = DragonSurfaceLowest.copy(alpha = 0.85f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder.copy(alpha = 0.5f)),
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 16.dp, end = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    onClick = {
                                        Toast.makeText(context, "Exporting ${detectionResult.fileName}...", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = DragonSurfaceHigh.copy(alpha = 0.5f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Share Document",
                                            tint = DragonDarkTextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Surface(
                                    onClick = {
                                        val doc = parsedDocument!!
                                        val stats = "${doc.paragraphCount} paragraphs, ${doc.tableCount} tables, ${doc.imageCount} images"
                                        Toast.makeText(context, stats, Toast.LENGTH_LONG).show()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = DragonSurfaceHigh.copy(alpha = 0.5f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Document Info",
                                            tint = DragonDarkTextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Floating Tactical Bottom Controls Hub
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = DragonSurfaceLowest.copy(alpha = 0.90f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder.copy(alpha = 0.6f)),
                            shadowElevation = 12.dp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .fillMaxWidth(0.92f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Zoom step buttons
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        onClick = {
                                            zoomScale = (zoomScale / 1.25f).coerceAtLeast(1.0f)
                                        },
                                        shape = CircleShape,
                                        color = DragonSurfaceHigh.copy(alpha = 0.7f),
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Remove,
                                                contentDescription = "Zoom Out",
                                                tint = DragonDarkTextPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = "${(zoomScale * 100).roundToInt()}%",
                                        color = DragonPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable {
                                                zoomScale = 1.0f
                                                panOffset = Offset.Zero
                                            }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )

                                    Surface(
                                        onClick = {
                                            zoomScale = (zoomScale * 1.25f).coerceAtMost(3.5f)
                                        },
                                        shape = CircleShape,
                                        color = DragonSurfaceHigh.copy(alpha = 0.7f),
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Zoom In",
                                                tint = DragonDarkTextPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }

                                // Fit Width / Reset button
                                Surface(
                                    onClick = {
                                        zoomScale = 1.0f
                                        panOffset = Offset.Zero
                                    },
                                    shape = CircleShape,
                                    color = DragonSurfaceHigh.copy(alpha = 0.7f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FitScreen,
                                            contentDescription = null,
                                            tint = DragonDarkTextSecondary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = "Fit Width",
                                            color = DragonDarkTextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * AndroidView hosting stacked, paginated document pages with continuous vertical scrolling.
 */
@Composable
private fun AndroidDocumentRecyclerView(
    document: DocumentData,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                setItemViewCacheSize(3)
                setBackgroundColor(Color.parseColor("#E8EAED"))
                setPadding(0, 16, 0, 32)
                clipToPadding = false
                val pages = DocumentPaginator.paginate(document.elements)
                val docAdapter = DocumentPageAdapter(ctx, pages)
                adapter = docAdapter
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        super.onScrollStateChanged(recyclerView, newState)
                        val flinging = (newState == RecyclerView.SCROLL_STATE_SETTLING)
                        if (docAdapter.isFlinging != flinging) {
                            docAdapter.isFlinging = flinging
                        }
                    }
                })
            }
        },
        update = { recyclerView ->
            val pages = DocumentPaginator.paginate(document.elements)
            (recyclerView.adapter as? DocumentPageAdapter)?.updatePages(pages)
        }
    )
}

/**
 * Logical representation of a single document page.
 */
data class DocumentPage(
    val pageNumber: Int,
    val totalPages: Int,
    val elements: List<DocumentElement>
)

/**
 * Paginates document elements into structured reading pages.
 */
object DocumentPaginator {
    private const val TARGET_PAGE_WEIGHT = 2200

    fun paginate(elements: List<DocumentElement>): List<DocumentPage> {
        if (elements.isEmpty()) {
            return listOf(DocumentPage(pageNumber = 1, totalPages = 1, elements = emptyList()))
        }

        val pages = mutableListOf<List<DocumentElement>>()
        val currentPage = mutableListOf<DocumentElement>()
        var currentWeight = 0

        for (element in elements) {
            val elementWeight = when (element) {
                is DocumentElement.Paragraph -> {
                    val textLen = element.runs.sumOf { it.text.length }
                    val base = if (element.isHeading) 250 else 120
                    base + textLen
                }
                is DocumentElement.Table -> {
                    250 + (element.rows.size * 180)
                }
                is DocumentElement.Image -> {
                    650
                }
            }

            if (currentPage.isNotEmpty() && currentWeight + elementWeight > TARGET_PAGE_WEIGHT) {
                pages.add(currentPage.toList())
                currentPage.clear()
                currentWeight = 0
            }

            currentPage.add(element)
            currentWeight += elementWeight
        }

        if (currentPage.isNotEmpty()) {
            pages.add(currentPage.toList())
        }

        val total = pages.size
        return pages.mapIndexed { index, pageElements ->
            DocumentPage(pageNumber = index + 1, totalPages = total, elements = pageElements)
        }
    }
}

/**
 * RecyclerView Adapter hosting stacked white document pages with clear separation and shadows.
 */
class DocumentPageAdapter(
    private val context: Context,
    private var pages: List<DocumentPage>
) : RecyclerView.Adapter<DocumentPageViewHolder>() {

    var isFlinging: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    notifyItemRangeChanged(0, itemCount)
                }
            }
        }

    fun updatePages(newPages: List<DocumentPage>) {
        this.pages = newPages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentPageViewHolder {
        val density = parent.context.resources.displayMetrics.density

        val pageCard = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            val lp = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val hMargin = (12 * density).toInt()
                val vMargin = (10 * density).toInt()
                setMargins(hMargin, vMargin, hMargin, vMargin)
            }
            layoutParams = lp
            elevation = 4 * density

            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.parseColor("#E2E8F0"))
                cornerRadius = 6 * density
            }

            val hPad = (20 * density).toInt()
            val vPadTop = (24 * density).toInt()
            val vPadBottom = (18 * density).toInt()
            setPadding(hPad, vPadTop, hPad, vPadBottom)
        }

        val elementsContainer = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        pageCard.addView(elementsContainer)

        val footer = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (20 * density).toInt()
            }
            gravity = Gravity.CENTER_HORIZONTAL
            textSize = 11f
            setTextColor(Color.parseColor("#9CA3AF"))
        }
        pageCard.addView(footer)

        return DocumentPageViewHolder(pageCard, elementsContainer, footer)
    }

    override fun onBindViewHolder(holder: DocumentPageViewHolder, position: Int) {
        holder.bind(pages[position], isFlinging)
    }

    override fun getItemCount(): Int = pages.size
}

/**
 * ViewHolder representing a single white paper sheet page in the document.
 */
class DocumentPageViewHolder(
    itemView: View,
    private val elementsContainer: LinearLayout,
    private val footer: TextView
) : RecyclerView.ViewHolder(itemView) {

    fun bind(page: DocumentPage, isFlinging: Boolean) {
        val context = elementsContainer.context
        val density = context.resources.displayMetrics.density

        elementsContainer.removeAllViews()

        for (element in page.elements) {
            when (element) {
                is DocumentElement.Paragraph -> {
                    val textView = TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, (4 * density).toInt(), 0, (8 * density).toInt())
                        }
                        setTextColor(Color.parseColor("#1A1A1A"))
                        setTextIsSelectable(true)
                        setLineSpacing(4f * density, 1.25f)
                    }
                    DocumentElementBinder.bindParagraph(element, textView, isFlinging)
                    elementsContainer.addView(textView)
                }

                is DocumentElement.Table -> {
                    val tableContainer = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, (12 * density).toInt(), 0, (16 * density).toInt())
                        }
                        setPadding(1, 1, 1, 1)
                        setBackgroundColor(Color.parseColor("#D1D5DB"))
                    }
                    DocumentElementBinder.bindTable(element, tableContainer)
                    elementsContainer.addView(tableContainer)
                }

                is DocumentElement.Image -> {
                    if (element.bitmap != null && !isFlinging) {
                        val imageView = ImageView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, (12 * density).toInt(), 0, (16 * density).toInt())
                            }
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageBitmap(element.bitmap)
                            contentDescription = element.altText ?: "Document Image"
                        }
                        elementsContainer.addView(imageView)
                    }
                }
            }
        }

        footer.text = "Page ${page.pageNumber} of ${page.totalPages}"
    }
}

/**
 * Shared styling and layout binding logic for document paragraphs, tables, and runs.
 */
object DocumentElementBinder {

    fun bindParagraph(paragraph: DocumentElement.Paragraph, textView: TextView, isFlinging: Boolean) {
        if (isFlinging) {
            textView.textSize = 15f
            textView.text = paragraph.plainText()
            return
        }

        val ssb = SpannableStringBuilder()

        if (paragraph.isBullet) {
            ssb.append(" •  ")
        }

        val baseFontSize = 15f
        val scaledDensity = textView.resources.displayMetrics.scaledDensity

        for (run in paragraph.runs) {
            val start = ssb.length
            ssb.append(run.text)
            val end = ssb.length

            if (run.isBold && run.isItalic) {
                ssb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (run.isBold) {
                ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (run.isItalic) {
                ssb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (run.isUnderline) {
                ssb.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (run.isStrike) {
                ssb.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (run.colorHex != null) {
                try {
                    val color = Color.parseColor(run.colorHex)
                    ssb.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } catch (_: Exception) { }
            }

            if (run.highlightColorHex != null) {
                try {
                    val hColor = Color.parseColor(run.highlightColorHex)
                    ssb.setSpan(BackgroundColorSpan(hColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } catch (_: Exception) { }
            }

            val targetSizeSp = run.fontSizeSp ?: baseFontSize
            val px = (targetSizeSp * scaledDensity).toInt()
            ssb.setSpan(AbsoluteSizeSpan(px), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (paragraph.isHeading) {
            val headingScale = when (paragraph.headingLevel) {
                1 -> 22f
                2 -> 19f
                3 -> 17f
                else -> 16f
            }
            val headingPx = (headingScale * scaledDensity).toInt()
            ssb.setSpan(AbsoluteSizeSpan(headingPx), 0, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(StyleSpan(Typeface.BOLD), 0, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(ForegroundColorSpan(Color.parseColor("#111827")), 0, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        textView.text = ssb

        textView.gravity = when (paragraph.alignment) {
            DocumentAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
            DocumentAlignment.RIGHT -> Gravity.END
            DocumentAlignment.JUSTIFY -> Gravity.START
            DocumentAlignment.LEFT -> Gravity.START
        }
    }

    fun bindTable(table: DocumentElement.Table, container: LinearLayout) {
        container.removeAllViews()

        for (row in table.rows) {
            val rowLayout = LinearLayout(container.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            for (cell in row.cells) {
                val cellLayout = LinearLayout(container.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        setMargins(1, 1, 1, 1)
                    }
                    setPadding(8, 8, 8, 8)
                    val bgColor = cell.backgroundColorHex?.let {
                        try { Color.parseColor(it) } catch (_: Exception) { null }
                    } ?: Color.WHITE
                    setBackgroundColor(bgColor)
                }

                for (paragraph in cell.paragraphs) {
                    val cellTextView = TextView(container.context).apply {
                        setTextColor(Color.parseColor("#1A1A1A"))
                        textSize = 12f
                        text = paragraph.plainText()
                    }
                    cellLayout.addView(cellTextView)
                }

                rowLayout.addView(cellLayout)
            }

            container.addView(rowLayout)
        }
    }
}
