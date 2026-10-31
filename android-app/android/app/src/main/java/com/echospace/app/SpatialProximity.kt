package com.echospace.app

import kotlin.math.sqrt

/** Coordinate helpers for phone-relative heading with gravity-aligned height. */
internal object SpatialProximity {
  fun dot(x: Float, y: Float, z: Float, axis: FloatArray): Float = x * axis[0] + y * axis[1] + z * axis[2]
  fun horizontalDistance(x: Float, z: Float): Float = sqrt(x * x + z * z)
  fun withinZone(x: Float, y: Float, z: Float, range: Float, below: Float, above: Float): Boolean {
    if (!x.isFinite() || !y.isFinite() || !z.isFinite() || y < -below || y > above) return false
    val distance3d = x * x + y * y + z * z
    return distance3d >= 0.0016f && x * x + z * z < range * range
  }
  fun heading(up: FloatArray, forward: FloatArray, previous: FloatArray): FloatArray {
    fun flatten(v: FloatArray): FloatArray {
      val vertical = dot(v[0], v[1], v[2], up)
      return floatArrayOf(v[0] - vertical * up[0], v[1] - vertical * up[1], v[2] - vertical * up[2])
    }
    var result = flatten(forward)
    var length = sqrt(dot(result[0], result[1], result[2], result))
    if (length < 0.05f) { result = flatten(previous); length = sqrt(dot(result[0], result[1], result[2], result)) }
    if (length < 0.05f) {
      val fallback = if (kotlin.math.abs(up[0]) < 0.8f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 0f, 1f)
      result = flatten(fallback); length = sqrt(dot(result[0], result[1], result[2], result))
    }
    return floatArrayOf(result[0] / length, result[1] / length, result[2] / length)
  }
  fun right(forward: FloatArray, up: FloatArray): FloatArray = floatArrayOf(
    forward[1] * up[2] - forward[2] * up[1],
    forward[2] * up[0] - forward[0] * up[2],
    forward[0] * up[1] - forward[1] * up[0],
  )
  fun insidePolygon(x: Float, z: Float, polygon: FloatArray): Boolean {
    if (polygon.size < 6 || polygon.size % 2 != 0) return false
    var inside = false
    var j = polygon.size - 2
    var i = 0
    while (i < polygon.size) {
      val xi = polygon[i]; val zi = polygon[i + 1]; val xj = polygon[j]; val zj = polygon[j + 1]
      if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside
      j = i; i += 2
    }
    return inside
  }
}
