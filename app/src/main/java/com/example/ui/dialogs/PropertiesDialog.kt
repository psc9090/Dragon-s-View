package com.example.ui.dialogs

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DocumentProperties
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PropertiesDialog(
  properties: DocumentProperties,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = "File Properties",
        fontWeight = FontWeight.Bold,
        color = DragonRed,
        fontSize = 18.sp
      )
    },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        item { PropRow("Name", properties.fileName) }
        item { PropRow("Format", properties.detectedFormat.displayName) }
        item { PropRow("Category", properties.detectedFormat.category) }
        item { PropRow("Size", properties.formattedSize) }
        if (properties.pageCount != null) item { PropRow("Pages", "${properties.pageCount}") }
        if (properties.slideCount != null) item { PropRow("Slides", "${properties.slideCount}") }
        if (properties.sheetCount != null) item { PropRow("Sheets", "${properties.sheetCount}") }

        if (properties.detectionEvidence.isNotEmpty()) {
          item {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Detection Evidence:", fontWeight = FontWeight.Bold, color = TextOnDark, fontSize = 12.sp)
            for (ev in properties.detectionEvidence) {
              Text("• $ev", color = TextOnDarkSecondary, fontSize = 11.sp)
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Close", color = DragonRed, fontWeight = FontWeight.Bold)
      }
    },
    containerColor = AshSurface,
    shape = MaterialTheme.shapes.small
  )
}

@Composable
private fun PropRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Text(label, color = TextOnDarkSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Text(value, color = TextOnDark, fontSize = 12.sp)
  }
}
