> **Vision document:** This describes proposed product features and is not a list of implemented functionality. See the repository README for the current prototype.

# EchoSpace — Android Product Specification

Status: proposed product requirements, not a claim of implemented functionality.
Date: 19 October 2026.
Primary reference device: Samsung Galaxy S23 Ultra.

## 1. Product idea

EchoSpace translates live room geometry and recognized objects into a personalized spatial soundscape. A phone continuously estimates depth, builds a point cloud, and places sound at the positions of scanned points. Nearby points sound louder. Object categories change the character of those sounds: a wall can have a soft noise texture, a door a chime, and a detected hazard a distinct warning pattern.

Users can change these associations through an accessible interface or by speaking to a Gemini-powered assistant. The associations persist and apply when that category appears again. A live density slider lets users explore dense point audio rather than forcing a small set of obstacle announcements.

The intended product distinction is the combination of live geometry, dense point sonification, persistent object sound rules, and conversational control on an Android phone. This is a proposed differentiation, not a claim that each component or the exact combination is unprecedented.

## 2. Users and intended use

- Blind and low-vision users exploring the layout of nearby indoor space.
- Users learning a personal sound vocabulary for surfaces and objects.
- Researchers and hackathon evaluators comparing different point densities and sound mappings.
- Sighted collaborators who need to inspect what was scanned and why a sound played.

Initial use is supervised indoor exploration and perception experiments. Route planning, turn-by-turn navigation, and guaranteed hazard avoidance are separate capabilities and are not implied by producing spatial audio.

## 3. Product principles

1. Local perception and audio remain responsive without a cloud conversation.
2. Every active audio point contributes a sound in direct point mode; source reduction is explicit and observable.
3. Sound rules are predictable and remain stable across frames and sessions.
4. Unknown geometry stays audible even when object recognition fails.
5. Saved geometry is distinguished from currently observed geometry.
6. Core controls work with TalkBack, large text, and physical touch without requiring voice.
7. Google integrations support demonstrable features. Service counts alone do not establish judging eligibility or scoring.

## 4. Main user journeys

### 4.1 First use

1. Start locally without an account; offer optional account connection for synchronization.
2. Explain camera, microphone, and cloud features separately. Camera is required for scanning; microphone is required only for spoken commands.
3. Check ARCore Depth support, camera access, audio output, and available detector models.
4. Run a short headphone orientation check: left, right, ahead, behind, above, and below.
5. Explain that the first version uses the phone's orientation as the listening direction. Offer a known forward-facing mounting position.
6. Let the user preview and select default wall, object, unknown, and hazard sounds.
7. Enter Explore with a large Start Scan action.

### 4.2 Explore a new room

1. Start Scan begins continuous depth capture and point-cloud fusion.
2. The user moves the phone gently to observe new surfaces; rotating in place alone may not supply enough parallax.
3. Only observed surfaces enter the map. Hidden surfaces and unseen rooms are not fabricated.
4. Points begin sounding immediately after valid observations become available.
5. Recognition assigns categories to matching geometry; category sounds fade smoothly into place.
6. Adjust density, volume, and hearing range during scanning.
7. Pause Scan freezes acquisition while audio continues from retained geometry; Stop Audio independently silences playback.
8. Save the scanned space with a name and optional landmarks.

### 4.3 Revisit a familiar space

1. Select a saved space, such as Home / Living Room.
2. Align the current camera tracking coordinates with the saved map using supported visual relocalization or a guided reference alignment.
3. Until alignment succeeds, offer saved-map preview only; do not present it as registered to the current room.
4. After alignment, replay the saved soundscape relative to the current pose and continue scanning visible areas.
5. Fresh observations update geometry. Previously scanned areas outside the camera view remain historical, not verified current.
6. A moved object is updated or invalidated when observed again. Unseen movement cannot be detected.

### 4.4 Customize sounds

Examples:

- “Make all doors sound like a bell.”
- “Make walls quieter.”
- “Use a rough pulsing sound for chairs.”
- “Only this chair should use that sound.”
- “Set density to seventy percent.”
- “What is making that sound?”
- “Undo the last change.”

