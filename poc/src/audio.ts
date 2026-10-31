import * as THREE from "three";
import { distanceFade } from "./distance";
import {
  resolveRule,
  seeded,
  type Point,
  type Rules,
  type Settings,
  type Rule,
} from "./types";
interface Voice {
  source: AudioBufferSourceNode;
  gain: GainNode;
  panner: PannerNode;
  signature: string;
  position?: THREE.Vector3;
  baseGain?: number;
  radius?: number;
  lastGain?: number;
}
export class PointAudio {
  context: AudioContext | null = null;
  active = false;
  spatialNoise = false;
  level = 0;
  voices = new Map<number, Voice>();
  wallVoices = new Map<number, Voice>();
  wallDuckFactor = 1;
  private listenerPosition = new THREE.Vector3();
  private lastDistancePosition = new THREE.Vector3(
    Infinity,
    Infinity,
    Infinity,
  );
  private lastDistanceUpdate = -1;
  private volume = 35;
  private wallCueEpoch = 0;
  private bufferCache = new Map<string, AudioBuffer>();
  private master: GainNode | null = null;
  private limiter: AudioWorkletNode | null = null;
  private generation = 0;
  private resumePending: Promise<void> | null = null;
  private previewSources = new Set<AudioBufferSourceNode>();
  async start() {
    if (this.active) return;
    const generation = ++this.generation;
    if (!this.context) {
      this.context = new AudioContext({ latencyHint: "interactive" });
      const createdContext = this.context;
      createdContext.addEventListener("statechange", () => {
        if (this.active && !document.hidden && createdContext.state !== "running")
          void this.ensureRunning();
      });
      this.master = this.context.createGain();
      this.master.gain.value = 0;
      try {
        await this.context.audioWorklet.addModule(
          `${import.meta.env.BASE_URL}limiter.js`,
        );
        this.limiter = new AudioWorkletNode(this.context, "point-limiter", {
          outputChannelCount: [2],
        });
        this.limiter.port.onmessage = (e) => {
          this.level = e.data.peak;
        };
        this.master.connect(this.limiter).connect(this.context.destination);
      } catch {
        const limiter = this.context.createDynamicsCompressor();
        limiter.threshold.value = -6;
        limiter.knee.value = 3;
        limiter.ratio.value = 20;
        this.master.connect(limiter).connect(this.context.destination);
      }
    }
    await this.ensureRunning();
    if (generation === this.generation) {
      this.active = true;
      this.wallCueEpoch = this.context.currentTime;
      this.setVolume(this.volume);
    }
  }
  async ensureRunning() {
    const context = this.context;
    if (!context || context.state === "running" || context.state === "closed") return;
    if (this.resumePending) return this.resumePending;
    this.resumePending = context.resume().catch(() => undefined).finally(() => {
      this.resumePending = null;
    });
    return this.resumePending;
  }
  setVolume(volume: number) {
    this.volume = volume;
    if (this.context && this.master)
      this.master.gain.setTargetAtTime(
        (volume / 100) * 0.55,
        this.context.currentTime,
        0.04,
      );
  }
  stop() {
    this.generation++;
    this.active = false;
    for (const v of this.voices.values()) this.remove(v);
    this.voices.clear();
    for (const v of this.wallVoices.values()) this.remove(v);
    this.wallVoices.clear();
    for (const s of this.previewSources) {
      try {
        s.stop();
      } catch {
        /* finished */
      }
    }
    this.previewSources.clear();
    this.level = 0;
    if (this.context)
      this.master?.gain.setTargetAtTime(0, this.context.currentTime, 0.02);
  }
  private signature(rule: Rule) {
    return JSON.stringify([
      rule.texture,
      rule.frequency,
      rule.pulse,
      rule.texture === "noise" && this.spatialNoise,
    ]);
  }
  private buffer(rule: Rule, id: number, wallMode?: Settings["wallCueMode"]) {
    const ctx = this.context!;
    // Points with the same sound rule can share one immutable sample buffer.
    // Their individual panners and seeded playback offsets still make them
    // spatially distinct, while avoiding a random 12k-sample buffer allocation
    // every time scanning changes the selected voice set.
    const signature = this.signature(rule);
    const key = `${wallMode === "pulsed" ? id : "shared"}:${signature}:${wallMode ?? "point"}`;
    const cached = this.bufferCache.get(key);
    if (cached) {
      this.bufferCache.delete(key);
      this.bufferCache.set(key, cached);
      return cached;
    }
    const seconds = wallMode === "pulsed" ? 3 : 1;
    const sampleRate =
      rule.texture === "noise" && this.spatialNoise ? 24000 : 12000;
    const b = ctx.createBuffer(1, Math.floor(sampleRate * seconds), sampleRate);
    const data = b.getChannelData(0);
    let signatureSeed = 2166136261;
    for (let i = 0; i < signature.length; i++)
      signatureSeed = Math.imul(signatureSeed ^ signature.charCodeAt(i), 16777619);
    const rng = seeded(wallMode === "pulsed" ? id * 7919 + 83 : signatureSeed);
    const phase = rng() * Math.PI * 2;
    let low = 0;
    const alpha = Math.min(0.8, (2 * Math.PI * rule.frequency) / sampleRate);
    const angularStep =
      (2 * Math.PI * Math.round(rule.frequency * seconds)) /
      (sampleRate * seconds);
    for (let i = 0; i < data.length; i++) {
      const t = i / sampleRate;
      const angle = angularStep * i + phase;
      let value = 0;
      if (rule.texture === "noise") {
        const white = rng() * 2 - 1;
        low += alpha * (white - low);
        value = this.spatialNoise ? low * 1.2 + white * 0.45 : low * 2.4;
      } else if (rule.texture === "triangle")
        value = (2 / Math.PI) * Math.asin(Math.sin(angle));
      else if (rule.texture === "bell")
        value =
          (Math.sin(angle) +
            0.35 * Math.sin(angle * 2) +
            0.15 * Math.sin(angle * 3)) *
          0.65;
      else value = Math.sin(angle);
      if (rule.pulse > 0) {
        const pulses = Math.max(1, Math.round(rule.pulse * seconds));
        value *=
          (0.5 +
            0.5 * Math.sin(((2 * Math.PI * pulses) / seconds) * t + phase)) **
          (rule.texture === "bell" ? 7 : 5);
      }
      if (wallMode === "pulsed") {
        const slot = (-id - 101) * 0.75;
        const elapsed = t - slot;
        // Audio-clock envelope: 180ms per side, with 570ms between sides.
        value *=
          elapsed >= 0 && elapsed < 0.18
            ? Math.sin((Math.PI * elapsed) / 0.18) ** 2
            : 0;
      }
      data[i] = value;
    }
    this.bufferCache.set(key, b);
    // Bounded reuse prevents regenerated buffers on short back-and-forth moves.
    while (this.bufferCache.size > 768)
      this.bufferCache.delete(this.bufferCache.keys().next().value!);
    return b;
  }
  private create(point: Point, rule: Rule, wallMode?: Settings["wallCueMode"]) {
    const ctx = this.context!;
    const source = ctx.createBufferSource();
    source.buffer = this.buffer(rule, point.id, wallMode);
    source.loop = true;
    const gain = ctx.createGain();
    gain.gain.value = 0;
    const panner = ctx.createPanner();
    panner.panningModel = "HRTF";
    panner.distanceModel = point.category === "unknown" ? "linear" : "inverse";
    panner.refDistance = point.category === "unknown" ? 1 : 0.65;
    panner.rolloffFactor = point.category === "unknown" ? 0.2 : 1.3;
    panner.maxDistance = 100;
    panner.positionX.value = point.position.x;
    panner.positionY.value = point.position.y;
    panner.positionZ.value = point.position.z;
    source.connect(gain).connect(panner).connect(this.master!);
    const offset =
      wallMode === "pulsed"
        ? (ctx.currentTime - this.wallCueEpoch) % 3
        : seeded(point.id * 104729 + 17)();
    source.start(0, offset);
    return {
      source,
      gain,
      panner,
      signature: wallMode
        ? JSON.stringify([this.signature(rule), wallMode])
        : this.signature(rule),
    };
  }
  private remove(v: Voice) {
    const ctx = this.context;
    if (!ctx) return;
    v.gain.gain.setTargetAtTime(0, ctx.currentTime, 0.025);
    v.source.stop(ctx.currentTime + 0.15);
    v.source.onended = () => {
      v.source.disconnect();
      v.gain.disconnect();
      v.panner.disconnect();
    };
  }
  sync(points: Point[], rules: Rules, settings: Settings) {
    if (!this.active || !this.context) return;
    const ids = new Set(points.map((p) => p.id));
    for (const [id, v] of this.voices) {
      if (!ids.has(id)) {
        this.remove(v);
        this.voices.delete(id);
      }
    }
    const normalize = settings.compensate
      ? 1 / Math.sqrt(Math.max(1, points.length))
      : 0.1;
    this.setVolume(settings.volume);
    for (const p of points) {
      const rule = resolveRule(rules, p);
      let voice = this.voices.get(p.id);
      if (voice && voice.signature !== this.signature(rule)) {
        this.remove(voice);
        this.voices.delete(p.id);
        voice = undefined;
      }
      if (!voice) {
        voice = this.create(p, rule);
        this.voices.set(p.id, voice);
      }
      const isWall =
        p.category === "wall" && !p.objectId.startsWith("ceiling-");
      const wallScale = isWall ? settings.wallPointVolume / 100 : 1;
      voice.position = p.position;
      const voiceNormalize = p.category === "unknown" ? 1 : normalize;
      voice.baseGain =
        rule.gain * voiceNormalize * wallScale * (isWall ? this.wallDuckFactor : 1);
      voice.radius = p.category === "unknown" ? 5 : settings.objectRanges[p.objectId] ?? settings.range;
      this.updateDistanceGain(voice);
    }
  }
  syncWallCues(
    points: Point[],
    rules: Rules,
    settings: Settings,
    listener: THREE.Vector3,
  ) {
    if (!this.active || !this.context) return;
    const now = this.context.currentTime;
    const wanted = new Set(points.map((p) => p.id));
    for (const [id, voice] of this.wallVoices)
      if (!wanted.has(id)) {
        this.remove(voice);
        this.wallVoices.delete(id);
      }
    for (const point of points) {
      const assigned = resolveRule(rules, point);
      const rule =
        settings.wallCueMode === "pulsed"
          ? { ...assigned, pulse: 0 }
          : assigned;
      const signature = JSON.stringify([
        this.signature(rule),
        settings.wallCueMode,
      ]);
      let voice = this.wallVoices.get(point.id);
      if (voice && voice.signature !== signature) {
        this.remove(voice);
        this.wallVoices.delete(point.id);
        voice = undefined;
      }
      if (!voice) {
        voice = this.create(point, rule, settings.wallCueMode);
        voice.panner.rolloffFactor = 0;
        this.wallVoices.set(point.id, voice);
      }
      voice.panner.positionX.setTargetAtTime(point.position.x, now, 0.08);
      voice.panner.positionY.setTargetAtTime(point.position.y, now, 0.08);
      voice.panner.positionZ.setTargetAtTime(point.position.z, now, 0.08);
      // Keep distant references audible rather than cutting them at the point-layer radius.
      const distance = point.position.distanceTo(listener);
      const attenuation = 0.2 + 0.8 / (1 + distance * 0.18);
      voice.gain.gain.setTargetAtTime(
        rule.gain *
          (settings.wallCueVolume / 100) *
          0.65 *
          attenuation *
          this.wallDuckFactor,
        now,
        0.08,
      );
    }
  }
  private updateDistanceGain(voice: Voice) {
    if (
      !this.context ||
      !voice.position ||
      voice.baseGain === undefined ||
      voice.radius === undefined
    )
      return;
    const target =
      voice.baseGain *
      distanceFade(
        voice.position.distanceTo(this.listenerPosition),
        voice.radius,
      );
    if (
      voice.lastGain === undefined ||
      (voice.lastGain === 0 && target > 0) ||
      (target === 0 && voice.lastGain !== 0) ||
      Math.abs(target - voice.lastGain) > Math.max(1e-7, voice.baseGain * 0.002)
    ) {
      voice.gain.gain.setTargetAtTime(target, this.context.currentTime, 0.045);
      voice.lastGain = target;
    }
  }
  listener(camera: THREE.PerspectiveCamera) {
    if (!this.context) return;
    this.listenerPosition.copy(camera.position);
    if (
      this.context.currentTime - this.lastDistanceUpdate >= 1 / 30 &&
      camera.position.distanceToSquared(this.lastDistancePosition) > 1e-6
    ) {
      for (const voice of this.voices.values()) this.updateDistanceGain(voice);
      this.lastDistancePosition.copy(camera.position);
      this.lastDistanceUpdate = this.context.currentTime;
    }
    const l = this.context.listener;
    const p = camera.position;
    const forward = new THREE.Vector3(0, 0, -1).applyQuaternion(
        camera.quaternion,
      ),
      up = new THREE.Vector3(0, 1, 0).applyQuaternion(camera.quaternion);
    const t = this.context.currentTime;
    l.positionX.setValueAtTime(p.x, t);
    l.positionY.setValueAtTime(p.y, t);
    l.positionZ.setValueAtTime(p.z, t);
    l.forwardX.setValueAtTime(forward.x, t);
    l.forwardY.setValueAtTime(forward.y, t);
    l.forwardZ.setValueAtTime(forward.z, t);
    l.upX.setValueAtTime(up.x, t);
    l.upY.setValueAtTime(up.y, t);
    l.upZ.setValueAtTime(up.z, t);
  }
  async preview(rule: Rule, camera?: THREE.PerspectiveCamera, azimuth = 0) {
    const wasActive = this.active;
    await this.start();
    this.active = wasActive;
    const ctx = this.context!;
    const s = ctx.createBufferSource();
    s.buffer = this.buffer(rule, 947);
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, ctx.currentTime);
    g.gain.linearRampToValueAtTime(0.13, ctx.currentTime + 0.05);
    g.gain.setTargetAtTime(0, ctx.currentTime + 0.65, 0.1);
    let panner: PannerNode | null = null;
    if (camera) {
      this.listener(camera);
      panner = ctx.createPanner();
      panner.panningModel = "HRTF";
      const offset = new THREE.Vector3(
        Math.sin(azimuth) * 2,
        0,
        -Math.cos(azimuth) * 2,
      )
        .applyQuaternion(camera.quaternion)
        .add(camera.position);
      panner.positionX.value = offset.x;
      panner.positionY.value = offset.y;
      panner.positionZ.value = offset.z;
      s.connect(g).connect(panner).connect(this.master!);
    } else s.connect(g).connect(this.master!);
    s.start();
    s.stop(ctx.currentTime + 1.1);
    this.previewSources.add(s);
    s.onended = () => {
      s.disconnect();
      g.disconnect();
      panner?.disconnect();
      this.previewSources.delete(s);
    };
  }
  dispose() {
    this.stop();
    this.bufferCache.clear();
    void this.context?.close();
  }
}
