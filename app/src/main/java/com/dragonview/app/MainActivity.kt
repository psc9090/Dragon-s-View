// app/src/main/java/com/dragonview/app/MainActivity.kt
package com.dragonview.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatDetectionResult
import com.dragonview.app.router.FormatRouter
import com.dragonview.app.ui.glassmorphicSurface
import com.dragonview.app.ui.theme.DragonCrimson
import com.dragonview.app.ui.theme.DragonDarkBackground
import com.dragonview.app.ui.theme.DragonDarkBorder
import com.dragonview.app.ui.theme.DragonDarkSurface
import com.dragonview.app.ui.theme.DragonDarkSurfaceContainer
import com.dragonview.app.ui.theme.DragonDarkSurfaceVariant
import com.dragonview.app.ui.theme.DragonDarkTextMuted
import com.dragonview.app.ui.theme.DragonDarkTextPrimary
import com.dragonview.app.ui.theme.DragonDarkTextSecondary
import com.dragonview.app.ui.theme.DragonFlame
import com.dragonview.app.ui.theme.DragonViewTheme
import com.dragonview.app.viewer.code.CodeViewer
import com.dragonview.app.viewer.document.DocumentRenderer
import com.dragonview.app.viewer.docx.DocxRenderer
import com.dragonview.app.viewer.image.ImageViewer
import com.dragonview.app.viewer.markdown.MarkdownRenderer
import com.dragonview.app.viewer.odg.OdgRenderer
import com.dragonview.app.viewer.pdf.PdfViewer
import com.dragonview.app.viewer.pptx.PptxRenderer
import com.dragonview.app.viewer.pptx.PresentationRenderer
import com.dragonview.app.viewer.xlsx.XlsxRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private var initialUriState = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            DragonViewTheme {
                DragonViewApp(
                    initialUri = initialUriState.value,
                    onClearUri = { initialUriState.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = extractUriFromIntent(intent) ?: return
        if (uri.scheme == ContentResolver.SCHEME_CONTENT && intent != null) {
            try {
                val flags = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (flags != 0) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Persistable permission could not be taken for $uri: ${e.message}")
            }
        }
        initialUriState.value = uri
    }

    private fun extractUriFromIntent(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> intent.data
        }
    }
}