The assistant resolves the target, applies a structured rule through an app tool, and acknowledges the actual result. Ambiguous targets produce a short clarification. Assigning a sound to an unsupported category does not create a new visual detector.

## 5. App structure and appearance

Use React Native for the main interface and native Android screens where direct AR rendering needs them. Keep the interface accessible with a high-contrast dark theme, an equally accessible light theme, large text, clear focus states, large touch targets, and TalkBack labels with meaningful values. Color supplements labels and audio; it never carries state alone.

The primary navigation contains Explore, Spaces, and Sound Rules. Assistant and Settings are available from the main screen. The app should feel like an audio instrument with a few clear actions, rather than a dense analytics dashboard.

### 5.1 Explore screen

- Main area: live camera view with optional point-cloud and object overlays.
- Alternative views: point cloud only, camera only, and low-distraction audio view.
- Top status: tracking state, scanning state, saved-space alignment state, audio output, and connection status when the assistant is in use.
- Persistent bottom controls: Start/Pause Scan, Start/Stop Audio, and Talk.
- A directly accessible density slider with numeric value and spoken value announcements.
- Expandable controls for master volume, hearing distance, and sound profile.
- Objects button opens the currently observed object list and its sound assignments.
- Save Space and Clear Current Scan are available without cluttering the main action area.
- Optional diagnostic overlay shows stored points, requested audio points, actual audio points, map age, scan rate, detection rate, and audio processing load.

In audio view, the Stop Audio action remains large and easy to find. Pausing the camera must visibly and audibly indicate that live updates have stopped.

### 5.2 Objects panel

Each entry includes category, optional instance name, approximate distance and direction, recognition confidence, last observed time, and assigned sound.

Actions: preview sound, edit category rule, override this instance, focus this object, correct label, and clear override. Label corrections are metadata unless a separate model-training workflow is explicitly implemented.

“What is making that sound?” refers to the focused object or selected source. When multiple sources overlap, the assistant identifies the ambiguity instead of inventing one target.

### 5.3 Sound Rules screen

List categories with their sound previews and editable settings:

- Base frequency or frequency range.
- Tone, filtered noise, or short repeating sound texture.
- Timbre/waveform, pulse rate, and relative volume.
- Distance response and maximum hearing range.
- Optional height-to-pitch reinforcement, separately enabled from true spatial positioning.
- Scope: global category, category within a space, or one object instance.

Changing a rule previews it before or during application and supports undo. Precedence is instance override, space category rule, global category rule, then unknown geometry default. A dedicated hazard layer uses explicit priority settings.

### 5.4 Spaces screen

Each space card shows its name, last scan date, rough observed extent, landmark count, local/cloud storage state, and alignment availability. Do not claim a percentage of a room is scanned when the full extent is unknown.

Actions: resume scan, align and explore, preview saved map, rename, export, back up, and delete. Show last-seen times and distinguish historical objects from live observations.

### 5.5 Assistant screen or sheet

Show push-to-talk, listening/processing/speaking state, a transcript, pending clarification, and completed action receipts. Support typed input as an alternative. Preview and undo recent rule changes.

When cloud access fails, explain that the assistant is unavailable while local scanning, audio, and manual controls continue. Conversation audio can temporarily lower the ambient soundscape by a user-controlled amount.

### 5.6 Settings and learning

Include audio calibration, reference orientation, detector/model availability, depth source, confidence filtering, local storage management, cloud upload preferences, account controls, accessible onboarding replay, and a sound dictionary tutorial.

## 6. Live mapping and scanning requirements

### 6.1 Acquisition

Use ARCore camera frames, camera intrinsics, timestamps, tracking pose, and depth. Choose raw depth plus its confidence map for confidence-aware reconstruction; offer full depth as a denser, interpolated experimental option. Do not imply full depth has the same confidence data as raw depth.

Reconstruct metric surface positions and transform them into one session coordinate frame. Keep image orientation, detector crop/resize, depth coordinates, and timestamps aligned. Process only genuinely new depth observations for fusion; a camera frame does not guarantee a fresh raw depth image.

The S23 Ultra's multiple camera lenses are not a dependency for the initial Android design. ARCore depth-from-motion is the starting method. Device support and behavior must be checked at runtime.

