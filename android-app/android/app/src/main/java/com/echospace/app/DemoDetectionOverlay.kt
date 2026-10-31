package com.echospace.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Renders live detector boxes and labels into the camera demo recording. */
internal class DemoDetectionOverlay {
  private var program = 0
  private var texture = 0
  private var lastResult: LiveObjectDetector.Result? = null
  private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
    put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)); position(0)
  }
  fun draw(result: LiveObjectDetector.Result?) {
    if (result == null || SystemClock.elapsedRealtime() - result.capturedAt > 2000 || result.detections.isEmpty()) return
    if (program == 0) init()
    if (lastResult !== result) {
      val bitmap = Bitmap.createBitmap(DemoRecorder.WIDTH, DemoRecorder.HEIGHT, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(142, 228, 171); style = Paint.Style.STROKE; strokeWidth = 3f }
      val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 21f; setShadowLayer(4f, 1f, 1f, Color.BLACK) }
      for (d in result.detections) {
        val left = d.left * DemoRecorder.WIDTH; val top = d.top * DemoRecorder.HEIGHT
        canvas.drawRect(left, top, d.right * DemoRecorder.WIDTH, d.bottom * DemoRecorder.HEIGHT, line)
        canvas.drawText("${d.name} ${(d.score * 100).toInt()}%", left, maxOf(23f, top - 5f), text)
      }
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
      GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
      bitmap.recycle(); lastResult = result
    }
    GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glEnable(GLES20.GL_BLEND)
    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    GLES20.glUseProgram(program)
    val position = GLES20.glGetAttribLocation(program, "aPosition")
    val uv = GLES20.glGetAttribLocation(program, "aUv")
    quad.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(position)
    quad.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(uv)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(uv)
    GLES20.glDisable(GLES20.GL_BLEND); GLES20.glEnable(GLES20.GL_DEPTH_TEST)
  }
  private fun init() {
    fun shader(type: Int, code: String): Int = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, code); GLES20.glCompileShader(it) }
    val v = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPosition; attribute vec2 aUv; varying vec2 vUv; void main(){vUv=aUv;gl_Position=vec4(aPosition,0.,1.);}")
    val f = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 vUv; uniform sampler2D uTexture; void main(){gl_FragColor=texture2D(uTexture,vUv);}")
    program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, v); GLES20.glAttachShader(program, f); GLES20.glLinkProgram(program)
    GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
    val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); texture = ids[0]
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
  }
  fun release() {
    if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    if (program != 0) GLES20.glDeleteProgram(program)
    texture = 0; program = 0; lastResult = null
  }
}
