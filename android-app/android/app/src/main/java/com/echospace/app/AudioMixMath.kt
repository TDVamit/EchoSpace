package com.echospace.app

/** Bounded per-class mix and linked stereo protection. */
internal object AudioMixMath {
  fun objectGain(volume: Float, proximity: Float, categoryVoices: Int): Float =
    volume.coerceIn(0f, 1f) * proximity.coerceIn(0f, 1f) * 0.65f / categoryVoices.coerceAtLeast(1)

  fun limiter(peak: Float): Float {
    if (peak <= 0.75f) return 1f
    val over = peak - 0.75f
    val limited = 0.75f + 0.23f * over / (0.23f + over)
    return limited / peak
  }
}
