package com.echospace.app

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/** Bounded, transient scene summaries; coordinates are relative to the phone. */
internal object VoiceScene {
  private data class Snapshot(val json: String, val at: Long)
  private val snapshot = AtomicReference<Snapshot?>(null)
  private val detections = AtomicReference<Snapshot?>(null)
  private val recognizedAt = ConcurrentHashMap<String, Long>()

  fun clear() { snapshot.set(null); detections.set(null); recognizedAt.clear() }

  fun updateDetections(result: LiveObjectDetector.Result) {
    val objects = JSONArray()
    for (item in result.detections.take(15)) {
      if (item.score < 0.35f) continue
      recognizedAt[item.name] = result.capturedAt
      val center = (item.left + item.right) / 2f
      val direction = when { center < 0.35f -> "front-left"; center > 0.65f -> "front-right"; else -> "front" }
      val objectInfo = JSONObject().put("category", item.name).put("direction", direction)
        .put("confidence", item.score.toDouble())
      item.distance?.let { objectInfo.put("depth_m", it.toDouble()) }
      objects.put(objectInfo)
    }
    detections.set(Snapshot(objects.toString(), result.capturedAt))
  }

  fun update(xyz: FloatArray, categories: IntArray, count: Int) {
    val nearest = mutableMapOf<Pair<Int, String>, Float>()
    for (i in 0 until count) {
      val category = categories[i]
      if (category !in SemanticClasses.labels.indices) continue
      val x = xyz[i * 3]; val z = xyz[i * 3 + 2]
      val direction = if (abs(x) > abs(z)) { if (x > 0) "right" else "left" } else { if (z < 0) "front" else "behind" }
      val distance = sqrt(x * x + z * z)
      val key = category to direction
      if (distance < (nearest[key] ?: Float.MAX_VALUE)) nearest[key] = distance
    }
    val objects = JSONArray()
    nearest.entries.sortedBy { it.value }.take(18).forEach { (key, distance) ->
      objects.put(JSONObject().put("category", SemanticClasses.labels[key.first]).put("direction", key.second)
        .put("horizontal_distance_m", (distance * 10).toInt() / 10.0))
    }
    snapshot.set(Snapshot(JSONObject().put("scan_active", true).put("audible_categories", objects).toString(), SystemClock.elapsedRealtime()))
  }

  fun read(): String {
    val now = SystemClock.elapsedRealtime()
    val current = snapshot.get()?.takeIf { now - it.at <= 2000 }
    val detected = detections.get()?.takeIf { now - it.at <= 2000 }
    if (current == null && detected == null) return JSONObject().put("scan_active", false).put("reason", "scene unavailable or stale").toString()
    val result = current?.let { JSONObject(it.json) } ?: JSONObject().put("scan_active", true).put("audible_categories", JSONArray())
    result.put("visible_detections", detected?.let { JSONArray(it.json) } ?: JSONArray())
    return result.toString()
  }

  fun hasRecentCategory(category: String): Boolean {
    val seenAt = recognizedAt[category]
    if (DepthScanActivity.isVoiceScanActive() && seenAt != null && SystemClock.elapsedRealtime() - seenAt in 0..30_000) return true
    val scene = JSONObject(read())
    for (key in listOf("visible_detections", "audible_categories")) {
      val items = scene.optJSONArray(key) ?: continue
      for (i in 0 until items.length()) if (items.optJSONObject(i)?.optString("category") == category) return true
    }
    return false
  }
}
