package com.example.ui.browser

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BrowserFileItem(
  val displayName: String,
  val isDirectory: Boolean,
  val sizeBytes: Long,
  val uri: Uri
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(
  onFileSelected: (Uri) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  var currentFolderUri by remember { mutableStateOf<Uri?>(null) }
  var fileItems by remember { mutableStateOf<List<BrowserFileItem>>(emptyList()) }
  var isLoading by remember { mutableStateOf(false) }

  val treePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree()
  ) { uri ->
    if (uri != null) {
      try {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
      } catch (_: Exception) {}
      currentFolderUri = uri
    }
  }

  LaunchedEffect(currentFolderUri) {
    val folderUri = currentFolderUri ?: return@LaunchedEffect
    isLoading = true
    withContext(Dispatchers.IO) {
      val items = mutableListOf<BrowserFileItem>()
      try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
          folderUri,
          DocumentsContract.getTreeDocumentId(folderUri)
        )
        context.contentResolver.query(
          childrenUri,
          arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
          ),
          null, null, null
        )?.use { cursor ->
          val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
          val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
          val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
          val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

          while (cursor.moveToNext()) {
            val docId = cursor.getString(idIdx)
            val name = cursor.getString(nameIdx) ?: "document"
            val mime = cursor.getString(mimeIdx) ?: ""
            val size = if (sizeIdx != -1) cursor.getLong(sizeIdx) else 0L
            val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

            val docUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
            items.add(
              BrowserFileItem(
                displayName = name,
                isDirectory = isDir,
                sizeBytes = size,
                uri = docUri
              )
            )
          }
        }
      } catch (_: Exception) {}
      fileItems = items.sortedWith(compareBy({ !it.isDirectory }, { it.displayName }))
      isLoading = false
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = "Folder Browser",
            color = TextOnDark,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextOnDark)
          }
        },
        actions = {
          IconButton(onClick = { treePickerLauncher.launch(null) }) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = "Grant Folder Access", tint = DragonRed)
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AshSurface)
      )
    },
    containerColor = NearBlack
  ) { padding ->
    if (currentFolderUri == null) {
      Column(
        modifier = modifier
          .fillMaxSize()
          .padding(padding)
          .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = DragonRed, modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = "No Folder Granted",
          color = TextOnDark,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Android requires explicit user authorization via Storage Access Framework to browse a folder.",
          color = TextOnDarkSecondary,
          fontSize = 13.sp,
          textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
          onClick = { treePickerLauncher.launch(null) },
          colors = ButtonDefaults.buttonColors(containerColor = DragonRed),
          shape = MaterialTheme.shapes.small
        ) {
          Text("Select Folder to Browse")
        }
      }
    } else if (isLoading) {
      Box(modifier = modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = DragonRed)
      }
    } else {
      LazyColumn(
        modifier = modifier
          .fillMaxSize()
          .padding(padding)
          .padding(horizontal = 12.dp, vertical = 8.dp)
      ) {
        items(fileItems) { item ->
          Card(
            shape = MaterialTheme.shapes.small,
            colors = CardDefaults.cardColors(containerColor = AshSurface),
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 3.dp)
              .clickable(enabled = !item.isDirectory) { onFileSelected(item.uri) }
              .border(0.5.dp, AshOutline, MaterialTheme.shapes.small)
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                tint = if (item.isDirectory) DragonRed else TextOnDarkSecondary,
                modifier = Modifier.size(22.dp)
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = item.displayName,
                  color = TextOnDark,
                  fontSize = 13.sp,
                  maxLines = 1
                )
                if (!item.isDirectory) {
                  Text(
                    text = "${item.sizeBytes / 1024} KB",
                    color = TextOnDarkTertiary,
                    fontSize = 11.sp
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