### 6.2 Point persistence

Store stable point or voxel identifiers, world position, raw-depth confidence when available, observation time, category confidence, object track ID, and source type. Deduplicate repeated observations so looking at a wall for longer does not continually multiply its loudness.

Separate persistent structural geometry from short-lived moving-object observations. Dynamic points expire or update promptly. Old geometry can be removed when fresh observations contradict it; absence outside the camera view is not proof of removal.

Mapping uses explicit memory and spatial limits. Evict or compress old regions according to visible policy and retain the last saved snapshot separately.

### 6.3 Saved spaces

Save point geometry, object metadata, rule references, landmarks, coordinate conventions, and any supported relocalization data. A saved point cloud alone is not a persistent ARCore tracking map.

Cross-session alignment is a separate milestone. Evaluate visual reference localization and optional ARCore Cloud Anchors. Cloud Anchors can support coordinate alignment but do not store or reconstruct the entire room point cloud. If alignment is unreliable, retain preview and same-session functionality and label the limitation.

## 7. Point-to-sound behavior

### 7.1 Direct point mode

Every point in the active audio set is a sound contributor. Its position determines direction; listener-to-point distance determines gain with a bounded, smooth falloff; its semantic category determines the selected sound rule. Unknown points retain the default geometry sound.

Use stable per-point seeds and continuous phase/envelopes so sources do not restart randomly every scan frame. Fade point births, removals, and category changes. Separate density-driven gain compensation from distance attenuation to permit fair density comparisons.

Dense rendering should allow many contributions to form a spatial texture, but the implementation must not claim that listeners can distinguish every individual source.

### 7.2 Density and limits

- Density slider: 0–100% of eligible mapped points within the hearing range, using stable nested sampling.
- At 100%, request all eligible points. Show any practical voice budget and the actual count separately.
- At 0%, silence point contributors while retaining the map and UI.
- Keep visual point density and audio point density separate.
- Never silently replace direct point mode with one sound per object or spatial cell.
- If a budget is exceeded, visibly report the limitation and use a documented stable subset. Offer an explicit performance mode that may aggregate sources.
- Density reduction must preserve existing points as much as possible to avoid reshuffling the whole soundscape.

### 7.3 Rendering and orientation

Use binaural spatialization with elevation cues rather than left/right volume panning alone. Standard stereo headphones are sufficient for binaural output; perceived externalization and elevation vary by listener.

Initial listener orientation follows the phone. State this during onboarding and provide a recalibrate-forward action. Independent head tracking is an optional future capability requiring compatible hardware and an accessible pose source; do not assume ordinary headphones report head orientation.

Use output gain control and a final limiter. Offer explicit audio occlusion behavior: default to currently visible or plausibly audible local surfaces, and expose a saved-map/all-points mode for experiments. Otherwise stored geometry behind walls may produce misleading sounds.

The first technical spike must establish achievable simultaneous source counts with real binaural processing on the S23 Ultra. Large source counts are a measurement goal, not an assumed capability.

## 8. Semantic perception

Use an efficient on-device detector, such as an exported YOLO-family model, executed through Google LiteRT when compatible. Prefer instance masks where possible: a bounding rectangle alone includes background and can wrongly label wall points as part of a chair.

For each detection: associate depth with the object mask or filtered depth cluster, estimate its 3D extent, track identity across frames, and assign labels only to supported points. Recognition confidence and depth confidence remain separate.

Required category plan:

| Category | Proposed source | Behavior |
|---|---|---|
| Chairs, tables, bottles, people | Supported detector classes | Apply category texture to matching points |
| Walls and floors | Plane geometry with orientation; semantic validation where available | Structural sound rules |
| Doors and doorways | Custom detector/segmentation or validated geometry model | Distinct sound; distinguish an object from a confirmed opening |
| Unknown obstacles | Depth geometry | Default point sound without a guessed label |
| Fire/smoke | Dedicated trained and evaluated visual model | Experimental hazard cue with confidence and persistence gating |

Stock COCO-trained detectors do not include every proposed class, including wall, door, and fire. Voice commands cannot add those classes to a fixed model. A door is not automatically open or passable, and a detected person or chair is not automatically safe to approach.

