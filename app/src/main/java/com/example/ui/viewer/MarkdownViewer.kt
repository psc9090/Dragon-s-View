package com.example.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FileSource
import com.example.parser.markdown.*
import com.example.ui.theme.*

@Composable
fun MarkdownViewer(
  fileSource: FileSource,
  modifier: Modifier = Modifier,
  isNightMode: Boolean = false
) {
  var doc by remember { mutableStateOf<MarkdownDoc?>(null) }
  var viewSource by remember { mutableStateOf(false) }

  LaunchedEffect(fileSource) {
    doc = MarkdownParser.parse(fileSource)
  }

  if (viewSource) {
    CodeTextViewer(fileSource = fileSource, modifier = modifier, isNightMode = isNightMode)
    return
  }

  val activeDoc = doc
  if (activeDoc == null) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(color = DragonRed)
    }
    return
  }

  val bg = if (isNightMode) Color(0xFF0D080A) else AshSurface

  Column(modifier = modifier.fillMaxSize().background(bg)) {
    // Mode toggle bar
    Surface(
      color = AshSurfaceVariant,
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        modifier = Modifier
          .padding(horizontal = 16.dp, vertical = 6.dp)
          .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Markdown Reading Mode",
          color = TextOnDarkSecondary,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium
        )
        TextButton(onClick = { viewSource = true }) {
          Icon(Icons.Default.Code, contentDescription = "View Source", tint = DragonRed, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("View Source", color = DragonRed, fontSize = 12.sp)
        }
      }
    }

    SelectionContainer {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        // Front matter metadata card
        if (activeDoc.frontMatter != null && activeDoc.frontMatter.isNotEmpty()) {
          item {
            Card(
              shape = MaterialTheme.shapes.small,
              colors = CardDefaults.cardColors(containerColor = AshSurfaceVariant),
              modifier = Modifier.fillMaxWidth().border(0.5.dp, AshOutline, MaterialTheme.shapes.small)
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text("Metadata (YAML)", fontWeight = FontWeight.Bold, color = DragonRed, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                for ((k, v) in activeDoc.frontMatter) {
                  Row {
                    Text("$k: ", color = TextOnDarkSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(v, color = TextOnDark, fontSize = 12.sp)
                  }
                }
              }
            }
          }
        }

        items(activeDoc.blocks) { block ->
          RenderMarkdownBlock(block = block)
        }
      }
    }
  }
}

@Composable
fun RenderMarkdownBlock(block: MarkdownBlock) {
  when (block) {
    is MdHeading -> {
      val (fontSize, color) = when (block.level) {
        1 -> Pair(22.sp, DragonRed)
        2 -> Pair(19.sp, TextOnDark)
        3 -> Pair(17.sp, TextOnDark)
        4 -> Pair(15.sp, TextOnDarkSecondary)
        else -> Pair(14.sp, TextOnDarkSecondary)
      }
      Text(
        text = block.text,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
      )
    }
    is MdParagraph -> {
      Text(
        text = block.text,
        fontSize = 14.sp,
        color = TextOnDark,
        lineHeight = 20.sp
      )
    }
    is MdCodeBlock -> {
      val scrollState = rememberScrollState()
      Card(
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0A0C)),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.5.dp, AshOutline, MaterialTheme.shapes.extraSmall)
      ) {
        Column(modifier = Modifier.padding(10.dp)) {
          if (block.language.isNotEmpty()) {
            Text(
              text = block.language,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = DragonRed,
              modifier = Modifier.padding(bottom = 4.dp)
            )
          }
          Box(modifier = Modifier.horizontalScroll(scrollState)) {
            Text(
              text = block.code,
              fontFamily = FontFamily.Monospace,
              fontSize = 12.sp,
              color = TextOnDark
            )
          }
        }
      }
    }
    is MdBlockQuote -> {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(AshSurfaceVariant, RoundedCornerShape(2.dp))
          .padding(8.dp)
      ) {
        Box(
          modifier = Modifier
            .width(3.dp)
            .fillMaxHeight()
            .background(DragonRed)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = block.text, color = TextOnDarkSecondary, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
      }
    }
    is MdCallout -> {
      Card(
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = DarkCrimson.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().border(1.dp, DragonRed, MaterialTheme.shapes.extraSmall)
      ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
          Icon(Icons.Default.Info, contentDescription = block.type, tint = DragonRed, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Column {
            Text(text = block.title, fontWeight = FontWeight.Bold, color = DragonRed, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = block.content, color = TextOnDark, fontSize = 13.sp)
          }
        }
      }
    }
    is MdListItem -> {
      Row(modifier = Modifier.fillMaxWidth().padding(start = (block.level * 16).dp), verticalAlignment = Alignment.CenterVertically) {
        if (block.isTask) {
          Checkbox(
            checked = block.isChecked,
            onCheckedChange = null, // Read-only!
            colors = CheckboxDefaults.colors(checkedColor = DragonRed, uncheckedColor = TextOnDarkSecondary),
            modifier = Modifier.size(24.dp).padding(end = 4.dp)
          )
        } else if (block.isOrdered) {
          Text("1. ", color = DragonRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        } else {
          Text("• ", color = DragonRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Text(text = block.text, color = TextOnDark, fontSize = 14.sp)
      }
    }
    is MdTable -> {
      val scrollState = rememberScrollState()
      Box(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
        Column(modifier = Modifier.border(0.5.dp, AshOutline)) {
          // Headers
          Row(modifier = Modifier.background(DarkCrimson)) {
            for (h in block.headers) {
              Box(modifier = Modifier.width(120.dp).border(0.5.dp, AshOutline).padding(6.dp)) {
                Text(h, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextOnDark)
              }
            }
          }
          // Rows
          for (row in block.rows) {
            Row {
              for (cell in row) {
                Box(modifier = Modifier.width(120.dp).border(0.5.dp, AshOutline).padding(6.dp)) {
                  Text(cell, fontSize = 12.sp, color = TextOnDark)
                }
              }
            }
          }
        }
      }
    }
    is MdDivider -> {
      HorizontalDivider(color = AshOutline, thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
    }
  }
}
