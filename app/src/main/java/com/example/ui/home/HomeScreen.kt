package com.example.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.FileFormat
import com.example.model.RecentFile
import com.example.ui.theme.*
import com.example.util.RecentFilesStore
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(
  onFileSelected: (Uri) -> Unit,
  onOpenFolderBrowser: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val recentsStore = remember { RecentFilesStore(context) }
  var recents by remember { mutableStateOf<List<RecentFile>>(emptyList()) }

  LaunchedEffect(Unit) {
    recents = recentsStore.getRecents(5)
  }

  // System file picker launcher
  val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri != null) {
      try {
        context.contentResolver.takePersistableUriPermission(
          uri,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
      } catch (_: Exception) {}
      onFileSelected(uri)
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(NearBlack)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Top Settings Bar
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.End
      ) {
        IconButton(onClick = onOpenSettings) {
          Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextOnDarkSecondary)
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Logo & Branding Lockup
      Box(
        modifier = Modifier
          .size(76.dp)
          .border(1.5.dp, DragonRed, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center
      ) {
        Image(
          painter = painterResource(id = R.drawable.ic_dragon_logo),
          contentDescription = "Dragon's View Crest",
          modifier = Modifier.size(68.dp)
        )
      }

      Spacer(modifier = Modifier.height(14.dp))

      Text(
        text = "DRAGON'S VIEW",
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
        letterSpacing = 2.sp,
        color = TextOnDark
      )

      Text(
        text = "Universal File Viewer",
        fontSize = 13.sp,
        color = DragonRed,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp
      )

      Text(
        text = "Created by The RedDragon Society",
        fontSize = 11.sp,
        color = TextOnDarkTertiary,
        modifier = Modifier.padding(top = 2.dp)
      )

      Spacer(modifier = Modifier.height(28.dp))

      // Primary Action: OPEN FILE
      Button(
        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
        colors = ButtonDefaults.buttonColors(containerColor = DragonRed),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
      ) {
        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
          text = "OPEN FILE",
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.5.sp,
          fontSize = 14.sp
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Secondary Action: Browse Folders
      TextButton(
        onClick = onOpenFolderBrowser,
        shape = MaterialTheme.shapes.small
      ) {
        Text(
          text = "Browse Folders",
          color = TextOnDarkSecondary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium
        )
      }

      Spacer(modifier = Modifier.height(20.dp))

      // Recents Section Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Recent",
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp,
          color = TextOnDark
        )
        if (recents.isNotEmpty()) {
          TextButton(onClick = {
            recentsStore.clear()
            recents = emptyList()
          }) {
            Text("Clear", color = TextOnDarkTertiary, fontSize = 11.sp)
          }
        }
      }

      HorizontalDivider(color = AshOutline, thickness = 0.5.dp, modifier = Modifier.padding(bottom = 8.dp))

      // Recents List
      if (recents.isEmpty()) {
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = "No recent documents",
            color = TextOnDarkTertiary,
            fontSize = 12.sp
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
        ) {
          items(recents) { item ->
            RecentFileItem(
              item = item,
              onClick = { onFileSelected(Uri.parse(item.uriString)) },
              onRemove = {
                recentsStore.remove(item.uriString)
                recents = recentsStore.getRecents(5)
              }
            )
          }
        }
      }

      // Offline Trust Badge
      Row(
        modifier = Modifier
          .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(Icons.Default.Security, contentDescription = null, tint = DragonRed, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = "0 Network Permissions · 100% Offline",
          color = TextOnDarkTertiary,
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold
        )
      }
    }
  }
}

@Composable
fun RecentFileItem(
  item: RecentFile,
  onClick: () -> Unit,
  onRemove: () -> Unit
) {
  var showMenu by remember { mutableStateOf(false) }

  val relativeTime = formatRelativeTime(item.lastOpenedTimestamp)

  Card(
    shape = MaterialTheme.shapes.small,
    colors = CardDefaults.cardColors(containerColor = AshSurface),
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
      .clickable { onClick() }
      .border(0.5.dp, AshOutline, MaterialTheme.shapes.small)
  ) {
    Row(
      modifier = Modifier
        .padding(12.dp)
        .fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Format Icon Badge
      Box(
        modifier = Modifier
          .size(36.dp)
          .background(DarkCrimson, MaterialTheme.shapes.extraSmall),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = item.format.name.take(3),
          color = TextOnDark,
          fontWeight = FontWeight.Black,
          fontSize = 10.sp
        )
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.displayName,
          color = TextOnDark,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1
        )
        Text(
          text = "${item.format.displayName} · $relativeTime",
          color = TextOnDarkSecondary,
          fontSize = 11.sp
        )
      }

      Box {
        IconButton(onClick = { showMenu = true }) {
          Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = TextOnDarkTertiary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
          expanded = showMenu,
          onDismissRequest = { showMenu = false },
          modifier = Modifier.background(AshSurface)
        ) {
          DropdownMenuItem(
            text = { Text("Open", color = TextOnDark) },
            onClick = { showMenu = false; onClick() }
          )
          DropdownMenuItem(
            text = { Text("Remove from Recents", color = DragonRed) },
            onClick = { showMenu = false; onRemove() }
          )
        }
      }
    }
  }
}

private fun formatRelativeTime(timestamp: Long): String {
  val diff = System.currentTimeMillis() - timestamp
  val mins = diff / (60 * 1000)
  val hours = mins / 60
  val days = hours / 24

  return when {
    mins < 1 -> "Just now"
    mins < 60 -> "$mins min ago"
    hours < 24 -> "$hours hr ago"
    days == 1L -> "Yesterday"
    else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
  }
}
