package com.echospace.app

import kotlin.math.atan2
import kotlin.math.sqrt

/** Select individual points across the mapped directions around the listener. */
internal object AudioPointSelector {
  private const val AZIMUTH_BINS = 16
  private const val HEIGHT_BINS = 3
  private const val BIN_COUNT = AZIMUTH_BINS * HEIGHT_BINS
  private const val TWO_PI = 2.0 * Math.PI

  /** Returns offsets into xyz; all returned points are within range. */
  fun select(xyz: FloatArray, listener: FloatArray, range: Float, limit: Int, categories: IntArray? = null): IntArray =
    selectOriented(xyz, listener, range, limit, categories, floatArrayOf(0f, 1f, 0f),
      floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), 1.5f, 0.6f, null)

  fun selectOriented(xyz: FloatArray, listener: FloatArray, range: Float, limit: Int, categories: IntArray?,
    up: FloatArray, right: FloatArray, forward: FloatArray, below: Float, above: Float, excluded: BooleanArray?): IntArray {
    if (limit <= 0 || range <= 0f || listener.size < 3) return IntArray(0)
    if (categories != null && categories.size == xyz.size / 3 && categories.any { it >= 0 }) {
      // Give identified objects and unclassified geometry separate shares when
      // both exist. A wall is currently unclassified, not a separate class.
      fun candidates(wantTagged: Boolean): IntArray {
        val compact = FloatArray(xyz.size)
        val offsets = IntArray(categories.size)
        var count = 0
        for (i in categories.indices) if (excluded?.getOrNull(i) != true && (categories[i] >= 0) == wantTagged) {
          xyz.copyInto(compact, count * 3, i * 3, i * 3 + 3)
          offsets[count++] = i * 3
        }
        return selectOriented(compact.copyOf(count * 3), listener, range, limit, null, up, right, forward, below, above, null).map { offsets[it / 3] }.toIntArray()
      }
      val tagged = candidates(true)
      val background = candidates(false)
      val result = IntArray(minOf(limit, tagged.size + background.size))
      var t = 0; var b = 0; var written = 0
      val taggedShare = minOf(tagged.size, limit / 2)
      val backgroundShare = minOf(background.size, limit - taggedShare)
      while (t < taggedShare) result[written++] = tagged[t++]
      while (b < backgroundShare) result[written++] = background[b++]
      while (written < result.size && t < tagged.size) result[written++] = tagged[t++]
      while (written < result.size && b < background.size) result[written++] = background[b++]
      return result
    }
    val counts = IntArray(BIN_COUNT)
    val buckets = IntArray(xyz.size / 3) { -1 }
    fun bucket(offset: Int): Int {
      if (excluded?.getOrNull(offset / 3) == true) return -1
      val dx = xyz[offset] - listener[0]
      val dy = xyz[offset + 1] - listener[1]
      val dz = xyz[offset + 2] - listener[2]
      val x = SpatialProximity.dot(dx, dy, dz, right)
      val y = SpatialProximity.dot(dx, dy, dz, up)
      val z = -SpatialProximity.dot(dx, dy, dz, forward)
      val d2 = x * x + y * y + z * z
      if (!SpatialProximity.withinZone(x, y, z, range, below, above)) return -1
      val azimuth = (((atan2(z.toDouble(), x.toDouble()) + Math.PI) / TWO_PI * AZIMUTH_BINS).toInt()).coerceIn(0, AZIMUTH_BINS - 1)
      val vertical = y / sqrt(d2)
      val height = if (vertical < -0.35f) 0 else if (vertical > 0.35f) 2 else 1
      return height * AZIMUTH_BINS + azimuth
    }
    var offset = 0
    while (offset + 2 < xyz.size) {
      val b = bucket(offset)
      buckets[offset / 3] = b
      if (b >= 0) counts[b]++
      offset += 3
    }
    val total = counts.sum()
    val wanted = minOf(limit, total)
    if (wanted == 0) return IntArray(0)
    // Share voices between populated directions, redistributing unused slots.
    val quota = IntArray(BIN_COUNT)
    var remaining = wanted
    while (remaining > 0) {
      var allocated = false
      for (b in 0 until BIN_COUNT) {
        if (quota[b] < counts[b]) {
          quota[b]++; remaining--; allocated = true
          if (remaining == 0) break
        }
      }
      if (!allocated) break
    }
    val starts = IntArray(BIN_COUNT)
    for (b in 1 until BIN_COUNT) starts[b] = starts[b - 1] + quota[b - 1]
    val seen = IntArray(BIN_COUNT)
    val written = IntArray(BIN_COUNT)
    val result = IntArray(wanted)
    offset = 0
    while (offset + 2 < xyz.size) {
      val b = buckets[offset / 3]
      if (b >= 0) {
        val before = seen[b].toLong() * quota[b] / counts[b]
        seen[b]++
        val after = seen[b].toLong() * quota[b] / counts[b]
        if (after > before) result[starts[b] + written[b]++] = offset
      }
      offset += 3
    }
    return result
  }
}
