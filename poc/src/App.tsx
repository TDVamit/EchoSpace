import { useEffect, useRef, useState } from "react";
import {
  AudioLines,
  ArrowUpRight,
  ScanLine,
  Pause,
  Play,
  Volume2,
  VolumeX,
  RotateCcw,
  MousePointer2,
  Headphones,
  SlidersHorizontal,
  Box,
  ChevronDown,
  ChevronRight,
  Focus,
  Radio,
  Map,
  MoveUpRight,
  Info,
  Check,
  Trash2,
  Eye,
  EyeOff,
} from "lucide-react";
import { Simulator } from "./engine";
import {
  categories,
  colors,
  defaultRules,
  initialStats,
  type Settings,
  type Stats,
  type Rules,
  type Rule,
  type Category,
  type WorldObject,
  type Texture,
} from "./types";
const fmt = (n: number) => n.toLocaleString();
const labelCategory = (category: Category) => category === "studio-cabin" ? "Studio cabin" : category === "unknown" ? "Unidentified" : category;
const initialSettings: Settings = {
  density: 100,
  volume: 35,
  range: 8,
  objectRanges: {},
  budget: 64,
  compensate: true,
  throughWalls: true,
  view: "overlay",
  cameraMode: "walk",
  pointSize: 3.5,
  blind: false,
  spatialNoise: false,
  hearFloor: false,
  hearCeiling: false,
  wallCues: false,
  wallCueVolume: 30,
  wallCueMode: "continuous",
  wallPointVolume: 45,
  autoQuietWalls: false,
};
function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? { ...fallback, ...JSON.parse(raw) } : fallback;
  } catch {
    return fallback;
  }
}
function Slider({
  label,
  value,
  min = 0,
  max = 100,
  step = 1,
  onChange,
  suffix = "",
  description,
}: {
  label: string;
  value: number;
  min?: number;
  max?: number;
  step?: number;
  onChange: (v: number) => void;
  suffix?: string;
  description?: string;
}) {
  return (
    <div className="slider-group">
      <label>
        <span>{label}</span>
        <output>
          {value}
          {suffix}
        </output>
        <input
          aria-label={label}
          type="range"
          min={min}
          max={max}
          step={step}
          value={value}
          onChange={(e) => onChange(Number(e.target.value))}
          style={
            {
              "--fill": `${((value - min) / (max - min)) * 100}%`,
            } as React.CSSProperties
          }
        />
      </label>
      {description && <p>{description}</p>}
    </div>
  );
}
function TopViewPanel({ mount, stats }: { mount: React.RefObject<HTMLDivElement | null>; stats: Stats }) {
  const room = stats.position[2] < -28 ? "Studio" : stats.position[2] < -15 ? "Connecting corridor" : "Main room";
  return (
    <section className="top-view-panel" aria-label="Live overhead room view">
      <div className="top-view-heading">
        <Map size={14} />
        <span>Live top view</span>
        <span className="top-view-status">{stats.mode === "full" ? "Full scan" : "Live"}</span>
      </div>
      <div ref={mount} className="top-view-render" role="img" aria-label="Live overhead room and point cloud view" />
      <div className="top-view-meta">
        <span>{room}</span>
        <span>{fmt(stats.stored)} points · {stats.position[0].toFixed(1)}, {stats.position[2].toFixed(1)} m</span>
      </div>
    </section>
  );
}

function RoomViewPanel({ mount, target, identifiedCount }: { mount: React.RefObject<HTMLDivElement | null>; target: WorldObject | undefined; identifiedCount: number }) {
  return (
    <section className="room-view-panel" aria-label="Room view with simulated object detection">
      <div className="room-view-heading">
        <Box size={14} />
        <span>Room view</span>
        <span className={`room-view-target ${target?.category === "unknown" ? "unknown" : ""}`}>
          {target ? target.name : `${identifiedCount} detected`}
        </span>
      </div>
      <div ref={mount} className="room-view-render" role="img" aria-label="Live room view with object detection boxes" />
    </section>
  );
}

