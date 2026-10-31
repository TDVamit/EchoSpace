package com.echospace.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.Image
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

internal object SemanticClasses {
  val labels = arrayOf("door", "chair", "table", "sofa", "bed", "cabinet", "shelf", "person", "backpack", "suitcase", "box", "trash can", "potted plant", "bicycle", "staircase", "step", "fire extinguisher", "wooden door")
}

/** Experimental detector. Camera, depth and coordinate transforms are captured from one AR frame. */
internal class LiveObjectDetector(private val context: Context, private val publish: (Result) -> Unit) {
  data class Detection(val name: String, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float, val distance: Float?)
  data class Result(val detections: List<Detection>, val inferenceMs: Long, val totalMs: Long, val capturedAt: Long, val error: String? = null, val semantic: SemanticAssociation.Matches? = null)
  private data class Candidate(val detection: Detection, val category: Int, val coefficients: FloatArray)
  private data class Plane(val bytes: ByteArray, val rowStride: Int, val pixelStride: Int)
  private data class Snapshot(
    val width: Int, val height: Int, val planes: List<Plane>, val viewToImage: FloatArray,
    val viewToDepth: FloatArray, val imageToView: FloatArray, val imageToDepth: FloatArray,
    val depth: ShortArray?, val depthWidth: Int, val depthHeight: Int, val aspect: Float,
    val capturedAt: Long, val mapPoints: FloatArray, val mapToCamera: FloatArray,
    val focal: FloatArray, val principal: FloatArray,
  )
  private val labels = SemanticClasses.labels
  private val executor = Executors.newSingleThreadExecutor()
  private val busy = AtomicBoolean(false)
  @Volatile private var closed = false
  private var interpreter: Interpreter? = null
  private val input = ByteBuffer.allocateDirect(3 * 640 * 640 * 4).order(ByteOrder.nativeOrder())
  private val boxes = ByteBuffer.allocateDirect(54 * 8400 * 4).order(ByteOrder.nativeOrder())
  private val masks = ByteBuffer.allocateDirect(32 * 160 * 160 * 4).order(ByteOrder.nativeOrder())

