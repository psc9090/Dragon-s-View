package com.example.model

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

class FileSource private constructor(
  val context: Context,
  val uri: Uri,
  val displayName: String,
  val sizeBytes: Long,
  private val cachedFile: File? = null,
  private val pfd: ParcelFileDescriptor? = null
) : Closeable {

  fun openStream(): InputStream {
    return if (cachedFile != null && cachedFile.exists()) {
      FileInputStream(cachedFile)
    } else {
      context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Cannot open input stream for $uri")
    }
  }

  fun asFile(): File {
    return cachedFile ?: ensureCacheFile()
  }

  fun asZip(): ZipFile {
    val file = asFile()
    return ZipFile(file)
  }

  fun getFileDescriptor(): ParcelFileDescriptor {
    return pfd ?: context.contentResolver.openFileDescriptor(uri, "r")
      ?: throw IllegalStateException("Cannot obtain FileDescriptor for $uri")
  }

  private fun ensureCacheFile(): File {
    val cacheDir = File(context.cacheDir, "dragon_source_cache").apply { mkdirs() }
    val tempFile = File.createTempFile("dview_", ".tmp", cacheDir)
    tempFile.deleteOnExit()

    context.contentResolver.openInputStream(uri).use { input ->
      if (input == null) throw IllegalStateException("Cannot read stream from $uri")
      FileOutputStream(tempFile).use { output ->
        input.copyTo(output)
      }
    }
    return tempFile
  }

  override fun close() {
    try {
      pfd?.close()
    } catch (_: Exception) {}
    try {
      if (cachedFile != null && cachedFile.exists()) {
        cachedFile.delete()
      }
    } catch (_: Exception) {}
  }

  companion object {
    fun fromUri(context: Context, uri: Uri): FileSource {
      var displayName = "document"
      var sizeBytes = 0L

      // Query display name and size safely from ContentResolver
      try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
          val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (cursor.moveToFirst()) {
            if (nameIndex != -1 && !cursor.isNull(nameIndex)) {
              displayName = cursor.getString(nameIndex)
            }
            if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
              sizeBytes = cursor.getLong(sizeIndex)
            }
          }
        }
      } catch (_: Exception) {}

      if (displayName == "document") {
        displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
      }

      val pfd = try {
        context.contentResolver.openFileDescriptor(uri, "r")
      } catch (_: Exception) {
        null
      }

      var cachedFile: File? = null
      // If pfd is not seekable or null, create a seekable cache file
      if (pfd == null) {
        try {
          val cacheDir = File(context.cacheDir, "dragon_source_cache").apply { mkdirs() }
          val temp = File.createTempFile("src_", ".tmp", cacheDir)
          context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output ->
              input.copyTo(output)
            }
          }
          cachedFile = temp
          if (sizeBytes <= 0) {
            sizeBytes = temp.length()
          }
        } catch (_: Exception) {}
      }

      return FileSource(
        context = context,
        uri = uri,
        displayName = displayName,
        sizeBytes = sizeBytes,
        cachedFile = cachedFile,
        pfd = pfd
      )
    }

    fun fromByteArray(context: Context, name: String, bytes: ByteArray): FileSource {
      val cacheDir = File(context.cacheDir, "dragon_source_cache").apply { mkdirs() }
      val temp = File.createTempFile("mem_", ".tmp", cacheDir)
      temp.writeBytes(bytes)
      temp.deleteOnExit()
      return FileSource(
        context = context,
        uri = Uri.fromFile(temp),
        displayName = name,
        sizeBytes = bytes.size.toLong(),
        cachedFile = temp,
        pfd = null
      )
    }
  }
}
