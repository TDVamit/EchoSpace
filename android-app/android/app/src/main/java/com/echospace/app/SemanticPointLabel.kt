package com.echospace.app

/** Per-voxel evidence. A new class requires two separate, recent detections. */
internal class SemanticPointLabel {
  private var candidate = -1
  private var candidateCount = 0
  private var lastCandidateAt = Long.MIN_VALUE
  var category: Int = -1
    private set
  var lastConfirmedAt: Long = 0
    private set

  fun observe(newCategory: Int, confidence: Float, capturedAt: Long): Boolean {
    if (newCategory !in 0..17 || !confidence.isFinite() || confidence < 0.35f || capturedAt <= lastCandidateAt) return false
    if (candidate == newCategory && capturedAt - lastCandidateAt <= 6000L) candidateCount++
    else { candidate = newCategory; candidateCount = 1 }
    lastCandidateAt = capturedAt
    if (candidateCount < 2) return false
    val changed = category != newCategory
    category = newCategory
    lastConfirmedAt = capturedAt
    return changed || newCategory == PERSON_CATEGORY
  }

  fun expiresAt(): Long = if (category == PERSON_CATEGORY) lastConfirmedAt + 2500L else Long.MAX_VALUE

  companion object { const val PERSON_CATEGORY = 7 }
}

/** Default per-class tones; -1 means retain the unidentified point's noise. */
internal object SemanticTone {
  private val frequencies = floatArrayOf(330f, 440f, 554f, 294f, 220f, 370f, 494f, 660f, 262f, 392f, 587f, 311f, 523f, 698f, 247f, 349f, 784f, 415f, 880f, 932f, 1047f, 196f, 740f, 466f)
  fun frequencyHz(category: Int): Float = frequencies.getOrElse(category) { 0f }
  fun pulseHz(category: Int): Float = if (category in frequencies.indices) 0.65f + (category % 4) * 0.25f else 0f
}
