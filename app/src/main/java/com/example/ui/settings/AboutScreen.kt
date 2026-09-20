package com.example.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.FileFormat
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier
) {
  val supportedFormats = listOf(
    Pair("Microsoft Office", "DOCX, DOTX, DOCM, XLSX, XLSM, PPTX, PPSX, POTX"),
    Pair("OpenDocument (ODF)", "ODT, OTT, FODT, ODS, OTS, FODS, ODP, OTP, FODP, ODG, OTG, FODG"),
    Pair("Portable Document Format", "PDF (via Android Native PdfRenderer)"),
    Pair("Rich Text & Plain Text", "RTF, TXT, LOG, INI, CONF, PROPERTIES"),
    Pair("Markdown", "MD, MARKDOWN (Rendered view & YAML metadata card)"),
    Pair("Spreadsheets & Data", "CSV, TSV, DSV"),
    Pair("Source Code & Web", "JSON, XML, HTML, CSS, JS, TS, KT, JAVA, PY, C, CPP, RS, GO, SH, SQL, YAML"),
    Pair("Images & Vector", "JPG, JPEG, PNG, GIF, WEBP, BMP, HEIC, SVG"),
    Pair("Archives & Packages", "ZIP, TAR, GZ, TGZ (Safe extracted in-app inspection)"),
    Pair("Dragon X-Ray", "Deep package inspection, parts tree, XML inspector, macro/security risk scanning")
  )

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("About", color = TextOnDark, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
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
      verticalArrangement = Arrangement.spacedBy(14.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      item {
        Box(
          modifier = Modifier
            .size(72.dp)
            .border(1.5.dp, DragonRed, MaterialTheme.shapes.small),
          contentAlignment = Alignment.Center
        ) {
          Image(
            painter = painterResource(id = R.drawable.ic_dragon_logo),
            contentDescription = "Dragon's View Logo",
            modifier = Modifier.size(64.dp)
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
          text = "DRAGON'S VIEW",
          fontWeight = FontWeight.Black,
          fontSize = 18.sp,
          letterSpacing = 1.5.sp,
          color = TextOnDark
        )

        Text(
          text = "v1.0.0 · Universal Offline File Viewer",
          fontSize = 12.sp,
          color = DragonRed
        )

        Text(
          text = "Created by The RedDragon Society",
          fontSize = 11.sp,
          color = TextOnDarkTertiary,
          modifier = Modifier.padding(top = 2.dp)
        )
      }

      // Offline pledge card
      item {
        Card(
          shape = MaterialTheme.shapes.small,
          colors = CardDefaults.cardColors(containerColor = AshSurface),
          modifier = Modifier.fillMaxWidth().border(1.dp, DragonRed, MaterialTheme.shapes.small)
        ) {
          Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, contentDescription = null, tint = DragonRed, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
              Text("OFFLINE-FIRST ARCHITECTURE", fontWeight = FontWeight.Bold, color = TextOnDark, fontSize = 12.sp)
              Text(
                text = "Dragon's View contains 0 network permissions. All document parsing, rendering, and inspection occurs 100% locally on your device.",
                color = TextOnDarkSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp
              )
            }
          }
        }
      }

      // Format Registry
      item {
        Text(
          text = "SUPPORTED FORMAT REGISTRY",
          fontWeight = FontWeight.Bold,
          color = DragonRed,
          fontSize = 12.sp,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
      }

      items(supportedFormats) { (category, formats) ->
        Card(
          shape = MaterialTheme.shapes.extraSmall,
          colors = CardDefaults.cardColors(containerColor = AshSurfaceVariant),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Text(category, fontWeight = FontWeight.Bold, color = TextOnDark, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(formats, color = TextOnDarkSecondary, fontSize = 11.sp)
          }
        }
      }

      item {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = "Copyright © 2026 The RedDragon Society. All rights reserved.",
          fontSize = 10.sp,
          color = TextOnDarkTertiary
        )
      }
    }
  }
}
