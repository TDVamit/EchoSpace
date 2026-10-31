package com.echospace.app

/** Smooth, bounded default textures and rhythmic envelopes for detected classes. */
internal object AudioCueMath {
  fun texture(category: Int, fundamental: Float, harmonic: Float): Float = when (category % 3) {
    0 -> fundamental
    1 -> 0.70f * fundamental + 0.30f * harmonic
    else -> 0.85f * fundamental - 0.15f * harmonic
  }
  fun envelope(category: Int, slowSine: Float, doubleSine: Float): Float {
    val rhythm = when (category % 4) {
      0 -> return 0.75f
      1 -> (slowSine + 1f) * 0.5f
      2 -> (doubleSine + 1f) * 0.5f
      else -> ((slowSine + 1f) * 0.5f) * ((doubleSine + 1f) * 0.5f)
    }
    // Rounded pulses fade to zero, with no hard on/off edges.
    return 0.08f + 0.92f * rhythm * rhythm * rhythm
  }
}
