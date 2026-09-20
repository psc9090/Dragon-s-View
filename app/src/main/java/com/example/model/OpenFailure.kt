package com.example.model

enum class PasswordKind {
  PDF,
  OFFICE,
  ODF,
  ARCHIVE
}

sealed interface OpenFailure {
  data class CorruptedStructure(val stage: String, val cause: Throwable? = null) : OpenFailure
  data class MissingRequiredPart(val part: String) : OpenFailure
  data class PasswordRequired(val kind: PasswordKind, val wrongPasswordBefore: Boolean = false) : OpenFailure
  data class UnsupportedVariant(val detail: String) : OpenFailure
  data class UnsupportedKnownFormat(val format: FileFormat, val customReason: String? = null) : OpenFailure
  object UnknownFormat : OpenFailure
  data class LimitExceeded(val limit: String) : OpenFailure
  object NoLongerAccessible : OpenFailure
  object EmptyFile : OpenFailure
  data class InsufficientMemoryOrStorage(val what: String) : OpenFailure
  data class IoError(val cause: Throwable? = null) : OpenFailure

  fun getMessage(): String = when (this) {
    is CorruptedStructure -> "The file structure appears to be corrupted or incomplete ($stage)."
    is MissingRequiredPart -> "This document is missing a required internal component ($part)."
    is PasswordRequired -> when (kind) {
      PasswordKind.PDF -> if (wrongPasswordBefore) "Incorrect password. Please try again." else "This PDF is password protected."
      PasswordKind.OFFICE -> "This Office file is encrypted with a password."
      PasswordKind.ODF -> "This OpenDocument file is encrypted with a password."
      PasswordKind.ARCHIVE -> "This archive requires a password to extract."
    }
    is UnsupportedVariant -> "This file uses a variant that Dragon's View does not currently support: $detail"
    is UnsupportedKnownFormat -> customReason ?: when (format) {
      FileFormat.LEGACY_OFFICE -> "Legacy .doc/.xls/.ppt files are not supported by this lightweight viewer."
      FileFormat.APPLE_IWORK -> ".pages/.numbers/.key use Apple's proprietary closed format and cannot be reliably rendered by this lightweight offline viewer."
      else -> "This format (${format.displayName}) is currently unsupported."
    }
    is UnknownFormat -> "Dragon's View could not identify this file format."
    is LimitExceeded -> "This file exceeds safety limits: $limit"
    is NoLongerAccessible -> "The file is no longer accessible. The system permission grant may have expired."
    is EmptyFile -> "The file is 0 bytes (empty)."
    is InsufficientMemoryOrStorage -> "Insufficient resources to open file: $what"
    is IoError -> "Error accessing storage: ${cause?.localizedMessage ?: "I/O error"}"
  }
}
