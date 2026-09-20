package com.example.util

import android.content.Context
import android.content.SharedPreferences
import com.example.model.FileFormat
import com.example.model.RecentFile
import org.json.JSONArray
import org.json.JSONObject

class RecentFilesStore(context: Context) {
  private val prefs: SharedPreferences = context.getSharedPreferences("dragons_view_recents", Context.MODE_PRIVATE)

  fun getRecents(maxCount: Int = 10): List<RecentFile> {
    val jsonStr = prefs.getString("recents_list", "[]") ?: "[]"
    val list = mutableListOf<RecentFile>()
    try {
      val array = JSONArray(jsonStr)
      for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        list.add(
          RecentFile(
            uriString = obj.getString("uri"),
            displayName = obj.getString("name"),
            format = try { FileFormat.valueOf(obj.getString("format")) } catch (_: Exception) { FileFormat.UNKNOWN },
            sizeBytes = obj.optLong("size", 0L),
            lastOpenedTimestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            lastPosition = obj.optString("position", ""),
            progressPercent = obj.optDouble("progress", 0.0).toFloat()
          )
        )
      }
    } catch (_: Exception) {}
    return list.sortedByDescending { it.lastOpenedTimestamp }.take(maxCount)
  }

  fun addOrUpdate(recent: RecentFile) {
    val current = getRecents(50).toMutableList()
    current.removeAll { it.uriString == recent.uriString }
    current.add(0, recent)

    saveList(current.take(20))
  }

  fun remove(uriString: String) {
    val current = getRecents(50).toMutableList()
    current.removeAll { it.uriString == uriString }
    saveList(current)
  }

  fun clear() {
    prefs.edit().remove("recents_list").apply()
  }

  private fun saveList(list: List<RecentFile>) {
    val array = JSONArray()
    for (item in list) {
      val obj = JSONObject().apply {
        put("uri", item.uriString)
        put("name", item.displayName)
        put("format", item.format.name)
        put("size", item.sizeBytes)
        put("timestamp", item.lastOpenedTimestamp)
        put("position", item.lastPosition)
        put("progress", item.progressPercent.toDouble())
      }
      array.put(obj)
    }
    prefs.edit().putString("recents_list", array.toString()).apply()
  }
}
