# Android prototype

React Native provides the home screen and settings. The live scanner is a native Kotlin activity using ARCore, LiteRT, OpenGL ES, and Android `AudioTrack`. The model is included under `android/app/src/main/assets/` for the demo.

## Prerequisites

- Node.js 22.11 or newer and npm.
- JDK 17 and Android SDK/NDK versions requested by the Gradle project. Set `ANDROID_HOME` or create a local `android/local.properties`.
- An Android device with Google Play Services for AR and ARCore Depth support. A Galaxy S23 Ultra has been used for early tests.
- Stereo headphones for spatial audio testing.
- A Firebase Android app registered for `com.echospace.app`. Download its `google-services.json` into `android/app/`; this local configuration file is intentionally ignored by Git.

Voice integration is experimental. A Firebase-authenticated LiveKit/Vertex service must be configured separately. After sign-in, Vosk listens on the phone for “Hey Deep” or “Hello Deep”; no audio is sent to the voice service before the wake phrase. Pause after the phrase until Deep responds, then speak. The conversation disconnects after roughly 30 seconds of silence and returns to local wake listening. Guest mode disables Deep. The wake phrase and its interaction with camera scanning and audio require physical-device testing.

## Build a standalone debug APK

From this directory:

```sh
npm ci
sh scripts/prepare-wake-model.sh
cd android
./gradlew :app:assembleDebug -PstandaloneDebug -PreactNativeArchitectures=arm64-v8a
```

The debug-signed APK is at `android/app/build/outputs/apk/debug/app-debug.apk`. `-PstandaloneDebug` includes the JavaScript bundle, so the intended test build does not require Metro. A developer's default Android debug keystore is generated locally; it is not a production signing key. The release build configuration currently also uses debug signing and is **not suitable for distribution**.

## Use the prototype

Start a scan and slowly sweep the phone through the space. Detection is enabled by default. Switch among camera, points, and the elevated 3D view in the scanner; the last marks the phone's position. Adjust sound range, vertical zone, density, volume, and blind mode on the home screen. The optional floor filter may also quiet low obstacles.

The experimental **Record 3 views** control attempts to save three silent MP4s to `Movies/EchoSpace` and a separate stereo WAV to `Music/EchoSpace`. On the reference device these recording paths, sustained performance, and synchronization still need verification. The videos are 480 × 848 at up to 12 fps; audio may need alignment in an editor. If the phone cannot allocate three simultaneous encoders, recording can fail while scanning continues.

## Implementation and limitations

- Depth points are held in memory for the current scan. A local map anchor and voxel grid reduce duplication; there is no saved-room relocalization.
- The detector uses a 0.35 confidence threshold. A segmentation mask, depth agreement, and repeated observations associate an object category with map points. It does not track persistent object identities.
- Raw-depth map correction is conservative: points are removed only after repeated high-confidence observations contradict them. Occlusion and sparse depth can leave stale points.
- Audio is an experimental stereo mix. It uses shared sound generators and point contributions, not one independent hardware audio source per point or head-tracked binaural audio.
- Low/overhead geometry, stereo direction, detection accuracy, floor filtering, correction, recording and performance need supervised physical-device tests. See [testing](../docs/testing.md).

## Model and licensing

The supplied model's embedded metadata identifies Ultralytics and AGPL-3.0. See the [root README](../README.md#model-and-attribution) before distributing or reusing it. No production release signing configuration is included.
