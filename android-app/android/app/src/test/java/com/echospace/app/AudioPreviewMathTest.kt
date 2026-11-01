package com.echospace.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPreviewMathTest {
  @Test fun previewHasSmoothBoundedStartAndEnd() {
    val total = 88_200
    val fade = 2_205
    assertEquals(0f, AudioPreviewMath.fade(0, total, fade), 0f)
    assertEquals(1f, AudioPreviewMath.fade(fade, total, fade), 0f)
    assertEquals(1f, AudioPreviewMath.fade(total / 2, total, fade), 0f)
    assertEquals(0f, AudioPreviewMath.fade(total - 1, total, fade), 0f)
    assertEquals(0f, AudioPreviewMath.fade(total, total, fade), 0f)
    var previous = 0f
    for (frame in 0 until total) {
      val current = AudioPreviewMath.fade(frame, total, fade)
      assertTrue(current in 0f..1f)
      assertTrue(kotlin.math.abs(current - previous) <= 0.001f)
      previous = current
    }
  }
}