  fun submit(frame: Frame, viewWidth: Int, viewHeight: Int, cameraInMap: Pose, mapPoints: FloatArray) {
    if (closed || viewWidth <= 0 || viewHeight <= 0 || !busy.compareAndSet(false, true)) return
    var camera: Image? = null
    var depth: Image? = null
    try {
      camera = frame.acquireCameraImage()
      val view = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
      val viewToImage = FloatArray(6)
      val viewToDepth = FloatArray(6)
      frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, view, Coordinates2d.IMAGE_PIXELS, viewToImage)
      frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, view, Coordinates2d.TEXTURE_NORMALIZED, viewToDepth)
      val imageCorners = floatArrayOf(0f, 0f, camera.width.toFloat(), 0f, 0f, camera.height.toFloat())
      val imageToView = FloatArray(6)
      val imageToDepth = FloatArray(6)
      frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, imageCorners, Coordinates2d.VIEW_NORMALIZED, imageToView)
      frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, imageCorners, Coordinates2d.TEXTURE_NORMALIZED, imageToDepth)
      val mapToCamera = FloatArray(16)
      cameraInMap.inverse().toMatrix(mapToCamera, 0)
      val intrinsics = frame.camera.imageIntrinsics
      val focal = intrinsics.focalLength.copyOf()
      val principal = intrinsics.principalPoint.copyOf()
      val planes = camera.planes.map { p ->
        val b = p.buffer.duplicate(); val bytes = ByteArray(b.remaining()); b.get(bytes)
        Plane(bytes, p.rowStride, p.pixelStride)
      }
      var depthData: ShortArray? = null
      var dw = 0; var dh = 0
      try {
        depth = frame.acquireDepthImage16Bits()
        dw = depth.width; dh = depth.height
        val plane = depth.planes[0]; val b = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val values = ShortArray(dw * dh)
        for (y in 0 until dh) for (x in 0 until dw) {
          val i = y * plane.rowStride + x * plane.pixelStride
          if (i + 1 < b.limit()) values[y * dw + x] = b.getShort(i)
        }
        depthData = values
      } catch (_: Exception) { /* Depth can temporarily be unavailable. */ }
      val snap = Snapshot(camera.width, camera.height, planes, viewToImage, viewToDepth, imageToView, imageToDepth,
        depthData, dw, dh, viewWidth.toFloat() / viewHeight, SystemClock.elapsedRealtime(), mapPoints, mapToCamera, focal, principal)
      executor.execute {
        try { if (!closed) publish(infer(snap)) }
        catch (e: Exception) { Log.e("EchoSpaceDetector", "Inference failed", e); if (!closed) publish(Result(emptyList(), 0, 0, snap.capturedAt, e.message ?: e.javaClass.simpleName)) }
        finally { busy.set(false) }
      }
    } catch (_: com.google.ar.core.exceptions.NotYetAvailableException) { busy.set(false) }
    catch (e: Exception) { busy.set(false); Log.w("EchoSpaceDetector", "Frame unavailable", e) }
    finally { depth?.close(); camera?.close() }
  }

  private fun affine(a: FloatArray, x: Float, y: Float, axis: Int): Float = a[axis] + x * (a[2 + axis] - a[axis]) + y * (a[4 + axis] - a[axis])
  private fun sample(p: Plane, x: Int, y: Int): Int { val i = y * p.rowStride + x * p.pixelStride; return if (i in p.bytes.indices) p.bytes[i].toInt() and 255 else 128 }
  private fun infer(s: Snapshot): Result {
    val start = SystemClock.elapsedRealtime()
    val runtime = interpreter ?: run {
      val data = context.assets.open("yoloe-26s-seg.tflite").use { it.readBytes() }
      val buffer = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()); buffer.put(data); buffer.rewind()
      Interpreter(buffer, Interpreter.Options().setNumThreads(4)).also { instance ->
        require(instance.getInputTensor(0).shape().contentEquals(intArrayOf(1, 3, 640, 640))) { "Unexpected model input" }
        require(instance.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 54, 8400))) { "Unexpected model output" }
        interpreter = instance
      }
    }
    // Letterbox the visible camera view. ARCore supplies the display-to-image transform.
    val contentW = if (s.aspect >= 1f) 640f else 640f * s.aspect
    val contentH = if (s.aspect >= 1f) 640f / s.aspect else 640f
    val padX = (640f - contentW) / 2f; val padY = (640f - contentH) / 2f
    val pixels = IntArray(640 * 640)
    for (y in 0 until 640) for (x in 0 until 640) {
      val u = (x - padX) / contentW; val v = (y - padY) / contentH
      if (u !in 0f..1f || v !in 0f..1f) { pixels[y * 640 + x] = 0x727272; continue }
      val ix = affine(s.viewToImage, u, v, 0).toInt().coerceIn(0, s.width - 1)
      val iy = affine(s.viewToImage, u, v, 1).toInt().coerceIn(0, s.height - 1)
      val yy = sample(s.planes[0], ix, iy); val uu = sample(s.planes[1], ix / 2, iy / 2) - 128; val vv = sample(s.planes[2], ix / 2, iy / 2) - 128
      val c = max(0, yy - 16)
      val r = ((298 * c + 409 * vv + 128) shr 8).coerceIn(0, 255)
      val g = ((298 * c - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255)
      val b = ((298 * c + 516 * uu + 128) shr 8).coerceIn(0, 255)
      pixels[y * 640 + x] = (r shl 16) or (g shl 8) or b
    }
    input.rewind()
    for (shift in intArrayOf(16, 8, 0)) for (pixel in pixels) input.putFloat(((pixel shr shift) and 255) / 255f)
    input.rewind(); boxes.rewind(); masks.rewind()
    val beforeInference = SystemClock.elapsedRealtime()
    runtime.runForMultipleInputsOutputs(arrayOf(input), mutableMapOf<Int, Any>(0 to boxes, 1 to masks))
    val afterInference = SystemClock.elapsedRealtime()
    boxes.rewind()
    val floats = boxes.asFloatBuffer()
    fun value(channel: Int, i: Int) = floats.get(channel * 8400 + i)
    val candidates = ArrayList<Candidate>()
    for (i in 0 until 8400) {
      var score = 0f; var cls = -1
      for (c in labels.indices) { val v = value(4 + c, i); if (v > score) { score = v; cls = c } }
      if (score < 0.35f || cls < 0) continue
      val cx = value(0, i) * 640; val cy = value(1, i) * 640; val w = value(2, i) * 640; val h = value(3, i) * 640
      val l = ((cx - w / 2 - padX) / contentW).coerceIn(0f, 1f); val t = ((cy - h / 2 - padY) / contentH).coerceIn(0f, 1f)
      val r = ((cx + w / 2 - padX) / contentW).coerceIn(0f, 1f); val b = ((cy + h / 2 - padY) / contentH).coerceIn(0f, 1f)
      if (r <= l || b <= t) continue
      val tx = affine(s.viewToDepth, (l + r) / 2, (t + b) / 2, 0); val ty = affine(s.viewToDepth, (l + r) / 2, (t + b) / 2, 1)
      val depth = if (s.depth != null && tx in 0f..1f && ty in 0f..1f) {
        val dx = (tx * s.depthWidth).toInt().coerceIn(0, s.depthWidth - 1); val dy = (ty * s.depthHeight).toInt().coerceIn(0, s.depthHeight - 1)
        val mm = s.depth[dy * s.depthWidth + dx].toInt() and 0xffff
        if (mm in 180..8000) mm / 1000f else null
      } else null
      val coefficients = FloatArray(32) { channel -> value(4 + labels.size + channel, i) }
      candidates.add(Candidate(Detection(labels[cls], score, l, t, r, b, depth), cls, coefficients))
    }
    val selected = ArrayList<Candidate>()
    for (candidate in candidates.sortedByDescending { it.detection.score }) {
      if (selected.size >= 15) break
      val d = candidate.detection
      if (selected.any { it.detection.name == d.name && iou(it.detection, d) > 0.45f }) continue
      selected.add(candidate)
    }
    masks.rewind()
    val semantic = s.depth?.let { depth ->
      val geometry = SemanticAssociation.Frame(
        s.mapPoints, s.mapToCamera, s.focal, s.principal, s.width, s.height,
        s.imageToView, s.imageToDepth, depth, s.depthWidth, s.depthHeight,
        contentW, contentH, padX, padY,
      )
      val instances = selected.map { candidate ->
        val d = candidate.detection
        SemanticAssociation.Instance(candidate.category, d.score, d.left, d.top, d.right, d.bottom, candidate.coefficients)
      }
      SemanticAssociation.associate(geometry, instances, masks.asFloatBuffer())
    }
    return Result(selected.map { it.detection }, afterInference - beforeInference,
      SystemClock.elapsedRealtime() - start, s.capturedAt, semantic = semantic)
  }
  private fun iou(a: Detection, b: Detection): Float {
    val overlap = max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
    val union = (a.right-a.left)*(a.bottom-a.top) + (b.right-b.left)*(b.bottom-b.top) - overlap
    return if (union > 0f) overlap / union else 0f
  }
  fun close() { closed = true; executor.execute { interpreter?.close(); interpreter = null }; executor.shutdown() }
}

