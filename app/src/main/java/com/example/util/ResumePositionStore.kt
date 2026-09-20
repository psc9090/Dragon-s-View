package com.example.util

import android.content.Context
import android.content.SharedPreferences

class ResumePositionStore(context: Context) {
  private val prefs: SharedPreferences = context.getSharedPreferences("dragons_view_resume", Context.MODE_PRIVATE)

  fun savePosition(uriString: String, page: Int, offset: Int = 0) {
    prefs.edit().putString(uriString, "$page:$offset").apply()
  }

  fun getPosition(uriString: String): Pair<Int, Int> {
    val raw = prefs.getString(uriString, null) ?: return Pair(1, 0)
    val parts = raw.split(":")
    val page = parts.getOrNull(0)?.toIntOrNull() ?: 1
    val offset = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return Pair(page, offset)
  }
}
