package com.example.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.example.ui.theme.*
import com.example.util.RecentFilesStore
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  onOpenAbout: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val recentsStore = remember { RecentFilesStore(context) }

  var screenAwake by remember { mutableStateOf(true) }
  var confirmLinks by remember { mutableStateOf(true) }
  var flagSecure by remember { mutableStateOf(false) }
  var cacheSizeStr by remember { mutableStateOf("Calculating...") }

  LaunchedEffect(Unit) {
    val cacheDir = context.cacheDir
    val bytes = calculateDirSize(cacheDir)
    cacheSizeStr = "${bytes / 1024} KB"
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text("Settings", color = TextOnDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextOnDark)
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AshSurface)
      )
    },
    containerColor = NearBlack
  ) { padding ->
    LazyColumn(
      modifier = modifier
        .fillMaxSize()
        .padding(padding)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Reading Comfort
      item {
        SectionTitle("READING COMFORT")
      }
      item {
        SettingSwitch(
          title = "Keep Screen Awake",
          subtitle = "Prevent screen from dimming while reading documents",
          checked = screenAwake,
          onCheckedChange = { screenAwake = it }
        )
      }

      // Security & Privacy
      item {
        SectionTitle("SECURITY & PRIVACY")
      }
      item {
        SettingSwitch(
          title = "Confirm External Links",
          subtitle = "Prompt before opening hyperlinks in external browser",
          checked = confirmLinks,
          onCheckedChange = { confirmLinks = it }
        )
      }
      item {
        SettingSwitch(
          title = "Screen Capture Protection",
          subtitle = "Block screenshots and app previews (FLAG_SECURE)",
          checked = flagSecure,
          onCheckedChange = {
            flagSecure = it
            Toast.makeText(context, "Setting updated", Toast.LENGTH_SHORT).show()
          }
        )
      }

      // Cache Management
      item {
        SectionTitle("STORAGE & CACHE")
      }
      item {
        Card(
          shape = MaterialTheme.shapes.small,
          colors = CardDefaults.cardColors(containerColor = AshSurface),
          modifier = Modifier.fillMaxWidth().border(0.5.dp, AshOutline, MaterialTheme.shapes.small)
        ) {
          Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("Temporary Cache", color = TextOnDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
              Text("Current: $cacheSizeStr", color = TextOnDarkSecondary, fontSize = 11.sp)
            }
            Button(
              onClick = {
                try {
                  context.cacheDir.deleteRecursively()
                  cacheSizeStr = "0 KB"
                  Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
              },
              colors = ButtonDefaults.buttonColors(containerColor = DarkCrimson),
              shape = MaterialTheme.shapes.extraSmall
            ) {
              Text("Clear", fontSize = 12.sp)
            }
          }
        }
      }

      // About
      item {
        SectionTitle("ABOUT")
      }
      item {
        Card(
          shape = MaterialTheme.shapes.small,
          colors = CardDefaults.cardColors(containerColor = AshSurface),
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenAbout() }
            .border(0.5.dp, AshOutline, MaterialTheme.shapes.small)
        ) {
          Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("About Dragon's View", color = TextOnDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
              Text("Format registry, licenses & creator info", color = TextOnDarkSecondary, fontSize = 11.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = DragonRed)
          }
        }
      }
    }
  }
}

@Composable
private fun SectionTitle(title: String) {
  Text(
    text = title,
    color = DragonRed,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    letterSpacing = 1.sp,
    modifier = Modifier.padding(vertical = 4.dp)
  )
}

@Composable
private fun SettingSwitch(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Card(
    shape = MaterialTheme.shapes.small,
    colors = CardDefaults.cardColors(containerColor = AshSurface),
    modifier = Modifier.fillMaxWidth().border(0.5.dp, AshOutline, MaterialTheme.shapes.small)
  ) {
    Row(
      modifier = Modifier.padding(14.dp).fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(title, color = TextOnDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = TextOnDarkSecondary, fontSize = 11.sp)
      }
      Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
          checkedThumbColor = Color.White,
          checkedTrackColor = DragonRed
        )
      )
    }
  }
}

private fun calculateDirSize(dir: File): Long {
  var size = 0L
  dir.listFiles()?.forEach { file ->
    size += if (file.isDirectory) calculateDirSize(file) else file.length()
  }
  return size
}
