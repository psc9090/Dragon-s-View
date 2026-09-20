package com.example.parser.archive

import android.content.Context
import com.example.model.FileSource
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ArchiveEntryItem(
  val name: String,
  val isDirectory: Boolean,
  val sizeBytes: Long,
  val compressedSizeBytes: Long,
  val entryPath: String
)

object ArchiveParser {

  fun listEntries(fileSource: FileSource): List<ArchiveEntryItem> {
    val items = mutableListOf<ArchiveEntryItem>()
    val zip = fileSource.asZip()
    try {
      val entries = zip.entries()
      var count = 0
      while (entries.hasMoreElements() && count < 5000) {
        val entry = entries.nextElement()
        items.add(
          ArchiveEntryItem(
            name = entry.name.substringAfterLast('/').ifEmpty { entry.name },
            isDirectory = entry.isDirectory,
            sizeBytes = entry.size,
            compressedSizeBytes = entry.compressedSize,
            entryPath = entry.name
          )
        )
        count++
      }
    } finally {
      try { zip.close() } catch (_: Exception) {}
    }
    return items.sortedWith(compareBy({ !it.isDirectory }, { it.entryPath }))
  }

  fun extractEntry(context: Context, fileSource: FileSource, entryPath: String): FileSource {
    val zip = fileSource.asZip()
    try {
      val entry = zip.getEntry(entryPath)
        ?: throw IllegalArgumentException("Entry not found: $entryPath")

      // Zip-slip security protection: write strictly to our isolated cache directory with safe unique name
      val safeCacheDir = File(context.cacheDir, "dragon_archive_extract").apply { mkdirs() }
      val cleanFileName = entryPath.substringAfterLast('/').ifEmpty { "file" }
      val targetFile = File.createTempFile("ext_", "_$cleanFileName", safeCacheDir)
      targetFile.deleteOnExit()

      zip.getInputStream(entry).use { input ->
        FileOutputStream(targetFile).use { output ->
          input.copyTo(output)
        }
      }

      return FileSource.fromUri(context, android.net.Uri.fromFile(targetFile))
    } finally {
      try { zip.close() } catch (_: Exception) {}
    }
  }
}
