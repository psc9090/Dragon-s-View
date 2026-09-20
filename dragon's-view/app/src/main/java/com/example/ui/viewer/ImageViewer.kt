package com.example.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.model.FileSource
import com.example.ui.theme.AshSurface
import com.example.ui.theme.DragonRed
import com.example.ui.theme.NearBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ImageViewer(
  fileSource: FileSource,
  isSvg: Boolean = false,
  modifier: Modifier = Modifier,
  isNightMode: Boolean = false
) {
  var bitmap by remember { mutableStateOf<Bitmap?>(null) }
  var svgContent by remember { mutableStateOf<String?>(null) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  var scale by remember { mutableStateOf(1f) }
  var offsetX by remember { mutableStateOf(0f) }
  var offsetY by remember { mutableStateOf(0f) }

  LaunchedEffect(fileSource) {
    withContext(Dispatchers.IO) {
      try {
        if (isSvg) {
          fileSource.openStream().use { stream ->
            svgContent = stream.bufferedReader(Charsets.UTF_8).readText()
          }
        } else {
          fileSource.openStream().use { stream ->
            // First check dimensions
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, boundsOpts)

            var sampleSize = 1
            val maxDim = boundsOpts.outWidth.coerceAtLeast(boundsOpts.outHeight)
            if (maxDim > 3072) {
              sampleSize = 2
            }
            if (maxDim > 6144) {
              sampleSize = 4
            }

            fileSource.openStream().use { realStream ->
              val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
              }
              bitmap = BitmapFactory.decodeStream(realStream, null, decodeOpts)
            }
          }
        }
      } catch (e: Exception) {
        errorMessage = "Unable to decode image: ${e.localizedMessage}"
      }
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(if (isNightMode) Color.Black else NearBlack)
      .pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
          scale = (scale * zoom).coerceIn(0.5f, 6f)
          offsetX += pan.x
          offsetY += pan.y
        }
      },
    contentAlignment = Alignment.Center
  ) {
    if (errorMessage != null) {
      Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
    } else if (isSvg && svgContent != null) {
      // Display SVG XML source if rendered
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        Text("SVG Vector Graphics", color = DragonRed, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = svgContent!!.take(500) + if (svgContent!!.length > 500) "\n..." else "",
          color = Color.LightGray,
          style = MaterialTheme.typography.bodySmall
        )
      }
    } else if (bitmap != null) {
      Image(
        bitmap = bitmap!!.asImageBitmap(),
        contentDescription = fileSource.displayName,
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            translationX = offsetX,
            translationY = offsetY
          )
      )
    } else {
      CircularProgressIndicator(color = DragonRed)
    }
  }
}
