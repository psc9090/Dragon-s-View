package com.example.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.theme.*

@Composable
fun SpreadsheetViewer(
  workbook: SheetWorkbook,
  modifier: Modifier = Modifier,
  isNightMode: Boolean = false
) {
  var activeSheetIndex by remember { mutableStateOf(0) }
  val activeSheet = workbook.sheets.getOrNull(activeSheetIndex) ?: workbook.sheets.firstOrNull()

  var selectedCellInfo by remember { mutableStateOf<Pair<String, String>?>(null) }

  if (activeSheet == null) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("No sheets found in workbook", color = TextOnDarkSecondary)
    }
    return
  }

  val horizontalScrollState = rememberScrollState()
  val verticalListState = rememberLazyListState()

  val bg = if (isNightMode) NearBlack else Color(0xFFF9F7F7)
  val cellBorderColor = if (isNightMode) Color(0xFF2C2224) else Color(0xFFE2D6D8)
  val headerBg = if (isNightMode) DarkCrimson else Color(0xFFECE1E3)
  val textColor = if (isNightMode) TextOnDark else Color(0xFF1A1113)

  Column(modifier = modifier.fillMaxSize().background(bg)) {
    // Formula / selected cell preview bar
    if (selectedCellInfo != null) {
      Surface(
        color = AshSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = selectedCellInfo!!.first,
            fontWeight = FontWeight.Bold,
            color = DragonRed,
            fontSize = 13.sp,
            modifier = Modifier.width(60.dp)
          )
          Text(
            text = selectedCellInfo!!.second,
            color = TextOnDark,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }

    // Grid body
    Box(modifier = Modifier.weight(1f)) {
      Row(modifier = Modifier.fillMaxSize()) {
        // Horizontally scrollable columns
        Box(modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScrollState)) {
          Column {
            // Column Headers (A, B, C...)
            Row(modifier = Modifier.background(headerBg)) {
              // Corner cell
              Box(
                modifier = Modifier
                  .width(48.dp)
                  .height(30.dp)
                  .border(0.5.dp, cellBorderColor),
                contentAlignment = Alignment.Center
              ) {
                Text("#", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textColor)
              }

              for (col in 1..activeSheet.maxCols.coerceAtMost(50)) {
                Box(
                  modifier = Modifier
                    .width(100.dp)
                    .height(30.dp)
                    .border(0.5.dp, cellBorderColor),
                  contentAlignment = Alignment.Center
                ) {
                  Text(
                    text = colIndexToLetter(col),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                  )
                }
              }
            }

            // Spreadsheet Rows
            LazyColumn(
              state = verticalListState,
              modifier = Modifier.fillMaxSize()
            ) {
              items(activeSheet.rows.size) { rIndex ->
                val row = activeSheet.rows[rIndex]
                Row(modifier = Modifier.height(32.dp)) {
                  // Row header (1, 2, 3...)
                  Box(
                    modifier = Modifier
                      .width(48.dp)
                      .fillMaxHeight()
                      .background(headerBg)
                      .border(0.5.dp, cellBorderColor),
                    contentAlignment = Alignment.Center
                  ) {
                    Text(
                      text = "${row.rowIndex}",
                      fontSize = 11.sp,
                      fontWeight = FontWeight.SemiBold,
                      color = textColor
                    )
                  }

                  // Data cells
                  for (col in 1..activeSheet.maxCols.coerceAtMost(50)) {
                    val cellVal = row.cells[col]
                    val displayText = cellVal?.displayText ?: ""
                    val isNumber = cellVal is CellValue.Number

                    Box(
                      modifier = Modifier
                        .width(100.dp)
                        .fillMaxHeight()
                        .border(0.5.dp, cellBorderColor)
                        .clickable {
                          if (displayText.isNotEmpty()) {
                            selectedCellInfo = Pair("${colIndexToLetter(col)}${row.rowIndex}", displayText)
                          }
                        }
                        .padding(horizontal = 6.dp),
                      contentAlignment = if (isNumber) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                      Text(
                        text = displayText,
                        fontSize = 12.sp,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (isNumber) TextAlign.End else TextAlign.Start
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

    // Bottom Sheet Tabs
    if (workbook.sheets.size > 1) {
      Surface(
        color = AshSurface,
        modifier = Modifier.fillMaxWidth()
      ) {
        ScrollableTabRow(
          selectedTabIndex = activeSheetIndex,
          edgePadding = 8.dp,
          containerColor = AshSurface,
          contentColor = DragonRed
        ) {
          workbook.sheets.forEachIndexed { index, sheet ->
            Tab(
              selected = activeSheetIndex == index,
              onClick = { activeSheetIndex = index },
              text = {
                Text(
                  text = sheet.name,
                  fontWeight = if (activeSheetIndex == index) FontWeight.Bold else FontWeight.Normal,
                  fontSize = 13.sp
                )
              }
            )
          }
        }
      }
    }
  }
}
