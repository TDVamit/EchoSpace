# EchoSpace POC — Spatial sound simulator

Status: implementation specification. Desktop browser POC; simulated environment and object recognition.
Date: 19 October 2026.

## Goal

Test whether a dense sound field derived from 3D surface points conveys the arrangement of walls and objects. Move through a synthetic indoor map, scan only visible surfaces or preload the whole map, and adjust audio density and category sounds while moving.

## Scope and stack

Implement in `poc/` using React, TypeScript, Vite, Three.js and Web Audio. No camera, Android runtime, actual YOLO inference, backend, voice agent, Google account, or cloud service is needed at this stage. The Android product and Google integration roadmap remain in PRODUCT_SPEC.md.

## World

A furnished primary room connects through an open doorway to a long corridor and a second room. Include walls, floors, ceiling, doors, tables, chairs, sofa, plant, cabinet and an explicitly simulated hazard. Geometry has stable object IDs and category metadata. Recognition is deterministic ground-truth lookup, clearly labeled simulated. No recognition success rate is claimed.

## Movement

Mouse look using pointer lock; WASD and arrow-key movement; Shift to move faster; Escape releases the mouse. An explicit Enter Room button captures the pointer after user interaction. UI fields do not trigger movement. Collisions prevent walking through solid objects or outside the walkable footprint. Player has fixed standing eye height. No jumping is needed. Reset position returns to the entrance without clearing the map.

## Mapping modes

- New space: begin with zero scanned points; Start Scan continuously emits rays within camera field of view at a bounded update rate. Only first-hit visible surfaces are captured; hidden objects and surfaces behind walls remain unscanned.
- Fully scanned: preload surface points across the entire authored map, including unseen rooms. Audio remains range limited. Label this synthetic complete map, not a camera result.
- Pause Scan freezes acquisition but retains geometry and audio. Clear Scan clears scanned points and simulated detections. Loading a different mode explicitly resets map state.
- Deduplicate scan hits using stable surface samples/voxels; a stationary scan eventually stops adding duplicate points. Surface normals support occlusion and display.

## Views

Large 3D viewport with compact sidebar, as selected by the user. Scene, Overlay, and Points views; point view hides solids but retains clear scanned geometry. Point colours inherit category colours. A small floor plan shows player position, facing and map footprint. Crosshair identifies the first visible object and approximate distance. A visible screen hint explains mouse capture and Escape.

## Direct point sound

Every selected audio point has an individual looping sound source, individual distance attenuation, and a 3D HRTF panner relative to the player's eye position and orientation. A category rule sets waveform/noise texture, base frequency, pulse and gain. Rule changes crossfade/restart affected sources; movement updates spatialisation without restarting sounds. Independent per-point seeds/phases prevent identical synchronized noise. Direct sources must not be silently replaced by one source per object or direction.

A finite configurable source budget is necessary for browser performance. The density slider requests 0–100% of scanned points within hearing range and scales the available source budget by the same percentage, so the control remains audible even when the map is much denser than the audio cap. A stable selection chooses contributors. Display requested and actually sounding counts. At 100%, request every eligible point; show an explicit capped status when the source budget prevents that. The budget may be raised for experiments; no unmeasured maximum count is promised.

Master volume, hearing radius and density are independent controls. Optional density gain compensation can stabilize overall level across density changes. Bound gains, fade source changes and use a final limiter. Stereo headphones are recommended; true binaural elevation is attempted by HRTF, not simulated solely through pitch. The simulator listener follows camera orientation. No separate physical headphone/head tracking.

A worklet handles final output limiting and level reporting. Native Web Audio buffer voices generate looping point signals on the browser audio thread. No UI-thread timer drives individual audio samples.

## Simulated recognition and rules

List objects observed by the simulated detector (field of view plus line-of-sight), or all known objects in fully scanned mode. Selecting an entry highlights it in the scene. Provide category and instance rule scopes, waveform/noise selection, frequency, relative gain and pulse rate; preview sound; reset override; reset all rules. Instance rules override category rules. Unknown/default geometry remains sounding.

Defaults distinguish structural surfaces, interactable objects, and a simulated hazard. The scene itself supplies labels; editing a label changes metadata and never trains YOLO. Sound assignments persist in localStorage. No persistent map relocalization is claimed.

## Controls

- Density: 0–100%, default 100% to preserve the dense experiment.
- Master volume: 0–100%, starts muted until Start Audio.
- Global hearing radius: 1–30 metres. Selected-object overrides: 1–60 metres.
- Voice budget: selectable experimental caps, default 256; allow larger counts explicitly.
- Point display size, scene visibility mode, gain compensation and audio-through-walls toggle.
- Scan, pause, full-map preload, clear, enter room, reset position, audio start/stop, sound preview.