“Structural,” “potentially interactable,” and “potential hazard” are presentation categories with explicit rules, not guarantees of physical affordance or safety.

## 9. Conversational assistant and tools

Use Gemini Live for spoken interaction and function calling. Host the agent gateway on Google Cloud Run. The device sends bounded, timestamped scene summaries and user requests; it does not stream raw point clouds to the language model for every update.

Suggested tools:

- `get_scene_summary(max_age_ms)`
- `list_observed_objects(category)`
- `get_sound_rule(target)`
- `set_sound_rule(target, scope, sound_parameters)`
- `preview_sound(sound_parameters)`
- `set_density(percent)`
- `set_hearing_range(meters)`
- `focus_object(track_id)`
- `save_space(name)`
- `undo_last_change()`

Scene summaries include track IDs, categories, distances, timestamps, and confidence. The assistant must not claim to see an object unsupported by current scene data. Stale data is identified as historical.

Validate tool arguments locally, bound frequency and gain, resolve rule precedence, apply the change, then return an action receipt. The assistant speaks success only after receipt. Network retries must not duplicate mutations. Store rule revisions and undo information.

## 10. Architecture and recommended stack

| Layer | Technology | Responsibility |
|---|---|---|
| Android UI | React Native, with native Android AR views where needed | Shared product UI and direct AR rendering |
| Camera/depth | ARCore | Camera frames, pose, depth, geometry input |
| Object inference | Google LiteRT + compatible detector/segmenter | Local recognition |
| Map processing | Kotlin and native C++ where profiling warrants it | Fusion, point indexing, semantic association |
| Point rendering | OpenGL ES | Camera overlay and point-cloud inspection |
| Audio | Native C++ DSP, Oboe output, selected binaural renderer | Local synthesis, mixing, and spatialization |
| Device storage | Room + files + DataStore | Metadata, point snapshots, preferences |
| Voice intelligence | Gemini Live API | Conversation and structured tool requests |
| Agent backend | Python/FastAPI on Cloud Run | Authorized sessions, Gemini connection, tool routing |
| Accounts | Firebase Authentication | Optional account and sync identity |
| Structured cloud data | Cloud Firestore | Sound profiles and space metadata |
| Large cloud assets | Cloud Storage for Firebase | Opt-in map backups and sound assets |
| Operations | Firebase Crashlytics, Performance Monitoring | Crashes and instrumented performance |
| Abuse protection | Firebase App Check + backend token validation | Protect application endpoints |

ARCore owns the active camera session. Obtain CPU images from that session for inference rather than assuming a second CameraX session can use the same camera concurrently.

Map ingestion, detection, UI rendering, and audio run on separate schedules. The audio callback cannot wait on network requests, allocation-heavy map updates, or inference. Transfer bounded snapshots or lock-free updates to the renderer. Evaluate the binaural renderer independently; Oboe supplies audio I/O, not HRTF spatialization by itself.

## 11. Google service integration plan

### Hackathon priorities

1. **ARCore Depth:** demonstrate a real phone-derived point cloud and pose tracking.
2. **LiteRT:** demonstrate local object inference feeding category-specific point sounds.
3. **Gemini Live:** demonstrate a spoken sound assignment becoming a real app rule.
4. **Cloud Run:** demonstrate the hosted assistant gateway and validated tool execution.
5. **Firebase Authentication + Firestore:** demonstrate optional sign-in and restoration of sound preferences.

### Useful extensions if the core demo is complete

- **Cloud Storage for Firebase:** opt-in saved-space backup and retrieval.
- **Firebase App Check:** protect cloud-facing requests; backend authorization remains necessary.
- **Firebase Crashlytics / Performance Monitoring:** show real crash reporting and custom traces for acquisition, detection, and rule latency.
- **Firebase Remote Config:** choose model/config rollout defaults without overriding explicit user sound preferences.
- **ARCore Cloud Anchors:** evaluate cross-session coordinate alignment for saved spaces; it is a technical risk item, not a prerequisite for the initial demo.

