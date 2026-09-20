package com.example.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FileSource
import com.example.parser.archive.ArchiveEntryItem
import com.example.parser.archive.ArchiveParser
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ArchiveViewer(
  fileSource: FileSource,
  onOpenInnerFile: (FileSource) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  var entries by remember { mutableStateOf<List<ArchiveEntryItem>>(emptyList()) }
  var isLoading by remember { mutableStateOf(true) }

  LaunchedEffect(fileSource) {
    withContext(Dispatchers.IO) {
      try {
        entries = ArchiveParser.listEntries(fileSource)
      } catch (_: Exception) {}
      isLoading = false
    }
  }

  if (isLoading) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(color = DragonRed)
    }
    return
  }

  Column(modifier = modifier.fillMaxSize().background(NearBlack)) {
    Surface(color = AshSurfaceVariant, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = "Archive Contents (${entries.size} items)",
        color = TextOnDarkSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(12.dp)
      )
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(8.dp)
    ) {
      items(entries) { entry ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !entry.isDirectory) {
              try {
                val extracted = ArchiveParser.extractEntry(context, fileSource, entry.entryPath)
                onOpenInnerFile(extracted)
              } catch (_: Exception) {}
            }
            .padding(vertical = 8.dp, horizontal = 12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = if (entry.isDirectory) DragonRed else TextOnDarkSecondary,
            modifier = Modifier.size(20.dp)
          )
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = entry.entryPath,
              color = TextOnDark,
              fontSize = 13.sp
            )
            if (!entry.isDirectory && entry.sizeBytes >= 0) {
              Text(
                text = "${entry.sizeBytes / 1024} KB (comp: ${entry.compressedSizeBytes / 1024} KB)",
                color = TextOnDarkTertiary,
                fontSize = 11.sp
              )
            }
          }
        }
        HorizontalDivider(color = AshOutline.copy(alpha = 0.5f), thickness = 0.5.dp)
      }
    }
  }
}
