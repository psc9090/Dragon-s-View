package com.example.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FileSource
import com.example.model.OpenFailure
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnsupportedScreen(
  fileSource: FileSource,
  failure: OpenFailure,
  onBack: () -> Unit,
  onViewAsText: () -> Unit,
  onViewAsHex: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(fileSource.displayName, color = TextOnDark, fontSize = 15.sp) },
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
    Column(
      modifier = modifier
        .fillMaxSize()
        .padding(padding)
        .padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Icon(
        Icons.Default.Info,
        contentDescription = null,
        tint = DragonRed,
        modifier = Modifier.size(56.dp)
      )

      Spacer(modifier = Modifier.height(16.dp))

      Text(
        text = "Cannot Display File",
        color = TextOnDark,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold
      )

      Spacer(modifier = Modifier.height(8.dp))

      Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = AshSurface),
        modifier = Modifier.fillMaxWidth().border(0.5.dp, AshOutline, MaterialTheme.shapes.small)
      ) {
        Text(
          text = failure.getMessage(),
          color = TextOnDarkSecondary,
          fontSize = 14.sp,
          modifier = Modifier.padding(16.dp),
          lineHeight = 20.sp
        )
      }

      Spacer(modifier = Modifier.height(24.dp))

      // Action buttons
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Button(
          onClick = {
            try {
              val sendIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileSource.uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
              context.startActivity(Intent.createChooser(sendIntent, "Open with another app"))
            } catch (e: Exception) {
              Toast.makeText(context, "No other app available to open this file", Toast.LENGTH_SHORT).show()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = DragonRed),
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
          Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Open with another app", fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
          onClick = onViewAsText,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
          Icon(Icons.Default.Code, contentDescription = null, tint = TextOnDark, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("View as Plain Text", color = TextOnDark)
        }

        OutlinedButton(
          onClick = onViewAsHex,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
          Text("View as Hex Dump", color = TextOnDark)
        }

        OutlinedButton(
          onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val diag = "File: ${fileSource.displayName}\nSize: ${fileSource.sizeBytes} bytes\nIssue: ${failure.getMessage()}"
            cm.setPrimaryClip(ClipData.newPlainText("Diagnostics", diag))
            Toast.makeText(context, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
          },
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
          Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TextOnDarkSecondary, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Copy Diagnostics", color = TextOnDarkSecondary)
        }
      }
    }
  }
}
