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

The S23 Ultra has been used for early prototype checks, but these scenarios and the current recording path have not been comprehensively validated. Results from the browser simulator, synthetic checks, or a successful build do not establish safe real-world navigation.
