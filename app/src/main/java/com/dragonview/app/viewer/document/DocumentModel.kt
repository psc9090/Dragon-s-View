// app/src/main/java/com/dragonview/app/viewer/document/DocumentModel.kt
package com.dragonview.app.viewer.document

import android.graphics.Bitmap

/**
 * Universal run model representing formatted text in rich documents (DOCX, RTF, etc.).
 */
data class DocumentRun(
    val text: String,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isStrike: Boolean = false,
    val fontSizeSp: Float? = null,
    val colorHex: String? = null,
    val isHighlight: Boolean = false,
    val highlightColorHex: String? = null
)

/**
 * Paragraph horizontal alignment.
 */
enum class DocumentAlignment {
    LEFT, CENTER, RIGHT, JUSTIFY
}

/**
 * Polymorphic structural element contained in a document.
 */
sealed class DocumentElement {
    data class Paragraph(
        val runs: List<DocumentRun>,
        val alignment: DocumentAlignment = DocumentAlignment.LEFT,
        val isHeading: Boolean = false,
        val headingLevel: Int = 0,
        val isBullet: Boolean = false
    ) : DocumentElement() {
        fun plainText(): String = runs.joinToString("") { it.text }
    }

    data class Table(
        val rows: List<DocumentTableRow>
    ) : DocumentElement()

    data class Image(
        val mediaPath: String = "",
        val bitmap: Bitmap? = null,
        val altText: String? = null
    ) : DocumentElement()
}

/**
 * Table structure definitions.
 */
data class DocumentTableRow(
    val cells: List<DocumentTableCell>
)

data class DocumentTableCell(
    val paragraphs: List<DocumentElement.Paragraph>,
    val backgroundColorHex: String? = null
)

/**
 * Unified parsed document container returned by document parsers (DOCX, RTF).
 */
data class DocumentData(
    val elements: List<DocumentElement>,
    val paragraphCount: Int,
    val tableCount: Int = 0,
    val imageCount: Int = 0,
    val formatLabel: String = "Document"
)
