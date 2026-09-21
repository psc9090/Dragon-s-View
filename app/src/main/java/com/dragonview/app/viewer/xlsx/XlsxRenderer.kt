// app/src/main/java/com/dragonview/app/viewer/xlsx/XlsxRenderer.kt
package com.dragonview.app.viewer.xlsx

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.FrameLayout
import androidx.compose.runtime.mutableFloatStateOf
import com.otaliastudios.zoom.ZoomLayout
import com.dragonview.app.ui.ZoomHelper
import com.dragonview.app.performance.JankMonitor
import com.dragonview.app.performance.LookAheadPrefetcher
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatDetectionResult
import com.dragonview.app.viewer.ods.OdsParser
import com.dragonview.app.ui.theme.DragonDarkBackground
import com.dragonview.app.ui.theme.DragonDarkBorder
import com.dragonview.app.ui.theme.DragonDarkSurface
import com.dragonview.app.ui.theme.DragonDarkSurfaceContainer
import com.dragonview.app.ui.theme.DragonDarkTextMuted
import com.dragonview.app.ui.theme.DragonDarkTextPrimary
import com.dragonview.app.ui.theme.DragonDarkTextSecondary
import com.dragonview.app.ui.theme.DragonFlame
import kotlinx.coroutines.launch

/**
 * Modern Compose viewer for Microsoft Excel (.xlsx) spreadsheets.
 *
 * Capabilities:
 * - Lazy on-demand sheet parsing with 2-item LRU sheet cache.
 * - Frozen header row (Row 1) and frozen first column (Column A).
 * - Interactive Excel formula & cell inspector bar.
 * - Sheet tab strip for switching worksheets.
 * - Graceful isolated sheet error handling.
 */