internal class DetectionOverlay(context: Context) : View(context) {
  @Volatile var result: LiveObjectDetector.Result? = null
  @Volatile var cloudMode = false
  // Screen-normalized x, y and category, projected from confirmed map points.
  @Volatile var cloudPoints = FloatArray(0)
  private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(151, 230, 177); style = Paint.Style.STROKE; strokeWidth = 4f }
  private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; setShadowLayer(5f, 1f, 1f, Color.BLACK) }
  private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 13, 31, 29) }
  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val current = result ?: return
    if (SystemClock.elapsedRealtime() - current.capturedAt > 2000) return
    val points = cloudPoints
    for (d in current.detections) {
      val left = d.left * width; val top = d.top * height
      val right = d.right * width; val bottom = d.bottom * height
      val category = SemanticClasses.labels.indexOf(d.name)
      if (cloudMode) {
        // Connect nearby confirmed points only inside this detection's box.
        // A small screen-space grid keeps the overlay bounded and avoids long lines.
        val cells = HashMap<Long, Pair<Float, Float>>()
        linePaint.color = Color.HSVToColor(175, floatArrayOf((category * 61f) % 360f, .65f, 1f))
        var offset = 0
        while (offset + 2 < points.size) {
          val x = points[offset] * width; val y = points[offset + 1] * height
          if (points[offset + 2].toInt() == category && x in left..right && y in top..bottom) {
            val cellX = (x / 36f).toInt(); val cellY = (y / 36f).toInt()
            val key = (cellX.toLong() shl 32) or (cellY.toLong() and 0xffffffffL)
            val previous = cells[key]
            if (previous != null) canvas.drawLine(previous.first, previous.second, x, y, linePaint)
            cells[key] = Pair(x, y)
          }
          offset += 3
        }
        boxPaint.color = Color.HSVToColor(floatArrayOf((category * 61f) % 360f, .65f, 1f))
        val title = d.name.uppercase()
        val textWidth = textPaint.measureText(title)
        val labelTop = max(0f, top - 40f)
        canvas.drawRoundRect(left, labelTop, min(width.toFloat(), left + textWidth + 18f), labelTop + 38f, 5f, 5f, labelPaint)
        canvas.drawText(title, left + 9f, labelTop + 28f, textPaint)
      } else {
        boxPaint.color = Color.rgb(151, 230, 177)
        val distance = d.distance?.let { " · %.1f m depth".format(it) } ?: " · depth unavailable"
        canvas.drawText("${d.name} ${(d.score * 100).toInt()}%$distance", left, max(30f, top - 8f), textPaint)
      }
      canvas.drawRect(left, top, right, bottom, boxPaint)
    }
  }
}
