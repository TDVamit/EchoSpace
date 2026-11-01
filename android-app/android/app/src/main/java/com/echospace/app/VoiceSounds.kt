package com.echospace.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID

/** Category-level preview and explicit confirmation; no persistent object identity is implied. */
internal object VoiceSounds {
  private data class Pending(val id: String, val category: Int, val profile: Int, val expires: Long)
  @Volatile private var pending: Pending? = null
  private val names = mapOf("chime" to 19, "bell" to 20, "soft" to 21, "pulse" to 22, "beacon" to 23)

  fun load(context: Context) {
    val prefs = context.getSharedPreferences("voice_cues", Context.MODE_PRIVATE)
    for (i in SemanticClasses.labels.indices) AudioEngine.setCueProfile(i, prefs.getInt("category_$i", i))
  }

  @Synchronized fun preview(categoryName: String, requestedSound: String): String {
    val categoryText = categoryName.lowercase().trim().replace(Regex("^(a|an|the)\\s+"), "")
    val category = SemanticClasses.labels.indexOfFirst { label ->
      categoryText == label || categoryText == "${label}s" || categoryText.startsWith("$label ")
    }
    if (category < 0) return JSONObject().put("ok", false).put("error", "This object type is not supported by the detector.").toString()
    if (!VoiceScene.hasRecentCategory(SemanticClasses.labels[category])) return JSONObject().put("ok", false).put("error", "The scanner has not recognized this object type recently. Please point the camera at it and try again.").toString()
    val sound = requestedSound.lowercase().trim().ifBlank { "random" }
    val profile = if (sound == "random") (0..23).firstOrNull { it != AudioEngine.cueProfile(category) && !AudioEngine.usedCueProfiles().contains(it) }
      ?: ((AudioEngine.cueProfile(category) + 1) % 24) else names[sound]
    if (profile == null) return JSONObject().put("ok", false).put("error", "Choose chime, bell, soft, pulse, beacon, or random.").toString()
    val candidate = Pending(UUID.randomUUID().toString(), category, profile, SystemClock.elapsedRealtime() + 120_000)
    pending = candidate
    try {
      val playbackError = AudioEngine.playPreview(profile)
      if (playbackError != null) error(playbackError)
      // Allow buffered samples to leave the speaker before Deep asks for confirmation.
      Thread.sleep(200)
    } catch (e: Exception) {
      pending = null
      android.util.Log.e("EchoSpaceVoice", "Sound preview failed", e)
      return JSONObject().put("ok", false).put("error", e.message ?: "Could not play the preview.").toString()
    }
    return JSONObject().put("ok", true).put("preview_id", candidate.id).put("category", categoryName)
      .put("scope", "all objects of this type").put("sound", sound).toString()
  }

  @Synchronized fun confirm(context: Context, id: String): String {
    val candidate = pending
    if (candidate == null || candidate.id != id || SystemClock.elapsedRealtime() > candidate.expires)
      return JSONObject().put("ok", false).put("error", "Preview expired. Preview the sound again.").toString()
    AudioEngine.setCueProfile(candidate.category, candidate.profile)
    context.getSharedPreferences("voice_cues", Context.MODE_PRIVATE).edit()
      .putInt("category_${candidate.category}", candidate.profile).apply()
    pending = null
    return JSONObject().put("ok", true).put("category", SemanticClasses.labels[candidate.category])
      .put("scope", "all objects of this type").toString()
  }

}
