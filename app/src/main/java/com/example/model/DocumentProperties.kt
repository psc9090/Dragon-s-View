package com.example.model

import android.net.Uri

data class DocumentProperties(
  val fileName: String,
  val detectedFormat: FileFormat,
  val sizeBytes: Long,
  val formattedSize: String,
  val uri: Uri,
  val mimeType: String?,
  val lastModified: Long?,
  val pageCount: Int? = null,
  val slideCount: Int? = null,
  val sheetCount: Int? = null,
  val title: String? = null,
  val author: String? = null,
  val creationDate: String? = null,
  val application: String? = null,
  val detectionEvidence: List<String> = emptyList()
)

data class RecentFile(
  val uriString: String,
  val displayName: String,
  val format: FileFormat,
  val sizeBytes: Long,
  val lastOpenedTimestamp: Long,
  val lastPosition: String = "",
  val progressPercent: Float = 0f
)
