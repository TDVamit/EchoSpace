package com.echospace.app

import java.nio.FloatBuffer
import kotlin.math.min

/** Matches points from a captured map snapshot against masks and depth from that same frame. */
internal object SemanticAssociation {
  data class Frame(
    val points: FloatArray, val mapToCamera: FloatArray, val focal: FloatArray, val principal: FloatArray,
    val imageWidth: Int, val imageHeight: Int, val imageToView: FloatArray, val imageToDepth: FloatArray,
    val depth: ShortArray, val depthWidth: Int, val depthHeight: Int,
    val contentWidth: Float, val contentHeight: Float, val padX: Float, val padY: Float,
  )
  data class Instance(val category: Int, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float, val coefficients: FloatArray)
  data class Matches(val points: FloatArray, val offsets: IntArray, val categories: IntArray, val scores: FloatArray)

  private fun affine(a: FloatArray, x: Float, y: Float, axis: Int): Float =
    a[axis] + x * (a[2 + axis] - a[axis]) + y * (a[4 + axis] - a[axis])

  fun associate(frame: Frame, objects: List<Instance>, prototype: FloatBuffer): Matches? {
    if (objects.isEmpty() || frame.points.isEmpty() || frame.depthWidth <= 0 || frame.depthHeight <= 0 ||
      frame.imageWidth <= 0 || frame.imageHeight <= 0 || frame.depth.size < frame.depthWidth * frame.depthHeight) return null
    // Inspect the entire captured map so a fixed sampling stride does not
    // permanently exclude the same voxels from consecutive observations.
    val offsets = IntArray(min(20000, frame.points.size / 3))
    val categories = IntArray(offsets.size)
    val scores = FloatArray(offsets.size)
    val matrix = frame.mapToCamera
    var count = 0
    var index = 0
    while (index + 2 < frame.points.size && count < offsets.size) {
      val x = frame.points[index]; val y = frame.points[index + 1]; val z = frame.points[index + 2]
      val cx = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]
      val cy = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]
      val cz = -(matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14])
      if (cz >= 0.18f && cz <= 8f) {
        val nx = (frame.focal[0] * cx / cz + frame.principal[0]) / frame.imageWidth
        val ny = (frame.principal[1] - frame.focal[1] * cy / cz) / frame.imageHeight
        if (nx in 0f..1f && ny in 0f..1f) {
          val vx = affine(frame.imageToView, nx, ny, 0); val vy = affine(frame.imageToView, nx, ny, 1)
          val tx = affine(frame.imageToDepth, nx, ny, 0); val ty = affine(frame.imageToDepth, nx, ny, 1)
          if (vx in 0f..1f && vy in 0f..1f && tx >= 0f && tx < 1f && ty >= 0f && ty < 1f) {
            val dx = (tx * frame.depthWidth).toInt(); val dy = (ty * frame.depthHeight).toInt()
            val observed = (frame.depth[dy * frame.depthWidth + dx].toInt() and 0xffff) / 1000f
            if (SemanticGeometry.depthMatches(observed, cz)) {
              val mx = ((vx * frame.contentWidth + frame.padX) / 4f).toInt().coerceIn(0, 159)
              val my = ((vy * frame.contentHeight + frame.padY) / 4f).toInt().coerceIn(0, 159)
              val pixel = my * 160 + mx
              // Instances arrive in confidence order, so the strongest overlapping mask wins.
              for (candidate in objects) {
                if (candidate.score < 0.35f || !candidate.score.isFinite() || candidate.category !in 0..17) continue
                if (vx < candidate.left || vx > candidate.right || vy < candidate.top || vy > candidate.bottom) continue
                if (SemanticGeometry.maskContains(candidate.coefficients, prototype, pixel)) {
                  offsets[count] = index; categories[count] = candidate.category; scores[count] = candidate.score
                  count++; break
                }
              }
            }
          }
        }
      }
      index += 3
    }
    return Matches(frame.points, offsets.copyOf(count), categories.copyOf(count), scores.copyOf(count))
  }
}
