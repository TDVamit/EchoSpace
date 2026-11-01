package com.echospace.app

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.uimanager.ViewManager
import java.util.concurrent.Executors

class ARScannerModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  private val previewExecutor = Executors.newSingleThreadExecutor()
  override fun getName() = "ARScanner"

  @ReactMethod
  fun readVoiceScene(promise: Promise) { promise.resolve(VoiceScene.read()) }

  @ReactMethod
  fun isLiveScanActive(promise: Promise) { promise.resolve(DepthScanActivity.isVoiceScanActive()) }

  @ReactMethod
  fun stopScan(promise: Promise) {
    Handler(Looper.getMainLooper()).post {
      try { promise.resolve(DepthScanActivity.stopVoiceScan()) }
      catch (e: Exception) { promise.reject("STOP_SCAN_FAILED", "Unable to stop the live scan.", e) }
    }
  }

  @ReactMethod
  fun captureVoiceImage(promise: Promise) { VoiceCamera.request(promise) }

  @ReactMethod
  fun previewVoiceSound(category: String, sound: String, promise: Promise) {
    previewExecutor.execute {
      try { promise.resolve(VoiceSounds.preview(category, sound)) }
      catch (e: Exception) { promise.reject("PREVIEW_FAILED", "Sound preview failed.", e) }
    }
  }

  @ReactMethod
  fun confirmVoiceSound(previewId: String, promise: Promise) { promise.resolve(VoiceSounds.confirm(context, previewId)) }

  override fun invalidate() { previewExecutor.shutdownNow(); super.invalidate() }

  @ReactMethod
  fun setLiveVoiceSetting(name: String, value: Double, promise: Promise) {
    promise.resolve(DepthScanActivity.setVoiceSetting(name, value))
  }

  @ReactMethod
  fun startScan(options: ReadableMap, promise: Promise) {
    val activity = context.currentActivity
    if (activity == null) {
      promise.reject("NO_ACTIVITY", "The Android screen is not ready")
      return
    }
    val intent = Intent(activity, DepthScanActivity::class.java)
      .putExtra("density", if (options.hasKey("density")) options.getInt("density") else 55)
      .putExtra("range", if (options.hasKey("range")) options.getInt("range") else 1)
      .putExtra("blindMode", options.hasKey("blindMode") && options.getBoolean("blindMode"))
      .putExtra("heightBelow", if (options.hasKey("heightBelow")) options.getDouble("heightBelow").toFloat() else 1.5f)
      .putExtra("heightAbove", if (options.hasKey("heightAbove")) options.getDouble("heightAbove").toFloat() else 0.6f)
      .putExtra("reduceFloor", options.hasKey("reduceFloor") && options.getBoolean("reduceFloor"))
      .putExtra("audioEnabled", !options.hasKey("audioEnabled") || options.getBoolean("audioEnabled"))
      .putExtra("audioVoices", if (options.hasKey("audioVoices")) options.getInt("audioVoices") else 128)
      .putExtra("audioVolume", if (options.hasKey("audioVolume")) options.getDouble("audioVolume").toFloat() else 0.35f)
    activity.startActivity(intent)
    promise.resolve(null)
  }
}

class ARScannerPackage : ReactPackage {
  override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(ARScannerModule(context))
  override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
