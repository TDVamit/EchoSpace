package com.echospace.app

/** Conservative evidence for removing a voxel contradicted by live raw depth. */
internal class MapCorrectionEvidence {
  private var contradictions = 0
  private var lastTimestamp = 0L

  fun observe(observation: Int, timestamp: Long): Boolean {
    if (timestamp <= 0L || timestamp <= lastTimestamp) return false
    when (observation) {
      MapCorrection.CLEAR -> {
        contradictions = if (timestamp - lastTimestamp <= 3_000_000_000L) contradictions + 1 else 1
        lastTimestamp = timestamp
        return contradictions >= 4
      }
      MapCorrection.PRESENT_OR_OCCLUDED -> {
        contradictions = 0
        lastTimestamp = timestamp
      }
      // Unknown or outside the view never counts as evidence of empty space.
    }
    return false
  }
}

internal object MapCorrection {
  const val UNKNOWN = 0
  const val CLEAR = 1
  const val PRESENT_OR_OCCLUDED = 2
  const val MIN_CONFIDENCE = 180
  const val MIN_CLEAR_SAMPLES = 3

  fun classify(pointDepth: Float, clearSamples: Int, nearOrOccludingSamples: Int): Int {
    if (!pointDepth.isFinite() || pointDepth !in 0.18f..8f) return UNKNOWN
    if (nearOrOccludingSamples > 0) return PRESENT_OR_OCCLUDED
    return if (clearSamples >= MIN_CLEAR_SAMPLES) CLEAR else UNKNOWN
  }

  fun isReliable(confidence: Int, observedDepth: Float): Boolean =
    confidence >= MIN_CONFIDENCE && observedDepth.isFinite() && observedDepth in 0.18f..8f

  fun isBehind(observedDepth: Float, pointDepth: Float): Boolean =
    observedDepth > pointDepth + maxOf(0.20f, pointDepth * 0.08f)

  /** Projects a map point to normalized image coordinates and camera depth. */
  fun project(point: FloatArray, matrix: FloatArray, focal: FloatArray, principal: FloatArray,
    imageWidth: Int, imageHeight: Int, out: FloatArray): Boolean {
    if (imageWidth <= 0 || imageHeight <= 0 || focal.size < 2 || principal.size < 2 || out.size < 3) return false
    val x = point[0]; val y = point[1]; val z = point[2]
    val cx = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]
    val cy = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]
    val depth = -(matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14])
    if (!depth.isFinite() || depth !in 0.18f..8f) return false
    val u = (focal[0] * cx / depth + principal[0]) / imageWidth
    val v = (principal[1] - focal[1] * cy / depth) / imageHeight
    if (!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return false
    out[0] = u; out[1] = v; out[2] = depth
    return true
  }
}
