package com.example.parser.rtf

import com.example.model.*
import java.io.InputStream
import java.util.ArrayDeque

object RtfParser {

  private data class RtfState(
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var strike: Boolean = false,
    var fontSizePt: Float = 11f,
    var textColor: Int? = null,
    var destinationSkipped: Boolean = false
  )

  fun parse(fileSource: FileSource): DocumentModel {
    val paragraphs = mutableListOf<ParagraphBlock>()
    var currentRuns = mutableListOf<TextRun>()
    val currentText = StringBuilder()

    val stack = ArrayDeque<RtfState>()
    var currentState = RtfState()
    stack.push(currentState.copy())

    fileSource.openStream().use { stream ->
      val reader = stream.bufferedReader(Charsets.ISO_8859_1)
      var chInt = reader.read()

      fun flushText() {
        if (currentText.isNotEmpty()) {
          currentRuns.add(
            TextRun(
              text = currentText.toString(),
              isBold = currentState.bold,
              isItalic = currentState.italic,
              isUnderline = currentState.underline,
              isStrike = currentState.strike,
              fontSizePt = currentState.fontSizePt,
              textColor = currentState.textColor
            )
          )
          currentText.clear()
        }
      }

      fun flushParagraph() {
        flushText()
        if (currentRuns.isNotEmpty()) {
          paragraphs.add(ParagraphBlock(runs = currentRuns.toList()))
          currentRuns = mutableListOf()
        }
      }

      while (chInt != -1) {
        val ch = chInt.toChar()

        if (ch == '{') {
          flushText()
          stack.push(currentState.copy())
        } else if (ch == '}') {
          flushText()
          if (!stack.isEmpty()) {
            currentState = stack.pop()
          }
        } else if (ch == '\\') {
          // Read control word or symbol
          chInt = reader.read()
          if (chInt == -1) break
          val nextCh = chInt.toChar()

          if (nextCh == '\\' || nextCh == '{' || nextCh == '}') {
            if (!currentState.destinationSkipped) currentText.append(nextCh)
          } else if (nextCh == '\'') {
            // Hex character \'hh
            val h1 = reader.read()
            val h2 = reader.read()
            if (h1 != -1 && h2 != -1) {
              val hexStr = "${h1.toChar()}${h2.toChar()}"
              val byteVal = hexStr.toIntOrNull(16) ?: 32
              if (!currentState.destinationSkipped) currentText.append(byteVal.toChar())
            }
          } else if (nextCh == '*') {
            // Ignorable destination
            currentState.destinationSkipped = true
          } else if (nextCh.isLetter()) {
            val cmd = StringBuilder()
            cmd.append(nextCh)
            while (true) {
              reader.mark(1)
              val c = reader.read()
              if (c != -1 && c.toChar().isLetter()) {
                cmd.append(c.toChar())
              } else {
                reader.reset()
                break
              }
            }

            // Read optional numeric parameter
            val param = StringBuilder()
            while (true) {
              reader.mark(1)
              val c = reader.read()
              if (c != -1 && (c.toChar().isDigit() || c.toChar() == '-')) {
                param.append(c.toChar())
              } else {
                reader.reset()
                break
              }
            }

            // Optional space delimiter consumed
            reader.mark(1)
            val sp = reader.read()
            if (sp != ' '.code) {
              reader.reset()
            }

            val cmdStr = cmd.toString()
            val paramVal = param.toString().toIntOrNull()

            when (cmdStr) {
              "par" -> flushParagraph()
              "line" -> currentText.append("\n")
              "tab" -> currentText.append("\t")
              "b" -> {
                flushText()
                currentState.bold = (paramVal != 0)
              }
              "i" -> {
                flushText()
                currentState.italic = (paramVal != 0)
              }
              "ul" -> {
                flushText()
                currentState.underline = true
              }
              "ulnone" -> {
                flushText()
                currentState.underline = false
              }
              "strike" -> {
                flushText()
                currentState.strike = (paramVal != 0)
              }
              "fs" -> {
                flushText()
                if (paramVal != null) {
                  currentState.fontSizePt = paramVal / 2f
                }
              }
              "u" -> {
                // Unicode character \uN
                if (paramVal != null) {
                  val code = if (paramVal < 0) paramVal + 65536 else paramVal
                  if (!currentState.destinationSkipped) currentText.append(code.toChar())
                }
              }
              "fonttbl", "colortbl", "stylesheet", "info", "pict" -> {
                currentState.destinationSkipped = true
              }
            }
          }
        } else if (ch != '\r' && ch != '\n') {
          if (!currentState.destinationSkipped) {
            currentText.append(ch)
          }
        }

        chInt = reader.read()
      }

      flushParagraph()
    }

    if (paragraphs.isEmpty()) {
      paragraphs.add(ParagraphBlock(runs = listOf(TextRun("Empty RTF document"))))
    }

    val pages = mutableListOf<DocumentPage>()
    var pList = mutableListOf<DocumentBlock>()
    var pageNum = 1
    for (p in paragraphs) {
      pList.add(p)
      if (pList.size >= 25) {
        pages.add(DocumentPage(pageNumber = pageNum++, blocks = pList))
        pList = mutableListOf()
      }
    }
    if (pList.isNotEmpty() || pages.isEmpty()) {
      pages.add(DocumentPage(pageNumber = pageNum, blocks = pList))
    }

    return DocumentModel(
      title = fileSource.displayName.substringBeforeLast('.'),
      pages = pages
    )
  }
}
