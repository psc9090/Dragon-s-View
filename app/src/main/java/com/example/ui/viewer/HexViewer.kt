package com.example.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FileSource
import com.example.ui.theme.DragonRed
import com.example.ui.theme.NearBlack
import com.example.ui.theme.TextOnDark
import com.example.ui.theme.TextOnDarkTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HexViewer(
  fileSource: FileSource,
  modifier: Modifier = Modifier
) {
  var chunks by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
  var isLoading by remember { mutableStateOf(true) }
  val scrollState = rememberScrollState()

  LaunchedEffect(fileSource) {
    withContext(Dispatchers.IO) {
      val list = mutableListOf<ByteArray>()
      fileSource.openStream().use { stream ->
        val buffer = ByteArray(16)
        var read = stream.read(buffer)
        var total = 0
        while (read > 0 && total < 64 * 1024) { // Cap at 64KB for hex viewer display
          list.add(buffer.copyOf(read))
          total += read
          read = stream.read(buffer)
        }
      }
      chunks = list
      isLoading = false
    }
  }

  if (isLoading) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(color = DragonRed)
    }
    return
  }

  SelectionContainer {
    Box(
      modifier = modifier
        .fillMaxSize()
        .background(NearBlack)
        .horizontalScroll(scrollState)
    ) {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(8.dp)
      ) {
        itemsIndexed(chunks) { index, chunk ->
          val offsetStr = String.format("%08X", index * 16)
          val hexSb = StringBuilder()
          val asciiSb = StringBuilder()

          for (b in chunk) {
            hexSb.append(String.format("%02X ", b))
            val c = b.toInt().toChar()
            if (c in ' '..'~') asciiSb.append(c) else asciiSb.append('.')
          }

          Row(modifier = Modifier.padding(vertical = 1.dp)) {
            Text(
              text = "$offsetStr: ",
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              color = DragonRed
            )
            Text(
              text = hexSb.toString().padEnd(48),
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              color = TextOnDark
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = "|$asciiSb|",
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              color = TextOnDarkTertiary
            )
          }
        }
      }
    }
  }
}