@Composable
fun XlsxRenderer(
    uri: Uri,
    detectionResult: FormatDetectionResult,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isInitializing by remember { mutableStateOf(true) }
    var workbookError by remember { mutableStateOf<String?>(null) }
    var parserInstance by remember { mutableStateOf<SpreadsheetParser?>(null) }

    var selectedSheetIndex by remember { mutableIntStateOf(0) }
    var currentSheetState by remember { mutableStateOf<SheetLoadState>(SheetLoadState.Loading) }
    var activeCell by remember { mutableStateOf<XlsxCell?>(null) }
    var rowLimit by remember { mutableIntStateOf(1000) }
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var zoomLayoutRef by remember { mutableStateOf<ZoomLayout?>(null) }

    val prefetcher = remember(parserInstance) {
        LookAheadPrefetcher(coroutineScope) { targetSheetIndex ->
            val parser = parserInstance ?: return@LookAheadPrefetcher
            val sheetRef = parser.workbook.sheets.getOrNull(targetSheetIndex) ?: return@LookAheadPrefetcher
            // Pre-warm the sheet in the background LRU cache with small row limit
            parser.loadSheet(sheetRef, maxRows = 200)
        }
    }

    DisposableEffect(Unit) {
        JankMonitor.start()
        onDispose {
            JankMonitor.stop()
            prefetcher.cancelPending()
        }
    }

    // Initialize workbook & shared strings
    LaunchedEffect(uri) {
        isInitializing = true
        workbookError = null
        val result: Result<SpreadsheetParser> = when (detectionResult.format) {
            FileFormat.ODS, FileFormat.ODS_TEMPLATE, FileFormat.ODS_FLAT -> {
                OdsParser.create(context, uri, detectionResult.format)
            }
            else -> {
                when (detectionResult.extension.lowercase()) {
                    "ods", "ots", "fods" -> OdsParser.create(context, uri, detectionResult.format)
                    else -> XlsxParser.create(context, uri)
                }
            }
        }
        result.onSuccess { parser ->
            parserInstance = parser
            isInitializing = false
            selectedSheetIndex = 0
        }.onFailure { error ->
            val isOds = detectionResult.format in listOf(FileFormat.ODS, FileFormat.ODS_TEMPLATE, FileFormat.ODS_FLAT) ||
                detectionResult.extension.lowercase() in listOf("ods", "ots", "fods")
            val defaultMsg = if (isOds) {
                "This ODS file appears corrupted or uses an unsupported structure."
            } else {
                "This XLSX file appears corrupted or uses an unsupported structure."
            }
            workbookError = error.localizedMessage ?: defaultMsg
            isInitializing = false
        }
    }

    // Load active sheet on demand
    LaunchedEffect(parserInstance, selectedSheetIndex, rowLimit) {
        val parser = parserInstance ?: return@LaunchedEffect
        val sheetRef = parser.workbook.sheets.getOrNull(selectedSheetIndex) ?: return@LaunchedEffect

        currentSheetState = SheetLoadState.Loading
        currentSheetState = parser.loadSheet(sheetRef, maxRows = rowLimit)
        prefetcher.onPositionChanged(selectedSheetIndex, parser.workbook.sheets.size)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DragonDarkBackground)
    ) {
        when {
            isInitializing -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = DragonFlame,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Unpacking spreadsheet workbook...",
                        color = DragonDarkTextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            workbookError != null -> {
                Surface(
                    color = DragonDarkSurfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
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
                            text = "Spreadsheet Error",
                            style = MaterialTheme.typography.titleMedium,
                            color = DragonDarkTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = workbookError ?: "This XLSX file appears corrupted or uses an unsupported structure.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DragonDarkTextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            parserInstance != null -> {
                val parser = parserInstance!!
                val sheets = parser.workbook.sheets
                val activeSheetRef = sheets.getOrNull(selectedSheetIndex)

                Column(modifier = Modifier.fillMaxSize()) {
                    // Top Spreadsheet Toolbar
                    XlsxToolbar(
                        title = detectionResult.fileName.ifEmpty { parser.workbook.title },
                        sheetCount = sheets.size,
                        activeSheetName = activeSheetRef?.name ?: "Sheet 1",
                        zoomScale = zoomScale,
                        onResetZoom = {
                            zoomScale = 1.0f
                            zoomLayoutRef?.engine?.zoomTo(1.0f, true)
                        },
                        onBack = onBack
                    )

                    // Cell Inspector / Formula Bar
                    CellInspectorBar(cell = activeCell)

                    // Sheet Grid Content
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        when (val state = currentSheetState) {
                            is SheetLoadState.Loading -> {
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(
                                        color = DragonFlame,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Loading ${activeSheetRef?.name ?: "worksheet"}...",
                                        color = DragonDarkTextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }

                            is SheetLoadState.Error -> {
                                Surface(
                                    color = DragonDarkSurfaceContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder),
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(24.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = Color(0xFFEF5350),
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = state.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = DragonDarkTextSecondary,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }

                            is SheetLoadState.Success -> {
                                val sheet = state.sheet

                                Column(modifier = Modifier.fillMaxSize()) {
                                    // 4-pane scrollable spreadsheet grid wrapped in ZoomLayout
                                    AndroidView(
                                        factory = { ctx ->
                                            val gridView = SpreadsheetGridView(ctx).apply {
                                                setOnCellSelectedListener { cell ->
                                                    activeCell = cell
                                                }
                                                setSheetData(sheet)
                                            }
                                            val zoomLayout = ZoomHelper.createZoomLayout(
                                                context = ctx,
                                                contentView = gridView,
                                                tag = "ZoomLayout_Xlsx",
                                                onZoomChanged = { scale ->
                                                    zoomScale = scale
                                                }
                                            )
                                            zoomLayoutRef = zoomLayout
                                            zoomLayout
                                        },
                                        update = { zoomLayout ->
                                            zoomLayoutRef = zoomLayout
                                            val gridView = zoomLayout.getChildAt(0) as? SpreadsheetGridView
                                            gridView?.setSheetData(sheet)
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                    )

                                    // Pagination Banner if sheet exceeds initial row limit
                                    if (sheet.hasMoreRows) {
                                        Surface(
                                            color = DragonDarkSurfaceContainer,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Showing first ${sheet.totalRowsParsed} rows",
                                                    fontSize = 12.sp,
                                                    color = DragonDarkTextMuted
                                                )
                                                OutlinedButton(
                                                    onClick = { rowLimit += 1000 },
                                                    colors = ButtonDefaults.outlinedButtonColors(
                                                        contentColor = DragonFlame
                                                    ),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Load More", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Bottom Sheet Tabs Strip
                    SheetTabBar(
                        sheets = sheets,
                        selectedIndex = selectedSheetIndex,
                        onSelectSheet = { idx ->
                            if (selectedSheetIndex != idx) {
                                selectedSheetIndex = idx
                                activeCell = null
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun XlsxToolbar(
    title: String,
    sheetCount: Int,
    activeSheetName: String,
    zoomScale: Float = 1.0f,
    onResetZoom: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null
) {
    Surface(
        color = DragonDarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("xlsx_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Home",
                            tint = DragonDarkTextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2E7D32).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.TableChart,
                        contentDescription = null,
                        tint = Color(0xFF81C784),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = DragonDarkTextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Excel Spreadsheet • $sheetCount ${if (sheetCount == 1) "sheet" else "sheets"}",
                        fontSize = 11.sp,
                        color = DragonDarkTextMuted
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (zoomScale != 1.0f) {
                    Surface(
                        color = DragonFlame.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { onResetZoom?.invoke() }
                    ) {
                        Text(
                            text = "${(zoomScale * 100).toInt()}% (Reset)",
                            color = DragonFlame,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Surface(
                    color = DragonFlame.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = activeSheetName,
                        color = DragonFlame,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CellInspectorBar(cell: XlsxCell?) {
    Surface(
        color = DragonDarkSurfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Coordinate badge (e.g. "B4")
            val coordText = if (cell != null) {
                "${indexToColumnLetter(cell.colIndex)}${cell.rowIndex}"
            } else {
                "A1"
            }
            Surface(
                color = DragonDarkBackground,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder)
            ) {
                Text(
                    text = coordText,
                    color = DragonFlame,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.Functions,
                contentDescription = null,
                tint = DragonDarkTextMuted,
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Value or formula display
            val contentText = when {
                cell == null -> "Select a cell to inspect formula and value"
                cell.formula != null -> "=${cell.formula} (${cell.displayValue})"
                else -> cell.displayValue.ifEmpty { "(empty)" }
            }

            Text(
                text = contentText,
                color = if (cell != null) DragonDarkTextPrimary else DragonDarkTextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Cell Type Badge
            if (cell != null && cell.type != CellType.BLANK) {
                Spacer(modifier = Modifier.width(6.dp))
                val badgeColor = when (cell.type) {
                    CellType.NUMBER -> Color(0xFF64B5F6)
                    CellType.DATE -> Color(0xFFFFB74D)
                    CellType.FORMULA -> Color(0xFFBA68C8)
                    CellType.BOOLEAN -> Color(0xFF81C784)
                    CellType.ERROR -> Color(0xFFE57373)
                    CellType.TEXT -> DragonDarkTextMuted
                    CellType.BLANK -> Color.Transparent
                }

                Surface(
                    color = badgeColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = cell.type.name,
                        color = badgeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetTabBar(
    sheets: List<XlsxSheetRef>,
    selectedIndex: Int,
    onSelectSheet: (Int) -> Unit
) {
    val scrollState = rememberScrollState()

    Surface(
        color = DragonDarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for ((idx, sheetRef) in sheets.withIndex()) {
                val isSelected = (idx == selectedIndex)
                Surface(
                    color = if (isSelected) DragonFlame else DragonDarkSurfaceContainer,
                    shape = RoundedCornerShape(6.dp),
                    border = if (!isSelected) androidx.compose.foundation.BorderStroke(1.dp, DragonDarkBorder) else null,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clickable { onSelectSheet(idx) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = sheetRef.name,
                            color = if (isSelected) Color.White else DragonDarkTextPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shared spreadsheet renderer for XLSX, ODS, OTS, and FODS.
 */
@Composable
fun SpreadsheetRenderer(
    uri: Uri,
    detectionResult: FormatDetectionResult,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    XlsxRenderer(
        uri = uri,
        detectionResult = detectionResult,
        modifier = modifier,
        onBack = onBack
    )
}

