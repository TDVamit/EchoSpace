package com.echospace.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Nonblocking audio-thread tap, written as a separate stereo PCM WAV. */
internal class DemoAudioRecorder(private val context: Context, private val onSaved: (String) -> Unit) {
  private val queue = ArrayBlockingQueue<ShortArray>(128)
  @Volatile private var running = true
  @Volatile private var dropped = 0
  private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
  private val file = File(context.cacheDir, "echospace-$stamp-audio.wav")
  private val writer = Thread(::write, "EchoSpace-DemoAudio").apply { start() }
  fun offer(samples: ShortArray) { if (running && !queue.offer(samples.copyOf())) dropped++ }
  fun finish() { running = false }
  private fun write() {
    try {
      RandomAccessFile(file, "rw").use { out ->
        out.setLength(0); out.write(ByteArray(44)); var bytes = 0L
        while (running || queue.isNotEmpty()) {
          val samples = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
          val data = ByteArray(samples.size * 2)
          for (i in samples.indices) { val value = samples[i].toInt(); data[2*i] = value.toByte(); data[2*i+1] = (value shr 8).toByte() }
          out.write(data); bytes += data.size
        }
        require(bytes <= 0xffffffffL - 36) { "Audio recording too long" }
        fun little(value: Long, length: Int) { repeat(length) { shift -> out.write((value shr (8 * shift)).toInt() and 255) } }
        out.seek(0); out.writeBytes("RIFF"); little(bytes + 36, 4); out.writeBytes("WAVEfmt "); little(16, 4)
        little(1, 2); little(2, 2); little(44100, 4); little(44100L * 4, 4); little(4, 2); little(16, 2)
        out.writeBytes("data"); little(bytes, 4)
      }
      if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
          put(MediaStore.Audio.Media.DISPLAY_NAME, "EchoSpace-$stamp-audio.wav")
          put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
          put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/EchoSpace")
          put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create audio")
        try {
          resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("Could not write audio")
          values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0); resolver.update(uri, values, null, null)
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
      } else {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: error("Music folder unavailable")
        file.copyTo(File(dir, "EchoSpace-$stamp-audio.wav"), overwrite = false)
      }
      file.delete()
      onSaved(if (dropped > 0) "Stereo audio saved with $dropped dropped blocks" else "Stereo audio saved to Music/EchoSpace")
    } catch (e: Exception) {
      android.util.Log.e("EchoSpaceDemo", "Audio save failed; cache retained at $file", e)
      onSaved("Stereo audio could not be saved")
    }
  }
}
