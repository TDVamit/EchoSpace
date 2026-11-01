package com.echospace.app

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.livekit.reactnative.LiveKitReactNative
import com.livekit.reactnative.audio.AudioType
import com.oney.WebRTCModule.WebRTCModuleOptions

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          // Packages that cannot be autolinked yet can be added manually here, for example:
          // add(MyReactNativePackage())
          add(ARScannerPackage())
          add(VoiceAuthPackage())
          add(WakeWordPackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    VoiceSounds.load(this)
    LiveKitReactNative.setup(this, AudioType.MediaAudioType())
    WebRTCModuleOptions.getInstance().defaultTrackVolume = 0.45
    loadReactNative(this)
  }
}
