package com.echospace.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Three synchronized, silent video tracks from the same GL/AR frame. GL thread only. */
internal class DemoRecorder(private val context: Context) {
  companion object { const val WIDTH = 480; const val HEIGHT = 848; private const val FPS = 12 }
  private data class Stream(val name: String, val file: File, val codec: MediaCodec, val input: Surface,
    val muxer: MediaMuxer, val eglSurface: EGLSurface, var track: Int = -1, var started: Boolean = false, var eos: Boolean = false)
  private val streams = ArrayList<Stream>()
  private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var encoderContext: EGLContext = EGL14.EGL_NO_CONTEXT
  private var previousContext: EGLContext = EGL14.EGL_NO_CONTEXT
  private var previousDraw: EGLSurface = EGL14.EGL_NO_SURFACE
  private var previousRead: EGLSurface = EGL14.EGL_NO_SURFACE
  private var lastFrame = 0L
  private var firstFrame = 0L
  private val bufferInfo = MediaCodec.BufferInfo()
  private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

  fun start() {
    display = EGL14.eglGetCurrentDisplay()
    previousContext = EGL14.eglGetCurrentContext()
    check(display != EGL14.EGL_NO_DISPLAY && previousContext != EGL14.EGL_NO_CONTEXT) { "OpenGL is not ready" }
    val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
    val attrs = intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
      EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE)
    check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "Recording graphics unavailable" }
    val config = configs[0]!!
    encoderContext = EGL14.eglCreateContext(display, config, previousContext, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
    check(encoderContext != EGL14.EGL_NO_CONTEXT) { "Recording context unavailable" }
    try {
      for (name in listOf("camera", "point-cloud", "third-person")) {
        val file = File(context.cacheDir, "echospace-$stamp-$name.mp4")
        val format = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT).apply {
          setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
          setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
          setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
          setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType("video/avc")
        var input: Surface? = null
        var muxer: MediaMuxer? = null
        var surface = EGL14.EGL_NO_SURFACE
        try {
          codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
          input = codec.createInputSurface(); codec.start()
          muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
          surface = EGL14.eglCreateWindowSurface(display, config, input, intArrayOf(EGL14.EGL_NONE), 0)
          check(surface != EGL14.EGL_NO_SURFACE) { "Recording surface unavailable" }
          streams.add(Stream(name, file, codec, input, muxer, surface))
        } catch (e: Exception) {
          if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
          try { muxer?.release() } catch (_: Exception) {}
          try { input?.release() } catch (_: Exception) {}
          try { codec.stop() } catch (_: Exception) {}
          codec.release(); file.delete(); throw e
        }
      }
    } catch (e: Exception) { abort(); throw e }
  }

  fun frame(timestamp: Long, render: (Int) -> Unit): Boolean {
    if (timestamp <= 0 || (lastFrame != 0L && timestamp - lastFrame < 1_000_000_000L / FPS)) return false
    if (firstFrame == 0L) firstFrame = timestamp
    lastFrame = timestamp
    previousContext = EGL14.eglGetCurrentContext()
    previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
    previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
    try {
      for (i in streams.indices) {
        val stream = streams[i]
        drain(stream, false)
        check(EGL14.eglMakeCurrent(display, stream.eglSurface, stream.eglSurface, encoderContext)) { "Recording graphics stopped" }
        android.opengl.GLES20.glViewport(0, 0, WIDTH, HEIGHT)
        render(i)
        EGLExt.eglPresentationTimeANDROID(display, stream.eglSurface, timestamp - firstFrame)
        check(EGL14.eglSwapBuffers(display, stream.eglSurface)) { "Recording frame failed" }
      }
    } finally {
      EGL14.eglMakeCurrent(display, previousDraw, previousRead, previousContext)
    }
    return true
  }

  private fun drain(stream: Stream, final: Boolean) {
    var waits = 0
    while (true) {
      val index = stream.codec.dequeueOutputBuffer(bufferInfo, if (final) 10_000 else 0)
      when {
        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!final || ++waits >= 100) return
        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          check(!stream.started) { "Video format changed twice" }
          stream.track = stream.muxer.addTrack(stream.codec.outputFormat)
          stream.muxer.start(); stream.started = true
        }
        index >= 0 -> {
          val data = stream.codec.getOutputBuffer(index)
          if (data != null && bufferInfo.size > 0 && stream.started && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
            data.position(bufferInfo.offset); data.limit(bufferInfo.offset + bufferInfo.size)
            stream.muxer.writeSampleData(stream.track, data, bufferInfo)
          }
          val ended = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
          stream.codec.releaseOutputBuffer(index, false)
          if (ended) { stream.eos = true; return }
        }
      }
    }
  }

  /** Finalizes on GL thread, then saves completed files asynchronously to Movies/EchoSpace. */
  fun stop(onSaved: (String) -> Unit) {
    val completed = ArrayList<Pair<String, File>>()
    for (stream in streams) {
      try {
        stream.codec.signalEndOfInputStream(); drain(stream, true)
        if (stream.started && stream.eos) { stream.muxer.stop(); completed.add(stream.name to stream.file) }
      } catch (e: Exception) { android.util.Log.e("EchoSpaceDemo", "Video finalize failed", e) }
      finally {
        try { stream.muxer.release() } catch (_: Exception) {}
        try { stream.codec.stop() } catch (_: Exception) {}
        try { stream.codec.release() } catch (_: Exception) {}
        try { stream.input.release() } catch (_: Exception) {}
        EGL14.eglDestroySurface(display, stream.eglSurface)
      }
    }
    streams.clear()
    if (encoderContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, encoderContext)
    encoderContext = EGL14.EGL_NO_CONTEXT
    Thread {
      var saved = 0
      for ((name, file) in completed) {
        try {
          if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
              put(MediaStore.Video.Media.DISPLAY_NAME, "EchoSpace-$stamp-$name.mp4")
              put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
              put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/EchoSpace")
              put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create video")
            try {
              resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("Could not write video")
              values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); resolver.update(uri, values, null, null)
            } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
          } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: error("Movies folder unavailable")
            file.copyTo(File(dir, "EchoSpace-$stamp-$name.mp4"), overwrite = false)
          }
          saved++; file.delete()
        } catch (e: Exception) { android.util.Log.e("EchoSpaceDemo", "Save failed; cache retained at $file", e) }
      }
      onSaved(if (saved == 3) "Saved 3 videos to Movies/EchoSpace" else "Saved $saved of 3 videos; check device storage")
    }.start()
  }

  fun abort() {
    for (stream in streams) {
      try { stream.codec.stop() } catch (_: Exception) {}
      try { stream.codec.release() } catch (_: Exception) {}
      try { stream.muxer.release() } catch (_: Exception) {}
      try { stream.input.release() } catch (_: Exception) {}
      try { EGL14.eglDestroySurface(display, stream.eglSurface) } catch (_: Exception) {}
      stream.file.delete()
    }
    streams.clear()
    if (encoderContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, encoderContext)
    encoderContext = EGL14.EGL_NO_CONTEXT
  }
}
