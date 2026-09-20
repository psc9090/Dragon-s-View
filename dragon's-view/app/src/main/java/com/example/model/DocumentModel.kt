package com.example.model

import android.graphics.Bitmap

data class DocumentModel(
  val title: String = "",
  val pageSettings: PageSettings = PageSettings(),
  val pages: List<DocumentPage> = emptyList(),
  val outline: List<OutlineItem> = emptyList(),
  val totalWordCount: Int = 0
)

data class PageSettings(
  val widthPt: Float = 612f,   // Standard US Letter width in points
  val heightPt: Float = 792f,  // Standard US Letter height in points
  val marginTopPt: Float = 54f,
  val marginBottomPt: Float = 54f,
  val marginLeftPt: Float = 54f,
  val marginRightPt: Float = 54f
)

data class DocumentPage(
  val pageNumber: Int,
  val blocks: List<DocumentBlock> = emptyList(),
  val headerText: String? = null,
  val footerText: String? = null
)

sealed interface DocumentBlock

data class ParagraphBlock(
  val runs: List<TextRun>,
  val alignment: Alignment = Alignment.LEFT,
  val isHeading: Boolean = false,
  val headingLevel: Int = 0,
  val isBullet: Boolean = false,
  val bulletNumber: String? = null,
  val indentLevel: Int = 0,
  val spaceBeforePt: Float = 0f,
  val spaceAfterPt: Float = 4f
) : DocumentBlock

data class TableBlock(
  val rows: List<TableRow>,
  val colWidthRatios: List<Float> = emptyList()
) : DocumentBlock

data class TableRow(
  val cells: List<TableCell>,
  val isHeader: Boolean = false
)

data class TableCell(
  val text: String,
  val blocks: List<DocumentBlock> = emptyList(),
  val colSpan: Int = 1,
  val rowSpan: Int = 1,
  val backgroundColor: Int? = null,
  val isHeader: Boolean = false
)

data class ImageBlock(
  val bitmap: Bitmap?,
  val widthDp: Int = 300,
  val heightDp: Int = 200,
  val caption: String? = null
) : DocumentBlock

data class TextRun(
  val text: String,
  val isBold: Boolean = false,
  val isItalic: Boolean = false,
  val isUnderline: Boolean = false,
  val isStrike: Boolean = false,
  val fontSizePt: Float = 11f,
  val textColor: Int? = null,
  val highlightColor: Int? = null,
  val linkUrl: String? = null
)

data class OutlineItem(
  val title: String,
  val level: Int,
  val pageIndex: Int
)

enum class Alignment {
  LEFT, CENTER, RIGHT, JUSTIFY
}