Audio-through-walls is explicit: enabled means nearby stored points contribute even when hidden; disabled performs geometric occlusion checks. Scan acquisition always respects occlusion. The setting makes it possible to hear why a pre-scanned room may need an audibility policy.

## Interface and state

A light control surface surrounds a dark, architectural 3D viewport. Controls use clear labels and native inputs. A concise status line distinguishes Ready, Scanning, Paused, Fully scanned, and Audio on/off. Show live FPS, stored points, audible requested/active points and detected objects. No fabricated latency or performance numbers.

Include empty scan guidance, audio activation errors, unavailable WebGL fallback, visible keyboard focus and accessible input labels. Use a responsive layout with scrollable sidebar; on narrow screens stack the viewport and controls and explain that full movement requires a keyboard/mouse. Persist rules and preferences but not active audio or pointer lock.

## Data

SurfacePoint: stable ID, position, normal, object ID/category. Scan state: set of observed point IDs. Object metadata: ID, category, name, bounds, mesh references. SoundRule: texture, frequency, gain, pulse. Audio stats: eligible count, requested count, active count, budget status, measured output level.

## Acceptance checks

1. New-space mode initially contains no scanned points or point audio.
2. Scan adds only visible first-hit geometry and persists it after turning away.
3. Pausing retains points; clearing removes points and stops their voices.
4. Full-map mode loads all authored geometry.
5. WASD/arrows and mouse move/look; solids block movement; Escape frees controls.
6. Density and budget change actual contributors; 0% stops point audio.
7. Nearer sounds are louder; moving/turning updates direction.
8. Category edits affect matching points; instance override affects only one object; edits persist after reload.
9. Audio starts only on explicit interaction and stops independently of scanning.
10. The object list and UI explicitly identify recognition as simulated.
11. Requested/active counts remain honest when capped.
12. Production build and TypeScript checks pass; browser smoke checks exercise scan, mode, audio and rule changes.

## Deliverables

Runnable source, lockfile, this spec, README with setup and controls, and a local development URL. Save the POC under EchoSpace/poc. Keep Android implementation separate for the later phase.

## Blind simulation and distance feedback

Blind mode (toolbar button or B key) disables world rendering and hides the minimap, crosshair, object target labels and current-room name. Scene geometry, collision, scanning and individual spatial audio remain active. Object inspection is disabled in this mode; sound controls remain available. Show viewer or B restores the same scene, pose and scan. This mode also avoids the WebGL draw cost while active.

256 voices is the initial source budget; existing experimental choices extend through 2,048. It is not a fixed Web Audio limit. The user must measure usable counts on their own device. The hearing radius can be raised to 30 metres for the larger synthetic map. Visible target identification uses the camera's viewing distance independently of the 22m scan acquisition distance. Targets outside the current hearing radius are explicitly marked so silence is not mistaken for a missing label.

## Audio stability revision

Stable hash ordering replaces the angular-bin selection experiment. Small listener movements do not reassign the entire source pool. Default point signals return to 12kHz filtered noise; optional broadband noise uses 24kHz and remains an experimental opt-in. Cache at most 768 recently used signal buffers. Gain edits update gains without regenerating source buffers. Point loops have staggered start offsets and no synchronized per-buffer amplitude dips. Ahead/behind calibration remains available.

Hear floor and Hear ceiling independently exclude those surfaces from the eligible audio set and requested/active counts. Both default off. They do not affect scanning, rendering, occlusion or collision. Floors use their category; ceilings use explicit authored ceiling object IDs rather than disabling the wall category. Persist these controls with other preferences, migrating existing users once away from the regressed defaults while preserving their volume, range and cap.

## Per-object hearing distance and smooth fades

The global Hearing distance (1–30m, default 8m) applies to every point unless an object override exists. In the Objects tab, **This object hearing distance** sets a 1–60m range for the selected detected object, covering the full synthetic map. **Use global hearing distance** deletes that override. Preferences persist overrides by object ID in local storage. The effective per-object radius controls point eligibility and the smooth gain envelope. Wall, floor, and ceiling points use the global radius.

Every point contribution receives a smooth distance envelope: let x = clamp(1 - distance / effectiveRadius, 0, 1), then gain multiplier = x²(3 - 2x). Gain is zero at/outside the hearing boundary and increases smoothly toward the listener, in addition to the native spatializer's distance attenuation. Position-driven gain updates run at up to 30Hz only during movement, with small-change suppression and a 45ms gain smoothing constant. Source identity is preserved while fading. Persistent wall references and experimental sonar have been removed from the current interface; scanned surfaces are heard through their individual point sources.

Scanned wall points default to 45% of their assigned point gain; the volume control can raise or lower them independently of objects. Optional Quiet walls near objects applies a smooth reduction of at most 40%, with the option off by default. Existing installations that had the prior 15% default and hidden 90% reduction migrate to these audible defaults, while custom wall volume choices are retained.
