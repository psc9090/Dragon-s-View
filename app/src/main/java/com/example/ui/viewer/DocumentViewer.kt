package com.example.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.theme.AshSurface
import com.example.ui.theme.DarkCrimson
import com.example.ui.theme.DocumentPaper
import com.example.ui.theme.DocumentText
import com.example.ui.theme.NearBlack

@Composable
fun DocumentViewer(
  document: DocumentModel,
  modifier: Modifier = Modifier,
  onPageChanged: (current: Int, total: Int) -> Unit = { _, _ -> },
  isReaderMode: Boolean = false,
  isNightMode: Boolean = false
) {
  val listState = rememberLazyListState()
  var scale by remember { mutableStateOf(1f) }
  var offsetX by remember { mutableStateOf(0f) }
  var offsetY by remember { mutableStateOf(0f) }

  val totalPages = document.pages.size.coerceAtLeast(1)

  LaunchedEffect(listState.firstVisibleItemIndex, totalPages) {
    onPageChanged(listState.firstVisibleItemIndex + 1, totalPages)
  }

  val pageBg = if (isNightMode) Color(0xFF181415) else DocumentPaper
  val textColor = if (isNightMode) Color(0xFFECE5E5) else DocumentText
  val outerBg = if (isNightMode) NearBlack else AshSurface

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(outerBg)
      .pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
          scale = (scale * zoom).coerceIn(1f, 3.5f)
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
    SelectionContainer {
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        items(document.pages.size) { pageIndex ->
          val page = document.pages[pageIndex]

          Card(
            shape = MaterialTheme.shapes.small,
            colors = CardDefaults.cardColors(containerColor = pageBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
              .fillMaxWidth()
              .wrapContentHeight()
              .border(0.5.dp, Color(0xFF332225), MaterialTheme.shapes.small)
          ) {
            Column(
              modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
            ) {
              if (page.headerText != null) {
                Text(
                  text = page.headerText,
                  style = MaterialTheme.typography.labelSmall,
                  color = Color.Gray,
                  modifier = Modifier.padding(bottom = 12.dp)
                )
              }

              for (block in page.blocks) {
                RenderDocumentBlock(block = block, defaultTextColor = textColor)
                Spacer(modifier = Modifier.height(6.dp))
              }

              Spacer(modifier = Modifier.height(16.dp))
              Text(
                text = "Page ${page.pageNumber}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun RenderDocumentBlock(block: DocumentBlock, defaultTextColor: Color) {
  when (block) {
    is ParagraphBlock -> {
      val annotated = buildAnnotatedString {
        if (block.isBullet) {
          append("• ")
        }
        for (run in block.runs) {
          val style = SpanStyle(
            fontWeight = if (run.isBold || block.isHeading) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (run.isItalic) FontStyle.Italic else FontStyle.Normal,
            fontSize = if (block.isHeading) {
              (20 - block.headingLevel * 2).coerceAtLeast(14).sp
            } else {
              run.fontSizePt.sp
            },
            color = run.textColor?.let { Color(it) } ?: defaultTextColor,
            textDecoration = when {
              run.isUnderline -> TextDecoration.Underline
              run.isStrike -> TextDecoration.LineThrough
              else -> TextDecoration.None
            }
          )
          withStyle(style) {
            append(run.text)
          }
        }
      }

      val textAlign = when (block.alignment) {
        com.example.model.Alignment.CENTER -> TextAlign.Center
        com.example.model.Alignment.RIGHT -> TextAlign.Right
        com.example.model.Alignment.JUSTIFY -> TextAlign.Justify
        else -> TextAlign.Left
      }

      Text(
        text = annotated,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth()
      )
    }
    is TableBlock -> {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, Color(0xFFD0C4C4), MaterialTheme.shapes.extraSmall)
      ) {
        for (row in block.rows) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(if (row.isHeader) Color(0xFFEEE3E5) else Color.Transparent)
          ) {
            for (cell in row.cells) {
              Box(
                modifier = Modifier
                  .weight(1f)
                  .border(0.5.dp, Color(0xFFE2D6D8))
                  .padding(6.dp)
              ) {
                Text(
                  text = cell.text,
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = if (row.isHeader) FontWeight.Bold else FontWeight.Normal,
                  color = defaultTextColor
                )
              }
            }
          }
        }
      }
    }
    is ImageBlock -> {
      val bmp = block.bitmap
      if (bmp != null) {
        androidx.compose.foundation.Image(
          bitmap = bmp.asImageBitmap(),
          contentDescription = block.caption ?: "Document Image",
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .padding(vertical = 8.dp)
        )
      }
    }
  }
}
