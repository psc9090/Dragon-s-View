package com.example.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Description
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
import com.example.parser.xray.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XRayInspectorScreen(
  fileSource: FileSource,
  onBack: () -> Unit,
  modifier: Modifier = Modifier
) {
  var inspection by remember { mutableStateOf<XRayInspection?>(null) }
  var selectedPartContent by remember { mutableStateOf<Pair<String, String>?>(null) }

  LaunchedEffect(fileSource) {
    withContext(Dispatchers.IO) {
      try {
        inspection = XRayInspector.inspect(fileSource)
      } catch (_: Exception) {}
    }
  }

  val active = inspection

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("DRAGON X-RAY", color = DragonRed, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(fileSource.displayName, color = TextOnDarkSecondary, fontSize = 11.sp)
          }
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
    if (active == null) {
      Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = DragonRed)
      }
      return@Scaffold
    }

    if (selectedPartContent != null) {
      // Show raw part viewer
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding)
          .background(NearBlack)
      ) {
        Surface(color = AshSurfaceVariant, modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(selectedPartContent!!.first, color = DragonRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            TextButton(onClick = { selectedPartContent = null }) {
              Text("Close", color = TextOnDark)
            }
          }
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
          item {
            Text(
              text = selectedPartContent!!.second,
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              color = TextOnDark
            )
          }
        }
      }
      return@Scaffold
    }

    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // Security Analysis Card
      item {
        Card(
          shape = MaterialTheme.shapes.small,
          colors = CardDefaults.cardColors(containerColor = AshSurface),
          modifier = Modifier.fillMaxWidth().border(1.dp, DragonRed, MaterialTheme.shapes.small)
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            Text("SECURITY ASSESSMENT", fontWeight = FontWeight.Bold, color = DragonRed, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))
            for (risk in active.securityRisks) {
              Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Icon(
                  imageVector = when (risk.level) {
                    RiskLevel.DANGER -> Icons.Default.Dangerous
                    RiskLevel.WARNING -> Icons.Default.Warning
                    RiskLevel.INFO -> Icons.Default.CheckCircle
                  },
                  contentDescription = null,
                  tint = when (risk.level) {
                    RiskLevel.DANGER -> DragonRed
                    RiskLevel.WARNING -> Color(0xFFFFA000)
                    RiskLevel.INFO -> Color(0xFF4CAF50)
                  },
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                  Text(risk.title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextOnDark)
                  Text(risk.description, fontSize = 11.sp, color = TextOnDarkSecondary)
                }
              }
            }
          }
        }
      }

      // Metadata Card
      if (active.metadata.isNotEmpty()) {
        item {
          Card(
            shape = MaterialTheme.shapes.small,
            colors = CardDefaults.cardColors(containerColor = AshSurface),
            modifier = Modifier.fillMaxWidth().border(0.5.dp, AshOutline, MaterialTheme.shapes.small)
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Text("PACKAGE METADATA", fontWeight = FontWeight.Bold, color = DragonRed, fontSize = 13.sp)
              Spacer(modifier = Modifier.height(6.dp))
              for ((k, v) in active.metadata) {
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                  Text("$k: ", color = TextOnDarkSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                  Text(v, color = TextOnDark, fontSize = 12.sp)
                }
              }
            }
          }
        }
      }

      // Package Parts Tree
      item {
        Text("PACKAGE PARTS (${active.totalEntries} entries)", fontWeight = FontWeight.Bold, color = TextOnDark, fontSize = 13.sp)
      }

      items(active.parts) { part ->
        Card(
          shape = MaterialTheme.shapes.extraSmall,
          colors = CardDefaults.cardColors(containerColor = AshSurfaceVariant),
          modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = part.isXml) {
              val content = XRayInspector.readPartContent(fileSource, part.path)
              selectedPartContent = Pair(part.path, content)
            }
        ) {
          Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Default.Description, contentDescription = null, tint = if (part.isXml) DragonRed else TextOnDarkTertiary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(part.path, fontSize = 12.sp, color = TextOnDark)
              Text(
                text = "${part.size / 1024} KB (compressed: ${part.compressedSize / 1024} KB)",
                fontSize = 10.sp,
                color = TextOnDarkTertiary
              )
            }
            if (part.isXml) {
              Text("View XML", color = DragonRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }
  }
}
