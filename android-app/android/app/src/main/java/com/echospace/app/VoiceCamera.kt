package com.echospace.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.facebook.react.bridge.Promise
import com.google.ar.core.Frame
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/** Captures a small camera frame only when explicitly requested by voice. */
internal object VoiceCamera {
  private val pending = AtomicReference<Promise?>(null)
  fun request(promise: Promise) {
    if (!DepthScanActivity.isVoiceScanActive()) { promise.reject("NO_SCAN", "Start a scan before requesting a camera image."); return }
    if (!pending.compareAndSet(null, promise)) { promise.reject("BUSY", "An image is already being captured."); return }
    Handler(Looper.getMainLooper()).postDelayed({ if (pending.compareAndSet(promise, null)) promise.reject("TIMEOUT", "Camera image unavailable.") }, 4000)
  }

  fun capture(frame: Frame) {
    if (pending.get() == null) return
    try {
      frame.acquireCameraImage().use { image ->
        val width = image.width; val height = image.height
        val planes = image.planes
        val nv21 = ByteArray(width * height * 3 / 2)
        fun sample(plane: android.media.Image.Plane, x: Int, y: Int): Byte = plane.buffer.get(y * plane.rowStride + x * plane.pixelStride)
        for (y in 0 until height) for (x in 0 until width) nv21[y * width + x] = sample(planes[0], x, y)
        var offset = width * height
        for (y in 0 until height / 2) for (x in 0 until width / 2) { nv21[offset++] = sample(planes[2], x, y); nv21[offset++] = sample(planes[1], x, y) }
        val original = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 55, original)
        val bytes = original.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Unable to decode frame")
        var data = ByteArray(0)
        // Fit the LiveKit RPC payload while preserving as much visual detail as possible.
        for (targetWidth in intArrayOf(320, 256, 192, 160)) {
          val small = Bitmap.createScaledBitmap(decoded, targetWidth, (targetWidth.toFloat() * height / width).toInt().coerceAtLeast(1), true)
          val rotated = Bitmap.createBitmap(small, 0, 0, small.width, small.height, Matrix().apply { postRotate(90f) }, true)
          val output = ByteArrayOutputStream()
          rotated.compress(Bitmap.CompressFormat.JPEG, 40, output)
          data = output.toByteArray()
          if (rotated !== small) rotated.recycle()
          if (small !== decoded) small.recycle()
          if (data.size <= 10000) break
        }
        decoded.recycle()
        if (data.size > 10000) error("Camera frame too large")
        pending.getAndSet(null)?.resolve(Base64.encodeToString(data, Base64.NO_WRAP))
      }
    } catch (_: com.google.ar.core.exceptions.NotYetAvailableException) {
      // Try the next ARCore frame until the request times out.
    } catch (e: Exception) {
      pending.getAndSet(null)?.reject("CAMERA_ERROR", "Camera image unavailable.")
    }
  }
}
