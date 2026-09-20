package com.example.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun PresentationViewer(
  presentation: PresentationModel,
  modifier: Modifier = Modifier,
  onSlideChanged: (current: Int, total: Int) -> Unit = { _, _ -> },
  isNightMode: Boolean = false
) {
  val totalSlides = presentation.slides.size.coerceAtLeast(1)
  val pagerState = rememberPagerState { totalSlides }
  val coroutineScope = rememberCoroutineScope()

  var showNotes by remember { mutableStateOf(false) }

  LaunchedEffect(pagerState.currentPage, totalSlides) {
    onSlideChanged(pagerState.currentPage + 1, totalSlides)
  }

  val currentSlide = presentation.slides.getOrNull(pagerState.currentPage)

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(if (isNightMode) NearBlack else AshSurface)
  ) {
    // Top slide controller
    Surface(
      color = AshSurfaceVariant,
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        modifier = Modifier
          .padding(horizontal = 16.dp, vertical = 8.dp)
          .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = {
              if (pagerState.currentPage > 0) {
                coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
              }
            },
            enabled = pagerState.currentPage > 0
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Slide", tint = TextOnDark)
          }

          Text(
            text = "Slide ${pagerState.currentPage + 1} / $totalSlides",
            fontWeight = FontWeight.Bold,
            color = TextOnDark,
            fontSize = 14.sp
          )

          IconButton(
            onClick = {
              if (pagerState.currentPage < totalSlides - 1) {
                coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
              }
            },
            enabled = pagerState.currentPage < totalSlides - 1
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Slide", tint = TextOnDark)
          }
        }

        if (currentSlide?.notes != null) {
          IconButton(onClick = { showNotes = !showNotes }) {
            Icon(
              Icons.Default.Notes,
              contentDescription = "Speaker Notes",
              tint = if (showNotes) DragonRed else TextOnDarkSecondary
            )
          }
        }
      }
    }

    // Main slide canvas
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .padding(16.dp),
      contentAlignment = Alignment.Center
    ) {
      HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
      ) { page ->
        val slide = presentation.slides.getOrNull(page)
        if (slide != null) {
          SlideCanvas(slide = slide, isNightMode = isNightMode)
        }
      }
    }

    // Speaker notes drawer
    if (showNotes && currentSlide?.notes != null) {
      Surface(
        color = AshSurfaceVariant,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 160.dp)
          .border(0.5.dp, AshOutline)
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Text("Speaker Notes", fontWeight = FontWeight.Bold, color = DragonRed, fontSize = 12.sp)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = currentSlide.notes ?: "",
            color = TextOnDark,
            fontSize = 13.sp,
            style = MaterialTheme.typography.bodyMedium
          )
        }
      }
    }
  }
}

@Composable
fun SlideCanvas(slide: SlideModel, isNightMode: Boolean) {
  Card(
    shape = MaterialTheme.shapes.small,
    colors = CardDefaults.cardColors(
      containerColor = if (isNightMode) Color(0xFF1E1A1B) else Color(slide.backgroundColor)
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(16f / 9f)
      .border(1.dp, AshOutline, MaterialTheme.shapes.small)
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // Slide title
        if (slide.title.isNotBlank()) {
          Text(
            text = slide.title,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = if (isNightMode) TextOnDark else Color(0xFF111111),
            modifier = Modifier.padding(bottom = 12.dp)
          )
        }

        // Slide elements
        for (element in slide.elements) {
          when (element) {
            is SlideTextBox -> {
              for (p in element.paragraphs) {
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                  if (p.isBullet) {
                    Text("• ", color = DragonRed, fontWeight = FontWeight.Bold)
                  }
                  for (run in p.runs) {
                    Text(
                      text = run.text,
                      fontWeight = if (run.isBold) FontWeight.Bold else FontWeight.Normal,
                      fontSize = run.fontSizePt.sp,
                      color = if (isNightMode) TextOnDark else Color(0xFF222222)
                    )
                  }
                }
              }
            }
            is SlideShape -> {
              Box(
                modifier = Modifier
                  .padding(vertical = 6.dp)
                  .size(width = 120.dp, height = 50.dp)
                  .background(Color(element.fillColor), shape = RoundedCornerShape(2.dp))
                  .border(1.dp, Color(element.strokeColor ?: 0xFF6E0B14.toInt())),
                contentAlignment = Alignment.Center
              ) {
                if (element.text != null) {
                  Text(element.text, color = Color.White, fontSize = 12.sp)
                }
              }
            }
            is SlideImage -> {
              val bmp = element.bitmap
              if (bmp != null) {
                Image(
                  bitmap = bmp.asImageBitmap(),
                  contentDescription = "Slide Image",
                  modifier = Modifier
                    .size(width = 180.dp, height = 120.dp)
                    .padding(vertical = 4.dp)
                )
              }
            }
            is SlideTable -> {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .border(0.5.dp, Color.Gray)
              ) {
                for (row in element.rows) {
                  Row(modifier = Modifier.fillMaxWidth()) {
                    for (cell in row) {
                      Box(
                        modifier = Modifier
                          .weight(1f)
                          .border(0.5.dp, Color.LightGray)
                          .padding(4.dp)
                      ) {
                        Text(cell, fontSize = 11.sp, color = if (isNightMode) TextOnDark else Color.Black)
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
  }
}
