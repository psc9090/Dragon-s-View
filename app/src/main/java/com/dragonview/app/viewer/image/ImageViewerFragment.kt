// app/src/main/java/com/dragonview/app/viewer/image/ImageViewerFragment.kt
package com.dragonview.app.viewer.image

import android.content.Intent
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import com.dragonview.app.performance.JankMonitor
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
import com.github.chrisbanes.photoview.PhotoView
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Standard Fragment wrapper for DragonView's image viewing engine.
 */
class ImageViewerFragment : Fragment() {

    companion object {
        private const val ARG_URI = "arg_uri"
        private const val ARG_FILE_NAME = "arg_file_name"
        private const val ARG_EXTENSION = "arg_extension"
        private const val ARG_FILE_SIZE = "arg_file_size"

        fun newInstance(
            uri: Uri,
            fileName: String,
            extension: String,
            fileSize: Long
        ): ImageViewerFragment {
            return ImageViewerFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_URI, uri)
                    putString(ARG_FILE_NAME, fileName)
                    putString(ARG_EXTENSION, extension)
                    putLong(ARG_FILE_SIZE, fileSize)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val uri = arguments?.getParcelable<Uri>(ARG_URI) ?: Uri.EMPTY
        val fileName = arguments?.getString(ARG_FILE_NAME) ?: "Image"
        val extension = arguments?.getString(ARG_EXTENSION) ?: ""
        val fileSize = arguments?.getLong(ARG_FILE_SIZE) ?: -1L

        val imageType = ImageType.fromExtension(extension) ?: ImageType.JPEG
        val detectionResult = FormatDetectionResult(
            format = com.dragonview.app.router.FileFormat.IMAGE,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = null,
            extension = extension,
            imageType = imageType
        )

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ImageViewer(
                    uri = uri,
                    detectionResult = detectionResult,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Core Tactical Jetpack Compose interactive Image Viewer component.
 *
 * Implements:
 * - HUD Micro-badge with pixel telemetry & sub-sampling indicators
 * - High-contrast glassmorphic top header with resolution/format tags
 * - Floating tactical bottom controls hub with precision zoom +/- & Fit Width
 * - Floating right-edge tool dock (Share, Details/Info)
 * - Memory-disciplined inSampleSize bounds checking to prevent OOM
 */
@Composable
fun ImageViewer(
    uri: Uri,
    detectionResult: FormatDetectionResult,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loadedImage by remember { mutableStateOf<LoadedImageData?>(null) }

    val imageType = detectionResult.imageType
        ?: ImageType.fromExtension(detectionResult.extension)
        ?: ImageType.JPEG

    // Decode image off the main thread
    LaunchedEffect(uri) {
        isLoading = true
        errorMessage = null
        val result = ImageDecoderHelper.decode(
            context = context,
            uri = uri,
            type = imageType,
            fileSize = detectionResult.fileSize
        )
        if (result.isSuccess) {
            loadedImage = result.getOrNull()
            isLoading = false
        } else {
            val ex = result.exceptionOrNull()
            android.util.Log.e("ImageViewer", "Failed to decode image from uri: $uri (type: $imageType)", ex)
            errorMessage = ex?.let {
                if (!it.message.isNullOrBlank()) "${it.javaClass.simpleName}: ${it.message}"
                else "${it.javaClass.simpleName}: Unable to decode image data"
            } ?: "This image file appears corrupted or uses an unsupported variant"
            isLoading = false
        }
    }

    // Free memory when leaving screen
    DisposableEffect(uri) {
        JankMonitor.start()
        onDispose {
            JankMonitor.stop()
            loadedImage?.recycle()
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
                                text = "HARDWARE RASTER // SUB-SAMPLED DECODE",
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
                                text = "E2EE SEALED",
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
                                        .testTag("image_back_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
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
                                    text = "${imageType.badge} • ${detectionResult.fileSize / 1024} KB",
                                    color = DragonPrimary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        // Resolution Badge
                        loadedImage?.let { img ->
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
                                        text = "${img.width}×${img.height}",
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
                                    text = "Subsampling and decoding image...",
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
                                        tint = Color(0xFFFF5252),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.size(12.dp))
                                    Text(
                                        text = "Unable to Display Image",
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
                    loadedImage != null -> {
                        ImageInteractiveCanvas(
                            uri = uri,
                            loadedImage = loadedImage!!,
                            fileName = detectionResult.fileName
                        )
                    }
                }
            }
        }
    }
}

/**
 * Interactive pinch-to-zoom and pan viewport for images powered by PhotoView.
 */
@Composable
private fun ImageInteractiveCanvas(
    uri: Uri,
    loadedImage: LoadedImageData,
    fileName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentScale by remember { mutableFloatStateOf(1f) }
    var photoViewRef by remember { mutableStateOf<PhotoView?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // GIF animation state
    var isGifPlaying by remember {
        mutableStateOf(
            if (loadedImage is LoadedImageData.AnimatedGifImage) !loadedImage.isLargeGif else false
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DragonSurfaceDim)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx: android.content.Context ->
                PhotoView(ctx).apply {
                    tag = "PhotoView_Content"
                    photoViewRef = this
                    minimumScale = 1.0f
                    mediumScale = 2.5f
                    maximumScale = 5.0f
                    setOnScaleChangeListener { _, _, _ ->
                        currentScale = scale
                    }
                }
            },
            update = { photoView: PhotoView ->
                photoViewRef = photoView
                when (loadedImage) {
                    is LoadedImageData.StaticImage -> {
                        photoView.setImageBitmap(loadedImage.bitmap)
                    }
                    is LoadedImageData.VectorSvgImage -> {
                        photoView.setImageBitmap(loadedImage.bitmap)
                    }
                    is LoadedImageData.AnimatedGifImage -> {
                        val drawable = loadedImage.drawable
                        if (photoView.drawable != drawable) {
                            photoView.setImageDrawable(drawable)
                        }
                        if (drawable is Animatable) {
                            if (isGifPlaying && !drawable.isRunning) {
                                drawable.start()
                            } else if (!isGifPlaying && drawable.isRunning) {
                                drawable.stop()
                            }
                        }
                    }
                }
            }
        )

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
                        Toast.makeText(context, "Exporting $fileName...", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = DragonSurfaceHigh.copy(alpha = 0.5f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Image",
                            tint = DragonDarkTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Surface(
                    onClick = {
                        val subSampleText = if (loadedImage is LoadedImageData.StaticImage && loadedImage.sampleSize > 1) {
                            ", Subsampled ${loadedImage.sampleSize}x"
                        } else ""
                        Toast.makeText(
                            context,
                            "${loadedImage.width}×${loadedImage.height} px$subSampleText",
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
                            contentDescription = "Image Details",
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
                            photoViewRef?.let { pv ->
                                val target = (pv.scale / 1.25f).coerceAtLeast(1.0f)
                                pv.setScale(target, true)
                                currentScale = target
                            }
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
                        text = "${(currentScale * 100).roundToInt()}%",
                        color = DragonPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                photoViewRef?.setScale(1.0f, true)
                                currentScale = 1.0f
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )

                    Surface(
                        onClick = {
                            photoViewRef?.let { pv ->
                                val target = (pv.scale * 1.25f).coerceAtMost(5.0f)
                                pv.setScale(target, true)
                                currentScale = target
                            }
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
                        photoViewRef?.setScale(1.0f, true)
                        currentScale = 1.0f
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

                // GIF Play/Pause button if animated
                if (loadedImage is LoadedImageData.AnimatedGifImage) {
                    Surface(
                        onClick = { isGifPlaying = !isGifPlaying },
                        shape = CircleShape,
                        color = if (isGifPlaying) DragonPrimaryContainer else DragonSurfaceHigh.copy(alpha = 0.7f),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isGifPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isGifPlaying) "Pause GIF" else "Play GIF",
                                tint = if (isGifPlaying) Color.White else DragonPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}



