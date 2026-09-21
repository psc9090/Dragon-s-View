// app/src/main/java/com/dragonview/app/router/FileFormat.kt
package com.dragonview.app.router

import com.dragonview.app.viewer.code.CodeLanguage
import com.dragonview.app.viewer.image.ImageType

/**
 * Recognized high-level document formats supported by DragonView.
 */
enum class FileFormat(val label: String, val badgeColorHex: Long) {
    PDF("PDF Document", 0xFFE53935),
    DOCX("Word Document", 0xFF1E88E5),
    RTF("Rich Text Format", 0xFF00ACC1),
    IMAGE("Image", 0xFFFF7043),
    PPTX("PowerPoint Presentation", 0xFFFB8C00),
    XLSX("Excel Spreadsheet", 0xFF43A047),
    ODT("OpenDocument Text", 0xFF8E24AA),
    ODT_TEMPLATE("OpenDocument Template", 0xFF8E24AA),
    ODT_FLAT("Flat OpenDocument XML", 0xFF8E24AA),
    ODS("OpenDocument Spreadsheet", 0xFF43A047),
    ODS_TEMPLATE("OpenDocument Spreadsheet Template", 0xFF43A047),
    ODS_FLAT("Flat OpenDocument Spreadsheet XML", 0xFF43A047),
    ODP("OpenDocument Presentation", 0xFFFB8C00),
    ODP_TEMPLATE("OpenDocument Presentation Template", 0xFFFB8C00),
    ODP_FLAT("Flat OpenDocument Presentation XML", 0xFFFB8C00),
    ODG("OpenDocument Drawing", 0xFF00ACC1),
    ODG_TEMPLATE("OpenDocument Drawing Template", 0xFF00ACC1),
    ODG_FLAT("Flat OpenDocument Drawing XML", 0xFF00ACC1),
    MARKDOWN("Markdown Document", 0xFFAB47BC),
    CODE("Source Code", 0xFFD81B60),
    UNKNOWN("Unsupported / Corrupted", 0xFF757575)
}

/**
 * Detailed detection analysis result emitted by FormatRouter.
 *
 * @param format The identified format enum.
 * @param fileName Display name of the file.
 * @param fileSize Size in bytes (or -1 if stream length indeterminate).
 * @param mimeType MIME type reported by ContentResolver or inferred.
 * @param extension Lowercase file extension without dot.
 * @param isCorrupted True if extension/MIME suggested a known format but magic bytes conflicted or header was truncated.
 * @param statusMessage Explanatory message for user (e.g. why UNKNOWN or corrupted).
 * @param codeLanguage Specific code language when format is CODE.
 * @param imageType Specific image format when format is IMAGE.
 * @param magicBytesHeader Hex string representation of the first few bytes inspected.
 */
data class FormatDetectionResult(
    val format: FileFormat,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String?,
    val extension: String,
    val isCorrupted: Boolean = false,
    val statusMessage: String? = null,
    val codeLanguage: CodeLanguage? = null,
    val imageType: ImageType? = null,
    val magicBytesHeader: String = ""
)
