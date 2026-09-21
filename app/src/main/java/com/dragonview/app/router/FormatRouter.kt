// app/src/main/java/com/dragonview/app/router/FormatRouter.kt
package com.dragonview.app.router

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.dragonview.app.viewer.code.CodeLanguage
import com.dragonview.app.viewer.image.ImageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * FormatRouter inspects files via triple-check verification:
 * 1. Extension parsing
 * 2. ContentResolver MIME type query
 * 3. File header magic bytes inspection
 *
 * Memory Safety:
 * - Streams are strictly scoped with `.use { }` to guarantee no unclosed file descriptors.
 * - Only the first 1KB of the file is sampled for magic numbers.
 * - No Activity references are retained; operations execute on Dispatchers.IO.
 */
object FormatRouter {

    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK..
    private val ZIP_EMPTY_MAGIC = byteArrayOf(0x50, 0x4B, 0x05, 0x06) // Empty zip
    private val CFB_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    ) // Microsoft Compound File Binary (legacy .doc, .xls, .ppt)

    // Image magic bytes
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val GIF87_MAGIC = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF89_MAGIC = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)
    private val BMP_MAGIC = byteArrayOf(0x42, 0x4D)
    private val TIFF_II_MAGIC = byteArrayOf(0x49, 0x49, 0x2A, 0x00)
    private val TIFF_MM_MAGIC = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)
    private val FTYP_MAGIC = "ftyp".toByteArray(Charsets.US_ASCII)
    private val RTF_MAGIC = byteArrayOf(0x7B, 0x5C, 0x72, 0x74, 0x66) // "{\rtf"

    suspend fun detectFormat(context: Context, uri: Uri): FormatDetectionResult =
        withContext(Dispatchers.IO) {
            val contentResolver = context.contentResolver

            // 1. Resolve Display Name & Size from ContentResolver
            val (resolvedName, resolvedSize) = resolveMetadata(contentResolver, uri)
            val extension = extractExtension(resolvedName, uri)

            // 2. Query MIME type from ContentResolver, fallback to MimeTypeMap
            val mimeType = contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

            // 3. Read header bytes (capped at 1024 bytes) safely with use { }
            val headerBytes = readHeaderBytes(contentResolver, uri, 1024)
            val hexHeader = headerBytes.take(16).joinToString(" ") { "%02X".format(it) }

            if (headerBytes.isEmpty()) {
                return@withContext FormatDetectionResult(
                    format = FileFormat.UNKNOWN,
                    fileName = resolvedName,
                    fileSize = resolvedSize,
                    mimeType = mimeType,
                    extension = extension,
                    isCorrupted = true,
                    statusMessage = "The file is completely empty (0 bytes) or the stream could not be read.",
                    magicBytesHeader = hexHeader
                )
            }

            // 4. Verify against known Magic Bytes

            // --- PDF Verification ---
            if (startsOrContainsBytes(headerBytes, PDF_MAGIC)) {
                return@withContext FormatDetectionResult(
                    format = FileFormat.PDF,
                    fileName = resolvedName,
                    fileSize = resolvedSize,
                    mimeType = mimeType ?: "application/pdf",
                    extension = extension,
                    isCorrupted = false,
                    statusMessage = "Valid PDF document confirmed by %PDF header.",
                    magicBytesHeader = hexHeader
                )
            } else if (extension == "pdf" || mimeType == "application/pdf") {
                return@withContext FormatDetectionResult(
                    format = FileFormat.UNKNOWN,
                    fileName = resolvedName,
                    fileSize = resolvedSize,
                    mimeType = mimeType,
                    extension = extension,
                    isCorrupted = true,
                    statusMessage = "File claims to be a PDF (.pdf), but lacks the standard '%PDF-' magic header. The file appears damaged, truncated, or is not a genuine PDF.",
                    magicBytesHeader = hexHeader
                )
            }

            // --- RTF Verification ---
            if (startsOrContainsBytes(headerBytes, RTF_MAGIC)) {
                return@withContext FormatDetectionResult(
                    format = FileFormat.RTF,
                    fileName = resolvedName,
                    fileSize = resolvedSize,
                    mimeType = mimeType ?: "application/rtf",
                    extension = extension,
                    isCorrupted = false,
                    statusMessage = "Rich Text Format confirmed by {\\rtf header.",
                    magicBytesHeader = hexHeader
                )
            } else if (extension == "rtf" || mimeType == "application/rtf" || mimeType == "text/rtf") {
                return@withContext FormatDetectionResult(
                    format = FileFormat.UNKNOWN,
                    fileName = resolvedName,
                    fileSize = resolvedSize,
                    mimeType = mimeType,
                    extension = extension,
                    isCorrupted = true,
                    statusMessage = "This RTF file appears corrupted or invalid (missing {\\rtf header).",
                    magicBytesHeader = hexHeader
                )
            }

            // --- Flat OpenDocument XML (.fodt / .fods) Verification ---
            if (extension == "fodt" || mimeType == "application/vnd.oasis.opendocument.text-flat-xml") {
                val headerString = String(headerBytes, Charsets.UTF_8)
                val isFodtXml = headerString.contains("office:document") ||
                    headerString.contains("xmlns:office") ||
                    headerString.contains("opendocument") ||
                    headerString.contains("<?xml")
                if (isFodtXml) {
                    return@withContext FormatDetectionResult(
                        format = FileFormat.ODT_FLAT,
                        fileName = resolvedName,
                        fileSize = resolvedSize,
                        mimeType = mimeType ?: "application/vnd.oasis.opendocument.text-flat-xml",
                        extension = extension,
                        isCorrupted = false,
                        statusMessage = "Confirmed Flat OpenDocument XML (.fodt).",
                        magicBytesHeader = hexHeader
                    )
                }
            } else if (extension == "fods" || mimeType == "application/vnd.oasis.opendocument.spreadsheet-flat-xml") {
                val headerString = String(headerBytes, Charsets.UTF_8)
                val isFodsXml = headerString.contains("office:document") ||
                    headerString.contains("xmlns:office") ||
                    headerString.contains("opendocument") ||
                    headerString.contains("<?xml")
                if (isFodsXml) {
                    return@withContext FormatDetectionResult(
                        format = FileFormat.ODS_FLAT,
                        fileName = resolvedName,
                        fileSize = resolvedSize,
                        mimeType = mimeType ?: "application/vnd.oasis.opendocument.spreadsheet-flat-xml",
                        extension = extension,
                        isCorrupted = false,
                        statusMessage = "Confirmed Flat OpenDocument Spreadsheet XML (.fods).",
                        magicBytesHeader = hexHeader
                    )
                }
            } else if (extension == "fodp" || mimeType == "application/vnd.oasis.opendocument.presentation-flat-xml") {
                val headerString = String(headerBytes, Charsets.UTF_8)
                val isFodpXml = headerString.contains("office:document") ||
                    headerString.contains("xmlns:office") ||
                    headerString.contains("opendocument") ||
                    headerString.contains("<?xml")
                if (isFodpXml) {
                    return@withContext FormatDetectionResult(
                        format = FileFormat.ODP_FLAT,
                        fileName = resolvedName,
                        fileSize = resolvedSize,
                        mimeType = mimeType ?: "application/vnd.oasis.opendocument.presentation-flat-xml",
                        extension = extension,
                        isCorrupted = false,
                        statusMessage = "Confirmed Flat OpenDocument Presentation XML (.fodp).",
                        magicBytesHeader = hexHeader
                    )
                }
            } else if (extension == "fodg" || mimeType == "application/vnd.oasis.opendocument.graphics-flat-xml") {
                val headerString = String(headerBytes, Charsets.UTF_8)
                val isFodgXml = headerString.contains("office:document") ||
                    headerString.contains("xmlns:office") ||
                    headerString.contains("opendocument") ||
                    headerString.contains("office:drawing") ||
                    headerString.contains("<?xml")
                if (isFodgXml) {
                    return@withContext FormatDetectionResult(
                        format = FileFormat.ODG_FLAT,
                        fileName = resolvedName,
                        fileSize = resolvedSize,
                        mimeType = mimeType ?: "application/vnd.oasis.opendocument.graphics-flat-xml",
                        extension = extension,
                        isCorrupted = false,
                        statusMessage = "Confirmed Flat OpenDocument Drawing XML (.fodg).",
                        magicBytesHeader = hexHeader
                    )
                }
            }

            // --- ZIP Container Verification (DOCX, PPTX, XLSX, ODT) ---
            if (startsWithBytes(headerBytes, ZIP_MAGIC) || startsWithBytes(headerBytes, ZIP_EMPTY_MAGIC)) {
                val zipFormat = identifyZipContainer(contentResolver, uri, extension)
                if (zipFormat != null) {
                    return@withContext FormatDetectionResult(
                        format = zipFormat,
                        fileName = resolvedName,
                        fileSize = resolvedSize,
                        mimeType = mimeType,
                        extension = extension,
                        isCorrupted = false,
                        statusMessage = "Confirmed ${zipFormat.label} package structure.",
                        magicBytesHeader = hexHeader
                    )
                } else if (isOfficeExtension(extension)) {
                    return@withContext FormatDetectionResult(
                        format = FileFormat.UNKNOWN,
                        fileName = resolvedName,
                        fileSize = resolvedSize,
                        mimeType = mimeType,
                        extension = extension,
                        isCorrupted = true,
                        statusMessage = "File has a .$extension extension and valid ZIP header, but internal office XML structures (document/workbook/presentation) are missing or corrupt.",
                        magicBytesHeader = hexHeader
                    )
                }
            } else if (isOfficeExtension(extension)) {
                // If extension was docx/xlsx/pptx/odt but magic bytes are not PK:
                return@withContext FormatDetectionResult(
                    format = FileFormat.UNKNOWN,
                    fileName = resolvedName,
                    fileSize = resolvedSize,
                    mimeType = mimeType,
                    extension = extension,
                    isCorrupted = true,
                    statusMessage = "File claims to be an Office document (.$extension), but is missing standard PK (ZIP) magic bytes (found: $hexHeader).",
                    magicBytesHeader = hexHeader
                )
            }

            // --- Legacy Microsoft Office (CFB) Verification (.doc, .xls, .ppt) ---
            if (startsWithBytes(headerBytes, CFB_MAGIC)) {
                val legacyFormat = when (extension) {
                    "doc" -> FileFormat.DOCX // Routed to Word renderer module
                    "xls" -> FileFormat.XLSX // Routed to Excel renderer module
                    "ppt" -> FileFormat.PPTX // Routed to PowerPoint renderer module
                    else -> FileFormat.DOCX
                }
                return@withContext FormatDetectionResult(
                    format = legacyFormat,
                    fileName = resolvedName,
                    fileSize = resolvedSize,
                    mimeType = mimeType,
                    extension = extension,
                    isCorrupted = false,
                    statusMessage = "Legacy Microsoft Compound File Binary detected ($extension).",
                    magicBytesHeader = hexHeader
                )
            }

            // --- Image Verification (.jpg, .jpeg, .png, .gif, .webp, .bmp, .tiff, .heic, .svg) ---
            val isImageExt = ImageType.isImageExtension(extension)
            val isImageMime = mimeType?.startsWith("image/") == true
            val detectedImageType = identifyImage(headerBytes, extension, mimeType)

            if (detectedImageType != null) {
                return@withContext FormatDetectionResult(
                    format = FileFormat.IMAGE,
                    fileName = resolvedName,
                    fileSize = resolvedSize,
                    mimeType = mimeType ?: "image/*",
                    extension = extension,
                    isCorrupted = false,
                    statusMessage = "Confirmed ${detectedImageType.label}.",
                    imageType = detectedImageType,
                    magicBytesHeader = hexHeader
                )
            } else if (isImageExt || isImageMime) {
                return@withContext FormatDetectionResult(
                    format = FileFormat.UNKNOWN,
                    fileName = resolvedName,
                    fileSize = resolvedSize,
                    mimeType = mimeType,
                    extension = extension,
                    isCorrupted = true,
                    statusMessage = "This image file appears corrupted or uses an unsupported variant",
                    imageType = ImageType.fromExtension(extension),
                    magicBytesHeader = hexHeader
                )
            }

            // --- Markdown Document Verification (.md, .markdown) ---
            if (extension in listOf("md", "markdown") || mimeType == "text/markdown") {
                val containsBinaryGarbage = headerBytes.take(256).any { it == 0.toByte() }
                if (containsBinaryGarbage) {
                    return@withContext FormatDetectionResult(
                        format = FileFormat.UNKNOWN,
                        fileName = resolvedName,
                        fileSize = resolvedSize,
                        mimeType = mimeType,
                        extension = extension,
                        isCorrupted = true,
                        statusMessage = "File extension suggests Markdown (.$extension), but file contains null binary bytes (0x00). It may be a compiled binary or corrupt file.",
                        magicBytesHeader = hexHeader
                    )
                }

                return@withContext FormatDetectionResult(
                    format = FileFormat.MARKDOWN,
                    fileName = resolvedName,
                    fileSize = resolvedSize,
                    mimeType = mimeType ?: "text/markdown",
                    extension = extension,
                    isCorrupted = false,
                    statusMessage = "Confirmed Markdown document (Reading View).",
                    codeLanguage = CodeLanguage.MARKDOWN,
                    magicBytesHeader = hexHeader
                )
            }

            // --- Source Code / Text Verification (.html, .css, .js, .json, .xml, .py, .kt, .c...) ---
            val isLikelyCodeExt = CodeLanguage.isCodeExtension(extension)
            val isTextMime = mimeType?.startsWith("text/") == true ||
                mimeType in listOf(
                    "application/json",
                    "application/xml",
                    "text/xml",
                    "application/javascript",
                    "text/javascript",
                    "text/css",
                    "text/html",
                    "text/markdown",
                    "text/x-python",
                    "application/x-sh"
                )

            if (isLikelyCodeExt || isTextMime) {
                val containsBinaryGarbage = headerBytes.take(256).any { it == 0.toByte() }
                if (containsBinaryGarbage) {
                    return@withContext FormatDetectionResult(
                        format = FileFormat.UNKNOWN,
                        fileName = resolvedName,
                        fileSize = resolvedSize,
                        mimeType = mimeType,
                        extension = extension,
                        isCorrupted = true,
                        statusMessage = "File extension suggests code (.$extension), but file contains null binary bytes (0x00). It may be a compiled binary or corrupt file.",
                        magicBytesHeader = hexHeader
                    )
                }

                val detectedLang = CodeLanguage.fromExtension(extension)
                return@withContext FormatDetectionResult(
                    format = FileFormat.CODE,
                    fileName = resolvedName,
                    fileSize = resolvedSize,
                    mimeType = mimeType ?: "text/plain",
                    extension = extension,
                    isCorrupted = false,
                    statusMessage = "Source code detected: ${detectedLang.displayName}",
                    codeLanguage = detectedLang,
                    magicBytesHeader = hexHeader
                )
            }

            // --- Unsupported / UNKNOWN Format ---
            val reason = if (extension.isBlank()) {
                "File has no extension and its binary signature ($hexHeader) has no matching renderer in DragonView."
            } else {
                "No matching renderer for '$extension' ($mimeType). DragonView currently renders PDFs, Word, PowerPoint, Excel, OpenDocument, and syntax-highlighted source code."
            }

            FormatDetectionResult(
                format = FileFormat.UNKNOWN,
                fileName = resolvedName,
                fileSize = resolvedSize,
                mimeType = mimeType,
                extension = extension,
                isCorrupted = false,
                statusMessage = reason,
                magicBytesHeader = hexHeader
            )
        }

    private fun isOfficeExtension(ext: String): Boolean =
        ext in listOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "ott", "ots", "otp", "odg", "otg", "fodt", "fods", "fodp", "fodg")

    /**
     * Inspects ZIP directory entries without extracting the archive to avoid heavy memory allocation.
     */
    private fun identifyZipContainer(
        contentResolver: ContentResolver,
        uri: Uri,
        extensionHint: String
    ): FileFormat? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry: ZipEntry?
                    var count = 0
                    var foundWord = false
                    var foundExcel = false
                    var foundPpt = false
                    var foundOdt = false
                    var foundOds = false
                    var foundOdp = false
                    var foundOdg = false

                    // Check first 30 entries to identify package type quickly
                    while (zipStream.nextEntry.also { entry = it } != null && count < 30) {
                        val name = entry?.name?.lowercase() ?: ""
                        if (name.startsWith("word/") || name.contains("document.xml")) foundWord = true
                        if (name.startsWith("xl/") || name.contains("workbook.xml")) foundExcel = true
                        if (name.startsWith("ppt/") || name.contains("presentation.xml")) foundPpt = true
                        if (name == "mimetype") {
                            val mimeBytes = ByteArray(128)
                            val read = zipStream.read(mimeBytes, 0, 128)
                            if (read > 0) {
                                val mimeStr = String(mimeBytes, 0, read, Charsets.US_ASCII)
                                if (mimeStr.contains("spreadsheet")) foundOds = true
                                else if (mimeStr.contains("presentation")) foundOdp = true
                                else if (mimeStr.contains("graphics") || mimeStr.contains("drawing")) foundOdg = true
                                else if (mimeStr.contains("text")) foundOdt = true
                            }
                        }
                        if (name == "content.xml") {
                            if (extensionHint == "ods" || extensionHint == "ots") foundOds = true
                            else if (extensionHint == "odp" || extensionHint == "otp") foundOdp = true
                            else if (extensionHint == "odg" || extensionHint == "otg") foundOdg = true
                            else if (extensionHint == "odt" || extensionHint == "ott") foundOdt = true
                            else foundOdt = true
                        }
                        zipStream.closeEntry()
                        count++
                    }

                    when {
                        foundWord -> FileFormat.DOCX
                        foundExcel -> FileFormat.XLSX
                        foundPpt -> FileFormat.PPTX
                        foundOdg -> if (extensionHint == "otg") FileFormat.ODG_TEMPLATE else FileFormat.ODG
                        foundOdp -> if (extensionHint == "otp") FileFormat.ODP_TEMPLATE else FileFormat.ODP
                        foundOds -> if (extensionHint == "ots") FileFormat.ODS_TEMPLATE else FileFormat.ODS
                        foundOdt -> if (extensionHint == "ott") FileFormat.ODT_TEMPLATE else FileFormat.ODT
                        extensionHint == "docx" -> FileFormat.DOCX
                        extensionHint == "xlsx" -> FileFormat.XLSX
                        extensionHint == "pptx" -> FileFormat.PPTX
                        extensionHint == "odg" -> FileFormat.ODG
                        extensionHint == "otg" -> FileFormat.ODG_TEMPLATE
                        extensionHint == "odp" -> FileFormat.ODP
                        extensionHint == "otp" -> FileFormat.ODP_TEMPLATE
                        extensionHint == "ods" -> FileFormat.ODS
                        extensionHint == "ots" -> FileFormat.ODS_TEMPLATE
                        extensionHint == "odt" -> FileFormat.ODT
                        extensionHint == "ott" -> FileFormat.ODT_TEMPLATE
                        else -> null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun startsWithBytes(source: ByteArray, pattern: ByteArray): Boolean {
        if (source.size < pattern.size) return false
        for (i in pattern.indices) {
            if (source[i] != pattern[i]) return false
        }
        return true
    }

    private fun readHeaderBytes(contentResolver: ContentResolver, uri: Uri, maxBytes: Int): ByteArray {
        return try {
            val stream = try {
                contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                if (uri.scheme == "file" && uri.path != null) {
                    java.io.FileInputStream(java.io.File(uri.path!!))
                } else throw e
            }
            stream?.use { isStream ->
                val buffer = ByteArray(maxBytes)
                var totalRead = 0
                while (totalRead < maxBytes) {
                    val read = isStream.read(buffer, totalRead, maxBytes - totalRead)
                    if (read <= 0) break
                    totalRead += read
                }
                if (totalRead > 0) buffer.copyOf(totalRead) else ByteArray(0)
            } ?: ByteArray(0)
        } catch (e: Exception) {
            android.util.Log.e("FormatRouter", "Failed to read header bytes from $uri: ${e.message}", e)
            ByteArray(0)
        }
    }

    private fun startsOrContainsBytes(source: ByteArray, pattern: ByteArray): Boolean {
        if (source.size < pattern.size) return false
        // Check exact prefix
        var matchesPrefix = true
        for (i in pattern.indices) {
            if (source[i] != pattern[i]) {
                matchesPrefix = false
                break
            }
        }
        if (matchesPrefix) return true

        // For formats like PDF, check within first 512 bytes in case of leading whitespace/comments
        val searchWindow = minOf(source.size - pattern.size, 512)
        for (offset in 0..searchWindow) {
            var found = true
            for (j in pattern.indices) {
                if (source[offset + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) return true
        }
        return false
    }

    private fun resolveMetadata(contentResolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = "unknown_document"
        var size: Long = -1

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex != -1) {
                            cursor.getString(nameIndex)?.let { name = it }
                        }
                        if (sizeIndex != -1) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback to URI lastPathSegment
            }
        }

        if (name == "unknown_document") {
            uri.lastPathSegment?.let { segment ->
                val lastPart = segment.substringAfterLast('/')
                if (lastPart.isNotBlank()) name = lastPart
            }
        }

        return Pair(name, size)
    }

    private fun extractExtension(fileName: String, uri: Uri): String {
        val fromName = fileName.substringAfterLast('.', "").lowercase().trim()
        if (fromName.isNotBlank() && fromName != fileName.lowercase().trim()) {
            return fromName
        }
        val fromUri = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()?.trim() ?: ""
        return if (fromUri.length in 1..8) fromUri else ""
    }

    private fun identifyImage(headerBytes: ByteArray, extension: String, mimeType: String?): ImageType? {
        // 1. Check JPEG: FF D8 (SOI marker)
        if (headerBytes.size >= 2 &&
            headerBytes[0] == 0xFF.toByte() &&
            headerBytes[1] == 0xD8.toByte()
        ) {
            return ImageType.JPEG
        }

        // 2. Check PNG: 89 50 4E 47 0D 0A 1A 0A
        if (startsWithBytes(headerBytes, PNG_MAGIC)) {
            return ImageType.PNG
        }

        // 3. Check GIF: GIF87a or GIF89a
        if (startsWithBytes(headerBytes, GIF87_MAGIC) || startsWithBytes(headerBytes, GIF89_MAGIC)) {
            return ImageType.GIF
        }

        // 4. Check WebP: RIFF....WEBP
        if (headerBytes.size >= 12 &&
            startsWithBytes(headerBytes, RIFF_MAGIC) &&
            headerBytes[8] == 0x57.toByte() && headerBytes[9] == 0x45.toByte() &&
            headerBytes[10] == 0x42.toByte() && headerBytes[11] == 0x50.toByte()
        ) {
            return ImageType.WEBP
        }

        // 5. Check BMP: BM (42 4D)
        if (headerBytes.size >= 2 && headerBytes[0] == 0x42.toByte() && headerBytes[1] == 0x4D.toByte()) {
            return ImageType.BMP
        }

        // 6. Check TIFF: II* (49 49 2A 00) or MM* (4D 4D 00 2A)
        if (startsWithBytes(headerBytes, TIFF_II_MAGIC) || startsWithBytes(headerBytes, TIFF_MM_MAGIC)) {
            return ImageType.TIFF
        }

        // 7. Check HEIC / HEIF: contains "ftyp" in bytes 4..32
        if (headerBytes.size >= 12) {
            val ftypWindow = headerBytes.take(32).toByteArray()
            if (startsOrContainsBytes(ftypWindow, FTYP_MAGIC)) {
                val ftypStr = String(ftypWindow, Charsets.US_ASCII).lowercase()
                if (ftypStr.contains("heic") || ftypStr.contains("mif1") || ftypStr.contains("msf1") ||
                    ftypStr.contains("hevc") || ftypStr.contains("heix") || extension in listOf("heic", "heif")
                ) {
                    return ImageType.HEIC
                }
            }
        }
        if ((extension == "heic" || extension == "heif") && headerBytes.size >= 8) {
            return ImageType.HEIC
        }

        // 8. Check SVG: text containing <svg, <?xml with svg, or svg doctype
        val textSample = if (headerBytes.isNotEmpty()) {
            String(headerBytes, Charsets.UTF_8).lowercase()
        } else ""
        if (textSample.contains("<svg") || textSample.contains("xmlns=\"http://www.w3.org/2000/svg\"") ||
            ((extension == "svg" || mimeType == "image/svg+xml") && !headerBytes.take(256).any { it == 0.toByte() })
        ) {
            return ImageType.SVG
        }

        // 9. Fallback by extension or MIME type if header is non-empty
        if (headerBytes.isNotEmpty()) {
            val fromExt = ImageType.fromExtension(extension)
            if (fromExt != null) return fromExt

            if (mimeType != null && mimeType.startsWith("image/")) {
                val subType = mimeType.substringAfter("image/").lowercase()
                return when {
                    subType.contains("jpeg") || subType.contains("jpg") -> ImageType.JPEG
                    subType.contains("png") -> ImageType.PNG
                    subType.contains("gif") -> ImageType.GIF
                    subType.contains("webp") -> ImageType.WEBP
                    subType.contains("bmp") -> ImageType.BMP
                    subType.contains("tiff") || subType.contains("tif") -> ImageType.TIFF
                    subType.contains("heic") || subType.contains("heif") -> ImageType.HEIC
                    subType.contains("svg") -> ImageType.SVG
                    else -> null
                }
            }
        }

        return null
    }
}
