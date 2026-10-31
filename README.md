# EchoSpace

EchoSpace explores whether a phone can make nearby surroundings perceptible through sound. The Android prototype combines ARCore depth and motion tracking, an on-device object detector, and stereo point-cloud sonification. A separate browser simulator explores the same idea with a synthetic room.

**Status:** hackathon prototype, not a navigation aid. Depth, object labels, audio direction, and map correction can be inaccurate or unavailable. Do not rely on it to avoid obstacles or replace a cane, guide dog, or other mobility aid.

## What is implemented

- **Live Android scanning:** ARCore builds a session-only point map from camera depth and motion tracking. The app offers camera, colored point-cloud, and third-person views.
- **Object-aware sound:** a YOLOE-26s segmentation model runs locally with LiteRT. Detections at or above 0.35 can label matching depth points; confirmed categories have different default sounds. This is experimental and not every detection produces a tagged point.
- **Stereo point audio:** nearby mapped points contribute to a bounded mixer. Distance uses a configurable horizontal range and vertical zone relative to the phone. Direction follows the phone, not a tracked head position.
- **Map correction:** repeated, confident raw-depth observations can remove visible points that are contradicted by new observations. Off-camera or occluded points are retained; moved objects can still leave stale points.
- **Demo capture:** an experimental recorder attempts three separate views of the same scan plus a separate stereo WAV. The simultaneous encoder path and synchronization require device verification.
- **Browser simulator:** a Three.js room for testing point-cloud audio and simulated object assignments. It does not use a real camera or detector.

The Android app is designed for devices supporting [ARCore Depth](https://developers.google.com/ar/develop/depth). It uses Google ARCore and LiteRT locally. The proposed Gemini agent, cloud services, persistent room relocalization, and custom Android sound rules are **not implemented**.

## Start here

| Component | Purpose | Instructions |
| --- | --- | --- |
| `android-app/` | React Native controls and native Kotlin ARCore, detector, and audio pipeline | [Android setup](android-app/README.md) |
| `poc/` | Desktop browser simulation | [Run the simulator](poc/README.md) |
| `docs/` | Architecture, verification status, and product vision | [Architecture](docs/architecture.md) · [Testing](docs/testing.md) · [Product vision](docs/product-vision.md) · [Third-party notices](docs/third-party.md) |

## Model and attribution

The on-device model was supplied by a teammate as a YOLOE-26s segmentation LiteRT export. Its embedded metadata identifies Ultralytics as the author and **AGPL-3.0** as its license; see [Ultralytics licensing](https://www.ultralytics.com/license). The model and its training/export provenance should be reviewed before redistribution or use outside this prototype. The related detector work is at [prateekgarg08/object-detection](https://github.com/prateekgarg08/object-detection).

An absolute local dataset path was redacted from the model's appended metadata; model flatbuffer bytes and license metadata were retained.

The repository does not contain a production signing key, API keys, local recordings, built APKs, or user scans. A public release needs its own signing and dependency/license review.
