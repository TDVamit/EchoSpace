# Testing and known gaps

## Local checks

```sh
# Browser simulator
cd poc
npm ci
npm run build

# Android JavaScript, from android-app/
npm ci
npx tsc --noEmit
npm test -- --runInBand
```

For the Android debug build, use the command in [android-app/README.md](../android-app/README.md). `android-app/verification/` contains small JVM checks for geometry, selection, object association, correction, and audio math; they are not Android instrumentation tests.

## Physical-device checks still needed

- Scan at different phone heights and orientations. Compare left/right audio, horizontal distance fades, overhead cues, and the optional floor filter with actual objects. The filter can suppress low obstacles.
- Move a scanned chair while it is visible, then while it is out of view. Check for stale points and audio; block an unmoved object and make sure it is not mistakenly erased. Raw depth and confidence can be sparse.
- Check object masks and colored points against real boundaries at different distances and lighting. Confirm depth association and audio for multiple classes and overlapping objects.
- Record all three demo views from one scan. Verify the saved videos and separate stereo WAV, playback, orientation, matching timing, storage failure handling, audio continuity, and behavior when the activity is paused. The video streams are 480 × 848 at up to 12 fps; audio is separate and may require manual alignment.
- Run extended scans with detection, audio, correction, and recording enabled. Measure frame rate, encoder limits, battery, heat, audio underruns, and responsiveness. Check tracking loss and recovery.
- Check TalkBack, large text, contrast, permissions, and controls in camera, point, third-person, and blind modes.
- Sign into the demo account, start Deep, grant or deny microphone and location permissions, and check the greeting and portrait orientation prompt. Check reconnect and sign-out.
- While scanning, ask about visible objects and the right/left sound source, adjust hearing distance and volume, and verify live audio actually changes. Camera snapshot orientation, image quality, direction, depth, stale data and object detection must be checked on the S23 Ultra.
- Preview and reject or confirm different sounds; check category-wide persistence across restarts, unsupported classes, expired previews, overlapping objects, latency and crackles. Confirm voice speech and scanner audio remain stereo and do not interrupt each other.
- Check that sound preview plays through the scanner output with the background briefly reduced, then returns to its original level. Verify sound-off and zero-volume errors, repeated previews, leaving the scan during playback and no cut-out after a confirmed change.
- With Bluetooth headphones, compare left/right spatial cues before, during, and after a Deep conversation. Verify the phone or headset microphone still captures speech in media mode, Deep's independent voice level, and the Stop Deep / Enable Deep controls on the scanner and home screen.
- Test “Hey Deep” and “Hello Deep” in quiet and noisy rooms, with headphones and during scanning. Verify no cloud session starts before a wake phrase, wake-only listening resumes after 30 seconds of inactivity, false activations, permissions and app background behavior. Say the phrase, pause for Deep, then give the command. OpenAI realtime fallback is configured but has not been forced and verified.
- While the native scan is open, wake Deep and confirm it connects without leaving the scan. Ask it to stop the live scan, verify the home screen appears and AR/audio/recording resources close, then start a new scan and repeat. Also check microphone permissions, app backgrounding and returning to the app.

The S23 Ultra has been used for early prototype checks, but these scenarios and the current recording path have not been comprehensively validated. Results from the browser simulator, synthetic checks, or a successful build do not establish safe real-world navigation.