Do not add outdoor Google Maps navigation to the core indoor prototype solely to increase the service count. Record each integrated service, the user-facing feature it powers, and demo evidence. The hackathon's actual eligible products and rubric still need to be checked.

The default design uses Cloud Run as the Gemini gateway. Firebase AI Logic is a supported alternative for direct client integrations, but is not an additional required hop in the same assistant path.

## 12. Data and synchronization

Entities:

- SoundProfile: owner, name, defaults, revision.
- SoundRule: target category/instance, scope, timbre, frequency, pulse, gain, range, revision.
- Space: owner, name, coordinate/alignment metadata, bounds, timestamps, version.
- PointChunk: geometry, observation metadata, confidence source, semantic references.
- ObjectTrack: category, instance identity, 3D extent, confidence, last seen, motion state.
- Landmark: user name, space-relative pose, optional object association.
- ActionReceipt: request ID, validated mutation, before/after revision, execution result.

The active local profile is authoritative during a session. Synchronization uses versioned updates and defined conflict handling rather than unexpectedly changing the soundscape mid-session. Firestore stores metadata and rules; large point clouds live in local files or opt-in object storage.

Local use requires no account. Cloud backups require authentication and owner-scoped access. Manual rules and cached models work offline; cloud conversation does not. Removing a space or account includes its linked backups according to the product's published deletion behavior.

## 13. Privacy and runtime states

Process camera frames and geometry locally by default. Send compact scene summaries to the assistant during requested interactions. Sending a camera image for contextual explanation is a separate opt-in action. Do not upload a continuous camera stream or raw room map by default. Make microphone and cloud transmission state visible.

Store backend credentials in managed secrets, authenticate sessions, validate ownership, enforce quotas, and avoid raw camera/audio data in routine logs.

Explicit states must include: missing camera permission, missing microphone permission, unsupported depth, depth initializing, tracking lost, low-confidence geometry, saved-map alignment pending, audio device disconnected, cloud unavailable, detector unavailable, thermal throttling, and source budget exceeded.

On tracking loss, fade positional playback or label a deliberate frozen preview; do not continue pretending that pose is current. On headphone disconnection, pause playback. On thermal pressure, report reduced update rates or point budgets and preserve the user's requested values separately from achieved values.

## 14. Performance targets and evaluation

These are provisional engineering targets to measure on the S23 Ultra, not current results:

- UI and camera view target: at least 30 fps under the selected workload.
- Consume fresh depth at its available rate; measure fresh versus reprojected observations.
- Detector target: 5–15 updates per second, with independent object tracking between results.
- Pose-to-audio parameter update target: under 50 ms at the 95th percentile.
- Fresh perception-to-audio target: under 150 ms at the 95th percentile, measured separately from output-device latency.
- Density and manual rule changes: audible within 150 ms under the reference workload.
- Voice rule change target: under three seconds after the end of speech on a healthy connection; report measured distribution.
- Direct audio density: benchmark increasing source counts, report sustained maximum with spatialization, and expose underruns.
- Run a sustained session to measure heat, battery drain, memory, and whether audio stays continuous.

Evaluate perceptual usefulness separately: left/right/front/back and height identification; rough distance ranking; doorway versus wall discrimination; recognition of assigned object sounds; time to learn sound rules; and effect of density on interpretation. Blindfolded sighted demonstrations are preliminary; intended-user feedback is required to assess accessibility and usefulness.

## 15. Acceptance scenarios

1. Scanning a visible wall produces a persistent point surface; an occluded object is not scanned through it.
2. Repeatedly scanning the same surface does not multiply stored points or loudness indefinitely.
3. Moving around a known point changes its perceived direction; approaching it changes gain smoothly.
4. The density slider changes the active contributor count live, including an explicit budget indicator.
5. A manually assigned category rule changes the sound of associated points and persists after restart.
6. A voice rule executes through a structured tool and reports the rule actually applied; undo restores it.
7. A missing semantic label preserves the geometry sound instead of removing the obstacle.
8. A person moving out of view does not leave a permanent live person sound at the old location.
9. A saved room is not presented as aligned until alignment succeeds.
10. Cloud failure leaves local scanning and manual sound control usable.
11. TalkBack can start/stop scanning, adjust density, preview/edit a rule, and stop audio.
12. Performance mode, if implemented, is explicitly labeled when it aggregates contributors.

