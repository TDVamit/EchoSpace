package com.echospace.app

/** Smooth envelope for a finite preview mixed into the existing stereo stream. */
internal object AudioPreviewMath {
  fun fade(frame: Int, totalFrames: Int, fadeFrames: Int): Float {
    if (frame < 0 || frame >= totalFrames || totalFrames <= 0 || fadeFrames <= 0) return 0f
    return minOf(1f, frame.toFloat() / fadeFrames, (totalFrames - 1 - frame).toFloat() / fadeFrames).coerceAtLeast(0f)
  }
}
