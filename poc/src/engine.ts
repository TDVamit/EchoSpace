import * as THREE from "three";
import { PointerLockControls } from "three/addons/controls/PointerLockControls.js";
import { createWorld, type World } from "./world";
import { PointAudio } from "./audio";
import { wallDucking } from "./distance";
import { selectAudioPoints, surfaceEnabled } from "./selection";
import {
  colors,
  initialStats,
  seeded,
  type Settings,
  type Rules,
  type Stats,
  type Point,
} from "./types";
export class Simulator {
  world: World;
  camera: THREE.PerspectiveCamera;
  private topCamera: THREE.OrthographicCamera;
  private overviewCamera: THREE.OrthographicCamera;
  private roomCamera: THREE.PerspectiveCamera;
  renderer: THREE.WebGLRenderer;
  private overviewRenderer: THREE.WebGLRenderer;
  private roomRenderer: THREE.WebGLRenderer;
  controls: PointerLockControls;
  audio = new PointAudio();
  stats: Stats = { ...initialStats };
  settings: Settings;
  rules: Rules;
  scanned = new Set<number>();
  selected: string | null = null;
  private pointGeometry = new THREE.BufferGeometry();
  private pointMaterial: THREE.PointsMaterial;
  private pointSprite: THREE.CanvasTexture;
  private pointCloud: THREE.Points;
  private positions: Float32Array;
  private pointColors: Float32Array;
  private pointBirths: Float32Array;
  private scannedPointIndex = new Map<number, number>();
  private revealUniforms: { value: number }[] = [];
  private reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  private playerMarker: THREE.Group;
  private highlight: THREE.Box3Helper;
  private detectionLayer: THREE.Group;
  private detectionBoxes = new Map<string, THREE.Box3Helper>();
  private detectionLabels = new Map<string, THREE.Sprite>();
  private ray = new THREE.Raycaster();
  private keys = new Set<string>();
  private raf = 0;
  private observer: ResizeObserver;
  private last = 0;
  private lastScan = 0;
  private lastSync = 0;
  private lastAudioRecovery = 0;
  private lastStats = 0;
  private frames = 0;
  private lastFps = 0;
  private lastOverviewRender = 0;
  private scanIndex = 0;
  private detected = new Set<string>();
  private count = 0;
  private needsAudioSync = true;
  private lastAudioState = false;
  private resumeAudioOnVisibility = false;
  private wallAnchors = new Map<number, Point>();
  private wallMeshes: THREE.Mesh[] = [];
  private disposed = false;
  constructor(
    private element: HTMLDivElement,
    private overviewElement: HTMLDivElement,
    private roomViewElement: HTMLDivElement,
    settings: Settings,
    rules: Rules,
    private report: (stats: Stats) => void,
  ) {
    this.settings = settings;
    this.rules = rules;
    this.world = createWorld();
    this.wallMeshes = this.world.objects
      .filter((o) => o.category === "wall" && !o.id.startsWith("ceiling-"))
      .flatMap((o) => o.meshes);
    this.camera = new THREE.PerspectiveCamera(72, 1, 0.07, 90);
    this.camera.position.set(0, 1.65, 5.5);
    this.topCamera = new THREE.OrthographicCamera(-15, 15, 15, -15, 0.1, 100);
    this.topCamera.up.set(0, 0, -1);
    this.overviewCamera = new THREE.OrthographicCamera(-16, 16, 16, -16, 0.1, 100);
    this.overviewCamera.up.set(0, 0, -1);
    this.roomCamera = new THREE.PerspectiveCamera(72, 1, 0.07, 90);
    this.playerMarker = new THREE.Group();
    const markerMaterial = new THREE.MeshBasicMaterial({ color: 0xc9e8a7, depthTest: false });
    const markerRing = new THREE.Mesh(new THREE.RingGeometry(0.2, 0.27, 28), markerMaterial);
    markerRing.rotation.x = -Math.PI / 2;
    markerRing.position.y = 0.045;
    markerRing.renderOrder = 10;
    const markerTip = new THREE.Mesh(new THREE.ConeGeometry(0.18, 0.48, 3), markerMaterial);
    markerTip.rotation.x = -Math.PI / 2;
    markerTip.position.set(0, 0.09, -0.31);
    markerTip.renderOrder = 10;
    this.playerMarker.add(markerRing, markerTip);
    this.playerMarker.visible = false;
    this.world.scene.add(this.playerMarker);
    this.renderer = new THREE.WebGLRenderer({
      antialias: true,
      powerPreference: "high-performance",
    });
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.15;
    this.element.appendChild(this.renderer.domElement);
    this.overviewRenderer = new THREE.WebGLRenderer({ antialias: false, alpha: false, powerPreference: "low-power" });
    this.overviewRenderer.setPixelRatio(1);
    this.overviewRenderer.outputColorSpace = THREE.SRGBColorSpace;
    this.overviewRenderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.overviewRenderer.toneMappingExposure = 1.15;
    this.overviewElement.appendChild(this.overviewRenderer.domElement);
    this.overviewRenderer.domElement.setAttribute("aria-hidden", "true");
    this.roomRenderer = new THREE.WebGLRenderer({ antialias: false, alpha: false, powerPreference: "low-power" });
    this.roomRenderer.setPixelRatio(1);
    this.roomRenderer.outputColorSpace = THREE.SRGBColorSpace;
    this.roomRenderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.roomRenderer.toneMappingExposure = 1.15;
    this.roomViewElement.appendChild(this.roomRenderer.domElement);
    this.roomRenderer.domElement.setAttribute("aria-hidden", "true");
    this.renderer.domElement.setAttribute(
      "aria-label",
      "First-person simulated room. Use Enter room for keyboard and mouse movement.",
    );
    this.controls = new PointerLockControls(this.camera, this.element);
    this.controls.pointerSpeed = 0.7;
    this.controls.minPolarAngle = Math.PI / 2;
    this.controls.maxPolarAngle = Math.PI / 2;
    this.positions = new Float32Array(this.world.points.length * 3);
    this.pointColors = new Float32Array(this.world.points.length * 3);
    this.pointBirths = new Float32Array(this.world.points.length);
    this.pointGeometry.setAttribute(
      "position",
      new THREE.BufferAttribute(this.positions, 3).setUsage(
        THREE.DynamicDrawUsage,
      ),
    );
    this.pointGeometry.setAttribute(
      "color",
      new THREE.BufferAttribute(this.pointColors, 3).setUsage(
        THREE.DynamicDrawUsage,
      ),
    );
    this.pointGeometry.setAttribute(
      "aBirth",
      new THREE.BufferAttribute(this.pointBirths, 1).setUsage(THREE.DynamicDrawUsage),
    );
    this.pointGeometry.setDrawRange(0, 0);
    const spriteCanvas = document.createElement("canvas");
    spriteCanvas.width = spriteCanvas.height = 64;
    const spriteContext = spriteCanvas.getContext("2d");
    if (spriteContext) {
      const glow = spriteContext.createRadialGradient(32, 32, 5, 32, 32, 32);
      glow.addColorStop(0, "rgba(255,255,255,1)");
      glow.addColorStop(0.68, "rgba(255,255,255,0.96)");
      glow.addColorStop(1, "rgba(255,255,255,0)");
      spriteContext.fillStyle = glow;
      spriteContext.fillRect(0, 0, 64, 64);
    }
    this.pointSprite = new THREE.CanvasTexture(spriteCanvas);
    this.pointSprite.minFilter = THREE.LinearFilter;
    this.pointSprite.magFilter = THREE.LinearFilter;
    this.pointSprite.generateMipmaps = false;
    this.pointMaterial = new THREE.PointsMaterial({
      size: settings.pointSize * 0.0065,
      map: this.pointSprite,
      vertexColors: true,
      sizeAttenuation: true,
      depthTest: true,
      depthWrite: false,
      transparent: true,
      opacity: 0.9,
    });
    this.pointMaterial.onBeforeCompile = (shader) => {
      shader.uniforms.uRevealTime = { value: 0 };
      this.revealUniforms.push(shader.uniforms.uRevealTime);
      shader.vertexShader = shader.vertexShader
        .replace("#include <common>", "#include <common>\nattribute float aBirth;\nvarying float vReveal;\nuniform float uRevealTime;")
        .replace("#include <begin_vertex>", "#include <begin_vertex>\nvReveal = smoothstep(aBirth, aBirth + 0.24, uRevealTime);");
      shader.fragmentShader = shader.fragmentShader
        .replace("#include <common>", "#include <common>\nvarying float vReveal;")
        .replace("#include <color_fragment>", "#include <color_fragment>\ndiffuseColor.a *= vReveal;");
    };
    this.pointMaterial.customProgramCacheKey = () => "echospace-point-reveal-v1";
    this.pointCloud = new THREE.Points(this.pointGeometry, this.pointMaterial);
    this.pointCloud.frustumCulled = false;
    this.world.scene.add(this.pointCloud);
    this.highlight = new THREE.Box3Helper(new THREE.Box3(), 0xf4d18a);
    this.highlight.visible = false;
    this.world.scene.add(this.highlight);
    this.detectionLayer = new THREE.Group();
    this.detectionLayer.visible = false;
    this.world.scene.add(this.detectionLayer);
    for (const object of this.world.objects) this.createDetectionMarker(object);
    this.observer = new ResizeObserver(() => this.resize());
    this.observer.observe(element);
    this.observer.observe(overviewElement);
    this.observer.observe(roomViewElement);
    window.addEventListener("keydown", this.keydown);
    window.addEventListener("keyup", this.keyup);
    window.addEventListener("blur", this.blur);
    document.addEventListener("visibilitychange", this.visibility);
    this.controls.addEventListener("unlock", this.blur);
    this.resize();
    this.updateSettings(settings);
    this.raf = requestAnimationFrame(this.tick);
  }
  private resize() {
    const { width, height } = this.element.getBoundingClientRect();
    this.camera.aspect = width / Math.max(1, height);
    this.camera.updateProjectionMatrix();
    const halfHeight = 16;
    const aspect = width / Math.max(1, height);
    this.topCamera.left = -halfHeight * aspect;
    this.topCamera.right = halfHeight * aspect;
    this.topCamera.top = halfHeight;
    this.topCamera.bottom = -halfHeight;
    this.topCamera.updateProjectionMatrix();
    this.renderer.setSize(width, height);
    const overviewBounds = this.overviewElement.getBoundingClientRect();
    const overviewWidth = Math.max(1, overviewBounds.width);
    const overviewHeight = Math.max(1, overviewBounds.height);
    const overviewHalfHeight = 11;
    const overviewAspect = overviewWidth / overviewHeight;
    this.overviewCamera.left = -overviewHalfHeight * overviewAspect;
    this.overviewCamera.right = overviewHalfHeight * overviewAspect;
    this.overviewCamera.top = overviewHalfHeight;
    this.overviewCamera.bottom = -overviewHalfHeight;
    this.overviewCamera.updateProjectionMatrix();
    this.overviewRenderer.setSize(overviewWidth, overviewHeight, false);
    const roomBounds = this.roomViewElement.getBoundingClientRect();
    const roomWidth = Math.max(1, roomBounds.width);
    const roomHeight = Math.max(1, roomBounds.height);
    this.roomCamera.aspect = roomWidth / roomHeight;
    this.roomCamera.updateProjectionMatrix();
    this.roomRenderer.setSize(roomWidth, roomHeight, false);
  }
  private createDetectionMarker(object: World["objects"][number]) {
    const color = colors[object.category];
    const detectable = object.category !== "wall" && object.category !== "floor";
    const box = new THREE.Box3Helper(object.bounds.clone(), color);
    box.visible = false;
    box.userData.detectable = detectable;
    box.visible = false;
    box.renderOrder = 20;
    const material = box.material as THREE.LineBasicMaterial;
    material.depthTest = true;
    material.depthWrite = false;
    this.detectionLayer.add(box);
    this.detectionBoxes.set(object.id, box);

    const canvas = document.createElement("canvas");
    canvas.width = 512;
    canvas.height = 96;
    const context = canvas.getContext("2d");
    if (context) {
      context.fillStyle = "rgba(18, 32, 39, .9)";
      context.strokeStyle = color;
      context.lineWidth = 4;
      context.beginPath();
      context.roundRect(3, 3, 506, 90, 16);
      context.fill();
      context.stroke();
      context.fillStyle = color;
      context.font = "700 34px sans-serif";
      context.textAlign = "center";
      context.textBaseline = "middle";
      context.fillText(object.name.toUpperCase(), 256, 48, 470);
    }
    const texture = new THREE.CanvasTexture(canvas);
    const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, transparent: true, depthTest: true, depthWrite: false }));
    const center = object.bounds.getCenter(new THREE.Vector3());
    sprite.position.set(center.x, object.bounds.max.y + 0.35, center.z);
    sprite.scale.set(2.6, 0.49, 1);
    sprite.visible = false;
    sprite.renderOrder = 21;
    this.detectionLayer.add(sprite);
    this.detectionLabels.set(object.id, sprite);
  }
  private updateDetectionMarker(id: string) {
    const object = this.world.objects.find((item) => item.id === id);
    const box = this.detectionBoxes.get(id);
    const sprite = this.detectionLabels.get(id);
    if (!object || !box || !sprite) return;
    const color = colors[object.category];
    box.box.copy(object.bounds);
    (box.material as THREE.LineBasicMaterial).color.set(color);
    const canvas = document.createElement("canvas");
    canvas.width = 512;
    canvas.height = 96;
    const context = canvas.getContext("2d");
    if (context) {
      context.fillStyle = "rgba(18, 32, 39, .9)";
      context.strokeStyle = color;
      context.lineWidth = 4;
      context.beginPath();
      context.roundRect(3, 3, 506, 90, 16);
      context.fill();
      context.stroke();
      context.fillStyle = color;
      context.font = "700 34px sans-serif";
      context.textAlign = "center";
      context.textBaseline = "middle";
      context.fillText(object.name.toUpperCase(), 256, 48, 470);
    }
    const material = sprite.material as THREE.SpriteMaterial;
    material.map?.dispose();
    material.map = new THREE.CanvasTexture(canvas);
    material.needsUpdate = true;
  }
  private keydown = (e: KeyboardEvent) => {
    if (e.code === "Escape") {
      this.controls.unlock();
      this.blur();
      return;
    }
    if (!this.controls.isLocked && this.settings.cameraMode !== "top") return;
    if (
      [
        "KeyW",
        "KeyA",
        "KeyS",
        "KeyD",
        "ArrowUp",
        "ArrowDown",
        "ArrowLeft",
        "ArrowRight",
        "KeyQ",
        "KeyE",
        "ShiftLeft",
        "ShiftRight",
      ].includes(e.code)
    ) {
      e.preventDefault();
      this.keys.add(e.code);
    }
  };
  private keyup = (e: KeyboardEvent) => {
    this.keys.delete(e.code);
  };
  private blur = () => {
    this.keys.clear();
  };
  private visibility = () => {
    if (document.hidden) {
      this.blur();
      this.controls.unlock();
      this.resumeAudioOnVisibility = this.audio.active;
      if (this.resumeAudioOnVisibility && this.audio.context?.state === "running")
        void this.audio.context.suspend().catch(() => undefined);
      this.stats.scanning = false;
      this.emit();
      return;
    }
    if (this.resumeAudioOnVisibility) {
      this.resumeAudioOnVisibility = false;
      void this.audio.ensureRunning().then(() => {
        this.lastAudioState = this.audio.active;
        this.emit();
      }).catch(() => {
        this.emit();
      });
    }
  };
  enter() {
    this.controls.lock();
  }
  resetPosition() {
    this.camera.position.set(0, 1.65, 5.5);
    this.camera.rotation.set(0, 0, 0);
    this.needsAudioSync = true;
  }
  updateSettings(settings: Settings) {
    const cameraChanged = this.settings.cameraMode !== settings.cameraMode;
    this.settings = settings;
    if (cameraChanged && settings.cameraMode === "top") this.controls.unlock();
    this.audio.setVolume(settings.volume);
    this.audio.spatialNoise = settings.spatialNoise;
    this.renderer.domElement.style.visibility = settings.blind
      ? "hidden"
      : "visible";
    this.pointMaterial.size = settings.pointSize * 0.0065;
    this.pointCloud.visible = settings.view !== "scene";
    for (const object of this.world.objects) {
      const hiddenCeiling = settings.cameraMode === "top" && object.id.startsWith("ceiling-");
      for (const mesh of object.meshes) mesh.visible = settings.view !== "points" && !hiddenCeiling;
    }
    this.playerMarker.visible = settings.cameraMode === "top";
    this.needsAudioSync = true;
  }
  identifyStudioCabin(id: string) {
    const object = this.world.objects.find((item) => item.id === id);
    if (!object || object.category !== "unknown") return false;
    object.name = "Studio cabin";
    object.category = "studio-cabin";
    const color = new THREE.Color(colors["studio-cabin"]);
    for (const point of this.world.points) {
      if (point.objectId !== id) continue;
      point.category = "studio-cabin";
      const scannedIndex = this.scannedPointIndex.get(point.id);
      if (scannedIndex !== undefined) color.toArray(this.pointColors, scannedIndex * 3);
    }
    this.pointGeometry.attributes.color.needsUpdate = true;
    this.updateDetectionMarker(id);
    this.needsAudioSync = true;
    this.emit();
    return true;
  }
  updateRules(rules: Rules) {
    this.rules = rules;
    this.needsAudioSync = true;
  }
  select(id: string | null) {
    this.selected = id;
    const object = this.world.objects.find((o) => o.id === id);
    this.highlight.visible = !!object;
    if (object) {
      this.highlight.box.copy(object.bounds);
      this.highlight.updateMatrixWorld(true);
    }
  }
  setMode(mode: "new" | "full") {
    this.stats.mode = mode;
    this.stats.scanning = false;
    this.clear();
    if (mode === "full") {
      for (const p of this.world.points) this.addPoint(p);
      for (const o of this.world.objects) {
        this.detected.add(o.id);
        const box = this.detectionBoxes.get(o.id);
        const label = this.detectionLabels.get(o.id);
        if (box?.userData.detectable) box.visible = true;
        if (box?.userData.detectable && label) label.visible = true;
      }
      this.dirtyPoints();
    }
    this.needsAudioSync = true;
    this.emit();
  }
  clear() {
    this.scanned.clear();
    this.wallAnchors.clear();
    this.detected.clear();
    for (const box of this.detectionBoxes.values()) box.visible = false;
    for (const label of this.detectionLabels.values()) label.visible = false;
    this.count = 0;
    this.scannedPointIndex.clear();
    this.pointGeometry.setDrawRange(0, 0);
    this.audio.sync([], this.rules, this.settings);
    this.audio.syncWallCues(
      [],
      this.rules,
      this.settings,
      this.camera.position,
    );
    this.needsAudioSync = true;
    this.emit();
  }
  toggleScan() {
    if (this.stats.mode === "full") this.setMode("new");
    this.stats.scanning = !this.stats.scanning;
    this.emit();
  }
  private addPoint(p: Point, birthTime = performance.now() / 1000) {
    if (this.scanned.has(p.id)) return;
    this.scanned.add(p.id);
    const offset = this.count++ * 3;
    const pointIndex = offset / 3;
    this.scannedPointIndex.set(p.id, pointIndex);
    const delay = this.reducedMotion
      ? 0
      : Math.min(p.position.distanceTo(this.camera.position) * 0.025, 1.1);
    this.pointBirths[pointIndex] = birthTime + delay;
    this.positions[offset] = p.position.x + p.normal.x * 0.012;
    this.positions[offset + 1] = p.position.y + p.normal.y * 0.012;
    this.positions[offset + 2] = p.position.z + p.normal.z * 0.012;
    const color = new THREE.Color(colors[p.category]);
    color.toArray(this.pointColors, offset);
  }
  private dirtyPoints() {
    this.pointGeometry.attributes.position.needsUpdate = true;
    this.pointGeometry.attributes.color.needsUpdate = true;
    this.pointGeometry.attributes.aBirth.needsUpdate = true;
    this.pointGeometry.setDrawRange(0, this.count);
  }
  private cast(ndc: THREE.Vector2, far = 22) {
    this.ray.setFromCamera(ndc, this.camera);
    this.ray.far = far;
    return this.ray.intersectObjects(this.world.meshes, false)[0];
  }
  private scan() {
    this.camera.updateMatrixWorld();
    let changed = false;
    const rng = seeded(6501 + this.scanIndex++);
    for (let y = 0; y < 20; y++)
      for (let x = 0; x < 30; x++) {
        const hit = this.cast(
          new THREE.Vector2(
            ((x + rng()) / 30) * 2 - 1,
            ((y + rng()) / 20) * 2 - 1,
          ),
        );
        if (hit) {
          const detectedId = hit.object.userData.objectId as string;
          this.detected.add(detectedId);
          const box = this.detectionBoxes.get(detectedId);
          const label = this.detectionLabels.get(detectedId);
          if (box?.userData.detectable) box.visible = true;
          if (box?.userData.detectable && label) label.visible = true;
          const p = this.world.findPoint(hit);
          if (p && !this.scanned.has(p.id)) {
            this.addPoint(p);
            changed = true;
          }
        }
      }
    if (changed) {
      this.dirtyPoints();
      this.needsAudioSync = true;
    }
  }
  private inside(x: number, z: number) {
    const r = 0.22;
    return (
      (x > -9 + r && x < 9 - r && z > -15 + r && z < 7 - r) ||
      (x > -2 + r && x < 2 - r && z > -28 - r && z < -15 + r) ||
      (x > -7 + r && x < 7 - r && z > -39 + r && z < -28 + r)
    );
  }
  private blocked(x: number, z: number) {
    if (!this.inside(x, z)) return true;
    return this.world.solids.some(
      (b) =>
        b.min.y < 1.7 &&
        b.max.y > 0.1 &&
        x > b.min.x - 0.23 &&
        x < b.max.x + 0.23 &&
        z > b.min.z - 0.23 &&
        z < b.max.z + 0.23,
    );
  }
  private move(dt: number) {
    if (!this.controls.isLocked && this.settings.cameraMode !== "top") return;
    if (this.settings.cameraMode === "top") {
      const turn = Number(this.keys.has("KeyE")) - Number(this.keys.has("KeyQ"));
      if (turn) {
        this.camera.rotation.y -= turn * 1.8 * dt;
        this.needsAudioSync = true;
      }
    }
    const x =
      Number(this.keys.has("KeyD") || this.keys.has("ArrowRight")) -
      Number(this.keys.has("KeyA") || this.keys.has("ArrowLeft"));
    const z =
      Number(this.keys.has("KeyW") || this.keys.has("ArrowUp")) -
      Number(this.keys.has("KeyS") || this.keys.has("ArrowDown"));
    if (!x && !z) return;
    const f = new THREE.Vector3();
    this.camera.getWorldDirection(f);
    f.y = 0;
    f.normalize();
    const right = new THREE.Vector3().crossVectors(
      f,
      new THREE.Vector3(0, 1, 0),
    );
    const v = f
      .multiplyScalar(z)
      .addScaledVector(right, x)
      .normalize()
      .multiplyScalar(
        dt *
          (this.keys.has("ShiftLeft") || this.keys.has("ShiftRight")
            ? 4.2
            : 2.4),
      );
    const p = this.camera.position;
    if (!this.blocked(p.x + v.x, p.z)) p.x += v.x;
    if (!this.blocked(p.x, p.z + v.z)) p.z += v.z;
    this.needsAudioSync = true;
  }
  private updateWallAnchors() {
    const known = this.wallMeshes.filter((m) =>
      this.detected.has(m.userData.objectId),
    );
    const directions = [
      new THREE.Vector3(0, 0, -1),
      new THREE.Vector3(1, 0, 0),
      new THREE.Vector3(0, 0, 1),
      new THREE.Vector3(-1, 0, 0),
    ];
    for (let i = 0; i < directions.length; i++) {
      const direction = directions[i];
      this.ray.set(this.camera.position, direction);
      this.ray.far = this.camera.far;
      const hit = this.ray.intersectObjects(known, false)[0];
      let location = hit?.point.clone();
      let objectId = hit?.object.userData.objectId as string | undefined;
      let best = location?.distanceToSquared(this.camera.position) ?? Infinity;
      // A doorway must not become a phantom wall. Use the closest actual wall
      // surface in this quarter of the room, including a wall beside an opening.
      for (const wall of this.world.objects) {
        if (
          wall.category !== "wall" ||
          wall.id.startsWith("ceiling-") ||
          !this.detected.has(wall.id) ||
          wall.bounds.min.y > this.camera.position.y ||
          wall.bounds.max.y < this.camera.position.y
        )
          continue;
        const point = wall.bounds.clampPoint(
          this.camera.position,
          new THREE.Vector3(),
        );
        const delta = point.clone().sub(this.camera.position);
        const forward = delta.dot(direction),
          side = Math.abs(delta.x * direction.z - delta.z * direction.x);
        const distance = delta.lengthSq();
        if (forward > 0 && forward >= side && distance < best) {
          location = point;
          objectId = wall.id;
          best = distance;
        }
      }
      if (location && objectId)
        this.wallAnchors.set(i, {
          id: -101 - i,
          position: location,
          normal: direction.clone().negate(),
          objectId,
          category: "wall",
        });
      // Keep the last real surface anchor when a doorway briefly opens this sector.
    }
  }
  private synchronize() {
    const camera = this.camera.position;
    if (this.settings.wallCues) this.updateWallAnchors();
    const wallPoints = this.settings.wallCues
      ? [...this.wallAnchors.values()]
      : [];
    const pointBudget = Math.max(0, this.settings.budget - wallPoints.length);
    const eligible: Point[] = [];
    let closestObject = Infinity;
    const direction = new THREE.Vector3();
    for (const id of this.scanned) {
      const p = this.world.points[id];
      if (p.category === "unknown") continue;
      if (
        !surfaceEnabled(p, this.settings.hearFloor, this.settings.hearCeiling)
      )
        continue;
      const pointRange =
        this.settings.objectRanges[p.objectId] ?? this.settings.range;
      const pointDistanceSq = p.position.distanceToSquared(camera);
      if (pointDistanceSq > pointRange ** 2) continue;
      if (!this.settings.throughWalls) {
        direction.subVectors(p.position, camera);
        const distance = direction.length();
        this.ray.set(camera, direction.normalize());
        this.ray.far = Math.max(0, distance - 0.12);
        if (this.ray.intersectObjects(this.world.meshes, false).length)
          continue;
      }
      if (p.category !== "wall" && p.category !== "floor")
        closestObject = Math.min(
          closestObject,
          (Math.sqrt(pointDistanceSq) * this.settings.range) / pointRange,
        );
      eligible.push(p);
    }
    // Unknown detections get one persistent pulsing beacon instead of hundreds of point voices.
    const beacons: Point[] = [];
    for (const id of this.detected) {
      const object = this.world.objects.find((item) => item.id === id);
      if (!object || object.category !== "unknown") continue;
      const beaconPosition = object.bounds.getCenter(new THREE.Vector3());
      const beaconRadius = 5;
      if (beaconPosition.distanceToSquared(camera) > beaconRadius ** 2) continue;
      beacons.push({
        id: -200000 - this.world.objects.indexOf(object),
        position: beaconPosition,
        normal: new THREE.Vector3(0, 0, 1),
        objectId: object.id,
        category: "unknown",
      });
    }
    // Keep point priority fixed during movement; only range/filter changes replace sources.
    const requested = Math.ceil(((eligible.length + beacons.length) * this.settings.density) / 100);
    const activeLimit = Math.ceil((Math.min(eligible.length + beacons.length, pointBudget) * this.settings.density) / 100);
    const audibleBeacons = beacons.slice(0, activeLimit);
    const chosen = [
      ...audibleBeacons,
      ...selectAudioPoints(eligible, Math.max(0, activeLimit - audibleBeacons.length)),
    ];
    this.stats.eligible = eligible.length + beacons.length;
    this.stats.requested = requested;
    this.audio.wallDuckFactor = this.settings.autoQuietWalls
      ? wallDucking(closestObject, this.settings.range)
      : 1;
    this.audio.sync(chosen, this.rules, this.settings);
    this.audio.syncWallCues(wallPoints, this.rules, this.settings, camera);
    this.stats.active = this.audio.active
      ? this.audio.voices.size + this.audio.wallVoices.size
      : 0;
  }
  private emit() {
    this.stats = {
      ...this.stats,
      knownWalls: this.wallAnchors.size,
      wallCues: this.audio.active ? this.audio.wallVoices.size : 0,
      stored: this.scanned.size,
      detected: [...this.detected],
      locked: this.controls.isLocked,
      position: this.camera.position.toArray(),
      yaw: this.camera.rotation.y,
      active: this.audio.active
        ? this.audio.voices.size + this.audio.wallVoices.size
        : 0,
      level: this.audio.level,
    };
    this.report(this.stats);
  }
  private tick = (time: number) => {
    if (this.disposed) return;
    const dt = Math.min((time - (this.last || time)) / 1000, 0.05);
    this.last = time;
    this.move(dt);
    this.camera.updateMatrixWorld();
    this.audio.listener(this.camera);
    if (
      this.audio.active &&
      !document.hidden &&
      this.audio.context?.state !== "running" &&
      time - this.lastAudioRecovery > 750
    ) {
      this.lastAudioRecovery = time;
      void this.audio.ensureRunning();
    }
    if (this.stats.scanning && time - this.lastScan > 110) {
      this.scan();
      this.lastScan = time;
    }
    if (this.audio.active !== this.lastAudioState) {
      this.lastAudioState = this.audio.active;
      this.needsAudioSync = true;
    }
    if (this.needsAudioSync && time - this.lastSync > 100) {
      this.synchronize();
      this.lastSync = time;
      this.needsAudioSync = false;
    }
    if (time - this.lastStats > 160) {
      const hit = this.cast(new THREE.Vector2(), this.camera.far);
      this.stats.target = hit?.object.userData.objectId ?? null;
      this.stats.distance = hit?.distance ?? 0;
      const targetObject = this.world.objects.find((object) => object.id === this.stats.target);
      if (targetObject) {
        this.highlight.box.copy(targetObject.bounds);
        (this.highlight.material as THREE.LineBasicMaterial).color.set(colors[targetObject.category]);
        this.highlight.visible = true;
      } else if (this.selected) {
        const selectedObject = this.world.objects.find((object) => object.id === this.selected);
        if (selectedObject) {
          this.highlight.box.copy(selectedObject.bounds);
          (this.highlight.material as THREE.LineBasicMaterial).color.set(colors[selectedObject.category]);
          this.highlight.visible = true;
        } else this.highlight.visible = false;
      } else this.highlight.visible = false;
      this.emit();
      this.lastStats = time;
    }
    const p = this.camera.position;
    this.topCamera.position.set(p.x, 34, p.z);
    this.topCamera.lookAt(p.x, 0, p.z);
    this.playerMarker.position.set(p.x, 0, p.z);
    this.playerMarker.rotation.y = this.camera.rotation.y;
    for (const uniform of this.revealUniforms) uniform.value = time / 1000;
    this.frames++;
    if (time - this.lastFps > 1000) {
      this.stats.fps = Math.round((this.frames * 1000) / (time - this.lastFps));
      this.frames = 0;
      this.lastFps = time;
    }
    if (!this.settings.blind)
      this.renderer.render(this.world.scene, this.settings.cameraMode === "top" ? this.topCamera : this.camera);
    if (time - this.lastOverviewRender >= 90) {
      this.overviewCamera.position.set(p.x, 34, p.z);
      this.overviewCamera.lookAt(p.x, 0, p.z);
      const roomMeshes = this.world.objects.flatMap((object) => object.meshes);
      const roomVisibility = roomMeshes.map((mesh) => mesh.visible);
      const markerVisibility = this.playerMarker.visible;
      const pointVisibility = this.pointCloud.visible;
      const highlightVisibility = this.highlight.visible;
      const detectionVisibility = this.detectionLayer.visible;
      for (const object of this.world.objects) {
        for (const mesh of object.meshes) mesh.visible = !object.id.startsWith("ceiling-");
      }
      this.pointCloud.visible = true;
      this.playerMarker.visible = true;
      this.highlight.visible = false;
      this.detectionLayer.visible = false;
      this.overviewRenderer.render(this.world.scene, this.overviewCamera);

      for (const object of this.world.objects) {
        for (const mesh of object.meshes) mesh.visible = !object.id.startsWith("ceiling-");
      }
      this.pointCloud.visible = false;
      this.playerMarker.visible = false;
      this.highlight.visible = false;
      this.detectionLayer.visible = true;
      this.roomCamera.position.copy(this.camera.position);
      this.roomCamera.quaternion.copy(this.camera.quaternion);
      this.roomCamera.aspect = this.roomViewElement.clientWidth / Math.max(1, this.roomViewElement.clientHeight);
      this.roomCamera.updateProjectionMatrix();
      this.roomRenderer.render(this.world.scene, this.roomCamera);

      roomMeshes.forEach((mesh, index) => { mesh.visible = roomVisibility[index]; });
      this.playerMarker.visible = markerVisibility;
      this.pointCloud.visible = pointVisibility;
      this.highlight.visible = highlightVisibility;
      this.detectionLayer.visible = detectionVisibility;
      this.lastOverviewRender = time;
    }
    this.raf = requestAnimationFrame(this.tick);
  };
  dispose() {
    this.disposed = true;
    cancelAnimationFrame(this.raf);
    this.observer.disconnect();
    window.removeEventListener("keydown", this.keydown);
    window.removeEventListener("keyup", this.keyup);
    window.removeEventListener("blur", this.blur);
    document.removeEventListener("visibilitychange", this.visibility);
    this.controls.removeEventListener("unlock", this.blur);
    this.controls.unlock();
    this.controls.dispose();
    this.audio.dispose();
    this.pointGeometry.dispose();
    this.pointMaterial.dispose();
    this.pointSprite.dispose();
    this.highlight.geometry.dispose();
    (this.highlight.material as THREE.Material).dispose();
    this.world.dispose();
    this.renderer.dispose();
    this.renderer.domElement.remove();
    this.overviewRenderer.dispose();
    this.overviewRenderer.domElement.remove();
    this.roomRenderer.dispose();
    this.roomRenderer.domElement.remove();
    for (const sprite of this.detectionLabels.values()) {
      (sprite.material as THREE.SpriteMaterial).map?.dispose();
      (sprite.material as THREE.Material).dispose();
    }
    for (const box of this.detectionBoxes.values()) {
      box.geometry.dispose();
      (box.material as THREE.Material).dispose();
    }
  }
}