## 16. Delivery stages

### Stage A — desktop simulation POC

Use TypeScript, React, Vite, Three.js, and Web Audio. Build a furnished room/corridor map; WASD/arrows and mouse controls; ray-based field-of-view scanning with occlusion; persistent sampled surfaces; fully scanned simulation mode; dense direct point audio; separate density/volume/range sliders; known object labels simulating detection; manual category and instance sound assignments; and diagnostics.

Do not add real detection or the agent until the core point-audio experiment is usable. Export scene/rule schemas that can inform Android, while keeping platform-specific rendering/audio separate.

### Stage B — Android live core

ARCore scanning, point storage/rendering, native spatial audio, live density control, manual rules, local storage, and on-device supported-class recognition. Benchmark the audio path before promising dense HRTF voice counts.

### Stage C — Google-powered hackathon demo

Gemini Live conversational rules through Cloud Run; Firebase Authentication and Firestore profile synchronization; a focused real-room demo; clear service evidence. Add optional backup/observability integrations only after the interaction works end to end.

### Stage D — fuller product

Saved-space relocalization; custom door/wall/hazard perception; durable instance naming; optional head tracking; improved acoustic occlusion; intended-user studies; learning tutorials; and longer-term reliability work.

## 17. Hackathon demonstration

1. Start with an unscanned physical test area and show points appearing only where observed.
2. Move the phone and demonstrate the sound direction and distance response.
3. Change density live and display requested versus achieved contributor counts.
4. Recognize supported objects and show their different point textures.
5. Say “Make chairs a low pulsing sound”; show the Gemini tool receipt and immediate sound change.
6. Save the profile, restart or load it on a second authenticated session, and show the preference retained.
7. Demonstrate saved-map preview. Claim live saved-room playback only if coordinate alignment is implemented and shown.
8. Turn off the network and demonstrate local scanning/audio continuing while the assistant reports offline.

Use simulated labels and hazard props only with explicit on-screen labeling. Present measured performance and related work honestly. Pitch the demonstrated integration and user control rather than claiming the invention of depth sonification.

## 18. Decisions still requiring experiments

- Sustainable simultaneous point-source count with accurate binaural rendering.
- Whether dense noise, tones, or mixed textures are most interpretable.
- Native renderer choice and any licensing requirements.
- Best detector/segmentation model, export compatibility, supported classes, and acceleration on the reference phone.
- Custom-model training data for doors, walls, fire, and smoke.
- Reliable saved-space alignment approach.
- Headphone latency, phone mounting, elevation perception, and independent head-pose availability.
- Exact hackathon rubric, eligible Google integrations, schedule, and team capacity.

## 19. Reference documentation

Product requirements above are design proposals. These sources support the platform capabilities and limitations:

- [ARCore Depth overview](https://developers.google.com/ar/develop/depth) — depth-from-motion and practical scene conditions.
- [ARCore Raw Depth](https://developers.google.com/ar/develop/java/depth/raw-depth) — confidence images, sparse depth, and fresh-frame timestamps.
- [ARCore supported devices](https://developers.google.com/ar/devices) — runtime support baseline; check the actual device.
- [Google LiteRT for Android](https://developers.google.com/edge/litert/android) — local model execution and runtime choices.
- [Gemini Live tool use](https://ai.google.dev/gemini-api/docs/live-api/tools) — function calling and explicit tool responses.
- [Cloud Run WebSockets](https://docs.cloud.google.com/run/docs/triggering/websockets) — persistent connections require timeout/reconnect handling.
- [Firestore offline data](https://firebase.google.com/docs/firestore/manage-data/enable-offline) — offline persistence and synchronization behavior.
- [ARCore Cloud Anchors](https://developers.google.com/ar/develop/cloud-anchors) — shared/persistent coordinate references, distinct from room geometry storage.
- [Firebase AI Logic](https://firebase.google.com/docs/ai-logic/get-started) — alternative client-oriented Gemini integration.
- [COCO detector categories](https://docs.ultralytics.com/datasets/detect/coco) — baseline class coverage and missing target categories.
