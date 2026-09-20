package com.example.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.model.FileSource
import com.example.ui.theme.AshSurface
import com.example.ui.theme.DarkCrimson
import com.example.ui.theme.DragonRed
import com.example.ui.theme.NearBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PdfViewer(
  fileSource: FileSource,
  modifier: Modifier = Modifier,
  onPageChanged: (current: Int, total: Int) -> Unit = { _, _ -> },
  isNightMode: Boolean = false
) {
  val context = LocalContext.current
  var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
  var totalPages by remember { mutableStateOf(0) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var scale by remember { mutableStateOf(1f) }
  var offsetX by remember { mutableStateOf(0f) }
  var offsetY by remember { mutableStateOf(0f) }

  val listState = rememberLazyListState()

  // Track currently visible page
  LaunchedEffect(listState.firstVisibleItemIndex, totalPages) {
    if (totalPages > 0) {
      onPageChanged(listState.firstVisibleItemIndex + 1, totalPages)
    }
  }

  // Initialize PdfRenderer off main thread
  DisposableEffect(fileSource) {
    var pfd: android.os.ParcelFileDescriptor? = null
    var rend: PdfRenderer? = null
    try {
      pfd = fileSource.getFileDescriptor()
      rend = PdfRenderer(pfd)
      renderer = rend
      totalPages = rend.pageCount
    } catch (e: Exception) {
      errorMessage = "Unable to render PDF: ${e.localizedMessage}"
    }

    onDispose {
      try {
        rend?.close()
      } catch (_: Exception) {}
      try {
        pfd?.close()
      } catch (_: Exception) {}
    }
  }

  if (errorMessage != null) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        text = errorMessage ?: "",
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(16.dp)
      )
    }
    return
  }

  val activeRenderer = renderer
  if (activeRenderer == null) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(color = DragonRed)
    }
    return
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(if (isNightMode) NearBlack else AshSurface)
      .pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
          scale = (scale * zoom).coerceIn(1f, 4f)
          if (scale > 1f) {
            offsetX += pan.x
            offsetY += pan.y
          } else {
            offsetX = 0f
            offsetY = 0f
          }
        }
      }
  ) {
    LazyColumn(
      state = listState,
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer(
          scaleX = scale,
          scaleY = scale,
          translationX = offsetX,
          translationY = offsetY
        ),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      items(totalPages) { pageIndex ->
        PdfPageItem(
          renderer = activeRenderer,
          pageIndex = pageIndex,
          isNightMode = isNightMode
        )
      }
    }
  }
}

@Composable
private fun PdfPageItem(
  renderer: PdfRenderer,
  pageIndex: Int,
  isNightMode: Boolean
) {
  var bitmap by remember { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(pageIndex) {
    withContext(Dispatchers.IO) {
      try {
        synchronized(renderer) {
          renderer.openPage(pageIndex).use { page ->
            val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap = bmp
          }
        }
      } catch (_: Exception) {}
    }
  }

  val currentBitmap = bitmap
  Card(
    shape = MaterialTheme.shapes.small,
    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    colors = CardDefaults.cardColors(containerColor = if (isNightMode) Color(0xFF1E1E1E) else Color.White),
    modifier = Modifier
      .fillMaxWidth()
      .wrapContentHeight()
  ) {
    if (currentBitmap != null) {
      Image(
        bitmap = currentBitmap.asImageBitmap(),
        contentDescription = "PDF Page ${pageIndex + 1}",
        modifier = Modifier
          .fillMaxWidth()
          .wrapContentHeight()
      )
    } else {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(400.dp)
          .background(Color.White),
        contentAlignment = Alignment.Center
      ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = DragonRed)
      }
    }
  }
}
