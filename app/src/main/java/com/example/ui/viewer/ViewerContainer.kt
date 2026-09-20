package com.example.ui.viewer

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.parser.docx.DocxParser
import com.example.parser.drawing.OdgParser
import com.example.parser.odf.OdfParser
import com.example.parser.presentation.OdpParser
import com.example.parser.presentation.PptxParser
import com.example.parser.rtf.RtfParser
import com.example.parser.spreadsheet.CsvParser
import com.example.parser.spreadsheet.OdsParser
import com.example.parser.spreadsheet.XlsxParser
import com.example.ui.dialogs.PropertiesDialog
import com.example.ui.theme.*
import com.example.util.RecentFilesStore
import com.example.util.ResumePositionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerContainer(
  fileSource: FileSource,
  onBack: () -> Unit,
  onOpenInnerFile: (FileSource) -> Unit = {}
) {
  val context = LocalContext.current
  val recentsStore = remember { RecentFilesStore(context) }
  val resumeStore = remember { ResumePositionStore(context) }

  var detectionResult by remember { mutableStateOf<DetectionResult?>(null) }
  var failure by remember { mutableStateOf<OpenFailure?>(null) }
  var isNightMode by remember { mutableStateOf(false) }
  var isSearching by remember { mutableStateOf(false) }
  var searchQuery by remember { mutableStateOf("") }
  var showMenu by remember { mutableStateOf(false) }
  var showProperties by remember { mutableStateOf(false) }
  var showXRay by remember { mutableStateOf(false) }
  var forceTextView by remember { mutableStateOf(false) }
  var forceHexView by remember { mutableStateOf(false) }

  // Navigation / page state
  var currentPage by remember { mutableStateOf(1) }
  var totalPages by remember { mutableStateOf(1) }

  // Parsed models
  var parsedDocument by remember { mutableStateOf<DocumentModel?>(null) }
  var parsedWorkbook by remember { mutableStateOf<SheetWorkbook?>(null) }
  var parsedPresentation by remember { mutableStateOf<PresentationModel?>(null) }
  var parsedDrawing by remember { mutableStateOf<DrawingModel?>(null) }
  var isLoading by remember { mutableStateOf(true) }

  // TTS Engine
  var tts by remember { mutableStateOf<TextToSpeech?>(null) }
  var isSpeaking by remember { mutableStateOf(false) }

  DisposableEffect(Unit) {
    val engine = TextToSpeech(context) { status ->
      if (status == TextToSpeech.SUCCESS) {
        // ready
      }
    }
    tts = engine
    onDispose {
      try {
        engine.stop()
        engine.shutdown()
      } catch (_: Exception) {}
      // Persist resume position
      resumeStore.savePosition(fileSource.uri.toString(), currentPage)
    }
  }

  // Parse document in background
  LaunchedEffect(fileSource) {
    isLoading = true
    withContext(Dispatchers.IO) {
      try {
        val detected = FormatRouter.detect(context, fileSource)
        detectionResult = detected

        // Record in recents
        recentsStore.addOrUpdate(
          RecentFile(
            uriString = fileSource.uri.toString(),
            displayName = fileSource.displayName,
            format = detected.format,
            sizeBytes = fileSource.sizeBytes,
            lastOpenedTimestamp = System.currentTimeMillis()
          )
        )

        when (detected.format) {
          FileFormat.DOCX -> {
            parsedDocument = DocxParser.parse(fileSource)
          }
          FileFormat.RTF -> {
            parsedDocument = RtfParser.parse(fileSource)
          }
          FileFormat.ODT -> {
            parsedDocument = OdfParser.parse(fileSource)
          }
          FileFormat.XLSX -> {
            parsedWorkbook = XlsxParser.parse(fileSource)
          }
          FileFormat.ODS -> {
            parsedWorkbook = OdsParser.parse(fileSource)
          }
          FileFormat.CSV -> {
            parsedWorkbook = CsvParser.parse(fileSource)
          }
          FileFormat.PPTX -> {
            parsedPresentation = PptxParser.parse(fileSource)
          }
          FileFormat.ODP -> {
            parsedPresentation = OdpParser.parse(fileSource)
          }
          FileFormat.ODG -> {
            parsedDrawing = OdgParser.parse(fileSource)
          }
          FileFormat.LEGACY_OFFICE -> {
            failure = OpenFailure.UnsupportedKnownFormat(FileFormat.LEGACY_OFFICE)
          }
          FileFormat.APPLE_IWORK -> {
            failure = OpenFailure.UnsupportedKnownFormat(FileFormat.APPLE_IWORK)
          }
          FileFormat.ENCRYPTED_OFFICE -> {
            failure = OpenFailure.PasswordRequired(PasswordKind.OFFICE)
          }
          FileFormat.UNKNOWN -> {
            failure = OpenFailure.UnknownFormat
          }
          else -> {
            // PDF, IMAGE, CODE, MARKDOWN, ARCHIVE handled directly by their viewers
          }
        }
      } catch (e: Exception) {
        failure = OpenFailure.CorruptedStructure("Parsing", e)
      } finally {
        isLoading = false
      }
    }
  }

  if (showXRay) {
    XRayInspectorScreen(fileSource = fileSource, onBack = { showXRay = false })
    return
  }

  val activeFailure = failure
  if (activeFailure != null && !forceTextView && !forceHexView) {
    UnsupportedScreen(
      fileSource = fileSource,
      failure = activeFailure,
      onBack = onBack,
      onViewAsText = { forceTextView = true },
      onViewAsHex = { forceHexView = true }
    )
    return
  }

  val currentFormat = detectionResult?.format ?: FileFormat.UNKNOWN

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = fileSource.displayName,
              color = TextOnDark,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1
            )
            if (totalPages > 1) {
              Text(
                text = "Page $currentPage / $totalPages",
                color = TextOnDarkSecondary,
                fontSize = 11.sp
              )
            }
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextOnDark)
          }
        },
        actions = {
          IconButton(onClick = { isSearching = !isSearching }) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = TextOnDark)
          }
          IconButton(onClick = { isNightMode = !isNightMode }) {
            Icon(
              if (isNightMode) Icons.Default.LightMode else Icons.Default.DarkMode,
              contentDescription = "Night Mode",
              tint = if (isNightMode) DragonRed else TextOnDark
            )
          }
          IconButton(onClick = { showMenu = !showMenu }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = TextOnDark)
          }

          DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(AshSurface)
          ) {
            if (currentFormat.isZipBased) {
              DropdownMenuItem(
                text = { Text("Dragon X-Ray", color = DragonRed, fontWeight = FontWeight.Bold) },
                leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null, tint = DragonRed) },
                onClick = {
                  showMenu = false
                  showXRay = true
                }
              )
              HorizontalDivider(color = AshOutline, thickness = 0.5.dp)
            }

            DropdownMenuItem(
              text = { Text(if (isSpeaking) "Stop Read Aloud" else "Read Aloud", color = TextOnDark) },
              leadingIcon = { Icon(Icons.Default.VolumeUp, contentDescription = null, tint = TextOnDark) },
              onClick = {
                showMenu = false
                if (isSpeaking) {
                  tts?.stop()
                  isSpeaking = false
                } else {
                  val textToRead = parsedDocument?.pages?.firstOrNull()?.blocks
                    ?.filterIsInstance<ParagraphBlock>()
                    ?.joinToString(" ") { it.runs.joinToString("") { r -> r.text } } ?: ""
                  if (textToRead.isNotBlank()) {
                    tts?.speak(textToRead, TextToSpeech.QUEUE_FLUSH, null, "DV_TTS")
                    isSpeaking = true
                  } else {
                    Toast.makeText(context, "No readable text on this page", Toast.LENGTH_SHORT).show()
                  }
                }
              }
            )

            DropdownMenuItem(
              text = { Text("Properties", color = TextOnDark) },
              leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = TextOnDark) },
              onClick = {
                showMenu = false
                showProperties = true
              }
            )

            DropdownMenuItem(
              text = { Text("Share File", color = TextOnDark) },
              leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = TextOnDark) },
              onClick = {
                showMenu = false
                try {
                  val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, fileSource.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                  }
                  context.startActivity(Intent.createChooser(shareIntent, "Share ${fileSource.displayName}"))
                } catch (e: Exception) {
                  Toast.makeText(context, "Cannot share file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
              }
            )

            DropdownMenuItem(
              text = { Text("View as Hex", color = TextOnDark) },
              leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, tint = TextOnDark) },
              onClick = {
                showMenu = false
                forceHexView = true
              }
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AshSurface)
      )
    },
    containerColor = NearBlack
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      // Search bar
      AnimatedVisibility(visible = isSearching) {
        Surface(color = AshSurfaceVariant, modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            TextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              placeholder = { Text("Search document...", fontSize = 13.sp, color = TextOnDarkTertiary) },
              colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedTextColor = TextOnDark,
                unfocusedTextColor = TextOnDark,
                focusedIndicatorColor = DragonRed
              ),
              modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { isSearching = false; searchQuery = "" }) {
              Icon(Icons.Default.Close, contentDescription = "Close Search", tint = TextOnDark)
            }
          }
        }
      }

      if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = DragonRed)
            Spacer(modifier = Modifier.height(12.dp))
            Text("Opening document...", color = TextOnDarkSecondary, fontSize = 13.sp)
          }
        }
      } else if (forceHexView) {
        HexViewer(fileSource = fileSource)
      } else if (forceTextView) {
        CodeTextViewer(fileSource = fileSource, isNightMode = isNightMode, searchQuery = searchQuery)
      } else {
        when (currentFormat) {
          FileFormat.PDF -> {
            PdfViewer(
              fileSource = fileSource,
              onPageChanged = { cur, tot -> currentPage = cur; totalPages = tot },
              isNightMode = isNightMode
            )
          }
          FileFormat.DOCX, FileFormat.RTF, FileFormat.ODT -> {
            val doc = parsedDocument
            if (doc != null) {
              DocumentViewer(
                document = doc,
                onPageChanged = { cur, tot -> currentPage = cur; totalPages = tot },
                isNightMode = isNightMode
              )
            }
          }
          FileFormat.XLSX, FileFormat.ODS, FileFormat.CSV -> {
            val wb = parsedWorkbook
            if (wb != null) {
              SpreadsheetViewer(workbook = wb, isNightMode = isNightMode)
            }
          }
          FileFormat.PPTX, FileFormat.ODP -> {
            val pres = parsedPresentation
            if (pres != null) {
              PresentationViewer(
                presentation = pres,
                onSlideChanged = { cur, tot -> currentPage = cur; totalPages = tot },
                isNightMode = isNightMode
              )
            }
          }
          FileFormat.ODG -> {
            val drw = parsedDrawing
            if (drw != null) {
              DrawingViewer(drawing = drw, isNightMode = isNightMode)
            }
          }
          FileFormat.IMAGE_RASTER -> {
            ImageViewer(fileSource = fileSource, isSvg = false, isNightMode = isNightMode)
          }
          FileFormat.IMAGE_SVG -> {
            ImageViewer(fileSource = fileSource, isSvg = true, isNightMode = isNightMode)
          }
          FileFormat.CODE -> {
            CodeTextViewer(fileSource = fileSource, isNightMode = isNightMode, searchQuery = searchQuery)
          }
          FileFormat.MARKDOWN -> {
            MarkdownViewer(fileSource = fileSource, isNightMode = isNightMode)
          }
          FileFormat.ARCHIVE -> {
            ArchiveViewer(fileSource = fileSource, onOpenInnerFile = onOpenInnerFile)
          }
          FileFormat.HEX -> {
            HexViewer(fileSource = fileSource)
          }
          else -> {
            CodeTextViewer(fileSource = fileSource, isNightMode = isNightMode, searchQuery = searchQuery)
          }
        }
      }
    }
  }

  // Properties Dialog
  if (showProperties) {
    val sizeStr = if (fileSource.sizeBytes > 1024 * 1024) {
      String.format(Locale.US, "%.1f MB", fileSource.sizeBytes / (1024.0 * 1024.0))
    } else {
      "${fileSource.sizeBytes / 1024} KB"
    }

    PropertiesDialog(
      properties = DocumentProperties(
        fileName = fileSource.displayName,
        detectedFormat = currentFormat,
        sizeBytes = fileSource.sizeBytes,
        formattedSize = sizeStr,
        uri = fileSource.uri,
        mimeType = context.contentResolver.getType(fileSource.uri),
        lastModified = System.currentTimeMillis(),
        pageCount = if (totalPages > 1) totalPages else null,
        detectionEvidence = detectionResult?.evidence ?: emptyList()
      ),
      onDismiss = { showProperties = false }
    )
  }
}
