package com.example.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FileSource
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CodeTextViewer(
  fileSource: FileSource,
  modifier: Modifier = Modifier,
  isNightMode: Boolean = false,
  searchQuery: String = ""
) {
  var lines by remember { mutableStateOf<List<String>>(emptyList()) }
  var isLargeFile by remember { mutableStateOf(false) }
  var showLineNumbers by remember { mutableStateOf(true) }
  var wordWrap by remember { mutableStateOf(false) }
  var fontSizeSp by remember { mutableStateOf(13f) }

  val listState = rememberLazyListState()
  val horizontalScroll = rememberScrollState()

  LaunchedEffect(fileSource) {
    withContext(Dispatchers.IO) {
      val readLines = mutableListOf<String>()
      fileSource.openStream().use { stream ->
        stream.bufferedReader(Charsets.UTF_8).forEachLine {
          if (readLines.size < 50000) {
            readLines.add(it)
          }
        }
      }
      isLargeFile = readLines.size > 4000 || fileSource.sizeBytes > 400 * 1024
      lines = readLines
    }
  }

  val bg = if (isNightMode) Color(0xFF0A0708) else NearBlack
  val gutterBg = if (isNightMode) DarkCrimson else Color(0xFF140C0E)
  val defaultCodeColor = TextOnDark

  Column(modifier = modifier.fillMaxSize().background(bg)) {
    // Toolbar controls for Code
    Surface(
      color = AshSurfaceVariant,
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        modifier = Modifier
          .padding(horizontal = 12.dp, vertical = 4.dp)
          .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = { showLineNumbers = !showLineNumbers }) {
            Icon(
              Icons.Default.FormatListNumbered,
              contentDescription = "Toggle Line Numbers",
              tint = if (showLineNumbers) DragonRed else TextOnDarkSecondary
            )
          }
          IconButton(onClick = { wordWrap = !wordWrap }) {
            Icon(
              Icons.Default.WrapText,
              contentDescription = "Toggle Word Wrap",
              tint = if (wordWrap) DragonRed else TextOnDarkSecondary
            )
          }
          Text(
            text = "${lines.size} lines",
            color = TextOnDarkTertiary,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp)
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          TextButton(onClick = { fontSizeSp = (fontSizeSp - 1).coerceAtLeast(10f) }) {
            Text("A-", color = TextOnDark, fontSize = 12.sp)
          }
          TextButton(onClick = { fontSizeSp = (fontSizeSp + 1).coerceAtMost(22f) }) {
            Text("A+", color = TextOnDark, fontSize = 14.sp)
          }
        }
      }
    }

    if (isLargeFile) {
      Surface(color = DarkCrimson, modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Syntax highlighting disabled for this large file to maintain performance.",
          color = Color.White,
          fontSize = 11.sp,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
      }
    }

    // Code lines
    SelectionContainer {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .then(if (!wordWrap) Modifier.horizontalScroll(horizontalScroll) else Modifier)
      ) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize()
        ) {
          itemsIndexed(lines) { index, lineText ->
            Row(modifier = Modifier.fillMaxWidth()) {
              if (showLineNumbers) {
                Text(
                  text = "${index + 1}",
                  fontFamily = FontFamily.Monospace,
                  fontSize = fontSizeSp.sp,
                  color = TextOnDarkTertiary,
                  modifier = Modifier
                    .width(48.dp)
                    .background(gutterBg)
                    .padding(end = 8.dp),
                  textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
                Spacer(modifier = Modifier.width(8.dp))
              }

              val highlighted = if (!isLargeFile) {
                highlightCodeLine(lineText, defaultCodeColor, searchQuery)
              } else {
                buildAnnotatedString {
                  append(lineText)
                }
              }

              Text(
                text = highlighted,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSizeSp.sp,
                color = defaultCodeColor,
                modifier = Modifier.padding(end = 16.dp),
                softWrap = wordWrap
              )
            }
          }
        }
      }
    }
  }
}

private val KEYWORDS = setOf(
  "val", "var", "fun", "class", "interface", "object", "return", "if", "else",
  "for", "while", "import", "package", "public", "private", "protected", "override",
  "const", "let", "function", "def", "async", "await", "SELECT", "FROM", "WHERE", "INSERT"
)

private fun highlightCodeLine(
  line: String,
  defaultColor: Color,
  searchQuery: String
): androidx.compose.ui.text.AnnotatedString {
  return buildAnnotatedString {
    var i = 0
    while (i < line.length) {
      if (searchQuery.isNotEmpty() && line.startsWith(searchQuery, i, ignoreCase = true)) {
        withStyle(SpanStyle(background = Color(0xFFE65100), color = Color.White, fontWeight = FontWeight.Bold)) {
          append(line.substring(i, i + searchQuery.length))
        }
        i += searchQuery.length
      } else if (line.startsWith("//", i) || line.startsWith("#", i)) {
        // Comment to end of line
        withStyle(SpanStyle(color = CodeComment)) {
          append(line.substring(i))
        }
        break
      } else if (line[i] == '"' || line[i] == '\'') {
        // String literal
        val quote = line[i]
        val endQuote = line.indexOf(quote, i + 1)
        val strEnd = if (endQuote != -1) endQuote + 1 else line.length
        withStyle(SpanStyle(color = CodeString)) {
          append(line.substring(i, strEnd))
        }
        i = strEnd
      } else if (line[i].isDigit()) {
        val numEnd = line.indexOfFirst { !it.isDigit() && it != '.' }.takeIf { it >= i } ?: line.length
        withStyle(SpanStyle(color = CodeNumber)) {
          append(line.substring(i, numEnd))
        }
        i = numEnd
      } else if (line[i].isLetter()) {
        val wordBuilder = StringBuilder()
        var w = i
        while (w < line.length && (line[w].isLetterOrDigit() || line[w] == '_')) {
          wordBuilder.append(line[w])
          w++
        }
        val word = wordBuilder.toString()
        if (word in KEYWORDS) {
          withStyle(SpanStyle(color = CodeKeyword, fontWeight = FontWeight.Bold)) {
            append(word)
          }
        } else if (word.first().isUpperCase()) {
          withStyle(SpanStyle(color = CodeType)) {
            append(word)
          }
        } else {
          append(word)
        }
        i = w
      } else {
        append(line[i])
        i++
      }
    }
  }
}
