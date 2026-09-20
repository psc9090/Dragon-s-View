package com.example.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.theme.AshSurface
import com.example.ui.theme.DarkCrimson
import com.example.ui.theme.DragonRed
import com.example.ui.theme.NearBlack

@Composable
fun DrawingViewer(
  drawing: DrawingModel,
  modifier: Modifier = Modifier,
  isNightMode: Boolean = false
) {
  var scale by remember { mutableStateOf(1f) }
  var offsetX by remember { mutableStateOf(0f) }
  var offsetY by remember { mutableStateOf(0f) }

  val page = drawing.pages.firstOrNull() ?: return

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(if (isNightMode) NearBlack else AshSurface)
      .pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
          scale = (scale * zoom).coerceIn(0.8f, 4f)
          offsetX += pan.x
          offsetY += pan.y
        }
      },
    contentAlignment = Alignment.Center
  ) {
    Card(
      shape = MaterialTheme.shapes.small,
      colors = CardDefaults.cardColors(containerColor = if (isNightMode) Color(0xFF1B1718) else Color.White),
      elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
      modifier = Modifier
        .size(width = 360.dp, height = 480.dp)
        .graphicsLayer(
          scaleX = scale,
          scaleY = scale,
          translationX = offsetX,
          translationY = offsetY
        )
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          for (el in page.elements) {
            when (el) {
              is DrawingRect -> {
                if (el.fillColor != null) {
                  drawRect(
                    color = Color(el.fillColor),
                    topLeft = Offset(el.x, el.y),
                    size = Size(el.width, el.height)
                  )
                }
                drawRect(
                  color = Color(el.strokeColor),
                  topLeft = Offset(el.x, el.y),
                  size = Size(el.width, el.height),
                  style = Stroke(width = el.strokeWidth)
                )
              }
              is DrawingEllipse -> {
                if (el.fillColor != null) {
                  drawCircle(
                    color = Color(el.fillColor),
                    radius = el.rx,
                    center = Offset(el.cx, el.cy)
                  )
                }
                drawCircle(
                  color = Color(el.strokeColor),
                  radius = el.rx,
                  center = Offset(el.cx, el.cy),
                  style = Stroke(width = el.strokeWidth)
                )
              }
              else -> {}
            }
          }
        }

        // Overlay text elements
        for (el in page.elements) {
          if (el is DrawingText) {
            Text(
              text = el.text,
              fontSize = el.fontSize.sp,
              color = if (isNightMode) Color.White else Color(el.color),
              modifier = Modifier.offset(x = el.x.dp, y = el.y.dp)
            )
          }
        }
      }
    }
  }
}
