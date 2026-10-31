package com.echospace.app

import java.nio.FloatBuffer
import kotlin.math.abs

/** Geometry gates for connecting a 2D instance mask to an observed 3D point. */
internal object SemanticGeometry {
  fun depthMatches(observed: Float, pointDepth: Float): Boolean =
    observed.isFinite() && pointDepth.isFinite() && observed in 0.18f..8f &&
      pointDepth in 0.18f..8f && abs(observed - pointDepth) <= 0.15f + 0.05f * pointDepth

  fun maskContains(coefficients: FloatArray, prototype: FloatBuffer, pixel: Int): Boolean {
    if (coefficients.size != 32 || pixel !in 0 until 160 * 160 || prototype.limit() < 32 * 160 * 160) return false
    var logit = 0f
    for (channel in 0 until 32) logit += coefficients[channel] * prototype.get(channel * 160 * 160 + pixel)
    // sigmoid(logit) > 0.5, without evaluating an exponential for every point.
    return logit > 0f
  }
}