function App() {
  const viewport = useRef<HTMLDivElement>(null),
    overview = useRef<HTMLDivElement>(null),
    roomViewport = useRef<HTMLDivElement>(null),
    sim = useRef<Simulator | null>(null);
  const [settings, setSettings] = useState<Settings>(() => {
    const loaded: Settings = (() => {
      // Preserve existing controls, but migrate away from the regressed audio defaults once.
      try {
        if (localStorage.getItem("echospace.settings.v6"))
          return load("echospace.settings.v6", initialSettings);
        if (localStorage.getItem("echospace.settings.v5")) {
          const previous = load("echospace.settings.v5", initialSettings);
          return {
            ...previous,
            wallPointVolume:
              previous.wallPointVolume === 15 ? 45 : previous.wallPointVolume,
            autoQuietWalls: false,
          };
        }
        if (localStorage.getItem("echospace.settings.v4")) {
          const previous = load("echospace.settings.v4", initialSettings);
          return {
            ...previous,
            wallCues: false,
            objectRanges: {},
            wallPointVolume:
              previous.wallPointVolume === 15 ? 45 : previous.wallPointVolume,
            autoQuietWalls: false,
          };
        }
        const previous = load<Settings>(
          "echospace.settings.v3",
          load<Settings>("echospace.settings.v2", {
            ...load<Settings>("echospace.settings.v1", initialSettings),
            spatialNoise: false,
            hearFloor: false,
            hearCeiling: false,
          }),
        );
        return {
          ...previous,
          wallCues: false,
          objectRanges: {},
          wallCueMode: "continuous",
          wallPointVolume:
            previous.wallPointVolume === 15 ? 45 : previous.wallPointVolume,
          autoQuietWalls: false,
        };
      } catch {
        return initialSettings;
      }
    })();
    // Migrate existing experiments to a conservative voice ceiling. HRTF
    // panners are expensive, especially when the live scanner is also active.
    return { ...loaded, budget: Math.min(64, loaded.budget) };
  });
  const [rules, setRules] = useState<Rules>(() => {
    const saved = load<Rules>("echospace.rules.v1", defaultRules);
    return {
      categories: { ...defaultRules.categories, ...saved.categories },
      instances: saved.instances ?? {},
    };
  });
  const [stats, setStats] = useState<Stats>(initialStats),
    [objects, setObjects] = useState<WorldObject[]>([]),
    [tab, setTab] = useState<"sound" | "objects">("sound");
  const [audioOn, setAudioOn] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [selected, setSelected] = useState<string>(""),
    [scope, setScope] = useState<"category" | "instance">("category"),
    [category, setCategory] = useState<Category>("wall"),
    [advanced, setAdvanced] = useState(false),
    [identifying, setIdentifying] = useState(""),
    [toast, setToast] = useState("");
  useEffect(() => {
    if (!viewport.current || !overview.current || !roomViewport.current) return;
    try {
      const engine = new Simulator(viewport.current, overview.current, roomViewport.current, settings, rules, (s) => {
        setStats(s);
        setAudioOn(engine.audio.active);
      });
      sim.current = engine;
      setObjects(engine.world.objects);
      return () => {
        engine.dispose();
        sim.current = null;
      };
    } catch (e) {
      setError(
        `3D view could not start: ${e instanceof Error ? e.message : String(e)}. Try a WebGL-enabled desktop browser.`,
      );
    }
  }, []);
  useEffect(() => {
    sim.current?.updateSettings(settings);
    try {
      localStorage.setItem("echospace.settings.v6", JSON.stringify(settings));
    } catch {
      /* private browsing */
    }
  }, [settings]);
  useEffect(() => {
    sim.current?.updateRules(rules);
    try {
      localStorage.setItem("echospace.rules.v1", JSON.stringify(rules));
    } catch {
      /* private browsing */
    }
  }, [rules]);
  useEffect(() => {
    sim.current?.select(selected || null);
  }, [selected]);
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(""), 2500);
    return () => clearTimeout(timer);
  }, [toast]);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (
        event.code !== "KeyB" ||
        event.repeat ||
        event.ctrlKey ||
        event.metaKey ||
        event.altKey ||
        target?.closest("input, select, textarea, [contenteditable=true]")
      )
        return;
      event.preventDefault();
      setSettings((s) => ({ ...s, blind: !s.blind }));
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
  useEffect(() => {
    if (settings.blind) {
      setTab("sound");
      setScope("category");
      setSelected("");
    }
  }, [settings.blind]);
  const change = <K extends keyof Settings>(key: K, value: Settings[K]) =>
    setSettings((s) => ({ ...s, [key]: value }));
  const toggleAudio = async () => {
    if (!sim.current) return;
    if (audioOn) {
      sim.current.audio.stop();
      setAudioOn(false);
      return;
    }
    setBusy(true);
    setError("");
    try {
      await sim.current.audio.start();
      setAudioOn(true);
    } catch (e) {
      setError(
        `Audio could not start: ${e instanceof Error ? e.message : String(e)}. Press Start audio to retry.`,
      );
    } finally {
      setBusy(false);
    }
  };
  const testDirection = async (azimuth: number) => {
    if (!sim.current) return;
    try {
      await sim.current.audio.preview(
        { ...rules.categories.wall, texture: "noise", pulse: 0 },
        sim.current.camera,
        azimuth,
      );
      setToast(
        azimuth === 0
          ? "Test sound: directly ahead"
          : "Test sound: directly behind",
      );
    } catch (e) {
      setError(`Direction test unavailable: ${String(e)}`);
    }
  };
  const object = objects.find((o) => o.id === selected);
  const rule =
    scope === "instance" && object
      ? (rules.instances[object.id] ?? rules.categories[object.category])
      : rules.categories[category];
  const updateRule = (patch: Partial<Rule>) =>
    setRules((r) =>
      scope === "instance" && object
        ? {
            ...r,
            instances: { ...r.instances, [object.id]: { ...rule, ...patch } },
          }
        : {
            ...r,
            categories: { ...r.categories, [category]: { ...rule, ...patch } },
          },
    );
  const selectObject = (o: WorldObject) => {
    setSelected(o.id);
    setCategory(o.category);
    setScope("instance");
  };
  const detected = objects.filter((o) => stats.detected.includes(o.id)).sort((a, b) => Number(b.category === "unknown") - Number(a.category === "unknown"));
  const detectedObjectsCount = detected.filter((o) => o.category !== "wall" && o.category !== "floor").length;
  const target = objects.find((o) => o.id === stats.target);
  const identifyTarget = async (o: WorldObject) => {
    if (identifying || o.category !== "unknown") return;
    setIdentifying(o.id);
    await new Promise((resolve) => setTimeout(resolve, 1200));
    if (sim.current?.identifyStudioCabin(o.id)) {
      setObjects([...sim.current.world.objects]);
      setSelected(o.id);
      setCategory("studio-cabin");
      setScope("instance");
      setTab("objects");
      setToast("Simulated AI identified it as a studio cabin");
    }
    setIdentifying("");
  };
  const capped = stats.requested > settings.budget;
  const preview = async () => {
    try {
      await sim.current?.audio.preview(rule);
      setToast("Playing sound preview");
    } catch (e) {
      setError(`Preview unavailable: ${String(e)}`);
    }
  };
  return (
    <div className="app-shell">
      <header className="app-header">
        <a className="brand" href="/" aria-label="EchoSpace home">
          <span className="brand-symbol">
            <AudioLines size={22} />
          </span>
          <span>
            EchoSpace<span className="brand-dot">.</span>
          </span>
        </a>
        <div className="header-title">
          Spatial audio lab <span>Browser prototype</span>
        </div>
        <a
          className="spec-link"
          href="/POC_SPEC.md"
          target="_blank"
          rel="noreferrer"
        >
          Read the spec <ArrowUpRight size={15} />
        </a>
      </header>
      <main className="workspace">
        <section className="scene-panel" aria-label="Room simulator">
          <div className="scene-toolbar">
            <div className="scene-name">
              <Box size={18} />
              <strong>The connected rooms</strong>
              <span>Indoor test environment</span>
            </div>
            <button
              className={`blind-toggle ${settings.blind ? "active" : ""}`}
              aria-pressed={settings.blind}
              onClick={() => change("blind", !settings.blind)}
              title="Toggle blind mode (B)"
            >
              {settings.blind ? <Eye size={14} /> : <EyeOff size={14} />}
              {settings.blind ? "Show viewer" : "Blind mode"}
            </button>
            <div className="view-switch camera-switch" aria-label="Camera view">
              <button
                className={settings.cameraMode === "walk" ? "active" : ""}
                aria-pressed={settings.cameraMode === "walk"}
                onClick={() => change("cameraMode", "walk")}
              >
                Walk
              </button>
              <button
                className={settings.cameraMode === "top" ? "active" : ""}
                aria-pressed={settings.cameraMode === "top"}
                onClick={() => change("cameraMode", "top")}
              >
                Top view
              </button>
            </div>
            <div className="view-switch" aria-label="Viewport style">
              {(["scene", "overlay", "points"] as const).map((v) => (
                <button
                  key={v}
                  className={settings.view === v ? "active" : ""}
                  aria-pressed={settings.view === v}
                  disabled={settings.blind}
                  onClick={() => change("view", v)}
                >
                  {v === "scene"
                    ? "Room"
                    : v === "overlay"
                      ? "Room + points"
                      : "Points only"}
                </button>
              ))}
            </div>
          </div>
          <div
            className={`viewport-wrap ${settings.blind ? "blind-mode" : ""}`}
          >
            <div
              ref={viewport}
              className="viewport"
              aria-hidden={settings.blind}
            />
            {settings.blind && (
              <div className="blind-message" role="status">
                <EyeOff size={24} />
                <strong>Blind mode</strong>
                <span>
                  The viewer is off. Movement, scanning and audio continue.
                </span>
                <small>Press B to reveal the room.</small>
              </div>
            )}
            <div className="view-top">
              <span className={`state-tag ${stats.scanning ? "scanning" : ""}`}>
                <span />
                {stats.mode === "full"
                  ? "Fully scanned"
                  : stats.scanning
                    ? "Scanning live"
                    : stats.stored
                      ? "Scan paused"
                      : "Ready to explore"}
              </span>
              <span className="synthetic-tag">
                Synthetic scene · simulated detection
              </span>
            </div>
            {!settings.blind && settings.cameraMode === "walk" && (
              <div className="crosshair">
                <i />
                <i />
              </div>
            )}
            {!settings.blind && settings.cameraMode === "walk" && target && (
              <div className="target-label">
                <button className="target-select" onClick={() => { selectObject(target); setTab("objects"); }}>
                  <span className="category-dot" style={{ background: colors[target.category] }} />
                  <span>{target.name}</span>
                  <span>{stats.distance.toFixed(1)} m{stats.distance > (settings.objectRanges[target.id] ?? settings.range) ? " · outside hearing range" : ""}</span>
                  <Focus size={13} />
                </button>
                {target.category === "unknown" && stats.detected.includes(target.id) && (
                  <button className="identify-object" onClick={() => void identifyTarget(target)} disabled={!!identifying}>
                    {identifying === target.id ? "Identifying…" : "Identify with AI"}
                  </button>
                )}
              </div>
            )}
            {!stats.locked && settings.cameraMode === "walk" && (
              <div className="enter-prompt">
                <button
                  className="enter-button"
                  onClick={() => sim.current?.enter()}
                  disabled={!!error && !sim.current}
                >
                  <MousePointer2 size={17} />
                  Enter room
                  <ArrowUpRight size={16} />
                </button>
                <span>Mouse turns left/right · Esc returns to controls</span>
              </div>
            )}
            {stats.locked && (
              <div className="locked-hint">
                WASD / arrows to move <span>Mouse turns left/right</span> Esc to
                release mouse
              </div>
            )}
            {settings.cameraMode === "top" && !settings.blind && (
              <div className="overhead-hint">
                OVERHEAD FOLLOW · WASD / ARROWS MOVE · Q / E TURN · SCAN FOLLOWS
                PLAYER HEADING
              </div>
            )}
            {!settings.blind && (
              <div className="room-label">
                <span>
                  {stats.position[2] < -28
                    ? "Studio"
                    : stats.position[2] < -15
                      ? "Connecting corridor"
                      : "Main room"}
                </span>
                <small>{settings.cameraMode === "top" ? "Overhead follow view" : "Eye height 1.65 m"}</small>
              </div>
            )}
            <button
              className="reset-position"
              title="Return to starting position"
              aria-label="Return to starting position"
              onClick={() => {
                sim.current?.resetPosition();
                setToast("Returned to the entrance");
              }}
            >
              <RotateCcw size={16} />
            </button>
          </div>
          <div className="scene-actions">
            <div className="scan-actions">
              <button
                className="primary"
                onClick={() => sim.current?.toggleScan()}
                disabled={!objects.length}
              >
                {stats.scanning ? <Pause size={16} /> : <ScanLine size={16} />}{" "}
                {stats.scanning ? "Pause scan" : "Start scan"}
              </button>
              <button
                className="secondary"
                onClick={() => {
                  sim.current?.setMode("full");
                  setToast("All room surfaces are now scanned");
                }}
                disabled={!objects.length}
              >
                <Map size={16} />
                Load full scan
              </button>
              <button
                className="icon-button clear-button"
                title="Clear scan and return to new-space mode"
                aria-label="Clear scan and return to new-space mode"
                onClick={() => {
                  sim.current?.setMode("new");
                  setSelected("");
                  setToast("Scan cleared. Ready for a new space.");
                }}
              >
                <Trash2 size={16} />
              </button>
            </div>
            <button
              className={`audio-button ${audioOn ? "on" : ""}`}
              onClick={() => void toggleAudio()}
              disabled={busy || !objects.length}
            >
              {audioOn ? <Volume2 size={17} /> : <VolumeX size={17} />}{" "}
              {busy ? "Starting…" : audioOn ? "Stop audio" : "Start audio"}
              <span className="audio-led" />
            </button>
          </div>
          <div className="telemetry" aria-label="Live diagnostics">
            <div>
              <span>Scanned points</span>
              <strong>{fmt(stats.stored)}</strong>
            </div>
            <div>
              <span>Requested audio</span>
              <strong>{fmt(stats.requested)}</strong>
            </div>
            <div className={capped ? "capped-metric" : ""}>
              <span>Sounding points</span>
              <strong>
                {fmt(stats.active)}{" "}
                <small>
                  / {fmt(settings.budget)} cap
                  {stats.wallCues > 0 ? ` · ${stats.wallCues} wall cues` : ""}
                </small>
              </strong>
            </div>
            <div>
              <span>Rendering</span>
              <strong>
                {settings.blind ? "Off" : stats.fps}
                <small>{settings.blind ? " viewer paused" : " fps"}</small>
              </strong>
            </div>
            <div className="output-meter">
              <span>Output level</span>
              <div className="meter-track">
                <i
                  style={{
                    width: "100%",
                    transform: `scaleX(${Math.min(1, stats.level)})`,
                  }}
                />
              </div>
            </div>
          </div>
          <div className="scene-note">
            <Headphones size={15} />
            <span>
              Stereo headphones recommended. Every active point has its own
              spatial sound.
            </span>
            <span className="local-label">Runs locally</span>
          </div>
        </section>
        <aside className="control-panel" aria-label="Experiment controls">
          <div className="panel-heading">
            <h1>Shape what you hear.</h1>
            <p>Explore the space. Find your sound.</p>
          </div>
          <div className="observation-views">
            <TopViewPanel mount={overview} stats={stats} />
            <RoomViewPanel mount={roomViewport} target={target} identifiedCount={detectedObjectsCount} />
          </div>
          <div
            className="panel-tabs"
            role="tablist"
            aria-label="Control panels"
          >
            <button
              role="tab"
              aria-selected={tab === "sound"}
              aria-controls="sound-controls"
              id="sound-tab"
              className={tab === "sound" ? "active" : ""}
              onClick={() => {
                setTab("sound");
                setScope("category");
                setSelected("");
              }}
            >
              <SlidersHorizontal size={15} />
              Sound controls
            </button>
            <button
              role="tab"
              aria-selected={tab === "objects"}
              aria-controls="object-controls"
              id="objects-tab"
              className={tab === "objects" ? "active" : ""}
              disabled={settings.blind}
              title={
                settings.blind
                  ? "Object labels are hidden during blind mode"
                  : undefined
              }
              onClick={() => setTab("objects")}
            >
              <Box size={15} />
              Objects <span>{detected.length}</span>
            </button>
          </div>
          <div className="panel-scroll">
            {tab === "sound" ? (
              <div
                id="sound-controls"
                role="tabpanel"
                aria-labelledby="sound-tab"
              >
                <section className="control-section">
                  <div className="section-title">
                    <h2>Point soundscape</h2>
                    <span className="small-label">Direct mode</span>
                  </div>
                  <Slider
                    label="Audio density"
                    value={settings.density}
                    onChange={(v) => change("density", v)}
                    suffix="%"
                    description="Scales point contributions and the available voice budget."
                  />
                  <div className="density-scale">
                    <span>Sparse</span>
                    <span>Dense</span>
                  </div>
                  <Slider
                    label="Master volume"
                    value={settings.volume}
                    onChange={(v) => change("volume", v)}
                    suffix="%"
                  />
                  <Slider
                    label="Hearing distance"
                    value={settings.range}
                    min={1}
                    max={30}
                    onChange={(v) => change("range", v)}
                    suffix=" m"
                    description="Objects fade in from silence at this distance and grow louder as you approach."
                  />
                  <Slider
                    label="Wall point volume"
                    value={settings.wallPointVolume}
                    onChange={(v) => change("wallPointVolume", v)}
                    suffix="%"
                    description="Scales scanned wall sounds independently from other objects."
                  />
                  <label className="check-field">
                    <input
                      type="checkbox"
                      checked={settings.autoQuietWalls}
                      onChange={(e) =>
                        change("autoQuietWalls", e.target.checked)
                      }
                    />
                    <span>Quiet walls near objects</span>
                  </label>
                  <div
                    className="surface-toggles"
                    aria-label="Audible surfaces"
                  >
                    <label className="check-field">
                      <input
                        type="checkbox"
                        checked={settings.hearFloor}
                        onChange={(e) => change("hearFloor", e.target.checked)}
                      />
                      <span>Hear floor</span>
                    </label>
                    <label className="check-field">
                      <input
                        type="checkbox"
                        checked={settings.hearCeiling}
                        onChange={(e) =>
                          change("hearCeiling", e.target.checked)
                        }
                      />
                      <span>Hear ceiling</span>
                    </label>
                    <p className="direction-help">
                      Only affects audio. Both surfaces remain visible and
                      scannable.
                    </p>
                  </div>
                  <div className={`budget-note ${capped ? "is-capped" : ""}`}>
                    <Info size={15} />
                    <span>
                      {capped
                        ? `${fmt(stats.requested)} point sources requested; up to ${fmt(Math.ceil((Math.max(0, settings.budget - (settings.wallCues ? stats.knownWalls : 0)) * settings.density) / 100))} point voices + ${settings.wallCues ? stats.knownWalls : 0} wall cues.`
                        : "Each active point uses a separate binaural audio source."}
                    </span>
                  </div>
                </section>
                <section className="control-section rule-section">
                  <div className="section-title">
                    <h2>Sound assignments</h2>
                    <span className="small-label">Saved locally</span>
                  </div>
                  <div className="field">
                    <label htmlFor="rule-category">Object category</label>
                    <select
                      id="rule-category"
                      value={category}
                      onChange={(e) => {
                        setCategory(e.target.value as Category);
                        setScope("category");
                        setSelected("");
                      }}
                    >
                      {categories.map((c) => (
                        <option key={c} value={c}>
                          {labelCategory(c as Category)}
                        </option>
                      ))}
                    </select>
                  </div>
                  {ruleEditor()}
                </section>
              </div>
            ) : (
              <div
                id="object-controls"
                role="tabpanel"
                aria-labelledby="objects-tab"
              >
                <section className="control-section">
                  <div className="section-title">
                    <h2>Observed objects</h2>
                    <span className="small-label">Simulated YOLO</span>
                  </div>
                  <p className="section-description">
                    Labels come from the scene, not a vision model. Scan a
                    surface to discover it.
                  </p>
                  <div className="object-list">
                    {detected.length === 0 ? (
                      <div className="empty-objects">
                        <ScanLine size={24} />
                        <strong>No objects scanned yet</strong>
                        <p>
                          Start a scan or load the full room to assign object
                          sounds.
                        </p>
                      </div>
                    ) : (
                      detected.map((o) => (
                        <button
                          key={o.id}
                          className={`object-row ${selected === o.id ? "selected" : ""}`}
                          onClick={() => selectObject(o)}
                        >
                          <span
                            className="category-dot"
                            style={{ background: colors[o.category] }}
                          />
                          <span>
                            <strong>{o.name}</strong>
                            <small>
                              {labelCategory(o.category)}
                              {rules.instances[o.id] ? " · custom sound" : ""}
                            </small>
                          </span>
                          {selected === o.id ? (
                            <Check size={14} />
                          ) : (
                            <ChevronRight size={14} />
                          )}
                        </button>
                      ))
                    )}
                  </div>
                </section>
                {object && (
                  <section className="control-section">
                    <div className="section-title">
                      <h2>{object.name}</h2>
                      <Focus size={15} />
                    </div>
                    {object.category === "unknown" && (
                      <button className="identify-panel-button" onClick={() => void identifyTarget(object)} disabled={!!identifying}>
                        <Radio size={14} />
                        {identifying === object.id ? "Identifying with AI…" : "Identify with AI"}
                      </button>
                    )}
                    <div className="scope-switch">
                      <button
                        className={scope === "instance" ? "active" : ""}
                        onClick={() => setScope("instance")}
                      >
                        This object
                      </button>
                      <button
                        className={scope === "category" ? "active" : ""}
                        onClick={() => setScope("category")}
                      >
                        All {labelCategory(object.category)} sounds
                      </button>
                    </div>
                    <Slider
                      label="This object hearing distance"
                      value={settings.objectRanges[object.id] ?? settings.range}
                      min={1}
                      max={60}
                      onChange={(v) =>
                        setSettings((s) => ({
                          ...s,
                          objectRanges: { ...s.objectRanges, [object.id]: v },
                        }))
                      }
                      suffix=" m"
                      description={
                        settings.objectRanges[object.id] === undefined
                          ? `Using the global ${settings.range} m distance.`
                          : "Only this detected object uses this distance."
                      }
                    />
                    {settings.objectRanges[object.id] !== undefined && (
                      <button
                        className="text-button"
                        onClick={() =>
                          setSettings((s) => {
                            const objectRanges = { ...s.objectRanges };
                            delete objectRanges[object.id];
                            return { ...s, objectRanges };
                          })
                        }
                      >
                        Use global hearing distance
                      </button>
                    )}
                    {ruleEditor()}
                    {rules.instances[object.id] && (
                      <button
                        className="text-button"
                        onClick={() => {
                          setRules((r) => {
                            const instances = { ...r.instances };
                            delete instances[object.id];
                            return { ...r, instances };
                          });
                          setToast("Object uses its category sound again");
                        }}
                      >
                        Remove object override
                      </button>
                    )}
                  </section>
                )}
              </div>
            )}
            <section className="control-section advanced-section">
              <button
                className="advanced-toggle"
                onClick={() => setAdvanced((v) => !v)}
                aria-expanded={advanced}
              >
                <span>
                  <SlidersHorizontal size={14} />
                  Experiment settings
                </span>
                <ChevronDown size={15} className={advanced ? "rotated" : ""} />
              </button>
              {advanced && (
                <div className="advanced-body">
                  <div className="field">
                    <label htmlFor="voice-budget">
                      Simultaneous source cap
                    </label>
                    <select
                      id="voice-budget"
                      value={settings.budget}
                      onChange={(e) => change("budget", Number(e.target.value))}
                    >
                      {[32, 48, 64].map((n) => (
                        <option key={n} value={n}>
                          {fmt(n)} individual points
                        </option>
                      ))}
                    </select>
                    <p>
                      The POC limits binaural voices to 64 to keep live scanning
                      responsive. The density slider selects how many of those
                      points sound at once.
                    </p>
                  </div>
                  <label className="check-field">
                    <input
                      type="checkbox"
                      checked={settings.spatialNoise}
                      onChange={(e) => change("spatialNoise", e.target.checked)}
                    />
                    <span>Broadband noise (experimental)</span>
                  </label>
                  <p className="direction-help">
                    Keeps high frequencies in wall/noise sounds for front/back
                    and height cues. Front/back remains dependent on your
                    headphones and hearing. Turn the view with your mouse to
                    compare.
                  </p>
                  <div className="direction-tests">
                    <button onClick={() => void testDirection(0)}>
                      <Headphones size={13} />
                      Test ahead
                    </button>
                    <button onClick={() => void testDirection(Math.PI)}>
                      <Headphones size={13} />
                      Test behind
                    </button>
                  </div>
                  <label className="check-field">
                    <input
                      type="checkbox"
                      checked={settings.compensate}
                      onChange={(e) => change("compensate", e.target.checked)}
                    />
                    <span>Compensate for density loudness</span>
                  </label>
                  <label className="check-field">
                    <input
                      type="checkbox"
                      checked={settings.throughWalls}
                      onChange={(e) => change("throughWalls", e.target.checked)}
                    />
                    <span>Hear stored points through walls</span>
                  </label>
                  <Slider
                    label="Point display size"
                    min={1}
                    max={8}
                    step={0.5}
                    value={settings.pointSize}
                    onChange={(v) => change("pointSize", v)}
                  />
                  <button
                    className="text-button"
                    onClick={() => {
                      setRules(structuredClone(defaultRules));
                      setSettings(initialSettings);
                      setToast("Sound rules and controls reset");
                    }}
                  >
                    <RotateCcw size={13} />
                    Reset sound settings
                  </button>
                </div>
              )}
            </section>
            <div className="help-block">
              <Radio size={17} />
              <div>
                <strong>A sound for every point.</strong>
                <p>
                  Nearby points are louder. Object rules change their texture.
                  Try the same room at different densities.
                </p>
              </div>
            </div>
          </div>
          <div className="panel-footer">
            <span className="status-dot" />
            Local simulation <span>v0.1</span>
          </div>
        </aside>
      </main>
      <footer className="app-footer">
        <span>
          <span className="keycap">W A S D</span> or arrows to move{" "}
          <span className="keycap">Mouse</span> to look
        </span>
        <span>
          Depth → points → spatial sound <MoveUpRight size={13} />
        </span>
      </footer>
      {error && (
        <div className="error-banner" role="alert">
          <span>{error}</span>
          <button onClick={() => setError("")}>Dismiss</button>
        </div>
      )}
      {toast && (
        <div className="toast" role="status">
          <Check size={15} />
          {toast}
        </div>
      )}
    </div>
  );
  function ruleEditor() {
    return (
      <>
        <div className="field">
          <label htmlFor={`texture-${tab}`}>Sound texture</label>
          <select
            id={`texture-${tab}`}
            value={rule.texture}
            onChange={(e) => updateRule({ texture: e.target.value as Texture })}
          >
            <option value="noise">Filtered noise</option>
            <option value="sine">Soft tone</option>
            <option value="triangle">Textured tone</option>
            <option value="bell">Bell / chime</option>
          </select>
        </div>
        <Slider
          label="Frequency"
          min={80}
          max={2000}
          step={10}
          value={rule.frequency}
          onChange={(v) => updateRule({ frequency: v })}
          suffix=" Hz"
        />
        <div className="rule-pair">
          <div className="field">
            <label htmlFor={`gain-${tab}`}>Relative volume</label>
            <select
              id={`gain-${tab}`}
              value={rule.gain}
              onChange={(e) => updateRule({ gain: Number(e.target.value) })}
            >
              {[0.13, 0.28, 0.3, 0.4, 0.45, 0.5, 0.6, 0.7, 1]
                .sort((a, b) => a - b)
                .map((v) => (
                  <option key={v} value={v}>
                    {Math.round(v * 100)}%
                  </option>
                ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor={`pulse-${tab}`}>Pulse rate</label>
            <select
              id={`pulse-${tab}`}
              value={rule.pulse}
              onChange={(e) => updateRule({ pulse: Number(e.target.value) })}
            >
              {[0, 1, 2, 3, 4, 6, 8].map((v) => (
                <option key={v} value={v}>
                  {v === 0 ? "Continuous" : `${v} / sec`}
                </option>
              ))}
            </select>
          </div>
        </div>
        <button className="preview-button" onClick={() => void preview()}>
          <Play size={14} />
          Preview sound
          <span>
            {scope === "instance" && object ? "This object" : category}
          </span>
        </button>
      </>
    );
  }
}
export default App;
