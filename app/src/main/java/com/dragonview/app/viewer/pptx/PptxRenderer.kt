// app/src/main/java/com/dragonview/app/viewer/pptx/PptxRenderer.kt
package com.dragonview.app.viewer.pptx

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.dragonview.app.performance.JankMonitor
import com.dragonview.app.performance.LookAheadPrefetcher
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatDetectionResult
import com.dragonview.app.ui.theme.DragonDarkBackground
import com.dragonview.app.ui.theme.DragonDarkBorder
import com.dragonview.app.ui.theme.DragonDarkSurface
import com.dragonview.app.ui.theme.DragonDarkSurfaceContainer
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
import com.dragonview.app.viewer.odp.OdpParser
import kotlin.math.roundToInt

/**
 * Modern Compose viewer for PowerPoint (.pptx) and OpenDocument (.odp, .otp, .fodp) presentations.
 * Uses ViewPager2 for horizontal slide swiping with proportional canvas rendering.
 * Fully upgraded to the Tactical HUD design paradigm.
 */
@Composable
fun PresentationRenderer(
    uri: Uri,
    detectionResult: FormatDetectionResult,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var presentation by remember { mutableStateOf<PptxPresentation?>(null) }
    var currentSlideIndex by remember { mutableIntStateOf(0) }
    var viewPagerRef by remember { mutableStateOf<ViewPager2?>(null) }
    var zoomScale by remember { mutableFloatStateOf(1.0f) }

    val scope = rememberCoroutineScope()
    val prefetcher = remember(presentation) {
        LookAheadPrefetcher(scope) { targetIndex ->
            presentation?.let { pres ->
                if (targetIndex in 0 until pres.slideCount) {
                    pres.slides[targetIndex].elements.size
                }
            }
        }
    }

    DisposableEffect(Unit) {
        JankMonitor.start()
        onDispose {
            JankMonitor.stop()
            prefetcher.cancelPending()
            PptxParser.pptxBitmapPool.clear()
        }
    }

    LaunchedEffect(uri) {
        isLoading = true
        errorMessage = null
        val result = when (detectionResult.format) {
            FileFormat.ODP, FileFormat.ODP_TEMPLATE, FileFormat.ODP_FLAT -> {
                OdpParser.parse(context, uri, detectionResult.format)
            }
            else -> {
                PptxParser.parse(context, uri)
            }
        }
        result.onSuccess { pres ->
            presentation = pres
            isLoading = false
        }.onFailure { error ->
            val fallbackMsg = if (detectionResult.format in listOf(FileFormat.ODP, FileFormat.ODP_TEMPLATE, FileFormat.ODP_FLAT)) {
                OdpParser.CORRUPTED_ERROR_MESSAGE
            } else {
                "This presentation file appears corrupted or uses an unsupported structure."
            }
            errorMessage = error.localizedMessage ?: fallbackMsg
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
                            Color.Transparent
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
                                text = "SLIDE RASTER ENGINE // VIEW-PAGER2 VIRTUALIZED",
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
                                        .testTag("pptx_back_button")
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
                                val currentTitle = presentation?.slides?.getOrNull(currentSlideIndex)?.title
                                    ?: presentation?.title ?: detectionResult.fileName
                                Text(
                                    text = currentTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = DragonDarkTextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 220.dp)
                                )
                                Text(
                                    text = "${detectionResult.format.label} • ${detectionResult.fileSize / 1024} KB",
                                    color = DragonPrimary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        // Slide Progress Badge
                        presentation?.let { pres ->
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
                                        text = "${currentSlideIndex + 1} / ${pres.slideCount}",
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
                                CircularProgressIndicator(
                                    color = DragonPrimary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Unpacking presentation slides...",
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
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = Color(0xFFEF5350),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Presentation Error",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = DragonDarkTextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = errorMessage ?: "This PPTX file appears corrupted or uses an unsupported structure.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = DragonDarkTextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    presentation != null -> {
                        val pres = presentation!!
                        val totalSlides = pres.slideCount

                        // Horizontal ViewPager2 slide canvas with robust zoom
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = zoomScale
                                    scaleY = zoomScale
                                }
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        zoomScale = (zoomScale * zoom).coerceIn(1.0f, 4.0f)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    val container = FrameLayout(ctx).apply {
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                    val viewPager = ViewPager2(ctx).apply {
                                        layoutParams = FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.MATCH_PARENT
                                        )
                                        orientation = ViewPager2.ORIENTATION_HORIZONTAL
                                        offscreenPageLimit = 1
                                        (getChildAt(0) as? RecyclerView)?.setItemViewCacheSize(2)
                                        val slideAdapter = PptxSlideAdapter(pres)
                                        adapter = slideAdapter
                                        Log.d(
                                            "PptxRenderer",
                                            "Attached adapter: parsed slideCount=${pres.slideCount}, adapter itemCount=${slideAdapter.itemCount}"
                                        )
                                        registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                                            override fun onPageSelected(position: Int) {
                                                super.onPageSelected(position)
                                                currentSlideIndex = position
                                                prefetcher.onPositionChanged(position, pres.slideCount)
                                            }
                                        })
                                        viewPagerRef = this
                                    }
                                    container.addView(viewPager)
                                    container
                                },
                                update = { container ->
                                    val viewPager = container.getChildAt(0) as? ViewPager2
                                    viewPagerRef = viewPager
                                    if (viewPager != null && viewPager.adapter == null) {
                                        viewPager.adapter = PptxSlideAdapter(pres)
                                    }
                                },
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
                                        Toast.makeText(context, "Exporting slide ${currentSlideIndex + 1}...", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = DragonSurfaceHigh.copy(alpha = 0.5f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Share Slide",
                                            tint = DragonDarkTextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Surface(
                                    onClick = {
                                        val slide = pres.slides.getOrNull(currentSlideIndex)
                                        val elemCount = slide?.elements?.size ?: 0
                                        Toast.makeText(
                                            context,
                                            "Slide ${currentSlideIndex + 1} of $totalSlides • $elemCount elements",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = DragonSurfaceHigh.copy(alpha = 0.5f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Slide Info",
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
                                // Slide navigation controls
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        onClick = {
                                            if (currentSlideIndex > 0) {
                                                viewPagerRef?.currentItem = currentSlideIndex - 1
                                            }
                                        },
                                        enabled = currentSlideIndex > 0,
                                        shape = CircleShape,
                                        color = DragonSurfaceHigh.copy(alpha = 0.7f),
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Previous Slide",
                                                tint = if (currentSlideIndex > 0) DragonDarkTextPrimary else DragonDarkTextMuted,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = "${currentSlideIndex + 1} / $totalSlides",
                                        color = DragonPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    )

                                    Surface(
                                        onClick = {
                                            if (currentSlideIndex < totalSlides - 1) {
                                                viewPagerRef?.currentItem = currentSlideIndex + 1
                                            }
                                        },
                                        enabled = currentSlideIndex < totalSlides - 1,
                                        shape = CircleShape,
                                        color = DragonSurfaceHigh.copy(alpha = 0.7f),
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = "Next Slide",
                                                tint = if (currentSlideIndex < totalSlides - 1) DragonDarkTextPrimary else DragonDarkTextMuted,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                // Zoom step controls
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
                                            .clickable { zoomScale = 1.0f }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )

                                    Surface(
                                        onClick = {
                                            zoomScale = (zoomScale * 1.25f).coerceAtMost(4.0f)
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

                                // Fit Screen / Reset button
                                Surface(
                                    onClick = { zoomScale = 1.0f },
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
                                            text = "Fit",
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
 * RecyclerView Adapter for ViewPager2 hosting SlideView.
 */
private class PptxSlideAdapter(
    private val presentation: PptxPresentation
) : RecyclerView.Adapter<PptxSlideAdapter.SlideViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
        Log.d("PptxSlideAdapter", "onCreateViewHolder called: parent=${parent.width}x${parent.height}")
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Center the slide canvas within the ViewPager page
            setPadding(8, 8, 8, 8)
        }

        val cardWrapper = FrameLayout(parent.context).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
            elevation = 6f
            setBackgroundColor(android.graphics.Color.WHITE)
        }

        val slideView = SlideView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        cardWrapper.addView(slideView)
        container.addView(cardWrapper)

        return SlideViewHolder(container, slideView)
    }

    override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
        Log.d("PptxSlideAdapter", "onBindViewHolder: binding slide $position of ${presentation.slideCount}")
        val slide = presentation.slides[position]
        holder.slideView.setSlide(
            slide = slide,
            widthEmu = presentation.slideWidthEmu,
            heightEmu = presentation.slideHeightEmu
        )
    }

    override fun getItemCount(): Int {
        val count = presentation.slideCount
        Log.d("PptxSlideAdapter", "getItemCount: returning $count")
        return count
    }

    class SlideViewHolder(
        itemView: View,
        val slideView: SlideView
    ) : RecyclerView.ViewHolder(itemView)
}

/**
 * Backward compatibility wrapper for PptxRenderer.
 */
@Composable
fun PptxRenderer(
    uri: Uri,
    detectionResult: FormatDetectionResult,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    PresentationRenderer(
        uri = uri,
        detectionResult = detectionResult,
        modifier = modifier,
        onBack = onBack
    )
}
