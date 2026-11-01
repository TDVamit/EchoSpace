package com.echospace.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.uimanager.ViewManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Offline wake recognition. No captured audio leaves the device. */
class WakeWordModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  private val executor = Executors.newSingleThreadExecutor()
  private val requested = AtomicBoolean(false)
  private var model: Model? = null
  private var recognizer: Recognizer? = null
  private var speech: SpeechService? = null
  override fun getName() = "WakeWord"

  @ReactMethod
  fun setDeepEnabled(enabled: Boolean, available: Boolean, promise: Promise) {
    context.getSharedPreferences("deep_controls", 0).edit().putBoolean("enabled", enabled).putBoolean("available", available).apply()
    DepthScanActivity.updateDeepControl()
    promise.resolve(null)
  }

  @ReactMethod
  fun setVoiceVolume(volume: Double, promise: Promise) {
    context.getSharedPreferences("deep_controls", 0).edit().putInt("volume", volume.toInt().coerceIn(0, 100)).apply()
    promise.resolve(null)
  }

  private fun emit(event: String, value: String? = null) {
    if (context.hasActiveReactInstance()) context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(event, value)
  }

  private fun closeRecorder() {
    speech?.cancel()
    speech?.shutdown()
    speech = null
    recognizer?.close()
    recognizer = null
  }

  private fun modelDirectory(): File {
    val destination = File(context.filesDir, "vosk-wake-v1")
    val root = File(destination, "vosk-model-small-en-us-0.15")
    if (File(root, "am/final.mdl").isFile && File(root, "graph/Gr.fst").isFile && File(destination, ".complete").isFile) return root
    val staging = File(context.filesDir, "vosk-wake-v1.tmp")
    staging.deleteRecursively()
    staging.mkdirs()
    try {
      ZipInputStream(context.assets.open("vosk-model-small-en-us-0.15.zip")).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          val file = File(staging, entry.name)
          if (!file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) throw IllegalStateException("Invalid model archive")
          if (entry.isDirectory) file.mkdirs() else {
            file.parentFile?.mkdirs()
            file.outputStream().use { zip.copyTo(it) }
          }
          zip.closeEntry()
        }
      }
      File(staging, ".complete").writeText("1")
      destination.deleteRecursively()
      check(staging.renameTo(destination)) { "Unable to install wake model" }
      return root
    } catch (e: Exception) { staging.deleteRecursively(); throw e }
  }

  @ReactMethod
  fun start(promise: Promise) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      promise.reject("MIC_PERMISSION", "Microphone permission is required for the wake phrase."); return
    }
    if (!requested.compareAndSet(false, true)) { promise.resolve(null); return }
    executor.execute {
      try {
        val loaded = model ?: Model(modelDirectory().absolutePath).also { model = it }
        if (!requested.get()) { promise.resolve(null); return@execute }
        val currentRecognizer = Recognizer(loaded, 16000f, "[\"hey deep\",\"hello deep\",\"[unk]\"]")
        recognizer = currentRecognizer
        val currentSpeech = SpeechService(currentRecognizer, 16000f)
        speech = currentSpeech
        currentSpeech.startListening(object : RecognitionListener {
          private fun handle(json: String?) {
            val data = try { JSONObject(json ?: "{}") } catch (_: Exception) { return }
            val text = data.optString("partial", data.optString("text", "")).trim().lowercase()
            if (!Regex("(?:^|\\s)(?:hey|hello)\\s+deep(?:$|\\s)").containsMatchIn(text)) return
            if (requested.compareAndSet(true, false)) executor.execute {
              closeRecorder()
              emit("EchoWakeDetected")
            }
          }
          override fun onPartialResult(hypothesis: String?) = handle(hypothesis)
          override fun onResult(hypothesis: String?) = handle(hypothesis)
          override fun onFinalResult(hypothesis: String?) = handle(hypothesis)
          override fun onError(exception: Exception?) {
            if (requested.compareAndSet(true, false)) executor.execute { closeRecorder(); emit("EchoWakeError", "Wake listening stopped. Please try again.") }
          }
          override fun onTimeout() = Unit
        })
        if (!requested.get()) closeRecorder()
        promise.resolve(null)
      } catch (e: Exception) {
        requested.set(false)
        closeRecorder()
        promise.reject("WAKE_FAILED", "Could not start offline wake listening.", e)
      }
    }
  }

  @ReactMethod
  fun stop(promise: Promise) {
    requested.set(false)
    executor.execute { closeRecorder(); promise.resolve(null) }
  }

  @ReactMethod fun addListener(eventName: String) = Unit
  @ReactMethod fun removeListeners(count: Int) = Unit

  override fun invalidate() {
    requested.set(false)
    executor.execute { closeRecorder(); model?.close(); model = null }
    executor.shutdown()
    super.invalidate()
  }
}

class WakeWordPackage : ReactPackage {
  override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(WakeWordModule(context))
  override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
