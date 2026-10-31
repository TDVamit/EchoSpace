# Browser simulator

The browser proof of concept uses a synthetic Three.js room to explore point-cloud sound. Recognition is simulated; it does not run the Android camera or object-detection model.

## Run

```sh
cd poc
npm ci
npm run dev
```

Open the local URL printed by Vite in a WebGL-capable desktop browser and use stereo headphones. Choose **Start scan** to capture the visible scene or **Load full scan** to preload it. Start audio, enter the room, and use WASD or arrow keys to move and the mouse to turn. Escape releases the pointer.

The sidebar includes sound density, hearing range, object sound assignments, a top view, and blind mode. It is a controlled simulation; its audio performance and localization do not establish how the Android app behaves in a real room.

```sh
npm run build
npm run preview
```

The simulator specification is available in [public/POC_SPEC.md](public/POC_SPEC.md). The current Android implementation is documented in [android-app/](../android-app/README.md).