@Composable
fun DragonViewApp(
    initialUri: Uri?,
    onClearUri: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var activeUri by remember { mutableStateOf(initialUri) }
    var detectionResult by remember { mutableStateOf<FormatDetectionResult?>(null) }
    var isDetecting by remember { mutableStateOf(false) }

    // Launcher for Storage Access Framework file picking
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { selectedUri ->
        selectedUri?.let { uri ->
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Failed to take persistable URI permission for $uri: ${e.message}")
            }
            activeUri = uri
        }
    }

    // Process file whenever activeUri changes
    LaunchedEffect(activeUri) {
        val uri = activeUri
        if (uri != null) {
            isDetecting = true
            detectionResult = FormatRouter.detectFormat(context, uri)
            isDetecting = false
        } else {
            detectionResult = null
            isDetecting = false
        }
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null && initialUri != activeUri) {
            activeUri = initialUri
        }
    }

    val navigateBack: () -> Unit = {
        activeUri = null
        detectionResult = null
        onClearUri()
    }

    BackHandler(enabled = activeUri != null, onBack = navigateBack)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DragonDarkBackground,
        topBar = {
            if (activeUri == null) {
                DragonTopBar(
                    detectionResult = null,
                    onOpenFileClick = {
                        openDocumentLauncher.launch(
                            arrayOf(
                                "application/pdf",
                                "image/*",
                                "application/vnd.openxmlformats-officedocument.*",
                                "application/msword",
                                "application/vnd.ms-excel",
                                "application/vnd.ms-powerpoint",
                                "text/*",
                                "application/json",
                                "application/xml",
                                "application/javascript",
                                "*/*"
                            )
                        )
                    },
                    onBackClick = navigateBack
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isDetecting -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = DragonFlame)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Inspecting file signatures & magic bytes...",
                                color = DragonDarkTextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                activeUri != null && detectionResult != null -> {
                    val result = detectionResult!!
                    when (result.format) {
                        FileFormat.PDF -> {
                            PdfViewer(
                                uri = activeUri!!,
                                detectionResult = result,
                                modifier = Modifier.fillMaxSize(),
                                onBack = navigateBack
                            )
                        }
                        FileFormat.MARKDOWN -> {
                            MarkdownRenderer(
                                uri = activeUri!!,
                                detectionResult = result,
                                modifier = Modifier.fillMaxSize(),
                                onBack = navigateBack
                            )
                        }
                        FileFormat.CODE -> {
                            CodeViewer(
                                uri = activeUri!!,
                                detectionResult = result,
                                modifier = Modifier.fillMaxSize(),
                                onBack = navigateBack
                            )
                        }
                        FileFormat.DOCX, FileFormat.RTF, FileFormat.ODT, FileFormat.ODT_TEMPLATE, FileFormat.ODT_FLAT -> {
                            DocumentRenderer(
                                uri = activeUri!!,
                                detectionResult = result,
                                modifier = Modifier.fillMaxSize(),
                                onBack = navigateBack
                            )
                        }
                        FileFormat.IMAGE -> {
                            ImageViewer(
                                uri = activeUri!!,
                                detectionResult = result,
                                modifier = Modifier.fillMaxSize(),
                                onBack = navigateBack
                            )
                        }
                        FileFormat.PPTX, FileFormat.ODP, FileFormat.ODP_TEMPLATE, FileFormat.ODP_FLAT -> {
                            PresentationRenderer(
                                uri = activeUri!!,
                                detectionResult = result,
                                modifier = Modifier.fillMaxSize(),
                                onBack = navigateBack
                            )
                        }
                        FileFormat.ODG, FileFormat.ODG_TEMPLATE, FileFormat.ODG_FLAT -> {
                            OdgRenderer(
                                uri = activeUri!!,
                                detectionResult = result,
                                modifier = Modifier.fillMaxSize(),
                                onBack = navigateBack
                            )
                        }
                        FileFormat.XLSX, FileFormat.ODS, FileFormat.ODS_TEMPLATE, FileFormat.ODS_FLAT -> {
                            XlsxRenderer(
                                uri = activeUri!!,
                                detectionResult = result,
                                modifier = Modifier.fillMaxSize(),
                                onBack = navigateBack
                            )
                        }
                        FileFormat.UNKNOWN -> {
                            UnreadableFileView(
                                result = result,
                                onOpenAnother = {
                                    openDocumentLauncher.launch(arrayOf("*/*"))
                                },
                                onBack = navigateBack
                            )
                        }
                    }
                }
                else -> {
                    // Home Landing Screen with file selector & embedded demo files
                    DragonLandingScreen(
                        onSelectFile = {
                            openDocumentLauncher.launch(arrayOf("*/*"))
                        },
                        onOpenSample = { sampleName, content, ext ->
                            coroutineScope.launch {
                                val sampleUri = createSampleFile(context, sampleName, content, ext)
                                activeUri = sampleUri
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DragonTopBar(
    detectionResult: FormatDetectionResult?,
    onOpenFileClick: () -> Unit,
    onBackClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DragonDarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (detectionResult != null) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.testTag("back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Close Document",
                        tint = DragonDarkTextPrimary
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(DragonCrimson),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "DV",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detectionResult?.fileName ?: "DragonView",
                    style = MaterialTheme.typography.titleMedium,
                    color = DragonDarkTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (detectionResult != null) {
                    Text(
                        text = "${detectionResult.format.label} • ${formatFileSize(detectionResult.fileSize)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = DragonDarkTextMuted
                    )
                } else {
                    Text(
                        text = "Universal Native File Viewer",
                        style = MaterialTheme.typography.labelSmall,
                        color = DragonFlame
                    )
                }
            }

            IconButton(
                onClick = onOpenFileClick,
                modifier = Modifier.testTag("open_file_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "Open File",
                    tint = DragonDarkTextPrimary
                )
            }
        }
    }
}

@Composable
private fun DragonLandingScreen(
    onSelectFile: () -> Unit,
    onOpenSample: (name: String, content: ByteArray, ext: String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "hero_card", contentType = "hero") {
            // Hero card
            Surface(
                color = DragonDarkSurfaceContainer,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(DragonCrimson.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = DragonCrimson,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "DragonView",
                        style = MaterialTheme.typography.headlineMedium,
                        color = DragonDarkTextPrimary,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Native document & code viewer built for high performance on Android 9-11.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DragonDarkTextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = onSelectFile,
                        colors = ButtonDefaults.buttonColors(containerColor = DragonCrimson),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("select_file_primary_button")
                    ) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browse Device Files", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val context = androidx.compose.ui.platform.LocalContext.current
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(context, TestZoomActivity::class.java))
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DragonFlame),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DragonFlame.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("open_zoom_test_button")
                    ) {
                        Text("🔬 Open Isolated ZoomLayout Test", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item(key = "section_header", contentType = "header") {
            Text(
                text = "Interactive Test Files (Step 1)",
                style = MaterialTheme.typography.titleMedium,
                color = DragonDarkTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap to test FormatRouter, PDFium tile rendering, syntax highlighting, and corrupt file trap:",
                style = MaterialTheme.typography.bodyMedium,
                color = DragonDarkTextMuted
            )
        }

        // Test File: Markdown Document (Obsidian Reading View)
        item(key = "sample_markdown", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Description,
                title = "reading_view_demo.md",
                subtitle = "Obsidian Reading View: YAML frontmatter, headings, tasks, tables & code",
                badge = "MARKDOWN",
                badgeColor = Color(0xFFAB47BC),
                onClick = {
                    val mdText = """
                        ---
                        title: DragonView Architecture Notes
                        author: DragonView Core Team
                        status: Production
                        vault: Obsidian Reading View
                        ---

                        # DragonView Architecture & Reading View

                        Welcome to the **Obsidian Reading View** rendered natively in *DragonView*!
                        This document tests block-level parsing, inline styling, and memory-disciplined rendering.

                        ## Core Specifications
                        Here is a summary of supported inline formats:
                        - **Bold emphasis** with double asterisks or __underscores__
                        - *Italic style* for citations and nuances
                        - ***Bold and italic combined*** for prominent remarks
                        - `inline code spans` with background highlighting
                        - ~~Strikethrough text~~ for deprecated items
                        - [DragonView Docs](https://dragonview.app) clickable links
                        - Raw wiki-links like [[Architecture Overview]] and tags like #obsidian remain intact

                        ## Project Roadmap & Tasks
                        - [x] Implement Markdown block parser with YAML frontmatter support
                        - [x] Build RecyclerView Obsidian Reading View renderer
                        - [x] Support proportional headings H1 through H6 with distinct styling
                        - [x] Implement read-only task list checkboxes (☐ and ☑)
                        - [ ] Support custom Obsidian CSS snippets
                        - [ ] Add interactive search inside markdown document

                        ### Technical Highlights
                        1. Direct RecyclerView row recycling with zero WebView overhead
                        2. Memory-disciplined inSampleSize bitmap downsampling to prevent OOM
                        3. Fling debouncing for smooth 60 FPS scrolling performance

                        ## Code Sample
                        ```kotlin
                        // Pure Kotlin Markdown Block Model
                        data class MarkdownDocument(
                            val title: String,
                            val frontmatter: Map<String, String>?,
                            val blocks: List<MarkdownBlock>
                        )
                        ```

                        ## Architectural Comparison
                        | Component | Strategy | Performance Benefit |
                        | --- | --- | --- |
                        | Parser | Streaming line parser | Sub-millisecond parsing |
                        | Renderer | Native Android RecyclerView | 60 FPS scrolling & recycling |
                        | Memory | inSampleSize downsampling | Zero large bitmap OOM crashes |
                        | Theme | Dragon Obsidian Dark | Eye-safe high-contrast reading |

                        > DragonView delivers instant, offline-first, native document visualization without compromise.
                        > "Simplicity is prerequisite for reliability."

                        ---
                    """.trimIndent()
                    onOpenSample("reading_view_demo.md", mdText.toByteArray(Charsets.UTF_8), "md")
                }
            )
        }

        // Test File: Python Code
        item(key = "sample_python", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Code,
                title = "algorithm.py (Source Code)",
                subtitle = "Python syntax highlighting with regex tokens & line numbers",
                badge = "CODE",
                badgeColor = Color(0xFFD81B60),
                onClick = {
                    val code = """
                        # DragonView Algorithm Demo
                        import sys
                        import math
                        from typing import List, Optional

                        def benchmark(func):
                            '''Decorator measuring function execution latency.'''
                            def wrapper(*args, **kwargs):
                                return func(*args, **kwargs)
                            return wrapper

                        class FastFileBuffer:
                            '''Triple-quoted documentation string.'''
                            def __init__(self, capacity_mb: int = 16):
                                self.capacity = capacity_mb * 1024 * 1024
                                self.allocated = 0

                            @benchmark
                            def read_magic_header(self, raw_bytes: bytes) -> str:
                                # Inspect magic bytes
                                if raw_bytes.startswith(b"%PDF"):
                                    return "PDF"
                                elif raw_bytes.startswith(b"PK\x03\x04"):
                                    return "ZIP_CONTAINER"
                                return "UNKNOWN"
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                    onOpenSample("algorithm.py", code, "py")
                }
            )
        }

        // Test File: JSON Configuration
        item(key = "sample_json", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Code,
                title = "manifest.json (Config)",
                subtitle = "JSON key/value grammar, numbers, booleans & brackets",
                badge = "JSON",
                badgeColor = Color(0xFFAB47BC),
                onClick = {
                    val code = """
                        {
                            "name": "DragonView",
                            "version": 4.0,
                            "buildCode": 104,
                            "isUniversal": true,
                            "debugMode": false,
                            "supportedEngines": [
                                "PDFium",
                                "XmlPullParser",
                                "AndroidSVG",
                                "RegexTokenizer"
                            ],
                            "memoryDiscipline": {
                                "maxCacheMb": 32,
                                "autoRecycle": true,
                                "lowRamOptimized": true
                            }
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                    onOpenSample("manifest.json", code, "json")
                }
            )
        }

        // Test File: Kotlin Source Code
        item(key = "sample_kotlin", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Code,
                title = "FormatRouter.kt (Source Code)",
                subtitle = "Kotlin language grammar highlighting and gutter scrolling",
                badge = "KOTLIN",
                badgeColor = Color(0xFF8E24AA),
                onClick = {
                    val code = """
                        package com.dragonview.app.router

                        import android.net.Uri
                        import kotlinx.coroutines.Dispatchers
                        import kotlinx.coroutines.withContext

                        class CoroutineDetector(val timeoutMs: Long = 5000L) {
                            suspend fun inspectStream(uri: Uri): Boolean = withContext(Dispatchers.IO) {
                                val buffer = ByteArray(1024)
                                // Streams closed reliably via .use { }
                                true
                            }
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                    onOpenSample("FormatRouter.kt", code, "kt")
                }
            )
        }

        // Test File: Sample PDF Document
        item(key = "sample_pdf", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.PictureAsPdf,
                title = "sample_document.pdf",
                subtitle = "Native PDFium tile rendering with memory lifecycle recycling",
                badge = "PDF",
                badgeColor = Color(0xFFE53935),
                onClick = {
                    val pdfBytes = createMinimalPdf()
                    onOpenSample("sample_document.pdf", pdfBytes, "pdf")
                }
            )
        }

        // Test File: Intentionally Corrupted File
        item(key = "sample_corrupt", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Warning,
                title = "corrupted_invoice.pdf",
                subtitle = "Extension is .pdf but contains junk bytes (tests corrupted detection)",
                badge = "CORRUPT TEST",
                badgeColor = Color(0xFFFF5252),
                onClick = {
                    val corruptBytes = "THIS IS NOT A VALID PDF FILE AT ALL!!".toByteArray(Charsets.UTF_8)
                    onOpenSample("corrupted_invoice.pdf", corruptBytes, "pdf")
                }
            )
        }

        // Test File: DOCX File (Active Streaming Renderer)
        item(key = "sample_docx", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Description,
                title = "quarterly_report.docx",
                subtitle = "Headings, formatted runs, bullet points & native tables",
                badge = "DOCX",
                badgeColor = Color(0xFF1E88E5),
                onClick = {
                    val docxBytes = createSampleDocx()
                    onOpenSample("quarterly_report.docx", docxBytes, "docx")
                }
            )
        }

        // Test File: RTF File (Active Streaming Tokenizer)
        item(key = "sample_rtf", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Description,
                title = "specification.rtf",
                subtitle = "Streaming control words, color tables, styled runs & alignments",
                badge = "RTF",
                badgeColor = Color(0xFF00ACC1),
                onClick = {
                    val rtfBytes = createSampleRtf()
                    onOpenSample("specification.rtf", rtfBytes, "rtf")
                }
            )
        }

        // Test File: PPTX Presentation (Active ViewPager2 Renderer)
        item(key = "sample_pptx", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Slideshow,
                title = "pitch_deck.pptx",
                subtitle = "Proportional EMU spatial layout, stylized runs & swipable slides",
                badge = "PPTX",
                badgeColor = Color(0xFFD84315),
                onClick = {
                    val pptxBytes = createSamplePptx()
                    onOpenSample("pitch_deck.pptx", pptxBytes, "pptx")
                }
            )
        }

        // Test File: XLSX Spreadsheet (Active Grid + Frozen Panes Renderer)
        item(key = "sample_xlsx", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.TableChart,
                title = "quarterly_financials.xlsx",
                subtitle = "Frozen header & column A, multi-sheet tabs, formulas & dates",
                badge = "XLSX",
                badgeColor = Color(0xFF2E7D32),
                onClick = {
                    val xlsxBytes = createSampleXlsx()
                    onOpenSample("quarterly_financials.xlsx", xlsxBytes, "xlsx")
                }
            )
        }

        // Test File: SVG Vector Graphic (Active AndroidSVG)
        item(key = "sample_svg", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Image,
                title = "dragon_emblem.svg",
                subtitle = "Scalable vector graphic rendered with AndroidSVG",
                badge = "SVG",
                badgeColor = Color(0xFFFFA726),
                onClick = {
                    val svgBytes = createSampleSvg()
                    onOpenSample("dragon_emblem.svg", svgBytes, "svg")
                }
            )
        }

        // Test File: Bitmap Image (Active Subsampled Viewer)
        item(key = "sample_bmp", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Image,
                title = "dragon_badge.bmp",
                subtitle = "Subsampled raster bitmap with pinch-zoom & pan",
                badge = "BMP",
                badgeColor = Color(0xFF78909C),
                onClick = {
                    val bmpBytes = createSampleBmp()
                    onOpenSample("dragon_badge.bmp", bmpBytes, "bmp")
                }
            )
        }

        // Test File: OpenDocument Text (Active Streaming Parser)
        item(key = "sample_odt", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Description,
                title = "specification.odt",
                subtitle = "OpenDocument Text with styles.xml, content.xml, formatting & tables",
                badge = "ODT",
                badgeColor = Color(0xFF8E24AA),
                onClick = {
                    val odtBytes = createSampleOdt()
                    onOpenSample("specification.odt", odtBytes, "odt")
                }
            )
        }

        // Test File: Flat OpenDocument XML (Active Direct Parser)
        item(key = "sample_fodt", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Description,
                title = "manifesto.fodt",
                subtitle = "Flat XML OpenDocument text parsed without ZIP extraction",
                badge = "FODT",
                badgeColor = Color(0xFFAB47BC),
                onClick = {
                    val fodtBytes = createSampleFodt()
                    onOpenSample("manifesto.fodt", fodtBytes, "fodt")
                }
            )
        }

        // Test File: OpenDocument Spreadsheet (Active Streaming Parser)
        item(key = "sample_ods", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.TableChart,
                title = "financial_forecast.ods",
                subtitle = "OpenDocument Spreadsheet with multi-sheet tabs, compressed rows & formulas",
                badge = "ODS",
                badgeColor = Color(0xFF43A047),
                onClick = {
                    val odsBytes = createSampleOds()
                    onOpenSample("financial_forecast.ods", odsBytes, "ods")
                }
            )
        }

        // Test File: Flat OpenDocument Spreadsheet XML (Active Direct Parser)
        item(key = "sample_fods", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.TableChart,
                title = "budget_matrix.fods",
                subtitle = "Flat XML OpenDocument spreadsheet parsed directly without unzipping",
                badge = "FODS",
                badgeColor = Color(0xFF66BB6A),
                onClick = {
                    val fodsBytes = createSampleFods()
                    onOpenSample("budget_matrix.fods", fodsBytes, "fods")
                }
            )
        }

        // Test File: OpenDocument Presentation (Active ViewPager2 Renderer)
        item(key = "sample_odp", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Slideshow,
                title = "architecture_deck.odp",
                subtitle = "OpenDocument presentation with styles.xml, content.xml & swipable slides",
                badge = "ODP",
                badgeColor = Color(0xFFFB8C00),
                onClick = {
                    val odpBytes = createSampleOdp()
                    onOpenSample("architecture_deck.odp", odpBytes, "odp")
                }
            )
        }

        // Test File: Flat OpenDocument Presentation XML (Active Direct Parser)
        item(key = "sample_fodp", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.Slideshow,
                title = "roadmap_brief.fodp",
                subtitle = "Flat XML OpenDocument presentation parsed directly without unzipping",
                badge = "FODP",
                badgeColor = Color(0xFFFF9800),
                onClick = {
                    val fodpBytes = createSampleFodp()
                    onOpenSample("roadmap_brief.fodp", fodpBytes, "fodp")
                }
            )
        }

        // Test File: OpenDocument Drawing Vector Package (Active ODG Renderer)
        item(key = "sample_odg", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.InsertDriveFile,
                title = "system_diagram.odg",
                subtitle = "Vector shapes, paths, groups, and styles in OpenDocument drawing archive",
                badge = "ODG",
                badgeColor = Color(0xFF00ACC1),
                onClick = {
                    val odgBytes = createSampleOdg()
                    onOpenSample("system_diagram.odg", odgBytes, "odg")
                }
            )
        }

        // Test File: Flat OpenDocument Drawing XML (Active Direct Parser)
        item(key = "sample_fodg", contentType = "sample_item") {
            SampleFileItem(
                icon = Icons.Default.InsertDriveFile,
                title = "flowchart.fodg",
                subtitle = "Flat XML OpenDocument drawing with custom paths and transforms",
                badge = "FODG",
                badgeColor = Color(0xFF26C6DA),
                onClick = {
                    val fodgBytes = createSampleFodg()
                    onOpenSample("flowchart.fodg", fodgBytes, "fodg")
                }
            )
        }

        item(key = "capabilities_roadmap", contentType = "roadmap") {
            FormatCapabilitiesGrid()
        }
    }
}

private val SampleCardShape = RoundedCornerShape(14.dp)
private val SampleIconBadgeShape = RoundedCornerShape(10.dp)
private val SampleTagShape = RoundedCornerShape(6.dp)

@Composable
private fun SampleFileItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    badgeColor: Color,
    onClick: () -> Unit
) {
    val borderStroke = remember { androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder) }
    Surface(
        color = DragonDarkSurface,
        shape = SampleCardShape,
        border = borderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(SampleIconBadgeShape)
                    .background(badgeColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = DragonDarkTextPrimary,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(SampleTagShape)
                            .background(badgeColor.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            color = badgeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DragonDarkTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FormatCapabilitiesGrid() {
    Surface(
        color = DragonDarkSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = DragonFlame)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "DragonView Format Roadmap",
                    style = MaterialTheme.typography.titleMedium,
                    color = DragonDarkTextPrimary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            RoadmapRow("PDF Documents", "Active (Tile Renderer)", Color(0xFF81C784))
            RoadmapRow("Source Code (.c,.py,.kt,.js...)", "Active (Syntax Highlighter)", Color(0xFF81C784))
            RoadmapRow("Word (.doc/.docx)", "Active (XmlPullParser Streaming)", Color(0xFF81C784))
            RoadmapRow("Rich Text Format (.rtf)", "Active (Streaming Tokenizer)", Color(0xFF81C784))
            RoadmapRow("PowerPoint (.ppt/.pptx)", "Active (EMU Canvas + ViewPager2)", Color(0xFF81C784))
            RoadmapRow("Excel (.xls/.xlsx)", "Active (Frozen Headers + Tabs)", Color(0xFF81C784))
            RoadmapRow("Images (.jpg,.png,.gif,.webp,.svg...)", "Active (Subsampling + Zoom)", Color(0xFF81C784))
            RoadmapRow("OpenDocument Text (.odt/.ott/.fodt)", "Active (XmlPullParser Streaming)", Color(0xFF81C784))
            RoadmapRow("OpenDocument Spreadsheets (.ods/.ots/.fods)", "Active (XmlPullParser + Sparse Grid)", Color(0xFF81C784))
            RoadmapRow("OpenDocument Presentations (.odp/.otp/.fodp)", "Active (EMU Canvas + ViewPager2)", Color(0xFF81C784))
            RoadmapRow("OpenDocument Drawings (.odg/.otg/.fodg)", "Active (Direct Vector Canvas + SVG Paths)", Color(0xFF81C784))
        }
    }
}

@Composable
private fun RoadmapRow(title: String, status: String, statusColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, color = DragonDarkTextSecondary, fontSize = 13.sp)
        Text(text = status, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OfficeFormatPlaceholder(
    result: FormatDetectionResult,
    onOpenAnother: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = DragonDarkSurfaceContainer,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.TableChart,
                    contentDescription = null,
                    tint = DragonFlame,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "${result.format.label} Detected",
                    style = MaterialTheme.typography.titleLarge,
                    color = DragonDarkTextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Package structure verified by FormatRouter:\n${result.statusMessage}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DragonDarkTextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "As part of our step-by-step architecture, this format's native parser module will be activated in the upcoming step.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DragonDarkTextMuted,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) {
                        Button(
                            onClick = onBack,
                            colors = ButtonDefaults.buttonColors(containerColor = DragonFlame)
                        ) {
                            Text("Back to Home")
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenAnother,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DragonDarkTextPrimary)
                    ) {
                        Text("Open Another File")
                    }
                }
            }
        }
    }
}

@Composable
private fun UnreadableFileView(
    result: FormatDetectionResult,
    onOpenAnother: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xFF261014),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6B202A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4A1017)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (result.isCorrupted) Icons.Default.Warning else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (result.isCorrupted) "File Cannot Be Opened (Corrupted)" else "Format Not Supported",
                    style = MaterialTheme.typography.titleLarge,
                    color = DragonDarkTextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = result.statusMessage ?: "DragonView could not identify a valid format parser for this file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DragonDarkTextSecondary,
                    textAlign = TextAlign.Center
                )

                if (result.magicBytesHeader.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color(0xFF19070A),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Detected Header: ${result.magicBytesHeader}",
                            modifier = Modifier.padding(10.dp),
                            color = Color(0xFFFF8A80),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) {
                        OutlinedButton(
                            onClick = onBack,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DragonDarkTextPrimary)
                        ) {
                            Text("Back to Home")
                        }
                    }
                    Button(
                        onClick = onOpenAnother,
                        colors = ButtonDefaults.buttonColors(containerColor = DragonCrimson)
                    ) {
                        Text("Select Another File")
                    }
                }
            }
        }
    }
}

/**
 * Creates an isolated sample file in app cacheDir and returns its content/file Uri.
 */
private suspend fun createSampleFile(
    context: android.content.Context,
    fileName: String,
    content: ByteArray,
    extension: String
): Uri = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "sample_files").apply { mkdirs() }
    val file = File(dir, fileName)
    FileOutputStream(file).use { it.write(content) }
    Uri.fromFile(file)
}

/**
 * Creates a valid, minimal single-page PDF with standard %PDF-1.4 header and stream.
 */
private fun createMinimalPdf(): ByteArray {
    val pdfSource = """
        %PDF-1.4
        1 0 obj <</Type /Catalog /Pages 2 0 R>> endobj
        2 0 obj <</Type /Pages /Kids [3 0 R] /Count 1>> endobj
        3 0 obj <</Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj
        4 0 obj <</Length 110>> stream
        BT
        /F1 24 Tf
        100 700 Td
        (DragonView PDF Rendering Test) Tj
        /F1 14 Tf
        0 -40 Td
        (Step 1: Pdfium Tile Rendering Engine OK) Tj
        ET
        endstream
        endobj
        5 0 obj <</Type /Font /Subtype /Type1 /BaseFont /Helvetica>> endobj
        xref
        0 6
        0000000000 65535 f 
        0000000009 00000 n 
        0000000058 00000 n 
        0000000115 00000 n 
        0000000244 00000 n 
        0000000405 00000 n 
        trailer <</Size 6 /Root 1 0 R>>
        startxref
        474
        %%EOF
    """.trimIndent()
    return pdfSource.toByteArray(Charsets.US_ASCII)
}

/**
 * Creates a minimal valid ZIP archive containing a specified sub-file entry.
 */
private fun createMockZipWithEntry(entryName: String): ByteArray {
    val baos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baos).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry(entryName))
        zos.write("<xml></xml>".toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
    return baos.toByteArray()
}

/**
 * Creates a real sample DOCX file containing paragraphs, styled runs, and a table.
 */
private fun createSampleDocx(): ByteArray {
    val docXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
    <w:body>
        <w:p>
            <w:pPr>
                <w:pStyle w:val="Heading1"/>
                <w:jc w:val="center"/>
            </w:pPr>
            <w:r>
                <w:rPr>
                    <w:b/>
                    <w:color w:val="FF5252"/>
                    <w:sz w:val="48"/>
                </w:rPr>
                <w:t>DragonView Q3 Engineering Report</w:t>
            </w:r>
        </w:p>
        <w:p>
            <w:pPr>
                <w:jc w:val="center"/>
            </w:pPr>
            <w:r>
                <w:rPr>
                    <w:i/>
                    <w:color w:val="D4A5AB"/>
                    <w:sz w:val="24"/>
                </w:rPr>
                <w:t>Universal Document &amp; Source Code Engine</w:t>
            </w:r>
        </w:p>
        <w:p>
            <w:r>
                <w:rPr><w:b/></w:rPr>
                <w:t>Executive Summary: </w:t>
            </w:r>
            <w:r>
                <w:t>This DOCX document was parsed on the fly using Android's native </w:t>
            </w:r>
            <w:r>
                <w:rPr>
                    <w:b/>
                    <w:color w:val="81C784"/>
                </w:rPr>
                <w:t>XmlPullParser</w:t>
            </w:r>
            <w:r>
                <w:t> streaming engine without heavy external libraries. Memory footprint is strictly bounded to prevent OOM on 3-4GB RAM devices.</w:t>
            </w:r>
        </w:p>
        <w:p>
            <w:pPr>
                <w:pStyle w:val="Heading2"/>
            </w:pPr>
            <w:r>
                <w:rPr>
                    <w:b/>
                    <w:color w:val="FF8A80"/>
                    <w:sz w:val="36"/>
                </w:rPr>
                <w:t>Key Architectural Highlights</w:t>
            </w:r>
        </w:p>
        <w:p>
            <w:pPr><w:numPr/></w:pPr>
            <w:r>
                <w:rPr><w:b/></w:rPr>
                <w:t>Zero Apache POI Overhead: </w:t>
            </w:r>
            <w:r>
                <w:t>Custom streaming pull-parser decodes runs and styling instantly.</w:t>
            </w:r>
        </w:p>
        <w:p>
            <w:pPr><w:numPr/></w:pPr>
            <w:r>
                <w:rPr><w:b/></w:rPr>
                <w:t>Hardware Downsampling: </w:t>
            </w:r>
            <w:r>
                <w:t>All embedded images decode with calculateInSampleSize down to device density.</w:t>
            </w:r>
        </w:p>
        <w:p>
            <w:pPr><w:numPr/></w:pPr>
            <w:r>
                <w:rPr><w:b/></w:rPr>
                <w:t>Safe Error Trapping: </w:t>
            </w:r>
            <w:r>
                <w:t>Corrupted or missing document.xml states are trapped without crashing the app.</w:t>
            </w:r>
        </w:p>
        <w:p>
            <w:pPr>
                <w:pStyle w:val="Heading2"/>
            </w:pPr>
            <w:r>
                <w:rPr>
                    <w:b/>
                    <w:color w:val="FF8A80"/>
                    <w:sz w:val="36"/>
                </w:rPr>
                <w:t>Performance Benchmarks</w:t>
            </w:r>
        </w:p>
        <w:tbl>
            <w:tr>
                <w:tc>
                    <w:shd w:val="clear" w:color="auto" w:fill="4A1F26"/>
                    <w:p>
                        <w:r><w:rPr><w:b/><w:color w:val="FFFFFF"/></w:rPr><w:t>Module</w:t></w:r>
                    </w:p>
                </w:tc>
                <w:tc>
                    <w:shd w:val="clear" w:color="auto" w:fill="4A1F26"/>
                    <w:p>
                        <w:r><w:rPr><w:b/><w:color w:val="FFFFFF"/></w:rPr><w:t>Heap Usage</w:t></w:r>
                    </w:p>
                </w:tc>
                <w:tc>
                    <w:shd w:val="clear" w:color="auto" w:fill="4A1F26"/>
                    <w:p>
                        <w:r><w:rPr><w:b/><w:color w:val="FFFFFF"/></w:rPr><w:t>Parse Latency</w:t></w:r>
                    </w:p>
                </w:tc>
            </w:tr>
            <w:tr>
                <w:tc>
                    <w:p><w:r><w:t>PDF (Pdfium Tile)</w:t></w:r></w:p>
                </w:tc>
                <w:tc>
                    <w:p><w:r><w:t>&lt; 18 MB</w:t></w:r></w:p>
                </w:tc>
                <w:tc>
                    <w:p><w:r><w:t>~12 ms/tile</w:t></w:r></w:p>
                </w:tc>
            </w:tr>
            <w:tr>
                <w:tc>
                    <w:p><w:r><w:t>DOCX (XmlPullParser)</w:t></w:r></w:p>
                </w:tc>
                <w:tc>
                    <w:p><w:r><w:t>&lt; 14 MB</w:t></w:r></w:p>
                </w:tc>
                <w:tc>
                    <w:p><w:r><w:t>~45 ms (100 pgs)</w:t></w:r></w:p>
                </w:tc>
            </w:tr>
            <w:tr>
                <w:tc>
                    <w:p><w:r><w:t>Code Viewer</w:t></w:r></w:p>
                </w:tc>
                <w:tc>
                    <w:p><w:r><w:t>&lt; 9 MB</w:t></w:r></w:p>
                </w:tc>
                <w:tc>
                    <w:p><w:r><w:t>~18 ms (4k lines)</w:t></w:r></w:p>
                </w:tc>
            </w:tr>
        </w:tbl>
    </w:body>
</w:document>""".trimIndent()

    val baos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baos).use { zos ->
        // [Content_Types].xml
        zos.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml"))
        zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
    <Default Extension="xml" ContentType="application/xml"/>
    <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        // word/document.xml
        zos.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
        zos.write(docXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
    return baos.toByteArray()
}

/**
 * Creates a sample SVG vector graphic.
 */
private fun createSampleSvg(): ByteArray {
    val svgXml = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 400" width="400" height="400">
    <rect width="400" height="400" rx="40" fill="#1F0D10"/>
    <circle cx="200" cy="200" r="140" fill="#2E1218" stroke="#D32F2F" stroke-width="4"/>
    <path d="M 140,240 Q 200,100 260,240 Q 200,200 140,240 Z" fill="#FF5252"/>
    <circle cx="200" cy="180" r="30" fill="#FF8A80"/>
    <polygon points="200,110 220,160 180,160" fill="#FFD54F"/>
    <text x="200" y="320" font-family="sans-serif" font-size="22" font-weight="bold" fill="#FDE8EA" text-anchor="middle">DRAGONVIEW SVG</text>
    <text x="200" y="350" font-family="sans-serif" font-size="14" fill="#D4A5AB" text-anchor="middle">AndroidSVG High-DPI Vector</text>
</svg>""".trimIndent()
    return svgXml.toByteArray(Charsets.UTF_8)
}

/**
 * Creates a sample 24-bit BMP image.
 */
private fun createSampleBmp(): ByteArray {
    val width = 120
    val height = 120
    val rowSize = (width * 3 + 3) and 3.inv()
    val imageSize = rowSize * height
    val fileSize = 54 + imageSize

    val buffer = java.nio.ByteBuffer.allocate(fileSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    // BMP Header
    buffer.put(0x42.toByte()) // 'B'
    buffer.put(0x4D.toByte()) // 'M'
    buffer.putInt(fileSize)
    buffer.putShort(0)
    buffer.putShort(0)
    buffer.putInt(54) // offset to pixel array

    // DIB Header (BITMAPINFOHEADER)
    buffer.putInt(40) // header size
    buffer.putInt(width)
    buffer.putInt(height)
    buffer.putShort(1) // color planes
    buffer.putShort(24) // 24 bits per pixel
    buffer.putInt(0) // BI_RGB compression
    buffer.putInt(imageSize)
    buffer.putInt(2835) // 72 DPI
    buffer.putInt(2835)
    buffer.putInt(0)
    buffer.putInt(0)

    // Pixel data (BGR bottom-to-top)
    val row = ByteArray(rowSize)
    for (y in 0 until height) {
        var idx = 0
        for (x in 0 until width) {
            val r = ((x.toFloat() / width) * 200 + 55).toInt().toByte()
            val g = (40).toByte()
            val b = ((y.toFloat() / height) * 120 + 30).toInt().toByte()
            row[idx++] = b
            row[idx++] = g
            row[idx++] = r
        }
        buffer.put(row)
    }

    return buffer.array()
}

private fun createSampleRtf(): ByteArray {
    val rtf = "{\\rtf1\\ansi\\deff0" +
        "{\\fonttbl{\\f0\\fnil\\fcharset0 Calibri;}}" +
        "{\\colortbl ;\\red229\\green57\\blue53;\\red67\\green160\\blue71;\\red30\\green136\\blue229;}" +
        "\\viewkind4\\uc1\\pard\\qc\\b\\fs34 DragonView Universal Document Engine\\b0\\fs24\\par" +
        "\\pard\\ql\\par" +
        "This document is parsed via a \\b streaming character-by-character RTF tokenizer\\b0  designed specifically for low-RAM mobile devices.\\par" +
        "\\par" +
        "\\b Engine Verification Test Suite:\\b0\\par" +
        "\\bullet  \\b Text formatting:\\b0  \\b bold\\b0 , \\i italic accents\\i0 , \\ul underline\\ulnone , and \\strike strikethrough\\strike0\\par" +
        "\\bullet  \\b Color table mapping:\\b0  \\cf1 Crimson highlight\\cf0 , \\cf2 Emerald status\\cf0 , and \\cf3 Sapphire lead\\cf0\\par" +
        "\\bullet  \\b Font size scaling:\\b0  from \\fs18 fine print (9pt)\\fs24  to \\fs30 section titles (15pt)\\fs24\\par" +
        "\\bullet  \\b Memory footprint:\\b0  Zero full-DOM memory allocation; styled runs recycled directly in RecyclerView\\par" +
        "\\par" +
        "\\pard\\qc\\i Native rendering architecture shared across DOCX and RTF.\\i0\\par" +
        "}"
    return rtf.toByteArray(Charsets.ISO_8859_1)
}

private fun createSamplePptx(): ByteArray {
    val contentTypesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
  <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
  <Override PartName="/ppt/slides/slide2.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
  <Override PartName="/ppt/slides/slide3.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
</Types>""".trimIndent()

    val rootRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>""".trimIndent()

    val presentationXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldSz cx="12192000" cy="6858000"/>
  <p:sldIdLst>
    <p:sldId id="256" r:id="rId1"/>
    <p:sldId id="257" r:id="rId2"/>
    <p:sldId id="258" r:id="rId3"/>
  </p:sldIdLst>
</p:presentation>""".trimIndent()

    val presentationRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide2.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide3.xml"/>
</Relationships>""".trimIndent()

    val slide1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr>
      <p:sp>
        <p:spPr>
          <a:xfrm><a:off x="1000000" y="1600000"/><a:ext cx="10192000" cy="1800000"/></a:xfrm>
        </p:spPr>
        <p:txBody>
          <a:bodyPr/>
          <a:p>
            <a:pPr algn="ctr"/>
            <a:r>
              <a:rPr b="1" sz="4000">
                <a:solidFill><a:srgbClr val="D84315"/></a:solidFill>
              </a:rPr>
              <a:t>DragonView Mobile Engine</a:t>
            </a:r>
          </a:p>
        </p:txBody>
      </p:sp>
      <p:sp>
        <p:spPr>
          <a:xfrm><a:off x="1000000" y="3600000"/><a:ext cx="10192000" cy="1400000"/></a:xfrm>
        </p:spPr>
        <p:txBody>
          <a:bodyPr/>
          <a:p>
            <a:pPr algn="ctr"/>
            <a:r>
              <a:rPr i="1" sz="2400">
                <a:solidFill><a:srgbClr val="424242"/></a:solidFill>
              </a:rPr>
              <a:t>Native PPTX Viewer with ViewPager2 Swiping</a:t>
            </a:r>
          </a:p>
        </p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
</p:sld>""".trimIndent()

    val slide2Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr>
      <p:sp>
        <p:spPr>
          <a:xfrm><a:off x="800000" y="600000"/><a:ext cx="10500000" cy="900000"/></a:xfrm>
        </p:spPr>
        <p:txBody>
          <a:bodyPr/>
          <a:p>
            <a:pPr algn="l"/>
            <a:r><a:rPr b="1" sz="3000"><a:solidFill><a:srgbClr val="1E88E5"/></a:solidFill></a:rPr><a:t>Native PPTX Engine Architecture</a:t></a:r>
          </a:p>
        </p:txBody>
      </p:sp>
      <p:sp>
        <p:spPr>
          <a:xfrm><a:off x="800000" y="1800000"/><a:ext cx="10500000" cy="4200000"/></a:xfrm>
        </p:spPr>
        <p:txBody>
          <a:bodyPr/>
          <a:p>
            <a:pPr algn="l"><a:buChar char="•"/></a:pPr>
            <a:r><a:rPr b="1" sz="2000"/><a:t>Streaming XmlPullParser: </a:t></a:r>
            <a:r><a:rPr sz="1800"/><a:t>Extracts slide XMLs with zero full-DOM memory overhead</a:t></a:r>
          </a:p>
          <a:p>
            <a:pPr algn="l"><a:buChar char="•"/></a:pPr>
            <a:r><a:rPr b="1" sz="2000"/><a:t>EMU Spatial Scaling: </a:t></a:r>
            <a:r><a:rPr sz="1800"/><a:t>Proportionally maps 12,192,000 x 6,858,000 EMUs to Android screen</a:t></a:r>
          </a:p>
          <a:p>
            <a:pPr algn="l"><a:buChar char="•"/></a:pPr>
            <a:r><a:rPr b="1" sz="2000"/><a:t>Horizontal ViewPager2: </a:t></a:r>
            <a:r><a:rPr sz="1800"/><a:t>Smooth horizontal swiping with page dots and jump buttons</a:t></a:r>
          </a:p>
          <a:p>
            <a:pPr algn="l"><a:buChar char="•"/></a:pPr>
            <a:r><a:rPr b="1" sz="2000"/><a:t>Smart Element Tolerance: </a:t></a:r>
            <a:r><a:rPr sz="1800"/><a:t>Silently ignores complex SmartArt and charts without crashing</a:t></a:r>
          </a:p>
        </p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
</p:sld>""".trimIndent()

    val slide3Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr>
      <p:sp>
        <p:spPr>
          <a:xfrm><a:off x="1000000" y="2000000"/><a:ext cx="10192000" cy="1200000"/></a:xfrm>
        </p:spPr>
        <p:txBody>
          <a:bodyPr/>
          <a:p>
            <a:pPr algn="ctr"/>
            <a:r><a:rPr b="1" sz="3400"><a:solidFill><a:srgbClr val="43A047"/></a:solidFill></a:rPr><a:t>Engine Verification Complete</a:t></a:r>
          </a:p>
        </p:txBody>
      </p:sp>
      <p:sp>
        <p:spPr>
          <a:xfrm><a:off x="1000000" y="3400000"/><a:ext cx="10192000" cy="1200000"/></a:xfrm>
        </p:spPr>
        <p:txBody>
          <a:bodyPr/>
          <a:p>
            <a:pPr algn="ctr"/>
            <a:r><a:rPr sz="2200"/><a:t>Native PPTX rendering verified with low RAM footprint.</a:t></a:r>
          </a:p>
        </p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
</p:sld>""".trimIndent()

    val baos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baos).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml"))
        zos.write(contentTypesXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("_rels/.rels"))
        zos.write(rootRelsXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("ppt/presentation.xml"))
        zos.write(presentationXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("ppt/_rels/presentation.xml.rels"))
        zos.write(presentationRelsXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("ppt/slides/slide1.xml"))
        zos.write(slide1Xml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("ppt/slides/slide2.xml"))
        zos.write(slide2Xml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("ppt/slides/slide3.xml"))
        zos.write(slide3Xml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
    return baos.toByteArray()
}

private fun createSampleXlsx(): ByteArray {
    val contentTypesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>""".trimIndent()

    val rootRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".trimIndent()

    val workbookXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Q3 Performance" sheetId="1" r:id="rId1"/>
    <sheet name="Regional Summary" sheetId="2" r:id="rId2"/>
  </sheets>
</workbook>""".trimIndent()

    val workbookRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
</Relationships>""".trimIndent()

    val sharedStringsList = listOf(
        "Region", "Target Revenue", "Actual Revenue", "Variance", "Growth Rate", "Fulfillment Date", "Status",
        "North America", "Europe", "Asia Pacific", "Latin America", "Middle East",
        "EXCEEDED", "ON TRACK", "REVIEW NEEDED",
        "Metric", "Target", "Achieved", "Score",
        "Customer NPS", "Server SLA", "Sprint Velocity"
    )

    val sharedStringsXml = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${sharedStringsList.size}" uniqueCount="${sharedStringsList.size}">""")
        for (str in sharedStringsList) {
            append("<si><t>$str</t></si>")
        }
        append("</sst>")
    }

    val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><name val="Calibri"/><sz val="11"/></font>
    <font><b/><name val="Calibri"/><sz val="11"/><color rgb="FFFFFFFF"/></font>
  </fonts>
  <cellXfs count="4">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" applyFont="1"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" applyFont="1"/>
    <xf numFmtId="14" fontId="0" fillId="0" borderId="0" applyNumberFormat="1"/>
    <xf numFmtId="2" fontId="0" fillId="0" borderId="0" applyNumberFormat="1"/>
  </cellXfs>
</styleSheet>""".trimIndent()

    // Sheet 1: Q3 Performance
    val sheet1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    <row r="1">
      <c r="A1" t="s" s="1"><v>0</v></c>
      <c r="B1" t="s" s="1"><v>1</v></c>
      <c r="C1" t="s" s="1"><v>2</v></c>
      <c r="D1" t="s" s="1"><v>3</v></c>
      <c r="E1" t="s" s="1"><v>4</v></c>
      <c r="F1" t="s" s="1"><v>5</v></c>
      <c r="G1" t="s" s="1"><v>6</v></c>
    </row>
    <row r="2">
      <c r="A2" t="s"><v>7</v></c>
      <c r="B2"><v>150000</v></c>
      <c r="C2"><v>185420</v></c>
      <c r="D2"><f>C2-B2</f><v>35420</v></c>
      <c r="E2"><f>(C2-B2)/B2</f><v>0.236</v></c>
      <c r="F2" s="2"><v>45180</v></c>
      <c r="G2" t="s"><v>12</v></c>
    </row>
    <row r="3">
      <c r="A3" t="s"><v>8</v></c>
      <c r="B3"><v>120000</v></c>
      <c r="C3"><v>124800</v></c>
      <c r="D3"><f>C3-B3</f><v>4800</v></c>
      <c r="E3"><f>(C3-B3)/B3</f><v>0.04</v></c>
      <c r="F3" s="2"><v>45182</v></c>
      <c r="G3" t="s"><v>13</v></c>
    </row>
    <row r="4">
      <c r="A4" t="s"><v>9</v></c>
      <c r="B4"><v>200000</v></c>
      <c r="C4"><v>235000</v></c>
      <c r="D4"><f>C4-B4</f><v>35000</v></c>
      <c r="E4"><f>(C4-B4)/B4</f><v>0.175</v></c>
      <c r="F4" s="2"><v>45185</v></c>
      <c r="G4" t="s"><v>12</v></c>
    </row>
    <row r="5">
      <c r="A5" t="s"><v>10</v></c>
      <c r="B5"><v>80000</v></c>
      <c r="C5"><v>76200</v></c>
      <c r="D5"><f>C5-B5</f><v>-3800</v></c>
      <c r="E5"><f>(C5-B5)/B5</f><v>-0.0475</v></c>
      <c r="F5" s="2"><v>45188</v></c>
      <c r="G5" t="s"><v>14</v></c>
    </row>
    <row r="6">
      <c r="A6" t="s"><v>11</v></c>
      <c r="B6"><v>60000</v></c>
      <c r="C6"><v>64100</v></c>
      <c r="D6"><f>C6-B6</f><v>4100</v></c>
      <c r="E6"><f>(C6-B6)/B6</f><v>0.068</v></c>
      <c r="F6" s="2"><v>45190</v></c>
      <c r="G6" t="s"><v>13</v></c>
    </row>
  </sheetData>
</worksheet>""".trimIndent()

    // Sheet 2: Regional Summary
    val sheet2Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    <row r="1">
      <c r="A1" t="s" s="1"><v>15</v></c>
      <c r="B1" t="s" s="1"><v>16</v></c>
      <c r="C1" t="s" s="1"><v>17</v></c>
      <c r="D1" t="s" s="1"><v>18</v></c>
    </row>
    <row r="2">
      <c r="A2" t="s"><v>19</v></c>
      <c r="B2"><v>65</v></c>
      <c r="C2"><v>74</v></c>
      <c r="D2"><v>1.14</v></c>
    </row>
    <row r="3">
      <c r="A3" t="s"><v>20</v></c>
      <c r="B3"><v>99.9</v></c>
      <c r="C3"><v>99.98</v></c>
      <c r="D3"><v>1.00</v></c>
    </row>
    <row r="4">
      <c r="A4" t="s"><v>21</v></c>
      <c r="B4"><v>45</v></c>
      <c r="C4"><v>52</v></c>
      <c r="D4"><v>1.15</v></c>
    </row>
  </sheetData>
</worksheet>""".trimIndent()

    val baos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baos).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml"))
        zos.write(contentTypesXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("_rels/.rels"))
        zos.write(rootRelsXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("xl/workbook.xml"))
        zos.write(workbookXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("xl/_rels/workbook.xml.rels"))
        zos.write(workbookRelsXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("xl/sharedStrings.xml"))
        zos.write(sharedStringsXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("xl/styles.xml"))
        zos.write(stylesXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"))
        zos.write(sheet1Xml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet2.xml"))
        zos.write(sheet2Xml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
    return baos.toByteArray()
}

private fun createSampleOdt(): ByteArray {
    val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:styles>
    <style:style style:name="Standard" style:family="paragraph"/>
    <style:style style:name="Heading_1" style:family="paragraph">
      <style:text-properties fo:font-size="20pt" fo:font-weight="bold" fo:color="#8E24AA"/>
    </style:style>
    <style:style style:name="BoldHighlight" style:family="text">
      <style:text-properties fo:font-weight="bold" fo:background-color="#FFF59D" fo:color="#000000"/>
    </style:style>
  </office:styles>
</office:document-styles>""".trimIndent()

    val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:automatic-styles>
    <style:style style:name="P_Center" style:family="paragraph">
      <style:paragraph-properties fo:text-align="center"/>
    </style:style>
    <style:style style:name="T_Blue" style:family="text">
      <style:text-properties fo:font-weight="bold" fo:color="#1E88E5"/>
    </style:style>
    <style:style style:name="T_Green" style:family="text">
      <style:text-properties fo:font-style="italic" fo:color="#43A047"/>
    </style:style>
    <style:style style:name="HeaderCell" style:family="paragraph">
      <style:paragraph-properties fo:background-color="#4A148C"/>
      <style:text-properties fo:font-weight="bold" fo:color="#FFFFFF"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:text>
      <text:h text:outline-level="1" text:style-name="Heading_1">DragonView OpenDocument Engine</text:h>
      <text:p text:style-name="P_Center"><text:span text:style-name="T_Blue">High-Performance ODF Streaming</text:span><text:s text:c="2"/>•<text:s text:c="2"/><text:span text:style-name="T_Green">Universal Model Integration</text:span></text:p>
      <text:p>OpenDocument Text (.odt) files are decompressed on-the-fly and processed via streaming XmlPullParser. All styles from styles.xml and content.xml are compiled and mapped directly into DragonView's shared DocumentElement model.</text:p>
      <text:h text:outline-level="2">Engine Capabilities</text:h>
      <text:list>
        <text:list-item><text:p><text:span text:style-name="BoldHighlight">Native XmlPullParser Pipeline:</text:span> Zero third-party Java/Kotlin office framework bloat.</text:p></text:list-item>
        <text:list-item><text:p><text:span text:style-name="T_Blue">Unified Document Model:</text:span> Slots directly into DocumentRenderer with full pinch-zoom &amp; pan support.</text:p></text:list-item>
        <text:list-item><text:p><text:span text:style-name="T_Green">Multi-Format Compatibility:</text:span> Supports .odt documents, .ott templates, and .fodt flat XML files.</text:p></text:list-item>
      </text:list>
      <table:table table:name="MetricsTable">
        <table:table-row>
          <table:table-cell><text:p text:style-name="HeaderCell">ODF Type</text:p></table:table-cell>
          <table:table-cell><text:p text:style-name="HeaderCell">Parsing Engine</text:p></table:table-cell>
          <table:table-cell><text:p text:style-name="HeaderCell">Memory Footprint</text:p></table:table-cell>
        </table:table-row>
        <table:table-row>
          <table:table-cell><text:p>.odt (Text Document)</text:p></table:table-cell>
          <table:table-cell><text:p>ZipStream + XmlPullParser</text:p></table:table-cell>
          <table:table-cell><text:p>&lt; 14 MB</text:p></table:table-cell>
        </table:table-row>
        <table:table-row>
          <table:table-cell><text:p>.ott (Document Template)</text:p></table:table-cell>
          <table:table-cell><text:p>ZipStream + XmlPullParser</text:p></table:table-cell>
          <table:table-cell><text:p>&lt; 14 MB</text:p></table:table-cell>
        </table:table-row>
        <table:table-row>
          <table:table-cell><text:p>.fodt (Flat XML)</text:p></table:table-cell>
          <table:table-cell><text:p>Direct XmlPullParser</text:p></table:table-cell>
          <table:table-cell><text:p>&lt; 8 MB</text:p></table:table-cell>
        </table:table-row>
      </table:table>
    </office:text>
  </office:body>
</office:document-content>""".trimIndent()

    val baos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baos).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry("mimetype"))
        zos.write("application/vnd.oasis.opendocument.text".toByteArray(Charsets.US_ASCII))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("styles.xml"))
        zos.write(stylesXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("content.xml"))
        zos.write(contentXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
    return baos.toByteArray()
}

private fun createSampleFodt(): ByteArray {
    val fodtXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:automatic-styles>
    <style:style style:name="FodtHeading" style:family="paragraph">
      <style:text-properties fo:font-size="22pt" fo:font-weight="bold" fo:color="#AB47BC"/>
    </style:style>
    <style:style style:name="FodtSub" style:family="paragraph">
      <style:paragraph-properties fo:text-align="center"/>
      <style:text-properties fo:font-style="italic" fo:color="#78909C"/>
    </style:style>
    <style:style style:name="FodtHighlight" style:family="text">
      <style:text-properties fo:font-weight="bold" fo:color="#00897B"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:text>
      <text:h text:outline-level="1" text:style-name="FodtHeading">Flat OpenDocument Text (.fodt)</text:h>
      <text:p text:style-name="FodtSub">Single Uncompressed XML File — Direct Parse Bypass</text:p>
      <text:p>This file is stored as pure XML without the standard PK (ZIP) container. DragonView detects the <text:span text:style-name="FodtHighlight">.fodt</text:span> extension and XML header, bypassing the zip decompression phase entirely.</text:p>
      <text:h text:outline-level="2">Key Benefits</text:h>
      <text:list>
        <text:list-item><text:p><text:span text:style-name="FodtHighlight">Zero Decompression Overhead:</text:span> Opens instantly via InputStream directly to XmlPullParser.</text:p></text:list-item>
        <text:list-item><text:p><text:span text:style-name="FodtHighlight">Unified Styling:</text:span> Styles and body elements parsed in a single sequential pass.</text:p></text:list-item>
        <text:list-item><text:p><text:span text:style-name="FodtHighlight">Seamless UX:</text:span> Renders using the same buttery-smooth native RecyclerView as DOCX and RTF.</text:p></text:list-item>
      </text:list>
    </office:text>
  </office:body>
</office:document>""".trimIndent()

    return fodtXml.toByteArray(Charsets.UTF_8)
}

private fun createSampleOds(): ByteArray {
    val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:styles>
    <style:style style:name="HeaderStyle" style:family="table-cell">
      <style:text-properties fo:font-weight="bold" fo:color="#FFFFFF"/>
      <style:table-cell-properties fo:background-color="#2E7D32"/>
    </style:style>
    <style:style style:name="SummaryStyle" style:family="table-cell">
      <style:text-properties fo:font-weight="bold" fo:color="#1B5E20"/>
    </style:style>
  </office:styles>
</office:document-styles>""".trimIndent()

    val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:automatic-styles>
    <style:style style:name="ce_header" style:family="table-cell">
      <style:text-properties fo:font-weight="bold" fo:color="#FFFFFF"/>
      <style:table-cell-properties fo:background-color="#1B5E20"/>
    </style:style>
    <style:style style:name="ce_bold" style:family="table-cell">
      <style:text-properties fo:font-weight="bold" fo:color="#2E7D32"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:spreadsheet>
      <table:table table:name="Revenue Forecast">
        <table:table-row>
          <table:table-cell table:style-name="ce_header" office:value-type="string"><text:p>Category</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_header" office:value-type="string"><text:p>Q1 2026</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_header" office:value-type="string"><text:p>Q2 2026</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_header" office:value-type="string"><text:p>Q3 2026</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_header" office:value-type="string"><text:p>Q4 2026</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_header" office:value-type="string"><text:p>Full Year Total</text:p></table:table-cell>
          <table:table-cell table:number-columns-repeated="16"/>
        </table:table-row>
        <table:table-row>
          <table:table-cell office:value-type="string"><text:p>Enterprise Subscriptions</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="125000" office:currency="USD"><text:p>$125,000</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="142000" office:currency="USD"><text:p>$142,000</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="168000" office:currency="USD"><text:p>$168,000</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="195000" office:currency="USD"><text:p>$195,000</text:p></table:table-cell>
          <table:table-cell table:formula="of:=SUM([.B2:.E2])" office:value-type="currency" office:value="630000" office:currency="USD"><text:p>$630,000</text:p></table:table-cell>
        </table:table-row>
        <table:table-row>
          <table:table-cell office:value-type="string"><text:p>Cloud Compute &amp; AI</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="84000" office:currency="USD"><text:p>$84,000</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="96000" office:currency="USD"><text:p>$96,000</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="115000" office:currency="USD"><text:p>$115,000</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="138000" office:currency="USD"><text:p>$138,000</text:p></table:table-cell>
          <table:table-cell table:formula="of:=SUM([.B3:.E3])" office:value-type="currency" office:value="433000" office:currency="USD"><text:p>$433,000</text:p></table:table-cell>
        </table:table-row>
        <table:table-row>
          <table:table-cell office:value-type="string"><text:p>Professional Services</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="32000" office:currency="USD"><text:p>$32,000</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="35000" office:currency="USD"><text:p>$35,000</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="38000" office:currency="USD"><text:p>$38,000</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="42000" office:currency="USD"><text:p>$42,000</text:p></table:table-cell>
          <table:table-cell table:formula="of:=SUM([.B4:.E4])" office:value-type="currency" office:value="147000" office:currency="USD"><text:p>$147,000</text:p></table:table-cell>
        </table:table-row>
        <table:table-row>
          <table:table-cell table:style-name="ce_bold" office:value-type="string"><text:p>Total Projected ARR</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_bold" office:value-type="currency" office:value="241000" office:currency="USD"><text:p>$241,000</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_bold" office:value-type="currency" office:value="273000" office:currency="USD"><text:p>$273,000</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_bold" office:value-type="currency" office:value="321000" office:currency="USD"><text:p>$321,000</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_bold" office:value-type="currency" office:value="375000" office:currency="USD"><text:p>$375,000</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_bold" table:formula="of:=SUM([.B5:.E5])" office:value-type="currency" office:value="1210000" office:currency="USD"><text:p>$1,210,000</text:p></table:table-cell>
        </table:table-row>
        <table:table-row table:number-rows-repeated="20">
          <table:table-cell table:number-columns-repeated="10"/>
        </table:table-row>
      </table:table>
      <table:table table:name="Department Expenses">
        <table:table-row>
          <table:table-cell table:style-name="ce_header" office:value-type="string"><text:p>Department</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_header" office:value-type="string"><text:p>Headcount</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_header" office:value-type="string"><text:p>Quarterly Budget</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_header" office:value-type="string"><text:p>Actual Spend</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_header" office:value-type="string"><text:p>Status</text:p></table:table-cell>
        </table:table-row>
        <table:table-row>
          <table:table-cell office:value-type="string"><text:p>Engineering &amp; Core Systems</text:p></table:table-cell>
          <table:table-cell office:value-type="float" office:value="42"><text:p>42</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="520000" office:currency="USD"><text:p>$520,000</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="495000" office:currency="USD"><text:p>$495,000</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_bold" office:value-type="string"><text:p>Under Budget</text:p></table:table-cell>
        </table:table-row>
        <table:table-row>
          <table:table-cell office:value-type="string"><text:p>Product Design &amp; UX</text:p></table:table-cell>
          <table:table-cell office:value-type="float" office:value="16"><text:p>16</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="180000" office:currency="USD"><text:p>$180,000</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="176000" office:currency="USD"><text:p>$176,000</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_bold" office:value-type="string"><text:p>Under Budget</text:p></table:table-cell>
        </table:table-row>
      </table:table>
    </office:spreadsheet>
  </office:body>
</office:document-content>""".trimIndent()

    val baos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baos).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry("mimetype"))
        zos.write("application/vnd.oasis.opendocument.spreadsheet".toByteArray(Charsets.US_ASCII))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("styles.xml"))
        zos.write(stylesXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("content.xml"))
        zos.write(contentXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
    return baos.toByteArray()
}

private fun createSampleFods(): ByteArray {
    val fodsXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:automatic-styles>
    <style:style style:name="FodsHeader" style:family="table-cell">
      <style:text-properties fo:font-weight="bold" fo:color="#FFFFFF"/>
      <style:table-cell-properties fo:background-color="#388E3C"/>
    </style:style>
    <style:style style:name="FodsHighlight" style:family="table-cell">
      <style:text-properties fo:font-weight="bold" fo:color="#2E7D32"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:spreadsheet>
      <table:table table:name="Hardware Budget">
        <table:table-row>
          <table:table-cell table:style-name="FodsHeader" office:value-type="string"><text:p>Component</text:p></table:table-cell>
          <table:table-cell table:style-name="FodsHeader" office:value-type="string"><text:p>Units</text:p></table:table-cell>
          <table:table-cell table:style-name="FodsHeader" office:value-type="string"><text:p>Unit Cost</text:p></table:table-cell>
          <table:table-cell table:style-name="FodsHeader" office:value-type="string"><text:p>Total Cost</text:p></table:table-cell>
          <table:table-cell table:number-columns-repeated="12"/>
        </table:table-row>
        <table:table-row>
          <table:table-cell office:value-type="string"><text:p>Server Racks (42U)</text:p></table:table-cell>
          <table:table-cell office:value-type="float" office:value="8"><text:p>8</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="1850" office:currency="USD"><text:p>$1,850</text:p></table:table-cell>
          <table:table-cell table:formula="of:=[.B2]*[.C2]" office:value-type="currency" office:value="14800" office:currency="USD"><text:p>$14,800</text:p></table:table-cell>
        </table:table-row>
        <table:table-row>
          <table:table-cell office:value-type="string"><text:p>NVMe Arrays (100TB)</text:p></table:table-cell>
          <table:table-cell office:value-type="float" office:value="12"><text:p>12</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="4200" office:currency="USD"><text:p>$4,200</text:p></table:table-cell>
          <table:table-cell table:formula="of:=[.B3]*[.C3]" office:value-type="currency" office:value="50400" office:currency="USD"><text:p>$50,400</text:p></table:table-cell>
        </table:table-row>
        <table:table-row>
          <table:table-cell table:style-name="FodsHighlight" office:value-type="string"><text:p>Total Hardware Investment</text:p></table:table-cell>
          <table:table-cell table:style-name="FodsHighlight" office:value-type="float" office:value="20"><text:p>20</text:p></table:table-cell>
          <table:table-cell office:value-type="string"><text:p>—</text:p></table:table-cell>
          <table:table-cell table:style-name="FodsHighlight" table:formula="of:=SUM([.D2:.D3])" office:value-type="currency" office:value="65200" office:currency="USD"><text:p>$65,200</text:p></table:table-cell>
        </table:table-row>
      </table:table>
    </office:spreadsheet>
  </office:body>
</office:document>""".trimIndent()

    return fodsXml.toByteArray(Charsets.UTF_8)
}

private fun createSampleOdp(): ByteArray {
    val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:styles>
    <style:style style:name="TitleStyle" style:family="presentation">
      <style:text-properties fo:font-size="28pt" fo:font-weight="bold" fo:color="#D84315"/>
      <style:paragraph-properties fo:text-align="center"/>
    </style:style>
    <style:style style:name="SubtitleStyle" style:family="presentation">
      <style:text-properties fo:font-size="16pt" fo:color="#5A5A5A"/>
      <style:paragraph-properties fo:text-align="center"/>
    </style:style>
    <style:style style:name="BodyStyle" style:family="presentation">
      <style:text-properties fo:font-size="16pt" fo:color="#1C1B1F"/>
    </style:style>
  </office:styles>
</office:document-styles>""".trimIndent()

    val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0">
  <office:automatic-styles>
    <style:page-layout style:name="PM1">
      <style:page-layout-properties fo:page-width="28cm" fo:page-height="21cm"/>
    </style:page-layout>
    <style:style style:name="dp1" style:family="drawing-page">
      <style:drawing-page-properties draw:fill-color="#FFFFFF"/>
    </style:style>
    <style:style style:name="FrameCard" style:family="graphic">
      <style:graphic-properties draw:fill="solid" draw:fill-color="#F5F5F5"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:presentation>
      <draw:page draw:name="Slide 1" draw:style-name="dp1">
        <draw:frame svg:x="2.0cm" svg:y="3.0cm" svg:width="24.0cm" svg:height="4.5cm" draw:style-name="FrameCard">
          <draw:text-box>
            <text:p text:style-name="TitleStyle"><text:span>DragonView Architecture Overview</text:span></text:p>
            <text:p text:style-name="SubtitleStyle"><text:span>Native OpenDocument &amp; OpenXML Presentation Engine</text:span></text:p>
          </draw:text-box>
        </draw:frame>
        <draw:frame svg:x="3.0cm" svg:y="9.0cm" svg:width="22.0cm" svg:height="8.0cm">
          <draw:text-box>
            <text:list>
              <text:list-item><text:p text:style-name="BodyStyle"><text:span>Zero-memory-leak streaming XML parser</text:span></text:p></text:list-item>
              <text:list-item><text:p text:style-name="BodyStyle"><text:span>Proportional coordinate scaling supporting real SVG units</text:span></text:p></text:list-item>
              <text:list-item><text:p text:style-name="BodyStyle"><text:span>Subsampled media pipeline with memory-safe inSampleSize</text:span></text:p></text:list-item>
            </text:list>
          </draw:text-box>
        </draw:frame>
      </draw:page>
      <draw:page draw:name="Slide 2" draw:style-name="dp1">
        <draw:frame svg:x="2.0cm" svg:y="3.0cm" svg:width="24.0cm" svg:height="3.5cm">
          <draw:text-box>
            <text:p text:style-name="TitleStyle"><text:span>Key Benchmarks &amp; Presentation Features</text:span></text:p>
          </draw:text-box>
        </draw:frame>
        <draw:frame svg:x="2.0cm" svg:y="7.5cm" svg:width="24.0cm" svg:height="9.0cm" draw:style-name="FrameCard">
          <draw:text-box>
            <text:p text:style-name="BodyStyle"><text:span>• 60 FPS ViewPager2 horizontal swipe navigation</text:span></text:p>
            <text:p text:style-name="BodyStyle"><text:span>• LookAhead prefetching on adjacent presentation slides</text:span></text:p>
            <text:p text:style-name="BodyStyle"><text:span>• Full support for .odp, .otp templates, and flat .fodp</text:span></text:p>
          </draw:text-box>
        </draw:frame>
      </draw:page>
    </office:presentation>
  </office:body>
</office:document-content>""".trimIndent()

    val baos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baos).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry("mimetype"))
        zos.write("application/vnd.oasis.opendocument.presentation".toByteArray(Charsets.US_ASCII))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("styles.xml"))
        zos.write(stylesXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("content.xml"))
        zos.write(contentXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
    return baos.toByteArray()
}

private fun createSampleFodp(): ByteArray {
    val fodpXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0">
  <office:automatic-styles>
    <style:page-layout style:name="PM1">
      <style:page-layout-properties fo:page-width="28cm" fo:page-height="21cm"/>
    </style:page-layout>
    <style:style style:name="TitleStyle" style:family="presentation">
      <style:text-properties fo:font-size="26pt" fo:font-weight="bold" fo:color="#E65100"/>
    </style:style>
    <style:style style:name="BodyStyle" style:family="presentation">
      <style:text-properties fo:font-size="16pt" fo:color="#212121"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:presentation>
      <draw:page draw:name="Roadmap Slide 1">
        <draw:frame svg:x="2.0cm" svg:y="2.5cm" svg:width="24.0cm" svg:height="4.0cm">
          <draw:text-box>
            <text:p text:style-name="TitleStyle"><text:span>Flat XML OpenDocument Presentation (.fodp)</text:span></text:p>
          </draw:text-box>
        </draw:frame>
        <draw:frame svg:x="2.0cm" svg:y="7.0cm" svg:width="24.0cm" svg:height="10.0cm">
          <draw:text-box>
            <text:list>
              <text:list-item><text:p text:style-name="BodyStyle"><text:span>Direct stream parsing without ZIP decompression overhead</text:span></text:p></text:list-item>
              <text:list-item><text:p text:style-name="BodyStyle"><text:span>High-density SVG layout and styling properties</text:span></text:p></text:list-item>
              <text:list-item><text:p text:style-name="BodyStyle"><text:span>Instantaneous slide switching via ViewPager2 adapter</text:span></text:p></text:list-item>
            </text:list>
          </draw:text-box>
        </draw:frame>
      </draw:page>
    </office:presentation>
  </office:body>
</office:document>""".trimIndent()

    return fodpXml.toByteArray(Charsets.UTF_8)
}

private fun createSampleOdg(): ByteArray {
    val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0">
  <office:styles>
    <style:style style:name="HeaderBox" style:family="graphic">
      <style:graphic-properties draw:fill="solid" draw:fill-color="#3B151C" svg:stroke-color="#D32F2F" svg:stroke-width="0.08cm" draw:stroke="solid"/>
    </style:style>
    <style:style style:name="CyanNode" style:family="graphic">
      <style:graphic-properties draw:fill="solid" draw:fill-color="#0E2E3B" svg:stroke-color="#00ACC1" svg:stroke-width="0.06cm" draw:stroke="solid"/>
    </style:style>
    <style:style style:name="PurplePolygon" style:family="graphic">
      <style:graphic-properties draw:fill="solid" draw:fill-color="#2D1136" svg:stroke-color="#AB47BC" svg:stroke-width="0.06cm" draw:stroke="solid"/>
    </style:style>
    <style:style style:name="AccentStroke" style:family="graphic">
      <style:graphic-properties svg:stroke-color="#FF5252" svg:stroke-width="0.08cm" draw:stroke="solid"/>
    </style:style>
  </office:styles>
</office:document-styles>""".trimIndent()

    val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:automatic-styles>
    <style:page-layout style:name="PM1">
      <style:page-layout-properties fo:page-width="21cm" fo:page-height="29.7cm"/>
    </style:page-layout>
  </office:automatic-styles>
  <office:body>
    <office:drawing>
      <draw:page draw:name="Architecture Diagram">
        <draw:rect svg:x="1.5cm" svg:y="2.0cm" svg:width="18.0cm" svg:height="3.5cm" draw:corner-radius="0.4cm" draw:style-name="HeaderBox">
          <text:p><text:span>DragonView ODG Vector Engine</text:span></text:p>
          <text:p><text:span>High-Performance Native Canvas Graphics</text:span></text:p>
        </draw:rect>
        <draw:ellipse svg:cx="5.5cm" svg:cy="8.5cm" svg:rx="3.0cm" svg:ry="2.0cm" draw:style-name="CyanNode">
          <text:p><text:span>Vector Ellipse</text:span></text:p>
        </draw:ellipse>
        <draw:line svg:x1="9.0cm" svg:y1="8.5cm" svg:x2="12.5cm" svg:y2="8.5cm" draw:style-name="AccentStroke"/>
        <draw:polygon draw:points="14.0cm,6.5cm 18.0cm,6.5cm 19.5cm,9.5cm 16.0cm,11.5cm 12.5cm,9.5cm" draw:style-name="PurplePolygon">
          <text:p><text:span>Polygon</text:span></text:p>
        </draw:polygon>
        <draw:path svg:d="M 50 50 C 150 150 250 0 350 100 S 550 50 650 120" svg:x="1.5cm" svg:y="13.0cm" svg:width="18.0cm" svg:height="4.0cm" draw:style-name="AccentStroke"/>
        <draw:g draw:transform="translate(60, 650)">
          <draw:rect svg:x="1.0cm" svg:y="1.0cm" svg:width="6.5cm" svg:height="2.5cm" draw:corner-radius="0.3cm" draw:style-name="CyanNode">
            <text:p><text:span>Nested Group Shape 1</text:span></text:p>
          </draw:rect>
          <draw:rect svg:x="9.5cm" svg:y="1.0cm" svg:width="6.5cm" svg:height="2.5cm" draw:corner-radius="0.3cm" draw:style-name="HeaderBox">
            <text:p><text:span>Nested Group Shape 2</text:span></text:p>
          </draw:rect>
        </draw:g>
      </draw:page>
    </office:drawing>
  </office:body>
</office:document-content>""".trimIndent()

    val manifestXml = """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
  <manifest:file-entry manifest:full-path="/" manifest:version="1.2" manifest:media-type="application/vnd.oasis.opendocument.graphics"/>
  <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
  <manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>
</manifest:manifest>""".trimIndent()

    val baos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baos).use { zos ->
        val mimeEntry = java.util.zip.ZipEntry("mimetype")
        zos.putNextEntry(mimeEntry)
        zos.write("application/vnd.oasis.opendocument.graphics".toByteArray(Charsets.US_ASCII))
        zos.closeEntry()

        val manifestEntry = java.util.zip.ZipEntry("META-INF/manifest.xml")
        zos.putNextEntry(manifestEntry)
        zos.write(manifestXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        val stylesEntry = java.util.zip.ZipEntry("styles.xml")
        zos.putNextEntry(stylesEntry)
        zos.write(stylesXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        val contentEntry = java.util.zip.ZipEntry("content.xml")
        zos.putNextEntry(contentEntry)
        zos.write(contentXml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    return baos.toByteArray()
}

private fun createSampleFodg(): ByteArray {
    val fodgXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    office:mimetype="application/vnd.oasis.opendocument.graphics-flat-xml">
  <office:automatic-styles>
    <style:page-layout style:name="PM1">
      <style:page-layout-properties fo:page-width="21cm" fo:page-height="29.7cm"/>
    </style:page-layout>
    <style:style style:name="CardA" style:family="graphic">
      <style:graphic-properties draw:fill="solid" draw:fill-color="#1B2A4A" svg:stroke-color="#42A5F5" svg:stroke-width="0.08cm" draw:stroke="solid"/>
    </style:style>
    <style:style style:name="CardB" style:family="graphic">
      <style:graphic-properties draw:fill="solid" draw:fill-color="#3E1A24" svg:stroke-color="#EF5350" svg:stroke-width="0.08cm" draw:stroke="solid"/>
    </style:style>
    <style:style style:name="LineStyle" style:family="graphic">
      <style:graphic-properties svg:stroke-color="#26C6DA" svg:stroke-width="0.08cm" draw:stroke="solid"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:drawing>
      <draw:page draw:name="Flat Drawing Page">
        <draw:rect svg:x="1.5cm" svg:y="2.0cm" svg:width="18.0cm" svg:height="3.0cm" draw:corner-radius="0.4cm" draw:style-name="CardA">
          <text:p><text:span>Flat OpenDocument Drawing (.fodg)</text:span></text:p>
          <text:p><text:span>Streamed directly without unzipping overhead</text:span></text:p>
        </draw:rect>
        <draw:ellipse svg:cx="6.0cm" svg:cy="8.0cm" svg:rx="3.5cm" svg:ry="2.0cm" draw:style-name="CardB">
          <text:p><text:span>Processing Node</text:span></text:p>
        </draw:ellipse>
        <draw:line svg:x1="10.0cm" svg:y1="8.0cm" svg:x2="13.5cm" svg:y2="8.0cm" draw:style-name="LineStyle"/>
        <draw:rect svg:x="14.0cm" svg:y="6.5cm" svg:width="5.5cm" svg:height="3.0cm" draw:corner-radius="0.2cm" draw:style-name="CardA">
          <text:p><text:span>Output Sink</text:span></text:p>
        </draw:rect>
        <draw:path svg:d="M 50 100 L 150 50 L 250 150 L 350 80 L 450 120" svg:x="2.0cm" svg:y="12.0cm" svg:width="16.0cm" svg:height="4.0cm" draw:style-name="LineStyle"/>
      </draw:page>
    </office:drawing>
  </office:body>
</office:document>""".trimIndent()

    return fodgXml.toByteArray(Charsets.UTF_8)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "Unknown size"
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> "%.1f MB".format(bytes / (1024f * 1024f))
    }
}
